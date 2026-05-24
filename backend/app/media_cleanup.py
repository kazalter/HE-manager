from __future__ import annotations

from collections.abc import Iterable

from sqlalchemy import or_
from sqlalchemy.orm import Session

from . import models


def detach_media_references(db: Session, media_ids: Iterable[int]) -> list[int]:
    """Remove cross-table references that would block deleting Media rows."""
    ids = sorted({int(media_id) for media_id in media_ids if media_id is not None})
    if not ids:
        return []

    db.query(models.XMediaItem).filter(
        models.XMediaItem.library_media_id.in_(ids)
    ).update({models.XMediaItem.library_media_id: None}, synchronize_session=False)
    db.query(models.DuplicateCandidate).filter(
        or_(
            models.DuplicateCandidate.existing_media_id.in_(ids),
            models.DuplicateCandidate.candidate_media_id.in_(ids),
        )
    ).delete(synchronize_session=False)

    return ids
