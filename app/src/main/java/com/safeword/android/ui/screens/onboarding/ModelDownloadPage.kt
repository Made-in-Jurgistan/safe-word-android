package com.safeword.android.ui.screens.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.safeword.android.R
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.ui.components.GlassCard
import com.safeword.android.ui.theme.GlassDimText

@Composable
internal fun ModelDownloadPage(
    stepIndex: Int,
    totalSteps: Int,
    visibleSteps: List<Int>,
    skippedSteps: Set<Int>,
    downloadState: ModelDownloadState,
    modelReady: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    whyText: String,
) {
    val modelSizeMb = 235
    val statusText = when (downloadState) {
        is ModelDownloadState.NotDownloaded ->
            stringResource(
                R.string.onboarding_step_model_preparing,
                OnboardingViewModel.DEFAULT_MODEL_SIZE_DESC,
            )
        is ModelDownloadState.Downloading -> {
            val downloadedMb = (downloadState.progress * modelSizeMb).toInt()
            val percent = (downloadState.progress * 100).toInt()
            stringResource(
                R.string.onboarding_step_model_downloading_mb,
                downloadedMb,
                modelSizeMb,
                percent,
            )
        }
        is ModelDownloadState.Downloaded ->
            stringResource(R.string.onboarding_step_model_downloaded)
        is ModelDownloadState.Error -> {
            val msg = downloadState.message.lowercase()
            when {
                "network" in msg || "connect" in msg || "timeout" in msg ||
                    "unresolvedaddress" in msg ->
                    stringResource(R.string.onboarding_step_model_error_network)
                "space" in msg || "storage" in msg || "nospc" in msg ->
                    stringResource(R.string.onboarding_step_model_error_storage)
                else ->
                    stringResource(R.string.onboarding_step_model_error_generic)
            }
        }
    }

    OnboardingStepPage(
        stepIndex = stepIndex,
        totalSteps = totalSteps,
        visibleSteps = visibleSteps,
        skippedSteps = skippedSteps,
        title = stringResource(R.string.onboarding_step_model_title),
        description = stringResource(R.string.onboarding_step_model_subtitle),
        isDone = modelReady,
        doneLabel = stringResource(R.string.onboarding_step_model_downloaded),
        onContinue = onContinue,
        continueEnabled = modelReady,
        whyText = whyText,
    ) {
        if (!modelReady) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = 16.dp,
            ) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = when (downloadState) {
                        is ModelDownloadState.Error -> MaterialTheme.colorScheme.error
                        else -> GlassDimText
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (downloadState is ModelDownloadState.Downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (downloadState is ModelDownloadState.Error) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_step_model_retry))
                    }
                }
            }
        }
    }
}
