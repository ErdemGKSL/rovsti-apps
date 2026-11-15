package dev.rosty.stops.history

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Appearance
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.data.HistoryStore
import dev.rosty.stops.data.SavedStore
import dev.rosty.stops.data.StopsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S32 on the REAL backup ops: a backup reads back, a restore replaces the three files and its Undo puts them back. */
class BackupTest {
    private val fake = FakeStopsCore(history = HistoryFixtures.history, scriptTransit = false, real = HostCore.real())
    private val ops = NativeStopsOps(fake.rust)
    private val backup =
        Backup(
            ops,
            SavedStore(ops, nowMs = { HistoryFixtures.NOW }),
            HistoryStore(ops),
            StopsStore(ops),
            { HistoryFixtures.NOW },
            { 0 },
            { "2026-09-21" },
        )

    @Test fun aBackupRestoresAndTheRestoreCanBeUndone() =
        runBlocking {
            val file = (backup.export() as Outcome.Ok).value
            assertTrue(file.fileName, file.fileName.endsWith(".json"))
            val parsed = (backup.parse(file.text) as Outcome.Ok).value
            assertEquals(6, parsed.history.records.size)
            assertEquals(2, parsed.saved.stops.size)
            // Things change after the backup...
            fake.history = null
            fake.saved = null
            fake.prefs = fake.prefs.copy(appearance = Appearance.Dark)
            val now =
                BackupSnapshot(
                    parsed.saved.copy(stops = emptyList()),
                    parsed.history.copy(records = emptyList()),
                    fake.prefs,
                )
            backup.apply(now)
            // ...and the restore brings the backup's back, answering what it replaced.
            val old = backup.apply(parsed.snapshot())!!
            assertEquals(0, old.history.records.size)
            assertEquals(6, fake.history!!.records.size)
            assertEquals(2, fake.saved!!.stops.size)
            assertEquals(Appearance.Auto, fake.prefs.appearance)
            backup.apply(old)
            assertEquals(0, fake.history!!.records.size)
        }

    @Test fun anythingElseIsRefused() =
        runBlocking {
            assertTrue(backup.parse("{\"hello\": 1}") is Outcome.Err)
        }
}
