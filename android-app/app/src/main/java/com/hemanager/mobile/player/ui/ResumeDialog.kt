package com.hemanager.mobile.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/**
 * "Continue from {time} or restart?" dialog. Shown only when the persisted progress is
 * close to the end (≥85%) — a casual continue is auto-applied by the VM in all other cases.
 *
 * The button labels match the spec wording exactly: "从头播放" / "继续播放".
 */
@Composable
fun ResumeDialog(
    resumeAtSeconds: Int,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
) {
    Dialog(
        onDismissRequest = onContinue,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(PlayerColors.Surface)
                .padding(24.dp),
        ) {
            Text(
                text = "上次看到 ${formatSeconds(resumeAtSeconds)}",
                color = PlayerColors.OnSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "已经接近结尾，要从头开始还是继续上次进度？",
                color = PlayerColors.OnSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("从头播放")
                }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PlayerColors.Primary,
                        contentColor = Color(0xFF080B12),
                    ),
                ) {
                    Text("继续播放")
                }
            }
        }
    }
}

private fun formatSeconds(seconds: Int): String {
    if (seconds <= 0) return "00:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}
