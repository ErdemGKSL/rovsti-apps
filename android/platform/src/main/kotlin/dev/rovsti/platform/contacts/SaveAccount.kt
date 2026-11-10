package dev.rovsti.platform.contacts

import dev.rovsti.core.model.contacts.Account

/**
 * The account a new contact or a new group is saved to. The editor and the Groups tab both ask here, so the two cannot
 * drift apart:
 *
 * 1. the Save To preference, when that account is still on the phone;
 * 2. otherwise the account that holds the most live raw contacts, SIM accounts left out (a SIM holds no groups and few
 *    fields);
 * 3. only then the phone itself (the empty key; [ContactsEdit] writes it to the device's local account).
 *
 * An empty preference means nothing was chosen, so it falls through to 2. This matters on phones where every contact
 * lives in a Google account, or whose provider (MIUI/HyperOS) refuses a group with no account.
 */
object SaveAccount {
    /** [saveTo] is `prefs.save_to`; [accounts] are [ContactsRepository.accounts]; [rawContacts] counts by key. */
    fun choose(
        saveTo: String,
        accounts: List<Account>,
        rawContacts: Map<String, Int>,
    ): String {
        if (saveTo.isNotBlank() && accounts.any { it.key == saveTo }) return saveTo
        return accounts
            .filter { it.key.isNotEmpty() && !isSim(it) && (rawContacts[it.key] ?: 0) > 0 }
            .maxByOrNull { rawContacts[it.key] ?: 0 }
            ?.key
            ?: ""
    }

    /** SIM card accounts: `com.android.contacts.sim`, `vnd.sec.contact.sim2`, `com.android.contacts.usim`, ... */
    fun isSim(account: Account): Boolean {
        val type = account.kind.ifEmpty { account.key.substringBefore('|', "") }
        return type
            .lowercase()
            .split('.', '_', '-', ' ', '/')
            .any { SIM_PART.matches(it) }
    }

    private val SIM_PART = Regex("(sim|usim|csim|ruim|icc|simcontacts?)\\d*")
}

/** [SaveAccount.choose] over what the provider says now; the phone when the accounts cannot be read. */
suspend fun ContactsRepository.saveAccount(saveTo: String): String {
    val accounts = (accounts() as? ContactResult.Ok)?.value.orEmpty()
    val counts = (rawContactCounts() as? ContactResult.Ok)?.value.orEmpty()
    return SaveAccount.choose(saveTo, accounts, counts)
}
