package com.safeword.android.ui.screens.settings

import app.cash.turbine.test
import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.util.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.settings } returns flowOf(AppSettings())
        viewModel = SettingsViewModel(settingsRepository)
    }

    @Test
    fun `initial state should load from repository`() = runTest {
        val expected = AppSettings(
            darkMode = "dark",
            overlayEnabled = false,
            hapticFeedbackEnabled = true,
        )
        every { settingsRepository.settings } returns flowOf(expected)
        viewModel = SettingsViewModel(settingsRepository)

        viewModel.settings.test {
            dispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(expected, expectMostRecentItem())
        }
    }

    @Test
    fun `updateDarkMode should call repository update`() = runTest {
        viewModel.updateDarkMode("light")
        dispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        coVerify { settingsRepository.updateDarkMode("light") }
    }

    @Test
    fun `updateOverlayEnabled should call repository update`() = runTest {
        viewModel.updateOverlayEnabled(false)
        dispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        coVerify { settingsRepository.updateOverlayEnabled(false) }
    }

    @Test
    fun `updateHapticFeedbackEnabled should call repository update`() = runTest {
        viewModel.updateHapticFeedbackEnabled(false)
        dispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        coVerify { settingsRepository.updateHapticFeedbackEnabled(false) }
    }
}
