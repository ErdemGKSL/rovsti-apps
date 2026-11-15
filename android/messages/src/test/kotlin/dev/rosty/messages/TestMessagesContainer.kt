package dev.rosty.messages

import android.app.Application
import android.content.Context
import dev.rosty.core.FakeRustCore
import dev.rosty.core.RustCore
import dev.rosty.platform.contacts.ContactsRepository
import dev.rosty.platform.contacts.FakeContactsRepository
import dev.rosty.platform.sms.FakeSmsRepository
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.SpecialAccess
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

    override fun createRust(): RustCore = fakeRust

    override fun createSms(): SmsRepository = fakeSms

    override fun createContacts(): ContactsRepository = FakeContactsRepository()

    override fun createSpecialAccess(): SpecialAccess = fakeSpecial

    override fun createWa(): WaRepository = fakeWa
}

class TestMessagesApp :
    Application(),
    HasContainer<TestMessagesContainer> {
    override val container: TestMessagesContainer by lazy { TestMessagesContainer(this) }
}
