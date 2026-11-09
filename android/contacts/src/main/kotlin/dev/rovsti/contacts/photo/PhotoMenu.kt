package dev.rovsti.contacts.photo

import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.MenuItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** What the avatar menu of the editor can do. */
class PhotoMenuActions(
    val choose: () -> Unit,
    val take: () -> Unit,
    val whatsApp: () -> Unit,
    val remove: () -> Unit,
)

/**
 * The rows of the avatar menu (E4.4 AC2), pure so a test can read them: Choose Photo and Take Photo always; Use WhatsApp
 * Photo only for a contact whose WhatsApp [jid] is known (the instance is linked and the number is on WhatsApp); then,
 * after a separator, the destructive Remove Photo, only when there is a photo to remove.
 */
fun photoMenuItems(
    hasPhoto: Boolean,
    jid: String?,
    actions: PhotoMenuActions,
): ImmutableList<MenuItem> {
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(CHOOSE, RovstiIcons.Photo, onClick = actions.choose)
    items += MenuItem.Action(TAKE, RovstiIcons.Camera, onClick = actions.take)
    if (!jid.isNullOrBlank()) items += MenuItem.Action(WHATSAPP, RovstiIcons.WhatsApp, onClick = actions.whatsApp)
    if (hasPhoto) {
        items += MenuItem.Separator
        items += MenuItem.Action(REMOVE, RovstiIcons.Trash, destructive = true, onClick = actions.remove)
    }
    return persistentListOf<MenuItem>().addAll(items)
}

const val CHOOSE = "Choose Photo"
const val TAKE = "Take Photo"
const val WHATSAPP = "Use WhatsApp Photo"
const val REMOVE = "Remove Photo"
