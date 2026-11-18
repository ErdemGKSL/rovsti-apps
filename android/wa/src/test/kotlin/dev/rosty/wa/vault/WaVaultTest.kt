package dev.rosty.wa.vault

import dev.rosty.core.FakeVaultOps
import dev.rosty.core.getOrNull
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.platform.vault.FakeVaultKeystore
import dev.rosty.platform.vault.VaultAlias
import dev.rosty.platform.vault.VaultCrypto
import dev.rosty.platform.vault.VaultKeyAuth
import dev.rosty.platform.vault.VaultKeyKind
import dev.rosty.platform.vault.VaultPrompt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The vault's rules on the JVM (plan 3, 4, 5, 13.2): setup with two groups typed back, the daily (biometric) unlock and
 * when it is dropped (reboot, 72 h idle, 30 days, a boot broadcast, five failed attempts, an enrolment change), the
 * passphrase unlock, a change, a key that is gone, the reset, and the hand-over unlock from a sibling app (round 7a).
 */
class WaVaultTest {
    private val link = VaultLink("https://gw.example", "inst1", "rwk_" + "a".repeat(40))
    private val ops = FakeVaultOps(legacyLink = link)
    private val keystore = FakeVaultKeystore()
    private val prefs = MemoryWaVaultPrefs()
    private var boot = 7L
    private var now = 1_800_000_000_000L
    private var locks = 0
    private var unlocks = 0
    private var resets = 0

    /** What an unlocked sibling app would hand over (`WaLinkProvider`); null: no sibling is unlocked. */
    private var sibling: StoredLink? = null
    private val prompt = { title: String -> VaultPrompt(title, negative = "no") }
    private val vault =
        WaVault(
            ops = ops,
            keystore = keystore,
            prefs = prefs,
            env = WaVaultEnv(bootCount = { boot }, now = { now }),
            prompts = VaultPrompts(prompt("protect"), prompt("unlock"), prompt("daily"), prompt("enable")),
            onLock = { locks++ },
            onUnlocked = { unlocks++ },
            onReset = {
                resets++
                ops.legacyLink = null
            },
            random = Random(1),
            siblingLink = { sibling },
        )

    private suspend fun setUp(): PassphraseOffer {
        vault.refresh()
        assertEquals(WaVaultState.NeedsSetup, vault.state.value)
        val offer = vault.startSetup().getOrNull()!!
        assertEquals(VaultOutcome.Done, vault.confirm(offer, offer.ask.map { offer.groups[it] }))
        return offer
    }

    private fun dailyAliases() = keystore.keys.filter { VaultAlias.kindOf(it) == VaultKeyKind.Daily }

    private fun hardwareAliases() = keystore.keys.filter { VaultAlias.kindOf(it) == VaultKeyKind.Hardware }

    @Test fun noLinkAndNoVaultIsTheLinkFlow() =
        runTest {
            ops.legacyLink = null
            vault.refresh()
            assertEquals(WaVaultState.NoLink, vault.state.value)
        }

    @Test fun setupAsksForTwoGroupsThenSealsAndOffersTheDailyUnlock() =
        runTest {
            vault.refresh()
            val offer = vault.startSetup().getOrNull()!!
            assertEquals(7, offer.groups.size)
            assertEquals(2, offer.ask.toSet().size)
            assertEquals(
                VaultOutcome.Refused(VaultProblem.GroupsDiffer),
                vault.confirm(offer, listOf("ZZZZ", "ZZZZ")),
            )
            assertTrue("nothing sealed before the groups match", keystore.keys.isEmpty())
            // Case and spaces do not matter.
            val typed = offer.ask.map { " " + offer.groups[it].lowercase() }
            assertEquals(VaultOutcome.Done, vault.confirm(offer, typed))
            assertEquals(WaVaultState.Unlocked, vault.state.value)
            assertEquals(1, unlocks)
            assertEquals(listOf("protect", "enable"), keystore.prompts)
            assertEquals(1, hardwareAliases().size)
            assertEquals(1, dailyAliases().size)
            assertEquals(VaultKeyAuth.Biometric, VaultAlias.authOf(dailyAliases().single()))
        }

    @Test fun aCancelledSetupPromptLeavesNothingBehind() =
        runTest {
            vault.refresh()
            val offer = vault.startSetup().getOrNull()!!
            keystore.next = VaultCrypto.Cancelled
            assertEquals(VaultOutcome.Cancelled, vault.confirm(offer, offer.ask.map { offer.groups[it] }))
            assertTrue(keystore.keys.isEmpty())
            vault.refresh()
            assertEquals(WaVaultState.NeedsSetup, vault.state.value)
        }

    @Test fun aPhoneWithoutAScreenLockCannotSetUp() =
        runTest {
            keystore.device = keystore.device.copy(secureLockScreen = false)
            vault.refresh()
            val offer = vault.startSetup().getOrNull()!!
            assertEquals(
                VaultOutcome.Refused(VaultProblem.NoScreenLock),
                vault.confirm(offer, offer.ask.map { offer.groups[it] }),
            )
        }

    @Test fun lockThenTheBiometricUnlocks() =
        runTest {
            setUp()
            vault.lock()
            assertEquals(1, locks)
            assertEquals(WaVaultState.Locked(biometric = true), vault.state.value)
            assertNull(ops.openHandle)
            assertEquals(VaultOutcome.Done, vault.unlockWithBiometric())
            assertEquals(WaVaultState.Unlocked, vault.state.value)
            assertEquals(2, unlocks)
            assertNotNull(ops.openHandle)
        }

    @Test fun aRebootDropsTheDailyUnlock() =
        runTest {
            setUp()
            vault.lock()
            boot += 1
            vault.refresh()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.Rebooted), vault.state.value)
            assertTrue("the K_daily key is deleted", dailyAliases().isEmpty())
            assertEquals(VaultOutcome.Refused(VaultProblem.NeedsPassphrase), vault.unlockWithBiometric())
        }

    @Test fun seventyTwoHoursDropTheDailyUnlock() =
        runTest {
            setUp()
            vault.lock()
            now += 72 * 3_600_000L - 1
            vault.refresh()
            assertEquals(WaVaultState.Locked(biometric = true), vault.state.value)
            now += 2
            vault.refresh()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.Idle), vault.state.value)
            assertTrue(dailyAliases().isEmpty())
        }

    @Test fun theBootBroadcastDropsTheDailyUnlock() =
        runTest {
            setUp()
            vault.lock()
            vault.onBoot()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.Rebooted), vault.state.value)
            assertTrue(dailyAliases().isEmpty())
        }

    @Test fun fiveFailedAttemptsDropTheDailyUnlock() =
        runTest {
            setUp()
            vault.lock()
            keystore.failedAttempts = 4
            keystore.next = VaultCrypto.Cancelled
            assertEquals(VaultOutcome.Cancelled, vault.unlockWithBiometric())
            assertEquals(4, prefs.failedDaily)
            assertEquals(WaVaultState.Locked(biometric = true), vault.state.value)
            keystore.failedAttempts = 1
            keystore.next = VaultCrypto.Cancelled
            vault.unlockWithBiometric()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.TooManyAttempts), vault.state.value)
            assertEquals(0, prefs.failedDaily)
            assertTrue(dailyAliases().isEmpty())
        }

    @Test fun aSuccessResetsTheFailedAttempts() =
        runTest {
            setUp()
            vault.lock()
            keystore.failedAttempts = 4
            assertEquals(VaultOutcome.Done, vault.unlockWithBiometric())
            assertEquals(0, prefs.failedDaily)
        }

    @Test fun theCountSurvivesARestartOfTheProcess() =
        runTest {
            setUp()
            vault.lock()
            prefs.failedDaily = WaVault.MAX_FAILED_DAILY
            vault.refresh()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.TooManyAttempts), vault.state.value)
        }

    @Test fun aSystemLockoutDropsTheDailyUnlock() =
        runTest {
            setUp()
            vault.lock()
            keystore.next = VaultCrypto.LockedOut
            assertEquals(VaultOutcome.Refused(VaultProblem.NeedsPassphrase), vault.unlockWithBiometric())
            assertEquals(WaVaultState.Locked(false, PassphraseReason.TooManyAttempts), vault.state.value)
        }

    @Test fun anEnrolmentChangeDropsTheDailyUnlock() =
        runTest {
            setUp()
            vault.lock()
            keystore.next = VaultCrypto.Invalidated
            vault.unlockWithBiometric()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.BiometricChanged), vault.state.value)
        }

    @Test fun aScreenLockDailyKeyEndsOnceABiometricIsEnrolled() =
        runTest {
            keystore.device = keystore.device.copy(strongBiometric = false)
            setUp()
            assertEquals(VaultKeyAuth.Credential, VaultAlias.authOf(dailyAliases().single()))
            vault.lock()
            assertEquals(WaVaultState.Locked(biometric = true), vault.state.value)
            keystore.device = keystore.device.copy(strongBiometric = true)
            vault.refresh()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.BiometricChanged), vault.state.value)
        }

    @Test fun thePassphraseUnlocksAndBringsTheDailyUnlockBack() =
        runTest {
            val offer = setUp()
            vault.lock()
            vault.onBoot()
            keystore.prompts.clear()
            assertEquals(VaultOutcome.Refused(VaultProblem.Malformed), vault.unlockWithPassphrase("AAAA"))
            assertTrue("a malformed passphrase costs no prompt", keystore.prompts.isEmpty())
            val other = "ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ"
            assertEquals(VaultOutcome.Refused(VaultProblem.WrongPassphrase), vault.unlockWithPassphrase(other))
            assertEquals(listOf("unlock"), keystore.prompts)
            assertEquals(VaultOutcome.Done, vault.unlockWithPassphrase(offer.groups.joinToString(" ")))
            assertEquals(WaVaultState.Unlocked, vault.state.value)
            assertEquals(listOf("unlock", "unlock", "enable"), keystore.prompts)
            vault.lock()
            assertEquals(WaVaultState.Locked(biometric = true), vault.state.value)
        }

    @Test fun aGoneHardwareKeyBreaksTheVaultUntilReset() =
        runTest {
            val offer = setUp()
            vault.lock()
            hardwareAliases().forEach(keystore::delete)
            assertEquals(
                VaultOutcome.Refused(VaultProblem.Broken),
                vault.unlockWithPassphrase(offer.groups.joinToString("-")),
            )
            assertEquals(WaVaultState.Broken, vault.state.value)
            vault.refresh()
            assertEquals(WaVaultState.Broken, vault.state.value)
            assertEquals(VaultOutcome.Done, vault.reset())
            assertEquals(1, resets)
            assertEquals(WaVaultState.NoLink, vault.state.value)
            assertTrue(keystore.keys.isEmpty())
        }

    @Test fun forgottenPassphraseResetsEverything() =
        runTest {
            setUp()
            vault.lock()
            assertEquals(VaultOutcome.Done, vault.reset())
            assertTrue(keystore.keys.isEmpty())
            assertTrue("vault.reset" in ops.calls)
            assertEquals(WaVaultState.NoLink, vault.state.value)
        }

    @Test fun aChangeMakesANewHardwareKeyAndAsksForTheNewPassphraseOnce() =
        runTest {
            setUp()
            val before = hardwareAliases().single()
            val offer = vault.startChange().getOrNull()!!
            assertEquals(VaultOutcome.Done, vault.confirm(offer, offer.ask.map { offer.groups[it] }))
            assertEquals(WaVaultState.Unlocked, vault.state.value)
            val after = hardwareAliases().single()
            assertFalse(before == after)
            assertTrue("the old daily blob sealed the old data key", dailyAliases().isEmpty())
            vault.lock()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.NewPassphrase), vault.state.value)
            assertEquals(VaultOutcome.Done, vault.unlockWithPassphrase(offer.groups.joinToString("-")))
        }

    @Test fun anAbandonedChangeKeepsTheSessionOpen() =
        runTest {
            setUp()
            val offer = vault.startChange().getOrNull()!!
            vault.abandon(offer)
            assertNotNull(ops.openHandle)
        }

    @Test fun aBiometricUnlockResetsTheThreeDays() =
        runTest {
            setUp()
            vault.lock()
            now += 70 * HOUR
            assertEquals(VaultOutcome.Done, vault.unlockWithBiometric())
            vault.lock()
            now += 70 * HOUR
            vault.refresh()
            assertEquals(
                "140 h after the passphrase, 70 h idle",
                WaVaultState.Locked(biometric = true),
                vault.state.value,
            )
            assertEquals(VaultOutcome.Done, vault.unlockWithBiometric())
        }

    @Test fun thirtyDaysAskForThePassphraseHoweverOftenTheBiometricUnlocks() =
        runTest {
            setUp()
            vault.lock()
            repeat(14) {
                now += 48 * HOUR
                assertEquals(VaultOutcome.Done, vault.unlockWithBiometric())
                vault.lock()
            }
            now += 48 * HOUR + 1
            vault.refresh()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.Expired), vault.state.value)
            assertTrue(dailyAliases().isEmpty())
        }

    @Test fun anUnlockedSiblingOpensTheVaultWithoutAPrompt() =
        runTest {
            setUp()
            vault.lock()
            val before = keystore.prompts.size
            sibling = StoredLink(link.gatewayUrl, link.instanceId, link.key)
            assertTrue(vault.unlockFromSibling())
            assertEquals(WaVaultState.Unlocked, vault.state.value)
            assertEquals("no prompt", before, keystore.prompts.size)
            assertEquals(2, unlocks)
            assertNotNull(ops.openHandle)
        }

    @Test fun aSiblingHandOverAlsoWorksWhenThePassphraseWouldBeAsked() =
        runTest {
            setUp()
            vault.lock()
            boot += 1
            vault.refresh()
            assertEquals(WaVaultState.Locked(false, PassphraseReason.Rebooted), vault.state.value)
            sibling = StoredLink(link.gatewayUrl, link.instanceId, link.key)
            assertTrue(vault.unlockFromSibling())
            assertEquals(WaVaultState.Unlocked, vault.state.value)
        }

    @Test fun noSiblingOrAnotherLinkLeavesTheVaultLocked() =
        runTest {
            setUp()
            vault.lock()
            assertFalse(vault.unlockFromSibling())
            sibling = StoredLink(link.gatewayUrl, "inst2", link.key)
            assertFalse(vault.unlockFromSibling())
            sibling = StoredLink(link.gatewayUrl, link.instanceId, link.key)
            ops.linkWrapped = false
            assertFalse("a vault not unlocked since the update has no link wrap", vault.unlockFromSibling())
            assertEquals(WaVaultState.Locked(biometric = true), vault.state.value)
            assertNull(ops.openHandle)
        }

    @Test fun passphraseRules() {
        assertTrue(PassphraseText.wellFormed("abcd-efgh-jkmn-pqrs-tvwx-yz01-2345"))
        assertTrue(PassphraseText.wellFormed("ABCD EFGH JKMN PQRS TVWX YZ01 2345"))
        assertFalse(PassphraseText.wellFormed("ABCD-EFGH"))
        assertFalse(PassphraseText.wellFormed("ABCD-EFGH-JKMN-PQRS-TVWX-YZ01-234U"))
        assertEquals("1100", PassphraseText.canonical("iLoO"))
        repeat(50) { seed ->
            val picked = PassphraseText.pickGroups(7, Random(seed))
            assertEquals(2, picked.toSet().size)
            assertTrue(picked.all { it in 0 until 7 })
            assertEquals(picked.sorted(), picked)
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
