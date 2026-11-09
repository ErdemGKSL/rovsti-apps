package dev.rovsti.platform.location

import android.content.Context
import dev.rovsti.bridge.TripService
import dev.rovsti.core.Envelope
import dev.rovsti.core.model.headless.TripFixAnswer
import dev.rovsti.platform.PlatformEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the trip service reports (the `trip_status` events), decoded once for the UI. */
sealed interface TripStatus {
    /** Rust's answer to a fix: distance, next interval, the text of the notification. */
    data class Fixed(
        val answer: TripFixAnswer,
    ) : TripStatus

    /** No fix yet, or the provider found nothing; the watchdog keeps asking. */
    data object Searching : TripStatus

    /** The user stopped the trip from the notification. */
    data object Stopped : TripStatus

    /** Rust said no trip is active any more. */
    data object Ended : TripStatus

    /** `restart`, `foreground`, `permission` or `full_screen`: the UI turns it into a settings row or a notice. */
    data class Error(
        val code: String,
    ) : TripStatus

    /** A payload this version does not know; kept raw so nothing is lost. */
    data class Other(
        val json: String,
    ) : TripStatus

    companion object {
        /** Pure: the status in a `trip_status` payload. */
        fun parse(json: String): TripStatus {
            val obj =
                try {
                    Envelope.json.parseToJsonElement(json) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return Other(json)
            return when ((obj["state"] as? JsonPrimitive)?.contentOrNull) {
                "searching" -> {
                    Searching
                }

                "stopped" -> {
                    Stopped
                }

                "ended" -> {
                    Ended
                }

                "error" -> {
                    Error((obj["error"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                }

                null -> {
                    Other(json)
                }

                else -> {
                    try {
                        Fixed(Envelope.json.decodeFromString(TripFixAnswer.serializer(), json))
                    } catch (_: IllegalArgumentException) {
                        Other(json)
                    }
                }
            }
        }
    }
}

/**
 * The trip as the UI sees it: a status flow and a start call that never throws (ARCH 7.5).
 *
 * The service still talks to Rust for every fix; this only reads what it reports and starts it.
 */
interface TripRepository {
    /** Every `trip_status` the service reports, in order. */
    val status: Flow<TripStatus>

    /** Starts the foreground service for [req]; see [StartResult]. */
    fun start(req: TripSpec): StartResult

    /** Stops the service; true when it was running. */
    fun stop(): Boolean
}

class AndroidTripRepository(
    private val context: Context,
) : TripRepository {
    override val status: Flow<TripStatus> =
        PlatformEvents.events
            .filter { it.kind == TripService.EVENT_STATUS }
            .map { TripStatus.parse(it.json) }

    override fun start(req: TripSpec): StartResult = TripService.startResult(context, req.toJson())

    override fun stop(): Boolean = TripService.stop(context)
}
