package dev.rovsti.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.SaverScope
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.media.LocalRovstiImageLoader
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.permissions.LocalPermissionController
import dev.rovsti.shell.permissions.LocalSpecialAccess
import kotlinx.serialization.KSerializer

/** The tabs of an app, the root route of each, and how routes are saved: what a [TabNavigator] is made of. */
class NavSpec<R : Any>(
    val tabs: List<Tab>,
    val roots: Map<Tab, R>,
    val serializer: KSerializer<R>,
) {
    fun create(): TabNavigator<R> = TabNavigator(tabs, roots)

    internal val saver = TabNavigator.saver(tabs, roots, serializer)
}

/**
 * The one activity of an app (ARCH 5.4). It does the things every app must do the same way, and leaves the screens to
 * [Content]:
 *
 * - edge to edge, drawn under both system bars. The app manifest sets `android:enableOnBackInvokedCallback="true"` (so
 *   predictive back works) and `android:windowSoftInputMode="adjustResize"`; the back order is the overlays, then the
 *   tab stack, then this activity (`RovstiNavHost`);
 * - the highest refresh rate at the current resolution ([preferHighestRefreshRate]);
 * - `onResume` is the only place that calls `permissions.refresh`;
 * - `onCreate` (first start only; a restart restores the saved stacks) and `onNewIntent` turn the intent extras into
 *   routes with [routeFor] and call `navigator.resetTo` (the replacement of the old `Mailbox`, ARCH 7.3).
 */
abstract class RovstiShellActivity<R : Any> : ComponentActivity() {
    /** The app's container; by default the one of the `Application` (see [HasContainer]). */
    protected open val container: ShellContainer get() = shellContainer()

    protected abstract val navSpec: NavSpec<R>

    /**
     * The deep link carried by [extras] (`open_contact`, `answer_call_id`, ...), or null to leave the stacks alone. A
     * pure function: it neither reads the intent nor touches the navigator, so it is tested on the JVM.
     */
    protected abstract fun routeFor(extras: Bundle?): Pair<Tab, List<R>>?

    /**
     * The same for a whole intent. The default looks at the extras only; an app whose deep links also carry an action
     * or data (a vCard SEND or VIEW) overrides this and keeps its decision in a pure function.
     */
    protected open fun routeFor(intent: Intent): Pair<Tab, List<R>>? = routeFor(intent.extras)

    /** The navigator of this activity; it exists from `onCreate` on. */
    protected lateinit var navigator: TabNavigator<R>
        private set

    /** The app's screens: usually one [RovstiShell] with its entries. Runs inside the theme and the shell locals. */
    @Composable
    protected abstract fun Content()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.preferHighestRefreshRate(display)
        container.haptics.attach(window.decorView)
        navigator = restoreNavigator(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        setContent {
            RovstiTheme {
                CompositionLocalProvider(
                    LocalRovstiHaptics provides container.haptics,
                    LocalRovstiImageLoader provides container.imageLoader,
                    LocalPermissionController provides container.permissions,
                    LocalSpecialAccess provides container.specialAccess,
                ) {
                    Content()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onResume() {
        super.onResume()
        container.permissions.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val scope = SaverScope { true }
        with(navSpec.saver) { scope.save(navigator) }?.let { outState.putString(STATE_NAV, it) }
    }

    override fun onDestroy() {
        container.haptics.detach()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.trimMemory(level)
    }

    private fun route(intent: Intent?) {
        val (tab, routes) = intent?.let(::routeFor) ?: return
        navigator.resetTo(tab, routes)
    }

    private fun restoreNavigator(saved: Bundle?): TabNavigator<R> {
        val text = saved?.getString(STATE_NAV) ?: return navSpec.create()
        return try {
            navSpec.saver.restore(text) ?: navSpec.create()
        } catch (_: IllegalArgumentException) {
            // A route class that no longer exists (an update between save and restore) starts the app fresh.
            navSpec.create()
        }
    }

    private companion object {
        const val STATE_NAV = "rovsti.shell.nav"
    }
}
