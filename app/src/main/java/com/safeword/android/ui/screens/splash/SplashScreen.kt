package com.safeword.android.ui.screens.splash

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.safeword.android.R
import com.safeword.android.ui.theme.GlassBg
import com.safeword.android.ui.theme.GlassDarkSurface
import kotlinx.coroutines.delay

/**
 * Splash screen phases:
 * 1. noise.gif — static noise
 * 2. safeword_start.mp4 — glitch intro video
 * 3. g.png — logo displayed for 2 seconds with neon flicker
 * Then navigates to onboarding.
 */
private enum class SplashPhase { NOISE, VIDEO, LOGO }

/** Cobalt blue gradient stops for the video frame. */
private val FrameBlue1 = Color(0xFF007FF9)  // Brightest — top-left highlight
private val FrameBlue2 = Color(0xFF006BF4)
private val FrameBlue3 = Color(0xFF0059F2)
private val FrameBlue4 = Color(0xFF0042EF)
private val FrameBlue5 = Color(0xFF001DEB)  // Deepest — bottom-right shadow

@OptIn(UnstableApi::class)
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var phase by remember { mutableStateOf(SplashPhase.NOISE) }

    // --- Phase 1 → 2: show noise for ~1.2s then switch to video ---
    LaunchedEffect(Unit) {
        delay(1200L)
        phase = SplashPhase.VIDEO
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            SplashPhase.NOISE -> NoiseGif()

            SplashPhase.VIDEO -> FramedStage(
                rawResId = R.raw.safeword_start,
                showLogo = false,
                onVideoEnded = { phase = SplashPhase.LOGO },
                onFinished = {},
            )

            SplashPhase.LOGO -> FramedStage(
                rawResId = null,
                showLogo = true,
                onVideoEnded = {},
                onFinished = onFinished,
            )
        }
    }
}

// ---------- Phase 1: Animated GIF via ImageDecoder (API 28+) ----------

// LocalContextResourcesRead: ImageDecoder.createSource() requires Resources — no Context-only overload exists
@Suppress("LocalContextResourcesRead")
@Composable
private fun NoiseGif() {
    val context = LocalContext.current
    val drawable = remember {
        val source = ImageDecoder.createSource(context.resources, R.raw.noise)
        (ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable)
    }

    DisposableEffect(drawable) {
        drawable?.start()
        onDispose { drawable?.stop() }
    }
    AndroidView(
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                setImageDrawable(drawable)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---------- Phase 2 & 3: Shared framed stage ----------

@OptIn(UnstableApi::class)
@Composable
private fun FramedStage(
    rawResId: Int?,
    showLogo: Boolean,
    onVideoEnded: () -> Unit,
    onFinished: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        VideoFrame(
            modifier = Modifier.fillMaxWidth(),
            frameThickness = 36.dp,
            cornerRadius = 28.dp,
        ) {
            if (showLogo) {
                NeonFlickerLogoContent(onFinished = onFinished)
            } else if (rawResId != null) {
                VideoPlayer(
                    rawResId = rawResId,
                    onVideoEnded = onVideoEnded,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black),
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    rawResId: Int,
    onVideoEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val uri = "android.resource://${context.packageName}/$rawResId".toUri()
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onVideoEnded()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun VideoFrame(
    modifier: Modifier = Modifier,
    frameThickness: Dp = 36.dp,
    cornerRadius: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    val innerCorner = (cornerRadius - frameThickness).coerceAtLeast(8.dp)
    val outerShape = RoundedCornerShape(cornerRadius)
    val innerShape = RoundedCornerShape(innerCorner)

    // 3D frame: gradient from bright neon-blue top-left to deep blue bottom-right,
    // with highlight and shadow inset strokes for depth.
    val frameBrush = Brush.linearGradient(
        colors = listOf(FrameBlue1, FrameBlue2, FrameBlue3, FrameBlue4, FrameBlue5),
        start = Offset.Zero,
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    Box(
        modifier = modifier
            .background(brush = frameBrush, shape = outerShape)
            // Outer highlight edge (top-left light catch).
            .border(1.5.dp, FrameBlue1.copy(alpha = 0.6f), outerShape)
            // 3D depth: draw a bright inset highlight at the top and a dark shadow at the bottom.
            .drawBehind {
                val cr = cornerRadius.toPx()
                val ft = frameThickness.toPx()
                // Top-left highlight inset — simulates light hitting the bevel.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = ft,
                    ),
                    cornerRadius = CornerRadius(cr, cr),
                    size = Size(size.width, ft),
                )
                // Bottom-right shadow inset — simulates depth.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.35f),
                        ),
                        startY = size.height - ft,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - ft),
                    cornerRadius = CornerRadius(cr, cr),
                    size = Size(size.width, ft),
                )
                // Inner groove — dark ring just outside the content area.
                val inset = ft - 2.dp.toPx()
                val icr = innerCorner.toPx() + 2.dp.toPx()
                drawRoundRect(
                    color = FrameBlue5.copy(alpha = 0.7f),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(icr, icr),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            .padding(frameThickness),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .background(GlassDarkSurface, innerShape)
                .border(1.dp, FrameBlue4.copy(alpha = 0.6f), innerShape)
                .clip(innerShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// ---------- Phase 3: Logo with neon sign flicker (rendered inside the frame) ----------

@Composable
private fun NeonFlickerLogoContent(onFinished: () -> Unit) {
    // Navigate away after 2 seconds
    LaunchedEffect(Unit) {
        delay(2000L)
        onFinished()
    }

    // Neon flicker: rapid alpha oscillation simulating a buzzing neon sign
    val infiniteTransition = rememberInfiniteTransition(label = "neonFlicker")
    val flicker by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flickerAlpha",
    )

    // Layer a secondary slower pulse for realistic neon feel
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val combinedAlpha = (flicker * pulse).coerceIn(0.15f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(GlassBg),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.g),
            contentDescription = "Safe Word",
            modifier = Modifier.alpha(combinedAlpha),
            contentScale = ContentScale.Fit,
        )
    }
}
