package com.hemanager.mobile.ui.op

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hemanager.mobile.ui.theme.Corner
import com.hemanager.mobile.ui.theme.HeColors

/**
 * 四角 L 形 registration marks（HUD chrome 装饰）。
 *
 * 用法：覆盖在 hero / 卡片封面 / 大图上层做"工业终端取景框"感。
 * 单个 Canvas 画 8 个 rect（每个 L = 2 rect），避免多 Composable 重组开销。
 *
 * @param inset 距离各边缘的内缩
 * @param length L 形单边长度
 * @param hideCorners 不画的角（例如卡片下方有信息条时 hide BL/BR）
 */
@Composable
fun HudBrackets(
    modifier: Modifier = Modifier,
    inset: Dp = 8.dp,
    length: Dp = 12.dp,
    thickness: Dp = 1.2.dp,
    color: Color = HeColors.Yellow,
    hideCorners: Set<Corner> = emptySet(),
) {
    Canvas(modifier.fillMaxSize()) {
        val i = inset.toPx()
        val l = length.toPx()
        val t = thickness.toPx()
        val w = size.width
        val h = size.height

        // 每个 corner：(锚点位置, 水平方向是否向右, 垂直方向是否向下)
        data class Mark(val anchor: Offset, val horizRight: Boolean, val vertDown: Boolean)
        val marks = mapOf(
            Corner.TL to Mark(Offset(i, i), horizRight = true,  vertDown = true),
            Corner.TR to Mark(Offset(w - i, i), horizRight = false, vertDown = true),
            Corner.BL to Mark(Offset(i, h - i), horizRight = true,  vertDown = false),
            Corner.BR to Mark(Offset(w - i, h - i), horizRight = false, vertDown = false),
        )

        marks.forEach { (corner, mark) ->
            if (corner in hideCorners) return@forEach
            val xStart = if (mark.horizRight) mark.anchor.x else mark.anchor.x - l
            val xEnd   = if (mark.horizRight) mark.anchor.x + l else mark.anchor.x
            val yStart = if (mark.vertDown)   mark.anchor.y else mark.anchor.y - l
            val yEnd   = if (mark.vertDown)   mark.anchor.y + l else mark.anchor.y
            // 横线
            drawRect(
                color = color,
                topLeft = Offset(xStart, mark.anchor.y - t / 2),
                size = Size(xEnd - xStart, t),
            )
            // 竖线
            drawRect(
                color = color,
                topLeft = Offset(mark.anchor.x - t / 2, yStart),
                size = Size(t, yEnd - yStart),
            )
        }
    }
}
