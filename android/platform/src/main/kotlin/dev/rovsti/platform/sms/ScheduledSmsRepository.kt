package dev.rovsti.platform.sms

import android.content.Context
import dev.rovsti.core.model.messages.FiredSms
import dev.rovsti.platform.PlatformEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What [ScheduledSmsRepository.arm] did. */
sealed interface ArmResult {
    /**
     * The alarm is set. [ArmMode.Inexact] means exact alarms are not allowed: the system may fire it a few minutes
     * late, and the UI says so (AD-MSG6).
     */
    data class Armed(
        val mode: ArmMode,
    ) : ArmResult

    /** The item has no id or recipient, or the device has no alarm service. */
    data object Rejected : ArmResult
}

/** Scheduled SMS alarms (AD-MSG6). Rust owns the queue; this arms the alarm and reports what fired. */
interface ScheduledSmsRepository {
    suspend fun arm(item: ScheduledItem): ArmResult

    /** True when the id was armed. */
    suspend fun disarm(id: Long): Boolean

    /** Items still armed on this device, for the reconcile after an update. */
    suspend fun armed(): List<ScheduledItem>

    /** One value per item that fired, live from the `scheduled_fired` event. */
    fun fired(): Flow<FiredSms>

    /** The fired list stored while no UI listened, as `rovsti_core` reads it (a JSON array), then cleared. */
    suspend fun pullFired(): String
}

class AndroidScheduledSmsRepository(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : ScheduledSmsRepository {
    override suspend fun arm(item: ScheduledItem): ArmResult =
        withContext(io) {
            ScheduledSms.arm(context, item)?.let { ArmResult.Armed(it) } ?: ArmResult.Rejected
        }

    override suspend fun disarm(id: Long) = withContext(io) { ScheduledSms.disarm(context, id) }

    override suspend fun armed() = withContext(io) { ScheduledSms.armed(context) }

    override fun fired(): Flow<FiredSms> =
        PlatformEvents.events
            .filter { it.kind == ScheduledSms.EVENT_FIRED }
            .mapNotNull { event ->
                try {
                    val o = JSONObject(event.json)
                    FiredSms(o.getLong("id"), o.optBoolean("ok", false), o.optLong("at_ms", SmsClock.nowMillis()))
                } catch (_: org.json.JSONException) {
                    null
                }
            }

    override suspend fun pullFired() = withContext(io) { ScheduledSms.pullFired(context) }
}
