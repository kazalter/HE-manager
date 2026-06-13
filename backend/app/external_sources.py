from dataclasses import dataclass
from html import unescape
from html.parser import HTMLParser
import json
import os
import re
import time
from typing import Dict, Iterable, List, Optional
from urllib.parse import urljoin
from urllib.request import getproxies

from curl_cffi import requests as cffi_requests


WNACG_DEFAULT_URL = "https://www.wnacg.com/users-users_fav.html"
WNACG_BASE_URL = "https://www.wnacg.com/"

# wnacg sits behind Cloudflare bot management, which fingerprints the TLS/HTTP
# client (JA3). Python's stdlib ssl fingerprint is deterministically blocked
# (HTTP 403) and the handshake is sometimes reset outright (SSL UNEXPECTED_EOF).
# curl_cffi impersonates a real Chrome's TLS+HTTP2 fingerprint so Cloudflare
# lets us through; the residual flaky 403/network reset is absorbed by retries.
#
# Mirror x_import/client.py: keep several browser profiles because some Windows
# proxy/TUN paths reject a given curl-impersonate handshake with
# `TLS connect error: invalid library`, and the profile that works varies.
_IMPERSONATIONS = ("chrome", "chrome124", "edge101", "firefox147")
_FETCH_RETRIES = int(os.getenv("HE_WNACG_FETCH_RETRIES", "3"))
_FETCH_TIMEOUT_SECONDS = int(os.getenv("HE_WNACG_FETCH_TIMEOUT_SECONDS", "45"))
_FETCH_RETRY_BACKOFF_SECONDS = float(os.getenv("HE_WNACG_FETCH_RETRY_BACKOFF_SECONDS", "1.5"))
_RETRYABLE_HTTP_STATUSES = (403, 429, 500, 502, 503, 520, 521, 522, 523, 524)


def _proxies() -> Optional[dict]:
    # Honour both env (HTTP(S)_PROXY) and the Windows system-proxy registry that
    # Clash/Mihomo sets when "set as system proxy" is on. Empty dict => direct,
    # which is correct under TUN-mode tunnels that route transparently.
    proxies = getproxies()
    return proxies or None


def _request(
    url: str,
    *,
    cookie: str,
    accept: str,
    referer: str,
    timeout: int,
    method: str = "GET",
    extra_headers: Optional[dict] = None,
    retries: int = _FETCH_RETRIES,
    raise_on_status: bool = True,
    proxy: Optional[str] = None,
    data: Optional[bytes | str] = None,
    stream: bool = False,
):
    """Perform a GET/HEAD with browser TLS impersonation, retrying past
    Cloudflare's intermittent 403/connection-reset and swapping fingerprint
    profiles on handshake failure. Returns the curl_cffi Response."""
    from app.external_config import get_global_proxy
    proxy = get_global_proxy()

    headers = {
        # Deliberately no User-Agent override: impersonate=chrome sets a Chrome
        # UA + client hints that must match the spoofed TLS fingerprint.
        "Accept": accept,
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer": referer,
    }
    if cookie:
        headers["Cookie"] = cookie
    if extra_headers:
        headers.update(extra_headers)

    last_exc: Optional[Exception] = None
    last_status: Optional[int] = None
    # Outer loop swaps the impersonation profile (handshake-level fallback);
    # inner loop retries transient Cloudflare 403/429/5xx and network resets.
    for impersonate in _IMPERSONATIONS:
        for attempt in range(max(1, retries)):
            try:
                request_kwargs = {
                    "headers": headers,
                    "timeout": timeout,
                    "impersonate": impersonate,
                    "proxies": {"http": proxy, "https": proxy} if proxy else _proxies(),
                }
                if data is not None:
                    request_kwargs["data"] = data
                if stream:
                    request_kwargs["stream"] = True
                response = cffi_requests.request(method, url, **request_kwargs)
            except Exception as exc:  # network reset / TLS abort -> retry
                last_exc = exc
                if attempt + 1 < max(1, retries):
                    time.sleep(_FETCH_RETRY_BACKOFF_SECONDS * (attempt + 1))
                    continue
                break  # bad handshake or consistently bad path for this profile; try the next one
            if response.status_code == 200 or not raise_on_status:
                return response
            last_status = response.status_code
            if response.status_code not in _RETRYABLE_HTTP_STATUSES:
                raise RuntimeError(
                    f"请求 wnacg 失败（HTTP {response.status_code}）；"
                    "请检查 Cookie 是否过期、收藏页地址是否有效"
                )
            if attempt + 1 < max(1, retries):
                time.sleep(_FETCH_RETRY_BACKOFF_SECONDS * (attempt + 1))

    if last_status is not None:
        if last_status in (403, 429):
            raise RuntimeError(f"请求被 Cloudflare 拦截（HTTP {last_status}），多种指纹重试仍失败")
        raise RuntimeError(f"请求 wnacg 失败（HTTP {last_status}），多次重试仍失败")
    if last_exc is not None:
        raise RuntimeError(
            "连接 wnacg 超时或被网络中断；请确认代理/TUN 可访问 www.wnacg.com，"
            "或稍后重试。若经常发生，可调大 HE_WNACG_FETCH_TIMEOUT_SECONDS。"
            f" 原始错误：{last_exc}"
        ) from last_exc
    raise RuntimeError("请求失败：无法建立到 wnacg 的连接")


@dataclass
class ParsedExternalFavorite:
    external_id: str
    title: str
    url: str
    cover_url: Optional[str] = None
    category_id: Optional[str] = None
    category_name: Optional[str] = None


@dataclass
class WnacgCategory:
    id: str
    name: str


@dataclass
class WnacgWorkerArchiveRequest:
    api_url: str
    file_key: str
    file_name: str


class WnacgFavoriteParser(HTMLParser):
    def __init__(self, base_url: str, category_id: Optional[str] = None, category_name: Optional[str] = None):
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.category_id = category_id
        self.category_name = category_name
        self.items: List[ParsedExternalFavorite] = []
        self._current: Optional[Dict[str, object]] = None
        self._pending_cover_url: Optional[str] = None
        self._pending_category_id: Optional[str] = None
        self._pending_category_name: Optional[str] = None
        self._category_link: Optional[Dict[str, str]] = None

    def handle_starttag(self, tag: str, attrs: Iterable[tuple[str, Optional[str]]]):
        attrs_dict = {name.lower(): value or "" for name, value in attrs}
        if tag == "a":
            href = attrs_dict.get("href", "")
            if "photos-index-aid-" in href:
                self._current = {
                    "href": href,
                    "texts": [],
                    "cover_url": self._pending_cover_url,
                    "title_hint": attrs_dict.get("title", ""),
                    "category_id": self._pending_category_id,
                    "category_name": self._pending_category_name,
                }
            elif "users-users_fav-c-" in href:
                self._category_link = {"href": href, "text": ""}
        elif tag == "img" and self._current is not None:
            src = attrs_dict.get("src") or attrs_dict.get("data-src")
            if src:
                self._current["cover_url"] = src
            title_hint = attrs_dict.get("title") or attrs_dict.get("alt")
            if title_hint and not self._current.get("title_hint"):
                self._current["title_hint"] = title_hint
        elif tag == "img":
            src = attrs_dict.get("src") or attrs_dict.get("data-src")
            if src:
                self._pending_cover_url = src

    def handle_data(self, data: str):
        if self._current is not None:
            text = data.strip()
            if text:
                self._current["texts"].append(text)
        elif self._category_link is not None:
            text = data.strip()
            if text:
                self._category_link["text"] += text

    def handle_endtag(self, tag: str):
        if tag == "a" and self._category_link is not None:
            category_id_match = re.search(r"users-users_fav-c-(\d+)\.html", self._category_link["href"])
            if category_id_match:
                self._pending_category_id = category_id_match.group(1)
                self._pending_category_name = unescape(self._category_link["text"]).strip() or None
            self._category_link = None
            return

        if tag != "a" or self._current is None:
            return

        href = str(self._current["href"])
        external_id = extract_wnacg_aid(href)
        if not external_id:
            self._current = None
            return

        texts = self._current.get("texts") or []
        title = " ".join(str(part) for part in texts).strip()
        if not title:
            title = str(self._current.get("title_hint") or "").strip()
        if not title:
            title = f"WNACG #{external_id}"

        cover_url = self._current.get("cover_url")
        self.items.append(
            ParsedExternalFavorite(
                external_id=external_id,
                title=unescape(title),
                url=urljoin(self.base_url, href),
                cover_url=urljoin(self.base_url, str(cover_url)) if cover_url else None,
                category_id=self.category_id or str(self._current.get("category_id") or "") or None,
                category_name=self.category_name or str(self._current.get("category_name") or "") or None,
            )
        )
        self._current = None
        self._pending_cover_url = None


class WnacgArchiveLinkParser(HTMLParser):
    ARCHIVE_EXTENSIONS = (".zip", ".cbz")

    def __init__(self, base_url: str):
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.urls: List[str] = []

    def handle_starttag(self, tag: str, attrs: Iterable[tuple[str, Optional[str]]]):
        if tag != "a":
            return
        attrs_dict = {name.lower(): value or "" for name, value in attrs}
        href = unescape(attrs_dict.get("href", "")).strip()
        if not href:
            return
        path = href.split("?", 1)[0].lower()
        if not path.endswith(self.ARCHIVE_EXTENSIONS):
            return
        absolute = urljoin(self.base_url, href)
        if absolute not in self.urls:
            self.urls.append(absolute)


def extract_wnacg_aid(url: str) -> Optional[str]:
    match = re.search(r"photos-index-aid-(\d+)\.html", url)
    return match.group(1) if match else None


def wnacg_download_url(aid: str, base_url: str = WNACG_BASE_URL) -> str:
    return urljoin(base_url, f"download-index-aid-{aid}.html")


def parse_wnacg_categories(html: str) -> List[WnacgCategory]:
    categories: Dict[str, WnacgCategory] = {}
    for match in re.finditer(r"users-users_fav(?:-page-\d+)?-c-(\d+)\.html[^>]*>(.*?)</a>", html, re.S):
        category_id, raw_name = match.groups()
        name = re.sub(r"<[^>]+>", "", raw_name)
        name = unescape(name).strip()
        if name:
            categories[category_id] = WnacgCategory(id=category_id, name=name)
    return list(categories.values())


def parse_wnacg_favorites(
    html: str,
    base_url: str = WNACG_BASE_URL,
    category_id: Optional[str] = None,
    category_name: Optional[str] = None,
) -> List[ParsedExternalFavorite]:
    parser = WnacgFavoriteParser(base_url=base_url, category_id=category_id, category_name=category_name)
    parser.feed(html)

    deduped: Dict[str, ParsedExternalFavorite] = {}
    for item in parser.items:
        existing = deduped.get(item.external_id)
        if not existing:
            deduped[item.external_id] = item
            continue
        if not existing.cover_url and item.cover_url:
            existing.cover_url = item.cover_url
        if len(item.title) > len(existing.title):
            existing.title = item.title
    return list(deduped.values())


def parse_wnacg_image_urls(html: str) -> List[str]:
    urls = re.findall(r"https?://[^\"']+\.(?:jpg|jpeg|png|webp|gif|avif)", html, re.I)
    deduped: List[str] = []
    seen = set()
    for url in urls:
        if url in seen:
            continue
        seen.add(url)
        deduped.append(url)
    return deduped


def parse_wnacg_archive_urls(html: str, base_url: str = WNACG_BASE_URL) -> List[str]:
    parser = WnacgArchiveLinkParser(base_url=base_url)
    parser.feed(html)
    return parser.urls


def parse_wnacg_worker_archive_request(html: str) -> Optional[WnacgWorkerArchiveRequest]:
    def read_config_string(name: str) -> Optional[str]:
        match = re.search(rf"{name}\s*:\s*([\"'])(.*?)\1", html, re.S)
        return unescape(match.group(2)).strip() if match else None

    api_url = read_config_string("WORKER_API")
    file_key = read_config_string("FILE_KEY")
    file_name = read_config_string("FILE_NAME")
    if not api_url or not file_key or not file_name:
        return None
    return WnacgWorkerArchiveRequest(api_url=api_url, file_key=file_key, file_name=file_name)


def resolve_wnacg_worker_archive_url(
    request: WnacgWorkerArchiveRequest,
    *,
    cookie: str,
    referer: str,
    timeout: Optional[int] = None,
    proxy: Optional[str] = None,
) -> Optional[str]:
    payload = json.dumps(
        {"file_key": request.file_key, "file_name": request.file_name},
        ensure_ascii=False,
    )
    response = _request(
        request.api_url,
        cookie=cookie,
        accept="application/json,text/plain,*/*",
        referer=referer,
        timeout=timeout or _FETCH_TIMEOUT_SECONDS,
        method="POST",
        extra_headers={"Content-Type": "application/json"},
        data=payload.encode("utf-8"),
        proxy=proxy,
    )
    try:
        data = json.loads(response.content.decode("utf-8", errors="replace"))
    except Exception as exc:
        raise RuntimeError("下载页返回的压缩包直链响应无法解析") from exc
    if not data.get("success"):
        message = data.get("msg") or data.get("message") or "未知错误"
        raise RuntimeError(f"生成压缩包直链失败：{message}")
    url = str(data.get("url") or "").strip()
    return url or None


def wnacg_category_url(category_id: str, page: int, base_url: str = WNACG_BASE_URL) -> str:
    return urljoin(base_url, f"users-users_fav-page-{page}-c-{category_id}.html")


def html_has_next_page(html: str) -> bool:
    return ">後頁" in html or ">后页" in html or ">下一页" in html


def fetch_html(url: str, cookie: str, timeout: Optional[int] = None, proxy: Optional[str] = None) -> str:
    response = _request(
        url,
        cookie=cookie,
        accept="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        referer=WNACG_BASE_URL,
        timeout=timeout or _FETCH_TIMEOUT_SECONDS,
        proxy=proxy,
    )
    raw = response.content
    content_type = response.headers.get("Content-Type", "")
    encoding_match = re.search(r"charset=([\w-]+)", content_type, re.I)
    encoding = encoding_match.group(1) if encoding_match else "utf-8"
    return raw.decode(encoding, errors="replace")


def fetch_binary(url: str, cookie: str, referer: str = WNACG_BASE_URL, timeout: Optional[int] = None, proxy: Optional[str] = None) -> tuple[bytes, str]:
    response = _request(
        url,
        cookie=cookie,
        accept="image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        referer=referer,
        timeout=timeout or _FETCH_TIMEOUT_SECONDS,
        proxy=proxy,
    )
    return response.content, response.headers.get("Content-Type", "application/octet-stream")


def fetch_file_to_path(
    url: str,
    cookie: str,
    destination_path: str,
    referer: str = WNACG_BASE_URL,
    timeout: Optional[int] = None,
    proxy: Optional[str] = None,
    accept: str = "application/zip,application/octet-stream,*/*",
    on_chunk=None,
) -> tuple[int, str]:
    response = _request(
        url,
        cookie=cookie,
        accept=accept,
        referer=referer,
        timeout=timeout or _FETCH_TIMEOUT_SECONDS,
        proxy=proxy,
        stream=True,
    )
    total = 0
    try:
        with open(destination_path, "wb") as out:
            for chunk in response.iter_content(chunk_size=1024 * 512):
                if not chunk:
                    continue
                out.write(chunk)
                total += len(chunk)
                if on_chunk is not None:
                    on_chunk(len(chunk))
    finally:
        close = getattr(response, "close", None)
        if callable(close):
            close()
    return total, response.headers.get("Content-Type", "application/octet-stream")


def _content_length_from_headers(headers) -> Optional[int]:
    content_range = headers.get("Content-Range", "")
    range_match = re.search(r"/(\d+)$", content_range)
    if range_match:
        return int(range_match.group(1))

    content_length = headers.get("Content-Length")
    if content_length and content_length.isdigit():
        return int(content_length)
    return None


def fetch_content_length(url: str, cookie: str, referer: str = WNACG_BASE_URL, timeout: int = 10, proxy: Optional[str] = None) -> Optional[int]:
    accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    for method in ("HEAD", "GET"):
        extra = {"Range": "bytes=0-0"} if method == "GET" else None
        try:
            # Best-effort sizing: don't raise on non-200 (e.g. 206/403), just
            # read whatever length headers came back and move on.
            response = _request(
                url,
                cookie=cookie,
                accept=accept,
                referer=referer,
                timeout=timeout,
                method=method,
                extra_headers=extra,
                retries=2,
                raise_on_status=False,
                proxy=proxy,
            )
            length = _content_length_from_headers(response.headers)
            if length is not None:
                return length
        except Exception:
            continue

    return None
