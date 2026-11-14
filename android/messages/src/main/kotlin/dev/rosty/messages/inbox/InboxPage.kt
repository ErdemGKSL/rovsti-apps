package dev.rosty.messages.inbox

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.confirmDestructive
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.resolve
import dev.rosty.design.theme.Spacing
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.ChatsTab
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.access.DefaultSmsCard
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.ui.LocalWantAvatar
import kotlinx.collections.immutable.toImmutableList
import java.time.ZoneId

/** The inbox view model of the activity (kept across the tabs and the conversation pages). */
@Composable
fun rememberInboxViewModel(container: MessagesContainer): InboxViewModel {
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
                            sms = container.sms,
                            smsReadable = container.smsReadable,
                            roleHeld = container.smsRole,
                            wa = container.waChats,
                            book = container.book.book,
                            store = container.store,
                            ops = container.ops,
                            chat = container.chat,
                            now = System::currentTimeMillis,
                        ),
                        InboxBuilder(words, time),
                        words,
                        GlobalSearch(container.chat, container.sms, container.waChats, time, System::currentTimeMillis),
                    )
                }
            }
        ViewModelProvider(owner, factory)[InboxViewModel::class.java]
    }
}

/** The Chats tab root: [InboxScreen] over its view model, the default-SMS ask above it while the role is not held. */
@Composable
fun ChatsPage(
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
) {
    val viewModel = rememberInboxViewModel(container)
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val searchState = rememberTextFieldState()
    val strings = rememberInboxStrings()
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    LaunchedEffect(navigator, listState) {
        navigator.scrollToTop.collect { if (it.tab == ChatsTab) listState.animateScrollToItem(0) }
    }
    InboxEffects(viewModel, navigator, overlays, strings)
    val actions =
        remember(viewModel, overlays, strings) {
            RowMenuActions(
                onAction = viewModel::onAction,
                onMute = { row ->
                    presentMute(overlays, context.resources) { viewModel.onAction(InboxAction.Mute(row.key, it)) }
                },
                onAvatar = { row -> viewModel.onAction(InboxAction.Open(row.key)) },
                onCopyCode = { code ->
                    container.clipboard.copy(code, sensitive = true)
                    overlays.showToast(context.getString(R.string.inbox_code_copied, code))
                },
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
            banner =
                if (!state.roleHeld) {
                    {
                        DefaultSmsCard(
                            onResult = { container.refreshSmsRole() },
                            Modifier.padding(vertical = Spacing.s),
                        )
                    }
                } else {
                    null
                },
        )
    }
}

/** The mute durations (GPUI "Mute for": 8 hours, 1 week, Always) as an action sheet. */
internal fun presentMute(
    overlays: OverlayController,
    res: android.content.res.Resources,
    onPick: (MuteFor) -> Unit,
) {
    val labels =
        mapOf(
            MuteFor.EightHours to R.string.inbox_mute_8h,
            MuteFor.Week to R.string.inbox_mute_week,
            MuteFor.Always to R.string.inbox_mute_always,
        )
    overlays.present(
        ActionSheetEntry(
            title = res.getString(R.string.inbox_mute_for),
            actions =
                MuteFor.entries
                    .map {
                        ActionSheetAction(res.getString(labels.getValue(it))) { onPick(it) }
                    }.toImmutableList(),
            cancelLabel = res.getString(R.string.inbox_cancel),
        ),
    )
}

@Composable
private fun InboxEffects(
    viewModel: InboxViewModel,
    navigator: TabNavigator<MessagesRoute>,
    overlays: OverlayController,
    strings: InboxStrings,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is InboxEffect.Open -> {
                    navigator.push(effect.route)
                }

                is InboxEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is InboxEffect.Undo -> {
                    overlays.showUndo(effect.text.resolve(context), effect.undo, context.getString(R.string.inbox_undo))
                }

                is InboxEffect.ConfirmDelete -> {
                    val message =
                        effect.title?.let { context.getString(R.string.inbox_delete_one, it) }
                            ?: context.resources.getQuantityString(
                                R.plurals.inbox_delete_many,
                                effect.count,
                                effect.count,
                            )
                    overlays.confirmDestructive(
                        destructiveLabel = context.getString(R.string.inbox_delete),
                        onConfirm = effect.confirm,
                        message = message,
                        cancelLabel = strings.cancel,
                    )
                }

                InboxEffect.NeedsRole -> {
                    overlays.showToast(context.getString(R.string.inbox_needs_role))
                }
            }
        }
    }
}
