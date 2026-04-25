package com.safeword.android.transcription

/**
 * Phase 3 — Text Formatting.
 *
 * Applies cosmetic formatting to normalized content: whitespace cleanup,
 * space after sentence punctuation, and trailing space for cursor readiness.
 *
 * Sentence-case capitalization, pronoun "I" fix, and trailing punctuation
 * enforcement have been removed — Moonshine outputs properly cased and
 * punctuated text natively.
 */
object TextFormatter {

    private val SENTENCE_PUNCT_SPACING_PATTERN = Regex("([.!?])(?=\\S)")

    /**
     * Apply formatting to normalized content.
     *
     * Pipeline order:
     *  1. Normalize whitespace (collapse multi-space runs)
     *  2. Trim
     *  3. Space after sentence-terminal punctuation
     */
    fun format(text: String): String {
        if (text.isBlank()) return ""

        var result = text

        // 1. Collapse multi-space/tab runs
        result = MULTI_SPACE_REGEX.replace(result, " ")

        // 2. Trim
        result = result.trim()

        // 3. Ensure one space after sentence punctuation when another token follows.
        result = SENTENCE_PUNCT_SPACING_PATTERN.replace(result, "$1 ")

        // Note: Trailing space removed — was causing double-spaces in incremental insertion.
        // Cursor positioning is handled by the text field directly, not via trailing space.

        return result
    }
}
