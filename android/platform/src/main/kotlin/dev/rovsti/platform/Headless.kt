package dev.rovsti.platform

import android.content.Context
import android.os.Looper
import dev.rovsti.core.HeadlessClient
import dev.rovsti.core.Rust
import dev.rovsti.core.RustCore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The headless path (AD-MSG4): a receiver that may run with no activity asks Rust for a decision. This is a
 * **blocking** wrapper over `Rust.core().headless(kind, json)` from `:core-bridge`, so call it off the main thread
 * (a receiver's `goAsync` thread, a service worker). With [assertOffMain] on, calling it on the main thread throws.
 *
 * Every answer is advisory: `null` means "no decision" (library missing, an error envelope, an empty or blank answer)
 * and callers fall back to a plain notification.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/Headless.kt
 */
object Headless {
    /** Debug builds set this (the app's `Application`); the main-thread check is then enforced. */
    @Volatile var assertOffMain: Boolean = false

    @Volatile private var override: RustCore? = null

    /** Tests: answers come from [core] (a `FakeRustCore`) and the native library is never loaded. */
    fun useCore(core: RustCore?) {
        override = core
    }

    /** `null` when there is no usable answer. Blocks: not the main thread. */
    fun call(
        kind: String,
        json: String,
    ): String? {
        if (assertOffMain) {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Headless.call blocks: run it off the main thread" }
        }
        val fake = override
        val client = if (fake != null) HeadlessClient(fake) {} else HeadlessClient(Rust.core())
        return try {
            runBlocking { client.ask(kind, json) }?.raw.let(::answer)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Pure: an empty or blank answer, or an error envelope (`"ok": false`), is "no decision". */
    fun answer(raw: String?): String? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        val failed =
            try {
                ((Json.parseToJsonElement(text) as? JsonObject)?.get("ok") as? JsonPrimitive)?.booleanOrNull == false
            } catch (_: IllegalArgumentException) {
                false
            }
        return if (failed) null else text
    }

    /** The directory Rust's `rovsti_ui::storage::data_dir` resolves to on Android. */
    fun dataDir(context: Context): String = context.noBackupFilesDir.absolutePath
}
