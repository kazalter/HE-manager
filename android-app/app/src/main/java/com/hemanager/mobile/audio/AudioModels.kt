package com.hemanager.mobile.audio

/**
 * Data model for the ASMR audio player. A "work" (one downloaded ASMR album) is
 * a folder of ordered tracks; the backend exposes them via /audio/{id}/tracks.
 * Kept deliberately separate from the video player's `player/` package so the
 * in-progress video rewrite is never touched.
 */

/** One audio track inside a work. [durationSec] is null when the manifest
 * (tracks.json) had no duration for it — resume math falls back gracefully. */
data class AudioTrack(
    val index: Int,
    val title: String,
    val rel: String,
    val durationSec: Double?,
    val hasLyrics: Boolean,
)

/** One timed lyric line. [timeSec] is the start time in seconds (LRC/VTT/SRT
 * all normalised to this shape by the backend). */
data class LyricLine(val timeSec: Double, val text: String)

/** Repeat behaviour, mirroring what mainstream players offer. */
enum class LoopMode { OFF, ALL, ONE, SHUFFLE }

/**
 * Everything the Now-Playing UI renders. Emitted as a single immutable snapshot
 * so Compose recomposition stays predictable.
 *
 * [sleepTimerEndUptimeMs] is an `SystemClock.uptimeMillis()` deadline (0 = off).
 * [sleepAfterCurrentTrack] is the "stop when this track ends" variant, which
 * can't be expressed as a wall-clock deadline.
 */
data class AudioPlayerUiState(
    val workTitle: String = "",
    val coverUrl: String? = null,
    val tracks: List<AudioTrack> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val loopMode: LoopMode = LoopMode.OFF,
    val speed: Float = 1f,
    val lyrics: List<LyricLine> = emptyList(),
    val activeLyricIndex: Int = -1,
    val sleepTimerEndUptimeMs: Long = 0L,
    val sleepAfterCurrentTrack: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val currentTrack: AudioTrack? get() = tracks.getOrNull(currentIndex)
    val sleepTimerArmed: Boolean get() = sleepTimerEndUptimeMs > 0L || sleepAfterCurrentTrack
}
