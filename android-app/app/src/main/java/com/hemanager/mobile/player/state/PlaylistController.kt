package com.hemanager.mobile.player.state

import com.hemanager.mobile.player.model.PlaybackMode
import kotlin.random.Random

/**
 * Owns the current playlist (a list of media ids) and resolves what plays next based on
 * [PlaybackMode]. Stateless w.r.t. ExoPlayer — the VM is responsible for actually
 * loading the resolved id and re-binding the player.
 *
 * Avoids consecutive shuffle repeats by remembering the last few ids served from
 * [SHUFFLE]. List has < 4 items? — fall back to plain SEQUENCE behaviour.
 *
 * Design note: keeping this in its own class makes Phase 2 testable without ExoPlayer:
 * given an id list and a mode, what's the next id?
 */
class PlaylistController {
    private var ids: List<Int> = emptyList()
    private val recentShuffle: ArrayDeque<Int> = ArrayDeque()
    private val random = Random.Default

    fun setPlaylist(ids: List<Int>) {
        this.ids = ids
        recentShuffle.clear()
    }

    fun size(): Int = ids.size
    fun isEmpty(): Boolean = ids.isEmpty()

    fun indexOf(currentId: Int): Int = ids.indexOf(currentId)

    fun canSkipPrevious(currentId: Int, mode: PlaybackMode): Boolean = when {
        ids.size <= 1 -> false
        mode == PlaybackMode.SHUFFLE -> true
        mode == PlaybackMode.LIST -> true
        else -> ids.indexOf(currentId) > 0
    }

    fun canSkipNext(currentId: Int, mode: PlaybackMode): Boolean = when {
        ids.size <= 1 -> false
        mode == PlaybackMode.SHUFFLE -> true
        mode == PlaybackMode.LIST -> true
        else -> {
            val idx = ids.indexOf(currentId)
            idx in 0..(ids.size - 2)
        }
    }

    fun previous(currentId: Int, mode: PlaybackMode): Int? {
        if (ids.isEmpty()) return null
        if (ids.size == 1) return null
        return when (mode) {
            PlaybackMode.SHUFFLE -> shufflePick(currentId)
            PlaybackMode.LIST -> {
                val idx = ids.indexOf(currentId)
                if (idx <= 0) ids.last() else ids[idx - 1]
            }
            else -> {
                val idx = ids.indexOf(currentId)
                if (idx <= 0) null else ids[idx - 1]
            }
        }
    }

    fun next(currentId: Int, mode: PlaybackMode): Int? {
        if (ids.isEmpty()) return null
        if (ids.size == 1) return null
        return when (mode) {
            PlaybackMode.SHUFFLE -> shufflePick(currentId)
            PlaybackMode.LIST -> {
                val idx = ids.indexOf(currentId)
                if (idx < 0 || idx >= ids.size - 1) ids.first() else ids[idx + 1]
            }
            PlaybackMode.SEQUENCE, PlaybackMode.SINGLE, PlaybackMode.END_PAUSE -> {
                val idx = ids.indexOf(currentId)
                if (idx < 0 || idx >= ids.size - 1) null else ids[idx + 1]
            }
        }
    }

    /**
     * Resolve what should happen at end-of-video. Returns:
     *  - [EndAction.Repeat] for SINGLE
     *  - [EndAction.Pause] for END_PAUSE or end-of-list-without-loop
     *  - [EndAction.PlayNext] with the target id otherwise
     */
    fun onPlaybackEnded(currentId: Int, mode: PlaybackMode): EndAction = when (mode) {
        PlaybackMode.SINGLE -> EndAction.Repeat
        PlaybackMode.END_PAUSE -> EndAction.Pause
        PlaybackMode.SEQUENCE -> {
            val n = next(currentId, PlaybackMode.SEQUENCE)
            if (n != null) EndAction.PlayNext(n) else EndAction.Pause
        }
        PlaybackMode.LIST -> {
            val n = next(currentId, PlaybackMode.LIST) ?: ids.firstOrNull()
            if (n != null) EndAction.PlayNext(n) else EndAction.Pause
        }
        PlaybackMode.SHUFFLE -> {
            val n = shufflePick(currentId)
            if (n != null) EndAction.PlayNext(n) else EndAction.Pause
        }
    }

    private fun shufflePick(currentId: Int): Int? {
        if (ids.size <= 1) return null
        val candidates = ids.filter { it != currentId && it !in recentShuffle }
        val pool = if (candidates.isEmpty()) ids.filter { it != currentId } else candidates
        if (pool.isEmpty()) return null
        val pick = pool[random.nextInt(pool.size)]
        recentShuffle.addLast(pick)
        // Remember the last (size/2 - 1) so we don't re-pick recents until the pool rotates.
        val keep = (ids.size / 2 - 1).coerceAtLeast(1)
        while (recentShuffle.size > keep) recentShuffle.removeFirst()
        return pick
    }

    sealed interface EndAction {
        data object Pause : EndAction
        data object Repeat : EndAction
        data class PlayNext(val mediaId: Int) : EndAction
    }
}
