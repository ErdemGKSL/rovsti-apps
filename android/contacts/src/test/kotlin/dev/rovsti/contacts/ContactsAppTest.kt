package dev.rovsti.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [35])
class ContactsAppTest : ShotTest() {
    @Test fun grantedBookShowsTheEmptyStateWithItsSentence() {
        val fixture = AppFixture(granted = true)
        composeFrame { fixture.Content() }
        compose.onNodeWithTag(ContactsTags.EmptyBook).assertIsDisplayed()
        compose.onNodeWithText("No Contacts").assertIsDisplayed()
        compose.onNodeWithText("Tap + to add someone, or import a .vcf file in Settings.").assertIsDisplayed()
    }

    @Test fun deniedBookShowsThePermissionRowAndNoEmptyState() {
        val fixture = AppFixture(granted = false)
        composeFrame { fixture.Content() }
        advance(900)
        compose.onNodeWithTag(ContactsTags.PermissionSection, useUnmergedTree = true).assertExists()
        assertEquals(0, compose.onAllNodes(hasText("No Contacts")).fetchSemanticsNodes().size)
    }

    @Test fun theBarHasTheFourTabsAndSwitching() {
        val fixture = AppFixture(granted = true)
        composeFrame { fixture.Content() }
        for (label in listOf("Contacts", "Favorites", "Groups", "Settings")) {
            assertEquals(
                label,
                1,
                compose
                    .onAllNodes(
                        hasText(label) and
                            androidx.compose.ui.test
                                .hasClickAction(),
                    ).fetchSemanticsNodes()
                    .size
                    .coerceAtLeast(1),
            )
        }
        compose.onNodeWithText("Settings").performClick()
        advance(600)
        assertEquals(SettingsTab, fixture.navigator.currentTab)
        compose.onNodeWithTag(ContactsTags.SettingsPage).assertIsDisplayed()
        compose.onNodeWithText("WhatsApp").assertIsDisplayed()
    }

    @Test fun contactsKeepsNoAppearanceOrHapticsRow() {
        val fixture = AppFixture(granted = true)
        fixture.navigator.select(SettingsTab)
        composeFrame { fixture.Content() }
        compose.onNodeWithText("About").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Appearance")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Haptics")).fetchSemanticsNodes().size)
    }

    @Test fun aRestoredCardRouteHasAPage() {
        val fixture = AppFixture(granted = true)
        fixture.navigator.resetTo(ContactsTab, listOf(ContactsRoute.ContactCard(5L)))
        composeFrame { fixture.Content() }
        advance(600)
        compose.onNodeWithTag("scaffold-list", useUnmergedTree = true).assertExists()
    }
}
