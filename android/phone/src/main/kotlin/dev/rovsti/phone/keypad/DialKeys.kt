package dev.rovsti.phone.keypad

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.press.glassPress
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.phone.DELETE_CLEAR_MS
import dev.rovsti.phone.DELETE_REPEAT_AFTER_MS
import dev.rovsti.phone.DELETE_REPEAT_MS
import dev.rovsti.phone.KEY_HOLD_MS
import dev.rovsti.phone.KeypadMetrics
import kotlinx.coroutines.withTimeoutOrNull

/** The twelve keys, row by row, with the letters under each (the letters are the keys' own, not translated). */
internal val KeyRows: List<List<Pair<Char, String>>> =
    listOf(
        listOf('1' to "", '2' to "ABC", '3' to "DEF"),
        listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
        listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ"),
        listOf('*' to "", '0' to "+", '#' to ""),
    )

/** Test tags of the keypad. */
object KeypadTags {
    const val Root = "keypad"
    const val Field = "dial-field"
    const val Call = "keypad-call"
    const val Delete = "keypad-delete"
    const val AddContact = "keypad-add-contact"
    const val More = "keypad-more"
    const val Suggestions = "keypad-suggestions"

    fun key(c: Char) = "key-$c"

    fun suggestion(index: Int) = "suggestion-$index"
}

/**
 * What a key reports. [down] runs the moment the finger lands (the digit is typed, the tone and the haptic play, as on a
 * hardware keypad); [hold] runs once if the finger stays [KEY_HOLD_MS] (the key's second function) and is null for keys
 * without one.
 */
private fun Modifier.keyGesture(
    down: () -> Unit,
    hold: (() -> Unit)?,
): Modifier =
    pointerInput(hold != null) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            down()
            if (hold == null) {
                waitForUpOrCancellation()
            } else {
                var lifted = false
                withTimeoutOrNull(KEY_HOLD_MS) {
                    waitForUpOrCancellation()
                    lifted = true
                }
                if (!lifted) {
                    hold()
                    waitForUpOrCancellation()
                }
            }
        }
    }

/** A digit key: a circle with the digit and its letters. Presses only transform it; its size never changes. */
@Composable
internal fun DigitKey(
    char: Char,
    letters: String,
    diameter: Dp,
    showLetters: Boolean,
    voicemail: ImageVector?,
    onDown: (Char) -> Unit,
    onHold: ((Char) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    val density = LocalDensity.current
    // The key's text follows its size, not the font scale: a bigger system font must not make the key's digit spill.
    val digitSize: TextUnit = with(density) { (diameter * KeypadMetrics.DigitRatio).toSp() }
    val lettersSize: TextUnit = with(density) { (diameter * KeypadMetrics.LettersRatio).toSp() }
    val describe = if (letters.isEmpty() || !letters[0].isLetter()) char.toString() else "$char $letters"
    Box(
        modifier
            .size(diameter)
            .glassPress()
            .clip(CircleShape)
            .background(if (colors.isDark) DialColors.KeyDark else DialColors.KeyLight)
            .keyGesture({ onDown(char) }, onHold?.let { h -> { h(char) } })
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = describe
                onClick {
                    onDown(char)
                    true
                }
                if (onHold != null) {
                    onLongClick {
                        onHold(char)
                        true
                    }
                }
            }.testTag(KeypadTags.key(char)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val digit =
                RovstiTheme.type.title1.copy(
                    fontSize = digitSize,
                    lineHeight = digitSize,
                    textAlign = TextAlign.Center,
                )
            if (voicemail != null) {
                RovstiIcon(voicemail, null, tint = colors.labelPrimary, size = diameter * VOICEMAIL_RATIO)
                if (showLetters) {
                    IosText(char.toString(), color = colors.labelSecondary, style = digit.copy(fontSize = lettersSize))
                }
            } else {
                IosText(
                    char.toString(),
                    color = colors.labelPrimary,
                    style = digit.copy(fontWeight = FontWeight.Normal),
                )
                if (showLetters && letters.isNotEmpty()) {
                    IosText(
                        letters,
                        color = colors.labelSecondary,
                        style =
                            RovstiTheme.type.caption2.copy(
                                fontSize = lettersSize,
                                lineHeight = lettersSize,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = LETTER_SPACING,
                            ),
                    )
                }
            }
        }
    }
}

private const val VOICEMAIL_RATIO = 0.30f
private val LETTER_SPACING =
    androidx.compose.ui.unit
        .TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp)

/** The green call key. A tap calls; holding it opens the menu (one row per SIM, WhatsApp, Message). */
@Composable
internal fun CallKey(
    diameter: Dp,
    description: String,
    onCall: () -> Unit,
    onMenu: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val menu by rememberUpdatedState(onMenu)
    Box(
        modifier
            .size(diameter)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .glassPress()
            .clip(CircleShape)
            .background(if (colors.isDark) DialColors.CallDark else DialColors.CallLight)
            .pointerInput(onCall) {
                detectTapGestures(onTap = { onCall() }, onLongPress = { menu(bounds) })
            }.semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    onCall()
                    true
                }
                onLongClick {
                    menu(bounds)
                    true
                }
            }.testTag(KeypadTags.Call),
        contentAlignment = Alignment.Center,
    ) {
        // A filled white handset in both themes, as on iOS (the theme's onAccent is dark in dark mode).
        RovstiIcon(DialColors.FilledHandset, null, tint = Color.White, size = KeypadMetrics.CallGlyph)
    }
}

/**
 * The delete key: a bare glyph. A tap takes the character before the caret; held, it repeats ([DELETE_REPEAT_MS] after
 * [DELETE_REPEAT_AFTER_MS]) and, if kept down for [DELETE_CLEAR_MS], clears the number (with Undo).
 */
@Composable
internal fun DeleteKey(
    diameter: Dp,
    description: String,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    Box(
        modifier
            .size(diameter)
            .glassPress()
            .pointerInput(onDelete, onClear) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onDelete()
                    var elapsed = 0L
                    var wait = DELETE_REPEAT_AFTER_MS
                    while (true) {
                        var lifted = false
                        withTimeoutOrNull(wait) {
                            waitForUpOrCancellation()
                            lifted = true
                        }
                        if (lifted) break
                        elapsed += wait
                        wait = DELETE_REPEAT_MS
                        if (elapsed >= DELETE_CLEAR_MS) {
                            onClear()
                            waitForUpOrCancellation()
                            break
                        }
                        onDelete()
                    }
                }
            }.semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    onDelete()
                    true
                }
                onLongClick {
                    onClear()
                    true
                }
            }.testTag(KeypadTags.Delete),
        contentAlignment = Alignment.Center,
    ) {
        RovstiIcon(
            dev.rovsti.design.icons.RovstiIcons.Backspace,
            null,
            tint = colors.labelPrimary,
            size = KeypadMetrics.DeleteGlyph,
        )
    }
}

/** Dial pad colours: iOS keypad keys and the call button's system green (light and dark appearances). */
private object DialColors {
    val KeyLight = Color(0xFFE5E5EA)
    val KeyDark = Color(0xFF333336)
    val CallLight = Color(0xFF34C759)
    val CallDark = Color(0xFF30D158)

    /** The handset glyph of `RovstiIcons.Phone`, filled instead of stroked. */
    val FilledHandset: ImageVector by lazy {
        ImageVector
            .Builder("FilledHandset", 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black)) {
                moveTo(5f, 3.5f)
                horizontalLineTo(9f)
                lineTo(10.6f, 8f)
                lineTo(8.6f, 9.6f)
                curveTo(9.6f, 11.9f, 11.6f, 13.9f, 14.4f, 15.4f)
                lineTo(16f, 13.4f)
                lineTo(20.5f, 15f)
                verticalLineTo(19f)
                arcTo(1.5f, 1.5f, 0f, false, true, 19f, 20.5f)
                curveTo(10.7f, 20.5f, 3.5f, 13.3f, 3.5f, 5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 5f, 3.5f)
                close()
            }.build()
    }
}
