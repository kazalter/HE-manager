package com.hemanager.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
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
import androidx.compose.foundation.Image
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val coverCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }
    private val coverInFlightLock = Mutex()
    private val coverInFlight = mutableMapOf<String, CompletableDeferred<Bitmap?>>()

    internal val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value = resumeTick.value + 1
    }

    private val scheme = darkColorScheme(
        primary = Color(0xFF8EA2FF),
        secondary = Color(0xFF58E6C2),
        tertiary = Color(0xFFF6C46B),
        background = Color(0xFF070A12),
        surface = Color(0xFF111620),
        surfaceVariant = Color(0xFF202838),
        primaryContainer = Color(0xFF28336D),
        secondaryContainer = Color(0xFF123C34),
        tertiaryContainer = Color(0xFF483615),
        errorContainer = Color(0xFF54212F),
        onPrimary = Color(0xFF080B12),
        onSecondary = Color(0xFF0E1715),
        onTertiary = Color(0xFF15100A),
        onBackground = Color(0xFFF4F7FF),
        onSurface = Color(0xFFF4F7FF),
        onSurfaceVariant = Color(0xFFC6CFDD),
        onErrorContainer = Color(0xFFFFD9DD)
    )

    private data class FilterOption(val label: String, val value: String)
    private enum class LibraryViewMode { Large, Grid, Detail }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 10, 18)
        window.navigationBarColor = android.graphics.Color.rgb(7, 10, 18)

        setContent {
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HeManagerApp()
                }
            }
        }
    }

    @Composable
    private fun HeManagerApp() {
        val prefs = remember { getSharedPreferences("he_manager", MODE_PRIVATE) }
        var serverUrl by remember { mutableStateOf(prefs.getString("server_url", "") ?: "") }
        var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }

        if (serverUrl.isBlank() || token.isBlank()) {
            LoginScreen(
                initialServer = serverUrl,
                onLoggedIn = { nextServer, nextToken ->
                    serverUrl = nextServer
                    token = nextToken
                    prefs.edit().putString("server_url", nextServer).putString("token", nextToken).apply()
                }
            )
        } else {
            LibraryScreenV2(
                serverUrl = serverUrl,
                token = token,
                onLogout = {
                    prefs.edit().remove("token").apply()
                    token = ""
                }
            )
        }
    }

    @Composable
    private fun LoginScreen(
        initialServer: String,
        onLoggedIn: (String, String) -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var server by remember { mutableStateOf(if (initialServer.isBlank()) "http://" else initialServer) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        val formScale by animateFloatAsState(
            targetValue = if (busy) 0.985f else 1f,
            animationSpec = spring(stiffness = 420f, dampingRatio = 0.82f),
            label = "loginScale"
        )

        fun submit(bootstrap: Boolean) {
            val targetServer = ApiClient.trimSlash(server)
            if (!targetServer.startsWith("http://") && !targetServer.startsWith("https://")) {
                message = "服务器地址需要以 http:// 或 https:// 开头"
                return
            }
            if (username.isBlank() || password.isBlank()) {
                message = "请填写用户名和密码"
                return
            }
            busy = true
            message = "正在连接..."
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val body = JSONObject()
                            .put("username", username.trim())
                            .put("password", password)
                        if (bootstrap) body.put("is_admin", true)
                        ApiClient(targetServer, "")
                            .postJson(if (bootstrap) "/auth/bootstrap" else "/auth/login", body, false)
                            .getString("access_token")
                    }
                }
                busy = false
                result.onSuccess { onLoggedIn(targetServer, it) }
                result.onFailure { message = it.message ?: "登录失败" }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackgroundBrush())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .graphicsLayer {
                        scaleX = formScale
                        scaleY = formScale
                    },
                verticalArrangement = Arrangement.Center
            ) {
                BrandMark()
                Spacer(Modifier.height(20.dp))
                Text(
                    "HE Manager",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "让电脑里的视频、漫画和图片，以更舒服的方式进入手机。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(26.dp))

                GlassPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Text(
                            "连接你的媒体服务",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "输入电脑端服务地址和账号，登录后会自动记住这台服务器。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        ModernTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = "服务器",
                            support = "例如 http://192.168.1.23:8010"
                        )
                        ModernTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = "账号"
                        )
                        ModernTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = "密码",
                            password = true
                        )
                        Button(
                            onClick = { submit(false) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            enabled = !busy,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text("登录", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { submit(true) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            enabled = !busy,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("创建第一个管理员", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = message.isNotBlank(),
                    enter = fadeIn(tween(180)) + expandVertically(),
                    exit = fadeOut(tween(160)) + shrinkVertically()
                ) {
                    Text(
                        message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    @Composable
    private fun LibraryScreen(
        serverUrl: String,
        token: String,
        onLogout: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var mediaType by remember { mutableStateOf("") }
        var search by remember { mutableStateOf("") }
        var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var requestId by remember { mutableStateOf(0) }
        val filters = remember {
            listOf(
                FilterOption("全部", ""),
                FilterOption("视频", "video"),
                FilterOption("漫画", "manga"),
                FilterOption("图片", "image")
            )
        }
        val drawerState = rememberDrawerState(DrawerValue.Closed)

        fun load() {
            val currentRequest = requestId + 1
            requestId = currentRequest
            loading = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ApiClient(serverUrl, token).getMedia(mediaType, search, "date") }
                }
                if (requestId != currentRequest) return@launch
                loading = false
                result.onSuccess { items = it }
                result.onFailure {
                    error = readableError(it)
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        }

        LaunchedEffect(serverUrl, token, mediaType) {
            load()
        }

        val selectedFilterLabel = filters.firstOrNull { it.value == mediaType }?.label ?: "全部"

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawer(
                    serverUrl = serverUrl,
                    items = items,
                    filters = filters,
                    selectedValue = mediaType,
                    loading = loading,
                    onSelected = { value ->
                        mediaType = value
                        scope.launch { drawerState.close() }
                    },
                    onRefresh = {
                        scope.launch { drawerState.close() }
                        load()
                    },
                    onLogout = {
                        scope.launch { drawerState.close() }
                        onLogout()
                    }
                )
            }
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppBackgroundBrush())
                        .padding(padding)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            LibraryHeader(
                                serverUrl = serverUrl,
                                items = items,
                                loading = loading,
                                selectedFilterLabel = selectedFilterLabel,
                                onMenu = { scope.launch { drawerState.open() } },
                                onRefresh = { load() }
                            )
                        }
                        item {
                            SearchAndFilterPanel(
                                search = search,
                                onSearchChange = { search = it },
                                filters = filters,
                                selectedValue = mediaType,
                                onSelected = { mediaType = it },
                                loading = loading,
                                onSearch = { load() }
                            )
                        }
                        item {
                            AnimatedVisibility(
                                visible = error != null,
                                enter = fadeIn(tween(180)) + expandVertically(),
                                exit = fadeOut(tween(160)) + shrinkVertically()
                            ) {
                                ErrorPanel(error = error ?: "", onRetry = { load() })
                            }
                        }
                        item {
                            AnimatedVisibility(
                                visible = loading,
                                enter = fadeIn(tween(160)),
                                exit = fadeOut(tween(160))
                            ) {
                                LoadingLine()
                            }
                        }
                        if (loading && items.isEmpty()) {
                            repeat(4) { index ->
                                item(key = "loading-card-$index") {
                                    LoadingCardSkeleton(index)
                                }
                            }
                        }
                        if (!loading && error == null && items.isEmpty()) {
                            item { EmptyState() }
                        }
                        items(items, key = { it.id }) { item ->
                            MediaCard(serverUrl, token, item) { restart -> 
                                openItem(item, serverUrl, token, restart) 
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppDrawer(
        serverUrl: String,
        items: List<MediaItem>,
        filters: List<FilterOption>,
        selectedValue: String,
        loading: Boolean,
        onSelected: (String) -> Unit,
        onRefresh: () -> Unit,
        onLogout: () -> Unit
    ) {
        ModalDrawerSheet(
            modifier = Modifier.width(316.dp),
            drawerContainerColor = Color(0xFF10141D),
            drawerContentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackgroundBrush())
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BrandMark()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        greetingText(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        "今天想看点什么？",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                DrawerServerCard(serverUrl = serverUrl)
                DrawerDivider()

                DrawerSectionTitle("浏览")
                filters.forEach { option ->
                    val accent = filterAccent(option.value)
                    NavigationDrawerItem(
                        selected = selectedValue == option.value,
                        onClick = { onSelected(option.value) },
                        icon = { DrawerDot(accent) },
                        label = {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                        },
                        badge = {
                            Text("${countForFilter(items, option.value)}")
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            unselectedContainerColor = Color.Transparent,
                            selectedIconColor = accent,
                            unselectedIconColor = accent,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedBadgeColor = MaterialTheme.colorScheme.primary,
                            unselectedBadgeColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                DrawerDivider()
                DrawerSectionTitle("状态")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill("${items.size}", "全部", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    StatPill("${items.count { it.viewStatus == "viewing" }}", "继续", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill("${items.count { it.favorite }}", "收藏", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                    StatPill("${items.count { it.viewStatus == "viewed" }}", "已看", Color(0xFFFF8FA3), Modifier.weight(1f))
                }

                DrawerDivider()
                DrawerAction(
                    label = if (loading) "正在刷新" else "刷新媒体库",
                    accent = MaterialTheme.colorScheme.primary,
                    enabled = !loading,
                    onClick = onRefresh
                )
                DrawerAction(
                    label = "退出登录",
                    accent = Color(0xFFFF8FA3),
                    onClick = onLogout
                )
            }
        }
    }

    @Composable
    private fun LibraryHeader(
        serverUrl: String,
        items: List<MediaItem>,
        loading: Boolean,
        selectedFilterLabel: String,
        onMenu: () -> Unit,
        onRefresh: () -> Unit
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconPill(onClick = onMenu, contentDescription = "打开侧边栏") {
                    Icon(Icons.Default.Menu, contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "媒体库",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "$selectedFilterLabel · ${summary(items)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconPill(onClick = onRefresh, enabled = !loading, contentDescription = "刷新") {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            }
            CompactInsightStrip(items = items, loading = loading, serverUrl = serverUrl)
        }
    }

    @Composable
    private fun CompactInsightStrip(items: List<MediaItem>, loading: Boolean, serverUrl: String) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = 0.045f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.075f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        serverUrl,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    StatusPill("${items.count { it.viewStatus == "viewing" }} 个继续看", MaterialTheme.colorScheme.primary)
                    StatusPill("${items.count { it.favorite }} 个收藏", MaterialTheme.colorScheme.tertiary)
                    StatusPill("${items.count { it.viewStatus == "viewed" }} 个已看完", MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }

    @Composable
    private fun DrawerServerCard(serverUrl: String) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = 0.050f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "服务器",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    serverUrl,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun DrawerSectionTitle(label: String) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }

    @Composable
    private fun DrawerDivider() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
    }

    @Composable
    private fun DrawerDot(color: Color) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
    }

    @Composable
    private fun DrawerAction(
        label: String,
        accent: Color,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.98f else 1f,
            animationSpec = tween(120, easing = FastOutSlowInEasing),
            label = "drawerActionScale"
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.55f)
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
            shape = RoundedCornerShape(8.dp),
            color = accent.copy(alpha = if (pressed) 0.20f else 0.13f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DrawerDot(accent)
                Spacer(Modifier.width(10.dp))
                Text(label, color = accent, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun SearchAndFilterPanel(
        search: String,
        onSearchChange: (String) -> Unit,
        filters: List<FilterOption>,
        selectedValue: String,
        onSelected: (String) -> Unit,
        loading: Boolean,
        onSearch: () -> Unit
    ) {
        GlassPanel(contentPadding = PaddingValues(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    label = { Text("搜索标题") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        AnimatedVisibility(visible = search.isNotBlank()) {
                            IconButton(onClick = { onSearchChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "清除搜索")
                            }
                        }
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { option ->
                        ModernFilterChip(
                            label = option.label,
                            selected = selectedValue == option.value,
                            accent = filterAccent(option.value),
                            onClick = { onSelected(option.value) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "按更新时间排序",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = onSearch,
                        enabled = !loading,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("搜索", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun ModernFilterChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val background by animateColorAsState(
            targetValue = if (selected) accent else Color.White.copy(alpha = 0.045f),
            animationSpec = tween(180),
            label = "filterBg"
        )
        val foreground by animateColorAsState(
            targetValue = if (selected) Color(0xFF0B0D12) else MaterialTheme.colorScheme.onSurfaceVariant,
            animationSpec = tween(180),
            label = "filterFg"
        )
        val scale by animateFloatAsState(
            targetValue = when {
                pressed -> 0.97f
                selected -> 1.02f
                else -> 1f
            },
            animationSpec = tween(140, easing = FastOutSlowInEasing),
            label = "filterScale"
        )

        Surface(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            color = background,
            border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.36f) else Color.White.copy(alpha = 0.10f))
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                color = foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun MediaCard(
        serverUrl: String,
        token: String,
        item: MediaItem,
        onOpen: (Boolean) -> Unit
    ) {
        val progress = progressFraction(item)
        val accent = typeAccent(item.mediaType)
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val cardScale by animateFloatAsState(
            targetValue = if (pressed) 0.985f else 1f,
            animationSpec = tween(140, easing = FastOutSlowInEasing),
            label = "cardScale"
        )
        val cardColor by animateColorAsState(
            targetValue = if (pressed) Color(0xFF1B2130) else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            animationSpec = tween(160),
            label = "cardColor"
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = spring(stiffness = 420f, dampingRatio = 0.86f))
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .clickable(interactionSource = interactionSource, indication = null, onClick = { onOpen(false) }),
            shape = RoundedCornerShape(8.dp),
            color = cardColor,
            border = BorderStroke(1.dp, accent.copy(alpha = if (pressed) 0.36f else 0.18f))
        ) {
            Row(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                accent.copy(alpha = 0.075f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(112.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(9.dp))
                RemoteCover(
                    url = coverUrl(serverUrl, token, item),
                    label = typeLabel(item.mediaType),
                    accent = accent
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                meta(item),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = accent.copy(alpha = 0.16f),
                            contentColor = accent
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(7.dp)
                                    .size(18.dp)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = progress != null,
                        enter = fadeIn(tween(180)) + expandVertically(),
                        exit = fadeOut(tween(160)) + shrinkVertically()
                    ) {
                        Column {
                            Spacer(Modifier.height(11.dp))
                            LinearProgressIndicator(
                                progress = { progress ?: 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                color = progressColor(item),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(progressText(item), progressColor(item))
                        if (item.mediaType == "manga" && (item.viewStatus == "viewing" || item.viewStatus == "viewed")) {
                            ActionPill("从头观看", MaterialTheme.colorScheme.primary) {
                                onOpen(true)
                            }
                        }
                        if (item.favorite) StatusPill("收藏", MaterialTheme.colorScheme.tertiary)
                        if (item.missing) StatusPill("文件缺失", Color(0xFFFF8FA3))
                    }
                }
            }
        }
    }

    @Composable
    private fun RemoteCover(url: String?, label: String, accent: Color) {
        val bitmap by produceState<Bitmap?>(initialValue = cachedCover(url), key1 = url) {
            if (url.isNullOrBlank() || value != null) return@produceState
            value = withContext(Dispatchers.IO) { loadCoverBitmap(url) }
        }

        Box(
            modifier = Modifier
                .width(92.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.34f),
                            Color(0xFF11141B)
                        )
                    )
                )
                .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.60f)),
                                startY = 120f
                            )
                        )
                )
            } else {
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.52f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun EmptyState() {
        GlassPanel {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.padding(14.dp).size(24.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("没有找到媒体", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "换个分类或搜索词试试",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun ErrorPanel(error: String, onRetry: () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Row(
                modifier = Modifier.padding(13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    error,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = onRetry) {
                    Text("重试", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun LoadingLine() {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = 0.045f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.075f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在更新媒体库", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun LoadingCardSkeleton(index: Int) {
        val transition = rememberInfiniteTransition(label = "skeleton$index")
        val alpha by transition.animateFloat(
            initialValue = 0.26f,
            targetValue = 0.62f,
            animationSpec = infiniteRepeatable(
                animation = tween(860, delayMillis = index * 90, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "skeletonAlpha$index"
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.065f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(92.dp)
                        .aspectRatio(0.72f),
                    alpha = alpha
                )
                Spacer(Modifier.width(14.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth(0.86f).height(18.dp), alpha)
                    SkeletonBlock(Modifier.fillMaxWidth(0.58f).height(12.dp), alpha * 0.82f)
                    Spacer(Modifier.height(2.dp))
                    SkeletonBlock(Modifier.fillMaxWidth().height(5.dp), alpha * 0.72f)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SkeletonBlock(Modifier.width(56.dp).height(24.dp), alpha * 0.78f)
                        SkeletonBlock(Modifier.width(64.dp).height(24.dp), alpha * 0.62f)
                    }
                }
            }
        }
    }

    @Composable
    private fun SkeletonBlock(modifier: Modifier, alpha: Float) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.05f),
                            MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.20f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                )
        )
    }

    @Composable
    private fun GlassPanel(
        modifier: Modifier = Modifier,
        contentPadding: PaddingValues = PaddingValues(16.dp),
        content: @Composable () -> Unit
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.045f),
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
    private fun ModernTextField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        support: String? = null,
        password: Boolean = false
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            label = { Text(label) },
            supportingText = support?.let { { Text(it) } },
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
        )
    }

    @Composable
    private fun BrandMark() {
        Row(
            modifier = Modifier.wrapContentWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("HE", color = Color(0xFF10131B), fontWeight = FontWeight.Black)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.White.copy(alpha = 0.055f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Text(
                    "Native Android",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun IconPill(
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
            label = "iconPillScale"
        )
        Surface(
            modifier = Modifier
                .size(42.dp)
                .alpha(if (enabled) 1f else 0.55f)
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
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = if (pressed) 0.10f else 0.060f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
    }

    @Composable
    private fun StatPill(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = 0.045f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.075f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(value, color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun StatusPill(label: String, color: Color) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = color.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.18f))
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun ActionPill(label: String, color: Color, onClick: () -> Unit) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        Surface(
            modifier = Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            color = color.copy(alpha = if (pressed) 0.2f else 0.12f),
            border = BorderStroke(1.dp, color.copy(alpha = if (pressed) 0.3f else 0.18f))
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun AppBackgroundBrush(): Brush {
        return Brush.radialGradient(
            listOf(
                Color(0xFF17213C),
                Color(0xFF0B1020),
                Color(0xFF070A12)
            ),
            radius = 1250f
        )
    }

    @Composable
    private fun LibraryScreenV2(
        serverUrl: String,
        token: String,
        onLogout: () -> Unit
    ) {
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

        val listState = rememberLazyListState()
        val swipeController = remember { SwipeRevealController() }
        LaunchedEffect(listState, swipeController) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collectLatest { scrolling ->
                    if (scrolling && !swipeController.isDragging) swipeController.close()
                }
        }
        LaunchedEffect(viewMode) { swipeController.close() }

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
                    error = readableError(it)
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
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

        val initialResumeTick = remember { resumeTick.value }
        val resumeCounter by resumeTick
        LaunchedEffect(resumeCounter) {
            if (resumeCounter != initialResumeTick) load(search)
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
                        Toast.makeText(this@MainActivity, "设置即将开放", Toast.LENGTH_SHORT).show()
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
                                item(key = "loading-card-v2-$index") {
                                    LoadingCardSkeletonV2(index)
                                }
                            }
                        }
                        if (!loading && error == null && visibleItems.isEmpty()) {
                            item { EmptyStateV2() }
                        }
                        when (viewMode) {
                            LibraryViewMode.Large -> {
                                items(visibleItems, key = { "large-${it.id}" }) { item ->
                                    MediaCardV2(
                                        serverUrl = serverUrl,
                                        token = token,
                                        item = item,
                                        controller = swipeController,
                                        onOpen = { restart -> openItem(item, serverUrl, token, restart) },
                                        onQuickAction = { action -> runQuickAction(item, action) }
                                    )
                                }
                            }
                            LibraryViewMode.Grid -> {
                                items(visibleItems.chunked(3), key = { row -> "grid-${row.firstOrNull()?.id ?: 0}" }) { rowItems ->
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
                                                onOpen = { openItem(item, serverUrl, token, false) },
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
                                items(visibleItems, key = { "detail-${it.id}" }) { item ->
                                    MediaDetailRowV2(
                                        serverUrl = serverUrl,
                                        token = token,
                                        item = item,
                                        controller = swipeController,
                                        onOpen = { restart -> openItem(item, serverUrl, token, restart) },
                                        onQuickAction = { action -> runQuickAction(item, action) }
                                    )
                                }
                            }
                        }
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TagPickerSheetV2(
        serverUrl: String,
        token: String,
        item: MediaItem,
        onDismiss: () -> Unit,
        onTagAdded: (MediaItem) -> Unit
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        var allTags by remember { mutableStateOf<List<TagItem>>(emptyList()) }
        var loadingTags by remember { mutableStateOf(true) }
        var newTagName by remember { mutableStateOf("") }
        var submitting by remember { mutableStateOf(false) }

        LaunchedEffect(item.id) {
            val result = withContext(Dispatchers.IO) {
                runCatching { ApiClient(serverUrl, token).getTags() }
            }
            loadingTags = false
            result.onSuccess { allTags = it }
            result.onFailure {
                Toast.makeText(this@MainActivity, "标签加载失败: ${readableError(it)}", Toast.LENGTH_SHORT).show()
            }
        }

        fun submitTag(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || submitting) return
            submitting = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ApiClient(serverUrl, token).addTag(item.id, trimmed) }
                }
                submitting = false
                result.onSuccess { fresh ->
                    onTagAdded(fresh)
                    newTagName = ""
                    if (allTags.none { it.name.equals(trimmed, ignoreCase = true) }) {
                        // Re-fetch tag list so the new tag becomes available for other items.
                        scope.launch {
                            val refresh = withContext(Dispatchers.IO) {
                                runCatching { ApiClient(serverUrl, token).getTags() }
                            }
                            refresh.onSuccess { allTags = it }
                        }
                    }
                }
                result.onFailure {
                    Toast.makeText(this@MainActivity, "添加失败: ${readableError(it)}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = Color(0xFF12172A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    "为「${item.title}」添加标签",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))

                if (item.tags.isNotEmpty()) {
                    Text(
                        "当前标签",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRowCompat(
                        items = item.tags,
                        spacing = 8.dp
                    ) { tag ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF7AB8FF).copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color(0xFF7AB8FF).copy(alpha = 0.42f))
                        ) {
                            Text(
                                tag.name,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = Color(0xFFB8D4FF),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                Text(
                    "新建标签",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入标签名…", color = Color.White.copy(alpha = 0.45f)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF7AB8FF).copy(alpha = 0.6f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                            cursorColor = Color(0xFF7AB8FF)
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { submitTag(newTagName) },
                        enabled = newTagName.trim().isNotEmpty() && !submitting,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7AB8FF),
                            contentColor = Color(0xFF0B1020)
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))

                Text(
                    "已有标签",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                if (loadingTags) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF7AB8FF))
                        Spacer(Modifier.width(10.dp))
                        Text("加载中…", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (allTags.isEmpty()) {
                    Text("暂无标签，新建一个吧", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                } else {
                    val attachedNames = remember(item.tags) { item.tags.map { it.name }.toSet() }
                    FlowRowCompat(items = allTags, spacing = 8.dp) { tag ->
                        val attached = tag.name in attachedNames
                        Surface(
                            modifier = Modifier.clickable(enabled = !attached && !submitting) {
                                submitTag(tag.name)
                            },
                            shape = RoundedCornerShape(999.dp),
                            color = if (attached) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = if (attached) 0.10f else 0.20f))
                        ) {
                            Text(
                                tag.name,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (attached) Color.White.copy(alpha = 0.45f) else Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    /**
     * Minimal flow row — wraps children to multiple lines as space runs out.
     * Compose has FlowRow in androidx.compose.foundation.layout but it's still
     * marked experimental; this avoids the opt-in and pulls its weight.
     */
    @Composable
    private fun <T> FlowRowCompat(
        items: List<T>,
        spacing: androidx.compose.ui.unit.Dp,
        content: @Composable (T) -> Unit
    ) {
        Layout(
            content = { items.forEach { content(it) } },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val spacingPx = spacing.roundToPx()
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
            val rows = mutableListOf<MutableList<androidx.compose.ui.layout.Placeable>>()
            var current = mutableListOf<androidx.compose.ui.layout.Placeable>()
            var rowWidth = 0
            placeables.forEach { p ->
                val needed = if (current.isEmpty()) p.width else rowWidth + spacingPx + p.width
                if (needed > constraints.maxWidth && current.isNotEmpty()) {
                    rows.add(current)
                    current = mutableListOf(p)
                    rowWidth = p.width
                } else {
                    current.add(p)
                    rowWidth = needed
                }
            }
            if (current.isNotEmpty()) rows.add(current)
            val totalHeight = rows.sumOf { row -> row.maxOfOrNull { it.height } ?: 0 } +
                spacingPx * (rows.size - 1).coerceAtLeast(0)
            layout(constraints.maxWidth, totalHeight) {
                var y = 0
                rows.forEach { row ->
                    var x = 0
                    val rowHeight = row.maxOfOrNull { it.height } ?: 0
                    row.forEach { p ->
                        p.placeRelative(x, y)
                        x += p.width + spacingPx
                    }
                    y += rowHeight + spacingPx
                }
            }
        }
    }

    @Composable
    private fun AppDrawerV2(
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
        onLogout: () -> Unit
    ) {
        ModalDrawerSheet(
            modifier = Modifier.width(326.dp),
            drawerContainerColor = Color(0xEF080B12),
            drawerContentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEF080B12))
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 18.dp, end = 17.dp, top = 22.dp, bottom = 18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DrawerProfileHeaderV2()
                        DrawerServerCardV2(
                            serverUrl = serverUrl,
                            onManage = {
                                Toast.makeText(this@MainActivity, "媒体源管理即将开放", Toast.LENGTH_SHORT).show()
                            }
                        )
                        DrawerStatsCardV2(
                            items = items,
                            selectedStatus = selectedStatusValue,
                            onStatusSelected = onStatusSelected
                        )

                        DrawerSectionTitleV2("导航")
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DrawerNavItemV2(
                                label = "媒体库",
                                icon = Icons.Default.Home,
                                selected = selectedValue.isBlank() && selectedStatusValue.isBlank(),
                                onClick = {
                                    onSelected("")
                                    onStatusSelected("")
                                }
                            )
                            DrawerNavItemV2(
                                label = "最近阅读",
                                icon = Icons.Default.History,
                                selected = selectedStatusValue == "viewing",
                                onClick = { onStatusSelected("viewing") }
                            )
                            DrawerNavItemV2(
                                label = "收藏夹",
                                icon = Icons.Default.StarBorder,
                                selected = selectedStatusValue == "favorite",
                                onClick = { onStatusSelected("favorite") }
                            )
                            DrawerNavItemV2(
                                label = "作者 / 标签",
                                icon = Icons.Default.LocalOffer,
                                onClick = {
                                    Toast.makeText(this@MainActivity, "作者/标签即将开放", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DrawerNavItemV2(
                                label = "文件夹",
                                icon = Icons.Default.Folder,
                                onClick = {
                                    Toast.makeText(this@MainActivity, "文件夹视图即将开放", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DrawerNavItemV2(
                                label = "设置",
                                icon = Icons.Default.Settings,
                                onClick = onSettings
                            )
                        }

                        DrawerSectionTitleV2("媒体分类")
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            filters.forEach { option ->
                                DrawerCategoryRowV2(
                                    label = option.label,
                                    count = countForFilter(items, option.value),
                                    selected = selectedValue == option.value,
                                    accent = filterAccent(option.value),
                                    onClick = { onSelected(option.value) }
                                )
                            }
                        }
                    }

                    DrawerDividerV2()
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DrawerActionV2(
                            label = if (loading) "正在刷新" else "刷新媒体库",
                            accent = MaterialTheme.colorScheme.primary,
                            icon = Icons.Default.Refresh,
                            enabled = !loading,
                            loading = loading,
                            onClick = onRefresh
                        )
                        DrawerActionV2(
                            label = "退出登录",
                            accent = Color(0xFFFF8CA3),
                            icon = Icons.AutoMirrored.Filled.ExitToApp,
                            subtle = true,
                            onClick = onLogout
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.12f))
                )
            }
        }
    }

    @Composable
    private fun DrawerProfileHeaderV2() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF8EA2FF), Color(0xFF58E6C2))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("HE", color = Color(0xFF070A12), fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                ) {
                    Text(
                        "Native Android",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    greetingText(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "今天想看点什么？",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun DrawerServerCardV2(serverUrl: String, onManage: () -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.060f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF58E6C2))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "本地媒体源",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "在线 · 局域网",
                            color = Color(0xFF58E6C2),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        if (expanded) "收起" else "详情",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180)) + expandVertically(),
                    exit = fadeOut(tween(150)) + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DrawerDividerV2()
                        Text(
                            serverHostV2(serverUrl),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            serverUrl,
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = onManage, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text(
                                "媒体源管理",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DrawerStatsCardV2(
        items: List<MediaItem>,
        selectedStatus: String,
        onStatusSelected: (String) -> Unit
    ) {
        val total = items.size
        val viewing = items.count { it.viewStatus == "viewing" }
        val favorite = items.count { it.favorite }
        val viewed = items.count { it.viewStatus == "viewed" }
        GlassPanelV2(contentPadding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrawerStatCellV2(
                        label = "总计",
                        count = total,
                        accent = MaterialTheme.colorScheme.primary,
                        selected = selectedStatus.isBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = { onStatusSelected("") }
                    )
                    DrawerStatCellV2(
                        label = "继续看",
                        count = viewing,
                        accent = MaterialTheme.colorScheme.primary,
                        selected = selectedStatus == "viewing",
                        modifier = Modifier.weight(1f),
                        onClick = { onStatusSelected("viewing") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DrawerStatCellV2(
                        label = "收藏",
                        count = favorite,
                        accent = Color(0xFFF6C46B),
                        selected = selectedStatus == "favorite",
                        modifier = Modifier.weight(1f),
                        onClick = { onStatusSelected("favorite") }
                    )
                    DrawerStatCellV2(
                        label = "已看完",
                        count = viewed,
                        accent = Color(0xFF58E6C2),
                        selected = selectedStatus == "viewed",
                        modifier = Modifier.weight(1f),
                        onClick = { onStatusSelected("viewed") }
                    )
                }
            }
        }
    }

    @Composable
    private fun DrawerStatCellV2(
        label: String,
        count: Int,
        accent: Color,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val background by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.045f),
            animationSpec = tween(160),
            label = "statCellBg"
        )
        Surface(
            modifier = modifier
                .heightIn(min = 64.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(18.dp),
            color = background,
            border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.36f) else Color.White.copy(alpha = 0.07f))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "$count",
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black
                )
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    @Composable
    private fun DrawerNavItemV2(
        label: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        selected: Boolean = false,
        onClick: () -> Unit
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val background by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent,
            animationSpec = tween(160),
            label = "navItemBg"
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(18.dp),
            color = background,
            border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.05f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold
                )
            }
        }
    }

    @Composable
    private fun DrawerCategoryRowV2(
        label: String,
        count: Int,
        selected: Boolean,
        accent: Color,
        onClick: () -> Unit
    ) {
        val background by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.16f) else Color.Transparent,
            animationSpec = tween(160),
            label = "categoryRowBg"
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(18.dp),
            color = background,
            border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.05f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DrawerDotV2(accent)
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = if (selected) 0.12f else 0.065f)
                ) {
                    Text(
                        "$count",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    private fun DrawerNavRowV2(
        label: String,
        count: Int,
        selected: Boolean,
        accent: Color,
        onClick: () -> Unit
    ) {
        val background by animateColorAsState(
            targetValue = if (selected) accent.copy(alpha = 0.16f) else Color.Transparent,
            animationSpec = tween(160),
            label = "drawerRowBg"
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(22.dp),
            color = background,
            border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.055f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DrawerDotV2(accent)
                Spacer(Modifier.width(11.dp))
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = if (selected) 0.12f else 0.065f)
                ) {
                    Text(
                        "$count",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    @Composable
    private fun LibraryHeaderV2(
        loading: Boolean,
        viewMode: LibraryViewMode,
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
            ViewModeIconButtonV2(viewMode = viewMode, onSelect = onViewModeSelected)
            Spacer(Modifier.width(8.dp))
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
    private fun ViewModeIconButtonV2(
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

    private fun viewModeIconV2(mode: LibraryViewMode) = when (mode) {
        LibraryViewMode.Large -> Icons.Default.ViewModule
        LibraryViewMode.Grid -> Icons.Default.GridView
        LibraryViewMode.Detail -> Icons.AutoMirrored.Filled.ViewList
    }

    private fun viewModeLabelV2(mode: LibraryViewMode) = when (mode) {
        LibraryViewMode.Large -> "大图"
        LibraryViewMode.Grid -> "三列"
        LibraryViewMode.Detail -> "详细"
    }

    @Composable
    private fun SearchAndFilterPanelV2(
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
    private fun QuickFilterRowV2(
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
    private fun QuickFilterChipV2(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
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
    private fun FilterBottomSheetV2(
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
    private fun PillTabV2(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
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
    private fun LibraryViewModeSwitcherV2(
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
    private fun ViewModeButtonV2(
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

    @Composable
    private fun MediaCardV2(
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
            shape = RoundedCornerShape(28.dp),
            actions = { progress ->
                QuickActionsPaneV2(
                    item = item,
                    onAction = { action -> onQuickAction(action) },
                    progress = progress
                )
            }
        ) {
            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = spring(stiffness = 420f, dampingRatio = 0.86f))
                        .graphicsLayer {
                            scaleX = cardScale
                            scaleY = cardScale
                        }
                        .clickable(interactionSource = interactionSource, indication = null, onClick = tapWhileSomethingOpen),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White.copy(alpha = 0.062f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = if (pressed) 0.16f else 0.085f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Box {
                            RemoteCoverV2(
                                url = coverUrl(serverUrl, token, item),
                                label = mediaTypeLabelV2(item.mediaType),
                                accent = accent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.50f)
                            )
                            StatusPillV2(
                                label = mediaTypeLabelV2(item.mediaType),
                                color = accent,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            )
                            if (item.mediaType == "manga" && item.pageCount > 0) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(10.dp),
                                    shape = RoundedCornerShape(999.dp),
                                    color = Color(0xCC0B1020),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                                ) {
                                    Text(
                                        "${item.pageCount}P",
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                            FloatingPlayButtonV2(
                                label = if (item.viewStatus == "viewing") "继续" else "播放",
                                onClick = { playWhileSomethingOpen(false) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(10.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.favorite) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = "收藏",
                                        tint = Color(0xFFF6C46B),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .padding(end = 4.dp)
                                    )
                                }
                                Text(
                                    item.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.viewStatus == "viewed") {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "已看完",
                                        tint = Color(0xFF58E6C2),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .padding(start = 6.dp)
                                    )
                                }
                            }
                            Text(
                                metaInlineV2(item),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            AnimatedVisibility(
                                visible = progress != null,
                                enter = fadeIn(tween(180)) + expandVertically(),
                                exit = fadeOut(tween(160)) + shrinkVertically()
                            ) {
                                LinearProgressIndicator(
                                    progress = { progress ?: 0f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(999.dp)),
                                    color = progressColorV2(item),
                                    trackColor = Color.White.copy(alpha = 0.075f)
                                )
                            }
                            if (item.missing || (item.mediaType == "manga" && (item.viewStatus == "viewing" || item.viewStatus == "viewed"))) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (item.missing) StatusPillV2("文件缺失", Color(0xFFFF8CA3))
                                    if (item.mediaType == "manga" && (item.viewStatus == "viewing" || item.viewStatus == "viewed")) {
                                        ActionPillV2("从头观看", MaterialTheme.colorScheme.primary) {
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
    }

    private class SwipeRevealController {
        var openKey by mutableStateOf<Any?>(null)
            private set
        var isDragging by mutableStateOf(false)
        fun open(key: Any) { openKey = key }
        fun close() { openKey = null }
        fun startDrag() { isDragging = true }
        fun endDrag() { isDragging = false }
    }

    private enum class SwipeState { Closed, Dragging, Settling, Opened }

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
    private fun SwipeRevealCard(
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
    private fun QuickActionsPaneV2(
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
    private fun QuickActionTileV2(
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
    private fun GridQuickActionsOverlayV2(
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
    private fun QuickActionCircleV2(
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
    private fun MediaGridTileV2(
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
            Surface(
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
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.052f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.075f))
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    Box {
                        RemoteCoverV2(
                            url = coverUrl(serverUrl, token, item),
                            label = mediaTypeLabelV2(item.mediaType),
                            accent = accent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.70f)
                        )
                        TinyBadgeV2(
                            label = mediaTypeLabelV2(item.mediaType),
                            color = accent,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(7.dp)
                        )
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = progressColorV2(item),
                                trackColor = Color.Black.copy(alpha = 0.38f)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        metaInlineV2(item),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

    @Composable
    private fun MediaDetailRowV2(
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
            shape = RoundedCornerShape(24.dp),
            actionsWidth = 156.dp,
            actions = { progress ->
                QuickActionsPaneV2(
                    item = item,
                    onAction = { action -> onQuickAction(action) },
                    progress = progress
                )
            }
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = {
                    if (controller.openKey != null) controller.close() else onOpen(false)
                }),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.056f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.078f))
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RemoteCoverV2(
                    url = coverUrl(serverUrl, token, item),
                    label = mediaTypeLabelV2(item.mediaType),
                    accent = accent,
                    modifier = Modifier
                        .width(78.dp)
                        .height(108.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TinyBadgeV2(mediaTypeLabelV2(item.mediaType), accent)
                        mediaMetaChipsV2(item).take(2).forEach { chip ->
                            TinyBadgeV2(chip, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.favorite) TinyBadgeV2("收藏", Color(0xFFF6C46B))
                        if (item.missing) TinyBadgeV2("缺失", Color(0xFFFF8CA3))
                    }
                    Text(
                        item.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = progressColorV2(item),
                            trackColor = Color.White.copy(alpha = 0.075f)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            progressTextV2(item),
                            modifier = Modifier.weight(1f),
                            color = progressColorV2(item),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.mediaType == "manga" && (item.viewStatus == "viewing" || item.viewStatus == "viewed")) {
                            ActionPillV2("从头", MaterialTheme.colorScheme.primary) {
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
    private fun TinyBadgeV2(label: String, color: Color, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(999.dp),
            color = color.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.20f))
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun RemoteCoverV2(url: String?, label: String, accent: Color, modifier: Modifier = Modifier) {
        val bitmap by produceState<Bitmap?>(initialValue = cachedCover(url), key1 = url) {
            if (url.isNullOrBlank() || value != null) return@produceState
            value = withContext(Dispatchers.IO) { loadCoverBitmap(url) }
        }

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.32f),
                            Color(0xFF101623),
                            Color(0xFF090C14)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.085f), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    label,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.02f),
                                Color.Black.copy(alpha = 0.16f),
                                Color.Black.copy(alpha = 0.66f)
                            )
                        )
                    )
            )
        }
    }

    @Composable
    private fun FloatingPlayButtonV2(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.clickable(onClick = onClick),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
            contentColor = Color(0xFF070A12),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
            }
        }
    }

    @Composable
    private fun EmptyStateV2() {
        GlassPanelV2 {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 42.dp, horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Text("没有匹配的媒体", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(7.dp))
                Text(
                    "换个关键词、类型或状态再探索一下",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun ErrorPanelV2(error: String, loading: Boolean, onRetry: () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFFF8CA3).copy(alpha = 0.12f),
            border = BorderStroke(1.dp, Color(0xFFFF8CA3).copy(alpha = 0.24f))
        ) {
            Row(
                modifier = Modifier.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("加载失败", color = Color(0xFFFFB7C4), fontWeight = FontWeight.Black)
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onRetry,
                    enabled = !loading,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8CA3), contentColor = Color(0xFF220912)),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)
                ) {
                    Text("重试", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun LoadingLineV2() {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color.White.copy(alpha = 0.055f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.085f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(11.dp))
                Text("正在刷新媒体库", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun LoadingCardSkeletonV2(index: Int) {
        val transition = rememberInfiniteTransition(label = "skeletonV2$index")
        val alpha by transition.animateFloat(
            initialValue = 0.20f,
            targetValue = 0.52f,
            animationSpec = infiniteRepeatable(
                animation = tween(860, delayMillis = index * 90, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "skeletonV2Alpha$index"
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.052f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.075f))
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBlockV2(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.50f),
                    alpha
                )
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SkeletonBlockV2(Modifier.fillMaxWidth(0.78f).height(22.dp), alpha)
                    SkeletonBlockV2(Modifier.fillMaxWidth().height(5.dp), alpha * 0.68f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlockV2(Modifier.width(64.dp).height(28.dp), alpha * 0.78f)
                        SkeletonBlockV2(Modifier.width(78.dp).height(28.dp), alpha * 0.62f)
                    }
                }
            }
        }
    }

    @Composable
    private fun SkeletonBlockV2(modifier: Modifier, alpha: Float) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.045f),
                            MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.22f),
                            Color.White.copy(alpha = 0.045f)
                        )
                    )
                )
        )
    }

    @Composable
    private fun GlassPanelV2(
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
    private fun IconPillV2(
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
    private fun StatusPillV2(label: String, color: Color, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(999.dp),
            color = color.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun SubtleChipV2(label: String) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color.White.copy(alpha = 0.055f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.070f))
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun ActionPillV2(label: String, color: Color, onClick: () -> Unit) {
        Surface(
            modifier = Modifier.clickable(onClick = onClick),
            shape = RoundedCornerShape(999.dp),
            color = color.copy(alpha = 0.13f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.22f))
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }

    @Composable
    private fun DrawerActionV2(
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
    private fun DrawerSectionTitleV2(label: String) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }

    @Composable
    private fun DrawerDividerV2() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.085f))
        )
    }

    @Composable
    private fun DrawerDotV2(color: Color) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color)
        )
    }

    @Composable
    private fun AppBackgroundBrushV2(): Brush {
        return Brush.radialGradient(
            listOf(
                Color(0xFF17213C),
                Color(0xFF0B1020),
                Color(0xFF070A12)
            ),
            radius = 1250f
        )
    }

    @Composable
    private fun statusAccentV2(value: String): Color {
        return when (value) {
            "viewing" -> MaterialTheme.colorScheme.primary
            "favorite" -> Color(0xFFF6C46B)
            "viewed" -> Color(0xFF58E6C2)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    @Composable
    private fun progressColorV2(item: MediaItem): Color {
        return when {
            item.missing -> Color(0xFFFF8CA3)
            item.viewStatus == "viewed" -> Color(0xFF58E6C2)
            item.viewStatus == "viewing" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    private fun matchesStatusV2(item: MediaItem, value: String): Boolean {
        return when (value) {
            "viewing" -> item.viewStatus == "viewing"
            "favorite" -> item.favorite
            "viewed" -> item.viewStatus == "viewed"
            else -> true
        }
    }

    private fun countForStatusV2(items: List<MediaItem>, value: String): Int {
        return items.count { matchesStatusV2(it, value) }
    }

    private fun serverHostV2(serverUrl: String): String {
        return ApiClient.trimSlash(serverUrl)
            .removePrefix("http://")
            .removePrefix("https://")
    }

    private fun mediaTypeLabelV2(type: String?): String {
        return when (type) {
            "video" -> "视频"
            "manga" -> "漫画"
            "image" -> "图片"
            else -> "媒体"
        }
    }

    private fun mediaMetaChipsV2(item: MediaItem): List<String> {
        return listOf(
            cleanExtensionV2(item.extension),
            item.duration.takeIf { it > 0 }?.let { formatDuration(it) } ?: "",
            item.pageCount.takeIf { it > 0 }?.let { "${it} 页" } ?: "",
            item.rating.takeIf { it > 0 }?.let { "${it} 星" } ?: ""
        ).filter { it.isNotBlank() }
    }

    private fun cleanExtensionV2(extension: String?): String {
        val raw = extension?.takeIf { it.isNotBlank() && it != "null" } ?: return ""
        val normalized = raw.lowercase(Locale.ROOT).removePrefix(".")
        if (normalized == "dir") return ""
        return raw.uppercase(Locale.ROOT)
    }

    private fun metaInlineV2(item: MediaItem): String {
        val parts = mutableListOf<String>()
        parts += mediaTypeLabelV2(item.mediaType)
        cleanExtensionV2(item.extension).takeIf { it.isNotBlank() }?.let { parts += it }
        if (item.duration > 0) parts += formatDuration(item.duration)
        if (item.pageCount > 0) parts += "${item.pageCount}P"
        parts += progressTextV2(item)
        return parts.joinToString(" · ")
    }

    private fun progressTextV2(item: MediaItem): String {
        if (item.viewStatus == "viewed") return "已看完"
        if (item.viewStatus == "viewing") {
            if (item.mediaType == "manga" && item.pageCount > 0) {
                return "第 ${minOf(item.progress + 1, item.pageCount)} / ${item.pageCount} 页"
            }
            if (item.mediaType == "video" && item.progress > 0) {
                return "看到 ${formatDuration(item.progress)}"
            }
            return "继续看"
        }
        return "未观看"
    }

    private fun openItem(item: MediaItem, serverUrl: String, token: String, restart: Boolean = false) {
        val target = if (item.mediaType == "video") PlayerActivity::class.java else MangaActivity::class.java
        startActivity(Intent(this, target).apply {
            putExtra("server_url", serverUrl)
            putExtra("token", token)
            putExtra("id", item.id)
            putExtra("title", item.title)
            putExtra("media_type", item.mediaType)
            putExtra("progress", item.progress)
            putExtra("duration", item.duration)
            putExtra("page_count", item.pageCount)
            if (restart) putExtra("restart", true)
        })
    }

    private fun coverUrl(serverUrl: String, token: String, item: MediaItem): String? {
        val cover = item.coverPath
        if (cover.isNullOrBlank() || cover == "null") return null
        return "$serverUrl/mobile/thumbnails/$cover?${ApiClient.tokenQuery(token)}"
    }

    private fun cachedCover(url: String?): Bitmap? {
        return if (url.isNullOrBlank()) null else coverCache.get(url)
    }

    private suspend fun loadCoverBitmap(url: String): Bitmap? {
        coverCache.get(url)?.let { return it }
        val deferred = coverInFlightLock.withLock {
            coverCache.get(url)?.let { return it }
            coverInFlight[url]?.let { return@withLock it }
            val newDeferred = CompletableDeferred<Bitmap?>()
            coverInFlight[url] = newDeferred
            null
        }
        if (deferred != null) return deferred.await()

        val owned = coverInFlight[url]!!
        val result = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "image/*,*/*")
            try {
                val bytes = conn.inputStream.use { it.readBytes() }
                val bitmap = decodeSampledBitmap(bytes, 240, 340)
                if (bitmap != null) coverCache.put(url, bitmap)
                bitmap
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        coverInFlightLock.withLock { coverInFlight.remove(url) }
        owned.complete(result)
        return result
    }

    private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun greetingText(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            in 18..23 -> "晚上好"
            else -> "夜深了"
        }
    }

    private fun countForFilter(items: List<MediaItem>, value: String): Int {
        return if (value.isBlank()) items.size else items.count { it.mediaType == value }
    }

    private fun summary(items: List<MediaItem>): String {
        val videos = items.count { it.mediaType == "video" }
        val manga = items.count { it.mediaType == "manga" }
        val images = items.count { it.mediaType == "image" }
        return listOf(
            "${items.size}个条目",
            if (videos > 0) "${videos}视频" else "",
            if (manga > 0) "${manga}漫画" else "",
            if (images > 0) "${images}图片" else ""
        ).filter { it.isNotBlank() }.joinToString(" / ")
    }

    private fun meta(item: MediaItem): String {
        return listOf(
            typeLabel(item.mediaType),
            item.extension?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "",
            item.duration.takeIf { it > 0 }?.let { formatDuration(it) } ?: "",
            item.pageCount.takeIf { it > 0 }?.let { "${it}页" } ?: "",
            if (item.favorite) "收藏" else "",
            item.rating.takeIf { it > 0 }?.let { "${it}星" } ?: ""
        ).filter { it.isNotBlank() }.joinToString(" / ")
    }

    private fun progressText(item: MediaItem): String {
        if (item.viewStatus == "viewed") return "已看完"
        if (item.viewStatus == "viewing") {
            if (item.mediaType == "manga" && item.pageCount > 0) {
                return "第 ${minOf(item.progress + 1, item.pageCount)} / ${item.pageCount} 页"
            }
            if (item.mediaType == "video" && item.progress > 0) {
                return "看到 ${formatDuration(item.progress)}"
            }
            return "正在看"
        }
        return "未观看"
    }

    private fun progressFraction(item: MediaItem): Float? {
        if (item.viewStatus == "viewed") return 1f
        if (item.viewStatus != "viewing") return null
        val fraction = when {
            item.mediaType == "manga" && item.pageCount > 0 -> (item.progress + 1).toFloat() / item.pageCount.toFloat()
            item.mediaType == "video" && item.duration > 0 -> item.progress.toFloat() / item.duration.toFloat()
            else -> return null
        }
        return fraction.coerceIn(0.02f, 1f)
    }

    @Composable
    private fun filterAccent(value: String): Color {
        return when (value) {
            "video" -> MaterialTheme.colorScheme.secondary
            "manga" -> MaterialTheme.colorScheme.tertiary
            "image" -> Color(0xFFFF8FA3)
            else -> MaterialTheme.colorScheme.primary
        }
    }

    @Composable
    private fun typeAccent(type: String?): Color {
        return when (type) {
            "video" -> MaterialTheme.colorScheme.secondary
            "manga" -> MaterialTheme.colorScheme.tertiary
            "image" -> Color(0xFFFF8FA3)
            else -> MaterialTheme.colorScheme.primary
        }
    }

    @Composable
    private fun progressColor(item: MediaItem): Color {
        return when {
            item.missing -> Color(0xFFFF8FA3)
            item.viewStatus == "viewed" -> MaterialTheme.colorScheme.secondary
            item.viewStatus == "viewing" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    private fun readableError(error: Throwable): String {
        val message = error.message ?: return "读取失败"
        return when {
            message.contains("Failed to connect", ignoreCase = true) -> "无法连接服务器，请确认电脑端服务已启动"
            message.contains("timeout", ignoreCase = true) -> "连接超时，请检查网络或服务器地址"
            else -> message
        }
    }

    private fun typeLabel(type: String?): String {
        return when (type) {
            "video" -> "视频"
            "manga" -> "漫画"
            "image" -> "图片"
            else -> "媒体"
        }
    }

    private fun formatDuration(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }
}
