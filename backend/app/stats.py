"""Read-only aggregations for the personal dashboard.

Everything here is derived from columns that already exist on `Media` — no schema
changes, no extra tables. We mirror the library's visibility rule: rows whose
`duplicate_status` is checking / strong / suspected are hidden from the user, so
they must not skew the numbers either.

Timestamps (`last_opened_at`, `created_at`) are stored as naive UTC. This app is
self-hosted on the user's own machine, so we bucket by the date portion as-is —
good enough for a personal dashboard, and documented here so it isn't a mystery
later.

Performance notes:
- Month/day bucketing is done in SQLite via `strftime`, not in Python. With ~10k
  rows the difference is microseconds vs tens of ms, but it scales cleanly.
- Each endpoint is wrapped in a tiny TTL cache (30s). On a personal box the
  staleness is invisible; the cache is mostly there so the same dashboard load
  + the inevitable double-tap on Refresh doesn't re-hammer the DB.
"""
from __future__ import annotations

import threading
import time
from collections import Counter
from datetime import datetime, timedelta
from typing import Callable

from sqlalchemy import case, func, text
from sqlalchemy.orm import Session

from . import creators as creators_mod
from . import models

# duplicate_status values the library hides; stats follow the same rule.
HIDDEN_DUP_STATUSES = ("checking", "strong_duplicate", "suspected_duplicate")

# Per-endpoint TTL in seconds. Refresh-button users on a personal box don't
# need sub-second freshness — this just avoids re-running 4 aggregations
# on every component re-mount or back-button.
_CACHE_TTL = 30.0


class _TtlCache:
    """Trivial single-slot TTL cache, thread-safe. One per stat function."""

    __slots__ = ("_value", "_expires_at", "_lock")

    def __init__(self) -> None:
        self._value = None
        self._expires_at = 0.0
        self._lock = threading.Lock()

    def get_or_compute(self, key: object, compute: Callable[[], dict]) -> dict:
        now = time.monotonic()
        with self._lock:
            if self._value is not None and self._value[0] == key and now < self._expires_at:
                return self._value[1]
        # Compute outside the lock — we don't want a slow query to block readers
        # who would have taken the cached value anyway.
        fresh = compute()
        with self._lock:
            self._value = (key, fresh)
            self._expires_at = time.monotonic() + _CACHE_TTL
        return fresh

    def invalidate(self) -> None:
        with self._lock:
            self._value = None
            self._expires_at = 0.0


_overview_cache = _TtlCache()
_distribution_cache = _TtlCache()
_activity_cache = _TtlCache()
_attention_cache = _TtlCache()
_highlights_cache = _TtlCache()


def invalidate_all() -> None:
    """Drop all stats caches. Call from write paths if you ever want fresh-on-write."""
    _overview_cache.invalidate()
    _distribution_cache.invalidate()
    _activity_cache.invalidate()
    _attention_cache.invalidate()
    _highlights_cache.invalidate()


def _visible(db: Session):
    """Base query over the media the user actually sees in the library."""
    return db.query(models.Media).filter(
        models.Media.duplicate_status.notin_(HIDDEN_DUP_STATUSES)
    )


def _overview_compute(db: Session) -> dict:
    base = _visible(db)

    # Single grouped scan for count + size per type (vs. one scan per type).
    type_rows = (
        base.with_entities(
            models.Media.media_type,
            func.count(models.Media.id),
            func.coalesce(func.sum(models.Media.file_size), 0),
        )
        .group_by(models.Media.media_type)
        .all()
    )
    by_type = {t: int(c) for t, c, _ in type_rows}
    by_type_size = {t: int(s) for t, _, s in type_rows}

    by_status = dict(
        base.with_entities(models.Media.view_status, func.count(models.Media.id))
        .group_by(models.Media.view_status)
        .all()
    )

    # One pass for the rest of the headline numbers.
    agg = base.with_entities(
        func.count(models.Media.id),
        func.sum(case((models.Media.favorite.is_(True), 1), else_=0)),
        func.sum(case((models.Media.rating > 0, 1), else_=0)),
        func.sum(case((models.Media.is_missing.is_(True), 1), else_=0)),
        func.coalesce(func.sum(models.Media.file_size), 0),
        func.coalesce(
            func.sum(
                case((models.Media.media_type == "video", models.Media.duration), else_=0)
            ),
            0,
        ),
        func.avg(case((models.Media.rating > 0, models.Media.rating), else_=None)),
    ).one()
    total, favorites, rated, missing, total_size, total_duration, avg_rating = agg

    return {
        "total": int(total or 0),
        "by_type": {
            "video": by_type.get("video", 0),
            "manga": by_type.get("manga", 0),
            "image": by_type.get("image", 0),
            "audio": by_type.get("audio", 0),
        },
        "by_type_size": {
            "video": by_type_size.get("video", 0),
            "manga": by_type_size.get("manga", 0),
            "image": by_type_size.get("image", 0),
            "audio": by_type_size.get("audio", 0),
        },
        "view_status": {
            "unviewed": int(by_status.get("unviewed", 0)),
            "viewing": int(by_status.get("viewing", 0)),
            "viewed": int(by_status.get("viewed", 0)),
        },
        "favorites": int(favorites or 0),
        "rated": int(rated or 0),
        "missing": int(missing or 0),
        "total_size_bytes": int(total_size or 0),
        "total_duration_seconds": int(total_duration or 0),
        "average_rating": round(float(avg_rating), 2) if avg_rating is not None else 0.0,
    }


def overview(db: Session) -> dict:
    return _overview_cache.get_or_compute("overview", lambda: _overview_compute(db))


def _distribution_compute(db: Session) -> dict:
    base = _visible(db)

    rating_rows = dict(
        base.with_entities(models.Media.rating, func.count(models.Media.id))
        .group_by(models.Media.rating)
        .all()
    )
    rating_histogram = {str(i): int(rating_rows.get(i, 0)) for i in range(0, 6)}

    # source_site: NULL means "local". Coalesce in SQL so we get one row per bucket.
    source_label = func.coalesce(models.Media.source_site, "local")
    source_rows = (
        base.with_entities(source_label, func.count(models.Media.id))
        .group_by(source_label)
        .all()
    )
    by_source: dict[str, int] = {label: int(count) for label, count in source_rows}

    # Library growth, bucketed by month of created_at, in SQL. Rows with a NULL
    # created_at would group to NULL — filter them out so they don't become a
    # phantom "null" bucket on the chart.
    month_key = func.strftime("%Y-%m", models.Media.created_at)
    growth_rows = (
        base.filter(models.Media.created_at.isnot(None))
        .with_entities(month_key.label("month"), func.count(models.Media.id))
        .group_by("month")
        .order_by("month")
        .all()
    )
    growth = []
    cumulative = 0
    for month, added in growth_rows:
        cumulative += int(added)
        growth.append({"month": month, "added": int(added), "cumulative": cumulative})

    return {
        "rating_histogram": rating_histogram,
        "by_source": dict(sorted(by_source.items(), key=lambda kv: kv[1], reverse=True)),
        "growth": growth,
    }


def distribution(db: Session) -> dict:
    return _distribution_cache.get_or_compute("distribution", lambda: _distribution_compute(db))


def _activity_compute(db: Session, days: int) -> dict:
    cutoff = datetime.utcnow() - timedelta(days=days)

    # Daily bucketing in SQL. We also break down by media_type in the same scan
    # so the frontend can offer a type filter without a second request.
    day_key = func.strftime("%Y-%m-%d", models.Media.last_opened_at)
    rows = (
        _visible(db)
        .filter(models.Media.last_opened_at.isnot(None))
        .filter(models.Media.last_opened_at >= cutoff)
        .with_entities(day_key.label("d"), models.Media.media_type, func.count(models.Media.id))
        .group_by("d", models.Media.media_type)
        .all()
    )

    total_by_day: Counter = Counter()
    type_by_day: dict[str, Counter] = {}
    for d, mt, c in rows:
        c = int(c)
        total_by_day[d] += c
        type_by_day.setdefault(mt or "unknown", Counter())[d] += c

    ordered = sorted(total_by_day.items())
    total = sum(total_by_day.values())
    by_type = {
        mt: [{"date": d, "count": c} for d, c in sorted(counter.items())]
        for mt, counter in type_by_day.items()
    }

    return {
        "days": days,
        "from_date": cutoff.strftime("%Y-%m-%d"),
        "to_date": datetime.utcnow().strftime("%Y-%m-%d"),
        "max": max(total_by_day.values()) if total_by_day else 0,
        "total": total,
        "buckets": [{"date": d, "count": c} for d, c in ordered],
        "by_type": by_type,
    }


def activity(db: Session, days: int = 365) -> dict:
    days = max(1, min(days, 1100))
    # Days is part of the cache key so a 30-day query and a 365-day query
    # don't clobber each other.
    return _activity_cache.get_or_compute(("activity", days), lambda: _activity_compute(db, days))


def _project(m: models.Media) -> dict:
    return {
        "id": m.id,
        "title": m.title,
        "cover_path": m.cover_path,
        "media_type": m.media_type,
        "rating": m.rating or 0,
        "view_status": m.view_status,
        "last_opened_at": m.last_opened_at,
    }


def _attention_compute(db: Session, stale_days: int, limit: int) -> dict:
    stale_before = datetime.utcnow() - timedelta(days=max(1, stale_days))
    base = _visible(db).filter(models.Media.is_missing.is_(False))

    # High-rated but gathering dust: never opened, or not opened since the cutoff.
    dusty = (
        base.filter(models.Media.rating >= 4)
        .filter(
            (models.Media.last_opened_at.is_(None))
            | (models.Media.last_opened_at < stale_before)
        )
        .order_by(
            models.Media.last_opened_at.is_(None).desc(),
            models.Media.last_opened_at.asc(),
        )
        .limit(limit)
        .all()
    )

    # Watched / in-progress but never rated — easy wins for tidying up.
    unrated = (
        base.filter(models.Media.rating == 0)
        .filter(models.Media.view_status.in_(("viewing", "viewed")))
        .order_by(
            models.Media.last_opened_at.is_(None).asc(),
            models.Media.last_opened_at.desc(),
        )
        .limit(limit)
        .all()
    )

    return {
        "stale_days": stale_days,
        "dusty": [_project(m) for m in dusty],
        "unrated": [_project(m) for m in unrated],
    }


def attention(db: Session, stale_days: int = 90, limit: int = 24) -> dict:
    limit = max(1, min(limit, 100))
    return _attention_cache.get_or_compute(
        ("attention", stale_days, limit),
        lambda: _attention_compute(db, stale_days, limit),
    )


# ---------------------------------------------------------------------------
# Highlights: bundled "top" lists for the dashboard (one request, three cards).
# ---------------------------------------------------------------------------

def _top_creators(db: Session, limit: int) -> list[dict]:
    """Top creators by media count, X authors + manga artists merged.

    We reuse the heavy lifting in creators.py so the count rule (visibility-
    aware, distinct media) stays consistent with /creators.
    """
    merged = creators_mod._x_creators(db) + creators_mod._artist_creators(db)
    merged.sort(key=lambda c: c["media_count"], reverse=True)
    return [
        {
            "key": c["key"],
            "kind": c["kind"],
            "screen_name": c.get("screen_name"),
            "display_name": c.get("display_name") or c.get("screen_name") or "（无名）",
            "media_count": c["media_count"],
            "cover_path": c.get("cover_path"),
        }
        for c in merged[:limit]
    ]


def _top_videos(db: Session, limit: int) -> list[dict]:
    """Longest videos by duration, visibility-aware."""
    rows = (
        _visible(db)
        .filter(models.Media.media_type == "video")
        .filter(models.Media.duration.isnot(None))
        .filter(models.Media.duration > 0)
        .order_by(models.Media.duration.desc())
        .limit(limit)
        .all()
    )
    return [
        {
            "id": m.id,
            "title": m.title,
            "cover_path": m.cover_path,
            "duration": int(m.duration or 0),
            "rating": int(m.rating or 0),
            "file_size": int(m.file_size or 0),
            "last_opened_at": m.last_opened_at,
        }
        for m in rows
    ]


def _top_tags(db: Session, limit: int) -> list[dict]:
    """Most-used tags by media count. Excludes the 'artist' namespace because
    artist tags are already represented by the top-creators card and would
    otherwise dominate the list."""
    # Raw SQL because `Tag.namespace` exists in the DB (added via ALTER TABLE
    # in the tagging refactor) but the ORM class hasn't been re-declared.
    rows = db.execute(
        text(
            """
            SELECT t.name AS name, t.namespace AS namespace, COUNT(mt.media_id) AS n
            FROM tags t
            JOIN media_tag mt ON mt.tag_id = t.id
            JOIN media m ON m.id = mt.media_id
            WHERE m.duplicate_status NOT IN ('checking', 'strong_duplicate', 'suspected_duplicate')
              AND COALESCE(t.namespace, 'general') != 'artist'
            GROUP BY t.id
            ORDER BY n DESC
            LIMIT :limit
            """
        ),
        {"limit": limit},
    ).all()
    return [
        {"name": r.name, "namespace": r.namespace or "general", "count": int(r.n)}
        for r in rows
    ]


def _highlights_compute(db: Session, limit: int) -> dict:
    return {
        "limit": limit,
        "top_creators": _top_creators(db, limit),
        "top_videos": _top_videos(db, limit),
        "top_tags": _top_tags(db, max(limit, 12)),  # tags are cheaper, show a few more
    }


def highlights(db: Session, limit: int = 10) -> dict:
    limit = max(1, min(limit, 50))
    return _highlights_cache.get_or_compute(
        ("highlights", limit),
        lambda: _highlights_compute(db, limit),
    )
