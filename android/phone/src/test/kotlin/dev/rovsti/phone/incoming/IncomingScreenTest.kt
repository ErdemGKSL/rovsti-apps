package dev.rovsti.phone.incoming

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
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
        compose.onNodeWithTag(IncomingTags.Number).assertTextEquals("+90 555 111 22 33")
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

    @Test fun lockedShowsTheSlideTrackAndASmallDeclineInsteadOfTheDiscs() {
        show(ui.copy(locked = true))
        compose.onNodeWithTag(IncomingTags.Answer).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Decline).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Remind).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Message).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Silence).assertDoesNotExist()
        val track = compose.onNodeWithTag(IncomingTags.Answer).fetchSemanticsNode().boundsInRoot
        val decline = compose.onNodeWithTag(IncomingTags.Decline).fetchSemanticsNode().boundsInRoot
        val remind = compose.onNodeWithTag(IncomingTags.Remind).fetchSemanticsNode().boundsInRoot
        assertTrue("Decline is under the track", decline.top >= track.bottom)
        assertTrue("Remind me is above the track", remind.bottom <= track.top)
        assertEquals(with(compose.density) { 76.dp.toPx() }, track.height, 1f)
    }

    @Test fun declineUnderTheTrackDeclinesAndTheTrackItselfAnswersOnAccessibilityClick() {
        show(ui.copy(locked = true))
        compose.onNodeWithTag(IncomingTags.Decline).performClick()
        assertEquals(
            listOf<IncomingAction>(IncomingAction.Decline),
            actions.filterNot { it is IncomingAction.FirstFrame },
        )
    }

    @Test fun withoutANumberThereIsNoMessageCircle() {
        show(ui.copy(canMessage = false))
        compose.onNodeWithTag(IncomingTags.Message).assertDoesNotExist()
    }

    @Test fun theSimChipNamesTheLine() {
        show(ui.copy(caller = caller.copy(sim = SimChip("Vodafone", 0xFFFF453A.toInt()))))
        compose.onNodeWithTag(IncomingTags.Sim).assertIsDisplayed()
    }

    // ---- F20: plain secondary actions ------------------------------------------------------------------------------

    private fun luminance(argb: Int): Int = ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    private fun nodeLuminance(tag: String): Long {
        val bmp = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        var sum = 0L
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) sum += luminance(bmp.getPixel(x, y))
        return sum
    }

    @Test fun theSecondaryActionsHaveNoDiscBehindThem() {
        show(aura = AuraMode.Shot)
        val root = compose.onRoot().captureToImage().asAndroidBitmap()
        val density = compose.density
        for (tag in listOf(IncomingTags.Remind, IncomingTags.Message, IncomingTags.Silence)) {
            val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val y = (b.top + with(density) { 22.dp.toPx() }).toInt()
            val inside = root.getPixel((b.center.x - with(density) { 20.dp.toPx() }).toInt(), y)
            val outside = root.getPixel((b.center.x - with(density) { 70.dp.toPx() }).toInt(), y)
            for (shift in listOf(16, 8, 0)) {
                val d = kotlin.math.abs(((inside shr shift) and 0xFF) - ((outside shr shift) and 0xFF))
                assertTrue("$tag: a disc would show here (channel diff $d)", d < 30)
            }
        }
    }

    @Test fun theSecondaryTargetsAreAtLeastFortyEightDpAndButtons() {
        show()
        val min = with(compose.density) { 48.dp.toPx() } - 1f
        for (tag in listOf(IncomingTags.Remind, IncomingTags.Message, IncomingTags.Silence)) {
            val node = compose.onNodeWithTag(tag)
            val b = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width", b.width >= min)
            assertTrue("$tag height", b.height >= min)
            assertEquals(Role.Button, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test fun aPressedSecondaryActionDims() {
        show(aura = AuraMode.Shot)
        val rest = nodeLuminance(IncomingTags.Remind)
        compose.onNodeWithTag(IncomingTags.Remind).performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val pressed = nodeLuminance(IncomingTags.Remind)
        assertTrue("pressed $pressed < rest $rest", pressed < rest)
        compose.onNodeWithTag(IncomingTags.Remind).performTouchInput { up() }
        advance(400)
        assertEquals(rest, nodeLuminance(IncomingTags.Remind))
    }

    @Test fun theNumberLineIsTheOneTheModelGaveAndTheNameIsLight() {
        show(ui.copy(caller = caller.copy(title = "A very long caller name that needs two lines and then some more")))
        compose.onNodeWithTag(IncomingTags.Name).assertIsDisplayed()
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

    // ---- WhatsApp calls (E5.8b) ------------------------------------------------------------------------------------------

    private fun whatsAppUi(): IncomingUi =
        ui.copy(caller = caller.copy(whatsapp = true, photoJid = "905551112233@s.whatsapp.net"), canMessage = false)

    @Test fun aWhatsAppCallReplacesTheIncomingLabelWithWhatsAppAudioAndTheGlyph() {
        show(whatsAppUi())
        compose.onNodeWithTag(IncomingTags.WaAudio).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.Answer).assertIsDisplayed()
        compose.onAllNodesWithTag(IncomingTags.Message).assertCountEquals(0)
    }

    @Test fun aCellularCallHasNoWhatsAppLine() {
        show()
        compose.onAllNodesWithTag(IncomingTags.WaAudio).assertCountEquals(0)
    }

    @Test fun aWaitingWhatsAppOfferHasEndAndAnswerAndDeclineOnly() {
        showCard(
            WaitingUi(
                3,
                "Ada Lovelace",
                "+905551112233",
                "k",
                relabelled = false,
                canHold = false,
                canMessage = false,
                whatsapp = true,
            ),
        )
        compose.onNodeWithTag(IncomingTags.WaitingEnd).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.WaitingDecline).assertIsDisplayed()
        compose.onNodeWithTag(IncomingTags.WaAudio).assertIsDisplayed()
        compose.onAllNodesWithTag(IncomingTags.WaitingHold).assertCountEquals(0)
        compose.onAllNodesWithTag(IncomingTags.WaitingMessage).assertCountEquals(0)
        compose.onNodeWithTag(IncomingTags.WaitingEnd).performClick()
        compose.onNodeWithTag(IncomingTags.WaitingDecline).performClick()
        assertEquals(listOf(IncomingAction.EndAndAnswer, IncomingAction.DeclineWaiting), actions)
    }
}
