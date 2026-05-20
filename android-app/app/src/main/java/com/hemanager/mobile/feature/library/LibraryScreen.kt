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
    var imageGalleryStableTileDp by remember {
        mutableFloatStateOf(
            galleryPrefs.galleryTileDp.coerceIn(imageGalleryMinTileDp, imageGalleryMaxTileDp)
        )
    }
    var imageGalleryTemporaryTileDp by remember { mutableFloatStateOf(imageGalleryStableTileDp) }
    var imageGalleryPinchStartTileDp by remember { mutableFloatStateOf(imageGalleryStableTileDp) }
    var imageGalleryPinching by remember { mutableStateOf(false) }
    var imageGallerySettling by remember { mutableStateOf(false) }
    var imageGalleryInteractionsEnabled by remember { mutableStateOf(true) }
    var imageGalleryAnchor by remember { mutableStateOf<GalleryPinchAnchor?>(null) }
    var imageGalleryAnchorJob by remember { mutableStateOf<Job?>(null) }
    val renderedImageTileDp by animateFloatAsState(
        targetValue = if (imageGalleryPinching) imageGalleryTemporaryTileDp else imageGalleryStableTileDp,
        animationSpec = if (imageGalleryPinching) {
            tween(durationMillis = 0)
        } else {
            tween(durationMillis = 160, easing = FastOutSlowInEasing)
        },
        label = "imageGalleryTileDp",
        finishedListener = {
            if (imageGallerySettling) {
                imageGallerySettling = false
                imageGalleryTemporaryTileDp = imageGalleryStableTileDp
                imageGalleryAnchor = null
                galleryPrefs.galleryTileDp = imageGalleryStableTileDp
                imageGalleryInteractionsEnabled = true
            }
        }
    )
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
        val item = imageGridState.layoutInfo.visibleItemsInfo
            .filter { (it.key as? String)?.startsWith("image-gallery-") == true }
            .minByOrNull {
                val centerX = it.offset.x + it.size.width / 2f
                val centerY = it.offset.y + it.size.height / 2f
                abs(centerX - focalPoint.x) + abs(centerY - focalPoint.y)
            }
        return item?.let { GalleryPinchAnchor(itemIndex = it.index, topOffsetPx = it.offset.y) }
    }

    fun scheduleImageGalleryAnchorCorrection() {
        val anchor = imageGalleryAnchor ?: return
        imageGalleryAnchorJob?.cancel()
        imageGalleryAnchorJob = scope.launch {
            withFrameNanos { }
            imageGridState.scrollToItem(anchor.itemIndex, -anchor.topOffsetPx)
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
    LaunchedEffect(imageGridState) {
        snapshotFlow { Triple(renderedImageTileDp, imageGalleryPinching, imageGallerySettling) }
            .collectLatest { (_, pinching, settling) ->
                if ((pinching || settling) && mediaType == "image") {
                    imageGalleryAnchor?.let {
                        imageGridState.scrollToItem(it.itemIndex, -it.topOffsetPx)
                    }
                }
            }
    }
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
                        val galleryColumns = floor(
                            (availableWidthDp + galleryGapDp) / (renderedImageTileDp + galleryGapDp)
                        ).toInt().coerceIn(imageGalleryMinColumns, imageGalleryMaxColumns)
                        val actualImageTileDp = (
                            (availableWidthDp - galleryGapDp * (galleryColumns - 1)) / galleryColumns
                        ).coerceAtLeast(1f)
                        LaunchedEffect(galleryColumns) {
                            imageGalleryCurrentColumns = galleryColumns
                        }
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
                            onBackTopVisible = { visible -> imageGalleryBackTopVisible = visible },
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                        )
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

