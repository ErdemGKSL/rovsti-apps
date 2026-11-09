package dev.rovsti.contacts.card

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.editor.contactEditorModel
import dev.rovsti.contacts.editor.presentContactEditor
import dev.rovsti.design.overlay.ActionSheetAction
import dev.rovsti.design.overlay.ActionSheetEntry
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.files.ShareIntents
import dev.rovsti.platform.system.Handoff
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.shell.permissions.PermissionGate
import dev.rovsti.shell.permissions.PermissionRow
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope

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
    ContactCardEffects(
        model,
        navigator,
        overlays,
        context,
        onEdit = { editId ->
            presentContactEditor(contactEditorModel(container, editId, null), overlays, container.scope)
        },
    ) { query -> container.pendingSearch.value = query }
    ContactCardScreen(
        state = state,
        onAction = model::onAction,
        onBack = onBack,
        listState = rememberLazyListState(),
        gate = { PermissionRow(PermissionGroup.CALL_LOG) },
    )
}

/** Runs the model's effects: intents, navigation, toasts and the number chooser. */
@Composable
fun ContactCardEffects(
    model: ContactCardViewModel,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController,
    context: Context,
    onEdit: ((Long) -> Unit)? = null,
    onSearch: (String) -> Unit,
) {
    val latestOverlays = rememberUpdatedState(overlays)
    val latestEdit = rememberUpdatedState(onEdit)
    val latestSearch = rememberUpdatedState(onSearch)
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

                ContactCardEffect.Close -> {
                    navigator.pop()
                }
            }
        }
    }
}

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
