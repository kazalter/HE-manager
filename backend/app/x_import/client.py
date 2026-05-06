"""Fetch tweet metadata + media via the public X syndication endpoint.

This is the same endpoint X's own embedded tweet widget uses. It requires no auth, just a
small token derived from the tweet id. Stable for years; degrades gracefully (404 / 403)
when a tweet is deleted, protected, or otherwise unavailable.

Reference (community-known token derivation): ((id / 1e15) * pi).toString(36).replace(/(0+|\.)/g, "")
"""
from __future__ import annotations

import json
import math
import time
from dataclasses import dataclass
from typing import List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


SYNDICATION_BASE = "https://cdn.syndication.twimg.com/tweet-result"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HE-Manager/1.0 X-Import "
    "(static read-only metadata fetch via public syndication)"
)


class TweetUnavailable(Exception):
    """Raised when a tweet returns 404/403/410 or is otherwise not retrievable.

    Treated as a permanent failure (skip + record), not a transient one.
    """


class TweetTransientError(Exception):
    """Network / 5xx / 429 — caller may retry with backoff."""


@dataclass
class TweetMedia:
    media_index: int
    media_type: str  # photo / video / animated_gif
    url: str
    width: Optional[int] = None
    height: Optional[int] = None
    duration_ms: Optional[int] = None


@dataclass
class TweetData:
    tweet_id: str
    author_screen_name: Optional[str]
    author_name: Optional[str]
    posted_at: Optional[str]  # ISO 8601 string
    full_text: Optional[str]
    media: List[TweetMedia]


def _derive_token(tweet_id: str) -> str:
    try:
        numeric = float(tweet_id)
    except ValueError:
        numeric = 0.0
    raw = (numeric / 1e15) * math.pi
    sign = "-" if raw < 0 else ""
    raw_abs = abs(raw)
    integer_part = int(raw_abs)
    fractional = raw_abs - integer_part

    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if integer_part == 0:
        int_str = "0"
    else:
        chunks: List[str] = []
        n = integer_part
        while n:
            chunks.append(digits[n % 36])
            n //= 36
        int_str = "".join(reversed(chunks))

    frac_chars: List[str] = []
    for _ in range(12):
        fractional *= 36
        digit = int(fractional)
        fractional -= digit
        frac_chars.append(digits[digit])
    frac_str = "".join(frac_chars)

    combined = sign + int_str + frac_str
    cleaned = combined.replace("0", "").replace(".", "")
    return cleaned or "0"


def _select_video_variant(variants: List[dict]) -> Tuple[Optional[str], Optional[int]]:
    best_url: Optional[str] = None
    best_bitrate = -1
    for variant in variants:
        if (variant.get("content_type") or variant.get("type")) != "video/mp4":
            continue
        bitrate = int(variant.get("bitrate") or 0)
        url = variant.get("url") or variant.get("src")
        if url and bitrate > best_bitrate:
            best_bitrate = bitrate
            best_url = url
    if best_url is None:
        for variant in variants:
            url = variant.get("url") or variant.get("src")
            if url:
                return url, None
    return best_url, (best_bitrate if best_bitrate >= 0 else None)


def _parse_media(payload: dict) -> List[TweetMedia]:
    extended = payload.get("mediaDetails") or []
    if not extended:
        ext_entities = payload.get("extended_entities") or {}
        extended = ext_entities.get("media") or []

    out: List[TweetMedia] = []
    for index, item in enumerate(extended):
        kind = (item.get("type") or "").lower()
        media_url: Optional[str] = None
        duration_ms: Optional[int] = None
        if kind == "photo":
            media_url = item.get("media_url_https") or item.get("media_url")
            if media_url and "?" not in media_url:
                # Request original size where possible (orig is full-resolution).
                media_url = f"{media_url}?name=orig"
        elif kind in {"video", "animated_gif"}:
            video_info = item.get("video_info") or {}
            variants = video_info.get("variants") or []
            picked, _bitrate = _select_video_variant(variants)
            media_url = picked
            duration_ms = video_info.get("duration_millis")
        if not media_url:
            continue
        sizes = item.get("original_info") or {}
        out.append(
            TweetMedia(
                media_index=index,
                media_type=kind or "photo",
                url=media_url,
                width=sizes.get("width") or item.get("sizes", {}).get("large", {}).get("w"),
                height=sizes.get("height") or item.get("sizes", {}).get("large", {}).get("h"),
                duration_ms=duration_ms,
            )
        )
    return out


def fetch_tweet(tweet_id: str, *, timeout: int = 20) -> TweetData:
    """Fetch tweet via syndication. Raises TweetUnavailable / TweetTransientError on failure."""
    params = urlencode({"id": tweet_id, "lang": "en", "token": _derive_token(tweet_id)})
    url = f"{SYNDICATION_BASE}?{params}"
    request = Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json,text/plain,*/*",
            "Referer": "https://platform.twitter.com/",
        },
    )

    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read()
    except HTTPError as exc:
        if exc.code in (404, 403, 410):
            raise TweetUnavailable(f"Tweet {tweet_id} unavailable (HTTP {exc.code})") from exc
        if exc.code in (429,) or exc.code >= 500:
            raise TweetTransientError(f"Tweet {tweet_id} transient HTTP {exc.code}") from exc
        raise TweetTransientError(f"Tweet {tweet_id} HTTP {exc.code}") from exc
    except URLError as exc:
        raise TweetTransientError(f"Network error: {exc}") from exc

    try:
        payload = json.loads(raw.decode("utf-8", errors="replace"))
    except json.JSONDecodeError as exc:
        raise TweetTransientError("Invalid JSON from syndication endpoint") from exc

    if not isinstance(payload, dict) or not payload:
        raise TweetUnavailable(f"Tweet {tweet_id} returned empty payload")

    user = payload.get("user") or {}
    return TweetData(
        tweet_id=tweet_id,
        author_screen_name=user.get("screen_name"),
        author_name=user.get("name"),
        posted_at=payload.get("created_at"),
        full_text=payload.get("text") or payload.get("full_text"),
        media=_parse_media(payload),
    )


def download_media(url: str, *, timeout: int = 60, retries: int = 2) -> Tuple[bytes, str]:
    """Returns (content_bytes, content_type). Retries transient failures with linear backoff."""
    last_error: Optional[Exception] = None
    for attempt in range(retries + 1):
        try:
            request = Request(
                url,
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "*/*",
                    "Referer": "https://twitter.com/",
                },
            )
            with urlopen(request, timeout=timeout) as response:
                content = response.read()
                content_type = response.headers.get("Content-Type", "application/octet-stream")
                return content, content_type
        except HTTPError as exc:
            if exc.code in (404, 403, 410):
                raise TweetUnavailable(f"Media unavailable (HTTP {exc.code}): {url}") from exc
            last_error = exc
        except URLError as exc:
            last_error = exc
        if attempt < retries:
            time.sleep(1.0 + attempt)
    raise TweetTransientError(f"Failed to download media after retries: {last_error}")
