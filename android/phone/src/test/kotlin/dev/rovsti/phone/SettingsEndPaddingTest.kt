package dev.rovsti.phone

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.rovsti.phone.settings.blocked.BlockedContent
import dev.rovsti.phone.settings.blocked.BlockedSample
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * F37: every settings page ends 16 dp above the floating tab bar. The last card scrolls fully clear of the bar, never
 * under it (the owner's complaint "the tab bar covers the last row").
 */
@Config(sdk = [35])
class SettingsEndPaddingTest : ShotTest() {
    private fun assertLastClearOfBar(
        route: PhoneRoute,
        pageTag: String,
        lastTag: String,
        fontScale: Float = 1f,
        page: @androidx.compose.runtime.Composable () -> Unit = {},
    ) {
        composeFrame(
            fontScale = fontScale,
        ) { HostedPhone(PhoneHarness(), PhoneSample.setup(), push = route, extra = page) }
        advance(900)
        compose.mainClock.autoAdvance = true
        // To the very end of the page (a scroll-to only brings the row into view, which says nothing about the padding).
        compose.onNodeWithTag(pageTag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, END_PX)
        }
        compose.mainClock.autoAdvance = false
        advance(900)
        val last = compose.onNodeWithTag(lastTag, useUnmergedTree = true).getBoundsInRoot()
        val bar = compose.onNodeWithTag("tab-bar", useUnmergedTree = true).getBoundsInRoot()
        compose.onNodeWithTag(lastTag, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "$route: the last row ends at ${last.bottom} but the bar starts at ${bar.top} (needs 16 dp between)",
            last.bottom <= bar.top - 16.dp + TOLERANCE,
        )
    }

    @Test fun settingsEndsClearOfTheBar() =
        assertLastClearOfBar(PhoneRoute.Settings, PhoneTags.SettingsPage, PhoneTags.VersionRow)

    @Test fun settingsEndsClearOfTheBarAtTwiceTheFontSize() =
        assertLastClearOfBar(PhoneRoute.Settings, PhoneTags.SettingsPage, PhoneTags.VersionRow, fontScale = 2f)

    @Test fun blockedEndsClearOfTheBar() =
        assertLastClearOfBar(
            PhoneRoute.Blocked,
            dev.rovsti.phone.settings.blocked.BlockedTags.Page,
            dev.rovsti.phone.settings.blocked.BlockedTags
                .call(BlockedSample.calls.last().key),
        ) {
            BlockedContent(BlockedSample.ui(), onAction = {}, onPaste = {}, onPrefix = {}, onImport = {})
        }

    private companion object {
        val TOLERANCE = 0.5.dp
        const val END_PX = 100_000f
    }
}
