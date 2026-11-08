package dev.rovsti.core.rust

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.stops.Coord
import dev.rovsti.core.model.stops.TileBuffer
import dev.rovsti.core.model.stops.TilesBbox
import dev.rovsti.core.model.stops.TilesCircle
import dev.rovsti.core.model.stops.TilesOfflineRequest
import dev.rovsti.core.model.stops.TilesOpenRequest
import dev.rovsti.core.model.stops.TilesZooms
import dev.rovsti.core.ops.NativeTilesOps
import dev.rovsti.core.ops.TileHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * E2.13 through the real library: `tiles.open`, the `tile` export with the cache-only flag on a seeded cache
 * directory (no network), the cache ops and an offline area against a localhost server.
 */
class TileHandleTest {
    private val ops = NativeTilesOps(TestRust.core)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contract = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))
    private lateinit var root: File

    private val mvt: ByteArray by lazy {
        val request = Json.parseToJsonElement(File(contract, "tiles.encode_mvt.request.json").readText())
        Base64.getDecoder().decode(
            request.jsonObject
                .getValue("mvt")
                .jsonPrimitive.content,
        )
    }

    @Before
    fun load() {
        TestRust.ensure()
        root = Files.createTempDirectory("rovsti-tiles").toFile()
    }

    @After
    fun tidy() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun open(url: String? = null): TileHandle =
        runBlocking {
            TileHandle.open(TilesOpenRequest(serverUrl = url, dir = root.absolutePath), ops, events = TestRust.events)
        }.getOrOk()

    /** A handle on the default server whose cache already holds the fixture tile. */
    private fun seeded(): TileHandle {
        val key = open().use { it.opened.key }
        File(root, "$key/14/9520/6130.mvt").apply { parentFile.mkdirs() }.writeBytes(mvt)
        return open()
    }

    @Test
    fun aCacheOnlyTileIsReadFromTheSeededDirectoryWithoutTheNetwork() =
        runBlocking {
            seeded().use { tiles ->
                assertEquals(1, tiles.opened.cachedTiles)
                val tile = tiles.tile(14, 9520, 6130, cacheOnly = true).getOrOk()
                assertTrue(tile.cacheOnly)
                assertFalse(tile.dark)
                assertEquals(listOf(14, 9520, 6130), listOf(tile.z, tile.x, tile.y))
                assertEquals(1083, tile.vertexCount)
                assertEquals(15, tile.classes.size)
                val dark = tiles.tile(14, 9520, 6130, dark = true, cacheOnly = true).getOrOk()
                assertTrue(dark.dark && dark.cacheOnly)
                assertNotEquals(tile.background, dark.background)
            }
        }

    @Test
    fun aTileThatIsNotCachedIsNotFoundWhenTheNetworkIsOff() =
        runBlocking {
            seeded().use { tiles ->
                val miss = tiles.tile(14, 9520, 6131, cacheOnly = true)
                val error = (miss as Outcome.Err).error
                assertTrue(error is RustError.NotFound)
                assertEquals(
                    "not_cached",
                    error.detail!!
                        .jsonObject
                        .getValue("reason")
                        .jsonPrimitive.content,
                )
                val outside = tiles.tile(3, 9, 0, cacheOnly = true) as Outcome.Err
                assertTrue(outside.error is RustError.Invalid)
            }
        }

    @Test
    fun aClosedHandleAnswersNotFoundWithoutANativeCall() =
        runBlocking {
            val tiles = seeded()
            tiles.close()
            assertTrue((tiles.tile(14, 9520, 6130, cacheOnly = true) as Outcome.Err).error is RustError.NotFound)
            assertTrue((tiles.usage() as Outcome.Err).error is RustError.NotFound)
        }

    @Test
    fun theCacheOpsReportAndFreeBytes() =
        runBlocking {
            seeded().use { tiles ->
                val usage = tiles.usage().getOrOk()
                assertEquals(1, usage.sourceTiles)
                assertEquals(mvt.size.toLong(), usage.sourceBytes)
                val sources = tiles.usageBySource().getOrOk().sources
                assertEquals(1, sources.size)
                assertTrue(sources[0].current)
                assertEquals(tiles.opened.key, sources[0].key)
                assertEquals(0L, tiles.clearExcept().getOrOk().freedBytes)
                assertEquals(64L * 1024, tiles.setCap(64L * 1024).getOrOk().capBytes)
                assertTrue(tiles.clearAll().getOrOk().freedBytes >= mvt.size)
                assertEquals(0, tiles.usage().getOrOk().sourceTiles)
                assertTrue(tiles.tile(14, 9520, 6130, cacheOnly = true) is Outcome.Err)
            }
        }

    @Test
    fun aBadServerAddressIsInvalidWithAReason() =
        runBlocking {
            val error =
                TileHandle.open(
                    TilesOpenRequest(serverUrl = "https://tiles.example.org/{z}/{x}", dir = root.absolutePath),
                    ops,
                )
                    as Outcome.Err
            assertTrue(error.error is RustError.Invalid)
            assertEquals(
                "placeholder",
                error.error.detail!!
                    .jsonObject
                    .getValue("reason")
                    .jsonPrimitive.content,
            )
        }

    /** A localhost tile server answering the fixture tile to every path after [delayMs]. */
    private class Server(
        private val body: ByteArray,
        private val delayMs: Long,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val hits = AtomicInteger()
        val template = "http://127.0.0.1:${socket.localPort}/{z}/{x}/{y}"

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread {
                        client.use { c ->
                            val input = c.getInputStream()
                            var tail = 0
                            while (tail != 4) {
                                val b = input.read()
                                if (b < 0) return@use
                                tail =
                                    if ((tail % 2 == 0 && b == 13) ||
                                        (tail % 2 == 1 && b == 10)
                                    ) {
                                        tail + 1
                                    } else if (b == 13) {
                                        1
                                    } else {
                                        0
                                    }
                            }
                            hits.incrementAndGet()
                            Thread.sleep(delayMs)
                            c.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        .encodeToByteArray(),
                                )
                                write(body)
                                flush()
                            }
                        }
                    }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = socket.close()
    }

    @Test
    fun anOfflineAreaReportsProgressAndEndsWithTheTilesCached() =
        runBlocking {
            Server(mvt, 0).use { server ->
                open(server.template).use { tiles ->
                    val job =
                        tiles
                            .startOffline(
                                TilesOfflineRequest(
                                    handle = 0,
                                    bbox = TilesBbox(south = 41.0, west = 28.96, north = 41.1, east = 29.06),
                                    zooms = TilesZooms(min = 10, max = 12),
                                ),
                                scope,
                            ).getOrOk()
                    job.use {
                        assertTrue(job.total > 4)
                        val events = withTimeout(30_000) { job.progress.toList() }
                        val last = events.last()
                        assertTrue(last.finished)
                        assertFalse(last.cancelled)
                        assertEquals(job.total, last.total)
                        assertEquals(job.total, last.saved)
                        assertEquals(1f, last.fraction, 0f)
                        assertTrue(events.zipWithNext().all { (a, b) -> b.done >= a.done })
                        assertTrue(last.text.startsWith("Map area ready offline"))
                        assertEquals(job.total, server.hits.get())
                        assertEquals(job.total, tiles.usage().getOrOk().sourceTiles)
                    }
                }
            }
        }

    @Test
    fun anOfflineAreaCanBeCancelledAndKeepsWhatWasSaved() =
        runBlocking {
            Server(mvt, 150).use { server ->
                open(server.template).use { tiles ->
                    val area =
                        TilesOfflineRequest(
                            handle = 0,
                            circle = TilesCircle(Coord(41.0, 28.994), radiusM = 20_000.0, budget = 300),
                            zooms = TilesZooms(min = 9, max = 12),
                        )
                    val job = tiles.startOffline(area, scope).getOrOk()
                    job.use {
                        assertTrue(job.total > 20)
                        Thread.sleep(300)
                        val stopped = job.cancel().getOrOk()
                        assertEquals(job.total, stopped.total)
                        val last = withTimeout(30_000) { job.progress.toList() }.last()
                        assertTrue(last.finished && last.cancelled)
                        assertTrue(last.done < job.total)
                        assertTrue(last.text.startsWith("Download cancelled."))
                        assertEquals(last.saved, tiles.usage().getOrOk().sourceTiles)
                        assertTrue(last.saved > 0)
                        assertTrue(server.hits.get() < job.total)
                    }
                    assertTrue((job.cancel() as Outcome.Err).error is RustError.NotFound)
                }
            }
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
