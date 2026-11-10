package dev.rovsti.phone.keypad

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.phone.FieldAction
import dev.rovsti.core.model.phone.FieldMenuItem
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.design.ios.component.IosContextMenuEntry
import dev.rovsti.phone.HostedPhone
import dev.rovsti.phone.KeypadTab
import dev.rovsti.phone.PhoneHarness
import dev.rovsti.phone.PhoneSample
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.2 screenshots: the Keypad on the real shell (tab bar included), its menus and sheets, light and dark. */
@Config(sdk = [35])
class E52ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val strings get() = KeypadStrings(ApplicationProvider.getApplicationContext<Context>().resources)

    private val ayse = T9Item("1:0", 1, "Ayse Yilmaz", "+90 555 111 22 33", 0..3)
    private val aysel = T9Item("4:0", 4, "Aysel Demir", "+90 555 444 11 22", 0..3)
    private val aybars = T9Item("5:0", 5, "Aybars Cetin", "+90 555 666 77 88", 0..3)
    private val ayten = T9Item("6:0", 6, "Ayten Kurt", "+90 555 123 45 67", 0..3)
    private val aylin = T9Item("7:0", 7, "Aylin Sahin", "+90 555 765 43 21", 0..3)

    private val simA = SimAccount("tel/svc#1", "Turkcell", "Turkcell", "Turkcell", 0xFF0A84FF.toInt(), 0, true)
    private val simB = SimAccount("tel/svc#2", "Vodafone", "Vodafone", "Vodafone", 0xFFFF453A.toInt(), 1, true)

    @Composable
    private fun Keypad(
        ui: KeypadUi,
        field: TextFieldState,
        onFieldMenu: (Rect) -> Unit = {},
        onCallMenu: (Rect) -> Unit = {},
    ) {
        KeypadContent(
            ui = ui,
            field = field,
            strings = strings,
            onAction = {},
            onTone = {},
            onMoreMatches = {},
            onAddContact = {},
            onFieldMenu = onFieldMenu,
            onCallMenu = onCallMenu,
            onSettings = {},
        )
    }

    private fun typed(text: String) = TextFieldState(text, TextRange(text.length))

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        ui: KeypadUi = KeypadUi(),
        field: () -> TextFieldState = { TextFieldState() },
        after: (PhoneHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val harness = PhoneHarness()
            val state = field()
            composeFrame(dark = dark, fontScale = fontScale) {
                HostedPhone(
                    harness,
                    PhoneSample.setup(roleHeld = true),
                    tab = KeypadTab,
                    keypad = { Keypad(ui, state) },
                )
            }
            advance(500)
            after(harness)
            advance(1_400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun bounds(tag: String): Rect {
        var rect = Rect.Zero
        val node: SemanticsNodeInteraction = compose.onNodeWithTag(tag)
        compose.runOnUiThread { rect = node.fetchSemanticsNode().boundsInWindow }
        return rect
    }

    @Test fun empty() = snap("e5.2-keypad-empty")

    @Test fun typingWithSuggestions() {
        val ui = KeypadUi(dialed = "297", t9 = persistentListOf(ayse, aysel, aybars, ayten, aylin))
        snap("e5.2-keypad-typing-t9", ui = ui, field = { typed("297") })
    }

    @Test fun unknownNumberOffersAdd() {
        val ui = KeypadUi(dialed = "+905550000000", unknown = true)
        snap("e5.2-keypad-add-contact", ui = ui, field = { typed("+905550000000") })
    }

    @Test fun fieldMenu() {
        val ui = KeypadUi(dialed = "5551234567")
        snap("e5.2-keypad-field-menu", ui = ui, field = { typed("5551234567") }, after = { h ->
            val entries =
                persistentListOf(
                    FieldEntry.Core(FieldMenuItem(FieldAction.Copy, "Copy", "Copied")),
                    FieldEntry.Core(FieldMenuItem(FieldAction.Paste, "Paste", null)),
                    FieldEntry.Core(FieldMenuItem(FieldAction.Clear, "Clear", "Cleared")),
                    FieldEntry.Share,
                    FieldEntry.E164("+905551234567"),
                )
            val rect = bounds(KeypadTags.Field)
            compose.runOnUiThread {
                h.overlays.present(IosContextMenuEntry(rect, fieldMenuSections(strings, entries) {}))
            }
        })
    }

    @Test fun selection() {
        val ui = KeypadUi(dialed = "5551234567")
        for (dark in listOf(false, true)) {
            val harness = PhoneHarness()
            val state = typed("5551234567")
            composeFrame(dark = dark) {
                HostedPhone(
                    harness,
                    PhoneSample.setup(roleHeld = true),
                    tab = KeypadTab,
                    keypad = { Keypad(ui, state) },
                )
            }
            advance(500)
            compose.onNodeWithTag(KeypadTags.Field).performTouchInput { click(center) }
            advance(300)
            compose.runOnUiThread { state.edit { selection = TextRange(3, 8) } }
            advance(500)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e5.2-keypad-selection-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun callLongPressMenu() {
        val ui = KeypadUi(dialed = "5551234567", sims = persistentListOf(simA, simB))
        snap("e5.2-call-longpress-menu", ui = ui, field = { typed("5551234567") }, after = { h ->
            val rect = bounds(KeypadTags.Call)
            val menu = KeypadEffect.CallMenu(persistentListOf(simA, simB), "5551234567")
            compose.runOnUiThread {
                h.overlays.present(IosContextMenuEntry(rect, callMenuSections(strings, menu) {}))
            }
        })
    }

    @Test fun smartPasteChooser() {
        val ui = KeypadUi()
        snap("e5.2-smart-paste-chooser", ui = ui, after = { h ->
            val choose =
                KeypadEffect.ChoosePaste(
                    persistentListOf("0532 111 22 33", "0555 999 88 77", "+90 212 555 00 11"),
                )
            compose.runOnUiThread { h.overlays.present(pasteChooser(strings, choose) {}) }
        })
    }

    @Test fun simChooserSheet() {
        val ui = KeypadUi(dialed = "5551234567", sims = persistentListOf(simA, simB))
        snap("e5.2-sim-chooser-sheet", ui = ui, field = { typed("5551234567") }, after = { h ->
            val ask = KeypadEffect.ChooseSim("5551234567", persistentListOf(simA, simB))
            compose.runOnUiThread { h.overlays.present(simChooser(strings, ask) {}) }
        })
    }

    @Test fun largeFont() {
        val ui = KeypadUi(dialed = "+905551112233", t9 = persistentListOf(ayse, aysel))
        snap("e5.2-keypad-fs2", fontScale = 2f, ui = ui, field = { typed("+905551112233") })
    }

    @Test fun smallScreen() {
        val ui = KeypadUi(dialed = "297", t9 = persistentListOf(ayse, aysel, aybars))
        for (dark in listOf(false, true)) {
            val harness = PhoneHarness()
            val state = typed("297")
            composeFrame(dark = dark) {
                Box(Modifier.size(360.dp, 640.dp).testTag("small-screen")) {
                    HostedPhone(
                        harness,
                        PhoneSample.setup(roleHeld = true),
                        tab = KeypadTab,
                        keypad = { Keypad(ui, state) },
                    )
                }
            }
            advance(800)
            compose.onNodeWithTag("small-screen").captureRoboImage(
                filePath = "screenshots/e5.2-keypad-small-screen-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }
}
