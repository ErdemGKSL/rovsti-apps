//! Select mode in the Contacts list: caps, export ids, group changes, notices
//! and the plain-text form of a contact for the clipboard.

use std::collections::BTreeSet;

use crate::contact_dates;
use crate::contact_edit::{ContactDraft, SelectionChange};

/// Most contacts one selection may hold.
pub const MAX_SELECTED: usize = 500;

fn contacts(n: usize) -> String {
    if n == 1 {
        "1 contact".to_owned()
    } else {
        format!("{n} contacts")
    }
}

/// The selected ids in ascending order, without the contact whose delete is
/// still pending (`hidden`).
pub fn export_ids(selected: &BTreeSet<i64>, hidden: Option<i64>) -> Vec<i64> {
    selected
        .iter()
        .copied()
        .filter(|id| Some(*id) != hidden)
        .collect()
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
    if !d.org.trim().is_empty() {
        lines.push(d.org.trim().to_owned());
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
        assert_eq!(export_ids(&set, Some(5)), vec![2, 9]);
        assert_eq!(export_ids(&set, None), vec![2, 5, 9]);
        assert!(export_ids(&BTreeSet::new(), Some(1)).is_empty());
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
}
