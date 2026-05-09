package com.hemanager.mobile.player.model

import androidx.compose.runtime.Immutable
import com.hemanager.mobile.MediaItem

/**
 * Single source of truth for the player UI. All composables read from a [PlayerUiState]
 * StateFlow. Mutating fields directly on the player happens in the ViewModel; this object
 * is rebuilt and emitted in response.
 */
@Immutable
data class PlayerUiState(
    val status: PlayerStatus = PlayerStatus.Idle,
    val media: MediaItem? = null,
    val title: String = "",

    // Playback timing — kept in millis. UI position updates come from a ticker
    // in the ViewModel so we don't read the player from composition.
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,

    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,

    val playbackSpeed: Float = 1.0f,
    val playbackMode: PlaybackMode = PlaybackMode.SEQUENCE,
    val aspectMode: AspectMode = AspectMode.FIT,

    val controlsVisible: Boolean = true,
    val locked: Boolean = false,

    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,

    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val subtitlesEnabled: Boolean = true,

    // True while the user is actively dragging the seek bar.
    val scrubbing: Boolean = false,

    val errorMessage: String? = null,
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val bufferedFraction: Float
        get() = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
