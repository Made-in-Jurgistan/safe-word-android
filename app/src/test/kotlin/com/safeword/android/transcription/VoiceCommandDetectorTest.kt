package com.safeword.android.transcription

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoiceCommandDetectorTest {

    // -------------------------------------------------------------------------
    // Empty / blank → Text("")
    // -------------------------------------------------------------------------

    @Test
    fun `empty string returns Text with empty string`() {
        val result = VoiceCommandDetector.detect("")
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals("", result.rawText)
    }

    @Test
    fun `whitespace-only returns Text with empty string`() {
        val result = VoiceCommandDetector.detect("   ")
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals("", result.rawText)
    }

    // -------------------------------------------------------------------------
    // Deletion commands
    // -------------------------------------------------------------------------

    @Test
    fun `delete that returns DeleteSelection`() {
        val result = VoiceCommandDetector.detect("delete that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteSelection, result.action)
    }

    @Test
    fun `delete last word returns DeleteLastWord`() {
        val result = VoiceCommandDetector.detect("delete last word")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteLastWord, result.action)
    }

    @Test
    fun `delete last sentence returns DeleteLastSentence`() {
        val result = VoiceCommandDetector.detect("Delete last sentence")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteLastSentence, result.action)
    }

    @Test
    fun `backspace returns Backspace`() {
        val result = VoiceCommandDetector.detect("backspace")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Backspace, result.action)
    }

    // -------------------------------------------------------------------------
    // Undo / redo
    // -------------------------------------------------------------------------

    @Test
    fun `undo returns Undo`() {
        val result = VoiceCommandDetector.detect("undo")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `undo that returns Undo`() {
        val result = VoiceCommandDetector.detect("undo that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `redo returns Redo`() {
        val result = VoiceCommandDetector.detect("redo")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Redo, result.action)
    }

    // -------------------------------------------------------------------------
    // Selection & clipboard
    // -------------------------------------------------------------------------

    @Test
    fun `select all returns SelectAll`() {
        val result = VoiceCommandDetector.detect("select all")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.SelectAll, result.action)
    }

    @Test
    fun `select last word returns SelectLastWord`() {
        val result = VoiceCommandDetector.detect("select last word")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.SelectLastWord, result.action)
    }

    @Test
    fun `copy that returns Copy`() {
        val result = VoiceCommandDetector.detect("copy that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Copy, result.action)
    }

    @Test
    fun `cut returns Cut`() {
        val result = VoiceCommandDetector.detect("cut")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Cut, result.action)
    }

    @Test
    fun `paste that returns Paste`() {
        val result = VoiceCommandDetector.detect("paste that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Paste, result.action)
    }

    // -------------------------------------------------------------------------
    // Navigation & formatting
    // -------------------------------------------------------------------------

    @Test
    fun `new line returns NewLine`() {
        val result = VoiceCommandDetector.detect("new line")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.NewLine, result.action)
    }

    @Test
    fun `press enter returns NewLine`() {
        val result = VoiceCommandDetector.detect("press enter")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.NewLine, result.action)
    }

    @Test
    fun `new paragraph returns NewParagraph`() {
        val result = VoiceCommandDetector.detect("new paragraph")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.NewParagraph, result.action)
    }

    @Test
    fun `capitalize that returns CapitalizeSelection`() {
        val result = VoiceCommandDetector.detect("capitalize that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.CapitalizeSelection, result.action)
    }

    @Test
    fun `uppercase that returns UppercaseSelection`() {
        val result = VoiceCommandDetector.detect("uppercase that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.UppercaseSelection, result.action)
    }

    @Test
    fun `lowercase that returns LowercaseSelection`() {
        val result = VoiceCommandDetector.detect("Lowercase that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.LowercaseSelection, result.action)
    }

    // -------------------------------------------------------------------------
    // InsertText commands
    // -------------------------------------------------------------------------

    @Test
    fun `space returns InsertText with space`() {
        val result = VoiceCommandDetector.detect("space")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(" "), result.action)
    }

    @Test
    fun `period returns InsertText with dot`() {
        val result = VoiceCommandDetector.detect("period")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(". "), result.action)
    }

    @Test
    fun `question mark returns InsertText with question mark`() {
        val result = VoiceCommandDetector.detect("question mark")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText("?"), result.action)
    }

    @Test
    fun `exclamation point returns InsertText with exclamation`() {
        val result = VoiceCommandDetector.detect("exclamation point")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText("!"), result.action)
    }

    @Test
    fun `full stop returns InsertText with dot`() {
        val result = VoiceCommandDetector.detect("full stop")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(". "), result.action)
    }

    // -------------------------------------------------------------------------
    // Case insensitivity
    // -------------------------------------------------------------------------

    @Test
    fun `command is case insensitive`() {
        val result = VoiceCommandDetector.detect("DELETE THAT")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteSelection, result.action)
    }

    @Test
    fun `mixed case command detected`() {
        val result = VoiceCommandDetector.detect("Undo That")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    // -------------------------------------------------------------------------
    // Trailing punctuation from Whisper
    // -------------------------------------------------------------------------

    @Test
    fun `command with trailing period detected`() {
        val result = VoiceCommandDetector.detect("delete that.")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteSelection, result.action)
    }

    @Test
    fun `command with trailing exclamation detected`() {
        val result = VoiceCommandDetector.detect("undo!")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `command with trailing question mark detected`() {
        val result = VoiceCommandDetector.detect("paste?")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Paste, result.action)
    }

    @Test
    fun `command with trailing whitespace detected`() {
        val result = VoiceCommandDetector.detect("  undo that  ")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `polite prefix is accepted`() {
        val result = VoiceCommandDetector.detect("please undo")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `polite suffix is accepted`() {
        val result = VoiceCommandDetector.detect("undo please")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `wake word wrapper is accepted`() {
        val result = VoiceCommandDetector.detect("safe word, delete that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteSelection, result.action)
    }

    @Test
    fun `command mode wrapper is accepted`() {
        val result = VoiceCommandDetector.detect("command mode undo")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    // -------------------------------------------------------------------------
    // Full-utterance matching — reject commands embedded in sentences
    // -------------------------------------------------------------------------

    @Test
    fun `command embedded in sentence returns Text`() {
        val input = "please delete that word now"
        val result = VoiceCommandDetector.detect(input)
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals(input, result.rawText)
    }

    @Test
    fun `normal dictation text returns Text`() {
        val input = "I would like a cup of coffee"
        val result = VoiceCommandDetector.detect(input)
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals(input, result.rawText)
    }

    @Test
    fun `command prefix only in longer text returns Text`() {
        val input = "undo the last three changes I made"
        val result = VoiceCommandDetector.detect(input)
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals(input, result.rawText)
    }

    @Test
    fun `partial match does not trigger command`() {
        val input = "paste the text here"
        val result = VoiceCommandDetector.detect(input)
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals(input, result.rawText)
    }

    @Test
    fun `polite sentence with extra words does not trigger command`() {
        val input = "please undo the last thing I typed"
        val result = VoiceCommandDetector.detect(input)
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals(input, result.rawText)
    }

    // -------------------------------------------------------------------------
    // New trigger phrases — deletion
    // -------------------------------------------------------------------------

    @Test
    fun `erase that returns DeleteSelection`() {
        val result = VoiceCommandDetector.detect("erase that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteSelection, result.action)
    }

    @Test
    fun `remove that returns DeleteSelection`() {
        val result = VoiceCommandDetector.detect("remove that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteSelection, result.action)
    }

    @Test
    fun `erase last word returns DeleteLastWord`() {
        val result = VoiceCommandDetector.detect("erase last word")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteLastWord, result.action)
    }

    @Test
    fun `erase last sentence returns DeleteLastSentence`() {
        val result = VoiceCommandDetector.detect("erase last sentence")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.DeleteLastSentence, result.action)
    }

    // -------------------------------------------------------------------------
    // New trigger phrases — undo / redo
    // -------------------------------------------------------------------------

    @Test
    fun `scratch that returns Undo`() {
        val result = VoiceCommandDetector.detect("scratch that")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `take that back returns Undo`() {
        val result = VoiceCommandDetector.detect("Take that back")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)
    }

    // -------------------------------------------------------------------------
    // New trigger phrases — selection & clipboard
    // -------------------------------------------------------------------------

    @Test
    fun `highlight all returns SelectAll`() {
        val result = VoiceCommandDetector.detect("highlight all")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.SelectAll, result.action)
    }

    @Test
    fun `copy this returns Copy`() {
        val result = VoiceCommandDetector.detect("copy this")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Copy, result.action)
    }

    @Test
    fun `cut this returns Cut`() {
        val result = VoiceCommandDetector.detect("cut this")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Cut, result.action)
    }

    @Test
    fun `paste here returns Paste`() {
        val result = VoiceCommandDetector.detect("paste here")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Paste, result.action)
    }

    // -------------------------------------------------------------------------
    // New trigger phrases — navigation
    // -------------------------------------------------------------------------

    @Test
    fun `go to next line returns NewLine`() {
        val result = VoiceCommandDetector.detect("go to next line")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.NewLine, result.action)
    }

    // -------------------------------------------------------------------------
    // New trigger phrases — punctuation
    // -------------------------------------------------------------------------

    @Test
    fun `add a space returns InsertText with space`() {
        val result = VoiceCommandDetector.detect("add a space")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(" "), result.action)
    }

    @Test
    fun `type a space returns InsertText with space`() {
        val result = VoiceCommandDetector.detect("type a space")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(" "), result.action)
    }

    @Test
    fun `dot returns InsertText with period`() {
        val result = VoiceCommandDetector.detect("dot")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(". "), result.action)
    }

    @Test
    fun `add a comma returns InsertText with comma`() {
        val result = VoiceCommandDetector.detect("add a comma")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(","), result.action)
    }

    @Test
    fun `colon returns InsertText with colon`() {
        val result = VoiceCommandDetector.detect("colon")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(":"), result.action)
    }

    @Test
    fun `semicolon returns InsertText with semicolon`() {
        val result = VoiceCommandDetector.detect("semicolon")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(";"), result.action)
    }

    @Test
    fun `hyphen returns InsertText with hyphen`() {
        val result = VoiceCommandDetector.detect("hyphen")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText("-"), result.action)
    }

    @Test
    fun `dash returns InsertText with em dash`() {
        val result = VoiceCommandDetector.detect("dash")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText(" — "), result.action)
    }

    // -------------------------------------------------------------------------
    // New trigger phrases — session control
    // -------------------------------------------------------------------------

    @Test
    fun `send it returns Send`() {
        val result = VoiceCommandDetector.detect("send it")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Send, result.action)
    }

    @Test
    fun `clear everything returns ClearAll`() {
        val result = VoiceCommandDetector.detect("clear everything")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.ClearAll, result.action)
    }

    @Test
    fun `erase all returns ClearAll`() {
        val result = VoiceCommandDetector.detect("erase all")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.ClearAll, result.action)
    }

    @Test
    fun `erase everything returns ClearAll`() {
        val result = VoiceCommandDetector.detect("erase everything")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.ClearAll, result.action)
    }

    @Test
    fun `stop dictation returns StopListening`() {
        val result = VoiceCommandDetector.detect("stop dictation")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.StopListening, result.action)
    }

    @Test
    fun `done returns StopListening`() {
        val result = VoiceCommandDetector.detect("done")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.StopListening, result.action)
    }

    // -------------------------------------------------------------------------
    // Fast-path length check — long text skips detection
    // -------------------------------------------------------------------------

    @Test
    fun `very long text skips detection and returns Text`() {
        val longInput = "a".repeat(100)
        val result = VoiceCommandDetector.detect(longInput)
        assertIs<VoiceCommandResult.Text>(result)
        assertEquals(longInput, result.rawText)
    }

    // -------------------------------------------------------------------------
    // Parameterized commands — ReplaceText (English)
    // -------------------------------------------------------------------------

    @Test
    fun `replace hello with world returns ReplaceText`() {
        val result = VoiceCommandDetector.detect("replace hello with world")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.ReplaceText("hello", "world"), result.action)
    }

    @Test
    fun `change hello to world returns ReplaceText`() {
        val result = VoiceCommandDetector.detect("change hello to world")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.ReplaceText("hello", "world"), result.action)
    }

    @Test
    fun `swap hello with world returns ReplaceText`() {
        val result = VoiceCommandDetector.detect("swap hello with world")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.ReplaceText("hello", "world"), result.action)
    }

    @Test
    fun `polite replace hello with world returns ReplaceText`() {
        val result = VoiceCommandDetector.detect("please replace hello with world please")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.ReplaceText("hello", "world"), result.action)
    }

    // -------------------------------------------------------------------------
    // Parameterized commands — SearchText (English)
    // -------------------------------------------------------------------------

    @Test
    fun `search for cats returns SearchText`() {
        val result = VoiceCommandDetector.detect("search for cats")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.SearchText("cats"), result.action)
    }

    @Test
    fun `look up android tips returns SearchText`() {
        val result = VoiceCommandDetector.detect("look up android tips")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.SearchText("android tips"), result.action)
    }

    @Test
    fun `google android tips returns SearchText`() {
        val result = VoiceCommandDetector.detect("google android tips")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.SearchText("android tips"), result.action)
    }

    @Test
    fun `utterance longer than 150 chars is not a command`() {
        val longText = "a".repeat(151)
        val result = VoiceCommandDetector.detect(longText)
        assertIs<VoiceCommandResult.Text>(result)
    }
}
