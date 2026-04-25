package com.safeword.android.transcription

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks per-session performance metrics for transcription.
 * These metrics are reset at the start of each recording session.
 */
class SessionMetrics {
    val commandsDetected = AtomicInteger(0)
    val linesInserted = AtomicInteger(0)
    val confidenceSum = AtomicLong(0L)  // confidence * 1000 for integer math
    val confidenceCount = AtomicInteger(0)
    val partialUpdateEpoch = AtomicLong(0L)
    var recordingStartTime: Long = 0L

    /**
     * Resets all metrics to their initial state.
     */
    fun reset() {
        commandsDetected.set(0)
        linesInserted.set(0)
        confidenceSum.set(0L)
        confidenceCount.set(0)
        partialUpdateEpoch.set(0L)
        recordingStartTime = 0L
    }

    /**
     * Gets the average confidence score (0.0 to 1.0).
     */
    fun getAverageConfidence(): Double {
        val count = confidenceCount.get()
        if (count == 0) return 0.0
        return (confidenceSum.get().toDouble() / count) / 1000.0
    }

    /**
     * Records a confidence score.
     */
    fun recordConfidence(confidence: Float) {
        confidenceSum.addAndGet((confidence * 1000).toLong())
        confidenceCount.incrementAndGet()
    }
}
