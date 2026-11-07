//! The popover menu of an inbox row or an open conversation: which items it lists.
//!
//! Pure (no gpui): `main.rs` maps the keys back to actions. A row can stand for
//! a WhatsApp chat, an SMS thread, or both merged into one. Each action appears
//! once and applies to every thread the row holds, so the menu never repeats
//! "Pin" or "Archive" for the second channel.
//!
//! Groups, as the layout spec (2.15) sets them:
//! 1. Select (rows) or the page actions (Search in chat, Send later, ...)
//! 2. Pin, exactly one of Mark as read/unread, Mute... or Unmute, Archive, plus
//!    the Spam and contact actions
//! 3. Block (SMS number senders only) and the destructive Delete conversation

use rovsti_core::mute::MuteFor;
use rovsti_ui::menu::model::{MenuGroup, MenuItem, MenuModel};

use crate::sms_actions::{self, SmsAction, SmsActionContext};

/// Flags of the row's WhatsApp chat.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct WaFlags {
    pub pinned: bool,
    pub archived: bool,
    pub muted: bool,
    /// Unread messages, or marked unread by hand.
    pub unread: bool,
}

/// Flags of the row's SMS thread.
#[derive(Clone, Copy, Debug, Default)]
pub struct SmsFlags {
    pub ctx: SmsActionContext,
    /// A new conversation with no provider thread yet: nothing to delete,
    /// archive, pin or mark.
    pub new_thread: bool,
}

/// What a menu adds around the row actions.
#[derive(Clone, Debug, Default)]
pub struct Extras {
    /// An inbox row: a "Select" item opens the group list.
    pub select: bool,
    /// An open conversation's menu.
    pub page: Option<PageExtras>,
}

/// Items only an open conversation's menu has.
#[derive(Clone, Debug)]
pub struct PageExtras {
    /// "Starred messages", with a count for SMS.
    pub starred: String,
    /// A 1:1 WhatsApp chat with a phone number: Call and a contact item.
    pub wa_number: bool,
    /// That number is in the address book ("Open contact").
    pub known_contact: bool,
}

/// Mute length keys of the submenu.
pub const MUTE_KEYS: [(&str, MuteFor); 3] = [
    ("mute_8h", MuteFor::EightHours),
    ("mute_week", MuteFor::Week),
    ("mute_always", MuteFor::Always),
];

/// The mute length a submenu key stands for.
pub fn mute_for_key(key: &str) -> Option<MuteFor> {
    MUTE_KEYS.iter().find(|(k, _)| *k == key).map(|(_, m)| *m)
}

/// The "Mute for" submenu (also the header bell's list).
pub fn mute_menu_items() -> MenuModel {
    MenuModel::new(vec![MenuGroup::new(
        MUTE_KEYS
            .iter()
            .map(|&(key, choice)| MenuItem::new(key, choice.label()).icon("clock"))
            .collect(),
    )])
}

/// The prompt above the Delete button of a conversation.
pub fn delete_prompt(title: &str) -> String {
    sms_actions::delete_prompt(title)
}

pub(crate) fn sms_actions_of(sms: Option<SmsFlags>) -> Vec<SmsAction> {
    let Some(sms) = sms else {
        return Vec::new();
    };
    let mut actions = sms_actions::actions(&sms.ctx);
    if sms.new_thread {
        actions.retain(|a| {
            !matches!(
                a,
                SmsAction::Delete | SmsAction::Archive | SmsAction::MarkUnread | SmsAction::Pin
            )
        });
    }
    actions
}

/// Whether every thread that can be pinned already is.
pub fn pinned_all(wa: Option<WaFlags>, sms: Option<SmsFlags>) -> bool {
    let acts = sms_actions_of(sms);
    let sms_side = acts
        .iter()
        .any(|a| matches!(a, SmsAction::Pin | SmsAction::Unpin))
        .then(|| acts.contains(&SmsAction::Unpin));
    wa.map(|w| w.pinned).into_iter().chain(sms_side).all(|p| p)
}

/// Whether every thread that can be archived already is.
pub fn archived_all(wa: Option<WaFlags>, sms: Option<SmsFlags>) -> bool {
    let acts = sms_actions_of(sms);
    let sms_side = acts
        .iter()
        .any(|a| matches!(a, SmsAction::Archive | SmsAction::Unarchive))
        .then(|| acts.contains(&SmsAction::Unarchive));
    wa.map(|w| w.archived)
        .into_iter()
        .chain(sms_side)
        .all(|p| p)
}

/// Whether every thread is muted (then the item is "Unmute").
pub fn muted_all(wa: Option<WaFlags>, sms: Option<SmsFlags>) -> bool {
    let acts = sms_actions_of(sms);
    let sms_side = acts
        .iter()
        .any(|a| matches!(a, SmsAction::Mute | SmsAction::Unmute))
        .then(|| acts.contains(&SmsAction::Unmute));
    wa.map(|w| w.muted).into_iter().chain(sms_side).all(|p| p)
}

/// Whether any thread is unread (then the item is "Mark as read").
pub fn unread_any(wa: Option<WaFlags>, sms: Option<SmsFlags>) -> bool {
    wa.is_some_and(|w| w.unread) || sms_actions_of(sms).contains(&SmsAction::MarkRead)
}

/// Whether the SMS side offers any of `wanted`.
pub fn sms_offers(sms: Option<SmsFlags>, wanted: &[SmsAction]) -> bool {
    has_sms(&sms_actions_of(sms), wanted)
}

fn has_sms(acts: &[SmsAction], wanted: &[SmsAction]) -> bool {
    acts.iter().any(|a| wanted.contains(a))
}

/// The menu of a row (or an open conversation) with these threads.
pub fn row_menu_items(wa: Option<WaFlags>, sms: Option<SmsFlags>, extras: &Extras) -> MenuModel {
    let acts = sms_actions_of(sms);
    let mut top = Vec::new();
    if extras.select {
        top.push(MenuItem::new("select", "Select").icon("circle-check"));
    }
    if let Some(page) = &extras.page {
        top.extend([
            MenuItem::new("find", "Search in chat").icon("search"),
            MenuItem::new("date", "Jump to date").icon("calendar-search"),
            MenuItem::new("gallery", "Media, links and docs").icon("image"),
            MenuItem::new("starred", page.starred.clone()).icon("star"),
            MenuItem::new("send_later", "Send later").icon("alarm-clock"),
            MenuItem::new("export", "Export chat").icon("download"),
            MenuItem::new("share", "Share as text").icon("share"),
        ]);
    }

    let mut org = Vec::new();
    if wa.is_some() || has_sms(&acts, &[SmsAction::Pin, SmsAction::Unpin]) {
        org.push(if pinned_all(wa, sms) {
            MenuItem::new("pin", "Unpin").icon("pin-off")
        } else {
            MenuItem::new("pin", "Pin").icon("pin")
        });
    }
    if wa.is_some() || has_sms(&acts, &[SmsAction::MarkRead, SmsAction::MarkUnread]) {
        org.push(if unread_any(wa, sms) {
            MenuItem::new("read", "Mark as read").icon("mail-open")
        } else {
            MenuItem::new("read", "Mark as unread").icon("mail")
        });
    }
    if wa.is_some() || has_sms(&acts, &[SmsAction::Mute, SmsAction::Unmute]) {
        org.push(if muted_all(wa, sms) {
            MenuItem::new("unmute", "Unmute").icon("bell")
        } else {
            MenuItem::new("mute", "Mute\u{2026}")
                .icon("bell-off")
                .submenu()
        });
    }
    if wa.is_some() || has_sms(&acts, &[SmsAction::Archive, SmsAction::Unarchive]) {
        org.push(if archived_all(wa, sms) {
            MenuItem::new("archive", "Unarchive").icon("archive-restore")
        } else {
            MenuItem::new("archive", "Archive").icon("archive")
        });
    }
    if acts.contains(&SmsAction::NotSpam) {
        org.push(MenuItem::new("not_spam", "Not spam").icon("shield-check"));
    }
    if acts.contains(&SmsAction::MoveToSpam) {
        org.push(MenuItem::new("spam", "Move to Spam").icon("shield-alert"));
    }
    let wa_number = extras.page.as_ref().is_some_and(|p| p.wa_number);
    if wa_number || acts.contains(&SmsAction::Call) {
        org.push(MenuItem::new("call", "Call").icon("phone"));
    }
    if wa_number {
        let known = extras.page.as_ref().is_some_and(|p| p.known_contact);
        org.push(if known {
            MenuItem::new("contact", "Open contact").icon("user")
        } else {
            MenuItem::new("contact", "Add to contacts").icon("user-plus")
        });
    } else if acts.contains(&SmsAction::OpenContact) {
        org.push(MenuItem::new("contact", "Open contact").icon("user"));
    } else if acts.contains(&SmsAction::AddContact) {
        org.push(MenuItem::new("contact", "Add to contacts").icon("user-plus"));
    }

    let mut danger = Vec::new();
    if acts.contains(&SmsAction::Unblock) {
        danger.push(MenuItem::new("unblock", "Unblock").icon("circle-check"));
    } else if acts.contains(&SmsAction::Block) {
        danger.push(MenuItem::new("block", "Block").icon("ban"));
    }
    if acts.contains(&SmsAction::Delete) {
        danger.push(
            MenuItem::new("delete", "Delete conversation")
                .icon("trash-2")
                .destructive(),
        );
    }
    MenuModel::dedup_by_key(vec![
        MenuGroup::new(top),
        MenuGroup::new(org),
        MenuGroup::new(danger),
    ])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn keys(m: &MenuModel) -> Vec<&'static str> {
        m.groups()
            .iter()
            .flat_map(|g| g.0.iter().map(|i| i.key))
            .collect()
    }

    fn item<'a>(m: &'a MenuModel, key: &str) -> &'a MenuItem {
        m.find(key).expect("the menu has the item")
    }

    fn row() -> Extras {
        Extras {
            select: true,
            page: None,
        }
    }

    fn sms(ctx: SmsActionContext) -> Option<SmsFlags> {
        Some(SmsFlags {
            ctx,
            new_thread: false,
        })
    }

    fn number_sender() -> SmsActionContext {
        SmsActionContext {
            single_sender: true,
            sender_is_number: true,
            ..SmsActionContext::default()
        }
    }

    fn assert_unique(m: &MenuModel) {
        let all = keys(m);
        let set: std::collections::HashSet<_> = all.iter().collect();
        assert_eq!(all.len(), set.len(), "duplicate keys in {all:?}");
    }

    #[test]
    fn whatsapp_only_row() {
        let m = row_menu_items(Some(WaFlags::default()), None, &row());
        assert_eq!(
            keys(&m),
            vec!["select", "pin", "read", "mute", "archive"],
            "no Block or Delete for a WhatsApp chat"
        );
        assert_eq!(m.group_sizes(), vec![1, 4]);
        assert!(item(&m, "mute").submenu);
    }

    #[test]
    fn whatsapp_states_flip_the_labels() {
        let wa = WaFlags {
            pinned: true,
            archived: true,
            muted: true,
            unread: true,
        };
        let m = row_menu_items(Some(wa), None, &row());
        assert_eq!(keys(&m), vec!["select", "pin", "read", "unmute", "archive"]);
        assert_eq!(item(&m, "pin").label.as_str(), "Unpin");
        assert_eq!(item(&m, "read").label.as_str(), "Mark as read");
        assert_eq!(item(&m, "archive").label.as_str(), "Unarchive");
    }

    #[test]
    fn sms_only_row_with_a_number_has_block_and_delete_last() {
        let m = row_menu_items(None, sms(number_sender()), &row());
        assert_eq!(
            keys(&m),
            vec![
                "select", "pin", "read", "mute", "archive", "block", "delete"
            ]
        );
        assert_eq!(m.group_sizes(), vec![1, 4, 2]);
        let delete = item(&m, "delete");
        assert_eq!(delete.role, rovsti_ui::menu::model::MenuRole::Destructive);
        // The destructive item is the very last one.
        assert_eq!(keys(&m).last(), Some(&"delete"));
    }

    #[test]
    fn alphanumeric_sender_cannot_be_blocked() {
        let ctx = SmsActionContext {
            single_sender: true,
            sender_is_number: false,
            ..SmsActionContext::default()
        };
        let m = row_menu_items(None, sms(ctx), &row());
        assert!(m.find("block").is_none());
        assert!(m.find("delete").is_some());
    }

    #[test]
    fn merged_row_lists_each_action_once() {
        let m = row_menu_items(Some(WaFlags::default()), sms(number_sender()), &row());
        assert_unique(&m);
        assert_eq!(
            keys(&m),
            vec![
                "select", "pin", "read", "mute", "archive", "block", "delete"
            ]
        );
    }

    #[test]
    fn merged_row_follows_the_weakest_side() {
        // WhatsApp pinned and archived, SMS not: the item still offers the action.
        let wa = WaFlags {
            pinned: true,
            archived: true,
            muted: true,
            unread: false,
        };
        let m = row_menu_items(Some(wa), sms(number_sender()), &row());
        assert_eq!(item(&m, "pin").label.as_str(), "Pin");
        assert_eq!(item(&m, "archive").label.as_str(), "Archive");
        assert!(m.find("mute").is_some());
        // Both sides pinned, archived and muted: every item flips.
        let both = SmsActionContext {
            pinned: true,
            archived: true,
            muted: true,
            ..number_sender()
        };
        let m = row_menu_items(Some(wa), sms(both), &row());
        assert_eq!(item(&m, "pin").label.as_str(), "Unpin");
        assert_eq!(item(&m, "archive").label.as_str(), "Unarchive");
        assert!(m.find("unmute").is_some() && m.find("mute").is_none());
        assert_unique(&m);
    }

    #[test]
    fn merged_row_is_unread_when_either_side_is() {
        let unread_sms = SmsActionContext {
            unread: true,
            ..number_sender()
        };
        let m = row_menu_items(Some(WaFlags::default()), sms(unread_sms), &row());
        assert_eq!(item(&m, "read").label.as_str(), "Mark as read");
        let m = row_menu_items(Some(WaFlags::default()), sms(number_sender()), &row());
        assert_eq!(item(&m, "read").label.as_str(), "Mark as unread");
    }

    #[test]
    fn spam_row_offers_not_spam_and_no_pin_or_archive() {
        let ctx = SmsActionContext {
            in_spam: true,
            ..number_sender()
        };
        let m = row_menu_items(None, sms(ctx), &row());
        assert_eq!(
            keys(&m),
            vec!["select", "read", "mute", "not_spam", "block", "delete"]
        );
    }

    #[test]
    fn blocked_sender_offers_unblock_instead_of_block() {
        let ctx = SmsActionContext {
            blocked: true,
            ..number_sender()
        };
        let m = row_menu_items(None, sms(ctx), &row());
        assert!(m.find("unblock").is_some());
        assert!(m.find("block").is_none());
        assert_unique(&m);
    }

    #[test]
    fn can_be_spam_adds_move_to_spam_before_block() {
        let ctx = SmsActionContext {
            can_be_spam: true,
            ..number_sender()
        };
        let m = row_menu_items(None, sms(ctx), &row());
        assert_eq!(
            keys(&m),
            vec![
                "select", "pin", "read", "mute", "archive", "spam", "block", "delete"
            ]
        );
    }

    #[test]
    fn new_conversation_has_no_delete_archive_or_pin() {
        let flags = SmsFlags {
            ctx: number_sender(),
            new_thread: true,
        };
        let m = row_menu_items(None, Some(flags), &row());
        assert_eq!(keys(&m), vec!["select", "mute", "block"]);
    }

    #[test]
    fn page_menu_leads_with_the_page_actions() {
        let extras = Extras {
            select: false,
            page: Some(PageExtras {
                starred: "Starred messages (2)".into(),
                wa_number: true,
                known_contact: false,
            }),
        };
        let m = row_menu_items(Some(WaFlags::default()), None, &extras);
        assert_unique(&m);
        assert_eq!(m.groups()[0].0.len(), 7);
        assert_eq!(item(&m, "starred").label.as_str(), "Starred messages (2)");
        assert_eq!(item(&m, "contact").label.as_str(), "Add to contacts");
        assert!(m.find("call").is_some());
    }

    #[test]
    fn sms_page_menu_has_one_call_and_one_contact_item() {
        let ctx = SmsActionContext {
            on_page: true,
            ..number_sender()
        };
        let extras = Extras {
            select: false,
            page: Some(PageExtras {
                starred: "Starred messages".into(),
                wa_number: false,
                known_contact: false,
            }),
        };
        let m = row_menu_items(None, sms(ctx), &extras);
        assert_unique(&m);
        assert!(m.find("call").is_some() && m.find("contact").is_some());
        assert_eq!(keys(&m).last(), Some(&"delete"));
    }

    #[test]
    fn mute_keys_round_trip() {
        let m = mute_menu_items();
        assert_eq!(m.item_count(), MuteFor::ALL.len());
        for (key, choice) in MUTE_KEYS {
            assert_eq!(mute_for_key(key), Some(choice));
            assert!(m.find(key).is_some());
        }
        assert_eq!(mute_for_key("pin"), None);
    }
}
