package dev.rosty.contacts.card

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import dev.rosty.contacts.R
import dev.rosty.contacts.photo.PhotoMenuActions
import dev.rosty.contacts.photo.PhotoViewerActions
import dev.rosty.contacts.photo.fullPhotoUri
import dev.rosty.design.media.ViewerPhoto
import dev.rosty.design.media.showPhotoViewer
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.UiText
import dev.rosty.design.text.resolve
import dev.rosty.people.PersonAvatar
import dev.rosty.platform.files.FilesRepository
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope

/**
 * The photo viewer of the card (E4.12): the thumbnail the avatar already shows, then the full photo, flying out of the
 * avatar rectangle on the 500 ms shared-artwork spec. The toolbar has Save, Share and an Edit menu ([menu]: Choose Photo,
 * Take Photo, Use WhatsApp Photo when [jid] knows the contact, Remove Photo after a question). [onOpened] gets the open
 * viewer so the page can close it once the photo has changed.
 */
fun cardPhotoViewer(
    context: Context,
    id: Long,
    files: FilesRepository,
    scope: CoroutineScope,
    overlays: () -> OverlayController,
    jid: () -> String?,
    menu: PhotoMenuActions,
    share: (Uri, String, String) -> Unit,
    onOpened: (OverlayEntry) -> Unit = {},
    /** The full-size WhatsApp picture of a JID and picture id (S12); null keeps the cached one. */
    fullPicture: suspend (jid: String, pictureId: String) -> Any? = { _, _ -> null },
): PhotoViewerLauncher =
    object : PhotoViewerLauncher {
        override fun openWhatsApp(
            header: CardHeader,
            picture: PersonAvatar.WhatsApp,
            anchorFor: () -> Rect?,
        ): OverlayEntry {
            // Read-only (X.1): Save and Share the cached picture, never the Edit menu of the contact's photo.
            val actions =
                PhotoViewerActions(
                    contactName = header.name,
                    sources = listOf(Uri.parse(picture.uri)),
                    files = files,
                    scope = scope,
                    say = { overlays().showToast(it.resolve(context)) },
                    share = { uri, mime -> share(uri, mime, header.name) },
                )
            val jid = picture.jid
            val upgrade: (suspend () -> Any?)? = jid?.let { { fullPicture(it, picture.pictureId) } }
            return overlays().showPhotoViewer(
                photos = persistentListOf(whatsAppViewerPhoto(picture, upgrade)),
                initialPage = 0,
                actions = actions.readOnlyActions(context),
                anchorFor = { anchorFor() },
            )
        }

        override fun open(
            header: CardHeader,
            anchorFor: () -> Rect?,
        ) = openOwn(header, anchorFor)

        private fun openOwn(
            header: CardHeader,
            anchorFor: () -> Rect?,
        ) {
            val actions =
                PhotoViewerActions(
                    contactName = header.name,
                    sources = listOfNotNull(fullPhotoUri(id), header.photoUri?.let(Uri::parse)),
                    files = files,
                    scope = scope,
                    say = { overlays().showToast(it.resolve(context)) },
                    share = { uri, mime -> share(uri, mime, header.name) },
                )
            val edit =
                actions.editMenu(context, jid(), menu) { onConfirm ->
                    overlays().present(
                        AlertEntry(
                            heading = context.getString(R.string.contacts_remove_photo_question),
                            message = context.getString(R.string.contacts_photo_remove_message, header.name),
                            actions =
                                persistentListOf(
                                    AlertAction(context.getString(R.string.contacts_cancel), AlertRole.Cancel),
                                    AlertAction(
                                        context.getString(R.string.contacts_remove_photo),
                                        AlertRole.Destructive,
                                        onConfirm,
                                    ),
                                ),
                        ),
                    )
                }
            onOpened(
                overlays().showPhotoViewer(
                    photos =
                        persistentListOf(
                            ViewerPhoto(full = fullPhotoUri(id), thumb = header.photoUri, circular = true),
                        ),
                    initialPage = 0,
                    actions = actions.viewerActions(context, edit),
                    anchorFor = { anchorFor() },
                ),
            )
        }
    }
