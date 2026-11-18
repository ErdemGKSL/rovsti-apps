package dev.rosty.wa.share

import dev.rosty.platform.vault.FakeVaultKeystore
import dev.rosty.platform.vault.VaultAlias
import dev.rosty.platform.vault.VaultKeyKind
import dev.rosty.platform.vault.VaultPrompt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A sibling's "Lock now" in an app whose process is not running (round 7c): only the stay wrap goes, file and K_stay,
 * with no core. The vault, the biometric unlock, the push key and other files stay.
 */
class WaLockAllTest {
    @Test fun aColdAppDropsOnlyItsStayWrap() =
        runTest {
            val dir = Files.createTempDirectory("walockall").toFile()
            val keystore = FakeVaultKeystore()
            val prompt = VaultPrompt("x", negative = "no")
            val stay = keystore.newAlias(VaultKeyKind.Stay)
            val daily = keystore.newAlias(VaultKeyKind.Daily)
            val hardware = keystore.newAlias(VaultKeyKind.Hardware)
            for (alias in listOf(stay, daily, hardware)) keystore.seal(alias, "AQ==", prompt)
            for (name in listOf(
                WaLockAll.STAY_FILE,
                "${WaLockAll.STAY_FILE}.tmp",
                WaLockAll.VAULT_FILE,
                "wa-vault-daily.json",
            )) {
                dir.resolve(name).writeText("{}")
            }

            WaLockAll.forgetStay(dir, keystore)

            assertFalse(dir.resolve(WaLockAll.STAY_FILE).exists())
            assertFalse(dir.resolve("${WaLockAll.STAY_FILE}.tmp").exists())
            assertTrue(dir.resolve(WaLockAll.VAULT_FILE).exists())
            assertTrue(dir.resolve("wa-vault-daily.json").exists())
            assertEquals(listOf(daily, hardware), keystore.aliases())
            assertTrue(keystore.aliases().none { VaultAlias.kindOf(it) == VaultKeyKind.Stay })
            dir.deleteRecursively()
        }
}
