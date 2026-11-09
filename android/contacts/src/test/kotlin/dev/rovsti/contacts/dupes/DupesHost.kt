package dev.rovsti.contacts.dupes

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
import dev.rovsti.contacts.intake.PreviewLine
import dev.rovsti.contacts.intake.PreviewSection
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Holds the page state like the view model would and records what the screen asks for. */
internal class DupesHarness(
    initial: DupesUiState,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<DupesAction>()
    var backs = 0
    val overlays = OverlayController()

    fun on(action: DupesAction) {
        actions += action
        if (action == DupesAction.CloseReview) ui = ui.copy(review = null)
    }
}

internal val SampleReview =
    MergeReview(
        key = 1,
        name = "Ayşe Yılmaz",
        subtitle = "Acme Yazılım",
        photoUri = "content://contacts/photo/1",
        sources = "From Ayşe Yılmaz, A. Yılmaz, Ayşe",
        sections =
            persistentListOf(
                PreviewSection(
                    "Phone",
                    persistentListOf(
                        PreviewLine("Mobile", "+90 555 111 22 33"),
                        PreviewLine("Work", "0212 000 00 01"),
                    ),
                ),
                PreviewSection("Email", persistentListOf(PreviewLine("Work", "ayse@acme.example"))),
                PreviewSection("Notes", persistentListOf(PreviewLine("Notes", "Met at the 2023 conference."))),
            ),
        mergeLabel = "Merge 3 Contacts",
    )

internal val SampleDupes =
    DupesUiState(
        phase = DupesPhase.Ready,
        summary = "4 possible duplicates",
        rows =
            listOf(
                DupeRow(1, "Ayşe Yılmaz", "and 2 more · Same number", "content://contacts/photo/1"),
                DupeRow(4, "Burak Demir", "and 1 more · Same number", null),
                DupeRow(6, "Elif Kaya", "and 1 more · Same name", null),
                DupeRow(9, "Mehmet Öz", "and 3 more · Same email", "content://contacts/photo/9"),
            ).toImmutableList(),
    )

/** The duplicates page pushed over the Settings tab in the real shell (tab bar hidden, as in the app). */
@Composable
internal fun HostedDupes(harness: DupesHarness) {
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots) }
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    LaunchedEffect(Unit) { navigator.push(ContactsRoute.Duplicates) }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = ::chromeFor,
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<ContactsRoute.ContactsList> {}
        entry<ContactsRoute.Favorites> {}
        entry<ContactsRoute.Groups> {}
        entry<ContactsRoute.Settings> {}
        entry<ContactsRoute.Duplicates> {
            DupesScreen(
                state = harness.ui,
                onAction = harness::on,
                onBack = { harness.backs++ },
                overlays = harness.overlays,
            )
        }
    }
}

/** [HostedDupes] with the model's effects wired to the overlays, fed from [effects]. */
@Composable
internal fun HostedDupesWithEffects(
    harness: DupesHarness,
    effects: kotlinx.coroutines.flow.Flow<DupesEffect>,
) {
    DupesEffects(effects, harness.overlays, harness::on)
    HostedDupes(harness)
}
