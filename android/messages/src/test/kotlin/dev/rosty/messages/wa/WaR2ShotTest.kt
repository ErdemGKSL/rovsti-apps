package dev.rosty.messages.wa

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.wa.SettingsButton
import dev.rosty.core.model.wa.SettingsRow
import dev.rosty.core.model.wa.SettingsView
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.text.resolve
import dev.rosty.design.theme.Spacing
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.SettingsTab
import dev.rosty.messages.WhatsAppTab
import dev.rosty.messages.chromeFor
import dev.rosty.messages.inbox.InboxSample
import dev.rosty.messages.inbox.InboxScreen
import dev.rosty.messages.inbox.InboxUiState
import dev.rosty.messages.inbox.RowMenuActions
import dev.rosty.messages.inbox.SmsAccess
import dev.rosty.messages.inbox.rememberInboxStrings
import dev.rosty.messages.messagesTabItems
import dev.rosty.messages.wa.status.StatusRowUi
import dev.rosty.shell.RostyShell
import dev.rosty.testing.ShotTest
import dev.rosty.wa.settings.WaSettingsUiState
import dev.rosty.wa.settings.WhatsAppSettingsScreen
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Screenshots of E6-WA R2: the history progress row on the WhatsApp tab, and "Storage and data" with the history
 * state and its note on the linked page of the WhatsApp settings; light and dark, Turkish and a large font.
 */
@Config(sdk = [35])
class WaR2ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val mib = 1024L * 1024

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        after: (OverlayController) -> Unit = {},
        content: @Composable (OverlayController) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark, fontScale = fontScale) { content(overlays) }
            advance(600)
            after(overlays)
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Composable
    private fun Shell(
        overlays: OverlayController,
        route: MessagesRoute,
        body: @Composable () -> Unit,
    ) {
        val navigator =
            remember {
                TabNavigator(MessagesTabs, MessagesRoots).also {
                    if (route == MessagesRoute.WhatsApp) {
                        it.select(WhatsAppTab)
                    } else {
                        it.select(SettingsTab)
                        it.push(route)
                    }
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
            entry<MessagesRoute.WhatsApp> { body() }
            entry<MessagesRoute.Settings> {}
            entry<MessagesRoute.WhatsAppSettings> { body() }
        }
    }

    // ---- the WhatsApp tab

    @Composable
    private fun Tab(
        overlays: OverlayController,
        history: WaHistoryUi?,
        offline: Boolean = false,
    ) {
        val base = rememberInboxStrings()
        val strings = base.copy(title = stringResource(R.string.tab_whatsapp))
        val statusTitle = stringResource(R.string.wa_status_title)
        val none = stringResource(R.string.wa_status_none)
        val rows = remember { InboxSample.rows.filter { it.latest == Channel.WhatsApp }.toImmutableList() }
        Shell(overlays, MessagesRoute.WhatsApp) {
            InboxScreen(
                state = InboxUiState(loaded = true, access = SmsAccess.Ready, rows = rows, anyRows = true),
                strings = strings,
                listState = rememberLazyListState(),
                searchState = rememberTextFieldState(),
                actions = remember { RowMenuActions(onAction = {}, onMute = {}, onAvatar = {}, onCopyCode = {}) },
                onNewMessage = {},
                banner = {
                    WaBanner(
                        StatusRowUi(subtitle = none),
                        statusTitle,
                        offline = offline,
                        onStatus = {},
                        history = history,
                    )
                },
            )
        }
    }

    private val arriving = WaHistoryUi(percent = 42, chats = 128, messages = 12_340)

    @Test fun historyRow() {
        snap("e6wa-r2-history-row") { Tab(it, arriving) }
        compose.onNodeWithTag(WaHistoryTags.Row).assertIsDisplayed()
        compose.onNodeWithTag(WaHistoryTags.Bar, useUnmergedTree = true).assertIsDisplayed()
    }

    /** The phone has not said how far it is: no bar, the counts so far, one chat. */
    @Test fun historyRowWithoutAPercent() {
        snap("e6wa-r2-history-row-unknown") { Tab(it, WaHistoryUi(percent = null, chats = 1, messages = 37)) }
        compose.onNodeWithTag(WaHistoryTags.Bar, useUnmergedTree = true).assertDoesNotExist()
    }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun historyRowOfflineTurkish() = snap("e6wa-r2-history-row-tr") { Tab(it, arriving, offline = true) }

    @Test fun historyRowFontScale2() = snap("e6wa-r2-history-row-fs2", fontScale = 2f) { Tab(it, arriving) }

    @Test fun withoutAHistorySyncTheTabHasNoRow() {
        composeFrame { Tab(OverlayController(), null) }
        compose.onNodeWithTag(WaHistoryTags.Row).assertDoesNotExist()
    }

    // ---- the WhatsApp settings

    /** The page of a linked account as the Rust state machine draws it. */
    private fun connected(turkish: Boolean) =
        SettingsView(
            kind = "connected",
            title = "WhatsApp",
            notes = emptyList(),
            rows =
                listOf(
                    SettingsRow(
                        if (turkish) "Durum" else "Status",
                        if (turkish) "Bağlı" else "Connected",
                        "status",
                        "connected",
                    ),
                ),
            buttons =
                listOf(
                    button("show_key", if (turkish) "Bağlantı anahtarını göster" else "Show connect key"),
                    button("keys", if (turkish) "Bağlı uygulamalar" else "Connected apps"),
                    button("unlink", if (turkish) "Bağlantıyı kaldır" else "Unlink"),
                ),
            fields = emptyList(),
            qrPayload = null,
            code = null,
            keyLines = emptyList(),
            keys = emptyList(),
            error = null,
            busy = false,
            back = null,
        )

    private fun button(
        id: String,
        label: String,
    ) = SettingsButton(id, label, null, "secondary", id == "unlink", null, null, null)

    @Composable
    private fun Settings(
        overlays: OverlayController,
        storage: WaStorageUi,
        history: WaHistoryUi? = null,
        turkish: Boolean = false,
        listState: LazyListState = rememberLazyListState(),
        onCap: (Long?) -> Unit = {},
    ) {
        val view = remember(turkish) { connected(turkish) }
        Shell(overlays, MessagesRoute.WhatsAppSettings) {
            WhatsAppSettingsScreen(
                state = WaSettingsUiState(view = view, page = view),
                onAction = {},
                listState = listState,
                linked = {
                    if (history != null) item { WaHistoryRow(history, Modifier.padding(top = Spacing.m)) }
                    item { WaStorageSection(storage, onCap) }
                    item { WaHistoryNote() }
                },
            )
        }
    }

    private val storage = WaStorageUi(usedBytes = 187 * mib, capBytes = 512 * mib)

    private fun scrollToEnd(state: LazyListState) {
        runBlocking { state.scrollToItem(state.layoutInfo.totalItemsCount - 1) }
    }

    @Test fun storageAndData() {
        snap("e6wa-r2-storage") { Settings(it, storage) }
        compose.onNodeWithTag(WaStorageTags.Used).assertIsDisplayed()
        compose.onNodeWithTag(WaStorageTags.Cap, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun storageAndDataEnd() {
        for (dark in listOf(false, true)) {
            val state = LazyListState()
            composeFrame(dark = dark) { Settings(OverlayController(), storage, listState = state) }
            advance(600)
            compose.runOnIdle { scrollToEnd(state) }
            advance(1_200)
            // The last block clears the floating tab bar.
            compose.onNodeWithTag(WaStorageTags.HistoryNote).assertIsDisplayed()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e6wa-r2-storage-end-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    /** While the history arrives the settings show the same row as the tab, above the storage section. */
    @Test fun storageWithHistoryAndNoLimit() =
        snap("e6wa-r2-storage-history") { Settings(it, WaStorageUi(3_400 * mib, null), history = arriving) }

    @Config(qualifiers = "+tr-rTR")
    @Test
    fun storageTurkishEnd() {
        for (dark in listOf(false, true)) {
            val state = LazyListState()
            composeFrame(dark = dark) { Settings(OverlayController(), storage, turkish = true, listState = state) }
            advance(600)
            compose.runOnIdle { scrollToEnd(state) }
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e6wa-r2-storage-end-tr-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun storageFontScale2() = snap("e6wa-r2-storage-fs2", fontScale = 2f) { Settings(it, storage) }

    @Test fun theCapMenuOffersTheFiveChoicesAndStoresTheOneTapped() {
        val chosen = mutableListOf<Long?>()
        snap(
            "e6wa-r2-storage-cap-menu",
            after = {
                compose.onNodeWithTag(WaStorageTags.Cap, useUnmergedTree = true).performTouchInput { down(center) }
                advance(100)
                compose.onNodeWithTag(WaStorageTags.Cap, useUnmergedTree = true).performTouchInput { up() }
            },
        ) { Settings(it, storage, onCap = { cap -> chosen += cap }) }
        // The menu of the last frame is open: the choice with the tick is the stored cap, a tap stores another.
        compose.onNodeWithText("No limit").assertIsDisplayed()
        compose.onNodeWithText("2 GB").performClick()
        advance(600)
        assertEquals(listOf<Long?>(2048 * mib), chosen)
    }
}
