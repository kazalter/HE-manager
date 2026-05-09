package com.hemanager.mobile.player.model

import androidx.media3.ui.AspectRatioFrameLayout

enum class AspectMode(val label: String, val resizeMode: Int) {
    FIT("适应屏幕", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("填充屏幕", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ORIGINAL("原始比例", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
    STRETCH("拉伸铺满", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    CROP("裁剪填充", AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

    companion object {
        fun fromName(name: String?): AspectMode =
            values().firstOrNull { it.name == name } ?: FIT
    }
}
