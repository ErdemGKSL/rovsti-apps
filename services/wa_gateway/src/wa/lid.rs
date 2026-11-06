//! LID -> phone-number resolution. WhatsApp may address a person by an opaque
//! LID (`<digits>@lid`) that is not a phone number; the apps match people by
//! phone number, so every chat, sender and participant id goes through here.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::driver::EventSink;
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::types::message::MessageSource;

/// Known LID -> phone-number mappings, keyed by bare user parts.
#[derive(Debug, Clone, Default)]
pub struct LidMap(HashMap<String, String>);

impl LidMap {
    pub fn insert(&mut self, lid_user: impl Into<String>, pn_user: impl Into<String>) {
        self.0.insert(lid_user.into(), pn_user.into());
    }

    pub fn get(&self, lid_user: &str) -> Option<&str> {
        self.0.get(lid_user).map(String::as_str)
    }

    pub fn from_entries<I, A, B>(entries: I) -> Self
    where
        I: IntoIterator<Item = (A, B)>,
        A: Into<String>,
        B: Into<String>,
    {
        Self(
            entries
                .into_iter()
                .map(|(a, b)| (a.into(), b.into()))
                .collect(),
        )
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    fn extend(&mut self, other: &LidMap) {
        self.0
            .extend(other.0.iter().map(|(k, v)| (k.clone(), v.clone())));
    }
}

/// The canonical id for `jid`: groups, broadcasts and phone numbers keep
/// their form (minus any device suffix); a LID becomes its phone-number JID
/// when `map` knows it, else the `alt` phone number, else stays a LID.
pub fn resolve(jid: &Jid, alt: Option<&Jid>, map: &LidMap) -> Jid {
    if !jid.is_lid() {
        return jid.to_non_ad();
    }
    if let Some(pn) = map.get(&jid.user) {
        return Jid::pn(pn);
    }
    match alt {
        Some(alt) if alt.is_pn() => alt.to_non_ad(),
        _ => jid.to_non_ad(),
    }
}

/// [`resolve`] for a stored id string; text that is not a JID is returned as is.
pub fn resolve_str(id: &str, map: &LidMap) -> String {
    match id.parse::<Jid>() {
        Ok(jid) => resolve(&jid, None, map).to_string(),
        Err(_) => id.to_owned(),
    }
}

/// Every JID of a message source that may be a LID, for [`Resolver::prefetch`].
pub fn source_jids(source: &MessageSource) -> Vec<&Jid> {
    let mut jids = vec![&source.chat, &source.sender];
    jids.extend(source.sender_alt.iter().chain(source.recipient_alt.iter()));
    jids
}

/// Merges every stored LID chat whose phone number is known into its
/// phone-number chat. Idempotent, so it is safe to run at every connect.
/// Returns how many chats were merged.
pub async fn sweep(resolver: &Resolver, sink: &EventSink) -> usize {
    sweep_with(sink, |jid| async move { resolver.lookup(&jid).await }).await
}

/// [`sweep`] with the LID lookup supplied by the caller.
pub async fn sweep_with<F, Fut>(sink: &EventSink, lookup: F) -> usize
where
    F: Fn(Jid) -> Fut,
    Fut: std::future::Future<Output = Option<String>>,
{
    let chats = match sink.lid_chats().await {
        Ok(chats) => chats,
        Err(e) => {
            log::warn!(
                "{}: lid-merge: listing LID chats failed: {e}",
                sink.instance()
            );
            return 0;
        }
    };
    let total = chats.len();
    let mut merged = 0;
    for chat in chats {
        let Ok(jid) = chat.parse::<Jid>() else {
            continue;
        };
        let Some(pn) = lookup(jid).await else {
            continue;
        };
        if sink
            .merge_chat(&chat, &Jid::pn(&pn).to_string())
            .await
            .is_some()
        {
            merged += 1;
        }
    }
    if total > 0 {
        log::info!(
            "{}: lid-merge: startup sweep merged {merged} of {total} LID chats",
            sink.instance()
        );
    }
    merged
}

/// Resolves LIDs against the live client's mapping store, remembering hits.
pub struct Resolver {
    client: Arc<Client>,
    learned: Mutex<LidMap>,
}

impl Resolver {
    pub fn new(client: Arc<Client>) -> Self {
        Self {
            client,
            learned: Mutex::new(LidMap::default()),
        }
    }

    /// The client the mappings come from.
    pub fn client(&self) -> &Arc<Client> {
        &self.client
    }

    fn cached(&self, lid_user: &str) -> Option<String> {
        let learned = self.learned.lock().unwrap_or_else(|e| e.into_inner());
        learned.get(lid_user).map(str::to_owned)
    }

    /// The phone-number user for a LID JID, if known.
    pub async fn lookup(&self, jid: &Jid) -> Option<String> {
        if !jid.is_lid() {
            return None;
        }
        if let Some(pn) = self.cached(&jid.user) {
            return Some(pn);
        }
        match self.client.get_lid_pn_entry(jid).await {
            Ok(Some(entry)) => {
                let pn = entry.phone_number.to_string();
                let mut learned = self.learned.lock().unwrap_or_else(|e| e.into_inner());
                learned.insert(jid.user.to_string(), pn.clone());
                Some(pn)
            }
            Ok(None) => None,
            Err(e) => {
                log::debug!("LID lookup for {} failed: {e:?}", jid.user);
                None
            }
        }
    }

    /// Mappings for every LID among `jids`, for use with the sync [`resolve`].
    pub async fn prefetch(&self, jids: &[&Jid]) -> LidMap {
        let mut found = LidMap::default();
        for jid in jids {
            if let Some(pn) = self.lookup(jid).await {
                found.insert(jid.user.to_string(), pn);
            }
        }
        found
    }

    /// [`resolve`] against the client's mappings.
    pub async fn resolve(&self, jid: &Jid, alt: Option<&Jid>) -> Jid {
        let mut map = LidMap::default();
        if let Some(pn) = self.lookup(jid).await {
            map.insert(jid.user.to_string(), pn);
        }
        resolve(jid, alt, &map)
    }

    /// Mappings learned so far.
    pub fn snapshot(&self) -> LidMap {
        let mut out = LidMap::default();
        out.extend(&self.learned.lock().unwrap_or_else(|e| e.into_inner()));
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jid(s: &str) -> Jid {
        s.parse().unwrap()
    }

    fn map() -> LidMap {
        LidMap::from_entries([("1234", "905321234567"), ("555", "555")])
    }

    #[test]
    fn lid_with_mapping_gives_pn() {
        assert_eq!(
            resolve(&jid("1234@lid"), None, &map()).to_string(),
            "905321234567@s.whatsapp.net"
        );
    }

    #[test]
    fn lid_without_mapping_unchanged() {
        assert_eq!(
            resolve(&jid("9999@lid"), None, &map()).to_string(),
            "9999@lid"
        );
    }

    #[test]
    fn device_suffix_stripped() {
        assert_eq!(
            resolve(&jid("1234:5@lid"), None, &map()).to_string(),
            "905321234567@s.whatsapp.net"
        );
        assert_eq!(
            resolve(&jid("9999:5@lid"), None, &map()).to_string(),
            "9999@lid"
        );
    }

    #[test]
    fn group_untouched() {
        let m = LidMap::from_entries([("1234-5678", "1")]);
        assert_eq!(
            resolve(&jid("1234-5678@g.us"), None, &m).to_string(),
            "1234-5678@g.us"
        );
    }

    #[test]
    fn pn_untouched() {
        assert_eq!(
            resolve(&jid("1234:7@s.whatsapp.net"), None, &map()).to_string(),
            "1234@s.whatsapp.net"
        );
    }

    #[test]
    fn alt_is_fallback_and_map_wins() {
        let alt = jid("491511:2@s.whatsapp.net");
        assert_eq!(
            resolve(&jid("9999@lid"), Some(&alt), &map()).to_string(),
            "491511@s.whatsapp.net"
        );
        assert_eq!(
            resolve(&jid("1234@lid"), Some(&alt), &map()).to_string(),
            "905321234567@s.whatsapp.net"
        );
    }

    #[test]
    fn source_jids_lists_all_addresses() {
        let source = MessageSource {
            chat: jid("1@lid"),
            sender: jid("2@lid"),
            sender_alt: Some(jid("3@s.whatsapp.net")),
            ..Default::default()
        };
        assert_eq!(source_jids(&source).len(), 3);
    }

    #[test]
    fn resolve_str_handles_ids() {
        assert_eq!(
            resolve_str("1234@lid", &map()),
            "905321234567@s.whatsapp.net"
        );
        assert_eq!(resolve_str("9999@lid", &map()), "9999@lid");
        assert_eq!(resolve_str("not a jid", &map()), "not a jid");
    }
}
