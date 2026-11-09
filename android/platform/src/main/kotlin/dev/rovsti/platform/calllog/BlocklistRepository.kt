package dev.rovsti.platform.calllog

import android.content.Context
import android.provider.BlockedNumberContract
import android.telephony.PhoneNumberUtils
import dev.rovsti.core.model.phone.BlockedNumber
import dev.rovsti.platform.liveQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The system blocklist, shared with the system and the SMS app. Only the default dialer may use it: without the right
 * [canBlock] is false and the writes answer [Blocklist.Result.UNAVAILABLE] (or `PERMISSION` when the right exists but
 * the call was refused). Nothing throws.
 */
interface BlocklistRepository {
    /** The blocked numbers; empty when the list cannot be read ([canBlock] says why). */
    fun numbers(): Flow<List<BlockedNumber>>

    /** Emergency numbers and blank strings are `REFUSED` before the provider is asked. */
    suspend fun block(raw: String): Blocklist.Result

    suspend fun unblock(raw: String): Blocklist.Result

    suspend fun canBlock(): Boolean

    /** False on any failure: a number that cannot be checked rings as usual. */
    suspend fun isBlocked(raw: String): Boolean
}

/** [BlocklistRepository] on `BlockedNumberContract`; [provider] and [isEmergency] are seams for tests. */
class AndroidBlocklistRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val provider: Blocklist.Provider = Blocklist.System(context),
    private val isEmergency: (String) -> Boolean = { PhoneNumberUtils.isEmergencyNumber(it) },
) : BlocklistRepository {
    override fun numbers(): Flow<List<BlockedNumber>> =
        liveQuery(
            context.contentResolver,
            listOf(BlockedNumberContract.BlockedNumbers.CONTENT_URI),
            io,
            emptyList(),
        ) { Blocklist.numbers(provider).second }

    override suspend fun block(raw: String) = withContext(io) { Blocklist.block(provider, raw, isEmergency) }

    override suspend fun unblock(raw: String) = withContext(io) { Blocklist.unblock(provider, raw) }

    override suspend fun canBlock() = withContext(io) { Blocklist.canBlock(provider) }

    override suspend fun isBlocked(raw: String) = withContext(io) { Blocklist.isBlocked(provider, raw) }
}
