package com.safeword.android.ui.screens.onboarding

/** Step indices for the onboarding flow. Values must be ascending in flow order. */
internal const val STEP_WELCOME = 0
internal const val STEP_MIC = 1
internal const val STEP_MODEL = 2

// NOTE: STEP_OVERLAY = 3 is declared in OnboardingViewModel.companion (STEP_OVERLAY)
//       because tests reference it as OnboardingViewModel.STEP_OVERLAY. The logical
//       position for it is here, adjacent to STEP_MIC..STEP_A11Y.

internal const val STEP_A11Y = 4
internal const val STEP_COMPLETE = 5

/** Delay before showing the "Need help?" link. */
internal const val STUCK_HINT_DELAY_MS = 15_000L

/** Delay before showing the full stuck-detection hint card. */
internal const val STUCK_TIMER_MS = 30_000L
