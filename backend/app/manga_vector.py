"""Dense-vector retrieval for manga recommendations (Phase 2 / RAG core).

What this provides
==================
- A lazily-loaded sentence-transformer model (multilingual MiniLM, 384-dim).
  Loaded once on first use, cached in module state. ~117MB on disk + ~200MB
  RAM. We don't preload on import so test runs and CLI scripts that don't
  need the model start fast.
- `encode_text(text)` / `encode_query(query)`: returns L2-normalised float32
  vectors. Normalised so cosine similarity == dot product.
- `compose_doc_text(media)`: turns a manga record into the canonical string we
  embed — title + parsed_title + artist + circle + parody + tags + ai
  summary/style/story/tone tags. OCR is *not* included; like the BM25 layer
  we keep OCR out of the matching surface.
- `serialize_vec` / `deserialize_vec`: bytes ↔ ndarray helpers. The stored
  format is raw float32 little-endian (np.tobytes()), so `len(blob) ==
  dim * 4`. No JSON overhead, no version prefix — embedding_model column
  on the row tells the consumer which model produced the blob.
- `rank_by_query(query, candidates_with_vecs, top_k)`: simple in-memory
  cosine search. The library size we target (~hundreds to low thousands of
  manga) doesn't need faiss or sqlite-vec; numpy matmul handles 10k vectors
  in single-digit ms.

Why not faiss / chroma / pgvector
=================================
Personal library, <2k items. The whole vector table for the user's current
library is 269 × 384 × 4 B = ~400KB. Loading into a numpy ndarray and doing
one matmul-per-query is faster than any external index for this scale, and
the deployment surface is one pip install instead of a C++ build.
"""
from __future__ import annotations

import logging
import threading
from typing import Iterable, Optional

import numpy as np

from . import models

log = logging.getLogger(__name__)

# A multilingual model that handles the JP/ZH/EN mix typical of doujin
# titles. 384 dims, ~117MB on disk. If you ever swap this:
#   1) bump the constant
#   2) every row's `embedding_model` will then disagree → it's effectively a
#      cache invalidation; backfill_embeddings.py re-encodes everything.
MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2"
VECTOR_DIM = 384

_model = None
_model_lock = threading.Lock()


def get_model():
    """Lazy singleton. Loads model on first call (10-20s warm, longer cold)."""
    global _model
    if _model is not None:
        return _model
    with _model_lock:
        if _model is not None:
            return _model
        # Imported inside the function so module import is cheap and tests
        # that don't touch vectors don't pay the dependency cost.
        from sentence_transformers import SentenceTransformer  # type: ignore

        log.info("Loading embedding model: %s", MODEL_NAME)
        _model = SentenceTransformer(MODEL_NAME)
    return _model


# --- text composition ------------------------------------------------------

def _json_list(value: Optional[str]) -> list[str]:
    """Permissive JSON-array parse — matches manga_search's helper."""
    import json

    if not value:
        return []
    try:
        parsed = json.loads(value)
    except (TypeError, ValueError):
        return []
    if not isinstance(parsed, list):
        return []
    return [str(item) for item in parsed if str(item).strip()]


def compose_doc_text(media: models.Media) -> str:
    """Build the canonical text we embed for a single manga.

    Order matters less than content — the encoder is a transformer, not
    bag-of-words — but we deliberately put the most discriminating fields
    (title, artist, parody, tags) first so very long ai summaries don't
    swamp them when the input gets truncated to model max_seq_length (128
    for MiniLM).
    """
    parts: list[str] = []

    if media.title:
        parts.append(media.title)

    metadata = media.metadata_profile
    if metadata:
        if metadata.parsed_title and metadata.parsed_title != media.title:
            parts.append(metadata.parsed_title)
        if metadata.parsed_artist:
            parts.append(f"作者：{metadata.parsed_artist}")
        if metadata.parsed_circle:
            parts.append(f"社团：{metadata.parsed_circle}")
        if metadata.parody:
            parts.append(f"原作：{metadata.parody}")
        if metadata.language:
            parts.append(f"语言：{metadata.language}")
        external_tags = _json_list(metadata.external_tags)
        if external_tags:
            parts.append("标签：" + "、".join(external_tags[:20]))
    elif media.artist:
        # Fall back to media.artist when metadata profile hasn't been built
        parts.append(f"作者：{media.artist}")

    tag_names = [t.name for t in media.tags]
    if tag_names:
        parts.append("标签：" + "、".join(tag_names[:20]))

    profile = media.ai_profile
    if profile:
        if profile.content_summary:
            parts.append(profile.content_summary[:300])
        style = _json_list(profile.style_tags)
        if style:
            parts.append("画风：" + "、".join(style[:8]))
        story = _json_list(profile.story_tags)
        if story:
            parts.append("题材：" + "、".join(story[:8]))
        tone = _json_list(profile.tone_tags)
        if tone:
            parts.append("氛围：" + "、".join(tone[:8]))

    return "  ".join(parts).strip()


# --- encoding / serialisation ---------------------------------------------

def encode_text(text: str) -> np.ndarray:
    """Encode one string to a normalised float32 vector of shape (VECTOR_DIM,)."""
    model = get_model()
    vec = model.encode(text or "", normalize_embeddings=True)
    return np.asarray(vec, dtype=np.float32).reshape(-1)


def encode_batch(texts: list[str]) -> np.ndarray:
    """Encode many strings; returns shape (N, VECTOR_DIM), normalised."""
    model = get_model()
    if not texts:
        return np.zeros((0, VECTOR_DIM), dtype=np.float32)
    vecs = model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
    return np.asarray(vecs, dtype=np.float32)


def encode_query(query: str) -> np.ndarray:
    """Alias of encode_text — kept separate in case we add query rewriting later."""
    return encode_text(query)


def serialize_vec(vec: np.ndarray) -> bytes:
    """ndarray -> bytes for storage in the embedding BLOB column."""
    return np.asarray(vec, dtype=np.float32).reshape(-1).tobytes()


def deserialize_vec(blob: bytes, dim: int = VECTOR_DIM) -> Optional[np.ndarray]:
    """Bytes -> ndarray. Returns None if the blob is wrong size for `dim`,
    which indicates the row was encoded by a different model."""
    if not blob:
        return None
    arr = np.frombuffer(blob, dtype=np.float32)
    if arr.shape[0] != dim:
        return None
    return arr


# --- retrieval -------------------------------------------------------------

def rank_by_query(
    query_vec: np.ndarray,
    candidates: Iterable[tuple[int, np.ndarray]],
    top_k: int = 80,
) -> list[tuple[int, float]]:
    """Cosine top-K over (media_id, vec) candidates.

    Returns [(media_id, similarity)] sorted by similarity desc. `query_vec`
    and each candidate vec are expected to be already L2-normalised, so
    cosine == dot. We don't re-normalise here — that would hide bugs.
    """
    items = list(candidates)
    if not items:
        return []
    ids = np.array([mid for mid, _ in items], dtype=np.int64)
    matrix = np.stack([vec for _, vec in items], axis=0)
    sims = matrix @ query_vec.astype(np.float32).reshape(-1)
    order = np.argsort(-sims)[: max(1, top_k)]
    return [(int(ids[i]), float(sims[i])) for i in order]


def load_candidate_vectors(
    profiles: Iterable[models.MangaAIProfile],
    dim: int = VECTOR_DIM,
    model_name: str = MODEL_NAME,
) -> list[tuple[int, np.ndarray]]:
    """Pull (media_id, vec) pairs from MangaAIProfile rows that have an
    embedding produced by the *current* model. Rows with NULL embedding or
    encoded by a different model are silently skipped — the recommender
    falls back to BM25-only for those."""
    out: list[tuple[int, np.ndarray]] = []
    for profile in profiles:
        if not profile.embedding:
            continue
        if profile.embedding_model and profile.embedding_model != model_name:
            continue
        vec = deserialize_vec(profile.embedding, dim=dim)
        if vec is None:
            continue
        out.append((profile.media_id, vec))
    return out
