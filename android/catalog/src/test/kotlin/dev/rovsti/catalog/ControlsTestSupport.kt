package dev.rovsti.catalog

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue

/** Re-reads the bounds of a tagged node in dp, to compare before and after a press. */
internal fun ShotTest.boundsOf(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

internal fun SemanticsNodeInteraction.heightDp(): Float = getUnclippedBoundsInRoot().height.value

internal fun SemanticsNodeInteraction.widthDp(): Float = getUnclippedBoundsInRoot().width.value

/** A glass draw recorded under [tag] that used the RenderEffect (the real path, not the plain fill). */
internal fun assertThumbDrewRealGlass(tag: String) {
    val records = BackdropDebug.snapshot().filter { it.tag == tag }
    assertTrue(
        "nothing tagged '$tag' drew; saw ${BackdropDebug.snapshot().mapNotNull { it.tag }.distinct()}",
        records.isNotEmpty(),
    )
    assertTrue("'$tag' drew without a RenderEffect: $records", records.all { it.hasRenderEffect })
}
