//! Tests of [`IdentityMap`]: resolving, learning, number changes, own ids
//! and the invariants.

use std::collections::{BTreeMap, BTreeSet};

use super::*;

const P1: &str = "905550000001@s.whatsapp.net";
const P2: &str = "905550000002@s.whatsapp.net";
const P3: &str = "905550000003@s.whatsapp.net";
const L1: &str = "111@lid";
const L2: &str = "222@lid";
const L3: &str = "333@lid";

fn pid(map: &IdentityMap, text: &str) -> PersonId {
    map.lookup(text)
        .and_then(|r| r.person_id)
        .unwrap_or_else(|| panic!("{text} has a person"))
}

fn key(map: &IdentityMap, text: &str) -> String {
    map.lookup(text).expect("parses").key.as_str().to_owned()
}

fn alias(map: &IdentityMap, text: &str) -> AliasRow {
    let id = parse(text).expect("parses").id;
    let kind = match id.kind() {
        WaIdKind::Pn => AliasKind::Pn,
        WaIdKind::Lid => AliasKind::Lid,
        other => panic!("{text} is a {other:?}, not a person"),
    };
    let (_, aliases) = map.rows();
    aliases
        .into_iter()
        .find(|row| row.kind == kind && row.user == id.user())
        .unwrap_or_else(|| panic!("{text} is an alias"))
}

fn state(map: &IdentityMap, text: &str) -> AliasState {
    alias(map, text).state
}

fn step(from: &str, into: &str, reason: MergeReason) -> MergeStep {
    MergeStep {
        from_key: IdentityKey::from_stored(from),
        into_key: IdentityKey::from_stored(into),
        reason,
    }
}

/// The map without person ids: per key, its aliases with state, source and
/// time.
type Shape = BTreeMap<String, BTreeSet<(String, String, String, String, i64)>>;

fn shape(map: &IdentityMap) -> Shape {
    let (persons, aliases) = map.rows();
    let keys: BTreeMap<PersonId, String> = persons
        .into_iter()
        .map(|p| (p.person_id, p.key.as_str().to_owned()))
        .collect();
    let mut shape = Shape::new();
    for row in aliases {
        shape
            .entry(keys[&row.person_id].clone())
            .or_default()
            .insert((
                row.kind.as_str().to_owned(),
                row.user,
                row.state.as_str().to_owned(),
                row.source.as_str().to_owned(),
                row.learned_ms,
            ));
    }
    shape
}

/// The invariants of the table.
fn check(map: &IdentityMap) {
    let (persons, aliases) = map.rows();
    let ids: BTreeSet<PersonId> = persons.iter().map(|p| p.person_id).collect();
    assert_eq!(ids.len(), persons.len(), "person ids are unique");
    let keys: BTreeSet<&str> = persons.iter().map(|p| p.key.as_str()).collect();
    assert_eq!(keys.len(), persons.len(), "keys are unique");
    let mut seen = BTreeSet::new();
    let mut current = BTreeMap::<(PersonId, AliasKind), Vec<String>>::new();
    let mut owned = BTreeSet::new();
    for row in &aliases {
        assert!(
            seen.insert((row.kind, row.user.clone())),
            "an alias belongs to exactly one person: {row:?}"
        );
        assert!(ids.contains(&row.person_id), "alias of a person: {row:?}");
        owned.insert(row.person_id);
        if row.state == AliasState::Current {
            current
                .entry((row.person_id, row.kind))
                .or_default()
                .push(row.user.clone());
        }
    }
    assert_eq!(owned, ids, "every person has an alias");
    for (who, users) in &current {
        assert_eq!(
            users.len(),
            1,
            "one current alias per kind: {who:?} {users:?}"
        );
    }
    for person in &persons {
        let pn = current.get(&(person.person_id, AliasKind::Pn));
        let lid = current.get(&(person.person_id, AliasKind::Lid));
        let expected = match (pn, lid) {
            (Some(pn), _) => format!("{}@s.whatsapp.net", pn[0]),
            (None, Some(lid)) => format!("{}@lid", lid[0]),
            (None, None) => panic!("a person has a current alias: {person:?}"),
        };
        assert_eq!(person.key.as_str(), expected, "key is canonical");
        if map
            .aliases(person.person_id)
            .iter()
            .any(|a| a.kind == AliasKind::Pn)
        {
            assert!(pn.is_some(), "a person with numbers has a current one");
        }
    }
    let rebuilt = IdentityMap::from_rows(persons.clone(), aliases.clone());
    assert_eq!(rebuilt.rows(), (persons, aliases), "rows rebuild the map");
}

/// A stand-in for the persistence layer: applies [`Changes`] to row maps.
#[derive(Default)]
struct Db {
    persons: BTreeMap<PersonId, PersonRow>,
    aliases: BTreeMap<(AliasKind, String), AliasRow>,
    log: Vec<MergeStep>,
}

impl Db {
    fn apply(&mut self, changes: Changes) {
        for row in changes.persons {
            self.persons.insert(row.person_id, row);
        }
        for row in changes.aliases {
            self.aliases.insert((row.kind, row.user.clone()), row);
        }
        for id in changes.removed_persons {
            self.persons.remove(&id);
        }
        self.log.extend(changes.merges);
    }

    fn rows(&self) -> (Vec<PersonRow>, Vec<AliasRow>) {
        (
            self.persons.values().cloned().collect(),
            self.aliases.values().cloned().collect(),
        )
    }
}

/// A small deterministic generator (an LCG), enough to shuffle and pick.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self
            .0
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        self.0 >> 33
    }

    fn below(&mut self, n: usize) -> usize {
        usize::try_from(self.next()).expect("fits") % n
    }

    fn shuffle<T>(&mut self, items: &mut [T]) {
        for i in (1..items.len()).rev() {
            items.swap(i, self.below(i + 1));
        }
    }
}

// Resolving

#[test]
fn resolve_creates_a_person_once() {
    let mut map = IdentityMap::new();
    let first = map.resolve(P1).expect("resolves");
    let person = first.person_id.expect("a person");
    assert_eq!(first.key.as_str(), P1);
    assert_eq!(first.kind, WaIdKind::Pn);
    assert_eq!(first.wire.bare(), P1);
    assert_eq!(first.device, None);
    assert_eq!(
        first.changes.persons,
        [PersonRow {
            person_id: person,
            key: IdentityKey::from_stored(P1),
        }]
    );
    assert_eq!(
        first.changes.aliases,
        [AliasRow {
            kind: AliasKind::Pn,
            user: "905550000001".to_owned(),
            person_id: person,
            state: AliasState::Current,
            source: Source::Other,
            learned_ms: 0,
        }]
    );
    let again = map.resolve(P1).expect("resolves");
    assert_eq!(again.person_id, Some(person));
    assert!(again.changes.is_empty());
    assert_eq!(map.len(), 1);
    check(&map);
}

#[test]
fn resolve_is_idempotent_and_ignores_the_device() {
    let mut map = IdentityMap::new();
    let person = map.resolve(P1).expect("resolves").person_id;
    for text in [
        "905550000001:0@s.whatsapp.net",
        "905550000001:12@s.whatsapp.net",
        "905550000001.0:12@s.whatsapp.net",
        "905550000001:99@hosted",
        "905550000001@c.us",
    ] {
        let resolved = map.resolve(text).expect("resolves");
        assert_eq!(resolved.person_id, person, "{text}");
        assert_eq!(resolved.key.as_str(), P1, "{text}");
        assert!(resolved.changes.is_empty(), "{text}");
    }
    let lid = map.resolve("111:7@lid").expect("resolves");
    assert_eq!(lid.device, Some(7));
    assert_eq!(lid.key.as_str(), L1);
    for text in [L1, "111:0@lid", "111:31@lid", "111@hosted.lid"] {
        assert_eq!(
            map.resolve(text).expect("resolves").person_id,
            lid.person_id
        );
    }
    assert_eq!(map.len(), 2);
    check(&map);
}

#[test]
fn equal_digits_as_number_and_lid_are_two_persons() {
    let mut map = IdentityMap::new();
    let pn = map
        .resolve("905550000001@s.whatsapp.net")
        .expect("resolves");
    let lid = map.resolve("905550000001@lid").expect("resolves");
    assert_ne!(pn.person_id, lid.person_id);
    assert_ne!(pn.key, lid.key);
    assert_eq!(lid.key.as_str(), "905550000001@lid");
    check(&map);
}

#[test]
fn kinds_that_are_not_persons_resolve_to_themselves() {
    let mut map = IdentityMap::new();
    for (text, kind, bare) in [
        ("1-2@g.us", WaIdKind::Group, "1-2@g.us"),
        ("status@broadcast", WaIdKind::Status, "status@broadcast"),
        ("17@broadcast", WaIdKind::Broadcast, "17@broadcast"),
        ("x@newsletter", WaIdKind::Newsletter, "x@newsletter"),
        ("1:4@bot", WaIdKind::Bot, "1@bot"),
        ("0@s.whatsapp.net", WaIdKind::Other, "0@s.whatsapp.net"),
        ("abc@example.org", WaIdKind::Other, "abc@example.org"),
    ] {
        let resolved = map.resolve(text).expect("resolves");
        assert_eq!(resolved.person_id, None, "{text}");
        assert_eq!(resolved.kind, kind, "{text}");
        assert_eq!(resolved.key.as_str(), bare, "{text}");
        assert!(resolved.changes.is_empty(), "{text}");
        assert_eq!(map.lookup(text).expect("parses"), resolved, "{text}");
    }
    assert!(map.is_empty());
    assert_eq!(map.resolve("no at sign"), None);
    assert_eq!(map.resolve("abc@s.whatsapp.net"), None);
    assert_eq!(map.lookup(""), None);
}

#[test]
fn lookup_creates_nothing() {
    let map = IdentityMap::new();
    let found = map.lookup("111:3@lid").expect("parses");
    assert_eq!(found.person_id, None);
    assert_eq!(found.key.as_str(), L1);
    assert_eq!(found.device, Some(3));
    assert!(map.is_empty());
}

// Learning

#[test]
fn learn_both_at_once() {
    let mut map = IdentityMap::new();
    let changes = map.learn(L1, P1, Source::Usync, 10);
    assert_eq!(changes.persons.len(), 1);
    assert_eq!(changes.aliases.len(), 2);
    assert!(changes.merges.is_empty());
    assert!(changes.rejected.is_empty());
    assert_eq!(pid(&map, L1), pid(&map, P1));
    assert_eq!(key(&map, "111:5@lid"), P1);
    assert_eq!(alias(&map, L1).source, Source::Usync);
    assert_eq!(alias(&map, P1).learned_ms, 10);
    assert_eq!(map.len(), 1);
    check(&map);
}

#[test]
fn learn_lid_then_pn_rekeys_the_lid_person() {
    let mut map = IdentityMap::new();
    let person = map.resolve(L1).expect("resolves").person_id;
    let changes = map.learn(L1, P1, Source::PeerMessage, 10);
    assert_eq!(changes.merges, [step(L1, P1, MergeReason::Mapped)]);
    assert!(changes.removed_persons.is_empty());
    assert_eq!(
        changes.persons,
        [PersonRow {
            person_id: person.expect("a person"),
            key: IdentityKey::from_stored(P1),
        }]
    );
    assert_eq!(map.resolve(P1).expect("resolves").person_id, person);
    assert_eq!(key(&map, L1), P1);
    check(&map);
}

#[test]
fn learn_pn_then_lid_attaches_without_a_merge() {
    let mut map = IdentityMap::new();
    let person = map.resolve(P1).expect("resolves").person_id;
    let changes = map.learn(L1, P1, Source::PeerMessage, 10);
    assert!(changes.merges.is_empty());
    assert!(changes.persons.is_empty());
    assert_eq!(changes.aliases.len(), 2, "the LID and the stronger source");
    assert_eq!(map.resolve(L1).expect("resolves").person_id, person);
    assert_eq!(state(&map, L1), AliasState::Current);
    check(&map);
}

#[test]
fn learn_merges_the_lid_person_into_the_number_person() {
    let mut map = IdentityMap::new();
    let lid_person = map
        .resolve(L1)
        .expect("resolves")
        .person_id
        .expect("a person");
    let pn_person = map
        .resolve(P1)
        .expect("resolves")
        .person_id
        .expect("a person");
    let changes = map.learn(
        "111:4@lid",
        "905550000001:2@s.whatsapp.net",
        Source::Receipt,
        10,
    );
    assert_eq!(changes.merges, [step(L1, P1, MergeReason::Mapped)]);
    assert_eq!(changes.removed_persons, [lid_person]);
    assert!(changes.persons.is_empty(), "the survivor keeps its key");
    assert_eq!(pid(&map, L1), pn_person);
    assert_eq!(map.len(), 1);
    check(&map);
}

#[test]
fn learn_is_idempotent() {
    let mut map = IdentityMap::new();
    let _ = map.resolve(L1);
    let _ = map.resolve(P1);
    for (lid, pn, source, at) in [
        (L1, P1, Source::Receipt, 10),
        (L2, P2, Source::History, 20),
        (L2, P1, Source::Usync, 30),
        (L3, P2, Source::Other, 5),
    ] {
        assert!(!map.learn(lid, pn, source, at).is_empty());
        let before = map.clone();
        assert!(map.learn(lid, pn, source, at).is_empty(), "{lid} {pn}");
        assert_eq!(map, before);
        check(&map);
    }
}

#[test]
fn learn_keeps_the_stronger_source_and_time() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::Usync, 10);
    assert!(map.learn(L1, P1, Source::History, 99).is_empty());
    assert!(map.learn(L1, P1, Source::Usync, 5).is_empty());
    let changes = map.learn(L1, P1, Source::Usync, 20);
    assert_eq!(changes.aliases.len(), 2);
    assert_eq!(alias(&map, L1).learned_ms, 20);
    let _ = map.learn(L1, P1, Source::PeerMessage, 1);
    assert_eq!(alias(&map, L1).source, Source::PeerMessage);
    assert_eq!(alias(&map, P1).source, Source::PeerMessage);
    assert_eq!(alias(&map, P1).learned_ms, 1);
}

#[test]
fn learn_rejects_ids_of_the_wrong_kind() {
    let mut map = IdentityMap::new();
    for (lid, pn) in [
        (P1, L1),
        (L1, "1-2@g.us"),
        ("", P1),
        (L1, "0@s.whatsapp.net"),
        ("111", P1),
        (L1, "abc@s.whatsapp.net"),
    ] {
        let changes = map.learn(lid, pn, Source::Usync, 1);
        assert_eq!(
            changes.rejected,
            [Rejected {
                lid: lid.to_owned(),
                pn: pn.to_owned(),
                source: Source::Usync,
                reason: RejectReason::Invalid,
            }]
        );
        assert!(changes.persons.is_empty() && changes.aliases.is_empty());
    }
    assert!(map.is_empty());
}

#[test]
fn shuffled_pairs_give_one_result() {
    const N: usize = 12;
    let pairs: Vec<(String, String, Source, i64)> = (0..N)
        .map(|i| {
            (
                format!("{}@lid", 1000 + i),
                format!("9055500001{i:02}@s.whatsapp.net"),
                Source::ALL[i % Source::ALL.len()],
                i64::try_from(i).expect("fits") * 7,
            )
        })
        .collect();
    let mut reference = IdentityMap::new();
    for (lid, pn, source, at) in &pairs {
        let _ = reference.learn(lid, pn, *source, *at);
    }
    assert_eq!(reference.len(), N);
    let expected = shape(&reference);
    let mut rng = Rng(7);
    for round in 0..40 {
        // Each pair as: the LID seen alone, the number seen alone, the pair,
        // and the pair once more, all shuffled together.
        let mut ops: Vec<(usize, u8)> =
            (0..N).flat_map(|i| (0..4).map(move |op| (i, op))).collect();
        rng.shuffle(&mut ops);
        let mut map = IdentityMap::new();
        let mut db = Db::default();
        for (i, op) in ops {
            let (lid, pn, source, at) = &pairs[i];
            match op {
                0 => db.apply(
                    map.resolve(&format!("{}:3@lid", 1000 + i))
                        .expect("resolves")
                        .changes,
                ),
                1 => db.apply(map.resolve(pn).expect("resolves").changes),
                _ => db.apply(map.learn(lid, pn, *source, *at)),
            }
        }
        check(&map);
        assert_eq!(shape(&map), expected, "round {round}");
        assert_eq!(db.rows(), map.rows(), "round {round}");
        for merge in &db.log {
            assert_eq!(merge.reason, MergeReason::Mapped);
            assert!(merge.from_key.as_str().ends_with("@lid"));
            assert!(merge.into_key.as_str().ends_with("@s.whatsapp.net"));
        }
    }
}

// Conflicts

fn accepted(new: Source, new_at: i64, old: Source, old_at: i64) -> bool {
    (new.rank(), new_at) >= (old.rank(), old_at)
}

/// Same LID, new number: every source pair, the new pair newer and older.
#[test]
fn conflict_matrix_same_lid_new_number() {
    for old in Source::ALL {
        for new in Source::ALL {
            for new_at in [50, 100, 200] {
                let label = format!("{old:?} then {new:?} at {new_at}");
                let mut map = IdentityMap::new();
                let _ = map.learn(L1, P1, old, 100);
                let before = map.clone();
                let changes = map.learn(L1, P2, new, new_at);
                check(&map);
                if !accepted(new, new_at, old, 100) {
                    assert_eq!(
                        changes,
                        Changes {
                            rejected: vec![Rejected {
                                lid: L1.to_owned(),
                                pn: P2.to_owned(),
                                source: new,
                                reason: RejectReason::Outranked,
                            }],
                            ..Changes::default()
                        },
                        "{label}"
                    );
                    assert_eq!(map, before, "{label}");
                    assert_eq!(map.lookup(P2).expect("parses").person_id, None, "{label}");
                } else if new == Source::NumberChange {
                    assert!(changes.rejected.is_empty(), "{label}");
                    assert_eq!(
                        changes.merges,
                        [step(P1, P2, MergeReason::NumberChange)],
                        "{label}"
                    );
                    assert_eq!(map.len(), 1, "{label}");
                    assert_eq!(pid(&map, P1), pid(&map, P2), "{label}");
                    assert_eq!(pid(&map, L1), pid(&map, P2), "{label}");
                    assert_eq!(state(&map, P1), AliasState::Former, "{label}");
                    assert_eq!(state(&map, P2), AliasState::Current, "{label}");
                    assert_eq!(key(&map, P1), P2, "{label}");
                } else {
                    assert!(changes.rejected.is_empty(), "{label}");
                    assert!(
                        changes.merges.is_empty(),
                        "{label}: a re-point is not a merge"
                    );
                    assert!(changes.removed_persons.is_empty(), "{label}");
                    assert_eq!(map.len(), 2, "{label}");
                    assert_ne!(pid(&map, P1), pid(&map, P2), "{label}");
                    assert_eq!(pid(&map, L1), pid(&map, P2), "{label}");
                    assert_eq!(key(&map, L1), P2, "{label}");
                    assert_eq!(key(&map, P1), P1, "{label}");
                    assert_eq!(state(&map, P1), AliasState::Current, "{label}");
                    assert_eq!(state(&map, L1), AliasState::Current, "{label}");
                }
            }
        }
    }
}

/// Same number, new LID: both LIDs stay with the person, rank picks the
/// current one.
#[test]
fn conflict_matrix_same_number_new_lid() {
    for old in Source::ALL {
        for new in Source::ALL {
            for new_at in [50, 100, 200] {
                let label = format!("{old:?} then {new:?} at {new_at}");
                let mut map = IdentityMap::new();
                let _ = map.learn(L1, P1, old, 100);
                let changes = map.learn(L2, P1, new, new_at);
                check(&map);
                assert!(changes.rejected.is_empty(), "{label}");
                assert!(changes.merges.is_empty(), "{label}");
                assert_eq!(map.len(), 1, "{label}");
                assert_eq!(pid(&map, L1), pid(&map, P1), "{label}");
                assert_eq!(pid(&map, L2), pid(&map, P1), "{label}");
                assert_eq!(key(&map, L2), P1, "{label}");
                let (first, second) = if accepted(new, new_at, old, 100) {
                    (AliasState::Former, AliasState::Current)
                } else {
                    (AliasState::Current, AliasState::Former)
                };
                assert_eq!(state(&map, L1), first, "{label}");
                assert_eq!(state(&map, L2), second, "{label}");
                assert!(map.learn(L2, P1, new, new_at).is_empty(), "{label}");
            }
        }
    }
}

#[test]
fn accepted_number_change_merges_two_number_persons() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::PeerMessage, 10);
    let old_person = pid(&map, P1);
    let new_person = map
        .resolve(P2)
        .expect("resolves")
        .person_id
        .expect("a person");
    let changes = map.learn(L1, P2, Source::NumberChange, 20);
    assert_eq!(changes.merges, [step(P1, P2, MergeReason::NumberChange)]);
    assert_eq!(changes.removed_persons, [old_person]);
    assert_eq!(pid(&map, P1), new_person);
    assert_eq!(pid(&map, L1), new_person);
    assert_eq!(state(&map, P1), AliasState::Former);
    assert_eq!(state(&map, L1), AliasState::Current);
    check(&map);
    // The old pair replayed after the change does not flip it back.
    assert!(map.learn(L1, P1, Source::NumberChange, 20).is_empty());
    assert!(map.learn(L1, P1, Source::PeerMessage, 99).is_empty());
    assert_eq!(key(&map, L1), P2);
}

#[test]
fn accepted_repoint_moves_only_the_lid() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::History, 10);
    let _ = map.learn(L2, P2, Source::Usync, 10);
    let changes = map.learn(L1, P2, Source::PeerMessage, 20);
    assert!(changes.merges.is_empty());
    assert!(changes.persons.is_empty());
    assert_eq!(map.len(), 2);
    assert_eq!(pid(&map, L1), pid(&map, P2));
    assert_eq!(state(&map, L1), AliasState::Current);
    assert_eq!(state(&map, L2), AliasState::Former);
    assert_eq!(map.aliases(pid(&map, P1)).len(), 1);
    check(&map);
}

#[test]
fn stale_lid_stays_an_alias() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::PeerMessage, 10);
    let _ = map.learn(L2, P1, Source::PeerMessage, 20);
    assert_eq!(state(&map, L1), AliasState::Former);
    assert_eq!(state(&map, L2), AliasState::Current);
    assert_eq!(map.resolve("111:9@lid").expect("resolves").key.as_str(), P1);
    // A second LID that already was a person merges in.
    let _ = map.resolve(L3);
    let changes = map.learn(L3, P1, Source::Other, 30);
    assert_eq!(changes.merges, [step(L3, P1, MergeReason::Mapped)]);
    assert_eq!(state(&map, L3), AliasState::Former);
    check(&map);
}

// Number change

#[test]
fn number_change_with_unchanged_lid() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::PeerMessage, 10);
    let person = pid(&map, P1);
    for (old_lid, new_lid) in [(Some(L1), Some(L1)), (Some(L1), None), (None, Some(L1))] {
        let mut map = map.clone();
        let changes = map.number_changed(P1, P2, old_lid, new_lid, 50);
        assert_eq!(changes.merges, [step(P1, P2, MergeReason::NumberChange)]);
        assert!(changes.removed_persons.is_empty());
        assert!(changes.rejected.is_empty());
        assert_eq!(map.len(), 1);
        for text in [P1, P2, L1] {
            assert_eq!(pid(&map, text), person);
            assert_eq!(key(&map, text), P2);
        }
        assert_eq!(state(&map, P1), AliasState::Former);
        assert_eq!(state(&map, P2), AliasState::Current);
        assert_eq!(state(&map, L1), AliasState::Current);
        assert_eq!(alias(&map, P2).source, Source::NumberChange);
        assert_eq!(map.numbers(person), ["+905550000002", "+905550000001"]);
        check(&map);
        assert!(map.number_changed(P1, P2, old_lid, new_lid, 50).is_empty());
    }
}

#[test]
fn number_change_with_a_new_lid() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::PeerMessage, 10);
    let person = pid(&map, P1);
    let changes = map.number_changed(P1, P2, Some(L1), Some(L2), 50);
    assert_eq!(changes.merges, [step(P1, P2, MergeReason::NumberChange)]);
    assert_eq!(map.len(), 1);
    for text in [P1, P2, L1, L2] {
        assert_eq!(pid(&map, text), person, "{text}");
    }
    assert_eq!(state(&map, L1), AliasState::Former);
    assert_eq!(state(&map, L2), AliasState::Current);
    check(&map);
    assert!(
        map.number_changed(P1, P2, Some(L1), Some(L2), 50)
            .is_empty()
    );
}

#[test]
fn number_change_with_a_new_lid_when_both_sides_had_chats() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::PeerMessage, 10);
    let _ = map.learn(L2, P2, Source::PeerMessage, 20);
    let old_person = pid(&map, P1);
    let new_person = pid(&map, P2);
    let changes = map.number_changed(P1, P2, Some(L1), Some(L2), 50);
    assert_eq!(changes.merges, [step(P1, P2, MergeReason::NumberChange)]);
    assert_eq!(changes.removed_persons, [old_person]);
    for text in [P1, P2, L1, L2] {
        assert_eq!(pid(&map, text), new_person, "{text}");
    }
    assert_eq!(state(&map, L1), AliasState::Former);
    assert_eq!(state(&map, L2), AliasState::Current);
    assert_eq!(state(&map, P1), AliasState::Former);
    check(&map);
}

#[test]
fn number_change_without_lids() {
    // Both numbers were persons with chats: they merge into the new one.
    let mut map = IdentityMap::new();
    let old_person = map
        .resolve(P1)
        .expect("resolves")
        .person_id
        .expect("a person");
    let new_person = map
        .resolve(P2)
        .expect("resolves")
        .person_id
        .expect("a person");
    let changes = map.number_changed(P1, P2, None, None, 50);
    assert_eq!(changes.merges, [step(P1, P2, MergeReason::NumberChange)]);
    assert_eq!(changes.removed_persons, [old_person]);
    assert_eq!(pid(&map, P1), new_person);
    assert_eq!(
        map.resolve("905550000001:4@s.whatsapp.net")
            .expect("resolves")
            .key
            .as_str(),
        P2
    );
    check(&map);

    // Only the old number was known: its person is rekeyed.
    let mut map = IdentityMap::new();
    let person = map
        .resolve(P1)
        .expect("resolves")
        .person_id
        .expect("a person");
    let changes = map.number_changed(P1, P2, None, None, 50);
    assert_eq!(changes.merges, [step(P1, P2, MergeReason::NumberChange)]);
    assert_eq!(pid(&map, P2), person);
    check(&map);

    // Only the new number was known: the old one becomes a former alias.
    let mut map = IdentityMap::new();
    let person = map
        .resolve(P2)
        .expect("resolves")
        .person_id
        .expect("a person");
    let changes = map.number_changed(P1, P2, None, None, 50);
    assert!(changes.merges.is_empty());
    assert_eq!(pid(&map, P1), person);
    assert_eq!(state(&map, P1), AliasState::Former);
    check(&map);

    // Neither was known: one person, no step.
    let mut map = IdentityMap::new();
    let changes = map.number_changed(P1, P2, None, None, 50);
    assert!(changes.merges.is_empty());
    assert_eq!(map.len(), 1);
    assert_eq!(key(&map, P1), P2);
    check(&map);
}

#[test]
fn number_change_twice_keeps_every_number() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::Usync, 10);
    let _ = map.number_changed(P1, P2, Some(L1), Some(L1), 50);
    let changes = map.number_changed(P2, P3, Some(L1), Some(L1), 90);
    assert_eq!(changes.merges, [step(P2, P3, MergeReason::NumberChange)]);
    let person = pid(&map, P3);
    assert_eq!(
        map.numbers(person),
        ["+905550000003", "+905550000002", "+905550000001"]
    );
    assert_eq!(key(&map, P1), P3);
    check(&map);
    // An older change replayed late does not win over the newer one.
    assert!(map.number_changed(P1, P2, None, None, 50).merges.is_empty());
    assert_eq!(key(&map, P2), P3);
    check(&map);
}

#[test]
fn number_change_rejects_unreadable_ids() {
    let mut map = IdentityMap::new();
    for (old, new, old_lid, new_lid) in [
        ("x", P2, None, None),
        (P1, L2, None, None),
        (P1, P2, Some(P3), None),
        (P1, P2, None, Some("1-2@g.us")),
    ] {
        let changes = map.number_changed(old, new, old_lid, new_lid, 1);
        assert_eq!(changes.rejected.len(), 1);
        assert_eq!(changes.rejected[0].reason, RejectReason::Invalid);
        assert_eq!(changes.rejected[0].source, Source::NumberChange);
    }
    assert!(map.is_empty());
}

// Own ids

#[test]
fn own_number_and_lid_are_me_under_any_device() {
    let mut map = IdentityMap::new();
    let changes = map.set_own(Some("905550000001:3@s.whatsapp.net"), Some("111:3@lid"));
    assert_eq!(changes.persons.len(), 1);
    let me = map.me().expect("me");
    for device in [0_u16, 1, 7, 42, 99] {
        for text in [
            format!("905550000001:{device}@s.whatsapp.net"),
            format!("905550000001.0:{device}@s.whatsapp.net"),
            format!("111:{device}@lid"),
        ] {
            assert!(map.is_me(&text), "{text}");
            let resolved = map.resolve(&text).expect("resolves");
            assert_eq!(resolved.person_id, Some(me), "{text}");
            assert_eq!(resolved.key.as_str(), P1, "{text}");
            assert_eq!(resolved.device, Some(device), "{text}");
        }
    }
    assert!(map.is_me(P1) && map.is_me(L1) && map.is_me("905550000001@c.us"));
    assert!(!map.is_me(P2));
    assert!(
        !map.is_me("905550000001@lid"),
        "the number's digits as a LID"
    );
    assert!(
        !map.is_me("111@s.whatsapp.net"),
        "the LID's digits as a number"
    );
    assert!(!map.is_me("1-2@g.us") && !map.is_me(""));
    assert!(map.set_own(Some(P1), Some(L1)).is_empty());
    check(&map);
}

#[test]
fn own_ids_join_what_was_stored_apart() {
    let mut map = IdentityMap::new();
    let _ = map.resolve(L1);
    let _ = map.resolve(P1);
    let changes = map.set_own(Some(P1), Some(L1));
    assert_eq!(changes.merges, [step(L1, P1, MergeReason::Mapped)]);
    assert_eq!(map.len(), 1);
    assert_eq!(map.me(), Some(pid(&map, L1)));
    check(&map);
}

#[test]
fn own_pair_overrides_a_wrong_mapping() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P2, Source::NumberChange, 999);
    let changes = map.set_own(Some(P1), Some(L1));
    assert!(changes.rejected.is_empty());
    assert_eq!(pid(&map, L1), pid(&map, P1));
    assert_ne!(pid(&map, P2), pid(&map, P1));
    assert!(map.is_me(L1) && !map.is_me(P2));
    check(&map);
}

#[test]
fn no_pair_joins_an_own_id_to_someone_else() {
    let mut map = IdentityMap::new();
    let _ = map.set_own(Some(P1), Some(L1));
    let before = map.clone();
    for (lid, pn) in [(L1, P2), (L2, P1)] {
        for source in Source::ALL {
            let changes = map.learn(lid, pn, source, i64::MAX);
            assert_eq!(changes.rejected.len(), 1, "{lid} {pn} {source:?}");
            assert_eq!(changes.rejected[0].reason, RejectReason::OwnId);
            assert_eq!(map, before);
        }
    }
    assert!(
        map.learn(L1, P1, Source::PeerMessage, 5)
            .rejected
            .is_empty()
    );
    assert!(map.is_me(L1));
}

#[test]
fn number_change_never_takes_the_own_lid() {
    let mut map = IdentityMap::new();
    let _ = map.set_own(Some(P1), Some(L1));
    let changes = map.number_changed(P2, P3, Some(L1), Some(L2), 50);
    assert_eq!(changes.rejected.len(), 1);
    assert_eq!(changes.rejected[0].reason, RejectReason::OwnId);
    assert_eq!(pid(&map, P2), pid(&map, P3));
    assert_eq!(pid(&map, L2), pid(&map, P3));
    assert_eq!(map.me(), Some(pid(&map, L1)));
    assert_ne!(map.me(), Some(pid(&map, P3)));
    check(&map);
}

#[test]
fn own_lid_is_learned_later() {
    // An old gateway names the number only; the LID arrives as a pair.
    let mut map = IdentityMap::new();
    let _ = map.set_own(Some(P1), None);
    assert!(map.is_me(P1));
    assert!(!map.is_me(L1));
    let _ = map.learn(L1, P1, Source::DeviceNotification, 5);
    assert!(map.is_me("111:2@lid"));
    assert_eq!(map.me(), Some(pid(&map, L1)));
    // Unreadable own ids count as unknown.
    let mut map = IdentityMap::new();
    assert!(map.set_own(Some("x"), Some(P1)).is_empty());
    assert_eq!(map.me(), None);
    assert!(!map.is_me(P1));
}

// Rows and changes

#[test]
fn stored_names() {
    for kind in [AliasKind::Pn, AliasKind::Lid] {
        assert_eq!(AliasKind::from_stored(kind.as_str()), Some(kind));
        let json = serde_json::to_string(&kind).expect("serializes");
        assert_eq!(json, format!("\"{}\"", kind.as_str()));
    }
    for state in [AliasState::Current, AliasState::Former] {
        assert_eq!(AliasState::from_stored(state.as_str()), Some(state));
        let json = serde_json::to_string(&state).expect("serializes");
        assert_eq!(json, format!("\"{}\"", state.as_str()));
    }
    for reason in [MergeReason::Mapped, MergeReason::NumberChange] {
        assert_eq!(MergeReason::from_stored(reason.as_str()), Some(reason));
        let json = serde_json::to_string(&reason).expect("serializes");
        assert_eq!(json, format!("\"{}\"", reason.as_str()));
    }
    assert_eq!(AliasKind::from_stored("group"), None);
    assert_eq!(AliasState::from_stored(""), None);
    assert_eq!(MergeReason::from_stored("split"), None);
}

#[test]
fn from_rows_rebuilds_and_continues_ids() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::Usync, 10);
    let _ = map.resolve(L2);
    let _ = map.number_changed(P1, P2, None, None, 20);
    let (persons, aliases) = map.rows();
    let mut rebuilt = IdentityMap::from_rows(persons, aliases);
    assert_eq!(rebuilt.rows(), map.rows());
    let taken: BTreeSet<PersonId> = map.rows().0.iter().map(|p| p.person_id).collect();
    let fresh = rebuilt
        .resolve(P3)
        .expect("resolves")
        .person_id
        .expect("a person");
    assert!(!taken.contains(&fresh));
    assert_eq!(Some(fresh), map.resolve(P3).expect("resolves").person_id);
}

#[test]
fn from_rows_repairs_bad_rows() {
    let row = |kind, user: &str, person, state, learned_ms| AliasRow {
        kind,
        user: user.to_owned(),
        person_id: PersonId(person),
        state,
        source: Source::Usync,
        learned_ms,
    };
    let persons = vec![
        PersonRow {
            person_id: PersonId(1),
            key: IdentityKey::from_stored("stale@lid"),
        },
        PersonRow {
            person_id: PersonId(2),
            key: IdentityKey::from_stored(P3),
        },
    ];
    let aliases = vec![
        row(AliasKind::Pn, "905550000001", 1, AliasState::Current, 10),
        row(AliasKind::Pn, "905550000002", 1, AliasState::Current, 20),
        row(AliasKind::Pn, "not digits", 1, AliasState::Current, 30),
        row(AliasKind::Lid, "111", 7, AliasState::Former, 5),
    ];
    let map = IdentityMap::from_rows(persons, aliases);
    check(&map);
    assert_eq!(
        map.len(),
        2,
        "the empty person is dropped, the missing one is back"
    );
    assert_eq!(key(&map, P1), P2);
    assert_eq!(state(&map, P1), AliasState::Former);
    assert_eq!(state(&map, P2), AliasState::Current);
    assert_eq!(pid(&map, L1), PersonId(7));
    assert_eq!(key(&map, L1), L1);
    let mut map = map;
    assert_eq!(
        map.resolve(P3).expect("resolves").person_id,
        Some(PersonId(8))
    );
}

#[test]
fn changes_append_keeps_the_latest_rows() {
    let mut map = IdentityMap::new();
    let mut all = Changes::default();
    let mut db = Db::default();
    for changes in [
        map.resolve(L1).expect("resolves").changes,
        map.resolve(P1).expect("resolves").changes,
        map.learn(L1, P1, Source::Usync, 10),
        map.number_changed(P1, P2, None, None, 20),
        map.learn(L1, P3, Source::Other, 1),
    ] {
        all.append(changes);
    }
    assert_eq!(all.persons.len(), 1);
    assert_eq!(all.removed_persons.len(), 1);
    assert_eq!(all.aliases.len(), 3);
    assert_eq!(
        all.merges,
        [
            step(L1, P1, MergeReason::Mapped),
            step(P1, P2, MergeReason::NumberChange),
        ]
    );
    assert_eq!(all.rejected.len(), 1);
    db.apply(all);
    assert_eq!(db.rows(), map.rows());
}

#[test]
fn numbers_and_aliases_of_a_person() {
    let mut map = IdentityMap::new();
    let _ = map.learn(L1, P1, Source::Usync, 10);
    let person = pid(&map, P1);
    assert_eq!(map.numbers(person), ["+905550000001"]);
    assert_eq!(map.aliases(person).len(), 2);
    assert_eq!(map.key(person).map(IdentityKey::as_str), Some(P1));
    let lid_only = map
        .resolve(L2)
        .expect("resolves")
        .person_id
        .expect("a person");
    assert!(
        map.numbers(lid_only).is_empty(),
        "a hidden member has no number"
    );
    assert!(map.numbers(PersonId(999)).is_empty());
    assert_eq!(map.key(PersonId(999)), None);
}

// Invariants under random use

#[test]
fn invariants_hold_under_random_operations() {
    let pns: Vec<String> = (1..=6)
        .map(|i| format!("90555000000{i}@s.whatsapp.net"))
        .collect();
    let lids: Vec<String> = (1..=6).map(|i| format!("90555000000{i}@lid")).collect();
    let mut rng = Rng(42);
    for round in 0..60 {
        let mut map = IdentityMap::new();
        let mut db = Db::default();
        if round % 3 == 0 {
            db.apply(map.set_own(Some(&pns[0]), Some(&lids[0])));
        }
        for at in 0..80_i64 {
            let pn = &pns[rng.below(pns.len())];
            let lid = &lids[rng.below(lids.len())];
            let changes = match rng.below(10) {
                0 | 1 => {
                    let text = if rng.below(2) == 0 { pn } else { lid };
                    let with_device = text.replace('@', &format!(":{}@", rng.below(20)));
                    let first = map.resolve(&with_device).expect("resolves");
                    let again = map.resolve(text).expect("resolves");
                    assert_eq!(first.person_id, again.person_id);
                    assert_eq!(first.key, again.key);
                    assert!(again.changes.is_empty());
                    first.changes
                }
                2 => {
                    let new_pn = &pns[rng.below(pns.len())];
                    let old_lid = (rng.below(2) == 0).then_some(lid.as_str());
                    let new_lid = (rng.below(2) == 0).then(|| lids[rng.below(lids.len())].as_str());
                    let changes = map.number_changed(pn, new_pn, old_lid, new_lid, at);
                    let before = map.clone();
                    let again = map.number_changed(pn, new_pn, old_lid, new_lid, at);
                    assert!(
                        again.persons.is_empty()
                            && again.aliases.is_empty()
                            && again.merges.is_empty()
                    );
                    assert_eq!(map, before, "a number change repeats to nothing");
                    changes
                }
                _ => {
                    let source = Source::ALL[rng.below(Source::ALL.len())];
                    let changes = map.learn(lid, pn, source, at);
                    let before = map.clone();
                    let again = map.learn(lid, pn, source, at);
                    assert!(
                        again.persons.is_empty()
                            && again.aliases.is_empty()
                            && again.merges.is_empty()
                    );
                    assert_eq!(again.rejected, changes.rejected);
                    assert_eq!(map, before, "learn repeats to nothing");
                    changes
                }
            };
            let live: BTreeSet<IdentityKey> = map.rows().0.into_iter().map(|p| p.key).collect();
            for merge in &changes.merges {
                assert_ne!(merge.from_key, merge.into_key);
                assert!(
                    !live.contains(&merge.from_key),
                    "{merge:?} leaves no person behind"
                );
            }
            if let Some(last) = changes.merges.last() {
                assert!(live.contains(&last.into_key), "{last:?} ends on a person");
            }
            db.apply(changes);
            check(&map);
            assert_eq!(db.rows(), map.rows(), "changes rebuild the rows");
            for (pn, lid) in pns.iter().zip(&lids) {
                // Equal digits on both servers are never the same alias.
                let (a, b) = (
                    parse(pn).expect("parses").id,
                    parse(lid).expect("parses").id,
                );
                assert_ne!(a, b);
            }
            if round % 3 == 0 {
                let me = map.me();
                assert!(me.is_some());
                assert_eq!(map.lookup(&pns[0]).expect("parses").person_id, me);
                assert_eq!(map.lookup(&lids[0]).expect("parses").person_id, me);
                assert!(map.is_me(&pns[0].replace('@', ":5@")));
                assert!(map.is_me(&lids[0].replace('@', ":9@")));
            }
        }
    }
}
