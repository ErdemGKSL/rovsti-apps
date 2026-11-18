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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    /** The muted SMS conversations, then (round 7a) the WhatsApp chats muted in WhatsApp while the vault is open. */
    val muted: ImmutableList<MutedRow> = persistentListOf(),
    /** WhatsApp is linked but its vault is locked: one row, "Unlock to see WhatsApp chats", stands for its mutes. */
    val waLocked: Boolean = false,
)

/** A WhatsApp chat and its mute as WhatsApp keeps it (`muted_until_ms`: -1 for ever, else until then). */
@Immutable
data class WaChatMute(
    val jid: String,
    val mutedUntilMs: Long?,
)

/** What the page may know of WhatsApp's mutes (round 7a). Nothing of it is kept on disk by this page. */
@Immutable
sealed interface WaMutes {
    /** No WhatsApp in this app (not linked): nothing to list. */
    data object Hidden : WaMutes

    /** Linked, vault locked: the chats are not readable now. */
    data object Locked : WaMutes

    /** The vault is open: the chats in memory, muted or not. */
    data class Open(
        val chats: List<WaChatMute>,
    ) : WaMutes
}

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

    /** "Unlock to see WhatsApp chats": the vault's unlock (round 7a). */
    data object UnlockWhatsApp : NotificationSettingsAction
}

/**
 * Settings, Notifications (GPUI `settings_ui::notifications`): the switches the notifier follows (E6.9) and the
 * conversations that are muted now, each with Unmute. SMS mutes live in the app's preferences (`messages.prefs_edit`
 * "unmute"); a timed mute that has run out is not listed ([NotifyPolicy.isMuted]). WhatsApp mutes live in WhatsApp
 * (round 6): while the vault is open the chats muted there are listed from memory ([waMutes]) and Unmute goes through
 * the chat action ([unmuteWa]); while it is locked one row offers the unlock ([unlockWa]). [nameOf] names a tag (the
 * contact or chat), null when it cannot.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModel(
    private val store: MessagesStore,
    private val ops: MessagesOps,
    private val nameOf: suspend (String) -> String?,
    private val now: () -> Long,
    /** Moves when a name may read differently (a contact saved, a WhatsApp name arrived): the list is named again. */
    nameChanges: Flow<*> = flowOf(Unit),
    /** WhatsApp's chats and their mutes while the vault is open (round 7a). */
    waMutes: Flow<WaMutes> = flowOf(WaMutes.Hidden),
    /** Unmutes a WhatsApp chat in WhatsApp (the chat action); false when it could not. */
    private val unmuteWa: suspend (jid: String) -> Boolean = { false },
    /** Opens the vault (the system prompt, or the unlock page). */
    private val unlockWa: () -> Unit = {},
) : ViewModel() {
    val state: StateFlow<NotificationSettingsUi> =
        combine(store.prefs, nameChanges, waMutes) { prefs, _, wa -> prefs to wa }
            .mapLatest { (prefs, wa) -> prefs?.let { build(it, wa) } ?: NotificationSettingsUi() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettingsUi())

    init {
        viewModelScope.launch { store.load() }
    }

    private suspend fun build(
        prefs: MessagesPrefs,
        wa: WaMutes,
    ): NotificationSettingsUi {
        val policy = NotifyPolicy.of(prefs)
        val at = now()
        // A `wa:` entry of an earlier build is no WhatsApp mute any more (round 6): WhatsApp keeps those.
        val sms =
            prefs.muted
                .filter { !it.startsWith(WA_PREFIX) && policy.isMuted(it, at) }
                .map { MutedRow(it, nameOf(it)) }
        val whatsApp =
            (wa as? WaMutes.Open)
                ?.chats
                .orEmpty()
                .filter { it.mutedUntilMs == FOREVER || (it.mutedUntilMs ?: 0L) > at }
                .map { WA_PREFIX + it.jid }
                .distinct()
                .map { MutedRow(it, nameOf(it)) }
        return NotificationSettingsUi(
            loaded = true,
            notifications = prefs.notifications,
            otpCopy = prefs.otpCopy,
            muted = (sms + whatsApp).toImmutableList(),
            waLocked = wa == WaMutes.Locked,
        )
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
                    if (action.tag.startsWith(WA_PREFIX)) {
                        unmuteWa(action.tag.removePrefix(WA_PREFIX))
                        return@launch
                    }
                    store.load()
                    val prefs = store.prefs.value ?: return@launch
                    val answer = ops.prefsEdit(PrefsEditRequest(prefs, "unmute", tag = action.tag)) as? Outcome.Ok
                    answer?.value?.takeIf { it.changed }?.let { edited -> store.updatePrefs { edited.prefs } }
                }

                NotificationSettingsAction.UnlockWhatsApp -> {
                    unlockWa()
                }
            }
        }
    }

    private companion object {
        const val WA_PREFIX = "wa:"

        /** WhatsApp's "muted for ever". */
        const val FOREVER = -1L
    }
}
