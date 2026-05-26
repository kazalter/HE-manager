package com.hemanager.mobile.feature.library

// HE OP HeroFeature + QueueRow — 主屏顶部「正在看 + 队列」区块。
// 只在 mediaFilter / statusFilter 全空 且 有 viewing 项时显示，避免和筛选模式冲突。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemanager.mobile.MediaItem
import com.hemanager.mobile.ui.op.CodeChip
import com.hemanager.mobile.ui.op.CtaSize
import com.hemanager.mobile.ui.op.GhostCta
import com.hemanager.mobile.ui.op.HudBrackets
import com.hemanager.mobile.ui.op.ProgressO
import com.hemanager.mobile.ui.op.Slash
import com.hemanager.mobile.ui.op.TypeChip
import com.hemanager.mobile.ui.op.YellowCTA
import com.hemanager.mobile.ui.op.YellowCornerSeal
import com.hemanager.mobile.ui.theme.GeistMono
import com.hemanager.mobile.ui.theme.HeColors
import com.hemanager.mobile.ui.theme.NotoSansSC
import com.hemanager.mobile.ui.theme.Oxanium

/**
 * 主屏首屏 Hero：展示当前正在看的 item，全宽 460dp。
 *
 * 视觉构成：
 *   - 全宽封面背景（ContentScale.Crop） + 底部 ink 渐变保证文字对比
 *   - 4 角 HudBrackets registration marks
 *   - 左上 28×3dp 黄短线 + 右上 fakeCode CodeChip
 *   - 底部 stack：Slash + OpTitle + meta + ProgressO + CTA 行
 */
@Composable
internal fun HeroFeature(
    serverUrl: String,
    token: String,
    item: MediaItem,
    onContinue: (Boolean) -> Unit,
    onToggleStar: () -> Unit,
) {
    val accent = typeAccent(item.mediaType)
    val progress = progressFraction(item)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(460.dp)
            .clip(com.hemanager.mobile.ui.theme.CutCornerShape(14.dp))
            .background(HeColors.Panel),
    ) {
        // 封面层
        RemoteCoverV2(
            url = coverUrl(serverUrl, token, item),
            label = mediaTypeLabelV2(item.mediaType),
            accent = accent,
            decodeWidthPx = 640,
            decodeHeightPx = 920,
            cutDp = 0.dp,
            modifier = Modifier.fillMaxSize(),
        )
        // 自上向下的 ink 渐变，让标题和按钮可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f   to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f   to HeColors.Ink.copy(alpha = 0.96f),
                    )
                )
        )
        // HUD marks
        HudBrackets(modifier = Modifier.fillMaxSize(), inset = 10.dp, length = 14.dp)

        // 顶部装饰：左 28×3 黄短线 + 右 CodeChip
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, end = 18.dp, top = 22.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .background(HeColors.Yellow)
            )
            Spacer(Modifier.weight(1f))
            CodeChip(text = fakeCode(item), color = HeColors.Yellow)
        }

        // 底部内容栈
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Slash(cn = "正在看", en = "Now Streaming")
            Text(
                item.title,
                color = HeColors.OpWhite,
                fontFamily = NotoSansSC,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.5).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            // meta 行
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(mediaType = item.mediaType)
                Spacer(Modifier.width(8.dp))
                if (item.extension.isNotBlank()) {
                    Text(
                        item.extension.uppercase(),
                        color = HeColors.OpWhiteSoft,
                        fontFamily = Oxanium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.6.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    progressTextV2(item),
                    color = HeColors.OpWhiteSoft,
                    fontFamily = GeistMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.3.sp,
                )
            }
            if (progress != null) {
                ProgressO(value = progress, height = 3.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YellowCTA(
                    text = if (item.viewStatus == "viewing") "CONTINUE · 继续" else "PLAY · 播放",
                    onClick = { onContinue(false) },
                    icon = Icons.Default.PlayArrow,
                    size = CtaSize.Medium,
                )
                GhostCta(
                    text = if (item.favorite) "STARRED" else "STAR",
                    onClick = onToggleStar,
                    icon = if (item.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    size = CtaSize.Medium,
                )
            }
        }
    }
}

/**
 * 队列横滑：紧凑 130dp 宽 tile，封面 2:3，TR 黄角，左上索引数字，右下 TypeChip，底部 ProgressO。
 */
@Composable
internal fun QueueRow(
    serverUrl: String,
    token: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    Column {
        Slash(cn = "队列", en = "Queue", modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEachIndexed { index, item ->
                QueueTile(
                    serverUrl = serverUrl,
                    token = token,
                    item = item,
                    index = index + 1,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun QueueTile(
    serverUrl: String,
    token: String,
    item: MediaItem,
    index: Int,
    onClick: () -> Unit,
) {
    val progress = progressFraction(item)
    val shape = com.hemanager.mobile.ui.theme.CutCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(130.dp)
            .clip(shape)
            .background(HeColors.Panel)
            .clickable(onClick = onClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            ) {
                RemoteCoverV2(
                    url = coverUrl(serverUrl, token, item),
                    label = mediaTypeLabelV2(item.mediaType),
                    accent = typeAccent(item.mediaType),
                    decodeWidthPx = 260,
                    decodeHeightPx = 390,
                    cutDp = 0.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                YellowCornerSeal(size = 8.dp, modifier = Modifier.align(Alignment.TopEnd))
                // 索引数字 01/02/...
                CodeChip(
                    text = index.toString().padStart(2, '0'),
                    color = HeColors.OpWhite,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                )
                TypeChip(
                    mediaType = item.mediaType,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                )
                if (progress != null) {
                    ProgressO(
                        value = progress,
                        height = 2.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                item.title,
                color = HeColors.OpWhite,
                fontFamily = NotoSansSC,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
