package com.safeword.android.transcription

import com.safeword.android.data.db.PersonalizedEntryDao
import com.safeword.android.data.db.PersonalizedEntryEntity
import com.safeword.android.data.settings.PersonalizedDictionaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultTextProcessorTest {
    private val fakeDictionaryDao = object : PersonalizedEntryDao {
        override fun observeAll(): Flow<List<PersonalizedEntryEntity>> = flowOf(emptyList())
        override fun observeEnabled(): Flow<List<PersonalizedEntryEntity>> = flowOf(emptyList())
        override suspend fun insert(entry: PersonalizedEntryEntity): Long = 0L
        override suspend fun update(entry: PersonalizedEntryEntity) {}
        override suspend fun delete(entry: PersonalizedEntryEntity) {}
        override suspend fun deleteById(id: Long) {}
        override suspend fun recordUse(id: Long, now: Long) {}
    }

    private val processor = DefaultTextProcessor(
        personalizedDictionaryRepository = PersonalizedDictionaryRepository(fakeDictionaryDao),
        scope = TestScope(),
    )

    private fun expectedPost(expected: String): String {
        return if (expected.isEmpty()) "" else "$expected "
    }

    // -------------------------------------------------------------------------
    // process() — full pipeline
    // -------------------------------------------------------------------------

    @Test
    fun `empty string returns empty string`() {
        assertEquals(expectedPost(""), processor.process(""))
    }

    @Test
    fun `blank string returns empty string`() {
        assertEquals(expectedPost(""), processor.process("   "))
    }

    @Test
    fun `clean input passes through unchanged`() {
        val input = "I would like a cup of tea."
        assertEquals(expectedPost(input), processor.process(input))
    }

    // -------------------------------------------------------------------------
    // Invisible character removal
    // -------------------------------------------------------------------------

    @Test
    fun `strips zero-width space`() {
        assertEquals(expectedPost("Hello world."), processor.process("hello\u200B world"))
    }

    @Test
    fun `strips byte order mark`() {
        assertEquals(expectedPost("Hello."), processor.process("\uFEFFhello"))
    }

    @Test
    fun `strips soft hyphen`() {
        assertEquals(expectedPost("Keyboard."), processor.process("key\u00ADboard"))
    }

    // -------------------------------------------------------------------------
    // Filler word removal
    // -------------------------------------------------------------------------

    @Test
    fun `removes leading uh`() {
        assertEquals(expectedPost("I want coffee."), processor.process("Uh I want coffee."))
    }

    @Test
    fun `removes mid-sentence um`() {
        assertEquals(expectedPost("I, want some tea."), processor.process("I, um, want some tea."))
    }

    @Test
    fun `removes multiple fillers`() {
        // After filler removal + sentence-case: "Uh it is um good." → "It is good."
        assertEquals(expectedPost("It is good."), processor.process("Uh it is um good."))
    }

    @Test
    fun `removes hmm`() {
        // Filler at sentence start → re-capitalised
        assertEquals(expectedPost("Let me think."), processor.process("Hmm let me think."))
    }

    @Test
    fun `filler removal is case insensitive`() {
        assertEquals(expectedPost("Yes."), processor.process("UH yes."))
    }

    @Test
    fun `does not remove uh as part of another word`() {
        val result = processor.process("I visited the cathedral.")
        assertEquals(expectedPost("I visited the cathedral."), result)
    }

    // -------------------------------------------------------------------------
    // Stutter collapse (P1: 2+ reps, up to 6-letter words)
    // -------------------------------------------------------------------------

    @Test
    fun `collapses triple single-letter stutter`() {
        assertEquals(expectedPost("I said hello."), processor.process("I I I said hello."))
    }

    @Test
    fun `collapses triple two-letter stutter`() {
        assertEquals(expectedPost("We should go."), processor.process("We we we should go."))
    }

    @Test
    fun `collapses double repetition`() {
        // P1: Now catches 2+ repetitions
        assertEquals(expectedPost("I said hello."), processor.process("I I said hello."))
    }

    @Test
    fun `collapses the the`() {
        assertEquals(expectedPost("The cat sat."), processor.process("the the cat sat."))
    }

    @Test
    fun `collapses and and`() {
        assertEquals(expectedPost("Salt and pepper."), processor.process("salt and and pepper."))
    }

    @Test
    fun `collapses longer word stutter`() {
        // "going going" (5 letters) should be caught
        assertEquals(expectedPost("I was going home."), processor.process("I was going going home."))
    }

    @Test
    fun `does not collapse 7-letter word repetition`() {
        // Words longer than 6 chars are likely intentional repetition
        val result = processor.process("running running is fun.")
        assertTrue(result.lowercase().contains("running running"), "7-letter word should not collapse: $result")
    }

    // -------------------------------------------------------------------------
    // Self-repair disfluency removal
    // -------------------------------------------------------------------------

    @Test
    fun `resolves coffee-to-tea self-repair`() {
        val input = "I'd like a coffee, oh no wait, tea."
        val result = processor.process(input)
        assertTrue(result.contains("tea", ignoreCase = true), "Expected 'tea' in: $result")
        assertTrue(!result.contains("coffee", ignoreCase = true), "Expected no 'coffee' in: $result")
    }

    @Test
    fun `resolves scratch-that self-repair`() {
        val input = "Turn left, scratch that, turn right."
        val result = processor.process(input)
        assertTrue(result.contains("right", ignoreCase = true), "Expected 'right' in: $result")
        assertTrue(!result.contains("left", ignoreCase = true), "Expected no 'left' in: $result")
    }

    @Test
    fun `resolves never-mind self-repair`() {
        val input = "Call Mark, never mind, call Sarah."
        val result = processor.process(input)
        assertTrue(result.contains("Sarah", ignoreCase = true), "Expected 'Sarah' in: $result")
        assertTrue(!result.contains("Mark", ignoreCase = true), "Expected no 'Mark' in: $result")
    }

    @Test
    fun `bare actually does not trigger self-repair`() {
        val input = "I want blue, actually red."
        val result = processor.process(input)
        assertEquals(expectedPost("I want blue, actually red."), result)
    }

    @Test
    fun `resolves i-mean self-repair`() {
        val input = "She works on Monday, I mean Tuesday."
        val result = processor.process(input)
        assertTrue(result.contains("Tuesday", ignoreCase = true), "Expected 'Tuesday' in: $result")
        assertTrue(!result.contains("Monday", ignoreCase = true), "Expected no 'Monday' in: $result")
    }

    @Test
    fun `resolves wait-no self-repair`() {
        val input = "The meeting is at 3, wait no, 4."
        val result = processor.process(input)
        assertTrue(result.contains("4", ignoreCase = true), "Expected '4' in: $result")
        assertTrue(!result.contains("3", ignoreCase = true), "Expected no '3' in: $result")
    }

    @Test
    fun `no self-repair marker leaves text intact`() {
        assertEquals(expectedPost("I want a cup of tea."), processor.process("I want a cup of tea."))
    }

    // -------------------------------------------------------------------------
    // Whitespace normalisation
    // -------------------------------------------------------------------------

    @Test
    fun `collapses multiple spaces`() {
        assertEquals(expectedPost("Bob likes cats."), processor.process("bob  likes   cats"))
    }

    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals(expectedPost("Hello world."), processor.process("  hello world  "))
    }

    // -------------------------------------------------------------------------
    // Spoken punctuation conversion (P0)
    // -------------------------------------------------------------------------

    @Test
    fun `converts spoken period`() {
        assertEquals(expectedPost("Hello."), processor.process("hello period"))
    }

    @Test
    fun `converts spoken comma`() {
        assertEquals(expectedPost("Hello, world."), processor.process("hello comma world"))
    }

    @Test
    fun `converts spoken question mark`() {
        assertEquals(expectedPost("How are you?"), processor.process("how are you question mark"))
    }

    @Test
    fun `converts spoken exclamation point`() {
        assertEquals(expectedPost("Wow!"), processor.process("wow exclamation point"))
    }

    @Test
    fun `converts spoken exclamation mark`() {
        assertEquals(expectedPost("Wow!"), processor.process("wow exclamation mark"))
    }

    @Test
    fun `converts spoken new line`() {
        val result = processor.process("hello new line world")
        assertTrue(result.contains("\n"), "Expected newline in: ${result.replace("\n", "\\n")}")
    }

    @Test
    fun `converts spoken new paragraph`() {
        val result = processor.process("hello new paragraph world")
        assertTrue(result.contains("\n\n"), "Expected double newline in: ${result.replace("\n", "\\n")}")
    }

    @Test
    fun `converts spoken colon`() {
        assertEquals(expectedPost("Note: important."), processor.process("note colon important"))
    }

    @Test
    fun `converts spoken semicolon`() {
        assertEquals(expectedPost("Hello; goodbye."), processor.process("hello semicolon goodbye"))
    }

    @Test
    fun `converts spoken hyphen`() {
        assertEquals(expectedPost("Well-known."), processor.process("well hyphen known"))
    }

    @Test
    fun `converts multiple spoken punctuation`() {
        assertEquals(expectedPost("Hi, how are you?"), processor.process("hi comma how are you question mark"))
    }

    @Test
    fun `converts spoken open and close parenthesis`() {
        val result = processor.process("see appendix open parenthesis page 5 close parenthesis for details")
        assertTrue(result.contains("("), "Expected '(' in: $result")
        assertTrue(result.contains(")"), "Expected ')' in: $result")
    }

    // -------------------------------------------------------------------------
    // Sentence-case capitalization (P0)
    // -------------------------------------------------------------------------

    @Test
    fun `capitalizes first word of text`() {
        assertEquals(expectedPost("Hello world."), processor.process("hello world"))
    }

    @Test
    fun `capitalizes after period`() {
        assertEquals(expectedPost("Hello. World."), processor.process("hello. world"))
    }

    @Test
    fun `capitalizes after question mark`() {
        assertEquals(expectedPost("What? Yes."), processor.process("what? yes"))
    }

    @Test
    fun `capitalizes after exclamation mark`() {
        assertEquals(expectedPost("Wow! Great."), processor.process("wow! great"))
    }

    @Test
    fun `capitalizes after newline`() {
        val result = processor.process("hello new line world")
        // After newline, "world" should be capitalized
        assertTrue(result.contains("World"), "Expected 'World' capitalized after newline in: $result")
    }

    @Test
    fun `preserves already capitalized text`() {
        assertEquals(expectedPost("Hello World is great."), processor.process("Hello World is great."))
    }

    // -------------------------------------------------------------------------
    // Pronoun "I" fix (P0)
    // -------------------------------------------------------------------------

    @Test
    fun `capitalizes standalone i`() {
        assertEquals(expectedPost("I want coffee."), processor.process("i want coffee"))
    }

    @Test
    fun `capitalizes i mid-sentence`() {
        assertEquals(expectedPost("And I want tea."), processor.process("and i want tea"))
    }

    @Test
    fun `does not capitalize i in words`() {
        val result = processor.process("visit the island.")
        assertTrue(result.contains("island"), "Should not capitalize 'i' in 'island': $result")
        assertTrue(!result.contains("Island"), "Should not capitalize 'i' in 'island': $result")
    }

    @Test
    fun `capitalizes i before punctuation`() {
        assertEquals(expectedPost("Tell me what I think."), processor.process("tell me what i think."))
    }

    // -------------------------------------------------------------------------
    // Trailing punctuation enforcement (P1)
    // -------------------------------------------------------------------------

    @Test
    fun `adds period if no terminal punctuation`() {
        assertEquals(expectedPost("Hello world."), processor.process("hello world"))
    }

    @Test
    fun `does not add period if already ends with period`() {
        assertEquals(expectedPost("Hello world."), processor.process("hello world."))
    }

    @Test
    fun `does not add period if ends with question mark`() {
        assertEquals(expectedPost("How are you?"), processor.process("how are you?"))
    }

    @Test
    fun `does not add period if ends with exclamation`() {
        assertEquals(expectedPost("Wow!"), processor.process("wow!"))
    }

    @Test
    fun `does not add period if ends with ellipsis`() {
        assertEquals(expectedPost("Well…"), processor.process("well…"))
    }

    // -------------------------------------------------------------------------
    // Basic number word → digit (P2)
    // -------------------------------------------------------------------------

    @Test
    fun `converts single digit words`() {
        assertEquals(expectedPost("I have 3 cats."), processor.process("i have three cats"))
    }

    @Test
    fun `converts zero`() {
        assertEquals(expectedPost("The score is 0."), processor.process("the score is zero"))
    }

    @Test
    fun `converts ten`() {
        assertEquals(expectedPost("I waited 10 minutes."), processor.process("i waited ten minutes"))
    }

    @Test
    fun `converts teen numbers`() {
        assertEquals(expectedPost("She is 15."), processor.process("she is fifteen"))
    }

    // -------------------------------------------------------------------------
    // Full ITN — compound numbers (P3)
    // -------------------------------------------------------------------------

    @Test
    fun `converts twenty five`() {
        assertEquals(expectedPost("I am 25 years old."), processor.process("i am twenty five years old"))
    }

    @Test
    fun `converts one hundred`() {
        assertEquals(expectedPost("There are 100 people."), processor.process("there are one hundred people"))
    }

    @Test
    fun `converts a hundred`() {
        assertEquals(expectedPost("About 100 items."), processor.process("about a hundred items"))
    }

    @Test
    fun `converts one hundred and thirty seven`() {
        assertEquals(expectedPost("The answer is 137."), processor.process("the answer is one hundred and thirty seven"))
    }

    @Test
    fun `converts two thousand`() {
        assertEquals(expectedPost("It costs 2000."), processor.process("it costs two thousand"))
    }

    @Test
    fun `converts three thousand five hundred`() {
        assertEquals(expectedPost("The altitude is 3500 feet."), processor.process("the altitude is three thousand five hundred feet"))
    }

    @Test
    fun `converts one million`() {
        assertEquals(expectedPost("The population is 1000000."), processor.process("the population is one million"))
    }

    // -------------------------------------------------------------------------
    // Ordinals (P3)
    // -------------------------------------------------------------------------

    @Test
    fun `converts ordinal first`() {
        assertEquals(expectedPost("I came in 1st place."), processor.process("i came in first place"))
    }

    @Test
    fun `converts ordinal third`() {
        assertEquals(expectedPost("The 3rd option."), processor.process("the third option"))
    }

    @Test
    fun `converts ordinal twentieth`() {
        assertEquals(expectedPost("The 20th century."), processor.process("the twentieth century"))
    }

    // -------------------------------------------------------------------------
    // Percent and currency (P3)
    // -------------------------------------------------------------------------

    @Test
    fun `converts number percent`() {
        assertEquals(expectedPost("It was 50%."), processor.process("it was fifty percent"))
    }

    @Test
    fun `converts number dollars`() {
        assertEquals(expectedPost("It costs $10."), processor.process("it costs ten dollars"))
    }

    @Test
    fun `converts number euros`() {
        assertEquals(expectedPost("That's €5."), processor.process("that's five euros"))
    }

    @Test
    fun `converts number pounds`() {
        assertEquals(expectedPost("It's £20."), processor.process("it's twenty pounds"))
    }

    // -------------------------------------------------------------------------
    // Combined pipeline
    // -------------------------------------------------------------------------

    @Test
    fun `combined fillers and self-repair`() {
        val input = "Uh, I want a coffee, wait no, tea."
        val result = processor.process(input)
        assertTrue(result.contains("tea", ignoreCase = true), "Expected 'tea' in: $result")
        assertTrue(!result.contains("coffee", ignoreCase = true), "Expected no 'coffee' in: $result")
        assertTrue(!result.lowercase().startsWith("uh"), "Expected 'uh' removed: $result")
    }

    @Test
    fun `combined stutter and filler`() {
        val input = "I I I um want some water."
        val result = processor.process(input)
        assertTrue(result.contains("want some water", ignoreCase = true), "Expected 'want some water' in: $result")
    }

    @Test
    fun `combined spoken punctuation and capitalization`() {
        val input = "hello comma how are you question mark i am fine"
        val result = processor.process(input)
        assertEquals(expectedPost("Hello, how are you? I am fine."), result)
    }

    @Test
    fun `combined fillers numbers and capitalization`() {
        val input = "uh i have um three cats and two dogs"
        val result = processor.process(input)
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
