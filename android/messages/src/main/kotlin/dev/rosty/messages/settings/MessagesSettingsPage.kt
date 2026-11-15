package dev.rosty.messages.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.messages.ChatTextSize
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosChevron
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.access.AccessTags
import dev.rosty.messages.settings.textsize.ChatTextScale
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.system.permissions.SpecialKind
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.shell.permissions.permissionSettingsItem
import dev.rosty.shell.permissions.specialAccessSettingsItem
import dev.rosty.shell.settings.CommonSettings
import dev.rosty.shell.settings.CommonSettingsGroup
import dev.rosty.shell.settings.CommonSettingsPage
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsPage

/** Test tags of the Messages settings page. */
object SettingsTags {
    const val Page = "messages-settings-page"
    const val Access = "messages-settings-access"
    const val WhatsAppRow = "messages-settings-whatsapp"
    const val SpamRow = "messages-settings-spam"
    const val DefaultHeld = "messages-settings-default-held"
    const val NotificationsRow = "messages-settings-notifications"
    const val QuickRepliesRow = "messages-settings-quick-replies"
    const val TextSizeRow = "messages-settings-text-size"
    const val BackupRow = "messages-settings-backup"
}

/** Where the rows of the settings page lead. */
class SettingsLinks(
    val openWhatsApp: () -> Unit,
    val openCommon: (CommonSettingsPage) -> Unit,
    /** Settings, Spam filter (E6.11). */
    val openSpam: () -> Unit = {},
    /** Settings, Notifications (E6.12). */
    val openNotifications: () -> Unit = {},
    /** Settings, Quick replies (M11). */
    val openQuickReplies: () -> Unit = {},
    /** Settings, Text size (M34). */
    val openTextSize: () -> Unit = {},
    /** Settings, Backup (AD-MSG7). */
    val openBackup: () -> Unit = {},
)

/** The values the root rows show beside their titles, from `messages-prefs.json`. */
@Immutable
data class SettingsSummary(
    val notifications: Boolean = true,
    val quickReplies: Int? = null,
    val textSize: ChatTextSize = ChatTextSize.Default,
) {
    companion object {
        fun of(prefs: MessagesPrefs?): SettingsSummary =
            prefs?.let { SettingsSummary(it.notifications, it.quickReplies.size, it.chatTextSize) } ?: SettingsSummary()
    }
}

/**
 * The Settings tab root, in the GPUI app's order (E6.12): the default SMS app (the ask while the role is not held),
 * Notifications, Quick replies, Text size and Spam filter, then WhatsApp and Backup, the access rows (contacts,
 * notifications, exact alarms for scheduled sends), and the rows every app shares (About carries developer mode's 10 s
 * hold).
 */
@Composable
fun MessagesSettingsPage(
    smsRoleHeld: Boolean,
    settings: CommonSettings,
    links: SettingsLinks,
    onRoleResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: SettingsSummary = SettingsSummary(),
    special: SpecialAccess = LocalSpecialAccess.current,
) {
    val contract = remember(special) { special.requestRoleContract(AppPermissions.ROLE_SMS) }
    val askRole = rememberLauncherForActivityResult(contract, onRoleResult)
    SettingsPage(title = stringResource(R.string.tab_settings), modifier = modifier.testTag(SettingsTags.Page)) {
        IosSettingsGroup(
            items =
                listOf(
                    if (!smsRoleHeld) {
                        IosSettingsItem(
                            icon = rememberVectorPainter(RostyIcons.Sms),
                            title = { IosText(stringResource(R.string.access_default_sms_title)) },
                            description = { IosText(stringResource(R.string.access_default_sms_detail)) },
                            trailingContent = {
                                IosText(
                                    stringResource(R.string.access_default_sms_action),
                                    Modifier.testTag(AccessTags.DefaultSmsAction),
                                )
                            },
                            onClick = { askRole.launch(Unit) },
                            modifier = Modifier.testTag(AccessTags.DefaultSmsCard),
                        )
                    } else {
                        IosSettingsItem(
                            icon = rememberVectorPainter(RostyIcons.Sms),
                            title = {
                                IosText(
                                    stringResource(R.string.settings_default_sms),
                                    Modifier.testTag(SettingsTags.DefaultHeld),
                                )
                            },
                            description = { IosText(stringResource(R.string.settings_default_sms_held)) },
                            trailingContent = {
                                RostyIcon(RostyIcons.Checkmark, null, tint = IosTheme.tint)
                            },
                        )
                    },
                ),
        )
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_messages),
            items =
                listOf(
                    linkItem(
                        RostyIcons.Bell,
                        stringResource(R.string.notify_settings_title),
                        SettingsTags.NotificationsRow,
                        stringResource(if (summary.notifications) R.string.settings_on else R.string.settings_off),
                        links.openNotifications,
                    ),
                    linkItem(
                        RostyIcons.Message,
                        stringResource(R.string.quick_replies_title),
                        SettingsTags.QuickRepliesRow,
                        summary.quickReplies?.toString(),
                        links.openQuickReplies,
                    ),
                    linkItem(
                        RostyIcons.Appearance,
                        stringResource(R.string.text_size_title),
                        SettingsTags.TextSizeRow,
                        stringResource(ChatTextScale.label(summary.textSize)),
                        links.openTextSize,
                    ),
                    linkItem(
                        RostyIcons.Shield,
                        stringResource(R.string.settings_spam_row),
                        SettingsTags.SpamRow,
                        null,
                        links.openSpam,
                    ),
                ),
        )
        IosSettingsGroup(
            items =
                listOf(
                    linkItem(
                        RostyIcons.WhatsApp,
                        stringResource(R.string.settings_whatsapp),
                        SettingsTags.WhatsAppRow,
                        null,
                        links.openWhatsApp,
                    ),
                    linkItem(
                        RostyIcons.Download,
                        stringResource(R.string.backup_title),
                        SettingsTags.BackupRow,
                        null,
                        links.openBackup,
                    ),
                ),
        )
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_access),
            items =
                listOf(
                    permissionSettingsItem(PermissionGroup.CONTACTS),
                    permissionSettingsItem(PermissionGroup.NOTIFICATIONS),
                    specialAccessSettingsItem(SpecialKind.ExactAlarms),
                ),
        )
        IosText(
            stringResource(R.string.settings_access_footer),
            Modifier.padding(horizontal = Spacing.l).testTag(SettingsTags.Access),
            color = IosTheme.colors.secondaryLabel,
            style = IosTheme.typography.footnote,
        )
        Spacer(Modifier.height(Spacing.l))
        CommonSettingsGroup(settings, links.openCommon)
    }
}

/** A row that opens a page: its tile, its title, and its value beside the chevron when it has one. */
@Composable
private fun linkItem(
    icon: ImageVector,
    title: String,
    tag: String,
    value: String?,
    onClick: () -> Unit,
) = IosSettingsItem(
    icon = rememberVectorPainter(icon),
    title = { IosText(title, Modifier.testTag(tag)) },
    trailingContent =
        value?.let {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    IosText(it, color = IosTheme.colors.secondaryLabel, maxLines = 1)
                    IosChevron()
                }
            }
        },
    onClick = onClick,
)
