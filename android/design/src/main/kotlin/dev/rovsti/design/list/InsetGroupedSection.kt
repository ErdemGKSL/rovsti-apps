package dev.rovsti.design.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/** Test tag of every separator between two rows of an [InsetGroupedSection]. */
const val GroupedSeparatorTag = "grouped-separator"

private val SeparatorThickness = 0.5.dp

/** Collects the rows of a section. */
class GroupedSectionScope internal constructor() {
    internal val rows = ArrayList<@Composable () -> Unit>()

    /** One row; usually a [ListRow]. */
    fun row(content: @Composable () -> Unit) {
        rows.add(content)
    }
}

/**
 * Rounded card (radius 20 dp) on the page, 16 dp in from the screen edges, with the rows stacked inside. Separators are
 * 0.5 dp lines that start at [separatorInset] (the text start of the rows, see [TextStartPlain] and
 * [TextStartWithLeading]) and run to the card edge; there is none after the last row. The card clips its rows, so a
 * row's press fill follows the rounded corners. [header] and [footer] sit outside the card, aligned with the row text.
 */
@Composable
fun InsetGroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    separatorInset: Dp = TextStartPlain,
    content: GroupedSectionScope.() -> Unit,
) {
    val colors = RovstiTheme.colors
    val rows = GroupedSectionScope().apply(content).rows
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        if (header != null) SectionHeader(header, horizontal = Spacing.gutter)
        Column(Modifier.fillMaxWidth().clip(RovstiShapes.grouped).background(colors.groupedSurface)) {
            rows.forEachIndexed { index, row ->
                row()
                if (index < rows.lastIndex) RowSeparator(inset = separatorInset)
            }
        }
        if (footer != null) {
            RovstiText(
                footer,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                style = RovstiTheme.type.footnote,
                color = colors.labelSecondary,
            )
        }
    }
}

/** A 0.5 dp separator that starts [inset] from the start edge. Used between rows; never put one after the last row. */
@Composable
fun RowSeparator(
    modifier: Modifier = Modifier,
    inset: Dp = TextStartPlain,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .testTag(GroupedSeparatorTag)
            .height(SeparatorThickness)
            .background(RovstiTheme.colors.separator),
    )
}
