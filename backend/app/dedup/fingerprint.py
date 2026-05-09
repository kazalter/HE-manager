"""Per-type sampled fingerprints.

We never hash the whole content of a large file. For manga / image-set we sample three
pages; for video we sample three frames; for single images the file is small enough that
a full SHA-1 is fine.

All hashes are truncated to 16 hex characters (8 bytes) — collision risk negligible at
home-library scale, half the storage of a full SHA-1.
"""
from __future__ import annotations

import hashlib
import os
import zipfile
from dataclasses import dataclass
from typing import List, Optional

import cv2

from .. import models


HASH_LENGTH = 16  # truncated SHA-1 hex chars
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif"}


@dataclass
class FingerprintResult:
    media_type: str
    file_size: Optional[int] = None
    page_count: Optional[int] = None
    duration: Optional[int] = None
    width: Optional[int] = None
    height: Optional[int] = None
    hash_first: Optional[str] = None
    hash_middle: Optional[str] = None
    hash_last: Optional[str] = None
    source_path: Optional[str] = None
    source_mtime: Optional[int] = None


def _short_hash(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()[:HASH_LENGTH]


def _safe_size(path: str) -> Optional[int]:
    try:
        return os.path.getsize(path)
    except OSError:
        return None


def _safe_mtime(path: str) -> Optional[int]:
    try:
        return int(os.path.getmtime(path))
    except OSError:
        return None


def _is_image(name: str) -> bool:
    return os.path.splitext(name)[1].lower() in IMAGE_EXTS


def _pick_three(values: List) -> List:
    n = len(values)
    if n == 0:
        return []
    if n == 1:
        return [values[0], None, None]
    if n == 2:
        return [values[0], None, values[-1]]
    return [values[0], values[n // 2], values[-1]]


def fingerprint_image(path: str) -> FingerprintResult:
    size = _safe_size(path)
    width = height = None
    try:
        img = cv2.imread(path)
        if img is not None:
            height, width = img.shape[:2]
    except Exception:
        pass

    file_hash: Optional[str] = None
    try:
        with open(path, "rb") as fp:
            file_hash = _short_hash(fp.read())
    except OSError:
        pass

    return FingerprintResult(
        media_type="image",
        file_size=size,
        width=width,
        height=height,
        hash_first=file_hash,
        source_path=path,
        source_mtime=_safe_mtime(path),
    )


def _hash_zip_member(zf: zipfile.ZipFile, name: str) -> Optional[str]:
    try:
        with zf.open(name) as fp:
            return _short_hash(fp.read())
    except (KeyError, zipfile.BadZipFile, OSError):
        return None


def fingerprint_manga_zip(path: str) -> FingerprintResult:
    size = _safe_size(path)
    page_count: Optional[int] = None
    h_first = h_middle = h_last = None
    try:
        with zipfile.ZipFile(path, "r") as zf:
            images = sorted(name for name in zf.namelist() if _is_image(name))
            page_count = len(images)
            picks = _pick_three(images)
            if picks:
                h_first = _hash_zip_member(zf, picks[0]) if picks[0] else None
                h_middle = _hash_zip_member(zf, picks[1]) if picks[1] else None
                h_last = _hash_zip_member(zf, picks[2]) if picks[2] else None
    except (zipfile.BadZipFile, OSError):
        pass

    return FingerprintResult(
        media_type="manga",
        file_size=size,
        page_count=page_count,
        hash_first=h_first,
        hash_middle=h_middle,
        hash_last=h_last,
        source_path=path,
        source_mtime=_safe_mtime(path),
    )


def fingerprint_manga_dir(path: str) -> FingerprintResult:
    images: List[str] = []
    for root, _, files in os.walk(path):
        for name in files:
            if _is_image(name):
                images.append(os.path.join(root, name))
    images.sort()
    page_count = len(images)

    h_first = h_middle = h_last = None
    picks = _pick_three(images)
    if picks:
        for idx, file_path in enumerate(picks):
            if not file_path:
                continue
            try:
                with open(file_path, "rb") as fp:
                    digest = _short_hash(fp.read())
            except OSError:
                continue
            if idx == 0:
                h_first = digest
            elif idx == 1:
                h_middle = digest
            elif idx == 2:
                h_last = digest

    return FingerprintResult(
        media_type="manga",
        file_size=_safe_size(path),
        page_count=page_count,
        hash_first=h_first,
        hash_middle=h_middle,
        hash_last=h_last,
        source_path=path,
        source_mtime=_safe_mtime(path),
    )


def _hash_video_frame(cap: "cv2.VideoCapture", t_ms: float) -> Optional[str]:
    try:
        cap.set(cv2.CAP_PROP_POS_MSEC, max(0.0, t_ms))
        success, frame = cap.read()
        if not success or frame is None:
            return None
        # Downscale for stability — same content shouldn't differ on minor codec quirks.
        h, w = frame.shape[:2]
        if w > 256:
            scale = 256 / w
            frame = cv2.resize(frame, (256, int(h * scale)))
        ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 70])
        if not ok:
            return None
        return _short_hash(buf.tobytes())
    except Exception:
        return None


def fingerprint_video(path: str) -> FingerprintResult:
    size = _safe_size(path)
    duration = width = height = None
    h_first = h_middle = h_last = None

    try:
        cap = cv2.VideoCapture(path)
        if cap.isOpened():
            fps = cap.get(cv2.CAP_PROP_FPS)
            total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
            w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            if w > 0:
                width = w
            if h > 0:
                height = h
            duration_ms = 0.0
            if fps and fps > 0 and total_frames > 0:
                duration_ms = (total_frames / fps) * 1000.0
                duration = int(duration_ms / 1000)

            sample_points = []
            if duration_ms > 0:
                sample_points = [duration_ms * 0.05, duration_ms * 0.5, max(0.0, duration_ms - 1000)]
            else:
                sample_points = [0.0, 1000.0, 5000.0]

            h_first = _hash_video_frame(cap, sample_points[0])
            h_middle = _hash_video_frame(cap, sample_points[1])
            h_last = _hash_video_frame(cap, sample_points[2])
        cap.release()
    except Exception:
        pass

    return FingerprintResult(
        media_type="video",
        file_size=size,
        duration=duration,
        width=width,
        height=height,
        hash_first=h_first,
        hash_middle=h_middle,
        hash_last=h_last,
        source_path=path,
        source_mtime=_safe_mtime(path),
    )


def fingerprint_for_media(media: "models.Media") -> Optional[FingerprintResult]:
    path = media.absolute_path
    if not path or not os.path.exists(path):
        return None
    if media.media_type == "image":
        return fingerprint_image(path)
    if media.media_type == "video":
        return fingerprint_video(path)
    if media.media_type == "manga":
        if media.extension == ".dir":
            return fingerprint_manga_dir(path)
        return fingerprint_manga_zip(path)
    return None


def fingerprint_cache_is_fresh(record: "models.MediaFingerprint", media: "models.Media") -> bool:
    """True if the stored fingerprint can be reused without recomputation."""
    if not record:
        return False
    if record.source_path != media.absolute_path:
        return False
    current_size = _safe_size(media.absolute_path)
    current_mtime = _safe_mtime(media.absolute_path)
    if current_size is None or current_mtime is None:
        return False
    if record.file_size != current_size:
        return False
    if record.source_mtime != current_mtime:
        return False
    return True
