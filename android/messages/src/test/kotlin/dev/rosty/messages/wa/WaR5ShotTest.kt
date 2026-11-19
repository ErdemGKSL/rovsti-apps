package dev.rosty.messages.wa

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.wa.Location
import dev.rosty.core.model.wa.Poll
import dev.rosty.core.model.wa.PollOption
import dev.rosty.core.model.wa.SharedContact
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.WhatsAppTab
import dev.rosty.messages.chromeFor
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.messagesTabItems
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.messages.wa.rich.RichCard
import dev.rosty.messages.wa.rich.RichCardActions
import dev.rosty.messages.wa.rich.presentPlacePreview
import dev.rosty.messages.wa.rich.presentPollSheet
import dev.rosty.platform.location.Fix
import dev.rosty.shell.RostyShell
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Screenshots of E6-WA R5, light and dark: a poll bubble, a place card (no map tiles in tests: the placeholder with
 * the pin), contact cards, the header's presence line, the poll create sheet and the place send preview.
 */
@Config(sdk = [35])
class WaR5ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val scope: CoroutineScope = MainScope()
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val minute = 60_000L

    @After fun stop() = scope.cancel()

    private fun row(
        chat: String,
        id: String,
        kind: WaSyncKind,
        ago: Int,
        sender: String? = chat,
        fromMe: Boolean = false,
    ): WaSyncMessage =
        WaTestKit
            .text(
                chat,
                id,
                NOW - ago * minute,
                "",
                fromMe,
                if (fromMe) WaSyncRowState.Read else WaSyncRowState.Received,
            ).copy(kind = kind, text = null, sender = if (fromMe) null else sender)

    private fun stateOf(
        jid: String,
        rows: List<WaSyncMessage>,
    ): ConversationUi {
        val kit = WaTestKit(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), graceMs = 0)
        kit.chat(ADA, name = "Ada Lovelace")
        kit.chat(GROUP, name = "Weekend hike", group = true)
        rows.forEach(kit::message)
        kit.offline.start()
        val days =
            DayLabels(
                ZoneId.of("UTC"),
                res.configuration.locales[0],
                true,
                res.getString(R.string.conversation_today),
                res.getString(R.string.inbox_yesterday),
            )
        val book = mapOf("+905550000001" to "Ada Lovelace", "+905550000002" to "Mert")
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                    drafts = MemoryDrafts(),
                    names = PersonNames({ book[it] }, MutableStateFlow(Unit)),
                    now = { kit.now },
                    rules = FakeWaActionRules(),
                ),
                jid,
                ResourceWaWords(res),
                ResourceInboxWords(res),
                days,
            )
        shadowOf(Looper.getMainLooper()).idle()
        return model.state.value
    }

    @Composable
    private fun Thread(
        overlays: OverlayController,
        state: ConversationUi,
    ) {
        OverlayHost(overlays) {
            ConversationScreen(
                state = state,
                composer = rememberTextFieldState(""),
                listState = rememberLazyListState(),
                actions =
                    remember {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { persistentListOf() },
                            richCard = {
                                bubble,
                                ink,
                                secondary,
                                ->
                                RichCard(bubble, ink, secondary, RichCardActions())
                            },
                        )
                    },
                sendLabel = stringResource(R.string.conversation_send),
                olderLabel = stringResource(R.string.conversation_load_earlier),
                labels = rememberToolLabels(),
            )
        }
    }

    private fun snap(
        name: String,
        content: @Composable (OverlayController) -> Unit,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark) { content(overlays) }
            advance(600)
            compose.runOnUiThread { after(overlays) }
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private val hike =
        Poll(
            question = "Which trail on Saturday?",
            options =
                listOf(
                    PollOption("Coastal trail, 12 km", listOf(ADA, MERT, ANNA)),
                    PollOption("Forest loop", listOf(MERT, dev.rosty.core.model.wa.MY_VOTE)),
                    PollOption("Summit ridge"),
                ),
            selectable = 0,
            myVotes = listOf("Forest loop"),
        )

    @Test fun aPollBubbleInAGroup() {
        val state =
            stateOf(
                GROUP,
                listOf(
                    row(GROUP, "p1", WaSyncKind.Poll, 20, sender = ADA).copy(senderName = "Ada", poll = hike),
                    row(GROUP, "p2", WaSyncKind.Poll, 10, fromMe = true).copy(
                        poll = Poll("Bring lunch?", listOf(PollOption("Yes", listOf(ADA)), PollOption("No")), 1),
                    ),
                ),
            )
        snap("wa-r5-poll", content = { Thread(it, state) })
    }

    @Test fun placeCardsWithAndWithoutAName() {
        val state =
            stateOf(
                ADA,
                listOf(
                    row(ADA, "l1", WaSyncKind.Location, 20).copy(location = Location(41.0082, 28.9784)),
                    row(ADA, "l2", WaSyncKind.Location, 10, fromMe = true).copy(
                        location = Location(41.0256, 28.9741, name = "Galata Tower", address = "Bereketzade, Beyoglu"),
                    ),
                ),
            )
        snap("wa-r5-place", content = { Thread(it, state) })
    }

    @Test fun contactCardsWithSeveralNumbersAndNone() {
        val two =
            "BEGIN:VCARD\nVERSION:3.0\nFN:Deniz Aydin\nTEL;type=CELL;waid=100000000061:+1 000-000-0061\n" +
                "TEL;type=HOME:+1 000-000-0062\nEND:VCARD"
        val none = "BEGIN:VCARD\nVERSION:3.0\nFN:Front desk\nEND:VCARD"
        val state =
            stateOf(
                ADA,
                listOf(
                    row(ADA, "c1", WaSyncKind.Contact, 20).copy(contact = SharedContact("Deniz Aydin", two)),
                    row(ADA, "c2", WaSyncKind.Contact, 10, fromMe = true).copy(
                        contact = SharedContact("Front desk", none),
                    ),
                ),
            )
        snap("wa-r5-contact", content = { Thread(it, state) })
    }

    @Test fun theHeaderPresenceLine() {
        val base = stateOf(ADA, listOf(WaTestKit.text(ADA, "t1", NOW - minute, "On my way")))
        snap("wa-r5-presence-online", content = { Thread(it, base.copy(subtitle = ResourceWaWords(res).online)) })
        val group =
            stateOf(GROUP, listOf(WaTestKit.text(GROUP, "t2", NOW - minute, "Who brings water?", sender = MERT)))
        snap(
            "wa-r5-presence-typing",
            content = { Thread(it, group.copy(subtitle = ResourceWaWords(res).typing("Mert", recording = false))) },
        )
    }

    @Test fun thePollCreateSheet() {
        snap(
            "wa-r5-poll-sheet",
            content = { Shell(it) },
            after = { it.presentPollSheet(res.getString(R.string.wa_poll_new)) { true } },
        )
    }

    @Test fun thePlaceSendPreview() {
        snap(
            "wa-r5-place-preview",
            content = { Shell(it) },
            after = {
                it.presentPlacePreview(
                    res.getString(R.string.wa_place_send_title),
                    Fix(41.0082, 28.9784, 12.0, NOW),
                    null,
                ) {}
            },
        )
    }

    /** The app shell on the WhatsApp tab: its overlay layer draws the page sheets (as in [WaR4ShotTest]). */
    @Composable
    private fun Shell(overlays: OverlayController) {
        val navigator = remember { TabNavigator(MessagesTabs, MessagesRoots).also { it.select(WhatsAppTab) } }
        val context = LocalContext.current
        RostyShell(
            navigator = navigator,
            tabs =
                messagesTabItems(
                    stringResource(R.string.tab_chats),
                    stringResource(R.string.tab_whatsapp),
                    stringResource(R.string.tab_settings),
                ),
            chromeFor = { r -> chromeFor(r) { it.resolve(context) } },
            overlays = overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<MessagesRoute.Chats> {}
            entry<MessagesRoute.WhatsApp> {}
            entry<MessagesRoute.Settings> {}
        }
    }

    private companion object {
        const val ANNA = "100000000011@s.whatsapp.net"
    }
}
