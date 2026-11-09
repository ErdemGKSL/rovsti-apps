package dev.rovsti.platform.calllog

import dev.rovsti.core.model.phone.BlockedNumber
import dev.rovsti.platform.wire.ProviderJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [BlocklistRepository] with the real refusals: blank and [emergency] numbers are `REFUSED`, and with
 * [allowed] false every call answers `UNAVAILABLE` like a phone where this app is not the default dialer.
 */
class FakeBlocklistRepository(
    numbers: List<BlockedNumber> = emptyList(),
    var allowed: Boolean = true,
    var emergency: Set<String> = setOf("112", "911"),
) : BlocklistRepository {
    private val rows = MutableStateFlow(numbers)
    private var nextId = (numbers.maxOfOrNull { it.id } ?: 0L) + 1

    override fun numbers(): Flow<List<BlockedNumber>> = rows

    override suspend fun block(raw: String): Blocklist.Result {
        val number = raw.trim()
        if (number.isEmpty() || number in emergency) return Blocklist.Result.REFUSED
        if (!allowed) return Blocklist.Result.UNAVAILABLE
        val normalized = ProviderJson.normalize(number)
        // The provider replaces a duplicate instead of adding a second row.
        if (rows.value.none { it.normalized == normalized }) {
            rows.value = rows.value + BlockedNumber(nextId++, number, normalized)
        }
        return Blocklist.Result.OK
    }

    override suspend fun unblock(raw: String): Blocklist.Result {
        val number = raw.trim()
        if (number.isEmpty()) return Blocklist.Result.REFUSED
        if (!allowed) return Blocklist.Result.UNAVAILABLE
        val normalized = ProviderJson.normalize(number)
        rows.value = rows.value.filterNot { it.normalized == normalized }
        return Blocklist.Result.OK
    }

    override suspend fun canBlock() = allowed

    override suspend fun isBlocked(raw: String): Boolean =
        allowed && raw.isNotBlank() && rows.value.any { it.normalized == ProviderJson.normalize(raw) }
}
