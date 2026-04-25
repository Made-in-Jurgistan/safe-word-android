package com.safeword.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.safeword.android.data.model.ModelDownloadWorker
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.settings.OnboardingRepository
import com.safeword.android.di.ApplicationScope
import com.safeword.android.transcription.DormancyWorker
import java.util.concurrent.TimeUnit
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Safe Word Android Application.
 *
 * Implements Configuration.Provider for HiltWorkerFactory
 * so @HiltWorker-annotated Workers get their dependencies injected.
 *
 * The default models are enqueued for download automatically on every app
 * start via WorkManager. ExistingWorkPolicy.KEEP prevents duplicate concurrent
 * downloads; the Worker itself exits immediately if the file is already on disk.
 */
@HiltAndroidApp
class SafeWordApp : Application(), Configuration.Provider {

    // Hilt requires field injection for Application subclasses — constructor injection
    // is not supported by @HiltAndroidApp. These are the only production field injections.
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var onboardingRepository: OnboardingRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority < android.util.Log.WARN) return
                    android.util.Log.println(priority, tag ?: "SafeWord", message)
                    t?.let { android.util.Log.e(tag ?: "SafeWord", message, it) }
                }
            })
        }
        Timber.i("[LIFECYCLE] SafeWordApp.onCreate | debug=%b", BuildConfig.DEBUG)
        scheduleDefaultModelDownloadAfterOnboarding()
        scheduleDormancyCheck()
    }

    /** Schedule a weekly dormancy sweep. KEEP policy means it enqueues only once; subsequent app
     * starts are no-ops if the periodic work is already queued or running. */
    private fun scheduleDormancyCheck() {
        val request = PeriodicWorkRequestBuilder<DormancyWorker>(7, TimeUnit.DAYS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DormancyWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Timber.d("[INIT] scheduleDormancyCheck | workName=%s policy=KEEP", DormancyWorker.WORK_NAME)
    }

    /**
     * Gate the automatic model download behind two privacy/cost invariants:
     *
     * 1. **Onboarding must be complete** — during onboarding the user drives the download
     *    through [com.safeword.android.ui.screens.onboarding.OnboardingViewModel] which calls
     *    [com.safeword.android.data.model.ModelRepository.downloadModel] directly. Before that,
     *    a 246 MB download without user consent violates first-run expectations.
     * 2. **UNMETERED network** — the default STT model is ~246 MB; downloading over metered
     *    cellular would silently consume data. If the user explicitly deletes and wants a
     *    re-download, they can trigger it from settings.
     *
     * KEEP policy + the Worker's fast-path "file already on disk" check make re-scheduling
     * on every app start safe and near-free.
     */
    private fun scheduleDefaultModelDownloadAfterOnboarding() {
        applicationScope.launch {
            // Wait for the first `true` emission — first launch will never trigger a download,
            // but re-installs with preserved app data or repeat launches enqueue immediately.
            onboardingRepository.onboardingComplete
                .distinctUntilChanged()
                .filter { it }
                .take(1)
                .first()
            enqueueModelDownloads()
        }
    }

    private fun enqueueModelDownloads() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val modelIds = listOf(
            ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID,
        )

        val workManager = WorkManager.getInstance(this)
        for (modelId in modelIds) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(ModelDownloadWorker.buildInputData(modelId))
                .build()
            workManager.enqueueUniqueWork(
                "download_$modelId",
                ExistingWorkPolicy.KEEP,
                request,
            )
            Timber.d("[INIT] enqueueModelDownloads | enqueued workName=download_%s policy=KEEP network=UNMETERED", modelId)
        }
    }

}
