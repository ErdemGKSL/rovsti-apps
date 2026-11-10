package dev.rovsti.design.text

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.coroutines.awaitCancellation

/** What a number field may hold: the digits, `+`, the two dial symbols and the pause (`,`) and wait (`;`) marks. */
object DialAlphabet {
    private const val CHARS = "0123456789+*#,;"

    /** The longest number the field takes (`dial::MAX_LEN` of the core). */
    const val MAX_LEN = 32

    fun isDial(c: Char): Boolean = CHARS.indexOf(c) >= 0

    /** [text] without the characters a dial string cannot hold. */
    fun keep(text: CharSequence): String = text.filter(::isDial).take(MAX_LEN).toString()
}

/** The font sizes of the number field, largest first: 36 sp down to 24 sp in 2 sp steps. */
object DialSizes {
    val steps: List<Int> = (36 downTo 24 step 2).toList()

    /**
     * The largest step whose text is at most [available] wide ([widthAt] measures the text at a size in sp), or the
     * smallest step when even that does not fit (the field then scrolls inside itself). Pure, tested on the JVM.
     */
    fun fit(
        available: Float,
        widthAt: (Int) -> Float,
    ): Int = steps.firstOrNull { widthAt(it) <= available } ?: steps.last()
}

/** Keeps only the dial characters of an edit (a paste from the system toolbar can carry spaces and dashes). */
private object DialOnly : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (length <= DialAlphabet.MAX_LEN && asCharSequence().all(DialAlphabet::isDial)) return
        val caret = selection.end.coerceAtMost(length)
        val before = DialAlphabet.keep(asCharSequence().subSequence(0, caret)).length
        replace(0, length, DialAlphabet.keep(asCharSequence()))
        selection = TextRange(before.coerceAtMost(length))
    }
}

/** Replaces the selection (or inserts at the caret) with [text]; the caret ends after it. */
fun TextFieldState.insertAtCaret(text: String) {
    edit {
        val from = selection.min
        replace(from, selection.max, text)
        selection = TextRange(from + text.length)
    }
}

/** Deletes the selection, or the character before the caret. False when there was nothing to delete. */
fun TextFieldState.backspace(): Boolean {
    var removed = false
    edit {
        val sel = selection
        when {
            !sel.collapsed -> {
                delete(sel.min, sel.max)
                selection = TextRange(sel.min)
                removed = true
            }

            sel.start > 0 -> {
                delete(sel.start - 1, sel.start)
                selection = TextRange(sel.start - 1)
                removed = true
            }
        }
    }
    return removed
}

/** Sets the whole text to [text] with the caret at its end. */
fun TextFieldState.setDialText(text: String) {
    edit {
        replace(0, length, text)
        placeCursorAtEnd()
    }
}

/**
 * The number field of the Keypad: one centred line, 36 sp shrinking to 24 sp in 2 sp steps so the number stays in view,
 * no border, no fill. It is an ordinary [BasicTextField] over a [TextFieldState]: a tap places the caret, a long press
 * on the digits selects with both drag handles and the system's Copy / Paste / Select all toolbar, and the selection
 * colours come from the theme. The soft keyboard never opens: the Keypad is the input, and the interceptor swallows the
 * input-method request that focus would start ([onImeRequest] only counts them, for tests). A hardware keyboard still
 * types, filtered to [DialAlphabet].
 *
 * The field is as wide as its text. A tap beside the digits focuses it with the caret at the end; a long press beside
 * them calls [onLongPressOutside] with the field's rect in window pixels (the Keypad opens its field menu there).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DialField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    description: String = "",
    onLongPressOutside: (Rect) -> Unit = {},
    onImeRequest: () -> Unit = {},
) {
    val colors = RovstiTheme.colors
    val base = RovstiTheme.type.body
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    var fieldBounds by remember { mutableStateOf(Rect.Zero) }
    var outerWindow by remember { mutableStateOf(Rect.Zero) }
    val interceptor =
        remember {
            PlatformTextInputInterceptor { _, _ ->
                onImeRequest()
                awaitCancellation()
            }
        }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = DialFieldMinHeight)
            .onGloballyPositioned { outerWindow = it.boundsInWindow() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { at ->
                        if (!fieldBounds.contains(at)) {
                            focus.requestFocus()
                            state.edit { placeCursorAtEnd() }
                        }
                    },
                    onLongPress = { at -> if (!fieldBounds.contains(at)) onLongPressOutside(outerWindow) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val availablePx = with(density) { maxWidth.toPx() }
        val text = state.text.toString()
        val sampleStyle = base.copy(fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
        val sizeSp =
            remember(text, availablePx, density.fontScale) {
                DialSizes.fit(availablePx - with(density) { SidePadding.toPx() }) { sp ->
                    measurer
                        .measure(text, sampleStyle.copy(fontSize = sp.sp))
                        .size.width
                        .toFloat()
                }
            }
        val style: TextStyle = sampleStyle.copy(fontSize = sizeSp.sp, color = colors.labelPrimary)
        val measured =
            remember(text, sizeSp, density.fontScale) {
                val sample = text.ifEmpty { placeholder }
                measurer.measure(sample, style).size.width
            }
        val width = with(density) { maxOf(measured.toDp() + SidePadding, MinFieldWidth) }
        if (text.isEmpty() && placeholder.isNotEmpty()) {
            IosText(placeholder, color = colors.labelTertiary, style = style, maxLines = 1)
        }
        InterceptPlatformTextInput(interceptor) {
            BasicTextField(
                state = state,
                modifier =
                    Modifier
                        .width(width.coerceAtMost(maxWidth))
                        .onGloballyPositioned { fieldBounds = it.boundsInParent() }
                        .focusRequester(focus)
                        .semantics { contentDescription = description },
                inputTransformation = DialOnly,
                textStyle = style,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                cursorBrush = SolidColor(colors.accent),
            )
        }
    }
}

private val DialFieldMinHeight = 48.dp
private val SidePadding = 8.dp
private val MinFieldWidth = 40.dp
