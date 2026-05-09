package com.hemanager.mobile.player.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hemanager.mobile.player.model.TrackInfo

/**
 * Combined audio + subtitle picker. Built on the shared [CompactSheet] so it matches
 * Speed and Aspect visually. Sections are conditionally rendered — single-track
 * audio or a video without subtitles won't show empty rows.
 */
@Composable
fun TracksSheet(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>,
    subtitlesEnabled: Boolean,
    onPickAudio: (TrackInfo) -> Unit,
    onPickSubtitle: (TrackInfo?) -> Unit,
    onDismiss: () -> Unit,
) {
    CompactSheet(title = "字幕 / 音轨", onDismiss = onDismiss) {
        if (audioTracks.size > 1) {
            CompactSectionHeader("音轨")
            audioTracks.forEach { track ->
                CompactRow(
                    label = track.label,
                    sub = track.language,
                    selected = track.isSelected,
                    onClick = {
                        onPickAudio(track)
                        onDismiss()
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        } else if (audioTracks.isEmpty()) {
            CompactSectionHeader("音轨")
            CompactEmptyHint("视频没有音频轨道")
            Spacer(modifier = Modifier.height(4.dp))
        }

        CompactSectionHeader("字幕")
        if (subtitleTracks.isEmpty()) {
            CompactEmptyHint("视频没有字幕轨道")
        } else {
            CompactRow(
                label = "关闭字幕",
                selected = !subtitlesEnabled,
                onClick = {
                    onPickSubtitle(null)
                    onDismiss()
                },
            )
            subtitleTracks.forEach { track ->
                CompactRow(
                    label = track.label,
                    sub = track.language,
                    selected = subtitlesEnabled && track.isSelected,
                    onClick = {
                        onPickSubtitle(track)
                        onDismiss()
                    },
                )
            }
        }
    }
}
