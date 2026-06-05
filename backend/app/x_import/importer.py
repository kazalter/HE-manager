"""Background X-import job: orchestrates fetch + download + library entry per post.

State machine (job.status):
    queued -> preparing -> running -> (paused <-> running) -> completed | failed | canceled

Each post is its own unit of work and committed independently — pausing or canceling
mid-run never corrupts the library; the next start picks up `pending` / `failed` posts.
Already-completed posts are skipped (incremental).
"""
from __future__ import annotations

import os
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from email.utils import parsedate_to_datetime
from typing import Dict, List, Optional

from sqlalchemy.orm import Session

from .. import database, models
from . import client, storage


JOB_LOCK = threading.Lock()
JOBS: Dict[str, "ImportJob"] = {}

# Polite spacing between syndication / download calls. Free, public endpoint, but we don't
# want to hammer it.
TWEET_DELAY_SEC = 0.4
MEDIA_DELAY_SEC = 0.2

# How many recent error log entries to keep in memory for the UI.
MAX_ERROR_LOG = 50


@dataclass
class JobError:
    tweet_id: str
    message: str
    at: str


@dataclass
class ImportJob:
    job_id: str
    source_id: int
    download_root: str
    thumbnail_dir: str
    status: str = "queued"  # queued, preparing, running, paused, completed, failed, canceled
    message: str = ""
    cookie: Optional[str] = None
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    total_posts: int = 0
    scanned_posts: int = 0
    media_posts: int = 0  # posts with at least one media item
    completed_posts: int = 0
    skipped_posts: int = 0
    failed_posts: int = 0
    media_total: int = 0
    media_downloaded: int = 0
    media_failed: int = 0
    current_author: str = ""
    current_post_id: str = ""
    current_file: str = ""
    pause_requested: bool = False
    cancel_requested: bool = False
    errors: List[JobError] = field(default_factory=list)

    # Internal: ids of posts the job will process this run.
    _post_ids: List[int] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "job_id": self.job_id,
            "source_id": self.source_id,
            "download_root": self.download_root,
            "status": self.status,
            "message": self.message,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "total_posts": self.total_posts,
            "scanned_posts": self.scanned_posts,
            "media_posts": self.media_posts,
            "completed_posts": self.completed_posts,
            "skipped_posts": self.skipped_posts,
            "failed_posts": self.failed_posts,
            "media_total": self.media_total,
            "media_downloaded": self.media_downloaded,
            "media_failed": self.media_failed,
            "current_author": self.current_author,
            "current_post_id": self.current_post_id,
            "current_file": self.current_file,
            "pause_requested": self.pause_requested,
            "cancel_requested": self.cancel_requested,
            "errors": [e.__dict__ for e in self.errors[-MAX_ERROR_LOG:]],
        }

    def log_error(self, tweet_id: str, message: str) -> None:
        self.errors.append(JobError(tweet_id=tweet_id, message=message, at=datetime.utcnow().isoformat()))
        if len(self.errors) > MAX_ERROR_LOG * 2:
            self.errors = self.errors[-MAX_ERROR_LOG:]


def _parse_posted_at(raw: Optional[str]) -> Optional[datetime]:
    if not raw:
        return None
    try:
        return parsedate_to_datetime(raw).replace(tzinfo=None)
    except (TypeError, ValueError):
        try:
            return datetime.fromisoformat(raw.replace("Z", "+00:00")).replace(tzinfo=None)
        except ValueError:
            return None


def _wait_if_paused(job: ImportJob) -> None:
    """Block while pause_requested is set; return as soon as resumed or canceled."""
    while job.pause_requested and not job.cancel_requested:
        if job.status != "paused":
            job.status = "paused"
            job.message = "已暂停"
        time.sleep(0.4)
    if not job.cancel_requested and job.status == "paused":
        job.status = "running"
        job.message = "继续导入"


def select_pending_post_ids(
    db: Session,
    source_id: int,
    *,
    include_failed: bool = True,
    retry_failed_only: bool = False,
    retry_skipped_only: bool = False,
) -> List[int]:
    query = db.query(models.XPost.id).filter(models.XPost.source_id == source_id)
    if retry_skipped_only:
        query = query.filter(models.XPost.status == "skipped")
    elif retry_failed_only:
        query = query.filter(models.XPost.status == "failed")
    else:
        active_states = ["pending", "fetched", "downloading"]
        if include_failed:
            active_states.append("failed")
        query = query.filter(models.XPost.status.in_(active_states))
    return [row[0] for row in query.order_by(models.XPost.discovered_at.asc(), models.XPost.id.asc()).all()]


def _process_post(job: ImportJob, post_id: int, db: Session, folder: models.Folder) -> None:
    post = db.query(models.XPost).filter(models.XPost.id == post_id).first()
    if not post:
        return

    if post.status == "completed":
        job.skipped_posts += 1
        return

    job.scanned_posts += 1
    job.current_post_id = post.tweet_id
    job.current_author = post.author_screen_name or ""
    job.message = f"处理 {post.author_screen_name or 'x'} / {post.tweet_id}"

    post.last_attempt_at = datetime.utcnow()
    post.error_message = None
    post.status = "fetched"
    db.commit()

    try:
        tweet = client.fetch_tweet(post.tweet_id, cookie=job.cookie)
    except client.TweetUnavailable as exc:
        post.status = "skipped"
        post.error_message = str(exc)
        db.commit()
        job.skipped_posts += 1
        job.log_error(post.tweet_id, f"不可用：{exc}")
        return
    except client.TweetTransientError as exc:
        post.status = "failed"
        post.error_message = str(exc)
        db.commit()
        job.failed_posts += 1
        job.log_error(post.tweet_id, f"读取失败：{exc}")
        return

    if tweet.author_screen_name:
        post.author_screen_name = tweet.author_screen_name
    if tweet.author_name:
        post.author_name = tweet.author_name
    if tweet.full_text and not post.full_text:
        post.full_text = tweet.full_text
    posted_at = _parse_posted_at(tweet.posted_at)
    if posted_at:
        post.posted_at = posted_at

    if not tweet.media:
        post.has_media = False
        post.media_count = 0
        post.status = "skipped"
        post.completed_at = datetime.utcnow()
        post.error_message = "无媒体"
        db.commit()
        job.skipped_posts += 1
        return

    post.has_media = True
    post.media_count = len(tweet.media)
    post.status = "downloading"
    db.commit()
    job.media_posts += 1
    job.media_total += len(tweet.media)

    post_dir = storage.post_dir_for(job.download_root, post.author_screen_name, post.tweet_id)
    os.makedirs(post_dir, exist_ok=True)

    existing_media = {m.media_index: m for m in post.media_items}
    media_records: List[models.XMediaItem] = []
    any_failed = False

    for media in tweet.media:
        if job.cancel_requested:
            return
        _wait_if_paused(job)
        if job.cancel_requested:
            return

        record = existing_media.get(media.media_index)
        if record and record.status == "downloaded" and record.local_path and os.path.exists(record.local_path):
            if not (
                record.library_media_id
                and db.query(models.Media.id).filter(models.Media.id == record.library_media_id).first()
            ):
                try:
                    file_size = record.file_size or os.path.getsize(record.local_path)
                    library_media = (
                        db.query(models.Media)
                        .filter(models.Media.absolute_path == record.local_path)
                        .first()
                    )
                    if not library_media:
                        library_media = storage.upsert_library_media(
                            record, post, folder, record.local_path, file_size, job.thumbnail_dir, db
                        )
                    record.library_media_id = library_media.id
                    record.file_size = file_size
                    db.commit()
                except Exception as exc:
                    job.log_error(post.tweet_id, f"已下载媒体补入库失败：{exc}")
                    any_failed = True
            media_records.append(record)
            job.media_downloaded += 1
            continue

        if record is None:
            record = models.XMediaItem(
                post_id=post.id,
                media_index=media.media_index,
                media_type=media.media_type,
                remote_url=media.url,
                width=media.width,
                height=media.height,
                duration_ms=media.duration_ms,
                status="pending",
            )
            db.add(record)
            db.flush()
        else:
            record.remote_url = media.url
            record.media_type = media.media_type
            record.width = media.width
            record.height = media.height
            record.duration_ms = media.duration_ms

        try:
            content, content_type = client.download_media(media.url)
        except client.TweetUnavailable as exc:
            record.status = "skipped"
            record.error_message = str(exc)
            db.commit()
            job.media_failed += 1
            any_failed = True
            job.log_error(post.tweet_id, f"媒体不可用：{exc}")
            continue
        except client.TweetTransientError as exc:
            record.status = "failed"
            record.error_message = str(exc)
            db.commit()
            job.media_failed += 1
            any_failed = True
            job.log_error(post.tweet_id, f"媒体下载失败：{exc}")
            continue

        ext = storage.media_extension_from(media.url, content_type, media.media_type)
        filename = f"{media.media_index + 1}{ext}"
        file_path = os.path.join(post_dir, filename)
        try:
            _, size = storage.write_binary(file_path, content)
        except OSError as exc:
            record.status = "failed"
            record.error_message = f"写入失败：{exc}"
            db.commit()
            job.media_failed += 1
            any_failed = True
            job.log_error(post.tweet_id, f"写入失败：{exc}")
            continue

        record.local_path = file_path
        record.file_size = size
        record.status = "downloaded"
        record.error_message = None
        record.downloaded_at = datetime.utcnow()
        db.flush()

        try:
            library_media = storage.upsert_library_media(
                record, post, folder, file_path, size, job.thumbnail_dir, db
            )
            record.library_media_id = library_media.id
        except Exception as exc:
            # File saved successfully; library entry failed. Treat as transient.
            job.log_error(post.tweet_id, f"入库失败：{exc}")
            any_failed = True

        db.commit()
        job.media_downloaded += 1
        job.current_file = filename
        media_records.append(record)
        time.sleep(MEDIA_DELAY_SEC)

    try:
        storage.write_post_metadata(post_dir, post, media_records)
    except OSError as exc:
        job.log_error(post.tweet_id, f"info.json 写入失败：{exc}")

    if any_failed:
        post.status = "failed"
        post.error_message = "部分媒体下载失败"
        job.failed_posts += 1
    else:
        post.status = "completed"
        post.completed_at = datetime.utcnow()
        post.error_message = None
        job.completed_posts += 1
    db.commit()


def _run(job: ImportJob) -> None:
    db = database.SessionLocal()
    try:
        job.status = "preparing"
        job.message = "准备中"
        job.started_at = datetime.utcnow().isoformat()

        folder = storage.ensure_folder_for_x(job.download_root, db)
        job.total_posts = len(job._post_ids)

        if not job._post_ids:
            job.status = "completed"
            job.message = "没有需要处理的内容"
            job.finished_at = datetime.utcnow().isoformat()
            return

        job.status = "running"
        job.message = f"开始处理 {job.total_posts} 个 Post"

        for post_id in job._post_ids:
            if job.cancel_requested:
                break
            _wait_if_paused(job)
            if job.cancel_requested:
                break
            try:
                _process_post(job, post_id, db, folder)
            except Exception as exc:
                job.failed_posts += 1
                job.log_error(str(post_id), f"未处理异常：{exc}")
                db.rollback()
            time.sleep(TWEET_DELAY_SEC)

        if job.cancel_requested:
            job.status = "canceled"
            job.message = "已取消"
        else:
            job.status = "completed"
            job.message = "导入完成"

        source = db.query(models.XImportSource).filter(models.XImportSource.id == job.source_id).first()
        if source:
            source.last_sync_at = datetime.utcnow()
            db.commit()
    except Exception as exc:
        job.status = "failed"
        job.message = f"任务失败：{exc}"
        job.log_error("", str(exc))
    finally:
        job.finished_at = datetime.utcnow().isoformat()
        db.close()


def start_job(
    *,
    job_id: str,
    source_id: int,
    download_root: str,
    thumbnail_dir: str,
    post_ids: List[int],
    cookie: Optional[str] = None,
) -> ImportJob:
    with JOB_LOCK:
        existing = JOBS.get(job_id)
        if existing and existing.status in {"queued", "preparing", "running", "paused"}:
            return existing
        job = ImportJob(
            job_id=job_id,
            source_id=source_id,
            download_root=download_root,
            thumbnail_dir=thumbnail_dir,
            cookie=cookie,
            _post_ids=list(post_ids),
        )
        JOBS[job_id] = job

    threading.Thread(target=_run, args=(job,), daemon=True, name=f"x-import-{job_id}").start()
    return job


def get_job(job_id: str) -> Optional[ImportJob]:
    return JOBS.get(job_id)


def latest_job_for_source(source_id: int) -> Optional[ImportJob]:
    candidates = [job for job in JOBS.values() if job.source_id == source_id]
    if not candidates:
        return None
    return max(candidates, key=lambda j: j.started_at or "")


def request_pause(job_id: str) -> Optional[ImportJob]:
    job = JOBS.get(job_id)
    if not job or job.status not in {"running", "preparing"}:
        return job
    job.pause_requested = True
    job.message = "正在暂停"
    return job


def request_resume(job_id: str) -> Optional[ImportJob]:
    job = JOBS.get(job_id)
    if not job:
        return None
    job.pause_requested = False
    if job.status == "paused":
        job.message = "继续中"
    return job


def request_cancel(job_id: str) -> Optional[ImportJob]:
    job = JOBS.get(job_id)
    if not job:
        return None
    job.cancel_requested = True
    job.pause_requested = False
    job.message = "正在取消"
    return job
