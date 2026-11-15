package dev.rosty.messages.wa

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import dev.rosty.core.model.wa.WaSyncHistory
import dev.rosty.core.model.wa.WaSyncHistoryState
import dev.rosty.design.ios.foundation.IosMetrics
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R

/** The history sync as the row shows it; there is a row only while the history is arriving. */
@Immutable
data class WaHistoryUi(
    /** 0 to 100; null while the phone has not said how far it is. */
    val percent: Int?,
    /** Chats and messages the gateway holds so far. */
    val chats: Int,
    val messages: Long,
) {
    /** The fill of the bar, 0..1; null: no bar (the progress is not known). */
    val fraction: Float? get() = percent?.let { it / PERCENT }

    companion object {
        private const val PERCENT = 100f
        private const val FULL = 100

        /**
         * The row of [history] as the store keeps it: one only while it says `syncing` (it goes when the sync is
         * `done` or `idle`, and there is none before the gateway reported). The percent is kept inside 0..100 and
         * counts are never negative.
         */
        fun of(history: WaSyncHistory?): WaHistoryUi? =
            history
                ?.takeIf { it.state == WaSyncHistoryState.Syncing }
                ?.let {
                    WaHistoryUi(
                        percent = it.progress?.coerceIn(0, FULL),
                        chats = it.chats.coerceAtLeast(0),
                        messages = it.messages.coerceAtLeast(0),
                    )
                }
    }
}

/** Test tags of the history row. */
object WaHistoryTags {
    const val Row = "wa-history"
    const val Bar = "wa-history-bar"
}

/**
 * The history progress: what is arriving and how far it is, the chats and messages so far, and that the app can be
 * used meanwhile. A card on the 16 dp margin; the same on the WhatsApp tab and in the WhatsApp settings.
 */
@Composable
fun WaHistoryRow(
    ui: WaHistoryUi,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    val title =
        ui.percent?.let { stringResource(R.string.wa_history_title_percent, it) }
            ?: stringResource(R.string.wa_history_title)
    val chats = pluralStringResource(R.plurals.wa_history_chats, ui.chats, ui.chats)
    val messages = pluralStringResource(R.plurals.wa_history_messages, quantityOf(ui.messages), ui.messages)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .clip(RoundedCornerShape(IosMetrics.GroupedCornerRadius))
            .background(colors.groupedSurface)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.m)
            .semantics(mergeDescendants = true) {}
            .testTag(WaHistoryTags.Row),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        RostyText(title, style = RostyTheme.type.subheadline, color = colors.labelPrimary)
        ui.fraction?.let { fraction ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs)
                    .height(Spacing.xs)
                    .clip(CircleShape)
                    .background(colors.separator)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
                    .testTag(WaHistoryTags.Bar),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
        RostyText(
            stringResource(R.string.wa_history_counts, chats, messages),
            style = RostyTheme.type.footnote,
            color = colors.labelSecondary,
        )
        RostyText(
            stringResource(R.string.wa_history_usable),
            style = RostyTheme.type.footnote,
            color = colors.labelSecondary,
        )
    }
}

/** A plural quantity for a count that may not fit an Int: only its size class matters. */
private fun quantityOf(count: Long): Int = count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
