//! Names and contacts: a WhatsApp person is matched against the address book by every number it has, the current
//! one first, then the former ones (`Store::identity_numbers` over `rosty_core::caller_id::NumberBook`).

use rosty_core::caller_id::NumberBook;
use rosty_core::wa_identity::Source;
use rosty_core::wire::{WireContact, WireNumber};

use super::Store;
use super::identity_tests::{GROUP, LID, LID3, PN, PN2, store};

fn contact(id: i64, numbers: &[&str]) -> WireContact {
    WireContact {
        id,
        display_name: format!("C{id}"),
        numbers: numbers
            .iter()
            .map(|raw| WireNumber {
                raw: (*raw).to_owned(),
                label: None,
                default: false,
                normalized: None,
            })
            .collect(),
        ..Default::default()
    }
}

/// The contact that names `id`: the owner of the first of the person's numbers anyone owns.
fn named_by(
    store: &Store,
    contacts: &[WireContact],
    home_cc: Option<&str>,
    id: &str,
) -> Option<i64> {
    let book = NumberBook::new(contacts, home_cc);
    store
        .identity_numbers(id)
        .iter()
        .find_map(|number| book.lookup(number, None))
        .map(|at| contacts[at].id)
}

/// A store that knows `LID` is `PN`.
fn known() -> Store {
    let mut store = store();
    store
        .identity_learn(LID, PN, Source::PeerMessage, 1_000)
        .unwrap();
    store
}

/// A store that knows `LID` is `PN`, and that `PN` moved to `PN2`.
fn moved() -> Store {
    let mut store = known();
    store
        .identity_number_changed(PN, PN2, Some(LID), None, 5_000)
        .unwrap();
    store
}

#[test]
fn the_numbers_of_a_person_are_the_current_one_then_the_former_ones() {
    let store = moved();
    for id in [
        LID,
        PN,
        PN2,
        "777000000000001:4@lid",
        "905550000001:2@s.whatsapp.net",
    ] {
        assert_eq!(
            store.identity_numbers(id),
            ["+905550000002", "+905550000001"],
            "{id}"
        );
    }
}

#[test]
fn what_has_no_number_has_no_numbers() {
    let mut store = store();
    // A LID nobody mapped, also once the store has met it, whatever its digits.
    assert!(store.identity_numbers(LID3).is_empty());
    store.identity_resolve(LID3).unwrap();
    assert!(store.identity_numbers(LID3).is_empty());
    assert!(store.identity_numbers("905550000001@lid").is_empty());
    for id in [
        GROUP,
        "status@broadcast",
        "x@newsletter",
        "0@s.whatsapp.net",
        "merhaba",
        "",
    ] {
        assert!(store.identity_numbers(id).is_empty(), "{id:?}");
    }
}

#[test]
fn a_number_the_store_has_not_met_is_its_own_number_and_asking_teaches_nothing() {
    let store = store();
    assert_eq!(
        store.identity_numbers("905550000007:3@s.whatsapp.net"),
        ["+905550000007"]
    );
    let seen = store
        .identity()
        .lookup("905550000007@s.whatsapp.net")
        .unwrap();
    assert!(seen.person_id.is_none());
}

#[test]
fn every_spelling_of_the_number_names_the_person_in_its_home_region() {
    let store = known();
    for raw in [
        "+90 555 000 00 01",
        "0555 000 00 01",
        "00905550000001",
        "555 000 00 01",
        "+90 (0) 555 000 00 01",
    ] {
        let contacts = [contact(1, &[raw])];
        for id in [LID, PN] {
            assert_eq!(
                named_by(&store, &contacts, Some("90"), id),
                Some(1),
                "{raw} {id}"
            );
        }
    }
}

#[test]
fn without_a_home_region_the_last_digits_decide_a_national_spelling() {
    let store = known();
    for raw in [
        "+90 555 000 00 01",
        "00905550000001",
        "+90 (0) 555 000 00 01",
        // No country on the contact's side to rule the match out.
        "0555 000 00 01",
        "555 000 00 01",
    ] {
        let contacts = [contact(1, &[raw])];
        assert_eq!(named_by(&store, &contacts, None, LID), Some(1), "{raw}");
    }
}

#[test]
fn another_country_with_the_same_tail_is_not_the_person() {
    let store = known();
    let abroad = [contact(1, &["+1 555 000 0001"])];
    assert_eq!(named_by(&store, &abroad, Some("90"), LID), None);
    assert_eq!(named_by(&store, &abroad, None, LID), None);
    // The national spelling read in another home region is another line too.
    let national = [contact(2, &["0555 000 00 01"])];
    assert_eq!(named_by(&store, &national, Some("44"), LID), None);
}

#[test]
fn two_contacts_with_the_number_rank_as_the_book_ranks_them() {
    let store = known();
    let mut starred = contact(7, &["0555 000 00 01"]);
    let plain = contact(3, &["+90 555 000 00 01"]);
    assert_eq!(
        named_by(&store, &[starred.clone(), plain.clone()], Some("90"), LID),
        Some(3)
    );
    starred.starred = true;
    assert_eq!(
        named_by(&store, &[starred, plain], Some("90"), LID),
        Some(7)
    );
}

#[test]
fn a_contact_that_only_holds_the_former_number_still_names_the_person() {
    let store = moved();
    let old_only = [contact(1, &["0555 000 00 01"])];
    for id in [LID, PN, PN2] {
        assert_eq!(named_by(&store, &old_only, Some("90"), id), Some(1), "{id}");
    }
    // A contact that holds the new number comes first, whatever the rank of the other.
    let mut old = contact(1, &["0555 000 00 01"]);
    old.starred = true;
    let both = [old, contact(2, &["+90 555 000 00 02"])];
    for id in [LID, PN, PN2] {
        assert_eq!(named_by(&store, &both, Some("90"), id), Some(2), "{id}");
    }
}

#[test]
fn a_hidden_member_matches_no_contact_whatever_its_digits() {
    let store = store();
    // The digits of the LID are a contact's number: a LID is never read as a number.
    let contacts = [contact(1, &["+90 555 000 00 01"])];
    assert_eq!(
        named_by(&store, &contacts, Some("90"), "905550000001@lid"),
        None
    );
}
