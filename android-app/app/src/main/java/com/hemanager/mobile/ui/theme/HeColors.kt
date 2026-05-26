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
    // ===================================================================
    // HE OP 设计语言（黑底 + 单一高饱和黄 + hairline）—— 用于新版库/登录/创作者屏
    // 旧的蓝紫 token 在下方保留给 player / audio 模块，不要删。
    // ===================================================================

    // ---- HE OP 表面层（偏冷的近黑） ----
    val Void       = Color(0xFF08090C)  // 最深 — 顶级背景、状态条
    val Ink        = Color(0xFF0F1014)  // 主背景
    val Panel      = Color(0xFF16171D)  // 卡片背景
    val OpSurface  = Color(0xFF1B1D25)  // 抬升卡片 / drawer item active
    val SurfaceAlt = Color(0xFF23252F)

    // ---- HE OP 描边（hairline 系统） ----
    val Hairline    = Color(0x12FFFFFF) // alpha 0.07 — 标准 separator
    val HairlineMid = Color(0x24FFFFFF) // alpha 0.14 — 卡片描边
    val HairlineHi  = Color(0x38FFFFFF) // alpha 0.22 — 强调描边

    // ---- HE OP 文字 ----
    val OpWhite      = Color(0xFFF2F1ED)  // 主文字
    val OpWhiteSoft  = Color(0xFFB8B9C2)  // 次要文字
    val OpWhiteMuted = Color(0xFF6E6F78)  // muted / metadata
    val OpWhiteFaint = Color(0xFF3C3D45)  // 极淡 / disabled

    // ---- HE OP 签名色：高饱和黄（绝对克制使用） ----
    val Yellow     = Color(0xFFF5D800)
    val YellowDim  = Color(0xFFB8A100)
    val YellowSoft = Color(0x1AF5D800)  // alpha 0.10

    // ---- HE OP 次要 accent（极少用） ----
    val Cyan       = Color(0xFF5CE5D7)
    val CyanSoft   = Color(0x1A5CE5D7)

    // ---- HE OP 状态色 ----
    val Online   = Color(0xFF7BC494)
    val OpDanger = Color(0xFFFF5C5C)

    // ---- CTA 文字色（在 Yellow 上） ----
    val OnYellow = Color(0xFF0E0F00)

    // ===================================================================
    // 旧蓝紫色板 —— player / audio / 当前 library V2 仍在用，保留
    // ===================================================================

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
