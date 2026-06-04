"""推送到下载中心客户端(downloader_push)的单测：纯逻辑，不连真实网关。"""
import pytest

from app import downloader_push as dp


def test_url_ext():
    assert dp.url_ext("https://x/a/001.JPG") == ".jpg"
    assert dp.url_ext("https://x/a/t.webp") == ".webp"
    assert dp.url_ext("https://x/a/img?foo=bar") == ".jpg"  # 无扩展名回落默认


def test_push_batch_not_configured(monkeypatch):
    monkeypatch.setattr(dp, "GATEWAY_URL", "")
    with pytest.raises(dp.DownloaderPushError):
        dp.push_batch("n", "/d", [{"url": "u", "rel_path": "r"}])


def test_push_batch_empty_files(monkeypatch):
    monkeypatch.setattr(dp, "GATEWAY_URL", "http://gw:8011")
    with pytest.raises(dp.DownloaderPushError):
        dp.push_batch("n", "/d", [])


def test_push_batch_posts_payload(monkeypatch):
    calls = {}

    class Resp:
        status_code = 200

        def raise_for_status(self):
            pass

        def json(self):
            return {"id": "job1", "status": "active"}

    def fake_post(url, json, headers, timeout):
        calls.update(url=url, json=json, headers=headers)
        return Resp()

    monkeypatch.setattr(dp, "GATEWAY_URL", "http://gw:8011")
    monkeypatch.setattr(dp, "GATEWAY_TOKEN", "secret")
    monkeypatch.setattr(dp.cffi_requests, "post", fake_post)

    job = dp.push_batch("漫画A", "/mnt/hdd/x", [{"url": "u", "rel_path": "001.jpg"}],
                        callback_url="http://he/cb")
    assert job["id"] == "job1"
    assert calls["url"].endswith("/jobs/batch")
    assert calls["json"]["name"] == "漫画A"
    assert calls["json"]["dest_dir"] == "/mnt/hdd/x"
    assert calls["json"]["callback_url"] == "http://he/cb"
    assert calls["headers"]["Authorization"] == "Bearer secret"
