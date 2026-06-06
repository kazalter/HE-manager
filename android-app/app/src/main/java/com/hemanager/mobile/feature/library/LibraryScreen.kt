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
    onOpenBd2Spine: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val coverImageLoader = LocalCoverImageLoader.current
    val scope = rememberCoroutineScope()
    var mediaType by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("") }
    // sourceFilter / sortFilter 来自 HE OP filter sheet 新增的两个分区。
    // sourceFilter 走客户端筛（MediaItem.sourceSite 字段，来自 backend Media.source_site）：
    //   "all" → 不筛；"local" → sourceSite==null；其它 → sourceSite 精确匹配 ("x" / "wnacg" / "asmr" / "bd2")
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
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
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

                                // HE OP 浮动列数 pill — 底部居中
                                if (!imageGalleryPinching && !imageGallerySettling) {
                                    ColsPill(
                                        currentColumns = imageGalleryStableColumns,
                                        onColumnsChange = { newCols ->
                                            imageGalleryStableColumns = newCols
                                            galleryPrefs.galleryColumns = newCols
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 22.dp),
                                    )
                                }

                                if (
                                    !imageGalleryPinching &&
                                    !imageGallerySettling &&
                                    drawerState.currentValue == DrawerValue.Closed
                                ) {
                                    val drawerBridgeOpenDistancePx = with(density) { 28.dp.toPx() }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .fillMaxHeight()
                                            .width(40.dp)
                                            .pointerInput(drawerBridgeOpenDistancePx) {
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                    val start = down.position
                                                    var opened = false
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull { it.id == down.id }
                                                            ?: break
                                                        val dx = change.position.x - start.x
                                                        val dy = change.position.y - start.y
                                                        if (
                                                            !opened &&
                                                            dx > drawerBridgeOpenDistancePx &&
                                                            abs(dx) > abs(dy) * 1.05f
                                                        ) {
                                                            opened = true
                                                            scope.launch { drawerState.open() }
                                                        }
                                                        if (!change.pressed) break
                                                    }
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                } else {
                // HE OP — hero + queue 只在「没有任何 filter + 有正在看的」时出现，
                // 避免和筛选模式语义冲突
                val viewingItems = allItems.filter { it.viewStatus == "viewing" }
                val showHero = mediaType.isBlank() && statusFilter.isBlank() &&
                    search.isBlank() && viewingItems.isNotEmpty()
                val queueItems = if (showHero) {
                    (viewingItems.drop(1) + allItems.filter { it.favorite && it.viewStatus != "viewing" })
                        .distinctBy { it.id }
                        .take(8)
                } else emptyList()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(swipeController) {
                            detectTapGestures(onTap = { swipeController.close() })
                        },
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    if (showHero) {
                        item(key = "hero", contentType = "library-hero") {
                            HeroFeature(
                                serverUrl = serverUrl,
                                token = token,
                                item = viewingItems.first(),
                                onContinue = { restart ->
                                    openItem(context, viewingItems.first(), serverUrl, token, restart, visibleItems)
                                },
                                onToggleStar = { runQuickAction(viewingItems.first(), "favorite") },
                            )
                        }
                        if (queueItems.isNotEmpty()) {
                            item(key = "queue", contentType = "library-queue") {
                                QueueRow(
                                    serverUrl = serverUrl,
                                    token = token,
                                    items = queueItems,
                                    onItemClick = { clicked ->
                                        openItem(context, clicked, serverUrl, token, false, visibleItems)
                                    },
                                )
                            }
                        }
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
                    item(key = "status-stripe", contentType = "library-status") {
                        Spacer(Modifier.height(12.dp))
                        com.hemanager.mobile.ui.op.StatusStripe(
                            server = serverHostV2(serverUrl),
                        )
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
                        mediaFilter = mediaType,
                        onMediaSelected = { mediaType = it },
                        statusFilter = statusFilter,
                        onStatusSelected = { statusFilter = it },
                        sourceFilter = sourceFilter,
                        onSourceSelected = { sourceFilter = it },
                        sortFilter = sortFilter,
                        onSortSelected = { sortFilter = it },
                        onReset = {
                            mediaType = ""
                            statusFilter = ""
                            sourceFilter = "all"
                            sortFilter = "added"
                        },
                        onDismiss = { filterSheetOpen = false },
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

