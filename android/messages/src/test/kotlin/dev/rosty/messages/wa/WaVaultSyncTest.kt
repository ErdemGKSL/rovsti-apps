package dev.rosty.messages.wa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.InstanceState
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.StatusList
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.model.wa.WaSyncEvent
import dev.rosty.core.model.wa.WaSyncFetch
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncOutcome
import dev.rosty.core.model.wa.WaSyncPlan
import dev.rosty.core.model.wa.WaSyncStoreChatsRequest
import dev.rosty.core.model.wa.WaSyncStorePageRequest
import dev.rosty.core.model.wa.WaSyncStoredChats
import dev.rosty.core.model.wa.WaSyncStoredPage
import dev.rosty.core.ops.WaSyncOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.INSTANCE
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.messages.wa.status.StatusFeed
import dev.rosty.people.LiveName
import dev.rosty.people.MemoryLiveNameStore
import dev.rosty.platform.system.FakeDeviceData
import dev.rosty.wa.sync.WaBackoff
import dev.rosty.wa.sync.WaRequestBudget
import dev.rosty.wa.sync.WaRequestGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * The memory-only store and the lazy sync of `plans/wa-vault.md` 13.1 and 14: what a cold start, an opened chat, a
 * scroll and a tap cost the gateway, the lock, and that nothing of WhatsApp reaches the app's data directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaVaultSyncTest {
    @get:Rule val folder = TemporaryFolder()

    /** Every gateway request of the sync, by kind, in order: the event socket counts as one. */
    private class CountingRemote(
        val inner: FakeWaRemote = FakeWaRemote(),
    ) : WaRemote by inner {
        val requests = mutableListOf<String>()

        override fun events(
            instance: String,
            after: Long?,
        ): Flow<WaRemoteEvent> = inner.events(instance, after).onStart { requests += "socket" }

        override suspend fun chats(): Outcome<List<RichChat>> = inner.chats().also { requests += "chats" }

        override suspend fun identityMappings(since: String?): Outcome<IdentityMappingsAnswer> =
            inner.identityMappings(since).also { requests += "identity" }

        override suspend fun status(): Outcome<InstanceStatus> = inner.status().also { requests += "status" }

        override suspend fun page(fetch: WaSyncFetch): Outcome<List<RichMessage>> =
            inner.page(fetch).also { requests += "page:${fetch.chat}" }

        override suspend fun media(messageId: String): Outcome<BytesAnswer> =
            inner.media(messageId).also { requests += "media:$messageId" }

        override suspend fun thumbnail(messageId: String): Outcome<BytesAnswer> =
            inner.thumbnail(messageId).also { requests += "thumb:$messageId" }

        override suspend fun statuses(): Outcome<StatusList> = inner.statuses().also { requests += "statuses" }

        override suspend fun perform(
            chat: String,
            action: WaSyncAction,
        ): Outcome<WaSyncOutcome.Sent> = inner.perform(chat, action).also { requests += "send" }
    }

    /**
     * The store as Rust answers a cold start after messages were missed in every chat: the eager sync owes two pages per
     * chat, the lazy one only the chat on screen, one page and no next (the Rust side is `wa_sync::lazy_tests`).
     */
    private class Gapped(
        private val fake: FakeWaSyncOps,
        private val chats: List<String>,
    ) : WaSyncOps by fake {
        override suspend fun storeChats(req: WaSyncStoreChatsRequest): Outcome<WaSyncStoredChats> {
            fake.storeChats(req)
            val first = chats.map { WaSyncFetch(it, null, PAGE) }
            val owed = if (fake.lazy) first.filter { it.chat == fake.focused } else first
            return Outcome.Ok(WaSyncStoredChats(FakeWaSyncOps.HANDLE, req.chats.size, owed))
        }

        override suspend fun storePage(req: WaSyncStorePageRequest): Outcome<WaSyncStoredPage> {
            val stored = fake.storePage(req).getOrThrow()
            val next = if (!fake.lazy && req.before == null) WaSyncFetch(req.chat, OLDER, PAGE) else null
            return Outcome.Ok(stored.copy(next = next))
        }
    }

    private class Kit(
        scope: CoroutineScope,
        lazy: Boolean,
        clock: () -> Long,
        chats: Int = CHATS,
        val ram: HeapRamFiles? = null,
        mediaDir: File? = null,
        startLocked: Boolean = false,
    ) {
        val fake = FakeWaSyncOps(INSTANCE)
        val names = (1..chats).map { "1000000000%02d@s.whatsapp.net".format(it) }
        val remote = CountingRemote()
        val gate =
            if (lazy) {
                WaRequestGate(
                    WaRequestBudget(clock),
                    WaBackoff(clock, random = { 1.0 }),
                    sleep = { delay(it) },
                )
            } else {
                null
            }
        val locks = mutableListOf<String>()
        val downloads =
            mediaDir?.let { dir ->
                WaDownloads(
                    scope,
                    remote,
                    dir,
                    clock,
                    FakeDeviceData(),
                    io = Dispatchers.Unconfined,
                    ram = ram,
                    requests = gate,
                )
            }
        val offline =
            WaOffline(
                scope = scope,
                linkChanges = MutableStateFlow(Unit),
                link = { WaStoreLink(INSTANCE) },
                open = { instance ->
                    WaSyncStore.open(instance, memory = lazy, ops = Gapped(fake, names), release = {}, lazy = lazy)
                },
                remote = remote,
                now = clock,
                wake = {},
                replayGraceMs = 100,
                downloads = downloads,
                gate = gate,
                onLock = { locks += "vault.lock" },
                memoryOnly = lazy,
                startLocked = startLocked,
            )

        init {
            // A fresh store owes the chat list (Rust: `needs_resync` of a new store).
            fake.owed = WaSyncPlan(afterSeq = null, refreshChats = true, fetch = emptyList())
            remote.inner.ownStatus = InstanceStatus(INSTANCE, InstanceState.Connected)
            remote.inner.mappings = mapOf(null to IdentityMappingsAnswer(supported = true))
            remote.inner.chatList = names.map { RichChat(it, null, null, 1, false) }
            for (chat in names) {
                remote.inner.pages[chat to null] = page(chat, NOW)
                remote.inner.pages[chat to OLDER] = page(chat, NOW - 1_000_000)
            }
        }
    }

    /** The cold start of N chats; the requests it made once the socket is up and the sync settled. */
    private fun TestScope.coldStart(lazy: Boolean): List<String> {
        val kit = Kit(backgroundScope, lazy, { testScheduler.currentTime })
        kit.offline.start()
        settle()
        kit.remote.inner.emit(WaRemoteEvent.Connected)
        settle()
        return kit.remote.requests.toList()
    }

    @Test fun aColdStartCostsAtMostFourRequestsAndLessThanTheEagerSync() =
        runTest {
            // Today's eager sync (measured): the socket after its seq, status and identity twice (start and connect),
            // the chat list, and every chat paged down to what was stored.
            val eager = coldStart(lazy = false)
            assertEquals(BASELINE_COLD_START, eager.size)
            assertEquals(2 * CHATS, eager.count { it.startsWith("page:") })

            val lazy = coldStart(lazy = true)
            assertEquals(listOf("chats", "identity", "socket", "status"), lazy.sorted())
            assertTrue(lazy.size <= 4 && lazy.size < eager.size)
        }

    @Test fun anOpenedChatCostsOnePageAndAScrollOneMoreNothingPrefetched() =
        runTest {
            val kit = Kit(backgroundScope, lazy = true, clock = { testScheduler.currentTime })
            kit.offline.start()
            settle()
            val chat = kit.names[3]
            val before = kit.remote.requests.size

            assertEquals(OlderResult.Stored, kit.offline.opened(chat))
            settle()
            assertEquals(listOf("page:$chat"), kit.remote.requests.drop(before))
            // Kept in memory for the session: opening it again asks nothing.
            kit.offline.closed()
            assertEquals(null, kit.offline.opened(chat))
            assertEquals(
                1,
                kit.remote.requests
                    .drop(before)
                    .size,
            )

            assertTrue(kit.offline.older(chat) != OlderResult.Offline)
            settle()
            assertEquals(listOf("page:$chat", "page:$chat"), kit.remote.requests.drop(before))
            // No other chat was paged, and nothing older came by itself.
            advanceTimeBy(60_000)
            settle()
            assertEquals(2, kit.remote.requests.count { it.startsWith("page:") })
        }

    @Test fun mediaIsFetchedOnATapOnlyAndLivesInRam() =
        runTest {
            val ram = HeapRamFiles(folder.newFolder("ram"))
            val kit =
                Kit(
                    backgroundScope,
                    lazy = true,
                    clock = { testScheduler.currentTime },
                    ram = ram,
                    mediaDir = folder.newFolder("wa-media"),
                )
            kit.offline.start()
            settle()
            val photo = WaTestKit.media(ADA, "P1", NOW, WaSyncKind.Image, "image/jpeg", 3)
            kit.fake.messages.getOrPut(ADA) { mutableListOf() } += photo
            kit.remote.inner.files["P1"] =
                BytesAnswer(3, dataB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            // A media message arrives and comes into view: nothing is downloaded by itself.
            kit.offline.wantMedia(listOf(WaMediaRef(ADA, "P1")))
            settle()
            val session = kit.offline.session.value
            assertNotNull(session)
            assertFalse(kit.downloads!!.auto(session!!.store, listOf(WaMediaRef(ADA, "P1"))))
            assertTrue(kit.remote.requests.none { it.startsWith("media:") })

            // A tap downloads it, once, into RAM.
            assertTrue(kit.downloads.tap(session.store, WaMediaRef(ADA, "P1")))
            kit.offline.kickMedia(session)?.join()
            settle()
            assertEquals(listOf("media:P1"), kit.remote.requests.filter { it.startsWith("media:") })
            val path =
                session.store
                    .media(ADA, "P1")
                    .getOrThrow()
                    .path
            assertNotNull(path)
            assertTrue(ram.owns(path!!))
            assertEquals(3L, ram.bytes)
        }

    /** Round 4: a process whose vault is not open yet starts locked; nothing (socket, sync, outbox) goes out. */
    @Test fun aLockedStartSendsNothingUntilTheUnlock() =
        runTest {
            val kit = Kit(backgroundScope, true, { testScheduler.currentTime }, startLocked = true)
            kit.offline.start()
            settle()
            assertTrue(kit.offline.locked.value)
            assertEquals(null, kit.offline.session.value)
            kit.remote.inner.emit(WaRemoteEvent.Connected)
            assertFalse(kit.offline.enqueue(ADA, WaSyncAction.SendText("selam")))
            // The outbox job: no retry while locked.
            kit.offline.runFromWork()
            advanceTimeBy(300_000)
            settle()
            assertEquals(emptyList<String>(), kit.remote.requests)
            kit.offline.unlock()
            settle()
            assertTrue(kit.remote.requests.isNotEmpty())
            assertTrue(kit.remote.requests.size <= 4)
        }

    @Test fun afterLockNoRequestGoesOutAndEverythingIsClosed() =
        runTest {
            val ram = HeapRamFiles(folder.newFolder("ram"))
            val kit =
                Kit(backgroundScope, true, { testScheduler.currentTime }, ram = ram, mediaDir = folder.newFolder("m"))
            kit.offline.start()
            settle()
            kit.remote.inner.emit(WaRemoteEvent.Connected)
            settle()
            ram.put("x", byteArrayOf(1))
            val before = kit.remote.requests.size

            kit.offline.lock()
            settle()
            assertEquals(null, kit.offline.session.value)
            assertTrue(kit.offline.locked.value)
            assertEquals(listOf("vault.lock"), kit.locks)
            assertEquals(0L, ram.bytes)

            // Events, an open, a scroll, a send, a wake: nothing reaches the gateway.
            kit.remote.inner.emit(WaRemoteEvent.Connected)
            kit.remote.inner.emit(1, WaTestKit.incoming(ADA, "late", NOW, "geç"))
            assertEquals(null, kit.offline.opened(ADA))
            assertEquals(OlderResult.Offline, kit.offline.older(ADA))
            assertFalse(kit.offline.enqueue(ADA, WaSyncAction.SendText("selam")))
            kit.offline.runFromWork()
            advanceTimeBy(120_000)
            settle()
            assertEquals(before, kit.remote.requests.size)

            // Unlocked again: a fresh cold start within the budget.
            kit.offline.unlock()
            settle()
            assertTrue(kit.remote.requests.size - before <= 4)
        }

    @Test fun noWhatsAppDataIsWrittenToTheAppDataDirectory() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val secret = "gizli-mesaj-1000000000"
            val ram = HeapRamFiles(folder.newFolder("ram"))
            val kit =
                Kit(
                    backgroundScope,
                    lazy = true,
                    clock = { testScheduler.currentTime },
                    ram = ram,
                    // Where the disk cache would write: the app's own directories.
                    mediaDir = File(context.filesDir, "wa-media"),
                )
            val chat = kit.names[0]
            kit.remote.inner.pages[chat to null] = listOf(WaTestKit.rich(chat, "S1", NOW, secret))
            kit.remote.inner.statusList = StatusList()
            kit.offline.start()
            settle()
            kit.remote.inner.emit(WaRemoteEvent.Connected)
            kit.remote.inner.emit(1, WaTestKit.incoming(chat, "S2", NOW + 1, secret))
            settle()
            kit.offline.opened(chat)
            settle()
            // The outbox, a draft, a live name and Status are all in memory too.
            kit.offline.enqueue(chat, WaSyncAction.SendText(secret))
            MemoryWaDrafts().put("wa:$chat", secret)
            MemoryLiveNameStore().save(mapOf("1000000000" to LiveName(secret, NOW)))
            StatusFeed(
                backgroundScope,
                kit.offline,
                kit.remote,
                File(context.cacheDir, "wa-status"),
                ram = ram,
                gate = kit.gate,
            ).reload()
            settle()
            assertTrue(
                kit.fake.messages[chat]
                    .orEmpty()
                    .any { it.text == secret },
            )

            val leaks =
                context.dataDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .filter { file -> file.readBytes().toString(Charsets.ISO_8859_1).contains(secret) }
                    .map { it.path }
                    .toList()
            assertEquals(emptyList<String>(), leaks)
        }

    private companion object {
        const val CHATS = 10
        const val PAGE = 50
        const val OLDER = 1_000L

        /**
         * The eager sync's cold start with [CHATS] chats that each missed messages (measured before the vault): the
         * socket, status and identity at the start and again at the connect, the chat list, two pages per chat.
         */
        const val BASELINE_COLD_START = 26

        fun page(
            chat: String,
            newest: Long,
        ): List<RichMessage> =
            (0 until PAGE).map { n ->
                RichMessage("$chat-${newest - n}", chat, chat, false, newest - n, MessageBody.Text("m$n"), emptyList())
            }
    }
}

/** [WaRamFiles] for the JVM: plain files under [root], which a test keeps outside the app's directories. */
class HeapRamFiles(
    private val root: File,
) : WaRamFiles {
    private val held = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var next = 0

    override fun put(
        name: String,
        bytes: ByteArray,
    ): File? {
        val file = File(root, "${next++}-$name")
        file.writeBytes(bytes)
        held[file.path] = bytes.size.toLong()
        return file
    }

    override fun owns(path: String): Boolean = held.containsKey(path)

    override fun release(path: String) {
        if (held.remove(path) != null) File(path).delete()
    }

    override fun clear() {
        held.keys.toList().forEach(::release)
    }

    override val bytes: Long get() = held.values.sum()
}
