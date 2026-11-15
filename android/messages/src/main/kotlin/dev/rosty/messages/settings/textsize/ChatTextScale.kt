package dev.rosty.messages.settings.textsize

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import dev.rosty.core.model.messages.ChatTextSize
import dev.rosty.messages.R
import kotlin.math.roundToInt

/**
 * M34: how large the text of message bubbles is drawn. The factors mirror `rosty_core::text_scale::ChatTextSize`
 * (`OPS.md` marks it as Kotlin-side: it is applied per frame to every bubble, no JNI hop). Captions, times and headers
 * keep their size.
 */
object ChatTextScale {
    /** The choices in the order the Text size page lists them. */
    val all: List<ChatTextSize> =
        listOf(ChatTextSize.Small, ChatTextSize.Default, ChatTextSize.Large, ChatTextSize.Larger)

    fun factor(size: ChatTextSize): Float =
        when (size) {
            ChatTextSize.Small -> SMALL
            ChatTextSize.Default -> 1f
            ChatTextSize.Large -> LARGE
            ChatTextSize.Larger -> LARGER
        }

    /** The name of [size] on the Text size page and in the Settings row. */
    fun label(size: ChatTextSize): Int =
        when (size) {
            ChatTextSize.Small -> R.string.text_size_small
            ChatTextSize.Default -> R.string.text_size_default
            ChatTextSize.Large -> R.string.text_size_large
            ChatTextSize.Larger -> R.string.text_size_larger
        }

    /** [base] scaled by [factor] and rounded to half a unit, like `ChatTextSize::scaled`. */
    fun scaled(
        base: Float,
        factor: Float,
    ): Float = (base * factor * 2f).roundToInt() / 2f

    private const val SMALL = 0.875f
    private const val LARGE = 1.15f
    private const val LARGER = 1.3f
}

/** This style with its font size and line height scaled by [factor] (1: unchanged). */
fun TextStyle.scaledBy(factor: Float): TextStyle {
    if (factor == 1f) return this
    return copy(fontSize = fontSize.scaledBy(factor), lineHeight = lineHeight.scaledBy(factor))
}

private fun TextUnit.scaledBy(factor: Float): TextUnit =
    if (isSpecified) TextUnit(ChatTextScale.scaled(value, factor), type) else this
