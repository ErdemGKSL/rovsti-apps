//! Editing a sent message.

/// How long after sending WhatsApp accepts an edit (the official apps and the gateway: 15 minutes).
pub const EDIT_WINDOW_MS: i64 = 15 * 60 * 1000;

/// What the edit rule needs to know about a message.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct EditTarget {
    pub from_me: bool,
    /// The message went out: it has the gateway's id (a row still in the outbox is edited in the composer instead).
    pub sent: bool,
    /// A text, or a photo, video or document with a caption slot.
    pub has_text_slot: bool,
    pub deleted: bool,
    /// A view-once message, a placeholder or a system line.
    pub special: bool,
    /// When it was sent (unix ms).
    pub sent_ms: i64,
}

/// Until when (unix ms) the message can be edited, or `None` when it never can.
#[must_use]
pub fn editable_until(target: &EditTarget) -> Option<i64> {
    (target.from_me && target.sent && target.has_text_slot && !target.deleted && !target.special)
        .then(|| target.sent_ms.saturating_add(EDIT_WINDOW_MS))
}

/// Whether `new` may replace `old`: not blank, and not the same text.
#[must_use]
pub fn edit_text_ok(old: &str, new: &str) -> bool {
    let new = new.trim();
    !new.is_empty() && new != old.trim()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mine() -> EditTarget {
        EditTarget {
            from_me: true,
            sent: true,
            has_text_slot: true,
            sent_ms: 1_000,
            ..EditTarget::default()
        }
    }

    #[test]
    fn my_sent_text_is_editable_for_fifteen_minutes() {
        assert_eq!(editable_until(&mine()), Some(1_000 + EDIT_WINDOW_MS));
    }

    #[test]
    fn others_unsent_deleted_and_special_messages_are_not() {
        for target in [
            EditTarget {
                from_me: false,
                ..mine()
            },
            EditTarget {
                sent: false,
                ..mine()
            },
            EditTarget {
                has_text_slot: false,
                ..mine()
            },
            EditTarget {
                deleted: true,
                ..mine()
            },
            EditTarget {
                special: true,
                ..mine()
            },
        ] {
            assert_eq!(editable_until(&target), None, "{target:?}");
        }
    }

    #[test]
    fn an_edit_must_change_the_text() {
        assert!(edit_text_ok("Hello", "Hello!"));
        assert!(!edit_text_ok("Hello", " Hello "));
        assert!(!edit_text_ok("Hello", "   "));
    }
}
