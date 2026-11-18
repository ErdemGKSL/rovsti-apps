package dev.rosty.messages.settings.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.model.wa.ChatActionKind
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.settings.Footnote
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.settings.switchItem
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.shell.permissions.permissionSettingsItem
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsPage
import dev.rosty.wa.vault.VaultOutcome
import dev.rosty.wa.vault.WaVaultState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Test tags of the Notifications page. */
object NotificationSettingsTags {
    const val Page = "messages-notification-settings"
    const val Master = "messages-notifications-switch"
    const val OtpCopy = "messages-otp-copy-switch"

    const val WaLocked = "messages-muted-wa-locked"

    fun muted(tag: String) = "messages-muted-$tag"
}

/**
 * Settings, Notifications: the app's own switch and "Copy code" (what E6.9's notifier follows), Android's permission,
 * and the muted conversations with Unmute.
 */
@Composable
fun NotificationSettingsPage(
    ui: NotificationSettingsUi,
    onAction: (NotificationSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    showPermission: Boolean = true,
) {
    SettingsPage(
        title = stringResource(R.string.notify_settings_title),
        modifier = modifier.testTag(NotificationSettingsTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            items =
                listOf(
                    switchItem(
                        title = stringResource(R.string.notify_settings_switch),
                        checked = ui.notifications,
                        tag = NotificationSettingsTags.Master,
                        icon = RostyIcons.Bell,
                        description = stringResource(R.string.notify_settings_switch_detail),
                    ) { onAction(NotificationSettingsAction.SetNotifications(!ui.notifications)) },
                    switchItem(
                        title = stringResource(R.string.notify_settings_otp),
                        checked = ui.otpCopy,
                        tag = NotificationSettingsTags.OtpCopy,
                        icon = RostyIcons.Copy,
                        description = stringResource(R.string.notify_settings_otp_detail),
                    ) { onAction(NotificationSettingsAction.SetOtpCopy(!ui.otpCopy)) },
                ),
        )
        if (showPermission) {
            IosSettingsGroup(
                title = stringResource(R.string.notify_settings_system),
                items = listOf(permissionSettingsItem(PermissionGroup.NOTIFICATIONS)),
            )
        }
        val waLockedRow =
            if (ui.waLocked) {
                listOf(
                    IosSettingsItem(
                        icon = rememberLockIcon(),
                        title = {
                            IosText(
                                stringResource(R.string.notify_settings_wa_locked),
                                Modifier.testTag(NotificationSettingsTags.WaLocked),
                                color = IosTheme.tint,
                            )
                        },
                        onClick = { onAction(NotificationSettingsAction.UnlockWhatsApp) },
                    ),
                )
            } else {
                emptyList()
            }
        IosSettingsGroup(
            title = stringResource(R.string.notify_settings_muted),
            items =
                ui.muted
                    .map { row ->
                        IosSettingsItem(
                            icon = rememberMutedIcon(),
                            title = {
                                IosText(
                                    row.title ?: stringResource(R.string.notify_settings_unknown),
                                    Modifier.testTag(NotificationSettingsTags.muted(row.tag)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                IosText(stringResource(R.string.notify_settings_unmute), color = IosTheme.tint)
                            },
                            onClick = { onAction(NotificationSettingsAction.Unmute(row.tag)) },
                        )
                    }.plus(waLockedRow)
                    .ifEmpty {
                        listOf(
                            IosSettingsItem(
                                title = {
                                    IosText(
                                        stringResource(R.string.notify_settings_none_muted),
                                        color = IosTheme.colors.secondaryLabel,
                                    )
                                },
                            ),
                        )
                    },
        )
        Footnote(stringResource(R.string.notify_settings_muted_footer))
    }
}

@Composable
private fun rememberMutedIcon() =
    androidx.compose.ui.graphics.vector
        .rememberVectorPainter(RostyIcons.BellSlash)

@Composable
private fun rememberLockIcon() =
    androidx.compose.ui.graphics.vector
        .rememberVectorPainter(RostyIcons.Shield)

/** The Notifications route: the page over its view model, kept by the activity. */
@Composable
fun NotificationSettingsEntry(
    container: MessagesContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    /** The WhatsApp page behind the vault: its unlock page asks for the passphrase when the biometric cannot. */
    openWhatsAppUnlock: () -> Unit = {},
) {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    val unlockScope = rememberCoroutineScope()
    val model =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        NotificationSettingsViewModel(
                            store = container.store,
                            ops = container.ops,
                            nameOf = { tag -> container.nameOfTag(tag) },
                            now = SystemRostyClock::nowMillis,
                            nameChanges = container.names.changes,
                            waMutes = container.waMutes(),
                            unmuteWa = { jid -> container.waChats.act(jid, ChatActionKind.Unmute) },
                            unlockWa = {
                                unlockScope.launch {
                                    if (!container.unlockWhatsAppHere()) openWhatsAppUnlock()
                                }
                            },
                        )
                    }
                }
            ViewModelProvider(owner, factory)[NotificationSettingsViewModel::class.java]
        }
    val ui by model.state.collectAsState()
    NotificationSettingsPage(ui, model::onAction, onBack = onBack, onBackLongClick = onBackLongClick)
}

/** The name behind a notification tag by the name-order rule: a group's subject, else the people of the thread. */
internal suspend fun MessagesContainer.nameOfTag(tag: String): String? =
    when {
        tag.startsWith("wa:") -> {
            val jid = tag.removePrefix("wa:")
            names.chatTitle(jid, withTimeoutOrNull(LOOKUP_MS) { waChats.chats.first() }?.firstOrNull { it.jid == jid })
        }

        tag.startsWith("sms:") -> {
            val id = tag.removePrefix("sms:").toLongOrNull()
            val thread =
                withTimeoutOrNull(LOOKUP_MS) { sms.threads().first() }?.firstOrNull { it.threadId == id }
            thread?.addresses?.map { address -> names.of(address).text }?.joinToString()
        }

        else -> {
            null
        }
    }

/**
 * WhatsApp's mutes for the Notifications page (round 7a): the chats in memory while the vault is open, "locked" while
 * it is, nothing without a link.
 */
internal fun MessagesContainer.waMutes(): Flow<WaMutes> =
    combine(waVault.state, waChats.chats.onStart { emit(emptyList()) }) { state, chats ->
        when (state) {
            WaVaultState.Unlocked -> WaMutes.Open(chats.map { WaChatMute(it.jid, it.meta.mutedUntilMs) })
            is WaVaultState.Locked -> WaMutes.Locked
            else -> WaMutes.Hidden
        }
    }

/**
 * The unlock the "Unlock to see WhatsApp chats" row runs (round 7a): an unlocked sibling app opens the vault with no
 * prompt, else the biometric prompt opens over this page. False when the passphrase is needed (the caller opens the
 * WhatsApp page, whose unlock page asks for it).
 */
internal suspend fun MessagesContainer.unlockWhatsAppHere(): Boolean {
    val vault = waVault
    if (vault.unlockFromSibling()) return true
    val state = vault.state.value as? WaVaultState.Locked ?: return vault.unlocked
    if (!state.biometric) return false
    return when (vault.unlockWithBiometric()) {
        VaultOutcome.Done, VaultOutcome.Cancelled -> true
        is VaultOutcome.Refused -> false
    }
}

private const val LOOKUP_MS = 2_000L
