package dev.rovsti.platform.telephony

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.model.phone.PhoneAccounts

/**
 * The phone's call-capable accounts (AD-26, story 16.3). Rust picks the account (`sim.build`); this only lists and
 * resolves keys. The key is `"{ComponentName.flattenToString()}#{handle.id}"`, which is also what the call log's
 * `PHONE_ACCOUNT_COMPONENT_NAME` and `PHONE_ACCOUNT_ID` columns build, so a log row and an account compare equal.
 * Callers never parse it.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/SimAccounts.kt; the `phone_accounts` JSON envelope is
 * dropped, [PhoneAccounts] is the wire model itself.
 */
object SimAccounts {
    fun keyOf(
        component: String?,
        id: String?,
    ): String? = if (component.isNullOrEmpty() || id == null) null else "$component#$id"

    fun keyOf(handle: PhoneAccountHandle): String = "${handle.componentName.flattenToString()}#${handle.id}"

    /** "Ask every time": no user-selected default and at least two accounts. */
    fun ask(
        defaultKey: String?,
        accounts: Int,
    ): Boolean = defaultKey == null && accounts >= 2

    /** The accounts and the default, as `sim.build` reads them. Needs `READ_PHONE_STATE`: null when refused. */
    @SuppressLint("MissingPermission")
    fun collect(context: Context): PhoneAccounts? =
        try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val subs = context.getSystemService(SubscriptionManager::class.java)
            val slots = HashMap<Int, Int>()
            subs?.activeSubscriptionInfoList?.forEach { slots[it.subscriptionId] = it.simSlotIndex }
            val handles = telecom?.callCapablePhoneAccounts ?: emptyList()
            val accounts =
                handles.map { h ->
                    val account = telecom?.getPhoneAccount(h)
                    val sim = account?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
                    val subId =
                        try {
                            telephony?.createForPhoneAccountHandle(h)?.subscriptionId
                        } catch (_: RuntimeException) {
                            null
                        }
                    PhoneAccountInfo(
                        key = keyOf(h),
                        label = account?.label?.toString().orEmpty(),
                        slotIndex = subId?.let { slots[it] },
                        colorArgb = account?.highlightColor ?: 0,
                        sim = sim,
                    )
                }
            val defaultKey =
                try {
                    telecom?.userSelectedOutgoingPhoneAccount?.let { keyOf(it) }
                } catch (_: RuntimeException) {
                    null
                }
            PhoneAccounts(accounts, defaultKey, ask(defaultKey, accounts.size))
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** The handle whose key is [key], among the call-capable accounts; null without `READ_PHONE_STATE`. */
    @SuppressLint("MissingPermission")
    fun handleFor(
        context: Context,
        key: String,
    ): PhoneAccountHandle? =
        try {
            context
                .getSystemService(TelecomManager::class.java)
                ?.callCapablePhoneAccounts
                ?.firstOrNull { keyOf(it) == key }
        } catch (_: SecurityException) {
            null
        }
}
