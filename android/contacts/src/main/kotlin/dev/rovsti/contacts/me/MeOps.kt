package dev.rovsti.contacts.me

import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.MeStateAnswer
import dev.rovsti.core.model.contacts.MeStateRequest
import dev.rovsti.core.model.contacts.MeToggleRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.text.UiText

/** The sentence to show after a change and whether the change was made (a refusal such as "Up to 5" is not). */
data class MeResult(
    val notice: UiText,
    val done: Boolean,
)

/**
 * My card and the emergency list over the stored preferences (E4.14). The rules and every sentence are Rust's
 * (`contacts.me_state`, `contacts.me_toggle`); this class only orders the calls and writes the answer to the
 * preferences. The card page, the list header and the Emergency Contacts page all go through it, so a change made on
 * one is the same change everywhere.
 */
class MeOps(
    private val store: ContactsStore,
    private val ops: ContactsOps,
) {
    /** The state of both features for [all], or null if the core fails. */
    suspend fun state(all: List<WireContact>): MeStateAnswer? {
        store.load()
        val request = MeStateRequest(store.prefs.value.myCard, all, store.emergency.value)
        return (ops.meState(request) as? Outcome.Ok)?.value
    }

    /**
     * Makes [contact] My Card ([on]) or clears it. Answers the sentence to show: Rust's "set" or "removed" notice, or
     * [CANT_SAVE] when the preferences could not be written, or [NO_KEY] for a contact the provider gave no lookup
     * key (it could not be found again); [MeResult.done] says whether anything changed.
     */
    suspend fun setMyCard(
        contact: WireContact,
        on: Boolean,
        all: List<WireContact>,
    ): MeResult {
        val key = contact.lookupKey?.takeIf { it.isNotEmpty() }
        if (on && key == null) return MeResult(NO_KEY, false)
        val texts = state(all) ?: return MeResult(CANT_SAVE, false)
        val saved = store.update { it.copy(myCard = if (on) key else null) }
        if (saved is Outcome.Err) return MeResult(CANT_SAVE, false)
        return MeResult(UiText.Literal(if (on) texts.myCardSetNotice else texts.myCardRemovedNotice), true)
    }

    /**
     * Adds [contact] to the emergency list or takes it out. Answers Rust's sentence ("Added to emergency contacts",
     * "Up to 5 emergency contacts", ...); the list is only written when the toggle was allowed.
     */
    suspend fun toggleEmergency(contact: WireContact): MeResult {
        store.load()
        val answer =
            (ops.meToggle(MeToggleRequest(store.emergency.value, contact)) as? Outcome.Ok)?.value
                ?: return MeResult(CANT_SAVE, false)
        if (answer.added == null) return MeResult(UiText.Literal(answer.notice), false)
        val saved = store.update { it.copy(emergency = answer.keys) }
        if (saved is Outcome.Err) return MeResult(CANT_SAVE, false)
        return MeResult(UiText.Literal(answer.notice), true)
    }

    companion object {
        val CANT_SAVE: UiText = UiText.Res(R.string.contacts_cant_save_right_now)
        val NO_KEY: UiText = UiText.Res(R.string.contacts_cant_be_my_card)
    }
}
