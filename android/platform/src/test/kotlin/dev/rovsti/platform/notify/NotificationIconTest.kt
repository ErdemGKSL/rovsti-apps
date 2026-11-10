package dev.rovsti.platform.notify

import android.app.Application
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationIconTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private fun build(
        kind: NotificationKind,
        photo: Icon? = null,
    ): Notification =
        NotificationIcon
            .apply(Notification.Builder(app, "test"), app, kind, photo)
            .setContentTitle("t")
            .build()

    @Test fun everyKindHasAGlyphAndAPicture() {
        for (kind in NotificationKind.entries) {
            assertEquals(kind.name, "drawable", app.resources.getResourceTypeName(kind.small))
            assertNotNull(kind.name, app.getDrawable(kind.small))
            assertEquals(kind.name, "drawable", app.resources.getResourceTypeName(kind.large))
            assertNotNull(kind.name, NotificationIcon.large(app, kind))
        }
    }

    @Test fun kindsDoNotShareAGlyphOrAPicture() {
        assertEquals(
            NotificationKind.entries.size,
            NotificationKind.entries
                .map { it.small }
                .toSet()
                .size,
        )
        assertEquals(
            NotificationKind.entries.size,
            NotificationKind.entries
                .map { it.large }
                .toSet()
                .size,
        )
    }

    @Test fun theNotificationWearsItsKind() {
        val n = build(NotificationKind.BIRTHDAY)
        assertEquals(NotificationKind.BIRTHDAY.small, n.smallIcon.resId)
        assertEquals(NotificationKind.BIRTHDAY.accent, n.color)
        assertEquals(Icon.TYPE_BITMAP, n.getLargeIcon().type)
    }

    @Test fun theLargeIconIsDecodedOnce() {
        assertSame(
            NotificationIcon.large(app, NotificationKind.TRIP),
            NotificationIcon.large(app, NotificationKind.TRIP),
        )
    }

    @Test fun aContactPhotoKeepsTheLargeIcon() {
        val photo = Icon.createWithBitmap(Bitmap.createBitmap(7, 7, Bitmap.Config.ARGB_8888))
        val n = build(NotificationKind.CALL_MISSED, photo)
        assertSame(photo, n.getLargeIcon())
        assertEquals(NotificationKind.CALL_MISSED.small, n.smallIcon.resId)
        assertEquals(NotificationKind.CALL_MISSED.accent, n.color)
    }
}
