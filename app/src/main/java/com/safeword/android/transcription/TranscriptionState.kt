package com.safeword.android.transcription

/**
 * Typed outcome of a completed transcription operation.
 *
 * - [Success] carries the final text and performance metrics.
 * - [NoSpeech] signals that audio contained only silence or background noise
 *   (all output filtered by the hallucination guard or post-processing).
 *
 * Errors surface through [TranscriptionState.Error], which already owns the
 * Idle → Recording → Done → Idle state machine. This type represents only the
 * *content* of a successfully completed [TranscriptionState.Done].
 */
sealed interface TranscriptionResult {

    /**
     * Transcription produced non-blank text.
     * Mirrors the TranscriptionResult struct in desktop Safe Word's Rust backend.
     */
    data class Success(
        /** The transcribed text. */
        val text: String,

        /** Duration of the audio that was transcribed, in milliseconds. */
        val audioDurationMs: Long,

        /** Time taken for inference, in milliseconds. */
        val inferenceDurationMs: Long,

        /** Language detected (if multilingual model). */
        val language: String = "en",

        /** Timestamp when transcription completed. */
        val timestamp: Long = System.currentTimeMillis(),
    ) : TranscriptionResult

    /**
     * Audio segment contained no detectable speech — silence, background noise,
     * or the entire output was stripped by the hallucination / post-processing pass.
     */
    data object NoSpeech : TranscriptionResult
}

/**
 * Transcription state machine — mirrors TranscriptionCoordinator in desktop Safe Word.
 *
 * States: Idle → Recording → Done
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
        /** Live partial text from Moonshine streaming engine. */
        val partialText: String = "",
        /** Text already inserted into the text field via incremental accessibility insertion. */
        val insertedText: String = "",
    ) : TranscriptionState

    /** Transcription complete. Text available. */
    data class Done(
        val result: TranscriptionResult.Success,
    ) : TranscriptionState

    /** An error occurred at any stage. */
    data class Error(
        val message: String,
    ) : TranscriptionState
}
