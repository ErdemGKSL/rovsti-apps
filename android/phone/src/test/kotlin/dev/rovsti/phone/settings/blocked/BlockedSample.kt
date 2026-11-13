package dev.rovsti.phone.settings.blocked

import dev.rovsti.core.model.phone.BlockReason
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** The rows the real core makes for a few blocked numbers, as plain data for the screen tests and screenshots. */
internal object BlockedSample {
    private fun number(
        id: Int,
        title: String,
        subtitle: String? = null,
    ) = BlockedRowUi("n-$id", title, subtitle, persistentListOf(title))

    val numbers =
        listOf(
            number(1, "+90 532 111 22 33"),
            number(2, "+49 151 2345678", "Hans Müller"),
            number(3, "0850 333 44 55"),
            number(4, "+44 20 7946 0958"),
            number(5, "+1 415 555 0132"),
        )

    val ranges =
        listOf(
            BlockedRowUi("r-+90 850", "+90 850 *", null, persistentListOf("+90 850"), range = true),
            BlockedRowUi("r-+44 7700", "+44 7700 *", null, persistentListOf("+44 7700"), range = true),
        )

    val calls =
        listOf(
            BlockedCallUi("c-1", "+90 532 111 22 33", "+905321112233", BlockReason.User, "Yesterday"),
            BlockedCallUi("c-2", "0850 333 44 55", "08503334455", BlockReason.System, "Monday"),
            BlockedCallUi("c-3", "+49 151 2345678", "+491512345678", BlockReason.User, "11/9/26"),
        )

    fun ui(
        numbers: List<BlockedRowUi> = this.numbers,
        ranges: List<BlockedRowUi> = this.ranges,
        calls: List<BlockedCallUi> = this.calls,
        canWrite: Boolean = true,
        paste: PastePreview? = null,
        prefix: PrefixPreview? = null,
    ) = BlockedUi(
        ready = true,
        canWrite = canWrite,
        numbers = numbers.toImmutableList(),
        ranges = ranges.toImmutableList(),
        calls = calls.toImmutableList(),
        paste = paste,
        prefix = prefix,
    )
}
