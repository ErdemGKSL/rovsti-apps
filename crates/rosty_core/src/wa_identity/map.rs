//! The table that joins phone-number ids and LIDs into persons.

use std::collections::{BTreeMap, BTreeSet};

use super::id::{IdentityKey, WaId, WaIdKind, canonical_key, parse};
use super::rows::{
    AliasKind, AliasRow, AliasState, Changes, MergeReason, MergeStep, PersonId, PersonRow,
    RejectReason, Rejected, Resolved,
};
use super::source::Source;

#[derive(Clone, Debug, PartialEq, Eq)]
struct Alias {
    person: PersonId,
    state: AliasState,
    source: Source,
    learned_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Person {
    key: IdentityKey,
    aliases: BTreeSet<WaId>,
}

/// A pair's source and time, as it competes with what is stored.
#[derive(Clone, Copy)]
struct Claim {
    source: Source,
    at_ms: i64,
    /// The account's own pair: wins every contest.
    force: bool,
}

impl Claim {
    /// Moves an alias without touching its source and time.
    const KEEP: Self = Self {
        source: Source::Other,
        at_ms: i64::MIN,
        force: false,
    };

    fn new(source: Source, at_ms: i64) -> Self {
        Self {
            source,
            at_ms,
            force: false,
        }
    }

    fn strength(self) -> (u8, i64) {
        (self.source.rank(), self.at_ms)
    }

    /// Higher rank wins; equal ranks go to the newer (or equally new) claim.
    fn beats(self, alias: &Alias) -> bool {
        self.force || self.strength() >= (alias.source.rank(), alias.learned_ms)
    }
}

/// What one call touched, turned into [`Changes`] when it ends.
#[derive(Default)]
struct Tx {
    persons: BTreeSet<PersonId>,
    aliases: BTreeSet<WaId>,
    removed: BTreeSet<PersonId>,
    merges: Vec<MergeStep>,
    rejected: Vec<Rejected>,
}

impl Tx {
    fn reject(&mut self, lid: &str, pn: &str, source: Source, reason: RejectReason) {
        self.rejected.push(Rejected {
            lid: lid.to_owned(),
            pn: pn.to_owned(),
            source,
            reason,
        });
    }
}

fn alias_kind(id: &WaId) -> Option<AliasKind> {
    match id.kind() {
        WaIdKind::Pn => Some(AliasKind::Pn),
        WaIdKind::Lid => Some(AliasKind::Lid),
        _ => None,
    }
}

fn person_id_of(text: &str, kind: WaIdKind) -> Option<WaId> {
    parse(text).map(|p| p.id).filter(|id| id.kind() == kind)
}

/// Persons and their aliases, in memory.
///
/// A person is one human on WhatsApp: at most one current phone number and
/// one current LID, plus earlier ones. Every alias belongs to exactly one
/// person. Groups and other kinds are never stored: they resolve to
/// themselves.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct IdentityMap {
    persons: BTreeMap<PersonId, Person>,
    aliases: BTreeMap<WaId, Alias>,
    next_id: i64,
    own_pn: Option<WaId>,
    own_lid: Option<WaId>,
}

impl IdentityMap {
    /// An empty map.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Rebuilds the map from stored rows. Own ids are not rows: call
    /// [`IdentityMap::set_own`] afterwards.
    ///
    /// Rows are repaired rather than refused: an alias with an unreadable
    /// user is skipped, a person without aliases is dropped, an alias of a
    /// missing person gets that person back, of two current aliases of one
    /// kind the stronger stays current, a person without a current number
    /// (or, having no number, without a current LID) gets its strongest one
    /// as current, and every key is recomputed.
    #[must_use]
    pub fn from_rows(
        persons: impl IntoIterator<Item = PersonRow>,
        aliases: impl IntoIterator<Item = AliasRow>,
    ) -> Self {
        let mut map = Self::default();
        let mut next_id = 1;
        for row in persons {
            next_id = next_id.max(row.person_id.0.saturating_add(1));
            map.persons.insert(
                row.person_id,
                Person {
                    key: row.key,
                    aliases: BTreeSet::new(),
                },
            );
        }
        for row in aliases {
            let id = match row.kind {
                AliasKind::Pn => WaId::pn(&row.user),
                AliasKind::Lid => WaId::lid(&row.user),
            };
            let Some(id) = id else { continue };
            next_id = next_id.max(row.person_id.0.saturating_add(1));
            let person = map.persons.entry(row.person_id).or_insert_with(|| Person {
                key: IdentityKey::from(&id),
                aliases: BTreeSet::new(),
            });
            person.aliases.insert(id.clone());
            let alias = Alias {
                person: row.person_id,
                state: row.state,
                source: row.source,
                learned_ms: row.learned_ms,
            };
            if let Some(old) = map.aliases.insert(id.clone(), alias)
                && old.person != row.person_id
                && let Some(other) = map.persons.get_mut(&old.person)
            {
                other.aliases.remove(&id);
            }
        }
        map.next_id = next_id;
        map.persons.retain(|_, person| !person.aliases.is_empty());
        let ids: Vec<PersonId> = map.persons.keys().copied().collect();
        for person in ids {
            map.keep_one_current(person, WaIdKind::Pn, true);
            let lid_only = !map.has_number(person);
            map.keep_one_current(person, WaIdKind::Lid, lid_only);
            if let Some(key) = map.person_key(person)
                && let Some(entry) = map.persons.get_mut(&person)
            {
                entry.key = key;
            }
        }
        map
    }

    /// Every row of the map, persons by id and aliases by kind and user.
    #[must_use]
    pub fn rows(&self) -> (Vec<PersonRow>, Vec<AliasRow>) {
        let persons = self
            .persons
            .iter()
            .map(|(id, person)| PersonRow {
                person_id: *id,
                key: person.key.clone(),
            })
            .collect();
        let aliases = self.aliases.keys().filter_map(|id| self.row(id)).collect();
        (persons, aliases)
    }

    /// How many persons the map holds.
    #[must_use]
    pub fn len(&self) -> usize {
        self.persons.len()
    }

    /// Whether the map holds no person.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.persons.is_empty()
    }

    /// Resolves an id as it arrives from the gateway, with or without
    /// device.
    ///
    /// A phone number or LID seen for the first time becomes a person of its
    /// own (its rows are in [`Resolved::changes`]). Every other kind resolves
    /// to itself: no person, the key is its bare id. `None` when the text is
    /// not an id.
    pub fn resolve(&mut self, text: &str) -> Option<Resolved> {
        let parsed = parse(text)?;
        let mut tx = Tx::default();
        let person_id = alias_kind(&parsed.id).map(|_| self.ensure(&mut tx, &parsed.id));
        let changes = self.finish(tx);
        Some(self.resolved(parsed.id, parsed.device, person_id, changes))
    }

    /// [`IdentityMap::resolve`] without creating anything: a number or LID
    /// the map has not seen has no person and keeps its own bare id as key.
    #[must_use]
    pub fn lookup(&self, text: &str) -> Option<Resolved> {
        let parsed = parse(text)?;
        let person_id = self.aliases.get(&parsed.id).map(|alias| alias.person);
        Some(self.resolved(parsed.id, parsed.device, person_id, Changes::default()))
    }

    /// Learns that `lid` and `pn` are one person, as `source` said at
    /// `at_ms` (Unix milliseconds). Both are JIDs, device allowed.
    ///
    /// - Neither is known: one new person with both.
    /// - One is known: the other joins its person. A person that was keyed
    ///   by its LID is rekeyed to the number (a [`MergeStep`]).
    /// - Both are the same person: the stronger source and time are kept.
    /// - Both are known and differ, the LID person has no number: it merges
    ///   into the number's person (a [`MergeStep`]).
    /// - The LID belongs to a person with another number: the pair must beat
    ///   the source and time the LID was learned with. A number change makes
    ///   the old number a former alias of the one person (the two persons
    ///   merge when the new number had one). Any other accepted source moves
    ///   only the LID to the new number's person; nothing is merged or
    ///   un-merged. A weaker pair changes nothing and is listed in
    ///   [`Changes::rejected`].
    /// - The number already has another current LID: both LIDs stay aliases
    ///   of the person, and the stronger or newer one is the current one.
    /// - The number is a former number of the LID's person: nothing changes.
    ///
    /// The same call repeated returns empty [`Changes`].
    #[must_use]
    pub fn learn(&mut self, lid: &str, pn: &str, source: Source, at_ms: i64) -> Changes {
        let mut tx = Tx::default();
        match (
            person_id_of(lid, WaIdKind::Lid),
            person_id_of(pn, WaIdKind::Pn),
        ) {
            (Some(lid_id), Some(pn_id)) => {
                self.learn_pair(&mut tx, &lid_id, &pn_id, Claim::new(source, at_ms));
            }
            _ => tx.reject(lid, pn, source, RejectReason::Invalid),
        }
        self.finish(tx)
    }

    /// A contact moved from `old_pn` to `new_pn`.
    ///
    /// The person keeps one identity: `new_pn` becomes the current number
    /// and `old_pn` a former alias, so old rows still resolve. When both
    /// numbers were persons they merge into the new number's person. Each
    /// key change is a [`MergeStep`] with [`MergeReason::NumberChange`].
    ///
    /// `new_lid` becomes the person's current LID. `old_lid`, when it
    /// differs, stays as a former alias; given alone (or equal to
    /// `new_lid`) it is the unchanged LID and stays current.
    #[must_use]
    pub fn number_changed(
        &mut self,
        old_pn: &str,
        new_pn: &str,
        old_lid: Option<&str>,
        new_lid: Option<&str>,
        at_ms: i64,
    ) -> Changes {
        let mut tx = Tx::default();
        let claim = Claim::new(Source::NumberChange, at_ms);
        let lid_of = |text: Option<&str>| text.map(|t| person_id_of(t, WaIdKind::Lid));
        let (Some(old), Some(new), Some(old_lid_id), Some(new_lid_id)) = (
            person_id_of(old_pn, WaIdKind::Pn),
            person_id_of(new_pn, WaIdKind::Pn),
            lid_of(old_lid).map_or(Some(None), |id| id.map(Some)),
            lid_of(new_lid).map_or(Some(None), |id| id.map(Some)),
        ) else {
            let lid = new_lid.or(old_lid).unwrap_or_default();
            tx.reject(lid, new_pn, Source::NumberChange, RejectReason::Invalid);
            return self.finish(tx);
        };
        if old != new {
            self.move_number(&mut tx, &old, &new, claim);
        }
        match (old_lid_id, new_lid_id) {
            (Some(kept), None) => self.learn_pair(&mut tx, &kept, &new, claim),
            (old_lid_id, Some(current)) => {
                self.learn_pair(&mut tx, &current, &new, claim);
                if let Some(earlier) = old_lid_id.filter(|id| *id != current) {
                    self.attach_former_lid(&mut tx, &earlier, &new, claim);
                }
            }
            (None, None) => {}
        }
        self.finish(tx)
    }

    /// Sets the account's own number and LID (JIDs, device allowed; an
    /// unreadable one counts as unknown). Both become one person, whatever
    /// the map held before, and no later pair can join either to someone
    /// else.
    #[must_use]
    pub fn set_own(&mut self, pn: Option<&str>, lid: Option<&str>) -> Changes {
        self.own_pn = pn.and_then(|text| person_id_of(text, WaIdKind::Pn));
        self.own_lid = lid.and_then(|text| person_id_of(text, WaIdKind::Lid));
        let mut tx = Tx::default();
        let force = Claim {
            source: Source::Other,
            at_ms: 0,
            force: true,
        };
        match (self.own_pn.clone(), self.own_lid.clone()) {
            (Some(pn), Some(lid)) => self.learn_pair(&mut tx, &lid, &pn, force),
            (Some(id), None) | (None, Some(id)) => {
                self.ensure(&mut tx, &id);
            }
            (None, None) => {}
        }
        self.finish(tx)
    }

    /// The account's own person, once [`IdentityMap::set_own`] named it.
    #[must_use]
    pub fn me(&self) -> Option<PersonId> {
        [&self.own_pn, &self.own_lid]
            .into_iter()
            .flatten()
            .find_map(|id| self.aliases.get(id))
            .map(|alias| alias.person)
    }

    /// Whether the id is the account itself: its number or LID, current or
    /// former, under any device.
    #[must_use]
    pub fn is_me(&self, text: &str) -> bool {
        let Some(id) = parse(text).map(|p| p.id) else {
            return false;
        };
        if self.own_pn.as_ref() == Some(&id) || self.own_lid.as_ref() == Some(&id) {
            return true;
        }
        let person = self.aliases.get(&id).map(|alias| alias.person);
        person.is_some() && person == self.me()
    }

    /// The key a person's rows are stored under.
    #[must_use]
    pub fn key(&self, person: PersonId) -> Option<&IdentityKey> {
        self.persons.get(&person).map(|p| &p.key)
    }

    /// The person's numbers as `+<digits>`: the current one first, then
    /// former ones, newest first. For matching device contacts.
    #[must_use]
    pub fn numbers(&self, person: PersonId) -> Vec<String> {
        let mut rows: Vec<AliasRow> = self
            .aliases(person)
            .into_iter()
            .filter(|row| row.kind == AliasKind::Pn)
            .collect();
        rows.sort_by(|a, b| {
            (a.state, std::cmp::Reverse(a.learned_ms))
                .cmp(&(b.state, std::cmp::Reverse(b.learned_ms)))
        });
        rows.into_iter()
            .map(|row| format!("+{}", row.user))
            .collect()
    }

    /// The person's alias rows, numbers before LIDs.
    #[must_use]
    pub fn aliases(&self, person: PersonId) -> Vec<AliasRow> {
        self.persons
            .get(&person)
            .into_iter()
            .flat_map(|p| &p.aliases)
            .filter_map(|id| self.row(id))
            .collect()
    }

    fn resolved(
        &self,
        wire: WaId,
        device: Option<u16>,
        person_id: Option<PersonId>,
        changes: Changes,
    ) -> Resolved {
        let key = person_id
            .and_then(|id| self.key(id).cloned())
            .unwrap_or_else(|| IdentityKey::from(&wire));
        Resolved {
            person_id,
            key,
            kind: wire.kind(),
            wire,
            device,
            changes,
        }
    }

    fn row(&self, id: &WaId) -> Option<AliasRow> {
        let alias = self.aliases.get(id)?;
        Some(AliasRow {
            kind: alias_kind(id)?,
            user: id.user().to_owned(),
            person_id: alias.person,
            state: alias.state,
            source: alias.source,
            learned_ms: alias.learned_ms,
        })
    }

    fn finish(&self, tx: Tx) -> Changes {
        Changes {
            persons: tx
                .persons
                .iter()
                .filter_map(|id| {
                    Some(PersonRow {
                        person_id: *id,
                        key: self.key(*id)?.clone(),
                    })
                })
                .collect(),
            aliases: tx.aliases.iter().filter_map(|id| self.row(id)).collect(),
            removed_persons: tx.removed.into_iter().collect(),
            merges: tx.merges,
            rejected: tx.rejected,
        }
    }

    fn owner(&self, id: &WaId) -> Option<PersonId> {
        self.aliases.get(id).map(|alias| alias.person)
    }

    fn of_kind(&self, person: PersonId, kind: WaIdKind) -> impl Iterator<Item = (&WaId, &Alias)> {
        self.persons
            .get(&person)
            .into_iter()
            .flat_map(|p| &p.aliases)
            .filter(move |id| id.kind() == kind)
            .filter_map(|id| Some((id, self.aliases.get(id)?)))
    }

    fn current(&self, person: PersonId, kind: WaIdKind) -> Option<WaId> {
        self.of_kind(person, kind)
            .find(|(_, alias)| alias.state == AliasState::Current)
            .map(|(id, _)| id.clone())
    }

    fn has_number(&self, person: PersonId) -> bool {
        self.of_kind(person, WaIdKind::Pn).next().is_some()
    }

    /// The canonical key, from the current aliases.
    fn person_key(&self, person: PersonId) -> Option<IdentityKey> {
        canonical_key(
            self.current(person, WaIdKind::Pn).as_ref(),
            self.current(person, WaIdKind::Lid).as_ref(),
        )
    }

    /// Repairs loaded rows: of several current aliases of a kind the
    /// strongest stays current, and the strongest becomes current when
    /// `needs_one` and none is.
    fn keep_one_current(&mut self, person: PersonId, kind: WaIdKind, needs_one: bool) {
        let ranked = |state| {
            let mut ids: Vec<(u8, i64, WaId)> = self
                .of_kind(person, kind)
                .filter(|(_, alias)| alias.state == state)
                .map(|(id, alias)| (alias.source.rank(), alias.learned_ms, id.clone()))
                .collect();
            ids.sort();
            ids
        };
        let mut current = ranked(AliasState::Current);
        let promoted = match current.pop() {
            Some(_) => None,
            None if needs_one => ranked(AliasState::Former).pop(),
            None => None,
        };
        for (_, _, id) in current {
            if let Some(alias) = self.aliases.get_mut(&id) {
                alias.state = AliasState::Former;
            }
        }
        if let Some((_, _, id)) = promoted
            && let Some(alias) = self.aliases.get_mut(&id)
        {
            alias.state = AliasState::Current;
        }
    }

    /// The person of an id, created with that id alone when unseen.
    fn ensure(&mut self, tx: &mut Tx, id: &WaId) -> PersonId {
        match self.owner(id) {
            Some(person) => person,
            None => self.add_person(tx, id, Claim::new(Source::Other, 0)),
        }
    }

    fn add_person(&mut self, tx: &mut Tx, first: &WaId, claim: Claim) -> PersonId {
        let person = PersonId(self.next_id.max(1));
        self.next_id = person.0 + 1;
        self.persons.insert(
            person,
            Person {
                key: IdentityKey::from(first),
                aliases: BTreeSet::new(),
            },
        );
        tx.persons.insert(person);
        self.put(tx, first, person, AliasState::Current, claim);
        person
    }

    /// Writes an alias: creates it, moves it to `person`, sets its state and
    /// raises its source and time when the claim is stronger.
    fn put(&mut self, tx: &mut Tx, id: &WaId, person: PersonId, state: AliasState, claim: Claim) {
        let mut moved_from = None;
        let changed = match self.aliases.get_mut(id) {
            Some(alias) => {
                let before = alias.clone();
                if alias.person != person {
                    moved_from = Some(alias.person);
                    alias.person = person;
                }
                alias.state = state;
                if claim.strength() > (alias.source.rank(), alias.learned_ms) {
                    alias.source = claim.source;
                    alias.learned_ms = claim.at_ms;
                }
                *alias != before
            }
            None => {
                self.aliases.insert(
                    id.clone(),
                    Alias {
                        person,
                        state,
                        source: claim.source,
                        learned_ms: claim.at_ms,
                    },
                );
                true
            }
        };
        if let Some(old) = moved_from
            && let Some(entry) = self.persons.get_mut(&old)
        {
            entry.aliases.remove(id);
        }
        if let Some(entry) = self.persons.get_mut(&person) {
            entry.aliases.insert(id.clone());
        }
        if changed {
            tx.aliases.insert(id.clone());
        }
    }

    /// Puts an alias on `person` and decides whether it or the person's
    /// current alias of the same kind is the current one.
    fn place(&mut self, tx: &mut Tx, id: &WaId, person: PersonId, claim: Claim) {
        let rival = self.current(person, id.kind()).filter(|c| c != id);
        let state = match rival {
            None => AliasState::Current,
            Some(rival) => {
                if self.aliases.get(&rival).is_some_and(|a| claim.beats(a)) {
                    self.put(tx, &rival, person, AliasState::Former, Claim::KEEP);
                    AliasState::Current
                } else {
                    AliasState::Former
                }
            }
        };
        self.put(tx, id, person, state, claim);
    }

    /// Recomputes a person's key; a change is a merge step.
    fn rekey(&mut self, tx: &mut Tx, person: PersonId, reason: MergeReason) {
        let Some(key) = self.person_key(person) else {
            return;
        };
        let Some(entry) = self.persons.get_mut(&person) else {
            return;
        };
        if entry.key == key {
            return;
        }
        let from_key = std::mem::replace(&mut entry.key, key.clone());
        tx.persons.insert(person);
        tx.merges.push(MergeStep {
            from_key,
            into_key: key,
            reason,
        });
    }

    /// Moves every alias of `from` to `into` and removes `from`. An alias
    /// that was current becomes former when `into` has a current one.
    fn merge(&mut self, tx: &mut Tx, from: PersonId, into: PersonId, reason: MergeReason) {
        let Some(gone) = self.persons.remove(&from) else {
            return;
        };
        for id in &gone.aliases {
            let Some(mut state) = self.aliases.get(id).map(|alias| alias.state) else {
                continue;
            };
            if self.current(into, id.kind()).is_some() {
                state = AliasState::Former;
            }
            self.put(tx, id, into, state, Claim::KEEP);
        }
        tx.persons.remove(&from);
        tx.removed.insert(from);
        self.rekey(tx, into, reason);
        if let Some(into_key) = self.key(into).filter(|key| **key != gone.key) {
            tx.merges.push(MergeStep {
                from_key: gone.key,
                into_key: into_key.clone(),
                reason,
            });
        }
    }

    /// Whether the pair would join an own id to someone else's.
    fn splits_own(&self, lid: &WaId, pn: &WaId) -> bool {
        let own_lid = self.own_lid.as_ref() == Some(lid);
        let own_pn = self.own_pn.as_ref() == Some(pn);
        (own_lid && self.own_pn.is_some() && !own_pn)
            || (own_pn && self.own_lid.is_some() && !own_lid)
    }

    fn learn_pair(&mut self, tx: &mut Tx, lid: &WaId, pn: &WaId, claim: Claim) {
        let reject = |tx: &mut Tx, reason| tx.reject(&lid.bare(), &pn.bare(), claim.source, reason);
        if !claim.force && self.splits_own(lid, pn) {
            reject(tx, RejectReason::OwnId);
            return;
        }
        let number_change = claim.source == Source::NumberChange;
        match (self.owner(lid), self.owner(pn)) {
            (None, None) => {
                let person = self.add_person(tx, pn, claim);
                self.place(tx, lid, person, claim);
            }
            (None, Some(person)) => {
                self.place(tx, lid, person, claim);
                self.touch(tx, pn, claim);
            }
            (Some(person), None) if !self.has_number(person) => {
                self.place(tx, pn, person, claim);
                self.place(tx, lid, person, claim);
                self.rekey(tx, person, MergeReason::Mapped);
            }
            (Some(person), None) => {
                if !self.outranks_link(lid, claim) {
                    reject(tx, RejectReason::Outranked);
                } else if number_change {
                    self.place(tx, pn, person, claim);
                    self.place(tx, lid, person, claim);
                    self.rekey(tx, person, MergeReason::NumberChange);
                } else {
                    let fresh = self.add_person(tx, pn, claim);
                    self.place(tx, lid, fresh, claim);
                }
            }
            (Some(same), Some(person)) if same == person => {
                let former = self
                    .aliases
                    .get(pn)
                    .is_some_and(|alias| alias.state == AliasState::Former);
                if former && !claim.force {
                    // A pair from before a number change: already one person.
                    return;
                }
                self.place(tx, pn, person, claim);
                self.place(tx, lid, person, claim);
                self.rekey(tx, person, MergeReason::Mapped);
            }
            (Some(lid_person), Some(person)) if !self.has_number(lid_person) => {
                self.merge(tx, lid_person, person, MergeReason::Mapped);
                self.place(tx, lid, person, claim);
                self.touch(tx, pn, claim);
            }
            (Some(lid_person), Some(person)) => {
                if !self.outranks_link(lid, claim) {
                    reject(tx, RejectReason::Outranked);
                } else if number_change {
                    self.merge(tx, lid_person, person, MergeReason::NumberChange);
                    self.place(tx, pn, person, claim);
                    self.place(tx, lid, person, claim);
                    self.rekey(tx, person, MergeReason::NumberChange);
                } else {
                    self.place(tx, lid, person, claim);
                    self.touch(tx, pn, claim);
                }
            }
        }
    }

    /// Whether a claim beats the source and time the LID was last linked
    /// with.
    fn outranks_link(&self, lid: &WaId, claim: Claim) -> bool {
        self.aliases.get(lid).is_none_or(|alias| claim.beats(alias))
    }

    /// Raises an alias's source and time, nothing else.
    fn touch(&mut self, tx: &mut Tx, id: &WaId, claim: Claim) {
        if let Some((person, state)) = self.aliases.get(id).map(|a| (a.person, a.state)) {
            self.put(tx, id, person, state, claim);
        }
    }

    /// The number part of a number change.
    fn move_number(&mut self, tx: &mut Tx, old: &WaId, new: &WaId, claim: Claim) {
        let reason = MergeReason::NumberChange;
        let person = match (self.owner(old), self.owner(new)) {
            (None, None) => {
                let person = self.add_person(tx, new, claim);
                self.put(tx, old, person, AliasState::Former, claim);
                return;
            }
            (None, Some(person)) => {
                self.put(tx, old, person, AliasState::Former, claim);
                person
            }
            (Some(person), None) => person,
            (Some(from), Some(into)) => {
                if from != into {
                    self.merge(tx, from, into, reason);
                }
                into
            }
        };
        self.place(tx, new, person, claim);
        self.rekey(tx, person, reason);
    }

    /// Keeps an earlier LID resolving to the person of `pn` after a number
    /// change named a new one.
    fn attach_former_lid(&mut self, tx: &mut Tx, lid: &WaId, pn: &WaId, claim: Claim) {
        let Some(person) = self.owner(pn) else {
            return;
        };
        if self.own_lid.as_ref() == Some(lid) && self.me() != Some(person) {
            tx.reject(&lid.bare(), &pn.bare(), claim.source, RejectReason::OwnId);
            return;
        }
        match self.owner(lid) {
            Some(same) if same == person => {}
            Some(other) if !self.has_number(other) => {
                self.merge(tx, other, person, MergeReason::NumberChange);
            }
            _ => self.put(tx, lid, person, AliasState::Former, claim),
        }
    }
}
