package com.safeword.android.transcription

import timber.log.Timber

/**
 * Primary semantic intent recognizer for voice commands.
 *
 * This is the main command detection engine used by [VoiceCommandDetector].
 * Each registered [Intent] maps a set of keyword/phrase triggers (with optional
 * weights) to a [VoiceAction]. Detection priority in [VoiceCommandDetector]:
 *  1. Exact match (O(1) hash lookup)
 *  2. **IntentRecognizer** (this — primary)
 *  3. Levenshtein fuzzy (fallback for ASR typos)
 *
 * Scoring:
 *  - Each exact keyword match contributes its weight to the score.
 *  - Partial prefix matches (≥ 4 chars) add half weight as a bonus.
 *  - The score is normalised by the sum of the top-K keyword weights,
 *    where K equals the number of matched keywords (top-K normalization).
 *  - Guard: matched keywords must form a strict majority of input tokens
 *    to suppress false positives from long dictation phrases.
 *  - An intent must exceed [MIN_CONFIDENCE] to be considered.
 *  - Destructive actions require [HIGH_CONFIDENCE] to avoid accidental triggers.
 *
 * This is a zero-dependency, on-device solution that runs in < 1 ms. It can be
 * upgraded to Moonshine SDK's embedding-based IntentRecognizer (Gemma300m) when
 * the ~300 MB model download is acceptable.
 */
object IntentRecognizer {

    /** Minimum confidence to accept a match. */
    internal const val MIN_CONFIDENCE = 0.45f
    /** Higher threshold for destructive actions (delete, undo, clear). */
    internal const val HIGH_CONFIDENCE = 0.65f

    data class Intent(
        val action: VoiceAction,
        val keywords: List<Pair<String, Float>>,
        val destructive: Boolean = false,
    )

    data class RecognitionResult(
        val action: VoiceAction,
        val confidence: Float,
    )

    private val intents: List<Intent> = buildList {
        // Deletion intents
        add(Intent(
            action = VoiceAction.DeleteSelection,
            keywords = listOf("delete" to 1.5f, "remove" to 1.2f, "erase" to 1.2f, "that" to 0.5f, "this" to 0.5f, "selection" to 0.8f),
            destructive = true,
        ))
        add(Intent(
            action = VoiceAction.DeleteLastWord,
            keywords = listOf("delete" to 1.5f, "erase" to 1.2f, "remove" to 1.0f, "last" to 1.0f, "word" to 1.5f, "previous" to 0.8f),
            destructive = true,
        ))
        add(Intent(
            action = VoiceAction.DeleteLastSentence,
            keywords = listOf("delete" to 1.5f, "erase" to 1.2f, "remove" to 1.0f, "last" to 1.0f, "sentence" to 1.5f, "previous" to 0.8f),
            destructive = true,
        ))
        add(Intent(
            action = VoiceAction.ClearAll,
            keywords = listOf("clear" to 1.5f, "erase" to 1.2f, "delete" to 1.0f, "remove" to 1.2f, "all" to 1.2f, "everything" to 1.2f, "entire" to 0.8f, "whole" to 0.8f),
            destructive = true,
        ))

        // Undo / redo
        add(Intent(
            action = VoiceAction.Undo,
            keywords = listOf("undo" to 2.0f, "scratch" to 1.2f, "take" to 0.5f, "back" to 0.8f, "revert" to 1.5f, "reverse" to 1.0f),
            destructive = true,
        ))
        add(Intent(
            action = VoiceAction.Redo,
            keywords = listOf("redo" to 2.0f, "restore" to 1.0f, "bring" to 0.5f, "back" to 0.5f),
        ))

        // Selection
        add(Intent(
            action = VoiceAction.SelectAll,
            keywords = listOf("select" to 1.5f, "highlight" to 1.2f, "all" to 1.5f, "everything" to 1.2f, "entire" to 0.8f),
        ))

        // Clipboard
        add(Intent(
            action = VoiceAction.Copy,
            keywords = listOf("copy" to 2.0f, "that" to 0.5f, "this" to 0.5f, "selection" to 0.8f, "text" to 0.5f),
        ))
        add(Intent(
            action = VoiceAction.Cut,
            keywords = listOf("cut" to 2.0f, "that" to 0.5f, "this" to 0.5f, "selection" to 0.5f),
        ))
        add(Intent(
            action = VoiceAction.Paste,
            keywords = listOf("paste" to 2.0f, "insert" to 0.8f, "clipboard" to 1.0f, "here" to 0.5f),
        ))

        // Navigation
        add(Intent(
            action = VoiceAction.NewLine,
            keywords = listOf("new" to 0.8f, "next" to 0.8f, "line" to 1.5f, "enter" to 1.5f, "return" to 1.0f),
        ))
        add(Intent(
            action = VoiceAction.NewParagraph,
            keywords = listOf("new" to 0.8f, "next" to 0.8f, "paragraph" to 2.0f),
        ))
        add(Intent(
            action = VoiceAction.MoveCursorToEnd,
            keywords = listOf("move" to 0.8f, "go" to 0.8f, "jump" to 0.8f, "cursor" to 0.5f, "end" to 1.5f, "bottom" to 1.0f),
        ))
        add(Intent(
            action = VoiceAction.MoveCursorToStart,
            keywords = listOf("move" to 0.8f, "go" to 0.8f, "jump" to 0.8f, "cursor" to 0.5f, "start" to 1.2f, "beginning" to 1.5f, "top" to 1.0f),
        ))

        // Formatting
        add(Intent(
            action = VoiceAction.CapitalizeSelection,
            keywords = listOf("capitalize" to 2.0f, "capital" to 1.5f, "that" to 0.3f, "this" to 0.3f, "selection" to 0.5f),
        ))
        add(Intent(
            action = VoiceAction.UppercaseSelection,
            keywords = listOf("uppercase" to 2.0f, "upper" to 1.5f, "case" to 0.5f, "caps" to 1.0f, "all caps" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.LowercaseSelection,
            keywords = listOf("lowercase" to 2.0f, "lower" to 1.5f, "case" to 0.5f, "small" to 0.5f),
        ))
        add(Intent(
            action = VoiceAction.BoldSelection,
            keywords = listOf("bold" to 2.0f, "make" to 0.3f, "that" to 0.3f),
        ))
        add(Intent(
            action = VoiceAction.ItalicSelection,
            keywords = listOf("italic" to 2.0f, "italicize" to 2.0f, "make" to 0.3f, "that" to 0.3f),
        ))

        // Selection — word-level
        add(Intent(
            action = VoiceAction.SelectLastWord,
            keywords = listOf("select" to 1.5f, "highlight" to 1.2f, "last" to 1.2f, "previous" to 1.0f, "word" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.SelectLastSentence,
            keywords = listOf("select" to 1.5f, "highlight" to 1.2f, "last" to 1.2f, "previous" to 1.0f, "sentence" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.SelectCurrentWord,
            keywords = listOf("select" to 1.5f, "highlight" to 1.2f, "this" to 1.0f, "current" to 1.0f, "word" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.SelectCurrentLine,
            keywords = listOf("select" to 1.5f, "highlight" to 1.2f, "this" to 1.0f, "current" to 1.0f, "line" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.SelectCurrentSentence,
            keywords = listOf("select" to 1.5f, "highlight" to 1.2f, "this" to 1.0f, "current" to 1.0f, "sentence" to 1.5f),
        ))

        // Underline
        add(Intent(
            action = VoiceAction.UnderlineSelection,
            keywords = listOf("underline" to 2.0f, "make" to 0.3f, "that" to 0.3f),
        ))

        // Submit
        add(Intent(
            action = VoiceAction.Submit,
            keywords = listOf("submit" to 2.0f, "confirm" to 1.5f, "that" to 0.3f, "form" to 0.8f),
        ))

        // Clear / erase everything (natural language variations)
        add(Intent(
            action = VoiceAction.DeleteLastParagraph,
            keywords = listOf("delete" to 1.5f, "erase" to 1.2f, "remove" to 1.0f, "last" to 1.0f, "paragraph" to 1.5f, "previous" to 0.8f),
            destructive = true,
        ))

        // Session
        add(Intent(
            action = VoiceAction.StopListening,
            keywords = listOf("stop" to 1.5f, "listening" to 1.5f, "recording" to 1.2f, "dictation" to 1.2f, "done" to 1.5f, "finished" to 1.0f, "that's" to 0.5f, "enough" to 0.5f),
        ))
        add(Intent(
            action = VoiceAction.Send,
            keywords = listOf("send" to 2.0f, "message" to 0.8f, "it" to 0.3f, "that" to 0.3f),
        ))
        add(Intent(
            action = VoiceAction.GoBack,
            keywords = listOf("go" to 0.8f, "navigate" to 0.8f, "back" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.OpenSettings,
            keywords = listOf("open" to 0.8f, "settings" to 2.0f, "preferences" to 1.5f, "configuration" to 1.0f, "safe" to 0.3f, "word" to 0.3f),
        ))
        add(Intent(
            action = VoiceAction.ScrollUp,
            keywords = listOf("scroll" to 1.5f, "page" to 1.0f, "up" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.ScrollDown,
            keywords = listOf("scroll" to 1.5f, "page" to 1.0f, "down" to 1.5f),
        ))
        add(Intent(
            action = VoiceAction.Dismiss,
            keywords = listOf("dismiss" to 2.0f, "hide" to 1.5f, "close" to 1.5f, "keyboard" to 1.2f),
        ))
    }

    /**
     * Score all registered intents against the given normalised text.
     *
     * @param normalizedText Lowercase, whitespace-collapsed text (already stripped of
     *   wake words, polite wrappers, and trailing punctuation by [VoiceCommandDetector]).
     * @return The best matching [RecognitionResult], or `null` if no intent exceeds
     *   the confidence threshold.
     */
    fun recognize(normalizedText: String): RecognitionResult? {
        if (normalizedText.isBlank()) return null
        val tokens = normalizedText.split(' ')

        var bestResult: RecognitionResult? = null
        for (intent in intents) {
            val score = scoreIntent(intent, tokens, normalizedText)
            val threshold = if (intent.destructive) HIGH_CONFIDENCE else MIN_CONFIDENCE
            if (score >= threshold && (bestResult == null || score > bestResult.confidence)) {
                bestResult = RecognitionResult(intent.action, score)
            }
        }

        if (bestResult != null) {
            Timber.d("[INTENT] IntentRecognizer.recognize | text=\"%s\" action=%s confidence=%.3f",
                normalizedText, bestResult.action, bestResult.confidence)
        }
        return bestResult
    }

    private fun scoreIntent(intent: Intent, tokens: List<String>, fullText: String): Float {
        if (intent.keywords.isEmpty()) return 0f
        var matchedWeight = 0f
        var matchedCount = 0

        for ((keyword, weight) in intent.keywords) {
            val kwTokens = keyword.split(' ')
            if (kwTokens.size > 1) {
                // Multi-word keyword: check substring match
                if (fullText.contains(keyword)) {
                    matchedWeight += weight
                    matchedCount += kwTokens.size
                }
            } else {
                // Single-word keyword
                if (tokens.any { it == keyword }) {
                    matchedWeight += weight
                    matchedCount++
                } else if (keyword.length >= 4 && tokens.any { it.startsWith(keyword.take(4)) }) {
                    // Prefix match — partial credit (does not count toward
                    // matchedCount so it won't inflate the top-K denominator).
                    matchedWeight += weight * 0.5f
                }
            }
        }

        if (matchedCount == 0) return 0f

        // Guard: a strict majority of input tokens must be keyword hits.
        // This prevents false positives from long phrases that happen to
        // contain one or two command-like words (e.g. "paste the text here").
        if (matchedCount * 2 <= tokens.size) return 0f

        // Normalize by the sum of the top-K keyword weights (K = matchedCount)
        // instead of totalWeight. This avoids penalizing intents that define
        // many synonym triggers — only the strongest K weights matter.
        val topKWeight = intent.keywords
            .sortedByDescending { it.second }
            .take(matchedCount)
            .sumOf { it.second.toDouble() }.toFloat()

        return matchedWeight / topKWeight
    }
}
