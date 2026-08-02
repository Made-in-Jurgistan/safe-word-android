package com.safeword.android.transcription

/**
 * Confidence threshold for trusted words.
 *
 * Used by [ConfusionSetCorrector] and the transcription pipeline to decide
 * when to apply aggressive vs conservative corrections.
 */
object WordConfidenceEstimator {

    /** Minimum confidence to consider a word "trusted". Below this, corrections are more aggressive. */
    const val TRUST_THRESHOLD = 0.7f
}
