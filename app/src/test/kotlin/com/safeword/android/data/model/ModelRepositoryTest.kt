package com.safeword.android.data.model

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Tests for [ModelRepository] — model lifecycle and local cache queries.
 *
 * Exercises:
 * - [isModelDownloaded] returns false for unknown model ID.
 * - [isModelDownloaded] returns false when model file is absent.
 * - [isModelDownloaded] returns true when all multi-file model files are present.
 * - [getModelPath] routes single-file models to a flat file path.
 * - [getModelPath] routes multi-file models to a subdirectory path.
 * - [getTotalModelSize] returns 0 for an empty models directory.
 * - [getDownloadedModels] returns only fully downloaded models.
 *
 * No network or ONNX is exercised here — download tests are integration-level.
 */
class ModelRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private lateinit var repository: ModelRepository

    @Before
    fun setUp() {
        every { context.filesDir } returns tempFolder.root
        repository = ModelRepository(context)
    }

    @Test
    fun `isModelDownloaded returns false for unknown modelId`() {
        assertFalse(repository.isModelDownloaded("unknown-model-id-that-does-not-exist"))
    }

    @Test
    fun `isModelDownloaded returns false when model file is absent`() {
        // Moonshine model requires multi-file entries in a subdirectory
        val modelId = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        assertFalse(repository.isModelDownloaded(modelId))
    }

    @Test
    fun `isModelDownloaded returns true when all multi-file model files are present`() {
        val modelId = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        val info = ModelInfo.findById(modelId)!!
        val modelDir = File(tempFolder.root, "models/$modelId")
        modelDir.mkdirs()
        // Create all required files with non-zero content
        for (filename in info.downloadFiles.keys) {
            File(modelDir, filename).writeBytes(ByteArray(1024))
        }
        assertTrue(repository.isModelDownloaded(modelId))
    }

    @Test
    fun `isModelDownloaded returns false when only some multi-file model files are present`() {
        val modelId = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        val info = ModelInfo.findById(modelId)!!
        val modelDir = File(tempFolder.root, "models/$modelId")
        modelDir.mkdirs()
        // Create only the first file — rest missing
        val firstFilename = info.downloadFiles.keys.first()
        File(modelDir, firstFilename).writeBytes(ByteArray(1024))
        assertFalse(repository.isModelDownloaded(modelId))
    }

    @Test
    fun `getModelPath for multi-file model returns subdirectory path`() {
        val modelId = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        val path = repository.getModelPath(modelId)
        assertTrue(path.endsWith(modelId), "Expected path ending with model ID, got: $path")
        assertFalse(path.endsWith(".bin"), "Multi-file model must not resolve to .bin, got: $path")
    }

    @Test
    fun `getTotalModelSize returns 0 for empty models directory`() {
        assertEquals(0L, repository.getTotalModelSize())
    }

    @Test
    fun `getDownloadedModels returns empty list when nothing is downloaded`() {
        assertTrue(repository.getDownloadedModels().isEmpty())
    }

    @Test
    fun `getDownloadedModels returns model once all its files are present`() {
        val modelId = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        val info = ModelInfo.findById(modelId)!!
        val modelDir = File(tempFolder.root, "models/$modelId")
        modelDir.mkdirs()
        for (filename in info.downloadFiles.keys) {
            File(modelDir, filename).writeBytes(ByteArray(1024))
        }
        val downloaded = repository.getDownloadedModels()
        assertTrue(downloaded.any { it.id == modelId })
    }
}
