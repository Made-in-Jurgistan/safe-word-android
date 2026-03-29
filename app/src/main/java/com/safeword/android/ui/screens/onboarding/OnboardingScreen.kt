package com.safeword.android.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import com.safeword.android.R
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.service.SafeWordAccessibilityService
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.components.GlassListItem
import com.safeword.android.ui.components.GlassStepBadge
import com.safeword.android.ui.components.GlassSurface
import com.safeword.android.ui.theme.DoneGreen
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.GlassWhite

/**
 * OnboardingScreen — sequential guided first-launch setup.
 *
 * Steps (must be completed in order):
 * 1. Grant microphone permission  -> "Allow"
 * 2. Grant notification permission (POST_NOTIFICATIONS) -> "Allow"
 * 3. Grant overlay permission (SYSTEM_ALERT_WINDOW) -> "Allow"
 * 4. Enable accessibility service -> "Enable"
 * 5. Download speech model -> auto-downloads, confirmed when done
 *
 * Each step must be confirmed before the next is shown.
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
    val totalSteps = 5

    // Current step (1-based): 1=mic, 2=notifications, 3=overlay, 4=accessibility, 5=download model
    var currentStep by rememberSaveable { mutableIntStateOf(1) }

    // --- Step 1: Mic permission ---
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) currentStep = 2
    }

    // --- Step 2: Notification permission ---
    var notifGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED,
        )
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifGranted = granted
        if (granted) currentStep = 3
    }

    // Auto-advance through already-granted permission steps.
    // Single effect keyed on currentStep so step 2 re-evaluates after step 1 advances
    // (fixes race when both permissions are pre-granted on the same composition frame).
    LaunchedEffect(currentStep, micGranted, notifGranted) {
        if (micGranted && currentStep == 1) currentStep = 2
        if (notifGranted && currentStep == 2) currentStep = 3
    }

    // --- Step 3: Overlay permission ---
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlayGranted = Settings.canDrawOverlays(context)
            if (overlayGranted && currentStep == 3) currentStep = 4
        }
    }

    // --- Step 4: Accessibility service ---
    var a11yEnabled by remember { mutableStateOf(SafeWordAccessibilityService.isActive()) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            a11yEnabled = SafeWordAccessibilityService.isActive()
            if (a11yEnabled && currentStep == 4) currentStep = 5
        }
    }

    // --- Step 5: Model download — start automatically when step 5 is reached ---
    LaunchedEffect(currentStep) {
        if (currentStep == 5) {
            viewModel.ensureModelDownloaded()
        }
    }

    GlassSurface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Image(
                painter = painterResource(id = R.drawable.safeword_icon),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(144.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = GlassDimText,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            LinearProgressIndicator(
                progress = { currentStep.coerceIn(1, totalSteps).toFloat() / totalSteps },
                modifier = Modifier.fillMaxWidth(),
            )

            // Progress indicator
            Spacer(Modifier.height(16.dp))
            StepProgressRow(currentStep = currentStep, totalSteps = totalSteps)

            Spacer(Modifier.height(24.dp))

            // Animated step content — only show the current step
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    (slideInVertically { it / 3 } + fadeIn())
                        .togetherWith(slideOutVertically { -it / 3 } + fadeOut())
                },
                label = "stepTransition",
            ) { step ->
                when (step) {
                    1 -> StepCard(
                        stepNumber = 1,
                        title = stringResource(R.string.onboarding_step_mic_title),
                        subtitle = stringResource(R.string.onboarding_step_mic_subtitle),
                        isDone = micGranted,
                        buttonLabel = stringResource(R.string.onboarding_step_mic_action),
                        onAction = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    )

                    2 -> StepCard(
                        stepNumber = 2,
                        title = stringResource(R.string.onboarding_step_notif_title),
                        subtitle = stringResource(R.string.onboarding_step_notif_subtitle),
                        isDone = notifGranted,
                        buttonLabel = stringResource(R.string.onboarding_step_notif_action),
                        onAction = {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )

                    3 -> StepCard(
                        stepNumber = 3,
                        title = stringResource(R.string.onboarding_step_overlay_title),
                        subtitle = stringResource(R.string.onboarding_step_overlay_subtitle),
                        isDone = overlayGranted,
                        buttonLabel = stringResource(R.string.onboarding_step_overlay_action),
                        onAction = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                            context.startActivity(intent)
                        },
                    )

                    4 -> StepCard(
                        stepNumber = 4,
                        title = stringResource(R.string.onboarding_step_a11y_title),
                        subtitle = stringResource(R.string.onboarding_step_a11y_subtitle),
                        isDone = a11yEnabled,
                        buttonLabel = stringResource(R.string.onboarding_step_a11y_action),
                        onAction = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )

                    5 -> DownloadStepCard(
                        stepNumber = 5,
                        downloadState = downloadState,
                        modelReady = modelReady,
                        onRetry = { viewModel.ensureModelDownloaded() },
                    )
                }
            }

            if (currentStep == 5 && modelReady) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        viewModel.markOnboardingComplete()
                        onComplete()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            }
        }
    }
}

// ---------- Step progress dots ----------

@Composable
private fun StepProgressRow(currentStep: Int, totalSteps: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..totalSteps) {
            GlassStepBadge(number = i, isDone = i < currentStep)
            if (i < totalSteps) Spacer(Modifier.width(12.dp))
        }
    }
}

// ---------- Generic step card ----------

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    buttonLabel: String,
    onAction: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        doneTint = isDone,
        contentPadding = 0.dp,
    ) {
        GlassListItem(
            headlineContent = {
                Text(
                    stringResource(R.string.onboarding_step_title, stepNumber, title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            supportingContent = {
                Text(
                    if (isDone) stringResource(R.string.onboarding_step_complete) else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDone) DoneGreen else GlassDimText,
                )
            },
            leadingContent = { GlassStepBadge(number = stepNumber, isDone = isDone) },
            trailingContent = {
                if (!isDone) {
                    Button(
                        onClick = onAction,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(buttonLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ---------- Download step card ----------

@Composable
private fun DownloadStepCard(
    stepNumber: Int,
    downloadState: ModelDownloadState,
    modelReady: Boolean,
    onRetry: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        doneTint = modelReady,
        contentPadding = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            GlassListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.onboarding_step_title, stepNumber, stringResource(R.string.onboarding_step_model_title)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                supportingContent = {
                    val statusText = when (downloadState) {
                        is ModelDownloadState.NotDownloaded ->
                            stringResource(
                                R.string.onboarding_step_model_preparing,
                                OnboardingViewModel.DEFAULT_MODEL_SIZE_DESC,
                            )
                        is ModelDownloadState.Downloading ->
                            stringResource(
                                R.string.onboarding_step_model_downloading,
                                (downloadState.progress * 100).toInt(),
                            )
                        is ModelDownloadState.Downloaded ->
                            stringResource(R.string.onboarding_step_model_downloaded)
                        is ModelDownloadState.Error ->
                            stringResource(R.string.onboarding_step_model_error, downloadState.message)
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (downloadState) {
                            is ModelDownloadState.Downloaded -> DoneGreen
                            is ModelDownloadState.Error -> MaterialTheme.colorScheme.error
                            else -> GlassDimText
                        },
                    )
                },
                leadingContent = { GlassStepBadge(number = stepNumber, isDone = modelReady) },
                trailingContent = {
                    if (downloadState is ModelDownloadState.Error) {
                        Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.onboarding_step_model_retry))
                        }
                    }
                },
            )

            if (downloadState is ModelDownloadState.Downloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
