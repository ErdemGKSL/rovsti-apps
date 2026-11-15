package dev.rosty.messages.wa

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.UnreadDividerRequest
import dev.rosty.core.model.wa.WaIds
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationEffects
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.bubblesOf
import dev.rosty.messages.conversation.handlePill
import dev.rosty.messages.conversation.headerMenu
import dev.rosty.messages.conversation.rememberDayLabels
import dev.rosty.messages.conversation.rememberDetector
import dev.rosty.messages.conversation.rememberToolHost
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.notify.TrackOpenConversation
import dev.rosty.messages.schedule.ScheduledStrip
import dev.rosty.messages.schedule.rememberWaScheduleHost
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.settings.replies.rememberComposerExtras
import dev.rosty.messages.starred.rememberMarkHost
import dev.rosty.messages.ui.LocalWantAvatar
import dev.rosty.platform.identity.PhoneNumbers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/** [WaWords] from the app's resources. */
class ResourceWaWords(
    private val res: Resources,
    /** "1.2 MB" in the phone's language; the default is for tests without a context. */
    private val sizeOf: (Long) -> String = ::plainSize,
) : WaWords {
    override val placeholder = res.getString(R.string.wa_thread_placeholder)
    override val sending = res.getString(R.string.wa_thread_sending)
    override val waiting = res.getString(R.string.wa_thread_waiting)
    override val notSent = res.getString(R.string.wa_thread_not_sent)
    override val now = res.getString(R.string.sms_now)
    override val offline = res.getString(R.string.wa_thread_offline)
    override val syncing = res.getString(R.string.wa_thread_syncing)
    override val you = res.getString(R.string.wa_thread_you)
    override val deleted = res.getString(R.string.wa_deleted)
    override val youDeleted = res.getString(R.string.wa_you_deleted)

    override fun typing(
        name: String?,
        recording: Boolean,
    ): String =
        when {
            name == null -> res.getString(if (recording) R.string.wa_thread_recording else R.string.wa_thread_typing)
            recording -> res.getString(R.string.wa_thread_recording_by, name)
            else -> res.getString(R.string.wa_thread_typing_by, name)
        }

    override fun edited(clock: String) = res.getString(R.string.wa_thread_edited, clock)

    override fun unread(count: Int) = res.getQuantityString(R.plurals.conversation_unread, count, count)

    override fun size(bytes: Long) = sizeOf(bytes)

    override val gone = res.getString(R.string.wa_media_gone)
}

/** "512 B", "340 KB", "1.2 MB": a file size without a context (1024-based, one decimal from a megabyte up). */
fun plainSize(bytes: Long): String =
    when {
        bytes < KILO -> "$bytes B"
        bytes < KILO * KILO -> "${bytes / KILO} KB"
        else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (KILO * KILO).toDouble())
    }

private const val KILO = 1024L

/** The thread drafts in `drafts.json`, next to the SMS ones. */
class StoreWaDrafts(
    private val store: MessagesStore,
) : WaDrafts {
    override suspend fun load(tag: String): String {
        store.load()
        return store.drafts.value[tag].orEmpty()
    }

    override suspend fun put(
        tag: String,
        text: String,
    ) {
        store.putDraft(tag, text)
    }
}

/**
 * The bubble menu of a WhatsApp message (E6.4a; the full menu with reactions is E6.5). A message still in the outbox
 * can be retried (failed), edited (back into the composer) or cancelled; a sent one of mine deleted for everyone.
 */
fun waBubbleMenu(
    bubble: BubbleUi,
    res: Resources,
    model: WaThreadViewModel,
    /** E6.13: "Star" or "Unstar" and "Remind me" for a message that can be marked. */
    marks: (BubbleUi) -> List<MenuItem> = { emptyList() },
): ImmutableList<MenuItem> {
    val items = ArrayList<MenuItem>()
    val queued =
        bubble.state == BubbleState.Waiting || bubble.state == BubbleState.Failed || bubble.state == BubbleState.Sending
    if (bubble.state == BubbleState.Failed) {
        items += MenuItem.Action(res.getString(R.string.sms_retry), RostyIcons.Rotate) { model.retry(bubble.key) }
    }
    if (bubble.canReply) {
        items += MenuItem.Action(res.getString(R.string.wa_reply), RostyIcons.Message) { model.startReply(bubble.key) }
    }
    if (!bubble.deleted && bubble.text.isNotEmpty()) {
        items += MenuItem.Action(res.getString(R.string.conversation_copy), RostyIcons.Copy) { model.copy(bubble.key) }
    }
    items += marks(bubble)
    if (queued && bubble.state != BubbleState.Sending) {
        items +=
            MenuItem.Action(res.getString(R.string.wa_thread_edit), RostyIcons.Pencil) {
                model.cancel(bubble.key, restore = true)
            }
        items += MenuItem.Separator
        items +=
            MenuItem.Action(res.getString(R.string.wa_thread_cancel_send), RostyIcons.Trash, destructive = true) {
                model.cancel(bubble.key)
            }
    } else if (bubble.outgoing && !bubble.deleted && !queued) {
        items += MenuItem.Separator
        items +=
            MenuItem.Action(res.getString(R.string.wa_thread_delete_everyone), RostyIcons.Trash, destructive = true) {
                model.revoke(bubble.key)
            }
    }
    return items.toImmutableList()
}

/** The view model of the thread [jid], kept by the activity while the page is on a stack. */
@Composable
fun rememberWaThreadViewModel(
    container: MessagesContainer,
    jid: String,
): WaThreadViewModel {
    val context = LocalContext.current
    val res = LocalResources.current
    val days = rememberDayLabels()
    val owner = remember(context) { context.findComponentActivity() }
    val key = "wa-$jid"
    return remember(owner, key) {
        val factory =
            viewModelFactory {
                initializer {
                    WaThreadViewModel(
                        WaThreadSources(
                            offline = container.waOffline,
                            remote = container.waRemote,
                            drafts = StoreWaDrafts(container.store),
                            names = container.names,
                            divider = { incoming, unread ->
                                container.chat
                                    .unreadDivider(UnreadDividerRequest(incoming, unread))
                                    .getOrNull()
                                    ?.index
                            },
                            searchJump = { request ->
                                container.chat
                                    .searchJump(request)
                                    .getOrNull()
                                    ?.step
                            },
                            now = System::currentTimeMillis,
                            media = container.waMedia,
                            voice = container.waVoice,
                            emoji = container.waEmoji,
                            stage = { uri -> stageForWhatsApp(container, uri) },
                            readable = { number ->
                                withContext(Dispatchers.Default) {
                                    PhoneNumbers.forReading(number, container.homeRegion)
                                }
                            },
                            rekeys = container.waOffline.rekeys.rekeys,
                        ),
                        jid,
                        ResourceWaWords(res) { bytes -> Formatter.formatShortFileSize(context, bytes) },
                        ResourceInboxWords(res),
                        days,
                    )
                }
            }
        ViewModelProvider(owner, factory)[key, WaThreadViewModel::class.java]
    }
}

/** A WhatsApp conversation page (E6.4a), read from the offline store (E6.4s). */
@Composable
fun WaThreadPage(
    container: MessagesContainer,
    route: MessagesRoute.WaThread,
    navigator: TabNavigator<MessagesRoute>,
    onBack: () -> Unit,
) {
    val res = LocalResources.current
    val model = rememberWaThreadViewModel(container, route.jid)
    val state by model.state.collectAsState()
    val composer = rememberTextFieldState()
    val listState = rememberLazyListState()
    val overlays = LocalOverlay.current
    LaunchedEffect(model) { composer.setTextAndPlaceCursorAtEnd(model.initialDraft(route.draft)) }
    LaunchedEffect(model) {
        @OptIn(FlowPreview::class)
        snapshotFlow { composer.text.toString() }.drop(1).debounce(DRAFT_DEBOUNCE_MS).collect(model::onDraft)
    }
    DisposableEffect(model) { onDispose { model.onDraft(composer.text.toString()) } }
    WhileResumed(model)
    OlderOnReachingTheEnd(listState, state.items.size, state.hasOlder && !state.loadingOlder, model::loadOlder)
    ConversationEffects(model.effects, overlays, composer) { container.clipboard.copy(it) }
    WaMediaEffects(model, container, overlays)
    val voice by model.voice.collectAsState()
    DisposableEffect(model) { onDispose { model.stopVoice() } }
    val attachMenu = rememberWaAttachMenu(model, overlays)
    val host =
        rememberToolHost(
            container,
            overlays,
            bubbles = { bubblesOf(model.state.value.items) },
            title = { model.state.value.title },
        )
    // A search hit opened from the inbox: scroll to it and let it glow once it is loaded.
    LaunchedEffect(model, host) { model.reveals.collect { host.tools.reveal(it) } }
    LaunchedEffect(model, route.reveal) { route.reveal?.let { model.reveal(it, route.revealAtMs) } }
    val toolsState by host.tools.state.collectAsState()
    val labels = rememberToolLabels()
    val detector = rememberDetector(container)
    val context = LocalContext.current
    // The key the chat is stored under: the route's id until it is resolved, another one after a merge.
    val jid by model.chatJid.collectAsState()
    val tag = WaRekeys.tagOf(jid)
    // The number the id carries at once; the one the store knows for the person (a LID with a number) once read.
    val number by produceState(WaIds.number(jid), jid, state.loaded) {
        value =
            container.names
                .numbersOf(jid)
                .firstOrNull()
                ?.takeIf { !WaIds.isGroup(jid) }
    }
    TrackOpenConversation(tag)
    val schedule = rememberWaScheduleHost(container, overlays, jid, composer)
    val marks = rememberMarkHost(container, overlays, tag, rememberDayLabels()) { model.state.value.title }
    val actions =
        remember(model, res, onBack, host, state.title, attachMenu, navigator, marks, number) {
            ConversationActions(
                onBack = onBack,
                onMenuOpened = marks::menuOpened,
                onSend = { text ->
                    model.send(text)
                    composer.setTextAndPlaceCursorAtEnd("")
                },
                onLoadOlder = model::loadOlder,
                onBubbleTap = { bubble ->
                    when {
                        bubble.state == BubbleState.Failed -> model.retry(bubble.key)

                        // A picture opens, a file downloads and opens; a second tap on a text reacts (M32).
                        bubble.media.isNotEmpty() && !bubble.deleted -> model.openMedia(bubble.key)

                        else -> model.tap(bubble.key)
                    }
                },
                menuFor = { waBubbleMenu(it, res, model, marks::menu) },
                // E6.5: the reaction row of the menu (recent emoji first, M49) and the detection pills.
                reactionsFor = { b ->
                    if (b.deleted) {
                        emptyList()
                    } else {
                        (model.recentEmoji.value + DEFAULT_REACTIONS).distinct().take(
                            REACTION_ROW,
                        )
                    }
                },
                onReact = { b, emoji -> model.react(b.key, emoji) },
                pillsFor = detector::pills,
                onPill = { pill ->
                    handlePill(
                        context,
                        pill,
                        overlays,
                    ) { text, sensitive -> container.clipboard.copy(text, sensitive = sensitive) }
                },
                headerMenu =
                    headerMenu(
                        host,
                        copyables =
                            listOfNotNull(
                                model.state.value.title.takeIf { it.isNotBlank() && it != number }?.let {
                                    res.getString(R.string.tools_copy_name) to it
                                },
                                number?.let { res.getString(R.string.tools_copy_number) to it },
                            ),
                    ),
                onFindQuery = host.tools::setQuery,
                onFindOlder = host.tools::older,
                onFindNewer = host.tools::newer,
                onFindClose = host.tools::closeFind,
                onScrolled = host.tools::scrolled,
                onSwipeReply = { model.startReply(it.key) },
                onCancelReply = model::cancelReply,
                onRemoveAttachment = model::removeAttachment,
                attachMenu = attachMenu,
                onMediaAction = { bubble, action -> model.mediaAction(bubble.key, action) },
                onVoiceToggle = { model.toggleVoice(it.key) },
                onVoiceSeek = { bubble, fraction -> model.seekVoice(bubble.key, fraction) },
                onVoiceSpeed = model::nextVoiceSpeed,
                senderCard = { bubble -> WaSenderCard(bubble, model, container, navigator, overlays) },
            )
        }
    val extras = rememberComposerExtras(container, composer)
    CompositionLocalProvider(LocalWantAvatar provides container.avatars::want) {
        ConversationScreen(
            state = marks.marked(state),
            composer = composer,
            listState = listState,
            actions = actions,
            sendLabel = stringResource(R.string.conversation_send),
            olderLabel =
                stringResource(
                    if (state.loadingOlder) R.string.conversation_loading else R.string.conversation_load_earlier,
                ),
            tools = toolsState,
            labels = labels,
            voice = voice,
            composerModifier = Modifier.pastedPictures(model),
            onSendLongPress = schedule::ask,
            sendLaterLabel = schedule.words.title,
            aboveComposer = { ScheduledStrip(schedule.strip, schedule.words, schedule::cancel, schedule::expand) },
            textScale = extras.textScale,
            quickReplies = extras.chips,
            onQuickReply = extras.insert,
        )
    }
}

/** Incoming messages are marked read only while the page is in front of the user. */
@Composable
private fun WhileResumed(model: WaThreadViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer =
            LifecycleEventObserver { _, _ ->
                model.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
        lifecycle.addObserver(observer)
        model.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            model.setVisible(false)
        }
    }
}

/**
 * Scrolling to the oldest loaded message asks for the page before it (from the store, else from the gateway); the
 * "Load Earlier" button stays for a reader who would rather tap.
 */
@Composable
private fun OlderOnReachingTheEnd(
    listState: LazyListState,
    count: Int,
    enabled: Boolean,
    loadOlder: () -> Unit,
) {
    LaunchedEffect(listState, count, enabled) {
        if (!enabled || count == 0) return@LaunchedEffect
        snapshotFlow {
            val last =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: -1
            listState.isScrollInProgress && last >= count - END_SLACK
        }.collect { atEnd -> if (atEnd) loadOlder() }
    }
}

private const val DRAFT_DEBOUNCE_MS = 400L
private const val END_SLACK = 2

/** The reactions offered after the recent ones (WhatsApp's own quick row). */
private val DEFAULT_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private const val REACTION_ROW = 6
