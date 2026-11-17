package dev.rosty.contacts.cards

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Immutable
import dev.rosty.contacts.R
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardSpec
import dev.rosty.core.ops.CardTemplate
import dev.rosty.design.text.UiText
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Test tags of the Card Studio sheet. */
object CardStudioTags {
    const val Sheet = "card-studio"
    const val Templates = "card-studio-templates"
    const val Preview = "card-studio-preview"
    const val Ring = "card-studio-ring"
    const val Swatches = "card-studio-swatches"
    const val Animated = "card-studio-animated"
    const val Share = "card-studio-share"
    const val Save = "card-studio-save"
    const val ShareMore = "card-studio-share-more"
    const val Replay = "card-studio-replay"

    fun swatch(palette: CardPalette) = "card-studio-swatch-${palette.name.lowercase()}"
}

/**
 * Who the card is for, as the entry points know them: the card page and the Upcoming rows both build one. [birthday]
 * is whether the contact has a birthday (the Birthday template needs it), [birthMonth] and [birthDay] its date (the
 * card prints it in the app's locale: "3 Ekim", "October 3"), [dateLine] a ready-made fallback when the date is not
 * known as numbers, and [ageYears] the age it reaches, when the year is known. [photo] is a readable picture URI or
 * null (initials are drawn then).
 */
@Immutable
data class CardStudioSubject(
    val contactId: Long,
    val name: String,
    val birthday: Boolean,
    val dateLine: String? = null,
    val ageYears: Int? = null,
    val birthMonth: Int? = null,
    val birthDay: Int? = null,
    val photo: String? = null,
    val numbers: List<String> = emptyList(),
    val vcard: String? = null,
) {
    /** The template the sheet opens on: Birthday for a contact with one, else Contact. */
    val defaultTemplate: CardTemplate get() = if (birthday) CardTemplate.Birthday else CardTemplate.Contact

    /**
     * The templates the picker offers: all of them. A contact without a birthday still gets a Birthday card (a
     * greeting by name with no age and no date pill), the sheet just does not open on it.
     */
    val templates: List<CardTemplate>
        get() = CardTemplate.entries.toList()

    val initials: String
        get() =
            name
                .split(' ', ' ')
                .filter { it.isNotBlank() }
                .let { parts ->
                    (
                        parts.firstOrNull().orEmpty().take(1) +
                            parts
                                .drop(1)
                                .lastOrNull()
                                .orEmpty()
                                .take(1)
                    )
                }.uppercase()
}

/** The finished picture on screen: the file and its pixel size. */
@Immutable
data class CardPreview(
    val path: String,
    val width: Int,
    val height: Int,
    val animated: Boolean,
)

/** Everything the sheet shows. [preview] stays while the next render runs, so the box never empties. */
@Immutable
data class CardStudioState(
    val subject: CardStudioSubject,
    val template: CardTemplate,
    val palette: CardPalette = CardPalette.Dawn,
    val animated: Boolean = false,
    val rendering: Boolean = false,
    val preview: CardPreview? = null,
    val failed: Boolean = false,
) {
    /** The Still / Animated switch only exists for templates that move. */
    val canAnimate: Boolean get() = template.animatable

    /** True when Share and Save have something to send. */
    val ready: Boolean get() = preview != null && !rendering
}

sealed interface CardStudioAction {
    data class PickTemplate(
        val template: CardTemplate,
    ) : CardStudioAction

    data class PickPalette(
        val palette: CardPalette,
    ) : CardStudioAction

    data class SetAnimated(
        val on: Boolean,
    ) : CardStudioAction

    data object Share : CardStudioAction

    /** Share the first frame of an animated card as a still image. */
    data object ShareAsStill : CardStudioAction

    data object Save : CardStudioAction

    /** Save the first frame of an animated card to Pictures/Rosty as a still image. */
    data object SaveAsStill : CardStudioAction

    /** The sheet left the screen: cancels the running render. */
    data object Close : CardStudioAction
}

sealed interface CardStudioEffect {
    data class Share(
        val uri: android.net.Uri,
        val mime: String,
        val name: String,
    ) : CardStudioEffect

    data class Toast(
        val text: UiText,
    ) : CardStudioEffect

    /** Something failed: the sheet plays the reject haptic. */
    data object Reject : CardStudioEffect
}

/** The words printed on the card, resolved from string resources so Turkish needs no second table in Rust. */
internal fun cardTexts(
    context: Context,
    subject: CardStudioSubject,
    template: CardTemplate,
): Map<String, String> =
    buildMap {
        put("name", subject.name)
        when (template) {
            CardTemplate.Birthday -> {
                put(
                    "title",
                    if (subject.name.isBlank()) {
                        context.getString(R.string.card_birthday_title_nameless)
                    } else {
                        context.getString(R.string.card_birthday_title, subject.name)
                    },
                )
                subject.ageYears?.takeIf { it > 0 }?.let {
                    put("age_line", context.resources.getQuantityString(R.plurals.card_birthday_age, it, it))
                }
                (cardDate(context, subject.birthMonth, subject.birthDay) ?: subject.dateLine)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("date_line", it) }
            }

            CardTemplate.Contact -> {
                put("title", subject.name)
                put("numbers", subject.numbers.take(MAX_NUMBERS).joinToString("\n"))
            }

            CardTemplate.NewNumber -> {
                put("title", subject.name)
                put("subtitle", context.getString(R.string.card_new_number_subtitle))
                put("numbers", subject.numbers.take(1).joinToString("\n"))
            }
        }
    }

/**
 * The birthday as the card prints it, in the app's locale: "3 Ekim" in Turkish, "October 3" in English. Null when the
 * month or day is unknown or not a real date.
 */
internal fun cardDate(
    context: Context,
    month: Int?,
    day: Int?,
): String? {
    if (month == null || day == null) return null
    val date = runCatching { MonthDay.of(month, day) }.getOrNull() ?: return null
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMMd")
    return DateTimeFormatter.ofPattern(pattern, locale).format(date)
}

/** Month and day of a contact event date (`YYYY-MM-DD` or `--MM-DD`), or null when it is not one. */
fun eventMonthDay(date: String): Pair<Int, Int>? {
    val match = EVENT_DATE.matchEntire(date.trim()) ?: return null
    val month = match.groupValues[1].toInt()
    val day = match.groupValues[2].toInt()
    return runCatching { MonthDay.of(month, day) }.getOrNull()?.let { month to day }
}

private val EVENT_DATE = Regex("^(?:\\d{4}|-)-(\\d{2})-(\\d{2})$")

internal fun CardStudioState.toSpec(texts: Map<String, String>): CardSpec =
    CardSpec(
        template = template,
        palette = palette,
        animated = animated && template.animatable,
        texts = texts,
        photo = subject.photo,
        initials = subject.initials,
        qr = if (template == CardTemplate.Contact) subject.vcard else null,
    )

private const val MAX_NUMBERS = 3
