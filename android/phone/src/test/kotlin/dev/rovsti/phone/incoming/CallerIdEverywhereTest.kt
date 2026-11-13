package dev.rovsti.phone.incoming

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.os.Looper
import android.telecom.Call
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.phone.CallActionReceiver
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.MainActivity
import dev.rovsti.phone.data.PeopleIndex
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.identity.CallerIds
import dev.rovsti.platform.identity.CallerLookup
import dev.rovsti.platform.identity.DeviceContactsSource
import dev.rovsti.platform.identity.PhoneNumbers
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.telephony.CallNotifications
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.FakeInCallHost
import dev.rovsti.platform.telephony.fakeCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F39, the owner's bug: "In Recents it finds the right name, but when I call, it can still show the number." The call
 * service named calls once, with Android's `PhoneLookup`, which misses a contact saved as `0532 …` when the call is
 * `+90532…`. Now the call screens' snapshot and the CallStyle notifications are named by the same caller-ID as Recents
 * (the core's number book, through [CallerLookup]), asked again when the contacts change; an unknown number reads
 * formatted, and a withheld caller or a short code gets no one's name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PhoneFullScreenShadow::class])
class CallerIdEverywhereTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val host by lazy { HostRust("rovsti-caller-id") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = FakeInCallHost()

    private val repo =
        FakeContactsRepository(
            listOf(
                ContactDraft(id = 1, given = "Ayşe", family = "Yılmaz", numbers = listOf(DraftField("0532 123 45 67"))),
                ContactDraft(id = 2, given = "Taksi", numbers = listOf(DraftField("+90 532 444 01 23"))),
            ),
        )
    private lateinit var people: PeopleIndex
    private lateinit var ids: CallerIds

    @Before fun setUp() {
        people =
            PeopleIndex(
                contacts = repo,
                permission = MutableStateFlow(PermissionState.Granted),
                search = NativeSearchOps(host.core),
                ops = NativePhoneOps(host.core),
                scope = scope,
                e164 = { PhoneNumbers.e164(it, "TR") },
                homeCc = { "90" },
            )
        ids =
            CallerIds(
                listOf(
                    DeviceContactsSource(people, e164 = { PhoneNumbers.e164(it, "TR") }, homeCc = { "90" }) {
                        people.displayName(it)
                    },
                ),
            )
        await("the index is built") { people.ready }
        CallerLookup.install { number -> runBlocking { ids.resolve(number) } }
        PlatformComponents.install(
            PlatformComponents(mainActivity = MainActivity::class.java, callAction = CallActionReceiver::class.java),
        )
        CallRegistry.init(app)
        CallRegistry.onServiceBound(service)
    }

    @After fun tearDown() {
        CallRegistry.onServiceUnbound()
        CallerLookup.install(null)
        scope.cancel()
    }

    /** Runs the main Looper until [done] (the registry's lookup runs on its own thread, then posts back). */
    private fun await(
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            assertTrue("timed out: $what", System.nanoTime() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun only() = CallRegistry.state.value.single()

    private fun Notification.caller(): Person? =
        extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)

    private fun incoming() = notifications.getNotification(CallNotifications.INCOMING_ID)

    @Test fun anIncomingCallFromTheE164FormIsNamedOnTheScreenAndInTheNotification() {
        CallRegistry.onCallAdded(fakeCall("+905321234567"))
        await("the caller is named") { only().displayName != null }
        assertEquals("Ayşe Yılmaz", only().displayName)
        assertEquals(1L, only().contactId)
        await("the notification follows") { incoming()?.caller()?.name == "Ayşe Yılmaz" }
        // The number line under the name reads formatted, never the dialled digits.
        assertEquals("+90 532 123 45 67", CallRegistry.incomingNow?.subtitle)
    }

    @Test fun anOutgoingCallPlacedInAnyFormIsNamedInTheOngoingNotification() {
        val manager = app.getSystemService(NotificationManager::class.java)
        for (dialled in listOf("+90 532 123 45 67", "00905321234567", "05321234567")) {
            val call = fakeCall(dialled, Call.STATE_DIALING)
            CallRegistry.onCallAdded(call)
            await("$dialled is named") { only().displayName == "Ayşe Yılmaz" }
            await("the ongoing notification names $dialled") {
                val shown =
                    notifications.getNotification(CallNotifications.ONGOING_ID)
                        ?: service.foreground.lastOrNull()?.second
                shown?.caller()?.name == "Ayşe Yılmaz"
            }
            CallRegistry.onCallRemoved(call)
            shadowOf(Looper.getMainLooper()).idle()
            manager.cancel(CallNotifications.ONGOING_ID)
            service.foreground.clear()
        }
    }

    @Test fun aContactRenamedDuringTheCallIsShownWithoutTheNumberInBetween() {
        CallRegistry.onCallAdded(fakeCall("+905321234567"))
        await("named") { only().displayName == "Ayşe Yılmaz" }
        val before = people.revision.value
        runBlocking {
            repo.save(
                ContactDraft(id = 1, given = "Ayşe", family = "Demir", numbers = listOf(DraftField("0532 123 45 67"))),
            )
        }
        await("the index follows the contacts") { people.revision.value > before }
        // What the app's container does on every new revision of its resolver.
        CallRegistry.refreshNames()
        val seen = mutableListOf<String?>()
        await("renamed") {
            seen += only().displayName
            only().displayName == "Ayşe Demir"
        }
        assertTrue("never the bare number: $seen", seen.all { it == "Ayşe Yılmaz" || it == "Ayşe Demir" })
        await("the notification follows") { incoming()?.caller()?.name == "Ayşe Demir" }
    }

    @Test fun anUnknownNumberReadsFormatted() {
        CallRegistry.onCallAdded(fakeCall("+905559998877"))
        await("formatted") { CallRegistry.incomingNow?.title == "+90 555 999 88 77" }
        assertNull(only().displayName)
    }

    @Test fun aWithheldCallerIsUnknown() {
        CallRegistry.onCallAdded(fakeCall(""))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(only().displayName)
        assertEquals("Unknown", CallRegistry.incomingNow?.title)
    }

    @Test fun aShortCodeIsNeverNamedAfterAFullNumberThatEndsLikeIt() {
        // "Taksi" is +90 532 444 01 23; the service number 444 01 23 is someone else.
        CallRegistry.onCallAdded(fakeCall("4440123"))
        await("the lookup ran") { CallRegistry.incomingNow != null }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(only().displayName)
        assertEquals("4440123", CallRegistry.incomingNow?.title)
    }
}
