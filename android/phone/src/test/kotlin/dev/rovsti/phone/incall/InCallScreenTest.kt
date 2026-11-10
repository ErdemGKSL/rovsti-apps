package dev.rovsti.phone.incall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.phone.LockScreenGate
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.incoming.AuraMode
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.7a: the In-Call screen without a model (every callback recorded), the mini pill, the motion numbers, the gate. */
@Config(sdk = [35])
class InCallScreenTest : ShotTest() {
    private val actions = mutableListOf<InCallAction>()
    private val audioAnchors = mutableListOf<Rect>()
    private var ui by mutableStateOf(InCallHarness().ui)
    private val timer = mutableStateOf("4:32")

    private val vodafone = SimAccount("tel/svc#2", "Vodafone", "Vodafone", "Vodafone", 0xFFFF453A.toInt(), 1, true)

    private fun show(
        value: InCallUi = ui,
        aura: AuraMode = AuraMode.Shot,
    ) {
        ui = value
        composeFrame {
            InCallContent(
                ui = ui,
                strings = inCallStrings,
                timer = timer,
                onAction = { actions += it },
                onAudio = { audioAnchors += it },
                onTimerMenu = {},
                onCollapse = { actions += InCallAction.StopTones },
                aura = aura,
            )
        }
        advance(100)
    }

    // ---- What shows --------------------------------------------------------------------------------------------------

    @Test fun nameNumberTimerAndTheSixControlsAreDrawn() {
        show()
        compose.onNodeWithTag(InCallTags.Name).assertTextEquals("Mert Kaya")
        compose.onNodeWithTag(InCallTags.Number).assertTextEquals("+905559990000")
        compose.onNodeWithTag(InCallTags.Timer).assertIsDisplayed()
        for (tag in listOf(
            InCallTags.Mute,
            InCallTags.Keypad,
            InCallTags.Audio,
            InCallTags.AddCall,
            InCallTags.Hold,
            InCallTags.Note,
            InCallTags.End,
        )) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test fun addCallIsDimmedUntilE57b() {
        show()
        compose.onNodeWithTag(InCallTags.AddCall).assertIsNotEnabled()
        compose
            .onNodeWithTag(
                InCallTags.AddCall,
            ).fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)
            .let { assertEquals(inCallStrings.addCallLater, it) }
    }

    @Test fun aCallThatCannotBeHeldHasAnInertHoldButton() {
        show(ui.copy(canHold = false))
        compose.onNodeWithTag(InCallTags.Hold).assertIsNotEnabled()
        compose.onNodeWithTag(InCallTags.Mute).assertIsEnabled()
    }

    @Test fun theSimChipNamesTheLine() {
        val sims = Sims(visible = true, chips = persistentListOf(vodafone))
        show(InCallHarness(liveSnapshot(liveCall(account = "tel/svc#2")), sims).ui)
        compose.onNodeWithTag(InCallTags.Sim).assertIsDisplayed()
    }

    @Test fun noSimChipWithOneSim() {
        show()
        compose.onNodeWithTag(InCallTags.Sim).assertDoesNotExist()
    }

    @Test fun aCallNotYetConnectedSaysCalling() {
        show(InCallHarness(liveSnapshot(liveCall(state = WireCallState.Dialing, connectedAgoS = -1))).ui)
        compose.onNodeWithTag(InCallTags.Status).assertTextEquals(inCallStrings.calling)
        compose.onNodeWithTag(InCallTags.Timer).assertDoesNotExist()
    }

    @Test fun aHeldCallSaysOnHold() {
        show(InCallHarness(liveSnapshot(liveCall(state = WireCallState.Holding))).ui)
        compose.onNodeWithTag(InCallTags.Status).assertTextEquals(inCallStrings.held)
    }

    @Test fun theEndedSummaryReplacesTheTimerAndDimsTheControls() {
        val h = InCallHarness()
        h.source.set(
            dev.rovsti.core.model.phone
                .CallSnapshot(keyguardLocked = false),
        )
        show(h.ui)
        compose.onNodeWithTag(InCallTags.Status).assertTextEquals("Call with Mert Kaya, 4:32")
        compose.onNodeWithTag(InCallTags.End).assertIsNotEnabled()
        compose.onNodeWithTag(InCallTags.Mute).assertIsNotEnabled()
    }

    @Test fun theKeypadReplacesTheGridAndHasTwelveKeys() {
        show(ui.copy(keypad = ToneKeypad(open = true, digits = "4821", pasteLabel = "4821#")))
        for (c in "0123456789*#") compose.onNodeWithTag(InCallTags.key(c)).assertIsDisplayed()
        compose.onNodeWithTag(InCallTags.Mute).assertDoesNotExist()
        compose.onNodeWithTag(InCallTags.Paste).assertIsDisplayed()
        compose.onNodeWithTag(InCallTags.Hide).assertIsDisplayed()
        compose.onNodeWithTag(InCallTags.End).assertIsDisplayed()
    }

    @Test fun whileTonesPlayThePasteButtonReadsStop() {
        show(ui.copy(keypad = ToneKeypad(open = true, pasteLabel = "4821#", sending = true)))
        compose.onNodeWithTag(InCallTags.Paste).assertTextEquals(inCallStrings.stopTones)
        compose.onNodeWithTag(InCallTags.Paste).performClick()
        assertEquals(listOf<InCallAction>(InCallAction.StopTones), actions)
    }

    @Test fun noPasteButtonWhenTheClipboardDoesNotFit() {
        show(ui.copy(keypad = ToneKeypad(open = true)))
        compose.onNodeWithTag(InCallTags.Paste).assertDoesNotExist()
    }

    @Test fun rememberedTonesAreOffered() {
        show(ui.copy(keypad = ToneKeypad(open = true, remembered = "4821#")))
        compose.onNodeWithTag(InCallTags.Remembered).assertIsDisplayed()
        compose.onNodeWithTag(InCallTags.Remembered).performClick()
        assertEquals(listOf<InCallAction>(InCallAction.SendRemembered), actions)
    }

    // ---- Actions -----------------------------------------------------------------------------------------------------

    @Test fun everyControlSendsItsAction() {
        show()
        for ((tag, expected) in listOf(
            InCallTags.Mute to InCallAction.ToggleMute,
            InCallTags.Keypad to InCallAction.ToggleKeypad,
            InCallTags.Hold to InCallAction.ToggleHold,
            InCallTags.Note to InCallAction.OpenNote,
            InCallTags.End to InCallAction.End,
            InCallTags.Audio to InCallAction.ToggleRoute,
        )) {
            actions.clear()
            compose.onNodeWithTag(tag).performClick()
            advance(100)
            assertEquals(tag, listOf(expected), actions)
        }
    }

    @Test fun withThreeRoutesTheAudioButtonAsksForTheListAtItsOwnBounds() {
        val rich = InCallHarness(liveSnapshot(endpoints = listOf(earpiece, speaker, headset))).ui
        show(rich)
        compose.onNodeWithTag(InCallTags.Audio).performClick()
        assertTrue(actions.isEmpty())
        assertEquals(1, audioAnchors.size)
        assertTrue(audioAnchors[0].width > 0f)
    }

    @Test fun anActiveControlSaysSoToAScreenReader() {
        show(ui.copy(muted = true, route = AudioRoute.Speaker))
        val node = compose.onNodeWithTag(InCallTags.Mute).fetchSemanticsNode()
        assertEquals(inCallStrings.unmute, node.config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun aKeyDownSendsADigitAndLiftingSendsTheStop() {
        show(ui.copy(keypad = ToneKeypad(open = true)))
        compose.onNodeWithTag(InCallTags.key('5')).performTouchInput {
            down(center)
            up()
        }
        assertEquals(listOf<InCallAction>(InCallAction.Digit('5'), InCallAction.DigitUp), actions)
    }

    @Test fun touchAndHoldOnTheDigitLineCopiesTheTones() {
        show(ui.copy(keypad = ToneKeypad(open = true, digits = "4821")))
        compose.onNodeWithTag(InCallTags.Digits).performTouchInput { longClick() }
        assertEquals(listOf<InCallAction>(InCallAction.CopyDigits), actions)
    }

    @Test fun touchAndHoldOnTheNameCopiesTheNumber() {
        show()
        compose.onNodeWithTag(InCallTags.Name).performTouchInput { longClick() }
        assertEquals(listOf<InCallAction>(InCallAction.CopyNumber), actions)
    }

    // ---- The timer -----------------------------------------------------------------------------------------------------

    @Test fun aTickRecomposesTheTimerTextAndNothingElse() {
        show()
        advance(200)
        val content = InCallProbe.content.get()
        val timerCount = InCallProbe.timer.get()
        timer.value = "4:33"
        advance(100)
        compose.onNodeWithTag(InCallTags.Timer).assertTextEquals("4:33")
        assertEquals("the screen did not recompose", content, InCallProbe.content.get())
        assertTrue("the text did", InCallProbe.timer.get() > timerCount)
    }

    // ---- The mini pill ------------------------------------------------------------------------------------------------

    private var pillVisible by mutableStateOf(true)
    private val pillTaps = mutableListOf<String>()

    private fun showPill() {
        composeFrame {
            MiniCallPill(
                ui = ui,
                strings = inCallStrings,
                timer = timer,
                visible = pillVisible,
                onExpand = { pillTaps += "expand" },
                onEnd = { pillTaps += "end" },
            )
        }
        advance(600)
    }

    @Test fun theMiniPillShowsTheCallerAndExpandsOnATap() {
        showPill()
        compose.onNodeWithTag(InCallTags.Mini).assertIsDisplayed()
        compose.onNodeWithTag(InCallTags.Mini).performClick()
        assertEquals(listOf("expand"), pillTaps)
    }

    @Test fun theEndCircleOfThePillHangsUp() {
        showPill()
        compose.onNodeWithTag(InCallTags.MiniEnd).performClick()
        assertEquals(listOf("end"), pillTaps)
    }

    @Test fun thePillLeavesWhenTheCallPageIsShown() {
        showPill()
        pillVisible = false
        advance(1_200)
        advance(1_200)
        compose.onNodeWithTag(InCallTags.Mini).assertDoesNotExist()
    }

    @Test fun theMotionWindowsAreTheReferenceNumbers() {
        // Pill content gone by 0.3, full content from 0.1 to 0.6, the pill takes touches up to 0.2.
        assertEquals(1f, CallSheetMotion.miniAlpha(0f), 0f)
        assertEquals(0f, CallSheetMotion.miniAlpha(0.3f), 1e-6f)
        assertEquals(0f, CallSheetMotion.miniAlpha(0.9f), 0f)
        assertEquals(0f, CallSheetMotion.fullAlpha(0.1f), 0f)
        assertEquals(1f, CallSheetMotion.fullAlpha(0.6f), 1e-6f)
        assertTrue(CallSheetMotion.fullAlpha(0.35f) < 0.5f)
        assertTrue(CallSheetMotion.miniOwnsInput(0.2f))
        assertFalse(CallSheetMotion.miniOwnsInput(0.21f))
        assertEquals(15f, CallSheetMotion.lerp(10f, 20f, 0.5f), 0f)
    }

    // ---- Over the lock screen -----------------------------------------------------------------------------------------

    @Test fun theScreenShowsOverTheLockScreenOnlyWhileALiveCallExists() {
        val flags = mutableListOf<Boolean>()
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val gate =
            LockScreenGate({ flags += it }, { r, ms -> pending += r to ms }, { r ->
                pending.removeAll {
                    it.first ===
                        r
                }
            })
        gate.onLiveCallChanged(true)
        assertEquals(listOf(true), flags)
        gate.onLiveCallChanged(false)
        // It lingers for the "Call ended" summary, then lets go.
        assertEquals(listOf(true), flags)
        assertEquals(LockScreenGate.LINGER_MS, pending.single().second)
        pending.single().first.run()
        assertEquals(listOf(true, false), flags)
    }

    @Test fun aNewCallCancelsTheLingeringClear() {
        val flags = mutableListOf<Boolean>()
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val gate =
            LockScreenGate({ flags += it }, { r, ms -> pending += r to ms }, { r ->
                pending.removeAll {
                    it.first ===
                        r
                }
            })
        gate.onLiveCallChanged(true)
        gate.onLiveCallChanged(false)
        gate.onLiveCallChanged(true)
        assertTrue(pending.isEmpty())
        assertEquals(listOf(true, true), flags)
    }
}
