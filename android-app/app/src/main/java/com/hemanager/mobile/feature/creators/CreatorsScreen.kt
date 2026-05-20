package com.hemanager.mobile.feature.creators

// 创作者聚合页：把 X 作者（kind="x"）和漫画作者（kind="artist"）作为统一的
// "创作者" 列表展示，点进去看该创作者的所有作品。
//
// 状态保存策略：用 rememberSaveable 持久化"是否在 creators 页"和"选中的 creator key"，
// 让从重型 Activity（视频/漫画/音频播放器）返回时不会丢失状态（之前老版本会
// 跳回 library 根目录，再按返回直接退 App）。
//
// 状态栏：进入本屏幕时隐藏（沉浸感 + 让 vertical 屏更聚焦），离开时恢复。通过
// MainActivity 上的 creatorsScreenActive / setStatusBarHidden 协调，DisposableEffect
// 确保挂载/卸载干净。

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.Creator
import com.hemanager.mobile.CreatorDetail
import com.hemanager.mobile.MainActivity
import com.hemanager.mobile.MediaItem
import com.hemanager.mobile.feature.library.AppBackgroundBrushV2
import com.hemanager.mobile.feature.library.FilterOption
import com.hemanager.mobile.feature.library.coverUrl
import com.hemanager.mobile.feature.library.creatorThumbUrl
import com.hemanager.mobile.feature.library.openItem
import com.hemanager.mobile.feature.library.readableError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CreatorsScreen(
    serverUrl: String,
    token: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val mainActivity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val typeFilters = remember {
        listOf(
            FilterOption("全部", "all"),
            FilterOption("漫画", "manga"),
            FilterOption("图片", "image"),
            FilterOption("视频", "video"),
            FilterOption("音频", "audio"),
        )
    }
    var typeFilter by rememberSaveable { mutableStateOf("all") }
    var creators by remember { mutableStateOf<List<Creator>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var listRequest by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Creator?>(null) }
    // 只持久化 creator key；Creator / CreatorDetail 不存 Parcelable，重建时再去 fetch。
    // 这是"创作者 → 作品 → 返回"能回到创作者列表（而不是掉到 library 根、再一次返回退 App）
    // 的关键。
    var selectedCreatorKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<CreatorDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }

    fun loadList() {
        val rid = listRequest + 1
        listRequest = rid
        loading = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ApiClient(serverUrl, token).getCreators(typeFilter, "", "count") }
            }
            if (listRequest != rid) return@launch
            loading = false
            r.onSuccess { creators = it }
            r.onFailure { error = readableError(it) }
        }
    }

    fun openCreator(c: Creator) {
        selected = c
        selectedCreatorKey = c.key
        detail = null
        detailLoading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ApiClient(serverUrl, token).getCreatorDetail(c.key) }
            }
            if (selected?.key != c.key) return@launch
            detailLoading = false
            r.onSuccess { detail = it }
            r.onFailure {
                Toast.makeText(context, readableError(it), Toast.LENGTH_LONG).show()
            }
        }
    }

    // 隐藏状态栏：本屏幕独享沉浸感，离开时立即恢复。creatorsScreenActive 标志让
    // MainActivity.onWindowFocusChanged 在 focus 重新拿到时也能按当前页面状态正确重置。
    DisposableEffect(Unit) {
        mainActivity?.creatorsScreenActive = true
        mainActivity?.setStatusBarHidden(true)
        onDispose {
            mainActivity?.creatorsScreenActive = false
            mainActivity?.setStatusBarHidden(false)
        }
    }

    LaunchedEffect(typeFilter) { loadList() }
    // Activity 重建后恢复打开的创作者：列表（重新）加载完成后，如果有持久化的 key 但没
    // selected 实例，自动重新 open。
    LaunchedEffect(creators, selectedCreatorKey) {
        if (selected == null && selectedCreatorKey != null) {
            creators.firstOrNull { it.key == selectedCreatorKey }?.let { openCreator(it) }
        }
    }
    BackHandler {
        if (selected != null) {
            selected = null
            selectedCreatorKey = null
            detail = null
        } else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundBrushV2())
    ) {
        if (selected == null) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        "创作者",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${creators.size} 位",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    typeFilters.forEach { opt ->
                        val sel = typeFilter == opt.value
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (sel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            contentColor = if (sel) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.clickable { typeFilter = opt.value }
                        ) {
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                when {
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    }
                    creators.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            "还没有可聚合的创作者",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        gridItems(creators, key = { it.key }) { c ->
                            CreatorCard(
                                creator = c,
                                coverUrl = creatorThumbUrl(serverUrl, token, c.coverPath),
                                onClick = { openCreator(c) }
                            )
                        }
                    }
                }
            }
        } else {
            // 拷成本地 non-null val。LazyVerticalGrid 的 item provider 是 lazy 的，Compose 在
            // snapshot-apply 时还会再求值；如果在那里读 selected!! / detail!!，刚把
            // selected 置 null 的瞬间会 NPE（这就是老版本"返回退出 App"的真实崩溃点）。
            // 闭包捕获稳定的本地变量则不会崩。
            val c = selected
            val d = detail
            if (c != null) Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        selected = null
                        selectedCreatorKey = null
                        detail = null
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            c.label(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (c.kind == "x" && c.screenName.isNotEmpty())
                                "@${c.screenName} · ${c.mediaCount} 件作品"
                            else "漫画作者 · ${c.mediaCount} 件作品",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                when {
                    detailLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    d == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            "加载创作者作品失败",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        gridItems(d.media, key = { it.id }) { m ->
                            CreatorMediaTile(
                                item = m,
                                coverUrl = coverUrl(serverUrl, token, m),
                                onClick = { openItem(context, m, serverUrl, token, false, d.media) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorCard(
    creator: Creator,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = creator.label(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.LocalOffer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.Center)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            creator.label(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (creator.kind == "x" && creator.screenName.isNotEmpty())
                "@${creator.screenName} · ${creator.mediaCount}"
            else "漫画 · ${creator.mediaCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CreatorMediaTile(
    item: MediaItem,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (item.mediaType == "video") {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(34.dp)
                        .align(Alignment.Center)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
