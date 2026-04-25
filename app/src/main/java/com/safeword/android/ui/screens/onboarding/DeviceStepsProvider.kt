package com.safeword.android.ui.screens.onboarding

import android.os.Build

/**
 * Provides OEM-specific accessibility and overlay navigation steps.
 *
 * Different manufacturers place accessibility settings behind different menus.
 * This provider detects [Build.MANUFACTURER] and returns step-by-step instructions
 * tailored to the user's device.
 */
object DeviceStepsProvider {

    enum class OemFamily {
        SAMSUNG,
        XIAOMI,
        ONEPLUS,
        OPPO,
        VIVO,
        HUAWEI,
        HONOR,
        MOTOROLA,
        TRANSSION,
        NOTHING,
        SONY,
        NOKIA,
        GOOGLE,
        GENERIC,
    }

    fun detectOem(): OemFamily {
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        return when {
            manufacturer == "samsung" -> OemFamily.SAMSUNG
            manufacturer in setOf("xiaomi", "redmi", "poco") -> OemFamily.XIAOMI
            manufacturer == "oneplus" -> OemFamily.ONEPLUS
            manufacturer in setOf("oppo", "realme") -> OemFamily.OPPO
            manufacturer == "vivo" -> OemFamily.VIVO
            manufacturer == "huawei" -> OemFamily.HUAWEI
            manufacturer == "honor" -> OemFamily.HONOR
            manufacturer == "motorola" -> OemFamily.MOTOROLA
            manufacturer in setOf("tecno", "infinix", "itel") -> OemFamily.TRANSSION
            manufacturer == "nothing" -> OemFamily.NOTHING
            manufacturer == "sony" -> OemFamily.SONY
            manufacturer in setOf("hmd global", "nokia") -> OemFamily.NOKIA
            manufacturer == "google" -> OemFamily.GOOGLE
            else -> OemFamily.GENERIC
        }
    }

    /**
     * Returns the accessibility walkthrough steps for the detected OEM.
     *
     * Each step is a user-facing string describing exactly where to tap.
     * The final step always tells the user to return to the app.
     */
    fun accessibilitySteps(oem: OemFamily = detectOem()): List<String> = when (oem) {
        OemFamily.SAMSUNG -> listOf(
            "Scroll down and tap \u0022Installed apps\u0022 (or \u0022Installed services\u0022)",
            "Find \u0022Safe Word\u0022 and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.XIAOMI -> listOf(
            "Scroll down and tap \u0022Downloaded apps\u0022 (or \u0022Installed services\u0022)",
            "Find \u0022Safe Word\u0022 and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.ONEPLUS -> listOf(
            "Scroll down and tap \u0022Downloaded apps\u0022 (or \u0022Installed apps\u0022)",
            "Find \u0022Safe Word\u0022 and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.OPPO -> listOf(
            "Scroll down and tap \u0022Downloaded apps\u0022",
            "Find \u0022Safe Word\u0022 and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.VIVO -> listOf(
            "Scroll down and tap \u0022Downloaded apps\u0022",
            "Find \u0022Safe Word\u0022 and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.HUAWEI -> listOf(
            "Tap \u0022Accessibility\u0022 (under \u0022Smart assistance\u0022 on some models)",
            "Find \u0022Safe Word\u0022 in the list and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.HONOR -> listOf(
            "Scroll down and tap \u0022Installed services\u0022",
            "Find \u0022Safe Word\u0022 and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.MOTOROLA -> listOf(
            "Find \u0022Safe Word\u0022 in the list and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.TRANSSION -> listOf(
            "Scroll down and tap \u0022Installed services\u0022",
            "Find \u0022Safe Word\u0022 and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.NOTHING -> listOf(
            "Find \u0022Safe Word\u0022 in the list and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.SONY -> listOf(
            "Find \u0022Safe Word\u0022 in the list and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.NOKIA -> listOf(
            "Find \u0022Safe Word\u0022 in the list and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.GOOGLE -> listOf(
            "Find \u0022Safe Word\u0022 in the list and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
        OemFamily.GENERIC -> listOf(
            "Find \u0022Safe Word\u0022 in the list and tap it",
            "Tap the switch to turn it on",
            "Tap \u0022Allow\u0022 when asked to confirm",
            "Come back here \u2014 Safe Word will pick it up automatically",
        )
    }

    /**
     * Returns a short OEM-specific hint appended after "Open settings" for accessibility.
     *
     * E.g. on Samsung: "Settings → Accessibility → Installed apps → Safe Word"
     * Returns null for generic devices where the default path is sufficient.
     */
    fun accessibilityPathHint(oem: OemFamily = detectOem()): String? = when (oem) {
        OemFamily.SAMSUNG -> "Settings → Accessibility → Installed apps → Safe Word"
        OemFamily.XIAOMI -> "Settings → Additional settings → Accessibility → Safe Word"
        OemFamily.ONEPLUS -> "Settings → Accessibility → Downloaded apps → Safe Word"
        OemFamily.OPPO -> "Settings → Accessibility → Downloaded apps → Safe Word"
        OemFamily.VIVO -> "Settings → Accessibility → Downloaded apps → Safe Word"
        OemFamily.HUAWEI -> "Settings → Accessibility → Safe Word"
        OemFamily.HONOR -> "Settings → Accessibility → Installed services → Safe Word"
        OemFamily.MOTOROLA -> null
        OemFamily.TRANSSION -> "Settings → Accessibility → Installed services → Safe Word"
        OemFamily.NOTHING -> null
        OemFamily.SONY -> null
        OemFamily.NOKIA -> null
        OemFamily.GOOGLE -> null
        OemFamily.GENERIC -> null
    }

    /**
     * Returns OEM-specific steps for unlocking restricted settings via App Info.
     *
     * These steps guide users through the Android 13+ "Allow restricted settings" flow
     * after they have triggered the restriction dialog by attempting to enable the service.
     */
    fun restrictionUnlockSteps(oem: OemFamily = detectOem()): List<String> = when (oem) {
        OemFamily.SAMSUNG -> listOf(
            "Open App Info for Safe Word (the screen that just appeared, or Settings → Apps → Safe Word)",
            "Tap the \u22ee (three dots) in the top-right corner",
            "Choose \u0022Allow restricted settings\u0022",
            "Confirm with your fingerprint, PIN, or pattern",
            "Come back here and enable Safe Word in Accessibility Settings",
        )
        OemFamily.XIAOMI -> listOf(
            "Open App Info for Safe Word (Settings → Apps → Manage apps → Safe Word)",
            "Tap the \u22ee (three dots) in the top-right corner",
            "Choose \u0022Allow restricted settings\u0022",
            "Confirm with your fingerprint, PIN, or pattern",
            "Come back here and enable Safe Word in Accessibility Settings",
        )
        else -> listOf(
            "Open App Info for Safe Word (tap the info button in Settings → Apps, or long-press Safe Word\u2019s icon → App Info)",
            "Tap the \u22ee (three dots) in the top-right corner",
            "Choose \u0022Allow restricted settings\u0022",
            "Confirm with your fingerprint, PIN, or pattern",
            "Come back here and enable Safe Word in Accessibility Settings",
        )
    }

    /**
     * Returns true for OEM/OS combinations where the \u22ee menu has been removed from App Info,
     * making the standard "Allow restricted settings" flow unavailable.
     *
     * Affected: OnePlus running OxygenOS 15 (Android 15 / API 35+).
     * Workaround: reinstall via SAI (Split APK Installer) from Play Store.
     */
    fun lacksRestrictionMenu(oem: OemFamily = detectOem()): Boolean =
        oem == OemFamily.ONEPLUS
}
