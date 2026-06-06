export interface Media {
    id: number;
    title: string;
    relative_path: string;
    media_type: 'video' | 'manga' | 'image' | 'audio';
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
    missing_since: string | null;
    normalized_title?: string | null;
    duplicate_status?: 'unique' | 'checking' | 'strong_duplicate' | 'suspected_duplicate' | 'weak_suspected' | string;
    created_at: string;
    tags: Tag[];
}

export type DedupLevel = 'strong_duplicate' | 'suspected_duplicate' | 'weak_suspected';

export interface DedupSummary {
    pending_pairs: number;
    strong_duplicate: number;
    suspected_duplicate: number;
    weak_suspected: number;
    checking: number;
    queue_size: number;
    worker_running: boolean;
}

export interface DedupMediaSummary {
    id: number;
    title: string;
    display_path: string;
    media_type: 'video' | 'manga' | 'image';
    extension: string | null;
    file_size: number | null;
    cover_path: string | null;
    duration: number | null;
    width: number | null;
    height: number | null;
    page_count: number | null;
    is_missing: boolean;
    missing_since: string | null;
    duplicate_status: string;
    favorite: boolean;
    rating: number;
    source_url: string | null;
    source_site: string | null;
}

export interface DuplicateCandidatePair {
    id: number;
    level: DedupLevel | string;
    similarity: number;
    reason: string | null;
    status: 'pending' | 'merged' | 'kept_both' | 'ignored' | 'replaced' | string;
    created_at: string;
    resolved_at: string | null;
    resolution_note: string | null;
    existing: DedupMediaSummary;
    candidate: DedupMediaSummary;
}

export interface Tag {
    id: number;
    name: string;
    namespace: string;
    count: number;
}

export interface Bd2SpineAsset {
    id: string;
    title: string;
    skeleton: string;
    atlas: string;
    textures: string[];
    skeleton_url: string;
    atlas_url: string;
}

export interface Bd2SpineListResponse {
    root: string;
    assets: Bd2SpineAsset[];
}

export interface MangaRecommendationItem {
    media: Media;
    reason: string;
    matched_tags: string[];
    score: number;
}

export interface MangaRecommendationResponse {
    recommendations: MangaRecommendationItem[];
    parsed_preferences: Record<string, unknown>;
    ai_enabled: boolean;
    candidate_count: number;
    message: string | null;
}

export interface AiRecommendationStatus {
    deepseek_configured: boolean;
    model: string;
    base_url: string;
    key_saved: boolean;
    env_key_present: boolean;
}

export interface DeepSeekConfigUpdate {
    api_key?: string | null;
    model?: string | null;
    base_url?: string | null;
    clear_api_key?: boolean;
}

export interface MangaProfileStats {
    total_manga: number;
    profiled: number;
    stale: number;
    missing: number;
}

export interface MangaProfileJob {
    job_id: string;
    status: string;
    total: number;
    completed: number;
    failed: number;
    message: string;
    current_title: string;
    errors: string[];
}

export interface MangaMetadataStats {
    total_manga: number;
    profiled: number;
    stale: number;
    missing: number;
}

export interface MangaMetadataJob {
    job_id: string;
    status: string;
    total: number;
    completed: number;
    failed: number;
    message: string;
    current_title: string;
    errors: string[];
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
    scan_mode: 'auto' | 'manga' | 'video' | 'image' | 'audio' | 'audio_work';
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
    api_mirrors?: string | null;
    audio_format_filter?: string | null;
    audio_version_filter?: string | null;
    playlist_url?: string | null;
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
    cookie_saved: boolean;
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

export interface XSyncJob {
    job_id: string;
    source_id: number;
    status: 'queued' | 'running' | 'completed' | 'failed' | 'canceled' | string;
    message: string;
    started_at: string | null;
    finished_at: string | null;
    pages_scanned: number;
    posts_seen: number;
    new_posts: number;
    existing_posts: number;
    cancel_requested: boolean;
    stop_reason: string | null;
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

// -- Creators (unified X authors + manga artists) ----------------------------
// Backend: /creators, /creators/{screen_name}, /mobile/creators*
// Source : backend/app/creators.py

export interface Creator {
    kind: 'x' | 'artist';
    key: string;                  // 'x:<screen_name>' or 'a:<artist>'
    screen_name?: string | null;  // X authors only; manga artists have none
    display_name?: string | null;
    media_count: number;
    posts_known: number;          // 0 for manga artists
    posts_pending: number;        // 0 for manga artists
    cover_path?: string | null;
}

export interface CreatorDetail {
    creator: Creator;
    media: Media[];
}

// -- Dashboard stats --------------------------------------------------------
// Backend: /stats/{overview,distribution,activity,attention}
// Source : backend/app/stats.py

export interface StatsOverview {
    total: number;
    by_type: Record<string, number>;        // {'video': N, 'manga': N, 'image': N, 'audio': N}
    by_type_size: Record<string, number>;   // same keys as by_type, sum(file_size) per type
    view_status: Record<string, number>;    // {'unviewed': N, 'viewing': N, 'viewed': N}
    favorites: number;
    rated: number;
    missing: number;
    total_size_bytes: number;
    total_duration_seconds: number;
    average_rating: number;
}

export interface StatsDistributionGrowthPoint {
    month: string;        // 'YYYY-MM'
    added: number;        // new items in that month
    cumulative: number;
}

export interface StatsDistribution {
    rating_histogram: Record<string, number>;  // {'5': N, '4': N, ...}
    by_source: Record<string, number>;         // {'wnacg': N, 'x': N, 'local': N, ...}
    growth: StatsDistributionGrowthPoint[];
}

export interface StatsActivityBucket {
    date: string;   // 'YYYY-MM-DD'
    count: number;
}

export interface StatsActivity {
    days: number;
    to_date: string;                    // 'YYYY-MM-DD' (right edge of window)
    buckets: StatsActivityBucket[];
    total: number;
    max: number;
    by_type: Record<string, StatsActivityBucket[]>;  // per-media-type buckets, same date keys
}

export interface StatAttentionItem {
    id: number;
    title: string;
    cover_path?: string | null;
    rating: number;
    last_opened_at: string | null;
}

export interface StatsAttention {
    dusty: StatAttentionItem[];
    unrated: StatAttentionItem[];
    stale_days: number;
}

export interface StatsTopCreator {
    key: string;            // 'x:<screen_name>' or 'a:<artist>'
    kind: 'x' | 'artist';
    screen_name: string | null;
    display_name: string;
    media_count: number;
    cover_path?: string | null;
}

export interface StatsTopVideo {
    id: number;
    title: string;
    cover_path?: string | null;
    duration: number;       // seconds
    rating: number;
    file_size: number;
    last_opened_at: string | null;
}

export interface StatsTopTag {
    name: string;
    namespace: string;
    count: number;
}

export interface StatsHighlights {
    limit: number;
    top_creators: StatsTopCreator[];
    top_videos: StatsTopVideo[];
    top_tags: StatsTopTag[];
}
