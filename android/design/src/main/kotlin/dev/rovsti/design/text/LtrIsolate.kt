package dev.rovsti.design.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Left-to-right isolation for numbers (F36). A phone number ("+49 30 123456") or a duration ("1 h 52 min") has no strong
 * character at its start, so inside a right-to-left layout the bidi algorithm reorders it ("123456 30 49+"). Two tools:
 *
 *  - [ltrIsolated] wraps a piece of a string in LRI .. PDI so it keeps its glyph order wherever it sits, even inside
 *    Arabic or Hebrew text;
 *  - [ltrStyleFor] gives the paragraph of a string with no right-to-left letters an LTR direction, so [RovstiText] and
 *    `IosText` show a number or a duration in order in an RTL layout without the caller doing anything.
 */
object LtrIsolate {
    /** LEFT-TO-RIGHT ISOLATE. */
    const val LRI: Char = '⁦'

    /** POP DIRECTIONAL ISOLATE. */
    const val PDI: Char = '⁩'

    /** True when [text] has a letter or digit-like character that is strongly right-to-left (Arabic, Hebrew, Syriac...). */
    fun hasRtl(text: CharSequence): Boolean =
        text.any {
            when (Character.getDirectionality(it)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE,
                -> true

                else -> false
            }
        }
}

/** This text wrapped in LRI..PDI (an empty text stays empty), so it reads left to right wherever it is placed. */
fun String.ltrIsolated(): String = if (isEmpty()) this else "${LtrIsolate.LRI}$this${LtrIsolate.PDI}"

/** [this] with [part] isolated: use for a number inside a sentence that may be Arabic or Hebrew. */
fun AnnotatedString.Builder.appendLtr(part: String) {
    append(part.ltrIsolated())
}

/** An [AnnotatedString] of [parts], each flagged `true` isolated left to right. */
fun ltrAnnotated(vararg parts: Pair<String, Boolean>): AnnotatedString =
    buildAnnotatedString {
        for ((text, isolate) in parts) if (isolate) appendLtr(text) else append(text)
    }

/**
 * [style] with an LTR paragraph direction for [text] in an RTL layout when [text] holds no right-to-left letters and the
 * style sets no direction of its own. Anything else comes back unchanged. Digits and `+` alone give the bidi algorithm
 * nothing to decide the direction with, which is why the explicit direction is needed.
 */
fun ltrStyleFor(
    style: TextStyle,
    text: CharSequence,
    layoutDirection: LayoutDirection,
): TextStyle =
    if (layoutDirection == LayoutDirection.Rtl &&
        style.textDirection == TextDirection.Unspecified &&
        !LtrIsolate.hasRtl(text)
    ) {
        style.copy(textDirection = TextDirection.Ltr)
    } else {
        style
    }

private val NumberRun = Regex("""\+?\d[\d ().\- ]*\d|\d""")

/**
 * [this] with every phone-number-like run ("+49 30 123456", "(030) 12-34") isolated left to right, and the rest left as
 * it is. Use it on a row title or subtitle that is a name or a number: an Arabic name keeps its direction, the number in
 * it keeps its glyph order. A text with no digits comes back as the same instance.
 */
fun String.isolateNumbers(): String =
    if (none(Char::isDigit)) {
        this
    } else {
        NumberRun.replace(this) {
            it.value.ltrIsolated()
        }
    }

/**
 * Makes this text safe in a right-to-left layout: a text with no right-to-left letters (a number, a duration, an English
 * fallback sentence) is isolated whole, a mixed one has its number runs isolated ([isolateNumbers]). In a left-to-right
 * layout the text itself comes back, so that screen, its accessibility text and its tests never see the isolate marks.
 */
@Composable
fun String.rtlSafe(): String =
    when {
        LocalLayoutDirection.current != LayoutDirection.Rtl -> this
        LtrIsolate.hasRtl(this) -> isolateNumbers()
        else -> ltrIsolated()
    }
