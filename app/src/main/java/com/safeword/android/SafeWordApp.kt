package com.safeword.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.safeword.android.data.model.ModelDownloadWorker
import com.safeword.android.data.model.ModelInfo
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Safe Word Android Application.
 * Mirrors desktop Safe Word Tauri app initialization.
 * Hilt provides the same singleton manager pattern as Arc<Manager> in Rust.
 *
 * Implements Configuration.Provider for HiltWorkerFactory
 * so @HiltWorker-annotated Workers get their dependencies injected.
 *
 * The default Moonshine model is enqueued for download automatically on every app
 * start via WorkManager. ExistingWorkPolicy.KEEP prevents duplicate concurrent
 * downloads; the Worker itself exits immediately if the file is already on disk.
 */
@HiltAndroidApp
class SafeWordApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("[LIFECYCLE] SafeWordApp.onCreate | debug=%b", BuildConfig.DEBUG)
        scheduleDefaultModelDownload()
    }

    /**
    * Enqueue a one-time WorkManager task to download the default Moonshine model.
     *
     * - Runs as soon as any network is available (CONNECTED constraint).
     * - KEEP policy: ignores the new request if a download is already
     *   queued or in-flight; restarts only after a terminal state.
     * - The Worker returns Result.success() immediately when the model file
     *   already exists, making every subsequent launch a fast no-op.
     */
    private fun scheduleDefaultModelDownload() {
        Timber.d("[INIT] scheduleDefaultModelDownload | modelId=%s", ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .setInputData(
                ModelDownloadWorker.buildInputData(ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID),
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "download_${ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID}",
            ExistingWorkPolicy.KEEP,
            downloadRequest,
        )
        Timber.d("[INIT] scheduleDefaultModelDownload | enqueued workName=download_%s policy=KEEP", ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID)
    }

}
