package dev.rovsti.platform.contacts

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactCallOptionsTest {
    @Test
    fun theRingtonePickerAnswersDefaultSilentOrAUri() {
        val ok = Activity.RESULT_OK
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        assertEquals(
            ContactCallOptions.Pick.Cancelled,
            ContactCallOptions.parsePicked(Activity.RESULT_CANCELED, null, true),
        )
        assertEquals(ContactCallOptions.Pick.Cancelled, ContactCallOptions.parsePicked(ok, null, false))
        assertEquals(ContactCallOptions.Pick.Chosen(""), ContactCallOptions.parsePicked(ok, null, true))
        assertEquals(ContactCallOptions.Pick.Chosen(null), ContactCallOptions.parsePicked(ok, default, true))
        val custom = Uri.parse("content://media/internal/audio/media/9")
        assertEquals(
            ContactCallOptions.Pick.Chosen(custom.toString()),
            ContactCallOptions.parsePicked(ok, custom, true),
        )
    }

    @Test
    fun theAccountKeySplitsAtTheFirstBar() {
        assertEquals(null to null, ContactsEdit.splitAccount(""))
        assertEquals(null to null, ContactsEdit.splitAccount("|"))
        assertEquals("com.google" to "a@b|c", ContactsEdit.splitAccount("com.google|a@b|c"))
        assertEquals(null to "only", ContactsEdit.splitAccount("only"))
    }
}
