package com.hemanager.mobile.audio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.audio.ui.AudioPlayerScreen
import com.hemanager.mobile.player.ui.PlayerColorScheme

/**
 * Host Activity for the ASMR audio player. Deliberately minimal: the player
 * lives in [AsmrPlaybackService], so leaving this Activity (or switching the
 * screen off) does not stop playback. Parallel to `player.PlayerActivity`,
 * never touching it.
 */
@UnstableApi
class AudioPlayerActivity : ComponentActivity() {

    private val viewModel by viewModels<AudioPlayerViewModel>(
        factoryProducer = {
            val serverUrl = ApiClient.trimSlash(intent.getStringExtra("server_url"))
            val token = intent.getStringExtra("token") ?: ""
            AudioPlayerViewModel.factory(serverUrl, token)
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        viewModel.bind(
            mediaId = intent.getIntExtra("id", 0),
            title = intent.getStringExtra("title").orEmpty(),
            progressSeconds = intent.getIntExtra("progress", 0).coerceAtLeast(0),
            durationSeconds = intent.getIntExtra("duration", 0).coerceAtLeast(0),
        )

        setContent {
            MaterialTheme(colorScheme = PlayerColorScheme) {
                AudioPlayerScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }

    override fun onStop() {
        viewModel.onUserLeaving()
        super.onStop()
    }
}
