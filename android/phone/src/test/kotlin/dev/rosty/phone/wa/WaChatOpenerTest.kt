package dev.rosty.phone.wa

import androidx.test.core.app.ApplicationProvider
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.design.overlay.MenuItem
import dev.rosty.phone.data.eventually
import dev.rosty.phone.details.DetailsSample
import dev.rosty.phone.details.DetailsStrings
import dev.rosty.phone.details.PersonAction
import dev.rosty.phone.details.personMenu
import dev.rosty.phone.recents.RecentsAction
import dev.rosty.phone.recents.RecentsSample
import dev.rosty.phone.recents.RecentsStrings
import dev.rosty.phone.recents.recentsMenu
import dev.rosty.wa.WaLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WA-R2: "WhatsApp message" on a person hands the chat to Rosty Messages. The chat is the phone JID of the number
 * (digits only), the item exists only while Messages is installed, and a number without a country opens nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaChatOpenerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val link =
        MutableStateFlow<WaLinkState>(
            WaLinkState.Connected(
                InstanceStatus("inst1", InstanceState.Connected, ownJid = "905550000000:7@s.whatsapp.net"),
            ),
        )
    private val started = CopyOnWriteArrayList<Pair<String, String>>()
    private val notices = WaNotices()
    private val said = CopyOnWriteArrayList<WaNotice>()

    @Volatile private var installed = true

    @Volatile private var taken = true

    private val opener =
        WaChatOpener(
            link = link,
            // As a phone whose home country could not be inferred: only a `+` number is international.
            e164 = { typed -> typed.takeIf { it.startsWith("+") }?.filter { it == '+' || it.isDigit() } },
            installed = { installed },
            start = { jid, name ->
                started += jid to name
                taken
            },
            notices = notices,
            scope = scope,
            io = Dispatchers.Unconfined,
        )

    init {
        scope.launch { notices.flow.collect { said += it } }
    }

    @After fun stop() {
        scope.cancel()
    }

    @Test fun theChatIsThePhoneJidOfTheNumberWithThePersonsName() =
        runBlocking {
            opener.open("+90 532 100 00 01", "Ayşe")
            eventually("started") { started.isNotEmpty() }
            assertEquals(listOf("905321000001@s.whatsapp.net" to "Ayşe"), started.toList())
            assertTrue(said.isEmpty())
        }

    @Test fun aNationalNumberTakesTheCountryOfTheLinkedAccount() =
        runBlocking {
            opener.open("0532 100 00 01")
            eventually("started") { started.isNotEmpty() }
            assertEquals(listOf("905321000001@s.whatsapp.net" to ""), started.toList())
        }

    @Test fun aNumberWithoutACountryOpensNothing() =
        runBlocking {
            link.value = WaLinkState.None
            opener.open("0532 100 00 01")
            eventually("refused") { said.isNotEmpty() }
            assertEquals(listOf<WaNotice>(WaNotice.Refused(WaRefusal.NeedsCountryCode)), said.toList())
            assertTrue(started.isEmpty())
        }

    @Test fun theItemFollowsWhetherMessagesIsInstalled() =
        runBlocking {
            assertFalse("unknown until asked", opener.available.value)
            opener.refresh()
            eventually("installed") { opener.available.value }
            installed = false
            opener.refresh()
            eventually("removed") { !opener.available.value }

            // Removed after the menu was built: the tap says it failed and the item goes.
            installed = true
            opener.refresh()
            eventually("installed") { opener.available.value }
            taken = false
            opener.open("+905321000001")
            eventually("failed") { said.isNotEmpty() }
            assertEquals(listOf<WaNotice>(WaNotice.Failed), said.toList())
            assertFalse(opener.available.value)
        }

    // ---- the menus

    private val resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    private fun List<MenuItem>.labels() = filterIsInstance<MenuItem.Action>().map { it.label }

    @Test fun theRecentsRowMenuOffersTheChatOnlyWithMessagesInstalled() {
        val strings = RecentsStrings(resources)
        val row = RecentsSample.row("1", "Ayşe")
        val actions = mutableListOf<RecentsAction>()
        assertFalse(strings.whatsAppMessage in recentsMenu(row, strings, actions::add, whatsApp = true).labels())

        val menu = recentsMenu(row, strings, actions::add, whatsApp = true, whatsAppMessage = true)
        val labels = menu.labels()
        assertEquals(
            "right under the WhatsApp call",
            labels.indexOf(strings.whatsApp) + 1,
            labels.indexOf(strings.whatsAppMessage),
        )
        val item = menu.filterIsInstance<MenuItem.Action>().first { it.label == strings.whatsAppMessage }
        assertTrue(item.enabled)
        item.onClick()
        assertEquals(listOf<RecentsAction>(RecentsAction.WhatsAppMessage("1")), actions)

        // WhatsApp is not linked in Phone: shown, like the WhatsApp call, but not enabled.
        val unlinked = recentsMenu(row, strings, actions::add, whatsApp = false, whatsAppMessage = true)
        assertFalse(unlinked.filterIsInstance<MenuItem.Action>().first { it.label == strings.whatsAppMessage }.enabled)
    }

    @Test fun thePersonSheetMenuOffersTheChatOnlyWithMessagesInstalled() {
        val strings = DetailsStrings(resources)
        val ui = DetailsSample.person()
        val actions = mutableListOf<PersonAction>()
        assertFalse(strings.menuWhatsAppMessage in personMenu(ui, strings, actions::add, whatsApp = true).labels())

        val menu = personMenu(ui, strings, actions::add, whatsApp = true, whatsAppMessage = true)
        val item = menu.filterIsInstance<MenuItem.Action>().first { it.label == strings.menuWhatsAppMessage }
        assertTrue(item.enabled)
        item.onClick()
        assertEquals(listOf<PersonAction>(PersonAction.WhatsAppMessage), actions)
    }
}
