package com.hemanager.mobile.feature.library

// Library 侧边抽屉相关 composable 集群。
// 包括 AppDrawerV2（顶层抽屉容器）+ 7 个子组件（profile/server/stats/cells/nav rows）。
// 全部 internal — 跨同包文件可见。同包顶层 helper（filterAccent 等）自动可见无需 import。

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
internal fun AppDrawerV2(
    serverUrl: String,
    items: List<MediaItem>,
    filters: List<FilterOption>,
    selectedValue: String,
    selectedStatusValue: String,
    loading: Boolean,
    onSelected: (String) -> Unit,
    onStatusSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onOpenCreators: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = com.hemanager.mobile.ui.theme.HeColors.Void,
        drawerContentColor = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(com.hemanager.mobile.ui.theme.HeColors.Void)) {
            // 顶部 3dp 黄色 strip（左侧 35% 长度）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        Brush.horizontalGradient(
                            0f     to com.hemanager.mobile.ui.theme.HeColors.Yellow,
                            0.35f  to com.hemanager.mobile.ui.theme.HeColors.Yellow,
                            0.351f to Color.Transparent,
                            1f     to Color.Transparent,
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 18.dp, end = 17.dp, top = 24.dp, bottom = 18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 1. Slash 标题
                    com.hemanager.mobile.ui.op.Slash(cn = "操作员面板", en = "Operator")

                    // 2. 操作员档案 — 头像 + ADMIN + LVL + UID
                    DrawerProfileHeaderV2()

                    // 3. 服务连接
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.hemanager.mobile.ui.op.Slash(cn = "服务连接", en = "Server Link", fontSize = 10.5.sp)
                        DrawerServerCardV2(
                            serverUrl = serverUrl,
                            online = true,
                        )
                    }

                    // 4. 导航
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.hemanager.mobile.ui.op.Slash(cn = "导航", en = "Navigate", fontSize = 10.5.sp)
                        Spacer(Modifier.height(6.dp))
                        DNavRow(
                            cn = "媒体库", en = "Library",
                            icon = Icons.Default.Home,
                            active = selectedValue.isBlank() && selectedStatusValue.isBlank(),
                            onClick = {
                                onSelected("")
                                onStatusSelected("")
                            }
                        )
                        DNavRow(
                            cn = "继续观看", en = "Continue",
                            icon = Icons.Default.History,
                            active = selectedStatusValue == "viewing",
                            count = items.count { it.viewStatus == "viewing" },
                            onClick = { onStatusSelected("viewing") }
                        )
                        DNavRow(
                            cn = "收藏夹", en = "Starred",
                            icon = Icons.Default.StarBorder,
                            active = selectedStatusValue == "favorite",
                            count = items.count { it.favorite },
                            onClick = { onStatusSelected("favorite") }
                        )
                        DNavRow(
                            cn = "创作者", en = "Curators",
                            icon = Icons.Default.LocalOffer,
                            active = false,
                            onClick = onOpenCreators
                        )
                        DNavRow(
                            cn = "文件夹", en = "Folders",
                            icon = Icons.Default.Folder,
                            active = false,
                            onClick = { context.toastComingSoon("文件夹视图") }
                        )
                        DNavRow(
                            cn = "设置", en = "Settings",
                            icon = Icons.Default.Settings,
                            active = false,
                            onClick = onSettings
                        )
                    }

                    // 5. 媒体分类
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.hemanager.mobile.ui.op.Slash(cn = "媒体分类", en = "Library", fontSize = 10.5.sp)
                        Spacer(Modifier.height(6.dp))
                        filters.forEach { option ->
                            DNavRow(
                                cn = option.label,
                                en = categoryEnLabel(option.value),
                                icon = categoryIcon(option.value),
                                active = selectedValue == option.value && selectedStatusValue.isBlank(),
                                count = countForFilter(items, option.value),
                                onClick = { onSelected(option.value) }
                            )
                        }
                    }
                }

                // 底部分隔 + 刷新 + DISCONNECT
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(com.hemanager.mobile.ui.theme.HeColors.HairlineMid)
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.hemanager.mobile.ui.op.IconBtn4(
                        icon = Icons.Default.Refresh,
                        contentDescription = if (loading) "正在刷新" else "刷新媒体库",
                        onClick = onRefresh,
                        tint = if (loading) com.hemanager.mobile.ui.theme.HeColors.OpWhiteMuted
                               else com.hemanager.mobile.ui.theme.HeColors.Yellow,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(com.hemanager.mobile.ui.theme.CutCornerShape(8.dp))
                            .background(Color.Transparent)
                            .border(
                                1.dp,
                                com.hemanager.mobile.ui.theme.HeColors.OpDanger.copy(alpha = 0.5f),
                                com.hemanager.mobile.ui.theme.CutCornerShape(8.dp)
                            )
                            .clickable(onClick = onLogout)
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = null,
                                tint = com.hemanager.mobile.ui.theme.HeColors.OpDanger,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "DISCONNECT",
                                color = com.hemanager.mobile.ui.theme.HeColors.OpDanger,
                                fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 1.8.sp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "退出登录",
                                color = com.hemanager.mobile.ui.theme.HeColors.OpDanger.copy(alpha = 0.85f),
                                fontFamily = com.hemanager.mobile.ui.theme.NotoSansSC,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryEnLabel(value: String): String = when (value) {
    "" -> "All"
    "video" -> "Video"
    "manga" -> "Manga"
    "image" -> "Images"
    "audio" -> "Audio"
    else -> ""
}

private fun categoryIcon(value: String): androidx.compose.ui.graphics.vector.ImageVector = when (value) {
    "video" -> Icons.Default.PlayArrow
    "manga" -> Icons.Default.Folder
    "image" -> Icons.Default.GridView
    "audio" -> Icons.Default.History
    else -> Icons.Default.Home
}

@Composable
internal fun DrawerProfileHeaderV2() {
    // HE OP 操作员档案：切角头像（黄环）+ ADMIN/ARCHIVIST + UID
    // 头像 modelUrl=null 时 OpAvatar 显示空 Panel 底色，再叠一个"HE"占位
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(56.dp)) {
            com.hemanager.mobile.ui.op.OpAvatar(
                modelUrl = null,
                size = 56.dp,
                ring = true,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Text(
                    "HE",
                    color = com.hemanager.mobile.ui.theme.HeColors.Yellow,
                    fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 1.5.sp,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "ADMIN",
                color = com.hemanager.mobile.ui.theme.HeColors.OpWhite,
                fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "ARCHIVIST · LVL 14",
                color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteSoft,
                fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(4.dp))
            com.hemanager.mobile.ui.op.CodeChip(
                text = "UID:1416-176-661",
                color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteFaint,
            )
        }
    }
}

@Composable
internal fun DrawerServerCardV2(serverUrl: String, online: Boolean = true) {
    // HE OP 服务连接卡：小切角面板 + 6dp 绿圆点 + ● ONLINE + serverHost CodeChip
    com.hemanager.mobile.ui.op.AngularPanel(
        modifier = Modifier.fillMaxWidth(),
        cut = 8.dp,
        background = com.hemanager.mobile.ui.theme.HeColors.Panel,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (online) com.hemanager.mobile.ui.theme.HeColors.Online
                        else com.hemanager.mobile.ui.theme.HeColors.OpDanger
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (online) "● ONLINE" else "● OFFLINE",
                color = if (online) com.hemanager.mobile.ui.theme.HeColors.Online
                        else com.hemanager.mobile.ui.theme.HeColors.OpDanger,
                fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.weight(1f))
            com.hemanager.mobile.ui.op.CodeChip(
                text = serverHostV2(serverUrl),
                color = com.hemanager.mobile.ui.theme.HeColors.OpWhiteSoft,
            )
        }
    }
}

/**
 * HE OP DNavO — Drawer 双层中英导航行。
 *
 * Active 时：左侧 2dp 黄竖条（内缩 8dp）+ Surface 背景 + 切角 TR/BL。
 * 默认时：透明、无切角。Icon (16dp) → 双层 EN/CN → 右侧 GeistMono count。
 */
@Composable
private fun DNavRow(
    cn: String,
    en: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    count: Int? = null,
) {
    val shape = com.hemanager.mobile.ui.theme.CutCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.clip(shape).background(com.hemanager.mobile.ui.theme.HeColors.OpSurface) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧 active 黄竖条（用 Box 占位，相对 Row 内插）
        if (active) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(20.dp)
                    .background(com.hemanager.mobile.ui.theme.HeColors.Yellow)
            )
            Spacer(Modifier.width(10.dp))
        }
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) com.hemanager.mobile.ui.theme.HeColors.Yellow
                   else com.hemanager.mobile.ui.theme.HeColors.OpWhiteSoft,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (en.isNotEmpty()) {
                Text(
                    en,
                    color = if (active) com.hemanager.mobile.ui.theme.HeColors.Yellow
                            else com.hemanager.mobile.ui.theme.HeColors.OpWhiteMuted,
                    fontFamily = com.hemanager.mobile.ui.theme.Oxanium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    letterSpacing = 1.6.sp,
                )
                Spacer(Modifier.height(1.dp))
            }
            Text(
                cn,
                color = if (active) com.hemanager.mobile.ui.theme.HeColors.OpWhite
                        else com.hemanager.mobile.ui.theme.HeColors.OpWhiteSoft,
                fontFamily = com.hemanager.mobile.ui.theme.NotoSansSC,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
            )
        }
        if (count != null) {
            Text(
                count.toString().padStart(2, '0'),
                color = if (active) com.hemanager.mobile.ui.theme.HeColors.Yellow
                        else com.hemanager.mobile.ui.theme.HeColors.OpWhiteMuted,
                fontFamily = com.hemanager.mobile.ui.theme.GeistMono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

