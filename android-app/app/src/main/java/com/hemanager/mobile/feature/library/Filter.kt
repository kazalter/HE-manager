package com.hemanager.mobile.feature.library

// Library 顶部 header + 搜索过滤面板 + 底部 sheet + 视图模式切换。
// 包括 LibraryHeaderV2, SearchAndFilterPanelV2, FilterBottomSheetV2,
// LibraryViewModeSwitcherV2 以及它们的子组件（QuickFilterRowV2/ChipV2/PillTabV2 等）。

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
internal fun LibraryHeaderV2(
    loading: Boolean,
    viewMode: LibraryViewMode,
    galleryMode: Boolean = false,
    onViewModeSelected: (LibraryViewMode) -> Unit,
    onMenu: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconPillV2(onClick = onMenu, contentDescription = "打开侧边栏") {
            Icon(Icons.Default.Menu, contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "媒体库",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "继续探索你的媒体内容",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!galleryMode) {
            ViewModeIconButtonV2(viewMode = viewMode, onSelect = onViewModeSelected)
            Spacer(Modifier.width(8.dp))
        }
        IconPillV2(onClick = onRefresh, enabled = !loading, contentDescription = "刷新") {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
        }
    }
}

@Composable
internal fun ViewModeIconButtonV2(
    viewMode: LibraryViewMode,
    onSelect: (LibraryViewMode) -> Unit
) {
    val ordered = listOf(LibraryViewMode.Large, LibraryViewMode.Grid, LibraryViewMode.Detail)
    val currentIndex = ordered.indexOf(viewMode).coerceAtLeast(0)
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "viewModeBtnScale"
    )
    Box {
        Surface(
            modifier = Modifier
                .size(46.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(viewMode) {
                    detectTapGestures(
                        onTap = { onSelect(ordered[(currentIndex + 1) % ordered.size]) },
                        onLongPress = { menuOpen = true }
                    )
                },
            shape = CircleShape,
            color = Color.White.copy(alpha = if (pressed) 0.12f else 0.065f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = viewMode,
                    transitionSpec = {
                        (slideInHorizontally(tween(160)) { it / 2 } + fadeIn(tween(160))) togetherWith
                            (slideOutHorizontally(tween(160)) { -it / 2 } + fadeOut(tween(160))) using
                            SizeTransform(clip = false)
                    },
                    label = "viewModeIcon"
                ) { mode ->
                    Icon(
                        viewModeIconV2(mode),
                        contentDescription = "切换视图：${viewModeLabelV2(mode)}",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            ordered.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(viewModeLabelV2(mode), fontWeight = if (mode == viewMode) FontWeight.Black else FontWeight.SemiBold) },
                    leadingIcon = {
                        Icon(
                            viewModeIconV2(mode),
                            contentDescription = null,
                            tint = if (mode == viewMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(mode)
                    }
                )
            }
        }
    }
}

internal fun viewModeIconV2(mode: LibraryViewMode) = when (mode) {
    LibraryViewMode.Large -> Icons.Default.ViewModule
    LibraryViewMode.Grid -> Icons.Default.GridView
    LibraryViewMode.Detail -> Icons.AutoMirrored.Filled.ViewList
}

internal fun viewModeLabelV2(mode: LibraryViewMode) = when (mode) {
    LibraryViewMode.Large -> "大图"
    LibraryViewMode.Grid -> "三列"
    LibraryViewMode.Detail -> "详细"
}

@Composable
internal fun SearchAndFilterPanelV2(
    search: String,
    onSearchChange: (String) -> Unit,
    filters: List<FilterOption>,
    selectedValue: String,
    onSelected: (String) -> Unit,
    statusFilters: List<FilterOption>,
    selectedStatusValue: String,
    onStatusSelected: (String) -> Unit,
    onOpenFilters: () -> Unit
) {
    val selectedMediaLabel = filters.firstOrNull { it.value == selectedValue }?.label ?: "全部"
    val selectedStatusLabel = statusFilters.firstOrNull { it.value == selectedStatusValue }?.label ?: "全部"
    val activeCount = listOf(selectedValue, selectedStatusValue).count { it.isNotBlank() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            placeholder = {
                Text(
                    "搜索标题、作者、文件夹",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                AnimatedVisibility(visible = search.isNotBlank()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索", modifier = Modifier.size(18.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.76f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                focusedContainerColor = Color.White.copy(alpha = 0.080f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.052f),
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )

        QuickFilterRowV2(
            mediaValue = selectedValue,
            statusValue = selectedStatusValue,
            onMediaSelected = onSelected,
            onStatusSelected = onStatusSelected
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenFilters() },
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.052f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.085f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "筛选",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "$selectedMediaLabel · $selectedStatusLabel",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (activeCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f))
                    ) {
                        Text(
                            activeCount.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    "展开",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
internal fun QuickFilterRowV2(
    mediaValue: String,
    statusValue: String,
    onMediaSelected: (String) -> Unit,
    onStatusSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickFilterChipV2(
            label = "全部",
            selected = mediaValue.isBlank() && statusValue.isBlank(),
            accent = MaterialTheme.colorScheme.primary,
            onClick = {
                onMediaSelected("")
                onStatusSelected("")
            }
        )
        listOf("video" to "视频", "manga" to "漫画", "image" to "图片").forEach { (value, label) ->
            QuickFilterChipV2(
                label = label,
                selected = mediaValue == value,
                accent = filterAccent(value),
                onClick = { onMediaSelected(if (mediaValue == value) "" else value) }
            )
        }
        listOf("viewing" to "继续看", "favorite" to "收藏").forEach { (value, label) ->
            QuickFilterChipV2(
                label = label,
                selected = statusValue == value,
                accent = statusAccentV2(value),
                onClick = { onStatusSelected(if (statusValue == value) "" else value) }
            )
        }
    }
}

@Composable
internal fun QuickFilterChipV2(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.045f),
        animationSpec = tween(160),
        label = "quickChipBg"
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(160),
        label = "quickChipFg"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "quickChipScale"
    )
    Surface(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = background,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.075f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterBottomSheetV2(
    filters: List<FilterOption>,
    selectedValue: String,
    onSelected: (String) -> Unit,
    statusFilters: List<FilterOption>,
    selectedStatusValue: String,
    onStatusSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xEF0B0E16),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "筛选",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerSectionTitleV2("媒体类型")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    filters.forEach { option ->
                        PillTabV2(
                            label = option.label,
                            selected = selectedValue == option.value,
                            accent = filterAccent(option.value),
                            onClick = { onSelected(option.value) }
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DrawerSectionTitleV2("观看状态")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    statusFilters.forEach { option ->
                        PillTabV2(
                            label = option.label,
                            selected = selectedStatusValue == option.value,
                            accent = statusAccentV2(option.value),
                            onClick = { onStatusSelected(option.value) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PillTabV2(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.052f),
        animationSpec = tween(180),
        label = "pillTabBg"
    )
    val foreground by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "pillTabFg"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "pillTabScale"
    )
    Surface(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = background,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.075f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 9.dp),
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun LibraryViewModeSwitcherV2(
    selectedMode: LibraryViewMode,
    onModeSelected: (LibraryViewMode) -> Unit
) {
    val options = listOf(
        LibraryViewMode.Large to "大图",
        LibraryViewMode.Grid to "三列",
        LibraryViewMode.Detail to "详细"
    )
    val accent = MaterialTheme.colorScheme.primary
    val selectedIndex = options.indexOfFirst { it.first == selectedMode }.coerceAtLeast(0)
    val density = LocalDensity.current

    var dragSegments by remember { mutableStateOf(0f) }
    var trackWidthPx by remember { mutableStateOf(0) }
    val segmentWidth = if (trackWidthPx > 0) trackWidthPx.toFloat() / options.size else 0f
    val displayIndex = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex, dragSegments) {
        if (dragSegments != 0f) {
            displayIndex.snapTo((selectedIndex + dragSegments).coerceIn(0f, (options.size - 1).toFloat()))
        } else {
            displayIndex.animateTo(
                selectedIndex.toFloat(),
                spring(stiffness = 360f, dampingRatio = 0.82f)
            )
        }
    }

    GlassPanelV2(contentPadding = PaddingValues(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                "显示方式",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.045f))
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                        RoundedCornerShape(18.dp)
                    )
                    .onSizeChanged { trackWidthPx = it.width }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            if (segmentWidth <= 0f) return@rememberDraggableState
                            val newDrag = dragSegments + delta / segmentWidth
                            dragSegments = newDrag.coerceIn(
                                -selectedIndex.toFloat(),
                                (options.size - 1 - selectedIndex).toFloat()
                            )
                        },
                        onDragStopped = {
                            val targetIndex =
                                (selectedIndex + dragSegments).roundToInt()
                                    .coerceIn(0, options.size - 1)
                            dragSegments = 0f
                            if (targetIndex != selectedIndex) {
                                onModeSelected(options[targetIndex].first)
                            }
                        }
                    )
            ) {
                if (segmentWidth > 0f) {
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset((displayIndex.value * segmentWidth).toInt(), 0)
                            }
                            .width(with(density) { segmentWidth.toDp() })
                            .fillMaxHeight()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(accent.copy(alpha = 0.22f))
                            .border(
                                BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
                                RoundedCornerShape(15.dp)
                            )
                    )
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    options.forEachIndexed { idx, (mode, label) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onModeSelected(mode) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (idx == selectedIndex) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Black,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ViewModeButtonV2(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.045f),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.070f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 10.dp),
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}
