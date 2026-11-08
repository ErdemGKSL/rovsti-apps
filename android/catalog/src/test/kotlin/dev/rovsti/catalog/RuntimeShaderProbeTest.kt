package dev.rovsti.catalog

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassLensShader
import dev.rovsti.design.glass.LensOptions
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Records, never fails on the outcome: can this Robolectric setup run `RuntimeShader` and `RenderEffect`?
 * Each probe renders a 40 x 40 dp box, left half red and right half blue, with and without an effect and compares the
 * pixels. The result goes to `build/probe/runtime-shader.txt`; E1.5 copies it into `docs/kotlin-ui/api-notes.md`.
 */
class RuntimeShaderProbeTest : ShotTest() {
    private fun render(effect: RenderEffect?): IntArray {
        val e = effect?.asComposeRenderEffect()
        composeFrame {
            Canvas(
                Modifier.testTag("probe").size(40.dp).graphicsLayer { renderEffect = e },
            ) {
                drawRect(Color.Red, Offset.Zero, Size(size.width / 2, size.height))
                drawRect(Color.Blue, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
            }
        }
        val bitmap = compose.onNodeWithTag("probe").captureToImage()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun probe(
        name: String,
        effect: () -> RenderEffect,
    ): String =
        try {
            val plain = render(null)
            val withEffect = render(effect())
            val changed = plain.indices.count { plain[it] != withEffect[it] }
            val seam = plain.size / 2 + 60 // a pixel in the middle row, left of the seam by a few px
            "$name: ${if (changed > 0) "EFFECT APPLIED" else "NO EFFECT"} ($changed of ${plain.size} px differ; " +
                "plain=0x${plain[seam].toUInt().toString(16)} effect=0x${withEffect[seam].toUInt().toString(16)})"
        } catch (t: Throwable) {
            "$name: THREW ${t::class.java.name}: ${t.message?.lineSequence()?.firstOrNull()}"
        }

    @Test
    fun recordsWhatTheRendererCanRun() {
        val lines =
            listOf(
                probe("RenderEffect.createBlurEffect") {
                    RenderEffect.createBlurEffect(8f, 8f, Shader.TileMode.CLAMP)
                },
                probe("RuntimeShader (AGSL) green fill via createRuntimeShaderEffect") {
                    val shader =
                        RuntimeShader(
                            "uniform shader content; half4 main(float2 c) { return content.eval(c) * 0.0 + half4(0.0, 1.0, 0.0, 1.0); }",
                        )
                    RenderEffect.createRuntimeShaderEffect(shader, "content")
                },
                probe("GlassLensShader.effect (the real lens)") {
                    GlassLensShader.effect(Size(120f, 120f), 30f, LensOptions.Glass, 1f)
                },
            )
        val text = lines.joinToString("\n")
        val out = File("build/probe").apply { mkdirs() }.resolve("runtime-shader.txt")
        out.writeText(text + "\n")
        println(text)
        assertTrue(out.exists())
    }
}
