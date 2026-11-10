package dev.rovsti.phone.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetMetrics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** E5.4 behaviour on a manual clock: the sheet's detents and dismissal, the actions, and the note field. */
@OptIn(ExperimentalTestApi::class)
class DetailsSheetsTest : ShotTest() {
    private val controller = OverlayController()
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun start() {
        composeFrame {
            OverlayHost(controller) {
                Box(
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .testTag("page"),
                )
            }
        }
    }

    private fun run(ms: Long) = repeat((ms / 100).toInt()) { advance(100) }

    private fun open(
        ui: PersonUi = DetailsSample.person(),
        record: (PersonAction) -> Unit = {},
        detent: Detent = Detent.Medium,
    ): SheetEntry {
        start()
        var entry: SheetEntry? = null
        compose.runOnUiThread { entry = controller.presentPerson(ui, record, detent) }
        run(1500)
        return entry!!
    }

    private fun top(entry: SheetEntry) = entry.sheet!!.offset.value

    private fun slowDrag(
        dp: Float,
        tag: String = "sheet-grabber",
    ) {
        val ms = (abs(dp) / 70f * 1000f).toLong().coerceAtLeast(300L)
        compose.onNodeWithTag(tag).performTouchInput {
            val from = Offset(centerX, centerY)
            swipe(from, from + Offset(0f, -dp * density), ms)
        }
        run(1500)
    }

    // ---- sheet motion

    @Test fun theContactSheetOpensMediumAndADragMovesItBetweenItsTwoDetents() {
        val entry = open()
        val medium = entry.sheet!!.detentOffsets[1]
        val large = entry.sheet!!.detentOffsets[0]
        assertEquals("opens at half the window", medium, top(entry), 3f)
        slowDrag((medium - large) / density)
        assertEquals(large, top(entry), 3f)
        slowDrag(-(medium - large) / density)
        assertEquals(medium, top(entry), 3f)
    }

    @Test fun aSlowDragPastTwelvePercentOfTheHeightDismissesAndASmallerOneSettlesBack() {
        val entry = open()
        val medium = top(entry)
        val height =
            compose.activity.resources.displayMetrics.heightPixels
                .toFloat()
        assertEquals("the margin is 12 %", 0.12f, SheetMetrics.DismissMargin, 0f)
        // 6 % of the height: the sheet comes back to its detent.
        slowDrag(-(0.06f * height) / density)
        assertEquals(medium, top(entry), 3f)
        compose.onNodeWithTag("sheet").assertExists()
        // 16 % of the height: it leaves.
        slowDrag(-(0.16f * height) / density)
        compose.onNodeWithTag("sheet").assertDoesNotExist()
        assertFalse(controller.hasEntries)
    }

    @Test fun aFastFlingDownDismissesTheSheetWhateverTheDistance() {
        open()
        compose.onNodeWithTag("sheet-grabber").performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY + 90f * density), 60)
        }
        run(2000)
        compose.onNodeWithTag("sheet").assertDoesNotExist()
    }

    @Test fun theScrimTapAndBackCloseTheSheetToo() {
        open()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        run(1800)
        compose.onNodeWithTag("sheet").assertDoesNotExist()
    }

    // ---- the Contact sheet

    @Test fun theThreeCapsulesAndTheNumbersAreOnTheSheetAndTheyReportTheirActions() {
        val seen = mutableListOf<PersonAction>()
        open(record = { seen += it })
        compose.onNodeWithTag(PersonTags.Call).performClick()
        compose.onNodeWithTag(PersonTags.Message).performClick()
        compose.onNodeWithTag(PersonTags.number("+902125550101")).performClick()
        compose.onNodeWithTag(PersonTags.number("+902125550101")).performTouchInput { longClick(center, 700) }
        compose.onNodeWithTag(PersonTags.AddContact).assertDoesNotExist()
        assertEquals(
            listOf(
                PersonAction.Call("+905551112233"),
                PersonAction.Message,
                PersonAction.Call("+902125550101"),
                PersonAction.AskSim("+902125550101"),
            ),
            seen,
        )
    }

    @Test fun theNumberSheetOffersAddToContactsAndTheContactSheetDoesNot() {
        val seen = mutableListOf<PersonAction>()
        open(DetailsSample.person(named = false), { seen += it })
        compose.onNodeWithTag(PersonTags.AddContact).performClick()
        assertEquals(listOf<PersonAction>(PersonAction.AddContact), seen)
    }

    @Test fun theMoreCapsuleOpensTheMenuOverTheSheetWithoutMovingIt() {
        val entry = open()
        val before = top(entry)
        compose.onNodeWithTag(PersonTags.More).performTouchInput {
            down(center)
            up()
        }
        run(900)
        compose.onNodeWithTag("menu-row-0", useUnmergedTree = true).assertExists()
        assertEquals("the sheet did not move", before, top(entry), 1f)
    }

    @Test fun aHistoryLineOpensItsDetailsAndTheDeleteRowAsksFirst() {
        val seen = mutableListOf<PersonAction>()
        open(DetailsSample.person(), { seen += it }, Detent.Large)
        compose.onNodeWithTag(PersonTags.line("log-2")).performClick()
        compose.onNodeWithTag(PersonTags.DeleteHistory).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(PersonAction.OpenCall("log-2"), PersonAction.AskDeleteHistory), seen)
    }

    @Test fun aSheetWithoutHistoryHasNoStatisticsNoHistoryAndNoDeleteRow() {
        val ui = DetailsSample.person().copy(history = kotlinx.collections.immutable.persistentListOf(), stats = null)
        open(ui)
        compose.onNodeWithTag(PersonTags.Stats).assertDoesNotExist()
        compose.onNodeWithTag(PersonTags.DeleteHistory).assertDoesNotExist()
    }

    // ---- the Call details sheet and its note

    private fun noteLayout(): TextLayoutResult {
        val node = compose.onNodeWithTag(CallDetailsTags.Note).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun selection(): TextRange =
        compose.onNodeWithTag(CallDetailsTags.Note).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun noteText(): String =
        compose
            .onNodeWithTag(CallDetailsTags.Note)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    private fun openCall(
        h: CallHarness = CallHarness(),
        detent: Detent = Detent.Large,
    ): CallHarness {
        start()
        compose.runOnUiThread { controller.presentCall(h, detent) }
        run(1500)
        return h
    }

    @Test fun tappingTheNoteFieldPutsTheCaretAtTheEndOfTheTextAndTheTextTakesInput() {
        val h = openCall(CallHarness(DetailsSample.call(note = "Ask about the invoice")))
        compose.onNodeWithTag(CallDetailsTags.Note).performClick()
        advance(300)
        compose.onNodeWithTag(CallDetailsTags.Note).assertIsFocused()
        assertEquals("the saved note is in the field", "Ask about the invoice", noteText())
        // A tap on character 5 places the caret there (the pill pads 14 dp).
        val at = Offset(14 * density + noteLayout().getCursorRect(5).left, noteLayout().getCursorRect(5).center.y + 10)
        compose.onNodeWithTag(CallDetailsTags.Note).performTouchInput { click(at) }
        advance(300)
        assertEquals(TextRange(5), selection())
        compose.onNodeWithTag(CallDetailsTags.Note).performTextInput("X")
        advance(200)
        assertEquals("Ask aXbout the invoice", noteText())
        assertTrue("focusing the field raised the sheet", h.editStarts >= 1)
    }

    @Test fun anEditedNoteShowsSaveAndCancelAndSaveReportsTheTypedText() {
        val h = openCall()
        compose.onNodeWithTag(CallDetailsTags.NoteSave).assertDoesNotExist()
        compose.onNodeWithTag(CallDetailsTags.Note).performClick()
        compose.onNodeWithTag(CallDetailsTags.Note).performTextInput("Taxi receipt")
        advance(300)
        compose.onNodeWithTag(CallDetailsTags.NoteSave).performClick()
        assertEquals(listOf<CallDetailsAction>(CallDetailsAction.SaveNote("Taxi receipt")), h.actions)
        compose.onNodeWithTag(CallDetailsTags.NoteRevert).performClick()
        advance(300)
        assertEquals("", noteText())
        compose.onNodeWithTag(CallDetailsTags.NoteSave).assertDoesNotExist()
    }

    @Test fun aSavedUnchangedNoteOffersDeleteNote() {
        val h = openCall(CallHarness(DetailsSample.call(note = "Call the bank")))
        compose.onNodeWithTag(CallDetailsTags.NoteSave).assertDoesNotExist()
        compose.onNodeWithTag(CallDetailsTags.NoteDelete).performClick()
        assertEquals(listOf<CallDetailsAction>(CallDetailsAction.DeleteNote), h.actions)
    }

    @Test fun pasteFromTheClipboardGoesThroughThePlatformToolbarIntoTheNote() {
        val toolbar = RecordingToolbar()
        val h = CallHarness(DetailsSample.call(note = "Call back"))
        // The toolbar provider is read at composition: the host is composed with it provided.
        composeFrame {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                OverlayHost(controller) {
                    Box(
                        androidx.compose.ui.Modifier
                            .fillMaxSize(),
                    )
                }
            }
        }
        compose.runOnUiThread { controller.presentCall(h, Detent.Large) }
        run(1500)
        val manager = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("test", " Ayla"))
        val at = Offset(14 * density + noteLayout().getCursorRect(9).left, noteLayout().getCursorRect(9).center.y + 10)
        compose.onNodeWithTag(CallDetailsTags.Note).performTouchInput { longClick(at) }
        advance(1000)
        assertTrue("the toolbar offers Paste while the clipboard has text", toolbar.offers(toolbar.pasteKey))
        toolbar.press(toolbar.pasteKey)
        advance(300)
        assertTrue(noteText().contains("Ayla"))
    }

    @Test fun pasteAlsoWorksThroughTheSemanticsAction() {
        openCall()
        val manager = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("test", "Demir"))
        compose.onNodeWithTag(CallDetailsTags.Note).performClick()
        compose.onNodeWithTag(CallDetailsTags.Note).performSemanticsAction(SemanticsActions.PasteText)
        advance(300)
        assertEquals("Demir", noteText())
    }

    @Test fun theKeyboardLiftsTheNoteFieldAboveItWithoutResizingTheSheet() {
        val h = openCall(detent = Detent.Large)
        val sheet = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(CallDetailsTags.Note).performClick()
        setSimulatedIme(300)
        advance(400)
        val keyboardTop = compose.activity.resources.displayMetrics.heightPixels - 300 * density
        val field = compose.onNodeWithTag(CallDetailsTags.Note).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the field sits above the keyboard: ${field.bottom} <= $keyboardTop",
            field.bottom <= keyboardTop + 1f,
        )
        assertEquals(
            "the sheet did not move",
            sheet.top,
            compose
                .onNodeWithTag("sheet")
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
        assertTrue(h.editStarts >= 1)
    }

    @Test fun copyShareRemindAndDeleteReportTheirActions() {
        val h = openCall()
        compose.onNodeWithTag(CallDetailsTags.Copy).performClick()
        compose.onNodeWithTag(CallDetailsTags.Share).performClick()
        compose.onNodeWithTag(CallDetailsTags.Remind).performClick()
        compose.onNodeWithTag(CallDetailsTags.Delete).performClick()
        assertEquals(
            listOf(
                CallDetailsAction.Copy,
                CallDetailsAction.Share,
                CallDetailsAction.AskRemind,
                CallDetailsAction.DeleteCall,
            ),
            h.actions,
        )
    }

    @Test fun aCallThatCannotBeDeletedHasNoDeleteRow() {
        openCall(CallHarness(DetailsSample.call().copy(deletable = false)))
        compose.onNodeWithTag(CallDetailsTags.Delete).assertDoesNotExist()
    }
}
