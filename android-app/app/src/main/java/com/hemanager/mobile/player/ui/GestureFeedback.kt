package com.hemanager.mobile.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
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
import java.util.Locale

/**
 * Container for all transient gesture feedback overlays. Each overlay renders only when
 * its corresponding state field is non-null/true so the GPU isn't drawing invisible
 * elements during normal playback.
 */
@Composable
fun GestureFeedback(
    seekIndicator: SeekIndicator?,
    barIndicator: BarIndicator?,
    showLongPress2x: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        seekIndicator?.let { SeekFeedbackBubble(it, Modifier.align(Alignment.Center)) }
        barIndicator?.let { BarFeedbackPill(it, Modifier.align(Alignment.Center)) }
        AnimatedVisibility(
            visible = showLongPress2x,
            enter = fadeIn() + scaleIn(initialScale = 0.92f),
            exit = fadeOut() + scaleOut(targetScale = 0.92f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 88.dp),
        ) {
            LongPressBubble()
        }
    }
}

/**
 * Indicator for an in-progress seek gesture. Used for horizontal-drag scrub only —
 * double-tap ±10s feedback has its own specialised overlay in [DoubleTapSeekFeedback].
 */
sealed interface SeekIndicator {
    data class Scrub(val targetMs: Long, val durationMs: Long) : SeekIndicator
}

/** Brightness or volume bar pill. [fraction] is 0..1. */
data class BarIndicator(val kind: Kind, val fraction: Float) {
    enum class Kind { BRIGHTNESS, VOLUME }
}

@Composable
private fun SeekFeedbackBubble(indicator: SeekIndicator, modifier: Modifier) {
    when (indicator) {
        is SeekIndicator.Scrub -> {
            val text = formatDual(indicator.targetMs, indicator.durationMs)
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xDD111318))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BarFeedbackPill(indicator: BarIndicator, modifier: Modifier) {
    val icon: ImageVector = when (indicator.kind) {
        BarIndicator.Kind.BRIGHTNESS -> Icons.Filled.BrightnessHigh
        BarIndicator.Kind.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xDD111318))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(indicator.fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(PlayerColors.Primary),
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = "${(indicator.fraction * 100).toInt()}%",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LongPressBubble() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xDD111318))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Speed,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text("2x 快进中", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

private fun formatDual(positionMs: Long, durationMs: Long): String {
    return formatTime(positionMs) + " / " + formatTime(durationMs)
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}
