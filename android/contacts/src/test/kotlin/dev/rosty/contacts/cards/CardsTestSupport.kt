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
 * A stand-in for the Rust renderer. A spec the Rust card goldens cover (`crates/rosty_app_core/tests/cards`, written by
 * `card_goldens_match` from the same texts [cardTexts] builds) gets that golden copied to [req.out], so the screenshots
 * show the real card; any other spec gets a card-shaped placeholder drawn here. The real op is proven by
 * `CardsOpsNativeTest` (`:core-bridge` `testRustBridge`). [gate] holds a render open (the progress ring state);
 * [fail] answers an error.
 */
internal class FakeCardsOps : CardsOps {
    val renders = CopyOnWriteArrayList<CardRenderRequest>()
    val cancelled = CopyOnWriteArrayList<String>()

    /** The requests answered with the drawn placeholder instead of a Rust golden; the screenshot tests assert none. */
    val placeholders = CopyOnWriteArrayList<CardRenderRequest>()

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
        golden(req)?.let { file ->
            file.copyTo(File(req.out), overwrite = true)
            return Outcome.Ok(
                CardRenderResult(req.out, GOLDEN_W, GOLDEN_H, file.length(), if (req.spec.animated) 24 else 1),
            )
        }
        placeholders += req
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

    /**
     * The Rust golden of this spec, if one exists: the goldens are keyed by template, palette and (for the birthday) the
     * language of the title, and are only used for the sample contact. Dawn birthday en/tr, Ink birthday tr, Dawn and
     * Lagoon contact, Meadow new number.
     */
    private fun golden(req: CardRenderRequest): File? {
        val spec = req.spec
        if (spec.texts["name"] != StudioSamples.birthday.name) return null
        val turkish = spec.texts["title"].orEmpty().startsWith("Doğum")
        val name =
            when (spec.template) {
                CardTemplate.Birthday -> {
                    when {
                        spec.palette == CardPalette.Dawn -> if (turkish) "birthday-dawn-tr" else "birthday-dawn-en"
                        spec.palette == CardPalette.Ink && turkish -> "birthday-ink-tr"
                        else -> null
                    }
                }

                CardTemplate.Contact -> {
                    when (spec.palette) {
                        CardPalette.Dawn -> "contact-dawn-en"
                        CardPalette.Lagoon -> "contact-lagoon-en"
                        else -> null
                    }
                }

                CardTemplate.NewNumber -> {
                    if (spec.palette == CardPalette.Meadow) "number-meadow-tr" else null
                }
            } ?: return null
        return File(GOLDENS, "$name.png").takeIf { it.isFile }
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

/** The Rust card goldens, from the module directory the tests run in. */
private val GOLDENS = File("../../crates/rosty_app_core/tests/cards")
private const val GOLDEN_W = 720
private const val GOLDEN_H = 900

/** Samples for the tests and shots. */
internal object StudioSamples {
    val birthday =
        CardStudioSubject(
            contactId = 1,
            name = "Ayşe Yılmaz",
            birthday = true,
            dateLine = "October 3",
            ageYears = 36,
            birthMonth = 10,
            birthDay = 3,
            numbers = listOf("+90 532 000 11 22"),
            vcard = "BEGIN:VCARD\nFN:Ayşe Yılmaz\nEND:VCARD",
        )

    val plain = birthday.copy(birthday = false, dateLine = null, ageYears = null, birthMonth = null, birthDay = null)

    /** The owner's device report: the name is the number itself and there is no birthday. */
    val numberNamed =
        plain.copy(contactId = 7, name = "+90 555 000 12 34", numbers = listOf("+90 555 000 12 34"), vcard = null)
}
