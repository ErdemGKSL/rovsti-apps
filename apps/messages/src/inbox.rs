//! The unified inbox: how SMS threads and WhatsApp chats become rows.
//!
//! Merging is `rovsti_core::inbox::merge`; this module only maps its rows to
//! what the screen shows (title, badges, unread count, OTP chip, spam
//! filtering) and draws one row. The mapping is pure and tested here, the
//! drawing is a thin GPUI shell.

use gpui::{
    AnyElement, App, ClickEvent, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_core::{
    Channel, PhoneNumber, ThreadId,
    blocklist::BlockSet,
    inbox::{InboxRow, InboxThread, PersonKey, merge},
    otp,
    recents::LocaleTime,
    search::ContactIndex,
    sms::SmsThreadRow,
    spam::{self, SpamRules},
};
use rovsti_ui::{
    components::avatar,
    theme,
    tokens::{Theme, typography},
};
use rovsti_wa_proto::{ChatSummary, WaMessage, to_rovsti};

use crate::conversation::civil_from_days;

/// Which channel a badge names.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Badge {
    Sms,
    WhatsApp,
}

impl Badge {
    pub fn label(self) -> &'static str {
        match self {
            Self::Sms => "SMS",
            Self::WhatsApp => "WhatsApp",
        }
    }

    pub fn of(channel: Channel) -> Self {
        match channel {
            Channel::Sms => Self::Sms,
            Channel::WhatsApp => Self::WhatsApp,
        }
    }
}

/// One inbox row as shown.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RowView {
    pub title: String,
    pub snippet: String,
    /// One badge per channel of the person, newest thread's first.
    pub badges: Vec<Badge>,
    pub unread: u32,
    /// The code to copy, when the newest message is an unread one-time code.
    pub otp: Option<String>,
    pub time: String,
    /// The person's threads, newest first; a tap opens the first.
    pub threads: Vec<ThreadId>,
    pub initials: String,
    /// Avatar colour follows the newest thread's channel.
    pub channel: Channel,
}

impl RowView {
    /// The thread a tap opens.
    pub fn newest(&self) -> Option<&ThreadId> {
        self.threads.first()
    }
}

/// The inbox, split into the normal rows and the Spam filter's rows.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct InboxView {
    pub rows: Vec<RowView>,
    pub spam: Vec<RowView>,
}

impl InboxView {
    /// Unread messages sitting in the Spam filter.
    pub fn spam_unread(&self) -> u32 {
        self.spam.iter().map(|r| r.unread).sum()
    }
}

/// What a build needs besides the threads.
pub struct Inputs<'a> {
    pub contacts: &'a ContactIndex,
    pub rules: &'a SpamRules,
    pub blocked: &'a BlockSet,
    pub locale: LocaleTime,
    pub now_ms: i64,
}

pub fn build(sms: &[SmsThreadRow], wa: &[InboxThread], inputs: &Inputs<'_>) -> InboxView {
    let mut view = InboxView::default();
    for row in merge(sms, wa, inputs.contacts) {
        let spam = is_spam(&row, sms, inputs);
        let shown = row_view(&row, sms, inputs);
        if spam {
            view.spam.push(shown);
        } else {
            view.rows.push(shown);
        }
    }
    view
}

fn sms_row<'a>(sms: &'a [SmsThreadRow], id: &ThreadId) -> Option<&'a SmsThreadRow> {
    match id {
        ThreadId::Sms(thread_id) => sms.iter().find(|t| t.thread_id == *thread_id),
        ThreadId::WhatsApp(_) => None,
    }
}

/// Only a one-to-one conversation that lives purely in SMS can be spam:
/// anyone also on WhatsApp, a contact or a group never is. The verdict is on
/// the newest message, like the notification path (`rovsti_core::spam`).
fn is_spam(row: &InboxRow, sms: &[SmsThreadRow], inputs: &Inputs<'_>) -> bool {
    if row.channels != [Channel::Sms] {
        return false;
    }
    let newest = &row.threads[0];
    if newest.is_group {
        return false;
    }
    let Some(source) = sms_row(sms, &newest.id) else {
        return false;
    };
    let sender = source.addresses.first().map_or("", String::as_str);
    let known = matches!(row.key, PersonKey::Contact(_));
    spam::classify(
        inputs.rules,
        sender,
        &newest.last.body,
        known,
        inputs.blocked,
    )
    .is_spam()
}

/// The title of a row. Rows of people take the contact or number from the
/// merge; a sender the number parser rejects (`GARANTI`) shows its address
/// instead of the merge's `#id`.
pub fn row_title(row: &InboxRow, sms: &[SmsThreadRow]) -> String {
    if let PersonKey::Group(ThreadId::Sms(_)) = row.key
        && row.threads.first().is_some_and(|t| t.title.is_none())
        && let Some(source) = sms_row(sms, &row.threads[0].id)
    {
        let addresses: Vec<&str> = source
            .addresses
            .iter()
            .map(|a| a.trim())
            .filter(|a| !a.is_empty())
            .collect();
        if !addresses.is_empty() {
            return addresses.join(", ");
        }
    }
    row.title.clone()
}

/// The gateway's chat list as inbox threads. A chat the app already holds
/// keeps its newer message (a live one can beat the fetch); chats with no
/// message yet are left out.
pub fn chat_threads(chats: &[ChatSummary], have: &[InboxThread]) -> Vec<InboxThread> {
    chats
        .iter()
        .filter_map(|chat| {
            let mut last = to_rovsti(chat.last.as_ref()?);
            let id = ThreadId::WhatsApp(chat.jid.clone());
            if let Some(held) = have.iter().find(|t| t.id == id)
                && held.last.timestamp_ms > last.timestamp_ms
            {
                last = held.last.clone();
            }
            Some(InboxThread {
                id,
                last,
                unread: chat.unread,
                title: chat.name.clone().filter(|n| !n.trim().is_empty()),
                is_group: chat.is_group || chat.jid.ends_with("@g.us"),
            })
        })
        .collect()
}

/// A live gateway message folded into the chat threads: the chat moves to the
/// newest message, and an incoming one counts as unread unless the chat is
/// open. A chat not yet in the list starts a thread.
pub fn fold_message(threads: &mut Vec<InboxThread>, message: &WaMessage, viewing: bool) {
    let last = to_rovsti(message);
    let id = ThreadId::WhatsApp(message.chat.clone());
    let bump = u32::from(!message.from_me && !viewing);
    match threads.iter_mut().find(|t| t.id == id) {
        Some(thread) => {
            if thread.last.timestamp_ms <= last.timestamp_ms {
                thread.last = last;
            }
            thread.unread = if viewing {
                0
            } else {
                thread.unread.saturating_add(bump)
            };
        }
        None => threads.push(InboxThread {
            id,
            last,
            unread: bump,
            title: None,
            is_group: message.chat.ends_with("@g.us"),
        }),
    }
}

pub fn badges(row: &InboxRow) -> Vec<Badge> {
    row.channels.iter().copied().map(Badge::of).collect()
}

fn row_view(row: &InboxRow, sms: &[SmsThreadRow], inputs: &Inputs<'_>) -> RowView {
    let title = row_title(row, sms);
    let newest = &row.threads[0];
    // A code is worth a chip while it is new: an old, read one is just text.
    let otp = (newest.id.channel() == Channel::Sms && row.unread > 0)
        .then(|| otp::detect(&newest.last.body))
        .flatten()
        .map(|o| o.code);
    RowView {
        initials: initials(&title),
        title,
        snippet: row.last.body.clone(),
        badges: badges(row),
        unread: row.unread,
        otp,
        time: row_time(row.last.timestamp_ms, inputs.now_ms, inputs.locale),
        threads: row.threads.iter().map(|t| t.id.clone()).collect(),
        channel: newest.id.channel(),
    }
}

/// Up to two initials; `#` for a number or an empty name.
pub fn initials(title: &str) -> String {
    let letters: String = title
        .split_whitespace()
        .filter_map(|word| word.chars().find(|c| c.is_alphabetic()))
        .take(2)
        .flat_map(char::to_uppercase)
        .collect();
    if letters.is_empty() {
        "#".to_owned()
    } else {
        letters
    }
}

/// The other thread of the same person, for the header's channel switch:
/// `current`'s row, its first other thread. Looks through the inbox and the
/// Spam filter, so the switch also works from a spam conversation.
pub fn switch_target<'a>(view: &'a InboxView, current: &ThreadId) -> Option<(&'a ThreadId, Badge)> {
    let row = view
        .rows
        .iter()
        .chain(&view.spam)
        .find(|r| r.threads.contains(current))?;
    let other = row.threads.iter().find(|t| *t != current)?;
    Some((other, Badge::of(other.channel())))
}

/// The existing one-to-one SMS thread with `number` (any spelling of it), for
/// the new-message entry: a conversation with them continues there.
pub fn thread_for_number<'a>(sms: &'a [SmsThreadRow], number: &str) -> Option<&'a SmsThreadRow> {
    let wanted = PhoneNumber::parse(number).ok()?;
    sms.iter().find(|thread| match thread.addresses.as_slice() {
        [one] => PhoneNumber::parse(one).is_ok_and(|have| have.matches(&wanted)),
        _ => false,
    })
}

/// The row's time: the clock today, "Yesterday", else the date.
pub fn row_time(ms: i64, now_ms: i64, locale: LocaleTime) -> String {
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    if ms <= 0 {
        return String::new();
    }
    let offset = i64::from(locale.tz_offset_s);
    let day_of = |ms: i64| (ms.div_euclid(1000) + offset).div_euclid(86_400);
    match day_of(now_ms) - day_of(ms) {
        ..=0 => clock(ms, locale),
        1 => "Yesterday".to_owned(),
        _ => {
            let (year, month, day) = civil_from_days(day_of(ms));
            let (now_year, ..) = civil_from_days(day_of(now_ms));
            let name = MONTHS[month - 1];
            if year == now_year {
                format!("{day} {name}")
            } else {
                format!("{day} {name} {year}")
            }
        }
    }
}

fn clock(ms: i64, locale: LocaleTime) -> String {
    let secs = (ms.div_euclid(1000) + i64::from(locale.tz_offset_s)).rem_euclid(86_400);
    let (hour, minute) = (secs / 3600, secs % 3600 / 60);
    if locale.format.hour12 {
        let shown = match hour % 12 {
            0 => 12,
            h => h,
        };
        format!(
            "{shown}:{minute:02} {}",
            if hour < 12 { "AM" } else { "PM" }
        )
    } else {
        format!("{hour:02}:{minute:02}")
    }
}

// ---- drawing --------------------------------------------------------------

type Handler = Box<dyn Fn(&ClickEvent, &mut Window, &mut App)>;

/// One inbox row: avatar, title and time, badges and snippet, unread dot, and
/// under it the OTP chip when there is one. The chip is a sibling of the
/// tappable row, so copying a code never opens the conversation.
pub fn row_element(
    theme: Theme,
    index: usize,
    view: &RowView,
    copied: bool,
    open: Handler,
    copy_code: Handler,
) -> AnyElement {
    let colors = theme::colors(theme);
    let tint = match view.channel {
        Channel::Sms => colors.accent,
        Channel::WhatsApp => colors.green,
    };
    let unread = view.unread > 0;
    let weight = if unread {
        typography::HEADLINE.weight
    } else {
        typography::BODY.weight
    };

    let badges = view.badges.iter().map(|badge| {
        let color = match badge {
            Badge::Sms => colors.accent,
            Badge::WhatsApp => colors.green,
        };
        div()
            .flex_none()
            .px(px(6.0))
            .rounded_full()
            .bg(color.opacity(0.16))
            .text_size(typography::CAPTION.size)
            .text_color(color)
            .child(badge.label())
    });

    let main = div()
        .id(("inbox-row", index))
        .flex()
        .items_center()
        .gap(px(12.0))
        .child(avatar(theme, view.initials.clone(), 44.0, tint))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(2.0))
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(8.0))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child(SharedString::from(view.title.clone())),
                        )
                        .child(
                            div()
                                .flex_none()
                                .text_size(typography::CAPTION.size)
                                .text_color(if unread {
                                    colors.accent
                                } else {
                                    colors.tertiary_label
                                })
                                .child(SharedString::from(view.time.clone())),
                        ),
                )
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .children(badges)
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .text_size(typography::SUBHEADLINE.size)
                                .font_weight(weight)
                                .text_color(colors.secondary_label)
                                .child(SharedString::from(view.snippet.clone())),
                        )
                        .children(unread.then(|| {
                            div()
                                .flex_none()
                                .min_w(px(20.0))
                                .h(px(20.0))
                                .px(px(6.0))
                                .rounded_full()
                                .bg(colors.accent)
                                .flex()
                                .items_center()
                                .justify_center()
                                .text_size(typography::CAPTION.size)
                                .text_color(colors.background)
                                .child(view.unread.to_string())
                        })),
                ),
        )
        .on_click(open);

    let chip = view.otp.as_ref().map(|code| {
        div()
            .id(("inbox-otp", index))
            .ml(px(56.0))
            .mt(px(6.0))
            .self_start()
            .px(px(12.0))
            .py(px(4.0))
            .rounded_full()
            .bg(colors.accent.opacity(0.16))
            .text_size(typography::SUBHEADLINE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(colors.accent)
            .child(if copied {
                format!("Copied {code}")
            } else {
                format!("Copy {code}")
            })
            .on_click(copy_code)
    });

    div()
        .flex()
        .flex_col()
        .px(px(16.0))
        .py(px(8.0))
        .border_b(px(0.5))
        .border_color(colors.separator)
        .child(main)
        .children(chip)
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use rovsti_core::{
        Message, MessageDirection, PhoneNumber,
        wire::{BlockedNumber, WireContact, WireNumber},
    };

    use super::*;

    const NOW: i64 = 1_793_887_500_000; // 2026-11-05 14:05 UTC
    const MIN: i64 = 60_000;

    fn contact(id: i64, name: &str, number: &str) -> WireContact {
        WireContact {
            id,
            display_name: name.into(),
            numbers: vec![WireNumber {
                raw: number.into(),
                label: None,
                default: false,
            }],
            ..WireContact::default()
        }
    }

    fn sms(id: i64, addrs: &[&str], snippet: &str, ago: i64, unread: u32) -> SmsThreadRow {
        SmsThreadRow {
            thread_id: id,
            addresses: addrs.iter().map(|a| (*a).to_owned()).collect(),
            snippet: snippet.into(),
            date_ms: NOW - ago,
            unread,
        }
    }

    fn wa(jid: &str, name: &str, ago: i64, unread: u32) -> InboxThread {
        let id = ThreadId::WhatsApp(jid.into());
        InboxThread {
            last: Message {
                thread: id.clone(),
                direction: MessageDirection::Incoming,
                sender: PhoneNumber::parse("+905321234567").ok(),
                body: "wa text".into(),
                timestamp_ms: NOW - ago,
                read: unread == 0,
            },
            id,
            unread,
            title: Some(name.into()),
            is_group: false,
        }
    }

    fn build_with(
        sms: &[SmsThreadRow],
        wa: &[InboxThread],
        contacts: Vec<WireContact>,
        rules: &SpamRules,
        blocked: &BlockSet,
    ) -> InboxView {
        let index = ContactIndex::new(contacts);
        build(
            sms,
            wa,
            &Inputs {
                contacts: &index,
                rules,
                blocked,
                locale: LocaleTime::default(),
                now_ms: NOW,
            },
        )
    }

    fn rules() -> SpamRules {
        SpamRules {
            rules: vec![spam::SpamRule::UnknownWithLink],
            ..SpamRules::default()
        }
    }

    #[test]
    fn a_merged_person_gets_both_badges_and_summed_unread() {
        let view = build_with(
            &[sms(1, &["+905321234567"], "sms text", 5 * MIN, 2)],
            &[wa("905321234567@s.whatsapp.net", "Ayse WA", 60 * MIN, 1)],
            vec![contact(7, "Ayşe Yılmaz", "+905321234567")],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(view.rows.len(), 1);
        let row = &view.rows[0];
        assert_eq!(row.title, "Ayşe Yılmaz");
        assert_eq!(row.badges, vec![Badge::Sms, Badge::WhatsApp]);
        assert_eq!(row.unread, 3);
        assert_eq!(row.snippet, "sms text");
        assert_eq!(row.newest(), Some(&ThreadId::Sms(1)));
        assert_eq!(row.initials, "AY");
        assert_eq!(row.channel, Channel::Sms);
        assert_eq!(row.time, "14:00");
    }

    #[test]
    fn single_channel_rows_get_one_badge() {
        let view = build_with(
            &[sms(1, &["+905559876543"], "hi", MIN, 0)],
            &[wa("905361112233@s.whatsapp.net", "Can", 2 * MIN, 0)],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(view.rows.len(), 2);
        assert_eq!(view.rows[0].badges, vec![Badge::Sms]);
        assert_eq!(view.rows[0].title, "+905559876543");
        assert_eq!(view.rows[1].badges, vec![Badge::WhatsApp]);
        assert_eq!(view.rows[1].title, "Can");
        assert_eq!(view.rows[1].channel, Channel::WhatsApp);
    }

    #[test]
    fn an_alphanumeric_sender_shows_its_name_not_the_thread_id() {
        let view = build_with(
            &[sms(9, &["GARANTI"], "hello", MIN, 0)],
            &[],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(view.rows[0].title, "GARANTI");
        assert_eq!(view.rows[0].initials, "G");
        assert_eq!(initials("+905321234567"), "#");
        assert_eq!(initials("mert demir yilmaz"), "MD");
    }

    #[test]
    fn a_group_thread_lists_its_members() {
        let view = build_with(
            &[sms(4, &["+905559876543", "+905440001122"], "who?", MIN, 0)],
            &[],
            vec![
                contact(1, "Mert", "+905559876543"),
                contact(2, "Elif", "+905440001122"),
            ],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(view.rows[0].title, "Mert, Elif");
    }

    #[test]
    fn an_unread_code_gets_a_chip_and_a_read_one_does_not() {
        let unread = build_with(
            &[sms(
                1,
                &["GARANTI"],
                "Your verification code is 482913.",
                MIN,
                1,
            )],
            &[],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(unread.rows[0].otp.as_deref(), Some("482913"));
        let read = build_with(
            &[sms(
                1,
                &["GARANTI"],
                "Your verification code is 482913.",
                MIN,
                0,
            )],
            &[],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(read.rows[0].otp, None);
        let plain = build_with(
            &[sms(1, &["+905559876543"], "see you at 1500", MIN, 1)],
            &[],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(plain.rows[0].otp, None);
    }

    #[test]
    fn spam_rows_are_collected_apart() {
        let view = build_with(
            &[
                sms(1, &["+447700900123"], "Win now http://bit.ly/x", MIN, 1),
                sms(2, &["+905559876543"], "hello", 2 * MIN, 0),
                sms(3, &["+905321234567"], "Win http://bit.ly/y", 3 * MIN, 1),
            ],
            &[],
            // The third sender is a contact: never spam.
            vec![contact(7, "Ayse", "+905321234567")],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(view.spam.len(), 1);
        assert_eq!(view.spam[0].title, "+447700900123");
        assert_eq!(view.spam_unread(), 1);
        assert_eq!(view.rows.len(), 2);
    }

    #[test]
    fn blocked_senders_and_codes() {
        let blocked = BlockSet::from_rows(&[BlockedNumber {
            id: 1,
            number_raw: "+905551112233".into(),
            normalized: "+905551112233".into(),
        }]);
        let view = build_with(
            &[sms(1, &["+905551112233"], "hello", MIN, 0)],
            &[],
            vec![],
            &SpamRules::default(),
            &blocked,
        );
        assert_eq!(view.spam.len(), 1);
        // A code from an unknown sender survives a keyword rule.
        let keyword = SpamRules {
            rules: vec![spam::SpamRule::Keyword {
                text: "code".into(),
            }],
            ..SpamRules::default()
        };
        let view = build_with(
            &[sms(2, &["GARANTI"], "Your code is 482913", MIN, 1)],
            &[],
            vec![],
            &keyword,
            &BlockSet::default(),
        );
        assert!(view.spam.is_empty());
    }

    #[test]
    fn a_person_on_whatsapp_too_is_never_spam() {
        let view = build_with(
            &[sms(1, &["+905321234567"], "Win http://bit.ly/x", MIN, 1)],
            &[wa("905321234567@s.whatsapp.net", "Ayse", 5 * MIN, 0)],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert!(view.spam.is_empty());
        assert_eq!(view.rows.len(), 1);
    }

    #[test]
    fn the_channel_switch_points_at_the_other_thread() {
        let view = build_with(
            &[
                sms(1, &["+905321234567"], "sms", MIN, 0),
                sms(2, &["+905559876543"], "solo", 2 * MIN, 0),
            ],
            &[wa("905321234567@s.whatsapp.net", "Ayse", 5 * MIN, 0)],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        let sms_thread = ThreadId::Sms(1);
        let wa_thread = ThreadId::WhatsApp("905321234567@s.whatsapp.net".into());
        assert_eq!(
            switch_target(&view, &sms_thread),
            Some((&wa_thread, Badge::WhatsApp))
        );
        assert_eq!(
            switch_target(&view, &wa_thread),
            Some((&sms_thread, Badge::Sms))
        );
        assert_eq!(switch_target(&view, &ThreadId::Sms(2)), None);
        assert_eq!(switch_target(&view, &ThreadId::Sms(99)), None);
    }

    #[test]
    fn a_number_finds_its_one_to_one_thread() {
        let threads = [
            sms(1, &["+905321234567"], "a", MIN, 0),
            sms(2, &["+905559876543", "+905440001122"], "g", MIN, 0),
        ];
        assert_eq!(
            thread_for_number(&threads, "0532 123 45 67").map(|t| t.thread_id),
            Some(1)
        );
        assert_eq!(
            thread_for_number(&threads, "+90 532 123 45 67").map(|t| t.thread_id),
            Some(1)
        );
        // A group thread is not "the" thread with one of its members.
        assert_eq!(thread_for_number(&threads, "+905559876543"), None);
        assert_eq!(thread_for_number(&threads, "nonsense"), None);
    }

    fn wa_message(chat: &str, id: &str, from_me: bool, ts: i64) -> WaMessage {
        WaMessage {
            id: id.into(),
            chat: chat.into(),
            sender: None,
            from_me,
            timestamp_ms: ts,
            body: rovsti_wa_proto::MessageBody::Text(id.into()),
            reactions: vec![],
        }
    }

    #[test]
    fn the_chat_list_becomes_threads_and_keeps_newer_live_messages() {
        let chat = |jid: &str, name: Option<&str>, last: Option<WaMessage>, unread| ChatSummary {
            jid: jid.into(),
            name: name.map(Into::into),
            last,
            unread,
            is_group: false,
        };
        let jid = "905321234567@s.whatsapp.net";
        let live = vec![InboxThread {
            id: ThreadId::WhatsApp(jid.into()),
            last: to_rovsti(&wa_message(jid, "live", false, 900)),
            unread: 5,
            title: None,
            is_group: false,
        }];
        let threads = chat_threads(
            &[
                chat(
                    jid,
                    Some("Ayse"),
                    Some(wa_message(jid, "old", false, 100)),
                    2,
                ),
                chat(
                    "1@g.us",
                    Some(" "),
                    Some(wa_message("1@g.us", "g", false, 50)),
                    0,
                ),
                chat("empty@s.whatsapp.net", None, None, 0),
            ],
            &live,
        );
        assert_eq!(threads.len(), 2);
        assert_eq!(threads[0].last.body, "live");
        assert_eq!(threads[0].unread, 2);
        assert_eq!(threads[0].title.as_deref(), Some("Ayse"));
        assert!(threads[1].is_group);
        assert_eq!(threads[1].title, None);
    }

    #[test]
    fn a_live_message_updates_or_starts_a_thread() {
        let jid = "905321234567@s.whatsapp.net";
        let mut threads = Vec::new();
        fold_message(&mut threads, &wa_message(jid, "a", false, 100), false);
        assert_eq!((threads[0].unread, threads[0].last.body.as_str()), (1, "a"));
        fold_message(&mut threads, &wa_message(jid, "b", false, 200), false);
        assert_eq!((threads[0].unread, threads[0].last.body.as_str()), (2, "b"));
        // Our own message never counts, and an old one does not move the chat back.
        fold_message(&mut threads, &wa_message(jid, "c", true, 50), false);
        assert_eq!((threads[0].unread, threads[0].last.body.as_str()), (2, "b"));
        // Viewing the chat reads it.
        fold_message(&mut threads, &wa_message(jid, "d", false, 300), true);
        assert_eq!((threads[0].unread, threads[0].last.body.as_str()), (0, "d"));
        assert_eq!(threads.len(), 1);
        fold_message(&mut threads, &wa_message("9@g.us", "g", false, 1), false);
        assert!(threads[1].is_group);
    }

    #[test]
    fn row_times_follow_the_locale() {
        let day = 86_400_000;
        let utc = LocaleTime::default();
        assert_eq!(row_time(NOW - 5 * MIN, NOW, utc), "14:00");
        assert_eq!(row_time(NOW - day, NOW, utc), "Yesterday");
        assert_eq!(row_time(NOW - 4 * day, NOW, utc), "1 Nov");
        assert_eq!(row_time(NOW - 400 * day, NOW, utc), "1 Oct 2025");
        assert_eq!(row_time(0, NOW, utc), "");
        let plus3 = LocaleTime {
            tz_offset_s: 3 * 3600,
            ..utc
        };
        assert_eq!(row_time(NOW - 5 * MIN, NOW, plus3), "17:00");
        let twelve = LocaleTime::parse("0,12,mdy").expect("locale");
        assert_eq!(row_time(NOW - 5 * MIN, NOW, twelve), "2:00 PM");
        assert_eq!(row_time(NOW - 14 * 60 * MIN, NOW, twelve), "12:05 AM");
    }
}
