const configuredApiBase = (import.meta.env.VITE_API_BASE_URL || '').trim().replace(/\/+$/, '');

export const API_BASE_URL = configuredApiBase;
export const THUMBNAIL_URL = `${API_BASE_URL}/thumbnails`;
export const STREAM_URL = `${API_BASE_URL}/stream`;
export const AUTH_TOKEN_STORAGE_KEY = 'he_manager_token';

export function authUrl(url: string): string {
    const token = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || '';
    if (!token) return url;
    const parsed = new URL(url, window.location.href);
    parsed.searchParams.set('token', token);
    return parsed.toString();
}

/** Resolve a Media.cover_path (relative, possibly null) to a full thumbnail URL.
 *  Returns an empty string for missing / null paths so <img :src=""> renders nothing
 *  instead of a broken-image icon. Pattern matches MediaCard.vue's getThumb. */
export function thumbnailUrl(path: string | null | undefined): string {
    if (!path) return '';
    return authUrl(`${THUMBNAIL_URL}/${path}`);
}
