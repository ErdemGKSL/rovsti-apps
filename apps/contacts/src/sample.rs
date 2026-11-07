//! Sample address book for desktop runs, where there is no provider to ask.

use rovsti_core::{
    contact_edit::{Account, ContactDraft, DraftEvent, DraftField, EventKind, FieldKind, Group},
    wire::{CallLogEntry, CallLogKind, WireContact, WireNumber},
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
        updated_ms: updated_ms(id),
        ..Default::default()
    }
}

/// Fixed "last updated" stamps (ms) for two samples, so the recent list has data.
fn updated_ms(id: i64) -> Option<i64> {
    match id {
        3 => Some(1_760_000_000_000),
        6 => Some(1_762_000_000_000),
        _ => None,
    }
}

/// The sample contacts, sorted by folded name like the provider sorts them.
pub(crate) fn contacts() -> Vec<WireContact> {
    #[cfg(test)]
    if rich::enabled() {
        return rich::contacts();
    }
    base_contacts()
}

fn base_contacts() -> Vec<WireContact> {
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
    // The same number as Burak Demir's, written another way: a duplicate pair.
    all.push(contact(
        12,
        "B. Demir",
        vec![number("+90 532 123 45 67", Some("Mobile"), false)],
        false,
    ));
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
    #[cfg(test)]
    if rich::enabled() {
        return rich::groups();
    }
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

/// Fixed birthdays, an anniversary and a home address for a few samples.
fn dates_and_places(id: i64) -> (Vec<DraftEvent>, Vec<DraftField>) {
    #[cfg(test)]
    if rich::enabled()
        && let Some(found) = rich::dates_and_places(id)
    {
        return found;
    }
    let event = |date: &str, kind| vec![DraftEvent::new(date, kind)];
    match id {
        1 => (
            event("1992-05-12", EventKind::Birthday),
            vec![DraftField::new(
                "Atatürk Cd. 12\nKadıköy, İstanbul",
                FieldKind::Home,
            )],
        ),
        3 => (event("--09-30", EventKind::Birthday), Vec::new()),
        6 => (event("2015-06-21", EventKind::Anniversary), Vec::new()),
        _ => (Vec::new(), Vec::new()),
    }
}

#[allow(dead_code)] // used by the photo viewer on desktop (plan step S5)
/// A sample portrait (a soft diagonal gradient) for desktop runs.
pub(crate) fn photo(size: u32) -> image::RgbaImage {
    image::RgbaImage::from_fn(size, size, |x, y| {
        let t = ((x + y) * 255 / (2 * size.max(1))) as u8;
        image::Rgba([40 + t / 2, 120, 255 - t / 2, 255])
    })
}

/// Sample call log for desktop runs, relative to `now_ms`: Burak Demir (2)
/// and Mehmet Öz (4) are called often, Ayşe (starred) too.
pub(crate) fn calls(now_ms: i64) -> Vec<CallLogEntry> {
    let day = 24 * 60 * 60 * 1000;
    let call = |number: &str, kind: CallLogKind, days_ago: i64| CallLogEntry {
        number_raw: number.to_owned(),
        kind,
        date_ms: now_ms - days_ago * day,
        duration_s: 60,
        ..Default::default()
    };
    vec![
        call("0532 123 45 67", CallLogKind::Outgoing, 1),
        call("0532 123 45 67", CallLogKind::Incoming, 3),
        call("0532 123 45 67", CallLogKind::Missed, 9),
        call("0216 555 01 02", CallLogKind::Outgoing, 2),
        call("0216 555 01 02", CallLogKind::Incoming, 12),
        call("+90 555 111 22 33", CallLogKind::Outgoing, 1),
        call("+90 555 111 22 33", CallLogKind::Outgoing, 2),
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
    let (events, addresses) = dates_and_places(id);
    Some(ContactDraft {
        id: Some(wire.id),
        events,
        addresses,
        org: if id == 1 {
            "Acme Yazılım".to_owned()
        } else {
            String::new()
        },
        nickname: if id == 1 {
            "Ayşecik".to_owned()
        } else {
            String::new()
        },
        title: if id == 1 {
            "Engineer".to_owned()
        } else {
            String::new()
        },
        websites: if id == 1 {
            vec!["https://example.com/ayse".to_owned()]
        } else {
            Vec::new()
        },
        given: given.to_owned(),
        family: family.to_owned(),
        numbers: wire
            .numbers
            .iter()
            .map(|n| DraftField {
                primary: n.default,
                ..DraftField::new(n.raw.clone(), kind(&n.label))
            })
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

/// A larger address book for the screenshot rig and its tests: 40 contacts
/// with Turkish initials (Ç, Ö, Ş, Ü, İ), favourites, groups, a birthday on
/// the shot day and one duplicate pair. Off unless the rig turns it on, so
/// the plain sample the other tests count on does not change.
#[cfg(test)]
pub(crate) mod rich {
    use std::sync::atomic::{AtomicBool, Ordering};

    use super::*;

    static ON: AtomicBool = AtomicBool::new(false);

    /// Whether [`contacts`] and friends answer with the larger book.
    pub(crate) fn enabled() -> bool {
        ON.load(Ordering::Relaxed)
    }

    /// Turns the larger book on (the shots test does, once).
    #[cfg(feature = "shots")]
    pub(crate) fn enable() {
        ON.store(true, Ordering::Relaxed);
    }

    /// The month and day of the shot day (2026-11-07), as `--MM-DD`.
    pub(crate) const BIRTHDAY_TODAY: &str = "--11-07";

    /// Id, name, number, label, starred. Ids 13 and up; 1 to 12 are the plain sample.
    const EXTRA: [(i64, &str, &str, &str, bool); 28] = [
        (13, "Çağla Aksu", "0535 100 20 30", "Mobile", true),
        (14, "Çetin Bozkurt", "0536 200 30 40", "Mobile", false),
        (15, "Ömer Faruk Yıldırım", "0537 300 40 50", "Work", false),
        (16, "Özlem Tuncer", "0539 400 50 60", "Mobile", true),
        (17, "Şebnem Ünal", "0541 500 60 70", "Mobile", false),
        (18, "Şükrü Saraçoğlu", "0542 600 70 80", "Home", false),
        (19, "Ümit Kılıç", "0543 700 80 90", "Mobile", false),
        (20, "Ülkü Başaran", "0545 800 90 10", "Mobile", true),
        (21, "İbrahim Ergin", "0546 900 10 20", "Mobile", false),
        (22, "İrem Doğan", "0547 110 20 30", "Work", false),
        (23, "Alper Tekin", "0548 120 30 40", "Mobile", false),
        (24, "Berrin Sezer", "0549 130 40 50", "Mobile", false),
        (25, "Cem Karaca", "0551 140 50 60", "Mobile", false),
        (26, "Dilara Eren", "0552 150 60 70", "Mobile", true),
        (27, "Emre Polat", "0553 160 70 80", "Work", false),
        (28, "Fatma Nur Çakır", "0554 170 80 90", "Mobile", false),
        (29, "Gökhan Işık", "0555 180 90 10", "Mobile", false),
        (30, "Hülya Avcı", "0556 190 10 20", "Home", false),
        (31, "Kemal Sunal", "0557 210 20 30", "Mobile", false),
        (32, "Leyla Yavuz", "0558 220 30 40", "Mobile", false),
        (33, "Murat Çınar", "0559 230 40 50", "Mobile", false),
        (34, "Nilüfer Şen", "0561 240 50 60", "Mobile", true),
        (35, "Orhan Gencebay", "0562 250 60 70", "Mobile", false),
        (36, "Pelin Güler", "0563 260 70 80", "Work", false),
        (37, "Rüya Özkan", "0564 270 80 90", "Mobile", false),
        (38, "Selim Yüce", "0565 280 90 10", "Mobile", false),
        (39, "Tuğba Işıklı", "0566 290 10 20", "Mobile", false),
        (
            40,
            "Zübeyde Hanım Çiftçioğlu Mühendislik ve Danışmanlık",
            "0567 310 20 30",
            "Work",
            false,
        ),
    ];

    /// The 40 contacts, sorted like the provider sorts them.
    pub(crate) fn contacts() -> Vec<WireContact> {
        let mut all = base_contacts();
        all.extend(EXTRA.iter().map(|&(id, name, raw, label, starred)| {
            contact(id, name, vec![number(raw, Some(label), false)], starred)
        }));
        for c in &mut all {
            c.lookup_key = Some(format!("key-{}", c.id));
        }
        all.sort_by_cached_key(|c| (rovsti_core::search::fold(&c.display_name), c.id));
        all
    }

    /// The plain groups plus a bigger Family and a Neighbours group.
    pub(crate) fn groups() -> Vec<Group> {
        vec![
            Group {
                id: 1,
                title: "Family".to_owned(),
                members: vec![1, 4, 6, 16, 21, 30],
                ..Group::default()
            },
            Group {
                id: 2,
                title: "Work".to_owned(),
                members: vec![3, 9, 15, 22, 27, 36, 40],
                ..Group::default()
            },
            Group {
                id: 3,
                title: "Friends".to_owned(),
                members: vec![11, 13, 17, 25, 26, 34],
                account: GOOGLE_ACCOUNT.to_owned(),
            },
            Group {
                id: 4,
                title: "Komşular".to_owned(),
                members: vec![18, 19, 20],
                ..Group::default()
            },
        ]
    }

    /// Dates for the extra contacts: a birthday today, two soon, an anniversary.
    pub(crate) fn dates_and_places(id: i64) -> Option<(Vec<DraftEvent>, Vec<DraftField>)> {
        let event = |date: &str, kind| vec![DraftEvent::new(date, kind)];
        match id {
            17 => Some((event(BIRTHDAY_TODAY, EventKind::Birthday), Vec::new())),
            13 => Some((event("1990-11-12", EventKind::Birthday), Vec::new())),
            26 => Some((event("--11-21", EventKind::Birthday), Vec::new())),
            34 => Some((event("2012-12-02", EventKind::Anniversary), Vec::new())),
            _ => None,
        }
    }

    #[cfg(test)]
    mod tests {
        use rovsti_core::contact_dupes;

        use super::*;

        #[test]
        fn the_larger_book_has_forty_unique_contacts() {
            let all = contacts();
            assert_eq!(all.len(), 40);
            let mut ids: Vec<i64> = all.iter().map(|c| c.id).collect();
            ids.sort_unstable();
            ids.dedup();
            assert_eq!(ids.len(), 40);
        }

        #[test]
        fn every_turkish_initial_is_present() {
            let all = contacts();
            for initial in ['Ç', 'Ö', 'Ş', 'Ü', 'İ'] {
                assert!(
                    all.iter().any(|c| c.display_name.starts_with(initial)),
                    "no contact starts with {initial}"
                );
            }
        }

        #[test]
        fn it_has_favourites_and_a_birthday_today() {
            let all = contacts();
            assert!(all.iter().filter(|c| c.starred).count() >= 8);
            let today = dates_and_places(17).expect("dates").0;
            assert_eq!(today[0].date, BIRTHDAY_TODAY);
        }

        #[test]
        fn every_group_member_exists_and_one_duplicate_pair_remains() {
            let all = contacts();
            for group in groups() {
                assert!(group.members.iter().all(|m| all.iter().any(|c| c.id == *m)));
            }
            let drafts: Vec<ContactDraft> = all
                .iter()
                .map(|c| {
                    let mut d = ContactDraft::default();
                    let wire = c.clone();
                    d.id = Some(wire.id);
                    d.given = wire.display_name;
                    d.numbers = wire
                        .numbers
                        .iter()
                        .map(|n| DraftField::new(n.raw.clone(), FieldKind::Mobile))
                        .collect();
                    d
                })
                .collect();
            assert_eq!(contact_dupes::find(&drafts).len(), 1);
        }
    }
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

    #[test]
    fn sample_drafts_pass_finish() {
        for c in contacts() {
            let d = draft(c.id).expect("draft");
            assert!(d.finish().is_ok(), "{} should be valid", c.display_name);
        }
    }

    #[test]
    fn sample_dates_parse_and_one_has_no_year() {
        let events: Vec<DraftEvent> = contacts()
            .iter()
            .filter_map(|c| draft(c.id))
            .flat_map(|d| d.events)
            .collect();
        assert!(events.len() >= 3);
        let parsed: Vec<_> = events
            .iter()
            .map(|e| rovsti_core::contact_dates::parse(&e.date).expect("parses"))
            .collect();
        assert!(parsed.iter().any(|d| d.year.is_none()));
        assert!(events.iter().any(|e| e.kind == EventKind::Anniversary));
        assert!(
            contacts()
                .iter()
                .filter_map(|c| draft(c.id))
                .any(|d| !d.addresses.is_empty())
        );
    }

    #[test]
    fn one_sample_has_a_nickname_title_and_website() {
        let d = draft(1).expect("draft");
        assert_eq!(d.nickname, "Ayşecik");
        assert_eq!(d.title, "Engineer");
        assert_eq!(d.websites.len(), 1);
        assert!(draft(2).expect("draft").websites.is_empty());
    }

    #[test]
    fn two_samples_carry_a_fixed_updated_stamp() {
        let stamped: Vec<i64> = contacts().iter().filter_map(|c| c.updated_ms).collect();
        assert_eq!(stamped.len(), 2);
        assert!(stamped.iter().all(|t| *t > 0));
    }

    #[test]
    fn one_sample_contact_has_a_primary_number() {
        let primary = draft(4).expect("sample");
        assert_eq!(
            rovsti_core::contact_edit::primary_of(&primary.numbers),
            Some(1)
        );
        assert!(draft(1).expect("sample").numbers.iter().all(|n| !n.primary));
    }

    #[test]
    fn the_sample_has_exactly_one_duplicate_group() {
        let drafts: Vec<ContactDraft> = contacts().iter().filter_map(|c| draft(c.id)).collect();
        let groups = rovsti_core::contact_dupes::find(&drafts);
        assert_eq!(groups.len(), 1);
    }
}
