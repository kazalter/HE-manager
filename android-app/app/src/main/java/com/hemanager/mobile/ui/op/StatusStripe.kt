package com.hemanager.mobile.ui.op

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemanager.mobile.ui.theme.GeistMono
import com.hemanager.mobile.ui.theme.HeColors

/**
 * 底部状态条：黑底 + GeistMono 服务器地址 + ping + UID。
 *
 * 出现在所有屏幕底部（登录页除外）。
 */
@Composable
fun StatusStripe(
    server: String = "192.168.1.23:8010",
    ping: String = "12ms",
    uid: String = "1416-176-661",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(HeColors.Void)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "//",
            color = HeColors.Yellow,
            fontFamily = GeistMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
        )
        Text(
            text = "HE://$server",
            color = HeColors.OpWhiteMuted,
            fontFamily = GeistMono,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            letterSpacing = 0.6.sp,
        )
        Box(
            modifier = Modifier
                .size(1.dp, 10.dp)
                .background(HeColors.HairlineMid)
        )
        Text(
            text = "● $ping",
            color = HeColors.Online,
            fontFamily = GeistMono,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "UID:$uid",
            color = HeColors.OpWhiteMuted,
            fontFamily = GeistMono,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
        )
    }
}
