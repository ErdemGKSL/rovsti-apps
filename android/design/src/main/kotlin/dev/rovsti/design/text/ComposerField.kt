package dev.rovsti.design.text

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.insert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassPreset
import dev.rovsti.design.glass.rovstiGlass
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/** The composer grows to this many lines, then its text scrolls inside the pill. */
const val COMPOSER_MAX_LINES = 6

private val ComposerRadius = 22.dp
private val SendSize = 32.dp
private val SendInset = 6.dp
private val ComposerTextPadding = 11.dp
private val SendGlyph = 18.dp
private const val SEND_POP_STIFFNESS = 700f
private const val FILL_MS = 200

/**
 * The message composer, docked at the bottom of a screen (ORIGINAL; the pill is the [GlassTextField] material).
 *
 * - One to [COMPOSER_MAX_LINES] lines, then the text scrolls; the pill's corners stay 22 dp, a capsule while it is a single
 *   line.
 * - The dock pads itself by the navigation bar, then by the part of it the keyboard really covers
 *   ([imeOverlapPadding], so a vendor build that also resizes the window does not lift it by a second keyboard
 *   height), so the field rides above the keyboard.
 * - The send button appears with text, pops in on a spring, and never takes focus: sending keeps the keyboard open and
 *   the caret in place. [onSend] gets the trimmed text once per send and the field is cleared afterwards; a blank
 *   composer sends nothing.
 * - Sending happens from the button, the keyboard's action key when [imeAction] is [ImeAction.Send] (the default key
 *   inserts a newline), and a hardware Enter. Shift+Enter inserts a newline on a hardware keyboard.
 *
 * [modifier] applies to the dock, [fieldModifier] to the text field itself (test tags, focus requesters).
 */
@Composable
fun ComposerField(
    state: TextFieldState,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    placeholder: String = "Message",
    sendLabel: String = "Send",
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
) {
    val tokens = RovstiTheme.glass
    val colors = RovstiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = enabled && state.text.isNotBlank()
    val pop by animateFloatAsState(
        if (canSend) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = SEND_POP_STIFFNESS),
        label = "sendPop",
    )
    val fill by animateColorAsState(
        if (focused) tokens.glassActive else Color.Transparent,
        tween(FILL_MS),
        label = "composerFill",
    )
    val send = {
        val text = state.text.toString().trim()
        if (text.isNotEmpty() && enabled) {
            onSend(text)
            state.clearText()
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imeOverlapPadding(excludeNavigationBar = false)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s),
    ) {
        BasicTextField(
            state = state,
            modifier =
                fieldModifier
                    .fillMaxWidth()
                    .heightIn(min = FieldMinHeight)
                    .rovstiGlass(GlassPreset.Bar, RoundedCornerShape(ComposerRadius), "composer", outerShadow = false)
                    .drawBehind {
                        val radius = CornerRadius(minOf(ComposerRadius.toPx(), size.height / 2f))
                        drawRoundRect(fill, cornerRadius = radius)
                        drawRoundRect(tokens.edge, cornerRadius = radius, style = Stroke(0.5.dp.toPx()))
                    }.pointerInput(enabled) {
                        detectTapGestures {
                            if (enabled) {
                                focus.requestFocus()
                                keyboard?.show()
                            }
                        }
                    }.focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        when {
                            !enter -> {
                                false
                            }

                            event.type != KeyEventType.KeyDown -> {
                                true
                            }

                            event.isShiftPressed -> {
                                state.edit { insert(selection.min, "\n") }
                                true
                            }

                            else -> {
                                send()
                                true
                            }
                        }
                    },
            enabled = enabled,
            textStyle = RovstiTheme.type.body.copy(color = tokens.foreground),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = COMPOSER_MAX_LINES),
            keyboardOptions =
                KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
            onKeyboardAction = { send() },
            interactionSource = interaction,
            cursorBrush = SolidColor(colors.accent),
            decorator =
                TextFieldDecorator { inner ->
                    Row(
                        Modifier.padding(start = FieldPaddingStart, end = SendInset),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(
                            Modifier.weight(1f).padding(vertical = ComposerTextPadding),
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
                        Box(Modifier.padding(start = Spacing.s, bottom = SendInset).size(SendSize)) {
                            if (pop > 0f || canSend) {
                                SendButton(label = sendLabel, enabled = canSend, pop = { pop }, onClick = send)
                            }
                        }
                    }
                },
        )
    }
}

/** Accent disc with an up arrow. Scale and alpha follow [pop] in the layer block, so the spring never recomposes it. */
@Composable
private fun SendButton(
    label: String,
    enabled: Boolean,
    pop: () -> Float,
    onClick: () -> Unit,
) {
    val colors = RovstiTheme.colors
    Box(
        Modifier
            .size(SendSize)
            .graphicsLayer {
                val p = pop()
                scaleX = 0.4f + 0.6f * p
                scaleY = 0.4f + 0.6f * p
                alpha = p.coerceIn(0f, 1f)
            }.background(colors.accent, CircleShape)
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(RovstiIcons.ArrowUp, contentDescription = label, tint = colors.onAccent, size = SendGlyph)
    }
}
