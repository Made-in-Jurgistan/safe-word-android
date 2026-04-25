package com.safeword.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.safeword.android.R
import com.safeword.android.transcription.VoiceAction
import com.safeword.android.transcription.clampCursor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HolderEntryPoint {
        fun accessibilityStateHolder(): AccessibilityStateHolder
    }

    /** Delay before auto-clearing clipboard fallback content (privacy safeguard). */
    private companion object {
        const val CLIPBOARD_CLEAR_DELAY_MS = 60_000L

        /** Maximum characters allowed in a single text insertion — prevents DoS via oversized transcripts. */
        const val MAX_INSERTION_LENGTH = 10_000
    }

    private lateinit var stateHolder: AccessibilityStateHolder

    /** Cached result of the last [hasImeWindow] IPC call. Updated only on WINDOWS_CHANGED events. */
    @Volatile private var cachedImeVisible: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        stateHolder = EntryPointAccessors.fromApplication(
            application,
            HolderEntryPoint::class.java,
        ).accessibilityStateHolder()
        stateHolder.serviceInstance = this
        Timber.i("[LIFECYCLE] SafeWordAccessibilityService.onServiceConnected")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
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
                // Single IPC: cache root + focused node for both focus tracking and context update.
                val root = rootInActiveWindow
                val focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val hasEditableFocus = focusedNode != null && focusedNode.isEditable
                if (stateHolder.textFieldFocused.value != hasEditableFocus) {
                    stateHolder.setTextFieldFocused(hasEditableFocus)
                    Timber.d("[A11Y] onAccessibilityEvent | textFieldFocused=%b event=%d", hasEditableFocus, event.eventType)
                }
                val imeVisible = if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                    val result = hasImeWindow()
                    cachedImeVisible = result
                    result
                } else {
                    cachedImeVisible
                }
                if (stateHolder.keyboardVisible.value != imeVisible) {
                    stateHolder.setKeyboardVisible(imeVisible)
                    Timber.d("[A11Y] onAccessibilityEvent | keyboardVisible=%b event=%d", imeVisible, event.eventType)
                }
                updateInputContext(event.packageName?.toString(), root, focusedNode)
            }
            else -> { /* ignore TYPE_VIEW_TEXT_CHANGED for focus tracking */ }
        }
    }

    private fun updateInputContext(
        eventPackage: String?,
        root: AccessibilityNodeInfo?,
        focused: AccessibilityNodeInfo?,
    ) {
        val packageName = eventPackage
            ?: focused?.packageName?.toString()
            ?: root?.packageName?.toString()
            ?: ""
        val hint = focused?.hintText?.toString().orEmpty()
        val className = focused?.className?.toString().orEmpty()

        if (stateHolder.activePackageName.value != packageName) {
            stateHolder.setActivePackageName(packageName)
        }
        if (stateHolder.focusedFieldHint.value != hint) {
            stateHolder.setFocusedFieldHint(hint)
        }
        if (stateHolder.focusedFieldClass.value != className) {
            stateHolder.setFocusedFieldClass(className)
        }
    }

    /** Check whether an IME (soft keyboard) window is currently on screen. */
    private fun hasImeWindow(): Boolean =
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    override fun onInterrupt() {
        Timber.w("[LIFECYCLE] SafeWordAccessibilityService.onInterrupt")
    }

    override fun onDestroy() {
        // Remove all pending clipboard auto-clear callbacks to prevent leaking
        // the service through the Handler's internal message queue.
        clipboardHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        if (::stateHolder.isInitialized) {
            stateHolder.serviceInstance = null
            stateHolder.setTextFieldFocused(false)
            stateHolder.setKeyboardVisible(false)
            stateHolder.setActivePackageName("")
            stateHolder.setFocusedFieldHint("")
            stateHolder.setFocusedFieldClass("")
        }
        Timber.i("[LIFECYCLE] SafeWordAccessibilityService.onDestroy")
    }

    /**
     * Return the current text content of the focused input field, or null if
     * - no active window / focused field
     * - the field is a password field (never read for privacy)
     */
    internal fun getCurrentFocusedFieldText(): String? {
        val rootNode = rootInActiveWindow ?: return null
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (focusedNode.isPassword) return null
        return focusedNode.text?.toString()
    }

    /**
     * Find the focused editable text field and insert text.
     * Strategy:
     *   1. Find focused input node → ACTION_SET_TEXT
     *   2. Find any editable node → ACTION_SET_TEXT
     *   3. Fallback: copy to clipboard + ACTION_PASTE on focused node
     */
    internal fun performTextInsertion(text: String): Boolean {
        if (text.length > MAX_INSERTION_LENGTH) {
            Timber.w("[A11Y] performTextInsertion | text too long: %d chars (max=%d) — truncating", text.length, MAX_INSERTION_LENGTH)
            // Fall back to clipboard with truncated text rather than silently truncating insertion,
            // so the user at least has access to the full content.
            return copyToClipboardOnly(text.take(MAX_INSERTION_LENGTH))
        }
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

        // Strategy 2b: Try tapping FAB to open chat input (WhatsApp, Telegram, etc.)
        // This handles apps where the input field is not accessible until FAB is tapped.
        if (voiceActionExecutor.tryTapFloatingActionButton()) {
            // After tapping FAB, retry finding the editable node
            val newRoot = rootInActiveWindow
            if (newRoot != null) {
                val postFabNode = findFirstEditableNode(newRoot)
                if (postFabNode != null) {
                    Timber.d("[A11Y] performTextInsertion | strategy=FAB_THEN_EDITABLE class=%s", postFabNode.className)
                    val success = insertIntoNode(postFabNode, text)
                    if (success) return true
                    return pasteViaClipboard(postFabNode, text)
                }
            }
        }

        // Strategy 3: clipboard-only fallback
        Timber.w("[A11Y] performTextInsertion | strategy=CLIPBOARD_ONLY — no editable field found")
        return copyToClipboardOnly(text)
    }

    private fun insertIntoNode(node: AccessibilityNodeInfo, text: String): Boolean = try {
        insertIntoNodeUnsafe(node, text)
    } catch (e: IllegalStateException) {
        Timber.w(e, "[WARN] insertIntoNode | node detached during action")
        false
    }

    private fun insertIntoNodeUnsafe(node: AccessibilityNodeInfo, text: String): Boolean {
        // Privacy: never read existing text from password fields — only insert.
        if (node.isPassword) {
            Timber.d("[A11Y] insertIntoNode | password field detected — using ACTION_PASTE instead of reading text")
            return pasteViaClipboard(node, text)
        }
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
        val selStart = if (treatingAsHint) 0 else (node.textSelectionStart.takeIf { it >= 0 } ?: existingText.length).clampCursor(existingText)
        val selEnd = if (treatingAsHint) existingText.length else (node.textSelectionEnd.takeIf { it >= 0 } ?: selStart).coerceIn(selStart, existingText.length)
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
        val clip = ClipData.newPlainText(getString(R.string.clipboard_label), text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)

        val result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (result) {
            Timber.i("[A11Y] pasteViaClipboard | ACTION_PASTE success chars=%d", text.length)
            // On Android 13+ (API 33+), clipboard content is visible in system UI.
            // Clear immediately for privacy rather than waiting for auto-clear.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clipboard.clearPrimaryClip()
                Timber.d("[CLIPBOARD] Cleared immediately on Android 13+ (API %d)", Build.VERSION.SDK_INT)
            }
        } else {
            Timber.w("[A11Y] pasteViaClipboard | ACTION_PASTE failed — text in clipboard for manual paste")
        }
        return result
    }

    /**
     * Replace everything in the focused field from [sessionStartOffset] onward with [newText].
     * Preserves pre-session text (indices 0..<sessionStartOffset).
     */
    internal fun performSessionReplacement(sessionStartOffset: Int, newText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return copyToClipboardOnly(newText)
        val node = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?: findFirstEditableNode(rootNode)
            ?: return copyToClipboardOnly(newText)
        return replaceSessionInNode(node, sessionStartOffset, newText)
    }

    private fun replaceSessionInNode(
        node: AccessibilityNodeInfo,
        sessionStartOffset: Int,
        newText: String,
    ): Boolean = try {
        replaceSessionInNodeUnsafe(node, sessionStartOffset, newText)
    } catch (e: IllegalStateException) {
        Timber.w(e, "[WARN] replaceSessionInNode | node detached during action")
        false
    }

    private fun replaceSessionInNodeUnsafe(
        node: AccessibilityNodeInfo,
        sessionStartOffset: Int,
        newText: String,
    ): Boolean {
        if (node.isPassword) return pasteViaClipboard(node, newText)
        val rawExistingText = node.text?.toString() ?: ""
        val hint = node.hintText?.toString().orEmpty()
        val treatingAsHint = node.isShowingHintText ||
            (hint.isNotEmpty() && rawExistingText.trim().equals(hint.trim(), ignoreCase = true))
        val existingText = if (treatingAsHint) "" else rawExistingText
        val safeOffset = sessionStartOffset.coerceIn(0, existingText.length)
        val newFullText = existingText.substring(0, safeOffset) + newText
        val cursorPos = newFullText.length
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newFullText,
            )
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (result) {
            val moveArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursorPos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, moveArgs)
            Timber.i("[A11Y] replaceSessionInNode | success totalChars=%d cursorAt=%d", newFullText.length, cursorPos)
        } else {
            Timber.w("[A11Y] replaceSessionInNode | ACTION_SET_TEXT failed totalChars=%d", newFullText.length)
        }
        return result
    }

    // Handler for delayed clipboard cleanup
    private val clipboardHandler = Handler(Looper.getMainLooper())

    /**
     * Last resort: just put text on clipboard so the user can paste manually.
     * Auto-clears after 60s to avoid leaking sensitive transcription text.
     */
    private fun copyToClipboardOnly(text: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.clipboard_label), text)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clipboard.setPrimaryClip(clip)
            Timber.i("[CLIPBOARD] copyToClipboardOnly | success chars=%d", text.length)
            // Schedule auto-clear to mitigate privacy risk of leaving transcription on clipboard
            clipboardHandler.postDelayed({
                try {
                    if (clipboard.primaryClip?.getItemAt(0)?.text == text) {
                        clipboard.clearPrimaryClip()
                        Timber.d("[CLIPBOARD] Auto-cleared after timeout")
                    }
                } catch (_: Exception) { /* ignore */ }
            }, CLIPBOARD_CLEAR_DELAY_MS)
            true
        } catch (e: Exception) {
            Timber.e(e, "[CLIPBOARD] copyToClipboardOnly | failed")
            false
        }
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        VoiceActionExecutor.findFirstEditableNode(node)

    // ── Voice action execution ──────────────────────────────────────────────

    private val voiceActionExecutor by lazy {
        VoiceActionExecutor({ rootInActiveWindow }, ::performTextInsertion)
    }

    internal fun performVoiceAction(action: VoiceAction): Boolean =
        voiceActionExecutor.execute(action)
}
