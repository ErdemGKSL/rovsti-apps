package dev.rosty.wa

import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.platform.PlatformEvent
import dev.rosty.platform.wa.WaPush
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository of an app with a WhatsApp vault (`plans/wa-vault.md` 6, 13.6, 15): it starts locked and sends
 * nothing (not even for a push) until the vault opens, then reads the vault's link, without a key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaRepositoryVaultTest {
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU")
    private val push = MutableSharedFlow<PlatformEvent>(extraBufferCapacity = 8)

    @Test fun aLockedStartSendsNothingUntilTheVaultOpens() =
        runTest(UnconfinedTestDispatcher()) {
            val fake =
                FakeWaGateway(link = link).apply {
                    status = Outcome.Ok(InstanceStatus("inst1", InstanceState.Connected))
                }
            var unlocked = false
            val opened = mutableListOf<StoredLink>()
            val repo =
                DefaultWaRepository(
                    store = WaLinkStore(fake.ops),
                    scope = backgroundScope,
                    opener = { link ->
                        opened += link
                        fake.opener.open(link)
                    },
                    waEvents = fake.waEvents,
                    platformEvents = push,
                    // The vault's link has no key: `wa.open` takes it from the vault.
                    source = { Outcome.Ok(if (unlocked) link.copy(key = "") else null) },
                    startLocked = true,
                )
            repo.start()
            assertTrue(repo.settled.value)
            assertNull(repo.gateway())
            push.emit(PlatformEvent(WaPush.EVENT, "{}"))
            repo.wake()
            assertTrue("no request while locked", fake.core.calls.isEmpty())
            unlocked = true
            repo.unlock()
            assertNotNull(repo.gateway())
            assertEquals(listOf(""), opened.map { it.key })
            repo.lock()
            assertNull(repo.gateway())
            assertEquals(1, opened.size)
        }
}
