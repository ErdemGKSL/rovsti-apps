package dev.rosty.messages.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rosty.platform.PlatformEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * The conversation on screen (`sms:<thread>` or `wa:<jid>`), for the `uiAttached` rule: while the UI is attached, the
 * open conversation gets no notification (GPUI `notifications::should_notify`). Process-wide, so the receivers and the
 * push service read it without a container.
 */
object OpenConversation {
    @Volatile private var open: String? = null

    /** The tag of the conversation in front of the user, or null. */
    val tag: String? get() = open

    fun enter(tag: String) {
        open = tag
    }

    /** Leaves [tag] only when it is still the open one (the next page may have entered already). */
    fun leave(tag: String) {
        if (open == tag) open = null
    }

    /** The open conversation [from] is [into] from now on (its chat merged into another one). */
    fun rekey(
        from: String,
        into: String,
    ) {
        if (open == from) open = into
    }

    /** True while the user is looking at [tag]: the UI is attached and that conversation is open. */
    fun isShowing(tag: String): Boolean = PlatformEvents.uiAttached && open == tag
}

/**
 * Notification taps that open a conversation (`sms:<thread>` or `wa:<jid>`) without naming a message. A page that is
 * already up for that conversation goes to the newest message; a page that opens for it misses the tap on purpose
 * (opening at the unread messages is its own rule).
 */
object ThreadOpens {
    private val taps = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun opened(tag: String) {
        taps.tryEmit(tag)
    }

    /** The taps for [tag]. */
    fun of(tag: String): Flow<Unit> = taps.filter { it == tag }.map { }
}

/**
 * Marks [tag] as the open conversation while the page is resumed, and takes its notification away when it opens
 * (GPUI `dismiss_notification`). A conversation not created yet (`sms:0`) has no notification to dismiss.
 */
@Composable
fun TrackOpenConversation(tag: String) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(tag, lifecycle) {
        fun sync() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                OpenConversation.enter(tag)
                if (tag != NEW_SMS_TAG) MessagesNotifier.dismiss(context, tag)
            } else {
                OpenConversation.leave(tag)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        onDispose {
            lifecycle.removeObserver(observer)
            OpenConversation.leave(tag)
        }
    }
}

private const val NEW_SMS_TAG = "sms:0"
