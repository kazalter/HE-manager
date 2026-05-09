package com.hemanager.mobile.player.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hemanager.mobile.player.model.AspectMode
import com.hemanager.mobile.player.model.PlaybackMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerDataStore by preferencesDataStore(name = "player_prefs")

/**
 * DataStore-backed persistence for player preferences. Each `flow*()` is reactive;
 * `set*()` is a suspend write. The ViewModel reads these flows once at init and writes
 * back when the user changes a setting.
 */
class PlayerPreferences(private val context: Context) {

    private object Keys {
        val SPEED = floatPreferencesKey("speed")
        val PLAYBACK_MODE = stringPreferencesKey("playback_mode")
        val ASPECT_MODE = stringPreferencesKey("aspect_mode")
        val SUBTITLES_ENABLED = booleanPreferencesKey("subtitles_enabled")
        val SUBTITLE_FONT_SCALE = floatPreferencesKey("subtitle_font_scale")
        val SUBTITLE_TEXT_COLOR = intPreferencesKey("subtitle_text_color")
        val SUBTITLE_BG_OPACITY = floatPreferencesKey("subtitle_bg_opacity")
        val LAST_AUDIO_LANG = stringPreferencesKey("last_audio_lang")
        val LAST_SUBTITLE_LANG = stringPreferencesKey("last_subtitle_lang")
    }

    val speed: Flow<Float> = context.playerDataStore.data.map { it[Keys.SPEED] ?: 1.0f }
    val playbackMode: Flow<PlaybackMode> =
        context.playerDataStore.data.map { PlaybackMode.fromName(it[Keys.PLAYBACK_MODE]) }
    val aspectMode: Flow<AspectMode> =
        context.playerDataStore.data.map { AspectMode.fromName(it[Keys.ASPECT_MODE]) }
    val subtitlesEnabled: Flow<Boolean> =
        context.playerDataStore.data.map { it[Keys.SUBTITLES_ENABLED] ?: true }
    val subtitleFontScale: Flow<Float> =
        context.playerDataStore.data.map { it[Keys.SUBTITLE_FONT_SCALE] ?: 1.0f }
    val subtitleTextColor: Flow<Int> =
        context.playerDataStore.data.map { it[Keys.SUBTITLE_TEXT_COLOR] ?: 0xFFFFFFFF.toInt() }
    val subtitleBgOpacity: Flow<Float> =
        context.playerDataStore.data.map { it[Keys.SUBTITLE_BG_OPACITY] ?: 0.0f }
    val lastAudioLanguage: Flow<String?> =
        context.playerDataStore.data.map { it[Keys.LAST_AUDIO_LANG] }
    val lastSubtitleLanguage: Flow<String?> =
        context.playerDataStore.data.map { it[Keys.LAST_SUBTITLE_LANG] }

    suspend fun setSpeed(value: Float) =
        context.playerDataStore.edit { it[Keys.SPEED] = value }

    suspend fun setPlaybackMode(mode: PlaybackMode) =
        context.playerDataStore.edit { it[Keys.PLAYBACK_MODE] = mode.name }

    suspend fun setAspectMode(mode: AspectMode) =
        context.playerDataStore.edit { it[Keys.ASPECT_MODE] = mode.name }

    suspend fun setSubtitlesEnabled(enabled: Boolean) =
        context.playerDataStore.edit { it[Keys.SUBTITLES_ENABLED] = enabled }

    suspend fun setSubtitleFontScale(scale: Float) =
        context.playerDataStore.edit { it[Keys.SUBTITLE_FONT_SCALE] = scale }

    suspend fun setSubtitleTextColor(color: Int) =
        context.playerDataStore.edit { it[Keys.SUBTITLE_TEXT_COLOR] = color }

    suspend fun setSubtitleBgOpacity(opacity: Float) =
        context.playerDataStore.edit { it[Keys.SUBTITLE_BG_OPACITY] = opacity }

    suspend fun setLastAudioLanguage(value: String?) =
        context.playerDataStore.edit {
            if (value == null) it.remove(Keys.LAST_AUDIO_LANG) else it[Keys.LAST_AUDIO_LANG] = value
        }

    suspend fun setLastSubtitleLanguage(value: String?) =
        context.playerDataStore.edit {
            if (value == null) it.remove(Keys.LAST_SUBTITLE_LANG) else it[Keys.LAST_SUBTITLE_LANG] = value
        }
}
