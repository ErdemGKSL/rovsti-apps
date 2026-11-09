package dev.rovsti.contacts.card

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.rovsti.platform.contacts.ContactCallOptions

/** What the ringtone picker starts from: [current] null is the default ringtone, "" is silent. */
data class RingtoneRequest(
    val current: String?,
)

/**
 * The system ringtone picker (`RingtoneManager.ACTION_RINGTONE_PICKER`) as an activity-result contract: the answer is
 * [ContactCallOptions.Pick.Chosen] (default, silent or a tone) or [ContactCallOptions.Pick.Cancelled].
 */
class PickRingtone : ActivityResultContract<RingtoneRequest, ContactCallOptions.Pick>() {
    override fun createIntent(
        context: Context,
        input: RingtoneRequest,
    ): Intent = ContactCallOptions.pickerIntent(input.current)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): ContactCallOptions.Pick = ContactCallOptions.parsePicked(resultCode, intent)
}
