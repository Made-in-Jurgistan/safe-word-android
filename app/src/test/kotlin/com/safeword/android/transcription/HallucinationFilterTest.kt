package com.safeword.android.transcription

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [HallucinationFilter] — cross-segment repeat detection
 * and triple-repeat deduplication.
 *
 * Phrase-based hallucination filtering has been removed (filter() is passthrough).
 */
class HallucinationFilterTest {

    @Before
    fun setUp() {
        HallucinationFilter.resetSession()
    }

    // ════════════════════════════════════════════════════════════════════
    //  filter() — passthrough (phrase removal has been removed)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `filter passes through normal text`() {
        assertEquals("I need to buy groceries today", HallucinationFilter.filter("I need to buy groceries today"))
    }

    @Test
    fun `filter passes through text unchanged`() {
        assertEquals("thanks for watching", HallucinationFilter.filter("thanks for watching"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  TRIPLE_REPEAT_PATTERN
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `triple repeat pattern matches three identical words`() {
        val input = "the the the quick brown fox"
        val result = HallucinationFilter.TRIPLE_REPEAT_PATTERN.replace(input) { it.groupValues[1] }
        assertEquals("the quick brown fox", result)
    }

    @Test
    fun `triple repeat pattern matches four identical words`() {
        val input = "go go go go now"
        val result = HallucinationFilter.TRIPLE_REPEAT_PATTERN.replace(input) { it.groupValues[1] }
        assertEquals("go now", result)
    }

    @Test
    fun `triple repeat pattern does not match two identical words`() {
        val input = "the the quick brown fox"
        val result = HallucinationFilter.TRIPLE_REPEAT_PATTERN.replace(input) { it.groupValues[1] }
        assertEquals("the the quick brown fox", result)
    }

    @Test
    fun `triple repeat pattern matches multi-word repetition`() {
        val input = "I think I think I think we should go"
        val result = HallucinationFilter.TRIPLE_REPEAT_PATTERN.replace(input) { it.groupValues[1] }
        assertEquals("I think we should go", result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  isRepeatOfLastSegment() — cross-segment repetition
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `first segment is never a repeat`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("hello world"))
    }

    @Test
    fun `identical consecutive segment is detected as repeat`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("hello world"))
        assertTrue(HallucinationFilter.isRepeatOfLastSegment("hello world"))
    }

    @Test
    fun `different consecutive segment is not a repeat`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("hello world"))
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("goodbye world"))
    }

    @Test
    fun `repeat detection is case-insensitive`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("Hello World"))
        assertTrue(HallucinationFilter.isRepeatOfLastSegment("hello world"))
    }

    @Test
    fun `repeat detection ignores leading and trailing whitespace`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("  hello world  "))
        assertTrue(HallucinationFilter.isRepeatOfLastSegment("hello world"))
    }

    @Test
    fun `empty segment is never a repeat`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment(""))
    }

    @Test
    fun `repeat updates state only for non-repeats`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("first"))
        assertTrue(HallucinationFilter.isRepeatOfLastSegment("first"))
        assertTrue(HallucinationFilter.isRepeatOfLastSegment("first"))
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("second"))
        assertTrue(HallucinationFilter.isRepeatOfLastSegment("second"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  resetSession()
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `resetSession clears cross-segment state`() {
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("hello"))
        assertTrue(HallucinationFilter.isRepeatOfLastSegment("hello"))

        HallucinationFilter.resetSession()

        // After reset, same text should not be detected as repeat
        assertFalse(HallucinationFilter.isRepeatOfLastSegment("hello"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `filter handles empty string`() {
        assertEquals("", HallucinationFilter.filter(""))
    }

    @Test
    fun `filter passes through whitespace-only string`() {
        assertEquals("   ", HallucinationFilter.filter("   "))
    }

    @Test
    fun `filter handles single character`() {
        assertEquals("a", HallucinationFilter.filter("a"))
    }

    @Test
    fun `filter passes through music symbols`() {
        assertEquals("♪", HallucinationFilter.filter("♪"))
    }

    @Test
    fun `filter passes through Korean bracket notation`() {
        assertEquals("[음악]", HallucinationFilter.filter("[음악]"))
    }

    @Test
    fun `filter passes through context-sensitive phrases`() {
        assertEquals("without further ado", HallucinationFilter.filter("without further ado"))
        assertEquals("at the end of the day", HallucinationFilter.filter("at the end of the day"))
    }
}
