package dev.rovsti.core.ops

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustHandle
import dev.rovsti.core.RustNative
import dev.rovsti.core.model.stops.Coord
import dev.rovsti.core.model.stops.DismissAnswer
import dev.rovsti.core.model.stops.DismissRequest
import dev.rovsti.core.model.stops.Endpoint
import dev.rovsti.core.model.stops.FixIn
import dev.rovsti.core.model.stops.NoticePrefs
import dev.rovsti.core.model.stops.NoticeResultAnswer
import dev.rovsti.core.model.stops.NoticeResultRequest
import dev.rovsti.core.model.stops.SnoozeAnswer
import dev.rovsti.core.model.stops.SnoozeRequest
import dev.rovsti.core.model.stops.StatusShareRequest
import dev.rovsti.core.model.stops.TextAnswer
import dev.rovsti.core.model.stops.TimeoutAnswer
import dev.rovsti.core.model.stops.TripEndAnswer
import dev.rovsti.core.model.stops.TripEndRequest
import dev.rovsti.core.model.stops.TripFixAnswer
import dev.rovsti.core.model.stops.TripFixRequest
import dev.rovsti.core.model.stops.TripHandleRequest
import dev.rovsti.core.model.stops.TripRestoreRequest
import dev.rovsti.core.model.stops.TripStartRequest
import dev.rovsti.core.model.stops.TripView
import kotlinx.serialization.json.JsonElement

/**
 * The trip in progress, a native `ActiveTrip`. Open it with [start] (a new trip) or [restore] (a persisted one),
 * feed it fixes with [fix], and [close] it when the trip ends or the process lets go of it. A closed handle answers
 * [RustError.NotFound] without a native call.
 *
 * With [dir] the mutating calls also write `trip.json` under the store lock, so the foreground service (which reads
 * that file through the headless kinds) sees the same trip.
 */
class ActiveTripHandle internal constructor(
    id: Long,
    /** The trip when the handle was opened. */
    val opened: TripView,
    private val ops: StopsOps,
    private val dir: String?,
    release: (Long) -> Unit,
) : RustHandle(id, release) {
    private suspend fun <T> guarded(call: suspend () -> Outcome<T>): Outcome<T> =
        if (released) Outcome.Err(RustError.NotFound("trip handle $id is closed")) else call()

    /** The trip as the card shows it now. */
    suspend fun state(): Outcome<TripView> = guarded { ops.tripState(TripHandleRequest(id)) }

    /** One location fix: what to do next (ring, when to ask again, the card text, the arrival message to send). */
    suspend fun fix(fix: FixIn): Outcome<TripFixAnswer> = guarded { ops.tripFix(TripFixRequest(id, fix, dir)) }

    /** [result] is `sent`, `retry_later` or `give_up`. */
    suspend fun noticeResult(result: String): Outcome<NoticeResultAnswer> =
        guarded { ops.tripNoticeResult(NoticeResultRequest(id, result, dir)) }

    suspend fun snooze(
        nowMs: Long,
        minutes: Int,
    ): Outcome<SnoozeAnswer> = guarded { ops.tripSnooze(SnoozeRequest(id, nowMs, minutes, dir)) }

    suspend fun timeout(
        nowMs: Long,
        minutes: Int,
    ): Outcome<TimeoutAnswer> = guarded { ops.tripTimeout(SnoozeRequest(id, nowMs, minutes, dir)) }

    suspend fun dismiss(): Outcome<DismissAnswer> = guarded { ops.tripDismiss(DismissRequest(id, dir)) }

    suspend fun statusShare(
        lineM: Double? = null,
        etaMin: Int? = null,
        here: Coord? = null,
        crossed: Boolean = false,
        arrive: String? = null,
    ): Outcome<TextAnswer> =
        guarded { ops.tripStatusShare(StatusShareRequest(id, lineM, etaMin, here, crossed.takeIf { it }, arrive)) }

    /** The history record of the trip. [outcome] is `arrived` or `cancelled`; the handle stays open until closed. */
    suspend fun end(
        outcome: String,
        nowMs: Long,
    ): Outcome<TripEndAnswer> = guarded { ops.tripEnd(TripEndRequest(id, outcome, nowMs, dir)) }

    companion object {
        /** Starts a trip; the handle is the caller's to close. */
        suspend fun start(
            id: Long,
            a: Endpoint,
            b: Endpoint,
            startedMs: Long,
            notice: NoticePrefs? = null,
            dir: String? = null,
            ops: StopsOps = NativeStopsOps(),
            release: (Long) -> Unit = RustNative::release,
        ): Outcome<ActiveTripHandle> =
            wrap(ops.tripStart(TripStartRequest(id, a, b, startedMs, notice, dir)), ops, dir, release)

        /** Opens a persisted trip (the `snapshot` of a [TripView] or the content of `trip.json`). */
        suspend fun restore(
            trip: JsonElement,
            dir: String? = null,
            ops: StopsOps = NativeStopsOps(),
            release: (Long) -> Unit = RustNative::release,
        ): Outcome<ActiveTripHandle> = wrap(ops.tripRestore(TripRestoreRequest(trip)), ops, dir, release)

        private fun wrap(
            opened: Outcome<TripView>,
            ops: StopsOps,
            dir: String?,
            release: (Long) -> Unit,
        ): Outcome<ActiveTripHandle> =
            when (opened) {
                is Outcome.Err -> {
                    opened
                }

                is Outcome.Ok -> {
                    opened.value.handle
                        ?.let { Outcome.Ok(ActiveTripHandle(it, opened.value, ops, dir, release)) }
                        ?: Outcome.Err(RustError.Parse("trip answer has no handle"))
                }
            }
    }
}
