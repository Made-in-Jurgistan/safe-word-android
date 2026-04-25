package com.safeword.android.data.settings

import android.app.Application
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.safeword.android.util.settingsDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsRepositoryTest {

    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.settingsDataStore.edit { it.clear() }
        settingsRepository = SettingsRepository(context)
    }

    @Test
    fun `settings flow should emit defaults when no values stored`() = runTest {
        settingsRepository.settings.test {
            val settings = awaitItem()
            assertEquals("system", settings.darkMode)
            assertEquals(false, settings.overlayEnabled)
            assertEquals(true, settings.hapticFeedbackEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateDarkMode should persist valid values`() = runTest {
        settingsRepository.updateDarkMode("dark")

        settingsRepository.settings.test {
            val settings = awaitItem()
            assertEquals("dark", settings.darkMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateDarkMode should fallback to default for invalid values`() = runTest {
        settingsRepository.updateDarkMode("invalid")

        settingsRepository.settings.test {
            val settings = awaitItem()
            assertEquals("system", settings.darkMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateOverlayEnabled should persist value`() = runTest {
        settingsRepository.updateOverlayEnabled(true)

        settingsRepository.settings.test {
            val settings = awaitItem()
            assertEquals(true, settings.overlayEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateHapticFeedbackEnabled should persist value`() = runTest {
        settingsRepository.updateHapticFeedbackEnabled(false)

        settingsRepository.settings.test {
            val settings = awaitItem()
            assertEquals(false, settings.hapticFeedbackEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
