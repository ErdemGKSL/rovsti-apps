package dev.rosty.messages.access

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.design.controls.TextButton
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.shell.permissions.PermissionIconTile

/** Test tags of the default-SMS ask. */
object AccessTags {
    const val DefaultSmsCard = "messages-default-sms-card"
    const val DefaultSmsAction = "messages-default-sms-action"
}

/**
 * The ask to become the default SMS app (ROLE_SMS, AD-MSG4), shown above the inbox and in Settings while the role is
 * not held. Without the role SMS can still be read but not sent, received or deleted, so the row says that plainly and
 * its one button opens the system's role dialog (or the default-apps page where the dialog cannot be offered).
 * [onResult] gets whether the app holds the role afterwards.
 */
@Composable
fun DefaultSmsCard(
    onResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    special: SpecialAccess = LocalSpecialAccess.current,
) {
    val contract = remember(special) { special.requestRoleContract(AppPermissions.ROLE_SMS) }
    val launcher = rememberLauncherForActivityResult(contract, onResult)
    InsetGroupedSection(modifier = modifier.testTag(AccessTags.DefaultSmsCard)) {
        row {
            DefaultSmsRow(onAsk = { launcher.launch(Unit) })
        }
    }
}

/** The stateless row of [DefaultSmsCard]: the text gets the full width, the button sits under it (it never squeezes). */
@Composable
fun DefaultSmsRow(
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ListRow(
            title = stringResource(R.string.access_default_sms_title),
            secondary = stringResource(R.string.access_default_sms_detail),
            secondaryMaxLines = 4,
            titleMaxLines = 2,
            leading = { PermissionIconTile(RostyIcons.Sms) },
        )
        TextButton(
            text = stringResource(R.string.access_default_sms_action),
            onClick = onAsk,
            modifier = Modifier.padding(start = TextStartWithLeading - Spacing.s).testTag(AccessTags.DefaultSmsAction),
        )
    }
}
