package dev.rovsti.phone

import android.telecom.CallAudioState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPathTest {
    @Test fun legacyEndpointsComeFromTheRouteMask() {
        val mask = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER or CallAudioState.ROUTE_BLUETOOTH
        val list = AudioPath.legacyEndpoints(mask, CallAudioState.ROUTE_BLUETOOTH)
        assertEquals(listOf("legacy:earpiece", "legacy:bluetooth", "legacy:speaker"), list.map { it.id })
        assertEquals(listOf(false, true, false), list.map { it.selected })
        // Types only: the legacy path has no names (visible degradation).
        assertEquals(listOf<String?>(null, null, null), list.map { it.name })
    }

    @Test fun legacyIdsMapToSetAudioRouteMasks() {
        assertEquals(CallAudioState.ROUTE_SPEAKER, AudioPath.legacyRouteMask("legacy:speaker"))
        assertEquals(CallAudioState.ROUTE_WIRED_HEADSET, AudioPath.legacyRouteMask("legacy:wired_headset"))
        assertEquals(0, AudioPath.legacyRouteMask("legacy:nonsense"))
        assertEquals(0, AudioPath.legacyRouteMask("7c1d0c3e-0000-4000-8000-000000000001"))
    }

    @Test fun endpointTypesMapAndStreamingIsDropped() {
        assertEquals("earpiece", AudioPath.typeName(1))
        assertEquals("bluetooth", AudioPath.typeName(2))
        assertEquals("wired_headset", AudioPath.typeName(3))
        assertEquals("speaker", AudioPath.typeName(4))
        assertNull(AudioPath.typeName(5))
        assertEquals("unknown", AudioPath.typeName(-1))
    }
}
