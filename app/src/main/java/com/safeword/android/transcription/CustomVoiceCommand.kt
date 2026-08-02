package com.safeword.android.transcription

import kotlinx.serialization.Serializable

/**
 * A user-defined custom voice command.
 *
 * Users can define custom trigger phrases that map to either:
 *  - **Text insertion**: The spoken phrase inserts specific text (e.g. "my email" → "john@example.com").
 *  - **Built-in action**: The spoken phrase triggers a standard [VoiceAction] (e.g. "nuke it" → ClearAll).
 *
 * Custom commands are evaluated in [VoiceCommandDetector] **after** exact match but
 * **before** IntentRecognizer, giving users override priority for phrases they define.
 */
@Serializable
data class CustomVoiceCommand(
    /** Unique identifier for the command. */
    val id: String,
    /** The spoken trigger phrase (lowercased, trimmed). Multiple phrases separated by "|". */
    val triggerPhrases: String,
    /** If non-null, the command inserts this text. */
    val insertText: String? = null,
    /** If non-null, the command triggers this built-in action name. */
    val actionName: String? = null,
    /** Whether this command is currently enabled. */
    val enabled: Boolean = true,
) {
    /**
     * Parse [triggerPhrases] into individual trigger strings.
     */
    fun triggers(): List<String> = triggerPhrases
        .split("|")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    /**
     * Resolve this command to a [VoiceAction], or null if misconfigured.
     */
    fun toVoiceAction(): VoiceAction? {
        // Text insertion takes priority.
        if (!insertText.isNullOrEmpty()) {
            return VoiceAction.InsertText(insertText)
        }
        // Map action name to built-in VoiceAction.
        return actionName?.let { resolveActionName(it) }
    }

    companion object {
        /** Map human-readable action names to [VoiceAction] instances. */
        private fun resolveActionName(name: String): VoiceAction? = when (name.lowercase().trim()) {
            "delete", "delete selection" -> VoiceAction.DeleteSelection
            "delete last word" -> VoiceAction.DeleteLastWord
            "delete last sentence" -> VoiceAction.DeleteLastSentence
            "delete last paragraph" -> VoiceAction.DeleteLastParagraph
            "backspace" -> VoiceAction.Backspace
            "undo" -> VoiceAction.Undo
            "redo" -> VoiceAction.Redo
            "select all" -> VoiceAction.SelectAll
            "select last word" -> VoiceAction.SelectLastWord
            "select last sentence" -> VoiceAction.SelectLastSentence
            "select last paragraph" -> VoiceAction.SelectLastParagraph
            "select current word" -> VoiceAction.SelectCurrentWord
            "select current sentence" -> VoiceAction.SelectCurrentSentence
            "select current line" -> VoiceAction.SelectCurrentLine
            "select to start" -> VoiceAction.SelectToStart
            "select to end" -> VoiceAction.SelectToEnd
            "copy" -> VoiceAction.Copy
            "cut" -> VoiceAction.Cut
            "paste" -> VoiceAction.Paste
            "new line" -> VoiceAction.NewLine
            "new paragraph" -> VoiceAction.NewParagraph
            "capitalize" -> VoiceAction.CapitalizeSelection
            "uppercase" -> VoiceAction.UppercaseSelection
            "lowercase" -> VoiceAction.LowercaseSelection
            "bold" -> VoiceAction.BoldSelection
            "italic" -> VoiceAction.ItalicSelection
            "underline" -> VoiceAction.UnderlineSelection
            "move to start" -> VoiceAction.MoveCursorToStart
            "move to end" -> VoiceAction.MoveCursorToEnd
            "move to line start" -> VoiceAction.MoveToLineStart
            "move to line end" -> VoiceAction.MoveToLineEnd
            "send" -> VoiceAction.Send
            "submit" -> VoiceAction.Submit
            "clear", "clear all" -> VoiceAction.ClearAll
            "go back" -> VoiceAction.GoBack
            "stop", "stop listening" -> VoiceAction.StopListening
            "open settings" -> VoiceAction.OpenSettings
            "scroll up" -> VoiceAction.ScrollUp
            "scroll down" -> VoiceAction.ScrollDown
            "dismiss" -> VoiceAction.Dismiss
            else -> null
        }

        /** List of available action names for UI display. */
        val AVAILABLE_ACTIONS: List<String> = listOf(
            "delete selection", "delete last word", "delete last sentence",
            "delete last paragraph", "backspace",
            "undo", "redo",
            "select all", "select last word", "select last sentence",
            "select last paragraph", "select current word", "select current sentence",
            "select current line", "select to start", "select to end",
            "copy", "cut", "paste",
            "new line", "new paragraph",
            "capitalize", "uppercase", "lowercase",
            "bold", "italic", "underline",
            "move to start", "move to end", "move to line start", "move to line end",
            "send", "submit", "clear all",
            "go back", "stop listening", "open settings",
            "scroll up", "scroll down", "dismiss",
        )
    }
}
