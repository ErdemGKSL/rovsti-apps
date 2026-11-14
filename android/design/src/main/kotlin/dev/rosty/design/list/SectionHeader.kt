package dev.rosty.design.list

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing

/**
 * A sentence-case section title in `footnote` secondary (ORIGINAL), announced as a heading. [horizontal] is the space
 * from the edge: the 16 dp gutter in a plain list, more when the title sits above an inset card.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    horizontal: Dp = Spacing.gutter,
) {
    RostyText(
        text,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = horizontal, end = horizontal, top = Spacing.s, bottom = Spacing.s)
                .semantics { heading() },
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.labelSecondary,
    )
}
