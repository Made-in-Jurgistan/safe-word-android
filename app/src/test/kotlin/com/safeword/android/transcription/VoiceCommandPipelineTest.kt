package com.safeword.android.transcription

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Voice command pipeline tests: raw ASR → VoiceCommandDetector → VoiceAction routing.
 *
 * Tests the full detection pipeline including canonicalization (wake-word stripping,
 * polite prefix/suffix removal), exact match, compositional match, trailing command
 * detection, and rejection of embedded commands in dictation.
 */
class VoiceCommandPipelineTest {

    private lateinit var detector: VoiceCommandDetector

    @Before
    fun setUp() {
        detector = VoiceCommandDetector()
    }

    // ════════════════════════════════════════════════════════════════════
    //  1. Deletion commands
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `delete last word detected`() {
        assertCommand<VoiceAction.DeleteLastWord>("delete last word")
    }

    @Test
    fun `delete last sentence detected`() {
        assertCommand<VoiceAction.DeleteLastSentence>("delete last sentence")
    }

    @Test
    fun `backspace detected`() {
        assertCommand<VoiceAction.Backspace>("backspace")
    }

    @Test
    fun `clear all detected`() {
        assertCommand<VoiceAction.ClearAll>("clear all")
    }

    @Test
    fun `delete single word command detected`() {
        assertCommand<VoiceAction.DeleteLastWord>("delete")
    }

    @Test
    fun `clear single word command detected`() {
        assertCommand<VoiceAction.ClearAll>("clear")
    }

    @Test
    fun `select single word command detected`() {
        assertCommand<VoiceAction.SelectLastWord>("select")
    }

    @Test
    fun `highlight single word command detected`() {
        assertCommand<VoiceAction.SelectLastWord>("highlight")
    }

    @Test
    fun `delete last N words with valid count`() {
        val result = detector.detect("delete last 3 words")
        assertIs<VoiceCommandResult.Command>(result)
        val action = result.action
        assertIs<VoiceAction.DeleteLastNWords>(action)
        assertEquals(3, action.count)
    }

    @Test
    fun `delete last 1 word`() {
        val result = detector.detect("delete last 1 word")
        assertIs<VoiceCommandResult.Command>(result)
        val action = result.action
        assertIs<VoiceAction.DeleteLastNWords>(action)
        assertEquals(1, action.count)
    }

    @Test
    fun `erase last word alias works`() {
        assertCommand<VoiceAction.DeleteLastWord>("erase last word")
    }

    @Test
    fun `delete selection detected`() {
        assertCommand<VoiceAction.DeleteSelection>("delete selection")
    }

    // ════════════════════════════════════════════════════════════════════
    //  2. Undo and redo
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `undo detected`() {
        assertCommand<VoiceAction.Undo>("undo")
    }

    @Test
    fun `redo detected`() {
        assertCommand<VoiceAction.Redo>("redo")
    }

    @Test
    fun `undo that detected`() {
        assertCommand<VoiceAction.Undo>("undo that")
    }

    // ════════════════════════════════════════════════════════════════════
    //  3. Selection and clipboard
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `select all detected`() {
        assertCommand<VoiceAction.SelectAll>("select all")
    }

    @Test
    fun `select everything detected`() {
        assertCommand<VoiceAction.SelectAll>("select everything")
    }

    @Test
    fun `select last word detected`() {
        assertCommand<VoiceAction.SelectLastWord>("select last word")
    }

    @Test
    fun `copy detected`() {
        assertCommand<VoiceAction.Copy>("copy")
    }

    @Test
    fun `copy that detected`() {
        assertCommand<VoiceAction.Copy>("copy that")
    }

    @Test
    fun `cut detected`() {
        assertCommand<VoiceAction.Cut>("cut")
    }

    @Test
    fun `paste detected`() {
        assertCommand<VoiceAction.Paste>("paste")
    }

    @Test
    fun `paste that detected`() {
        assertCommand<VoiceAction.Paste>("paste that")
    }

    // ════════════════════════════════════════════════════════════════════
    //  4. Navigation and formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `new line detected`() {
        assertCommand<VoiceAction.NewLine>("new line")
    }

    @Test
    fun `new paragraph detected`() {
        assertCommand<VoiceAction.NewParagraph>("new paragraph")
    }

    @Test
    fun `capitalize last word detected`() {
        assertCommand<VoiceAction.CapitalizeLastWord>("capitalize last word")
    }

    @Test
    fun `uppercase last word detected`() {
        assertCommand<VoiceAction.UppercaseLastWord>("uppercase last word")
    }

    @Test
    fun `lowercase last word detected`() {
        assertCommand<VoiceAction.LowercaseLastWord>("lowercase last word")
    }

    // ════════════════════════════════════════════════════════════════════
    //  5. Rich text formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `bold detected`() {
        assertCommand<VoiceAction.Bold>("bold")
    }

    @Test
    fun `italic detected`() {
        assertCommand<VoiceAction.Italic>("italic")
    }

    @Test
    fun `underline detected`() {
        assertCommand<VoiceAction.Underline>("underline")
    }

    // ════════════════════════════════════════════════════════════════════
    //  6. Session control
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `stop listening detected`() {
        assertCommand<VoiceAction.StopListening>("stop listening")
    }

    @Test
    fun `stop dictation detected`() {
        assertCommand<VoiceAction.StopListening>("stop dictation")
    }

    @Test
    fun `send detected`() {
        assertCommand<VoiceAction.Send>("send")
    }

    @Test
    fun `send message detected`() {
        assertCommand<VoiceAction.Send>("send message")
    }

    // ════════════════════════════════════════════════════════════════════
    //  7. Parameterized commands
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `replace X with Y detected`() {
        val result = detector.detect("replace hello with goodbye")
        assertIs<VoiceCommandResult.Command>(result)
        val action = result.action
        assertIs<VoiceAction.ReplaceText>(action)
        assertEquals("hello", action.oldText)
        assertEquals("goodbye", action.newText)
    }

    @Test
    fun `change X to Y detected`() {
        val result = detector.detect("change cat to dog")
        assertIs<VoiceCommandResult.Command>(result)
        val action = result.action
        assertIs<VoiceAction.ReplaceText>(action)
        assertEquals("cat", action.oldText)
        assertEquals("dog", action.newText)
    }

    @Test
    fun `search for X detected`() {
        val result = detector.detect("search for weather today")
        assertIs<VoiceCommandResult.Command>(result)
        val action = result.action
        assertIs<VoiceAction.SearchFor>(action)
        assertEquals("weather today", action.query)
    }

    @Test
    fun `select specific text detected`() {
        val result = detector.detect("select important")
        assertIs<VoiceCommandResult.Command>(result)
        val action = result.action
        assertIs<VoiceAction.SelectText>(action)
        assertEquals("important", action.query)
    }

    // ════════════════════════════════════════════════════════════════════
    //  8. Canonicalization — wake word, polite prefix/suffix
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `wake word prefix stripped`() {
        assertCommand<VoiceAction.Undo>("safe word undo")
    }

    @Test
    fun `ok safe word prefix stripped`() {
        assertCommand<VoiceAction.Undo>("ok safe word undo")
    }

    @Test
    fun `polite please prefix stripped`() {
        assertCommand<VoiceAction.DeleteLastWord>("please delete last word")
    }

    @Test
    fun `polite please suffix stripped`() {
        assertCommand<VoiceAction.DeleteLastWord>("delete last word please")
    }

    @Test
    fun `polite thank you suffix stripped`() {
        assertCommand<VoiceAction.Undo>("undo thank you")
    }

    @Test
    fun `can you prefix stripped`() {
        assertCommand<VoiceAction.SelectAll>("can you select all")
    }

    @Test
    fun `command mode prefix stripped`() {
        assertCommand<VoiceAction.Copy>("command copy")
    }

    @Test
    fun `case insensitive matching`() {
        assertCommand<VoiceAction.Undo>("UNDO")
        assertCommand<VoiceAction.DeleteLastWord>("Delete Last Word")
    }

    @Test
    fun `trailing punctuation stripped`() {
        assertCommand<VoiceAction.Undo>("undo.")
        assertCommand<VoiceAction.DeleteLastWord>("delete last word!")
        assertCommand<VoiceAction.Copy>("copy?")
    }

    // ════════════════════════════════════════════════════════════════════
    //  9. Trailing command detection
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `dictation followed by trailing command`() {
        val result = detector.detectIncludingTrailing("so I was saying delete last sentence")
        assertIs<VoiceCommandResult.TrailingCommand>(result)
        assertIs<VoiceAction.DeleteLastSentence>(result.action)
        assertTrue(result.prefix.isNotBlank(), "Prefix should contain dictation text")
    }

    @Test
    fun `dictation followed by multi-word trailing command`() {
        val result = detector.detectIncludingTrailing("this is text delete last word")
        assertIs<VoiceCommandResult.TrailingCommand>(result)
        assertIs<VoiceAction.DeleteLastWord>(result.action)
    }

    @Test
    fun `long dictation tail still detects trailing command`() {
        val prefix = "lorem ipsum dolor sit amet consectetur adipiscing elit ".repeat(4).trim()
        val input = "$prefix delete last sentence"
        val result = detector.detectIncludingTrailing(input)
        assertIs<VoiceCommandResult.TrailingCommand>(result)
        assertIs<VoiceAction.DeleteLastSentence>(result.action)
    }

    @Test
    fun `standalone command via detectIncludingTrailing`() {
        val result = detector.detectIncludingTrailing("undo")
        assertIs<VoiceCommandResult.Command>(result)
        assertIs<VoiceAction.Undo>(result.action)
    }

    // ════════════════════════════════════════════════════════════════════
    // 10. Dictation pass-through — commands NOT detected
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normal dictation returns Text`() {
        val result = detector.detect("the weather is nice today")
        assertIs<VoiceCommandResult.Text>(result)
    }

    @Test
    fun `command word embedded in sentence returns Text`() {
        // "delete" appears but isn't the full utterance → should not trigger
        val result = detector.detect("please do not delete the file on your computer")
        assertIs<VoiceCommandResult.Text>(result)
    }

    @Test
    fun `undo embedded in sentence returns Text`() {
        val result = detector.detect("I need to undo my entire weekend plan")
        assertIs<VoiceCommandResult.Text>(result)
    }

    @Test
    fun `empty input returns Text`() {
        val result = detector.detect("")
        assertIs<VoiceCommandResult.Text>(result)
    }

    @Test
    fun `very long input returns Text without processing`() {
        val longText = "a".repeat(300)
        val result = detector.detect(longText)
        assertIs<VoiceCommandResult.Text>(result)
    }

    @Test
    fun `copy embedded in natural speech returns Text`() {
        val result = detector.detect("I need a copy of the document")
        assertIs<VoiceCommandResult.Text>(result)
    }

    // ════════════════════════════════════════════════════════════════════
    // 12. Non-command text still passes through (no false positives)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normal speech with command-like words is not falsely matched`() {
        val result = detector.detect("the weather highlights of the week")
        assertIs<VoiceCommandResult.Text>(result)
    }

    @Test
    fun `deleted in past tense narrative is not a command`() {
        val result = detector.detect("he deleted the files from the server yesterday")
        assertIs<VoiceCommandResult.Text>(result)
    }

    // ════════════════════════════════════════════════════════════════════
    // Helper
    // ════════════════════════════════════════════════════════════════════

    private inline fun <reified T : VoiceAction> assertCommand(input: String) {
        val result = detector.detect(input)
        assertIs<VoiceCommandResult.Command>(
            result,
            "Expected Command for '$input' but got ${result::class.simpleName}",
        )
        assertIs<T>(
            result.action,
            "Expected ${T::class.simpleName} for '$input' but got ${result.action::class.simpleName}",
        )
    }
}
