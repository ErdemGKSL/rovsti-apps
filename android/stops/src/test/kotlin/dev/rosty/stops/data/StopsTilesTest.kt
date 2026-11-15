package dev.rosty.stops.data

import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.TilesOpenAnswer
import dev.rosty.core.model.stops.TilesOpenRequest
import dev.rosty.core.ops.NativeTilesOps
import dev.rosty.core.ops.TileHandle
import dev.rosty.core.ops.TileReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StopsTilesTest {
    @Test fun theRequestFollowsThePreferences() {
        val default = tilesOpenRequest(StopsPrefs(), "/data/tiles")
        assertNull("no server is the public VersaTiles one", default.serverUrl)
        assertEquals(128L * 1024 * 1024, default.cacheCap)
        assertEquals("/data/tiles", default.dir)
        val own = tilesOpenRequest(StopsPrefs(tileUrl = " https://t.example/{z}/{x}/{y}.pbf ", tileCacheMb = 512), "d")
        assertEquals("https://t.example/{z}/{x}/{y}.pbf", own.serverUrl)
        assertEquals(512L * 1024 * 1024, own.cacheCap)
        assertNull(tilesOpenRequest(StopsPrefs(tileUrl = "  "), "d").serverUrl)
    }

    @Test fun oneHandleUntilTheServerChangesThenTheOldOneIsClosed() =
        runBlocking {
            val core = FakeRustCore()
            var next = 1L
            val opened = mutableListOf<TilesOpenRequest>()
            core.onTyped("tiles.open", TilesOpenRequest.serializer(), TilesOpenAnswer.serializer()) {
                opened += it
                Outcome.Ok(
                    TilesOpenAnswer(
                        handle = next++,
                        key = "k",
                        template = it.serverUrl ?: "default",
                        isDefault = it.serverUrl == null,
                        capBytes = it.cacheCap ?: 0L,
                        cachedBytes = 0L,
                        cachedTiles = 0,
                    ),
                )
            }
            val released = mutableListOf<Long>()
            var prefs = StopsPrefs()
            val tiles =
                StopsTiles({ prefs }, "/data/tiles") { req ->
                    TileHandle.open(req, NativeTilesOps(core), TileReader { _, _, _, _, _ -> null }, release = {
                        released +=
                            it
                    })
                }
            val first = (tiles.handle() as Outcome.Ok).value
            assertSame(first, (tiles.handle() as Outcome.Ok).value)
            assertEquals(1, opened.size)
            prefs = prefs.copy(tileUrl = "https://t.example/{z}/{x}/{y}.pbf")
            val second = (tiles.handle() as Outcome.Ok).value
            assertEquals(2, opened.size)
            assertEquals(listOf(1L), released)
            assertEquals("https://t.example/{z}/{x}/{y}.pbf", second.opened.template)
            tiles.close()
            assertEquals(listOf(1L, 2L), released)
        }
}
