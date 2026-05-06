"""Filesystem layout + Media library entry creation for X imports.

Layout:
    <download_root>/
        x/
            <author_screen_name>/
                <tweet_id>/
                    1.jpg
                    2.mp4
                    info.json   (raw post + media metadata)

The storage root folder (.../x/) is registered as an `image` Folder so the existing media
library can index it; each downloaded image / video gets a `Media` row whose
`source_url` points back to the original tweet.
"""
from __future__ import annotations

import hashlib
import json
import mimetypes
import os
import re
from datetime import datetime
from typing import Optional, Tuple
from urllib.parse import urlparse

from sqlalchemy.orm import Session

from .. import models, scanner


THUMBNAIL_DIR_NAME = ".thumbnails"


def normalize_root(path: Optional[str]) -> str:
    raw = (path or "").strip()
    if not raw:
        raise ValueError("download root path is empty")
    return os.path.abspath(os.path.expanduser(raw))


def x_root_dir(download_root: str) -> str:
    return os.path.join(normalize_root(download_root), "x")


def post_dir_for(download_root: str, screen_name: Optional[str], tweet_id: str) -> str:
    safe_user = _safe_filename(screen_name or "unknown", "unknown")
    return os.path.join(x_root_dir(download_root), safe_user, _safe_filename(tweet_id, "post"))


def _safe_filename(value: str, fallback: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value or "").strip()
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    return (cleaned or fallback)[:120]


def media_extension_from(url: str, content_type: str, kind: str) -> str:
    parsed_ext = os.path.splitext(urlparse(url).path)[1].lower()
    if parsed_ext in {".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp4", ".mov", ".webm"}:
        return parsed_ext
    guessed = mimetypes.guess_extension((content_type or "").split(";", 1)[0].strip())
    if guessed:
        return ".jpg" if guessed == ".jpe" else guessed
    if kind in {"video", "animated_gif"}:
        return ".mp4"
    return ".jpg"


def ensure_folder_for_x(download_root: str, db: Session) -> models.Folder:
    """Make sure the x_root_dir is registered as a Folder so media library can show entries."""
    root = x_root_dir(download_root)
    os.makedirs(root, exist_ok=True)
    folder = db.query(models.Folder).filter(models.Folder.path == root).first()
    if folder:
        # Don't override scan_mode — image is fine; users may have re-pointed it manually.
        return folder
    folder = models.Folder(
        path=root,
        scan_mode="image",
        status="idle",
        thumbnail_enabled=True,
        thumbnail_interval=1,
    )
    db.add(folder)
    db.commit()
    db.refresh(folder)
    return folder


def write_post_metadata(post_dir: str, post: "models.XPost", media_records: list) -> None:
    info = {
        "tweet_id": post.tweet_id,
        "url": post.url,
        "author_screen_name": post.author_screen_name,
        "author_name": post.author_name,
        "posted_at": post.posted_at.isoformat() if post.posted_at else None,
        "full_text": post.full_text,
        "media_count": post.media_count,
        "media": [
            {
                "media_index": item.media_index,
                "media_type": item.media_type,
                "remote_url": item.remote_url,
                "local_path": item.local_path,
            }
            for item in media_records
        ],
    }
    with open(os.path.join(post_dir, "info.json"), "w", encoding="utf-8") as fp:
        json.dump(info, fp, ensure_ascii=False, indent=2)


def upsert_library_media(
    media_record: "models.XMediaItem",
    post: "models.XPost",
    folder: models.Folder,
    file_path: str,
    file_size: int,
    thumbnail_dir: str,
    db: Session,
) -> models.Media:
    """Create or update a `Media` row backed by the downloaded file."""
    media_type_for_library = "video" if media_record.media_type in {"video", "animated_gif"} else "image"
    rel_path = os.path.relpath(file_path, folder.path)
    extension = os.path.splitext(file_path)[1].lower() or ".bin"

    title_parts = [post.author_screen_name or "x", post.tweet_id]
    if post.media_count and post.media_count > 1:
        title_parts.append(f"{media_record.media_index + 1}")
    title = " · ".join(part for part in title_parts if part)

    media = (
        db.query(models.Media)
        .filter(models.Media.absolute_path == file_path)
        .first()
    )
    if media:
        media.folder_id = folder.id
        media.title = title
        media.relative_path = rel_path
        media.media_type = media_type_for_library
        media.extension = extension
        media.file_size = file_size
        media.source_url = post.url
        media.source_site = "x"
        media.is_missing = False
    else:
        media = models.Media(
            folder_id=folder.id,
            title=title,
            relative_path=rel_path,
            absolute_path=file_path,
            media_type=media_type_for_library,
            extension=extension,
            file_size=file_size,
            source_url=post.url,
            source_site="x",
            is_missing=False,
        )
        db.add(media)
        db.flush()

    # Thumbnail (best-effort; image files use themselves, videos use scanner's frame finder).
    if not media.cover_path:
        thumb_hash = hashlib.md5(file_path.encode("utf-8")).hexdigest()[:12]
        thumb_name = f"thumb_x_{thumb_hash}_{int(datetime.utcnow().timestamp())}.jpg"
        thumb_path = os.path.join(thumbnail_dir, thumb_name)
        if media_type_for_library == "video":
            success, t_ms, source = scanner.get_video_thumbnail(file_path, thumb_path)
            if success:
                media.cover_path = thumb_name
                media.cover_time_ms = t_ms
                media.cover_source = source
        else:
            try:
                from PIL import Image
                with Image.open(file_path) as im:
                    im.thumbnail((640, 640))
                    rgb = im.convert("RGB")
                    rgb.save(thumb_path, format="JPEG", quality=82)
                media.cover_path = thumb_name
                media.cover_source = "image_first_frame"
            except Exception:
                pass

    db.flush()
    return media


def write_binary(path: str, content: bytes) -> Tuple[str, int]:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fp:
        fp.write(content)
    return path, len(content)
