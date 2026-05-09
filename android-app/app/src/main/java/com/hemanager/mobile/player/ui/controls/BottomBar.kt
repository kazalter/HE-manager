package com.hemanager.mobile.player.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemanager.mobile.player.model.PlaybackMode
import com.hemanager.mobile.player.ui.PlayerSeekBar
import com.hemanager.mobile.player.ui.formatSpeed
import java.util.Locale

@Composable
fun BottomControlBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isFullscreen: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    speed: Float,
    playbackMode: PlaybackMode,
    locked: Boolean,
    onScrubStart: () -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onSpeedClick: () -> Unit,
    onAspectClick: () -> Unit,
    onTracksClick: () -> Unit,
    onLockClick: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PlayerSeekBar(
            progress = progress,
            buffered = buffered,
            onScrubStart = onScrubStart,
            onScrubMove = onScrubMove,
            onScrubEnd = onScrubEnd,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTime(positionMs), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("  /  ", color = Color(0x80FFFFFF), fontSize = 13.sp)
            Text(formatTime(durationMs), color = Color(0xCCFFFFFF), fontSize = 13.sp)

            Spacer(modifier = Modifier.weight(1f))

            // Speed always visible — primary control.
            PillTextButton(text = formatSpeed(speed), onClick = onSpeedClick)
            Spacer(modifier = Modifier.width(6.dp))

            // Playback mode always visible.
            IconButton(onClick = onCyclePlaybackMode, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = playbackModeIcon(playbackMode),
                    contentDescription = playbackMode.label,
                    tint = if (playbackMode == PlaybackMode.SEQUENCE) Color.White
                    else com.hemanager.mobile.player.ui.PlayerColors.Primary,
                )
            }

            // Landscape-only navigation + tracks. Portrait keeps the row tight —
            // those actions are reachable after tapping fullscreen.
            if (isFullscreen) {
                DimmableIcon(enabled = canSkipPrevious, onClick = onSkipPrevious) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "上一个", tint = Color.White)
                }
                DimmableIcon(enabled = canSkipNext, onClick = onSkipNext) {
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "下一个", tint = Color.White)
                }
                IconButton(onClick = onAspectClick, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.AspectRatio, contentDescription = "画面比例", tint = Color.White)
                }
                IconButton(onClick = onTracksClick, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Tune, contentDescription = "字幕 / 音轨", tint = Color.White)
                }
            }

            // Lock is available in BOTH orientations — same lock state, same overlay.
            IconButton(onClick = onLockClick, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = if (locked) "解锁" else "锁定",
                    tint = Color.White,
                )
            }

            IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                    tint = Color.White,
                )
            }
        }
    }
}

private fun playbackModeIcon(mode: PlaybackMode): ImageVector = when (mode) {
    PlaybackMode.SEQUENCE -> Icons.Filled.SkipNext
    PlaybackMode.SINGLE -> Icons.Filled.RepeatOne
    PlaybackMode.LIST -> Icons.Filled.Repeat
    PlaybackMode.SHUFFLE -> Icons.Filled.Shuffle
    PlaybackMode.END_PAUSE -> Icons.Filled.PauseCircleOutline
}

@Composable
private fun PillTextButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(com.hemanager.mobile.player.ui.PlayerColors.ButtonGlass)
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 44.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DimmableIcon(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.size(40.dp)) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
        ) {
            content()
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}
