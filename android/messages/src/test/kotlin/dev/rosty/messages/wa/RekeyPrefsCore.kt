package dev.rosty.messages.wa

import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.messages.DirRequest
import dev.rosty.core.model.messages.DraftsAnswer
import dev.rosty.core.model.messages.DraftsPutRequest
import dev.rosty.core.model.messages.DraftsRekeyRequest
import dev.rosty.core.model.messages.DraftsSaveRequest
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.PrefsEditAnswer
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.model.messages.PrefsLoadAnswer
import dev.rosty.core.model.messages.PrefsSaveRequest
import dev.rosty.messages.spam.prefsWith

/** `messages.drafts_*` and `messages.prefs_*` with Rust's rekey rules in miniature (the real ones: Rust tests). */
internal class RekeyPrefsCore(
    var prefs: MessagesPrefs = prefsWith(),
    var drafts: Map<String, String> = emptyMap(),
    /** The core the ops are scripted on; a test container passes its own. */
    val core: FakeRustCore = FakeRustCore(),
) {
    var prefsSaves = 0
    var draftsSaves = 0

    init {
        core.onTyped("messages.prefs_load", DirRequest.serializer(), PrefsLoadAnswer.serializer()) {
            Outcome.Ok(PrefsLoadAnswer(prefs, isReset = false))
        }
        core.onTyped("messages.prefs_save", PrefsSaveRequest.serializer(), Empty.serializer()) {
            prefs = it.prefs
            prefsSaves++
            Outcome.Ok(Empty)
        }
        core.onTyped("messages.drafts_load", DirRequest.serializer(), DraftsAnswer.serializer()) {
            Outcome.Ok(DraftsAnswer(drafts))
        }
        core.onTyped("messages.drafts_save", DraftsSaveRequest.serializer(), Empty.serializer()) {
            drafts = it.drafts
            draftsSaves++
            Outcome.Ok(Empty)
        }
        // `Drafts::rekey`: the draft of `into` is kept when both have one.
        core.onTyped("messages.drafts_rekey", DraftsRekeyRequest.serializer(), DraftsAnswer.serializer()) { req ->
            val from = req.drafts[req.from]
            val rest = req.drafts - req.from
            Outcome.Ok(DraftsAnswer(if (from == null || req.into in rest) rest else rest + (req.into to from)))
        }
        // `Drafts::put`: blank text removes the draft.
        core.onTyped("messages.drafts_put", DraftsPutRequest.serializer(), DraftsAnswer.serializer()) { req ->
            Outcome.Ok(
                DraftsAnswer(
                    if (req.text.isBlank()) req.drafts - req.thread else req.drafts + (req.thread to req.text),
                ),
            )
        }
        // `MessagesPrefs::rekey_thread`: the mute and its end follow the chat. `unmute`: both go.
        core.onTyped("messages.prefs_edit", PrefsEditRequest.serializer(), PrefsEditAnswer.serializer()) { req ->
            val p = req.prefs
            if (req.edit == "unmute") {
                val next = p.copy(muted = p.muted - req.tag, muteUntil = p.muteUntil - req.tag)
                return@onTyped Outcome.Ok(PrefsEditAnswer(next, next != p, null))
            }
            check(req.edit == "rekey") { "unexpected edit ${req.edit}" }
            val muted = if (req.from in p.muted) (p.muted - req.from + req.into).distinct() else p.muted
            val until =
                p.muteUntil[req.from]?.let { p.muteUntil - req.from + (req.into to it) } ?: p.muteUntil
            val next = p.copy(muted = muted, muteUntil = until)
            Outcome.Ok(PrefsEditAnswer(next, next != p, null))
        }
    }
}
