package dev.rovsti.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain

/**
 * Dispatchers.Main for the phone ViewModel tests, installed once per JVM and never reset.
 *
 * Calling `setMain`/`resetMain` around every test races with coroutines a previous test left on Main (a view model
 * scope or a Rust callback still returning): `TestMainDispatcher` throws "Dispatchers.Main is used concurrently with
 * setting it" when one of them touches Main while the next test's `@Before` swaps it. Installing it a single time
 * leaves nothing to race with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object UnconfinedMain {
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        Dispatchers.setMain(UnconfinedTestDispatcher())
        installed = true
    }
}
