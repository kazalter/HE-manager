import os
import tempfile
import unittest
import zipfile
from datetime import datetime

from PIL import Image
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import models, scanner


class MediaCoreTest(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
        models.Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)

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


if __name__ == "__main__":
    unittest.main()
