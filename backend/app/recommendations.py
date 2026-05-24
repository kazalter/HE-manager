import json
import re
from typing import Iterable, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from sqlalchemy.orm import Session

from . import ai_config, manga_search, models

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
        "你是本地漫画库的偏好解析器，把用户自然语言查询拆成结构化偏好。"
        "只输出 JSON，不输出解释。"
        "\n\n"
        "硬规则："
        "\n1) positive_terms 是单个**实质内容词**列表（作者名 / 题材 / 画风 / 氛围 / 系列 / 原作），"
        "不要塞整句话，不要带'作者'、'画师'、'我'、'想看'这类结构词。"
        "\n2) 作者/画师名要单独拆出来，不要黏在前缀上。"
        "\n3) 拒绝意图的词进 avoid_terms。"
        "\n4) length 必须是 short / medium / long / any 之一。"
        "\n\n"
        "示例：\n"
        "Q: 我想看作者mignon的作品\n"
        "A: {\"positive_terms\": [\"mignon\"], \"avoid_terms\": [], \"tone\": [], \"length\": \"any\"}\n"
        "\n"
        "Q: 画师ぽるのいぶき的本子，不要黑暗\n"
        "A: {\"positive_terms\": [\"ぽるのいぶき\"], \"avoid_terms\": [\"黑暗\"], \"tone\": [], \"length\": \"any\"}\n"
        "\n"
        "Q: 想看治愈系短篇，画风精细一点\n"
        "A: {\"positive_terms\": [\"治愈\"], \"avoid_terms\": [], \"tone\": [\"画风精细\"], \"length\": \"short\"}\n"
        "\n"
        "Q: 类似[Circle (Artist)]那种感觉的长篇剧情向\n"
        "A: {\"positive_terms\": [\"Artist\"], \"avoid_terms\": [], \"tone\": [\"剧情\"], \"length\": \"long\"}\n"
    )
    user = {
        "query": query,
        "avoid_tags": list(avoid_tags),
        "preferred_tags": list(preferred_tags),
        "schema": {
            "positive_terms": ["实质内容词，单个拆开"],
            "avoid_terms": ["用户明确不想要的内容"],
            "tone": ["氛围/画风/节奏描述"],
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


def _profile_list(value: Optional[str]) -> list[str]:
    if not value:
        return []
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item) for item in parsed if str(item).strip()]


# --- Static priors (non-text signals) --------------------------------------

# Weights for non-text features. Tuned so a tag-relevant manga can outrank
# a high-rated but irrelevant one, while a favorite/high-rating still wins
# when nothing else differentiates.
RATING_WEIGHT = 10.0
FAVORITE_BONUS = 18.0
VIEWED_PENALTY = -20.0
VIEWING_PENALTY = -5.0
LENGTH_BONUS = 8.0
LENGTH_MEDIUM_BONUS = 6.0
ARTIST_DIVERSITY_CAP = 2
AI_RERANK_POOL = 60  # how many top-scored candidates DeepSeek gets to reorder


def _prior_score(media: models.Media, preferences: dict) -> float:
    """Score components that don't depend on the query text."""
    score = float((media.rating or 0) * RATING_WEIGHT)
    if media.favorite:
        score += FAVORITE_BONUS
    if media.view_status == "viewed":
        score += VIEWED_PENALTY
    elif media.view_status == "viewing":
        score += VIEWING_PENALTY
    if media.page_count:
        length = preferences.get("length")
        if length == "short" and media.page_count <= 35:
            score += LENGTH_BONUS
        elif length == "long" and media.page_count >= 80:
            score += LENGTH_BONUS
        elif length == "medium" and 30 <= media.page_count <= 90:
            score += LENGTH_MEDIUM_BONUS
    return score


def _avoid_token_usable(token: str) -> bool:
    """Filter out 1-char Latin tokens that would over-match.

    Tokens come from manga_search.tokenize() which already drops 1-char Latin,
    but DeepSeek's avoid_terms enter raw — keep this as a defence in depth.
    """
    token = token.strip()
    if not token:
        return False
    if any("぀" <= ch <= "ヿ" or "㐀" <= ch <= "鿿" for ch in token):
        return True
    return len(token) >= 3


def _query_tokens(preferences: dict, key: str) -> list[str]:
    """Tokenize each entry in preferences[key] and flatten."""
    out: list[str] = []
    for term in _as_list(preferences.get(key)):
        out.extend(manga_search.tokenize(term))
    return _dedupe(out)


# --- Reason synthesis ------------------------------------------------------

def local_reason(media: models.Media, matched_terms: list[str]) -> str:
    bits = []
    if matched_terms:
        bits.append(f"匹配到 {', '.join(matched_terms[:3])}")
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


# --- DeepSeek rerank -------------------------------------------------------

def _candidate_for_llm(item: dict) -> dict:
    media = item["media"]
    profile = media.ai_profile
    metadata = media.metadata_profile
    return {
        "id": media.id,
        "title": media.title,
        "artist": media.artist,
        "tags": [tag.name for tag in media.tags[:10]],
        "profile": {
            "summary": (profile.content_summary or "")[:200] if profile else "",
            "style_tags": _profile_list(profile.style_tags) if profile else [],
            "story_tags": _profile_list(profile.story_tags) if profile else [],
            "tone_tags": _profile_list(profile.tone_tags) if profile else [],
            "keywords": _profile_list(profile.recommendation_keywords) if profile else [],
        },
        "metadata": {
            "summary": (metadata.external_summary or "")[:160] if metadata else "",
            "tags": _profile_list(metadata.external_tags) if metadata else [],
            "artist": metadata.parsed_artist if metadata else "",
            "circle": metadata.parsed_circle if metadata else "",
            "parody": metadata.parody if metadata else "",
            "language": metadata.language if metadata else "",
        },
        "rating": media.rating,
        "favorite": media.favorite,
        "viewed": media.view_status == "viewed",
        "page_count": media.page_count,
        "matched": item["matched_tags"][:8],
        "text_score": round(float(item.get("text_score", 0)), 2),
    }


def ai_rank_and_explain(query: str, scored: list[dict], limit: int) -> tuple[dict[int, str], Optional[str]]:
    if not deepseek_configured() or not scored:
        return {}, None

    compact_candidates = [_candidate_for_llm(item) for item in scored[:AI_RERANK_POOL]]
    system = (
        "你是本地漫画库推荐排序器。任务：从候选中挑出最契合 query 的若干条，"
        "并写出简洁中文理由（≤80 字，紧扣画风/题材/篇幅/氛围/标签命中）。"
        "硬约束：1) 只允许使用候选里出现过的 id；2) 同一个 artist 最多 2 条；"
        "3) 已 viewed 的尽量靠后或舍弃；4) 输出严格 JSON，键名只能是 items。"
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
            max_tokens=1200,
        )
        parsed = _safe_json_object(content)
        items = parsed.get("items") if isinstance(parsed.get("items"), list) else []
        reasons: dict[int, str] = {}
        for item in items:
            try:
                media_id = int(item.get("id"))
            except (TypeError, ValueError):
                continue
            reason = str(item.get("reason") or "").strip()
            if reason:
                reasons[media_id] = reason[:200]
        return reasons, None
    except RuntimeError as exc:
        return {}, str(exc)


# --- Main entry ------------------------------------------------------------

def _artist_key(media: models.Media) -> str:
    """Stable diversity key. Prefers metadata-parsed artist over the raw one."""
    metadata = media.metadata_profile
    if metadata and metadata.parsed_artist:
        return metadata.parsed_artist.strip().lower()
    if media.artist:
        return media.artist.strip().lower()
    return f"__media_{media.id}"


def _apply_diversity(
    scored: list[dict],
    ai_reasons: dict[int, str],
    limit: int,
    artist_cap: int = ARTIST_DIVERSITY_CAP,
) -> list[dict]:
    """Take AI-reordered ids first, then fall back to local score; cap per artist."""
    by_id = {item["media"].id: item for item in scored}
    artist_count: dict[str, int] = {}
    chosen: list[dict] = []
    chosen_ids: set[int] = set()

    def _try_add(item: dict) -> bool:
        if item["media"].id in chosen_ids:
            return False
        key = _artist_key(item["media"])
        if artist_count.get(key, 0) >= artist_cap:
            return False
        chosen.append(item)
        chosen_ids.add(item["media"].id)
        artist_count[key] = artist_count.get(key, 0) + 1
        return True

    for media_id in ai_reasons:
        if len(chosen) >= limit:
            return chosen
        item = by_id.get(media_id)
        if item is not None:
            _try_add(item)

    for item in scored:
        if len(chosen) >= limit:
            return chosen
        _try_add(item)

    return chosen


def recommend_manga(
    db: Session,
    query: str,
    limit: int,
    avoid_tags: Iterable[str],
    preferred_tags: Iterable[str],
) -> dict:
    preferences, ai_enabled, message = parse_preferences(query, avoid_tags, preferred_tags)

    positive_tokens = _query_tokens(preferences, "positive_terms")
    avoid_tokens = [t for t in _query_tokens(preferences, "avoid_terms") if _avoid_token_usable(t)]

    rows = (
        db.query(models.Media)
        .filter(
            models.Media.media_type == "manga",
            models.Media.is_missing == False,  # noqa: E712
            models.Media.duplicate_status.notin_(list(HIDDEN_DUPLICATE_STATUSES)),
        )
        .all()
    )

    field_tokens_by_id: dict[int, dict[str, set[str]]] = {
        media.id: manga_search.build_field_tokens(media) for media in rows
    }
    idf = manga_search.compute_idf(field_tokens_by_id)

    scored: list[dict] = []
    for media in rows:
        fields = field_tokens_by_id[media.id]
        if avoid_tokens and manga_search.avoid_hit(fields, avoid_tokens):
            continue
        text_score, matched = manga_search.score_text_match(fields, positive_tokens, idf)
        prior = _prior_score(media, preferences)
        scored.append({
            "media": media,
            "text_score": text_score,
            "score": text_score + prior,
            "matched_tags": matched,
        })

    # Two-key sort: text-score first (so query relevance wins), prior as tiebreak.
    scored.sort(key=lambda item: (item["text_score"], item["score"]), reverse=True)

    # When the user provided concrete content terms, only show items that
    # actually matched at least one of them. Otherwise (browse mode), keep the
    # full pool ranked by prior. This prevents padding the result list with
    # zero-relevance high-rated picks when only a few real matches exist.
    if positive_tokens:
        matched_pool = [item for item in scored if item["text_score"] > 0]
        if not matched_pool:
            terms = ", ".join(_as_list(preferences.get("positive_terms"))[:3]) or query
            return {
                "recommendations": [],
                "parsed_preferences": preferences,
                "ai_enabled": ai_enabled,
                "candidate_count": len(scored),
                "message": (
                    f"未在 manga 库中找到与「{terms}」相关的条目。"
                    "可能这本不在库里，或作者名拼写不一致。"
                    "也试试换关键词、或浏览其它作品。"
                ),
            }
        scored = matched_pool

    ai_reasons, ai_message = ai_rank_and_explain(query, scored, limit)
    if ai_message and not message:
        message = ai_message

    ordered = _apply_diversity(scored, ai_reasons, limit)

    recommendations = []
    for item in ordered:
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
        "candidate_count": len(scored),
        "message": message,
    }
