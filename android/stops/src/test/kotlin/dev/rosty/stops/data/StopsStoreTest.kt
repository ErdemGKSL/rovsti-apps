package dev.rosty.stops.data

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Appearance
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.stops.FakeStopsCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopsStoreTest {
    private val fake = FakeStopsCore(prefs = StopsPrefs(appearance = Appearance.Dark, snoozeMin = 10))
    private val store = StopsStore(NativeStopsOps(fake.core))

    @Test fun loadReadsTheFileOnce() =
        runBlocking {
            assertEquals(Appearance.Auto, store.prefs.value.appearance)
            assertEquals(10, (store.load() as Outcome.Ok).value.snoozeMin)
            assertEquals(Appearance.Dark, store.prefs.value.appearance)
            store.load()
            assertEquals(1, fake.core.calls.count { it.op == "stops.prefs_load" })
        }

    @Test fun anUpdateIsWrittenAndAnUnchangedOneIsNot() =
        runBlocking {
            store.update { it.copy(appearance = Appearance.Light) }
            assertEquals(listOf(Appearance.Light), fake.savedPrefs.map { it.appearance })
            assertEquals(10, fake.savedPrefs.single().snoozeMin)
            store.update { it }
            assertEquals(1, fake.savedPrefs.size)
        }

    @Test fun theCommonSettingsFollowTheFileAndHaveNoHaptics() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val settings = StopsCommonSettings(store, scope)
            assertNull(settings.haptics.value)
            withTimeout(5_000) { settings.appearance.first { it == Appearance.Dark } }
            settings.setAppearance(Appearance.Auto)
            withTimeout(5_000) { settings.appearance.first { it == Appearance.Auto } }
            assertEquals(Appearance.Auto, fake.prefs.appearance)
        }
}
