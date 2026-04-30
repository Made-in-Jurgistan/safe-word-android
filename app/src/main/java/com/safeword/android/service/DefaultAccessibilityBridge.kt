package com.safeword.android.service

import com.safeword.android.transcription.VoiceAction
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [AccessibilityBridge] that delegates to [SafeWordAccessibilityService] statics.
 */
@Singleton
class DefaultAccessibilityBridge @Inject constructor() : AccessibilityBridge {

    override fun isActive(): Boolean =
        SafeWordAccessibilityService.isActive()

    override fun insertText(text: String): Boolean =
        SafeWordAccessibilityService.insertText(text)

    override fun executeVoiceAction(action: VoiceAction): Boolean =
        SafeWordAccessibilityService.executeVoiceAction(action)

    override fun inputContextSnapshot(): SafeWordAccessibilityService.InputContextSnapshot =
        SafeWordAccessibilityService.inputContextSnapshot()

    override val keyboardVisible: StateFlow<Boolean>
        get() = SafeWordAccessibilityService.keyboardVisible

    override val textFieldFocused: StateFlow<Boolean>
        get() = SafeWordAccessibilityService.textFieldFocused
}
