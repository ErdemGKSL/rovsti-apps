package dev.rovsti.shell.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The Gradle copy task puts the repository's own files into the assets: the app reads the same text the repo holds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LicenceAssetsTest {
    @Test fun theAssetsAreTheRepositoryFiles() {
        val data = runBlocking { RuntimeEnvironment.getApplication().loadLicenceData() }
        assertNotNull("assets licences/ missing: the copyLicenceAssets task did not run", data)
        assertEquals(File("../../LICENSE").readLines(), data!!.gplText)
        assertEquals(realLicenceData().notices, data.notices)
        assertTrue(data.gplText.first().contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(data.notices.projects.any { it.name == "Convx" })
    }
}
