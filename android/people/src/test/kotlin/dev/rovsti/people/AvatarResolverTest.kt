package dev.rovsti.people

import dev.rovsti.core.model.people.PersonRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AvatarResolverTest {
    private val ayse =
        PersonRow(
            jid = "905321234567@s.whatsapp.net",
            numbers = listOf("+905321234567"),
            name = "Ayse",
            onWhatsapp = true,
            pictureId = "55",
        )
    private val noPicture =
        PersonRow(jid = "905320000000@s.whatsapp.net", numbers = listOf("+905320000000"), onWhatsapp = true)
    private val lid = PersonRow(jid = "4477@lid", lid = "4477", name = "Cem", onWhatsapp = true, pictureId = "9")

    private fun resolver(vararg rows: PersonRow): AvatarResolver {
        val index = PeopleIndex.forRegion("TR")
        index.replace(rows.toList()) { "content://p/${it.jid}/${it.pictureId}" }
        return AvatarResolver(index)
    }

    @Test fun anOwnPhotoIsKeptEvenWhenWhatsAppHasOne() {
        val avatar = resolver(ayse).resolve("content://contacts/photo/1", "+905321234567")
        assertEquals(PersonAvatar.Own("content://contacts/photo/1"), avatar)
    }

    @Test fun noPhotoAndAWhatsAppPictureShowsWhatsApp() {
        val avatar = resolver(ayse).resolve(null, listOf("0532 123 45 67"))
        assertEquals(PersonAvatar.WhatsApp("content://p/905321234567@s.whatsapp.net/55", "55"), avatar)
        assertEquals("a blank photo uri is no photo", avatar, resolver(ayse).resolve("  ", "+905321234567"))
    }

    @Test fun theFirstNumberWithAPictureWins() {
        val avatar = resolver(noPicture, ayse).resolve(null, listOf("+905320000000", "+905321234567"))
        assertEquals(PersonAvatar.WhatsApp("content://p/905321234567@s.whatsapp.net/55", "55"), avatar)
    }

    @Test fun unlinkedMeansInitials() {
        assertEquals(PersonAvatar.Initials, resolver().resolve(null, "+905321234567"))
        assertEquals(PersonAvatar.Initials, resolver(ayse).resolve(null, emptyList()))
        assertEquals(
            "on WhatsApp but no picture",
            PersonAvatar.Initials,
            resolver(noPicture).resolve(null, "+905320000000"),
        )
    }

    @Test fun anUnknownNumberThatIsOnWhatsAppGetsItsPicture() {
        // The number is in no address book (a caller in Recents), the cache knows it.
        val avatar = resolver(ayse).resolve(ownPhoto = null, number = "+90 532 123 45 67")
        assertEquals(PersonAvatar.WhatsApp("content://p/905321234567@s.whatsapp.net/55", "55"), avatar)
    }

    @Test fun aChatKnownByLidResolvesByJid() {
        assertEquals(PersonAvatar.WhatsApp("content://p/4477@lid/9", "9"), resolver(lid).resolveJid("4477@lid"))
        assertEquals(PersonAvatar.Initials, resolver(lid).resolveJid("1-2@g.us"))
        assertEquals(PersonAvatar.Own("u"), resolver(lid).resolveJid("4477@lid", ownPhoto = "u"))
    }
}
