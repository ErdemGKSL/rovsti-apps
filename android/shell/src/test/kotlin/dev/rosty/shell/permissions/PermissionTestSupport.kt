package dev.rosty.shell.permissions

import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
import dev.rosty.design.chrome.RostyBackdropRoot
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.PermissionController
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.system.permissions.PermissionState

/** A registry that records every launch instead of starting an activity: the "fake registry" of the gate tests. */
class RecordingRegistry(
    private val context: Context,
) : ActivityResultRegistry() {
    val intents = mutableListOf<Intent>()

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        intents += contract.createIntent(context, input)
    }

    val owner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry get() = this@RecordingRegistry
        }
}

/** A list page the gate guards: what the sheet floats over in the screenshots. */
@Composable
internal fun SamplePage(content: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(WindowInsets.safeDrawing.asPaddingValues())
            // No horizontal padding here: InsetGroupedSection brings the one 16 dp gutter itself (F11), so only the
            // title, which is not in a section, takes it.
            .padding(vertical = Spacing.gutter),
    ) {
        RostyText(
            "Contacts",
            style = RostyTheme.type.largeTitle,
            modifier = Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.m),
        )
        content()
        InsetGroupedSection(header = "Recent") {
            for (name in listOf(
                "Ada Lovelace",
                "Grace Hopper",
                "Alan Turing",
                "Katherine Johnson",
                "Margaret Hamilton",
            )) {
                row { ListRow(title = name, secondary = "mobile") }
            }
        }
    }
}

/** The gate over [SamplePage], on the real backdrop root so the sheet pushes the page back as in the apps. */
@Composable
internal fun GatedPage(
    group: PermissionGroup,
    controller: PermissionController,
    special: FakeSpecialAccess,
    registry: RecordingRegistry,
    onState: (PermissionState) -> Unit = {},
    withRow: Boolean = true,
) {
    val overlays = remember { OverlayController() }
    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides registry.owner,
        LocalPermissionController provides controller,
        LocalSpecialAccess provides special,
    ) {
        RostyBackdropRoot(overlays) {
            SamplePage {
                onState(PermissionGate(group))
                if (withRow) {
                    InsetGroupedSection(header = "Access", modifier = Modifier.padding(bottom = Spacing.l)) {
                        row { PermissionRow(group) }
                    }
                }
            }
        }
    }
}
