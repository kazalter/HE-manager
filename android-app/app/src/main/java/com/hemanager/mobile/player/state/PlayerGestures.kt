package com.hemanager.mobile.player.state

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** Categories the gesture recogniser can resolve a drag into. */
enum class DragKind { NONE, SEEK, BRIGHTNESS, VOLUME }

/**
 * Combined player-region gesture handler. Routes the same touch stream into:
 *  - tap / double-tap / long-press (via [detectTapGestures])
 *  - drag with axis-and-side detection (via [awaitEachGesture])
 *
 * Two separate `pointerInput` blocks coexist: Compose's gesture system arbitrates between
 * them — a finger that drags will cancel the tap detector, and a finger that just taps
 * never clears the drag's slop. This is the canonical pattern.
 *
 * Conflict-handling rules:
 *  - Slop: ignore drags below ~touchSlop.
 *  - Axis lock: horizontal vs vertical based on which delta is larger AND ≥ 1.35× the
 *    other (matches the original Java impl). Below ratio → no kind yet.
 *  - Side lock for vertical: left half → brightness, right half → volume. Decided once
 *    based on where the finger went DOWN. Once committed, we ride that axis to release.
 *
 * When [enabled] is false (locked screen), this whole modifier is a no-op so taps/drags
 * fall through to whatever the locked overlay shows.
 */
fun Modifier.playerGestures(
    enabled: Boolean,
    widthPx: Float,
    onSingleTap: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekDelta: (deltaPx: Float, totalDeltaPx: Float) -> Unit,
    onSeekEnd: () -> Unit,
    onBrightnessStart: () -> Unit,
    onBrightnessDelta: (deltaPx: Float) -> Unit,
    onBrightnessEnd: () -> Unit,
    onVolumeStart: () -> Unit,
    onVolumeDelta: (deltaPx: Float) -> Unit,
    onVolumeEnd: () -> Unit,
): Modifier {
    if (!enabled) return this
    return this
        .pointerInput(Unit) {
            // Strict single-tap-only contract:
            //  - onTap fires ONLY for a confirmed clean tap-up after the double-tap
            //    arbitration window. detectTapGestures handles the cancellation
            //    semantics: if a 2nd tap arrives → onDoubleTap fires and onTap does
            //    NOT; if movement breaks slop → no tap; if held past long-press →
            //    onLongPress fires and onTap does NOT.
            //  - This means single-tap is the ONLY thing that toggles controls. No
            //    other gesture (double-tap, drag, long-press, scrub) changes the
            //    controls' visibility — exactly what the spec asks for.
            //
            // The long-press flag is shared with onPress so onLongPressEnd fires when
            // the finger eventually lifts after a long-press fired.
            val longPressFlag = booleanArrayOf(false)
            detectTapGestures(
                onPress = {
                    longPressFlag[0] = false
                    try {
                        tryAwaitRelease()
                    } finally {
                        if (longPressFlag[0]) onLongPressEnd()
                    }
                },
                onTap = { onSingleTap() },
                onDoubleTap = { offset -> onDoubleTap(offset) },
                onLongPress = {
                    longPressFlag[0] = true
                    onLongPressStart()
                },
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down: PointerInputChange = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Main,
                )
                val startX = down.position.x
                val slop = viewConfiguration.touchSlop
                val ratio = 1.35f

                var kind = DragKind.NONE
                var totalDx = 0f
                var totalDy = 0f

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.first()
                    if (!change.pressed) break

                    val deltaX = change.positionChange().x
                    val deltaY = change.positionChange().y
                    totalDx += deltaX
                    totalDy += deltaY

                    if (kind == DragKind.NONE) {
                        val ax = abs(totalDx)
                        val ay = abs(totalDy)
                        if (ax > slop && ax > ay * ratio) {
                            kind = DragKind.SEEK
                            onSeekStart()
                        } else if (ay > slop && ay > ax * ratio) {
                            kind = if (startX < widthPx / 2f) DragKind.BRIGHTNESS else DragKind.VOLUME
                            when (kind) {
                                DragKind.BRIGHTNESS -> onBrightnessStart()
                                DragKind.VOLUME -> onVolumeStart()
                                else -> Unit
                            }
                        }
                    }

                    when (kind) {
                        DragKind.SEEK -> {
                            change.consume()
                            onSeekDelta(deltaX, totalDx)
                        }
                        DragKind.BRIGHTNESS -> {
                            change.consume()
                            onBrightnessDelta(deltaY)
                        }
                        DragKind.VOLUME -> {
                            change.consume()
                            onVolumeDelta(deltaY)
                        }
                        DragKind.NONE -> Unit
                    }
                }

                when (kind) {
                    DragKind.SEEK -> onSeekEnd()
                    DragKind.BRIGHTNESS -> onBrightnessEnd()
                    DragKind.VOLUME -> onVolumeEnd()
                    DragKind.NONE -> Unit
                }
            }
        }
}
