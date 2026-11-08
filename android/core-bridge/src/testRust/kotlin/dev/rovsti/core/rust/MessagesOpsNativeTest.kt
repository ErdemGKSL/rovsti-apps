package dev.rovsti.core.rust

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.messages.AccessRequest
import dev.rovsti.core.model.messages.AddRuleRequest
import dev.rovsti.core.model.messages.Ask
import dev.rovsti.core.model.messages.BackupBody
import dev.rovsti.core.model.messages.BackupExportRequest
import dev.rovsti.core.model.messages.BackupParseRequest
import dev.rovsti.core.model.messages.Channel
import dev.rovsti.core.model.messages.ClassifyRequest
import dev.rovsti.core.model.messages.DeliveryState
import dev.rovsti.core.model.messages.DirRequest
import dev.rovsti.core.model.messages.DraftsPutRequest
import dev.rovsti.core.model.messages.DraftsSaveRequest
import dev.rovsti.core.model.messages.FiredSms
import dev.rovsti.core.model.messages.InboxThread
import dev.rovsti.core.model.messages.MergeRequest
import dev.rovsti.core.model.messages.Message
import dev.rovsti.core.model.messages.MessageDirection
import dev.rovsti.core.model.messages.OtpRequest
import dev.rovsti.core.model.messages.PermissionState
import dev.rovsti.core.model.messages.PersonKey
import dev.rovsti.core.model.messages.PhoneNumber
import dev.rovsti.core.model.messages.PrefsEditRequest
import dev.rovsti.core.model.messages.PrefsSaveRequest
import dev.rovsti.core.model.messages.ScheduleAddRequest
import dev.rovsti.core.model.messages.ScheduleCancelRequest
import dev.rovsti.core.model.messages.ScheduleReconcileRequest
import dev.rovsti.core.model.messages.ScheduleTarget
import dev.rovsti.core.model.messages.ScheduledSms
import dev.rovsti.core.model.messages.SmsKey
import dev.rovsti.core.model.messages.SmsMessagesPayload
import dev.rovsti.core.model.messages.SmsRow
import dev.rovsti.core.model.messages.SmsStatus
import dev.rovsti.core.model.messages.SmsThreadRow
import dev.rovsti.core.model.messages.SpamRule
import dev.rovsti.core.model.messages.SpamRules
import dev.rovsti.core.model.messages.TagRequest
import dev.rovsti.core.model.messages.ThreadId
import dev.rovsti.core.model.messages.TimelineEvent
import dev.rovsti.core.model.messages.TimelineRequest
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.search.WireNumber
import dev.rovsti.core.ops.ContactIndexHandle
import dev.rovsti.core.ops.NativeMessagesOps
import dev.rovsti.core.ops.NativeSearchOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/** The core Messages facade against the real host library: what the apps do, end to end, with Kotlin-built values. */
class MessagesOpsNativeTest {
    private val ops = NativeMessagesOps(TestRust.core)
    private val now = 1_790_000_000_000L

    @Before
    fun load() = TestRust.ensure()

    private fun message(
        thread: ThreadId,
        sender: String,
        body: String,
    ) = Message(thread, MessageDirection.Incoming, PhoneNumber(sender), body, now, false)

    @Test
    fun theInboxFoldsSmsAndWhatsAppIntoOnePersonWhoseContactTheIndexKnows() =
        runBlocking {
            val index =
                ContactIndexHandle
                    .open(
                        listOf(WireContact(1, "Ayşe Yılmaz", listOf(WireNumber("0532 123 45 67")))),
                        ops = NativeSearchOps(TestRust.core),
                    ).getOrOk()
            index.use {
                val jid = "905321234567@s.whatsapp.net"
                val rows =
                    ops
                        .merge(
                            MergeRequest(
                                contacts = it.id,
                                sms = listOf(SmsThreadRow(5, listOf("05321234567"), "See you", now - 1000, 2)),
                                wa =
                                    listOf(
                                        InboxThread(
                                            ThreadId.WhatsApp(jid),
                                            message(ThreadId.WhatsApp(jid), "+905321234567", "Ok"),
                                            1,
                                            null,
                                            false,
                                        ),
                                    ),
                            ),
                        ).getOrOk()
                        .rows
                val row = rows.single()
                assertEquals(PersonKey.Contact(1), row.key)
                assertEquals(3, row.unread)
                assertEquals(setOf(Channel.Sms, Channel.WhatsApp), row.channels.toSet())
                assertEquals(
                    Channel.WhatsApp,
                    ops
                        .preferredChannel(
                            dev.rovsti.core.model.messages
                                .PreferredChannelRequest(row),
                        ).getOrOk()
                        .channel,
                )
            }
            val gone = ops.merge(MergeRequest(contacts = index.id)) as Outcome.Err
            assertTrue("a closed index is not found", gone.error is RustError.NotFound)
        }

    @Test
    fun aFailedSendIsRetriedThenDeliveredAndTheTimelineStaysOrdered() =
        runBlocking {
            val events =
                listOf(
                    TimelineEvent.pending(4, "Hello?", now, subId = 1),
                    TimelineEvent.status(SmsStatus(4, state = DeliveryState.Failed, errorCode = 3)),
                    TimelineEvent.retry(4),
                    TimelineEvent.status(SmsStatus(4, uri = "content://sms/10", state = DeliveryState.Delivered)),
                )
            val answer = ops.timeline(TimelineRequest(5, listOf("05321234567"), events)).getOrOk()
            assertEquals(
                "05321234567",
                answer.results[2]
                    .send
                    ?.to
                    ?.single(),
            )
            val item = answer.items.single()
            assertEquals(DeliveryState.Delivered, item.state)
            assertEquals(SmsKey.Local(4), item.key)
            assertEquals("sms:10", item.providerId)
            val page =
                SmsMessagesPayload(
                    sms = listOf(SmsRow(10, 5, "05321234567", "Hello?", now, 2, true, 1, DeliveryState.None)),
                    mms = emptyList(),
                    hasOlder = false,
                )
            val again = ops.timeline(TimelineRequest(5, events = events + TimelineEvent.page(page))).getOrOk()
            assertEquals("the page row folds into the local bubble", 1, again.items.size)
        }

    @Test
    fun theScheduleQueueTravelsAsAStringThroughRustAndBack() =
        runBlocking {
            fun item(
                id: Long,
                at: Long,
            ) = ScheduledSms(id, at, ScheduleTarget.Sms("+905321234567", 1), "Happy birthday", now)
            var queue = ""
            for ((id, at) in listOf(2L to now + 120_000, 1L to now + 90_000)) {
                queue = ops.scheduleAdd(ScheduleAddRequest(queue, item(id, at), now)).getOrOk().queueJson
            }
            val due =
                ops
                    .scheduleDueSoon(
                        dev.rovsti.core.model.messages
                            .DueSoonRequest(queue, now, 100_000),
                    ).getOrOk()
            assertEquals(listOf(1L), due.items.map { it.id })
            val tooSoon = ops.scheduleAdd(ScheduleAddRequest(queue, item(3, now + 1_000), now)) as Outcome.Err
            assertTrue(tooSoon.error is RustError.Invalid)
            assertEquals("pick a time at least a minute from now", tooSoon.error.message)
            val fired =
                ops
                    .scheduleReconcile(
                        ScheduleReconcileRequest(queue, listOf(FiredSms(1, false, now))),
                    ).getOrOk()
            assertEquals(listOf(1L), fired.failed.map { it.id })
            assertEquals(listOf(2L), fired.items.map { it.id })
            val cancelled = ops.scheduleCancel(ScheduleCancelRequest(fired.queueJson, 2)).getOrOk()
            assertTrue(cancelled.removed && cancelled.items.isEmpty())
        }

    @Test
    fun aBackupOpensOnlyWithItsPassphraseAndKotlinSuppliesTheRandomness() =
        runBlocking {
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val body =
                BackupBody(
                    now,
                    "0.1.0",
                    emptyList(),
                    emptyList(),
                    SpamRules(rules = listOf(SpamRule.Keyword("win"))),
                    emptyList(),
                )
            val sealed =
                ops
                    .backupExport(
                        BackupExportRequest(
                            body,
                            "correct horse battery",
                            Base64.encode(salt),
                            Base64.encode(nonce),
                            iterations = 1_000,
                        ),
                    ).getOrOk()
            val opened = ops.backupParse(BackupParseRequest(sealed.bytes, "correct horse battery")).getOrOk()
            assertEquals(body, opened.body)
            val wrong = ops.backupParse(BackupParseRequest(sealed.bytes, "nope")) as Outcome.Err
            assertTrue(wrong.error is RustError.Auth)
            val shortSalt =
                ops.backupExport(
                    BackupExportRequest(body, "pw", Base64.encode(ByteArray(3)), Base64.encode(nonce)),
                ) as Outcome.Err
            assertTrue(shortSalt.error is RustError.Invalid)
        }

    @Test
    fun spamRulesAreEditedClassifiedAndTheErrorsCarryTheSentence() =
        runBlocking {
            var rules = SpamRules()
            rules = ops.addRule(AddRuleRequest(rules, SpamRule.Sender("PROMO*"))).getOrOk().rules
            val dup = ops.addRule(AddRuleRequest(rules, SpamRule.Sender("promo*"))) as Outcome.Err
            assertEquals("the rule already exists", dup.error.message)
            assertTrue(ops.classify(ClassifyRequest(rules, "PROMO-SHOP", "Sale")).getOrOk().spam)
            val code = ops.classify(ClassifyRequest(rules, "PROMO-SHOP", "Your code is 482913")).getOrOk()
            assertFalse("a passcode is never spam", code.spam)
            assertEquals(
                "482913",
                ops
                    .otp(OtpRequest("Your code is 482913"))
                    .getOrOk()
                    .otp
                    ?.code,
            )
        }

    @Test
    fun preferencesAndDraftsLiveInTheDataDirectoryAndEditsKeepTheRulesInRust() =
        runBlocking {
            val dir =
                Files
                    .createTempDirectory("rovsti-messages")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val fresh = ops.prefsLoad(DirRequest(dir)).getOrOk()
            assertTrue(fresh.isReset)
            val muted =
                ops
                    .prefsEdit(
                        PrefsEditRequest(fresh.prefs, "mute", tag = "sms:7", untilMs = now + 3_600_000),
                    ).getOrOk()
            assertEquals(listOf("sms:7"), muted.prefs.muted)
            val spam = ops.prefsEdit(PrefsEditRequest(muted.prefs, "mark_spam", sender = " PROMO-SHOP ")).getOrOk()
            assertTrue(spam.mark?.ruleAdded == true)
            ops.prefsSave(PrefsSaveRequest(dir, spam.prefs)).getOrOk()
            assertEquals(spam.prefs, ops.prefsLoad(DirRequest(dir)).getOrOk().prefs)
            val undone =
                ops
                    .prefsEdit(
                        PrefsEditRequest(spam.prefs, "unmark_spam", sender = "PROMO-SHOP", mark = spam.mark),
                    ).getOrOk()
            assertEquals(muted.prefs.spam, undone.prefs.spam)

            val drafts = ops.draftsPut(DraftsPutRequest(thread = "sms:7", text = "On my way")).getOrOk().drafts
            ops.draftsSave(DraftsSaveRequest(dir, drafts)).getOrOk()
            assertEquals(drafts, ops.draftsLoad(DirRequest(dir)).getOrOk().drafts)
            assertTrue(
                ops
                    .draftsPut(DraftsPutRequest(drafts, "sms:7", "  "))
                    .getOrOk()
                    .drafts
                    .isEmpty(),
            )
        }

    @Test
    fun permissionsAndTagsAnswerWhatToAskAndWhatToCallIt() =
        runBlocking {
            val access = ops.access(AccessRequest(PermissionState.PermanentlyDenied)).getOrOk()
            assertEquals(Ask.OpenAppInfo, access.ask)
            assertNull(ops.access(AccessRequest(PermissionState.Granted)).getOrOk().ask)
            val tag = ops.tag(TagRequest(thread = ThreadId.WhatsApp("1@g.us"))).getOrOk()
            assertEquals("wa:1@g.us", tag.tag)
            assertEquals(ThreadId.Sms(7), ops.tag(TagRequest(tag = "sms:7")).getOrOk().thread)
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
