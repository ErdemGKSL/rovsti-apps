package dev.rosty.stops.trip

import dev.rosty.platform.location.TripRepository
import dev.rosty.platform.location.TripStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The trips of [inner], with the trip's copy of the WhatsApp link dropped as soon as the trip is over
 * (`plans/wa-vault.md` 13.3): a trip that carries an arriving-soon message keeps the link in RAM from its start
 * (`WaVault.holdForTrip`) to its end, and no longer. [release] runs when the user stops the trip here and when the
 * service reports it stopped or ended; Rust drops the link at every end of the trip as well (`stops::store::end_trip`).
 */
class TripLinkRelease(
    private val inner: TripRepository,
    private val scope: CoroutineScope,
    private val release: suspend () -> Unit,
) : TripRepository by inner {
    init {
        scope.launch {
            inner.status.collect { status ->
                if (status == TripStatus.Ended || status == TripStatus.Stopped) release()
            }
        }
    }

    override fun stop(): Boolean = inner.stop().also { scope.launch { release() } }
}
