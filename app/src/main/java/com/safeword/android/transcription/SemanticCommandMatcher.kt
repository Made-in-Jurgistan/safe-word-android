package com.safeword.android.transcription

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Semantic voice command matching using sentence embeddings.
 *
 * Pre-computes embeddings for 2-3 canonical phrases per [VoiceAction], then at
 * runtime compares the user utterance embedding against all entries via cosine
 * similarity (which reduces to a dot product since vectors are L2-normalised).
 *
 * Ambiguity guard: the top match must exceed [SIMILARITY_THRESHOLD] and the
 * margin between first and second place must be ≥ [AMBIGUITY_GAP].
 *
 * Thread-safety: [initialize] builds the index once; [match] is read-only
 * after that.
 */
@Singleton
class SemanticCommandMatcher @Inject constructor(
    private val embeddingModel: SentenceEmbeddingModel,
) {
    companion object {
        /** Minimum cosine similarity to consider a semantic match. */
        const val SIMILARITY_THRESHOLD = 0.70f

        /** Minimum gap between best and second-best to avoid ambiguity. */
        const val AMBIGUITY_GAP = 0.08f

        /** High-confidence shortcut — skip ambiguity check above this threshold. */
        private const val HIGH_CONFIDENCE = 0.88f
    }

    /**
     * Result of a semantic match attempt.
     *
     * @property action The matched voice action.
     * @property similarity Cosine similarity score in [0, 1].
     * @property phrase The canonical phrase that matched.
     */
    data class SemanticMatchResult(
        val action: VoiceAction,
        val similarity: Float,
        val phrase: String,
    )

    /** Pre-computed embedding for each canonical phrase. */
    private data class CommandEntry(
        val action: VoiceAction,
        val phrase: String,
        val embedding: FloatArray,
    )

    /** Full index — built once by [initialize]. */
    @Volatile
    private var index: List<CommandEntry> = emptyList()

    val isInitialized: Boolean get() = index.isNotEmpty()

    /**
     * Pre-compute embeddings for all canonical command phrases.
     *
     * Must be called after [SentenceEmbeddingModel.load] succeeds.
     * Idempotent — subsequent calls are no-ops.
     *
     * @return `true` if the index was built successfully.
     */
    fun initialize(): Boolean {
        if (index.isNotEmpty()) return true
        if (!embeddingModel.isLoaded && !embeddingModel.load()) {
            Timber.e("[ERROR] SemanticCommandMatcher.initialize | embedding model failed to load")
            return false
        }

        val start = System.nanoTime()
        val entries = mutableListOf<CommandEntry>()

        for ((action, phrases) in CANONICAL_PHRASES) {
            for (phrase in phrases) {
                val embedding = embeddingModel.embed(phrase)
                if (embedding != null) {
                    entries.add(CommandEntry(action, phrase, embedding))
                } else {
                    Timber.w("[WARN] SemanticCommandMatcher.initialize | embedding failed phrase=%s", phrase)
                }
            }
        }

        index = entries

        val ms = (System.nanoTime() - start) / 1_000_000
        Timber.i(
            "[INIT] SemanticCommandMatcher.initialize | entries=%d actions=%d initMs=%d",
            entries.size, CANONICAL_PHRASES.size, ms,
        )
        return entries.isNotEmpty()
    }

    /**
     * Find the best-matching [VoiceAction] for [utterance] using semantic similarity.
     *
     * Lazily initializes the embedding index on first call if not already built.
     *
     * @return match result, or `null` if below threshold or ambiguous.
     */
    fun match(utterance: String): SemanticMatchResult? {
        if (index.isEmpty() && !initialize()) return null
        val entries = index

        val embedding = embeddingModel.embed(utterance) ?: return null

        var bestScore = -1f
        var bestEntry: CommandEntry? = null
        var secondBestScore = -1f

        for (entry in entries) {
            val score = dotProduct(embedding, entry.embedding)
            if (score > bestScore) {
                secondBestScore = bestScore
                bestScore = score
                bestEntry = entry
            } else if (score > secondBestScore) {
                secondBestScore = score
            }
        }

        val entry = bestEntry ?: return null

        if (bestScore < SIMILARITY_THRESHOLD) {
            Timber.d(
                "[BRANCH] SemanticCommandMatcher.match | below threshold utterance=%s best=%.3f phrase=%s",
                utterance, bestScore, entry.phrase,
            )
            return null
        }

        // Ambiguity check — skip for very high confidence
        if (bestScore < HIGH_CONFIDENCE) {
            val gap = bestScore - secondBestScore
            if (gap < AMBIGUITY_GAP) {
                Timber.d(
                    "[BRANCH] SemanticCommandMatcher.match | ambiguous utterance=%s best=%.3f second=%.3f gap=%.3f",
                    utterance, bestScore, secondBestScore, gap,
                )
                return null
            }
        }

        Timber.d(
            "[VOICE] SemanticCommandMatcher.match | matched utterance=%s action=%s sim=%.3f phrase=%s",
            utterance, entry.action, bestScore, entry.phrase,
        )

        return SemanticMatchResult(
            action = entry.action,
            similarity = bestScore,
            phrase = entry.phrase,
        )
    }

    /**
     * Dot product of two L2-normalised vectors = cosine similarity.
     */
    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}

/**
 * Canonical phrases per action — 2-3 diverse phrasings.
 *
 * These should cover the semantic space of each command so the embedding
 * model can generalise to paraphrases. Exact-match variants and single-word
 * commands remain in [VoiceCommandRegistry.COMMAND_MAP].
 */
private val CANONICAL_PHRASES: Map<VoiceAction, List<String>> = mapOf(
    // Deletion
    VoiceAction.DeleteSelection to listOf(
        "delete the selected text",
        "delete that",
        "remove the selection",
        "erase what is highlighted",
        "delete selection",
    ),
    VoiceAction.DeleteLastWord to listOf(
        "delete the last word",
        "delete last word",
        "remove previous word",
        "erase one word back",
    ),
    VoiceAction.DeleteLastSentence to listOf(
        "delete the last sentence",
        "delete last sentence",
        "remove previous sentence",
        "erase the sentence I just said",
    ),
    VoiceAction.Backspace to listOf(
        "backspace",
        "back space",
        "delete one character",
        "delete character",
    ),
    VoiceAction.ClearAll to listOf(
        "clear all",
        "clear everything",
        "delete all",
        "delete all of the text",
        "erase all",
        "start over",
    ),

    // History
    VoiceAction.Undo to listOf(
        "undo",
        "undo that",
        "undo the last change",
        "revert that",
        "take that back",
        "oops",
    ),
    VoiceAction.Redo to listOf(
        "redo",
        "redo that",
        "redo the last change",
        "put it back",
        "put that back",
    ),

    // Selection
    VoiceAction.SelectAll to listOf(
        "select all",
        "select all the text",
        "highlight all",
        "highlight everything",
        "select everything",
    ),
    VoiceAction.SelectLastWord to listOf(
        "select last word",
        "select the last word",
        "highlight last word",
        "highlight previous word",
    ),

    // Clipboard
    VoiceAction.Copy to listOf(
        "copy",
        "copy that",
        "copy the text",
        "copy to clipboard",
    ),
    VoiceAction.Cut to listOf(
        "cut",
        "cut that",
        "cut the text",
        "cut to clipboard",
    ),
    VoiceAction.Paste to listOf(
        "paste",
        "paste that",
        "paste from clipboard",
        "paste the text",
        "paste that here",
    ),

    // Navigation
    VoiceAction.NewLine to listOf(
        "new line",
        "go to the next line",
        "insert a line break",
    ),
    VoiceAction.NewParagraph to listOf(
        "new paragraph",
        "start a new paragraph",
    ),

    // Formatting
    VoiceAction.CapitalizeLastWord to listOf(
        "capitalize the last word",
        "make the last word capitalized",
    ),
    VoiceAction.UppercaseLastWord to listOf(
        "uppercase the last word",
        "make the last word all caps",
    ),
    VoiceAction.LowercaseLastWord to listOf(
        "lowercase the last word",
        "make the last word all lowercase",
    ),

    // Session
    VoiceAction.Send to listOf(
        "send the message",
        "press send",
        "submit this",
    ),
    VoiceAction.StopListening to listOf(
        "stop listening",
        "stop dictation",
        "turn off the microphone",
    ),

    // Rich-text formatting
    VoiceAction.Bold to listOf(
        "make the text bold",
        "apply bold formatting",
        "bold the selection",
    ),
    VoiceAction.Italic to listOf(
        "make the text italic",
        "apply italic formatting",
        "italicize the selection",
    ),
    VoiceAction.Underline to listOf(
        "underline the text",
        "apply underline formatting",
        "underline the selection",
    ),
    VoiceAction.Strikethrough to listOf(
        "strikethrough the text",
        "apply strikethrough formatting",
        "cross out the selection",
    ),
)
