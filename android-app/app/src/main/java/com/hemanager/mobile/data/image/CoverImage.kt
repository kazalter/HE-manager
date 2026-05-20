package com.hemanager.mobile.data.image

import android.content.Context
import androidx.compose.ui.graphics.Color
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale

// ---------------------------------------------------------------------------
// 这个文件集中所有「为媒体封面图构造 Coil 请求/缓存 key」的逻辑。
// 之前散落在 MainActivity 内部，每个 helper 都依赖 MainActivity 字段，
// 难以单独测试也阻碍把 LibraryScreenV2 抽到独立文件。
//
// 全部改为 top-level 纯函数 / 扩展函数后：
//   - 任何 Composable / 协程作用域都能直接调用
//   - 不依赖 MainActivity，未来 ASMR / Player 可复用
//   - 测试时只需传 Context / ImageLoader，无需启动 Activity
// ---------------------------------------------------------------------------

/**
 * 把任意像素值对齐到一组预定义的 bucket，避免相邻尺寸各自请求和缓存独立图片。
 *
 * 例如 145px → 160 bucket，183px → 240 bucket。
 * 配合 Coil memoryCacheKey/diskCacheKey 使用，大幅提升缓存命中率。
 */
fun coverDecodeBucketPx(value: Int): Int {
    return when {
        value <= 96 -> 96
        value <= 128 -> 128
        value <= 160 -> 160
        value <= 180 -> 180
        value <= 240 -> 240
        value <= 320 -> 320
        else -> 420
    }
}

/**
 * 图片网格瓦片的请求像素决策：列数越多每张就越小，省内存省网络。
 *
 * 调用前会再走一次 [coverDecodeBucketPx] 对齐。
 */
fun imageGalleryDecodeBucketPx(tilePx: Int, columns: Int): Int {
    val targetPx = when {
        columns >= 7 -> tilePx.coerceAtMost(96)
        columns == 6 -> tilePx.coerceAtMost(128)
        columns == 5 -> tilePx.coerceAtMost(160)
        columns == 4 -> tilePx.coerceAtMost(180)
        else -> tilePx.coerceAtMost(320)
    }
    return coverDecodeBucketPx(targetPx)
}

/**
 * 由 seed（通常是 url 或标题）派生一个稳定的深色占位背景。
 *
 * 同样的 seed 永远返回同样的颜色；图片库瓦片在 Coil 还没解码完成前显示这个色块，
 * 避免大片黑色或纯灰。
 */
fun imageGalleryPlaceholderColor(seed: String): Color {
    val hash = seed.hashCode()
    val r = 28 + (hash and 0x17)
    val g = 36 + ((hash ushr 5) and 0x1F)
    val b = 48 + ((hash ushr 11) and 0x27)
    return Color(r, g, b)
}

/**
 * 标准化封面图缓存 key：去掉 query string（避免 token 失效让缓存失效），
 * 加上尺寸 bucket。
 *
 * Coil 的 memoryCache / diskCache 都用这个 key。
 */
fun coverCacheKey(url: String, reqWidth: Int, reqHeight: Int): String {
    val stableUrl = url.substringBefore("?")
    return "$stableUrl#${coverDecodeBucketPx(reqWidth)}x${coverDecodeBucketPx(reqHeight)}"
}

/**
 * 不发起网络请求，仅检查内存缓存中是否已有该封面。
 *
 * 用于预取调度时跳过已经在内存中的项。
 */
fun ImageLoader.isCoverInMemory(url: String, reqWidth: Int, reqHeight: Int): Boolean {
    val key = MemoryCache.Key(coverCacheKey(url, reqWidth, reqHeight))
    return memoryCache?.get(key) != null
}

/**
 * 构造一个标准化的封面 ImageRequest。
 *
 * @param context 需要 Context 来 build ImageRequest（Coil 要求）
 * @param networkAllowed 在图片库模式下网络可被前后台/可见性策略关掉，传 false 时仅命中本地缓存
 * @param crossfadeMillis 0 表示不淡入（预取等场景），> 0 启用过渡
 */
fun coverImageRequest(
    context: Context,
    url: String,
    reqWidth: Int,
    reqHeight: Int,
    crossfadeMillis: Int = 80,
    networkAllowed: Boolean = true
): ImageRequest {
    val width = coverDecodeBucketPx(reqWidth)
    val height = coverDecodeBucketPx(reqHeight)
    val cacheKey = coverCacheKey(url, width, height)
    val builder = ImageRequest.Builder(context)
        .data(url)
        .size(width, height)
        .scale(Scale.FILL)
        .precision(Precision.INEXACT)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(if (networkAllowed) CachePolicy.ENABLED else CachePolicy.DISABLED)
    if (crossfadeMillis > 0) {
        builder.crossfade(crossfadeMillis)
    }
    return builder.build()
}
