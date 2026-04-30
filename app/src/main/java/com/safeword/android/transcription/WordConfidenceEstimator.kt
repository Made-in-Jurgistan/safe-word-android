package com.safeword.android.transcription

import timber.log.Timber

/**
 * Estimates per-word confidence from Moonshine SDK word timestamps.
 *
 * The SDK provides word-level timestamps when `word_timestamps = true`. This class
 * uses timing heuristics to estimate confidence:
 *  - Words produced at an abnormally high token rate → likely hallucinated (lower confidence).
 *  - Words with long preceding silence gaps → possible segment boundary artefacts.
 *  - Very short words (1–2 chars) in non-typical positions → higher correction priority.
 *
 * The [ConfusionSetCorrector] and [TextProcessor] pipeline use these scores to
 * decide when to apply aggressive vs conservative corrections.
 */
object WordConfidenceEstimator {

    /** Minimum confidence to consider a word "trusted". Below this, corrections are more aggressive. */
    const val TRUST_THRESHOLD = 0.7f

    data class WordWithConfidence(
        val word: String,
        val startMs: Long,
        val endMs: Long,
        val confidence: Float,
    )

    /**
     * Estimate per-word confidence from raw text and optional word timestamp data.
     *
     * When word timestamps are unavailable (empty list), uniform confidence of 0.85
     * is assigned to all words — the standard behaviour before this feature was added.
     *
     * @param text The completed line text.
     * @param wordTimestamps Pairs of (startMs, endMs) per word, in order. May be empty
     *   if the SDK does not provide timestamps for this line.
     * @return List of [WordWithConfidence] with inferred confidence scores.
     */
    fun estimate(text: String, wordTimestamps: List<Pair<Long, Long>> = emptyList()): List<WordWithConfidence> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        // No timestamps available — return uniform confidence.
        if (wordTimestamps.isEmpty() || wordTimestamps.size != words.size) {
            if (wordTimestamps.isNotEmpty() && wordTimestamps.size != words.size) {
                Timber.w("[CONFIDENCE] WordConfidenceEstimator | timestamp count (%d) != word count (%d), falling back to uniform",
                    wordTimestamps.size, words.size)
            }
            return words.map { WordWithConfidence(it, 0L, 0L, 0.85f) }
        }

        // Compute per-word duration and inter-word gaps.
        val durations = wordTimestamps.map { (start, end) -> (end - start).coerceAtLeast(1) }
        val avgDuration = durations.average().coerceAtLeast(1.0)

        return words.mapIndexed { i, word ->
            val (start, end) = wordTimestamps[i]
            val duration = durations[i]

            var confidence = 1.0f

            // Penalty: very fast words (< 40% of avg duration) — possible hallucination.
            if (duration < avgDuration * 0.4) {
                confidence *= 0.6f
            }

            // Penalty: very long words relative to character count — possible misrecognition.
            val charsPerMs = word.length.toFloat() / duration
            if (charsPerMs < 0.005f && word.length > 3) {
                confidence *= 0.75f
            }

            // Penalty: abnormally large gap from previous word (> 500ms).
            if (i > 0) {
                val gap = start - wordTimestamps[i - 1].second
                if (gap > 500) {
                    confidence *= 0.8f
                }
            }

            // Penalty: very short words (1-2 chars) that aren't common short words.
            if (word.length <= 2 && word.lowercase() !in COMMON_SHORT_WORDS) {
                confidence *= 0.7f
            }

            // Boost: longer words with normal timing are usually more reliable.
            if (word.length >= 5 && duration >= avgDuration * 0.6) {
                confidence = (confidence * 1.1f).coerceAtMost(1.0f)
            }

            WordWithConfidence(word, start, end, confidence.coerceIn(0.0f, 1.0f))
        }
    }

    /**
     * Returns indices of low-confidence words that should receive more aggressive
     * correction from [ConfusionSetCorrector].
     */
    fun lowConfidenceIndices(words: List<WordWithConfidence>): Set<Int> {
        return words.indices.filter { words[it].confidence < TRUST_THRESHOLD }.toSet()
    }

    private val COMMON_SHORT_WORDS = setOf(
        "i", "a", "an", "am", "as", "at", "be", "by", "do", "go",
        "he", "if", "in", "is", "it", "me", "my", "no", "of", "on",
        "or", "so", "to", "up", "us", "we",
    )
}
