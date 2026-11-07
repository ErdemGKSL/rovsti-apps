//! Bulk actions for multi-selected inbox rows: which actions apply, pin caps and
//! the notices shown afterwards.

use crate::thread_marks;

/// Most WhatsApp chats that can be pinned.
pub const WA_PIN_LIMIT: usize = 3;

/// Pill text when the WhatsApp pin cap is hit.
const WA_TOO_MANY_PINS: &str = "You can pin up to 3 WhatsApp chats";

/// What the inbox knows about one selected row.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BulkFacts {
    /// Row has unread messages.
    pub unread: bool,
    /// Row is muted.
    pub muted: bool,
    /// Row is pinned.
    pub pinned: bool,
    /// Row is archived.
    pub archived: bool,
    /// Row is a WhatsApp chat.
    pub whatsapp: bool,
    /// Row is an SMS conversation.
    pub sms: bool,
    /// Row is SMS only (no WhatsApp side), so it can be deleted locally.
    pub sms_only: bool,
}

/// An action applicable to a whole selection.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum BulkAction {
    /// Mark as read.
    MarkRead,
    /// Mark as unread.
    MarkUnread,
    /// Mute.
    Mute,
    /// Unmute.
    Unmute,
    /// Pin.
    Pin,
    /// Unpin.
    Unpin,
    /// Archive.
    Archive,
    /// Move out of the archive.
    Unarchive,
    /// Delete the conversations.
    Delete,
}

impl BulkAction {
    /// Button label.
    pub fn label(self) -> &'static str {
        match self {
            Self::MarkRead => "Mark read",
            Self::MarkUnread => "Mark unread",
            Self::Mute => "Mute",
            Self::Unmute => "Unmute",
            Self::Pin => "Pin",
            Self::Unpin => "Unpin",
            Self::Archive => "Archive",
            Self::Unarchive => "Unarchive",
            Self::Delete => "Delete",
        }
    }

    /// Whether the action needs a confirmation.
    pub fn is_destructive(self) -> bool {
        matches!(self, Self::Delete)
    }
}

/// Actions to offer for `selected`, in display order.
pub fn actions(selected: &[BulkFacts], in_archive: bool) -> Vec<BulkAction> {
    if selected.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(5);
    out.push(if selected.iter().any(|r| r.unread) {
        BulkAction::MarkRead
    } else {
        BulkAction::MarkUnread
    });
    out.push(if selected.iter().all(|r| r.muted) {
        BulkAction::Unmute
    } else {
        BulkAction::Mute
    });
    if !in_archive {
        out.push(if selected.iter().all(|r| r.pinned) {
            BulkAction::Unpin
        } else {
            BulkAction::Pin
        });
    }
    out.push(if in_archive {
        BulkAction::Unarchive
    } else {
        BulkAction::Archive
    });
    if selected.iter().all(|r| r.sms_only) {
        out.push(BulkAction::Delete);
    }
    out
}

/// Checks that pinning the unpinned rows of `selected` stays within both caps.
pub fn pin_room(
    already_pinned_wa: usize,
    already_pinned_sms: usize,
    selected: &[BulkFacts],
) -> Result<(), &'static str> {
    let new_wa = selected.iter().filter(|r| r.whatsapp && !r.pinned).count();
    let new_sms = selected.iter().filter(|r| r.sms && !r.pinned).count();
    if already_pinned_wa + new_wa > WA_PIN_LIMIT {
        return Err(WA_TOO_MANY_PINS);
    }
    if already_pinned_sms + new_sms > thread_marks::MAX_LOCAL_PINS {
        return Err(thread_marks::TOO_MANY_PINS);
    }
    Ok(())
}

fn chats(n: usize) -> String {
    format!("{n} {}", if n == 1 { "chat" } else { "chats" })
}

fn conversations(n: usize) -> String {
    format!(
        "{n} {}",
        if n == 1 {
            "conversation"
        } else {
            "conversations"
        }
    )
}

/// Pill text after `action` succeeded on `n` rows.
pub fn done_notice(action: BulkAction, n: usize) -> String {
    match action {
        BulkAction::MarkRead => format!("Marked {} as read", chats(n)),
        BulkAction::MarkUnread => format!("Marked {} as unread", chats(n)),
        BulkAction::Mute => format!("Muted {}", chats(n)),
        BulkAction::Unmute => format!("Unmuted {}", chats(n)),
        BulkAction::Pin => format!("Pinned {}", chats(n)),
        BulkAction::Unpin => format!("Unpinned {}", chats(n)),
        BulkAction::Archive => format!("Archived {}", chats(n)),
        BulkAction::Unarchive => format!("Unarchived {}", chats(n)),
        BulkAction::Delete => format!("Deleted {}", conversations(n)),
    }
}

/// Confirmation text before deleting `n` conversations.
pub fn delete_prompt(n: usize) -> String {
    format!("Delete {}? This can't be undone.", conversations(n))
}

/// Pill text when only some rows succeeded.
pub fn partial_notice(done: usize, failed: usize) -> String {
    format!("{} done; {failed} failed", chats(done))
}

#[cfg(test)]
mod tests {
    use super::*;
    use BulkAction::*;

    fn wa() -> BulkFacts {
        BulkFacts {
            whatsapp: true,
            ..Default::default()
        }
    }
    fn sms() -> BulkFacts {
        BulkFacts {
            sms: true,
            sms_only: true,
            ..Default::default()
        }
    }

    #[test]
    fn empty_selection_has_no_actions() {
        assert!(actions(&[], false).is_empty());
    }

    #[test]
    fn mixed_selection() {
        let rows = [
            BulkFacts {
                unread: true,
                ..wa()
            },
            sms(),
        ];
        assert_eq!(actions(&rows, false), vec![MarkRead, Mute, Pin, Archive]);
    }

    #[test]
    fn all_read_offers_mark_unread() {
        assert_eq!(actions(&[sms()], false)[0], MarkUnread);
    }

    #[test]
    fn all_muted_and_pinned() {
        let r = BulkFacts {
            muted: true,
            pinned: true,
            ..sms()
        };
        assert_eq!(
            actions(&[r, r], false),
            vec![MarkUnread, Unmute, Unpin, Archive, Delete]
        );
        let half = [r, sms()];
        let a = actions(&half, false);
        assert!(a.contains(&Mute) && a.contains(&Pin));
    }

    #[test]
    fn archive_view() {
        let r = BulkFacts {
            pinned: true,
            ..sms()
        };
        let a = actions(&[r], true);
        assert_eq!(a, vec![MarkUnread, Mute, Unarchive, Delete]);
    }

    #[test]
    fn whatsapp_row_hides_delete() {
        assert!(!actions(&[sms(), wa()], false).contains(&Delete));
        assert!(actions(&[sms(), sms()], false).contains(&Delete));
    }

    #[test]
    fn destructive_and_labels() {
        assert!(Delete.is_destructive());
        for a in [
            MarkRead, MarkUnread, Mute, Unmute, Pin, Unpin, Archive, Unarchive,
        ] {
            assert!(!a.is_destructive());
        }
        assert_eq!(MarkRead.label(), "Mark read");
        assert_eq!(MarkUnread.label(), "Mark unread");
        assert_eq!(Mute.label(), "Mute");
        assert_eq!(Unmute.label(), "Unmute");
        assert_eq!(Pin.label(), "Pin");
        assert_eq!(Unpin.label(), "Unpin");
        assert_eq!(Archive.label(), "Archive");
        assert_eq!(Unarchive.label(), "Unarchive");
        assert_eq!(Delete.label(), "Delete");
    }

    #[test]
    fn wa_pin_cap() {
        assert_eq!(pin_room(1, 0, &[wa(), wa()]), Ok(()));
        assert_eq!(
            pin_room(2, 0, &[wa(), wa()]),
            Err("You can pin up to 3 WhatsApp chats")
        );
        let pinned = BulkFacts {
            pinned: true,
            ..wa()
        };
        assert_eq!(pin_room(3, 0, &[pinned]), Ok(()));
    }

    #[test]
    fn sms_pin_cap() {
        assert_eq!(pin_room(0, 4, &[sms()]), Ok(()));
        assert_eq!(
            pin_room(0, 4, &[sms(), sms()]),
            Err(thread_marks::TOO_MANY_PINS)
        );
        assert_eq!(pin_room(3, 5, &[]), Ok(()));
    }

    #[test]
    fn done_notices() {
        let cases = [
            (MarkRead, "Marked 1 chat as read", "Marked 3 chats as read"),
            (
                MarkUnread,
                "Marked 1 chat as unread",
                "Marked 3 chats as unread",
            ),
            (Mute, "Muted 1 chat", "Muted 3 chats"),
            (Unmute, "Unmuted 1 chat", "Unmuted 3 chats"),
            (Pin, "Pinned 1 chat", "Pinned 3 chats"),
            (Unpin, "Unpinned 1 chat", "Unpinned 3 chats"),
            (Archive, "Archived 1 chat", "Archived 3 chats"),
            (Unarchive, "Unarchived 1 chat", "Unarchived 3 chats"),
            (Delete, "Deleted 1 conversation", "Deleted 3 conversations"),
        ];
        for (a, one, many) in cases {
            assert_eq!(done_notice(a, 1), one);
            assert_eq!(done_notice(a, 3), many);
        }
    }

    #[test]
    fn prompts_and_partial() {
        assert_eq!(
            delete_prompt(2),
            "Delete 2 conversations? This can't be undone."
        );
        assert_eq!(
            delete_prompt(1),
            "Delete 1 conversation? This can't be undone."
        );
        assert_eq!(partial_notice(3, 1), "3 chats done; 1 failed");
        assert_eq!(partial_notice(1, 2), "1 chat done; 2 failed");
    }
}
