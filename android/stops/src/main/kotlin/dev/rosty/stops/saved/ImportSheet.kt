package dev.rosty.stops.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.sheetScrollConnection
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet
import kotlinx.collections.immutable.persistentListOf

object ImportTags {
    const val Sheet = "places-import"
    const val Confirm = "places-import-confirm"
    const val Cancel = "places-import-cancel"
}

/**
 * S5: what an import would do ([summary]) and the stops it adds ([names]); Import adds them (with Undo after), Cancel
 * leaves the list alone. Nothing to add leaves only Cancel.
 */
fun OverlayController.presentImportSheet(
    title: String,
    summary: String,
    names: List<String>,
    onImport: () -> Unit,
): SheetEntry =
    SheetEntry(title = title, detents = persistentListOf(Detent.Medium, Detent.Large)) { sheet ->
        OnSheet {
            Column(
                Modifier
                    .fillMaxWidth()
                    .nestedScroll(sheetScrollConnection(sheet))
                    .verticalScroll(rememberScrollState())
                    .testTag(ImportTags.Sheet),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                Column(
                    Modifier.padding(horizontal = Spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    RostyText(title, style = RostyTheme.type.title2)
                    RostyText(summary, style = RostyTheme.type.body, color = RostyTheme.colors.labelSecondary)
                }
                if (names.isNotEmpty()) {
                    Column(
                        Modifier.padding(horizontal = Spacing.gutter),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        CapsuleButton(
                            stringResource(R.string.places_import_button),
                            {
                                sheet.dismiss()
                                onImport()
                            },
                            Modifier.fillMaxWidth().testTag(ImportTags.Confirm),
                            style = CapsuleStyle.Filled,
                            fillWidth = true,
                        )
                    }
                }
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    CapsuleButton(
                        stringResource(R.string.pick_cancel),
                        { sheet.dismiss() },
                        Modifier.fillMaxWidth().testTag(ImportTags.Cancel),
                        fillWidth = true,
                    )
                }
                if (names.isNotEmpty()) {
                    InsetGroupedSection(header = stringResource(R.string.places_import_list)) {
                        for (name in names) row { ListRow(name) }
                    }
                }
            }
        }
    }.also { present(it) }
