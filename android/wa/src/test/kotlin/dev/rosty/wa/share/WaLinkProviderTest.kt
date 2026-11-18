package dev.rosty.wa.share

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.rosty.core.model.wa.StoredLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/**
 * The application of the provider tests: hands out [link] like `:shell`'s `HasContainer` does with an unlocked vault
 * (null stands for a locked vault, a cold process or no link).
 */
class LinkTestApp :
    Application(),
    WaLinkShareHost {
    @Volatile var link: StoredLink? = null

    /** How long the read takes. */
    @Volatile var readMs: Long = 0

    /** The links read for a sibling. */
    @Volatile var reads = 0

    override suspend fun waLinkToShare(): StoredLink? {
        if (readMs > 0) delay(readMs)
        reads += 1
        return link
    }

    override suspend fun waLinkOffered(): Boolean = link != null
}

/** Stands in for the provider of a sibling app: answers whatever cursor the test scripted. */
class SiblingStubProvider : ContentProvider() {
    @Volatile var answer: () -> Cursor? = { null }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = answer()

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/**
 * WA-R2: what `content://<package>.walink/link` answers and to whom, and what the asking side makes of a sibling's
 * answer. The manifest side (exported only behind the signature permission) is each app's merged-manifest test.
 * Robolectric does not enforce manifest permissions, so the caller checks here are the provider's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LinkTestApp::class)
class WaLinkProviderTest {
    private val app get() = ApplicationProvider.getApplicationContext<LinkTestApp>()
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_" + "A".repeat(43))
    private val messages = "dev.rosty.messages"

    private fun install(
        packageName: String,
        signature: String,
        provider: Boolean = true,
    ) {
        val application =
            ApplicationInfo().apply {
                this.packageName = packageName
                name = packageName
            }
        val info =
            PackageInfo().apply {
                this.packageName = packageName
                signatures = arrayOf(Signature(signature))
                applicationInfo = application
                if (provider) {
                    providers =
                        arrayOf(
                            ProviderInfo().apply {
                                authority = WaLinkShare.authority(packageName)
                                this.packageName = packageName
                                name = WaLinkProvider::class.java.name
                                exported = true
                                applicationInfo = application
                            },
                        )
                }
            }
        shadowOf(app.packageManager).installPackage(info)
    }

    private fun stub(packageName: String): SiblingStubProvider =
        Robolectric
            .buildContentProvider(
                SiblingStubProvider::class.java,
            ).create(WaLinkShare.authority(packageName))
            .get()

    @Before
    fun setUp() {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).signatures =
            arrayOf(Signature(OURS))
        Robolectric.buildContentProvider(WaLinkProvider::class.java).create(WaLinkShare.authority(app.packageName))
    }

    @After
    fun tearDown() = ShadowBinder.reset()

    private fun query(uri: Uri = WaLinkShare.linkUri(app.packageName)): Cursor =
        app.contentResolver.query(uri, null, null, null, null)!!

    // ---- the provider

    @Test fun anAppWithoutALinkAnswersNoRow() {
        query().use { cursor ->
            assertEquals(WaLinkShare.Columns.all.toList(), cursor.columnNames.toList())
            assertEquals(0, cursor.count)
        }
    }

    @Test fun anAppWithALinkAnswersItsOneRow() {
        app.link = link
        query().use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(link, WaLinkShare.linkOf(cursor))
        }
        assertEquals("content://${app.packageName}.walink/link", WaLinkShare.linkUri(app.packageName).toString())
    }

    @Test fun theOfferSaysWhetherALinkWouldBeHandedOverWithoutTheKey() {
        val offer = WaLinkShare.offerUri(app.packageName)
        query(offer).use { cursor ->
            assertEquals(listOf(WaLinkShare.Columns.AVAILABLE), cursor.columnNames.toList())
            assertEquals("locked: nothing to offer", 0, cursor.count)
        }
        app.link = link
        query(offer).use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.columnNames.none { it == WaLinkShare.Columns.KEY })
        }
        assertEquals("the offer reads no link", 0, app.reads)
    }

    @Test fun aStoredLinkThatIsNotUsableIsNotHandedOut() {
        app.link = StoredLink("", "inst1", "key")
        query().use { assertEquals(0, it.count) }
    }

    @Test fun anotherPathOrAuthorityIsRefused() {
        app.link = link
        val provider = WaLinkProvider().also { it.attachInfo(app, ProviderInfo().apply { authority = "x.walink" }) }
        for (uri in listOf(
            "content://${app.packageName}.walink/other",
            "content://x.walink/link",
            "content://x.walink/",
        )) {
            assertThrows(uri, IllegalArgumentException::class.java) {
                provider.query(Uri.parse(uri), null, null, null, null)
            }
        }
    }

    @Test fun aCallerSignedWithAnotherKeyIsRefused() {
        app.link = link
        install("evil.app", THEIRS, provider = false)
        shadowOf(app.packageManager).setPackagesForUid(STRANGER_UID, "evil.app")
        ShadowBinder.setCallingUid(STRANGER_UID)
        assertThrows(SecurityException::class.java) { query() }
    }

    @Test fun aCallerNobodyKnowsIsRefused() {
        app.link = link
        ShadowBinder.setCallingUid(STRANGER_UID)
        assertThrows(SecurityException::class.java) { query() }
    }

    @Test fun aSiblingSignedWithTheSameKeyIsAnswered() {
        app.link = link
        install(messages, OURS)
        shadowOf(app.packageManager).setPackagesForUid(SIBLING_UID, messages)
        ShadowBinder.setCallingUid(SIBLING_UID)
        query().use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(link, WaLinkShare.linkOf(cursor))
        }
    }

    @Test fun aUidSharedWithAStrangerIsRefused() {
        app.link = link
        install(messages, OURS)
        install("evil.app", THEIRS, provider = false)
        shadowOf(app.packageManager).setPackagesForUid(SIBLING_UID, messages, "evil.app")
        ShadowBinder.setCallingUid(SIBLING_UID)
        assertThrows(SecurityException::class.java) { query() }
    }

    @Test fun nothingCanBeWritten() {
        val uri = WaLinkShare.linkUri(app.packageName)
        assertThrows(UnsupportedOperationException::class.java) { app.contentResolver.insert(uri, ContentValues()) }
        assertThrows(UnsupportedOperationException::class.java) {
            app.contentResolver.update(uri, ContentValues(), null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) { app.contentResolver.delete(uri, null, null) }
    }

    // ---- the asking side

    private val siblings get() = ResolverSiblingLinks(app)

    @Test fun anAppThatIsNotInstalledIsNotInstalled() =
        runTest {
            assertEquals(SiblingAnswer.NotInstalled, siblings.ask(messages))
        }

    @Test fun aSiblingWithALinkAnswersIt() =
        runTest {
            install(messages, OURS)
            stub(messages).answer = { WaLinkShare.cursorOf(link) }
            assertEquals(SiblingAnswer.Link(link), siblings.ask(messages))
        }

    @Test fun aSiblingOffersOnlyWhatItsOfferRowSays() =
        runTest {
            assertEquals(false, siblings.offers(messages))
            install(messages, OURS)
            val provider = stub(messages)
            provider.answer = { WaLinkShare.offerCursorOf(false) }
            assertEquals(false, siblings.offers(messages))
            provider.answer = { WaLinkShare.offerCursorOf(true) }
            assertEquals(true, siblings.offers(messages))
            // An older sibling without the offer row, or one that throws.
            provider.answer = { error("boom") }
            assertEquals(false, siblings.offers(messages))
        }

    @Test fun aSiblingWithoutALinkAnswersNoLink() =
        runTest {
            install(messages, OURS)
            stub(messages).answer = { WaLinkShare.cursorOf(null) }
            assertEquals(SiblingAnswer.NoLink, siblings.ask(messages))
        }

    @Test fun aLookAlikeSignedWithAnotherKeyIsNeverAsked() =
        runTest {
            install(messages, THEIRS)
            var asked = false
            stub(messages).answer = {
                asked = true
                WaLinkShare.cursorOf(link.copy(gatewayUrl = "https://evil.example.com"))
            }
            assertEquals(SiblingAnswer.Untrusted, siblings.ask(messages))
            assertTrue("its provider was not even queried", !asked)
        }

    @Test fun anAuthorityOwnedByAnotherPackageIsNotTrusted() =
        runTest {
            // Another app registered the sibling's authority (the sibling itself is not installed).
            val application = ApplicationInfo().apply { packageName = "evil.app" }
            val info =
                PackageInfo().apply {
                    packageName = "evil.app"
                    signatures = arrayOf(Signature(OURS))
                    applicationInfo = application
                    providers =
                        arrayOf(
                            ProviderInfo().apply {
                                authority = WaLinkShare.authority(messages)
                                packageName = "evil.app"
                                name = "evil.Provider"
                                applicationInfo = application
                            },
                        )
                }
            shadowOf(app.packageManager).installPackage(info)
            assertEquals(SiblingAnswer.Untrusted, siblings.ask(messages))
        }

    @Test fun wrongDataIsInvalid() =
        runTest {
            install(messages, OURS)
            val provider = stub(messages)
            // Other columns.
            provider.answer = { MatrixCursor(arrayOf("a", "b")).apply { addRow(arrayOf("1", "2")) } }
            assertEquals(SiblingAnswer.Invalid, siblings.ask(messages))
            // The right columns, a null key.
            provider.answer = {
                MatrixCursor(
                    WaLinkShare.Columns.all,
                ).apply { addRow(arrayOf<Any?>("https://wa.example.com", "inst1", null)) }
            }
            assertEquals(SiblingAnswer.Invalid, siblings.ask(messages))
            // A value that is no link.
            provider.answer = { WaLinkShare.cursorOf(StoredLink("javascript:alert(1)", "inst1", "key")) }
            assertEquals(SiblingAnswer.Invalid, siblings.ask(messages))
            // The provider throws.
            provider.answer = { error("boom") }
            assertEquals(SiblingAnswer.Invalid, siblings.ask(messages))
            provider.answer = { throw SecurityException("no") }
            assertEquals(SiblingAnswer.Untrusted, siblings.ask(messages))
            // No cursor at all.
            provider.answer = { null }
            assertEquals(SiblingAnswer.NotInstalled, siblings.ask(messages))
        }

    @Test fun theMarksSurviveANewInstanceAndHoldNoKey() {
        // A one-way mark of a removed link of an earlier build goes with the next write.
        app
            .getSharedPreferences("wa-link-share", 0)
            .edit()
            .putString("declined", "f00")
            .commit()
        val marks = PrefsWaLinkMarks(app)
        assertNull(marks.adopted)
        marks.adopted = AdoptedLink("inst1", messages)
        val again = PrefsWaLinkMarks(app)
        assertEquals(AdoptedLink("inst1", messages), again.adopted)
        val stored = app.getSharedPreferences("wa-link-share", 0).all
        assertTrue("the key is not in the preferences", link.key !in stored.values.joinToString())
        assertTrue("declined" !in stored.keys)
        again.adopted = null
        assertNull(PrefsWaLinkMarks(app).adopted)
    }

    private companion object {
        /** Two signing certificates (hex text, as `Signature` reads it). */
        const val OURS = "0a0b0c"
        const val THEIRS = "0d0e0f"
        const val STRANGER_UID = 20_123
        const val SIBLING_UID = 20_456
    }
}
