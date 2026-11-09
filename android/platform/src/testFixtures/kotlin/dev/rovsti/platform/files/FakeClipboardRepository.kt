package dev.rovsti.platform.files

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** A [ClipboardRepository] for tests. [copy] stores the text and emits a change. */
class FakeClipboardRepository(
    var text: String? = null,
    var accepts: Boolean = true,
) : ClipboardRepository {
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Each `copy(text, sensitive)` call. */
    val copies = mutableListOf<Pair<String, Boolean>>()

    override fun copy(
        text: String,
        sensitive: Boolean,
    ): Boolean {
        copies += text to sensitive
        if (!accepts) return false
        this.text = text
        changed.tryEmit(Unit)
        return true
    }

    override fun primaryText(): String? = text

    override fun changes(): Flow<Unit> = changed
}
