import io
import json
import logging
import math
import os
import shutil
import subprocess
import tempfile
import zipfile
from datetime import datetime
from typing import Optional

from PIL import Image, ImageStat
from sqlalchemy.orm import Session

from . import models, recommendations


log = logging.getLogger(__name__)

ANALYZER_VERSION = "content-profile-v1"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif"}
MAX_OCR_CHARS = 4000
_OCR_WARNED = False


def _json_dumps(value) -> str:
    return json.dumps(value, ensure_ascii=False)


def json_loads(value, fallback):
    if not value:
        return fallback
    try:
        return json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return fallback


def _source_mtime(media: models.Media) -> int:
    try:
        return int(os.path.getmtime(media.absolute_path))
    except OSError:
        return 0


def _is_fresh(media: models.Media, profile: Optional[models.MangaAIProfile]) -> bool:
    if not profile:
        return False
    return (
        profile.analyzer_version == ANALYZER_VERSION
        and int(profile.source_mtime or 0) == _source_mtime(media)
    )


def needs_profile(media: models.Media) -> bool:
    return media.media_type == "manga" and not _is_fresh(media, media.ai_profile)


def _dir_image_paths(root: str) -> list[str]:
    paths = []
    for current, dirs, files in os.walk(root):
        dirs.sort()
        for name in sorted(files):
            if os.path.splitext(name)[1].lower() in IMAGE_EXTENSIONS:
                paths.append(os.path.join(current, name))
    return paths


def _zip_image_names(path: str) -> list[str]:
    try:
        with zipfile.ZipFile(path, "r") as zf:
            return sorted(
                name for name in zf.namelist()
                if os.path.splitext(name)[1].lower() in IMAGE_EXTENSIONS
            )
    except (OSError, zipfile.BadZipFile):
        return []


def _sample_indices(total: int, sample_count: int) -> list[int]:
    if total <= 0:
        return []
    sample_count = max(1, min(sample_count, total))
    anchors = {0, total - 1}
    if total >= 3:
        anchors.add(total // 2)
    if total >= 6:
        anchors.update({1, 2, max(0, total // 2 - 1), min(total - 1, total // 2 + 1), total - 2})
    if len(anchors) < sample_count:
        for i in range(sample_count):
            anchors.add(round(i * (total - 1) / max(1, sample_count - 1)))
    return sorted(i for i in anchors if 0 <= i < total)[:sample_count]


def _open_sample(media: models.Media, index: int):
    if media.extension == ".dir":
        paths = _dir_image_paths(media.absolute_path)
        if index >= len(paths):
            return None, ""
        return Image.open(paths[index]), os.path.relpath(paths[index], media.absolute_path).replace(os.sep, "/")

    names = _zip_image_names(media.absolute_path)
    if index >= len(names):
        return None, ""
    with zipfile.ZipFile(media.absolute_path, "r") as zf:
        raw = zf.read(names[index])
    return Image.open(io.BytesIO(raw)), names[index]


def sample_pages(media: models.Media, sample_count: int = 10) -> list[dict]:
    total = media.page_count or 0
    if total <= 0:
        total = len(_dir_image_paths(media.absolute_path)) if media.extension == ".dir" else len(_zip_image_names(media.absolute_path))
    samples = []
    for index in _sample_indices(total, sample_count):
        image, name = _open_sample(media, index)
        if image is None:
            continue
        samples.append({"index": index + 1, "name": name, "image": image})
    return samples


def _colorfulness(rgb: Image.Image) -> float:
    stat = ImageStat.Stat(rgb.resize((96, 96)))
    means = stat.mean
    stddev = stat.stddev
    rg = abs(means[0] - means[1])
    yb = abs((means[0] + means[1]) / 2 - means[2])
    return float(math.sqrt(sum(x * x for x in stddev[:3])) + 0.3 * math.sqrt(rg * rg + yb * yb))


def page_visual_features(image: Image.Image) -> dict:
    rgb = image.convert("RGB")
    small = rgb.resize((160, max(1, int(160 * rgb.height / max(1, rgb.width)))))
    gray = small.convert("L")
    stat = ImageStat.Stat(gray)
    histogram = gray.histogram()
    total = max(1, sum(histogram))
    dark = sum(histogram[:75]) / total
    light = sum(histogram[200:]) / total
    return {
        "width": rgb.width,
        "height": rgb.height,
        "aspect": round(rgb.width / max(1, rgb.height), 3),
        "brightness": round(float(stat.mean[0]), 2),
        "contrast": round(float(stat.stddev[0]), 2),
        "ink_density": round(float(dark), 4),
        "light_ratio": round(float(light), 4),
        "colorfulness": round(_colorfulness(rgb), 2),
    }


def _ocr_image(image: Image.Image) -> str:
    """Best-effort OCR. Returns "" when tesseract is unavailable.

    Logs a one-shot warning so silent failure (no OCR text in any profile) is
    diagnosable instead of looking like a bug in the analyzer.
    """
    global _OCR_WARNED
    try:
        import pytesseract  # type: ignore

        return pytesseract.image_to_string(image, lang="jpn+chi_sim+chi_tra+eng").strip()
    except ImportError:
        pass
    except Exception as exc:
        if not _OCR_WARNED:
            log.warning("pytesseract OCR failed (likely missing language packs): %s", exc)
            _OCR_WARNED = True

    tesseract = shutil.which("tesseract")
    if not tesseract:
        if not _OCR_WARNED:
            log.warning("tesseract not found on PATH; manga profiles will skip OCR")
            _OCR_WARNED = True
        return ""
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "page.png")
        image.convert("RGB").save(path)
        cmd = [tesseract, path, "stdout", "-l", "jpn+chi_sim+chi_tra+eng"]
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=20, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            if not _OCR_WARNED:
                log.warning("tesseract CLI invocation failed: %s", exc)
                _OCR_WARNED = True
            return ""
        return (result.stdout or "").strip()


def _tags_from_features(media: models.Media, aggregate: dict, ocr_text: str) -> tuple[list[str], list[str], list[str], list[str]]:
    style = []
    story = []
    tone = []
    keywords = []

    pages = media.page_count or 0
    if pages:
        if pages <= 35:
            story.append("短篇")
        elif pages >= 100:
            story.append("长篇")
        else:
            story.append("中篇")

    if aggregate["colorfulness"] < 22:
        style.append("黑白")
    elif aggregate["colorfulness"] > 45:
        style.append("彩色感强")
    else:
        style.append("低彩度")

    if aggregate["contrast"] > 62:
        style.append("高对比")
    if aggregate["ink_density"] > 0.34:
        style.append("画面信息密集")
    elif aggregate["ink_density"] < 0.18:
        style.append("画面干净")

    if aggregate["brightness"] < 105:
        tone.append("偏暗")
    elif aggregate["brightness"] > 170:
        tone.append("明亮")
    else:
        tone.append("标准亮度")

    if len(ocr_text) > 1200:
        story.append("对白较多")
    elif len(ocr_text) < 160:
        story.append("文字较少")

    keywords.extend(style + story + tone)
    for tag in media.tags:
        keywords.append(tag.name)
    if media.artist:
        keywords.append(media.artist)
    return _dedupe(style), _dedupe(story), _dedupe(tone), _dedupe(keywords)


def _dedupe(items: list[str]) -> list[str]:
    seen = set()
    out = []
    for item in items:
        key = item.strip().lower()
        if key and key not in seen:
            seen.add(key)
            out.append(item.strip())
    return out


def _aggregate_features(page_features: list[dict]) -> dict:
    if not page_features:
        return {"brightness": 0, "contrast": 0, "ink_density": 0, "light_ratio": 0, "colorfulness": 0}
    keys = ["brightness", "contrast", "ink_density", "light_ratio", "colorfulness"]
    return {
        key: round(sum(float(page.get(key) or 0) for page in page_features) / len(page_features), 3)
        for key in keys
    }


def _local_summary(media: models.Media, style: list[str], story: list[str], tone: list[str], ocr_text: str) -> str:
    parts = []
    if media.page_count:
        parts.append(f"{media.page_count} 页")
    if style:
        parts.append("画面：" + "、".join(style[:4]))
    if story:
        parts.append("结构：" + "、".join(story[:4]))
    if tone:
        parts.append("氛围：" + "、".join(tone[:3]))
    if ocr_text:
        text = " ".join(ocr_text.split())[:180]
        parts.append(f"抽样文字：{text}")
    return "；".join(parts) + "。"


def _ai_summary(media: models.Media, feature_payload: dict, local_summary: str) -> tuple[str, list[str], list[str], list[str], list[str]]:
    if not recommendations.deepseek_configured():
        return local_summary, [], [], [], []
    system = "你是本地漫画库内容画像生成器。基于抽样页 OCR 和视觉统计生成简洁 JSON。"
    user = {
        "title": media.title,
        "artist": media.artist,
        "existing_tags": [tag.name for tag in media.tags],
        "page_count": media.page_count,
        "local_summary": local_summary,
        "features": feature_payload,
        "schema": {
            "content_summary": "中文，1-3 句，概括画风、结构、氛围",
            "style_tags": ["画风/视觉标签"],
            "story_tags": ["结构/剧情标签"],
            "tone_tags": ["氛围标签"],
            "recommendation_keywords": ["推荐匹配关键词"],
        },
    }
    try:
        raw = recommendations.call_deepseek(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
            ],
            temperature=0.1,
            max_tokens=900,
        )
        parsed = recommendations._safe_json_object(raw)
    except Exception:
        return local_summary, [], [], [], []
    return (
        str(parsed.get("content_summary") or local_summary)[:1200],
        _as_list(parsed.get("style_tags")),
        _as_list(parsed.get("story_tags")),
        _as_list(parsed.get("tone_tags")),
        _as_list(parsed.get("recommendation_keywords")),
    )


def _as_list(value) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip()[:80] for item in value if str(item).strip()]


def serialize_profile(profile: models.MangaAIProfile) -> dict:
    return {
        "media_id": profile.media_id,
        "content_summary": profile.content_summary or "",
        "style_tags": json_loads(profile.style_tags, []),
        "story_tags": json_loads(profile.story_tags, []),
        "tone_tags": json_loads(profile.tone_tags, []),
        "recommendation_keywords": json_loads(profile.recommendation_keywords, []),
        "sampled_pages": json_loads(profile.sampled_pages, []),
        "ocr_text": profile.ocr_text or "",
        "visual_features": json_loads(profile.visual_features, {}),
        "analyzer_version": profile.analyzer_version,
        "source_mtime": profile.source_mtime,
        "updated_at": profile.updated_at,
    }


def analyze_media(db: Session, media: models.Media, sample_count: int = 10, force: bool = False) -> models.MangaAIProfile:
    if media.media_type != "manga":
        raise ValueError("Only manga media can be profiled")

    profile = media.ai_profile
    if profile and not force and _is_fresh(media, profile):
        return profile

    samples = sample_pages(media, sample_count=sample_count)
    page_features = []
    sampled_pages = []
    ocr_parts = []
    for sample in samples:
        image = sample["image"]
        try:
            page_features.append({"page": sample["index"], "name": sample["name"], **page_visual_features(image)})
            sampled_pages.append({"page": sample["index"], "name": sample["name"]})
            if len(" ".join(ocr_parts)) < MAX_OCR_CHARS:
                text = _ocr_image(image)
                if text:
                    ocr_parts.append(text)
        finally:
            try:
                image.close()
            except Exception:
                pass

    ocr_text = "\n".join(ocr_parts)
    if len(ocr_text) > MAX_OCR_CHARS:
        ocr_text = ocr_text[:MAX_OCR_CHARS]
    aggregate = _aggregate_features(page_features)
    style, story, tone, keywords = _tags_from_features(media, aggregate, ocr_text)
    local_summary = _local_summary(media, style, story, tone, ocr_text)
    feature_payload = {"aggregate": aggregate, "pages": page_features, "ocr_text": ocr_text[:1600]}
    ai_summary, ai_style, ai_story, ai_tone, ai_keywords = _ai_summary(media, feature_payload, local_summary)

    style = _dedupe(style + ai_style)
    story = _dedupe(story + ai_story)
    tone = _dedupe(tone + ai_tone)
    keywords = _dedupe(keywords + ai_keywords + style + story + tone)

    if not profile:
        profile = models.MangaAIProfile(media=media)
        db.add(profile)

    profile.content_summary = ai_summary or local_summary
    profile.style_tags = _json_dumps(style)
    profile.story_tags = _json_dumps(story)
    profile.tone_tags = _json_dumps(tone)
    profile.recommendation_keywords = _json_dumps(keywords)
    profile.sampled_pages = _json_dumps(sampled_pages)
    profile.ocr_text = ocr_text
    profile.visual_features = _json_dumps(feature_payload)
    profile.analyzer_version = ANALYZER_VERSION
    profile.source_mtime = _source_mtime(media)
    profile.updated_at = datetime.utcnow()

    # Compute the dense embedding from the now-final profile fields. This is
    # best-effort: the embedding model may not be installed, may fail to load,
    # or may OOM. None of that should block the rest of the analysis from
    # being persisted — recommendations will just fall back to BM25-only for
    # this row until the next backfill.
    _maybe_embed(profile, media)
    return profile


def _maybe_embed(profile: models.MangaAIProfile, media: models.Media) -> None:
    """Best-effort: compose the manga's canonical text and store its embedding.

    Imported inside the function so the heavy sentence-transformers dep is
    only paid when actually analyzing a manga, not on every backend import.
    """
    try:
        from . import manga_vector  # local import; heavy dep
        text = manga_vector.compose_doc_text(media)
        vec = manga_vector.encode_text(text)
        profile.embedding = manga_vector.serialize_vec(vec)
        profile.embedding_model = manga_vector.MODEL_NAME
    except Exception as exc:  # noqa: BLE001 — best-effort, never break analysis
        log.warning("embedding encode failed for media %s: %s", media.id, exc)
        # Leave the existing embedding alone (might be stale, but better than
        # blanking it on transient failure).


def profile_stats(db: Session) -> dict:
    total = db.query(models.Media).filter(models.Media.media_type == "manga", models.Media.is_missing == False).count()  # noqa: E712
    profiles = db.query(models.MangaAIProfile).count()
    stale = 0
    profiled_rows = (
        db.query(models.Media)
        .join(models.MangaAIProfile, models.MangaAIProfile.media_id == models.Media.id)
        .filter(models.Media.media_type == "manga", models.Media.is_missing == False)  # noqa: E712
        .all()
    )
    for media in profiled_rows:
        if not _is_fresh(media, media.ai_profile):
            stale += 1
    return {"total_manga": total, "profiled": profiles, "stale": stale, "missing": max(0, total - profiles)}
