package com.safeword.android.transcription

/**
 * Shared text-processing utilities for the transcription pipeline.
 *
 * Contains string-distance functions and common regex constants used
 * across normalization, correction, and command detection.
 */

/**
 * Standard dynamic-programming Levenshtein edit distance.
 *
 * Uses two pre-allocated rows swapped in-place — O(min(|a|,|b|)) memory, zero
 * per-call heap allocation.
 *
 * Also applies early termination: once every cell in the current row exceeds
 * [maxDistance] the strings cannot be within distance — returns [maxDistance]+1
 * immediately.  Pass [Int.MAX_VALUE] (default) to disable early termination and
 * compute the exact distance.
 */
internal fun levenshteinDistance(a: String, b: String, maxDistance: Int = Int.MAX_VALUE): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val (shorter, longer) = if (a.length <= b.length) a to b else b to a
    // Two rows reused across outer iterations — no per-iteration allocation.
    var prev = IntArray(shorter.length + 1) { it }
    var curr = IntArray(shorter.length + 1)
    for (j in longer.indices) {
        curr[0] = j + 1
        var rowMin = curr[0]
        for (i in shorter.indices) {
            val cost = if (shorter[i] == longer[j]) 0 else 1
            curr[i + 1] = minOf(curr[i] + 1, prev[i + 1] + 1, prev[i] + cost)
            if (curr[i + 1] < rowMin) rowMin = curr[i + 1]
        }
        // Early exit: no cell can improve below rowMin in subsequent rows.
        if (rowMin > maxDistance) return maxDistance + 1
        // Swap rows without allocation.
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[shorter.length]
}

/** Collapses runs of two-or-more spaces/tabs to a single space. */
internal val MULTI_SPACE_REGEX = Regex("[ \t]{2,}")

/** Splits on any Unicode whitespace sequence. */
internal val WHITESPACE_SPLIT_REGEX = Regex("\\s+")

/**
 * Clamps an accessibility-API cursor offset to a valid index within [text].
 *
 * Shared across the service package ([com.safeword.android.service.SafeWordAccessibilityService]
 * and [com.safeword.android.service.VoiceActionExecutor]) to avoid duplicated `coerceIn` calls
 * when interpreting `textSelectionStart` / `textSelectionEnd` values from [android.view.accessibility.AccessibilityNodeInfo].
 */
internal fun Int.clampCursor(text: String): Int = coerceIn(0, text.length)
