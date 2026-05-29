package com.hemanager.mobile.feature.library

// Library 媒体卡片相关 composable + 卡片左滑揭示控件。
// 包括 MediaCardV2（大卡片）、MediaGridTileV2（网格瓦片）、SwipeRevealController +
// SwipeRevealCard（左滑暴露快捷动作）、以及 QuickActions* 一族（长按弹出操作）。

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
import androidx.compose.ui.unit.sp
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
internal fun MediaCardV2(
    serverUrl: String,
    token: String,
    item: MediaItem,
    controller: SwipeRevealController,
    onOpen: (Boolean) -> Unit,
    onQuickAction: (String) -> Unit
) {
    val progress = progressFraction(item)
    val accent = typeAccent(item.mediaType)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.988f else 1f,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "mediaCardV2Scale"
    )

    val tapWhileSomethingOpen: () -> Unit = {
        if (controller.openKey != null) controller.close() else onOpen(false)
    }
    val playWhileSomethingOpen: (Boolean) -> Unit = { restart ->
        if (controller.openKey != null) controller.close() else onOpen(restart)
    }

    SwipeRevealCard(
        itemKey = "large-${item.id}",
        controller = controller,
        shape = com.hemanager.mobile.ui.theme.CutCornerShape(14.dp),
        actions = { progress ->
            QuickActionsPaneV2(
                item = item,
                onAction = { action -> onQuickAction(action) },
                progress = progress
            )
        }
    ) {
        // HE OP CardLargeOp — cut 14 + hairline + 封面 16:10
        com.hemanager.mobile.ui.op.AngularPanel(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .clickable(interactionSource = interactionSource, indication = null, onClick = tapWhileSomethingOpen),
            cut = 14.dp,
            background = com.hemanager.mobile.ui.theme.HeColors.Panel,
            contentPadding = PaddingValues(0.dp),
        ) {
            Column {
                // 封面区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.60f)
                ) {
                    RemoteCoverV2(
                        url = coverUrl(serverUrl, token, item),
                        label = mediaTypeLabelV2(item.mediaType),
                        accent = accent,
                        decodeWidthPx = 420,
                        decodeHeightPx = 280,
                        cutDp = 0.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    // HUD 角标（隐藏下方两角，因为下面是信息条）
                    com.hemanager.mobile.ui.op.HudBrackets(
                        modifier = Modifier.fillMaxSize(),
                        inset = 8.dp,
                        length = 12.dp,
                        hideCorners = setOf(
                            com.hemanager.mobile.ui.theme.Corner.BL,
                            com.hemanager.mobile.ui.theme.Corner.BR,
                        ),
                    )
                    com.hemanager.mobile.ui.op.TypeChip(
                        mediaType = item.mediaType,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    )
                    if (item.favorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "收藏",
                            tint = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .size(14.dp),
                        )
                    }
                    if (item.mediaType == "manga" && item.pageCount > 0) {
                        com.hemanager.mobile.ui.op.CodeChip(
                            text = "${item.pageCount}P",
                            color = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                        )
                    }
                    FloatingPlayButtonV2(
                        label = if (item.viewStatus == "viewing") "RESUME" else "PLAY",
                        onClick = { playWhileSomethingOpen(false) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    )
                }

                // 信息条
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.hemanager.mobile.ui.op.CodeChip(
                            text = fakeCode(item),
                            color = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                        )
                        Spacer(Modifier.weight(1f))
                        when (item.viewStatus) {
                            "viewing" -> StatusPillV2(
                                label = "ONGOING",
                                color = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                            )
                            "viewed" -> StatusPillV2(
                                label = "COMPLETED",
                                color = com.hemanager.mobile.ui.theme.HeColors.Online,
                            )
                        }
                    }
                    Text(
                        item.title,
                        color = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
                        fontFamily = com.hemanager.mobile.ui.theme.NotoSansSC,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        lineHeight = 23.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        metaInlineV2(item),
                        color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteMuted,
                        fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.5.sp,
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AnimatedVisibility(
                        visible = progress != null,
                        enter = fadeIn(tween(180)) + expandVertically(),
                        exit = fadeOut(tween(160)) + shrinkVertically()
                    ) {
                        com.hemanager.mobile.ui.op.ProgressO(
                            value = progress ?: 0f,
                            height = 2.dp,
                        )
                    }
                    if (item.missing || (item.mediaType == "manga" && (item.viewStatus == "viewing" || item.viewStatus == "viewed"))) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.missing) TinyBadgeV2("MISSING", com.hemanager.mobile.ui.theme.HeColors.OpDanger)
                            if (item.mediaType == "manga" && (item.viewStatus == "viewing" || item.viewStatus == "viewed")) {
                                ActionPillV2("RESTART", com.hemanager.mobile.ui.theme.HeColors.OpWhite) {
                                    playWhileSomethingOpen(true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class SwipeRevealController {
    var openKey by mutableStateOf<Any?>(null)
        private set
    var isDragging by mutableStateOf(false)
    fun open(key: Any) { openKey = key }
    fun close() { openKey = null }
    fun startDrag() { isDragging = true }
    fun endDrag() { isDragging = false }
}

enum class SwipeState { Closed, Dragging, Settling, Opened }

/**
 * Swipe-to-reveal card modelled on Gmail / iOS Mail / WeChat list rows.
 *
 * State machine — every transition is explicit, no implicit booleans:
 *   Closed   ──(|dx|>slop && |dx|>|dy|*1.2)──▶ Dragging
 *   Opened   ──(|dx|>slop && |dx|>|dy|*1.2)──▶ Dragging
 *   Dragging ──(finger up)──▶ Settling
 *   Settling ──(animation end / new touch)──▶ Closed | Opened
 *
 * Commit decision (on finger up, in priority order):
 *   1. velocity ≤ -velocityThreshold  → open   (fast left flick)
 *   2. velocity ≥ +velocityThreshold  → close  (fast right flick)
 *   3. |dragDelta| ≥ distanceThreshold → flip toward drag direction
 *   4. otherwise → keep current rest state
 *
 * Vertical-scroll cohabitation:
 *   We don't consume anything until the direction lock fires. If |dy| wins,
 *   we return from awaitEachGesture without ever consuming, so LazyColumn's
 *   scrollable owns the remainder of the gesture as if this card weren't here.
 *
 * Units: VelocityTracker reports px/s. We convert the dp/s threshold to px/s
 *   once via LocalDensity so the threshold *feels* the same on every DPI —
 *   conflating the two is the classic "fast flick works on phone A but not on phone B" bug.
 */
@Composable
internal fun SwipeRevealCard(
    itemKey: Any,
    controller: SwipeRevealController,
    actions: @Composable (progress: () -> Float) -> Unit,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    actionsWidth: androidx.compose.ui.unit.Dp = 156.dp,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val openPx = with(density) { actionsWidth.toPx() }
    val touchSlopPx = with(density) { 10.dp.toPx() }
    val distanceThresholdPx = openPx * 0.35f
    val velocityThresholdPxS = with(density) { 800.dp.toPx() }

    val offsetState = remember(itemKey) { mutableFloatStateOf(0f) }
    val stateRef = remember(itemKey) { mutableStateOf(SwipeState.Closed) }
    var settleJob by remember(itemKey) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val externallyOpen = controller.openKey == itemKey

    fun settleTo(target: Float, initialVelocityPxS: Float) {
        settleJob?.cancel()
        stateRef.value = SwipeState.Settling
        settleJob = scope.launch {
            animate(
                initialValue = offsetState.floatValue,
                targetValue = target,
                initialVelocity = initialVelocityPxS,
                // Critically damped spring → no overshoot, ~200ms to rest.
                // Stays inside the 180–260ms target window the spec calls for.
                animationSpec = spring(
                    stiffness = 700f,
                    dampingRatio = 1.0f,
                    visibilityThreshold = 0.5f
                )
            ) { value, _ -> offsetState.floatValue = value }
            stateRef.value = if (target == 0f) SwipeState.Closed else SwipeState.Opened
        }
    }

    // External transitions: another card opened, list scrolled, tap-outside.
    // Skipped while a finger is on the card — the gesture owns the offset.
    LaunchedEffect(externallyOpen, openPx) {
        if (controller.isDragging) return@LaunchedEffect
        val target = if (externallyOpen) -openPx else 0f
        if (offsetState.floatValue != target) {
            settleTo(target, 0f)
        } else {
            stateRef.value = if (externallyOpen) SwipeState.Opened else SwipeState.Closed
        }
    }

    // Read at draw time, not composition time — per-frame progress
    // updates never recompose the action tiles.
    val progressProvider: () -> Float = remember(itemKey, openPx) {
        { ((-offsetState.floatValue) / openPx).coerceIn(0f, 1f) }
    }

    Box(modifier = Modifier.fillMaxWidth().clip(shape)) {
        // Action layer behind the card. Clipped to the revealed strip so the
        // translucent card body cannot bleed tile colors through when closed.
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    val revealPx = (-offsetState.floatValue).coerceAtLeast(0f)
                    if (revealPx > 0f) {
                        clipRect(left = size.width - revealPx) {
                            this@drawWithContent.drawContent()
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(actionsWidth)
            ) {
                actions(progressProvider)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetState.floatValue.roundToInt(), 0) }
                .pointerInput(itemKey, openPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        val pointerId = down.id

                        // Touching halts any in-flight settle so the finger
                        // immediately picks up the position the animation was at.
                        settleJob?.cancel()
                        // We don't yet know if this is a tap, a horizontal drag,
                        // or a vertical scroll. State stays at whatever rest it was
                        // until the direction lock fires.

                        var totalDx = 0f
                        var totalDy = 0f
                        var startOffset = offsetState.floatValue

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: break

                            // Accumulate motion FIRST — including on the up event,
                            // which on a fast flick carries the largest positional jump
                            // and is therefore the most informative velocity sample.
                            val dx = change.position.x - change.previousPosition.x
                            val dy = change.position.y - change.previousPosition.y
                            totalDx += dx
                            totalDy += dy
                            velocityTracker.addPosition(change.uptimeMillis, change.position)

                            val isDragging = stateRef.value == SwipeState.Dragging

                            if (!isDragging) {
                                val absDx = abs(totalDx)
                                val absDy = abs(totalDy)
                                if (absDx > touchSlopPx && absDx > absDy * 1.2f) {
                                    // Closed/Opened → Dragging. Claim the gesture.
                                    stateRef.value = SwipeState.Dragging
                                    startOffset = offsetState.floatValue
                                    controller.startDrag()
                                    // Auto-close any other card the moment the user
                                    // commits to dragging this one — single-open invariant.
                                    if (controller.openKey != null && controller.openKey != itemKey) {
                                        controller.close()
                                    }
                                } else if (absDy > touchSlopPx && absDy > absDx * 1.2f) {
                                    // Vertical wins. We never consumed an event, so
                                    // LazyColumn's scrollable receives this gesture as
                                    // if this pointerInput weren't installed.
                                    return@awaitEachGesture
                                }
                            }

                            val nowDragging = stateRef.value == SwipeState.Dragging
                            if (nowDragging && change.pressed) {
                                // Consume so (a) the inner clickable's press is cancelled,
                                // (b) LazyColumn never sees these as scroll events.
                                change.consume()
                                val raw = offsetState.floatValue + dx
                                // Hard clamp — no rubber-band, no half-stable midpoints.
                                offsetState.floatValue = raw.coerceIn(-openPx, 0f)
                            }

                            if (!change.pressed) {
                                if (nowDragging) {
                                    controller.endDrag()
                                    val velocityPxS = velocityTracker.calculateVelocity().x
                                    val dragDelta = offsetState.floatValue - startOffset

                                    val shouldOpen = when {
                                        velocityPxS <= -velocityThresholdPxS -> true   // fast left flick
                                        velocityPxS >= velocityThresholdPxS -> false  // fast right flick
                                        abs(dragDelta) >= distanceThresholdPx -> dragDelta < 0
                                        else -> controller.openKey == itemKey         // hold rest
                                    }
                                    if (shouldOpen) controller.open(itemKey) else controller.close()
                                    settleTo(if (shouldOpen) -openPx else 0f, velocityPxS)
                                }
                                // No lock and finger up → it was a tap; we never consumed,
                                // so the inner clickable will fire its onClick normally.
                                break
                            }
                        }
                    }
                }
        ) {
            content()
        }
    }
}

@Composable
internal fun QuickActionsPaneV2(
    item: MediaItem,
    onAction: (String) -> Unit,
    progress: () -> Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickActionTileV2(
                label = "收藏",
                icon = if (item.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                accent = Color(0xFFF6C46B),
                onClick = { onAction("favorite") },
                progress = progress,
                modifier = Modifier.weight(1f)
            )
            QuickActionTileV2(
                label = "标签",
                icon = Icons.Default.LocalOffer,
                accent = Color(0xFF7AB8FF),
                onClick = { onAction("tag") },
                progress = progress,
                modifier = Modifier.weight(1f)
            )
            QuickActionTileV2(
                label = "删除",
                icon = Icons.Default.Delete,
                accent = Color(0xFFFF8CA3),
                onClick = { onAction("remove") },
                progress = progress,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun QuickActionTileV2(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    progress: () -> Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            // Progress-driven feedback read at draw time (no per-frame recomposition).
            // Fade in over the first 70% of the reveal so partial swipes don't show a
            // half-formed button. Slight scale-up resolves only at full reveal.
            .graphicsLayer {
                val p = progress()
                val a = (p / 0.7f).coerceIn(0f, 1f)
                alpha = a
                val s = 0.88f + 0.12f * a
                scaleX = s
                scaleY = s
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.18f),
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                label,
                color = Color.White.copy(alpha = 0.94f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * Long-press grid overlay — modeled after modern photo-library quick-action layers.
 *
 * Visual goals:
 *  - Card content stays visible underneath; mask is just a 55% black wash.
 *  - Three icon-only frosted-glass circles; no labels.
 *  - Buttons scale 0.9 → 1.0 and fade 0 → 1 on reveal; mask fades in.
 *  - Tap outside the buttons dismisses (handled by SwipeRevealController via this view's
 *    parent; the mask itself eats taps so card-click doesn't fire through).
 */
@Composable
internal fun GridQuickActionsOverlayV2(
    item: MediaItem,
    visible: Boolean,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180)),
        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.96f, animationSpec = tween(140))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                // Translucent so the card content beneath stays softly visible.
                .background(Color.Black.copy(alpha = 0.62f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() })
                },
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionCircleV2(
                    icon = if (item.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    accent = Color(0xFFF6C46B),
                    contentDescription = if (item.favorite) "取消收藏" else "收藏",
                    onClick = { onAction("favorite") }
                )
                QuickActionCircleV2(
                    icon = Icons.Default.LocalOffer,
                    accent = Color(0xFF7AB8FF),
                    contentDescription = "添加标签",
                    onClick = { onAction("tag") }
                )
                QuickActionCircleV2(
                    icon = Icons.Default.Delete,
                    accent = Color(0xFFFF8CA3),
                    contentDescription = "删除",
                    onClick = { onAction("remove") }
                )
            }
        }
    }
}

/**
 * Frosted-glass circular icon button. Reuses the swipe-pane accent treatment
 * (tinted bg + border + icon) so long-press and left-swipe feel like one system.
 */
@Composable
internal fun QuickActionCircleV2(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = accent.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.42f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaGridTileV2(
    serverUrl: String,
    token: String,
    item: MediaItem,
    controller: SwipeRevealController,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onQuickAction: (String) -> Unit
) {
    val accent = typeAccent(item.mediaType)
    val progress = progressFraction(item)
    val tileKey = "grid-${item.id}"
    val showOverlay = controller.openKey == tileKey

    // Outer Box so the long-press overlay can match the FULL tile bounds
    // (cover + title + meta), not just the cover image.
    Box(modifier = modifier) {
        com.hemanager.mobile.ui.op.AngularPanel(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (showOverlay) controller.close()
                        else if (controller.openKey != null) controller.close()
                        else onOpen()
                    },
                    onLongClick = { controller.open(tileKey) }
                ),
            cut = 10.dp,
            background = com.hemanager.mobile.ui.theme.HeColors.Panel,
            contentPadding = PaddingValues(4.dp),
        ) {
            Column {
                Box {
                    RemoteCoverV2(
                        url = coverUrl(serverUrl, token, item),
                        label = mediaTypeLabelV2(item.mediaType),
                        accent = accent,
                        decodeWidthPx = 220,
                        decodeHeightPx = 320,
                        cutDp = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.74f)
                    )
                    // TR 6dp 黄角封口
                    com.hemanager.mobile.ui.op.YellowCornerSeal(
                        size = 6.dp,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                    if (item.favorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "收藏",
                            tint = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .size(11.dp)
                        )
                    }
                    if (progress != null) {
                        com.hemanager.mobile.ui.op.ProgressO(
                            value = progress,
                            height = 2.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    item.title,
                    color = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
                    fontFamily = com.hemanager.mobile.ui.theme.NotoSansSC,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Spacer(Modifier.height(3.dp))
                com.hemanager.mobile.ui.op.CodeChip(
                    text = fakeCode(item),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
        // Spans the whole tile (cover + text). Pointer-passthrough when invisible
        // so normal taps and long-presses on the surface beneath still work.
        GridQuickActionsOverlayV2(
            item = item,
            visible = showOverlay,
            onAction = onQuickAction,
            onDismiss = { controller.close() },
            modifier = Modifier.matchParentSize()
        )
    }
}
