"""Direct GraphQL sync of liked tweets — bypasses the archive workflow.

Pulls the user's Likes timeline via the same authenticated GraphQL endpoint x.com itself
uses, paged with cursors, and inserts any new tweet ids as XPost rows. Does NOT download
media — that's the existing import job's job. Run sync, then run import.

Stops on any of:
- Two consecutive pages contain only tweets we already have (incremental catch-up).
- Cursor stops advancing.
- Hard page cap reached (defensive bound against infinite loops).
- Cancel requested by user.
- Auth error (401 / 403) — cookie expired / blocked.
- Repeated transient errors after backoff.
"""
from __future__ import annotations

import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Dict, Optional
from urllib.parse import unquote

from .. import database, models
from . import client


SYNC_JOB_LOCK = threading.Lock()
SYNC_JOBS: Dict[str, "SyncJob"] = {}

# Safety knobs. Errs on the polite side — Likes endpoint is the most rate-limited GraphQL op
# X exposes (anecdotally ~50 req / 15min for authenticated browsers), so we space pages out
# and cap total pages.
PAGE_SIZE = 20
PAGE_DELAY_SEC = 2.5
MAX_PAGES = 500  # 500 * 20 = 10k likes; covers any realistic account
MAX_CONSECUTIVE_EXISTING_PAGES = 2
MAX_TRANSIENT_RETRIES = 3
INITIAL_BACKOFF_SEC = 5.0
MAX_BACKOFF_SEC = 60.0


@dataclass
class SyncJob:
    job_id: str
    source_id: int
    status: str = "queued"  # queued, running, completed, failed, canceled
    message: str = ""
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    pages_scanned: int = 0
    posts_seen: int = 0
    new_posts: int = 0
    existing_posts: int = 0
    cancel_requested: bool = False
    stop_reason: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "job_id": self.job_id,
            "source_id": self.source_id,
            "status": self.status,
            "message": self.message,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "pages_scanned": self.pages_scanned,
            "posts_seen": self.posts_seen,
            "new_posts": self.new_posts,
            "existing_posts": self.existing_posts,
            "cancel_requested": self.cancel_requested,
            "stop_reason": self.stop_reason,
        }


def _extract_user_id(cookie: str) -> Optional[str]:
    """`twid` cookie holds the numeric user id, URL-encoded as `u%3D<id>`."""
    match = re.search(r"twid=([^;]+)", cookie)
    if not match:
        return None
    raw = unquote(match.group(1))
    m = re.match(r"u=(\d+)", raw)
    return m.group(1) if m else None


def _sleep_canceling(job: SyncJob, seconds: float) -> bool:
    """Sleep in 0.5s ticks so cancel takes effect quickly. Returns True if canceled."""
    elapsed = 0.0
    while elapsed < seconds:
        if job.cancel_requested:
            return True
        time.sleep(min(0.5, seconds - elapsed))
        elapsed += 0.5
    return False


def _run(job: SyncJob, cookie: str) -> None:
    db = database.SessionLocal()
    try:
        user_id = _extract_user_id(cookie)
        if not user_id:
            job.status = "failed"
            job.message = "无法从 cookie 中提取 user_id（缺少 twid）"
            job.stop_reason = "missing_user_id"
            return

        source = db.query(models.XImportSource).filter(models.XImportSource.id == job.source_id).first()
        if not source:
            job.status = "failed"
            job.message = "X 来源不存在"
            job.stop_reason = "missing_source"
            return

        proxy = source.proxy
        job.status = "running"
        job.started_at = datetime.utcnow().isoformat()
        job.message = "开始拉取喜欢列表"

        cursor: Optional[str] = None
        consecutive_existing = 0
        backoff = 0.0
        transient_attempts = 0

        for page_num in range(1, MAX_PAGES + 1):
            if job.cancel_requested:
                job.stop_reason = "canceled"
                break

            try:
                tweets, next_cursor = client.fetch_likes_page(
                    cookie=cookie, user_id=user_id, cursor=cursor, count=PAGE_SIZE, proxy=proxy
                )
                transient_attempts = 0
                backoff = 0.0
            except client.TweetUnavailable as exc:
                # 403 / 404 — auth blocked or endpoint moved. Don't retry, fail clearly.
                job.status = "failed"
                job.message = f"无法访问喜欢列表：{exc}"
                job.stop_reason = "auth_error"
                return
            except client.TweetTransientError as exc:
                transient_attempts += 1
                if transient_attempts > MAX_TRANSIENT_RETRIES:
                    job.status = "failed"
                    job.message = f"重试 {MAX_TRANSIENT_RETRIES} 次仍失败：{exc}"
                    job.stop_reason = "transient_exhausted"
                    return
                backoff = min(MAX_BACKOFF_SEC, max(INITIAL_BACKOFF_SEC, backoff * 2 if backoff else INITIAL_BACKOFF_SEC))
                job.message = f"瞬时错误：{exc}，{int(backoff)}s 后重试 ({transient_attempts}/{MAX_TRANSIENT_RETRIES})"
                if _sleep_canceling(job, backoff):
                    job.stop_reason = "canceled"
                    break
                continue

            job.pages_scanned += 1
            job.posts_seen += len(tweets)

            if not tweets and not next_cursor:
                job.stop_reason = "end_of_timeline"
                break

            page_new = 0
            page_existing = 0
            for t in tweets:
                existing = (
                    db.query(models.XPost.id)
                    .filter(
                        models.XPost.source_id == job.source_id,
                        models.XPost.tweet_id == t.tweet_id,
                    )
                    .first()
                )
                if existing:
                    page_existing += 1
                    continue
                post = models.XPost(
                    source_id=job.source_id,
                    tweet_id=t.tweet_id,
                    url=t.url,
                    author_screen_name=t.author_screen_name,
                    full_text=t.full_text,
                    archive_name="(direct-sync)",
                    status="pending",
                )
                db.add(post)
                page_new += 1

            try:
                db.commit()
            except Exception as exc:
                db.rollback()
                job.status = "failed"
                job.message = f"数据库写入失败：{exc}"
                job.stop_reason = "db_error"
                return

            job.new_posts += page_new
            job.existing_posts += page_existing
            job.message = (
                f"已扫描 {job.pages_scanned} 页 · 新增 {job.new_posts} · 已有 {job.existing_posts}"
            )

            # Incremental stop: two pages in a row with nothing new → caught up.
            if page_new == 0 and tweets:
                consecutive_existing += 1
                if consecutive_existing >= MAX_CONSECUTIVE_EXISTING_PAGES:
                    job.stop_reason = "incremental_caught_up"
                    break
            else:
                consecutive_existing = 0

            if not next_cursor:
                job.stop_reason = "end_of_timeline"
                break
            if next_cursor == cursor:
                job.stop_reason = "cursor_stuck"
                break
            cursor = next_cursor

            if _sleep_canceling(job, PAGE_DELAY_SEC):
                job.stop_reason = "canceled"
                break
        else:
            job.stop_reason = "page_cap"

        if job.cancel_requested:
            job.status = "canceled"
            job.message = f"已取消 · 新增 {job.new_posts} 条"
        else:
            job.status = "completed"
            reason_label = {
                "end_of_timeline": "已到末尾",
                "incremental_caught_up": "增量同步已追上",
                "cursor_stuck": "cursor 未推进",
                "page_cap": f"达到 {MAX_PAGES} 页上限",
            }.get(job.stop_reason or "", job.stop_reason or "")
            job.message = f"完成 · 新增 {job.new_posts} · 已有 {job.existing_posts}（{reason_label}）"
    except Exception as exc:
        try:
            db.rollback()
        except Exception:
            pass
        job.status = "failed"
        job.message = f"任务失败：{exc}"
        job.stop_reason = job.stop_reason or "error"
    finally:
        job.finished_at = datetime.utcnow().isoformat()
        db.close()


def start_sync(*, source_id: int, cookie: str) -> SyncJob:
    with SYNC_JOB_LOCK:
        for existing in SYNC_JOBS.values():
            if existing.source_id == source_id and existing.status in ("queued", "running"):
                return existing
        job_id = str(uuid.uuid4())
        job = SyncJob(job_id=job_id, source_id=source_id)
        SYNC_JOBS[job_id] = job

    threading.Thread(target=_run, args=(job, cookie), daemon=True, name=f"x-sync-{job_id}").start()
    return job


def get_sync(job_id: str) -> Optional[SyncJob]:
    return SYNC_JOBS.get(job_id)


def latest_sync_for_source(source_id: int) -> Optional[SyncJob]:
    candidates = [j for j in SYNC_JOBS.values() if j.source_id == source_id]
    if not candidates:
        return None
    return max(candidates, key=lambda j: j.started_at or "")


def request_cancel(job_id: str) -> Optional[SyncJob]:
    job = SYNC_JOBS.get(job_id)
    if not job:
        return None
    if job.status in ("completed", "failed", "canceled"):
        return job
    job.cancel_requested = True
    job.message = "正在取消"
    return job
