package com.hemanager.mobile.audio

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background playback host for the ASMR audio player.
 *
 * A [MediaSessionService] holding a single [ExoPlayer] is all that's needed to
 * get the "mainstream player" system integration for free: the media-style
 * notification, lock-screen transport controls, Bluetooth / wired-headset
 * buttons, audio-focus ducking on notifications, pause-on-call, and — the point
 * for ASMR — playback that keeps going with the screen off and the Activity
 * gone.
 *
 * The track list itself is loaded and pushed by [AudioPlayerViewModel] via a
 * MediaController, so all networking stays in the app layer; this class only
 * owns the player + session lifecycle. Parallel to the video `player/` package,
 * never touching it.
 */
@UnstableApi
class AsmrPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause (don't keep blasting from the speaker) when headphones are
            // yanked — expected behaviour for any audio app.
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** Swiping the app away while paused / idle should not leave a zombie
     * service + notification around. If something is actively playing we let it
     * continue (that's the whole point of a background service). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
