package dev.rovsti.contacts.people

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.people.sync.SliceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * People sync v2: WhatsApp's limit (a 429 with a hint), the gateway's bucket and an old gateway's silent throttle
 * (profiles with `fetched_ms: 0`), on the real planner. Short waits are slept inline, long ones end the slice with the
 * time to continue, and nothing already stored is lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleSyncRateLimitTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = (context as PeopleTestApp).container
    private val sync get() = container.peopleSync

    private fun jid(i: Int) = "90532000%04d@s.whatsapp.net".format(i)

    @Before fun script() {
        container.useRealPeople()
        container.numbers = (1..30).map { "+90532000%04d".format(it) }
        for (i in 1..30 step 3) {
            container.gw.onWhatsapp += jid(i)
            container.gw.profiles[jid(i)] = WaProfile(jid(i), pushName = "P$i", pictureId = "$i", fetchedMs = 1)
            container.gw.pictures[jid(i)] = PictureAnswer("image", id = "$i", dataB64 = b64("p$i"))
        }
        container.connect()
    }

    private fun slice(): SliceResult = runBlocking { sync.runSlice() }

    @Test fun aShortWhatsappLimitIsSleptInlineAndTheSameBatchIsAskedAgain() {
        var refused = 0
        val known = container.gw.profiles.toMap()
        container.gw.gateway.profiles = { jids ->
            if (refused++ == 0) {
                rateLimited(retryAfterMs = 70_000, source = "whatsapp")
            } else {
                Outcome.Ok(jids.mapNotNull(known::get))
            }
        }
        assertEquals(SliceResult.Done, slice())
        assertEquals(10, container.book().values.count { it.name != null })
        assertTrue("the wait honours the hint", container.pauses.any { it >= 70_000 })
    }

    @Test fun aLongLimitEndsTheSliceWithTheTimeToContinueAndKeepsWhatWasStored() {
        container.gw.lookupBucket = FakeBucket(burst = 15, perSec = 0.0, clock = { container.now })
        val result = slice()
        assertTrue("$result", result is SliceResult.Waiting)
        val until = (result as SliceResult.Waiting).untilMs
        assertTrue("at least the first backoff", until - container.now >= 60_000)
        val stored = container.book().size
        assertTrue("the first batch's people are stored", stored in 1..5)
        assertEquals(SyncOutcome.Partial, sync.status.value.outcome)
        // Before the time is up the planner refuses to start (both buttons wait, D8).
        assertEquals(
            SliceResult.Refused(
                sync.runner.status.value.stored
                    ?.notBeforeMs,
            ),
            slice(),
        )
        // After it, the run continues where it stopped: the first batch is not asked again.
        container.gw.lookupBucket = null
        container.gw.lookups.clear()
        container.now = until + 1
        assertEquals(SliceResult.Done, slice())
        assertTrue(
            container.gw.lookups
                .flatten()
                .none { it == jid(1) },
        )
        assertEquals(10, container.book().size)
    }

    @Test fun aRateLimitedPictureIsNotAFailure() {
        var refusals = 0
        container.gw.pictureOverride = { body ->
            if (body.jid == jid(1) && refusals++ == 0) rateLimited(source = "whatsapp") else null
        }
        assertEquals(SliceResult.Done, slice())
        assertTrue(
            "the picture was asked again after the wait",
            container.gw.pictureAsks.count { it.jid == jid(1) } >= 2,
        )
        assertTrue(container.book().getValue(jid(1)).pictureFile != null)
    }

    @Test fun anOldGatewaysUnansweredProfilesAreAThrottleNotAnAnswer() {
        container.gw.profiles.keys
            .forEach { container.gw.profiles[it] = WaProfile(it) }
        val result = slice()
        assertTrue("$result", result is SliceResult.Waiting)
        assertTrue("no name came from an unanswered profile", container.book().values.all { it.name == null })
    }
}
