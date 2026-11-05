//! Sample address book for desktop runs, where there is no provider to ask.

use rovsti_core::{
    contact_edit::{Account, ContactDraft, DraftField, FieldKind, Group},
    wire::{WireContact, WireNumber},
};

/// The key of the sample Google account (`type|name`).
pub(crate) const GOOGLE_ACCOUNT: &str = "com.google|sample@gmail.com";

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
    // Saved to the Google account with an email only: no number to call.
    all.push(WireContact {
        email: Some("selin.aksoy@example.com".to_owned()),
        ..contact(11, "Selin Aksoy", Vec::new(), false)
    });
    all.sort_by_cached_key(|c| (rovsti_core::search::fold(&c.display_name), c.id));
    all
}

/// The accounts besides the phone: one Google account.
pub(crate) fn accounts() -> Vec<Account> {
    vec![Account {
        key: GOOGLE_ACCOUNT.to_owned(),
        name: "sample@gmail.com".to_owned(),
        kind: "com.google".to_owned(),
    }]
}

/// The sample groups: two on the phone, one in the Google account.
pub(crate) fn groups() -> Vec<Group> {
    vec![
        Group {
            id: 1,
            title: "Family".to_owned(),
            members: vec![1, 4, 6],
            ..Group::default()
        },
        Group {
            id: 2,
            title: "Work".to_owned(),
            members: vec![3, 9],
            ..Group::default()
        },
        Group {
            id: 3,
            title: "Friends".to_owned(),
            members: vec![11],
            account: GOOGLE_ACCOUNT.to_owned(),
        },
    ]
}

/// The editable form of a sample contact, as the provider would return it.
pub(crate) fn draft(id: i64) -> Option<ContactDraft> {
    let wire = contacts().into_iter().find(|c| c.id == id)?;
    let (given, family) = wire
        .display_name
        .rsplit_once(' ')
        .map_or((wire.display_name.as_str(), ""), |(g, f)| (g, f));
    let kind = |label: &Option<String>| match label.as_deref() {
        Some("Home") => FieldKind::Home,
        Some("Work") => FieldKind::Work,
        Some("Mobile") | None => FieldKind::Mobile,
        Some(_) => FieldKind::Other,
    };
    Some(ContactDraft {
        id: Some(wire.id),
        given: given.to_owned(),
        family: family.to_owned(),
        numbers: wire
            .numbers
            .iter()
            .map(|n| DraftField::new(n.raw.clone(), kind(&n.label)))
            .collect(),
        groups: groups()
            .iter()
            .filter(|g| g.members.contains(&id))
            .map(|g| g.id)
            .collect(),
        emails: wire
            .email
            .iter()
            .map(|e| DraftField::new(e.clone(), FieldKind::Home))
            .collect(),
        starred: wire.starred,
        account: groups()
            .iter()
            .find(|g| g.members.contains(&id))
            .map(|g| g.account.clone())
            .unwrap_or_default(),
        ..ContactDraft::default()
    })
}

#[cfg(test)]
mod tests {
    use rovsti_core::contacts_view::{NameStyle, build_rows_styled};

    use super::*;

    #[test]
    fn the_number_less_sample_builds_a_row_with_its_email() {
        let all = contacts();
        let rows = build_rows_styled(&all, "", NameStyle::default());
        let row = rows
            .iter()
            .find(|r| r.name == "Selin Aksoy")
            .expect("the sample has a number-less contact");
        assert!(row.numbers.is_empty());
        assert_eq!(row.subtitle.as_deref(), Some("selin.aksoy@example.com"));
    }

    #[test]
    fn the_number_less_sample_is_a_valid_draft_in_the_google_account() {
        let mut draft = draft(11).expect("sample draft");
        assert_eq!(draft.account, GOOGLE_ACCOUNT);
        assert_eq!(draft.groups, [3]);
        draft.clean();
        assert_eq!(draft.validate(), Ok(()));
    }

    #[test]
    fn the_sample_google_group_belongs_to_the_listed_account() {
        let account = &accounts()[0];
        assert!(groups().iter().any(|g| g.account == account.key));
    }
}
