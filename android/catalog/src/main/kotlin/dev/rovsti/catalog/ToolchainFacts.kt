package dev.rovsti.catalog

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.LayoutDirection
import dev.rovsti.design.time.LocalRovstiClock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ClockFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

/** Facts a screenshot should pin down: everything here must be deterministic under [ShotTest]. */
@Composable
internal fun toolchainFacts(): List<Pair<String, String>> {
    val density = LocalDensity.current
    val clock = LocalRovstiClock.current
    val locale = LocalLocale.current.platformLocale
    return listOf(
        "Android API" to Build.VERSION.SDK_INT.toString(),
        "Clock" to ClockFormat.format(Instant.ofEpochMilli(clock.nowMillis())),
        "Locale" to locale.toLanguageTag(),
        "Density" to "%.2f".format(Locale.US, density.density),
        "Font scale" to "%.1f".format(Locale.US, density.fontScale),
        "Layout direction" to if (LocalLayoutDirection.current == LayoutDirection.Rtl) "RTL" else "LTR",
    )
}
