package dev.rovsti.phone

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import dev.rovsti.bridge.AudioInfo
import dev.rovsti.bridge.EndpointInfo

/**
 * The audio half of the registry (AD-23): the endpoint list as Telecom reports it, an id map to
 * route by, and the legacy `getCallAudioState()` fallback (spike S3). Main thread only, except
 * [knownIds], which is a volatile copy for the validation of commands from other threads.
 */
class AudioPath {
    private var available: List<CallEndpoint> = emptyList()
    private var current: CallEndpoint? = null
    private var byId: Map<String, CallEndpoint> = emptyMap()
    private var infos: List<EndpointInfo> = emptyList()
    private var hearingNames: Set<String> = emptySet()
    private var endpointsSeen = false
    private var timedOut = false

    /** Endpoint ids a command may name right now (any thread). */
    @Volatile var knownIds: Set<String> = emptySet()
        private set

    /** The legacy path is in use (forced, or Telecom never sent endpoints). */
    val legacy: Boolean get() = Caps.LEGACY_AUDIO_FORCED || timedOut

    /** Telecom's `onAvailableCallEndpointsChanged`. [hearing] = product names of hearing-aid devices. */
    fun onAvailable(list: List<CallEndpoint>, hearing: Set<String>) {
        available = list
        hearingNames = hearing
        endpointsSeen = true
        if (current != null) timedOut = false
        rebuildInfos()
    }

    /** Telecom's `onCallEndpointChanged`. */
    fun onCurrent(endpoint: CallEndpoint) {
        current = endpoint
        if (endpointsSeen) timedOut = false
        rebuildInfos()
    }

    /** 1.5 s after the first call: no endpoints (or no current one) means the legacy path. Returns true when it changed. */
    fun onFallbackDeadline(): Boolean {
        if (timedOut || (endpointsSeen && current != null)) return false
        timedOut = true
        return true
    }

    fun clear() {
        available = emptyList()
        current = null
        byId = emptyMap()
        infos = emptyList()
        knownIds = emptySet()
        endpointsSeen = false
        timedOut = false
    }

    private fun rebuildInfos() {
        val map = LinkedHashMap<String, CallEndpoint>(available.size)
        val out = ArrayList<EndpointInfo>(available.size)
        val currentId = current?.identifier
        for (e in available) {
            val type = typeName(e.endpointType) ?: continue
            val id = e.identifier.toString()
            map[id] = e
            val name = if (type in NAMED) e.endpointName.toString().trim() else ""
            out.add(
                EndpointInfo(
                    id = id,
                    type = type,
                    name = name.ifEmpty { null },
                    selected = e.identifier == currentId,
                    hint = if (type == "bluetooth" && name.isNotEmpty() && name in hearingNames) "hearing_aid" else null,
                ),
            )
        }
        byId = map
        infos = out
        knownIds = map.keys
    }

    /** The audio section for the snapshot; [legacyState] feeds the fallback list. */
    fun info(muted: Boolean, legacyState: CallAudioState?): AudioInfo {
        if (legacy && legacyState != null) {
            return AudioInfo.derived(muted, legacyEndpoints(legacyState.supportedRouteMask, legacyState.route))
        }
        return AudioInfo.derived(muted, infos, typeName(current?.endpointType ?: -1) ?: "earpiece")
    }

    fun endpoint(id: String): CallEndpoint? = byId[id]

    /** The unique earpiece or speaker endpoint for the Phone/Speaker toggle; null when absent. */
    fun target(name: String): CallEndpoint? = available.firstOrNull { typeName(it.endpointType) == name }

    /** The route name of the selected endpoint, or null when none is known. */
    fun routeName(): String? = typeName(current?.endpointType ?: -1)

    companion object {
        private val NAMED = setOf("bluetooth", "wired_headset", "unknown")

        /** `CallEndpoint.TYPE_*` to the wire type; null for streaming, which is never sent. */
        fun typeName(type: Int): String? = when (type) {
            CallEndpoint.TYPE_EARPIECE -> "earpiece"
            CallEndpoint.TYPE_BLUETOOTH -> "bluetooth"
            CallEndpoint.TYPE_WIRED_HEADSET -> "wired_headset"
            CallEndpoint.TYPE_SPEAKER -> "speaker"
            CallEndpoint.TYPE_STREAMING -> null
            else -> "unknown"
        }

        const val LEGACY_PREFIX = "legacy:"

        /** Synthetic endpoints from a `CallAudioState` route mask, types only (no names). */
        fun legacyEndpoints(supportedMask: Int, route: Int): List<EndpointInfo> {
            val order = listOf(
                CallAudioState.ROUTE_EARPIECE to "earpiece",
                CallAudioState.ROUTE_BLUETOOTH to "bluetooth",
                CallAudioState.ROUTE_WIRED_HEADSET to "wired_headset",
                CallAudioState.ROUTE_SPEAKER to "speaker",
            )
            return order.filter { (bit, _) -> supportedMask and bit != 0 }
                .map { (bit, name) -> EndpointInfo("$LEGACY_PREFIX$name", name, selected = route == bit) }
        }

        /** `legacy:<type>` to the `setAudioRoute` mask; 0 for anything else. */
        fun legacyRouteMask(id: String): Int = when (id) {
            "${LEGACY_PREFIX}earpiece" -> CallAudioState.ROUTE_EARPIECE
            "${LEGACY_PREFIX}bluetooth" -> CallAudioState.ROUTE_BLUETOOTH
            "${LEGACY_PREFIX}wired_headset" -> CallAudioState.ROUTE_WIRED_HEADSET
            "${LEGACY_PREFIX}speaker" -> CallAudioState.ROUTE_SPEAKER
            else -> 0
        }

        /** Product names of connected hearing aids (best effort, API 31); empty on any failure. */
        fun hearingAidNames(context: Context?): Set<String> = try {
            val audio = context?.getSystemService(AudioManager::class.java)
            audio?.availableCommunicationDevices
                ?.filter { it.type == AudioDeviceInfo.TYPE_HEARING_AID }
                ?.map { it.productName.toString().trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        } catch (_: RuntimeException) {
            emptySet()
        }
    }
}
