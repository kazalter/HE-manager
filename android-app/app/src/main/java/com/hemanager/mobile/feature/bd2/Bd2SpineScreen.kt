package com.hemanager.mobile.feature.bd2

import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.feature.library.FilterOption
import com.hemanager.mobile.feature.library.readableError
import com.hemanager.mobile.feature.library.serverHostV2
import com.hemanager.mobile.ui.op.AngularPanel
import com.hemanager.mobile.ui.op.CodeChip
import com.hemanager.mobile.ui.op.FilterTab
import com.hemanager.mobile.ui.op.IconBtn4
import com.hemanager.mobile.ui.op.OpTitle
import com.hemanager.mobile.ui.op.Slash
import com.hemanager.mobile.ui.op.StatNumber
import com.hemanager.mobile.ui.op.StatusStripe
import com.hemanager.mobile.ui.op.YellowCornerSeal
import com.hemanager.mobile.ui.theme.CutCornerShape
import com.hemanager.mobile.ui.theme.GeistMono
import com.hemanager.mobile.ui.theme.HeColors
import com.hemanager.mobile.ui.theme.NotoSansSC
import com.hemanager.mobile.ui.theme.Oxanium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SpineJsInterface(
    val onReady: (List<String>, List<String>) -> Unit,
    val onError: (String) -> Unit
) {
    @JavascriptInterface
    fun onPlayerSuccess(animsJson: String, skinsJson: String) {
        try {
            val animArray = JSONArray(animsJson)
            val skinArray = JSONArray(skinsJson)
            val anims = mutableListOf<String>()
            val skins = mutableListOf<String>()
            for (i in 0 until animArray.length()) anims.add(animArray.getString(i))
            for (i in 0 until skinArray.length()) skins.add(skinArray.getString(i))
            onReady(anims, skins)
        } catch (e: Exception) {
            onError("解析动画数据失败: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onPlayerError(msg: String) {
        onError(msg)
    }
}

@Composable
fun Bd2SpineScreen(
    serverUrl: String,
    token: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val assetsState = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val filteredAssetsState = remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val loadingState = remember { mutableStateOf(true) }
    val errorState = remember { mutableStateOf<String?>(null) }

    val selectedKindState = rememberSaveable { mutableStateOf("char") } // char, cutscene, illust
    val searchQueryState = rememberSaveable { mutableStateOf("") }
    val selectedAssetState = remember { mutableStateOf<JSONObject?>(null) }

    val kindFilters = remember {
        listOf(
            FilterOption("角色 CHAR", "char"),
            FilterOption("CG动画 CUT", "cutscene"),
            FilterOption("插画立绘 ILLUST", "illust")
        )
    }

    // Load assets list
    fun loadSpineAssets() {
        loadingState.value = true
        errorState.value = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = ApiClient(serverUrl, token)
                    val res: JSONObject = client.getJsonObject("/bd2/spine")
                    res
                }
            }
            loadingState.value = false
            result.onSuccess { res ->
                val arr = res.optJSONArray("assets") ?: JSONArray()
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getJSONObject(i))
                }
                assetsState.value = list
            }
            result.onFailure {
                errorState.value = readableError(it)
            }
        }
    }

    LaunchedEffect(serverUrl, token) {
        loadSpineAssets()
    }

    // Client-side filtering
    LaunchedEffect(assetsState.value, selectedKindState.value, searchQueryState.value) {
        val rawQuery = searchQueryState.value
        val kind = selectedKindState.value
        filteredAssetsState.value = assetsState.value.filter { item ->
            val k = item.optString("kind", "")
            val title = item.optString("title", "")
            val id = item.optString("asset_id", "")
            val kindMatch = k == kind
            val queryMatch = rawQuery.isBlank() ||
                    title.contains(rawQuery, ignoreCase = true) ||
                    id.contains(rawQuery, ignoreCase = true)
            kindMatch && queryMatch
        }
    }

    // Intercept back key
    BackHandler {
        if (selectedAssetState.value != null) {
            selectedAssetState.value = null
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HeColors.Ink)
    ) {
        if (selectedAssetState.value == null) {
            SpineAssetListView(
                assets = filteredAssetsState.value,
                totalCount = assetsState.value.size,
                loading = loadingState.value,
                error = errorState.value,
                searchQuery = searchQueryState.value,
                onSearchQueryChange = { searchQueryState.value = it },
                selectedKind = selectedKindState.value,
                kindFilters = kindFilters,
                onKindSelected = { selectedKindState.value = it },
                onBack = onBack,
                onAssetClick = { selectedAssetState.value = it },
                onRefresh = ::loadSpineAssets,
                serverUrl = serverUrl
            )
        } else {
            selectedAssetState.value?.let { asset ->
                SpinePlayerView(
                    serverUrl = serverUrl,
                    token = token,
                    asset = asset,
                    onBack = { selectedAssetState.value = null }
                )
            }
        }
    }
}

@Composable
private fun SpineAssetListView(
    assets: List<JSONObject>,
    totalCount: Int,
    loading: Boolean,
    error: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedKind: String,
    kindFilters: List<FilterOption>,
    onKindSelected: (String) -> Unit,
    onBack: () -> Unit,
    onAssetClick: (JSONObject) -> Unit,
    onRefresh: () -> Unit,
    serverUrl: String
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Topbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn4(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack
            )
            Spacer(Modifier.weight(1f))
            StatNumber(
                value = assets.size,
                total = totalCount,
                label = "ASSETS"
            )
        }

        // Header
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Slash(cn = "动态数据", en = "Spine Runtimes")
            Spacer(Modifier.height(14.dp))
            OpTitle(
                cn = "Spine 预览",
                en = "BROWN DUST 2 · SPINE 4.1",
                sizeSp = 36.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "测试 BD2 的骨骼动画。Spine 基于局域网服务\n通过 WebGL 硬件加速渲染，支持原生多重换装动作。",
                color = HeColors.OpWhiteSoft,
                fontFamily = NotoSansSC,
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .background(HeColors.Yellow)
            )
        }

        Spacer(Modifier.height(16.dp))

        // Search Bar
        val searchShape = CutCornerShape(8.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .heightIn(min = 46.dp)
                .clip(searchShape)
                .background(HeColors.Panel)
                .border(1.dp, HeColors.HairlineMid, searchShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = HeColors.OpWhiteMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isBlank()) {
                    Text(
                        "输入角色名或别名搜索...",
                        color = HeColors.OpWhiteMuted,
                        fontFamily = NotoSansSC,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                        color = HeColors.OpWhite,
                        fontFamily = NotoSansSC,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(HeColors.Yellow),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(visible = searchQuery.isNotBlank()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "清除搜索",
                    tint = HeColors.OpWhiteMuted,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onSearchQueryChange("") },
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            kindFilters.forEach { opt ->
                val parts = opt.label.split(" ")
                val cn = parts.getOrNull(0) ?: opt.label
                val en = parts.getOrNull(1)
                FilterTab(
                    label = cn,
                    en = en,
                    active = selectedKind == opt.value,
                    onClick = { onKindSelected(opt.value) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Content
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                CircularProgressIndicator(
                    color = HeColors.Yellow,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
            error != null -> Box(Modifier.fillMaxWidth().weight(1f).padding(18.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "数据加载失败: $error",
                        color = HeColors.OpDanger,
                        fontFamily = GeistMono,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .clip(CutCornerShape(6.dp))
                            .border(1.dp, HeColors.Yellow.copy(0.4f), CutCornerShape(6.dp))
                            .clickable(onClick = onRefresh)
                            .background(HeColors.Panel)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "RETRY",
                            color = HeColors.Yellow,
                            fontFamily = Oxanium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            assets.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                Text(
                    "// 暂无 Spine 资源，请先在网页端拉取仓库",
                    color = HeColors.OpWhiteMuted,
                    fontFamily = GeistMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(
                    count = assets.size,
                    key = { index -> assets[index].optString("asset_id", "") }
                ) { index ->
                    val assetItem = assets[index]
                    AssetCard(
                        asset = assetItem,
                        onClick = { onAssetClick(assetItem) }
                    )
                }
            }
        }

        StatusStripe(server = serverHostV2(serverUrl))
    }
}

@Composable
private fun AssetCard(
    asset: JSONObject,
    onClick: () -> Unit
) {
    val title = asset.optString("title", "未命名")
    val assetId = asset.optString("asset_id", "")
    val textures = asset.optJSONArray("textures")?.length() ?: 0

    AngularPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cut = 10.dp,
        background = HeColors.Panel,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                CodeChip(
                    text = "ID: $assetId",
                    color = HeColors.Yellow,
                    fontSize = 10.sp
                )
                YellowCornerSeal(
                    size = 8.dp,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = title,
                color = HeColors.OpWhite,
                fontFamily = NotoSansSC,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.GridOn,
                    contentDescription = null,
                    tint = HeColors.OpWhiteMuted,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$textures Textures",
                    color = HeColors.OpWhiteMuted,
                    fontFamily = GeistMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SpinePlayerView(
    serverUrl: String,
    token: String,
    asset: JSONObject,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val isWebLoading = remember { mutableStateOf(true) }
    val webError = remember { mutableStateOf<String?>(null) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val animations = remember { mutableStateOf<List<String>>(emptyList()) }
    val skins = remember { mutableStateOf<List<String>>(emptyList()) }
    val currentAnimation = remember { mutableStateOf("") }
    val currentSkin = remember { mutableStateOf("") }
    val hideEffects = remember { mutableStateOf(false) }

    val skeletonUrl = asset.optString("skeleton_url", "")
    val atlasUrl = asset.optString("atlas_url", "")
    val title = asset.optString("title", "Spine 动画")
    val assetId = asset.optString("asset_id", "")

    val embedUrl = remember(serverUrl, skeletonUrl, atlasUrl) {
        val base = serverUrl.trimEnd('/')
        val encodedSkel = Uri.encode(skeletonUrl)
        val encodedAtlas = Uri.encode(atlasUrl)
        "$base/#/bd2-spine/embed?skeletonUrl=$encodedSkel&atlasUrl=$encodedAtlas"
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.apply {
                stopLoading()
                clearHistory()
                removeAllViews()
                destroy()
            }
            webViewRef.value = null
        }
    }

    val onReadyBridge: (List<String>, List<String>) -> Unit = remember(scope) {
        { anims, sks ->
            scope.launch(Dispatchers.Main) {
                animations.value = anims
                skins.value = sks
                isWebLoading.value = false
                currentAnimation.value = anims.firstOrNull {
                    it.contains("idle", ignoreCase = true) || it.contains("lobby", ignoreCase = true)
                } ?: anims.firstOrNull() ?: ""
                currentSkin.value = sks.firstOrNull() ?: ""
            }
        }
    }

    val onErrorBridge: (String) -> Unit = remember(scope) {
        { err ->
            scope.launch(Dispatchers.Main) {
                webError.value = err
                isWebLoading.value = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn4(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回列表",
                onClick = onBack
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = HeColors.OpWhite,
                    fontFamily = NotoSansSC,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "ASSET: $assetId",
                    color = HeColors.OpWhiteMuted,
                    fontFamily = GeistMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF070a12))
        ) {
            AndroidView(
                factory = { ctx ->
                    val webView = WebView(ctx)
                    webView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    val settings = webView.settings
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE

                    webView.webViewClient = WebViewClient()
                    webView.webChromeClient = WebChromeClient()
                    webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                    val bridge = SpineJsInterface(onReady = onReadyBridge, onError = onErrorBridge)
                    webView.addJavascriptInterface(bridge, "Android")
                    webViewRef.value = webView
                    webView.loadUrl(embedUrl)
                    webView
                },
                update = { webView -> },
                modifier = Modifier.fillMaxSize()
            )

            if (isWebLoading.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xE0070a12)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = HeColors.Yellow,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "正在同步 WebGL 渲染流...",
                            color = HeColors.OpWhiteSoft,
                            fontFamily = NotoSansSC,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (webError.value != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xF0070a12))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "WebGL 载入故障",
                            color = HeColors.OpDanger,
                            fontFamily = NotoSansSC,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = webError.value ?: "未知错误",
                            color = HeColors.OpWhiteSoft,
                            fontFamily = GeistMono,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !isWebLoading.value && webError.value == null,
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f)
        ) {
            SpineControlPanel(
                hideEffects = hideEffects.value,
                onHideEffectsChange = { checked ->
                    hideEffects.value = checked
                    webViewRef.value?.loadUrl("javascript:window.setHideEffectLayers($checked)")
                },
                skins = skins.value,
                currentSkin = currentSkin.value,
                onSkinChange = { skin ->
                    currentSkin.value = skin
                    webViewRef.value?.loadUrl("javascript:window.setSkin('$skin')")
                },
                animations = animations.value,
                currentAnimation = currentAnimation.value,
                onAnimationChange = { anim ->
                    currentAnimation.value = anim
                    webViewRef.value?.loadUrl("javascript:window.playAnimation('$anim', true)")
                }
            )
        }
        StatusStripe(server = serverHostV2(serverUrl))
    }
}

@Composable
private fun SpineControlPanel(
    hideEffects: Boolean,
    onHideEffectsChange: (Boolean) -> Unit,
    skins: List<String>,
    currentSkin: String,
    onSkinChange: (String) -> Unit,
    animations: List<String>,
    currentAnimation: String,
    onAnimationChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HeColors.Panel)
            .border(BorderStroke(1.dp, HeColors.HairlineMid))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Animation,
                    contentDescription = null,
                    tint = HeColors.Yellow,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "隐藏背景及特效图层",
                    color = HeColors.OpWhite,
                    fontFamily = NotoSansSC,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = hideEffects,
                onCheckedChange = onHideEffectsChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = HeColors.Yellow,
                    checkedTrackColor = HeColors.Yellow.copy(0.35f),
                    uncheckedThumbColor = HeColors.OpWhiteMuted,
                    uncheckedTrackColor = HeColors.Void
                )
            )
        }

        if (skins.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hideEffects) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = HeColors.OpWhiteSoft,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "SKIN · 皮肤",
                        color = HeColors.OpWhiteSoft,
                        fontFamily = Oxanium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(skins.size) { index ->
                        val skin = skins[index]
                        val active = skin == currentSkin
                        Box(
                            modifier = Modifier
                                .clip(CutCornerShape(4.dp))
                                .background(if (active) HeColors.Yellow.copy(0.12f) else HeColors.Ink)
                                .border(
                                    1.dp,
                                    if (active) HeColors.Yellow else HeColors.HairlineMid,
                                    CutCornerShape(4.dp)
                                )
                                .clickable { onSkinChange(skin) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = skin,
                                color = if (active) HeColors.Yellow else HeColors.OpWhiteSoft,
                                fontFamily = GeistMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        if (animations.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Animation,
                        contentDescription = null,
                        tint = HeColors.OpWhiteSoft,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "ANIMATION · 动作姿态",
                        color = HeColors.OpWhiteSoft,
                        fontFamily = Oxanium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(animations.size) { index ->
                        val anim = animations[index]
                        val active = anim == currentAnimation
                        Box(
                            modifier = Modifier
                                .clip(CutCornerShape(4.dp))
                                .background(if (active) HeColors.Yellow.copy(0.12f) else HeColors.Ink)
                                .border(
                                    1.dp,
                                    if (active) HeColors.Yellow else HeColors.HairlineMid,
                                    CutCornerShape(4.dp)
                                )
                                .clickable { onAnimationChange(anim) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = anim,
                                color = if (active) HeColors.Yellow else HeColors.OpWhiteSoft,
                                fontFamily = GeistMono,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
