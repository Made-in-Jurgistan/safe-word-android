package com.safeword.android.transcription

/**
 * Phase 3 — Text Formatting.
 *
 * Applies cosmetic formatting to normalized content: whitespace cleanup,
 * sentence-case capitalization, pronoun "I" fix, and trailing punctuation
 * enforcement. These operations are order-sensitive and run after content
 * normalization (Phase 2).
 */
object TextFormatter {

    private val MULTI_SPACE_PATTERN = Regex(" {2,}")
    private val SENTENCE_PUNCT_SPACING_PATTERN = Regex("([.!?])(?=\\S)")
    private val PRONOUN_I_PATTERN = Regex("(?<=\\s|^)i(?=\\s|[.,!?;:]|$)")

    /**
     * Apply all formatting steps to normalized content.
     *
     * Pipeline order:
     *  1. Normalize whitespace (collapse multi-space runs)
     *  2. Trim
     *  3. Sentence-case capitalization
     *  4. Pronoun "I" fix
    *  5. Trailing punctuation enforcement
     */
    fun format(text: String): String {
        if (text.isBlank()) return ""

        var result = text

        // 1. Collapse multi-space runs
        result = MULTI_SPACE_PATTERN.replace(result, " ")

        // 2. Trim
        result = result.trim()

        // 3. Sentence-case capitalization
        result = applySentenceCase(result)

        // 4. Pronoun "I" fix
        result = PRONOUN_I_PATTERN.replace(result, "I")

        // 4.5 Ensure one space after sentence punctuation when another token follows.
        // Example: "Hello.World" -> "Hello. World"
        result = SENTENCE_PUNCT_SPACING_PATTERN.replace(result, "$1 ")

        // 5. Trailing punctuation enforcement
        if (result.isNotEmpty() && result.last() !in ".!?…") {
            result += "."
        }

        // 6. Trailing space — keeps cursor ready for next insertion.
        if (result.isNotEmpty()) {
            result += " "
        }

        return result
    }

    /**
     * Capitalize the first letter of each sentence (after `.`, `!`, `?`, `\n`, or at text start).
     */
    private fun applySentenceCase(text: String): String {
        if (text.isEmpty()) return text

        val sb = StringBuilder(text.length)
        var capitalizeNext = true

        for (ch in text) {
            when {
                capitalizeNext && ch.isLetter() -> {
                    sb.append(ch.uppercaseChar())
                    capitalizeNext = false
                }
                ch in ".!?" -> {
                    sb.append(ch)
                    capitalizeNext = true
                }
                ch == '\n' -> {
                    sb.append(ch)
                    capitalizeNext = true
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
