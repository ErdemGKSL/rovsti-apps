package dev.rovsti.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The type scale. Sizes are `sp` so system font scale applies; every style has an explicit line height. */
@Immutable
data class RovstiType(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption1: TextStyle,
    val caption2: TextStyle,
) {
    /** Every style with its name, in scale order (catalog and tests). */
    val all: List<Pair<String, TextStyle>>
        get() =
            listOf(
                "largeTitle" to largeTitle,
                "title1" to title1,
                "title2" to title2,
                "title3" to title3,
                "headline" to headline,
                "body" to body,
                "callout" to callout,
                "subheadline" to subheadline,
                "footnote" to footnote,
                "caption1" to caption1,
                "caption2" to caption2,
            )

    companion object {
        private fun style(
            size: Int,
            line: Int,
            weight: FontWeight = FontWeight.Normal,
            tracking: TextUnit = TextUnit.Unspecified,
        ) = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = size.sp,
            lineHeight = line.sp,
            fontWeight = weight,
            letterSpacing = tracking,
        )

        val Default =
            RovstiType(
                largeTitle = style(34, 41, FontWeight.Bold, (-0.7).sp),
                title1 = style(28, 34, FontWeight.Bold, (-0.4).sp),
                title2 = style(22, 28, FontWeight.Bold, (-0.3).sp),
                title3 = style(20, 25, FontWeight.SemiBold),
                headline = style(17, 22, FontWeight.SemiBold),
                body = style(17, 22),
                callout = style(16, 21),
                subheadline = style(15, 20),
                footnote = style(13, 18),
                caption1 = style(12, 16),
                caption2 = style(11, 13),
            )
    }
}
