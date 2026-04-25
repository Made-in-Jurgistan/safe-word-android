package com.safeword.android.service

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.safeword.android.transcription.VoiceAction
import com.safeword.android.transcription.clampCursor
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.ArrayDeque
import java.util.Locale

/**
 * Executes voice actions against the currently focused accessibility node.
 *
 * Extracted from [SafeWordAccessibilityService] so the dispatch table and
 * per-action helpers can be developed and tested independently of the service lifecycle.
 *
 * @param rootProvider Returns the root [AccessibilityNodeInfo] for the active window, or null.
 * @param textInserter Delegates text/paste insertion back to the service's strategy stack.
 */
internal class VoiceActionExecutor(
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val textInserter: (String) -> Boolean,
) {

    // ── Public entry point ──────────────────────────────────────────────────

    fun execute(action: VoiceAction): Boolean = when (action) {
        is VoiceAction.InsertText -> textInserter(action.text)
        is VoiceAction.NewLine -> textInserter("\n")
        is VoiceAction.NewParagraph -> textInserter("\n\n")
        is VoiceAction.Backspace -> performBackspace()
        is VoiceAction.DeleteSelection -> performDeleteSelection()
        is VoiceAction.DeleteLastWord -> performDeleteLastWord()
        is VoiceAction.DeleteLastSentence -> performDeleteLastSentence()
        is VoiceAction.DeleteLastNWords -> performDeleteLastNWords(action.count)
        is VoiceAction.ClearAll -> performClearAll()
        is VoiceAction.Undo -> performUndoRedo(undo = true)
        is VoiceAction.Redo -> performUndoRedo(undo = false)
        is VoiceAction.SelectAll -> performSelectAll()
        is VoiceAction.SelectLastWord -> performSelectLastWord()
        is VoiceAction.SelectLastSentence -> performSelectLastSentence()
        is VoiceAction.SelectNextWord -> performSelectNextWord()
        is VoiceAction.DeleteNextWord -> performDeleteNextWord()
        is VoiceAction.MoveCursorToStart -> performMoveCursorToStart()
        is VoiceAction.MoveCursorToEnd -> performMoveCursorToEnd()
        is VoiceAction.ScrollUp -> performScroll(scrollDown = false)
        is VoiceAction.ScrollDown -> performScroll(scrollDown = true)
        is VoiceAction.DismissKeyboard -> performDismissKeyboard()
        is VoiceAction.InsertDate -> textInserter(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                .withLocale(Locale.getDefault())
                .format(LocalDate.now())
        )
        is VoiceAction.InsertTime -> textInserter(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
                .format(LocalTime.now())
        )
        is VoiceAction.Copy -> performClipboardAction(AccessibilityNodeInfo.ACTION_COPY)
        is VoiceAction.Cut -> performClipboardAction(AccessibilityNodeInfo.ACTION_CUT)
        is VoiceAction.Paste -> performClipboardAction(AccessibilityNodeInfo.ACTION_PASTE)
        is VoiceAction.CapitalizeLastWord -> performTransformLastWord { text ->
            // Capitalize only the first letter of the first token — matches the command's name.
            // Previous implementation titlecased every word when an active selection spanned
            // multiple words, producing "Hello World" instead of "Hello world".
            text.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString() }
        }
        is VoiceAction.UppercaseLastWord -> performTransformLastWord { it.uppercase() }
        is VoiceAction.LowercaseLastWord -> performTransformLastWord { it.lowercase() }
        is VoiceAction.ReplaceText -> performReplaceText(action.oldText, action.newText)
        is VoiceAction.SelectText -> performSelectText(action.query)
        is VoiceAction.SearchFor -> performSearchFor(action.query)
        is VoiceAction.Bold -> performFormattingAction("bold")
        is VoiceAction.Italic -> performFormattingAction("italic")
        is VoiceAction.Underline -> performFormattingAction("underline")
        is VoiceAction.Strikethrough -> performFormattingAction("strikethrough")
        is VoiceAction.Send -> performSend()
        is VoiceAction.StopListening -> true  // handled upstream in TranscriptionCoordinator
    }

    // ── Node helpers ────────────────────────────────────────────────────────

    private fun getFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootProvider() ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        return findFirstEditableNode(root)
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

    // ── Action implementations ──────────────────────────────────────────────

    private fun performBackspace(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        val selStart = (node.textSelectionStart.takeIf { it >= 0 } ?: text.length).clampCursor(text)
        val selEnd = (node.textSelectionEnd.takeIf { it >= 0 } ?: selStart).coerceIn(selStart, text.length)
        val newText: String
        val newCursor: Int
        if (selStart != selEnd) {
            newText = text.substring(0, selStart) + text.substring(selEnd)
            newCursor = selStart
        } else if (selStart > 0) {
            newText = text.substring(0, selStart - 1) + text.substring(selStart)
            newCursor = selStart - 1
        } else {
            return false
        }
        return setTextAndCursor(node, newText, newCursor)
    }

    private fun performDeleteSelection(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        val selStart = (node.textSelectionStart.takeIf { it >= 0 } ?: return false).clampCursor(text)
        val selEnd = (node.textSelectionEnd.takeIf { it >= 0 } ?: return false).coerceIn(selStart, text.length)
        if (selStart == selEnd) return false
        val newText = text.substring(0, selStart) + text.substring(selEnd)
        return setTextAndCursor(node, newText, selStart)
    }

    private fun performDeleteLastWord(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        // Use textSelectionEnd so deletion anchors to the end of any active selection.
        val cursor = (node.textSelectionEnd.takeIf { it >= 0 } ?: text.length).clampCursor(text)
        if (cursor == 0) return false
        var i = cursor
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        val newText = text.substring(0, i) + text.substring(cursor)
        return setTextAndCursor(node, newText, i)
    }

    private fun performDeleteLastNWords(count: Int): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        val cursor = (node.textSelectionEnd.takeIf { it >= 0 } ?: text.length).clampCursor(text)
        if (cursor == 0) return false
        var i = cursor
        repeat(count) {
            while (i > 0 && text[i - 1].isWhitespace()) i--
            while (i > 0 && !text[i - 1].isWhitespace()) i--
            if (i == 0) return@repeat
        }
        val newText = text.substring(0, i) + text.substring(cursor)
        return setTextAndCursor(node, newText, i)
    }

    private fun performDeleteLastSentence(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        // Use textSelectionEnd so deletion anchors to the end of any active selection.
        val cursor = (node.textSelectionEnd.takeIf { it >= 0 } ?: text.length).clampCursor(text)
        if (cursor == 0) return false
        var i = cursor
        // Step 1: skip any trailing whitespace after the cursor
        while (i > 0 && text[i - 1].isWhitespace()) i--
        // Step 2: skip the sentence-ending punctuation of the sentence we are about to delete.
        // '\u2026' (…) is the Unicode ellipsis produced by ContentNormalizer; treat as terminator.
        while (i > 0 && text[i - 1] in ".!?\u2026") i--
        // Step 3: walk backward through the sentence body until the previous terminator or start.
        while (i > 0 && text[i - 1] !in ".!?\n\u2026") i--
        val newText = text.substring(0, i) + text.substring(cursor)
        return setTextAndCursor(node, newText, i)
    }

    private fun performClearAll(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        return setTextAndCursor(node, "", 0)
    }

    private fun performUndoRedo(undo: Boolean): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val actionId = resolveUndoRedoActionId(node, undo) ?: return false
        val result = node.performAction(actionId)
        Timber.d("[A11Y] performUndoRedo | undo=%b actionId=0x%08X result=%b", undo, actionId, result)
        return result
    }

    private fun resolveUndoRedoActionId(node: AccessibilityNodeInfo, undo: Boolean): Int? {
        val actionName = if (undo) "ACTION_UNDO" else "ACTION_REDO"

        // Preferred path: public AccessibilityAction constant if available in current SDK stubs.
        val actionObject = runCatching {
            AccessibilityNodeInfo.AccessibilityAction::class.java
                .getField(actionName)
                .get(null) as? AccessibilityNodeInfo.AccessibilityAction
        }.getOrNull()
        if (actionObject != null) return actionObject.id

        // Fallback: int action constant on AccessibilityNodeInfo if exposed.
        val intConstant = runCatching {
            AccessibilityNodeInfo::class.java.getField(actionName).getInt(null)
        }.getOrNull()
        if (intConstant != null) return intConstant

        // Last-resort heuristic: scan node action labels.
        val targetLabel = if (undo) "undo" else "redo"
        return node.actionList.firstOrNull { action ->
            action.label?.toString()?.equals(targetLabel, ignoreCase = true) == true
        }?.id
    }

    private fun performSelectAll(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString()
        if (text.isNullOrEmpty()) {
            // Some editors expose selectable content without returning node.text.
            val selected = node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            if (selected) return true
            val fallbackArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, fallbackArgs)
        }
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun performSelectLastWord(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString()
        if (text.isNullOrEmpty()) {
            // Best effort fallback when text is inaccessible on this editor.
            return node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
        }
        val cursor = (node.textSelectionStart.takeIf { it >= 0 } ?: text.length).clampCursor(text)
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

    private fun performSelectLastSentence(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        val cursor = (node.textSelectionEnd.takeIf { it >= 0 } ?: text.length).clampCursor(text)
        if (cursor == 0) return false
        // Skip trailing whitespace and punctuation after the sentence.
        var i = cursor
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && text[i - 1] in ".!?\u2026") i--
        // Walk back to the previous sentence terminator.
        var sentStart = i
        while (sentStart > 0 && text[sentStart - 1] !in ".!?\n\u2026") sentStart--
        if (sentStart == cursor) return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, sentStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun performSelectNextWord(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        val cursor = (node.textSelectionEnd.takeIf { it >= 0 } ?: 0).clampCursor(text)
        // Skip whitespace ahead of cursor, then scan to end of next word.
        var wordStart = cursor
        while (wordStart < text.length && text[wordStart].isWhitespace()) wordStart++
        var wordEnd = wordStart
        while (wordEnd < text.length && !text[wordEnd].isWhitespace()) wordEnd++
        if (wordStart == wordEnd) return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, wordStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, wordEnd)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun performDeleteNextWord(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        val cursor = (node.textSelectionEnd.takeIf { it >= 0 } ?: 0).clampCursor(text)
        var wordStart = cursor
        while (wordStart < text.length && text[wordStart].isWhitespace()) wordStart++
        var wordEnd = wordStart
        while (wordEnd < text.length && !text[wordEnd].isWhitespace()) wordEnd++
        if (wordStart == wordEnd) return false
        val newText = text.substring(0, cursor) + text.substring(wordEnd)
        return setTextAndCursor(node, newText, cursor)
    }

    private fun performMoveCursorToStart(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 0)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun performMoveCursorToEnd(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val len = node.text?.length ?: 0
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun performScroll(scrollDown: Boolean): Boolean {
        val root = rootProvider() ?: return false
        val scrollable = findScrollableNode(root) ?: return false
        val action = if (scrollDown) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                     else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return scrollable.performAction(action)
    }

    private fun findScrollableNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > 8) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i) ?: continue, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun performDismissKeyboard(): Boolean {
        val node = getFocusedEditableNode() ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
    }

    private fun performClipboardAction(action: Int): Boolean {
        val node = getFocusedEditableNode() ?: return false
        return node.performAction(action)
    }

    private fun performTransformLastWord(transform: (String) -> String): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val text = node.text?.toString() ?: return false
        val selEnd = (node.textSelectionEnd.takeIf { it >= 0 } ?: text.length).clampCursor(text)
        val selStart = (node.textSelectionStart.takeIf { it >= 0 } ?: selEnd).clampCursor(text)
        // If there is an active selection, transform the whole selected range.
        val (wordStart, wordEnd) = if (selStart < selEnd) {
            selStart to selEnd
        } else {
            // No active selection — find the last word boundary before cursor.
            var wEnd = selEnd
            while (wEnd > 0 && text[wEnd - 1].isWhitespace()) wEnd--
            var wStart = wEnd
            while (wStart > 0 && !text[wStart - 1].isWhitespace()) wStart--
            wStart to wEnd
        }
        if (wordStart == wordEnd) return false
        val word = text.substring(wordStart, wordEnd)
        val transformed = transform(word)
        val newText = text.substring(0, wordStart) + transformed + text.substring(wordEnd)
        return setTextAndCursor(node, newText, wordStart + transformed.length)
    }

    private fun performReplaceText(oldText: String, newText: String): Boolean {
        val node = getFocusedEditableNode() ?: return false
        if (node.isPassword) return false
        val current = node.text?.toString() ?: return false
        // Use the built-in case-insensitive search so the match span is a true
        // substring of [current]. Slicing by oldText.length can go out of bounds
        // when case-folding changes character width (e.g. German 'ß' → "SS").
        val idx = current.indexOf(oldText, ignoreCase = true)
        if (idx < 0) return false
        val endIdx = (idx + oldText.length).coerceAtMost(current.length)
        val replaced = current.substring(0, idx) + newText + current.substring(endIdx)
        return setTextAndCursor(node, replaced, idx + newText.length)
    }

    private fun performSelectText(query: String): Boolean {
        val node = getFocusedEditableNode() ?: return false
        val text = node.text?.toString() ?: return false
        val idx = text.lowercase().indexOf(query.lowercase())
        if (idx < 0) return false
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, idx)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, idx + query.length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    /**
     * Searches the accessibility tree for a clickable toolbar button whose label
     * (contentDescription or text) contains the given [formatting] keyword
     * (e.g. "bold", "italic"). Works in Google Docs, Samsung Notes, Word, and most
     * rich-text editors that expose toolbar buttons to accessibility.
     */
    private fun performFormattingAction(formatting: String): Boolean {
        val root = rootProvider() ?: return false
        val button = findNodeByContentDesc(root, setOf(formatting))
        if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        Timber.w("[WARN] performFormattingAction | button not found for formatting=%s", formatting)
        return false
    }

    /**
     * Finds a search or URL bar in the active window and types the query.
     * Falls back to the focused editable node if no search bar is found.
     */
    private fun performSearchFor(query: String): Boolean {
        val root = rootProvider() ?: return false
        val searchField = findSearchField(root)
        if (searchField != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            return searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        Timber.w("[WARN] performSearchFor | no search field found, falling back to text insertion")
        return textInserter(query)
    }

    /**
     * Finds a search field using BFS traversal for WebView compatibility.
     */
    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable) {
                val hint = (node.hintText?.toString() ?: node.contentDescription?.toString())?.lowercase()
                if (hint != null && SEARCH_FIELD_HINTS.any { hint.contains(it) }) {
                    Timber.d("[A11Y] findSearchField | BFS found search field at visited=%d hint=%s", visited, hint)
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        if (visited >= 200) {
            Timber.w("[A11Y] findSearchField | BFS truncated at 200 nodes")
        }
        return null
    }

    private fun performSend(): Boolean {
        val root = rootProvider()
        if (root != null) {
            val sendButton = findSendButton(root)
            if (sendButton != null && sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return textInserter("\n")
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNodeByContentDesc(root, setOf("send", "submit", "search", "go"))

    /**
     * Finds a clickable node matching [keywords] using BFS traversal.
     * BFS is preferred over DFS for finding UI elements as it discovers
     * elements in visual/reading order rather than deep-branch first.
     */
    private fun findNodeByContentDesc(
        root: AccessibilityNodeInfo,
        keywords: Set<String>,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            val node = queue.removeFirst()
            visited++
            if (node.isClickable) {
                // Check both contentDescription (set by developers for accessibility) and text
                // (the visible label on most Button / ImageButton / FAB widgets).
                val label = (node.contentDescription?.toString() ?: node.text?.toString())
                    ?.lowercase()
                if (label != null && keywords.any { label.contains(it) }) {
                    Timber.d("[A11Y] findNodeByContentDesc | found match at visited=%d label=%s", visited, label)
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        if (visited >= 200) {
            Timber.w("[A11Y] findNodeByContentDesc | BFS truncated at 200 nodes")
        }
        return null
    }

    /**
     * Attempts to tap a Floating Action Button (FAB) when no editable field is accessible.
     * Common in chat apps (WhatsApp, Telegram) where the FAB opens the chat input.
     *
     * @return true if a FAB was found and tapped, false otherwise.
     */
    internal fun tryTapFloatingActionButton(): Boolean {
        val root = rootProvider() ?: return false
        val fab = findNodeByContentDesc(root, setOf("new message", "compose", "create", "add", "chat", "send message"))
        if (fab != null && fab.isClickable) {
            val result = fab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Timber.i("[A11Y] tryTapFloatingActionButton | ACTION_CLICK result=%b", result)
            return result
        }
        return false
    }

    companion object {
        private val SEARCH_FIELD_HINTS = setOf("search", "url", "address", "find", "query")

        /**
         * Finds the first editable descendant of [root] using breadth-first search (BFS).
         * BFS is preferred for WebView content and complex UIs as it discovers elements
         * in visual/reading order rather than deep-branch first (DFS).
         *
         * Exposed as `internal` so [SafeWordAccessibilityService] can reuse it for
         * [SafeWordAccessibilityService.performTextInsertion] and
         * [SafeWordAccessibilityService.performSessionReplacement] without duplicating the logic.
         */
        internal fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 300) {
                val node = queue.removeFirst()
                visited++
                if (node.isEditable) {
                    Timber.d("[A11Y] findFirstEditableNode | BFS found editable at visited=%d", visited)
                    return node
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.addLast(child)
                }
            }
            if (visited >= 300) {
                Timber.w("[A11Y] findFirstEditableNode | BFS truncated at 300 nodes")
            }
            return null
        }
    }
}
