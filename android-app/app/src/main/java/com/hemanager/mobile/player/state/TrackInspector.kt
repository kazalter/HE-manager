package com.hemanager.mobile.player.state

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.hemanager.mobile.player.model.TrackInfo

/**
 * Helpers for translating between ExoPlayer's [Tracks] representation and our
 * UI-friendly [TrackInfo]s, plus applying selections back to the player.
 *
 * Why split: PlayerView and ExoPlayer's track APIs are imperative; the rest of our
 * player UI is reactive over a [com.hemanager.mobile.player.model.PlayerUiState] flow.
 * This file is the boundary layer.
 */
@UnstableApi
object TrackInspector {

    /**
     * Read the player's current Tracks and return a (audio, subtitle) split. Tracks that
     * aren't supported by the device are silently skipped — selecting them would error
     * at playback time anyway.
     */
    fun extract(tracks: Tracks): Pair<List<TrackInfo>, List<TrackInfo>> {
        val audio = mutableListOf<TrackInfo>()
        val subs = mutableListOf<TrackInfo>()
        tracks.groups.forEachIndexed { groupIdx, group ->
            for (trackIdx in 0 until group.length) {
                if (!group.isTrackSupported(trackIdx)) continue
                val format = group.getTrackFormat(trackIdx)
                val info = TrackInfo(
                    id = "$groupIdx-$trackIdx",
                    groupIndex = groupIdx,
                    trackIndex = trackIdx,
                    label = formatLabel(format.label, format.language, group.type, audio.size + subs.size + 1),
                    language = format.language,
                    isSelected = group.isTrackSelected(trackIdx),
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio.add(info)
                    C.TRACK_TYPE_TEXT -> subs.add(info)
                    else -> Unit
                }
            }
        }
        return audio to subs
    }

    fun selectAudio(player: ExoPlayer, info: TrackInfo) {
        val tracks = player.currentTracks
        val group = tracks.groups.getOrNull(info.groupIndex) ?: return
        if (group.type != C.TRACK_TYPE_AUDIO) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(info.trackIndex)),
            )
            .build()
    }

    fun selectSubtitle(player: ExoPlayer, info: TrackInfo?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (info == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val tracks = player.currentTracks
            val group = tracks.groups.getOrNull(info.groupIndex)
            if (group != null && group.type == C.TRACK_TYPE_TEXT) {
                builder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(info.trackIndex)),
                )
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun formatLabel(label: String?, language: String?, type: Int, fallbackIndex: Int): String {
        if (!label.isNullOrBlank()) return label
        if (!language.isNullOrBlank()) return language
        val prefix = when (type) {
            C.TRACK_TYPE_AUDIO -> "音轨"
            C.TRACK_TYPE_TEXT -> "字幕"
            else -> "Track"
        }
        return "$prefix $fallbackIndex"
    }
}
