package com.safeword.android.testutil

import kotlin.test.assertTrue

/**
 * Word Error Rate (WER) computation and assertion for pipeline accuracy tests.
 *
 * WER = (substitutions + insertions + deletions) / referenceLength
 */
object PipelineAssertions {

    /**
     * Assert that the processed [actual] text matches the [expected] text within the
     * given WER [threshold] (0.0 = perfect match, 0.15 = 15% word errors tolerated).
     */
    fun assertWithinWer(
        expected: String,
        actual: String,
        threshold: Double = 0.0,
        message: String = "",
    ) {
        val wer = computeWer(expected, actual)
        assertTrue(
            wer <= threshold,
            buildString {
                if (message.isNotEmpty()) append("$message — ")
                append("WER %.2f exceeds threshold %.2f".format(wer, threshold))
                append("\n  expected: \"$expected\"")
                append("\n  actual:   \"$actual\"")
            },
        )
    }

    /**
     * Compute word-level edit distance (Levenshtein) and return WER.
     */
    fun computeWer(reference: String, hypothesis: String): Double {
        val refWords = reference.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val hypWords = hypothesis.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (refWords.isEmpty()) return if (hypWords.isEmpty()) 0.0 else 1.0

        val n = refWords.size
        val m = hypWords.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (refWords[i - 1].equals(hypWords[j - 1], ignoreCase = true)) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost, // substitution
                )
            }
        }
        return dp[n][m].toDouble() / n
    }

    /**
     * Assert that the [actual] text is exactly [expected] after trimming.
     * Provides a clear diff message on failure.
     */
    fun assertPipelineOutput(expected: String, actual: String, label: String = "") {
        val prefix = if (label.isNotEmpty()) "$label: " else ""
        kotlin.test.assertEquals(
            expected.trim(),
            actual.trim(),
            "${prefix}Pipeline output mismatch",
        )
    }
}
