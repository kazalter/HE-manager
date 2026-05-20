package com.hemanager.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * App 的标志性深色径向渐变背景。
 *
 * Login/Library/Creators 顶层容器都用这个背景，确保跨屏视觉一致。
 * 颜色当前是硬编码，未来如需主题变体可迁到 [HeColors]。
 */
@Composable
fun AppBackgroundBrush(): Brush {
    return Brush.radialGradient(
        listOf(
            Color(0xFF17213C),
            Color(0xFF0B1020),
            Color(0xFF070A12)
        ),
        radius = 1250f
    )
}
