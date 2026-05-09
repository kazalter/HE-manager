package com.hemanager.mobile.player.data

import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Thin wrapper over [ApiClient] for the player. Centralises every backend call the
 * player makes so that retries, error mapping, and threading happen in one place.
 *
 * All methods are suspend and dispatch to IO. The Java [ApiClient] does its own blocking
 * I/O — we never call it from the main thread.
 */
class PlayerRepository(
    private val serverUrl: String,
    private val token: String,
) {
    private val api: ApiClient get() = ApiClient(serverUrl, token)

    fun streamUrl(mediaId: Int): String =
        "$serverUrl/mobile/stream/$mediaId?${ApiClient.tokenQuery(token)}"

    suspend fun loadMedia(mediaId: Int): MediaItem? = withContext(Dispatchers.IO) {
        runCatching { MediaItem.fromJson(api.getJsonObject("/mobile/media/$mediaId")) }.getOrNull()
    }

    /**
     * Save progress in seconds. Caller is responsible for throttling — this method
     * always issues the network call when invoked.
     */
    suspend fun saveProgress(mediaId: Int, progressSeconds: Int, durationSeconds: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("progress", progressSeconds)
                if (durationSeconds > 0) body.put("duration", durationSeconds)
                api.patchJson("/media/$mediaId", body, true)
            }
        }
    }

    suspend fun setViewStatus(mediaId: Int, status: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                api.patchJson("/media/$mediaId", JSONObject().put("view_status", status), true)
            }
        }
    }

    suspend fun toggleFavorite(mediaId: Int, favorite: Boolean): MediaItem? =
        withContext(Dispatchers.IO) {
            runCatching { api.toggleFavorite(mediaId, favorite) }.getOrNull()
        }

    suspend fun addTag(mediaId: Int, tagName: String): MediaItem? =
        withContext(Dispatchers.IO) {
            runCatching { api.addTag(mediaId, tagName) }.getOrNull()
        }
}
