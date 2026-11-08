package dev.rovsti.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.chrome.BackdropScope
import dev.rovsti.design.chrome.BottomScrim
import dev.rovsti.design.chrome.ChromeMetrics
import dev.rovsti.design.chrome.LocalChromeMetrics
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.RovstiTabBar
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/** E1.15: toasts and the undo pill over a page that has the tab bar; the line under the buttons says what happened. */
@Composable
internal fun ToastsPage() {
    val controller = LocalOverlay.current
    val colors = RovstiTheme.colors
    val chrome = remember { ChromeMetrics() }
    var selected by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("Nothing happened yet") }
    BackdropScope(
        modifier = Modifier.fillMaxSize().testTag("toasts-page"),
        baseBg = colors.page,
        content = {
            CompositionLocalProvider(LocalContentInsets provides chrome.contentInsets()) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(LocalContentInsets.current),
                    ) {
                        PageHeader("Toasts", "They sit above the tab bar and the page keeps working underneath.")
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s),
                        ) {
                            CapsuleButton(
                                text = "Show toast",
                                onClick = { controller.showToast("Number copied") },
                                modifier = Modifier.testTag("toast-show"),
                            )
                            CapsuleButton(
                                text = "Show long toast",
                                onClick = {
                                    controller.showToast(
                                        "Connect key copied. Paste it in Messages or Contacts to share the WhatsApp " +
                                            "link without scanning the code again.",
                                    )
                                },
                                modifier = Modifier.testTag("toast-long"),
                            )
                            CapsuleButton(
                                text = "Delete with undo",
                                onClick = {
                                    result = "Deleted"
                                    controller.showUndo("Conversation deleted", onUndo = { result = "Restored" })
                                },
                                modifier = Modifier.testTag("toast-undo"),
                            )
                            CapsuleButton(
                                text = "Replace",
                                onClick = {
                                    controller.showToast("Saving to Contacts")
                                    controller.showToast("Saved to Contacts")
                                },
                                modifier = Modifier.testTag("toast-replace"),
                            )
                            RovstiText(
                                result,
                                modifier = Modifier.padding(top = Spacing.s).testTag("toast-result"),
                                style = RovstiTheme.type.footnote,
                                color = colors.labelSecondary,
                            )
                        }
                    }
                }
            }
        },
        chrome = {
            CompositionLocalProvider(LocalChromeMetrics provides chrome) {
                BottomScrim(chrome, Modifier.align(Alignment.BottomCenter))
                RovstiTabBar(
                    tabs = CatalogTabs,
                    selected = selected,
                    onSelect = { selected = it },
                    onReselect = {},
                    modifier = Modifier.align(Alignment.BottomCenter).testTag("tab-bar"),
                )
            }
        },
    )
}
