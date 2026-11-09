package dev.rovsti.contacts

import android.app.Application
import android.content.Context
import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.RustCore
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.calllog.FakeCallLogRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.system.FakeSpecialAccess
import dev.rovsti.platform.system.permissions.SpecialAccess
import dev.rovsti.shell.HasContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A container whose seams count what they build; no native library and no provider is touched. */
class CountingContactsContainer(
    context: Context,
) : ContactsContainer(context) {
    var rustInits = 0
    var contactsInits = 0
    val fakeRust = FakeRustCore()
    val fakeContacts = FakeContactsRepository()

    override fun createRust(): RustCore {
        rustInits++
        return fakeRust
    }

    override fun createContacts(): ContactsRepository {
        contactsInits++
        return fakeContacts
    }

    override fun createCallLog(): CallLogRepository = FakeCallLogRepository()

    override fun createSpecialAccess(): SpecialAccess = FakeSpecialAccess()
}

class TestContactsApp :
    Application(),
    HasContainer<CountingContactsContainer> {
    override val container: CountingContactsContainer by lazy { CountingContactsContainer(this) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestContactsApp::class)
class ContactsContainerTest {
    private val container get() = (RuntimeEnvironment.getApplication() as TestContactsApp).container

    @Test fun buildingTheContainerStartsNothing() {
        assertEquals(0, container.rustInits)
        assertEquals(0, container.contactsInits)
    }

    @Test fun theBookIsBuiltOnFirstUseOnlyAndWithoutRust() {
        val c = container
        assertSame(c.fakeContacts, c.contacts)
        assertSame(c.fakeContacts, c.contacts)
        assertEquals(1, c.contactsInits)
        assertEquals("reading the book needs no Rust", 0, c.rustInits)
    }

    @Test fun opsStartRustOnceWhenFirstUsed() {
        val c = container
        assertEquals("nothing asked for an op yet", 0, c.rustInits)
        c.ops
        c.search
        c.qr
        c.rust
        assertEquals("the three ops and rust itself share one core", 1, c.rustInits)
    }

    @Test fun contactsKeepsNeitherAppearanceNorHaptics() {
        assertNull(container.commonSettings.appearance.value)
        assertNull(container.commonSettings.haptics.value)
    }

    @Test fun permissionsAreTheContactsOnes() {
        assertEquals("contacts", container.appPermissions.app)
    }
}
