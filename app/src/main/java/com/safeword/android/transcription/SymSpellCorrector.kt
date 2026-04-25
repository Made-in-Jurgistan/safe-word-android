package com.safeword.android.transcription

import android.content.Context as AndroidContext
import com.darkrockstudios.symspellkt.common.SuggestionItem
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.exception.SpellCheckException
import com.darkrockstudios.symspellkt.impl.SymSpell
import com.darkrockstudios.symspellkt.impl.loadUniGramLine
import com.safeword.android.data.db.PersonalVocabularyEntity
import timber.log.Timber

/**
 * Layer 5 — SymSpell compound spell correction.
 *
 * Extracted from [ConfusionSetCorrector] to keep the SymSpell lifecycle and
 * dictionary management separate from phonetic/vocabulary correction.
 *
 * Instantiated and owned by [ConfusionSetCorrector]; not a Hilt component.
 */
internal class SymSpellCorrector(private val appContext: AndroidContext) {

    @Volatile private var symSpell: SymSpell? = null
    @Volatile private var symSpellVocabWords: Set<String> = emptySet()
    @Volatile private var symSpellInjectedWords: Set<String> = emptySet()
    private val symSpellLock = Any()
    private val whitespaceRegex = Regex("""\s+""")

    // ════════════════════════════════════════════════════════════════════
    //  Public API (mirrors old ConfusionSetCorrector surface)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Initialise SymSpell from the bundled frequency dictionary asset.
     *
     * Safe to call multiple times — subsequent calls after first success are no-ops.
     * Called from [ConfusionSetCorrector.initSymSpell] on the coordinator thread.
     */
    fun initSymSpell() {
        if (symSpell != null) return // fast path (@Volatile ensures visibility)
        synchronized(symSpellLock) {
            if (symSpell != null) return // double-check: concurrent caller may have loaded first
            try {
                val ss = SymSpell()
                var loadedCount = 0
                appContext.assets.open("symspell/frequency_dictionary_en_82_765.txt")
                    .bufferedReader()
                    .useLines { lines ->
                        lines.forEach { line ->
                            try { ss.dictionary.loadUniGramLine(line); loadedCount++ } catch (_: SpellCheckException) {}
                        }
                    }
                if (loadedCount == 0) {
                    Timber.e("[ERROR] SymSpellCorrector.initSymSpell | zero entries loaded — dictionary asset may be corrupt or missing")
                    return
                }
                symSpell = ss
                Timber.i("[INIT] SymSpellCorrector | dictionary loaded entries=%d", loadedCount)
            } catch (e: Exception) {
                Timber.e(e, "[ERROR] SymSpellCorrector.initSymSpell | failed to load dictionary")
            }
        }
    }

    /**
     * Apply SymSpell compound correction to [text].
     *
     * Returns [text] unchanged when SymSpell is not initialised, the text is short
     * (< 4 meaningful tokens), or every word passes the confidence guard.
     */
    fun correct(text: String): String = applySymSpellCorrection(text)

    /**
     * Update the set of personal vocabulary words to prevent SymSpell from
     * suggesting dictionary replacements for user-defined terms.
     *
     * Replaces [ConfusionSetCorrector.onVocabularyChanged] logic.
     */
    fun updateVocabWords(vocab: List<PersonalVocabularyEntity>) {
        val newWords = vocab.flatMap { it.phrase.lowercase().split(" ") }.toSet()
        synchronized(symSpellLock) {
            if (newWords == symSpellVocabWords) return
            symSpellVocabWords = newWords
            symSpellInjectedWords = emptySet()
            Timber.d("[SETTINGS] SymSpellCorrector | vocabWords=%d", newWords.size)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Private implementation
    // ════════════════════════════════════════════════════════════════════

    /**
     * Inject vocab words registered after [initSymSpell] was called.
     * Only words not already injected are added (delta injection).
     *
     * Must be called under [symSpellLock].
     */
    private fun injectVocabIntoSymSpell(ss: SymSpell) {
        val toInject = symSpellVocabWords - symSpellInjectedWords
        if (toInject.isEmpty()) return
        toInject.forEach { word ->
            try {
                ss.dictionary.loadUniGramLine("$word\t1000000")
            } catch (_: SpellCheckException) {}
        }
        symSpellInjectedWords = symSpellVocabWords
        Timber.d("[SETTINGS] SymSpellCorrector | injected=%d words", toInject.size)
    }

    private fun applySymSpellCorrection(text: String): String {
        val ss = symSpell ?: return text
        return try {
            synchronized(symSpellLock) { injectVocabIntoSymSpell(ss) }
            val words = text.split(whitespaceRegex)
            // Skip if every token would be individually ignored.
            // Pass isFirstWord so sentence-start capitalisation isn't mistaken for a proper noun.
            if (words.indices.all { i -> shouldSkipSymSpell(words[i], isFirstWord = i == 0) }) return text
            val result = ss.lookupCompound(text, 2.0, true)
                ?.firstOrNull()
                ?.term
                ?.trim()
                ?: return text
            if (result == text || result.isEmpty()) return text
            result
        } catch (e: SpellCheckException) {
            Timber.w(e, "[WARN] SymSpellCorrector.applySymSpellCorrection | SpellCheckException")
            text
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] SymSpellCorrector.applySymSpellCorrection | unexpected")
            text
        }
    }

    /**
     * Returns true when the word should be excluded from SymSpell lookup:
     * - Shorter than 3 characters (not enough context for useful suggestions).
     *   Lowered from 4 → 3 so common 3-letter misspellings like "teh" → "the", "waht" → "what",
     *   "hte" → "the" are candidates for correction; SymSpell's own edit-distance guard filters
     *   spurious matches.
     * - All upper-case (acronym or abbreviation).
     * - Starts with an upper-case letter **and is not the first word** (mid-sentence proper noun).
     *   The first word of an utterance is always capitalised by [TextFormatter]; skipping it would
     *   cause SymSpell to ignore genuine spelling errors at the start of short phrases.
     * - Is a personal vocabulary term (user-defined; should never be corrected).
     */
    private fun shouldSkipSymSpell(word: String, isFirstWord: Boolean = false): Boolean {
        if (word.length < 3) return true
        if (word.all { it.isUpperCase() }) return true
        if (!isFirstWord && word.first().isUpperCase()) return true
        if (word.lowercase() in symSpellVocabWords) return true
        return false
    }
}
