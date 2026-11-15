// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rosty.design.text

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rosty.design.R
import dev.rosty.design.glass.GlassPreset
import dev.rosty.design.glass.rostyGlass
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme

/** Smallest field height (glisse is 40 on the web; 44 for a thumb). Fields grow with the font size. */
internal val FieldMinHeight: Dp = 44.dp

/** Horizontal padding inside the pill (glisse `padding: 0 14px`). */
internal val FieldPaddingStart: Dp = 14.dp

/** Space between the leading glyph and the text (glisse `gap: 10px`). */
private val FieldGap: Dp = 10.dp

/** Vertical room around the text so a larger font never touches the pill's edge. */
private val FieldTextPadding: Dp = 8.dp

/** Visible size of the clear button's hit target (glisse 32); it overlaps the pill's right padding by 8. */
private val ClearSize: Dp = 36.dp
private val ClearGlyph: Dp = 14.dp
private val LeadingGlyph: Dp = 16.dp
internal const val FIELD_FILL_MS = 200
internal const val FIELD_DISABLED_ALPHA = 0.35f

/**
 * A single-line text field in a capsule pill: the glisse `Input` over the platform text stack.
 *
 * [BasicTextField] with a [TextFieldState] gives the caret by tap, long-press word selection with handles and magnifier,
 * the platform cut/copy/paste toolbar, IME composition and autofill. The fill eases from `glass` to `glassActive` in 200 ms
 * when focused and is read in the draw phase, so focus changes never recompose the layout. Tapping the pill, not only the
 * text, focuses the field. With [search] a clear button appears while there is text; it never takes focus, so the caret
 * and the keyboard stay where they are.
 *
 * [state] is owned by the caller (`rememberTextFieldState()` or a state holder) and never recreated per keystroke.
 * [imeAction] decides the key on the keyboard; [onSubmit] runs once per press of it, and for a hardware Enter.
 */
@Composable
fun GlassTextField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    search: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = if (search) ImeAction.Search else ImeAction.Done,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: () -> Unit = {},
    maxLines: Int = 1,
    minLines: Int = 1,
    /** A requester the caller owns (a search field handing focus back after a sheet); else the field's own. */
    focusRequester: FocusRequester? = null,
) {
    val multiline = maxLines > 1
    val tokens = RostyTheme.glass
    val colors = RostyTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (focused) tokens.glassActive else Color.Transparent,
        tween(FIELD_FILL_MS),
        label = "fieldFill",
    )
    val focus = focusRequester ?: remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        state = state,
        modifier = modifier.glassFieldPill(enabled, multiline, { fill }, tokens.edge, focus, keyboard),
        enabled = enabled,
        textStyle = RostyTheme.type.body.copy(color = tokens.foreground),
        lineLimits =
            if (multiline) {
                TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines)
            } else {
                TextFieldLineLimits.SingleLine
            },
        keyboardOptions = keyboardOptions.copy(imeAction = imeAction),
        onKeyboardAction = { onSubmit() },
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.accent),
        decorator =
            glassFieldDecorator(state, placeholder, leading ?: if (search) RostyIcons.Search else null) {
                if (search && enabled && state.text.isNotEmpty()) {
                    ClearButton(onClear = { state.clearText() })
                }
            },
    )
}

/** The pill every glass field draws: glass, the focus fill read in the draw phase, a hairline, tap-to-focus. */
@Composable
internal fun Modifier.glassFieldPill(
    enabled: Boolean,
    multiline: Boolean,
    /** Read in the draw phase only, so an animated fill never recomposes the field. */
    fill: () -> Color,
    edge: Color,
    focus: FocusRequester,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
): Modifier =
    heightIn(min = FieldMinHeight)
        .alpha(if (enabled) 1f else FIELD_DISABLED_ALPHA)
        .rostyGlass(
            GlassPreset.Bar,
            if (multiline) RoundedCornerShape(FieldMinHeight / 2) else RostyShapes.capsule,
            "field",
            outerShadow = false,
        ).drawBehind {
            drawPill(
                fill(),
                edge,
                if (multiline) FieldMinHeight.toPx() / 2f else Float.MAX_VALUE,
            )
        }.pointerInput(enabled) {
            detectTapGestures {
                if (enabled) {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
        }.focusRequester(focus)

/** The inside of a glass field: an optional leading glyph, the placeholder under the text, then [trailing]. */
internal fun glassFieldDecorator(
    state: TextFieldState,
    placeholder: String,
    glyph: ImageVector?,
    trailing: @Composable () -> Unit = {},
): TextFieldDecorator =
    TextFieldDecorator { inner ->
        val colors = RostyTheme.colors
        Row(
            Modifier.padding(horizontal = FieldPaddingStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FieldGap),
        ) {
            if (glyph != null) {
                RostyIcon(glyph, contentDescription = null, tint = colors.labelSecondary, size = LeadingGlyph)
            }
            Box(
                Modifier.weight(1f).padding(vertical = FieldTextPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.text.isEmpty()) {
                    RostyText(
                        placeholder,
                        style = RostyTheme.type.body,
                        color = colors.labelSecondary,
                        maxLines = 1,
                    )
                }
                inner()
            }
            trailing()
        }
    }

/** Capsule fill with a hairline edge; both colours come from the glass tokens and are drawn, not composed. */
private fun DrawScope.drawPill(
    fill: Color,
    edge: Color,
    maxRadius: Float,
) {
    val radius = minOf(size.height / 2f, maxRadius)
    drawRoundRect(fill, cornerRadius = CornerRadius(radius))
    val hairline = 0.5.dp.toPx()
    drawRoundRect(
        edge,
        topLeft = Offset(hairline / 2f, hairline / 2f),
        size = Size(size.width - hairline, size.height - hairline),
        cornerRadius = CornerRadius(radius - hairline / 2f),
        style = Stroke(hairline),
    )
}

/**
 * The glisse clear button: a 14 dp cross in a round hit target that pulls 8 dp into the pill's padding. It is a button
 * for accessibility but `canFocus = false`, so pressing it leaves the text field focused and the caret blinking.
 */
@Composable
private fun ClearButton(onClear: () -> Unit) {
    Box(
        Modifier
            .size(ClearSize)
            .offset(x = FieldPaddingStart / 2)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.design_clear),
                onClick = onClear,
            ),
        contentAlignment = Alignment.Center,
    ) {
        RostyIcon(
            RostyIcons.Xmark,
            contentDescription = stringResource(R.string.design_clear),
            tint = RostyTheme.colors.labelSecondary,
            size = ClearGlyph,
        )
    }
}
