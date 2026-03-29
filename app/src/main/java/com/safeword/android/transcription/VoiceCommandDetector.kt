package com.safeword.android.transcription

import timber.log.Timber

/**
 * Phase 1 — Voice Command Detection (pre-text processing).
 *
 * Inspects raw Whisper output for spoken voice commands. If the entire utterance
 * matches a known command, returns a [VoiceCommandResult.Command] with early exit —
 * the text never enters content normalization or formatting.
 *
 * Commands are matched as full-utterance phrases (after trim + lowercase) to avoid
 * false positives inside normal dictation.
 */
object VoiceCommandDetector {

    // -- Command vocabulary ---------------------------------------------------
    // Keyed by canonical spoken phrase (lowercase). Values are the action to execute.

    private val COMMAND_MAP: Map<String, VoiceAction> = buildMap {
        // Deletion
        put("delete that", VoiceAction.DeleteSelection)
        put("delete this", VoiceAction.DeleteSelection)
        put("erase that", VoiceAction.DeleteSelection)
        put("remove that", VoiceAction.DeleteSelection)
        put("delete last word", VoiceAction.DeleteLastWord)
        put("erase last word", VoiceAction.DeleteLastWord)
        put("delete last sentence", VoiceAction.DeleteLastSentence)
        put("delete sentence", VoiceAction.DeleteLastSentence)
        put("erase last sentence", VoiceAction.DeleteLastSentence)
        put("backspace", VoiceAction.Backspace)

        // Undo / redo
        put("undo", VoiceAction.Undo)
        put("undo that", VoiceAction.Undo)
        put("scratch that", VoiceAction.Undo)
        put("take that back", VoiceAction.Undo)
        put("redo", VoiceAction.Redo)
        put("redo that", VoiceAction.Redo)

        // Selection
        put("select all", VoiceAction.SelectAll)
        put("highlight all", VoiceAction.SelectAll)
        put("select last word", VoiceAction.SelectLastWord)

        // Clipboard
        put("copy that", VoiceAction.Copy)
        put("copy", VoiceAction.Copy)
        put("copy this", VoiceAction.Copy)
        put("cut that", VoiceAction.Cut)
        put("cut this", VoiceAction.Cut)
        put("cut", VoiceAction.Cut)
        put("paste", VoiceAction.Paste)
        put("paste that", VoiceAction.Paste)
        put("paste here", VoiceAction.Paste)

        // Navigation
        put("new line", VoiceAction.NewLine)
        put("newline", VoiceAction.NewLine)
        put("next line", VoiceAction.NewLine)
        put("go to next line", VoiceAction.NewLine)
        put("enter", VoiceAction.NewLine)
        put("press enter", VoiceAction.NewLine)
        put("hit enter", VoiceAction.NewLine)
        put("new paragraph", VoiceAction.NewParagraph)
        put("next paragraph", VoiceAction.NewParagraph)

        // Formatting
        put("capitalize that", VoiceAction.CapitalizeSelection)
        put("uppercase that", VoiceAction.UppercaseSelection)
        put("lowercase that", VoiceAction.LowercaseSelection)

        // Spacing / punctuation shortcuts
        put("space", VoiceAction.InsertText(" "))
        put("add a space", VoiceAction.InsertText(" "))
        put("type a space", VoiceAction.InsertText(" "))
        put("tab", VoiceAction.InsertText("\t"))
        put("period", VoiceAction.InsertText(". "))
        put("full stop", VoiceAction.InsertText(". "))
        put("dot", VoiceAction.InsertText(". "))
        put("comma", VoiceAction.InsertText(","))
        put("add a comma", VoiceAction.InsertText(","))
        put("question mark", VoiceAction.InsertText("?"))
        put("exclamation point", VoiceAction.InsertText("!"))
        put("exclamation mark", VoiceAction.InsertText("!"))
        put("colon", VoiceAction.InsertText(":"))
        put("semicolon", VoiceAction.InsertText(";"))
        put("hyphen", VoiceAction.InsertText("-"))
        put("dash", VoiceAction.InsertText(" — "))

        // Session control
        put("send", VoiceAction.Send)
        put("send that", VoiceAction.Send)
        put("send message", VoiceAction.Send)
        put("send it", VoiceAction.Send)
        put("clear", VoiceAction.ClearAll)
        put("clear all", VoiceAction.ClearAll)
        put("clear everything", VoiceAction.ClearAll)
        put("erase all", VoiceAction.ClearAll)
        put("erase everything", VoiceAction.ClearAll)
        put("go back", VoiceAction.GoBack)
        put("stop listening", VoiceAction.StopListening)
        put("stop recording", VoiceAction.StopListening)
        put("stop", VoiceAction.StopListening)
        put("stop dictation", VoiceAction.StopListening)
        put("done", VoiceAction.StopListening)
    }

    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val WAKE_WORD_PREFIX = Regex("^(?:ok\\s+)?safe\\s*word\\b[,:\\-]?\\s*", RegexOption.IGNORE_CASE)
    private val COMMAND_MODE_PREFIX = Regex("^(?:command(?:\\s+mode)?|dictation\\s+command)\\b[,:\\-]?\\s*", RegexOption.IGNORE_CASE)
    private val POLITE_PREFIX = Regex("^(?:please|can\\s+you|could\\s+you|would\\s+you|will\\s+you)\\b\\s*", RegexOption.IGNORE_CASE)
    private val POLITE_SUFFIX = Regex("\\b(?:please|thanks|thank\\s+you)\\s*$", RegexOption.IGNORE_CASE)

    /** Max raw length that could plausibly be a command (with wake-word + polite wrappers). */
    private const val MAX_COMMAND_RAW_LENGTH = 80

    private val COMMAND_STEMS: Set<String> = setOf(
        "delete", "undo", "redo", "select", "copy", "cut", "paste",
        "new", "capitalize", "uppercase", "lowercase", "send", "clear",
        "back", "stop", "period", "comma", "question", "exclamation", "enter",
        "erase", "remove", "scratch", "highlight", "done", "dash", "colon",
        "semicolon", "hyphen", "dot",
    )

    /**
     * Attempt to detect a voice command from raw Whisper output.
     *
     * @param rawText The unprocessed transcription text.
     * @return [VoiceCommandResult.Command] if the utterance is a command (early exit),
     *         or [VoiceCommandResult.Text] if it should proceed to content normalization.
     */
    fun detect(rawText: String): VoiceCommandResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return VoiceCommandResult.Text("")

        // Fast-path: utterances longer than any plausible command skip detection entirely.
        if (trimmed.length > MAX_COMMAND_RAW_LENGTH) return VoiceCommandResult.Text(rawText)

        // Full-utterance command mode with deterministic canonicalization.
        // We only strip wrappers (wake-word/polite wrappers), not content words,
        // to avoid false positives on normal dictation.
        val normalized = normalizeCandidate(trimmed)

        val action = COMMAND_MAP[normalized]
        return if (action != null) {
            Timber.i("[VOICE] VoiceCommandDetector.detect | command=\"%s\" action=%s", normalized, action)
            VoiceCommandResult.Command(action)
        } else {
            logPotentialCommandMiss(trimmed)
            VoiceCommandResult.Text(rawText)
        }
    }

    private fun normalizeCandidate(text: String): String {
        var candidate = text.trim()
            .trimEnd('.', ',', '!', '?', ';', ':')
            .trim()

        var changed: Boolean
        do {
            changed = false

            val afterWakeWord = WAKE_WORD_PREFIX.replaceFirst(candidate, "")
            if (afterWakeWord != candidate) {
                candidate = afterWakeWord
                changed = true
            }

            val afterCommandMode = COMMAND_MODE_PREFIX.replaceFirst(candidate, "")
            if (afterCommandMode != candidate) {
                candidate = afterCommandMode
                changed = true
            }

            val afterPolitePrefix = POLITE_PREFIX.replaceFirst(candidate, "")
            if (afterPolitePrefix != candidate) {
                candidate = afterPolitePrefix
                changed = true
            }

            val afterPoliteSuffix = POLITE_SUFFIX.replaceFirst(candidate, "")
            if (afterPoliteSuffix != candidate) {
                candidate = afterPoliteSuffix
                changed = true
            }

            candidate = candidate.trimStart(',', ':', '-', ' ').trimEnd(',', ':', '-', ' ')
        } while (changed && candidate.isNotEmpty())

        return candidate
            .lowercase()
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
    }

    private fun logPotentialCommandMiss(trimmedText: String) {
        // Keep diagnostics low-noise: only short utterances that look command-like.
        if (trimmedText.length > 40) return
        val normalized = trimmedText.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
        val hasCommandStem = COMMAND_STEMS.any { stem -> normalized.contains(stem) }
        if (hasCommandStem) {
            Timber.w("[VOICE] VoiceCommandDetector.detect | near-miss raw=\"%s\" normalized=\"%s\"", trimmedText, normalized)
        }
    }
}

/**
 * Result of Phase 1 voice command detection.
 */
sealed interface VoiceCommandResult {
    /** The utterance is a command — execute on InputConnection, skip text processing. */
    data class Command(val action: VoiceAction) : VoiceCommandResult

    /** The utterance is normal text — proceed to Phase 2 (content normalization). */
    data class Text(val rawText: String) : VoiceCommandResult
}

/**
 * Actions that voice commands can trigger on the editor via InputConnection.
 */
sealed interface VoiceAction {
    data object DeleteSelection : VoiceAction
    data object DeleteLastWord : VoiceAction
    data object DeleteLastSentence : VoiceAction
    data object Backspace : VoiceAction
    data object Undo : VoiceAction
    data object Redo : VoiceAction
    data object SelectAll : VoiceAction
    data object SelectLastWord : VoiceAction
    data object Copy : VoiceAction
    data object Cut : VoiceAction
    data object Paste : VoiceAction
    data object NewLine : VoiceAction
    data object NewParagraph : VoiceAction
    data object CapitalizeSelection : VoiceAction
    data object UppercaseSelection : VoiceAction
    data object LowercaseSelection : VoiceAction
    data class InsertText(val text: String) : VoiceAction
    data object Send : VoiceAction
    data object ClearAll : VoiceAction
    data object GoBack : VoiceAction
    data object StopListening : VoiceAction
}
