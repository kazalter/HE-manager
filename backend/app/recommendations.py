import json
import re
from typing import Iterable, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from sqlalchemy.orm import Session

from . import ai_config, models

HIDDEN_DUPLICATE_STATUSES = {"checking", "strong_duplicate", "suspected_duplicate"}

# (removed — personal media library, no content restrictions)


def deepseek_base_url() -> str:
    return ai_config.get_deepseek_config()["base_url"]


def deepseek_model() -> str:
    return ai_config.get_deepseek_config()["model"]


def deepseek_configured() -> bool:
    return bool(ai_config.get_deepseek_config()["api_key"])


def _as_list(value) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip()[:80] for item in value if str(item).strip()]


def _safe_json_object(text: str) -> dict:
    text = (text or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.S)
        if not match:
            return {}
        try:
            parsed = json.loads(match.group(0))
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}


def call_deepseek(messages: list[dict], temperature: float = 0.2, max_tokens: int = 1200) -> str:
    api_key = ai_config.get_deepseek_config()["api_key"]
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY is not configured")

    payload = {
        "model": deepseek_model(),
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    request = Request(
        f"{deepseek_base_url()}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=45) as response:
            data = json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"DeepSeek API error {exc.code}: {detail[:300]}") from exc
    except (URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"DeepSeek API request failed: {exc}") from exc

    choices = data.get("choices") or []
    if not choices:
        raise RuntimeError("DeepSeek API returned no choices")
    message = choices[0].get("message") or {}
    return str(message.get("content") or "")


def parse_preferences(query: str, avoid_tags: Iterable[str], preferred_tags: Iterable[str]) -> tuple[dict, bool, Optional[str]]:
    fallback = _heuristic_preferences(query, avoid_tags, preferred_tags)
    if not deepseek_configured():
        return fallback, False, "未配置 DEEPSEEK_API_KEY，已使用本地规则推荐。"

    system = (
        "你是本地漫画库的偏好解析器。只输出 JSON，不输出解释。"
    )
    user = {
        "query": query,
        "avoid_tags": list(avoid_tags),
        "preferred_tags": list(preferred_tags),
        "schema": {
            "positive_terms": ["用户想要的题材、画风、作者、系列、氛围关键词"],
            "avoid_terms": ["用户明确不想要的内容"],
            "tone": ["轻松", "剧情", "画风精细"],
            "length": "short | medium | long | any",
        },
    }
    try:
        content = call_deepseek(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
            ],
            temperature=0,
            max_tokens=700,
        )
        parsed = _safe_json_object(content)
        if not parsed:
            return fallback, True, "AI 偏好解析失败，已使用本地规则推荐。"
        parsed["positive_terms"] = _as_list(parsed.get("positive_terms")) + fallback["positive_terms"]
        parsed["avoid_terms"] = _as_list(parsed.get("avoid_terms")) + fallback["avoid_terms"]
        parsed["tone"] = _as_list(parsed.get("tone"))
        parsed["length"] = str(parsed.get("length") or "any")
        parsed["positive_terms"] = _dedupe(parsed["positive_terms"])
        parsed["avoid_terms"] = _dedupe(parsed["avoid_terms"])
        return parsed, True, None
    except RuntimeError as exc:
        return fallback, True, str(exc)


def _dedupe(items: Iterable[str]) -> list[str]:
    seen = set()
    output = []
    for item in items:
        key = item.strip().lower()
        if not key or key in seen:
            continue
        seen.add(key)
        output.append(item.strip())
    return output


def _heuristic_preferences(query: str, avoid_tags: Iterable[str], preferred_tags: Iterable[str]) -> dict:
    terms = re.findall(r"[\w\u3040-\u30ff\u3400-\u9fff]+", query.lower())
    noise = {"want", "like", "recommend", "推荐", "漫画", "作品", "一点", "不要", "想看"}
    positive_terms = [term for term in terms if term not in noise and len(term) > 1]
    return {
        "positive_terms": _dedupe(list(preferred_tags) + positive_terms),
        "avoid_terms": _dedupe(list(avoid_tags)),
        "tone": [],
        "length": "any",
    }


def _text_blob(media: models.Media) -> str:
    tag_names = " ".join(tag.name for tag in media.tags)
    profile = media.ai_profile
    profile_text = ""
    if profile:
        profile_text = " ".join(
            [
                profile.content_summary or "",
                profile.style_tags or "",
                profile.story_tags or "",
                profile.tone_tags or "",
                profile.recommendation_keywords or "",
                profile.ocr_text or "",
            ]
        )
    metadata = media.metadata_profile
    metadata_text = ""
    if metadata:
        metadata_text = " ".join(
            [
                metadata.parsed_title or "",
                metadata.parsed_artist or "",
                metadata.parody or "",
                metadata.language or "",
                metadata.external_tags or "",
                metadata.external_summary or "",
            ]
        )
    return f"{media.title or ''} {media.artist or ''} {tag_names} {profile_text} {metadata_text}".lower()


def _profile_list(value: str | None) -> list[str]:
    if not value:
        return []
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item) for item in parsed if str(item).strip()]


def _profile_keywords(media: models.Media) -> list[str]:
    profile = media.ai_profile
    if not profile:
        return []
    keywords = []
    for value in [profile.style_tags, profile.story_tags, profile.tone_tags, profile.recommendation_keywords]:
        keywords.extend(_profile_list(value))
    metadata = media.metadata_profile
    if metadata:
        keywords.extend(_profile_list(metadata.external_tags))
        for value in [metadata.parsed_artist, metadata.parody, metadata.language]:
            if value:
                keywords.append(value)
    return _dedupe(keywords)


# (removed — personal media library, no content restrictions)


def _is_cjk(ch: str) -> bool:
    return "぀" <= ch <= "ヿ" or "㐀" <= ch <= "鿿"


def _avoid_term_usable(term: str) -> bool:
    """Skip too-short avoid terms so a single letter doesn't nuke the library.

    CJK terms: require ≥ 2 chars. Latin terms: require ≥ 3 chars.
    """
    term = term.strip()
    if not term:
        return False
    if any(_is_cjk(ch) for ch in term):
        return len(term) >= 2
    return len(term) >= 3


def _matches_any(blob: str, terms: Iterable[str]) -> bool:
    return any(_avoid_term_usable(term) and term.lower() in blob for term in terms)


def media_candidates(db: Session, avoid_terms: Iterable[str]) -> list[models.Media]:
    rows = (
        db.query(models.Media)
        .filter(
            models.Media.media_type == "manga",
            models.Media.is_missing == False,  # noqa: E712
            models.Media.duplicate_status.notin_(list(HIDDEN_DUPLICATE_STATUSES)),
        )
        .order_by(models.Media.rating.desc(), models.Media.favorite.desc(), models.Media.id.desc())
        .all()
    )

    candidates = []
    for media in rows:
        blob = _text_blob(media)
        if _matches_any(blob, avoid_terms):
            continue
        candidates.append(media)
    return candidates


def score_candidate(media: models.Media, preferences: dict) -> tuple[float, list[str]]:
    positive_terms = _as_list(preferences.get("positive_terms"))
    tag_names = [tag.name for tag in media.tags]
    profile_keywords = _profile_keywords(media)
    blob = _text_blob(media)
    score = float((media.rating or 0) * 12)
    if media.favorite:
        score += 18
    if media.view_status == "viewed":
        score -= 8
    if media.view_status == "viewing":
        score -= 3
    if media.page_count:
        if preferences.get("length") == "short" and media.page_count <= 35:
            score += 8
        elif preferences.get("length") == "long" and media.page_count >= 80:
            score += 8
        elif preferences.get("length") == "medium" and 30 <= media.page_count <= 90:
            score += 6

    matched = []
    for term in positive_terms:
        key = term.lower()
        if not key:
            continue
        if key in blob:
            matched.append(term)
            if any(key in tag.name.lower() for tag in media.tags):
                score += 18
            elif any(key in item.lower() for item in profile_keywords):
                score += 16
            else:
                score += 10

    # Keep unrated libraries usable by adding a small deterministic freshness
    # signal after preference matches.
    score += min(media.id or 0, 5000) / 5000
    return score, _dedupe(matched + tag_names[:3] + profile_keywords[:5])


def local_reason(media: models.Media, matched_tags: list[str]) -> str:
    bits = []
    if matched_tags:
        bits.append(f"匹配到 {', '.join(matched_tags[:3])}")
    if media.rating:
        bits.append(f"已有 {media.rating} 星评分")
    if media.favorite:
        bits.append("你收藏过它")
    if media.page_count:
        bits.append(f"{media.page_count} 页，长度比较明确")
    if media.ai_profile and media.ai_profile.content_summary:
        bits.append(media.ai_profile.content_summary[:80])
    if not bits:
        bits.append("和当前输入在标题、作者或标签上有接近点")
    return "；".join(bits) + "。"


def ai_rank_and_explain(query: str, scored: list[dict], limit: int) -> tuple[dict[int, str], Optional[str]]:
    if not deepseek_configured() or not scored:
        return {}, None

    compact_candidates = [
        {
            "id": item["media"].id,
            "title": item["media"].title,
            "artist": item["media"].artist,
            "tags": [tag.name for tag in item["media"].tags[:8]],
            "profile": {
                "summary": item["media"].ai_profile.content_summary if item["media"].ai_profile else "",
                "style_tags": _profile_list(item["media"].ai_profile.style_tags) if item["media"].ai_profile else [],
                "story_tags": _profile_list(item["media"].ai_profile.story_tags) if item["media"].ai_profile else [],
                "tone_tags": _profile_list(item["media"].ai_profile.tone_tags) if item["media"].ai_profile else [],
                "keywords": _profile_list(item["media"].ai_profile.recommendation_keywords) if item["media"].ai_profile else [],
            },
            "metadata": {
                "summary": item["media"].metadata_profile.external_summary if item["media"].metadata_profile else "",
                "tags": _profile_list(item["media"].metadata_profile.external_tags) if item["media"].metadata_profile else [],
                "artist": item["media"].metadata_profile.parsed_artist if item["media"].metadata_profile else "",
                "parody": item["media"].metadata_profile.parody if item["media"].metadata_profile else "",
                "language": item["media"].metadata_profile.language if item["media"].metadata_profile else "",
                "confidence": item["media"].metadata_profile.confidence if item["media"].metadata_profile else 0,
            },
            "rating": item["media"].rating,
            "favorite": item["media"].favorite,
            "page_count": item["media"].page_count,
            "matched": item["matched_tags"],
        }
        for item in scored[:40]
    ]
    system = (
        "你是本地漫画库推荐排序器。只允许从候选 id 中选择。"
        "推荐理由要简洁，聚焦画风、题材、篇幅、氛围、标签匹配。只输出 JSON。"
    )
    user = {
        "query": query,
        "limit": limit,
        "candidates": compact_candidates,
        "schema": {"items": [{"id": 1, "reason": "中文推荐理由"}]},
    }
    try:
        content = call_deepseek(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
            ],
            temperature=0.2,
            max_tokens=1000,
        )
        parsed = _safe_json_object(content)
        items = parsed.get("items") if isinstance(parsed.get("items"), list) else []
        reasons = {}
        for item in items:
            try:
                media_id = int(item.get("id"))
            except (TypeError, ValueError):
                continue
            reason = str(item.get("reason") or "").strip()
            if reason:
                reasons[media_id] = reason[:160]
        return reasons, None
    except RuntimeError as exc:
        return {}, str(exc)


def recommend_manga(db: Session, query: str, limit: int, avoid_tags: Iterable[str], preferred_tags: Iterable[str]) -> dict:
    preferences, ai_enabled, message = parse_preferences(query, avoid_tags, preferred_tags)
    candidates = media_candidates(db, preferences.get("avoid_terms") or [])
    scored = []
    for media in candidates:
        score, matched = score_candidate(media, preferences)
        scored.append({"media": media, "score": score, "matched_tags": matched})
    scored.sort(key=lambda item: item["score"], reverse=True)

    ai_reasons, ai_message = ai_rank_and_explain(query, scored, limit)
    if ai_message and not message:
        message = ai_message

    by_id = {item["media"].id: item for item in scored}
    ordered_items = []
    for media_id in ai_reasons:
        item = by_id.get(media_id)
        if item and item not in ordered_items:
            ordered_items.append(item)
    for item in scored:
        if len(ordered_items) >= limit:
            break
        if item not in ordered_items:
            ordered_items.append(item)

    recommendations = []
    for item in ordered_items[:limit]:
        media = item["media"]
        recommendations.append(
            {
                "media": media,
                "reason": ai_reasons.get(media.id) or local_reason(media, item["matched_tags"]),
                "matched_tags": item["matched_tags"],
                "score": round(float(item["score"]), 2),
            }
        )

    return {
        "recommendations": recommendations,
        "parsed_preferences": preferences,
        "ai_enabled": ai_enabled,
        "candidate_count": len(candidates),
        "message": message,
    }
