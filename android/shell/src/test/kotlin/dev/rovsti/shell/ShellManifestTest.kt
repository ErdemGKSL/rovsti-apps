package dev.rovsti.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The `:shell` library manifest is empty: the app manifests declare activity, services and permissions (ARCH 7.2). */
class ShellManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml")

    private fun elements(): List<String> {
        val all =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(manifest)
                .getElementsByTagName("*")
        return (0 until all.length).map { all.item(it).nodeName }
    }

    @Test fun manifestExists() = assertTrue(manifest.absolutePath, manifest.isFile)

    @Test fun theManifestIsEmpty() = assertEquals(listOf("manifest"), elements())
}
