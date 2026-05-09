package com.hemanager.mobile.player.state

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager

/**
 * Wrappers around system audio + window brightness so the gesture handlers don't have to
 * know about Android internals. Both stay deliberately stateless — they read fresh values
 * each call so a long press on the volume rocker (outside our gesture) is reflected
 * accurately on the next overlay.
 */
class SystemControls(private val activity: Activity) {

    private val audioManager: AudioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val maxVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val currentVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /** Volume as a 0..1 fraction of the music stream's max. */
    fun getVolume(): Float = if (maxVolume == 0) 0f else currentVolume.toFloat() / maxVolume

    /** Set music-stream volume from a 0..1 fraction. */
    fun setVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val target = (clamped * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    /**
     * Read the window's brightness override (0..1). We never read the system value
     * because the user might have it set differently from the per-app override — and
     * adjusting via this control should affect the player only.
     */
    fun getBrightness(): Float {
        val explicit = activity.window.attributes.screenBrightness
        if (explicit in 0f..1f) return explicit
        // First-run fallback — assume mid-bright so the first gesture has a sensible base.
        return 0.5f
    }

    /** Override window brightness as a 0..1 fraction. */
    fun setBrightness(fraction: Float) {
        val clamped = fraction.coerceIn(0.01f, 1f)
        val attrs = activity.window.attributes
        attrs.screenBrightness = clamped
        activity.window.attributes = attrs
    }
}
