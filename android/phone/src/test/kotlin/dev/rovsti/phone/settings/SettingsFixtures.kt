package dev.rovsti.phone.settings

import dev.rovsti.core.model.phone.BackupPreview
import dev.rovsti.core.model.phone.MovedDigit
import dev.rovsti.phone.settings.data.DataUi
import dev.rovsti.phone.settings.replies.QuickRepliesUi
import dev.rovsti.phone.settings.sim.SimChoiceRow
import dev.rovsti.phone.settings.sim.SimChoicesUi
import dev.rovsti.phone.settings.speeddial.SpeedDialRow
import dev.rovsti.phone.settings.speeddial.SpeedDialUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Values the Settings screens draw in tests and screenshots. */
internal object SettingsFixtures {
    val replyTexts =
        listOf(
            "Can't talk right now, I'll call you back",
            "I'm in a meeting",
            "I'll call you later",
            "On my way, see you in ten minutes",
            "Please send me a message instead",
        )

    fun replies(texts: List<String> = replyTexts) =
        QuickRepliesUi(
            loaded = true,
            replies = texts.toImmutableList(),
            custom = true,
            max = 6,
            maxChars = 100,
            counterAt = 80,
            canAdd = texts.size < 6,
        )

    fun speedDial() =
        SpeedDialUi(
            loaded = true,
            rows =
                persistentListOf(
                    SpeedDialRow(1),
                    SpeedDialRow(2, "Ayse Yilmaz", "+90 555 111 22 33"),
                    SpeedDialRow(3, "Deniz Aksoy", "+90 555 333 44 55"),
                    SpeedDialRow(4),
                    SpeedDialRow(5, "Burak Ozturk", "+90 555 555 66 77"),
                    SpeedDialRow(6),
                    SpeedDialRow(7),
                    SpeedDialRow(8),
                    SpeedDialRow(9),
                ),
            assignedCount = 3,
        )

    fun sims() =
        SimChoicesUi(
            visible = true,
            rows =
                persistentListOf(
                    SimChoiceRow("+90 555 333 44 55", "Deniz Aksoy", "Turkcell", "tel/svc#1"),
                    SimChoiceRow("+90 555 444 55 66", "Elif Sahin", "Vodafone", "tel/svc#2"),
                    SimChoiceRow("+90 212 555 01 02", null, "Turkcell", "tel/svc#1"),
                ),
        )

    fun data() = DataUi(calls = 312, notes = 7, contacts = 148)

    fun preview() =
        BackupPreview(
            settingsChanged = 3,
            quickReplies = 4,
            speedDial = 3,
            favorites = 5,
            moved = listOf(MovedDigit(to = 5, from = 3)),
            clamped = true,
        )
}
