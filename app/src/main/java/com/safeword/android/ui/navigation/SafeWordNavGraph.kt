package com.safeword.android.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.safeword.android.ui.screens.onboarding.OnboardingScreen
import com.safeword.android.ui.screens.settings.SettingsScreen
import com.safeword.android.ui.screens.splash.SplashScreen

/**
 * SafeWordNavGraph — Compose Navigation.
 * Routes: Splash (first launch), Onboarding (setup), Settings (main screen).
 * The floating overlay provides voice input; the app shows settings only.
 */

@Composable
fun SafeWordNavGraph(startDestination: String) {
    val navController = rememberNavController()

    Scaffold(
        containerColor = Color.Transparent,
    ) { innerPadding ->
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(innerPadding),
    ) {
        composable(NavigationRoutes.SPLASH) {
            SplashScreen(
                onFinished = {
                    navController.navigate(NavigationRoutes.ONBOARDING) {
                        popUpTo(NavigationRoutes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(NavigationRoutes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(NavigationRoutes.SETTINGS) {
                        popUpTo(NavigationRoutes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(NavigationRoutes.SETTINGS) { SettingsScreen() }
    }
    } // end Scaffold
}
