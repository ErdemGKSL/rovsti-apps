package dev.rosty.shell

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.AppId
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.ops.WaGateway
import dev.rosty.wa.FakeWaGateway
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaLinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The FCM token reaches the gateway when the link is up: without it the gateway sends no push at all. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WaPushRegistrarTest {
    private val fake = FakeWaGateway()
    private val registered = ArrayList<Pair<String, String>>()

    init {
        fake.core.on("wa.register_push") { request ->
            val body = request as JsonObject
            registered += body.getValue("app").jsonPrimitive.content to body.getValue("token").jsonPrimitive.content
            Outcome.Ok(JsonObject(emptyMap()))
        }
    }

    private suspend fun openGateway(): WaGateway? =
        (WaGateway.open("https://rosty.erdemdev.tr", "key", null, fake.ops, fake.audio, release = {}) as? Outcome.Ok)
            ?.value

    private fun connected(instance: String) =
        WaLinkState.Connected(InstanceStatus(instanceId = instance, state = InstanceState.Connected))

    private fun registrar(
        scope: TestScope,
        repo: FakeWaRepository,
        token: () -> String,
    ) = WaPushRegistrar(
        wa = repo,
        app = AppId.Messages,
        scope = scope,
        token = token,
        io = StandardTestDispatcher(scope.testScheduler),
    )

    @Test
    fun theTokenIsRegisteredOnceWhenTheLinkComesUp() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val repo = FakeWaRepository().apply { gatewayOpener = { openGateway() } }
            registrar(scope, repo) { "fcm-1" }.start()
            scope.advanceUntilIdle()
            assertEquals(emptyList<Pair<String, String>>(), registered)
            repo.setState(connected("inst1"))
            scope.advanceUntilIdle()
            // The same instance again (a reconnect) registers nothing more.
            repo.setState(WaLinkState.None)
            scope.advanceUntilIdle()
            repo.setState(connected("inst1"))
            scope.advanceUntilIdle()
            assertEquals(listOf("messages" to "fcm-1"), registered)
        }

    @Test
    fun withoutFirebaseNothingIsRegistered() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val repo = FakeWaRepository(connected("inst1")).apply { gatewayOpener = { openGateway() } }
            registrar(scope, repo) { "" }.start()
            scope.advanceUntilIdle()
            assertEquals(emptyList<Pair<String, String>>(), registered)
        }

    @Test
    fun aNewTokenIsRegisteredAtOnce() =
        runTest {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            var token = "fcm-1"
            val repo = FakeWaRepository(connected("inst1")).apply { gatewayOpener = { openGateway() } }
            val push = registrar(scope, repo) { token }
            push.start()
            scope.advanceUntilIdle()
            token = "fcm-2"
            push.refresh()
            scope.advanceUntilIdle()
            assertEquals(listOf("messages" to "fcm-1", "messages" to "fcm-2"), registered)
        }

    @Test
    fun appIdsOfThePackages() {
        assertEquals(AppId.Messages, WaPushRegistrar.appOf("dev.rosty.messages"))
        assertEquals(AppId.Phone, WaPushRegistrar.appOf("dev.rosty.phone"))
        assertEquals(AppId.Contacts, WaPushRegistrar.appOf("dev.rosty.contacts"))
        assertNull(WaPushRegistrar.appOf("dev.rosty.stops"))
    }
}
