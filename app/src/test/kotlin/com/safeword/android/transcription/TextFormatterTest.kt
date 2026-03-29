package com.safeword.android.transcription

import org.junit.Test
import kotlin.test.assertEquals

class TextFormatterTest {

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `empty string returns empty`() {
        assertEquals("", TextFormatter.format(""))
    }

    @Test
    fun `whitespace-only returns empty`() {
        assertEquals("", TextFormatter.format("   "))
    }

    // -------------------------------------------------------------------------
    // Multi-space collapse
    // -------------------------------------------------------------------------

    @Test
    fun `multiple spaces collapsed to single`() {
        assertEquals("Hello world. ", TextFormatter.format("hello  world"))
    }

    @Test
    fun `three spaces collapsed`() {
        assertEquals("A b c. ", TextFormatter.format("a   b   c"))
    }

    // -------------------------------------------------------------------------
    // Trim
    // -------------------------------------------------------------------------

    @Test
    fun `leading and trailing whitespace trimmed`() {
        assertEquals("Hello. ", TextFormatter.format("  hello  "))
    }

    // -------------------------------------------------------------------------
    // Sentence case capitalization
    // -------------------------------------------------------------------------

    @Test
    fun `first letter capitalized`() {
        assertEquals("Hello. ", TextFormatter.format("hello"))
    }

    @Test
    fun `letter after period capitalized`() {
        assertEquals("One. Two. ", TextFormatter.format("one. two"))
    }

    @Test
    fun `space inserted after period when missing`() {
        assertEquals("One. Two. ", TextFormatter.format("one.two"))
    }

    @Test
    fun `letter after exclamation capitalized`() {
        assertEquals("Wow! Great. ", TextFormatter.format("wow! great"))
    }

    @Test
    fun `letter after question mark capitalized`() {
        assertEquals("Why? Because. ", TextFormatter.format("why? because"))
    }

    @Test
    fun `letter after newline capitalized`() {
        assertEquals("Line one.\nLine two. ", TextFormatter.format("line one.\nline two"))
    }

    @Test
    fun `already capitalized stays unchanged`() {
        assertEquals("Hello World. ", TextFormatter.format("Hello World"))
    }

    // -------------------------------------------------------------------------
    // Pronoun I fix
    // -------------------------------------------------------------------------

    @Test
    fun `lowercase i after space becomes I`() {
        assertEquals("Then I went. ", TextFormatter.format("then i went"))
    }

    @Test
    fun `lowercase i at start becomes I`() {
        assertEquals("I think so. ", TextFormatter.format("i think so"))
    }

    @Test
    fun `i inside word is not capitalized`() {
        assertEquals("This is nice. ", TextFormatter.format("this is nice"))
    }

    @Test
    fun `i before comma becomes I`() {
        assertEquals("I, too, agree. ", TextFormatter.format("i, too, agree"))
    }

    // -------------------------------------------------------------------------
    // Trailing punctuation enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `period appended if none`() {
        assertEquals("Hello. ", TextFormatter.format("hello"))
    }

    @Test
    fun `existing period not duplicated`() {
        assertEquals("Hello. ", TextFormatter.format("hello."))
    }

    @Test
    fun `existing exclamation not duplicated`() {
        assertEquals("Wow! ", TextFormatter.format("wow!"))
    }

    @Test
    fun `existing question mark not duplicated`() {
        assertEquals("Really? ", TextFormatter.format("really?"))
    }

    @Test
    fun `existing ellipsis not duplicated`() {
        assertEquals("Hmm\u2026 ", TextFormatter.format("hmm\u2026"))
    }

    // -------------------------------------------------------------------------
    // Full pipeline integration
    // -------------------------------------------------------------------------

    @Test
    fun `full pipeline with all steps`() {
        val input = "  so  i  went  there  "
        val result = TextFormatter.format(input)
        assertEquals("So I went there. ", result)
    }

    @Test
    fun `multi-sentence formatting`() {
        val input = "one. two. three"
        val result = TextFormatter.format(input)
        assertEquals("One. Two. Three. ", result)
    }

    @Test
    fun `space inserted after question and exclamation when missing`() {
        val input = "what?yes!ok"
        val result = TextFormatter.format(input)
        assertEquals("What? Yes! Ok. ", result)
    }
}
