package com.mediadash.android.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages app settings using SharedPreferences.
 * Provides reactive flows for setting changes.
 */
@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "mediadash_settings"
        private const val KEY_LYRICS_ENABLED = "lyrics_enabled"
        private const val KEY_LYRICS_AUTO_FETCH = "lyrics_auto_fetch"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Lyrics settings
    private val _lyricsEnabled = MutableStateFlow(prefs.getBoolean(KEY_LYRICS_ENABLED, false))
    val lyricsEnabled: StateFlow<Boolean> = _lyricsEnabled.asStateFlow()

    private val _lyricsAutoFetch = MutableStateFlow(prefs.getBoolean(KEY_LYRICS_AUTO_FETCH, true))
    val lyricsAutoFetch: StateFlow<Boolean> = _lyricsAutoFetch.asStateFlow()

    /**
     * Enables or disables lyrics feature.
     */
    fun setLyricsEnabled(enabled: Boolean) {
        Log.d("LYRICS", "⚙️ SETTINGS - setLyricsEnabled: $enabled")
        Log.d("LYRICS", "   Previous value: ${_lyricsEnabled.value}")
        prefs.edit().putBoolean(KEY_LYRICS_ENABLED, enabled).apply()
        _lyricsEnabled.value = enabled
        Log.d("LYRICS", "   Setting saved to SharedPreferences")
    }

    /**
     * Enables or disables automatic lyrics fetching when track changes.
     */
    fun setLyricsAutoFetch(enabled: Boolean) {
        Log.d("LYRICS", "⚙️ SETTINGS - setLyricsAutoFetch: $enabled")
        Log.d("LYRICS", "   Previous value: ${_lyricsAutoFetch.value}")
        prefs.edit().putBoolean(KEY_LYRICS_AUTO_FETCH, enabled).apply()
        _lyricsAutoFetch.value = enabled
        Log.d("LYRICS", "   Setting saved to SharedPreferences")
    }
}
