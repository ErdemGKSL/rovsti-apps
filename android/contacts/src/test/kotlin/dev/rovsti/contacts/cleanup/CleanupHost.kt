package dev.rovsti.contacts.cleanup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.chromeFor
import dev.rovsti.contacts.contactsTabItems
import dev.rovsti.contacts.en
import dev.rovsti.design.controls.ChipModel
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.UiText
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/** Holds the page state like the view model would and records what the screen asks for. */
internal class CleanupHarness(
    initial: CleanupUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<CleanupAction>()
    var backs = 0
    val overlays = OverlayController()

    fun on(action: CleanupAction) {
        actions += action
    }
}

internal val SampleChips =
    persistentListOf(
        ChipModel("NoNumber", "No number", 12),
        ChipModel("NoName", "No name", 3),
        ChipModel("NoPhoto", "No photo", 148),
        ChipModel("NoGroup", "Not in a group", 96),
    )

private val people =
    listOf(
        Triple("Elif Kaya", "elif.kaya@example.test", null),
        Triple("Can Aydın", "Kaya Ltd", "content://contacts/photo/8"),
        Triple("Deniz Korkmaz", "deniz@example.test", null),
        Triple("Zeynep Arslan", "Acme Yazılım", null),
        Triple("Mert Koç", null, "content://contacts/photo/22"),
        Triple("Selin Aktaş", "selin@example.test", null),
        Triple("Tolga Uçar", null, null),
        Triple("Ulaş Erdem", "Erdem & Co", null),
        Triple("Vedat Özer", null, null),
        Triple("Yasemin Kurt", "yasemin@example.test", null),
    )

internal fun sampleRows(count: Int = 10) =
    people
        .take(count)
        .mapIndexed { i, (name, secondary, photo) -> CleanupRow(i + 1L, name, secondary, photo) }
        .toImmutableList()

internal fun cleanupState(
    picked: Set<Long>? = null,
    filter: String = "NoNumber",
    rows: List<CleanupRow> = sampleRows(),
) = CleanupUiState(
    phase = CleanupPhase.Ready,
    chips = SampleChips,
    filter = filter,
    emptyText = "Every contact has a number",
    rows = rows.toImmutableList(),
    select =
        picked?.let {
            CleanupSelect(
                selected = it.toImmutableSet(),
                title = UiText.Literal(if (it.isEmpty()) "Select contacts" else "${it.size} selected"),
                allSelected = it.size == rows.size && rows.isNotEmpty(),
                canDelete = it.isNotEmpty(),
            )
        },
)

/** The Clean up page pushed over the Settings tab in the real shell (tab bar hidden, as in the app). */
@Composable
internal fun HostedCleanup(harness: CleanupHarness) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots) }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    LaunchedEffect(Unit) { navigator.push(ContactsRoute.Cleanup) }
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
        entry<ContactsRoute.Cleanup> {
            CleanupScreen(state = harness.ui, onAction = harness::on, onBack = { harness.backs++ })
        }
    }
}

/** [HostedCleanup] with the model's effects wired to the overlays, fed from [effects]. */
@Composable
internal fun HostedCleanupWithEffects(
    harness: CleanupHarness,
    effects: kotlinx.coroutines.flow.Flow<CleanupEffect>,
) {
    CleanupEffects(effects, harness.overlays, harness::on)
    HostedCleanup(harness)
}
