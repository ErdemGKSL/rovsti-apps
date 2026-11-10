package dev.rovsti.phone.incoming

import dev.rovsti.core.Envelope
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.CallUpdate
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.platform.telephony.CallsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Where the call screens read the calls from: the whole snapshot (calls, the waiting id, the keyguard, the silenced
 * ringer), as the owner of the calls (`CallRegistry`) last built it. A seam, so the screens and their view models are
 * tested with a plain flow and no Telecom.
 */
interface CallSource {
    val snapshot: StateFlow<CallSnapshot>

    /**
     * Which calls of [snapshot] are WhatsApp calls, by their id there (AD-WA5); empty for cellular calls only. The
     * screens read it to draw the "WhatsApp audio" line, dim what a WhatsApp call cannot do and load the profile photo.
     */
    val whatsapp: StateFlow<Map<Int, WaCallMeta>> get() = NoWhatsApp
}

private val NoWhatsApp: StateFlow<Map<Int, WaCallMeta>> = MutableStateFlow(emptyMap())

/** What the screens know about one WhatsApp call beyond its [WireCall]: its gateway id, peer JID and phase. */
data class WaCallMeta(
    val callId: String,
    /** The peer's JID: the key of its profile picture. */
    val jid: String,
    val update: CallUpdate,
    val speaker: Boolean,
    val muted: Boolean,
)

/**
 * [CallSource] over `CallsApi.snapshot()` (the JSON the registry built, AD-4), re-read each time the registry's typed
 * call list changes (it emits after the snapshot text is set, so the two never disagree). A text that does not decode
 * is an empty snapshot, never an exception: a call screen must not crash while a call rings.
 */
class RegistryCallSource(
    private val calls: CallsApi,
    changes: StateFlow<List<WireCall>>,
    scope: CoroutineScope,
) : CallSource {
    override val snapshot: StateFlow<CallSnapshot> =
        changes.map { read() }.stateIn(scope, SharingStarted.Eagerly, read())

    private fun read(): CallSnapshot =
        runCatching { Envelope.json.decodeFromString(CallSnapshot.serializer(), calls.snapshot()) }
            .getOrDefault(CallSnapshot())
}
