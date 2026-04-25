package com.safeword.android.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import com.safeword.android.data.MicAccessEventRepository
import com.safeword.android.data.settings.AppSettings
import com.safeword.android.data.settings.SettingsRepository
import com.safeword.android.transcription.TranscriptionCoordinator
import com.safeword.android.transcription.TranscriptionState
import com.safeword.android.ui.components.OverlayMicButton
import com.safeword.android.ui.components.StreamingTextPreview
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.safeword.android.util.settingsDataStore
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
        private const val DEFAULT_OVERLAY_X = 16
        private const val DEFAULT_OVERLAY_Y = 200
        private const val DRAG_THRESHOLD_DP = 10
        private const val SNAP_ANIMATION_DURATION_MS = 250L
        private const val SAFE_TOP_PX = 80
        private const val SAFE_BOTTOM_MARGIN_PX = 200
        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingOverlayService::class.java))
        }

        // Lint incorrectly flags Intent(context, Class) as an ImplicitSamInstance — stopService
        // matches by component name, not by Intent reference identity.
        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    // Hilt requires field injection for @AndroidEntryPoint Service subclasses
    // (framework constraint). Constructor injection is not supported for Services.
    @Inject lateinit var transcriptionCoordinator: TranscriptionCoordinator
    @Inject lateinit var thermalMonitor: ThermalMonitor
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var accessibilityState: AccessibilityStateHolder
    @Inject lateinit var micAccessEventRepository: MicAccessEventRepository

    // IO dispatcher: the scope is used primarily for DataStore reads/writes and DB operations.
    // Flow collection operators (observeOverlayVisibility, stateIn) are lightweight and run
    // correctly on IO threads. CPU-bound work is not dispatched on this scope.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // DataStore keys for overlay position (replaces legacy SharedPreferences("overlay_prefs"))
    private val prefKeyOverlayX = intPreferencesKey("overlay_x")
    private val prefKeyOverlayY = intPreferencesKey("overlay_y")

    // In-memory position cache — initialised to defaults, updated from DataStore in onCreate()
    @Volatile private var cachedOverlayX: Int = DEFAULT_OVERLAY_X
    @Volatile private var cachedOverlayY: Int = DEFAULT_OVERLAY_Y

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private var visibilityJob: kotlinx.coroutines.Job? = null
    // Deferred used to suspend observeOverlayVisibility() until the overlay position
    // is loaded from DataStore — prevents the first showOverlay() from using stale defaults.
    private lateinit var positionReady: Deferred<Unit>
    private lateinit var currentSettings: StateFlow<AppSettings>
    private var snapAnimator: ValueAnimator? = null

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
        transcriptionCoordinator.preloadVocabulary()

        // Close any mic_access_events left open by a previous app crash
        serviceScope.launch { micAccessEventRepository.closeOrphanedSessions() }

        // Restore overlay position from DataStore into the in-memory cache.
        // Using async so that observeOverlayVisibility() can await completion before
        // the first showOverlay() call — prevents a TOCTOU race on the position defaults.
        positionReady = serviceScope.async {
            val prefs = applicationContext.settingsDataStore.data.first()
            cachedOverlayX = prefs[prefKeyOverlayX] ?: DEFAULT_OVERLAY_X
            cachedOverlayY = prefs[prefKeyOverlayY] ?: DEFAULT_OVERLAY_Y
            Timber.d("[SETTINGS] FloatingOverlayService.onCreate | overlayPos=($cachedOverlayX,$cachedOverlayY)")
        }

        // Start thermal monitoring
        thermalMonitor.start()

        // Cache latest settings for non-Compose reads (e.g. haptic in toggleRecording)
        currentSettings = settingsRepository.settings
            .stateIn(serviceScope, SharingStarted.Eagerly, AppSettings())

        // Observe text field focus + transcription state to show/hide overlay
        observeOverlayVisibility()

        // Move lifecycle to STARTED so collectAsStateWithLifecycle works
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    /**
     * Show overlay when the soft keyboard is visible or an active operation
     * (Recording) is in progress.
     *
     * Terminal states (Done, Error) do NOT keep the overlay
     * visible on their own — the overlay disappears as soon as the keyboard
     * is dismissed, regardless of transcription state.
     */
    private fun observeOverlayVisibility() {
        visibilityJob = serviceScope.launch {
            positionReady.await() // Ensure cached position is populated before first showOverlay()
            accessibilityState.keyboardVisible
                .combine(transcriptionCoordinator.state) { imeVisible, state ->
                    val activeOperation = state is TranscriptionState.Recording
                    imeVisible || activeOperation
                }
                .collect { shouldShow ->
                    // WindowManager operations must run on the main thread.
                    withContext(Dispatchers.Main) {
                        if (shouldShow) showOverlay() else removeOverlay()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("[SERVICE] FloatingOverlayService.onStartCommand")
        // Require Android 14+ (API 34+) with explicit foreground service type
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("[LIFECYCLE] FloatingOverlayService.onDestroy")
        visibilityJob?.cancel()
        visibilityJob = null
        thermalMonitor.stop()
        transcriptionCoordinator.cancelPreload()
        removeOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Timber.w("[WARN] showOverlay | SYSTEM_ALERT_WINDOW permission revoked — aborting")
            return
        }
        val params = buildOverlayLayoutParams()
        val composeView = buildOverlayComposeView()
        attachOverlayTouchListener(composeView, params)
        windowManager.addView(composeView, params)
        overlayView = composeView
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Timber.d("[SERVICE] FloatingOverlayService | overlay shown")
    }

    private fun buildOverlayLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (savedX, savedY) = restoreOverlayPosition()
            x = savedX
            y = savedY
        }

    private fun buildOverlayComposeView(): ComposeView =
        ComposeView(this).apply {
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
                val partialText =
                    (state as? TranscriptionState.Recording)?.partialText.orEmpty()
                val insertedText =
                    (state as? TranscriptionState.Recording)?.insertedText.orEmpty()
                val errorMessage =
                    (state as? TranscriptionState.Error)?.message.orEmpty()
                // Show error message in the text preview so the user knows why recording
                // didn't start — Error auto-resets to Idle in 1.5 s without this.
                val displayText = if (errorMessage.isNotBlank()) errorMessage else partialText
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .widthIn(max = 320.dp),
                ) {
                    StreamingTextPreview(
                        text = displayText,
                        insertedText = insertedText,
                        isDarkMode = isDarkMode,
                        isActivelyTranscribing = state is TranscriptionState.Recording,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (displayText.isNotBlank()) Spacer(Modifier.height(6.dp))
                    OverlayMicButton(
                        state = state,
                        isDarkMode = isDarkMode,
                    )
                }
            }
        }

    // ClickableViewAccessibility: performClick() IS called on ACTION_UP when not dragging.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachOverlayTouchListener(
        composeView: ComposeView,
        params: WindowManager.LayoutParams,
    ) {
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
                    val dragThresholdPx = DRAG_THRESHOLD_DP * resources.displayMetrics.density
                    if (dx * dx + dy * dy > dragThresholdPx * dragThresholdPx) moved = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(composeView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        composeView.performClick()
                        toggleRecording()
                    } else {
                        // Edge magnetism: snap to nearest horizontal screen edge.
                        // Algebra: compare the view's center (params.x + viewWidth/2) against the
                        // screen center (screenWidth/2). This yields the nearest edge even when
                        // the view is partially off-screen — a simple midpoint test wouldn't work
                        // because it assumes the view is fully visible.
                        val screenWidth = windowManager.currentWindowMetrics.bounds.width()
                        val viewWidth = composeView.width.coerceAtLeast(1)
                        val targetX = if (params.x + viewWidth / 2 < screenWidth / 2) 0
                            else screenWidth - viewWidth
                        snapAnimator?.cancel()
                        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
                            duration = SNAP_ANIMATION_DURATION_MS
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                params.x = anim.animatedValue as Int
                                try { windowManager.updateViewLayout(composeView, params) }
                                catch (_: IllegalArgumentException) { /* view removed mid-anim */ }
                            }
                            start()
                        }

                        // Safe zone: clamp Y so button stays reachable
                        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
                        val safeTop = SAFE_TOP_PX
                        val safeBottom = screenHeight - SAFE_BOTTOM_MARGIN_PX
                        params.y = params.y.coerceIn(safeTop, safeBottom)
                        try { windowManager.updateViewLayout(composeView, params) }
                        catch (_: IllegalArgumentException) { /* view removed */ }

                        saveOverlayPosition(targetX, params.y)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeOverlay() {
        snapAnimator?.cancel()
        snapAnimator = null
        overlayView?.let {
            it.disposeComposition()
            windowManager.removeView(it)
            overlayView = null
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            Timber.d("[SERVICE] FloatingOverlayService | overlay removed")
        }
    }

    private fun toggleRecording() {
        val currentState = transcriptionCoordinator.state.value
        Timber.d("[SERVICE] FloatingOverlayService.toggleRecording | state=%s", currentState)

        if (currentSettings.value.hapticFeedbackEnabled) {
            try {
                val isStarting = currentState !is TranscriptionState.Recording
                val durationMs = if (isStarting) 50L else 30L
                val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: SecurityException) {
                Timber.w(e, "[WARN] toggleRecording | VIBRATE permission missing")
            }
        }

        when (currentState) {
            is TranscriptionState.Idle,
            is TranscriptionState.Done,
            is TranscriptionState.Error -> transcriptionCoordinator.startRecording()
            is TranscriptionState.Recording -> transcriptionCoordinator.stopRecording()
        }
    }

    private fun saveOverlayPosition(x: Int, y: Int) {
        cachedOverlayX = x
        cachedOverlayY = y
        serviceScope.launch {
            applicationContext.settingsDataStore.edit { prefs ->
                prefs[prefKeyOverlayX] = x
                prefs[prefKeyOverlayY] = y
            }
        }
    }

    private fun restoreOverlayPosition(): Pair<Int, Int> = cachedOverlayX to cachedOverlayY

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
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
