package com.hemanager.mobile.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Error overlay: takes over the video region (not the whole screen — this lets the user
 * still see and use the info section in portrait mode while diagnosing).
 *
 * Spec-mandated actions: 重试 / 查看文件信息 / 返回 / 尝试播放下一个 / 重新扫描该文件.
 * Phase 1 wires the first three; "next video" and "rescan" become live in Phase 2/4
 * once the playlist controller and rescan endpoint are hooked up.
 */
@Composable
fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onShowFileInfo: () -> Unit,
    onBack: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onRescan: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(20.dp))
                .background(PlayerColors.Surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = PlayerColors.Danger,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "播放失败",
                color = PlayerColors.OnSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message.ifBlank { "文件可能已移动、损坏或格式不支持" },
                color = PlayerColors.OnSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PlayerColors.Primary,
                        contentColor = Color(0xFF080B12),
                    ),
                ) {
                    Text("重试")
                }
                OutlinedButton(
                    onClick = onShowFileInfo,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("文件信息")
                }
            }
            if (onPlayNext != null || onRescan != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onPlayNext != null) {
                        TextButton(onClick = onPlayNext, modifier = Modifier.weight(1f)) {
                            Text("下一个", color = PlayerColors.OnSurfaceVariant)
                        }
                    }
                    if (onRescan != null) {
                        TextButton(onClick = onRescan, modifier = Modifier.weight(1f)) {
                            Text("重新扫描", color = PlayerColors.OnSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = onBack) {
                Text("返回", color = PlayerColors.OnSurfaceVariant)
            }
        }
    }
}
