package dev.rovsti.contacts.intake

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.people.PersonItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Where the cards to import come from (E4.10). The clipboard is read on the tap that asks for it, never earlier. */
sealed interface IntakeSource {
    /** A file handed to the app by SEND or VIEW, or picked in Settings: the content URI as text. */
    data class File(
        val uri: String,
    ) : IntakeSource

    /** "Paste Contact": the primary clip, read when the model starts. */
    data object Paste : IntakeSource
}

enum class IntakePhase {
    /** The file is read and planned; the page shows a status line. */
    Reading,

    /** The preview sheet is up. */
    Preview,

    /** The cards are being written to the address book. */
    Importing,

    /** The page is done; it closes. */
    Done,
}

/** One card of the preview list. [photo] is the picture of the card, already decoded; [secondary] its first number. */
@Immutable
data class IntakeRow(
    val index: Int,
    val name: String,
    val secondary: String?,
    val photo: ImageBitmap?,
) {
    val colorKey: String get() = "intake-$index"
}

/** One labelled value of the read-only card. */
@Immutable
data class PreviewLine(
    val label: String,
    val value: String,
)

@Immutable
data class PreviewSection(
    val title: String?,
    val lines: ImmutableList<PreviewLine>,
)

/** The read-only card of one imported contact (C31). */
@Immutable
data class CardPreview(
    val index: Int,
    val name: String,
    val subtitle: String?,
    val photo: ImageBitmap?,
    val sections: ImmutableList<PreviewSection>,
) {
    val colorKey: String get() = "intake-$index"
}

@Immutable
data class IntakeUiState(
    val phase: IntakePhase = IntakePhase.Reading,
    val title: String = "Add these contacts?",
    /** "2 new contacts" or "2 new, 1 already in your contacts". */
    val countLine: String = "",
    val rows: ImmutableList<IntakeRow> = persistentListOf(),
    /** The label of the main button: "Add 2 Contacts". */
    val addLabel: String = "Add Contacts",
    /** A single card can also go into a contact that exists ("Add to Existing Contact"). */
    val canAddToExisting: Boolean = false,
    val preview: CardPreview? = null,
) {
    val canAdd: Boolean get() = phase == IntakePhase.Preview && rows.isNotEmpty()
}

sealed interface IntakeAction {
    data object Add : IntakeAction

    data object Cancel : IntakeAction

    data class OpenCard(
        val index: Int,
    ) : IntakeAction

    data object CloseCard : IntakeAction

    data object AddToExisting : IntakeAction

    data class PickExisting(
        val id: Long,
    ) : IntakeAction
}

sealed interface IntakeEffect {
    data class Toast(
        val text: String,
    ) : IntakeEffect

    /** The pill "Added 12 contacts" with Undo; [undo] answers the toast that follows it. */
    class Undo(
        val message: String,
        val undo: suspend () -> String,
    ) : IntakeEffect

    /** The contacts a single card can be added to. */
    data class ChooseExisting(
        val people: ImmutableList<PersonItem>,
    ) : IntakeEffect

    /** Plain text that is only a number or an address opens the editor with it. */
    data class OpenEditor(
        val seed: DraftSeed,
    ) : IntakeEffect

    /** The page is finished: it pops. Sent once. */
    data object Close : IntakeEffect
}
