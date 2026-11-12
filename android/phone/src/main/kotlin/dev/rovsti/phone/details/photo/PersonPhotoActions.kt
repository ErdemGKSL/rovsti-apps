package dev.rovsti.phone.details.photo

import android.content.Context
import android.net.Uri
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.media.PhotoSource
import dev.rovsti.design.media.ViewerAction
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.text.UiText
import dev.rovsti.phone.R
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.FilesRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the Edit menu of the viewer's toolbar does; each call is already behind the write-grant guard of the model. */
class PersonPhotoMenu(
    val choose: () -> Unit,
    val take: () -> Unit,
    val remove: () -> Unit,
)

/**
 * The toolbar of the person photo viewer (E5.13, U2 and U10). Save (U2) writes the picture to Pictures/Rovsti through
 * [FilesRepository.saveToGallery] (MediaStore, no storage permission) and says so in a toast; Share stages the picture
 * and hands it to [share]; Edit opens the photo menu (Choose Photo, Take Photo, Remove Photo) and is left out for a
 * read-only [PhotoSource] such as the WhatsApp fallback of X.1.
 *
 * The picture is read from the provider into the app cache first: the full-size photo, or the thumbnail when the full
 * one cannot be read. A tap on Save while a save runs is ignored, so the gallery gets the picture once.
 */
class PersonPhotoActions(
    private val name: String,
    private val sources: List<Uri>,
    private val files: FilesRepository,
    private val scope: CoroutineScope,
    private val say: (UiText) -> Unit,
    private val share: (Uri, String) -> Unit,
) {
    private var saving = false

    /** The name of the image file: the person's name, then `.jpg`. */
    val fileName: String
        get() {
            val base = name.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
            return base.ifEmpty { "contact" } + ".jpg"
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
                say(UiText.Res(if (ok) R.string.photo_saved else R.string.photo_cant_save))
            } finally {
                saving = false
            }
        }
    }

    fun share() {
        scope.launch {
            val read = fetch()
            val staged = read?.path?.let { files.stageForShare(it, fileName) }
            if (staged == null) say(UiText.Res(R.string.photo_cant_share)) else share(staged, read.mime ?: MIME)
        }
    }

    /** Save, Share and, for an editable [source], Edit with its [menu] rows. */
    fun viewerActions(
        context: Context,
        source: PhotoSource,
        menu: ImmutableList<MenuItem>,
    ): ImmutableList<ViewerAction> {
        val actions = mutableListOf<ViewerAction>()
        actions += ViewerAction(SAVE, RovstiIcons.Download, context.getString(R.string.photo_save)) { save() }
        actions += ViewerAction(SHARE, RovstiIcons.Share, context.getString(R.string.photo_share)) { share() }
        if (source.editable) {
            actions += ViewerAction(EDIT, RovstiIcons.Pencil, context.getString(R.string.photo_edit), menu = menu)
        }
        return actions.toImmutableList()
    }

    companion object {
        const val SAVE = "save"
        const val SHARE = "share"
        const val EDIT = "edit"
        const val MIME = "image/jpeg"
        private const val MAX_BYTES = 20 * 1024 * 1024

        /** The rows of the Edit menu: Choose and Take always, Remove only when there is a photo to remove. */
        fun editMenu(
            context: Context,
            hasPhoto: Boolean,
            menu: PersonPhotoMenu,
        ): ImmutableList<MenuItem> {
            val items = mutableListOf<MenuItem>()
            items += MenuItem.Action(context.getString(R.string.photo_choose), RovstiIcons.Photo, onClick = menu.choose)
            items += MenuItem.Action(context.getString(R.string.photo_take), RovstiIcons.Camera, onClick = menu.take)
            if (hasPhoto) {
                items += MenuItem.Separator
                items +=
                    MenuItem.Action(
                        context.getString(R.string.photo_remove),
                        RovstiIcons.Trash,
                        destructive = true,
                        onClick = menu.remove,
                    )
            }
            return items.toImmutableList()
        }
    }
}
