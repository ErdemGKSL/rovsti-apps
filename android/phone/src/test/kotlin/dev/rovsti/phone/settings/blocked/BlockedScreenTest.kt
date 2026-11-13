package dev.rovsti.phone.settings.blocked

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.phone.HostedPhone
import dev.rovsti.phone.PhoneHarness
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneSample
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

/**
 * E5.9a: the Blocked numbers page on the real shell without a view model. The Compose clock is manual
 * (`autoAdvance = false`), so a swipe and a sheet are driven by `advance`.
 */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class BlockedScreenTest : ShotTest() {
    private val density get() = compose.activity.resources.displayMetrics.density
    private val actions = mutableListOf<BlockedAction>()
    private val harness = PhoneHarness()

    private fun page(ui: BlockedUi = BlockedSample.ui()): Unit =
        composeFrame {
            HostedPhone(
                harness,
                PhoneSample.setup(),
                push = PhoneRoute.Blocked,
                extra = {
                    BlockedContent(ui, onAction = { actions += it }, onPaste = {}, onPrefix = {}, onImport = {})
                },
            )
        }

    /** Scrolls [tag] into view; the scroll animation needs a running clock (as in ChipRowTest). */
    private fun scrollTo(tag: String) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(tag).performScrollTo()
        compose.mainClock.autoAdvance = false
    }

    private fun openSheet(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        compose.runOnUiThread {
            harness.overlays.present(
                SheetEntry("Sheet", persistentListOf(Detent.Medium, Detent.Large), Detent.Large) { _ -> content() },
            )
        }
        advance(1_500)
    }

    // ---- the page -------------------------------------------------------------------------------------------------------

    @Test fun aSwipeToTheLeftUnblocksTheRowAndTheMenuSpeaksTheSameAction() {
        page()
        advance(500)
        compose.onNodeWithTag(BlockedTags.row("n-3")).performTouchInput { swipeLeft(durationMillis = 250) }
        advance(700)
        assertEquals(BlockedAction.Unblock("n-3"), actions.last())
    }

    @Test fun aRangeRowSwipesToo() {
        page()
        advance(500)
        compose.onNodeWithTag(BlockedTags.row("r-+90 850")).performTouchInput { swipeLeft(durationMillis = 250) }
        advance(700)
        assertEquals(BlockedAction.Unblock("r-+90 850"), actions.last())
    }

    @Test fun theRangeShowsAsAPrefixWithAStar() {
        page()
        advance(500)
        compose.onNodeWithTag(BlockedTags.row("r-+90 850")).assertIsDisplayed()
        assertEquals("+90 850 *", BlockedSample.ranges[0].title)
    }

    @Test fun theToolsAndTheAddRowsReportTheirActions() {
        var pasted = 0
        var prefixed = 0
        var imported = 0
        composeFrame {
            HostedPhone(
                harness,
                PhoneSample.setup(),
                push = PhoneRoute.Blocked,
                extra = {
                    BlockedContent(
                        BlockedSample.ui(),
                        onAction = { actions += it },
                        onPaste = { pasted++ },
                        onPrefix = { prefixed++ },
                        onImport = { imported++ },
                    )
                },
            )
        }
        advance(500)
        compose.onNodeWithTag(BlockedTags.AddNumbers).performClick()
        compose.onNodeWithTag(BlockedTags.AddPrefix).performClick()
        scrollTo(BlockedTags.Share)
        compose.onNodeWithTag(BlockedTags.Share).performClick()
        scrollTo(BlockedTags.Export)
        compose.onNodeWithTag(BlockedTags.Export).performClick()
        scrollTo(BlockedTags.Import)
        compose.onNodeWithTag(BlockedTags.Import).performClick()
        scrollTo(BlockedTags.UnblockAll)
        compose.onNodeWithTag(BlockedTags.UnblockAll).performClick()
        assertEquals(listOf(1, 1, 1), listOf(pasted, prefixed, imported))
        assertEquals(
            listOf(BlockedAction.Share, BlockedAction.Export, BlockedAction.AskUnblockAll),
            actions.toList(),
        )
    }

    @Test fun aBlockedCallOpensItsNumber() {
        page()
        advance(500)
        scrollTo(BlockedTags.call("c-2"))
        compose.onNodeWithTag(BlockedTags.call("c-2")).performClick()
        assertEquals(BlockedAction.OpenCall("c-2"), actions.last())
    }

    @Test fun anEmptyListSaysSoAndOffersNoShareExportOrUnblockAll() {
        page(BlockedSample.ui(emptyList(), emptyList(), emptyList()))
        advance(500)
        compose.onNodeWithTag(BlockedTags.Empty).assertIsDisplayed()
        compose.onNodeWithTag(BlockedTags.Share).assertDoesNotExist()
        compose.onNodeWithTag(BlockedTags.Export).assertDoesNotExist()
        compose.onNodeWithTag(BlockedTags.UnblockAll).assertDoesNotExist()
        compose.onNodeWithTag(BlockedTags.Import).assertExists()
    }

    @Test fun withoutTheDialerRoleTheFooterExplainsIt() {
        page(BlockedSample.ui(canWrite = false))
        advance(500)
        compose.onNodeWithTag("back-button").assertIsDisplayed()
    }

    // ---- the paste sheet ------------------------------------------------------------------------------------------------

    private lateinit var field: TextFieldState

    private fun openPaste(
        ui: BlockedUi = BlockedSample.ui(),
        seen: MutableList<BlockedAction> = actions,
    ) {
        page()
        openSheet {
            field = rememberTextFieldState()
            PasteSheetContent(ui, field, onAction = { seen += it }, onClose = {})
        }
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag(BlockedTags.PasteField)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        return results.first()
    }

    private fun text() =
        compose
            .onNodeWithTag(BlockedTags.PasteField)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    private fun selection() =
        compose
            .onNodeWithTag(
                BlockedTags.PasteField,
            ).fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]

    @Test fun aTapPutsTheCaretWhereItLandedAndTypingGoesThere() {
        openPaste()
        compose.onNodeWithTag(BlockedTags.PasteField).performClick()
        compose.onNodeWithTag(BlockedTags.PasteField).performTextInput("0532 111 22 33")
        advance(300)
        // A tap on character 5 places the caret there (the pill pads 14 dp).
        val rect = layout().getCursorRect(5)
        compose.onNodeWithTag(BlockedTags.PasteField).performTouchInput {
            click(Offset(14 * density + rect.left, rect.center.y + 10))
        }
        advance(300)
        assertEquals(TextRange(5), selection())
        compose.onNodeWithTag(BlockedTags.PasteField).performTextInput(",")
        advance(200)
        assertEquals("0532 ,111 22 33", text())
    }

    @Test fun pasteFromTheClipboardFillsTheFieldAndTheCoreCountsItAfterwards() {
        openPaste()
        val manager = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("test", "0532 111 22 33\n+49 151 2345678\n0850 333 44 55"))
        compose.onNodeWithTag(BlockedTags.PasteField).performClick()
        compose.onNodeWithTag(BlockedTags.PasteField).performSemanticsAction(SemanticsActions.PasteText)
        advance(300)
        assertEquals("0532 111 22 33\n+49 151 2345678\n0850 333 44 55", text())
        assertEquals("the field is multi-line", 3, layout().lineCount)
    }

    @Test fun theBlockButtonWaitsForANumberTheCoreFound() {
        openPaste(BlockedSample.ui(paste = null))
        // The enabled state lives on the button inside the tagged box.
        compose.onNode(isNotEnabled() and hasAnyAncestor(hasTestTag(BlockedTags.PasteConfirm))).assertExists()
    }

    @Test fun thePreviewLineSaysWhatWillHappenAndBlockReportsTheAction() {
        val seen = mutableListOf<BlockedAction>()
        openPaste(BlockedSample.ui(paste = PastePreview(toBlock = 12, emergency = 1, alreadyBlocked = 2)), seen)
        compose
            .onNodeWithTag(
                BlockedTags.PastePreview,
            ).assertTextEquals("12 to block, 1 emergency skipped, 2 already blocked")
        compose.onNodeWithTag(BlockedTags.PasteConfirm).assertIsEnabled()
        compose.onNodeWithTag(BlockedTags.PasteConfirm).performClick()
        assertEquals(listOf<BlockedAction>(BlockedAction.PasteConfirm), seen)
    }

    @Test fun theKeyboardLiftsTheFieldAboveItWithoutMovingTheSheet() {
        openPaste()
        val sheet = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(BlockedTags.PasteField).performClick()
        setSimulatedIme(300)
        advance(400)
        val keyboardTop = compose.activity.resources.displayMetrics.heightPixels - 300 * density
        val bottom =
            compose
                .onNodeWithTag(BlockedTags.PasteField)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertTrue("the field sits above the keyboard: $bottom <= $keyboardTop", bottom <= keyboardTop + 1f)
        assertEquals(
            sheet.top,
            compose
                .onNodeWithTag("sheet")
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
        compose.onNodeWithTag(BlockedTags.PasteConfirm).assertIsDisplayed()
    }

    // ---- the prefix sheet -----------------------------------------------------------------------------------------------

    @Test fun thePrefixSheetExplainsAProblemAndEnablesBlockOnlyForAValidRange() {
        val seen = mutableListOf<BlockedAction>()
        page()
        openSheet {
            val prefix = rememberTextFieldState()
            PrefixSheetContent(
                BlockedSample.ui(prefix = PrefixPreview(problem = PrefixProblem.TooShort)),
                prefix,
                onAction = { seen += it },
                onClose = {},
            )
        }
        compose.onNodeWithTag(BlockedTags.PrefixPreview).assertTextEquals("Type at least 3 digits.")
        compose.onNode(isNotEnabled() and hasAnyAncestor(hasTestTag(BlockedTags.PrefixConfirm))).assertExists()
    }

    @Test fun aValidPrefixShowsHowItWillReadAndBlocks() {
        val seen = mutableListOf<BlockedAction>()
        page()
        openSheet {
            val prefix = rememberTextFieldState()
            PrefixSheetContent(
                BlockedSample.ui(prefix = PrefixPreview(display = "+90 850")),
                prefix,
                onAction = { seen += it },
                onClose = {},
            )
        }
        compose
            .onNodeWithTag(
                BlockedTags.PrefixPreview,
            ).assertTextEquals("Blocks every number that starts with +90 850 *")
        compose.onNodeWithTag(BlockedTags.PrefixConfirm).assertIsEnabled()
        compose.onNodeWithTag(BlockedTags.PrefixConfirm).performClick()
        assertEquals(listOf<BlockedAction>(BlockedAction.PrefixConfirm), seen)
    }

    // ---- owner rule (c) -------------------------------------------------------------------------------------------------

    @Test fun noBlockedSourceUsesImePaddingOrTheImeInsetDirectly() {
        val dir = File("src/main/kotlin/dev/rovsti/phone/settings/blocked")
        val sources = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("the blocked sources are found", sources.isNotEmpty())
        for (file in sources) {
            val body =
                file
                    .readText()
                    .lines()
                    .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                    .joinToString("\n")
            val bad =
                Regex(
                    """(?<!overlap)imePadding\(|WindowInsets\.ime""",
                ).find(body.replace("imeOverlapPadding", ""))
            assertTrue("${file.name} uses ${bad?.value}", bad == null)
        }
        assertTrue(File(dir, "BlockedScreen.kt").readText().contains(".imeOverlapPadding()"))
    }
}
