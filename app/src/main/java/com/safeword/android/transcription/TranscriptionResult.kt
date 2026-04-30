package com.safeword.android.transcription

/**
 * Result of a transcription operation.
 * Mirrors the TranscriptionResult struct in desktop Safe Word's Rust backend.
 */
data class TranscriptionResult(
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
)
