package com.hemanager.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 全局色板 —— 整个 app 的颜色单一真相源。
 *
 * 同时被 MainActivity 的 colorScheme 和 player/ASMR 模块使用，
 * 避免 PlayerTheme.kt 早期版本的「两份重复定义、改一处忘一处」问题。
 *
 * 添加新颜色时优先放这里；语义色（状态、强调）见 [HeColors.Status]。
 */
object HeColors {
    // ---- 主色 ----
    val Primary = Color(0xFF8EA2FF)
    val Secondary = Color(0xFF58E6C2)
    val Tertiary = Color(0xFFF6C46B)

    // ---- 表面 / 背景 ----
    val Background = Color(0xFF070A12)
    val Surface = Color(0xFF111620)
    val SurfaceVariant = Color(0xFF202838)
    val PrimaryContainer = Color(0xFF28336D)
    val SecondaryContainer = Color(0xFF123C34)
    val TertiaryContainer = Color(0xFF483615)
    val ErrorContainer = Color(0xFF54212F)

    // ---- 前景 / 文字 ----
    val OnPrimary = Color(0xFF080B12)
    val OnSecondary = Color(0xFF0E1715)
    val OnTertiary = Color(0xFF15100A)
    val OnBackground = Color(0xFFF4F7FF)
    val OnSurface = Color(0xFFF4F7FF)
    val OnSurfaceVariant = Color(0xFFC6CFDD)
    val OnSurfaceMuted = Color(0xB3F4F7FF)
    val OnErrorContainer = Color(0xFFFFD9DD)

    // ---- 语义 / 状态 ----
    val Danger = Color(0xFFFF8FA3)

    /**
     * 媒体观看状态相关的语义色。
     * 这些值此前散落在 MainActivity 的 `statusAccentV2` / `progressColorV2` 中，
     * 后续可逐步迁移到这里。
     */
    object Status {
        val Viewed = Color(0xFF58E6C2)      // 已看完（绿）
        val Viewing = Color(0xFF8EA2FF)     // 继续看（蓝紫）
        val Favorite = Color(0xFFF6C46B)    // 收藏（金）
        val Missing = Danger                // 文件缺失
    }

    // ---- Player 模块用的半透明蒙层 ----
    val ScrimTop: Brush = Brush.verticalGradient(
        listOf(Color(0xCC000000), Color(0x00000000)),
    )
    val ScrimBottom: Brush = Brush.verticalGradient(
        listOf(Color(0x00000000), Color(0xDD000000)),
    )

    val ButtonGlass = Color(0x66000000)
    val ButtonGlassPressed = Color(0xAA000000)
}

/**
 * 整个 app 默认使用的 Material3 dark color scheme。
 *
 * MainActivity 和未来的 ASMR / Player 容器都引用这一份，
 * 避免重复声明 darkColorScheme(...)。
 */
val HeColorScheme: ColorScheme = darkColorScheme(
    primary = HeColors.Primary,
    secondary = HeColors.Secondary,
    tertiary = HeColors.Tertiary,
    background = HeColors.Background,
    surface = HeColors.Surface,
    surfaceVariant = HeColors.SurfaceVariant,
    primaryContainer = HeColors.PrimaryContainer,
    secondaryContainer = HeColors.SecondaryContainer,
    tertiaryContainer = HeColors.TertiaryContainer,
    errorContainer = HeColors.ErrorContainer,
    onPrimary = HeColors.OnPrimary,
    onSecondary = HeColors.OnSecondary,
    onTertiary = HeColors.OnTertiary,
    onBackground = HeColors.OnBackground,
    onSurface = HeColors.OnSurface,
    onSurfaceVariant = HeColors.OnSurfaceVariant,
    onErrorContainer = HeColors.OnErrorContainer,
)
