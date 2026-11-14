package dev.rosty.stops

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.transition.TabNavigator
import dev.rosty.shell.permissions.LocalPermissionController
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.shell.settings.dev.LocalDevMode
import dev.rosty.stops.home.HomeTestMap
import org.robolectric.RuntimeEnvironment

/** The real app root ([StopsApp]) on a [TestStopsContainer] with the fixed test map. */
internal class AppFixture(
    fake: FakeStopsCore = FakeStopsCore(),
    val app: Application = RuntimeEnvironment.getApplication(),
) {
    val container = TestStopsContainer(app, fake)
    val navigator = TabNavigator(StopsTabs, StopsRoots)
    val overlays = OverlayController()
    val map = HomeTestMap()

    @Composable
    fun Content() {
        CompositionLocalProvider(
            LocalPermissionController provides container.permissions,
            LocalSpecialAccess provides container.specialAccess,
            LocalDevMode provides container.devLog.devMode,
        ) {
            StopsApp(container = container, navigator = navigator, overlays = overlays, mapLayer = map.layer)
        }
    }
}
