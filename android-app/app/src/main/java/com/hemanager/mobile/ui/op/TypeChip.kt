package com.hemanager.mobile.ui.op

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemanager.mobile.ui.theme.CutCornerShape
import com.hemanager.mobile.ui.theme.GeistMono
import com.hemanager.mobile.ui.theme.HeColors

/**
 * 媒体类型小药丸：黑底切角 + 黄色 mono code。
 *
 * 映射：video → VID / manga → MNG / image → IMG / audio → AUD / 其他 → MED。
 */
@Composable
fun TypeChip(
    mediaType: String,
    modifier: Modifier = Modifier,
    onYellow: Boolean = false,
) {
    val code = when (mediaType) {
        "video" -> "VID"
        "manga" -> "MNG"
        "image" -> "IMG"
        "audio" -> "AUD"
        else -> "MED"
    }
    val bg = if (onYellow) HeColors.Yellow else Color.Black.copy(alpha = 0.78f)
    val fg = if (onYellow) HeColors.OnYellow else HeColors.Yellow
    Box(
        modifier = modifier
            .clip(CutCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = code,
            color = fg,
            fontFamily = GeistMono,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
    }
}
