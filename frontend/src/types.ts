export interface Media {
    id: number;
    title: string;
    relative_path: string;
    absolute_path: string;
    media_type: 'video' | 'manga' | 'image';
    extension: string;
    file_size: number;
    cover_path: string | null;
    duration: number | null;
    width: number | null;
    height: number | null;
    page_count: number | null;
    rating: number;
    favorite: boolean;
    view_status: 'unviewed' | 'viewing' | 'viewed';
    progress: number;
    last_opened_at: string | null;
    source_url: string | null;
    source_site: string | null;
    is_missing: boolean;
    created_at: string;
    tags: Tag[];
}

export interface Tag {
    id: number;
    name: string;
}

export interface User {
    id: number;
    username: string;
    is_admin: boolean;
    is_active: boolean;
    created_at: string;
}

export interface Folder {
    id: number;
    path: string;
    status: 'idle' | 'scanning' | 'error';
    scan_mode: 'auto' | 'manga' | 'video' | 'image';
    thumbnail_enabled: boolean;
    thumbnail_interval: number;
    last_scanned_at: string | null;
}

export interface ExternalFavoriteSource {
    id: number;
    source_type: 'wnacg' | 'x' | string;
    name: string;
    favorites_url: string;
    download_root_path: string | null;
    status: 'idle' | 'syncing' | 'ok' | 'error';
    last_synced_at: string | null;
    last_error: string | null;
    cookie_saved: boolean;
}

export interface ExternalDownloadTask {
    id: string;
    item_id: number;
    title: string;
    status: 'pending' | 'downloading' | 'success' | 'failed';
    total_pages: number;
    downloaded_pages: number;
    error?: string | null;
}

export interface ExternalDownloadJob {
    job_id: string;
    status: string;
    total: number;
    completed: number;
    failed: number;
    message: string;
    pages_total: number;
    pages_done: number;
    bytes_total: number;
    downloaded_bytes: number;
    bytes_total_known: boolean;
    unknown_size_files: number;
    cancel_requested: boolean;
    current_book_title: string;
    current_book_total_pages: number;
    current_book_downloaded_pages: number;
    tasks: ExternalDownloadTask[];
    results: any[];
}

export interface ExternalFavoriteItem {
    id: number;
    source_id: number;
    source_type: 'wnacg' | 'x' | string;
    external_id: string;
    title: string;
    url: string;
    cover_url: string | null;
    category_id: string | null;
    category_name: string | null;
    sync_position: number | null;
    last_seen_at: string | null;
    local_media_id: number | null;
}

export interface XImportSource {
    id: number;
    name: string;
    download_root_path: string | null;
    last_archive_name: string | null;
    last_archive_imported_at: string | null;
    last_sync_at: string | null;
}

export interface XImportStats {
    total_posts: number;
    completed_posts: number;
    failed_posts: number;
    skipped_posts: number;
    pending_posts: number;
    total_media: number;
    downloaded_media: number;
}

export interface XImportArchiveUploadResponse {
    source: XImportSource;
    archive_name: string;
    parsed: number;
    new_posts: number;
    existing_posts: number;
    stats: XImportStats;
}

export interface XImportJobError {
    tweet_id: string;
    message: string;
    at: string;
}

export interface XImportJob {
    job_id: string;
    source_id: number;
    download_root: string;
    status: 'queued' | 'preparing' | 'running' | 'paused' | 'completed' | 'failed' | 'canceled' | string;
    message: string;
    started_at: string | null;
    finished_at: string | null;
    total_posts: number;
    scanned_posts: number;
    media_posts: number;
    completed_posts: number;
    skipped_posts: number;
    failed_posts: number;
    media_total: number;
    media_downloaded: number;
    media_failed: number;
    current_author: string;
    current_post_id: string;
    current_file: string;
    pause_requested: boolean;
    cancel_requested: boolean;
    errors: XImportJobError[];
}

export interface XPost {
    id: number;
    source_id: number;
    tweet_id: string;
    url: string;
    author_screen_name: string | null;
    author_name: string | null;
    posted_at: string | null;
    full_text: string | null;
    media_count: number;
    has_media: boolean;
    status: 'pending' | 'fetched' | 'downloading' | 'completed' | 'failed' | 'skipped' | string;
    error_message: string | null;
    last_attempt_at: string | null;
    completed_at: string | null;
    discovered_at: string;
}
