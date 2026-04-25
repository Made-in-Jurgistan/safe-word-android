package com.safeword.android.transcription

import android.content.Context
import android.content.res.AssetManager
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.service.ThermalTier
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

/**
 * Tests for [ConfusionSetCorrector] — post-ASR correction pipeline Layers 2–5.
 *
 * Exercises:
 * - Empty / blank input short-circuits without modification.
 * - Thermal HOT gating suppresses SymSpell (Layer 5).
 * - Incremental mode skips SymSpell regardless of text length.
 * - [applyVocabularyLayer] with empty vocabulary is a no-op.
 * - Short-text (<4 words) path skips SymSpell unconditionally.
 *
 * Note: tests use isIncremental=true or short text to avoid triggering the
 * SymSpell dictionary asset load, keeping these fast JVM unit tests.
 */
class ConfusionSetCorrectorTest {

    private val assetManager = mockk<AssetManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val patternCache = mockk<VocabularyPatternCache>(relaxed = true)

    private lateinit var corrector: ConfusionSetCorrector

    private val defaultInputContext = InputContextSnapshot(
        packageName = "com.test.app",
        hintText = "",
        className = "android.widget.EditText",
        textFieldFocused = true,
        keyboardVisible = false,
    )

    @Before
    fun setUp() {
        every { context.assets } returns assetManager
        // Return empty stream for any asset open — SymSpell won't crash but won't load
        every { assetManager.open(any()) } returns ByteArrayInputStream(ByteArray(0))
        corrector = ConfusionSetCorrector(patternCache, context)
    }

    @Test
    fun `apply returns rawText unchanged for empty input`() {
        val result = corrector.apply("", defaultInputContext)
        assertEquals("", result)
    }

    @Test
    fun `apply returns rawText unchanged for blank input`() {
        val result = corrector.apply("   ", defaultInputContext)
        assertEquals("   ", result)
    }

    @Test
    fun `apply with incremental mode skips SymSpell on long text`() {
        // 6 words — would normally trigger SymSpell but isIncremental=true skips it
        val input = "the quick brown fox jumps over"
        val result = corrector.apply(input, defaultInputContext, isIncremental = true)
        // ContextualGrammarCorrector may change articles but won't crash
        assert(result.isNotEmpty())
    }

    @Test
    fun `apply with HOT thermal tier skips SymSpell`() {
        val input = "the quick brown fox jumps over lazy dog"
        // Must not throw even though SymSpell dictionary is not loaded
        val result = corrector.apply(
            input,
            defaultInputContext,
            isIncremental = false,
            thermalTier = ThermalTier.HOT,
        )
        assert(result.isNotEmpty())
    }

    @Test
    fun `apply with short text (3 words) skips SymSpell`() {
        // Fewer than 4 words — SymSpell condition skipped regardless of mode
        val input = "hello there friend"
        val result = corrector.apply(input, defaultInputContext, isIncremental = false)
        assert(result.isNotEmpty())
    }

    @Test
    fun `applyVocabularyLayer with empty vocabulary returns input unchanged`() {
        val text = "Some dictated text."
        val (output, applied) = corrector.applyVocabularyLayer(
            text = text,
            personalVocabulary = emptyList(),
            context = defaultInputContext,
        )
        assertEquals(text, output)
        assertEquals(emptyList(), applied)
    }

    @Test
    fun `applyVocabularyLayer at HOT tier with empty vocabulary is a no-op`() {
        val text = "Hot device text."
        val (output, _) = corrector.applyVocabularyLayer(
            text = text,
            personalVocabulary = emptyList(),
            context = defaultInputContext,
            thermalTier = ThermalTier.HOT,
        )
        assertEquals(text, output)
    }
}
