"""Merge actions for duplicate pairs.

Never deletes files implicitly. The "merge" semantically means "consolidate the candidate
into the existing entry" — afterwards the existing entry carries the union of useful
metadata, and the candidate is removed from the library (DB row only). The actual file
remains on disk.

For "replace_path", the existing entry adopts the new (candidate) path and the candidate
row is dropped. Useful when the existing entry is missing on disk.
"""
from __future__ import annotations

import os
from datetime import datetime
from typing import Optional

from sqlalchemy.orm import Session

from .. import models


# Action constants — passed in from API.
ACTION_KEEP_EXISTING = "keep_existing"   # candidate row removed; existing wins
ACTION_REPLACE_PATH = "replace_path"     # existing adopts candidate's path, candidate row removed
ACTION_KEEP_BOTH = "keep_both"           # both stay; candidate becomes 'unique'
ACTION_IGNORE = "ignore"                 # do nothing; pair archived as 'ignored'


def _merge_metadata(existing: models.Media, candidate: models.Media) -> None:
    if not existing.cover_path and candidate.cover_path:
        existing.cover_path = candidate.cover_path
    if not existing.duration and candidate.duration:
        existing.duration = candidate.duration
    if not existing.width and candidate.width:
        existing.width = candidate.width
    if not existing.height and candidate.height:
        existing.height = candidate.height
    if not existing.page_count and candidate.page_count:
        existing.page_count = candidate.page_count
    if not existing.file_size and candidate.file_size:
        existing.file_size = candidate.file_size
    if not existing.source_url and candidate.source_url:
        existing.source_url = candidate.source_url
    if not existing.source_site and candidate.source_site:
        existing.source_site = candidate.source_site
    # Carry over a richer title if the existing one looks generic (filename-y).
    if candidate.title and (not existing.title or len(candidate.title) > len(existing.title)):
        existing.title = candidate.title
    # Tag union.
    existing_tag_ids = {tag.id for tag in existing.tags}
    for tag in candidate.tags:
        if tag.id not in existing_tag_ids:
            existing.tags.append(tag)
    if candidate.favorite and not existing.favorite:
        existing.favorite = True
    if candidate.rating and (not existing.rating or candidate.rating > existing.rating):
        existing.rating = candidate.rating


def _drop_media_and_fingerprint(
    db: Session,
    media: models.Media,
    replacement: Optional[models.Media] = None,
) -> None:
    # X imports keep a back-reference from each downloaded file to its library row.
    # When duplicate resolution removes a Media row, preserve that back-reference by
    # moving it to the row that survives the merge.
    db.query(models.XMediaItem).filter(
        models.XMediaItem.library_media_id == media.id
    ).update(
        {models.XMediaItem.library_media_id: replacement.id if replacement else None},
        synchronize_session=False,
    )
    fp = (
        db.query(models.MediaFingerprint)
        .filter(models.MediaFingerprint.media_id == media.id)
        .first()
    )
    if fp:
        db.delete(fp)
    # Detach any other DuplicateCandidate rows pointing at this id, mark as resolved.
    other_pairs = (
        db.query(models.DuplicateCandidate)
        .filter(
            (models.DuplicateCandidate.existing_media_id == media.id)
            | (models.DuplicateCandidate.candidate_media_id == media.id)
        )
        .all()
    )
    for other in other_pairs:
        if other.status == "pending":
            other.status = "merged"
            other.resolved_at = datetime.utcnow()
            other.resolution_note = "对端已被合并删除"
        if other.existing_media_id == media.id:
            other.existing_media_id = replacement.id if replacement else None
        if other.candidate_media_id == media.id:
            other.candidate_media_id = replacement.id if replacement else None
    db.delete(media)


def apply_action(
    db: Session,
    pair: models.DuplicateCandidate,
    action: str,
    note: Optional[str] = None,
) -> models.DuplicateCandidate:
    existing = db.query(models.Media).filter(models.Media.id == pair.existing_media_id).first()
    candidate = db.query(models.Media).filter(models.Media.id == pair.candidate_media_id).first()
    if not existing or not candidate:
        pair.status = "ignored"
        pair.resolved_at = datetime.utcnow()
        pair.resolution_note = "对应媒体已不存在"
        db.commit()
        return pair

    now = datetime.utcnow()

    if action == ACTION_IGNORE:
        candidate.duplicate_status = "weak_suspected"
        pair.status = "ignored"
        pair.resolved_at = now
        pair.resolution_note = note
        db.commit()
        return pair

    if action == ACTION_KEEP_BOTH:
        candidate.duplicate_status = "unique"
        pair.status = "kept_both"
        pair.resolved_at = now
        pair.resolution_note = note
        db.commit()
        return pair

    if action == ACTION_REPLACE_PATH:
        old_path = existing.absolute_path
        existing.absolute_path = candidate.absolute_path
        existing.relative_path = candidate.relative_path
        existing.folder_id = candidate.folder_id
        existing.is_missing = False
        _merge_metadata(existing, candidate)
        _drop_media_and_fingerprint(db, candidate, replacement=existing)
        pair.status = "replaced"
        pair.resolved_at = now
        pair.resolution_note = note or f"路径替换：{old_path} → {existing.absolute_path}"
        db.commit()
        return pair

    if action == ACTION_KEEP_EXISTING:
        _merge_metadata(existing, candidate)
        _drop_media_and_fingerprint(db, candidate, replacement=existing)
        pair.status = "merged"
        pair.resolved_at = now
        pair.resolution_note = note
        db.commit()
        return pair

    raise ValueError(f"unsupported merge action: {action}")
