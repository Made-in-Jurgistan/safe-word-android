package com.safeword.android.transcription

import com.safeword.android.data.db.PersonalVocabularyEntity
import org.apache.commons.codec.language.DoubleMetaphone
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Phonetic index for personal vocabulary.
 *
 * Maps the Double Metaphone code(s) of the first word of each vocabulary phrase
 * to the phrase, enabling fuzzy phonetic lookup in [ConfusionSetCorrector] Layer 4.
 *
 * Each entry is indexed under its primary code and, when distinct, its alternate code,
 * so phonetically ambiguous words (e.g. "Brion" / "Brian" / "Bryon") are grouped together.
 *
 * Thread-safe: the index map is published atomically via a @Volatile reference.
 */
@Singleton
class PhoneticIndex @Inject constructor() {

    /** Double Metaphone encoder instance (thread-safe, stateless). */
    private val dm = DoubleMetaphone()

    /** dmCode(firstWord(phrase)) → list of phrases with that code. */
    @Volatile
    private var index: Map<String, List<String>> = emptyMap()

    private val lock = Any()

    // ── Index management ────────────────────────────────────────────────────

    /**
     * Rebuild the phonetic index from the current vocabulary.
     *
     * **Important**: only the **first word** of each phrase is indexed. Multi-word
     * phrases such as "New York" are indexed under the phonetic code for "New" only.
     * This is intentional — it keeps lookup O(1) at the cost of not matching phrases
     * by an interior or final word.
     *
     * Call whenever the personal vocabulary changes.
     */
    fun rebuild(vocabulary: List<PersonalVocabularyEntity>) {
        val newIndex = HashMap<String, MutableList<String>>()
        for (entry in vocabulary) {
            if (entry.phrase.isBlank()) continue
            val firstWord = entry.phrase.trim().split(WORD_SPLIT).first()
            val primary = dm.doubleMetaphone(firstWord) ?: continue
            newIndex.getOrPut(primary) { mutableListOf() }.add(entry.phrase)
            val alternate = dm.doubleMetaphone(firstWord, true)
            if (alternate != null && alternate != primary) {
                newIndex.getOrPut(alternate) { mutableListOf() }.add(entry.phrase)
            }
        }
        synchronized(lock) { index = newIndex }
        Timber.d("[INIT] PhoneticIndex.rebuild | entries=%d codes=%d", vocabulary.size, newIndex.size)
    }

    /**
     * Return vocabulary phrases whose first word has the same Double Metaphone code as
     * [token] **and** whose Levenshtein distance to [token] is ≤ [maxDistance].
     *
     * Both the primary and alternate codes of [token] are tried so that phonetically
     * ambiguous spellings (e.g. "Trunt" / "Trent") are correctly matched.
     * The [token] itself (exact, case-insensitive) is excluded from results.
     */
    fun candidatesFor(token: String, maxDistance: Int = 1): List<String> {
        if (token.length < 2) return emptyList()
        val primary = dm.doubleMetaphone(token) ?: return emptyList()
        val alternate = dm.doubleMetaphone(token, true)
        val seen = mutableSetOf<String>()
        val results = mutableListOf<String>()
        for (code in setOfNotNull(primary, alternate?.takeIf { it != primary })) {
            for (phrase in index[code] ?: emptyList()) {
                if (!seen.add(phrase)) continue
                val firstWord = phrase.split(WORD_SPLIT).first()
                if (!firstWord.equals(token, ignoreCase = true) &&
                    levenshteinDistance(token.lowercase(), firstWord.lowercase(), maxDistance) <= maxDistance
                ) {
                    results += phrase
                }
            }
        }
        return results
    }

    /** Test shim: delegates to [levenshteinDistance] for direct unit testing. */
    internal fun levenshtein(a: String, b: String): Int = levenshteinDistance(a, b)

    companion object {
        private val WORD_SPLIT = Regex("\\s+")
    }
}
