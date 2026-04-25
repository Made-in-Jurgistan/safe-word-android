package com.safeword.android.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.safeword.android.R
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.ui.components.GlassSurface
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.SilverDim
import kotlinx.coroutines.delay

/**
 * OnboardingScreen — sequential guided first-launch setup.
 *
 * Each step occupies the full screen. The user must confirm enablement
 * (via the Continue button) before advancing to the next step.
 *
 * Step order (easiest first → hardest last):
 * 0. Welcome
 * 1. Microphone — one-tap system dialog
 * 2. Speech model download — auto-background download
 * 3. Overlay — requires leaving app (skippable)
 * 4. Accessibility — requires leaving app; adaptive restriction help for sideloaded installs
 * 5. Completion
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val modelReady by viewModel.modelReady.collectAsStateWithLifecycle()
    val skippedSteps by viewModel.skippedSteps.collectAsStateWithLifecycle()

    val visibleSteps = remember {
        listOf(STEP_MIC, STEP_MODEL, OnboardingViewModel.STEP_OVERLAY, STEP_A11Y)
    }

    var currentStep by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    var stepRestored by rememberSaveable { mutableStateOf(false) }

    // Advance to the next visible step after the given one.
    val advanceFrom: (Int) -> Unit = remember(visibleSteps) {
        { from: Int ->
            val idx = visibleSteps.indexOf(from)
            currentStep = if (idx in 0 until visibleSteps.lastIndex) {
                visibleSteps[idx + 1]
            } else {
                STEP_COMPLETE
            }
        }
    }

    // Restore persisted step on first composition.
    LaunchedEffect(Unit) {
        if (!stepRestored) {
            var saved = viewModel.restoreStep()
            if (saved > 0) {
                // Ensure restored step is reachable in the current flow.
                if (saved !in visibleSteps && saved != STEP_WELCOME && saved != STEP_COMPLETE) {
                    saved = visibleSteps.firstOrNull { it >= saved } ?: STEP_MIC
                }
                currentStep = saved
            }
            stepRestored = true
        }
    }

    // Persist step whenever it changes (after restore).
    LaunchedEffect(currentStep) {
        if (stepRestored && currentStep in STEP_MIC..STEP_A11Y) {
            viewModel.persistStep(currentStep)
        }
    }

    // --- Step 1: Mic permission ---
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> micGranted = granted }

    // --- Step 3: Overlay permission (refreshed on RESUMED) ---
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlayGranted = Settings.canDrawOverlays(context)
        }
    }

    // --- Step 4: Accessibility service + restricted-settings phase ---
    // Collect reactively so the UI updates the moment onServiceConnected fires,
    // even if that happens 200–500 ms after the activity has already resumed.
    val a11yEnabled by viewModel.accessibilityActive.collectAsStateWithLifecycle()
    var restrictedState by remember { mutableStateOf(viewModel.restrictedState()) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            restrictedState = viewModel.restrictedState()
        }
    }

    // Stuck detection timer for overlay and a11y steps.
    var showNeedHelpLink by rememberSaveable { mutableStateOf(false) }
    var showStuckHint by rememberSaveable { mutableStateOf(false) }
    var showAdvancedStuckHint by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentStep) {
        showNeedHelpLink = false
        showStuckHint = false
        showAdvancedStuckHint = false
        if (currentStep in setOf(OnboardingViewModel.STEP_OVERLAY, STEP_A11Y)) {
            delay(STUCK_HINT_DELAY_MS)
            showNeedHelpLink = true
            delay(STUCK_TIMER_MS - STUCK_HINT_DELAY_MS)
            showStuckHint = true
            delay(30_000L) // 60s total
            showAdvancedStuckHint = true
        }
    }

    // Display helpers for the progress indicator.
    val displayStepIndex = visibleSteps.indexOf(currentStep) // 0-based
    val totalVisibleSteps = visibleSteps.size

    GlassSurface(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                val forward = targetState > initialState
                val enterOffset: (Int) -> Int =
                    if (forward) { w -> w / 3 } else { w -> -w / 3 }
                val exitOffset: (Int) -> Int =
                    if (forward) { w -> -w / 3 } else { w -> w / 3 }
                val spec = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 300f)
                (slideInHorizontally(animationSpec = spec, initialOffsetX = enterOffset) + fadeIn())
                    .togetherWith(slideOutHorizontally(animationSpec = spec, targetOffsetX = exitOffset) + fadeOut())
            },
            label = "onboardingPage",
        ) { step ->
            when (step) {
                STEP_WELCOME -> WelcomePage(
                    onStart = { currentStep = STEP_MIC },
                )

                STEP_MIC -> OnboardingStepPage(
                    stepIndex = displayStepIndex,
                    totalSteps = totalVisibleSteps,
                    visibleSteps = visibleSteps,
                    skippedSteps = skippedSteps,
                    title = stringResource(R.string.onboarding_step_mic_title),
                    description = stringResource(R.string.onboarding_step_mic_subtitle),
                    isDone = micGranted,
                    doneLabel = stringResource(R.string.onboarding_step_complete),
                    onContinue = { advanceFrom(STEP_MIC) },
                    continueEnabled = micGranted,
                    whyText = stringResource(R.string.onboarding_step_mic_why),
                ) {
                    if (!micGranted) {
                        Text(
                            stringResource(R.string.onboarding_step_mic_priming),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SilverDim,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                        )
                        Button(
                            onClick = {
                                micPermissionLauncher.launch(
                                    Manifest.permission.RECORD_AUDIO,
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp),
                        ) {
                            Text(
                                stringResource(R.string.onboarding_step_mic_action),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                STEP_MODEL -> ModelDownloadPage(
                    stepIndex = displayStepIndex,
                    totalSteps = totalVisibleSteps,
                    visibleSteps = visibleSteps,
                    skippedSteps = skippedSteps,
                    downloadState = downloadState,
                    modelReady = modelReady,
                    onRetry = { viewModel.ensureModelDownloaded() },
                    onContinue = { advanceFrom(STEP_MODEL) },
                    whyText = stringResource(R.string.onboarding_step_model_why),
                )

                OnboardingViewModel.STEP_OVERLAY -> OnboardingStepPage(
                    stepIndex = displayStepIndex,
                    totalSteps = totalVisibleSteps,
                    visibleSteps = visibleSteps,
                    skippedSteps = skippedSteps,
                    title = stringResource(R.string.onboarding_step_overlay_title),
                    description = stringResource(R.string.onboarding_step_overlay_subtitle),
                    isDone = overlayGranted,
                    doneLabel = stringResource(R.string.onboarding_step_complete),
                    onContinue = { advanceFrom(OnboardingViewModel.STEP_OVERLAY) },
                    continueEnabled = overlayGranted,
                    whyText = stringResource(R.string.onboarding_step_overlay_why),
                    showNeedHelpLink = showNeedHelpLink && !overlayGranted,
                    showStuckHint = showStuckHint && !overlayGranted,
                    showAdvancedStuckHint = showAdvancedStuckHint && !overlayGranted,
                    skippable = true,
                    onSkip = {
                        viewModel.skipStep(OnboardingViewModel.STEP_OVERLAY)
                        advanceFrom(OnboardingViewModel.STEP_OVERLAY)
                    },
                ) {
                    if (!overlayGranted) {
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        "package:${context.packageName}".toUri(),
                                    ),
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp),
                        ) {
                            Text(
                                stringResource(R.string.onboarding_step_overlay_action),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                    }
                }

                STEP_A11Y -> AccessibilityPage(
                    stepIndex = displayStepIndex,
                    totalSteps = totalVisibleSteps,
                    visibleSteps = visibleSteps,
                    skippedSteps = skippedSteps,
                    isDone = a11yEnabled,
                    restrictedState = restrictedState,
                    onOpenAccessibility = {
                        // Best-effort: pass the fragment args key recognised by AOSP/Pixel builds
                        // of Android 13+. On devices that support it, Settings navigates directly
                        // to Safe Word's service detail page — the user taps one toggle and the
                        // restriction dialog fires immediately, advancing state to TRIGGERED in a
                        // single step. On OEMs that ignore this extra the intent still opens the
                        // standard Accessibility Settings list with no harm.
                        val serviceComp =
                            "${context.packageName}/.service.SafeWordAccessibilityService"
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                putExtra(":settings:fragment_args_key", serviceComp)
                                putExtra(
                                    ":settings:show_fragment_args",
                                    android.os.Bundle().apply {
                                        putString(":settings:fragment_args_key", serviceComp)
                                    },
                                )
                            },
                        )
                    },
                    onOpenAppInfo = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri(),
                            ),
                        )
                    },
                    onRecheck = {
                        restrictedState = viewModel.restrictedState()
                        if (a11yEnabled) advanceFrom(STEP_A11Y)
                    },
                    onContinue = { advanceFrom(STEP_A11Y) },
                    showNeedHelpLink = showNeedHelpLink && !a11yEnabled,
                    showStuckHint = showStuckHint && !a11yEnabled,
                    showAdvancedStuckHint = showAdvancedStuckHint && !a11yEnabled,
                )

                STEP_COMPLETE -> CompletePage(
                    onComplete = {
                        viewModel.markOnboardingComplete()
                        onComplete()
                    },
                )
            }
        }
    }
}
