package dev.rosty.shell.permissions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithText
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.SpecialKind
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** A special-access row keeps the shared words by default and takes an app's own words when given. */
@Config(sdk = [35])
class SpecialRowTextsTest : ShotTest() {
    private val special = FakeSpecialAccess()

    private fun row(texts: SpecialRowTexts?) {
        composeFrame {
            CompositionLocalProvider(LocalSpecialAccess provides special) {
                InsetGroupedSection { row { SpecialAccessRow(SpecialKind.FullScreenIntent, texts = texts) } }
            }
        }
        advance(300)
    }

    @Test fun theSharedWordsStayByDefault() {
        special.fullScreenIntent = true
        row(null)
        compose.onNodeWithText("Full-screen calls").assertExists()
        compose.onNodeWithText("Calls can take over the screen").assertExists()
    }

    @Test fun anAppNamesItsOwnAccess() {
        special.fullScreenIntent = false
        row(
            SpecialRowTexts(
                "Full-screen alarm",
                "The alarm can take over the screen",
                "The alarm shows as a banner only",
            ),
        )
        compose.onNodeWithText("Full-screen alarm").assertExists()
        compose.onNodeWithText("The alarm shows as a banner only").assertExists()
        compose.onNodeWithText("Full-screen calls").assertDoesNotExist()
    }
}
