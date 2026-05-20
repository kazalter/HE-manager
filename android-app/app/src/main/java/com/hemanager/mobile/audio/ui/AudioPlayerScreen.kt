package com.hemanager.mobile.audio.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hemanager.mobile.audio.AudioPlayerUiState
import com.hemanager.mobile.audio.AudioPlayerViewModel
import com.hemanager.mobile.audio.LoopMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(viewModel: AudioPlayerViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var showLyrics by remember { mutableStateOf(true) }
    var tracksSheet by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val hasLyrics = state.lyrics.isNotEmpty()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = state.workTitle.ifBlank { "ASMR" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (hasLyrics) {
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Icon(
                            Icons.Filled.Lyrics,
                            "歌词/封面",
                            tint = if (showLyrics) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Cover / lyrics stage ────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    state.error != null -> Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    hasLyrics && showLyrics -> LyricsPane(
                        state = state,
                        onSeekToLine = { viewModel.seekTo((it * 1000).toLong()) },
                    )
                    else -> CoverArt(state.coverUrl)
                }
            }

            // ── Track title ─────────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.currentTrack?.title ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.tracks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${state.currentIndex + 1} / ${state.tracks.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── Seek bar ────────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SeekRow(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = { viewModel.seekTo(it) },
            )

            // ── Primary transport ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.cycleLoopMode() }) {
                    val (icon, on) = when (state.loopMode) {
                        LoopMode.OFF -> Icons.Filled.Repeat to false
                        LoopMode.ALL -> Icons.Filled.Repeat to true
                        LoopMode.ONE -> Icons.Filled.RepeatOne to true
                        LoopMode.SHUFFLE -> Icons.Filled.Shuffle to true
                    }
                    Icon(
                        icon,
                        "循环模式",
                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, "上一首", modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { viewModel.seekBy(-10_000) }) {
                    Icon(Icons.Filled.Replay10, "后退10秒", tint = MaterialTheme.colorScheme.onSurface)
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp).clickable { viewModel.togglePlayPause() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (state.isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                IconButton(onClick = { viewModel.seekBy(10_000) }) {
                    Icon(Icons.Filled.Forward10, "前进10秒", tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { viewModel.next() }) {
                    Icon(Icons.Filled.SkipNext, "下一首", modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            // ── Secondary controls ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { speedMenu = true }) {
                        Icon(Icons.Filled.Speed, "倍速", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                            DropdownMenuItem(
                                text = { Text(if (sp == state.speed) "✓ ${sp}x" else "${sp}x") },
                                onClick = { viewModel.setSpeed(sp); speedMenu = false },
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { sleepMenu = true }) {
                        Icon(
                            Icons.Filled.Bedtime,
                            "睡眠定时",
                            tint = if (state.sleepTimerArmed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(15, 30, 45, 60, 90).forEach { m ->
                            DropdownMenuItem(
                                text = { Text("$m 分钟") },
                                onClick = { viewModel.setSleepTimerMinutes(m); sleepMenu = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("本曲结束后") },
                            onClick = { viewModel.setSleepAfterCurrentTrack(); sleepMenu = false },
                        )
                        if (state.sleepTimerArmed) {
                            DropdownMenuItem(
                                text = { Text("取消定时") },
                                onClick = { viewModel.clearSleepTimer(); sleepMenu = false },
                            )
                        }
                    }
                }
                if (state.sleepTimerEndUptimeMs > 0L) {
                    val remainMs = (state.sleepTimerEndUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
                    Text(
                        formatTime(remainMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (state.sleepAfterCurrentTrack) {
                    Text("本曲结束停", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { tracksSheet = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "曲目列表", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (tracksSheet) {
        ModalBottomSheet(
            onDismissRequest = { tracksSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                itemsIndexed(state.tracks) { i, track ->
                    val current = i == state.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectTrack(i); tracksSheet = false }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}",
                            modifier = Modifier.width(32.dp),
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            track.title,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (track.hasLyrics) {
                            Icon(
                                Icons.Filled.Lyrics,
                                "有歌词",
                                modifier = Modifier.size(16.dp).padding(start = 8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverArt(coverUrl: String?) {
    if (coverUrl != null) {
        AsyncImage(
            model = coverUrl,
            contentDescription = "封面",
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LyricsPane(state: AudioPlayerUiState, onSeekToLine: (Double) -> Unit) {
    val listState = rememberLazyListState()
    val active = state.activeLyricIndex

    LaunchedEffect(active) {
        if (active >= 0 && active < state.lyrics.size) {
            // Keep the active line roughly centred (offset pulls it up from top).
            listState.animateScrollToItem(active, scrollOffset = -360)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 160.dp),
    ) {
        itemsIndexed(state.lyrics) { i, line ->
            val isActive = i == active
            Text(
                text = line.text,
                textAlign = TextAlign.Center,
                fontSize = if (isActive) 19.sp else 15.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeekToLine(line.timeSec) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SeekRow(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val dur = durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else positionMs.coerceIn(0, dur).toFloat() / dur

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * dur).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime((value * dur).toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatTime(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
