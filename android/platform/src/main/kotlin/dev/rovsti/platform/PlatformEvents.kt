package dev.rovsti.platform

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/** One thing the platform layer noticed: [kind] is the event name Rust knew (`call_log_changed`), [json] its payload. */
data class PlatformEvent(
    val kind: String,
    val json: String,
)

/**
 * The process-wide event bus of `:platform`. It replaces the old Kotlin-to-Rust `emit` call: receivers, services and
 * observers [emit] here, and repositories expose typed flows filtered from [events]. No JNI is involved.
 *
 * The buffer holds 64 events and drops the oldest, so a receiver never suspends when nobody listens.
 */
object PlatformEvents {
    private val bus =
        MutableSharedFlow<PlatformEvent>(
            extraBufferCapacity = BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val attached = AtomicBoolean(false)

    const val BUFFER = 64

    val events: SharedFlow<PlatformEvent> = bus.asSharedFlow()

    /** True while a UI is attached (the activity sets it in `onStart`/`onStop`); receivers use it to choose a path. */
    var uiAttached: Boolean
        get() = attached.get()
        set(value) = attached.set(value)

    /** Never suspends; false only if the bus could not take the event. */
    fun emit(
        kind: String,
        json: String,
    ): Boolean = bus.tryEmit(PlatformEvent(kind, json))
}
