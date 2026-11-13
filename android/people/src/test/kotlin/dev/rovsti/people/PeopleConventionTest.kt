package dev.rovsti.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-scanning rules of the module (X.1a: no Rust in static init; owner fixes b and d). */
class PeopleConventionTest {
    private val main = File("src/main/kotlin")

    private fun sources() = main.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun theModuleNeverTouchesRustNative() {
        assertTrue(sources().isNotEmpty())
        // The index and the sources are plain Kotlin: a Rust call would have to wait for Rust.init, so none is made here.
        assertEquals(emptyList<String>(), sources().filter { "RustNative" in it.readText() }.map { it.name })
    }

    @Test fun noObjectOrCompanionRunsCodeAtLoad() {
        // Static initializers would run on whatever thread first touches the class (and could load the native library).
        for (file in sources()) {
            val text = file.readText()
            assertTrue("${file.name} has an init block", Regex("""(?m)^\s*init\s*\{""").containsMatchIn(text).not())
        }
    }

    @Test fun theManifestHoldsNoComponent() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (tag in listOf("<activity", "<service", "<receiver")) assertTrue(tag, tag !in manifest)
        assertTrue("<provider android:authorities=\"${PeopleContract.AUTHORITY}\"" in manifest)
        assertTrue(PeopleContract.PERMISSION in manifest)
    }
}
