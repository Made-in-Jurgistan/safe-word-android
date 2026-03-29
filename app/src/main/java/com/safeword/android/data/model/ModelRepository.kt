package com.safeword.android.data.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
/**
 * ModelRepository — mirrors desktop Safe Word's ModelManager.
 * Manages model downloads, caching, and file access.
 */
@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val MODELS_DIR = "models"
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1_000L
    }

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    private val certificatePinner = CertificatePinner.Builder()
        // Amazon RSA 2048 M04 intermediate CA — expires 2030-08-23; survives leaf rotations.
        // Intermediate-only pinning: resilient to leaf cert rotations without app updates.
        .add("huggingface.co", "sha256/G9LNNAql897egYsabashkzUCTEJkWBzgoEtk8X/678c=")
        // Backup: Amazon Root CA 1 — expires 2038-01-17; fallback if intermediate rotates.
        .add("huggingface.co", "sha256/++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI=")
        .build()

    private val downloadMutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .certificatePinner(certificatePinner)
        .build()

    /** Download states for each model. */
    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    /** Check if a model is downloaded. */
    fun isModelDownloaded(modelId: String): Boolean {
        val file = getModelFile(modelId)
        val info = ModelInfo.findById(modelId) ?: run {
            Timber.w("[MODEL] isModelDownloaded | unknown modelId=%s", modelId)
            return false
        }
        // Check file exists and is roughly the right size (within 10%)
        val downloaded = file.exists() && file.length() > info.sizeBytes * 0.9
        Timber.d("[MODEL] isModelDownloaded | modelId=%s exists=%b fileSize=%d expectedSize=%d downloaded=%b",
            modelId, file.exists(), if (file.exists()) file.length() else 0, info.sizeBytes, downloaded)
        return downloaded
    }

    /** Get the local file path for a model. */
    fun getModelFile(modelId: String): File {
        val info = ModelInfo.findById(modelId)
        val filename = when (info?.modelType) {
            else -> "ggml-$modelId.bin"
        }
        return File(modelsDir, filename)
    }

    /** Get the full path string for loading into WhisperEngine. */
    fun getModelPath(modelId: String): String = getModelFile(modelId).absolutePath

    /** List all downloaded models. */
    fun getDownloadedModels(): List<ModelInfo> {
        val downloaded = ModelInfo.AVAILABLE_MODELS.filter { isModelDownloaded(it.id) }
        Timber.d("[MODEL] getDownloadedModels | count=%d ids=%s", downloaded.size, downloaded.map { it.id })
        return downloaded
    }

    /** Get total disk usage of downloaded models. */
    fun getTotalModelSize(): Long {
        val totalSize = modelsDir.listFiles()?.sumOf { it.length() } ?: 0
        Timber.d("[MODEL] getTotalModelSize | totalBytes=%d", totalSize)
        return totalSize
    }

    /**
     * Download a model file with progress tracking and coroutine-based retry.
     * Supports resume on partial downloads.
     * A mutex prevents concurrent downloads from racing on the shared temp file.
     */
    suspend fun downloadModel(modelId: String): Boolean = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val info = ModelInfo.findById(modelId) ?: run {
                Timber.e("[DOWNLOAD] downloadModel | unknown modelId=%s", modelId)
                updateState(modelId, ModelDownloadState.Error("Unknown model"))
                return@withContext false
            }

            val targetFile = getModelFile(modelId)
            val tempFile = File(modelsDir, "ggml-$modelId.bin.tmp")

            var lastException: IOException? = null
            var backoffMs = INITIAL_BACKOFF_MS
            for (attempt in 1..MAX_RETRIES) {
                try {
                    Timber.i("[DOWNLOAD] downloadModel | attempt=%d/%d modelId=%s url=%s", attempt, MAX_RETRIES, modelId, info.downloadUrl)
                    updateState(modelId, ModelDownloadState.Downloading(0f))

                    // Check for partial download (resume support)
                    var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

                    val requestBuilder = Request.Builder().url(info.downloadUrl)
                    if (downloadedBytes > 0) {
                        requestBuilder.header("Range", "bytes=$downloadedBytes-")
                        Timber.i("[DOWNLOAD] downloadModel | resuming from byte=%d modelId=%s", downloadedBytes, modelId)
                    }

                    val response = client.newCall(requestBuilder.build()).execute()

                    if (!response.isSuccessful && response.code != 206) {
                        val code = response.code
                        response.close()
                        if (code in 400..499) {
                            Timber.e("[DOWNLOAD] downloadModel | HTTP client error code=%d modelId=%s — not retrying", code, modelId)
                            updateState(modelId, ModelDownloadState.Error("HTTP $code"))
                            return@withContext false
                        }
                        Timber.w("[DOWNLOAD] downloadModel | HTTP server error code=%d attempt=%d/%d modelId=%s", code, attempt, MAX_RETRIES, modelId)
                        throw IOException("HTTP $code")
                    }

                    val totalBytes = if (response.code == 206) {
                        Timber.d("[DOWNLOAD] downloadModel | partial content (206) resuming")
                        downloadedBytes + (response.body?.contentLength() ?: 0)
                    } else {
                        downloadedBytes = 0 // Server doesn't support range, restart
                        Timber.d("[DOWNLOAD] downloadModel | full download (range not supported) modelId=%s", modelId)
                        tempFile.delete()
                        response.body?.contentLength() ?: info.sizeBytes
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    val outputStream = FileOutputStream(tempFile, downloadedBytes > 0)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    body.byteStream().use { input ->
                        outputStream.use { output ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val progress = downloadedBytes.toFloat() / totalBytes
                                updateState(modelId, ModelDownloadState.Downloading(progress.coerceIn(0f, 1f)))
                            }
                        }
                    }

                    // Rename temp to final
                    if (tempFile.renameTo(targetFile)) {
                        // Verify SHA256 if hash is known
                        val expectedHash = info.sha256
                        if (expectedHash != null) {
                            val actualHash = sha256(targetFile)
                            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                                Timber.e("[DOWNLOAD] downloadModel | SHA256 mismatch modelId=%s expected=%s actual=%s", modelId, expectedHash, actualHash)
                                targetFile.delete()
                                updateState(modelId, ModelDownloadState.Error("SHA256 verification failed"))
                                return@withContext false
                            }
                            Timber.i("[DOWNLOAD] downloadModel | SHA256 verified modelId=%s", modelId)
                        }
                        updateState(modelId, ModelDownloadState.Downloaded)
                        Timber.i("[DOWNLOAD] downloadModel | complete modelId=%s fileSize=%d file=%s", modelId, targetFile.length(), targetFile.name)
                        return@withContext true
                    } else {
                        Timber.e("[DOWNLOAD] downloadModel | rename failed temp=%s target=%s", tempFile.name, targetFile.name)
                        throw IOException("Failed to rename temp file")
                    }
                } catch (e: IOException) {
                    lastException = e
                    Timber.w(e, "[DOWNLOAD] downloadModel | attempt=%d/%d failed modelId=%s", attempt, MAX_RETRIES, modelId)
                    if (attempt < MAX_RETRIES) {
                        Timber.d("[DOWNLOAD] downloadModel | retrying in %dms", backoffMs)
                        delay(backoffMs)
                        backoffMs *= 2
                    }
                }
            }

            Timber.e(lastException, "[DOWNLOAD] downloadModel | exhausted %d retries modelId=%s", MAX_RETRIES, modelId)
            updateState(modelId, ModelDownloadState.Error(lastException?.message ?: "Download failed after $MAX_RETRIES attempts"))
            false
        }
    }

    /** Delete a downloaded model. */
    fun deleteModel(modelId: String): Boolean {
        val file = getModelFile(modelId)
        val tempFile = File(modelsDir, "ggml-$modelId.bin.tmp")
        tempFile.delete()
        val deleted = file.delete()
        if (deleted) {
            updateState(modelId, ModelDownloadState.NotDownloaded)
            Timber.i("[MODEL] deleteModel | deleted modelId=%s file=%s", modelId, file.name)
        } else {
            Timber.w("[MODEL] deleteModel | failed to delete modelId=%s exists=%b file=%s", modelId, file.exists(), file.name)
        }
        return deleted
    }

    /** Refresh download states from filesystem. */
    fun refreshStates() {
        Timber.d("[MODEL] refreshStates | scanning available models")
        val states = mutableMapOf<String, ModelDownloadState>()
        for (model in ModelInfo.AVAILABLE_MODELS) {
            states[model.id] = if (isModelDownloaded(model.id)) {
                ModelDownloadState.Downloaded
            } else {
                ModelDownloadState.NotDownloaded
            }
        }
        _downloadStates.value = states
        Timber.d("[MODEL] refreshStates | totalModels=%d downloaded=%d",
            states.size, states.count { it.value is ModelDownloadState.Downloaded })
    }

    private fun updateState(modelId: String, state: ModelDownloadState) {
        _downloadStates.value = _downloadStates.value + (modelId to state)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
