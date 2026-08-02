package com.safeword.android.data.model

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        /** Per-model file recording first-seen SHA-256 hashes (TOFU). */
        private const val CHECKSUMS_FILE = ".checksums"
        /** Required free space on top of the model size before downloading (bytes). */
        private const val DOWNLOAD_FREE_SPACE_HEADROOM = 64L * 1024 * 1024
        /** Hosts allowed as redirect targets — extend when a new CDN is observed. */
        private val REDIRECT_ALLOWLIST = setOf(
            "huggingface.co",
            "cdn-lfs.huggingface.co",
            "cdn-lfs-us-1.huggingface.co",
            "download.moonshine.ai",
        )
        /** Max redirects we'll follow manually. */
        private const val MAX_REDIRECTS = 5
    }

    private val modelsDir: File
        get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

    /** Serialises full download attempts so partial-write tempfiles don't collide. */
    private val downloadMutex = Mutex()

    /** Serialises mutations to [_downloadStates] from any caller (download / refresh / delete). */
    private val stateMutex = Mutex()

    // TLS chain validation is delegated to the system trust store. End-to-end
    // integrity of every model file is enforced after the body is fully written
    // by comparing against publisher-provided SHA-256 hashes (ModelInfo.sha256
    // / componentSha256) or, when the publisher does not supply one, the
    // Trust-On-First-Use record persisted in CHECKSUMS_FILE. A MITM that
    // tampers with bytes in flight cannot pass this check without producing a
    // pre-image-resistant SHA-256 collision against the intended model.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Manual redirect handling: every Location hop is validated against
        // REDIRECT_ALLOWLIST so downloads cannot escape to an unknown host.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
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

            val requiredBytes = info.sizeBytes + DOWNLOAD_FREE_SPACE_HEADROOM
            val freeBytes = availableBytes(modelsDir)
            if (info.sizeBytes > 0 && freeBytes < requiredBytes) {
                Timber.e(
                    "[DOWNLOAD] downloadModel | insufficient free space modelId=%s required=%d free=%d",
                    modelId, requiredBytes, freeBytes,
                )
                updateState(
                    modelId,
                    ModelDownloadState.Error("Not enough free space (need ${requiredBytes / 1_000_000} MB)"),
                )
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
                        if (info.componentSha256.isEmpty()) {
                            Timber.w(
                                "[DOWNLOAD] downloadModel | no component SHA256 map for modelId=%s " +
                                    "— integrity checks unavailable",
                                modelId,
                            )
                        }
                        val modelDir = getModelDir(modelId).also { it.mkdirs() }
                        var completed = 0
                        val total = components.size.toFloat()

                        // TOFU: persisted hashes from the first successful download.
                        // Used when ModelInfo.componentSha256 is empty (publisher didn't ship hashes).
                        val tofuHashes = readTofuHashes(modelDir).toMutableMap()

                        for (component in components) {
                            val targetFile = File(modelDir, component)
                            val expectedHash = info.componentSha256[component] ?: tofuHashes[component]
                            if (targetFile.exists() && targetFile.length() > 0L) {
                                if (expectedHash != null) {
                                    val actualHash = sha256(targetFile)
                                    if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                                        Timber.w(
                                            "[DOWNLOAD] downloadModel | SHA256 mismatch on existing " +
                                                "component modelId=%s component=%s — re-downloading",
                                            modelId, component,
                                        )
                                        targetFile.delete()
                                    } else {
                                        completed++
                                        updateState(
                                            modelId,
                                            ModelDownloadState.Downloading((completed / total).coerceIn(0f, 1f)),
                                        )
                                        continue
                                    }
                                } else {
                                    completed++
                                    updateState(
                                        modelId,
                                        ModelDownloadState.Downloading((completed / total).coerceIn(0f, 1f)),
                                    )
                                    continue
                                }
                            }

                            if (expectedHash == null) {
                                Timber.w(
                                    "[DOWNLOAD] downloadModel | no SHA256 (config or TOFU) for component " +
                                        "modelId=%s component=%s — TOFU will record after download",
                                    modelId, component,
                                )
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

                            val computedHash = sha256(targetFile)
                            if (expectedHash != null) {
                                if (!computedHash.equals(expectedHash, ignoreCase = true)) {
                                    Timber.e("[DOWNLOAD] downloadModel | SHA256 mismatch modelId=%s component=%s",
                                        modelId, component)
                                    targetFile.delete()
                                    throw IOException("SHA256 verification failed for component: $component")
                                }
                                Timber.i(
                                    "[DOWNLOAD] downloadModel | SHA256 verified modelId=%s component=%s",
                                    modelId, component,
                                )
                            } else {
                                // TOFU: record this hash so subsequent loads can verify integrity.
                                tofuHashes[component] = computedHash
                                Timber.i(
                                    "[DOWNLOAD] downloadModel | TOFU recorded SHA256 modelId=%s component=%s",
                                    modelId, component,
                                )
                            }

                            completed++
                        }

                        if (info.componentSha256.isEmpty() && tofuHashes.isNotEmpty()) {
                            writeTofuHashes(modelDir, tofuHashes)
                        }
                    }

                    updateState(modelId, ModelDownloadState.Downloaded)
                    Timber.i("[DOWNLOAD] downloadModel | complete modelId=%s", modelId)
                    return@withContext true
                } catch (e: IOException) {
                    lastException = e
                    Timber.w(
                        e,
                        "[DOWNLOAD] downloadModel | attempt=%d/%d failed modelId=%s",
                        attempt, MAX_RETRIES, modelId,
                    )
                    if (attempt < MAX_RETRIES) {
                        Timber.d("[DOWNLOAD] downloadModel | retrying in %dms", backoffMs)
                        delay(backoffMs)
                        backoffMs *= 2
                    }
                }
            }

            Timber.e(
                lastException,
                "[DOWNLOAD] downloadModel | exhausted %d retries modelId=%s",
                MAX_RETRIES, modelId,
            )
            updateState(
                modelId,
                ModelDownloadState.Error(lastException?.message ?: "Download failed after $MAX_RETRIES attempts"),
            )
            false
        }
    }

    private suspend fun downloadSingleFile(
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
                Timber.e(
                    "[DOWNLOAD] downloadModel | SHA256 mismatch modelId=%s expected=%s actual=%s",
                    modelId, expectedHash, actualHash,
                )
                targetFile.delete()
                throw IOException("SHA256 verification failed")
            }
            Timber.i("[DOWNLOAD] downloadModel | SHA256 verified modelId=%s", modelId)
        } else {
            Timber.w("[DOWNLOAD] downloadModel | no SHA256 configured for modelId=%s", modelId)
        }
    }

    private suspend fun downloadComponent(
        modelId: String,
        component: String,
        url: String,
        targetFile: File,
        tempFile: File,
        progressBase: Float,
        progressScale: Float,
    ) {
        var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

        executeWithRedirects(url, downloadedBytes).use { response ->
            if (!response.isSuccessful && response.code != 206) {
                handleDownloadErrorResponse(response, modelId, component)
            }

            // 5.8: Parse Content-Range so totalBytes reflects the *full* file size,
            // not just the bytes remaining after the resume offset.
            // * NOTE: For non-206 responses, the temp file is deleted and
            //   downloadedBytes is reset to 0 so the output stream opens in
            //   truncate mode (not append).
            var totalBytes = downloadedBytes
            if (response.code == 206) {
                totalBytes = resolveTotalBytes206(response, downloadedBytes, component)
            } else {
                tempFile.delete()
                downloadedBytes = 0
                totalBytes = response.body?.contentLength() ?: 0L
            }

            val body = response.body ?: throw IOException("Empty response body")
            val outputStream = FileOutputStream(tempFile, downloadedBytes > 0)
            downloadedBytes = streamBodyToFile(
                body, outputStream, downloadedBytes, totalBytes,
                modelId, progressBase, progressScale,
            )
        }

        atomicMove(tempFile, targetFile)
    }

    /** Stream the response body to the output file, reporting progress. */
    private suspend fun streamBodyToFile(
        body: okhttp3.ResponseBody,
        outputStream: FileOutputStream,
        initialDownloadedBytes: Long,
        totalBytes: Long,
        modelId: String,
        progressBase: Float,
        progressScale: Float,
    ): Long {
        var downloadedBytes = initialDownloadedBytes
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            outputStream.use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    val fraction = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                    val progress = progressBase + (progressScale * fraction)
                    updateState(modelId, ModelDownloadState.Downloading(progress.coerceIn(0f, 1f)))
                }
            }
        }
        return downloadedBytes
    }

    /** Handle a non-2xx / non-206 response by logging + throwing. */
    private suspend fun handleDownloadErrorResponse(
        response: Response,
        modelId: String,
        component: String,
    ) {
        val code = response.code
        if (code in 400..499) {
            Timber.e(
                "[DOWNLOAD] downloadComponent | HTTP client error code=%d modelId=%s " +
                    "component=%s — not retrying",
                code, modelId, component,
            )
            updateState(modelId, ModelDownloadState.Error("HTTP $code"))
            throw IOException("HTTP $code")
        }
        Timber.w(
            "[DOWNLOAD] downloadComponent | HTTP server error code=%d modelId=%s component=%s",
            code, modelId, component,
        )
        throw IOException("HTTP $code")
    }

    /** Resolve the total content length from a 206 partial-content response. */
    private fun resolveTotalBytes206(
        response: Response,
        downloadedBytes: Long,
        component: String,
    ): Long {
        val contentRange = response.header("Content-Range")
        val parsedTotal = parseContentRangeTotal(contentRange)
        if (parsedTotal > 0L) {
            Timber.d(
                "[DOWNLOAD] downloadComponent | partial content (206) total=%d component=%s",
                parsedTotal, component,
            )
            return parsedTotal
        }
        return downloadedBytes + (response.body?.contentLength() ?: 0)
    }

    /**
     * Execute [url] following redirects manually. Each Location is validated against
     * [REDIRECT_ALLOWLIST] so a download cannot escape the set of known model hosts.
     * The returned response body is open and must be `.use {}`-d by callers.
     */
    private fun executeWithRedirects(url: String, resumeOffset: Long): Response {
        var currentUrl = url
        repeat(MAX_REDIRECTS) {
            val builder = Request.Builder().url(currentUrl)
            if (resumeOffset > 0) {
                builder.header("Range", "bytes=$resumeOffset-")
            }
            val response = client.newCall(builder.build()).execute()
            val code = response.code
            if (code in 300..399 && code != 304) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw IOException("HTTP $code with no Location header")
                }
                val resolved = resolveRedirect(currentUrl, location)
                val host = resolved.toHttpUrlOrNull()?.host
                    ?: throw IOException("Invalid redirect URL: $location")
                if (host !in REDIRECT_ALLOWLIST) {
                    throw IOException("Refusing redirect to host outside allowlist: $host")
                }
                Timber.i("[DOWNLOAD] redirect | $currentUrl → $resolved")
                currentUrl = resolved
                return@repeat
            }
            return response
        }
        throw IOException("Exceeded max redirects ($MAX_REDIRECTS)")
    }

    private fun resolveRedirect(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        val baseUrl = base.toHttpUrlOrNull() ?: throw IOException("Invalid base URL: $base")
        return baseUrl.resolve(location)?.toString()
            ?: throw IOException("Cannot resolve redirect '$location' against '$base'")
    }

    /** Returns the total length encoded in a `Content-Range` header, or 0 if unknown. */
    internal fun parseContentRangeTotal(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        // Format: bytes <start>-<end>/<total>  (or */<total> for unsatisfiable ranges)
        val slash = header.lastIndexOf('/')
        if (slash <= 0 || slash >= header.length - 1) return 0L
        val totalStr = header.substring(slash + 1).trim()
        if (totalStr == "*") return 0L
        return totalStr.toLongOrNull() ?: 0L
    }

    /** Atomically move [src] over [dst]. Falls back to renameTo if ATOMIC_MOVE is unsupported. */
    private fun atomicMove(src: File, dst: File) {
        try {
            Files.move(
                src.toPath(),
                dst.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: RuntimeException) {
            Timber.w(e, "[DOWNLOAD] atomicMove | ATOMIC_MOVE unavailable, falling back to renameTo")
            if (!src.renameTo(dst)) {
                throw IOException("Failed to rename ${src.name} → ${dst.name}", e)
            }
        }
    }

    /** Returns free bytes available under [dir]'s mount point. */
    private fun availableBytes(dir: File): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (e: RuntimeException) {
        Timber.w(e, "[DOWNLOAD] availableBytes | StatFs failed dir=%s", dir.absolutePath)
        Long.MAX_VALUE
    }

    private fun readTofuHashes(modelDir: File): Map<String, String> {
        val file = File(modelDir, CHECKSUMS_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            file.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .mapNotNull { line ->
                    val sep = line.indexOf(' ')
                    if (sep <= 0) null else line.substring(0, sep) to line.substring(sep + 1).trim()
                }
                .toMap()
        } catch (e: RuntimeException) {
            Timber.w(e, "[TOFU] readTofuHashes | failed file=%s", file.absolutePath)
            emptyMap()
        }
    }

    private fun writeTofuHashes(modelDir: File, hashes: Map<String, String>) {
        val file = File(modelDir, CHECKSUMS_FILE)
        try {
            val tmp = File(modelDir, "$CHECKSUMS_FILE.part")
            tmp.bufferedWriter().use { w ->
                w.write("# Trust-on-first-use SHA-256 hashes recorded by Safe Word.\n")
                for ((component, hash) in hashes.entries.sortedBy { it.key }) {
                    w.write("$hash $component\n")
                }
            }
            atomicMove(tmp, file)
            Timber.i("[TOFU] writeTofuHashes | %d entries → %s", hashes.size, file.absolutePath)
        } catch (e: RuntimeException) {
            Timber.e(e, "[TOFU] writeTofuHashes | failed file=%s", file.absolutePath)
        }
    }

    /** Delete a downloaded model. */
    suspend fun deleteModel(modelId: String): Boolean {
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
    suspend fun refreshStates() {
        Timber.d("[MODEL] refreshStates | scanning available models")
        val states = mutableMapOf<String, ModelDownloadState>()
        for (model in ModelInfo.AVAILABLE_MODELS) {
            states[model.id] = if (isModelDownloaded(model.id)) {
                ModelDownloadState.Downloaded
            } else {
                ModelDownloadState.NotDownloaded
            }
        }
        stateMutex.withLock { _downloadStates.value = states }
        Timber.d("[MODEL] refreshStates | totalModels=%d downloaded=%d",
            states.size, states.count { it.value is ModelDownloadState.Downloaded })
    }

    /**
     * Single ingress point for [_downloadStates] mutations. All call sites are
     * already inside suspend functions, so this suspends on [stateMutex] instead
     * of blocking the caller. Writes are infrequent compared to reads.
     */
    private suspend fun updateState(modelId: String, state: ModelDownloadState) {
        stateMutex.withLock {
            _downloadStates.value = _downloadStates.value + (modelId to state)
        }
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
