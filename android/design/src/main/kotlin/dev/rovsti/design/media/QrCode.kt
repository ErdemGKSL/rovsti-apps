package dev.rovsti.design.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.design.R
import kotlin.math.floor

/**
 * The modules of a QR code as the design system draws them: a [side] x [side] grid and a lookup for the dark ones. It is
 * the design-side view of the bridge's `QrMatrix` (`:design` does not depend on the Rust bridge), so it holds a lookup
 * instead of the bits. The quiet zone is not part of the grid.
 */
@Immutable
class QrModules(
    val side: Int,
    private val dark: (x: Int, y: Int) -> Boolean,
) {
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = x in 0 until side && y in 0 until side && dark(x, y)
}

/** Light modules around the grid the QR specification asks for. */
const val QR_QUIET_MODULES = 4

/** The card is white and the modules black in both themes: scanners read dark on light, whatever the page looks like. */
private val QrPaper = Color.White
private val QrInk = Color.Black
private val QrCardRadius = 16.dp

/**
 * A QR code on a white rounded card: dark modules, [QR_QUIET_MODULES] light modules of quiet zone inside the card, in
 * both themes. The card is square; give it a size through [modifier]. With [modules] `null` the card is drawn empty (the
 * code is still being made). One [Path] is built per size change (`drawWithCache`), never per frame.
 */
@Composable
fun QrCode(
    modules: QrModules?,
    modifier: Modifier = Modifier,
) {
    val qrLabel = stringResource(R.string.design_qr_code)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(QrCardRadius))
            .background(QrPaper)
            .semantics { contentDescription = qrLabel }
            .drawWithCache {
                val path = if (modules != null) qrPath(modules, size.minDimension) else null
                onDrawBehind { if (path != null) drawPath(path, QrInk) }
            },
    )
}

/**
 * The dark modules of [modules] as one path inside a square of [extent] pixels: whole pixels per module, so the edges
 * stay sharp, centred, with [QR_QUIET_MODULES] modules of quiet zone. Horizontal runs are merged into one rectangle.
 */
internal fun qrPath(
    modules: QrModules,
    extent: Float,
): Path {
    val cells = modules.side + 2 * QR_QUIET_MODULES
    val cell = floor(extent / cells).coerceAtLeast(1f)
    val origin = (extent - cell * cells) / 2f
    val path = Path()
    for (y in 0 until modules.side) {
        var x = 0
        while (x < modules.side) {
            if (!modules[x, y]) {
                x++
                continue
            }
            val start = x
            while (x < modules.side && modules[x, y]) x++
            path.addRect(
                Rect(
                    Offset(origin + (start + QR_QUIET_MODULES) * cell, origin + (y + QR_QUIET_MODULES) * cell),
                    Size((x - start) * cell, cell),
                ),
            )
        }
    }
    return path
}

/** Draws the code (white square, quiet zone, black modules) into this scope, filling it. */
internal fun DrawScope.drawQr(modules: QrModules) {
    drawRect(QrPaper)
    drawPath(qrPath(modules, size.minDimension), QrInk)
}

/**
 * The code as a square bitmap of [px] pixels for Save Image and Share Image: the same drawing as [QrCode], square
 * corners. Drawn on an off-screen canvas, so it works without a window.
 */
fun renderQrBitmap(
    modules: QrModules,
    px: Int = QR_EXPORT_PX,
): ImageBitmap {
    val bitmap = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        drawQr(modules)
    }
    return bitmap
}

/** Side of the image that Save Image and Share Image write. */
const val QR_EXPORT_PX = 1024
