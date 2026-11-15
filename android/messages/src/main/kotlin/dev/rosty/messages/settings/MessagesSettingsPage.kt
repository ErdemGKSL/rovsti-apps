package dev.rosty.messages.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.access.AccessTags
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
}

/** Where the rows of the settings page lead. */
class SettingsLinks(
    val openWhatsApp: () -> Unit,
    val openCommon: (CommonSettingsPage) -> Unit,
    /** Settings, Spam filter (E6.11). */
    val openSpam: () -> Unit = {},
)

/**
 * The Settings tab root (E6.1; the Messages groups arrive with E6.12): the default-SMS ask while the role is not held,
 * the access rows (contacts, notifications, exact alarms for scheduled sends), WhatsApp, then the rows every app shares
 * (About carries developer mode's 10 s hold).
 */
@Composable
fun MessagesSettingsPage(
    smsRoleHeld: Boolean,
    settings: CommonSettings,
    links: SettingsLinks,
    onRoleResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    special: SpecialAccess = LocalSpecialAccess.current,
) {
    val contract = remember(special) { special.requestRoleContract(AppPermissions.ROLE_SMS) }
    val askRole = rememberLauncherForActivityResult(contract, onRoleResult)
    SettingsPage(title = stringResource(R.string.tab_settings), modifier = modifier.testTag(SettingsTags.Page)) {
        if (!smsRoleHeld) {
            IosSettingsGroup(
                items =
                    listOf(
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
                        ),
                    ),
            )
        }
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
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        icon = rememberVectorPainter(RostyIcons.WhatsApp),
                        title = {
                            IosText(
                                stringResource(R.string.settings_whatsapp),
                                Modifier.testTag(SettingsTags.WhatsAppRow),
                            )
                        },
                        onClick = links.openWhatsApp,
                    ),
                    IosSettingsItem(
                        icon = rememberVectorPainter(RostyIcons.Shield),
                        title = {
                            IosText(stringResource(R.string.settings_spam_row), Modifier.testTag(SettingsTags.SpamRow))
                        },
                        onClick = links.openSpam,
                    ),
                ),
        )
        CommonSettingsGroup(settings, links.openCommon)
    }
}
