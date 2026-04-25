package com.safeword.android.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.safeword.android.R
import com.safeword.android.transcription.TranscriptionState
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.CobaltGlow
import com.safeword.android.ui.theme.GlassPanel
import com.safeword.android.ui.theme.GlassWhite
import com.safeword.android.ui.theme.SilverBright

private val StopRed        = Color(0xFFD32F2F)
private val StopRedGlow    = Color(0xFFFF5252)
private val ErrorAmber     = Color(0xFFF57F17)
private val ErrorAmberGlow = Color(0xFFFFCA28)

/**
 * Floating mic button composable drawn as a system overlay.
 *
 * Visual states:
 * - Idle/Done/Error: mic icon on a dark glass circle with a cobalt glow rim
 * - Recording: red pulsing stop icon with red glow
 */
@Composable
fun OverlayMicButton(
    state: TranscriptionState,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val isRecording = state is TranscriptionState.Recording
    val isError = state is TranscriptionState.Error

    // Respect system reduced-motion setting — disable pulse/glow when animations are off.
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    // Pulse scale animation during recording (1.0→1.08, 800ms).
    val pulseScale = if (isRecording && !reduceMotion) {
        val pulseTransition = rememberInfiniteTransition(label = "pulse")
        val scale by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        scale
    } else {
        1f
    }

    // Background: red when recording, amber when error, otherwise glass panel per theme.
    val bgColor = when {
        isRecording -> StopRed
        isError -> ErrorAmber
        isDarkMode -> GlassPanel
        else -> GlassWhite
    }

    // Glow colour cycles between cobalt (idle/error) and red (recording).
    // Light mode needs a stronger bloom to show against the pale background.
    val glowColor = when {
        isRecording -> StopRedGlow.copy(alpha = 0.45f)
        isError -> ErrorAmberGlow.copy(alpha = 0.50f)
        else -> CobaltBright.copy(alpha = if (isDarkMode) 0.30f else 0.55f)
    }

    // Gradient border — cobalt→silver idle, red during recording, amber on error.
    // Light mode uses higher opacity so the ring is visible on the pale background.
    val borderBrush = when {
        isRecording -> Brush.linearGradient(
            listOf(StopRed.copy(alpha = 0.70f), StopRedGlow.copy(alpha = 0.35f)),
            start = Offset.Zero, end = Offset(80f, 80f),
        )
        isError -> Brush.linearGradient(
            listOf(ErrorAmber.copy(alpha = 0.80f), ErrorAmberGlow.copy(alpha = 0.50f)),
            start = Offset.Zero, end = Offset(80f, 80f),
        )
        isDarkMode -> Brush.linearGradient(
            listOf(CobaltBright.copy(alpha = 0.55f), SilverBright.copy(alpha = 0.20f), CobaltGlow.copy(alpha = 0.40f)),
            start = Offset.Zero, end = Offset(80f, 80f),
        )
        else -> Brush.linearGradient(
            listOf(CobaltBright.copy(alpha = 0.90f), SilverBright.copy(alpha = 0.55f), CobaltGlow.copy(alpha = 0.75f)),
            start = Offset.Zero, end = Offset(80f, 80f),
        )
    }

    // Outer wrapper: non-clipping, sized to absorb the pulse scale without being cut off.
    // At max pulse (1.08×) a 62dp circle reaches ~67dp, safely within the 80dp wrapper.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(80.dp),
    ) {
        // Inner circle: smaller in recording state so the pulsed size stays ≤ idle size.
        val circleSize = if (isRecording) 58.dp else 65.dp
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(circleSize)
                .scale(pulseScale)
                // Soft glow bloom behind the circle.
                .drawBehind {
                    drawIntoCanvas { canvas ->
                        val paint = Paint()
                        val fw = paint.asFrameworkPaint()
                        fw.isAntiAlias = true
                        fw.maskFilter = android.graphics.BlurMaskFilter(
                            18.dp.toPx(),
                            android.graphics.BlurMaskFilter.Blur.NORMAL,
                        )
                        paint.color = glowColor
                        canvas.drawCircle(
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.width / 2,
                            paint = paint,
                        )
                    }
                }
                .clip(CircleShape)
                .background(bgColor)
                .border(width = 1.dp, brush = borderBrush, shape = CircleShape),
        ) {
            Image(
                painter = painterResource(R.drawable.sw_button),
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                modifier = Modifier.size(if (isDarkMode) 36.dp else 40.dp),
            )
        }
    }
}
