package dev.rovsti.core

/**
 * The eight JNI entry points of `librovsti_ffi` (architecture 6.3). Names and signatures are fixed by the exported
 * `Java_dev_rovsti_core_RustNative_*` symbols; everything else in this module goes through [RustCore].
 *
 * Byte arrays are UTF-8 JSON envelopes (see [Envelope]). A null result means the native side could not even build
 * an answer; callers turn it into [RustError.Panic].
 */
object RustNative {
    @Volatile
    private var loaded = false

    /** Loads `librovsti_ffi` once; later calls are no-ops. Throws [UnsatisfiedLinkError] if the library is missing. */
    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("rovsti_ffi")
        loaded = true
    }

    /** Stores the app directories; answers `{"ok": {"version", "abi"}}`. */
    external fun init(
        dataDir: String,
        cacheDir: String,
        appId: String,
        sdk: Int,
    ): ByteArray?

    /** Runs one `"<domain>.<verb>"` op with a JSON request body. */
    external fun call(
        op: String,
        request: ByteArray,
    ): ByteArray?

    /** Process-less work for broadcast receivers and services: JSON in, JSON out, `""` for an unknown kind. */
    external fun headless(
        kind: String,
        json: String,
    ): String?

    /** One binary map tile of an open source handle. */
    external fun tile(
        handle: Long,
        z: Int,
        x: Int,
        y: Int,
        flags: Int,
    ): ByteArray?

    /** Microphone samples (16-bit PCM) for a call. */
    external fun audioPush(
        call: Long,
        pcm: ByteArray,
    )

    /** Fills [out] with playback samples; returns the number of bytes written. */
    external fun audioPull(
        call: Long,
        out: ByteArray,
    ): Int

    /** Registers the object whose `onEvent(String, byte[])` receives Rust events; null clears it. */
    external fun setEventSink(sink: RustEvents?)

    /** Drops a native handle; unknown ids are ignored. */
    external fun release(handle: Long)
}
