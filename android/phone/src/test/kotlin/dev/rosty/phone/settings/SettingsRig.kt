package dev.rosty.phone.settings

import dev.rosty.core.model.search.WireContact
import dev.rosty.core.model.search.WireNumber
import dev.rosty.core.ops.NativeContactsOps
import dev.rosty.core.ops.NativePhoneOps
import dev.rosty.phone.HostRust
import dev.rosty.phone.data.PhoneStore
import dev.rosty.phone.data.tempDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** The real Rust core with a preferences store of its own, for the Settings view model tests. */
internal class SettingsRig(
    val host: HostRust,
    scope: CoroutineScope,
) {
    val ops = NativePhoneOps(host.core)
    val contactsOps = NativeContactsOps(host.core)
    val dir = tempDir("rosty-settings")
    val store = PhoneStore(ops, scope, dir, Dispatchers.IO, debounceMs = 20)

    /** A second store on the same directory: what a restart would read. */
    fun reloaded(scope: CoroutineScope) = PhoneStore(ops, scope, dir, Dispatchers.IO, debounceMs = 20)
}

internal suspend fun <T> Flow<T>.until(check: (T) -> Boolean): T = withTimeout(10_000) { first(check) }

internal fun person(
    id: Long,
    name: String,
    number: String,
) = WireContact(id = id, displayName = name, numbers = listOf(WireNumber(number)), lookupKey = "k-$id")
