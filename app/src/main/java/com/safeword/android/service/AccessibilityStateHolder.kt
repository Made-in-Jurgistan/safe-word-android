package com.safeword.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the current input field context from AccessibilityService.
 *
 * Promoted to a top-level type so consumers do not need to depend on
 * [SafeWordAccessibilityService] directly.
 */
data class InputContextSnapshot(
    val packageName: String,
    val hintText: String,
    val className: String,
    val textFieldFocused: Boolean,
    val keyboardVisible: Boolean,
)

/**
 * Centralized holder for accessibility-derived state, extracted from
 * [SafeWordAccessibilityService]'s companion object so that consumers
 * can obtain this state via Hilt injection instead of static references.
 *
 * The service itself writes state here; other components only read.
 */
@Singleton
class AccessibilityStateHolder @Inject constructor() {

    // ── Mutable state (written by SafeWordAccessibilityService) ─────────

    private val _textFieldFocused = MutableStateFlow(false)
    val textFieldFocused: StateFlow<Boolean> = _textFieldFocused.asStateFlow()

    private val _keyboardVisible = MutableStateFlow(false)
    val keyboardVisible: StateFlow<Boolean> = _keyboardVisible.asStateFlow()

    private val _activePackageName = MutableStateFlow("")
    val activePackageName: StateFlow<String> = _activePackageName.asStateFlow()

    private val _focusedFieldHint = MutableStateFlow("")
    val focusedFieldHint: StateFlow<String> = _focusedFieldHint.asStateFlow()

    private val _focusedFieldClass = MutableStateFlow("")
    val focusedFieldClass: StateFlow<String> = _focusedFieldClass.asStateFlow()

    /**
     * Service reference — set by the service in [onServiceConnected],
     * cleared in [onDestroy].
     *
     * Writing via the property setter also updates [serviceActive] so that
     * observers (e.g. the onboarding UI) react immediately to service
     * connection without polling.
     *
     * Thread-safety: callers MUST capture this into a local `val` in a single @Volatile read
     * and perform the null check on that local val — never read `serviceInstance` twice.
     * The local val holds a strong reference so the instance cannot be GC'd mid-call even if
     * the field is concurrently set to null by [SafeWordAccessibilityService.onDestroy].
     */
    @Volatile
    var serviceInstance: SafeWordAccessibilityService? = null
        internal set(value) {
            field = value
            _serviceActive.value = value != null
        }

    private val _serviceActive = MutableStateFlow(false)
    /** Emits `true` the moment [onServiceConnected] fires; `false` when [onDestroy] fires. */
    val serviceActive: StateFlow<Boolean> = _serviceActive.asStateFlow()

    // ── Mutators (called by the service) ────────────────────────────────

    fun setTextFieldFocused(value: Boolean) { _textFieldFocused.value = value }
    fun setKeyboardVisible(value: Boolean) { _keyboardVisible.value = value }
    fun setActivePackageName(value: String) { _activePackageName.value = value }
    fun setFocusedFieldHint(value: String) { _focusedFieldHint.value = value }
    fun setFocusedFieldClass(value: String) { _focusedFieldClass.value = value }

    // ── Queries (used by consumers) ─────────────────────────────────────

    fun isActive(): Boolean = serviceInstance != null

    fun inputContextSnapshot(): InputContextSnapshot =
        InputContextSnapshot(
            packageName = _activePackageName.value,
            hintText = _focusedFieldHint.value,
            className = _focusedFieldClass.value,
            textFieldFocused = _textFieldFocused.value,
            keyboardVisible = _keyboardVisible.value,
        )

    fun insertText(text: String): Boolean {
        val service = serviceInstance ?: run {
            Timber.w("[A11Y] insertText | service not active, cannot insert %d chars", text.length)
            return false
        }
        Timber.d("[A11Y] insertText | attempting to insert %d chars", text.length)
        return try {
            service.performTextInsertion(text)
        } catch (e: IllegalStateException) {
            Timber.w(e, "[A11Y] insertText | service destroyed mid-call, chars=%d", text.length)
            false
        }
    }

    /**
     * Replace the session-owned portion of the focused field.
     *
     * Replaces everything from [sessionStartOffset] to the end of the field with [newText],
     * preserving any text the user had typed before the recording session started.
     */
    fun replaceSessionText(sessionStartOffset: Int, newText: String): Boolean {
        val service = serviceInstance ?: run {
            Timber.w("[A11Y] replaceSessionText | service not active, cannot replace offset=%d len=%d", sessionStartOffset, newText.length)
            return false
        }
        Timber.d("[A11Y] replaceSessionText | offset=%d newLen=%d", sessionStartOffset, newText.length)
        return try {
            service.performSessionReplacement(sessionStartOffset, newText)
        } catch (e: IllegalStateException) {
            Timber.w(e, "[A11Y] replaceSessionText | service destroyed mid-call, offset=%d", sessionStartOffset)
            false
        }
    }

    /** Read the current text of the focused input field; null if service inactive or field is a password. */
    fun getCurrentFocusedFieldText(): String? = serviceInstance?.getCurrentFocusedFieldText()

    /**
     * Execute a voice action on the currently focused text field via the accessibility service.
     *
     * @return true if the action was dispatched successfully, false if the service is not active.
     */
    fun executeVoiceAction(action: com.safeword.android.transcription.VoiceAction): Boolean {
        val service = serviceInstance ?: run {
            Timber.w("[A11Y] executeVoiceAction | service not active, cannot execute %s", action)
            return false
        }
        return try {
            service.performVoiceAction(action)
        } catch (e: IllegalStateException) {
            Timber.w(e, "[A11Y] executeVoiceAction | service destroyed mid-call, action=%s", action)
            false
        }
    }
}
