package dev.rosty.messages.wa

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.wa.EditRecord
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.Quoted
import dev.rosty.core.model.wa.Reaction
import dev.rosty.core.model.wa.StatusAuthor
import dev.rosty.core.model.wa.StatusItem
import dev.rosty.core.model.wa.StatusKind
import dev.rosty.core.model.wa.StatusList
import dev.rosty.core.model.wa.StatusMedia
import dev.rosty.core.model.wa.TypingState
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncErrorKind
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.core.model.wa.WaSyncSettleRequest
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.WhatsAppTab
import dev.rosty.messages.chromeFor
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationSample
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationTags
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.InboxSample
import dev.rosty.messages.inbox.InboxScreen
import dev.rosty.messages.inbox.InboxUiState
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.inbox.RowMenuActions
import dev.rosty.messages.inbox.SmsAccess
import dev.rosty.messages.inbox.rememberInboxStrings
import dev.rosty.messages.messagesTabItems
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.messages.wa.status.StatusTags
import dev.rosty.messages.wa.status.StatusViewerActions
import dev.rosty.messages.wa.status.showStatusViewer
import dev.rosty.messages.wa.status.statusRowOf
import dev.rosty.shell.RostyShell
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Screenshots of the WhatsApp side (E6.4a, E6.4s, the Status row of E6.2, the Status viewer of E6.7). The thread
 * states are built by the real view model over the in-memory offline store, with the words of `strings.xml`.
 */
@Config(sdk = [35])
class WaShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val scope: CoroutineScope = MainScope()
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val minute = 60_000L

    @After fun stop() = scope.cancel()

    private fun kit() = WaTestKit(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), graceMs = 0)

    /** The page state of [jid] as the view model builds it from the store. */
    private fun stateOf(
        kit: WaTestKit,
        jid: String,
        book: Map<String, String> = mapOf("+905550000001" to "Ada Lovelace", "+905550000002" to "Mert Kaya"),
        prepare: (WaThreadViewModel) -> Unit = {},
    ): ConversationUi {
        kit.offline.start()
        val locale = res.configuration.locales[0]
        val days =
            DayLabels(
                ZoneId.of("UTC"),
                locale,
                true,
                res.getString(R.string.conversation_today),
                res.getString(R.string.inbox_yesterday),
            )
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    drafts = MemoryDrafts(),
                    names = PersonNames({ book[it] }, MutableStateFlow(Unit)),
                    now = { kit.now },
                ),
                jid,
                ResourceWaWords(res),
                ResourceInboxWords(res),
                days,
            )
        shadowOf(Looper.getMainLooper()).idle()
        prepare(model)
        shadowOf(Looper.getMainLooper()).idle()
        return model.state.value
    }

    @Composable
    private fun Thread(
        overlays: OverlayController,
        state: ConversationUi,
        text: String = "",
    ) {
        OverlayHost(overlays) {
            ConversationScreen(
                state = state,
                composer = rememberTextFieldState(text),
                listState = rememberLazyListState(),
                actions =
                    remember {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { persistentListOf() },
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
        ime: Int = 0,
        fontScale: Float = 1f,
        content: @Composable (OverlayController) -> Unit,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { content(overlays) }
            if (ime > 0) setSimulatedIme(ime)
            advance(600)
            after(overlays)
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
            if (ime > 0) setSimulatedIme(0)
        }
    }

    /** A one-to-one chat with everything E6.4a draws: ticks, an edit, a forward, a deletion, a reply, a reaction. */
    private fun adaChat(kit: WaTestKit) {
        kit.chat(ADA, name = "Ada", pushName = "Ada")
        val day = 24 * 60 * minute
        kit.message(WaTestKit.text(ADA, "m1", NOW - day - 60 * minute, "Are we still on for Saturday?"))
        kit.message(
            WaTestKit.text(ADA, "m2", NOW - day - 58 * minute, "Yes! Ten at the harbour.", true, WaSyncRowState.Read),
        )
        kit.message(
            WaTestKit
                .text(ADA, "m3", NOW - day - 57 * minute, "I'll bring the tickets", true, WaSyncRowState.Read)
                .copy(editedMs = NOW - day - 56 * minute, editHistory = listOf(EditRecord("I bring", NOW - day))),
        )
        kit.message(
            WaTestKit.text(ADA, "m4", NOW - 200 * minute, "The ferry timetable for the weekend").copy(forwarded = true),
        )
        kit.message(WaTestKit.text(ADA, "m5", NOW - 199 * minute, "wrong chat").copy(deleted = true, deletedBy = ADA))
        kit.message(
            WaTestKit
                .text(ADA, "m6", NOW - 120 * minute, "The 9:40 one works for me", true, WaSyncRowState.Delivered)
                .copy(
                    quoted = Quoted("m4", ADA, "The ferry timetable for the weekend"),
                    reactions = listOf(Reaction(ADA, false, "👍")),
                ),
        )
        kit.message(WaTestKit.text(ADA, "m7", NOW - 30 * minute, "Perfect. See you there!"))
        kit.message(WaTestKit.text(ADA, "m8", NOW - 5 * minute, "Can't wait", true, WaSyncRowState.Sent))
    }

    private fun online(kit: WaTestKit) {
        kit.offline.start()
        kit.connect()
    }

    @Test fun waThread() {
        val kit = kit()
        adaChat(kit)
        online(kit)
        val state = stateOf(kit, ADA) { kit.remote.emit(9, GatewayEvent.Typing(ADA, ADA, TypingState.Typing)) }
        assertEquals("typing…", state.subtitle)
        assertEquals(null, state.notice)
        snap("e6.4-wa-thread", content = { Thread(it, state) })
    }

    @Test fun waThreadFontScale2() {
        val kit = kit()
        adaChat(kit)
        online(kit)
        val state = stateOf(kit, ADA)
        snap("e6.4-wa-thread-fs2", fontScale = 2f, content = { Thread(it, state) })
    }

    @Test fun waGroupThread() {
        val kit = kit()
        kit.chat(GROUP, name = "Weekend hike", group = true)
        val cem = "905550000003@s.whatsapp.net"
        kit.message(WaTestKit.text(GROUP, "g1", NOW - 90 * minute, "Who brings water?", sender = ADA))
        kit.message(WaTestKit.text(GROUP, "g2", NOW - 89 * minute, "And snacks, please", sender = ADA))
        kit.message(WaTestKit.text(GROUP, "g3", NOW - 80 * minute, "I can bring both", sender = MERT))
        kit.message(
            WaTestKit.text(GROUP, "g4", NOW - 70 * minute, "I have the map", sender = cem, senderName = "Cem"),
        )
        kit.message(
            WaTestKit
                .text(GROUP, "g5", NOW - 60 * minute, "Great, thank you all", true, WaSyncRowState.Read)
                .copy(reactions = listOf(Reaction(ADA, false, "❤️"), Reaction(MERT, false, "❤️"))),
        )
        kit.message(
            WaTestKit
                .text(GROUP, "g6", NOW - 20 * minute, "Start at eight?", sender = MERT)
                .copy(quoted = Quoted("g4", cem, "I have the map")),
        )
        kit.message(WaTestKit.text(GROUP, "g7", NOW - 10 * minute, "Eight it is", true, WaSyncRowState.Delivered))
        online(kit)
        val state = stateOf(kit, GROUP) { kit.remote.emit(9, GatewayEvent.Typing(GROUP, ADA, TypingState.Typing)) }
        assertEquals("Ada Lovelace is typing…", state.subtitle)
        snap("e6.4-wa-group-thread", content = { Thread(it, state) })
    }

    @Test fun waKeyboardOpen() {
        val kit = kit()
        adaChat(kit)
        online(kit)
        val state = stateOf(kit, ADA)
        snap("e6.4-wa-keyboard-open", ime = 300, content = { Thread(it, state, text = "On my way") })
        // The newest bubble stays above the composer with the keyboard open.
        compose.onNodeWithTag(ConversationTags.bubble("m8")).assertExists()
    }

    /** No connection: the stored thread reads as always, what is written waits with a clock, a refused one says so. */
    private fun offlineState(): ConversationUi {
        val kit = kit()
        adaChat(kit)
        kit.remote.down = true
        return stateOf(kit, ADA) { model ->
            runBlocking {
                kit.now = NOW - 2 * minute
                kit.offline.enqueue(ADA, WaSyncAction.SendText("Here is the whole timetable as text"))
                // What the store does with a request the gateway refused.
                kit.ops.outboxDue(
                    dev.rosty.core.model.wa
                        .WaSyncDueRequest(1, kit.now),
                )
                kit.ops.outboxSettle(
                    WaSyncSettleRequest(1, kit.now, "c1", WaSyncOutcome.Error(WaSyncErrorKind.Rejected, "refused")),
                )
                kit.now = NOW - minute
                model.send("Running five minutes late")
                kit.now = NOW
                model.send("Save me a seat")
            }
        }
    }

    @Test fun offlineThread() {
        val state = offlineState()
        assertEquals("Offline · showing saved messages", state.notice)
        snap("e6.4s-offline-thread", content = { Thread(it, state) })
    }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun offlineThreadTurkish() {
        val state = offlineState()
        assertEquals("Çevrimdışı · kayıtlı mesajlar gösteriliyor", state.notice)
        assertTrue(
            state.items.any { it is dev.rosty.messages.conversation.ConversationItem.Day && it.label == "Bugün" },
        )
        snap("e6.4s-offline-thread-tr", content = { Thread(it, state) })
    }

    // ---- Status

    private fun statusList(): StatusList {
        val cem = "905550000003@s.whatsapp.net"
        return StatusList(
            authors =
                listOf(
                    StatusAuthor(
                        ADA,
                        "Ada",
                        listOf(
                            StatusItem("s1", ADA, timestampMs = NOW - 50 * minute, kind = StatusKind.Text, text = "Hi"),
                            StatusItem(
                                "s2",
                                ADA,
                                timestampMs = NOW - 12 * minute,
                                kind = StatusKind.Image,
                                text = "Morning at the harbour",
                                media = StatusMedia("image/jpeg", 2_048, thumbnail = true),
                            ),
                            StatusItem("s3", ADA, timestampMs = NOW - 4 * minute, kind = StatusKind.Text, text = "Ok"),
                        ),
                    ),
                    StatusAuthor(
                        MERT,
                        "Mert",
                        listOf(StatusItem("s4", MERT, timestampMs = NOW - 30 * minute, text = "New bike day")),
                    ),
                    StatusAuthor(
                        cem,
                        "Cem",
                        listOf(StatusItem("s5", cem, timestampMs = NOW - 300 * minute, text = "Seen", viewed = true)),
                    ),
                ),
        )
    }

    @Composable
    private fun statusUi(list: StatusList = statusList()) =
        rememberStatusWords().let { words ->
            words to
                remember(words) {
                    statusRowOf(list, words, NOW) { jid, carried ->
                        mapOf(ADA to "Ada Lovelace", MERT to "Mert Kaya")[jid] ?: carried.orEmpty()
                    }
                }
        }

    /** The WhatsApp tab on the real shell: the Status row above the WhatsApp chats of the inbox. */
    @Composable
    private fun WhatsAppTabHost(
        overlays: OverlayController,
        offline: Boolean = false,
    ) {
        val navigator = remember { TabNavigator(MessagesTabs, MessagesRoots).also { it.select(WhatsAppTab) } }
        val base = rememberInboxStrings()
        val strings = base.copy(title = stringResource(R.string.tab_whatsapp))
        val (words, status) = statusUi()
        val rows = remember { InboxSample.rows.filter { it.latest == Channel.WhatsApp }.toImmutableList() }
        RostyShell(
            navigator = navigator,
            tabs =
                messagesTabItems(
                    stringResource(R.string.tab_chats),
                    stringResource(R.string.tab_whatsapp),
                    stringResource(R.string.tab_settings),
                ),
            chromeFor = { route -> chromeFor(route) { "" } },
            overlays = overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<MessagesRoute.Chats> {}
            entry<MessagesRoute.WhatsApp> {
                InboxScreen(
                    state = InboxUiState(loaded = true, access = SmsAccess.Ready, rows = rows, anyRows = true),
                    strings = strings,
                    listState = rememberLazyListState(),
                    searchState = rememberTextFieldState(),
                    actions = remember { RowMenuActions(onAction = {}, onMute = {}, onAvatar = {}, onCopyCode = {}) },
                    onNewMessage = {},
                    banner = { WaBanner(status, words.title, offline = offline, onStatus = {}) },
                )
            }
            entry<MessagesRoute.Settings> {}
        }
    }

    @Test fun statusRow() {
        snap("e6.2-status-row", content = { WhatsAppTabHost(it) })
        compose.onNodeWithTag(StatusTags.Row).assertExists()
    }

    @Test fun statusRowFontScale2() = snap("e6.2-status-row-fs2", fontScale = 2f, content = { WhatsAppTabHost(it) })

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun statusRowOfflineTurkish() =
        snap("e6.2-status-row-offline-tr", content = { WhatsAppTabHost(it, offline = true) })

    /** A fixed model: the fake image loader draws by the model's name, and a cache path changes from run to run. */
    private val picture = java.io.File("status-photo.jpg")

    @Composable
    private fun Viewer(
        overlays: OverlayController,
        open: (dev.rosty.messages.wa.status.StatusWords, dev.rosty.messages.wa.status.StatusRowUi) -> Unit,
    ) {
        val (words, status) = statusUi()
        val current = androidx.compose.runtime.rememberUpdatedState(open)
        androidx.compose.runtime.LaunchedEffect(Unit) { current.value(words, status) }
        OverlayHost(overlays) { Box(Modifier.fillMaxSize()) }
    }

    private fun viewerShot(
        name: String,
        start: Int,
        steps: Int,
        fontScale: Float = 1f,
    ) {
        val viewed = mutableListOf<String>()
        snap(name, fontScale = fontScale, content = { overlays ->
            val viewerWords = rememberStatusViewerWords(rememberStatusWords()) { NOW }
            Viewer(overlays) { _, status ->
                overlays.showStatusViewer(
                    status.people,
                    start,
                    viewerWords,
                    StatusViewerActions(
                        picture = { _, _ -> picture },
                        onViewed = { viewed += it.id },
                        onMessage = {},
                    ),
                )
            }
        }, after = {
            repeat(steps) {
                // A tap on the right half: the next update.
                compose.onNodeWithTag(StatusTags.Viewer).performTouchInput {
                    down(Offset(right * 0.8f, centerY))
                    up()
                }
                advance(100)
            }
        })
        assertTrue(viewed.isNotEmpty())
    }

    /** The second of Ada's three updates: a photo with its caption, the Message pill, one bar full and one filling. */
    @Test fun statusViewer() = viewerShot("e6.7-status-viewer", start = 0, steps = 1)

    @Test fun statusViewerText() = viewerShot("e6.7-status-viewer-text", start = 1, steps = 0)

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun statusViewerTurkishFontScale2() = viewerShot("e6.7-status-viewer-tr-fs2", start = 0, steps = 1, fontScale = 2f)
}
