package com.safeword.android.service

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.safeword.android.R
import com.safeword.android.transcription.TranscriptionState

/**
 * Floating mic button composable drawn as a system overlay.
 *
 * Visual states:
 * - Idle/Done/Error: mic icon on round background
 * - Recording: red pulsing stop icon
 * - Streaming: blue pulsing mic icon + live text preview chip below
 * - Transcribing: spinner
 *
 * @param isDarkMode Controls round background colour (dark grey or light grey).
 */
@Composable
fun OverlayMicButton(
    state: TranscriptionState,
    draftText: String,
    isDarkMode: Boolean,
    onDraftTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording = state is TranscriptionState.Recording
    val isTranscribing = state is TranscriptionState.Transcribing
    val isStreaming = state is TranscriptionState.Streaming
    val showDraftField = isRecording || isStreaming

    // Pulse while recording or streaming
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

    val backgroundColor = if (isDarkMode) Color(0xFF2D2D2D) else Color(0xFFF5F5F5)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(48.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(backgroundColor),
        ) {
            // Always show the mic image as the base
            Image(
                painter = painterResource(R.drawable.sw_button),
                contentDescription = "Start recording",
                modifier = Modifier.size(36.dp),
            )

            // Overlay state indicators on top of the mic image
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
                        contentDescription = "Transcribing",
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
                        contentDescription = "Streaming transcription",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                isRecording -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xAAD32F2F)),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop recording",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        if (showDraftField) {
            OutlinedTextField(
                value = draftText,
                onValueChange = onDraftTextChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.overlay_draft_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xE61E1E1E),
                    unfocusedContainerColor = Color(0xCC202124),
                    focusedBorderColor = Color(0xFF4FC3F7),
                    unfocusedBorderColor = Color(0x6690CAF9),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedPlaceholderColor = Color(0xB3FFFFFF),
                    unfocusedPlaceholderColor = Color(0x80FFFFFF),
                ),
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 320.dp)
                    .padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.overlay_draft_hint),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
