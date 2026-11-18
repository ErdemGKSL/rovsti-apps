package dev.rosty.messages.wa

import dev.rosty.people.LiveName
import dev.rosty.people.LiveNameStore

/**
 * The WhatsApp names asked for numbers, in memory for the life of the process since the vault (`plans/wa-vault.md`
 * 13.5): never in `wa-live-names.json`. A restart (or a lock) asks again.
 */
class MemoryLiveNameStore : LiveNameStore {
    @Volatile private var answers: Map<String, LiveName> = emptyMap()

    override fun load(): Map<String, LiveName> = answers

    override fun save(answers: Map<String, LiveName>) {
        this.answers = answers.toMap()
    }

    fun clear() {
        answers = emptyMap()
    }
}
