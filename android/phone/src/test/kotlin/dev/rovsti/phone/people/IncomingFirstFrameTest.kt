package dev.rovsti.phone.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.rovsti.people.ui.LocalAvatarResolver
import dev.rovsti.phone.incoming.CallPhotos
import dev.rovsti.phone.incoming.knownCallPhoto
import dev.rovsti.phone.incoming.rememberCallPhoto
import dev.rovsti.testing.ShotTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * X.1d: the incoming screen reads only what is in memory. A caller whose WhatsApp picture the people cache already
 * holds has it as the backdrop on the very first composition, even while the photo flows (which would need the
 * provider or the disk) never answer; nothing is asked for before it.
 */
@Config(sdk = [35])
class IncomingFirstFrameTest : ShotTest() {
    private val number = "+905321112233"

    /** Flows that never emit: anything that waited for them would leave the screen without a picture. */
    private val hanging =
        object : CallPhotos {
            override fun photoOf(contactId: Long): Flow<String?> = flow { awaitCancellation() }

            override fun photoOfJid(jid: String): Flow<Any?> = flow { awaitCancellation() }
        }

    @Composable private fun Probe(
        jid: String? = null,
        contactId: Long? = null,
        sink: (Any?) -> Unit,
    ) {
        val photo = rememberCallPhoto(hanging, contactId, jid, number)
        sink(photo)
    }

    @Test fun theCachedPictureIsThereOnTheFirstComposition() {
        val seen = mutableListOf<Any?>()
        composeFrame {
            CompositionLocalProvider(LocalAvatarResolver provides waResolver(listOf(number))) {
                Probe { seen += it }
            }
        }
        assertEquals("file:///people/905321112233@s.whatsapp.net.jpg", seen.first())
    }

    @Test fun withoutACacheThereIsNoPictureAndNothingBlocks() {
        val seen = mutableListOf<Any?>()
        composeFrame { Probe { seen += it } }
        assertNull(seen.first())
    }

    @Test fun aWhatsAppCallFindsItsPictureByJidToo() {
        val resolver = waResolver(listOf(number))
        assertEquals(
            "file:///people/905321112233@s.whatsapp.net.jpg",
            knownCallPhoto(resolver, ContactPhotoBook.Empty, null, null, "905321112233@s.whatsapp.net"),
        )
    }

    @Test fun theContactsOwnPhotoBeatsTheWhatsAppPicture() {
        val book = ContactPhotoBook(byId = mapOf(7L to "content://contacts/photo/7"))
        assertEquals(
            "content://contacts/photo/7",
            knownCallPhoto(waResolver(listOf(number)), book, 7L, number, null),
        )
    }

    @Test fun aNumberTheCacheDoesNotKnowHasNoPicture() {
        assertNull(knownCallPhoto(waResolver(listOf(number)), ContactPhotoBook.Empty, null, "+4930123456", null))
    }
}
