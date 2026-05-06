from dataclasses import dataclass
from html import unescape
from html.parser import HTMLParser
import re
from typing import Dict, Iterable, List, Optional
from urllib.error import HTTPError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


WNACG_DEFAULT_URL = "https://www.wnacg.com/users-users_fav.html"
WNACG_BASE_URL = "https://www.wnacg.com/"


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


def extract_wnacg_aid(url: str) -> Optional[str]:
    match = re.search(r"photos-index-aid-(\d+)\.html", url)
    return match.group(1) if match else None


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


def wnacg_category_url(category_id: str, page: int, base_url: str = WNACG_BASE_URL) -> str:
    return urljoin(base_url, f"users-users_fav-page-{page}-c-{category_id}.html")


def html_has_next_page(html: str) -> bool:
    return ">後頁" in html or ">后页" in html or ">下一页" in html


def fetch_html(url: str, cookie: str, timeout: int = 20) -> str:
    headers = {
        "User-Agent": "HE-Manager/1.0 local favorites sync",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer": WNACG_BASE_URL,
    }
    if cookie:
        headers["Cookie"] = cookie

    request = Request(url, headers=headers)
    with urlopen(request, timeout=timeout) as response:
        raw = response.read()
        content_type = response.headers.get("Content-Type", "")
        encoding_match = re.search(r"charset=([\w-]+)", content_type, re.I)
        encoding = encoding_match.group(1) if encoding_match else "utf-8"
        return raw.decode(encoding, errors="replace")


def fetch_binary(url: str, cookie: str, referer: str = WNACG_BASE_URL, timeout: int = 20) -> tuple[bytes, str]:
    headers = {
        "User-Agent": "HE-Manager/1.0 local favorites sync",
        "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        "Referer": referer,
    }
    if cookie:
        headers["Cookie"] = cookie

    request = Request(url, headers=headers)
    with urlopen(request, timeout=timeout) as response:
        return response.read(), response.headers.get("Content-Type", "application/octet-stream")


def _content_length_from_headers(headers) -> Optional[int]:
    content_range = headers.get("Content-Range", "")
    range_match = re.search(r"/(\d+)$", content_range)
    if range_match:
        return int(range_match.group(1))

    content_length = headers.get("Content-Length")
    if content_length and content_length.isdigit():
        return int(content_length)
    return None


def fetch_content_length(url: str, cookie: str, referer: str = WNACG_BASE_URL, timeout: int = 10) -> Optional[int]:
    headers = {
        "User-Agent": "HE-Manager/1.0 local favorites sync",
        "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        "Referer": referer,
    }
    if cookie:
        headers["Cookie"] = cookie

    for method in ("HEAD", "GET"):
        request_headers = dict(headers)
        if method == "GET":
            request_headers["Range"] = "bytes=0-0"

        request = Request(url, headers=request_headers, method=method)
        try:
            with urlopen(request, timeout=timeout) as response:
                length = _content_length_from_headers(response.headers)
                if length is not None:
                    return length
        except HTTPError as exc:
            length = _content_length_from_headers(exc.headers)
            if length is not None:
                return length
        except Exception:
            continue

    return None
