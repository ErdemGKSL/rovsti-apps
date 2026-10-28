package dev.rovsti.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's call-capable accounts (AD-26, story 16.3). Rust picks the account; this only lists and
 * resolves keys. The key is `"{ComponentName.flattenToString()}#{handle.id}"`, which is also what the
 * call log's `PHONE_ACCOUNT_COMPONENT_NAME` and `PHONE_ACCOUNT_ID` columns build, so a log row and an
 * account compare equal. Callers never parse it.
 */
object SimAccounts {
    data class Account(
        val key: String,
        val label: String,
        val slotIndex: Int?,
        val colorArgb: Int,
        val sim: Boolean,
        val enabled: Boolean = true,
    )

    fun keyOf(component: String?, id: String?): String? =
        if (component.isNullOrEmpty() || id == null) null else "$component#$id"

    fun keyOf(handle: PhoneAccountHandle): String = "${handle.componentName.flattenToString()}#${handle.id}"

    /** "Ask every time": no user-selected default and at least two accounts. */
    fun ask(defaultKey: String?, accounts: Int): Boolean = defaultKey == null && accounts >= 2

    /** The `phone_accounts` envelope. */
    fun json(accounts: List<Account>, defaultKey: String?): String {
        val list = JSONArray()
        for (a in accounts) {
            val o = JSONObject().put("key", a.key).put("label", a.label)
                .put("color_argb", a.colorArgb).put("sim", a.sim).put("enabled", a.enabled)
            a.slotIndex?.let { o.put("slot_index", it) }
            list.put(o)
        }
        val data = JSONObject().put("accounts", list).put("ask", ask(defaultKey, accounts.size))
        defaultKey?.let { data.put("default_key", it) }
        return JSONObject().put("v", WIRE_VERSION).put("ok", true).put("data", data).toString()
    }

    /** Needs `READ_PHONE_STATE`; a denial is `ok:false, error:"permission"`. */
    @SuppressLint("MissingPermission")
    fun collect(context: Context): String = try {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val subs = context.getSystemService(SubscriptionManager::class.java)
        val slots = HashMap<Int, Int>()
        subs?.activeSubscriptionInfoList?.forEach { slots[it.subscriptionId] = it.simSlotIndex }
        val handles = telecom?.callCapablePhoneAccounts ?: emptyList()
        val accounts = handles.map { h ->
            val account = telecom?.getPhoneAccount(h)
            val sim = account?.hasCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
            val subId = try {
                telephony?.createForPhoneAccountHandle(h)?.subscriptionId
            } catch (_: RuntimeException) {
                null
            }
            Account(
                key = keyOf(h),
                label = account?.label?.toString().orEmpty(),
                slotIndex = subId?.let { slots[it] },
                colorArgb = account?.highlightColor ?: 0,
                sim = sim,
            )
        }
        val defaultHandle = try {
            telecom?.getUserSelectedOutgoingPhoneAccount()
        } catch (_: RuntimeException) {
            null
        }
        json(accounts, defaultHandle?.let { keyOf(it) })
    } catch (_: SecurityException) {
        ProviderJson.error("permission")
    } catch (_: RuntimeException) {
        ProviderJson.error("unknown")
    }

    /** The handle whose key is [key], among the call-capable accounts. */
    fun handleFor(context: Context, key: String): PhoneAccountHandle? = try {
        context.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts?.firstOrNull { keyOf(it) == key }
    } catch (_: SecurityException) {
        null
    }
}
