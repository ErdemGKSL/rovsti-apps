//! The unified inbox: one row per person, folding their SMS thread(s) and
//! WhatsApp chat together (AD-MSG3). Pure; the caller passes the data.

use std::cmp::Reverse;

use crate::PhoneNumber;
use crate::search::ContactIndex;
use crate::sms::SmsThreadRow;
use crate::thread::{Channel, Message, MessageDirection, ThreadId};

/// Who an inbox row is about.
#[derive(Clone, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PersonKey {
    /// `ContactsContract.Contacts._ID`.
    Contact(i64),
    /// No contact: the number itself.
    Number(PhoneNumber),
    /// A group or an unaddressable thread. Never merges with anything.
    Group(ThreadId),
}

/// One conversation as the inbox sees it, whatever channel it is on.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct InboxThread {
    pub id: ThreadId,
    pub last: Message,
    pub unread: u32,
    /// Chat or group name when the channel knows one.
    pub title: Option<String>,
    pub is_group: bool,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct InboxRow {
    pub key: PersonKey,
    pub title: String,
    /// Newest first.
    pub threads: Vec<InboxThread>,
    pub last: Message,
    pub unread: u32,
    /// Distinct channels, in the order of the newest thread of each.
    pub channels: Vec<Channel>,
}

/// The WhatsApp user part of a JID. Mirrors `rovsti_wa_proto::jid_user`, which
/// cannot be used here (that crate depends on this one).
fn jid_user(jid: &str) -> &str {
    let local = jid.split('@').next().unwrap_or(jid);
    local.split(':').next().unwrap_or(local)
}

/// A person with the contact name when one matched.
struct Resolved {
    key: PersonKey,
    name: Option<String>,
}

fn resolve_number(raw: &str, contacts: &ContactIndex) -> Option<Resolved> {
    if let Some(contact) = contacts.match_number(raw) {
        let name = Some(contact.display_name.clone()).filter(|n| !n.trim().is_empty());
        return Some(Resolved {
            key: PersonKey::Contact(contact.id),
            name,
        });
    }
    PhoneNumber::parse(raw).ok().map(|number| Resolved {
        key: PersonKey::Number(number),
        name: None,
    })
}

/// Mirrors `rovsti_wa_proto::is_pn_jid`: a phone-number JID (digits-only user
/// on `s.whatsapp.net`, device suffix allowed).
fn is_pn_jid(jid: &str) -> bool {
    let Some((local, server)) = jid.split_once('@') else {
        return false;
    };
    let user = local.split(':').next().unwrap_or(local);
    server == "s.whatsapp.net" && !user.is_empty() && user.bytes().all(|b| b.is_ascii_digit())
}

fn resolve_jid(jid: &str, contacts: &ContactIndex) -> Option<Resolved> {
    if is_pn_jid(jid) {
        return resolve_number(&format!("+{}", jid_user(jid)), contacts);
    }
    // Groups, `@lid` identities and broadcast lists: never merged by number.
    Some(Resolved {
        key: PersonKey::Group(ThreadId::WhatsApp(jid.to_owned())),
        name: None,
    })
}

/// The person behind a phone number as the provider reports it (national or
/// international). `None` for senders that are not numbers (`"BANK"`).
pub fn person_for_number(raw: &str, contacts: &ContactIndex) -> Option<PersonKey> {
    resolve_number(raw, contacts).map(|r| r.key)
}

/// The person behind a WhatsApp JID; `@g.us` is a [`PersonKey::Group`]. `None`
/// when the JID is not a phone-number JID (a `@lid` identity, a broadcast list).
pub fn person_for_jid(jid: &str, contacts: &ContactIndex) -> Option<PersonKey> {
    if !jid.ends_with("@g.us") && !is_pn_jid(jid) {
        return None;
    }
    resolve_jid(jid, contacts).map(|r| r.key)
}

fn same_person(a: &PersonKey, b: &PersonKey) -> bool {
    match (a, b) {
        (PersonKey::Number(x), PersonKey::Number(y)) => x.matches(y),
        _ => a == b,
    }
}

struct Entry {
    key: PersonKey,
    name: Option<String>,
    threads: Vec<InboxThread>,
}

/// Merges SMS threads and WhatsApp chats into inbox rows, newest first.
///
/// - A thread joins the contact owning its number; without a contact, numbers
///   that [`PhoneNumber::matches`] share a row (`0532…` and `+90532…`).
/// - SMS threads with several recipients, `@g.us` chats and threads whose
///   address is not a number stay alone as [`PersonKey::Group`].
/// - `SmsThreadRow` carries no direction, so the SMS `last` message is a
///   placeholder `Incoming` one (read when `unread == 0`).
pub fn merge(sms: &[SmsThreadRow], wa: &[InboxThread], contacts: &ContactIndex) -> Vec<InboxRow> {
    let mut entries: Vec<Entry> = Vec::new();
    let mut add = |resolved: Resolved, thread: InboxThread| {
        match entries
            .iter_mut()
            .find(|e| same_person(&e.key, &resolved.key))
        {
            Some(entry) => {
                // Prefer the international spelling as the row key.
                if let (PersonKey::Number(have), PersonKey::Number(new)) =
                    (&entry.key, &resolved.key)
                    && !have.is_international()
                    && new.is_international()
                {
                    entry.key = resolved.key;
                }
                entry.threads.push(thread);
            }
            None => entries.push(Entry {
                key: resolved.key,
                name: resolved.name,
                threads: vec![thread],
            }),
        }
    };

    for row in sms {
        let (resolved, thread) = sms_thread(row, contacts);
        add(resolved, thread);
    }
    for thread in wa {
        let resolved = match &thread.id {
            ThreadId::WhatsApp(jid) if !thread.is_group => resolve_jid(jid, contacts),
            _ => None,
        }
        .unwrap_or_else(|| Resolved {
            key: PersonKey::Group(thread.id.clone()),
            name: None,
        });
        add(resolved, thread.clone());
    }

    let mut rows: Vec<InboxRow> = entries.into_iter().map(finish).collect();
    rows.sort_by_key(|r| Reverse(r.last.timestamp_ms));
    rows
}

fn sms_thread(row: &SmsThreadRow, contacts: &ContactIndex) -> (Resolved, InboxThread) {
    let id = ThreadId::Sms(row.thread_id);
    let addresses: Vec<&str> = row
        .addresses
        .iter()
        .map(|a| a.trim())
        .filter(|a| !a.is_empty())
        .collect();
    let people: Vec<Resolved> = addresses
        .iter()
        .filter_map(|a| resolve_number(a, contacts))
        .collect();

    let mut distinct: Vec<&PersonKey> = Vec::new();
    for p in &people {
        if !distinct.iter().any(|k| same_person(k, &p.key)) {
            distinct.push(&p.key);
        }
    }
    let single = addresses.len() == 1 || (distinct.len() == 1 && people.len() == addresses.len());
    let is_group = !single;

    let title = if is_group {
        let names: Vec<String> = addresses
            .iter()
            .map(|a| {
                resolve_number(a, contacts)
                    .and_then(|r| r.name)
                    .unwrap_or_else(|| (*a).to_owned())
            })
            .collect();
        Some(names.join(", ")).filter(|t| !t.is_empty())
    } else {
        None
    };

    let sender = addresses.first().and_then(|a| PhoneNumber::parse(a).ok());
    let last = Message {
        thread: id.clone(),
        direction: MessageDirection::Incoming,
        sender,
        body: row.snippet.clone(),
        timestamp_ms: row.date_ms,
        read: row.unread == 0,
    };
    let resolved = if is_group || people.is_empty() {
        Resolved {
            key: PersonKey::Group(id.clone()),
            name: None,
        }
    } else {
        people.into_iter().next().unwrap_or(Resolved {
            key: PersonKey::Group(id.clone()),
            name: None,
        })
    };
    let title = title.or_else(|| resolved.name.clone());
    let thread = InboxThread {
        id,
        last,
        unread: row.unread,
        title,
        is_group,
    };
    (resolved, thread)
}

fn finish(mut entry: Entry) -> InboxRow {
    // Stable: equal timestamps keep input order.
    entry.threads.sort_by_key(|t| Reverse(t.last.timestamp_ms));
    let last = entry.threads[0].last.clone();
    let unread = entry
        .threads
        .iter()
        .fold(0u32, |sum, t| sum.saturating_add(t.unread));
    let mut channels = Vec::new();
    for thread in &entry.threads {
        let channel = thread.id.channel();
        if !channels.contains(&channel) {
            channels.push(channel);
        }
    }
    let title = entry
        .name
        .clone()
        .or_else(|| {
            entry
                .threads
                .iter()
                .find_map(|t| t.title.clone().filter(|s| !s.trim().is_empty()))
        })
        .unwrap_or_else(|| match &entry.key {
            PersonKey::Number(n) => n.as_str().to_owned(),
            PersonKey::Contact(_) => String::new(),
            PersonKey::Group(ThreadId::WhatsApp(jid)) if jid.ends_with("@g.us") => {
                jid_user(jid).to_owned()
            }
            PersonKey::Group(ThreadId::WhatsApp(_)) => "WhatsApp user".to_owned(),
            PersonKey::Group(ThreadId::Sms(id)) => format!("#{id}"),
        });
    InboxRow {
        key: entry.key,
        title,
        threads: entry.threads,
        last,
        unread,
        channels,
    }
}

/// Where a reply goes by default: the channel of the newest thread.
pub fn preferred_channel(row: &InboxRow) -> Channel {
    row.threads
        .first()
        .map_or_else(|| row.last.thread.channel(), |t| t.id.channel())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::{WireContact, WireNumber};

    fn contact(id: i64, name: &str, number: &str) -> WireContact {
        WireContact {
            id,
            display_name: name.into(),
            numbers: vec![WireNumber {
                raw: number.into(),
                label: None,
                default: false,
            }],
            ..Default::default()
        }
    }

    fn sms(id: i64, addrs: &[&str], date: i64, unread: u32) -> SmsThreadRow {
        SmsThreadRow {
            thread_id: id,
            addresses: addrs.iter().map(|s| (*s).into()).collect(),
            snippet: format!("sms {id}"),
            date_ms: date,
            unread,
        }
    }

    fn wa(jid: &str, date: i64, unread: u32, group: bool) -> InboxThread {
        InboxThread {
            id: ThreadId::WhatsApp(jid.into()),
            last: Message {
                thread: ThreadId::WhatsApp(jid.into()),
                direction: MessageDirection::Incoming,
                sender: None,
                body: "wa".into(),
                timestamp_ms: date,
                read: unread == 0,
            },
            unread,
            title: group.then(|| "Team".to_owned()),
            is_group: group,
        }
    }

    fn index(contacts: Vec<WireContact>) -> ContactIndex {
        ContactIndex::new(contacts)
    }

    #[test]
    fn sms_and_whatsapp_share_a_contact() {
        let idx = index(vec![contact(1, "Ayse", "+905321234567")]);
        let rows = merge(
            &[sms(10, &["+905321234567"], 100, 1)],
            &[wa("905321234567@s.whatsapp.net", 200, 2, false)],
            &idx,
        );
        assert_eq!(rows.len(), 1);
        let row = &rows[0];
        assert_eq!(row.key, PersonKey::Contact(1));
        assert_eq!(row.title, "Ayse");
        assert_eq!(row.unread, 3);
        assert_eq!(row.threads.len(), 2);
        assert_eq!(row.last.timestamp_ms, 200);
        assert_eq!(row.channels, vec![Channel::WhatsApp, Channel::Sms]);
        assert_eq!(preferred_channel(row), Channel::WhatsApp);
    }

    #[test]
    fn national_number_matches_international_chat_without_contact() {
        let idx = index(vec![]);
        let rows = merge(
            &[sms(10, &["05321234567"], 300, 0)],
            &[wa("905321234567@s.whatsapp.net", 200, 0, false)],
            &idx,
        );
        assert_eq!(rows.len(), 1);
        assert_eq!(
            rows[0].key,
            PersonKey::Number(PhoneNumber::parse("+905321234567").expect("number"))
        );
        assert_eq!(rows[0].title, "+905321234567");
        assert_eq!(preferred_channel(&rows[0]), Channel::Sms);
    }

    #[test]
    fn national_number_matches_contact() {
        let idx = index(vec![contact(4, "Can", "+905321234567")]);
        assert_eq!(
            person_for_number("0532 123 45 67", &idx),
            Some(PersonKey::Contact(4))
        );
        assert_eq!(
            person_for_jid("905321234567@s.whatsapp.net", &idx),
            Some(PersonKey::Contact(4))
        );
    }

    #[test]
    fn groups_stay_separate() {
        let idx = index(vec![contact(1, "Ayse", "+905321234567")]);
        let rows = merge(
            &[
                sms(10, &["+905321234567"], 100, 0),
                sms(11, &["+905321234567", "+905559876543"], 150, 1),
            ],
            &[
                wa("905321234567@s.whatsapp.net", 120, 0, false),
                wa("120363@g.us", 130, 4, true),
            ],
            &idx,
        );
        assert_eq!(rows.len(), 3);
        let keys: Vec<_> = rows.iter().map(|r| r.key.clone()).collect();
        assert!(keys.contains(&PersonKey::Contact(1)));
        assert!(keys.contains(&PersonKey::Group(ThreadId::Sms(11))));
        assert!(keys.contains(&PersonKey::Group(ThreadId::WhatsApp("120363@g.us".into()))));
        let sms_group = rows
            .iter()
            .find(|r| r.key == PersonKey::Group(ThreadId::Sms(11)));
        assert!(sms_group.is_some_and(|r| r.threads[0].is_group));
        assert_eq!(
            person_for_jid("120363@g.us", &idx),
            Some(PersonKey::Group(ThreadId::WhatsApp("120363@g.us".into())))
        );
    }

    #[test]
    fn ordering_and_unread_sums() {
        let idx = index(vec![contact(1, "A", "+905321111111")]);
        let rows = merge(
            &[
                sms(1, &["+905321111111"], 10, 2),
                sms(2, &["+905322222222"], 50, 0),
                sms(3, &["+905321111111"], 5, 3),
            ],
            &[wa("905323333333@s.whatsapp.net", 30, 7, false)],
            &idx,
        );
        let order: Vec<i64> = rows.iter().map(|r| r.last.timestamp_ms).collect();
        assert_eq!(order, vec![50, 30, 10]);
        assert_eq!(rows[2].unread, 5);
        assert_eq!(rows[2].threads[0].id, ThreadId::Sms(1));
        assert_eq!(rows[2].threads[1].id, ThreadId::Sms(3));
        assert_eq!(rows.iter().map(|r| r.unread).sum::<u32>(), 12);
    }

    #[test]
    fn alphanumeric_sender_is_its_own_row() {
        let idx = index(vec![]);
        assert_eq!(person_for_number("BANKA", &idx), None);
        assert_eq!(person_for_jid("123456789012345678@lid", &idx), None);
        let rows = merge(&[sms(9, &["BANKA"], 1, 1)], &[], &idx);
        assert_eq!(rows[0].key, PersonKey::Group(ThreadId::Sms(9)));
        assert_eq!(rows[0].title, "#9");
    }

    #[test]
    fn lid_chat_never_merges_by_number() {
        let idx = index(vec![]);
        let mut lid = wa("905321234567@lid", 300, 1, false);
        let rows = merge(
            &[],
            &[
                wa("905321234567@s.whatsapp.net", 200, 2, false),
                lid.clone(),
            ],
            &idx,
        );
        assert_eq!(rows.len(), 2);
        let lid_row = rows
            .iter()
            .find(|r| matches!(&r.key, PersonKey::Group(ThreadId::WhatsApp(j)) if j.ends_with("@lid")))
            .expect("lid row");
        assert_eq!(lid_row.title, "WhatsApp user");
        assert!(!lid_row.title.contains('+'));
        lid.title = Some("Annem".into());
        let rows = merge(&[], &[lid], &idx);
        assert_eq!(rows[0].title, "Annem");
    }

    #[test]
    fn jid_user_strips_device_suffix() {
        assert_eq!(jid_user("905321234567:12@s.whatsapp.net"), "905321234567");
    }
}
