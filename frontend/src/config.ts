export const API_BASE_URL = `http://${window.location.hostname}:8010`;
export const THUMBNAIL_URL = `${API_BASE_URL}/thumbnails`;
export const STREAM_URL = `${API_BASE_URL}/stream`;

/** Resolve a Media.cover_path (relative, possibly null) to a full thumbnail URL.
 *  Returns an empty string for missing / null paths so <img :src=""> renders nothing
 *  instead of a broken-image icon. Pattern matches MediaCard.vue's getThumb. */
export function thumbnailUrl(path: string | null | undefined): string {
    if (!path) return '';
    return `${THUMBNAIL_URL}/${path}`;
}
