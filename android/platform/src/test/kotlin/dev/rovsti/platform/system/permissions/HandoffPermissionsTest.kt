package dev.rovsti.platform.system.permissions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * E3 AC6: the `uses-permission` lines, the HANDOFF `<permission>` declarations and the components it protects are
 * exactly those of the GPUI manifests. The committed `manifest/<app>.permissions.txt` files are also the expected sets
 * for each Kotlin app's merged-manifest test (E4.1, E5.1, E6.1, E7.1).
 */
class HandoffPermissionsTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val apps = listOf("phone", "messages", "contacts", "stops")

    private fun committed(app: String) =
        File("src/test/resources/manifest/$app.permissions.txt").readLines().filter { it.isNotBlank() }

    /** What the Kotlin apps added to the GPUI manifests (the GPUI app is deprecated and no longer changes). */
    private val addedSinceGpui =
        mapOf(
            "phone" to
                listOf(
                    "uses-permission android.permission.FOREGROUND_SERVICE_MICROPHONE",
                    // E5.12: BootReceiver re-arms the call-back reminders; they are exact while this is granted.
                    "uses-permission android.permission.RECEIVE_BOOT_COMPLETED",
                    "uses-permission android.permission.SCHEDULE_EXACT_ALARM",
                ),
        )

    private fun fromManifest(app: String): List<String> {
        val file = File("../../apps/$app/platforms/android/app/src/main/AndroidManifest.xml")
        val doc =
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(
                file,
            )
        val all = doc.getElementsByTagName("*")
        val elements = (0 until all.length).map { all.item(it) as Element }

        fun Element.attr(name: String) = getAttributeNS(ns, name)
        val uses =
            elements
                .filter { it.tagName == "uses-permission" }
                .map {
                    "uses-permission ${it.attr(
                        "name",
                    )}"
                }.sorted()
        val declares =
            elements
                .filter { it.tagName == "permission" }
                .map { "declares ${it.attr("name")} ${it.attr("protectionLevel")}" }
                .sorted()
        val protects =
            elements
                .filter { it.attr("permission") == HANDOFF }
                .map { "protects ${it.tagName} ${it.attr("name")} $HANDOFF" }
                .sorted()
        return (uses + addedSinceGpui[app].orEmpty()).sorted() + declares + protects
    }

    @Test fun everyAppManifestEqualsItsCommittedList() {
        for (app in apps) assertEquals(app, committed(app), fromManifest(app))
    }

    @Test fun gpuiforgePermissionsAreAllInTheList() {
        for (app in apps) {
            val json = JSONObject(File("../../apps/$app/gpuiforge.json").readText())
            val forge = mutableSetOf<String>()

            fun walk(o: Any?) {
                when (o) {
                    is JSONObject -> {
                        o.keys().forEach { k ->
                            if (k ==
                                "permissions"
                            ) {
                                o.getJSONArray(k).let { a ->
                                    for (i in 0 until a.length()) {
                                        forge +=
                                            a.getString(i)
                                    }
                                }
                            } else {
                                walk(o.get(k))
                            }
                        }
                    }

                    is org.json.JSONArray -> {
                        for (i in 0 until o.length()) walk(o.get(i))
                    }
                }
            }
            walk(json)
            assertTrue("$app: gpuiforge.json declares no permissions", forge.isNotEmpty())
            val lines = committed(app).toSet()
            assertEquals(app, emptySet<String>(), forge.map { "uses-permission $it" }.toSet() - lines)
        }
    }

    @Test fun handoffIsDeclaredByContactsMessagesAndPhoneOnly() {
        val declaring = apps.filter { app -> committed(app).any { it.startsWith("declares $HANDOFF ") } }
        assertEquals(
            listOf("phone", "messages", "contacts"),
            declaring.sortedBy {
                listOf("phone", "messages", "contacts").indexOf(it)
            },
        )
        assertTrue(committed("stops").none { it.contains(HANDOFF) })
    }

    @Test fun handoffIsASignaturePermissionAndTheKotlinConstantMatches() {
        assertEquals(HANDOFF, dev.rovsti.platform.system.Handoff.PERMISSION)
        for (app in listOf("phone", "messages", "contacts")) {
            assertTrue(
                app,
                "declares $HANDOFF signature" in committed(app),
            )
        }
    }

    @Test fun phoneAndMessagesProtectTheirWaHandoffAlias() {
        for (app in listOf("phone", "messages")) {
            assertTrue(
                app,
                "protects activity-alias ${dev.rovsti.platform.system.Handoff.ALIAS_SUFFIX} $HANDOFF" in committed(app),
            )
        }
        // Contacts hands off but receives nothing; Stops does neither.
        assertTrue(committed("contacts").none { it.startsWith("protects ") })
        assertTrue("uses-permission $HANDOFF" in committed("contacts"))
    }

    private companion object {
        const val HANDOFF = "dev.rovsti.permission.HANDOFF"
    }
}
