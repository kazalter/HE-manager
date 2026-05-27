package com.hemanager.mobile.feature.creators

// HE OP 创作者聚合屏：把 X 作者（kind="x"）和漫画作者（kind="artist"）作为统一的
// "创作者" 列表展示，点进去看该创作者的所有作品。
//
// 状态保存策略：用 rememberSaveable 持久化"是否在 creators 页"和"选中的 creator key"，
// 让从重型 Activity（视频/漫画/音频播放器）返回时不会丢失状态。
//
// 状态栏：进入本屏幕时隐藏；离开时恢复。

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.Creator
import com.hemanager.mobile.CreatorDetail
import com.hemanager.mobile.MainActivity
import com.hemanager.mobile.MediaItem
import com.hemanager.mobile.feature.library.FilterOption
import com.hemanager.mobile.feature.library.coverUrl
import com.hemanager.mobile.feature.library.creatorThumbUrl
import com.hemanager.mobile.feature.library.openItem
import com.hemanager.mobile.feature.library.readableError
import com.hemanager.mobile.ui.op.AngularPanel
import com.hemanager.mobile.ui.op.CodeChip
import com.hemanager.mobile.ui.op.CtaSize
import com.hemanager.mobile.ui.op.Diamond
import com.hemanager.mobile.ui.op.GhostCta
import com.hemanager.mobile.ui.op.IconBtn4
import com.hemanager.mobile.ui.op.OpAvatar
import com.hemanager.mobile.ui.op.OpTitle
import com.hemanager.mobile.ui.op.Slash
import com.hemanager.mobile.ui.op.StatNumber
import com.hemanager.mobile.ui.op.StatusStripe
import com.hemanager.mobile.ui.op.YellowCTA
import com.hemanager.mobile.ui.op.YellowCornerSeal
import com.hemanager.mobile.ui.theme.CutCornerShape
import com.hemanager.mobile.ui.theme.GeistMono
import com.hemanager.mobile.ui.theme.HeColors
import com.hemanager.mobile.ui.theme.NotoSansSC
import com.hemanager.mobile.ui.theme.Oxanium
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
            FilterOption("全部 ALL", "all"),
            FilterOption("X 来源 X-SRC", "x"),
            FilterOption("漫画作者 ARTIST", "artist"),
        )
    }
    var typeFilter by rememberSaveable { mutableStateOf("all") }
    var creators by remember { mutableStateOf<List<Creator>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var listRequest by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Creator?>(null) }
    // 只持久化 creator key；Creator / CreatorDetail 不存 Parcelable，重建时再去 fetch。
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

    // 隐藏状态栏：本屏幕独享沉浸感
    DisposableEffect(Unit) {
        mainActivity?.creatorsScreenActive = true
        mainActivity?.setStatusBarHidden(true)
        onDispose {
            mainActivity?.creatorsScreenActive = false
            mainActivity?.setStatusBarHidden(false)
        }
    }

    LaunchedEffect(typeFilter) { loadList() }
    // Activity 重建后恢复打开的创作者
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
            .background(HeColors.Ink)
    ) {
        if (selected == null) {
            CreatorsListView(
                serverUrl = serverUrl,
                token = token,
                creators = creators,
                loading = loading,
                error = error,
                typeFilter = typeFilter,
                typeFilters = typeFilters,
                onTypeFilterChange = { typeFilter = it },
                onBack = onBack,
                onCreatorClick = ::openCreator,
            )
        } else {
            val c = selected
            val d = detail
            if (c != null) {
                CreatorDetailView(
                    serverUrl = serverUrl,
                    token = token,
                    creator = c,
                    detail = d,
                    detailLoading = detailLoading,
                    onBack = {
                        selected = null
                        selectedCreatorKey = null
                        detail = null
                    },
                    onMediaClick = { m ->
                        openItem(context, m, serverUrl, token, false, d?.media ?: emptyList())
                    },
                )
            }
        }
    }
}

// ============================================================================
// 列表视图
// ============================================================================

@Composable
private fun CreatorsListView(
    serverUrl: String,
    token: String,
    creators: List<Creator>,
    loading: Boolean,
    error: String?,
    typeFilter: String,
    typeFilters: List<FilterOption>,
    onTypeFilterChange: (String) -> Unit,
    onBack: () -> Unit,
    onCreatorClick: (Creator) -> Unit,
) {
    val total = creators.size
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn4(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.weight(1f))
            StatNumber(
                value = total,
                total = null,
                label = "CURATORS",
            )
        }

        // Title 区
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Slash(cn = "聚合视图", en = "Curators")
            Spacer(Modifier.height(14.dp))
            OpTitle(
                cn = "创作者",
                en = "CURATORS · OPERATOR DOSSIER",
                sizeSp = 36.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "聚合所有 X 来源作者与漫画作者，\n按作品数排序。点击进入档案。",
                color = HeColors.OpWhiteSoft,
                fontFamily = NotoSansSC,
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(14.dp))
            // 32×3dp 黄短线
            Box(
                Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .background(HeColors.Yellow)
            )
        }

        // FilterTab row
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            typeFilters.forEach { opt ->
                val parts = opt.label.split(" ")
                val cn = parts.getOrNull(0) ?: opt.label
                val en = parts.getOrNull(1)
                com.hemanager.mobile.ui.op.FilterTab(
                    label = cn,
                    en = en,
                    active = typeFilter == opt.value,
                    onClick = { onTypeFilterChange(opt.value) },
                )
            }
        }

        // 内容区
        Spacer(Modifier.height(14.dp))
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                CircularProgressIndicator(
                    color = HeColors.Yellow,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(28.dp),
                )
            }
            error != null -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                Text(error, color = HeColors.OpDanger, fontFamily = GeistMono, fontSize = 11.sp)
            }
            creators.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                Text(
                    "// 暂无可聚合的创作者",
                    color = HeColors.OpWhiteMuted,
                    fontFamily = GeistMono,
                    fontSize = 11.sp,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                gridItems(creators, key = { it.key }) { c ->
                    CreatorDossier(
                        creator = c,
                        index = creators.indexOf(c) + 1,
                        coverUrl = creatorThumbUrl(serverUrl, token, c.coverPath),
                        onClick = { onCreatorClick(c) }
                    )
                }
            }
        }

        StatusStripe(server = com.hemanager.mobile.feature.library.serverHostV2(serverUrl))
    }
}

/** Dossier 档案卡：肖像 5:7 + TR 黄角 + OP-XXX + Rank Diamonds + name + handle */
@Composable
private fun CreatorDossier(
    creator: Creator,
    index: Int,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    // Rank 0..6 — 仅装饰，依 mediaCount 分档
    val rank = when {
        creator.mediaCount >= 200 -> 6
        creator.mediaCount >= 100 -> 5
        creator.mediaCount >= 50  -> 4
        creator.mediaCount >= 20  -> 3
        creator.mediaCount >= 10  -> 2
        creator.mediaCount >= 1   -> 1
        else -> 0
    }
    val srcLabel = if (creator.kind == "x") "X-SRC" else "ARTIST"
    val opCode = "OP-${index.toString().padStart(3, '0')}"

    AngularPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cut = 10.dp,
        background = HeColors.Panel,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            // 肖像
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(5f / 7f)
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = creator.label(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(HeColors.Panel),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            creator.label().take(2).uppercase(),
                            color = HeColors.OpWhiteFaint,
                            fontFamily = Oxanium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            letterSpacing = 2.sp,
                        )
                    }
                }
                // 底部 ink 渐变
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f   to Color.Transparent,
                                0.6f to Color.Transparent,
                                1f   to HeColors.Ink.copy(alpha = 0.92f),
                            )
                        )
                )
                // 左上 OP-001
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CutCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        opCode,
                        color = HeColors.Yellow,
                        fontFamily = GeistMono,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                    )
                }
                // TR 黄角
                YellowCornerSeal(
                    size = 10.dp,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                // 右上 Rank Diamonds (overlay slightly below corner seal)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 14.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    repeat(6) { i ->
                        Diamond(
                            size = 4.dp,
                            filled = i < rank,
                            color = HeColors.Yellow,
                        )
                    }
                }
                // 肖像底部信息
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "● $srcLabel",
                        color = HeColors.Yellow,
                        fontFamily = Oxanium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.2.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "×${creator.mediaCount}",
                        color = HeColors.OpWhite,
                        fontFamily = GeistMono,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                    )
                }
            }
            // 卡片下方 panel
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    creator.label(),
                    color = HeColors.OpWhite,
                    fontFamily = NotoSansSC,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                CodeChip(
                    text = if (creator.kind == "x" && creator.screenName.isNotEmpty())
                        "@${creator.screenName}"
                    else "— · ARTIST",
                    color = HeColors.OpWhiteMuted,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

// ============================================================================
// 详情视图
// ============================================================================

@Composable
private fun CreatorDetailView(
    serverUrl: String,
    token: String,
    creator: Creator,
    detail: CreatorDetail?,
    detailLoading: Boolean,
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
) {
    val rank = when {
        creator.mediaCount >= 200 -> 6
        creator.mediaCount >= 100 -> 5
        creator.mediaCount >= 50  -> 4
        creator.mediaCount >= 20  -> 3
        creator.mediaCount >= 10  -> 2
        creator.mediaCount >= 1   -> 1
        else -> 0
    }
    val srcLabel = if (creator.kind == "x") "X-SRC" else "ARTIST"
    val coverUrl = creatorThumbUrl(serverUrl, token, creator.coverPath)
    val mediaList = detail?.media ?: emptyList()
    val starredCount = mediaList.count { it.favorite }
    val viewedCount = mediaList.count { it.viewStatus == "viewed" }

    Column(modifier = Modifier.fillMaxSize()) {
        // Hero 头
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        ) {
            // 模糊放大背景
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.25f)
                        .blur(28.dp),
                )
            } else {
                Box(Modifier.fillMaxSize().background(HeColors.Panel))
            }
            // ink overlay
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f   to HeColors.Ink.copy(alpha = 0.55f),
                            0.5f to HeColors.Ink.copy(alpha = 0.78f),
                            1f   to HeColors.Ink,
                        )
                    )
            )

            // Hero 内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                // 顶栏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn4(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        CodeChip(
                            text = "OP-${creator.key.hashCode().toUInt().toString().take(3).padStart(3, '0')}",
                            color = HeColors.Yellow,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "$srcLabel OPERATOR",
                            color = HeColors.OpWhiteMuted,
                            fontFamily = Oxanium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 1.8.sp,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                // OpAvatar + 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OpAvatar(
                        modelUrl = coverUrl,
                        size = 72.dp,
                        ring = true,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            creator.label(),
                            color = HeColors.OpWhite,
                            fontFamily = NotoSansSC,
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                            letterSpacing = (-0.5).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (creator.kind == "x" && creator.screenName.isNotEmpty())
                                "@${creator.screenName}"
                            else "ARTIST",
                            color = HeColors.OpWhiteSoft,
                            fontFamily = if (creator.kind == "x") GeistMono else Oxanium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = if (creator.kind == "x") 0.5.sp else 1.6.sp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Rank diamonds + RANK CodeChip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(6) { i ->
                        Diamond(
                            size = 6.dp,
                            filled = i < rank,
                            color = HeColors.Yellow,
                        )
                        if (i < 5) Spacer(Modifier.width(4.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    CodeChip(text = "RANK $rank", color = HeColors.OpWhiteMuted)
                }
                Spacer(Modifier.weight(1f))
                // 3 个 StatNumber
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatNumber(value = creator.mediaCount, label = "WORKS")
                    Box(
                        Modifier
                            .padding(horizontal = 14.dp)
                            .width(1.dp)
                            .height(28.dp)
                            .background(HeColors.HairlineMid)
                    )
                    StatNumber(value = starredCount, label = "STARRED", accent = HeColors.Yellow)
                    Box(
                        Modifier
                            .padding(horizontal = 14.dp)
                            .width(1.dp)
                            .height(28.dp)
                            .background(HeColors.HairlineMid)
                    )
                    StatNumber(value = viewedCount, label = "VIEWED", accent = HeColors.Online)
                }
                Spacer(Modifier.height(14.dp))
                // CTA 行
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    YellowCTA(
                        text = "BROWSE · 浏览",
                        onClick = { mediaList.firstOrNull()?.let(onMediaClick) },
                        icon = Icons.Default.PlayArrow,
                        size = CtaSize.Small,
                    )
                    GhostCta(
                        text = "STAR",
                        onClick = { /* TODO: 收藏整个 creator — 后端尚无接口 */ },
                        icon = Icons.Default.Bookmark,
                        size = CtaSize.Small,
                    )
                }
            }
        }

        // 作品集
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Slash(cn = "作品集", en = "Works", fontSize = 9.5.sp)
            Spacer(Modifier.height(10.dp))
            when {
                detailLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(
                        color = HeColors.Yellow,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
                detail == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "// 加载创作者作品失败",
                        color = HeColors.OpDanger,
                        fontFamily = GeistMono,
                        fontSize = 11.sp,
                    )
                }
                mediaList.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "// 没有作品",
                        color = HeColors.OpWhiteMuted,
                        fontFamily = GeistMono,
                        fontSize = 11.sp,
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    gridItems(mediaList, key = { it.id }) { m ->
                        val mediaIndex = mediaList.indexOf(m) + 1
                        WorkTile(
                            serverUrl = serverUrl,
                            token = token,
                            item = m,
                            index = mediaIndex,
                            onClick = { onMediaClick(m) },
                        )
                    }
                }
            }
        }

        StatusStripe(server = com.hemanager.mobile.feature.library.serverHostV2(serverUrl))
    }
}

@Composable
private fun WorkTile(
    serverUrl: String,
    token: String,
    item: MediaItem,
    index: Int,
    onClick: () -> Unit,
) {
    val cover = coverUrl(serverUrl, token, item)
    val shape = CutCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(HeColors.Panel),
            ) {
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // TR 6dp 黄角
            YellowCornerSeal(
                size = 6.dp,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            // 左上 #01 index
            CodeChip(
                text = "#${index.toString().padStart(2, '0')}",
                color = HeColors.OpWhite,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
            )
            // fav 右下
            if (item.favorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = HeColors.Yellow,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .size(11.dp),
                )
            }
            // 视频中央播放按钮
            if (item.mediaType == "video") {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .clip(CutCornerShape(7.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, HeColors.HairlineHi, CutCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = HeColors.Yellow,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            color = HeColors.OpWhite,
            fontFamily = NotoSansSC,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.duration > 0 && item.mediaType == "video") {
            Spacer(Modifier.height(2.dp))
            CodeChip(
                text = com.hemanager.mobile.feature.library.formatDuration(item.duration),
                color = HeColors.OpWhiteMuted,
                fontSize = 8.5.sp,
            )
        }
    }
}
