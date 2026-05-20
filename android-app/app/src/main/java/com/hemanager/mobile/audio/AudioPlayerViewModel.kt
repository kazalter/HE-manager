package com.hemanager.mobile.audio

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the [MediaController] connection to [AsmrPlaybackService] and turns it
 * into a single [AudioPlayerUiState] flow for Compose.
 *
 * Design notes:
 *  - The player itself lives in the service (background-safe). This VM never
 *    holds an ExoPlayer; it drives playback through the controller.
 *  - Resume reuses the work's single `progress` column as *cumulative seconds*
 *    across all tracks (prior track durations + offset), matching the video
 *    player's reuse of the same field.
 *  - If the service is already playing this exact work (user left with the
 *    screen off and came back), we re-attach to the live queue instead of
 *    rebuilding it, so playback never hiccups.
 */
@UnstableApi
class AudioPlayerViewModel(
    application: Application,
    private val repository: AudioRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AudioPlayerUiState())
    val uiState: StateFlow<AudioPlayerUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var mediaId: Int = 0
    private var workTitle: String = ""
    private var coverUrl: String? = null
    private var totalDurationSec: Int = 0

    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private val lyricsCache = mutableMapOf<Int, List<LyricLine>>()
    private var lastSavedSec: Int = -1

    fun bind(mediaId: Int, title: String, progressSeconds: Int, durationSeconds: Int) {
        if (this.mediaId == mediaId && controller != null) return
        this.mediaId = mediaId
        this.workTitle = title
        this.totalDurationSec = durationSeconds.coerceAtLeast(0)
        _uiState.update { it.copy(workTitle = title, loading = true, error = null) }

        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), AsmrPlaybackService::class.java),
        )
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        controllerFuture = future
        future.addListener(
            { onControllerConnected(future, progressSeconds) },
            ContextCompat.getMainExecutor(getApplication()),
        )
    }

    private fun onControllerConnected(
        future: ListenableFuture<MediaController>,
        savedProgressSeconds: Int,
    ) {
        val c = runCatching { future.get() }.getOrNull() ?: run {
            _uiState.update { it.copy(loading = false, error = "无法连接播放服务") }
            return
        }
        controller = c
        c.addListener(playerListener)

        val alreadyPlayingThisWork = c.currentMediaItem?.mediaId
            ?.startsWith("$mediaId:") == true && c.mediaItemCount > 0

        viewModelScope.launch {
            val meta = repository.loadWork(mediaId)
            if (meta != null) {
                workTitle = meta.title
                coverUrl = meta.coverUrl
                if (totalDurationSec == 0) totalDurationSec = meta.totalDurationSec
            }
            val tracks = repository.getTracks(mediaId)
            if (tracks.isEmpty()) {
                _uiState.update { it.copy(loading = false, error = "没有可播放的音轨") }
                return@launch
            }
            _uiState.update {
                it.copy(workTitle = workTitle, coverUrl = coverUrl, tracks = tracks)
            }

            if (alreadyPlayingThisWork) {
                syncFromController()
            } else {
                val resumeSec = meta?.savedProgressSec ?: savedProgressSeconds
                val (startIndex, startOffsetMs) = resumeTarget(tracks, resumeSec)
                c.setMediaItems(tracks.map { buildItem(it) }, startIndex, startOffsetMs)
                c.prepare()
                c.playWhenReady = true
                lastSavedSec = resumeSec
            }
            _uiState.update { it.copy(loading = false) }
            syncFromController()
            loadLyricsFor(c.currentMediaItemIndex)
            startTicker()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                )
            ) {
                syncFromController()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                onTrackChanged(player.currentMediaItemIndex)
            }
        }
    }

    private fun onTrackChanged(index: Int) {
        // "Stop after this track" sleep mode: the moment the track flips, halt.
        if (_uiState.value.sleepAfterCurrentTrack) {
            controller?.pause()
            clearSleepTimer()
        }
        loadLyricsFor(index)
        viewModelScope.launch { saveProgress(force = true) }
    }

    private fun syncFromController() {
        val c = controller ?: return
        val loop = when {
            c.shuffleModeEnabled -> LoopMode.SHUFFLE
            c.repeatMode == Player.REPEAT_MODE_ONE -> LoopMode.ONE
            c.repeatMode == Player.REPEAT_MODE_ALL -> LoopMode.ALL
            else -> LoopMode.OFF
        }
        _uiState.update {
            it.copy(
                currentIndex = c.currentMediaItemIndex,
                isPlaying = c.isPlaying,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.coerceAtLeast(0),
                loopMode = loop,
                speed = c.playbackParameters.speed,
            )
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    val pos = c.currentPosition.coerceAtLeast(0)
                    val dur = c.duration.coerceAtLeast(0)
                    val posSec = pos / 1000.0
                    val lyrics = _uiState.value.lyrics
                    val active = lyrics.indexOfLast { it.timeSec <= posSec + 0.15 }
                    _uiState.update {
                        it.copy(positionMs = pos, durationMs = dur, activeLyricIndex = active)
                    }
                    if (c.isPlaying) saveProgress(force = false)
                }
                delay(500)
            }
        }
    }

    private fun loadLyricsFor(index: Int) {
        val track = _uiState.value.tracks.getOrNull(index) ?: return
        lyricsCache[track.index]?.let { cached ->
            _uiState.update { it.copy(lyrics = cached, activeLyricIndex = -1) }
            return
        }
        if (!track.hasLyrics) {
            _uiState.update { it.copy(lyrics = emptyList(), activeLyricIndex = -1) }
            return
        }
        viewModelScope.launch {
            val lines = repository.getLyrics(mediaId, track.index)
            lyricsCache[track.index] = lines
            if (controller?.currentMediaItemIndex == index) {
                _uiState.update { it.copy(lyrics = lines, activeLyricIndex = -1) }
            }
        }
    }

    // ─── Transport controls (called from the UI) ──────────────────────────────

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else { c.prepare(); c.play() }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, if (c.duration > 0) c.duration else Long.MAX_VALUE))
    }

    fun next() = controller?.seekToNextMediaItem() ?: Unit

    fun previous() {
        val c = controller ?: return
        // Mainstream behaviour: restart the track if we're >3s in, else go back.
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun selectTrack(index: Int) {
        controller?.seekTo(index, 0)
        controller?.play()
    }

    fun cycleLoopMode() {
        val next = when (_uiState.value.loopMode) {
            LoopMode.OFF -> LoopMode.ALL
            LoopMode.ALL -> LoopMode.ONE
            LoopMode.ONE -> LoopMode.SHUFFLE
            LoopMode.SHUFFLE -> LoopMode.OFF
        }
        setLoopMode(next)
    }

    fun setLoopMode(mode: LoopMode) {
        val c = controller ?: return
        when (mode) {
            LoopMode.OFF -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_OFF }
            LoopMode.ALL -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ALL }
            LoopMode.ONE -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_ONE }
            LoopMode.SHUFFLE -> { c.shuffleModeEnabled = true; c.repeatMode = Player.REPEAT_MODE_ALL }
        }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.25f, 3f))
    }

    // ─── Sleep timer ──────────────────────────────────────────────────────────

    fun setSleepTimerMinutes(minutes: Int) {
        clearSleepTimer()
        if (minutes <= 0) return
        val deadline = SystemClock.uptimeMillis() + minutes * 60_000L
        _uiState.update { it.copy(sleepTimerEndUptimeMs = deadline, sleepAfterCurrentTrack = false) }
        sleepJob = viewModelScope.launch {
            while (isActive && SystemClock.uptimeMillis() < deadline) delay(1_000)
            if (isActive) {
                controller?.pause()
                clearSleepTimer()
            }
        }
    }

    fun setSleepAfterCurrentTrack() {
        clearSleepTimer()
        _uiState.update { it.copy(sleepAfterCurrentTrack = true, sleepTimerEndUptimeMs = 0L) }
    }

    fun clearSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _uiState.update { it.copy(sleepTimerEndUptimeMs = 0L, sleepAfterCurrentTrack = false) }
    }

    // ─── Resume / progress bookkeeping ────────────────────────────────────────

    private fun prefixSeconds(tracks: List<AudioTrack>, index: Int): Double =
        tracks.take(index.coerceIn(0, tracks.size)).sumOf { it.durationSec ?: 0.0 }

    private fun resumeTarget(tracks: List<AudioTrack>, savedSec: Int): Pair<Int, Long> {
        if (savedSec <= 0) return 0 to 0L
        var acc = 0.0
        for (i in tracks.indices) {
            val d = tracks[i].durationSec ?: 0.0
            if (d > 0 && savedSec < acc + d) return i to ((savedSec - acc) * 1000).toLong().coerceAtLeast(0)
            acc += d
        }
        return 0 to 0L // durations unknown or progress past the end → start over
    }

    private suspend fun saveProgress(force: Boolean) {
        val c = controller ?: return
        val tracks = _uiState.value.tracks
        if (tracks.isEmpty()) return
        val cumulativeSec =
            (prefixSeconds(tracks, c.currentMediaItemIndex) + c.currentPosition / 1000.0).toInt().coerceAtLeast(0)
        if (!force && lastSavedSec >= 0 && kotlin.math.abs(cumulativeSec - lastSavedSec) < 3) return
        val total = tracks.sumOf { it.durationSec ?: 0.0 }.toInt().takeIf { it > 0 } ?: totalDurationSec
        repository.saveProgress(mediaId, cumulativeSec, total)
        lastSavedSec = cumulativeSec
    }

    fun onUserLeaving() {
        viewModelScope.launch { saveProgress(force = true) }
    }

    private fun buildItem(track: AudioTrack): Media3Item =
        Media3Item.Builder()
            .setMediaId("$mediaId:${track.index}")
            .setUri(repository.trackStreamUrl(mediaId, track.index))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setAlbumTitle(workTitle)
                    .setArtist(workTitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .apply { coverUrl?.let { setArtworkUri(Uri.parse(it)) } }
                    .build(),
            )
            .build()

    override fun onCleared() {
        ticker?.cancel()
        sleepJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }

    companion object {
        fun factory(serverUrl: String, token: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        ?: error("Application missing from CreationExtras")
                    @Suppress("UNCHECKED_CAST")
                    return AudioPlayerViewModel(app, AudioRepository(serverUrl, token)) as T
                }
            }
    }
}
