package dev.rovsti.platform.telephony

import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.model.phone.PhoneAccounts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** An in-memory [SimRepository]: set [current] to re-emit, like a subscription change does. */
class FakeSimRepository(
    accounts: List<PhoneAccountInfo> = emptyList(),
    defaultKey: String? = null,
) : SimRepository {
    val current =
        MutableStateFlow(PhoneAccounts(accounts, defaultKey, SimAccounts.ask(defaultKey, accounts.size)))

    override fun accounts(): Flow<PhoneAccounts> = current

    override suspend fun probe(
        placedKey: String?,
        logKey: String?,
    ): List<String> = SimProbe.lines(current.value.accounts, current.value.defaultKey, placedKey, logKey)
}
