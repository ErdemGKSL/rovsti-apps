package dev.rovsti.phone.stats

import dev.rovsti.core.model.phone.StatDir
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** What the core would answer for the screenshots, with its words. */
internal object StatsSample {
    private fun bars(
        labels: List<String>,
        names: List<String?>,
        calls: List<Int>,
    ) = labels
        .mapIndexed { i, label ->
            StatsBarUi(i, label, names[i], calls[i], calls[i] * 130L, calls[i].toFloat() / calls.max())
        }.toImmutableList()

    private val days = listOf("Wed", "Thu", "Fri", "Sat", "Sun", "Mon", "Tue")
    private val dayNames = listOf("Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Monday", "Tuesday")

    private val people =
        persistentListOf(
            StatsPersonUi("+905551112233", "Ayse Yilmaz", "Ayse Yilmaz", "+905551112233", 9, "9 calls · 1 h 05 min", 1),
            StatsPersonUi("+905559998877", "Mert Kaya", "Mert Kaya", "+905559998877", 5, "5 calls · 32 min", 2),
            StatsPersonUi("+4930123456", "+49 30 123456", null, "+4930123456", 2, "2 calls · 30 min", 0),
            StatsPersonUi("+905553334455", "Deniz Aksoy", "Deniz Aksoy", "+905553334455", 3, "3 calls · 4 min", 1),
        )

    private val records =
        StatsRecordsUi(
            StatsRecordUi("Tue 14 Nov", "5 calls · 21 min"),
            StatsRecordUi("Mert Kaya", "21:13 · Incoming · 30:12"),
        )

    fun week(selected: Int? = null) =
        StatsUi(
            ready = true,
            range = dev.rovsti.core.model.phone.StatRange.Week,
            summary = "19 calls · 1 h 52 min",
            cells = StatsCellsUi(19, 8, 7, 4, "1 h 52 min"),
            bars = bars(days, dayNames, listOf(0, 2, 1, 3, 4, 3, 6)),
            people = people,
            records = records,
            selected = selected,
        )

    fun weeks8() =
        StatsUi(
            ready = true,
            range = dev.rovsti.core.model.phone.StatRange.Weeks8,
            summary = "97 calls · 9 h 40 min",
            cells = StatsCellsUi(97, 41, 39, 17, "9 h 40 min"),
            bars =
                bars(
                    listOf("25 Sep", "2 Oct", "9 Oct", "16 Oct", "23 Oct", "30 Oct", "6 Nov", "13 Nov"),
                    List(8) { null },
                    listOf(8, 14, 11, 0, 16, 13, 21, 14),
                ),
            people = people,
            records = records.copy(busiest = StatsRecordUi("Week of 6 Nov", "21 calls · 2 h 02 min")),
        )

    fun empty() = StatsUi(ready = true, cells = StatsCellsUi(0, 0, 0, 0, "0 min"))

    fun bucket() =
        BucketUi(
            title = "Tue 14 Nov",
            summary = "6 calls · 21 min",
            lines =
                persistentListOf(
                    line("21:13", "Mert Kaya", "21:13 · Incoming · 12:30", StatDir.Incoming),
                    line("18:40", "Ayse Yilmaz", "18:40 · Outgoing · 2:05", StatDir.Outgoing),
                    line("15:02", "+49 30 123456", "15:02 · Missed", StatDir.Missed),
                    line("12:20", "Ayse Yilmaz", "12:20 · Incoming · 0:48 · WhatsApp", StatDir.Incoming),
                    line("09:31", "Deniz Aksoy", "09:31 · Outgoing · 4:12", StatDir.Outgoing),
                    line("08:05", "Mert Kaya", "08:05 · Missed", StatDir.Missed),
                ),
        )

    private fun line(
        key: String,
        title: String,
        detail: String,
        dir: StatDir,
    ) = BucketLineUi(
        key,
        title,
        detail,
        title.takeIf {
            !it.startsWith("+")
        },
        "+905551112233",
        dir,
        detail.endsWith("WhatsApp"),
    )
}
