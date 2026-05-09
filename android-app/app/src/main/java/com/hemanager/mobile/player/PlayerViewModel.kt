package com.hemanager.mobile.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.hemanager.mobile.MediaItem
import com.hemanager.mobile.player.data.PlayerPreferences
import com.hemanager.mobile.player.data.PlayerRepository
import com.hemanager.mobile.player.model.AspectMode
import com.hemanager.mobile.player.model.PlaybackMode
import com.hemanager.mobile.player.model.PlayerStatus
import com.hemanager.mobile.player.model.PlayerUiState
import com.hemanager.mobile.player.model.TrackInfo
import com.hemanager.mobile.player.state.PlaylistController
import com.hemanager.mobile.player.state.ProgressSaver
import com.hemanager.mobile.player.state.TrackInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Central player VM. Owns the [ExoPlayer] instance — keeping it in the VM (instead of the
 * Activity) is what survives orientation changes without recreating the player or
 * losing position.
 *
 * Phase 0 implements: load/play/pause/seek/seekBy, progress save (throttled + flushed),
 * resume from server progress, error mapping, preferences load/persist for speed and
 * aspect mode and playback mode. UI for those settings lands in later phases.
 */
@UnstableApi
class PlayerViewModel(
    application: Application,
    private val repository: PlayerRepository,
    private val preferences: PlayerPreferences,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(application)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(45_000, 120_000, 1_000, 2_000)
                .build(),
        )
        .build()

    private val saver = ProgressSaver(viewModelScope, repository)
    private val playlistController = PlaylistController()

    private var positionTickJob: Job? = null
    private var currentMediaId: Int = 0
    private var initialProgressSeconds: Int = 0
    private var hasMarkedWatched: Boolean = false

    /** Speed before long-press 2x kicked in. -1 → not boosted. */
    private var savedSpeedBeforeBoost: Float = -1f

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val newStatus = when (state) {
                Player.STATE_IDLE -> PlayerStatus.Idle
                Player.STATE_BUFFERING -> PlayerStatus.Buffering
                Player.STATE_READY -> if (player.isPlaying) PlayerStatus.Playing else PlayerStatus.Ready
                Player.STATE_ENDED -> PlayerStatus.Ended
                else -> PlayerStatus.Idle
            }
            _uiState.update { it.copy(status = newStatus) }
            if (state == Player.STATE_ENDED) {
                hasMarkedWatched = true
                if (currentMediaId > 0) {
                    viewModelScope.launch {
                        repository.setViewStatus(currentMediaId, "viewed")
                        saver.flushNow()
                    }
                }
                handlePlaybackEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update {
                it.copy(
                    isPlaying = isPlaying,
                    playWhenReady = player.playWhenReady,
                    status = if (isPlaying) PlayerStatus.Playing
                    else if (it.status is PlayerStatus.Error) it.status
                    else PlayerStatus.Paused,
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = error.localizedMessage ?: "播放失败，文件可能已移动、损坏或格式不支持"
            _uiState.update { it.copy(status = PlayerStatus.Error(message, error), errorMessage = message) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val (audio, subs) = TrackInspector.extract(tracks)
            _uiState.update {
                it.copy(
                    audioTracks = audio,
                    subtitleTracks = subs,
                    subtitlesEnabled = subs.any { t -> t.isSelected },
                )
            }
        }
    }

    init {
        player.addListener(playerListener)
        // Load persisted preferences once. Subsequent writes go through this VM so the
        // in-memory state and DataStore stay in lockstep.
        viewModelScope.launch {
            val speed = preferences.speed.first()
            val mode = preferences.playbackMode.first()
            val aspect = preferences.aspectMode.first()
            player.setPlaybackSpeed(speed)
            _uiState.update { it.copy(playbackSpeed = speed, playbackMode = mode, aspectMode = aspect) }
        }
    }

    fun bind(
        mediaId: Int,
        title: String,
        initialProgressSeconds: Int,
        durationSeconds: Int,
        restart: Boolean,
    ) {
        if (mediaId <= 0) return
        if (currentMediaId == mediaId && player.currentMediaItem != null) {
            // Re-bind for same media (e.g. activity recreated under us) — don't reset.
            return
        }
        currentMediaId = mediaId
        this.initialProgressSeconds = if (restart) 0 else initialProgressSeconds
        hasMarkedWatched = false
        saver.bind(mediaId, this.initialProgressSeconds)

        _uiState.update {
            it.copy(
                title = title,
                status = PlayerStatus.Loading,
                positionMs = this.initialProgressSeconds * 1000L,
                durationMs = durationSeconds * 1000L,
                bufferedPositionMs = 0L,
                errorMessage = null,
            )
        }

        val url = repository.streamUrl(mediaId)
        player.setMediaItem(Media3Item.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
        if (this.initialProgressSeconds > 0) {
            player.seekTo(this.initialProgressSeconds * 1000L)
        }

        // Refresh from server so a more recent progress (saved on another device)
        // overrides the value passed via Intent extras.
        viewModelScope.launch {
            val media = repository.loadMedia(mediaId)
            _uiState.update { it.copy(media = media) }
            if (!restart && media != null && media.progress > this@PlayerViewModel.initialProgressSeconds) {
                player.seekTo(media.progress * 1000L)
                this@PlayerViewModel.initialProgressSeconds = media.progress
                saver.bind(mediaId, media.progress)
            }
        }

        startPositionTicker()
        saver.start()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            viewModelScope.launch { saver.flushNow() }
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(target)
    }

    fun setScrubbing(active: Boolean) {
        _uiState.update { it.copy(scrubbing = active) }
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.update { it.copy(controlsVisible = visible) }
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
        viewModelScope.launch { preferences.setSpeed(speed) }
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        _uiState.update {
            it.copy(
                playbackMode = mode,
                canSkipPrevious = playlistController.canSkipPrevious(currentMediaId, mode),
                canSkipNext = playlistController.canSkipNext(currentMediaId, mode),
            )
        }
        viewModelScope.launch { preferences.setPlaybackMode(mode) }
    }

    fun cyclePlaybackMode(): PlaybackMode {
        val next = _uiState.value.playbackMode.next()
        setPlaybackMode(next)
        return next
    }

    fun setPlaylist(ids: List<Int>) {
        playlistController.setPlaylist(ids)
        val mode = _uiState.value.playbackMode
        _uiState.update {
            it.copy(
                canSkipPrevious = playlistController.canSkipPrevious(currentMediaId, mode),
                canSkipNext = playlistController.canSkipNext(currentMediaId, mode),
            )
        }
    }

    fun playPrevious() {
        val target = playlistController.previous(currentMediaId, _uiState.value.playbackMode) ?: return
        switchToMedia(target)
    }

    fun playNext() {
        val target = playlistController.next(currentMediaId, _uiState.value.playbackMode) ?: return
        switchToMedia(target)
    }

    /** Begin a long-press 2x boost. Idempotent — repeated starts are ignored. */
    fun startLongPressBoost() {
        if (savedSpeedBeforeBoost > 0f) return
        savedSpeedBeforeBoost = _uiState.value.playbackSpeed
        player.setPlaybackSpeed(2.0f)
    }

    fun endLongPressBoost() {
        if (savedSpeedBeforeBoost <= 0f) return
        player.setPlaybackSpeed(savedSpeedBeforeBoost)
        savedSpeedBeforeBoost = -1f
    }

    fun setAspectMode(mode: AspectMode) {
        _uiState.update { it.copy(aspectMode = mode) }
        viewModelScope.launch { preferences.setAspectMode(mode) }
    }

    fun cycleAspectMode(): AspectMode {
        val all = AspectMode.values()
        val next = all[(_uiState.value.aspectMode.ordinal + 1) % all.size]
        setAspectMode(next)
        return next
    }

    fun selectAudioTrack(track: TrackInfo) {
        TrackInspector.selectAudio(player, track)
        viewModelScope.launch { preferences.setLastAudioLanguage(track.language) }
    }

    fun selectSubtitleTrack(track: TrackInfo?) {
        TrackInspector.selectSubtitle(player, track)
        _uiState.update { it.copy(subtitlesEnabled = track != null) }
        viewModelScope.launch {
            preferences.setSubtitlesEnabled(track != null)
            preferences.setLastSubtitleLanguage(track?.language)
        }
    }

    fun toggleSubtitles() {
        val current = _uiState.value
        if (current.subtitlesEnabled) {
            selectSubtitleTrack(null)
        } else {
            // Re-enable: prefer the previously selected language if available, else first.
            val target = current.subtitleTracks.firstOrNull { it.isSelected }
                ?: current.subtitleTracks.firstOrNull()
            if (target != null) selectSubtitleTrack(target)
        }
    }

    fun setLocked(locked: Boolean) {
        _uiState.update { it.copy(locked = locked) }
    }

    /**
     * Live preview while the user drags the seek bar. Updates the visible position
     * but does NOT actually move the player — that happens on commit.
     */
    fun previewScrubPosition(positionMs: Long) {
        _uiState.update { it.copy(positionMs = positionMs.coerceIn(0L, it.durationMs.coerceAtLeast(0L))) }
    }

    /**
     * Apply a scrub. Seeks the player and flushes progress so the new position is
     * persisted right away (instead of waiting for the next 5 s tick).
     */
    fun commitScrub(positionMs: Long) {
        seekTo(positionMs)
        _uiState.update { it.copy(scrubbing = false) }
        viewModelScope.launch { saver.flushNow() }
    }

    fun retryCurrent() {
        _uiState.update { it.copy(status = PlayerStatus.Loading, errorMessage = null) }
        player.prepare()
        player.playWhenReady = true
    }

    fun resumeFromSaved() {
        // Player is already at the saved position from bind() — just continue playing.
        if (!player.isPlaying) player.play()
    }

    fun restartFromBeginning() {
        player.seekTo(0L)
        player.play()
        viewModelScope.launch { saver.flushNow() }
    }

    suspend fun toggleFavorite() {
        val media = _uiState.value.media ?: return
        val target = !media.favorite
        val updated = repository.toggleFavorite(media.id, target) ?: return
        _uiState.update { it.copy(media = updated) }
    }

    suspend fun toggleWatched() {
        val media = _uiState.value.media ?: return
        val newStatus = if (media.viewStatus == "viewed") "viewing" else "viewed"
        repository.setViewStatus(media.id, newStatus)
        media.viewStatus = newStatus
        _uiState.update { it.copy(media = media) }
    }

    /**
     * Re-bind the player to a different media id. Used by prev/next and end-of-video
     * SEQUENCE/LIST/SHUFFLE transitions. Flushes the current progress, resets the
     * watch-tracking flag, then loads the new stream from progress fetched from the
     * server (so we resume each video at its own saved point).
     */
    private fun switchToMedia(newMediaId: Int) {
        viewModelScope.launch {
            saver.flushNow()
            currentMediaId = newMediaId
            hasMarkedWatched = false
            saver.bind(newMediaId, 0)

            val media = repository.loadMedia(newMediaId)
            val resumeSeconds = (media?.progress ?: 0).coerceAtLeast(0)

            _uiState.update {
                it.copy(
                    media = media,
                    title = media?.title ?: it.title,
                    status = PlayerStatus.SwitchingVideo,
                    positionMs = resumeSeconds * 1000L,
                    durationMs = (media?.duration ?: 0) * 1000L,
                    bufferedPositionMs = 0L,
                    errorMessage = null,
                    canSkipPrevious = playlistController.canSkipPrevious(newMediaId, it.playbackMode),
                    canSkipNext = playlistController.canSkipNext(newMediaId, it.playbackMode),
                )
            }

            val url = repository.streamUrl(newMediaId)
            player.setMediaItem(Media3Item.fromUri(Uri.parse(url)))
            player.prepare()
            if (resumeSeconds > 0) player.seekTo(resumeSeconds * 1000L)
            player.playWhenReady = true
        }
    }

    private fun handlePlaybackEnded() {
        val mode = _uiState.value.playbackMode
        when (val action = playlistController.onPlaybackEnded(currentMediaId, mode)) {
            is PlaylistController.EndAction.Repeat -> {
                player.seekTo(0L)
                player.play()
                hasMarkedWatched = false
            }
            is PlaylistController.EndAction.Pause -> {
                // END_PAUSE or no further item available — leave on Ended state.
            }
            is PlaylistController.EndAction.PlayNext -> switchToMedia(action.mediaId)
        }
    }

    fun onUserLeftActivity() {
        // Called from Activity onStop / onPause when finishing or backgrounding.
        viewModelScope.launch { saver.flushNow() }
    }

    private fun startPositionTicker() {
        positionTickJob?.cancel()
        positionTickJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (player.duration > 0L) {
                    val pos = player.currentPosition
                    val dur = player.duration
                    val buf = player.bufferedPosition
                    saver.report(pos, dur)
                    _uiState.update {
                        if (it.scrubbing) it
                        else it.copy(positionMs = pos, durationMs = dur, bufferedPositionMs = buf)
                    }
                    maybeMarkWatched(pos, dur)
                }
                delay(500L)
            }
        }
    }

    private fun maybeMarkWatched(positionMs: Long, durationMs: Long) {
        if (hasMarkedWatched || currentMediaId <= 0 || durationMs <= 0L) return
        if (positionMs.toFloat() / durationMs >= 0.9f) {
            hasMarkedWatched = true
            viewModelScope.launch { repository.setViewStatus(currentMediaId, "viewed") }
        }
    }

    override fun onCleared() {
        positionTickJob?.cancel()
        saver.stop()
        player.removeListener(playerListener)
        player.release()
        super.onCleared()
    }

    companion object {
        fun factory(serverUrl: String, token: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        ?: error("Application missing from CreationExtras")
                    @Suppress("UNCHECKED_CAST")
                    return PlayerViewModel(
                        application = app,
                        repository = PlayerRepository(serverUrl, token),
                        preferences = PlayerPreferences(app),
                    ) as T
                }
            }
    }
}
