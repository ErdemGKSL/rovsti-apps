package dev.rovsti.platform.system.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Each app's table equals its ARCH 7.5 row and the committed manifest list (`HandoffPermissionsTest` pins the latter). */
class AppPermissionsTest {
    private val arch = File("../../docs/kotlin-ui/architecture.md").readLines()

    /** The 7.5 rows: app name to the cells `[roles, runtime, special, installTime]`. */
    private val rows: Map<String, List<String>> by lazy {
        val start = arch.indexOfFirst { it.startsWith("### 7.5 ") }
        assertTrue("no 7.5 section", start >= 0)
        arch
            .drop(start)
            .takeWhile { !it.startsWith("Rules the code follows") }
            .filter {
                it.startsWith("| Phone") || it.startsWith("| Messages") || it.startsWith("| Contacts") ||
                    it.startsWith("| Stops")
            }.associate { line ->
                val cells =
                    line
                        .trim()
                        .trim('|')
                        .split('|')
                        .map { it.trim() }
                cells[0].lowercase() to cells.drop(1)
            }
    }

    private fun tokens(cell: String) = Regex("`([A-Z][A-Z_]+)`").findAll(cell).map { it.groupValues[1] }.toSet()

    private fun full(p: String) = if (p.startsWith("android.") || p.startsWith("dev.")) p else "android.permission.$p"

    /** Tokens in the runtime cell that are not permissions: the camera is reached by an intent, not a grant. */
    private val notPermissions = setOf("ACTION_IMAGE_CAPTURE", "CAMERA")

    @Test fun fourAppsHaveFourRows() {
        assertEquals(setOf("phone", "messages", "contacts", "stops"), rows.keys)
        assertEquals(rows.keys, AppPermissions.all.map { it.app }.toSet())
    }

    @Test fun runtimeSetEqualsArchRowByRow() {
        for (app in AppPermissions.all) {
            val expected = (tokens(rows.getValue(app.app)[1]) - notPermissions).map(::full).toSet()
            assertEquals(app.app, expected, app.runtime)
        }
    }

    @Test fun rolesEqualArchRoles() {
        for (app in AppPermissions.all) {
            val expected = tokens(rows.getValue(app.app)[0]).map { "android.app.role.${it.removePrefix("ROLE_")}" }
            assertEquals(app.app, expected, app.roles)
        }
    }

    @Test fun installTimeEqualsArchRow() {
        for (app in AppPermissions.all) {
            val expected = tokens(rows.getValue(app.app)[3]).map(::full).toSet()
            assertEquals(app.app, expected, app.installTime)
        }
    }

    @Test fun theManifestListIsRuntimePlusInstallPlusSpecial() {
        for (app in AppPermissions.all) {
            val committed =
                File("src/test/resources/manifest/${app.app}.permissions.txt")
                    .readLines()
                    .filter { it.startsWith("uses-permission ") }
                    .map { it.removePrefix("uses-permission ") }
                    .toSet()
            val handoff = if (app.handoffUses) setOf("dev.rovsti.permission.HANDOFF") else emptySet()
            assertEquals(app.app, committed, app.manifestPermissions + handoff)
        }
    }

    @Test fun everyGroupPermissionIsDeclaredRuntime() {
        for (app in AppPermissions.all) {
            val empty = app.groups.filter { app.permissionsOf(it).isEmpty() }
            assertEquals(
                "${app.app}: a group the app shows holds no declared permission",
                emptyList<PermissionGroup>(),
                empty,
            )
        }
    }

    @Test fun messagesReadsContactsButNeverWritesThem() {
        assertEquals(
            listOf("android.permission.READ_CONTACTS"),
            AppPermissions.messages.permissionsOf(PermissionGroup.CONTACTS),
        )
        assertEquals(2, AppPermissions.phone.permissionsOf(PermissionGroup.CONTACTS).size)
    }

    @Test fun locationKeepsFineAndCoarseTogetherAndBackgroundApart() {
        assertEquals(2, PermissionGroup.LOCATION.permissions.size)
        assertEquals(
            listOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
            PermissionGroup.BACKGROUND_LOCATION.permissions,
        )
    }

    @Test fun groupIdsAreTodaysWireKeys() {
        assertEquals(
            listOf(
                "phone",
                "contacts",
                "notifications",
                "call_log",
                "bluetooth",
                "location",
                "background_location",
                "microphone",
            ),
            PermissionGroup.entries.map { it.id },
        )
        assertEquals("asked_call_log", PermissionGroup.askedKey(PermissionGroup.CALL_LOG))
    }

    @Test fun onlyPhoneAndMessagesHoldARole() {
        assertEquals(listOf("android.app.role.DIALER"), AppPermissions.phone.roles)
        assertEquals(listOf("android.app.role.SMS"), AppPermissions.messages.roles)
        assertTrue(AppPermissions.contacts.roles.isEmpty() && AppPermissions.stops.roles.isEmpty())
    }
}
