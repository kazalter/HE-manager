"""Read-only aggregations for the personal dashboard.

Everything here is derived from columns that already exist on `Media` — no schema
changes, no extra tables. We mirror the library's visibility rule: rows whose
`duplicate_status` is checking / strong / suspected are hidden from the user, so
they must not skew the numbers either.

Timestamps (`last_opened_at`, `created_at`) are stored as naive UTC. This app is
self-hosted on the user's own machine, so we bucket by the date portion as-is —
good enough for a personal dashboard, and documented here so it isn't a mystery
later.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import datetime, timedelta
from typing import Optional

from sqlalchemy import func
from sqlalchemy.orm import Session

from . import models

# duplicate_status values the library hides; stats follow the same rule.
HIDDEN_DUP_STATUSES = ("checking", "strong_duplicate", "suspected_duplicate")


def _visible(db: Session):
    """Base query over the media the user actually sees in the library."""
    return db.query(models.Media).filter(
        models.Media.duplicate_status.notin_(HIDDEN_DUP_STATUSES)
    )


def overview(db: Session) -> dict:
    base = _visible(db)

    by_type = dict(
        base.with_entities(models.Media.media_type, func.count(models.Media.id))
        .group_by(models.Media.media_type)
        .all()
    )
    by_status = dict(
        base.with_entities(models.Media.view_status, func.count(models.Media.id))
        .group_by(models.Media.view_status)
        .all()
    )

    total = base.count()
    favorites = base.filter(models.Media.favorite.is_(True)).count()
    rated = base.filter(models.Media.rating > 0).count()
    missing = base.filter(models.Media.is_missing.is_(True)).count()

    total_size = base.with_entities(func.coalesce(func.sum(models.Media.file_size), 0)).scalar() or 0
    total_duration = (
        base.filter(models.Media.media_type == "video")
        .with_entities(func.coalesce(func.sum(models.Media.duration), 0))
        .scalar()
        or 0
    )
    avg_rating = (
        base.filter(models.Media.rating > 0)
        .with_entities(func.avg(models.Media.rating))
        .scalar()
    )

    return {
        "total": total,
        "by_type": {
            "video": by_type.get("video", 0),
            "manga": by_type.get("manga", 0),
            "image": by_type.get("image", 0),
        },
        "view_status": {
            "unviewed": by_status.get("unviewed", 0),
            "viewing": by_status.get("viewing", 0),
            "viewed": by_status.get("viewed", 0),
        },
        "favorites": favorites,
        "rated": rated,
        "missing": missing,
        "total_size_bytes": int(total_size),
        "total_duration_seconds": int(total_duration),
        "average_rating": round(float(avg_rating), 2) if avg_rating is not None else 0.0,
    }


def distribution(db: Session) -> dict:
    base = _visible(db)

    rating_rows = dict(
        base.with_entities(models.Media.rating, func.count(models.Media.id))
        .group_by(models.Media.rating)
        .all()
    )
    rating_histogram = {str(i): int(rating_rows.get(i, 0)) for i in range(0, 6)}

    by_source: Counter = Counter()
    for (site,) in base.with_entities(models.Media.source_site).all():
        by_source[site or "local"] += 1

    # Library growth, bucketed by month of created_at, with a running cumulative.
    monthly: Counter = Counter()
    for (created,) in base.with_entities(models.Media.created_at).all():
        if created is None:
            continue
        monthly[created.strftime("%Y-%m")] += 1

    growth = []
    cumulative = 0
    for month in sorted(monthly):
        added = monthly[month]
        cumulative += added
        growth.append({"month": month, "added": added, "cumulative": cumulative})

    return {
        "rating_histogram": rating_histogram,
        "by_source": dict(sorted(by_source.items(), key=lambda kv: kv[1], reverse=True)),
        "growth": growth,
    }


def activity(db: Session, days: int = 365) -> dict:
    days = max(1, min(days, 1100))
    cutoff = datetime.utcnow() - timedelta(days=days)

    rows = (
        _visible(db)
        .filter(models.Media.last_opened_at.isnot(None))
        .filter(models.Media.last_opened_at >= cutoff)
        .with_entities(models.Media.last_opened_at)
        .all()
    )

    buckets: Counter = Counter()
    for (opened,) in rows:
        if opened is None:
            continue
        buckets[opened.strftime("%Y-%m-%d")] += 1

    ordered = sorted(buckets.items())
    total = sum(buckets.values())
    return {
        "days": days,
        "from_date": cutoff.strftime("%Y-%m-%d"),
        "to_date": datetime.utcnow().strftime("%Y-%m-%d"),
        "max": max(buckets.values()) if buckets else 0,
        "total": total,
        "buckets": [{"date": d, "count": c} for d, c in ordered],
    }


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


def attention(db: Session, stale_days: int = 90, limit: int = 24) -> dict:
    limit = max(1, min(limit, 100))
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
