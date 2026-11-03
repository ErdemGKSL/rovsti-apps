//! Sample address book for desktop runs, where there is no provider to ask.

use rovsti_core::wire::{WireContact, WireNumber};

fn number(raw: &str, label: Option<&str>, default: bool) -> WireNumber {
    WireNumber {
        raw: raw.to_owned(),
        label: label.map(str::to_owned),
        default,
    }
}

fn contact(id: i64, name: &str, numbers: Vec<WireNumber>, starred: bool) -> WireContact {
    // "Ayşe Yılmaz" to "Yılmaz, Ayşe", as the provider keeps it.
    let alt = name
        .rsplit_once(' ')
        .map(|(given, family)| format!("{family}, {given}"));
    WireContact {
        id,
        display_name: name.to_owned(),
        sort_key: Some(name.to_lowercase()),
        sort_key_alt: alt.as_ref().map(|a| a.to_lowercase()),
        display_name_alt: alt,
        numbers,
        starred,
        ..Default::default()
    }
}

/// The sample contacts, sorted by folded name like the provider sorts them.
pub(crate) fn contacts() -> Vec<WireContact> {
    let mut all = vec![
        contact(
            1,
            "Ayşe Yılmaz",
            vec![number("+90 555 111 22 33", Some("Mobile"), false)],
            true,
        ),
        contact(
            2,
            "Burak Demir",
            vec![number("0532 123 45 67", Some("Mobile"), false)],
            false,
        ),
        contact(
            3,
            "Elif Kaya",
            vec![
                number("+90 212 555 00 11", Some("Work"), false),
                number("0533 765 43 21", None, false),
            ],
            true,
        ),
        contact(
            4,
            "Mehmet Öz",
            vec![
                number("+49 151 234 567 89", Some("Mobile"), false),
                number("0216 555 01 02", Some("Home"), true),
            ],
            false,
        ),
        contact(
            5,
            "Özgür Şahin",
            vec![number("0544 987 65 43", Some("Mobile"), false)],
            true,
        ),
        contact(
            6,
            "Zeynep Arslan",
            vec![number("+1 415 555 0132", Some("Home"), false)],
            false,
        ),
        contact(
            7,
            "Ahmet Çelik",
            vec![number("0505 222 33 44", Some("Mobile"), false)],
            false,
        ),
        contact(
            8,
            "Can Aydın",
            vec![number("0506 333 44 55", Some("Mobile"), false)],
            true,
        ),
        contact(
            9,
            "Deniz Korkmaz",
            vec![number("0507 444 55 66", Some("Work"), false)],
            false,
        ),
        contact(
            10,
            "İpek Güneş",
            vec![number("0538 555 66 77", Some("Mobile"), false)],
            false,
        ),
    ];
    all.sort_by_cached_key(|c| (rovsti_core::search::fold(&c.display_name), c.id));
    all
}
