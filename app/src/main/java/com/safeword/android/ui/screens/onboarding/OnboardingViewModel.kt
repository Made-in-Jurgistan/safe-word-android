package com.safeword.android.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * OnboardingViewModel — manages first-launch setup state.
 *
 * Handles model download and tracks completion via DataStore (through SettingsRepository).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
) : ViewModel() {

    companion object {
        const val DEFAULT_MODEL_ID = ModelInfo.WHISPER_MODEL_ID
        const val DEFAULT_MODEL_SIZE_DESC = "~273 MB"
    }

    val onboardingComplete: StateFlow<Boolean> = settingsRepository.onboardingComplete
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val _modelReady = MutableStateFlow(
        modelRepository.isModelDownloaded(DEFAULT_MODEL_ID),
    )
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val downloading = AtomicBoolean(false)

    init {
        Timber.d("[INIT] OnboardingViewModel | subscribing to download states")
        viewModelScope.launch {
            modelRepository.refreshStates()
            modelRepository.downloadStates
                .map { states -> states[DEFAULT_MODEL_ID] ?: ModelDownloadState.NotDownloaded }
                .distinctUntilChanged()
                .collectLatest { sttState ->
                    Timber.d("[STATE] OnboardingViewModel | stt=%s", sttState::class.simpleName)
                    _downloadState.value = sttState
                    if (sttState is ModelDownloadState.Downloaded) {
                        _modelReady.value = true
                        Timber.i("[STATE] OnboardingViewModel | model ready")
                    }
                }
        }

        if (modelRepository.isModelDownloaded(DEFAULT_MODEL_ID)) {
            Timber.d("[INIT] OnboardingViewModel | fast-path: model already on disk")
            _modelReady.value = true
            _downloadState.value = ModelDownloadState.Downloaded
        }
    }

    /** Check if onboarding has been completed previously. */
    fun isOnboardingComplete(): Boolean {
        val complete = onboardingComplete.value
        Timber.d("[STATE] OnboardingViewModel.isOnboardingComplete | complete=%b", complete)
        return complete
    }

    /** Mark onboarding as complete so it won't show again. */
    fun markOnboardingComplete() {
        Timber.i("[STATE] OnboardingViewModel.markOnboardingComplete")
        viewModelScope.launch {
            settingsRepository.updateOverlayEnabled(true)
            settingsRepository.updateOnboardingComplete(true)
        }
    }

    /** Start downloading the default model if not already downloaded/downloading. */
    fun ensureModelDownloaded() {
        if (_modelReady.value) {
            Timber.d("[DOWNLOAD] OnboardingViewModel.ensureModelDownloaded | already ready, skipping")
            return
        }
        if (!downloading.compareAndSet(false, true)) {
            Timber.d("[DOWNLOAD] OnboardingViewModel.ensureModelDownloaded | already downloading, skipping")
            return
        }

        Timber.i("[DOWNLOAD] OnboardingViewModel.ensureModelDownloaded | starting download stt=%s", DEFAULT_MODEL_ID)
        viewModelScope.launch {
            val sttSuccess = modelRepository.downloadModel(DEFAULT_MODEL_ID)
            if (sttSuccess) {
                Timber.i("[DOWNLOAD] OnboardingViewModel.ensureModelDownloaded | download complete")
                _modelReady.value = true
            } else {
                Timber.w("[DOWNLOAD] OnboardingViewModel.ensureModelDownloaded | download failed")
            }
            downloading.set(false)
        }
    }
}
