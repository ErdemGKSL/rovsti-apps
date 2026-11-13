package dev.rovsti.phone

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** F34: Message, Add contact and Share never fail silently; with no app to take them, the user reads why. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemStarterTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val said = mutableListOf<String>()
    private var opened = 0

    private fun starter(): SystemStarter {
        // Robolectric resolves nothing by default; with this an intent nobody handles throws as on a phone.
        shadowOf(app).checkActivities(true)
        return SystemStarter(app) { said += it }
    }

    private val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+905551112233"))

    @Test fun noAppForTheIntentIsAToastNotSilence() {
        assertFalse(starter().start(sms) { opened++ })
        assertEquals(listOf("No app can do this."), said)
        assertEquals("nothing opened, so the screen stays", 0, opened)
    }

    @Test
    @Config(qualifiers = "tr")
    fun theToastIsTurkishOnATurkishPhone() {
        starter().start(sms)
        assertEquals(listOf("Bunu yapabilecek bir uygulama yok."), said)
    }

    @Test fun anAppThatTakesItRunsOpenedAndSaysNothing() {
        val starter = starter()
        shadowOf(app.packageManager).addResolveInfoForIntent(
            sms,
            android.content.pm.ResolveInfo().apply {
                activityInfo =
                    android.content.pm.ActivityInfo().apply {
                        packageName = "com.example.sms"
                        name = "com.example.sms.Compose"
                    }
            },
        )
        assertTrue(starter.start(sms) { opened++ })
        assertEquals(1, opened)
        assertTrue(said.isEmpty())
        assertEquals(sms.action, shadowOf(app).nextStartedActivity.action)
    }
}
