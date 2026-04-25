package com.safeword.android.data.model

import android.content.Context
import com.safeword.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.safeword.android.BuildConfig
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
    @ApplicationScope private val scope: CoroutineScope,
) {
    companion object {
        private const val MODELS_DIR = "models"
        private const val MAX_RETRIES = 3
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val DOWNLOAD_BUFFER_SIZE = 8_192
        /** GTS Root R1 expiry: 2036-06-22T00:00:00Z — computed to avoid silent staleness. */
        private val GTS_ROOT_R1_EXPIRY_MS = java.time.Instant.parse("2036-06-22T00:00:00Z").toEpochMilli()
        private const val CERT_EXPIRY_WARN_MS = 30L * 24L * 60L * 60L * 1_000L

        /** Minimum seconds between model download attempts — rate-limits bandwidth abuse. */
        private const val MIN_DOWNLOAD_INTERVAL_SEC = 30L
        /** Maximum concurrent download attempts within the rate-limit window. */
        private const val MAX_DOWNLOADS_PER_HOUR = 10
    }

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }

    private val downloadMutex = Mutex()

    // Rate-limiting state
    @Volatile private var lastDownloadAttemptMs: Long = 0L
    private val downloadHistoryMs = java.util.ArrayDeque<Long>(MAX_DOWNLOADS_PER_HOUR)

    // Pin the GTS Root R1 (stable through 2036-06-22) and WR3 intermediate
    // (current Google Trust Services chain). Avoids leaf pinning — GTS rotates
    // intermediates frequently. Post-download SHA256 verification is the primary
    // tamper-detection layer; pinning provides defense-in-depth.
    //
    // When the intermediate rotates, add the new hash here. Run:
    //   openssl s_client -connect download.moonshine.ai:443 -showcerts 2>/dev/null |
    //   openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER |
    //   openssl dgst -sha256 -binary | base64
    private val certPinner = CertificatePinner.Builder()
        .add(
            "download.moonshine.ai",
            "sha256/OdSlmQD9NWJh4EbcOHBxkhygPwNSwA9Q91eounfbcoE=", // WR3 intermediate (current)
            "sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=", // GTS Root R1 (expires 2036-06)
        )
        .build()

    private val client = OkHttpClient.Builder()
        .certificatePinner(certPinner)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    init {
        checkCertPinExpiry()
    }

    /**
     * Warns when the pinned GTS Root R1 certificate is within 30 days of expiry.
     * GTS Root R1 is valid through 2036-06-22T00:00:00Z. Update the pins and this
     * timestamp before that date.
     */
    private fun checkCertPinExpiry() {
        if (System.currentTimeMillis() > GTS_ROOT_R1_EXPIRY_MS - CERT_EXPIRY_WARN_MS) {
            Timber.w(
                "[WARN] ModelRepository.checkCertPinExpiry | GTS Root R1 cert pin expires <30 days. " +
                    "Update certificate pins BEFORE 2036-06-22. Run: openssl s_client " +
                    "-connect download.moonshine.ai:443 -showcerts | openssl x509 -pubkey " +
                    "-noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64",
            )
        }
    }

    /** Download states for each model. */
    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    /** Check if a model is downloaded. */
    fun isModelDownloaded(modelId: String): Boolean {
        val info = ModelInfo.findById(modelId) ?: run {
            Timber.w("[MODEL] isModelDownloaded | unknown modelId=%s", modelId)
            return false
        }
        // Multi-file models: all files in the subdirectory must exist.
        if (info.downloadFiles.isNotEmpty()) {
            val dir = File(modelsDir, modelId)
            val allPresent = info.downloadFiles.keys.all { filename ->
                val f = File(dir, filename)
                f.exists() && f.length() > 0
            }
            Timber.d("[MODEL] isModelDownloaded | modelId=%s multiFile=%b allPresent=%b",
                modelId, true, allPresent)
            return allPresent
        }
        val file = getModelFile(modelId)
        val downloaded = file.exists() && file.length() >= info.sizeBytes
        Timber.d("[MODEL] isModelDownloaded | modelId=%s exists=%b fileSize=%d expectedSize=%d downloaded=%b",
            modelId, file.exists(), if (file.exists()) file.length() else 0, info.sizeBytes, downloaded)
        return downloaded
    }

    /** Get the local file path for a model. */
    fun getModelFile(modelId: String): File {
        return File(modelsDir, "model-$modelId.bin")
    }

    /** Get the full path string for loading into a transcription engine. */
    fun getModelPath(modelId: String): String {
        val info = ModelInfo.findById(modelId)
        // Multi-file models live in a subdirectory named after the model ID.
        if (info != null && info.downloadFiles.isNotEmpty()) {
            return File(modelsDir, modelId).absolutePath
        }
        return getModelFile(modelId).absolutePath
    }

    /** List all downloaded models. */
    fun getDownloadedModels(): List<ModelInfo> {
        val downloaded = ModelInfo.AVAILABLE_MODELS.filter { isModelDownloaded(it.id) }
        Timber.d("[MODEL] getDownloadedModels | count=%d ids=%s", downloaded.size, downloaded.map { it.id })
        return downloaded
    }

    /** Get total disk usage of downloaded models (recurses into model subdirectories). */
    fun getTotalModelSize(): Long {
        val totalSize = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Timber.d("[MODEL] getTotalModelSize | totalBytes=%d", totalSize)
        return totalSize
    }

    /**
     * Download a model file with progress tracking and coroutine-based retry.
     * Supports resume on partial downloads.
     * A mutex prevents concurrent downloads from racing on the shared temp file.
     * Multi-file models (e.g. Moonshine) are downloaded into a subdirectory.
     */
    suspend fun downloadModel(modelId: String): Boolean {
        // Rate-limit: enforce minimum interval between attempts and per-hour quota.
        val now = System.currentTimeMillis()
        val last = lastDownloadAttemptMs
        if (last > 0 && (now - last) < MIN_DOWNLOAD_INTERVAL_SEC * 1_000L) {
            Timber.w("[DOWNLOAD] downloadModel | rate-limited: last attempt %d ms ago (min=%d s)", now - last, MIN_DOWNLOAD_INTERVAL_SEC)
            return false
        }
        // Prune history older than 1 hour, then check quota.
        synchronized(downloadHistoryMs) {
            while (downloadHistoryMs.isNotEmpty() && (now - downloadHistoryMs.peekFirst()) > 3_600_000L) {
                downloadHistoryMs.removeFirst()
            }
            if (downloadHistoryMs.size >= MAX_DOWNLOADS_PER_HOUR) {
                Timber.w("[DOWNLOAD] downloadModel | rate-limited: %d attempts in last hour (max=%d)", downloadHistoryMs.size, MAX_DOWNLOADS_PER_HOUR)
                return false
            }
            downloadHistoryMs.addLast(now)
        }
        lastDownloadAttemptMs = now

        return downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val info = ModelInfo.findById(modelId) ?: run {
                Timber.e("[DOWNLOAD] downloadModel | unknown modelId=%s", modelId)
                updateState(modelId, ModelDownloadState.Error("Unknown model"))
                return@withContext false
            }

            if (info.downloadFiles.isNotEmpty()) {
                return@withContext downloadMultiFileModel(modelId, info)
            }

            if (info.downloadUrl.isBlank()) {
                Timber.e("[DOWNLOAD] downloadModel | no downloadUrl for single-file model modelId=%s", modelId)
                updateState(modelId, ModelDownloadState.Error("No download URL configured"))
                return@withContext false
            }

            val targetFile = getModelFile(modelId)
            val tempFile = File(modelsDir, "model-$modelId.bin.tmp")

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
                            tempFile.delete()
                            Timber.e("[DOWNLOAD] downloadModel | HTTP client error code=%d modelId=%s — not retrying", code, modelId)
                            updateState(modelId, ModelDownloadState.Error("HTTP $code"))
                            return@withContext false
                        }
                        Timber.w("[DOWNLOAD] downloadModel | HTTP server error code=%d attempt=%d/%d modelId=%s", code, attempt, MAX_RETRIES, modelId)
                        throw IOException("HTTP $code")
                    }

                    val totalBytes = if (response.code == 206) {
                        Timber.d("[DOWNLOAD] downloadModel | partial content (206) resuming")
                        downloadedBytes + (response.body?.contentLength()?.takeIf { it >= 0 } ?: (info.sizeBytes - downloadedBytes))
                    } else {
                        downloadedBytes = 0 // Server doesn't support range, restart
                        Timber.d("[DOWNLOAD] downloadModel | full download (range not supported) modelId=%s", modelId)
                        tempFile.delete()
                        response.body?.contentLength() ?: info.sizeBytes
                    }

                    if (totalBytes <= 0L) {
                        Timber.w("[DOWNLOAD] downloadModel | unknown content length modelId=%s", modelId)
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    val outputStream = FileOutputStream(tempFile, downloadedBytes > 0)
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int

                    body.byteStream().use { input ->
                        outputStream.use { output ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val progress = if (totalBytes > 0L) {
                                    downloadedBytes.toFloat() / totalBytes
                                } else {
                                    0f
                                }
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
    }

    /**
     * Download a multi-file model (e.g. Moonshine ONNX) into a subdirectory.
     * Each file in [ModelInfo.downloadFiles] is downloaded individually.
     * Progress is reported as a weighted aggregate across all files.
     */
    private suspend fun downloadMultiFileModel(modelId: String, info: ModelInfo): Boolean {
        val dir = File(modelsDir, modelId)
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.e("[DOWNLOAD] downloadMultiFileModel | failed to create dir=%s", dir.absolutePath)
            updateState(modelId, ModelDownloadState.Error("Cannot create model directory"))
            return false
        }

        val files = info.downloadFiles
        val totalFiles = files.size
        var completedFiles = 0
        var lastException: IOException? = null

        for ((filename, url) in files) {
            val targetFile = File(dir, filename)
            val tempFile = File(dir, "$filename.tmp")

            if (targetFile.exists() && targetFile.length() > 0) {
                Timber.d("[DOWNLOAD] downloadMultiFileModel | skipping already downloaded file=%s", filename)
                completedFiles++
                continue
            }

            var backoffMs = INITIAL_BACKOFF_MS
            var downloaded = false
            for (attempt in 1..MAX_RETRIES) {
                try {
                    Timber.i("[DOWNLOAD] downloadMultiFileModel | file=%s attempt=%d/%d modelId=%s",
                        filename, attempt, MAX_RETRIES, modelId)

                    var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L
                    val requestBuilder = Request.Builder().url(url)
                    if (downloadedBytes > 0) {
                        requestBuilder.header("Range", "bytes=$downloadedBytes-")
                    }

                    val response = client.newCall(requestBuilder.build()).execute()

                    if (!response.isSuccessful && response.code != 206) {
                        val code = response.code
                        response.close()
                        if (code in 400..499) {
                            tempFile.delete()
                            Timber.e("[DOWNLOAD] downloadMultiFileModel | HTTP %d for file=%s — not retrying", code, filename)
                            updateState(modelId, ModelDownloadState.Error("HTTP $code for $filename"))
                            return false
                        }
                        throw IOException("HTTP $code")
                    }

                    val totalBytes = if (response.code == 206) {
                        downloadedBytes + (response.body?.contentLength()?.takeIf { it >= 0 } ?: 0L)
                    } else {
                        downloadedBytes = 0
                        tempFile.delete()
                        response.body?.contentLength() ?: 0
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile, downloadedBytes > 0).use { output ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val fileProgress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                                val overallProgress = (completedFiles + fileProgress) / totalFiles
                                updateState(modelId, ModelDownloadState.Downloading(overallProgress.coerceIn(0f, 1f)))
                            }
                        }
                    }

                    if (!tempFile.renameTo(targetFile)) {
                        throw IOException("Failed to rename $filename temp file")
                    }
                    val expectedHash = info.downloadFileHashes[filename]
                    if (expectedHash.isNullOrBlank()) {
                        if (!BuildConfig.DEBUG) {
                            targetFile.delete()
                            throw SecurityException(
                                "Missing SHA256 hash for $filename in release build. " +
                                    "Add the hash to ModelInfo.downloadFileHashes before shipping."
                            )
                        }
                        Timber.w("[WARN] downloadMultiFileModel | no SHA256 hash for file=%s — skipping verification", filename)
                    } else {
                        val actualHash = sha256(targetFile)
                        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                            targetFile.delete()
                            throw IOException(
                                "SHA256 mismatch for $filename: expected=$expectedHash actual=$actualHash"
                            )
                        }
                    }
                    Timber.i("[DOWNLOAD] downloadMultiFileModel | file=%s complete size=%d", filename, targetFile.length())
                    downloaded = true
                    break
                } catch (e: IOException) {
                    lastException = e
                    Timber.w(e, "[DOWNLOAD] downloadMultiFileModel | file=%s attempt=%d/%d failed", filename, attempt, MAX_RETRIES)
                    if (attempt < MAX_RETRIES) {
                        delay(backoffMs)
                        backoffMs *= 2
                    }
                }
            }

            if (!downloaded) {
                Timber.e(lastException, "[DOWNLOAD] downloadMultiFileModel | exhausted retries for file=%s modelId=%s", filename, modelId)
                updateState(modelId, ModelDownloadState.Error(lastException?.message ?: "Download failed for $filename"))
                return false
            }
            completedFiles++
        }

        updateState(modelId, ModelDownloadState.Downloaded)
        Timber.i("[DOWNLOAD] downloadMultiFileModel | all %d files complete modelId=%s", totalFiles, modelId)
        return true
    }

    /** Delete a downloaded model (single file or multi-file directory). */
    suspend fun deleteModel(modelId: String): Boolean = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            val info = ModelInfo.findById(modelId)

            // Multi-file models live in a subdirectory.
            if (info != null && info.downloadFiles.isNotEmpty()) {
                val dir = File(modelsDir, modelId)
                val deleted = dir.deleteRecursively()
                if (deleted) {
                    updateState(modelId, ModelDownloadState.NotDownloaded)
                    Timber.i("[MODEL] deleteModel | deleted multi-file modelId=%s dir=%s", modelId, dir.name)
                } else {
                    Timber.w("[MODEL] deleteModel | failed to delete multi-file modelId=%s dir=%s", modelId, dir.name)
                }
                return@withContext deleted
            }

            val file = getModelFile(modelId)
            val tempFile = File(modelsDir, "model-$modelId.bin.tmp")
            tempFile.delete()
            val deleted = file.delete()
            if (deleted) {
                updateState(modelId, ModelDownloadState.NotDownloaded)
                Timber.i("[MODEL] deleteModel | deleted modelId=%s file=%s", modelId, file.name)
            } else {
                Timber.w("[MODEL] deleteModel | failed to delete modelId=%s exists=%b file=%s", modelId, file.exists(), file.name)
            }
            deleted
        }
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

    /** Get the download state for a specific model. */
    fun getModelState(modelId: String): StateFlow<ModelDownloadState> {
        return _downloadStates
            .map { states -> states[modelId] ?: ModelDownloadState.NotDownloaded }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = _downloadStates.value[modelId] ?: ModelDownloadState.NotDownloaded
            )
    }

    /** Clear temporary cache files and incomplete downloads. */
    fun clearCache() {
        Timber.d("[MODEL] clearCache | clearing temporary files")
        modelsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
                Timber.d("[MODEL] clearCache | deleted temp file: %s", file.name)
            }
        }
    }

    private fun updateState(modelId: String, state: ModelDownloadState) {
        _downloadStates.value = _downloadStates.value + (modelId to state)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
