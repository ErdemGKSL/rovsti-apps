package dev.rosty.catalog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.media.PhotoViewerSemantics
import dev.rosty.design.media.PhotoViewerTags
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.testing.RecordingHaptics
import dev.rosty.testing.ShotTest

/** Composes the Photos page inside an overlay host; the viewer opens on top of it. */
internal fun ShotTest.openPhotosPage(dark: Boolean = false) {
    composeFrame(dark = dark) {
        CompositionLocalProvider(LocalRostyHaptics provides RecordingHaptics()) {
            OverlayHost(remember { OverlayController() }) { CatalogPageFrame { PhotosPage() } }
        }
    }
    advance(400)
}

internal fun ShotTest.viewerNode(): SemanticsNode = compose.onNodeWithTag(PhotoViewerTags.Root).fetchSemanticsNode()

internal fun ShotTest.viewerZoom(): Float = viewerNode().config[PhotoViewerSemantics.Zoom]

internal fun ShotTest.viewerDim(): Float = viewerNode().config[PhotoViewerSemantics.Dim]

internal fun ShotTest.viewerPage(): Int = viewerNode().config[PhotoViewerSemantics.Page]

internal fun ShotTest.viewerChrome(): Boolean = viewerNode().config[PhotoViewerSemantics.ChromeShown]

internal fun ShotTest.viewerOpen(): Boolean = compose.onAllNodesWithTagCount(PhotoViewerTags.Root) > 0

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
    onAllNodes(
        androidx.compose.ui.test
            .hasTestTag(tag),
    ).fetchSemanticsNodes().size

internal fun SemanticsNode.descriptionOrNull(): String? =
    config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull()
