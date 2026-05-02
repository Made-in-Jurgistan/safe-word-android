package com.safeword.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "safeword_settings")

/**
 * SettingsRepository — persists all user-configurable settings via Jetpack DataStore Preferences.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        // Onboarding
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        // UI theme
        val DARK_MODE = stringPreferencesKey("dark_mode")
        // Overlay
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        // Output behaviour
        val AUTO_COPY_TO_CLIPBOARD = booleanPreferencesKey("auto_copy_to_clipboard")
        val AUTO_INSERT_TEXT = booleanPreferencesKey("auto_insert_text")
        val SAVE_TO_HISTORY = booleanPreferencesKey("save_to_history")
        // Auto-stop on silence
        val AUTO_STOP_SILENCE_MS = intPreferencesKey("auto_stop_silence_ms")
        // Recording
        val MAX_RECORDING_DURATION_SEC = intPreferencesKey("max_recording_duration_sec")
        // Streaming
        val STREAMING_UPDATE_INTERVAL_MS = intPreferencesKey("streaming_update_interval_ms")
        // Word timestamps
        val ENABLE_WORD_TIMESTAMPS = booleanPreferencesKey("enable_word_timestamps")
        // Command mode profile
        val COMMAND_MODE_ENABLED = booleanPreferencesKey("command_mode_enabled")
    }

    /** Observe all settings as a reactive Flow. Missing keys fall back to AppSettings defaults. */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            darkMode = prefs[Keys.DARK_MODE] ?: defaults.darkMode,
            overlayEnabled = prefs[Keys.OVERLAY_ENABLED] ?: defaults.overlayEnabled,
            autoCopyToClipboard = prefs[Keys.AUTO_COPY_TO_CLIPBOARD] ?: defaults.autoCopyToClipboard,
            autoInsertText = prefs[Keys.AUTO_INSERT_TEXT] ?: defaults.autoInsertText,
            saveToHistory = prefs[Keys.SAVE_TO_HISTORY] ?: defaults.saveToHistory,
            autoStopSilenceMs = prefs[Keys.AUTO_STOP_SILENCE_MS] ?: defaults.autoStopSilenceMs,
            maxRecordingDurationSec = prefs[Keys.MAX_RECORDING_DURATION_SEC] ?: defaults.maxRecordingDurationSec,
            streamingUpdateIntervalMs = prefs[Keys.STREAMING_UPDATE_INTERVAL_MS] ?: defaults.streamingUpdateIntervalMs,
            enableWordTimestamps = prefs[Keys.ENABLE_WORD_TIMESTAMPS] ?: defaults.enableWordTimestamps,
            commandModeEnabled = prefs[Keys.COMMAND_MODE_ENABLED] ?: defaults.commandModeEnabled,
        )
    }

    // UI theme
    suspend fun updateDarkMode(mode: String) {
        Timber.i("[SETTINGS] updateDarkMode | mode=%s", mode)
        context.dataStore.edit { it[Keys.DARK_MODE] = mode }
    }

    // Overlay
    suspend fun updateOverlayEnabled(enabled: Boolean) {
        Timber.i("[SETTINGS] updateOverlayEnabled | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.OVERLAY_ENABLED] = enabled }
    }



    // Output behaviour
    suspend fun updateAutoCopyToClipboard(enabled: Boolean) {
        Timber.i("[SETTINGS] updateAutoCopyToClipboard | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.AUTO_COPY_TO_CLIPBOARD] = enabled }
    }

    suspend fun updateAutoInsertText(enabled: Boolean) {
        Timber.i("[SETTINGS] updateAutoInsertText | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.AUTO_INSERT_TEXT] = enabled }
    }

    suspend fun updateSaveToHistory(enabled: Boolean) {
        Timber.i("[SETTINGS] updateSaveToHistory | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.SAVE_TO_HISTORY] = enabled }
    }

    // Auto-stop on silence
    suspend fun updateAutoStopSilenceMs(ms: Int) {
        require(ms in 0..10000) { "autoStopSilenceMs must be in [0, 10000], got $ms" }
        Timber.i("[SETTINGS] updateAutoStopSilenceMs | ms=%d", ms)
        context.dataStore.edit { it[Keys.AUTO_STOP_SILENCE_MS] = ms }
    }

    // Recording & model selection
    suspend fun updateMaxRecordingDurationSec(sec: Int) {
        require(sec in 10..3600) { "maxRecordingDurationSec must be in [10, 3600], got $sec" }
        Timber.i("[SETTINGS] updateMaxRecordingDurationSec | sec=%d", sec)
        context.dataStore.edit { it[Keys.MAX_RECORDING_DURATION_SEC] = sec }
    }

    // Streaming
    suspend fun updateStreamingUpdateIntervalMs(ms: Int) {
        require(ms in 100..5000) { "streamingUpdateIntervalMs must be in [100, 5000], got $ms" }
        Timber.i("[SETTINGS] updateStreamingUpdateIntervalMs | ms=%d", ms)
        context.dataStore.edit { it[Keys.STREAMING_UPDATE_INTERVAL_MS] = ms }
    }

    // Word timestamps
    suspend fun updateEnableWordTimestamps(enabled: Boolean) {
        Timber.i("[SETTINGS] updateEnableWordTimestamps | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.ENABLE_WORD_TIMESTAMPS] = enabled }
    }

    // Command mode profile
    suspend fun updateCommandModeEnabled(enabled: Boolean) {
        Timber.i("[SETTINGS] updateCommandModeEnabled | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.COMMAND_MODE_ENABLED] = enabled }
    }

    // Onboarding
    val onboardingComplete: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.ONBOARDING_COMPLETE] ?: false }

    suspend fun updateOnboardingComplete(complete: Boolean) {
        Timber.i("[SETTINGS] updateOnboardingComplete | complete=%b", complete)
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }
}
