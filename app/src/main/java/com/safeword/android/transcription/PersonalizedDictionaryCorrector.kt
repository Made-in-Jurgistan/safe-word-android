package com.safeword.android.transcription

import com.safeword.android.data.db.PersonalizedEntryEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Applies user-defined word substitutions as the final post-processing phase.
 *
 * Substitutions are matched case-insensitively at word boundaries so that
 * "safeword" correctly replaces "safeword", "Safeword", and "SAFEWORD".
 * The original case pattern of the matched token is preserved in the output.
 *
 * This phase runs *after* [TextFormatter] so user preferences override all
 * automatic normalization.
 */
object PersonalizedDictionaryCorrector {

    /** Compiled word-boundary regex cache, keyed by fromPhrase (case-insensitive). */
    private val patternCache = ConcurrentHashMap<String, Regex>()

    /** Evict cached patterns when the entry list changes (called from [DefaultTextProcessor]). */
    fun invalidateCache() {
        patternCache.clear()
    }

    private fun patternFor(fromPhrase: String): Regex =
        patternCache.getOrPut(fromPhrase) {
            Regex("(?i)\\b${Regex.escape(fromPhrase)}\\b")
        }

    /**
     * Apply [entries] substitutions to [text].
     *
     * @return A [Result] containing the corrected text and the IDs of every
     *   entry that fired at least once (used to update use-count statistics).
     */
    fun apply(text: String, entries: List<PersonalizedEntryEntity>): Result {
        if (text.isBlank() || entries.isEmpty()) return Result(text, emptySet())

        var result = text
        val firedIds = mutableSetOf<Long>()

        for (entry in entries) {
            if (!entry.enabled || entry.fromPhrase.isBlank() || entry.toPhrase.isBlank()) continue

            val pattern = patternFor(entry.fromPhrase)
            val updated = pattern.replace(result) { match ->
                firedIds.add(entry.id)
                preserveCase(match.value, entry.toPhrase)
            }
            result = updated
        }

        return Result(result, firedIds)
    }

    data class Result(
        val text: String,
        /** IDs of entries that matched at least once in this pass. */
        val firedEntryIds: Set<Long>,
    )

    /** Preserve the case pattern of [original] when writing [replacement]. */
    internal fun preserveCase(original: String, replacement: String): String {
        return when {
            original.all { it.isUpperCase() } -> replacement.uppercase()
            original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercaseChar() }
            else -> replacement
        }
    }
}
