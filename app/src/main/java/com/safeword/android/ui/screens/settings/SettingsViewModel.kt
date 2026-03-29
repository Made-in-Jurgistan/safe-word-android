package com.safeword.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.transcription.WhisperLib
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings(),
    )

    private val _isRealWhisper = MutableStateFlow(false)
    val isRealWhisper: StateFlow<Boolean> = _isRealWhisper.asStateFlow()

    init {
        Timber.i("[INIT] SettingsViewModel | checking whisper JNI availability")
        viewModelScope.launch {
            _isRealWhisper.value = withContext(Dispatchers.Default) {
                try { WhisperLib.nativeIsRealWhisper() } catch (_: Throwable) { false }
            }
            Timber.d("[DIAGNOSTICS] SettingsViewModel | isRealWhisper=%b", _isRealWhisper.value)
        }
    }

    fun updateDarkMode(mode: String) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateDarkMode | mode=%s", mode)
        settingsRepository.updateDarkMode(mode)
    }

    fun updateColorPalette(palette: String) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateColorPalette | palette=%s", palette)
        settingsRepository.updateColorPalette(palette)
    }

    fun updateOverlayEnabled(enabled: Boolean) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateOverlayEnabled | enabled=%b", enabled)
        settingsRepository.updateOverlayEnabled(enabled)
    }

}
