package dev.rosty.messages.backup

import android.net.Uri
import dev.rosty.core.Envelope
import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.messages.BackupBody
import dev.rosty.core.model.messages.BackupExportAnswer
import dev.rosty.core.model.messages.BackupExportRequest
import dev.rosty.core.model.messages.BackupParseAnswer
import dev.rosty.core.model.messages.BackupParseRequest
import dev.rosty.core.model.messages.DeliveryState
import dev.rosty.core.model.messages.RestorePlan
import dev.rosty.core.model.messages.RestorePlanRequest
import dev.rosty.core.model.messages.ScheduleValidateAnswer
import dev.rosty.core.model.messages.ScheduleValidateRequest
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.messages.SmsRow
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.model.messages.Strength
import dev.rosty.core.model.messages.StrengthAnswer
import dev.rosty.core.model.messages.StrengthRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Base64

/** One SMS row of the fixtures (numbers from the +90 555 000 00 0x test range). */
fun smsRow(
    id: Long,
    thread: Long,
    body: String,
    at: Long,
    address: String = "+905550000001",
) = SmsRow(id, thread, address, body, at, 1, true, null, DeliveryState.None)

/**
 * A small Kotlin model of `msg_backup.*` and `schedule.validate`: the "file" is `FAKE1:<passphrase>:<json body>`, a
 * passphrase under 8 characters is weak, under 14 fair; a restore plan keeps the rows whose address, time and body are
 * not on the phone; an item less than a minute ahead cannot be scheduled. The real crypto is tested in Rust.
 */
fun FakeRustCore.fakeBackup() {
    val json = Envelope.json
    onTyped("msg_backup.strength", StrengthRequest.serializer(), StrengthAnswer.serializer()) { req ->
        val n = req.passphrase.length
        Outcome.Ok(
            StrengthAnswer(
                if (n < 8) {
                    Strength.Weak
                } else if (n < 14) {
                    Strength.Fair
                } else {
                    Strength.Strong
                },
            ),
        )
    }
    onTyped("msg_backup.export", BackupExportRequest.serializer(), BackupExportAnswer.serializer()) { req ->
        val text = "FAKE1:${req.passphrase}:" + json.encodeToString(BackupBody.serializer(), req.body)
        val bytes = Base64.getEncoder().encodeToString(text.toByteArray())
        Outcome.Ok(BackupExportAnswer(bytes, text.length, 1000))
    }
    onTyped("msg_backup.parse", BackupParseRequest.serializer(), BackupParseAnswer.serializer()) { req ->
        val text = String(Base64.getDecoder().decode(req.bytes))
        if (!text.startsWith("FAKE1:")) {
            return@onTyped Outcome.Err(
                RustError.Parse("not a backup", buildJsonObject { put("reason", JsonPrimitive("not_a_backup")) }),
            )
        }
        val rest = text.removePrefix("FAKE1:")
        val pass = rest.substringBefore(':')
        if (pass != req.passphrase) {
            return@onTyped Outcome.Err(
                RustError.Auth("wrong", buildJsonObject { put("reason", JsonPrimitive("wrong_passphrase")) }),
            )
        }
        Outcome.Ok(BackupParseAnswer(json.decodeFromString(BackupBody.serializer(), rest.substringAfter(':'))))
    }
    onTyped("msg_backup.plan", RestorePlanRequest.serializer(), RestorePlan.serializer()) { req ->
        val missing =
            req.backup.sms.filter { b ->
                req.existing.none { it.address == b.address && it.dateMs == b.dateMs && it.body == b.body }
            }
        Outcome.Ok(RestorePlan(missing, req.backup.sms.size - missing.size))
    }
    onTyped("schedule.validate", ScheduleValidateRequest.serializer(), ScheduleValidateAnswer.serializer()) { req ->
        Outcome.Ok(ScheduleValidateAnswer(if (req.atMs < req.nowMs + MIN_LEAD) "too soon" else null, MIN_LEAD))
    }
}

private const val MIN_LEAD = 60_000L

/** [BackupSources] in memory. */
class FakeBackupSources(
    var rules: SpamRules = SpamRules(rules = listOf(SpamRule.UnknownWithLink)),
    val waiting: MutableList<ScheduledSms> = mutableListOf(),
    var role: Boolean = true,
) : BackupSources {
    override suspend fun spamRules() = rules

    override suspend fun addRule(rule: SpamRule): Boolean {
        if (rule in rules.rules) return false
        rules = rules.copy(rules = rules.rules + rule)
        return true
    }

    override suspend fun allow(sender: String): Boolean {
        if (rules.allow.any { it.equals(sender, true) }) return false
        rules = rules.copy(allow = rules.allow + sender)
        return true
    }

    override suspend fun scheduled() = waiting.toList()

    override suspend fun reschedule(item: ScheduledSms): Boolean = waiting.add(item)

    override fun roleHeld() = role
}

/** [BackupFiles] in memory: what was written per target, and what a read answers. */
class FakeBackupFiles(
    var stored: ByteArray? = null,
    var writeOk: Boolean = true,
) : BackupFiles {
    val written = mutableMapOf<Uri, ByteArray>()

    override suspend fun write(
        target: Uri,
        bytes: ByteArray,
    ): Boolean {
        if (writeOk) written[target] = bytes
        return writeOk
    }

    override suspend fun read(source: Uri): PickedFile? = stored?.let { PickedFile(it, "rosty-messages-20261115.rmb") }
}
