package dev.rosty.messages.wa.rich

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.wa.PollCheckRequest
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.ios.component.IosPageSheetEntry
import dev.rosty.design.ios.component.IosToggle
import dev.rosty.design.ios.component.presentPageSheet
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.conversation.ContactCardUi
import dev.rosty.messages.conversation.RichCardUi
import dev.rosty.messages.theme.RichMetrics
import dev.rosty.messages.wa.WaThreadViewModel
import dev.rosty.platform.location.AndroidLocationRepository
import dev.rosty.platform.location.Fix
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** What the page offers for rich content (E6-WA R5): the cards' taps, and the three attachment entries. */
@Stable
class RichHost(
    val cards: RichCardActions,
    /** "Poll": the create sheet. */
    val createPoll: () -> Unit,
    /** "Location": permission at first use, a one-shot fix, the preview sheet. */
    val sendPlace: () -> Unit,
    /** "Contact": the system's contact picker; the pick goes as a vCard. */
    val pickContact: () -> Unit,
)

/** The rich content of a WhatsApp thread page: cards, sheets, the location fix and the contact picker. */
@Composable
fun rememberRichActions(
    model: WaThreadViewModel,
    container: MessagesContainer,
    navigator: TabNavigator<MessagesRoute>,
    overlays: OverlayController,
): RichHost {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val maps = container.placeMaps
    val locations = remember(context) { AndroidLocationRepository(context.applicationContext) }
    val askFix: () -> Unit = {
        scope.launch {
            overlays.showToast(res.getString(R.string.wa_place_finding))
            val fix = locations.fresh(FIX_TIMEOUT) ?: locations.lastKnown()
            if (fix == null) {
                overlays.showToast(res.getString(R.string.wa_place_no_fix))
            } else {
                overlays.presentPlacePreview(res.getString(R.string.wa_place_send_title), fix, maps) {
                    scope.launch { model.sendPlace(fix.lat, fix.lon) }
                }
            }
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) {
                askFix()
            } else {
                overlays.showToast(res.getString(R.string.wa_place_no_permission))
            }
        }
    val contact =
        rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            val picked =
                uri?.let { readContact(context.contentResolver, it) } ?: return@rememberLauncherForActivityResult
            scope.launch {
                if (!model.sendContact(picked.first, picked.second)) {
                    overlays.showToast(res.getString(R.string.wa_contact_not_sent))
                }
            }
        }
    return remember(model, overlays, maps, permission, contact) {
        RichHost(
            cards =
                RichCardActions(
                    onVote = model::vote,
                    onOpenPlace = { place -> overlays.presentPlace(context, place, maps) },
                    onMessage = { jid -> navigator.push(MessagesRoute.WaThread(jid)) },
                    onAddContact = { card -> addContact(context, card) },
                    maps = maps,
                ),
            createPoll = {
                overlays.presentPollSheet(res.getString(R.string.wa_poll_new)) { draft -> model.sendPoll(draft) }
            },
            sendPlace = {
                permission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
            pickContact = {
                try {
                    contact.launch(null)
                } catch (_: ActivityNotFoundException) {
                    overlays.showToast(res.getString(R.string.wa_contact_not_sent))
                }
            },
        )
    }
}

private val FIX_TIMEOUT = 10.seconds

/** The name and the numbers of a picked contact; null when it cannot be read. */
internal fun readContact(
    resolver: ContentResolver,
    uri: Uri,
): Pair<String, List<String>>? =
    try {
        val (id, name) =
            resolver
                .query(
                    uri,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) to (c.getString(1) ?: "") else null }
                ?: return null
        val numbers =
            resolver
                .query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { c -> buildList { while (c.moveToNext()) c.getString(0)?.let(::add) } }
                .orEmpty()
                .distinct()
        name.takeIf { it.isNotBlank() }?.let { it to numbers }
    } catch (_: SecurityException) {
        null
    }

/** The system's new-contact screen with the card's name and up to three numbers. */
private fun addContact(
    context: Context,
    card: ContactCardUi,
) {
    val numbers = card.numbers.map { it.substringBefore(" · ") }
    val keys =
        listOf(
            ContactsContract.Intents.Insert.PHONE,
            ContactsContract.Intents.Insert.SECONDARY_PHONE,
            ContactsContract.Intents.Insert.TERTIARY_PHONE,
        )
    val intent =
        Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
            putExtra(ContactsContract.Intents.Insert.NAME, card.name)
            numbers.zip(keys).forEach { (number, key) -> putExtra(key, number) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No contacts app: nothing to open.
    }
}

/** "Open in…": the system's chooser of the apps that open a `geo:` link. */
private fun openIn(
    context: Context,
    place: RichCardUi.Place,
    title: String,
) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(place.geoUri))
    try {
        context.startActivity(Intent.createChooser(view, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No map app: nothing to open.
    }
}

/** The page of a place: its map with the pin, the name, the address or coordinates, and "Open in…". */
fun OverlayController.presentPlace(
    context: Context,
    place: RichCardUi.Place,
    maps: PlaceMaps?,
): IosPageSheetEntry =
    presentPageSheet(place.title) {
        PlaceSheetBody(place.title, place.subtitle, place.live, place.latitude, place.longitude, maps) {
            val chooser = context.resources.getString(R.string.wa_place_open_in)
            CapsuleButton(
                chooser,
                onClick = { openIn(context, place, chooser) },
                style = CapsuleStyle.Filled,
                fillWidth = true,
            )
        }
    }

/** The preview of my place before it is sent: the map of the fix, its accuracy and "Send". */
fun OverlayController.presentPlacePreview(
    title: String,
    fix: Fix,
    maps: PlaceMaps?,
    onSend: () -> Unit,
): IosPageSheetEntry {
    var entry: IosPageSheetEntry? = null
    entry =
        presentPageSheet(title) {
            val accuracy = stringResource(R.string.wa_place_accuracy, fix.accuracyM.toInt())
            PlaceSheetBody(stringResource(R.string.wa_place_mine), accuracy, null, fix.lat, fix.lon, maps) {
                CapsuleButton(
                    stringResource(R.string.wa_place_send),
                    onClick = {
                        onSend()
                        entry?.let(::dismiss)
                    },
                    style = CapsuleStyle.Filled,
                    fillWidth = true,
                    modifier = Modifier.testTag(PLACE_SEND_TAG),
                )
            }
        }
    return entry
}

/** The tag of the preview's Send. */
const val PLACE_SEND_TAG = "wa_place_send"

/** The body of the place sheets; [action] is their button. */
@Composable
fun PlaceSheetBody(
    title: String,
    subtitle: String?,
    live: String?,
    latitude: Double,
    longitude: Double,
    maps: PlaceMaps?,
    action: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            PlaceMap(latitude, longitude, maxWidth, RichMetrics.MapPageHeight, maps)
        }
        RostyText(title, style = RostyTheme.type.headline, color = RostyTheme.colors.labelPrimary)
        subtitle?.let { RostyText(it, style = RostyTheme.type.subheadline, color = RostyTheme.colors.labelSecondary) }
        live?.let { RostyText(it, style = RostyTheme.type.footnote, color = RostyTheme.colors.accent) }
        action()
    }
}

/** The poll create sheet: a question, 2 to 12 options (a new row appears as the last one is filled), multiple answers. */
fun OverlayController.presentPollSheet(
    title: String,
    onSend: suspend (PollCheckRequest) -> Boolean,
): IosPageSheetEntry {
    var entry: IosPageSheetEntry? = null
    entry =
        presentPageSheet(title) {
            PollSheetBody(onSend) { entry?.let(::dismiss) }
        }
    return entry
}

/** Test tags of the poll sheet. */
object PollSheetTags {
    const val QUESTION = "wa_poll_question"
    const val MULTIPLE = "wa_poll_multiple"
    const val SEND = "wa_poll_send"

    fun option(index: Int) = "wa_poll_field:$index"
}

/** The most options a poll has (`rosty_core::wa_rich::MAX_OPTIONS`). */
private const val MAX_OPTIONS = 12
private const val MIN_OPTIONS = 2

@Composable
fun PollSheetBody(
    onSend: suspend (PollCheckRequest) -> Boolean,
    onDone: () -> Unit,
) {
    val question = rememberTextFieldState()
    val options = remember { mutableStateListOf(TextFieldState(), TextFieldState()) }
    val multiple = remember { mutableStateOf(false) }
    val sending = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A new empty row appears when the last one gets text, up to 12.
    LaunchedEffect(options) {
        snapshotFlow { options.lastOrNull()?.text?.isNotBlank() == true && options.size < MAX_OPTIONS }
            .collect { grow -> if (grow) options += TextFieldState() }
    }
    val filled = options.count { it.text.isNotBlank() }
    val ready = question.text.isNotBlank() && filled >= MIN_OPTIONS && !sending.value
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RostyText(
            stringResource(R.string.wa_poll_question),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
        GlassTextField(
            question,
            stringResource(R.string.wa_poll_question_hint),
            modifier = Modifier.fillMaxWidth().testTag(PollSheetTags.QUESTION),
            maxLines = 3,
        )
        RostyText(
            stringResource(R.string.wa_poll_options),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
        options.forEachIndexed { index, state ->
            GlassTextField(
                state,
                stringResource(R.string.wa_poll_option_hint, index + 1),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = RichMetrics.OptionFieldMin)
                        .testTag(PollSheetTags.option(index)),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RostyText(
                stringResource(R.string.wa_poll_multiple),
                style = RostyTheme.type.body,
                color = RostyTheme.colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            IosToggle(
                checked = multiple.value,
                onCheckedChange = { multiple.value = it },
                modifier = Modifier.testTag(PollSheetTags.MULTIPLE),
            )
        }
        CapsuleButton(
            stringResource(R.string.wa_poll_send),
            onClick = {
                sending.value = true
                scope.launch {
                    val sent =
                        onSend(
                            PollCheckRequest(
                                question = question.text.toString(),
                                options = options.map { it.text.toString() },
                                multiple = multiple.value,
                            ),
                        )
                    sending.value = false
                    if (sent) onDone()
                }
            },
            enabled = ready,
            style = CapsuleStyle.Filled,
            fillWidth = true,
            modifier = Modifier.testTag(PollSheetTags.SEND),
        )
    }
}
