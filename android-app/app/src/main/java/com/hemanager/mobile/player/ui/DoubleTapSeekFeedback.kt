package com.hemanager.mobile.player.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.abs

enum class DoubleTapSide { LEFT, RIGHT }

/**
 * Half-screen double-tap feedback overlay.
 *
 * Visual:
 *  - Soft horizontal gradient mask covering the tapped half (darker on the outer edge,
 *    transparent toward the screen centre — never blocks the centre of the video).
 *  - Centred vertically, offset toward the inner side: arrows + accumulated seconds.
 *  - Three arrow chars whose alphas pulse with phase offsets to suggest a wave flowing
 *    OUTWARD (away from the screen centre) — matches the spec's "依次变亮并向外扩散".
 *  - Crossfade enter/exit ~300 ms.
 *
 * Behaviour (parent owns the state):
 *  - [side] ≠ null and [visible] = true → fade in.
 *  - [visible] = false → fade out, stays mounted briefly so the alpha can animate down.
 *  - The parent restarts a 600 ms commit timer on each accumulation; when the timer
 *    fires, it calls `seekBy(accumulatedSeconds * 1000)` and flips [visible] to false.
 *
 * The component never seeks anything itself — it's purely visual.
 */
@Composable
fun DoubleTapSeekFeedback(
    side: DoubleTapSide?,
    accumulatedSeconds: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetAlpha = if (visible && side != null && accumulatedSeconds != 0) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 280, easing = LinearOutSlowInEasing),
        label = "doubleTapFade",
    )
    if (alpha < 0.01f || side == null) return

    Box(modifier = modifier.fillMaxSize().alpha(alpha)) {
        // Half-screen gradient. Dark on the outer edge, fully transparent toward the
        // centre — keeps the focus area of the video readable even while the overlay
        // is up.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .align(if (side == DoubleTapSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd)
                .background(
                    Brush.horizontalGradient(
                        colors = when (side) {
                            DoubleTapSide.LEFT -> listOf(Color(0x66000000), Color(0x00000000))
                            DoubleTapSide.RIGHT -> listOf(Color(0x00000000), Color(0x66000000))
                        },
                    ),
                ),
        )

        // Content positioned toward the inner edge of the same half.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.5f)
                .align(if (side == DoubleTapSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd),
            contentAlignment = if (side == DoubleTapSide.LEFT) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            DoubleTapContent(
                side = side,
                seconds = abs(accumulatedSeconds),
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

@Composable
private fun DoubleTapContent(side: DoubleTapSide, seconds: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        when (side) {
            DoubleTapSide.LEFT -> {
                FlowingArrows(side = side)
                Spacer(modifier = Modifier.width(10.dp))
                TimeText(seconds = seconds)
            }
            DoubleTapSide.RIGHT -> {
                TimeText(seconds = seconds)
                Spacer(modifier = Modifier.width(10.dp))
                FlowingArrows(side = side)
            }
        }
    }
}

@Composable
private fun TimeText(seconds: Int) {
    Text(
        text = "${seconds}s",
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        style = TextStyle(
            shadow = Shadow(
                color = Color(0xCC000000),
                offset = Offset(0f, 1f),
                blurRadius = 8f,
            ),
        ),
    )
}

/**
 * Three arrow characters whose alphas flow outward (away from the time text) in a
 * phase-shifted wave. We use one [rememberInfiniteTransition] driving three independent
 * `animateFloat`s with [StartOffset]s — each arrow is on its own slightly delayed cycle
 * so the visual pulse feels continuous rather than synchronised.
 */
@Composable
private fun FlowingArrows(side: DoubleTapSide) {
    val transition = rememberInfiniteTransition(label = "arrows")
    val cycle = 350
    val a0 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable<Float>(
            animation = tween(durationMillis = cycle, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(0),
        ),
        label = "a0",
    )
    val a1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable<Float>(
            animation = tween(durationMillis = cycle, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(140),
        ),
        label = "a1",
    )
    val a2 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable<Float>(
            animation = tween(durationMillis = cycle, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(280),
        ),
        label = "a2",
    )

    // Visual layout for LEFT:  [outer  middle  inner]  ‹ ‹ ‹  text
    //   wave should go inner→outer, so leftmost arrow is the LAST (a2),
    //   middle is a1, rightmost (closest to text) is the FIRST (a0).
    // For RIGHT: text  › › ›  [inner middle outer]
    //   wave goes inner→outer, leftmost (closest to text) first (a0).
    val arrowChar: String = if (side == DoubleTapSide.LEFT) "‹" else "›"
    val orderedAlphas: List<Float> = when (side) {
        DoubleTapSide.LEFT -> listOf(a2, a1, a0)
        DoubleTapSide.RIGHT -> listOf(a0, a1, a2)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (arrowAlpha in orderedAlphas) {
            Text(
                text = arrowChar,
                color = Color.White.copy(alpha = arrowAlpha),
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0xAA000000),
                        offset = Offset(0f, 1f),
                        blurRadius = 6f,
                    ),
                ),
            )
        }
    }
}
