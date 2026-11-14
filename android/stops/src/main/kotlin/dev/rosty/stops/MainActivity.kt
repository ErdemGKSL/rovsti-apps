package dev.rosty.stops

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import dev.rosty.design.transition.Tab
import dev.rosty.platform.contacts.Shortcuts
import dev.rosty.shell.NavSpec
import dev.rosty.shell.RostyShellActivity
import dev.rosty.shell.shellContainer

/**
 * The one activity of Rosty Stops (FQCN kept, singleTop). Edge to edge, back order, refresh rate and permission
 * refresh come from [RostyShellActivity]; this class only says what the stack is and how an intent becomes a link for
 * the map: a stop shortcut, a `geo:` URI, a maps link or shared text ([stopsLinkFor]).
 */
class MainActivity : RostyShellActivity<StopsRoute>() {
    override val container: StopsContainer get() = shellContainer() as StopsContainer

    override val navSpec: NavSpec<StopsRoute> = NavSpec(StopsTabs, StopsRoots, StopsRoute.serializer())

    override fun routeFor(extras: Bundle?): Pair<Tab, List<StopsRoute>>? = stopsRouteFor(null, null, extras)

    override fun routeFor(intent: Intent): Pair<Tab, List<StopsRoute>>? {
        val route = stopsRouteFor(intent.action, intent.dataString, intent.extras) ?: return null
        container.pendingLink.value = stopsLinkFor(intent.action, intent.dataString, intent.extras)
        // A link is used once: a rotation or a restart must not open the same stop again (as the GPUI deliverOpenStop).
        intent.removeExtra(Shortcuts.EXTRA_STOP_ID)
        return route
    }

    @Composable
    override fun Content() {
        StopsApp(container = container, navigator = navigator)
    }
}
