package dev.rovsti.shell.permissions

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.platform.PlatformPrefs
import dev.rovsti.platform.system.FakeSpecialAccess
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionController
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.testing.ShotTest
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The E3.11 screenshots of the permission UI, light and dark. */
@Config(sdk = [35])
class E311ShotTest : ShotTest() {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences(PlatformPrefs.BRIDGE, Context.MODE_PRIVATE)
    private val special = FakeSpecialAccess()
    private lateinit var controller: PermissionController
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    @Before fun setUp() {
        prefs.edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        controller = PermissionController(app, AppPermissions.contacts, special, prefs)
    }

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        modes: List<Boolean> = listOf(false, true),
    ) {
        for (dark in modes) {
            composeFrame(dark = dark, fontScale = fontScale) {
                GatedPage(PermissionGroup.CONTACTS, controller, special, RecordingRegistry(app))
            }
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun rationaleSheetOverAListPage() {
        controller.refreshNow { false }
        snap("e3.11-permission-rationale")
    }

    @Test fun deniedRowInASetupSection() {
        prefs.edit().putBoolean("asked_contacts", true).commit()
        controller.refreshNow { false }
        snap("e3.11-permission-denied-row")
    }
}
