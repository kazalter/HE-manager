package com.hemanager.mobile.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemanager.mobile.MediaItem
import com.hemanager.mobile.player.model.PlayerUiState
import java.util.Locale

/**
 * Portrait-mode info section. Mirrors the rounded-card style used elsewhere in the app
 * (see MainActivity.cardSurface).
 *
 * Phase 1 surfaces: title, view-status pill, tags chip row, favorite / add-tag /
 * mark-watched action row, file info (extension + duration + progress %), and stub cards
 * for "same folder" and "current playlist" — these latter two are wired with real data
 * in Phase 2.
 *
 * Author / source / library / file path / file size / resolution: backend already stores
 * those, but they aren't yet on the client [MediaItem]. Phase 2 extends MediaItem to
 * read them; Phase 1 hides those rows so we don't show empty placeholders.
 */
@Composable
fun PortraitInfoSection(
    state: PlayerUiState,
    onToggleFavorite: () -> Unit,
    onAddTag: () -> Unit,
    onToggleWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = state.media
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header card: title + status pills.
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = state.title.ifEmpty { "未命名" },
                    color = PlayerColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(progressLabel(state, media))
                    if (media?.favorite == true) StatusPill("收藏", PlayerColors.Tertiary)
                    if (media?.viewStatus == "viewed") StatusPill("已看完", PlayerColors.Secondary)
                    if (media?.missing == true) StatusPill("文件缺失", PlayerColors.Danger)
                }
            }
        }

        // Action row: favorite / add tag / mark watched.
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ActionTile(
                    icon = if (media?.favorite == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                    tint = if (media?.favorite == true) PlayerColors.Tertiary else PlayerColors.OnSurface,
                    label = if (media?.favorite == true) "已收藏" else "收藏",
                    onClick = onToggleFavorite,
                )
                ActionTile(
                    icon = Icons.Filled.AddCircleOutline,
                    tint = PlayerColors.OnSurface,
                    label = "添加标签",
                    onClick = onAddTag,
                )
                ActionTile(
                    icon = if (media?.viewStatus == "viewed") Icons.Filled.CheckCircle else Icons.Filled.Visibility,
                    tint = if (media?.viewStatus == "viewed") PlayerColors.Secondary else PlayerColors.OnSurface,
                    label = if (media?.viewStatus == "viewed") "已看完" else "标记已看",
                    onClick = onToggleWatched,
                )
            }
        }

        // Tags.
        if (media != null && media.tags.isNotEmpty()) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("标签", color = PlayerColors.OnSurfaceVariant, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        media.tags.take(8).forEach { tag ->
                            TagChip(tag.name)
                        }
                    }
                }
            }
        }

        // File info.
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow(label = "类型", value = "视频")
                InfoRow(label = "格式", value = (media?.extension ?: "").trimStart('.').ifEmpty { "—" })
                InfoRow(label = "时长", value = formatDurationSeconds(media?.duration ?: (state.durationMs / 1000L).toInt()))
                InfoRow(
                    label = "播放进度",
                    value = "${(state.progressFraction * 100).toInt()} %",
                )
            }
        }

        // Same-folder list placeholder.
        StubListCard(
            icon = Icons.Filled.Folder,
            title = "同目录视频",
            hint = "Phase 2 接入后这里会列出当前视频所在文件夹下的其它视频",
        )

        // Current playlist placeholder.
        StubListCard(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = "当前播放列表",
            hint = "Phase 2 会按你打开播放器时所在的来源列表加载，并在这里高亮当前正在播的视频",
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PlayerColors.Surface)
            .border(width = 1.dp, color = PlayerColors.SurfaceVariant, shape = RoundedCornerShape(18.dp)),
    ) {
        content()
    }
}

@Composable
private fun StatusPill(text: String, accent: Color = PlayerColors.Primary) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TagChip(name: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(PlayerColors.PrimaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = name, color = PlayerColors.OnSurface, fontSize = 12.sp)
    }
}

@Composable
private fun ActionTile(
    icon: ImageVector,
    tint: Color,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint)
        Text(text = label, color = PlayerColors.OnSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = PlayerColors.OnSurfaceVariant, fontSize = 13.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, color = PlayerColors.OnSurface, fontSize = 13.sp)
    }
}

@Composable
private fun StubListCard(icon: ImageVector, title: String, hint: String) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = PlayerColors.OnSurfaceVariant)
                Spacer(modifier = Modifier.height(0.dp))
                Text(
                    text = "  $title",
                    color = PlayerColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = hint, color = PlayerColors.OnSurfaceVariant, fontSize = 12.sp)
        }
    }
}

private fun progressLabel(state: PlayerUiState, media: MediaItem?): String {
    val percent = (state.progressFraction * 100).toInt()
    if (media?.viewStatus == "viewed") return "已看完"
    if (percent >= 1) return "已播放 $percent%"
    return "未观看"
}

private fun formatDurationSeconds(seconds: Int): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}
