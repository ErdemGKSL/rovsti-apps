package dev.rovsti.contacts.card

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.editor.contactEditorExtras
import dev.rovsti.contacts.editor.contactEditorModel
import dev.rovsti.contacts.editor.presentContactEditor
import dev.rovsti.contacts.photo.ContactPhotoModel
import dev.rovsti.contacts.photo.PhotoEffect
import dev.rovsti.contacts.photo.PhotoMenuActions
import dev.rovsti.contacts.photo.PhotoViewerActions
import dev.rovsti.contacts.photo.fullPhotoUri
import dev.rovsti.contacts.photo.showPhotoCrop
import dev.rovsti.design.media.ViewerPhoto
import dev.rovsti.design.media.showPhotoViewer
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.files.CapturePhoto
import dev.rovsti.platform.files.ExportDocument
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.PickPhoto
import dev.rovsti.platform.files.ShareIntents
import dev.rovsti.platform.system.Handoff
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.shell.permissions.PermissionGate
import dev.rovsti.shell.permissions.PermissionRow
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The card's model for one open: it lives as long as the page is composed, so every open asks WhatsApp once. */
@Composable
fun rememberContactCardModel(
    container: ContactsContainer,
    id: Long,
    scope: CoroutineScope = rememberCoroutineScope(),
): ContactCardViewModel =
    remember(container, id, scope) {
        ContactCardViewModel(
            id = id,
            repo = container.contacts,
            book = container.book,
            ops = container.ops,
            shared = container.sharedOps,
            phoneOps = container.phoneOps,
            waOps = container.waCard,
            wa = container.cardWa,
            callLog = container.callLog,
            callLogPermission = container.permissions.state(PermissionGroup.CALL_LOG),
            store = container.store,
            clipboard = container.clipboard,
            files = container.files,
            qr = container.qr,
            shortcuts = container.shortcuts,
            stagingDir = container.context.cacheDir,
            scope = scope,
        )
    }

/** The destination of [ContactsRoute.ContactCard]: the screen over its model, with the effects wired to the system. */
@Composable
fun ContactCardPage(
    container: ContactsContainer,
    id: Long,
    navigator: TabNavigator<ContactsRoute>,
    onBack: () -> Unit,
) {
    PermissionGate(PermissionGroup.CALL_LOG)
    val model = rememberContactCardModel(container, id)
    val state by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<ContactCardEffect.ExportVcf?>(null) }
    val exporter =
        rememberLauncherForActivityResult(ExportDocument("text/x-vcard")) { result ->
            val file = pendingExport ?: return@rememberLauncherForActivityResult
            pendingExport = null
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val written = container.files.writeExport(uri, text = file.text)
                overlays.showToast(if (written is FileResult.Ok) "Saved ${file.fileName}" else "Can't save the file.")
            }
        }
    ContactCardEffects(
        model,
        navigator,
        overlays,
        context,
        onExport = { effect ->
            pendingExport = effect
            exporter.launch(effect.fileName)
        },
        onEdit = { editId ->
            presentContactEditor(
                contactEditorModel(container, editId, null),
                overlays,
                container.scope,
                extras = contactEditorExtras(container, editId),
            )
        },
        onDelete = { deleteId -> container.deletion.ask(overlays, deleteId) {} },
    ) { query -> container.pendingSearch.value = query }
    val calls = rememberCallOptionsModel(container, id, state.header?.name.orEmpty())
    val callsState by calls.state.collectAsState()
    CallOptionsEffects(calls, overlays, context)
    val viewer = rememberCardPhotoViewer(container, id, overlays, context)
    ContactCardScreen(
        state = state,
        onAction = model::onAction,
        onBack = onBack,
        listState = rememberLazyListState(),
        gate = { PermissionRow(PermissionGroup.CALL_LOG) },
        calls = callsState,
        onCalls = calls::onAction,
        photoViewer = viewer,
    )
}

/** The "Calls" section's model for one open of the card; [name] is read when it asks a question. */
@Composable
fun rememberCallOptionsModel(
    container: ContactsContainer,
    id: Long,
    name: String,
    scope: CoroutineScope = rememberCoroutineScope(),
): CallOptionsViewModel {
    val latestName = rememberUpdatedState(name)
    return remember(container, id, scope) {
        CallOptionsViewModel(
            id = id,
            repo = container.contacts,
            ops = container.ops,
            blocklist = container.blocklist,
            name = { latestName.value },
            scope = scope,
        )
    }
}

/** Runs the effects of the "Calls" section: the ringtone picker, the two questions, the role settings and the toasts. */
@Composable
fun CallOptionsEffects(
    model: CallOptionsViewModel,
    overlays: OverlayController,
    context: Context,
) {
    val picker =
        rememberLauncherForActivityResult(
            remember { PickRingtone() },
        ) { model.onAction(CallOptionsAction.RingtonePicked(it)) }
    val latestOverlays = rememberUpdatedState(overlays)
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            when (effect) {
                is CallOptionsEffect.PickRingtone -> {
                    picker.launch(RingtoneRequest(effect.current))
                }

                is CallOptionsEffect.AskVoicemail -> {
                    latestOverlays.value.present(
                        AlertEntry(
                            heading = effect.confirm.title,
                            message = effect.confirm.body,
                            actions =
                                persistentListOf(
                                    AlertAction("Cancel", AlertRole.Cancel),
                                    AlertAction(
                                        "Send to Voicemail",
                                    ) { model.onAction(CallOptionsAction.ConfirmVoicemail) },
                                ),
                        ),
                    )
                }

                is CallOptionsEffect.AskBlock -> {
                    latestOverlays.value.present(
                        AlertEntry(
                            heading = effect.title,
                            message = effect.message,
                            actions =
                                persistentListOf(
                                    AlertAction("Cancel", AlertRole.Cancel),
                                    AlertAction(effect.actionLabel, AlertRole.Destructive) {
                                        model.onAction(CallOptionsAction.ConfirmBlock)
                                    },
                                ),
                        ),
                    )
                }

                CallOptionsEffect.OpenRoleSettings -> {
                    start(
                        context,
                        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                    ) { latestOverlays.value.showToast(it) }
                }

                is CallOptionsEffect.Toast -> {
                    latestOverlays.value.showToast(effect.text)
                }
            }
        }
    }
}

/**
 * The launcher of the card's photo viewer (E4.12): Save, Share and the Edit menu. Choose Photo and Take Photo run the
 * system picker and camera, Use WhatsApp Photo fetches the large picture, and all three end in the crop page; Remove Photo
 * asks first. The viewer closes once the photo has changed, so the card behind shows the new one.
 */
@Composable
fun rememberCardPhotoViewer(
    container: ContactsContainer,
    id: Long,
    overlays: OverlayController,
    context: Context,
): PhotoViewerLauncher {
    val photo =
        remember(container, id) {
            ContactPhotoModel(
                contactId = id,
                repo = container.contacts,
                ops = container.ops,
                files = container.files,
                waPhotos = container.waPhotos,
                cacheDir = container.context.cacheDir,
                scope = container.scope,
            )
        }
    val ui by photo.state.collectAsState()
    val marks by container.waMarks.marks.collectAsState()
    val jid = marks[id]
    val pick = rememberLauncherForActivityResult(remember { PickPhoto() }) { photo.picked(it) }
    val capture = rememberLauncherForActivityResult(remember { CapturePhoto() }) { photo.picked(it) }
    val latestJid = rememberUpdatedState(jid)
    val latestOverlays = rememberUpdatedState(overlays)
    val open = remember { arrayOfNulls<OverlayEntry>(1) }
    LaunchedEffect(photo) {
        photo.effects.collect { effect ->
            when (effect) {
                is PhotoEffect.Toast -> latestOverlays.value.showToast(effect.text)
                PhotoEffect.Changed -> open[0]?.let { latestOverlays.value.dismiss(it) }.also { open[0] = null }
            }
        }
    }
    LaunchedEffect(ui.crop) {
        ui.crop?.let { session ->
            overlays.showPhotoCrop(session, onChosen = photo::cropChosen, onCancel = photo::cropCancelled)
        }
    }
    val scope = rememberCoroutineScope()
    return remember(container, id, pick, capture) {
        cardPhotoViewer(
            id = id,
            files = container.files,
            scope = scope,
            overlays = { latestOverlays.value },
            jid = { latestJid.value },
            menu =
                PhotoMenuActions(
                    choose = { pick.launch(Unit) },
                    take = { capture.launch(Unit) },
                    whatsApp = { latestJid.value?.let(photo::useWhatsApp) },
                    remove = photo::remove,
                ),
            share = { uri, mime, name ->
                start(context, ShareIntents.image(uri, mime, name)) { latestOverlays.value.showToast(it) }
            },
            onOpened = { open[0] = it },
        )
    }
}

/** Runs the model's effects: intents, navigation, toasts and the number chooser. */
@Composable
fun ContactCardEffects(
    model: ContactCardViewModel,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController,
    context: Context,
    onEdit: ((Long) -> Unit)? = null,
    onDelete: ((Long) -> Unit)? = null,
    onExport: ((ContactCardEffect.ExportVcf) -> Unit)? = null,
    onSearch: (String) -> Unit,
) {
    val latestOverlays = rememberUpdatedState(overlays)
    val latestDelete = rememberUpdatedState(onDelete)
    val latestEdit = rememberUpdatedState(onEdit)
    val latestSearch = rememberUpdatedState(onSearch)
    val latestExport = rememberUpdatedState(onExport)
    LaunchedEffect(model, navigator) {
        model.effects.collect { effect ->
            val toast: (String) -> Unit = { text -> latestOverlays.value.showToast(text) }
            when (effect) {
                is ContactCardEffect.Dial -> {
                    start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", effect.number, null)), toast)
                }

                is ContactCardEffect.Sms -> {
                    start(context, ShareIntents.sendSmsTo(effect.number, effect.body), toast)
                }

                is ContactCardEffect.Email -> {
                    start(context, ShareIntents.sendEmailTo(effect.address), toast)
                }

                is ContactCardEffect.WhatsApp -> {
                    if (!Handoff.start(context, Handoff.ACTION_WA_CHAT, effect.jid, effect.name)) {
                        toast("Install Rovsti Messages to chat on WhatsApp")
                    }
                }

                is ContactCardEffect.PickNumber -> {
                    latestOverlays.value.present(
                        ActionSheetEntry(
                            title = "Call",
                            actions =
                                effect.choices
                                    .map { choice ->
                                        ActionSheetAction("${choice.label} · ${choice.display}") {
                                            model.onAction(ContactCardAction.Field(FieldOp.Dial(choice.raw)))
                                        }
                                    }.toImmutableList(),
                        ),
                    )
                }

                is ContactCardEffect.OpenEditor -> {
                    latestEdit.value?.invoke(effect.id)
                        ?: navigator.push(ContactsRoute.ContactEditor(effect.id, null))
                }

                is ContactCardEffect.AskDelete -> {
                    latestDelete.value?.invoke(effect.id)
                }

                is ContactCardEffect.OpenCard -> {
                    navigator.push(ContactsRoute.ContactCard(effect.id))
                }

                is ContactCardEffect.SearchFor -> {
                    latestSearch.value(effect.query)
                    navigator.select(dev.rovsti.contacts.ContactsTab)
                }

                is ContactCardEffect.OpenUrl -> {
                    start(context, Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)), toast)
                }

                is ContactCardEffect.Share -> {
                    start(context, ShareIntents.text(effect.text), toast)
                }

                is ContactCardEffect.OpenAndroidContact -> {
                    start(context, androidContactIntent(effect), toast)
                }

                is ContactCardEffect.Toast -> {
                    toast(effect.text)
                }

                is ContactCardEffect.ShareFile -> {
                    start(context, ShareIntents.file(effect.uri, VCARD_MIME, effect.fileName), toast)
                }

                is ContactCardEffect.ExportVcf -> {
                    latestExport.value?.invoke(effect)
                }

                is ContactCardEffect.ShowQr -> {
                    latestOverlays.value.presentQrSheet(effect.model) { uri ->
                        start(context, ShareIntents.image(uri, QrSheetModel.MIME, effect.model.name), toast)
                    }
                }

                ContactCardEffect.Close -> {
                    navigator.pop()
                }
            }
        }
    }
}

private const val VCARD_MIME = "text/x-vcard"

/** `content://com.android.contacts/contacts/lookup/<key>/<id>` when the key is known, else the plain contact id. */
internal fun androidContactIntent(effect: ContactCardEffect.OpenAndroidContact): Intent {
    val uri =
        if (effect.lookupKey != null) {
            ContactsContract.Contacts.getLookupUri(effect.id, effect.lookupKey)
        } else {
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, effect.id)
        }
    return Intent(Intent.ACTION_VIEW, uri)
}

private fun start(
    context: Context,
    intent: Intent,
    onFailure: (String) -> Unit,
) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        onFailure("No app can do this.")
    }
}
