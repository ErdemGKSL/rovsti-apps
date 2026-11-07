//! Select mode in the Contacts list: caps, export ids, group changes, notices
//! and the plain-text form of a contact for the clipboard.

use std::collections::BTreeSet;

use crate::contact_dates;
use crate::contact_edit::{ContactDraft, Group, SelectionChange};

/// Most contacts one selection may hold.
pub const MAX_SELECTED: usize = 500;

fn contacts(n: usize) -> String {
    if n == 1 {
        "1 contact".to_owned()
    } else {
        format!("{n} contacts")
    }
}

/// The selected ids in ascending order, without the contacts whose delete is
/// still pending (`hidden`).
pub fn export_ids(selected: &BTreeSet<i64>, hidden: &[i64]) -> Vec<i64> {
    selected
        .iter()
        .copied()
        .filter(|id| !hidden.contains(id))
        .collect()
}

/// What the select bar can do with the selection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SelectAction {
    Share,
    SaveToFile,
    AddToGroup,
    /// Stars or unstars, as [`crate::multi_select::star_all`] decides.
    Star,
    Delete,
}

/// The bulk actions to offer for `n` selected contacts: none while nothing is
/// selected (the bar then only has Cancel and Select all), and no Add to group
/// when the phone has no groups.
pub fn select_bar_actions(n: usize, has_groups: bool) -> Vec<SelectAction> {
    if n == 0 {
        return Vec::new();
    }
    let mut actions = vec![SelectAction::Share, SelectAction::SaveToFile];
    if has_groups {
        actions.push(SelectAction::AddToGroup);
    }
    actions.push(SelectAction::Star);
    actions.push(SelectAction::Delete);
    actions
}

/// The header title of select mode.
pub fn title(n: usize) -> String {
    if n == 0 {
        "Select contacts".to_owned()
    } else {
        format!("{n} selected")
    }
}

/// Shown when the selection hit its cap.
pub fn cap_notice() -> String {
    format!(
        "You can select up to {} contacts",
        crate::multi_select::MAX_SELECTED
    )
}

/// "Starred 3 contacts" / "Unstarred 1 contact".
pub fn starred_notice(n: usize, star: bool) -> String {
    let verb = if star { "Starred" } else { "Unstarred" };
    format!("{verb} {}", contacts(n))
}

/// The pill after a delete: the Undo button rides on it.
pub fn deleted_notice(n: usize) -> String {
    if n == 1 {
        "Contact deleted.".to_owned()
    } else {
        format!("{n} contacts deleted.")
    }
}

/// A group the selection can join, with the selected contacts that belong to
/// the group's account.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GroupTarget {
    pub group_id: i64,
    pub title: String,
    pub account: String,
    pub eligible: Vec<i64>,
    /// Selected contacts of other accounts, left out.
    pub skipped: usize,
}

/// The groups that fit at least one of `selected` (id, account), in the order
/// of `groups`.
pub fn group_targets(groups: &[Group], selected: &[(i64, String)]) -> Vec<GroupTarget> {
    groups
        .iter()
        .filter_map(|g| {
            let eligible: Vec<i64> = selected
                .iter()
                .filter(|(_, account)| *account == g.account)
                .map(|(id, _)| *id)
                .collect();
            (!eligible.is_empty()).then(|| GroupTarget {
                group_id: g.id,
                title: g.title.clone(),
                account: g.account.clone(),
                skipped: selected.len() - eligible.len(),
                eligible,
            })
        })
        .collect()
}

/// [`added_notice`] plus how many contacts were left out for being in another
/// account.
pub fn added_with_skipped(n: usize, group_title: &str, skipped: usize) -> String {
    let base = added_notice(n, group_title);
    if skipped == 0 {
        base
    } else {
        format!("{base}. {skipped} skipped: other account")
    }
}

/// The drafts whose id is in `ids`, in the order of `all`.
pub fn pick_drafts(all: &[ContactDraft], ids: &[i64]) -> Vec<ContactDraft> {
    all.iter()
        .filter(|d| d.id.is_some_and(|id| ids.contains(&id)))
        .cloned()
        .collect()
}

/// Adds only the selected contacts that are not members yet.
pub fn add_to_group_change(group_members: &[i64], selected: &[i64]) -> SelectionChange {
    let mut add: Vec<i64> = Vec::new();
    for id in selected {
        if !group_members.contains(id) && !add.contains(id) {
            add.push(*id);
        }
    }
    SelectionChange {
        add,
        remove: Vec::new(),
    }
}

/// "Added 3 contacts to Family" or "They are all in Family already" (for `n`
/// contacts actually added).
pub fn added_notice(n: usize, group_title: &str) -> String {
    if n == 0 {
        format!("They are all in {group_title} already")
    } else {
        format!("Added {} to {group_title}", contacts(n))
    }
}

/// "Saved 4 contacts to the file".
pub fn exported_notice(n: usize) -> String {
    format!("Saved {} to the file", contacts(n))
}

/// File name for exporting `n` selected contacts, same shape as
/// [`crate::vcard::export_name`]: several get
/// `rovsti-contacts-<n>-YYYY-MM-DD.vcf`, one gets `contact.vcf` (the caller
/// names a lone contact after itself).
pub fn selection_file_name(n: usize, today: (i32, u8, u8)) -> String {
    if n <= 1 {
        "contact.vcf".to_owned()
    } else {
        format!(
            "rovsti-contacts-{n}-{:04}-{:02}-{:02}.vcf",
            today.0, today.1, today.2
        )
    }
}

/// Title and body of the delete confirmation.
pub fn delete_confirm(n: usize) -> (String, String) {
    let title = if n == 1 {
        "Delete this contact?".to_owned()
    } else {
        format!("Delete {n} contacts?")
    };
    let body = if n == 1 {
        "It is removed from the phone and from its accounts."
    } else {
        "They are removed from the phone and from their accounts."
    };
    (title, body.to_owned())
}

/// The whole contact as plain text for the clipboard. Empty parts are skipped.
pub fn full_text(d: &ContactDraft) -> String {
    let mut lines: Vec<String> = Vec::new();
    let name = [d.given.trim(), d.family.trim()]
        .iter()
        .filter(|p| !p.is_empty())
        .copied()
        .collect::<Vec<_>>()
        .join(" ");
    if !name.is_empty() {
        lines.push(name);
    }
    let nick = d.nickname.trim();
    if !nick.is_empty() {
        lines.push(format!("\"{nick}\""));
    }
    let org = [d.title.trim(), d.org.trim()]
        .iter()
        .filter(|p| !p.is_empty())
        .copied()
        .collect::<Vec<_>>()
        .join(", ");
    if !org.is_empty() {
        lines.push(org);
    }
    let fields = |lines: &mut Vec<String>, list: &[crate::contact_edit::DraftField], one_line| {
        for f in list {
            let value = f.value.trim();
            if value.is_empty() {
                continue;
            }
            let value = if one_line {
                value
                    .lines()
                    .map(str::trim)
                    .filter(|l| !l.is_empty())
                    .collect::<Vec<_>>()
                    .join(", ")
            } else {
                value.to_owned()
            };
            lines.push(format!("{}: {value}", f.title()));
        }
    };
    fields(&mut lines, &d.numbers, false);
    fields(&mut lines, &d.emails, false);
    fields(&mut lines, &d.addresses, true);
    for site in d
        .websites
        .iter()
        .map(|w| w.trim())
        .filter(|w| !w.is_empty())
    {
        lines.push(format!("Website: {site}"));
    }
    for e in &d.events {
        let when = match contact_dates::parse(&e.date) {
            Some(date) => date.text(),
            None if e.date.trim().is_empty() => continue,
            None => e.date.trim().to_owned(),
        };
        lines.push(format!("{}: {when}", e.title()));
    }
    if !d.note.trim().is_empty() {
        lines.push(d.note.trim().to_owned());
    }
    lines.join("\n")
}

pub fn copied_contact_notice() -> &'static str {
    "Contact copied"
}

pub fn copied_vcard_notice() -> &'static str {
    "vCard copied"
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contact_edit::{DraftEvent, DraftField, EventKind, FieldKind};

    fn d(id: i64, given: &str) -> ContactDraft {
        ContactDraft {
            id: Some(id),
            given: given.into(),
            ..Default::default()
        }
    }

    #[test]
    fn export_ids_sorts_and_drops_hidden() {
        let set: BTreeSet<i64> = [9, 2, 5].into();
        assert_eq!(export_ids(&set, &[5]), vec![2, 9]);
        assert_eq!(export_ids(&set, &[]), vec![2, 5, 9]);
        assert_eq!(export_ids(&set, &[9, 2, 77]), vec![5]);
        assert!(export_ids(&BTreeSet::new(), &[1]).is_empty());
    }

    #[test]
    fn pick_drafts_keeps_order_of_all() {
        let all = [d(1, "A"), d(2, "B"), d(3, "C"), ContactDraft::default()];
        let got = pick_drafts(&all, &[3, 1, 99]);
        let ids: Vec<_> = got.iter().map(|x| x.id).collect();
        assert_eq!(ids, vec![Some(1), Some(3)]);
    }

    #[test]
    fn group_change_adds_only_non_members() {
        let c = add_to_group_change(&[1, 2], &[2, 3, 4, 3]);
        assert_eq!(c.add, vec![3, 4]);
        assert!(c.remove.is_empty());
        assert!(add_to_group_change(&[1], &[]).is_empty());
        assert!(add_to_group_change(&[1, 2], &[1, 2]).is_empty());
    }

    #[test]
    fn notices_are_singular_and_plural() {
        assert_eq!(added_notice(3, "Family"), "Added 3 contacts to Family");
        assert_eq!(added_notice(1, "Family"), "Added 1 contact to Family");
        assert_eq!(added_notice(0, "Family"), "They are all in Family already");
        assert_eq!(exported_notice(1), "Saved 1 contact to the file");
        assert_eq!(exported_notice(4), "Saved 4 contacts to the file");
        assert_eq!(copied_contact_notice(), "Contact copied");
        assert_eq!(copied_vcard_notice(), "vCard copied");
    }

    #[test]
    fn file_names_and_confirm() {
        assert_eq!(
            selection_file_name(4, (2026, 5, 7)),
            "rovsti-contacts-4-2026-05-07.vcf"
        );
        let drafts = [d(1, "A"), d(2, "B")];
        assert_eq!(
            selection_file_name(2, (2026, 5, 7)),
            crate::vcard::export_name(&drafts, (2026, 5, 7))
        );
        assert_eq!(selection_file_name(1, (2026, 5, 7)), "contact.vcf");
        let (t, b) = delete_confirm(3);
        assert_eq!(t, "Delete 3 contacts?");
        assert_eq!(
            b,
            "They are removed from the phone and from their accounts."
        );
        assert_eq!(delete_confirm(1).0, "Delete this contact?");
    }

    #[test]
    fn full_text_orders_fields_and_skips_empties() {
        let mut c = d(1, "Ayşe");
        c.family = "Yılmaz".into();
        c.org = "Acme".into();
        c.numbers = vec![
            DraftField::new("+90 555 111 22 33", FieldKind::Mobile),
            DraftField::new("  ", FieldKind::Home),
        ];
        c.emails = vec![DraftField::new("a@b.com", FieldKind::Work)];
        c.addresses = vec![DraftField::new(
            "1 Main St\nIstanbul\n\nTR",
            FieldKind::Home,
        )];
        c.events = vec![
            DraftEvent::new("1994-05-12", EventKind::Birthday),
            DraftEvent::new("", EventKind::Anniversary),
        ];
        c.note = " Met at work ".into();
        assert_eq!(
            full_text(&c),
            "Ayşe Yılmaz\nAcme\nMobile: +90 555 111 22 33\nWork: a@b.com\n\
             Home: 1 Main St, Istanbul, TR\nBirthday: 12 May 1994\nMet at work"
        );
        assert_eq!(full_text(&ContactDraft::default()), "");
    }

    #[test]
    fn full_text_has_nickname_title_and_websites() {
        let mut c = d(1, "Ayşe");
        c.nickname = "Ayo".into();
        c.title = "CTO".into();
        c.org = "Acme".into();
        c.websites = vec!["acme.com".into(), " ".into()];
        assert_eq!(full_text(&c), "Ayşe\n\"Ayo\"\nCTO, Acme\nWebsite: acme.com");
        c.org.clear();
        assert_eq!(full_text(&c), "Ayşe\n\"Ayo\"\nCTO\nWebsite: acme.com");
    }

    #[test]
    fn the_bar_offers_nothing_for_an_empty_selection() {
        assert!(select_bar_actions(0, true).is_empty());
    }

    #[test]
    fn add_to_group_needs_a_group() {
        assert!(select_bar_actions(2, true).contains(&SelectAction::AddToGroup));
        let without = select_bar_actions(2, false);
        assert_eq!(
            without,
            vec![
                SelectAction::Share,
                SelectAction::SaveToFile,
                SelectAction::Star,
                SelectAction::Delete
            ]
        );
    }

    #[test]
    fn select_texts_count_contacts() {
        assert_eq!(title(0), "Select contacts");
        assert_eq!(title(3), "3 selected");
        assert_eq!(starred_notice(1, true), "Starred 1 contact");
        assert_eq!(starred_notice(4, false), "Unstarred 4 contacts");
        assert_eq!(deleted_notice(1), "Contact deleted.");
        assert_eq!(deleted_notice(3), "3 contacts deleted.");
        assert!(cap_notice().contains("200"));
    }

    #[test]
    fn group_targets_split_by_account() {
        let g = |id, title: &str, account: &str| Group {
            id,
            title: title.into(),
            account: account.into(),
            ..Group::default()
        };
        let groups = [g(1, "Family", "a"), g(2, "Work", "b"), g(3, "Empty", "c")];
        let selected = [
            (10, "a".to_owned()),
            (11, "b".to_owned()),
            (12, "a".to_owned()),
        ];
        let targets = group_targets(&groups, &selected);
        assert_eq!(targets.len(), 2);
        assert_eq!(targets[0].eligible, vec![10, 12]);
        assert_eq!(targets[0].skipped, 1);
        assert_eq!(targets[1].eligible, vec![11]);
        assert_eq!(targets[1].skipped, 2);
        assert_eq!(
            added_with_skipped(2, "Family", 1),
            "Added 2 contacts to Family. 1 skipped: other account"
        );
        assert_eq!(added_with_skipped(1, "Work", 0), "Added 1 contact to Work");
    }
}
