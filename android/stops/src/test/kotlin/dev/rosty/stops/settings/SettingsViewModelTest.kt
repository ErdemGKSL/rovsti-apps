package dev.rosty.stops.settings

import dev.rosty.core.model.stops.AlarmSound
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.VibrationPattern
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.design.text.UiText
import dev.rosty.platform.location.AlarmPick
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.R
import dev.rosty.stops.data.StopsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * E7.10: the Settings root, the Alarm page and the transit feed on the REAL Rust core (prefs_edit, prefs_choices,
 * alarm_plan, gtfs_import/summary, store_gtfs_remove) over the fake's preference file; the phone's alarm is recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class SettingsViewModelTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private var fake = FakeStopsCore(real = HostCore.real())
    private val device = FakeAlarmDevice()
    private var transitChanges = 0
    private lateinit var model: SettingsViewModel

    @Before fun setUp() {
        main = newSingleThreadContext("main")
        Dispatchers.setMain(main)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    private fun start(prefs: StopsPrefs = StopsPrefs()) {
        fake.prefs = prefs
        val ops = NativeStopsOps(fake.rust)
        model = SettingsViewModel(StopsStore(ops), ops, device, nowMs = { NOW }, transitChanged = { transitChanges++ })
        settled { true }
    }

    private fun settled(check: (SettingsUiState) -> Boolean): SettingsUiState =
        runBlocking { withTimeout(TIMEOUT) { model.state.first { !it.loading && check(it) } } }

    private fun nextEffect(): SettingsEffect = runBlocking { withTimeout(TIMEOUT) { model.effects.first() } }

    private fun waitFor(check: () -> Boolean) =
        runBlocking {
            withTimeout(TIMEOUT) {
                while (!check()) kotlinx.coroutines.delay(10)
            }
        }

    @Test fun soundAndVibrationAreNeverBothOff() {
        start()
        model.toggleVibrate()
        assertFalse(settled { !it.alarm.vibrate }.alarm.vibrate)
        assertFalse(fake.prefs.vibrate)
        model.toggleSound()
        assertEquals(SettingsEffect.Toast(UiText.Res(R.string.settings_keep_one_on)), nextEffect())
        assertTrue("Rust kept the sound on", fake.prefs.sound)
        assertEquals(R.string.settings_alarm_sound_only, alarmSummary(model.state.value.alarm))
    }

    @Test fun choicesComeFromRustAndAreReachedThroughItsCycle() {
        start()
        val state = settled { it.alarm.volumeChoices.isNotEmpty() }
        assertEquals(listOf(80, 90, 100), state.alarm.volumeChoices)
        assertEquals(
            VibrationPattern.entries.toSet(),
            state.alarm.vibrationChoices
                .map { it.id }
                .toSet(),
        )
        model.setVolume(100)
        settled { it.alarm.volumePct == 100 }
        assertEquals("one write for the whole cycle", 1, fake.savedPrefs.size)
        assertFalse(model.state.value.alarm.isDefault)
        model.nextSnooze()
        settled { it.alarm.snoozeMin != 5 }
    }

    @Test fun aPatternIsChosenAndBuzzesItsPreview() {
        start()
        val pulse =
            settled { it.alarm.vibrationChoices.isNotEmpty() }.alarm.vibrationChoices.first {
                it.id ==
                    VibrationPattern.Pulse
            }
        model.setVibration(pulse)
        settled { it.alarm.vibration == VibrationPattern.Pulse }
        waitFor { device.vibrations.isNotEmpty() }
        assertEquals(pulse.previewMs, device.vibrations.single())
    }

    @Test fun resetPutsOnlyTheAlarmBackAndUndoRestoresIt() {
        start(StopsPrefs(snoozeMin = 15, volumePct = 100, tileUrl = "https://tiles.example.org/{z}/{x}/{y}"))
        assertFalse(settled { !it.alarm.isDefault }.alarm.isDefault)
        model.resetAlarm()
        val undo = nextEffect() as SettingsEffect.Undo
        assertEquals(UiText.Res(R.string.settings_alarm_reset_done), undo.text)
        assertTrue(settled { it.alarm.isDefault }.alarm.isDefault)
        assertEquals(5, fake.prefs.snoozeMin)
        assertEquals("the tile server stays", "https://tiles.example.org/{z}/{x}/{y}", fake.prefs.tileUrl)
        undo.undo()
        settled { !it.alarm.isDefault && it.alarm.snoozeMin == 15 }
        assertEquals(100, fake.prefs.volumePct)
    }

    @Test fun aPickedSoundIsSavedAndUndoGoesBack() {
        start()
        model.onSoundPicked(AlarmPick.Cancelled)
        model.onSoundPicked(AlarmPick.Chosen("content://media/3", "Birds"))
        val undo = nextEffect() as SettingsEffect.Undo
        assertEquals(UiText.Res(R.string.settings_sound_set, listOf(UiText.Literal("Birds"))), undo.text)
        assertEquals(AlarmSound("content://media/3", "Birds"), fake.prefs.alarmSound)
        assertEquals("Birds", settled { it.alarm.soundTitle != null }.alarm.soundTitle)
        undo.undo()
        settled { it.alarm.soundTitle == null }
        assertNull(fake.prefs.alarmSound)
        model.onSoundPicked(AlarmPick.Chosen("content://media/4", ""))
        assertEquals(
            UiText.Res(R.string.settings_sound_set, listOf(UiText.Res(R.string.settings_sound_unnamed))),
            (nextEffect() as SettingsEffect.Undo).text,
        )
        model.onSoundPicked(AlarmPick.Default)
        nextEffect()
        assertNull(fake.prefs.alarmSound)
    }

    @Test fun theVolumeTestPlaysRustsPlanAndTheTestRingItsChannels() {
        start(StopsPrefs(volumePct = 90, vibrate = false))
        model.testVolume()
        assertEquals(SettingsEffect.Toast(UiText.Res(R.string.settings_test_volume_playing)), nextEffect())
        assertTrue(device.played.single(), device.played.single().contains("\"volume_pct\":90"))
        model.testAlarm()
        waitFor { device.rings.isNotEmpty() }
        assertTrue(device.rings.single(), device.rings.single().contains("\"vibrate\":false"))
        device.ringing = true
        model.testVolume()
        assertEquals(SettingsEffect.Toast(UiText.Res(R.string.settings_alarm_busy)), nextEffect())
        model.leaveAlarm()
        assertEquals(1, device.stops)
    }

    @Test fun aFeedIsImportedSummarisedAndRemoved() {
        start()
        assertFalse(settled { true }.transit.hasFeed)
        val copy = File.createTempFile("feed", ".zip")
        tinyFeed().copyTo(copy, overwrite = true)
        model.importFeed(copy.absolutePath)
        val imported = settled { it.transit.hasFeed && !it.transit.reading }.transit
        assertNotNull(imported.summary)
        assertNotNull(imported.imported)
        assertFalse("the cache copy is gone", copy.exists())
        // The toast follows the map's hook, so once it is here the hook has run.
        assertEquals(SettingsEffect.Toast(UiText.Res(R.string.settings_transit_imported)), nextEffect())
        assertEquals(1, transitChanges)
        model.removeFeed()
        assertEquals(SettingsEffect.Toast(UiText.Res(R.string.settings_transit_removed)), nextEffect())
        assertFalse(settled { !it.transit.hasFeed }.transit.hasFeed)
        assertEquals(2, transitChanges)
    }

    @Test fun aBrokenFeedShowsWhyAndKeepsNothing() {
        start()
        val junk = File.createTempFile("junk", ".zip").apply { writeText("not a zip") }
        model.importFeed(junk.absolutePath)
        val transit = settled { it.transit.error != null }.transit
        assertFalse(transit.hasFeed)
        assertEquals(R.string.settings_transit_failed, (transit.error as UiText.Res).id)
        model.importFailed(tooLarge = true)
        assertEquals(
            UiText.Res(R.string.settings_transit_too_large),
            settled {
                it.transit.error is UiText.Res &&
                    (it.transit.error as UiText.Res).id == R.string.settings_transit_too_large
            }.transit.error,
        )
    }

    private fun tinyFeed(): File =
        File(System.getProperty("rosty.ffi.fixtures"), "../tests/data/gtfs-tiny.zip").canonicalFile

    private companion object {
        const val NOW = 1_790_000_000_000L
        const val TIMEOUT = 10_000L
    }
}
