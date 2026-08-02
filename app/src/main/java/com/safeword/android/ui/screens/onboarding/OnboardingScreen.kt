package com.safeword.android.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
 * 1. Grant microphone permission (RECORD_AUDIO) -> "Allow"
 * 2. Grant overlay permission (SYSTEM_ALERT_WINDOW) -> "Allow"
 * 3. Enable accessibility service -> "Enable"
 * 4. Download speech model -> auto-downloads, confirmed when done
 *
 * 2026 UX patterns applied:
 * - Value-first headline ("On-device dictation for any app") so users see
 *   the outcome before the permission asks.
 * - Single horizontal dot progress indicator (no redundant linear bar).
 * - Accessibility step uses one primary CTA; the step auto-re-checks on
 *   lifecycle RESUMED, so the old "Check again" button is unnecessary.
 * - Step badge exposes a per-step contentDescription for screen readers.
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
    val totalSteps = 4

    // Current step (1-based): 1=mic, 2=overlay, 3=accessibility, 4=download model
    var currentStep by rememberSaveable { mutableIntStateOf(1) }

    // --- Step 1: Mic permission ---
    var micGranted by rememberSaveable {
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

    // Auto-advance through already-granted permission steps.
    LaunchedEffect(currentStep, micGranted) {
        if (micGranted && currentStep == 1) currentStep = 2
    }

    // --- Step 3: Overlay permission ---
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlayGranted = Settings.canDrawOverlays(context)
            if (overlayGranted && currentStep == 2) currentStep = 3
        }
    }

    // --- Step 4: Accessibility service ---
    var a11yEnabled by remember { mutableStateOf(SafeWordAccessibilityService.isActive()) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            a11yEnabled = SafeWordAccessibilityService.isActive()
            if (a11yEnabled && currentStep == 3) currentStep = 4
        }
    }

    // --- Step 5: Model download — start automatically when step 5 is reached ---
    LaunchedEffect(currentStep) {
        if (currentStep == 4) {
            viewModel.ensureModelDownloaded()
        }
    }

    GlassSurface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            // Horizontal brand lockup. The 64.dp hero icon sits at ~3.8× the
            // cap height of `headlineSmall`, which is the Material 3 sweet spot
            // for an inline leading icon — distinct enough to read as a brand
            // mark, small enough that the headline keeps reading priority.
            // Vertical stacking (icon-on-top + headline + body) is intentionally
            // avoided so the step card lands above the fold on 360.dp devices.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Brand mark is decorative: the headline below already
                // communicates app identity verbally; a duplicate "Safe Word,
                // image" announcement just before the headline is noise for
                // screen-reader users.
                Image(
                    painter = painterResource(id = R.drawable.safeword_icon),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    // heading() lets TalkBack's heading-jump gesture land on
                    // the value-prop, matching how sighted users skim the page.
                    Text(
                        stringResource(R.string.onboarding_intro_headline),
                        style = MaterialTheme.typography.headlineSmall,
                        color = GlassWhite,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.onboarding_intro_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassDimText,
                        textAlign = TextAlign.Start,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
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
                        title = stringResource(R.string.onboarding_step_overlay_title),
                        subtitle = stringResource(R.string.onboarding_step_overlay_subtitle),
                        isDone = overlayGranted,
                        buttonLabel = stringResource(R.string.onboarding_step_overlay_action),
                        onAction = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${context.packageName}".toUri(),
                            )
                            context.startActivity(intent)
                        },
                    )

                    3 -> AccessibilityStepCard(
                        stepNumber = 3,
                        isDone = a11yEnabled,
                        onOpenSettings = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )

                    4 -> DownloadStepCard(
                        stepNumber = 4,
                        downloadState = downloadState,
                        modelReady = modelReady,
                        onRetry = { viewModel.ensureModelDownloaded() },
                    )
                }
            }

            if (currentStep == 4 && modelReady) {
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
    // Collapse the four numbered badges into a single TalkBack node that
    // announces the overall position ("Step 2 of 4") and re-announces it
    // politely when the active step changes. Without this, TalkBack reads
    // every badge in turn ("Step 1 complete, Step 2, Step 3, Step 4") which
    // is verbose and lacks the "of N" framing.
    val progressDescription = stringResource(
        R.string.onboarding_progress_cd,
        currentStep,
        totalSteps,
    )
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = progressDescription
            liveRegion = LiveRegionMode.Polite
        },
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

// ---------- Accessibility step card ----------

@Composable
private fun AccessibilityStepCard(
    stepNumber: Int,
    isDone: Boolean,
    onOpenSettings: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        doneTint = isDone,
        contentPadding = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassStepBadge(number = stepNumber, isDone = isDone)
                Column {
                    Text(
                        stringResource(
                            R.string.onboarding_step_title,
                            stepNumber,
                            stringResource(R.string.onboarding_step_a11y_title),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (isDone) {
                            stringResource(R.string.onboarding_step_complete)
                        } else {
                            stringResource(R.string.onboarding_step_a11y_subtitle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDone) DoneGreen else GlassDimText,
                    )
                }
            }

            if (!isDone) {
                Spacer(Modifier.height(16.dp))
                // Hint reads as a tagged label — bumped weight + slight letter
                // spacing (via labelMedium) so the eye separates the framing
                // sentence from the bullet rationale below it.
                Text(
                    text = stringResource(R.string.onboarding_step_a11y_hint),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassWhite,
                )
                Spacer(Modifier.height(8.dp))
                // Bullets share one Column with `spacedBy` instead of three
                // adjacent Texts, so vertical rhythm is consistent and a
                // future entry doesn't drift out of alignment with the others.
                // The Column is announced with a single descriptive label and
                // each bullet's contentDescription overrides its visible text
                // to suppress TalkBack reading the literal "•" glyph aloud.
                val listLabel = stringResource(
                    R.string.onboarding_step_a11y_disclosure_list_cd,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.semantics { contentDescription = listLabel },
                ) {
                    listOf(
                        R.string.onboarding_step_a11y_disclosure_1,
                        R.string.onboarding_step_a11y_disclosure_2,
                        R.string.onboarding_step_a11y_disclosure_3,
                    ).forEach { resId ->
                        val spoken = stringResource(resId)
                        Text(
                            text = "•  $spoken",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassDimText,
                            modifier = Modifier.semantics {
                                contentDescription = spoken
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_step_a11y_open_settings))
                }
            }
        }
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
                        stringResource(
                            R.string.onboarding_step_title,
                            stepNumber,
                            stringResource(R.string.onboarding_step_model_title),
                        ),
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
