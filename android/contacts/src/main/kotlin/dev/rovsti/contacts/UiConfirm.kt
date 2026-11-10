package dev.rovsti.contacts

import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.design.text.UiText

/** A confirmation the screen shows: a [title] and a [body] that may come from a string resource. */
data class UiConfirm(
    val title: UiText,
    val body: UiText,
)

/** A confirmation the Rust core wrote: its words are already in the user's language. */
fun Confirm.toUi(): UiConfirm = UiConfirm(UiText.Literal(title), UiText.Literal(body))
