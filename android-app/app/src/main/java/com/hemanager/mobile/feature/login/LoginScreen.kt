package com.hemanager.mobile.feature.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.ui.components.BrandMark
import com.hemanager.mobile.ui.components.GlassPanel
import com.hemanager.mobile.ui.components.ModernTextField
import com.hemanager.mobile.ui.theme.AppBackgroundBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 登录界面：服务器地址 + 用户名 + 密码。
 *
 * 状态完全本地，登录成功通过 [onLoggedIn] 回调把 (serverUrl, token) 交还给调用者，
 * 持久化（SharedPreferences）由调用者负责，本屏不感知存储。
 *
 * 「创建第一个管理员」走 `/auth/bootstrap`，普通登录走 `/auth/login`。
 */
@Composable
fun LoginScreen(
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
