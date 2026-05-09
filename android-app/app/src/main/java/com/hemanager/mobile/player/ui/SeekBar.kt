package com.hemanager.mobile.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Custom horizontal seek bar with three layered tracks (background / buffered / progress).
 *
 * Phase 1 features: tap-to-seek, drag-to-seek, drag scaling (3 dp idle → 5 dp dragging),
 * thumb appears on drag, callbacks for start/move/end so the parent can suppress the
 * controls auto-hide and update the time bubble while dragging.
 *
 * Drag callbacks are intentionally separate from the [progress]/[buffered] params: this
 * widget doesn't own the position — the ViewModel does. Parent feeds the latest values
 * and reacts to drag events. This keeps state ownership clean across configChanges.
 */
@Composable
fun PlayerSeekBar(
    progress: Float,
    buffered: Float,
    modifier: Modifier = Modifier,
    onScrubStart: () -> Unit = {},
    onScrubMove: (Float) -> Unit = {},
    onScrubEnd: (Float) -> Unit = {},
) {
    var width by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val effectiveProgress = if (dragging) dragFraction else progress.coerceIn(0f, 1f)
    val trackHeight by animateFloatAsState(
        targetValue = if (dragging) 6f else 3f,
        label = "trackHeight",
    )
    val thumbSize by animateFloatAsState(
        targetValue = if (dragging) 14f else 0f,
        label = "thumbSize",
    )
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        if (width <= 0) return@detectTapGestures
                        val fraction = (offset.x / width).coerceIn(0f, 1f)
                        onScrubEnd(fraction)
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (width <= 0) return@detectDragGestures
                        dragging = true
                        dragFraction = (offset.x / width).coerceIn(0f, 1f)
                        onScrubStart()
                        onScrubMove(dragFraction)
                    },
                    onDrag = { change, _ ->
                        if (width <= 0) return@detectDragGestures
                        dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                        onScrubMove(dragFraction)
                        change.consume()
                    },
                    onDragEnd = {
                        onScrubEnd(dragFraction)
                        dragging = false
                    },
                    onDragCancel = {
                        dragging = false
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Background (un-buffered) track.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x33FFFFFF)),
        )
        // Buffered track.
        Box(
            modifier = Modifier
                .fillMaxWidth(buffered.coerceIn(0f, 1f))
                .height(trackHeight.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x66FFFFFF)),
        )
        // Played-progress track.
        Box(
            modifier = Modifier
                .fillMaxWidth(effectiveProgress)
                .height(trackHeight.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(PlayerColors.Primary),
        )
        // Thumb.
        if (thumbSize > 0f && width > 0) {
            val xPx = (effectiveProgress * width).roundToInt()
            val halfPx = with(density) { (thumbSize / 2f).dp.toPx() }.roundToInt()
            Box(
                modifier = Modifier
                    .offset { IntOffset(xPx - halfPx, 0) }
                    .size(thumbSize.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}
