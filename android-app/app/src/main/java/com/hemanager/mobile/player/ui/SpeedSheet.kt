package com.hemanager.mobile.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val PLAYER_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

/**
 * Compact speed picker. Renders as a small modal, NOT full-width — the playback area
 * around it stays visible. Sized to fit comfortably on phones in either orientation
 * (tested down to ~360 dp wide / ~360 dp tall in landscape) with internal scroll if
 * the option list ever exceeds the available height.
 *
 * Visual baseline matches [AspectModeSheet] and [TracksSheet] via [CompactSheet].
 */
@Composable
fun SpeedSheet(
    currentSpeed: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    CompactSheet(title = "倍速", onDismiss = onDismiss) {
        PLAYER_SPEEDS.forEach { speed ->
            CompactRow(
                label = formatSpeed(speed),
                selected = approxEquals(speed, currentSpeed),
                onClick = {
                    onPick(speed)
                    onDismiss()
                },
            )
        }
    }
}

fun formatSpeed(speed: Float): String {
    if (approxEquals(speed, 1f)) return "1x"
    val s = ("%.2f".format(speed)).trimEnd('0').trimEnd('.')
    return "${s}x"
}

private fun approxEquals(a: Float, b: Float, tolerance: Float = 0.001f): Boolean =
    kotlin.math.abs(a - b) < tolerance

// ---------- Shared compact-sheet primitives (used by Speed/Aspect/Tracks) ----------

/**
 * Tight Dialog wrapper styled like a small popup card. Sized constraints:
 *  - Width: 200..300 dp (fits comfortably on a 360 dp phone screen, leaves the video
 *    visible behind the sheet on either side).
 *  - Height: capped to 70 % of available height with internal scroll fallback so even
 *    a long option list stays inside the screen.
 */
@Composable
fun CompactSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 200.dp, max = 300.dp)
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PlayerColors.Surface)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = title,
                color = PlayerColors.OnSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
            )
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

@Composable
fun CompactRow(
    label: String,
    selected: Boolean,
    sub: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = label,
                color = if (selected) PlayerColors.Primary else PlayerColors.OnSurface,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (!sub.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(text = sub, color = PlayerColors.OnSurfaceVariant, fontSize = 11.sp)
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = PlayerColors.Primary,
                modifier = Modifier.width(18.dp),
            )
        } else {
            Spacer(modifier = Modifier.width(18.dp))
        }
    }
}

@Composable
fun CompactSectionHeader(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
    ) {
        Text(
            text = title,
            color = PlayerColors.OnSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}

@Composable
fun CompactEmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text = text, color = PlayerColors.OnSurfaceVariant, fontSize = 12.sp)
    }
}
