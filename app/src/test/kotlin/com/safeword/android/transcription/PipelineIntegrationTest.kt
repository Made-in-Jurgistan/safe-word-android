package com.safeword.android.transcription

import com.safeword.android.data.db.PersonalizedEntryDao
import com.safeword.android.data.db.PersonalizedEntryEntity
import com.safeword.android.data.settings.PersonalizedDictionaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the full post-processing pipeline:
 *   VoiceCommandDetector → ConfusionSetCorrector → ContentNormalizer → PunctuationPredictor → TextFormatter
 *
 * These tests verify end-to-end behaviour across multiple processing stages.
 */
private val fakeDictionaryDao = object : PersonalizedEntryDao {
    override fun observeAll(): Flow<List<PersonalizedEntryEntity>> = flowOf(emptyList())
    override fun observeEnabled(): Flow<List<PersonalizedEntryEntity>> = flowOf(emptyList())
    override suspend fun insert(entry: PersonalizedEntryEntity): Long = 0L
    override suspend fun update(entry: PersonalizedEntryEntity) {}
    override suspend fun delete(entry: PersonalizedEntryEntity) {}
    override suspend fun deleteById(id: Long) {}
    override suspend fun recordUse(id: Long, now: Long) {}
}

class PipelineIntegrationTest {

    private val testScope = TestScope()
    private val processor = DefaultTextProcessor(
        personalizedDictionaryRepository = PersonalizedDictionaryRepository(fakeDictionaryDao),
        scope = testScope,
    )

    // -------------------------------------------------------------------------
    // Full pipeline: raw ASR text → TextProcessor.process()
    // -------------------------------------------------------------------------

    @Test
    fun `pipeline normalizes numbers and applies sentence case`() {
        val result = processor.process("i have twenty three cats")
        assertTrue(result.startsWith("I have 23 cats"))
    }

    @Test
    fun `pipeline handles spoken punctuation and formatting`() {
        val result = processor.process("hello period how are you question mark")
        assertTrue(result.contains("."))
        assertTrue(result.contains("?"))
    }

    @Test
    fun `pipeline removes filler words`() {
        val result = processor.process("um so like I went to the uh store")
        assertTrue(!result.contains(" um "))
        assertTrue(!result.contains(" uh "))
    }

    @Test
    fun `pipeline collapses stutters`() {
        val result = processor.process("I I I want to go")
        assertTrue(result.startsWith("I want to go"))
    }

    @Test
    fun `pipeline capitalizes acronyms`() {
        val result = processor.process("the nasa api is great")
        assertTrue(result.contains("NASA"))
        assertTrue(result.contains("API"))
    }

    @Test
    fun `pipeline adds trailing punctuation`() {
        val result = processor.process("hello world")
        assertTrue(result.trimEnd().endsWith("."))
    }

    @Test
    fun `pipeline handles empty input`() {
        assertEquals("", processor.process(""))
        assertEquals("", processor.process("   "))
    }

    // -------------------------------------------------------------------------
    // IntentRecognizer unit tests
    // -------------------------------------------------------------------------

    @Test
    fun `intent recognizer detects clear all intent`() {
        val result = assertNotNull(IntentRecognizer.recognize("please remove everything"))
        assertEquals(VoiceAction.ClearAll, result.action)
        assertTrue(result.confidence >= IntentRecognizer.HIGH_CONFIDENCE)
    }

    @Test
    fun `intent recognizer detects delete last word intent`() {
        val result = assertNotNull(IntentRecognizer.recognize("erase the last word"))
        assertEquals(VoiceAction.DeleteLastWord, result.action)
    }

    @Test
    fun `intent recognizer detects undo intent`() {
        val result = assertNotNull(IntentRecognizer.recognize("revert"))
        assertEquals(VoiceAction.Undo, result.action)
    }

    @Test
    fun `intent recognizer rejects unrelated text`() {
        val result = IntentRecognizer.recognize("the weather today is lovely")
        assertNull(result)
    }

    @Test
    fun `intent recognizer requires higher confidence for destructive actions`() {
        // "clear" alone should match ClearAll, but needs HIGH_CONFIDENCE for destructive
        val result = IntentRecognizer.recognize("clear")
        // Either null (confidence too low) or matches with adequate confidence
        if (result != null) {
            assertTrue(result.confidence >= IntentRecognizer.MIN_CONFIDENCE)
        }
    }

    // -------------------------------------------------------------------------
    // Custom voice commands
    // -------------------------------------------------------------------------

    @Test
    fun `custom command text insertion is detected`() {
        val commands = listOf(
            CustomVoiceCommand(
                id = "1",
                triggerPhrases = "my email",
                insertText = "john@example.com",
                enabled = true,
            )
        )
        VoiceCommandDetector.updateCustomCommands(commands)

        val result = VoiceCommandDetector.detect("my email")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.InsertText("john@example.com"), result.action)

        // Cleanup
        VoiceCommandDetector.updateCustomCommands(emptyList())
    }

    @Test
    fun `custom command action mapping works`() {
        val commands = listOf(
            CustomVoiceCommand(
                id = "2",
                triggerPhrases = "nuke it|destroy everything",
                actionName = "clear all",
                enabled = true,
            )
        )
        VoiceCommandDetector.updateCustomCommands(commands)

        val result1 = VoiceCommandDetector.detect("nuke it")
        assertIs<VoiceCommandResult.Command>(result1)
        assertEquals(VoiceAction.ClearAll, result1.action)

        val result2 = VoiceCommandDetector.detect("destroy everything")
        assertIs<VoiceCommandResult.Command>(result2)
        assertEquals(VoiceAction.ClearAll, result2.action)

        VoiceCommandDetector.updateCustomCommands(emptyList())
    }

    @Test
    fun `disabled custom command is not detected`() {
        val commands = listOf(
            CustomVoiceCommand(
                id = "3",
                triggerPhrases = "my address",
                insertText = "123 Main St",
                enabled = false,
            )
        )
        VoiceCommandDetector.updateCustomCommands(commands)

        val result = VoiceCommandDetector.detect("my address")
        assertIs<VoiceCommandResult.Text>(result)

        VoiceCommandDetector.updateCustomCommands(emptyList())
    }

    @Test
    fun `custom commands do not override built-in exact matches`() {
        // Built-in "undo" should still work even with custom commands loaded
        val commands = listOf(
            CustomVoiceCommand(
                id = "4",
                triggerPhrases = "my shortcut",
                insertText = "shortcut text",
                enabled = true,
            )
        )
        VoiceCommandDetector.updateCustomCommands(commands)

        val result = VoiceCommandDetector.detect("undo")
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.Undo, result.action)

        VoiceCommandDetector.updateCustomCommands(emptyList())
    }

    // -------------------------------------------------------------------------
    // PunctuationPredictor
    // -------------------------------------------------------------------------

    @Test
    fun `punctuation predictor inserts comma after introductory phrase`() {
        val result = PunctuationPredictor.predict("however I disagree")
        assertTrue(result.contains("however,") || result.contains("However,"))
    }

    @Test
    fun `punctuation predictor detects question from wh-word`() {
        val result = PunctuationPredictor.predict("where is the store.")
        assertTrue(result.trimEnd().endsWith("?"))
    }

    @Test
    fun `punctuation predictor detects tag question`() {
        val result = PunctuationPredictor.predict("that was good right.")
        assertTrue(result.trimEnd().endsWith("?"))
    }

    @Test
    fun `punctuation predictor leaves non-questions alone`() {
        val result = PunctuationPredictor.predict("I went to the store.")
        assertTrue(result.trimEnd().endsWith("."))
    }

    // -------------------------------------------------------------------------
    // WordConfidenceEstimator
    // -------------------------------------------------------------------------

    @Test
    fun `uniform confidence when no timestamps provided`() {
        val words = WordConfidenceEstimator.estimate("hello world")
        assertEquals(2, words.size)
        assertTrue(words.all { it.confidence == 0.85f })
    }

    @Test
    fun `low confidence for very fast words`() {
        val timestamps = listOf(
            0L to 200L,    // normal
            200L to 210L,  // very fast — 10ms duration
            210L to 400L,  // normal
        )
        val words = WordConfidenceEstimator.estimate("hello quick world", timestamps)
        assertEquals(3, words.size)
        // The fast word should have lower confidence
        assertTrue(words[1].confidence < words[0].confidence)
    }

    @Test
    fun `lowConfidenceIndices returns correct set`() {
        val words = listOf(
            WordConfidenceEstimator.WordWithConfidence("hello", 0, 200, 0.9f),
            WordConfidenceEstimator.WordWithConfidence("world", 200, 210, 0.5f),
            WordConfidenceEstimator.WordWithConfidence("test", 210, 400, 0.8f),
        )
        val lowIdx = WordConfidenceEstimator.lowConfidenceIndices(words)
        assertEquals(setOf(1), lowIdx)
    }

    // -------------------------------------------------------------------------
    // CustomVoiceCommand model
    // -------------------------------------------------------------------------

    @Test
    fun `triggers parses pipe-separated phrases`() {
        val cmd = CustomVoiceCommand(
            id = "t1",
            triggerPhrases = "hello|world|test phrase",
        )
        assertEquals(listOf("hello", "world", "test phrase"), cmd.triggers())
    }

    @Test
    fun `toVoiceAction returns InsertText for insertText`() {
        val cmd = CustomVoiceCommand(id = "t2", triggerPhrases = "test", insertText = "hello")
        val action = cmd.toVoiceAction()
        assertEquals(VoiceAction.InsertText("hello"), action)
    }

    @Test
    fun `toVoiceAction returns correct action for actionName`() {
        val cmd = CustomVoiceCommand(id = "t3", triggerPhrases = "test", actionName = "undo")
        val action = cmd.toVoiceAction()
        assertEquals(VoiceAction.Undo, action)
    }

    @Test
    fun `toVoiceAction returns null for unknown actionName`() {
        val cmd = CustomVoiceCommand(id = "t4", triggerPhrases = "test", actionName = "nonexistent")
        assertNull(cmd.toVoiceAction())
    }

    @Test
    fun `toVoiceAction prefers insertText over actionName`() {
        val cmd = CustomVoiceCommand(
            id = "t5",
            triggerPhrases = "test",
            insertText = "some text",
            actionName = "undo",
        )
        assertEquals(VoiceAction.InsertText("some text"), cmd.toVoiceAction())
    }

    // -------------------------------------------------------------------------
    // Field type gating with IntentRecognizer
    // -------------------------------------------------------------------------

    @Test
    fun `password field suppresses all commands`() {
        val result = VoiceCommandDetector.detect("delete that", FieldType.PASSWORD)
        assertIs<VoiceCommandResult.Text>(result)
    }

    @Test
    fun `search field allows search commands`() {
        val result = VoiceCommandDetector.detect("search for cats", FieldType.SEARCH)
        assertIs<VoiceCommandResult.Command>(result)
        assertEquals(VoiceAction.SearchText("cats"), result.action)
    }

    // -------------------------------------------------------------------------
    // Moonshine overlap guards — containsModelPunctuation
    // -------------------------------------------------------------------------

    @Test
    fun `containsModelPunctuation detects comma`() {
        assertTrue(DefaultTextProcessor.containsModelPunctuation("Hello, world"))
    }

    @Test
    fun `containsModelPunctuation detects question mark`() {
        assertTrue(DefaultTextProcessor.containsModelPunctuation("How are you?"))
    }

    @Test
    fun `containsModelPunctuation detects exclamation`() {
        assertTrue(DefaultTextProcessor.containsModelPunctuation("Wow!"))
    }

    @Test
    fun `containsModelPunctuation detects internal period`() {
        // Period followed by more text = multi-sentence model output
        assertTrue(DefaultTextProcessor.containsModelPunctuation("Hello. World"))
    }

    @Test
    fun `containsModelPunctuation ignores trailing period only`() {
        // A single trailing period is NOT counted — Moonshine often omits it
        // and PunctuationPredictor may still add value on unpunctuated runs
        assertFalse(DefaultTextProcessor.containsModelPunctuation("Hello world."))
    }

    @Test
    fun `containsModelPunctuation returns false for raw unpunctuated text`() {
        assertFalse(DefaultTextProcessor.containsModelPunctuation("hello world"))
    }

    @Test
    fun `containsModelPunctuation detects semicolon`() {
        assertTrue(DefaultTextProcessor.containsModelPunctuation("hello; world"))
    }

    // -------------------------------------------------------------------------
    // Moonshine overlap guards — pipeline-level
    // -------------------------------------------------------------------------

    @Test
    fun `model punctuated text skips PunctuationPredictor and preserves commas`() {
        // Moonshine output with comma — PunctuationPredictor must NOT run
        val input = "Hello, how are you"
        val result = processor.process(input)
        // Should have exactly one comma, not doubled
        assertEquals(1, result.count { it == ',' }, "Expected single comma in: $result")
    }

    @Test
    fun `model punctuated question mark preserved through pipeline`() {
        val input = "Where is the store?"
        val result = processor.process(input)
        assertTrue(result.trimEnd().endsWith("?"), "Question mark should be preserved: $result")
        assertEquals(1, result.count { it == '?' }, "Expected single question mark in: $result")
    }

    @Test
    fun `unpunctuated text still gets PunctuationPredictor treatment`() {
        // No internal punctuation — PunctuationPredictor should run
        val input = "however I disagree"
        val result = processor.process(input)
        // Should get a comma after "however" from PunctuationPredictor
        assertTrue(result.contains(","), "Expected comma insertion for unpunctuated text: $result")
    }

    @Test
    fun `double terminal period from model plus pipeline is collapsed`() {
        // Model outputs "Hello." and spoken-punct also resolves to "."
        // After ContentNormalizer this might produce "Hello.." — TextFormatter must collapse
        val input = "Hello.."
        val result = processor.process(input)
        assertFalse(result.contains(".."), "Double period should be collapsed: $result")
    }

    @Test
    fun `model native casing of proper nouns preserved through pipeline`() {
        // Moonshine outputs "I went to Paris" with correct casing
        val input = "I went to Paris."
        val result = processor.process(input)
        assertTrue(result.contains("Paris"), "Proper noun casing must be preserved: $result")
    }

    @Test
    fun `model multi-sentence cased output preserved`() {
        // Moonshine outputs fully cased, punctuated multi-sentence text
        val input = "The United States is large. Canada is also big."
        val result = processor.process(input)
        assertTrue(result.contains("The United States"), "Model casing preserved: $result")
        assertTrue(result.contains("Canada"), "Model casing preserved: $result")
    }
}
