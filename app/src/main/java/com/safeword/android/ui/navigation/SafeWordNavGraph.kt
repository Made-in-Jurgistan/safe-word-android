package com.safeword.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.safeword.android.ui.screens.onboarding.OnboardingScreen
import com.safeword.android.ui.screens.onboarding.OnboardingViewModel
import com.safeword.android.ui.screens.settings.SettingsScreen
import com.safeword.android.ui.screens.splash.SplashScreen

/**
 * SafeWordNavGraph — Compose Navigation.
 * Routes: Splash (first launch), Onboarding (setup), Settings (main screen).
 * The floating overlay provides voice input; the app shows settings only.
 */

private const val SPLASH_ROUTE = "splash"
private const val ONBOARDING_ROUTE = "onboarding"
private const val SETTINGS_ROUTE = "settings"

@Composable
fun SafeWordNavGraph() {
    val navController = rememberNavController()
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingComplete by onboardingViewModel.onboardingComplete.collectAsState()

    // First launch: splash → onboarding → settings
    // Returning user: straight to settings
    val startDestination = if (onboardingComplete) SETTINGS_ROUTE else SPLASH_ROUTE

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(SPLASH_ROUTE) {
            SplashScreen(
                onFinished = {
                    navController.navigate(ONBOARDING_ROUTE) {
                        popUpTo(SPLASH_ROUTE) { inclusive = true }
                    }
                },
            )
        }
        composable(ONBOARDING_ROUTE) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(SETTINGS_ROUTE) {
                        popUpTo(ONBOARDING_ROUTE) { inclusive = true }
                    }
                },
            )
        }
        composable(SETTINGS_ROUTE) { SettingsScreen() }
    }
}
