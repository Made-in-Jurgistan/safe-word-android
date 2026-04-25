package com.safeword.android.ui.screens.onboarding

import android.content.Context
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.data.model.ModelRepository
import com.safeword.android.data.settings.OnboardingRepository
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.service.AccessibilityStateHolder
import com.safeword.android.util.InstallSourceDetector
import com.safeword.android.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var onboardingRepository: OnboardingRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var accessibilityState: AccessibilityStateHolder
    private lateinit var appContext: Context

    private fun createViewModel(): OnboardingViewModel = OnboardingViewModel(
        onboardingRepository, settingsRepository, modelRepository, accessibilityState, appContext,
    )

    @Before
    fun setup() {
        onboardingRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)
        accessibilityState = mockk(relaxed = true)
        appContext = mockk(relaxed = true)

        // Satisfy init block subscriptions
        every { onboardingRepository.onboardingComplete } returns flowOf(false)
        every { onboardingRepository.skippedSteps } returns flowOf(emptySet())
        every { onboardingRepository.onboardingStep } returns flowOf(0)
        every { modelRepository.downloadStates } returns MutableStateFlow(emptyMap())
        coEvery { modelRepository.isModelDownloaded(any()) } returns true

        every { accessibilityState.serviceActive } returns MutableStateFlow(false)
        every { accessibilityState.isActive() } returns false

        mockkObject(InstallSourceDetector)
        every { InstallSourceDetector.isSideloaded(any()) } returns false
    }

    @After
    fun tearDown() {
        unmockkObject(InstallSourceDetector)
    }

    @Test
    fun `isOnboardingComplete returns false when not completed`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.isOnboardingComplete())
    }

    @Test
    fun `restoreStep returns saved step from repository`() = runTest {
        every { onboardingRepository.onboardingStep } returns flowOf(2)
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.restoreStep())
    }

    @Test
    fun `restoreStep defaults to 1 when saved step is 0`() = runTest {
        every { onboardingRepository.onboardingStep } returns flowOf(0)
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(1, viewModel.restoreStep())
    }

    @Test
    fun `persistStep calls repository updateOnboardingStep`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.persistStep(3)
        advanceUntilIdle()

        coVerify { onboardingRepository.updateOnboardingStep(3) }
    }

    @Test
    fun `skipStep updates skipped set and calls repository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.skipStep(OnboardingViewModel.STEP_OVERLAY)
        advanceUntilIdle()

        coVerify { onboardingRepository.updateSkippedSteps(setOf(OnboardingViewModel.STEP_OVERLAY)) }
    }

    @Test
    fun `markOnboardingComplete calls repository`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.markOnboardingComplete()
        advanceUntilIdle()

        coVerify { onboardingRepository.updateOnboardingComplete(true) }
    }

    @Test
    fun `markOnboardingComplete enables overlay when not skipped`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.markOnboardingComplete()
        advanceUntilIdle()

        coVerify { settingsRepository.updateOverlayEnabled(true) }
    }

    @Test
    fun `markOnboardingComplete does not enable overlay when skipped`() = runTest {
        every { onboardingRepository.skippedSteps } returns flowOf(setOf(OnboardingViewModel.STEP_OVERLAY))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.markOnboardingComplete()
        advanceUntilIdle()

        coVerify(exactly = 0) { settingsRepository.updateOverlayEnabled(any()) }
    }
}
