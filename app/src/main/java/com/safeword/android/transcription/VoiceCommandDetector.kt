package com.safeword.android.transcription

import timber.log.Timber
import com.safeword.android.transcription.VoiceCommandRegistry.COMMAND_MAP
import com.safeword.android.transcription.VoiceCommandRegistry.COMMAND_STEMS
import com.safeword.android.transcription.VoiceCommandRegistry.STRUCTURAL_COMMANDS_BY_LENGTH_DESC
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of Phase 1 voice command detection.
 */
sealed interface VoiceCommandResult {
    /** The utterance is a known command — execute it, skip text post-processing. */
    data class Command(val action: VoiceAction, val confidence: Float = 1.0f) : VoiceCommandResult
    /**
     * A command was found at the **end** of the utterance, after some dictation text.
     * The dictation [prefix] should be inserted, then [action] should be executed.
     */
    data class TrailingCommand(val prefix: String, val action: VoiceAction, val confidence: Float = 1.0f) : VoiceCommandResult
    /** Normal dictation — pass to Phase 2 (ContentNormalizer). */
    data class Text(val rawText: String) : VoiceCommandResult
}

/**
 * Phase 1 — Voice Command Detection (pre-text processing, English only).
 *
 * Inspects the raw Moonshine transcription of a completed streaming line for a
 * known spoken command. If the entire utterance matches, a [VoiceCommandResult.Command]
 * is returned and the text never enters content normalization (Phase 2) or formatting
 * (Phase 3). Otherwise [VoiceCommandResult.Text] passes the utterance downstream.
 *
 * Matching is full-utterance only (after canonicalization). This prevents command
 * stems like "delete" or "stop" from firing inside normal dictation.
 *
 * Command vocabulary lives in [VoiceCommandRegistry].
 * Compositional matching (verb + target) is in [CompositionalCommandMatcher].
 */
@Singleton
class VoiceCommandDetector @Inject constructor() {

    companion object {
        /** Longest plausible command after all wrappers + one parameterized argument. */
        private const val MAX_COMMAND_RAW_LENGTH = 200

        /** Tail scan window for trailing commands embedded in long lines. */
        private const val TRAILING_SCAN_MAX_CHARS = 200

        // ── Canonicalization patterns ─────────────────────────────────────────────

        /** "Safe Word, ..." / "OK Safe Word ..." → strip wake-word prefix. */
        private val WAKE_WORD_PREFIX = Regex(
            "^(?:ok\\s+)?safe\\s*word\\b[,:\\-]?\\s*",
            RegexOption.IGNORE_CASE,
        )

        /** "command: ..." / "dictation command ..." → strip mode prefix. */
        private val COMMAND_MODE_PREFIX = Regex(
            "^(?:command(?:\\s+mode)?|dictation\\s+command)\\b[,:\\-]?\\s*",
            RegexOption.IGNORE_CASE,
        )

        /** "please ..." / "can you ..." / "would you ..." → strip polite prefix. */
        private val POLITE_PREFIX = Regex(
            "^(?:please|can\\s+you|could\\s+you|would\\s+you|will\\s+you)\\b\\s*",
            RegexOption.IGNORE_CASE,
        )

        /** "... please" / "... thanks" / "... thank you" → strip polite suffix. */
        private val POLITE_SUFFIX = Regex(
            "\\b(?:please|thanks|thank\\s+you)\\s*$",
            RegexOption.IGNORE_CASE,
        )

        // ── Parameterized patterns ────────────────────────────────────────────────

        /** "replace X with Y" / "change X to Y": group 1 = old text, group 2 = new text. */
        private val REPLACE_PATTERNS: List<Regex> = listOf(
            Regex("^replace (.+) with (.+)$"),
            Regex("^change (.+) to (.+)$"),
            Regex("^swap (.+) with (.+)$"),
            Regex("^find (.+) and replace(?: it)? with (.+)$"),
            Regex("^find (.+) and replace(?: it)? by (.+)$"),
        )

        /**
         * "highlight X" / "select X": group 1 = word or phrase to select in text.
         *
         * Note: exact COMMAND_MAP commands such as "select all", "select everything", and
         * "select last word" are matched before these parameterized patterns are evaluated,
         * so there is no ambiguity — e.g., "select all" → SelectAll, not SelectText("all").
         */
        private val SELECT_PATTERNS: List<Regex> = listOf(
            Regex("^highlight (?:the )?(?:word )?(.+)$"),
            Regex("^select (?:the )?(?:word )?(.+)$"),
        )

        /**
         * "search for X" / "search X" / "look up X" / "google X": group 1 = query.
         */
        private val SEARCH_PATTERNS: List<Regex> = listOf(
            Regex("^search (?:for )?(.+)$"),
            Regex("^look up (.+)$"),
            Regex("^google (.+)$"),
        )

        /** "delete last N words" / "erase last N words": group 1 = count (1–50). */
        private val DELETE_N_WORDS_PATTERN = Regex(
            "^(?:delete|erase|remove) (?:the )?last (\\d+) words?$",
        )

        /** Common words ASR prepends that never start a command phrase. */
        private val NOISE_WORDS: Set<String> = setOf(
            "a", "an", "the", "in", "on", "at", "to", "for",
            "um", "uh", "hmm", "er", "ah", "oh",
            "well", "so", "just", "like",
            "and", "but", "or", "is", "was", "be",
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Attempt to detect a voice command from raw Moonshine output.
     *
     * @param rawText The unprocessed transcription of a single completed line.
     * @return [VoiceCommandResult.Command] to execute and skip post-processing,
     *         or [VoiceCommandResult.Text] to continue through the pipeline.
     */
    fun detect(rawText: String): VoiceCommandResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return VoiceCommandResult.Text("")

        // Long utterances can't be commands (fast-path avoids normalization cost).
        if (trimmed.length > MAX_COMMAND_RAW_LENGTH) return VoiceCommandResult.Text(rawText)

        val normalized = normalizeCandidate(trimmed)

        // -- Exact match (Tier 1: highest confidence 0.95)
        val exactAction = COMMAND_MAP[normalized] ?: matchParameterizedCommand(normalized)
        if (exactAction != null) {
            Timber.i(
                "[VOICE] VoiceCommandDetector.detect | command=\"%s\" action=%s tier=1 confidence=%.2f",
                normalized, exactAction, OptimalParameters.getConfidenceThresholdForTier(1),
            )
            return VoiceCommandResult.Command(exactAction, OptimalParameters.getConfidenceThresholdForTier(1))
        }

        // -- Compositional match (Tier 2: verb + target, confidence 0.90)
        val composedAction = compositionalMatch(normalized)
        if (composedAction != null) {
            Timber.i(
                "[VOICE] VoiceCommandDetector.detect | command=\"%s\" action=%s tier=2 confidence=%.2f",
                normalized, composedAction, OptimalParameters.getConfidenceThresholdForTier(2),
            )
            return VoiceCommandResult.Command(composedAction, OptimalParameters.getConfidenceThresholdForTier(2))
        }

        // -- Fuzzy fallback: strip leading noise words → retry exact + compositional (Tier 3: confidence 0.85)
        val stripped = stripLeadingNoise(normalized)
        if (stripped != normalized && stripped.isNotEmpty()) {
            val strippedAction = COMMAND_MAP[stripped]
                ?: matchParameterizedCommand(stripped)
                ?: compositionalMatch(stripped)
            if (strippedAction != null) {
                Timber.i(
                    "[VOICE] detect | NOISE-STRIP input=\"%s\" stripped=\"%s\" action=%s tier=3 confidence=%.2f",
                    normalized, stripped, strippedAction, OptimalParameters.getConfidenceThresholdForTier(3),
                )
                return VoiceCommandResult.Command(strippedAction, OptimalParameters.getConfidenceThresholdForTier(3))
            }
        }

        return VoiceCommandResult.Text(rawText)
    }

    /**
     * Extended command detection that also recognises a command appended to the **end**
     * of an utterance that contains leading dictation text.
     *
     * Motivation: ASR engines (including Moonshine) sometimes merge a short pause between
     * dictation and a command into a single completed line, e.g.:
     *   "So I'm still typing. Delete last sentence."
     * Full-utterance detection correctly rejects this (not a standalone command), but the
     * intent is clear -- insert the dictation prefix and execute the command.
     *
     * Only multi-word structural commands are eligible (at least 2 words). Single-word
     * commands ("copy", "undo", "stop", ...) are skipped to avoid false positives on common
     * English words that legitimately appear at the end of dictation.
     *
     * @return [VoiceCommandResult.Command] for a full-utterance match,
     *         [VoiceCommandResult.TrailingCommand] when a command trails dictation text,
     *         or [VoiceCommandResult.Text] when no command is found.
     */
    fun detectIncludingTrailing(rawText: String): VoiceCommandResult {
        // Fast path: full-utterance check first (cheapest + most reliable).
        val fullMatch = detect(rawText)
        if (fullMatch is VoiceCommandResult.Command) return fullMatch

        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return VoiceCommandResult.Text(rawText)

        // If the line contains multiple sentences, try the last sentence as a command.
        if (trimmed.any { it in ".!?\u2026" }) {
            val lastSentence = trimmed
                .split(Regex("[.!?\u2026]+"))
                .lastOrNull { it.isNotBlank() }
                ?.trim()
            if (!lastSentence.isNullOrEmpty()) {
                val sentenceMatch = detect(lastSentence)
                val normalizedLastSentence = normalizeCandidate(lastSentence)
                if (sentenceMatch is VoiceCommandResult.Command && isTrailingSentenceCommandCandidate(normalizedLastSentence)) {
                    val sentenceStart = trimmed.lastIndexOf(lastSentence)
                    val rawPrefix = if (sentenceStart > 0) {
                        trimmed.substring(0, sentenceStart).trim().trimEnd('.', ',', ';', ':', '\u2026')
                    } else {
                        ""
                    }
                    return if (rawPrefix.isBlank()) {
                        VoiceCommandResult.Command(sentenceMatch.action, sentenceMatch.confidence)
                    } else {
                        VoiceCommandResult.TrailingCommand(rawPrefix, sentenceMatch.action, sentenceMatch.confidence)
                    }
                }
            }
        }

        val searchableRaw = trimmed
            .trimEnd('.', ',', '!', '?', ';', ':', '\u2026')
            .trim()
        val searchable = searchableRaw.lowercase()

        val tail = if (searchable.length > TRAILING_SCAN_MAX_CHARS) {
            searchable.takeLast(TRAILING_SCAN_MAX_CHARS)
        } else {
            searchable
        }

        for ((phrase, action) in STRUCTURAL_COMMANDS_BY_LENGTH_DESC) {
            if (!tail.endsWith(phrase)) continue
            val cmdStart = searchableRaw.length - phrase.length
            if (cmdStart > 0 && searchableRaw[cmdStart - 1] != ' ') continue

            val rawPrefix = searchableRaw.substring(0, cmdStart)
                .trim()
                .trimEnd('.', ',', ';', ':', '\u2026')

            return if (rawPrefix.isBlank()) {
                VoiceCommandResult.Command(action)
            } else {
                Timber.i(
                    "[VOICE] VoiceCommandDetector.detectIncludingTrailing | prefix='%s' command=%s",
                    rawPrefix, action,
                )
                VoiceCommandResult.TrailingCommand(rawPrefix, action)
            }
        }

        return VoiceCommandResult.Text(rawText)
    }

    /**
     * Safety guard for sentence-tail command extraction: only permit multi-word
     * candidates to avoid accidental one-word command triggers at sentence boundaries.
     */
    private fun isTrailingSentenceCommandCandidate(normalized: String): Boolean =
        normalized.split(WHITESPACE_SPLIT_REGEX).count { it.isNotBlank() } >= 2

    // ── Canonicalization ──────────────────────────────────────────────────────

    private fun normalizeCandidate(text: String): String {
        var candidate = text.trim()
            .trimEnd('.', ',', '!', '?', ';', ':', '\u2026')
            .trim()

        var changed: Boolean
        do {
            changed = false
            var c = candidate
            val c1 = WAKE_WORD_PREFIX.replaceFirst(c, "");   if (c1 != c) { c = c1; changed = true }
            val c2 = COMMAND_MODE_PREFIX.replaceFirst(c, ""); if (c2 != c) { c = c2; changed = true }
            val c3 = POLITE_PREFIX.replaceFirst(c, "");       if (c3 != c) { c = c3; changed = true }
            val c4 = POLITE_SUFFIX.replaceFirst(c, "");       if (c4 != c) { c = c4; changed = true }
            val c5 = c.trimStart(',', ':', '-', ' ').trimEnd(',', ':', '-', ' ')
            if (c5 != c) { changed = true }
            candidate = c5
        } while (changed && candidate.isNotEmpty())

        return candidate
            .lowercase()
            .replace(Regex("[,;:!?\"\u201C\u201D]"), "") // strip interior punctuation (keep apostrophes for "i'm")
            .replace(WHITESPACE_SPLIT_REGEX, " ")
            .trim()
    }

    // ── Parameterized matching ────────────────────────────────────────────────

    private fun matchParameterizedCommand(normalized: String): VoiceAction? {
        for (pattern in REPLACE_PATTERNS) {
            val m = pattern.matchEntire(normalized) ?: continue
            val old = m.groupValues[1].trim()
            val new = m.groupValues[2].trim()
            if (old.isNotEmpty() && new.isNotEmpty()) return VoiceAction.ReplaceText(old, new)
        }
        for (pattern in SELECT_PATTERNS) {
            val m = pattern.matchEntire(normalized) ?: continue
            val word = m.groupValues[1].trim()
            if (word.isNotEmpty()) return VoiceAction.SelectText(word)
        }
        for (pattern in SEARCH_PATTERNS) {
            val m = pattern.matchEntire(normalized) ?: continue
            val query = m.groupValues[1].trim()
            if (query.isNotEmpty()) return VoiceAction.SearchFor(query)
        }
        DELETE_N_WORDS_PATTERN.matchEntire(normalized)?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: return@let
            if (count in 1..50) return VoiceAction.DeleteLastNWords(count)
        }
        return null
    }

    // ── Noise word stripping ────────────────────────────────────────────────

    private fun stripLeadingNoise(text: String): String {
        val words = text.split(WHITESPACE_SPLIT_REGEX)
        val idx = words.indexOfFirst { it !in NOISE_WORDS }
        return if (idx > 0) words.subList(idx, words.size).joinToString(" ") else text
    }
}
