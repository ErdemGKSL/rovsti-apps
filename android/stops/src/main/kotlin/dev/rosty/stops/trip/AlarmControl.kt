package dev.rosty.stops.trip

import android.content.Context
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.location.TripAlarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What the alarm of `:platform` reports (`trip_alarm` events), decoded once. */
sealed interface AlarmEvent {
    data object Ringing : AlarmEvent

    /** Snoozed (or the ring timed out, AD-SA5): it rings again at [atMs]. */
    data class Later(
        val atMs: Long,
    ) : AlarmEvent

    data object Dismissed : AlarmEvent

    companion object {
        /** Pure: the event of a `trip_alarm` payload, null for one this version does not know. */
        fun parse(json: String): AlarmEvent? {
            val obj =
                try {
                    Json.parseToJsonElement(json) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
            val at = (obj["at_ms"] as? JsonPrimitive)?.longOrNull
            return when ((obj["event"] as? JsonPrimitive)?.contentOrNull) {
                "ringing" -> Ringing
                "snoozed", "timed_out" -> at?.let { Later(it) }
                "dismissed" -> Dismissed
                else -> null
            }
        }
    }
}

/**
 * The alarm as the in-app screen sees it: the events of the ringing alarm and its two actions. The native
 * `AlarmActivity` over the lock screen is unchanged; this screen offers the same actions while Stops is open (E7.6
 * AC1).
 */
interface AlarmControl {
    val events: Flow<AlarmEvent>

    /** Stops the sound, ends the trip (Rust's `trip_alarm` `dismiss`) and reports [AlarmEvent.Dismissed]. */
    fun dismiss()

    /** Stops the sound and asks Rust when to ring again (no snoozes left means dismiss). */
    fun snooze()
}

/** [AlarmControl] over `:platform`'s [TripAlarm] and its `trip_alarm` events. */
class AndroidAlarmControl(
    private val context: Context,
) : AlarmControl {
    override val events: Flow<AlarmEvent> =
        PlatformEvents.events
            .filter { it.kind == TripAlarm.EVENT }
            .mapNotNull { AlarmEvent.parse(it.json) }

    override fun dismiss() = TripAlarm.dismiss(context)

    override fun snooze() = TripAlarm.snooze(context)
}
