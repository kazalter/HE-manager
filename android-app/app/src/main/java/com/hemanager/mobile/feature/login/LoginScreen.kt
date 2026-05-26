package com.hemanager.mobile.feature.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.ui.op.CodeChip
import com.hemanager.mobile.ui.op.Diamond
import com.hemanager.mobile.ui.op.CtaSize
import com.hemanager.mobile.ui.op.OpTitle
import com.hemanager.mobile.ui.op.Slash
import com.hemanager.mobile.ui.op.YellowCTA
import com.hemanager.mobile.ui.theme.CutCornerShape
import com.hemanager.mobile.ui.theme.GeistMono
import com.hemanager.mobile.ui.theme.HeColors
import com.hemanager.mobile.ui.theme.NotoSansSC
import com.hemanager.mobile.ui.theme.Oxanium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 登录界面（HE OP 终端美学版）。
 *
 * 状态完全本地，登录成功通过 [onLoggedIn] 回调把 (serverUrl, token) 交还给调用者，
 * 持久化由调用者负责。
 *
 * 「创建第一个管理员」走 `/auth/bootstrap`，普通登录走 `/auth/login`。
 */
@Composable
fun LoginScreen(
    initialServer: String,
    onLoggedIn: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }

    var server by remember { mutableStateOf(if (initialServer.isBlank()) "http://" else initialServer) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun submit(bootstrap: Boolean) {
        val targetServer = ApiClient.trimSlash(server)
        if (!targetServer.startsWith("http://") && !targetServer.startsWith("https://")) {
            message = "// ERR · 服务器地址需以 http:// 或 https:// 开头"
            return
        }
        if (username.isBlank() || password.isBlank()) {
            message = "// ERR · 请填写用户名和密码"
            return
        }
        focusManager.clearFocus()
        busy = true
        message = "// LINK · 正在连接终端..."
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
            result.onFailure { message = "// ERR · " + (it.message ?: "登录失败") }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HeColors.Void),
    ) {
        // ---- 装饰层 ----
        BackgroundGrid()
        DecorativeHeadline()
        TopYellowStrip()

        // ---- 主内容 ----
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 34.dp)
                .widthIn(max = 560.dp),
        ) {
            // Logo row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Diamond(10.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "HE INDUSTRIES",
                    color = HeColors.OpWhite,
                    fontFamily = Oxanium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.weight(1f))
                CodeChip("v0.4.2 · ARCHIVE")
            }
            Spacer(Modifier.height(56.dp))

            // 标题
            Slash(cn = "连接终端", en = "Connect")
            Spacer(Modifier.height(14.dp))
            OpTitle(cn = "HE MANAGER", en = "OPERATOR ARCHIVE TERMINAL", sizeSp = 38.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "将电脑里的视频、漫画与影像，\n以更舒服的方式抵达手机。",
                color = HeColors.OpWhiteSoft,
                fontFamily = NotoSansSC,
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
            )

            Spacer(Modifier.height(40.dp))

            // 表单
            TerminalField(
                label = "SERVER",
                labelCN = "服务器",
                code = "ENDPOINT-01",
                value = server,
                onValueChange = { server = it },
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                onImeAction = { userFocus.requestFocus() },
            )
            Spacer(Modifier.height(18.dp))
            TerminalField(
                label = "OPERATOR",
                labelCN = "账号",
                code = "USER-01",
                value = username,
                onValueChange = { username = it },
                imeAction = ImeAction.Next,
                onImeAction = { passFocus.requestFocus() },
                focusRequester = userFocus,
            )
            Spacer(Modifier.height(18.dp))
            TerminalField(
                label = "PASS-KEY",
                labelCN = "密码",
                code = "AUTH-01",
                value = password,
                onValueChange = { password = it },
                password = true,
                imeAction = ImeAction.Done,
                onImeAction = { submit(false) },
                focusRequester = passFocus,
            )

            Spacer(Modifier.height(22.dp))

            // 主 CTA
            Box(Modifier.fillMaxWidth()) {
                if (busy) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(CutCornerShape(10.dp))
                            .background(HeColors.YellowDim),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = HeColors.OnYellow,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "CONNECTING",
                                color = HeColors.OnYellow,
                                fontFamily = Oxanium,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.5.sp,
                            )
                        }
                    }
                } else {
                    YellowCTA(
                        text = "CONNECT · 登录",
                        onClick = { submit(false) },
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        size = CtaSize.Large,
                        fullWidth = true,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 次级 link
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { submit(true) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "// INIT FIRST ADMIN  →",
                    color = HeColors.OpWhiteSoft,
                    fontFamily = Oxanium,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.6.sp,
                )
            }

            // 状态信息
            AnimatedVisibility(
                visible = message.isNotBlank(),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(160)),
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    color = if (message.startsWith("// ERR")) HeColors.OpDanger else HeColors.OpWhiteMuted,
                    fontFamily = GeistMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.4.sp,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun TerminalField(
    label: String,
    labelCN: String,
    code: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "//",
                color = HeColors.OpWhiteMuted,
                fontFamily = GeistMono,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(
                text = label,
                color = HeColors.Yellow,
                fontFamily = Oxanium,
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                letterSpacing = 2.sp,
            )
            Text(
                text = labelCN,
                color = HeColors.OpWhiteMuted,
                fontFamily = NotoSansSC,
                fontWeight = FontWeight.Medium,
                fontSize = 9.5.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            CodeChip(code, color = HeColors.OpWhiteFaint)
        }
        Spacer(Modifier.height(6.dp))
        val shape = CutCornerShape(8.dp)
        val fieldModifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HeColors.Ink.copy(alpha = 0.92f))
            .border(1.dp, HeColors.HairlineMid, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = HeColors.OpWhite,
                fontFamily = GeistMono,
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp,
                letterSpacing = if (password) 4.sp else 0.2.sp,
            ),
            cursorBrush = SolidColor(HeColors.Yellow),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onNext = { onImeAction() },
                onDone = { onImeAction() },
                onGo = { onImeAction() },
            ),
        )
    }
}

/** 背景 32dp 网格——极淡 hairline。 */
@Composable
private fun BackgroundGrid() {
    val gridColor = Color.White.copy(alpha = 0.022f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 32.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}

/** 左上巨型 HE/ARCH 装饰字。 */
@Composable
private fun DecorativeHeadline() {
    Text(
        text = "HE\nARCH",
        color = Color.White.copy(alpha = 0.018f),
        fontFamily = Oxanium,
        fontWeight = FontWeight.Bold,
        fontSize = 200.sp,
        letterSpacing = (-8).sp,
        lineHeight = 160.sp,
        modifier = Modifier.padding(start = 0.dp, top = 0.dp),
    )
}

/** 顶部 4dp 黄色 strip，只覆盖左侧 20%。 */
@Composable
private fun TopYellowStrip() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(
                Brush.horizontalGradient(
                    0f to HeColors.Yellow,
                    0.2f to HeColors.Yellow,
                    0.20001f to Color.Transparent,
                    1f to Color.Transparent,
                )
            ),
    )
}
