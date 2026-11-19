package dev.rosty.messages.wa

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import dev.rosty.core.model.wa.LinkPreview
import dev.rosty.core.model.wa.ReceiptSection
import dev.rosty.core.model.wa.SystemNote
import dev.rosty.core.model.wa.WaMentionSpan
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.text.resolve
import dev.rosty.design.theme.Spacing
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.SettingsTab
import dev.rosty.messages.chromeFor
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.EditingUi
import dev.rosty.messages.conversation.LinkChipUi
import dev.rosty.messages.conversation.MentionOptionUi
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.messagesTabItems
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
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
 * Screenshots of E6-WA R4, light and dark: a group thread with a mention, a link card, "Forwarded many times" and a
 * disappearing-timer line; the composer editing a message, with the `@` list and a link chip; message info and the
 * timer choices as sheets; the "Link previews" switch of the WhatsApp settings.
 */
@Config(sdk = [35])
class WaR4ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val scope: CoroutineScope = MainScope()
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val minute = 60_000L

    @After fun stop() = scope.cancel()

    private fun stateOf(jid: String): ConversationUi {
        val kit = WaTestKit(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), graceMs = 0)
        kit.chat(GROUP, name = "Weekend hike", group = true)
        kit.message(
            WaTestKit
                .text(GROUP, "t1", NOW - 30 * minute, "", sender = ADA)
                .copy(system = SystemNote(SystemNote.DISAPPEARING, WaWords.WEEK_S, ADA)),
        )
        kit.message(
            WaTestKit
                .text(GROUP, "g1", NOW - 20 * minute, "@100000000011 can you bring the map?", sender = MERT)
                .copy(senderName = "Mert", mentionSpans = listOf(WaMentionSpan(0, 13, ANNA))),
        )
        kit.message(
            WaTestKit
                .text(GROUP, "g2", NOW - 15 * minute, "Trail notes: https://www.example.org/trail", sender = ADA)
                .copy(
                    linkPreview =
                        LinkPreview(
                            "https://www.example.org/trail",
                            "Coastal trail, 12 km",
                            "Route, water points and the best time to start",
                        ),
                ),
        )
        kit.message(
            WaTestKit
                .text(GROUP, "g3", NOW - 10 * minute, "Weather warning for Saturday", sender = MERT)
                .copy(senderName = "Mert", forwarded = true, forwardingScore = 6, forwardedMany = true),
        )
        kit.message(WaTestKit.text(GROUP, "g4", NOW - 5 * minute, "I'll bring it", true, WaSyncRowState.Read))
        kit.offline.start()
        val days =
            DayLabels(
                ZoneId.of("UTC"),
                res.configuration.locales[0],
                true,
                res.getString(R.string.conversation_today),
                res.getString(R.string.inbox_yesterday),
            )
        val book = mapOf("+905550000001" to "Ada Lovelace", "+100000000011" to "Anna")
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
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
        content: @Composable (OverlayController) -> Unit,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark) { content(overlays) }
            advance(600)
            after(overlays)
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun aGroupThreadWithAMentionALinkCardAForwardAndATimerLine() {
        val state = stateOf(GROUP)
        snap("wa-r4-thread", content = { Thread(it, state) })
    }

    @Test fun theComposerEditingAMessageWithTheMentionListAndALinkChip() {
        val state =
            stateOf(GROUP).copy(
                editing = EditingUi("g4", "I'll bring it"),
                mentionOptions =
                    persistentListOf(
                        MentionOptionUi(ANNA, "Anna", "+1 000-000-0011"),
                        MentionOptionUi(LID_ONLY, "Lale"),
                    ),
            )
        snap("wa-r4-editing", content = { Thread(it, state, "I'll bring it and @") })
        val chip =
            stateOf(GROUP).copy(
                linkChip = LinkChipUi("https://www.example.org/trail", "Coastal trail, 12 km", "example.org"),
            )
        snap("wa-r4-link-chip", content = { Thread(it, chip, "Look: https://www.example.org/trail") })
        val loading =
            stateOf(
                GROUP,
            ).copy(linkChip = LinkChipUi("https://example.org", null, "example.org", loading = true))
        snap("wa-r4-link-loading", content = { Thread(it, loading, "https://example.org") })
    }

    private fun sheet(
        title: String,
        detents: List<Detent>,
        body: @Composable () -> Unit,
    ): (OverlayController) -> Unit =
        { overlays ->
            compose.runOnUiThread {
                overlays.present(
                    SheetEntry(title = title, detents = persistentListOf(*detents.toTypedArray())) { _ -> body() },
                )
            }
        }

    @Test fun messageInfoAsASheet() {
        val info =
            WaInfoUi(
                listOf(
                    WaInfoSection(ReceiptSection.Read, listOf(WaInfoRow(ANNA, "Anna", "14:05", left = false))),
                    WaInfoSection(
                        ReceiptSection.Delivered,
                        listOf(
                            WaInfoRow(ADA, "Ada Lovelace", "14:02", left = false),
                            WaInfoRow(MERT, "Mert", "14:01", left = true),
                        ),
                    ),
                    WaInfoSection(ReceiptSection.Waiting, listOf(WaInfoRow(LID_ONLY, "Lale", null, left = false))),
                ),
            )
        snap(
            "wa-r4-info",
            content = { Settings(it) },
            after = sheet(res.getString(R.string.wa_info_title), listOf(Detent.Large)) { WaInfoContent(info, null) },
        )
        snap(
            "wa-r4-info-old-gateway",
            content = { Settings(it) },
            after =
                sheet(res.getString(R.string.wa_info_title), listOf(Detent.Medium)) {
                    WaInfoContent(null, WaInfoProblem.OldGateway)
                },
        )
    }

    @Test fun theDisappearingTimerChoicesAsASheet() {
        snap(
            "wa-r4-timer",
            content = { Settings(it) },
            after =
                sheet(res.getString(R.string.wa_disappearing), listOf(Detent.Medium)) {
                    WaTimerContent(res, WaWords.WEEK_S, onPick = {})
                },
        )
    }

    /** The WhatsApp settings page in the app shell (whose overlay layer shows the sheets), with "Link previews". */
    @Composable
    private fun Settings(overlays: OverlayController) {
        val navigator =
            remember {
                TabNavigator(MessagesTabs, MessagesRoots).also {
                    it.select(SettingsTab)
                    it.push(MessagesRoute.WhatsAppSettings)
                }
            }
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
            entry<MessagesRoute.WhatsAppSettings> {
                Column(Modifier.fillMaxSize().padding(top = Spacing.xl * 5)) {
                    WaLinkPreviewSection(on = true, onChange = {})
                }
            }
        }
    }

    @Test fun theLinkPreviewsSwitch() {
        snap("wa-r4-link-previews", content = { Settings(it) })
    }

    private companion object {
        const val ANNA = "100000000011@s.whatsapp.net"
        const val LID_ONLY = "123456789012345@lid"
    }
}
