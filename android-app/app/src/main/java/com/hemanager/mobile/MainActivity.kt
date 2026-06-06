package com.hemanager.mobile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.hemanager.mobile.feature.creators.CreatorsScreen
import com.hemanager.mobile.feature.library.LibraryScreenV2
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
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private val coverImageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.24)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cover_cache"))
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .build()
    }

    // 颜色统一在 ui.theme.HeColors / HeColorScheme 中维护
    private val scheme = HeColorScheme

    // -- 跨模块共享的 Activity 级状态（feature/library、feature/creators 等通过
    //    `LocalContext.current as? MainActivity` 拿到并读写）。

    /** 是否当前在「创作者」全屏页面上。CreatorsScreen 用 DisposableEffect 控制，决定状态栏显隐。 */
    internal var creatorsScreenActive: Boolean = false

    /** 左边缘滑动手势触发时调用的回调；由 LibraryScreen 在挂载时注册（指向 drawerState.open()）。 */
    internal var edgeDrawerOpenRequester: (() -> Unit)? = null

    /** 是否启用左边缘抽屉手势。LibraryScreen 挂载时设 true，卸载或图廊全屏时设 false。 */
    internal var edgeDrawerGestureEnabled: Boolean = false

    /** 图廊原生 RecyclerView 上 pinch 缩放正在进行中。Gallery 模块 OnItemTouchListener 设；
     *  dispatchTouchEvent 读到为 true 时主动放弃边缘手势探测，避免双指误判。 */
    internal var imageGalleryNativePinching: Boolean = false

    /** 图廊每个 RecyclerView 注册的 pinch OnItemTouchListener，WeakHashMap 防止
     *  RecyclerView 被销毁后我们还持有强引用造成内存泄漏。 */
    private val imageGalleryPinchTouchListeners =
        WeakHashMap<RecyclerView, RecyclerView.OnItemTouchListener>()

    // -- 边缘手势触摸跟踪（仅 dispatchTouchEvent 内部使用，保持 private）
    private var edgeDrawerTracking: Boolean = false
    private var edgeDrawerOpened: Boolean = false
    private var edgeDrawerStartX: Float = 0f
    private var edgeDrawerStartY: Float = 0f


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 10, 18)
        window.navigationBarColor = android.graphics.Color.rgb(7, 10, 18)

        // 刘海/挖孔屏：永远不让内容延伸进 cutout 区域。各 OEM 默认值不统一（状态栏隐藏后内容
        // 可能滑到挖孔下面），显式钉死成 NEVER，挖孔区会保留一条背景色窄边而不是覆盖 UI。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }

        setContent {
            CompositionLocalProvider(LocalCoverImageLoader provides coverImageLoader) {
                MaterialTheme(colorScheme = scheme) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        HeManagerApp()
                    }
                }
            }
        }

        // 状态栏只在库主页等场景显示；创作者页面进入时由 CreatorsScreen 自己切隐藏。
        // 这里按当前 creatorsScreenActive（默认 false）应用一次初始状态。
        setStatusBarHidden(creatorsScreenActive)
        // 在左边沿注册系统手势排除区，让 Android 10+ 的返回手势不会吃掉左边缘滑动。
        applyDrawerEdgeExclusion()
    }

    // ------------------------------------------------------------------------
    // 全局触摸分发：左边缘滑动开抽屉
    // ------------------------------------------------------------------------
    // 在 ComponentActivity 顶层拦截：从左边缘 56dp 内按下、向右滑超过 32dp 时，
    // 触发 edgeDrawerOpenRequester 回调（由 LibraryScreen 注入）。
    //
    // 谨慎让出：
    //   - 多指（pinch）：直接放过，让 RecyclerView 自己处理缩放
    //   - 图廊正在原生 pinch：让出
    //   - 垂直分量明显大于水平（用户在滚列表）：让出
    //   - 反向左滑：让出
    //
    // 这块逻辑必须在 ComponentActivity 层而不是 Compose 层，因为目标是「跨 composable 全局
    // 都能从左边沿开抽屉」，不限于 LibraryScreen 内部的可点击区域。
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!edgeDrawerGestureEnabled || imageGalleryNativePinching || event.pointerCount > 1) {
            edgeDrawerTracking = false
            edgeDrawerOpened = false
            return super.dispatchTouchEvent(event)
        }

        val density = resources.displayMetrics.density
        val edgeWidthPx = 56f * density
        val openDistancePx = 32f * density
        val touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeDrawerTracking = event.x <= edgeWidthPx
                edgeDrawerOpened = false
                edgeDrawerStartX = event.x
                edgeDrawerStartY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeDrawerTracking) {
                    val dx = event.x - edgeDrawerStartX
                    val dy = event.y - edgeDrawerStartY
                    val absDx = abs(dx)
                    val absDy = abs(dy)

                    // 仅在 Y 明显主导时才放弃跟踪，保护垂直列表滚动。
                    if (absDy > absDx * 1.5f && absDy > touchSlopPx * 2) {
                        edgeDrawerTracking = false
                    } else if (dx < -touchSlopPx) {
                        edgeDrawerTracking = false
                    } else if (dx > openDistancePx && absDx > absDy * 1.1f) {
                        edgeDrawerTracking = false
                        edgeDrawerOpened = true
                        edgeDrawerOpenRequester?.invoke()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consume = edgeDrawerOpened
                edgeDrawerTracking = false
                edgeDrawerOpened = false
                if (consume) return true
            }
        }

        return super.dispatchTouchEvent(event)
    }

    /** 显示或隐藏顶部状态栏（仅本 Activity 的 window）。隐藏时支持顶部下滑短暂显示。 */
    internal fun setStatusBarHidden(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统在 focus 变化时（旋转、前后台切换）有时会清掉 systemGestureExclusionRects，
        // 也可能重置状态栏。重新应用一次以保持一致。
        if (hasFocus) {
            setStatusBarHidden(creatorsScreenActive)
            applyDrawerEdgeExclusion()
        }
    }

    private fun applyDrawerEdgeExclusion() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val decor = window.decorView
        decor.post {
            val edge = (56f * resources.displayMetrics.density).toInt()
            val height = decor.height
            if (edge > 0 && height > 0) {
                decor.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, edge, height))
            }
        }
    }

    // ------------------------------------------------------------------------
    // 图廊 pinch 缩放：原生 RecyclerView OnItemTouchListener
    // ------------------------------------------------------------------------
    // 比 Compose 的 awaitEachGesture 修饰符更响应——不走 Compose 的事件管线，
    // 直接在 RecyclerView 层拦截，配合 requestDisallowInterceptTouchEvent 阻止
    // 父级（包括我们自己的 dispatchTouchEvent 边缘手势）抢走双指。
    //
    // 该方法被 Gallery 模块通过 (context as MainActivity).setImageGalleryPinchTouchListener(...)
    // 调用。enabled=false 时移除已注册的监听并复位 imageGalleryNativePinching 标志。
    //
    // 回调语义：
    //   onPinchStart(focal): 第二指落下，传入两指中点（RecyclerView 局部坐标）
    //   onPinch(scale, focal): 持续两指移动；scale = currentDistance / startDistance，已 clamp 到 [0.45, 2.40]
    //   onPinchEnd(): 任一指抬起或取消
    internal fun setImageGalleryPinchTouchListener(
        recyclerView: RecyclerView,
        enabled: Boolean,
        onPinchStart: (Offset) -> Unit,
        onPinch: (Float, Offset) -> Unit,
        onPinchEnd: () -> Unit
    ) {
        imageGalleryPinchTouchListeners.remove(recyclerView)?.let {
            recyclerView.removeOnItemTouchListener(it)
        }
        if (!enabled) {
            imageGalleryNativePinching = false
            return
        }

        var pinching = false
        var startDistance = 0f

        fun distance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return sqrt(dx * dx + dy * dy)
        }

        fun focalPoint(event: MotionEvent): Offset {
            if (event.pointerCount < 2) return Offset.Zero
            return Offset(
                (event.getX(0) + event.getX(1)) / 2f,
                (event.getY(0) + event.getY(1)) / 2f
            )
        }

        fun endPinch() {
            if (!pinching) return
            pinching = false
            imageGalleryNativePinching = false
            recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
            onPinchEnd()
        }

        val listener = object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                if (
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_UP
                ) {
                    val wasPinching = pinching
                    endPinch()
                    return wasPinching
                }
                if (event.pointerCount >= 2) {
                    val currentDistance = distance(event)
                    if (currentDistance > 1f && !pinching) {
                        pinching = true
                        imageGalleryNativePinching = true
                        startDistance = currentDistance
                        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                        onPinchStart(focalPoint(event))
                    }
                    return true
                }
                return pinching
            }

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                if (
                    event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    event.actionMasked == MotionEvent.ACTION_POINTER_UP
                ) {
                    endPinch()
                } else if (event.pointerCount >= 2) {
                    val currentDistance = distance(event)
                    if (currentDistance <= 1f) return
                    if (!pinching) {
                        pinching = true
                        imageGalleryNativePinching = true
                        startDistance = currentDistance
                        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                        onPinchStart(focalPoint(event))
                    } else {
                        val scale = (currentDistance / startDistance).coerceIn(0.45f, 2.40f)
                        onPinch(scale, focalPoint(event))
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        }

        imageGalleryPinchTouchListeners[recyclerView] = listener
        recyclerView.addOnItemTouchListener(listener)
    }

    @Composable
    private fun HeManagerApp() {
        val prefs = remember { HePrefs(this) }
        var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
        var token by remember { mutableStateOf(prefs.token) }
        var serverHistory by remember { mutableStateOf(prefs.serverHistory) }

        if (serverUrl.isBlank() || token.isBlank()) {
            LoginScreen(
                initialServer = serverUrl,
                serverHistory = serverHistory,
                onRemoveServer = {
                    prefs.removeServerHistory(it)
                    serverHistory = prefs.serverHistory
                },
                onLoggedIn = { nextServer, nextToken ->
                    serverUrl = nextServer
                    token = nextToken
                    prefs.saveCredentials(nextServer, nextToken)
                    serverHistory = prefs.serverHistory
                }
            )
        } else {
            // rememberSaveable：creators 屏幕标志要跨 Activity 重建保留（config 改、
            // process death after 打开重型播放器 Activity）。普通 remember 在返回路径
            // 上 reset 为 false，会掉到 library 根，再一次返回直接退 App。
            var showCreators by androidx.compose.runtime.saveable.rememberSaveable {
                mutableStateOf(false)
            }
            var showBd2Spine by androidx.compose.runtime.saveable.rememberSaveable {
                mutableStateOf(false)
            }
            if (showCreators) {
                CreatorsScreen(
                    serverUrl = serverUrl,
                    token = token,
                    onBack = { showCreators = false }
                )
            } else if (showBd2Spine) {
                com.hemanager.mobile.feature.bd2.Bd2SpineScreen(
                    serverUrl = serverUrl,
                    token = token,
                    onBack = { showBd2Spine = false }
                )
            } else {
                LibraryScreenV2(
                    serverUrl = serverUrl,
                    token = token,
                    onOpenCreators = { showCreators = true },
                    onOpenBd2Spine = { showBd2Spine = true },
                    onLogout = {
                        prefs.clearToken()
                        token = ""
                    }
                )
            }
        }
    }
}
