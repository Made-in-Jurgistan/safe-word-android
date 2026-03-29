package com.safeword.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safeword.android.ui.theme.CobaltBright
import com.safeword.android.ui.theme.CobaltGlow
import com.safeword.android.ui.theme.DoneGreen
import com.safeword.android.ui.theme.GlassBg
import com.safeword.android.ui.theme.GlassDarkSurface
import com.safeword.android.ui.theme.GlassPanel
import com.safeword.android.ui.theme.GlassDimText
import com.safeword.android.ui.theme.GlassWhite
import com.safeword.android.ui.theme.SilverBright

// ---------------------------------------------------------------------------
// GlassSurface — full-screen gradient backdrop
// ---------------------------------------------------------------------------

/**
 * Screen-level gradient background for the glass UI language.
 * Pair with [GlassCard] and [glowEffect] for a cohesive futuristic look.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    GlassBg,
                    GlassDarkSurface,
                    Color(0xFF0A1220),
                ),
            ),
        ),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// glowEffect — BlurMaskFilter bloom behind any composable
// ---------------------------------------------------------------------------

/**
 * Draws a soft bloom behind this composable using [android.graphics.BlurMaskFilter].
 *
 * @param glowColor   Colour of the halo — typically [CobaltBright] or [DoneGreen].
 * @param glowRadius  Kernel sigma; larger = softer and wider bloom.
 * @param cornerRadius Must match the clipped shape of the target composable.
 */
fun Modifier.glowEffect(
    glowColor: Color,
    glowRadius: Dp = 18.dp,
    cornerRadius: Dp = 20.dp,
): Modifier = drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.isAntiAlias = true
        frameworkPaint.maskFilter = android.graphics.BlurMaskFilter(
            glowRadius.toPx(),
            android.graphics.BlurMaskFilter.Blur.NORMAL,
        )
        paint.color = glowColor
        canvas.drawRoundRect(
            left = 0f,
            top = 0f,
            right = size.width,
            bottom = size.height,
            radiusX = cornerRadius.toPx(),
            radiusY = cornerRadius.toPx(),
            paint = paint,
        )
    }
}

// ---------------------------------------------------------------------------
// GlassCard — frosted liquid-glass panel
// ---------------------------------------------------------------------------

/**
 * Frosted glass card with a gradient cobalt/silver rim and soft bloom behind it.
 * Drop this anywhere a plain Material [androidx.compose.material3.Card] would be used.
 *
 * All content is placed inside an implicit [Column] so existing column lambdas work
 * without modification.
 *
 * @param glowColor      Primary glow tint (default cobalt blue).
 * @param glowAlpha      Bloom intensity 0–1.
 * @param doneTint       When true, shifts the palette to success-green (completed steps).
 * @param contentPadding Internal padding. Set to 0.dp when inner content provides its own.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    glowColor: Color = CobaltBright,
    glowAlpha: Float = 0.25f,
    doneTint: Boolean = false,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val effectiveGlow = if (doneTint) DoneGreen else glowColor
    val borderBrush = Brush.linearGradient(
        colors = if (doneTint) {
            listOf(
                DoneGreen.copy(alpha = 0.65f),
                SilverBright.copy(alpha = 0.20f),
                DoneGreen.copy(alpha = 0.35f),
            )
        } else {
            listOf(
                CobaltBright.copy(alpha = 0.60f),
                SilverBright.copy(alpha = 0.18f),
                CobaltGlow.copy(alpha = 0.40f),
            )
        },
        start = Offset(0f, 0f),
        end = Offset(700f, 400f),
    )
    val shimmerTint = if (doneTint) DoneGreen.copy(alpha = 0.07f) else CobaltBright.copy(alpha = 0.05f)
    val shape = RoundedCornerShape(cornerRadius)

    Column(
        modifier = modifier
            .glowEffect(
                glowColor = effectiveGlow.copy(alpha = glowAlpha),
                glowRadius = 20.dp,
                cornerRadius = cornerRadius,
            )
            .clip(shape)
            .background(GlassPanel)
            .background(
                Brush.linearGradient(
                    colors = listOf(shimmerTint, Color.White.copy(alpha = 0.04f), Color.Transparent),
                    start = Offset(0f, 0f),
                    end = Offset(800f, 600f),
                ),
            )
            .border(width = 1.dp, brush = borderBrush, shape = shape)
            .padding(contentPadding),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// GlassStepBadge — circular numbered step indicator
// ---------------------------------------------------------------------------

/**
 * Circular badge showing a step number or checkmark with a cobalt/green glow ring.
 * Used in onboarding step cards.
 */
@Composable
fun GlassStepBadge(
    number: Int,
    isDone: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (isDone) DoneGreen else CobaltBright
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .size(36.dp)
            .glowEffect(glowColor = accent.copy(alpha = 0.40f), glowRadius = 12.dp, cornerRadius = 18.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.15f))
            .border(1.5.dp, accent.copy(alpha = 0.75f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isDone) "✓" else number.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

// ---------------------------------------------------------------------------
// GlassDivider — gradient cobalt micro-line separator
// ---------------------------------------------------------------------------

/**
 * A cobalt-silver gradient divider line for use inside glass cards.
 * Replace [androidx.compose.material3.HorizontalDivider] in glass UIs.
 */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        CobaltBright.copy(alpha = 0.28f),
                        SilverBright.copy(alpha = 0.16f),
                        CobaltBright.copy(alpha = 0.28f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

// ---------------------------------------------------------------------------
// GlassSectionHeader — cobalt section label with accent underline
// ---------------------------------------------------------------------------

/**
 * Section header with a cobalt-highlight title and a short glowing underline bar.
 * Use in place of the plain [Text] section labels in settings and similar screens.
 */
@Composable
fun GlassSectionHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = CobaltGlow,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(2.dp)
                .glowEffect(
                    glowColor = CobaltBright.copy(alpha = 0.55f),
                    glowRadius = 5.dp,
                    cornerRadius = 1.dp,
                )
                .clip(RoundedCornerShape(1.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(CobaltBright, CobaltGlow.copy(alpha = 0.4f)),
                    ),
                ),
        )
    }
}

// ---------------------------------------------------------------------------
// GlassRow — labelled info row for settings/about sections
// ---------------------------------------------------------------------------

/**
 * A horizontal label–value row styled for glass cards.
 * Equivalent to the plain InfoRow but with glass-appropriate typography colours.
 */
@Composable
fun GlassInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = SilverBright,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = CobaltGlow,
        )
    }
}

// ---------------------------------------------------------------------------
// GlassListItem — Material 3 ListItem tuned for glass UI
// ---------------------------------------------------------------------------

/**
 * Glass-styled wrapper for Material 3 [ListItem].
 * Use this for settings rows, pickers, and other list-based UI.
 */
@Composable
fun GlassListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }

    ListItem(
        headlineContent = headlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        modifier = modifier.then(clickModifier),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = GlassWhite,
            supportingColor = GlassDimText,
            leadingIconColor = CobaltGlow,
            trailingIconColor = SilverBright,
        ),
    )
}
