package dev.rovsti.phone.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import dev.rovsti.phone.CallActionReceiver
import dev.rovsti.phone.MainActivity
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.identity.CallerIdentity
import dev.rovsti.platform.identity.CallerLookup
import dev.rovsti.platform.notify.NotifyPhotos
import dev.rovsti.platform.telephony.MissedCallNotifications
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * X.1d: the large icon of the ringing, missed-call, ongoing and reminder notifications is the caller's own contact
 * photo, else their WhatsApp picture (also for a stranger), else the kind's coloured picture. All four ask
 * [NotifyPhotos.photoOf]; the missed-call notification is posted for real here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationLargeIconTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val number = "+905551112233"

    @Before fun install() {
        PlatformComponents.install(
            PlatformComponents(mainActivity = MainActivity::class.java, callAction = CallActionReceiver::class.java),
        )
    }

    @After fun uninstall() = CallerLookup.install(null)

    private fun picture(color: Int): Uri {
        val file = File.createTempFile("wa-picture", ".png").apply { deleteOnExit() }
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun Icon.centre(): Int {
        val bitmap = Icon::class.java.getMethod("getBitmap").invoke(this) as Bitmap
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private fun largeIconOfMissedCall(): Icon? {
        MissedCallNotifications.post(context, number, 1_000L)
        return manager
            .getNotification(MissedCallNotifications.TAG, MissedCallNotifications.notificationId(number))!!
            .getLargeIcon()
    }

    @Test fun aStrangerOnWhatsAppGetsItsPictureAsTheMissedCallLargeIcon() {
        CallerLookup.install { CallerIdentity(waPushName = "Ayşe", waPhotoUri = picture(Color.RED).toString()) }
        assertEquals(Color.RED, largeIconOfMissedCall()!!.centre())
    }

    @Test fun aCallerWithNoPictureKeepsTheKindsColouredPicture() {
        CallerLookup.install { CallerIdentity(waPushName = "Ayşe") }
        val icon = largeIconOfMissedCall()
        assertNotNull("the kind's own large icon", icon)
        assert(icon!!.centre() != Color.RED)
    }

    @Test fun anUnreadablePictureFallsBackWithoutThrowing() {
        val who = CallerIdentity(waPhotoUri = Uri.fromFile(File("/no/such/picture.png")).toString())
        assertNull(NotifyPhotos.photoOf(context, who))
    }

    @Test fun theContactsOwnPhotoWinsOverTheWhatsAppPicture() {
        val own =
            Icon.createWithBitmap(
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) },
            )
        val who = CallerIdentity(contactId = 5, waPhotoUri = picture(Color.RED).toString())
        val icon = NotifyPhotos.photoOf(context, who) { id -> own.takeIf { id == 5L } }
        assertEquals(Color.BLUE, icon!!.centre())
    }

    @Test fun aContactWithoutAPhotoOfItsOwnGetsTheWhatsAppPicture() {
        val who = CallerIdentity(contactId = 5, waPhotoUri = picture(Color.GREEN).toString())
        assertEquals(Color.GREEN, NotifyPhotos.photoOf(context, who) { null }!!.centre())
    }

    @Test fun theReminderAndTheCallScreensNameTheSamePersonWithTheSamePicture() {
        CallerLookup.install { CallerIdentity(waPushName = "Ayşe", waPhotoUri = picture(Color.MAGENTA).toString()) }
        val person = NotifyPhotos.person(context, number)
        assertEquals("Ayşe", person.name)
        assertEquals(Color.MAGENTA, person.photo!!.centre())
    }
}
