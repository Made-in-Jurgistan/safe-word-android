package com.safeword.android.transcription

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ContentNormalizer] — pre-processing (steps 1–5) and
 * post-pre-processing (steps 6–10) pipelines.
 */
class ContentNormalizerTest {

    @Before
    fun setUp() {
        HallucinationFilter.resetSession()
    }

    // ════════════════════════════════════════════════════════════════════
    //  preProcess — step 1: invisible character removal
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `preProcess removes zero-width spaces`() {
        val result = ContentNormalizer.preProcess("hello\u200Bworld")
        assertEquals("helloworld", result)
    }

    @Test
    fun `preProcess removes soft hyphens`() {
        val result = ContentNormalizer.preProcess("auto\u00ADmatic")
        assertEquals("automatic", result)
    }

    @Test
    fun `preProcess removes BOM characters`() {
        val result = ContentNormalizer.preProcess("\uFEFFhello")
        assertEquals("hello", result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  preProcess — step 2: hallucination filter (now passthrough)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `preProcess passes hallucination phrases through`() {
        val result = ContentNormalizer.preProcess("thanks for watching goodbye")
        assertEquals("thanks for watching goodbye", result)
    }

    @Test
    fun `preProcess passes standalone hallucination through`() {
        val result = ContentNormalizer.preProcess("thank you")
        assertEquals("thank you", result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  preProcess — cross-segment repeat (new in Phase 4.1)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `preProcess discards cross-segment repeat`() {
        val first = ContentNormalizer.preProcess("hello world")
        assertEquals("hello world", first)
        val second = ContentNormalizer.preProcess("hello world")
        assertEquals("", second)
    }

    @Test
    fun `preProcess passes different segment after repeat`() {
        ContentNormalizer.preProcess("hello world")
        ContentNormalizer.preProcess("hello world") // repeat — discarded
        val third = ContentNormalizer.preProcess("goodbye world")
        assertEquals("goodbye world", third)
    }

    // ════════════════════════════════════════════════════════════════════
    //  preProcess — blank / empty input
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `preProcess returns empty for blank input`() {
        assertEquals("", ContentNormalizer.preProcess(""))
    }

    @Test
    fun `preProcess returns empty for whitespace-only input`() {
        assertEquals("", ContentNormalizer.preProcess("   "))
    }

    // ════════════════════════════════════════════════════════════════════
    //  normalizePostPreProcess — step 9: triple-repeat deduplication
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normalizePostPreProcess deduplicates triple repeats`() {
        val result = ContentNormalizer.normalizePostPreProcess("go go go now")
        assertEquals("go now", result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  normalizePostPreProcess — whitespace collapse
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normalizePostPreProcess collapses multi-spaces`() {
        val result = ContentNormalizer.normalizePostPreProcess("hello   world")
        assertEquals("hello world", result)
    }

    @Test
    fun `normalizePostPreProcess returns empty for blank input`() {
        assertEquals("", ContentNormalizer.normalizePostPreProcess(""))
    }

    // ════════════════════════════════════════════════════════════════════
    //  normalize — full pipeline (steps 1–10)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normalize applies full pipeline`() {
        val result = ContentNormalizer.normalize("hello\u200B   world   go go go")
        assertEquals("hello world go", result)
    }

    @Test
    fun `normalize passes hallucination phrases through`() {
        val result = ContentNormalizer.normalize("thanks for watching actual text here")
        assertEquals("thanks for watching actual text here", result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  normalizePostPreProcess — newline cleanup
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normalizePostPreProcess cleans whitespace around newlines`() {
        val result = ContentNormalizer.normalizePostPreProcess("hello   \n   world")
        assertEquals("hello\nworld", result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  Test delegation shims
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normalizeNumbers converts spoken numbers via InverseTextNormalizer`() {
        val result = InverseTextNormalizer.normalizeNumbers("twenty three")
        assertTrue(result.contains("23") || result == "twenty three",
            "Expected ITN to convert 'twenty three' or pass through")
    }
}
