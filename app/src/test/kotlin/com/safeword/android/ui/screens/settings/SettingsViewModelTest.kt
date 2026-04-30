package com.safeword.android.ui.screens.settings

import app.cash.turbine.test
import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        settingsRepository = mockk {
            every { settings } returns flowOf(AppSettings())
            coEvery { updateDarkMode(any()) } just Runs
            coEvery { updateOverlayEnabled(any()) } just Runs
        }
        viewModel = SettingsViewModel(settingsRepository)
    }

    // --- State ---

    @Test
    fun `settings flow emits AppSettings defaults on collection`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.settings.test {
                val s = awaitItem()
                assertEquals("system", s.darkMode)
                assertFalse(s.overlayEnabled)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- Delegates ---

    @Test
    fun `updateDarkMode delegates to repository`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.updateDarkMode("dark")
        advanceUntilIdle()
        coVerify { settingsRepository.updateDarkMode("dark") }
    }

    @Test
    fun `updateOverlayEnabled delegates to repository`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.updateOverlayEnabled(true)
        advanceUntilIdle()
        coVerify { settingsRepository.updateOverlayEnabled(true) }
    }

}
