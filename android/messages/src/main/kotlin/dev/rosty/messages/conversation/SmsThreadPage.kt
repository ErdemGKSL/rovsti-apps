package dev.rosty.messages.conversation

import android.content.res.Resources
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
import dev.rosty.messages.inbox.rememberInboxViewModel
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.ui.LocalWantAvatar
import dev.rosty.messages.viewer.picturesOf
import dev.rosty.messages.viewer.showConversationPictures
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import java.time.ZoneId

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

/** The bubble menu of an SMS (E6.3; the full menu with reactions is E6.5): failed ones retry, copy or delete. */
fun smsBubbleMenu(
    bubble: BubbleUi,
    res: Resources,
    model: SmsThreadViewModel,
    onForward: (BubbleUi) -> Unit = {},
): ImmutableList<MenuItem> {
    val items = ArrayList<MenuItem>()
    if (bubble.state == BubbleState.Failed) {
        items += MenuItem.Action(res.getString(R.string.sms_retry), RostyIcons.Rotate) { model.retry(bubble.key) }
    }
    items += MenuItem.Action(res.getString(R.string.conversation_copy), RostyIcons.Copy) { model.copy(bubble.key) }
    if (bubble.text.isNotBlank()) {
        items += MenuItem.Action(res.getString(R.string.conversation_forward), RostyIcons.Forward) { onForward(bubble) }
    }
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
    val inbox = rememberInboxViewModel(container)
    val forward: (BubbleUi) -> Unit = { bubble ->
        overlays.presentForward(context, inbox.state.value.rows, model.tag, bubble.text, onOpen)
    }
    val labels = rememberToolLabels()
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
                menuFor = { smsBubbleMenu(it, res, model, forward) },
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
                        context.getString(R.string.inbox_undo),
                        durationMs = effect.durationMs,
                    )
                }

                is ConversationEffect.Restore -> {
                    composer.setTextAndPlaceCursorAtEnd(effect.text)
                }

                is ConversationEffect.Copy -> {
                    copy(effect.text)
                    overlays.showToast(context.getString(R.string.conversation_copied))
                }

                ConversationEffect.NeedsRole -> {
                    overlays.showToast(context.getString(R.string.inbox_needs_role))
                }
            }
        }
    }
}

private const val DRAFT_DEBOUNCE_MS = 400L
