package com.hemanager.mobile.player.ui.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hemanager.mobile.player.model.PlayerStatus
import com.hemanager.mobile.player.ui.PlayerColors

/**
 * Center controls: rewind 10s, big play/pause, forward 10s.
 *
 * The play/pause icon scales up briefly on each press — both for tactile feedback and
 * to disguise the icon swap.
 */
@Composable
fun CenterControls(
    status: PlayerStatus,
    isPlaying: Boolean,
    onRewind: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(targetValue = if (isPlaying) 1f else 1.06f, label = "playScale")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onRewind,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(PlayerColors.ButtonGlass),
        ) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = "快退 10 秒",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }

        IconButton(
            onClick = onTogglePlay,
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(PlayerColors.ButtonGlass),
        ) {
            val ended = status is PlayerStatus.Ended
            Icon(
                imageVector = when {
                    ended -> Icons.Filled.Replay
                    isPlaying -> Icons.Filled.Pause
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }

        IconButton(
            onClick = onForward,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(PlayerColors.ButtonGlass),
        ) {
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = "快进 10 秒",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
