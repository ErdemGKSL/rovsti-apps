//! Clean up page filters: contacts without a number, a name, a photo or a group.

use std::collections::HashSet;

use crate::contact_edit::ContactDraft;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Cleanup {
    NoNumber,
    NoName,
    NoPhoto,
    NoGroup,
}

impl Cleanup {
    pub const ALL: [Cleanup; 4] = [Self::NoNumber, Self::NoName, Self::NoPhoto, Self::NoGroup];

    pub fn label(self) -> &'static str {
        match self {
            Self::NoNumber => "No number",
            Self::NoName => "No name",
            Self::NoPhoto => "No photo",
            Self::NoGroup => "Not in a group",
        }
    }
}

/// Whether `d` belongs under filter `c`. "No name" means the list can only
/// show a number or an email (or nothing) for it.
pub fn matches(d: &ContactDraft, has_photo: bool, c: Cleanup) -> bool {
    match c {
        Cleanup::NoNumber => d.numbers.iter().all(|n| n.value.trim().is_empty()),
        Cleanup::NoName => {
            d.given.trim().is_empty() && d.family.trim().is_empty() && d.org.trim().is_empty()
        }
        Cleanup::NoPhoto => !has_photo,
        Cleanup::NoGroup => d.groups.is_empty(),
    }
}

/// Ids of the saved contacts under `c`, in the order of `drafts`.
pub fn ids(drafts: &[ContactDraft], photo_ids: &HashSet<i64>, c: Cleanup) -> Vec<i64> {
    drafts
        .iter()
        .filter_map(|d| {
            let id = d.id?;
            matches(d, photo_ids.contains(&id), c).then_some(id)
        })
        .collect()
}

/// Matches per filter, in [`Cleanup::ALL`] order.
pub fn counts(drafts: &[ContactDraft], photo_ids: &HashSet<i64>) -> [usize; 4] {
    Cleanup::ALL.map(|c| ids(drafts, photo_ids, c).len())
}

/// Shown when a filter has no matches.
pub fn empty_text(c: Cleanup) -> String {
    match c {
        Cleanup::NoNumber => "Every contact has a number",
        Cleanup::NoName => "Every contact has a name",
        Cleanup::NoPhoto => "Every contact has a photo",
        Cleanup::NoGroup => "Every contact is in a group",
    }
    .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contact_edit::{DraftField, FieldKind};

    fn full(id: Option<i64>) -> ContactDraft {
        ContactDraft {
            id,
            given: "Ayse".to_owned(),
            numbers: vec![DraftField::new("555", FieldKind::Mobile)],
            groups: vec![1],
            ..ContactDraft::default()
        }
    }

    #[test]
    fn each_filter_matches_its_gap() {
        let ok = full(Some(1));
        for c in Cleanup::ALL {
            assert!(!matches(&ok, true, c), "{c:?}");
        }
        let mut d = full(Some(1));
        d.numbers.clear();
        assert!(matches(&d, true, Cleanup::NoNumber));
        d.numbers = vec![DraftField::new("  ", FieldKind::Mobile)];
        assert!(matches(&d, true, Cleanup::NoNumber));
        assert!(matches(&ok, false, Cleanup::NoPhoto));
        let mut d = full(Some(1));
        d.groups.clear();
        assert!(matches(&d, true, Cleanup::NoGroup));
    }

    #[test]
    fn no_name_ignores_number_and_email_fallback() {
        let mut d = full(Some(1));
        d.given.clear();
        assert!(matches(&d, true, Cleanup::NoName));
        d.org = "Acme".to_owned();
        assert!(!matches(&d, true, Cleanup::NoName));
        d.org.clear();
        d.family = "  ".to_owned();
        assert!(matches(&d, true, Cleanup::NoName));
    }

    #[test]
    fn ids_skip_unsaved_and_keep_order() {
        let mut a = full(Some(7));
        a.groups.clear();
        let mut b = full(None);
        b.groups.clear();
        let mut c = full(Some(3));
        c.groups.clear();
        let d = full(Some(9));
        let drafts = [a, b, c, d];
        assert_eq!(ids(&drafts, &HashSet::new(), Cleanup::NoGroup), vec![7, 3]);
    }

    #[test]
    fn ids_use_the_photo_set() {
        let drafts = [full(Some(1)), full(Some(2))];
        let photos: HashSet<i64> = [1].into_iter().collect();
        assert_eq!(ids(&drafts, &photos, Cleanup::NoPhoto), vec![2]);
    }

    #[test]
    fn counts_follow_all_order() {
        let mut nameless = full(Some(2));
        nameless.given.clear();
        nameless.numbers.clear();
        let drafts = [full(Some(1)), nameless];
        let photos: HashSet<i64> = [1, 2].into_iter().collect();
        assert_eq!(counts(&drafts, &photos), [1, 1, 0, 0]);
        assert_eq!(counts(&[], &photos), [0; 4]);
    }

    #[test]
    fn labels_and_empty_texts() {
        assert_eq!(Cleanup::NoGroup.label(), "Not in a group");
        assert_eq!(Cleanup::NoPhoto.label(), "No photo");
        assert_eq!(empty_text(Cleanup::NoNumber), "Every contact has a number");
        for c in Cleanup::ALL {
            assert!(!empty_text(c).is_empty());
        }
    }
}
