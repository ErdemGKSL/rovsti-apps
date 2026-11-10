package dev.rovsti.phone.keypad

import androidx.compose.ui.text.TextRange
import dev.rovsti.core.model.phone.FieldAction
import dev.rovsti.core.model.phone.SpeedDialEntry
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.eventually
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.2 Keypad decisions on the REAL Rust core: typing at the caret, T9, paste, redial, speed dial, SIM choice. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeypadViewModelTest {
    private val host by lazy { HostRust("rovsti-keypad") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun stop() {
        // Main stays set: the view models of this class still have coroutines that end after the test body.
        scope.cancel()
    }

    private suspend fun rig(
        accounts: List<dev.rovsti.core.model.phone.PhoneAccountInfo> = listOf(sim1),
        defaultKey: String? = null,
    ) = KeypadRig(host, scope, accounts, defaultKey).also { it.ready() }

    @Test fun digitsGoInAtTheCaretNotAtTheEnd() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.type("1256")
            model.field.edit { selection = TextRange(2) }
            model.type("34")
            assertEquals("123456", model.text())
            assertEquals(TextRange(4), model.field.selection)
            model.onAction(KeypadAction.Backspace)
            assertEquals("the character before the caret goes", "12356", model.text())
            eventually("state follows") { model.state.value.dialed == "12356" }
        }

    @Test fun theNumberCannotOutgrowTheCoresLimit() =
        runBlocking {
            val model = rig().model()
            model.type("1".repeat(40))
            assertEquals(32, model.text().length)
        }

    @Test fun t9SpellsNamesAndListsThemWithTheMatchedLetters() =
        runBlocking {
            val model = rig().model()
            model.type("2973")
            eventually("suggestions") {
                model.state.value.t9
                    .isNotEmpty()
            }
            val first =
                model.state.value.t9
                    .first()
            assertEquals("Ayse Yilmaz", first.name)
            assertEquals("a name match carries a span", true, first.span != null)
            model.onAction(KeypadAction.Backspace)
            model.onAction(KeypadAction.Backspace)
            model.onAction(KeypadAction.Backspace)
            model.onAction(KeypadAction.Backspace)
            eventually("list empties with the field") {
                model.state.value.t9
                    .isEmpty()
            }
        }

    @Test fun anUnknownNumberOffersToBeAdded() =
        runBlocking {
            val model = rig().model()
            model.type("+905550000000")
            eventually("unknown") { model.state.value.unknown }
            model.onAction(KeypadAction.Clear)
            model.type("+905551112233")
            eventually("known number is nobody new") {
                !model.state.value.unknown &&
                    model.state.value.dialed.length == 13
            }
        }

    @Test fun pauseMarksShowTheTonesTheyStandFor() =
        runBlocking {
            val model = rig().model()
            model.type("4821")
            model.onAction(KeypadAction.Digit(','))
            model.type("12")
            eventually("hint") { model.state.value.toneHint == "4821,12" }
            assertNull(
                "no marks, no hint",
                run {
                    model.onAction(KeypadAction.Clear)
                    eventually("cleared") { model.state.value.toneHint == null }
                    model.state.value.toneHint
                },
            )
        }

    @Test fun holdingZeroStarAndHashGivePlusPauseAndWait() =
        runBlocking {
            val model = rig().model()
            model.onAction(KeypadAction.Digit('0'))
            model.onAction(KeypadAction.Hold('0'))
            assertEquals("+", model.text())
            model.onAction(KeypadAction.Digit('*'))
            model.onAction(KeypadAction.Hold('*'))
            model.onAction(KeypadAction.Digit('#'))
            model.onAction(KeypadAction.Hold('#'))
            assertEquals("+,;", model.text())
        }

    @Test fun callPlacesTheTypedNumber() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.type("+905551112233")
            model.onAction(KeypadAction.Call)
            eventually("placed") { rig.launcher.placed.isNotEmpty() }
            assertEquals(
                "+905551112233",
                rig.launcher.placed
                    .single()
                    .first,
            )
        }

    @Test fun anInvalidNumberRejectsAndSaysWhy() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.onAction(KeypadAction.Digit(','))
            model.onAction(KeypadAction.Call)
            with(rig) { model.next<KeypadEffect.Reject>() }
            assertTrue(rig.launcher.placed.isEmpty())
        }

    @Test fun twoSimsWithoutADefaultAskWhichOne() =
        runBlocking {
            val rig = rig(listOf(sim1, sim2))
            val model = rig.model()
            model.type("+905551112233")
            model.onAction(KeypadAction.Call)
            val ask = with(rig) { model.next<KeypadEffect.ChooseSim>() }
            assertEquals(listOf("tel/svc#1", "tel/svc#2"), ask.choices.map { it.key })
            assertTrue("nothing was called yet", rig.launcher.placed.isEmpty())
            model.onAction(KeypadAction.CallOn(ask.number, "tel/svc#2"))
            eventually("placed on the chosen SIM") { rig.launcher.placed.isNotEmpty() }
            assertEquals("+905551112233" to "tel/svc#2", rig.launcher.placed.single())
        }

    @Test fun emptyFieldCallRedialsInsteadOfCalling() =
        runBlocking {
            val rig = rig()
            rig.outgoing("+905559998877")
            val model = rig.model()
            model.onAction(KeypadAction.Call)
            eventually("number filled") { model.text() == "+905559998877" }
            assertTrue("filling is not calling", rig.launcher.placed.isEmpty())
            model.onAction(KeypadAction.Call)
            eventually("second press calls") { rig.launcher.placed.isNotEmpty() }
        }

    @Test fun emptyFieldWithNoHistoryJustRejects() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.onAction(KeypadAction.Call)
            with(rig) { model.next<KeypadEffect.Reject>() }
            assertEquals("", model.text())
        }

    @Test fun pasteOfOneNumberInsertsItAtTheCaret() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.type("77")
            model.field.edit { selection = TextRange(1) }
            rig.clipboard.text = "Call me on 0532 111 22 33 tonight"
            model.onAction(KeypadAction.Paste)
            eventually("pasted") { model.text().length > 2 }
            assertTrue(model.text().startsWith("7") && model.text().endsWith("7"))
            assertTrue("the digits of the number are in the middle", "05321112233" in model.text())
        }

    @Test fun pasteOfSeveralNumbersAsksWhichOne() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            rig.clipboard.text = "Ayse 0532 111 22 33 or Mert 0555 999 88 77"
            model.onAction(KeypadAction.Paste)
            val choose = with(rig) { model.next<KeypadEffect.ChoosePaste>() }
            assertEquals(2, choose.numbers.size)
            model.onAction(KeypadAction.PasteChosen(choose.numbers[1]))
            assertEquals(choose.numbers[1], model.text())
        }

    @Test fun pasteOfNothingUsefulRejects() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            rig.clipboard.text = "hello world"
            model.onAction(KeypadAction.Paste)
            with(rig) { model.next<KeypadEffect.Reject>() }
            assertEquals("", model.text())
        }

    @Test fun clearOffersUndoAndUndoBringsTheNumberBack() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.type("5551234")
            model.onAction(KeypadAction.Clear)
            assertEquals("", model.text())
            val undo = with(rig) { model.next<KeypadEffect.Undo>() }
            assertEquals("5551234", undo.old)
            model.onAction(KeypadAction.Restore(undo.old))
            assertEquals("5551234", model.text())
        }

    @Test fun holdingATwoToNineKeyCallsItsSpeedDialSlot() =
        runBlocking {
            val rig = rig()
            rig.speedDial.assign(2, SpeedDialEntry("k-ayse", "+905551112233", "Ayse"))
            val model = rig.model()
            model.onAction(KeypadAction.Digit('2'))
            model.onAction(KeypadAction.Hold('2'))
            eventually("speed dial called") { rig.launcher.placed.isNotEmpty() }
            assertEquals(
                "+905551112233",
                rig.launcher.placed
                    .single()
                    .first,
            )
            assertEquals("the digit typed on touch-down was taken back", "", model.text())
        }

    @Test fun holdingAFreeKeyOffersToAssignIt() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.onAction(KeypadAction.Digit('5'))
            model.onAction(KeypadAction.Hold('5'))
            val assign = with(rig) { model.next<KeypadEffect.AssignSpeedDial>() }
            assertEquals(5, assign.digit)
            assertEquals("", model.text())
        }

    @Test fun holdingAKeyWithNumbersAlreadyTypedJustTypesIt() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.type("12")
            model.onAction(KeypadAction.Digit('5'))
            model.onAction(KeypadAction.Hold('5'))
            eventually("typed again") { model.text() == "125" }
            assertTrue(rig.launcher.placed.isEmpty())
        }

    @Test fun holdingOneCallsVoicemailOrSaysThereIsNone() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.onAction(KeypadAction.Digit('1'))
            model.onAction(KeypadAction.Hold('1'))
            val say = with(rig) { model.next<KeypadEffect.Say>() }
            assertEquals(Notice.NoVoicemail, say.notice)
            rig.voicemail = "+905550000001"
            model.onAction(KeypadAction.Digit('1'))
            model.onAction(KeypadAction.Hold('1'))
            eventually("voicemail called") { rig.launcher.placed.isNotEmpty() }
            assertEquals(
                "+905550000001",
                rig.launcher.placed
                    .single()
                    .first,
            )
        }

    @Test fun theFieldMenuIsTheCoresPlusShareAndThePlusForm() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.type("5551234")
            rig.clipboard.text = "0555 000 11 22"
            model.onAction(KeypadAction.OpenFieldMenu)
            val menu = with(rig) { model.next<KeypadEffect.FieldMenu>() }
            val actions = menu.entries.filterIsInstance<FieldEntry.Core>().map { it.item.action }
            assertEquals(listOf(FieldAction.Copy, FieldAction.Paste, FieldAction.Clear), actions)
            assertTrue(menu.entries.any { it is FieldEntry.Share })
            assertTrue(menu.entries.any { it is FieldEntry.E164 })
        }

    @Test fun theFieldMenuOfAnEmptyFieldWithNothingToPasteStaysClosed() =
        runBlocking {
            val model = rig().model()
            model.onAction(KeypadAction.OpenFieldMenu)
            kotlinx.coroutines.delay(300)
            val none = kotlinx.coroutines.withTimeoutOrNull(300) { model.effect.first() }
            assertNull("the core offers nothing for an empty field", none)
        }

    @Test fun aSuggestionFillsTheNumberAndCalls() =
        runBlocking {
            val rig = rig()
            val model = rig.model()
            model.type("2973")
            eventually("suggestions") {
                model.state.value.t9
                    .isNotEmpty()
            }
            model.onAction(
                KeypadAction.Suggestion(
                    model.state.value.t9
                        .first(),
                ),
            )
            eventually("called") { rig.launcher.placed.isNotEmpty() }
            assertTrue(model.text().startsWith("+90"))
        }
}
