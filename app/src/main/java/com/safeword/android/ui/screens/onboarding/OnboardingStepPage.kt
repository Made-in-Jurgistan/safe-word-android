package com.safeword.android.ui.screens.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.safeword.android.R
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.components.GlassDivider
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.CobaltGlow
import com.safeword.android.ui.theme.DoneGreen
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.GlassWhite
import com.safeword.android.ui.theme.SilverBright
import com.safeword.android.ui.theme.SilverDim

/**
 * Shared full-screen layout for each onboarding setup step.
 *
 * Renders: app icon, progress indicator + dots, title, description,
 * a done badge (when completed), custom action content, "Why?" expandable,
 * stuck hint, Continue button, and optional Skip button.
 */
@Composable
internal fun OnboardingStepPage(
    stepIndex: Int,
    totalSteps: Int,
    visibleSteps: List<Int>,
    skippedSteps: Set<Int>,
    title: String,
    description: String,
    isDone: Boolean,
    doneLabel: String,
    onContinue: () -> Unit,
    continueEnabled: Boolean,
    continueLabel: String = stringResource(R.string.onboarding_continue),
    whyText: String? = null,
    whySummary: String? = null,
    showNeedHelpLink: Boolean = false,
    showStuckHint: Boolean = false,
    showAdvancedStuckHint: Boolean = false,
    stuckHintExtraFaq: List<String> = emptyList(),
    onRecheck: (() -> Unit)? = null,
    skippable: Boolean = false,
    onSkip: (() -> Unit)? = null,
    actionContent: @Composable ColumnScope.() -> Unit = {},
) {
    var whyExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.safeword_icon),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )

        Spacer(Modifier.height(16.dp))

        // "Step X of Y"
        Text(
            stringResource(R.string.onboarding_step_of, stepIndex + 1, totalSteps),
            style = MaterialTheme.typography.labelLarge,
            color = SilverBright,
        )

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { (stepIndex + 1).toFloat() / totalSteps },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        StepProgressDots(
            currentIndex = stepIndex,
            skippedSteps = skippedSteps,
            visibleSteps = visibleSteps,
        )

        Spacer(Modifier.height(28.dp))

        // Title
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = GlassWhite,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        // Description
        Text(
            description,
            style = MaterialTheme.typography.bodyLarge,
            color = GlassDimText,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        // Done badge
        if (isDone) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                doneTint = true,
                contentPadding = 16.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "\u2713",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DoneGreen,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        doneLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DoneGreen,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Per-step action content (buttons, download progress, etc.)
        actionContent()

        Spacer(Modifier.weight(1f))

        // "Why is this needed?" — inline summary + expandable detail
        if (whySummary != null) {
            Text(
                whySummary,
                style = MaterialTheme.typography.bodyMedium,
                color = GlassDimText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        if (whyText != null) {
            WhyExpandable(
                expanded = whyExpanded,
                onToggle = { whyExpanded = !whyExpanded },
                text = whyText,
                toggleLabel = if (whySummary != null) {
                    stringResource(R.string.onboarding_learn_more_detail)
                } else {
                    stringResource(R.string.onboarding_learn_more)
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        // Subtle "Need help?" link shown early (15 s) before the full stuck hint
        AnimatedVisibility(
            visible = showNeedHelpLink && !showStuckHint,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                stringResource(R.string.onboarding_need_help),
                style = MaterialTheme.typography.bodyLarge,
                color = CobaltGlow,
                modifier = Modifier
                    .clickable(role = Role.Button) {
                        // Scroll to the bottom when stuck hint is about to appear anyway
                    }
                    .padding(horizontal = 8.dp)
                    .defaultMinSize(minHeight = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }

        // Stuck hint
        if (showStuckHint) {
            StuckHintSection(
                onRecheck = onRecheck,
                showAdvancedHint = showAdvancedStuckHint,
                extraFaqItems = stuckHintExtraFaq,
            )
            Spacer(Modifier.height(12.dp))
        }

        // Continue button
        Button(
            onClick = onContinue,
            enabled = continueEnabled,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp),
        ) {
            Text(
                continueLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Skip button
        if (skippable && !isDone && onSkip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = SilverBright),
            ) {
                Text(
                    stringResource(R.string.onboarding_skip),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

// ---------- Progress dots ----------

@Composable
internal fun StepProgressDots(
    currentIndex: Int,
    skippedSteps: Set<Int>,
    visibleSteps: List<Int>,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        visibleSteps.forEachIndexed { index, step ->
            val isCurrent = index == currentIndex
            val isDone = index < currentIndex || step in skippedSteps
            val color = when {
                isDone -> DoneGreen
                isCurrent -> CobaltBright
                else -> SilverDim
            }
            val dotSize = if (isCurrent) 10.dp else 8.dp
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color),
            )
            if (index < visibleSteps.lastIndex) Spacer(Modifier.width(8.dp))
        }
    }
}

// ---------- "Why is this needed?" expandable ----------

@Composable
internal fun WhyExpandable(
    expanded: Boolean,
    onToggle: () -> Unit,
    text: String,
    toggleLabel: String = stringResource(R.string.onboarding_learn_more),
) {
    Column {
        Text(
            toggleLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = CobaltGlow,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 8.dp)
                .defaultMinSize(minHeight = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = GlassDimText,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ---------- Stuck detection FAQ ----------

@Composable
internal fun StuckHintSection(
    onRecheck: (() -> Unit)? = null,
    showAdvancedHint: Boolean = false,
    extraFaqItems: List<String> = emptyList(),
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp,
    ) {
        Text(
            stringResource(R.string.onboarding_stuck_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = SilverBright,
            modifier = Modifier
                .clickable(role = Role.Button) { expanded = !expanded }
                .defaultMinSize(minHeight = 48.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                GlassDivider(modifier = Modifier.padding(vertical = 8.dp))
                val faqItems = listOf(
                    stringResource(R.string.onboarding_stuck_toggle_greyed),
                    stringResource(R.string.onboarding_stuck_cant_find),
                    stringResource(R.string.onboarding_stuck_enabled_nothing),
                )
                faqItems.forEach { item ->
                    Text(
                        "\u2022 $item",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassDimText,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
                extraFaqItems.forEach { item ->
                    Text(
                        "\u2022 $item",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassDimText,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
                if (onRecheck != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRecheck,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.onboarding_stuck_recheck),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (showAdvancedHint) {
                    GlassDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.onboarding_stuck_advanced_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SilverBright,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri(),
                            )
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.onboarding_stuck_open_settings),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
