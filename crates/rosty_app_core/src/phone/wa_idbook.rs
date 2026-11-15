//! Phone's own WhatsApp identity table: which phone number is behind a LID.
//!
//! Phone shows no WhatsApp messages, so it does not open the offline store of Messages
//! ([`crate::wa_sync`]). It keeps only this: the [`IdentityMap`] of `rosty_core::wa_identity`, saved as one JSON
//! file ([`FILE`]) in the app's data directory, so a WhatsApp call whose peer arrives as a LID still has a number
//! to show, to dial and to look a contact up by.
//!
//! What feeds it: pages of the gateway's `GET /v1/identity/mappings` ([`Book::catch_up`], resumed from the stored
//! cursor), the `identity_mapped` and `identity_number_changed` events and the `peer` / `peer_alt` pair of a call
//! ([`Book::learn_alt`], [`Book::number_changed`]). Another account on the link empties it
//! ([`Book::note_account`], [`Book::account_changed`]).
//!
//! The file belongs to one instance: read for another instance id it is empty. A missing, damaged or newer file
//! is an empty table too, and the next catch-up fills it again from the gateway.

use std::path::Path;

use rosty_core::wa_identity::{
    AliasKind, AliasRow, IdentityMap, PersonRow, Source, WaId, WaIdKind, parse,
};
use rosty_wa_proto::IdentityMappings;
use serde::{Deserialize, Serialize};

use crate::storage::{load_json, save_json};

/// The file of the table, in the app's data directory.
pub const FILE: &str = "wa-identity.json";

/// The layout of [`FILE`]. A file of another version is not read.
const VERSION: u32 = 1;

/// [`FILE`] as stored: the `person` and `alias` rows of the map and what the next catch-up needs.
#[derive(Debug, Default, Serialize, Deserialize)]
struct Stored {
    version: u32,
    /// The instance the rows belong to.
    instance: String,
    /// The account's own number, bare (`<digits>@s.whatsapp.net`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    own_pn: Option<String>,
    /// The account's own LID, bare.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    own_lid: Option<String>,
    /// The gateway's `account_epoch` the rows were learned under.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    account_epoch: Option<u32>,
    /// `since=` of the next `GET /v1/identity/mappings`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    cursor: Option<String>,
    #[serde(default)]
    persons: Vec<PersonRow>,
    #[serde(default)]
    aliases: Vec<AliasRow>,
}

/// A LID and the phone number of the person behind it, both bare.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Pair {
    /// `<user>@lid`.
    pub lid: String,
    /// `<digits>@s.whatsapp.net`: the person's current number.
    pub pn: String,
}

/// What [`Book::catch_up`] did with one page of the gateway's pairs.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct CaughtUp {
    /// Pairs in the page.
    pub pairs: u32,
    /// The cursor the table now holds: `since=` of the next call.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub since: Option<String>,
    /// The gateway has more: ask again with `since`.
    pub more: bool,
    /// The table or its cursor changed: save it.
    pub changed: bool,
}

/// The identity table of one instance, in memory.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Book {
    instance: String,
    map: IdentityMap,
    own_pn: Option<String>,
    own_lid: Option<String>,
    account_epoch: Option<u32>,
    cursor: Option<String>,
}

/// The bare id of `text` when it is of `kind`.
fn bare_of(text: Option<&str>, kind: WaIdKind) -> Option<String> {
    let id = parse(text?)?.id;
    (id.kind() == kind).then(|| id.bare())
}

/// The LID and the number among two ids that are the two forms of one person, in that order.
fn pair<'a>(a: &'a str, b: &'a str) -> Option<(&'a str, &'a str)> {
    let kind = |text: &str| parse(text).map(|parsed| parsed.id.kind());
    match (kind(a)?, kind(b)?) {
        (WaIdKind::Lid, WaIdKind::Pn) => Some((a, b)),
        (WaIdKind::Pn, WaIdKind::Lid) => Some((b, a)),
        _ => None,
    }
}

impl Book {
    /// An empty table for `instance`.
    #[must_use]
    pub fn new(instance: &str) -> Self {
        Self {
            instance: instance.to_owned(),
            ..Self::default()
        }
    }

    /// The table of `instance` stored in `dir`; empty when there is none, when the file is damaged or of another
    /// version, or when it belongs to another instance.
    #[must_use]
    pub fn load(dir: &Path, instance: &str) -> Self {
        let Some(stored) = load_json::<Stored>(dir, FILE)
            .filter(|stored| stored.version == VERSION && stored.instance == instance)
        else {
            return Self::new(instance);
        };
        let mut map = IdentityMap::from_rows(stored.persons, stored.aliases);
        let _ = map.set_own(stored.own_pn.as_deref(), stored.own_lid.as_deref());
        Self {
            instance: stored.instance,
            map,
            own_pn: stored.own_pn,
            own_lid: stored.own_lid,
            account_epoch: stored.account_epoch,
            cursor: stored.cursor,
        }
    }

    /// Writes the table to `dir` (atomically, readable by the owner only).
    pub fn save(&self, dir: &Path) -> std::io::Result<()> {
        let (persons, aliases) = self.map.rows();
        save_json(
            dir,
            FILE,
            &Stored {
                version: VERSION,
                instance: self.instance.clone(),
                own_pn: self.own_pn.clone(),
                own_lid: self.own_lid.clone(),
                account_epoch: self.account_epoch,
                cursor: self.cursor.clone(),
                persons,
                aliases,
            },
        )
    }

    /// Removes the stored table of `dir` (the link was removed). A missing file is fine.
    pub fn wipe(dir: &Path) -> std::io::Result<()> {
        match std::fs::remove_file(dir.join(FILE)) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }

    /// The instance the table belongs to.
    #[must_use]
    pub fn instance(&self) -> &str {
        &self.instance
    }

    /// `since=` of the next `GET /v1/identity/mappings`; `None` before the first page (ask for every pair).
    #[must_use]
    pub fn since(&self) -> Option<&str> {
        self.cursor.as_deref()
    }

    /// The account's own number and LID, bare, as far as they are known.
    #[must_use]
    pub fn own(&self) -> (Option<&str>, Option<&str>) {
        (self.own_pn.as_deref(), self.own_lid.as_deref())
    }

    /// How many persons the table holds.
    #[must_use]
    pub fn len(&self) -> usize {
        self.map.len()
    }

    /// Whether the table holds no person.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    /// Learns that `lid` and `pn` are one person. True when a row changed.
    pub fn learn(&mut self, lid: &str, pn: &str, source: Source, at_ms: i64) -> bool {
        let changes = self.map.learn(lid, pn, source, at_ms);
        !(changes.persons.is_empty()
            && changes.aliases.is_empty()
            && changes.removed_persons.is_empty())
    }

    /// [`Self::learn`] for two ids in either order (a call's `peer` and `peer_alt`). Two ids that are not a LID
    /// and a number teach nothing.
    pub fn learn_alt(&mut self, a: &str, b: &str, source: Source, at_ms: i64) -> bool {
        pair(a, b).is_some_and(|(lid, pn)| self.learn(lid, pn, source, at_ms))
    }

    /// A contact moved from `old_pn` to `new_pn`: its LIDs now resolve to the new number. True when a row changed.
    pub fn number_changed(
        &mut self,
        old_pn: &str,
        new_pn: &str,
        old_lid: Option<&str>,
        new_lid: Option<&str>,
        at_ms: i64,
    ) -> bool {
        let changes = self
            .map
            .number_changed(old_pn, new_pn, old_lid, new_lid, at_ms);
        !(changes.persons.is_empty()
            && changes.aliases.is_empty()
            && changes.removed_persons.is_empty())
    }

    /// Takes in one page of `GET /v1/identity/mappings?since=<cursor>`: learns every pair and keeps the page's
    /// cursor. The cursor only moves when the page names one, so a catch-up that was cut short goes on where it
    /// stopped.
    pub fn catch_up(&mut self, page: &IdentityMappings) -> CaughtUp {
        let mut changed = false;
        for item in &page.items {
            changed |= self.learn(
                &item.lid,
                &item.pn,
                Source::from_wire(&item.source),
                item.learned_ms,
            );
        }
        if let Some(next) = &page.next
            && self.cursor.as_deref() != Some(next.as_str())
        {
            self.cursor = Some(next.clone());
            changed = true;
        }
        CaughtUp {
            pairs: page.items.len() as u32,
            since: self.cursor.clone(),
            more: page.next.is_some(),
            changed,
        }
    }

    /// Takes in what the instance status says about the account: its own number and LID and the gateway's
    /// account epoch. Another own number, or another account epoch, than the stored one is another account: the
    /// table and its cursor are emptied first (true). A value the status leaves out never empties anything.
    pub fn note_account(
        &mut self,
        own_pn: Option<&str>,
        own_lid: Option<&str>,
        account_epoch: Option<u32>,
    ) -> bool {
        let pn = bare_of(own_pn, WaIdKind::Pn);
        let lid = bare_of(own_lid, WaIdKind::Lid);
        let other_number = matches!((&self.own_pn, &pn), (Some(old), Some(new)) if old != new);
        let other_epoch =
            matches!((self.account_epoch, account_epoch), (Some(old), Some(new)) if old != new);
        let reset = other_number || other_epoch;
        if reset {
            self.reset();
        }
        if pn.is_some() {
            // The LID of the old number is not the LID of the new one.
            if self.own_pn != pn {
                self.own_lid = None;
            }
            self.own_pn = pn;
        }
        if lid.is_some() {
            self.own_lid = lid;
        }
        if account_epoch.is_some() {
            self.account_epoch = account_epoch;
        }
        let _ = self
            .map
            .set_own(self.own_pn.as_deref(), self.own_lid.as_deref());
        reset
    }

    /// The gateway announced another account on the link (`account_changed`): the table and its cursor are
    /// emptied, and `new_pn` is the own number from now on. The account epoch is taken from the next status.
    pub fn account_changed(&mut self, new_pn: Option<&str>) {
        self.reset();
        self.account_epoch = None;
        self.own_pn = bare_of(new_pn, WaIdKind::Pn);
        let _ = self.map.set_own(self.own_pn.as_deref(), None);
    }

    /// Empties the table, its cursor and the own ids; the instance stays.
    pub fn reset(&mut self) {
        *self = Self {
            instance: std::mem::take(&mut self.instance),
            account_epoch: self.account_epoch,
            ..Self::default()
        };
    }

    /// `+<digits>` of the person behind `id` (a JID, device allowed): the current number of a LID the table
    /// knows, the number a phone JID carries. `None` for a LID nobody mapped and for anything that is not a
    /// person.
    #[must_use]
    pub fn number_of(&self, id: &str) -> Option<String> {
        let resolved = self.map.lookup(id)?;
        match resolved.person_id {
            Some(person) => self
                .map
                .numbers(person)
                .into_iter()
                .next()
                .or_else(|| resolved.wire.number()),
            None => resolved.wire.number(),
        }
    }

    /// Every LID the table can name, current or former, with the current number of its person, by LID.
    #[must_use]
    pub fn pairs(&self) -> Vec<Pair> {
        let (_, aliases) = self.map.rows();
        aliases
            .into_iter()
            .filter(|row| row.kind == AliasKind::Lid)
            .filter_map(|row| {
                let number = self.map.numbers(row.person_id).into_iter().next()?;
                Some(Pair {
                    lid: WaId::lid(&row.user)?.bare(),
                    pn: WaId::pn(number.trim_start_matches('+'))?.bare(),
                })
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use rosty_wa_proto::{IdentityMapping, IdentitySource};

    use super::*;

    const INSTANCE: &str = "inst-1";
    const LID_A: &str = "777000000000001@lid";
    const PN_A: &str = "100000000001@s.whatsapp.net";
    const LID_B: &str = "777000000000002@lid";
    const PN_B: &str = "100000000002@s.whatsapp.net";
    const OWN: &str = "100000000009@s.whatsapp.net";

    /// A fresh directory under the system temp dir, removed when dropped.
    struct TempDir(std::path::PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let dir =
                std::env::temp_dir().join(format!("rosty-wa-idbook-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).expect("temp dir");
            Self(dir)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn page(items: &[(&str, &str)], next: Option<&str>) -> IdentityMappings {
        IdentityMappings {
            items: items
                .iter()
                .map(|(lid, pn)| IdentityMapping::new(*lid, *pn, IdentitySource::Usync, 10))
                .collect(),
            next: next.map(str::to_owned),
        }
    }

    #[test]
    fn a_learned_lid_has_its_number_and_an_unknown_one_has_none() {
        let mut book = Book::new(INSTANCE);
        assert!(book.learn(LID_A, PN_A, Source::Call, 1));
        assert_eq!(book.number_of(LID_A).as_deref(), Some("+100000000001"));
        // The device suffix of a call's peer does not matter.
        assert_eq!(
            book.number_of("777000000000001:4@lid").as_deref(),
            Some("+100000000001")
        );
        assert_eq!(book.number_of(LID_B), None);
        assert_eq!(book.number_of(PN_B).as_deref(), Some("+100000000002"));
        assert_eq!(book.number_of("120000000000000001@g.us"), None);
        assert_eq!(book.number_of("not an id"), None);
        // The same pair again changes nothing.
        assert!(!book.learn(LID_A, PN_A, Source::Call, 1));
    }

    #[test]
    fn a_call_pair_teaches_in_either_order_and_other_pairs_teach_nothing() {
        let mut book = Book::new(INSTANCE);
        assert!(book.learn_alt(PN_A, LID_A, Source::Call, 1));
        assert!(book.learn_alt(LID_B, PN_B, Source::Call, 1));
        assert!(!book.learn_alt(PN_A, PN_B, Source::Call, 1));
        assert!(!book.learn_alt(LID_A, "120000000000000001@g.us", Source::Call, 1));
        assert!(!book.learn_alt(LID_A, "", Source::Call, 1));
        assert_eq!(
            book.pairs(),
            [
                Pair {
                    lid: LID_A.into(),
                    pn: PN_A.into()
                },
                Pair {
                    lid: LID_B.into(),
                    pn: PN_B.into()
                },
            ]
        );
    }

    #[test]
    fn the_table_survives_a_save_and_a_load() {
        let dir = TempDir::new("save");
        let mut book = Book::new(INSTANCE);
        book.note_account(Some(OWN), Some("777000000000009@lid"), Some(3));
        book.learn(LID_A, PN_A, Source::PeerMessage, 5);
        book.catch_up(&page(&[(LID_B, PN_B)], Some("c1")));
        book.save(dir.path()).unwrap();

        let loaded = Book::load(dir.path(), INSTANCE);
        assert_eq!(loaded, book);
        assert_eq!(loaded.number_of(LID_B).as_deref(), Some("+100000000002"));
        assert_eq!(loaded.since(), Some("c1"));
        assert_eq!(loaded.own(), (Some(OWN), Some("777000000000009@lid")));
    }

    #[test]
    fn a_catch_up_goes_on_from_the_stored_cursor() {
        let dir = TempDir::new("cursor");
        let mut book = Book::load(dir.path(), INSTANCE);
        assert_eq!(book.since(), None);
        let first = book.catch_up(&page(&[(LID_A, PN_A)], Some("c1")));
        assert_eq!(
            first,
            CaughtUp {
                pairs: 1,
                since: Some("c1".into()),
                more: true,
                changed: true
            }
        );
        book.save(dir.path()).unwrap();

        // Killed here: the next start asks from "c1", not from the beginning.
        let mut book = Book::load(dir.path(), INSTANCE);
        assert_eq!(book.since(), Some("c1"));
        let second = book.catch_up(&page(&[(LID_B, PN_B)], Some("c2")));
        assert_eq!((second.since.as_deref(), second.more), (Some("c2"), true));
        // The empty last page names no cursor: the stored one stays, and nothing needs saving.
        let last = book.catch_up(&page(&[], None));
        assert_eq!(
            last,
            CaughtUp {
                pairs: 0,
                since: Some("c2".into()),
                more: false,
                changed: false
            }
        );
        assert_eq!(book.len(), 2);
    }

    #[test]
    fn another_own_number_empties_the_table_and_its_cursor() {
        let mut book = Book::new(INSTANCE);
        assert!(!book.note_account(Some(OWN), None, Some(1)));
        book.catch_up(&page(&[(LID_A, PN_A)], Some("c1")));

        // The same account again, and a status without the number: nothing is lost.
        assert!(!book.note_account(Some("100000000009:3@s.whatsapp.net"), None, Some(1)));
        assert!(!book.note_account(None, None, None));
        assert_eq!(book.number_of(LID_A).as_deref(), Some("+100000000001"));

        assert!(book.note_account(Some("100000000008@s.whatsapp.net"), None, None));
        assert_eq!(book.number_of(LID_A), None);
        assert_eq!(book.since(), None);
        assert_eq!(book.own().0, Some("100000000008@s.whatsapp.net"));
        assert!(book.pairs().is_empty());
    }

    #[test]
    fn another_account_epoch_empties_the_table_even_with_the_same_number() {
        let mut book = Book::new(INSTANCE);
        book.note_account(Some(OWN), None, Some(1));
        book.catch_up(&page(&[(LID_A, PN_A)], Some("c1")));
        assert!(book.note_account(Some(OWN), None, Some(2)));
        assert_eq!((book.number_of(LID_A), book.since()), (None, None));
        // Learned again under the new epoch, it stays.
        book.learn(LID_A, PN_A, Source::Call, 2);
        assert!(!book.note_account(Some(OWN), None, Some(2)));
        assert_eq!(book.number_of(LID_A).as_deref(), Some("+100000000001"));
    }

    #[test]
    fn the_account_changed_event_empties_the_table() {
        let mut book = Book::new(INSTANCE);
        book.note_account(Some(OWN), Some("777000000000009@lid"), Some(1));
        book.catch_up(&page(&[(LID_A, PN_A)], Some("c1")));
        book.account_changed(Some("100000000008@s.whatsapp.net"));
        assert!(book.pairs().is_empty());
        assert_eq!(book.since(), None);
        assert_eq!(book.own(), (Some("100000000008@s.whatsapp.net"), None));
        // The status that follows names the new epoch without emptying again.
        assert!(!book.note_account(Some("100000000008@s.whatsapp.net"), None, Some(2)));
    }

    #[test]
    fn a_number_change_moves_the_lid_to_the_new_number() {
        let mut book = Book::new(INSTANCE);
        book.learn(LID_A, PN_A, Source::Usync, 1);
        assert!(book.number_changed(PN_A, PN_B, Some(LID_A), None, 2));
        assert_eq!(book.number_of(LID_A).as_deref(), Some("+100000000002"));
        // The old number is a former alias of the same person.
        assert_eq!(book.number_of(PN_A).as_deref(), Some("+100000000002"));
    }

    #[test]
    fn a_file_of_another_instance_a_damaged_or_a_newer_one_reads_empty() {
        let dir = TempDir::new("other");
        let mut book = Book::new(INSTANCE);
        book.catch_up(&page(&[(LID_A, PN_A)], Some("c1")));
        book.save(dir.path()).unwrap();

        let other = Book::load(dir.path(), "inst-2");
        assert_eq!(
            (other.len(), other.since(), other.instance()),
            (0, None, "inst-2")
        );

        std::fs::write(dir.path().join(FILE), b"{ not json").unwrap();
        assert!(Book::load(dir.path(), INSTANCE).is_empty());

        std::fs::write(
            dir.path().join(FILE),
            format!(r#"{{"version":{},"instance":"{INSTANCE}"}}"#, VERSION + 1),
        )
        .unwrap();
        assert!(Book::load(dir.path(), INSTANCE).is_empty());
    }

    #[test]
    fn wipe_removes_the_file_and_a_missing_one_is_fine() {
        let dir = TempDir::new("wipe");
        Book::new(INSTANCE).save(dir.path()).unwrap();
        Book::wipe(dir.path()).unwrap();
        assert!(!dir.path().join(FILE).exists());
        Book::wipe(dir.path()).unwrap();
    }

    #[test]
    fn a_weaker_pair_does_not_move_a_lid_a_call_named() {
        let mut book = Book::new(INSTANCE);
        book.learn(LID_A, PN_A, Source::Call, 5);
        book.learn(LID_A, PN_B, Source::History, 9);
        assert_eq!(book.number_of(LID_A).as_deref(), Some("+100000000001"));
    }
}
