package dev.rovsti.shell.permissions

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.system.permissions.SpecialKind
import dev.rovsti.shell.R

/** What the rationale sheet of one group says. */
@Immutable
class PermissionRationale(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

@StringRes
internal fun PermissionGroup.titleRes(): Int =
    when (this) {
        PermissionGroup.PHONE -> R.string.permission_group_phone
        PermissionGroup.CONTACTS -> R.string.permission_group_contacts
        PermissionGroup.NOTIFICATIONS -> R.string.permission_group_notifications
        PermissionGroup.CALL_LOG -> R.string.permission_group_call_log
        PermissionGroup.BLUETOOTH -> R.string.permission_group_bluetooth
        PermissionGroup.LOCATION -> R.string.permission_group_location
        PermissionGroup.BACKGROUND_LOCATION -> R.string.permission_group_background_location
        PermissionGroup.MICROPHONE -> R.string.permission_group_microphone
    }

internal fun PermissionGroup.icon(): ImageVector =
    when (this) {
        PermissionGroup.PHONE, PermissionGroup.CALL_LOG, PermissionGroup.MICROPHONE -> RovstiIcons.Phone
        PermissionGroup.CONTACTS -> RovstiIcons.People
        PermissionGroup.NOTIFICATIONS -> RovstiIcons.Message
        PermissionGroup.LOCATION, PermissionGroup.BACKGROUND_LOCATION -> RovstiIcons.Map
        PermissionGroup.BLUETOOTH -> RovstiIcons.Gear
    }

/** The default words for [group], from `strings.xml`. */
@Composable
fun rationaleFor(group: PermissionGroup): PermissionRationale {
    val (title, body) =
        when (group) {
            PermissionGroup.PHONE -> {
                R.string.permission_rationale_phone_title to
                    R.string.permission_rationale_phone_body
            }

            PermissionGroup.CONTACTS -> {
                R.string.permission_rationale_contacts_title to
                    R.string.permission_rationale_contacts_body
            }

            PermissionGroup.NOTIFICATIONS -> {
                R.string.permission_rationale_notifications_title to
                    R.string.permission_rationale_notifications_body
            }

            PermissionGroup.CALL_LOG -> {
                R.string.permission_rationale_call_log_title to
                    R.string.permission_rationale_call_log_body
            }

            PermissionGroup.BLUETOOTH -> {
                R.string.permission_rationale_bluetooth_title to
                    R.string.permission_rationale_bluetooth_body
            }

            PermissionGroup.LOCATION -> {
                R.string.permission_rationale_location_title to
                    R.string.permission_rationale_location_body
            }

            PermissionGroup.BACKGROUND_LOCATION -> {
                R.string.permission_rationale_background_location_title to
                    R.string.permission_rationale_background_location_body
            }

            PermissionGroup.MICROPHONE -> {
                R.string.permission_rationale_microphone_title to
                    R.string.permission_rationale_microphone_body
            }
        }
    return PermissionRationale(group.icon(), stringResource(title), stringResource(body))
}

@Composable
internal fun statusText(state: PermissionState): String =
    stringResource(
        when (state) {
            PermissionState.NeverAsked -> R.string.permission_status_never_asked
            PermissionState.DeniedOnce -> R.string.permission_status_denied
            PermissionState.DeniedForGood -> R.string.permission_status_denied_for_good
            PermissionState.RevokedWhileRunning -> R.string.permission_status_revoked
            PermissionState.Granted -> R.string.permission_status_granted
        },
    )

@StringRes
internal fun SpecialKind.titleRes(): Int =
    when (this) {
        SpecialKind.ExactAlarms -> R.string.special_exact_alarms
        SpecialKind.FullScreenIntent -> R.string.special_full_screen
        SpecialKind.BatteryOptimisation -> R.string.special_battery
        SpecialKind.XiaomiBackground -> R.string.special_background
    }

@StringRes
internal fun SpecialKind.statusRes(on: Boolean?): Int =
    when (this) {
        SpecialKind.ExactAlarms -> {
            if (on ==
                true
            ) {
                R.string.special_exact_alarms_on
            } else {
                R.string.special_exact_alarms_off
            }
        }

        SpecialKind.FullScreenIntent -> {
            if (on ==
                true
            ) {
                R.string.special_full_screen_on
            } else {
                R.string.special_full_screen_off
            }
        }

        SpecialKind.BatteryOptimisation -> {
            if (on == true) R.string.special_battery_on else R.string.special_battery_off
        }

        SpecialKind.XiaomiBackground -> {
            R.string.special_background_unknown
        }
    }
