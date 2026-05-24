import json
import re
from datetime import datetime
from typing import Optional

from sqlalchemy.orm import Session

from . import manga_artist, models
from .dedup import normalize as dedup_normalize


ANALYZER_VERSION = "metadata-v1"

LANGUAGE_HINTS = {
    "中文": "中文",
    "中国翻訳": "中文",
    "中國翻譯": "中文",
    "汉化": "中文",
    "漢化": "中文",
    "english": "English",
    "eng": "English",
    "japanese": "日本語",
    "日本語": "日本語",
}

NOISE_WORDS = {
    "dl版", "dl version", "無修正", "无修正", "digital", "カラー", "color",
    "中国翻訳", "中國翻譯", "汉化", "漢化", "中文", "english", "eng",
}


def _json_dumps(value) -> str:
    return json.dumps(value, ensure_ascii=False)


def json_loads(value, fallback):
    if not value:
        return fallback
    try:
        return json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return fallback


def _dedupe(items: list[str]) -> list[str]:
    seen = set()
    out = []
    for item in items:
        item = str(item).strip()
        key = item.lower()
        if item and key not in seen:
            seen.add(key)
            out.append(item)
    return out


def _bracket_chunks(title: str) -> list[str]:
    chunks = []
    for pattern in [r"\[([^\]]+)\]", r"\(([^)]+)\)", r"（([^）]+)）", r"【([^】]+)】"]:
        chunks.extend(match.group(1).strip() for match in re.finditer(pattern, title))
    return [chunk for chunk in chunks if chunk]


def _strip_bracket_noise(title: str) -> str:
    cleaned = re.sub(r"^\s*\([^)]*\)\s*", "", title)
    cleaned = re.sub(r"^\s*\[[^\]]+\]\s*", "", cleaned)
    cleaned = re.sub(r"\[[^\]]*(?:汉化|漢化|中文|DL|無修正|无修正|翻訳|翻譯)[^\]]*\]", "", cleaned, flags=re.I)
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" -_｜|")
    return cleaned or title


def parse_title_metadata(title: str) -> dict:
    chunks = _bracket_chunks(title or "")
    tags = []
    language = None
    parody = None

    for chunk in chunks:
        lowered = chunk.lower()
        for key, label in LANGUAGE_HINTS.items():
            if key.lower() in lowered:
                language = language or label
        if any(word.lower() in lowered for word in NOISE_WORDS):
            tags.append(chunk)

    paren_chunks = re.findall(r"\(([^)]+)\)", title or "")
    for chunk in paren_chunks:
        lowered = chunk.lower()
        if not any(word.lower() in lowered for word in NOISE_WORDS) and not re.match(r"c\d+|comitia|例大祭", lowered):
            parody = parody or chunk.strip()

    artist = manga_artist.parse_artist(title)
    circle = manga_artist.parse_circle(title)
    parsed_title = _strip_bracket_noise(title or "")
    normalized = dedup_normalize.normalize_title(parsed_title)

    return {
        "normalized_title": normalized,
        "parsed_title": parsed_title,
        "parsed_artist": artist,
        "parsed_circle": circle,
        "parody": parody,
        "language": language,
        "title_tags": _dedupe(tags),
    }


def _source_signature(media: models.Media) -> str:
    return "|".join(
        [
            str(media.id),
            media.title or "",
            media.source_site or "",
            media.source_url or "",
            ",".join(sorted(tag.name for tag in media.tags)),
        ]
    )


def _external_item_for_media(db: Session, media: models.Media) -> Optional[models.ExternalFavoriteItem]:
    if not media.source_url:
        return None
    return (
        db.query(models.ExternalFavoriteItem)
        .filter(models.ExternalFavoriteItem.url == media.source_url)
        .first()
    )


def needs_metadata(media: models.Media) -> bool:
    profile = media.metadata_profile
    if not profile:
        return media.media_type == "manga"
    return (
        profile.analyzer_version != ANALYZER_VERSION
        or profile.source_signature != _source_signature(media)
    )


def build_metadata_profile(db: Session, media: models.Media, force: bool = False) -> models.MangaMetadataProfile:
    if media.media_type != "manga":
        raise ValueError("Only manga media can have manga metadata")
    if media.metadata_profile and not force and not needs_metadata(media):
        return media.metadata_profile

    parsed = parse_title_metadata(media.title or "")
    tags = list(parsed["title_tags"])
    source_matches = []
    summary_bits = []
    confidence = 25

    if parsed.get("parsed_artist"):
        tags.append(f"artist:{parsed['parsed_artist']}")
        summary_bits.append(f"作者/社团：{parsed['parsed_artist']}")
        confidence += 15
    if parsed.get("parody"):
        tags.append(f"parody:{parsed['parody']}")
        summary_bits.append(f"原作/系列：{parsed['parody']}")
        confidence += 10
    if parsed.get("language"):
        tags.append(f"language:{parsed['language']}")
        summary_bits.append(f"语言：{parsed['language']}")
        confidence += 5

    for tag in media.tags:
        tags.append(tag.name)

    item = _external_item_for_media(db, media)
    if item:
        source_matches.append({
            "source_type": item.source_type,
            "external_id": item.external_id,
            "url": item.url,
            "title": item.title,
            "category_id": item.category_id,
            "category_name": item.category_name,
        })
        confidence += 30
        if item.category_name:
            tags.append(f"category:{item.category_name}")
            summary_bits.append(f"来源分类：{item.category_name}")
        if item.title and item.title != media.title:
            summary_bits.append(f"来源标题：{item.title}")

    if media.source_site:
        source_matches.append({"source_type": media.source_site, "url": media.source_url})
        confidence += 10

    if media.page_count:
        if media.page_count <= 35:
            tags.append("length:短篇")
        elif media.page_count >= 100:
            tags.append("length:长篇")
        else:
            tags.append("length:中篇")
        summary_bits.append(f"页数：{media.page_count}")

    external_summary = "；".join(summary_bits) + ("。" if summary_bits else "")
    profile = media.metadata_profile
    if not profile:
        profile = models.MangaMetadataProfile(media=media)
        db.add(profile)

    profile.normalized_title = parsed["normalized_title"]
    profile.parsed_title = parsed["parsed_title"]
    profile.parsed_artist = parsed["parsed_artist"]
    profile.parsed_circle = parsed["parsed_circle"]
    profile.parody = parsed["parody"]
    profile.language = parsed["language"]
    profile.external_tags = _json_dumps(_dedupe(tags))
    profile.external_summary = external_summary
    profile.source_matches = _json_dumps(source_matches)
    profile.confidence = min(100, confidence)
    profile.analyzer_version = ANALYZER_VERSION
    profile.source_signature = _source_signature(media)
    profile.updated_at = datetime.utcnow()
    return profile


def serialize_profile(profile: models.MangaMetadataProfile) -> dict:
    return {
        "media_id": profile.media_id,
        "normalized_title": profile.normalized_title,
        "parsed_title": profile.parsed_title,
        "parsed_artist": profile.parsed_artist,
        "parsed_circle": profile.parsed_circle,
        "parody": profile.parody,
        "language": profile.language,
        "external_tags": json_loads(profile.external_tags, []),
        "external_summary": profile.external_summary or "",
        "source_matches": json_loads(profile.source_matches, []),
        "confidence": profile.confidence or 0,
        "updated_at": profile.updated_at,
    }


def profile_stats(db: Session) -> dict:
    total = db.query(models.Media).filter(models.Media.media_type == "manga", models.Media.is_missing == False).count()  # noqa: E712
    profiled = db.query(models.MangaMetadataProfile).count()
    stale = 0
    rows = (
        db.query(models.Media)
        .join(models.MangaMetadataProfile, models.MangaMetadataProfile.media_id == models.Media.id)
        .filter(models.Media.media_type == "manga", models.Media.is_missing == False)  # noqa: E712
        .all()
    )
    for media in rows:
        if needs_metadata(media):
            stale += 1
    return {"total_manga": total, "profiled": profiled, "stale": stale, "missing": max(0, total - profiled)}
