package dev.rosty.messages.settings

import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.chat.InsertReplyAnswer
import dev.rosty.core.model.chat.InsertReplyRequest
import dev.rosty.core.model.chat.RepliesAction
import dev.rosty.core.model.chat.RepliesAnswer
import dev.rosty.core.model.chat.RepliesRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The defaults of `rosty_core::quick_replies`. */
val DefaultReplies =
    listOf(
        "On my way.",
        "Can I call you later?",
        "I'm driving, I'll reply soon.",
        "Thanks!",
        "Running 10 minutes late.",
    )

/**
 * Answers `quick_replies.edit` and `quick_replies.insert` with a small Kotlin model of `rosty_core::quick_replies`
 * (trim, 500 characters, case-folded duplicates, 20 replies, chip labels cut at 28). The real rules are tested in Rust.
 */
fun FakeRustCore.fakeQuickReplies() {
    onTyped("quick_replies.edit", RepliesRequest.serializer(), RepliesAnswer.serializer()) { req ->
        val list = (req.list ?: DefaultReplies).toMutableList()
        val before = list.toList()
        var index: Int? = null
        var removed: String? = null
        when (req.action) {
            RepliesAction.Show -> {}

            RepliesAction.Add -> {
                val text = req.text.trim()
                val reason =
                    when {
                        text.isEmpty() -> "empty"
                        text.length > MAX_CHARS -> "too_long"
                        list.any { it.equals(text, ignoreCase = true) } -> "duplicate"
                        list.size >= MAX_REPLIES -> "full"
                        else -> null
                    }
                if (reason != null) {
                    return@onTyped Outcome.Err(
                        RustError.Invalid(reason, buildJsonObject { put("reason", JsonPrimitive(reason)) }),
                    )
                }
                list += text
                index = list.size - 1
            }

            RepliesAction.Remove -> {
                if (req.index in list.indices) removed = list.removeAt(req.index)
            }

            RepliesAction.Restore -> {
                if (req.text !in list && list.size < MAX_REPLIES) list.add(req.index.coerceAtMost(list.size), req.text)
            }

            RepliesAction.MoveUp -> {
                if (req.index in 1 until list.size) list[req.index - 1] = list.set(req.index, list[req.index - 1])
            }
        }
        Outcome.Ok(
            RepliesAnswer(
                list = list,
                chips = list.map { if (it.length <= CHIP) it else it.take(CHIP) + "…" },
                index = index,
                removed = removed,
                changed = list != before,
                maxReplies = MAX_REPLIES,
                maxChars = MAX_CHARS,
            ),
        )
    }
    onTyped("quick_replies.insert", InsertReplyRequest.serializer(), InsertReplyAnswer.serializer()) { req ->
        Outcome.Ok(InsertReplyAnswer(if (req.draft.isBlank()) req.reply else req.draft.trimEnd() + " " + req.reply))
    }
}

private const val MAX_REPLIES = 20
private const val MAX_CHARS = 500
private const val CHIP = 28
