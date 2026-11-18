package dev.rosty.messages

import android.app.Application
import android.content.Context
import dev.rosty.core.FakeRustCore
import dev.rosty.core.FakeVaultOps
import dev.rosty.core.RustCore
import dev.rosty.core.ops.VaultOps
import dev.rosty.people.LiveWaNames
import dev.rosty.platform.contacts.ContactsRepository
import dev.rosty.platform.contacts.FakeContactsRepository
import dev.rosty.platform.sms.FakeSmsRepository
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.vault.FakeVaultKeystore
import dev.rosty.platform.vault.VaultKeystore
import dev.rosty.shell.HasContainer
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaRepository

/** The Messages container on fakes: no native library, no provider, no gateway. */
open class TestMessagesContainer(
    context: Context,
) : MessagesContainer(context) {
    val fakeRust = FakeRustCore()
    val fakeSms = FakeSmsRepository()
    val fakeSpecial = FakeSpecialAccess()
    val fakeWa = FakeWaRepository()

    /** The vault's Rust half and Keystore half (`plans/wa-vault.md`): no link and no vault until a test says. */
    val fakeVault = FakeVaultOps()
    val fakeKeystore = FakeVaultKeystore()

    override fun createRust(): RustCore = fakeRust

    // The eager start runs on a pool thread and could outlive the test (it once failed MessageAnnouncerTest).
    override fun startsDevLogAtCreate(): Boolean = false

    override fun createSms(): SmsRepository = fakeSms

    override fun createContacts(): ContactsRepository = FakeContactsRepository()

    override fun createSpecialAccess(): SpecialAccess = fakeSpecial

    override fun createWa(): WaRepository = fakeWa

    override fun createVaultOps(): VaultOps = fakeVault

    override fun createVaultKeystore(): VaultKeystore = fakeKeystore

    // No gateway: a number nobody names stays a number.
    override fun createLiveWaNames(): LiveWaNames = LiveWaNames(gateway = { null }, e164 = { null }, scope = scope)
}

class TestMessagesApp :
    Application(),
    HasContainer<TestMessagesContainer> {
    override val container: TestMessagesContainer by lazy { TestMessagesContainer(this) }
}
