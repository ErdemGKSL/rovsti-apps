package dev.rovsti.platform.telephony

import android.content.ComponentName
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dev.rovsti.core.Envelope
import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.model.phone.PhoneAccounts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SimRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val first =
        PhoneAccountInfo("com.android.phone/.PstnConnectionService#1", "SIM 1 (Turkcell)", 0, -16776961, true, true)
    private val second = PhoneAccountInfo("com.android.phone/.PstnConnectionService#2", "SIM 2", 1, -65536, true, false)

    private fun fixture(): PhoneAccounts {
        val dir = File(requireNotNull(System.getProperty("rovsti.wire.fixtures")))
        val root = Envelope.json.parseToJsonElement(File(dir, "phone_accounts.json").readText()) as JsonObject
        return Envelope.json.decodeFromString(PhoneAccounts.serializer(), root.getValue("data").toString())
    }

    @Test fun theKeyIsTheOneTheCallLogColumnsBuild() {
        assertEquals("a/.B#1", SimAccounts.keyOf("a/.B", "1"))
        assertNull(SimAccounts.keyOf(null, "1"))
        assertNull(SimAccounts.keyOf("", "1"))
        assertNull(SimAccounts.keyOf("a/.B", null))
        val handle =
            PhoneAccountHandle(ComponentName("com.android.phone", "com.android.phone.PstnConnectionService"), "1")
        assertEquals(SimAccounts.keyOf(handle.componentName.flattenToString(), "1"), SimAccounts.keyOf(handle))
    }

    @Test fun askNeedsNoDefaultAndTwoAccounts() {
        assertTrue(SimAccounts.ask(null, 2))
        assertFalse(SimAccounts.ask(null, 1))
        assertFalse(SimAccounts.ask("k", 2))
    }

    @Test fun theFakeAnswersWhatTheFixtureDecodes() =
        runTest {
            val expected = fixture()
            val fake = FakeSimRepository(expected.accounts, expected.defaultKey)
            assertEquals(expected, fake.accounts().first())
            assertEquals(listOf(first, second), expected.accounts)
        }

    @Test fun collectReadsTheTelecomAccounts() {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val handle =
            PhoneAccountHandle(ComponentName("com.android.phone", "com.android.phone.PstnConnectionService"), "1")
        val account =
            PhoneAccount
                .builder(handle, "SIM 1")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER or PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
                .setHighlightColor(-16776961)
                .build()
        telecom.registerPhoneAccount(account)
        shadowOf(telecom).enablePhoneAccount(handle, true)
        shadowOf(telecom).setUserSelectedOutgoingPhoneAccount(handle)
        val collected = SimAccounts.collect(context)!!
        val info = collected.accounts.single()
        assertEquals(SimAccounts.keyOf(handle), info.key)
        assertEquals("SIM 1", info.label)
        assertEquals(-16776961, info.colorArgb)
        assertTrue(info.sim)
        assertEquals(info.key, collected.defaultKey)
        assertFalse("one account never asks", collected.ask)
        assertEquals(handle, SimAccounts.handleFor(context, info.key))
        assertNull(SimAccounts.handleFor(context, "x#9"))
    }

    private fun TestScope.repository(
        changes: MutableSharedFlow<Unit>,
        reads: () -> Unit,
        result: () -> PhoneAccounts?,
    ) = AndroidSimRepository(
        context,
        StandardTestDispatcher(testScheduler),
        changes,
        collect = {
            reads()
            result()
        },
    )

    @Test fun aBurstOfSubscriptionChangesIsOneRequery() =
        runTest {
            val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
            var reads = 0
            var now = PhoneAccounts(listOf(first), first.key)
            val seen = mutableListOf<PhoneAccounts>()
            backgroundScope.launch { repository(changes, { reads++ }, { now }).accounts().collect { seen += it } }
            runCurrent()
            assertEquals(1, reads)
            assertEquals(listOf(PhoneAccounts(listOf(first), first.key)), seen)

            now = PhoneAccounts(listOf(first, second), null, ask = true)
            repeat(5) { changes.tryEmit(Unit) }
            runCurrent()
            assertEquals("nothing reads before the burst settles", 1, reads)
            advanceTimeBy(AndroidSimRepository.CHANGE_DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(2, reads)
            assertEquals(now, seen.last())
        }

    @Test fun aRefusedReadIsNoAccounts() =
        runTest {
            val repository = repository(MutableSharedFlow(), {}, { null })
            assertEquals(PhoneAccounts(), repository.accounts().first())
        }

    @Test fun theProbeReportsTheKeyPair() =
        runTest {
            var now = PhoneAccounts(listOf(first, second), null, ask = true)
            val repository = repository(MutableSharedFlow(), {}, { now })
            assertEquals("pass: key equals the call-log pair", repository.probe(first.key, first.key)[3])
            assertTrue(repository.probe(first.key, second.key).any { it.startsWith("fail:") })
            assertTrue(repository.probe(null, null).any { it.startsWith("unknown: key equals") })
            val lines = repository.probe(first.key, first.key)
            assertEquals("info accounts=2 default=none ask=true", lines[0])
            assertTrue(lines[1].contains("slot=0") && lines[1].contains("#FF0000FF"))
            now = PhoneAccounts(listOf(first), first.key)
            assertTrue(repository.probe(null, null).last().startsWith("unknown: key equals the call-log pair (n/a"))
        }
}
