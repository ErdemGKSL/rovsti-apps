//! One SMS/MMS thread as an ascending list: provider pages, optimistic sends
//! and `sms_status` events merged. Mirrors `rovsti_wa_proto::timeline::Timeline`.
//! No I/O, no clock.

use crate::sms::{
    DeliveryState, MmsPart, MmsRow, SmsBox, SmsMessagesPayload, SmsRow, SmsSend, SmsStatus,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SmsKey {
    /// `content://sms/<id>`.
    Sms(i64),
    /// `content://mms/<id>`.
    Mms(i64),
    /// A send this app started, before (or without) a provider row.
    Local(u64),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SmsItem {
    pub key: SmsKey,
    pub outgoing: bool,
    pub body: String,
    pub parts: Vec<MmsPart>,
    pub at_ms: i64,
    pub state: DeliveryState,
    pub sub_id: Option<i32>,
}

fn rank(state: DeliveryState) -> u8 {
    match state {
        DeliveryState::None => 0,
        DeliveryState::Pending => 1,
        DeliveryState::Sent => 2,
        DeliveryState::Delivered | DeliveryState::Failed => 3,
    }
}

/// Whether `from -> to` is a move forward. `Delivered` is final, `Failed` can
/// only be followed by `Delivered` (a late report); going back to `Pending`
/// is [`SmsTimeline::retry`]'s job.
fn advances(from: DeliveryState, to: DeliveryState) -> bool {
    match (from, to) {
        (DeliveryState::Delivered, _) => false,
        (DeliveryState::Failed, to) => to == DeliveryState::Delivered,
        (_, DeliveryState::Failed) => true,
        (from, to) => rank(to) > rank(from),
    }
}

fn row_state(row: &SmsRow) -> DeliveryState {
    if row.box_ == SmsBox::Failed {
        return DeliveryState::Failed;
    }
    if row.delivery != DeliveryState::None {
        return row.delivery;
    }
    match row.box_ {
        SmsBox::Sent => DeliveryState::Sent,
        SmsBox::Outbox | SmsBox::Queued => DeliveryState::Pending,
        _ => DeliveryState::None,
    }
}

/// `content://sms/42` or `content://mms/42` -> its numeric id.
fn uri_id(uri: &str) -> Option<i64> {
    uri.rsplit('/').next()?.parse().ok()
}

#[derive(Clone, Debug)]
pub struct SmsTimeline {
    thread_id: i64,
    recipients: Vec<String>,
    items: Vec<SmsItem>,
    /// Provider row ids already owned by a `Local` item.
    linked: Vec<(u64, SmsKey)>,
    has_older: bool,
}

impl SmsTimeline {
    pub fn new(thread_id: i64) -> Self {
        Self {
            thread_id,
            recipients: Vec::new(),
            items: Vec::new(),
            linked: Vec::new(),
            has_older: false,
        }
    }

    /// Who a retry sends to (the thread's addresses).
    pub fn set_recipients(&mut self, recipients: Vec<String>) {
        self.recipients = recipients;
    }

    pub fn thread_id(&self) -> i64 {
        self.thread_id
    }

    pub fn has_older(&self) -> bool {
        self.has_older
    }

    pub fn items(&self) -> &[SmsItem] {
        &self.items
    }

    /// Timestamp of the oldest provider-backed item, for paging.
    pub fn oldest_ms(&self) -> Option<i64> {
        self.items
            .iter()
            .find(|i| match i.key {
                SmsKey::Local(id) => self.linked.iter().any(|(l, _)| *l == id),
                _ => true,
            })
            .map(|i| i.at_ms)
    }

    /// A provider page of this thread: dedupes by key, keeps ascending time
    /// order and never lowers a delivery state.
    pub fn apply_page(&mut self, page: SmsMessagesPayload) {
        self.has_older = page.has_older;
        let thread_id = self.thread_id;
        for row in page.sms.iter().filter(|r| r.thread_id == thread_id) {
            let item = SmsItem {
                key: SmsKey::Sms(row.id),
                outgoing: row.box_.is_outgoing(),
                body: row.body.clone(),
                parts: Vec::new(),
                at_ms: row.date_ms,
                state: row_state(row),
                sub_id: row.sub_id,
            };
            self.upsert(item);
        }
        for row in page.mms.iter().filter(|r| r.thread_id == thread_id) {
            self.upsert(mms_item(row));
        }
    }

    /// Adds an optimistic outgoing message.
    pub fn push_pending(&mut self, local_id: u64, body: String, sub_id: Option<i32>, now_ms: i64) {
        if self.position(SmsKey::Local(local_id)).is_some() {
            return;
        }
        self.insert_sorted(SmsItem {
            key: SmsKey::Local(local_id),
            outgoing: true,
            body,
            parts: Vec::new(),
            at_ms: now_ms,
            state: DeliveryState::Pending,
            sub_id,
        });
    }

    /// Applies a send status. The state only moves forward; a `uri` ties the
    /// local item to its provider row so a later page does not duplicate it.
    /// Returns `true` when something changed.
    pub fn apply_status(&mut self, status: &SmsStatus) -> bool {
        if self.position(SmsKey::Local(status.local_id)).is_none() {
            return false;
        }
        let mut changed = false;
        if let Some(id) = status.uri.as_deref().and_then(uri_id) {
            let key = if status.uri.as_deref().is_some_and(|u| u.contains("/mms/")) {
                SmsKey::Mms(id)
            } else {
                SmsKey::Sms(id)
            };
            if !self.linked.contains(&(status.local_id, key)) {
                self.linked.push((status.local_id, key));
                changed = true;
            }
            // The page arrived first: the local item absorbs the provider row.
            if let Some(dup) = self.position(key) {
                let row = self.items.remove(dup);
                let local = self.position(SmsKey::Local(status.local_id));
                if let Some(item) = local.and_then(|p| self.items.get_mut(p)) {
                    item.at_ms = row.at_ms;
                    if advances(item.state, row.state) {
                        item.state = row.state;
                    }
                }
                self.resort(SmsKey::Local(status.local_id));
                changed = true;
            }
        }
        if let Some(pos) = self.position(SmsKey::Local(status.local_id)) {
            let item = &mut self.items[pos];
            if advances(item.state, status.state) {
                item.state = status.state;
                changed = true;
            }
        }
        changed
    }

    /// Takes a failed local send back to pending and returns the request to
    /// send again. `None` unless the item is a local `Failed` one.
    pub fn retry(&mut self, local_id: u64) -> Option<SmsSend> {
        let pos = self.position(SmsKey::Local(local_id))?;
        let item = &mut self.items[pos];
        if item.state != DeliveryState::Failed {
            return None;
        }
        item.state = DeliveryState::Pending;
        Some(SmsSend {
            local_id,
            to: self.recipients.clone(),
            body: item.body.clone(),
            sub_id: item.sub_id,
        })
    }

    fn position(&self, key: SmsKey) -> Option<usize> {
        self.items.iter().position(|i| i.key == key)
    }

    fn insert_sorted(&mut self, item: SmsItem) {
        let at = self.items.partition_point(|i| i.at_ms <= item.at_ms);
        self.items.insert(at, item);
    }

    fn resort(&mut self, key: SmsKey) {
        if let Some(pos) = self.position(key) {
            let item = self.items.remove(pos);
            self.insert_sorted(item);
        }
    }

    /// Inserts a provider item, or refreshes the item that already stands for
    /// it (possibly a `Local` one), never lowering its state.
    fn upsert(&mut self, item: SmsItem) {
        let local = self
            .linked
            .iter()
            .find(|(_, k)| *k == item.key)
            .map(|(id, _)| SmsKey::Local(*id));
        let key = local.unwrap_or(item.key);
        let Some(pos) = self.position(key) else {
            self.insert_sorted(item);
            return;
        };
        let existing = &mut self.items[pos];
        let moved = existing.at_ms != item.at_ms;
        existing.at_ms = item.at_ms;
        existing.body = item.body;
        existing.parts = item.parts;
        existing.outgoing = item.outgoing;
        existing.sub_id = item.sub_id;
        if advances(existing.state, item.state) {
            existing.state = item.state;
        }
        if moved {
            self.resort(key);
        }
    }
}

fn mms_item(row: &MmsRow) -> SmsItem {
    SmsItem {
        key: SmsKey::Mms(row.id),
        outgoing: row.outgoing,
        body: row.text(),
        parts: row.parts.clone(),
        at_ms: row.date_ms,
        state: if row.outgoing {
            DeliveryState::Sent
        } else {
            DeliveryState::None
        },
        sub_id: row.sub_id,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: i64, date: i64, box_: SmsBox) -> SmsRow {
        SmsRow {
            id,
            thread_id: 1,
            body: format!("m{id}"),
            date_ms: date,
            box_,
            ..Default::default()
        }
    }

    fn status(local_id: u64, state: DeliveryState) -> SmsStatus {
        SmsStatus {
            local_id,
            state,
            ..Default::default()
        }
    }

    #[test]
    fn page_dedupes_sorts_and_filters_thread() {
        let mut t = SmsTimeline::new(1);
        let mut other = row(9, 1, SmsBox::Inbox);
        other.thread_id = 2;
        let page = SmsMessagesPayload {
            sms: vec![row(2, 20, SmsBox::Inbox), row(1, 10, SmsBox::Sent), other],
            mms: vec![MmsRow {
                id: 5,
                thread_id: 1,
                date_ms: 15,
                parts: vec![MmsPart {
                    content_type: "text/plain".into(),
                    text: Some("pic".into()),
                    path: None,
                }],
                ..Default::default()
            }],
            has_older: true,
        };
        t.apply_page(page.clone());
        t.apply_page(page);
        let keys: Vec<_> = t.items().iter().map(|i| i.key).collect();
        assert_eq!(keys, vec![SmsKey::Sms(1), SmsKey::Mms(5), SmsKey::Sms(2)]);
        assert_eq!(t.items()[1].body, "pic");
        assert_eq!(t.oldest_ms(), Some(10));
        assert!(t.has_older());
    }

    #[test]
    fn status_never_moves_backwards() {
        let mut t = SmsTimeline::new(1);
        t.push_pending(1, "hi".into(), None, 100);
        assert!(t.apply_status(&status(1, DeliveryState::Sent)));
        assert!(t.apply_status(&status(1, DeliveryState::Delivered)));
        assert!(!t.apply_status(&status(1, DeliveryState::Sent)));
        assert!(!t.apply_status(&status(1, DeliveryState::Pending)));
        assert!(!t.apply_status(&status(1, DeliveryState::Failed)));
        assert_eq!(t.items()[0].state, DeliveryState::Delivered);
        assert!(!t.apply_status(&status(77, DeliveryState::Sent)));
    }

    #[test]
    fn pages_do_not_lower_state() {
        let mut t = SmsTimeline::new(1);
        t.apply_page(SmsMessagesPayload {
            sms: vec![SmsRow {
                delivery: DeliveryState::Delivered,
                ..row(1, 10, SmsBox::Sent)
            }],
            ..Default::default()
        });
        t.apply_page(SmsMessagesPayload {
            sms: vec![row(1, 10, SmsBox::Sent)],
            ..Default::default()
        });
        assert_eq!(t.items()[0].state, DeliveryState::Delivered);
    }

    #[test]
    fn retry_only_for_failed_local() {
        let mut t = SmsTimeline::new(1);
        t.set_recipients(vec!["+905321234567".into()]);
        t.push_pending(1, "hi".into(), Some(3), 100);
        assert!(t.retry(1).is_none());
        t.apply_status(&status(1, DeliveryState::Sent));
        assert!(t.retry(1).is_none());
        t.push_pending(2, "yo".into(), Some(3), 200);
        t.apply_status(&status(2, DeliveryState::Failed));
        let send = t.retry(2).expect("failed send retries");
        assert_eq!(send.local_id, 2);
        assert_eq!(send.to, vec!["+905321234567".to_owned()]);
        assert_eq!(send.body, "yo");
        assert_eq!(send.sub_id, Some(3));
        assert_eq!(t.items()[1].state, DeliveryState::Pending);
        assert!(t.retry(2).is_none());
        assert!(t.retry(99).is_none());
    }

    #[test]
    fn status_uri_links_local_item_to_provider_row() {
        let mut t = SmsTimeline::new(1);
        t.push_pending(1, "hi".into(), None, 100);
        t.apply_status(&SmsStatus {
            local_id: 1,
            uri: Some("content://sms/42".into()),
            state: DeliveryState::Sent,
            error_code: None,
        });
        t.apply_page(SmsMessagesPayload {
            sms: vec![row(42, 105, SmsBox::Sent)],
            ..Default::default()
        });
        assert_eq!(t.items().len(), 1);
        assert_eq!(t.items()[0].key, SmsKey::Local(1));
        assert_eq!(t.items()[0].at_ms, 105);
        assert_eq!(t.oldest_ms(), Some(105));
    }

    #[test]
    fn page_before_status_is_absorbed() {
        let mut t = SmsTimeline::new(1);
        t.push_pending(1, "hi".into(), None, 100);
        t.apply_page(SmsMessagesPayload {
            sms: vec![row(42, 105, SmsBox::Outbox)],
            ..Default::default()
        });
        assert_eq!(t.items().len(), 2);
        t.apply_status(&SmsStatus {
            local_id: 1,
            uri: Some("content://sms/42".into()),
            state: DeliveryState::Sent,
            error_code: None,
        });
        assert_eq!(t.items().len(), 1);
        assert_eq!(t.items()[0].state, DeliveryState::Sent);
    }

    #[test]
    fn failed_box_reads_as_failed() {
        let mut t = SmsTimeline::new(1);
        t.apply_page(SmsMessagesPayload {
            sms: vec![row(1, 1, SmsBox::Failed)],
            ..Default::default()
        });
        assert_eq!(t.items()[0].state, DeliveryState::Failed);
    }
}
