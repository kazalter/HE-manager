package com.hemanager.mobile.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader

/**
 * CompositionLocal：跨 Composable 注入封面图 ImageLoader。
 *
 * MainActivity 持有唯一一份 `coverImageLoader`（带磁盘 + 内存缓存配置），
 * 在 `HeManagerApp` 顶层通过 [CompositionLocalProvider] 注入。
 *
 * 任意子 Composable 通过 `LocalCoverImageLoader.current` 拿到同一份实例，
 * 避免到处传 `imageLoader: ImageLoader` 参数。
 *
 * `staticCompositionLocalOf` 表示这个值在整个组合中不会变 —— 读取时不会触发
 * 不必要的 recomposition。
 */
val LocalCoverImageLoader: ProvidableCompositionLocal<ImageLoader> =
    staticCompositionLocalOf {
        error(
            "LocalCoverImageLoader not provided. " +
                "Wrap your composables with CompositionLocalProvider(LocalCoverImageLoader provides imageLoader) { ... }."
        )
    }
