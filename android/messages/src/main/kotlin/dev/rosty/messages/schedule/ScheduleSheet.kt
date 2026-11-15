package dev.rosty.messages.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.GlassIconButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.design.time.LocalRostyClock
import dev.rosty.messages.schedule.theme.ScheduleMetrics
import dev.rosty.messages.theme.MessagesColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Send later sheet. */
object ScheduleTags {
    const val Sheet = "schedule-sheet"
    const val Confirm = "schedule-confirm"
    const val Error = "schedule-error"
    const val LateNote = "schedule-late-note"
    const val DayEarlier = "schedule-day-earlier"
    const val DayLater = "schedule-day-later"
    const val TimeEarlier = "schedule-time-earlier"
    const val TimeLater = "schedule-time-later"
    const val Strip = "scheduled-strip"
    const val StripMore = "scheduled-strip-more"

    fun quick(index: Int) = "schedule-quick-$index"

    fun cancel(index: Int) = "scheduled-cancel-$index"
}

/** The words of the Send later sheet and the Scheduled strip, in the phone's language. */
class ScheduleWords(
    val title: String,
    /** The label of one of Rust's quick picks ("In 1 hour", "This evening", ...). */
    val quick: (String) -> String,
    val day: String,
    val time: String,
    val earlierDay: String,
    val laterDay: String,
    val earlierTime: String,
    val laterTime: String,
    /** "Schedule for Today 18:30". */
    val confirm: (String) -> String,
    val tooSoon: String,
    val lateNote: String,
    val stripTitle: String,
    val cancel: String,
    /** "Cancel the message scheduled for Today 18:30", for a screen reader. */
    val cancelFor: (String) -> String,
    /** "Today 18:30 · See you at six". */
    val waiting: (String, String) -> String,
    /** "Not sent · See you at six". */
    val notSent: (String) -> String,
    val more: (Int) -> String,
)

/** One of the quick times of the sheet. */
@Immutable
data class QuickPick(
    val label: String,
    val atMs: Long,
)

/** What the sheet shows; built before it opens. */
@Immutable
data class ScheduleSheetUi(
    /** The text being scheduled. */
    val body: String,
    val quick: ImmutableList<QuickPick> = persistentListOf(),
    /** An SMS while exact alarms are denied: Android may send it a few minutes late. */
    val lateNote: Boolean = false,
    /** Large text: the sheet opens at its tallest, so the Schedule button is not below the fold. */
    val tall: Boolean = false,
)

/** The part of the sheet that changes while it is open. */
@Stable
class ScheduleSheetState(
    initial: CustomPick,
) {
    var pick by mutableStateOf(initial)

    /** Why the last choice was refused, in the user's words. */
    var error by mutableStateOf<String?>(null)

    /** A choice is being scheduled: the sheet takes no second one. */
    var busy by mutableStateOf(false)
}

/**
 * Presents "Send later" (E6.10, AD-MSG6; GPUI `ScheduleSheet`): three quick times, a day and a time stepper (15-minute
 * steps, today to 30 days ahead) and the Schedule button. [onPick] gets the chosen time; it closes the sheet itself
 * when the message was scheduled, or sets [ScheduleSheetState.error].
 */
fun OverlayController.presentSchedule(
    ui: ScheduleSheetUi,
    state: ScheduleSheetState,
    words: ScheduleWords,
    labels: ScheduleLabels,
    onPick: (Long) -> Unit,
    onLateNote: () -> Unit,
): OverlayEntry =
    present(
        SheetEntry(
            title = words.title,
            detents = persistentListOf(Detent.Fraction(SHEET_FRACTION), Detent.Large),
            initialDetent = if (ui.tall) Detent.Large else Detent.Fraction(SHEET_FRACTION),
        ) { _ -> ScheduleSheetContent(ui, state, words, labels, onPick, onLateNote) },
    )

@Composable
internal fun ScheduleSheetContent(
    ui: ScheduleSheetUi,
    state: ScheduleSheetState,
    words: ScheduleWords,
    labels: ScheduleLabels,
    onPick: (Long) -> Unit,
    onLateNote: () -> Unit,
) {
    val clock = LocalRostyClock.current
    val now = remember(clock) { clock.nowMillis() }
    val colors = RostyTheme.colors
    val choose: (Long) -> Unit = { at -> if (!state.busy) onPick(at) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(ScheduleTags.Sheet)) {
        Column(Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.s)) {
            RostyText(words.title, style = RostyTheme.type.title3)
            RostyText(
                ScheduleRules.preview(ui.body),
                style = RostyTheme.type.subheadline,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ui.quick.forEachIndexed { i, quick ->
            if (i > 0) RowSeparator()
            ListRow(
                title = words.quick(quick.label),
                modifier = Modifier.testTag(ScheduleTags.quick(i)),
                meta = labels.whenLabel(quick.atMs, now),
                onClick = { choose(quick.atMs) },
            )
        }
        if (ui.quick.isNotEmpty()) RowSeparator()
        Stepper(
            name = words.day,
            value = labels.day(labels.atMs(state.pick, now), now),
            earlier = words.earlierDay to ScheduleTags.DayEarlier,
            later = words.laterDay to ScheduleTags.DayLater,
            onStep = { delta ->
                state.pick = state.pick.stepDay(delta)
                state.error = null
            },
        )
        Stepper(
            name = words.time,
            value = labels.clock(labels.atMs(state.pick, now)),
            earlier = words.earlierTime to ScheduleTags.TimeEarlier,
            later = words.laterTime to ScheduleTags.TimeLater,
            onStep = { delta ->
                state.pick = state.pick.stepMinutes(delta)
                state.error = null
            },
        )
        CapsuleButton(
            words.confirm(labels.whenLabel(labels.atMs(state.pick, now), now)),
            onClick = {
                // The clock moved on while the sheet was open: check against now, not against when it opened.
                val at = labels.atMs(state.pick, now)
                if (at < clock.nowMillis() + ScheduleRules.MIN_LEAD_MS) state.error = words.tooSoon else choose(at)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter)
                    .padding(top = Spacing.s)
                    .testTag(ScheduleTags.Confirm),
            style = CapsuleStyle.Filled,
            // The deep accent of my bubbles: white text reads on it in both themes (the dark accent is too light).
            tint = MessagesColors.OutgoingBubble,
            enabled = !state.busy,
            fillWidth = true,
        )
        state.error?.let {
            RostyText(
                it,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter)
                        .padding(top = Spacing.s)
                        .testTag(ScheduleTags.Error),
                style = RostyTheme.type.footnote,
                color = colors.destructive,
                textAlign = TextAlign.Center,
            )
        }
        if (ui.lateNote) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onLateNote)
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.m)
                    .testTag(ScheduleTags.LateNote),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                RostyIcon(RostyIcons.Clock, null, tint = colors.labelSecondary, size = ScheduleMetrics.NoteGlyph)
                RostyText(
                    words.lateNote,
                    modifier = Modifier.weight(1f),
                    style = RostyTheme.type.footnote,
                    color = colors.labelSecondary,
                )
            }
        }
    }
}

/** "Day ‹ Tomorrow ›": one value with a button to each side; the row reads as one adjustable value. */
@Composable
private fun Stepper(
    name: String,
    value: String,
    earlier: Pair<String, String>,
    later: Pair<String, String>,
    onStep: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.s)
            .semantics { stateDescription = value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RostyText(
            name,
            modifier = Modifier.weight(1f),
            style = RostyTheme.type.body,
            color = RostyTheme.colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        GlassIconButton(
            RostyIcons.Back,
            earlier.first,
            onClick = { onStep(-1) },
            modifier = Modifier.testTag(earlier.second),
        )
        RostyText(
            value,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs),
            style = RostyTheme.type.headline,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        GlassIconButton(RostyIcons.ChevronRight, later.first, onClick = {
            onStep(1)
        }, modifier = Modifier.testTag(later.second))
    }
}

private const val SHEET_FRACTION = 0.62f
