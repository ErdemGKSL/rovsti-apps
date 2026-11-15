package dev.rosty.phone.incoming

import dev.rosty.core.Envelope
import dev.rosty.core.model.phone.CallSnapshot
import dev.rosty.core.model.phone.CallUpdate
import dev.rosty.core.model.phone.WireCall
import dev.rosty.platform.telephony.CallsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
 * [CallSource] over `CallsApi.snapshot()` (the JSON the registry built, AD-4), re-read on each emission of [changes]:
 * the registry's snapshot seq (`CallRegistry.snapshotSeq`), which moves on every rebuild (it is set after the snapshot
 * text, so the two never disagree). Not the typed call list: it stays equal when only the audio route, mute or the
 * keyguard changes, and a state flow does not emit an equal value, so the Speaker button would never light up. A text
 * that does not decode is an empty snapshot, never an exception: a call screen must not crash while a call rings.
 */
class RegistryCallSource(
    private val calls: CallsApi,
    changes: Flow<Long>,
    scope: CoroutineScope,
) : CallSource {
    override val snapshot: StateFlow<CallSnapshot> =
        changes.map { read() }.stateIn(scope, SharingStarted.Eagerly, read())

    private fun read(): CallSnapshot =
        runCatching { Envelope.json.decodeFromString(CallSnapshot.serializer(), calls.snapshot()) }
            .getOrDefault(CallSnapshot())
}
