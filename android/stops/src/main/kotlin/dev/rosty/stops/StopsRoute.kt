package dev.rosty.stops

import android.content.Intent
import android.os.Bundle
import dev.rosty.design.transition.ChromeSpec
import dev.rosty.design.transition.Tab
import dev.rosty.design.transition.TabBarMode
import dev.rosty.platform.contacts.Shortcuts
import kotlinx.serialization.Serializable

/**
 * Every page of Rosty Stops (ARCH 5.1). The map home is the one root; everything else is pushed over it (Settings and
 * its sub-pages) or is a sheet over the map (Saved, History, the pick sheet), which is not a route: the map is the
 * full window in every state (ARCH 8.4).
 */
@Serializable
sealed interface StopsRoute {
    /** The full-window map with its floating chrome and the sheet host. */
    @Serializable
    data object Home : StopsRoute

    @Serializable
    data object Settings : StopsRoute

    /** Settings > Alarm: sound, vibration, pattern, volume, snooze, tests and reset (S12-S14, S39). */
    @Serializable
    data object AlarmSettings : StopsRoute

    /** Settings > Map tiles: the server, the cache and the offline area (S10, S22). */
    @Serializable
    data object MapTiles : StopsRoute

    // The common settings pages (shell CommonSettingsPage + the licence detail).
    @Serializable
    data object Appearance : StopsRoute

    @Serializable
    data object About : StopsRoute

    @Serializable
    data object Licences : StopsRoute

    @Serializable
    data class LicenceDetail(
        val id: String,
    ) : StopsRoute

    /** Developer mode's Logs page; [tag] preselects a tag. */
    @Serializable
    data class Logs(
        val tag: String? = null,
    ) : StopsRoute
}

/**
 * The navigator has one stack: the map. The GPUI tabs (Trip, Saved, Settings) are the home's own folded bar
 * ([HomeTab]): Trip is the map, Saved opens its sheet over the map and Settings pushes its page.
 */
internal val TripTab = Tab("trip")
internal val StopsTabs = listOf(TripTab)
internal val StopsRoots: Map<Tab, StopsRoute> = mapOf(TripTab to StopsRoute.Home)

/** The three items of the home's folded tab bar, in the GPUI order (`apps/stops/src/main.rs` `TABS`). */
enum class HomeTab(
    val id: String,
) {
    Trip("trip"),
    Saved("saved"),
    Settings("settings"),
}

/**
 * The chrome of each route (pure, `StopsRoutesTest`). The map draws its own folded bar (it rides the sheet), so the host
 * draws none there; Settings and its pages keep the full Trip / Saved / Settings bar with Settings selected (owner rule
 * F16), through [stopsTabSelection].
 */
fun chromeFor(route: StopsRoute): ChromeSpec =
    when (route) {
        StopsRoute.Home -> {
            ChromeSpec(TabBarMode.Hidden)
        }

        StopsRoute.Settings,
        StopsRoute.AlarmSettings,
        StopsRoute.MapTiles,
        StopsRoute.Appearance,
        StopsRoute.About,
        StopsRoute.Licences,
        is StopsRoute.LicenceDetail,
        is StopsRoute.Logs,
        -> {
            ChromeSpec(TabBarMode.Visible)
        }
    }

/** The bar item of [route] (pure): the map is Trip, every pushed page belongs to Settings. */
fun selectedHomeTab(route: StopsRoute): HomeTab = if (route == StopsRoute.Home) HomeTab.Trip else HomeTab.Settings

/** What a tap on a bar item of a pushed page does (pure); the caller runs it on the navigator. */
sealed interface BarTap {
    /** Back to the map. */
    data object ToMap : BarTap

    /** Back to the map with the Saved sheet open. */
    data object ToSaved : BarTap

    /** Back to the Settings page (from one of its sub-pages; nothing on Settings itself). */
    data object ToSettings : BarTap
}

/** The tap on item [id] of the full bar (pure). */
fun barTap(id: String): BarTap? =
    when (id) {
        HomeTab.Trip.id -> BarTap.ToMap
        HomeTab.Saved.id -> BarTap.ToSaved
        HomeTab.Settings.id -> BarTap.ToSettings
        else -> null
    }

/** Something an intent hands to the map: the home consumes it once (E7.8 turns a place into the Confirm sheet). */
sealed interface StopsLink {
    /** A `geo:` URI, a maps link or shared text; `stops.dest_parse` reads it. */
    data class Place(
        val text: String,
    ) : StopsLink

    /** A saved stop's launcher shortcut (`StopsOps.savedShortcut`, `ShortcutAnswer.stopId`). */
    data class Stop(
        val id: Long,
    ) : StopsLink
}

/** The hosts of the GPUI manifest's map-link filters; www.google.com only under `/maps`. */
private val MapHosts = setOf("maps.google.com", "www.openstreetmap.org", "maps.apple.com")
private const val GOOGLE = "www.google.com"
private const val GOOGLE_MAPS_PATH = "/maps"

/**
 * What an intent asks the map to show (pure, JVM-tested). [dataUri] is the intent's data, [extras] its extras. A stop
 * shortcut wins; then a VIEW of a `geo:` URI or of a maps link on one of the GPUI manifest's hosts; then the text of a
 * SEND (a maps app shares a link or the place's text). Anything else is null.
 */
fun stopsLinkFor(
    action: String?,
    dataUri: String?,
    extras: Bundle?,
): StopsLink? {
    stopIdOf(extras)?.let { return StopsLink.Stop(it) }
    return when (action) {
        Intent.ACTION_VIEW -> {
            dataUri?.takeIf(::isMapUri)?.let { StopsLink.Place(it) }
        }

        Intent.ACTION_SEND -> {
            extras
                ?.getString(Intent.EXTRA_TEXT)
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }?.let { StopsLink.Place(it) }
        }

        else -> {
            null
        }
    }
}

/** The routes for an intent: any link resets the stack to the map (a pushed Settings page closes), else null. */
fun stopsRouteFor(
    action: String?,
    dataUri: String?,
    extras: Bundle?,
): Pair<Tab, List<StopsRoute>>? = stopsLinkFor(action, dataUri, extras)?.let { TripTab to emptyList() }

/** True for `geo:` and for an https link on a maps host (scheme and host compared case-insensitively). */
internal fun isMapUri(uri: String): Boolean {
    val scheme = uri.substringBefore(':', "").lowercase()
    if (scheme == "geo") return true
    if (scheme != "https") return false
    val rest = uri.substringAfter("://", "")
    if (rest.isEmpty()) return false
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val host =
        rest
            .substring(0, end)
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
    val path = rest.substring(end)
    return host in MapHosts || (host == GOOGLE && path.startsWith(GOOGLE_MAPS_PATH))
}

/** The stop id of a shortcut's extra (a Long, as the GPUI shortcuts wrote it); a missing or non-positive one is none. */
private fun stopIdOf(extras: Bundle?): Long? {
    if (extras == null || !extras.containsKey(Shortcuts.EXTRA_STOP_ID)) return null
    @Suppress("DEPRECATION")
    val raw = extras.get(Shortcuts.EXTRA_STOP_ID)
    return (raw as? Long ?: (raw as? Int)?.toLong())?.takeIf { it > 0 }
}
