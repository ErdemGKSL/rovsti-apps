package dev.rosty.contacts.photo

import android.content.Context
import androidx.compose.runtime.Composable
import dev.rosty.contacts.R
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.text.UiText
import dev.rosty.design.text.resolve
import dev.rosty.design.text.text
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
    context: Context,
    hasPhoto: Boolean,
    jid: String?,
    actions: PhotoMenuActions,
): ImmutableList<MenuItem> {
    val items = ArrayList<MenuItem>()
    items += MenuItem.Action(CHOOSE.resolve(context), RostyIcons.Photo, onClick = actions.choose)
    items += MenuItem.Action(TAKE.resolve(context), RostyIcons.Camera, onClick = actions.take)
    if (!jid.isNullOrBlank()) {
        items +=
            MenuItem.Action(WHATSAPP.resolve(context), RostyIcons.WhatsApp, onClick = actions.whatsApp)
    }
    if (hasPhoto) {
        items += MenuItem.Separator
        items +=
            MenuItem.Action(REMOVE.resolve(context), RostyIcons.Trash, destructive = true, onClick = actions.remove)
    }
    return persistentListOf<MenuItem>().addAll(items)
}

val CHOOSE: UiText = UiText.Res(R.string.contacts_choose_photo)
val TAKE: UiText = UiText.Res(R.string.contacts_take_photo)
val WHATSAPP: UiText = UiText.Res(R.string.contacts_use_whatsapp_photo)
val REMOVE: UiText = UiText.Res(R.string.contacts_remove_photo)
