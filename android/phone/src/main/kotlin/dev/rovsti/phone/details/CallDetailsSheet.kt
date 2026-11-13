package dev.rovsti.phone.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.GroupedSectionScope
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.text.imeOverlapPadding
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.time.LocalRovstiClock
import dev.rovsti.phone.DetailsMetrics
import dev.rovsti.phone.details.photo.PersonPhoto
import dev.rovsti.phone.details.photo.PersonPhotoAvatar
import dev.rovsti.phone.remind.RemindStrings
import dev.rovsti.phone.remind.RemindTime
import dev.rovsti.phone.remind.menuAnchor
import dev.rovsti.phone.remind.presentRemindMenu
import dev.rovsti.phone.remind.remindMenuItems
import dev.rovsti.phone.remind.showTimePicker

/** Test tags of the Call details sheet. */
object CallDetailsTags {
    const val Sheet = "call-details-sheet"
    const val Title = "call-details-title"
    const val SavedAs = "call-details-saved-as"
    const val Note = "call-details-note"
    const val NoteSave = "call-details-note-save"
    const val NoteRevert = "call-details-note-revert"
    const val NoteDelete = "call-details-note-delete"
    const val Copy = "call-details-copy"
    const val Share = "call-details-share"
    const val Remind = "call-details-remind"
    const val Delete = "call-details-delete"
}

/**
 * The body of the Call details sheet (E5.4, U11), stateless. A title with the person under it, the core's info rows
 * (when, duration, channel, number), the note field (U8), Copy and Share capsules, Remind me (U44) and Delete this
 * call.
 *
 * The note is a multi-line [GlassTextField] with the platform caret, selection handles and paste menu. The
 * column pads
 * itself by the keyboard's overlap ([imeOverlapPadding], never `imePadding`: some vendor builds resize the window
 * too), so
 * the field stays above the keyboard and the sheet does not jump. A note that differs from the saved one
 * shows Save and
 * Cancel; a saved, unchanged one shows Delete note. [onEditStart] runs when the field takes focus (the sheet rises
 * to its
 * large detent so the keyboard has room).
 */
@Composable
fun ColumnScope.CallDetailsContent(
    ui: CallDetailsUi,
    who: String?,
    strings: DetailsStrings,
    note: TextFieldState,
    photo: PersonPhoto? = null,
    onAction: (CallDetailsAction) -> Unit,
    onEditStart: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    var saved by remember { mutableStateOf("") }
    // The saved note arrives after the sheet is up; an edit in progress is never overwritten by it.
    LaunchedEffect(ui.note) {
        if (note.text.toString() == saved) note.setTextAndPlaceCursorAtEnd(ui.note)
        saved = ui.note
    }
    val typed = note.text.toString()
    val dirty = typed.trim() != ui.note
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            modifier
                .fillMaxWidth()
                .weight(1f)
                .imeOverlapPadding()
                .verticalScroll(rememberScrollState())
                .testTag(CallDetailsTags.Sheet),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.s),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (photo != null) {
                    PersonPhotoAvatar(
                        name = who.orEmpty(),
                        size = DetailsMetrics.CallAvatar,
                        colorKey = ui.number.ifEmpty { ui.title },
                        photo = photo,
                        modifier = Modifier.padding(bottom = Spacing.m),
                    )
                }
                RovstiText(
                    ui.title,
                    modifier = Modifier.testTag(CallDetailsTags.Title).semantics { heading() },
                    style = RovstiTheme.type.title2,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!who.isNullOrBlank()) {
                    RovstiText(
                        who,
                        modifier = Modifier.padding(top = Spacing.textGap),
                        style = RovstiTheme.type.subheadline,
                        color = colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ui.savedAs?.let { saved ->
                    RovstiText(
                        strings.savedAs(saved),
                        modifier = Modifier.padding(top = Spacing.textGap).testTag(CallDetailsTags.SavedAs),
                        style = RovstiTheme.type.footnote,
                        color = colors.labelSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.height(Spacing.l))
            InsetGroupedSection {
                infoRow(strings.whenLabel, ui.whenText)
                ui.duration?.let { infoRow(strings.durationLabel, it) }
                infoRow(strings.channelLabel, ui.channel)
                infoRow(strings.numberLabel, ui.number)
            }
            Box(Modifier.height(Spacing.sectionGap))
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
                RovstiText(
                    strings.note,
                    modifier = Modifier.padding(start = Spacing.s, bottom = Spacing.s).semantics { heading() },
                    style = RovstiTheme.type.footnote,
                    color = colors.labelSecondary,
                )
                GlassTextField(
                    state = note,
                    placeholder = strings.notePlaceholder,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) onEditStart() }
                            .testTag(CallDetailsTags.Note),
                    maxLines = NOTE_MAX_LINES,
                    minLines = NOTE_MIN_LINES,
                )
                if (dirty) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = Spacing.m),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        CapsuleButton(
                            strings.noteRevert,
                            onClick = { note.setTextAndPlaceCursorAtEnd(ui.note) },
                            modifier = Modifier.weight(1f).testTag(CallDetailsTags.NoteRevert),
                            fillWidth = true,
                        )
                        CapsuleButton(
                            strings.noteSave,
                            onClick = { onAction(CallDetailsAction.SaveNote(typed)) },
                            modifier = Modifier.weight(1f).testTag(CallDetailsTags.NoteSave),
                            style = CapsuleStyle.Filled,
                            fillWidth = true,
                        )
                    }
                } else if (ui.note.isNotEmpty()) {
                    TextButton(
                        strings.noteDelete,
                        onClick = { onAction(CallDetailsAction.DeleteNote) },
                        modifier = Modifier.align(Alignment.Start).testTag(CallDetailsTags.NoteDelete),
                        tint = colors.destructive,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).padding(top = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                CapsuleButton(
                    strings.copy,
                    onClick = { onAction(CallDetailsAction.Copy) },
                    modifier = Modifier.weight(1f).testTag(CallDetailsTags.Copy),
                    icon = RovstiIcons.Copy,
                    fillWidth = true,
                )
                CapsuleButton(
                    strings.share,
                    onClick = { onAction(CallDetailsAction.Share) },
                    modifier = Modifier.weight(1f).testTag(CallDetailsTags.Share),
                    icon = RovstiIcons.Share,
                    fillWidth = true,
                )
            }
            Box(Modifier.height(Spacing.l))
            InsetGroupedSection {
                row {
                    ListRow(
                        title = strings.remind,
                        leading = {
                            RovstiIcon(
                                RovstiIcons.Clock,
                                null,
                                tint = colors.accent,
                                size = DetailsMetrics.RowGlyph,
                            )
                        },
                        titleColor = colors.accent,
                        onClick = { onAction(CallDetailsAction.AskRemind) },
                        modifier = Modifier.testTag(CallDetailsTags.Remind),
                    )
                }
                if (ui.deletable) {
                    row {
                        ListRow(
                            title = strings.deleteCall,
                            leading = {
                                RovstiIcon(
                                    RovstiIcons.Trash,
                                    null,
                                    tint = colors.destructive,
                                    size = DetailsMetrics.RowGlyph,
                                )
                            },
                            titleColor = colors.destructive,
                            onClick = { onAction(CallDetailsAction.DeleteCall) },
                            modifier = Modifier.testTag(CallDetailsTags.Delete),
                        )
                    }
                }
            }
            Box(Modifier.height(Spacing.xl))
        }
    }
}

private const val NOTE_MIN_LINES = 3
private const val NOTE_MAX_LINES = 6

private fun GroupedSectionScope.infoRow(
    label: String,
    value: String,
) = row { ListRow(title = label, meta = value) }

/**
 * The stateful sheet: collects the model, owns the note's text state, runs the effects and rises to the large
 * detent when
 * the note field takes focus. [onClose] dismisses the sheet (a deleted call).
 */
@Composable
fun ColumnScope.CallDetailsSheet(
    model: CallDetailsViewModel,
    sheet: SheetState,
    host: DetailsHost,
    onClose: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val strings = DetailsStrings.current()
    val note = rememberTextFieldState()
    DisposableEffect(model) { onDispose { model.close() } }
    CallDetailsEffects(model, host, strings, onClose)
    CallDetailsContent(
        ui = ui,
        who = model.target.who,
        strings = strings,
        note = note,
        photo = model.target.photo,
        onAction = model::onAction,
        onEditStart = { sheet.animateToDetent(sheet.detentOffsets.lastIndex) },
    )
}

/** Where the remind menu opens, as a fraction of the window height (the Remind row of the sheet is near there). */
private const val REMIND_MENU_AT = 0.6f

/** Runs the model's one-shot results. */
@Composable
fun CallDetailsEffects(
    model: CallDetailsViewModel,
    host: DetailsHost,
    strings: DetailsStrings,
    onClose: () -> Unit,
) {
    val overlays = LocalOverlayOrNull.current ?: return
    val haptics = LocalRovstiHaptics.current
    val context = LocalContext.current
    val clock = LocalRovstiClock.current
    val windowSize = LocalWindowInfo.current.containerSize
    val remindStrings = remember(context) { RemindStrings(context.resources) }
    val currentHost by rememberUpdatedState(host)
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(model, overlays, strings) {
        model.effect.collect { effect ->
            when (effect) {
                CallDetailsEffect.Reject -> {
                    haptics.reject()
                }

                is CallDetailsEffect.Say -> {
                    if (effect.notice == CallDetailsNotice.NoteSaved || effect.notice == CallDetailsNotice.Copied) {
                        haptics.confirm()
                    }
                    overlays.showToast(strings.say(effect.notice))
                }

                is CallDetailsEffect.Reminded -> {
                    haptics.confirm()
                    overlays.showToast(effect.text)
                }

                is CallDetailsEffect.ShareText -> {
                    currentHost.share(effect.text)
                }

                CallDetailsEffect.Close -> {
                    close()
                }

                is CallDetailsEffect.ChooseRemind -> {
                    overlays.presentRemindMenu(
                        menuAnchor(windowSize, REMIND_MENU_AT),
                        remindMenuItems(
                            effect.slots,
                            remindStrings.pickTime,
                            onSlot = { model.onAction(CallDetailsAction.Remind(it)) },
                            onPickTime = {
                                showTimePicker(context, clock.nowMillis()) { at ->
                                    model.onAction(CallDetailsAction.RemindAtTime(at))
                                }
                            },
                        ),
                    )
                }

                is CallDetailsEffect.RemindedAt -> {
                    overlays.showToast(remindStrings.setAt(RemindTime.clock(context, effect.atMs)))
                }

                is CallDetailsEffect.Undoable -> {
                    haptics.confirm()
                    overlays.showUndo(
                        message = strings.undoable(effect.kind),
                        onUndo = { model.onAction(CallDetailsAction.Undo(effect.undo)) },
                        actionLabel = strings.undo,
                    )
                }
            }
        }
    }
}
