"""Background fingerprint + classification worker.

Single thread + a queue: scanner enqueues media ids that need fingerprinting; the worker
computes the fingerprint, finds candidate pairs, classifies, persists. UI is never
blocked — scans return as soon as basic insertion finishes.
"""
from __future__ import annotations

import queue
import threading
import traceback
from datetime import datetime
from typing import Iterable, List, Optional

from sqlalchemy.orm import Session

from .. import database, models
from . import classify, fingerprint, normalize


_QUEUE: "queue.Queue[int]" = queue.Queue()
_WORKER_THREAD: Optional[threading.Thread] = None
_WORKER_LOCK = threading.Lock()


# ----- public API -----

def enqueue(media_ids: Iterable[int]) -> int:
    count = 0
    for media_id in media_ids:
        if media_id is None:
            continue
        _QUEUE.put(int(media_id))
        count += 1
    if count:
        _ensure_worker()
    return count


def queue_size() -> int:
    return _QUEUE.qsize()


def is_running() -> bool:
    return bool(_WORKER_THREAD and _WORKER_THREAD.is_alive())


# ----- internals -----

def _ensure_worker() -> None:
    global _WORKER_THREAD
    with _WORKER_LOCK:
        if _WORKER_THREAD and _WORKER_THREAD.is_alive():
            return
        thread = threading.Thread(target=_run, name="dedup-worker", daemon=True)
        thread.start()
        _WORKER_THREAD = thread


def _run() -> None:
    while True:
        try:
            media_id = _QUEUE.get(timeout=30)
        except queue.Empty:
            return
        try:
            _process_one(media_id)
        except Exception:
            print("[dedup] worker error:")
            print(traceback.format_exc())
        finally:
            _QUEUE.task_done()


def _process_one(media_id: int) -> None:
    db = database.SessionLocal()
    try:
        media = db.query(models.Media).filter(models.Media.id == media_id).first()
        if not media:
            return

        # Compute (or reuse cached) fingerprint for the new entry.
        new_fp = _ensure_fingerprint(db, media)
        if new_fp is None:
            # Couldn't compute (file missing / unreadable). Leave entry as 'unique' so it
            # still shows up; it'll be revisited on the next scan.
            if media.duplicate_status == "checking":
                media.duplicate_status = "unique"
                db.commit()
            return

        candidates = _candidates_for(db, media)
        if not candidates:
            media.duplicate_status = "unique"
            db.commit()
            return

        new_lite = _lite_from_record(media, new_fp)
        best_level = classify.LEVEL_UNIQUE
        best_similarity = 0
        any_pair = False

        level_rank = {
            classify.LEVEL_UNIQUE: 0,
            classify.LEVEL_WEAK: 1,
            classify.LEVEL_SUSPECTED: 2,
            classify.LEVEL_STRONG: 3,
        }

        for existing in candidates:
            existing_fp = _ensure_fingerprint(db, existing)
            if existing_fp is None:
                continue
            existing_lite = _lite_from_record(existing, existing_fp)
            level, similarity, reasons = classify.classify(existing_lite, new_lite)
            if level == classify.LEVEL_UNIQUE:
                continue
            any_pair = True
            _upsert_candidate(db, existing.id, media.id, level, similarity, reasons)
            if level_rank[level] > level_rank[best_level]:
                best_level = level
                best_similarity = similarity
            elif level_rank[level] == level_rank[best_level] and similarity > best_similarity:
                best_similarity = similarity

        if any_pair:
            media.duplicate_status = best_level
        else:
            media.duplicate_status = "unique"
        db.commit()
    finally:
        db.close()


def _candidates_for(db: Session, media: models.Media) -> List[models.Media]:
    norm = (media.normalized_title or normalize.normalize_title(media.title or "")).strip()
    if not norm:
        return []
    return (
        db.query(models.Media)
        .filter(models.Media.id != media.id)
        .filter(models.Media.media_type == media.media_type)
        .filter(models.Media.is_missing == False)  # noqa: E712
        .filter(models.Media.duplicate_status.notin_(["strong_duplicate", "checking"]))
        .filter(models.Media.normalized_title == norm)
        .all()
    )


def _ensure_fingerprint(db: Session, media: models.Media) -> Optional[models.MediaFingerprint]:
    record = (
        db.query(models.MediaFingerprint)
        .filter(models.MediaFingerprint.media_id == media.id)
        .first()
    )
    if record and fingerprint.fingerprint_cache_is_fresh(record, media):
        return record

    result = fingerprint.fingerprint_for_media(media)
    if result is None:
        return None

    if not record:
        record = models.MediaFingerprint(media_id=media.id)
        db.add(record)
    record.media_type = result.media_type
    record.file_size = result.file_size
    record.page_count = result.page_count
    record.duration = result.duration
    record.width = result.width
    record.height = result.height
    record.hash_first = result.hash_first
    record.hash_middle = result.hash_middle
    record.hash_last = result.hash_last
    record.source_path = result.source_path
    record.source_mtime = result.source_mtime
    record.computed_at = datetime.utcnow()
    db.commit()
    db.refresh(record)
    return record


def _lite_from_record(
    media: models.Media,
    record: models.MediaFingerprint,
) -> classify.FingerprintLite:
    return classify.FingerprintLite(
        media_type=record.media_type or media.media_type,
        file_size=record.file_size or media.file_size,
        page_count=record.page_count or media.page_count,
        duration=record.duration or media.duration,
        width=record.width or media.width,
        height=record.height or media.height,
        hash_first=record.hash_first,
        hash_middle=record.hash_middle,
        hash_last=record.hash_last,
    )


def _upsert_candidate(
    db: Session,
    existing_media_id: int,
    candidate_media_id: int,
    level: str,
    similarity: int,
    reasons: List[str],
) -> models.DuplicateCandidate:
    existing_pair = (
        db.query(models.DuplicateCandidate)
        .filter(
            models.DuplicateCandidate.existing_media_id == existing_media_id,
            models.DuplicateCandidate.candidate_media_id == candidate_media_id,
        )
        .first()
    )
    reason = "；".join(reasons) if reasons else None
    if existing_pair:
        if existing_pair.status not in {"merged", "kept_both", "ignored", "replaced"}:
            existing_pair.level = level
            existing_pair.similarity = similarity
            existing_pair.reason = reason
            existing_pair.status = "pending"
        return existing_pair
    pair = models.DuplicateCandidate(
        existing_media_id=existing_media_id,
        candidate_media_id=candidate_media_id,
        level=level,
        similarity=similarity,
        reason=reason,
        status="pending",
    )
    db.add(pair)
    db.flush()
    return pair
