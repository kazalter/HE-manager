package com.hemanager.mobile.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 全局间距 / 尺寸常量。
 *
 * **为什么用 object 不用 const**：`Dp` 不是 primitive，编译期常量只能是 primitive / String。
 *
 * **使用建议**：
 *   - **新代码请用这些常量**，例如 `Modifier.padding(HeSpacing.md)` 而不是 `padding(14.dp)`
 *   - 已存在的 dp 字面量不强制批量替换；遇到调整一处时再迁移即可
 *   - 想全局把 padding 收紧/放宽时只改这一个文件
 *
 * 命名约定参考 Material 3 spec：xs/sm/md/lg/xl/xxl 是倍数递增的视觉密度阶梯。
 */
object HeSpacing {
    /** 4.dp — 极小间距（图标内边距、紧凑分隔） */
    val xs = 4.dp

    /** 8.dp — 小间距（按钮内 padding、行内 spacer） */
    val sm = 8.dp

    /** 12.dp — 中小间距（卡片内组件之间） */
    val md = 12.dp

    /** 16.dp — 标准间距（屏幕水平 padding、列表项 vertical padding） */
    val lg = 16.dp

    /** 22.dp — 大间距（屏幕级 padding，section 之间） */
    val xl = 22.dp

    /** 32.dp — 超大间距（屏幕级垂直留白） */
    val xxl = 32.dp
}

/**
 * 常用圆角半径，与 [HeSpacing] 配套。
 *
 * 大多数容器使用 [HeRadius.card]（8.dp）；圆形头像使用 [HeRadius.pill]（50%）。
 */
object HeRadius {
    /** 4.dp — 紧凑控件（chip、小标签） */
    val chip = 4.dp

    /** 8.dp — 标准卡片、按钮、面板 */
    val card = 8.dp

    /** 16.dp — 大卡片、底部 sheet */
    val sheet = 16.dp
}
