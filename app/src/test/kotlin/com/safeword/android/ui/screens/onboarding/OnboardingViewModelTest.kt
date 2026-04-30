package com.safeword.android.ui.screens.onboarding

import app.cash.turbine.test
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var viewModel: OnboardingViewModel

    private val downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    private val onboardingCompleteFlow = MutableStateFlow(false)

    @Before
    fun setUp() {
        settingsRepository = mockk {
            every { onboardingComplete } returns onboardingCompleteFlow
            coEvery { updateOnboardingComplete(any()) } just Runs
            coEvery { updateOverlayEnabled(any()) } just Runs
        }
        modelRepository = mockk {
            every { isModelDownloaded(OnboardingViewModel.DEFAULT_MODEL_ID) } returns false
            every { refreshStates() } just Runs
        }
        every { modelRepository.downloadStates } returns downloadStates

        viewModel = OnboardingViewModel(settingsRepository, modelRepository)
    }

    @Test
    fun `isOnboardingComplete returns false when prefs flag not set`() {
        assertFalse(viewModel.isOnboardingComplete())
    }

    @Test
    fun `markOnboardingComplete writes flag via settings repository`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.markOnboardingComplete()
            advanceUntilIdle()
            coVerify { settingsRepository.updateOnboardingComplete(true) }
        }

    @Test
    fun `modelReady is false initially when model not downloaded`() {
        assertFalse(viewModel.modelReady.value)
    }

    @Test
    fun `downloadState transitions to Downloaded when repository emits Downloaded`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.modelReady.test {
                assertFalse(awaitItem())
                downloadStates.value = mapOf(
                    OnboardingViewModel.DEFAULT_MODEL_ID to ModelDownloadState.Downloaded,
                )
                advanceUntilIdle()
                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ensureModelDownloaded triggers download and sets modelReady on success`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery {
                modelRepository.downloadModel(OnboardingViewModel.DEFAULT_MODEL_ID)
            } returns true

            viewModel.ensureModelDownloaded()
            advanceUntilIdle()

            assertTrue(viewModel.modelReady.value)
        }

    @Test
    fun `ensureModelDownloaded is idempotent - concurrent calls trigger download once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var callCount = 0
            coEvery { modelRepository.downloadModel(OnboardingViewModel.DEFAULT_MODEL_ID) } coAnswers {
                callCount++
                true
            }

            viewModel.ensureModelDownloaded()
            viewModel.ensureModelDownloaded()
            advanceUntilIdle()

            assertEquals(1, callCount)
        }

    @Test
    fun `ensureModelDownloaded is no-op when model already ready`() =
        runTest(mainDispatcherRule.testDispatcher) {
            every { modelRepository.isModelDownloaded(OnboardingViewModel.DEFAULT_MODEL_ID) } returns true
            val vm = OnboardingViewModel(settingsRepository, modelRepository)
            advanceUntilIdle()

            assertTrue(vm.modelReady.value)

            vm.ensureModelDownloaded()
            advanceUntilIdle()

            coVerify(exactly = 0) { modelRepository.downloadModel(any()) }
        }
}
