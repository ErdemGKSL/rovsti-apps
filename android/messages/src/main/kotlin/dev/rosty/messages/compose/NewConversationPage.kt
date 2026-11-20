package dev.rosty.messages.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.design.chrome.ScaffoldSpec
import dev.rosty.design.chrome.ScreenLargeTitle
import dev.rosty.design.chrome.ScreenLazyPage
import dev.rosty.design.controls.EmptyState
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.text.resolve
import dev.rosty.design.theme.Spacing
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.ui.MessagesAvatar
import dev.rosty.messages.wa.group.presentJoinWithLink
import dev.rosty.messages.wa.group.presentNewGroup
import dev.rosty.platform.identity.PhoneNumbers
import kotlinx.collections.immutable.persistentListOf

object NewConversationTags {
    const val Page = "new-conversation"
    const val Field = "new-conversation-field"

    fun option(key: String) = "new-option-$key"
}

/** New Conversation (E6.8); with a [MessagesRoute.NewConversation.share] it is the share-in chooser too. */
@Composable
fun NewConversationPage(
    container: MessagesContainer,
    route: MessagesRoute.NewConversation,
    navigator: TabNavigator<MessagesRoute>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val owner = remember(context) { context.findComponentActivity() }
    val model =
        remember(owner, route) {
            val factory =
                viewModelFactory {
                    initializer {
                        NewConversationViewModel(
                            book = container.book.book,
                            sms = container.sms,
                            wa = container.wa,
                            waOps = container.waOps,
                            e164 = { PhoneNumbers.e164(it, container.homeRegion) },
                            share = route.share,
                            names = container.names,
                        )
                    }
                }
            ViewModelProvider(owner, factory)["new-${route.share}", NewConversationViewModel::class.java]
        }
    val state by model.state.collectAsState()
    val field = rememberTextFieldState()
    val overlays = LocalOverlay.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(model::setQuery) }
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            when (effect) {
                is NewConversationEffect.Open -> {
                    navigator.pop()
                    navigator.push(effect.route)
                }

                is NewConversationEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is NewConversationEffect.ChooseChannel -> {
                    overlays.present(
                        ActionSheetEntry(
                            title = effect.option.title,
                            actions =
                                persistentListOf(
                                    ActionSheetAction(
                                        res.getString(R.string.inbox_chip_sms),
                                    ) { model.openSms(effect.option) },
                                    ActionSheetAction(
                                        res.getString(R.string.tab_whatsapp),
                                    ) { model.openWhatsApp(effect.option) },
                                ),
                            cancelLabel = res.getString(R.string.inbox_cancel),
                        ),
                    )
                }
            }
        }
    }
    val groups = route.share == null && container.wa.linkState.collectAsState().value is dev.rosty.wa.WaLinkState.Connected
    NewConversationScreen(
        state,
        field,
        share = route.share != null,
        onBack = onBack,
        onPick = model::pick,
        onNewGroup = { overlays.presentNewGroup(res, container, scope, navigator) }.takeIf { groups },
        onJoinGroup = { overlays.presentJoinWithLink(res, container, navigator) }.takeIf { groups },
    )
}

/** The stateless page: the field, then the options (typed number first, then contacts). */
@Composable
fun NewConversationScreen(
    state: NewConversationUi,
    field: androidx.compose.foundation.text.input.TextFieldState,
    share: Boolean,
    onBack: () -> Unit,
    onPick: (RecipientOption) -> Unit,
    /** E6-WA R6: "New Group" and "Join Group with Link" (WhatsApp linked); null: not offered. */
    onNewGroup: (() -> Unit)? = null,
    onJoinGroup: (() -> Unit)? = null,
) {
    val title = stringResource(if (share) R.string.new_share_title else R.string.new_conversation_title)
    ScreenLazyPage(
        title = title,
        onBack = onBack,
        modifier = Modifier.testTag(NewConversationTags.Page),
        largeTitle = {
            // ScreenLazyPage leaves the status bar to the title block; the bar row belongs to the back button.
            Spacer(Modifier.statusBarsPadding())
            ScreenLargeTitle(title, top = ScaffoldSpec.BarHeight)
        },
    ) {
        item(key = "field", contentType = "field") {
            GlassTextField(
                field,
                stringResource(R.string.new_field),
                Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag(NewConversationTags.Field),
            )
        }
        // E6-WA R6: a new WhatsApp group, or one joined with a pasted link.
        if (onNewGroup != null && onJoinGroup != null && state.query.isBlank()) {
            item(key = "new-group", contentType = "group") {
                ListRow(
                    title = stringResource(R.string.wa_group_new),
                    modifier = Modifier.testTag(NewConversationTags.option("new-group")),
                    leading = {
                        dev.rosty.design.icons.RostyIcon(
                            RostyIcons.People,
                            null,
                            tint = dev.rosty.design.theme.RostyTheme.colors.accent,
                        )
                    },
                    onClick = onNewGroup,
                )
                RowSeparator(inset = TextStartWithLeading)
                ListRow(
                    title = stringResource(R.string.wa_group_join_with_link),
                    modifier = Modifier.testTag(NewConversationTags.option("join-group")),
                    leading = {
                        dev.rosty.design.icons.RostyIcon(
                            RostyIcons.Share,
                            null,
                            tint = dev.rosty.design.theme.RostyTheme.colors.accent,
                        )
                    },
                    onClick = onJoinGroup,
                )
            }
        }
        if (state.options.isEmpty() && state.query.isNotBlank()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(
                    RostyIcons.Person,
                    stringResource(R.string.new_no_match),
                    "",
                    Modifier.padding(top = Spacing.xl),
                )
            }
        }
        items(state.options, key = { it.key }, contentType = { "option" }) { option ->
            if (option != state.options.first()) RowSeparator(inset = TextStartWithLeading)
            ListRow(
                title = option.title,
                secondary = option.detail,
                modifier = Modifier.testTag(NewConversationTags.option(option.key)),
                leading = {
                    MessagesAvatar(
                        option.title,
                        RowAvatar(
                            photoUri = option.photoUri,
                            numbers = persistentListOf(option.number),
                            colorKey = option.number,
                        ),
                    )
                },
                onClick = { onPick(option) },
            )
        }
    }
}
