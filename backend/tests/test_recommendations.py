import os
import tempfile
import unittest

from PIL import Image
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from app import ai_config, manga_metadata, manga_profiles, models, recommendations


class RecommendationTest(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
        event.listen(self.engine, "connect", self._enable_foreign_keys)
        models.Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)
        self._old_config_dir = ai_config.CONFIG_DIR
        self._old_config_path = ai_config.CONFIG_PATH
        self._old_env = {key: os.environ.get(key) for key in ("DEEPSEEK_API_KEY", "DEEPSEEK_MODEL", "DEEPSEEK_API_BASE")}
        for key in self._old_env:
            os.environ.pop(key, None)
        self._config_tmp = tempfile.TemporaryDirectory()
        ai_config.CONFIG_DIR = self._config_tmp.name
        ai_config.CONFIG_PATH = os.path.join(self._config_tmp.name, "deepseek.json")

    def tearDown(self):
        ai_config.CONFIG_DIR = self._old_config_dir
        ai_config.CONFIG_PATH = self._old_config_path
        for key, value in self._old_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        self._config_tmp.cleanup()

    @staticmethod
    def _enable_foreign_keys(dbapi_connection, connection_record):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.close()

    def test_recommend_manga_honors_avoid_tags(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            good = models.Media(
                folder=folder,
                title="画风精细的短篇",
                relative_path="good.cbz",
                absolute_path="D:\\Manga\\good.cbz",
                media_type="manga",
                extension=".cbz",
                file_size=1024,
                page_count=24,
                rating=4,
            )
            good.tags.append(models.Tag(name="短篇"))
            avoided = models.Media(
                folder=folder,
                title="黑暗剧情短篇",
                relative_path="avoid.cbz",
                absolute_path="D:\\Manga\\avoid.cbz",
                media_type="manga",
                extension=".cbz",
                file_size=1024,
                page_count=20,
                rating=5,
            )
            db.add(folder)
            db.commit()

            result = recommendations.recommend_manga(
                db=db,
                query="想看画风精细短篇",
                limit=5,
                avoid_tags=["黑暗"],
                preferred_tags=[],
            )

            ids = [item["media"].id for item in result["recommendations"]]
            self.assertIn(good.id, ids)
            self.assertNotIn(avoided.id, ids)
        finally:
            db.close()

    def test_deepseek_config_is_saved_without_exposing_key(self):
        saved = ai_config.update_deepseek_config(
            api_key="sk-test",
            model="deepseek-chat",
            base_url="https://api.deepseek.com/",
        )

        self.assertEqual(saved["api_key"], "sk-test")
        self.assertEqual(saved["base_url"], "https://api.deepseek.com")
        self.assertTrue(saved["key_saved"])

        cleared = ai_config.update_deepseek_config(clear_api_key=True)
        self.assertFalse(cleared["key_saved"])
        self.assertEqual(cleared["api_key"], "")

    def test_manga_profile_analyzes_sampled_pages(self):
        db = self.Session()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                for idx, color in enumerate([(245, 245, 245), (20, 20, 20), (200, 200, 210)], start=1):
                    Image.new("RGB", (240, 320), color=color).save(os.path.join(tmp, f"{idx:03d}.jpg"))

                folder = models.Folder(path=tmp, scan_mode="manga")
                media = models.Media(
                    folder=folder,
                    title="sample manga",
                    relative_path=".",
                    absolute_path=tmp,
                    media_type="manga",
                    extension=".dir",
                    file_size=1024,
                    page_count=3,
                    rating=0,
                )
                db.add(folder)
                db.commit()

                profile = manga_profiles.analyze_media(db, media, sample_count=3)
                db.commit()

                self.assertEqual(profile.media_id, media.id)
                self.assertTrue(profile.content_summary)
                self.assertGreaterEqual(len(manga_profiles.json_loads(profile.sampled_pages, [])), 1)
                self.assertIn("aggregate", manga_profiles.json_loads(profile.visual_features, {}))
        finally:
            db.close()

    def test_manga_metadata_profile_parses_title_and_source_item(self):
        db = self.Session()
        try:
            folder = models.Folder(path="D:\\Manga", scan_mode="manga")
            source = models.ExternalFavoriteSource(source_type="wnacg", name="WNACG", favorites_url="https://example.test")
            item = models.ExternalFavoriteItem(
                source=source,
                source_type="wnacg",
                external_id="123",
                title="[Circle (Artist)] Clean Title (Original) [中文]",
                url="https://www.wnacg.com/photos-index-aid-123.html",
                category_name="Favorites",
            )
            media = models.Media(
                folder=folder,
                title="[Circle (Artist)] Clean Title (Original) [中文]",
                relative_path="book",
                absolute_path="D:\\Manga\\book",
                media_type="manga",
                extension=".dir",
                file_size=1024,
                page_count=28,
                source_url=item.url,
                source_site="wnacg",
            )
            db.add_all([folder, source])
            db.commit()

            profile = manga_metadata.build_metadata_profile(db, media)
            db.commit()
            tags = manga_metadata.json_loads(profile.external_tags, [])

            self.assertEqual(profile.media_id, media.id)
            self.assertEqual(profile.language, "中文")
            self.assertIn("length:短篇", tags)
            self.assertTrue(profile.source_matches)
            self.assertGreaterEqual(profile.confidence, 60)
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main()
