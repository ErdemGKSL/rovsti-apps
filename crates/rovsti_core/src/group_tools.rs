//! Group utilities: who a group SMS or email goes to, and the .vcf file name.

use std::collections::HashSet;

use crate::{
    contact_edit::{ContactDraft, DraftField, FieldKind},
    import_plan::number_key,
};

pub const MAX_SMS: usize = 30;
pub const MAX_EMAIL: usize = 50;

/// What a recipients list is made of.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Sms,
    Email,
}

/// The entry to use: the default one, else the first mobile, else the first.
/// Blank values are ignored.
fn pick(fields: &[DraftField]) -> Option<&str> {
    let usable = || fields.iter().filter(|f| !f.value.trim().is_empty());
    usable()
        .find(|f| f.primary)
        .or_else(|| usable().find(|f| f.kind == FieldKind::Mobile))
        .or_else(|| usable().next())
        .map(|f| f.value.trim())
}

fn recipients(
    members: &[ContactDraft],
    cap: usize,
    list: impl Fn(&ContactDraft) -> &[DraftField],
    key: impl Fn(&str) -> Option<String>,
) -> (Vec<String>, usize) {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    let mut missing = 0;
    for member in members {
        let Some(value) = pick(list(member)) else {
            missing += 1;
            continue;
        };
        let Some(k) = key(value) else {
            missing += 1;
            continue;
        };
        if out.len() < cap && seen.insert(k) {
            out.push(value.to_owned());
        }
    }
    (out, missing)
}

/// Numbers for a group SMS and how many members have none. At most
/// [`MAX_SMS`], one per distinct number.
pub fn sms_recipients(members: &[ContactDraft]) -> (Vec<String>, usize) {
    recipients(members, MAX_SMS, |m| &m.numbers, number_key)
}

/// Addresses for a group email and how many members have none. At most
/// [`MAX_EMAIL`], one per distinct address (case-insensitive).
pub fn email_recipients(members: &[ContactDraft]) -> (Vec<String>, usize) {
    recipients(
        members,
        MAX_EMAIL,
        |m| &m.emails,
        |v| Some(v.to_lowercase()),
    )
}

/// `smsto:` recipients: ";"-joined.
pub fn sms_address(numbers: &[String]) -> String {
    numbers
        .iter()
        .map(|n| n.trim())
        .filter(|n| !n.is_empty())
        .collect::<Vec<_>>()
        .join(";")
}

/// `mailto:` recipients: ","-joined.
pub fn email_address(addrs: &[String]) -> String {
    addrs
        .iter()
        .map(|a| a.trim())
        .filter(|a| !a.is_empty())
        .collect::<Vec<_>>()
        .join(",")
}

/// Pill text about members left out, or `None` when all are in.
pub fn recipients_notice(kind: Kind, used: usize, missing: usize, capped: bool) -> Option<String> {
    let (what, cap) = match kind {
        Kind::Sms => ("number", MAX_SMS),
        Kind::Email => ("email", MAX_EMAIL),
    };
    let mut parts = Vec::new();
    if missing > 0 {
        let verb = if missing == 1 { "has" } else { "have" };
        let people = if missing == 1 { "person" } else { "people" };
        parts.push(format!("{missing} {people} {verb} no {what}"));
    }
    if capped {
        parts.push(format!("Only the first {} are added", used.min(cap)));
    }
    (!parts.is_empty()).then(|| parts.join(". "))
}

/// "Family.vcf"; characters unsafe in file names become `_`. An empty title
/// falls back to the export name of a multi-contact file.
pub fn group_file_name(title: &str, today: (i32, u8, u8)) -> String {
    let clean: String = title
        .trim()
        .chars()
        .map(|c| {
            if c.is_control() || "/\\:*?\"<>|".contains(c) {
                '_'
            } else {
                c
            }
        })
        .collect();
    let clean = clean.trim_matches(|c: char| c == '.' || c == ' ' || c == '_');
    if clean.is_empty() {
        let (y, m, d) = today;
        return format!("rovsti-contacts-{y:04}-{m:02}-{d:02}.vcf");
    }
    let clean: String = clean.chars().take(60).collect();
    format!("{clean}.vcf")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn member(numbers: &[(&str, FieldKind)], emails: &[&str]) -> ContactDraft {
        ContactDraft {
            given: "X".to_owned(),
            numbers: numbers
                .iter()
                .map(|(v, k)| DraftField::new(*v, *k))
                .collect(),
            emails: emails
                .iter()
                .map(|v| DraftField::new(*v, FieldKind::Home))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn sms_prefers_the_first_mobile_else_the_first() {
        let a = member(
            &[
                ("111 000 1111", FieldKind::Home),
                ("555 000 2222", FieldKind::Mobile),
            ],
            &[],
        );
        let b = member(&[("333 000 3333", FieldKind::Work)], &[]);
        let (list, missing) = sms_recipients(&[a, b]);
        assert_eq!(list, vec!["555 000 2222", "333 000 3333"]);
        assert_eq!(missing, 0);
    }

    #[test]
    fn sms_dedups_by_number_key_and_counts_missing() {
        let a = member(&[("+90 555 111 2233", FieldKind::Mobile)], &[]);
        let b = member(&[("0555 111 2233", FieldKind::Mobile)], &[]);
        let none = member(&[], &[]);
        let blank = member(&[("  ", FieldKind::Mobile)], &[]);
        let (list, missing) = sms_recipients(&[a, b, none, blank]);
        assert_eq!(list, vec!["+90 555 111 2233"]);
        assert_eq!(missing, 2);
    }

    #[test]
    fn sms_is_capped() {
        let members: Vec<_> = (0..40)
            .map(|i| member(&[(&format!("555{i:07}"), FieldKind::Mobile)], &[]))
            .collect();
        let (list, missing) = sms_recipients(&members);
        assert_eq!(list.len(), MAX_SMS);
        assert_eq!(missing, 0);
    }

    #[test]
    fn email_dedups_and_caps() {
        let a = member(&[], &["A@b.com"]);
        let b = member(&[], &["a@B.com"]);
        let none = member(&[("555", FieldKind::Mobile)], &[]);
        let (list, missing) = email_recipients(&[a, b, none]);
        assert_eq!(list, vec!["A@b.com"]);
        assert_eq!(missing, 1);
        let many: Vec<_> = (0..60)
            .map(|i| member(&[], &[&format!("u{i}@x.com")]))
            .collect();
        assert_eq!(email_recipients(&many).0.len(), MAX_EMAIL);
    }

    #[test]
    fn joins() {
        let n = vec![" 1 ".to_owned(), "".to_owned(), "2".to_owned()];
        assert_eq!(sms_address(&n), "1;2");
        assert_eq!(
            email_address(&["a@x.com".to_owned(), "b@x.com".to_owned()]),
            "a@x.com,b@x.com"
        );
        assert_eq!(sms_address(&[]), "");
    }

    #[test]
    fn notices() {
        assert_eq!(recipients_notice(Kind::Sms, 5, 0, false), None);
        assert_eq!(
            recipients_notice(Kind::Sms, 5, 2, false).as_deref(),
            Some("2 people have no number")
        );
        assert_eq!(
            recipients_notice(Kind::Email, 5, 1, false).as_deref(),
            Some("1 person has no email")
        );
        assert_eq!(
            recipients_notice(Kind::Sms, 30, 0, true).as_deref(),
            Some("Only the first 30 are added")
        );
        assert_eq!(
            recipients_notice(Kind::Email, 50, 3, true).as_deref(),
            Some("3 people have no email. Only the first 50 are added")
        );
    }

    #[test]
    fn file_names_are_safe() {
        let today = (2026, 11, 7);
        assert_eq!(group_file_name("Family", today), "Family.vcf");
        assert_eq!(group_file_name(" A/B:C* ", today), "A_B_C.vcf");
        assert_eq!(group_file_name("../x", today), "x.vcf");
        assert_eq!(group_file_name("", today), "rovsti-contacts-2026-11-07.vcf");
        assert_eq!(
            group_file_name(" ?? ", today),
            "rovsti-contacts-2026-11-07.vcf"
        );
        assert_eq!(group_file_name(&"a".repeat(100), today).len(), 64);
    }
}
