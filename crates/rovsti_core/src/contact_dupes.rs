//! Finding duplicate contacts and merging them.
//!
//! Two contacts are linked when they share a phone number (last ten digits,
//! see [`crate::import_plan`]), share an email (case ignored), or have the
//! same name (case and accents ignored) while neither has a number. Links are
//! transitive, so A~B and B~C put all three in one group. Hashing keeps the
//! search linear in the number of contacts.

use std::collections::HashMap;

use crate::{
    contact_edit::{ContactDraft, DraftField},
    import_plan::number_key,
    search::fold,
};

/// Why contacts are grouped, weakest to strongest by [`DupReason::rank`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DupReason {
    SameNumber,
    SameEmail,
    SameName,
}

impl DupReason {
    pub fn label(self) -> &'static str {
        match self {
            Self::SameNumber => "Same number",
            Self::SameEmail => "Same email",
            Self::SameName => "Same name",
        }
    }

    fn rank(self) -> u8 {
        match self {
            Self::SameNumber => 3,
            Self::SameEmail => 2,
            Self::SameName => 1,
        }
    }
}

/// Contacts that look like the same person. The keeper is `ids[0]`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DupGroup {
    pub ids: Vec<i64>,
    pub reason: DupReason,
}

fn root(parent: &mut [usize], mut i: usize) -> usize {
    while parent[i] != i {
        parent[i] = parent[parent[i]];
        i = parent[i];
    }
    i
}

/// Links `i` to the first contact seen with `key`, recording the edge.
fn link<K: std::hash::Hash + Eq>(
    seen: &mut HashMap<K, usize>,
    key: K,
    i: usize,
    reason: DupReason,
    parent: &mut [usize],
    edges: &mut Vec<(usize, DupReason)>,
) {
    match seen.get(&key) {
        Some(&j) => {
            let (a, b) = (root(parent, i), root(parent, j));
            if a != b {
                parent[a] = b;
            }
            edges.push((i, reason));
        }
        None => {
            seen.insert(key, i);
        }
    }
}

/// Groups of likely duplicates among drafts that have an id, ordered by the
/// keeper's name. Singletons are dropped.
pub fn find(drafts: &[ContactDraft]) -> Vec<DupGroup> {
    let mut ids_seen = std::collections::HashSet::new();
    let items: Vec<&ContactDraft> = drafts
        .iter()
        .filter(|d| d.id.is_some_and(|id| ids_seen.insert(id)))
        .collect();
    let mut parent: Vec<usize> = (0..items.len()).collect();
    let mut edges = Vec::new();
    let mut by_number = HashMap::new();
    let mut by_email = HashMap::new();
    let mut by_name = HashMap::new();
    for (i, d) in items.iter().enumerate() {
        let mut has_number = false;
        for key in d.numbers.iter().filter_map(|n| number_key(&n.value)) {
            has_number = true;
            link(
                &mut by_number,
                key,
                i,
                DupReason::SameNumber,
                &mut parent,
                &mut edges,
            );
        }
        for e in &d.emails {
            let key = e.value.trim().to_lowercase();
            if !key.is_empty() {
                link(
                    &mut by_email,
                    key,
                    i,
                    DupReason::SameEmail,
                    &mut parent,
                    &mut edges,
                );
            }
        }
        let name = fold(d.display_name().trim());
        if !has_number && !name.is_empty() {
            link(
                &mut by_name,
                name,
                i,
                DupReason::SameName,
                &mut parent,
                &mut edges,
            );
        }
    }
    let mut members: HashMap<usize, Vec<usize>> = HashMap::new();
    for i in 0..items.len() {
        let r = root(&mut parent, i);
        members.entry(r).or_default().push(i);
    }
    let mut reasons: HashMap<usize, DupReason> = HashMap::new();
    for (i, reason) in edges {
        let r = root(&mut parent, i);
        let slot = reasons.entry(r).or_insert(reason);
        if reason.rank() > slot.rank() {
            *slot = reason;
        }
    }
    let mut out: Vec<(String, DupGroup)> = members
        .into_iter()
        .filter(|(_, m)| m.len() > 1)
        .filter_map(|(r, mut m)| {
            m.sort_by_key(|&i| {
                let d = items[i];
                (
                    std::cmp::Reverse(d.numbers.len() + d.emails.len()),
                    !d.starred,
                    d.id,
                )
            });
            let reason = *reasons.get(&r)?;
            let key = fold(&items[m[0]].display_name());
            let ids = m.iter().filter_map(|&i| items[i].id).collect();
            Some((key, DupGroup { ids, reason }))
        })
        .collect();
    out.sort_by(|a, b| (&a.0, a.1.ids[0]).cmp(&(&b.0, b.1.ids[0])));
    out.into_iter().map(|(_, g)| g).collect()
}

/// Appends the fields of `others` that `keeper` does not have yet, judged by
/// `key`. The first occurrence (kind and label included) wins.
fn union_fields(
    keeper: &mut Vec<DraftField>,
    others: &[&Vec<DraftField>],
    key: impl Fn(&str) -> Option<String>,
) {
    let mut seen: std::collections::HashSet<String> =
        keeper.iter().filter_map(|f| key(&f.value)).collect();
    for f in others.iter().flat_map(|v| v.iter()) {
        if key(&f.value).is_some_and(|k| seen.insert(k)) {
            keeper.push(f.clone());
        }
    }
}

/// One contact made of `keeper` and `others`; see the module docs for the
/// rules. The result is cleaned.
pub fn merge(keeper: &ContactDraft, others: &[ContactDraft]) -> ContactDraft {
    let mut out = keeper.clone();
    if out.given.trim().is_empty()
        && out.family.trim().is_empty()
        && let Some(o) = others
            .iter()
            .find(|o| !o.given.trim().is_empty() || !o.family.trim().is_empty())
    {
        out.given = o.given.clone();
        out.family = o.family.clone();
    }
    if out.org.trim().is_empty()
        && let Some(o) = others.iter().find(|o| !o.org.trim().is_empty())
    {
        out.org = o.org.clone();
    }
    if out.nickname.trim().is_empty()
        && let Some(o) = others.iter().find(|o| !o.nickname.trim().is_empty())
    {
        out.nickname = o.nickname.clone();
    }
    if out.title.trim().is_empty()
        && let Some(o) = others.iter().find(|o| !o.title.trim().is_empty())
    {
        out.title = o.title.clone();
    }
    for site in others.iter().flat_map(|o| &o.websites) {
        out.websites.push(site.clone());
    }
    let mut notes: Vec<String> = Vec::new();
    for note in std::iter::once(&keeper.note).chain(others.iter().map(|o| &o.note)) {
        let note = note.trim();
        if !note.is_empty() && !notes.iter().any(|n| n == note) {
            notes.push(note.to_owned());
        }
    }
    out.note = notes.join("\n\n");
    // New Vec fields of ContactDraft are merged here, in one place.
    union_fields(
        &mut out.numbers,
        &others.iter().map(|o| &o.numbers).collect::<Vec<_>>(),
        number_key,
    );
    union_fields(
        &mut out.emails,
        &others.iter().map(|o| &o.emails).collect::<Vec<_>>(),
        |v| Some(v.trim().to_lowercase()).filter(|k| !k.is_empty()),
    );
    union_fields(
        &mut out.addresses,
        &others.iter().map(|o| &o.addresses).collect::<Vec<_>>(),
        |v| Some(v.trim().to_lowercase()).filter(|k| !k.is_empty()),
    );
    for o in others {
        for e in &o.events {
            if !out
                .events
                .iter()
                .any(|x| x.date.trim() == e.date.trim() && x.kind == e.kind)
            {
                out.events.push(e.clone());
            }
        }
    }
    for o in others.iter().filter(|o| o.account == keeper.account) {
        out.groups.extend(o.groups.iter().copied());
    }
    out.starred = keeper.starred || others.iter().any(|o| o.starred);
    out.clean();
    out
}

/// The line above the duplicates list.
pub fn summary(groups: &[DupGroup]) -> String {
    match groups.len() {
        0 => "No duplicates found".to_owned(),
        1 => "1 possible duplicate".to_owned(),
        n => format!("{n} possible duplicates"),
    }
}

/// The toast after merging `n` contacts into one.
pub fn merged_notice(n: usize) -> String {
    format!("Merged {n} contacts")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contact_edit::FieldKind;

    fn c(id: i64, given: &str, nums: &[&str], mails: &[&str]) -> ContactDraft {
        ContactDraft {
            id: Some(id),
            given: given.into(),
            numbers: nums
                .iter()
                .map(|n| DraftField::new(*n, FieldKind::Mobile))
                .collect(),
            emails: mails
                .iter()
                .map(|m| DraftField::new(*m, FieldKind::Home))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn same_number_in_two_formats() {
        let g = find(&[
            c(1, "Anna", &["0555 111 22 33"], &[]),
            c(2, "A.", &["+90 555 111 22 33"], &[]),
        ]);
        assert_eq!(g.len(), 1);
        assert_eq!(g[0].reason, DupReason::SameNumber);
        assert_eq!(g[0].ids, vec![1, 2]);
    }

    #[test]
    fn email_case_ignored() {
        let g = find(&[
            c(1, "A", &[], &["Anna@X.com"]),
            c(2, "B", &["1"], &["anna@x.COM"]),
        ]);
        assert_eq!(g[0].reason, DupReason::SameEmail);
    }

    #[test]
    fn name_only_without_numbers() {
        let g = find(&[c(1, "Ayşe", &[], &[]), c(2, "ayse", &[], &[])]);
        assert_eq!(g[0].reason, DupReason::SameName);
        assert_eq!(g[0].reason.label(), "Same name");
    }

    #[test]
    fn name_with_different_numbers_not_grouped() {
        assert!(find(&[c(1, "Anna", &["111"], &[]), c(2, "Anna", &["222"], &[])]).is_empty());
        assert!(find(&[c(1, "Anna", &["111"], &[]), c(2, "Anna", &[], &[])]).is_empty());
    }

    #[test]
    fn transitive_chain_is_one_group_with_strongest_reason() {
        let g = find(&[
            c(1, "A", &["5551112233"], &[]),
            c(2, "B", &["5551112233"], &["x@y.z"]),
            c(3, "C", &[], &["X@y.z"]),
            c(4, "D", &[], &[]),
        ]);
        assert_eq!(g.len(), 1);
        assert_eq!(g[0].ids.len(), 3);
        assert_eq!(g[0].reason, DupReason::SameNumber);
    }

    #[test]
    fn keeper_has_most_fields_then_starred_then_lowest_id() {
        let mut starred = c(5, "A", &["5551112233"], &[]);
        starred.starred = true;
        let g = find(&[
            c(3, "A", &["5551112233"], &[]),
            starred,
            c(9, "A", &["5551112233"], &["a@b.c"]),
        ]);
        assert_eq!(g[0].ids, vec![9, 5, 3]);
    }

    #[test]
    fn empty_and_idless_input() {
        assert!(find(&[]).is_empty());
        let mut a = c(1, "A", &["5551112233"], &[]);
        let mut b = a.clone();
        a.id = None;
        b.id = None;
        assert!(find(&[a, b]).is_empty());
    }

    #[test]
    fn merge_unions_events_and_addresses() {
        use crate::contact_edit::{DraftEvent, EventKind};
        let mut k = c(1, "Keep", &[], &[]);
        k.events = vec![DraftEvent::new("1990-01-02", EventKind::Birthday)];
        k.addresses = vec![DraftField::new("1 Main St", FieldKind::Home)];
        let mut o = c(2, "Other", &[], &[]);
        o.events = vec![
            DraftEvent::new("1990-01-02", EventKind::Birthday),
            DraftEvent::new("--06-07", EventKind::Anniversary),
        ];
        o.addresses = vec![
            DraftField::new("1 main st", FieldKind::Work),
            DraftField::new("2 Side Rd", FieldKind::Work),
        ];
        let m = merge(&k, &[o]);
        assert_eq!(m.events.len(), 2);
        assert_eq!(m.addresses.len(), 2);
    }

    #[test]
    fn merge_keeps_nickname_title_and_unions_websites() {
        let mut k = c(1, "Keep", &[], &[]);
        k.nickname = "Kay".into();
        k.websites = vec!["a.com".into()];
        let mut o = c(2, "Other", &[], &[]);
        o.nickname = "Oh".into();
        o.title = "CTO".into();
        o.websites = vec!["A.com".into(), "b.com".into()];
        let m = merge(&k, &[o]);
        assert_eq!((m.nickname.as_str(), m.title.as_str()), ("Kay", "CTO"));
        assert_eq!(m.websites, vec!["a.com", "b.com"]);
    }

    #[test]
    fn merge_dedups_and_joins() {
        let mut k = c(1, "", &["0555 111 22 33"], &["a@b.c"]);
        k.note = "Hi".into();
        k.groups = vec![1];
        k.account = "acc".into();
        let mut o = c(
            2,
            "Anna",
            &["+90 555 111 22 33", "222"],
            &["A@B.c", "d@e.f"],
        );
        o.family = "Y".into();
        o.note = "Hi".into();
        let mut o2 = c(3, "Z", &[], &[]);
        o2.note = "Other".into();
        o2.groups = vec![2];
        o2.account = "acc".into();
        o2.starred = true;
        let mut foreign = c(4, "Z", &[], &[]);
        foreign.groups = vec![7];
        foreign.account = "other".into();
        let m = merge(&k, &[o, o2, foreign]);
        assert_eq!(m.id, Some(1));
        assert_eq!(m.account, "acc");
        assert_eq!((m.given.as_str(), m.family.as_str()), ("Anna", "Y"));
        assert_eq!(m.numbers.len(), 2);
        assert_eq!(m.numbers[0].value, "0555 111 22 33");
        assert_eq!(m.emails.len(), 2);
        assert_eq!(m.note, "Hi\n\nOther");
        assert_eq!(m.groups, vec![1, 2]);
        assert!(m.starred);
        assert!(m.validate().is_ok());
    }

    #[test]
    fn keeper_name_wins() {
        let m = merge(&c(1, "Keep", &[], &[]), &[c(2, "Other", &[], &[])]);
        assert_eq!(m.given, "Keep");
    }

    #[test]
    fn texts() {
        assert_eq!(summary(&[]), "No duplicates found");
        let g = DupGroup {
            ids: vec![1, 2],
            reason: DupReason::SameEmail,
        };
        assert_eq!(summary(std::slice::from_ref(&g)), "1 possible duplicate");
        assert_eq!(summary(&[g.clone(), g]), "2 possible duplicates");
        assert_eq!(merged_notice(3), "Merged 3 contacts");
        assert_eq!(DupReason::SameNumber.label(), "Same number");
        assert_eq!(DupReason::SameEmail.label(), "Same email");
    }
}
