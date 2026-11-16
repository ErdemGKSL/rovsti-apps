package dev.rosty.messages.wa

import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.TypingState
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.GROUP
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.platform.system.permissions.PermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inbox's "typing…" and "recording audio…" lines, and what a hold on the microphone leads to. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaChatStatesTest {
    @Test fun aRecordingLineLastsUntilPausedTheirMessageOrTheTimeout() =
        runTest {
            val live = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 16)
            val states = WaChatStates(backgroundScope, live, shownMs = 10_000).start()
            runCurrent()
            live.emit(GatewayEvent.Typing(ADA, ADA, TypingState.Recording))
            live.emit(GatewayEvent.Typing(GROUP, MERT, TypingState.Typing))
            runCurrent()
            assertEquals(
                mapOf(ADA to WaChatState(ADA, recording = true), GROUP to WaChatState(MERT, recording = false)),
                states.now.value,
            )
            // Paused ends Ada's line; Mert's message ends his.
            live.emit(GatewayEvent.Typing(ADA, ADA, TypingState.Paused))
            live.emit(WaTestKit.incoming(GROUP, "g1", NOW, "here").copy(sender = MERT))
            runCurrent()
            assertTrue(states.now.value.isEmpty())

            // No paused at all: the line goes on its own; a new event keeps it longer.
            live.emit(GatewayEvent.Typing(ADA, ADA, TypingState.Typing))
            runCurrent()
            advanceTimeBy(8_000)
            live.emit(GatewayEvent.Typing(ADA, ADA, TypingState.Recording))
            runCurrent()
            advanceTimeBy(8_000)
            runCurrent()
            assertEquals(mapOf(ADA to WaChatState(ADA, recording = true)), states.now.value)
            advanceTimeBy(2_001)
            runCurrent()
            assertTrue(states.now.value.isEmpty())
        }

    @Test fun myOwnMessageDoesNotEndTheirLine() =
        runTest {
            val live = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 16)
            val states = WaChatStates(backgroundScope, live).start()
            runCurrent()
            live.emit(GatewayEvent.Typing(ADA, ADA, TypingState.Typing))
            live.emit(WaTestKit.incoming(ADA, "m1", NOW, "mine").copy(fromMe = true))
            runCurrent()
            assertEquals(setOf(ADA), states.now.value.keys)
        }

    @Test fun theMicrophoneRecordsOnlyWithThePermission() {
        assertEquals(MicStep.Record, micStep(PermissionState.Granted))
        assertEquals(MicStep.Ask, micStep(PermissionState.NeverAsked))
        assertEquals(MicStep.Ask, micStep(PermissionState.DeniedOnce))
        assertEquals(MicStep.Settings, micStep(PermissionState.DeniedForGood))
        assertEquals(MicStep.Settings, micStep(PermissionState.RevokedWhileRunning))
    }
}
