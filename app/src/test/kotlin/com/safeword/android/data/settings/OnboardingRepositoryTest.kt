package com.safeword.android.data.settings

import android.app.Application
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.safeword.android.util.onboardingDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingRepositoryTest {

    private lateinit var onboardingRepository: OnboardingRepository

    @Before
    fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.onboardingDataStore.edit { it.clear() }
        onboardingRepository = OnboardingRepository(context)
    }

    @Test
    fun `onboardingComplete should default to false`() = runTest {
        onboardingRepository.onboardingComplete.test {
            val complete = awaitItem()
            assertFalse(complete)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateOnboardingComplete should persist value`() = runTest {
        onboardingRepository.updateOnboardingComplete(true)

        onboardingRepository.onboardingComplete.test {
            val complete = awaitItem()
            assertTrue(complete)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboardingStep should default to 0`() = runTest {
        onboardingRepository.onboardingStep.test {
            val step = awaitItem()
            assertEquals(0, step)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateOnboardingStep should persist value`() = runTest {
        onboardingRepository.updateOnboardingStep(3)

        onboardingRepository.onboardingStep.test {
            val step = awaitItem()
            assertEquals(3, step)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `skippedSteps should default to empty`() = runTest {
        onboardingRepository.skippedSteps.test {
            val skipped = awaitItem()
            assertEquals(emptySet(), skipped)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateSkippedSteps should persist set`() = runTest {
        val steps = setOf(2, 4, 7)
        onboardingRepository.updateSkippedSteps(steps)

        onboardingRepository.skippedSteps.test {
            val skipped = awaitItem()
            assertEquals(steps, skipped)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
