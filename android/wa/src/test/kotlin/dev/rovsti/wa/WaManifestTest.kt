package dev.rovsti.wa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The `:wa` library manifest declares no component and no permission, so an app cannot get one by accident (ARCH 7.2). */
class WaManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml")

    private fun elements(): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val all = doc.getElementsByTagName("*")
        return (0 until all.length).map { all.item(it).nodeName }
    }

    @Test fun manifestExists() = assertTrue(manifest.absolutePath, manifest.isFile)

    @Test fun declaresNoComponent() {
        val banned = setOf("activity", "activity-alias", "service", "receiver", "provider")
        assertEquals(emptyList<String>(), elements().filter { it in banned })
    }

    @Test fun theManifestIsEmpty() = assertEquals(listOf("manifest"), elements())
}
