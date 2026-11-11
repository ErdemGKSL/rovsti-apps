package dev.rovsti.phone.incall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.design.controls.CallControlActiveFillTag
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
    private val endMenus = mutableListOf<Rect>()
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
                onEndMenu = { endMenus += it },
            )
        }
        advance(100)
    }

    // ---- F20: the frosted controls --------------------------------------------------------------------------------------

    private fun pixel(
        tag: String,
        fx: Float,
        fy: Float,
    ): Int {
        val image = compose.onNodeWithTag(tag).captureToImage()
        return image.toPixelMap()[(image.width * fx).toInt(), (image.height * fy).toInt()].toArgb()
    }

    private fun grey(argb: Int) = ((argb shr 16 and 0xFF) + (argb shr 8 and 0xFF) + (argb and 0xFF)) / 3

    @Test fun anActiveToggleIsSolidWhiteWithADarkGlyph() {
        show(InCallHarness(liveSnapshot(muted = true)).ui)
        advance(600)
        // Off the glyph, inside the disc: white.
        assertTrue(
            "muted disc: ${pixel(InCallTags.Mute, 0.13f, 0.5f).toString(16)}",
            grey(pixel(InCallTags.Mute, 0.13f, 0.5f)) >= 245,
        )
        val image = compose.onNodeWithTag(InCallTags.Mute).captureToImage().toPixelMap()
        val darkest =
            (0 until image.width).minOf { x ->
                (0 until image.height).minOf { y -> grey(image[x, y].toArgb()) }
            }
        assertTrue("dark glyph: $darkest", darkest < 80)
        // An inactive toggle (Keypad) is not white.
        assertTrue(grey(pixel(InCallTags.Keypad, 0.13f, 0.5f)) < 200)
    }

    @Test fun theActiveFillFadesInInsteadOfSnapping() {
        show()
        compose.onNodeWithTag(CallControlActiveFillTag).assertDoesNotExist()
        ui = InCallHarness(liveSnapshot(muted = true)).ui
        advance(60)
        val early = grey(pixel(InCallTags.Mute, 0.13f, 0.5f))
        advance(400)
        val late = grey(pixel(InCallTags.Mute, 0.13f, 0.5f))
        assertTrue("early $early late $late", early < late && late >= 245)
    }

    @Test fun aDisabledControlIsDimmed() {
        show(ui.copy(canHold = false))
        advance(300)
        val on = grey(pixel(InCallTags.Keypad, 0.13f, 0.5f))
        val off = grey(pixel(InCallTags.Hold, 0.13f, 0.5f))
        assertTrue("enabled $on, disabled $off", off < on)
    }

    @Test fun everyFrostedControlDrawsThroughTheBackdropEffect() {
        BackdropDebug.clear()
        BackdropDebug.enabled = true
        try {
            show()
            advance(300)
            compose.onRoot().captureToImage()
            val records = BackdropDebug.snapshot()
            val controls = records.filter { it.tag == "call.control" }
            assertTrue("six grid discs drew: ${controls.size}", controls.size >= 6)
            assertTrue(records.any { it.tag == "call.collapse" })
            val bad = records.filter { it.tag?.startsWith("call.") == true && (!it.hasRenderEffect || !it.recorded) }
            assertEquals(emptyList<BackdropDebug.Record>(), bad)
        } finally {
            BackdropDebug.enabled = false
            BackdropDebug.clear()
        }
    }

    @Test fun theEndButtonIsSolidRed() {
        show()
        advance(300)
        val c = pixel(InCallTags.End, 0.13f, 0.5f)
        assertTrue("red ${c.toString(16)}", (c shr 16 and 0xFF) > 200 && (c and 0xFF) < 120)
    }

    // ---- What shows --------------------------------------------------------------------------------------------------

    @Test fun nameNumberTimerAndTheSixControlsAreDrawn() {
        show()
        compose.onNodeWithTag(InCallTags.Name).assertTextEquals("Mert Kaya")
        compose.onNodeWithTag(InCallTags.Number).assertTextEquals("+90 555 999 00 00")
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

    @Test fun addCallOpensTheSheetOnATap() {
        show()
        compose.onNodeWithTag(InCallTags.AddCall).assertIsEnabled().performClick()
        assertEquals(listOf<InCallAction>(InCallAction.OpenAddCall), actions)
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

    // ---- WhatsApp calls (E5.8b) ------------------------------------------------------------------------------------------

    private fun whatsAppUi(): InCallUi {
        val call = liveCall(name = "Ada Lovelace", contactId = null, canHold = false)
        val h = InCallHarness(liveSnapshot(call, endpoints = emptyList()))
        val jid = "905559990000@s.whatsapp.net"
        h.source.setWhatsApp(
            mapOf(
                call.id to
                    dev.rovsti.phone.incoming.WaCallMeta(
                        "c1",
                        jid,
                        dev.rovsti.core.model.phone.CallUpdate(
                            "c1",
                            jid,
                            "Ada Lovelace",
                            dev.rovsti.core.model.phone.CallDirection.Out,
                            dev.rovsti.core.model.phone.CallPhase.Active,
                            false,
                        ),
                        speaker = false,
                        muted = false,
                    ),
            ),
        )
        return h.ui
    }

    @Test fun aWhatsAppCallSaysWhatsAppAudioWithTheGlyphAndMuteSpeakerEndWork() {
        show(whatsAppUi())
        compose.onNodeWithTag(InCallTags.WaAudio).assertIsDisplayed()
        compose.onNodeWithTag(InCallTags.Mute).assertIsEnabled()
        compose.onNodeWithTag(InCallTags.Audio).assertIsEnabled()
        compose.onNodeWithTag(InCallTags.End).assertIsEnabled()
        compose.onNodeWithTag(InCallTags.Mute).performClick()
        compose.onNodeWithTag(InCallTags.End).performClick()
        assertEquals(listOf(InCallAction.ToggleMute, InCallAction.End), actions)
    }

    @Test fun aCellularCallShowsNoWhatsAppLine() {
        show()
        compose.onAllNodesWithTag(InCallTags.WaAudio).assertCountEquals(0)
    }

    @Test fun keypadHoldAddCallAndNoteAreDimmedOnAWhatsAppCallAndATapSaysWhy() {
        show(whatsAppUi())
        val dimmed = listOf(InCallTags.Keypad, InCallTags.Hold, InCallTags.AddCall, InCallTags.Note)
        for (tag in dimmed) {
            compose.onNodeWithTag(tag).assertIsEnabled()
            compose.onNodeWithTag(tag).performClick()
        }
        assertEquals(
            "every dimmed control answers a tap with the sentence",
            dimmed.map { InCallAction.Unavailable },
            actions,
        )
        compose.onNodeWithTag(InCallTags.Keypad).assertContentDescriptionContains("Keypad")
        val state =
            compose
                .onNodeWithTag(
                    InCallTags.Hold,
                ).fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.StateDescription)
        assertEquals("Not available on WhatsApp calls", state)
    }

    @Test fun theDimmedControlsAreDrawnAtFortyFivePercent() {
        show(whatsAppUi())
        val dim = pixel(InCallTags.Hold, 0.5f, 0.5f)
        show(InCallHarness().ui)
        val full = pixel(InCallTags.Hold, 0.5f, 0.5f)
        assertTrue("a dimmed disc is darker than a live one: $dim vs $full", dim != full)
    }

    // ---- E5.7b: two calls and a conference ---------------------------------------------------------------------------

    private fun twoUi(snapshot: dev.rovsti.core.model.phone.CallSnapshot = twoCalls()) = InCallHarness(snapshot).ui

    private fun top(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .boundsInRoot.top

    @Test fun theHeldCardShowsTheWaitingCallAndATapSwaps() {
        show(twoUi())
        advance(600)
        compose
            .onNodeWithTag(
                MultiCallTags.Held,
            ).assertIsDisplayed()
            .assertContentDescriptionEquals("Ayşe Yılmaz, on hold")
        compose.onNodeWithTag(MultiCallTags.Held).performClick()
        assertEquals(listOf<InCallAction>(InCallAction.Swap), actions)
    }

    @Test fun twoCallsTurnHoldIntoSwapAndNoteIntoMerge() {
        show(twoUi())
        advance(600)
        compose.onAllNodesWithTag(InCallTags.Hold).assertCountEquals(0)
        compose.onAllNodesWithTag(InCallTags.Note).assertCountEquals(0)
        compose.onNodeWithTag(MultiCallTags.Swap).assertIsEnabled().performClick()
        compose.onNodeWithTag(MultiCallTags.Merge).assertIsEnabled().performClick()
        assertEquals(listOf(InCallAction.Swap, InCallAction.Merge), actions)
    }

    @Test fun addCallIsDimmedWithTwoCallsAndATapShowsTheCoresReason() {
        show(twoUi())
        advance(600)
        compose.onNodeWithTag(InCallTags.AddCall).performClick()
        assertEquals(listOf<InCallAction>(InCallAction.Dimmed("Two calls are already in progress")), actions)
    }

    @Test fun mergeIsDimmedWhenTheCarrierSaysNo() {
        show(twoUi(twoCalls(canMerge = false)))
        advance(600)
        compose.onNodeWithTag(MultiCallTags.Merge).performClick()
        assertEquals(
            listOf<InCallAction>(InCallAction.Dimmed("Your carrier doesn't allow merging")),
            actions,
        )
    }

    @Test fun theHeldCardDoesNotMoveAnythingBelowIt() {
        show(InCallHarness().ui)
        advance(600)
        val name = top(InCallTags.Name)
        val end = top(InCallTags.End)
        val mute = top(InCallTags.Mute)
        ui = twoUi()
        // Frame by frame while the hold-away spring runs, and settled: the layout is the same in every one.
        for (ms in listOf(16L, 60L, 200L, 900L)) {
            advance(ms)
            assertEquals("name at +$ms", name, top(InCallTags.Name), 0.5f)
            assertEquals("end at +$ms", end, top(InCallTags.End), 0.5f)
            assertEquals("mute at +$ms", mute, top(InCallTags.Mute), 0.5f)
        }
    }

    @Test fun aSwapSettlesWithTheOtherCallOnStageAndNothingJumps() {
        show(twoUi())
        advance(900)
        val name = top(InCallTags.Name)
        val end = top(InCallTags.End)
        // The other call takes the stage; the first one is the card now.
        val swapped =
            twoCalls().let { it.copy(foregroundCallId = 3, backgroundCallId = 2) }
        ui = InCallHarness(swapped).ui
        advance(16)
        compose.onNodeWithTag(MultiCallTags.Held).assertIsDisplayed()
        advance(1_200)
        compose.onNodeWithTag(InCallTags.Name).assertTextEquals("Ayşe Yılmaz")
        compose.onNodeWithTag(MultiCallTags.Held).assertContentDescriptionEquals("Mert Kaya, on hold")
        assertEquals(end, top(InCallTags.End), 0.5f)
        assertEquals("the stage is back where it was", name, top(InCallTags.Name), 1f)
    }

    @Test fun aConferenceShowsItsPeopleAndThePillOpensThePanel() {
        show(twoUi(conferenceCall()))
        advance(900)
        compose.onNodeWithTag(InCallTags.Name).assertTextEquals("Conference call")
        compose.onNodeWithTag(MultiCallTags.People).assertIsDisplayed()
        compose.onNodeWithTag(MultiCallTags.People).performClick()
        assertEquals(listOf<InCallAction>(InCallAction.OpenConference), actions)
    }

    @Test fun holdingTheEndButtonWithTwoCallsOpensTheEndAllMenu() {
        show(twoUi())
        advance(600)
        compose.onNodeWithTag(InCallTags.End).performTouchInput { longClick() }
        assertEquals(1, endMenus.size)
        assertTrue(actions.isEmpty())
    }

    @Test fun holdingTheEndButtonWithOneCallDoesNothingSpecial() {
        show()
        compose.onNodeWithTag(InCallTags.End).performTouchInput { longClick() }
        assertTrue(endMenus.isEmpty())
    }

    // ---- F23: the compact header over the keypad --------------------------------------------------------------------------

    @Test fun withTheKeypadOpenTheNameAndTheDigitsDoNotTouch() {
        show(ui.copy(keypad = ToneKeypad(open = true, digits = "4821", pasteLabel = "4821#")))
        advance(600)
        val name = compose.onNodeWithTag(InCallTags.Name).fetchSemanticsNode()
        val digits = compose.onNodeWithTag(InCallTags.Digits).fetchSemanticsNode()
        assertFalse(
            "name ${name.boundsInRoot} digits ${digits.boundsInRoot}",
            name.boundsInRoot.overlaps(digits.boundsInRoot),
        )
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        name.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertFalse("the name is cut", layout.single().hasVisualOverflow)
        assertEquals("one line", 1, layout.single().lineCount)
    }

    @Test fun aLongNameStaysOnOneLineWithAnEllipsisOverTheKeypad() {
        val long = InCallHarness(liveSnapshot(liveCall(name = "Muhammed Abdurrahman Karaoglanoglu-Yildirimoglu"))).ui
        show(long.copy(keypad = ToneKeypad(open = true, digits = "4821")))
        advance(600)
        val name = compose.onNodeWithTag(InCallTags.Name).fetchSemanticsNode()
        val digits = compose.onNodeWithTag(InCallTags.Digits).fetchSemanticsNode()
        assertFalse(name.boundsInRoot.overlaps(digits.boundsInRoot))
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        name.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertEquals(1, layout.single().lineCount)
    }

    // ---- F26: the Audio glyph ---------------------------------------------------------------------------------------------

    @Test fun theAudioGlyphIsASpeakerForEarpieceAndSpeakerBluetoothForBluetoothHeadphonesForWired() {
        assertEquals(dev.rovsti.design.icons.RovstiIcons.Speaker, audioIcon(AudioRoute.Earpiece))
        assertEquals(dev.rovsti.design.icons.RovstiIcons.Speaker, audioIcon(AudioRoute.Speaker))
        assertEquals(dev.rovsti.design.icons.RovstiIcons.Bluetooth, audioIcon(AudioRoute.Bluetooth))
        assertEquals(dev.rovsti.design.icons.RovstiIcons.Headphones, audioIcon(AudioRoute.WiredHeadset))
    }
}
