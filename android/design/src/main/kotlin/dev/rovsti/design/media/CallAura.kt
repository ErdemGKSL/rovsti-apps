/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/ArtworkMeshBackground.kt
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/AnimatedChromeMesh.kt
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, artwork/src/main/kotlin/com/convx/artwork/ArtworkMesh.kt
 *
 * Changes for Rovsti: package dev.rovsti.design.media. The colours come from a seed (the contact's avatar colour) or
 * from the pixels of a contact photo instead of a Coil request; the "reduce dynamic blur" preference became
 * LocalReduceMotion; the nav-transition and motion-frozen flags became one `paused` lambda (a MotionGate reading);
 * a `fixedPhase` renders one still frame (screenshots and reduce motion); the playlist appearance and the artwork
 * seed registry are gone. Both loops keep Convx's rules: only while the lifecycle is RESUMED, at most 30 fps
 * (33 ms), and they stop while the system animation scale is 0.
 */
package dev.rovsti.design.media

import android.graphics.Bitmap
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.rovsti.design.motion.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A colour of the mesh, channels 0..1. */
data class MeshColor(
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    fun saturated(amount: Float): MeshColor {
        val r = (red * 255).roundToInt() / 255f
        val g = (green * 255).roundToInt() / 255f
        val b = (blue * 255).roundToInt() / 255f
        val maximum = maxOf(r, g, b)
        val minimum = minOf(r, g, b)
        val lightness = (maximum + minimum) / 2f
        val range = maximum - minimum
        val saturation = if (range > 0f) range / (1f - abs(2f * lightness - 1f)) else 0f
        if (saturation < .08f) return this
        val factor = minOf(amount, 1f / saturation)
        return MeshColor(
            lightness + (r - lightness) * factor,
            lightness + (g - lightness) * factor,
            lightness + (b - lightness) * factor,
        )
    }

    /** Pulled toward white by [amount], so the drift reads as colour rather than shadow (Convx `lifted`). */
    fun lifted(amount: Float) =
        MeshColor(
            red + (1f - red) * amount,
            green + (1f - green) * amount,
            blue + (1f - blue) * amount,
        )
}

/** Nine cell colours (a 3 x 3 grid) and their average: what the mesh blends. */
data class ArtworkMeshPalette(
    val cells: List<MeshColor>,
    val average: MeshColor,
    val sourceAverage: MeshColor = average,
) {
    init {
        require(cells.size == 9)
    }

    fun lifted(amount: Float) = copy(cells = cells.map { it.lifted(amount) }, average = average.lifted(amount))

    companion object {
        val Fallback =
            ArtworkMeshPalette(
                listOf(0x101E3D, 0x2E1A57, 0x0F3B4D, 0x38143D, 0x24476B, 0x4D1F4D, 0x0D2E42, 0x291A4D, 0x121C33)
                    .map(::color),
                color(0x171D32),
            )

        fun fromPixels(
            pixels: IntArray,
            width: Int,
            height: Int,
        ): ArtworkMeshPalette {
            require(width > 0 && height > 0 && width.toLong() * height <= pixels.size)

            fun average(
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
            ): MeshColor {
                var red = 0L
                var green = 0L
                var blue = 0L
                var count = 0
                for (y in top until bottom step 2) {
                    for (x in left until right step 2) {
                        val pixel = pixels[y * width + x]
                        red += (pixel ushr 16) and 255
                        green += (pixel ushr 8) and 255
                        blue += pixel and 255
                        count++
                    }
                }
                if (count == 0) {
                    return color(pixels[(top.coerceAtMost(height - 1)) * width + left.coerceAtMost(width - 1)])
                }
                val scale = count * 255f
                return MeshColor(red / scale, green / scale, blue / scale)
            }
            val cells =
                List(9) { index ->
                    val column = index % 3
                    val row = index / 3
                    average(column * width / 3, row * height / 3, (column + 1) * width / 3, (row + 1) * height / 3)
                        .saturated(1.2f)
                }
            val sourceAverage = average(0, 0, width, height)
            return ArtworkMeshPalette(cells, sourceAverage.saturated(1.1f), sourceAverage)
        }

        /**
         * A palette spun from one accent colour, for a caller with no photo to sample: nine neighbours of the seed's
         * hue at different lightness, so the mesh still drifts through colour instead of sitting flat.
         */
        fun fromSeed(argb: Int): ArtworkMeshPalette {
            val seed = color(argb)
            val maximum = maxOf(seed.red, seed.green, seed.blue)
            val minimum = minOf(seed.red, seed.green, seed.blue)
            val delta = maximum - minimum
            val hue =
                when {
                    delta == 0f -> 0f
                    maximum == seed.red -> 60f * (((seed.green - seed.blue) / delta) % 6f)
                    maximum == seed.green -> 60f * ((seed.blue - seed.red) / delta + 2f)
                    else -> 60f * ((seed.red - seed.green) / delta + 4f)
                }.let { if (it < 0f) it + 360f else it }
            val saturation = if (maximum == 0f) 0f else (delta / maximum).coerceAtLeast(.55f)
            val hueShifts = floatArrayOf(-28f, 0f, 24f, 14f, 0f, -18f, 30f, -10f, 6f)
            val values = floatArrayOf(.42f, .62f, .38f, .55f, .72f, .36f, .5f, .66f, .4f)
            val cells = List(9) { hsv(hue + hueShifts[it], saturation, values[it]) }
            val average = hsv(hue, saturation, .5f)
            return ArtworkMeshPalette(cells, average, average)
        }

        private fun hsv(
            hue: Float,
            saturation: Float,
            value: Float,
        ): MeshColor {
            val h = ((hue % 360f) + 360f) % 360f / 60f
            val c = value * saturation
            val x = c * (1f - abs(h % 2f - 1f))
            val m = value - c
            val (r, g, b) =
                when (h.toInt()) {
                    0 -> Triple(c, x, 0f)
                    1 -> Triple(x, c, 0f)
                    2 -> Triple(0f, c, x)
                    3 -> Triple(0f, x, c)
                    4 -> Triple(x, 0f, c)
                    else -> Triple(c, 0f, x)
                }
            return MeshColor(r + m, g + m, b + m)
        }

        private fun color(argb: Int) =
            MeshColor(
                ((argb ushr 16) and 255) / 255f,
                ((argb ushr 8) and 255) / 255f,
                (argb and 255) / 255f,
            )
    }
}

/** Renders a tiny bitmap (32 x 73) of nine drifting colour blobs; the canvas scales it up with a high-quality filter. */
class ArtworkMesh(
    val width: Int = 32,
    val height: Int = width * 23 / 10,
) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
    }

    private val centersX = FloatArray(9)
    private val centersY = FloatArray(9)
    private val frequencyX = floatArrayOf(.50f, .25f, .75f, .50f, .25f, .75f, .50f, .25f, .75f)
    private val frequencyY = floatArrayOf(.25f, .75f, .50f, .75f, .50f, .25f, .50f, .25f, .75f)

    fun render(
        palette: ArtworkMeshPalette,
        phase: Float,
        pixels: IntArray,
    ) {
        require(pixels.size >= width * height)
        val time = if (phase.isFinite()) phase % PhasePeriod else 0f
        for (index in 0..8) {
            centersX[index] = .5f + .3f * sin(time * frequencyX[index] + index * 1.7f)
            centersY[index] = .5f + .3f * cos(time * frequencyY[index] + index * .9f)
        }
        val dimension = maxOf(width, height).toFloat()
        val widthScale = width / dimension
        val heightScale = height / dimension
        var output = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val u = x.toFloat() / (width - 1).coerceAtLeast(1)
                val v = y.toFloat() / (height - 1).coerceAtLeast(1)
                val warpX =
                    sin(v * 3.1f + time) * cos(u * 2.3f - time * .75f) +
                        .5f * sin((u + v) * 4.9f - time * 1.25f)
                val warpY =
                    cos(u * 2.9f - time) * sin(v * 2.6f + time * .5f) +
                        .5f * cos((u - v) * 5.4f + time)
                var sampleU = u + .15f * warpX
                var sampleV = v + .15f * warpY
                sampleU += .15f * .28f * sin(sampleV * 6.7f - time * 1.5f)
                sampleV += .15f * .28f * cos(sampleU * 7.1f + time * 1.5f)
                var weightSum = .16f
                var red = palette.average.red * .16f
                var green = palette.average.green * .16f
                var blue = palette.average.blue * .16f
                for (index in 0..8) {
                    val dx = (sampleU - centersX[index]) * widthScale / .45f
                    val dy = (sampleV - centersY[index]) * heightScale / .45f
                    val falloff = 1f / (1f + (dx * dx + dy * dy) * 4.5f)
                    val weight = falloff * falloff
                    weightSum += weight
                    red += palette.cells[index].red * weight
                    green += palette.cells[index].green * weight
                    blue += palette.cells[index].blue * weight
                }
                pixels[output++] = (255 shl 24) or
                    (((red / weightSum).coerceIn(0f, 1f) * 255).toInt() shl 16) or
                    (((green / weightSum).coerceIn(0f, 1f) * 255).toInt() shl 8) or
                    ((blue / weightSum).coerceIn(0f, 1f) * 255).toInt()
            }
        }
    }

    companion object {
        const val PhasePeriod = 25.132742f
        const val RadiansPerSecond = 1.92f
    }
}

/** Convx's 30 fps cap: a mesh frame is drawn at most this often. */
const val AuraFrameIntervalNanos = 33_000_000L

/** How far the call aura is pulled toward white (Convx `PlaylistMeshLift`). */
const val AuraLift = 0.12f

/**
 * The colours of a call's aura: the pixels of the contact [photo] (averaged over 160 x 160, as Convx samples cover
 * art), else a palette spun from [seedArgb] (the avatar colour), else Convx's dark fallback.
 */
@Composable
fun rememberCallAuraPalette(
    seedArgb: Int?,
    photo: ImageBitmap? = null,
): ArtworkMeshPalette =
    remember(seedArgb, photo) {
        val sampled = photo?.let { runCatching { meshPalette(it) }.getOrNull() }
        (sampled ?: seedArgb?.let(ArtworkMeshPalette::fromSeed) ?: ArtworkMeshPalette.Fallback).lifted(AuraLift)
    }

private fun meshPalette(image: ImageBitmap): ArtworkMeshPalette {
    val original = image.asAndroidBitmap()
    val resized = Bitmap.createScaledBitmap(original, SAMPLE_SIDE, SAMPLE_SIDE, true)
    val software =
        if (resized.config == Bitmap.Config.HARDWARE) resized.copy(Bitmap.Config.ARGB_8888, false) else resized
    try {
        val pixels = IntArray(SAMPLE_SIDE * SAMPLE_SIDE)
        software.getPixels(pixels, 0, SAMPLE_SIDE, 0, 0, SAMPLE_SIDE, SAMPLE_SIDE)
        return ArtworkMeshPalette.fromPixels(pixels, SAMPLE_SIDE, SAMPLE_SIDE)
    } finally {
        if (software !== resized) software.recycle()
        if (resized !== original) resized.recycle()
    }
}

private const val SAMPLE_SIDE = 160

/**
 * The full-screen colour wash behind a call (Convx `ArtworkMeshBackground`). It moves only while [running], not
 * [paused], the lifecycle is RESUMED, reduce motion is off and the system animation scale is above 0, and then at
 * most 30 times a second; otherwise it holds one still frame. [fixedPhase] forces that still frame at a given phase
 * (screenshots). The state description says `animating` or `still`, which the tests read.
 */
@Composable
fun CallAura(
    palette: ArtworkMeshPalette,
    modifier: Modifier = Modifier,
    running: Boolean = true,
    paused: () -> Boolean = { false },
    fixedPhase: Float? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduceMotion = LocalReduceMotion.current
    val isPaused by rememberUpdatedState(paused)
    val latestPalette by rememberUpdatedState(palette)
    val still = fixedPhase != null || reduceMotion || !running
    val mesh = remember { ArtworkMesh() }
    val bitmaps =
        remember(mesh) {
            val first = IntArray(mesh.width * mesh.height)
            mesh.render(palette, fixedPhase ?: 0f, first)
            List(2) {
                Bitmap
                    .createBitmap(mesh.width, mesh.height, Bitmap.Config.ARGB_8888)
                    .also { it.setPixels(first, 0, mesh.width, 0, 0, mesh.width, mesh.height) }
            }
        }
    val images = remember(bitmaps) { bitmaps.map(Bitmap::asImageBitmap) }
    val displayed = remember(images) { mutableStateOf(images.first()) }
    var animating by remember { mutableStateOf(false) }

    // A still aura repaints once per palette (or phase) change, without a coroutine.
    LaunchedEffect(mesh, bitmaps, palette, fixedPhase, still) {
        if (!still) return@LaunchedEffect
        val pixels = IntArray(mesh.width * mesh.height)
        mesh.render(palette, fixedPhase ?: 0f, pixels)
        bitmaps[0].setPixels(pixels, 0, mesh.width, 0, 0, mesh.width, mesh.height)
        displayed.value = images[0]
    }

    LaunchedEffect(mesh, bitmaps, lifecycle, still) {
        if (still) {
            animating = false
            return@LaunchedEffect
        }
        val pixels = IntArray(mesh.width * mesh.height)
        var phase = 0f
        var nextBitmap = 1
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previousFrame = 0L
            var lastRendered = 0L
            animating = true
            while (true) {
                val frame = withFrameNanos { it }
                if (lastRendered != 0L && isPaused()) {
                    snapshotFlow { isPaused() }.first { !it }
                    previousFrame = 0L
                    continue
                }
                if (frame - lastRendered < AuraFrameIntervalNanos) continue
                val elapsed =
                    if (previousFrame == 0L) 0f else ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .1f)
                previousFrame = frame
                lastRendered = frame
                val systemScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                if (systemScale <= 0f) {
                    animating = false
                    return@repeatOnLifecycle
                }
                phase = (phase + ArtworkMesh.RadiansPerSecond * elapsed / systemScale) % ArtworkMesh.PhasePeriod
                val bitmap = bitmaps[nextBitmap]
                val renderedPalette = latestPalette
                withContext(Dispatchers.Default) {
                    mesh.render(renderedPalette, phase, pixels)
                    bitmap.setPixels(pixels, 0, mesh.width, 0, 0, mesh.width, mesh.height)
                }
                displayed.value = images[nextBitmap]
                nextBitmap = 1 - nextBitmap
            }
        }
        animating = false
    }

    Canvas(
        modifier
            .fillMaxSize()
            .semantics { stateDescription = if (animating && !still) AuraAnimating else AuraStill },
    ) {
        drawImage(
            image = displayed.value,
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.High,
        )
        // Convx's veil: a little black at the top, 48 % at the bottom, so white text and buttons always read.
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .04f),
                .52f to Color.Black.copy(alpha = .10f),
                1f to Color.Black.copy(alpha = .48f),
            ),
        )
    }
}

const val AuraAnimating = "animating"
const val AuraStill = "still"

/**
 * Slowly advancing phase in radians. It only runs while [visible] is true and [paused] is false, the lifecycle is
 * RESUMED, motion is not reduced and the system animation scale is above 0 (Convx `rememberMeshPhase`).
 */
@Composable
internal fun rememberMeshPhase(
    visible: State<Boolean>,
    paused: () -> Boolean,
    fixedPhase: Float? = null,
): State<Float> {
    val reduceMotion = LocalReduceMotion.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPaused = rememberUpdatedState(paused)
    val phase = remember { mutableFloatStateOf(fixedPhase ?: 0f) }

    LaunchedEffect(lifecycleOwner, reduceMotion, fixedPhase) {
        if (fixedPhase != null) {
            phase.floatValue = fixedPhase
            return@LaunchedEffect
        }
        if (reduceMotion) return@LaunchedEffect
        val durationScale = currentCoroutineContext()[MotionDurationScale]
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                visible.value && !currentPaused.value() && (durationScale?.scaleFactor ?: 1f) > 0f
            }.collectLatest { running ->
                if (!running) return@collectLatest
                var lastUpdate = withFrameNanos { it }
                while (currentCoroutineContext().isActive) {
                    val frame = withFrameNanos { it }
                    if (frame - lastUpdate >= AuraFrameIntervalNanos) {
                        val elapsedSeconds = ((frame - lastUpdate) / 1_000_000_000f).coerceAtMost(0.1f)
                        val scale = durationScale?.scaleFactor ?: 1f
                        if (scale > 0f) {
                            phase.floatValue = (phase.floatValue + elapsedSeconds * MeshRadiansPerSecond / scale) %
                                MeshCycleRadians
                        }
                        lastUpdate = frame
                    }
                }
            }
        }
    }
    return phase
}

/**
 * Soft colour blobs that drift sideways across the top of a screen (Convx `AnimatedChromeMesh`): three radial
 * gradients, faded out toward the bottom, each swaying on its own speed. It draws behind its content and costs one
 * layer; [paused] and the lifecycle stop it like [CallAura].
 */
@Composable
fun AnimatedChromeMesh(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    paused: () -> Boolean = { false },
    primaryColor: Color,
    secondaryColor: Color,
    alpha: Float = 0.2f,
    fadeAtBottom: Boolean = true,
    fixedPhase: Float? = null,
) {
    if (!enabled || alpha <= 0f) return

    val visible = remember { mutableStateOf(false) }
    val phase = rememberMeshPhase(visible, paused, fixedPhase)

    Box(
        // Isolate mesh redraws from cached artwork and backdrop layers.
        modifier =
            modifier
                .graphicsLayer()
                .onGloballyPositioned { coordinates ->
                    val onScreen = coordinates.isAttached && !coordinates.boundsInWindow().isEmpty
                    if (visible.value != onScreen) visible.value = onScreen
                }.drawWithCache {
                    val width = size.width
                    val height = size.height
                    val opacity = alpha.coerceIn(0f, 1f)
                    val colors = arrayOf(primaryColor, secondaryColor, lerp(primaryColor, secondaryColor, 0.5f))
                    val white = android.graphics.Color.WHITE
                    val clear = android.graphics.Color.TRANSPARENT
                    val fadeStart = if (fadeAtBottom) white else clear
                    val fadeEnd = if (fadeAtBottom) clear else white
                    val fade =
                        LinearGradient(
                            0f,
                            0f,
                            0f,
                            height.coerceAtLeast(1f),
                            intArrayOf(fadeStart, android.graphics.Color.WHITE, fadeEnd),
                            if (fadeAtBottom) floatArrayOf(0f, 0.22f, 1f) else floatArrayOf(0f, 0.7f, 1f),
                            Shader.TileMode.CLAMP,
                        )
                    val radius = max(width * 0.58f, height * 1.35f).coerceAtLeast(1f)
                    val centerY = height * if (fadeAtBottom) 0.2f else 0.75f
                    val paints =
                        Array(colors.size) { index ->
                            val color = colors[index]
                            val radial =
                                RadialGradient(
                                    width * MeshAnchors[index],
                                    centerY,
                                    radius,
                                    intArrayOf(
                                        color.copy(alpha = color.alpha * opacity).toArgb(),
                                        color.copy(alpha = color.alpha * opacity * 0.7f).toArgb(),
                                        color.copy(alpha = 0f).toArgb(),
                                    ),
                                    floatArrayOf(0f, 0.35f, 1f),
                                    Shader.TileMode.CLAMP,
                                )
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                shader = ComposeShader(radial, fade, PorterDuff.Mode.DST_IN)
                            }
                        }

                    onDrawBehind {
                        val drift = phase.value
                        drawIntoCanvas { canvas ->
                            val nativeCanvas = canvas.nativeCanvas
                            val clipSave = nativeCanvas.save()
                            nativeCanvas.clipRect(0f, 0f, width, height)
                            for (index in paints.indices) {
                                val offset = width * 0.12f * sin(drift * MeshSpeeds[index] + MeshOffsets[index])
                                val blobSave = nativeCanvas.save()
                                nativeCanvas.translate(offset, 0f)
                                nativeCanvas.drawRect(-offset, 0f, width - offset, height, paints[index])
                                nativeCanvas.restoreToCount(blobSave)
                            }
                            nativeCanvas.restoreToCount(clipSave)
                        }
                    }
                },
    )
}

private val MeshCycleRadians = (2 * PI).toFloat()
private val MeshRadiansPerSecond = MeshCycleRadians / 32f
private val MeshAnchors = floatArrayOf(0.12f, 0.5f, 0.88f)
private val MeshSpeeds = floatArrayOf(1f, 1f, 2f)
private val MeshOffsets = floatArrayOf(0f, 2.1f, 4.2f)
