package com.hemanager.mobile.audio

import android.net.Uri
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Thin suspend wrapper over the Java [ApiClient] for the audio player. Mirrors
 * the role of `player/data/PlayerRepository` but for the ASMR audio endpoints
 * (FEATURE_PLANS ⑥). All blocking I/O is dispatched to [Dispatchers.IO].
 *
 * Note: the audio streaming endpoints are unauthenticated server-side (the web
 * client uses them token-less). We still pass the token in the stream URL so
 * behaviour stays consistent if auth is ever tightened — ignored harmlessly today.
 */
class AudioRepository(
    private val serverUrl: String,
    private val token: String,
) {
    private val api: ApiClient get() = ApiClient(serverUrl, token)

    /** Title / cover / saved-resume-progress / total duration for a work. */
    data class WorkMeta(
        val title: String,
        val coverUrl: String?,
        val savedProgressSec: Int,
        val totalDurationSec: Int,
    )

    suspend fun loadWork(mediaId: Int): WorkMeta? = withContext(Dispatchers.IO) {
        runCatching {
            val item = MediaItem.fromJson(api.getJsonObject("/mobile/media/$mediaId"))
            WorkMeta(
                title = item.title,
                coverUrl = coverUrl(item.coverPath),
                savedProgressSec = item.progress.coerceAtLeast(0),
                totalDurationSec = item.duration.coerceAtLeast(0),
            )
        }.getOrNull()
    }

    suspend fun getTracks(mediaId: Int): List<AudioTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = api.getJsonObject("/audio/$mediaId/tracks").optJSONArray("tracks")
                ?: return@runCatching emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AudioTrack(
                    index = o.optInt("index", i + 1),
                    title = o.optString("title", "Track ${i + 1}"),
                    rel = o.optString("rel", ""),
                    durationSec = if (o.isNull("duration")) null else o.optDouble("duration").takeIf { it > 0 },
                    hasLyrics = !o.isNull("lyrics") && o.optString("lyrics").isNotBlank(),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Normalised timed lyrics for one track. Always returns a list (empty when
     * the track has no subtitle — the endpoint is a guaranteed 200). */
    suspend fun getLyrics(mediaId: Int, index: Int): List<LyricLine> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = api.getJsonObject("/audio/$mediaId/track/$index/lyrics").optJSONArray("lines")
                ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").trim()
                if (text.isEmpty()) null else LyricLine(o.optDouble("t", 0.0), text)
            }.sortedBy { it.timeSec }
        }.getOrDefault(emptyList())
    }

    fun trackStreamUrl(mediaId: Int, index: Int): String =
        "$serverUrl/audio/$mediaId/track/$index?${ApiClient.tokenQuery(token)}"

    /** Resume bookkeeping: progress is stored as cumulative seconds across the
     * whole work (sum of prior track durations + offset into the current one),
     * reusing the single `progress` column exactly like the video player does. */
    suspend fun saveProgress(mediaId: Int, progressSec: Int, durationSec: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("progress", progressSec)
                if (durationSec > 0) body.put("duration", durationSec)
                api.patchJson("/media/$mediaId", body, true)
            }
        }
    }

    private fun coverUrl(coverPath: String?): String? {
        if (coverPath.isNullOrBlank() || coverPath == "null") return null
        val encoded = Uri.encode(coverPath, "")
        return "$serverUrl/mobile/thumbnails/$encoded?${ApiClient.tokenQuery(token)}"
    }
}
