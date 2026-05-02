package com.safeword.android.transcription

import com.safeword.android.data.db.PersonalizedEntryEntity
import com.safeword.android.data.settings.PersonalizedDictionaryRepository
import com.safeword.android.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [TextProcessor] implementation.
 *
 * **Moonshine-native-output aware**: Moonshine v2 produces cased, punctuated text.
 * Each phase is guarded so it only adds value beyond what the model already provides,
 * avoiding duplicate inference (double-punctuation, double-casing, etc.).
 *
 * Pipeline phases:
 *  0. [ConfusionSetCorrector.applyInContext] — mid-sentence homophone correction (when context supplied)
 *  1. [ContentNormalizer.normalize] — emoji, punctuation, fillers, stutters, self-repair, ITN
 *  2. [PunctuationPredictor.predict] — rule-based punctuation insertion
 *     (skipped when [punctuationEnabled] is `false` **or** when the model already
 *     emitted internal punctuation — see [containsModelPunctuation])
 *  3. [TextFormatter.format] — whitespace, additive sentence case, acronyms, trailing punctuation
 *  4. [PersonalizedDictionaryCorrector.apply] — user word substitutions (always last; user preference wins)
 *
 * @param punctuationEnabled When `false`, phase 2 is skipped entirely.
 */
@Singleton
class DefaultTextProcessor @Inject constructor(
    private val personalizedDictionaryRepository: PersonalizedDictionaryRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    var punctuationEnabled: Boolean = true

    companion object {
        /**
         * Punctuation characters that Moonshine v2 may emit natively.
         * If any of these appear *between* words (not just trailing), the model
         * already punctuated the text and [PunctuationPredictor] should be skipped
         * to avoid double-punctuation conflicts.
         */
        private val MODEL_PUNCT_CHARS = charArrayOf(',', ';', ':', '!', '?')

        /**
         * Returns `true` when [text] contains internal (mid-sentence) punctuation
         * that indicates the ASR model already punctuated its output.
         *
         * A single trailing period is **not** counted — Moonshine often omits it
         * and the TextFormatter trailing-punct step covers that gap.
         */
        internal fun containsModelPunctuation(text: String): Boolean {
            val trimmed = text.trim()
            // Any of ,;:!? anywhere → model punctuated
            if (trimmed.indexOfAny(MODEL_PUNCT_CHARS) >= 0) return true
            // Internal period (not just the very last char) → model punctuated
            val lastDot = trimmed.lastIndexOf('.')
            if (lastDot >= 0 && lastDot < trimmed.length - 1) return true
            return false
        }
    }

    @Volatile
    private var personalizedEntries: List<PersonalizedEntryEntity> = emptyList()

    init {
        scope.launch {
            personalizedDictionaryRepository.enabledEntries.collect { entries ->
                personalizedEntries = entries
                PersonalizedDictionaryCorrector.invalidateCache()
                Timber.d("[DICT] DefaultTextProcessor | loaded %d entries", entries.size)
            }
        }
    }

    fun process(
        text: String,
        correctorContext: ConfusionSetCorrector.Context? = null,
    ): String {
        if (text.isBlank()) return ""
        Timber.d("[ENTER] TextProcessor.process | inputLen=%d", text.length)
        val processStart = System.nanoTime()

        var result = text

        // Phase 0: mid-sentence confusion-set correction
        if (correctorContext != null) {
            val before = result
            result = ConfusionSetCorrector.applyInContext(result, correctorContext)
            if (result != before) {
                Timber.d("[PHASE0] ConfusionSetCorrector.applyInContext | changed")
            }
        }

        // Phase 1: content normalization
        result = ContentNormalizer.normalize(result)

        // Phase 2: punctuation prediction — skipped when model already punctuated
        if (punctuationEnabled && !containsModelPunctuation(result)) {
            result = PunctuationPredictor.predict(result)
        }

        // Phase 3: cosmetic formatting
        result = TextFormatter.format(result)

        // Phase 4: personalized dictionary substitutions (user preference wins)
        val entries = personalizedEntries
        if (entries.isNotEmpty()) {
            val dictResult = PersonalizedDictionaryCorrector.apply(result, entries)
            if (dictResult.firedEntryIds.isNotEmpty()) {
                result = dictResult.text
                Timber.i("[DICT] Phase4 | fired=%d subs", dictResult.firedEntryIds.size)
                scope.launch {
                    dictResult.firedEntryIds.forEach { id ->
                        personalizedDictionaryRepository.recordUse(id)
                    }
                }
            }
        }

        val processMs = (System.nanoTime() - processStart) / 1_000_000
        if (result != text.trim()) {
            Timber.i("[PERF] TextProcessor.process | lenBefore=%d lenAfter=%d processMs=%d",
                text.length, result.length, processMs)
        } else {
            Timber.d("[EXIT] TextProcessor.process | no changes inputLen=%d processMs=%d",
                text.length, processMs)
        }

        return result
    }
}
