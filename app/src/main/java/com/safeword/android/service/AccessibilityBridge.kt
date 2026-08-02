package com.safeword.android.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over [SafeWordAccessibilityService] statics for testability.
 *
 * All accessibility operations that other classes need (text insertion, voice
 * action dispatch, keyboard visibility, input context) are exposed through
 * this interface so callers can be unit-tested with a test double.
 */
interface AccessibilityBridge {

    /** Whether the accessibility service is currently connected and active. */
    fun isActive(): Boolean

    /** Insert [text] into the currently focused text field. Returns true on success. */
    fun insertText(text: String): Boolean

    /** Execute a [VoiceAction] via the accessibility service. Returns true on success. */
    fun executeVoiceAction(action: com.safeword.android.transcription.VoiceAction): Boolean

    /** Snapshot of the current input field context (package, hint, class, etc.). */
    fun inputContextSnapshot(): SafeWordAccessibilityService.InputContextSnapshot

    /** Observable keyboard visibility state. */
    val keyboardVisible: StateFlow<Boolean>

    /** Observable text-field-focused state. */
    val textFieldFocused: StateFlow<Boolean>
}
