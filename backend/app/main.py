from datetime import datetime
import glob
import hashlib
import mimetypes
import os
import re
import shutil
import time
import uuid
import zipfile
from typing import List, Optional
from urllib.parse import urljoin, urlparse

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import inspect, text
from sqlalchemy.orm import Session

from . import auth, database, external_sources, models, schemas, scanner
from .database import engine, get_db
from .x_import import archive as x_archive
from .x_import import importer as x_importer
from .x_import import storage as x_storage


models.Base.metadata.create_all(bind=engine)


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

    with engine.begin() as conn:
        if "download_root_path" not in columns:
            conn.execute(text("ALTER TABLE external_favorite_sources ADD COLUMN download_root_path VARCHAR"))


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


ensure_media_indexes()
ensure_media_cover_info_columns()


def ensure_x_import_indexes():
    inspector = inspect(engine)
    if not inspector.has_table("x_posts"):
        return
    with engine.begin() as conn:
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_x_posts_source_status ON x_posts (source_id, status)"))
        conn.execute(text("CREATE INDEX IF NOT EXISTS ix_x_media_items_post ON x_media_items (post_id)"))


ensure_x_import_indexes()

app = FastAPI(title="HE Manager API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

THUMBNAIL_DIR = os.path.join(os.getcwd(), ".thumbnails")
os.makedirs(THUMBNAIL_DIR, exist_ok=True)
app.mount("/thumbnails", StaticFiles(directory=THUMBNAIL_DIR), name="thumbnails")

DEFAULT_EXTERNAL_DOWNLOAD_DIR = os.path.join(os.getcwd(), "external_downloads")
EXTERNAL_COVERS_DIR = os.path.abspath(os.path.join(os.getcwd(), "..", "covers"))
os.makedirs(EXTERNAL_COVERS_DIR, exist_ok=True)
DOWNLOAD_JOBS = {}

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


def get_cover_cache_prefix(item: models.ExternalFavoriteItem) -> str:
    stable_id = item.external_id or str(item.id)
    digest = hashlib.sha1((item.cover_url or item.url or stable_id).encode("utf-8")).hexdigest()[:10]
    return f"{item.id}_{stable_id}_{digest}"


def find_cached_cover(covers_dir: str, item: models.ExternalFavoriteItem) -> Optional[str]:
    matches = glob.glob(os.path.join(covers_dir, f"{get_cover_cache_prefix(item)}.*"))
    return matches[0] if matches else None


def safe_filename(value: str, fallback: str = "item") -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value or "").strip()
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" .")
    return (cleaned or fallback)[:120]


def external_item_download_dir(item: models.ExternalFavoriteItem, source: models.ExternalFavoriteSource, download_root_path: Optional[str] = None) -> str:
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


def find_local_media_for_external_item(item: models.ExternalFavoriteItem, db: Session) -> Optional[models.Media]:
    media = (
        db.query(models.Media)
        .filter(
            models.Media.source_url == item.url,
            models.Media.source_site == item.source_type,
            models.Media.media_type == "manga",
            models.Media.is_missing == False,
        )
        .first()
    )
    if media:
        return media

    source = item.source
    if not source or not source.download_root_path:
        return None

    item_dir = external_item_download_dir(item, source)
    if not os.path.isdir(item_dir):
        return None

    media = (
        db.query(models.Media)
        .filter(
            models.Media.absolute_path == item_dir,
            models.Media.media_type == "manga",
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

    media = (
        db.query(models.Media)
        .filter(models.Media.absolute_path == item_dir, models.Media.media_type == "manga")
        .first()
    )
    if media:
        media.folder_id = folder.id
        media.title = item.title
        media.relative_path = rel_path
        media.file_size = 0
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
            file_size=0,
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
        if scanner.get_folder_thumbnail(item_dir, thumb_path):
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
def login(payload: schemas.UserLogin, db: Session = Depends(get_db)):
    user = db.query(models.User).filter(models.User.username == payload.username.strip()).first()
    if not user or not user.is_active or not auth.verify_password(payload.password, user.password_hash):
        raise HTTPException(status_code=401, detail="用户名或密码错误")

    token = auth.create_access_token(db, user)
    return {"access_token": token, "user": user}


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
    db.delete(media)
    db.commit()
    return {"message": "Media removed from library"}


@app.get("/tags", response_model=List[schemas.Tag])
def list_tags(db: Session = Depends(get_db)):
    return db.query(models.Tag).order_by(models.Tag.name.asc()).all()


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
        get_external_storage_dirs(source)
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
                    parsed_items.extend(
                        external_sources.parse_wnacg_favorites(
                            page_html,
                            base_url=base_url,
                            category_id=category.id,
                            category_name=category.name,
                        )
                    )
                    if not external_sources.html_has_next_page(page_html):
                        break
        else:
            parsed_items = external_sources.parse_wnacg_favorites(first_html, base_url=base_url)

        now = datetime.utcnow()
        existing_items = {
            item.external_id: item
            for item in db.query(models.ExternalFavoriteItem).filter(models.ExternalFavoriteItem.source_id == source.id).all()
        }
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
        _, covers_dir, _ = get_external_storage_dirs(source)
        cached_cover = find_cached_cover(covers_dir, item)
        if cached_cover and os.path.exists(cached_cover):
            return FileResponse(cached_cover)

        content, content_type = external_sources.fetch_binary(
            item.cover_url,
            source.cookie or "",
            referer=item.url or source.favorites_url,
        )
        extension = get_cover_extension(content_type, item.cover_url)
        cover_path = os.path.join(covers_dir, f"{get_cover_cache_prefix(item)}{extension}")
        with open(cover_path, "wb") as cover_file:
            cover_file.write(content)
        return FileResponse(cover_path, media_type=content_type)
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
def stream_media(media_id: int, db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)

    if not os.path.exists(media.absolute_path):
        media.is_missing = True
        db.commit()
        raise HTTPException(status_code=404, detail="File not found on disk")

    media.last_opened_at = datetime.utcnow()
    if media.view_status == "unviewed":
        media.view_status = "viewing"
    db.commit()
    return FileResponse(media.absolute_path)


@app.get("/mobile/stream/{media_id}")
def stream_mobile_media(media_id: int, _: models.User = Depends(auth.get_current_user), db: Session = Depends(get_db)):
    media = get_media_or_404(media_id, db)
    if not os.path.exists(media.absolute_path):
        media.is_missing = True
        db.commit()
        raise HTTPException(status_code=404, detail="File not found on disk")

    media.last_opened_at = datetime.utcnow()
    if media.view_status == "unviewed":
        media.view_status = "viewing"
    db.commit()
    return FileResponse(media.absolute_path)


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
        raise HTTPException(status_code=500, detail=str(e))


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
    )
    job_id = str(uuid.uuid4())
    job = x_importer.start_job(
        job_id=job_id,
        source_id=source.id,
        download_root=source.download_root_path,
        thumbnail_dir=THUMBNAIL_DIR,
        post_ids=post_ids,
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
