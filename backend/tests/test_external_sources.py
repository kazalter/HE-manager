import unittest

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import external_sources, models


class ExternalSourcesTest(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False})
        models.Base.metadata.create_all(bind=self.engine)
        self.Session = sessionmaker(autocommit=False, autoflush=False, bind=self.engine)

    def test_parse_wnacg_favorites_from_anchor_and_image(self):
        html = """
        <div class="box_cel u_listcon">
          <a href="/photos-index-aid-123456.html" title="Fallback title">
            <img src="//img.example.test/cover.jpg" alt="Cover title">
            [Author] Sample Book
          </a>
        </div>
        <div class="box_cel u_listcon">
          <a href="https://www.wnacg.com/photos-index-aid-123456.html">Sample Book</a>
        </div>
        """

        items = external_sources.parse_wnacg_favorites(
            html,
            category_id="7",
            category_name="Default",
        )

        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].external_id, "123456")
        self.assertIn("Sample Book", items[0].title)
        self.assertEqual(items[0].url, "https://www.wnacg.com/photos-index-aid-123456.html")
        self.assertEqual(items[0].cover_url, "https://img.example.test/cover.jpg")
        self.assertEqual(items[0].category_id, "7")

    def test_parse_wnacg_favorites_when_cover_precedes_title(self):
        html = """
        <div class="asTB">
          <div class="asTBcell thumb"><div><img src="//t4.qy0.ru/data/t/3214/23/cover.jpg" /></div></div>
          <div class="box_cel u_listcon">
            <p class="l_catg"><a href="/users-users_fav-c-1505161.html">收藏</a></p>
            <p class="l_title"><a href="/photos-index-aid-321423.html">Sample Favorite</a></p>
          </div>
        </div>
        """

        items = external_sources.parse_wnacg_favorites(html)

        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].external_id, "321423")
        self.assertEqual(items[0].cover_url, "https://t4.qy0.ru/data/t/3214/23/cover.jpg")
        self.assertEqual(items[0].category_id, "1505161")
        self.assertEqual(items[0].category_name, "收藏")

    def test_parse_wnacg_categories(self):
        html = """
        <label class="nav_label">書架分類：</label>
        <a href="/users-users_fav-c-11.html ">默认</a>
        <a href="/users-users_fav-c-12.html ">待看</a>
        """

        categories = external_sources.parse_wnacg_categories(html)

        self.assertEqual([(item.id, item.name) for item in categories], [("11", "默认"), ("12", "待看")])

    def test_parse_wnacg_image_urls_from_reader_script(self):
        script = """
        mReader.initData({"page_url":[
          "http://img5.qy0.ru/data/3214/23/01.jpg",
          "http://img5.qy0.ru/data/3214/23/02.webp",
          "http://img5.qy0.ru/data/3214/23/01.jpg",
        ]});
        """

        urls = external_sources.parse_wnacg_image_urls(script)

        self.assertEqual(
            urls,
            [
                "http://img5.qy0.ru/data/3214/23/01.jpg",
                "http://img5.qy0.ru/data/3214/23/02.webp",
            ],
        )

    def test_external_source_and_items_are_persisted(self):
        db = self.Session()
        try:
            source = models.ExternalFavoriteSource(
                source_type="wnacg",
                name="WNACG",
                favorites_url=external_sources.WNACG_DEFAULT_URL,
                cookie="session=test",
                download_root_path="D:\\ExternalLibrary",
            )
            source.items.append(
                models.ExternalFavoriteItem(
                    source_type="wnacg",
                    external_id="123456",
                    title="Sample Book",
                    url="https://www.wnacg.com/photos-index-aid-123456.html",
                    cover_url="https://img.example.test/cover.jpg",
                    category_id="11",
                    sync_position=3,
                    category_name="默认",
                )
            )
            db.add(source)
            db.commit()

            saved = db.query(models.ExternalFavoriteSource).one()
            self.assertTrue(saved.cookie_saved)
            self.assertEqual(saved.download_root_path, "D:\\ExternalLibrary")
            self.assertEqual(len(saved.items), 1)
            self.assertEqual(saved.items[0].external_id, "123456")
            self.assertEqual(saved.items[0].sync_position, 3)
        finally:
            db.close()


if __name__ == "__main__":
    unittest.main()
