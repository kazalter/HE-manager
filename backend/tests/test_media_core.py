import os
import tempfile
import unittest
import zipfile
from datetime import datetime

from PIL import Image
from sqlalchemy import create_engine, event
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import sessionmaker

from app import media_cleanup, models, scanner
from app.dedup import merge as dedup_merge


class MediaCoreTest(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
        event.listen(self.engine, "connect", self._enable_foreign_keys)
        models.Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)

    @staticmethod
    def _enable_foreign_keys(dbapi_connection, connection_record):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.close()

    def test_media_metadata_and_tags_are_persisted(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Library", scan_mode="auto")
            media = models.Media(
                folder=folder,
                title="chapter.cbz",
                relative_path="chapter.cbz",
                absolute_path="D:\\Library\\chapter.cbz",
                media_type="manga",
                extension=".cbz",
                file_size=1024,
                page_count=24,
                rating=4,
                favorite=True,
                view_status="viewing",
                progress=3,
                last_opened_at=datetime.utcnow(),
                source_site="example",
                source_url="https://example.test/item/1",
            )
            media.tags.append(models.Tag(name="作者A"))
            media.tags.append(models.Tag(name="已整理"))
            db.add(folder)
            db.commit()

            saved = db.query(models.Media).one()
            self.assertEqual(saved.page_count, 24)
            self.assertEqual(saved.rating, 4)
            self.assertTrue(saved.favorite)
            self.assertEqual(saved.view_status, "viewing")
            self.assertEqual(saved.progress, 3)
            self.assertEqual({tag.name for tag in saved.tags}, {"作者A", "已整理"})
        finally:
            db.close()

    def test_image_metadata_reads_dimensions(self):
        with tempfile.TemporaryDirectory() as tmp:
            image_path = os.path.join(tmp, "cover.jpg")
            Image.new("RGB", (320, 480), color=(20, 40, 60)).save(image_path)

            metadata = scanner.get_image_metadata(image_path)

            self.assertEqual(metadata["width"], 320)
            self.assertEqual(metadata["height"], 480)

    def test_manga_page_count_supports_directory_and_cbz(self):
        with tempfile.TemporaryDirectory() as tmp:
            Image.new("RGB", (10, 10)).save(os.path.join(tmp, "001.jpg"))
            Image.new("RGB", (10, 10)).save(os.path.join(tmp, "002.png"))
            os.makedirs(os.path.join(tmp, ".he_cover"))
            Image.new("RGB", (10, 10)).save(os.path.join(tmp, ".he_cover", "cover.jpg"))
            with open(os.path.join(tmp, "notes.txt"), "w", encoding="utf-8") as f:
                f.write("not a page")

            self.assertEqual(scanner.count_manga_pages(tmp, ".dir"), 2)

            cbz_path = os.path.join(tmp, "book.cbz")
            with zipfile.ZipFile(cbz_path, "w") as archive:
                archive.write(os.path.join(tmp, "001.jpg"), "001.jpg")
                archive.write(os.path.join(tmp, "002.png"), "002.png")
                archive.write(os.path.join(tmp, "notes.txt"), "notes.txt")

            self.assertEqual(scanner.count_manga_pages(cbz_path, ".cbz"), 2)

    def test_video_progress_can_drive_view_status(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Videos", scan_mode="video")
            media = models.Media(
                folder=folder,
                title="clip.mp4",
                relative_path="clip.mp4",
                absolute_path="D:\\Videos\\clip.mp4",
                media_type="video",
                extension=".mp4",
                file_size=2048,
                duration=100,
                progress=0,
                view_status="unviewed",
            )
            db.add(folder)
            db.commit()

            saved = db.query(models.Media).one()
            saved.progress = 40
            ratio = saved.progress / saved.duration
            saved.view_status = "viewed" if ratio >= 0.95 else "viewing"
            db.commit()

            self.assertEqual(saved.view_status, "viewing")

            saved.progress = 96
            ratio = saved.progress / saved.duration
            saved.view_status = "viewed" if ratio >= 0.95 else "viewing"
            db.commit()

            self.assertEqual(saved.view_status, "viewed")
        finally:
            db.close()

    def test_folder_media_delete_detaches_cross_table_references(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Imported", scan_mode="image")
            media_a = models.Media(
                folder=folder,
                title="a.jpg",
                relative_path="a.jpg",
                absolute_path="D:\\Imported\\a.jpg",
                media_type="image",
                extension=".jpg",
                file_size=100,
            )
            media_b = models.Media(
                folder=folder,
                title="b.jpg",
                relative_path="b.jpg",
                absolute_path="D:\\Imported\\b.jpg",
                media_type="image",
                extension=".jpg",
                file_size=100,
            )
            source = models.XImportSource(name="X")
            post = models.XPost(source=source, tweet_id="1", url="https://x.test/1")
            post.media_items.append(
                models.XMediaItem(
                    media_index=0,
                    media_type="photo",
                    remote_url="https://x.test/a.jpg",
                    status="downloaded",
                )
            )
            db.add_all([folder, source])
            db.commit()

            media_ids = [media_a.id, media_b.id]
            x_item = db.query(models.XMediaItem).one()
            x_item.library_media_id = media_a.id
            db.add(
                models.DuplicateCandidate(
                    existing_media_id=media_a.id,
                    candidate_media_id=media_b.id,
                    level="suspected_duplicate",
                    similarity=90,
                )
            )
            db.commit()

            db.delete(folder)
            with self.assertRaises(IntegrityError):
                db.commit()
            db.rollback()

            media_cleanup.detach_media_references(db, media_ids)
            db.delete(folder)
            db.commit()

            self.assertEqual(db.query(models.Media).count(), 0)
            self.assertIsNone(db.query(models.XMediaItem).one().library_media_id)
            self.assertEqual(db.query(models.DuplicateCandidate).count(), 0)
        finally:
            db.close()

    def test_dedup_merge_transfers_x_media_reference_to_surviving_media(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Imported", scan_mode="image")
            existing = models.Media(
                folder=folder,
                title="keep.jpg",
                relative_path="keep.jpg",
                absolute_path="D:\\Imported\\keep.jpg",
                media_type="image",
                extension=".jpg",
                file_size=100,
            )
            candidate = models.Media(
                folder=folder,
                title="candidate.jpg",
                relative_path="candidate.jpg",
                absolute_path="D:\\Imported\\candidate.jpg",
                media_type="image",
                extension=".jpg",
                file_size=100,
            )
            source = models.XImportSource(name="X")
            post = models.XPost(source=source, tweet_id="1", url="https://x.test/1")
            x_item = models.XMediaItem(
                post=post,
                media_index=0,
                media_type="photo",
                remote_url="https://x.test/candidate.jpg",
                status="downloaded",
            )
            db.add_all([folder, source, existing, candidate, x_item])
            db.flush()
            x_item.library_media_id = candidate.id
            pair = models.DuplicateCandidate(
                existing_media_id=existing.id,
                candidate_media_id=candidate.id,
                level="suspected_duplicate",
                similarity=90,
            )
            db.add(pair)
            db.commit()

            dedup_merge.apply_action(db, pair, dedup_merge.ACTION_KEEP_EXISTING)

            self.assertEqual(db.query(models.Media).count(), 1)
            self.assertEqual(db.query(models.XMediaItem).one().library_media_id, existing.id)
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main()
