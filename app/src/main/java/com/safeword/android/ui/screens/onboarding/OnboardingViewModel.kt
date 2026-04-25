package com.safeword.android.ui.screens.onboarding

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import com.safeword.android.util.InstallSourceDetector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.data.settings.OnboardingRepository
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.service.AccessibilityStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * OnboardingViewModel — manages first-launch setup state.
 *
 * Steps (easiest first, hardest last):
 * 1. Microphone — one-tap system dialog
 * 2. Speech model download — auto-background download
 * 3. Overlay — requires leaving app (skippable)
 * 4. Accessibility — requires leaving app; adaptive restriction help for sideloaded installs
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val accessibilityState: AccessibilityStateHolder,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    companion object {
        const val DEFAULT_MODEL_ID = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        const val DEFAULT_MODEL_SIZE_DESC = "~235 MB"

        /** Overlay step index; used in [markOnboardingComplete] to conditionally enable overlay. */
        internal const val STEP_OVERLAY = 3

        /** Steps that the user may skip without blocking onboarding. */
        val SKIPPABLE_STEPS = setOf(STEP_OVERLAY)
    }

    val onboardingComplete: StateFlow<Boolean> = onboardingRepository.onboardingComplete
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Reactive accessibility-service active state.
     *
     * Emits immediately when [SafeWordAccessibilityService.onServiceConnected] fires,
     * fixing the timing race where [isAccessibilityActive] returned false because the
     * activity had already resumed before Android called onServiceConnected.
     */
    val accessibilityActive: StateFlow<Boolean> = accessibilityState.serviceActive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accessibilityState.isActive())

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotDownloaded)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady.asStateFlow()

    private val _skippedSteps = MutableStateFlow<Set<Int>>(emptySet())
    val skippedSteps: StateFlow<Set<Int>> = _skippedSteps.asStateFlow()

    /** True when app is sideloaded and restricted settings may block overlay/a11y. */
    val isSideloaded: Boolean = InstallSourceDetector.isSideloaded(appContext)

    private val downloading = AtomicBoolean(false)

    /**
     * Android 13+ ACCESS_RESTRICTED_SETTINGS AppOps phase for sideloaded apps.
     *
     * [BLOCKED] — toggle in Accessibility Settings is greyed out; user must first attempt to
     *              enable (which triggers the system dialog and advances state to [TRIGGERED]).
     * [TRIGGERED] — system dialog was shown; "Allow restricted settings" now appears in App Info ⋮.
     * [ALLOWED] — restriction lifted (or never applied); accessibility toggle works normally.
     */
    enum class RestrictedState { BLOCKED, TRIGGERED, ALLOWED }

    /**
     * Returns the current [RestrictedState] for this package using the authoritative AppOps check.
     *
     * Falls back to the install-source heuristic if the AppOps call fails (e.g., unusual ROM).
     * Always returns [RestrictedState.ALLOWED] for Play Store / trusted-store installs.
     *
     * On API 35+ (Android 15+), Enhanced Confirmation Mode replaces the old "Restricted Settings"
     * flow. The system shows an inline confirmation dialog when the user toggles the service,
     * so the separate "Allow restricted settings" step is unnecessary and the legacy AppOps
     * value is unreliable.
     */
    fun restrictedState(): RestrictedState {
        if (!isSideloaded) return RestrictedState.ALLOWED

        // Android 15+ uses Enhanced Confirmation Mode - restriction is handled inline
        // when the user toggles the service in Accessibility Settings
        return RestrictedState.ALLOWED
    }

    /** Whether the accessibility service is currently active. */
    fun isAccessibilityActive(): Boolean = accessibilityState.isActive()

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

        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { modelRepository.isModelDownloaded(DEFAULT_MODEL_ID) }
            if (ready) {
                Timber.d("[INIT] OnboardingViewModel | fast-path: model already on disk")
                _modelReady.value = true
                _downloadState.value = ModelDownloadState.Downloaded
            } else {
                // Start download immediately (P9 — don't wait until step 3).
                ensureModelDownloaded()
            }
        }

        // Restore skipped steps from DataStore.
        viewModelScope.launch {
            _skippedSteps.value = onboardingRepository.skippedSteps.first()
        }
    }

    /** Restore persisted step, or default to 1. */
    suspend fun restoreStep(): Int {
        val saved = onboardingRepository.onboardingStep.first()
        return if (saved > 0) saved else 1
    }

    /** Persist current step to DataStore so process death doesn't lose it. */
    fun persistStep(step: Int) {
        viewModelScope.launch {
            onboardingRepository.updateOnboardingStep(step)
        }
    }

    /** Mark a step as skipped. */
    fun skipStep(step: Int) {
        require(step in SKIPPABLE_STEPS) { "Step $step is not skippable" }
        val updated = _skippedSteps.value + step
        _skippedSteps.value = updated
        Timber.i("[STATE] OnboardingViewModel.skipStep | step=%d, skipped=%s", step, updated)
        viewModelScope.launch {
            onboardingRepository.updateSkippedSteps(updated)
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
            // Only enable overlay if user didn't skip it.
            if (STEP_OVERLAY !in _skippedSteps.value) {
                settingsRepository.updateOverlayEnabled(true)
            }
            onboardingRepository.updateOnboardingComplete(true)
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
