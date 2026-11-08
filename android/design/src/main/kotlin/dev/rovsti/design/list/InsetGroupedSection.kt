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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.ios.component.IosCardSection
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosMetrics
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/** Test tag of every separator between two rows of an [InsetGroupedSection]. */
const val GroupedSeparatorTag = "grouped-separator"

/** Collects the rows of a section. */
class GroupedSectionScope internal constructor() {
    internal val rows = ArrayList<@Composable () -> Unit>()

    /** One row; usually a [ListRow]. */
    fun row(content: @Composable () -> Unit) {
        rows.add(content)
    }
}

/**
 * Rounded card (Convx radius 26 dp) on the page, 16 dp in from the screen edges, with the rows stacked inside. The card,
 * its outer corners and the separators are drawn by Convx's [IosCardSection] (the same code as a section of `IosList`):
 * separators start at [separatorInset] (the text start of the rows, see [TextStartPlain] and [TextStartWithLeading]) and
 * run to the card edge; there is none after the last row. The card clips its rows, so a row's press fill follows the
 * rounded corners. [header] and [footer] sit outside the card, aligned with the row text.
 */
@Composable
fun InsetGroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    separatorInset: Dp = TextStartPlain,
    content: GroupedSectionScope.() -> Unit,
) {
    val rows = GroupedSectionScope().apply(content).rows
    IosCardSection(
        rows = rows,
        modifier = modifier,
        header = header?.let { text -> { SectionHeaderText(text) } },
        footer = footer?.let { text -> { IosText(text) } },
        separatorInset = separatorInset,
        separatorTag = GroupedSeparatorTag,
    )
}

@Composable
private fun SectionHeaderText(text: String) {
    IosText(text, modifier = Modifier.semantics { heading() })
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
            .height(IosMetrics.SeparatorThickness)
            .background(RovstiTheme.colors.separator),
    )
}
