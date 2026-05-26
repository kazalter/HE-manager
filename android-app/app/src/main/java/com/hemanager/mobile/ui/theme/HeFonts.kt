package com.hemanager.mobile.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * HE OP 字体角色定义。
 *
 * **当前状态：占位**。等 14 个 TTF 文件放入 `app/src/main/res/font/` 后，
 * 把下面 4 个 `FontFamily.Default` / `Monospace` 替换成正式的 `FontFamily(Font(R.font.xxx, ...))`。
 *
 * 需要的 TTF（来自 Google Fonts）：
 *   oxanium_regular / oxanium_semibold / oxanium_bold
 *   geist_regular / geist_medium / geist_semibold / geist_bold
 *   geist_mono_regular / geist_mono_medium / geist_mono_semibold
 *   noto_sans_sc_medium / noto_sans_sc_bold / noto_sans_sc_black
 *
 * 角色分工：
 *   - [Oxanium]   英文 display / ALL-CAPS 标签 / 大字标题 / CTA 文字
 *   - [Geist]     英文 UI body 文字（次要）
 *   - [GeistMono] 数字、装饰代码（VID-A001 / UID:1416-176-661 / 12ms / @handle / 04:32）
 *   - [NotoSansSC] 中文（Black 900 大标题、Bold 700 卡片标题、Medium 500 正文）
 *
 * 切换 fallback → real 字体时，整套 UI 不需要任何其他改动。
 */

// TODO: 等 res/font/ 放好 TTF 后切到 FontFamily(Font(R.font.oxanium_xxx, ...))
val Oxanium: FontFamily = FontFamily.Default

// TODO: 等 res/font/ 放好 TTF 后切到 FontFamily(Font(R.font.geist_xxx, ...))
val Geist: FontFamily = FontFamily.Default

// TODO: 等 res/font/ 放好 TTF 后切到 FontFamily(Font(R.font.geist_mono_xxx, ...))
val GeistMono: FontFamily = FontFamily.Monospace

// TODO: 等 res/font/ 放好 TTF 后切到 FontFamily(Font(R.font.noto_sans_sc_xxx, ...))
// 中文 fallback：Android 系统默认字体链已含 Noto Sans CJK，先用 Default 不会显示不出来。
val NotoSansSC: FontFamily = FontFamily.Default
