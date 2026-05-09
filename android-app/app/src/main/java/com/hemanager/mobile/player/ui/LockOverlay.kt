package com.hemanager.mobile.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Lock overlay shown when [locked] is true.
 *
 * Behaviour:
 *  - Absorbs all taps on the video region (`pointerInput { detectTapGestures }` —
 *    this prevents the underlying gesture handler from firing because the lock overlay
 *    sits on top).
 *  - Shows a small "unlock" pill in the centre-left edge that fades out after 2 s of
 *    no taps; another tap brings it back. This matches the spec's "锁定状态下单击屏幕只显示解锁按钮".
 *  - Tapping the unlock pill calls [onUnlock] which clears the lock state.
 *
 * The lock overlay does NOT extend below the controls' Z-stack — caller renders this
 * ABOVE the controls so it always wins.
 */
@Composable
fun LockOverlay(
    locked: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!locked) return

    var unlockButtonVisible by remember { mutableStateOf(true) }

    // Auto-hide the unlock pill after a brief idle window so the video stays clean.
    LaunchedEffect(unlockButtonVisible) {
        if (unlockButtonVisible) {
            delay(2200L)
            unlockButtonVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Absorb every tap so the gesture layer below sees nothing.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { unlockButtonVisible = true })
            },
    ) {
        AnimatedVisibility(
            visible = unlockButtonVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp),
        ) {
            IconButton(
                onClick = onUnlock,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xAA000000)),
            ) {
                Icon(
                    imageVector = Icons.Filled.LockOpen,
                    contentDescription = "解锁",
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Tiny standalone lock-toggle button intended for the bottom control bar in landscape
 * mode. Renders the closed-lock icon when unlocked (the action being "tap to lock")
 * and the open-lock icon when locked (action: "tap to unlock"). Caller toggles state.
 */
@Composable
fun LockToggleIcon(locked: Boolean) {
    Icon(
        imageVector = if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock,
        contentDescription = if (locked) "解锁" else "锁定",
        tint = Color.White,
    )
}
