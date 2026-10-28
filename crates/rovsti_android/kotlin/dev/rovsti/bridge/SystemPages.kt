package dev.rovsti.bridge

import org.json.JSONObject

/**
 * Settings pages the apps hand off to (story 14.4). Pure tables: the page-to-action mapping and
 * the fallback chain are unit-tested on the JVM; `RovstiBridge.openSystem` does the starting.
 * Actions are public constants where Android has one, and literals where it has none.
 */
object SystemPages {
    const val ACTION_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** Page key (the Rust `SystemPage::key`) to its intent action. */
    val ACTIONS: Map<String, String> = mapOf(
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        // No public constant for hearing devices: the literal is the documented action.
        "hearing_devices" to "android.settings.HEARING_DEVICES_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "default_apps" to "android.settings.MANAGE_DEFAULT_APPS_SETTINGS",
        "call_settings" to "android.telecom.action.SHOW_CALL_SETTINGS",
        "call_accessibility" to "android.telecom.action.SHOW_CALL_ACCESSIBILITY_SETTINGS",
        "change_phone_accounts" to "android.telecom.action.CHANGE_PHONE_ACCOUNTS",
        // No public constant either.
        "emergency_info" to "android.telephony.action.EMERGENCY_ASSISTANCE",
        "app_details" to ACTION_DETAILS,
    )

    /** The actions to try in order; empty for an unknown page. The last is App info, which always exists. */
    fun chain(page: String): List<String> {
        val action = ACTIONS[page] ?: return emptyList()
        // TTY and RTT: the call accessibility page, else the general Accessibility page, else App info.
        if (page == "call_accessibility") return listOf(action, ACTIONS.getValue("accessibility"), ACTION_DETAILS)
        return if (action == ACTION_DETAILS) listOf(ACTION_DETAILS) else listOf(action, ACTION_DETAILS)
    }

    /** `{v, ok, error?, data?:{fallback}}`. */
    fun envelope(ok: Boolean, fallback: Boolean = false): String {
        val o = JSONObject().put("v", WIRE_VERSION).put("ok", ok)
        if (ok) o.put("data", JSONObject().put("fallback", fallback)) else o.put("error", "unavailable")
        return o.toString()
    }

    /** Walks [chain]; [start] returns whether an activity took the action. */
    fun open(page: String, start: (String) -> Boolean): String {
        val actions = chain(page)
        for ((i, action) in actions.withIndex()) {
            if (start(action)) return envelope(true, fallback = i > 0)
        }
        return envelope(false)
    }
}
