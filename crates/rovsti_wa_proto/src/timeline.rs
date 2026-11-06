//! Pure conversation model: merges history pages, live events and optimistic
//! sends into one ascending list the UI can render. No I/O, no clock.

use std::collections::HashMap;

use rovsti_core::PhoneNumber;

use crate::api::{LinkError, MediaKind, MessageBody, Reaction, Sent, WaMessage};
use crate::event::{GatewayEvent, ReceiptKind};
use crate::meta::{DeliveryStatus, MessageMeta, RichMessage};

/// Delivery state of one timeline item. The order of `Sent < Delivered < Read`
/// is the order receipts may move it in.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ItemState {
    Sent,
    Delivered,
    Read,
    Pending { local_id: u64 },
    Failed { local_id: u64 },
}

impl ItemState {
    fn rank(self) -> Option<u8> {
        match self {
            Self::Sent => Some(0),
            Self::Delivered => Some(1),
            Self::Read => Some(2),
            Self::Pending { .. } | Self::Failed { .. } => None,
        }
    }

    fn local_id(self) -> Option<u64> {
        match self {
            Self::Pending { local_id } | Self::Failed { local_id } => Some(local_id),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Item {
    pub message: WaMessage,
    pub state: ItemState,
}

#[derive(Clone, Debug)]
pub struct Timeline {
    chat: String,
    items: Vec<Item>,
    has_older: bool,
    /// Non-empty [`MessageMeta`] by message id.
    metas: HashMap<String, MessageMeta>,
}

impl Timeline {
    pub fn new(chat: impl Into<String>) -> Self {
        Self {
            chat: chat.into(),
            items: Vec::new(),
            has_older: false,
            metas: HashMap::new(),
        }
    }

    /// Like [`Self::apply_page`] for a page with meta
    /// (`Gateway::messages_with_meta`): also keeps each message's meta and
    /// starts my messages at the ticks the gateway stored.
    pub fn apply_rich_page(&mut self, page: Vec<RichMessage>, limit: u32) {
        self.has_older = page.len() == limit as usize;
        for RichMessage { message, meta } in page {
            if message.chat != self.chat {
                continue;
            }
            let id = message.id.clone();
            self.upsert(message);
            self.set_meta(&id, meta);
        }
    }

    /// The meta of a message, when it has any.
    pub fn meta(&self, id: &str) -> Option<&MessageMeta> {
        self.metas.get(id)
    }

    /// Stores `meta` for `id` and moves its ticks forward. Returns `true` on
    /// change.
    fn set_meta(&mut self, id: &str, meta: MessageMeta) -> bool {
        let mut changed = false;
        if let Some(status) = meta.status
            && let Some(item) = self.items.iter_mut().find(|i| i.message.id == id)
        {
            changed |= upgrade(&mut item.state, state_of(status));
        }
        if meta.is_empty() {
            changed |= self.metas.remove(id).is_some();
        } else if self.metas.get(id) != Some(&meta) {
            self.metas.insert(id.to_owned(), meta);
            changed = true;
        }
        changed
    }

    /// A `GET /v1/chats/{jid}/messages` page (newest first). Dedups by id, keeps
    /// ascending time order, sets `has_older = page.len() == limit as usize`.
    pub fn apply_page(&mut self, page: Vec<WaMessage>, limit: u32) {
        self.has_older = page.len() == limit as usize;
        for message in page {
            if message.chat == self.chat {
                self.upsert(message);
            }
        }
    }

    /// Applies `Message`, `MessageUpdate` and `Receipt` events of this chat.
    /// Returns `true` when the timeline changed.
    pub fn apply_event(&mut self, event: &GatewayEvent) -> bool {
        match event {
            GatewayEvent::Message(m) if m.chat == self.chat => self.upsert(m.clone()),
            GatewayEvent::MessageUpdate {
                chat,
                id,
                reactions,
                read,
            } if *chat == self.chat => self.update(id, reactions, *read),
            GatewayEvent::MessageMeta {
                chat,
                id,
                body,
                meta,
            } if *chat == self.chat => {
                let mut changed = false;
                if let Some(body) = body
                    && let Some(item) = self.items.iter_mut().find(|i| i.message.id == *id)
                    && item.message.body != *body
                {
                    item.message.body = body.clone();
                    changed = true;
                }
                changed | self.set_meta(id, (**meta).clone())
            }
            GatewayEvent::MessageDeleted { chat, id } if *chat == self.chat => {
                self.metas.remove(id);
                let before = self.items.len();
                self.items.retain(|i| i.message.id != *id);
                self.items.len() != before
            }
            GatewayEvent::ChatCleared { jid } | GatewayEvent::ChatDeleted { jid }
                if *jid == self.chat =>
            {
                let changed = !self.items.is_empty();
                self.items.retain(|i| i.state.local_id().is_some());
                self.metas.clear();
                changed
            }
            GatewayEvent::Receipt { chat, ids, kind } if *chat == self.chat => {
                let to = match kind {
                    ReceiptKind::Delivered => ItemState::Delivered,
                    ReceiptKind::Read => ItemState::Read,
                };
                let mut changed = false;
                for id in ids {
                    if let Some(item) = self.items.iter_mut().find(|i| i.message.id == *id) {
                        changed |= upgrade(&mut item.state, to);
                    }
                }
                changed
            }
            _ => false,
        }
    }

    /// Adds an optimistic outgoing text message.
    pub fn push_pending(&mut self, local_id: u64, text: String, now_ms: i64) {
        if self.position_of_local(local_id).is_some() {
            return;
        }
        let message = WaMessage {
            id: format!("pending-{local_id}"),
            chat: self.chat.clone(),
            sender: None,
            from_me: true,
            timestamp_ms: now_ms,
            body: MessageBody::Text(text),
            reactions: Vec::new(),
        };
        self.insert_sorted(Item {
            message,
            state: ItemState::Pending { local_id },
        });
    }

    /// Adds an optimistic outgoing media message. Dedups like
    /// [`Self::push_pending`].
    pub fn push_pending_media(
        &mut self,
        local_id: u64,
        kind: MediaKind,
        mime: String,
        caption: Option<String>,
        size: u64,
        now_ms: i64,
    ) {
        if self.position_of_local(local_id).is_some() {
            return;
        }
        let message = WaMessage {
            id: format!("pending-{local_id}"),
            chat: self.chat.clone(),
            sender: None,
            from_me: true,
            timestamp_ms: now_ms,
            body: MessageBody::Media {
                kind,
                caption,
                mime,
                size,
            },
            reactions: Vec::new(),
        };
        self.insert_sorted(Item {
            message,
            state: ItemState::Pending { local_id },
        });
    }

    /// Swaps the pending item for the real id. If our own echo already arrived
    /// under that id, the pending item is simply dropped.
    pub fn confirm(&mut self, local_id: u64, sent: &Sent) {
        let Some(pos) = self.position_of_local(local_id) else {
            return;
        };
        if self.items.iter().any(|i| i.message.id == sent.message_id) {
            self.items.remove(pos);
            return;
        }
        let mut item = self.items.remove(pos);
        item.message.id = sent.message_id.clone();
        item.message.timestamp_ms = sent.timestamp_ms;
        item.state = ItemState::Sent;
        self.insert_sorted(item);
    }

    /// Marks a pending send as failed; the text stays so the UI can retry.
    pub fn fail(&mut self, local_id: u64) {
        if let Some(pos) = self.position_of_local(local_id) {
            self.items[pos].state = ItemState::Failed { local_id };
        }
    }

    /// Takes a failed send back to pending (the retry tap) and returns its
    /// text. `None` when `local_id` is not a failed text item.
    pub fn restart(&mut self, local_id: u64) -> Option<String> {
        let pos = self
            .items
            .iter()
            .position(|i| i.state == ItemState::Failed { local_id })?;
        let MessageBody::Text(text) = &self.items[pos].message.body else {
            return None;
        };
        let text = text.clone();
        self.items[pos].state = ItemState::Pending { local_id };
        Some(text)
    }

    /// Takes any failed send (text or media) back to pending and returns its
    /// body so the caller can resend it. `None` when `local_id` is not failed.
    pub fn restart_body(&mut self, local_id: u64) -> Option<MessageBody> {
        let pos = self
            .items
            .iter()
            .position(|i| i.state == ItemState::Failed { local_id })?;
        self.items[pos].state = ItemState::Pending { local_id };
        Some(self.items[pos].message.body.clone())
    }

    /// Optimistically sets our own reaction on a message (`""` removes it),
    /// replacing any earlier one of ours. The gateway's `MessageUpdate`
    /// overwrites this later. Returns `true` when something changed.
    pub fn set_own_reaction(&mut self, id: &str, emoji: &str) -> bool {
        let Some(item) = self.items.iter_mut().find(|i| i.message.id == id) else {
            return false;
        };
        let before = item.message.reactions.clone();
        item.message.reactions.retain(|r| !r.from_me);
        if !emoji.is_empty() {
            item.message.reactions.push(Reaction {
                sender: None,
                from_me: true,
                emoji: emoji.to_owned(),
            });
        }
        item.message.reactions != before
    }

    /// Timestamp of the oldest confirmed message, for `before_ms` paging.
    pub fn oldest_ms(&self) -> Option<i64> {
        self.items
            .iter()
            .find(|i| i.state.local_id().is_none())
            .map(|i| i.message.timestamp_ms)
    }

    /// Incoming messages not yet known to be read.
    pub fn unread_ids(&self) -> Vec<String> {
        self.items
            .iter()
            .filter(|i| !i.message.from_me && i.state != ItemState::Read)
            .map(|i| i.message.id.clone())
            .collect()
    }

    pub fn items(&self) -> &[Item] {
        &self.items
    }

    pub fn has_older(&self) -> bool {
        self.has_older
    }

    fn position_of_local(&self, local_id: u64) -> Option<usize> {
        self.items
            .iter()
            .position(|i| i.state.local_id() == Some(local_id))
    }

    fn insert_sorted(&mut self, item: Item) {
        let at = self
            .items
            .partition_point(|i| i.message.timestamp_ms <= item.message.timestamp_ms);
        self.items.insert(at, item);
    }

    /// Inserts a confirmed message, or refreshes the existing one with the
    /// same id (keeping the furthest delivery state). Returns `true` on change.
    fn upsert(&mut self, message: WaMessage) -> bool {
        if let Some(pos) = self.items.iter().position(|i| i.message.id == message.id) {
            if self.items[pos].message == message {
                return false;
            }
            let moved = self.items[pos].message.timestamp_ms != message.timestamp_ms;
            self.items[pos].message = message;
            if moved {
                let item = self.items.remove(pos);
                self.insert_sorted(item);
            }
            return true;
        }
        self.insert_sorted(Item {
            message,
            state: ItemState::Sent,
        });
        true
    }

    fn update(&mut self, id: &str, reactions: &[Reaction], read: bool) -> bool {
        let Some(item) = self.items.iter_mut().find(|i| i.message.id == id) else {
            return false;
        };
        let mut changed = false;
        if item.message.reactions != reactions {
            item.message.reactions = reactions.to_vec();
            changed = true;
        }
        if read {
            changed |= upgrade(&mut item.state, ItemState::Read);
        }
        changed
    }
}

/// The item state that shows `status`.
fn state_of(status: DeliveryStatus) -> ItemState {
    match status {
        DeliveryStatus::Sent => ItemState::Sent,
        DeliveryStatus::Delivered => ItemState::Delivered,
        DeliveryStatus::Read | DeliveryStatus::Played => ItemState::Read,
    }
}

/// Moves `state` to `to` only when that is strictly further along.
fn upgrade(state: &mut ItemState, to: ItemState) -> bool {
    match (state.rank(), to.rank()) {
        (Some(from), Some(target)) if target > from => {
            *state = to;
            true
        }
        _ => false,
    }
}

/// `+905321234567` becomes `905321234567@s.whatsapp.net`.
pub fn user_jid(number: &str) -> Result<String, LinkError> {
    let parsed = PhoneNumber::parse(number)?;
    let user = parsed
        .whatsapp_user()
        .ok_or(LinkError::MissingCountryCode)?;
    Ok(format!("{user}@s.whatsapp.net"))
}

/// Day separators: `(index, day_start_ms)` for items whose local day differs
/// from the previous item (the first item always starts a day).
pub fn day_breaks(items: &[Item], utc_offset_s: i32) -> Vec<(usize, i64)> {
    const DAY_S: i64 = 86_400;
    let offset = i64::from(utc_offset_s);
    let day_of = |ms: i64| (ms.div_euclid(1000) + offset).div_euclid(DAY_S);
    let mut out = Vec::new();
    let mut previous = None;
    for (index, item) in items.iter().enumerate() {
        let day = day_of(item.message.timestamp_ms);
        if previous != Some(day) {
            out.push((index, (day * DAY_S - offset) * 1000));
            previous = Some(day);
        }
    }
    out
}

/// Reaction chips under a bubble: `(emoji, count, mine)` in order of first
/// appearance. Empty emojis (removed reactions) are skipped.
pub fn reaction_chips(reactions: &[Reaction]) -> Vec<(String, u32, bool)> {
    let mut chips: Vec<(String, u32, bool)> = Vec::new();
    for reaction in reactions.iter().filter(|r| !r.emoji.is_empty()) {
        match chips
            .iter_mut()
            .find(|(emoji, ..)| *emoji == reaction.emoji)
        {
            Some((_, count, mine)) => {
                *count += 1;
                *mine |= reaction.from_me;
            }
            None => chips.push((reaction.emoji.clone(), 1, reaction.from_me)),
        }
    }
    chips
}

/// Decides when the composer may tell the gateway "typing": at most one
/// `Typing` per [`TypingThrottle::INTERVAL_MS`], and a `Paused` only after a
/// `Typing` went out.
#[derive(Clone, Copy, Debug, Default)]
pub struct TypingThrottle {
    last_typing_ms: Option<i64>,
}

impl TypingThrottle {
    pub const INTERVAL_MS: i64 = 5_000;

    pub fn new() -> Self {
        Self::default()
    }

    /// The composer text changed: `true` when a `Typing` should be sent now.
    pub fn on_edit(&mut self, now_ms: i64) -> bool {
        let due = self
            .last_typing_ms
            .is_none_or(|last| now_ms.saturating_sub(last) >= Self::INTERVAL_MS);
        if due {
            self.last_typing_ms = Some(now_ms);
        }
        due
    }

    /// The message was sent (or the text cleared): `true` when a `Paused`
    /// should be sent. Resets the throttle.
    pub fn on_send(&mut self) -> bool {
        self.last_typing_ms.take().is_some()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CHAT: &str = "1@s.whatsapp.net";

    #[test]
    fn rich_pages_and_meta_events() {
        let mut t = Timeline::new(CHAT);
        let page = vec![
            RichMessage {
                message: msg("b", 20, true),
                meta: MessageMeta {
                    status: Some(DeliveryStatus::Read),
                    ..Default::default()
                },
            },
            RichMessage::from(msg("a", 10, false)),
        ];
        t.apply_rich_page(page, 2);
        assert!(t.has_older());
        assert_eq!(t.items()[1].state, ItemState::Read);
        assert!(t.meta("a").is_none());
        assert_eq!(t.meta("b").unwrap().status, Some(DeliveryStatus::Read));

        // An edit replaces the body and keeps the meta.
        let edit = GatewayEvent::MessageMeta {
            chat: CHAT.into(),
            id: "a".into(),
            body: Some(MessageBody::Text("fixed".into())),
            meta: Box::new(MessageMeta {
                edited_ms: Some(30),
                ..Default::default()
            }),
        };
        assert!(t.apply_event(&edit));
        assert!(!t.apply_event(&edit));
        assert_eq!(t.items()[0].message.body, MessageBody::Text("fixed".into()));
        assert_eq!(t.meta("a").unwrap().edited_ms, Some(30));

        // Other chats are ignored; a delete for me drops the item.
        let other = GatewayEvent::MessageDeleted {
            chat: "x".into(),
            id: "a".into(),
        };
        assert!(!t.apply_event(&other));
        assert!(t.apply_event(&GatewayEvent::MessageDeleted {
            chat: CHAT.into(),
            id: "a".into(),
        }));
        assert_eq!(t.items().len(), 1);
        assert!(t.meta("a").is_none());

        // Clearing the chat keeps only pending sends.
        t.push_pending(1, "later".into(), 40);
        assert!(t.apply_event(&GatewayEvent::ChatCleared { jid: CHAT.into() }));
        assert_eq!(t.items().len(), 1);
        assert!(matches!(t.items()[0].state, ItemState::Pending { .. }));
    }

    fn msg(id: &str, ts: i64, from_me: bool) -> WaMessage {
        WaMessage {
            id: id.into(),
            chat: CHAT.into(),
            sender: None,
            from_me,
            timestamp_ms: ts,
            body: MessageBody::Text(id.into()),
            reactions: vec![],
        }
    }

    fn ids(t: &Timeline) -> Vec<&str> {
        t.items().iter().map(|i| i.message.id.as_str()).collect()
    }

    fn receipt(ids: &[&str], kind: ReceiptKind) -> GatewayEvent {
        GatewayEvent::Receipt {
            chat: CHAT.into(),
            ids: ids.iter().map(|s| (*s).into()).collect(),
            kind,
        }
    }

    #[test]
    fn overlapping_pages_dedup_and_sort() {
        let mut t = Timeline::new(CHAT);
        t.apply_page(vec![msg("c", 30, false), msg("b", 20, false)], 2);
        assert!(t.has_older());
        assert_eq!(t.oldest_ms(), Some(20));
        t.apply_page(vec![msg("b", 20, false), msg("a", 10, false)], 3);
        assert!(!t.has_older());
        assert_eq!(ids(&t), ["a", "b", "c"]);
    }

    #[test]
    fn live_message_after_page() {
        let mut t = Timeline::new(CHAT);
        t.apply_page(vec![msg("a", 10, false)], 50);
        assert!(t.apply_event(&GatewayEvent::Message(msg("b", 20, false))));
        assert!(!t.apply_event(&GatewayEvent::Message(msg("b", 20, false))));
        assert_eq!(ids(&t), ["a", "b"]);
    }

    #[test]
    fn echo_after_confirm_does_not_duplicate() {
        let mut t = Timeline::new(CHAT);
        t.push_pending(1, "hi".into(), 100);
        assert_eq!(t.items()[0].state, ItemState::Pending { local_id: 1 });
        t.confirm(
            1,
            &Sent {
                message_id: "real".into(),
                timestamp_ms: 105,
            },
        );
        t.apply_event(&GatewayEvent::Message(msg("real", 105, true)));
        assert_eq!(ids(&t), ["real"]);
        assert_eq!(t.items()[0].state, ItemState::Sent);
    }

    #[test]
    fn echo_before_confirm_does_not_duplicate() {
        let mut t = Timeline::new(CHAT);
        t.push_pending(1, "hi".into(), 100);
        t.apply_event(&GatewayEvent::Message(msg("real", 105, true)));
        t.confirm(
            1,
            &Sent {
                message_id: "real".into(),
                timestamp_ms: 105,
            },
        );
        assert_eq!(ids(&t), ["real"]);
    }

    #[test]
    fn receipts_upgrade_never_downgrade() {
        let mut t = Timeline::new(CHAT);
        t.apply_page(vec![msg("a", 10, true)], 50);
        assert!(t.apply_event(&receipt(&["a"], ReceiptKind::Read)));
        assert!(!t.apply_event(&receipt(&["a"], ReceiptKind::Delivered)));
        assert_eq!(t.items()[0].state, ItemState::Read);
    }

    #[test]
    fn update_replaces_reactions() {
        let mut t = Timeline::new(CHAT);
        t.apply_page(vec![msg("a", 10, false)], 50);
        let reactions = vec![Reaction {
            sender: None,
            from_me: true,
            emoji: "x".into(),
        }];
        let ev = GatewayEvent::MessageUpdate {
            chat: CHAT.into(),
            id: "a".into(),
            reactions: reactions.clone(),
            read: false,
        };
        assert!(t.apply_event(&ev));
        assert!(!t.apply_event(&ev));
        assert_eq!(t.items()[0].message.reactions, reactions);
        t.apply_event(&GatewayEvent::MessageUpdate {
            chat: CHAT.into(),
            id: "a".into(),
            reactions: vec![],
            read: true,
        });
        assert!(t.items()[0].message.reactions.is_empty());
        assert_eq!(t.items()[0].state, ItemState::Read);
        assert!(t.unread_ids().is_empty());
    }

    #[test]
    fn other_chat_events_ignored() {
        let mut t = Timeline::new(CHAT);
        let mut other = msg("a", 10, false);
        other.chat = "2@s.whatsapp.net".into();
        assert!(!t.apply_event(&GatewayEvent::Message(other)));
        assert!(t.items().is_empty());
    }

    #[test]
    fn fail_keeps_text_and_unread_ids() {
        let mut t = Timeline::new(CHAT);
        t.push_pending(7, "keep me".into(), 5);
        t.fail(7);
        assert_eq!(t.items()[0].state, ItemState::Failed { local_id: 7 });
        assert_eq!(
            t.items()[0].message.body,
            MessageBody::Text("keep me".into())
        );
        assert_eq!(t.oldest_ms(), None);
        t.apply_page(vec![msg("in", 1, false), msg("out", 2, true)], 50);
        assert_eq!(t.unread_ids(), ["in"]);
    }

    #[test]
    fn user_jid_cases() {
        assert_eq!(
            user_jid("+90 532 123 45 67").as_deref(),
            Ok("905321234567@s.whatsapp.net")
        );
        assert_eq!(
            user_jid("0532 123 45 67"),
            Err(LinkError::MissingCountryCode)
        );
    }

    #[test]
    fn day_breaks_use_local_offset() {
        let day = 86_400_000;
        let items: Vec<Item> = [("a", 1_000), ("b", day - 1_000), ("c", day + 1_000)]
            .iter()
            .map(|(id, ts)| Item {
                message: msg(id, *ts, false),
                state: ItemState::Sent,
            })
            .collect();
        assert_eq!(day_breaks(&items, 0), [(0, 0), (2, day)]);
        // UTC+3: b (23:59:59 UTC) is already on the next local day.
        let h3 = 3 * 3_600;
        assert_eq!(
            day_breaks(&items, h3),
            [(0, -3 * 3_600_000), (1, day - 3 * 3_600_000)]
        );
        assert!(day_breaks(&[], 0).is_empty());
    }

    #[test]
    fn restart_returns_the_text_of_a_failed_send_only() {
        let mut t = Timeline::new(CHAT);
        t.push_pending(1, "hello".into(), 10);
        assert_eq!(t.restart(1), None);
        t.fail(1);
        assert_eq!(t.restart(1).as_deref(), Some("hello"));
        assert_eq!(t.items()[0].state, ItemState::Pending { local_id: 1 });
        assert_eq!(t.restart(2), None);
    }

    fn reaction(emoji: &str, from_me: bool) -> Reaction {
        Reaction {
            sender: None,
            from_me,
            emoji: emoji.into(),
        }
    }

    #[test]
    fn reaction_chips_group_count_and_mark_mine() {
        let chips = reaction_chips(&[
            reaction("👍", false),
            reaction("❤️", false),
            reaction("👍", true),
            reaction("", true),
            reaction("👍", false),
        ]);
        assert_eq!(
            chips,
            [("👍".to_owned(), 3, true), ("❤️".to_owned(), 1, false)]
        );
        assert!(reaction_chips(&[]).is_empty());
    }

    #[test]
    fn own_reaction_replaces_and_removes() {
        let mut t = Timeline::new(CHAT);
        t.apply_page(vec![msg("a", 10, false)], 50);
        assert!(t.set_own_reaction("a", "👍"));
        assert!(!t.set_own_reaction("a", "👍"));
        assert!(t.set_own_reaction("a", "❤️"));
        assert_eq!(t.items()[0].message.reactions.len(), 1);
        assert!(t.set_own_reaction("a", ""));
        assert!(t.items()[0].message.reactions.is_empty());
        assert!(!t.set_own_reaction("missing", "👍"));
    }

    #[test]
    fn typing_throttle_sends_once_per_interval() {
        let mut t = TypingThrottle::new();
        assert!(t.on_edit(1_000));
        assert!(!t.on_edit(1_001));
        assert!(!t.on_edit(5_999));
        assert!(t.on_edit(6_000));
        assert!(!t.on_edit(6_100));
    }

    #[test]
    fn typing_throttle_paused_only_after_typing() {
        let mut t = TypingThrottle::new();
        assert!(!t.on_send());
        assert!(t.on_edit(0));
        assert!(t.on_send());
        assert!(!t.on_send());
        // A new burst after sending types immediately.
        assert!(t.on_edit(10));
    }

    fn pending_image(t: &mut Timeline, local_id: u64) {
        t.push_pending_media(
            local_id,
            MediaKind::Image,
            "image/jpeg".into(),
            Some("cap".into()),
            42,
            100,
        );
    }

    fn sent(id: &str) -> Sent {
        Sent {
            message_id: id.into(),
            timestamp_ms: 200,
        }
    }

    #[test]
    fn pending_media_confirm_keeps_body() {
        let mut t = Timeline::new(CHAT);
        pending_image(&mut t, 1);
        pending_image(&mut t, 1);
        assert_eq!(t.items().len(), 1);
        t.confirm(1, &sent("real"));
        let item = &t.items()[0];
        assert_eq!(item.message.id, "real");
        assert_eq!(item.state, ItemState::Sent);
        assert_eq!(
            item.message.body,
            MessageBody::Media {
                kind: MediaKind::Image,
                caption: Some("cap".into()),
                mime: "image/jpeg".into(),
                size: 42,
            }
        );
    }

    #[test]
    fn media_echo_first_drops_pending() {
        let mut t = Timeline::new(CHAT);
        pending_image(&mut t, 1);
        let mut echo = msg("real", 150, true);
        echo.body = t.items()[0].message.body.clone();
        assert!(t.apply_event(&GatewayEvent::Message(echo)));
        t.confirm(1, &sent("real"));
        assert_eq!(ids(&t), ["real"]);
    }

    #[test]
    fn restart_body_returns_media_and_text_restart_refuses_it() {
        let mut t = Timeline::new(CHAT);
        pending_image(&mut t, 1);
        assert_eq!(t.restart_body(1), None);
        t.fail(1);
        assert_eq!(t.restart(1), None);
        assert_eq!(t.items()[0].state, ItemState::Failed { local_id: 1 });
        let body = t.restart_body(1).unwrap();
        assert!(matches!(body, MessageBody::Media { size: 42, .. }));
        assert_eq!(t.items()[0].state, ItemState::Pending { local_id: 1 });
    }
}
