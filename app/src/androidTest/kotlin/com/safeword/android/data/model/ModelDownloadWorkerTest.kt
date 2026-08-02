package com.safeword.android.data.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloadWorkerTest {
    @Test
    fun doWork_returns_success_when_model_already_downloaded() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = mockk<ModelRepository>()
        val modelId = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        every { repo.isModelDownloaded(modelId) } returns true

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(ModelDownloadWorker.buildInputData(modelId))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? {
                        return if (workerClassName == ModelDownloadWorker::class.java.name) {
                            ModelDownloadWorker(appContext, workerParameters, repo)
                        } else {
                            null
                        }
                    }
                },
            )
            .build()

        val result = (worker as CoroutineWorker).doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun doWork_returns_success_when_repository_download_succeeds() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = mockk<ModelRepository>()
        val modelId = ModelInfo.MOONSHINE_SMALL_STREAMING_MODEL_ID
        every { repo.isModelDownloaded(modelId) } returns false
        coEvery { repo.downloadModel(modelId) } returns true
        every { repo.downloadStates } returns MutableStateFlow(emptyMap())

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(ModelDownloadWorker.buildInputData(modelId))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker? {
                        return if (workerClassName == ModelDownloadWorker::class.java.name) {
                            ModelDownloadWorker(appContext, workerParameters, repo)
                        } else {
                            null
                        }
                    }
                },
            )
            .build()

        val result = (worker as CoroutineWorker).doWork()
        assertTrue(result is ListenableWorker.Result.Success)
    }
}
