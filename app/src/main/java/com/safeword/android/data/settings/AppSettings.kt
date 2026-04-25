package com.safeword.android.data.settings

/**
 * Application settings data class.
 *
 * Only user-facing preferences are persisted here. All pipeline parameters
 * (VAD thresholds, filler removal, recording limits, etc.) are hardcoded
 * at point of use — research-backed values with no UI exposure.
 */
data class AppSettings(
    val darkMode: String = "system", // "system", "light", "dark"
    val overlayEnabled: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
)
