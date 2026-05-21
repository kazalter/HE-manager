"""Subtitle/lyrics parsing for ASMR works.

asmr.one ships per-track timed text as `.lrc`, `.vtt` or `.srt`. They are all
simple line+timestamp formats, so we normalise them here into one shape the
frontend can scroll without knowing the source format:

    [{"t": <seconds, float>, "text": <str>}, ...]   # sorted by t

Pure functions only (no I/O) so it unit-tests like `asmr_source` — the
endpoint in main.py owns reading the file off disk.
"""
from __future__ import annotations

import re
from typing import List

# LRC line-time tag: [mm:ss], [mm:ss.xx], [mm:ss.xxx] (also tolerates [mm:ss:xx]).
_LRC_TIME = re.compile(r"\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\]")
# LRC id tag: [offset:+250], [ti:..], [ar:..] — only `offset` affects timing.
_LRC_ID = re.compile(r"\[([a-zA-Z]+):([^\]]*)\]")
# Enhanced-LRC inline word stamps <mm:ss.xx> — stripped, we keep line level only.
_LRC_WORD = re.compile(r"<\d+:\d{1,2}(?:[.:]\d{1,3})?>")
# VTT/SRT range line: HH:MM:SS.mmm --> HH:MM:SS.mmm  (hours optional, , or .)
_CUE_RANGE = re.compile(
    r"(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*"
    r"(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})"
)


def _frac_seconds(raw: str | None) -> float:
    """A 1-3 digit fractional field is hundredths or thousandths depending on
    width ("5" -> .5, "50" -> .50, "500" -> .500)."""
    if not raw:
        return 0.0
    return int(raw) / (10 ** len(raw))


def _norm(entries: List[dict]) -> List[dict]:
    """Stable-sort by time and drop entries with no text (keeps a timed blank
    only if it carries text; pure spacers add nothing to a scroller)."""
    cleaned = [e for e in entries if e["text"]]
    cleaned.sort(key=lambda e: e["t"])
    return cleaned


def _parse_lrc(text: str) -> List[dict]:
    offset = 0.0  # seconds; [offset:+ms] shifts lyrics earlier (spec convention)
    out: List[dict] = []
    for raw_line in text.splitlines():
        # Pull the offset id-tag before deciding the line has no time tags.
        if not _LRC_TIME.search(raw_line):
            for key, val in _LRC_ID.findall(raw_line):
                if key.lower() == "offset":
                    try:
                        offset = int(val.strip()) / 1000.0
                    except ValueError:
                        pass
            continue
        stamps = _LRC_TIME.findall(raw_line)
        body = _LRC_WORD.sub("", _LRC_TIME.sub("", raw_line)).strip()
        for minutes, seconds, frac in stamps:
            t = int(minutes) * 60 + int(seconds) + _frac_seconds(frac)
            out.append({"t": max(0.0, t - offset), "text": body})
    return _norm(out)


def _cue_start_seconds(match: re.Match) -> float:
    h, m, s, frac = match.group(1), match.group(2), match.group(3), match.group(4)
    return (int(h or 0) * 3600) + int(m) * 60 + int(s) + _frac_seconds(frac)


def _parse_cues(text: str) -> List[dict]:
    """Shared VTT/SRT walker: a timing line `start --> end` followed by one or
    more text lines until a blank line. Cue numbers / WEBVTT header / NOTE /
    STYLE blocks are ignored. Multi-line cue text joins with a space so it fits
    one scroller row."""
    out: List[dict] = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        m = _CUE_RANGE.search(lines[i])
        if not m:
            i += 1
            continue
        start = _cue_start_seconds(m)
        i += 1
        parts: List[str] = []
        while i < len(lines) and lines[i].strip():
            # A bare integer just before a range is the next cue's number.
            if i + 1 < len(lines) and lines[i].strip().isdigit() and _CUE_RANGE.search(lines[i + 1]):
                break
            parts.append(lines[i].strip())
            i += 1
        out.append({"t": start, "text": " ".join(parts).strip()})
    return _norm(out)


def parse_lyrics(text: str, ext: str) -> List[dict]:
    """Parse subtitle `text` of kind `ext` ('.lrc'/'lrc'/'.vtt'/'.srt') into
    the normalised [{t, text}] list. Unknown/empty -> []."""
    if not text:
        return []
    kind = (ext or "").lower().lstrip(".")
    if kind == "lrc":
        return _parse_lrc(text)
    if kind in ("vtt", "srt"):
        return _parse_cues(text)
    return []
