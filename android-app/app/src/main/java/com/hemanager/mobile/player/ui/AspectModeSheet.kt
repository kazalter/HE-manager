package com.hemanager.mobile.player.ui

import androidx.compose.runtime.Composable
import com.hemanager.mobile.player.model.AspectMode

@Composable
fun AspectModeSheet(
    current: AspectMode,
    onPick: (AspectMode) -> Unit,
    onDismiss: () -> Unit,
) {
    CompactSheet(title = "画面比例", onDismiss = onDismiss) {
        AspectMode.values().forEach { mode ->
            CompactRow(
                label = mode.label,
                selected = mode == current,
                onClick = {
                    onPick(mode)
                    onDismiss()
                },
            )
        }
    }
}
