from datetime import datetime
import glob
import hashlib
import json
import mimetypes
import os
import re
import shutil
import time
import uuid
import zipfile
from typing import List, Optional
from urllib.parse import urlencode, urljoin, urlparse

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException, Response, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import inspect, text
from sqlalchemy.orm import Session

from . import (
    asmr_source,
    auth,
    ai_config,
    creators as creators_mod,
    database,
    downloader_push,
    external_sources,
    media_cleanup,
    manga_metadata,
    manga_profiles,
    models,
    recommendations,
    schemas,
    scanner,
    stats as stats_mod,
)
from .database import engine, get_db
from .dedup import merge as dedup_merge
from .dedup import worker as dedup_worker
from .x_import import archive as x_archive
from .x_import import importer as x_importer
from .x_import import storage as x_storage
from .x_import import sync as x_sync


models.Base.metadata.create_all(bind=engine)


def get_ranged_file_response(request: Request, file_path: str):
    file_size = os.stat(file_path).st_size
    range_header = request.headers.get("range")
    media_type = mimetypes.guess_type(file_path)[0] or "application/octet-stream"
    
    headers = {
        "Accept-Ranges": "bytes",
        "Content-Length": str(file_size),
        "Content-Type": media_type,
    }

    if not range_header:
        def file_iterator():
            with open(file_path, "rb") as f:
                while chunk := f.read(1024 * 1024):
                    yield chunk
        return StreamingResponse(file_iterator(), headers=headers, media_type=media_type)
    
    try:
        range_match = re.match(r"bytes=(\d+)-(\d*)", range_header)
        start = int(range_match.group(1))
        end = range_match.group(2)
        end = int(end) if end else file_size - 1
    except Exception:
        start = 0
        end = file_size - 1

    start = max(0, start)
    end = min(file_size - 1, end)
    content_length = end - start + 1

    headers["Content-Length"] = str(content_length)
    headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"

    def ranged_file_iterator():
        with open(file_path, "rb") as f:
            f.seek(start)
            bytes_to_read = content_length
            chunk_size = 1024 * 1024  # 1MB chunk
            while bytes_to_read > 0:
                chunk = f.read(min(chunk_size, bytes_to_read))
                if not chunk:
                    break
                bytes_to_read -= len(chunk)
                yield chunk

    return StreamingResponse(
        ranged_file_iterator(),
        status_code=206,
        headers=headers,
        media_type=media_type
    )


# ============================================================================
# Audio (ASMR) — track scan + lyrics parsing
# ============================================================================
# An "audio" Media row points at a folder of audio files (the work) instead of
# a single file like video/manga do. /audio/{id}/tracks lists the files in
# deterministic 1-based order; /audio/{id}/track/{i} streams one of them;
# /audio/{id}/track/{i}/lyrics returns the timed lines from a sidecar LRC/VTT/
# SRT next to it. The Android client (AudioRepository.kt) is the consumer.

AUDIO_TRACK_EXTS = {".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".opus"}
LYRIC_EXTS = (".lrc", ".vtt", ".srt")


# ============================================================================
# Brown Dust 2 Spine preview endpoints
# ============================================================================
# The BD2 importer stores the asset checkout as Folder.scan_mode='bd2_asset'.
# These endpoints expose a small, path-guarded Spine asset browser for the web
# prototype. BD2 uses Spine (.skel/.atlas/.png), not Live2D Cubism.

BD2_CHARINFO_FILENAME = "CharInfo(Dropped).json"


def _bd2_asset_root(db: Session) -> str:
    configured = (os.getenv("HE_BD2_ASSET_ROOT") or "").strip()
    candidates = []
    if configured:
        candidates.append(configured)

    folders = (
        db.query(models.Folder)
        .filter(models.Folder.scan_mode == "bd2_asset")
        .order_by(models.Folder.id.desc())
        .all()
    )
    candidates.extend(folder.path for folder in folders if folder.path)

    media_rows = (
        db.query(models.Media.absolute_path)
        .filter(models.Media.source_site == "bd2")
        .order_by(models.Media.id.desc())
        .limit(20)
        .all()
    )
    for (path,) in media_rows:
        current = os.path.abspath(path or "")
        for _ in range(8):
            if not current:
                break
            candidates.append(current)
            parent = os.path.dirname(current)
            if parent == current:
                break
            current = parent

    for raw in candidates:
        root = os.path.abspath(raw)
        if os.path.isdir(root) and (
            os.path.isdir(os.path.join(root, "spine"))
            or os.path.exists(os.path.join(root, BD2_CHARINFO_FILENAME))
        ):
            return root
    raise HTTPException(status_code=404, detail="BD2 asset root not found")


def _bd2_char_info(root: str) -> dict[str, dict[str, str]]:
    path = os.path.join(root, BD2_CHARINFO_FILENAME)
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8-sig", errors="replace") as file:
            text = file.read()
        # Upstream currently has one malformed object missing a comma before
        # "cutscene"; repair that known typo so names still resolve.
        text = re.sub(
            r'("censored_spine"\s*:\s*"[^"]+")\s*("cutscene"\s*:)',
            r"\1,\n        \2",
            text,
        )
        rows = json.loads(text)
    except Exception:
        return {}

    out: dict[str, dict[str, str]] = {}
    for char in rows if isinstance(rows, list) else []:
        char_name = str(char.get("charName") or "").strip()
        for costume in char.get("costumes") or []:
            costume_id = str(costume.get("costumeId") or "").strip()
            if not costume_id:
                continue
            out[costume_id] = {
                "char_name": char_name,
                "costume_name": str(costume.get("costumeName") or "").strip(),
                "release_date": str(costume.get("releaseDate") or "").strip(),
            }
    return out


def _bd2_spine_title(asset_id: str, char_info: dict[str, dict[str, str]]) -> str:
    match = re.match(r"char(\d{6})(?:_c)?$", asset_id)
    meta = char_info.get(match.group(1) if match else "")
    if not meta:
        return asset_id
    title = f"{meta.get('char_name') or 'Unknown'} - {meta.get('costume_name') or asset_id}"
    if asset_id.endswith("_c"):
        title += " (censored)"
    return title


def _bd2_spine_dir(root: str, asset_id: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_]+", asset_id or ""):
        raise HTTPException(status_code=400, detail="Invalid Spine asset id")
    base = os.path.realpath(os.path.join(root, "spine", "char"))
    target = os.path.realpath(os.path.join(base, asset_id))
    if not (target == base or target.startswith(base + os.sep)):
        raise HTTPException(status_code=400, detail="Invalid Spine asset path")
    if not os.path.isdir(target):
        raise HTTPException(status_code=404, detail="Spine asset not found")
    return target


def scan_audio_tracks(item_dir: str) -> List[dict]:
    """Walk an ASMR work folder and return tracks in stable display order.

    Order is folder-then-filename across the whole tree (`os.walk` ordered by
    `sorted()`), so re-runs produce identical indices. Each entry carries the
    absolute path (for streaming) and a sibling lyric path if a same-stem
    .lrc/.vtt/.srt exists next to the audio file."""
    if not item_dir or not os.path.isdir(item_dir):
        return []

    tracks: List[dict] = []
    for root, dirs, files in os.walk(item_dir):
        dirs.sort()
        for fname in sorted(files):
            ext = os.path.splitext(fname)[1].lower()
            if ext not in AUDIO_TRACK_EXTS:
                continue
            abs_path = os.path.join(root, fname)
            rel_path = os.path.relpath(abs_path, item_dir).replace(os.sep, "/")
            stem = os.path.splitext(fname)[0]
            lyrics_abs = None
            for lyric_ext in LYRIC_EXTS:
                candidate = os.path.join(root, stem + lyric_ext)
                if os.path.exists(candidate):
                    lyrics_abs = candidate
                    break
            tracks.append({
                "title": fname,
                "rel": rel_path,
                "abs_path": abs_path,
                "lyrics_abs": lyrics_abs,
            })

    for index, track in enumerate(tracks, start=1):
        track["index"] = index
    return tracks


_LRC_LINE_RE = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\](.*)")
_VTT_SRT_TIME_RE = re.compile(r"(\d+):(\d+):(\d+(?:[.,]\d+)?)")


def _hms_to_seconds(h: str, m: str, s: str) -> float:
    return int(h) * 3600 + int(m) * 60 + float(s.replace(",", "."))


def _parse_lrc(text: str) -> List[dict]:
    """LRC: one or more `[mm:ss.xx]` tags followed by lyric text per line.
    A single line can carry multiple timestamps (repeats); we expand each."""
    lines: List[dict] = []
    for raw in text.splitlines():
        # Strip metadata tags like [ti:...], [ar:...] — they have non-numeric
        # first chars, so the regex naturally skips them.
        stamps = []
        rest = raw
        while True:
            m = _LRC_LINE_RE.match(rest)
            if not m:
                break
            stamps.append(int(m.group(1)) * 60 + float(m.group(2)))
            rest = m.group(3)
            # multiple stamps may be back-to-back: "[00:01.20][00:05.40]text"
            if not rest.startswith("["):
                break
        if not stamps:
            continue
        body = rest.strip()
        if not body:
            continue
        for t in stamps:
            lines.append({"t": t, "text": body})
    return lines


def _parse_vtt_or_srt(text: str) -> List[dict]:
    """VTT and SRT have similar cue blocks: a time line `HH:MM:SS[.,]xxx -->
    HH:MM:SS[.,]xxx` followed by 1+ text lines, blocks separated by blank
    lines. We only need the start time + concatenated text."""
    lines: List[dict] = []
    blocks = re.split(r"\r?\n\s*\r?\n", text.strip())
    for block in blocks:
        block_lines = [ln for ln in block.splitlines() if ln.strip()]
        time_line = None
        body_lines: List[str] = []
        for ln in block_lines:
            if "-->" in ln and time_line is None:
                time_line = ln
            elif time_line is not None:
                body_lines.append(ln)
        if time_line is None or not body_lines:
            continue
        m = _VTT_SRT_TIME_RE.search(time_line)
        if not m:
            continue
        start = _hms_to_seconds(m.group(1), m.group(2), m.group(3))
        body = " ".join(body_lines).strip()
        if body:
            lines.append({"t": start, "text": body})
    return lines


def parse_lyrics_file(path: str) -> List[dict]:
    """Normalise an LRC/VTT/SRT file to [{t: seconds, text: str}], sorted by
    start time. Returns [] on any parse / encoding failure — the endpoint is
    meant to be a guaranteed 200 with empty lines for tracks that don't have
    real lyrics."""
    if not path or not os.path.exists(path):
        return []
    ext = os.path.splitext(path)[1].lower()
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return []
    # Some LRCs ship as UTF-8 with BOM; the open() above already handles it.
    if ext == ".lrc":
        lines = _parse_lrc(text)
    elif ext in (".vtt", ".srt"):
        lines = _parse_vtt_or_srt(text)
    else:
        lines = []
    lines.sort(key=lambda item: item["t"])
    return lines


def ensure_folder_option_columns():
    inspector = inspect(engine)
    columns = {column["name"] for column in inspector.get_columns("folders")}

    with engine.begin() as conn:
        if "thumbnail_enabled" not in columns:
            conn.execute(text("ALTER TABLE folders ADD COLUMN thumbnail_enabled BOOLEAN NOT NULL DEFAULT 1"))
        if "thumbnail_interval" not in columns:
            conn.execute(text("ALTER TABLE folders ADD COLUMN thumbnail_interval INTEGER NOT NULL DEFAULT 1"))


def ensure_media_option_columns():
    inspector = inspect(engine)
    columns = {column["name"] for column in inspector.get_columns("media")}
    definitions = {
        "duration": "INTEGER",
        "width": "INTEGER",
        "height": "INTEGER",
        "page_count": "INTEGER",
        "rating": "INTEGER NOT NULL DEFAULT 0",
        "favorite": "BOOLEAN NOT NULL DEFAULT 0",
        "view_status": "VARCHAR NOT NULL DEFAULT 'unviewed'",
        "progress": "INTEGER NOT NULL DEFAULT 0",
        "last_opened_at": "DATETIME",
        "source_url": "VARCHAR",
        "source_site": "VARCHAR",
        "is_missing": "BOOLEAN NOT NULL DEFAULT 0",
        "missing_since": "DATETIME",
        # Manga artist parsed from doujin-style title (see manga_artist.py +
        # creators._artist_creators). Backfill is opportunistic — happens
        # next time the row is rescanned or imported; old rows stay NULL
        # until then, which creators.py treats as "no artist".
        "artist": "VARCHAR",
    }

    with engine.begin() as conn:
        for name, definition in definitions.items():
            if name not in columns:
                conn.execute(text(f"ALTER TABLE media ADD COLUMN {name} {definition}"))


ensure_folder_option_columns()
ensure_media_option_columns()


def ensure_external_source_columns():
    inspector = inspect(engine)
    if not inspector.has_table("external_favorite_sources"):
        return
    columns = {column["name"] for column in inspector.get_columns("external_favorite_sources")}

    # ASMR-only columns; NULL for legacy wnacg sources. See models.py for the
    # rationale (polymorphic single table over a sibling table).
    asmr_columns = {
        "api_mirrors": "TEXT",
        "audio_format_filter": "VARCHAR",
        "audio_version_filter": "VARCHAR",
        "username": "VARCHAR",
        "playlist_url": "VARCHAR",
    }

    with engine.begin() as conn:
        if "download_root_path" not in columns:
            conn.execute(text("ALTER TABLE external_favorite_sources ADD COLUMN download_root_path VARCHAR"))
        for name, definition in asmr_columns.items():
            if name not in columns:
                conn.execute(text(f"ALTER TABLE external_favorite_sources ADD COLUMN {name} {definition}"))


ensure_external_source_columns()


def ensure_external_favorite_item_columns():
    inspector = inspect(engine)
    if not inspector.has_table("external_favorite_items"):
        return
    columns = {column["name"] for column in inspector.get_columns("external_favorite_items")}

    with engine.begin() as conn:
        if "sync_position" not in columns:
            conn.execute(text("ALTER TABLE external_favorite_items ADD COLUMN sync_position INTEGER"))


ensure_external_favorite_item_columns()


def ensure_media_indexes():
    with engine.begin() as conn:
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_media_folder_id ON media (folder_id)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_media_media_type ON media (media_type)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_media_folder_missing ON media (folder_id, is_missing)"))


def ensure_media_cover_info_columns():
    inspector = inspect(engine)
    columns = {column["name"] for column in inspector.get_columns("media")}
    with engine.begin() as conn:
        if "cover_time_ms" not in columns:
            conn.execute(text("ALTER TABLE media ADD COLUMN cover_time_ms INTEGER"))
        if "cover_source" not in columns:
            conn.execute(text("ALTER TABLE media ADD COLUMN cover_source VARCHAR"))


def ensure_manga_ai_profile_columns():
    """Phase 2: add the dense-embedding columns to manga_ai_profiles.

    The table itself is created by Base.metadata.create_all on first boot, but
    `create_all` never adds columns to an already-existing table, so we still
    need this idempotent ALTER for installs that came up before the
    embedding/embedding_model columns existed.
    """
    inspector = inspect(engine)
    if not inspector.has_table("manga_ai_profiles"):
        return
    columns = {column["name"] for column in inspector.get_columns("manga_ai_profiles")}
    with engine.begin() as conn:
        if "embedding" not in columns:
            conn.execute(text("ALTER TABLE manga_ai_profiles ADD COLUMN embedding BLOB"))
        if "embedding_model" not in columns:
            conn.execute(text("ALTER TABLE manga_ai_profiles ADD COLUMN embedding_model VARCHAR"))


ensure_media_indexes()
ensure_media_cover_info_columns()
ensure_manga_ai_profile_columns()


def ensure_x_import_indexes():
    inspector = inspect(engine)
    if not inspector.has_table("x_posts"):
        return
    with engine.begin() as conn:
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_x_posts_source_status ON x_posts (source_id, status)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_x_media_items_post ON x_media_items (post_id)"))


ensure_x_import_indexes()


def ensure_dedup_columns():
    inspector = inspect(engine)
    columns = {column["name"] for column in inspector.get_columns("media")}
    with engine.begin() as conn:
        if "normalized_title" not in columns:
            conn.execute(text("ALTER TABLE media ADD COLUMN normalized_title VARCHAR"))
        if "duplicate_status" not in columns:
            conn.execute(text("ALTER TABLE media ADD COLUMN duplicate_status VARCHAR NOT NULL DEFAULT 'unique'"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_media_normalized_title ON media (normalized_title)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_media_duplicate_status ON media (duplicate_status)"))


def ensure_dedup_indexes():
    inspector = inspect(engine)
    if not inspector.has_table("duplicate_candidates"):
        return
    with engine.begin() as conn:
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_dup_candidates_status ON duplicate_candidates (status)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_dup_candidates_level ON duplicate_candidates (level)"))


ensure_dedup_columns()
ensure_dedup_indexes()

_docs_enabled = os.getenv("HE_ENABLE_DOCS", "").lower() in {"1", "true", "yes", "on"}
app = FastAPI(
    title="HE Manager API",
    docs_url="/docs" if _docs_enabled else None,
    redoc_url="/redoc" if _docs_enabled else None,
    openapi_url="/openapi.json" if _docs_enabled else None,
)


def _cors_origins() -> list[str]:
    raw = os.getenv("HE_ALLOWED_ORIGINS", "").strip()
    if raw:
        return [origin.strip() for origin in raw.split(",") if origin.strip()]
    # Keep development and Sakura-FRP ad-hoc access working by default. The app
    # does not use cookie auth, so credentials stay disabled below.
    return ["*"]

app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins(),
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

THUMBNAIL_DIR = os.path.join(os.getcwd(), ".thumbnails")
os.makedirs(THUMBNAIL_DIR, exist_ok=True)
app.mount("/thumbnails", StaticFiles(directory=THUMBNAIL_DIR), name="thumbnails")


@app.get("/bd2/spine")
def list_bd2_spine_assets(db: Session = Depends(get_db)):
    root = _bd2_asset_root(db)
    char_root = os.path.join(root, "spine", "char")
    if not os.path.isdir(char_root):
        return {"root": root, "assets": []}

    char_info = _bd2_char_info(root)
    assets = []
    for name in sorted(os.listdir(char_root)):
        asset_dir = os.path.join(char_root, name)
        if not os.path.isdir(asset_dir):
            continue
        files = sorted(
            filename
            for filename in os.listdir(asset_dir)
            if os.path.isfile(os.path.join(asset_dir, filename))
        )
        skeleton = next((f for f in files if f.lower().endswith(".skel")), None)
        atlas = next((f for f in files if f.lower().endswith(".atlas")), None)
        textures = [f for f in files if f.lower().endswith((".png", ".jpg", ".jpeg", ".webp"))]
        if not skeleton or not atlas or not textures:
            continue
        assets.append({
            "id": name,
            "title": _bd2_spine_title(name, char_info),
            "skeleton": skeleton,
            "atlas": atlas,
            "textures": textures,
            "skeleton_url": f"/bd2/spine/{name}/{skeleton}",
            "atlas_url": f"/bd2/spine/{name}/{atlas}",
        })
    return {"root": root, "assets": assets}


@app.get("/bd2/spine/{asset_id}/{filename}")
def get_bd2_spine_file(asset_id: str, filename: str, db: Session = Depends(get_db)):
    root = _bd2_asset_root(db)
    asset_dir = _bd2_spine_dir(root, asset_id)
    safe_name = os.path.basename(filename)
    if safe_name != filename:
        raise HTTPException(status_code=400, detail="Invalid filename")
    target = os.path.realpath(os.path.join(asset_dir, safe_name))
    real_asset_dir = os.path.realpath(asset_dir)
    if not (target == real_asset_dir or target.startswith(real_asset_dir + os.sep)):
        raise HTTPException(status_code=400, detail="Invalid file path")
    if not os.path.exists(target) or not os.path.isfile(target):
        raise HTTPException(status_code=404, detail="Spine file not found")
    return FileResponse(target)

DEFAULT_EXTERNAL_DOWNLOAD_DIR = os.path.join(os.getcwd(), "external_downloads")
EXTERNAL_COVERS_DIR = os.path.abspath(os.path.join(os.getcwd(), "..", "covers"))
os.makedirs(EXTERNAL_COVERS_DIR, exist_ok=True)
DOWNLOAD_JOBS = {}
MANGA_PROFILE_JOBS = {}
MANGA_METADATA_JOBS = {}
LOGIN_FAILURES: dict[str, list[float]] = {}

LOGIN_FAILURE_WINDOW_SECONDS = int(os.getenv("HE_LOGIN_FAILURE_WINDOW_SECONDS", "300"))
LOGIN_MAX_FAILURES = int(os.getenv("HE_LOGIN_MAX_FAILURES", "5"))
# Per-username backstop independent of client IP. The IP+username bucket above
# can be defeated by an attacker who rotates a forged X-Forwarded-For on every
# request (each forged IP gets its own bucket); this counter keys on the
# username alone so it survives that, while staying loose enough not to lock a
# legitimate user out from fat-fingering the password a few times.
LOGIN_MAX_FAILURES_PER_USER = int(os.getenv("HE_LOGIN_MAX_FAILURES_PER_USER", "15"))
# Only honour X-Forwarded-For when explicitly told to (i.e. a trusted reverse
# proxy sets it). Off by default so a public client cannot spoof its source IP
# to escape the throttle — under a raw FRP tunnel every request then shares the
# frpc loopback peer, which is exactly the conservative behaviour we want.
_TRUST_FORWARDED_FOR = os.getenv("HE_TRUST_FORWARDED_FOR", "").lower() in {"1", "true", "yes", "on"}
HE_PUBLIC_URL = os.getenv("HE_PUBLIC_URL", "").strip().rstrip("/")
HE_CALLBACK_TOKEN = os.getenv("HE_CALLBACK_TOKEN", "").strip()

PUBLIC_PATHS = {
    "/auth/status",
    "/auth/login",
    "/auth/bootstrap",
}
ADMIN_PREFIXES = (
    "/users",
    "/folders",
    "/search-folder",
    "/system",
    "/external",
    "/x",
    "/dedup",
)
ADMIN_EXACT_PATHS = {
    "/ai/recommendations/config",
    "/recommend/manga-profiles/analyze",
    "/recommend/manga-metadata/analyze",
}


def _public_path(path: str) -> bool:
    return path in PUBLIC_PATHS or path.startswith("/bd2/spine")


def _admin_path(method: str, path: str) -> bool:
    if path.startswith(ADMIN_PREFIXES):
        return True
    if path in ADMIN_EXACT_PATHS:
        return True
    if method == "DELETE" and path.startswith("/media/"):
        return True
    if method == "POST" and path.startswith("/media/") and path.endswith("/regenerate-thumbnail"):
        return True
    return False


def _json_error(status_code: int, detail: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"detail": detail})


@app.middleware("http")
async def require_authenticated_request(request: Request, call_next):
    path = request.url.path
    if request.method == "OPTIONS" or _public_path(path):
        response = await call_next(request)
    else:
        raw_token = auth.extract_token(
            authorization=request.headers.get("authorization"),
            query_token=request.query_params.get("token"),
        )
        if not raw_token:
            return _json_error(401, "Missing access token")

        db = database.SessionLocal()
        try:
            user = auth.authenticate_access_token(db, raw_token)
            if _admin_path(request.method, path) and not user.is_admin:
                return _json_error(403, "Admin permission required")
            request.state.current_user = user
        except HTTPException as exc:
            return _json_error(exc.status_code, str(exc.detail))
        finally:
            db.close()
        response = await call_next(request)

    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "same-origin")
    return response

X_ARCHIVE_UPLOAD_DIR = os.path.join(os.getcwd(), "x_archive_uploads")
os.makedirs(X_ARCHIVE_UPLOAD_DIR, exist_ok=True)


def normalize_download_root(path: Optional[str], source_type: str = "wnacg") -> str:
    raw_path = (path or "").strip()
    if not raw_path:
        raw_path = os.path.join(DEFAULT_EXTERNAL_DOWNLOAD_DIR, source_type)
    return os.path.abspath(os.path.expanduser(raw_path))


def get_external_storage_dirs(source: models.ExternalFavoriteSource, download_root_path: Optional[str] = None):
    root = normalize_download_root(
        download_root_path if download_root_path is not None else source.download_root_path,
        source.source_type or "wnacg",
    )
    covers_dir = os.path.join(EXTERNAL_COVERS_DIR, source.source_type or "wnacg")
    manga_dir = os.path.join(root, "manga")
    os.makedirs(covers_dir, exist_ok=True)
    os.makedirs(manga_dir, exist_ok=True)
    return root, covers_dir, manga_dir


def get_cover_extension(content_type: str, url: str) -> str:
    guessed = mimetypes.guess_extension((content_type or "").split(";", 1)[0].strip())
    if guessed:
        return ".jpg" if guessed == ".jpe" else guessed
    parsed_ext = os.path.splitext(urlparse(url).path)[1].lower()
    return parsed_ext if parsed_ext in {".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif"} else ".img"


def external_cover_sidecar_rel_path(item: models.ExternalFavoriteItem) -> Optional[str]:
    cover_url = (item.cover_url or "").strip()
    if not cover_url:
        return None
    if (item.source_type or "") == "asmr":
        ext = downloader_push.url_ext(cover_url, ".jpg")
        return f"cover{ext}"
    return None


def find_external_cover_sidecar(item_dir: str) -> Optional[str]:
    image_exts = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif"}
    candidates = glob.glob(os.path.join(item_dir, "cover.*"))
    candidates += glob.glob(os.path.join(item_dir, ".he_cover", "cover.*"))
    for path in sorted(candidates):
        if os.path.splitext(path)[1].lower() in image_exts and os.path.isfile(path):
            return path
    return None


def ensure_asmr_cover_file(item: models.ExternalFavoriteItem, item_dir: str) -> Optional[str]:
    """Ensure item_dir has a sidecar cover.* image, downloading from
    item.cover_url when missing. Idempotent (returns the existing path if one
    is already there) and best-effort (returns None on any failure — cover is
    nice-to-have, never a download blocker).

    Called from two places so a single transient fetch_file failure doesn't
    leave a work permanently coverless:
      1. download_asmr_item — first attempt right after audio finishes.
      2. upsert_external_downloaded_audio_media — second attempt before the
         Media row's thumbnail is generated, so a retried download (or a later
         job that revisits the same work) heals the gap automatically.
    """
    if not item_dir or not os.path.isdir(item_dir):
        return None
    existing = scanner.get_work_cover_path(item_dir)
    if existing:
        return existing
    cover_url = (item.cover_url or "").strip()
    if not cover_url:
        return None
    try:
        content, content_type = asmr_source.fetch_file(cover_url)
    except Exception as exc:  # noqa: BLE001 — cover is nice-to-have
        print(f"  ! Failed to download cover for {item.title!r}: {exc}")
        return None
    ext = get_cover_extension(content_type, cover_url)
    cover_local = os.path.join(item_dir, f"cover{ext}")
    try:
        with open(cover_local, "wb") as cover_file:
            cover_file.write(content)
    except OSError as exc:
        print(f"  ! Failed to write cover for {item.title!r}: {exc}")
        return None
    return cover_local


def get_cover_cache_prefix(item: models.ExternalFavoriteItem) -> str:
    stable_id = item.external_id or str(item.id)
    digest = hashlib.sha1((item.cover_url or item.url or stable_id).encode("utf-8")).hexdigest()[:10]
    return f"{item.id}_{stable_id}_{digest}"


def find_cached_cover(covers_dir: str, item: models.ExternalFavoriteItem) -> Optional[str]:
    matches = glob.glob(os.path.join(covers_dir, f"{get_cover_cache_prefix(item)}.*"))
    return matches[0] if matches else None


def ensure_external_cover_cache(item: models.ExternalFavoriteItem, source: models.ExternalFavoriteSource) -> Optional[str]:
    if not (item.cover_url or "").strip():
        return None
    _, covers_dir, _ = get_external_storage_dirs(source)
    cached_cover = find_cached_cover(covers_dir, item)
    if cached_cover and os.path.exists(cached_cover):
        return cached_cover
    try:
        if (source.source_type or "") == "asmr":
            content, content_type = asmr_source.fetch_file(item.cover_url)
        else:
            content, content_type = external_sources.fetch_binary(
                item.cover_url,
                source.cookie or "",
                referer=item.url or source.favorites_url,
            )
    except Exception as exc:  # noqa: BLE001
        print(f"  ! Failed to cache external cover for {item.title!r}: {exc}")
        return None
    extension = get_cover_extension(content_type, item.cover_url)
    cover_path = os.path.join(covers_dir, f"{get_cover_cache_prefix(item)}{extension}")
    try:
        with open(cover_path, "wb") as cover_file:
            cover_file.write(content)
    except OSError as exc:
        print(f"  ! Failed to write external cover cache for {item.title!r}: {exc}")
        return None
    return cover_path


def safe_filename(value: str, fallback: str = "item") -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value or "").strip()
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    return (cleaned or fallback)[:120]


def get_asmr_storage_dirs(source: models.ExternalFavoriteSource, download_root_path: Optional[str] = None):
    """ASMR-side counterpart to get_external_storage_dirs(): returns
    (root, audio_dir). ASMR works are audio + subtitles, not page-based
    manga, so they live under `{root}/audio/{title}_{RJ}/...` instead of
    sharing the manga folder."""
    root = normalize_download_root(
        download_root_path if download_root_path is not None else source.download_root_path,
        source.source_type or "asmr",
    )
    audio_dir = os.path.join(root, "audio")
    os.makedirs(audio_dir, exist_ok=True)
    return root, audio_dir


def external_item_download_dir(item: models.ExternalFavoriteItem, source: models.ExternalFavoriteSource, download_root_path: Optional[str] = None) -> str:
    if (source.source_type or "wnacg") == "asmr":
        _, audio_dir = get_asmr_storage_dirs(source, download_root_path)
        # external_id for ASMR is the RJ code (e.g. "RJ123456"); keep the
        # title-first naming so the directory is human-skimmable in a file
        # explorer while the RJ suffix guarantees uniqueness.
        return os.path.join(audio_dir, f"{safe_filename(item.title, 'asmr')}_{item.external_id}")
    _, _, manga_dir = get_external_storage_dirs(source, download_root_path)
    return os.path.join(manga_dir, f"{safe_filename(item.title, 'wnacg')}_{item.external_id}")


def ensure_external_manga_library(source: models.ExternalFavoriteSource, download_root_path: str, db: Session) -> models.Folder:
    _, _, manga_dir = get_external_storage_dirs(source, download_root_path)
    folder = db.query(models.Folder).filter(models.Folder.path == manga_dir).first()
    if folder:
        folder.scan_mode = "manga"
        folder.status = "idle"
        db.commit()
        db.refresh(folder)
        return folder

    folder = models.Folder(
        path=manga_dir,
        scan_mode="manga",
        status="idle",
        thumbnail_enabled=True,
        thumbnail_interval=1,
    )
    db.add(folder)
    db.commit()
    db.refresh(folder)
    return folder


def ensure_external_audio_library(source: models.ExternalFavoriteSource, download_root_path: str, db: Session) -> models.Folder:
    """ASMR-side counterpart to ensure_external_manga_library(): the audio
    subdir gets a Folder row with scan_mode='audio_work' so the /media endpoint's
    media_type='audio' filter can later pick up these items.

    `scan_mode='audio_work'` is the folder-of-audio-files mode (one Media per
    work folder), shared with the manual "audio work" library users can add
    in SettingsView. The scanner's audio_work branch is idempotent: it will
    skip re-creating Media rows that this function already upserted at
    download time."""
    _, audio_dir = get_asmr_storage_dirs(source, download_root_path)
    folder = db.query(models.Folder).filter(models.Folder.path == audio_dir).first()
    if folder:
        folder.scan_mode = "audio_work"
        folder.status = "idle"
        db.commit()
        db.refresh(folder)
        return folder

    folder = models.Folder(
        path=audio_dir,
        scan_mode="audio_work",
        status="idle",
        thumbnail_enabled=False,
        thumbnail_interval=1,
    )
    db.add(folder)
    db.commit()
    db.refresh(folder)
    return folder


def upsert_external_downloaded_audio_media(
    item: models.ExternalFavoriteItem,
    source: models.ExternalFavoriteSource,
    item_dir: str,
    download_root_path: str,
    db: Session,
    track_count: int,
    total_bytes: int,
) -> models.Media:
    """Register a freshly-downloaded ASMR work as a Media row so the UI can
    surface it under /media?media_type=audio and the 'already downloaded'
    badge in AsmrPanel can light up via find_local_media_for_external_item.

    The row is intentionally minimal: extension='.dir' (the work is a folder,
    not a single file) and page_count holds the track count so list views
    have something meaningful to show. cover_path is populated from the
    sidecar cover file dropped by download_asmr_item — using the same
    scanner helpers as audio_work scan mode so both code paths produce
    identical thumbnails."""
    folder = ensure_external_audio_library(source, download_root_path, db)
    rel_path = os.path.relpath(item_dir, folder.path)

    media = (
        db.query(models.Media)
        .filter(models.Media.absolute_path == item_dir, models.Media.media_type == "audio")
        .first()
    )
    if media:
        media.folder_id = folder.id
        media.title = item.title
        media.relative_path = rel_path
        media.file_size = total_bytes
        media.page_count = track_count
        media.source_url = item.url
        media.source_site = source.source_type
        media.is_missing = False
    else:
        media = models.Media(
            folder_id=folder.id,
            title=item.title,
            relative_path=rel_path,
            absolute_path=item_dir,
            media_type="audio",
            extension=".dir",
            file_size=total_bytes,
            page_count=track_count,
            source_url=item.url,
            source_site=source.source_type,
            is_missing=False,
        )
        db.add(media)
        db.flush()

    # Pull a thumbnail from the cover file download_asmr_item dropped in the
    # work folder. Only generate when there's no cover yet (avoid orphan
    # thumb files piling up across re-runs of the same RJ). If the download
    # path's cover fetch silently failed (CDN/mirror blip), retry it here so
    # the second attempt heals the gap — same helper, same idempotency.
    if not media.cover_path:
        cover_src = ensure_asmr_cover_file(item, item_dir)
        if cover_src:
            digest = hashlib.md5(item_dir.encode("utf-8")).hexdigest()[:12]
            thumb_name = f"thumb_audio_{digest}_{int(datetime.now().timestamp())}.jpg"
            thumb_path = os.path.join(THUMBNAIL_DIR, thumb_name)
            if scanner.make_work_thumbnail(cover_src, thumb_path):
                media.cover_path = thumb_name

    folder.last_scanned_at = datetime.now()
    db.commit()
    db.refresh(media)
    return media


def wnacg_download_is_complete(item_dir: str) -> bool:
    # source.txt is written by download_wnacg_item only AFTER the page loop
    # finishes (see below), so its presence is a reliable "this download
    # actually completed" sentinel. A failed/partial download (exception mid
    # loop) never reaches that write, so its folder lacks source.txt — which is
    # exactly how we tell a half-downloaded book apart from a finished one
    # without needing to know the expected page count out-of-band.
    return os.path.isfile(os.path.join(item_dir, "source.txt"))


def ensure_wnacg_source_marker(item: models.ExternalFavoriteItem, item_dir: str) -> None:
    if not item_dir or not os.path.isdir(item_dir):
        return
    info_path = os.path.join(item_dir, "source.txt")
    if os.path.exists(info_path):
        return
    with open(info_path, "w", encoding="utf-8") as info_file:
        info_file.write(f"{item.title}\n{item.url}\n")


def find_local_media_for_external_item(item: models.ExternalFavoriteItem, db: Session) -> Optional[models.Media]:
    # WNACG works are manga; ASMR works are audio. The expected media_type is
    # the only branch difference — everything else (source_url/source_site
    # match, then absolute_path fallback) is identical.
    expected_media_type = "audio" if (item.source_type or "") == "asmr" else "manga"
    is_manga = expected_media_type == "manga"

    source = item.source
    item_dir = (
        external_item_download_dir(item, source)
        if source and source.download_root_path
        else None
    )
    # For manga, "downloaded" means the folder exists AND finished (has the
    # source.txt sentinel). A partial folder — or one whose folder the user
    # deleted by hand — must NOT count as downloaded, otherwise the favourite
    # gets a permanent "已下载" badge that greys it out and blocks re-download.
    manga_complete = bool(is_manga and item_dir and wnacg_download_is_complete(item_dir))

    media = (
        db.query(models.Media)
        .filter(
            models.Media.source_url == item.url,
            models.Media.source_site == item.source_type,
            models.Media.media_type == expected_media_type,
            models.Media.is_missing == False,
        )
        .first()
    )
    if media:
        if is_manga and not manga_complete:
            # Stale row: a previous failed download (or the find-local fallback
            # below) registered a half-finished/now-deleted folder as a Media
            # row. Self-heal by flagging it missing so it drops out of both the
            # library and this favourite's "已下载" badge, then report
            # not-downloaded so the user can re-download (which resumes via the
            # per-page skip in download_wnacg_item).
            media.is_missing = True
            db.commit()
            return None
        return media

    if not source or not source.download_root_path or not item_dir:
        return None

    if not os.path.isdir(item_dir):
        return None

    media = (
        db.query(models.Media)
        .filter(
            models.Media.absolute_path == item_dir,
            models.Media.media_type == expected_media_type,
            models.Media.is_missing == False,
        )
        .first()
    )
    if media:
        if not media.source_url or not media.source_site:
            media.source_url = item.url
            media.source_site = item.source_type
            db.commit()
            db.refresh(media)
        return media

    # Auto-link an existing downloaded folder back to a Media row. ASMR can't
    # safely auto-upsert here because it needs the live track count / byte
    # total that only the download path knows, so we just bail — the row gets
    # created when the user actually downloads via run_asmr_download_job.
    if expected_media_type == "audio":
        return None

    # Only auto-promote a folder that actually finished downloading; a partial
    # folder left behind by a failed job must stay re-downloadable.
    if not manga_complete:
        return None

    return upsert_external_downloaded_media(item, source, item_dir, source.download_root_path, db)


def serialize_external_favorite_item(item: models.ExternalFavoriteItem, db: Session) -> dict:
    local_media = find_local_media_for_external_item(item, db)
    return {
        "id": item.id,
        "source_id": item.source_id,
        "source_type": item.source_type,
        "external_id": item.external_id,
        "title": item.title,
        "url": item.url,
        "cover_url": item.cover_url,
        "category_id": item.category_id,
        "category_name": item.category_name,
        "sync_position": item.sync_position,
        "last_seen_at": item.last_seen_at,
        "local_media_id": local_media.id if local_media else None,
    }


def upsert_external_downloaded_media(
    item: models.ExternalFavoriteItem,
    source: models.ExternalFavoriteSource,
    item_dir: str,
    download_root_path: str,
    db: Session,
) -> models.Media:
    folder = ensure_external_manga_library(source, download_root_path, db)
    page_count = scanner.count_manga_pages(item_dir, ".dir")
    rel_path = os.path.relpath(item_dir, folder.path)
    total_bytes = scanner.directory_size(item_dir)

    media = (
        db.query(models.Media)
        .filter(models.Media.absolute_path == item_dir, models.Media.media_type == "manga")
        .first()
    )
    if media:
        media.folder_id = folder.id
        media.title = item.title
        media.relative_path = rel_path
        media.file_size = total_bytes
        media.page_count = page_count
        media.source_url = item.url
        media.source_site = source.source_type
        media.is_missing = False
    else:
        media = models.Media(
            folder_id=folder.id,
            title=item.title,
            relative_path=rel_path,
            absolute_path=item_dir,
            media_type="manga",
            extension=".dir",
            file_size=total_bytes,
            page_count=page_count,
            source_url=item.url,
            source_site=source.source_type,
            is_missing=False,
        )
        db.add(media)
        db.flush()

    if not media.cover_path:
        thumb_hash = hashlib.md5(item_dir.encode("utf-8")).hexdigest()[:12]
        thumb_name = f"thumb_ext_{thumb_hash}_{datetime.now().timestamp()}.jpg"
        thumb_path = os.path.join(THUMBNAIL_DIR, thumb_name)
        cover_src = ensure_external_cover_cache(item, source)
        if cover_src and scanner.make_work_thumbnail(cover_src, thumb_path):
            media.cover_path = thumb_name
            media.cover_source = "external_cover"
        elif scanner.get_folder_thumbnail(item_dir, thumb_path):
            media.cover_path = thumb_name

    folder.last_scanned_at = datetime.now()
    db.commit()
    db.refresh(media)
    return media


def get_image_extension(content_type: str, url: str) -> str:
    return get_cover_extension(content_type, url)


class DownloadCancelled(Exception):
    def __init__(self, item_dir: Optional[str] = None):
        super().__init__("Download cancelled")
        self.item_dir = item_dir


def is_cancel_requested(job: dict) -> bool:
    return bool(job.get("cancel_requested"))


def find_task(job: dict, item_id: int) -> Optional[dict]:
    for task in job.get("tasks", []):
        if task.get("item_id") == item_id:
            return task
    return None


def cleanup_incomplete_download(item_dir: str, expected_pages: int):
    if not item_dir or not os.path.isdir(item_dir):
        return

    existing_pages = scanner.count_manga_pages(item_dir, ".dir") or 0
    if existing_pages >= expected_pages:
        return

    shutil.rmtree(item_dir, ignore_errors=True)


def log_wnacg_download_failure(download_root_path: str, title: str, url: str, error: str):
    """Append one line to a durable failure log next to the downloaded books.

    DOWNLOAD_JOBS is in-memory only, so the per-job failure list evaporates on
    a page reload or a backend restart — which is exactly why the user "couldn't
    see which books failed". This file survives both, giving a permanent record
    of what failed and why."""
    try:
        root = normalize_download_root(download_root_path, "wnacg")
        manga_dir = os.path.join(root, "manga")
        os.makedirs(manga_dir, exist_ok=True)
        log_path = os.path.join(manga_dir, "_download_errors.log")
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with open(log_path, "a", encoding="utf-8") as log_file:
            log_file.write(f"[{timestamp}] {title} | {url} | {error}\n")
    except Exception:
        # Logging must never take down a download job.
        pass


def prepare_wnacg_download_plan(item: models.ExternalFavoriteItem, source: models.ExternalFavoriteSource, download_root_path: str):
    item_dir = external_item_download_dir(item, source, download_root_path)

    item_api_url = urljoin(item.url, f"/photos-item-aid-{item.external_id}.html")
    item_html = external_sources.fetch_html(item_api_url, source.cookie or "")
    image_urls = external_sources.parse_wnacg_image_urls(item_html)
    if not image_urls:
        raise RuntimeError("没有解析到图片地址")

    return {
        "item_dir": item_dir,
        "image_urls": image_urls,
    }


def download_wnacg_item(item: models.ExternalFavoriteItem, source: models.ExternalFavoriteSource, plan: dict, job: Optional[dict] = None):
    item_dir = plan["item_dir"]
    image_urls = plan["image_urls"]
    os.makedirs(item_dir, exist_ok=True)
    downloaded = 0
    skipped = 0
    task = find_task(job, item.id) if job is not None else None
    for index, image_url in enumerate(image_urls, start=1):
        if job is not None and is_cancel_requested(job):
            raise DownloadCancelled(item_dir)

        existing = glob.glob(os.path.join(item_dir, f"{index:03d}.*"))
        if existing:
            skipped += 1
            if job is not None:
                job["pages_done"] += 1
                job["current_book_downloaded_pages"] += 1
                if task is not None:
                    task["downloaded_pages"] += 1
            continue
        content, content_type = external_sources.fetch_binary(image_url, source.cookie or "", referer=item.url)
        extension = get_image_extension(content_type, image_url)
        image_path = os.path.join(item_dir, f"{index:03d}{extension}")
        with open(image_path, "wb") as image_file:
            image_file.write(content)
        downloaded += 1
        if job is not None:
            job["pages_done"] += 1
            job["downloaded_bytes"] += len(content)
            job["current_book_downloaded_pages"] += 1
            if task is not None:
                task["downloaded_pages"] += 1
        time.sleep(0.15)

    info_path = os.path.join(item_dir, "source.txt")
    with open(info_path, "w", encoding="utf-8") as info_file:
        info_file.write(f"{item.title}\n{item.url}\n")

    return {
        "item_id": item.id,
        "title": item.title,
        "status": "completed",
        "path": item_dir,
        "pages": len(image_urls),
        "downloaded": downloaded,
        "skipped": skipped,
    }


def run_wnacg_download_job(job_id: str, item_ids: List[int], download_root_path: str):
    db = database.SessionLocal()
    job = DOWNLOAD_JOBS[job_id]
    try:
        planned_downloads = []
        job["status"] = "preparing"
        job["message"] = "正在准备下载"

        for item_id in item_ids:
            if is_cancel_requested(job):
                raise DownloadCancelled()

            task = find_task(job, item_id)
            item = db.query(models.ExternalFavoriteItem).filter(models.ExternalFavoriteItem.id == item_id).first()
            if not item:
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = "条目不存在"
                job["results"].append({"item_id": item_id, "status": "failed", "error": "条目不存在"})
                continue
            if task is not None:
                task["title"] = item.title
            source = get_source_or_404(item.source_id, db)
            if source.source_type != "wnacg":
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = "暂不支持这个站点"
                job["results"].append({"item_id": item_id, "title": item.title, "status": "failed", "error": "暂不支持这个站点"})
                continue
            if source.download_root_path != download_root_path:
                source.download_root_path = download_root_path
                db.commit()
            local_media = find_local_media_for_external_item(item, db)
            if local_media:
                job["completed"] += 1
                if task is not None:
                    task["status"] = "success"
                job["results"].append({
                    "item_id": item.id,
                    "title": item.title,
                    "status": "completed",
                    "local_media_id": local_media.id,
                    "skipped": True,
                })
                continue
            ensure_external_manga_library(source, download_root_path, db)
            try:
                job["message"] = f"正在准备：{item.title}"
                plan = prepare_wnacg_download_plan(item, source, download_root_path)
                job["pages_total"] += len(plan["image_urls"])
                if task is not None:
                    task["total_pages"] = len(plan["image_urls"])
                planned_downloads.append((item, source, plan))
            except Exception as exc:
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = str(exc)
                job["results"].append({"item_id": item.id, "title": item.title, "status": "failed", "error": str(exc)})
                log_wnacg_download_failure(download_root_path, item.title, item.url, str(exc))

        job["bytes_total_known"] = False
        job["status"] = "running"
        for item, source, plan in planned_downloads:
            if is_cancel_requested(job):
                raise DownloadCancelled()

            task = find_task(job, item.id)
            try:
                job["message"] = f"正在下载：{item.title}"
                job["current_book_title"] = item.title
                job["current_book_total_pages"] = len(plan["image_urls"])
                job["current_book_downloaded_pages"] = 0
                if task is not None:
                    task["status"] = "downloading"
                result = download_wnacg_item(item, source, plan, job)
                local_media = upsert_external_downloaded_media(item, source, result["path"], download_root_path, db)
                result["local_media_id"] = local_media.id
                job["completed"] += 1
                if task is not None:
                    task["status"] = "success"
                job["results"].append(result)
            except DownloadCancelled as exc:
                cleanup_incomplete_download(exc.item_dir or plan["item_dir"], len(plan["image_urls"]))
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = "已取消"
                job["results"].append({"item_id": item.id, "title": item.title, "status": "canceled", "path": plan["item_dir"]})
                raise
            except Exception as exc:
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = str(exc)
                job["results"].append({"item_id": item.id, "title": item.title, "status": "failed", "error": str(exc)})
                log_wnacg_download_failure(download_root_path, item.title, item.url, str(exc))

        job["current_book_title"] = ""
        job["current_book_total_pages"] = 0
        job["current_book_downloaded_pages"] = 0

        job["status"] = "completed"
        job["message"] = "下载完成"
    except DownloadCancelled:
        job["status"] = "canceled"
        job["message"] = "下载已取消，未完成的漫画已删除"
    except Exception as exc:
        job["status"] = "failed"
        job["message"] = str(exc)
    finally:
        db.close()


# ============================================================================
# ASMR download pipeline
# ============================================================================
# Mirrors the WNACG download flow above, but the unit of work is one ASMR work
# (= a folder of audio + optional subtitle files) instead of one manga (= a
# folder of page images). Key differences:
#   - tracks come from /api/tracks/{rj} as a nested folder tree
#   - format / SE-version filters are stored on the source row from sync time
#   - single files can be hundreds of MB / GB -> stream to disk, never load
#     the whole body into RAM
#   - download root layout: {root}/audio/{title}_{RJ}/{...nested folders}


def prepare_asmr_download_plan_for_item(item: models.ExternalFavoriteItem, source: models.ExternalFavoriteSource, download_root_path: str):
    """ASMR counterpart to prepare_wnacg_download_plan(): fetch the /api/tracks
    tree, apply the source's format + SE-version filters, and resolve each
    track + subtitle to a local destination under the work's folder."""
    item_dir = external_item_download_dir(item, source, download_root_path)

    token = source.cookie or ""
    if not token:
        raise RuntimeError("ASMR 来源未登录，请先同步一次以获取令牌")

    working_base = source.favorites_url or asmr_source.DEFAULT_API_BASE
    mirrors = asmr_source.parse_mirrors(source.api_mirrors) if source.api_mirrors else None
    tree = asmr_source.fetch_work_tracks(working_base, token, item.external_id, mirrors=mirrors)

    planned_files = asmr_source.prepare_asmr_download_plan(
        tree,
        audio_format=source.audio_format_filter or "all",
        audio_version=source.audio_version_filter or "all",
        include_subtitles=True,
    )
    if not planned_files:
        raise RuntimeError("没有可下载的音频文件（作品 tracks 为空）")

    files = []
    for planned in planned_files:
        local_path = os.path.join(item_dir, *planned.rel_segments)
        files.append({
            "url": planned.download_url,
            "local_path": local_path,
            "kind": planned.kind,
            "size": planned.size,
        })

    return {
        "item_dir": item_dir,
        "files": files,
    }


def download_asmr_item(item: models.ExternalFavoriteItem, source: models.ExternalFavoriteSource, plan: dict, job: Optional[dict] = None):
    """Stream every file in `plan["files"]` to disk under `plan["item_dir"]`,
    updating job counters as bytes come in. Cancellation is checked between
    files AND inside the per-chunk read loop so the user doesn't have to wait
    for a multi-GB WAV to finish before the cancel kicks in."""
    item_dir = plan["item_dir"]
    files = plan["files"]
    os.makedirs(item_dir, exist_ok=True)

    downloaded = 0
    skipped = 0
    total_bytes = 0
    audio_track_count = 0
    task = find_task(job, item.id) if job is not None else None

    for index, file_info in enumerate(files, start=1):
        if job is not None and is_cancel_requested(job):
            raise DownloadCancelled(item_dir)

        local_path = file_info["local_path"]
        if file_info["kind"] == "audio":
            audio_track_count += 1

        # Skip if the file already exists with a non-zero size (resume on rerun).
        if os.path.exists(local_path) and os.path.getsize(local_path) > 0:
            skipped += 1
            existing_size = os.path.getsize(local_path)
            total_bytes += existing_size
            if job is not None:
                job["pages_done"] += 1
                job["current_book_downloaded_pages"] += 1
                job["downloaded_bytes"] += existing_size
                if task is not None:
                    task["downloaded_pages"] += 1
            continue

        os.makedirs(os.path.dirname(local_path), exist_ok=True)
        tmp_path = local_path + ".part"
        bytes_written = 0
        try:
            with asmr_source.open_file_stream(file_info["url"]) as response:
                with open(tmp_path, "wb") as out_file:
                    while True:
                        if job is not None and is_cancel_requested(job):
                            raise DownloadCancelled(item_dir)
                        chunk = response.read(64 * 1024)
                        if not chunk:
                            break
                        out_file.write(chunk)
                        bytes_written += len(chunk)
                        if job is not None:
                            job["downloaded_bytes"] += len(chunk)
            os.replace(tmp_path, local_path)
        except BaseException:
            # Leave any partial .part for inspection-free cleanup by
            # cleanup_incomplete_asmr_download; just don't let the tmp pollute.
            try:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)
            except OSError:
                pass
            raise

        downloaded += 1
        total_bytes += bytes_written
        if job is not None:
            job["pages_done"] += 1
            job["current_book_downloaded_pages"] += 1
            if task is not None:
                task["downloaded_pages"] += 1
        time.sleep(0.05)

    # Download the cover image alongside the audio so the local work folder
    # is self-contained (and audio_work scanner / upsert can pick it up as
    # the Media row's thumbnail). Failure is silently tolerated here — the
    # upsert path retries via the same helper, so a transient blip doesn't
    # leave the work permanently coverless.
    ensure_asmr_cover_file(item, item_dir)

    # Drop a small breadcrumb file so the local folder is self-describing if
    # the DB ever gets wiped. Matches WNACG's source.txt convention.
    info_path = os.path.join(item_dir, "source.txt")
    with open(info_path, "w", encoding="utf-8") as info_file:
        info_file.write(f"{item.title}\n{item.url}\n{item.external_id}\n")

    return {
        "item_id": item.id,
        "title": item.title,
        "status": "completed",
        "path": item_dir,
        "files": len(files),
        "downloaded": downloaded,
        "skipped": skipped,
        "total_bytes": total_bytes,
        "audio_track_count": audio_track_count,
    }


def cleanup_incomplete_asmr_download(item_dir: str, expected_files: int):
    """Drop a partly-downloaded work so the next run starts fresh (or, if the
    user has the same RJ in their selection again, doesn't pick up half-files).
    Counts files recursively because the work is a nested folder tree."""
    if not item_dir or not os.path.isdir(item_dir):
        return

    actual = 0
    for _, _, files in os.walk(item_dir):
        actual += len([f for f in files if not f.endswith(".part") and f != "source.txt"])
    if actual >= expected_files and expected_files > 0:
        return

    shutil.rmtree(item_dir, ignore_errors=True)


def run_asmr_download_job(job_id: str, item_ids: List[int], download_root_path: str):
    db = database.SessionLocal()
    job = DOWNLOAD_JOBS[job_id]
    try:
        planned_downloads = []
        job["status"] = "preparing"
        job["message"] = "正在准备下载"

        for item_id in item_ids:
            if is_cancel_requested(job):
                raise DownloadCancelled()

            task = find_task(job, item_id)
            item = db.query(models.ExternalFavoriteItem).filter(models.ExternalFavoriteItem.id == item_id).first()
            if not item:
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = "条目不存在"
                job["results"].append({"item_id": item_id, "status": "failed", "error": "条目不存在"})
                continue
            if task is not None:
                task["title"] = item.title
            source = get_source_or_404(item.source_id, db)
            if (source.source_type or "") != "asmr":
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = "不是 ASMR 条目"
                job["results"].append({"item_id": item_id, "title": item.title, "status": "failed", "error": "不是 ASMR 条目"})
                continue
            if source.download_root_path != download_root_path:
                source.download_root_path = download_root_path
                db.commit()
            local_media = find_local_media_for_external_item(item, db)
            if local_media:
                job["completed"] += 1
                if task is not None:
                    task["status"] = "success"
                job["results"].append({
                    "item_id": item.id,
                    "title": item.title,
                    "status": "completed",
                    "local_media_id": local_media.id,
                    "skipped": True,
                })
                continue
            ensure_external_audio_library(source, download_root_path, db)
            try:
                job["message"] = f"正在准备：{item.title}"
                plan = prepare_asmr_download_plan_for_item(item, source, download_root_path)
                job["pages_total"] += len(plan["files"])
                if task is not None:
                    task["total_pages"] = len(plan["files"])
                planned_downloads.append((item, source, plan))
            except Exception as exc:
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = str(exc)
                job["results"].append({"item_id": item.id, "title": item.title, "status": "failed", "error": str(exc)})

        job["bytes_total_known"] = False
        job["status"] = "running"
        for item, source, plan in planned_downloads:
            if is_cancel_requested(job):
                raise DownloadCancelled()

            task = find_task(job, item.id)
            try:
                job["message"] = f"正在下载：{item.title}"
                job["current_book_title"] = item.title
                job["current_book_total_pages"] = len(plan["files"])
                job["current_book_downloaded_pages"] = 0
                if task is not None:
                    task["status"] = "downloading"
                result = download_asmr_item(item, source, plan, job)
                local_media = upsert_external_downloaded_audio_media(
                    item,
                    source,
                    result["path"],
                    download_root_path,
                    db,
                    track_count=result["audio_track_count"],
                    total_bytes=result["total_bytes"],
                )
                result["local_media_id"] = local_media.id
                job["completed"] += 1
                if task is not None:
                    task["status"] = "success"
                job["results"].append(result)
            except DownloadCancelled as exc:
                cleanup_incomplete_asmr_download(exc.item_dir or plan["item_dir"], len(plan["files"]))
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = "已取消"
                job["results"].append({"item_id": item.id, "title": item.title, "status": "canceled", "path": plan["item_dir"]})
                raise
            except Exception as exc:
                job["failed"] += 1
                if task is not None:
                    task["status"] = "failed"
                    task["error"] = str(exc)
                job["results"].append({"item_id": item.id, "title": item.title, "status": "failed", "error": str(exc)})

        job["current_book_title"] = ""
        job["current_book_total_pages"] = 0
        job["current_book_downloaded_pages"] = 0

        job["status"] = "completed"
        job["message"] = "下载完成"
    except DownloadCancelled:
        job["status"] = "canceled"
        job["message"] = "下载已取消，未完成的作品已清理"
    except Exception as exc:
        job["status"] = "failed"
        job["message"] = str(exc)
    finally:
        db.close()


def get_media_or_404(media_id: int, db: Session):
    media = db.query(models.Media).filter(models.Media.id == media_id).first()
    if not media:
        raise HTTPException(status_code=404, detail="Media not found")
    return media


def get_source_or_404(source_id: int, db: Session):
    source = db.query(models.ExternalFavoriteSource).filter(models.ExternalFavoriteSource.id == source_id).first()
    if not source:
        raise HTTPException(status_code=404, detail="External source not found")
    return source


def get_url_base(url: str) -> str:
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.netloc:
        return external_sources.WNACG_BASE_URL
    return f"{parsed.scheme}://{parsed.netloc}/"


_MANGA_FILES_CACHE: dict[int, tuple[float, list[str]]] = {}


def get_manga_image_files(media: models.Media):
    image_exts = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".avif"}
    try:
        mtime = os.path.getmtime(media.absolute_path)
    except OSError:
        mtime = 0.0
    cached = _MANGA_FILES_CACHE.get(media.id)
    if cached and cached[0] == mtime:
        return cached[1]

    if media.extension == ".dir":
        files = []
        for root, _, filenames in os.walk(media.absolute_path):
            for filename in filenames:
                if any(filename.lower().endswith(ext) for ext in image_exts):
                    files.append(os.path.join(root, filename))
        result = sorted(files)
    else:
        with zipfile.ZipFile(media.absolute_path, "r") as archive:
            result = sorted(
                name for name in archive.namelist()
                if any(name.lower().endswith(ext) for ext in image_exts)
            )

    _MANGA_FILES_CACHE[media.id] = (mtime, result)
    return result


@app.on_event("startup")
def cleanup_orphaned_thumbnails():
    db = database.SessionLocal()
    try:
        valid_bases = [
            row[0].rsplit(".", 1)[0]
            for row in db.query(models.Media.cover_path).filter(models.Media.cover_path != None).all()
            if row[0]
        ]

        if os.path.exists(THUMBNAIL_DIR):
            for filename in os.listdir(THUMBNAIL_DIR):
                if any(filename.startswith(base) for base in valid_bases):
                    continue
                try:
                    os.remove(os.path.join(THUMBNAIL_DIR, filename))
                except Exception:
                    pass
    except Exception as e:
        print(f"Error cleaning up thumbnails: {e}")
    finally:
        db.close()


@app.get("/")
def read_root():
    return {"message": "Welcome to HE Manager API"}


@app.get("/auth/status", response_model=schemas.AuthStatus)
def auth_status(db: Session = Depends(get_db)):
    return {"has_users": db.query(models.User).first() is not None}


def _client_ip(request: Request) -> str:
    # X-Forwarded-For is client-controllable over a raw FRP tunnel, so only
    # trust it when a known reverse proxy is in front (HE_TRUST_FORWARDED_FOR).
    # Otherwise use the direct peer — under FRP that collapses every public
    # caller onto the frpc loopback peer, which means the throttle can't be
    # sidestepped by forging a fresh source IP per request.
    if _TRUST_FORWARDED_FOR:
        forwarded = (request.headers.get("x-forwarded-for") or "").split(",", 1)[0].strip()
        if forwarded:
            return forwarded
    return request.client.host if request.client else "unknown"


def _login_failure_key(request: Request, username: str) -> str:
    return f"{_client_ip(request)}:{username.strip().lower()}"


def _pruned_login_failures(key: str) -> list[float]:
    now = time.time()
    failures = [ts for ts in LOGIN_FAILURES.get(key, []) if now - ts <= LOGIN_FAILURE_WINDOW_SECONDS]
    if failures:
        LOGIN_FAILURES[key] = failures
    else:
        LOGIN_FAILURES.pop(key, None)
    return failures


def _record_login_failure(key: str) -> None:
    failures = _pruned_login_failures(key)
    failures.append(time.time())
    LOGIN_FAILURES[key] = failures


@app.post("/auth/bootstrap", response_model=schemas.AuthToken)
def bootstrap_first_user(payload: schemas.UserCreate, db: Session = Depends(get_db)):
    if db.query(models.User).first():
        raise HTTPException(status_code=409, detail="Users already exist")

    user = models.User(
        username=payload.username.strip(),
        password_hash=auth.hash_password(payload.password),
        is_admin=True,
        is_active=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    token = auth.create_access_token(db, user)
    return {"access_token": token, "user": user}


@app.post("/auth/login", response_model=schemas.AuthToken)
def login(payload: schemas.UserLogin, request: Request, db: Session = Depends(get_db)):
    failure_key = _login_failure_key(request, payload.username)
    # Per-username key has no IP component, so a forged X-Forwarded-For can't
    # spawn a fresh bucket to dodge it.
    user_key = f"user:{payload.username.strip().lower()}"
    if (
        len(_pruned_login_failures(failure_key)) >= LOGIN_MAX_FAILURES
        or len(_pruned_login_failures(user_key)) >= LOGIN_MAX_FAILURES_PER_USER
    ):
        raise HTTPException(status_code=429, detail="登录失败次数过多，请稍后再试")

    user = db.query(models.User).filter(models.User.username == payload.username.strip()).first()
    if not user or not user.is_active or not auth.verify_password(payload.password, user.password_hash):
        _record_login_failure(failure_key)
        _record_login_failure(user_key)
        raise HTTPException(status_code=401, detail="用户名或密码错误")

    LOGIN_FAILURES.pop(failure_key, None)
    LOGIN_FAILURES.pop(user_key, None)
    token = auth.create_access_token(db, user)
    return {"access_token": token, "user": user}


@app.post("/auth/logout")
def logout(request: Request, db: Session = Depends(get_db)):
    raw_token = auth.extract_token(
        authorization=request.headers.get("authorization"),
        query_token=request.query_params.get("token"),
    )
    if raw_token:
        auth.revoke_access_token(db, raw_token)
    return {"message": "Logged out"}


@app.get("/auth/me", response_model=schemas.UserRead)
def get_me(user: models.User = Depends(auth.get_current_user)):
    return user


@app.get("/users", response_model=List[schemas.UserRead])
def list_users(_: models.User = Depends(auth.require_admin), db: Session = Depends(get_db)):
    return db.query(models.User).order_by(models.User.id.asc()).all()


@app.post("/users", response_model=schemas.UserRead)
def create_user(payload: schemas.UserCreate, _: models.User = Depends(auth.require_admin), db: Session = Depends(get_db)):
    username = payload.username.strip()
    if db.query(models.User).filter(models.User.username == username).first():
        raise HTTPException(status_code=409, detail="用户名已存在")

    user = models.User(
        username=username,
        password_hash=auth.hash_password(payload.password),
        is_admin=payload.is_admin,
        is_active=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


@app.put("/users/{user_id}", response_model=schemas.UserRead)
def update_user(user_id: int, payload: schemas.UserUpdate, current_user: models.User = Depends(auth.require_admin), db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    if payload.username is not None:
        username = payload.username.strip()
        existing = db.query(models.User).filter(models.User.username == username).first()
        if existing and existing.id != user_id:
            raise HTTPException(status_code=409, detail="用户名已存在")
        user.username = username

    if payload.password is not None:
        user.password_hash = auth.hash_password(payload.password)

    if payload.is_admin is not None:
        # Prevent self-demotion to avoid losing admin access
        if current_user.id == user_id and payload.is_admin is False:
            raise HTTPException(status_code=400, detail="不能撤销自己的管理员权限")
        user.is_admin = payload.is_admin

    if payload.is_active is not None:
        # Prevent deactivating oneself
        if current_user.id == user_id and payload.is_active is False:
            raise HTTPException(status_code=400, detail="不能停用自己的账号")
        user.is_active = payload.is_active

    db.commit()
    db.refresh(user)
    return user


@app.delete("/users/{user_id}")
def delete_user(user_id: int, current_user: models.User = Depends(auth.require_admin), db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    if current_user.id == user_id:
        raise HTTPException(status_code=400, detail="不能删除自己的账号")

    db.delete(user)
    db.commit()
    return {"message": "User deleted"}


@app.get("/search-folder")
def search_folder(name: str):
    import string

    results = []
    search_roots = []

    for letter in string.ascii_uppercase:
        drive = f"{letter}:\\"
        if os.path.exists(drive):
            search_roots.append(drive)

    search_roots.append(os.path.expanduser("~"))

    for root in search_roots:
        try:
            for entry in os.listdir(root):
                full = os.path.join(root, entry)
                if os.path.isdir(full) and entry.lower() == name.lower():
                    results.append(full)
        except (PermissionError, OSError):
            continue

    return {"results": results}


@app.post("/folders", response_model=schemas.Folder)
def create_folder(folder: schemas.FolderCreate, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    if not os.path.exists(folder.path):
        raise HTTPException(status_code=400, detail="指定的文件夹路径不存在，请检查路径是否正确。")

    if not os.path.isdir(folder.path):
        raise HTTPException(status_code=400, detail="指定的路径不是一个目录。")

    db_folder = db.query(models.Folder).filter(models.Folder.path == folder.path).first()
    if db_folder:
        db_folder.scan_mode = folder.scan_mode
        db_folder.thumbnail_enabled = folder.thumbnail_enabled
        db_folder.thumbnail_interval = folder.thumbnail_interval
        db.commit()
        db.refresh(db_folder)
        background_tasks.add_task(scanner.scan_folder, db_folder.id)
        return db_folder

    new_folder = models.Folder(
        path=folder.path,
        scan_mode=folder.scan_mode,
        thumbnail_enabled=folder.thumbnail_enabled,
        thumbnail_interval=folder.thumbnail_interval,
    )
    db.add(new_folder)
    db.commit()
    db.refresh(new_folder)

    background_tasks.add_task(scanner.scan_folder, new_folder.id)
    return new_folder


@app.post("/folders/{folder_id}/scan", response_model=schemas.Folder)
def scan_folder(folder_id: int, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    db_folder = db.query(models.Folder).filter(models.Folder.id == folder_id).first()
    if not db_folder:
        raise HTTPException(status_code=404, detail="Folder not found")

    background_tasks.add_task(scanner.scan_folder, folder_id)
    return db_folder


@app.get("/folders", response_model=List[schemas.Folder])
def list_folders(db: Session = Depends(get_db)):
    return db.query(models.Folder).all()


@app.delete("/folders/{folder_id}")
def delete_folder(folder_id: int, db: Session = Depends(get_db)):
    db_folder = db.query(models.Folder).filter(models.Folder.id == folder_id).first()
    if not db_folder:
        raise HTTPException(status_code=404, detail="Folder not found")

    associated_media = db.query(models.Media).filter(models.Media.folder_id == folder_id).all()
    media_cleanup.detach_media_references(db, [media.id for media in associated_media])
    for media in associated_media:
        if not media.cover_path:
            continue
        thumb_base = media.cover_path.rsplit(".", 1)[0]
        for filename in os.listdir(THUMBNAIL_DIR):
            if not filename.startswith(thumb_base):
                continue
            thumb_path = os.path.join(THUMBNAIL_DIR, filename)
            if os.path.exists(thumb_path):
                try:
                    os.remove(thumb_path)
                except Exception:
                    pass

    db.delete(db_folder)
    db.commit()
    return {"message": "Folder and associated media deleted from library"}


@app.get("/media", response_model=List[schemas.Media])
def list_media(
    media_type: Optional[str] = None,
    search: Optional[str] = None,
    tag: Optional[str] = None,
    favorite: Optional[bool] = None,
    view_status: Optional[str] = None,
    is_missing: Optional[bool] = None,
    duplicate_status: Optional[str] = None,
    include_hidden_duplicates: bool = False,
    source_site: Optional[str] = None,
    sort: str = "date",
    db: Session = Depends(get_db),
):
    query = db.query(models.Media)
    if media_type:
        query = query.filter(models.Media.media_type == media_type)
    if search:
        query = query.filter(models.Media.title.ilike(f"%{search}%"))
    if tag:
        query = query.join(models.Media.tags).filter(models.Tag.name == tag)
    if favorite is not None:
        query = query.filter(models.Media.favorite == favorite)
    if view_status:
        query = query.filter(models.Media.view_status == view_status)
    if is_missing is not None:
        query = query.filter(models.Media.is_missing == is_missing)
    if source_site:
        if source_site == "local":
            query = query.filter(models.Media.source_site.is_(None))
        else:
            query = query.filter(models.Media.source_site == source_site)
    if duplicate_status:
        query = query.filter(models.Media.duplicate_status == duplicate_status)
    elif not include_hidden_duplicates:
        query = query.filter(
            models.Media.duplicate_status.notin_(["checking", "strong_duplicate", "suspected_duplicate"])
        )

    if sort == "title":
        query = query.order_by(models.Media.title.asc())
    elif sort == "rating":
        query = query.order_by(models.Media.rating.desc(), models.Media.id.desc())
    elif sort == "opened":
        query = query.order_by(models.Media.last_opened_at.desc(), models.Media.id.desc())
    else:
        query = query.order_by(models.Media.id.desc())

    return query.all()


@app.get("/mobile/media", response_model=List[schemas.Media])
def list_mobile_media(
    media_type: Optional[str] = None,
    search: Optional[str] = None,
    sort: str = "date",
    _: models.User = Depends(auth.get_current_user),
    db: Session = Depends(get_db),
):
    query = db.query(models.Media).filter(models.Media.is_missing == False)
    if media_type:
        query = query.filter(models.Media.media_type == media_type)
    if search:
        query = query.filter(models.Media.title.ilike(f"%{search}%"))

    if sort == "title":
        query = query.order_by(models.Media.title.asc())
    elif sort == "rating":
        query = query.order_by(models.Media.rating.desc(), models.Media.id.desc())
    elif sort == "opened":
        query = query.order_by(models.Media.last_opened_at.desc(), models.Media.id.desc())
    else:
        query = query.order_by(models.Media.id.desc())

    return query.all()


@app.get("/mobile/media/{media_id}", response_model=schemas.Media)
def get_mobile_media(media_id: int, _: models.User = Depends(auth.get_current_user), db: Session = Depends(get_db)):
    return get_media_or_404(media_id, db)


@app.get("/mobile/thumbnails/{filename}")
def get_mobile_thumbnail(filename: str, _: models.User = Depends(auth.get_current_user)):
    safe_name = os.path.basename(filename)
    thumb_path = os.path.join(THUMBNAIL_DIR, safe_name)
    if not os.path.exists(thumb_path):
        raise HTTPException(status_code=404, detail="Thumbnail not found")
    return FileResponse(thumb_path)


@app.get("/media/{media_id}", response_model=schemas.Media)
def get_media(media_id: int, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    
    # Auto-heal missing flag if file is back
    if media.is_missing and os.path.exists(media.absolute_path):
        media.is_missing = False
        media.missing_since = None

    media.last_opened_at = datetime.utcnow()
    if media.view_status == "unviewed":
        media.view_status = "viewing"
    db.commit()
    db.refresh(media)
    return media


@app.patch("/media/{media_id}", response_model=schemas.Media)
def update_media(media_id: int, payload: schemas.MediaUpdate, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    data = payload.dict(exclude_unset=True)
    if "view_status" in data and data["view_status"] not in {"unviewed", "viewing", "viewed"}:
        raise HTTPException(status_code=400, detail="view_status must be unviewed, viewing, or viewed")

    for key, value in data.items():
        setattr(media, key, value)

    if "progress" in data:
        progress = int(media.progress or 0)
        if media.media_type == "video":
            if progress <= 0:
                media.view_status = "unviewed"
            elif media.duration:
                ratio = progress / max(1, int(media.duration))
                media.view_status = "viewed" if ratio >= 0.95 else "viewing"
            else:
                media.view_status = "viewing"
        elif media.media_type == "manga" and media.page_count:
            if progress >= int(media.page_count) - 1:
                media.view_status = "viewed"
            elif progress > 0:
                media.view_status = "viewing"
            else:
                media.view_status = "unviewed"

    db.commit()
    db.refresh(media)
    return media


@app.delete("/media/{media_id}")
def delete_media(media_id: int, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    if media.cover_path:
        thumb_base = media.cover_path.rsplit(".", 1)[0]
        try:
            for filename in os.listdir(THUMBNAIL_DIR):
                if not filename.startswith(thumb_base):
                    continue
                thumb_path = os.path.join(THUMBNAIL_DIR, filename)
                if os.path.exists(thumb_path):
                    try:
                        os.remove(thumb_path)
                    except Exception:
                        pass
        except FileNotFoundError:
            pass

    # Clean up FK references that don't cascade automatically. SQLite enforces
    # foreign_keys=ON so leaving these would block the delete with a 500.
    media_cleanup.detach_media_references(db, [media.id])

    db.delete(media)
    db.commit()
    return {"message": "Media removed from library"}


@app.post("/media/{media_id}/recheck", response_model=schemas.Media)
def recheck_media(media_id: int, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    
    if os.path.exists(media.absolute_path):
        media.is_missing = False
        media.missing_since = None
        media.last_opened_at = datetime.utcnow()
        try:
            # Optionally update file size
            media.file_size = os.path.getsize(media.absolute_path)
        except OSError:
            pass
        db.commit()
        db.refresh(media)
        return media
    else:
        if not media.is_missing:
            media.is_missing = True
            media.missing_since = datetime.utcnow()
            db.commit()
            db.refresh(media)
        raise HTTPException(status_code=404, detail="File still missing")


@app.post("/system/recheck-missing")
def recheck_all_missing(background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    # This checks all missing files. Since checking os.path.exists is fast, we can do it synchronously,
    # but to be totally safe we just do it right here and return the count of recovered items.
    missing_items = db.query(models.Media).filter(models.Media.is_missing == True).all()
    recovered_count = 0
    for media in missing_items:
        if os.path.exists(media.absolute_path):
            media.is_missing = False
            media.missing_since = None
            recovered_count += 1
    
    if recovered_count > 0:
        db.commit()
    
    return {"message": "Recheck completed", "total_missing_checked": len(missing_items), "recovered": recovered_count}


@app.get("/tags", response_model=List[schemas.Tag])
def list_tags(db: Session = Depends(get_db)):
    return db.query(models.Tag).order_by(models.Tag.name.asc()).all()


@app.get("/ai/recommendations/status", response_model=schemas.AiRecommendationStatus)
def ai_recommendation_status():
    config = ai_config.get_deepseek_config()
    return {
        "deepseek_configured": bool(config["api_key"]),
        "model": config["model"],
        "base_url": config["base_url"],
        "key_saved": config["key_saved"],
        "env_key_present": config["env_key_present"],
    }


@app.put("/ai/recommendations/config", response_model=schemas.AiRecommendationStatus)
def update_ai_recommendation_config(payload: schemas.DeepSeekConfigUpdate):
    config = ai_config.update_deepseek_config(
        api_key=payload.api_key,
        model=payload.model,
        base_url=payload.base_url,
        clear_api_key=payload.clear_api_key,
    )
    return {
        "deepseek_configured": bool(config["api_key"]),
        "model": config["model"],
        "base_url": config["base_url"],
        "key_saved": config["key_saved"],
        "env_key_present": config["env_key_present"],
    }


def run_manga_profile_job(job_id: str, media_ids: list[int], sample_count: int, force: bool):
    db = database.SessionLocal()
    job = MANGA_PROFILE_JOBS[job_id]
    try:
        job["status"] = "running"
        for media_id in media_ids:
            media = db.query(models.Media).filter(models.Media.id == media_id).first()
            if not media:
                job["failed"] += 1
                job["errors"].append(f"Media {media_id} not found")
                continue
            job["current_title"] = media.title or str(media.id)
            try:
                manga_profiles.analyze_media(db, media, sample_count=sample_count, force=force)
                db.commit()
                job["completed"] += 1
                job["message"] = f"已分析 {job['completed']} / {job['total']}"
            except Exception as exc:  # noqa: BLE001 - batch job should continue
                db.rollback()
                job["failed"] += 1
                job["errors"].append(f"{media.title or media.id}: {exc}")
        job["status"] = "completed"
        job["current_title"] = ""
        job["message"] = f"完成：{job['completed']} 个，失败 {job['failed']} 个"
    except Exception as exc:  # noqa: BLE001
        job["status"] = "failed"
        job["message"] = str(exc)
    finally:
        db.close()


@app.get("/recommend/manga-profiles/stats", response_model=schemas.MangaProfileStats)
def manga_profile_stats(db: Session = Depends(get_db)):
    return manga_profiles.profile_stats(db)


@app.post("/recommend/manga-profiles/analyze", response_model=schemas.MangaProfileJob)
def analyze_manga_profiles(
    payload: schemas.MangaProfileAnalyzeRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
):
    if payload.media_id:
        media = get_media_or_404(payload.media_id, db)
        if media.media_type != "manga":
            raise HTTPException(status_code=400, detail="只能分析漫画")
        media_ids = [media.id]
    else:
        query = db.query(models.Media).filter(
            models.Media.media_type == "manga",
            models.Media.is_missing == False,  # noqa: E712
            models.Media.duplicate_status.notin_(["checking", "strong_duplicate", "suspected_duplicate"]),
        )
        rows = query.order_by(models.Media.id.desc()).all()
        media_ids = [
            media.id for media in rows
            if payload.force or manga_profiles.needs_profile(media)
        ][: payload.limit]

    job_id = str(uuid.uuid4())
    MANGA_PROFILE_JOBS[job_id] = {
        "job_id": job_id,
        "status": "queued",
        "total": len(media_ids),
        "completed": 0,
        "failed": 0,
        "message": "准备分析内容画像",
        "current_title": "",
        "errors": [],
    }
    background_tasks.add_task(run_manga_profile_job, job_id, media_ids, payload.sample_count, payload.force)
    return MANGA_PROFILE_JOBS[job_id]


@app.get("/recommend/manga-profiles/jobs/{job_id}", response_model=schemas.MangaProfileJob)
def get_manga_profile_job(job_id: str):
    job = MANGA_PROFILE_JOBS.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="job not found")
    return job


def run_manga_metadata_job(job_id: str, media_ids: list[int], force: bool):
    db = database.SessionLocal()
    job = MANGA_METADATA_JOBS[job_id]
    try:
        job["status"] = "running"
        for media_id in media_ids:
            media = db.query(models.Media).filter(models.Media.id == media_id).first()
            if not media:
                job["failed"] += 1
                job["errors"].append(f"Media {media_id} not found")
                continue
            job["current_title"] = media.title or str(media.id)
            try:
                manga_metadata.build_metadata_profile(db, media, force=force)
                db.commit()
                job["completed"] += 1
                job["message"] = f"已补全 {job['completed']} / {job['total']}"
            except Exception as exc:  # noqa: BLE001
                db.rollback()
                job["failed"] += 1
                job["errors"].append(f"{media.title or media.id}: {exc}")
        job["status"] = "completed"
        job["current_title"] = ""
        job["message"] = f"完成：{job['completed']} 个，失败 {job['failed']} 个"
    except Exception as exc:  # noqa: BLE001
        job["status"] = "failed"
        job["message"] = str(exc)
    finally:
        db.close()


@app.get("/recommend/manga-metadata/stats", response_model=schemas.MangaMetadataStats)
def manga_metadata_stats(db: Session = Depends(get_db)):
    return manga_metadata.profile_stats(db)


@app.post("/recommend/manga-metadata/analyze", response_model=schemas.MangaMetadataJob)
def analyze_manga_metadata(
    payload: schemas.MangaMetadataAnalyzeRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
):
    if payload.media_id:
        media = get_media_or_404(payload.media_id, db)
        if media.media_type != "manga":
            raise HTTPException(status_code=400, detail="只能补全漫画元数据")
        media_ids = [media.id]
    else:
        rows = (
            db.query(models.Media)
            .filter(
                models.Media.media_type == "manga",
                models.Media.is_missing == False,  # noqa: E712
            )
            .order_by(models.Media.id.desc())
            .all()
        )
        media_ids = [
            media.id for media in rows
            if payload.force or manga_metadata.needs_metadata(media)
        ][: payload.limit]

    job_id = str(uuid.uuid4())
    MANGA_METADATA_JOBS[job_id] = {
        "job_id": job_id,
        "status": "queued",
        "total": len(media_ids),
        "completed": 0,
        "failed": 0,
        "message": "准备补全元数据",
        "current_title": "",
        "errors": [],
    }
    background_tasks.add_task(run_manga_metadata_job, job_id, media_ids, payload.force)
    return MANGA_METADATA_JOBS[job_id]


@app.get("/recommend/manga-metadata/jobs/{job_id}", response_model=schemas.MangaMetadataJob)
def get_manga_metadata_job(job_id: str):
    job = MANGA_METADATA_JOBS.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="job not found")
    return job


@app.post("/recommend/manga", response_model=schemas.MangaRecommendationResponse)
def recommend_manga(payload: schemas.MangaRecommendationRequest, db: Session = Depends(get_db)):
    return recommendations.recommend_manga(
        db=db,
        query=payload.query,
        limit=payload.limit,
        avoid_tags=payload.avoid_tags,
        preferred_tags=payload.preferred_tags,
    )


@app.post("/media/{media_id}/tags", response_model=schemas.Media)
def add_media_tag(media_id: int, payload: schemas.TagCreate, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    tag_name = payload.name.strip()
    if not tag_name:
        raise HTTPException(status_code=400, detail="Tag name cannot be empty")

    tag = db.query(models.Tag).filter(models.Tag.name == tag_name).first()
    if not tag:
        tag = models.Tag(name=tag_name)
        db.add(tag)
        db.flush()

    if tag not in media.tags:
        media.tags.append(tag)
    db.commit()
    db.refresh(media)
    return media


@app.delete("/media/{media_id}/tags/{tag_id}", response_model=schemas.Media)
def remove_media_tag(media_id: int, tag_id: int, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    tag = db.query(models.Tag).filter(models.Tag.id == tag_id).first()
    if not tag:
        raise HTTPException(status_code=404, detail="Tag not found")
    if tag in media.tags:
        media.tags.remove(tag)
    db.commit()
    db.refresh(media)
    return media


@app.get("/external/sources", response_model=List[schemas.ExternalFavoriteSource])
def list_external_sources(db: Session = Depends(get_db)):
    return db.query(models.ExternalFavoriteSource).order_by(models.ExternalFavoriteSource.id.desc()).all()


@app.patch("/external/sources/{source_id}", response_model=schemas.ExternalFavoriteSource)
def update_external_source(source_id: int, payload: schemas.ExternalFavoriteSourceUpdate, db: Session = Depends(get_db)):
    source = get_source_or_404(source_id, db)
    data = payload.dict(exclude_unset=True)
    if "name" in data and data["name"]:
        source.name = data["name"]
    if "favorites_url" in data and data["favorites_url"]:
        source.favorites_url = data["favorites_url"]
    if "download_root_path" in data:
        source.download_root_path = (data["download_root_path"] or "").strip() or None
        # download_root_path setup is wnacg-shaped (creates a manga dir).
        # Skip the side-effect for asmr sources — their root layout is handled
        # by get_asmr_storage_dirs() at download time.
        if (source.source_type or "wnacg") != "asmr":
            get_external_storage_dirs(source)
    # ASMR knobs: "all"/"no_wav"/"mp3_only" + "all"/"no_se"/"se_only". Empty
    # string falls back to "all" so the front-end can clear without nulling.
    if "audio_format_filter" in data:
        source.audio_format_filter = (data["audio_format_filter"] or "").strip() or "all"
    if "audio_version_filter" in data:
        source.audio_version_filter = (data["audio_version_filter"] or "").strip() or "all"
    if "playlist_url" in data:
        # null / empty string both mean "no playlist, use marked works"
        source.playlist_url = (data["playlist_url"] or "").strip() or None
    if "api_mirrors" in data:
        source.api_mirrors = (data["api_mirrors"] or "").strip() or None
    db.commit()
    db.refresh(source)
    return source


@app.get("/external/favorites", response_model=List[schemas.ExternalFavoriteItem])
def list_external_favorites(
    source_type: Optional[str] = None,
    source_id: Optional[int] = None,
    search: Optional[str] = None,
    db: Session = Depends(get_db),
):
    query = db.query(models.ExternalFavoriteItem)
    if source_type:
        query = query.filter(models.ExternalFavoriteItem.source_type == source_type)
    if source_id:
        query = query.filter(models.ExternalFavoriteItem.source_id == source_id)
    if search:
        query = query.filter(models.ExternalFavoriteItem.title.ilike(f"%{search}%"))
    favorite_items = query.order_by(
        models.ExternalFavoriteItem.sync_position.is_(None),
        models.ExternalFavoriteItem.sync_position.asc(),
        models.ExternalFavoriteItem.id.desc(),
    ).all()
    return [serialize_external_favorite_item(item, db) for item in favorite_items]


@app.post("/external/wnacg/sync", response_model=schemas.ExternalFavoriteSyncResponse)
def sync_wnacg_favorites(payload: schemas.ExternalFavoriteSyncRequest, db: Session = Depends(get_db)):
    if payload.source_id:
        source = get_source_or_404(payload.source_id, db)
        source.name = payload.name or source.name
        source.favorites_url = payload.favorites_url or source.favorites_url
        if payload.download_root_path is not None:
            source.download_root_path = payload.download_root_path.strip() or None
        if payload.cookie is not None:
            source.cookie = payload.cookie.strip()
    else:
        source = (
            db.query(models.ExternalFavoriteSource)
            .filter(
                models.ExternalFavoriteSource.source_type == "wnacg",
                models.ExternalFavoriteSource.favorites_url == payload.favorites_url,
            )
            .first()
        )
        if not source:
            source = models.ExternalFavoriteSource(
                source_type="wnacg",
                name=payload.name,
                favorites_url=payload.favorites_url,
                cookie=(payload.cookie or "").strip() or None,
                download_root_path=(payload.download_root_path or "").strip() or None,
            )
            db.add(source)
            db.flush()
        else:
            source.name = payload.name or source.name
            if payload.download_root_path is not None:
                source.download_root_path = payload.download_root_path.strip() or None
            if payload.cookie is not None:
                source.cookie = payload.cookie.strip() or None

    cookie = source.cookie or ""
    if not cookie:
        raise HTTPException(status_code=400, detail="请填写你自己账号的 Cookie 后再同步收藏页")

    source.status = "syncing"
    source.last_error = None
    db.commit()

    try:
        existing_items = {
            item.external_id: item
            for item in db.query(models.ExternalFavoriteItem).filter(models.ExternalFavoriteItem.source_id == source.id).all()
        }
        existing_external_ids = set(existing_items.keys())
        base_url = get_url_base(source.favorites_url)
        first_html = external_sources.fetch_html(source.favorites_url, cookie)
        categories = external_sources.parse_wnacg_categories(first_html)
        parsed_items: List[external_sources.ParsedExternalFavorite] = []

        if payload.category_id:
            categories = [category for category in categories if category.id == payload.category_id]
            if not categories:
                categories = [external_sources.WnacgCategory(id=payload.category_id, name=f"分类 {payload.category_id}")]

        if categories:
            for category in categories:
                for page in range(1, payload.page_limit + 1):
                    page_url = external_sources.wnacg_category_url(category.id, page, base_url=base_url)
                    page_html = external_sources.fetch_html(page_url, cookie)
                    page_items = external_sources.parse_wnacg_favorites(
                        page_html,
                        base_url=base_url,
                        category_id=category.id,
                        category_name=category.name,
                    )
                    parsed_items.extend(page_items)
                    if any(item.external_id in existing_external_ids for item in page_items):
                        break
                    if not external_sources.html_has_next_page(page_html):
                        break
        else:
            parsed_items = external_sources.parse_wnacg_favorites(first_html, base_url=base_url)

        now = datetime.utcnow()
        for db_item in existing_items.values():
            db_item.sync_position = None

        deduped = {item.external_id: item for item in parsed_items}
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
        db.refresh(source)

        items = (
            db.query(models.ExternalFavoriteItem)
            .filter(models.ExternalFavoriteItem.source_id == source.id)
            .order_by(
                models.ExternalFavoriteItem.sync_position.is_(None),
                models.ExternalFavoriteItem.sync_position.asc(),
                models.ExternalFavoriteItem.id.desc(),
            )
            .all()
        )
        return {"source": source, "synced_count": len(deduped), "items": [serialize_external_favorite_item(item, db) for item in items]}
    except HTTPException:
        source.status = "error"
        source.last_error = "同步失败"
        db.commit()
        raise
    except Exception as exc:
        source.status = "error"
        source.last_error = str(exc)
        db.commit()
        raise HTTPException(status_code=502, detail=f"同步 WNACG 收藏失败：{exc}")


@app.get("/external/favorites/{favorite_id}/cover")
def get_external_favorite_cover(favorite_id: int, db: Session = Depends(get_db)):
    item = db.query(models.ExternalFavoriteItem).filter(models.ExternalFavoriteItem.id == favorite_id).first()
    if not item:
        raise HTTPException(status_code=404, detail="External favorite not found")
    if not item.cover_url:
        raise HTTPException(status_code=404, detail="Cover not found")

    source = get_source_or_404(item.source_id, db)
    try:
        cached_cover = ensure_external_cover_cache(item, source)
        if cached_cover and os.path.exists(cached_cover):
            return FileResponse(cached_cover)
        raise RuntimeError("封面缓存失败")
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"读取外部封面失败：{exc}")


@app.post("/external/wnacg/downloads", response_model=schemas.ExternalDownloadJob)
def create_wnacg_download_job(
    payload: schemas.ExternalDownloadRequest,
    background_tasks: BackgroundTasks,
):
    download_root_path = payload.download_root_path.strip()
    if not download_root_path:
        raise HTTPException(status_code=400, detail="请先设置下载位置")

    job_id = str(uuid.uuid4())
    DOWNLOAD_JOBS[job_id] = {
        "job_id": job_id,
        "status": "running",
        "total": len(payload.item_ids),
        "completed": 0,
        "failed": 0,
        "message": "准备下载",
        "pages_total": 0,
        "pages_done": 0,
        "bytes_total": 0,
        "downloaded_bytes": 0,
        "bytes_total_known": False,
        "unknown_size_files": 0,
        "cancel_requested": False,
        "current_book_title": "",
        "current_book_total_pages": 0,
        "current_book_downloaded_pages": 0,
        "tasks": [
            {
                "id": str(item_id),
                "item_id": item_id,
                "title": "",
                "status": "pending",
                "total_pages": 0,
                "downloaded_pages": 0,
                "error": None,
            }
            for item_id in payload.item_ids
        ],
        "results": [],
    }
    background_tasks.add_task(run_wnacg_download_job, job_id, payload.item_ids, download_root_path)
    return DOWNLOAD_JOBS[job_id]


@app.post("/external/downloads/{job_id}/cancel", response_model=schemas.ExternalDownloadJob)
def cancel_external_download_job(job_id: str):
    job = DOWNLOAD_JOBS.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Download job not found")

    if job["status"] in {"completed", "failed", "canceled"}:
        return job

    job["cancel_requested"] = True
    job["status"] = "canceling"
    job["message"] = "正在取消下载"
    return job


@app.get("/external/downloads/{job_id}", response_model=schemas.ExternalDownloadJob)
def get_external_download_job(job_id: str):
    job = DOWNLOAD_JOBS.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Download job not found")
    return job


@app.get("/stream/{media_id}")
def stream_media(request: Request, media_id: int, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)

    if not os.path.exists(media.absolute_path):
        if not media.is_missing:
            media.is_missing = True
            media.missing_since = datetime.utcnow()
            db.commit()
        raise HTTPException(status_code=404, detail="File not found on disk")
    elif media.is_missing:
        media.is_missing = False
        media.missing_since = None
        db.commit()

    return get_ranged_file_response(request, media.absolute_path)


@app.get("/mobile/stream/{media_id}")
def stream_mobile_media(request: Request, media_id: int, _: models.User = Depends(auth.get_current_user), db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    if not os.path.exists(media.absolute_path):
        if not media.is_missing:
            media.is_missing = True
            media.missing_since = datetime.utcnow()
            db.commit()
        raise HTTPException(status_code=404, detail="File not found on disk")
    elif media.is_missing:
        media.is_missing = False
        media.missing_since = None
        db.commit()

    return get_ranged_file_response(request, media.absolute_path)


# ============================================================================
# /audio/{id}/* — ASMR audio player endpoints
# ============================================================================
# Consumer: android-app/audio/AudioRepository.kt. Web has no audio view yet.
# Auth: kept open per AudioRepository's contract ("the audio streaming
# endpoints are unauthenticated server-side ... we still pass the token in
# the stream URL ... ignored harmlessly today"). The token query param is
# ignored because the route signatures don't declare it.

def _get_audio_media_or_404(media_id: int, db: Session) -> models.Media:
    media = get_media_or_404(media_id, db)
    if media.media_type != "audio":
        raise HTTPException(status_code=400, detail="This media is not an audio work")
    if not os.path.exists(media.absolute_path):
        if not media.is_missing:
            media.is_missing = True
            media.missing_since = datetime.utcnow()
            db.commit()
        raise HTTPException(status_code=404, detail="Audio file/folder not found on disk")
    return media


def _resolve_audio_tracks(media: models.Media) -> List[dict]:
    """Return the in-display-order track list for an audio Media row.

    Two shapes are supported under the same `media_type='audio'`:
      - work folder (`extension='.dir'`, ASMR downloads or audio_work scans):
        prefer the tracks.json manifest at the work root (canonical order,
        clean titles, per-track durations); fall back to a directory walk if
        the manifest is missing.
      - single file (scanner's `scan_mode='audio'` route): the row IS the
        track — synthesize a one-entry list with its sidecar lyrics, if any.

    Keeping both shapes behind one resolver lets /audio/{id}/track/{i} and
    /audio/{id}/track/{i}/lyrics stay symmetric for the consumer
    (AudioRepository.kt / MediaDetail.vue) without branching them."""
    if os.path.isdir(media.absolute_path):
        manifest = scanner.read_tracks_json(media.absolute_path)
        if manifest and isinstance(manifest.get("tracks"), list):
            # The work directory is the security boundary. Anything resolved
            # from manifest entries must stay inside it — otherwise a tampered
            # tracks.json could turn /audio/{id}/track/{i} into an arbitrary
            # file reader (the streaming response doesn't care what it serves).
            work_root_abs = os.path.realpath(media.absolute_path)
            out: List[dict] = []
            for entry in manifest["tracks"]:
                if not isinstance(entry, dict):
                    continue
                rel = entry.get("rel") or ""
                if not rel:
                    continue
                abs_path = os.path.realpath(os.path.join(media.absolute_path, *rel.split("/")))
                # Path traversal guard: realpath() resolves "..", symlinks,
                # double-separators etc. Reject anything that doesn't sit
                # under work_root_abs (the +sep prevents the classic
                # "/work_root_evil" sibling-prefix bypass).
                if not (abs_path == work_root_abs or abs_path.startswith(work_root_abs + os.sep)):
                    continue
                if not os.path.exists(abs_path):
                    continue
                stem, _ = os.path.splitext(abs_path)
                lyrics_abs = None
                for lyric_ext in LYRIC_EXTS:
                    candidate = stem + lyric_ext
                    if os.path.exists(candidate):
                        lyrics_abs = candidate
                        break
                out.append({
                    "index": entry.get("index") if isinstance(entry.get("index"), int) else (len(out) + 1),
                    "title": entry.get("title") or os.path.basename(abs_path),
                    "rel": rel,
                    "abs_path": abs_path,
                    "lyrics_abs": lyrics_abs,
                    "duration": entry.get("duration") if isinstance(entry.get("duration"), (int, float)) else None,
                })
            if out:
                return out
        # Manifest missing or empty / all entries unresolved — fall back.
        return scan_audio_tracks(media.absolute_path)

    parent = os.path.dirname(media.absolute_path)
    stem = os.path.splitext(os.path.basename(media.absolute_path))[0]
    lyrics_abs = None
    for lyric_ext in LYRIC_EXTS:
        candidate = os.path.join(parent, stem + lyric_ext)
        if os.path.exists(candidate):
            lyrics_abs = candidate
            break
    return [{
        "index": 1,
        "title": os.path.basename(media.absolute_path),
        "rel": os.path.basename(media.absolute_path),
        "abs_path": media.absolute_path,
        "lyrics_abs": lyrics_abs,
        "duration": None,
    }]


def _audio_lyrics_rel(track: dict, media: models.Media) -> Optional[str]:
    """Resolve a track's lyrics path into a path relative to whichever anchor
    makes sense (the work folder, or the parent dir for single-file audio)."""
    if not track.get("lyrics_abs"):
        return None
    anchor = media.absolute_path if os.path.isdir(media.absolute_path) else os.path.dirname(media.absolute_path)
    return os.path.relpath(track["lyrics_abs"], anchor).replace(os.sep, "/")


@app.get("/audio/{media_id}/tracks")
def get_audio_tracks(media_id: int, db: Session = Depends(get_db)):
    media = _get_audio_media_or_404(media_id, db)
    tracks = _resolve_audio_tracks(media)
    return {
        "tracks": [
            {
                "index": t["index"],
                "title": t["title"],
                "rel": t["rel"],
                # Duration comes from tracks.json when present; otherwise null
                # and the client probes it on load (HTML5 audio / ExoPlayer
                # both tolerate null gracefully).
                "duration": t.get("duration"),
                # Client only checks isNotBlank(); the rel path doubles as a
                # human-readable marker.
                "lyrics": _audio_lyrics_rel(t, media),
            }
            for t in tracks
        ],
    }


@app.get("/audio/{media_id}/track/{index}")
def stream_audio_track(media_id: int, index: int, request: Request, db: Session = Depends(get_db)):
    media = _get_audio_media_or_404(media_id, db)
    tracks = _resolve_audio_tracks(media)
    if index < 1 or index > len(tracks):
        raise HTTPException(status_code=404, detail="Track index out of range")
    return get_ranged_file_response(request, tracks[index - 1]["abs_path"])


@app.get("/audio/{media_id}/track/{index}/lyrics")
def get_audio_track_lyrics(media_id: int, index: int, db: Session = Depends(get_db)):
    media = _get_audio_media_or_404(media_id, db)
    tracks = _resolve_audio_tracks(media)
    if index < 1 or index > len(tracks):
        raise HTTPException(status_code=404, detail="Track index out of range")
    lyrics_path = tracks[index - 1]["lyrics_abs"]
    # Guaranteed 200 with empty `lines` when no sidecar exists.
    return {"lines": parse_lyrics_file(lyrics_path) if lyrics_path else []}


@app.get("/manga/{media_id}/pages")
def get_manga_pages_count(media_id: int, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    if media.media_type != "manga":
        return {"total_pages": 0}

    try:
        total_pages = len(get_manga_image_files(media))
        media.page_count = total_pages
        db.commit()
        return {"total_pages": total_pages}
    except Exception:
        return {"total_pages": media.page_count or 0}


@app.get("/mobile/manga/{media_id}/pages")
def get_mobile_manga_pages_count(media_id: int, _: models.User = Depends(auth.get_current_user), db: Session = Depends(get_db)):
    return get_manga_pages_count(media_id, db)


@app.get("/manga/{media_id}/page/{page_index}")
def get_manga_page(
    media_id: int,
    page_index: int,
    track_progress: bool = False,
    db: Session = Depends(get_db),
):
    media = get_media_or_404(media_id, db)
    if media.media_type != "manga":
        raise HTTPException(status_code=404, detail="Manga not found")

    if media.is_missing and os.path.exists(media.absolute_path):
        media.is_missing = False
        media.missing_since = None
        db.commit()

    try:
        files = get_manga_image_files(media)
        if not 0 <= page_index < len(files):
            raise HTTPException(status_code=404, detail="Page not found")

        if track_progress:
            media.last_opened_at = datetime.utcnow()
            media.progress = page_index
            if page_index >= len(files) - 1:
                media.view_status = "viewed"
            elif page_index > 0:
                media.view_status = "viewing"
            elif media.view_status == "unviewed":
                media.view_status = "viewing"
            db.commit()

        if media.extension == ".dir":
            img_path = files[page_index]
            with open(img_path, "rb") as f:
                content = f.read()
            mime, _ = mimetypes.guess_type(img_path)
            return Response(content=content, media_type=mime or "application/octet-stream")

        with zipfile.ZipFile(media.absolute_path, "r") as archive:
            filename = files[page_index]
            with archive.open(filename) as f:
                content = f.read()
            mime, _ = mimetypes.guess_type(filename)
            return Response(content=content, media_type=mime or "application/octet-stream")
    except HTTPException:
        raise
    except Exception as e:
        # Don't echo the raw exception — it can carry absolute filesystem paths
        # (zip member names, on-disk locations) that we deliberately keep out of
        # the API surface. Log server-side, return a generic message.
        print(f"  ! Failed to serve manga page {media_id}/{page_index}: {e}")
        raise HTTPException(status_code=500, detail="Failed to read page")


@app.post("/media/{media_id}/regenerate-thumbnail")
def regenerate_thumbnail(media_id: int, background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    if media.media_type != "video":
        raise HTTPException(status_code=400, detail="Only videos support thumbnail regeneration")

    background_tasks.add_task(do_regenerate_thumbnail, media.id)
    return {"message": "Thumbnail regeneration task started"}


def do_regenerate_thumbnail(media_id: int):
    db = database.SessionLocal()
    try:
        media = db.query(models.Media).filter(models.Media.id == media_id).first()
        if not media:
            return

        thumbnail_dir = os.path.join(os.getcwd(), ".thumbnails")
        file_hash = hashlib.md5(media.absolute_path.encode()).hexdigest()[:12]
        base_name = f"thumb_v_{file_hash}_{datetime.now().timestamp()}".replace(' ', '_')
        thumb_name = f"{base_name}.jpg"
        thumb_path = os.path.join(thumbnail_dir, thumb_name)

        success, t_ms, source = scanner.get_video_thumbnail(media.absolute_path, thumb_path)
        if success:
            # Delete old thumbnail if it exists
            if media.cover_path:
                old_path = os.path.join(thumbnail_dir, media.cover_path)
                if os.path.exists(old_path):
                    try:
                        os.remove(old_path)
                    except Exception:
                        pass

            media.cover_path = thumb_name
            media.cover_time_ms = t_ms
            media.cover_source = source
            db.commit()
    finally:
        db.close()



@app.get("/mobile/manga/{media_id}/page/{page_index}")
def get_mobile_manga_page(
    media_id: int,
    page_index: int,
    track_progress: bool = False,
    _: models.User = Depends(auth.get_current_user),
    db: Session = Depends(get_db),
):
    return get_manga_page(media_id, page_index, track_progress, db)


# ----- X (Twitter) one-click import -----

from fastapi import File, Form, UploadFile  # noqa: E402  (grouped with the X endpoints)


def _get_or_create_x_source(db: Session) -> models.XImportSource:
    source = db.query(models.XImportSource).order_by(models.XImportSource.id.asc()).first()
    if source:
        return source
    source = models.XImportSource(name="X 喜欢导入")
    db.add(source)
    db.commit()
    db.refresh(source)
    return source


def _x_source_or_404(source_id: int, db: Session) -> models.XImportSource:
    source = db.query(models.XImportSource).filter(models.XImportSource.id == source_id).first()
    if not source:
        raise HTTPException(status_code=404, detail="X 导入数据源不存在")
    return source


def _x_import_stats(source_id: int, db: Session) -> dict:
    base = db.query(models.XPost).filter(models.XPost.source_id == source_id)
    total = base.count()
    completed = base.filter(models.XPost.status == "completed").count()
    failed = base.filter(models.XPost.status == "failed").count()
    skipped = base.filter(models.XPost.status == "skipped").count()
    pending = base.filter(models.XPost.status.in_(["pending", "fetched", "downloading"])).count()
    media_query = (
        db.query(models.XMediaItem)
        .join(models.XPost, models.XPost.id == models.XMediaItem.post_id)
        .filter(models.XPost.source_id == source_id)
    )
    total_media = media_query.count()
    downloaded_media = media_query.filter(models.XMediaItem.status == "downloaded").count()
    return {
        "total_posts": total,
        "completed_posts": completed,
        "failed_posts": failed,
        "skipped_posts": skipped,
        "pending_posts": pending,
        "total_media": total_media,
        "downloaded_media": downloaded_media,
    }


@app.get("/x/sources", response_model=List[schemas.XImportSource])
def list_x_sources(db: Session = Depends(get_db)):
    if db.query(models.XImportSource).count() == 0:
        _get_or_create_x_source(db)
    return db.query(models.XImportSource).order_by(models.XImportSource.id.asc()).all()


@app.patch("/x/sources/{source_id}", response_model=schemas.XImportSource)
def update_x_source(source_id: int, payload: schemas.XImportSourceUpdate, db: Session = Depends(get_db)):
    source = _x_source_or_404(source_id, db)
    data = payload.dict(exclude_unset=True)
    if "name" in data and data["name"]:
        source.name = data["name"].strip() or source.name
    if "cookie" in data:
        source.cookie = (data["cookie"] or "").strip() or None
    if "download_root_path" in data:
        path = (data["download_root_path"] or "").strip() or None
        if path:
            try:
                normalized = x_storage.normalize_root(path)
            except ValueError:
                raise HTTPException(status_code=400, detail="下载路径无效")
            source.download_root_path = normalized
            os.makedirs(x_storage.x_root_dir(normalized), exist_ok=True)
        else:
            source.download_root_path = None
    db.commit()
    db.refresh(source)
    return source


@app.get("/x/sources/{source_id}/stats", response_model=schemas.XImportStats)
def get_x_source_stats(source_id: int, db: Session = Depends(get_db)):
    _x_source_or_404(source_id, db)
    return _x_import_stats(source_id, db)


@app.get("/x/sources/{source_id}/posts", response_model=List[schemas.XPost])
def list_x_posts(
    source_id: int,
    status: Optional[str] = None,
    limit: int = 100,
    db: Session = Depends(get_db),
):
    _x_source_or_404(source_id, db)
    query = db.query(models.XPost).filter(models.XPost.source_id == source_id)
    if status:
        query = query.filter(models.XPost.status == status)
    return (
        query.order_by(models.XPost.discovered_at.desc(), models.XPost.id.desc())
        .limit(max(1, min(limit, 500)))
        .all()
    )


@app.post("/x/sources/{source_id}/archive", response_model=schemas.XImportArchiveUploadResponse)
def upload_x_archive(
    source_id: int,
    file: UploadFile = File(...),
    download_root_path: Optional[str] = Form(default=None),
    db: Session = Depends(get_db),
):
    source = _x_source_or_404(source_id, db)

    if download_root_path is not None:
        trimmed = download_root_path.strip()
        if trimmed:
            source.download_root_path = x_storage.normalize_root(trimmed)
            db.commit()

    safe_name = re.sub(r"[^A-Za-z0-9._-]", "_", file.filename or "archive.zip")
    saved_name = f"{int(time.time())}_{safe_name}"
    saved_path = os.path.join(X_ARCHIVE_UPLOAD_DIR, saved_name)
    with open(saved_path, "wb") as out:
        shutil.copyfileobj(file.file, out)

    try:
        likes = x_archive.parse_likes_from_zip(saved_path)
    except Exception as exc:
        try:
            os.remove(saved_path)
        except OSError:
            pass
        raise HTTPException(status_code=400, detail=f"解析归档失败：{exc}")

    new_count = 0
    existing_count = 0
    for like in likes:
        existing = (
            db.query(models.XPost)
            .filter(models.XPost.source_id == source.id, models.XPost.tweet_id == like.tweet_id)
            .first()
        )
        if existing:
            existing_count += 1
            if not existing.full_text and like.full_text:
                existing.full_text = like.full_text
            existing.archive_name = file.filename or saved_name
            continue
        post = models.XPost(
            source_id=source.id,
            tweet_id=like.tweet_id,
            url=like.url,
            author_screen_name=like.author_screen_name,
            full_text=like.full_text,
            archive_name=file.filename or saved_name,
            status="pending",
        )
        db.add(post)
        new_count += 1

    source.last_archive_name = file.filename or saved_name
    source.last_archive_imported_at = datetime.utcnow()
    db.commit()
    db.refresh(source)

    return {
        "source": source,
        "archive_name": source.last_archive_name,
        "parsed": len(likes),
        "new_posts": new_count,
        "existing_posts": existing_count,
        "stats": _x_import_stats(source.id, db),
    }


@app.post("/x/imports", response_model=schemas.XImportJob)
def start_x_import(payload: schemas.XImportStartRequest, db: Session = Depends(get_db)):
    source = _x_source_or_404(payload.source_id, db)
    if not source.download_root_path:
        raise HTTPException(status_code=400, detail="请先设置下载位置")

    existing = x_importer.latest_job_for_source(source.id)
    if existing and existing.status in {"queued", "preparing", "running", "paused"}:
        raise HTTPException(status_code=409, detail="已有正在进行的导入任务")

    post_ids = x_importer.select_pending_post_ids(
        db,
        source.id,
        retry_failed_only=payload.retry_failed_only,
        retry_skipped_only=payload.retry_skipped_only,
    )
    job_id = str(uuid.uuid4())
    job = x_importer.start_job(
        job_id=job_id,
        source_id=source.id,
        download_root=source.download_root_path,
        thumbnail_dir=THUMBNAIL_DIR,
        post_ids=post_ids,
        cookie=source.cookie,
    )
    return job.to_dict()


@app.get("/x/imports/{job_id}", response_model=schemas.XImportJob)
def get_x_import_job(job_id: str):
    job = x_importer.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="导入任务不存在或已被清理")
    return job.to_dict()


@app.get("/x/sources/{source_id}/active-job", response_model=Optional[schemas.XImportJob])
def get_x_active_job(source_id: int):
    job = x_importer.latest_job_for_source(source_id)
    return job.to_dict() if job else None


@app.post("/x/imports/{job_id}/pause", response_model=schemas.XImportJob)
def pause_x_import_job(job_id: str):
    job = x_importer.request_pause(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="导入任务不存在")
    return job.to_dict()


@app.post("/x/imports/{job_id}/resume", response_model=schemas.XImportJob)
def resume_x_import_job(job_id: str):
    job = x_importer.request_resume(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="导入任务不存在")
    return job.to_dict()


@app.post("/x/imports/{job_id}/cancel", response_model=schemas.XImportJob)
def cancel_x_import_job(job_id: str):
    job = x_importer.request_cancel(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="导入任务不存在")
    return job.to_dict()


@app.post("/x/sources/{source_id}/sync", response_model=schemas.XSyncJob)
def start_x_sync(source_id: int, db: Session = Depends(get_db)):
    source = _x_source_or_404(source_id, db)
    if not source.cookie:
        raise HTTPException(status_code=400, detail="请先保存账号 cookie 再使用直接同步")

    existing = x_sync.latest_sync_for_source(source.id)
    if existing and existing.status in ("queued", "running"):
        raise HTTPException(status_code=409, detail="已有同步任务在进行")

    job = x_sync.start_sync(source_id=source.id, cookie=source.cookie)
    return job.to_dict()


@app.get("/x/syncs/{job_id}", response_model=schemas.XSyncJob)
def get_x_sync_job(job_id: str):
    job = x_sync.get_sync(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="同步任务不存在或已被清理")
    return job.to_dict()


@app.get("/x/sources/{source_id}/active-sync", response_model=Optional[schemas.XSyncJob])
def get_x_active_sync(source_id: int):
    job = x_sync.latest_sync_for_source(source_id)
    return job.to_dict() if job else None


@app.post("/x/syncs/{job_id}/cancel", response_model=schemas.XSyncJob)
def cancel_x_sync_job(job_id: str):
    job = x_sync.request_cancel(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="同步任务不存在")
    return job.to_dict()


# ----- Local-file deduplication -----


def _serialize_dedup_media(media: models.Media) -> dict:
    return {
        "id": media.id,
        "title": media.title,
        "display_path": media.relative_path or media.title,
        "media_type": media.media_type,
        "extension": media.extension,
        "file_size": media.file_size,
        "cover_path": media.cover_path,
        "duration": media.duration,
        "width": media.width,
        "height": media.height,
        "page_count": media.page_count,
        "is_missing": bool(media.is_missing),
        "duplicate_status": media.duplicate_status or "unique",
        "favorite": bool(media.favorite),
        "rating": media.rating or 0,
        "source_url": media.source_url,
        "source_site": media.source_site,
    }


def _serialize_pair(pair: models.DuplicateCandidate, db: Session) -> Optional[dict]:
    existing = db.query(models.Media).filter(models.Media.id == pair.existing_media_id).first()
    candidate = db.query(models.Media).filter(models.Media.id == pair.candidate_media_id).first()
    if not existing or not candidate:
        return None
    return {
        "id": pair.id,
        "level": pair.level,
        "similarity": pair.similarity or 0,
        "reason": pair.reason,
        "status": pair.status,
        "created_at": pair.created_at,
        "resolved_at": pair.resolved_at,
        "resolution_note": pair.resolution_note,
        "existing": _serialize_dedup_media(existing),
        "candidate": _serialize_dedup_media(candidate),
    }


@app.get("/dedup/summary", response_model=schemas.DedupSummary)
def dedup_summary(db: Session = Depends(get_db)):
    pending_pairs = (
        db.query(models.DuplicateCandidate)
        .filter(models.DuplicateCandidate.status == "pending")
        .count()
    )
    base = db.query(models.Media)
    return {
        "pending_pairs": pending_pairs,
        "strong_duplicate": base.filter(models.Media.duplicate_status == "strong_duplicate").count(),
        "suspected_duplicate": base.filter(models.Media.duplicate_status == "suspected_duplicate").count(),
        "weak_suspected": base.filter(models.Media.duplicate_status == "weak_suspected").count(),
        "checking": base.filter(models.Media.duplicate_status == "checking").count(),
        "queue_size": dedup_worker.queue_size(),
        "worker_running": dedup_worker.is_running(),
    }


@app.get("/dedup/candidates", response_model=List[schemas.DuplicateCandidatePair])
def list_duplicate_candidates(
    level: Optional[str] = None,
    status: str = "pending",
    media_type: Optional[str] = None,
    limit: int = 200,
    db: Session = Depends(get_db),
):
    query = db.query(models.DuplicateCandidate)
    if status and status != "all":
        query = query.filter(models.DuplicateCandidate.status == status)
    if level:
        query = query.filter(models.DuplicateCandidate.level == level)
    pairs = (
        query.order_by(
            models.DuplicateCandidate.status.asc(),
            models.DuplicateCandidate.similarity.desc(),
            models.DuplicateCandidate.id.desc(),
        )
        .limit(max(1, min(limit, 500)))
        .all()
    )
    out: List[dict] = []
    for pair in pairs:
        serialized = _serialize_pair(pair, db)
        if not serialized:
            continue
        if media_type and serialized["existing"]["media_type"] != media_type:
            continue
        out.append(serialized)
    return out


@app.get("/dedup/candidates/{pair_id}", response_model=schemas.DuplicateCandidatePair)
def get_duplicate_candidate(pair_id: int, db: Session = Depends(get_db)):
    pair = db.query(models.DuplicateCandidate).filter(models.DuplicateCandidate.id == pair_id).first()
    if not pair:
        raise HTTPException(status_code=404, detail="重复条目不存在")
    serialized = _serialize_pair(pair, db)
    if not serialized:
        raise HTTPException(status_code=404, detail="对应媒体已不存在")
    return serialized


_DEDUP_ACTIONS = {
    dedup_merge.ACTION_KEEP_EXISTING,
    dedup_merge.ACTION_REPLACE_PATH,
    dedup_merge.ACTION_KEEP_BOTH,
    dedup_merge.ACTION_IGNORE,
}


@app.post("/dedup/candidates/{pair_id}/resolve", response_model=schemas.DuplicateCandidatePair)
def resolve_duplicate_candidate(
    pair_id: int,
    payload: schemas.DedupActionRequest,
    db: Session = Depends(get_db),
):
    if payload.action not in _DEDUP_ACTIONS:
        raise HTTPException(status_code=400, detail="不支持的合并动作")
    pair = db.query(models.DuplicateCandidate).filter(models.DuplicateCandidate.id == pair_id).first()
    if not pair:
        raise HTTPException(status_code=404, detail="重复条目不存在")
    if pair.status != "pending":
        raise HTTPException(status_code=409, detail="该条目已被处理过")

    pair = dedup_merge.apply_action(db, pair, payload.action, note=payload.note)
    db.refresh(pair)
    serialized = _serialize_pair(pair, db)
    if not serialized:
        raise HTTPException(status_code=404, detail="处理后媒体已不存在")
    return serialized


@app.post("/dedup/media/{media_id}/recheck")
def recheck_media_dedup(media_id: int, db: Session = Depends(get_db)):
    media = db.query(models.Media).filter(models.Media.id == media_id).first()
    if not media:
        raise HTTPException(status_code=404, detail="Media not found")
    media.duplicate_status = "checking"
    db.commit()
    dedup_worker.enqueue([media.id])
    return {"queued": True, "media_id": media.id}


@app.delete("/dedup/media/{media_id}/file")
def delete_media_file(
    media_id: int,
    payload: schemas.DedupDeleteFileRequest,
    db: Session = Depends(get_db),
):
    if not payload.confirm:
        raise HTTPException(status_code=400, detail="请通过 confirm=true 二次确认")
    media = db.query(models.Media).filter(models.Media.id == media_id).first()
    if not media:
        raise HTTPException(status_code=404, detail="Media not found")

    file_deleted = False
    target_path = media.absolute_path
    if target_path and os.path.exists(target_path):
        try:
            if os.path.isdir(target_path):
                shutil.rmtree(target_path)
            else:
                os.remove(target_path)
            file_deleted = True
        except OSError as exc:
            raise HTTPException(status_code=500, detail=f"文件删除失败：{exc}")

    # Same FK cleanup as DELETE /media/{id}: SQLite foreign_keys=ON would otherwise
    # block deletion when x_media_items or duplicate_candidates still reference this row.
    media_cleanup.detach_media_references(db, [media.id])

    fp = db.query(models.MediaFingerprint).filter(models.MediaFingerprint.media_id == media.id).first()
    if fp:
        db.delete(fp)
    db.delete(media)
    db.commit()
    return {"file_deleted": file_deleted, "media_id": media_id}


# ============================================================================
# Dashboard statistics — backed by app/stats.py (pure read-only aggregations)
# ============================================================================
# Frontend: StatsView.vue fetches all four on mount + on refresh click. Each
# function below is a thin wrapper around stats.<name>(db); no Pydantic model
# because the response is already a dict that FastAPI auto-JSON-encodes, and
# the shape evolves with the dashboard (formalising it would create churn).

@app.get("/stats/overview")
def stats_overview(db: Session = Depends(get_db)):
    return stats_mod.overview(db)


@app.get("/stats/distribution")
def stats_distribution(db: Session = Depends(get_db)):
    return stats_mod.distribution(db)


@app.get("/stats/activity")
def stats_activity(days: int = 365, db: Session = Depends(get_db)):
    # Default 365 matches StatsView's heatmap which renders a year's worth of
    # buckets. Cap upper bound so a stray ?days=99999 can't lock the table.
    days = max(1, min(days, 730))
    return stats_mod.activity(db, days=days)


@app.get("/stats/attention")
def stats_attention(db: Session = Depends(get_db)):
    return stats_mod.attention(db)


@app.get("/stats/highlights")
def stats_highlights(limit: int = 10, db: Session = Depends(get_db)):
    # Bundles top creators / longest videos / hottest tags so the dashboard
    # only needs one extra request for the "highlights" row.
    return stats_mod.highlights(db, limit=limit)


# ============================================================================
# Creators — unified X authors + manga artists, backed by app/creators.py
# ============================================================================
# Frontend: CreatorsView.vue lists via /creators and opens detail via
# /creators/{screen_name}. The vue-only detail path assumes X authors
# (manga artists have no `screen_name`, so they aren't clickable in the UI).
# Android (Wave 5 of the structural refactor) uses /mobile/creators and
# /mobile/creators/detail?key=... which support both kinds via the unified
# `key` ("x:<sn>" or "a:<artist>").

@app.get("/creators")
def list_creators(
    search: Optional[str] = None,
    sort: str = "count",
    media_type: Optional[str] = None,
    db: Session = Depends(get_db),
):
    return creators_mod.list_creators(db, search=search, sort=sort, media_type=media_type)


@app.get("/creators/{screen_name}")
def creator_detail_by_sn(screen_name: str, db: Session = Depends(get_db)):
    # The vue UI only routes here for X authors (its <router-link> uses
    # creator.screen_name). Manga-artist detail goes through /mobile/creators/detail.
    detail = creators_mod.creator_detail(db, f"x:{screen_name}")
    if detail is None:
        raise HTTPException(status_code=404, detail="creator not found")
    return detail


@app.get("/mobile/creators")
def mobile_list_creators(
    kind: Optional[str] = None,
    search: Optional[str] = None,
    sort: str = "count",
    db: Session = Depends(get_db),
):
    # Android client sends `kind` ('all'/'manga'/'image'/'video'/'audio'); the
    # module's list_creators takes `media_type`. 'all' or empty → no filter;
    # 'manga' → manga artists only (X authors will return zero); 'image'/'video'
    # → X authors filtered by that media_type. ('audio' currently has no creator
    # source, so returns empty — kept in the enum for forward compatibility.)
    media_type = None if not kind or kind == "all" else kind
    return creators_mod.list_creators(db, search=search, sort=sort, media_type=media_type)


@app.get("/mobile/creators/detail")
def mobile_creator_detail(key: str, db: Session = Depends(get_db)):
    # Android sends the full unified key ("x:<sn>" or "a:<artist>") so this is
    # a direct passthrough — no x:-prefix assumption like the vue endpoint above.
    detail = creators_mod.creator_detail(db, key)
    if detail is None:
        raise HTTPException(status_code=404, detail="creator not found")
    return detail


# ============================================================================
# ASMR.one — mirror probe, favorites sync, downloads
# ============================================================================
# Frontend: AsmrPanel.vue. The three endpoints here form one user flow:
#   1. /external/asmr/mirrors/ping  — find a reachable mirror
#   2. /external/asmr/sync          — login + pull marked/playlist works into
#                                     the ExternalFavoriteItem table
#   3. /external/asmr/downloads     — fetch selected works to disk and
#                                     register them as audio Media rows
# Mirror probe is pure network (no DB writes); sync + downloads persist via
# ExternalFavoriteSource rows with source_type='asmr', where source.cookie
# holds the bearer token (raw password is never stored).

@app.post("/external/asmr/mirrors/ping")
def asmr_ping_mirrors(payload: dict = None):
    payload = payload or {}
    api_base = (payload.get("api_base") or "").strip()
    raw_mirrors = payload.get("api_mirrors") or ""
    bases = asmr_source.candidate_bases(
        preferred=api_base if api_base else None,
        mirrors=asmr_source.parse_mirrors(raw_mirrors) if raw_mirrors else None,
    )
    # Dedupe while keeping the preferred-first order (candidate_bases already
    # does this, but be defensive against future changes).
    seen = set()
    ordered = []
    for b in bases:
        if b not in seen:
            seen.add(b)
            ordered.append(b)
    return {"results": [asmr_source.ping_mirror(b) for b in ordered]}


@app.post("/external/asmr/recheck-covers")
def asmr_recheck_covers(db: Session = Depends(get_db)):
    """Backfill cover thumbnails for audio Media rows downloaded before the
    cover step existed in the pipeline.

    For each audio work folder without a cover_path:
      1. Look for an existing sidecar image (cover.jpg, etc.) — covers some
         users will have copied in manually.
      2. Fall back to the ExternalFavoriteItem.cover_url (paired by source_url),
         download it next to the audio, then run the same scanner helpers as
         the download pipeline does.

    Idempotent: rows that already have cover_path are skipped. Returns counts
    so the UI can show a "fixed N / M" toast."""
    rows = db.query(models.Media).filter(
        models.Media.media_type == "audio",
        models.Media.cover_path.is_(None),
    ).all()

    checked = 0
    fixed = 0
    fetched_remote = 0
    failed = 0

    for media in rows:
        if not media.absolute_path or not os.path.isdir(media.absolute_path):
            continue
        checked += 1
        item_dir = media.absolute_path

        cover_src = scanner.get_work_cover_path(item_dir)

        if not cover_src and media.source_url:
            # Reverse-link to the ExternalFavoriteItem so we know the remote
            # cover URL. ASMR items are matched by their work page URL.
            item = (
                db.query(models.ExternalFavoriteItem)
                .filter(
                    models.ExternalFavoriteItem.url == media.source_url,
                    models.ExternalFavoriteItem.source_type == "asmr",
                )
                .first()
            )
            cover_url = (item.cover_url or "").strip() if item else ""
            if cover_url:
                try:
                    content, content_type = asmr_source.fetch_file(cover_url)
                    ext = get_cover_extension(content_type, cover_url)
                    cover_dst = os.path.join(item_dir, f"cover{ext}")
                    with open(cover_dst, "wb") as cover_file:
                        cover_file.write(content)
                    cover_src = cover_dst
                    fetched_remote += 1
                except Exception as exc:  # noqa: BLE001
                    print(f"  ! recheck-covers: remote fetch failed for {media.title!r}: {exc}")
                    failed += 1
                    continue

        if not cover_src:
            continue

        digest = hashlib.md5(item_dir.encode("utf-8")).hexdigest()[:12]
        thumb_name = f"thumb_audio_{digest}_{int(datetime.now().timestamp())}.jpg"
        thumb_path = os.path.join(THUMBNAIL_DIR, thumb_name)
        if scanner.make_work_thumbnail(cover_src, thumb_path):
            media.cover_path = thumb_name
            fixed += 1
        else:
            failed += 1

    db.commit()
    return {
        "checked": checked,
        "fixed": fixed,
        "fetched_remote": fetched_remote,
        "failed": failed,
    }


@app.post("/external/asmr/sync", response_model=schemas.ExternalFavoriteSyncResponse)
def sync_asmr_favorites(payload: schemas.AsmrSyncRequest, db: Session = Depends(get_db)):
    # Mirrors WNACG's /external/wnacg/sync but with asmr.one specifics. The
    # source row is reused (source_type='asmr'); favorites_url=api_base and
    # cookie=bearer_token. We don't store the raw password — only exchange it
    # once for a token via asmr_source.login() and persist that.
    if payload.source_id:
        source = get_source_or_404(payload.source_id, db)
    else:
        # Match an existing asmr source on (source_type, api_base) to avoid
        # creating dupes when the user re-syncs with the same base.
        source = (
            db.query(models.ExternalFavoriteSource)
            .filter(
                models.ExternalFavoriteSource.source_type == "asmr",
                models.ExternalFavoriteSource.favorites_url == payload.api_base,
            )
            .first()
        )
        if not source:
            source = models.ExternalFavoriteSource(
                source_type="asmr",
                name=payload.name or "ASMR",
                favorites_url=payload.api_base,
            )
            db.add(source)
            db.flush()

    # Apply incoming config (everything except creds, which are handled below)
    source.name = payload.name or source.name
    source.favorites_url = payload.api_base or source.favorites_url
    source.api_mirrors = payload.api_mirrors if payload.api_mirrors is not None else source.api_mirrors
    source.audio_format_filter = payload.audio_format_filter or source.audio_format_filter or "all"
    source.audio_version_filter = payload.audio_version_filter or source.audio_version_filter or "all"
    source.playlist_url = payload.playlist_url if payload.playlist_url is not None else source.playlist_url
    if payload.download_root_path is not None:
        source.download_root_path = payload.download_root_path.strip() or None
    if payload.username:
        source.username = payload.username

    # Resolve token: if creds came in this request, login fresh (overwriting any
    # stale token). Otherwise reuse what's already stored.
    api_base = asmr_source.normalize_api_base(source.favorites_url)
    mirrors = asmr_source.parse_mirrors(source.api_mirrors) if source.api_mirrors else None
    token = source.cookie or ""

    if payload.password:
        # Need both for a fresh login. payload.username falls back to stored.
        login_name = (payload.username or source.username or "").strip()
        if not login_name:
            raise HTTPException(status_code=400, detail="登录需要用户名")
        try:
            # NB: login() returns (token, working_base) — token FIRST. An
            # earlier version of this call swapped the tuple, which silently
            # wrote the base URL into source.cookie (used as the bearer) and
            # the JWT into source.favorites_url. The result was every
            # subsequent /api/marks call sending `Authorization: Bearer
            # https://api.asmr-200.com`, getting back 401 + "invalid token",
            # and looping forever because the 401-handler kept clearing the
            # cookie and forcing a fresh — but still mis-stored — login.
            token, working_base = asmr_source.login(
                preferred_base=api_base,
                name=login_name,
                password=payload.password,
                mirrors=mirrors,
            )
        except asmr_source.AsmrApiError as exc:
            source.status = "error"
            source.last_error = f"登录失败：{exc}"
            db.commit()
            raise HTTPException(status_code=401, detail=f"asmr.one 登录失败：{exc}")
        # login() may have switched to a working mirror — persist the new base
        # so subsequent syncs start there.
        source.favorites_url = working_base
        source.cookie = token
        source.username = login_name
    elif not token:
        raise HTTPException(
            status_code=400,
            detail="尚未登录 asmr.one：请填写账号密码后再同步（密码只用于换取 token，不会存储）",
        )

    source.status = "syncing"
    source.last_error = None
    db.commit()

    try:
        # Choose source: explicit playlist URL takes precedence; otherwise pull
        # the user's "marked" works.
        if source.playlist_url:
            playlist_id = asmr_source.extract_playlist_id(source.playlist_url)
            parsed_works = asmr_source.fetch_playlist_works(
                preferred_base=source.favorites_url,
                token=token,
                playlist_id=playlist_id,
                page_limit=payload.page_limit,
                mirrors=mirrors,
            )
        else:
            parsed_works = asmr_source.fetch_marked_works(
                working_base=source.favorites_url,
                token=token,
                page_limit=payload.page_limit,
                mirrors=mirrors,
            )

        now = datetime.utcnow()
        existing_items = {
            item.external_id: item
            for item in db.query(models.ExternalFavoriteItem)
            .filter(models.ExternalFavoriteItem.source_id == source.id)
            .all()
        }
        # Mirror wnacg: blank all sync_positions first, then re-assign in the
        # order the API returned (newest-first marked or playlist sequence).
        # Items that fall out of the page window keep their row but lose
        # sync_position, so the UI's "currently in source" ordering reflects
        # only what we just saw.
        for db_item in existing_items.values():
            db_item.sync_position = None

        # ParsedAsmrWork attribute names (see asmr_source.py): external_id,
        # title, url, cover_url, category_name. The first cut of this code
        # used `rj_code` / `work_url` / `circle` everywhere — names that don't
        # exist on the dataclass — so the very first sync after login worked
        # blew up with AttributeError. Keeping the canonical names below.
        deduped = {w.external_id: w for w in parsed_works if w.external_id}
        for sync_position, work in enumerate(deduped.values()):
            db_item = existing_items.get(work.external_id)
            if not db_item:
                db_item = models.ExternalFavoriteItem(
                    source=source,
                    source_type="asmr",
                    external_id=work.external_id,
                    title=work.title or work.external_id,
                    url=work.url or "",
                    cover_url=work.cover_url,
                    # Reuse category_name for the circle (visible chip in UI);
                    # category_id stays NULL since asmr has no category system.
                    category_name=work.category_name or None,
                    sync_position=sync_position,
                    last_seen_at=now,
                )
                db.add(db_item)
            else:
                db_item.title = work.title or db_item.title
                db_item.url = work.url or db_item.url
                db_item.cover_url = work.cover_url or db_item.cover_url
                db_item.category_name = work.category_name or db_item.category_name
                db_item.sync_position = sync_position
                db_item.last_seen_at = now

        source.status = "ok"
        source.last_synced_at = now
        source.last_error = None
        db.commit()
        db.refresh(source)

        items = (
            db.query(models.ExternalFavoriteItem)
            .filter(models.ExternalFavoriteItem.source_id == source.id)
            .order_by(
                models.ExternalFavoriteItem.sync_position.is_(None),
                models.ExternalFavoriteItem.sync_position.asc(),
                models.ExternalFavoriteItem.id.desc(),
            )
            .all()
        )
        return {
            "source": source,
            "synced_count": len(deduped),
            "items": [serialize_external_favorite_item(item, db) for item in items],
        }
    except HTTPException:
        source.status = "error"
        source.last_error = "同步失败"
        db.commit()
        raise
    except asmr_source.AsmrApiError as exc:
        source.status = "error"
        source.last_error = f"API 错误：{exc}"
        # An expired/revoked bearer manifests as 401 + api_code='invalid token'.
        # Drop the stale token so the next sync attempt forces a fresh login
        # path instead of looping on the bad credential. The user has to enter
        # their password again — we don't store it, so this is the right cycle.
        if exc.status == 401:
            source.cookie = None
        db.commit()
        # 401 is auth, surface as 401 so the front-end can render it distinctly
        # from generic 502 network/parse errors.
        http_status = 401 if exc.status == 401 else 502
        raise HTTPException(status_code=http_status, detail=f"同步 ASMR 收藏失败：{exc}")
    except Exception as exc:
        source.status = "error"
        source.last_error = str(exc)
        db.commit()
        raise HTTPException(status_code=502, detail=f"同步 ASMR 收藏失败：{exc}")


@app.post("/external/asmr/downloads", response_model=schemas.ExternalDownloadJob)
def create_asmr_download_job(
    payload: schemas.ExternalDownloadRequest,
    background_tasks: BackgroundTasks,
):
    """ASMR sibling of /external/wnacg/downloads. Same request shape (item_ids
    + download_root_path), same shared job dict (DOWNLOAD_JOBS), so the poll
    + cancel endpoints (/external/downloads/{id}, .../cancel) work unchanged.
    Format / SE-version filters are read off the source row that sync stored,
    not from this payload — keeping the front-end's startDownload signature
    aligned with WNACG."""
    download_root_path = payload.download_root_path.strip()
    if not download_root_path:
        raise HTTPException(status_code=400, detail="请先设置下载位置")

    job_id = str(uuid.uuid4())
    DOWNLOAD_JOBS[job_id] = {
        "job_id": job_id,
        "status": "running",
        "total": len(payload.item_ids),
        "completed": 0,
        "failed": 0,
        "message": "准备下载",
        "pages_total": 0,
        "pages_done": 0,
        "bytes_total": 0,
        "downloaded_bytes": 0,
        "bytes_total_known": False,
        "unknown_size_files": 0,
        "cancel_requested": False,
        "current_book_title": "",
        "current_book_total_pages": 0,
        "current_book_downloaded_pages": 0,
        "tasks": [
            {
                "id": str(item_id),
                "item_id": item_id,
                "title": "",
                "status": "pending",
                "total_pages": 0,
                "downloaded_pages": 0,
                "error": None,
            }
            for item_id in payload.item_ids
        ],
        "results": [],
    }
    background_tasks.add_task(run_asmr_download_job, job_id, payload.item_ids, download_root_path)
    return DOWNLOAD_JOBS[job_id]


# ============================================================================
# 推给独立下载中心（HE_downloader gateway）—— 方向 B，与上面的内置下载并存
# ============================================================================
# HE 仍负责解析（cookie / 签名 URL 在这边），把文件清单作为一个分组任务 POST
# 给网关 /jobs/batch；文件落到库目录（dest_dir 走 /mnt/hdd/...），由下载中心的
# aria2 下载、续传、统一面板展示。下载中心完成后回调 HE，再复用内置下载的 upsert 入库逻辑。

def _external_downloader_callback_url(item_id: int, source_type: str) -> Optional[str]:
    if not HE_PUBLIC_URL or not HE_CALLBACK_TOKEN:
        return None
    query = urlencode({
        "item_id": item_id,
        "source_type": source_type or "wnacg",
        "token": HE_CALLBACK_TOKEN,
    })
    return f"{HE_PUBLIC_URL}/external/downloader/callback?{query}"


def _download_root_from_item_dir(item_dir: str, source: models.ExternalFavoriteSource) -> Optional[str]:
    if not item_dir:
        return source.download_root_path
    expected_bucket = "audio" if (source.source_type or "") == "asmr" else "manga"
    parent = os.path.dirname(os.path.abspath(item_dir))
    if os.path.basename(parent).lower() == expected_bucket:
        return os.path.dirname(parent)
    return source.download_root_path

def _push_external_items(payload, db, build):
    """把选中收藏逐条解析成 (item_dir, files) 并 push_batch。build(item, source, root)
    返回 (item_dir, [{url, rel_path, headers}])。"""
    if not downloader_push.is_configured():
        raise HTTPException(status_code=503, detail="未配置下载中心地址（HE_DOWNLOADER_URL）")
    root_override = (payload.download_root_path or "").strip()
    results = []
    for item_id in payload.item_ids:
        item = db.query(models.ExternalFavoriteItem).filter(models.ExternalFavoriteItem.id == item_id).first()
        if not item:
            results.append({"item_id": item_id, "status": "failed", "error": "条目不存在"})
            continue
        source = get_source_or_404(item.source_id, db)
        root = root_override or source.download_root_path
        if not root:
            results.append({"item_id": item_id, "title": item.title, "status": "failed", "error": "未设置下载位置"})
            continue
        try:
            item_dir, files = build(item, source, root)
            if not files:
                raise RuntimeError("没有可下载的文件")
            callback_url = _external_downloader_callback_url(item.id, source.source_type or "wnacg")
            job = downloader_push.push_batch(
                name=item.title,
                dest_dir=item_dir,
                files=files,
                callback_url=callback_url,
            )
            results.append({"item_id": item_id, "title": item.title, "status": "pushed",
                            "job_id": job.get("id"), "files": len(files), "dest_dir": item_dir})
        except Exception as exc:  # noqa: BLE001
            results.append({"item_id": item_id, "title": item.title, "status": "failed", "error": str(exc)})
    pushed = [r for r in results if r.get("status") == "pushed"]
    if not pushed and results:
        raise HTTPException(status_code=502, detail=results[0].get("error") or "推送失败")
    return {"pushed": len(pushed), "results": results}


@app.post("/external/downloader/callback")
def downloader_callback(payload: dict, item_id: int, source_type: str = "wnacg", db: Session = Depends(get_db)):
    event = (payload or {}).get("event")
    if event != "complete":
        return {"ok": True, "skipped": event}

    item = db.query(models.ExternalFavoriteItem).filter(models.ExternalFavoriteItem.id == item_id).first()
    if not item:
        raise HTTPException(status_code=404, detail="条目不存在")

    source = get_source_or_404(item.source_id, db)
    job = (payload or {}).get("job") or {}
    item_dir = job.get("dir") or external_item_download_dir(item, source)
    download_root_path = _download_root_from_item_dir(item_dir, source)
    if not download_root_path:
        raise HTTPException(status_code=400, detail="未设置下载位置")

    if (source.source_type or source_type or "") == "asmr":
        files = job.get("files") or []
        track_count = sum(
            1
            for file_info in files
            if os.path.splitext((file_info or {}).get("rel_path") or (file_info or {}).get("name") or "")[1].lower()
            in AUDIO_TRACK_EXTS
        )
        if track_count <= 0:
            track_count = len(scan_audio_tracks(item_dir)) if os.path.isdir(item_dir) else len(files)
        total_bytes = int(job.get("total_bytes") or job.get("completed_bytes") or 0)
        if total_bytes <= 0 and os.path.isdir(item_dir):
            total_bytes = scanner.directory_size(item_dir)
        local_media = upsert_external_downloaded_audio_media(
            item,
            source,
            item_dir,
            download_root_path,
            db,
            track_count=track_count,
            total_bytes=total_bytes,
        )
    else:
        ensure_wnacg_source_marker(item, item_dir)
        local_media = upsert_external_downloaded_media(item, source, item_dir, download_root_path, db)

    db.commit()
    return {"ok": True, "item_id": item_id, "local_media_id": local_media.id}


@app.post("/external/asmr/push")
def push_asmr_to_downloader(payload: schemas.ExternalDownloadRequest, db: Session = Depends(get_db)):
    """把选中的 ASMR 收藏推给下载中心。ASMR 是签名 CDN 直链，aria2 直接可下。"""
    def build(item, source, root):
        if (source.source_type or "") != "asmr":
            raise RuntimeError("不是 ASMR 条目")
        plan = prepare_asmr_download_plan_for_item(item, source, root)
        item_dir = plan["item_dir"]
        files = []
        for f in plan["files"]:
            rel = os.path.relpath(f["local_path"], item_dir).replace(os.sep, "/")
            files.append({
                "url": f["url"],
                "rel_path": rel,
                "headers": {
                    "User-Agent": "HE-Manager/1.0 local ASMR sync",
                    "Referer": asmr_source.WEB_WORK_BASE + "/",
                },
            })
        cover_rel = external_cover_sidecar_rel_path(item)
        if cover_rel:
            files.append({
                "url": item.cover_url,
                "rel_path": cover_rel,
                "optional": True,
                "headers": {
                    "User-Agent": "HE-Manager/1.0 local ASMR sync",
                    "Referer": asmr_source.WEB_WORK_BASE + "/",
                },
            })
        return item_dir, files

    return _push_external_items(payload, db, build)


@app.post("/external/wnacg/push")
def push_wnacg_to_downloader(payload: schemas.ExternalDownloadRequest, db: Session = Depends(get_db)):
    """把选中的 wnacg 收藏推给下载中心。⚠️ wnacg 在 Cloudflare 后、图片可能要浏览器
    TLS 指纹，aria2 或被 403；走不通就继续用内置 /external/wnacg/downloads。"""
    def build(item, source, root):
        if (source.source_type or "wnacg") != "wnacg":
            raise RuntimeError("不是 wnacg 条目")
        plan = prepare_wnacg_download_plan(item, source, root)
        item_dir = plan["item_dir"]
        headers = {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/124.0.0.0 Safari/537.36"
            ),
            "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            "Referer": item.url or source.favorites_url or "",
        }
        if source.cookie:
            headers["Cookie"] = source.cookie
        files = [
            {"url": url, "rel_path": f"{idx:03d}{downloader_push.url_ext(url)}", "headers": headers}
            for idx, url in enumerate(plan["image_urls"], start=1)
        ]
        cover_rel = external_cover_sidecar_rel_path(item)
        if cover_rel:
            cover_headers = dict(headers)
            cover_headers["Referer"] = source.favorites_url or item.url or ""
            files.append({"url": item.cover_url, "rel_path": cover_rel, "headers": cover_headers, "optional": True})
        return item_dir, files

    return _push_external_items(payload, db, build)
