package com.safeword.android.transcription

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextPostProcessorTest {
    private fun expectedPost(expected: String): String {
        return if (expected.isEmpty()) "" else "$expected "
    }

    // -------------------------------------------------------------------------
    // process() — full pipeline
    // -------------------------------------------------------------------------

    @Test
    fun `empty string returns empty string`() {
        assertEquals(expectedPost(""), TextPostProcessor.process(""))
    }

    @Test
    fun `blank string returns empty string`() {
        assertEquals(expectedPost(""), TextPostProcessor.process("   "))
    }

    @Test
    fun `clean input passes through unchanged`() {
        val input = "I would like a cup of tea."
        assertEquals(expectedPost(input), TextPostProcessor.process(input))
    }

    // -------------------------------------------------------------------------
    // Invisible character removal
    // -------------------------------------------------------------------------

    @Test
    fun `strips zero-width space`() {
        assertEquals(expectedPost("Hello world."), TextPostProcessor.process("hello\u200B world"))
    }

    @Test
    fun `strips byte order mark`() {
        assertEquals(expectedPost("Hello."), TextPostProcessor.process("\uFEFFhello"))
    }

    @Test
    fun `strips soft hyphen`() {
        assertEquals(expectedPost("Keyboard."), TextPostProcessor.process("key\u00ADboard"))
    }

    // -------------------------------------------------------------------------
    // Filler word removal
    // -------------------------------------------------------------------------

    @Test
    fun `removes leading uh`() {
        assertEquals(expectedPost("I want coffee."), TextPostProcessor.process("Uh I want coffee."))
    }

    @Test
    fun `removes mid-sentence um`() {
        assertEquals(expectedPost("I, want some tea."), TextPostProcessor.process("I, um, want some tea."))
    }

    @Test
    fun `removes multiple fillers`() {
        // After filler removal + sentence-case: "Uh it is um good." → "It is good."
        assertEquals(expectedPost("It is good."), TextPostProcessor.process("Uh it is um good."))
    }

    @Test
    fun `removes hmm`() {
        // Filler at sentence start → re-capitalised
        assertEquals(expectedPost("Let me think."), TextPostProcessor.process("Hmm let me think."))
    }

    @Test
    fun `filler removal is case insensitive`() {
        assertEquals(expectedPost("Yes."), TextPostProcessor.process("UH yes."))
    }

    @Test
    fun `does not remove uh as part of another word`() {
        val result = TextPostProcessor.process("I visited the cathedral.")
        assertEquals(expectedPost("I visited the cathedral."), result)
    }

    // -------------------------------------------------------------------------
    // Stutter collapse (P1: 2+ reps, up to 6-letter words)
    // -------------------------------------------------------------------------

    @Test
    fun `collapses triple single-letter stutter`() {
        assertEquals(expectedPost("I said hello."), TextPostProcessor.process("I I I said hello."))
    }

    @Test
    fun `collapses triple two-letter stutter`() {
        assertEquals(expectedPost("We should go."), TextPostProcessor.process("We we we should go."))
    }

    @Test
    fun `collapses double repetition`() {
        // P1: Now catches 2+ repetitions
        assertEquals(expectedPost("I said hello."), TextPostProcessor.process("I I said hello."))
    }

    @Test
    fun `collapses the the`() {
        assertEquals(expectedPost("The cat sat."), TextPostProcessor.process("the the cat sat."))
    }

    @Test
    fun `collapses and and`() {
        assertEquals(expectedPost("Salt and pepper."), TextPostProcessor.process("salt and and pepper."))
    }

    @Test
    fun `collapses longer word stutter`() {
        // "going going" (5 letters) should be caught
        assertEquals(expectedPost("I was going home."), TextPostProcessor.process("I was going going home."))
    }

    @Test
    fun `does not collapse 7-letter word repetition`() {
        // Words longer than 6 chars are likely intentional repetition
        val result = TextPostProcessor.process("running running is fun.")
        assertTrue(result.lowercase().contains("running running"), "7-letter word should not collapse: $result")
    }

    // -------------------------------------------------------------------------
    // Self-repair disfluency removal
    // -------------------------------------------------------------------------

    @Test
    fun `resolves coffee-to-tea self-repair`() {
        val input = "I'd like a coffee, oh no wait, tea."
        val result = TextPostProcessor.process(input)
        assertTrue(result.contains("tea", ignoreCase = true), "Expected 'tea' in: $result")
        assertTrue(!result.contains("coffee", ignoreCase = true), "Expected no 'coffee' in: $result")
    }

    @Test
    fun `resolves scratch-that self-repair`() {
        val input = "Turn left, scratch that, turn right."
        val result = TextPostProcessor.process(input)
        assertTrue(result.contains("right", ignoreCase = true), "Expected 'right' in: $result")
        assertTrue(!result.contains("left", ignoreCase = true), "Expected no 'left' in: $result")
    }

    @Test
    fun `resolves never-mind self-repair`() {
        val input = "Call Mark, never mind, call Sarah."
        val result = TextPostProcessor.process(input)
        assertTrue(result.contains("Sarah", ignoreCase = true), "Expected 'Sarah' in: $result")
        assertTrue(!result.contains("Mark", ignoreCase = true), "Expected no 'Mark' in: $result")
    }

    @Test
    fun `bare actually does not trigger self-repair`() {
        val input = "I want blue, actually red."
        val result = TextPostProcessor.process(input)
        assertEquals(expectedPost("I want blue, actually red."), result)
    }

    @Test
    fun `resolves i-mean self-repair`() {
        val input = "She works on Monday, I mean Tuesday."
        val result = TextPostProcessor.process(input)
        assertTrue(result.contains("Tuesday", ignoreCase = true), "Expected 'Tuesday' in: $result")
        assertTrue(!result.contains("Monday", ignoreCase = true), "Expected no 'Monday' in: $result")
    }

    @Test
    fun `resolves wait-no self-repair`() {
        val input = "The meeting is at 3, wait no, 4."
        val result = TextPostProcessor.process(input)
        assertTrue(result.contains("4", ignoreCase = true), "Expected '4' in: $result")
        assertTrue(!result.contains("3", ignoreCase = true), "Expected no '3' in: $result")
    }

    @Test
    fun `no self-repair marker leaves text intact`() {
        assertEquals(expectedPost("I want a cup of tea."), TextPostProcessor.process("I want a cup of tea."))
    }

    // -------------------------------------------------------------------------
    // Whitespace normalisation
    // -------------------------------------------------------------------------

    @Test
    fun `collapses multiple spaces`() {
        assertEquals(expectedPost("Bob likes cats."), TextPostProcessor.process("bob  likes   cats"))
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals(expectedPost("Hello world."), TextPostProcessor.process("  hello world  "))
    }

    // -------------------------------------------------------------------------
    // Spoken punctuation conversion (P0)
    // -------------------------------------------------------------------------

    @Test
    fun `converts spoken period`() {
        assertEquals(expectedPost("Hello."), TextPostProcessor.process("hello period"))
    }

    @Test
    fun `converts spoken comma`() {
        assertEquals(expectedPost("Hello, world."), TextPostProcessor.process("hello comma world"))
    }

    @Test
    fun `converts spoken question mark`() {
        assertEquals(expectedPost("How are you?"), TextPostProcessor.process("how are you question mark"))
    }

    @Test
    fun `converts spoken exclamation point`() {
        assertEquals(expectedPost("Wow!"), TextPostProcessor.process("wow exclamation point"))
    }

    @Test
    fun `converts spoken exclamation mark`() {
        assertEquals(expectedPost("Wow!"), TextPostProcessor.process("wow exclamation mark"))
    }

    @Test
    fun `converts spoken new line`() {
        val result = TextPostProcessor.process("hello new line world")
        assertTrue(result.contains("\n"), "Expected newline in: ${result.replace("\n", "\\n")}")
    }

    @Test
    fun `converts spoken new paragraph`() {
        val result = TextPostProcessor.process("hello new paragraph world")
        assertTrue(result.contains("\n\n"), "Expected double newline in: ${result.replace("\n", "\\n")}")
    }

    @Test
    fun `converts spoken colon`() {
        assertEquals(expectedPost("Note: important."), TextPostProcessor.process("note colon important"))
    }

    @Test
    fun `converts spoken semicolon`() {
        assertEquals(expectedPost("Hello; goodbye."), TextPostProcessor.process("hello semicolon goodbye"))
    }

    @Test
    fun `converts spoken hyphen`() {
        assertEquals(expectedPost("Well-known."), TextPostProcessor.process("well hyphen known"))
    }

    @Test
    fun `converts multiple spoken punctuation`() {
        assertEquals(expectedPost("Hi, how are you?"), TextPostProcessor.process("hi comma how are you question mark"))
    }

    @Test
    fun `converts spoken open and close parenthesis`() {
        val result = TextPostProcessor.process("see appendix open parenthesis page 5 close parenthesis for details")
        assertTrue(result.contains("("), "Expected '(' in: $result")
        assertTrue(result.contains(")"), "Expected ')' in: $result")
    }

    // -------------------------------------------------------------------------
    // Sentence-case capitalization (P0)
    // -------------------------------------------------------------------------

    @Test
    fun `capitalizes first word of text`() {
        assertEquals(expectedPost("Hello world."), TextPostProcessor.process("hello world"))
    }

    @Test
    fun `capitalizes after period`() {
        assertEquals(expectedPost("Hello. World."), TextPostProcessor.process("hello. world"))
    }

    @Test
    fun `capitalizes after question mark`() {
        assertEquals(expectedPost("What? Yes."), TextPostProcessor.process("what? yes"))
    }

    @Test
    fun `capitalizes after exclamation mark`() {
        assertEquals(expectedPost("Wow! Great."), TextPostProcessor.process("wow! great"))
    }

    @Test
    fun `capitalizes after newline`() {
        val result = TextPostProcessor.process("hello new line world")
        // After newline, "world" should be capitalized
        assertTrue(result.contains("World"), "Expected 'World' capitalized after newline in: $result")
    }

    @Test
    fun `preserves already capitalized text`() {
        assertEquals(expectedPost("Hello World is great."), TextPostProcessor.process("Hello World is great."))
    }

    // -------------------------------------------------------------------------
    // Pronoun "I" fix (P0)
    // -------------------------------------------------------------------------

    @Test
    fun `capitalizes standalone i`() {
        assertEquals(expectedPost("I want coffee."), TextPostProcessor.process("i want coffee"))
    }

    @Test
    fun `capitalizes i mid-sentence`() {
        assertEquals(expectedPost("And I want tea."), TextPostProcessor.process("and i want tea"))
    }

    @Test
    fun `does not capitalize i in words`() {
        val result = TextPostProcessor.process("visit the island.")
        assertTrue(result.contains("island"), "Should not capitalize 'i' in 'island': $result")
        assertTrue(!result.contains("Island"), "Should not capitalize 'i' in 'island': $result")
    }

    @Test
    fun `capitalizes i before punctuation`() {
        assertEquals(expectedPost("Tell me what I think."), TextPostProcessor.process("tell me what i think."))
    }

    // -------------------------------------------------------------------------
    // Trailing punctuation enforcement (P1)
    // -------------------------------------------------------------------------

    @Test
    fun `adds period if no terminal punctuation`() {
        assertEquals(expectedPost("Hello world."), TextPostProcessor.process("hello world"))
    }

    @Test
    fun `does not add period if already ends with period`() {
        assertEquals(expectedPost("Hello world."), TextPostProcessor.process("hello world."))
    }

    @Test
    fun `does not add period if ends with question mark`() {
        assertEquals(expectedPost("How are you?"), TextPostProcessor.process("how are you?"))
    }

    @Test
    fun `does not add period if ends with exclamation`() {
        assertEquals(expectedPost("Wow!"), TextPostProcessor.process("wow!"))
    }

    @Test
    fun `does not add period if ends with ellipsis`() {
        assertEquals(expectedPost("Well…"), TextPostProcessor.process("well…"))
    }

    // -------------------------------------------------------------------------
    // Basic number word → digit (P2)
    // -------------------------------------------------------------------------

    @Test
    fun `converts single digit words`() {
        assertEquals(expectedPost("I have 3 cats."), TextPostProcessor.process("i have three cats"))
    }

    @Test
    fun `converts zero`() {
        assertEquals(expectedPost("The score is 0."), TextPostProcessor.process("the score is zero"))
    }

    @Test
    fun `converts ten`() {
        assertEquals(expectedPost("I waited 10 minutes."), TextPostProcessor.process("i waited ten minutes"))
    }

    @Test
    fun `converts teen numbers`() {
        assertEquals(expectedPost("She is 15."), TextPostProcessor.process("she is fifteen"))
    }

    // -------------------------------------------------------------------------
    // Full ITN — compound numbers (P3)
    // -------------------------------------------------------------------------

    @Test
    fun `converts twenty five`() {
        assertEquals(expectedPost("I am 25 years old."), TextPostProcessor.process("i am twenty five years old"))
    }

    @Test
    fun `converts one hundred`() {
        assertEquals(expectedPost("There are 100 people."), TextPostProcessor.process("there are one hundred people"))
    }

    @Test
    fun `converts a hundred`() {
        assertEquals(expectedPost("About 100 items."), TextPostProcessor.process("about a hundred items"))
    }

    @Test
    fun `converts one hundred and thirty seven`() {
        assertEquals(expectedPost("The answer is 137."), TextPostProcessor.process("the answer is one hundred and thirty seven"))
    }

    @Test
    fun `converts two thousand`() {
        assertEquals(expectedPost("It costs 2000."), TextPostProcessor.process("it costs two thousand"))
    }

    @Test
    fun `converts three thousand five hundred`() {
        assertEquals(expectedPost("The altitude is 3500 feet."), TextPostProcessor.process("the altitude is three thousand five hundred feet"))
    }

    @Test
    fun `converts one million`() {
        assertEquals(expectedPost("The population is 1000000."), TextPostProcessor.process("the population is one million"))
    }

    // -------------------------------------------------------------------------
    // Ordinals (P3)
    // -------------------------------------------------------------------------

    @Test
    fun `converts ordinal first`() {
        assertEquals(expectedPost("I came in 1st place."), TextPostProcessor.process("i came in first place"))
    }

    @Test
    fun `converts ordinal third`() {
        assertEquals(expectedPost("The 3rd option."), TextPostProcessor.process("the third option"))
    }

    @Test
    fun `converts ordinal twentieth`() {
        assertEquals(expectedPost("The 20th century."), TextPostProcessor.process("the twentieth century"))
    }

    // -------------------------------------------------------------------------
    // Percent and currency (P3)
    // -------------------------------------------------------------------------

    @Test
    fun `converts number percent`() {
        assertEquals(expectedPost("It was 50%."), TextPostProcessor.process("it was fifty percent"))
    }

    @Test
    fun `converts number dollars`() {
        assertEquals(expectedPost("It costs $10."), TextPostProcessor.process("it costs ten dollars"))
    }

    @Test
    fun `converts number euros`() {
        assertEquals(expectedPost("That's €5."), TextPostProcessor.process("that's five euros"))
    }

    @Test
    fun `converts number pounds`() {
        assertEquals(expectedPost("It's £20."), TextPostProcessor.process("it's twenty pounds"))
    }

    // -------------------------------------------------------------------------
    // Combined pipeline
    // -------------------------------------------------------------------------

    @Test
    fun `combined fillers and self-repair`() {
        val input = "Uh, I want a coffee, wait no, tea."
        val result = TextPostProcessor.process(input)
        assertTrue(result.contains("tea", ignoreCase = true), "Expected 'tea' in: $result")
        assertTrue(!result.contains("coffee", ignoreCase = true), "Expected no 'coffee' in: $result")
        assertTrue(!result.lowercase().startsWith("uh"), "Expected 'uh' removed: $result")
    }

    @Test
    fun `combined stutter and filler`() {
        val input = "I I I um want some water."
        val result = TextPostProcessor.process(input)
        assertTrue(result.contains("want some water", ignoreCase = true), "Expected 'want some water' in: $result")
    }

    @Test
    fun `combined spoken punctuation and capitalization`() {
        val input = "hello comma how are you question mark i am fine"
        val result = TextPostProcessor.process(input)
        assertEquals(expectedPost("Hello, how are you? I am fine."), result)
    }

    @Test
    fun `combined fillers numbers and capitalization`() {
        val input = "uh i have um three cats and two dogs"
        val result = TextPostProcessor.process(input)
        assertEquals(expectedPost("I have 3 cats and 2 dogs."), result)
    }

    // -------------------------------------------------------------------------
    // resolveSelfRepairs() — internal method
    // -------------------------------------------------------------------------

    @Test
    fun `resolveSelfRepairs with oh no wait`() {
        val result = ContentNormalizer.resolveSelfRepairs("I want cold brew, oh no wait, hot tea.")
        assertTrue(result.contains("hot tea", ignoreCase = true), "Expected 'hot tea' in: $result")
        assertTrue(!result.contains("cold brew", ignoreCase = true), "Expected no 'cold brew' in: $result")
    }

    @Test
    fun `resolveSelfRepairs with no editing term returns original`() {
        val input = "Just a clean sentence."
        assertEquals(input, ContentNormalizer.resolveSelfRepairs(input))
    }

    @Test
    fun `resolveSelfRepairs is idempotent on clean text`() {
        val input = "I want tea."
        assertEquals(input, ContentNormalizer.resolveSelfRepairs(input))
    }

    @Test
    fun `resolveSelfRepairs handles blank input`() {
        assertEquals("", ContentNormalizer.resolveSelfRepairs(""))
    }

    @Test
    fun `resolveSelfRepairs maximum 10 iterations safety`() {
        val input = "actually wait actually wait actually wait actually wait the end."
        val result = ContentNormalizer.resolveSelfRepairs(input)
        assertTrue(result.isNotEmpty(), "Expected non-empty result for pathological input")
    }

    // -------------------------------------------------------------------------
    // normalizeNumbers() — internal method
    // -------------------------------------------------------------------------

    @Test
    fun `normalizeNumbers with mixed text`() {
        val result = ContentNormalizer.normalizeNumbers("about twenty five percent of the population")
        assertTrue(result.contains("25%"), "Expected '25%' in: $result")
    }

    @Test
    fun `normalizeNumbers with no numbers passes through`() {
        val input = "hello world"
        assertEquals(input, ContentNormalizer.normalizeNumbers(input))
    }
}
