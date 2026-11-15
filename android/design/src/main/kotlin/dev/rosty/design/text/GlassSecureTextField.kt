package dev.rosty.design.text

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.rosty.design.theme.RostyTheme

/**
 * A password or passphrase field in the [GlassTextField] pill: the platform's secure field (the text shows as dots,
 * the last typed character briefly, no suggestions, and cut and copy are off), with the same focus fill, hairline and
 * tap-to-focus. [onSubmit] runs once per press of the keyboard's [imeAction] key.
 */
@Composable
fun GlassSecureTextField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val tokens = RostyTheme.glass
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (focused) tokens.glassActive else Color.Transparent,
        tween(FIELD_FILL_MS),
        label = "secureFieldFill",
    )
    val focus = focusRequester ?: remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicSecureTextField(
        state = state,
        modifier = modifier.glassFieldPill(enabled, multiline = false, { fill }, tokens.edge, focus, keyboard),
        enabled = enabled,
        textStyle = RostyTheme.type.body.copy(color = tokens.foreground),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        onKeyboardAction = { onSubmit() },
        interactionSource = interaction,
        cursorBrush = SolidColor(RostyTheme.colors.accent),
        decorator = glassFieldDecorator(state, placeholder, glyph = null),
        textObfuscationMode = TextObfuscationMode.RevealLastTyped,
    )
}
