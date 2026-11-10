package dev.rovsti.phone.incall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.ios.component.IosContextMenuEntry
import dev.rovsti.design.ios.component.IosMenuItem
import dev.rovsti.design.ios.component.IosMenuSection
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.PopoverEntry
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.text.imeOverlapPadding
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.data.BlockUndo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

/** What only the platform can do for the In-Call screen. */
interface InCallHost {
    fun share(text: String)
}

/** The sheets, menus and pills of the In-Call screen, presented on the app's overlay controller. */
class InCallSheets(
    private val overlays: OverlayController,
    private val strings: InCallStrings,
    private val model: InCallViewModel,
    private val host: InCallHost,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private var popover: PopoverEntry? = null

    /** The audio route list, grown out of the Audio button. */
    fun routes(
        anchor: Rect,
        state: kotlinx.coroutines.flow.StateFlow<InCallUi>,
    ) {
        popover?.let { if (it.state.targetState) overlays.dismiss(it) }
        lateinit var entry: PopoverEntry
        entry =
            PopoverEntry(anchor, title = strings.routeTitle) {
                val ui by state.collectAsState()
                RoutePickerContent(
                    rows = ui.routeRows,
                    strings = strings,
                    onPick = { id ->
                        overlays.dismiss(entry)
                        model.onAction(InCallAction.PickRoute(id))
                    },
                )
            }
        popover = entry
        overlays.present(entry)
    }

    /** The menu on the timer: Copy time, Share summary (U37). */
    fun timerMenu(
        anchor: Rect,
        timerText: String,
        summary: String,
    ) {
        overlays.present(
            IosContextMenuEntry(
                anchor,
                listOf(
                    IosMenuSection(
                        listOf(
                            IosMenuItem(
                                strings.timerCopy,
                                onClick = { model.onAction(InCallAction.CopyTimer(timerText)) },
                            ),
                            IosMenuItem(
                                strings.timerShare,
                                onClick = { model.onAction(InCallAction.ShareSummary(summary)) },
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    /** The note sheet for a call; Save stores it under the number and the start time. */
    fun note(effect: InCallEffect.ShowNote) {
        val entry =
            SheetEntry(title = strings.noteTitle, detents = persistentListOf(Detent.Medium, Detent.Large)) { sheet ->
                NoteSheetBody(effect, strings) { text ->
                    sheet.dismiss()
                    model.save(effect.number, effect.startedMs, text)
                }
            }
        overlays.present(entry)
    }

    /** The pill after a call: Block (then Undo) for a short call from a stranger, Add note after a connected one. */
    fun postCall(effect: InCallEffect.PostCall) {
        when (effect.kind) {
            PostCallKind.Block -> {
                overlays.showUndo(
                    message = strings.blockMessage(effect.title),
                    actionLabel = strings.postCallBlockAction,
                    onUndo = {
                        scope.launch {
                            val undo = model.block(effect.number)
                            if (undo ==
                                null
                            ) {
                                overlays.showToast(strings.postCallBlockFailed)
                            } else {
                                blockedPill(effect.title, undo)
                            }
                        }
                    },
                )
            }

            PostCallKind.AddNote -> {
                overlays.showUndo(
                    message =
                        strings.summary(
                            effect.title,
                            EndedUi(0, true, effect.talkS, effect.elapsed),
                            effect.title != effect.number,
                        ),
                    actionLabel = strings.postCallAddNote,
                    onUndo = { note(InCallEffect.ShowNote(effect.number, effect.startedMs, effect.title, "")) },
                )
            }
        }
    }

    private fun blockedPill(
        title: String,
        undo: BlockUndo,
    ) {
        overlays.showUndo(
            message = strings.blocked(title),
            actionLabel = strings.postCallUndo,
            onUndo = { scope.launch { model.undoBlock(undo) } },
        )
    }

    fun share(text: String) = host.share(text)
}

@Composable
private fun ColumnScope.NoteSheetBody(
    effect: InCallEffect.ShowNote,
    strings: InCallStrings,
    onSave: (String) -> Unit,
) {
    val note: TextFieldState = rememberTextFieldState(effect.text)
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .imeOverlapPadding()
                .padding(horizontal = Spacing.gutter),
        ) {
            RovstiText(
                effect.title,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s).semantics { heading() },
                style = RovstiTheme.type.title2,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.l))
            GlassTextField(
                state = note,
                placeholder = strings.notePlaceholder,
                modifier = Modifier.fillMaxWidth().testTag(NOTE_FIELD_TAG),
                maxLines = NOTE_MAX_LINES,
                minLines = NOTE_MIN_LINES,
            )
            Row(Modifier.fillMaxWidth().padding(top = Spacing.m), horizontalArrangement = Arrangement.End) {
                CapsuleButton(
                    strings.noteSave,
                    onClick = { onSave(note.text.toString()) },
                    modifier = Modifier.testTag(NOTE_SAVE_TAG),
                    style = CapsuleStyle.Filled,
                )
            }
        }
    }
}

const val NOTE_FIELD_TAG = "incall-note-field"
const val NOTE_SAVE_TAG = "incall-note-save"
private const val NOTE_MIN_LINES = 3
private const val NOTE_MAX_LINES = 6

/**
 * Collects the model's effects once for the whole app: notices and pills go through the [overlays], haptics through the
 * design system's, the note sheet and the share sheet through [sheets], and [onLeave] closes the screen when the ended
 * summary has been shown.
 */
@Composable
fun InCallEffects(
    model: InCallViewModel,
    sheets: InCallSheets,
    overlays: OverlayController,
    strings: InCallStrings,
    onLeave: () -> Unit,
) {
    val haptics = LocalRovstiHaptics.current
    val leave by rememberUpdatedState(onLeave)
    LaunchedEffect(model, overlays) {
        model.effect.collect { effect ->
            when (effect) {
                is InCallEffect.Notice -> overlays.showToast(strings.say(effect.notice))
                is InCallEffect.ShowNote -> sheets.note(effect)
                is InCallEffect.Share -> sheets.share(effect.text)
                is InCallEffect.PostCall -> sheets.postCall(effect)
                InCallEffect.Leave -> leave()
                InCallEffect.Confirm -> haptics.confirm()
                InCallEffect.Reject -> haptics.reject()
            }
        }
    }
}
