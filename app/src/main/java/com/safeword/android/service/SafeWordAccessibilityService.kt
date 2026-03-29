package com.safeword.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.safeword.android.R
import com.safeword.android.transcription.VoiceAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * SafeWordAccessibilityService — Direct text insertion into any app.
 *
 * Mirrors desktop Safe Word's `enigo` paste functionality:
 * Desktop: types/pastes text via OS-level keyboard simulation.
 * Android: uses AccessibilityService to find the focused text field and insert text.
 * Falls back to clipboard paste if ACTION_SET_TEXT fails.
 *
 * This service is activated via Settings → Accessibility.
 */
class SafeWordAccessibilityService : AccessibilityService() {

    data class InputContextSnapshot(
        val packageName: String,
        val hintText: String,
        val className: String,
        val textFieldFocused: Boolean,
        val keyboardVisible: Boolean,
    )

    companion object {
        @Volatile
        var instance: SafeWordAccessibilityService? = null
            private set

        private val _textFieldFocused = MutableStateFlow(false)
        /** True when an editable text field is focused in any app. */
        val textFieldFocused: StateFlow<Boolean> = _textFieldFocused.asStateFlow()

        private val _keyboardVisible = MutableStateFlow(false)
        /** True when any IME (soft keyboard) window is visible on screen. */
        val keyboardVisible: StateFlow<Boolean> = _keyboardVisible.asStateFlow()

        private val _activePackageName = MutableStateFlow("")
        /** Package name for the currently focused editor context (if available). */
        val activePackageName: StateFlow<String> = _activePackageName.asStateFlow()

        private val _focusedFieldHint = MutableStateFlow("")
        /** Hint/placeholder text for the currently focused editable node (if available). */
        val focusedFieldHint: StateFlow<String> = _focusedFieldHint.asStateFlow()

        private val _focusedFieldClass = MutableStateFlow("")
        /** Class name of the currently focused editable node (if available). */
        val focusedFieldClass: StateFlow<String> = _focusedFieldClass.asStateFlow()

        /** Snapshot of the current input context for STT prompt/correction decisions. */
        fun inputContextSnapshot(): InputContextSnapshot = InputContextSnapshot(
            packageName = _activePackageName.value,
            hintText = _focusedFieldHint.value,
            className = _focusedFieldClass.value,
            textFieldFocused = _textFieldFocused.value,
            keyboardVisible = _keyboardVisible.value,
        )

        /**
         * Insert text into the currently focused text field in any app.
         * Falls back to clipboard paste if direct insertion fails.
         *
         * @return true if text was successfully inserted
         */
        fun insertText(text: String): Boolean {
            val service = instance ?: run {
                Timber.w("[A11Y] insertText | service not active, cannot insert %d chars", text.length)
                return false
            }
            Timber.d("[A11Y] insertText | attempting to insert %d chars", text.length)
            return service.performTextInsertion(text)
        }

        /** Check if the accessibility service is currently active. */
        fun isActive(): Boolean {
            val active = instance != null
            Timber.v("[A11Y] isActive | active=%b", active)
            return active
        }

        /**
         * Execute a voice action on the currently focused text field.
         *
         * @return true if the action was executed successfully, false if the service
         *         is not active or the action could not be performed.
         */
        fun executeVoiceAction(action: VoiceAction): Boolean {
            val service = instance ?: run {
                Timber.w("[A11Y] executeVoiceAction | service not active, cannot execute %s", action)
                return false
            }
            Timber.i("[VOICE] executeVoiceAction | action=%s", action)
            return service.performVoiceAction(action)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("[LIFECYCLE] SafeWordAccessibilityService.onServiceConnected")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val focused = hasFocusedEditableNode()
                if (_textFieldFocused.value != focused) {
                    _textFieldFocused.value = focused
                    Timber.d("[A11Y] onAccessibilityEvent | textFieldFocused=%b event=%d", focused, event.eventType)
                }
                val imeVisible = hasImeWindow()
                if (_keyboardVisible.value != imeVisible) {
                    _keyboardVisible.value = imeVisible
                    Timber.d("[A11Y] onAccessibilityEvent | keyboardVisible=%b event=%d", imeVisible, event.eventType)
                }
                updateInputContext(event.packageName?.toString())
            }
            else -> { /* ignore TYPE_VIEW_TEXT_CHANGED for focus tracking */ }
        }
    }

    private fun updateInputContext(eventPackage: String?) {
        val root = rootInActiveWindow
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val packageName = eventPackage
            ?: focused?.packageName?.toString()
            ?: root?.packageName?.toString()
            ?: ""
        val hint = focused?.hintText?.toString().orEmpty()
        val className = focused?.className?.toString().orEmpty()

        if (_activePackageName.value != packageName) {
            _activePackageName.value = packageName
        }
        if (_focusedFieldHint.value != hint) {
            _focusedFieldHint.value = hint
        }
        if (_focusedFieldClass.value != className) {
            _focusedFieldClass.value = className
        }
    }

    /**
     * Check whether the active window has a focused editable node.
     */
    private fun hasFocusedEditableNode(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused != null && focused.isEditable
    }

    /** Check whether an IME (soft keyboard) window is currently on screen. */
    private fun hasImeWindow(): Boolean =
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    override fun onInterrupt() {
        Timber.w("[LIFECYCLE] SafeWordAccessibilityService.onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _textFieldFocused.value = false
        _keyboardVisible.value = false
        _activePackageName.value = ""
        _focusedFieldHint.value = ""
        _focusedFieldClass.value = ""
        Timber.i("[LIFECYCLE] SafeWordAccessibilityService.onDestroy")
    }

    /**
     * Find the focused editable text field and insert text.
     * Strategy:
     *   1. Find focused input node → ACTION_SET_TEXT
     *   2. Find any editable node → ACTION_SET_TEXT
     *   3. Fallback: copy to clipboard + ACTION_PASTE on focused node
     */
    private fun performTextInsertion(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Timber.w("[A11Y] performTextInsertion | no active window root node — clipboard fallback")
            return copyToClipboardOnly(text)
        }

        // Strategy 1: focused editable node
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            Timber.d("[A11Y] performTextInsertion | strategy=FOCUSED_EDITABLE class=%s", focusedNode.className)
            val success = insertIntoNode(focusedNode, text)
            if (success) return true
            Timber.d("[A11Y] performTextInsertion | ACTION_SET_TEXT failed, falling back to paste")
            return pasteViaClipboard(focusedNode, text)
        }

        // Strategy 2: any editable node in the window
        val editableNode = findFirstEditableNode(rootNode)
        if (editableNode != null) {
            Timber.d("[A11Y] performTextInsertion | strategy=FIRST_EDITABLE class=%s", editableNode.className)
            val success = insertIntoNode(editableNode, text)
            if (success) return true
            Timber.d("[A11Y] performTextInsertion | ACTION_SET_TEXT failed on first editable, falling back to paste")
            return pasteViaClipboard(editableNode, text)
        }

        // Strategy 3: clipboard-only fallback
        Timber.w("[A11Y] performTextInsertion | strategy=CLIPBOARD_ONLY — no editable field found")
        return copyToClipboardOnly(text)
    }

    private fun insertIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val rawExistingText = node.text?.toString() ?: ""
        val hint = node.hintText?.toString().orEmpty()
        val normalizedExisting = rawExistingText.trim()
        val normalizedHint = hint.trim()
        // Some search fields expose placeholder text via node.text; replace it instead of appending.
        val treatingAsHint = node.isShowingHintText ||
            (normalizedHint.isNotEmpty() && normalizedExisting.equals(normalizedHint, ignoreCase = true))
        val existingText = if (treatingAsHint) "" else rawExistingText

        // Respect cursor position so text is inserted at caret for real content;
        // for hint placeholders, force replacement from the beginning.
        val selStart = if (treatingAsHint) 0 else (node.textSelectionStart.takeIf { it >= 0 } ?: existingText.length)
        val selEnd = if (treatingAsHint) existingText.length else (node.textSelectionEnd.takeIf { it >= 0 } ?: selStart)
        val newText = existingText.substring(0, selStart) + text + existingText.substring(selEnd)
        val newCursorPos = selStart + text.length

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText,
            )
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (result) {
            Timber.i("[A11Y] insertIntoNode | ACTION_SET_TEXT success chars=%d cursorAt=%d", text.length, newCursorPos)
            // Restore cursor to insertion point
            val moveArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, moveArgs)
        } else {
            Timber.w("[A11Y] insertIntoNode | ACTION_SET_TEXT failed chars=%d", text.length)
        }
        return result
    }

    /**
     * Paste via clipboard: set clipboard content then perform ACTION_PASTE on the node.
     */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), text))

        val result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (result) {
            Timber.i("[A11Y] pasteViaClipboard | ACTION_PASTE success chars=%d", text.length)
        } else {
            Timber.w("[A11Y] pasteViaClipboard | ACTION_PASTE failed — text in clipboard for manual paste")
        }
        return result
    }

    /**
     * Last resort: just put text on clipboard so the user can paste manually.
     */
    private fun copyToClipboardOnly(text: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), text))
            Timber.i("[CLIPBOARD] copyToClipboardOnly | success chars=%d", text.length)
            true
        } catch (e: Exception) {
            Timber.e(e, "[CLIPBOARD] copyToClipboardOnly | failed")
            false
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 20) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditableNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    // ── Voice action execution ──────────────────────────────────────────────

    private fun performVoiceAction(action: VoiceAction): Boolean {
        return when (action) {
            is VoiceAction.InsertText -> performTextInsertion(action.text)
            is VoiceAction.NewLine -> performTextInsertion("\n")
            is VoiceAction.NewParagraph -> performTextInsertion("\n\n")

            is VoiceAction.Backspace -> performBackspace()
            is VoiceAction.DeleteSelection -> performDeleteSelection()
            is VoiceAction.DeleteLastWord -> performDeleteLastWord()
            is VoiceAction.DeleteLastSentence -> performDeleteLastSentence()
            is VoiceAction.ClearAll -> performClearAll()

            is VoiceAction.Undo -> performUndoRedo(undo = true)
            is VoiceAction.Redo -> performUndoRedo(undo = false)

            is VoiceAction.SelectAll -> performSelectAll()
            is VoiceAction.SelectLastWord -> performSelectLastWord()

            is VoiceAction.Copy -> performClipboardAction(AccessibilityNodeInfo.ACTION_COPY)
            is VoiceAction.Cut -> performClipboardAction(AccessibilityNodeInfo.ACTION_CUT)
            is VoiceAction.Paste -> performClipboardAction(AccessibilityNodeInfo.ACTION_PASTE)

            is VoiceAction.CapitalizeSelection -> performTransformSelection { text ->
                text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            is VoiceAction.UppercaseSelection -> performTransformSelection { it.uppercase() }
            is VoiceAction.LowercaseSelection -> performTransformSelection { it.lowercase() }

            is VoiceAction.GoBack -> performGlobalAction(GLOBAL_ACTION_BACK)
            is VoiceAction.Send -> performSend()

            // StopListening is handled by TranscriptionCoordinator directly — never reaches here
            is VoiceAction.StopListening -> true
        }
    }

    private fun getFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        return findFirstEditableNode(root)
    }

    private fun performBackspace(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val selStart = node.textSelectionStart.takeIf { it >= 0 } ?: text.length
        val selEnd = node.textSelectionEnd.takeIf { it >= 0 } ?: selStart
        val newText: String
        val newCursor: Int
        if (selStart != selEnd) {
            // Delete selection
            newText = text.substring(0, selStart) + text.substring(selEnd)
            newCursor = selStart
        } else if (selStart > 0) {
            // Delete one character before cursor
            newText = text.substring(0, selStart - 1) + text.substring(selStart)
            newCursor = selStart - 1
        } else {
            return false
        }
        return setTextAndCursor(node, newText, newCursor)
    }

    private fun performDeleteSelection(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val selStart = node.textSelectionStart.takeIf { it >= 0 } ?: return false
        val selEnd = node.textSelectionEnd.takeIf { it >= 0 } ?: return false
        if (selStart == selEnd) return false // nothing selected
        val newText = text.substring(0, selStart) + text.substring(selEnd)
        return setTextAndCursor(node, newText, selStart)
    }

    private fun performDeleteLastWord(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val cursor = node.textSelectionStart.takeIf { it >= 0 } ?: text.length
        if (cursor == 0) return false
        // Scan backwards: skip trailing whitespace, then delete word chars
        var i = cursor
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        val newText = text.substring(0, i) + text.substring(cursor)
        return setTextAndCursor(node, newText, i)
    }

    private fun performDeleteLastSentence(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val cursor = node.textSelectionStart.takeIf { it >= 0 } ?: text.length
        if (cursor == 0) return false
        // Scan backwards past current sentence to the previous sentence-ending punctuation
        var i = cursor
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && text[i - 1] !in ".!?\n") i--
        val newText = text.substring(0, i) + text.substring(cursor)
        return setTextAndCursor(node, newText, i)
    }

    private fun performClearAll(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        return setTextAndCursor(node, "", 0)
    }

    private fun performUndoRedo(undo: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Timber.w("[A11Y] performUndoRedo | undo=%b unsupported on API %d (requires 34+)", undo, Build.VERSION.SDK_INT)
            return false
        }
        val node = getFocusedEditableNode() ?: return false
        // AccessibilityAction IDs for undo (0x02000000) and redo (0x02000001) added in API 34.
        val actionId = if (undo) 0x02000000 else 0x02000001
        val result = node.performAction(actionId)
        Timber.d("[A11Y] performUndoRedo | undo=%b result=%b", undo, result)
        return result
    }

    private fun performSelectAll(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun performSelectLastWord(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val cursor = node.textSelectionStart.takeIf { it >= 0 } ?: text.length
        var wordEnd = cursor
        while (wordEnd > 0 && text[wordEnd - 1].isWhitespace()) wordEnd--
        var wordStart = wordEnd
        while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) wordStart--
        if (wordStart == wordEnd) return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, wordStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, wordEnd)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun performClipboardAction(action: Int): Boolean {
        val node = getFocusedEditableNode() ?: return false
        return node.performAction(action)
    }

    private fun performTransformSelection(transform: (String) -> String): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val selStart = node.textSelectionStart.takeIf { it >= 0 } ?: return false
        val selEnd = node.textSelectionEnd.takeIf { it >= 0 } ?: return false
        if (selStart == selEnd) return false
        val selected = text.substring(selStart, selEnd)
        val transformed = transform(selected)
        val newText = text.substring(0, selStart) + transformed + text.substring(selEnd)
        return setTextAndCursor(node, newText, selStart + transformed.length)
    }

    private fun performSend(): Boolean {
        // Simulate pressing Enter/Send via IME action on the focused node
        val node = getFocusedEditableNode()
        if (node != null) {
            val args = Bundle().apply {
                putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
                )
            }
            // Try clicking the send/action button if IME exposes one; fall back to newline
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) return true
        }
        // Fall back to inserting newline as a "send" attempt
        return performTextInsertion("\n")
    }

    private fun setTextAndCursor(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (result) {
            val moveArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, moveArgs)
        }
        return result
    }
}
