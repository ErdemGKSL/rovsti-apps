//! History sync: WhatsApp's `HistorySync` protobuf to store rows.

use rosty_wa_proto::{
    DeliveryStatus, HistorySyncType, MUTED_FOREVER, MessageBody, Reaction, is_status_chat,
};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::history_sync::HistorySyncError;
use whatsapp_rust::wacore::store::DevicePropsOverride;
use whatsapp_rust::wacore::store::device::default_history_sync_config;
use whatsapp_rust::wacore::types::events::LazyHistorySync;
use whatsapp_rust::waproto::buffa::Message as _;
use whatsapp_rust::waproto::whatsapp::web_message_info::Status as WebStatus;

use super::convert::{self, Converted, Origin};
use super::lid::{self, LidMap, Pair};
use super::status;
use crate::store::{ChatSettings, HistoryChat, HistoryMessage, StoredStatus};

/// How far back a full history is asked for, in days: the number WhatsApp's
/// own Windows client sends (the `win_hybrid` row of the table on
/// `wacore::store::device::DEVICE_PROPS`). The phone decides what it sends.
pub const FULL_SYNC_DAYS: u32 = 365;

/// The device props to pair with. `None` leaves the library's defaults: a
/// browser-like companion that gets the recent messages only.
///
/// With `full_history` the device registers the way the Windows desktop
/// client does, the only official companion that asks for the full history:
/// its platform type, `require_full_sync`, the days limit and the on-demand
/// flags are one decision there and are set together here (see
/// `DevicePropsOverride::with_require_full_sync`). The phone then lists the
/// device as Windows.
///
/// The props travel in the registration payload only
/// (`Device::get_registration_payload`), so they take effect when a device
/// is paired: an account that is already linked keeps the sync it was paired
/// with until it is linked again.
pub fn device_props(full_history: bool) -> Option<DevicePropsOverride> {
    full_history.then(|| {
        DevicePropsOverride::new()
            .with_os("Windows")
            .with_platform_type(wa::device_props::PlatformType::UWP)
            .with_require_full_sync(true)
            .with_history_sync_config(wa::device_props::HistorySyncConfig {
                full_sync_days_limit: Some(FULL_SYNC_DAYS),
                on_demand_ready: Some(true),
                complete_on_demand_ready: Some(true),
                ..default_history_sync_config()
            })
    })
}

/// The kind of a history chunk, from `LazyHistorySync::sync_type` (the
/// numbers of `Message.HistorySyncType` in WhatsApp's protobuf).
pub fn sync_kind(raw: i32) -> HistorySyncType {
    match raw {
        0 => HistorySyncType::Initial,
        2 => HistorySyncType::Full,
        3 => HistorySyncType::Recent,
        6 => HistorySyncType::OnDemand,
        // Status updates, push names, non-blocking data and newer kinds.
        _ => HistorySyncType::Other,
    }
}

/// Chats that are not conversations the apps show.
fn is_skipped(jid: &str) -> bool {
    jid == "status@broadcast" || jid.ends_with("@newsletter") || jid.ends_with("@broadcast")
}

fn seconds_to_ms(seconds: u64) -> i64 {
    i64::try_from(seconds)
        .unwrap_or(i64::MAX / 1000)
        .saturating_mul(1000)
}

/// The ticks a history entry of mine had.
fn delivery(status: Option<WebStatus>) -> Option<DeliveryStatus> {
    match status? {
        WebStatus::SERVER_ACK => Some(DeliveryStatus::Sent),
        WebStatus::DELIVERY_ACK => Some(DeliveryStatus::Delivered),
        WebStatus::READ => Some(DeliveryStatus::Read),
        WebStatus::PLAYED => Some(DeliveryStatus::Played),
        WebStatus::ERROR | WebStatus::PENDING => None,
    }
}

/// The author of a group message as the apps get it and as the sync named
/// it (bare).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct Author {
    sender: Option<String>,
    wire: Option<String>,
}

/// The author of `key` in `chat`, for groups.
fn author(chat: &str, participant: Option<&str>, map: &LidMap) -> Author {
    let Some(jid) = participant
        .filter(|p| !p.is_empty() && convert::is_group(chat))
        .and_then(|p| p.parse::<Jid>().ok())
    else {
        return Author::default();
    };
    Author {
        sender: Some(lid::resolve(&jid, None, map).to_string()),
        wire: Some(jid.to_non_ad().to_string()),
    }
}

/// The reactions a history entry carries. Their other id is left to the
/// store, which fills it from the learned pairs when the message is read.
fn reactions(chat: &str, info: &wa::WebMessageInfo, map: &LidMap) -> Vec<Reaction> {
    info.reactions
        .iter()
        .filter_map(|r| {
            let emoji = r.text.clone().filter(|t| !t.is_empty())?;
            let key = r.key.as_option();
            Some(Reaction {
                sender: author(chat, key.and_then(|k| k.participant.as_deref()), map).sender,
                from_me: key.and_then(|k| k.from_me).unwrap_or(false),
                emoji,
                sender_alt: None,
            })
        })
        .collect()
}

/// One history message of the conversation stored as `chat`, which the sync
/// names `wire_chat`. `None` when it has no id or content the apps show.
fn history_message(
    chat: &str,
    wire_chat: &str,
    info: &wa::WebMessageInfo,
    map: &LidMap,
) -> Option<HistoryMessage> {
    let key = info.key.as_option()?;
    let id = key.id.as_deref().filter(|id| !id.is_empty())?;
    let content = info.message.as_option()?;
    let from_me = key.from_me.unwrap_or(false);
    let author = author(
        chat,
        info.participant.as_deref().or(key.participant.as_deref()),
        map,
    );
    let origin = Origin {
        sender_name: info.push_name.as_deref(),
        ..Origin::new(
            id,
            chat,
            author.sender.as_deref(),
            from_me,
            seconds_to_ms(info.message_timestamp.unwrap_or(0)),
        )
    };
    match convert::convert(origin, content) {
        Converted::Message(message, extras) => {
            let mut extras = *extras;
            if matches!(message.body, MessageBody::Media { .. }) {
                extras.raw_proto = Some(content.encode_to_vec());
            }
            convert::resolve_meta(&mut extras.meta, map);
            extras.wire_chat = Some(wire_chat.to_owned());
            extras.wire_sender = author.wire;
            if from_me {
                extras.meta.status = delivery(info.status).or(extras.meta.status);
            }
            extras.meta.starred = info.starred.unwrap_or(false);
            if extras.secret.is_none() {
                extras.secret = info.message_secret.clone().filter(|s| !s.is_empty());
            }
            Some(HistoryMessage {
                reactions: reactions(chat, info, map),
                message,
                extras,
            })
        }
        // A change of the chat's timer is a line of the conversation; the
        // timer itself comes with the conversation's settings.
        Converted::Disappearing {
            chat: line_chat,
            id,
            seconds,
            at_ms,
            by,
        } => Some(HistoryMessage {
            reactions: Vec::new(),
            message: rosty_wa_proto::WaMessage {
                id,
                chat: line_chat,
                sender: author.sender.clone().filter(|_| !from_me),
                from_me,
                timestamp_ms: at_ms,
                body: MessageBody::Unsupported,
                reactions: Vec::new(),
                chat_alt: None,
                sender_alt: None,
            },
            extras: crate::store::MessageExtras {
                meta: rosty_wa_proto::MessageMeta {
                    system: Some(rosty_wa_proto::SystemNote::Disappearing {
                        seconds,
                        by: Some(by),
                    }),
                    ..Default::default()
                },
                wire_chat: Some(wire_chat.to_owned()),
                ..crate::store::MessageExtras::default()
            },
        }),
        // History holds each message as it is now; reactions come with their
        // message, edits and deletes are already applied.
        Converted::Reaction { .. }
        | Converted::Edit { .. }
        | Converted::Revoke { .. }
        | Converted::Ignore => None,
    }
}

/// A conversation's mute end as the apps see it. WhatsApp has sent it in
/// seconds and in milliseconds; a huge value means "forever".
fn mute_end(raw: Option<u64>, now_ms: i64) -> Option<i64> {
    let raw = raw.filter(|&v| v > 0)?;
    let ms = match i64::try_from(raw) {
        Err(_) => return Some(MUTED_FOREVER),
        Ok(v) if v < 100_000_000_000 => v.saturating_mul(1000),
        Ok(v) => v,
    };
    if ms >= i64::MAX / 2 {
        Some(MUTED_FOREVER)
    } else {
        (ms > now_ms).then_some(ms)
    }
}

/// The chat JID to store a conversation under: the phone-number JID when
/// WhatsApp keys it by LID and gives (`pn_jid`) or the map knows the number.
fn conversation_jid(conv: &wa::Conversation, map: &LidMap) -> String {
    let id = conv.id.as_str();
    match conv.pn_jid.as_deref() {
        Some(pn) if id.ends_with("@lid") && pn.ends_with("@s.whatsapp.net") => {
            lid::resolve_str(pn, map)
        }
        _ => lid::resolve_str(id, map),
    }
}

/// The LIDs a conversation names (its id, its message authors, who
/// reacted, who is mentioned and whose message is quoted), to look up before
/// [`conversation`] runs.
pub fn conversation_lids(conv: &wa::Conversation) -> Vec<Jid> {
    let participants = conv
        .messages
        .iter()
        .filter_map(|m| m.message.as_option())
        .flat_map(|info| {
            let key = info.key.as_option();
            let named = info
                .message
                .as_option()
                .map(convert::context_jids)
                .unwrap_or_default();
            [
                info.participant.as_deref(),
                key.and_then(|k| k.participant.as_deref()),
            ]
            .into_iter()
            .flatten()
            .chain(
                info.reactions
                    .iter()
                    .filter_map(|r| r.key.as_option().and_then(|k| k.participant.as_deref())),
            )
            .map(str::to_owned)
            .chain(named.iter().map(ToString::to_string))
            .collect::<Vec<_>>()
        });
    std::iter::once(conv.id.clone())
        .chain(participants)
        .filter(|id| id.contains("@lid"))
        .filter_map(|id| id.parse::<Jid>().ok())
        .filter(Jid::is_lid)
        .collect()
}

/// The pair a conversation names: it is keyed by one id of the other person
/// and carries the other one (`pnJid` next to a LID id, `lidJid` next to a
/// phone-number id).
pub fn conversation_pair(conv: &wa::Conversation) -> Option<Pair> {
    let id = Some(conv.id.as_str());
    lid::pair_of_strs(id, conv.pn_jid.as_deref())
        .or_else(|| lid::pair_of_strs(id, conv.lid_jid.as_deref()))
}

/// The pairs of a sync's own table (`phoneNumberToLidMappings`).
pub fn mapping_pairs(sync: &wa::HistorySync) -> Vec<Pair> {
    sync.phone_number_to_lid_mappings
        .iter()
        .filter_map(|m| lid::pair_of_strs(m.lid_jid.as_deref(), m.pn_jid.as_deref()))
        .collect()
}

/// The id a conversation is keyed by in the sync, bare.
fn wire_chat(conv: &wa::Conversation) -> String {
    match conv.id.parse::<Jid>() {
        Ok(jid) => jid.to_non_ad().to_string(),
        Err(_) => conv.id.clone(),
    }
}

/// One conversation. `None` for chats the apps do not show.
pub fn conversation(conv: &wa::Conversation, now_ms: i64, map: &LidMap) -> Option<HistoryChat> {
    let jid = conversation_jid(conv, map);
    let jid = jid.as_str();
    if jid.is_empty() || is_skipped(jid) {
        return None;
    }
    let wire_chat = wire_chat(conv);
    let messages: Vec<HistoryMessage> = conv
        .messages
        .iter()
        .filter_map(|m| m.message.as_option())
        .filter_map(|info| history_message(jid, &wire_chat, info, map))
        .collect();
    let newest = messages
        .iter()
        .map(|m| m.message.timestamp_ms)
        .max()
        .unwrap_or(0);
    let stamped = conv
        .conversation_timestamp
        .or(conv.last_msg_timestamp)
        .map_or(0, seconds_to_ms);
    let name = conv
        .name
        .as_deref()
        .or(conv.display_name.as_deref())
        .map(str::trim)
        .filter(|n| !n.is_empty())
        .map(str::to_owned);
    Some(HistoryChat {
        jid: jid.to_owned(),
        name,
        is_group: convert::is_group(jid),
        unread: conv.unread_count.unwrap_or(0),
        last_ms: newest.max(stamped),
        messages,
        settings: ChatSettings {
            archived: conv.archived.unwrap_or(false),
            pinned: conv.pinned.unwrap_or(0) > 0,
            muted_until_ms: mute_end(conv.mute_end_time, now_ms),
            marked_unread: conv.marked_as_unread.unwrap_or(false),
            // The chat's disappearing timer, as of when it was set (0 when
            // the sync does not say: any live setting wins).
            ephemeral_s: conv.ephemeral_expiration,
            ephemeral_ms: conv.ephemeral_expiration.map(|_| {
                conv.ephemeral_setting_timestamp
                    .unwrap_or(0)
                    .saturating_mul(1000)
                    .max(0)
            }),
        },
    })
}

/// Every conversation of a decoded sync.
pub fn sync_to_chats(sync: &wa::HistorySync, now_ms: i64, map: &LidMap) -> Vec<HistoryChat> {
    sync.conversations
        .iter()
        .filter_map(|c| conversation(c, now_ms, map))
        .collect()
}

/// `(jid, push name)` pairs a sync carries, each under the id the person's
/// chat and messages are stored under: the phone number when `map` knows
/// the LID.
pub fn push_names(sync: &wa::HistorySync, map: &LidMap) -> Vec<(String, String)> {
    sync.pushnames
        .iter()
        .filter_map(|p| {
            let jid = p.id.as_deref()?.parse::<Jid>().ok()?;
            let name = p.pushname.as_deref()?.trim();
            (!name.is_empty() && name != "-")
                .then(|| (lid::resolve(&jid, None, map).to_string(), name.to_owned()))
        })
        .collect()
}

/// The LIDs among a sync's push names.
fn push_name_lids(sync: &wa::HistorySync) -> impl Iterator<Item = Jid> {
    sync.pushnames
        .iter()
        .filter_map(|p| p.id.as_deref()?.parse::<Jid>().ok())
        .filter(Jid::is_lid)
}

/// What one history sync holds for the store.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Drained {
    /// The conversations; empty when they were handed out in batches
    /// ([`drain_in_batches`]).
    pub chats: Vec<HistoryChat>,
    pub push_names: Vec<(String, String)>,
    /// Status updates from the `status@broadcast` conversation.
    pub statuses: Vec<StoredStatus>,
}

/// What a first pass over a sync finds about identities, before [`drain`]
/// stores anything.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Scan {
    /// Every LID the sync names, to look up
    /// ([`Resolver::prefetch`](super::lid::Resolver::prefetch)).
    pub lids: Vec<Jid>,
    /// The pairs the sync itself states: its mapping table and the two ids
    /// of each one-to-one conversation.
    pub pairs: Vec<Pair>,
}

impl Scan {
    fn add_lid(&mut self, jid: Jid) {
        if !self.lids.iter().any(|f| f.user == jid.user) {
            self.lids.push(jid);
        }
    }

    fn add_pair(&mut self, pair: Pair) {
        if !self.pairs.contains(&pair) {
            self.pairs.push(pair);
        }
    }

    fn add_conversation(&mut self, conv: &wa::Conversation) {
        for jid in conversation_lids(conv) {
            self.add_lid(jid);
        }
        if let Some(pair) = conversation_pair(conv) {
            self.add_pair(pair);
        }
    }

    /// The non-conversation part of a sync: push names and the mapping table.
    fn add_rest(&mut self, rest: &wa::HistorySync) {
        for jid in push_name_lids(rest) {
            self.add_lid(jid);
        }
        for pair in mapping_pairs(rest) {
            self.add_pair(pair);
        }
    }

    /// `map` plus the sync's own pairs, for LIDs `map` does not know.
    pub fn complete(&self, mut map: LidMap) -> LidMap {
        for pair in &self.pairs {
            map.insert_new(pair);
        }
        map
    }
}

/// Every LID and pair a sync names. Blocking like [`drain`].
pub fn scan(lazy: &LazyHistorySync) -> Result<Scan, HistorySyncError> {
    let mut stream = lazy.stream();
    let mut found = Scan::default();
    while let Some(conv) = stream.next_conversation()? {
        found.add_conversation(&conv);
    }
    // Like the push names in `drain`: a tail that does not decode costs the
    // sync's own table, not its conversations.
    match stream.remainder() {
        Ok(rest) => found.add_rest(&rest),
        Err(e) => log::debug!("history sync remainder unreadable: {e}"),
    }
    Ok(found)
}

/// Walks the lazily decoded sync one conversation at a time, so the whole
/// inflated blob never sits in memory. Blocking and CPU-heavy: call it from
/// `spawn_blocking`.
/// `own_pn` is this account's phone-number JID, the author of my own status
/// updates.
pub fn drain(
    lazy: &LazyHistorySync,
    now_ms: i64,
    own_pn: Option<&str>,
    map: &LidMap,
) -> Result<Drained, HistorySyncError> {
    let mut chats = Vec::new();
    let mut drained = drain_in_batches(lazy, now_ms, own_pn, map, usize::MAX, |batch| {
        chats.extend(batch);
        true
    })?;
    drained.chats = chats;
    Ok(drained)
}

/// [`drain`] for a large sync: the conversations are handed to `emit` in
/// batches of about `max_messages` messages (a batch is closed once it holds
/// that many; one conversation is never split), so neither the inflated blob
/// nor all of its decoded conversations sit in memory at once. `emit`
/// returns false to stop the walk. The returned [`Drained`] holds the rest
/// (push names, status updates) and no chats.
pub fn drain_in_batches(
    lazy: &LazyHistorySync,
    now_ms: i64,
    own_pn: Option<&str>,
    map: &LidMap,
    max_messages: usize,
    mut emit: impl FnMut(Vec<HistoryChat>) -> bool,
) -> Result<Drained, HistorySyncError> {
    let mut stream = lazy.stream();
    let mut chats = Vec::new();
    let mut held = 0usize;
    let mut statuses = Vec::new();
    while let Some(conv) = stream.next_conversation()? {
        if is_status_chat(&conv.id) {
            statuses.extend(status::from_history(&conv, own_pn));
            continue;
        }
        let Some(chat) = conversation(&conv, now_ms, map) else {
            continue;
        };
        held = held.saturating_add(chat.messages.len());
        chats.push(chat);
        if held >= max_messages {
            held = 0;
            if !emit(std::mem::take(&mut chats)) {
                return Ok(Drained::default());
            }
        }
    }
    if !chats.is_empty() && !emit(std::mem::take(&mut chats)) {
        return Ok(Drained::default());
    }
    // Push names are optional: a sync whose tail does not decode still
    // delivers its chats.
    let push_names = match stream.remainder() {
        Ok(rest) => push_names(&rest, map),
        Err(e) => {
            log::debug!("history sync remainder unreadable: {e}");
            Vec::new()
        }
    };
    Ok(Drained {
        chats,
        push_names,
        statuses,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{InstanceId, Store};

    /// The props reach the payload a device registers with, and only that
    /// one: a device that is already paired logs in without them.
    #[test]
    fn a_full_history_is_asked_for_when_a_device_registers() {
        use whatsapp_rust::wacore::store::Device;
        use whatsapp_rust::waproto::whatsapp::device_props::PlatformType;

        assert!(device_props(false).is_none());
        let props = device_props(true).unwrap();
        assert_eq!(props.require_full_sync, Some(true));
        assert_eq!(props.platform_type, Some(PlatformType::UWP));
        assert_eq!(props.os.as_deref(), Some("Windows"));
        let config = props.history_sync_config.clone().unwrap();
        assert_eq!(config.full_sync_days_limit, Some(FULL_SYNC_DAYS));
        assert_eq!(config.on_demand_ready, Some(true));
        assert_eq!(config.complete_on_demand_ready, Some(true));
        // The library's defaults stay next to them.
        assert_eq!(config.support_group_history, Some(true));

        let mut device = Device::new();
        device.set_device_props(props);
        let registering = device.get_client_payload();
        assert!(
            registering
                .device_pairing_data
                .as_option()
                .is_some_and(|data| data.device_props.is_some())
        );
        device.pn = Some("100000000001@s.whatsapp.net".parse().unwrap());
        assert!(device.get_client_payload().device_pairing_data.is_unset());
    }

    #[test]
    fn sync_kinds_follow_whatsapps_numbers() {
        use wa::message::HistorySyncType as Wire;
        let cases = [
            (Wire::INITIAL_BOOTSTRAP, HistorySyncType::Initial),
            (Wire::RECENT, HistorySyncType::Recent),
            (Wire::FULL, HistorySyncType::Full),
            (Wire::ON_DEMAND, HistorySyncType::OnDemand),
            (Wire::INITIAL_STATUS_V3, HistorySyncType::Other),
            (Wire::PUSH_NAME, HistorySyncType::Other),
            (Wire::NON_BLOCKING_DATA, HistorySyncType::Other),
        ];
        for (wire, kind) in cases {
            assert_eq!(sync_kind(wire as i32), kind);
        }
        assert_eq!(sync_kind(99), HistorySyncType::Other);
        assert_eq!(sync_kind(-1), HistorySyncType::Other);
    }

    /// `raw` as a zlib stream of stored (uncompressed) deflate blocks, which
    /// is all a test needs to feed the library's inflater.
    fn zlib_stored(raw: &[u8]) -> Vec<u8> {
        let mut out = vec![0x78, 0x01];
        let mut blocks = raw.chunks(usize::from(u16::MAX)).peekable();
        if blocks.peek().is_none() {
            out.extend([1, 0, 0, 0xff, 0xff]);
        }
        while let Some(block) = blocks.next() {
            let len = block.len() as u16;
            out.push(u8::from(blocks.peek().is_none()));
            out.extend(len.to_le_bytes());
            out.extend((!len).to_le_bytes());
            out.extend(block);
        }
        let (mut a, mut b) = (1u32, 0u32);
        for &byte in raw {
            a = (a + u32::from(byte)) % 65521;
            b = (b + a) % 65521;
        }
        out.extend(((b << 16) | a).to_be_bytes());
        out
    }

    fn lazy(sync: &wa::HistorySync) -> LazyHistorySync {
        let raw = sync.encode_to_vec();
        LazyHistorySync::new(zlib_stored(&raw).into(), raw.len(), 3, None, Some(50))
    }

    /// A big chunk comes out in bounded batches, in order, and holds the
    /// same rows as the walk in one go.
    #[test]
    fn a_large_sync_is_handed_out_in_batches() {
        let conversations = (0..7)
            .map(|c| wa::Conversation {
                id: format!("10000000000{c}@s.whatsapp.net"),
                messages: (0..3)
                    .map(|m| web_message(&format!("C{c}M{m}"), false, 1_700_000_000 + m, "x", None))
                    .collect(),
                ..Default::default()
            })
            .collect();
        let sync = wa::HistorySync {
            conversations,
            pushnames: vec![wa::Pushname {
                id: Some("100000000001@s.whatsapp.net".into()),
                pushname: Some("Ada".into()),
            }],
            ..Default::default()
        };
        let lazy = lazy(&sync);
        let map = LidMap::default();

        let whole = drain(&lazy, 0, None, &map).unwrap();
        assert_eq!(whole.chats.len(), 7);
        assert_eq!(whole.push_names.len(), 1);

        let mut batches = Vec::new();
        let rest = drain_in_batches(&lazy, 0, None, &map, 5, |batch| {
            batches.push(batch);
            true
        })
        .unwrap();
        // Two chats (six messages) close a batch of five; the last one is
        // what is left.
        assert_eq!(
            batches.iter().map(Vec::len).collect::<Vec<_>>(),
            [2, 2, 2, 1]
        );
        assert!(rest.chats.is_empty());
        assert_eq!(rest.push_names, whole.push_names);
        assert_eq!(batches.concat(), whole.chats);

        // The receiver went away: the walk stops at once.
        let mut seen = 0;
        drain_in_batches(&lazy, 0, None, &map, 5, |_| {
            seen += 1;
            false
        })
        .unwrap();
        assert_eq!(seen, 1);
    }
    use rosty_wa_proto::{ConnectKey, KeyId};
    use whatsapp_rust::waproto::buffa::MessageField;

    fn web_message(
        id: &str,
        from_me: bool,
        ts: u64,
        text: &str,
        participant: Option<&str>,
    ) -> wa::HistorySyncMsg {
        wa::HistorySyncMsg {
            message: MessageField::some(wa::WebMessageInfo {
                key: MessageField::some(wa::MessageKey {
                    remote_jid: Some("ignored".into()),
                    from_me: Some(from_me),
                    id: Some(id.into()),
                    participant: participant.map(str::to_owned),
                }),
                message: MessageField::some(wa::Message {
                    conversation: Some(text.to_owned()),
                    ..Default::default()
                }),
                message_timestamp: Some(ts),
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    fn sync() -> wa::HistorySync {
        let direct = wa::Conversation {
            id: "905321234567@s.whatsapp.net".into(),
            name: Some("Ayse".into()),
            unread_count: Some(1),
            messages: vec![
                web_message("M1", false, 1_700_000_000, "selam", None),
                web_message("M2", true, 1_700_000_060, "merhaba", None),
            ],
            ..Default::default()
        };
        let group = wa::Conversation {
            id: "123-456@g.us".into(),
            messages: vec![web_message(
                "G1",
                false,
                1_700_000_100,
                "hi all",
                Some("4915123456789:7@s.whatsapp.net"),
            )],
            ..Default::default()
        };
        let status = wa::Conversation {
            id: "status@broadcast".into(),
            messages: vec![web_message("S1", false, 1, "story", None)],
            ..Default::default()
        };
        wa::HistorySync {
            conversations: vec![direct, group, status],
            ..Default::default()
        }
    }

    #[test]
    fn maps_conversations_and_skips_status() {
        let chats = sync_to_chats(&sync(), 0, &LidMap::default());
        assert_eq!(chats.len(), 2);

        let direct = &chats[0];
        assert_eq!(direct.jid, "905321234567@s.whatsapp.net");
        assert_eq!(direct.name.as_deref(), Some("Ayse"));
        assert!(!direct.is_group);
        assert_eq!(direct.unread, 1);
        assert_eq!(direct.last_ms, 1_700_000_060_000);
        assert_eq!(direct.messages.len(), 2);
        let first = &direct.messages[0].message;
        assert_eq!(first.id, "M1");
        assert!(!first.from_me);
        assert_eq!(first.chat, direct.jid);
        assert_eq!(first.timestamp_ms, 1_700_000_000_000);
        assert_eq!(first.sender, None);
        assert!(direct.messages[1].message.from_me);

        let group = &chats[1];
        assert!(group.is_group);
        assert_eq!(
            group.messages[0].message.sender.as_deref(),
            Some("4915123456789@s.whatsapp.net")
        );
    }

    #[test]
    fn messages_without_id_or_content_are_dropped() {
        let mut broken = web_message("", false, 1, "x", None);
        let conv = wa::Conversation {
            id: "1@s.whatsapp.net".into(),
            messages: vec![broken.clone(), {
                broken.message = MessageField::none();
                broken
            }],
            ..Default::default()
        };
        let chat = conversation(&conv, 0, &LidMap::default()).unwrap();
        assert!(chat.messages.is_empty());
    }

    #[tokio::test]
    async fn rows_land_in_the_store_in_one_import() {
        let store = Store::open_in_memory().unwrap();
        let id = InstanceId::from_bytes(&[1; 12]);
        let key = ConnectKey::from_bytes(&[1; 32]);
        store
            .create_instance(
                id.clone(),
                crate::store::NewKey {
                    key_id: KeyId::from_bytes(&[1; 8]),
                    hash: key.hash(),
                    label: "k".into(),
                },
                1,
                4,
            )
            .await
            .unwrap();

        let chats = sync_to_chats(&sync(), 0, &LidMap::default());
        assert_eq!(
            store
                .import_history(id.clone(), chats.clone())
                .await
                .unwrap(),
            crate::store::HistoryImport {
                chats: 2,
                messages: 3,
                oldest_ms: Some(1_700_000_000_000),
            }
        );

        let summaries = store.chats(id.clone()).await.unwrap();
        assert_eq!(summaries.len(), 2);
        // Newest first: the group message is the latest.
        assert_eq!(summaries[0].jid, "123-456@g.us");
        assert!(summaries[0].is_group);
        assert_eq!(summaries[1].name.as_deref(), Some("Ayse"));
        assert_eq!(summaries[1].unread, 1);
        assert_eq!(
            summaries[1].last.as_ref().map(|m| m.id.as_str()),
            Some("M2")
        );

        let messages = store
            .messages(id.clone(), "905321234567@s.whatsapp.net".into(), None, 10)
            .await
            .unwrap();
        assert_eq!(
            messages.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            ["M2", "M1"]
        );
        assert_eq!(messages[1].body, MessageBody::Text("selam".into()));

        // A second import of the same sync changes nothing.
        let again = store.import_history(id.clone(), chats).await.unwrap();
        assert_eq!((again.chats, again.messages), (2, 0));
        let again = store
            .messages(id, "905321234567@s.whatsapp.net".into(), None, 10)
            .await
            .unwrap();
        assert_eq!(again.len(), 2);
    }

    #[test]
    fn ticks_stars_reactions_and_push_names_come_along() {
        let mut mine = web_message("M", true, 10, "x", None);
        let info = mine.message.as_option_mut().unwrap();
        info.status = Some(WebStatus::READ);
        info.starred = Some(true);
        info.reactions = vec![wa::Reaction {
            key: MessageField::some(wa::MessageKey {
                from_me: Some(false),
                ..Default::default()
            }),
            text: Some("\u{1F44D}".into()),
            ..Default::default()
        }];
        let mut theirs = web_message("T", false, 11, "y", None);
        theirs.message.as_option_mut().unwrap().push_name = Some("Ayse".into());
        let conv = wa::Conversation {
            id: "1234@lid".into(),
            pn_jid: Some("905321234567@s.whatsapp.net".into()),
            archived: Some(true),
            pinned: Some(1_700_000_000),
            mute_end_time: Some(u64::MAX),
            messages: vec![mine, theirs],
            ..Default::default()
        };
        let chat = conversation(&conv, 0, &LidMap::default()).unwrap();
        assert_eq!(
            chat.jid, "905321234567@s.whatsapp.net",
            "LID chats use the number"
        );
        assert_eq!(
            chat.settings,
            ChatSettings {
                archived: true,
                pinned: true,
                muted_until_ms: Some(MUTED_FOREVER),
                marked_unread: false,
                ..ChatSettings::default()
            }
        );
        let m = &chat.messages[0];
        assert_eq!(m.extras.meta.status, Some(DeliveryStatus::Read));
        assert!(m.extras.meta.starred);
        assert_eq!(m.reactions.len(), 1);
        assert!(!m.reactions[0].from_me);
        assert_eq!(
            chat.messages[1].extras.meta.sender_name.as_deref(),
            Some("Ayse")
        );

        let sync = wa::HistorySync {
            pushnames: vec![
                wa::Pushname {
                    id: Some("905321234567@s.whatsapp.net".into()),
                    pushname: Some("Ayse".into()),
                },
                wa::Pushname {
                    id: Some("bad jid@@".into()),
                    pushname: Some("x".into()),
                },
                wa::Pushname {
                    id: Some("1@s.whatsapp.net".into()),
                    pushname: Some("-".into()),
                },
            ],
            ..Default::default()
        };
        assert_eq!(
            push_names(&sync, &LidMap::default()),
            [("905321234567@s.whatsapp.net".to_owned(), "Ayse".to_owned())]
        );
    }

    #[test]
    fn the_map_supplies_the_number_of_a_lid_chat() {
        let lid_author = "555:3@lid";
        let conv = wa::Conversation {
            id: "1234@lid".into(),
            messages: vec![web_message("M", false, 10, "x", None)],
            ..Default::default()
        };
        let group = wa::Conversation {
            id: "1-2@g.us".into(),
            messages: vec![web_message("G", false, 11, "y", Some(lid_author))],
            ..Default::default()
        };
        let none = LidMap::default();
        let known = LidMap::from_entries([("1234", "905321234567"), ("555", "4915")]);

        assert_eq!(conversation(&conv, 0, &none).unwrap().jid, "1234@lid");
        let chat = conversation(&conv, 0, &known).unwrap();
        assert_eq!(chat.jid, "905321234567@s.whatsapp.net");
        assert_eq!(chat.messages[0].message.chat, chat.jid);

        let chat = conversation(&group, 0, &none).unwrap();
        assert_eq!(chat.messages[0].message.sender.as_deref(), Some("555@lid"));
        let chat = conversation(&group, 0, &known).unwrap();
        assert_eq!(chat.jid, "1-2@g.us");
        assert_eq!(
            chat.messages[0].message.sender.as_deref(),
            Some("4915@s.whatsapp.net")
        );

        let users: Vec<String> = conversation_lids(&conv)
            .into_iter()
            .chain(conversation_lids(&group))
            .map(|j| j.user.to_string())
            .collect();
        assert_eq!(users, ["1234", "555"]);
    }

    #[test]
    fn mute_ends_in_seconds_or_millis() {
        let now = 1_700_000_000_000;
        assert_eq!(mute_end(None, now), None);
        assert_eq!(mute_end(Some(0), now), None);
        assert_eq!(mute_end(Some(1_800_000_000), now), Some(1_800_000_000_000));
        assert_eq!(
            mute_end(Some(1_800_000_000_000), now),
            Some(1_800_000_000_000)
        );
        assert_eq!(mute_end(Some(1_600_000_000), now), None, "already over");
        assert_eq!(mute_end(Some(u64::MAX), now), Some(MUTED_FOREVER));
    }

    const PEER_PN: &str = "905550000001@s.whatsapp.net";

    #[test]
    fn a_sync_states_pairs() {
        let pair = |lid: &str, pn: &str| lid::pair_of_strs(Some(lid), Some(pn)).unwrap();
        // A conversation keyed by the LID that names the number, and one
        // keyed by the number that names the LID.
        let by_lid = wa::Conversation {
            id: "1234@lid".into(),
            pn_jid: Some(PEER_PN.into()),
            ..Default::default()
        };
        assert_eq!(conversation_pair(&by_lid), Some(pair("1234@lid", PEER_PN)));
        let by_pn = wa::Conversation {
            id: "905550000002@s.whatsapp.net".into(),
            lid_jid: Some("55:3@lid".into()),
            ..Default::default()
        };
        assert_eq!(
            conversation_pair(&by_pn),
            Some(pair("55@lid", "905550000002@s.whatsapp.net"))
        );
        let group = wa::Conversation {
            id: "1-2@g.us".into(),
            pn_jid: Some(PEER_PN.into()),
            ..Default::default()
        };
        assert_eq!(conversation_pair(&group), None);
        assert_eq!(conversation_pair(&wa::Conversation::default()), None);

        // The sync's own table; rows that are not a pair are skipped.
        let mapping = |pn: Option<&str>, lid: Option<&str>| wa::PhoneNumberToLIDMapping {
            pn_jid: pn.map(str::to_owned),
            lid_jid: lid.map(str::to_owned),
        };
        let rest = wa::HistorySync {
            phone_number_to_lid_mappings: vec![
                mapping(Some("905550000003@s.whatsapp.net"), Some("66@lid")),
                mapping(Some("905550000004@s.whatsapp.net"), None),
                mapping(Some("nonsense"), Some("67@lid")),
                mapping(Some("68@lid"), Some("905550000005@s.whatsapp.net")),
            ],
            pushnames: vec![
                wa::Pushname {
                    id: Some("70@lid".into()),
                    pushname: Some("Ada".into()),
                },
                wa::Pushname {
                    id: Some(PEER_PN.into()),
                    pushname: Some("Bob".into()),
                },
            ],
            ..Default::default()
        };
        assert_eq!(
            mapping_pairs(&rest),
            [
                pair("66@lid", "905550000003@s.whatsapp.net"),
                pair("68@lid", "905550000005@s.whatsapp.net"),
            ]
        );

        let mut scan = Scan::default();
        scan.add_conversation(&by_lid);
        scan.add_conversation(&by_lid);
        scan.add_conversation(&by_pn);
        scan.add_rest(&rest);
        assert_eq!(scan.pairs.len(), 4, "each pair once");
        let lids: Vec<String> = scan.lids.iter().map(|j| j.user.to_string()).collect();
        assert_eq!(lids, ["1234", "70"]);

        // The lookups win; the sync fills what they do not know.
        let looked_up = LidMap::from_entries([("1234", "905550000009")]);
        let map = scan.complete(looked_up);
        assert_eq!(map.get("1234"), Some("905550000009"));
        assert_eq!(map.get("55"), Some("905550000002"));
        assert_eq!(map.get("66"), Some("905550000003"));
    }

    #[test]
    fn push_names_sit_under_the_id_the_chat_is_stored_under() {
        let sync = wa::HistorySync {
            pushnames: vec![
                wa::Pushname {
                    id: Some("1234:2@lid".into()),
                    pushname: Some("Ada".into()),
                },
                wa::Pushname {
                    id: Some("9999@lid".into()),
                    pushname: Some("Hidden".into()),
                },
            ],
            ..Default::default()
        };
        let known = LidMap::from_entries([("1234", "905550000001")]);
        assert_eq!(
            push_names(&sync, &known),
            [
                (PEER_PN.to_owned(), "Ada".to_owned()),
                ("9999@lid".to_owned(), "Hidden".to_owned()),
            ]
        );
        assert_eq!(push_names(&sync, &LidMap::default())[0].0, "1234@lid");
    }

    #[test]
    fn history_rows_keep_their_wire_ids_and_name_people_by_number() {
        let known = LidMap::from_entries([("1234", "905550000001"), ("555", "905550000002")]);
        // A one-to-one conversation keyed by the LID.
        let direct = wa::Conversation {
            id: "1234@lid".into(),
            messages: vec![web_message("M", false, 10, "x", None)],
            ..Default::default()
        };
        let chat = conversation(&direct, 0, &known).unwrap();
        assert_eq!(chat.jid, PEER_PN);
        let row = &chat.messages[0];
        assert_eq!(row.extras.wire_chat.as_deref(), Some("1234@lid"));
        assert_eq!(row.extras.wire_sender, None);

        // A group message from a LID that quotes and mentions LIDs.
        let mut quoting = web_message("G", false, 11, "y", Some("555:3@lid"));
        quoting.message.as_option_mut().unwrap().message = MessageField::some(wa::Message {
            extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                text: Some("re".into()),
                context_info: MessageField::some(wa::ContextInfo {
                    stanza_id: Some("Q".into()),
                    participant: Some("1234:1@lid".into()),
                    mentioned_jid: vec!["1234@lid".into(), "8888@lid".into()],
                    ..Default::default()
                }),
                ..Default::default()
            }),
            ..Default::default()
        });
        let group = wa::Conversation {
            id: "1-2@g.us".into(),
            messages: vec![quoting],
            ..Default::default()
        };
        // The quoted author and the mentions are looked up too.
        let users: Vec<String> = conversation_lids(&group)
            .iter()
            .map(|j| j.user.to_string())
            .collect();
        assert_eq!(users, ["555", "1234", "8888", "1234"]);

        let chat = conversation(&group, 0, &known).unwrap();
        let row = &chat.messages[0];
        assert_eq!(
            row.message.sender.as_deref(),
            Some("905550000002@s.whatsapp.net")
        );
        assert_eq!(row.extras.wire_chat.as_deref(), Some("1-2@g.us"));
        assert_eq!(row.extras.wire_sender.as_deref(), Some("555@lid"));
        assert_eq!(
            row.extras.meta.quoted.as_ref().unwrap().sender.as_deref(),
            Some(PEER_PN)
        );
        assert_eq!(row.extras.meta.mentions, [PEER_PN, "8888@lid"]);
    }
}
