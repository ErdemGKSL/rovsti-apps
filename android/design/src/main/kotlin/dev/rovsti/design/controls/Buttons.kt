// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassSource
import dev.rovsti.design.glass.GlassSpec
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.design.glass.glass
import dev.rovsti.design.glass.glassTier
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.press.glassPress
import dev.rovsti.design.press.scalePress
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import androidx.compose.ui.graphics.lerp as lerpColor

/** A disabled control keeps its shape and fades to this alpha (glisse `.lg-button:disabled`). */
const val DisabledAlpha = 0.35f

/** Surface of a [CapsuleButton]: clear glass, or the accent colour for the primary action. */
enum class CapsuleStyle { Glass, Filled }

private val IconSize = 20.dp
private val Hairline = 0.5.dp

/**
 * A 44 dp round glass button holding one icon, inside a 48 dp touch target (the extra 2 dp on every side is not
 * drawn). Without a [glass] source the surface is a flat translucent disc with a hairline. The press is the glass
 * press (a layer transform, so neighbours never move) and the fill lights up at once and fades back over 200 ms.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glass: GlassSource? = null,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    val colors = RovstiTheme.colors
    val source = remember { MutableInteractionSource() }
    val press = rememberPressFill(source)
    Box(
        modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .sizeIn(minWidth = Sizes.touch, minHeight = Sizes.touch)
            .glassPress(enabled = enabled, glow = true, glowShape = RovstiShapes.capsule)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(Sizes.button).glassSurface(glass, press),
            contentAlignment = Alignment.Center,
        ) {
            RovstiIcon(icon, null, tint = tint.takeOrElse { RovstiTheme.glass.foreground }, size = IconSize)
        }
    }
}

/**
 * A 44 dp tall capsule with a [text] label (17 sp) and an optional leading [icon]. Padding is 16 dp (12 dp on the icon
 * side), the gap between icon and label 8 dp. [CapsuleStyle.Glass] takes the label colour from the glass tokens or
 * [tint]; [CapsuleStyle.Filled] fills with [tint] (default the accent) under an on-accent label and darkens 15 % while
 * pressed. The visual is 44 dp; the touch target is at least 48 dp.
 */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: CapsuleStyle = CapsuleStyle.Glass,
    glass: GlassSource? = null,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    val colors = RovstiTheme.colors
    val source = remember { MutableInteractionSource() }
    val press = rememberPressFill(source)
    val filled = style == CapsuleStyle.Filled
    val label = if (filled) colors.onAccent else tint.takeOrElse { RovstiTheme.glass.foreground }
    val surface =
        if (filled) {
            Modifier.filledSurface(
                tint.takeOrElse {
                    colors.accent
                },
                press,
            )
        } else {
            Modifier.glassSurface(glass, press)
        }
    Box(
        modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .sizeIn(minWidth = Sizes.touch, minHeight = Sizes.touch)
            .then(
                if (filled) {
                    Modifier.scalePress(
                        enabled,
                    )
                } else {
                    Modifier.glassPress(enabled, glow = true, glowShape = RovstiShapes.capsule)
                },
            ).clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .defaultMinSize(minWidth = Sizes.button, minHeight = Sizes.button)
                .then(surface)
                .padding(start = if (icon != null) Spacing.m else Spacing.l, end = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) RovstiIcon(icon, null, tint = label, size = IconSize)
            RovstiText(
                text,
                style = if (filled) RovstiTheme.type.headline else RovstiTheme.type.body,
                color = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Accent-coloured label without a surface; the press is the scale press. At least 44 dp tall, 48 dp touch target. */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    Box(
        modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .sizeIn(minWidth = Sizes.touch, minHeight = Sizes.touch)
            .scalePress(enabled)
            .clickable(
                indication = null,
                interactionSource =
                    remember {
                        MutableInteractionSource()
                    },
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(
            text,
            modifier = Modifier.padding(horizontal = Spacing.s),
            style = RovstiTheme.type.body,
            color = tint.takeOrElse { RovstiTheme.colors.accent },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A glass capsule with a destructive-coloured label, for actions that delete or cannot be undone. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    glass: GlassSource? = null,
    enabled: Boolean = true,
) {
    CapsuleButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        glass = glass,
        enabled = enabled,
        tint = RovstiTheme.colors.destructive,
    )
}

/**
 * Glass surface of a button. With a source: the glass node, then the pressed fill over it. Without: a flat translucent
 * fill and a hairline. Both are capsules (radius = half the height).
 */
@Composable
internal fun Modifier.glassSurface(
    source: GlassSource?,
    press: Animatable<Float, *>,
): Modifier {
    val colors = RovstiTheme.colors
    val tokens = RovstiTheme.glass
    val base =
        if (source != null) {
            val tier = glassTier(LocalGlassConditions.current)
            Modifier.glass(source, GlassSpec.bar(tokens, colors, tier))
        } else {
            val fill = colors.groupedSurface.copy(alpha = GlassSpec.FLAT_ALPHA_BAR)
            Modifier.drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(fill, cornerRadius = radius)
                drawRoundRect(tokens.edge, cornerRadius = radius, style = Stroke(Hairline.toPx()))
            }
        }
    return this.then(base).drawBehind {
        val amount = press.value
        if (amount > 0f) {
            drawRoundRect(
                tokens.glassActive.copy(alpha = tokens.glassActive.alpha * amount),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
    }
}

/** Accent fill that mixes 15 % toward black while pressed (glisse `color-mix(fill 85%, #000)`). */
@Composable
private fun Modifier.filledSurface(
    fill: Color,
    press: Animatable<Float, *>,
): Modifier {
    val pressed = lerpColor(fill, Color.Black, PressedDarken)
    return drawBehind {
        drawRoundRect(lerpColor(fill, pressed, press.value), cornerRadius = CornerRadius(size.height / 2f))
    }
}

private const val PressedDarken = 0.15f
