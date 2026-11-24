package dev.rosty.messages.wa.group

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.Outcome
import dev.rosty.core.model.wa.CreateGroup
import dev.rosty.core.model.wa.GroupCanRequest
import dev.rosty.core.model.wa.GroupMember
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.AlertAction
import dev.rosty.design.overlay.AlertEntry
import dev.rosty.design.overlay.AlertRole
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.compose.NewConversationViewModel
import dev.rosty.messages.settings.findComponentActivity
import dev.rosty.messages.theme.GroupMetrics
import dev.rosty.messages.wa.presentWaTimer
import dev.rosty.messages.wa.rememberWaThreadViewModel
import dev.rosty.messages.wa.timerLabel
import dev.rosty.platform.identity.PhoneNumbers
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

/** My ids (number and LID) as the link last named them. */
internal fun ownIdsOf(container: MessagesContainer): List<String> =
    (container.wa.linkState.value as? dev.rosty.wa.WaLinkState.Connected)
        ?.status
        ?.let { listOfNotNull(it.ownJid, it.ownLid) }
        .orEmpty()

/** The real [GroupSources]: the gateway through [MessagesContainer.waRemote], the offline store, the name rule. */
fun realGroupSources(container: MessagesContainer): GroupSources =
    GroupSources(
        remote = container.waRemote,
        read = { jid, own, query, offset, limit -> container.waOffline.groupRead(jid, own, query, offset, limit) },
        store = { container.waOffline.groupStore(it) },
        observe = { container.waOffline.observe(groups = listOf(it)) },
        name = { jid, push -> container.names.sender(jid, push) },
        readable = { number ->
            withContext(Dispatchers.Default) { PhoneNumbers.forReading(number, container.homeRegion) }
        },
        rules = container.waActionRules,
        ownIds = { ownIdsOf(container) },
        day = { ms -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)) },
        changes = container.waOffline.changes,
    )

/** The view model of the info page of [jid], kept by the activity while the page is on a stack. */
@Composable
fun rememberWaGroupModel(
    container: MessagesContainer,
    jid: String,
): WaGroupModel {
    val context = LocalContext.current
    val res = LocalResources.current
    val owner = remember(context) { context.findComponentActivity() }
    val key = "wa-group-$jid"
    return remember(owner, key) {
        val factory =
            viewModelFactory { initializer { WaGroupModel(realGroupSources(container), jid, GroupWords(res)) } }
        ViewModelProvider(owner, factory)[key, WaGroupModel::class.java]
    }
}

/** The group info page (E6-WA R6), opened from the thread header of a group. */
@Composable
fun WaGroupInfoPage(
    container: MessagesContainer,
    route: MessagesRoute.WaGroupInfo,
    navigator: TabNavigator<MessagesRoute>,
    onBack: () -> Unit,
) {
    val res = LocalResources.current
    val context = LocalContext.current
    val model = rememberWaGroupModel(container, route.jid)
    val thread = rememberWaThreadViewModel(container, route.jid)
    val state by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val scope = rememberCoroutineScope()
    val search = rememberTextFieldState()
    LaunchedEffect(model) {
        snapshotFlow { search.text.toString() }.collect {
            if (it !=
                model.state.value.query
            ) {
                model.search(it)
            }
        }
    }
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            when (effect) {
                is GroupEffect.Toast -> {
                    overlays.showToast(effect.text)
                }

                is GroupEffect.Link -> {
                    overlays.presentLink(res, context, container, effect.link, model)
                }

                is GroupEffect.Results -> {
                    overlays.present(
                        SheetEntry(res.getString(R.string.wa_group_results_title), persistentListOf(Detent.Medium)) {
                            GroupResultsContent(effect.done, effect.rows, onInvite = { row ->
                                row.invite?.let { model.sendInvite(row.jid, it) }
                            })
                        },
                    )
                }

                GroupEffect.Left -> {}
            }
        }
    }
    val picture =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch { jpegOf(context, uri)?.let(model::setPicture) }
        }
    val actions =
        remember(model, overlays, res) {
            GroupInfoActions(
                onBack = onBack,
                onMember = { member ->
                    scope.launch { overlays.presentMember(res, container, model, member, navigator) }
                },
                onSetting = model::setSetting,
                onLink = { model.inviteLink(reset = false) },
                onRequests = { overlays.presentRequests(res, model) },
                onAdd = {
                    overlays.presentPicker(
                        res,
                        container,
                        button = { n -> res.getString(R.string.wa_group_add_button, n) },
                        withSubject = false,
                    ) { jids, _ -> model.members("add", jids) }
                },
                onEditSubject = {
                    overlays.presentText(
                        res,
                        R.string.wa_group_edit_subject,
                        model.state.value.subject,
                        1,
                        model::setSubject,
                    )
                },
                onEditDescription = {
                    overlays.presentText(
                        res,
                        R.string.wa_group_edit_description,
                        model.state.value.description
                            .orEmpty(),
                        DESCRIPTION_EDIT_LINES,
                        model::setDescription,
                    )
                },
                onPicture = {
                    overlays.present(
                        ActionSheetEntry(
                            actions =
                                persistentListOf(
                                    ActionSheetAction(res.getString(R.string.wa_group_change_picture)) {
                                        picture.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                    ActionSheetAction(
                                        res.getString(R.string.wa_group_remove_picture),
                                        destructive = true,
                                    ) {
                                        model.setPicture(null)
                                    },
                                ),
                            cancelLabel = res.getString(android.R.string.cancel),
                        ),
                    )
                },
                onTimer = { overlays.presentWaTimer(res, thread) },
                onLeave = { overlays.presentLeave(res, model) },
                onMore = model::more,
                onSubgroup = { group -> navigator.push(MessagesRoute.WaThread(group.jid)) },
            )
        }
    GroupInfoScreen(state, actions, timerLabel(res, state.timer), search)
}

/** The actions on one member, as far as my role allows (`wa.group_can`). */
private suspend fun OverlayController.presentMember(
    res: Resources,
    container: MessagesContainer,
    model: WaGroupModel,
    member: GroupMemberUi,
    navigator: TabNavigator<MessagesRoute>,
) {
    val state = model.state.value
    val myRole = state.myRoleKey

    suspend fun can(action: String): Boolean {
        val role = myRole ?: return false
        if (!state.abilities.manage) return false
        val answer = container.waOps.groupCan(GroupCanRequest(role, member.role, action))
        return (answer as? Outcome.Ok)?.value?.allowed == true
    }
    val items = ArrayList<ActionSheetAction>()
    items +=
        ActionSheetAction(res.getString(R.string.wa_group_message_member)) {
            navigator.push(MessagesRoute.WaThread(member.jid))
        }
    if (can("promote")) {
        items +=
            ActionSheetAction(
                res.getString(R.string.wa_group_make_admin),
            ) { model.members("promote", listOf(member.jid)) }
    }
    if (can("demote")) {
        items +=
            ActionSheetAction(
                res.getString(R.string.wa_group_dismiss_admin),
            ) { model.members("demote", listOf(member.jid)) }
    }
    if (can("remove")) {
        items +=
            ActionSheetAction(res.getString(R.string.wa_group_remove_member), destructive = true) {
                model.members("remove", listOf(member.jid))
            }
    }
    present(
        ActionSheetEntry(
            title = member.name,
            actions = items.toImmutableList(),
            cancelLabel = res.getString(android.R.string.cancel),
        ),
    )
}

/** "Exit Group?" with the last-admin warning. */
private fun OverlayController.presentLeave(
    res: Resources,
    model: WaGroupModel,
) {
    val state = model.state.value
    present(
        AlertEntry(
            heading = res.getString(R.string.wa_group_leave_confirm, state.subject),
            message = res.getString(R.string.wa_group_leave_last_admin).takeIf { state.lastAdmin },
            actions =
                persistentListOf(
                    AlertAction(res.getString(android.R.string.cancel), AlertRole.Cancel),
                    AlertAction(res.getString(R.string.wa_group_leave), AlertRole.Destructive) { model.leave() },
                ),
        ),
    )
}

/** The invite link with copy, share and reset. */
private fun OverlayController.presentLink(
    res: Resources,
    context: Context,
    container: MessagesContainer,
    link: String,
    model: WaGroupModel,
) {
    val controller = this
    present(
        SheetEntry(res.getString(R.string.wa_group_link_title), persistentListOf(Detent.Medium)) {
            GroupLinkContent(
                link,
                onCopy = {
                    container.clipboard.copy(link)
                    controller.showToast(res.getString(R.string.wa_group_link_copied))
                },
                onShare = { share(context, link) },
                onReset = {
                    controller.dismissAll()
                    model.inviteLink(reset = true)
                },
            )
        },
    )
}

private fun share(
    context: Context,
    text: String,
) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The pending join requests; a tap approves or rejects. */
private fun OverlayController.presentRequests(
    res: Resources,
    model: WaGroupModel,
) {
    val controller = this
    present(
        SheetEntry(res.getString(R.string.wa_group_requests), persistentListOf(Detent.Medium, Detent.Large)) {
            var rows by remember { mutableStateOf<List<ResultRowUi>?>(null) }
            LaunchedEffect(model) { rows = model.requests() }
            GroupRequestsContent(rows?.toImmutableList(), onAnswer = { row, approve ->
                model.answerRequests(approve, listOf(row.jid))
                rows = rows?.filterNot { it.jid == row.jid }
                if (rows.isNullOrEmpty()) controller.dismissAll()
            })
        },
    )
}

/** A subject or description to edit. */
private fun OverlayController.presentText(
    res: Resources,
    title: Int,
    current: String,
    lines: Int,
    onSave: (String) -> Unit,
) {
    val controller = this
    present(
        SheetEntry(res.getString(title), persistentListOf(Detent.Medium)) {
            val field = rememberTextFieldState()
            LaunchedEffect(Unit) { field.setTextAndPlaceCursorAtEnd(current) }
            GroupTextContent(field, res.getString(title), lines, onSave = {
                onSave(field.text.toString())
                controller.dismissAll()
            })
        },
    )
}

/**
 * Picks people from the contacts (and a typed number) for "Add Members" or "New Group" ([withSubject]: the name
 * field too). [onDone] gets the picked WhatsApp ids and the subject.
 */
fun OverlayController.presentPicker(
    res: Resources,
    container: MessagesContainer,
    button: (Int) -> String,
    withSubject: Boolean,
    onDone: (List<String>, String) -> Unit,
) {
    val controller = this
    present(
        SheetEntry(
            res.getString(if (withSubject) R.string.wa_group_new else R.string.wa_group_add_members),
            persistentListOf(Detent.Large),
        ) {
            val field = rememberTextFieldState()
            val subject = if (withSubject) rememberTextFieldState() else null
            val model =
                remember {
                    NewConversationViewModel(
                        book = container.book.book,
                        sms = container.sms,
                        wa = container.wa,
                        waOps = container.waOps,
                        e164 = { PhoneNumbers.e164(it, container.homeRegion) },
                        share = null,
                        names = container.names,
                    )
                }
            LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect(model::setQuery) }
            val state by model.state.collectAsState()
            var picked by remember { mutableStateOf(linkedSetOf<String>()) }
            val options =
                state.options
                    .mapNotNull { option ->
                        val e164 = PhoneNumbers.e164(option.number, container.homeRegion) ?: return@mapNotNull null
                        PickOption(
                            option.key,
                            option.title,
                            option.detail,
                            e164.removePrefix("+") + "@s.whatsapp.net",
                            option.photoUri,
                        )
                    }.toImmutableList()
            val named = subject?.text?.isNotBlank() ?: true
            GroupPickerContent(
                field = field,
                options = options,
                picked = picked,
                onToggle = { option ->
                    picked = LinkedHashSet(picked).apply { if (!remove(option.jid)) add(option.jid) }
                },
                button = button(picked.size),
                enabled = named && (picked.isNotEmpty() || withSubject),
                onDone = {
                    onDone(picked.toList(), subject?.text?.toString().orEmpty())
                    controller.dismissAll()
                },
                subject = subject,
            )
        },
    )
}

/** "New Group": the name and the people, then the new group's thread; who did not get in is listed. */
fun OverlayController.presentNewGroup(
    res: Resources,
    container: MessagesContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    navigator: TabNavigator<MessagesRoute>,
) {
    val controller = this
    presentPicker(res, container, button = { n ->
        res.getString(R.string.wa_group_create, n)
    }, withSubject = true) { jids, subject ->
        scope.launch {
            when (val made = container.waRemote.createGroup(CreateGroup(subject, jids))) {
                is Outcome.Ok -> navigator.push(MessagesRoute.WaGroupInfo(made.value.jid))
                is Outcome.Err -> controller.showToast(res.getString(R.string.wa_group_failed))
            }
        }
    }
}

/**
 * The group an invite link leads to, then Join (or Request to Join). [code] is the link or code as found; a reset or
 * unknown link says so.
 */
fun OverlayController.presentJoin(
    res: Resources,
    container: MessagesContainer,
    code: String,
    navigator: TabNavigator<MessagesRoute>,
) {
    val controller = this
    present(
        SheetEntry(res.getString(R.string.wa_group_join), persistentListOf(Detent.Medium)) {
            var preview by remember { mutableStateOf(JoinPreviewUi(loading = true)) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(code) {
                preview =
                    when (val got = container.waRemote.invitePreview(code)) {
                        is Outcome.Ok -> {
                            JoinPreviewUi(
                                jid = got.value.jid,
                                subject = got.value.subject,
                                size =
                                    got.value.size?.let {
                                        res.getQuantityString(
                                            R.plurals.wa_group_member_count,
                                            it,
                                            it,
                                        )
                                    },
                                description = got.value.description,
                                approval = got.value.approval,
                            )
                        }

                        is Outcome.Err -> {
                            JoinPreviewUi(invalid = true)
                        }
                    }
            }
            JoinPreviewContent(preview, onJoin = {
                preview = preview.copy(loading = true)
                scope.launch {
                    when (val joined = container.waRemote.joinGroup(code)) {
                        is Outcome.Ok -> {
                            if (joined.value.pending) {
                                preview = preview.copy(requested = true, loading = false)
                            } else {
                                controller.dismissAll()
                                navigator.push(MessagesRoute.WaThread(joined.value.jid))
                            }
                        }

                        is Outcome.Err -> {
                            preview = preview.copy(loading = false)
                            controller.showToast(res.getString(R.string.wa_group_failed))
                        }
                    }
                }
            })
        },
    )
}

/** "Join Group with Link": a pasted link, then the preview. */
fun OverlayController.presentJoinWithLink(
    res: Resources,
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
) {
    val controller = this
    present(
        SheetEntry(res.getString(R.string.wa_group_join_with_link), persistentListOf(Detent.Medium)) {
            val field = rememberTextFieldState()
            val scope = rememberCoroutineScope()
            GroupTextContent(field, res.getString(R.string.wa_group_paste_link), 2, onSave = {
                scope.launch {
                    val code =
                        container.waActionRules.inviteFind(field.text.toString())?.let {
                            it.code
                                ?: it.found?.code
                        }
                    controller.dismissAll()
                    if (code == null) {
                        controller.showToast(res.getString(R.string.wa_group_link_invalid))
                    } else {
                        controller.presentJoin(res, container, code, navigator)
                    }
                }
            })
        },
    )
}

/** The picked picture as a square-cropped [GroupMetrics.PICTURE_PX] px JPEG in base64; null when it cannot be read. */
private suspend fun jpegOf(
    context: Context,
    uri: Uri,
): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap =
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator =
                        ImageDecoder.ALLOCATOR_SOFTWARE
                }
            val side = minOf(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, GroupMetrics.PICTURE_PX, GroupMetrics.PICTURE_PX, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, GroupMetrics.PICTURE_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

private const val DESCRIPTION_EDIT_LINES = 6
