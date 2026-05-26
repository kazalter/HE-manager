import json
import logging
import re
from typing import Iterable, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from sqlalchemy.orm import Session

from . import ai_config, manga_retrievers, manga_search, models

log = logging.getLogger(__name__)

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


VALID_INTENTS = ("by_author", "by_style", "similar_to", "browse")


def _normalize_intent(value: object, slots: dict) -> str:
    """Coerce the LLM's `intent` to one of VALID_INTENTS.

    Falls back to `browse` when the model emits something we don't expect.
    `by_author` and `similar_to` are also downgraded to `by_style` if their
    required slot is empty (e.g. model said by_author but never extracted
    an artist name).
    """
    raw = str(value or "").strip().lower()
    if raw not in VALID_INTENTS:
        return "browse"
    if raw == "by_author" and not slots.get("artists"):
        return "by_style"
    if raw == "similar_to" and not slots.get("similar_to_title"):
        return "by_style"
    return raw


def parse_preferences(query: str, avoid_tags: Iterable[str], preferred_tags: Iterable[str]) -> tuple[dict, bool, Optional[str]]:
    """Parse the user query into an `intent` plus retrieval slots.

    Output dict keys:
      intent          one of by_author / by_style / similar_to / browse
      slots           intent-specific structured fields (see prompt)
      positive_terms  legacy flat token list (still consumed by the BM25
                      + vector layer until per-intent retrievers fully
                      replace it)
      avoid_terms     legacy flat avoid list
      tone            legacy tone list
      length          short | medium | long | any
    """
    fallback = _heuristic_preferences(query, avoid_tags, preferred_tags)
    if not deepseek_configured():
        return fallback, False, "未配置 DEEPSEEK_API_KEY，已使用本地规则推荐。"

    system = (
        "你是本地漫画库的偏好解析器，把用户自然语言查询拆成结构化意图 (intent) 和槽位 (slots)。"
        "只输出 JSON，不输出解释。"
        "\n\n"
        "intent 必须是以下之一："
        "\n  - by_author    : 用户明确指名某个作者/画师/社团。slots.artists 必填。"
        "\n  - similar_to   : 用户引用了具体一本作品名要求'类似的'。slots.similar_to_title 必填。"
        "\n  - by_style     : 用户描述题材/画风/氛围/篇幅。slots.themes / tone / length 视情况填。"
        "\n  - browse       : 用户没给明确条件（'随便'、'看点什么'），返回这个。"
        "\n\n"
        "slots 字段说明（按 intent 取需要的，其余留空/省略）："
        "\n  artists          : 作者/画师/社团名列表（拼写按用户原样保留，不要拼接前缀）"
        "\n  similar_to_title : 引用作品名（原样字符串）"
        "\n  themes           : 题材/类型关键词（青梅竹马、JK、寝取り、...）"
        "\n  tone             : 氛围/画风形容（治愈、温馨、黑暗、画风精细、剧情向、...）"
        "\n  length           : short | medium | long | any"
        "\n  avoid_terms      : 用户明确不想要的内容"
        "\n  positive_terms   : artists+themes+tone 全部去重后的扁平内容词列表（不要带结构词如'作者'/'我'/'想看'）"
        "\n\n"
        "示例：\n"
        "Q: 我想看作者mignon的作品\n"
        "A: {\"intent\":\"by_author\",\"slots\":{\"artists\":[\"mignon\"],\"length\":\"any\"},"
        "\"positive_terms\":[\"mignon\"],\"avoid_terms\":[],\"tone\":[],\"length\":\"any\"}\n"
        "\n"
        "Q: 画师ぽるのいぶき的本子，不要黑暗\n"
        "A: {\"intent\":\"by_author\",\"slots\":{\"artists\":[\"ぽるのいぶき\"],\"avoid_terms\":[\"黑暗\"],"
        "\"length\":\"any\"},\"positive_terms\":[\"ぽるのいぶき\"],\"avoid_terms\":[\"黑暗\"],"
        "\"tone\":[],\"length\":\"any\"}\n"
        "\n"
        "Q: 想看治愈系短篇，画风精细一点\n"
        "A: {\"intent\":\"by_style\",\"slots\":{\"themes\":[\"治愈\"],\"tone\":[\"画风精细\"],\"length\":\"short\"},"
        "\"positive_terms\":[\"治愈\",\"画风精细\"],\"avoid_terms\":[],\"tone\":[\"画风精细\"],\"length\":\"short\"}\n"
        "\n"
        "Q: 类似《お姉さんと一週間》那种感觉的长篇剧情向\n"
        "A: {\"intent\":\"similar_to\",\"slots\":{\"similar_to_title\":\"お姉さんと一週間\","
        "\"tone\":[\"剧情向\"],\"length\":\"long\"},\"positive_terms\":[\"剧情向\"],"
        "\"avoid_terms\":[],\"tone\":[\"剧情向\"],\"length\":\"long\"}\n"
        "\n"
        "Q: 随便推荐点轻松的\n"
        "A: {\"intent\":\"browse\",\"slots\":{\"tone\":[\"轻松\"],\"length\":\"any\"},"
        "\"positive_terms\":[\"轻松\"],\"avoid_terms\":[],\"tone\":[\"轻松\"],\"length\":\"any\"}\n"
    )
    user = {
        "query": query,
        "avoid_tags": list(avoid_tags),
        "preferred_tags": list(preferred_tags),
    }
    try:
        content = call_deepseek(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
            ],
            temperature=0,
            max_tokens=900,
        )
        parsed = _safe_json_object(content)
        if not parsed:
            return fallback, True, "AI 偏好解析失败，已使用本地规则推荐。"

        slots_raw = parsed.get("slots") if isinstance(parsed.get("slots"), dict) else {}
        slots = {
            "artists": _as_list(slots_raw.get("artists")),
            "similar_to_title": str(slots_raw.get("similar_to_title") or "").strip() or None,
            "themes": _as_list(slots_raw.get("themes")),
            "tone": _as_list(slots_raw.get("tone")),
            "length": str(slots_raw.get("length") or parsed.get("length") or "any"),
            "avoid_terms": _as_list(slots_raw.get("avoid_terms")),
        }
        intent = _normalize_intent(parsed.get("intent"), slots)

        # Legacy flat fields: merge LLM output with heuristic fallback so a
        # short LLM response doesn't drop tokens the heuristic catches.
        result = {
            "intent": intent,
            "slots": slots,
            "positive_terms": _dedupe(_as_list(parsed.get("positive_terms")) + fallback["positive_terms"]),
            "avoid_terms": _dedupe(_as_list(parsed.get("avoid_terms"))
                                   + slots["avoid_terms"]
                                   + fallback["avoid_terms"]),
            "tone": _as_list(parsed.get("tone")) or slots["tone"],
            "length": str(parsed.get("length") or slots["length"] or "any"),
        }
        return result, True, None
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
    legacy_positive = [term for term in terms if term not in noise and len(term) > 1]
    # Re-tokenize each leftover term so a single-chunk regex hit like
    # "想看作者mignon的作品" gets split into atomic content words instead of
    # being passed downstream as one positive_term (which contaminated the
    # vector query with filler vocabulary and the BM25 surface with garbage).
    positive_terms: list[str] = []
    for term in legacy_positive:
        # tokenize handles its own stopword filtering. If it returns nothing,
        # the chunk was 100% structural/filler ("推荐一些作品" → 推荐/一些/作品
        # all stopwords) — keeping the whole chunk as a fallback would
        # contaminate the BM25 surface and embedding query, AND prevent
        # the intent router from correctly classifying this as a browse query.
        positive_terms.extend(manga_search.tokenize(term))
    positive_terms = _dedupe(list(preferred_tags) + positive_terms)
    avoid_terms = _dedupe(list(avoid_tags))

    # Heuristic intent classification. Tight set of patterns — when in
    # doubt, fall through to by_style because that's the safest router
    # (BM25 + vector hybrid, same as the pre-Phase-3 default).
    intent, slots = _classify_intent_heuristic(query, positive_terms, avoid_terms)

    return {
        "intent": intent,
        "slots": slots,
        "positive_terms": positive_terms,
        "avoid_terms": avoid_terms,
        "tone": [],
        "length": slots["length"],
    }


# Substrings that indicate the user wants results FROM a specific creator.
# Conservative — when matched we additionally require at least one positive
# token to actually fill the artists slot.
_BY_AUTHOR_HINTS = ("作者", "画师", "画家", "社团", "の作品")
_SIMILAR_HINTS = ("类似", "similar to", "像", "差不多的", "一样的", "風格的")


def _classify_intent_heuristic(
    query: str,
    positive_terms: list[str],
    avoid_terms: list[str],
) -> tuple[str, dict]:
    """Best-effort intent + slot extraction without an LLM.

    Returns (intent, slots) where slots matches the LLM-path shape. Errs
    toward `by_style` — the least-surprising default that preserves the
    pre-Phase-3 hybrid retrieval behaviour.
    """
    q_lower = (query or "").lower()
    length = "any"
    if any(kw in q_lower for kw in ("短篇", "short")):
        length = "short"
    elif any(kw in q_lower for kw in ("长篇", "long")):
        length = "long"
    elif any(kw in q_lower for kw in ("中篇", "medium")):
        length = "medium"

    slots = {
        "artists": [],
        "similar_to_title": None,
        "themes": [],
        "tone": [],
        "length": length,
        "avoid_terms": list(avoid_terms),
    }

    has_author_hint = any(hint in q_lower for hint in _BY_AUTHOR_HINTS)
    has_similar_hint = any(hint in q_lower for hint in _SIMILAR_HINTS)

    if has_author_hint and positive_terms:
        # No entity tagger — pass all surviving positive terms as
        # potential artist names. The by_author retriever does its own
        # filtering against the real artist set.
        slots["artists"] = list(positive_terms)
        return "by_author", slots

    if has_similar_hint and positive_terms:
        # Heuristic can't reliably extract a real title from free text;
        # down-route to by_style so we don't claim a similar_to intent
        # we can't service.
        slots["themes"] = list(positive_terms)
        return "by_style", slots

    if not positive_terms:
        return "browse", slots

    slots["themes"] = list(positive_terms)
    return "by_style", slots


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

# --- RAG fusion --------------------------------------------------------------
# Reciprocal Rank Fusion combines two ranked lists (BM25 + vector) into one.
# Standard k = 60 from the original RRF paper. FUSION_WEIGHT scales the raw
# RRF score (which is small — top hit in both lists ≈ 2/61 = 0.033) up to a
# range that competes with the prior_score (rating ≤ 50 + favorite 18). With
# W = 1000:
#   * top in both lists: ~33 — outranks a 3-star prior (30), beats by ~30%
#   * top in BM25 only:  ~16 — beats a 1-star prior (10) but loses to 4-star
# The matched-pool filter still excludes anything with fused == 0 when the
# user provided positive_tokens, so unrelated high-rating items don't appear
# even if their prior would otherwise win.
# Phase 3 — per-intent score weights. These scale a 0..1 retrieval score
# (or RRF score) into a range that competes with _prior_score.
BY_AUTHOR_WEIGHT = 50.0       # 1.0 exact match -> 50, beats a 5-star prior
SIMILAR_TO_WEIGHT = 60.0      # cosine 0.6 -> 36, comparable to a 4-star prior
BROWSE_PASSTHROUGH = True     # browse scoring is already in prior-scale units

RRF_K = 60
FUSION_WEIGHT = 1000.0
VECTOR_POOL = 60  # how many top vector neighbours we even consider for fusion

# MiniLM-multilingual cosine noise floors are query-dependent: for an exact
# author lookup the noise sits around 0.27, but for vague "doujin theme"
# queries the model maps half the library to ≈ 0.5 from shared vocabulary
# alone. A fixed threshold can't separate both, so we use an *adaptive*
# threshold: a hit only counts if it scores at least the corpus 90th
# percentile plus a small gap. This auto-tightens when the query is fuzzy
# (most of the corpus would be "similar") and relaxes when the query has
# a clear semantic target. The absolute floor stops single rare-sim
# outliers from dragging the threshold low enough to admit garbage.
VECTOR_MIN_SIMILARITY = 0.30
# Empirical: on this library MiniLM-multilingual gives real-match cosines
# in the 0.58–0.67 range, while p90 of "everything that's not a match"
# sits at 0.27–0.47 depending on the query. A 0.10 gap above p90 cleanly
# rejects the noise tail while admitting genuinely-semantic hits like
# "黑暗系恐怖物" → "Nightmare from Goddess" (0.46 vs p90 0.28).
VECTOR_GAP_OVER_P90 = 0.10


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
        # Retrieval provenance — LLM can use this to weigh keyword vs
        # semantic match. None = the candidate didn't make that retriever's
        # top-K.
        "bm25_rank": item.get("bm25_rank"),
        "vec_rank": item.get("vec_rank"),
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


def _empty_result(
    preferences: dict,
    ai_enabled: bool,
    candidate_count: int,
    message: str,
) -> dict:
    return {
        "recommendations": [],
        "parsed_preferences": preferences,
        "ai_enabled": ai_enabled,
        "candidate_count": candidate_count,
        "message": message,
    }


def _route_by_author(
    preferences: dict,
    candidates: list[models.Media],
    avoid_tokens: list[str],
) -> tuple[list[dict], Optional[str]]:
    """by_author retrieval → scored items.

    Returns (scored, error_message). When the artist isn't in the library
    we surface a specific message that names what the user asked for,
    rather than the generic "nothing matched" fallback.
    """
    slots = preferences.get("slots") or {}
    artists = _as_list(slots.get("artists")) or _as_list(preferences.get("positive_terms"))
    if not artists:
        return [], "未识别出作者/画师名。试着把名字单独写出来，例如「想看 hahakigi 的作品」。"

    # Avoid filter: build field tokens for the avoid check only.
    if avoid_tokens:
        ft = {m.id: manga_search.build_field_tokens(m) for m in candidates}
        candidates = [m for m in candidates if not manga_search.avoid_hit(ft[m.id], avoid_tokens)]

    ranks = manga_retrievers.by_author(None, artists, candidates)
    if not ranks:
        listed = ", ".join(artists[:3])
        return [], (
            f"manga 库里没找到作者「{listed}」的作品。"
            "可能这本不在库里，或名字拼写跟库里记录的不一致（试试日文原名 / 英文转写）。"
        )

    by_id = {m.id: m for m in candidates}
    scored = []
    for media_id, retrieval_score in ranks:
        media = by_id.get(media_id)
        if not media:
            continue
        fused = retrieval_score * BY_AUTHOR_WEIGHT
        prior = _prior_score(media, preferences)
        scored.append({
            "media": media,
            "text_score": retrieval_score,  # 0..1 — surfaced to LLM for reason grounding
            "prior": prior,
            "matched_tags": list(artists)[:3],
            "fused": fused,
            "bm25_rank": None,
            "vec_rank": None,
            "score": fused + prior,
        })
    return scored, None


def _route_by_style(
    preferences: dict,
    candidates: list[models.Media],
    avoid_tokens: list[str],
) -> tuple[list[dict], Optional[str]]:
    """BM25 + vector hybrid → RRF-fused scored items."""
    slots = preferences.get("slots") or {}
    # Prefer the structured slot, fall back to the flat positive_terms list.
    query_terms = (
        _as_list(slots.get("themes"))
        + _as_list(slots.get("tone"))
        + _as_list(preferences.get("positive_terms"))
    )
    query_terms = _dedupe(query_terms)
    if not query_terms:
        return [], None  # Caller will down-route to browse.

    bm25_ranks, vec_ranks, matched_tags = manga_retrievers.by_style(
        candidates, query_terms, avoid_tokens,
    )
    if not bm25_ranks and not vec_ranks:
        listed = ", ".join(query_terms[:3])
        return [], (
            f"未在 manga 库中找到与「{listed}」相关的条目。"
            "可以试试换关键词、或浏览其它作品。"
        )

    bm25_rank_by_id = {mid: idx for idx, (mid, _s) in enumerate(bm25_ranks)}
    vec_rank_by_id = {mid: idx for idx, (mid, _s) in enumerate(vec_ranks)}

    # Build the scored list. Include every candidate that landed in either
    # retriever's pool — the matched_pool filter (fused > 0) trims later.
    in_pool = set(bm25_rank_by_id) | set(vec_rank_by_id)
    by_id = {m.id: m for m in candidates}
    scored: list[dict] = []
    for media_id in in_pool:
        media = by_id.get(media_id)
        if not media:
            continue
        rrf = 0.0
        if media_id in bm25_rank_by_id:
            rrf += 1.0 / (RRF_K + bm25_rank_by_id[media_id] + 1)
        if media_id in vec_rank_by_id:
            rrf += 1.0 / (RRF_K + vec_rank_by_id[media_id] + 1)
        fused = rrf * FUSION_WEIGHT
        prior = _prior_score(media, preferences)
        # text_score: prefer BM25 raw score when present (more discriminating
        # than RRF's tiny 0.01-scale numbers when surfaced to the LLM).
        text_score_raw = next((s for mid, s in bm25_ranks if mid == media_id), 0.0)
        scored.append({
            "media": media,
            "text_score": text_score_raw,
            "prior": prior,
            "matched_tags": matched_tags.get(media_id, []),
            "fused": fused,
            "bm25_rank": bm25_rank_by_id.get(media_id),
            "vec_rank": vec_rank_by_id.get(media_id),
            "score": fused + prior,
        })
    return scored, None


def _route_similar_to(
    db: Session,
    preferences: dict,
    candidates: list[models.Media],
    avoid_tokens: list[str],
) -> tuple[list[dict], Optional[str]]:
    """Find the referenced manga, then cosine-rank neighbours."""
    slots = preferences.get("slots") or {}
    title_hint = (slots.get("similar_to_title") or "").strip()
    if not title_hint:
        return [], "没识别出你想参照的作品名，试着把书名加上书名号引起来，例如「类似《X》」。"

    # Avoid filter: build field tokens once.
    surviving_ids: Optional[set[int]] = None
    if avoid_tokens:
        ft = {m.id: manga_search.build_field_tokens(m) for m in candidates}
        surviving_ids = {m.id for m in candidates
                         if not manga_search.avoid_hit(ft[m.id], avoid_tokens)}

    referenced, neighbours = manga_retrievers.similar_to(
        db, title_hint, candidates, surviving_ids=surviving_ids,
    )
    if not referenced:
        return [], (
            f"manga 库里找不到名字像「{title_hint}」的作品。"
            "可以贴更完整的标题、或试试关键词搜索（'类似 X 题材的'）。"
        )
    if not neighbours:
        return [], (
            f"找到了「{referenced.title}」，但暂时没生成它的语义画像，无法找类似作品。"
            "去「内容画像」面板对它跑一次分析后再试。"
        )

    by_id = {m.id: m for m in candidates}
    scored: list[dict] = []
    for rank, (media_id, sim) in enumerate(neighbours):
        media = by_id.get(media_id)
        if not media:
            continue
        fused = sim * SIMILAR_TO_WEIGHT
        prior = _prior_score(media, preferences)
        scored.append({
            "media": media,
            "text_score": sim,
            "prior": prior,
            "matched_tags": [f"类似《{referenced.title[:30]}》"],
            "fused": fused,
            "bm25_rank": None,
            "vec_rank": rank,
            "score": fused + prior,
        })
    return scored, None


def _route_browse(
    preferences: dict,
    candidates: list[models.Media],
    avoid_tokens: list[str],
) -> tuple[list[dict], Optional[str]]:
    """No-query default: rating + favorite + freshness."""
    ranks = manga_retrievers.browse(candidates, avoid_tokens)
    if not ranks:
        return [], "manga 库为空，或所有作品都被排除标签过滤掉了。"

    by_id = {m.id: m for m in candidates}
    scored: list[dict] = []
    for media_id, retrieval_score in ranks:
        media = by_id.get(media_id)
        if not media:
            continue
        # Browse scores are already in prior-scale units, so we put them
        # in `score` directly and leave fused=0 to indicate "no query".
        scored.append({
            "media": media,
            "text_score": 0.0,
            "prior": retrieval_score,
            "matched_tags": [],
            "fused": 0.0,
            "bm25_rank": None,
            "vec_rank": None,
            "score": retrieval_score,
        })
    return scored, None


def recommend_manga(
    db: Session,
    query: str,
    limit: int,
    avoid_tags: Iterable[str],
    preferred_tags: Iterable[str],
) -> dict:
    preferences, ai_enabled, message = parse_preferences(query, avoid_tags, preferred_tags)
    intent: str = preferences.get("intent") or "by_style"

    avoid_tokens = [t for t in _query_tokens(preferences, "avoid_terms") if _avoid_token_usable(t)]

    candidates = manga_retrievers.visible_manga(db)
    candidate_count_total = len(candidates)

    # ---- Route ----------------------------------------------------------
    if intent == "by_author":
        scored, err = _route_by_author(preferences, candidates, avoid_tokens)
    elif intent == "similar_to":
        scored, err = _route_similar_to(db, preferences, candidates, avoid_tokens)
    elif intent == "browse":
        scored, err = _route_browse(preferences, candidates, avoid_tokens)
    else:  # by_style — also the down-route target when other intents have empty slots
        scored, err = _route_by_style(preferences, candidates, avoid_tokens)
        # If by_style had no content terms at all, gracefully fall back to browse.
        if not scored and err is None:
            scored, err = _route_browse(preferences, candidates, avoid_tokens)
            intent = "browse"
            preferences["intent"] = "browse"

    if err:
        return _empty_result(preferences, ai_enabled, candidate_count_total, err)

    # ---- Sort by total score -------------------------------------------
    scored.sort(key=lambda item: item["score"], reverse=True)

    # ---- LLM rerank + diversity + response ------------------------------
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
