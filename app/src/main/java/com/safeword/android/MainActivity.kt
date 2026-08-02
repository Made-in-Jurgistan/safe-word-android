package com.safeword.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.service.FloatingOverlayService
import com.safeword.android.ui.SafeWordAndroidApp
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

    @Inject lateinit var settingsRepository: SettingsRepository

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
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            SafeWordTheme(mode = settings.darkMode) {
                SafeWordAndroidApp()
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
