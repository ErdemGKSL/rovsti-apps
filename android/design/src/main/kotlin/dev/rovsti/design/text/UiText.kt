package dev.rovsti.design.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Text a view model asks the screen to show. View models have no Context, so they name a string resource (with its
 * format arguments) or a plural, or pass text that is already final (a notice the Rust core wrote). The screen turns it
 * into a String with [resolve] or [text], so the language follows the phone.
 */
sealed interface UiText {
    /** A `<string>` resource. [args] fill its `%1$s` / `%1$d` placeholders. */
    data class Res(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** A `<plurals>` resource. [count] picks the quantity and is also the first format argument. */
    data class Plural(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** Text that is already in the user's language (a notice from the Rust core). */
    data class Literal(
        val value: String,
    ) : UiText
}

/**
 * The text for this value in [context]'s resources. A [UiText] among the format arguments resolves to its own text
 * first, so a sentence can be built from parts that each name a resource.
 */
fun UiText.resolve(context: Context): String =
    when (this) {
        is UiText.Res -> {
            val formatArgs = args.map { if (it is UiText) it.resolve(context) else it }
            if (formatArgs.isEmpty()) context.getString(id) else context.getString(id, *formatArgs.toTypedArray())
        }

        is UiText.Plural -> {
            val formatArgs = listOf<Any>(count) + args.map { if (it is UiText) it.resolve(context) else it }
            context.resources.getQuantityString(id, count, *formatArgs.toTypedArray())
        }

        is UiText.Literal -> {
            value
        }
    }

/** The text for this value in the current locale. */
@Composable
fun UiText.text(): String = resolve(LocalContext.current)
