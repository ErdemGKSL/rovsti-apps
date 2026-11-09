package dev.rovsti.platform.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** E3 AC7: photos come from the system Photo Picker and the camera app; the library asks for no media or camera right. */
class NoMediaPermissionTest {
    private val banned =
        Regex(
            """READ_MEDIA_|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|permission\.CAMERA|Manifest\.permission\.CAMERA""",
        )

    @Test fun noSourceNamesAMediaOrCameraPermission() {
        val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList()
        assertTrue(sources.size > 20)
        val hits = sources.filter { banned.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun theLibraryManifestDeclaresNone() {
        val text = File("src/main/AndroidManifest.xml").readText()
        assertTrue(text.contains("<manifest"))
        assertEquals(emptyList<String>(), banned.findAll(text).map { it.value }.toList())
    }

    @Test fun theAppManifestsDeclareNone() {
        val apps = File("../../apps").takeIf { it.isDirectory }
        val manifests =
            apps
                ?.walkTopDown()
                ?.filter {
                    it.name == "AndroidManifest.xml" && "/build/" !in it.path
                }?.toList()
                .orEmpty()
        val hits = manifests.filter { banned.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals(emptyList<String>(), hits)
    }
}
