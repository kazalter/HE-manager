package com.hemanager.mobile.feature.library

// Library 模块复用的小型 composable 集合：badge / pill / chip / placeholder / loading skeleton / 玻璃面板等。
// 命名以 V2 结尾的是 V2 重写版本，对应已删除的 V1。
// 同时包含 BackToTopButtonV2, MediaDetailRowV2 等中等大小的复用组件。

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
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
internal fun BackToTopButtonV2(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "backToTopScale"
    )

    Box(
        modifier = modifier
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(com.hemanager.mobile.ui.theme.HeColors.Ink)
            .border(1.dp, com.hemanager.mobile.ui.theme.HeColors.HairlineHi, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = "Back to top",
            tint = com.hemanager.mobile.ui.theme.HeColors.Yellow,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun MediaDetailRowV2(
    serverUrl: String,
    token: String,
    item: MediaItem,
    controller: SwipeRevealController,
    onOpen: (Boolean) -> Unit,
    onQuickAction: (String) -> Unit
) {
    val progress = progressFraction(item)
    val accent = typeAccent(item.mediaType)
    SwipeRevealCard(
        itemKey = "detail-${item.id}",
        controller = controller,
        shape = com.hemanager.mobile.ui.theme.CutCornerShape(8.dp),
        actionsWidth = 156.dp,
        actions = { progress ->
            QuickActionsPaneV2(
                item = item,
                onAction = { action -> onQuickAction(action) },
                progress = progress
            )
        }
    ) {
        com.hemanager.mobile.ui.op.AngularPanel(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = {
                    if (controller.openKey != null) controller.close() else onOpen(false)
                }),
            cut = 10.dp,
            background = com.hemanager.mobile.ui.theme.HeColors.Panel,
            contentPadding = PaddingValues(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier
                    .width(84.dp)
                    .height(116.dp)
                ) {
                    RemoteCoverV2(
                        url = coverUrl(serverUrl, token, item),
                        label = mediaTypeLabelV2(item.mediaType),
                        accent = accent,
                        decodeWidthPx = 220,
                        decodeHeightPx = 308,
                        cutDp = 7.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    // TR 7dp 黄角封口
                    com.hemanager.mobile.ui.op.YellowCornerSeal(
                        size = 7.dp,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.hemanager.mobile.ui.op.CodeChip(
                            text = fakeCode(item),
                            color = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                        )
                        if (item.favorite) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "收藏",
                                tint = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        if (item.missing) {
                            TinyBadgeV2("MISSING", com.hemanager.mobile.ui.theme.HeColors.OpDanger)
                        }
                    }
                    Text(
                        item.title,
                        color = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
                        fontFamily = com.hemanager.mobile.ui.theme.NotoSansSC,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (progress != null) {
                        com.hemanager.mobile.ui.op.ProgressO(
                            value = progress,
                            height = 2.dp,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            progressTextV2(item),
                            modifier = Modifier.weight(1f),
                            color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteMuted,
                            fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
                            fontSize = 10.sp,
                            letterSpacing = 0.3.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.mediaType == "manga" && (item.viewStatus == "viewing" || item.viewStatus == "viewed")) {
                            ActionPillV2("RESTART", com.hemanager.mobile.ui.theme.HeColors.OpWhite) {
                                if (controller.openKey != null) controller.close() else onOpen(true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TinyBadgeV2(label: String, color: Color, modifier: Modifier = Modifier) {
    // HE OP — 小切角黑底，文字色不强（用传入色但 alpha 降一档）
    Box(
        modifier = modifier
            .clip(com.hemanager.mobile.ui.theme.CutCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            color = color,
            fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun RemoteCoverV2(
    url: String?,
    label: String,
    accent: Color,
    decodeWidthPx: Int = 240,
    decodeHeightPx: Int = 340,
    modifier: Modifier = Modifier,
    cutDp: Dp = 8.dp,
) {
    val decodeWidth = remember(decodeWidthPx) { coverDecodeBucketPx(decodeWidthPx) }
    val decodeHeight = remember(decodeHeightPx) { coverDecodeBucketPx(decodeHeightPx) }
    val shape = com.hemanager.mobile.ui.theme.CutCornerShape(cutDp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(com.hemanager.mobile.ui.theme.HeColors.Panel),
        contentAlignment = Alignment.Center
    ) {
        // 占位文字（图未加载时显示）
        Text(
            label,
            color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteFaint,
            fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
        )
        CoilCoverImage(
            url = url,
            label = label,
            decodeWidthPx = decodeWidth,
            decodeHeightPx = decodeHeight,
            modifier = Modifier.fillMaxSize()
        )
        // 底部 ink 渐变，给文字制造对比；上半部分基本透明
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f   to Color.Transparent,
                        0.5f to Color.Transparent,
                        1f   to com.hemanager.mobile.ui.theme.HeColors.Ink.copy(alpha = 0.78f),
                    )
                )
        )
    }
}

@Composable
internal fun FloatingPlayButtonV2(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // HE OP — 黄底切角 + Oxanium Bold 大写
    val shape = com.hemanager.mobile.ui.theme.CutCornerShape(8.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(com.hemanager.mobile.ui.theme.HeColors.Yellow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = com.hemanager.mobile.ui.theme.HeColors.OnYellow,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = com.hemanager.mobile.ui.theme.HeColors.OnYellow,
            fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
internal fun EmptyStateV2() {
    com.hemanager.mobile.ui.op.AngularPanel(
        modifier = Modifier.fillMaxWidth(),
        cut = 14.dp,
        background = com.hemanager.mobile.ui.theme.HeColors.Panel,
        contentPadding = PaddingValues(vertical = 36.dp, horizontal = 18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            com.hemanager.mobile.ui.op.Slash(cn = "无匹配", en = "EMPTY")
            Spacer(Modifier.height(14.dp))
            Text(
                "没有匹配的媒体",
                color = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
                fontFamily = com.hemanager.mobile.ui.theme.NotoSansSC,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "换个关键词、类型或状态再探索一下",
                color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteMuted,
                fontFamily = com.hemanager.mobile.ui.theme.NotoSansSC,
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun ErrorPanelV2(error: String, loading: Boolean, onRetry: () -> Unit) {
    com.hemanager.mobile.ui.op.AngularPanel(
        modifier = Modifier.fillMaxWidth(),
        cut = 10.dp,
        background = com.hemanager.mobile.ui.theme.HeColors.Panel,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "// ERR",
                    color = com.hemanager.mobile.ui.theme.HeColors.OpDanger,
                    fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteSoft,
                    fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            com.hemanager.mobile.ui.op.GhostCta(
                text = "RETRY",
                onClick = onRetry,
                size = com.hemanager.mobile.ui.op.CtaSize.Small,
            )
        }
    }
}

@Composable
internal fun LoadingLineV2() {
    com.hemanager.mobile.ui.op.AngularPanel(
        modifier = Modifier.fillMaxWidth(),
        cut = 8.dp,
        background = com.hemanager.mobile.ui.theme.HeColors.Panel,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "// STREAM · 同步媒体库",
                color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteSoft,
                fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
internal fun LoadingCardSkeletonV2(index: Int) {
    val transition = rememberInfiniteTransition(label = "skeletonV2$index")
    val alpha by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(860, delayMillis = index * 90, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonV2Alpha$index"
    )
    com.hemanager.mobile.ui.op.AngularPanel(
        modifier = Modifier.fillMaxWidth(),
        cut = 14.dp,
        background = com.hemanager.mobile.ui.theme.HeColors.Panel,
        contentPadding = PaddingValues(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBlockV2(
                Modifier.fillMaxWidth().aspectRatio(1.55f),
                alpha
            )
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SkeletonBlockV2(Modifier.fillMaxWidth(0.62f).height(18.dp), alpha)
                SkeletonBlockV2(Modifier.fillMaxWidth().height(3.dp), alpha * 0.6f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlockV2(Modifier.width(54.dp).height(20.dp), alpha * 0.7f)
                    SkeletonBlockV2(Modifier.width(74.dp).height(20.dp), alpha * 0.55f)
                }
            }
        }
    }
}

@Composable
internal fun SkeletonBlockV2(modifier: Modifier, alpha: Float) {
    Box(
        modifier = modifier
            .clip(com.hemanager.mobile.ui.theme.CutCornerShape(6.dp))
            .background(com.hemanager.mobile.ui.theme.HeColors.OpWhite.copy(alpha = alpha))
    )
}

@Composable
internal fun GlassPanelV2(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.060f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f))
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.070f),
                            Color.Transparent
                        )
                    )
                )
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

@Composable
internal fun IconPillV2(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    icon: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "iconPillV2Scale"
    )
    Surface(
        modifier = Modifier
            .size(46.dp)
            .alpha(if (enabled) 1f else 0.58f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = CircleShape,
        color = Color.White.copy(alpha = if (pressed) 0.12f else 0.065f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
internal fun StatusPillV2(label: String, color: Color, modifier: Modifier = Modifier) {
    // HE OP — 用 ● 圆点 + ALL-CAPS Oxanium，不再做药丸 surface
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            color = color,
            fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            letterSpacing = 1.4.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun SubtleChipV2(label: String) {
    Text(
        label,
        color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteMuted,
        fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.4.sp,
        maxLines = 1,
    )
}

@Composable
internal fun ActionPillV2(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(com.hemanager.mobile.ui.theme.CutCornerShape(6.dp))
            .background(Color.Transparent)
            .border(1.dp, com.hemanager.mobile.ui.theme.HeColors.HairlineMid, com.hemanager.mobile.ui.theme.CutCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
            fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )
    }
}

@Composable
internal fun DrawerActionV2(
    label: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    subtle: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val containerColor = if (subtle) {
        Color.White.copy(alpha = if (pressed) 0.07f else 0.025f)
    } else {
        accent.copy(alpha = if (pressed) 0.18f else 0.095f)
    }
    val borderColor = if (subtle) {
        Color.White.copy(alpha = 0.08f)
    } else {
        accent.copy(alpha = 0.18f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.58f)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(22.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = accent,
                    strokeWidth = 2.dp
                )
                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                else -> DrawerDotV2(accent)
            }
            Spacer(Modifier.width(11.dp))
            Text(label, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun DrawerSectionTitleV2(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
internal fun DrawerDividerV2() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.085f))
    )
}

@Composable
internal fun DrawerDotV2(color: Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
internal fun AppBackgroundBrushV2(): Brush {
    // HE OP — 顶部极淡 amber bloom 渐变到 Ink，模拟工业终端的环境光感
    return Brush.radialGradient(
        colorStops = arrayOf(
            0f   to com.hemanager.mobile.ui.theme.HeColors.Yellow.copy(alpha = 0.024f),
            0.4f to com.hemanager.mobile.ui.theme.HeColors.Ink,
            1f   to com.hemanager.mobile.ui.theme.HeColors.Void
        ),
        radius = 1400f
    )
}
