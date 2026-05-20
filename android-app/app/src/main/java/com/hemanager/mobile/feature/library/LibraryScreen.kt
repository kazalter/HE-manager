package com.hemanager.mobile.feature.library

// =============================================================================
// HE Manager 媒体库主屏 + 全部 V2 子组件 + 私有助手函数
//
// 历史背景：这些代码原先塞在 MainActivity 里造成 6000 行怪兽文件。
// 本次重构整体平移到此文件以脱离 MainActivity 上下文，
// 后续可继续拆到 feature/library/{drawer,filter,card,gallery}/ 子文件。
//
// 入口 fun LibraryScreenV2 是 HeManagerApp 已登录后唯一调用的 composable。
// 依赖：LocalCoverImageLoader / LocalContext / HePrefs / data.image.* / ApiClient
// 所有 fun 标 internal — 同模块可用但模块外不可见。
// =============================================================================

// 根包的活代码（Java POJO/Activity），需在子包显式 import
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.TransformOrigin
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

data class FilterOption(val label: String, val value: String)
data class GalleryPinchAnchor(val itemIndex: Int, val topOffsetPx: Int)
enum class LibraryViewMode { Large, Grid, Detail }
internal val imageGalleryMinTileDp = 46f
internal val imageGalleryMaxTileDp = 150f
internal val imageGalleryMinColumns = 3
internal val imageGalleryMaxColumns = 7
internal val imageGalleryPerfLogTag = "ImageGalleryPerf"
@Composable
internal fun CoilCoverImage(
    url: String?,
    label: String,
    decodeWidthPx: Int,
    decodeHeightPx: Int,
    crossfadeMillis: Int = 80,
    networkAllowed: Boolean = true,
    networkAllowedProvider: (() -> Boolean)? = null,
    requestRestartKey: Int = 0,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (url.isNullOrBlank()) return
    val context = LocalContext.current
    val loader = LocalCoverImageLoader.current
    val width = remember(decodeWidthPx) { coverDecodeBucketPx(decodeWidthPx) }
    val height = remember(decodeHeightPx) { coverDecodeBucketPx(decodeHeightPx) }
    val request = remember(url, width, height, crossfadeMillis, networkAllowed, requestRestartKey) {
        coverImageRequest(
            context,
            url,
            width,
            height,
            crossfadeMillis,
            networkAllowedProvider?.invoke() ?: networkAllowed
        )
    }

    AsyncImage(
        model = request,
        contentDescription = label,
        imageLoader = loader,
        contentScale = contentScale,
        modifier = modifier
    )
}

@Composable
internal fun LibraryScreenV2(
    serverUrl: String,
    token: String,
    onOpenCreators: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val coverImageLoader = LocalCoverImageLoader.current
    val scope = rememberCoroutineScope()
    var mediaType by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var allItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var requestId by remember { mutableStateOf(0) }
    val mediaFilters = remember {
        listOf(
            FilterOption("全部", ""),
            FilterOption("视频", "video"),
            FilterOption("漫画", "manga"),
            FilterOption("图片", "image")
        )
    }
    val statusFilters = remember {
        listOf(
            FilterOption("全部", ""),
            FilterOption("继续看", "viewing"),
            FilterOption("收藏", "favorite"),
            FilterOption("已看完", "viewed")
        )
    }
    var viewMode by remember { mutableStateOf(LibraryViewMode.Large) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var filterSheetOpen by remember { mutableStateOf(false) }

    // 接 MainActivity 的左边缘滑动手势：注册回调让 dispatchTouchEvent 探测到边缘手势时
    // 能打开本屏幕的 drawer。LibraryScreen 卸载时（导航到 CreatorsScreen 等）清掉，
    // 避免边缘手势在不该有抽屉的页面尝试调用过期的 drawerState。
    val mainActivity = context as? com.hemanager.mobile.MainActivity
    DisposableEffect(mainActivity, drawerState, scope) {
        mainActivity?.edgeDrawerOpenRequester = {
            scope.launch { drawerState.open() }
        }
        mainActivity?.edgeDrawerGestureEnabled = true
        onDispose {
            mainActivity?.edgeDrawerGestureEnabled = false
            mainActivity?.edgeDrawerOpenRequester = null
        }
    }
    var tagSheetItem by remember { mutableStateOf<MediaItem?>(null) }
    // Pending optimistic-deletes — keyed by media id so concurrent deletes don't collide.
    val pendingDeletes = remember { mutableStateMapOf<Int, Job>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val visibleItems by remember {
        derivedStateOf {
            allItems.filter { item ->
                item.id !in pendingDeletes &&
                    (mediaType.isBlank() || item.mediaType == mediaType) &&
                    matchesStatusV2(item, statusFilter)
            }
        }
    }
    val visibleGridRows by remember {
        derivedStateOf { visibleItems.chunked(3) }
    }
    val listState = rememberLazyListState()
    val imageGridState = rememberLazyGridState()
    val density = LocalDensity.current
    var coverPrefetchJob by remember { mutableStateOf<Job?>(null) }
    val coverPrefetchDisposables = remember { mutableListOf<coil.request.Disposable>() }
    var visibleCoverWarmupJob by remember { mutableStateOf<Job?>(null) }
    val visibleCoverWarmupDisposables = remember { mutableListOf<coil.request.Disposable>() }
    var imageGalleryRecyclerView by remember { mutableStateOf<RecyclerView?>(null) }
    var imageGalleryBackTopVisible by remember { mutableStateOf(false) }
    var imageGalleryCurrentColumns by remember { mutableStateOf(5) }
    val imageGalleryNetworkAllowed = remember { AtomicBoolean(true) }
    val imageGalleryNetworkAllowedProvider = remember(imageGalleryNetworkAllowed) {
        { imageGalleryNetworkAllowed.get() }
    }
    val imageGalleryLastPrefetchStart = remember { AtomicInteger(0) }
    var imageGalleryLoadGeneration by remember { mutableIntStateOf(0) }
    val showBackToTopButton by remember {
        derivedStateOf {
            if (mediaType == "image") {
                imageGalleryBackTopVisible
            } else {
                listState.firstVisibleItemIndex > 1 || listState.firstVisibleItemScrollOffset > 360
            }
        }
    }
    val galleryPrefs = remember { HePrefs(context) }
    // 列数是 pinch 的主单位（用户偏好稳定值就是列数，不是瓦片像素）。pinch 期间
    // 只动 pinchVisualScale，不动 spanCount；松手时一次性 swap 列数 + snap scale 回 1。
    val initialImageGalleryColumns = remember {
        galleryPrefs.galleryColumns.coerceIn(imageGalleryMinColumns, imageGalleryMaxColumns)
    }
    var imageGalleryStableColumns by remember { mutableIntStateOf(initialImageGalleryColumns) }
    var imageGalleryPinchStartColumns by remember { mutableIntStateOf(initialImageGalleryColumns) }
    var imageGalleryColumnsMigrated by remember {
        mutableStateOf(galleryPrefs.hasGalleryColumns)
    }
    var imageGalleryPinching by remember { mutableStateOf(false) }
    // pinch 松手后 visual-scale animateTo→snap 阶段为 true，期间禁止 prefetch/warmup
    // 这样新瓦片 decode 不会跟旧帧打架。settle 完成后回 false。
    var imageGallerySettling by remember { mutableStateOf(false) }
    var imageGalleryInteractionsEnabled by remember { mutableStateOf(true) }
    var imageGalleryAnchor by remember { mutableStateOf<GalleryPinchAnchor?>(null) }
    var imageGalleryAnchorJob by remember { mutableStateOf<Job?>(null) }
    // 当前实际渲染的瓦片 dp（由列数 + 容器宽度推算）。由 gallery 渲染块在 BoxWithConstraints
    // 内 LaunchedEffect 更新；下游 prefetch/warmup/decode-size 逻辑读它来选合适的图大小。
    var renderedImageTileDp by remember { mutableFloatStateOf(78f) }
    // Visual-scale pinch 模型：pinch 期间只通过 graphicsLayer 把 RecyclerView 容器
    // scaleX/Y，spanCount 不变；松手 animateTo(handoffScale)，handoff 时刻 (oldTile×scale)
    // == newTile×1，所以同帧 swap 列数 + snap scale=1 视觉上无缝。
    val pinchVisualScale = remember { Animatable(1f) }
    var pinchFocalPx by remember { mutableStateOf(Offset(0f, 0f)) }
    var pinchRecyclerSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val swipeController = remember { SwipeRevealController() }

    fun imageGalleryTileForColumns(availableWidthDp: Float, gapDp: Float, columns: Int): Float {
        return ((availableWidthDp - gapDp * (columns - 1)) / columns).coerceAtLeast(1f)
    }

    fun nearestImageGalleryPreset(sizeDp: Float, availableWidthDp: Float, gapDp: Float): Float {
        return (imageGalleryMinColumns..imageGalleryMaxColumns)
            .map { columns -> imageGalleryTileForColumns(availableWidthDp, gapDp, columns) }
            .minByOrNull { abs(it - sizeDp) }
            ?: imageGalleryTileForColumns(availableWidthDp, gapDp, 5)
    }

    fun captureImageGalleryAnchor(focalPoint: Offset): GalleryPinchAnchor? {
        // 用真实的图廊 RecyclerView 而不是 LazyGridState（后者在当前架构下不挂在
        // 图廊路径上，只是历史遗留 state）。挑离 focal 最近的可见瓦片作锚点。
        val recyclerView = imageGalleryRecyclerView ?: return null
        val nativeHeaderCount = 4  // Gallery adapter 默认 inlineHeaders=true 时 4 个 header
        val closest = (0 until recyclerView.childCount)
            .mapNotNull { idx ->
                val child = recyclerView.getChildAt(idx) ?: return@mapNotNull null
                val adapterPosition = recyclerView.getChildAdapterPosition(child)
                // inlineHeaders=false 时实际 header 0 个，>= 0 的位置都是瓦片；
                // inlineHeaders=true 时跳过前 nativeHeaderCount。这里用宽松条件 >= 0。
                if (adapterPosition < 0) return@mapNotNull null
                val centerX = child.left + child.width / 2f
                val centerY = child.top + child.height / 2f
                Triple(child, adapterPosition, abs(centerX - focalPoint.x) + abs(centerY - focalPoint.y))
            }
            .minByOrNull { it.third }
            ?: return null
        return GalleryPinchAnchor(
            itemIndex = closest.second,
            topOffsetPx = closest.first.top
        )
    }

    fun scheduleImageGalleryAnchorCorrection() {
        val anchor = imageGalleryAnchor ?: return
        val recyclerView = imageGalleryRecyclerView ?: return
        imageGalleryAnchorJob?.cancel()
        imageGalleryAnchorJob = scope.launch {
            withFrameNanos { }
            (recyclerView.layoutManager as? GridLayoutManager)
                ?.scrollToPositionWithOffset(anchor.itemIndex, anchor.topOffsetPx)
        }
    }

    fun coverPrefetchStartIndex(): Int {
        val visibleIds = if (mediaType == "image") {
            imageGridState.layoutInfo.visibleItemsInfo.mapNotNull {
                (it.key as? String)?.removePrefix("image-gallery-")?.toIntOrNull()
            }
        } else {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val key = info.key as? String
                when {
                    key?.startsWith("large-") == true -> key.removePrefix("large-").toIntOrNull()
                    key?.startsWith("detail-") == true -> key.removePrefix("detail-").toIntOrNull()
                    key?.startsWith("grid-") == true -> key.removePrefix("grid-").toIntOrNull()
                    else -> null
                }
            }
        }
        val firstVisibleId = visibleIds.firstOrNull() ?: return 0
        return visibleItems.indexOfFirst { it.id == firstVisibleId }.coerceAtLeast(0)
    }

    fun imageGalleryVisibleMediaIndices(): List<Int> {
        if (mediaType != "image") return emptyList()
        return imageGridState.layoutInfo.visibleItemsInfo.mapNotNull {
            val id = (it.key as? String)
                ?.removePrefix("image-gallery-")
                ?.toIntOrNull()
                ?: return@mapNotNull null
            visibleItems.indexOfFirst { media -> media.id == id }.takeIf { index -> index >= 0 }
        }
    }

    fun imageGalleryWarmupMediaIndices(): List<Int> {
        val visibleIndices = imageGalleryVisibleMediaIndices()
        if (visibleIndices.isNotEmpty()) return visibleIndices
        val startIndex = coverPrefetchStartIndex()
        val fallbackCount = imageGalleryCurrentColumns.coerceIn(3, 7) * 4
        return (startIndex until (startIndex + fallbackCount))
            .filter { it in visibleItems.indices }
    }

    fun coverPrefetchSize(): Pair<Int, Int> {
        return if (mediaType == "image") {
            val tilePx = with(density) { renderedImageTileDp.dp.toPx().roundToInt() }
            val px = imageGalleryDecodeBucketPx(tilePx, imageGalleryCurrentColumns)
            px to px
        } else {
            when (viewMode) {
                LibraryViewMode.Large -> 420 to 280
                LibraryViewMode.Grid -> 220 to 320
                LibraryViewMode.Detail -> 156 to 216
            }
        }
    }

    fun cancelCoverPrefetches() {
        coverPrefetchJob?.cancel()
        synchronized(coverPrefetchDisposables) {
            coverPrefetchDisposables.forEach { it.dispose() }
            coverPrefetchDisposables.clear()
        }
    }

    fun cancelVisibleCoverWarmup() {
        visibleCoverWarmupJob?.cancel()
        synchronized(visibleCoverWarmupDisposables) {
            visibleCoverWarmupDisposables.forEach { it.dispose() }
            visibleCoverWarmupDisposables.clear()
        }
    }

    fun scheduleVisibleCoverWarmup(deferMs: Long = 0L) {
        if (mediaType != "image") return
        cancelVisibleCoverWarmup()
        val visibleIndices = imageGalleryWarmupMediaIndices()
        if (visibleIndices.isEmpty()) return
        val (reqWidth, reqHeight) = coverPrefetchSize()
        val itemsToLoad = visibleIndices
            .distinct()
            .mapNotNull { visibleItems.getOrNull(it) }
            .filter { item ->
                val url = coverUrl(serverUrl, token, item)
                url != null && !coverImageLoader.isCoverInMemory(url, reqWidth, reqHeight)
            }
        if (itemsToLoad.isEmpty()) return
        logImageGalleryPerf(
            "visible warmup scheduled count=${itemsToLoad.size} size=${reqWidth}x$reqHeight defer=$deferMs visible=${imageGalleryVisibleMediaIndices().isNotEmpty()}"
        )
        visibleCoverWarmupJob = scope.launch(Dispatchers.IO) warmup@{
            if (deferMs > 0L) delay(deferMs)
            if (!imageGalleryNetworkAllowed.get()) return@warmup
            itemsToLoad.chunked(imageGalleryCurrentColumns.coerceIn(3, 7)).forEach { chunk ->
                if (!imageGalleryNetworkAllowed.get()) return@warmup
                coroutineScope {
                    chunk.forEach { item ->
                        launch {
                            val url = coverUrl(serverUrl, token, item) ?: return@launch
                            val disposable = coverImageLoader.enqueue(
                                coverImageRequest(context, url, reqWidth, reqHeight, crossfadeMillis = 0)
                            )
                            synchronized(visibleCoverWarmupDisposables) {
                                visibleCoverWarmupDisposables.add(disposable)
                            }
                        }
                    }
                }
                delay(24L)
            }
        }
    }

    fun scheduleCoverPrefetch(deferMs: Long = if (mediaType == "image") 220L else 0L) {
        cancelCoverPrefetches()
        val (reqWidth, reqHeight) = coverPrefetchSize()
        val itemsToPrefetch = if (mediaType == "image") {
            val visibleIndices = imageGalleryVisibleMediaIndices()
            if (visibleIndices.isNotEmpty()) {
                val firstVisible = visibleIndices.minOrNull() ?: 0
                val lastVisible = visibleIndices.maxOrNull() ?: firstVisible
                val previousStart = imageGalleryLastPrefetchStart.getAndSet(firstVisible)
                val scrollingDown = firstVisible >= previousStart
                val forwardRows = when {
                    imageGalleryCurrentColumns >= 6 -> 2
                    imageGalleryCurrentColumns == 5 -> 3
                    else -> 4
                }
                val aheadCount = imageGalleryCurrentColumns * if (scrollingDown) forwardRows else 1
                val behindCount = imageGalleryCurrentColumns * if (scrollingDown) 1 else 2
                val ahead = ((lastVisible + 1)..(lastVisible + aheadCount))
                    .filter { it in visibleItems.indices }
                val behind = ((firstVisible - behindCount) until firstVisible)
                    .filter { it in visibleItems.indices }
                if (scrollingDown) {
                    ahead + behind.asReversed()
                } else {
                    behind.asReversed() + ahead
                }
                    .distinct()
                    .mapNotNull { visibleItems.getOrNull(it) }
            } else {
                val startIndex = coverPrefetchStartIndex()
                val beforeCount = (imageGalleryCurrentColumns / 2).coerceAtLeast(1)
                val afterCount = imageGalleryCurrentColumns * 3
                visibleItems
                    .drop((startIndex - beforeCount).coerceAtLeast(0))
                    .take(beforeCount + afterCount)
            }
        } else {
            val startIndex = coverPrefetchStartIndex()
            visibleItems
                .drop((startIndex - 8).coerceAtLeast(0))
                .take(64)
        }.filter { item ->
            val url = coverUrl(serverUrl, token, item)
            url != null && !coverImageLoader.isCoverInMemory(url, reqWidth, reqHeight)
        }
        if (itemsToPrefetch.isEmpty()) return
        if (mediaType == "image") {
            logImageGalleryPerf(
                "prefetch scheduled count=${itemsToPrefetch.size} size=${reqWidth}x$reqHeight cols=$imageGalleryCurrentColumns defer=$deferMs"
            )
        }
        coverPrefetchJob = scope.launch(Dispatchers.IO) prefetch@{
            if (deferMs > 0L) delay(deferMs)
            if (mediaType == "image" && !imageGalleryNetworkAllowed.get()) return@prefetch
            val chunkSize = if (mediaType == "image") 1 else 18
            val chunkDelay = if (mediaType == "image") 120L else 24L
            itemsToPrefetch.chunked(chunkSize).forEach { chunk ->
                if (mediaType == "image" && !imageGalleryNetworkAllowed.get()) return@prefetch
                coroutineScope {
                    chunk.forEach { item ->
                        launch {
                            val url = coverUrl(serverUrl, token, item) ?: return@launch
                            val disposable = coverImageLoader.enqueue(
                                coverImageRequest(context, url, reqWidth, reqHeight, crossfadeMillis = 0)
                            )
                            synchronized(coverPrefetchDisposables) {
                                coverPrefetchDisposables.add(disposable)
                            }
                        }
                    }
                }
                delay(chunkDelay)
            }
        }
    }

    LaunchedEffect(
        visibleItems.size,
        mediaType,
        statusFilter,
        search,
        viewMode,
        renderedImageTileDp.roundToInt(),
        imageGalleryPinching,
        imageGallerySettling
    ) {
        if (visibleItems.isEmpty()) return@LaunchedEffect
        if (mediaType == "image" && (imageGalleryPinching || imageGallerySettling)) return@LaunchedEffect
        delay(180L)
        if (mediaType == "image") {
            scheduleVisibleCoverWarmup()
            scheduleCoverPrefetch(deferMs = 420L)
        } else {
            scheduleCoverPrefetch()
        }
    }

    LaunchedEffect(listState, swipeController) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling && !swipeController.isDragging) swipeController.close()
            }
    }
    LaunchedEffect(imageGridState, swipeController) {
        snapshotFlow { imageGridState.isScrollInProgress }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling && !swipeController.isDragging && !imageGalleryPinching) swipeController.close()
            }
    }
    // 旧的「pinch 期间每帧把 imageGridState 滚到锚点」LaunchedEffect 已删除——
    // 新模型只在 pinch 结束 swap 列数那一刻通过 scheduleImageGalleryAnchorCorrection
    // 单次复位，过程更平滑。LazyGridState 在本架构也不参与图廊渲染。
    LaunchedEffect(listState, imageGridState, mediaType) {
        snapshotFlow {
            if (mediaType == "image") imageGridState.isScrollInProgress else listState.isScrollInProgress
        }
            .distinctUntilChanged()
            .collectLatest { scrolling ->
                if (scrolling) {
                    cancelVisibleCoverWarmup()
                    cancelCoverPrefetches()
                    if (mediaType == "image") {
                        imageGalleryNetworkAllowed.set(false)
                        logImageGalleryPerf("scroll start: cancel prefetch, memory/disk only")
                    }
                } else {
                    delay(if (mediaType == "image") 180L else 120L)
                    if (mediaType == "image") {
                        imageGalleryNetworkAllowed.set(true)
                        scheduleVisibleCoverWarmup()
                        imageGalleryLoadGeneration += 1
                        logImageGalleryPerf("scroll settled: visible reload generation=$imageGalleryLoadGeneration")
                    }
                    scheduleCoverPrefetch(deferMs = if (mediaType == "image") 520L else 0L)
                }
            }
    }
    LaunchedEffect(viewMode) { swipeController.close() }
    LaunchedEffect(mediaType) {
        if (mediaType != "image") imageGalleryNetworkAllowed.set(true)
    }

    fun load(query: String = search) {
        val currentRequest = requestId + 1
        requestId = currentRequest
        loading = true
        error = null
        val querySnapshot = query.trim()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ApiClient(serverUrl, token).getMedia("", querySnapshot, "date") }
            }
            if (requestId != currentRequest) return@launch
            loading = false
            result.onSuccess { allItems = it }
            result.onFailure {
                // error state 已经走 ErrorPanelV2，不再额外弹 Toast 避免重复
                error = readableError(it)
            }
        }
    }

    // Single dispatch point for all per-card quick actions (long-press grid overlay
    // and left-swipe pane both call this). Optimistic local update first, network
    // call in the background, snackbar feedback on either path.
    fun runQuickAction(item: MediaItem, action: String) {
        swipeController.close()
        when (action) {
            "favorite" -> {
                val target = !item.favorite
                allItems = allItems.map {
                    if (it.id == item.id) it.also { it.favorite = target } else it
                }
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ApiClient(serverUrl, token).toggleFavorite(item.id, target) }
                    }
                    result.onSuccess { fresh ->
                        allItems = allItems.map { if (it.id == fresh.id) fresh else it }
                    }
                    result.onFailure {
                        // Roll back optimistic flip and surface the error.
                        allItems = allItems.map {
                            if (it.id == item.id) it.also { it.favorite = !target } else it
                        }
                        snackbarHostState.showSnackbar(
                            message = "收藏失败: ${readableError(it)}",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
            "tag" -> {
                tagSheetItem = item
            }
            "remove" -> {
                // Optimistic hide via pendingDeletes; actual DELETE is fired after the
                // undo window closes. Cancelling the job restores the row.
                pendingDeletes[item.id]?.cancel()
                val deleteJob = scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "已从媒体库移除「${item.title}」",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        pendingDeletes.remove(item.id)
                        return@launch
                    }
                    // Snackbar dismissed — commit the delete on the server.
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching { ApiClient(serverUrl, token).deleteMedia(item.id) }
                    }
                    outcome.onSuccess {
                        allItems = allItems.filterNot { it.id == item.id }
                        pendingDeletes.remove(item.id)
                    }
                    outcome.onFailure {
                        pendingDeletes.remove(item.id)
                        snackbarHostState.showSnackbar(
                            message = "删除失败: ${readableError(it)}",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
                pendingDeletes[item.id] = deleteJob
            }
        }
    }

    LaunchedEffect(serverUrl, token, search) {
        delay(if (search.isBlank()) 0L else 280L)
        load(search)
    }

    // Activity 重回前台时刷新媒体库；首次进入由上方 LaunchedEffect 负责初次加载，跳过避免重复。
    var hasSeenFirstResume by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (hasSeenFirstResume) {
            load(search)
        } else {
            hasSeenFirstResume = true
        }
    }

    fun scrollLibraryToTop() {
        swipeController.close()
        scope.launch {
            if (mediaType == "image") {
                imageGalleryRecyclerView?.smoothScrollToPosition(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerV2(
                serverUrl = serverUrl,
                items = allItems,
                filters = mediaFilters,
                selectedValue = mediaType,
                selectedStatusValue = statusFilter,
                loading = loading,
                onSelected = { value ->
                    mediaType = value
                    scope.launch { drawerState.close() }
                },
                onStatusSelected = { value ->
                    statusFilter = value
                    scope.launch { drawerState.close() }
                },
                onRefresh = {
                    scope.launch { drawerState.close() }
                    load(search)
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    context.toastComingSoon("设置")
                },
                onOpenCreators = {
                    scope.launch { drawerState.close() }
                    onOpenCreators()
                },
                onLogout = {
                    scope.launch { drawerState.close() }
                    onLogout()
                }
            )
        }
    ) {
        Scaffold(containerColor = Color.Transparent) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackgroundBrushV2())
                    .padding(padding)
            ) {
                if (mediaType == "image") {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val galleryGapDp = 2f
                        val availableWidthDp = (maxWidth.value - 4f).coerceAtLeast(1f)
                        // 列数是稳定单位；pinch 期间不变（视觉缩放走 graphicsLayer），
                        // 松手 swap 时一次性切到 targetColumns。
                        val galleryColumns = imageGalleryStableColumns
                            .coerceIn(imageGalleryMinColumns, imageGalleryMaxColumns)
                        val actualImageTileDp = imageGalleryTileForColumns(
                            availableWidthDp, galleryGapDp, galleryColumns
                        )
                        LaunchedEffect(actualImageTileDp) {
                            renderedImageTileDp = actualImageTileDp
                        }
                        LaunchedEffect(galleryColumns) {
                            imageGalleryCurrentColumns = galleryColumns
                        }
                        // 旧用户迁移：第一次进图廊时如果还没存过 columns 偏好，
                        // 从老的 tileDp + 当前可用宽度反推一个等价列数。
                        LaunchedEffect(availableWidthDp, imageGalleryColumnsMigrated) {
                            if (!imageGalleryColumnsMigrated && availableWidthDp > 1f) {
                                val migrated = imageGalleryColumnsForTile(
                                    galleryPrefs.galleryTileDp
                                        .coerceIn(imageGalleryMinTileDp, imageGalleryMaxTileDp),
                                    availableWidthDp,
                                    galleryGapDp,
                                    imageGalleryStableColumns,
                                    withHysteresis = false
                                ).coerceIn(imageGalleryMinColumns, imageGalleryMaxColumns)
                                imageGalleryStableColumns = migrated
                                galleryPrefs.galleryColumns = migrated
                                imageGalleryColumnsMigrated = true
                            }
                        }

                        // ----------------- pinch 回调 -----------------
                        val onPinchStart: (Offset) -> Unit = { focal ->
                            imageGalleryAnchor = captureImageGalleryAnchor(focal)
                            imageGalleryPinchStartColumns = imageGalleryStableColumns
                            imageGalleryPinching = true
                            imageGallerySettling = false
                            imageGalleryInteractionsEnabled = false
                            imageGalleryNetworkAllowed.set(false)
                            cancelVisibleCoverWarmup()
                            cancelCoverPrefetches()
                            pinchFocalPx = focal
                            scope.launch { pinchVisualScale.snapTo(1f) }
                        }
                        val onPinch: (Float, Offset) -> Unit = { scale, focal ->
                            pinchFocalPx = focal
                            // 阻尼：原始 pinch 缩放×0.72，避免微小手指移动跨列数阈值
                            val damped = 1f + (scale - 1f) * 0.72f
                            // 橡皮筋：推算目标列数越界时，多余力量减半
                            val implied = imageGalleryPinchStartColumns / damped
                            val clampedImplied = implied.coerceIn(
                                imageGalleryMinColumns.toFloat(),
                                imageGalleryMaxColumns.toFloat()
                            )
                            val rubberDamped = if (implied != clampedImplied) {
                                damped + (imageGalleryPinchStartColumns / clampedImplied - damped) * 0.5f
                            } else damped
                            scope.launch {
                                pinchVisualScale.snapTo(rubberDamped.coerceIn(0.35f, 3f))
                            }
                        }
                        val onPinchEnd: () -> Unit = {
                            val finalScale = pinchVisualScale.value
                            val targetColumns = (imageGalleryPinchStartColumns / finalScale)
                                .roundToInt()
                                .coerceIn(imageGalleryMinColumns, imageGalleryMaxColumns)
                            val startTile = imageGalleryTileForColumns(
                                availableWidthDp, galleryGapDp, imageGalleryPinchStartColumns
                            )
                            val targetTile = imageGalleryTileForColumns(
                                availableWidthDp, galleryGapDp, targetColumns
                            )
                            // handoffScale：使旧网格在 swap 瞬间的视觉大小等同新网格 scale=1 的大小
                            val handoffScale = targetTile / startTile
                            imageGallerySettling = true
                            imageGalleryNetworkAllowed.set(true)
                            scope.launch {
                                // Phase 1：平滑滑到 handoff
                                pinchVisualScale.animateTo(
                                    targetValue = handoffScale,
                                    animationSpec = tween(
                                        durationMillis = 140,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                // Phase 2：同帧 swap 列数 + scale snap 回 1
                                if (targetColumns != imageGalleryStableColumns) {
                                    imageGalleryStableColumns = targetColumns
                                    galleryPrefs.galleryColumns = targetColumns
                                }
                                pinchVisualScale.snapTo(1f)
                                imageGalleryPinching = false
                                imageGallerySettling = false
                                imageGalleryInteractionsEnabled = true
                                scheduleImageGalleryAnchorCorrection()
                                imageGalleryAnchor = null
                                scheduleVisibleCoverWarmup(deferMs = 80L)
                                scheduleCoverPrefetch(deferMs = 360L)
                            }
                        }

                        // ----------------- 渲染 -----------------
                        // 4 个 header 提到 Column 顶部（不参与 pinch 缩放）。瓦片网格放
                        // graphicsLayer Box，scale 跟 pinchVisualScale，origin 跟手指焦点。
                        Column(modifier = Modifier.fillMaxSize()) {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                                LibraryHeaderV2(
                                    loading = loading,
                                    viewMode = viewMode,
                                    galleryMode = true,
                                    onViewModeSelected = { viewMode = it },
                                    onMenu = { scope.launch { drawerState.open() } },
                                    onRefresh = { load(search) }
                                )
                            }
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                SearchAndFilterPanelV2(
                                    search = search,
                                    onSearchChange = { search = it },
                                    filters = mediaFilters,
                                    selectedValue = mediaType,
                                    onSelected = { mediaType = it },
                                    statusFilters = statusFilters,
                                    selectedStatusValue = statusFilter,
                                    onStatusSelected = { statusFilter = it },
                                    onOpenFilters = { filterSheetOpen = true }
                                )
                            }
                            if (error != null) {
                                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                    ErrorPanelV2(
                                        error = error ?: "",
                                        loading = loading,
                                        onRetry = { load(search) }
                                    )
                                }
                            }
                            if (loading) {
                                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                    LoadingLineV2()
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { pinchRecyclerSizePx = it }
                                    .graphicsLayer {
                                        scaleX = pinchVisualScale.value
                                        scaleY = pinchVisualScale.value
                                        val w = pinchRecyclerSizePx.width.toFloat()
                                            .coerceAtLeast(1f)
                                        val h = pinchRecyclerSizePx.height.toFloat()
                                            .coerceAtLeast(1f)
                                        transformOrigin = TransformOrigin(
                                            (pinchFocalPx.x / w).coerceIn(0f, 1f),
                                            (pinchFocalPx.y / h).coerceIn(0f, 1f)
                                        )
                                    }
                                    .clipToBounds()
                            ) {
                                NativeImageGalleryRecyclerV2(
                                    items = visibleItems,
                                    serverUrl = serverUrl,
                                    token = token,
                                    loading = loading,
                                    error = error,
                                    search = search,
                                    mediaFilters = mediaFilters,
                                    statusFilters = statusFilters,
                                    selectedMediaType = mediaType,
                                    selectedStatus = statusFilter,
                                    viewMode = viewMode,
                                    columns = galleryColumns,
                                    tileDp = actualImageTileDp,
                                    interactionsEnabled = imageGalleryInteractionsEnabled && !imageGalleryPinching,
                                    playlist = visibleItems,
                                    onSearchChange = { search = it },
                                    onMediaSelected = { mediaType = it },
                                    onStatusSelected = { statusFilter = it },
                                    onOpenFilters = { filterSheetOpen = true },
                                    onViewModeSelected = { viewMode = it },
                                    onMenu = { scope.launch { drawerState.open() } },
                                    onRefresh = { load(search) },
                                    onRetry = { load(search) },
                                    onRecycler = { imageGalleryRecyclerView = it },
                                    onBackTopVisible = { visible ->
                                        imageGalleryBackTopVisible = visible
                                    },
                                    inlineHeaders = false,
                                    pinchZoomEnabled = true,
                                    onPinchStart = onPinchStart,
                                    onPinch = onPinch,
                                    onPinchEnd = onPinchEnd,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(swipeController) {
                            detectTapGestures(onTap = { swipeController.close() })
                        },
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        LibraryHeaderV2(
                            loading = loading,
                            viewMode = viewMode,
                            galleryMode = false,
                            onViewModeSelected = { viewMode = it },
                            onMenu = { scope.launch { drawerState.open() } },
                            onRefresh = { load(search) }
                        )
                    }
                    item {
                        SearchAndFilterPanelV2(
                            search = search,
                            onSearchChange = { search = it },
                            filters = mediaFilters,
                            selectedValue = mediaType,
                            onSelected = { mediaType = it },
                            statusFilters = statusFilters,
                            selectedStatusValue = statusFilter,
                            onStatusSelected = { statusFilter = it },
                            onOpenFilters = { filterSheetOpen = true }
                        )
                    }
                    item {
                        AnimatedVisibility(
                            visible = error != null,
                            enter = fadeIn(tween(180)) + expandVertically(),
                            exit = fadeOut(tween(160)) + shrinkVertically()
                        ) {
                            ErrorPanelV2(error = error ?: "", loading = loading, onRetry = { load(search) })
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = loading,
                            enter = fadeIn(tween(160)),
                            exit = fadeOut(tween(160))
                        ) {
                            LoadingLineV2()
                        }
                    }
                    if (loading && allItems.isEmpty()) {
                        repeat(3) { index ->
                            item(key = "loading-card-v2-$index", contentType = "library-skeleton") {
                                LoadingCardSkeletonV2(index)
                            }
                        }
                    }
                    if (!loading && error == null && visibleItems.isEmpty()) {
                        item(key = "library-empty", contentType = "library-empty") { EmptyStateV2() }
                    }
                    when (viewMode) {
                        LibraryViewMode.Large -> {
                            items(visibleItems, key = { "large-${it.id}" }, contentType = { "library-large-card" }) { item ->
                                MediaCardV2(
                                    serverUrl = serverUrl,
                                    token = token,
                                    item = item,
                                    controller = swipeController,
                                    onOpen = { restart -> openItem(context, item, serverUrl, token, restart, visibleItems) },
                                    onQuickAction = { action -> runQuickAction(item, action) }
                                )
                            }
                        }
                        LibraryViewMode.Grid -> {
                            items(visibleGridRows, key = { row -> "grid-${row.firstOrNull()?.id ?: 0}" }, contentType = { "library-grid-row" }) { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        MediaGridTileV2(
                                            serverUrl = serverUrl,
                                            token = token,
                                            item = item,
                                            controller = swipeController,
                                            modifier = Modifier.weight(1f),
                                            onOpen = { openItem(context, item, serverUrl, token, false, visibleItems) },
                                            onQuickAction = { action -> runQuickAction(item, action) }
                                        )
                                    }
                                    repeat(3 - rowItems.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        LibraryViewMode.Detail -> {
                            items(visibleItems, key = { "detail-${it.id}" }, contentType = { "library-detail-row" }) { item ->
                                MediaDetailRowV2(
                                    serverUrl = serverUrl,
                                    token = token,
                                    item = item,
                                    controller = swipeController,
                                    onOpen = { restart -> openItem(context, item, serverUrl, token, restart, visibleItems) },
                                    onQuickAction = { action -> runQuickAction(item, action) }
                                )
                            }
                        }
                    }
                }
                }

                AnimatedVisibility(
                    visible = showBackToTopButton && !imageGalleryPinching,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 34.dp),
                    enter = fadeIn(tween(120)) + scaleIn(
                        initialScale = 0.88f,
                        animationSpec = tween(150, easing = FastOutSlowInEasing)
                    ),
                    exit = fadeOut(tween(110)) + scaleOut(
                        targetScale = 0.88f,
                        animationSpec = tween(120, easing = FastOutSlowInEasing)
                    )
                ) {
                    BackToTopButtonV2(onClick = ::scrollLibraryToTop)
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = Color(0xEE0F1320),
                        contentColor = Color.White,
                        actionColor = Color(0xFFB8C7FF)
                    )
                }

                if (filterSheetOpen) {
                    FilterBottomSheetV2(
                        filters = mediaFilters,
                        selectedValue = mediaType,
                        onSelected = { mediaType = it },
                        statusFilters = statusFilters,
                        selectedStatusValue = statusFilter,
                        onStatusSelected = { statusFilter = it },
                        onDismiss = { filterSheetOpen = false }
                    )
                }

                val sheetItem = tagSheetItem
                if (sheetItem != null) {
                    TagPickerSheetV2(
                        serverUrl = serverUrl,
                        token = token,
                        item = sheetItem,
                        onDismiss = { tagSheetItem = null },
                        onTagAdded = { fresh ->
                            allItems = allItems.map { if (it.id == fresh.id) fresh else it }
                            tagSheetItem = fresh
                        }
                    )
                }
            }
        }
    }
}

