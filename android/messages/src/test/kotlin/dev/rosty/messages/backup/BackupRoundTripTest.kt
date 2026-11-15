package dev.rosty.messages.backup

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.Telephony
import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.ops.NativeMessagesOps
import dev.rosty.platform.sms.AndroidSmsRepository
import dev.rosty.platform.sms.RestoreOutcome
import dev.rosty.platform.sms.SmsResult
import dev.rosty.platform.sms.TelephonyTestProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * AD-MSG7 end to end on the real Rust core (the host `librosty_ffi` that `:core-bridge:cargoBuildHost` builds; skipped
 * without it) and the real [AndroidSmsRepository] on a provider that stores rows ([TelephonyTestProvider]): seal what
 * the provider holds with a passphrase, open it (a wrong passphrase is refused), plan against the provider, write the
 * missing SMS back through `insertRestored`, and a second restore adds nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupRoundTripTest {
    private val context = RuntimeEnvironment.getApplication()

    init {
        Robolectric.setupContentProvider(TelephonyTestProvider::class.java, "sms")
    }

    private val mmsProvider = Robolectric.setupContentProvider(TelephonyTestProvider::class.java, "mms")
    private val resolver = context.contentResolver

    /** One provider SMS as the comparison needs it: what a restore must give back. */
    private data class Stored(
        val address: String,
        val body: String,
        val date: Long,
        val type: Int,
        val read: Int,
    )

    private fun put(row: Stored) {
        resolver.insert(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply {
                put("address", row.address)
                put("body", row.body)
                put("date", row.date)
                put("type", row.type)
                put("read", row.read)
            },
        )
    }

    private fun stored(): Set<Stored> {
        val out = HashSet<Stored>()
        resolver
            .query(Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date", "type", "read"), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    out +=
                        Stored(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3), c.getInt(4))
                }
            }
        return out
    }

    private fun smsCount(): Int = resolver.query(Telephony.Sms.CONTENT_URI, null, null, null, null)!!.use { it.count }

    private fun putMms() {
        val id =
            resolver
                .insert(
                    Telephony.Mms.CONTENT_URI,
                    ContentValues().apply {
                        put("thread_id", 4L)
                        put("date", 1_700_000_500L)
                        put("msg_box", 1)
                        put("read", 1)
                    },
                )!!
                .lastPathSegment!!
                .toLong()
        for ((address, type) in listOf(A to 137, B to 151)) {
            resolver.insert(
                Uri.parse("content://mms/$id/addr"),
                ContentValues().apply {
                    put("address", address)
                    put("type", type)
                },
            )
        }
        for ((ct, text) in listOf("image/jpeg" to null, "text/plain" to "Fotoğraf ektedir")) {
            resolver.insert(
                Uri.parse("content://mms/part"),
                ContentValues().apply {
                    put("mid", id)
                    put("ct", ct)
                    put("text", text)
                },
            )
        }
    }

    private fun realOps(): NativeMessagesOps {
        val lib = File(System.getProperty("rosty.hostLib").orEmpty())
        assumeTrue(
            "host librosty_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rosty_ffi") },
        )
        val root = Files.createTempDirectory("rosty-messages-backup").toFile().apply { deleteOnExit() }
        val started =
            runBlocking {
                Rust
                    .init(
                        File(root, "data"),
                        File(root, "cache"),
                        "dev.rosty.messages",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            }
        assertTrue("Rust.init: $started", started is Outcome.Ok)
        return NativeMessagesOps(Rust.core())
    }

    private val phone =
        listOf(
            Stored(A, "Merhaba", 1_700_000_000_000, 1, 1),
            Stored(A, "Nasılsın?", 1_700_000_100_000, 2, 1),
            // Same time and body from another sender: not a duplicate.
            Stored(B, "Nasılsın?", 1_700_000_100_000, 1, 1),
            Stored(B, "Kargo yolda", 1_700_000_200_000, 1, 1),
        )

    @Test fun sealOpenAndRestoreThroughTheProvider() =
        runBlocking {
            val ops = realOps()
            phone.forEach(::put)
            putMms()
            val sms = AndroidSmsRepository(context, Dispatchers.Unconfined)
            val random = SecureRandom()
            val engine =
                BackupEngine(
                    ops,
                    sms,
                    FakeBackupSources(),
                    { n -> ByteArray(n).also(random::nextBytes) },
                    { NOW },
                    "1.0",
                    iterations = 1_000,
                )

            val bytes = checkNotNull(engine.seal(PASS)) { "seal failed" }
            assertEquals(0, mmsProvider.filesOpened)
            assertEquals(OpenResult.WrongPassphrase, engine.open(bytes, "not the passphrase"))

            // Everything is still on the phone: nothing to write.
            val untouched = (engine.open(bytes, PASS) as OpenResult.Opened).backup
            assertEquals(RestorePreview(insert = 0, skipped = 4, scheduled = 0, spamRules = 1), untouched.preview)
            assertEquals(4, untouched.body.sms.size)
            val mms = untouched.body.mms.single()
            assertEquals(A, mms.from)
            assertEquals(listOf(B), mms.addresses)
            assertEquals(listOf("Fotoğraf ektedir"), mms.parts.map { it.text })
            assertEquals(BackupPhase.Done(BackupDone.Restored(0, 4)), engine.restore(untouched))
            assertEquals(phone.toSet(), stored())

            // The phone lost all but one: the other three come back as they were.
            resolver.delete(Telephony.Sms.CONTENT_URI, null, null)
            put(phone[1])
            val opened = (engine.open(bytes, PASS) as OpenResult.Opened).backup
            assertEquals(3, opened.preview.insert)
            assertEquals(1, opened.preview.skipped)
            assertEquals(BackupPhase.Done(BackupDone.Restored(3, 1)), engine.restore(opened))
            assertEquals(phone.toSet(), stored())
            assertEquals(4, smsCount())

            // Again: the plan finds everything, and the provider write skips it even when asked directly.
            val again = (engine.open(bytes, PASS) as OpenResult.Opened).backup
            assertEquals(0, again.preview.insert)
            assertEquals(BackupPhase.Done(BackupDone.Restored(0, 4)), engine.restore(again))
            assertEquals(SmsResult.Ok(RestoreOutcome(0, 4)), sms.insertRestored(again.body.sms))
            assertEquals(4, smsCount())
            assertEquals(phone.toSet(), stored())
        }

    private companion object {
        // The +90 555 000 00 0x test range.
        const val A = "+905550000001"
        const val B = "+905550000002"
        const val PASS = "correct horse battery staple"
        const val NOW = 1_800_000_000_000L
    }
}
