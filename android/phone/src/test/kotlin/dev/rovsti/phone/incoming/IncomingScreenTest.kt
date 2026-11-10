package dev.rovsti.phone.incoming

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.design.media.AuraAnimating
import dev.rovsti.design.media.AuraStill
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.6: the Incoming screen and the waiting-call card without a model; every callback is recorded. */
@Config(sdk = [35])
class IncomingScreenTest : ShotTest() {
    private val actions = mutableListOf<IncomingAction>()
    private var ui by mutableStateOf(IncomingHarness().ui)
    private val caller = IncomingHarness().ui.caller!!

    private fun show(
        value: IncomingUi = ui,
        reduceMotion: Boolean = false,
        aura: AuraMode = AuraMode(),
    ) {
        ui = value
        composeFrame {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                IncomingContent(ui, incomingStrings, { actions += it }, aura = aura)
            }
        }
        advance(100)
    }

    private fun auraState(): String? =
        compose
            .onNodeWithTag(IncomingTags.Aura)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    // ---- What shows --------------------------------------------------------------------------------------------------

    @Test fun nameNumberAndLabelsAreDrawn() {
        show()
        compose.onNodeWithTag(IncomingTags.Name).assertTextEquals("Ayse Yilmaz")
        compose.onNodeWithTag(IncomingTags.Number).assertTextEquals("+905551112233")
        compose.onNodeWithTag(IncomingTags.Answer).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Decline).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Message).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Remind).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Silence).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Lock).assertDoesNotExist()
    }

    @Test fun theLockScreenVariantSaysTheScreenIsLocked() {
        show(ui.copy(locked = true))
        compose.onNodeWithTag(IncomingTags.Lock).assertIsDisplayed()
    }

    @Test fun withoutANumberThereIsNoMessageCircle() {
        show(ui.copy(canMessage = false))
        compose.onNodeWithTag(IncomingTags.Message).assertDoesNotExist()
    }

    @Test fun theSimChipNamesTheLine() {
        show(ui.copy(caller = caller.copy(sim = SimChip("Vodafone", 0xFFFF453A.toInt()))))
        compose.onNodeWithTag(IncomingTags.Sim).assertIsDisplayed()
    }

    // ---- Actions -----------------------------------------------------------------------------------------------------

    @Test fun everyCircleSendsItsAction() {
        show()
        for ((tag, expected) in listOf(
            IncomingTags.Answer to IncomingAction.Answer,
            IncomingTags.Decline to IncomingAction.Decline,
            IncomingTags.Message to IncomingAction.OpenReplies,
            IncomingTags.Remind to IncomingAction.OpenReminders,
            IncomingTags.Silence to IncomingAction.Silence,
        )) {
            actions.clear()
            compose.onNodeWithTag(tag).performClick()
            advance(100)
            assertEquals(tag, listOf(expected), actions.filterNot { it is IncomingAction.FirstFrame })
        }
    }

    @Test fun aSilencedRingerDisablesSilence() {
        show(ui.copy(silenced = true))
        compose.onNodeWithTag(IncomingTags.Silence).assertIsNotEnabled()
        compose.onNodeWithTag(IncomingTags.Answer).assertIsEnabled()
    }

    // ---- First frame -------------------------------------------------------------------------------------------------

    @Test fun theFirstFrameIsReportedOnceForTheCall() {
        show()
        advance(300)
        assertEquals(listOf<IncomingAction>(IncomingAction.FirstFrame(1)), actions)
        // A change of the same call (silenced, the name resolving) is not a new first frame.
        ui = ui.copy(silenced = true, caller = caller.copy(title = "Ayse Y."))
        advance(300)
        assertEquals(1, actions.count { it is IncomingAction.FirstFrame })
    }

    @Test fun aNewCallReportsItsOwnFirstFrame() {
        show()
        advance(300)
        ui = ui.copy(caller = caller.copy(callId = 2))
        advance(300)
        assertEquals(listOf(1, 2), actions.filterIsInstance<IncomingAction.FirstFrame>().map { it.callId })
    }

    @Test fun noCallerMeansNoReportAndNoButtons() {
        show(IncomingUi())
        advance(300)
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag(IncomingTags.Answer).assertDoesNotExist()
    }

    // ---- The aura ----------------------------------------------------------------------------------------------------

    @Test fun theAuraMovesWhileTheScreenRings() {
        show()
        advance(200)
        assertEquals(AuraAnimating, auraState())
    }

    @Test fun theAuraStopsWhenReduceMotionIsOn() {
        show(reduceMotion = true)
        advance(200)
        assertEquals(AuraStill, auraState())
    }

    @Test fun theAuraRestsWhenTheRingerIsSilenced() {
        show()
        advance(200)
        ui = ui.copy(silenced = true)
        advance(200)
        assertEquals(AuraStill, auraState())
    }

    @Test fun aShotAuraIsAFixedFrame() {
        show(aura = AuraMode.Shot)
        advance(200)
        assertEquals(AuraStill, auraState())
    }

    // ---- The waiting-call card ---------------------------------------------------------------------------------------

    private fun showCard(waiting: WaitingUi) {
        composeFrame { WaitingCallCard(waiting, incomingStrings, { actions += it }) }
        advance(100)
    }

    private val waiting = IncomingHarness(waitingSnapshot()).ui.waiting!!

    @Test fun theCardNamesTheCallerAndOffersFourPills() {
        showCard(waiting)
        compose.onNodeWithTag(IncomingTags.WaitingEnd).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.WaitingHold).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.WaitingMessage).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.WaitingDecline).assertIsDisplayed()
    }

    @Test fun eachPillSendsItsAction() {
        showCard(waiting)
        for ((tag, expected) in listOf(
            IncomingTags.WaitingEnd to IncomingAction.EndAndAnswer,
            IncomingTags.WaitingHold to IncomingAction.HoldAndAnswer,
            IncomingTags.WaitingMessage to IncomingAction.OpenWaitingReplies,
            IncomingTags.WaitingDecline to IncomingAction.DeclineWaiting,
        )) {
            actions.clear()
            compose.onNodeWithTag(tag).performClick()
            advance(100)
            assertEquals(tag, listOf(expected), actions)
        }
    }

    @Test fun holdIsDimmedWhenTheLiveCallCannotBeHeld() {
        showCard(waiting.copy(canHold = false))
        compose.onNodeWithTag(IncomingTags.WaitingHold).performClick()
        advance(100)
        assertTrue("a dimmed pill sends nothing", actions.isEmpty())
        compose.onNodeWithTag(IncomingTags.WaitingEnd).performClick()
        advance(100)
        assertEquals(listOf<IncomingAction>(IncomingAction.EndAndAnswer), actions)
    }

    // ---- The sheets --------------------------------------------------------------------------------------------------

    @Test fun theReplySheetPicksATextOrCustom() {
        val picked = mutableListOf<String>()
        var custom = 0
        composeFrame {
            androidx.compose.foundation.layout.Column {
                RepliesSheet(
                    persistentListOf("Can't talk now.", "Call you later."),
                    incomingStrings,
                    { picked += it },
                    { custom++ },
                )
            }
        }
        advance(100)
        compose.onNodeWithTag(IncomingTags.ReplyPrefix + 1).performClick()
        compose.onNodeWithTag(IncomingTags.ReplyCustom).performClick()
        advance(100)
        assertEquals(listOf("Call you later."), picked)
        assertEquals(1, custom)
    }
}
