package dev.rosty.stops.notice

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.model.stops.NoticeWhen
import dev.rosty.core.model.wa.AppId
import dev.rosty.core.ops.NativeWaSettingsOps
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.text.resolve
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsPage
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.home.findComponentActivity
import dev.rosty.stops.settings.Footnote
import dev.rosty.stops.settings.NoChevron
import dev.rosty.stops.settings.SettingsMetrics
import dev.rosty.stops.settings.Value
import dev.rosty.stops.settings.ValueChevron
import dev.rosty.stops.settings.choiceItem
import dev.rosty.stops.settings.tile
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.WaLinkStore
import dev.rosty.wa.settings.WhatsAppSettingsRoute
import dev.rosty.wa.settings.WhatsAppSettingsViewModel

/** Test tags of the arriving-soon pages. */
object NoticeTags {
    const val Page = "stops-notice-page"
    const val Chat = "stops-notice-chat"
    const val TurnOff = "stops-notice-turn-off"
    const val Template = "stops-notice-template"
    const val Preview = "stops-notice-preview"
    const val Problem = "stops-notice-problem"
    const val Save = "stops-notice-save"
    const val Default = "stops-notice-default"
    const val WhatsApp = "stops-notice-whatsapp"
    const val Picker = "stops-notice-picker"
    const val Search = "stops-notice-search"
    const val SettingsRow = "stops-settings-notice"
    const val SettingsWhatsApp = "stops-settings-whatsapp"

    fun sendWhen(sendWhen: NoticeWhen): String =
        when (sendWhen) {
            NoticeWhen.WithAlarm -> "stops-notice-when-alarm"
            is NoticeWhen.MinutesBefore -> "stops-notice-when-${sendWhen.minutes}"
        }

    fun chat(jid: String): String = "stops-notice-chat-$jid"
}

/** What the rows of the arriving-soon page do; no-op defaults for tests. */
@Immutable
class NoticePageActions(
    val pickChat: () -> Unit = {},
    val turnOff: () -> Unit = {},
    val save: () -> Unit = {},
    val useDefault: () -> Unit = {},
    val setWhen: (NoticeWhen) -> Unit = {},
    val openWhatsApp: () -> Unit = {},
)

/** The arriving-soon view model, owned by the activity like the other settings view models. */
@Composable
fun rememberNoticeViewModel(container: StopsContainer): NoticeViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    val sample = stringResource(R.string.notice_sample_stop)
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    NoticeViewModel(
                        store = container.store,
                        ops = container.ops,
                        wa = container.wa,
                        names = { chats ->
                            // What WhatsApp knows about each person (the saved contact name, the business name)
                            // is asked for once; the names follow when it arrives.
                            chats.forEach { if (!it.isGroup) container.avatars.wantProfile(it.jid) }
                            namedChats(chats, container.avatars::profile, container.waOps::names)
                        },
                        sampleStop = sample,
                        namesChanged = container.avatars.revision,
                    )
                }
            }
        ViewModelProvider(owner, factory)[NoticeViewModel::class.java]
    }
}

@Composable
private fun NoticeEffects(
    viewModel: NoticeViewModel,
    onChatChosen: () -> Unit = {},
) {
    val overlays = LocalOverlayOrNull.current
    val context = LocalContext.current
    val undo = stringResource(R.string.settings_undo)
    LaunchedEffect(viewModel, overlays) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NoticeEffect.Toast -> overlays?.showToast(effect.text.resolve(context))
                is NoticeEffect.Undo -> overlays?.showUndo(effect.text.resolve(context), effect.undo, undo)
                NoticeEffect.ChatChosen -> onChatChosen()
            }
        }
    }
}

/** Settings > Arriving-soon message, pushed over the map. */
@Composable
fun ArrivingSoonEntry(
    container: StopsContainer,
    onOpen: (StopsRoute) -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val vm = rememberNoticeViewModel(container)
    val state by vm.state.collectAsState()
    NoticeEffects(vm)
    LaunchedEffect(vm) { snapshotFlow { vm.templateField.text.toString() }.collect(vm::templateChanged) }
    ArrivingSoonPage(
        state = state,
        template = vm.templateField,
        actions =
            NoticePageActions(
                pickChat = { onOpen(StopsRoute.ArrivingSoonChat) },
                turnOff = vm::turnOff,
                save = vm::saveTemplate,
                useDefault = vm::useDefault,
                setWhen = vm::setWhen,
                openWhatsApp = { onOpen(StopsRoute.WhatsApp) },
            ),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

/** The chat picker of the arriving-soon message, pushed over its page; choosing a chat goes back. */
@Composable
fun ArrivingSoonChatEntry(
    container: StopsContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val vm = rememberNoticeViewModel(container)
    val state by vm.state.collectAsState()
    NoticeEffects(vm, onChatChosen = onBack)
    LaunchedEffect(vm) { snapshotFlow { vm.searchField.text.toString() }.collect(vm::searchChanged) }
    DisposableEffect(vm) {
        vm.openChats()
        onDispose { vm.closeChats() }
    }
    NoticeChatPage(
        chats = state.chats,
        chosen = state.chat,
        search = vm.searchField,
        onChoose = vm::chooseChat,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

/** Settings > WhatsApp: the shared screen of `:wa` (link, connect key, keys, relink, delete), for the Stops app. */
@Composable
fun StopsWhatsAppEntry(container: StopsContainer) {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    val viewModel =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        WhatsAppSettingsViewModel(
                            app = AppId.Stops,
                            machine = NativeWaSettingsOps(container.rust),
                            ops = container.waOps,
                            store = WaLinkStore(container.waOps),
                            repo = container.wa,
                            qr = container.qr,
                            clipboard = container.clipboard,
                            files = container.files,
                            stagingDir = container.context.cacheDir,
                            clock = System::currentTimeMillis,
                            saved = SavedStateHandle(),
                        )
                    }
                }
            ViewModelProvider(owner, factory)[WhatsAppSettingsViewModel::class.java]
        }
    WhatsAppSettingsRoute(viewModel)
}

/** "Connected", "Not connected", ...: the link in a word, for a row. */
@Composable
fun linkText(
    link: WaLinkState,
    settled: Boolean,
): String =
    when (link) {
        is WaLinkState.Connected -> stringResource(R.string.notice_wa_connected)
        is WaLinkState.Linking -> stringResource(R.string.notice_wa_linking)
        WaLinkState.LoggedOut -> stringResource(R.string.notice_wa_logged_out)
        WaLinkState.Banned -> stringResource(R.string.notice_wa_banned)
        is WaLinkState.Error -> stringResource(link.kind.text)
        WaLinkState.None -> if (settled) stringResource(R.string.notice_wa_not_connected) else ""
    }

/**
 * The page (AD-ST7). While WhatsApp is connected: the chat, the message with its preview, and when it goes out.
 * Otherwise one row that leads to Settings > WhatsApp; the saved message is kept and shows again once connected.
 */
@Composable
fun ArrivingSoonPage(
    state: NoticeUiState,
    template: TextFieldState,
    actions: NoticePageActions,
    onBack: () -> Unit = {},
    onBackLongClick: () -> Unit = {},
) {
    SettingsPage(
        title = stringResource(R.string.notice_title),
        modifier = Modifier.testTag(NoticeTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        if (state.loading || !state.linkSettled) return@SettingsPage
        if (!state.connected) {
            NotConnected(state, actions)
            return@SettingsPage
        }
        IosSettingsGroup(
            title = stringResource(R.string.notice_send_to),
            items =
                buildList {
                    add(
                        IosSettingsItem(
                            icon = tile(RostyIcons.WhatsApp),
                            title = {
                                IosText(
                                    stringResource(R.string.notice_chat),
                                    Modifier.testTag(NoticeTags.Chat),
                                )
                            },
                            trailingContent = {
                                ValueChevron(if (state.on) state.chatName else stringResource(R.string.notice_none))
                            },
                            trailingShrinks = true,
                            onClick = actions.pickChat,
                        ),
                    )
                    if (state.on) {
                        add(
                            IosSettingsItem(
                                title = {
                                    IosText(
                                        stringResource(R.string.notice_turn_off),
                                        Modifier.testTag(NoticeTags.TurnOff),
                                        color = IosTheme.colors.systemRed,
                                    )
                                },
                                trailingContent = { NoChevron() },
                                onClick = actions.turnOff,
                            ),
                        )
                    }
                },
        )
        Footnote(stringResource(if (state.on) R.string.notice_on_footer else R.string.notice_off_footer))
        MessageField(state, template, actions)
        IosSettingsGroup(
            title = stringResource(R.string.notice_when),
            items =
                state.whenChoices.map { choice ->
                    choiceItem(
                        title = choice.label,
                        chosen = choice.id == state.sendWhen,
                        tag = NoticeTags.sendWhen(choice.id),
                    ) { actions.setWhen(choice.id) }
                },
        )
        Footnote(stringResource(R.string.notice_when_footer))
    }
}

/** The message: the field, how it would read, why it cannot be saved, Save and the way back to the default. */
@Composable
private fun MessageField(
    state: NoticeUiState,
    template: TextFieldState,
    actions: NoticePageActions,
) {
    val inset = Modifier.padding(horizontal = SettingsMetrics.FieldInset + SettingsMetrics.AfterFooter)
    IosText(
        stringResource(R.string.notice_message),
        style = IosTheme.typography.subheadline,
        color = IosTheme.colors.secondaryLabel,
        modifier = inset,
    )
    GlassTextField(
        state = template,
        placeholder = state.defaultTemplate,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsMetrics.FieldInset, vertical = SettingsMetrics.FieldGap)
                .testTag(NoticeTags.Template),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        imeAction = ImeAction.Default,
        maxLines = TEMPLATE_MAX_LINES,
        minLines = TEMPLATE_MIN_LINES,
    )
    val problem = state.problem
    if (problem != null) {
        IosText(
            when (problem) {
                NoticeProblem.Empty -> stringResource(R.string.notice_problem_empty)
                NoticeProblem.TooLong -> stringResource(R.string.notice_problem_long, state.maxChars)
            },
            style = IosTheme.typography.footnote,
            color = IosTheme.colors.systemRed,
            modifier = inset.testTag(NoticeTags.Problem),
        )
    } else if (state.preview.isNotEmpty()) {
        IosText(
            stringResource(R.string.notice_preview, state.preview),
            style = IosTheme.typography.footnote,
            color = IosTheme.colors.secondaryLabel,
            modifier = inset.testTag(NoticeTags.Preview),
        )
    }
    Footnote(stringResource(R.string.notice_message_footer))
    val canSave = state.dirty && problem == null
    val notDefault = state.saved.trim() != state.defaultTemplate.trim() || state.dirty
    IosSettingsGroup(
        items =
            buildList {
                add(
                    IosSettingsItem(
                        title = {
                            IosText(
                                stringResource(R.string.notice_save),
                                Modifier.testTag(NoticeTags.Save),
                                color = if (canSave) IosTheme.tint else IosTheme.colors.tertiaryLabel,
                            )
                        },
                        trailingContent = {
                            if (!state.dirty) {
                                RostyIcon(RostyIcons.Checkmark, null, tint = IosTheme.colors.tertiaryLabel)
                            } else {
                                NoChevron()
                            }
                        },
                        enabled = canSave,
                        onClick = actions.save,
                    ),
                )
                if (notDefault && state.defaultTemplate.isNotEmpty()) {
                    add(
                        IosSettingsItem(
                            title = {
                                IosText(
                                    stringResource(R.string.notice_use_default),
                                    Modifier.testTag(NoticeTags.Default),
                                )
                            },
                            trailingContent = { NoChevron() },
                            onClick = actions.useDefault,
                        ),
                    )
                }
            },
    )
}

@Composable
private fun NotConnected(
    state: NoticeUiState,
    actions: NoticePageActions,
) {
    IosSettingsGroup(
        items =
            listOf(
                IosSettingsItem(
                    icon = tile(RostyIcons.WhatsApp),
                    title = {
                        IosText(
                            stringResource(R.string.notice_whatsapp),
                            Modifier.testTag(NoticeTags.WhatsApp),
                        )
                    },
                    trailingContent = { ValueChevron(linkText(state.link, state.linkSettled)) },
                    trailingShrinks = true,
                    onClick = actions.openWhatsApp,
                ),
            ),
    )
    Footnote(stringResource(R.string.notice_not_connected_footer))
}

/** The chats of the gateway with a search field; the chosen one is ticked, a tap chooses. */
@Composable
fun NoticeChatPage(
    chats: NoticeChats,
    chosen: String,
    search: TextFieldState,
    onChoose: (NoticeChat) -> Unit,
    onBack: () -> Unit = {},
    onBackLongClick: () -> Unit = {},
) {
    SettingsPage(
        title = stringResource(R.string.notice_pick_title),
        modifier = Modifier.testTag(NoticeTags.Picker),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        GlassTextField(
            state = search,
            placeholder = stringResource(R.string.notice_pick_search),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsMetrics.FieldInset, vertical = SettingsMetrics.FieldGap)
                    .testTag(NoticeTags.Search),
            leading = RostyIcons.Search,
            search = true,
        )
        when (chats) {
            NoticeChats.Loading -> {
                Footnote(stringResource(R.string.notice_pick_loading))
            }

            is NoticeChats.Loaded -> {
                if (chats.shown.isEmpty()) {
                    val empty = if (chats.all.isEmpty()) R.string.notice_pick_empty else R.string.notice_pick_no_match
                    Footnote(stringResource(empty))
                } else {
                    val group = stringResource(R.string.notice_pick_group)
                    IosSettingsGroup(
                        items =
                            chats.shown.map { chat ->
                                IosSettingsItem(
                                    title = {
                                        IosText(
                                            chat.name,
                                            Modifier.testTag(NoticeTags.chat(chat.jid)),
                                            maxLines = 1,
                                        )
                                    },
                                    description =
                                        (if (chat.group) group else chat.number)?.let { { IosText(it, maxLines = 1) } },
                                    trailingContent = {
                                        if (chat.jid == chosen) {
                                            RostyIcon(RostyIcons.Checkmark, null, tint = IosTheme.tint)
                                        } else {
                                            NoChevron()
                                        }
                                    },
                                    onClick = { onChoose(chat) },
                                )
                            },
                    )
                }
            }
        }
    }
}

private const val TEMPLATE_MIN_LINES = 2
private const val TEMPLATE_MAX_LINES = 5
