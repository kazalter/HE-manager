package com.hemanager.mobile.ui.op

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemanager.mobile.ui.theme.CutCornerShape
import com.hemanager.mobile.ui.theme.Geist
import com.hemanager.mobile.ui.theme.HeColors
import com.hemanager.mobile.ui.theme.Oxanium

enum class CtaSize { Small, Medium, Large }

private data class CtaMetrics(
    val padV: Dp, val padH: Dp, val fontSize: TextUnit, val cut: Dp, val iconSize: Dp,
)

private fun metricsFor(size: CtaSize): CtaMetrics = when (size) {
    CtaSize.Small  -> CtaMetrics(8.dp,  16.dp, 12.sp,   8.dp, 13.dp)
    CtaSize.Medium -> CtaMetrics(11.dp, 20.dp, 13.5.sp, 10.dp, 14.5.dp)
    CtaSize.Large  -> CtaMetrics(14.dp, 26.dp, 15.sp,   10.dp, 16.dp)
}

/**
 * 主 CTA：黄底切角，OnYellow 文字，Oxanium Bold ALL-CAPS。
 *
 * 文档规则：黄色只允许出现在 1 个主 CTA + active 状态 + 切角封口等少数地方。
 * 不要拿来当普通按钮用。
 */
@Composable
fun YellowCTA(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    size: CtaSize = CtaSize.Medium,
    fullWidth: Boolean = false,
) {
    val m = metricsFor(size)
    val shape = CutCornerShape(m.cut)
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(HeColors.Yellow)
            .clickable { onClick() }
            .padding(horizontal = m.padH, vertical = m.padV),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = HeColors.OnYellow,
                    modifier = Modifier.size(m.iconSize),
                )
            }
            Text(
                text = text,
                color = HeColors.OnYellow,
                fontFamily = Oxanium,
                fontWeight = FontWeight.Bold,
                fontSize = m.fontSize,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

/**
 * 次级按钮：黑底 + 1dp HairlineMid 描边 + 白字 + 切角。
 */
@Composable
fun GhostCta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    size: CtaSize = CtaSize.Medium,
    fullWidth: Boolean = false,
) {
    val m = metricsFor(size)
    val shape = CutCornerShape(m.cut)
    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(HeColors.Ink)
            .border(1.dp, HeColors.HairlineMid, shape)
            .clickable { onClick() }
            .padding(horizontal = m.padH, vertical = m.padV),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = HeColors.OpWhite,
                    modifier = Modifier.size(m.iconSize),
                )
            }
            Text(
                text = text,
                color = HeColors.OpWhite,
                fontFamily = Oxanium,
                fontWeight = FontWeight.Bold,
                fontSize = m.fontSize,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

/**
 * 筛选条上的切角小药丸：active 时黄底，否则透明 + hairline 描边。
 *
 * EN 小字作为 ALL-CAPS 尾巴。active 状态左侧叠一个 6dp Diamond。
 */
@Composable
fun FilterTab(
    label: String,
    en: String? = null,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CutCornerShape(7.dp)
    val bgMod = if (active)
        Modifier.background(HeColors.Yellow)
    else
        Modifier.background(Color.Transparent).border(1.dp, HeColors.HairlineMid, shape)
    val labelColor = if (active) HeColors.OnYellow else HeColors.OpWhiteSoft

    Box(
        modifier = modifier
            .clip(shape)
            .then(bgMod)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (active) Diamond(6.dp, color = HeColors.OnYellow)
            Text(
                text = label,
                color = labelColor,
                fontFamily = Geist,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            )
            if (en != null) {
                Text(
                    text = en,
                    color = labelColor,
                    fontFamily = Oxanium,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.alpha(0.6f),
                )
            }
        }
    }
}

/**
 * 圆形描边 icon 按钮（顶栏菜单 / 搜索 / 返回）。
 *
 * 36dp 圆，Ink 背景 + 1dp HairlineMid 描边，居中 icon。
 */
@Composable
fun IconBtn4(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 36.dp,
    iconSize: Dp = 16.dp,
    tint: Color = HeColors.OpWhite,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(HeColors.Ink)
            .border(1.dp, HeColors.HairlineMid, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
