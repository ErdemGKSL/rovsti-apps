package dev.rovsti.design.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.backdrops.LayerBackdrop
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.backdrop.effects.blur
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Convx `Motion.kt` (PushMillis note) and `NavTransitionFreeze`: while a push or scroll swaps the whole screen, the glass
 * must not re-record its backdrop per frame. This pins the engine side on the real `drawBackdrop`: the source layer
 * follows its animated content every frame (`BackdropDebug.rootRecords` grows) while a sampling surface that sits over it
 * records its own layer once and then only references the source RenderNode (`BackdropDebug.samplerRecords` stays flat),
 * before, during and after the freeze provider reports frozen, even though the source geometry version keeps changing
 * (the frozen branch is the one that must hold the capture back). The nav-host side (the provider turns true on a push,
 * not on a pop) is covered by `NavFreezeTest` in `:catalog`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FrozenRecordCountTest {
    @get:Rule
    val compose = createComposeRule()

    private var source: LayerBackdrop? = null

    private val tick = mutableIntStateOf(0)
    private var frozen = false

    @Before
    fun debugOn() {
        BackdropDebug.clear()
        BackdropDebug.enabled = true
        compose.mainClock.autoAdvance = false
    }

    @After
    fun debugOff() {
        BackdropDebug.enabled = false
        BackdropDebug.clear()
    }

    private fun frame() {
        tick.intValue++
        compose.mainClock.advanceTimeBy(50)
        source!!.geometryChanged()
        compose.onRoot().captureToImage()
    }

    @Test
    fun samplerRecordCountStaysFlatWhileTheSourceKeepsRecording() {
        compose.setContent {
            val backdrop = rememberLayerBackdrop().also { source = it }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .drawBehind { drawRect(Color.Gray.copy(alpha = 0.2f + 0.01f * (tick.intValue % 50))) },
                )
                // A sibling of the source, never inside it: a sampler recorded into the layer it samples crashes natively.
                Box(
                    Modifier.size(80.dp).drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(8.dp.toPx()) },
                        frozen = { frozen },
                        debugTag = "sampler",
                    ),
                )
            }
        }
        repeat(4) { frame() }
        assertTrue("sampler recorded its first capture", BackdropDebug.samplerRecords > 0)

        frozen = true
        val samplersAtFreeze = BackdropDebug.samplerRecords
        val rootAtFreeze = BackdropDebug.rootRecords
        repeat(12) { frame() }
        assertEquals("no sampler re-record while frozen", samplersAtFreeze, BackdropDebug.samplerRecords)
        assertTrue("the source keeps following its animated content", BackdropDebug.rootRecords > rootAtFreeze)

        frozen = false
        repeat(6) { frame() }
        assertEquals("a thaw alone does not re-record either", samplersAtFreeze, BackdropDebug.samplerRecords)
    }
}
