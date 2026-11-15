package dev.rosty.messages.starred

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.chat.ReminderSlot
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.ChatsTab
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.chromeFor
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationSample
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationTags
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.ToolsUi
import dev.rosty.messages.inbox.HostedInbox
import dev.rosty.messages.inbox.InboxHarness
import dev.rosty.messages.inbox.InboxTags
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.messagesTabItems
import dev.rosty.messages.remind.RemindTags
import dev.rosty.shell.RostyShell
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/**
 * E6.13 screenshots (the Starred list, the "Remind me" popover, a glowing message with its star and reminder badge)
 * and the taps behind them: Star and Remind me in a bubble's menu, a row of the list.
 */
@Config(sdk = [35])
class E613ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val now = ConversationSample.NOW
    private val days = DayLabels(ZoneId.of("UTC"), Locale.US, true, "Today", "Yesterday")

    private fun kit() = MarkKit(CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined, { now })

    private fun target(key: String) =
        ConversationSample
            .bubbles(null)
            .first { it.key == key }
            .markTarget("sms:7", "Mert Kaya", "Me")!!

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    // ---- the conversation: star, remind me, glow

    @Composable
    private fun Thread(
        overlays: OverlayController,
        kit: MarkKit,
        tools: ToolsUi = ToolsUi(),
    ) {
        val res = LocalResources.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density
        val marks =
            remember(res, kit) {
                MarkHost(
                    kit.stars,
                    kit.reminders,
                    overlays,
                    scope,
                    MarkWords(res),
                    "sms:7",
                    { "Mert Kaya" },
                    days::clock,
                ) { rect -> remindAnchor(rect, WINDOW_DP * density, GAP_DP * density, ROOM_DP * density) }
            }
        val copy = stringResource(R.string.conversation_copy)
        val delete = stringResource(R.string.inbox_delete)
        OverlayHost(overlays) {
            ConversationScreen(
                state = marks.marked(ConversationSample.state(null)),
                composer = rememberTextFieldState(),
                listState = rememberLazyListState(),
                actions =
                    remember(marks) {
                        ConversationActions(
                            onBack = {},
                            onSend = {},
                            onLoadOlder = {},
                            menuFor = { bubble ->
                                (
                                    listOf(MenuItem.Action(copy, RostyIcons.Copy) {}) +
                                        marks.menu(bubble) +
                                        listOf(
                                            MenuItem.Separator,
                                            MenuItem.Action(delete, RostyIcons.Trash, destructive = true) {},
                                        )
                                ).toImmutableList()
                            },
                            onMenuOpened = marks::menuOpened,
                        )
                    },
                sendLabel = stringResource(R.string.conversation_send),
                olderLabel = stringResource(R.string.conversation_load_earlier),
                tools = tools,
            )
        }
    }

    private fun press(key: String) {
        compose
            .onNodeWithTag(
                ConversationTags.bubble(key),
            ).performTouchInput { longClick(center, durationMillis = 700) }
        repeat(STEPS) { advance(STEP_MS) }
    }

    private fun choose(textRes: Int) {
        compose.onNodeWithText(compose.activity.getString(textRes)).performClick()
        repeat(STEPS) { advance(STEP_MS) }
    }

    private fun popover(
        name: String,
        armed: Boolean,
    ) {
        for (dark in listOf(false, true)) {
            val kit = kit()
            if (armed) runBlocking { kit.reminders.arm(target("sms:5"), ReminderSlot.Tonight) { it } }
            val overlays = OverlayController()
            composeFrame(dark = dark) { Thread(overlays, kit) }
            advance(600)
            press("sms:5")
            choose(R.string.remind_me)
            compose.onNodeWithTag(RemindTags.Root).assertIsDisplayed()
            capture(name, dark)
        }
    }

    @Test fun remindMePopover() = popover("e6.13-remind-me-popover", armed = false)

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun remindMePopoverTurkishWithCancel() = popover("e6.13-remind-me-popover-tr", armed = true)

    @Test fun thePopoverOpensBelowTheBubbleOrAboveItNearTheEnd() {
        val bubble =
            androidx.compose.ui.geometry
                .Rect(40f, 500f, 600f, 580f)
        assertEquals(588f, remindAnchor(bubble, 2_000f, 8f, 900f).top)
        assertEquals(0f, remindAnchor(bubble, 2_000f, 8f, 900f).height)
        assertEquals(492f, remindAnchor(bubble, 1_200f, 8f, 900f).top)
    }

    @Test fun messageGlow() {
        for (dark in listOf(false, true)) {
            val kit = kit()
            runBlocking {
                kit.stars.set(target("sms:5"), true)
                kit.stars.set(target("sms:2"), true)
                kit.reminders.arm(target("sms:5"), ReminderSlot.Tonight) { it }
            }
            val overlays = OverlayController()
            // What a tap on the reminder's notification or on a Starred row leaves: the message lit up.
            composeFrame(dark = dark) { Thread(overlays, kit, tools = ToolsUi(highlight = "sms:5")) }
            advance(900)
            compose.onNodeWithTag(ConversationTags.star("sms:5"), useUnmergedTree = true).assertExists()
            capture("e6.13-message-glow", dark)
        }
    }

    @Test fun starInTheMenuMarksTheBubbleAndKeepsItInThePrefs() {
        val kit = kit()
        val overlays = OverlayController()
        composeFrame { Thread(overlays, kit) }
        advance(600)
        press("sms:5")
        choose(R.string.starred_star)
        compose.onNodeWithTag(ConversationTags.star("sms:5"), useUnmergedTree = true).assertExists()
        assertEquals(
            setOf("sms:5"),
            kit.store.prefs.value!!
                .smsStars.keys,
        )
        assertEquals(
            listOf("sms:5"),
            kit.stars.items.value
                .map { it.id },
        )
        // The same row now unstars.
        press("sms:5")
        choose(R.string.starred_unstar)
        assertTrue(
            kit.store.prefs.value!!
                .smsStars
                .isEmpty(),
        )
    }

    @Test fun aSlotOfThePopoverArmsTheReminder() {
        val kit = kit()
        val overlays = OverlayController()
        composeFrame { Thread(overlays, kit) }
        advance(600)
        press("sms:5")
        choose(R.string.remind_me)
        compose.onNodeWithTag(RemindTags.slot(ReminderSlot.In1Hour)).performClick()
        repeat(STEPS) { advance(STEP_MS) }
        val item = kit.repo.now.single()
        assertEquals("sms:7", item.tag)
        assertEquals("sms:5", item.messageId)
        assertEquals("Reminder: Mert Kaya", item.title)
        // The badge on the bubble: the bell's clock, one hour from 14:13.
        compose.onNodeWithText("15:13", useUnmergedTree = true).assertExists()
        // A message still on its way has neither row.
        assertTrue(
            ConversationSample.bubbles(null).first { it.key == "local:7" }.markTarget("sms:7", "Mert", "Me") == null,
        )
    }

    // ---- the Starred list

    private fun rows(turkish: Boolean) =
        if (turkish) {
            persistentListOf(
                StarredRowUi(
                    "3EB0A1",
                    "Elif Demir",
                    "Ben: Biletler bende, altıda garda buluşalım",
                    "13:58",
                    RowAvatar(colorKey = "elif"),
                ),
                StarredRowUi(
                    "sms:5",
                    "Mert Kaya",
                    "Kapı kodu 4821, ikinci kat. Zil çalışmıyor, gelince ara.",
                    "Dün",
                    RowAvatar(colorKey = "mert"),
                ),
                StarredRowUi(
                    "3EB0A2",
                    "Ev Arkadaşları",
                    "Deniz: Kira ayın beşinde, IBAN mesajın altında",
                    "18 Eyl",
                    RowAvatar(group = true, colorKey = "ev"),
                ),
                StarredRowUi("mms:4", "+90 555 000 00 03", "Fotoğraf", "2 Eyl", RowAvatar(colorKey = "n3")),
            )
        } else {
            persistentListOf(
                StarredRowUi(
                    "3EB0A1",
                    "Elif Demir",
                    "Me: I have the tickets, six at the station",
                    "13:58",
                    RowAvatar(colorKey = "elif"),
                ),
                StarredRowUi(
                    "sms:5",
                    "Mert Kaya",
                    "Door code 4821, second floor. The bell is broken, call when you arrive.",
                    "Yesterday",
                    RowAvatar(colorKey = "mert"),
                ),
                StarredRowUi(
                    "3EB0A2",
                    "Flatmates",
                    "Deniz: Rent is due on the fifth, IBAN below",
                    "18 Sep",
                    RowAvatar(group = true, colorKey = "ev"),
                ),
                StarredRowUi("mms:4", "+90 555 000 00 03", "Photo", "2 Sep", RowAvatar(colorKey = "n3")),
            )
        }

    private val opened = mutableListOf<String>()
    private val unstarred = mutableListOf<String>()

    @Composable
    private fun StarredHost(
        overlays: OverlayController,
        rows: kotlinx.collections.immutable.ImmutableList<StarredRowUi>,
    ) {
        val navigator =
            remember {
                TabNavigator(MessagesTabs, MessagesRoots).also {
                    it.select(ChatsTab)
                    it.push(MessagesRoute.Starred)
                }
            }
        val context = LocalContext.current
        val res = LocalResources.current
        val labels =
            remember(res) {
                StarredLabels(
                    title = res.getString(R.string.starred_title),
                    emptyTitle = res.getString(R.string.starred_empty_title),
                    emptyDetail = res.getString(R.string.starred_empty_detail),
                    unstar = { res.getString(R.string.starred_unstar_a11y, it) },
                )
            }
        RostyShell(
            navigator = navigator,
            tabs =
                messagesTabItems(
                    stringResource(R.string.tab_chats),
                    stringResource(R.string.tab_whatsapp),
                    stringResource(R.string.tab_settings),
                ),
            chromeFor = { route -> chromeFor(route) { it.resolve(context) } },
            overlays = overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<MessagesRoute.Chats> {}
            entry<MessagesRoute.WhatsApp> {}
            entry<MessagesRoute.Settings> {}
            entry<MessagesRoute.Starred> {
                StarredScreen(rows, labels, rememberLazyListState(), onOpen = { opened += it }, onUnstar = {
                    unstarred +=
                        it
                })
            }
        }
    }

    private fun list(
        name: String,
        turkish: Boolean,
        empty: Boolean = false,
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark) { StarredHost(overlays, if (empty) persistentListOf() else rows(turkish)) }
            advance(1_400)
            capture(name, dark)
        }
    }

    @Test fun starredList() = list("e6.13-starred-list", turkish = false)

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun starredListTurkish() = list("e6.13-starred-list-tr", turkish = true)

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun starredListEmptyTurkish() = list("e6.13-starred-empty-tr", turkish = true, empty = true)

    @Test fun aRowOpensItsMessageAndItsStarUnstars() {
        val overlays = OverlayController()
        composeFrame { StarredHost(overlays, rows(false)) }
        advance(1_400)
        compose.onNodeWithTag(StarredTags.row("sms:5")).performClick()
        advance(300)
        assertEquals(listOf("sms:5"), opened)
        compose.onNodeWithTag(StarredTags.unstar("3EB0A2")).performClick()
        advance(300)
        assertEquals(listOf("3EB0A2"), unstarred)
        assertEquals(listOf("sms:5"), opened)
    }

    @Test fun theRowsTakeTheChatsTitleAndSayWhoWrote() {
        val items =
            listOf(
                StarredItem(
                    "3EB0A1",
                    "wa:905550000002@s.whatsapp.net",
                    "Me",
                    "I have the tickets",
                    now - 900_000,
                    "Elif Demir",
                ),
                StarredItem("sms:5", "sms:7", "Mert Kaya", "Door code 4821", now - 86_400_000),
            )
        val time =
            dev.rosty.messages.inbox
                .RowTime(ZoneId.of("UTC"), Locale.US, true, "Yesterday")
        val built = starredRows(items, emptyList(), time, now) { author, preview -> "$author: $preview" }
        assertEquals(listOf("Elif Demir", "Mert Kaya"), built.map { it.title })
        assertEquals(listOf("Me: I have the tickets", "Door code 4821"), built.map { it.line })
        assertEquals(listOf("13:58", "Yesterday"), built.map { it.time })
    }

    // ---- the way in: the Starred row of the inbox

    @Test fun inboxStarredRow() {
        for (dark in listOf(false, true)) {
            val harness = InboxHarness()
            composeFrame(dark = dark) { HostedInbox(harness, starred = 4) }
            advance(1_400)
            compose.onNodeWithTag(InboxTags.Starred).assertIsDisplayed()
            capture("e6.13-inbox-starred-row", dark)
        }
    }

    private companion object {
        const val STEPS = 15
        const val WINDOW_DP = 891f
        const val GAP_DP = 8f
        const val ROOM_DP = 360f
        const val STEP_MS = 100L
    }
}
