package dev.rosty.messages

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.transition.Tab
import dev.rosty.messages.notify.ThreadOpens
import dev.rosty.messages.schedule.ScheduleNotices
import dev.rosty.people.ui.LocalAvatarResolver
import dev.rosty.platform.PlatformEvents
import dev.rosty.shell.NavSpec
import dev.rosty.shell.RostyShellActivity
import dev.rosty.shell.shellContainer
import kotlinx.coroutines.launch

/**
 * The one activity of Rosty Messages (FQCN kept, singleTop). Edge to edge, back order, refresh rate and permission
 * refresh come from [RostyShellActivity]; this class says which tabs there are, how an intent becomes routes
 * ([messagesRouteFor]: notification taps, `sms:` links, shares, the WhatsApp hand-off) and marks the UI attached while
 * it is visible, so receivers and the push leave the notifications of an open conversation to the app (`uiAttached`).
 */
class MainActivity : RostyShellActivity<MessagesRoute>() {
    override val container: MessagesContainer get() = shellContainer() as MessagesContainer

    override val navSpec: NavSpec<MessagesRoute> = NavSpec(MessagesTabs, MessagesRoots, MessagesRoute.serializer())

    override fun routeFor(extras: Bundle?): Pair<Tab, List<MessagesRoute>>? =
        messagesRouteFor(
            IntentFacts(
                threadTag = extras?.getString(EXTRA_THREAD_TAG),
                messageId = extras?.getString(EXTRA_MESSAGE_ID),
                messageAtMs = extras?.getLong(EXTRA_MESSAGE_AT, 0L)?.takeIf { it > 0 },
            ),
        )

    override fun routeFor(intent: Intent): Pair<Tab, List<MessagesRoute>>? {
        val facts = IntentFacts.of(intent)
        // A notification tap for a conversation: if its page is up, it goes to the newest message.
        facts.threadTag?.takeIf { facts.messageId.isNullOrBlank() }?.let(ThreadOpens::opened)
        val route = messagesRouteFor(facts)
        // A link is used once: a rotation or a restart must not open the same conversation again.
        if (route != null) {
            intent.removeExtra(EXTRA_THREAD_TAG)
            intent.removeExtra(EXTRA_MESSAGE_ID)
            intent.removeExtra(EXTRA_MESSAGE_AT)
        }
        return route
    }

    override fun onResume() {
        super.onResume()
        // The role can be given or taken in system settings while the app is away (access.rs `resume_pulls`).
        container.refreshSmsRole()
    }

    override fun onStart() {
        super.onStart()
        PlatformEvents.uiAttached = true
        // Off the main thread: both read files through the Rust core, which may still be starting.
        container.scope.launch {
            container.announcer.start()
            container.schedule.start()
        }
    }

    override fun onStop() {
        PlatformEvents.uiAttached = false
        container.scope.launch { container.store.flush() }
        super.onStop()
    }

    @Composable
    override fun Content() {
        // Every avatar goes through the resolver (own photo, else the WhatsApp picture, else initials; X.1c).
        val overlays = remember { OverlayController() }
        CompositionLocalProvider(LocalAvatarResolver provides container.avatarResolver) {
            MessagesApp(container = container, navigator = navigator, overlays = overlays)
            // A scheduled message that could not be sent says so on whatever page is up (E6.10).
            ScheduleNotices(container, overlays) { navigator.push(it) }
        }
    }
}
