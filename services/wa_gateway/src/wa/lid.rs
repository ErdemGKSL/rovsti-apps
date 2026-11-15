//! LID and phone-number resolution. WhatsApp may address a person by an
//! opaque LID (`<digits>@lid`) that is not a phone number; the apps match
//! people by phone number, so every chat, sender and participant id goes
//! through here. The id it arrived under is not thrown away: it is stored
//! next to the message and handed to the apps as the "alt" id, and every
//! pair seen is recorded ([`EventSink::learn_pairs`]).

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use rosty_wa_proto::IdentitySource;
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::types::lid_pn::LearningSource;
use whatsapp_rust::wacore::types::message::MessageSource;

use crate::driver::EventSink;
use crate::now_ms;
use crate::store::NewPair;

/// Known LID and phone-number pairs, by bare user parts, readable both ways.
#[derive(Debug, Clone, Default)]
pub struct LidMap {
    pn_of: HashMap<String, String>,
    lid_of: HashMap<String, String>,
}

impl LidMap {
    pub fn insert(&mut self, lid_user: impl Into<String>, pn_user: impl Into<String>) {
        let (lid, pn) = (lid_user.into(), pn_user.into());
        self.lid_of.insert(pn.clone(), lid.clone());
        self.pn_of.insert(lid, pn);
    }

    /// The phone-number user of a LID user.
    pub fn get(&self, lid_user: &str) -> Option<&str> {
        self.pn_of.get(lid_user).map(String::as_str)
    }

    /// The LID user of a phone-number user.
    pub fn lid_of(&self, pn_user: &str) -> Option<&str> {
        self.lid_of.get(pn_user).map(String::as_str)
    }

    pub fn from_entries<I, A, B>(entries: I) -> Self
    where
        I: IntoIterator<Item = (A, B)>,
        A: Into<String>,
        B: Into<String>,
    {
        let mut map = Self::default();
        for (lid, pn) in entries {
            map.insert(lid, pn);
        }
        map
    }

    pub fn is_empty(&self) -> bool {
        self.pn_of.is_empty()
    }

    fn extend(&mut self, other: &LidMap) {
        for (lid, pn) in &other.pn_of {
            self.insert(lid.clone(), pn.clone());
        }
    }

    /// Adds `pair` unless its LID is known already.
    pub fn insert_new(&mut self, pair: &Pair) {
        if self.get(&pair.lid.user).is_none() {
            self.insert(pair.lid.user.to_string(), pair.pn.user.to_string());
        }
    }
}

/// A LID and the phone-number JID of the same person, both bare.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pair {
    pub lid: Jid,
    pub pn: Jid,
}

impl Pair {
    /// The pair two ids of one person form: one of them a LID, the other a
    /// phone-number JID, in either order. `None` for anything else.
    pub fn of(a: &Jid, b: &Jid) -> Option<Self> {
        let (lid, pn) = if a.is_lid() && b.is_pn() {
            (a, b)
        } else if a.is_pn() && b.is_lid() {
            (b, a)
        } else {
            return None;
        };
        if lid.user.is_empty() || pn.user.is_empty() {
            return None;
        }
        Some(Self {
            lid: lid.to_non_ad(),
            pn: pn.to_non_ad(),
        })
    }

    /// The pair of two bare user parts.
    pub fn from_users(lid_user: &str, pn_user: &str) -> Option<Self> {
        Self::of(&Jid::lid(lid_user), &Jid::pn(pn_user))
    }

    /// The row to record, learned now.
    pub fn learned(&self, source: IdentitySource) -> NewPair {
        self.learned_at(source, now_ms())
    }

    pub fn learned_at(&self, source: IdentitySource, learned_ms: i64) -> NewPair {
        NewPair::new(
            self.lid.to_string(),
            self.pn.to_string(),
            source,
            learned_ms,
        )
    }
}

/// Parses ids of which one may be a LID and the other its number.
pub fn pair_of_strs(a: Option<&str>, b: Option<&str>) -> Option<Pair> {
    let parse = |id: Option<&str>| id.filter(|id| !id.is_empty())?.parse::<Jid>().ok();
    Pair::of(&parse(a)?, &parse(b)?)
}

/// The gateway's name for where whatsapp-rust learned a pair. Its sources
/// without a name of their own (blocklist entries, pairing) are `other`.
pub fn source_of(source: LearningSource) -> IdentitySource {
    match source {
        LearningSource::Usync | LearningSource::RecipientLatestLid => IdentitySource::Usync,
        LearningSource::PeerPnMessage | LearningSource::PeerLidMessage => {
            IdentitySource::PeerMessage
        }
        LearningSource::MigrationSyncLatest | LearningSource::MigrationSyncOld => {
            IdentitySource::MigrationSync
        }
        LearningSource::DeviceNotification => IdentitySource::DeviceNotification,
        LearningSource::BlocklistActive
        | LearningSource::BlocklistInactive
        | LearningSource::Pairing
        | LearningSource::Other => IdentitySource::Other,
    }
}

/// When a pair of the library was learned, in unix ms. The library stamps
/// pairs it knows to be stale with 0: those count as learned `now_ms`.
fn learned_ms(created_at_s: i64, now_ms: i64) -> i64 {
    if created_at_s > 0 {
        created_at_s.saturating_mul(1000)
    } else {
        now_ms
    }
}

/// The row for a pair as whatsapp-rust keeps it (bare user parts, its
/// source, its creation time in seconds).
pub fn library_pair(
    lid_user: &str,
    pn_user: &str,
    source: LearningSource,
    created_at_s: i64,
    now_ms: i64,
) -> Option<NewPair> {
    let pair = Pair::from_users(lid_user, pn_user)?;
    Some(pair.learned_at(source_of(source), learned_ms(created_at_s, now_ms)))
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

/// The other form of the person the apps know as `canonical` (what
/// [`resolve`] made of `wire`), bare: for a phone-number JID its LID, taken
/// from the id it arrived under, else from the `hint` WhatsApp sent along,
/// else from `map`. `None` for a LID (its number is not known, or it would be
/// the canonical id), for groups and when no LID is known.
pub fn alt_of(canonical: &Jid, wire: &Jid, hint: Option<&Jid>, map: &LidMap) -> Option<String> {
    if !canonical.is_pn() {
        return None;
    }
    [Some(wire), hint]
        .into_iter()
        .flatten()
        .find(|jid| jid.is_lid())
        .map(Jid::to_non_ad)
        .or_else(|| map.lid_of(&canonical.user).map(Jid::lid))
        .map(|lid| lid.to_string())
}

/// Every JID of a message source whose other form may be worth knowing, for
/// [`Resolver::prefetch`].
pub fn source_jids(source: &MessageSource) -> Vec<&Jid> {
    let mut jids = vec![&source.chat, &source.sender];
    jids.extend(source.sender_alt.iter().chain(source.recipient_alt.iter()));
    jids
}

/// A sweep that found nothing is still logged when it took this long.
const SLOW_SWEEP_MS: u128 = 250;

/// Moves everything stored under a LID whose phone number is known to that
/// number: LID chats, and rows of any chat that name a LID as an author, a
/// reactor, a receipt, a quoted author, a mention or a profile. Idempotent,
/// so it is safe to run at every connect. Returns how many LIDs were moved.
pub async fn sweep(resolver: &Resolver, sink: &EventSink) -> usize {
    sweep_with(sink, |jid| async move { resolver.lookup(&jid).await }).await
}

/// [`sweep`] with the LID lookup supplied by the caller.
pub async fn sweep_with<F, Fut>(sink: &EventSink, lookup: F) -> usize
where
    F: Fn(Jid) -> Fut,
    Fut: std::future::Future<Output = Option<String>>,
{
    let started = std::time::Instant::now();
    let lids = match sink.lids_seen().await {
        Ok(lids) => lids,
        Err(e) => {
            log::warn!(
                "{}: lid-merge: listing stored LIDs failed: {e}",
                sink.instance()
            );
            return 0;
        }
    };
    let total = lids.len();
    let mut merged = 0;
    for lid in lids {
        let Ok(jid) = lid.parse::<Jid>() else {
            continue;
        };
        let Some(pn) = lookup(jid).await else {
            continue;
        };
        if sink
            .merge_chat(&lid, &Jid::pn(&pn).to_string())
            .await
            .is_some()
        {
            merged += 1;
        }
    }
    // The listing reads the whole message table: the time says when that needs an index or a rarer sweep.
    let took_ms = started.elapsed().as_millis();
    if total > 0 || took_ms >= SLOW_SWEEP_MS {
        log::info!(
            "{}: lid-merge: sweep moved {merged} of {total} stored LIDs in {took_ms} ms",
            sink.instance()
        );
    }
    merged
}

/// Records every pair whatsapp-rust has in its own table. The library tells
/// nobody when it learns one, so this runs at every connect; pairs already
/// recorded cost one lookup each. Returns how many were new or changed.
pub async fn seed(client: &Client, sink: &EventSink) -> usize {
    let backend = client.persistence_manager().backend();
    let entries = match backend.get_all_lid_mappings().await {
        Ok(entries) => entries,
        Err(e) => {
            log::warn!(
                "{}: reading the library's LID table failed: {e}",
                sink.instance()
            );
            return 0;
        }
    };
    let now = now_ms();
    let pairs: Vec<NewPair> = entries
        .iter()
        .filter_map(|entry| {
            library_pair(
                &entry.lid,
                &entry.phone_number,
                LearningSource::parse(&entry.learning_source),
                entry.created_at,
                now,
            )
        })
        .collect();
    let known = pairs.len();
    let learned = sink.learn_pairs(pairs).await.len();
    if learned > 0 {
        log::info!(
            "{}: identity: {learned} of the library's {known} pairs were new",
            sink.instance()
        );
    }
    learned
}

/// Resolves LIDs against the live client's mapping store, remembering hits
/// and, when it has a sink, recording each one as a learned pair.
pub struct Resolver {
    client: Arc<Client>,
    sink: Option<EventSink>,
    learned: Mutex<LidMap>,
}

impl Resolver {
    /// A resolver that only looks up.
    pub fn new(client: Arc<Client>) -> Self {
        Self {
            client,
            sink: None,
            learned: Mutex::new(LidMap::default()),
        }
    }

    /// A resolver that also records what it finds.
    pub fn recording(client: Arc<Client>, sink: EventSink) -> Self {
        Self {
            sink: Some(sink),
            ..Self::new(client)
        }
    }

    /// The client the mappings come from.
    pub fn client(&self) -> &Arc<Client> {
        &self.client
    }

    fn cached(&self, jid: &Jid) -> Option<String> {
        let learned = self.learned.lock().unwrap_or_else(|e| e.into_inner());
        if jid.is_lid() {
            learned.get(&jid.user).map(str::to_owned)
        } else {
            learned.lid_of(&jid.user).map(str::to_owned)
        }
    }

    /// The other user part of a LID or phone-number JID, if the client
    /// knows the pair, and the row to record when the client was asked for
    /// it (a hit this resolver has not seen before).
    async fn find(&self, jid: &Jid) -> Option<(String, Option<NewPair>)> {
        if !jid.is_lid() && !jid.is_pn() {
            return None;
        }
        if let Some(known) = self.cached(jid) {
            return Some((known, None));
        }
        let entry = match self.client.get_lid_pn_entry(jid).await {
            Ok(Some(entry)) => entry,
            Ok(None) => return Some((self.recorded(jid).await?, None)),
            Err(e) => {
                log::debug!("LID lookup for {} failed: {e:?}", jid.user);
                return Some((self.recorded(jid).await?, None));
            }
        };
        let (lid, pn) = (entry.lid.to_string(), entry.phone_number.to_string());
        {
            let mut learned = self.learned.lock().unwrap_or_else(|e| e.into_inner());
            learned.insert(lid.clone(), pn.clone());
        }
        let row = library_pair(&lid, &pn, entry.learning_source, entry.created_at, now_ms());
        Some((if jid.is_lid() { pn } else { lid }, row))
    }

    /// Records what lookups found, in one go.
    async fn record(&self, rows: Vec<NewPair>) {
        if let Some(sink) = self.sink.as_ref().filter(|_| !rows.is_empty()) {
            sink.learn_pairs(rows).await;
        }
    }

    /// [`Self::find`], recording a new hit.
    async fn other(&self, jid: &Jid) -> Option<String> {
        let (other, row) = self.find(jid).await?;
        self.record(row.into_iter().collect()).await;
        Some(other)
    }

    /// What the gateway's own table says when the client knows nothing: a
    /// pair the client was told but did not take (it only seeds unseen LIDs
    /// from weaker sources) still names the person the same way everywhere.
    async fn recorded(&self, jid: &Jid) -> Option<String> {
        let sink = self.sink.as_ref()?;
        let id = jid.to_non_ad().to_string();
        let other = if jid.is_lid() {
            sink.pn_of_lid(&id).await
        } else {
            sink.lid_of_pn(&id).await
        }?;
        let other: Jid = other.parse().ok()?;
        Some(other.user.to_string())
    }

    /// The phone-number user for a LID JID, if known.
    pub async fn lookup(&self, jid: &Jid) -> Option<String> {
        if !jid.is_lid() {
            return None;
        }
        self.other(jid).await
    }

    /// Both directions for every LID and phone-number JID among `jids`, for
    /// use with the sync [`resolve`] and [`alt_of`].
    pub async fn prefetch(&self, jids: &[&Jid]) -> LidMap {
        let mut found = LidMap::default();
        let mut rows = Vec::new();
        for jid in jids {
            let Some((other, row)) = self.find(jid).await else {
                continue;
            };
            rows.extend(row);
            if jid.is_lid() {
                found.insert(jid.user.to_string(), other);
            } else {
                found.insert(other, jid.user.to_string());
            }
        }
        // One write for the lot: a group lists hundreds of members.
        self.record(rows).await;
        found
    }

    /// [`resolve`] against the client's mappings.
    pub async fn resolve(&self, jid: &Jid, alt: Option<&Jid>) -> Jid {
        resolve(jid, alt, &self.prefetch(&[jid]).await)
    }

    /// [`resolve`] and [`alt_of`] against the client's mappings: the id the
    /// apps know `jid` by, and its other form when one is known.
    pub async fn resolve_with_alt(&self, jid: &Jid, hint: Option<&Jid>) -> (Jid, Option<String>) {
        let map = self.prefetch(&[jid]).await;
        let canonical = resolve(jid, hint, &map);
        let alt = alt_of(&canonical, jid, hint, &map);
        (canonical, alt)
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
        LidMap::from_entries([("1234", "905550000001"), ("555", "555")])
    }

    #[test]
    fn lid_with_mapping_gives_pn() {
        assert_eq!(
            resolve(&jid("1234@lid"), None, &map()).to_string(),
            "905550000001@s.whatsapp.net"
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
            "905550000001@s.whatsapp.net"
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
            "905550000001@s.whatsapp.net"
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
            "905550000001@s.whatsapp.net"
        );
        assert_eq!(resolve_str("9999@lid", &map()), "9999@lid");
        assert_eq!(resolve_str("not a jid", &map()), "not a jid");
    }

    #[test]
    fn the_map_reads_both_ways() {
        let mut m = map();
        assert_eq!(m.get("1234"), Some("905550000001"));
        assert_eq!(m.lid_of("905550000001"), Some("1234"));
        assert_eq!(m.lid_of("1234"), None, "a LID is not a number");
        // A number that got a new LID answers with the newest one.
        m.insert("77", "905550000001");
        assert_eq!(m.lid_of("905550000001"), Some("77"));
        assert_eq!(m.get("1234"), Some("905550000001"));
        // A pair from a weaker place does not replace a known LID.
        let other = Pair::from_users("1234", "905550000009").unwrap();
        m.insert_new(&other);
        assert_eq!(m.get("1234"), Some("905550000001"));
        m.insert_new(&Pair::from_users("88", "905550000008").unwrap());
        assert_eq!(m.get("88"), Some("905550000008"));
    }

    #[test]
    fn pairs_are_bare_and_ordered() {
        let lid = jid("1234:7@lid");
        let pn = jid("905550000001:3@s.whatsapp.net");
        let pair = Pair::of(&pn, &lid).unwrap();
        assert_eq!(pair, Pair::of(&lid, &pn).unwrap());
        assert_eq!(pair.lid.to_string(), "1234@lid");
        assert_eq!(pair.pn.to_string(), "905550000001@s.whatsapp.net");
        let row = pair.learned_at(IdentitySource::Receipt, 9);
        assert_eq!(
            row,
            NewPair::new(
                "1234@lid",
                "905550000001@s.whatsapp.net",
                IdentitySource::Receipt,
                9
            )
        );
        // Two ids of the same kind, groups and empty users are no pair.
        assert_eq!(Pair::of(&lid, &jid("5@lid")), None);
        assert_eq!(Pair::of(&pn, &jid("905550000002@s.whatsapp.net")), None);
        assert_eq!(Pair::of(&lid, &jid("1-2@g.us")), None);
        assert_eq!(Pair::from_users("", "905550000001"), None);
        assert_eq!(
            pair_of_strs(Some("905550000001@s.whatsapp.net"), Some("1234@lid")),
            Some(pair)
        );
        assert_eq!(pair_of_strs(Some("1234@lid"), None), None);
        assert_eq!(pair_of_strs(Some(""), Some("1234@lid")), None);
    }

    #[test]
    fn the_other_form_of_a_number_is_its_lid() {
        let pn = jid("905550000001@s.whatsapp.net");
        let none = LidMap::default();
        // It arrived under the LID.
        assert_eq!(
            alt_of(&pn, &jid("1234:5@lid"), None, &none).as_deref(),
            Some("1234@lid")
        );
        // It arrived under the number, WhatsApp named the LID next to it.
        assert_eq!(
            alt_of(
                &pn,
                &jid("905550000001:2@s.whatsapp.net"),
                Some(&jid("1234:9@lid")),
                &none
            )
            .as_deref(),
            Some("1234@lid")
        );
        // Neither: the map.
        assert_eq!(alt_of(&pn, &pn, None, &map()).as_deref(), Some("1234@lid"));
        assert_eq!(alt_of(&pn, &pn, None, &none), None);
        // The id on the wire beats the map (an older LID of the same person).
        assert_eq!(
            alt_of(&pn, &jid("77@lid"), None, &map()).as_deref(),
            Some("77@lid")
        );
        // A LID whose number is unknown has no other form, nor has a group.
        let lid = jid("9999@lid");
        assert_eq!(alt_of(&lid, &lid, None, &map()), None);
        let group = jid("1-2@g.us");
        assert_eq!(alt_of(&group, &group, Some(&jid("1234@lid")), &map()), None);
    }

    #[test]
    fn library_sources_get_the_gateways_names() {
        use IdentitySource as S;
        use LearningSource as L;
        for (theirs, ours) in [
            (L::Usync, S::Usync),
            (L::RecipientLatestLid, S::Usync),
            (L::PeerPnMessage, S::PeerMessage),
            (L::PeerLidMessage, S::PeerMessage),
            (L::MigrationSyncLatest, S::MigrationSync),
            (L::MigrationSyncOld, S::MigrationSync),
            (L::DeviceNotification, S::DeviceNotification),
            (L::BlocklistActive, S::Other),
            (L::BlocklistInactive, S::Other),
            (L::Pairing, S::Other),
            (L::Other, S::Other),
        ] {
            assert_eq!(source_of(theirs), ours, "{theirs:?}");
        }
    }

    #[test]
    fn library_rows_become_pairs() {
        let row = library_pair(
            "1234",
            "905550000001",
            LearningSource::Usync,
            1_700_000_000,
            5,
        );
        assert_eq!(
            row,
            Some(NewPair::new(
                "1234@lid",
                "905550000001@s.whatsapp.net",
                IdentitySource::Usync,
                1_700_000_000_000
            ))
        );
        // Stale rows carry no time of their own.
        let stale = library_pair(
            "1234",
            "905550000001",
            LearningSource::MigrationSyncOld,
            0,
            5,
        )
        .unwrap();
        assert_eq!(
            (stale.source, stale.learned_ms),
            (IdentitySource::MigrationSync, 5)
        );
        assert_eq!(
            library_pair("", "905550000001", LearningSource::Other, 1, 5),
            None
        );
    }
}
