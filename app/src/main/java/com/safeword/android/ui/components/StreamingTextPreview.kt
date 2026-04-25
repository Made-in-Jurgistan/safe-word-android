package com.safeword.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeword.android.R
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.CobaltGlow
import com.safeword.android.ui.theme.GlassOutlineVar
import com.safeword.android.ui.theme.GlassPanel
import com.safeword.android.ui.theme.GlassWhite
import com.safeword.android.ui.theme.SilverBright
import com.safeword.android.ui.theme.SilverDim

private val DarkTextBody  = Color(0xFF1A2840)
private val DarkTextDim   = Color(0xFF4A5568)
private val AccentCheck   = Color(0xFF4A8CFF)

/** Monospace gives the bubble a futuristic terminal aesthetic. */
private val BubbleFont = FontFamily.Monospace

/**
 * Floating glass text bubble shown during streaming transcription.
 *
 * Visual language:
 * - Deep-charcoal glass panel with a pulsing cobalt glow bloom
 * - Gradient cobalt → silver rim to match GlassCard
 * - Shimmer highlight across the top-left corner
 * - Monospace font for that futuristic terminal look
 * - Inserted text in dim silver with a check icon; pending text in bright silver
 * - Auto-scrolls to keep latest text visible
 */
@Composable
fun StreamingTextPreview(
    text: String,
    insertedText: String,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier,
    isActivelyTranscribing: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val rawPending = pendingText(text, insertedText)

    // Auto-fade after 15s of inactivity — track when text last changed.
    var lastTextChangeTime by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var showPreview by remember { mutableStateOf(true) }
    var displayPending by remember { mutableStateOf("") }

    LaunchedEffect(rawPending, isActivelyTranscribing) {
        if (rawPending.isNotBlank()) {
            displayPending = rawPending
            return@LaunchedEffect
        }
        if (isActivelyTranscribing && displayPending.isNotBlank()) {
            kotlinx.coroutines.delay(180L)
            if (pendingText(text, insertedText).isBlank()) {
                displayPending = ""
            }
        } else {
            displayPending = ""
        }
    }

    LaunchedEffect(text) {
        lastTextChangeTime = android.os.SystemClock.elapsedRealtime()
        showPreview = true
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    LaunchedEffect(lastTextChangeTime) {
        kotlinx.coroutines.delay(15_000L)
        if (!isActivelyTranscribing) {
            showPreview = false
        }
    }

    // Pulsing glow intensity — only while actively transcribing.
    val glowAlpha = if (isActivelyTranscribing) {
        val infiniteTransition = rememberInfiniteTransition(label = "glow")
        val animated by infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.40f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowAlpha",
        )
        animated
    } else {
        0.15f
    }

    // Travelling shimmer highlight — only while actively transcribing.
    val shimmerOffset = if (isActivelyTranscribing) {
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
        val animated by infiniteTransition.animateFloat(
            initialValue = -200f,
            targetValue = 800f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        )
        animated
    } else {
        -200f
    }

    AnimatedVisibility(
        visible = (insertedText.isNotBlank() || displayPending.isNotBlank()) && showPreview,
        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(250)),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(14.dp)

        // Cobalt→silver gradient border (matches GlassCard).
        val borderBrush = Brush.linearGradient(
            colors = listOf(
                CobaltBright.copy(alpha = 0.55f),
                SilverBright.copy(alpha = 0.18f),
                CobaltGlow.copy(alpha = 0.35f),
            ),
            start = Offset.Zero,
            end = Offset(700f, 400f),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                // Cobalt glow bloom behind the card.
                .drawBehind {
                    drawIntoCanvas { canvas ->
                        val paint = Paint()
                        val fw = paint.asFrameworkPaint()
                        fw.isAntiAlias = true
                        fw.maskFilter = android.graphics.BlurMaskFilter(
                            18.dp.toPx(),
                            android.graphics.BlurMaskFilter.Blur.NORMAL,
                        )
                        paint.color = CobaltBright.copy(alpha = glowAlpha)
                        canvas.drawRoundRect(
                            left = 0f, top = 0f,
                            right = size.width, bottom = size.height,
                            radiusX = 14.dp.toPx(), radiusY = 14.dp.toPx(),
                            paint = paint,
                        )
                    }
                }
                .clip(shape)
                // Base panel — switches with user dark/light preference.
                .background(if (isDarkMode) GlassPanel else GlassWhite)
                // Travelling shimmer highlight.
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            CobaltGlow.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        start = Offset(shimmerOffset, 0f),
                        end = Offset(shimmerOffset + 250f, 180f),
                    ),
                )
                .border(width = 1.dp, brush = borderBrush, shape = shape)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                // ── Inserted block: already committed to the text field ──
                if (insertedText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.streaming_text_inserted),
                            tint = AccentCheck,
                            modifier = Modifier
                                .size(13.dp)
                                .alpha(0.75f),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = insertedText,
                            color = if (isDarkMode) SilverDim else DarkTextDim,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            fontFamily = BubbleFont,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.3.sp,
                        )
                    }
                }

                // ── Pending partial: text still being recognized ──
                if (displayPending.isNotBlank()) {
                    if (insertedText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = displayPending,
                        color = if (isDarkMode) SilverBright else DarkTextBody,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = BubbleFont,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp,
                    )
                }
            }
        }
    }
}

/**
 * Derive the pending (not-yet-inserted) portion from the full partial text.
 */
private fun pendingText(fullText: String, insertedText: String): String {
    if (insertedText.isBlank()) return fullText
    val normalizedInserted = insertedText.trimStart()
    return if (fullText.startsWith(normalizedInserted)) {
        fullText.removePrefix(normalizedInserted).trimStart()
    } else {
        fullText
    }
}
