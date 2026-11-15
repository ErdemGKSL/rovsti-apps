package dev.rosty.messages.wa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.wa.StatusList
import dev.rosty.design.chrome.ScreenPage
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.confirmDestructive
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.resolve
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.design.time.LocalRostyClock
import dev.rosty.design.time.RostyClock
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.WhatsAppTab
import dev.rosty.messages.conversation.rememberDayLabels
import dev.rosty.messages.inbox.InboxAction
import dev.rosty.messages.inbox.InboxBuilder
import dev.rosty.messages.inbox.InboxEffect
import dev.rosty.messages.inbox.InboxScreen
import dev.rosty.messages.inbox.InboxSources
import dev.rosty.messages.inbox.InboxViewModel
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.inbox.RowMenuActions
import dev.rosty.messages.inbox.RowTime
import dev.rosty.messages.inbox.presentMute
import dev.rosty.messages.inbox.rememberInboxStrings
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.ui.LocalWantAvatar
import dev.rosty.messages.wa.status.StatusPeople
import dev.rosty.messages.wa.status.StatusPerson
import dev.rosty.messages.wa.status.StatusRow
import dev.rosty.messages.wa.status.StatusRowUi
import dev.rosty.messages.wa.status.StatusViewerActions
import dev.rosty.messages.wa.status.StatusViewerWords
import dev.rosty.messages.wa.status.StatusWords
import dev.rosty.messages.wa.status.ago
import dev.rosty.messages.wa.status.showStatusViewer
import dev.rosty.messages.wa.status.statusRowOf
import dev.rosty.platform.sms.SmsRepository
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneId

/** Test tags of the WhatsApp tab. */
object WhatsAppTags {
    const val NotLinked = "wa-not-linked"
    const val Offline = "wa-offline"
}

/** The SMS side of the WhatsApp tab's inbox: no threads, so its rows are the WhatsApp chats alone. */
private class NoSmsThreads(
    private val real: SmsRepository,
) : SmsRepository by real {
    override fun threads(limit: Int): Flow<List<SmsThreadRow>> = flowOf(emptyList())
}

/**
 * The inbox view model of the WhatsApp tab: the same inbox as the Chats tab (rows, chips, pins, archive, mute, select
 * mode) over the WhatsApp chats only. Its own instance, so a filter or a selection here does not move the Chats tab.
 */
@Composable
fun rememberWaInboxViewModel(container: MessagesContainer): InboxViewModel {
    val context = LocalContext.current
    val resources = LocalResources.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val words = ResourceInboxWords(resources)
        val time =
            RowTime(
                ZoneId.systemDefault(),
                resources.configuration.locales[0],
                android.text.format.DateFormat
                    .is24HourFormat(context),
                resources.getString(R.string.inbox_yesterday),
            )
        val factory =
            viewModelFactory {
                initializer {
                    InboxViewModel(
                        InboxSources(
                            sms = NoSmsThreads(container.sms),
                            smsReadable = MutableStateFlow(true),
                            roleHeld = MutableStateFlow(true),
                            wa = container.waChats,
                            book = container.book.book,
                            store = container.store,
                            ops = container.ops,
                            chat = container.chat,
                            now = System::currentTimeMillis,
                            names = container.names,
                        ),
                        InboxBuilder(words, time),
                        words,
                    )
                }
            }
        ViewModelProvider(owner, factory)[WA_INBOX_KEY, InboxViewModel::class.java]
    }
}

/** [StatusWords] from the app's resources; the clock follows the phone's zone and 24-hour setting. */
@Composable
fun rememberStatusWords(): StatusWords {
    val res = LocalResources.current
    val days = rememberDayLabels()
    return remember(res, days) {
        StatusWords(
            title = res.getString(R.string.wa_status_title),
            none = res.getString(R.string.wa_status_none),
            newUpdates = { res.getQuantityString(R.plurals.wa_status_new, it, it) },
            mine = res.getString(R.string.wa_status_my),
            recent = res.getString(R.string.wa_status_recent),
            viewed = res.getString(R.string.wa_status_viewed),
            justNow = res.getString(R.string.wa_status_just_now),
            minutesAgo = { res.getString(R.string.wa_status_min_ago, it) },
            clock = days::clock,
            empty = res.getString(R.string.wa_status_empty),
        )
    }
}

@Composable
fun rememberStatusViewerWords(
    words: StatusWords,
    now: () -> Long,
): StatusViewerWords {
    val res = LocalResources.current
    return remember(res, words) {
        StatusViewerWords(
            title = words.title,
            close = res.getString(R.string.wa_status_close),
            message = res.getString(R.string.wa_status_message),
            loading = res.getString(R.string.conversation_loading),
            photoFailed = res.getString(R.string.wa_status_photo_failed),
            video = res.getString(R.string.wa_status_video),
            videoLength = { res.getString(R.string.wa_status_video_length, it) },
            videoNote = res.getString(R.string.wa_status_video_note),
            unsupported = res.getString(R.string.wa_status_unsupported),
            position = { at, count -> res.getString(R.string.wa_status_position, at, count) },
            ago = { words.ago(now(), it) },
        )
    }
}

/**
 * The WhatsApp tab root: the Status row, then the WhatsApp chats in the inbox's own list (E6.2), all read from the
 * offline store, so it is the same with and without a connection. Not linked: a way into the WhatsApp settings.
 */
@Composable
fun WhatsAppPage(
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
) {
    val offline = container.waOffline
    val session by offline.session.collectAsState()
    val settled by offline.settled.collectAsState()
    if (settled && session == null) {
        NotLinked { navigator.push(MessagesRoute.WhatsAppSettings) }
        return
    }
    val viewModel = rememberWaInboxViewModel(container)
    val state by viewModel.state.collectAsState()
    val online by offline.online.collectAsState()
    val history by offline.history.collectAsState()
    val listState = rememberLazyListState()
    val searchState = rememberTextFieldState()
    val base = rememberInboxStrings()
    val title = stringResource(R.string.tab_whatsapp)
    val emptyTitle = stringResource(R.string.wa_tab_empty_title)
    val emptyDetail = stringResource(R.string.wa_tab_empty_detail)
    val strings = remember(base, title) { base.copy(title = title, emptyTitle = emptyTitle, emptyDetail = emptyDetail) }
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    val res = LocalResources.current
    LaunchedEffect(navigator, listState) {
        navigator.scrollToTop.collect { if (it.tab == WhatsAppTab) listState.animateScrollToItem(0) }
    }
    LaunchedEffect(viewModel, res) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is InboxEffect.Open -> {
                    navigator.push(effect.route)
                }

                is InboxEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is InboxEffect.Undo -> {
                    overlays.showUndo(effect.text.resolve(context), effect.undo, res.getString(R.string.inbox_undo))
                }

                is InboxEffect.ConfirmDelete -> {
                    overlays.confirmDestructive(
                        destructiveLabel = res.getString(R.string.inbox_delete),
                        onConfirm = effect.confirm,
                        message =
                            effect.title?.let { res.getString(R.string.inbox_delete_one, it) }
                                ?: res.getQuantityString(
                                    R.plurals.inbox_delete_many,
                                    effect.count,
                                    effect.count,
                                ),
                        cancelLabel = strings.cancel,
                    )
                }

                InboxEffect.NeedsRole -> {
                    overlays.showToast(res.getString(R.string.inbox_needs_role))
                }
            }
        }
    }
    val actions =
        remember(viewModel, overlays, res) {
            RowMenuActions(
                onAction = viewModel::onAction,
                onMute = { row ->
                    presentMute(overlays, res) { viewModel.onAction(InboxAction.Mute(row.key, it)) }
                },
                onAvatar = { row -> viewModel.onAction(InboxAction.Open(row.key)) },
                onCopyCode = { code ->
                    container.clipboard.copy(code, sensitive = true)
                    overlays.showToast(res.getString(R.string.inbox_code_copied, code))
                },
            )
        }
    val statusWords = rememberStatusWords()
    val statuses by container.statusFeed.list.collectAsState()
    val status by statusRow(container, statuses, statusWords)
    val viewerWords = rememberStatusViewerWords(statusWords, System::currentTimeMillis)
    val viewer =
        remember(container, navigator) {
            StatusViewerActions(
                picture = container.statusFeed::picture,
                onViewed = { container.statusFeed.viewed(it.id) },
                onMessage = { jid -> navigator.push(MessagesRoute.WaThread(jid)) },
            )
        }
    CompositionLocalProvider(LocalWantAvatar provides container.avatars::want) {
        InboxScreen(
            state = state,
            strings = strings,
            listState = listState,
            searchState = searchState,
            actions = actions,
            onNewMessage = { navigator.push(MessagesRoute.NewConversation()) },
            banner = {
                WaBanner(
                    status = status,
                    statusTitle = statusWords.title,
                    offline = !online,
                    onStatus = { presentStatus(overlays, status, statusWords, viewerWords, viewer) },
                    history = WaHistoryUi.of(history),
                )
            },
        )
    }
}

/**
 * The rows above the search field of the WhatsApp tab: the history progress (while the history arrives), the offline
 * line (when the gateway is away) and Status.
 */
@Composable
fun WaBanner(
    status: StatusRowUi,
    statusTitle: String,
    offline: Boolean,
    onStatus: () -> Unit,
    history: WaHistoryUi? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        history?.let { WaHistoryRow(it) }
        if (offline) {
            RostyText(
                stringResource(R.string.wa_tab_offline),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
                        .testTag(WhatsAppTags.Offline),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        StatusRow(status, statusTitle, onClick = onStatus)
    }
}

/** The people of the status list with the app's names (address book, WhatsApp name, number). */
@Composable
private fun statusRow(
    container: MessagesContainer,
    list: StatusList,
    words: StatusWords,
): androidx.compose.runtime.State<StatusRowUi> {
    val clock = LocalRostyClock.current
    return statusRowState(container, list, words, clock)
}

@Composable
private fun statusRowState(
    container: MessagesContainer,
    list: StatusList,
    words: StatusWords,
    clock: RostyClock,
): androidx.compose.runtime.State<StatusRowUi> {
    // Moves when a contact is saved or a WhatsApp name that was asked for arrives: the people are named again.
    val revision by container.names.changes.collectAsState(0L)
    return produceState(StatusRowUi(subtitle = words.none), list, words, revision) {
        val names = HashMap<String, String>()
        for (author in list.authors) names[author.author] = container.names.sender(author.author, author.name)
        value = statusRowOf(list, words, clock.nowMillis()) { jid, _ -> names[jid].orEmpty() }
    }
}

/** Opens the Status list as a sheet; a person opens the viewer on their updates. */
fun presentStatus(
    overlays: OverlayController,
    status: StatusRowUi,
    words: StatusWords,
    viewerWords: StatusViewerWords,
    viewer: StatusViewerActions,
) {
    // Only people with updates of their own play one after the other; mine is opened on its own.
    val others = status.people.filter { !it.mine }
    overlays.present(
        SheetEntry(title = words.title, detents = persistentListOf(Detent.Medium, Detent.Large)) { sheet ->
            StatusPeople(status, words, onOpen = { person: StatusPerson ->
                overlays.dismissAll()
                if (person.mine) {
                    overlays.showStatusViewer(persistentListOf(person), 0, viewerWords, viewer)
                } else {
                    overlays.showStatusViewer(
                        kotlinx.collections.immutable
                            .persistentListOf<StatusPerson>()
                            .addAll(others),
                        others.indexOf(person).coerceAtLeast(0),
                        viewerWords,
                        viewer,
                    )
                }
            })
        },
    )
}

@Composable
private fun NotLinked(onLink: () -> Unit) {
    ScreenPage(title = stringResource(R.string.tab_whatsapp), modifier = Modifier.testTag(WhatsAppTags.NotLinked)) {
        EmptyState(
            RostyIcons.WhatsApp,
            stringResource(R.string.wa_tab_not_linked_title),
            stringResource(R.string.wa_tab_not_linked_detail),
            actionLabel = stringResource(R.string.wa_tab_link),
            onAction = onLink,
        )
    }
}

private const val WA_INBOX_KEY = "wa-inbox"
