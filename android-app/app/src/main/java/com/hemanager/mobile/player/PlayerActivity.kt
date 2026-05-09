package com.hemanager.mobile.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import com.hemanager.mobile.ApiClient
import com.hemanager.mobile.player.state.SystemControls
import com.hemanager.mobile.player.ui.PlayerColorScheme

/**
 * Host Activity for the Compose player.
 *
 * Orientation behaviour (unchanged from Phase 1):
 *  - Activity does NOT recreate on rotation (configChanges in manifest).
 *  - "Fullscreen" = setRequestedOrientation(LANDSCAPE) + hide system bars.
 *
 * Phase 2 additions:
 *  - Reads `playlist_ids` int-array extra and feeds it to the ViewModel's PlaylistController.
 *  - Creates [SystemControls] tied to this Activity's window so brightness gestures can
 *    override window brightness without affecting the global system value.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private val playerViewModel by viewModels<PlayerViewModel>(
        factoryProducer = {
            val serverUrl = ApiClient.trimSlash(intent.getStringExtra("server_url"))
            val token = intent.getStringExtra("token") ?: ""
            PlayerViewModel.factory(serverUrl, token)
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT
        }

        val mediaId = intent.getIntExtra("id", 0)
        val title = intent.getStringExtra("title").orEmpty()
        val progress = intent.getIntExtra("progress", 0).coerceAtLeast(0)
        val duration = intent.getIntExtra("duration", 0).coerceAtLeast(0)
        val restart = intent.getBooleanExtra("restart", false)
        val playlist = intent.getIntArrayExtra("playlist_ids")?.toList().orEmpty()

        playerViewModel.bind(
            mediaId = mediaId,
            title = title,
            initialProgressSeconds = progress,
            durationSeconds = duration,
            restart = restart,
        )
        if (playlist.isNotEmpty()) {
            playerViewModel.setPlaylist(playlist)
        }

        val systemControls = SystemControls(this)

        setContent {
            MaterialTheme(colorScheme = PlayerColorScheme) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    systemControls = systemControls,
                    onBack = { onBackOrFinish() },
                    onToggleFullscreen = { toggleFullscreen() },
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsForOrientation(newConfig.orientation)
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsForOrientation(resources.configuration.orientation)
    }

    override fun onStop() {
        playerViewModel.onUserLeftActivity()
        super.onStop()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun onBackOrFinish() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun applySystemBarsForOrientation(orientation: Int) {
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
