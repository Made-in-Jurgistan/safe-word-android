package com.safeword.android.domain.usecase

import com.safeword.android.transcription.TranscriptionCoordinator
import com.safeword.android.transcription.TranscriptionState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for managing transcription operations.
 * Encapsulates transcription business logic and provides a clean interface for the UI layer.
 */
@Singleton
class TranscriptionUseCase @Inject constructor(
    private val transcriptionCoordinator: TranscriptionCoordinator
) {

    val transcriptionState: StateFlow<TranscriptionState> = transcriptionCoordinator.state

    fun startTranscription() {
        transcriptionCoordinator.startRecording()
    }

    fun stopTranscription() {
        transcriptionCoordinator.stopRecording()
    }

    fun resetToIdle() {
        // Reset to idle is handled automatically by the coordinator after a delay
        // This method cancels any ongoing recording and triggers immediate reset
        transcriptionCoordinator.cancel()
    }
}
