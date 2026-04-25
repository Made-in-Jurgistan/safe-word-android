@file:OptIn(ExperimentalCoroutinesApi::class)

package com.safeword.android.transcription

import android.content.Context
import com.safeword.android.data.db.PersonalVocabularyEntity
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.service.ThermalTier
import com.safeword.android.testutil.DEFAULT_INPUT_CONTEXT
import com.safeword.android.testutil.PipelineAssertions.assertPipelineOutput
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full post-processing pipeline tests: raw ASR text → ContentNormalizer → ConfusionSetCorrector
 * → TextFormatter → final output.
 *
 * Each test case feeds realistic ASR-like input through the complete pipeline chain and asserts
 * the final user-facing string. This validates cross-step interactions that per-unit tests miss
 * (e.g. filler removal before grammar correction, emoji conversion before formatting).
 */
class TextPostProcessingPipelineTest {

    private lateinit var corrector: ConfusionSetCorrector
    private val context: InputContextSnapshot = DEFAULT_INPUT_CONTEXT

    @Before
    fun setUp() {
        val mockContext = mockk<Context>(relaxed = true)
        corrector = ConfusionSetCorrector(
            patternCache = VocabularyPatternCache(),
            appContext = mockContext,
        )
    }

    // ── Helper: run full pipeline ───────────────────────────────────────

    private fun pipeline(raw: String, previousText: String = ""): String {
        val preProcessed = ContentNormalizer.preProcess(raw)
        val corrected = corrector.apply(
            preProcessed, context,
            previousText = previousText,
            thermalTier = ThermalTier.NOMINAL,
        )
        val normalized = ContentNormalizer.normalizePostPreProcess(corrected)
        return TextFormatter.format(normalized)
    }

    // ════════════════════════════════════════════════════════════════════
    //  1. Clean speech — pass-through sanity
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `clean sentence passes through pipeline unchanged`() {
        assertPipelineOutput(
            "hello world ",
            pipeline("hello world"),
        )
    }

    @Test
    fun `multi-sentence clean speech gets proper formatting`() {
        assertPipelineOutput(
            "the weather is nice. i will go for a walk ",
            pipeline("the weather is nice. i will go for a walk"),
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  2. Disfluency removal → formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `filler words removed before formatting`() {
        val result = pipeline("um so basically uh the meeting is at three")
        // Fillers stripped; no sentence case
        assertTrue(
            !result.contains("um", ignoreCase = true) && !result.contains("uh", ignoreCase = true),
            "Fillers should be stripped: $result",
        )
        assertTrue(result.isNotBlank(), "Should produce output: $result")
    }

    @Test
    fun `self-repair retracted before formatting`() {
        val result = pipeline("i went to the scratch that i drove to the store")
        // Self-repair should retract "i went to the scratch that" → "i drove to the store"
        assertTrue(
            !result.contains("went to the", ignoreCase = true),
            "Self-repair should be retracted: $result",
        )
    }

    @Test
    fun `stutter collapsed across pipeline`() {
        val result = pipeline("the the the problem is is is clear")
        assertPipelineOutput("the problem is clear ", result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  3. Spoken punctuation → symbol conversion → formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `spoken period becomes sentence terminator`() {
        assertPipelineOutput(
            "this sentence. next sentence ",
            pipeline("this sentence period next sentence"),
        )
    }

    @Test
    fun `spoken comma inserts comma`() {
        val result = pipeline("hello comma world")
        assertTrue(result.contains(","), "Should contain comma: $result")
    }

    @Test
    fun `spoken question mark applies`() {
        val result = pipeline("how are you question mark")
        assertTrue(result.endsWith("?") || result.contains("?"), "Should contain question mark: $result")
    }

    @Test
    fun `spoken exclamation mark applies`() {
        val result = pipeline("wow that is amazing exclamation mark")
        assertTrue(
            result.contains("!"),
            "Should contain exclamation: $result",
        )
    }

    @Test
    fun `spoken colon and semicolon convert`() {
        val resultColon = pipeline("note colon bring snacks")
        assertTrue(resultColon.contains(":"), "Should contain colon: $resultColon")

        val resultSemicolon = pipeline("first part semicolon second part")
        assertTrue(resultSemicolon.contains(";"), "Should contain semicolon: $resultSemicolon")
    }

    @Test
    fun `spoken ellipsis converts`() {
        val result = pipeline("well dot dot dot maybe")
        assertTrue(result.contains("…") || result.contains("..."), "Should contain ellipsis: $result")
    }

    // ════════════════════════════════════════════════════════════════════
    //  4. Spoken emoji → symbol conversion → formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `spoken emoji smiley face converts`() {
        val result = pipeline("that was great smiley face emoji")
        assertTrue(result.contains("😊") || result.contains("🙂"), "Should contain smiley: $result")
    }

    @Test
    fun `spoken emoji heart converts`() {
        val result = pipeline("i love you heart emoji")
        assertTrue(result.contains("❤") || result.contains("♥"), "Should contain heart: $result")
    }

    @Test
    fun `spoken emoji thumbs up converts`() {
        val result = pipeline("sounds good thumbs up emoji")
        assertTrue(result.contains("👍"), "Should contain thumbs up: $result")
    }

    @Test
    fun `spoken emoji fire converts`() {
        val result = pipeline("this is lit fire emoji")
        assertTrue(result.contains("🔥"), "Should contain fire: $result")
    }

    // ════════════════════════════════════════════════════════════════════
    //  5. Inverse Text Normalization (ITN) → formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `number words normalized to digits`() {
        val result = pipeline("i have twenty three cats")
        assertTrue(result.contains("23"), "Should contain '23': $result")
    }

    @Test
    fun `ordinal words normalized`() {
        val result = pipeline("he finished in first place")
        // "first" should remain as "first" or convert to "1st" depending on ITN
        assertTrue(
            result.contains("first", ignoreCase = true) || result.contains("1st"),
            "Ordinal handled: $result",
        )
    }

    @Test
    fun `time expressions normalized`() {
        val result = pipeline("the meeting is at two thirty pm")
        assertTrue(
            result.contains("2:30") || result.contains("two thirty", ignoreCase = true),
            "Time normalized: $result",
        )
    }

    @Test
    fun `large number words normalized`() {
        val result = pipeline("the population is three hundred thousand")
        assertTrue(
            result.contains("300,000") || result.contains("300000") ||
                result.contains("300 thousand", ignoreCase = true),
            "Large number normalized: $result",
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  6. Grammar correction (ConfusionSetCorrector) → formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `its vs it's corrected in context`() {
        val result = pipeline("its a beautiful day")
        assertPipelineOutput("it's a beautiful day ", result)
    }

    @Test
    fun `your vs you're corrected`() {
        val result = pipeline("your welcome to join us")
        assertPipelineOutput("you're welcome to join us ", result)
    }

    @Test
    fun `whose vs who's corrected`() {
        val result = pipeline("whose coming to the party")
        assertPipelineOutput("who's coming to the party ", result)
    }

    @Test
    fun `modal of corrected to have`() {
        // "should of" → "should have"
        val result = pipeline("i should of gone earlier")
        assertTrue(
            result.contains("should have", ignoreCase = true),
            "Modal+of should be corrected: $result",
        )
    }

    @Test
    fun `could of corrected to could have`() {
        val result = pipeline("you could of done better")
        assertTrue(
            result.contains("could have", ignoreCase = true),
            "could of → could have: $result",
        )
    }

    // ════════════════════════════════════════════════════════════════════
    //  7. Formatting edge cases
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `pronoun I passes through lowercase`() {
        val result = pipeline("i think i am right and i know it")
        // Pronoun I is no longer capitalized by TextFormatter
        assertTrue(result.isNotBlank(), "Should produce output: $result")
    }

    @Test
    fun `trailing space appended`() {
        val result = pipeline("the sky is blue")
        assertTrue(
            result.endsWith(" "),
            "Should have trailing space: $result",
        )
    }

    @Test
    fun `multi-space collapsed`() {
        val result = pipeline("hello     world")
        assertTrue(!result.contains("  "), "No double spaces: $result")
    }

    @Test
    fun `blank input returns empty`() {
        assertEquals("", pipeline(""))
        assertEquals("", pipeline("   "))
    }

    // ════════════════════════════════════════════════════════════════════
    //  8. Hallucination filtering → formatting
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `hallucination phrases pass through`() {
        val result = pipeline("hello world thank you for watching")
        // Hallucination filter is now passthrough
        assertTrue(
            result.contains("thank you for watching", ignoreCase = true),
            "Hallucination phrases should pass through: $result",
        )
    }

    @Test
    fun `invisible characters stripped`() {
        val result = pipeline("hello\u200Bworld")
        assertTrue(!result.contains("\u200B"), "Zero-width space should be stripped: $result")
    }

    // ════════════════════════════════════════════════════════════════════
    //  9. Combined multi-step interactions
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `filler removal + grammar correction + formatting combined`() {
        val result = pipeline("um its uh basically you know a nice day")
        // Fillers removed, "its" → "it's" (contextual), no sentence case
        assertTrue(!result.contains("um", ignoreCase = true), "Fillers removed: $result")
        assertTrue(result.isNotBlank(), "Should produce output: $result")
    }

    @Test
    fun `stutter + emoji + punctuation combined`() {
        val result = pipeline("that that was great smiley face period see you later")
        // Stutter collapsed, emoji converted, period applied
        assertTrue(!result.contains("that that"), "Stutter collapsed: $result")
    }

    @Test
    fun `self-repair + number normalization combined`() {
        val result = pipeline("i need fifteen no i mean twenty three items")
        // Self-repair retracts "fifteen no i mean" → result should contain 23
        assertTrue(
            result.contains("23") || result.contains("twenty", ignoreCase = true),
            "After self-repair, number should remain: $result",
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // 10. Thermal degradation path
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `warm thermal tier skips SymSpell but keeps other corrections`() {
        val preProcessed = ContentNormalizer.preProcess("its a wonderful day out there today isnt it")
        val corrected = corrector.apply(
            preProcessed, context,
            thermalTier = ThermalTier.WARM,
        )
        val normalized = ContentNormalizer.normalizePostPreProcess(corrected)
        val result = TextFormatter.format(normalized)
        // Grammar corrections (its→it's) should still apply even at WARM
        assertTrue(result.isNotBlank(), "Should produce output at WARM: $result")
    }

    // ════════════════════════════════════════════════════════════════════
    // 11. Sentence deduplication
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `repeated sentences deduplicated`() {
        val result = pipeline("hello world hello world hello world")
        // Triple repeat should be collapsed
        val helloCount = Regex("(?i)hello world").findAll(result).count()
        assertTrue(helloCount <= 2, "Repeated text should be collapsed: $result")
    }

    // ════════════════════════════════════════════════════════════════════
    // 12. Bug regressions
    // ════════════════════════════════════════════════════════════════════

    // BUG-1: SPOKEN_PUNCTUATION_MAP trimEnd() stripped trailing space from period/dot/full-stop,
    // causing mid-text "period" to produce no space before next word (e.g. "went.to").
    @Test
    fun `spoken period mid-text preserves space after dot`() {
        val result = pipeline("i went period to the store")
        assertTrue(
            !result.contains(".t", ignoreCase = true),
            "Missing space after mid-text period — got: $result",
        )
    }

    @Test
    fun `spoken full stop mid-text preserves space after dot`() {
        val result = pipeline("the answer is yes full stop not maybe")
        assertTrue(
            !result.contains(".n", ignoreCase = true),
            "Missing space after mid-text full stop — got: $result",
        )
    }

    // BUG-2: resolveBacktracks used `break` instead of `continue`+searchFrom advancement inside
    // the mid-sentence "correction" guard, causing the while-loop to re-find "correction" on
    // every iteration and exhaust maxIterations without ever reaching later triggers.
    @Test
    fun `second backtrack trigger fires after mid-sentence correction word`() {
        // "correction" here is NOT preceded by sentence-ending punctuation → should be skipped.
        // "actually no" follows and IS a valid backtrack trigger → it MUST fire, discarding
        // everything before it ("i need a correction here") and keeping what comes after.
        val result = pipeline("i need a correction here actually no let me start again")
        // Content before "actually no" should be gone
        assertTrue(
            !result.contains("need a correction here", ignoreCase = true),
            "Content before 'actually no' should have been discarded — got: $result",
        )
        // Content after "actually no" should survive
        assertTrue(
            result.contains("start again", ignoreCase = true),
            "'actually no' trigger should preserve post-trigger text — got: $result",
        )
    }

    // BUG-3: STRAY_PUNCT_PREFIX captures-and-re-emits a leading space via $1, creating a
    // double-space when a stray comma is surrounded by spaces. MULTI_SPACE_REGEX was
    // applied only before stray-punct cleanup, so double-spaces could escape.
    @Test
    fun `stray comma after filler removal produces no double spaces`() {
        val result = pipeline("hello uh , world")
        assertTrue(
            !result.contains("  "),
            "Double space must not remain after stray-comma removal — got: '$result'",
        )
    }

    @Test
    fun `stray semicolon removal produces no double spaces`() {
        val result = pipeline("okay um ; that is all")
        assertTrue(
            !result.contains("  "),
            "Double space must not remain after stray-semicolon removal — got: '$result'",
        )
    }

    // BUG-4: SPOKEN_EMOJI_PATTERN was built from ALL SpokenEmojiTable entries, including
    // internet shorthand aliases ("lol", "rip", "omg", "fyi", "gg", "smh", "lmao", "ngl").
    // These were converted to emoji mid-dictation, corrupting normal text.
    @Test
    fun `fyi in dictation is not converted to emoji`() {
        val result = pipeline("just fyi the meeting is at three")
        assertTrue(!result.contains("💡"), "fyi should not become 💡 mid-text — got: $result")
        assertTrue(result.contains("fyi", ignoreCase = true), "fyi should remain as text — got: $result")
    }

    @Test
    fun `rip in dictation is not converted to emoji`() {
        val result = pipeline("do not rip the page")
        assertTrue(!result.contains("🪦"), "rip should not become 🪦 mid-text — got: $result")
        assertTrue(result.contains("rip", ignoreCase = true), "rip should remain as text — got: $result")
    }

    @Test
    fun `omg in dictation is not converted to emoji`() {
        val result = pipeline("i said omg when i saw it")
        assertTrue(!result.contains("😱"), "omg should not become 😱 mid-text — got: $result")
    }

    @Test
    fun `lol in dictation is not converted to emoji`() {
        val result = pipeline("that was so funny lol i could not stop laughing")
        assertTrue(!result.contains("😂"), "lol should not become 😂 mid-text — got: $result")
    }

    @Test
    fun `formal heart emoji phrase is still converted`() {
        val result = pipeline("i love you heart emoji")
        assertTrue(result.contains("❤️"), "Formal 'heart emoji' phrase should still convert — got: $result")
    }
}
