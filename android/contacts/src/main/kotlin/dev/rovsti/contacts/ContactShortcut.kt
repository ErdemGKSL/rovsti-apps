package dev.rovsti.contacts

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.PinShortcutRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ShortcutsRepository

/**
 * "Add to Home screen" for one contact, the single path the row menu and the card menu share: `contacts.pin_shortcut`
 * decides the shortcut, the launcher is asked to pin it. Returns the toast to show, or null when the op failed.
 */
internal suspend fun pinContactToHome(
    ops: ContactsOps,
    shortcuts: ShortcutsRepository,
    id: Long,
    lookupKey: String?,
    name: String,
): UiText? {
    val spec = (ops.pinShortcut(PinShortcutRequest(id, lookupKey, name)) as? Outcome.Ok)?.value ?: return null
    return if (shortcuts.pinContact(
            spec.shortcut,
        )
    ) {
        UiText.Res(R.string.contacts_added_to_home_screen)
    } else {
        UiText.Res(R.string.contacts_launcher_no_shortcuts)
    }
}
