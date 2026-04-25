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
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings(),
    )

    fun updateDarkMode(mode: String) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateDarkMode | mode=%s", mode)
        settingsRepository.updateDarkMode(mode)
    }

    fun updateOverlayEnabled(enabled: Boolean) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateOverlayEnabled | enabled=%b", enabled)
        settingsRepository.updateOverlayEnabled(enabled)
    }

    fun updateHapticFeedbackEnabled(enabled: Boolean) = viewModelScope.launch {
        Timber.d("[SETTINGS] SettingsViewModel.updateHapticFeedbackEnabled | enabled=%b", enabled)
        settingsRepository.updateHapticFeedbackEnabled(enabled)
    }
}
