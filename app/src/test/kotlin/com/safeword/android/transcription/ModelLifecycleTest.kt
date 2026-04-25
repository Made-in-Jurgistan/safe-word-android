package com.safeword.android.transcription

import com.safeword.android.audio.SileroVadDetector
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.service.ThermalMonitor
import com.safeword.android.service.ThermalTier
import com.safeword.android.testutil.FakeStreamingEngine
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.safeword.android.util.MainDispatcherRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ModelManager] — STT model lifecycle orchestration.
 *
 * Exercises:
 * - [preloadModels]: parallel VAD + engine loading, idempotency.
 * - [ensureVadLoaded] / [ensureEngineLoaded]: on-demand loading.
 * - [isTooHotForTranscription]: thermal gate delegation.
 * - [resolveModelPath]: model-not-downloaded guard.
 * - [releaseAll]: resource cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelLifecycleTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val fakeEngine = FakeStreamingEngine()
    private val moonshineEngine = mockk<MoonshineNativeEngine>(relaxed = true)
    private val modelRepository = mockk<ModelRepository>(relaxed = true)
    private val vadDetector = mockk<SileroVadDetector>(relaxed = true)
    private val thermalMonitor = mockk<ThermalMonitor>(relaxed = true)

    private lateinit var testScope: TestScope
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        testScope = TestScope(StandardTestDispatcher())
        every { thermalMonitor.thermalTier } returns ThermalTier.NOMINAL
        every { vadDetector.isLoaded } returns false
        every { moonshineEngine.isLoaded } returns false
        every { modelRepository.isModelDownloaded(any()) } returns true
        every { modelRepository.getModelPath(any()) } returns "/fake/model/path"

        modelManager = ModelManager(
            moonshineStreamingEngine = moonshineEngine,
            modelRepository = modelRepository,
            vadDetector = vadDetector,
            thermalMonitor = thermalMonitor,
            scope = testScope,
        )
    }

    // ── Thermal gating ──────────────────────────────────────────────────

    @Test
    fun `isTooHotForTranscription returns false at NOMINAL`() {
        every { thermalMonitor.thermalTier } returns ThermalTier.NOMINAL
        assertFalse(modelManager.isTooHotForTranscription())
    }

    @Test
    fun `isTooHotForTranscription returns false at WARM`() {
        every { thermalMonitor.thermalTier } returns ThermalTier.WARM
        assertFalse(modelManager.isTooHotForTranscription())
    }

    @Test
    fun `isTooHotForTranscription returns true at HOT`() {
        every { thermalMonitor.thermalTier } returns ThermalTier.HOT
        assertTrue(modelManager.isTooHotForTranscription())
    }

    // ── Model path resolution ───────────────────────────────────────────

    @Test
    fun `resolveModelPath returns null when model not downloaded`() = runTest {
        every { modelRepository.isModelDownloaded(any()) } returns false
        val path = modelManager.resolveModelPath()
        assertEquals(null, path)
    }

    @Test
    fun `resolveModelPath returns path when model is downloaded`() = runTest {
        every { modelRepository.isModelDownloaded(any()) } returns true
        every { modelRepository.getModelPath(any()) } returns requireNotNull(System.getProperty("java.io.tmpdir")) { "java.io.tmpdir system property not set" }
        val path = modelManager.resolveModelPath()
        assertEquals(System.getProperty("java.io.tmpdir"), path)
    }

    // ── ensureVadLoaded ─────────────────────────────────────────────────

    @Test
    fun `ensureVadLoaded loads VAD when not loaded`() = runTest {
        every { vadDetector.isLoaded } returns false andThen true
        val result = modelManager.ensureVadLoaded()
        assertTrue(result)
        verify { vadDetector.load() }
    }

    @Test
    fun `ensureVadLoaded skips load when already loaded`() = runTest {
        every { vadDetector.isLoaded } returns true
        val result = modelManager.ensureVadLoaded()
        assertTrue(result)
        verify(exactly = 0) { vadDetector.load() }
    }

    // ── ensureEngineLoaded ──────────────────────────────────────────────

    @Test
    fun `ensureEngineLoaded loads engine when not loaded`() = runTest {
        every { moonshineEngine.isLoaded } returns false andThen true
        every { modelRepository.isModelDownloaded(any()) } returns true
        every { modelRepository.getModelPath(any()) } returns requireNotNull(System.getProperty("java.io.tmpdir")) { "java.io.tmpdir system property not set" }

        val result = modelManager.ensureEngineLoaded()
        assertTrue(result)
        coVerify { moonshineEngine.loadModel(any(), any()) }
    }

    @Test
    fun `ensureEngineLoaded fails when model not downloaded`() = runTest {
        every { moonshineEngine.isLoaded } returns false
        every { modelRepository.isModelDownloaded(any()) } returns false

        val result = modelManager.ensureEngineLoaded()
        assertFalse(result)
    }

    @Test
    fun `ensureEngineLoaded skips when already loaded`() = runTest {
        every { moonshineEngine.isLoaded } returns true
        val result = modelManager.ensureEngineLoaded()
        assertTrue(result)
        coVerify(exactly = 0) { moonshineEngine.loadModel(any(), any()) }
    }

    // ── preloadModels ───────────────────────────────────────────────────

    @Test
    fun `preloadModels launches parallel loading`() {
        every { modelRepository.isModelDownloaded(any()) } returns true
        every { modelRepository.getModelPath(any()) } returns requireNotNull(System.getProperty("java.io.tmpdir")) { "java.io.tmpdir system property not set" }

        modelManager.preloadModels()
        Thread.sleep(200)

        verify { vadDetector.load() }
        coVerify { moonshineEngine.loadModel(any(), any()) }
    }

    @Test
    fun `preloadModels skips when already loaded`() {
        every { vadDetector.isLoaded } returns true
        every { moonshineEngine.isLoaded } returns true

        modelManager.preloadModels()
        testScope.advanceUntilIdle()

        verify(exactly = 0) { vadDetector.load() }
        coVerify(exactly = 0) { moonshineEngine.loadModel(any(), any()) }
    }

    @Test
    fun `preloadModels is idempotent while job is active`() {
        // First call starts the preload.
        modelManager.preloadModels()
        // Second call should be a no-op (job still active).
        modelManager.preloadModels()
        Thread.sleep(200)

        // load() should only be called once, not twice.
        verify(exactly = 1) { vadDetector.load() }
    }

    // ── releaseAll ──────────────────────────────────────────────────────

    @Test
    fun `releaseAll releases engine and VAD`() = runTest {
        modelManager.releaseAll()
        coVerify { moonshineEngine.release() }
        verify { vadDetector.release() }
    }

    // ── streamingEngine accessor ────────────────────────────────────────

    @Test
    fun `streamingEngine returns the injected engine`() {
        assertEquals(moonshineEngine, modelManager.streamingEngine)
    }
}
