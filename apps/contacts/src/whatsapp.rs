//! WhatsApp on the contact card and in the list (AD-CT6).
//!
//! Contacts never sends the address book to WhatsApp. The list mark comes from
//! the instance's existing chats (`GET /v1/chats`, no lookup). Only when a card
//! opens are that one contact's international numbers (at most
//! [`MAX_NUMBERS`]) looked up, and presence is subscribed for the WhatsApp
//! number. Everything that decides something is a pure function below.

use std::{
    collections::{HashMap, HashSet},
    time::{SystemTime, UNIX_EPOCH},
};

use gpui::{Context, SharedString};
use rovsti_core::{PhoneNumber, recents::LocaleTime, wire::WireContact};
use rovsti_wa_proto::{
    ChatSummary, GatewayEvent, PresenceBook, WaTarget, WaUser, chat_users, presence_label,
    timeline::user_jid, wa_target,
};

use crate::ContactsApp;

/// Most numbers of one contact sent in one lookup.
pub(crate) const MAX_NUMBERS: usize = 12;

/// Where one contact's WhatsApp lookup stands.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum LookupState {
    Checking,
    Done(Vec<WaUser>),
    Failed,
}

/// What the app knows about WhatsApp, for this run.
#[derive(Default)]
pub(crate) struct WaPanel {
    /// User parts of the 1:1 chats of the instance.
    pub chat_users: Vec<String>,
    pub book: PresenceBook,
    pub lookups: HashMap<i64, LookupState>,
    /// Contacts whose list row carries the WhatsApp mark.
    pub marked: HashSet<i64>,
    /// JIDs presence was already subscribed for.
    subscribed: HashSet<String>,
    connected: bool,
    chats_gen: u64,
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
pub(crate) fn lookup_jids(target: &WaTarget, numbers: &[&str]) -> Vec<String> {
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
pub(crate) fn wa_jid(target: &WaTarget, lookup: Option<&LookupState>) -> Option<String> {
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

/// The status line of the card's WhatsApp section.
pub(crate) fn status_line(
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
/// matches a known 1:1 chat. Parses each chat and number once.
pub(crate) fn marked_contacts<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    chat_users: &[String],
) -> HashSet<i64> {
    let chats: Vec<PhoneNumber> = chat_users
        .iter()
        .filter_map(|user| PhoneNumber::parse(&format!("+{user}")).ok())
        .collect();
    if chats.is_empty() {
        return HashSet::new();
    }
    contacts
        .into_iter()
        .filter(|c| {
            c.numbers
                .iter()
                .filter_map(|n| PhoneNumber::parse(&n.raw).ok())
                .filter(PhoneNumber::is_international)
                .any(|n| chats.iter().any(|chat| n.matches(chat)))
        })
        .map(|c| c.id)
        .collect()
}

pub(crate) fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

impl ContactsApp {
    /// Reads the phone's time zone once, for "last seen" lines.
    pub(crate) fn pull_wa_offset(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let raw = cx
                .background_executor()
                .spawn(async { rovsti_android::providers::local_time_info() })
                .await;
            if let Some(locale) = raw.ok().and_then(|text| LocaleTime::parse(&text)) {
                this.update(cx, |this, cx| {
                    this.wa.offset_s = locale.tz_offset_s;
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    /// The shared settings entity changed: take its gateway events, and read
    /// the chats again when the instance just connected or asked for a resync.
    pub(crate) fn on_whatsapp_changed(&mut self, cx: &mut Context<Self>) {
        let (events, connected) = self
            .whatsapp
            .update(cx, |wa, _| (wa.take_events(), wa.is_connected()));
        let now = now_ms();
        let mut resync = false;
        for event in &events {
            resync |= matches!(event, GatewayEvent::Resync);
            self.wa.book.apply(event, now);
        }
        let just_connected = connected && !self.wa.connected;
        self.wa.connected = connected;
        if resync || just_connected {
            self.refresh_chats(cx);
        } else if connected {
            self.check_whatsapp(cx);
        }
        cx.notify();
    }

    /// Reads the chats (background) and re-marks the list.
    pub(crate) fn refresh_chats(&mut self, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        self.wa.chats_gen += 1;
        let generation = self.wa.chats_gen;
        cx.spawn(async move |this, cx| {
            let chats = cx
                .background_executor()
                .spawn(async move { gateway.chats() })
                .await;
            this.update(cx, |this, cx| {
                if this.wa.chats_gen != generation {
                    return;
                }
                match chats {
                    Ok(chats) => this.set_chats(&chats, cx),
                    Err(error) => log::warn!("reading the WhatsApp chats failed: {error}"),
                }
            })
            .ok();
        })
        .detach();
    }

    fn set_chats(&mut self, chats: &[ChatSummary], cx: &mut Context<Self>) {
        self.wa.chat_users = chat_users(chats);
        self.refresh_marks();
        self.check_whatsapp(cx);
        cx.notify();
    }

    /// Recomputes the list marks: once per pull and per chats refresh.
    pub(crate) fn refresh_marks(&mut self) {
        self.wa.marked = match self.index() {
            Some(index) => marked_contacts(index.all(), &self.wa.chat_users),
            None => HashSet::new(),
        };
        self.apply_marks();
    }

    /// Copies the marks onto the built rows.
    pub(crate) fn apply_marks(&mut self) {
        for row in self
            .rows
            .iter_mut()
            .chain(self.favorites.iter_mut())
            .chain(self.group_rows.iter_mut())
        {
            row.set_whatsapp(self.wa.marked.contains(&row.vm.id));
        }
    }

    /// For the open card: look the contact up when needed and subscribe
    /// presence once. Sends at most this one contact's numbers.
    pub(crate) fn check_whatsapp(&mut self, cx: &mut Context<Self>) {
        if !self.wa.connected {
            return;
        }
        let Some(card) = &self.open else { return };
        let id = card.id;
        let numbers: Vec<String> = card.numbers.iter().map(|n| n.raw.clone()).collect();
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        let jids = self.wa.lookup_request(id, &numbers);
        if jids.is_empty() {
            self.subscribe_presence(id, &numbers, &gateway, cx);
            return;
        }
        self.wa.lookups.insert(id, LookupState::Checking);
        let lookup = gateway.clone();
        cx.spawn(async move |this, cx| {
            let answer = cx
                .background_executor()
                .spawn(async move { lookup.lookup_users(&jids) })
                .await;
            this.update(cx, |this, cx| {
                let state = match answer {
                    Ok(users) => LookupState::Done(users),
                    Err(error) => {
                        log::warn!("the WhatsApp lookup failed: {error}");
                        LookupState::Failed
                    }
                };
                this.wa.lookups.insert(id, state);
                if let Some(card) = this.open.as_ref().filter(|c| c.id == id) {
                    let numbers: Vec<String> = card.numbers.iter().map(|n| n.raw.clone()).collect();
                    this.subscribe_presence(id, &numbers, &gateway, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn subscribe_presence(
        &mut self,
        id: i64,
        numbers: &[String],
        gateway: &rovsti_wa_client::Gateway,
        cx: &mut Context<Self>,
    ) {
        let Some(jid) = self.wa.jid(id, numbers) else {
            return;
        };
        if !self.wa.take_subscription(&jid) {
            return;
        }
        let gateway = gateway.clone();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = gateway.subscribe_presence(&jid) {
                    log::warn!("subscribing to WhatsApp presence failed: {error}");
                }
            })
            .detach();
    }

    /// Hands a chat or a call to Messages or Phone.
    pub(crate) fn wa_handoff(
        &mut self,
        call: bool,
        jid: String,
        name: String,
        cx: &mut Context<Self>,
    ) {
        self.notice = None;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    if call {
                        rovsti_android::handoff::wa_call(&jid, &name)
                    } else {
                        rovsti_android::handoff::wa_chat(&jid, &name)
                    }
                })
                .await;
            let notice: Option<SharedString> = match result {
                Ok(true) => None,
                Ok(false) if call => Some("Install Rovsti Phone to call on WhatsApp".into()),
                Ok(false) => Some("Install Rovsti Messages to chat on WhatsApp".into()),
                Err(error) => {
                    let what = if call { "call" } else { "chat" };
                    Some(format!("Can't {what} on WhatsApp: {error}.").into())
                }
            };
            this.update(cx, |this, cx| {
                this.notice = notice;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
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
                })
                .collect(),
            ..Default::default()
        }
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
        let marked = marked_contacts(&contacts, &["905321234567".to_owned()]);
        assert!(marked.contains(&1));
        assert!(!marked.contains(&3));
        assert!(!marked.contains(&4));
        assert!(marked_contacts(&contacts, &[]).is_empty());
    }
}
