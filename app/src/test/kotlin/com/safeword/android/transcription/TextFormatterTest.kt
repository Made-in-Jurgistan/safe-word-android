package com.safeword.android.transcription

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [TextFormatter] — whitespace normalization.
 *
 * Covers multi-space collapse, trim, inter-sentence spacing, trailing space,
 * and edge cases. Sentence-case and pronoun-I have been removed.
 */
class TextFormatterTest {

    // ════════════════════════════════════════════════════════════════════
    //  No sentence-case capitalization (passthrough)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format does not capitalize first letter`() {
        assertEquals("hello ", TextFormatter.format("hello"))
    }

    @Test
    fun `format does not capitalize after period`() {
        assertEquals("hello. world ", TextFormatter.format("hello. world"))
    }

    @Test
    fun `format does not capitalize after exclamation mark`() {
        assertEquals("wow! great ", TextFormatter.format("wow! great"))
    }

    @Test
    fun `format does not capitalize after question mark`() {
        assertEquals("really? yes ", TextFormatter.format("really? yes"))
    }

    @Test
    fun `format does not capitalize after ellipsis`() {
        assertEquals("well\u2026 okay ", TextFormatter.format("well\u2026 okay"))
    }

    @Test
    fun `format does not capitalize after newline`() {
        assertEquals("hello.\nworld ", TextFormatter.format("hello.\nworld"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  No pronoun "I" fix (passthrough)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format does not capitalize pronoun I`() {
        assertEquals("i think i should go ", TextFormatter.format("i think i should go"))
    }

    @Test
    fun `format does not alter I inside word`() {
        assertEquals("the item is nice ", TextFormatter.format("the item is nice"))
    }

    @Test
    fun `format does not capitalize I before punctuation`() {
        assertEquals("did i? ", TextFormatter.format("did i?"))
    }

    @Test
    fun `format does not capitalize I at end of sentence`() {
        assertEquals("it is i ", TextFormatter.format("it is i"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  No trailing punctuation enforcement (passthrough)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format does not add period when no terminal punctuation`() {
        assertEquals("hello world ", TextFormatter.format("hello world"))
    }

    @Test
    fun `format preserves existing period`() {
        assertEquals("hello world. ", TextFormatter.format("hello world."))
    }

    @Test
    fun `format preserves existing question mark`() {
        assertEquals("are you sure? ", TextFormatter.format("are you sure?"))
    }

    @Test
    fun `format preserves existing exclamation`() {
        assertEquals("watch out! ", TextFormatter.format("watch out!"))
    }

    @Test
    fun `format preserves existing ellipsis`() {
        assertEquals("well\u2026 ", TextFormatter.format("well\u2026"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Trailing space
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format appends trailing space`() {
        val result = TextFormatter.format("hello")
        assertEquals(' ', result.last())
    }

    // ════════════════════════════════════════════════════════════════════
    //  Whitespace normalization
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format collapses multiple spaces`() {
        assertEquals("hello world ", TextFormatter.format("hello   world"))
    }

    @Test
    fun `format collapses tabs`() {
        assertEquals("hello world ", TextFormatter.format("hello\t\tworld"))
    }

    @Test
    fun `format trims leading and trailing whitespace`() {
        assertEquals("hello ", TextFormatter.format("  hello  "))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Inter-sentence spacing
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format adds space after sentence punctuation when missing`() {
        assertEquals("hello. world ", TextFormatter.format("hello.world"))
    }

    @Test
    fun `format preserves existing space after punctuation`() {
        assertEquals("hello. world ", TextFormatter.format("hello. world"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Multi-sentence
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format handles multi-sentence text`() {
        assertEquals(
            "the weather is nice. i will go outside ",
            TextFormatter.format("the weather is nice. i will go outside"),
        )
    }

    @Test
    fun `format handles mixed punctuation sentence chain`() {
        assertEquals(
            "is it? yes! definitely ",
            TextFormatter.format("is it? yes! definitely"),
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `format returns empty for blank input`() {
        assertEquals("", TextFormatter.format(""))
    }

    @Test
    fun `format returns empty for whitespace-only input`() {
        assertEquals("", TextFormatter.format("   "))
    }

    @Test
    fun `format handles single character`() {
        assertEquals("a ", TextFormatter.format("a"))
    }

    @Test
    fun `format handles already properly formatted text`() {
        assertEquals("Hello world. ", TextFormatter.format("Hello world."))
    }
}
