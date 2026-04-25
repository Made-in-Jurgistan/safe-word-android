package com.safeword.android.data.model

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * ModelDownloadWorker — WorkManager worker for reliable model downloads.
 * Continues downloading even if the app is backgrounded or killed.
 *
 * Retry strategy: WorkManager retries up to 5 times on failure (handles process death /
 * network changes), while [ModelRepository.downloadModel] retries up to 3 times internally
 * with exponential backoff (handles transient server errors within a single attempt).
 * Maximum total attempts per enqueue cycle: 5 (Worker) × 3 (Repository) = 15.
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

        // Hard ceiling: give up after 5 attempts to avoid infinite battery drain.
        if (runAttemptCount >= 5) {
            Timber.e("[DOWNLOAD] doWork | reached max retries=%d, giving up | modelId=%s", runAttemptCount, modelId)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "[DOWNLOAD] doWork | error modelId=%s attempt=%d", modelId, runAttemptCount + 1)
            Result.retry()
        }
    }
}
