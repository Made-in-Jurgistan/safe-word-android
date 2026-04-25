package com.safeword.android.transcription

import com.safeword.android.testutil.FakeStreamingEngine
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contract tests for [StreamingTranscriptionEngine] using [FakeStreamingEngine].
 *
 * Validates the lifecycle invariants that all engine implementations must uphold:
 * load → start → feed → stop → release.
 *
 * Uses the fake directly so these tests run without Moonshine/ONNX native libs.
 */
class StreamingEngineContractTest {

    private lateinit var engine: FakeStreamingEngine

    @Before
    fun setUp() {
        engine = FakeStreamingEngine()
    }

    // ════════════════════════════════════════════════════════════════════
    //  1. Lifecycle — load
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `engine starts unloaded`() {
        assertFalse(engine.isLoaded)
    }

    @Test
    fun `loadModel succeeds and marks loaded`() = runTest {
        val result = engine.loadModel("/models/moonshine", 0)
        assertTrue(result)
        assertTrue(engine.isLoaded)
    }

    @Test
    fun `loadModel fails when loadShouldFail is set`() = runTest {
        engine.loadShouldFail = true
        val result = engine.loadModel("/models/moonshine", 0)
        assertFalse(result)
        assertFalse(engine.isLoaded)
    }

    // ════════════════════════════════════════════════════════════════════
    //  2. Lifecycle — stream
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `startStream after load succeeds`() = runTest {
        engine.loadModel("/m", 0)
        engine.startStream()
        // No exception = success
    }

    @Test(expected = IllegalStateException::class)
    fun `startStream before load throws`() {
        engine.startStream()
    }

    @Test
    fun `feedAudio captures chunks`() = runTest {
        engine.loadModel("/m", 0)
        engine.startStream()

        val chunk1 = floatArrayOf(0.1f, 0.2f, 0.3f)
        val chunk2 = floatArrayOf(0.4f, 0.5f)
        engine.feedAudio(chunk1)
        engine.feedAudio(chunk2)

        assertEquals(2, engine.receivedChunks.size)
        assertEquals(3, engine.receivedChunks[0].size)
        assertEquals(2, engine.receivedChunks[1].size)
    }

    @Test(expected = IllegalStateException::class)
    fun `feedAudio before startStream throws`() = runTest {
        engine.loadModel("/m", 0)
        engine.feedAudio(floatArrayOf(0.1f))
    }

    // ════════════════════════════════════════════════════════════════════
    //  3. Lifecycle — stop
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `stopStream returns completed lines as Success`() = runTest {
        engine.loadModel("/m", 0)
        engine.startStream()
        engine.simulateLineCompleted(0, "hello")
        engine.simulateLineCompleted(1, "world")

        val result = engine.stopStream()
        assertIs<TranscriptionResult.Success>(result)
        assertEquals("hello world", result.text)
    }

    @Test
    fun `stopStream with no lines returns empty Success`() = runTest {
        engine.loadModel("/m", 0)
        engine.startStream()
        val result = engine.stopStream()
        assertIs<TranscriptionResult.Success>(result)
        assertEquals("", result.text)
    }

    @Test
    fun `stopStream returns custom result when set`() = runTest {
        engine.loadModel("/m", 0)
        engine.startStream()
        engine.stopResult = TranscriptionResult.NoSpeech

        val result = engine.stopStream()
        assertIs<TranscriptionResult.NoSpeech>(result)
    }

    // ════════════════════════════════════════════════════════════════════
    //  4. Lifecycle — release
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `release clears loaded state`() = runTest {
        engine.loadModel("/m", 0)
        assertTrue(engine.isLoaded)

        engine.release()
        assertFalse(engine.isLoaded)
    }

    // ════════════════════════════════════════════════════════════════════
    //  5. Event listener callbacks
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `simulateLineStarted fires listener`() = runTest {
        engine.loadModel("/m", 0)
        var receivedId = -1L
        var receivedText = ""
        engine.setEventListener(object : StreamingEventListener {
            override fun onLineStarted(lineId: Long, text: String) {
                receivedId = lineId
                receivedText = text
            }
            override fun onLineTextChanged(lineId: Long, text: String) {}
            override fun onLineCompleted(lineId: Long, lineText: String, fullText: String) {}
        })

        engine.startStream()
        val id = engine.simulateLineStarted("start")

        assertEquals(id, receivedId)
        assertEquals("start", receivedText)
    }

    @Test
    fun `simulateLineTextChanged fires listener`() = runTest {
        engine.loadModel("/m", 0)
        var receivedText = ""
        engine.setEventListener(object : StreamingEventListener {
            override fun onLineStarted(lineId: Long, text: String) {}
            override fun onLineTextChanged(lineId: Long, text: String) {
                receivedText = text
            }
            override fun onLineCompleted(lineId: Long, lineText: String, fullText: String) {}
        })

        engine.startStream()
        engine.simulateLineTextChanged(0, "partial text")

        assertEquals("partial text", receivedText)
    }

    @Test
    fun `simulateError fires onError`() = runTest {
        engine.loadModel("/m", 0)
        var receivedError: Throwable? = null
        engine.setEventListener(object : StreamingEventListener {
            override fun onLineStarted(lineId: Long, text: String) {}
            override fun onLineTextChanged(lineId: Long, text: String) {}
            override fun onLineCompleted(lineId: Long, lineText: String, fullText: String) {}
            override fun onError(cause: Throwable) {
                receivedError = cause
            }
        })

        engine.startStream()
        val error = RuntimeException("engine failure")
        engine.simulateError(error)

        assertEquals(error, receivedError)
    }

    @Test
    fun `no crash when listener is null`() = runTest {
        engine.loadModel("/m", 0)
        engine.setEventListener(null)
        engine.startStream()
        // These should silently no-op without crashes
        engine.simulateLineStarted("text")
        engine.simulateLineTextChanged(0, "text")
        engine.simulateLineCompleted(0, "text")
        engine.simulateError(RuntimeException("test"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  6. Reset between sessions
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `startStream clears previous session data`() = runTest {
        engine.loadModel("/m", 0)
        engine.startStream()
        engine.feedAudio(floatArrayOf(0.1f))
        engine.simulateLineCompleted(0, "old text")
        engine.stopStream()

        // Second session
        engine.startStream()
        assertEquals(0, engine.receivedChunks.size)
        val result = engine.stopStream()
        assertIs<TranscriptionResult.Success>(result)
        assertEquals("", result.text)
    }
}
