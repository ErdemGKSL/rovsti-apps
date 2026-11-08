package dev.rovsti.catalog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.16 behaviour: caret, selection, paste, IME action, clear, keyboard avoidance, font scale and the composer. */
@OptIn(ExperimentalTestApi::class)
class TextFieldsTest : ShotTest() {
    private val density get() = compose.activity.resources.displayMetrics.density

    private fun show() = composeFrame { CatalogApp(initialPage = CatalogPage.TextFields) }

    private fun layoutOf(tag: String): TextLayoutResult {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun selectionOf(tag: String): TextRange =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    private fun heightOf(tag: String): Int =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .size.height

    /** The point inside field [tag] where the caret for character [offset] would sit (the pill pads 14 dp). */
    private fun pointAt(
        tag: String,
        offset: Int,
    ): Offset {
        val layout = layoutOf(tag)
        val box = compose.onNodeWithTag(tag).fetchSemanticsNode().size
        return Offset(14 * density + layout.getCursorRect(offset).left, box.height / 2f)
    }

    @Test
    fun tapAtCharacterFivePlacesTheCaretThere() {
        show()
        val at = pointAt("field-note", 5)
        compose.onNodeWithTag("field-note").performTouchInput { click(at) }
        advance(300)
        assertEquals(TextRange(5), selectionOf("field-note"))
        compose.onNodeWithTag("field-note").assertIsFocused()
    }

    @Test
    fun longPressSelectsTheWord() {
        show()
        val at = pointAt("field-note", 7)
        compose.onNodeWithTag("field-note").performTouchInput { longClick(at) }
        advance(500)
        assertEquals(TextRange(5, 9), selectionOf("field-note"))
    }

    @Test
    fun selectionCanBeSetThroughSemantics() {
        show()
        compose.onNodeWithTag("field-note").performClick()
        compose.onNodeWithTag("field-note").performTextInputSelection(TextRange(0, 4))
        advance(200)
        assertEquals(TextRange(0, 4), selectionOf("field-note"))
    }

    private fun putOnClipboard(text: String) {
        val manager = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("test", text))
    }

    @Test
    fun pasteFromTheClipboardThroughThePlatformToolbar() {
        val toolbar = RecordingToolbar()
        composeFrame {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                CatalogApp(initialPage = CatalogPage.TextFields)
            }
        }
        putOnClipboard("Ayla")
        val at = pointAt("field-note", 7)
        compose.onNodeWithTag("field-note").performTouchInput { longClick(at) }
        advance(1000)
        assertTrue("the toolbar offers Paste while the clipboard has text", toolbar.offers(toolbar.pasteKey))
        toolbar.press(toolbar.pasteKey)
        advance(300)
        val shown = compose.onNodeWithTag("field-note").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("Call Ayla after the meeting", shown.text)
    }

    @Test
    fun pasteThroughTheSemanticsAction() {
        show()
        putOnClipboard("Demir")
        compose.onNodeWithTag("field-empty").performClick()
        compose.onNodeWithTag("field-empty").performSemanticsAction(SemanticsActions.PasteText)
        advance(300)
        val shown = compose.onNodeWithTag("field-empty").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("Demir", shown.text)
    }

    @Test
    fun theImeActionSubmitsOnce() {
        show()
        compose.onNodeWithTag("field-empty").performClick()
        compose.onNodeWithTag("field-empty").performImeAction()
        advance(200)
        compose.onNodeWithTag("field-submits").assertTextEquals("Submitted 1 times")
    }

    @Test
    fun clearEmptiesTheFieldAndKeepsFocus() {
        show()
        compose.onNodeWithTag("field-search").performClick()
        advance(200)
        compose.onNodeWithContentDescription("Clear").performClick()
        advance(300)
        val text = compose.onNodeWithTag("field-search").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("", text.text)
        compose.onNodeWithTag("field-search").assertIsFocused()
        compose.onNodeWithContentDescription("Clear").assertDoesNotExist()
    }

    @Test
    fun theComposerStaysAboveTheKeyboard() {
        show()
        val rootHeight =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .size.height
        val before =
            compose
                .onNodeWithTag("composer-field")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertTrue("composer sits above the nav bar", before <= rootHeight)
        setSimulatedIme(300)
        compose.onNodeWithTag("composer-field").performClick()
        advance(400)
        val after =
            compose
                .onNodeWithTag("composer-field")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertTrue(
            "composer bottom $after must clear the keyboard at ${rootHeight - 300 * density}",
            after <= rootHeight - 300 * density + 1,
        )
    }

    @Test
    fun noClippingAtFontScale2() {
        composeFrame(fontScale = 2f) { CatalogApp(initialPage = CatalogPage.TextFields) }
        for (tag in listOf("field-empty", "field-note", "field-search")) {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val text = layoutOf(tag).size.height
            assertTrue(
                "$tag is ${node.size.height} px for $text px of text",
                node.size.height >= text + 2 * 8 * density,
            )
            assertTrue(
                node.boundsInRoot.left >= 0f &&
                    node.boundsInRoot.right <=
                    compose
                        .onRoot()
                        .fetchSemanticsNode()
                        .size.width,
            )
        }
    }

    @Test
    fun theComposerGrowsToSixLinesThenScrolls() {
        show()
        val one = heightOf("composer-field")
        compose.onNodeWithTag("composer-field").performTextInput("a\nb\nc")
        advance(300)
        val three = heightOf("composer-field")
        compose.onNodeWithTag("composer-field").performTextInput("\nd\ne\nf")
        advance(300)
        val six = heightOf("composer-field")
        compose.onNodeWithTag("composer-field").performTextInput("\ng\nh\ni\nj")
        advance(300)
        val ten = heightOf("composer-field")
        assertTrue("1 line $one < 3 lines $three", one < three)
        assertTrue("3 lines $three < 6 lines $six", three < six)
        assertEquals("ten lines scroll inside the six-line height", six, ten)
    }

    @Test
    fun sendButtonSendsOnceAndKeepsFocus() {
        show()
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").performTextInput("On my way")
        advance(500)
        compose.onNodeWithContentDescription("Send").performClick()
        advance(300)
        compose.onNodeWithTag("composer-sent").assertTextEquals("Sent: On my way")
        compose.onNodeWithTag("composer-field").assertIsFocused()
        val text = compose.onNodeWithTag("composer-field").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("", text.text)
    }

    @Test
    fun hardwareEnterSendsAndShiftEnterInsertsANewline() {
        show()
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").performTextInput("one")
        compose.onNodeWithTag("composer-field").performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }
        advance(200)
        compose.onNodeWithTag("composer-sent").assertTextEquals("Nothing sent yet")
        compose.onNodeWithTag("composer-field").performTextInput("two")
        compose.onNodeWithTag("composer-field").performKeyInput { pressKey(Key.Enter) }
        advance(300)
        compose.onNodeWithTag("composer-sent").assertTextEquals("Sent: one\ntwo")
    }

    @Test
    fun blankComposerSendsNothing() {
        show()
        compose.onNodeWithTag("composer-field").performClick()
        compose.onNodeWithTag("composer-field").performTextInput("   ")
        compose.onNodeWithTag("composer-field").performKeyInput { pressKey(Key.Enter) }
        advance(300)
        compose.onNodeWithTag("composer-sent").assertTextEquals("Nothing sent yet")
    }

    @Test
    fun longPressAsksThePlatformForItsToolbar() {
        val toolbar = RecordingToolbar()
        composeFrame {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                CatalogApp(initialPage = CatalogPage.TextFields)
            }
        }
        val at = pointAt("field-note", 7)
        compose.onNodeWithTag("field-note").performTouchInput { longClick(at) }
        advance(1000)
        assertTrue("the platform toolbar was requested", toolbar.shown > 0)
        assertTrue("copy is offered for a selection", toolbar.offers(toolbar.copyKey))
    }
}
