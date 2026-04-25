package com.safeword.android.transcription

/**
 * Runtime parameters for Safe Word STT post-processing pipeline.
 *
 * References:
 * - SpeechShield (arXiv:2502.01649): 0.8–0.9 confidence threshold optimal
 * - MobileBERT (arXiv:2004.02984): 80–120 ms inference on ARM64
 */
object OptimalParameters {

    // ── Confidence thresholds ──────────────────────────────────────────────


    /** Final voice command acceptance threshold (empirically optimal: 0.85). */
    const val VOICE_COMMAND_CONFIDENCE_THRESHOLD = 0.85f

    /** Semantic (embedding) command match threshold — lower bar than exact match. */
    const val SEMANTIC_COMMAND_CONFIDENCE_THRESHOLD = 0.80f

    /** MobileBERT token classification confidence threshold for SafeWord model corrections. */
    const val SAFEWORD_TRANSFORMER_CONFIDENCE_THRESHOLD = 0.82f

    // ── Per-category confidence thresholds (11-label model) ────────────────

    /** Threshold for REMOVE labels — high to avoid deleting real words. */
    const val SAFEWORD_REMOVE_CONFIDENCE_THRESHOLD = 0.90f

    /** Threshold for punctuation insertion (COMMA, PERIOD, QUESTION, EXCLAIM). */
    const val SAFEWORD_PUNCT_CONFIDENCE_THRESHOLD = 0.70f

    /** Threshold for capitalization actions (CAP bit). */
    const val SAFEWORD_CAPITALIZE_CONFIDENCE_THRESHOLD = 0.75f

    /** Below this confidence, a token is flagged as "uncertain" for downstream enhancement. */
    const val SAFEWORD_UNCERTAINTY_THRESHOLD = 0.70f

    // ── 11-label compound scheme constants ─────────────────────────────────

    /** Total number of labels in the SafeWord transformer model. */
    const val SAFEWORD_NUM_LABELS = 11

    const val SAFEWORD_LABEL_CORRECT = 0
    const val SAFEWORD_LABEL_CAP = 1
    const val SAFEWORD_LABEL_COMMA = 2
    const val SAFEWORD_LABEL_COMMA_CAP = 3
    const val SAFEWORD_LABEL_PERIOD = 4
    const val SAFEWORD_LABEL_PERIOD_CAP = 5
    const val SAFEWORD_LABEL_QUESTION = 6
    const val SAFEWORD_LABEL_QUESTION_CAP = 7
    const val SAFEWORD_LABEL_EXCLAIM = 8
    const val SAFEWORD_LABEL_EXCLAIM_CAP = 9
    const val SAFEWORD_LABEL_REMOVE = 10

    // ── Latency budgets ────────────────────────────────────────────────────

    /**
     * Max inference budget for SafeWord transformer (MobileBERT INT8 on ARM64: 80–120 ms).
     *
     * Profiled on flagship SoCs (Snapdragon 8 Gen 2+). Budget devices with Cortex-A55-only
     * clusters may exceed this by 2–3×. Consider device-tier detection to relax or skip
     * inference on low-end hardware.
     */
    const val MAX_LATENCY_MS_TRANSFORMER = 150L

    // ── Pipeline buffer caps ───────────────────────────────────────────────────

    /**
     * Safety cap for accumulated raw dictation text in [IncrementalTextInserter].
     * Beyond this length, the oldest words are trimmed to bound O(n) rule-stage cost.
     * ~2000 chars ≈ 300 words ≈ 5–6 minutes of typical speech.
     */
    const val MAX_ACCUMULATED_CHARS = 2000

    // ── Feature flags ──────────────────────────────────────────────────────

    /** Enable interim (partial) transcription results for UI streaming. */
    const val ENABLE_INTERIM_RESULTS = true

    // ── Accent / bias validation ───────────────────────────────────────────

    /** Max acceptable accuracy gap between native and non-native speakers (referenced by AccentBiasTest). */
    const val ACCENT_BIAS_FAIRNESS_THRESHOLD = 0.15f

    // ── Tier-aware helpers ─────────────────────────────────────────────────

    /**
     * Returns the confidence threshold for a command-detection tier.
     * @param tier 1 = exact, 2 = compositional, 3 = noise-strip, 4 = semantic
     */
    fun getConfidenceThresholdForTier(tier: Int): Float = when (tier) {
        1 -> 0.95f
        2 -> 0.90f
        3 -> 0.85f
        4 -> SEMANTIC_COMMAND_CONFIDENCE_THRESHOLD
        else -> VOICE_COMMAND_CONFIDENCE_THRESHOLD
    }
}
