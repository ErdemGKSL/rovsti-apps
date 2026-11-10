package dev.rovsti.contacts.intake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.chromeFor
import dev.rovsti.contacts.contactsTabItems
import dev.rovsti.contacts.en
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.UiText
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.toImmutableList

/** Holds the page state like the view model would and records what the screen asks for. */
internal class IntakeHarness(
    initial: IntakeUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<IntakeAction>()
    val overlays = OverlayController()

    fun on(action: IntakeAction) {
        actions += action
    }
}

/** A round picture in a flat colour: what a card's PHOTO decodes to. */
internal fun portrait(color: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(color)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.argb(200, 255, 255, 255)
    canvas.drawCircle(48f, 38f, 18f, paint)
    canvas.drawCircle(48f, 100f, 40f, paint)
    return bitmap.asImageBitmap()
}

internal fun sampleRows(count: Int = 9): List<IntakeRow> {
    val people =
        listOf(
            Triple("Ada Lovelace", "+44 20 7946 0958", Color.rgb(70, 110, 190)),
            Triple("Grace Hopper", "+1 415 555 0100", null),
            Triple("Katherine Johnson", "+1 304 555 0111", Color.rgb(190, 110, 70)),
            Triple("Alan Turing", "+44 20 7946 0123", null),
            Triple("Margaret Hamilton", "+1 617 555 0142", null),
            Triple("Hedy Lamarr", "+43 1 555 0100", Color.rgb(90, 150, 110)),
            Triple("Dennis Ritchie", "+1 908 555 0166", null),
            Triple("Barbara Liskov", "+1 617 555 0177", null),
            Triple("Linus Torvalds", "+358 9 555 0188", null),
        )
    return people.take(count).mapIndexed { i, (name, number, tint) ->
        IntakeRow(i, name, number, tint?.let(::portrait))
    }
}

internal fun previewState(
    count: Int = 9,
    duplicates: Int = 2,
    single: Boolean = false,
) = IntakeUiState(
    phase = IntakePhase.Preview,
    countLine =
        UiText.Literal(
            (if (count == 1) "1 new contact" else "$count new contacts") +
                if (duplicates > 0) ", $duplicates already in your contacts" else "",
        ),
    rows = sampleRows(count).toImmutableList(),
    addLabel = UiText.Literal(if (count == 1) "Add 1 Contact" else "Add $count Contacts"),
    canAddToExisting = single,
)

/** The import page pushed over the Contacts tab in the real shell (tab bar hidden, as in the app). */
@Composable
internal fun HostedIntake(harness: IntakeHarness) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots) }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    LaunchedEffect(Unit) { navigator.push(ContactsRoute.Intake("content://x/people.vcf")) }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { chromeFor(it, UiText::en) },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<ContactsRoute.ContactsList> {}
        entry<ContactsRoute.Favorites> {}
        entry<ContactsRoute.Groups> {}
        entry<ContactsRoute.Settings> {}
        entry<ContactsRoute.Intake> { IntakeScreen(harness.ui, harness::on) }
    }
}

/** The mapping sheet of a six-column file (the columns of an address book export from a German phone). */
internal fun mappingState(found: Int = 12): IntakeUiState {
    val columns =
        listOf(
            Triple("Vorname", "Ada", CsvRole.FirstName),
            Triple("Nachname", "Lovelace", CsvRole.LastName),
            Triple("Handy", "+44 20 7946 0958", CsvRole.Phone),
            Triple("Mail", "ada@example.org", null),
            Triple("Firma", "Analytical Engines", null),
            Triple("Notiz", "First programmer", CsvRole.Ignore),
        )
    return IntakeUiState(
        phase = IntakePhase.Mapping,
        mapping =
            CsvMappingUi(
                columns =
                    columns
                        .mapIndexed { i, (name, sample, role) ->
                            CsvColumn(i, name, sample, detected = null, chosen = role)
                        }.toImmutableList(),
                countLine =
                    UiText.Literal(
                        when (found) {
                            0 -> "No contacts found with these columns"
                            1 -> "1 contact found"
                            else -> "$found contacts found"
                        },
                    ),
                canContinue = found > 0,
            ),
    )
}
