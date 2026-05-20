package com.hemanager.mobile.feature.library

// Library 模块复用的纯函数 / 半纯函数集合：颜色映射、文本格式化、URL 拼装等。
// 这些函数大多没有 state，可以独立测试。
//
// 命名约定：
//   - 不带 V2 后缀的（filterAccent/typeAccent/progressColor 等）：通用版本
//   - 带 V2 后缀的（statusAccentV2/progressColorV2 等）：V2 重写时新增的语义版本
// 未来可考虑合并语义同步去掉 V2 后缀。

import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.MangaActivity
import com.hemanager.mobile.MediaItem
import com.hemanager.mobile.TagItem
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.load
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hemanager.mobile.data.HePrefs
import com.hemanager.mobile.data.image.coverCacheKey
import com.hemanager.mobile.data.image.coverDecodeBucketPx
import com.hemanager.mobile.data.image.coverImageRequest
import com.hemanager.mobile.data.image.imageGalleryDecodeBucketPx
import com.hemanager.mobile.data.image.imageGalleryPlaceholderColor
import com.hemanager.mobile.data.image.isCoverInMemory
import com.hemanager.mobile.feature.login.LoginScreen
import com.hemanager.mobile.ui.components.BrandMark
import com.hemanager.mobile.ui.components.GlassPanel
import com.hemanager.mobile.ui.components.LocalCoverImageLoader
import com.hemanager.mobile.ui.components.ModernTextField
import com.hemanager.mobile.ui.theme.AppBackgroundBrush
import com.hemanager.mobile.ui.theme.HeColorScheme
import com.hemanager.mobile.ui.util.toastComingSoon
import com.hemanager.mobile.ui.util.toastError
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
internal fun statusAccentV2(value: String): Color {
    return when (value) {
        "viewing" -> MaterialTheme.colorScheme.primary
        "favorite" -> Color(0xFFF6C46B)
        "viewed" -> Color(0xFF58E6C2)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun progressColorV2(item: MediaItem): Color {
    return when {
        item.missing -> Color(0xFFFF8CA3)
        item.viewStatus == "viewed" -> Color(0xFF58E6C2)
        item.viewStatus == "viewing" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun matchesStatusV2(item: MediaItem, value: String): Boolean {
    return when (value) {
        "viewing" -> item.viewStatus == "viewing"
        "favorite" -> item.favorite
        "viewed" -> item.viewStatus == "viewed"
        else -> true
    }
}

internal fun countForStatusV2(items: List<MediaItem>, value: String): Int {
    return items.count { matchesStatusV2(it, value) }
}

internal fun serverHostV2(serverUrl: String): String {
    return ApiClient.trimSlash(serverUrl)
        .removePrefix("http://")
        .removePrefix("https://")
}

internal fun mediaTypeLabelV2(type: String?): String {
    return when (type) {
        "video" -> "视频"
        "manga" -> "漫画"
        "image" -> "图片"
        else -> "媒体"
    }
}

internal fun mediaMetaChipsV2(item: MediaItem): List<String> {
    return listOf(
        cleanExtensionV2(item.extension),
        item.duration.takeIf { it > 0 }?.let { formatDuration(it) } ?: "",
        item.pageCount.takeIf { it > 0 }?.let { "${it} 页" } ?: "",
        item.rating.takeIf { it > 0 }?.let { "${it} 星" } ?: ""
    ).filter { it.isNotBlank() }
}

internal fun cleanExtensionV2(extension: String?): String {
    val raw = extension?.takeIf { it.isNotBlank() && it != "null" } ?: return ""
    val normalized = raw.lowercase(Locale.ROOT).removePrefix(".")
    if (normalized == "dir") return ""
    return raw.uppercase(Locale.ROOT)
}

internal fun metaInlineV2(item: MediaItem): String {
    val parts = mutableListOf<String>()
    parts += mediaTypeLabelV2(item.mediaType)
    cleanExtensionV2(item.extension).takeIf { it.isNotBlank() }?.let { parts += it }
    if (item.duration > 0) parts += formatDuration(item.duration)
    if (item.pageCount > 0) parts += "${item.pageCount}P"
    parts += progressTextV2(item)
    return parts.joinToString(" · ")
}

internal fun progressTextV2(item: MediaItem): String {
    if (item.viewStatus == "viewed") return "已看完"
    if (item.viewStatus == "viewing") {
        if (item.mediaType == "manga" && item.pageCount > 0) {
            return "第 ${minOf(item.progress + 1, item.pageCount)} / ${item.pageCount} 页"
        }
        if (item.mediaType == "video" && item.progress > 0) {
            return "看到 ${formatDuration(item.progress)}"
        }
        return "继续看"
    }
    return "未观看"
}

internal fun openItem(
    context: android.content.Context,
    item: MediaItem,
    serverUrl: String,
    token: String,
    restart: Boolean = false,
    playlist: List<MediaItem> = emptyList(),
) {
    val isVideo = item.mediaType == "video"
    val isAudio = item.mediaType == "audio"
    // 音频走独立的 ASMR 播放器（Media3 service-based 后台播放）；视频走 PlayerActivity；
    // 其他（漫画/图片）走 MangaActivity（图片借用漫画查看器的翻页能力）。
    val target = when {
        isVideo -> com.hemanager.mobile.player.PlayerActivity::class.java
        isAudio -> com.hemanager.mobile.audio.AudioPlayerActivity::class.java
        else -> MangaActivity::class.java
    }
    context.startActivity(Intent(context, target).apply {
        putExtra("server_url", serverUrl)
        putExtra("token", token)
        putExtra("id", item.id)
        putExtra("title", item.title)
        putExtra("media_type", item.mediaType)
        putExtra("progress", item.progress)
        putExtra("duration", item.duration)
        putExtra("page_count", item.pageCount)
        if (restart) putExtra("restart", true)
        if ((isVideo || item.mediaType == "image") && playlist.isNotEmpty()) {
            val ids = playlist.filter { it.mediaType == item.mediaType }.map { it.id }.toIntArray()
            if (ids.isNotEmpty()) putExtra("playlist_ids", ids)
        }
    })
}

internal fun coverUrl(serverUrl: String, token: String, item: MediaItem): String? {
    val cover = item.coverPath
    if (cover.isNullOrBlank() || cover == "null") return null
    // URL-encode the path so that thumbnails with non-ASCII characters (e.g. Japanese
    // dirname containing parens, brackets, CJK) reach the server intact.
    val encoded = android.net.Uri.encode(cover, "")
    return "$serverUrl/mobile/thumbnails/$encoded?${ApiClient.tokenQuery(token)}"
}

/** 创作者头像 URL（封面路径走 mobile/thumbnails，独立于 MediaItem.coverPath）。 */
internal fun creatorThumbUrl(serverUrl: String, token: String, coverPath: String?): String? {
    if (coverPath.isNullOrBlank() || coverPath == "null") return null
    val encoded = android.net.Uri.encode(coverPath, "")
    return "$serverUrl/mobile/thumbnails/$encoded?${ApiClient.tokenQuery(token)}"
}


internal fun logImageGalleryPerf(message: String) {
    Log.d(imageGalleryPerfLogTag, message)
}

internal fun greetingText(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..23 -> "晚上好"
        else -> "夜深了"
    }
}

internal fun countForFilter(items: List<MediaItem>, value: String): Int {
    return if (value.isBlank()) items.size else items.count { it.mediaType == value }
}

internal fun summary(items: List<MediaItem>): String {
    val videos = items.count { it.mediaType == "video" }
    val manga = items.count { it.mediaType == "manga" }
    val images = items.count { it.mediaType == "image" }
    return listOf(
        "${items.size}个条目",
        if (videos > 0) "${videos}视频" else "",
        if (manga > 0) "${manga}漫画" else "",
        if (images > 0) "${images}图片" else ""
    ).filter { it.isNotBlank() }.joinToString(" / ")
}

internal fun meta(item: MediaItem): String {
    return listOf(
        typeLabel(item.mediaType),
        item.extension?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "",
        item.duration.takeIf { it > 0 }?.let { formatDuration(it) } ?: "",
        item.pageCount.takeIf { it > 0 }?.let { "${it}页" } ?: "",
        if (item.favorite) "收藏" else "",
        item.rating.takeIf { it > 0 }?.let { "${it}星" } ?: ""
    ).filter { it.isNotBlank() }.joinToString(" / ")
}

internal fun progressText(item: MediaItem): String {
    if (item.viewStatus == "viewed") return "已看完"
    if (item.viewStatus == "viewing") {
        if (item.mediaType == "manga" && item.pageCount > 0) {
            return "第 ${minOf(item.progress + 1, item.pageCount)} / ${item.pageCount} 页"
        }
        if (item.mediaType == "video" && item.progress > 0) {
            return "看到 ${formatDuration(item.progress)}"
        }
        return "正在看"
    }
    return "未观看"
}

internal fun progressFraction(item: MediaItem): Float? {
    if (item.viewStatus == "viewed") return 1f
    if (item.viewStatus != "viewing") return null
    val fraction = when {
        item.mediaType == "manga" && item.pageCount > 0 -> (item.progress + 1).toFloat() / item.pageCount.toFloat()
        item.mediaType == "video" && item.duration > 0 -> item.progress.toFloat() / item.duration.toFloat()
        else -> return null
    }
    return fraction.coerceIn(0.02f, 1f)
}

@Composable
internal fun filterAccent(value: String): Color {
    return when (value) {
        "video" -> MaterialTheme.colorScheme.secondary
        "manga" -> MaterialTheme.colorScheme.tertiary
        "image" -> Color(0xFFFF8FA3)
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
internal fun typeAccent(type: String?): Color {
    return when (type) {
        "video" -> MaterialTheme.colorScheme.secondary
        "manga" -> MaterialTheme.colorScheme.tertiary
        "image" -> Color(0xFFFF8FA3)
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
internal fun progressColor(item: MediaItem): Color {
    return when {
        item.missing -> Color(0xFFFF8FA3)
        item.viewStatus == "viewed" -> MaterialTheme.colorScheme.secondary
        item.viewStatus == "viewing" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

internal fun readableError(error: Throwable): String {
    val message = error.message ?: return "读取失败"
    return when {
        message.contains("Failed to connect", ignoreCase = true) -> "无法连接服务器，请确认电脑端服务已启动"
        message.contains("timeout", ignoreCase = true) -> "连接超时，请检查网络或服务器地址"
        else -> message
    }
}

internal fun typeLabel(type: String?): String {
    return when (type) {
        "video" -> "视频"
        "manga" -> "漫画"
        "image" -> "图片"
        else -> "媒体"
    }
}

internal fun formatDuration(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}

// ---------------------------------------------------------------------------
// 图廊瓦片大小 / 列数互换工具
// ---------------------------------------------------------------------------
// Gallery 模块的网格在 pinch-to-zoom 期间需要在「瓦片 dp 大小」和「列数」之间
// 来回换算（列数才是用户偏好的稳定单位，但绘制阶段需要瓦片大小驱动 ItemDecoration
// 和 measure 阶段）。这些函数是纯数学，没有 Compose 或 Android 依赖，方便测试。
//
// 参数定义：
//   availableWidthDp：RecyclerView 可用宽度（已扣除 padding）的 dp 值
//   gapDp：相邻瓦片之间的间距
//   columns：列数；调用方负责把它 clamp 到 [imageGalleryMinColumns, imageGalleryMaxColumns]

/** 给定列数反推每个瓦片的边长 dp。 */
internal fun imageGalleryTileForColumns(availableWidthDp: Float, gapDp: Float, columns: Int): Float {
    return ((availableWidthDp - gapDp * (columns - 1)) / columns).coerceAtLeast(1f)
}

/**
 * 给定一个自由瓦片 dp，吸附到「最近的列数对应的瓦片 dp」。
 * pinch 松手后用来 snap 到稳定状态。
 */
internal fun nearestImageGalleryPreset(sizeDp: Float, availableWidthDp: Float, gapDp: Float): Float {
    return (imageGalleryMinColumns..imageGalleryMaxColumns)
        .map { columns -> imageGalleryTileForColumns(availableWidthDp, gapDp, columns) }
        .minByOrNull { abs(it - sizeDp) }
        ?: imageGalleryTileForColumns(availableWidthDp, gapDp, 5)
}

/**
 * 给定瓦片 dp 推断目标列数。可选 hysteresis（迟滞）防止用户在阈值附近抖动时列数反复跳。
 *
 * 迟滞规则：仅当瓦片大小越过「两个相邻列数的瓦片中点 ± switchMargin」才切换，避免边界抖。
 *
 * @param withHysteresis 默认按"最近列数"硬切；为 true 时启用迟滞，需要传 [currentColumns]。
 */
internal fun imageGalleryColumnsForTile(
    sizeDp: Float,
    availableWidthDp: Float,
    gapDp: Float,
    currentColumns: Int,
    withHysteresis: Boolean
): Int {
    val rawColumns = floor((availableWidthDp + gapDp) / (sizeDp + gapDp))
        .toInt()
        .coerceIn(imageGalleryMinColumns, imageGalleryMaxColumns)
    if (!withHysteresis) return rawColumns

    val current = currentColumns.coerceIn(imageGalleryMinColumns, imageGalleryMaxColumns)
    if (rawColumns == current) return current

    val currentTile = imageGalleryTileForColumns(availableWidthDp, gapDp, current)
    val targetTile = imageGalleryTileForColumns(availableWidthDp, gapDp, rawColumns)
    val midpoint = (currentTile + targetTile) / 2f
    val switchMarginDp = 3.5f
    return if (rawColumns < current) {
        if (sizeDp >= midpoint + switchMarginDp) rawColumns else current
    } else {
        if (sizeDp <= midpoint - switchMarginDp) rawColumns else current
    }
}
