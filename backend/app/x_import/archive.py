"""Parse a Twitter / X data export archive (the zip the user requests from Settings → Your account → Download an archive).

We only need `data/like.js`, which is a JS file shaped like:

    window.YTD.like.part0 = [
        {"like": {"tweetId": "...", "fullText": "...", "expandedUrl": "https://twitter.com/user/status/..."}},
        ...
    ]

Some archives split into part0/part1/...; we accept any path that matches data/like*.js.
"""
from __future__ import annotations

import json
import re
import zipfile
from dataclasses import dataclass
from typing import Iterable, List, Optional


LIKE_FILE_PATTERN = re.compile(r"(?:^|/)data/like(?:-part\d+)?\.js$", re.IGNORECASE)
TWEET_URL_PATTERN = re.compile(r"https?://(?:twitter|x)\.com/([^/]+)/status/(\d+)")


@dataclass
class ArchiveLike:
    tweet_id: str
    url: str
    full_text: Optional[str]
    author_screen_name: Optional[str]


def _strip_assignment(raw: str) -> str:
    """The .js files start with `window.YTD.<name>.partN = [...]`. Strip the lhs to leave a JSON array."""
    idx = raw.find("=")
    if idx == -1:
        return raw
    body = raw[idx + 1 :].strip()
    if body.endswith(";"):
        body = body[:-1].rstrip()
    return body


def _iter_like_records(data: bytes) -> Iterable[dict]:
    text = data.decode("utf-8", errors="replace")
    body = _strip_assignment(text)
    try:
        records = json.loads(body)
    except json.JSONDecodeError:
        return []
    if not isinstance(records, list):
        return []
    return records


def parse_likes_from_zip(zip_path: str) -> List[ArchiveLike]:
    likes: dict[str, ArchiveLike] = {}
    with zipfile.ZipFile(zip_path, "r") as archive:
        like_members = [name for name in archive.namelist() if LIKE_FILE_PATTERN.search(name)]
        if not like_members:
            raise ValueError("归档中找不到 data/like.js，请确认这是 X / Twitter 的数据归档")

        for member in like_members:
            with archive.open(member) as fp:
                payload = fp.read()
            for record in _iter_like_records(payload):
                like = record.get("like") if isinstance(record, dict) else None
                if not like:
                    continue
                tweet_id = str(like.get("tweetId") or "").strip()
                url = (like.get("expandedUrl") or "").strip()
                if not tweet_id and url:
                    match = TWEET_URL_PATTERN.search(url)
                    if match:
                        tweet_id = match.group(2)
                if not tweet_id:
                    continue
                if not url:
                    url = f"https://twitter.com/i/web/status/{tweet_id}"
                screen_name = None
                match = TWEET_URL_PATTERN.search(url)
                if match:
                    screen_name = match.group(1)
                likes[tweet_id] = ArchiveLike(
                    tweet_id=tweet_id,
                    url=url,
                    full_text=like.get("fullText") or None,
                    author_screen_name=screen_name,
                )
    return list(likes.values())
