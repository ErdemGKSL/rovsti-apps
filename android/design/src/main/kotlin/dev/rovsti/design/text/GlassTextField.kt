// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.text

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme

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
private const val FILL_MS = 200
private const val DISABLED_ALPHA = 0.35f

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
) {
    val tokens = RovstiTheme.glass
    val colors = RovstiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (focused) tokens.glassActive else tokens.glass,
        tween(FILL_MS),
        label = "fieldFill",
    )
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        state = state,
        modifier =
            modifier
                .heightIn(min = FieldMinHeight)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .drawBehind { drawPill(fill, tokens.edge) }
                .pointerInput(enabled) {
                    detectTapGestures {
                        if (enabled) {
                            focus.requestFocus()
                            keyboard?.show()
                        }
                    }
                }.focusRequester(focus),
        enabled = enabled,
        textStyle = RovstiTheme.type.body.copy(color = tokens.foreground),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = keyboardOptions.copy(imeAction = imeAction),
        onKeyboardAction = { onSubmit() },
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.accent),
        decorator =
            TextFieldDecorator { inner ->
                Row(
                    Modifier.padding(horizontal = FieldPaddingStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FieldGap),
                ) {
                    val glyph = leading ?: if (search) RovstiIcons.Search else null
                    if (glyph != null) {
                        RovstiIcon(glyph, contentDescription = null, tint = colors.labelSecondary, size = LeadingGlyph)
                    }
                    Box(
                        Modifier.weight(1f).padding(vertical = FieldTextPadding),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.text.isEmpty()) {
                            RovstiText(
                                placeholder,
                                style = RovstiTheme.type.body,
                                color = colors.labelSecondary,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                    if (search && enabled && state.text.isNotEmpty()) {
                        ClearButton(onClear = { state.clearText() })
                    }
                }
            },
    )
}

/** Capsule fill with a hairline edge; both colours come from the glass tokens and are drawn, not composed. */
private fun DrawScope.drawPill(
    fill: Color,
    edge: Color,
) {
    drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2f))
    val hairline = 0.5.dp.toPx()
    drawRoundRect(
        edge,
        topLeft = Offset(hairline / 2f, hairline / 2f),
        size = Size(size.width - hairline, size.height - hairline),
        cornerRadius = CornerRadius((size.height - hairline) / 2f),
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
                onClickLabel = "Clear",
                onClick = onClear,
            ),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(
            RovstiIcons.Xmark,
            contentDescription = "Clear",
            tint = RovstiTheme.colors.labelSecondary,
            size = ClearGlyph,
        )
    }
}
