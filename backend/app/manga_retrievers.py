"""Per-intent retrievers for the RAG router (Phase 3).

Each retriever returns a ranked list of (media_id, score) pairs over the
non-avoided candidate set. The score is meaningful **within** a retriever
but not comparable across retrievers — that's why the caller fuses them
by rank (RRF) instead of by raw score.

Strategies
==========
* by_author   — SQL exact / LIKE against media.artist, metadata.parsed_artist
                and metadata.parsed_circle. Whatever survives is the entire
                pool (no fuzzy expansion — if the user asked for a specific
                author, only that author counts).
* by_style    — BM25 over named fields + dense vector cosine + adaptive
                threshold. Same machinery the pre-Phase-3 path used, now
                wrapped behind a clean function with explicit pool size.
* similar_to  — Look up the referenced title with fuzzy matching against
                media.title / metadata.parsed_title, take its embedding,
                do cosine top-K. Optional: also expand by the same
                author's other works.
* browse      — Sort by (favorite, rating, -recency_of_last_open) and
                return the head of that list. No query content needed.

Each function is a free-standing helper — it doesn't import recommendations
itself, so circular-import risk is contained.
"""
from __future__ import annotations

import logging
from typing import Iterable, Optional

from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from . import manga_search, manga_vector, models

log = logging.getLogger(__name__)


# --- Common candidate pull --------------------------------------------------

HIDDEN_DUPLICATE_STATUSES = {"checking", "strong_duplicate", "suspected_duplicate"}


def visible_manga(db: Session) -> list[models.Media]:
    """All manga rows the recommender is allowed to consider.

    Excludes missing files and pending/confirmed duplicates. The list is
    fully materialised (with tag / profile / metadata relationships
    accessible) because every retriever needs the full row anyway.
    """
    return (
        db.query(models.Media)
        .filter(
            models.Media.media_type == "manga",
            models.Media.is_missing == False,  # noqa: E712
            models.Media.duplicate_status.notin_(list(HIDDEN_DUPLICATE_STATUSES)),
        )
        .all()
    )


# --- by_author --------------------------------------------------------------

def by_author(
    db: Session,
    artists: Iterable[str],
    candidates: list[models.Media],
) -> list[tuple[int, float]]:
    """Match candidates against any of the user-named artists.

    Each candidate is scored 0..1 based on the best evidence:
      1.0 — exact (case-insensitive) match on media.artist /
            metadata.parsed_artist / metadata.parsed_circle
      0.8 — partial match (user name is substring of, or contains, the
            stored name — handles minor romanisation differences)
      0.0 — no signal; excluded

    Returns sorted by score desc. Ties broken by (rating, favorite, id)
    so the better-curated work of the same author comes first.
    """
    targets = [str(a).strip().lower() for a in artists if str(a).strip()]
    if not targets:
        return []

    out: list[tuple[int, float, float]] = []  # (id, score, tiebreak_prior)
    for media in candidates:
        stored_names = _all_artist_aliases(media)
        if not stored_names:
            continue
        best = 0.0
        for target in targets:
            for name in stored_names:
                if not name:
                    continue
                if name == target:
                    best = max(best, 1.0)
                    break
                if target in name or name in target:
                    best = max(best, 0.8)
            if best >= 1.0:
                break
        if best <= 0:
            continue
        tiebreak = float(media.rating or 0) + (1.0 if media.favorite else 0.0)
        out.append((media.id, best, tiebreak))

    out.sort(key=lambda x: (x[1], x[2], x[0]), reverse=True)
    return [(mid, s) for mid, s, _ in out]


def _all_artist_aliases(media: models.Media) -> list[str]:
    """Every artist/circle string we have for a manga, lowercased.

    Combines media.artist (parsed by the scanner / backfill), and the
    metadata-profile parsed_artist + parsed_circle (parsed by the title
    bracket analyzer). Duplicates are fine — by_author scoring already
    deduplicates per-candidate.
    """
    out: list[str] = []
    if media.artist:
        out.append(media.artist.strip().lower())
    meta = media.metadata_profile
    if meta:
        if meta.parsed_artist:
            out.append(meta.parsed_artist.strip().lower())
        if meta.parsed_circle:
            out.append(meta.parsed_circle.strip().lower())
    return [s for s in out if s]


# --- by_style ---------------------------------------------------------------

# These constants mirror what recommendations.py was using inline. Keeping
# them here keeps the retrievers self-contained.
VECTOR_POOL = 60
VECTOR_MIN_SIMILARITY = 0.30
VECTOR_GAP_OVER_P90 = 0.10
VECTOR_MIN_MAX_OVER_P90 = 0.12


def by_style(
    candidates: list[models.Media],
    query_terms: list[str],
    avoid_tokens: list[str],
) -> tuple[list[tuple[int, float]], list[tuple[int, float]], dict[int, list[str]]]:
    """BM25 + dense vector hybrid for theme/tone queries.

    Returns three things in one tuple because the caller (recommend_manga)
    wants all of them and recomputing inside it would be wasteful:
      bm25_ranks      [(media_id, text_score)] sorted desc, text_score > 0
      vec_ranks       [(media_id, similarity)] sorted desc, threshold-passed
      matched_tags    {media_id: [tokens that hit fields]}

    The retriever does NOT do the RRF fusion — that's the caller's job, so
    it can mix in the prior score (rating / favorite / view_status) at the
    same time.
    """
    if not query_terms:
        return [], [], {}

    # Tokenise once, dedupe.
    tokens: list[str] = []
    for term in query_terms:
        tokens.extend(manga_search.tokenize(term))
    seen: set[str] = set()
    positive_tokens: list[str] = []
    for t in tokens:
        if t and t not in seen:
            seen.add(t)
            positive_tokens.append(t)

    if not positive_tokens:
        return [], [], {}

    # Build field tokens + IDF over the candidate set.
    field_tokens_by_id = {m.id: manga_search.build_field_tokens(m) for m in candidates}
    idf = manga_search.compute_idf(field_tokens_by_id)

    # BM25 pass (with avoid filter)
    bm25_scored: list[tuple[int, float]] = []
    matched_tags: dict[int, list[str]] = {}
    for media in candidates:
        fields = field_tokens_by_id[media.id]
        if avoid_tokens and manga_search.avoid_hit(fields, avoid_tokens):
            continue
        text_score, matched = manga_search.score_text_match(fields, positive_tokens, idf)
        if text_score > 0:
            bm25_scored.append((media.id, text_score))
            matched_tags[media.id] = matched
    bm25_scored.sort(key=lambda x: x[1], reverse=True)

    # Vector pass — gated on BM25 finding *something*. Rationale: dense
    # cosine over MiniLM-multilingual returns above-noise-floor hits for
    # essentially every query (the "doujin baseline"), so without a BM25
    # anchor we end up rescuing irrelevant manga whenever the user asks
    # for something the library doesn't actually contain (the mignon
    # case). Requiring at least one BM25 hit grounds the relevance signal
    # and preserves the "honest empty result" guarantee.
    if not bm25_scored:
        return [], [], {}

    surviving_ids = {m.id for m in candidates}
    if avoid_tokens:
        avoided_ids = {
            m.id for m in candidates
            if manga_search.avoid_hit(field_tokens_by_id[m.id], avoid_tokens)
        }
        surviving_ids -= avoided_ids
    vec_ranks = _vector_pass(candidates, positive_tokens, surviving_ids)

    return bm25_scored, vec_ranks, matched_tags


def _vector_pass(
    candidates: list[models.Media],
    positive_tokens: list[str],
    surviving_ids: set[int],
) -> list[tuple[int, float]]:
    """Dense embedding retrieval with adaptive threshold.

    Returns [(media_id, similarity)] of items above the noise floor, in
    descending similarity. Returns [] when the model can't load or the
    distribution suggests no real signal.
    """
    try:
        import numpy as np
        query_text = " ".join(positive_tokens)[:512]
        query_vec = manga_vector.encode_query(query_text)

        profiles = [m.ai_profile for m in candidates
                    if m.ai_profile and m.id in surviving_ids]
        pairs = manga_vector.load_candidate_vectors(profiles)
        if not pairs:
            return []

        all_ranked = manga_vector.rank_by_query(query_vec, pairs, top_k=len(pairs))
        if not all_ranked:
            return []

        sims = np.array([sim for _, sim in all_ranked])
        if len(sims) >= 10:
            top_sim = float(sims.max())
            p90 = float(np.percentile(sims, 90))
            if top_sim - p90 < VECTOR_MIN_MAX_OVER_P90:
                log.debug("by_style vector: weak signal (gap=%.3f < %.3f), skipping",
                          top_sim - p90, VECTOR_MIN_MAX_OVER_P90)
                return []
            threshold = max(VECTOR_MIN_SIMILARITY, p90 + VECTOR_GAP_OVER_P90)
        else:
            threshold = VECTOR_MIN_SIMILARITY

        return [(mid, sim) for mid, sim in all_ranked[:VECTOR_POOL] if sim >= threshold]
    except Exception as exc:  # noqa: BLE001 — best-effort
        log.warning("by_style vector retrieval failed: %s", exc)
        return []


# --- similar_to -------------------------------------------------------------

def similar_to(
    db: Session,
    referenced_title: str,
    candidates: list[models.Media],
    surviving_ids: Optional[set[int]] = None,
) -> tuple[Optional[models.Media], list[tuple[int, float]]]:
    """Find a referenced manga, then cosine-rank the rest by its embedding.

    Returns (referenced_media, ranked_neighbours). `referenced_media` is
    None when we can't identify what the user is talking about — caller
    should report that to the user instead of guessing.

    Neighbours exclude the referenced manga itself and pass the same
    adaptive cosine threshold as by_style. `surviving_ids`, when given,
    restricts the result to those (e.g. after avoid filtering).
    """
    if not referenced_title or not referenced_title.strip():
        return None, []

    # Resolve the referenced manga via fuzzy SQL LIKE on title/parsed_title.
    needle = f"%{referenced_title.strip()}%"
    referenced = (
        db.query(models.Media)
        .outerjoin(models.MangaMetadataProfile)
        .filter(
            models.Media.media_type == "manga",
            models.Media.is_missing == False,  # noqa: E712
            or_(
                func.lower(models.Media.title).like(f"%{referenced_title.strip().lower()}%"),
                func.lower(models.MangaMetadataProfile.parsed_title).like(
                    f"%{referenced_title.strip().lower()}%"
                ),
            ),
        )
        .first()
    )
    if not referenced:
        log.debug("similar_to: no manga matches title hint %r", referenced_title)
        return None, []
    if not referenced.ai_profile or not referenced.ai_profile.embedding:
        log.debug("similar_to: media %s has no embedding yet", referenced.id)
        return referenced, []

    target_vec = manga_vector.deserialize_vec(referenced.ai_profile.embedding)
    if target_vec is None:
        return referenced, []

    # Neighbour pool: all other surviving candidates with embeddings.
    profiles = [
        m.ai_profile for m in candidates
        if m.ai_profile and m.id != referenced.id
        and (surviving_ids is None or m.id in surviving_ids)
    ]
    pairs = manga_vector.load_candidate_vectors(profiles)
    if not pairs:
        return referenced, []

    ranked = manga_vector.rank_by_query(target_vec, pairs, top_k=len(pairs))
    if not ranked:
        return referenced, []

    # similar_to has a clearer signal definition (same manga = 1.0) so we
    # use a flat threshold here, not the p90 trick. Anything < 0.40 is
    # genuinely "different".
    return referenced, [(mid, sim) for mid, sim in ranked if sim >= 0.40][:VECTOR_POOL]


# --- browse -----------------------------------------------------------------

def browse(
    candidates: list[models.Media],
    avoid_tokens: list[str],
) -> list[tuple[int, float]]:
    """No-query default: favorites + rating + freshness ranking.

    Score is a weighted blend of:
      favorite        +50 if true
      rating          *10 per star (0..50)
      not viewed      +10 (unviewed > viewing > viewed)
      not viewing     +3
      tiebreak by id desc (newer first) inside the score key

    Avoid tokens still apply — the user can say "随便推荐点不要黑暗的" and
    have it filter correctly.
    """
    # Avoid filter: build minimal field tokens just for the avoid check.
    if avoid_tokens:
        field_tokens_by_id = {m.id: manga_search.build_field_tokens(m) for m in candidates}
        candidates = [
            m for m in candidates
            if not manga_search.avoid_hit(field_tokens_by_id[m.id], avoid_tokens)
        ]

    scored: list[tuple[int, float]] = []
    for media in candidates:
        score = 0.0
        if media.favorite:
            score += 50.0
        score += (media.rating or 0) * 10.0
        if media.view_status == "unviewed":
            score += 10.0
        elif media.view_status == "viewing":
            score += 3.0
        # freshness tiebreak: tiny bump for higher id
        score += min((media.id or 0) / 1_000_000.0, 0.001)
        scored.append((media.id, score))

    scored.sort(key=lambda x: x[1], reverse=True)
    return scored
