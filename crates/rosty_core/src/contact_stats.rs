//! Summary of the whole address book: counts per kind and per account.

use crate::contact_edit::{ContactDraft, EventKind, account_label};
use std::collections::HashMap;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Summary {
    pub total: usize,
    pub with_number: usize,
    pub with_email: usize,
    pub with_birthday: usize,
    pub starred: usize,
    /// Account key and contact count, most contacts first, then by key.
    pub accounts: Vec<(String, usize)>,
}

pub fn summarize(drafts: &[ContactDraft]) -> Summary {
    let mut s = Summary {
        total: drafts.len(),
        ..Default::default()
    };
    let mut by_account: HashMap<&str, usize> = HashMap::new();
    for d in drafts {
        s.with_number += d.numbers.iter().any(|n| !n.value.trim().is_empty()) as usize;
        s.with_email += d.emails.iter().any(|n| !n.value.trim().is_empty()) as usize;
        s.with_birthday += d.events.iter().any(|e| e.kind == EventKind::Birthday) as usize;
        s.starred += d.starred as usize;
        *by_account.entry(d.account.as_str()).or_default() += 1;
    }
    s.accounts = by_account
        .into_iter()
        .map(|(k, n)| (k.to_owned(), n))
        .collect();
    s.accounts
        .sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    s
}

/// "1 contact", "12 contacts".
pub fn total_text(n: usize) -> String {
    if n == 1 {
        "1 contact".to_owned()
    } else {
        format!("{n} contacts")
    }
}

/// Label and value rows for the summary sheet.
pub fn rows(s: &Summary) -> Vec<(String, String)> {
    let mut out = vec![
        ("Total".to_owned(), total_text(s.total)),
        ("With a number".to_owned(), s.with_number.to_string()),
        ("With an email".to_owned(), s.with_email.to_string()),
        ("With a birthday".to_owned(), s.with_birthday.to_string()),
        ("Favorites".to_owned(), s.starred.to_string()),
    ];
    for (key, n) in &s.accounts {
        out.push((account_label(key), n.to_string()));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contact_edit::{DraftEvent, DraftField};

    fn field(v: &str) -> DraftField {
        DraftField {
            value: v.to_owned(),
            ..Default::default()
        }
    }

    fn draft(account: &str) -> ContactDraft {
        ContactDraft {
            account: account.to_owned(),
            ..Default::default()
        }
    }

    #[test]
    fn counts() {
        let mut a = draft("");
        a.numbers.push(field("0555"));
        a.starred = true;
        let mut b = draft("com.google|a@b.com");
        b.emails.push(field("a@b.com"));
        b.numbers.push(field("  "));
        b.events
            .push(DraftEvent::new("1990-01-02", EventKind::Birthday));
        let mut c = draft("");
        c.events
            .push(DraftEvent::new("2010-01-02", EventKind::Anniversary));
        let s = summarize(&[a, b, c]);
        assert_eq!(
            (
                s.total,
                s.with_number,
                s.with_email,
                s.with_birthday,
                s.starred
            ),
            (3, 1, 1, 1, 1)
        );
    }

    #[test]
    fn accounts_sorted_by_count_then_key() {
        let s = summarize(&[draft("b"), draft("a"), draft("c"), draft("c"), draft("")]);
        assert_eq!(
            s.accounts,
            [
                ("c".into(), 2),
                ("".into(), 1),
                ("a".into(), 1),
                ("b".into(), 1)
            ]
        );
    }

    #[test]
    fn empty_summary() {
        let s = summarize(&[]);
        assert_eq!(s, Summary::default());
        assert_eq!(rows(&s).len(), 5);
    }

    #[test]
    fn texts() {
        assert_eq!(total_text(0), "0 contacts");
        assert_eq!(total_text(1), "1 contact");
        assert_eq!(total_text(2), "2 contacts");
    }

    #[test]
    fn rows_label_accounts() {
        let s = summarize(&[draft(""), draft("com.google|a@b.com")]);
        let r = rows(&s);
        assert_eq!(r[0], ("Total".into(), "2 contacts".into()));
        assert!(r.contains(&("On this phone".into(), "1".into())));
        assert!(r.contains(&("Google, a@b.com".into(), "1".into())));
    }
}
