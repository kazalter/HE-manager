package com.hemanager.mobile.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hemanager.mobile.R

/**
 * HE OP 字体家族定义。
 *
 * 字体源文件位于 `app/src/main/res/font/`。
 * 角色分工：
 *   - [Oxanium]   英文 display / ALL-CAPS 标签 / 大字标题 / CTA 文字
 *   - [Geist]     英文 UI body 文字（次要）
 *   - [GeistMono] 数字、装饰代码（VID-A001 / UID:1416-176-661 / 12ms / @handle / 04:32）
 *   - [NotoSansSC] 中文（Black 900 大标题、Bold 700 卡片标题、Medium 500 正文）
 *
 * **Oxanium 是 variable font**（单文件含 wght 轴 100-800）。Compose 在 API 26+ 会用轴渲染对应
 * weight；API 23-25 退化为默认 weight（差别不大）。
 */

val Oxanium: FontFamily = FontFamily(
    Font(R.font.oxanium_variable, FontWeight.Normal),
    Font(R.font.oxanium_variable, FontWeight.SemiBold),
    Font(R.font.oxanium_variable, FontWeight.Bold),
)

val Geist: FontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val GeistMono: FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
)

val NotoSansSC: FontFamily = FontFamily(
    Font(R.font.noto_sans_sc_medium, FontWeight.Medium),
    Font(R.font.noto_sans_sc_bold, FontWeight.Bold),
    Font(R.font.noto_sans_sc_black, FontWeight.Black),
)
