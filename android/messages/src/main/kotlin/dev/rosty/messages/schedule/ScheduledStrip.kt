package dev.rosty.messages.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.controls.TextButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.schedule.theme.ScheduleMetrics
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** One row of the Scheduled strip, formatted. */
@Immutable
data class StripRowUi(
    val key: StripKey,
    /** "Today 18:30 · See you at six", or "Not sent · See you at six". */
    val line: String,
    val failed: Boolean,
    /** What a screen reader says for the row's Cancel. */
    val cancelLabel: String,
)

/** The Scheduled strip of one conversation. */
@Immutable
data class ScheduledStripUi(
    val rows: ImmutableList<StripRowUi> = persistentListOf(),
    /** Rows behind "Show n more". */
    val hidden: Int = 0,
) {
    companion object {
        /** The strip of [items] (soonest first): the first few rows, or all when [expanded]. */
        fun of(
            items: List<StripItem>,
            expanded: Boolean,
            nowMs: Long,
            words: ScheduleWords,
            labels: ScheduleLabels,
        ): ScheduledStripUi {
            val shown = ScheduleRules.visible(items, expanded)
            return ScheduledStripUi(
                rows =
                    shown
                        .map { item ->
                            val whenLabel = labels.whenLabel(item.atMs, nowMs)
                            val preview = ScheduleRules.preview(item.body)
                            StripRowUi(
                                key = item.key,
                                line = if (item.failed) words.notSent(preview) else words.waiting(whenLabel, preview),
                                failed = item.failed,
                                cancelLabel = words.cancelFor(whenLabel),
                            )
                        }.toImmutableList(),
                hidden = items.size - shown.size,
            )
        }
    }
}

/**
 * The Scheduled strip above a composer (E6.10; GPUI `schedule_ui::strip`): what still waits in this conversation, each
 * with Cancel. A message the gateway could not send stays, in red, until it is cancelled. Nothing is drawn when
 * nothing waits.
 */
@Composable
fun ScheduledStrip(
    ui: ScheduledStripUi,
    words: ScheduleWords,
    onCancel: (StripKey) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ui.rows.isEmpty()) return
    val colors = RostyTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.xs)
            .clip(RoundedCornerShape(ScheduleMetrics.StripCorner))
            .background(colors.fillTertiary)
            .heightIn(max = ScheduleMetrics.StripMaxHeight)
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.m, top = Spacing.s)
            .testTag(ScheduleTags.Strip),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RostyIcon(RostyIcons.Clock, null, tint = colors.labelSecondary, size = ScheduleMetrics.NoteGlyph)
            RostyText(words.stripTitle, style = RostyTheme.type.caption1, color = colors.labelSecondary)
        }
        ui.rows.forEachIndexed { i, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RostyText(
                    row.line.rtlSafe(),
                    modifier = Modifier.weight(1f),
                    style = RostyTheme.type.footnote,
                    color = if (row.failed) colors.destructive else colors.labelPrimary,
                    // Large text: one line per row, so three rows and their buttons still fit above the composer.
                    maxLines = if (LocalDensity.current.fontScale >= LARGE_TEXT_SCALE) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    words.cancel,
                    onClick = { onCancel(row.key) },
                    modifier =
                        Modifier
                            .semantics { contentDescription = row.cancelLabel }
                            .testTag(ScheduleTags.cancel(i)),
                )
            }
        }
        if (ui.hidden > 0) {
            // The button pads its label; pulled back so the label lines up with the rows above.
            TextButton(
                words.more(ui.hidden),
                onClick = onMore,
                modifier = Modifier.offset(x = -Spacing.s).testTag(ScheduleTags.StripMore),
            )
        }
    }
}

private const val LARGE_TEXT_SCALE = 1.3f
