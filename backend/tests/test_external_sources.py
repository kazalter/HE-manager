import unittest
from unittest.mock import patch

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app import external_sources, main as app_main, models, schemas


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

    def test_wnacg_sync_stops_category_after_existing_item(self):
        db = self.Session()
        try:
            source = models.ExternalFavoriteSource(
                source_type="wnacg",
                name="WNACG",
                favorites_url=external_sources.WNACG_DEFAULT_URL,
                cookie="session=test",
            )
            source.items.append(
                models.ExternalFavoriteItem(
                    source_type="wnacg",
                    external_id="1003",
                    title="Already saved",
                    url="https://www.wnacg.com/photos-index-aid-1003.html",
                )
            )
            db.add(source)
            db.commit()
            db.refresh(source)

            calls = []

            def fake_fetch_html(url, cookie):
                calls.append(url)
                if url == external_sources.WNACG_DEFAULT_URL:
                    return '<a href="/users-users_fav-c-7.html">默认</a>'
                if "page-1-c-7" in url:
                    return """
                    <a href="/photos-index-aid-1001.html">New 1</a>
                    <a href="/photos-index-aid-1002.html">New 2</a>
                    <a>下一页</a>
                    """
                if "page-2-c-7" in url:
                    return """
                    <a href="/photos-index-aid-1003.html">Already saved</a>
                    <a>下一页</a>
                    """
                raise AssertionError(f"unexpected fetch: {url}")

            with patch.object(external_sources, "fetch_html", fake_fetch_html):
                result = app_main.sync_wnacg_favorites(
                    schemas.ExternalFavoriteSyncRequest(
                        source_id=source.id,
                        favorites_url=external_sources.WNACG_DEFAULT_URL,
                        page_limit=30,
                    ),
                    db=db,
                )

            self.assertEqual(result["synced_count"], 3)
            self.assertTrue(any("page-1-c-7" in url for url in calls))
            self.assertTrue(any("page-2-c-7" in url for url in calls))
            self.assertFalse(any("page-3-c-7" in url for url in calls))
        finally:
            db.close()

    def test_request_retries_network_error_on_same_impersonation(self):
        calls = []

        class Response:
            status_code = 200
            headers = {}
            content = b"ok"

        def fake_request(method, url, headers, timeout, impersonate, proxies):
            calls.append(impersonate)
            if len(calls) == 1:
                raise RuntimeError("temporary timeout")
            return Response()

        with (
            patch.object(external_sources.cffi_requests, "request", fake_request),
            patch.object(external_sources, "_proxies", lambda: None),
            patch.object(external_sources.time, "sleep", lambda _: None),
        ):
            response = external_sources._request(
                "https://www.wnacg.com/",
                cookie="",
                accept="text/html",
                referer=external_sources.WNACG_BASE_URL,
                timeout=1,
                retries=2,
            )

        self.assertEqual(response.content, b"ok")
        self.assertEqual(calls, ["chrome", "chrome"])

    def test_request_raises_on_non_retryable_http_status(self):
        class Response:
            status_code = 404
            headers = {}
            content = b"not found"

        with (
            patch.object(external_sources.cffi_requests, "request", lambda *a, **k: Response()),
            patch.object(external_sources, "_proxies", lambda: None),
        ):
            with self.assertRaisesRegex(RuntimeError, "HTTP 404"):
                external_sources._request(
                    "https://www.wnacg.com/missing",
                    cookie="",
                    accept="text/html",
                    referer=external_sources.WNACG_BASE_URL,
                    timeout=1,
                    retries=1,
                )

    def test_request_can_return_non_200_when_status_check_disabled(self):
        class Response:
            status_code = 404
            headers = {}
            content = b"not found"

        with (
            patch.object(external_sources.cffi_requests, "request", lambda *a, **k: Response()),
            patch.object(external_sources, "_proxies", lambda: None),
        ):
            response = external_sources._request(
                "https://www.wnacg.com/missing",
                cookie="",
                accept="text/html",
                referer=external_sources.WNACG_BASE_URL,
                timeout=1,
                retries=1,
                raise_on_status=False,
            )

        self.assertEqual(response.status_code, 404)


if __name__ == "__main__":
    unittest.main()
