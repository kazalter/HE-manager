"""asmr.one (DLsite ASMR aggregator) API client.

Parallel to `external_sources.py` (WNACG) but talks a JSON API instead of
scraping HTML. asmr.one's edge domains get blocked and rotate often, so every
call tries the user-configured base first and then falls back across the known
mirrors. Auth is a bearer token obtained from username/password (the user's own
account — required so we can read *their* marked/favourited works).

P1 scope: login + list marked works. The per-work track tree
(`fetch_work_tracks`) needed for P2 downloads is stubbed here with the endpoint
shape documented so P2 can fill it in without re-deriving the API.
"""

from dataclasses import dataclass, field
import json
import re
import socket
import time
from typing import List, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, urlparse
from urllib.request import Request, urlopen


# Known API mirrors. The base the user enters is always tried first; these are
# the fallback order when it is unreachable/blocked. Kept here (not in the DB)
# so a new mirror only needs a code bump, matching how WNACG_BASE_URL lives in
# external_sources.py.
DEFAULT_API_BASE = "https://api.asmr-200.com"
API_MIRRORS = [
    "https://api.asmr-200.com",
    "https://api.asmr.one",
    "https://api.asmr-100.com",
    "https://api.asmr-300.com",
]

# Web page for a work, only used as the human-facing "open original" link.
WEB_WORK_BASE = "https://www.asmr.one"

# Mark buckets asmr.one exposes on the user's review/marks list.
MARK_FILTERS = ("marked", "listening", "listened", "replay", "postponed")

_RETRYABLE_HTTP_STATUS = {429, 500, 502, 503, 504}

# Friendly messages for API-level errors that mirror fallback can't fix.
_API_ERROR_HINTS = {
    "playlist.playlistNotFound": (
        "登录的账号读不到这个播放列表——它对外不可见，"
        "必须用列表归属的本人 asmr.one 账号登录"
    ),
}


class AsmrApiError(RuntimeError):
    """A definitive API/auth error (wrong credentials, list not visible to
    this account, …). Distinct from "all mirrors unreachable" so the caller
    can show the real cause instead of a network message."""

    def __init__(self, status: int, api_code: Optional[str], detail: str):
        self.status = status
        self.api_code = api_code
        super().__init__(detail)


@dataclass
class ParsedAsmrWork:
    external_id: str          # RJ code, e.g. "RJ01234567"
    title: str
    url: str                  # asmr.one work page (display only)
    cover_url: Optional[str] = None
    category_id: Optional[str] = None
    category_name: Optional[str] = None   # circle (社团) name
    va_names: List[str] = field(default_factory=list)  # voice actors (CV)


def normalize_api_base(base: Optional[str]) -> str:
    base = (base or "").strip().rstrip("/")
    if not base:
        return DEFAULT_API_BASE
    if not base.startswith(("http://", "https://")):
        base = "https://" + base
    return base


def parse_mirrors(raw: Optional[str]) -> List[str]:
    """Split a user-entered mirror blob (newline and/or comma separated) into a
    normalised, de-duplicated list. Blank lines/garbage are dropped so the UI
    can be forgiving. Returns [] when nothing usable -> caller uses defaults."""
    if not raw:
        return []
    out: List[str] = []
    for chunk in re.split(r"[\n,]+", raw):
        norm = normalize_api_base(chunk)
        if chunk.strip() and norm not in out:
            out.append(norm)
    return out


def candidate_bases(preferred: Optional[str], mirrors: Optional[List[str]] = None) -> List[str]:
    """Preferred base first, then the fallback pool, de-duplicated. The pool is
    the user-configured `mirrors` when given (non-empty), else the built-in
    API_MIRRORS — so a new asmr.one domain only needs a UI edit, not a code
    bump."""
    pool = mirrors if mirrors else API_MIRRORS
    ordered: List[str] = []
    for base in [normalize_api_base(preferred), *pool]:
        norm = normalize_api_base(base)
        if norm not in ordered:
            ordered.append(norm)
    return ordered


def _request_json(
    method: str,
    base: str,
    path: str,
    token: Optional[str] = None,
    body: Optional[dict] = None,
    timeout: int = 20,
    retries: int = 3,
    backoff: float = 1.5,
):
    """One JSON request to a single base, with backoff retry on transient
    statuses. Network/blocked errors raise so the caller can try the next
    mirror."""
    url = f"{base}{path}"
    headers = {
        "User-Agent": "HE-Manager/1.0 local ASMR sync",
        "Accept": "application/json",
        "Referer": WEB_WORK_BASE + "/",
    }
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    last_exc: Optional[Exception] = None
    for attempt in range(retries + 1):
        request = Request(url, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=timeout) as response:
                raw = response.read()
            return json.loads(raw.decode("utf-8", errors="replace"))
        except HTTPError as exc:
            last_exc = exc
            if exc.code in _RETRYABLE_HTTP_STATUS and attempt < retries:
                time.sleep(min(backoff * (2 ** attempt), 10.0))
                continue
            # Non-retryable: surface the API's own error code/message so the
            # caller doesn't mistake an auth/visibility problem for a dead
            # mirror.
            api_code = None
            try:
                api_code = json.loads(exc.read().decode("utf-8", "replace")).get("error")
            except Exception:  # noqa: BLE001 - body may be HTML/empty
                pass
            detail = _API_ERROR_HINTS.get(api_code) or f"HTTP {exc.code}" + (
                f" ({api_code})" if api_code else ""
            )
            raise AsmrApiError(exc.code, api_code, detail)
        except (URLError, socket.timeout, ConnectionError, json.JSONDecodeError) as exc:
            last_exc = exc
            if attempt == retries:
                raise
        time.sleep(min(backoff * (2 ** attempt), 10.0))
    if last_exc:
        raise last_exc
    raise RuntimeError("unreachable retry state")


def _request_json_any(
    method: str,
    preferred_base: str,
    path: str,
    token: Optional[str] = None,
    body: Optional[dict] = None,
    timeout: int = 20,
    mirrors: Optional[List[str]] = None,
) -> tuple[dict, str]:
    """Try the request across mirrors; return (json, working_base)."""
    errors: List[str] = []
    for base in candidate_bases(preferred_base, mirrors):
        try:
            payload = _request_json(method, base, path, token=token, body=body, timeout=timeout)
            return payload, base
        except AsmrApiError:
            # Definitive API/auth error — same on every mirror, surface now.
            raise
        except Exception as exc:  # noqa: BLE001 - aggregate then report
            errors.append(f"{base} -> {exc}")
    raise RuntimeError("所有 asmr.one 镜像都连不上：" + "; ".join(errors))


def login(
    preferred_base: str,
    name: str,
    password: str,
    mirrors: Optional[List[str]] = None,
) -> tuple[str, str]:
    """Exchange username/password for a bearer token.

    Returns (token, working_base) so callers can pin subsequent requests to the
    mirror that actually answered.
    """
    payload, working_base = _request_json_any(
        "POST",
        preferred_base,
        "/api/auth/me",
        body={"name": name, "password": password},
        mirrors=mirrors,
    )
    token = payload.get("token") or payload.get("accessToken")
    if not token:
        raise RuntimeError("登录未返回 token，请检查账号密码")
    return token, working_base


def _work_url(rj_code: str) -> str:
    return f"{WEB_WORK_BASE}/work/{rj_code}"


def _rj_code(work: dict) -> str:
    """asmr.one exposes the DLsite RJ code as `source_id`; fall back to the
    numeric id so an item is never dropped for lack of an RJ."""
    rj = (work.get("source_id") or "").strip()
    if rj:
        return rj
    numeric = work.get("id")
    return f"RJ{numeric}" if numeric is not None else ""


def _circle_name(work: dict) -> Optional[str]:
    circle = work.get("circle")
    if isinstance(circle, dict):
        name = (circle.get("name") or "").strip()
        if name:
            return name
    name = (work.get("name") or "").strip()
    return name or None


def _va_names(work: dict) -> List[str]:
    """asmr.one exposes voice actors as `vas`: [{"id", "name"}, ...]. Order is
    preserved and duplicates dropped so they map cleanly to artist tags."""
    vas = work.get("vas")
    names: List[str] = []
    if isinstance(vas, list):
        for va in vas:
            if not isinstance(va, dict):
                continue
            name = (va.get("name") or "").strip()
            if name and name not in names:
                names.append(name)
    return names


def parse_marked_works(payload: dict) -> List[ParsedAsmrWork]:
    """Pure parser over one works page (/api/review *or*
    /api/playlist/get-playlist-works — both return the same work shape). Kept
    separate from the network layer so it is unit-testable with a captured
    JSON sample."""
    works = payload.get("works")
    if works is None:
        works = payload.get("data") or []
    items: List[ParsedAsmrWork] = []
    for entry in works:
        if not isinstance(entry, dict):
            continue
        # Playlist entries sometimes wrap the work as {"work": {...}}.
        work = entry["work"] if isinstance(entry.get("work"), dict) else entry
        rj = _rj_code(work)
        if not rj:
            continue
        cover = (
            work.get("mainCoverUrl")
            or work.get("thumbnailCoverUrl")
            or work.get("samCoverUrl")
            or None
        )
        items.append(
            ParsedAsmrWork(
                external_id=rj,
                title=(work.get("title") or rj).strip(),
                url=_work_url(rj),
                cover_url=cover,
                category_id=None,
                category_name=_circle_name(work),
                va_names=_va_names(work),
            )
        )
    return items


def _pagination_total_pages(payload: dict, page_size: int) -> Optional[int]:
    pagination = payload.get("pagination")
    if not isinstance(pagination, dict):
        return None
    total = pagination.get("totalCount")
    size = pagination.get("pageSize") or page_size
    if isinstance(total, int) and isinstance(size, int) and size > 0:
        return max(1, -(-total // size))  # ceil div
    return None


def fetch_marked_works(
    working_base: str,
    token: str,
    mark_filter: str = "marked",
    page_limit: int = 3,
    page_size: int = 96,
    mirrors: Optional[List[str]] = None,
) -> List[ParsedAsmrWork]:
    """List the user's marked works. Stops at `page_limit` pages or when the
    API reports no more pages, whichever comes first."""
    mark_filter = mark_filter if mark_filter in MARK_FILTERS else "marked"
    collected: dict[str, ParsedAsmrWork] = {}
    total_pages: Optional[int] = None

    for page in range(1, page_limit + 1):
        path = (
            f"/api/review?filter={quote(mark_filter)}"
            f"&order=updated_at&sort=desc&page={page}&pageSize={page_size}"
        )
        # The mirror was already pinned by login(); still allow fallback in
        # case it dies mid-sync.
        payload, _ = _request_json_any("GET", working_base, path, token=token, mirrors=mirrors)
        page_items = parse_marked_works(payload)
        if not page_items:
            break
        for item in page_items:
            collected.setdefault(item.external_id, item)

        if total_pages is None:
            total_pages = _pagination_total_pages(payload, page_size)
        if total_pages is not None and page >= total_pages:
            break

    return list(collected.values())


_UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


def extract_playlist_id(url_or_id: str) -> str:
    """Accept either a raw playlist UUID or a share URL like
    `https://asmr.one/playlist?id=<uuid>` and return the UUID."""
    value = (url_or_id or "").strip()
    if not value:
        return ""
    parsed = urlparse(value)
    if parsed.query:
        qs = parse_qs(parsed.query)
        if qs.get("id"):
            value = qs["id"][0]
    match = _UUID_RE.search(value)
    return match.group(0) if match else value


def fetch_playlist_works(
    preferred_base: str,
    token: Optional[str],
    playlist_id: str,
    page_limit: int = 5,
    page_size: int = 24,
    mirrors: Optional[List[str]] = None,
) -> List[ParsedAsmrWork]:
    """List works in a user's playlist. `token` is optional — public playlists
    resolve anonymously; a private one needs the owner's bearer token. The
    mirror is chosen by the same fallback chain as everything else."""
    playlist_id = extract_playlist_id(playlist_id)
    if not playlist_id:
        raise RuntimeError("播放列表地址里没有解析到 id")

    collected: dict[str, ParsedAsmrWork] = {}
    total_pages: Optional[int] = None
    working_base = preferred_base

    for page in range(1, page_limit + 1):
        path = (
            f"/api/playlist/get-playlist-works?id={quote(playlist_id)}"
            f"&page={page}&pageSize={page_size}"
        )
        payload, working_base = _request_json_any("GET", working_base, path, token=token, mirrors=mirrors)
        page_items = parse_marked_works(payload)
        if not page_items:
            break
        for item in page_items:
            collected.setdefault(item.external_id, item)

        if total_pages is None:
            total_pages = _pagination_total_pages(payload, page_size)
        if total_pages is not None and page >= total_pages:
            break

    return list(collected.values())


def fetch_work_tracks(working_base: str, token: str, rj_or_id: str, mirrors: Optional[List[str]] = None):
    """GET /api/tracks/{id} -> nested folder/file tree (a list). Folder nodes
    carry `children`; file nodes (`type` audio/image) carry `mediaDownloadUrl`
    / `size` / `duration` / `title`."""
    numeric = rj_or_id.upper().removeprefix("RJ").lstrip("0") or rj_or_id
    payload, _ = _request_json_any("GET", working_base, f"/api/tracks/{quote(numeric)}", token=token, mirrors=mirrors)
    return payload


@dataclass
class AsmrTrack:
    title: str            # original file name (incl. extension)
    download_url: str
    rel_dir: str          # nested folder path ("" for root), '/'-joined
    kind: str             # "audio" | "image"
    size: Optional[int] = None
    duration: Optional[float] = None


def _safe_segment(name: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name or "").strip().strip(". ")
    return cleaned[:120] or "_"


def flatten_tracks(tree, kinds=("audio",)) -> List[AsmrTrack]:
    """Depth-first flatten of the /api/tracks tree, keeping folder nesting as a
    relative path so a multi-folder work stays organised on disk. Encounter
    order is preserved (it's the work's natural track order)."""
    out: List[AsmrTrack] = []

    def walk(node, rel_dir: str):
        if isinstance(node, list):
            for child in node:
                walk(child, rel_dir)
            return
        if not isinstance(node, dict):
            return
        node_type = node.get("type")
        if node_type == "folder":
            sub = node.get("children") or []
            folder = _safe_segment(str(node.get("title") or ""))
            next_dir = f"{rel_dir}/{folder}" if rel_dir else folder
            walk(sub, next_dir)
            return
        if node_type in kinds:
            url = node.get("mediaDownloadUrl") or node.get("mediaStreamUrl")
            if not url:
                return
            duration = node.get("duration")
            out.append(
                AsmrTrack(
                    title=str(node.get("title") or "track"),
                    download_url=url,
                    rel_dir=rel_dir,
                    kind=node_type,
                    size=node.get("size") if isinstance(node.get("size"), int) else None,
                    duration=float(duration) if isinstance(duration, (int, float)) else None,
                )
            )

    walk(tree, "")
    return out


def _track_ext(track: AsmrTrack) -> str:
    name = track.title or ""
    return name.rsplit(".", 1)[-1].lower() if "." in name else ""


def filter_audio_tracks(tracks: List[AsmrTrack], mode: Optional[str]) -> List[AsmrTrack]:
    """Drop heavyweight formats per the source setting ("all" | "no_wav" |
    "mp3_only"). A single work's WAV can be several GB while the MP3 is plenty
    for listening. Never filters to zero — a wav-only / flac-only work still
    downloads, since something beats nothing."""
    mode = (mode or "all").strip()
    if mode == "no_wav":
        kept = [t for t in tracks if _track_ext(t) != "wav"]
    elif mode == "mp3_only":
        kept = [t for t in tracks if _track_ext(t) == "mp3"]
    else:
        return tracks
    return kept or tracks


# Folder-name tokens that mark the SE / effect / ambient / noise variant.
# asmr.one ships works as parallel folders and circles label them every which
# way: "SE有り"/"SE無し", "効果音あり"/"効果音なし", "ノイズあり"/"ノイズなし",
# "環境音", "背景音", "SE抜き". The [a-z] guards keep the Latin "SE" from
# matching inside words like "noise"/"nose".
#
# The first branch also accepts "SE" glued straight onto a format word with no
# separator — real labels like "含SEMP3" / "無SEWAV" (RJ01109928) — which the
# plain `(?![a-z])` guard would otherwise reject (under IGNORECASE the trailing
# "M"/"W" counts as a word char). Only known audio extensions are whitelisted
# there, so "Settings"/"session" still don't false-match.
_SE_TOKEN_RE = re.compile(
    r"(?<![a-z])se(?=mp3|wav|flac|m4a|aac|ogg|opus|mp4)"
    r"|(?<![a-z])se(?![a-z])"
    r"|効果音|環境音|环境音|ノイズ|noise|背景音",
    re.IGNORECASE,
)
_SE_NEG_RE = re.compile(r"無|无|なし|ナシ|抜き|抜|オフ|(?<![a-z])off(?![a-z])", re.IGNORECASE)


def _rel_dir_se_kind(rel_dir: str) -> str:
    """Classify a track's folder path as the SE/effects variant ("with"), the
    clean variant ("no"), or "neutral" (no SE marker, or contradictory
    segments). A folder named just "SE"/"効果音"/"ノイズ" — token with no
    negation — is the with-SE mix."""
    seen = set()
    for seg in (rel_dir or "").split("/"):
        if not seg or not _SE_TOKEN_RE.search(seg):
            continue
        seen.add("no" if _SE_NEG_RE.search(seg) else "with")
    if seen == {"with"}:
        return "with"
    if seen == {"no"}:
        return "no"
    return "neutral"


def filter_audio_versions(tracks: List[AsmrTrack], mode: Optional[str]) -> List[AsmrTrack]:
    """Pick one of a work's parallel SE / no-SE folder variants ("all" |
    "no_se" | "se_only"). Folder labels are fuzzy so this is best-effort; like
    filter_audio_tracks it never filters to zero, so a work that isn't split
    into SE variants still downloads everything."""
    mode = (mode or "all").strip()
    if mode == "no_se":
        kept = [t for t in tracks if _rel_dir_se_kind(t.rel_dir) != "with"]
    elif mode == "se_only":
        kept = [t for t in tracks if _rel_dir_se_kind(t.rel_dir) == "with"]
    else:
        return tracks
    return kept or tracks


SUBTITLE_EXTS = ("lrc", "vtt", "srt")


@dataclass
class AsmrSubtitle:
    title: str            # original file name (incl. extension)
    download_url: str
    rel_dir: str          # same nesting scheme as AsmrTrack.rel_dir
    ext: str              # lowercased, no dot: "lrc" | "vtt" | "srt"
    stem: str             # filename without extension, for audio pairing


def collect_subtitle_nodes(tree) -> List[AsmrSubtitle]:
    """Same depth-first walk as flatten_tracks but for timed-text files.

    asmr.one tags these `type: "text"`, but matching on the filename
    extension is more robust across mirrors that vary the type label — any
    downloadable node whose name ends in .lrc/.vtt/.srt counts."""
    out: List[AsmrSubtitle] = []

    def walk(node, rel_dir: str):
        if isinstance(node, list):
            for child in node:
                walk(child, rel_dir)
            return
        if not isinstance(node, dict):
            return
        if node.get("type") == "folder":
            folder = _safe_segment(str(node.get("title") or ""))
            next_dir = f"{rel_dir}/{folder}" if rel_dir else folder
            walk(node.get("children") or [], next_dir)
            return
        title = str(node.get("title") or "")
        stem, dot_ext = (title.rsplit(".", 1) + [""])[:2] if "." in title else (title, "")
        ext = dot_ext.lower()
        if ext not in SUBTITLE_EXTS:
            return
        url = node.get("mediaDownloadUrl") or node.get("mediaStreamUrl")
        if not url:
            return
        out.append(AsmrSubtitle(title=title, download_url=url, rel_dir=rel_dir, ext=ext, stem=stem))

    walk(tree, "")
    return out


def fetch_file(url: str, timeout: int = 90, retries: int = 3, backoff: float = 1.5) -> tuple[bytes, str]:
    """Download one media file. The CDN URLs are pre-authorised, so no bearer
    token is sent (passing one can break a signed URL). Retries transient 5xx
    / network blips like the JSON path does."""
    headers = {
        "User-Agent": "HE-Manager/1.0 local ASMR sync",
        "Accept": "*/*",
        "Referer": WEB_WORK_BASE + "/",
    }
    last_exc: Optional[Exception] = None
    for attempt in range(retries + 1):
        try:
            with urlopen(Request(url, headers=headers), timeout=timeout) as response:
                return response.read(), response.headers.get("Content-Type", "application/octet-stream")
        except HTTPError as exc:
            last_exc = exc
            if exc.code not in _RETRYABLE_HTTP_STATUS or attempt == retries:
                raise
        except (URLError, socket.timeout, ConnectionError) as exc:
            last_exc = exc
            if attempt == retries:
                raise
        time.sleep(min(backoff * (2 ** attempt), 10.0))
    if last_exc:
        raise last_exc
    raise RuntimeError("unreachable retry state")


def ping_mirror(base: str, timeout: int = 6) -> dict:
    """Reachability probe for the mirror picker: hit `/api/` once. Any HTTP
    answer (even 4xx) proves the domain resolves and isn't blocked; only
    network/TLS/timeout errors count as unreachable. Returns
    {base, ok, latency_ms, error}."""
    norm = normalize_api_base(base)
    request = Request(
        f"{norm}/api/",
        headers={"User-Agent": "HE-Manager/1.0 mirror ping", "Accept": "*/*"},
        method="GET",
    )
    start = time.monotonic()
    try:
        with urlopen(request, timeout=timeout):
            pass
    except HTTPError:
        pass  # 404/403/etc. still means the mirror answered -> reachable
    except (URLError, socket.timeout, ConnectionError, OSError) as exc:
        return {"base": norm, "ok": False, "latency_ms": None, "error": str(exc)}
    return {"base": norm, "ok": True, "latency_ms": int((time.monotonic() - start) * 1000), "error": None}
