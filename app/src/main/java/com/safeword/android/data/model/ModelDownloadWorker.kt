package com.safeword.android.data.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.safeword.android.MainActivity
import com.safeword.android.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

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
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        private const val CHANNEL_ID = "safeword_model_downloads"
        private const val NOTIFICATION_ID = 3

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

        return coroutineScope {
            createNotificationChannel()
            setForeground(createForegroundInfo(0))
            setProgress(progressData(0))

            val progressJob = launch {
                modelRepository.downloadStates
                    .map { states ->
                        (states[modelId] as? ModelDownloadState.Downloading)
                            ?.progress
                            ?.let { (it * 100).roundToInt().coerceIn(0, 100) }
                    }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collect { percent ->
                        setProgress(progressData(percent))
                        setForeground(createForegroundInfo(percent))
                    }
            }

            try {
                val success = modelRepository.downloadModel(modelId)
                if (success) {
                    setProgress(progressData(100))
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
            } finally {
                progressJob.cancelAndJoin()
            }
        }
    }

    private fun progressData(percent: Int): Data {
        return Data.Builder()
            .putInt(KEY_PROGRESS_PERCENT, percent.coerceIn(0, 100))
            .build()
    }

    private fun createForegroundInfo(percent: Int): ForegroundInfo {
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(percent),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.model_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.model_download_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(percent: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.model_download_notification_title))
            .setContentText(applicationContext.getString(R.string.model_download_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()
    }
}
