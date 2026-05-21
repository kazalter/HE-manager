"""Creator aggregation: X (Twitter) authors + manga artists, unified.

Two independent sources of "who made this", surfaced through one list:

* **X authors** — X-imported media carries its author: every downloaded file
  is an `XMediaItem` → `Media`, and its `XPost` knows
  `author_screen_name` / `author_name`. X library rows are `media_type`
  'image' or 'video'.
* **Manga artists** — manga has no author metadata, so `Media.artist` is
  parsed from the doujinshi-style title (see `manga_artist.py`) and persisted.

Every creator carries a unified `key` (`x:<screen_name>` or `a:<artist>`) and
a `kind` so the API/UI can treat both the same, plus a `media_type` filter:
'manga' → artists, 'image'/'video' → X authors of that type, none → both.

We mirror the library's visibility rule (hidden duplicate statuses excluded)
so counts match what the user actually sees.
"""
from __future__ import annotations

from typing import Optional

from sqlalchemy import func
from sqlalchemy.orm import Session

from . import models

HIDDEN_DUP_STATUSES = ("checking", "strong_duplicate", "suspected_duplicate")


# ---------------------------------------------------------------------------
# X authors
# ---------------------------------------------------------------------------

def _visible_media_q(db: Session, media_type: Optional[str] = None):
    """X media joined to its author, restricted to library-visible rows."""
    q = (
        db.query(models.Media)
        .join(models.XMediaItem, models.XMediaItem.library_media_id == models.Media.id)
        .join(models.XPost, models.XPost.id == models.XMediaItem.post_id)
        .filter(models.XPost.author_screen_name.isnot(None))
        .filter(models.Media.duplicate_status.notin_(HIDDEN_DUP_STATUSES))
    )
    if media_type:
        q = q.filter(models.Media.media_type == media_type)
    return q


def _display_names(db: Session) -> dict:
    """Latest non-null display name per screen_name (handles handle renames)."""
    rows = (
        db.query(models.XPost.author_screen_name, models.XPost.author_name)
        .filter(models.XPost.author_screen_name.isnot(None))
        .filter(models.XPost.author_name.isnot(None))
        .order_by(models.XPost.id.desc())
        .all()
    )
    out: dict = {}
    for sn, name in rows:
        if sn not in out and name:
            out[sn] = name
    return out


def _covers(db: Session, media_type: Optional[str] = None) -> dict:
    """Most-recent library cover per screen_name, for the creator card art."""
    q = (
        db.query(models.XPost.author_screen_name, models.Media.cover_path)
        .join(models.XMediaItem, models.XMediaItem.post_id == models.XPost.id)
        .join(models.Media, models.Media.id == models.XMediaItem.library_media_id)
        .filter(models.XPost.author_screen_name.isnot(None))
        .filter(models.Media.cover_path.isnot(None))
        .filter(models.Media.duplicate_status.notin_(HIDDEN_DUP_STATUSES))
    )
    if media_type:
        q = q.filter(models.Media.media_type == media_type)
    rows = q.order_by(models.Media.id.desc()).all()
    out: dict = {}
    for sn, cover in rows:
        if sn not in out and cover:
            out[sn] = cover
    return out


def _media_counts(db: Session, media_type: Optional[str] = None) -> dict:
    rows = (
        _visible_media_q(db, media_type)
        .with_entities(
            models.XPost.author_screen_name,
            func.count(func.distinct(models.Media.id)),
        )
        .group_by(models.XPost.author_screen_name)
        .all()
    )
    return {sn: n for sn, n in rows}


def _posts_known(db: Session) -> dict:
    rows = (
        db.query(models.XPost.author_screen_name, func.count(models.XPost.id))
        .filter(models.XPost.author_screen_name.isnot(None))
        .group_by(models.XPost.author_screen_name)
        .all()
    )
    return {sn: n for sn, n in rows}


def _posts_in_library(db: Session) -> dict:
    rows = (
        db.query(
            models.XPost.author_screen_name,
            func.count(func.distinct(models.XPost.id)),
        )
        .join(models.XMediaItem, models.XMediaItem.post_id == models.XPost.id)
        .filter(models.XPost.author_screen_name.isnot(None))
        .filter(models.XMediaItem.library_media_id.isnot(None))
        .group_by(models.XPost.author_screen_name)
        .all()
    )
    return {sn: n for sn, n in rows}


def _build_x(sn: str, counts, names, covers, known, in_lib) -> dict:
    pending = max(0, known.get(sn, 0) - in_lib.get(sn, 0))
    return {
        "kind": "x",
        "key": f"x:{sn}",
        "screen_name": sn,
        "display_name": names.get(sn),
        "media_count": counts.get(sn, 0),
        "posts_known": known.get(sn, 0),
        "posts_pending": pending,
        "cover_path": covers.get(sn),
    }


def _x_creators(db: Session, media_type: Optional[str] = None) -> list:
    counts = _media_counts(db, media_type)
    if not counts:
        return []
    names = _display_names(db)
    covers = _covers(db, media_type)
    known = _posts_known(db)
    in_lib = _posts_in_library(db)
    return [_build_x(sn, counts, names, covers, known, in_lib) for sn in counts]


# ---------------------------------------------------------------------------
# Manga artists
# ---------------------------------------------------------------------------

def _artist_media_q(db: Session):
    """Visible manga rows that have a parsed artist."""
    return (
        db.query(models.Media)
        .filter(models.Media.media_type == "manga")
        .filter(models.Media.artist.isnot(None))
        .filter(models.Media.duplicate_status.notin_(HIDDEN_DUP_STATUSES))
    )


def _artist_covers(db: Session) -> dict:
    rows = (
        _artist_media_q(db)
        .filter(models.Media.cover_path.isnot(None))
        .with_entities(models.Media.artist, models.Media.cover_path)
        .order_by(models.Media.id.desc())
        .all()
    )
    out: dict = {}
    for artist, cover in rows:
        if artist not in out and cover:
            out[artist] = cover
    return out


def _artist_creators(db: Session) -> list:
    counts = dict(
        _artist_media_q(db)
        .with_entities(models.Media.artist, func.count(func.distinct(models.Media.id)))
        .group_by(models.Media.artist)
        .all()
    )
    if not counts:
        return []
    covers = _artist_covers(db)
    return [
        {
            "kind": "artist",
            "key": f"a:{artist}",
            "screen_name": None,
            "display_name": artist,
            "media_count": n,
            "posts_known": 0,
            "posts_pending": 0,
            "cover_path": covers.get(artist),
        }
        for artist, n in counts.items()
    ]


# ---------------------------------------------------------------------------
# Unified API
# ---------------------------------------------------------------------------

def list_creators(
    db: Session,
    search: Optional[str] = None,
    sort: str = "count",
    media_type: Optional[str] = None,
) -> list:
    mt = (media_type or "").strip().lower()
    if mt == "manga":
        creators = _artist_creators(db)
    elif mt in ("image", "video"):
        creators = _x_creators(db, mt)
    else:  # "all" / "" / None -> everything
        creators = _x_creators(db) + _artist_creators(db)

    if search:
        q = search.strip().lower().lstrip("@")
        creators = [
            c
            for c in creators
            if q in (c["screen_name"] or "").lower()
            or q in (c["display_name"] or "").lower()
        ]

    if sort == "name":
        creators.sort(key=lambda c: (c["display_name"] or c["screen_name"] or "").lower())
    elif sort == "pending":
        creators.sort(key=lambda c: (-c["posts_pending"], -c["media_count"]))
    else:  # "count"
        creators.sort(key=lambda c: (-c["media_count"], (c["display_name"] or "").lower()))

    return creators


def creator_detail(db: Session, key: str) -> Optional[dict]:
    """Resolve a unified creator key (`x:<sn>` or `a:<artist>`) to its works."""
    if key.startswith("a:"):
        artist = key[2:]
        media = (
            _artist_media_q(db)
            .filter(models.Media.artist == artist)
            .order_by(models.Media.id.desc())
            .all()
        )
        if not media:
            return None
        covers = _artist_covers(db)
        creator = {
            "kind": "artist",
            "key": key,
            "screen_name": None,
            "display_name": artist,
            "media_count": len({m.id for m in media}),
            "posts_known": 0,
            "posts_pending": 0,
            "cover_path": covers.get(artist),
        }
        return {"creator": creator, "media": media}

    # X author. Accept both "x:<sn>" and a bare screen_name for back-compat.
    sn = key[2:] if key.startswith("x:") else key
    media = (
        _visible_media_q(db)
        .filter(models.XPost.author_screen_name == sn)
        .distinct()
        .order_by(models.Media.id.desc())
        .all()
    )
    if not media:
        return None
    counts = {sn: len({m.id for m in media})}
    names = _display_names(db)
    covers = {sn: next((m.cover_path for m in media if m.cover_path), None)}
    known = _posts_known(db)
    in_lib = _posts_in_library(db)
    return {
        "creator": _build_x(sn, counts, names, covers, known, in_lib),
        "media": media,
    }
