package dev.rosty.messages.settings.notify

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.ops.MessagesOps
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.notify.NotifyPolicy
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A muted conversation on the Notifications page: its tag (`sms:7`, `wa:<jid>`) and its name, when known. */
@Immutable
data class MutedRow(
    val tag: String,
    val title: String?,
)

@Immutable
data class NotificationSettingsUi(
    val loaded: Boolean = false,
    /** The master switch of the app's message notifications (E6.9 reads it through [NotifyPolicy]). */
    val notifications: Boolean = true,
    /** "Copy code" on passcode notifications and bubbles. */
    val otpCopy: Boolean = true,
    val muted: ImmutableList<MutedRow> = persistentListOf(),
)

sealed interface NotificationSettingsAction {
    data class SetNotifications(
        val on: Boolean,
    ) : NotificationSettingsAction

    data class SetOtpCopy(
        val on: Boolean,
    ) : NotificationSettingsAction

    data class Unmute(
        val tag: String,
    ) : NotificationSettingsAction
}

/**
 * Settings, Notifications (GPUI `settings_ui::notifications`): the switches the notifier follows (E6.9) and the
 * conversations that are muted now, each with Unmute (`messages.prefs_edit` "unmute"). A timed mute that has run out is
 * not listed ([NotifyPolicy.isMuted]). [nameOf] names a tag (the contact or chat), null when it cannot.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModel(
    private val store: MessagesStore,
    private val ops: MessagesOps,
    private val nameOf: suspend (String) -> String?,
    private val now: () -> Long,
) : ViewModel() {
    val state: StateFlow<NotificationSettingsUi> =
        store.prefs
            .mapLatest { prefs -> prefs?.let { build(it) } ?: NotificationSettingsUi() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettingsUi())

    init {
        viewModelScope.launch { store.load() }
    }

    private suspend fun build(prefs: MessagesPrefs): NotificationSettingsUi {
        val policy = NotifyPolicy.of(prefs)
        val at = now()
        val muted = prefs.muted.filter { policy.isMuted(it, at) }.map { MutedRow(it, nameOf(it)) }
        return NotificationSettingsUi(true, prefs.notifications, prefs.otpCopy, muted.toImmutableList())
    }

    fun onAction(action: NotificationSettingsAction) {
        viewModelScope.launch {
            when (action) {
                is NotificationSettingsAction.SetNotifications -> {
                    store.updatePrefs { it.copy(notifications = action.on) }
                }

                is NotificationSettingsAction.SetOtpCopy -> {
                    store.updatePrefs { it.copy(otpCopy = action.on) }
                }

                is NotificationSettingsAction.Unmute -> {
                    store.load()
                    val prefs = store.prefs.value ?: return@launch
                    val answer = ops.prefsEdit(PrefsEditRequest(prefs, "unmute", tag = action.tag)) as? Outcome.Ok
                    answer?.value?.takeIf { it.changed }?.let { edited -> store.updatePrefs { edited.prefs } }
                }
            }
        }
    }
}
