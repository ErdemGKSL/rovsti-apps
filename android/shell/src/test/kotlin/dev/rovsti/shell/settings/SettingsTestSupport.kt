package dev.rovsti.shell.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.rovsti.design.chrome.RovstiBackdropRoot
import dev.rovsti.design.overlay.OverlayController
import java.io.File

/** The real notices and the real `LICENSE`, read from the repository (the Gradle copy task serves the app the same files). */
internal fun realLicenceData(): LicenceData =
    LicenceData(
        notices = NoticesParser.parse(File("../THIRD_PARTY_NOTICES.md").readText()),
        gplText = File("../../LICENSE").readLines(),
    )

internal val SampleAbout =
    AboutInfo(
        appName = "Rovsti",
        versionName = "0.9.0",
        versionCode = 42,
        coreVersion = "0.3.1",
        installedAt = "Nov 9, 2026",
    )

/** A page on the app backdrop with an overlay host, as the apps compose it. */
@Composable
internal fun HostedPage(content: @Composable () -> Unit) {
    val overlays = remember { OverlayController() }
    RovstiBackdropRoot(overlays) { content() }
}
