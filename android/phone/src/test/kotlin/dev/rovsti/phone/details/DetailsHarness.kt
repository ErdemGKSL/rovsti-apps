package dev.rovsti.phone.details

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.model.phone.CallType
import dev.rovsti.core.model.phone.EntryRef
import dev.rovsti.core.model.phone.HistoryLine
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal val strings get() = DetailsStrings(ApplicationProvider.getApplicationContext<Context>().resources)

/** What the core would answer for the people of the screenshots, with its words. */
internal object DetailsSample {
    private const val DAY = 86_400_000L
    private const val T = 1_790_000_000_000L

    fun line(
        n: Long,
        title: String,
        detail: String,
        type: CallType,
        number: String = "+90 555 111 22 33",
        whatsapp: Boolean = false,
        duration: Long = 0,
    ) = HistoryLineUi(
        key = "log-$n",
        title = title,
        detail = detail,
        callType = type,
        missed = type == CallType.Missed,
        whatsapp = whatsapp,
        blocked = false,
        line = HistoryLine(EntryRef.Log(n), false, type, whatsapp, T - n * DAY / 4, duration, number),
        number = number,
    )

    fun person(
        named: Boolean = true,
        stats: Boolean = true,
        photo: dev.rovsti.phone.details.photo.PersonPhoto? = null,
    ): PersonUi {
        val number = if (named) "+90 555 111 22 33" else "+49 30 1234567"
        return PersonUi(
            ready = true,
            title = if (named) "Ayse Yilmaz" else number,
            subtitle = if (named) null else "Germany",
            named = named,
            colorKey = number,
            photo = photo,
            numbers =
                if (named) {
                    persistentListOf(
                        NumberRowUi("+905551112233", number, "mobile"),
                        NumberRowUi("+902125550101", "+90 212 555 01 01", "work"),
                    )
                } else {
                    persistentListOf(NumberRowUi("+4930123456", number, null))
                },
            history =
                persistentListOf(
                    line(1, "Missed call", "12:41", CallType.Missed, number),
                    line(2, "Outgoing call", "Yesterday, 18:20 · 2:05", CallType.Outgoing, number, duration = 125),
                    line(
                        3,
                        "Incoming call",
                        "Mon, 09:12 · 14:40",
                        CallType.Incoming,
                        number,
                        whatsapp = true,
                        duration = 880,
                    ),
                    line(4, "Missed call", "Sun, 20:03", CallType.Missed, number),
                    line(5, "Outgoing call", "Fri, 11:30 · 0:48", CallType.Outgoing, number, duration = 48),
                ).toImmutableList(),
            stats =
                if (stats) {
                    StatsUi(
                        calls = 5,
                        incoming = 1,
                        outgoing = 2,
                        missed = 2,
                        lines =
                            persistentListOf(
                                "All time: 5 calls · 17 min",
                                "Incoming 1 · Outgoing 2 · Missed 2",
                                "Longest call: 14 min 40 s",
                                "This week: 5 calls · 17 min",
                            ),
                    )
                } else {
                    null
                },
        )
    }

    fun call(note: String = "") =
        CallDetailsUi(
            ready = true,
            title = "Outgoing call",
            whenText = "Mon 21 Sep 2026, 16:13",
            duration = "2:05",
            channel = "Turkcell",
            number = "+90 212 555 01 02",
            note = note,
            deletable = true,
            copyText = "",
            shareText = "",
        )
}

/** Opens the Contact sheet on a stateless body, the way the presenter does. */
internal fun OverlayController.presentPerson(
    ui: PersonUi,
    record: (PersonAction) -> Unit = {},
    detent: Detent = Detent.Medium,
): SheetEntry =
    present(
        SheetEntry(title = "Contact", detents = PersonDetents, initialDetent = detent) { _ ->
            val menu = remember(ui) { personMenu(ui, strings, record) }
            PersonSheetContent(ui, strings, menu, record, LocalOverlayOrNull.current)
        },
    ) as SheetEntry

/** What a Call details sheet over [CallDetailsContent] reports. */
internal class CallHarness(
    ui: CallDetailsUi = DetailsSample.call(),
) {
    var ui by mutableStateOf(ui)
    val actions = mutableListOf<CallDetailsAction>()
    var editStarts = 0
    var photo: dev.rovsti.phone.details.photo.PersonPhoto? = null
    lateinit var note: TextFieldState
}

internal fun OverlayController.presentCall(
    h: CallHarness,
    detent: Detent = Detent.Medium,
): SheetEntry =
    present(
        SheetEntry(title = "Call details", detents = PersonDetents, initialDetent = detent) { _ ->
            h.note = rememberTextFieldState()
            CallDetailsContent(
                ui = h.ui,
                who = "Mehmet Demir",
                strings = strings,
                note = h.note,
                photo = h.photo,
                onAction = { h.actions += it },
                onEditStart = { h.editStarts++ },
            )
        },
    ) as SheetEntry
