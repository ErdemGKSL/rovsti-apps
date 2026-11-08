package dev.rovsti.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement

/** What Rust pushes to Kotlin. */
sealed interface RustEvent {
    /** One event: its kind (`"tick"`) and the UTF-8 JSON body. */
    class Message(
        val kind: String,
        val json: ByteArray,
    ) : RustEvent {
        fun body(): JsonElement = Envelope.json.parseToJsonElement(json.decodeToString())
    }

    /** Events were dropped because a collector fell behind; reload the state you derived from events. */
    data object Resync : RustEvent
}

/**
 * The object handed to `RustNative.setEventSink`. Rust calls [onEvent] from any thread; the events are published in
 * call order on [events]. The buffer holds [BUFFER] events; when a collector is that far behind, the newest events are
 * dropped and a single [RustEvent.Resync] is published before the next event that fits.
 */
class RustEvents : (String, ByteArray) -> Unit {
    private val flow = MutableSharedFlow<RustEvent>(extraBufferCapacity = BUFFER)
    private var overflowed = false

    val events: SharedFlow<RustEvent> = flow.asSharedFlow()

    /** Called from JNI (`onEvent(Ljava/lang/String;[B)V`); never throws. */
    @Synchronized
    fun onEvent(
        kind: String,
        json: ByteArray,
    ) {
        if (overflowed) {
            if (!flow.tryEmit(RustEvent.Resync)) return
            overflowed = false
        }
        if (!flow.tryEmit(RustEvent.Message(kind, json))) overflowed = true
    }

    override fun invoke(
        kind: String,
        json: ByteArray,
    ) = onEvent(kind, json)

    companion object {
        const val BUFFER = 1024
    }
}
