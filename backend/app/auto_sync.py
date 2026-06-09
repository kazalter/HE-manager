"""Scheduled auto-sync+download for WNACG and X external favourites.

Architecture:
- A single daemon thread runs a lightweight event loop that checks every 30s
  whether any source's next_run_at has arrived.
- When a source is due, the scheduler spawns a one-shot daemon thread to run
  sync+download (so the scheduler tick is never blocked by a long download).
- Source configs (enabled, interval_hours, next_run_at) are persisted in the DB
  columns added to ExternalFavoriteSource / XImportSource.
- Execution history is recorded in the auto_sync_logs table.
- On app startup, ``init()`` restores timers from DB state.
"""
from __future__ import annotations

import os
import threading
import time
import traceback
from datetime import datetime, timedelta
from typing import Dict, Optional, Set

from . import database, models

# ---------------------------------------------------------------------------
# Scheduler singleton
# ---------------------------------------------------------------------------

_lock = threading.Lock()
_running = False
_ticker_thread: Optional[threading.Thread] = None
# source keys currently executing (to prevent overlap)
_active: Set[str] = set()

# Minimum tick interval in seconds.  The ticker wakes up this often to check
# whether any source is overdue.  30s is a good balance between responsiveness
# and idle-CPU friendliness.
TICK_INTERVAL = 30


def _source_key(source_type: str, source_id: int) -> str:
    return f"{source_type}:{source_id}"


# ---------------------------------------------------------------------------
# Ticker loop
# ---------------------------------------------------------------------------

def _tick() -> None:
    """Called every TICK_INTERVAL seconds.  Checks all auto-sync-enabled
    sources and fires off workers for any that are overdue."""
    now = datetime.utcnow()
    db = database.SessionLocal()
    try:
        # --- WNACG sources ---
        wnacg_sources = (
            db.query(models.ExternalFavoriteSource)
            .filter(
                models.ExternalFavoriteSource.auto_sync_enabled == True,  # noqa: E712
                models.ExternalFavoriteSource.source_type == "wnacg",
            )
            .all()
        )
        for source in wnacg_sources:
            key = _source_key("wnacg", source.id)
            if key in _active:
                continue
            if source.auto_sync_next_run_at and source.auto_sync_next_run_at > now:
                continue
            # Time to run
            _active.add(key)
            threading.Thread(
                target=_run_wnacg,
                args=(source.id,),
                daemon=True,
                name=f"auto-sync-wnacg-{source.id}",
            ).start()

        # --- X sources ---
        x_sources = (
            db.query(models.XImportSource)
            .filter(models.XImportSource.auto_sync_enabled == True)  # noqa: E712
            .all()
        )
        for source in x_sources:
            key = _source_key("x", source.id)
            if key in _active:
                continue
            if source.auto_sync_next_run_at and source.auto_sync_next_run_at > now:
                continue
            _active.add(key)
            threading.Thread(
                target=_run_x,
                args=(source.id,),
                daemon=True,
                name=f"auto-sync-x-{source.id}",
            ).start()
    except Exception as exc:
        print(f"  [auto-sync] ticker error: {exc}")
    finally:
        db.close()


def _ticker_loop() -> None:
    """Background loop that fires _tick() periodically."""
    while _running:
        try:
            _tick()
        except Exception:
            traceback.print_exc()
        # Sleep in small increments so stop() isn't blocked for 30s
        for _ in range(int(TICK_INTERVAL / 0.5)):
            if not _running:
                return
            time.sleep(0.5)


# ---------------------------------------------------------------------------
# WNACG auto-sync worker
# ---------------------------------------------------------------------------

def _run_wnacg(source_id: int) -> None:
    """Sync WNACG favourites then download all new (un-downloaded) items."""
    key = _source_key("wnacg", source_id)
    db = database.SessionLocal()
    started_at = datetime.utcnow()
    synced_count = 0
    downloaded_count = 0
    failed_count = 0
    status = "success"
    message = ""

    try:
        source = (
            db.query(models.ExternalFavoriteSource)
            .filter(models.ExternalFavoriteSource.id == source_id)
            .first()
        )
        if not source:
            status = "failed"
            message = "数据源不存在"
            return
        if not source.cookie:
            status = "failed"
            message = "Cookie 未配置，无法自动同步"
            return
        if not source.download_root_path:
            status = "failed"
            message = "下载路径未配置"
            return

        source.auto_sync_last_status = "running"
        source.auto_sync_last_message = "正在自动同步"
        source.auto_sync_last_run_at = started_at
        db.commit()

        # --- Phase 1: sync favourites list ---
        # Lazy import to avoid circular dependency (these live in main.py)
        from . import main as _main, external_sources

        cookie = source.cookie
        base_url = _main.get_url_base(source.favorites_url)
        first_html = external_sources.fetch_html(source.favorites_url, cookie)
        categories = external_sources.parse_wnacg_categories(first_html)

        existing_items = {
            item.external_id: item
            for item in db.query(models.ExternalFavoriteItem)
            .filter(models.ExternalFavoriteItem.source_id == source.id)
            .all()
        }
        existing_ids = set(existing_items.keys())
        page_limit = 30
        parsed_items = []

        if categories:
            for category in categories:
                for page in range(1, page_limit + 1):
                    page_url = external_sources.wnacg_category_url(
                        category.id, page, base_url=base_url
                    )
                    page_html = external_sources.fetch_html(page_url, cookie)
                    page_items = external_sources.parse_wnacg_favorites(
                        page_html,
                        base_url=base_url,
                        category_id=category.id,
                        category_name=category.name,
                    )
                    parsed_items.extend(page_items)
                    if any(
                        item.external_id in existing_ids
                        for item in page_items
                    ):
                        break
                    if not external_sources.html_has_next_page(page_html):
                        break
        else:
            parsed_items = external_sources.parse_wnacg_favorites(
                first_html, base_url=base_url
            )

        now = datetime.utcnow()
        for db_item in existing_items.values():
            db_item.sync_position = None

        deduped = {item.external_id: item for item in parsed_items}
        new_count = 0
        for sync_position, item in enumerate(deduped.values()):
            db_item = existing_items.get(item.external_id)
            if not db_item:
                db_item = models.ExternalFavoriteItem(
                    source=source,
                    source_type="wnacg",
                    external_id=item.external_id,
                    title=item.title,
                    url=item.url,
                    cover_url=item.cover_url,
                    category_id=item.category_id,
                    category_name=item.category_name,
                    sync_position=sync_position,
                    last_seen_at=now,
                )
                db.add(db_item)
                new_count += 1
            else:
                db_item.title = item.title
                db_item.url = item.url
                db_item.cover_url = item.cover_url or db_item.cover_url
                db_item.category_id = item.category_id
                db_item.category_name = item.category_name
                db_item.sync_position = sync_position
                db_item.last_seen_at = now

        source.status = "ok"
        source.last_synced_at = now
        source.last_error = None
        db.commit()
        synced_count = new_count

        # --- Phase 2: download un-downloaded items ---
        all_items = (
            db.query(models.ExternalFavoriteItem)
            .filter(models.ExternalFavoriteItem.source_id == source.id)
            .all()
        )
        download_root = source.download_root_path
        to_download = []
        for fav_item in all_items:
            local_media = _main.find_local_media_for_external_item(fav_item, db)
            if not local_media:
                to_download.append(fav_item)

        if to_download:
            message = f"同步完成（新增 {synced_count}），开始下载 {len(to_download)} 本"
            source.auto_sync_last_message = message
            db.commit()

            _main.ensure_external_manga_library(source, download_root, db)

            for fav_item in to_download:
                try:
                    plan = _main.prepare_wnacg_download_plan(
                        fav_item, source, download_root
                    )
                    result = _main.download_wnacg_item(
                        fav_item, source, plan, job=None
                    )
                    _main.upsert_external_downloaded_media(
                        fav_item, source, result["path"], download_root, db
                    )
                    downloaded_count += 1
                except Exception as exc:
                    failed_count += 1
                    _main.log_wnacg_download_failure(
                        download_root,
                        fav_item.title,
                        fav_item.url,
                        str(exc),
                    )

        if failed_count > 0:
            status = "partial"
            message = (
                f"同步新增 {synced_count}，"
                f"下载 {downloaded_count} 本，"
                f"失败 {failed_count} 本"
            )
        else:
            status = "success"
            message = (
                f"同步新增 {synced_count}，"
                f"下载 {downloaded_count} 本"
            )

    except Exception as exc:
        status = "failed"
        message = f"自动同步失败：{exc}"
        traceback.print_exc()
    finally:
        finished_at = datetime.utcnow()
        duration = int((finished_at - started_at).total_seconds())
        try:
            # Refresh source from DB (it may have been modified during long runs)
            source = (
                db.query(models.ExternalFavoriteSource)
                .filter(models.ExternalFavoriteSource.id == source_id)
                .first()
            )
            if source:
                source.auto_sync_last_status = status
                source.auto_sync_last_message = message
                source.auto_sync_last_run_at = started_at
                interval = source.auto_sync_interval_hours or 24
                source.auto_sync_next_run_at = (
                    finished_at + timedelta(hours=interval)
                )
                db.commit()

            log = models.AutoSyncLog(
                source_type="wnacg",
                source_id=source_id,
                action="sync+download",
                status=status,
                synced_count=synced_count,
                downloaded_count=downloaded_count,
                failed_count=failed_count,
                message=message,
                started_at=started_at,
                finished_at=finished_at,
                duration_seconds=duration,
            )
            db.add(log)
            db.commit()
        except Exception:
            traceback.print_exc()
        finally:
            db.close()
            _active.discard(key)
            print(
                f"  [auto-sync] wnacg #{source_id} finished: "
                f"{status} — synced={synced_count} downloaded={downloaded_count} "
                f"failed={failed_count} ({duration}s)"
            )


# ---------------------------------------------------------------------------
# X auto-sync worker
# ---------------------------------------------------------------------------

def _run_x(source_id: int) -> None:
    """Sync X likes via GraphQL then download all pending posts."""
    key = _source_key("x", source_id)
    db = database.SessionLocal()
    started_at = datetime.utcnow()
    synced_count = 0
    downloaded_count = 0
    failed_count = 0
    status = "success"
    message = ""

    try:
        source = (
            db.query(models.XImportSource)
            .filter(models.XImportSource.id == source_id)
            .first()
        )
        if not source:
            status = "failed"
            message = "数据源不存在"
            return
        if not source.cookie:
            status = "failed"
            message = "Cookie 未配置，无法自动同步"
            return
        if not source.download_root_path:
            status = "failed"
            message = "下载路径未配置"
            return

        source.auto_sync_last_status = "running"
        source.auto_sync_last_message = "正在自动同步"
        source.auto_sync_last_run_at = started_at
        db.commit()

        from .x_import import sync as x_sync, importer as x_importer

        # --- Phase 1: GraphQL sync ---
        # Check for existing running sync
        existing_sync = x_sync.latest_sync_for_source(source.id)
        if existing_sync and existing_sync.status in ("queued", "running"):
            status = "failed"
            message = "已有同步任务在进行，跳过本次自动同步"
            return

        sync_job = x_sync.start_sync(
            source_id=source.id, cookie=source.cookie
        )

        # Wait for sync to complete (poll every 2s, timeout 10min)
        timeout = 600
        elapsed = 0
        while sync_job.status in ("queued", "running") and elapsed < timeout:
            time.sleep(2)
            elapsed += 2

        if sync_job.status == "completed":
            synced_count = sync_job.new_posts
        elif sync_job.status == "failed":
            status = "failed"
            message = f"同步失败：{sync_job.message}"
            return
        else:
            status = "failed"
            message = f"同步超时（{timeout}s）"
            x_sync.request_cancel(sync_job.job_id)
            return

        # --- Phase 2: download pending posts ---
        # Check for existing running import
        existing_import = x_importer.latest_job_for_source(source.id)
        if existing_import and existing_import.status in (
            "queued",
            "preparing",
            "running",
            "paused",
        ):
            status = "partial"
            message = (
                f"同步新增 {synced_count} 条，"
                "但已有下载任务在进行，跳过下载"
            )
            return

        import uuid

        post_ids = x_importer.select_pending_post_ids(db, source.id)
        if not post_ids:
            status = "success"
            message = f"同步新增 {synced_count} 条，无需下载"
            return

        from . import main as _main

        job_id = str(uuid.uuid4())
        import_job = x_importer.start_job(
            job_id=job_id,
            source_id=source.id,
            download_root=source.download_root_path,
            thumbnail_dir=_main.THUMBNAIL_DIR,
            post_ids=post_ids,
            cookie=source.cookie,
        )

        # Wait for import to complete (poll every 5s, timeout 4h for large backlogs)
        timeout = 14400
        elapsed = 0
        while import_job.status in (
            "queued",
            "preparing",
            "running",
        ) and elapsed < timeout:
            time.sleep(5)
            elapsed += 5

        if import_job.status in ("completed", "canceled"):
            downloaded_count = import_job.completed_posts
            failed_count = import_job.failed_posts
            if failed_count > 0:
                status = "partial"
                message = (
                    f"同步新增 {synced_count}，"
                    f"下载 {downloaded_count} 个，"
                    f"失败 {failed_count} 个"
                )
            else:
                status = "success"
                message = (
                    f"同步新增 {synced_count}，"
                    f"下载 {downloaded_count} 个"
                )
        elif import_job.status == "failed":
            status = "failed"
            downloaded_count = import_job.completed_posts
            failed_count = import_job.failed_posts
            message = f"下载失败：{import_job.message}"
        else:
            status = "partial"
            downloaded_count = import_job.completed_posts
            failed_count = import_job.failed_posts
            message = f"下载超时（{timeout}s），已完成 {downloaded_count} 个"
            x_importer.request_cancel(job_id)

    except Exception as exc:
        status = "failed"
        message = f"自动同步失败：{exc}"
        traceback.print_exc()
    finally:
        finished_at = datetime.utcnow()
        duration = int((finished_at - started_at).total_seconds())
        try:
            source = (
                db.query(models.XImportSource)
                .filter(models.XImportSource.id == source_id)
                .first()
            )
            if source:
                source.auto_sync_last_status = status
                source.auto_sync_last_message = message
                source.auto_sync_last_run_at = started_at
                interval = source.auto_sync_interval_hours or 24
                source.auto_sync_next_run_at = (
                    finished_at + timedelta(hours=interval)
                )
                source.last_sync_at = datetime.utcnow()
                db.commit()

            log = models.AutoSyncLog(
                source_type="x",
                source_id=source_id,
                action="sync+download",
                status=status,
                synced_count=synced_count,
                downloaded_count=downloaded_count,
                failed_count=failed_count,
                message=message,
                started_at=started_at,
                finished_at=finished_at,
                duration_seconds=duration,
            )
            db.add(log)
            db.commit()
        except Exception:
            traceback.print_exc()
        finally:
            db.close()
            _active.discard(key)
            print(
                f"  [auto-sync] x #{source_id} finished: "
                f"{status} — synced={synced_count} downloaded={downloaded_count} "
                f"failed={failed_count} ({duration}s)"
            )


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def init() -> None:
    """Start the scheduler.  Called once from app startup (main.py)."""
    global _running, _ticker_thread
    with _lock:
        if _running:
            return
        _running = True
        # Restore next_run_at for sources that have auto_sync enabled but
        # next_run_at is in the past (e.g. the server was down for a while).
        _restore_schedules()
        _ticker_thread = threading.Thread(
            target=_ticker_loop, daemon=True, name="auto-sync-ticker"
        )
        _ticker_thread.start()
        print("  [auto-sync] scheduler started")


def stop() -> None:
    """Stop the scheduler.  Optional; daemon threads die with the process."""
    global _running
    _running = False


def is_running() -> bool:
    return _running


def is_source_active(source_type: str, source_id: int) -> bool:
    return _source_key(source_type, source_id) in _active


def trigger_now(source_type: str, source_id: int) -> bool:
    """Immediately trigger an auto-sync run for the given source.
    Returns False if the source is already running."""
    key = _source_key(source_type, source_id)
    with _lock:
        if key in _active:
            return False
        _active.add(key)
    if source_type == "wnacg":
        threading.Thread(
            target=_run_wnacg,
            args=(source_id,),
            daemon=True,
            name=f"auto-sync-wnacg-{source_id}-manual",
        ).start()
    elif source_type == "x":
        threading.Thread(
            target=_run_x,
            args=(source_id,),
            daemon=True,
            name=f"auto-sync-x-{source_id}-manual",
        ).start()
    else:
        _active.discard(key)
        return False
    return True


def update_schedule(
    source_type: str,
    source_id: int,
    enabled: bool,
    interval_hours: int,
) -> None:
    """Persist auto-sync configuration and compute next_run_at."""
    db = database.SessionLocal()
    try:
        if source_type == "wnacg":
            source = (
                db.query(models.ExternalFavoriteSource)
                .filter(models.ExternalFavoriteSource.id == source_id)
                .first()
            )
        elif source_type == "x":
            source = (
                db.query(models.XImportSource)
                .filter(models.XImportSource.id == source_id)
                .first()
            )
        else:
            return

        if not source:
            return

        source.auto_sync_enabled = enabled
        source.auto_sync_interval_hours = interval_hours
        if enabled:
            # If no previous run, schedule from now; otherwise from last run
            last_run = source.auto_sync_last_run_at or datetime.utcnow()
            source.auto_sync_next_run_at = last_run + timedelta(
                hours=interval_hours
            )
        else:
            source.auto_sync_next_run_at = None
        db.commit()
    finally:
        db.close()


def _restore_schedules() -> None:
    """On startup, ensure next_run_at is set for any enabled source that has
    a stale / NULL next_run_at (e.g. server was down)."""
    db = database.SessionLocal()
    try:
        now = datetime.utcnow()
        for source in (
            db.query(models.ExternalFavoriteSource)
            .filter(
                models.ExternalFavoriteSource.auto_sync_enabled == True,  # noqa: E712
                models.ExternalFavoriteSource.source_type == "wnacg",
            )
            .all()
        ):
            if not source.auto_sync_next_run_at or source.auto_sync_next_run_at < now:
                # Due or overdue — run soon (stagger by 60s to avoid thundering herd)
                source.auto_sync_next_run_at = now + timedelta(seconds=60)
            db.commit()

        for source in (
            db.query(models.XImportSource)
            .filter(models.XImportSource.auto_sync_enabled == True)  # noqa: E712
            .all()
        ):
            if not source.auto_sync_next_run_at or source.auto_sync_next_run_at < now:
                source.auto_sync_next_run_at = now + timedelta(seconds=60)
            db.commit()
    except Exception:
        traceback.print_exc()
    finally:
        db.close()
