//! Creating and editing contacts in the app (Rovsti Contacts).
//!
//! A [`ContactDraft`] is the whole editable contact: structured name,
//! organisation, numbers, emails, note and group membership. The same value is
//! what Kotlin returns for "open this contact", what the editor changes, what
//! the vCard module reads and writes, and what goes back to Kotlin to save.
//! Everything here is pure: cleaning, validation and the user-facing messages.
//!
//! A draft needs a name or an organisation to be saved; phone numbers are
//! optional, so a contact can be just an email address or a name. Numbers and
//! emails that are present must be well formed.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Longest name part, organisation or label, in characters.
pub const MAX_TEXT_CHARS: usize = 120;
/// Longest phone number or email address, in characters.
pub const MAX_VALUE_CHARS: usize = 100;
/// Longest note, in characters.
pub const MAX_NOTE_CHARS: usize = 2000;
/// Most numbers and most emails one contact keeps.
pub const MAX_FIELDS: usize = 12;

/// The kind of a number or an email, as the system labels them.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FieldKind {
    Mobile,
    Home,
    Work,
    /// A label the owner typed; the text is in [`DraftField::label`].
    Custom,
    #[default]
    #[serde(other)]
    Other,
}

impl FieldKind {
    /// The kinds the editor offers for a number, in cycling order.
    pub const NUMBER_KINDS: [Self; 3] = [Self::Mobile, Self::Home, Self::Work];
    /// The kinds the editor offers for an email, in cycling order.
    pub const EMAIL_KINDS: [Self; 3] = [Self::Home, Self::Work, Self::Other];

    /// What the editor shows on the kind button.
    pub fn title(self) -> &'static str {
        match self {
            Self::Mobile => "Mobile",
            Self::Home => "Home",
            Self::Work => "Work",
            Self::Custom | Self::Other => "Other",
        }
    }

    /// The next kind in `kinds` after `self` (the first when `self` is not in it).
    pub fn next_in(self, kinds: &[Self]) -> Self {
        let next = kinds
            .iter()
            .position(|k| *k == self)
            .map_or(0, |i| (i + 1) % kinds.len());
        kinds.get(next).copied().unwrap_or_default()
    }
}

/// One number or email of a draft.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DraftField {
    pub value: String,
    pub kind: FieldKind,
    /// The owner's own label, used when `kind` is [`FieldKind::Custom`].
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
}

impl DraftField {
    pub fn new(value: impl Into<String>, kind: FieldKind) -> Self {
        Self {
            value: value.into(),
            kind,
            label: None,
        }
    }

    /// The label shown next to the value.
    pub fn title(&self) -> String {
        match (&self.kind, self.label.as_deref().map(str::trim)) {
            (FieldKind::Custom, Some(label)) if !label.is_empty() => label.to_owned(),
            (kind, _) => kind.title().to_owned(),
        }
    }
}

/// A contact being created, edited, imported or exported.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ContactDraft {
    /// The contact id; `None` for a contact that does not exist yet.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<i64>,
    pub given: String,
    pub family: String,
    pub org: String,
    pub note: String,
    pub numbers: Vec<DraftField>,
    pub emails: Vec<DraftField>,
    /// Ids of the groups the contact belongs to.
    pub groups: Vec<i64>,
    pub starred: bool,
    /// The account that owns the contact, as Kotlin names it (opaque; empty
    /// for a contact stored only on the phone). A contact can only join the
    /// groups of its own account.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub account: String,
}

/// Why a draft cannot be saved, in the owner's words.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum DraftError {
    #[error("Add a name or an organisation.")]
    NoName,
    #[error("Number {} has no digits.", .0 + 1)]
    BadNumber(usize),
    #[error("Email {} needs an @ and a domain.", .0 + 1)]
    BadEmail(usize),
}

impl ContactDraft {
    /// An empty draft for "New Contact": one empty mobile number to fill in.
    pub fn blank() -> Self {
        Self {
            numbers: vec![DraftField::new("", FieldKind::Mobile)],
            ..Self::default()
        }
    }

    /// The name the list shows: given and family, else the organisation, else
    /// the first number, else the first email.
    pub fn display_name(&self) -> String {
        let full = [self.given.trim(), self.family.trim()]
            .iter()
            .filter(|part| !part.is_empty())
            .copied()
            .collect::<Vec<_>>()
            .join(" ");
        if !full.is_empty() {
            full
        } else if !self.org.trim().is_empty() {
            self.org.trim().to_owned()
        } else {
            self.numbers
                .iter()
                .chain(&self.emails)
                .map(|f| f.value.trim())
                .find(|v| !v.is_empty())
                .unwrap_or_default()
                .to_owned()
        }
    }

    /// Trims everything, drops empty and repeated fields and applies the
    /// length limits. Safe to call more than once.
    pub fn clean(&mut self) {
        for text in [&mut self.given, &mut self.family, &mut self.org] {
            *text = clip(text.trim(), MAX_TEXT_CHARS).to_owned();
        }
        self.note = clip(self.note.trim(), MAX_NOTE_CHARS).to_owned();
        clean_fields(&mut self.numbers, digits_key);
        clean_fields(&mut self.emails, |value| value.to_lowercase());
        self.groups.sort_unstable();
        self.groups.dedup();
    }

    /// Checks a cleaned draft. Call [`ContactDraft::clean`] first.
    pub fn validate(&self) -> Result<(), DraftError> {
        if self.given.is_empty() && self.family.is_empty() && self.org.is_empty() {
            return Err(DraftError::NoName);
        }
        if let Some(i) = self.numbers.iter().position(|n| !has_digit(&n.value)) {
            return Err(DraftError::BadNumber(i));
        }
        if let Some(i) = self.emails.iter().position(|e| !plausible_email(&e.value)) {
            return Err(DraftError::BadEmail(i));
        }
        Ok(())
    }

    /// Cleans and validates in one step; the cleaned draft is ready to save.
    pub fn finish(mut self) -> Result<Self, DraftError> {
        self.clean();
        self.validate()?;
        Ok(self)
    }
}

/// What Kotlin returns for one contact (`contactDetail`).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DetailPayload {
    pub contact: ContactDraft,
}

/// What Kotlin returns for every contact (`contactsFull`), for export.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct FullPayload {
    pub contacts: Vec<ContactDraft>,
}

/// What Kotlin returns after saving a contact.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SavedPayload {
    pub id: i64,
}

/// What Kotlin returns after an import.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ImportPayload {
    pub added: u32,
}

/// One account contacts can be stored in, as Kotlin lists it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Account {
    /// The opaque key (`type|name`); empty for "on this phone".
    pub key: String,
    pub name: String,
    /// The account type, e.g. `com.google`.
    pub kind: String,
}

/// What Kotlin returns for the accounts.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AccountsPayload {
    pub accounts: Vec<Account>,
}

/// The owner-facing name of an account key: "On this phone",
/// "Google, a@b.com", or just the account name for an unknown type.
pub fn account_label(key: &str) -> String {
    if key.is_empty() {
        return "On this phone".to_owned();
    }
    let (kind, name) = key.split_once('|').unwrap_or(("", key));
    match kind {
        "com.google" => format!("Google, {name}"),
        _ => name.to_owned(),
    }
}

/// A group row's second line: "3 contacts", or with an account
/// "3 contacts · Google, a@b.com".
pub fn group_subtitle(members: usize, account: Option<&str>) -> String {
    let count = match members {
        0 => "No contacts".to_owned(),
        1 => "1 contact".to_owned(),
        n => format!("{n} contacts"),
    };
    match account {
        Some(key) => format!("{count} · {}", account_label(key)),
        None => count,
    }
}

/// Tells the owner which contacts can join a group of `account`.
pub fn group_join_hint(account: &str) -> String {
    if account.is_empty() {
        "Only contacts saved on this phone can join this group.".to_owned()
    } else {
        format!(
            "Only contacts saved in {} can join this group.",
            account_label(account)
        )
    }
}

/// The groups that belong to `account` (a contact can only join those).
pub fn groups_of<'a>(groups: &'a [Group], account: &str) -> impl Iterator<Item = &'a Group> {
    let account = account.to_owned();
    groups.iter().filter(move |g| g.account == account)
}

/// Drops the draft's groups that are not in its own account.
pub fn retain_account_groups(draft: &mut ContactDraft, groups: &[Group]) {
    let allowed: std::collections::HashSet<i64> =
        groups_of(groups, &draft.account).map(|g| g.id).collect();
    draft.groups.retain(|id| allowed.contains(id));
}

/// Longest group title, in characters.
pub const MAX_GROUP_TITLE_CHARS: usize = 60;

/// A contact group (a label such as "Family") and who is in it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Group {
    pub id: i64,
    pub title: String,
    /// Ids of the contacts in the group.
    pub members: Vec<i64>,
    /// The owning account, in the same opaque form as [`ContactDraft::account`].
    #[serde(skip_serializing_if = "String::is_empty")]
    pub account: String,
}

/// What Kotlin returns for the groups.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct GroupsPayload {
    pub groups: Vec<Group>,
}

/// Why a group title is refused, in the owner's words.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum GroupError {
    #[error("Give the group a name.")]
    Empty,
    #[error("A group with that name already exists.")]
    Duplicate,
}

/// Trims `title` and checks it against the other groups. `editing` is the
/// group being renamed, which may keep its own name.
pub fn group_title(
    title: &str,
    groups: &[Group],
    editing: Option<i64>,
) -> Result<String, GroupError> {
    let title = clip(title.trim(), MAX_GROUP_TITLE_CHARS).trim_end();
    if title.is_empty() {
        return Err(GroupError::Empty);
    }
    let wanted = crate::search::fold(title);
    let taken = groups
        .iter()
        .filter(|g| Some(g.id) != editing)
        .any(|g| crate::search::fold(&g.title) == wanted);
    if taken {
        Err(GroupError::Duplicate)
    } else {
        Ok(title.to_owned())
    }
}

/// Cuts `s` to at most `max` characters on a character boundary.
fn clip(s: &str, max: usize) -> &str {
    match s.char_indices().nth(max) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}

fn has_digit(value: &str) -> bool {
    value.chars().any(|c| c.is_ascii_digit())
}

/// Digits only, with a leading `+` kept, so `0555 111 22 33` equals
/// `05551112233`.
fn digits_key(value: &str) -> String {
    let plus = value.trim_start().starts_with('+');
    let digits: String = value.chars().filter(char::is_ascii_digit).collect();
    if plus { format!("+{digits}") } else { digits }
}

fn plausible_email(value: &str) -> bool {
    let Some((local, domain)) = value.rsplit_once('@') else {
        return false;
    };
    !local.is_empty()
        && !value.contains(char::is_whitespace)
        && domain.contains('.')
        && !domain.starts_with('.')
        && !domain.ends_with('.')
}

/// Trims values, drops empty and duplicate ones (by `key`), clips the labels
/// and keeps at most [`MAX_FIELDS`].
fn clean_fields(fields: &mut Vec<DraftField>, key: impl Fn(&str) -> String) {
    let mut seen = std::collections::HashSet::new();
    fields.retain_mut(|field| {
        field.value = clip(field.value.trim(), MAX_VALUE_CHARS).to_owned();
        field.label = field
            .label
            .take()
            .map(|l| clip(l.trim(), MAX_TEXT_CHARS).to_owned())
            .filter(|l| !l.is_empty());
        if field.kind == FieldKind::Custom && field.label.is_none() {
            field.kind = FieldKind::Other;
        }
        !field.value.is_empty() && seen.insert(key(&field.value))
    });
    fields.truncate(MAX_FIELDS);
}

/// Membership edits for one group: ids to add and ids to remove.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SelectionChange {
    pub add: Vec<i64>,
    pub remove: Vec<i64>,
}

impl SelectionChange {
    /// True when nothing would change.
    pub fn is_empty(&self) -> bool {
        self.add.is_empty() && self.remove.is_empty()
    }
}

/// Difference between the initial members and the chosen set, ascending.
pub fn selection_change(initial: &[i64], chosen: &BTreeSet<i64>) -> SelectionChange {
    let initial: BTreeSet<i64> = initial.iter().copied().collect();
    SelectionChange {
        add: chosen.difference(&initial).copied().collect(),
        remove: initial.difference(chosen).copied().collect(),
    }
}

/// Result of a group membership change, as Kotlin reports it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MembersPayload {
    #[serde(default)]
    pub added: u32,
    #[serde(default)]
    pub removed: u32,
    #[serde(default)]
    pub skipped: u32,
}

/// One-line message for a finished membership change.
pub fn members_summary(title: &str, done: &MembersPayload) -> String {
    let mut text = match (done.added, done.removed) {
        (0, 0) => "No changes.".to_owned(),
        (a, 0) => format!("Added {a} to {title}."),
        (0, r) => format!("Removed {r} from {title}."),
        (a, r) => format!("Added {a} to {title} and removed {r}."),
    };
    match done.skipped {
        0 => {}
        1 => text.push_str(" 1 contact is in another account and can't join."),
        n => text.push_str(&format!(
            " {n} contacts are in another account and can't join."
        )),
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    fn draft(given: &str, numbers: &[&str]) -> ContactDraft {
        ContactDraft {
            given: given.to_owned(),
            numbers: numbers
                .iter()
                .map(|n| DraftField::new(*n, FieldKind::Mobile))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn a_blank_draft_has_one_empty_number_row() {
        let blank = ContactDraft::blank();
        assert_eq!(blank.numbers.len(), 1);
        assert_eq!(blank.finish(), Err(DraftError::NoName));
    }

    #[test]
    fn a_name_and_a_number_are_enough() {
        let saved = draft("  Ayşe ", &["0555 111 22 33"]).finish();
        let saved = saved.expect("valid");
        assert_eq!(saved.given, "Ayşe");
        assert_eq!(saved.display_name(), "Ayşe");
    }

    #[test]
    fn a_number_is_optional() {
        let saved = draft("Ayşe", &["  "]).finish().expect("valid");
        assert!(saved.numbers.is_empty());
        assert!(draft("Ayşe", &[]).finish().is_ok());
    }

    #[test]
    fn a_name_or_an_organisation_is_needed() {
        assert_eq!(draft("", &["5551112233"]).finish(), Err(DraftError::NoName));
        let mut d = draft("", &[]);
        d.org = "Rovsti".into();
        assert!(d.finish().is_ok());
    }

    #[test]
    fn display_name_falls_back_to_the_first_email() {
        let mut d = draft("", &[]);
        d.emails = vec![DraftField::new("a@b.com", FieldKind::Home)];
        assert_eq!(d.display_name(), "a@b.com");
        d.numbers = vec![DraftField::new("555", FieldKind::Mobile)];
        assert_eq!(d.display_name(), "555");
    }

    #[test]
    fn account_labels_read_well() {
        assert_eq!(account_label(""), "On this phone");
        assert_eq!(account_label("com.google|a@b.com"), "Google, a@b.com");
        assert_eq!(account_label("org.example|Work"), "Work");
    }

    #[test]
    fn only_the_accounts_own_groups_are_kept() {
        let g = |id, account: &str| Group {
            id,
            account: account.into(),
            ..Group::default()
        };
        let groups = vec![g(1, ""), g(2, "com.google|a@b.com"), g(3, "")];
        assert_eq!(groups_of(&groups, "").count(), 2);
        let mut d = draft("Ayşe", &[]);
        d.groups = vec![1, 2, 9];
        retain_account_groups(&mut d, &groups);
        assert_eq!(d.groups, [1]);
        d.account = "com.google|a@b.com".into();
        d.groups = vec![1, 2];
        retain_account_groups(&mut d, &groups);
        assert_eq!(d.groups, [2]);
    }

    #[test]
    fn a_number_needs_a_digit() {
        assert_eq!(
            draft("Ayşe", &["abc"]).finish(),
            Err(DraftError::BadNumber(0))
        );
    }

    #[test]
    fn the_same_number_in_two_formats_is_kept_once() {
        let saved = draft(
            "Ayşe",
            &["0555 111 22 33", "05551112233", "+90 555 111 22 33"],
        )
        .finish()
        .expect("valid");
        assert_eq!(saved.numbers.len(), 2, "the +90 form is a different key");
    }

    #[test]
    fn emails_are_checked_and_lowercased_for_duplicates() {
        let mut d = draft("Ayşe", &["5551112233"]);
        d.emails = vec![
            DraftField::new("A@Example.com", FieldKind::Work),
            DraftField::new("a@example.com", FieldKind::Home),
        ];
        let saved = d.clone().finish().expect("valid");
        assert_eq!(saved.emails.len(), 1);
        d.emails = vec![DraftField::new("nope", FieldKind::Home)];
        assert_eq!(d.finish(), Err(DraftError::BadEmail(0)));
    }

    #[test]
    fn an_organisation_alone_names_a_contact() {
        let mut d = draft("", &["5551112233"]);
        d.org = "Rovsti Ltd".to_owned();
        let saved = d.finish().expect("valid");
        assert_eq!(saved.display_name(), "Rovsti Ltd");
    }

    #[test]
    fn display_name_falls_back_to_the_first_number() {
        let d = draft("", &["", "5551112233"]);
        assert_eq!(d.display_name(), "5551112233");
    }

    #[test]
    fn a_custom_kind_without_a_label_becomes_other() {
        let mut d = draft("Ayşe", &["5551112233"]);
        d.numbers[0].kind = FieldKind::Custom;
        let saved = d.finish().expect("valid");
        assert_eq!(saved.numbers[0].kind, FieldKind::Other);
    }

    #[test]
    fn limits_apply_on_character_boundaries() {
        let mut d = draft(&"ş".repeat(300), &["5551112233"]);
        d.note = "ğ".repeat(MAX_NOTE_CHARS + 10);
        d.clean();
        assert_eq!(d.given.chars().count(), MAX_TEXT_CHARS);
        assert_eq!(d.note.chars().count(), MAX_NOTE_CHARS);
    }

    #[test]
    fn at_most_twelve_numbers_are_kept() {
        let numbers: Vec<String> = (0..20).map(|i| format!("555000{i:04}")).collect();
        let refs: Vec<&str> = numbers.iter().map(String::as_str).collect();
        let saved = draft("Ayşe", &refs).finish().expect("valid");
        assert_eq!(saved.numbers.len(), MAX_FIELDS);
    }

    #[test]
    fn kinds_cycle_and_wrap() {
        let kinds = FieldKind::NUMBER_KINDS;
        assert_eq!(FieldKind::Mobile.next_in(&kinds), FieldKind::Home);
        assert_eq!(FieldKind::Work.next_in(&kinds), FieldKind::Mobile);
        assert_eq!(FieldKind::Other.next_in(&kinds), FieldKind::Mobile);
    }

    #[test]
    fn groups_are_sorted_and_unique() {
        let mut d = draft("Ayşe", &["5551112233"]);
        d.groups = vec![9, 3, 9, 1];
        d.clean();
        assert_eq!(d.groups, [1, 3, 9]);
    }

    #[test]
    fn json_round_trip_ignores_unknown_fields() {
        let json =
            r#"{"id":7,"given":"Ayşe","numbers":[{"value":"5551112233","kind":"mobile"}],"x":1}"#;
        let d: ContactDraft = serde_json::from_str(json).expect("parses");
        assert_eq!(d.id, Some(7));
        let back = serde_json::to_string(&d).expect("serialises");
        assert!(back.contains("\"given\":\"Ayşe\""));
    }

    #[test]
    fn an_unknown_kind_reads_as_other() {
        let f: DraftField = serde_json::from_str(r#"{"value":"1","kind":"pager"}"#).expect("ok");
        assert_eq!(f.kind, FieldKind::Other);
    }

    #[test]
    fn group_subtitle_counts_and_account() {
        assert_eq!(group_subtitle(0, None), "No contacts");
        assert_eq!(group_subtitle(1, None), "1 contact");
        assert_eq!(group_subtitle(3, None), "3 contacts");
        assert_eq!(group_subtitle(0, Some("")), "No contacts · On this phone");
        assert_eq!(
            group_subtitle(3, Some("com.google|a@b.com")),
            "3 contacts · Google, a@b.com"
        );
        assert_eq!(group_subtitle(1, Some("")), "1 contact · On this phone");
    }

    #[test]
    fn group_join_hint_names_the_account() {
        assert_eq!(
            group_join_hint(""),
            "Only contacts saved on this phone can join this group."
        );
        assert_eq!(
            group_join_hint("com.google|a@b.com"),
            "Only contacts saved in Google, a@b.com can join this group."
        );
    }

    #[test]
    fn group_titles_are_trimmed_and_checked() {
        let groups = vec![
            Group {
                id: 1,
                title: "Family".into(),
                ..Group::default()
            },
            Group {
                id: 2,
                title: "Work".into(),
                ..Group::default()
            },
        ];
        assert_eq!(
            group_title("  Friends ", &groups, None).as_deref(),
            Ok("Friends")
        );
        assert_eq!(group_title("   ", &groups, None), Err(GroupError::Empty));
        assert_eq!(
            group_title("family", &groups, None),
            Err(GroupError::Duplicate)
        );
        assert_eq!(
            group_title("FAMİLY", &groups, None),
            Err(GroupError::Duplicate)
        );
        assert_eq!(
            group_title("Family", &groups, Some(1)).as_deref(),
            Ok("Family")
        );
        assert_eq!(
            group_title("Work", &groups, Some(1)),
            Err(GroupError::Duplicate)
        );
    }

    fn set(ids: &[i64]) -> BTreeSet<i64> {
        ids.iter().copied().collect()
    }

    #[test]
    fn selection_change_cases() {
        let c = selection_change(&[1, 2], &set(&[1, 2, 5, 3]));
        assert_eq!((c.add, c.remove), (vec![3, 5], vec![]));
        let c = selection_change(&[4, 2, 1], &set(&[1]));
        assert_eq!((c.add, c.remove), (vec![], vec![2, 4]));
        let c = selection_change(&[1, 2], &set(&[2, 3]));
        assert_eq!((c.add, c.remove), (vec![3], vec![1]));
        assert!(selection_change(&[1, 2], &set(&[2, 1])).is_empty());
        let c = selection_change(&[2, 2, 1, 1], &set(&[]));
        assert_eq!(c.remove, vec![1, 2]);
        assert!(!c.is_empty());
    }

    #[test]
    fn members_summary_branches() {
        let p = |added, removed, skipped| MembersPayload {
            added,
            removed,
            skipped,
        };
        assert_eq!(members_summary("Family", &p(2, 0, 0)), "Added 2 to Family.");
        assert_eq!(
            members_summary("Family", &p(0, 1, 0)),
            "Removed 1 from Family."
        );
        assert_eq!(
            members_summary("Family", &p(1, 2, 0)),
            "Added 1 to Family and removed 2."
        );
        assert_eq!(members_summary("Family", &p(0, 0, 0)), "No changes.");
        assert_eq!(
            members_summary("Family", &p(2, 0, 1)),
            "Added 2 to Family. 1 contact is in another account and can't join."
        );
        assert_eq!(
            members_summary("Family", &p(0, 0, 3)),
            "No changes. 3 contacts are in another account and can't join."
        );
    }

    #[test]
    fn members_payload_defaults() {
        let p: MembersPayload = serde_json::from_str(r#"{"added":1}"#).expect("decodes");
        assert_eq!(
            p,
            MembersPayload {
                added: 1,
                removed: 0,
                skipped: 0
            }
        );
    }
}
