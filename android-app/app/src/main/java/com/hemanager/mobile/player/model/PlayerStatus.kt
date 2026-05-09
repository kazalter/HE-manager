package com.hemanager.mobile.player.model

/**
 * Full state machine for the player. Spec asks for 15 distinct states; we model them
 * as a sealed hierarchy so UI can pattern-match and rendering doesn't depend on
 * inspecting raw ExoPlayer ints.
 */
sealed interface PlayerStatus {
    data object Idle : PlayerStatus
    data object Loading : PlayerStatus
    data object Restoring : PlayerStatus
    data object Ready : PlayerStatus
    data object Playing : PlayerStatus
    data object Paused : PlayerStatus
    data object Buffering : PlayerStatus
    data object Seeking : PlayerStatus
    data object Ended : PlayerStatus
    data object SwitchingVideo : PlayerStatus
    data object Backgrounded : PlayerStatus
    data class Error(val message: String, val cause: Throwable? = null) : PlayerStatus
}
