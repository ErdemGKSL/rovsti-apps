package dev.rovsti.phone.incoming

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.chat.ReminderSlotOption
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Presents the two sheets of the Incoming screen over whatever is showing: "Decline with message" (the core's reply
 * choices, then Custom...) and "Remind me" (the core's time slots). Choosing a row closes the sheet first, then acts.
 */
class IncomingSheets(
    private val overlays: OverlayController,
    private val strings: IncomingStrings,
    private val onAction: (IncomingAction) -> Unit,
) {
    fun replies(effect: IncomingEffect.ShowReplies): SheetEntry {
        val entry =
            SheetEntry(title = strings.repliesTitle, detents = persistentListOf(Detent.Medium, Detent.Large)) { sheet ->
                RepliesSheet(
                    choices = effect.choices,
                    strings = strings,
                    onPick = { text ->
                        sheet.dismiss()
                        onAction(IncomingAction.Reply(effect.callId, text))
                    },
                    onCustom = {
                        sheet.dismiss()
                        onAction(IncomingAction.ReplyCustom(effect.callId))
                    },
                )
            }
        overlays.present(entry)
        return entry
    }

    fun reminders(effect: IncomingEffect.ShowReminders): SheetEntry {
        val entry =
            SheetEntry(title = strings.remindTitle, detents = persistentListOf(Detent.Medium)) { sheet ->
                RemindSheet(
                    slots = effect.slots,
                    strings = strings,
                    onPick = { slot ->
                        sheet.dismiss()
                        onAction(IncomingAction.RemindAt(slot))
                    },
                )
            }
        overlays.present(entry)
        return entry
    }
}

/** The reply choices as one grouped section under a title, and "Custom..." as its last row. */
@Composable
fun ColumnScope.RepliesSheet(
    choices: ImmutableList<String>,
    strings: IncomingStrings,
    onPick: (String) -> Unit,
    onCustom: () -> Unit,
) {
    SheetBody(strings.repliesTitle) {
        InsetGroupedSection {
            choices.forEachIndexed { index, text ->
                row {
                    ListRow(
                        title = text,
                        titleMaxLines = REPLY_LINES,
                        onClick = { onPick(text) },
                        modifier = Modifier.testTag(IncomingTags.ReplyPrefix + index),
                    )
                }
            }
            row {
                ListRow(
                    title = strings.replyCustom,
                    trailing = { ListRowChevron() },
                    onClick = onCustom,
                    modifier = Modifier.testTag(IncomingTags.ReplyCustom),
                )
            }
        }
    }
}

@Composable
fun ColumnScope.RemindSheet(
    slots: ImmutableList<ReminderSlotOption>,
    strings: IncomingStrings,
    onPick: (ReminderSlot) -> Unit,
) {
    SheetBody(strings.remindTitle) {
        InsetGroupedSection {
            slots.forEach { slot ->
                row { ListRow(title = slot.label, onClick = { onPick(slot.key) }) }
            }
        }
    }
}

@Composable
private fun ColumnScope.SheetBody(
    title: String,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            RovstiText(
                title,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter)
                        .padding(top = Spacing.s)
                        .semantics { heading() },
                style = RovstiTheme.type.title2,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.height(Spacing.l))
            content()
            Box(Modifier.height(Spacing.xl))
        }
    }
}

/**
 * Collects the model's effects once for the whole app: sheets and toasts go through the [overlays], the
 * haptics through the design system's, and [onLeave] closes the Incoming screen (or opens In-Call) when the ringing
 * call is gone.
 */
@Composable
fun IncomingEffects(
    model: IncomingViewModel,
    overlays: OverlayController,
    onLeave: (toCall: Boolean) -> Unit,
) {
    val haptics = LocalRovstiHaptics.current
    val strings = IncomingStrings.current()
    val sheets = remember(overlays, strings, model) { IncomingSheets(overlays, strings, model::onAction) }
    val leave by rememberUpdatedState(onLeave)
    LaunchedEffect(model, overlays) {
        model.effect.collect { effect ->
            when (effect) {
                is IncomingEffect.ShowReplies -> sheets.replies(effect)
                is IncomingEffect.ShowReminders -> sheets.reminders(effect)
                is IncomingEffect.Notice -> overlays.showToast(strings.say(effect.notice, effect.text))
                is IncomingEffect.Leave -> leave(effect.toCall)
                IncomingEffect.Confirm -> haptics.confirm()
                IncomingEffect.Reject -> haptics.reject()
            }
        }
    }
}

private const val REPLY_LINES = 3
