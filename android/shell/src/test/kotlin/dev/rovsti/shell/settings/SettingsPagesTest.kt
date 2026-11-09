package dev.rovsti.shell.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.design.haptics.NoHaptics
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** Compose UI tests of the common settings pages: they write to the settings and show what they should. */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class SettingsPagesTest : ShotTest() {
    @Test fun choosingDarkWritesAppearanceAndMovesTheSegment() {
        val settings = FakeCommonSettings()
        composeFrame {
            HostedPage {
                val value by settings.appearance.collectAsState()
                AppearancePage(selected = value ?: Appearance.Auto, onSelect = settings::setAppearance)
            }
        }
        advance(300)
        compose.onNodeWithText("Dark").performClick()
        advance(600)
        assertEquals(Appearance.Dark, settings.appearance.value)
        assertEquals(listOf<Any>(Appearance.Dark), settings.writes)
    }

    @Test fun choosingALevelWritesItAndPlaysThePreviewExceptForOff() {
        val settings = FakeCommonSettings(haptics = HapticsLevel.Normal)
        val haptics = RecordingHaptics()
        composeFrame {
            HostedPage {
                val level by settings.haptics.collectAsState()
                HapticsPage(selected = level ?: HapticsLevel.Normal, onSelect = settings::setHaptics, haptics = haptics)
            }
        }
        advance(300)
        compose.onNodeWithTag(hapticsRowTag(HapticsLevel.Light), useUnmergedTree = true).performClick()
        advance(300)
        assertEquals(HapticsLevel.Light, settings.haptics.value)
        assertEquals(listOf(HapticsPreviewEvent), haptics.events)
        compose.onNodeWithTag(hapticsRowTag(HapticsLevel.Off), useUnmergedTree = true).performClick()
        advance(300)
        assertEquals(HapticsLevel.Off, settings.haptics.value)
        assertEquals("Off is silent", 1, haptics.events.size)
    }

    @Test fun theTryItRowPlaysOnlyWhenHapticsAreOn() {
        val haptics = RecordingHaptics()
        composeFrame { HostedPage { HapticsPage(selected = HapticsLevel.Off, onSelect = {}, haptics = haptics) } }
        advance(300)
        compose.onNodeWithText("Try it").performClick()
        advance(200)
        assertTrue(haptics.events.isEmpty())
        composeFrame { HostedPage { HapticsPage(selected = HapticsLevel.Normal, onSelect = {}, haptics = haptics) } }
        advance(300)
        compose.onNodeWithText("Try it").performClick()
        advance(200)
        assertEquals(1, haptics.events.size)
    }

    @Test fun aMissingPreferenceHidesItsRow() {
        val opened = mutableListOf<CommonSettingsPage>()
        composeFrame {
            HostedPage {
                SettingsPage(title = "Settings") {
                    CommonSettingsGroup(
                        FakeCommonSettings(appearance = null, haptics = HapticsLevel.Light),
                        opened::add,
                    )
                }
            }
        }
        advance(300)
        compose.onAllNodesWithText("Appearance").assertCountEquals(0)
        compose.onNodeWithText("Haptics").assertIsDisplayed()
        compose.onNodeWithText("Light").assertIsDisplayed()
        compose.onNodeWithText("About").performClick()
        advance(200)
        assertEquals(listOf(CommonSettingsPage.About), opened)
    }

    @Test fun aboutShowsVersionBuildAndCoreAndOpensTheLicences() {
        var opened = 0
        composeFrame { HostedPage { AboutPage(SampleAbout, onOpenLicences = { opened++ }) } }
        advance(300)
        compose.onNodeWithText("v0.9.0 • 42").assertIsDisplayed()
        compose.onNodeWithText("0.3.1").assertIsDisplayed()
        compose.onNodeWithText("Nov 9, 2026").assertIsDisplayed()
        compose.onNodeWithText("Licences").performClick()
        advance(200)
        assertEquals(1, opened)
    }

    @Test fun aboutSaysWhenTheCoreVersionIsMissing() {
        composeFrame { HostedPage { AboutPage(SampleAbout.copy(coreVersion = null), onOpenLicences = {}) } }
        advance(300)
        compose.onNodeWithText("Not loaded").assertIsDisplayed()
    }

    @Test fun theLicenceListOpensAProjectAndTheDetailShowsItsText() {
        val data = realLicenceData()
        var open by mutableStateOf<String?>(null)
        composeFrame {
            HostedPage {
                val id = open
                if (id == null) {
                    LicencesPage(data, onOpen = { open = it })
                } else {
                    LicenceDetailPage(id, data)
                }
            }
        }
        advance(300)
        compose.onNodeWithText("Rovsti").assertIsDisplayed()
        compose.onNodeWithText("glisse").performClick()
        advance(400)
        assertEquals("glisse", open)
        compose.onNode(hasText("MIT License", substring = true)).assertIsDisplayed()
    }

    @Test fun theLastLicenceRowClearsTheBottomInset() {
        composeFrame { HostedPage { LicencesPage(realLicenceData(), onOpen = {}) } }
        advance(300)
        val last =
            realLicenceData()
                .notices.projects
                .last()
                .name
        // Fling to the end with real swipes; scrollToNode would wait for an idle clock that never comes with a manual one.
        repeat(6) {
            compose.onNode(hasScrollAction()).performTouchInput {
                swipeUp(startY = height * 0.9f, endY = height * 0.1f, durationMillis = 100)
            }
            advance(600)
        }
        val bottom =
            compose
                .onNodeWithText(last)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val root =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("last row bottom $bottom of $root", root - bottom >= 48 * density)
    }

    @Test fun theOwnLicenceShowsTheGplText() {
        composeFrame { HostedPage { LicenceDetailPage(OwnLicenceId, realLicenceData()) } }
        advance(300)
        compose.onNodeWithText("Licence text").assertIsDisplayed()
        compose.onNode(hasText("GNU GENERAL PUBLIC LICENSE", substring = true)).assertIsDisplayed()
    }

    @Test fun theLoadingRowShowsUntilTheDataArrives() {
        composeFrame { HostedPage { LicencesPage(null, onOpen = {}) } }
        advance(300)
        compose.onNodeWithText("Loading licences").assertIsDisplayed()
    }

    @Test fun noHapticsIsTheDefaultPreview() {
        // The page works with the ambient NoHaptics (previews, catalog): nothing throws.
        composeFrame { HostedPage { HapticsPage(selected = HapticsLevel.Light, onSelect = {}, haptics = NoHaptics) } }
        advance(200)
        compose.onNodeWithText("Try it").performClick()
    }
}
