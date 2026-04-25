package com.safeword.android.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.safeword.android.R
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.components.GlassDivider
import com.safeword.android.ui.theme.CobaltGlow
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.SilverBright

@Composable
internal fun AccessibilityPage(
    stepIndex: Int,
    totalSteps: Int,
    visibleSteps: List<Int>,
    skippedSteps: Set<Int>,
    isDone: Boolean,
    restrictedState: OnboardingViewModel.RestrictedState,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRecheck: () -> Unit,
    onContinue: () -> Unit,
    showNeedHelpLink: Boolean = false,
    showStuckHint: Boolean,
    showAdvancedStuckHint: Boolean = false,
) {
    var a11yAttemptCount by rememberSaveable { mutableIntStateOf(0) }
    var disclosureAccepted by rememberSaveable { mutableStateOf(false) }

    val needsRestrictionUnlock =
        restrictedState != OnboardingViewModel.RestrictedState.ALLOWED
    val lacksMenu = remember { DeviceStepsProvider.lacksRestrictionMenu() }

    val a11yFaqItems = listOf(
        stringResource(R.string.onboarding_stuck_a11y_samsung),
        stringResource(R.string.onboarding_stuck_a11y_restart),
        stringResource(R.string.onboarding_stuck_a11y_not_found),
    )

    OnboardingStepPage(
        stepIndex = stepIndex,
        totalSteps = totalSteps,
        visibleSteps = visibleSteps,
        skippedSteps = skippedSteps,
        title = stringResource(R.string.onboarding_step_a11y_title),
        description = stringResource(R.string.onboarding_step_a11y_subtitle),
        isDone = isDone,
        doneLabel = stringResource(R.string.onboarding_step_complete),
        onContinue = onContinue,
        continueEnabled = isDone,
        whyText = stringResource(R.string.onboarding_step_a11y_why),
        whySummary = stringResource(R.string.onboarding_a11y_why_summary),
        showNeedHelpLink = showNeedHelpLink,
        showStuckHint = showStuckHint,
        showAdvancedStuckHint = showAdvancedStuckHint,
        stuckHintExtraFaq = a11yFaqItems,
        onRecheck = onRecheck,
    ) {
        if (!isDone) {
            // --- Prominent disclosure (required by Google Play) ---
            if (!disclosureAccepted) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 16.dp,
                ) {
                    Text(
                        stringResource(R.string.onboarding_a11y_disclosure_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SilverBright,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.onboarding_a11y_disclosure_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassDimText,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { disclosureAccepted = true },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp),
                ) {
                    Text(
                        stringResource(R.string.onboarding_a11y_disclosure_consent),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                // --- Post-disclosure: single unified enablement view ---

                // OEM-specific path hint (e.g., "Settings → Accessibility → Safe Word")
                val pathHint = remember { DeviceStepsProvider.accessibilityPathHint() }
                if (pathHint != null) {
                    Text(
                        pathHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CobaltGlow,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                // Main action button — always "Open Accessibility Settings"
                Button(
                    onClick = {
                        a11yAttemptCount++
                        onOpenAccessibility()
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp),
                ) {
                    Text(
                        stringResource(R.string.onboarding_step_a11y_action),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // OEM-specific step-by-step walkthrough, always visible
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 16.dp,
                ) {
                    val guideSteps = remember { DeviceStepsProvider.accessibilitySteps() }
                    guideSteps.forEachIndexed { index, text ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = CobaltGlow,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = GlassDimText,
                            )
                        }
                    }
                }

                // --- Collapsible restriction-unlock helper (sideloaded users only) ---
                if (needsRestrictionUnlock) {
                    Spacer(Modifier.height(16.dp))
                    RestrictionUnlockHelper(
                        restrictedState = restrictedState,
                        lacksMenu = lacksMenu,
                        onOpenAppInfo = onOpenAppInfo,
                        onOpenAccessibility = {
                            a11yAttemptCount++
                            onOpenAccessibility()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Collapsible helper section for sideloaded users who need to unlock
 * Android's restricted-settings gate before the accessibility toggle works.
 */
@Composable
private fun RestrictionUnlockHelper(
    restrictedState: OnboardingViewModel.RestrictedState,
    lacksMenu: Boolean,
    onOpenAppInfo: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Auto-expand when state transitions to TRIGGERED (user has seen the dialog)
    val autoExpand = restrictedState == OnboardingViewModel.RestrictedState.TRIGGERED
    if (autoExpand && !expanded) expanded = true

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp,
    ) {
        Text(
            stringResource(R.string.onboarding_a11y_trouble_title),
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
            @Suppress("KotlinConstantConditions")
            when (restrictedState) {
                OnboardingViewModel.RestrictedState.BLOCKED -> {
                    // User hasn't triggered the restriction dialog yet
                    GlassDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.onboarding_a11y_prewarn_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassDimText,
                    )
                }

                OnboardingViewModel.RestrictedState.TRIGGERED -> {
                    // Restriction dialog seen — guide to App Info unlock
                    GlassDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.onboarding_a11y_trouble_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassDimText,
                    )
                    Spacer(Modifier.height(12.dp))

                    val unlockSteps = remember { DeviceStepsProvider.restrictionUnlockSteps() }
                    unlockSteps.forEachIndexed { index, text ->
                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = CobaltGlow,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = GlassDimText,
                            )
                        }
                    }

                    if (lacksMenu) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.onboarding_a11y_no_menu_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassDimText,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onOpenAppInfo,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp),
                    ) {
                        Text(
                            stringResource(R.string.onboarding_a11y_trouble_action),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        stringResource(R.string.onboarding_a11y_trouble_restart),
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassDimText,
                    )
                }

                OnboardingViewModel.RestrictedState.ALLOWED -> {
                    // Should not show this section if ALLOWED, but handle gracefully
                }
            }
        }
    }
}
