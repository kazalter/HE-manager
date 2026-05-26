package com.hemanager.mobile.ui.op

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hemanager.mobile.ui.theme.CutCornerShape
import com.hemanager.mobile.ui.theme.HeColors

/**
 * 切角方形头像（不要做成圆形）。
 *
 * @param ring true 时外圈是黄色高亮（用于自己的 avatar），false 时是 HairlineMid 灰描边。
 */
@Composable
fun OpAvatar(
    modelUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ring: Boolean = false,
    contentDescription: String? = null,
) {
    val cut = (size.value * 0.16f).dp
    val shape = CutCornerShape(cut)
    val ringColor: Color = if (ring) HeColors.Yellow else HeColors.HairlineMid

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(HeColors.Panel)
            .border(1.5.dp, ringColor, shape),
    ) {
        if (modelUrl != null) {
            AsyncImage(
                model = modelUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(shape),
            )
        }
    }
}
