package com.hemanager.mobile.ui.op

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.hemanager.mobile.ui.theme.GeistMono
import com.hemanager.mobile.ui.theme.HeColors
import com.hemanager.mobile.ui.theme.NotoSansSC
import com.hemanager.mobile.ui.theme.Oxanium

/**
 * 鹰角签名标签：`// 中文 EN`。
 *
 * 黄色 mono `//` 前缀 + ALL-CAPS Oxanium 中文 label，可选英文小字尾巴。
 * 用于 section 头、小标题、强调短语。文字 ALL-CAPS 由调用方手写大写字符串，
 * **不要**用 `.uppercase()`——会把中文也大写化（虽不影响显示但语义不对）。
 */
@Composable
fun Slash(
    cn: String,
    en: String? = null,
    fontSize: TextUnit = 12.sp,
    color: Color = HeColors.OpWhiteSoft,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = "//",
            color = HeColors.Yellow,
            fontFamily = GeistMono,
            fontSize = (fontSize.value + 1f).sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = cn,
            color = color,
            fontFamily = Oxanium,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
        )
        if (en != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = en,
                color = HeColors.OpWhiteMuted,
                fontFamily = Oxanium,
                fontSize = (fontSize.value - 1.5f).sp,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

/**
 * 双层中英标题：上 NotoSansSC Black 中文，下 Oxanium SemiBold 大写英文小字。
 *
 * @param sizeSp 中文字号；英文字号自动取 32%。
 */
@Composable
fun OpTitle(
    cn: String,
    en: String? = null,
    sizeSp: TextUnit = 32.sp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = cn,
            color = HeColors.OpWhite,
            fontFamily = NotoSansSC,
            fontWeight = FontWeight.Black,
            fontSize = sizeSp,
            letterSpacing = (-1).sp,
            lineHeight = sizeSp,
        )
        if (en != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = en,
                color = HeColors.OpWhiteMuted,
                fontFamily = Oxanium,
                fontWeight = FontWeight.SemiBold,
                fontSize = (sizeSp.value * 0.32f).sp,
                letterSpacing = 3.5.sp,
            )
        }
    }
}

/**
 * 装饰代码 chip：`VID-A001` / `UID:1416-176-661` / `@handle` 等。
 *
 * GeistMono Medium 9.5sp 默认 muted 灰；hero 上常用 Yellow。
 */
@Composable
fun CodeChip(
    text: String,
    color: Color = HeColors.OpWhiteMuted,
    fontSize: TextUnit = 10.5.sp,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontFamily = GeistMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
        letterSpacing = 0.8.sp,
        modifier = modifier,
    )
}

/**
 * 旋转方块标记。filled = 实心，false = 1.2dp 线宽描边。
 *
 * 用法：active 状态指示、Rank 计数、FilterTab active 时左侧装饰。
 */
@Composable
fun Diamond(
    size: Dp = 8.dp,
    filled: Boolean = true,
    color: Color = HeColors.Yellow,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .rotate(45f)
            .then(
                if (filled) Modifier.background(color)
                else Modifier.border(1.2.dp, color)
            )
    )
}
