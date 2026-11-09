package dev.rovsti.platform.system.permissions

import android.Manifest

/**
 * The permission groups the apps ask for. [id] is the key the GPUI bridge used in its `groups` table, in the prefs key
 * `asked_<id>` and in the `permissions_changed` payload, so an update keeps what the user was already asked.
 *
 * Ported from the GPUI bridge (crates/rovsti_android/kotlin/dev/rovsti/bridge, the `groups` table).
 */
enum class PermissionGroup(
    val id: String,
    val permissions: List<String>,
) {
    PHONE(
        "phone",
        listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
        ),
    ),
    CONTACTS("contacts", listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)),
    NOTIFICATIONS("notifications", listOf(Manifest.permission.POST_NOTIFICATIONS)),
    CALL_LOG("call_log", listOf(Manifest.permission.READ_CALL_LOG)),
    BLUETOOTH("bluetooth", listOf(Manifest.permission.BLUETOOTH_CONNECT)),

    /** Fine and coarse together: one dialog, precise requested. */
    LOCATION(
        "location",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),

    /** A separate second step with its own rationale (AD-SA7). */
    BACKGROUND_LOCATION("background_location", listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
    MICROPHONE("microphone", listOf(Manifest.permission.RECORD_AUDIO)),
    ;

    companion object {
        fun fromId(id: String): PermissionGroup? = entries.firstOrNull { it.id == id }

        /** The prefs key that remembers the dialog was shown for [group] (never rename: it is user data). */
        fun askedKey(group: PermissionGroup): String = "asked_${group.id}"
    }
}
