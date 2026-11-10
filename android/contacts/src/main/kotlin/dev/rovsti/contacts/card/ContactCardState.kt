package dev.rovsti.contacts.card

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.contacts.TitleDetail
import dev.rovsti.design.text.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Where the card stands: the contact is being read, shown, or gone (deleted elsewhere). */
enum class CardPhase { Loading, Ready, Gone }

/** The kinds of field row, in the order the sections appear. */
enum class RowKind {
    Number,
    Email,
    Date,
    Address,
    Website,
    Relation,
    Group,
    Nickname,
    Phonetic,
    Note,
    Account,
    Whatsapp,
}

/** The four capsules under the name. Equal width, never wrapping. */
enum class CapsuleKind { Call, Message, WhatsApp, Email }

@Immutable
data class CardCapsule(
    val kind: CapsuleKind,
    val label: UiText,
    val enabled: Boolean = true,
)

/** What one entry of a field's long-press menu does. The view model runs it; the screen only draws the labels. */
@Immutable
sealed interface FieldOp {
    /** Copies [text]; [notice] is the toast ("Number copied"). */
    data class Copy(
        val text: String,
        val notice: UiText,
    ) : FieldOp

    data class ShareText(
        val text: String,
    ) : FieldOp

    data class Dial(
        val number: String,
    ) : FieldOp

    data class Sms(
        val number: String,
    ) : FieldOp

    data class Email(
        val address: String,
    ) : FieldOp

    /** Makes the entry the default of its list, or clears the default when [clear]. */
    data class SetDefault(
        val emails: Boolean,
        val value: String,
        val clear: Boolean,
    ) : FieldOp

    data class OpenUrl(
        val url: String,
    ) : FieldOp
}

@Immutable
data class FieldMenuItem(
    val label: UiText,
    val op: FieldOp,
)

/** A number, e-mail, date, address ... row. [title] is the value, [label] the small caption above/below it. */
@Immutable
data class CardRow(
    val key: String,
    val kind: RowKind,
    val title: UiText,
    val label: UiText? = null,
    /** The "Default" tag: only on a list with more than one entry. */
    val isDefault: Boolean = false,
    /** The JID whose WhatsApp chip this row carries (one chip per WhatsApp number, C17). */
    val waJid: String? = null,
    /** Text of the birthday "Wish" chip, sent as an SMS to [wishNumber]. */
    val wish: String? = null,
    val wishNumber: String? = null,
    /** What a tap does; null for rows that only copy (note, nickname). */
    val tap: FieldOp? = null,
    /** A relation row opens the card of the person or searches for the name. */
    val relationName: String? = null,
    val menu: ImmutableList<FieldMenuItem> = persistentListOf(),
)

@Immutable
data class CardSection(
    val kind: RowKind,
    val title: UiText?,
    val rows: ImmutableList<CardRow>,
)

@Immutable
data class CardHeader(
    val id: Long,
    val name: String,
    /** "Engineer · Acme". */
    val orgLine: String?,
    val photoUri: String?,
    /** The presence line under the name ("Online", "Last seen today at 14:02"); null when WhatsApp says nothing. */
    val presence: String?,
    val starred: Boolean,
    val isMyCard: Boolean = false,
    /** On the emergency list: the "..." menu offers Remove instead of Add. */
    val isEmergency: Boolean = false,
)

/** The "Recent" section: hidden, asking for the call log, or the newest calls. */
@Immutable
sealed interface CardRecent {
    data object Hidden : CardRecent

    /** READ_CALL_LOG is not granted: [text] is Rust's title and detail for the row above the permission button. */
    data class NeedsPermission(
        val text: TitleDetail,
    ) : CardRecent

    data class Calls(
        val summary: String?,
        val rows: ImmutableList<TitleDetail>,
    ) : CardRecent
}

/** A number the Call capsule can choose between when the contact has several and no default (`call_target` choose). */
@Immutable
data class CallChoice(
    val raw: String,
    val display: String,
    val label: String,
)

@Immutable
data class ContactCardUiState(
    val phase: CardPhase = CardPhase.Loading,
    val header: CardHeader? = null,
    val capsules: ImmutableList<CardCapsule> = persistentListOf(),
    val sections: ImmutableList<CardSection> = persistentListOf(),
    val recent: CardRecent = CardRecent.Hidden,
    /** The Android Contacts lookup key for the "Open in Android Contacts" row; null hides the row. */
    val lookupKey: String? = null,
    /** WhatsApp JID the WhatsApp capsule opens; null hides it. */
    val waJid: String? = null,
    /** The numbers behind a "choose" Call capsule. */
    val callChoices: ImmutableList<CallChoice> = persistentListOf(),
)

sealed interface ContactCardAction {
    data object Call : ContactCardAction

    data object Message : ContactCardAction

    data object WhatsApp : ContactCardAction

    data object Email : ContactCardAction

    data object Edit : ContactCardAction

    /** "Delete Contact" of the top-bar menu: asks first (E4.5). */
    data object Delete : ContactCardAction

    data object ToggleStar : ContactCardAction

    data object OpenInContacts : ContactCardAction

    data class Tap(
        val op: FieldOp,
    ) : ContactCardAction

    data class Field(
        val op: FieldOp,
    ) : ContactCardAction

    data class Relation(
        val name: String,
    ) : ContactCardAction

    data class WaChip(
        val jid: String,
    ) : ContactCardAction

    data class Wish(
        val number: String,
        val text: String,
    ) : ContactCardAction

    /** The "..." menu: Share as Text, Share as File, Show QR Code. */
    data object ShareText : ContactCardAction

    data object ShareFile : ContactCardAction

    data object ShowQr : ContactCardAction

    /** Export .vcf: asks where to save the file. */
    data object ExportVcf : ContactCardAction

    /** Copy Contact (the card text) and Copy as vCard. */
    data object CopyContact : ContactCardAction

    data object CopyVcard : ContactCardAction

    data object AddToHome : ContactCardAction

    /** "This Is Me" / "Not Me" of the "..." menu (C38). */
    data object ToggleMyCard : ContactCardAction

    /** "Add to / Remove from Emergency Contacts" of the "..." menu (C43). */
    data object ToggleEmergency : ContactCardAction
}

/** One-shot results of the card; the entry turns them into intents, navigation and toasts. */
sealed interface ContactCardEffect {
    data class Dial(
        val number: String,
    ) : ContactCardEffect

    data class Sms(
        val number: String,
        val body: String? = null,
    ) : ContactCardEffect

    data class Email(
        val address: String,
    ) : ContactCardEffect

    data class WhatsApp(
        val jid: String,
        val name: String,
    ) : ContactCardEffect

    data class PickNumber(
        val choices: ImmutableList<CallChoice>,
    ) : ContactCardEffect

    data class OpenEditor(
        val id: Long,
    ) : ContactCardEffect

    /** Ask whether to delete the contact; the entry runs the Trash flow (the page closes when the contact is gone). */
    data class AskDelete(
        val id: Long,
    ) : ContactCardEffect

    data class OpenCard(
        val id: Long,
    ) : ContactCardEffect

    /** No contact has this name: the list is searched for it. */
    data class SearchFor(
        val query: String,
    ) : ContactCardEffect

    data class OpenUrl(
        val url: String,
    ) : ContactCardEffect

    data class Share(
        val text: String,
    ) : ContactCardEffect

    data class OpenAndroidContact(
        val id: Long,
        val lookupKey: String?,
    ) : ContactCardEffect

    data class Toast(
        val text: UiText,
    ) : ContactCardEffect

    /** The staged vCard file to send with `text/x-vcard`. */
    data class ShareFile(
        val uri: android.net.Uri,
        val fileName: String,
    ) : ContactCardEffect

    /** Choose where to write the .vcf ([ExportVcf]); the entry writes [text] to the chosen document. */
    data class ExportVcf(
        val fileName: String,
        val text: String,
    ) : ContactCardEffect

    /** Open the QR sheet for [model]. */
    data class ShowQr(
        val model: QrSheetModel,
    ) : ContactCardEffect

    /** The contact is gone: leave the page. */
    data object Close : ContactCardEffect
}
