package dev.rovsti.platform.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import dev.rovsti.core.model.contacts.PinShortcut
import dev.rovsti.core.model.stops.ShortcutAnswer
import dev.rovsti.platform.PlatformComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutsTest {
    private class Main : Activity()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val repository = AndroidShortcutsRepository(context) { PlatformComponents(mainActivity = Main::class.java) }
    private val contact = PinShortcut("contact-4", "Ada", "Ada Lovelace", 4, null)
    private val stop = ShortcutAnswer("stop-9", "Home", "Home stop", 9)

    @Test
    fun theExtrasKeepTheirGpuiNames() {
        assertEquals("open_contact_id", Shortcuts.EXTRA_CONTACT_ID)
        assertEquals("dev.rovsti.stops.STOP_ID", Shortcuts.EXTRA_STOP_ID)
        assertEquals(144, Shortcuts.safeSide(216))
    }

    @Test
    fun theTargetActivityComesFromPlatformComponents() {
        val intent = repository.launchIntent(Shortcuts.EXTRA_CONTACT_ID, 4)
        assertEquals(Main::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertEquals(4L, intent.getLongExtra(Shortcuts.EXTRA_CONTACT_ID, 0))
    }

    @Test
    fun pinningNeedsLauncherSupport() {
        val manager = context.getSystemService(ShortcutManager::class.java)
        shadowOf(manager).setIsRequestPinShortcutSupported(false)
        assertFalse(repository.canPin())
        assertFalse(repository.pinContact(contact))
        assertFalse(repository.pinStop(stop))
        shadowOf(manager).setIsRequestPinShortcutSupported(true)
        assertTrue(repository.canPin())
        assertTrue(repository.pinContact(contact))
        assertTrue(repository.pinStop(stop))
    }
}
