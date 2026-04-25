package com.safeword.android.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.safeword.android.util.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SettingsRepository — persists user-facing settings via Jetpack DataStore Preferences.
 *
 * Pipeline parameters (VAD thresholds, filler mode, recording limits, etc.) are no
 * longer persisted here — they are hardcoded at point of use with research-backed defaults.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    private object Keys {
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("haptic_feedback_enabled")
    }

    /** Observe all settings as a reactive Flow. Missing keys fall back to AppSettings defaults. */
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            darkMode = prefs[Keys.DARK_MODE] ?: defaults.darkMode,
            overlayEnabled = prefs[Keys.OVERLAY_ENABLED] ?: defaults.overlayEnabled,
            hapticFeedbackEnabled = prefs[Keys.HAPTIC_FEEDBACK_ENABLED] ?: defaults.hapticFeedbackEnabled,
        )
    }

    suspend fun updateDarkMode(mode: String) {
        val normalized = mode.lowercase(Locale.ROOT)
        val defaults = AppSettings()
        val stored = when (normalized) {
            "system", "light", "dark" -> normalized
            else -> {
                Timber.w("[SETTINGS] updateDarkMode | invalid value=%s, defaulting=%s", mode, defaults.darkMode)
                defaults.darkMode
            }
        }
        Timber.i("[SETTINGS] updateDarkMode | mode=%s", stored)
        context.settingsDataStore.edit { it[Keys.DARK_MODE] = stored }
    }

    suspend fun updateOverlayEnabled(enabled: Boolean) {
        Timber.i("[SETTINGS] updateOverlayEnabled | enabled=%b", enabled)
        context.settingsDataStore.edit { it[Keys.OVERLAY_ENABLED] = enabled }
    }

    suspend fun updateHapticFeedbackEnabled(enabled: Boolean) {
        Timber.i("[SETTINGS] updateHapticFeedbackEnabled | enabled=%b", enabled)
        context.settingsDataStore.edit { it[Keys.HAPTIC_FEEDBACK_ENABLED] = enabled }
    }
}
