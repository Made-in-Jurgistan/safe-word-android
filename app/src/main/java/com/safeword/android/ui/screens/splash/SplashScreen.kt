package com.safeword.android.ui.screens.splash

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.widget.FrameLayout
import androidx.annotation.OptIn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.safeword.android.ui.theme.GlassOutlineColor
import com.safeword.android.ui.theme.GlassPanel
import com.safeword.android.ui.theme.GlassPanelHigh
import com.safeword.android.ui.theme.SilverBright
import com.safeword.android.ui.theme.SilverDim
import kotlinx.coroutines.delay

/**
 * Splash screen phases:
 * 1. noise.gif — static noise
 * 2. safeword_start.mp4 — glitch intro video
 * 3. g.png — logo displayed for 2 seconds with neon flicker
 * Then navigates to onboarding.
 */
private enum class SplashPhase { NOISE, VIDEO, LOGO }

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

            SplashPhase.VIDEO -> FramedVideoStage(
                rawResId = R.raw.safeword_start,
                onVideoEnded = { phase = SplashPhase.LOGO },
            )

            SplashPhase.LOGO -> NeonFlickerLogo(onFinished = onFinished)
        }
    }
}

// ---------- Phase 1: Animated GIF via ImageDecoder (API 28+) ----------

@Composable
private fun NoiseGif() {
    val context = LocalContext.current
    val drawable = remember {
        val source = ImageDecoder.createSource(context.resources, R.raw.noise)
        val dec = ImageDecoder.decodeDrawable(source)
        (dec as? AnimatedImageDrawable)?.also { it.start() }
    }

    if (drawable != null) {
        // Render animated drawable into a Canvas-backed composable
        val bitmap = remember {
            android.graphics.Bitmap.createBitmap(
                drawable.intrinsicWidth,
                drawable.intrinsicHeight,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        }
        val canvas = remember { android.graphics.Canvas(bitmap) }
        var tick by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(33L) // ~30 fps
                tick++
            }
        }

        // Suppress unused-value lint — tick access forces recomposition
        @Suppress("UNUSED_EXPRESSION")
        tick

        drawable.draw(canvas)
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

// ---------- Phase 2: Video via Media3 ExoPlayer ----------

@OptIn(UnstableApi::class)
@Composable
private fun FramedVideoStage(rawResId: Int, onVideoEnded: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        VideoFrame(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 18.dp),
        ) {
            VideoPlayer(
                rawResId = rawResId,
                onVideoEnded = onVideoEnded,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black),
            )
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
            val uri = Uri.parse("android.resource://${context.packageName}/$rawResId")
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
    frameThickness: Dp = 32.dp,
    cornerRadius: Dp = 34.dp,
    content: @Composable () -> Unit,
) {
    val midInset = frameThickness * 0.35f
    val innerInset = frameThickness - midInset
    val contentCorner = (cornerRadius - frameThickness * 0.7f).coerceAtLeast(10.dp)
    val outerShape = RoundedCornerShape(cornerRadius)
    val midShape = RoundedCornerShape((cornerRadius - midInset).coerceAtLeast(12.dp))
    val innerShape = RoundedCornerShape((cornerRadius - frameThickness).coerceAtLeast(10.dp))

    Box(
        modifier = modifier
            .background(GlassPanel, outerShape)
            .padding(midInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .background(GlassPanelHigh, midShape)
                .padding(innerInset),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(GlassDarkSurface, innerShape)
                    .border(2.dp, GlassOutlineColor.copy(alpha = 0.7f), innerShape)
                    .border(2.dp, SilverBright.copy(alpha = 0.4f), innerShape)
                    .border(2.dp, SilverDim.copy(alpha = 0.4f), innerShape)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(contentCorner)),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}

// ---------- Phase 3: Logo with neon sign flicker ----------

@Composable
private fun NeonFlickerLogo(onFinished: () -> Unit) {
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
            .fillMaxSize()
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
