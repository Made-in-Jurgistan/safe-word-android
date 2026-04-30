package com.safeword.android.transcription

import com.safeword.android.data.db.PersonalizedEntryEntity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [PersonalizedDictionaryCorrector].
 *
 * Verifies substitution matching, case preservation, multi-entry ordering,
 * disabled entry exclusion, and the use-count tracking result.
 */
class PersonalizedDictionaryCorrectorTest {

    private fun entry(
        id: Long = 1L,
        from: String,
        to: String,
        enabled: Boolean = true,
    ) = PersonalizedEntryEntity(id = id, fromPhrase = from, toPhrase = to, enabled = enabled)

    // -------------------------------------------------------------------------
    // Basic substitution
    // -------------------------------------------------------------------------

    @Test
    fun `replaces exact match`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "gonna go to the store",
            listOf(entry(from = "gonna", to = "going to")),
        )
        assertEquals("going to go to the store", result.text)
        assertTrue(result.firedEntryIds.contains(1L))
    }

    @Test
    fun `replaces phrase match`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "my email is john at example dot com",
            listOf(entry(from = "my email", to = "my email address")),
        )
        assertTrue(result.text.contains("my email address"))
    }

    @Test
    fun `no match leaves text unchanged`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "hello world",
            listOf(entry(from = "gonna", to = "going to")),
        )
        assertEquals("hello world", result.text)
        assertTrue(result.firedEntryIds.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Case preservation
    // -------------------------------------------------------------------------

    @Test
    fun `preserves lowercase when original is lowercase`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "safeword is great",
            listOf(entry(from = "safeword", to = "Safe Word")),
        )
        assertEquals("Safe Word is great", result.text)
    }

    @Test
    fun `preserves title case when original starts with capital`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "Safeword is great",
            listOf(entry(from = "safeword", to = "safe word")),
        )
        assertTrue(result.text.startsWith("Safe"))
    }

    @Test
    fun `preserves ALL CAPS when original is all uppercase`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "using GONNA here",
            listOf(entry(from = "gonna", to = "going to")),
        )
        assertTrue(result.text.contains("GOING TO"))
    }

    // -------------------------------------------------------------------------
    // Case-insensitive matching
    // -------------------------------------------------------------------------

    @Test
    fun `matches case-insensitively`() {
        val entries = listOf(entry(from = "gonna", to = "going to"))
        val lower  = PersonalizedDictionaryCorrector.apply("gonna", entries)
        val title  = PersonalizedDictionaryCorrector.apply("Gonna", entries)
        val upper  = PersonalizedDictionaryCorrector.apply("GONNA", entries)
        assertTrue(lower.firedEntryIds.isNotEmpty())
        assertTrue(title.firedEntryIds.isNotEmpty())
        assertTrue(upper.firedEntryIds.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Word-boundary enforcement
    // -------------------------------------------------------------------------

    @Test
    fun `does not replace substring inside larger word`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "goingon",
            listOf(entry(from = "going", to = "proceeding")),
        )
        assertEquals("goingon", result.text)
    }

    // -------------------------------------------------------------------------
    // Disabled entries
    // -------------------------------------------------------------------------

    @Test
    fun `skips disabled entries`() {
        val result = PersonalizedDictionaryCorrector.apply(
            "gonna go",
            listOf(entry(from = "gonna", to = "going to", enabled = false)),
        )
        assertEquals("gonna go", result.text)
        assertTrue(result.firedEntryIds.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Multiple entries
    // -------------------------------------------------------------------------

    @Test
    fun `applies multiple substitutions in one pass`() {
        val entries = listOf(
            entry(id = 1L, from = "gonna", to = "going to"),
            entry(id = 2L, from = "wanna", to = "want to"),
        )
        val result = PersonalizedDictionaryCorrector.apply("I gonna wanna go", entries)
        assertTrue(result.text.contains("going to"))
        assertTrue(result.text.contains("want to"))
        assertEquals(setOf(1L, 2L), result.firedEntryIds)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `returns blank text unchanged`() {
        val result = PersonalizedDictionaryCorrector.apply("", listOf(entry(from = "x", to = "y")))
        assertEquals("", result.text)
        assertTrue(result.firedEntryIds.isEmpty())
    }

    @Test
    fun `returns original text unchanged when entry list is empty`() {
        val result = PersonalizedDictionaryCorrector.apply("hello", emptyList())
        assertEquals("hello", result.text)
    }

    // -------------------------------------------------------------------------
    // preserveCase helper
    // -------------------------------------------------------------------------

    @Test
    fun `preserveCase returns lowercase for lowercase original`() {
        val out = PersonalizedDictionaryCorrector.preserveCase("gonna", "going to")
        assertEquals("going to", out)
    }

    @Test
    fun `preserveCase returns title case for title-case original`() {
        val out = PersonalizedDictionaryCorrector.preserveCase("Gonna", "going to")
        assertEquals("Going to", out)
    }

    @Test
    fun `preserveCase returns ALL CAPS for uppercase original`() {
        val out = PersonalizedDictionaryCorrector.preserveCase("GONNA", "going to")
        assertEquals("GOING TO", out)
    }
}
