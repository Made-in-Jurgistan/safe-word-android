package com.safeword.android.service

import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.di.ApplicationScope
import com.safeword.android.transcription.TranscriptionCoordinator
import com.safeword.android.transcription.TranscriptionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ViewModel layer for [FloatingOverlayService].
 *
 * Extracts overlay visibility logic, transcription toggle, and UI state observation
 * out of the Service class. The Service becomes a thin shell responsible only for
 * window management, notification, and lifecycle plumbing.
 *
 * This improves testability (ViewModel can be unit-tested without an Android Service)
 * and follows the architecture recommendation from the audit (Section 8.3).
 */
@Singleton
class OverlayViewModel @Inject constructor(
    private val transcriptionCoordinator: TranscriptionCoordinator,
    private val thermalMonitor: ThermalMonitor,
    private val settingsRepository: SettingsRepository,
    private val a11y: AccessibilityBridge,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /** Current transcription state — observed by the overlay ComposeView. */
    val transcriptionState: StateFlow<TranscriptionState> = transcriptionCoordinator.state

    /** Editable draft transcript shown in overlay while recording/streaming. */
    val draftText: StateFlow<String> = transcriptionCoordinator.draftText

    /** Current settings — observed for dark mode preference. */
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /**
     * Whether the overlay should be visible.
     *
     * True when the soft keyboard is showing OR an active transcription operation
     * (Recording, Streaming, Transcribing) is in progress.
     */
    val shouldShowOverlay: StateFlow<Boolean> = a11y.keyboardVisible
        .combine(transcriptionCoordinator.state) { imeVisible, state ->
            val activeOperation = state is TranscriptionState.Recording ||
                    state is TranscriptionState.Transcribing ||
                    state is TranscriptionState.Streaming
            imeVisible || activeOperation
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

    /** Whether the device is thermally throttled — can be used to tint the overlay. */
    val isThrottled: Boolean get() = thermalMonitor.isThrottled

    /**
     * Lifecycle mutex serialises [onServiceCreated] and [onServiceDestroyed]. If the
     * Android Service is recycled (stopService → startService) while a previous
     * destroy() is still draining native resources, the next preload would otherwise
     * race with a half-released Transcriber.
     */
    private val lifecycleMutex = Mutex()
    private var destroyJob: Job? = null

    /**
     * Launches preload on the application scope. Must not block — the Service calls this
     * from its main-thread [android.app.Service.onCreate], and blocking there is an ANR.
     * The [lifecycleMutex] serialises against a concurrent [onServiceDestroyed].
     */
    fun onServiceCreated() {
        scope.launch {
            lifecycleMutex.withLock {
                destroyJob?.let {
                    Timber.i("[LIFECYCLE] OverlayViewModel.onServiceCreated | awaiting prior destroy before preload")
                    it.join()
                    destroyJob = null
                }
                transcriptionCoordinator.preloadModels()
                thermalMonitor.start()
                Timber.i("[LIFECYCLE] OverlayViewModel.onServiceCreated | preload + thermal started")
            }
        }
    }

    fun onServiceDestroyed() {
        thermalMonitor.stop()
        destroyJob = scope.launch {
            lifecycleMutex.withLock {
                transcriptionCoordinator.destroy()
                Timber.i("[LIFECYCLE] OverlayViewModel.onServiceDestroyed | cleanup complete")
            }
        }
    }

    /**
     * Toggle recording on mic button tap.
     * Delegates state-machine transitions to [TranscriptionCoordinator].
     */
    fun toggleRecording() {
        val currentState = transcriptionCoordinator.state.value
        Timber.d("[SERVICE] OverlayViewModel.toggleRecording | state=%s", currentState)
        when (currentState) {
            is TranscriptionState.Idle,
            is TranscriptionState.Done,
            is TranscriptionState.Error,
            is TranscriptionState.CommandDetected -> transcriptionCoordinator.startRecording()
            is TranscriptionState.Recording,
            is TranscriptionState.Streaming -> transcriptionCoordinator.stopRecording()
            is TranscriptionState.Transcribing -> { /* busy finalizing, ignore tap */ }
        }
    }

    fun updateDraftText(text: String) {
        transcriptionCoordinator.updateDraftText(text)
    }

    fun onDraftFieldFocused() {
        transcriptionCoordinator.onDraftFieldFocused()
    }
}
