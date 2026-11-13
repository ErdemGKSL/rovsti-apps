package dev.rovsti.phone.wa

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.shared.InferHomeCcRequest
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.model.wa.CallStarted
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.eventually
import dev.rovsti.platform.PlatformEvent
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.wa.DefaultWaRepository
import dev.rovsti.wa.FakeCallPump
import dev.rovsti.wa.FakeWaGateway
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaLinkStore
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The JID a WhatsApp call from Phone goes to (owner bug, round 14): the gateway only knows `<digits>@s.whatsapp.net`
 * (it answers 400 to `+90…@s.whatsapp.net`), and a national number such as `0532 123 45 67` must get the country code
 * even when Phone could not infer the home country from the contacts (they are all stored as `+90 …`). Before the fix
 * the launcher sent `05321234567@s.whatsapp.net`, which is nobody on WhatsApp. Real core (`format.render`,
 * `format.infer_home_cc`, `phone.wa_tracker_can_place`), fake gateway. The numbers are made up.
 */
class WaCallLauncherTest {
    private val host = HostRust("rovsti-wa-launcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val fake =
        FakeWaGateway(link = link).apply {
            status =
                Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected, ownJid = "905550000000:7@s.whatsapp.net"))
        }
    private val placedTo = CopyOnWriteArrayList<String>()
    private val repo =
        DefaultWaRepository(
            WaLinkStore(fake.ops),
            scope,
            fake.opener,
            fake.waEvents,
            MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8),
        )
    private val waCalls =
        WaCalls(repo, MutableStateFlow<PermissionState>(PermissionState.Granted), FakeCallPump(), scope)
    private val shared = NativeSharedOps(host.core)
    private val notices = WaNotices()
    private val said = CopyOnWriteArrayList<WaNotice>()
    private val started =
        java.util.concurrent.atomic
            .AtomicInteger()

    /** The contacts of the owner: every number international, so there is no national/international pair to infer from. */
    private val book = listOf("+90 555 111 22 33", "+90 555 444 55 66", "+49 151 2345678")

    /** As `PhoneContainer.e164`: the home country inferred from the contacts' numbers. */
    private suspend fun e164(typed: String): String? {
        val home = (shared.inferHomeCc(InferHomeCcRequest(book)) as? Outcome.Ok)?.value?.homeCc
        return (shared.render(RenderRequest(typed, NumberForm.E164, home)) as? Outcome.Ok)?.value?.text
    }

    private val launcher =
        WaCallLauncher(
            waCalls = waCalls,
            link = repo.linkState,
            ops = host.ops,
            busy = { false },
            e164 = ::e164,
            notices = notices,
            scope = scope,
            onStarted = { started.incrementAndGet() },
        )

    init {
        fake.core.on("wa.place_call") { request ->
            placedTo += (request as JsonObject)["to"]!!.jsonPrimitive.content
            Outcome.Ok(
                Envelope.json.encodeToJsonElement(CallStarted.serializer(), CallStarted("call-${placedTo.size}")),
            )
        }
        scope.launch { notices.flow.collect { said += it } }
        repo.start()
        waCalls.start()
        runBlocking { eventually("the link is connected") { repo.linkState.value is WaLinkState.Connected } }
    }

    @After fun stop() {
        scope.cancel()
    }

    private fun placed(number: String): String? {
        val before = placedTo.size
        val notes = said.size
        launcher.place(number)
        runBlocking { eventually("a call or a notice for $number") { placedTo.size > before || said.size > notes } }
        return placedTo.getOrNull(before)
    }

    @Test fun `the home country is not inferred from an all-international book`() =
        runBlocking {
            assertNull((shared.inferHomeCc(InferHomeCcRequest(book)) as Outcome.Ok).value.homeCc)
        }

    @Test fun `an international number calls its digits only`() {
        assertEquals("905551112233@s.whatsapp.net", placed("+90 555 111 22 33"))
        assertEquals("905551112233@s.whatsapp.net", placed("+905551112233"))
    }

    @Test fun `a national number gets the country of the linked WhatsApp number`() {
        assertEquals("905321234567@s.whatsapp.net", placed("0532 123 45 67"))
        assertEquals("905321234567@s.whatsapp.net", placed("(0532) 123 45 67"))
        assertEquals("905321234567@s.whatsapp.net", placed("532 123 45 67"))
        assertEquals("905321234567@s.whatsapp.net", placed("90532 123 45 67"))
        assertEquals("905321234567@s.whatsapp.net", placed("0090 532 123 45 67"))
    }

    @Test fun `no JID is ever sent with a plus, a space or a trunk zero`() {
        for (n in listOf("+90 555 111 22 33", "0532 123 45 67", "(0532) 123 45 67", "90532 123 45 67")) placed(n)
        assertTrue(placedTo.toString(), placedTo.all { Regex("[1-9][0-9]{6,14}@s\\.whatsapp\\.net").matches(it) })
    }

    /** Owner bug 2026-11-13: a WhatsApp call placed from a sheet left the sheet open. A started call leaves the screen. */
    @Test fun `a started call leaves the screen and a refused one does not`() {
        placed("+90 555 111 22 33")
        runBlocking { eventually("the leave was signalled") { started.get() == 1 } }
        val notes = said.size
        launcher.place("12")
        runBlocking { eventually("the refusal") { said.size > notes } }
        assertEquals("a refused call stays on the screen", 1, started.get())
    }

    /** A Turkish phone showed the core's English sentences: the refusal is a reason the screen words itself. */
    @Test fun `a refusal carries its reason, never an English sentence`() {
        assertEquals(WaRefusal.NoNumber, refusalOf(connected = true, busy = false, number = "  "))
        assertEquals(WaRefusal.NotLinked, refusalOf(connected = false, busy = true, number = "+905551112233"))
        assertEquals(WaRefusal.Busy, refusalOf(connected = true, busy = true, number = "+905551112233"))
        val notes = said.size
        launcher.place("12")
        runBlocking { eventually("the refusal") { said.size > notes } }
        assertEquals(WaNotice.Refused(WaRefusal.NeedsCountryCode), said.last())
    }

    @Test fun `own country helper`() {
        val own = "905550000000"
        assertEquals("+905321234567", WaCallLauncher.withOwnCountry("0532 123 45 67", own))
        assertEquals("+905321234567", WaCallLauncher.withOwnCountry("905321234567", own))
        assertEquals("+4915123456789", WaCallLauncher.withOwnCountry("0049 151 23456789", own))
        assertEquals("+905321234567", WaCallLauncher.withOwnCountry("+90 532 123 45 67", null))
        assertNull(WaCallLauncher.withOwnCountry("0532 123 45 67", null))
        assertNull(WaCallLauncher.withOwnCountry("112", own))
        assertEquals("905321234567@s.whatsapp.net", WaCallLauncher.jidOf("+90 (532) 123-45-67"))
    }
}
