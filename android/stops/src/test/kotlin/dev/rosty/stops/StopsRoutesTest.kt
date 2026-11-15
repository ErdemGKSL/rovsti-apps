package dev.rosty.stops

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.saveable.SaverScope
import dev.rosty.design.transition.TabBarMode
import dev.rosty.design.transition.TabNavigator
import dev.rosty.platform.contacts.Shortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StopsRoutesTest {
    private fun extras(block: Bundle.() -> Unit) = Bundle().apply(block)

    private val home = TripTab to emptyList<StopsRoute>()

    @Test fun aStopShortcutOpensThatStop() {
        val e = extras { putLong(Shortcuts.EXTRA_STOP_ID, 7L) }
        assertEquals(StopsLink.Stop(7L), stopsLinkFor(Intent.ACTION_MAIN, null, e))
        assertEquals(home, stopsRouteFor(Intent.ACTION_MAIN, null, e))
        // The extra name is the one the GPUI shortcuts wrote.
        assertEquals("dev.rosty.stops.STOP_ID", Shortcuts.EXTRA_STOP_ID)
    }

    @Test fun aShortcutWinsOverTheIntentData() {
        val e = extras { putLong(Shortcuts.EXTRA_STOP_ID, 2L) }
        assertEquals(StopsLink.Stop(2L), stopsLinkFor(Intent.ACTION_VIEW, "geo:41,29", e))
    }

    @Test fun anIntStopIdIsReadAndABadOneIsNoLink() {
        assertEquals(StopsLink.Stop(3L), stopsLinkFor(null, null, extras { putInt(Shortcuts.EXTRA_STOP_ID, 3) }))
        assertNull(stopsLinkFor(null, null, extras { putLong(Shortcuts.EXTRA_STOP_ID, 0L) }))
        assertNull(stopsLinkFor(null, null, extras { putString(Shortcuts.EXTRA_STOP_ID, "7") }))
    }

    @Test fun geoUrisAndMapLinksAreAPlace() {
        for (uri in listOf(
            "geo:41.0,29.0",
            "GEO:41.0,29.0?q=41.0,29.0(Pier)",
            "https://maps.google.com/?q=41.0,29.0",
            "https://www.google.com/maps/place/41.0,29.0",
            "https://www.openstreetmap.org/#map=16/41.0/29.0",
            "https://maps.apple.com/?ll=41.0,29.0",
            "HTTPS://Maps.Apple.Com/?ll=41.0,29.0",
        )) {
            assertEquals(uri, StopsLink.Place(uri), stopsLinkFor(Intent.ACTION_VIEW, uri, null))
            assertEquals(uri, home, stopsRouteFor(Intent.ACTION_VIEW, uri, null))
        }
    }

    @Test fun otherLinksAreNotForUs() {
        for (uri in listOf(
            "https://www.google.com/search?q=maps",
            "http://maps.google.com/?q=1,2",
            "https://example.org/maps",
            "https://maps.google.com.evil.example/x",
            "content://x/y",
            "https://",
            "",
        )) {
            assertNull(uri, stopsLinkFor(Intent.ACTION_VIEW, uri, null))
            assertFalse(uri, isMapUri(uri))
        }
        assertNull(stopsLinkFor(Intent.ACTION_VIEW, null, null))
    }

    @Test fun sharedTextIsAPlaceAndBlankTextIsNothing() {
        val text = "Pier West https://maps.google.com/?q=41.0,29.0"
        assertEquals(
            StopsLink.Place(text),
            stopsLinkFor(Intent.ACTION_SEND, null, extras { putString(Intent.EXTRA_TEXT, "  $text\n") }),
        )
        assertNull(stopsLinkFor(Intent.ACTION_SEND, null, extras { putString(Intent.EXTRA_TEXT, "   ") }))
        assertNull(stopsLinkFor(Intent.ACTION_SEND, null, null))
    }

    @Test fun theLauncherAndOtherActionsLeaveTheStackAlone() {
        assertNull(stopsRouteFor(Intent.ACTION_MAIN, null, null))
        assertNull(stopsRouteFor(null, null, Bundle()))
        assertNull(stopsRouteFor("dev.rosty.stops.SOMETHING", "geo:1,2", null))
    }

    @Test fun theMapDrawsItsOwnBarAndPushedPagesKeepTheFullOne() {
        assertEquals(TabBarMode.Hidden, chromeFor(StopsRoute.Home).tabBar)
        val pushed =
            listOf(
                StopsRoute.Settings,
                StopsRoute.Appearance,
                StopsRoute.About,
                StopsRoute.Licences,
                StopsRoute.LicenceDetail("x"),
                StopsRoute.Logs(),
            )
        for (route in pushed) {
            assertEquals(route.toString(), TabBarMode.Visible, chromeFor(route).tabBar)
            assertEquals(HomeTab.Settings, selectedHomeTab(route))
        }
        assertEquals(HomeTab.Trip, selectedHomeTab(StopsRoute.Home))
    }

    @Test fun barTapsOnAPushedPage() {
        assertEquals(BarTap.ToMap, barTap("trip"))
        assertEquals(BarTap.ToSaved, barTap("saved"))
        assertEquals(BarTap.ToSettings, barTap("settings"))
        assertNull(barTap("other"))
    }

    @Test fun theOneStackIsTheMapAndItSurvivesARestore() {
        val spec = dev.rosty.shell.NavSpec(StopsTabs, StopsRoots, StopsRoute.serializer())
        val nav = spec.create()
        assertEquals(StopsRoute.Home, nav.current)
        nav.push(StopsRoute.Settings)
        nav.push(StopsRoute.Logs("sync"))
        val saver = TabNavigator.saver(StopsTabs, StopsRoots, StopsRoute.serializer())
        val text = with(saver) { SaverScope { true }.save(nav) }!!
        val back = saver.restore(text)!!
        assertEquals(listOf(StopsRoute.Home, StopsRoute.Settings, StopsRoute.Logs("sync")), back.stack(TripTab))
    }

    @Test fun theHomeTabsAreTheGpuiOnes() {
        assertEquals(listOf("trip", "saved", "settings"), HomeTab.entries.map { it.id })
        assertTrue(StopsTabs.single() == TripTab)
    }
}
