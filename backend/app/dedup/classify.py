"""Classify a candidate pair into strong / suspected / weak / unique.

The decision is based on sampled hashes + size/duration/dimension comparisons. We never
compute a full-file hash for large media — strong duplicates land here when *all three*
sampled positions match and the high-level metadata (page count / duration / dimensions)
also lines up.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple


# Public level names — kept stable since they appear in the DB.
LEVEL_UNIQUE = "unique"
LEVEL_STRONG = "strong_duplicate"
LEVEL_SUSPECTED = "suspected_duplicate"
LEVEL_WEAK = "weak_suspected"


@dataclass
class FingerprintLite:
    """Minimal projection of a fingerprint, usable for both Media and FingerprintResult."""
    media_type: str
    file_size: Optional[int]
    page_count: Optional[int]
    duration: Optional[int]
    width: Optional[int]
    height: Optional[int]
    hash_first: Optional[str]
    hash_middle: Optional[str]
    hash_last: Optional[str]


def _hash_match(a: Optional[str], b: Optional[str]) -> bool:
    return bool(a) and bool(b) and a == b


def _hashes_overlap(a: FingerprintLite, b: FingerprintLite) -> int:
    """Count of matching sampled hashes (0..3)."""
    return sum(
        1 for pair in (
            (a.hash_first, b.hash_first),
            (a.hash_middle, b.hash_middle),
            (a.hash_last, b.hash_last),
        ) if _hash_match(*pair)
    )


def _within_pct(a: Optional[int], b: Optional[int], pct: float) -> bool:
    if a is None or b is None or a <= 0 or b <= 0:
        return False
    larger = max(a, b)
    return abs(a - b) / larger <= pct


def _within_seconds(a: Optional[int], b: Optional[int], seconds: int) -> bool:
    if a is None or b is None:
        return False
    return abs(a - b) <= seconds


def classify_manga(existing: FingerprintLite, candidate: FingerprintLite) -> Tuple[str, int, List[str]]:
    reasons: List[str] = []
    matches = _hashes_overlap(existing, candidate)
    pages_match = existing.page_count and candidate.page_count and existing.page_count == candidate.page_count
    size_match = _within_pct(existing.file_size, candidate.file_size, 0.05)

    if pages_match:
        reasons.append(f"页数一致 ({existing.page_count})")
    if size_match:
        reasons.append("文件大小接近")
    if matches:
        reasons.append(f"{matches}/3 个抽样页 hash 匹配")

    if pages_match and matches >= 3:
        return LEVEL_STRONG, 95, reasons
    if matches >= 2 and (pages_match or size_match):
        return LEVEL_SUSPECTED, 75, reasons
    if pages_match and size_match:
        return LEVEL_SUSPECTED, 65, reasons
    if matches >= 1 or pages_match:
        return LEVEL_WEAK, 45, reasons
    return LEVEL_UNIQUE, 0, reasons


def classify_image(existing: FingerprintLite, candidate: FingerprintLite) -> Tuple[str, int, List[str]]:
    reasons: List[str] = []
    hash_match = _hash_match(existing.hash_first, candidate.hash_first)
    same_size = existing.file_size and candidate.file_size and existing.file_size == candidate.file_size
    same_dim = (
        existing.width and candidate.width and existing.height and candidate.height
        and existing.width == candidate.width and existing.height == candidate.height
    )

    if hash_match:
        reasons.append("文件 hash 一致")
    if same_size:
        reasons.append("文件大小一致")
    if same_dim:
        reasons.append(f"分辨率一致 ({existing.width}x{existing.height})")

    if hash_match:
        return LEVEL_STRONG, 99, reasons
    if same_size and same_dim:
        return LEVEL_SUSPECTED, 70, reasons
    if same_dim:
        return LEVEL_WEAK, 40, reasons
    return LEVEL_UNIQUE, 0, reasons


def classify_video(existing: FingerprintLite, candidate: FingerprintLite) -> Tuple[str, int, List[str]]:
    reasons: List[str] = []
    matches = _hashes_overlap(existing, candidate)
    duration_match = _within_seconds(existing.duration, candidate.duration, 1)
    duration_close = _within_seconds(existing.duration, candidate.duration, 5)
    same_dim = (
        existing.width and candidate.width and existing.height and candidate.height
        and existing.width == candidate.width and existing.height == candidate.height
    )
    size_close = _within_pct(existing.file_size, candidate.file_size, 0.05)

    if duration_match:
        reasons.append(f"时长一致 ({existing.duration}s)")
    elif duration_close:
        reasons.append("时长接近")
    if same_dim:
        reasons.append(f"分辨率一致 ({existing.width}x{existing.height})")
    if matches:
        reasons.append(f"{matches}/3 个抽样帧 hash 匹配")
    if size_close:
        reasons.append("文件大小接近")

    if matches >= 2 and duration_match and same_dim:
        return LEVEL_STRONG, 95, reasons
    if matches >= 2 and (duration_close or same_dim):
        return LEVEL_SUSPECTED, 75, reasons
    if matches >= 1 and duration_close and same_dim:
        return LEVEL_SUSPECTED, 65, reasons
    if matches >= 1 or (duration_close and same_dim):
        return LEVEL_WEAK, 45, reasons
    return LEVEL_UNIQUE, 0, reasons


def classify(existing: FingerprintLite, candidate: FingerprintLite) -> Tuple[str, int, List[str]]:
    """Returns (level, similarity_0_100, reason_list)."""
    if existing.media_type != candidate.media_type:
        return LEVEL_UNIQUE, 0, []
    if existing.media_type == "manga":
        return classify_manga(existing, candidate)
    if existing.media_type == "image":
        return classify_image(existing, candidate)
    if existing.media_type == "video":
        return classify_video(existing, candidate)
    return LEVEL_UNIQUE, 0, []
