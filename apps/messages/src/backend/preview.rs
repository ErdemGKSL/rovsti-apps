//! Desktop sample data, so `cargo run -p rovsti_messages` shows a living inbox
//! instead of "needs an Android phone". Nothing here runs on a phone: the
//! backend only builds a [`Preview`] when the provider answers `Unsupported`.
//!
//! The rows are the single source of truth: threads, snippets and unread
//! counts are derived from them, and a preview send or a mark-read edits them,
//! so the inbox and the conversation stay consistent while you click around.

use rovsti_core::{
    Message, MessageDirection, PhoneNumber, ThreadId,
    inbox::InboxThread,
    sms::{
        DeliveryState, SmsBox, SmsMessagesPayload, SmsRow, SmsSim, SmsSimsPayload, SmsThreadRow,
        SmsThreadsPayload,
    },
    wire::{WireContact, WireNumber},
};

#[cfg(test)]
pub mod shots_data;

pub(crate) const MINUTE: i64 = 60_000;
pub(crate) const HOUR: i64 = 60 * MINUTE;
pub(crate) const DAY: i64 = 24 * HOUR;

/// How many extra, older messages Ayse's thread has (so "Load earlier" shows).
const HISTORY: i64 = 110;

pub const AYSE: &str = "+905321234567";
pub const MERT: &str = "+905559876543";
pub const ELIF: &str = "+905440001122";
pub const WHATSAPP_AYSE: &str = "905321234567@s.whatsapp.net";

#[derive(Clone, Debug)]
pub struct Preview {
    rows: Vec<SmsRow>,
    /// `(thread id, addresses)`.
    addresses: Vec<(i64, Vec<String>)>,
    next_row: i64,
    next_thread: i64,
}

impl Preview {
    /// What the desktop backend starts with: the sample data, or (screenshot
    /// tests only) the rich fixture.
    pub fn initial(now_ms: i64) -> Self {
        #[cfg(test)]
        if shots_data::enabled() {
            return Self::shots(now_ms);
        }
        Self::new(now_ms)
    }

    pub fn new(now_ms: i64) -> Self {
        let mut preview = Self {
            rows: Vec::new(),
            addresses: vec![
                (1, vec![AYSE.to_owned()]),
                (2, vec!["GARANTI".to_owned()]),
                (3, vec!["+447700900123".to_owned()]),
                (4, vec![MERT.to_owned(), ELIF.to_owned()]),
                (5, vec![MERT.to_owned()]),
            ],
            next_row: 1,
            next_thread: 6,
        };
        let at = |ago: i64| now_ms - ago;
        // 1: Ayse, SMS and WhatsApp (the merged person).
        for i in (0..HISTORY).rev() {
            let when = at(2 * DAY + i * 10 * MINUTE);
            if i % 2 == 0 {
                preview.push(
                    1,
                    AYSE,
                    &format!("Earlier message {i}"),
                    when,
                    SmsBox::Inbox,
                    true,
                );
            } else {
                preview.push(
                    1,
                    AYSE,
                    &format!("Earlier reply {i}"),
                    when,
                    SmsBox::Sent,
                    true,
                );
            }
        }
        preview.push(1, AYSE, "Lunch tomorrow?", at(DAY), SmsBox::Inbox, true);
        preview.push(
            1,
            AYSE,
            "Sure, 12:30 works",
            at(DAY - 5 * MINUTE),
            SmsBox::Sent,
            true,
        );
        preview.push(
            1,
            AYSE,
            "Are we still on for 7?",
            at(9 * MINUTE),
            SmsBox::Inbox,
            false,
        );
        preview.push(
            1,
            AYSE,
            "I can bring the cake",
            at(5 * MINUTE),
            SmsBox::Inbox,
            false,
        );
        // 2: a one-time code from an alphanumeric sender.
        preview.push(
            2,
            "GARANTI",
            "Your verification code is 482913. Do not share it with anyone.",
            at(12 * MINUTE),
            SmsBox::Inbox,
            false,
        );
        // 3: spam from an unknown number.
        preview.push(
            3,
            "+447700900123",
            "You won a prize! Claim it now: http://bit.ly/claim-now",
            at(2 * HOUR),
            SmsBox::Inbox,
            false,
        );
        // 4: a group thread.
        preview.push(
            4,
            MERT,
            "Who is bringing drinks?",
            at(DAY + 3 * HOUR),
            SmsBox::Inbox,
            true,
        );
        preview.push(4, ELIF, "I will", at(DAY + 2 * HOUR), SmsBox::Inbox, true);
        // 5: Mert alone.
        preview.push(
            5,
            MERT,
            "Tamam, yarın görüşürüz",
            at(3 * DAY),
            SmsBox::Inbox,
            true,
        );
        preview
    }

    fn push(
        &mut self,
        thread_id: i64,
        address: &str,
        body: &str,
        date_ms: i64,
        box_: SmsBox,
        read: bool,
    ) {
        let id = self.next_row;
        self.next_row += 1;
        let outgoing = box_.is_outgoing();
        self.rows.push(SmsRow {
            id,
            thread_id,
            address: address.to_owned(),
            body: body.to_owned(),
            date_ms,
            box_,
            read,
            sub_id: None,
            delivery: if outgoing {
                DeliveryState::Delivered
            } else {
                DeliveryState::None
            },
        });
    }

    pub fn contacts() -> Vec<WireContact> {
        #[cfg(test)]
        if shots_data::enabled() {
            return Self::shots_contacts();
        }
        let contact = |id: i64, name: &str, number: &str| WireContact {
            id,
            display_name: name.to_owned(),
            numbers: vec![WireNumber {
                raw: number.to_owned(),
                label: Some("Mobile".to_owned()),
                default: true,
            }],
            ..WireContact::default()
        };
        vec![
            contact(1, "Ayşe Yılmaz", AYSE),
            contact(2, "Mert Demir", MERT),
            contact(3, "Elif Kaya", ELIF),
            contact(4, "Can Öztürk", "+905361112233"),
        ]
    }

    /// One or two SIMs (`ROVSTI_PREVIEW_SIMS`, default 2 so the chip shows).
    pub fn sims(count: u32) -> SmsSimsPayload {
        let sims: Vec<SmsSim> = (0..count.min(2))
            .map(|slot| SmsSim {
                sub_id: i32::try_from(slot).unwrap_or(0) + 1,
                slot_index: u8::try_from(slot).ok(),
                label: format!("SIM {}", slot + 1),
                color_argb: if slot == 0 {
                    0xFF0A84FF_u32 as i32
                } else {
                    0xFF30D158_u32 as i32
                },
            })
            .collect();
        SmsSimsPayload {
            default_sub_id: sims.first().map(|s| s.sub_id),
            sims,
        }
    }

    pub fn threads(&self) -> SmsThreadsPayload {
        let mut threads: Vec<SmsThreadRow> = self
            .addresses
            .iter()
            .filter_map(|(id, addresses)| {
                let newest = self
                    .rows
                    .iter()
                    .filter(|r| r.thread_id == *id)
                    .max_by_key(|r| r.date_ms)?;
                let unread = self
                    .rows
                    .iter()
                    .filter(|r| r.thread_id == *id && !r.read && !r.box_.is_outgoing())
                    .count();
                Some(SmsThreadRow {
                    thread_id: *id,
                    addresses: addresses.clone(),
                    snippet: newest.body.clone(),
                    date_ms: newest.date_ms,
                    unread: u32::try_from(unread).unwrap_or(u32::MAX),
                })
            })
            .collect();
        threads.sort_by_key(|t| std::cmp::Reverse(t.date_ms));
        SmsThreadsPayload { threads }
    }

    /// One page, newest first, strictly older than `before_ms` when given.
    pub fn messages(
        &self,
        thread_id: i64,
        before_ms: Option<i64>,
        limit: usize,
    ) -> SmsMessagesPayload {
        let mut rows: Vec<&SmsRow> = self
            .rows
            .iter()
            .filter(|r| r.thread_id == thread_id && before_ms.is_none_or(|b| r.date_ms < b))
            .collect();
        rows.sort_by_key(|r| std::cmp::Reverse((r.date_ms, r.id)));
        let has_older = rows.len() > limit;
        SmsMessagesPayload {
            sms: rows.into_iter().take(limit).cloned().collect(),
            mms: Vec::new(),
            has_older,
        }
    }

    /// Removes a thread and its messages.
    pub fn delete_thread(&mut self, thread_id: i64) {
        self.rows.retain(|r| r.thread_id != thread_id);
        self.addresses.retain(|(id, _)| *id != thread_id);
    }

    /// Removes one message. False when it is not there.
    pub fn delete_message(&mut self, id: i64) -> bool {
        let before = self.rows.len();
        self.rows.retain(|r| r.id != id);
        self.rows.len() != before
    }

    pub fn mark_read(&mut self, thread_id: i64) {
        for row in self.rows.iter_mut().filter(|r| r.thread_id == thread_id) {
            row.read = true;
        }
    }

    /// Stores a send as a sent row of the thread whose addresses match, or of a
    /// new thread. Returns `(thread id, row id)`.
    pub fn record_send(
        &mut self,
        to: &[String],
        body: &str,
        sub_id: Option<i32>,
        now_ms: i64,
    ) -> (i64, i64) {
        let thread_id = self.thread_for(to);
        let address = to.first().cloned().unwrap_or_default();
        let row_id = self.next_row;
        self.push(thread_id, &address, body, now_ms, SmsBox::Sent, true);
        if let Some(row) = self.rows.last_mut() {
            row.sub_id = sub_id;
        }
        (thread_id, row_id)
    }

    fn thread_for(&mut self, to: &[String]) -> i64 {
        let wanted: Vec<Option<PhoneNumber>> =
            to.iter().map(|a| PhoneNumber::parse(a).ok()).collect();
        let found = self.addresses.iter().find(|(_, have)| {
            have.len() == to.len()
                && have
                    .iter()
                    .zip(&wanted)
                    .all(|(h, w)| match (PhoneNumber::parse(h).ok(), w) {
                        (Some(h), Some(w)) => h.matches(w),
                        _ => false,
                    })
        });
        if let Some((id, _)) = found {
            return *id;
        }
        let id = self.next_thread;
        self.next_thread += 1;
        self.addresses.push((id, to.to_vec()));
        id
    }
}

/// The preview person's WhatsApp chat, older than their newest SMS so the
/// merged row opens the SMS thread (WhatsApp needs a linked gateway).
pub fn whatsapp_threads(now_ms: i64) -> Vec<InboxThread> {
    #[cfg(test)]
    if shots_data::enabled() {
        return shots_data::wa_threads(now_ms);
    }
    let id = ThreadId::WhatsApp(WHATSAPP_AYSE.to_owned());
    vec![InboxThread {
        last: Message {
            thread: id.clone(),
            direction: MessageDirection::Incoming,
            sender: PhoneNumber::parse(AYSE).ok(),
            body: "Photos from the weekend".to_owned(),
            timestamp_ms: now_ms - 3 * HOUR,
            read: false,
        },
        id,
        unread: 1,
        title: Some("Ayşe".to_owned()),
        is_group: false,
    }]
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_793_887_500_000;

    #[test]
    fn threads_are_newest_first_with_unread_counts() {
        let preview = Preview::new(NOW);
        let threads = preview.threads().threads;
        assert_eq!(threads.len(), 5);
        assert!(threads.windows(2).all(|w| w[0].date_ms >= w[1].date_ms));
        let ayse = threads.iter().find(|t| t.thread_id == 1).expect("thread 1");
        assert_eq!(ayse.unread, 2);
        assert_eq!(ayse.snippet, "I can bring the cake");
        assert_eq!(threads[0].thread_id, 1);
    }

    #[test]
    fn deleting_a_thread_removes_it_and_its_messages() {
        let mut preview = Preview::new(NOW);
        preview.delete_thread(1);
        assert!(preview.threads().threads.iter().all(|t| t.thread_id != 1));
        assert!(preview.messages(1, None, 50).sms.is_empty());
        assert_eq!(preview.threads().threads.len(), 4);
    }

    #[test]
    fn pages_walk_back_through_a_thread() {
        let preview = Preview::new(NOW);
        let first = preview.messages(1, None, 50);
        assert_eq!(first.sms.len(), 50);
        assert!(first.has_older);
        let oldest = first.sms.last().expect("row").date_ms;
        let second = preview.messages(1, Some(oldest), 50);
        assert!(second.sms.iter().all(|r| r.date_ms < oldest));
        let last = preview.messages(1, Some(0), 50);
        assert!(last.sms.is_empty());
        assert!(!last.has_older);
        assert!(!preview.messages(2, None, 50).has_older);
    }

    #[test]
    fn mark_read_clears_unread() {
        let mut preview = Preview::new(NOW);
        preview.mark_read(1);
        let threads = preview.threads().threads;
        assert_eq!(
            threads
                .iter()
                .find(|t| t.thread_id == 1)
                .expect("thread")
                .unread,
            0
        );
        assert_eq!(
            threads
                .iter()
                .find(|t| t.thread_id == 2)
                .expect("thread")
                .unread,
            1
        );
    }

    #[test]
    fn a_send_joins_the_matching_thread_or_starts_one() {
        let mut preview = Preview::new(NOW);
        // A national spelling of Ayse's number lands in thread 1.
        let (thread, _) = preview.record_send(&["0532 123 45 67".to_owned()], "hi", Some(2), NOW);
        assert_eq!(thread, 1);
        let top = preview.messages(1, None, 1).sms;
        assert_eq!(top[0].body, "hi");
        assert_eq!(top[0].sub_id, Some(2));
        let (new_thread, _) = preview.record_send(&["+905361112233".to_owned()], "yo", None, NOW);
        assert_eq!(new_thread, 6);
        assert_eq!(preview.threads().threads.len(), 6);
        // The group thread is only matched by the same recipients.
        let (group, _) = preview.record_send(&[MERT.to_owned(), ELIF.to_owned()], "all", None, NOW);
        assert_eq!(group, 4);
    }

    #[test]
    fn sims_follow_the_count() {
        assert_eq!(Preview::sims(1).sims.len(), 1);
        let two = Preview::sims(2);
        assert_eq!(two.sims.len(), 2);
        assert_eq!(two.default_sub_id, Some(1));
        assert!(Preview::sims(0).sims.is_empty());
    }

    #[test]
    fn the_whatsapp_chat_is_older_than_the_sms_thread() {
        let preview = Preview::new(NOW);
        let sms = preview.threads().threads;
        let wa = whatsapp_threads(NOW);
        assert!(wa[0].last.timestamp_ms < sms[0].date_ms);
    }
}
