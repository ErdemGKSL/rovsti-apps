//! "Is this number on WhatsApp?" lookups, their cache, and picking the
//! WhatsApp target for a contact.

use std::collections::VecDeque;

use rovsti_core::PhoneNumber;

use crate::api::ChatSummary;
use crate::timeline::user_jid;

/// Most JIDs one lookup request may carry.
pub const MAX_LOOKUP: usize = 50;

/// Body of `POST /v1/contacts/lookup`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct LookupUsers {
    pub jids: Vec<String>,
}

/// One answer of a lookup.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct WaUser {
    pub jid: String,
    pub on_whatsapp: bool,
    #[serde(default)]
    pub business: bool,
    #[serde(default)]
    pub verified_name: Option<String>,
}

/// A bounded cache of lookup answers. Entries expire after `ttl_ms`; when
/// full, the oldest entry is dropped.
#[derive(Clone, Debug)]
pub struct LookupCache {
    cap: usize,
    ttl_ms: i64,
    /// Oldest first; `(stored_at_ms, user)`.
    entries: VecDeque<(i64, WaUser)>,
}

impl LookupCache {
    pub fn new(cap: usize, ttl_ms: i64) -> Self {
        Self {
            cap: cap.max(1),
            ttl_ms,
            entries: VecDeque::new(),
        }
    }

    /// The fresh answer for `jid`, if any.
    pub fn get(&self, jid: &str, now_ms: i64) -> Option<&WaUser> {
        self.entries
            .iter()
            .find(|(at, user)| user.jid == jid && self.fresh(*at, now_ms))
            .map(|(_, user)| user)
    }

    pub fn put(&mut self, user: WaUser, now_ms: i64) {
        self.entries.retain(|(_, u)| u.jid != user.jid);
        while self.entries.len() >= self.cap {
            self.entries.pop_front();
        }
        self.entries.push_back((now_ms, user));
    }

    /// Splits `jids` into cached answers and the JIDs still to ask for
    /// (duplicates are asked once).
    pub fn split(&self, jids: &[String], now_ms: i64) -> (Vec<WaUser>, Vec<String>) {
        let mut hits: Vec<WaUser> = Vec::new();
        let mut misses: Vec<String> = Vec::new();
        for jid in jids {
            if hits.iter().any(|u| &u.jid == jid) || misses.contains(jid) {
                continue;
            }
            match self.get(jid, now_ms) {
                Some(user) => hits.push(user.clone()),
                None => misses.push(jid.clone()),
            }
        }
        (hits, misses)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    fn fresh(&self, stored_at: i64, now_ms: i64) -> bool {
        (0..self.ttl_ms).contains(&(now_ms - stored_at))
    }
}

/// Where a contact's WhatsApp actions go.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum WaTarget {
    /// An existing 1:1 chat (its user JID).
    Chat(String),
    /// A number to reach by JID.
    Number(String),
    /// The contact has numbers but none is international.
    NeedsCountryCode,
    /// The contact has no usable number.
    None,
}

/// Picks the WhatsApp target: a number matching an existing 1:1 chat wins,
/// otherwise the first international number, otherwise the country code is
/// missing. `chat_users` are user parts as from [`chat_users`].
pub fn wa_target(numbers: &[&str], chat_users: &[&str]) -> WaTarget {
    let parsed: Vec<PhoneNumber> = numbers
        .iter()
        .filter_map(|n| PhoneNumber::parse(n).ok())
        .collect();
    if parsed.is_empty() {
        return WaTarget::None;
    }
    let chats: Vec<(&str, PhoneNumber)> = chat_users
        .iter()
        .filter_map(|user| {
            PhoneNumber::parse(&format!("+{user}"))
                .ok()
                .map(|n| (*user, n))
        })
        .collect();
    for number in &parsed {
        if let Some((user, _)) = chats.iter().find(|(_, chat)| number.matches(chat)) {
            return WaTarget::Chat(format!("{user}@s.whatsapp.net"));
        }
    }
    parsed
        .iter()
        .find_map(|n| user_jid(n.as_str()).ok())
        .map_or(WaTarget::NeedsCountryCode, WaTarget::Number)
}

/// The user parts of the 1:1 chats (groups are never targets).
pub fn chat_users(chats: &[ChatSummary]) -> Vec<String> {
    chats
        .iter()
        .filter(|c| !c.is_group && crate::api::is_pn_jid(&c.jid))
        .map(|c| crate::api::jid_user(&c.jid).to_owned())
        .filter(|user| !user.is_empty())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn user(jid: &str, on: bool) -> WaUser {
        WaUser {
            jid: jid.into(),
            on_whatsapp: on,
            business: false,
            verified_name: None,
        }
    }

    fn chat(jid: &str, is_group: bool) -> ChatSummary {
        ChatSummary {
            jid: jid.into(),
            name: None,
            last: None,
            unread: 0,
            is_group,
        }
    }

    #[test]
    fn cache_expires_by_ttl() {
        let mut cache = LookupCache::new(4, 1000);
        cache.put(user("a", true), 100);
        assert!(cache.get("a", 100).is_some());
        assert!(cache.get("a", 1099).is_some());
        assert!(cache.get("a", 1100).is_none());
        assert!(cache.get("a", 50).is_none(), "clock going back is a miss");
    }

    #[test]
    fn cache_is_capped_and_replaces_same_jid() {
        let mut cache = LookupCache::new(2, 1000);
        cache.put(user("a", true), 0);
        cache.put(user("b", true), 0);
        cache.put(user("c", true), 0);
        assert_eq!(cache.len(), 2);
        assert!(cache.get("a", 1).is_none());
        cache.put(user("b", false), 2);
        assert_eq!(cache.len(), 2);
        assert!(!cache.get("b", 3).unwrap().on_whatsapp);
    }

    #[test]
    fn split_separates_hits_and_misses() {
        let mut cache = LookupCache::new(8, 1000);
        cache.put(user("a", true), 0);
        cache.put(user("old", true), -5000);
        let jids: Vec<String> = ["a", "b", "old", "b", "a"].map(String::from).to_vec();
        let (hits, misses) = cache.split(&jids, 10);
        assert_eq!(hits, vec![user("a", true)]);
        assert_eq!(misses, vec!["b".to_owned(), "old".to_owned()]);
    }

    #[test]
    fn wa_target_prefers_a_known_chat() {
        assert_eq!(
            wa_target(&["0532 123 45 67", "+49 170 1234567"], &["905321234567"]),
            WaTarget::Chat("905321234567@s.whatsapp.net".into())
        );
    }

    #[test]
    fn wa_target_uses_the_first_international_number() {
        assert_eq!(
            wa_target(&["0532 123 45 67", "+49 170 1234567"], &["15551234567"]),
            WaTarget::Number("491701234567@s.whatsapp.net".into())
        );
    }

    #[test]
    fn a_local_number_with_no_chat_needs_the_country_code() {
        assert_eq!(
            wa_target(&["0532 123 45 67"], &["15551234567"]),
            WaTarget::NeedsCountryCode
        );
        assert_eq!(wa_target(&[], &[]), WaTarget::None);
        assert_eq!(wa_target(&["not a number"], &[]), WaTarget::None);
    }

    #[test]
    fn groups_are_never_targets() {
        let chats = [
            chat("905321234567-1600000000@g.us", true),
            chat("905321234567@s.whatsapp.net", false),
            chat("491701234567@s.whatsapp.net", true),
        ];
        let users = chat_users(&chats);
        assert_eq!(users, vec!["905321234567".to_owned()]);
        let refs: Vec<&str> = users.iter().map(String::as_str).collect();
        assert_eq!(
            wa_target(&["+49 170 1234567"], &refs),
            WaTarget::Number("491701234567@s.whatsapp.net".into())
        );
    }

    #[test]
    fn chat_users_drops_lids() {
        let chats = vec![
            chat("905321234567@s.whatsapp.net", false),
            chat("96413459456093@lid", false),
            chat("120363@g.us", true),
        ];
        assert_eq!(chat_users(&chats), vec!["905321234567".to_owned()]);
    }

    #[test]
    fn lookup_types_round_trip() {
        let body = LookupUsers {
            jids: vec!["1@s.whatsapp.net".into()],
        };
        let json = serde_json::to_string(&body).unwrap();
        assert_eq!(json, r#"{"jids":["1@s.whatsapp.net"]}"#);
        assert_eq!(serde_json::from_str::<LookupUsers>(&json).unwrap(), body);
        let wa = WaUser {
            jid: "1@s.whatsapp.net".into(),
            on_whatsapp: true,
            business: true,
            verified_name: Some("Shop".into()),
        };
        let json = serde_json::to_string(&wa).unwrap();
        assert_eq!(serde_json::from_str::<WaUser>(&json).unwrap(), wa);
        let sparse: WaUser =
            serde_json::from_str(r#"{"jid":"1@s.whatsapp.net","on_whatsapp":false}"#).unwrap();
        assert!(!sparse.business && sparse.verified_name.is_none());
    }
}
