package dev.rosty.core

import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultCreateRequest
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultIdleRefusal
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultStateRequest
import dev.rosty.core.model.wa.VaultStayUnlockRequest
import dev.rosty.core.model.wa.VaultTouchRequest
import dev.rosty.core.model.wa.VaultUnlockRequest
import dev.rosty.core.model.wa.VaultWipeRequest
import dev.rosty.core.ops.vaultCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FakeVaultOps] walks the vault protocol the way the Rust core does, and fails with the codes the contract fixtures
 * hold. No native library.
 */
class FakeVaultOpsTest {
    private val key = "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"
    private val link = VaultLink("https://rosty.erdemdev.tr", "inst1", key)
    private val t0 = 1_800_000_000_000L
    private val wipe = VaultWipeRequest(filesDir = "/f")

    /** What a Keystore seal looks like from here: anything reversible. */
    private fun seal(text: String) = text.reversed()

    private fun <T> Outcome<T>.ok(): T = (this as Outcome.Ok).value

    private fun Outcome<*>.code(): String? = (this as Outcome.Err).error.vaultCode

    /** `detail.code` of a contract fixture answer. */
    private fun fixtureCode(stem: String): String {
        val dir = File(System.getProperty("rosty.contract") ?: error("rosty.contract not set"))
        val answer = Json.parseToJsonElement(File(dir, "$stem.answer.json").readText()).jsonObject
        val detail = answer["err"]!!.jsonObject["detail"] as JsonObject
        return detail["code"]!!.jsonPrimitive.content
    }

    private fun unlockRequest(
        pass: String,
        boot: Long = 3,
    ) = VaultUnlockRequest(keyBlob = "k", passphrase = pass, bootCount = boot, nowMs = t0)

    private fun dailyRequest(
        blob: String,
        now: Long = t0 + 1,
    ) = VaultDailyUnlockRequest(dailyBlob = blob, nowMs = now)

    private val day = 24 * 3_600_000L

    @Test
    fun migrationUnlockAndDailyUnlock() =
        runTest {
            val vault = FakeVaultOps(legacyLink = link)
            assertTrue(vault.state().ok().legacyLink)
            assertEquals(fixtureCode("vault.wipe_plaintext.no_vault"), vault.wipePlaintext(wipe).code())

            val keys = vault.create().ok()
            assertEquals(34, keys.passphrase.length)
            assertNull("nothing on disk before the seal", vault.state().ok().vault)
            val committed = vault.commit(VaultCommitRequest(keys.handle, "rosty.wa.k1", seal(keys.seal))).ok()
            assertEquals(keys.handle, committed.unlocked?.handle)
            assertEquals(listOf("whatsapp-link.json"), vault.wipePlaintext(wipe).ok().deleted)
            assertEquals(fixtureCode("vault.create.exists"), vault.create().code())

            vault.lock()
            assertNull(vault.openHandle)
            assertEquals(fixtureCode("vault.unlock.typo"), vault.unlock(unlockRequest("12")).code())
            val wrong = vault.unlock(unlockRequest("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ"))
            assertEquals(fixtureCode("vault.unlock.wrong_passphrase"), wrong.code())
            val opened = vault.unlock(unlockRequest(keys.passphrase.lowercase().replace('-', ' '))).ok()
            assertEquals(opened.handle, vault.openHandle)

            val store = VaultDailyStoreRequest(opened.handle, "rosty.wa.d1", seal(opened.dailySeal!!))
            val daily = vault.dailyStore(store).ok().daily!!
            val stay =
                vault
                    .stayStore(
                        VaultDailyStoreRequest(opened.handle, "rosty.wa.s1", seal(opened.staySeal!!)),
                    ).ok()
                    .stay!!
            vault.lock(VaultLockRequest(opened.handle))
            // After a reboot the stay wrap opens with no prompt; it makes no new wrap.
            val silent = vault.unlockStay(VaultStayUnlockRequest(stayBlob = seal(stay.sealed), nowMs = t0 + 1)).ok()
            assertEquals(silent.handle, vault.openHandle)
            assertNull(silent.staySeal)
            // "Lock now": the stay wrap goes; the biometric unlock makes a new one.
            vault.lock()
            vault.stayForget()
            assertEquals(
                fixtureCode("vault.unlock_stay.no_stay"),
                vault.unlockStay(VaultStayUnlockRequest(stayBlob = "x", nowMs = t0)).code(),
            )
            val again = vault.unlockDaily(dailyRequest(seal(daily.sealed))).ok()
            assertEquals(again.handle, vault.openHandle)
            assertNotNull(again.staySeal)
            vault.lock()
            val idle = vault.unlockDaily(dailyRequest(seal(daily.sealed), now = t0 + 91 * day))
            assertEquals(fixtureCode("vault.unlock_daily.idle"), idle.code())
            assertEquals("idle", (idle as Outcome.Err).error.reason)
            val gone = vault.unlockDaily(dailyRequest("x"))
            assertEquals(fixtureCode("vault.unlock_daily.no_daily"), gone.code())
        }

    @Test
    fun anyUseMovesThe90DaysOnAndTouchIsThrottled() =
        runTest {
            val vault = FakeVaultOps()
            vault.openVault(link)
            val opened = vault.unlock(unlockRequest(List(7) { "0000" }.joinToString("-"))).ok()
            vault.stayStore(VaultDailyStoreRequest(opened.handle, "rosty.wa.s1", "s"))
            assertEquals(true, vault.touch(VaultTouchRequest(t0 + 80 * day)).ok().recorded)
            assertEquals(false, vault.touch(VaultTouchRequest(t0 + 80 * day + 1_000)).ok().recorded)
            assertNull(vault.state(VaultStateRequest(nowMs = t0 + 160 * day)).ok().expired)
            val idle = vault.touch(VaultTouchRequest(t0 + 171 * day))
            assertEquals(fixtureCode("vault.touch.idle"), idle.code())
            assertNull(vault.state().ok().stay)
            vault.lock()
            assertEquals(fixtureCode("vault.touch.locked"), vault.touch(VaultTouchRequest(t0)).code())
        }

    @Test
    fun changeNeedsTheNewPassphraseAndStateDropsTheWrapsAfter90IdleDays() =
        runTest {
            val vault = FakeVaultOps()
            assertEquals(fixtureCode("vault.create.no_link"), vault.create().code())
            val keys = vault.create(VaultCreateRequest(link = link)).ok()
            vault.commit(VaultCommitRequest(keys.handle, "rosty.wa.k1", seal(keys.seal)))
            val twice = vault.commit(VaultCommitRequest(keys.handle, "a", "b"))
            assertEquals(fixtureCode("vault.commit.twice"), twice.code())
            val changed = vault.change(VaultHandleRequest(keys.handle)).ok()
            assertNotEquals(keys.passphrase, changed.passphrase)
            vault.commit(VaultCommitRequest(keys.handle, "rosty.wa.k2", seal(changed.seal)))
            assertEquals(
                "rosty.wa.k2",
                vault
                    .state()
                    .ok()
                    .vault
                    ?.hwAlias,
            )
            vault.lock()
            assertEquals(fixtureCode("vault.change.locked"), vault.change(VaultHandleRequest(keys.handle)).code())
            assertEquals("wrong_passphrase", vault.unlock(unlockRequest(keys.passphrase, boot = 1)).code())
            val opened = vault.unlock(unlockRequest(changed.passphrase, boot = 1)).ok()
            vault.dailyStore(VaultDailyStoreRequest(opened.handle, "rosty.wa.d1", "s"))
            vault.stayStore(VaultDailyStoreRequest(opened.handle, "rosty.wa.s1", "s"))
            // A reboot and 72 h end nothing any more; 90 days without a use do.
            assertNull(vault.state(VaultStateRequest(bootCount = 9, nowMs = t0 + 89 * day)).ok().expired)
            val idle = vault.state(VaultStateRequest(nowMs = t0 + 90 * day + 1)).ok()
            assertEquals(VaultIdleRefusal.Idle, idle.expired)
            assertNull(idle.daily)
            assertNull(idle.stay)
            assertNotNull(
                vault
                    .reset()
                    .ok()
                    .deleted
                    .singleOrNull(),
            )
            assertNull(vault.state().ok().vault)
        }
}
