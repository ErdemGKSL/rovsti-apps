package dev.rovsti.platform.system.permissions

import android.Manifest

/** A page in Settings that can revoke access at any time (ARCH 7.5 "special access"). */
enum class SpecialKind { FullScreenIntent, ExactAlarms, BatteryOptimisation, XiaomiBackground }

/**
 * What one app needs from Android, row by row from ARCH 7.5. `AppPermissionsTest` compares each table with the
 * architecture document and with the committed manifest lists, so a drift in either fails a test.
 *
 * @property roles RoleManager role ids (`android.app.role.DIALER`).
 * @property runtime every runtime permission the app may ask for, asked on first use and never at launch. Messages'
 *   SMS permissions are here too although the SMS role grants them.
 * @property groups the [PermissionGroup]s the UI shows a row for.
 * @property special Settings pages that can be revoked while the app runs.
 * @property installTime permissions granted at install; [handoffUses] adds the signature permission for apps that
 *   call a sibling.
 * @property specialPermissions manifest entries behind [special] and the install-time alarm permission.
 */
class AppPermissions(
    val app: String,
    val roles: List<String>,
    val runtime: Set<String>,
    val groups: List<PermissionGroup>,
    val special: Set<SpecialKind>,
    val installTime: Set<String>,
    val specialPermissions: Set<String>,
    val handoffUses: Boolean = false,
    /** Reads Contacts' people provider (X.1b): holds `dev.rovsti.permission.READ_PEOPLE`. */
    val peopleUses: Boolean = false,
    /** What a library merges into the manifest (WorkManager's network, wake lock and boot entries), not asked of the user. */
    val libraryPermissions: Set<String> = emptySet(),
) {
    /** What [group] asks for in this app: Messages reads contacts but never writes them, so its group holds one entry. */
    fun permissionsOf(group: PermissionGroup): List<String> = group.permissions.filter { it in runtime }

    /** Every `uses-permission` the merged manifest must hold, without the HANDOFF entry. */
    val manifestPermissions: Set<String> get() = runtime + installTime + specialPermissions + libraryPermissions

    companion object {
        const val ROLE_DIALER = "android.app.role.DIALER"
        const val ROLE_SMS = "android.app.role.SMS"

        private const val FSI = "android.permission.USE_FULL_SCREEN_INTENT"
        private const val BATTERY = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"

        val phone =
            AppPermissions(
                app = "phone",
                roles = listOf(ROLE_DIALER),
                runtime =
                    setOf(
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.WRITE_CALL_LOG,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.RECORD_AUDIO,
                    ),
                groups =
                    listOf(
                        PermissionGroup.PHONE,
                        PermissionGroup.CONTACTS,
                        PermissionGroup.NOTIFICATIONS,
                        PermissionGroup.CALL_LOG,
                        PermissionGroup.BLUETOOTH,
                        PermissionGroup.MICROPHONE,
                    ),
                special =
                    setOf(
                        SpecialKind.FullScreenIntent,
                        SpecialKind.BatteryOptimisation,
                        SpecialKind.XiaomiBackground,
                        // Call-back reminders are exact while this is granted (E5.12).
                        SpecialKind.ExactAlarms,
                    ),
                installTime =
                    setOf(
                        // BootReceiver puts the call-back reminders back after a reboot (E5.12).
                        Manifest.permission.RECEIVE_BOOT_COMPLETED,
                        Manifest.permission.FOREGROUND_SERVICE,
                        "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
                        // WaCallService: background microphone during a WhatsApp call (docs/kotlin-ui/wa-call-audio.md).
                        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                        Manifest.permission.VIBRATE,
                        Manifest.permission.WAKE_LOCK,
                        Manifest.permission.INTERNET,
                    ),
                specialPermissions = setOf(FSI, BATTERY, "android.permission.SCHEDULE_EXACT_ALARM"),
                peopleUses = true,
            )

        val messages =
            AppPermissions(
                app = "messages",
                roles = listOf(ROLE_SMS),
                runtime =
                    setOf(
                        Manifest.permission.READ_SMS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.RECEIVE_MMS,
                        Manifest.permission.RECEIVE_WAP_PUSH,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                groups = listOf(PermissionGroup.CONTACTS, PermissionGroup.NOTIFICATIONS),
                special = setOf(SpecialKind.ExactAlarms),
                installTime =
                    setOf(
                        Manifest.permission.RECEIVE_BOOT_COMPLETED,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.INTERNET,
                    ),
                specialPermissions = setOf("android.permission.SCHEDULE_EXACT_ALARM"),
            )

        /** Camera capture goes through the system camera app, so there is no `CAMERA` permission. */
        val contacts =
            AppPermissions(
                app = "contacts",
                roles = emptyList(),
                runtime =
                    setOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS,
                        Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                groups = listOf(PermissionGroup.CONTACTS, PermissionGroup.CALL_LOG, PermissionGroup.NOTIFICATIONS),
                special = emptySet(),
                installTime = setOf(Manifest.permission.VIBRATE, Manifest.permission.INTERNET),
                specialPermissions = emptySet(),
                handoffUses = true,
                // work-runtime (the people sync, X.1b) merges these four.
                libraryPermissions =
                    setOf(
                        "android.permission.ACCESS_NETWORK_STATE",
                        Manifest.permission.FOREGROUND_SERVICE,
                        Manifest.permission.RECEIVE_BOOT_COMPLETED,
                        Manifest.permission.WAKE_LOCK,
                    ),
            )

        /** `USE_EXACT_ALARM` is install-time for alarm apps: no Settings page. */
        val stops =
            AppPermissions(
                app = "stops",
                roles = emptyList(),
                runtime =
                    setOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                groups =
                    listOf(
                        PermissionGroup.LOCATION,
                        PermissionGroup.BACKGROUND_LOCATION,
                        PermissionGroup.NOTIFICATIONS,
                    ),
                special = setOf(SpecialKind.FullScreenIntent, SpecialKind.BatteryOptimisation),
                installTime =
                    setOf(
                        Manifest.permission.FOREGROUND_SERVICE,
                        "android.permission.FOREGROUND_SERVICE_LOCATION",
                        Manifest.permission.VIBRATE,
                        Manifest.permission.WAKE_LOCK,
                        Manifest.permission.RECEIVE_BOOT_COMPLETED,
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        Manifest.permission.INTERNET,
                    ),
                specialPermissions = setOf(FSI, BATTERY, "android.permission.USE_EXACT_ALARM"),
            )

        val all: List<AppPermissions> = listOf(phone, messages, contacts, stops)
    }
}
