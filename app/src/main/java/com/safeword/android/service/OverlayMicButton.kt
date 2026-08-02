package com.safeword.android.service

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeword.android.R
import com.safeword.android.transcription.TranscriptionState

/**
 * Floating mic button composable drawn as a system overlay.
 *
 * Visual states:
 * - Idle/Done/Error: mic icon on round background
 * - Recording: red pulsing stop icon
 * - Streaming: blue pulsing mic icon + live editable transcript below
 * - Transcribing: static hourglass overlay
 *
 * The draft preview uses theme-neutral tokens tuned for system overlay context
 * (cannot rely on MaterialTheme from caller) with AA-contrast borders, a live
 * word counter, a clear-draft button, and state-driven placeholder copy.
 *
 * @param isDarkMode Controls round background colour (dark grey or light grey).
 */
@Composable
fun OverlayMicButton(
    state: TranscriptionState,
    draftText: String,
    isDarkMode: Boolean,
    onDraftTextChange: (String) -> Unit,
    onDraftFieldFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording = state is TranscriptionState.Recording
    val isTranscribing = state is TranscriptionState.Transcribing
    val isStreaming = state is TranscriptionState.Streaming
    val showDraftField = isRecording || isStreaming

    val pulse = if (isRecording || isStreaming) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "scale",
        )
        scale
    } else {
        1f
    }

    val backgroundColor = when {
        isRecording -> Color(0xFFD32F2F)
        isDarkMode -> Color(0xFF2D2D2D)
        else -> Color(0xFFF5F5F5)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(48.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(backgroundColor),
        ) {
            Image(
                painter = painterResource(R.drawable.sw_button),
                contentDescription = stringResource(R.string.overlay_cd_mic),
                modifier = Modifier.size(36.dp),
            )

            when {
                // Static indicator during transcription — avoids continuous GPU
                // draw calls that compete with Vulkan ML inference.
                isTranscribing -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xAA455A64)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.HourglassTop,
                        contentDescription = stringResource(R.string.overlay_cd_transcribing),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                isStreaming -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xAA1565C0)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MicNone,
                        contentDescription = stringResource(R.string.overlay_cd_streaming),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showDraftField,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
        ) {
            TranscriptDraftField(
                draftText = draftText,
                isStreaming = isStreaming,
                onDraftTextChange = onDraftTextChange,
                onDraftFieldFocused = onDraftFieldFocused,
            )
        }
    }
}

/**
 * Live editable transcript card. Shown only while recording/streaming and
 * commits to the focused app only after recording stops.
 */
@Composable
private fun TranscriptDraftField(
    draftText: String,
    isStreaming: Boolean,
    onDraftTextChange: (String) -> Unit,
    onDraftFieldFocused: () -> Unit,
) {
    val wordCount = remember(draftText) {
        if (draftText.isBlank()) 0 else draftText.trim().split(Regex("\\s+")).size
    }
    val placeholderRes = if (isStreaming) {
        R.string.overlay_draft_streaming
    } else {
        R.string.overlay_draft_listening
    }

    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .padding(top = 8.dp),
    ) {
        OutlinedTextField(
            value = draftText,
            onValueChange = onDraftTextChange,
            placeholder = {
                Text(
                    text = stringResource(placeholderRes),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                )
            },
            trailingIcon = {
                if (draftText.isNotEmpty()) {
                    IconButton(onClick = { onDraftTextChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.overlay_cd_clear_draft),
                            tint = Color(0xFFE8F0FE),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            minLines = 2,
            maxLines = 6,
            shape = RoundedCornerShape(14.dp),
            textStyle = TextStyle(fontSize = 14.sp, color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xF21A2030),
                unfocusedContainerColor = Color(0xE6121A28),
                focusedBorderColor = Color(0xFF2979FF),
                unfocusedBorderColor = Color(0xAA2979FF),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFF82B1FF),
                focusedPlaceholderColor = Color(0xCCE8F0FE),
                unfocusedPlaceholderColor = Color(0x99E8F0FE),
                focusedTrailingIconColor = Color(0xFFE8F0FE),
                unfocusedTrailingIconColor = Color(0xCCE8F0FE),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(6.dp, RoundedCornerShape(14.dp), clip = false)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) onDraftFieldFocused()
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.overlay_draft_commit_on_stop),
                color = Color(0xCCE8F0FE),
                fontSize = 10.sp,
            )
            Text(
                text = pluralStringResource(R.plurals.overlay_draft_word_count, wordCount, wordCount),
                color = Color(0xFF82B1FF),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
