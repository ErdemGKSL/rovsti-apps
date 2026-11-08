package dev.rovsti.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.ios.component.presentPageSheet
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

private val StopNames =
    listOf(
        "Kadikoy Iskele",
        "Moda Caddesi",
        "Bahariye",
        "Altiyol",
        "Fenerbahce",
        "Goztepe",
        "Suadiye",
        "Bostanci",
        "Caddebostan",
        "Acibadem",
        "Uskudar Meydan",
        "Besiktas Iskele",
        "Taksim",
        "Sisli",
        "Levent",
        "Maslak",
        "Yenikapi",
        "Sirkeci",
        "Eminonu",
        "Karakoy",
    )

private val Names =
    listOf(
        "Ayla Demir",
        "Bora Kaya",
        "Cem Yilmaz",
        "Deniz Arslan",
        "Ece Sahin",
        "Furkan Celik",
        "Gul Ozturk",
        "Hakan Aydin",
        "Irem Kurt",
        "Kerem Polat",
        "Leyla Tas",
        "Mert Koc",
        "Naz Erdem",
        "Onur Aksoy",
        "Pelin Yildiz",
        "Riza Gunes",
        "Selin Bulut",
        "Tolga Acar",
        "Umut Avci",
        "Vildan Korkmaz",
    )

/** The title line of a sheet: a headline and a Done action. */
@Composable
private fun SheetHeader(
    title: String,
    sheet: SheetState,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RovstiText(title, style = RovstiTheme.type.title3.copy(fontWeight = FontWeight.SemiBold))
        RovstiText(
            "Done",
            style = RovstiTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
            color = RovstiTheme.colors.accent,
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button) { sheet.dismiss() }
                    .padding(horizontal = Spacing.s, vertical = Spacing.m)
                    .testTag("sheet-done"),
        )
    }
}

@Composable
private fun ColumnScope.RecentsBody(
    title: String,
    sheet: SheetState,
    names: List<String> = Names,
    secondary: String = "mobile",
) {
    SheetHeader(title, sheet)
    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("sheet-list")) {
        items(names, key = { it }) { name ->
            ListRow(title = name, secondary = secondary, modifier = Modifier.testTag("sheet-row-$name"))
        }
    }
}

/** E1.13: modal sheets at each detent, a stacked pair, and a non-modal sheet over a page that stays alive. */
@Composable
internal fun SheetsPage() {
    val controller = LocalOverlay.current
    var taps by remember { mutableIntStateOf(0) }
    var striped by remember { mutableStateOf(false) }

    fun modal(
        detent: Detent,
        title: String,
    ) {
        controller.present(
            SheetEntry(
                title = title,
                detents = persistentListOf(Detent.Peek(), Detent.Medium, Detent.Large),
                initialDetent = detent,
            ) { sheet -> RecentsBody(title, sheet) },
        )
    }

    fun stacked() {
        controller.present(
            SheetEntry(
                title = "Recents",
                detents = persistentListOf(Detent.Medium, Detent.Large),
                initialDetent = Detent.Large,
            ) { sheet ->
                SheetHeader("Recents", sheet)
                Column(
                    Modifier.padding(horizontal = Spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    RovstiText(
                        "Tap the button to open a second sheet on top of this one.",
                        style = RovstiTheme.type.subheadline,
                    )
                    CapsuleButton(
                        text = "Open second sheet",
                        onClick = {
                            controller.present(
                                SheetEntry(title = "Details", detents = persistentListOf(Detent.Medium)) { inner ->
                                    SheetHeader("Details", inner)
                                    RovstiText(
                                        "The page stays pushed back until this and the sheet below have both closed.",
                                        style = RovstiTheme.type.subheadline,
                                        modifier = Modifier.padding(horizontal = Spacing.gutter),
                                    )
                                },
                            )
                        },
                        modifier = Modifier.testTag("sheet-open-second"),
                    )
                }
            },
        )
    }

    fun nonModal() {
        striped = true
        controller.present(
            SheetEntry(
                title = "Place",
                detents = persistentListOf(Detent.Peek(), Detent.Medium, Detent.Large),
                modal = false,
                onDismissed = { striped = false },
            ) { sheet -> RecentsBody("Nearby stops", sheet, StopNames, "bus stop") },
        )
    }

    fun pageSheet() {
        controller.presentPageSheet("New list") {
            RovstiText(
                "A full-screen page sheet: edge to edge, ten points under the status bar.",
                style = RovstiTheme.type.subheadline,
                modifier = Modifier.padding(top = Spacing.s, start = Spacing.gutter, end = Spacing.gutter),
            )
            // F7: the row sits in an inset-grouped card, like every other row on a page (one 16 dp gutter).
            InsetGroupedSection(Modifier.padding(top = Spacing.m)) {
                row {
                    ListRow(
                        title = "Name",
                        secondary = "Weekend stops",
                        modifier = Modifier.testTag("page-sheet-row"),
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (striped) StripedBackdrop()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly()),
        ) {
            PageHeader(
                "Sheets",
                "Drag the grabber, fling, or scroll the list; the page steps back behind modal sheets.",
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                CapsuleButton("Peek sheet", { modal(Detent.Peek(), "Recents") }, Modifier.testTag("sheet-peek"))
                CapsuleButton("Medium sheet", { modal(Detent.Medium, "Recents") }, Modifier.testTag("sheet-medium"))
                CapsuleButton("Large sheet", { modal(Detent.Large, "Recents") }, Modifier.testTag("sheet-large"))
                CapsuleButton("Stacked sheets", ::stacked, Modifier.testTag("sheet-stacked"))
                CapsuleButton("Non-modal sheet", ::nonModal, Modifier.testTag("sheet-nonmodal"))
                CapsuleButton("Page sheet", ::pageSheet, Modifier.testTag("sheet-page"))
                CapsuleButton(
                    text = "Page taps: $taps",
                    onClick = { taps++ },
                    modifier = Modifier.testTag("page-tap"),
                )
            }
        }
    }
}
