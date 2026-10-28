package dev.rovsti.bridge

/**
 * One-shot events (`document_result`, `ringtone_result`, `ui_request`) can arrive while Rust has no
 * session (the activity was recreated, the picker returned first). They wait here, one slot per
 * kind (the newest wins), and are drained once when Rust attaches, right after its snapshot
 * (AD-5 order). Pure: [deliver] is `RovstiBridge.emit`.
 */
class Mailbox(private val deliver: (kind: String, json: String) -> Unit) {
    private val slots = LinkedHashMap<String, String>()
    private var attached = false

    /** Delivers at once while attached; otherwise keeps it (replacing an older one of the same kind). */
    @Synchronized
    fun post(kind: String, json: String) {
        if (attached) {
            deliver(kind, json)
        } else {
            slots[kind] = json
        }
    }

    /** Rust's session is live: drains the slots once, in arrival order of their kinds. */
    fun attach() {
        val pending: List<Pair<String, String>>
        synchronized(this) {
            if (attached) return
            attached = true
            pending = slots.map { it.key to it.value }
            slots.clear()
        }
        for ((kind, json) in pending) deliver(kind, json)
    }

    @Synchronized
    fun detach() {
        attached = false
    }

    @Synchronized
    fun pending(): Int = slots.size

    @Synchronized
    fun isAttached(): Boolean = attached
}
