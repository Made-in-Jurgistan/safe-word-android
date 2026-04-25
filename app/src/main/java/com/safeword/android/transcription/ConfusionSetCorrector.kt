package com.safeword.android.transcription

import android.content.Context as AndroidContext
import com.safeword.android.data.db.PersonalVocabularyEntity
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.service.ThermalTier
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-ASR confusion-set corrections for known transcription errors.
 *
 * Applies correction layers 2–5 in the pipeline (layer 1 is [ContentNormalizer.preProcess]
 * which runs upstream). Layers applied in order:
 * 2. **Repetition cleanup** — collapse Moonshine repetitive-token artifacts.
 * 3. **Contextual corrections** — fix common homophone and grammar errors using
 *    surrounding-word signals (conservative; applied only at high confidence).
 *      This class must remain rule-based only.
 * 4. **Personal vocabulary** — apply user-confirmed custom vocabulary entries
 *    (manual, contacts, or auto-learned at [PersonalVocabularyEntity.CONFIRMATION_THRESHOLD]).
 * 5. **SymSpell** — statistical spell correction on complete utterances.
 *
 * Runs *after* [ContentNormalizer.preProcess] strips retracted/hallucinated text,
 * and *before* [TextFormatter] applies formatting.
 */
@Singleton
class ConfusionSetCorrector @Inject constructor(
    private val patternCache: VocabularyPatternCache,
    @ApplicationContext private val appContext: AndroidContext,
) {

    private val whitespaceRx = Regex("""\s+""")
    private val symSpellCorrector = SymSpellCorrector(appContext)

    // ════════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Apply all correction layers (Layers 1–3, 5).
     * Personal vocabulary (Layer 4) is always applied separately via [applyVocabularyLayer]
     * after [TextFormatter] has resolved spoken punctuation.
     */
    fun apply(
        rawText: String,
        context: InputContextSnapshot,
        isIncremental: Boolean = false,
        locale: Locale = Locale.getDefault(),
        previousText: String = "",
        thermalTier: ThermalTier = ThermalTier.NOMINAL,
    ): String {
        val normalized = rawText.trim()
        if (normalized.isEmpty()) return rawText

        // Delegate Layers 2–3 to ContextualGrammarCorrector.
        var result = ContextualGrammarCorrector.correct(normalized, locale, isIncremental)

        // Layer 5: SymSpell statistical spell correction (non-incremental only).
        // Allowed at WARM tier for accuracy; skipped only at HOT.
        if (!isIncremental && thermalTier != ThermalTier.HOT &&
            result.split(whitespaceRx).count { it.isNotBlank() } >= 4
        ) {
            result = symSpellCorrector.correct(result)
        }

        result = result.trim()
        if (result != normalized) {
            Timber.d(
                "[BRANCH] ConfusionSetCorrector | corrected len=%d→%d",
                normalized.length, result.length,
            )
        }

        return result.ifEmpty { rawText }
    }

    /**
     * Apply personal vocabulary substitutions only (Layer 4).
     *
     * Call this **after** [TextFormatter.format] so that vocabulary
     * substitutions operate on fully-formatted text — vocabulary
     * matches will never fire on speech that is about to be retracted.
     */
    fun applyVocabularyLayer(
        text: String,
        personalVocabulary: List<PersonalVocabularyEntity>,
        context: InputContextSnapshot,
        isIncremental: Boolean = false,
        thermalTier: ThermalTier = ThermalTier.NOMINAL,
    ): Pair<String, List<String>> = if (personalVocabulary.isEmpty()) Pair(text, emptyList())
    else applyPersonalVocabulary(text, personalVocabulary, context, isIncremental, thermalTier)

    // ════════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Layer 4 — Replace words/phrases that the user has confirmed as correct.
     *
     * Filters out dormant entries and app-scoped entries that don't match the current
     * context. Uses Aho-Corasick multi-pattern search when the vocabulary is large,
     * falling back to per-entry cached regex patterns otherwise.
     */
    private fun applyPersonalVocabulary(
        text: String,
        vocabulary: List<PersonalVocabularyEntity>,
        context: InputContextSnapshot,
        isIncremental: Boolean,
        thermalTier: ThermalTier = ThermalTier.NOMINAL,
    ): Pair<String, List<String>> {
        if (vocabulary.isEmpty()) return Pair(text, emptyList())

        // Filter: skip dormant entries and app-specific entries not matching current context.
        val activeVocab = vocabulary.filter { entry ->
            !entry.isDormant &&
            (entry.appPackage == null || entry.appPackage == context.packageName) &&
            (entry.contextHint == null || context.hintText.contains(entry.contextHint, ignoreCase = true))
        }
        if (activeVocab.isEmpty()) return Pair(text, emptyList())

        val phraseToEntry = activeVocab
            .filter { it.phrase.isNotBlank() }
            .associateBy { it.phrase.lowercase() }
        if (phraseToEntry.isEmpty()) return Pair(text, emptyList())

        var result = text
        val matchedPhrases = mutableListOf<String>()

        if (activeVocab.size >= VocabularyPatternCache.AHO_CORASICK_THRESHOLD) {
            // Fast path: Aho-Corasick multi-pattern search — O(n) in text length.
            val matcher = patternCache.getAhoCorasick(phraseToEntry.keys.toList())
            val matches = matcher.findAll(result)
            if (matches.isNotEmpty()) {
                val sb = StringBuilder(result.length)
                var pos = 0
                for (m in matches) {
                    if (m.start < pos) continue  // skip overlapping (shorter) matches
                    sb.append(result, pos, m.start)
                    val entry = phraseToEntry[m.pattern]
                    val replacement = entry?.let { it.writtenForm ?: it.phrase }
                        ?: result.substring(m.start, m.end)
                    sb.append(replacement)
                    if (entry != null) {
                        matchedPhrases += entry.phrase
                        Timber.d("[BRANCH] ConfusionSetCorrector | ahoCorasick match '%s'", m.pattern)
                    }
                    pos = m.end
                }
                sb.append(result, pos, result.length)
                result = sb.toString()
            }
        } else {
            // Regular path: per-entry cached regex patterns, longest-first,
            // then highest activity score to prefer frequently-used entries at equal length.
            // Capture `now` once so every activityScore() call uses the same timestamp
            // and System.currentTimeMillis() is not invoked O(n log n) times during sort.
            val now = System.currentTimeMillis()
            val sorted = activeVocab.sortedWith(
                compareByDescending<PersonalVocabularyEntity> { it.phrase.length }
                    .thenByDescending { it.activityScore(now) },
            )
            for (entry in sorted) {
                val phrase = entry.phrase
                if (phrase.isBlank()) continue
                val pattern = patternCache.patternFor(phrase)
                val before = result
                val replacement = Regex.escapeReplacement(entry.writtenForm ?: phrase)
                result = pattern.replace(result, replacement)
                if (result != before) {
                    matchedPhrases += phrase
                    Timber.d("[BRANCH] ConfusionSetCorrector | personalVocab match '%s'", phrase)
                }
            }
        }

        return Pair(result, matchedPhrases)
    }

    /** @see SymSpellCorrector.initSymSpell */
    fun initSymSpell() = symSpellCorrector.initSymSpell()

    /**
     * Notify the corrector that the confirmed vocabulary has changed.
     * Delegates word-set management to [SymSpellCorrector.updateVocabWords].
     */
    fun onVocabularyChanged(vocab: List<PersonalVocabularyEntity>) =
        symSpellCorrector.updateVocabWords(vocab)
}
