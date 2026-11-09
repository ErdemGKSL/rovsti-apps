package dev.rovsti.contacts.card

import android.net.Uri
import dev.rovsti.contacts.photo.PhotoMenuActions
import dev.rovsti.contacts.photo.PhotoViewerActions
import dev.rovsti.contacts.photo.fullPhotoUri
import dev.rovsti.design.media.ViewerPhoto
import dev.rovsti.design.media.showPhotoViewer
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.platform.files.FilesRepository
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope

/**
 * The photo viewer of the card (E4.12): the thumbnail the avatar already shows, then the full photo, flying out of the
 * avatar rectangle on the 500 ms shared-artwork spec. The toolbar has Save, Share and an Edit menu ([menu]: Choose Photo,
 * Take Photo, Use WhatsApp Photo when [jid] knows the contact, Remove Photo after a question). [onOpened] gets the open
 * viewer so the page can close it once the photo has changed.
 */
fun cardPhotoViewer(
    id: Long,
    files: FilesRepository,
    scope: CoroutineScope,
    overlays: () -> OverlayController,
    jid: () -> String?,
    menu: PhotoMenuActions,
    share: (Uri, String, String) -> Unit,
    onOpened: (OverlayEntry) -> Unit = {},
): PhotoViewerLauncher =
    PhotoViewerLauncher { header, anchorFor ->
        val actions =
            PhotoViewerActions(
                contactName = header.name,
                sources = listOfNotNull(fullPhotoUri(id), header.photoUri?.let(Uri::parse)),
                files = files,
                scope = scope,
                say = { overlays().showToast(it) },
                share = { uri, mime -> share(uri, mime, header.name) },
            )
        val edit =
            actions.editMenu(jid(), menu) { onConfirm ->
                overlays().present(
                    AlertEntry(
                        heading = "Remove photo?",
                        message = "${header.name} will have no photo on this phone.",
                        actions =
                            persistentListOf(
                                AlertAction("Cancel", AlertRole.Cancel),
                                AlertAction("Remove Photo", AlertRole.Destructive, onConfirm),
                            ),
                    ),
                )
            }
        onOpened(
            overlays().showPhotoViewer(
                photos = persistentListOf(ViewerPhoto(full = fullPhotoUri(id), thumb = header.photoUri)),
                initialPage = 0,
                actions = actions.viewerActions(edit),
                anchorFor = { anchorFor() },
            ),
        )
    }
