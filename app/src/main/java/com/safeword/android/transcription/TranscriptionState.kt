package com.safeword.android.transcription

/**
 * Transcription state machine — mirrors TranscriptionCoordinator in desktop Safe Word.
 *
 * States: Idle → Recording → Transcribing → Done
 * Each state carries relevant data for the UI to observe via StateFlow.
 */
sealed interface TranscriptionState {
    /** Ready to record. No active operation. */
    data object Idle : TranscriptionState

    /** Actively capturing audio from the microphone. */
    data class Recording(
        val durationMs: Long = 0,
        val amplitudeDb: Float = -60f,
        val speechProbability: Float = 0f,
    ) : TranscriptionState

    /** Audio captured, currently running whisper inference. */
    data class Transcribing(
        val audioDurationMs: Long = 0,
        /** Live partial text accumulated from streaming segment callbacks. Empty until first segment arrives. */
        val partialText: String = "",
    ) : TranscriptionState

    /** Transcription complete. Text available. */
    data class Done(
        val result: TranscriptionResult,
    ) : TranscriptionState

    /** Voice command detected — should be executed by the IME, not committed as text. */
    data class CommandDetected(
        val action: VoiceAction,
    ) : TranscriptionState

    /** An error occurred at any stage. */
    data class Error(
        val message: String,
        val previousState: TranscriptionState = Idle,
    ) : TranscriptionState
}
