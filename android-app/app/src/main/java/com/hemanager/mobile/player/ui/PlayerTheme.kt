package com.hemanager.mobile.player.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hemanager.mobile.ui.theme.HeColorScheme
import com.hemanager.mobile.ui.theme.HeColors

/**
 * Player 模块色板入口。
 *
 * 历史原因，player 包里大量代码引用 [PlayerColors.Primary] / [PlayerColors.ScrimTop] 等常量。
 * 这些值现在统一在 [HeColors] 维护；此对象只是薄转发壳，保留 API 不破坏现有代码。
 *
 * 新代码建议直接 import [com.hemanager.mobile.ui.theme.HeColors]。
 */
object PlayerColors {
    // ---- 主色 ----
    val Primary: Color get() = HeColors.Primary
    val Secondary: Color get() = HeColors.Secondary
    val Tertiary: Color get() = HeColors.Tertiary

    // ---- 表面 ----
    val Background: Color get() = HeColors.Background
    val Surface: Color get() = HeColors.Surface
    val SurfaceVariant: Color get() = HeColors.SurfaceVariant
    val PrimaryContainer: Color get() = HeColors.PrimaryContainer

    // ---- 前景 ----
    val OnSurface: Color get() = HeColors.OnSurface
    val OnSurfaceVariant: Color get() = HeColors.OnSurfaceVariant
    val OnSurfaceMuted: Color get() = HeColors.OnSurfaceMuted

    // ---- 语义 ----
    val Danger: Color get() = HeColors.Danger

    // ---- Player 专用蒙层 ----
    val ScrimTop: Brush get() = HeColors.ScrimTop
    val ScrimBottom: Brush get() = HeColors.ScrimBottom

    val ButtonGlass: Color get() = HeColors.ButtonGlass
    val ButtonGlassPressed: Color get() = HeColors.ButtonGlassPressed
}

/**
 * Player 模块沿用全局 [HeColorScheme]。保留旧名让现有 import 继续生效。
 */
val PlayerColorScheme: ColorScheme = HeColorScheme
