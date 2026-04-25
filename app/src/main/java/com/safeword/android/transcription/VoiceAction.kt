package com.safeword.android.transcription

/**
 * Every action that a spoken voice command can trigger on the focused editor.
 * All variants must be handled in [SafeWordAccessibilityService.performVoiceAction].
 */
sealed interface VoiceAction {
    // Deletion
    data object DeleteSelection : VoiceAction
    data object DeleteLastWord : VoiceAction
    data object DeleteLastSentence : VoiceAction
    data object DeleteNextWord : VoiceAction
    data object Backspace : VoiceAction
    data object ClearAll : VoiceAction
    data class DeleteLastNWords(val count: Int) : VoiceAction

    // History
    data object Undo : VoiceAction
    data object Redo : VoiceAction

    // Selection
    data object SelectAll : VoiceAction
    data object SelectLastWord : VoiceAction
    data object SelectLastSentence : VoiceAction
    data object SelectNextWord : VoiceAction

    // Clipboard
    data object Copy : VoiceAction
    data object Cut : VoiceAction
    data object Paste : VoiceAction

    // Navigation & structure
    data object NewLine : VoiceAction
    data object NewParagraph : VoiceAction
    data object MoveCursorToStart : VoiceAction
    data object MoveCursorToEnd : VoiceAction
    data object ScrollUp : VoiceAction
    data object ScrollDown : VoiceAction
    data object DismissKeyboard : VoiceAction

    // Formatting
    data object CapitalizeLastWord : VoiceAction
    data object UppercaseLastWord : VoiceAction
    data object LowercaseLastWord : VoiceAction

    // Inline insertion
    data class InsertText(val text: String) : VoiceAction
    data object InsertDate : VoiceAction
    data object InsertTime : VoiceAction

    // Parameterized editing
    data class ReplaceText(val oldText: String, val newText: String) : VoiceAction
    data class SelectText(val query: String) : VoiceAction
    data class SearchFor(val query: String) : VoiceAction

    // Rich-text formatting (applied to current selection)
    data object Bold : VoiceAction
    data object Italic : VoiceAction
    data object Underline : VoiceAction
    data object Strikethrough : VoiceAction

    // App / session
    data object Send : VoiceAction
    data object StopListening : VoiceAction
}
