// Rosty Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rosty.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.RowImage
import dev.rosty.design.media.ViewerAction
import dev.rosty.design.media.showPhotoViewer
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

private val GridGap = 2.dp
private const val Columns = 3

/** E1.20: a 3-column grid of seven generated photos; a tap opens the full-screen viewer out of the tapped cell. */
@Composable
internal fun PhotosPage() {
    val overlays = LocalOverlay.current
    var log by remember { mutableStateOf("Tap a photo.") }
    val bounds = remember { HashMap<Int, Rect>() }
    val photos = remember { (1..CatalogPhotoCount).map { catalogPhotoModel(it) }.toPersistentList<Any?>() }
    val actions =
        remember {
            persistentListOf(
                ViewerAction("save", RostyIcons.Download, "Save") { log = "Save photo ${it + 1}" },
                ViewerAction("share", RostyIcons.Share, "Share") { log = "Share photo ${it + 1}" },
                ViewerAction("forward", RostyIcons.Forward, "Forward") { log = "Forward photo ${it + 1}" },
                ViewerAction("edit", RostyIcons.Pencil, "Edit") { log = "Edit photo ${it + 1}" },
                ViewerAction("remove", RostyIcons.Trash, "Remove") { log = "Remove photo ${it + 1}" },
            )
        }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader(CatalogPage.Photos.title, "Thumbnails decode at their own pixels; a tap opens the viewer.")
        RostyText(
            log,
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.m).testTag("photos-log"),
        )
        BoxWithConstraints(Modifier.padding(horizontal = Spacing.gutter)) {
            val cell = (maxWidth - GridGap * (Columns - 1)) / Columns
            Column(verticalArrangement = Arrangement.spacedBy(GridGap)) {
                for (row in photos.indices.chunked(Columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                        for (index in row) {
                            RowImage(
                                model = photos[index],
                                size = cell,
                                shape = RoundedCornerShape(0.dp),
                                modifier =
                                    Modifier
                                        .testTag("photo-cell-${index + 1}")
                                        .semantics { contentDescription = "Photo ${index + 1}" }
                                        .onGloballyPositioned { bounds[index] = it.boundsInWindow() }
                                        .clickable(role = Role.Button) {
                                            overlays.showPhotoViewer(
                                                photos = photos,
                                                initialPage = index,
                                                actions = actions,
                                                anchorFor = { bounds[it] },
                                            )
                                        },
                            )
                        }
                    }
                }
            }
        }
    }
}
