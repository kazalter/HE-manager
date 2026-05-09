package com.hemanager.mobile.player.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hemanager.mobile.player.ui.PlayerColors

/**
 * Top control bar shown above the video. Used in both portrait and landscape modes —
 * landscape adds a top scrim behind it (caller's responsibility) since the video may be
 * white near the edges.
 *
 * The "more" entry is a placeholder — Phase 4 wires it to the bottom-sheet menu.
 */
@Composable
fun TopControlBar(
    title: String,
    favorite: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            GlassIconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
        } else {
            Box(modifier = Modifier.size(40.dp))
        }

        Text(
            text = title.ifEmpty { "—" },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .weight(1f),
        )

        GlassIconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) PlayerColors.Tertiary else Color.White,
            )
        }
        GlassIconButton(onClick = onMore) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = Color.White,
            )
        }
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(PlayerColors.ButtonGlass),
    ) {
        content()
    }
}
