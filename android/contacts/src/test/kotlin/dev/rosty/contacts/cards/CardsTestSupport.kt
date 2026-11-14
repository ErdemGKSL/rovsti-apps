package dev.rosty.contacts.cards

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import dev.rosty.core.Outcome
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardRenderRequest
import dev.rosty.core.ops.CardRenderResult
import dev.rosty.core.ops.CardTemplate
import dev.rosty.core.ops.CardsOps
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for the Rust renderer: draws a card-shaped PNG (palette gradient, a frosted panel and the card's own
 * words) at [req.out]. The Rust goldens of X.2 step 2 do not exist in this tree yet, so the screenshots use these
 * pictures; the sheet's behaviour does not depend on what the core draws. [gate] holds a render open (the progress ring
 * state); [failWith] answers an error.
 */
internal class FakeCardsOps : CardsOps {
    val renders = CopyOnWriteArrayList<CardRenderRequest>()
    val cancelled = CopyOnWriteArrayList<String>()

    /** While set, a render waits for it. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    @Volatile var fail = false

    override suspend fun render(req: CardRenderRequest): Outcome<CardRenderResult> {
        renders += req
        gate?.await()
        if (fail) {
            return Outcome.Err(
                dev.rosty.core.RustError
                    .Io("out_unwritable"),
            )
        }
        File(req.out).parentFile?.mkdirs()
        val bitmap = draw(req)
        FileOutputStream(req.out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Outcome.Ok(
            CardRenderResult(
                req.out,
                bitmap.width,
                bitmap.height,
                File(req.out).length(),
                if (req.spec.animated) 24 else 1,
            ),
        )
    }

    override suspend fun cancel(out: String) {
        cancelled += out
    }

    private fun draw(req: CardRenderRequest): Bitmap {
        val w = 480
        val h = 600
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = req.spec.palette
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader =
            LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                palette.swatchTop.toInt(),
                palette.swatchBottom.toInt(),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        val ink = palette == CardPalette.Ink
        paint.color = if (ink) 0x66000000 else 0x66FFFFFF
        canvas.drawRoundRect(RectF(40f, 90f, w - 40f, h - 90f), 56f, 56f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = 0x88FFFFFF.toInt()
        canvas.drawRoundRect(RectF(40f, 90f, w - 40f, h - 90f), 56f, 56f, paint)
        paint.style = Paint.Style.FILL
        // The photo disc with initials.
        paint.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(w / 2f, 190f, 56f, paint)
        paint.color = 0xFF3A3F4B.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 44f
        canvas.drawText(req.spec.initials, w / 2f, 206f, paint)
        paint.color = if (ink) 0xFFFFFFFF.toInt() else 0xFF14161C.toInt()
        paint.textSize = 38f
        var y = 320f
        for (key in listOf("title", "age_line", "date_line", "subtitle", "numbers")) {
            val text = req.spec.texts[key] ?: continue
            paint.textSize = if (key == "title") 38f else 28f
            for (line in text.split('\n')) {
                canvas.drawText(line, w / 2f, y, paint)
                y += paint.textSize * 1.4f
            }
        }
        if (req.spec.template == CardTemplate.Contact && req.spec.qr != null) {
            paint.color = if (ink) 0xFFFFFFFF.toInt() else 0xFF14161C.toInt()
            canvas.drawRect(w / 2f - 40f, h - 190f, w / 2f + 40f, h - 110f, paint)
        }
        return bitmap
    }
}

/** Samples for the tests and shots. */
internal object StudioSamples {
    val birthday =
        CardStudioSubject(
            contactId = 1,
            name = "Ayşe Yılmaz",
            birthday = true,
            dateLine = "October 3",
            ageYears = 36,
            numbers = listOf("+90 532 000 11 22"),
            vcard = "BEGIN:VCARD\nFN:Ayşe Yılmaz\nEND:VCARD",
        )

    val plain = birthday.copy(birthday = false, dateLine = null, ageYears = null)
}
