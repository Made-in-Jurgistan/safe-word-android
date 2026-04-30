package com.safeword.android.transcription

/**
 * Phase 3 — Text Formatting.
 *
 * Applies cosmetic formatting to normalized content: whitespace cleanup,
 * sentence-case capitalization, pronoun "I" fix, and trailing punctuation
 * enforcement. These operations are order-sensitive and run after content
 * normalization (Phase 1) and punctuation prediction (Phase 2).
 *
 * **Moonshine-native-output aware**: Moonshine v2 produces cased, punctuated
 * text. Every step here is *additive* — it never downcases letters the model
 * already capitalised, and trailing-punctuation enforcement guards against
 * double terminal punctuation that can arise when the model's own period
 * collides with [ContentNormalizer] spoken-punctuation insertion.
 */
object TextFormatter {

    private val MULTI_SPACE_PATTERN = Regex(" {2,}")
    private val SENTENCE_PUNCT_SPACING_PATTERN = Regex("([.!?])(?=\\S)")
    private val PRONOUN_I_PATTERN = Regex("(?<=\\s|^)i(?=\\s|[.,!?;:]|$)")

    /** Matches doubled terminal punctuation produced by model + pipeline overlap. */
    private val DOUBLE_TERMINAL_PUNCT = Regex("([.!?…])\\1+\\s*$")

    /** Known acronyms that should always be uppercased. */
    private val ACRONYMS = setOf(
        "usa", "uk", "eu", "un", "nato", "nasa", "fbi", "cia", "nsa",
        "fda", "epa", "irs", "doj", "dhs", "osha", "sec",
        "api", "sdk", "url", "html", "css", "json", "xml", "sql", "http", "https",
        "ai", "ml", "gpt", "llm", "cpu", "gpu", "ram", "ssd", "hdd", "usb", "hdmi",
        "ios", "pdf", "ui", "ux", "vpn", "dns", "ip", "tcp", "wifi",
        "ceo", "cfo", "cto", "coo", "vp", "hr", "pr", "qa",
        "asap", "rsvp", "fyi", "eta", "diy", "faq",
        "gps", "atm", "id", "pin",
    )

    /** Days of the week (lowercase) → should be capitalized. */
    private val DAYS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    /** Months (lowercase) → should be capitalized. */
    private val MONTHS = setOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    private val WORD_BOUNDARY_PATTERN = Regex("\\b[a-zA-Z]+\\b")

    /**
     * Apply all formatting steps to normalized content.
     *
     * Pipeline order:
     *  1. Normalize whitespace (collapse multi-space runs)
     *  2. Trim
     *  3. Collapse doubled terminal punctuation (model + pipeline overlap guard)
     *  4. Sentence-case capitalization (additive — never downcases)
     *  5. Pronoun "I" fix
     *  6. Trailing punctuation enforcement
     */
    fun format(text: String): String {
        if (text.isBlank()) return ""

        var result = text

        // 1. Collapse multi-space runs
        result = MULTI_SPACE_PATTERN.replace(result, " ")

        // 2. Trim
        result = result.trim()

        // 3. Collapse doubled terminal punctuation (e.g. model "." + spoken-punct ".")
        //    Must run before sentence-punct spacing which would separate ".." → ". ."
        result = DOUBLE_TERMINAL_PUNCT.replace(result) { it.groupValues[1] }

        // 4. Sentence-case capitalization
        result = applySentenceCase(result)

        // 5. Pronoun "I" fix
        result = PRONOUN_I_PATTERN.replace(result, "I")

        // 5.1 Acronym uppercasing ("nasa" → "NASA", "api" → "API")
        result = applySmartCapitalization(result)

        // 5.5 Ensure one space after sentence punctuation when another token follows.
        // Example: "Hello.World" -> "Hello. World"
        result = SENTENCE_PUNCT_SPACING_PATTERN.replace(result, "$1 ")

        // 6. Trailing punctuation enforcement — only when model omitted it
        if (result.isNotEmpty() && result.last() !in ".!?…") {
            result += "."
        }

        // 7. Trailing space — keeps cursor ready for next insertion.
        if (result.isNotEmpty()) {
            result += " "
        }

        return result
    }

    /**
     * Uppercase known acronyms and capitalize proper nouns (days of week, months).
     */
    private fun applySmartCapitalization(text: String): String {
        return WORD_BOUNDARY_PATTERN.replace(text) { match ->
            val word = match.value
            val lower = word.lowercase()
            when {
                lower in ACRONYMS -> word.uppercase()
                lower in DAYS || lower in MONTHS -> word.replaceFirstChar { it.uppercaseChar() }
                else -> word
            }
        }
    }

    /**
     * Capitalize the first letter of each sentence (after `.`, `!`, `?`, `\n`, or at text start).
     *
     * This is **additive-only**: it uppercases chars after sentence boundaries but
     * never lowercases anything. Moonshine v2's native casing of proper nouns,
     * acronyms, etc. is preserved because no character is ever downcased here.
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
