package dev.rovsti.phone

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.model.phone.DialTextAnswer
import dev.rovsti.core.model.phone.TelUriRequest
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.CallsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** E5.1: the container is lazy, uses the call registry and the Phone permission table, and decodes `tel:` links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneContainerTest {
    private class Fakes(
        val core: FakeRustCore = FakeRustCore(),
    ) : PhoneContainer(RuntimeEnvironment.getApplication()) {
        var opsBuilt = 0

        override fun createRust(): RustCore = core

        override fun createOps(): PhoneOps {
            opsBuilt++
            return NativePhoneOps(core)
        }
    }

    @Test fun nothingIsBuiltUntilItIsAsked() {
        val container = Fakes()
        assertEquals(0, container.opsBuilt)
        assertEquals(AppPermissions.phone.app, container.appPermissions.app)
    }

    @Test fun theCallOwnerIsTheRegistryAndTheCommandsRouteWhatsAppCallsToWaCalls() {
        val container = Fakes()
        assertSame(CallRegistry, container.registryCalls)
        val calls: CallsApi = container.calls
        assertTrue("the screens' commands go through the router (AD-WA5)", calls is dev.rovsti.phone.wa.WaCallsRouter)
    }

    @Test fun aTelLinkIsDecodedByTheCoreAndOfferedToTheKeypad() {
        val container = Fakes()
        container.core.onTyped("dial.from_tel_uri", TelUriRequest.serializer(), DialTextAnswer.serializer()) {
            Outcome.Ok(DialTextAnswer(if (it.uri == "tel:%2A21%23") "*21#" else ""))
        }
        assertNull(container.pendingDial.value)
        container.openTelUri("tel:%2A21%23")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (container.pendingDial.value == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("*21#", container.pendingDial.value?.text)
        assertEquals(false, container.pendingDial.value?.fromHandoff)
    }

    @Test fun twoLinksForTheSameNumberAreTwoRequests() {
        val container = Fakes()
        container.offerDial("123")
        val first = container.pendingDial.value
        container.offerDial("123")
        val second = container.pendingDial.value
        assertEquals(first?.text, second?.text)
        assertEquals(false, first == second)
    }

    /** A core whose calls wait for [gate], the way `NativeRustCore.call` waits for `Rust.init`. */
    private class GatedCore(
        val inner: FakeRustCore = FakeRustCore(),
    ) : RustCore by inner {
        val gate = CompletableDeferred<Unit>()

        override suspend fun call(
            op: String,
            request: JsonElement,
        ): Outcome<JsonElement> {
            gate.await()
            return inner.call(op, request)
        }
    }

    /** Owner rule (b): nothing reaches the native side before `Rust.init` is done. */
    @Test fun touchingTheDataLayerBeforeRustIsReadyIssuesNoNativeCall() {
        val gated = GatedCore()
        val container =
            object : PhoneContainer(RuntimeEnvironment.getApplication()) {
                override fun createRust(): RustCore = gated
            }
        try {
            container.store
            container.recents
            container.people
            container.simState
            container.speedDial
            container.block
            container.placer
            // Give the collectors time to reach their first call (they park on the gate).
            Thread.sleep(500)
            assertEquals(emptyList<String>(), gated.inner.calls.map { it.op })
            assertEquals(false, container.recents.model.value.ready)

            gated.gate.complete(Unit)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gated.inner.calls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(true, gated.inner.calls.isNotEmpty())
        } finally {
            gated.gate.complete(Unit)
            container.scope.cancel()
        }
    }
}
