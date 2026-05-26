package com.hemanager.mobile.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * HE OP 切角形状（鹰角签名）。
 *
 * 默认切**右上 + 左下**两个斜角；可通过 [tl] / [tr] / [bl] / [br] 任意组合。
 * 用于所有 panel、chip、按钮、输入框、头像。
 *
 * @param cut 切角大小（dp），默认 14
 */
class CutCornerShape(
    private val cut: Dp,
    private val tr: Boolean = true,
    private val tl: Boolean = false,
    private val br: Boolean = false,
    private val bl: Boolean = true,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val c = with(density) { cut.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val path = Path().apply {
            if (tl) {
                moveTo(0f, c)
                lineTo(c, 0f)
            } else {
                moveTo(0f, 0f)
            }
            if (tr) {
                lineTo(size.width - c, 0f)
                lineTo(size.width, c)
            } else {
                lineTo(size.width, 0f)
            }
            if (br) {
                lineTo(size.width, size.height - c)
                lineTo(size.width - c, size.height)
            } else {
                lineTo(size.width, size.height)
            }
            if (bl) {
                lineTo(c, size.height)
                lineTo(0f, size.height - c)
            } else {
                lineTo(0f, size.height)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * HE OP 常用切角档位的便利工厂。
 *
 * 角度逻辑：
 *   - [standard] 主卡片 / panel — 切 TR + BL，14dp
 *   - [small]    chip / 按钮 / 输入框 — 切 TR + BL，7dp
 *   - [sheet]    底部 sheet — 仅切顶部 TL + TR，18dp
 */
object HeCut {
    fun standard(cut: Dp = 14.dp): CutCornerShape = CutCornerShape(cut)
    fun small(cut: Dp = 7.dp): CutCornerShape = CutCornerShape(cut)
    fun chip(cut: Dp = 7.dp): CutCornerShape = CutCornerShape(cut)
    fun sheet(cut: Dp = 18.dp): CutCornerShape =
        CutCornerShape(cut, tl = true, tr = true, bl = false, br = false)
}

/** 角的语义枚举（用在 AngularPanel 的 corners 参数里）。 */
enum class Corner { TL, TR, BR, BL }
