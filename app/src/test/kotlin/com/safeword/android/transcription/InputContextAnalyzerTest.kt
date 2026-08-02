package com.safeword.android.transcription

import com.safeword.android.service.SafeWordAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Test

class InputContextAnalyzerTest {

    private fun snapshot(
        hintText: String = "",
        className: String = "",
        inputType: Int = 0,
    ) = SafeWordAccessibilityService.InputContextSnapshot(
        packageName = "com.example",
        hintText = hintText,
        className = className,
        inputType = inputType,
        textFieldFocused = true,
        keyboardVisible = true,
    )

    @Test
    fun `confidenceToLogprob returns 0 for boundary values`() {
        assertEquals(0f, InputContextAnalyzer.confidenceToLogprob(0f), 0.0001f)
        assertEquals(0f, InputContextAnalyzer.confidenceToLogprob(1f), 0.0001f)
        assertEquals(0f, InputContextAnalyzer.confidenceToLogprob(-0.5f), 0.0001f)
    }

    @Test
    fun `confidenceToLogprob maps 0_92 to approximately -0_08`() {
        val result = InputContextAnalyzer.confidenceToLogprob(0.92f)
        assertEquals(-0.08f, result, 0.01f)
    }

    @Test
    fun `confidenceToLogprob maps 0_5 to approximately -0_69`() {
        val result = InputContextAnalyzer.confidenceToLogprob(0.5f)
        assertEquals(-0.693f, result, 0.01f)
    }

    @Test
    fun `deriveFieldType returns PASSWORD for password inputType`() {
        // * NOTE: android.text.InputType constants return 0 in plain unit tests
        //   (no Robolectric). Use raw int values: TYPE_CLASS_TEXT=1,
        //   TYPE_TEXT_VARIATION_PASSWORD=128, TYPE_TEXT_VARIATION_URI=16.
        //   The source code's android.text.InputType.* also resolves to 0 in
        //   the test JVM, so inputType-based detection is not testable here.
        //   Test hint-based detection instead.
        val ctx = snapshot(hintText = "password")
        assertEquals(FieldType.PASSWORD, InputContextAnalyzer.deriveFieldType(ctx))
    }

    @Test
    fun `deriveFieldType returns PASSWORD for password hint`() {
        val ctx = snapshot(hintText = "Enter your password")
        assertEquals(FieldType.PASSWORD, InputContextAnalyzer.deriveFieldType(ctx))
    }

    @Test
    fun `deriveFieldType returns PASSWORD for pin hint`() {
        val ctx = snapshot(hintText = "Enter PIN")
        assertEquals(FieldType.PASSWORD, InputContextAnalyzer.deriveFieldType(ctx))
    }

    @Test
    fun `deriveFieldType returns SEARCH for search hint`() {
        val ctx = snapshot(hintText = "Search")
        assertEquals(FieldType.SEARCH, InputContextAnalyzer.deriveFieldType(ctx))
    }

    @Test
    fun `deriveFieldType returns MESSAGING for message hint`() {
        val ctx = snapshot(hintText = "Type a message")
        assertEquals(FieldType.MESSAGING, InputContextAnalyzer.deriveFieldType(ctx))
    }

    @Test
    fun `deriveFieldType returns MESSAGING for chat hint`() {
        val ctx = snapshot(hintText = "Chat with friends")
        assertEquals(FieldType.MESSAGING, InputContextAnalyzer.deriveFieldType(ctx))
    }

    @Test
    fun `deriveFieldType returns UNKNOWN for generic text`() {
        val ctx = snapshot(hintText = "Enter your name")
        assertEquals(FieldType.UNKNOWN, InputContextAnalyzer.deriveFieldType(ctx))
    }

    @Test
    fun `buildCorrectorContext uses 0 avgLogprob when confidences empty`() {
        val ctx = snapshot(hintText = "test")
        val result = InputContextAnalyzer.buildCorrectorContext(ctx)
        assertEquals(0f, result.avgLogprob, 0.0001f)
    }

    @Test
    fun `buildCorrectorContext derives avgLogprob from confidences`() {
        val ctx = snapshot(hintText = "test")
        val result = InputContextAnalyzer.buildCorrectorContext(ctx, listOf(0.5f, 0.5f))
        assertEquals(-0.693f, result.avgLogprob, 0.01f)
    }

    @Test
    fun `buildCorrectorContext propagates packageName and hintText`() {
        val ctx = snapshot(hintText = "Email", className = "EditText")
        val result = InputContextAnalyzer.buildCorrectorContext(ctx)
        assertEquals("com.example", result.packageName)
        assertEquals("Email", result.hintText)
        assertEquals("EditText", result.className)
    }
}
