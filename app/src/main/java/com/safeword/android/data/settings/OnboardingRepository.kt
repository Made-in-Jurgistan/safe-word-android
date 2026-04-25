package com.safeword.android.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.safeword.android.util.onboardingDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OnboardingRepository — persists first-launch onboarding state via Jetpack DataStore.
 *
 * Separated from [SettingsRepository] because onboarding state is lifecycle-once
 * (never accessed after onboarding completes), whereas runtime settings are read
 * throughout the app's lifetime.
 */
@Singleton
class OnboardingRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val ONBOARDING_STEP = intPreferencesKey("onboarding_step")
        val SKIPPED_STEPS = stringPreferencesKey("onboarding_skipped_steps")
    }

    val onboardingComplete: Flow<Boolean> = context.onboardingDataStore.data
        .map { prefs -> prefs[Keys.ONBOARDING_COMPLETE] ?: false }

    suspend fun updateOnboardingComplete(complete: Boolean) {
        Timber.i("[SETTINGS] updateOnboardingComplete | complete=%b", complete)
        context.onboardingDataStore.edit { it[Keys.ONBOARDING_COMPLETE] = complete }
    }

    /** Last completed onboarding step (1-based). 0 = not started. Survives process death. */
    val onboardingStep: Flow<Int> = context.onboardingDataStore.data
        .map { prefs -> prefs[Keys.ONBOARDING_STEP] ?: 0 }

    suspend fun updateOnboardingStep(step: Int) {
        Timber.i("[SETTINGS] updateOnboardingStep | step=%d", step)
        context.onboardingDataStore.edit { it[Keys.ONBOARDING_STEP] = step }
    }

    /** Comma-separated list of skipped step numbers (e.g., "2,4"). */
    val skippedSteps: Flow<Set<Int>> = context.onboardingDataStore.data
        .map { prefs ->
            prefs[Keys.SKIPPED_STEPS]
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                ?: emptySet()
        }

    suspend fun updateSkippedSteps(steps: Set<Int>) {
        val value = steps.sorted().joinToString(",")
        Timber.i("[SETTINGS] updateSkippedSteps | steps=%s", value)
        context.onboardingDataStore.edit { it[Keys.SKIPPED_STEPS] = value }
    }
}
