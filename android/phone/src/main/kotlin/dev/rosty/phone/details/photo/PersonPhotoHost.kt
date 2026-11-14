package dev.rosty.phone.details.photo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import dev.rosty.design.media.ViewerPhoto
import dev.rosty.design.media.crop.showPhotoCrop
import dev.rosty.design.media.showPhotoViewer
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.resolve
import dev.rosty.phone.PhoneContainer
import dev.rosty.phone.R
import dev.rosty.platform.files.CapturePhoto
import dev.rosty.platform.files.PickPhoto
import dev.rosty.platform.files.ShareIntents
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.PermissionState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The opener of the person photo viewer (E5.13): a tap on the avatar of the Person or Call details sheet opens the
 * shared-bounds viewer ([showPhotoViewer]) with Save, Share and, for the contact's own photo, the Edit menu; a contact
 * without a photo gets Choose Photo / Take Photo in an action sheet. Choose and Take end in the crop page of `:design`
 * and the write goes through `ContactsRepository` ([PersonPhotoViewModel]); without a write grant the contact's system
 * editor opens through [handOff] instead.
 */
@Composable
fun rememberPersonPhotoOpener(
    container: PhoneContainer,
    overlays: OverlayController,
    handOff: (contactId: Long) -> Unit,
): PersonPhotoOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOverlays = rememberUpdatedState(overlays)
    val latestHandOff = rememberUpdatedState(handOff)
    val current = remember { arrayOfNulls<PersonPhotoViewModel>(1) }
    val pick = rememberLauncherForActivityResult(remember { PickPhoto() }) { current[0]?.picked(it) }
    val capture = rememberLauncherForActivityResult(remember { CapturePhoto() }) { current[0]?.picked(it) }
    return remember(container, pick, capture, scope) {
        PersonPhotoOpener { photo, name, anchor ->
            val model =
                PersonPhotoViewModel(
                    contactId = photo.contactId,
                    repo = container.contacts,
                    ops = container.contactsOps,
                    files = container.files,
                    canWrite = {
                        container.permissions.state(PermissionGroup.CONTACTS).value is PermissionState.Granted
                    },
                    scope = container.scope,
                )
            current[0] = model
            PersonPhotoSession(
                context = context,
                scope = scope,
                overlays = { latestOverlays.value },
                container = container,
                model = model,
                photo = photo,
                name = name,
                anchor = anchor,
                choose = { if (model.guard()) pick.launch(Unit) },
                take = { if (model.guard()) capture.launch(Unit) },
                handOff = { latestHandOff.value(it) },
            ).start()
        }
    }
}

/** One opened viewer (or the add-photo sheet) and the collectors that live as long as it does. */
private class PersonPhotoSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val overlays: () -> OverlayController,
    private val container: PhoneContainer,
    private val model: PersonPhotoViewModel,
    private val photo: PersonPhoto,
    private val name: String,
    private val anchor: () -> Rect?,
    private val choose: () -> Unit,
    private val take: () -> Unit,
    private val handOff: (Long) -> Unit,
) {
    private var viewer: OverlayEntry? = null
    private var collecting: Job? = null

    fun start() {
        collecting?.cancel()
        collecting =
            scope.launch {
                launch {
                    model.effects.collect { effect ->
                        when (effect) {
                            is PersonPhotoEffect.Toast -> {
                                overlays().showToast(effect.text.resolve(context))
                            }

                            PersonPhotoEffect.Changed -> {
                                viewer?.let { overlays().dismiss(it) }
                                viewer = null
                                overlays().showToast(context.getString(R.string.photo_updated))
                            }

                            is PersonPhotoEffect.HandOff -> {
                                overlays().showToast(context.getString(R.string.photo_handoff))
                                handOff(effect.contactId)
                            }
                        }
                    }
                }
                launch {
                    model.state
                        .map { it.crop }
                        .distinctUntilChanged()
                        .collect { session ->
                            if (session != null) {
                                overlays().showPhotoCrop(
                                    session,
                                    onChosen = model::cropChosen,
                                    onCancel = model::cropCancelled,
                                )
                            }
                        }
                }
            }
        if (photo.hasPhoto) openViewer() else offerAdd()
    }

    private fun offerAdd() {
        if (!photo.source.editable) return
        overlays().present(
            ActionSheetEntry(
                title = name,
                actions =
                    persistentListOf(
                        ActionSheetAction(context.getString(R.string.photo_choose), onClick = choose),
                        ActionSheetAction(context.getString(R.string.photo_take), onClick = take),
                    ),
                cancelLabel = context.getString(R.string.photo_cancel),
            ),
        )
    }

    private fun confirmRemove() {
        overlays().present(
            AlertEntry(
                heading = context.getString(R.string.photo_remove_question),
                message = context.getString(R.string.photo_remove_message, name),
                actions =
                    persistentListOf(
                        AlertAction(context.getString(R.string.photo_cancel), AlertRole.Cancel),
                        AlertAction(context.getString(R.string.photo_remove), AlertRole.Destructive) { model.remove() },
                    ),
            ),
        )
    }

    /** The WhatsApp picture's full-size version from the gateway (S12); null for the contact's own photo. */
    private fun fullPicture(): (suspend () -> Any?)? {
        val jid = photo.waJid ?: return null
        val id = photo.waPictureId ?: return null
        return { container.waFullPictures.load(jid, id) }
    }

    private fun openViewer() {
        val actions =
            PersonPhotoActions(
                name = name,
                sources = listOfNotNull(photo.full, photo.thumb?.let(android.net.Uri::parse)),
                files = container.files,
                scope = scope,
                say = { overlays().showToast(it.resolve(context)) },
                share = { uri, mime ->
                    try {
                        context.startActivity(
                            ShareIntents.image(uri, mime, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        overlays().showToast(context.getString(R.string.photo_cant_share))
                    }
                },
            )
        val menu = PersonPhotoMenu(choose = choose, take = take, remove = ::confirmRemove)
        viewer =
            overlays().showPhotoViewer(
                photos =
                    persistentListOf(
                        ViewerPhoto(
                            full = photo.full,
                            thumb = photo.thumb,
                            source = photo.source,
                            circular = true,
                            upgrade = fullPicture(),
                        ),
                    ),
                initialPage = 0,
                actions =
                    actions.viewerActions(
                        context,
                        photo.source,
                        PersonPhotoActions.editMenu(context, photo.hasPhoto, menu),
                    ),
                anchorFor = { anchor() },
            )
    }
}
