package dev.rovsti.contacts.photo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rovsti.contacts.R
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.media.ViewerAction
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.FilesRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The full-size picture of contact [id] (`display_photo`); the list and the card show the thumbnail of the same photo. */
fun fullPhotoUri(id: Long): Uri =
    Uri.withAppendedPath(
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
        ContactsContract.Contacts.Photo.DISPLAY_PHOTO,
    )

/**
 * What the toolbar of the card's photo viewer does (E4.12, C2/C3): Save writes the picture to Pictures/Rovsti and says so
 * in a toast, Share stages a JPEG and hands it to the share sheet, Edit opens the avatar menu of the editor. A tap on
 * Save while a save runs is ignored, so the gallery gets the picture once.
 *
 * The picture is read from the provider into the app cache first ([FilesRepository.readUri]): the full-size photo, or the
 * thumbnail when the full one cannot be read.
 */
class PhotoViewerActions(
    private val contactName: String,
    private val sources: List<Uri>,
    private val files: FilesRepository,
    private val scope: CoroutineScope,
    private val say: (UiText) -> Unit,
    private val share: (Uri, String) -> Unit,
) {
    private var saving = false

    /** The name of the image file: the contact's name, then `.jpg`. */
    val fileName: String
        get() {
            val base = contactName.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
            return (base.ifEmpty { "contact" }) + ".jpg"
        }

    private suspend fun fetch(): FileResult.Ok? {
        for (source in sources) {
            val read = files.readUri(source, MAX_BYTES)
            if (read is FileResult.Ok && read.path != null) return read
        }
        return null
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val read = fetch()
                val ok = read?.path?.let { files.saveToGallery(it, fileName, read.mime ?: MIME) } ?: false
                say(
                    if (ok) {
                        UiText.Res(
                            R.string.contacts_saved_to_photos,
                        )
                    } else {
                        UiText.Res(R.string.contacts_cant_save_photo)
                    },
                )
            } finally {
                saving = false
            }
        }
    }

    fun share() {
        scope.launch {
            val read = fetch()
            val staged = read?.path?.let { files.stageForShare(it, fileName) }
            if (staged ==
                null
            ) {
                say(UiText.Res(R.string.contacts_cant_share_photo))
            } else {
                share(staged, read.mime ?: MIME)
            }
        }
    }

    /**
     * The rows of the Edit menu: the editor's avatar menu ([photoMenuItems], with a photo to remove), where Remove Photo
     * first asks through [confirmRemove].
     */
    fun editMenu(
        context: Context,
        jid: String?,
        actions: PhotoMenuActions,
        confirmRemove: (onConfirm: () -> Unit) -> Unit,
    ): ImmutableList<MenuItem> =
        photoMenuItems(
            context = context,
            hasPhoto = true,
            jid = jid,
            actions =
                PhotoMenuActions(
                    choose = actions.choose,
                    take = actions.take,
                    whatsApp = actions.whatsApp,
                    remove = { confirmRemove(actions.remove) },
                ),
        )

    /** Save, Share and Edit (with its [menu]) for the viewer's toolbar. */
    fun viewerActions(
        context: Context,
        menu: ImmutableList<MenuItem>,
    ): ImmutableList<ViewerAction> =
        persistentListOf(
            ViewerAction(SAVE, RovstiIcons.Download, context.getString(R.string.groups_save)) { save() },
            ViewerAction(SHARE, RovstiIcons.Share, context.getString(R.string.contacts_share)) { share() },
            ViewerAction(EDIT, RovstiIcons.Pencil, context.getString(R.string.favorites_edit), menu = menu),
        )

    companion object {
        const val SAVE = "save"
        const val SHARE = "share"
        const val EDIT = "edit"
        const val MIME = "image/jpeg"
        private const val MAX_BYTES = 20 * 1024 * 1024
    }
}
