package com.hemanager.mobile.player.state

import com.hemanager.mobile.player.data.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Throttled progress writer.
 *
 * Spec requires: periodic saves while playing, plus saves on pause / exit / video switch /
 * background — and DB pressure must stay low. This component does the throttling once so
 * the ViewModel can call [report] from many places without thinking about it.
 *
 * - Background tick saves every [intervalMs] millis IF the position has moved more than
 *   [minDeltaSeconds] since the last save.
 * - [flushNow] forces an immediate write — used for pause / exit / switch / >90% / etc.
 */
class ProgressSaver(
    private val scope: CoroutineScope,
    private val repository: PlayerRepository,
    private val intervalMs: Long = 5_000L,
    private val minDeltaSeconds: Int = 3,
) {
    @Volatile private var mediaId: Int = 0
    @Volatile private var lastSavedSeconds: Int = -1
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var currentDurationMs: Long = 0L

    private var tickerJob: Job? = null

    fun bind(mediaId: Int, initialProgressSeconds: Int) {
        this.mediaId = mediaId
        this.lastSavedSeconds = initialProgressSeconds
    }

    fun report(positionMs: Long, durationMs: Long) {
        currentPositionMs = positionMs
        currentDurationMs = durationMs
    }

    fun start() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(intervalMs)
                writeIfMoved()
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
    }

    suspend fun flushNow() {
        if (mediaId <= 0) return
        val seconds = (currentPositionMs / 1000L).toInt().coerceAtLeast(0)
        val durationSec = (currentDurationMs / 1000L).toInt().coerceAtLeast(0)
        repository.saveProgress(mediaId, seconds, durationSec)
        lastSavedSeconds = seconds
    }

    private suspend fun writeIfMoved() {
        if (mediaId <= 0) return
        val seconds = (currentPositionMs / 1000L).toInt().coerceAtLeast(0)
        if (lastSavedSeconds < 0 || abs(seconds - lastSavedSeconds) >= minDeltaSeconds) {
            val durationSec = (currentDurationMs / 1000L).toInt().coerceAtLeast(0)
            repository.saveProgress(mediaId, seconds, durationSec)
            lastSavedSeconds = seconds
        }
    }
}
