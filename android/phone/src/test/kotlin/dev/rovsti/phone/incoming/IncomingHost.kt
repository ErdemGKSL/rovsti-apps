package dev.rovsti.phone.incoming

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.design.media.ArtworkMeshPalette
import dev.rovsti.design.media.CallAura
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.phone.PhoneRoots
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneTabs
import dev.rovsti.phone.RecentsTab
import dev.rovsti.phone.chromeFor
import dev.rovsti.phone.phoneTabItems
import dev.rovsti.shell.RovstiShell

internal val incomingStrings get() = IncomingStrings(ApplicationProvider.getApplicationContext<Context>().resources)

/** Records what the Incoming screen asks for, and holds the overlay controller the sheets are presented on. */
internal class IncomingShotHarness(
    var ui: IncomingUi,
) {
    val overlays = OverlayController()
    val actions = mutableListOf<IncomingAction>()
}

/**
 * The Incoming screen on the real shell, the way the app routes it: Recents below, Incoming pushed, no tab bar. With a
 * waiting call the route is In-Call (a stand-in page until E5.7a) and the card floats over it in the `chrome` slot.
 */
@Composable
internal fun HostedIncoming(
    harness: IncomingShotHarness,
    inCall: Boolean = false,
    aura: AuraMode = AuraMode.Shot,
) {
    val navigator =
        remember {
            TabNavigator(PhoneTabs, PhoneRoots).also {
                it.select(RecentsTab)
                it.push(if (inCall) PhoneRoute.InCall else PhoneRoute.Incoming(1))
            }
        }
    val strings = incomingStrings
    RovstiShell(
        navigator = navigator,
        tabs = phoneTabItems("Favorites", "Recents", "Contacts", "Keypad"),
        chromeFor = { route -> chromeFor(route) },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
        chrome = {
            val waiting = harness.ui.waiting
            if (waiting != null) {
                WaitingCallCard(waiting, strings, { harness.actions += it }, Modifier.align(Alignment.TopCenter))
            }
        },
    ) {
        entry<PhoneRoute.Recents> {}
        entry<PhoneRoute.Favorites> {}
        entry<PhoneRoute.Contacts> {}
        entry<PhoneRoute.Keypad> {}
        entry<PhoneRoute.Incoming> {
            IncomingContent(harness.ui, strings, { harness.actions += it }, aura = aura)
        }
        entry<PhoneRoute.InCall> { InCallStandIn() }
    }
}

/** What the call page looks like until E5.7a: the aura of the live call and its name. */
@Composable
private fun InCallStandIn() {
    Box(Modifier.fillMaxSize().then(Modifier), contentAlignment = Alignment.Center) {
        CallAura(ArtworkMeshPalette.fromSeed(0xFF1C7436.toInt()).lifted(0.12f), running = false, fixedPhase = 1.2f)
        RovstiText("Mert Kaya", style = RovstiTheme.type.largeTitle, color = Color.White)
    }
}
