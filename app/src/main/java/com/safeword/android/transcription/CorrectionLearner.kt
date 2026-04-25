package com.safeword.android.transcription

import com.safeword.android.data.PersonalVocabularyRepository
import com.safeword.android.data.db.PersonalVocabularyEntity
import com.safeword.android.data.db.VocabularySource
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Observes differences between ASR output and user-corrected text to
 * automatically learn new personal vocabulary entries.
 *
 * Flow:
 *  1. [TranscriptionCoordinator] calls [recordDictation] with the final
 *     inserted text right after each dictation completes.
 *  2. Later, the IME service or UI calls [onTextFieldSnapshot] with the
 *     current text-field content when the user moves on (navigates away,
 *     starts a new dictation, or after an idle timeout).
 *  3. This class diffs the two, extracts changed words, and persists them
 *     as `auto_learned` entries. Each recurrence increments the confirmation
 *     count until [PersonalVocabularyEntity.CONFIRMATION_THRESHOLD] is reached,
 *     at which point the entry becomes an active correction rule.
 *
 * Design rationale:
 * - Word-level diff (not character-level) avoids noise from minor typos.
 * - Only tracks replacements of similar length (±50% tokens) to filter
 *   out additions, deletions, and complete rewrites.
 * - Case-only changes (e.g. "Iphone" → "iPhone") are also learned so
 *   the personal vocabulary preserves user-preferred casing.
 */
@Singleton
class CorrectionLearner @Inject constructor(
    private val vocabularyRepository: PersonalVocabularyRepository,
) {

    private data class DictationRecord(
        val text: String,
        val appPackage: String?,
        val dictatedAt: Long,
        val appliedCorrections: List<Pair<String, String>> = emptyList(),
        val uncertainWordIndices: Set<Int> = emptySet(),
    )

    /** Atomically stores the most recent dictation record for consumption. */
    private val lastDictation = AtomicReference<DictationRecord?>(null)

    /**
     * Record what was inserted into the text field at dictation completion.
     * Must be called with the final post-processed text (after all pipeline stages).
     *
     * @param appPackage The package name of the app that was active during dictation.
     *        Used to scope learned vocabulary to specific apps when appropriate.
     * @param uncertainWordIndices Word positions where the model had low confidence.
     *        Corrections at these positions get a fast-tracked confirmation count.
     */
    fun recordDictation(
        text: String,
        appPackage: String? = null,
        appliedCorrections: List<Pair<String, String>> = emptyList(),
        uncertainWordIndices: Set<Int> = emptySet(),
    ) {
        val trimmed = text.trim()
        lastDictation.set(
            DictationRecord(trimmed, appPackage, System.currentTimeMillis(), appliedCorrections, uncertainWordIndices),
        )
        Timber.d(
            "[STATE] CorrectionLearner.recordDictation | len=%d pkg=%s corrections=%d uncertain=%d",
            trimmed.length, appPackage, appliedCorrections.size, uncertainWordIndices.size,
        )
    }

    /**
     * Provide the current text-field content so corrections can be detected.
     *
     * Call this when the user finishes editing (e.g. new dictation starts,
     * or focus leaves the text field). The method atomically consumes the
     * stored dictation so duplicate learning is impossible.
     */
    suspend fun onTextFieldSnapshot(currentText: String) {
        val record = lastDictation.getAndSet(null) ?: return
        val elapsedMs = System.currentTimeMillis() - record.dictatedAt
        if (elapsedMs > MAX_CORRECTION_WINDOW_MS) {
            Timber.d(
                "[BRANCH] CorrectionLearner.onTextFieldSnapshot | expired elapsedMs=%d",
                elapsedMs,
            )
            return
        }
        val original = record.text
        val appPackage = record.appPackage

        if (original.isBlank() || currentText.isBlank()) return
        if (original == currentText) return

        val corrections = extractCorrections(original, currentText)
        if (corrections.isEmpty()) return

        // Rejection detection: if the user edited away a word that Layer 4 substituted
        // from personal vocabulary, decrement that entry's confirmation count.
        val rejectedWrittenForms = mutableSetOf<String>()
        for ((spokenPhrase, writtenForm) in record.appliedCorrections) {
            if (corrections.any { (from, _) -> from.equals(writtenForm, ignoreCase = true) }) {
                Timber.d(
                    "[BRANCH] CorrectionLearner.onTextFieldSnapshot | rejection detected phrase='%s'",
                    spokenPhrase,
                )
                vocabularyRepository.decrementConfirmationByPhrase(spokenPhrase)
                rejectedWrittenForms += writtenForm.lowercase()
            }
        }

        // Filter out rejected substitutions to avoid learning spurious replacements.
        val actionableCorrections = if (rejectedWrittenForms.isEmpty()) {
            corrections
        } else {
            corrections.filter { (from, _) -> from.lowercase() !in rejectedWrittenForms }
        }
        if (actionableCorrections.isEmpty()) return

        // Large-diff short-circuit: more than MAX_SIMULTANEOUS_WORD_CORRECTIONS simultaneous
        // replacements suggests the user rewrote the whole sentence, not correcting individual words.
        // Threshold raised from 3 → 6 to capture multi-word phrase corrections in
        // professional dictation without missing cascade auto-correct scenarios.
        if (actionableCorrections.size > MAX_SIMULTANEOUS_WORD_CORRECTIONS) {
            Timber.d(
                "[BRANCH] CorrectionLearner.onTextFieldSnapshot | skipping large diff corrections=%d",
                actionableCorrections.size,
            )
            return
        }

        Timber.i(
            "[STATE] CorrectionLearner.onTextFieldSnapshot | corrections=%d",
            actionableCorrections.size,
        )

        // Build a set of uncertain words for fast lookup.
        val origWords = tokenize(original)
        val uncertainWords = record.uncertainWordIndices
            .filter { it in origWords.indices }
            .mapTo(mutableSetOf()) { origWords[it].lowercase() }

        for ((originalWord, corrected) in actionableCorrections) {
            // Fast-track uncertain corrections: if ANY word in the original phrase
            // was flagged uncertain by the model, start at confirmationCount=2
            // (needs only 1 more confirmation instead of 2 to become active).
            val isUncertain = originalWord.split(WHITESPACE_SPLIT_REGEX)
                .any { it.lowercase() in uncertainWords }
            persistCorrection(originalWord, corrected, appPackage, fastTrack = isUncertain)
        }
    }

    /**
     * Explicitly teach a correction — use from a settings UI "Add word" action
     * or when the user explicitly corrects a word via long-press suggestion.
     *
     * @param writtenPhrase The desired written form (e.g. "Kubernetes").
     * @param spokenPhrase  The ASR spoken form to match (defaults to [writtenPhrase]
     *                      for simple casing or spelling entries).
     */
    suspend fun learnExplicit(writtenPhrase: String, spokenPhrase: String = writtenPhrase) {
        if (writtenPhrase.isBlank()) return
        persistCorrection(spokenPhrase.trim(), writtenPhrase.trim())
    }

    // ────────────────────────────────────────────────────────────────────
    //  Diffing
    // ────────────────────────────────────────────────────────────────────

    /**
     * Extract word-level replacements between [original] and [current].
     *
     * Uses a simple longest-common-subsequence (LCS) approach on whitespace-
     * tokenized words. Only keeps changes where the replaced span and the
     * replacement span have a similar token count (prevents learning from
     * large-scale rewrites).
     */
    internal fun extractCorrections(
        original: String,
        current: String,
    ): List<Pair<String, String>> {
        val origWords = tokenize(original)
        val currWords = tokenize(current)
        if (origWords.isEmpty() || currWords.isEmpty()) return emptyList()

        val lcs = longestCommonSubsequence(origWords, currWords)
        val corrections = mutableListOf<Pair<String, String>>()

        var oi = 0
        var ci = 0
        var li = 0

        while (li < lcs.size) {
            val anchor = lcs[li]
            // Advance both to the next LCS anchor.
            val origChunk = mutableListOf<String>()
            while (oi < origWords.size && !origWords[oi].equals(anchor, ignoreCase = true)) {
                origChunk += origWords[oi++]
            }
            val currChunk = mutableListOf<String>()
            while (ci < currWords.size && !currWords[ci].equals(anchor, ignoreCase = true)) {
                currChunk += currWords[ci++]
            }

            addIfCorrection(origChunk, currChunk, corrections)

            // Skip past the anchor itself.
            oi++
            ci++
            li++
        }

        // Trailing words after last LCS anchor.
        val origTail = origWords.subList(oi, origWords.size)
        val currTail = currWords.subList(ci, currWords.size)
        addIfCorrection(origTail.toList(), currTail.toList(), corrections)

        return corrections
    }

    private fun addIfCorrection(
        origChunk: List<String>,
        currChunk: List<String>,
        out: MutableList<Pair<String, String>>,
    ) {
        if (origChunk.isEmpty() || currChunk.isEmpty()) return
        // Only learn replacements — not pure insertions or pure deletions.
        // Filter out wild-length changes (complete rewrites).
        val ratio = currChunk.size.toFloat() / origChunk.size
        if (ratio < 0.5f || ratio > 2.0f) return

        val origPhrase = origChunk.joinToString(" ")
        val currPhrase = currChunk.joinToString(" ")

        // Skip identical strings (no actual correction).
        if (origPhrase == currPhrase) return

        // Skip very short spoken forms — single-char and 2-char phrases are almost always
        // statistical noise from the LCS diff (e.g. "a" → "A", "in" → "In") and cluttering
        // the vocabulary table provides no transcription benefit.
        if (origPhrase.length < 3 || currPhrase.length < 3) return

        out += origPhrase to currPhrase
    }

    private fun tokenize(text: String): List<String> =
        text.trim().split(WHITESPACE_SPLIT_REGEX).filter { it.isNotBlank() }

    /**
     * Standard LCS on word lists (case-insensitive matching).
     * Returns the subsequence in the order it appears.
     *
     * Uses rolling 2-row DP (O(n) integer space) plus a compact byte-per-cell
     * choice matrix for backtracking (4× smaller than a full int matrix).
     */
    private fun longestCommonSubsequence(
        a: List<String>,
        b: List<String>,
    ): List<String> {
        val m = a.size
        val n = b.size
        if (m == 0 || n == 0) return emptyList()

        // Rolling rows — O(n) int space instead of O(m×n).
        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)

        // choice[i * (n+1) + j]: 0 = diagonal (match), 1 = up, 2 = left.
        // ByteArray is 4× smaller than IntArray for the same cell count.
        val choice = ByteArray((m + 1) * (n + 1))

        for (i in 1..m) {
            curr.fill(0)
            for (j in 1..n) {
                if (a[i - 1].equals(b[j - 1], ignoreCase = true)) {
                    curr[j] = prev[j - 1] + 1
                    choice[i * (n + 1) + j] = 0  // diagonal
                } else if (prev[j] >= curr[j - 1]) {
                    curr[j] = prev[j]
                    choice[i * (n + 1) + j] = 1  // up
                } else {
                    curr[j] = curr[j - 1]
                    choice[i * (n + 1) + j] = 2  // left
                }
            }
            val tmp = prev; prev = curr; curr = tmp
        }

        // Backtrack to recover the subsequence.
        val result = mutableListOf<String>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            when (choice[i * (n + 1) + j]) {
                0.toByte() -> { result += a[i - 1]; i--; j-- }
                1.toByte() -> i--
                else -> j--
            }
        }
        return result.reversed()
    }

    // ────────────────────────────────────────────────────────────────────
    //  Persistence
    // ────────────────────────────────────────────────────────────────────

    private suspend fun persistCorrection(
        spokenPhrase: String,
        writtenPhrase: String,
        appPackage: String? = null,
        fastTrack: Boolean = false,
    ) {
        val exists = vocabularyRepository.exists(spokenPhrase)
        if (exists) {
            val updated = vocabularyRepository.incrementConfirmation(spokenPhrase)
            Timber.d(
                "[STATE] CorrectionLearner.persistCorrection | increment '%s' rows=%d",
                spokenPhrase, updated,
            )
        } else {
            // Fast-tracked entries start at 2 confirmations (model was uncertain AND
            // the user corrected the word), needing only 1 more to become active.
            val initialCount = if (fastTrack) 2 else 1
            val writtenFormToStore = writtenPhrase.takeIf { it != spokenPhrase }
            val id = vocabularyRepository.insert(
                PersonalVocabularyEntity(
                    phrase = spokenPhrase,
                    writtenForm = writtenFormToStore,
                    source = VocabularySource.AUTO_LEARNED,
                    confirmationCount = initialCount,
                    appPackage = appPackage,
                ),
            )
            Timber.d(
                "[STATE] CorrectionLearner.persistCorrection | insert spoken='%s' written='%s' id=%d pkg=%s fastTrack=%b",
                spokenPhrase, writtenPhrase, id, appPackage, fastTrack,
            )
        }
    }

    companion object {
        private const val MAX_CORRECTION_WINDOW_MS = 30_000L
        /** Maximum number of word-level replacements in a single diff before treating it as a full rewrite. */
        private const val MAX_SIMULTANEOUS_WORD_CORRECTIONS = 6
        /** Delay after a dictation segment before snapshotting the text field for correction learning. */
        internal const val SNAPSHOT_DELAY_MS = 12_000L
    }
}
