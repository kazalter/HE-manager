"""Single source of truth for tag get-or-create + attach.

Used by the manual tag endpoint (main.py) and the X / WNACG auto-tagging
hooks. Kept dependency-light (only models + Session) so import-heavy modules
like main.py and the x_import package can both use it without a cycle.
"""
from __future__ import annotations

from typing import Optional

from sqlalchemy.orm import Session

from . import models


def get_or_create_tag(
    db: Session, name: str, namespace: str = "general"
) -> Optional[models.Tag]:
    """Find or create a tag scoped by (name, namespace).

    Returns None for a blank name so auto-tag callers don't need to
    pre-check. Caller owns the commit; this only flushes so a new row gets
    an id."""
    name = (name or "").strip()
    if not name:
        return None
    namespace = (namespace or "general").strip() or "general"
    tag = (
        db.query(models.Tag)
        .filter(models.Tag.name == name, models.Tag.namespace == namespace)
        .first()
    )
    if not tag:
        tag = models.Tag(name=name, namespace=namespace)
        db.add(tag)
        db.flush()
    return tag


def attach_tag(
    db: Session, media: models.Media, name: str, namespace: str = "general"
) -> Optional[models.Tag]:
    """Idempotently attach a (name, namespace) tag to `media`.

    Best-effort: a blank name is a no-op so import hooks can call it
    unconditionally. Caller owns the commit."""
    tag = get_or_create_tag(db, name, namespace)
    if tag is None:
        return None
    if tag not in media.tags:
        media.tags.append(tag)
    return tag
