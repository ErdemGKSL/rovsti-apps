package dev.rovsti.platform.notify

import android.app.Application
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every channel id and importance equals the committed table (`notification_channels.tsv`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationChannelsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    private data class Row(
        val id: String,
        val importance: Int,
        val name: String,
    )

    private val table: List<Row> =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("notification_channels.tsv"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Row(it[0], it[1].toInt(), it[2]) } }

    @Test fun theTableHasTwelveChannels() = assertEquals(12, table.size)

    @Test fun ensuringEveryChannelGivesExactlyTheCommittedIdsAndImportances() {
        for (row in table) assertTrue(PlatformChannels.ensure(app, row.id))
        val actual = manager.notificationChannels.associate { it.id to it.importance }
        assertEquals(table.associate { it.id to it.importance }, actual)
    }

    @Test fun namesMatchToo() {
        for (row in table) PlatformChannels.ensure(app, row.id)
        assertEquals(
            table.associate { it.id to it.name },
            manager.notificationChannels.associate {
                it.id to
                    it.name.toString()
            },
        )
    }

    @Test fun theCodeTableAndTheCommittedTableListTheSameIds() {
        assertEquals(table.map { it.id }.toSet(), PlatformChannels.all.map { it.id }.toSet())
        assertEquals(
            table.associate { it.id to it.importance },
            PlatformChannels.all.associate { it.id to it.importance },
        )
    }

    @Test fun eachChannelIsCreatedByItsOwnEnsureAndNothingElse() {
        for (row in table) {
            manager.notificationChannels.forEach { manager.deleteNotificationChannel(it.id) }
            PlatformChannels.ensure(app, row.id)
            val created = manager.notificationChannels.filter { it.id != "miscellaneous" }
            assertEquals(listOf(row.id), created.map { it.id })
            assertEquals(row.importance, created.single().importance)
        }
    }

    @Test fun anExistingChannelKeepsTheUsersImportance() {
        PlatformChannels.ensure(app, PlatformChannels.MESSAGES)
        manager.getNotificationChannel(PlatformChannels.MESSAGES).importance = NotificationManager.IMPORTANCE_LOW
        PlatformChannels.ensure(app, PlatformChannels.MESSAGES)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(PlatformChannels.MESSAGES).importance,
        )
    }

    /** F32: a channel made before the update (English words) gets today's words on the next ensure, and only them. */
    @Test
    @Config(qualifiers = "tr")
    fun anExistingChannelGetsTheWordsOfTheLanguageOnEnsure() {
        manager.createNotificationChannel(
            android.app.NotificationChannel(PlatformChannels.MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.getNotificationChannel(PlatformChannels.MESSAGES).importance = NotificationManager.IMPORTANCE_LOW
        PlatformChannels.ensure(app, PlatformChannels.MESSAGES)
        val channel = manager.getNotificationChannel(PlatformChannels.MESSAGES)
        assertEquals("Mesajlar", channel.name.toString())
        assertEquals("Yeni mesajlar", channel.description)
        assertEquals("the user's importance stays", NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    @Config(qualifiers = "tr")
    fun aTurkishPhoneCreatesTurkishChannels() {
        for (row in table) PlatformChannels.ensure(app, row.id)
        val names = manager.notificationChannels.associate { it.id to it.name.toString() }
        assertEquals("Gelen aramalar", names[PlatformChannels.INCOMING_CALL])
        assertEquals("Varış alarmı", names[PlatformChannels.TRIP_ALARM])
        for (row in table) {
            assertTrue(
                "${row.id} is in Turkish",
                names.getValue(row.id) != row.name || row.name == "WhatsApp",
            )
        }
    }

    @Test fun aLanguageChangeRelabelsEveryExistingChannel() {
        for (row in table) PlatformChannels.ensure(app, row.id)
        assertEquals("Missed calls", manager.getNotificationChannel(PlatformChannels.MISSED_CALLS).name.toString())
        RuntimeEnvironment.setQualifiers("tr")
        PlatformChannels.relabel(app)
        val missed = manager.getNotificationChannel(PlatformChannels.MISSED_CALLS)
        assertEquals("Cevapsız aramalar", missed.name.toString())
        assertEquals("Yanıtlanmayan aramalar", missed.description)
        assertEquals(
            "relabel creates nothing",
            table.map { it.id }.toSet(),
            manager.notificationChannels.map { it.id }.toSet() - "miscellaneous",
        )
    }

    @Test fun theConstantsAreTheGpuiIds() {
        assertEquals(
            listOf(
                "messages",
                "birthdays",
                "reminders",
                "message_reminders",
                "wa_calls",
                "wa_missed",
                "trip_problem",
                "trip",
                "trip_alarm_v2",
                "ongoing",
                "incoming",
            ),
            listOf(
                PlatformChannels.MESSAGES,
                PlatformChannels.BIRTHDAYS,
                PlatformChannels.CALL_REMINDERS,
                PlatformChannels.MESSAGE_REMINDERS,
                PlatformChannels.WA_CALLS,
                PlatformChannels.WA_MISSED,
                PlatformChannels.TRIP_PROBLEM,
                PlatformChannels.TRIP,
                PlatformChannels.TRIP_ALARM,
                PlatformChannels.ONGOING_CALL,
                PlatformChannels.INCOMING_CALL,
            ),
        )
    }
}
