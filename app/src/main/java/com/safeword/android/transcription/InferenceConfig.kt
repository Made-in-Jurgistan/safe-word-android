package com.safeword.android.transcription

/**
 * Dynamically computes optimal inference parameters for the current device.
 *
 * Whisper threads target 2-4 big cores on big.LITTLE SoCs.
 */
object InferenceConfig {

    /**
     * Target performance-cluster cores on big.LITTLE SoCs.
     * Exynos 2400: 1× X4 + 3× A720 + 2× A720 = 6 P-cores; avoid 4× A520 E-cores.
     * Snapdragon 8 Gen 3: 1× X4 + 3× A720 + 2× A720 = 6 P-cores; avoid 2× A520.
     */
    fun optimalWhisperThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 4
            cores >= 6 -> 4
            else -> (cores - 1).coerceIn(2, 4)
        }
    }

    /**
     * Audio-duration-aware thread count: short clips (<2s) benefit from fewer threads
     * to reduce scheduling overhead; longer clips use the full heuristic.
     */
    fun optimalWhisperThreads(audioDurationSec: Float): Int {
        if (audioDurationSec <= 2f) {
            return 2
        }
        return optimalWhisperThreads()
    }
}
