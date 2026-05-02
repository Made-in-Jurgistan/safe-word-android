package com.safeword.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings(),
    )



    fun updateDarkMode(mode: String) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateDarkMode | mode=%s", mode)
        settingsRepository.updateDarkMode(mode)
    }

    fun updateOverlayEnabled(enabled: Boolean) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateOverlayEnabled | enabled=%b", enabled)
        settingsRepository.updateOverlayEnabled(enabled)
    }

    // P3: Expose streaming interval and word timestamps settings
    fun updateStreamingUpdateIntervalMs(ms: Int) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateStreamingUpdateIntervalMs | ms=%d", ms)
        settingsRepository.updateStreamingUpdateIntervalMs(ms)
    }

    fun updateEnableWordTimestamps(enabled: Boolean) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateEnableWordTimestamps | enabled=%b", enabled)
        settingsRepository.updateEnableWordTimestamps(enabled)
    }

    fun updateCommandModeEnabled(enabled: Boolean) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateCommandModeEnabled | enabled=%b", enabled)
        settingsRepository.updateCommandModeEnabled(enabled)
    }

}
