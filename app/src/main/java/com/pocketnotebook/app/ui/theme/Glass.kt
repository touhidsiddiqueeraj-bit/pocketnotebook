package com.pocketnotebook.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width

// ---------------------------------------------------------------------------
// Aurora background: drifting color blobs behind everything — the "liquid"
// that the glass panels refract.
// ---------------------------------------------------------------------------

@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    val glass = LocalGlassColors.current
    val base = MaterialTheme.colorScheme.background
    val transition = rememberInfiniteTransition(label = "aurora")

    val drifts = listOf(
        rememberDrift(transition, duration = 26000, from = 0.08f, to = 0.75f, seed = 0.13f),
        rememberDrift(transition, duration = 31000, from = 0.62f, to = 0.20f, seed = 0.71f),
        rememberDrift(transition, duration = 37000, from = 0.40f, to = 0.88f, seed = 0.45f),
    )

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                listOf(base, base.copy(alpha = 0.86f), base),
            ),
        )

        val blobs = listOf(
            Triple(glass.accent, 0.55f, 0.30f),
            Triple(glass.accent2, 0.85f, 0.36f),
            Triple(glass.accent3, 0.15f, 0.55f),
            Triple(glass.accent2, 0.40f, 0.85f),
        )
        blobs.forEachIndexed { i, (color, bx, by) ->
            val d = drifts[i % drifts.size].value
            val cx = w * bx + w * 0.18f * (d - 0.5f)
            val cy = h * by + h * 0.10f * (0.5f - d)
            val r = size.minDimension() * (0.42f + 0.10f * i)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.16f * intensity), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
    }
}

@Composable
private fun rememberDrift(
    transition: androidx.compose.animation.core.InfiniteTransition,
    duration: Int,
    from: Float,
    to: Float,
    seed: Float,
): androidx.compose.State<Float> =
    transition.animateFloat(
        initialValue = if (seed > 0.5f) from else to,
        targetValue = if (seed > 0.5f) to else from,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift$seed",
    )

// ---------------------------------------------------------------------------
// Glass panels
// ---------------------------------------------------------------------------

/**
 * The core surface: translucent gradient fill, 1px light border, soft shadow
 * and a specular highlight along the top edge. Optional press-scale.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 26.dp,
    fillTop: Color? = null,
    fillBottom: Color? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val glass = LocalGlassColors.current
    val shape = RoundedCornerShape(corner)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .shadow(8.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        fillTop ?: glass.fillTop,
                        fillBottom ?: glass.fillBottom,
                    ),
                ),
            )
            .border(0.75.dp, Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)), shape)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongClick?.invoke() },
                    )
                } else Modifier,
            ),
    ) {
        // Specular highlight: a thin bright arc along the top edge.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .matchParentSize(),
        ) {
            val y = size.height * 0.015f
            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(size.width * 0.10f, y + 4f),
                end = Offset(size.width * 0.55f, y + 4f),
                strokeWidth = 2f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            content()
        }
    }
}

/** Row-style compact glass card (lists). Long-press optional. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassRow(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val glass = LocalGlassColors.current
    val shape = RoundedCornerShape(corner)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(5.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(glass.fillTop, glass.fillBottom)))
            .border(0.75.dp, Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)), shape)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongClick?.invoke() },
                    )
                } else Modifier,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

// ---------------------------------------------------------------------------
// Buttons / chips / inputs
// ---------------------------------------------------------------------------

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val glass = LocalGlassColors.current
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .shadow(if (primary) 10.dp else 4.dp, shape, spotColor = if (primary) glass.glow else Color(0x22000000))
            .clip(shape)
            .background(
                if (primary) {
                    Brush.linearGradient(listOf(glass.accent.copy(alpha = 0.95f), glass.accent2.copy(alpha = 0.92f)))
                } else {
                    Brush.linearGradient(listOf(glass.fillTop, glass.fillBottom))
                },
            )
            .border(
                0.75.dp,
                if (primary) Brush.verticalGradient(listOf(Color.White.copy(0.55f), Color.White.copy(0.12f)))
                else Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)),
                shape,
            )
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let {
                Icon(
                    it, contentDescription = null,
                    tint = if (primary) Color(0xFF06231E) else glass.text,
                    modifier = Modifier.height(18.dp),
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                color = if (primary) Color(0xFF06231E) else (if (enabled) glass.text else glass.textMuted),
            )
        }
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color? = null,
) {
    val glass = LocalGlassColors.current
    Box(
        modifier = modifier
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(glass.fillTop, glass.fillBottom)))
            .border(0.75.dp, Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint ?: glass.text,
            modifier = Modifier.padding(9.dp),
        )
    }
}

@Composable
fun GlassChip(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val glass = LocalGlassColors.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) Brush.linearGradient(
                    listOf(
                        (accent ?: glass.accent).copy(alpha = 0.32f),
                        (accent ?: glass.accent2).copy(alpha = 0.18f),
                    ),
                ) else Brush.linearGradient(listOf(glass.fillTop, glass.fillBottom)),
            )
            .border(
                0.75.dp,
                if (selected) Brush.linearGradient(
                    listOf(
                        (accent ?: glass.accent).copy(alpha = 0.7f),
                        (accent ?: glass.accent).copy(alpha = 0.7f),
                    ),
                ) else Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)),
                shape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = glass.text,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val glass = LocalGlassColors.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(glass.fillTop, glass.fillBottom)))
            .border(0.75.dp, Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)), shape),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = glass.textMuted) },
            singleLine = singleLine,
            enabled = enabled,
            trailingIcon = trailing,
            shape = shape,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = glass.text),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                cursorColor = glass.accent,
                focusedTextColor = glass.text,
                unfocusedTextColor = glass.text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Misc primitives
// ---------------------------------------------------------------------------

@Composable
fun GlassProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val glass = LocalGlassColors.current
    val shape = RoundedCornerShape(height / 2)
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .background(Brush.linearGradient(listOf(glass.fillTop, glass.fillBottom)))
            .border(0.75.dp, glass.borderBottom.copy(alpha = 0.4f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated.coerceAtMost(0.999f))
                .fillMaxHeight()
                .clip(shape)
                .background(Brush.linearGradient(listOf(glass.accent, glass.accent2))),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val glass = LocalGlassColors.current
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = glass.textMuted,
        modifier = modifier.padding(start = 6.dp, bottom = 8.dp),
    )
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(
        modifier = Modifier
            .width(size)
            .heightIn(min = size)
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// Floating glass tab bar with animated pill indicator
// ---------------------------------------------------------------------------

@Composable
fun GlassTabBar(
    items: List<Pair<String, ImageVector>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val itemW = maxWidth / items.size
        val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
            targetValue = itemW * selected + itemW * 0.5f - itemW * 0.28f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f),
            label = "tabIndicator",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .shadow(12.dp, RoundedCornerShape(29.dp))
                .clip(RoundedCornerShape(29.dp))
                .background(Brush.linearGradient(listOf(glass.fillTop, glass.fillBottom)))
                .border(0.75.dp, Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)), RoundedCornerShape(29.dp)),
        ) {
            // indicator pill
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .offset(x = indicatorOffset)
                    .width(itemW * 0.56f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(21.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                glass.accent.copy(alpha = 0.34f),
                                glass.accent2.copy(alpha = 0.20f),
                            ),
                        ),
                    ),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, (label, icon) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val active = i == selected
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                icon, contentDescription = label,
                                tint = if (active) glass.accent else glass.textMuted,
                                modifier = Modifier.height(18.dp),
                            )
                            if (active) Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = glass.text,
                            )
                        }
                    }
                }
            }
        }
    }
}
