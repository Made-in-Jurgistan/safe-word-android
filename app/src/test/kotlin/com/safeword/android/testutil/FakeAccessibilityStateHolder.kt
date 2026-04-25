package com.safeword.android.testutil

import com.safeword.android.service.AccessibilityStateHolder
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.transcription.VoiceAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

/**
 * Default [InputContextSnapshot] used across pipeline tests.
 */
val DEFAULT_INPUT_CONTEXT = InputContextSnapshot(
    packageName = "com.test.app",
    hintText = "Type here",
    className = "android.widget.EditText",
    textFieldFocused = true,
    keyboardVisible = false,
)

/**
 * Creates a [mockk] of [AccessibilityStateHolder] that captures all inserted text and voice
 * actions for later assertion.
 *
 * @return a triple of (mock, insertedTexts list, executedActions list).
 */
fun createMockAccessibilityState(
    snapshot: InputContextSnapshot = DEFAULT_INPUT_CONTEXT,
    insertAlwaysSucceeds: Boolean = true,
    actionAlwaysSucceeds: Boolean = true,
): Triple<AccessibilityStateHolder, MutableList<String>, MutableList<VoiceAction>> {
    val insertedTexts = mutableListOf<String>()
    val executedActions = mutableListOf<VoiceAction>()
    val textSlot = slot<String>()
    val actionSlot = slot<VoiceAction>()

    val mock = mockk<AccessibilityStateHolder>(relaxed = true) {
        every { isActive() } returns true
        every { inputContextSnapshot() } returns snapshot
        every { insertText(capture(textSlot)) } answers {
            if (insertAlwaysSucceeds) {
                insertedTexts.add(textSlot.captured)
                true
            } else {
                false
            }
        }
        every { executeVoiceAction(capture(actionSlot)) } answers {
            if (actionAlwaysSucceeds) {
                executedActions.add(actionSlot.captured)
                true
            } else {
                false
            }
        }
    }

    return Triple(mock, insertedTexts, executedActions)
}
