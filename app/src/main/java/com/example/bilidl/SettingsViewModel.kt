package com.example.bilidl

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single owner for persisted download preferences.
 *
 * The legacy View-based screens can continue asking for keyed values while
 * new screens observe [state] as an immutable settings snapshot.
 */
data class AppSettingsState(
    val quality: Int = 0,
    val concurrentDownloads: Int = 1,
    val wifiOnly: Boolean = true,
    val downloadDanmaku: Boolean = false,
    val downloadSubtitles: Boolean = false,
    val audioOnly: Boolean = false,
    val notifyOnCompletion: Boolean = true
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences: SharedPreferences = application.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE
    )

    private val _state = MutableStateFlow(readSettings())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    fun getInt(key: String, defaultValue: Int): Int = when (key) {
        KEY_QUALITY -> state.value.quality
        KEY_CONCURRENT -> state.value.concurrentDownloads
        else -> preferences.getInt(key, defaultValue)
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = when (key) {
        KEY_WIFI_ONLY -> state.value.wifiOnly
        KEY_DANMAKU -> state.value.downloadDanmaku
        KEY_SUBTITLES -> state.value.downloadSubtitles
        KEY_AUDIO_ONLY -> state.value.audioOnly
        KEY_NOTIFY -> state.value.notifyOnCompletion
        else -> preferences.getBoolean(key, defaultValue)
    }

    fun setInt(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
        _state.value = readSettings()
    }

    fun setBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
        _state.value = readSettings()
    }

    fun getString(key: String, defaultValue: String = ""): String =
        preferences.getString(key, defaultValue) ?: defaultValue

    fun setString(key: String, value: String) {
        preferences.edit().putString(key, value.trim()).apply()
    }

    private fun readSettings(): AppSettingsState = AppSettingsState(
        quality = preferences.getInt(KEY_QUALITY, 0),
        concurrentDownloads = preferences.getInt(KEY_CONCURRENT, 1),
        wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, true),
        downloadDanmaku = preferences.getBoolean(KEY_DANMAKU, false),
        downloadSubtitles = preferences.getBoolean(KEY_SUBTITLES, false),
        audioOnly = preferences.getBoolean(KEY_AUDIO_ONLY, false),
        notifyOnCompletion = preferences.getBoolean(KEY_NOTIFY, true)
    )

    companion object {
        private const val PREFERENCES_FILE = "ui"
        private const val KEY_QUALITY = "quality"
        private const val KEY_CONCURRENT = "concurrent"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_DANMAKU = "danmaku"
        private const val KEY_SUBTITLES = "subtitles"
        private const val KEY_AUDIO_ONLY = "audio_only"
        private const val KEY_NOTIFY = "notify"
    }
}
