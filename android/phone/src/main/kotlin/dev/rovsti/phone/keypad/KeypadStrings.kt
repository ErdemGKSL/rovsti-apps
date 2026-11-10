package dev.rovsti.phone.keypad

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.phone.R

/** Every word of the Keypad, read once per configuration (the screens and the tests share it). */
@Immutable
class KeypadStrings(
    private val res: Resources,
) {
    val fieldDescription: String = res.getString(R.string.keypad_field_description)
    val call: String = res.getString(R.string.keypad_call)
    val delete: String = res.getString(R.string.keypad_delete)
    val addContact: String = res.getString(R.string.keypad_add_contact)
    val createContact: String = res.getString(R.string.keypad_create_contact)
    val addExisting: String = res.getString(R.string.keypad_add_existing)
    val matchesTitle: String = res.getString(R.string.keypad_matches_title)
    val share: String = res.getString(R.string.keypad_share)
    val copyE164: String = res.getString(R.string.keypad_copy_e164)
    val undo: String = res.getString(R.string.keypad_undo)
    val cancel: String = res.getString(R.string.keypad_cancel)
    val pasteTitle: String = res.getString(R.string.keypad_paste_title)
    val pasteMessage: String = res.getString(R.string.keypad_paste_message)
    val simTitle: String = res.getString(R.string.keypad_sim_title)
    val whatsApp: String = res.getString(R.string.keypad_menu_whatsapp)
    val comingSoon: String = res.getString(R.string.keypad_menu_soon)
    val message: String = res.getString(R.string.keypad_menu_message)
    val speedTitle: String = res.getString(R.string.keypad_speed_title)
    val speedChoose: String = res.getString(R.string.keypad_speed_choose)
    val settings: String = res.getString(R.string.settings_open)

    fun more(count: Int): String = res.getString(R.string.keypad_more, count)

    fun matches(count: Int): String = res.getString(R.string.keypad_matches, count)

    fun toneHint(preview: String): String = res.getString(R.string.keypad_tone_hint, preview)

    fun callWith(sim: String): String = res.getString(R.string.keypad_call_with, sim)

    fun speedMessage(digit: Int): String = res.getString(R.string.keypad_speed_message, digit)

    fun say(notice: Notice): String =
        res.getString(
            when (notice) {
                Notice.NoVoicemail -> R.string.keypad_notice_no_voicemail
                Notice.DialerOpened -> R.string.keypad_notice_dialer_opened
                Notice.CallFailed -> R.string.keypad_notice_call_failed
                Notice.NothingToPaste -> R.string.keypad_notice_nothing_to_paste
                Notice.Cleared -> R.string.keypad_notice_cleared
                Notice.Copied -> R.string.keypad_notice_copied
                Notice.CopiedE164 -> R.string.keypad_notice_copied_e164
            },
        )

    companion object {
        @Composable
        fun current(): KeypadStrings {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            return remember(context, configuration) { KeypadStrings(context.resources) }
        }
    }
}
