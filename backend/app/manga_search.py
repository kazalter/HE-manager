"""Token-based ranking for manga recommendations.

Replaces the previous substring-on-blob matching in recommendations.py with
field-weighted token matching + IDF rarity boost. Conceptually BM25-lite:

- tokenize CJK with jieba when available, else fall back to char-unigrams +
  bigrams so two-character Chinese words still hit;
- build per-media token sets for several *named* fields (tag, meta_tag,
  profile keyword, parody, artist, title, summary) so we can weight them
  separately and never let OCR text or generative summaries dominate;
- IDF over the candidate corpus so rare terms (e.g. an obscure parody name)
  outweigh common ones (e.g. "短篇");
- avoid terms are only checked against the *high-signal* fields (tag /
  meta_tag / parody / artist / title) — never summary or AI-generated text,
  which previously caused false-positive filtering.

Public API:
    tokenize(text)           -> list[str]
    build_field_tokens(media) -> dict[str, set[str]]
    compute_idf(by_media)    -> dict[str, float]
    score_text_match(fields, query_tokens, idf) -> (score, matched_terms)
    avoid_hit(fields, avoid_tokens) -> bool
"""
from __future__ import annotations

import json
import math
import re
from typing import Iterable, Optional

from . import models


def _json_list(value: Optional[str]) -> list[str]:
    """Permissive parse: bad JSON or non-list returns []."""
    if not value:
        return []
    try:
        parsed = json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item) for item in parsed if str(item).strip()]

try:  # jieba is optional — install for better CJK tokenization
    import jieba  # type: ignore

    _HAS_JIEBA = True
    jieba.setLogLevel(60)  # quiet
except ImportError:
    _HAS_JIEBA = False


# Field weights. Higher = stronger evidence the manga is what the user asked
# for. Tuned so a tag hit dominates a title-only hit, but title still beats
# "the AI summary mentions the word once".
FIELD_WEIGHTS: dict[str, float] = {
    "tag": 5.0,
    "meta_tag": 3.5,
    "profile_kw": 3.0,
    "parody": 2.5,
    "artist": 2.5,
    "title": 1.5,
    "summary": 0.8,
}

# Fields consulted when filtering by avoid_terms. Deliberately excludes
# `summary` and `profile_kw` (LLM-generated, noisy) and OCR (never indexed).
AVOID_FIELDS: tuple[str, ...] = ("tag", "meta_tag", "parody", "artist", "title")

_LATIN_TOKEN_RE = re.compile(r"[a-z0-9][a-z0-9_+\-]*")

# Tokens that show up as filler in free-form queries. Stripped before
# scoring so they don't drag IDF down or produce noise hits.
#
# Includes:
#   - Verbs / fillers      ("想看", "推荐", ...)
#   - Generic catalog nouns ("漫画", "作品", "类型", ...)
#   - Pronouns / determiners that latch onto everything ("我", "他", "她",
#     "这个", "那个", "你") — without these, queries like "我想看作者X的作品"
#     match every manga whose title contains "我".
#   - Structural meta words ("作者", "画师", "画家") that the user uses to
#     *name* the slot they care about, not as a content word.
_STOPWORDS: frozenset[str] = frozenset({
    # zh — verbs / fillers
    "想看", "想要", "推荐", "一点", "一些", "看看", "求", "的", "了",
    "请", "帮我", "找", "类似", "类型", "看", "想", "有", "没有", "是",
    # zh — generic catalog nouns
    "漫画", "作品", "本子", "本", "图", "图片", "图集", "故事",
    # zh — meta slot names (NOT content)
    "作者", "画师", "画家", "社团", "标签",
    # zh — pronouns / determiners
    "我", "你", "他", "她", "它", "这", "那", "这个", "那个", "这种", "那种",
    "哪", "哪个", "什么", "怎么", "谁",
    # ja — very common fillers
    "おすすめ", "好き", "もの", "こと",
    # en
    "want", "like", "recommend", "manga", "comic", "find", "show",
    "please", "give", "looking",
    "i", "me", "my", "you", "the", "a", "an", "of", "for", "with",
})


def _is_cjk(ch: str) -> bool:
    return "぀" <= ch <= "ヿ" or "㐀" <= ch <= "鿿"


def _cjk_ngrams(s: str) -> list[str]:
    """Unigrams + bigrams over a pure-CJK run. Used when jieba is unavailable."""
    out: list[str] = []
    if len(s) >= 1:
        out.extend(s)
    for i in range(len(s) - 1):
        out.append(s[i:i + 2])
    return out


def tokenize(text: Optional[str]) -> list[str]:
    """Lowercase + tokenize. Returns a flat list (may contain duplicates)."""
    if not text:
        return []
    text = text.lower()
    tokens: list[str] = []

    # Latin / digit / hyphenated identifier
    tokens.extend(m.group(0) for m in _LATIN_TOKEN_RE.finditer(text))

    # Group consecutive CJK characters into runs, then tokenize each run
    run: list[str] = []
    for ch in text:
        if _is_cjk(ch):
            run.append(ch)
            continue
        if run:
            tokens.extend(_tokenize_cjk_run("".join(run)))
            run = []
    if run:
        tokens.extend(_tokenize_cjk_run("".join(run)))

    # Filter stopwords and 1-char Latin (CJK 1-chars are kept as anchors)
    out: list[str] = []
    for t in tokens:
        if not t or t in _STOPWORDS:
            continue
        if len(t) == 1 and not _is_cjk(t):
            continue
        out.append(t)
    return out


def _tokenize_cjk_run(run: str) -> list[str]:
    if _HAS_JIEBA:
        cut = [t.strip() for t in jieba.cut(run, HMM=True)]
        # jieba emits single-char tokens; keep CJK singles, drop punctuation
        return [t for t in cut if t]
    return _cjk_ngrams(run)


def _tokens_from_items(items: Iterable[str]) -> set[str]:
    """Tokenize each item AND keep the lowercased whole as an exact-match key."""
    out: set[str] = set()
    for raw in items:
        if not raw:
            continue
        s = str(raw).strip()
        if not s:
            continue
        out.update(tokenize(s))
        out.add(s.lower())
    return out


def _tokens_from_text(text: Optional[str]) -> set[str]:
    return set(tokenize(text))


def _tag_field(media: models.Media) -> list[str]:
    return [t.name for t in media.tags]


def _meta_tag_field(media: models.Media) -> list[str]:
    metadata = media.metadata_profile
    if not metadata:
        return []
    return _json_list(metadata.external_tags)


def _profile_kw_field(media: models.Media) -> list[str]:
    profile = media.ai_profile
    if not profile:
        return []
    out: list[str] = []
    for raw in (profile.style_tags, profile.story_tags, profile.tone_tags, profile.recommendation_keywords):
        out.extend(_json_list(raw))
    return out


def _parody_field(media: models.Media) -> str:
    metadata = media.metadata_profile
    return (metadata.parody or "") if metadata else ""


def _artist_field(media: models.Media) -> str:
    parts: list[str] = []
    if media.artist:
        parts.append(media.artist)
    metadata = media.metadata_profile
    if metadata:
        if metadata.parsed_artist:
            parts.append(metadata.parsed_artist)
        if metadata.parsed_circle:
            parts.append(metadata.parsed_circle)
    return " ".join(parts)


def _title_field(media: models.Media) -> str:
    metadata = media.metadata_profile
    if metadata and metadata.parsed_title:
        return metadata.parsed_title
    return media.title or ""


def _summary_field(media: models.Media) -> str:
    parts: list[str] = []
    profile = media.ai_profile
    if profile and profile.content_summary:
        parts.append(profile.content_summary)
    metadata = media.metadata_profile
    if metadata and metadata.external_summary:
        parts.append(metadata.external_summary)
    return " ".join(parts)


def build_field_tokens(media: models.Media) -> dict[str, set[str]]:
    return {
        "tag": _tokens_from_items(_tag_field(media)),
        "meta_tag": _tokens_from_items(_meta_tag_field(media)),
        "profile_kw": _tokens_from_items(_profile_kw_field(media)),
        "parody": _tokens_from_text(_parody_field(media)),
        "artist": _tokens_from_items(filter(None, _artist_field(media).split())),
        "title": _tokens_from_text(_title_field(media)),
        "summary": _tokens_from_text(_summary_field(media)),
    }


def compute_idf(field_tokens_by_media: dict[int, dict[str, set[str]]]) -> dict[str, float]:
    """Standard plus-one-smoothed IDF over the union of every field's tokens."""
    n_docs = len(field_tokens_by_media) or 1
    doc_freq: dict[str, int] = {}
    for fields in field_tokens_by_media.values():
        seen: set[str] = set()
        for tokens in fields.values():
            seen.update(tokens)
        for token in seen:
            doc_freq[token] = doc_freq.get(token, 0) + 1
    return {
        token: math.log(1 + (n_docs - df + 0.5) / (df + 0.5))
        for token, df in doc_freq.items()
    }


def score_text_match(
    fields: dict[str, set[str]],
    query_tokens: Iterable[str],
    idf: dict[str, float],
) -> tuple[float, list[str]]:
    """Sum weighted hits across fields, multiplied by per-token IDF.

    Returns (score, matched_terms). matched_terms preserves first-hit order
    so the caller can surface "what we matched on" to the user.
    """
    score = 0.0
    matched: list[str] = []
    seen_terms: set[str] = set()
    for token in query_tokens:
        if not token:
            continue
        token_weight = 0.0
        for field, weight in FIELD_WEIGHTS.items():
            if token in fields.get(field, ()):
                token_weight += weight
        if token_weight <= 0:
            continue
        score += token_weight * idf.get(token, 1.0)
        if token not in seen_terms:
            matched.append(token)
            seen_terms.add(token)
    return score, matched


def avoid_hit(fields: dict[str, set[str]], avoid_tokens: Iterable[str]) -> bool:
    for token in avoid_tokens:
        if not token:
            continue
        for field in AVOID_FIELDS:
            if token in fields.get(field, ()):
                return True
    return False
