package dev.rovsti.contacts.data

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactsPrefs
import dev.rovsti.core.model.contacts.DirRequest
import dev.rovsti.core.model.contacts.PrefsLoadAnswer
import dev.rovsti.core.model.contacts.PrefsSaveRequest
import dev.rovsti.core.model.core.Empty
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory `contacts-prefs.json` behind the fake core: what `prefs_load` answers is what `prefs_save` last got. */
class FakePrefsFile(
    initial: ContactsPrefs = ContactsStore.DEFAULT_PREFS,
) {
    @Volatile var disk: ContactsPrefs = initial
    val saves = AtomicInteger()
    val loads = AtomicInteger()

    /** When set, `prefs_save` fails with this message. */
    @Volatile var failSave: String? = null

    fun installOn(core: FakeRustCore) {
        core.onTyped("contacts.prefs_load", DirRequest.serializer(), PrefsLoadAnswer.serializer()) {
            loads.incrementAndGet()
            val p = disk
            Outcome.Ok(
                PrefsLoadAnswer(
                    prefs = p,
                    favoritesOrder = p.favoritesOrder.filter { it.isNotBlank() }.distinct(),
                    emergency = p.emergency,
                    reminderHour = p.reminderHour.coerceIn(0, 23),
                ),
            )
        }
        core.onTyped("contacts.prefs_save", PrefsSaveRequest.serializer(), Empty.serializer()) { req ->
            val fail = failSave
            if (fail != null) {
                Outcome.Err(
                    dev.rovsti.core.RustError
                        .Io(fail),
                )
            } else {
                saves.incrementAndGet()
                disk = req.prefs
                Outcome.Ok(Empty)
            }
        }
    }
}
