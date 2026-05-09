package com.hemanager.mobile.player.model

import androidx.compose.runtime.Immutable

/**
 * Plain-data representation of a single audio or subtitle track exposed by ExoPlayer.
 * Compose-friendly: immutable, lightweight to compare for skip-recomposition.
 *
 * `groupIndex` + `trackIndex` are how we round-trip back to ExoPlayer's TrackSelectionOverride —
 * we cannot keep the live MediaTrackGroup reference here because it's tied to the player's
 * current Tracks snapshot, which gets replaced on every track-change callback.
 */
@Immutable
data class TrackInfo(
    val id: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
)
