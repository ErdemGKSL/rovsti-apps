package dev.rovsti.platform.files

import android.content.Context
import android.net.Uri
import dev.rovsti.bridge.RovstiShareProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RovstiShareProviderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var provider: RovstiShareProvider

    @Before fun setUp() {
        provider =
            Robolectric
                .buildContentProvider(
                    RovstiShareProvider::class.java,
                ).create(MediaShare.authority(context))
                .get()
        MediaShare.dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun uri(segment: String): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(MediaShare.authority(context))
            .appendPath(segment)
            .build()

    @Test fun aSafeNameOpensReadOnly() {
        File(MediaShare.dir(context), "photo.png").writeText("png")
        assertNotNull(provider.openFile(uri("photo.png"), "r"))
        assertNull(provider.openFile(uri("photo.png"), "w"))
        assertNull(provider.openFile(uri("photo.png"), "wt"))
        assertNull(provider.openFile(uri("photo.png"), "rw"))
    }

    @Test fun traversalAndAbsolutePathsAreRefused() {
        File(context.cacheDir, "secret.txt").writeText("secret")
        for (bad in listOf("../secret.txt", "..", "/etc/passwd", "a/b.txt", "..%2Fsecret.txt", "", ".../x")) {
            assertNull(bad, provider.openFile(uri(bad), "r"))
        }
        assertNull(provider.openFile(Uri.parse("content://${MediaShare.authority(context)}/../secret.txt"), "r"))
        assertNull(provider.openFile(Uri.parse("content://${MediaShare.authority(context)}/%2E%2E%2Fsecret.txt"), "r"))
    }

    @Test fun onlyCameraJpgIsWritable() {
        File(MediaShare.dir(context), "camera-1.jpg").writeText("")
        File(MediaShare.dir(context), "a.jpg").writeText("")
        assertNotNull(provider.openFile(uri("camera-1.jpg"), "w"))
        assertNotNull(provider.openFile(uri("camera-1.jpg"), "wt"))
        assertNull(provider.openFile(uri("a.jpg"), "w"))
        assertNull(provider.openFile(uri("camera-1.png"), "w"))
    }

    @Test fun aMissingFileIsNull() = assertNull(provider.openFile(uri("none.png"), "r"))

    @Test fun getTypeFollowsMimeFor() {
        assertEquals("image/png", provider.getType(uri("a.png")))
        assertEquals("image/jpeg", provider.getType(uri("camera-1.jpg")))
        assertEquals("application/pdf", provider.getType(uri("a.PDF")))
        assertEquals("application/octet-stream", provider.getType(uri("a.bin")))
    }

    @Test fun theProviderStoresNothing() {
        assertNull(provider.query(uri("a.png"), null, null, null, null))
        assertNull(provider.insert(uri("a.png"), null))
        assertEquals(0, provider.delete(uri("a.png"), null, null))
        assertEquals(0, provider.update(uri("a.png"), null, null, null))
    }

    @Test fun theAuthorityIsPackagePlusRovstishare() =
        assertEquals("${context.packageName}.rovstishare", MediaShare.authority(context))

    @Test fun stagedFilesAreServed() {
        val src = File(context.cacheDir, "in.png").apply { writeText("x") }
        val staged = MediaShare.stageForShare(context, src.path, "my pic.png")!!
        assertNotNull(provider.openFile(staged, "r"))
        assertEquals("image/png", provider.getType(staged))
        assertEquals(MediaShare.authority(context), staged.authority)
    }

    @Test fun safeNameKeepsExtensionAndFallsBack() {
        assertEquals("my_pic.png", MediaShare.safeName("my pic.png", "f"))
        assertEquals("f", MediaShare.safeName("", "f"))
        assertEquals("a_b.txt", MediaShare.safeName("a..b.txt", "f"))
    }
}
