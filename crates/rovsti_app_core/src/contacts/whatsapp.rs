//! WhatsApp on the contact card and in the list (AD-CT6): the parts that decide
//! something, without UI.
//!
//! Contacts never sends the address book to WhatsApp. The list mark comes from
//! the instance's existing chats (`GET /v1/chats`, no lookup). Only when a card
//! opens are that one contact's international numbers (at most
//! [`MAX_NUMBERS`]) looked up, and presence is subscribed for the WhatsApp
//! number. The `impl ContactsApp` half stays in `apps/contacts`.

use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
};

use rovsti_core::{PhoneNumber, search::fold, wire::WireContact};
use rovsti_wa_client::{Gateway, Picture};
use rovsti_wa_proto::{
    PresenceBook, WaProfile, WaTarget, WaUser, presence_label, timeline::user_jid, wa_target,
};

pub const MAX_NUMBERS: usize = 12;

/// Where one contact's WhatsApp lookup stands.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LookupState {
    Checking,
    Done(Vec<WaUser>),
    Failed,
}

/// What the app knows about WhatsApp, for this run.
#[derive(Default)]
pub struct WaPanel {
    /// User parts of the 1:1 chats of the instance.
    pub chat_users: Vec<String>,
    pub book: PresenceBook,
    pub lookups: HashMap<i64, LookupState>,
    /// Contacts whose list row carries the WhatsApp mark, with the JID of
    /// their chat (the list asks for its picture).
    pub marked: HashMap<i64, String>,
    /// JIDs presence was already subscribed for.
    pub subscribed: HashSet<String>,
    /// Whether the gateway link was up at the last check.
    pub connected: bool,
    /// Numbers the chat refreshes, so a slow answer yields to a newer one.
    pub chats_gen: u64,
    /// The phone's offset from UTC, for "last seen" lines.
    pub offset_s: i32,
}

impl WaPanel {
    /// The target of a contact with these dialable numbers.
    pub fn target(&self, numbers: &[String]) -> WaTarget {
        let numbers: Vec<&str> = numbers.iter().map(String::as_str).collect();
        let users: Vec<&str> = self.chat_users.iter().map(String::as_str).collect();
        wa_target(&numbers, &users)
    }

    /// The JIDs to look up for contact `id`: nothing unless the target is a
    /// bare number and no answer (or answer in flight) is held.
    pub fn lookup_request(&self, id: i64, numbers: &[String]) -> Vec<String> {
        if matches!(
            self.lookups.get(&id),
            Some(LookupState::Checking | LookupState::Done(_))
        ) {
            return Vec::new();
        }
        let target = self.target(numbers);
        let refs: Vec<&str> = numbers.iter().map(String::as_str).collect();
        lookup_jids(&target, &refs)
    }

    /// The JID chat and call go to, once known.
    pub fn jid(&self, id: i64, numbers: &[String]) -> Option<String> {
        wa_jid(&self.target(numbers), self.lookups.get(&id))
    }

    /// The JID of one number when it is known to be on WhatsApp: it matches a
    /// 1:1 chat, or contact `id`'s lookup found it registered.
    pub fn jid_for_number(&self, id: i64, number: &str) -> Option<String> {
        number_jid(number, &self.chat_users, self.lookups.get(&id))
    }

    /// The status line of the card, `None` when there is nothing to say.
    pub fn status(&self, id: i64, numbers: &[String], now_ms: i64) -> Option<String> {
        let target = self.target(numbers);
        let jid = wa_jid(&target, self.lookups.get(&id));
        let seen = jid.as_deref().and_then(|j| self.book.get(j));
        status_line(&target, self.lookups.get(&id), seen, now_ms, self.offset_s)
    }

    /// Whether presence of `jid` still has to be subscribed (once per run).
    pub fn take_subscription(&mut self, jid: &str) -> bool {
        self.subscribed.insert(jid.to_owned())
    }
}

/// The JIDs to ask the gateway about: only for a bare-number target, every
/// distinct international number of the contact, at most [`MAX_NUMBERS`].
pub fn lookup_jids(target: &WaTarget, numbers: &[&str]) -> Vec<String> {
    if !matches!(target, WaTarget::Number(_)) {
        return Vec::new();
    }
    let mut jids: Vec<String> = Vec::new();
    for number in numbers {
        if let Ok(jid) = user_jid(number)
            && !jids.contains(&jid)
        {
            jids.push(jid);
            if jids.len() == MAX_NUMBERS {
                break;
            }
        }
    }
    jids
}

/// The JID of the contact on WhatsApp: the matching chat, or the first number
/// the lookup found registered.
pub fn wa_jid(target: &WaTarget, lookup: Option<&LookupState>) -> Option<String> {
    match target {
        WaTarget::Chat(jid) => Some(jid.clone()),
        WaTarget::Number(_) => match lookup {
            Some(LookupState::Done(users)) => {
                users.iter().find(|u| u.on_whatsapp).map(|u| u.jid.clone())
            }
            _ => None,
        },
        WaTarget::NeedsCountryCode | WaTarget::None => None,
    }
}

/// The JID of `number` when it is on WhatsApp: a 1:1 chat with the same
/// number, or a positive answer of the contact's lookup.
pub fn number_jid(
    number: &str,
    chat_users: &[String],
    lookup: Option<&LookupState>,
) -> Option<String> {
    let parsed = PhoneNumber::parse(number).ok()?;
    let chat = chat_users.iter().find(|user| {
        PhoneNumber::parse(&format!("+{user}")).is_ok_and(|chat| parsed.matches(&chat))
    });
    if let Some(user) = chat {
        return Some(format!("{user}@s.whatsapp.net"));
    }
    let wanted = user_jid(parsed.as_str()).ok()?;
    match lookup {
        Some(LookupState::Done(users)) => users
            .iter()
            .find(|u| u.on_whatsapp && u.jid == wanted)
            .map(|u| u.jid.clone()),
        _ => None,
    }
}

/// Which number rows get a WhatsApp chip: those with a JID, and only the
/// first row of each JID.
pub fn wa_chip_index(jids: &[Option<String>]) -> Vec<bool> {
    let mut seen: Vec<&str> = Vec::new();
    jids.iter()
        .map(|jid| match jid.as_deref() {
            Some(j) if !seen.contains(&j) => {
                seen.push(j);
                true
            }
            _ => false,
        })
        .collect()
}

/// The status line of the card's WhatsApp section.
pub fn status_line(
    target: &WaTarget,
    lookup: Option<&LookupState>,
    presence: Option<(bool, Option<i64>)>,
    now_ms: i64,
    offset_s: i32,
) -> Option<String> {
    let on = |presence: Option<(bool, Option<i64>)>| {
        presence
            .and_then(|(available, seen)| presence_label(available, seen, now_ms, offset_s))
            .unwrap_or_else(|| "On WhatsApp".to_owned())
    };
    match target {
        WaTarget::None => None,
        WaTarget::NeedsCountryCode => Some("Add the country code to check WhatsApp".to_owned()),
        WaTarget::Chat(_) => Some(on(presence)),
        WaTarget::Number(_) => Some(match lookup {
            None | Some(LookupState::Checking) => "Checking…".to_owned(),
            Some(LookupState::Failed) => "Can't check WhatsApp right now".to_owned(),
            Some(LookupState::Done(users)) => {
                if users.iter().any(|u| u.on_whatsapp) {
                    on(presence)
                } else {
                    "Not on WhatsApp".to_owned()
                }
            }
        }),
    }
}

/// The contacts that get the WhatsApp mark in the list: some number of theirs
/// is the number of a known 1:1 chat. Each maps to that chat's JID. A number
/// is compared in its E.164 form by the shared caller-ID rules (F39,
/// [`rovsti_core::caller_id::e164_of`]): the provider's `NORMALIZED_NUMBER`,
/// else the number with `home_cc`, so a contact saved as `0532 …` gets the mark
/// of the chat `90532…`. A national number with no known country never does.
pub fn marked_contacts<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    chat_users: &[String],
    home_cc: Option<&str>,
) -> HashMap<i64, String> {
    let chats: HashMap<String, &str> = chat_users
        .iter()
        .filter_map(|user| {
            rovsti_core::caller_id::e164_of(&format!("+{user}"), None, None)
                .map(|key| (key, user.as_str()))
        })
        .collect();
    if chats.is_empty() {
        return HashMap::new();
    }
    contacts
        .into_iter()
        .filter_map(|c| {
            c.numbers
                .iter()
                .filter_map(|n| {
                    rovsti_core::caller_id::e164_of(&n.raw, n.normalized.as_deref(), home_cc)
                })
                .find_map(|key| chats.get(&key).copied())
                .map(|user| (c.id, format!("{user}@s.whatsapp.net")))
        })
        .collect()
}

/// The contact's numbers as the WhatsApp decisions read them: each in its
/// E.164 form when the shared caller-ID rules give one (F39: a national number
/// with the home calling code), else as written, in the same order.
pub fn e164_numbers(numbers: &[String], home_cc: Option<&str>) -> Vec<String> {
    numbers
        .iter()
        .map(|n| rovsti_core::caller_id::e164_of(n, None, home_cc).unwrap_or_else(|| n.clone()))
        .collect()
}

/// What the card says about the person from their WhatsApp profile, as
/// `(label, value)`: the name they set (when it differs from the contact's),
/// the business name, and their About text.
pub fn profile_lines(profile: &WaProfile, contact_name: &str) -> Vec<(&'static str, String)> {
    let text = |value: &Option<String>| {
        value
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
    };
    let mut lines = Vec::new();
    // Folded, so "AYŞE" and "Ayşe" (or "İ" and "i") count as the same name.
    if let Some(push) = text(&profile.push_name)
        && fold(&push) != fold(contact_name.trim())
    {
        lines.push(("WhatsApp name", push));
    }
    if profile.business {
        let name = text(&profile.verified_name).unwrap_or_else(|| "Business account".to_owned());
        lines.push(("Business", name));
    }
    if let Some(about) = text(&profile.about) {
        lines.push(("About", about));
    }
    lines
}

/// The file the large WhatsApp picture of contact `id` is written to.
pub fn wa_photo_path(dir: &Path, id: i64) -> PathBuf {
    dir.join(format!("wa-photo-{id}.jpg"))
}

/// Fetches the large WhatsApp picture of `jid` and writes it under `dir`.
/// Blocking: run it on the background executor. Returns the file's path.
pub fn fetch_wa_photo(gateway: &Gateway, jid: &str, dir: &Path, id: i64) -> Result<String, String> {
    let bytes = match gateway.picture(jid, true, None) {
        Ok(Picture::Image { bytes, .. }) => bytes,
        Ok(Picture::None | Picture::Unchanged) => {
            return Err("they have no WhatsApp photo you can see".to_owned());
        }
        Err(error) => {
            log::warn!("fetching the WhatsApp photo failed: {error}");
            return Err("WhatsApp can't be reached right now".to_owned());
        }
    };
    let path = wa_photo_path(dir, id);
    std::fs::write(&path, &bytes).map_err(|error| {
        log::warn!("writing the WhatsApp photo failed: {:?}", error.kind());
        "the photo can't be stored on this phone".to_owned()
    })?;
    path.into_os_string()
        .into_string()
        .map_err(|_| "the data folder has an unusual name".to_owned())
}

#[cfg(test)]
mod tests {
    use rovsti_core::wire::WireNumber;

    use super::*;

    fn user(jid: &str, on: bool) -> WaUser {
        WaUser {
            jid: jid.into(),
            on_whatsapp: on,
            business: false,
            verified_name: None,
        }
    }

    fn number(target: &str) -> WaTarget {
        WaTarget::Number(target.into())
    }

    const NOW: i64 = 1_700_000_000_000;

    #[test]
    fn status_texts() {
        let n = number("905321234567@s.whatsapp.net");
        let s = |t: &WaTarget, l: Option<&LookupState>, p| status_line(t, l, p, NOW, 0);
        assert_eq!(s(&WaTarget::None, None, None), None);
        assert_eq!(
            s(&WaTarget::NeedsCountryCode, None, None).as_deref(),
            Some("Add the country code to check WhatsApp")
        );
        assert_eq!(s(&n, None, None).as_deref(), Some("Checking…"));
        assert_eq!(
            s(&n, Some(&LookupState::Checking), None).as_deref(),
            Some("Checking…")
        );
        assert_eq!(
            s(&n, Some(&LookupState::Failed), None).as_deref(),
            Some("Can't check WhatsApp right now")
        );
        let no = LookupState::Done(vec![user("905321234567@s.whatsapp.net", false)]);
        assert_eq!(s(&n, Some(&no), None).as_deref(), Some("Not on WhatsApp"));
        let yes = LookupState::Done(vec![user("905321234567@s.whatsapp.net", true)]);
        assert_eq!(s(&n, Some(&yes), None).as_deref(), Some("On WhatsApp"));
        assert_eq!(
            s(&n, Some(&yes), Some((true, None))).as_deref(),
            Some("online")
        );
        // A presence without a last-seen time says nothing more.
        assert_eq!(
            s(&n, Some(&yes), Some((false, None))).as_deref(),
            Some("On WhatsApp")
        );
        let chat = WaTarget::Chat("905321234567@s.whatsapp.net".into());
        assert_eq!(s(&chat, None, None).as_deref(), Some("On WhatsApp"));
    }

    #[test]
    fn last_seen_uses_the_local_offset() {
        let chat = WaTarget::Chat("1@s.whatsapp.net".into());
        let seen = Some((false, Some(NOW - 60_000)));
        let utc = status_line(&chat, None, seen, NOW, 0).expect("a line");
        let plus3 = status_line(&chat, None, seen, NOW, 10_800).expect("a line");
        assert!(utc.starts_with("last seen"), "{utc}");
        assert_ne!(utc, plus3);
    }

    #[test]
    fn jid_comes_from_the_chat_or_the_registered_number() {
        let chat = WaTarget::Chat("1@s.whatsapp.net".into());
        assert_eq!(wa_jid(&chat, None).as_deref(), Some("1@s.whatsapp.net"));
        let n = number("2@s.whatsapp.net");
        assert_eq!(wa_jid(&n, None), None);
        let done = LookupState::Done(vec![
            user("2@s.whatsapp.net", false),
            user("3@s.whatsapp.net", true),
        ]);
        assert_eq!(wa_jid(&n, Some(&done)).as_deref(), Some("3@s.whatsapp.net"));
        assert_eq!(wa_jid(&WaTarget::NeedsCountryCode, None), None);
    }

    fn contact(id: i64, numbers: &[&str]) -> WireContact {
        WireContact {
            id,
            numbers: numbers
                .iter()
                .map(|n| WireNumber {
                    raw: (*n).into(),
                    label: None,
                    default: false,
                    normalized: None,
                })
                .collect(),
            ..Default::default()
        }
    }

    #[test]
    fn lid_chats_never_mark_a_contact() {
        let chat = |jid: &str| rovsti_wa_proto::ChatSummary {
            jid: jid.into(),
            name: None,
            last: None,
            unread: 0,
            is_group: false,
        };
        let users = rovsti_wa_proto::chat_users(&[chat("96413459456093@lid")]);
        let c = contact(1, &["+96413459456093"]);
        assert!(marked_contacts([&c], &users, None).is_empty());
        let users = rovsti_wa_proto::chat_users(&[chat("96413459456093@s.whatsapp.net")]);
        assert_eq!(marked_contacts([&c], &users, None).len(), 1);
    }

    #[test]
    fn lookup_asks_for_one_contact_and_caps_numbers() {
        let panel = WaPanel::default();
        let many: Vec<String> = (0..20).map(|i| format!("+9053212345{i:02}")).collect();
        let jids = panel.lookup_request(1, &many);
        assert_eq!(jids.len(), MAX_NUMBERS);
        // A local number mixed in is never sent.
        let mixed = vec!["0532 123 45 67".to_owned(), "+90 532 123 45 68".to_owned()];
        assert_eq!(
            panel.lookup_request(1, &mixed),
            vec!["905321234568@s.whatsapp.net".to_owned()]
        );
        // The same number twice is sent once.
        let twice = vec!["+90 532 123 45 68".to_owned(), "+905321234568".to_owned()];
        assert_eq!(panel.lookup_request(1, &twice).len(), 1);
    }

    #[test]
    fn nothing_is_sent_without_an_international_number_or_with_an_answer() {
        let mut panel = WaPanel::default();
        let local = vec!["0532 123 45 67".to_owned()];
        assert!(panel.lookup_request(1, &local).is_empty());
        assert!(panel.lookup_request(1, &[]).is_empty());
        let intl = vec!["+905321234567".to_owned()];
        assert_eq!(panel.lookup_request(1, &intl).len(), 1);
        panel.lookups.insert(1, LookupState::Checking);
        assert!(panel.lookup_request(1, &intl).is_empty());
        panel.lookups.insert(1, LookupState::Done(Vec::new()));
        assert!(panel.lookup_request(1, &intl).is_empty());
        panel.lookups.insert(1, LookupState::Failed);
        assert_eq!(panel.lookup_request(1, &intl).len(), 1);
    }

    #[test]
    fn a_known_chat_needs_no_lookup() {
        let panel = WaPanel {
            chat_users: vec!["905321234567".into()],
            ..Default::default()
        };
        let numbers = vec!["0532 123 45 67".to_owned()];
        assert!(panel.lookup_request(1, &numbers).is_empty());
        assert_eq!(
            panel.jid(1, &numbers).as_deref(),
            Some("905321234567@s.whatsapp.net")
        );
    }

    #[test]
    fn number_jid_needs_a_chat_or_a_positive_lookup() {
        let chats = vec!["905321234567".to_owned()];
        assert_eq!(
            number_jid("+90 532 123 45 67", &chats, None).as_deref(),
            Some("905321234567@s.whatsapp.net")
        );
        assert_eq!(number_jid("+905321234568", &chats, None), None);
        let found = LookupState::Done(vec![WaUser {
            jid: "905321234568@s.whatsapp.net".into(),
            on_whatsapp: true,
            business: false,
            verified_name: None,
        }]);
        assert_eq!(
            number_jid("+905321234568", &[], Some(&found)).as_deref(),
            Some("905321234568@s.whatsapp.net")
        );
        assert_eq!(number_jid("+905321234569", &[], Some(&found)), None);
        assert_eq!(number_jid("not a number", &chats, Some(&found)), None);
    }

    #[test]
    fn one_chip_per_jid_and_only_for_whatsapp_numbers() {
        let a = Some("1@s.whatsapp.net".to_owned());
        let b = Some("2@s.whatsapp.net".to_owned());
        assert_eq!(
            wa_chip_index(&[a.clone(), None, a.clone(), b]),
            vec![true, false, false, true]
        );
        assert!(wa_chip_index(&[]).is_empty());
    }

    #[test]
    fn subscription_happens_once_per_jid() {
        let mut panel = WaPanel::default();
        assert!(panel.take_subscription("1@s.whatsapp.net"));
        assert!(!panel.take_subscription("1@s.whatsapp.net"));
        assert!(panel.take_subscription("2@s.whatsapp.net"));
    }

    #[test]
    fn list_mark_goes_to_contacts_with_a_matching_chat() {
        let contacts = [
            contact(1, &["+90 532 123 45 67"]),
            contact(2, &["0532 123 45 67"]),
            contact(3, &["+44 20 7946 0958"]),
            contact(4, &[]),
        ];
        let marked = marked_contacts(&contacts, &["905321234567".to_owned()], None);
        assert_eq!(
            marked.get(&1).map(String::as_str),
            Some("905321234567@s.whatsapp.net")
        );
        // No home country known: a national number has no E.164 form to compare.
        assert!(!marked.contains_key(&2));
        assert!(!marked.contains_key(&3));
        assert!(!marked.contains_key(&4));
        assert!(marked_contacts(&contacts, &[], None).is_empty());
    }

    #[test]
    fn a_nationally_saved_contact_gets_its_chat_with_the_home_code() {
        // F39: the owner's contact is saved as `0532 …`; its chat is `90532…`.
        let mut provider = contact(5, &["532-123-4567"]);
        provider.numbers[0].normalized = Some("+905321234567".into());
        let contacts = [
            contact(2, &["0532 123 45 67"]),
            contact(6, &["0532 123 45 68"]),
            contact(7, &["Private"]),
            provider,
        ];
        let users = ["905321234567".to_owned()];
        let marked = marked_contacts(&contacts, &users, Some("90"));
        let jid = Some("905321234567@s.whatsapp.net");
        assert_eq!(marked.get(&2).map(String::as_str), jid);
        assert_eq!(marked.get(&5).map(String::as_str), jid);
        assert!(!marked.contains_key(&6));
        assert!(!marked.contains_key(&7));
        // The provider's own E.164 form needs no home code.
        assert_eq!(
            marked_contacts(&contacts, &users, None)
                .get(&5)
                .map(String::as_str),
            jid
        );
        assert_eq!(
            e164_numbers(&["0532 123 45 67".into(), "112".into()], Some("90")),
            vec!["+905321234567".to_owned(), "112".to_owned()]
        );
    }

    #[test]
    fn profile_lines_skip_what_says_nothing_new() {
        let profile = WaProfile {
            jid: "1@s.whatsapp.net".into(),
            push_name: Some(" ayse ".into()),
            about: Some("Hey there".into()),
            ..WaProfile::default()
        };
        assert_eq!(
            profile_lines(&profile, "Ayşe Yılmaz"),
            vec![
                ("WhatsApp name", "ayse".to_owned()),
                ("About", "Hey there".to_owned())
            ]
        );
        // The same name as the contact is not repeated.
        assert_eq!(
            profile_lines(&profile, "AYSE"),
            vec![("About", "Hey there".to_owned())]
        );
        // Turkish capitals are the same name too.
        let turkish = WaProfile {
            push_name: Some("AYŞE İNCE".into()),
            ..profile.clone()
        };
        assert_eq!(
            profile_lines(&turkish, "Ayşe ince"),
            vec![("About", "Hey there".to_owned())]
        );
        let business = WaProfile {
            business: true,
            verified_name: Some("Ayşe Bakery".into()),
            about: Some("  ".into()),
            ..WaProfile::default()
        };
        assert_eq!(
            profile_lines(&business, "Ayşe"),
            vec![("Business", "Ayşe Bakery".to_owned())]
        );
        let unnamed = WaProfile {
            business: true,
            ..WaProfile::default()
        };
        assert_eq!(
            profile_lines(&unnamed, "Ayşe"),
            vec![("Business", "Business account".to_owned())]
        );
    }

    #[test]
    fn the_photo_file_is_named_per_contact() {
        let dir = Path::new("data");
        assert_ne!(wa_photo_path(dir, 1), wa_photo_path(dir, 2));
        assert!(wa_photo_path(dir, 7).starts_with(dir));
    }
}
