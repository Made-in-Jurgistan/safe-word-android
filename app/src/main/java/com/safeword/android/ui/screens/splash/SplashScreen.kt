package com.safeword.android.ui.screens.splash

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.safeword.android.R
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.CobaltDeep
import com.safeword.android.ui.theme.CobaltGlow
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

@Composable
private fun FramedTaglineStage(onFinished: () -> Unit) {
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
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        VideoFrame(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(GlassBg),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.g),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.alpha(combinedAlpha),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

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

            SplashPhase.LOGO -> FramedTaglineStage(onFinished = onFinished)
        }
    }
}

// ---------- Phase 1: Animated GIF via ImageDecoder (API 28+) ----------

@Composable
private fun NoiseGif() {
    val context = LocalContext.current
    // Decode once; tolerate both animated and non-animated drawables. If R.raw.noise
    // ever ships as a static frame the splash still renders that frame instead of
    // showing nothing. AnimatedImageDrawable advances its own internal frame state
    // when start() has been called and time elapses; we only need to redraw it.
    val drawable = remember {
        runCatching {
            val source = ImageDecoder.createSource(context.resources, R.raw.noise)
            ImageDecoder.decodeDrawable(source).also { dec ->
                (dec as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            }
        }.getOrNull()
    } ?: return

    // Drive a per-frame ticker via withFrameNanos. Reading frameTick.intValue
    // inside the Canvas draw scope subscribes the draw to it, so each new
    // value invalidates the draw and pulls the next AnimatedImageDrawable
    // frame. The previous bitmap+SideEffect approach never recomposed because
    // tick was only read inside SideEffect, which does not subscribe state.
    val frameTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { /* no-op: schedule on the choreographer */ }
            frameTick.intValue++
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") frameTick.intValue
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w > 0 && h > 0) {
            drawable.setBounds(0, 0, w, h)
            drawIntoCanvas { canvas -> drawable.draw(canvas.nativeCanvas) }
        }
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
                .padding(horizontal = 12.dp),
            frameThickness = 56.dp,
            cornerRadius = 38.dp,
        ) {
            VideoPlayer(
                rawResId = rawResId,
                onVideoEnded = onVideoEnded,
                modifier = Modifier.fillMaxWidth(),
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
    // Track the latest callback so the listener never holds a stale closure.
    val currentOnVideoEnded by rememberUpdatedState(onVideoEnded)

    // Aspect ratio derived from the actual decoded stream — the VideoFrame's
    // height is sized from this, so the player fills the container with no
    // letterbox/pillarbox regardless of whether the asset is 16:9, 4:3, etc.
    // Seeded at 16:9 to give layout something to measure before the first
    // onVideoSizeChanged callback fires.
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
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
                    currentOnVideoEnded()
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                    if (ratio.isFinite() && ratio > 0f) {
                        videoAspectRatio = ratio
                    }
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
                // FILL: the surface fully covers the container. Combined with the
                // container being sized to the actual video aspect ratio, this
                // shows the entire frame edge-to-edge without bars.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // Decorative intro video: hide from TalkBack so the splash
                // doesn't announce a generic "video" node between the noise
                // and logo phases.
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(videoAspectRatio),
    )
}

@Composable
private fun VideoFrame(
    modifier: Modifier = Modifier,
    frameThickness: Dp = 56.dp,
    cornerRadius: Dp = 38.dp,
    content: @Composable () -> Unit,
) {
    val midInset = frameThickness * 0.45f
    val innerInset = frameThickness - midInset
    val contentCorner = (cornerRadius - frameThickness * 0.7f).coerceAtLeast(10.dp)
    val outerShape = RoundedCornerShape(cornerRadius)
    val midShape = RoundedCornerShape((cornerRadius - midInset).coerceAtLeast(14.dp))
    val innerShape = RoundedCornerShape((cornerRadius - frameThickness).coerceAtLeast(10.dp))

    // Diagonal gradients give the bezel its "lit from upper-left" 3D appearance.
    // The outer ring reads as a polished metal lip, the middle ring as a darker
    // recessed groove, and the inner ring as a glowing inset socket.
    val outerBezel = Brush.linearGradient(
        colors = listOf(
            CobaltBright.copy(alpha = 0.95f),
            GlassBg.copy(alpha = 0.92f),
            CobaltDeep.copy(alpha = 0.95f),
        ),
        start = Offset(0f, 0f),
        end = Offset.Infinite,
    )
    val midBezel = Brush.linearGradient(
        colors = listOf(
            GlassDarkSurface.copy(alpha = 0.92f),
            CobaltDeep.copy(alpha = 0.55f),
            GlassBg.copy(alpha = 0.85f),
        ),
        start = Offset.Infinite,
        end = Offset(0f, 0f),
    )
    val innerSocket = Brush.linearGradient(
        colors = listOf(
            GlassDarkSurface.copy(alpha = 0.98f),
            GlassBg.copy(alpha = 0.92f),
        ),
        start = Offset(0f, 0f),
        end = Offset.Infinite,
    )

    Box(
        modifier = modifier
            .shadow(elevation = 36.dp, shape = outerShape, clip = false)
            .background(outerBezel, outerShape)
            .border(3.dp, CobaltGlow.copy(alpha = 0.55f), outerShape)
            .padding(midInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .shadow(elevation = 14.dp, shape = midShape, clip = false)
                .background(midBezel, midShape)
                .border(2.dp, CobaltBright.copy(alpha = 0.55f), midShape)
                .padding(innerInset),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .shadow(elevation = 6.dp, shape = innerShape, clip = false)
                    .background(innerSocket, innerShape)
                    .border(2.dp, CobaltGlow.copy(alpha = 0.7f), innerShape)
                    .border(1.dp, CobaltDeep.copy(alpha = 0.55f), innerShape)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(contentCorner)),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }
}

// ---------- Phase 3: Tagline/logo in same framed stage ----------
