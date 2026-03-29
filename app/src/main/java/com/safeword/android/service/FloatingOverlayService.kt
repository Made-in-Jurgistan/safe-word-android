package com.safeword.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.safeword.android.MainActivity
import com.safeword.android.R
import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.transcription.TranscriptionCoordinator
import com.safeword.android.transcription.TranscriptionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * FloatingOverlayService — draws a draggable mic button over all apps.
 *
 * Uses SYSTEM_ALERT_WINDOW to place a ComposeView floating button.
 * Tapping toggles recording via TranscriptionCoordinator.
 * The button is only visible when a text field is focused (via AccessibilityService)
 * or when a transcription operation is in progress.
 */
@AndroidEntryPoint
class FloatingOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val CHANNEL_ID = "safeword_overlay"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    @Inject lateinit var transcriptionCoordinator: TranscriptionCoordinator
    @Inject lateinit var thermalMonitor: ThermalMonitor
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var visibilityJob: kotlinx.coroutines.Job? = null

    // Lifecycle plumbing for ComposeView in a Service
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        Timber.i("[LIFECYCLE] FloatingOverlayService.onCreate")
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        // Preload models on service start so first recording is fast
        transcriptionCoordinator.preloadModels()

        // Start thermal monitoring
        thermalMonitor.start()

        // Observe text field focus + transcription state to show/hide overlay
        observeOverlayVisibility()
    }

    /**
     * Show overlay when the soft keyboard is visible or an active operation
     * (Recording / Transcribing) is in progress.
     *
     * Terminal states (Done, Error, CommandDetected) do NOT keep the overlay
     * visible on their own — the overlay disappears as soon as the keyboard
     * is dismissed, regardless of transcription state.
     */
    private fun observeOverlayVisibility() {
        visibilityJob = serviceScope.launch {
            SafeWordAccessibilityService.keyboardVisible
                .combine(transcriptionCoordinator.state) { imeVisible, state ->
                    val activeOperation = state is TranscriptionState.Recording ||
                        state is TranscriptionState.Transcribing
                    imeVisible || activeOperation
                }
                .collect { shouldShow ->
                    if (shouldShow) showOverlay() else removeOverlay()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("[SERVICE] FloatingOverlayService.onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("[LIFECYCLE] FloatingOverlayService.onDestroy")
        visibilityJob?.cancel()
        visibilityJob = null
        thermalMonitor.stop()
        transcriptionCoordinator.cancel()
        removeOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingOverlayService)
            setViewTreeSavedStateRegistryOwner(this@FloatingOverlayService)
            setContent {
                val state by transcriptionCoordinator.state
                    .collectAsStateWithLifecycle(lifecycleOwner = this@FloatingOverlayService)
                val settings by settingsRepository.settings
                    .collectAsStateWithLifecycle(
                        initialValue = AppSettings(),
                        lifecycleOwner = this@FloatingOverlayService,
                    )
                val isDarkMode = when (settings.darkMode) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                }
                OverlayMicButton(
                    state = state,
                    isDarkMode = isDarkMode,
                    onTap = { toggleRecording() },
                )
            }
        }

        // Dragging support
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        composeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) moved = true  // 10px threshold
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(composeView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        composeView.performClick()
                        toggleRecording()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(composeView, params)
        overlayView = composeView
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Timber.d("[SERVICE] FloatingOverlayService | overlay shown")
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
            Timber.d("[SERVICE] FloatingOverlayService | overlay removed")
        }
    }

    private fun toggleRecording() {
        val currentState = transcriptionCoordinator.state.value
        Timber.d("[SERVICE] FloatingOverlayService.toggleRecording | state=%s", currentState)
        when (currentState) {
            is TranscriptionState.Idle,
            is TranscriptionState.Done,
            is TranscriptionState.Error,
            is TranscriptionState.CommandDetected -> transcriptionCoordinator.startRecording()
            is TranscriptionState.Recording -> transcriptionCoordinator.stopRecording()
            is TranscriptionState.Transcribing -> { /* busy finalizing, ignore tap */ }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.overlay_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
