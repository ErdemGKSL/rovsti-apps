package dev.rovsti.phone.incall

import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.RowKind
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.phone.ToneStep
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.Sims
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5.7a: the In-Call model maps every tap to one `CallsApi` command and the snapshot to what the screen draws. */
class InCallViewModelTest {
    private fun ended(h: InCallHarness) = h.source.set(CallSnapshot(keyguardLocked = false))

    // ---- What is drawn ---------------------------------------------------------------------------------------------

    @Test fun theLiveCallIsDrawnWithItsNameNumberAndPhase() {
        val h = InCallHarness()
        val call = h.ui.call!!
        assertEquals("Mert Kaya", call.title)
        assertEquals("+90 555 999 00 00", call.subtitle)
        assertEquals(CallPhase.Active, call.phase)
        assertTrue(h.ui.live)
        assertTrue(h.ui.canHold)
    }

    @Test fun anUnnamedCallShowsTheNumberAndNoSubtitle() {
        val h = InCallHarness(liveSnapshot(liveCall(name = null, contactId = null)))
        assertEquals("+90 555 999 00 00", h.ui.call!!.title)
        assertNull(h.ui.call!!.subtitle)
    }

    @Test fun aPrivateCallerShowsTheUnknownWord() {
        val h = InCallHarness(liveSnapshot(liveCall(name = null, number = "", contactId = null)))
        assertEquals("Unknown", h.ui.call!!.title)
    }

    private fun realDisplay() = NumberDisplay(NativeSharedOps(HostRust("rovsti-incall-display").core)) { "90" }

    private fun eventually(check: () -> Boolean) =
        runBlocking {
            withTimeout(10_000) {
                while (!check()) delay(10)
            }
        }

    @Test fun theNumberIsFormattedInTheNameTheSubtitleAndThePostCallPill() {
        val display = realDisplay()
        val named = InCallHarness(liveSnapshot(liveCall(number = "+905551112233")), display = display)
        eventually { named.ui.call?.subtitle == "+90 555 111 22 33" }
        assertEquals("+90 555 111 22 33", named.ui.call!!.subtitle)
        assertEquals("the raw number still keys the notes", "+905551112233", named.ui.call!!.number)
        val bare =
            InCallHarness(
                liveSnapshot(liveCall(name = null, number = "+905551112233", contactId = null)),
                display = display,
            )
        eventually { bare.ui.call?.title == "+90 555 111 22 33" }
        ended(bare)
        eventually { bare.effects.any { it is InCallEffect.PostCall } }
        val post = bare.effects.filterIsInstance<InCallEffect.PostCall>().single()
        assertEquals("+90 555 111 22 33", post.title)
        assertEquals("+905551112233", post.number)
    }

    @Test fun theHeldCardOfAnUnnamedCallReadsTheFormattedNumber() {
        val display = realDisplay()
        val snapshot =
            twoCalls().let { two ->
                two.copy(
                    calls =
                        two.calls.map {
                            if (it.id ==
                                3
                            ) {
                                it.copy(displayName = null, contactId = null, numberRaw = "+905558887766")
                            } else {
                                it
                            }
                        },
                )
            }
        val h =
            InCallHarness(
                snapshot,
                display = display,
                backend = CoreSceneBackend(NativePhoneOps(HostRust("rovsti-held").core)),
            )
        eventually { h.ui.multi.held != null }
        assertEquals(
            "+90 555 888 77 66",
            h.ui.multi.held!!
                .title,
        )
    }

    @Test fun aConferenceRowOfAnUnnamedLegReadsTheFormattedNumber() {
        val display = realDisplay()
        val snapshot =
            conferenceCall().let { conf ->
                conf.copy(
                    calls = conf.calls.map { if (it.id == 7) it.copy(displayName = null, contactId = null) else it },
                )
            }
        val h =
            InCallHarness(
                snapshot,
                display = display,
                backend = CoreSceneBackend(NativePhoneOps(HostRust("rovsti-conf").core)),
            )
        eventually { h.ui.multi.participants.size == 2 }
        val labels =
            h.ui.multi.participants
                .map { it.label }
        assertTrue("$labels", labels.none { it.startsWith("+9055") && !it.contains(' ') })
        assertTrue("$labels", labels.contains("+90 555 888 77 66"))
    }

    @Test fun aHeldCallReadsAsHeld() {
        val h = InCallHarness(liveSnapshot(liveCall(state = WireCallState.Holding)))
        assertTrue(h.ui.held)
        assertEquals(CallPhase.Holding, h.ui.call!!.phase)
    }

    @Test fun aCallNotYetConnectedHasNoConnectTime() {
        val h = InCallHarness(liveSnapshot(liveCall(state = WireCallState.Dialing, connectedAgoS = -1)))
        assertEquals(CallPhase.Dialing, h.ui.call!!.phase)
        assertEquals(0L, h.ui.call!!.connectTimeMs)
    }

    @Test fun theSimChipShowsOnlyWithTwoSims() {
        val vodafone = SimAccount("tel/svc#2", "Vodafone", "Vodafone", "Vodafone", 0xFFFF453A.toInt(), 1, true)
        val one =
            InCallHarness(
                liveSnapshot(liveCall(account = "tel/svc#2")),
                Sims(visible = false, chips = persistentListOf(vodafone)),
            )
        assertNull(one.ui.call!!.sim)
        val two =
            InCallHarness(
                liveSnapshot(liveCall(account = "tel/svc#2")),
                Sims(visible = true, chips = persistentListOf(vodafone)),
            )
        assertEquals(
            "Vodafone",
            two.ui.call!!
                .sim!!
                .label,
        )
    }

    // ---- Commands --------------------------------------------------------------------------------------------------

    @Test fun muteHoldAndEndMapToCallsApi() {
        val h = InCallHarness()
        h.model.onAction(InCallAction.ToggleMute)
        h.model.onAction(InCallAction.ToggleHold)
        h.model.onAction(InCallAction.End)
        assertEquals(listOf("mute true", "hold 2 true", "disconnect 2"), h.calls.log)
    }

    @Test fun unmuteAndResumeSendTheOpposite() {
        val h = InCallHarness(liveSnapshot(liveCall(state = WireCallState.Holding), muted = true))
        h.model.onAction(InCallAction.ToggleMute)
        h.model.onAction(InCallAction.ToggleHold)
        assertEquals(listOf("mute false", "hold 2 false"), h.calls.log)
    }

    @Test fun holdIsNotSentWhenTheCallCannotBeHeld() {
        val h = InCallHarness(liveSnapshot(liveCall(canHold = false)))
        h.model.onAction(InCallAction.ToggleHold)
        assertTrue(h.calls.log.isEmpty())
    }

    @Test fun aRefusedCommandSaysSo() {
        val h = InCallHarness()
        h.calls.accept = false
        h.model.onAction(InCallAction.ToggleMute)
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.CommandFailed)))
    }

    @Test fun withTwoRoutesTheAudioButtonTogglesSpeaker() {
        val h = InCallHarness()
        assertFalse(h.ui.routeOpensList)
        h.model.onAction(InCallAction.ToggleRoute)
        assertEquals(listOf("route speaker"), h.calls.log)
    }

    @Test fun onTheSpeakerTheAudioButtonGoesBackToThePhone() {
        val h = InCallHarness(liveSnapshot(route = AudioRoute.Speaker))
        h.model.onAction(InCallAction.ToggleRoute)
        assertEquals(listOf("route earpiece"), h.calls.log)
    }

    @Test fun aThirdRouteOpensTheList() {
        val h = InCallHarness(liveSnapshot(endpoints = listOf(earpiece, speaker, headset)))
        assertTrue(h.ui.routeOpensList)
        assertEquals(3, h.ui.routeRows.size)
        h.model.onAction(InCallAction.PickRoute("b1"))
        assertEquals(listOf("endpoint b1"), h.calls.log)
    }

    @Test fun anUnknownRouteSaysSo() {
        val h = InCallHarness()
        h.calls.accept = false
        h.model.onAction(InCallAction.PickRoute("zzz"))
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.RouteFailed)))
    }

    @Test fun withoutTheBluetoothGrantNoBluetoothRowIsShown() {
        val h = InCallHarness(liveSnapshot(endpoints = listOf(earpiece, speaker, headset)), bluetooth = false)
        assertTrue(h.ui.routeRows.none { it.kind == RowKind.Bluetooth })
        assertEquals(2, h.ui.routeRows.size)
        // Phone and speaker alone: the button toggles instead of opening a list with one choice too few.
        assertFalse(h.ui.routeOpensList)
        h.grant.value = true
        assertTrue(h.ui.routeRows.any { it.kind == RowKind.Bluetooth })
        assertTrue(h.ui.routeOpensList)
    }

    // ---- The tone keypad ---------------------------------------------------------------------------------------------

    @Test fun aKeyStartsAToneAndLiftingStopsIt() {
        val h = InCallHarness()
        h.model.onAction(InCallAction.ToggleKeypad)
        h.model.onAction(InCallAction.Digit('5'))
        h.model.onAction(InCallAction.DigitUp)
        h.model.onAction(InCallAction.Digit('#'))
        h.model.onAction(InCallAction.DigitUp)
        assertEquals(listOf("dtmf 5", "dtmfStop", "dtmf #", "dtmfStop"), h.calls.log)
        assertEquals("5#", h.ui.keypad.digits)
    }

    @Test fun copyDigitsCopiesTheTonesQuietly() {
        val h = InCallHarness()
        h.model.onAction(InCallAction.ToggleKeypad)
        h.model.onAction(InCallAction.CopyDigits)
        assertTrue(h.clipboard.copies.isEmpty())
        h.model.onAction(InCallAction.Digit('1'))
        h.model.onAction(InCallAction.Digit('2'))
        h.model.onAction(InCallAction.CopyDigits)
        assertEquals(listOf("12" to true), h.clipboard.copies)
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.TonesCopied)))
    }

    @Test fun theClipboardIsReadOnceWhenTheKeypadOpens() {
        val h = InCallHarness()
        h.clipboard.clip = "4821#"
        h.backend.pasteLabel = "4821#"
        h.model.onAction(InCallAction.ToggleKeypad)
        assertEquals("4821#", h.ui.keypad.pasteLabel)
        h.model.onAction(InCallAction.ToggleKeypad)
        assertFalse(h.ui.keypad.open)
    }

    @Test fun pastedTonesAreSentOneByOneThenRemembered() {
        val tones = ToneMemory()
        val h = InCallHarness(tones = tones)
        h.clipboard.clip = "48,1"
        h.backend.pasteLabel = "48,1"
        h.backend.plan = listOf(ToneStep.Tone('4'), ToneStep.Tone('8'), ToneStep.Pause, ToneStep.Tone('1'))
        h.model.onAction(InCallAction.ToggleKeypad)
        h.model.onAction(InCallAction.PasteTones)
        assertTrue(h.ui.keypad.sending)
        h.advance(60_000)
        assertEquals(listOf("dtmf 4", "dtmfStop", "dtmf 8", "dtmfStop", "dtmf 1", "dtmfStop"), h.calls.log)
        assertEquals("481", h.ui.keypad.digits)
        assertFalse(h.ui.keypad.sending)
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.TonesSent)))
        assertEquals("48,1", tones.get(NUMBER))
    }

    @Test fun stopCutsAPasteShort() {
        val h = InCallHarness()
        h.clipboard.clip = "1234"
        h.backend.pasteLabel = "1234"
        h.backend.plan = "1234".map { ToneStep.Tone(it) }
        h.model.onAction(InCallAction.ToggleKeypad)
        h.model.onAction(InCallAction.PasteTones)
        h.advance(TONE_ON_MS + TONE_GAP_MS + 10)
        h.model.onAction(InCallAction.StopTones)
        val sent = h.calls.log.count { it.startsWith("dtmf ") && it != "dtmfStop" }
        h.advance(60_000)
        assertEquals(sent, h.calls.log.count { it.startsWith("dtmf ") && it != "dtmfStop" })
        assertFalse(h.ui.keypad.sending)
        assertFalse(h.effects.contains(InCallEffect.Notice(InCallNotice.TonesSent)))
    }

    @Test fun aKeyTapStopsAPasteFirst() {
        val h = InCallHarness()
        h.clipboard.clip = "1234"
        h.backend.pasteLabel = "1234"
        h.backend.plan = "1234".map { ToneStep.Tone(it) }
        h.model.onAction(InCallAction.ToggleKeypad)
        h.model.onAction(InCallAction.PasteTones)
        h.model.onAction(InCallAction.Digit('9'))
        assertFalse(h.ui.keypad.sending)
        h.advance(60_000)
        assertEquals(
            "9",
            h.ui.keypad.digits
                .takeLast(1),
        )
        assertTrue(h.calls.log.none { it == "dtmf 4" })
    }

    @Test fun tonesRememberedForANumberAreOfferedNextTime() {
        val tones = ToneMemory().also { it.put(NUMBER, "4821#") }
        val h = InCallHarness(tones = tones)
        h.model.onAction(InCallAction.ToggleKeypad)
        assertEquals("4821#", h.ui.keypad.remembered)
        h.backend.plan = "4821#".map { ToneStep.Tone(it) }
        h.model.onAction(InCallAction.SendRemembered)
        h.advance(60_000)
        assertEquals("4821#", h.ui.keypad.digits)
    }

    // ---- Note, number, timer ----------------------------------------------------------------------------------------

    @Test fun theNoteSheetOpensWithTheSavedNote() {
        val h = InCallHarness()
        val started = h.ui.call!!.startedMs
        h.notes.saved[NUMBER to started] = "ask about the invoice"
        h.model.onAction(InCallAction.OpenNote)
        val show = h.effects.filterIsInstance<InCallEffect.ShowNote>().single()
        assertEquals("ask about the invoice", show.text)
        assertEquals(NUMBER, show.number)
        assertEquals(started, show.startedMs)
    }

    @Test fun savingANoteStoresItUnderTheNumberAndStartTime() {
        val h = InCallHarness()
        h.model.onAction(InCallAction.SaveNote("  call back at 5  "))
        assertEquals("call back at 5", h.notes.saved[NUMBER to h.ui.call!!.startedMs])
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.NoteSaved)))
        assertTrue(h.ui.hasNote)
    }

    @Test fun aNoteThatCannotBeSavedSaysSo() {
        val h = InCallHarness()
        h.notes.accept = false
        h.model.onAction(InCallAction.SaveNote("x"))
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.NoteFailed)))
    }

    @Test fun aPrivateCallerCannotHaveANote() {
        val h = InCallHarness(liveSnapshot(liveCall(name = null, number = "", contactId = null)))
        h.model.onAction(InCallAction.OpenNote)
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.NoNumber)))
        assertTrue(h.effects.none { it is InCallEffect.ShowNote })
    }

    @Test fun copyNumberCopiesAndSaysSo() {
        val h = InCallHarness()
        h.model.onAction(InCallAction.CopyNumber)
        assertEquals(listOf(NUMBER to false), h.clipboard.copies)
        assertTrue(h.effects.contains(InCallEffect.Notice(InCallNotice.NumberCopied)))
    }

    @Test fun copyTimerAndShareSummary() {
        val h = InCallHarness()
        h.model.onAction(InCallAction.CopyTimer("4:32"))
        h.model.onAction(InCallAction.ShareSummary("Call with Mert Kaya, 4:32"))
        assertEquals(listOf("4:32" to false), h.clipboard.copies)
        assertTrue(h.effects.contains(InCallEffect.Share("Call with Mert Kaya, 4:32")))
    }

    // ---- The end ------------------------------------------------------------------------------------------------------

    @Test fun anEndedCallShowsItsSummaryForTwoSecondsThenLeaves() {
        val h = InCallHarness()
        ended(h)
        val summary = h.ui.ended!!
        assertEquals(272L, summary.talkS)
        assertEquals("4:32", summary.elapsed)
        assertEquals(CallPhase.Ended, h.ui.call!!.phase)
        assertFalse(h.ui.live)
        assertTrue(h.effects.none { it == InCallEffect.Leave })
        h.advance(ENDED_DWELL_MS - 1)
        assertNotNull(h.ui.ended)
        h.advance(2)
        assertNull(h.ui.ended)
        assertNull(h.ui.call)
        assertEquals(1, h.effects.count { it == InCallEffect.Leave })
    }

    @Test fun anEndedCallIsSummarisedOnce() {
        val h = InCallHarness()
        ended(h)
        h.source.set(CallSnapshot(keyguardLocked = false, nowMs = 5))
        h.advance(10_000)
        assertEquals(1, h.effects.count { it == InCallEffect.Leave })
    }

    @Test fun commandsAfterTheEndDoNothing() {
        val h = InCallHarness()
        ended(h)
        h.model.onAction(InCallAction.End)
        h.model.onAction(InCallAction.ToggleMute)
        assertTrue(h.calls.log.none { it.startsWith("disconnect") || it.startsWith("mute") })
    }

    @Test fun aConnectedCallFromAContactOffersAddNote() {
        val h = InCallHarness()
        ended(h)
        val post = h.effects.filterIsInstance<InCallEffect.PostCall>().single()
        assertEquals(PostCallKind.AddNote, post.kind)
        assertEquals(NUMBER, post.number)
        assertEquals("4:32", post.elapsed)
    }

    @Test fun aCallThatAlreadyHasANoteOffersNothing() {
        val h = InCallHarness()
        h.notes.saved[NUMBER to h.ui.call!!.startedMs] = "done"
        ended(h)
        assertTrue(h.effects.none { it is InCallEffect.PostCall })
    }

    @Test fun aShortCallFromAStrangerOffersBlock() {
        val h = InCallHarness(liveSnapshot(liveCall(name = null, contactId = null, incoming = true, connectedAgoS = 8)))
        ended(h)
        val post = h.effects.filterIsInstance<InCallEffect.PostCall>().single()
        assertEquals(PostCallKind.Block, post.kind)
    }

    @Test fun noBlockPillWhenTheNumberCannotBeBlocked() {
        val h = InCallHarness(liveSnapshot(liveCall(name = null, contactId = null, incoming = true, connectedAgoS = 8)))
        h.blocker.can = false
        ended(h)
        // Not blockable, and a call this short that connected still offers the note instead.
        assertEquals(
            PostCallKind.AddNote,
            h.effects
                .filterIsInstance<InCallEffect.PostCall>()
                .single()
                .kind,
        )
    }

    @Test fun aLongCallFromAStrangerOffersTheNoteNotTheBlock() {
        val h =
            InCallHarness(liveSnapshot(liveCall(name = null, contactId = null, incoming = true, connectedAgoS = 300)))
        ended(h)
        assertEquals(
            PostCallKind.AddNote,
            h.effects
                .filterIsInstance<InCallEffect.PostCall>()
                .single()
                .kind,
        )
    }

    @Test fun aCallThatNeverConnectedHasNoElapsedTime() {
        val h = InCallHarness(liveSnapshot(liveCall(state = WireCallState.Dialing, connectedAgoS = -1)))
        ended(h)
        assertFalse(h.ui.ended!!.connected)
        assertEquals("", h.ui.ended!!.elapsed)
        assertTrue(h.effects.none { it is InCallEffect.PostCall })
    }

    @Test fun blockAndUndoGoThroughTheBlocker() =
        kotlinx.coroutines.test.runTest {
            val h = InCallHarness()
            val undo = h.model.block(NUMBER)!!
            assertEquals(listOf(NUMBER), h.blocker.blocked)
            assertTrue(h.model.undoBlock(undo))
            assertEquals(listOf(undo), h.blocker.undone)
        }

    // ---- WhatsApp calls (E5.8b) ------------------------------------------------------------------------------------

    private fun waHarness(
        wa: Boolean = true,
        call: dev.rovsti.core.model.phone.WireCall = liveCall(contactId = null, canHold = false),
    ): InCallHarness {
        val h = InCallHarness(liveSnapshot(call, endpoints = emptyList()))
        if (wa) {
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
                                "Mert Kaya",
                                dev.rovsti.core.model.phone.CallDirection.Out,
                                dev.rovsti.core.model.phone.CallPhase.Active,
                                false,
                            ),
                            speaker = false,
                            muted = false,
                        ),
                ),
            )
        }
        return h
    }

    @Test fun aWhatsAppCallIsMarkedAndTheCoreSaysWhichControlsWork() {
        val ui = waHarness().ui
        assertTrue(ui.call!!.whatsapp)
        assertEquals("905559990000@s.whatsapp.net", ui.call!!.photoJid)
        val wa = ui.whatsapp!!
        assertTrue(wa.mute && wa.speaker && wa.end)
        assertFalse(wa.keypad || wa.hold || wa.addCall)
        assertEquals("the sentence is the core's", "Not available on WhatsApp calls", wa.note)
        assertNull("a cellular call has no WhatsApp facts", waHarness(wa = false).ui.whatsapp)
    }

    @Test fun aTapOnADimmedControlSaysWhyAndDoesNothingElse() {
        val h = waHarness()
        h.model.onAction(InCallAction.Unavailable)
        assertEquals(listOf<InCallEffect>(InCallEffect.Notice(InCallNotice.WaUnavailable)), h.effects)
        assertTrue(h.calls.log.isEmpty())
    }

    @Test fun muteAndEndOnAWhatsAppCallGoToTheCallsApiWithItsId() {
        val h = waHarness()
        h.model.onAction(InCallAction.ToggleMute)
        h.model.onAction(InCallAction.End)
        assertEquals(listOf("mute true", "disconnect 2"), h.calls.log)
    }

    @Test fun theNoteAndTheBlockPillAreNotOfferedAfterAWhatsAppCall() {
        val h = waHarness(call = liveCall(contactId = null, incoming = true, connectedAgoS = 300, canHold = false))
        h.source.set(CallSnapshot())
        h.advance(100)
        assertTrue(h.effects.none { it is InCallEffect.PostCall })
    }
}
