package com.safeword.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.safeword.android.data.settings.OnboardingRepository
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.service.FloatingOverlayService
import com.safeword.android.ui.navigation.SafeWordNavGraph
import com.safeword.android.ui.navigation.NavigationRoutes
import com.safeword.android.ui.theme.SafeWordTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Single Activity host for Jetpack Compose.
 * Mirrors Tauri's single window with WebView, replaced by Compose.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hilt requires field injection for @AndroidEntryPoint Activity subclasses
    // (framework constraint). Constructor injection is not supported for Activities.
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var onboardingRepository: OnboardingRepository

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = Settings.canDrawOverlays(this)
        Timber.i("[PERMISSION] SYSTEM_ALERT_WINDOW | granted=%b", granted)
        if (granted) {
            lifecycleScope.launch {
                if (settingsRepository.settings.first().overlayEnabled) {
                    FloatingOverlayService.start(this@MainActivity)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("[LIFECYCLE] MainActivity.onCreate | savedState=%b", savedInstanceState != null)
        enableEdgeToEdge()
        observeOverlaySetting()
        setContent {
            // Resolve the correct start destination asynchronously; show splash in the meantime.
            val startDestination by produceState(NavigationRoutes.SPLASH) {
                val onboardingComplete = onboardingRepository.onboardingComplete.first()
                value = if (onboardingComplete) NavigationRoutes.SETTINGS else NavigationRoutes.SPLASH
            }
            SafeWordTheme {
                SafeWordNavGraph(startDestination = startDestination)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Timber.d("[LIFECYCLE] MainActivity.onStart")
    }

    override fun onResume() {
        super.onResume()
        Timber.d("[LIFECYCLE] MainActivity.onResume")
    }

    override fun onPause() {
        super.onPause()
        Timber.d("[LIFECYCLE] MainActivity.onPause")
    }

    override fun onStop() {
        super.onStop()
        Timber.d("[LIFECYCLE] MainActivity.onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("[LIFECYCLE] MainActivity.onDestroy")
    }

    private fun observeOverlaySetting() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.settings
                    .map { it.overlayEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        val canDraw = Settings.canDrawOverlays(this@MainActivity)
                        Timber.d("[STATE] MainActivity | overlayEnabled=%b canDrawOverlays=%b", enabled, canDraw)
                        if (enabled && canDraw) {
                            FloatingOverlayService.start(this@MainActivity)
                        } else if (enabled && !canDraw) {
                            Timber.i("[STATE] MainActivity | requesting overlay permission")
                            overlayPermissionLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:$packageName".toUri(),
                                ),
                            )
                        } else {
                            FloatingOverlayService.stop(this@MainActivity)
                        }
                    }
            }
        }
    }

}
