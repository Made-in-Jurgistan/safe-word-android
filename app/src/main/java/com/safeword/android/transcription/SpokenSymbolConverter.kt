package com.safeword.android.transcription

/**
 * Steps 6–7 of the ContentNormalizer pipeline — spoken emoji and punctuation conversion.
 *
 * Converts spoken-word representations ("heart emoji", "period") to their
 * Unicode/symbol equivalents, then applies space-cleanup rules so the output
 * is typographically correct. Extracted from [ContentNormalizer].
 */
internal object SpokenSymbolConverter {

    // Note: trimEnd() intentionally NOT used here — trailing spaces on "period", "dot",
    // "full stop" (→ ". ") must be preserved so mid-text conversion produces a space
    // after the period (e.g. "went period to" → "went. to", not "went.to").
    // ContentNormalizer.normalizePostPreProcess() applies MULTI_SPACE_REGEX after this
    // step, collapsing any resulting double-space to a single space.
    private val SPOKEN_PUNCTUATION_MAP: Map<String, String> =
        SpokenPunctuationTable.entries

    private val SPOKEN_PUNCTUATION_PATTERN: Regex = run {
        val alts = SPOKEN_PUNCTUATION_MAP.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex("(?i)\\b(?:$alts)\\b")
    }

    // Only "formal" emoji phrases (keys ending in "emoji") are eligible for inline
    // mid-text conversion. Internet shorthand aliases ("lol", "omg", "rip", "fyi",
    // "gg", "smh", "lmao", "ngl") are excluded here to prevent false-positive emoji
    // insertion when those acronyms appear in ordinary dictation.
    // Standalone commands ("fyi" → 💡) still work via COMMAND_MAP in VoiceCommandDetector.
    private val SPOKEN_EMOJI_PATTERN: Regex = run {
        val alts = SpokenEmojiTable.entries.keys
            .filter { it.endsWith("emoji") }
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex("(?i)\\b(?:$alts)\\b")
    }

    private val OPENING_SYMBOLS = setOf("(", "[", "{", "\"", "'", "\u20AC", "\u00A5")

    private val PUNCT_SPACE_CLEANUP = Regex("\\s+([.,!?;:…)}\\]'%_\\\\\u00AE\u2122\u00A9\u00B0\u00A2])")
    private val OPEN_BRACKET_SPACE_CLEANUP = Regex("([\\[({'\\$\u20AC\u00A5_\\\\])\\s+")
    private val NEWLINE_SPACE_CLEANUP = Regex("[ \\t]*\\n[ \\t]*")
    private val HYPHEN_SPACE_CLEANUP = Regex(" +- +")
    private val HASHTAG_SPACE_CLEANUP = Regex("#\\s+(?=\\w)")
    private val AT_SIGN_SPACE_CLEANUP = Regex("@\\s+(?=\\w)")
    private val CURRENCY_SPACE_CLEANUP = Regex("[\\$\u20AC\u00A5]\\s+(?=\\d)")
    private val DOT_DOT_DOT_PATTERN = Regex("(?i)\\bdot\\s+dot\\s+dot\\b")

    // -- Public API -----------------------------------------------------------

    /**
     * Convert spoken emoji and punctuation to symbols and apply space-cleanup rules.
     */
    fun convert(text: String): String {
        var result = convertSpokenEmoji(text)
        result = convertSpokenPunctuation(result)
        return result
    }

    /**
     * Early-phase guard: convert "dot dot dot" → "…" so the stutter-dedup step in
     * [DisfluencyNormalizer] cannot collapse the three repeated words into one.
     * Called from [ContentNormalizer.preProcess] before disfluency normalization.
     */
    internal fun protectDotDotDot(text: String): String =
        DOT_DOT_DOT_PATTERN.replace(text, "…")

    // -- Internal helpers used by model-first normalization phases --------

    internal fun convertSpokenEmoji(text: String): String {
        val result = SPOKEN_EMOJI_PATTERN.replace(text) { match ->
            SpokenEmojiTable.entries[match.value.lowercase()] ?: match.value
        }
        return MULTI_SPACE_REGEX.replace(result, " ")
    }

    internal fun convertSpokenPunctuation(text: String): String {
        // Note: "dot dot dot" was already converted to "…" in ContentNormalizer.preProcess()
        // via protectDotDotDot(). This replacement is intentionally a no-op in that path.
        // It fires for callers that invoke convert() directly without a prior preProcess().
        var result = DOT_DOT_DOT_PATTERN.replace(text, "…")
        val current = result
        result = SPOKEN_PUNCTUATION_PATTERN.replace(current) { match ->
            val symbol = SPOKEN_PUNCTUATION_MAP[match.value.lowercase()] ?: match.value
            val before = current.getOrNull(match.range.first - 1)
            if (symbol in OPENING_SYMBOLS && before != null && !before.isWhitespace()) " $symbol" else symbol
        }
        result = PUNCT_SPACE_CLEANUP.replace(result, "$1")
        result = OPEN_BRACKET_SPACE_CLEANUP.replace(result, "$1")
        result = NEWLINE_SPACE_CLEANUP.replace(result, "\n")
        result = HYPHEN_SPACE_CLEANUP.replace(result, "-")
        result = HASHTAG_SPACE_CLEANUP.replace(result, "#")
        result = AT_SIGN_SPACE_CLEANUP.replace(result, "@")
        result = CURRENCY_SPACE_CLEANUP.replace(result) { it.value.take(1) }
        return result
    }
}
