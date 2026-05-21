from pydantic import BaseModel, Field
from datetime import datetime
from typing import List, Optional


class AuthStatus(BaseModel):
    has_users: bool


class UserCreate(BaseModel):
    username: str = Field(min_length=3, max_length=40)
    password: str = Field(min_length=6, max_length=128)
    is_admin: bool = False


class UserUpdate(BaseModel):
    username: Optional[str] = Field(default=None, min_length=3, max_length=40)
    password: Optional[str] = Field(default=None, min_length=6, max_length=128)
    is_admin: Optional[bool] = None
    is_active: Optional[bool] = None


class UserLogin(BaseModel):
    username: str
    password: str


class UserRead(BaseModel):
    id: int
    username: str
    is_admin: bool
    is_active: bool
    created_at: datetime

    class Config:
        from_attributes = True


class AuthToken(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserRead


class TagBase(BaseModel):
    name: str

class Tag(TagBase):
    id: int
    class Config:
        from_attributes = True

class MediaBase(BaseModel):
    title: str
    relative_path: str
    absolute_path: str
    media_type: str
    extension: str
    file_size: int
    cover_path: Optional[str] = None
    duration: Optional[int] = None
    width: Optional[int] = None
    height: Optional[int] = None
    page_count: Optional[int] = None
    rating: int = 0
    favorite: bool = False
    view_status: str = "unviewed"
    progress: int = 0
    last_opened_at: Optional[datetime] = None
    source_url: Optional[str] = None
    source_site: Optional[str] = None
    is_missing: bool = False
    missing_since: Optional[datetime] = None
    cover_time_ms: Optional[int] = None
    cover_source: Optional[str] = None
    normalized_title: Optional[str] = None
    duplicate_status: str = "unique"
    created_at: datetime

class Media(MediaBase):
    id: int
    tags: List[Tag] = []
    class Config:
        from_attributes = True

class MediaUpdate(BaseModel):
    title: Optional[str] = None
    duration: Optional[int] = Field(default=None, ge=0)
    rating: Optional[int] = Field(default=None, ge=0, le=5)
    favorite: Optional[bool] = None
    view_status: Optional[str] = None
    progress: Optional[int] = Field(default=None, ge=0)
    source_url: Optional[str] = None
    source_site: Optional[str] = None

class TagCreate(TagBase):
    pass

class FolderBase(BaseModel):
    path: str
    scan_mode: str = "video"
    thumbnail_enabled: bool = True
    thumbnail_interval: int = Field(default=1, ge=1, le=60)

class FolderCreate(FolderBase):
    pass

class Folder(FolderBase):
    id: int
    status: str
    last_scanned_at: Optional[datetime] = None
    class Config:
        from_attributes = True


class ExternalFavoriteSource(BaseModel):
    id: int
    source_type: str
    name: str
    favorites_url: str
    download_root_path: Optional[str] = None
    status: str
    last_synced_at: Optional[datetime] = None
    last_error: Optional[str] = None
    cookie_saved: bool = False

    class Config:
        from_attributes = True


class ExternalFavoriteItem(BaseModel):
    id: int
    source_id: int
    source_type: str
    external_id: str
    title: str
    url: str
    cover_url: Optional[str] = None
    category_id: Optional[str] = None
    category_name: Optional[str] = None
    sync_position: Optional[int] = None
    last_seen_at: Optional[datetime] = None
    local_media_id: Optional[int] = None

    class Config:
        from_attributes = True


class ExternalFavoriteSyncRequest(BaseModel):
    source_id: Optional[int] = None
    name: str = "WNACG"
    favorites_url: str = "https://www.wnacg.com/users-users_fav.html"
    cookie: Optional[str] = None
    page_limit: int = Field(default=3, ge=1, le=30)
    category_id: Optional[str] = None
    download_root_path: Optional[str] = None


class ExternalFavoriteSyncResponse(BaseModel):
    source: ExternalFavoriteSource
    synced_count: int
    items: List[ExternalFavoriteItem]


class AsmrSyncRequest(BaseModel):
    # ASMR shares ExternalFavoriteSource with wnacg via source_type='asmr'; this
    # request is its dedicated payload (different fields than wnacg's cookie/url).
    source_id: Optional[int] = None
    name: str = "ASMR"
    api_base: str = Field(min_length=1)
    api_mirrors: Optional[str] = None       # newline/comma-separated alternates
    audio_format_filter: str = "all"        # all | no_wav | mp3_only
    audio_version_filter: str = "all"       # all | no_se | se_only
    playlist_url: Optional[str] = None      # if set, pull from playlist instead of "marked"
    username: Optional[str] = None          # only required first time; afterwards the stored bearer token is reused
    password: Optional[str] = None          # transient — never persisted, only exchanged for a token
    page_limit: int = Field(default=3, ge=1, le=30)
    download_root_path: Optional[str] = None


class ExternalFavoriteSourceUpdate(BaseModel):
    name: Optional[str] = None
    favorites_url: Optional[str] = None
    download_root_path: Optional[str] = None


class ExternalDownloadRequest(BaseModel):
    item_ids: List[int] = Field(min_length=1)
    download_root_path: str = Field(min_length=1)


class ExternalDownloadJob(BaseModel):
    job_id: str
    status: str
    total: int
    completed: int = 0
    failed: int = 0
    message: str = ""
    pages_total: int = 0
    pages_done: int = 0
    bytes_total: int = 0
    downloaded_bytes: int = 0
    bytes_total_known: bool = False
    unknown_size_files: int = 0
    cancel_requested: bool = False
    current_book_title: str = ""
    current_book_total_pages: int = 0
    current_book_downloaded_pages: int = 0
    tasks: List[dict] = []
    results: List[dict] = []


# --- X (Twitter) one-click import ---

class XImportSource(BaseModel):
    id: int
    name: str
    download_root_path: Optional[str] = None
    last_archive_name: Optional[str] = None
    last_archive_imported_at: Optional[datetime] = None
    last_sync_at: Optional[datetime] = None
    cookie_saved: bool = False

    class Config:
        from_attributes = True


class XImportSourceUpdate(BaseModel):
    name: Optional[str] = None
    download_root_path: Optional[str] = None
    cookie: Optional[str] = None


class XImportStats(BaseModel):
    total_posts: int = 0
    completed_posts: int = 0
    failed_posts: int = 0
    skipped_posts: int = 0
    pending_posts: int = 0
    total_media: int = 0
    downloaded_media: int = 0


class XImportArchiveUploadResponse(BaseModel):
    source: XImportSource
    archive_name: str
    parsed: int
    new_posts: int
    existing_posts: int
    stats: XImportStats


class XImportStartRequest(BaseModel):
    source_id: int
    retry_failed_only: bool = False
    retry_skipped_only: bool = False


class XSyncJob(BaseModel):
    job_id: str
    source_id: int
    status: str
    message: str = ""
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    pages_scanned: int = 0
    posts_seen: int = 0
    new_posts: int = 0
    existing_posts: int = 0
    cancel_requested: bool = False
    stop_reason: Optional[str] = None


class XImportJobError(BaseModel):
    tweet_id: str
    message: str
    at: str


class XImportJob(BaseModel):
    job_id: str
    source_id: int
    download_root: str
    status: str
    message: str = ""
    started_at: Optional[str] = None
    finished_at: Optional[str] = None
    total_posts: int = 0
    scanned_posts: int = 0
    media_posts: int = 0
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
    errors: List[XImportJobError] = []


# --- local-file deduplication ---

class DedupSummary(BaseModel):
    pending_pairs: int = 0
    strong_duplicate: int = 0
    suspected_duplicate: int = 0
    weak_suspected: int = 0
    checking: int = 0
    queue_size: int = 0
    worker_running: bool = False


class DedupMediaSummary(BaseModel):
    id: int
    title: str
    absolute_path: str
    media_type: str
    extension: Optional[str] = None
    file_size: Optional[int] = None
    cover_path: Optional[str] = None
    duration: Optional[int] = None
    width: Optional[int] = None
    height: Optional[int] = None
    page_count: Optional[int] = None
    is_missing: bool = False
    missing_since: Optional[datetime] = None
    duplicate_status: str = "unique"
    favorite: bool = False
    rating: int = 0
    source_url: Optional[str] = None
    source_site: Optional[str] = None

    class Config:
        from_attributes = True


class DuplicateCandidatePair(BaseModel):
    id: int
    level: str
    similarity: int
    reason: Optional[str] = None
    status: str
    created_at: datetime
    resolved_at: Optional[datetime] = None
    resolution_note: Optional[str] = None
    existing: DedupMediaSummary
    candidate: DedupMediaSummary


class DedupActionRequest(BaseModel):
    action: str = Field(description="keep_existing | replace_path | keep_both | ignore")
    note: Optional[str] = None


class DedupDeleteFileRequest(BaseModel):
    confirm: bool = False


class XPost(BaseModel):
    id: int
    source_id: int
    tweet_id: str
    url: str
    author_screen_name: Optional[str] = None
    author_name: Optional[str] = None
    posted_at: Optional[datetime] = None
    full_text: Optional[str] = None
    media_count: int = 0
    has_media: bool = False
    status: str
    error_message: Optional[str] = None
    last_attempt_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    discovered_at: datetime

    class Config:
        from_attributes = True
