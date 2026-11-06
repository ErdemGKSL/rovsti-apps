//! What the conversation menus of an SMS thread offer. Pure: the menus in
//! `main.rs` only map [`SmsAction`]s to rows.

/// One thing a user can do with an SMS conversation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SmsAction {
    MarkRead,
    Mute,
    Unmute,
    NotSpam,
    Block,
    Unblock,
    Call,
    AddContact,
    OpenContact,
    Delete,
}

/// What decides which actions apply.
#[derive(Clone, Copy, Debug, Default)]
pub struct SmsActionContext {
    pub unread: bool,
    pub muted: bool,
    pub in_spam: bool,
    /// One-to-one: the thread has exactly one other address.
    pub single_sender: bool,
    pub known_contact: bool,
    pub blocked: bool,
    /// The menu is the conversation page's, not an inbox row's.
    pub on_page: bool,
}

/// The actions to list, in order, the destructive ones last.
pub fn actions(ctx: &SmsActionContext) -> Vec<SmsAction> {
    let mut out = Vec::new();
    if ctx.on_page {
        if ctx.single_sender {
            out.push(SmsAction::Call);
            out.push(if ctx.known_contact {
                SmsAction::OpenContact
            } else {
                SmsAction::AddContact
            });
        }
    } else {
        if ctx.unread {
            out.push(SmsAction::MarkRead);
        }
        out.push(if ctx.muted {
            SmsAction::Unmute
        } else {
            SmsAction::Mute
        });
        if ctx.in_spam && ctx.single_sender {
            out.push(SmsAction::NotSpam);
        }
    }
    if ctx.single_sender {
        out.push(if ctx.blocked {
            SmsAction::Unblock
        } else {
            SmsAction::Block
        });
    }
    out.push(SmsAction::Delete);
    out
}

pub fn label(action: SmsAction) -> &'static str {
    match action {
        SmsAction::MarkRead => "Mark as read",
        SmsAction::Mute => "Mute",
        SmsAction::Unmute => "Unmute",
        SmsAction::NotSpam => "Not spam",
        SmsAction::Block => "Block",
        SmsAction::Unblock => "Unblock",
        SmsAction::Call => "Call",
        SmsAction::AddContact => "Add to contacts",
        SmsAction::OpenContact => "Open contact",
        SmsAction::Delete => "Delete conversation",
    }
}

/// Shown in red.
pub fn is_danger(action: SmsAction) -> bool {
    matches!(action, SmsAction::Block | SmsAction::Delete)
}

/// The confirmation text before a conversation is deleted.
pub fn delete_prompt(title: &str) -> String {
    let title = title.trim();
    let who = if title.is_empty() {
        "this number"
    } else {
        title
    };
    format!("Delete the conversation with {who}? Its messages are removed from this phone.")
}

/// The one address of a one-to-one thread; `None` for no address or a group.
pub fn sender_of(addresses: &[String]) -> Option<&str> {
    let mut found: Option<&str> = None;
    for address in addresses.iter().map(|a| a.trim()).filter(|a| !a.is_empty()) {
        match found {
            None => found = Some(address),
            Some(first) if first == address => {}
            Some(_) => return None,
        }
    }
    found
}

/// Shown when deleting needs the default-SMS-app role.
pub const NEEDS_ROLE: &str = "Make Rovsti Messages your default SMS app to delete messages";

/// The pill action label offered next to [`NEEDS_ROLE`].
pub const MAKE_DEFAULT: &str = "Make default";

/// What the pill says after Not spam.
pub fn not_spam_text(sender: &str) -> String {
    format!("{} is no longer spam", sender.trim())
}

/// What the pill says after Block; a blank title reads "this number".
pub fn blocked_text(title: &str) -> String {
    let title = title.trim();
    format!(
        "Blocked {}",
        if title.is_empty() {
            "this number"
        } else {
            title
        }
    )
}

/// Why Block was refused before Android was asked.
pub fn refusal_note(refusal: rovsti_core::blocklist::BlockRefusal) -> &'static str {
    use rovsti_core::blocklist::BlockRefusal;
    match refusal {
        BlockRefusal::Emergency => "Emergency numbers can't be blocked",
        BlockRefusal::NoNumber | BlockRefusal::NotANumber => "Unknown number, cannot block",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use SmsAction::*;

    fn row() -> SmsActionContext {
        SmsActionContext {
            single_sender: true,
            ..SmsActionContext::default()
        }
    }

    #[test]
    fn row_actions_follow_the_state() {
        assert_eq!(actions(&row()), vec![Mute, Block, Delete]);
        let c = SmsActionContext {
            unread: true,
            muted: true,
            ..row()
        };
        assert_eq!(actions(&c), vec![MarkRead, Unmute, Block, Delete]);
        let c = SmsActionContext {
            in_spam: true,
            ..row()
        };
        assert_eq!(actions(&c), vec![Mute, NotSpam, Block, Delete]);
        let c = SmsActionContext {
            blocked: true,
            in_spam: true,
            ..row()
        };
        assert_eq!(actions(&c), vec![Mute, NotSpam, Unblock, Delete]);
    }

    #[test]
    fn a_blocked_row_offers_unblock_never_both() {
        let c = SmsActionContext {
            blocked: true,
            ..row()
        };
        assert_eq!(actions(&c), vec![Mute, Unblock, Delete]);
        assert!(!actions(&c).contains(&Block));
    }

    #[test]
    fn a_group_row_has_no_sender_actions() {
        let c = SmsActionContext {
            single_sender: false,
            in_spam: true,
            unread: true,
            ..SmsActionContext::default()
        };
        assert_eq!(actions(&c), vec![MarkRead, Mute, Delete]);
    }

    #[test]
    fn page_actions_depend_on_the_contact() {
        let page = SmsActionContext {
            on_page: true,
            unread: true,
            muted: true,
            ..row()
        };
        assert_eq!(actions(&page), vec![Call, AddContact, Block, Delete]);
        assert!(!actions(&page).contains(&OpenContact));
        let known = SmsActionContext {
            known_contact: true,
            ..page
        };
        assert_eq!(actions(&known), vec![Call, OpenContact, Block, Delete]);
        let blocked = SmsActionContext {
            blocked: true,
            ..page
        };
        assert_eq!(actions(&blocked), vec![Call, AddContact, Unblock, Delete]);
        let group = SmsActionContext {
            single_sender: false,
            ..page
        };
        assert_eq!(actions(&group), vec![Delete]);
    }

    #[test]
    fn labels_are_unique_and_danger_is_marked() {
        let all = [
            MarkRead,
            Mute,
            Unmute,
            NotSpam,
            Block,
            Unblock,
            Call,
            AddContact,
            OpenContact,
            Delete,
        ];
        let labels: std::collections::HashSet<_> = all.iter().map(|a| label(*a)).collect();
        assert_eq!(labels.len(), all.len());
        assert!(labels.iter().all(|l| !l.is_empty()));
        assert!(is_danger(Block) && is_danger(Delete));
        assert!(!is_danger(Mute) && !is_danger(Call) && !is_danger(Unblock));
        assert_eq!(
            (label(Unblock), label(OpenContact)),
            ("Unblock", "Open contact")
        );
        assert_eq!(MAKE_DEFAULT, "Make default");
    }

    #[test]
    fn the_delete_prompt_names_the_conversation() {
        assert_eq!(
            delete_prompt("Ayşe"),
            "Delete the conversation with Ayşe? Its messages are removed from this phone."
        );
        assert!(delete_prompt("  ").contains("this number"));
        assert!(!NEEDS_ROLE.is_empty());
    }

    #[test]
    fn sender_of_needs_exactly_one_address() {
        let v = |a: &[&str]| a.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>();
        assert_eq!(sender_of(&v(&[])), None);
        assert_eq!(sender_of(&v(&["+90555"])), Some("+90555"));
        assert_eq!(sender_of(&v(&[" ", " +90555 "])), Some("+90555"));
        assert_eq!(sender_of(&v(&["+90555", "+90556"])), None);
        assert_eq!(sender_of(&v(&["+90555", "+90555"])), Some("+90555"));
    }

    #[test]
    fn pill_texts_and_refusals() {
        assert_eq!(not_spam_text(" Bank "), "Bank is no longer spam");
        assert_eq!(blocked_text("Ayse"), "Blocked Ayse");
        assert_eq!(blocked_text("  "), "Blocked this number");
        let emergency = rovsti_core::wire::EmergencyList::default();
        let refusal = rovsti_core::blocklist::can_block("112", &emergency).expect_err("refused");
        assert_eq!(refusal_note(refusal), "Emergency numbers can't be blocked");
        let refusal = rovsti_core::blocklist::can_block("*#06#", &emergency).expect_err("refused");
        assert_eq!(refusal_note(refusal), "Unknown number, cannot block");
    }
}
