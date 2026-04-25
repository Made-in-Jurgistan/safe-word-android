package com.safeword.android.transcription

import com.safeword.android.service.AccessibilityStateHolder
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.service.ThermalMonitor
import com.safeword.android.service.ThermalTier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.safeword.android.util.MainDispatcherRule
import com.safeword.android.data.db.PersonalVocabularyEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [IncrementalTextInserter] — the bridge between STT engine output
 * and AccessibilityService text insertion.
 *
 * Exercises:
 * - Incremental diff: only new text is processed and inserted.
 * - Full post-processing pipeline (preProcess → corrector → normalize → vocabulary → format).
 * - Session lifecycle: resetForSession, consumeInsertedText.
 * - Insertion success/failure routing.
 * - SymSpell warmup delegation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalTextInserterTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val corrector = mockk<ConfusionSetCorrector>(relaxed = true)
    private val vocabularyObserver = mockk<VocabularyObserver>(relaxed = true)
    private val thermalMonitor = mockk<ThermalMonitor>(relaxed = true)
    private val accessibilityState = mockk<AccessibilityStateHolder>(relaxed = true)

    private val defaultContext = InputContextSnapshot(
        packageName = "com.test.app",
        hintText = "",
        className = "android.widget.EditText",
        textFieldFocused = true,
        keyboardVisible = false,
    )

    private lateinit var inserter: IncrementalTextInserter
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        testScope = TestScope(StandardTestDispatcher())
        HallucinationFilter.resetSession()

        // Default stubs.
        every { thermalMonitor.thermalTier } returns ThermalTier.NOMINAL
        every { vocabularyObserver.confirmedVocabulary } returns MutableStateFlow(emptyList())
        // Corrector passes through by default.
        every { corrector.apply(any(), any(), any(), any(), any(), any()) } answers { firstArg() }
        every { corrector.applyVocabularyLayer(any(), any(), any(), any(), any()) } answers {
            Pair(firstArg<String>(), emptyList<String>())
        }
        // Insertion succeeds by default.
        every { accessibilityState.replaceSessionText(any(), any()) } returns true
        every { accessibilityState.getCurrentFocusedFieldText() } returns null

        val testDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScope.testScheduler)

        inserter = IncrementalTextInserter(
            confusionSetCorrector = corrector,
            vocabularyObserver = vocabularyObserver,
            thermalMonitor = thermalMonitor,
            accessibilityStateHolder = accessibilityState,
            performanceMonitor = PerformanceMonitor(),
            scope = testScope,
            ioDispatcher = testDispatcher,
        )
    }

    // ── Session lifecycle ───────────────────────────────────────────────

    @Test
    fun `resetForSession clears buffers`() = runTest {
        inserter.incrementalInsert("hello world", defaultContext)
        assertEquals("hello world", inserter.getInsertedText())

        inserter.resetForSession()
        assertEquals("", inserter.getInsertedText())
    }

    @Test
    fun `consumeInsertedText returns text and clears state`() = runTest {
        inserter.incrementalInsert("alpha", defaultContext)
        inserter.incrementalInsert("alpha bravo", defaultContext)
        val consumed = inserter.consumeInsertedText()
        assertEquals("alpha bravo", consumed)
        assertEquals("", inserter.getInsertedText(), "Should be empty after consume")
    }

    // ── Incremental diff logic ──────────────────────────────────────────

    @Test
    fun `first insert sends full text`() = runTest {
        inserter.incrementalInsert("hello", defaultContext)

        verify { accessibilityState.replaceSessionText(0, "hello") }
        assertEquals("hello", inserter.getInsertedText())
    }

    @Test
    fun `second insert replaces with full accumulated session text`() = runTest {
        inserter.incrementalInsert("hello", defaultContext)
        inserter.incrementalInsert("hello world", defaultContext)

        // replaceSessionText always rewrites the full session content (not an incremental diff).
        verify { accessibilityState.replaceSessionText(0, "hello world") }
        assertEquals("hello world", inserter.getInsertedText())
    }

    @Test
    fun `blank diff is skipped`() = runTest {
        inserter.incrementalInsert("hello", defaultContext)
        // Same text again — newRaw is empty after removePrefix; early return fires.
        inserter.incrementalInsert("hello", defaultContext)

        verify(exactly = 1) { accessibilityState.replaceSessionText(any(), any()) }
    }

    @Test
    fun `prefix mismatch falls back to full text`() = runTest {
        inserter.incrementalInsert("hello world", defaultContext)
        // Complete rewrite — prefix doesn't match; falls back to full transcript text.
        inserter.incrementalInsert("goodbye world", defaultContext)

        verify { accessibilityState.replaceSessionText(0, "goodbye world") }
    }

    // ── Insertion failure handling ───────────────────────────────────────

    @Test
    fun `failed insertion does not update tracking state`() = runTest {
        every { accessibilityState.replaceSessionText(any(), any()) } returns false

        inserter.incrementalInsert("hello", defaultContext)

        assertEquals("", inserter.getInsertedText(), "Nothing tracked on failure")
    }

    @Test
    fun `subsequent insert after failure retries full text`() = runTest {
        every { accessibilityState.replaceSessionText(any(), any()) } returns false
        inserter.incrementalInsert("hello", defaultContext)

        // Now insertion succeeds. displayableRawText was cleared on failure so no stale accumulation.
        every { accessibilityState.replaceSessionText(any(), any()) } returns true
        inserter.incrementalInsert("hello world", defaultContext)

        verify { accessibilityState.replaceSessionText(0, "hello world") }
    }

    // ── Post-processing pipeline ────────────────────────────────────────

    @Test
    fun `postProcessFull chains normalizer, corrector, formatter, vocabulary`() = runTest {
        val result = inserter.postProcessFull("  hello world  ", defaultContext)
        assertNotNull(result)

        // Verify the pipeline was invoked.
        verify { corrector.apply(any(), eq(defaultContext), any(), any(), any(), any()) }
        verify { corrector.applyVocabularyLayer(any(), any(), eq(defaultContext), any(), any()) }
    }

    @Test
    fun `postProcessFull returns null for blank result`() = runTest {
        // Corrector strips all content.
        every { corrector.apply(any(), any(), any(), any(), any(), any()) } returns ""
        every { corrector.applyVocabularyLayer(any(), any(), any(), any(), any()) } returns
            Pair("", emptyList<String>())

        val result = inserter.postProcessFull("   ", defaultContext)
        assertNull(result)
    }

    @Test
    fun `postProcessFull records vocabulary usage`() = runTest {
        every { corrector.applyVocabularyLayer(any(), any(), any(), any(), any()) } returns
            Pair("cleaned", listOf("vocab1", "vocab2"))

        inserter.postProcessFull("some text", defaultContext)

        verify { vocabularyObserver.recordVocabUsed(listOf("vocab1", "vocab2")) }
    }

    @Test
    fun `incrementalInsert passes thermal tier to corrector`() = runTest {
        every { thermalMonitor.thermalTier } returns ThermalTier.WARM

        inserter.incrementalInsert("hello", defaultContext)

        verify { corrector.apply(any(), any(), eq(true), any(), any(), eq(ThermalTier.WARM)) }
    }

    // ── SymSpell warmup ─────────────────────────────────────────────────

    @Test
    fun `warmSymSpell delegates to corrector on IO`() = runTest {
        inserter.warmSymSpell()
        testScope.advanceUntilIdle()

        verify { corrector.initSymSpell() }
    }

    // ── Streaming text buffer ───────────────────────────────────────────

    @Test
    fun `updateStreamingText and clearStreamingText manage buffer`() {
        inserter.updateStreamingText("partial transcription")
        // No public getter for streaming text — just verify no crash.
        inserter.clearStreamingText()
    }

    // ── Model-ownership invariant ──

    @Test
    fun `incrementalInsert does not apply legacy sentence formatter when model is unavailable`() = runTest {
        inserter.incrementalInsert("here is the demo finally", defaultContext)

        verify { accessibilityState.replaceSessionText(0, "here is the demo finally") }
        assertEquals("here is the demo finally", inserter.getInsertedText())
    }

    @Test
    fun `incrementalInsert keeps chunk boundaries without legacy punctuation formatting`() = runTest {
        inserter.incrementalInsert("hello there", defaultContext)
        inserter.incrementalInsert("hello there world", defaultContext)

        // Each call replaces the full session content, not an incremental diff.
        verify(ordering = io.mockk.Ordering.ORDERED) {
            accessibilityState.replaceSessionText(0, "hello there")
            accessibilityState.replaceSessionText(0, "hello there world")
        }
    }

    @Test
    fun `incrementalInsert preserves existing punctuation when already present`() = runTest {
        inserter.incrementalInsert("Here is the demo, finally.", defaultContext)

        verify { accessibilityState.replaceSessionText(0, "Here is the demo, finally.") }
    }
}
