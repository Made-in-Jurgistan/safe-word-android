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
        val info = ModelInfo.findById(modelId) ?: run {
            Timber.w("[MODEL] isModelDownloaded | unknown modelId=%s", modelId)
            return false
        }
        val components = info.components
        return if (components.isEmpty()) {
            val file = getModelFile(modelId)
            val downloaded = file.exists() && (info.sizeBytes <= 0 || file.length() > info.sizeBytes * 0.9)
            Timber.d("[MODEL] isModelDownloaded | modelId=%s exists=%b fileSize=%d expectedSize=%d downloaded=%b",
                modelId, file.exists(), if (file.exists()) file.length() else 0, info.sizeBytes, downloaded)
            downloaded
        } else {
            val files = components.map { File(getModelDir(modelId), it) }
            val allExist = files.all { it.exists() }
            val totalSize = files.sumOf { if (it.exists()) it.length() else 0L }
            val sizeOk = info.sizeBytes <= 0 || totalSize > info.sizeBytes * 0.9
            val downloaded = allExist && sizeOk
            Timber.d("[MODEL] isModelDownloaded | modelId=%s files=%d totalSize=%d expectedSize=%d downloaded=%b",
                modelId, files.size, totalSize, info.sizeBytes, downloaded)
            downloaded
        }
    }

    /** Get the local directory path for a model. */
    fun getModelDir(modelId: String): File = File(modelsDir, modelId)

    /** Get the local file path for a single-file model. */
    fun getModelFile(modelId: String): File = File(modelsDir, modelId)

    /** Get the full path string for loading into Moonshine. */
    fun getModelPath(modelId: String): String = getModelDir(modelId).absolutePath

    /** List all downloaded models. */
    fun getDownloadedModels(): List<ModelInfo> {
        val downloaded = ModelInfo.AVAILABLE_MODELS.filter { isModelDownloaded(it.id) }
        Timber.d("[MODEL] getDownloadedModels | count=%d ids=%s", downloaded.size, downloaded.map { it.id })
        return downloaded
    }

    /** Get total disk usage of downloaded models. */
    fun getTotalModelSize(): Long {
        val totalSize = modelsDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        Timber.d("[MODEL] getTotalModelSize | totalBytes=%d", totalSize)
        return totalSize
    }

    /**
     * Download a model with progress tracking and coroutine-based retry.
     * Supports resume on partial downloads.
     * A mutex prevents concurrent downloads from racing on shared temp files.
     */
    suspend fun downloadModel(modelId: String): Boolean = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val info = ModelInfo.findById(modelId) ?: run {
                Timber.e("[DOWNLOAD] downloadModel | unknown modelId=%s", modelId)
                updateState(modelId, ModelDownloadState.Error("Unknown model"))
                return@withContext false
            }

            val components = info.components
            var lastException: IOException? = null
            var backoffMs = INITIAL_BACKOFF_MS

            for (attempt in 1..MAX_RETRIES) {
                try {
                    Timber.i("[DOWNLOAD] downloadModel | attempt=%d/%d modelId=%s", attempt, MAX_RETRIES, modelId)
                    updateState(modelId, ModelDownloadState.Downloading(0f))

                    if (components.isEmpty()) {
                        val targetFile = getModelFile(modelId)
                        val tempFile = File(modelsDir, "$modelId.part")
                        downloadSingleFile(info, modelId, targetFile, tempFile)
                    } else {
                        val modelDir = getModelDir(modelId).also { it.mkdirs() }
                        var completed = 0
                        val total = components.size.toFloat()

                        for (component in components) {
                            val targetFile = File(modelDir, component)
                            if (targetFile.exists() && targetFile.length() > 0L) {
                                completed++
                                updateState(modelId, ModelDownloadState.Downloading((completed / total).coerceIn(0f, 1f)))
                                continue
                            }

                            val tempFile = File(modelDir, "$component.part")
                            val url = "${info.downloadUrl}/$component"
                            downloadComponent(
                                modelId = modelId,
                                component = component,
                                url = url,
                                targetFile = targetFile,
                                tempFile = tempFile,
                                progressBase = completed / total,
                                progressScale = 1f / total,
                            )
                            completed++
                        }
                    }

                    updateState(modelId, ModelDownloadState.Downloaded)
                    Timber.i("[DOWNLOAD] downloadModel | complete modelId=%s", modelId)
                    return@withContext true
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

    private fun downloadSingleFile(
        info: ModelInfo,
        modelId: String,
        targetFile: File,
        tempFile: File,
    ) {
        val url = info.downloadUrl
        downloadComponent(
            modelId = modelId,
            component = targetFile.name,
            url = url,
            targetFile = targetFile,
            tempFile = tempFile,
            progressBase = 0f,
            progressScale = 1f,
        )

        val expectedHash = info.sha256
        if (expectedHash != null) {
            val actualHash = sha256(targetFile)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                Timber.e("[DOWNLOAD] downloadModel | SHA256 mismatch modelId=%s expected=%s actual=%s", modelId, expectedHash, actualHash)
                targetFile.delete()
                throw IOException("SHA256 verification failed")
            }
            Timber.i("[DOWNLOAD] downloadModel | SHA256 verified modelId=%s", modelId)
        }
    }

    private fun downloadComponent(
        modelId: String,
        component: String,
        url: String,
        targetFile: File,
        tempFile: File,
        progressBase: Float,
        progressScale: Float,
    ) {
        // Check for partial download (resume support)
        var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (downloadedBytes > 0) {
            requestBuilder.header("Range", "bytes=$downloadedBytes-")
            Timber.i("[DOWNLOAD] downloadComponent | resuming modelId=%s component=%s byte=%d", modelId, component, downloadedBytes)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                val code = response.code
                if (code in 400..499) {
                    Timber.e("[DOWNLOAD] downloadComponent | HTTP client error code=%d modelId=%s component=%s — not retrying", code, modelId, component)
                    updateState(modelId, ModelDownloadState.Error("HTTP $code"))
                    throw IOException("HTTP $code")
                }
                Timber.w("[DOWNLOAD] downloadComponent | HTTP server error code=%d modelId=%s component=%s", code, modelId, component)
                throw IOException("HTTP $code")
            }

            val totalBytes = if (response.code == 206) {
                Timber.d("[DOWNLOAD] downloadComponent | partial content (206) component=%s", component)
                downloadedBytes + (response.body?.contentLength() ?: 0)
            } else {
                downloadedBytes = 0
                tempFile.delete()
                response.body?.contentLength() ?: 0L
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
                        val fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                        val progress = progressBase + (progressScale * fraction)
                        updateState(modelId, ModelDownloadState.Downloading(progress.coerceIn(0f, 1f)))
                    }
                }
            }
        }

        if (!tempFile.renameTo(targetFile)) {
            Timber.e("[DOWNLOAD] downloadComponent | rename failed component=%s", component)
            throw IOException("Failed to rename temp file")
        }
    }

    /** Delete a downloaded model. */
    fun deleteModel(modelId: String): Boolean {
        val info = ModelInfo.findById(modelId)
        val deleted = if (info?.components?.isNotEmpty() == true) {
            val dir = getModelDir(modelId)
            dir.deleteRecursively()
        } else {
            val file = getModelFile(modelId)
            val tempFile = File(modelsDir, "$modelId.part")
            tempFile.delete()
            file.delete()
        }
        if (deleted) {
            updateState(modelId, ModelDownloadState.NotDownloaded)
            Timber.i("[MODEL] deleteModel | deleted modelId=%s", modelId)
        } else {
            Timber.w("[MODEL] deleteModel | failed to delete modelId=%s", modelId)
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
