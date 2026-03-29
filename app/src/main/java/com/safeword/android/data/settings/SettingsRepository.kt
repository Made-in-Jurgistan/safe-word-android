package com.safeword.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "safeword_settings")

/**
 * SettingsRepository — persists all user-configurable settings via Jetpack DataStore Preferences.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    private object Keys {
        // Onboarding
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        // UI theme
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
        // Overlay
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        // Transcription language
        val LANGUAGE = stringPreferencesKey("language")
        val AUTO_DETECT_LANGUAGE = booleanPreferencesKey("auto_detect_language")
        val TRANSLATE_TO_ENGLISH = booleanPreferencesKey("translate_to_english")
        // Output behaviour
        val AUTO_COPY_TO_CLIPBOARD = booleanPreferencesKey("auto_copy_to_clipboard")
        val AUTO_INSERT_TEXT = booleanPreferencesKey("auto_insert_text")
        val SAVE_TO_HISTORY = booleanPreferencesKey("save_to_history")
        // Auto-stop on silence
        val AUTO_STOP_SILENCE_MS = intPreferencesKey("auto_stop_silence_ms")
        // Voice Activity Detection
        val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        val VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        val VAD_MIN_SPEECH_DURATION_MS = intPreferencesKey("vad_min_speech_duration_ms")
        val VAD_MIN_SILENCE_DURATION_MS = intPreferencesKey("vad_min_silence_duration_ms")
        // Native VAD (whisper.cpp built-in)
        val NATIVE_VAD_ENABLED = booleanPreferencesKey("native_vad_enabled")
        val NATIVE_VAD_THRESHOLD = floatPreferencesKey("native_vad_threshold")
        val NATIVE_VAD_MIN_SPEECH_MS = intPreferencesKey("native_vad_min_speech_ms")
        val NATIVE_VAD_MIN_SILENCE_MS = intPreferencesKey("native_vad_min_silence_ms")
        val NATIVE_VAD_SPEECH_PAD_MS = intPreferencesKey("native_vad_speech_pad_ms")
        // Hallucination suppression
        val NO_SPEECH_THRESHOLD = floatPreferencesKey("no_speech_threshold")
        val LOGPROB_THRESHOLD = floatPreferencesKey("logprob_threshold")
        val ENTROPY_THRESHOLD = floatPreferencesKey("entropy_threshold")
        // Inference
        val INITIAL_PROMPT = stringPreferencesKey("initial_prompt")
        // Recording
        val MAX_RECORDING_DURATION_SEC = intPreferencesKey("max_recording_duration_sec")
    }

    /** Observe all settings as a reactive Flow. Missing keys fall back to AppSettings defaults. */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            darkMode = prefs[Keys.DARK_MODE] ?: defaults.darkMode,
            colorPalette = prefs[Keys.COLOR_PALETTE] ?: defaults.colorPalette,
            overlayEnabled = prefs[Keys.OVERLAY_ENABLED] ?: defaults.overlayEnabled,
            language = prefs[Keys.LANGUAGE] ?: defaults.language,
            autoDetectLanguage = prefs[Keys.AUTO_DETECT_LANGUAGE] ?: defaults.autoDetectLanguage,
            translateToEnglish = prefs[Keys.TRANSLATE_TO_ENGLISH] ?: defaults.translateToEnglish,
            autoCopyToClipboard = prefs[Keys.AUTO_COPY_TO_CLIPBOARD] ?: defaults.autoCopyToClipboard,
            autoInsertText = prefs[Keys.AUTO_INSERT_TEXT] ?: defaults.autoInsertText,
            saveToHistory = prefs[Keys.SAVE_TO_HISTORY] ?: defaults.saveToHistory,
            autoStopSilenceMs = prefs[Keys.AUTO_STOP_SILENCE_MS] ?: defaults.autoStopSilenceMs,
            vadEnabled = prefs[Keys.VAD_ENABLED] ?: defaults.vadEnabled,
            vadThreshold = prefs[Keys.VAD_THRESHOLD] ?: defaults.vadThreshold,
            vadMinSpeechDurationMs = prefs[Keys.VAD_MIN_SPEECH_DURATION_MS] ?: defaults.vadMinSpeechDurationMs,
            vadMinSilenceDurationMs = prefs[Keys.VAD_MIN_SILENCE_DURATION_MS] ?: defaults.vadMinSilenceDurationMs,
            nativeVadEnabled = prefs[Keys.NATIVE_VAD_ENABLED] ?: defaults.nativeVadEnabled,
            nativeVadThreshold = prefs[Keys.NATIVE_VAD_THRESHOLD] ?: defaults.nativeVadThreshold,
            nativeVadMinSpeechMs = prefs[Keys.NATIVE_VAD_MIN_SPEECH_MS] ?: defaults.nativeVadMinSpeechMs,
            nativeVadMinSilenceMs = prefs[Keys.NATIVE_VAD_MIN_SILENCE_MS] ?: defaults.nativeVadMinSilenceMs,
            nativeVadSpeechPadMs = prefs[Keys.NATIVE_VAD_SPEECH_PAD_MS] ?: defaults.nativeVadSpeechPadMs,
            noSpeechThreshold = prefs[Keys.NO_SPEECH_THRESHOLD] ?: defaults.noSpeechThreshold,
            logprobThreshold = prefs[Keys.LOGPROB_THRESHOLD] ?: defaults.logprobThreshold,
            entropyThreshold = prefs[Keys.ENTROPY_THRESHOLD] ?: defaults.entropyThreshold,
            initialPrompt = prefs[Keys.INITIAL_PROMPT] ?: defaults.initialPrompt,
            maxRecordingDurationSec = prefs[Keys.MAX_RECORDING_DURATION_SEC] ?: defaults.maxRecordingDurationSec,
        )
    }

    // UI theme
    suspend fun updateDarkMode(mode: String) {
        Timber.i("[SETTINGS] updateDarkMode | mode=%s", mode)
        context.dataStore.edit { it[Keys.DARK_MODE] = mode }
    }

    suspend fun updateColorPalette(palette: String) {
        Timber.i("[SETTINGS] updateColorPalette | palette=%s", palette)
        context.dataStore.edit { it[Keys.COLOR_PALETTE] = palette }
    }

    // Overlay
    suspend fun updateOverlayEnabled(enabled: Boolean) {
        Timber.i("[SETTINGS] updateOverlayEnabled | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.OVERLAY_ENABLED] = enabled }
    }



    // Transcription language
    suspend fun updateLanguage(language: String) {
        Timber.i("[SETTINGS] updateLanguage | language=%s", language)
        context.dataStore.edit { it[Keys.LANGUAGE] = language }
    }

    suspend fun updateAutoDetectLanguage(enabled: Boolean) {
        Timber.i("[SETTINGS] updateAutoDetectLanguage | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.AUTO_DETECT_LANGUAGE] = enabled }
    }

    suspend fun updateTranslateToEnglish(enabled: Boolean) {
        Timber.i("[SETTINGS] updateTranslateToEnglish | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.TRANSLATE_TO_ENGLISH] = enabled }
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

    // Voice Activity Detection
    suspend fun updateVadEnabled(enabled: Boolean) {
        Timber.i("[SETTINGS] updateVadEnabled | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.VAD_ENABLED] = enabled }
    }

    suspend fun updateVadThreshold(threshold: Float) {
        require(threshold in 0f..1f) { "VAD threshold must be in [0.0, 1.0], got $threshold" }
        Timber.i("[SETTINGS] updateVadThreshold | threshold=%.3f", threshold)
        context.dataStore.edit { it[Keys.VAD_THRESHOLD] = threshold }
    }

    suspend fun updateVadMinSpeechDurationMs(ms: Int) {
        Timber.i("[SETTINGS] updateVadMinSpeechDurationMs | ms=%d", ms)
        context.dataStore.edit { it[Keys.VAD_MIN_SPEECH_DURATION_MS] = ms }
    }

    suspend fun updateVadMinSilenceDurationMs(ms: Int) {
        Timber.i("[SETTINGS] updateVadMinSilenceDurationMs | ms=%d", ms)
        context.dataStore.edit { it[Keys.VAD_MIN_SILENCE_DURATION_MS] = ms }
    }

    // Native VAD (whisper.cpp built-in)
    suspend fun updateNativeVadEnabled(enabled: Boolean) {
        Timber.i("[SETTINGS] updateNativeVadEnabled | enabled=%b", enabled)
        context.dataStore.edit { it[Keys.NATIVE_VAD_ENABLED] = enabled }
    }

    suspend fun updateNativeVadThreshold(threshold: Float) {
        require(threshold in 0f..1f) { "Native VAD threshold must be in [0.0, 1.0], got $threshold" }
        Timber.i("[SETTINGS] updateNativeVadThreshold | threshold=%.3f", threshold)
        context.dataStore.edit { it[Keys.NATIVE_VAD_THRESHOLD] = threshold }
    }

    suspend fun updateNativeVadMinSpeechMs(ms: Int) {
        require(ms > 0) { "nativeVadMinSpeechMs must be > 0, got $ms" }
        Timber.i("[SETTINGS] updateNativeVadMinSpeechMs | ms=%d", ms)
        context.dataStore.edit { it[Keys.NATIVE_VAD_MIN_SPEECH_MS] = ms }
    }

    suspend fun updateNativeVadMinSilenceMs(ms: Int) {
        require(ms > 0) { "nativeVadMinSilenceMs must be > 0, got $ms" }
        Timber.i("[SETTINGS] updateNativeVadMinSilenceMs | ms=%d", ms)
        context.dataStore.edit { it[Keys.NATIVE_VAD_MIN_SILENCE_MS] = ms }
    }

    suspend fun updateNativeVadSpeechPadMs(ms: Int) {
        require(ms >= 0) { "nativeVadSpeechPadMs must be >= 0, got $ms" }
        Timber.i("[SETTINGS] updateNativeVadSpeechPadMs | ms=%d", ms)
        context.dataStore.edit { it[Keys.NATIVE_VAD_SPEECH_PAD_MS] = ms }
    }

    // Hallucination suppression
    suspend fun updateNoSpeechThreshold(threshold: Float) {
        require(threshold in 0f..1f) { "noSpeechThreshold must be in [0.0, 1.0], got $threshold" }
        Timber.i("[SETTINGS] updateNoSpeechThreshold | threshold=%.3f", threshold)
        context.dataStore.edit { it[Keys.NO_SPEECH_THRESHOLD] = threshold }
    }

    suspend fun updateLogprobThreshold(threshold: Float) {
        Timber.i("[SETTINGS] updateLogprobThreshold | threshold=%.3f", threshold)
        context.dataStore.edit { it[Keys.LOGPROB_THRESHOLD] = threshold }
    }

    suspend fun updateEntropyThreshold(threshold: Float) {
        require(threshold > 0f) { "entropyThreshold must be > 0, got $threshold" }
        Timber.i("[SETTINGS] updateEntropyThreshold | threshold=%.3f", threshold)
        context.dataStore.edit { it[Keys.ENTROPY_THRESHOLD] = threshold }
    }

    suspend fun updateInitialPrompt(prompt: String) {
        Timber.i("[SETTINGS] updateInitialPrompt | len=%d", prompt.length)
        context.dataStore.edit { it[Keys.INITIAL_PROMPT] = prompt }
    }

    // Recording & model selection
    suspend fun updateMaxRecordingDurationSec(sec: Int) {
        require(sec in 10..3600) { "maxRecordingDurationSec must be in [10, 3600], got $sec" }
        Timber.i("[SETTINGS] updateMaxRecordingDurationSec | sec=%d", sec)
        context.dataStore.edit { it[Keys.MAX_RECORDING_DURATION_SEC] = sec }
    }

    // Onboarding
    val onboardingComplete: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[Keys.ONBOARDING_COMPLETE] ?: false }

    suspend fun updateOnboardingComplete(complete: Boolean) {
        Timber.i("[SETTINGS] updateOnboardingComplete | complete=%b", complete)
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }
}
