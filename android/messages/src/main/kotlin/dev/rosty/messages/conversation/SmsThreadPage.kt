package dev.rosty.messages.conversation

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.ChatUnread
import dev.rosty.core.model.chat.NextUnreadRequest
import dev.rosty.core.model.chat.SearchJumpRequest
import dev.rosty.core.model.chat.SearchJumpStep
import dev.rosty.core.ops.ChatOps
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.resolve
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.compose.presentForward
import dev.rosty.messages.inbox.InboxAction
import dev.rosty.messages.inbox.presentMute
import dev.rosty.messages.inbox.rememberInboxViewModel
import dev.rosty.messages.notify.TrackOpenConversation
import dev.rosty.messages.schedule.ScheduledStrip
import dev.rosty.messages.schedule.rememberSmsScheduleHost
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.ui.LocalWantAvatar
import dev.rosty.messages.viewer.openAttachment
import dev.rosty.messages.viewer.picturesOf
import dev.rosty.messages.viewer.showConversationPictures
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** [SmsWords] from the app's resources. */
class ResourceSmsWords(
    private val res: Resources,
) : SmsWords {
    override val placeholder = res.getString(R.string.sms_placeholder)
    override val sending = res.getString(R.string.sms_sending)
    override val notSent = res.getString(R.string.sms_not_sent)
    override val now = res.getString(R.string.sms_now)
    override val readOnly = res.getString(R.string.sms_read_only)

    override fun counter(
        left: Int,
        parts: Int,
        unicode: Boolean,
    ) = res.getString(if (unicode) R.string.sms_counter_unicode else R.string.sms_counter, left, parts)

    override fun people(count: Int) = res.getQuantityString(R.plurals.sms_people, count, count)

    override fun unread(count: Int) = res.getQuantityString(R.plurals.conversation_unread, count, count)

    override fun part(contentType: String) =
        res.getString(
            when {
                contentType.startsWith("image/") -> R.string.wa_photo
                contentType.startsWith("video/") -> R.string.wa_video
                contentType.startsWith("audio/") -> R.string.wa_audio
                contentType.contains("vcard", ignoreCase = true) -> R.string.sms_contact_card
                else -> R.string.sms_attachment
            },
        )
}

/** The day labels and clocks in the phone's language, zone and 24-hour setting. */
@Composable
fun rememberDayLabels(): DayLabels {
    val context = LocalContext.current
    val res = LocalResources.current
    return remember(res) {
        DayLabels(
            ZoneId.systemDefault(),
            res.configuration.locales[0],
            android.text.format.DateFormat
                .is24HourFormat(context),
            res.getString(R.string.conversation_today),
            res.getString(R.string.inbox_yesterday),
        )
    }
}

/** What the SMS bubble menu does besides the view model's own actions (E6.5). */
class SmsMenuHooks(
    val onForward: (BubbleUi) -> Unit = {},
    val onSelectText: (BubbleUi) -> Unit = {},
    val onSelect: (BubbleUi) -> Unit = {},
    val onInfo: (BubbleUi) -> Unit = {},
)

/**
 * The bubble menu of an SMS (E6.3, E6.5): Retry for a failed one, Copy, Select Text (M16), Forward (M9), Select (M20),
 * Info (M14), then Delete.
 */
fun smsBubbleMenu(
    bubble: BubbleUi,
    res: Resources,
    model: SmsThreadViewModel,
    hooks: SmsMenuHooks = SmsMenuHooks(),
): ImmutableList<MenuItem> {
    val items = ArrayList<MenuItem>()
    if (bubble.state == BubbleState.Failed) {
        items += MenuItem.Action(res.getString(R.string.sms_retry), RostyIcons.Rotate) { model.retry(bubble.key) }
    }
    items += MenuItem.Action(res.getString(R.string.conversation_copy), RostyIcons.Copy) { model.copy(bubble.key) }
    if (bubble.text.isNotBlank()) {
        items +=
            MenuItem.Action(res.getString(R.string.menu_select_text), RostyIcons.Pencil) { hooks.onSelectText(bubble) }
        items +=
            MenuItem.Action(
                res.getString(R.string.conversation_forward),
                RostyIcons.Forward,
            ) { hooks.onForward(bubble) }
    }
    items += MenuItem.Action(res.getString(R.string.menu_select), RostyIcons.CircleCheck) { hooks.onSelect(bubble) }
    items += MenuItem.Action(res.getString(R.string.menu_info), RostyIcons.Info) { hooks.onInfo(bubble) }
    items += MenuItem.Separator
    items +=
        MenuItem.Action(res.getString(R.string.inbox_delete), RostyIcons.Trash, destructive = true) {
            if (bubble.state == BubbleState.Failed) model.discard(bubble.key) else model.delete(bubble.key)
        }
    return items.toImmutableList()
}

/** An SMS/MMS conversation page (E6.3). One view model per thread, kept while the page is on the stack. */
@Composable
fun SmsThreadPage(
    container: MessagesContainer,
    route: MessagesRoute.SmsThread,
    onBack: () -> Unit,
    onOpen: (MessagesRoute) -> Unit = {},
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val days = rememberDayLabels()
    val owner = remember(context) { context.findComponentActivity() }
    val key = "sms-${route.threadId}-${route.addresses.joinToString(",")}"
    val model =
        remember(owner, key) {
            val factory =
                viewModelFactory {
                    initializer {
                        SmsThreadViewModel(
                            SmsThreadSources(
                                sms = container.sms,
                                ops = container.ops,
                                chat = container.chat,
                                store = container.store,
                                book = container.book.book,
                                roleHeld = container.smsRole,
                                now = System::currentTimeMillis,
                            ),
                            route.threadId,
                            route.addresses,
                            ResourceSmsWords(res),
                            days,
                        )
                    }
                }
            ViewModelProvider(owner, factory)[key, SmsThreadViewModel::class.java]
        }
    val state by model.state.collectAsState()
    val composer = rememberTextFieldState()
    val listState = rememberLazyListState()
    val overlays = LocalOverlay.current
    LaunchedEffect(model) { composer.setTextAndPlaceCursorAtEnd(model.initialDraft(route.draft)) }
    LaunchedEffect(model) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { composer.text.toString() }.drop(1).debounce(DRAFT_DEBOUNCE_MS).collect(model::onDraft)
    }
    DisposableEffect(model) { onDispose { model.onDraft(composer.text.toString()) } }
    ConversationEffects(model.effects, overlays, composer) { container.clipboard.copy(it) }
    val host =
        rememberToolHost(
            container,
            overlays,
            bubbles = { bubblesOf(model.state.value.items) },
            title = { model.state.value.title },
        )
    val toolsState by host.tools.state.collectAsState()
    RevealHit(route.reveal, route.revealAtMs, state, host, container.chat, model::loadOlder)
    val inbox = rememberInboxViewModel(container)
    val forward: (BubbleUi) -> Unit = { bubble ->
        overlays.presentForward(context, inbox.state.value.rows, model.tag, bubble.text, onOpen)
    }
    val hooks =
        remember(model, host) {
            SmsMenuHooks(
                onForward = forward,
                onSelectText = { overlays.presentSelectText(context, it.text) },
                onSelect = { host.tools.enterSelect(it.key) },
                onInfo = { b ->
                    val mms = b.key.startsWith("mms:")
                    overlays.presentInfo(
                        context,
                        infoRows(res, b, res.getString(if (mms) R.string.info_mms else R.string.inbox_chip_sms)),
                    )
                },
            )
        }
    val stage: (Uri) -> Unit = { uri -> container.scope.launch { copyPicture(context, uri)?.let(model::attach) } }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(stage) }
    val detector = rememberDetector(container)
    val selectLabels = rememberSelectLabels()
    val labels = rememberToolLabels()
    TrackOpenConversation(model.tag)
    val schedule = rememberSmsScheduleHost(container, overlays, route, composer)
    val actions =
        remember(model, res, onBack, overlays, host, state.title) {
            ConversationActions(
                onBack = onBack,
                onSend = { text ->
                    model.send(text)
                    composer.setTextAndPlaceCursorAtEnd("")
                },
                onLoadOlder = model::loadOlder,
                onBubbleTap = { bubble ->
                    when {
                        bubble.state == BubbleState.Failed -> {
                            model.retry(bubble.key)
                        }

                        bubble.media.any { !it.isImage } && bubble.media.none { it.isImage } -> {
                            container.scope.launch {
                                overlays.openAttachment(
                                    context,
                                    bubble.media.first(),
                                    container.chat,
                                    container.files,
                                    container.scope,
                                )
                            }
                        }

                        bubble.media.any { it.isImage } -> {
                            overlays.showConversationPictures(
                                context,
                                picturesOf(model.state.value.items),
                                bubble.key,
                                model.state.value.title,
                                container.files,
                                container.scope,
                            )
                        }
                    }
                },
                menuFor = { smsBubbleMenu(it, res, model, hooks) },
                pillsFor = detector::pills,
                attachMenu =
                    persistentListOf(
                        MenuItem.Action(res.getString(R.string.composer_attach), RostyIcons.Photo) {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    ),
                onRemoveAttachment = model::clearAttachment,
                onPill = { pill ->
                    handlePill(
                        context,
                        pill,
                        overlays,
                    ) { text, sensitive -> container.clipboard.copy(text, sensitive = sensitive) }
                    // M47: a copied code books its message for deletion when the user turned that on.
                    if (pill.kind == PillKind.CopyCode) {
                        container.scope.launch { container.codeCleanup.copied(route.threadId, pill.value) }
                    }
                },
                onToggleSelect = host.tools::toggleSelect,
                onSelectAction = { action ->
                    val chosen =
                        host.tools.state.value.selected
                            .orEmpty()
                    when (action) {
                        SelectAction.Copy -> {
                            container.scope.launch {
                                host.tools
                                    .selectionText(
                                        model.state.value.title,
                                        res.getString(R.string.tools_me),
                                    )?.let(host.copy)
                                host.tools.exitSelect()
                            }
                        }

                        SelectAction.Forward -> {
                            container.scope.launch {
                                val text =
                                    host.tools.selectionText(
                                        model.state.value.title,
                                        res.getString(R.string.tools_me),
                                    )
                                host.tools.exitSelect()
                                if (text !=
                                    null
                                ) {
                                    overlays.presentForward(
                                        context,
                                        inbox.state.value.rows,
                                        model.tag,
                                        text,
                                        onOpen,
                                    )
                                }
                            }
                        }

                        SelectAction.Delete -> {
                            model.deleteAll(chosen)
                            host.tools.exitSelect()
                        }

                        SelectAction.Cancel -> {
                            host.tools.exitSelect()
                        }
                    }
                },
                onSim = model::nextSim,
                headerMenu =
                    headerMenu(
                        host,
                        copyables =
                            listOfNotNull(
                                model.state.value.title.takeIf { t -> route.addresses.singleOrNull() != t }?.let {
                                    res.getString(R.string.tools_copy_name) to it
                                },
                                route.addresses.singleOrNull()?.let { res.getString(R.string.tools_copy_number) to it },
                            ),
                        extra =
                            listOfNotNull(
                                inbox.state.value.rows.firstOrNull { it.smsThread == route.threadId }?.let { row ->
                                    // M29: mute this conversation from inside it.
                                    MenuItem.Action(res.getString(R.string.inbox_mute_menu), RostyIcons.BellSlash) {
                                        presentMute(overlays, res) { inbox.onAction(InboxAction.Mute(row.key, it)) }
                                    }
                                },
                                // M43: the next chat with unread messages, in the inbox's order.
                                MenuItem.Action(res.getString(R.string.tools_next_unread), RostyIcons.ArrowDown) {
                                    container.scope.launch {
                                        val rows = inbox.state.value.rows
                                        val current = rows.firstOrNull { it.smsThread == route.threadId }?.key
                                        val next =
                                            container.chat
                                                .nextUnread(
                                                    NextUnreadRequest(
                                                        rows.map { ChatUnread(it.key, it.isUnread) },
                                                        current,
                                                    ),
                                                ).getOrNull()
                                                ?.index
                                                ?.let(rows::getOrNull)
                                        val target =
                                            next?.smsThread?.let {
                                                MessagesRoute.SmsThread(
                                                    it,
                                                    listOfNotNull(next.smsSender),
                                                )
                                            }
                                                ?: next?.waChat?.let { MessagesRoute.WaThread(it) }
                                        if (target == null) {
                                            overlays.showToast(res.getString(R.string.inbox_none_unread))
                                        } else {
                                            onOpen(target)
                                        }
                                    }
                                },
                            ),
                    ),
                onFindQuery = host.tools::setQuery,
                onFindOlder = host.tools::older,
                onFindNewer = host.tools::newer,
                onFindClose = host.tools::closeFind,
                onScrolled = host.tools::scrolled,
                onRoleResult = {
                    container.refreshSmsRole()
                    model.refreshRole()
                },
            )
        }
    CompositionLocalProvider(LocalWantAvatar provides container.avatars::want) {
        ConversationScreen(
            state = state,
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
            selectLabels = selectLabels,
            composerModifier = Modifier.pasteImages(stage),
            onSendLongPress = schedule::ask,
            sendLaterLabel = schedule.words.title,
            aboveComposer = { ScheduledStrip(schedule.strip, schedule.words, schedule::cancel, schedule::expand) },
        )
    }
}

/** Pills, undo pills, copies and composer restores of a conversation's view model. */
@Composable
fun ConversationEffects(
    effects: kotlinx.coroutines.flow.Flow<ConversationEffect>,
    overlays: OverlayController,
    composer: androidx.compose.foundation.text.input.TextFieldState,
    copy: (String) -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is ConversationEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is ConversationEffect.Undo -> {
                    overlays.showUndo(
                        effect.text.resolve(context),
                        effect.undo,
                        res.getString(R.string.inbox_undo),
                        durationMs = effect.durationMs,
                    )
                }

                is ConversationEffect.Restore -> {
                    composer.setTextAndPlaceCursorAtEnd(effect.text)
                }

                is ConversationEffect.Copy -> {
                    copy(effect.text)
                    overlays.showToast(res.getString(R.string.conversation_copied))
                }

                ConversationEffect.NeedsRole -> {
                    overlays.showToast(res.getString(R.string.inbox_needs_role))
                }
            }
        }
    }
}

private const val DRAFT_DEBOUNCE_MS = 400L

/**
 * Works towards a global search hit (M7): older pages load until [key] is there (`global_search.jump` decides when to
 * stop), then the page scrolls to it and it glows. Runs once per page.
 */
@Composable
internal fun RevealHit(
    key: String?,
    atMs: Long?,
    state: ConversationUi,
    host: ToolHost,
    chat: ChatOps,
    loadOlder: () -> Unit,
) {
    if (key == null) return
    val done = rememberSaveable(key) { mutableStateOf(false) }
    val pages = remember(key) { intArrayOf(0) }
    LaunchedEffect(key, state.items, state.loaded, state.loadingOlder) {
        if (done.value || !state.loaded || state.loadingOlder) return@LaunchedEffect
        val bubbles = bubblesOf(state.items)
        val loaded = bubbles.any { it.key == key }
        val request =
            SearchJumpRequest(
                loaded = loaded,
                hasOlder = state.hasOlder,
                pagesLoaded = pages[0],
                oldestMs = bubbles.minOfOrNull { it.atMs },
                targetMs = atMs ?: 0L,
            )
        when (chat.searchJump(request).getOrNull()?.step ?: SearchJumpStep.Gone) {
            SearchJumpStep.Reveal -> {
                done.value = true
                if (loaded) host.tools.reveal(key)
            }

            SearchJumpStep.LoadOlder -> {
                pages[0]++
                loadOlder()
            }

            SearchJumpStep.FurtherBack -> {
                done.value = true
                host.overlays.showToast(host.context.getString(R.string.tools_further_back))
            }

            SearchJumpStep.Gone -> {
                done.value = true
                host.overlays.showToast(host.context.getString(R.string.tools_message_gone))
            }
        }
    }
}

/** Copies a picked or pasted picture into the app's cache, where the core can read it for the MMS. */
internal suspend fun copyPicture(
    c: Context,
    uri: Uri,
): File? =
    withContext(Dispatchers.IO) {
        val mime = c.contentResolver.getType(uri) ?: "image/jpeg"
        val ext =
            when (mime) {
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "jpg"
            }
        val dir = File(c.cacheDir, "mms-out").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        runCatching {
            c.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        }.getOrNull() ?: return@withContext null
        file
    }
