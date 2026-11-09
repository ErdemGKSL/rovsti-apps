// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
// (DisabledAlpha, .lg-button:disabled). A facade over Convx's GlassCircleButton and IosButton:
// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/GlassCircleButton.kt and app/src/main/kotlin/com/convx/music/ui/ios/component/IosButton.kt
package dev.rovsti.design.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.ios.component.IosButton
import dev.rovsti.design.ios.component.IosButtonSizing
import dev.rovsti.design.ios.component.IosButtonStyle
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosControlSize
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.ios.foundation.LocalIosContentColor
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

/** A disabled control keeps its shape and fades to this alpha (glisse `.lg-button:disabled`). */
const val DisabledAlpha = 0.35f

/** Surface of a [CapsuleButton]: clear glass, or the accent colour for the primary action. */
enum class CapsuleStyle { Glass, Filled }

private val IconSize = 20.dp

/**
 * A 44 dp round glass button holding one icon, inside a 48 dp touch target (the extra 2 dp on every side is not
 * drawn). It is Convx's [GlassCircleButton]: liquid glass over the app backdrop, the tanh elastic stretch on press and
 * drag-out (a layer transform, so neighbours never move) and the soft chrome shadow.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    GlassCircleButton(
        onClick = onClick,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        alpha = if (enabled) 1f else DisabledAlpha,
        size = Sizes.button,
        touchTarget = Sizes.touch,
        enabled = enabled,
        debugTag = "button.icon",
    ) {
        RovstiIcon(icon, null, tint = tint.takeOrElse { LocalIosContentColor.current }, size = IconSize)
    }
}

/**
 * A 44 dp tall capsule with a [text] label (17 sp) and an optional leading [icon] (a [fillWidth] capsule takes the width its parent offers, for side-by-side pairs): Convx's [IosButton] with the Glass
 * style (clear glass; the label takes [tint] when given) or, for [CapsuleStyle.Filled], BorderedProminent filled with
 * [tint] (default the accent) under an on-accent label. The visual is 44 dp; the touch target is at least 48 dp.
 */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: CapsuleStyle = CapsuleStyle.Glass,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    fillWidth: Boolean = false,
) {
    val colors = RovstiTheme.colors
    val filled = style == CapsuleStyle.Filled
    // A button inside the recorded page content has no backdrop it may sample (a layer cannot draw into itself): it
    // takes IosButton's flat Bordered fill instead. That is a design decision, as in Convx's forceFlat buttons.
    val glass = LocalIosBackdrop.current != null
    Box(
        modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .sizeIn(minWidth = Sizes.touch, minHeight = Sizes.touch),
        contentAlignment = Alignment.Center,
    ) {
        IosButton(
            onClick = onClick,
            style =
                when {
                    filled -> IosButtonStyle.BorderedProminent
                    glass -> IosButtonStyle.Glass
                    else -> IosButtonStyle.Bordered
                },
            tint =
                when {
                    filled -> tint.takeOrElse { colors.accent }
                    glass -> tint
                    else -> tint.takeOrElse { colors.labelPrimary }
                },
            enabled = enabled,
            sizing = if (fillWidth) IosButtonSizing.Flexible else IosButtonSizing.Fitted,
            controlSize = IosControlSize.Large,
            debugTag = "button.capsule",
        ) {
            if (icon != null) RovstiIcon(icon, null, tint = LocalIosContentColor.current, size = IconSize)
            IosText(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Accent-coloured label without a surface: Convx's [IosButton] in the Borderless style. At least 44 dp tall, 48 dp touch target. */
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
            .sizeIn(minWidth = Sizes.touch, minHeight = Sizes.touch),
        contentAlignment = Alignment.Center,
    ) {
        IosButton(
            onClick = onClick,
            style = IosButtonStyle.Borderless,
            tint = tint.takeOrElse { RovstiTheme.colors.accent },
            enabled = enabled,
            modifier = Modifier.padding(horizontal = Spacing.s),
        ) {
            IosText(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A glass capsule with a destructive-coloured label, for actions that delete or cannot be undone. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    CapsuleButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        tint = RovstiTheme.colors.destructive,
    )
}
