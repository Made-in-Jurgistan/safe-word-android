package com.safeword.android.data.model

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * ModelDownloadWorker — WorkManager worker for reliable model downloads.
 * Continues downloading even if the app is backgrounded or killed.
 *
 * Retry strategy: WorkManager retries up to 3 times on failure (handles process death /
 * network changes), while [ModelRepository.downloadModel] retries up to 3 times internally
 * with exponential backoff (handles transient server errors within a single attempt).
 * Maximum total attempts per enqueue cycle: 3 (Worker) × 3 (Repository) = 9.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelRepository: ModelRepository,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_MODEL_ID = "model_id"

        fun buildInputData(modelId: String): Data {
            return Data.Builder()
                .putString(KEY_MODEL_ID, modelId)
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: run {
                Timber.e("[DOWNLOAD] doWork | modelId is null — failing")
                return Result.failure()
            }

        // Fast-path: skip network entirely if model already on disk
        if (modelRepository.isModelDownloaded(modelId)) {
            Timber.i("[INIT] doWork | model already present, skipping download modelId=%s", modelId)
            return Result.success()
        }

        Timber.i("[DOWNLOAD] doWork | starting download modelId=%s attempt=%d", modelId, runAttemptCount + 1)

        return try {
            val success = modelRepository.downloadModel(modelId)
            if (success) {
                Timber.i("[DOWNLOAD] doWork | complete modelId=%s", modelId)
                Result.success()
            } else {
                Timber.w("[DOWNLOAD] doWork | failed, will retry modelId=%s attempt=%d", modelId, runAttemptCount + 1)
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "[DOWNLOAD] doWork | error modelId=%s attempt=%d", modelId, runAttemptCount + 1)
            if (runAttemptCount < 3) {
                Timber.d("[DOWNLOAD] doWork | scheduling retry attempt=%d", runAttemptCount + 2)
                Result.retry()
            } else {
                Timber.e("[DOWNLOAD] doWork | exhausted retries modelId=%s maxAttempts=3", modelId)
                Result.failure()
            }
        }
    }
}
