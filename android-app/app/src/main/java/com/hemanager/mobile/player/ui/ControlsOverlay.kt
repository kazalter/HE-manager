package com.hemanager.mobile.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hemanager.mobile.player.model.PlaybackMode
import com.hemanager.mobile.player.model.PlayerStatus
import com.hemanager.mobile.player.model.PlayerUiState
import com.hemanager.mobile.player.ui.controls.BottomControlBar
import com.hemanager.mobile.player.ui.controls.CenterControls
import com.hemanager.mobile.player.ui.controls.TopControlBar
import kotlinx.coroutines.delay

@Composable
fun PlayerControlsOverlay(
    state: PlayerUiState,
    isFullscreen: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMore: () -> Unit,
    onTogglePlay: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onSpeedClick: () -> Unit,
    onAspectClick: () -> Unit,
    onTracksClick: () -> Unit,
    onLockClick: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onAutoHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.controlsVisible, state.isPlaying, state.scrubbing, state.status) {
        if (!state.controlsVisible) return@LaunchedEffect
        if (!state.isPlaying) return@LaunchedEffect
        if (state.scrubbing) return@LaunchedEffect
        if (state.status is PlayerStatus.Error) return@LaunchedEffect
        delay(3000L)
        onAutoHide()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (state.status is PlayerStatus.Loading ||
            state.status is PlayerStatus.Buffering ||
            state.status is PlayerStatus.Restoring ||
            state.status is PlayerStatus.SwitchingVideo
        ) {
            CircularProgressIndicator(
                color = PlayerColors.Primary,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = state.controlsVisible && state.status !is PlayerStatus.Error && !state.locked,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(PlayerColors.ScrimTop)
                        .align(Alignment.TopCenter),
                )
                TopControlBar(
                    title = state.title,
                    favorite = state.media?.favorite == true,
                    showBack = isFullscreen,
                    onBack = onBack,
                    onToggleFavorite = onToggleFavorite,
                    onMore = onMore,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp),
                )

                CenterControls(
                    status = state.status,
                    isPlaying = state.isPlaying,
                    onRewind = onRewind,
                    onTogglePlay = onTogglePlay,
                    onForward = onForward,
                    modifier = Modifier.align(Alignment.Center),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(PlayerColors.ScrimBottom)
                        .align(Alignment.BottomCenter),
                )
                BottomControlBar(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    bufferedMs = state.bufferedPositionMs,
                    isFullscreen = isFullscreen,
                    canSkipPrevious = state.canSkipPrevious,
                    canSkipNext = state.canSkipNext,
                    speed = state.playbackSpeed,
                    playbackMode = state.playbackMode,
                    locked = state.locked,
                    onScrubStart = onScrubStart,
                    onScrubMove = onScrubMove,
                    onScrubEnd = onScrubEnd,
                    onSkipPrevious = onSkipPrevious,
                    onSkipNext = onSkipNext,
                    onCyclePlaybackMode = onCyclePlaybackMode,
                    onSpeedClick = onSpeedClick,
                    onAspectClick = onAspectClick,
                    onTracksClick = onTracksClick,
                    onLockClick = onLockClick,
                    onToggleFullscreen = onToggleFullscreen,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                )
            }
        }
    }
}
