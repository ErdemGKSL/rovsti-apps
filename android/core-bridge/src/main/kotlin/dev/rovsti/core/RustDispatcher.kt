package dev.rovsti.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Every blocking native call runs here: at most four threads, so a burst of calls cannot starve the rest of the
 * process and the Rust side sees bounded parallelism.
 */
@OptIn(ExperimentalCoroutinesApi::class)
val RustDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

/** Tells whether the current thread is the UI thread. A tiny interface so plain JVM tests can pass a fake. */
fun interface MainThreadCheck {
    fun isMainThread(): Boolean

    companion object {
        /** The real check. Touches `android.os.Looper` only when called. */
        val Android: MainThreadCheck =
            MainThreadCheck { android.os.Looper.getMainLooper() === android.os.Looper.myLooper() }
    }
}

/** Debug guard against a native call on the UI thread (the call would block frames). Off until [enable]d. */
object RustThreading {
    @Volatile
    private var check: MainThreadCheck? = null

    fun enable(check: MainThreadCheck = MainThreadCheck.Android) {
        this.check = check
    }

    fun disable() {
        check = null
    }

    /** Throws [IllegalStateException] when enabled and called on the main thread. */
    fun assertNotMain() {
        val c = check ?: return
        check(!c.isMainThread()) { "Rust calls must not run on the main thread" }
    }
}
