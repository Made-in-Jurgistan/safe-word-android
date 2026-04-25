package com.safeword.android.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WordPiece tokenizer compatible with BERT/MiniLM models.
 *
 * Loads `vocab.txt` from assets and performs standard WordPiece encoding:
 * lowercase → split on whitespace → greedy longest-match subword tokenization.
 * Adds `[CLS]` and `[SEP]` special tokens and pads/truncates to [MAX_LENGTH].
 *
 * Thread-safe: all methods are pure after [load] completes.
 */
@Singleton
class WordPieceTokenizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val VOCAB_ASSET = "minilm_vocab.txt"

        /** Voice commands are short — 32 tokens is more than enough. */
        const val MAX_LENGTH = 32

        private const val CLS_TOKEN = "[CLS]"
        private const val SEP_TOKEN = "[SEP]"
        private const val UNK_TOKEN = "[UNK]"
        private const val PAD_TOKEN = "[PAD]"

        /** Maximum characters in a single subword token before falling back to [UNK]. */
        private const val MAX_WORD_CHARS = 100

        private val WHITESPACE_REGEX = Regex("\\s+")

        // Standard BERT vocabulary indices — used as fallbacks when vocab.txt
        // does not contain the expected special token.
        private const val DEFAULT_PAD_ID = 0
        private const val DEFAULT_UNK_ID = 100
        private const val DEFAULT_CLS_ID = 101
        private const val DEFAULT_SEP_ID = 102
    }

    /** Token string → vocabulary index. */
    @Volatile
    private var vocab: Map<String, Int> = emptyMap()

    private var clsId = DEFAULT_CLS_ID
    private var sepId = DEFAULT_SEP_ID
    internal var unkId = DEFAULT_UNK_ID
        private set
    private var padId = DEFAULT_PAD_ID

    val isLoaded: Boolean get() = vocab.isNotEmpty()

    /**
     * Load vocabulary from assets. Idempotent.
     *
     * @return `true` on success.
     */
    fun load(): Boolean {
        if (vocab.isNotEmpty()) return true

        val start = System.nanoTime()
        return try {
            val lines = context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
            val map = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { index, line -> map[line.trim()] = index }

            clsId = map[CLS_TOKEN] ?: DEFAULT_CLS_ID
            sepId = map[SEP_TOKEN] ?: DEFAULT_SEP_ID
            unkId = map[UNK_TOKEN] ?: DEFAULT_UNK_ID
            padId = map[PAD_TOKEN] ?: DEFAULT_PAD_ID

            vocab = map

            val ms = (System.nanoTime() - start) / 1_000_000
            Timber.i("[INIT] WordPieceTokenizer.load | vocabSize=%d loadMs=%d", map.size, ms)
            true
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] WordPieceTokenizer.load | failed to load vocab")
            false
        }
    }

    /**
     * Tokenize [text] into token IDs and an attention mask.
     *
     * @return Pair of (inputIds, attentionMask), both [LongArray] of length [MAX_LENGTH].
     */
    fun tokenize(text: String): Pair<LongArray, LongArray> {
        val v = vocab
        check(v.isNotEmpty()) { "WordPieceTokenizer not loaded — call load() first" }

        val tokens = mutableListOf(clsId)
        val words = text.lowercase().trim().split(WHITESPACE_REGEX)

        for (word in words) {
            if (word.isEmpty()) continue
            tokens.addAll(wordPieceEncode(word, v))
            if (tokens.size >= MAX_LENGTH - 1) break // leave room for [SEP]
        }

        if (tokens.size > MAX_LENGTH - 1) {
            // Truncate, keeping [CLS] at front
            while (tokens.size > MAX_LENGTH - 1) tokens.removeAt(tokens.lastIndex)
        }
        tokens.add(sepId)

        val inputIds = LongArray(MAX_LENGTH) { padId.toLong() }
        val attentionMask = LongArray(MAX_LENGTH) { 0L }
        for (i in tokens.indices) {
            inputIds[i] = tokens[i].toLong()
            attentionMask[i] = 1L
        }

        return inputIds to attentionMask
    }

    /**
     * Result of tokenization with word-boundary tracking for token classification models.
     *
     * @property inputIds Padded token ID array of length [maxLength].
     * @property attentionMask 1 for real tokens, 0 for padding.
     * @property wordBoundaries Token index range per original word (aligned to the padded sequence,
     *   so index 0 is [CLS] and word tokens start at 1).
     */
    data class TokenizationResult(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val wordBoundaries: List<IntRange>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TokenizationResult) return false
            return inputIds.contentEquals(other.inputIds) &&
                attentionMask.contentEquals(other.attentionMask) &&
                wordBoundaries == other.wordBoundaries
        }

        override fun hashCode(): Int {
            var result = inputIds.contentHashCode()
            result = 31 * result + attentionMask.contentHashCode()
            result = 31 * result + wordBoundaries.hashCode()
            return result
        }
    }

    /**
     * Tokenize [text] into padded token IDs with word-boundary tracking.
     *
     * Unlike [tokenize], this method tracks which token indices belong to each
     * original whitespace-delimited word, enabling token-level classifications
     * (e.g. grammar correction) to be mapped back to words.
     *
     * @param maxLength Maximum sequence length (including [CLS] and [SEP]).
     * @return [TokenizationResult] with padded arrays and word boundaries.
     */
    fun tokenizeWithBoundaries(text: String, maxLength: Int): TokenizationResult {
        val v = vocab
        check(v.isNotEmpty()) { "WordPieceTokenizer not loaded \u2014 call load() first" }

        val tokens = mutableListOf(clsId)
        val boundaries = mutableListOf<IntRange>()
        val words = text.lowercase().trim().split(WHITESPACE_REGEX)

        for (word in words) {
            if (word.isEmpty()) continue
            val wordTokens = wordPieceEncode(word, v)
            val startIdx = tokens.size
            tokens.addAll(wordTokens)
            boundaries.add(startIdx until tokens.size)
            if (tokens.size >= maxLength - 1) break
        }

        if (tokens.size > maxLength - 1) {
            while (tokens.size > maxLength - 1) tokens.removeAt(tokens.lastIndex)
            // Trim word boundaries that were partially or fully truncated.
            val trimmed = boundaries.mapNotNull { range ->
                val clamped = range.first until minOf(range.last + 1, tokens.size)
                if (clamped.isEmpty()) null else clamped
            }
            boundaries.clear()
            boundaries.addAll(trimmed)
        }

        tokens.add(sepId)

        val inputIds = LongArray(maxLength) { padId.toLong() }
        val attentionMask = LongArray(maxLength) { 0L }
        for (i in tokens.indices) {
            inputIds[i] = tokens[i].toLong()
            attentionMask[i] = 1L
        }

        return TokenizationResult(inputIds, attentionMask, boundaries)
    }

    /**
     * Greedy longest-match WordPiece encoding for a single word.
     */
    private fun wordPieceEncode(word: String, v: Map<String, Int>): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(unkId)

        val result = mutableListOf<Int>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var matched = false

            while (start < end) {
                val substr = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##" + word.substring(start, end)
                }

                val id = v[substr]
                if (id != null) {
                    result.add(id)
                    start = end
                    matched = true
                    break
                }
                end--
            }

            if (!matched) {
                result.add(unkId)
                start++
            }
        }

        return result
    }
}
