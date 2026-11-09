package dev.rovsti.contacts.card

import dev.rovsti.platform.contacts.ContactCallOptions
import dev.rovsti.platform.contacts.ContactResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E4.13 on the REAL Rust core (`contacts.options` words) with the in-memory provider and blocklist: the ringtone result
 * is stored and named, the voicemail switch asks before it turns on, and Block This Caller works on every number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallOptionsViewModelTest {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(Job)
            ?.cancel()
    }

    private fun rig(
        id: Long = 1,
        canBlock: Boolean = true,
    ): CardRig = CardRig(id, canBlock = canBlock).also { rig = it }

    private fun toast(r: CardRig): String = (r.callEffect() as CallOptionsEffect.Toast).text

    @Test fun theSectionStartsWithTheDefaultRingtoneAndNoVoicemail() {
        val r = rig()
        val state = r.callsWhere { it.loaded && it.block != BlockRow.Hidden }
        assertEquals("Default", state.ringtone)
        assertFalse(state.voicemail)
        assertEquals(BlockRow.Available(blocked = false), state.block)
    }

    @Test fun theRingtoneRowOpensThePickerOnTheCurrentTone() {
        val r = rig()
        r.callsWhere { it.loaded }
        r.callOptions.onAction(CallOptionsAction.ChooseRingtone)
        assertEquals(CallOptionsEffect.PickRingtone(null), r.callEffect())
    }

    @Test fun aPickedRingtoneIsSavedAndNamed() {
        val r = rig()
        r.callsWhere { it.loaded }
        r.callOptions.onAction(
            CallOptionsAction.RingtonePicked(ContactCallOptions.Pick.Chosen("content://media/audio/9")),
        )
        assertEquals("Ringtone set to content://media/audio/9", toast(r))
        val stored = runBlocking { r.repo.options(1) } as ContactResult.Ok
        assertEquals("content://media/audio/9", stored.value.ringtoneUri)
        assertEquals("content://media/audio/9", r.callsWhere { it.ringtone != "Default" }.ringtone)
    }

    @Test fun silentAndDefaultAreWordsNotUris() {
        val r = rig()
        r.callsWhere { it.loaded }
        r.callOptions.onAction(CallOptionsAction.RingtonePicked(ContactCallOptions.Pick.Chosen("")))
        assertEquals("Ringtone set to silent", toast(r))
        assertEquals("Silent", r.callsWhere { it.ringtone == "Silent" }.ringtone)
        r.callOptions.onAction(CallOptionsAction.RingtonePicked(ContactCallOptions.Pick.Chosen(null)))
        assertEquals("Ringtone reset to default", toast(r))
    }

    @Test fun aCancelledPickerChangesNothing() {
        val r = rig()
        r.callsWhere { it.loaded }
        r.callOptions.onAction(CallOptionsAction.RingtonePicked(ContactCallOptions.Pick.Cancelled))
        r.callOptions.onAction(CallOptionsAction.ChooseRingtone)
        assertEquals("the next effect is the picker, not a toast", CallOptionsEffect.PickRingtone(null), r.callEffect())
    }

    @Test fun turningVoicemailOnAsksFirstAndOnlyTheAnswerChangesIt() {
        val r = rig()
        r.callsWhere { it.loaded }
        r.callOptions.onAction(CallOptionsAction.SetVoicemail(true))
        val ask = r.callEffect() as CallOptionsEffect.AskVoicemail
        assertEquals("Send Ayşe's calls to voicemail?", ask.confirm.title)
        assertEquals("Their calls won't ring this phone.", ask.confirm.body)
        assertFalse("nothing changed before the answer", r.callOptions.state.value.voicemail)
        r.callOptions.onAction(CallOptionsAction.ConfirmVoicemail)
        assertEquals("Calls go to voicemail", toast(r))
        assertTrue(r.callsWhere { it.voicemail }.voicemail)
    }

    @Test fun turningVoicemailOffDoesNotAsk() {
        val r = rig()
        r.callsWhere { it.loaded }
        r.callOptions.onAction(CallOptionsAction.SetVoicemail(true))
        r.callEffect()
        r.callOptions.onAction(CallOptionsAction.ConfirmVoicemail)
        r.callEffect()
        r.callsWhere { it.voicemail }
        r.callOptions.onAction(CallOptionsAction.SetVoicemail(false))
        assertEquals("the very next effect is the notice", "Calls ring again", toast(r))
        assertFalse(r.callsWhere { !it.voicemail }.voicemail)
    }

    @Test fun blockingAsksThenBlocksEveryNumberOfTheContact() {
        val r = rig(1)
        r.callsWhere { it.block == BlockRow.Available(false) }
        r.callOptions.onAction(CallOptionsAction.ToggleBlock)
        val ask = r.callEffect() as CallOptionsEffect.AskBlock
        assertEquals("Block Ayşe Yılmaz?", ask.title)
        assertEquals("Block Contact", ask.actionLabel)
        assertTrue("two numbers: $ask", ask.message.contains("all their numbers"))
        assertFalse("nothing is blocked before the answer", runBlocking { r.blocklist.isBlocked("+49 30 1234567") })
        r.callOptions.onAction(CallOptionsAction.ConfirmBlock)
        assertEquals("Blocked", toast(r))
        runBlocking {
            assertTrue(r.blocklist.isBlocked("+49 30 1234567"))
            assertTrue(r.blocklist.isBlocked("+90 532 111 22 33"))
        }
        assertEquals(BlockRow.Available(blocked = true), r.callsWhere { it.block == BlockRow.Available(true) }.block)
    }

    @Test fun aBlockedContactOffersUnblockWithoutAsking() {
        val r = rig(2)
        runBlocking { r.blocklist.block("0532 123 45 67") }
        r.callsWhere { it.block == BlockRow.Available(true) }
        r.callOptions.onAction(CallOptionsAction.ToggleBlock)
        assertEquals("Unblocked", toast(r))
        runBlocking { assertFalse(r.blocklist.isBlocked("0532 123 45 67")) }
        assertEquals(BlockRow.Available(false), r.callsWhere { it.block == BlockRow.Available(false) }.block)
    }

    @Test fun withoutTheDefaultPhoneRoleTheRowExplainsAndOpensTheRoleSettings() {
        val r = rig(1, canBlock = false)
        assertEquals(BlockRow.NeedsRole, r.callsWhere { it.loaded && it.block == BlockRow.NeedsRole }.block)
        r.callOptions.onAction(CallOptionsAction.ToggleBlock)
        assertEquals(CallOptionsEffect.OpenRoleSettings, r.callEffect())
        assertTrue(NEEDS_ROLE_TEXT.contains("default phone app"))
    }

    @Test fun aContactWithoutNumbersHasNoBlockRow() {
        val r = rig(4)
        assertEquals(BlockRow.Hidden, r.callsWhere { it.loaded }.block)
    }

    @Test fun aBlockedNumberFromElsewhereShowsUp() {
        val r = rig(2)
        r.callsWhere { it.block == BlockRow.Available(false) }
        runBlocking { r.blocklist.block("0532 123 45 67") }
        assertTrue(r.callsWhere { it.block == BlockRow.Available(true) }.block is BlockRow.Available)
    }
}
