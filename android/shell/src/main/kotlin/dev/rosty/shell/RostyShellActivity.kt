package dev.rosty.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaverScope
import androidx.fragment.app.FragmentActivity
import dev.rosty.core.model.phone.HapticsLevel
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.media.LocalRostyImageLoader
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.ThemeMelt
import dev.rosty.design.transition.Tab
import dev.rosty.design.transition.TabNavigator
import dev.rosty.shell.permissions.LocalPermissionController
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.shell.settings.dev.LocalDevMode
import dev.rosty.shell.settings.rememberDarkTheme
import dev.rosty.shell.settings.toMode
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
 *   tab stack, then this activity (`RostyNavHost`);
 * - the highest refresh rate at the current resolution ([preferHighestRefreshRate]);
 * - `onResume` is the only place that calls `permissions.refresh`;
 * - `onCreate` (first start only; a restart restores the saved stacks) and `onNewIntent` turn the intent extras into
 *   routes with [routeFor] and call `navigator.resetTo` (the replacement of the old `Mailbox`, ARCH 7.3);
 * - the WhatsApp vault: the activity is the window of its system prompts (a `FragmentActivity`, as `BiometricPrompt`
 *   needs) and reports its starts and stops to the container's lock timer (`plans/wa-vault.md` 6).
 */
abstract class RostyShellActivity<R : Any> : FragmentActivity() {
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

    /** The app's screens: usually one [RostyShell] with its entries. Runs inside the theme and the shell locals. */
    @Composable
    protected abstract fun Content()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.preferHighestRefreshRate(display)
        container.haptics.attach(window.decorView)
        container.vaultPromptHost.attach(this)
        navigator = restoreNavigator(savedInstanceState)
        if (savedInstanceState == null) route(intent)
        setContent {
            val settings = container.commonSettings
            val dark = settings.rememberDarkTheme()
            // The window's bar icons follow the app's theme, not the system's, when the person forced one.
            LaunchedEffect(dark) {
                val bars =
                    SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { dark }
                enableEdgeToEdge(bars, bars)
            }
            val level by settings.haptics.collectAsState()
            SideEffect { container.haptics.mode = (level ?: HapticsLevel.Light).toMode() }
            RostyTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalRostyHaptics provides container.haptics,
                    LocalRostyImageLoader provides container.imageLoader,
                    LocalPermissionController provides container.permissions,
                    LocalSpecialAccess provides container.specialAccess,
                    LocalDevMode provides container.devLog.devMode,
                ) {
                    ThemeMelt(dark) { Content() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onStart() {
        super.onStart()
        container.vaultUse.onActivityStarted()
        container.migratePlaintextSoon()
    }

    override fun onResume() {
        super.onResume()
        container.vaultPromptHost.attach(this)
        container.permissions.refresh(this)
    }

    override fun onStop() {
        container.vaultUse.onActivityStopped(isChangingConfigurations)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val scope = SaverScope { true }
        with(navSpec.saver) { scope.save(navigator) }?.let { outState.putString(STATE_NAV, it) }
    }

    override fun onDestroy() {
        container.haptics.detach()
        container.vaultPromptHost.detach(this)
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
        const val STATE_NAV = "rosty.shell.nav"
    }
}
