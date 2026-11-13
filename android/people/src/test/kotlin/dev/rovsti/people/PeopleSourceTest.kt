package dev.rovsti.people

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.people.PeopleContract.addPerson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeopleSourceTest {
    private val ayse =
        PersonRow("1@s.whatsapp.net", listOf("+905321234567"), name = "Ayse", onWhatsapp = true, pictureId = "5")

    class Fake : ContentProvider() {
        var rows: List<PersonRow> = emptyList()
        var denied: Boolean = false

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sort: String?,
        ): Cursor {
            if (denied) throw SecurityException("READ_PEOPLE")
            return PeopleContract.newCursor().also { c -> rows.forEach { r -> c.addPerson(r) } }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            args: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            args: Array<out String>?,
        ) = 0
    }

    private fun install(): Fake = Robolectric.setupContentProvider(Fake::class.java, PeopleContract.AUTHORITY)

    @Test fun theProviderIsChosenWhenContactsOffersIt() {
        val context = RuntimeEnvironment.getApplication()
        val packages = context.packageManager

        fun pick() =
            PeopleSources.choose(packages, {
                ProviderSource(context.contentResolver)
            }, { GatewaySource({ emptyList() }, { null }) })
        assertEquals(PeopleSource.Kind.Gateway, pick().kind)
        val info =
            ProviderInfo().apply {
                authority = PeopleContract.AUTHORITY
                name = Fake::class.java.name
                packageName = "dev.rovsti.contacts"
            }
        shadowOf(packages).addOrUpdateProvider(info)
        assertTrue(PeopleSources.providerInstalled(packages))
        assertEquals(PeopleSource.Kind.Provider, pick().kind)
    }

    @Test fun theProviderSourceReadsRowsAndTurnsRefusalIntoNothing() =
        runTest {
            val provider = install()
            provider.rows = listOf(ayse)
            val source = ProviderSource(RuntimeEnvironment.getApplication().contentResolver)
            assertEquals(listOf(ayse), source.load())
            assertEquals("content://dev.rovsti.contacts.people/picture/1%40s.whatsapp.net/5", source.pictureUri(ayse))
            assertNull(source.pictureUri(ayse.copy(pictureId = null)))
            provider.denied = true
            assertEquals(emptyList<PersonRow>(), source.load())
        }

    @Test fun theGatewaySourceLoadsInMemoryAndSurvivesAFailure() =
        runTest {
            var fail = false
            val source =
                GatewaySource(
                    fetch = { if (fail) throw IOException("offline") else listOf(ayse) },
                    pictureFile = { File("/cache/${it.pictureId}.jpg") },
                )
            assertEquals(listOf(ayse), source.load())
            assertEquals("file:///cache/5.jpg", source.pictureUri(ayse))
            fail = true
            assertEquals(emptyList<PersonRow>(), source.load())
        }
}
