"""Fetch tweet metadata + media from X.

Two modes:
- Anonymous (no cookie): public syndication endpoint, same one X's embedded tweet widget uses.
  Stable but always treats requesters as logged-out viewers, which hides age-gated content.
- Authenticated (cookie + ct0): GraphQL TweetResultByRestId, what x.com itself uses. Sees
  adult/sensitive content as long as the account has the "show sensitive content" setting on.

Token derivation for the syndication endpoint (community-known):
    ((id / 1e15) * pi).toString(36).replace(/(0+|\\.)/g, "")
"""
from __future__ import annotations

import json
import math
import re
import time
from dataclasses import dataclass
from typing import List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

# curl_cffi wraps libcurl with TLS fingerprint impersonation. Required for X's GraphQL:
# Cloudflare drops the TLS connection mid-handshake when it sees Python's stdlib JA3/JA4
# fingerprint. urllib still works fine for syndication (less strict) and CDN downloads.
from curl_cffi import requests as cffi_requests
from curl_cffi.requests.exceptions import RequestException as CffiRequestException


SYNDICATION_BASE = "https://cdn.syndication.twimg.com/tweet-result"

# Public X web-app bearer. Used when posting authenticated requests to the GraphQL endpoint
# alongside the user's session cookie + ct0 csrf token.
WEB_BEARER_TOKEN = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

# Modern x.com uses x.com/i/api/graphql as the base; api.twitter.com/graphql is the
# legacy Twitter-era host and 404s for most operations now.
# X rotates GraphQL queryIds occasionally. If requests start failing with 404 or "operation
# not found", grab the current id from the network tab of x.com.
# Captured 2026-05-08.
GRAPHQL_BASE = "https://x.com/i/api/graphql"
GRAPHQL_QUERY_ID = "uEyKTt72BfzaY84WLGC5Dw"
GRAPHQL_OP = "TweetResultByRestId"
GRAPHQL_URL = f"{GRAPHQL_BASE}/{GRAPHQL_QUERY_ID}/{GRAPHQL_OP}"

# X rejects GraphQL requests when the feature toggle set drifts from what the web client sends.
# If you see "The following features cannot be null: ..." re-capture from the network panel.
GRAPHQL_FEATURES = {
    "creator_subscriptions_tweet_preview_api_enabled": True,
    "premium_content_api_read_enabled": False,
    "communities_web_enable_tweet_community_results_fetch": True,
    "c9s_tweet_anatomy_moderator_badge_enabled": True,
    "responsive_web_grok_analyze_button_fetch_trends_enabled": False,
    "responsive_web_grok_analyze_post_followups_enabled": True,
    "rweb_cashtags_composer_attachment_enabled": True,
    "responsive_web_jetfuel_frame": True,
    "responsive_web_grok_share_attachment_enabled": True,
    "responsive_web_grok_annotations_enabled": True,
    "articles_preview_enabled": True,
    "responsive_web_edit_tweet_api_enabled": True,
    "graphql_is_translatable_rweb_tweet_is_translatable_enabled": True,
    "view_counts_everywhere_api_enabled": True,
    "longform_notetweets_consumption_enabled": True,
    "responsive_web_twitter_article_tweet_consumption_enabled": True,
    "content_disclosure_indicator_enabled": True,
    "content_disclosure_ai_generated_indicator_enabled": True,
    "responsive_web_grok_show_grok_translated_post": False,
    "responsive_web_grok_analysis_button_from_backend": True,
    "post_ctas_fetch_enabled": False,
    "rweb_cashtags_enabled": True,
    "freedom_of_speech_not_reach_fetch_enabled": True,
    "standardized_nudges_misinfo": True,
    "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled": True,
    "longform_notetweets_rich_text_read_enabled": True,
    "longform_notetweets_inline_media_enabled": False,
    "profile_label_improvements_pcf_label_in_post_enabled": True,
    "responsive_web_profile_redirect_enabled": False,
    "rweb_tipjar_consumption_enabled": False,
    "verified_phone_label_enabled": False,
    "responsive_web_grok_image_annotation_enabled": True,
    "responsive_web_grok_imagine_annotation_enabled": True,
    "responsive_web_grok_community_note_auto_translation_is_enabled": False,
    "responsive_web_graphql_skip_user_profile_image_extensions_enabled": False,
    "responsive_web_graphql_timeline_navigation_enabled": True,
}

GRAPHQL_FIELD_TOGGLES = {
    "withArticleRichContentState": True,
    "withArticlePlainText": False,
    "withArticleSummaryText": True,
    "withArticleVoiceOver": True,
}

# Likes timeline. Captured 2026-05-08 from x.com web client. Same caveats: queryId rotates,
# features drift; re-capture from network panel when requests start 404'ing or complaining
# about missing features.
GRAPHQL_LIKES_QUERY_ID = "iSJzRLEkBUj_VWQkCWSpEQ"
GRAPHQL_LIKES_OP = "Likes"
GRAPHQL_LIKES_URL = f"{GRAPHQL_BASE}/{GRAPHQL_LIKES_QUERY_ID}/{GRAPHQL_LIKES_OP}"

GRAPHQL_LIKES_FEATURES = {
    "rweb_video_screen_enabled": False,
    "rweb_cashtags_enabled": True,
    "profile_label_improvements_pcf_label_in_post_enabled": True,
    "responsive_web_profile_redirect_enabled": False,
    "rweb_tipjar_consumption_enabled": False,
    "verified_phone_label_enabled": False,
    "creator_subscriptions_tweet_preview_api_enabled": True,
    "responsive_web_graphql_timeline_navigation_enabled": True,
    "responsive_web_graphql_skip_user_profile_image_extensions_enabled": False,
    "premium_content_api_read_enabled": False,
    "communities_web_enable_tweet_community_results_fetch": True,
    "c9s_tweet_anatomy_moderator_badge_enabled": True,
    "responsive_web_grok_analyze_button_fetch_trends_enabled": False,
    "responsive_web_grok_analyze_post_followups_enabled": True,
    "rweb_cashtags_composer_attachment_enabled": True,
    "responsive_web_jetfuel_frame": True,
    "responsive_web_grok_share_attachment_enabled": True,
    "responsive_web_grok_annotations_enabled": True,
    "articles_preview_enabled": True,
    "responsive_web_edit_tweet_api_enabled": True,
    "graphql_is_translatable_rweb_tweet_is_translatable_enabled": True,
    "view_counts_everywhere_api_enabled": True,
    "longform_notetweets_consumption_enabled": True,
    "responsive_web_twitter_article_tweet_consumption_enabled": True,
    "content_disclosure_indicator_enabled": True,
    "content_disclosure_ai_generated_indicator_enabled": True,
    "responsive_web_grok_show_grok_translated_post": False,
    "responsive_web_grok_analysis_button_from_backend": True,
    "post_ctas_fetch_enabled": False,
    "freedom_of_speech_not_reach_fetch_enabled": True,
    "standardized_nudges_misinfo": True,
    "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled": True,
    "longform_notetweets_rich_text_read_enabled": True,
    "longform_notetweets_inline_media_enabled": False,
    "responsive_web_grok_image_annotation_enabled": True,
    "responsive_web_grok_imagine_annotation_enabled": True,
    "responsive_web_grok_community_note_auto_translation_is_enabled": False,
    "responsive_web_enhance_cards_enabled": False,
}

GRAPHQL_LIKES_FIELD_TOGGLES = {"withArticlePlainText": False}

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

# Primary is the web-client fingerprint we captured X GraphQL with. Some
# Windows proxy/TUN paths reject curl-impersonate's Chrome/BoringSSL handshake
# with `TLS connect error: invalid library`; keep several web-like fallbacks
# because the working profile varies between the root x.com page and the Likes
# GraphQL endpoint under local proxy/TUN stacks.
GRAPHQL_IMPERSONATIONS = (
    ("chrome124", True),
    ("chrome101", True),
    ("edge101", False),
    ("firefox147", True),
    ("safari170", False),
)


class TweetUnavailable(Exception):
    """Raised when a tweet returns 404/403/410 or is otherwise not retrievable.

    Treated as a permanent failure (skip + record), not a transient one.
    """


class TweetTransientError(Exception):
    """Network / 5xx / 429; caller may retry with backoff."""


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


def _graphql_get(url: str, headers: dict, timeout: int, *, what: str, proxy: Optional[str] = None) -> bytes:
    """GET via curl_cffi with Chrome 124 TLS/HTTP2 fingerprint.

    Cloudflare drops Python's stdlib TLS handshake, surfacing as SSL UNEXPECTED_EOF.
    Maps HTTP status codes to TweetUnavailable / TweetTransientError so callers can stay
    framework-agnostic."""
    network_errors: List[str] = []
    response = None
    if not proxy:
        from app.external_config import get_global_proxy
        proxy = get_global_proxy()
    proxies_dict = {"http": proxy, "https": proxy} if proxy else None
    for impersonate, keep_user_agent in GRAPHQL_IMPERSONATIONS:
        request_headers = dict(headers)
        if not keep_user_agent:
            request_headers.pop("User-Agent", None)
        try:
            response = cffi_requests.get(
                url,
                headers=request_headers,
                timeout=timeout,
                impersonate=impersonate,
                allow_redirects=True,
                proxies=proxies_dict,
            )
            break
        except CffiRequestException as exc:
            network_errors.append(f"{impersonate}: {exc}")
            if (impersonate, keep_user_agent) == GRAPHQL_IMPERSONATIONS[-1]:
                raise TweetTransientError(f"Network error: {' | '.join(network_errors)}") from exc
    if response is None:
        raise TweetTransientError("Network error: no response from GraphQL endpoint")

    code = response.status_code
    if 200 <= code < 300:
        return response.content
    if code in (404, 410):
        raise TweetUnavailable(f"{what} unavailable (HTTP {code})")
    if code == 403:
        raise TweetUnavailable(f"{what} forbidden (HTTP 403) - cookie may be invalid or content restricted")
    if code == 401:
        raise TweetTransientError(f"{what} HTTP 401 - cookie likely expired")
    if code == 429 or code >= 500:
        raise TweetTransientError(f"{what} transient HTTP {code}")
    raise TweetTransientError(f"{what} HTTP {code}")


def _fetch_via_graphql(tweet_id: str, cookie: str, timeout: int, proxy: Optional[str] = None) -> TweetData:
    """Authenticated path: GraphQL TweetResultByRestId. Honors session cookie so adult/age-gated
    content is visible (the syndication endpoint always treats requesters as anonymous and
    hides those tweets regardless of the cookie that's attached)."""
    variables = {
        "tweetId": tweet_id,
        "includePromotedContent": True,
        "withBirdwatchNotes": True,
        "withVoice": True,
        "withCommunity": True,
    }
    params = urlencode({
        "variables": json.dumps(variables, separators=(",", ":")),
        "features": json.dumps(GRAPHQL_FEATURES, separators=(",", ":")),
        "fieldToggles": json.dumps(GRAPHQL_FIELD_TOGGLES, separators=(",", ":")),
    })
    url = f"{GRAPHQL_URL}?{params}"

    match = re.search(r"ct0=([^;]+)", cookie)
    csrf_token = match.group(1) if match else ""
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Authorization": f"Bearer {WEB_BEARER_TOKEN}",
        "Cookie": cookie,
        "x-csrf-token": csrf_token,
        "x-twitter-active-user": "yes",
        "x-twitter-auth-type": "OAuth2Session",
        "x-twitter-client-language": "en",
        "Referer": "https://x.com/",
        "Origin": "https://x.com",
    }

    raw = _graphql_get(url, headers, timeout, what=f"Tweet {tweet_id}", proxy=proxy)

    try:
        body = json.loads(raw.decode("utf-8", errors="replace"))
    except json.JSONDecodeError as exc:
        raise TweetTransientError("Invalid JSON from GraphQL endpoint") from exc

    errors = body.get("errors") if isinstance(body, dict) else None
    data = body.get("data") if isinstance(body, dict) else None
    if errors and not data:
        msg = errors[0].get("message", "GraphQL error")
        raise TweetTransientError(f"GraphQL error: {msg}")

    result = ((data or {}).get("tweetResult") or {}).get("result")
    if not result:
        raise TweetUnavailable(f"Tweet {tweet_id} returned empty result")

    typename = result.get("__typename")
    if typename in ("TweetUnavailable", "TweetTombstone"):
        reason = result.get("reason") or typename
        raise TweetUnavailable(f"Tweet {tweet_id} unavailable: {reason}")
    if typename == "TweetWithVisibilityResults":
        result = result.get("tweet") or {}

    legacy = result.get("legacy") or {}
    user_legacy = (((result.get("core") or {}).get("user_results") or {}).get("result") or {}).get("legacy") or {}
    user_core = ((result.get("core") or {}).get("user_results") or {}).get("result") or {}

    payload = {
        "user": {
            "screen_name": user_legacy.get("screen_name") or user_core.get("core", {}).get("screen_name"),
            "name": user_legacy.get("name") or user_core.get("core", {}).get("name"),
        },
        "created_at": legacy.get("created_at"),
        "full_text": legacy.get("full_text"),
        "extended_entities": legacy.get("extended_entities") or {},
    }

    return TweetData(
        tweet_id=tweet_id,
        author_screen_name=payload["user"]["screen_name"],
        author_name=payload["user"]["name"],
        posted_at=payload["created_at"],
        full_text=payload["full_text"],
        media=_parse_media(payload),
    )


def fetch_tweet(tweet_id: str, *, cookie: Optional[str] = None, timeout: int = 20, proxy: Optional[str] = None) -> TweetData:
    """Fetch tweet metadata. With cookie: authenticated GraphQL (sees adult content).
    Without cookie: public syndication endpoint (anonymous, hides age-gated tweets)."""
    if not proxy:
        from app.external_config import get_global_proxy
        proxy = get_global_proxy()
    if cookie:
        return _fetch_via_graphql(tweet_id, cookie, timeout, proxy=proxy)

    params = urlencode({"id": tweet_id, "lang": "en", "token": _derive_token(tweet_id)})
    url = f"{SYNDICATION_BASE}?{params}"
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/json,text/plain,*/*",
        "Referer": "https://platform.twitter.com/",
    }

    request = Request(url, headers=headers)

    try:
        if proxy:
            from urllib.request import build_opener, ProxyHandler
            opener = build_opener(ProxyHandler({"http": proxy, "https": proxy}))
            with opener.open(request, timeout=timeout) as response:
                raw = response.read()
        else:
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


@dataclass
class LikedTweet:
    tweet_id: str
    url: str
    author_screen_name: Optional[str]
    full_text: Optional[str]


def fetch_likes_page(
    *,
    cookie: str,
    user_id: str,
    cursor: Optional[str] = None,
    count: int = 20,
    timeout: int = 30,
    proxy: Optional[str] = None,
) -> Tuple[List[LikedTweet], Optional[str]]:
    """Pull one page of the authenticated user's Likes timeline. Returns (tweets, next_cursor).

    `next_cursor` is None when X reports no more pages. Same auth model and error mapping as
    `_fetch_via_graphql`. The caller is expected to space pages out (X rate-limits Likes
    aggressively) and to stop when next_cursor stops advancing.
    """
    variables: dict = {
        "userId": user_id,
        "count": count,
        "includePromotedContent": False,
        "withClientEventToken": False,
        "withBirdwatchNotes": False,
        "withVoice": True,
    }
    if cursor:
        variables["cursor"] = cursor

    params = urlencode({
        "variables": json.dumps(variables, separators=(",", ":")),
        "features": json.dumps(GRAPHQL_LIKES_FEATURES, separators=(",", ":")),
        "fieldToggles": json.dumps(GRAPHQL_LIKES_FIELD_TOGGLES, separators=(",", ":")),
    })
    url = f"{GRAPHQL_LIKES_URL}?{params}"

    match = re.search(r"ct0=([^;]+)", cookie)
    csrf_token = match.group(1) if match else ""
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Authorization": f"Bearer {WEB_BEARER_TOKEN}",
        "Cookie": cookie,
        "x-csrf-token": csrf_token,
        "x-twitter-active-user": "yes",
        "x-twitter-auth-type": "OAuth2Session",
        "x-twitter-client-language": "en",
        "Referer": "https://x.com/",
        "Origin": "https://x.com",
    }

    raw = _graphql_get(url, headers, timeout, what="Likes timeline", proxy=proxy)
    try:
        body = json.loads(raw.decode("utf-8", errors="replace"))
    except json.JSONDecodeError as exc:
        raise TweetTransientError("Invalid JSON from Likes endpoint") from exc

    errors = body.get("errors") if isinstance(body, dict) else None
    data = body.get("data") if isinstance(body, dict) else None
    if errors and not data:
        msg = errors[0].get("message", "GraphQL error")
        raise TweetTransientError(f"GraphQL error: {msg}")

    user_result = ((data or {}).get("user") or {}).get("result") or {}
    timeline_root = user_result.get("timeline_v2") or user_result.get("timeline") or {}
    instructions = (timeline_root.get("timeline") or {}).get("instructions") or []

    tweets: List[LikedTweet] = []
    next_cursor: Optional[str] = None

    for inst in instructions:
        for entry in inst.get("entries", []) or []:
            content = entry.get("content") or {}
            entry_type = content.get("entryType") or content.get("__typename") or ""

            # Cursor entries
            if "Cursor" in entry_type:
                if content.get("cursorType") == "Bottom":
                    next_cursor = content.get("value")
                continue

            item_content = content.get("itemContent") or {}
            tweet_results = item_content.get("tweet_results") or {}
            tweet = tweet_results.get("result")
            if not tweet:
                continue
            if tweet.get("__typename") in ("TweetUnavailable", "TweetTombstone"):
                continue
            if tweet.get("__typename") == "TweetWithVisibilityResults":
                tweet = tweet.get("tweet") or {}

            legacy = tweet.get("legacy") or {}
            tweet_id = tweet.get("rest_id") or legacy.get("id_str")
            if not tweet_id:
                continue

            user_legacy = (
                (((tweet.get("core") or {}).get("user_results") or {}).get("result") or {})
                .get("legacy")
                or {}
            )
            screen_name = user_legacy.get("screen_name")
            tweets.append(
                LikedTweet(
                    tweet_id=str(tweet_id),
                    url=f"https://x.com/{screen_name or 'i'}/status/{tweet_id}",
                    author_screen_name=screen_name,
                    full_text=legacy.get("full_text"),
                )
            )

    return tweets, next_cursor


def download_media(url: str, *, timeout: int = 60, retries: int = 2, proxy: Optional[str] = None) -> Tuple[bytes, str]:
    """Returns (content_bytes, content_type). Retries transient failures with linear backoff."""
    if not proxy:
        from app.external_config import get_global_proxy
        proxy = get_global_proxy()
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
            if proxy:
                from urllib.request import build_opener, ProxyHandler
                opener = build_opener(ProxyHandler({"http": proxy, "https": proxy}))
                response_ctx = opener.open(request, timeout=timeout)
            else:
                response_ctx = urlopen(request, timeout=timeout)

            with response_ctx as response:
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
