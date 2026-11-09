package dev.rovsti.phone

import android.content.Context
import dev.rovsti.core.getOrNull
import dev.rovsti.core.model.phone.TelUriRequest
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.QrOps
import dev.rovsti.core.ops.SearchOps
import dev.rovsti.phone.setup.AndroidSetupFacts
import dev.rovsti.phone.setup.InMemorySetupMemory
import dev.rovsti.phone.setup.SetupFactsSource
import dev.rovsti.phone.setup.SetupMemory
import dev.rovsti.platform.calllog.AndroidBlocklistRepository
import dev.rovsti.platform.calllog.AndroidCallLogRepository
import dev.rovsti.platform.calllog.BlocklistRepository
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.contacts.AndroidContactsRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.telephony.AndroidSimRepository
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.CallsApi
import dev.rovsti.platform.telephony.SimRepository
import dev.rovsti.shell.ShellContainer
import dev.rovsti.wa.calls.AndroidCallAudioPump
import dev.rovsti.wa.calls.CallAudioPump
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A number the Keypad is asked to show: a `tel:` link decoded by `dial.fromTelUri`, or a WhatsApp hand-off. */
data class DialRequest(
    val text: String,
    /** The WhatsApp chip stays disabled for a hand-off until the WhatsApp calls land (E5.8). */
    val fromHandoff: Boolean = false,
    /** Makes two requests for the same number two values, so the Keypad takes the second one too. */
    val serial: Long = 0,
)

/**
 * What Rovsti Phone adds to the shared [ShellContainer] (ARCH 5.3). Every member is lazy and built by a `create*`
 * seam, so a cold path (the `InCallService`, a notification receiver) pays for what it touches and tests swap in fakes
 * without a native library.
 */
open class PhoneContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.phone) {
    /** The call owner (`CallRegistry`, the process-wide singleton the `InCallService` feeds). */
    val calls: CallsApi by lazy { createCalls() }

    val callLog: CallLogRepository by lazy { createCallLog() }

    val blocklist: BlocklistRepository by lazy { createBlocklist() }

    val contacts: ContactsRepository by lazy { createContacts() }

    /** The call-capable SIMs and the system default. */
    val sims: SimRepository by lazy { createSims() }

    /** Every phone decision the Rust core makes (ARCH 4). */
    val ops: PhoneOps by lazy { createOps() }

    /** The contact index and T9 search. */
    val search: SearchOps by lazy { createSearch() }

    val qr: QrOps by lazy { createQr() }

    /** The WhatsApp calls of the linked instance; started by the screens that show them (E5.8). */
    val waCalls: WaCalls by lazy { createWaCalls() }

    /** What Android says about the Setup checklist rows. */
    val setupFacts: SetupFactsSource by lazy { createSetupFacts() }

    /** The Xiaomi confirmations and the "already offered" flag. In memory until `PhoneStore` (Step 2) persists them. */
    val setupMemory: SetupMemory by lazy { createSetupMemory() }

    /** A number for the Keypad from a `tel:` link or a hand-off; the Keypad consumes it (sets it back to null). */
    val pendingDial: MutableStateFlow<DialRequest?> = MutableStateFlow(null)

    /** The JID of a WhatsApp call handed over by a sibling app; the app shows a toast and clears it (E5.8 places it). */
    val pendingHandoff: MutableStateFlow<String?> = MutableStateFlow(null)

    /** The payload of an Answer or Decline tapped on a WhatsApp call notification, until E5.8 acts on it once. */
    val pendingWaCallAction: MutableStateFlow<String?> = MutableStateFlow(null)

    private var dialSerial = 0L

    /** Sets [text] as the next [pendingDial]. */
    fun offerDial(
        text: String,
        fromHandoff: Boolean = false,
    ) {
        pendingDial.value = DialRequest(text, fromHandoff, ++dialSerial)
    }

    /** Decodes a `tel:` link with `dial.fromTelUri` and offers the number to the Keypad (an empty link is an empty field). */
    fun openTelUri(uri: String) {
        scope.launch {
            ops
                .fromTelUri(TelUriRequest(uri))
                .getOrNull()
                ?.let { offerDial(it.text) }
        }
    }

    protected open fun createCalls(): CallsApi = CallRegistry

    protected open fun createCallLog(): CallLogRepository = AndroidCallLogRepository(context, Dispatchers.IO)

    protected open fun createBlocklist(): BlocklistRepository = AndroidBlocklistRepository(context, Dispatchers.IO)

    protected open fun createContacts(): ContactsRepository = AndroidContactsRepository(context, Dispatchers.IO)

    protected open fun createSims(): SimRepository = AndroidSimRepository(context, Dispatchers.IO)

    protected open fun createOps(): PhoneOps = NativePhoneOps(rust)

    protected open fun createSearch(): SearchOps = NativeSearchOps(rust)

    protected open fun createQr(): QrOps = NativeQrOps(rust)

    protected open fun createCallPump(): CallAudioPump = AndroidCallAudioPump(context)

    protected open fun createWaCalls(): WaCalls =
        WaCalls(wa, permissions.state(PermissionGroup.MICROPHONE), createCallPump(), scope)

    protected open fun createSetupFacts(): SetupFactsSource = AndroidSetupFacts(context, permissions, specialAccess)

    protected open fun createSetupMemory(): SetupMemory = InMemorySetupMemory()
}
