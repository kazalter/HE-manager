package com.hemanager.mobile.ui.op

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.hemanager.mobile.ui.theme.Corner
import com.hemanager.mobile.ui.theme.CutCornerShape
import com.hemanager.mobile.ui.theme.HeColors

/**
 * HE OP 主面板容器：切角 + hairline 描边 + 可选黄角封口。
 *
 * **实装注意**：hairline 描边走 `Modifier.border(1.dp, color, shape)`——直接画在 shape 上，
 * 与切角天然贴合，不会有 sub-pixel 抖动（这是文档里反复强调的坑）。
 *
 * @param corners 哪些角切角，默认 TR + BL
 * @param yellowCorner true 时在 TR 角叠一个黄色三角"封口"装饰
 * @param hairline true 时画 1dp `HairlineMid` 描边
 */
@Composable
fun AngularPanel(
    modifier: Modifier = Modifier,
    cut: Dp = 14.dp,
    corners: Set<Corner> = setOf(Corner.TR, Corner.BL),
    background: Color = HeColors.Panel,
    yellowCorner: Boolean = false,
    hairline: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = CutCornerShape(
        cut = cut,
        tr = Corner.TR in corners,
        tl = Corner.TL in corners,
        br = Corner.BR in corners,
        bl = Corner.BL in corners,
    )
    // 关键修正：早期版本用 Box(modifier) + 内层 Box(matchParentSize)，外层没有非
    // matchParentSize 的子节点撑大尺寸 → 整 panel 塌成 0×0。修法是让"内容 Box"自身就
    // 当主容器（modifier 直接作用其上，content 撑大它），可选的封口三角用 Box overlay
    // 包一层时再 align 到 TopEnd——但只在确实需要时才包，避免 yellowCorner=false 时
    // 多余一层 Box 包装。
    if (yellowCorner && Corner.TR in corners) {
        Box(modifier = modifier) {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(background)
                    .then(if (hairline) Modifier.border(1.dp, HeColors.HairlineMid, shape) else Modifier)
                    .padding(contentPadding),
                content = content,
            )
            YellowCornerSeal(
                size = cut,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(background)
                .then(if (hairline) Modifier.border(1.dp, HeColors.HairlineMid, shape) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * 黄色切角封口三角：贴在 panel 右上角，覆盖切掉的那块缺口。
 *
 * 用 Canvas 画 `[0,0] → [size,0] → [size,size]` 三点三角形。
 */
@Composable
fun YellowCornerSeal(
    size: Dp = 10.dp,
    color: Color = HeColors.Yellow,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.width
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(s, 0f)
            lineTo(s, s)
            close()
        }
        drawPath(path = path, color = color)
    }
}
