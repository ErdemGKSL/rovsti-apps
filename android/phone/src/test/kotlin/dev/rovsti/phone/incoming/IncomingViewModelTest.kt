package dev.rovsti.phone.incoming

import dev.rovsti.core.model.chat.ReminderSlot
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.Sims
import dev.rovsti.platform.telephony.RejectPolicy
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5.6: each tap becomes exactly one `CallsApi` call; the Rust core only supplies words and time slots. */
class IncomingViewModelTest {
    private fun notices(h: IncomingHarness) = h.effects.filterIsInstance<IncomingEffect.Notice>()

    // ---- What is drawn ---------------------------------------------------------------------------------------------

    /** The Rust core's Display form, with home "90" (the host library; skipped when it is not built). */
    private fun realDisplay() = NumberDisplay(NativeSharedOps(HostRust("rovsti-incoming-display").core)) { "90" }

    private fun settled(
        h: IncomingHarness,
        done: (IncomingUi) -> Boolean,
    ) = runBlocking {
        withTimeout(10_000) {
            while (!done(h.ui)) delay(10)
        }
    }

    @Test fun theNumberUnderTheNameIsFormattedAndAnUnnamedCallTitlesWithIt() {
        val display = realDisplay()
        val named = IncomingHarness(display = display)
        settled(named) { it.caller?.subtitle == "+90 555 111 22 33" }
        assertEquals("+90 555 111 22 33", named.ui.caller!!.subtitle)
        assertEquals("the avatar key does not follow the number's text", "7", named.ui.caller!!.avatarKey)
        val bare = IncomingHarness(ringingSnapshot(ringing(name = null, contactId = null)), display = display)
        settled(bare) { it.caller?.title == "+90 555 111 22 33" }
        assertNull(bare.ui.caller!!.subtitle)
    }

    @Test fun theWaitingCardNumberIsFormattedToo() {
        val h = IncomingHarness(waitingSnapshot(), display = realDisplay())
        settled(h) { it.waiting?.subtitle == "+90 555 333 44 55" }
        assertEquals("+90 555 333 44 55", h.ui.waiting!!.subtitle)
    }

    @Test fun theRingingCallBecomesTheCallerWithNameNumberAndKey() {
        val caller = IncomingHarness().ui.caller!!
        assertEquals("Ayse Yilmaz", caller.title)
        assertEquals("+905551112233", caller.subtitle)
        assertEquals("7", caller.avatarKey)
    }

    @Test fun anUnnamedCallShowsTheNumberAloneAndABlankOneUnknown() {
        val bare = IncomingHarness(ringingSnapshot(ringing(name = null, contactId = null))).ui.caller!!
        assertEquals("+905551112233", bare.title)
        assertNull(bare.subtitle)
        val blank = IncomingHarness(ringingSnapshot(ringing(name = null, number = "", contactId = null))).ui
        assertEquals("Unknown", blank.caller!!.title)
        assertFalse("nothing to message", blank.canMessage)
    }

    @Test fun lockedAndSilencedComeFromTheSnapshot() {
        val ui = IncomingHarness(ringingSnapshot(locked = true, silenced = true)).ui
        assertTrue(ui.locked)
        assertTrue(ui.silenced)
    }

    @Test fun theSimChipShowsOnlyWithTwoSimsAndOnlyForTheCallsAccount() {
        val chip = SimAccount("tel/svc#2", "Vodafone", "Vodafone", "Vodafone", 0xFFFF453A.toInt(), 1, true)
        val sims = Sims(visible = true, chips = persistentListOf(chip))
        val call = ringing(account = "tel/svc#2")
        assertEquals(
            SimChip("Vodafone", 0xFFFF453A.toInt()),
            IncomingHarness(ringingSnapshot(call), sims).ui.caller!!.sim,
        )
        assertNull(
            "one SIM: no chip",
            IncomingHarness(ringingSnapshot(call), Sims(visible = false, chips = sims.chips)).ui.caller!!.sim,
        )
        assertNull(
            "other account",
            IncomingHarness(ringingSnapshot(ringing(account = "tel/svc#1")), sims).ui.caller!!.sim,
        )
    }

    @Test fun aWaitingCallIsNotTheRingingCall() {
        val ui = IncomingHarness(waitingSnapshot()).ui
        assertNull("the Incoming screen is for the first ring", ui.caller)
        assertEquals(3, ui.waiting!!.callId)
        assertEquals("Deniz Aksoy", ui.waiting!!.title)
        assertFalse(ui.waiting!!.relabelled)
        assertTrue(ui.waiting!!.canHold)
    }

    @Test fun aHeldCallRelabelsHoldAndAnswer() {
        val waiting = IncomingHarness(waitingSnapshot(held = true)).ui.waiting!!
        assertTrue(waiting.relabelled)
        assertTrue("ending the held call is always possible", waiting.canHold)
        assertFalse(IncomingHarness(waitingSnapshot(canHold = false)).ui.waiting!!.canHold)
    }

    // ---- Answer, decline ---------------------------------------------------------------------------------------------

    @Test fun answerAnswersOnceWhateverTheTapCount() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.Answer)
        h.model.onAction(IncomingAction.Answer)
        assertEquals(listOf("answer 1"), h.calls.log)
        assertEquals(listOf<IncomingEffect>(IncomingEffect.Confirm), h.effects)
    }

    @Test fun declineRejectsTheRingingCall() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.Decline)
        assertEquals(listOf("reject 1"), h.calls.log)
        assertEquals(listOf<IncomingEffect>(IncomingEffect.Reject), h.effects)
    }

    @Test fun withNothingRingingEveryCommandIsStaleAndTouchesNoCall() {
        val h = IncomingHarness(CallSnapshot())
        for (a in listOf(
            IncomingAction.Answer,
            IncomingAction.Decline,
            IncomingAction.OpenReplies,
            IncomingAction.OpenReminders,
        )) {
            h.model.onAction(a)
        }
        assertTrue(h.calls.log.toString(), h.calls.log.isEmpty())
        assertEquals(4, notices(h).count { it.notice == IncomingNotice.Stale })
    }

    @Test fun aRefusedAnswerIsStaleNotConfirmed() {
        val h = IncomingHarness()
        h.calls.accept = false
        h.model.onAction(IncomingAction.Answer)
        assertEquals(IncomingNotice.Stale, notices(h).single().notice)
        assertFalse(h.effects.contains(IncomingEffect.Confirm))
    }

    // ---- Decline with message ----------------------------------------------------------------------------------------

    @Test fun messageAsksTheCoreForTheChoicesWithTheSavedRepliesAndShowsThem() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.OpenReplies)
        val sheet = h.effects.single() as IncomingEffect.ShowReplies
        assertEquals(1, sheet.callId)
        assertEquals(listOf("Can't talk now.", "I'll call you back."), sheet.choices)
        assertEquals(listOf("saved"), h.backend.savedAsked)
        assertTrue("choosing a reply is what declines; opening the sheet does not", h.calls.log.isEmpty())
    }

    @Test fun aFailedCoreStillOffersCustom() {
        val h = IncomingHarness()
        h.backend.replies = null
        h.model.onAction(IncomingAction.OpenReplies)
        val sheet = h.effects.single() as IncomingEffect.ShowReplies
        assertTrue(sheet.choices.isEmpty())
        assertEquals(RejectPolicy.MAX_CHARS, sheet.maxChars)
    }

    @Test fun aChosenReplyDeclinesWithThatText() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.Reply(1, "Can't talk now."))
        assertEquals(listOf("reply 1 Can't talk now."), h.calls.log)
        assertEquals(listOf<IncomingEffect>(IncomingEffect.Reject), h.effects)
    }

    @Test fun theReplyCodesMapToTheRightFeedback() {
        for ((code, expected) in listOf(
            RejectPolicy.SENT to IncomingEffect.Reject,
            RejectPolicy.OPENED to IncomingEffect.Reject,
            RejectPolicy.REJECTED_ONLY to IncomingEffect.Notice(IncomingNotice.DeclinedOnly),
            RejectPolicy.STALE to IncomingEffect.Notice(IncomingNotice.Stale),
        )) {
            val h = IncomingHarness()
            h.calls.rejectCode = code
            h.model.onAction(IncomingAction.Reply(1, "x"))
            assertEquals("code $code", listOf<IncomingEffect>(expected), h.effects)
        }
    }

    @Test fun customDeclinesAndOpensTheSmsApp() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.ReplyCustom(1))
        assertEquals(listOf("compose 1"), h.calls.log)
    }

    // ---- Remind me ---------------------------------------------------------------------------------------------------

    @Test fun remindMeShowsTheCoresSlots() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.OpenReminders)
        val sheet = h.effects.single() as IncomingEffect.ShowReminders
        assertEquals(listOf("In 15 minutes", "In 1 hour"), sheet.slots.map { it.label })
        assertTrue("the call keeps ringing until a slot is chosen", h.calls.log.isEmpty())
    }

    @Test fun aChosenSlotArmsTheReminderThenDeclines() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.RemindAt(ReminderSlot.In1Hour))
        assertEquals(Triple("+905551112233", "Ayse Yilmaz", ReminderSlot.In1Hour), h.backend.reminderFor)
        assertEquals(listOf("""{"id":5}"""), h.reminders.armed)
        assertEquals(listOf("reject 1"), h.calls.log)
        assertEquals(
            listOf(IncomingEffect.Notice(IncomingNotice.ReminderSet, "Reminder at 18:05"), IncomingEffect.Reject),
            h.effects,
        )
    }

    @Test fun aReminderThatCannotBeArmedKeepsTheCallRinging() {
        val h = IncomingHarness()
        h.reminders.accept = false
        h.model.onAction(IncomingAction.RemindAt(ReminderSlot.In15Min))
        assertTrue(h.calls.log.isEmpty())
        assertEquals(IncomingNotice.ReminderFailed, notices(h).single().notice)
    }

    @Test fun noSlotsIsAFailureNotAnEmptySheet() {
        val h = IncomingHarness()
        h.backend.slots = emptyList()
        h.model.onAction(IncomingAction.OpenReminders)
        assertEquals(listOf<IncomingEffect>(IncomingEffect.Notice(IncomingNotice.ReminderFailed)), h.effects)
    }

    // ---- Silence, first frame ----------------------------------------------------------------------------------------

    @Test fun silenceAsksTheRegistryToSilenceTheRinger() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.Silence)
        assertEquals(listOf("silence"), h.calls.log)
    }

    @Test fun theFirstFrameIsReportedOncePerCall() {
        val h = IncomingHarness()
        h.model.onAction(IncomingAction.FirstFrame(1))
        h.model.onAction(IncomingAction.FirstFrame(1))
        h.model.onAction(IncomingAction.FirstFrame(2))
        assertEquals(listOf("incomingFrame 1", "incomingFrame 2"), h.calls.log)
    }

    // ---- The waiting-call card ---------------------------------------------------------------------------------------

    @Test fun endAndAnswerReplacesTheLiveCall() {
        val h = IncomingHarness(waitingSnapshot())
        h.model.onAction(IncomingAction.EndAndAnswer)
        assertEquals(listOf("answerReplacing 3"), h.calls.log)
    }

    @Test fun holdAndAnswerHoldsTheLiveCall() {
        val h = IncomingHarness(waitingSnapshot())
        h.model.onAction(IncomingAction.HoldAndAnswer)
        assertEquals(listOf("answerHolding 3"), h.calls.log)
    }

    @Test fun withAHeldCallTheSameButtonEndsTheHeldCall() {
        val h = IncomingHarness(waitingSnapshot(held = true))
        h.model.onAction(IncomingAction.HoldAndAnswer)
        assertEquals(listOf("answerEndingHeld 3"), h.calls.log)
    }

    @Test fun aCallThatCannotBeHeldRejectsTheTapInsteadOfAnswering() {
        val h = IncomingHarness(waitingSnapshot(canHold = false))
        h.model.onAction(IncomingAction.HoldAndAnswer)
        assertTrue(h.calls.log.isEmpty())
        assertEquals(listOf<IncomingEffect>(IncomingEffect.Reject), h.effects)
    }

    @Test fun declineOnTheCardRejectsTheWaitingCallNotTheLiveOne() {
        val h = IncomingHarness(waitingSnapshot())
        h.model.onAction(IncomingAction.DeclineWaiting)
        assertEquals(listOf("reject 3"), h.calls.log)
    }

    @Test fun messageOnTheCardOffersTheRepliesOfTheWaitingCall() {
        val h = IncomingHarness(waitingSnapshot())
        h.model.onAction(IncomingAction.OpenWaitingReplies)
        assertEquals(3, (h.effects.single() as IncomingEffect.ShowReplies).callId)
    }

    // ---- Leaving -----------------------------------------------------------------------------------------------------

    @Test fun answeringLeavesForTheCall() {
        val h = IncomingHarness()
        h.source.set(CallSnapshot(calls = listOf(ringing().copy(state = WireCallState.Active)), foregroundCallId = 1))
        assertEquals(listOf<IncomingEffect>(IncomingEffect.Leave(toCall = true)), h.effects)
    }

    @Test fun aCallerWhoGivesUpClosesTheScreen() {
        val h = IncomingHarness()
        h.source.set(CallSnapshot())
        assertEquals(listOf<IncomingEffect>(IncomingEffect.Leave(toCall = false)), h.effects)
    }

    @Test fun aScreenOpenedOnNoRingingCallClosesAtOnce() {
        assertEquals(
            listOf<IncomingEffect>(IncomingEffect.Leave(toCall = false)),
            IncomingHarness(CallSnapshot(), keepInitial = true).effects,
        )
    }

    @Test fun aScreenOpenedWhileACallIsLiveLeavesForIt() {
        val live = CallSnapshot(calls = listOf(active()), foregroundCallId = 2)
        assertEquals(
            listOf<IncomingEffect>(IncomingEffect.Leave(toCall = true)),
            IncomingHarness(live, keepInitial = true).effects,
        )
    }

    @Test fun aRingingScreenDoesNotLeaveWhileItRings() {
        val h = IncomingHarness()
        h.source.set(ringingSnapshot(silenced = true))
        assertTrue(h.effects.isEmpty())
    }
}
