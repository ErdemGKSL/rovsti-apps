//! The unified inbox: how SMS threads and WhatsApp chats become rows.
//!
//! Merging is `rovsti_core::inbox::merge`; this module only maps its rows to
//! what the screen shows (title, badges, unread count, OTP chip, spam
//! filtering) and draws one row. The mapping is pure and tested here, the
//! drawing is a thin GPUI shell.

use std::{
    collections::{BTreeSet, HashMap},
    sync::Arc,
};

use gpui::{
    AnyElement, App, ClickEvent, InteractiveElement, IntoElement, LongPressEvent, ParentElement,
    RenderImage, SharedString, StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_core::{
    Channel, PhoneNumber, ThreadId,
    blocklist::BlockSet,
    inbox::{InboxRow, InboxThread, PersonKey, merge},
    otp,
    recents::LocaleTime,
    search::{self, ContactIndex},
    sms::SmsThreadRow,
    spam::{self, SpamRules},
};
use rovsti_ui::{
    Icon, theme,
    tokens::{Theme, typography},
};
use rovsti_wa_proto::{
    ChatActionKind, ChatMeta, DeliveryStatus, InstanceState, MUTED_FOREVER, MessageBody,
    MessageMeta, RichChat, WaMessage, event::ReceiptKind, is_pn_jid, is_status_chat, jid_user,
    meta::body_preview, to_rovsti,
};
use rovsti_wa_ui::{display_name, photo_avatar};

use crate::conversation::civil_from_days;
use crate::notifications::thread_tag;
use crate::sms_actions;
use crate::ticks::{Tick, read_color, ticks};

/// Which channel a badge names.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
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

/// One inbox row as shown. Text is held as `SharedString`, so drawing a row
/// only bumps reference counts; rows are built once per inbox change.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct RowView {
    pub title: SharedString,
    pub snippet: SharedString,
    /// One badge per channel of the person, newest thread's first.
    pub badges: Vec<Badge>,
    pub unread: u32,
    /// The code to copy, when the newest message is an unread one-time code.
    pub otp: Option<SharedString>,
    pub time: SharedString,
    /// The person's threads, newest first; a tap opens the first.
    pub threads: Vec<ThreadId>,
    pub initials: SharedString,
    /// Avatar colour follows the newest thread's channel.
    pub channel: Channel,
    /// Where the avatar's picture comes from.
    pub avatar: RowAvatar,
    /// The row's WhatsApp chat, for the long-press menu.
    pub wa_chat: Option<SharedString>,
    /// Pinned in WhatsApp: shown at the top.
    pub pinned: bool,
    /// Notifications of the WhatsApp chat are silenced.
    pub muted: bool,
    /// Marked unread by hand: the dot without a count.
    pub marked_unread: bool,
    /// The ticks of the newest message when it is a WhatsApp message of mine.
    pub tick: Option<Tick>,
    /// The person's newest SMS thread.
    pub sms_thread: Option<i64>,
    /// Its one address, when it is a one-to-one thread.
    pub sms_sender: Option<SharedString>,
}

/// The pictures an avatar may show, best first: the address book's photo,
/// then the WhatsApp profile picture. Initials when neither loads.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash)]
pub struct RowAvatar {
    /// `Contacts.PHOTO_THUMBNAIL_URI` of the matched contact.
    pub photo_uri: Option<SharedString>,
    /// The WhatsApp JID whose profile picture stands in.
    pub wa_jid: Option<SharedString>,
    /// Its current picture id, when the gateway told one.
    pub picture_id: Option<SharedString>,
}

impl RowView {
    /// The thread a tap opens.
    pub fn newest(&self) -> Option<&ThreadId> {
        self.threads.first()
    }
}

/// The chats a "mark all as read" touches.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MarkAllTargets {
    pub sms_threads: Vec<i64>,
    pub wa_chats: Vec<String>,
}

impl MarkAllTargets {
    /// How many chats are touched.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn count(&self) -> usize {
        self.sms_threads.len() + self.wa_chats.len()
    }
}

/// The unread chats of `rows` (the normal rows: pass neither the Spam
/// filter's nor the archived rows).
#[cfg_attr(not(test), allow(dead_code))]
pub fn mark_all_targets(rows: &[RowView]) -> MarkAllTargets {
    let mut out = MarkAllTargets::default();
    for row in rows.iter().filter(|r| r.unread > 0 || r.marked_unread) {
        if let Some(id) = row.sms_thread {
            out.sms_threads.push(id);
        }
        if let Some(chat) = &row.wa_chat {
            out.wa_chats.push(chat.to_string());
        }
    }
    out
}

/// "Marked 3 chats as read", "Marked 1 chat as read".
#[cfg_attr(not(test), allow(dead_code))]
pub fn mark_all_text(n: usize) -> String {
    let noun = if n == 1 { "chat" } else { "chats" };
    format!("Marked {n} {noun} as read")
}

/// The inbox, split into the normal rows, the Spam filter's rows and the
/// archived WhatsApp chats.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct InboxView {
    pub rows: Vec<RowView>,
    pub spam: Vec<RowView>,
    pub archived: Vec<RowView>,
}

impl InboxView {
    /// Unread messages sitting in the Spam filter.
    pub fn spam_unread(&self) -> u32 {
        self.spam.iter().map(|r| r.unread).sum()
    }

    /// Unread messages in archived chats.
    pub fn archived_unread(&self) -> u32 {
        self.archived.iter().map(|r| r.unread).sum()
    }

    /// True when `thread` sits in the Spam filter.
    pub fn in_spam(&self, thread: &ThreadId) -> bool {
        self.spam.iter().any(|r| r.threads.contains(thread))
    }

    /// True when the row holding `thread` is SMS only: no WhatsApp chat, no
    /// WhatsApp thread (so it may be moved into Spam).
    pub fn sms_only(&self, thread: &ThreadId) -> bool {
        self.all_rows()
            .find(|r| r.threads.contains(thread))
            .is_some_and(|r| {
                r.wa_chat.is_none() && r.threads.iter().all(|t| matches!(t, ThreadId::Sms(_)))
            })
    }

    /// Every row, wherever it is shown.
    pub fn all_rows(&self) -> impl Iterator<Item = &RowView> {
        self.rows.iter().chain(&self.spam).chain(&self.archived)
    }
}

/// The newest message of a WhatsApp chat as the inbox previews it.
#[derive(Clone, Debug, PartialEq)]
pub struct LastMessage {
    pub id: String,
    pub from_me: bool,
    pub sender: Option<String>,
    pub timestamp_ms: i64,
    pub body: MessageBody,
    pub meta: MessageMeta,
}

/// What the inbox keeps about a WhatsApp chat beyond its [`InboxThread`].
#[derive(Clone, Debug, Default, PartialEq)]
pub struct WaChatInfo {
    pub meta: ChatMeta,
    pub last: Option<LastMessage>,
    pub is_group: bool,
}

/// The chat meta of the gateway's chat list, by JID.
pub fn chat_infos(chats: &[RichChat]) -> HashMap<String, WaChatInfo> {
    chats
        .iter()
        .map(|chat| {
            let last = chat.chat.last.as_ref().map(|m| LastMessage {
                id: m.id.clone(),
                from_me: m.from_me,
                sender: m.sender.clone(),
                timestamp_ms: m.timestamp_ms,
                body: m.body.clone(),
                meta: chat.last_meta.clone().unwrap_or_default(),
            });
            let info = WaChatInfo {
                meta: chat.meta.clone(),
                last,
                is_group: chat.chat.is_group || chat.chat.jid.ends_with("@g.us"),
            };
            (chat.chat.jid.clone(), info)
        })
        .collect()
}

/// A live message becomes the chat's preview when it is the newest.
pub fn note_last(infos: &mut HashMap<String, WaChatInfo>, message: &WaMessage) {
    let info = infos
        .entry(message.chat.clone())
        .or_insert_with(|| WaChatInfo {
            is_group: message.chat.ends_with("@g.us"),
            ..WaChatInfo::default()
        });
    if info
        .last
        .as_ref()
        .is_some_and(|last| last.timestamp_ms > message.timestamp_ms)
    {
        return;
    }
    let meta = match &info.last {
        Some(last) if last.id == message.id => last.meta.clone(),
        _ => MessageMeta::default(),
    };
    info.last = Some(LastMessage {
        id: message.id.clone(),
        from_me: message.from_me,
        sender: message.sender.clone(),
        timestamp_ms: message.timestamp_ms,
        body: message.body.clone(),
        meta,
    });
}

/// A `message_meta` event: an edit, a delete for everyone or a star of the
/// chat's newest message changes its preview. Returns `true` on change.
pub fn note_meta(
    infos: &mut HashMap<String, WaChatInfo>,
    chat: &str,
    id: &str,
    body: Option<&MessageBody>,
    meta: &MessageMeta,
) -> bool {
    let Some(last) = infos
        .get_mut(chat)
        .and_then(|info| info.last.as_mut())
        .filter(|last| last.id == id)
    else {
        return false;
    };
    let mut changed = false;
    if let Some(body) = body
        && last.body != *body
    {
        last.body = body.clone();
        changed = true;
    }
    if last.meta != *meta {
        last.meta = meta.clone();
        changed = true;
    }
    changed
}

/// A `receipt` event: the ticks of the chat's newest message, when it is
/// mine, move forward (never back). Returns `true` on change.
pub fn note_receipt(
    infos: &mut HashMap<String, WaChatInfo>,
    chat: &str,
    ids: &[String],
    kind: ReceiptKind,
) -> bool {
    let Some(last) = infos
        .get_mut(chat)
        .and_then(|info| info.last.as_mut())
        .filter(|last| last.from_me && ids.contains(&last.id))
    else {
        return false;
    };
    let status = match kind {
        ReceiptKind::Delivered => DeliveryStatus::Delivered,
        ReceiptKind::Read => DeliveryStatus::Read,
    };
    let merged = status.merge(last.meta.status);
    if last.meta.status == Some(merged) {
        return false;
    }
    last.meta.status = Some(merged);
    true
}

/// The ticks the preview of `info`'s newest message shows: mine only, and
/// none on a deleted one.
pub fn preview_tick(info: &WaChatInfo) -> Option<Tick> {
    let last = info
        .last
        .as_ref()
        .filter(|l| l.from_me && !l.meta.revoked)?;
    let voice = last.meta.media.as_ref().is_some_and(|m| m.voice_note);
    Some(Tick::of_status(last.meta.status, voice))
}

/// The action that takes `action` back, given the chat's meta `before` it
/// was applied. `None` for mark-read (nothing to undo from a pill).
pub fn undo_of(action: &ChatActionKind, before: &ChatMeta) -> Option<ChatActionKind> {
    match *action {
        ChatActionKind::Archive { archived } => Some(ChatActionKind::Archive {
            archived: !archived,
        }),
        ChatActionKind::Pin { pinned } => Some(ChatActionKind::Pin { pinned: !pinned }),
        ChatActionKind::Mute { .. } => Some(ChatActionKind::Unmute),
        ChatActionKind::Unmute => before.muted_until_ms.map(|until| ChatActionKind::Mute {
            until_ms: (until != MUTED_FOREVER).then_some(until),
        }),
        ChatActionKind::MarkRead { .. } => None,
    }
}

/// What to tell the user after `action`; `None` when the row itself shows the
/// change (pin, mark read).
pub fn action_notice(action: &ChatActionKind) -> Option<&'static str> {
    match *action {
        ChatActionKind::Archive { archived: true } => Some("Archived"),
        ChatActionKind::Archive { archived: false } => Some("Unarchived"),
        ChatActionKind::Mute { .. } => Some("Muted"),
        ChatActionKind::Unmute => Some("Unmuted"),
        ChatActionKind::Pin { .. } | ChatActionKind::MarkRead { .. } => None,
    }
}

/// The inbox banner for the WhatsApp connection; `None` when there is nothing
/// to say (not linked, connected, or still linking).
pub fn wa_banner(linked: bool, state: Option<InstanceState>) -> Option<&'static str> {
    if !linked {
        return None;
    }
    match state {
        Some(InstanceState::Connected | InstanceState::Linking) => None,
        Some(InstanceState::Disconnected) | None => Some("WhatsApp is offline. Reconnecting…"),
        Some(InstanceState::LoggedOut) => {
            Some("WhatsApp logged this phone out. Tap to link again.")
        }
        Some(InstanceState::Banned) => Some("WhatsApp blocked this account. Tap for details."),
    }
}

/// What a chat action does to the chat before the gateway confirms it (its
/// `chat_meta` event overwrites this). `unread` is the chat's counter.
pub fn apply_action(meta: &mut ChatMeta, unread: &mut u32, action: &ChatActionKind) {
    match *action {
        ChatActionKind::Archive { archived } => meta.archived = archived,
        ChatActionKind::Pin { pinned } => meta.pinned = pinned,
        ChatActionKind::Mute { until_ms } => {
            meta.muted_until_ms = Some(until_ms.unwrap_or(MUTED_FOREVER));
        }
        ChatActionKind::Unmute => meta.muted_until_ms = None,
        ChatActionKind::MarkRead { read } => {
            meta.marked_unread = !read;
            if read {
                *unread = 0;
            }
        }
    }
}

/// `0:42`, `12:05`, `1:02:03`.
pub fn duration_label(seconds: u32) -> String {
    let (h, m, s) = (seconds / 3600, seconds % 3600 / 60, seconds % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// The one-line preview of a WhatsApp chat's newest message: deletes and
/// edits said so, shared locations and contacts named, voice notes timed,
/// and in groups the sender in front (contact, push name, number). `None`
/// when nothing better than the plain body is known.
pub fn wa_snippet(info: &WaChatInfo, contacts: &ContactIndex) -> Option<String> {
    let last = info.last.as_ref()?;
    let meta = &last.meta;
    if meta.revoked {
        return Some(if last.from_me {
            "You deleted this message".to_owned()
        } else {
            "This message was deleted".to_owned()
        });
    }
    let text = if let Some(location) = &meta.location {
        let place = location
            .name
            .clone()
            .filter(|n| !n.trim().is_empty())
            .unwrap_or_else(|| "Location".to_owned());
        if location.live {
            format!("Live location · {place}")
        } else {
            format!("Location · {place}")
        }
    } else if let Some(contact) = &meta.contact {
        format!("Contact · {}", contact.display_name)
    } else if let (MessageBody::Media { .. }, Some(media)) = (&last.body, &meta.media)
        && media.voice_note
    {
        match media.seconds {
            Some(s) => format!("Voice message ({})", duration_label(s)),
            None => "Voice message".to_owned(),
        }
    } else {
        body_preview(&last.body, 120)?
    };
    let text = if meta.edited_ms.is_some() {
        format!("{text} · Edited")
    } else {
        text
    };
    if last.from_me {
        return Some(format!("You: {text}"));
    }
    if info.is_group {
        let sender = last.sender.as_deref().unwrap_or("");
        let contact = is_pn_jid(sender)
            .then(|| contacts.match_number(&format!("+{}", jid_user(sender))))
            .flatten()
            .map(|c| c.display_name.as_str());
        let name = display_name(contact, meta.sender_name.as_deref(), sender);
        if !name.is_empty() {
            return Some(format!("{name}: {text}"));
        }
    }
    Some(text)
}

/// What a build needs besides the threads.
pub struct Inputs<'a> {
    pub contacts: &'a ContactIndex,
    /// What the gateway says about each WhatsApp chat.
    pub wa: &'a HashMap<String, WaChatInfo>,
    pub rules: &'a SpamRules,
    pub blocked: &'a BlockSet,
    /// Muted notification tags (`sms:<id>`, `wa:<jid>`).
    pub muted: &'a BTreeSet<String>,
    pub locale: LocaleTime,
    pub now_ms: i64,
}

/// SMS thread rows by thread id, built once per merge so that looking up a
/// row's source is O(1) instead of a scan per row.
struct SmsIndex<'a>(HashMap<i64, &'a SmsThreadRow>);

impl<'a> SmsIndex<'a> {
    fn new(sms: &'a [SmsThreadRow]) -> Self {
        let mut map = HashMap::with_capacity(sms.len());
        for thread in sms {
            // The first row wins, as a scan would find it.
            map.entry(thread.thread_id).or_insert(thread);
        }
        Self(map)
    }

    fn get(&self, id: &ThreadId) -> Option<&'a SmsThreadRow> {
        match id {
            ThreadId::Sms(thread_id) => self.0.get(thread_id).copied(),
            ThreadId::WhatsApp(_) => None,
        }
    }
}

pub fn build(sms: &[SmsThreadRow], wa: &[InboxThread], inputs: &Inputs<'_>) -> InboxView {
    let index = SmsIndex::new(sms);
    let mut view = InboxView::default();
    for row in merge(sms, wa, inputs.contacts) {
        let spam = is_spam(&row, &index, inputs);
        let archived = is_archived(&row, inputs.wa);
        let shown = row_view(&row, &index, inputs);
        if spam {
            view.spam.push(shown);
        } else if archived {
            view.archived.push(shown);
        } else {
            view.rows.push(shown);
        }
    }
    // Pinned chats lead, each part keeping its newest-first order.
    view.rows.sort_by_key(|row| !row.pinned);
    view
}

/// A row is archived when every thread of it is an archived WhatsApp chat:
/// an SMS thread of the same person keeps it in the inbox.
fn is_archived(row: &InboxRow, wa: &HashMap<String, WaChatInfo>) -> bool {
    row.threads.iter().all(|t| match &t.id {
        ThreadId::WhatsApp(jid) => wa.get(jid).is_some_and(|i| i.meta.archived),
        ThreadId::Sms(_) => false,
    })
}

/// Only a one-to-one conversation that lives purely in SMS can be spam:
/// anyone also on WhatsApp, a contact or a group never is. The verdict is on
/// the newest message, like the notification path (`rovsti_core::spam`).
fn is_spam(row: &InboxRow, sms: &SmsIndex<'_>, inputs: &Inputs<'_>) -> bool {
    if row.channels != [Channel::Sms] {
        return false;
    }
    let newest = &row.threads[0];
    if newest.is_group {
        return false;
    }
    let Some(source) = sms.get(&newest.id) else {
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
fn row_title(row: &InboxRow, sms: &SmsIndex<'_>) -> String {
    if let PersonKey::Group(ThreadId::Sms(_)) = row.key
        && row.threads.first().is_some_and(|t| t.title.is_none())
        && let Some(source) = sms.get(&row.threads[0].id)
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
/// message yet are left out. A one-to-one chat without a name takes the
/// person's push name (the contact name still wins in the merge).
pub fn chat_threads(chats: &[RichChat], have: &[InboxThread]) -> Vec<InboxThread> {
    chats
        .iter()
        // An older gateway listed status updates as a chat; they have their
        // own page now.
        .filter(|rich| !is_status_chat(&rich.chat.jid))
        .filter_map(|rich| {
            let chat = &rich.chat;
            let is_group = chat.is_group || chat.jid.ends_with("@g.us");
            let mut last = to_rovsti(chat.last.as_ref()?);
            let id = ThreadId::WhatsApp(chat.jid.clone());
            if let Some(held) = have.iter().find(|t| t.id == id)
                && held.last.timestamp_ms > last.timestamp_ms
            {
                last = held.last.clone();
            }
            let push_name = (!is_group).then(|| rich.meta.push_name.clone()).flatten();
            Some(InboxThread {
                id,
                last,
                unread: chat.unread,
                title: chat
                    .name
                    .clone()
                    .filter(|n| !n.trim().is_empty())
                    .or(push_name)
                    .filter(|n| !n.trim().is_empty()),
                is_group,
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

/// A `chat_merged` event: the chat `from` (a LID) now lives under `into`. The
/// `from` thread goes; its unread count adds to `into`'s and the newer last
/// message wins. The chat meta (pin, mute, archive) moves when `into` has
/// none of its own. `into` is created from `from` when it was not listed.
pub fn apply_merge(
    threads: &mut Vec<InboxThread>,
    info: &mut HashMap<String, WaChatInfo>,
    from: &str,
    into: &str,
) {
    if from == into {
        return;
    }
    let from_id = ThreadId::WhatsApp(from.to_owned());
    let into_id = ThreadId::WhatsApp(into.to_owned());
    if let Some(at) = threads.iter().position(|t| t.id == from_id) {
        let mut gone = threads.remove(at);
        match threads.iter_mut().find(|t| t.id == into_id) {
            Some(kept) => {
                kept.unread = kept.unread.saturating_add(gone.unread);
                if gone.last.timestamp_ms > kept.last.timestamp_ms {
                    gone.last.thread = into_id;
                    kept.last = gone.last;
                }
                if kept.title.is_none() {
                    kept.title = gone.title;
                }
            }
            None => {
                gone.id = into_id.clone();
                gone.last.thread = into_id;
                threads.push(gone);
            }
        }
    }
    let Some(gone) = info.remove(from) else {
        return;
    };
    match info.get_mut(into) {
        Some(kept) => {
            if kept.meta == ChatMeta::default() {
                kept.meta = gone.meta;
            } else if kept.meta.push_name.is_none() {
                kept.meta.push_name = gone.meta.push_name;
            }
            let older = match (&kept.last, &gone.last) {
                (Some(a), Some(b)) => b.timestamp_ms > a.timestamp_ms,
                (None, Some(_)) => true,
                _ => false,
            };
            if older {
                kept.last = gone.last;
            }
        }
        None => {
            info.insert(into.to_owned(), gone);
        }
    }
}

pub fn badges(row: &InboxRow) -> Vec<Badge> {
    row.channels.iter().copied().map(Badge::of).collect()
}

/// A WhatsApp chat is muted on the gateway or by the local tag.
pub fn wa_muted(meta: Option<&ChatMeta>, local: bool, now_ms: i64) -> bool {
    local || meta.is_some_and(|m| m.is_muted(now_ms))
}

/// What the bell inside a WhatsApp chat does next.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BellToggle {
    Mute,
    Unmute { gateway: bool, local: bool },
}

/// Mute when nothing mutes the chat; else unmute whichever mutes it.
pub fn wa_bell(meta: Option<&ChatMeta>, local: bool, now_ms: i64) -> BellToggle {
    let gateway = meta.is_some_and(|m| m.is_muted(now_ms));
    if gateway || local {
        BellToggle::Unmute { gateway, local }
    } else {
        BellToggle::Mute
    }
}

/// JIDs muted only on the gateway (no local tag), sorted, for Settings > Muted.
pub fn gateway_muted_jids(
    wa: &HashMap<String, WaChatInfo>,
    local_tags: &BTreeSet<String>,
    now_ms: i64,
) -> Vec<String> {
    let mut jids: Vec<String> = wa
        .iter()
        .filter(|(jid, info)| {
            info.meta.is_muted(now_ms)
                && !local_tags.contains(&thread_tag(&ThreadId::WhatsApp((*jid).clone())))
        })
        .map(|(jid, _)| jid.clone())
        .collect();
    jids.sort();
    jids
}

fn row_view(row: &InboxRow, sms: &SmsIndex<'_>, inputs: &Inputs<'_>) -> RowView {
    let title = row_title(row, sms);
    let newest = &row.threads[0];
    // A code is worth a chip while it is new: an old, read one is just text.
    let otp = (newest.id.channel() == Channel::Sms && row.unread > 0)
        .then(|| otp::detect(&newest.last.body))
        .flatten()
        .map(|o| SharedString::from(o.code));
    // The row's WhatsApp chat (a merged person has at most one).
    let wa_chat = row.threads.iter().find_map(|t| match &t.id {
        ThreadId::WhatsApp(jid) => Some(jid.as_str()),
        ThreadId::Sms(_) => None,
    });
    let info = wa_chat.and_then(|jid| inputs.wa.get(jid));
    let snippet = match (&newest.id, info) {
        (ThreadId::WhatsApp(_), Some(info)) => wa_snippet(info, inputs.contacts),
        _ => None,
    }
    .unwrap_or_else(|| row.last.body.clone());
    let tick = match (&newest.id, info) {
        (ThreadId::WhatsApp(_), Some(info)) => preview_tick(info),
        _ => None,
    };
    let meta = info.map(|i| &i.meta);
    let sms_thread = row.threads.iter().find_map(|t| match t.id {
        ThreadId::Sms(id) => Some(id),
        ThreadId::WhatsApp(_) => None,
    });
    let sms_sender = sms_thread
        .and_then(|id| sms.0.get(&id))
        .and_then(|source| sms_actions::sender_of(&source.addresses))
        .map(|s| SharedString::from(s.to_owned()));
    let sms_muted =
        sms_thread.is_some_and(|id| inputs.muted.contains(&thread_tag(&ThreadId::Sms(id))));
    let wa_local = wa_chat.is_some_and(|jid| {
        inputs
            .muted
            .contains(&thread_tag(&ThreadId::WhatsApp(jid.to_owned())))
    });
    let photo_uri = match &row.key {
        PersonKey::Contact(_) => contact_photo(row, sms, inputs.contacts),
        _ => None,
    };
    RowView {
        initials: initials(&title).into(),
        title: title.into(),
        snippet: snippet.into(),
        badges: badges(row),
        unread: row.unread,
        otp,
        time: row_time(row.last.timestamp_ms, inputs.now_ms, inputs.locale).into(),
        threads: row.threads.iter().map(|t| t.id.clone()).collect(),
        channel: newest.id.channel(),
        avatar: RowAvatar {
            photo_uri: photo_uri.map(SharedString::from),
            wa_jid: wa_chat.map(|jid| SharedString::from(jid.to_owned())),
            picture_id: meta
                .and_then(|m| m.picture_id.clone())
                .map(SharedString::from),
        },
        wa_chat: wa_chat.map(|jid| SharedString::from(jid.to_owned())),
        pinned: meta.is_some_and(|m| m.pinned),
        muted: sms_muted || wa_muted(meta, wa_local, inputs.now_ms),
        marked_unread: meta.is_some_and(|m| m.marked_unread),
        tick,
        sms_thread,
        sms_sender,
    }
}

/// Shows the unsent draft of each row's first thread that has one in place
/// of its snippet.
pub fn apply_drafts<'a>(view: &mut InboxView, draft_of: impl Fn(&ThreadId) -> Option<&'a str>) {
    for row in view
        .rows
        .iter_mut()
        .chain(view.spam.iter_mut())
        .chain(view.archived.iter_mut())
    {
        let draft = row.threads.iter().find_map(&draft_of);
        if draft.is_some() {
            row.snippet = snippet_with_draft(&row.snippet, draft).into();
        }
    }
}

/// The snippet an inbox row shows: the unsent draft's first line when there is one.
pub fn snippet_with_draft(snippet: &str, draft: Option<&str>) -> String {
    match draft.map(|d| d.trim()).filter(|d| !d.is_empty()) {
        Some(draft) => format!("Draft: {}", draft.lines().next().unwrap_or("").trim()),
        None => snippet.to_owned(),
    }
}

/// Indices of the rows that match `query`: by folded title or snippet, or, for
/// a query with three or more digits, by the digits of the SMS sender. A blank
/// query keeps every row. Order is kept.
pub fn filter_rows(rows: &[RowView], query: &str) -> Vec<usize> {
    let query = query.trim();
    if query.is_empty() {
        return (0..rows.len()).collect();
    }
    let folded = search::fold(query);
    let digits: String = query.chars().filter(char::is_ascii_digit).collect();
    rows.iter()
        .enumerate()
        .filter(|(_, row)| {
            search::fold(&row.title).contains(&folded)
                || search::fold(&row.snippet).contains(&folded)
                || (digits.len() >= 3
                    && row.sms_sender.as_ref().is_some_and(|sender| {
                        let have: String = sender.chars().filter(char::is_ascii_digit).collect();
                        have.contains(&digits)
                    }))
        })
        .map(|(i, _)| i)
        .collect()
}

/// The photo of the contact a row belongs to, found through its number.
fn contact_photo(row: &InboxRow, sms: &SmsIndex<'_>, contacts: &ContactIndex) -> Option<String> {
    row.threads.iter().find_map(|thread| {
        let number = match &thread.id {
            ThreadId::Sms(_) => sms.get(&thread.id)?.addresses.first()?.clone(),
            ThreadId::WhatsApp(jid) if is_pn_jid(jid) => format!("+{}", jid_user(jid)),
            ThreadId::WhatsApp(_) => return None,
        };
        contacts
            .match_number(&number)
            .and_then(|c| c.photo_uri.clone())
            .filter(|uri| !uri.is_empty())
    })
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
type PressHandler = Box<dyn Fn(&LongPressEvent, &mut Window, &mut App)>;

/// What a row is drawn with besides its view.
pub struct RowExtras {
    /// The picture to fade in over the initials, and its opacity.
    pub photo: Option<(Arc<RenderImage>, f32)>,
    pub copied: bool,
    pub open: Handler,
    pub copy_code: Handler,
    /// Long press: the chat menu (WhatsApp rows only).
    pub menu: Option<PressHandler>,
}

fn small_icon(icon: Icon, color: gpui::Hsla) -> impl IntoElement {
    gpui::svg()
        .flex_none()
        .path(icon.path())
        .size(px(14.0))
        .text_color(color)
}

/// One inbox row: avatar, title and time, badges and snippet, unread dot, and
/// under it the OTP chip when there is one. The chip is a sibling of the
/// tappable row, so copying a code never opens the conversation. Only rows
/// on screen are built (the inbox is a virtualised `list`).
pub fn row_element(theme: Theme, index: usize, view: &RowView, extras: RowExtras) -> AnyElement {
    let RowExtras {
        photo,
        copied,
        open,
        copy_code,
        menu,
    } = extras;
    let colors = theme::colors(theme);
    let tint = match view.channel {
        Channel::Sms => colors.accent,
        Channel::WhatsApp => colors.green,
    };
    let unread = view.unread > 0 || view.marked_unread;
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

    let mut main = div()
        .id(("inbox-row", index))
        .flex()
        .items_center()
        .gap(px(12.0))
        .child(photo_avatar(
            theme,
            view.initials.clone(),
            44.0,
            tint,
            photo,
        ))
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
                                .child(view.title.clone()),
                        )
                        .children(
                            view.muted
                                .then(|| small_icon(Icon::LucideBellOff, colors.tertiary_label)),
                        )
                        .children(
                            view.pinned
                                .then(|| small_icon(Icon::LucidePin, colors.tertiary_label)),
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
                                .child(view.time.clone()),
                        ),
                )
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .children(badges)
                        .children(view.tick.map(|tick| {
                            ticks(tick, 15.0, colors.tertiary_label, read_color(false))
                        }))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .text_size(typography::SUBHEADLINE.size)
                                .font_weight(weight)
                                .text_color(colors.secondary_label)
                                .child(view.snippet.clone()),
                        )
                        .children(unread.then(|| {
                            // Marked unread by hand: a dot without a count.
                            let badge = div()
                                .flex_none()
                                .rounded_full()
                                .bg(if view.muted {
                                    colors.tertiary_label
                                } else {
                                    colors.accent
                                })
                                .flex()
                                .items_center()
                                .justify_center();
                            if view.unread > 0 {
                                badge
                                    .min_w(px(20.0))
                                    .h(px(20.0))
                                    .px(px(6.0))
                                    .text_size(typography::CAPTION.size)
                                    .text_color(colors.background)
                                    .child(view.unread.to_string())
                            } else {
                                badge.size(px(12.0))
                            }
                        })),
                ),
        )
        .on_click(open);
    if let Some(menu) = menu {
        main = main.on_long_press(menu);
    }

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

    #[test]
    fn undo_reverses_archive_pin_and_mute() {
        let none = ChatMeta::default();
        assert_eq!(
            undo_of(&ChatActionKind::Archive { archived: true }, &none),
            Some(ChatActionKind::Archive { archived: false })
        );
        assert_eq!(
            undo_of(&ChatActionKind::Pin { pinned: false }, &none),
            Some(ChatActionKind::Pin { pinned: true })
        );
        assert_eq!(
            undo_of(&ChatActionKind::Mute { until_ms: Some(5) }, &none),
            Some(ChatActionKind::Unmute)
        );
        assert_eq!(undo_of(&ChatActionKind::Unmute, &none), None);
        let forever = ChatMeta {
            muted_until_ms: Some(MUTED_FOREVER),
            ..ChatMeta::default()
        };
        assert_eq!(
            undo_of(&ChatActionKind::Unmute, &forever),
            Some(ChatActionKind::Mute { until_ms: None })
        );
        let timed = ChatMeta {
            muted_until_ms: Some(9_000),
            ..ChatMeta::default()
        };
        assert_eq!(
            undo_of(&ChatActionKind::Unmute, &timed),
            Some(ChatActionKind::Mute {
                until_ms: Some(9_000)
            })
        );
        assert_eq!(
            undo_of(&ChatActionKind::MarkRead { read: true }, &none),
            None
        );
    }

    #[test]
    fn notices_only_for_hidden_changes() {
        let n = action_notice;
        assert_eq!(
            n(&ChatActionKind::Archive { archived: true }),
            Some("Archived")
        );
        assert_eq!(
            n(&ChatActionKind::Archive { archived: false }),
            Some("Unarchived")
        );
        assert_eq!(n(&ChatActionKind::Mute { until_ms: None }), Some("Muted"));
        assert_eq!(n(&ChatActionKind::Unmute), Some("Unmuted"));
        assert_eq!(n(&ChatActionKind::Pin { pinned: true }), None);
        assert_eq!(n(&ChatActionKind::MarkRead { read: false }), None);
    }

    #[test]
    fn banner_only_when_linked_and_not_connected() {
        use InstanceState::*;
        for state in [None, Some(Connected), Some(Linking), Some(LoggedOut)] {
            assert_eq!(wa_banner(false, state), None);
        }
        assert_eq!(wa_banner(true, Some(Connected)), None);
        assert_eq!(wa_banner(true, Some(Linking)), None);
        assert!(wa_banner(true, None).expect("banner").contains("offline"));
        assert!(
            wa_banner(true, Some(Disconnected))
                .expect("banner")
                .contains("offline")
        );
        assert!(
            wa_banner(true, Some(LoggedOut))
                .expect("banner")
                .contains("link again")
        );
        assert!(
            wa_banner(true, Some(Banned))
                .expect("banner")
                .contains("blocked")
        );
    }

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

    #[test]
    fn apply_merge_folds_the_lid_chat_into_the_pn_chat() {
        let lid = "1@lid";
        let pn = "905321111111@s.whatsapp.net";
        let mut threads = vec![wa(lid, "x", 10, 2), wa(pn, "Annem", 500, 3)];
        let mut infos = HashMap::new();
        infos.insert(
            lid.to_owned(),
            info(
                ChatMeta {
                    pinned: true,
                    ..ChatMeta::default()
                },
                Some(last(true, None, "newer", MessageMeta::default())),
                false,
            ),
        );
        apply_merge(&mut threads, &mut infos, lid, pn);
        assert_eq!(threads.len(), 1);
        assert_eq!(threads[0].id, ThreadId::WhatsApp(pn.into()));
        assert_eq!(threads[0].unread, 5);
        assert_eq!(threads[0].last.timestamp_ms, NOW - 10, "newer last kept");
        assert_eq!(threads[0].last.thread, threads[0].id);
        assert_eq!(threads[0].title.as_deref(), Some("Annem"));
        assert!(!infos.contains_key(lid));
        assert!(infos[pn].meta.pinned, "meta moved");
        assert!(infos[pn].last.is_some());

        // Again: nothing left to merge.
        let before = threads.clone();
        apply_merge(&mut threads, &mut infos, lid, pn);
        assert_eq!(threads, before);
    }

    #[test]
    fn apply_merge_keeps_the_target_meta_and_renames_a_lone_thread() {
        let lid = "1@lid";
        let pn = "2@s.whatsapp.net";
        let mut threads = vec![wa(lid, "x", 10, 1)];
        let mut infos = HashMap::new();
        infos.insert(
            lid.to_owned(),
            info(
                ChatMeta {
                    pinned: true,
                    ..ChatMeta::default()
                },
                None,
                false,
            ),
        );
        infos.insert(
            pn.to_owned(),
            info(
                ChatMeta {
                    archived: true,
                    ..ChatMeta::default()
                },
                None,
                false,
            ),
        );
        apply_merge(&mut threads, &mut infos, lid, pn);
        assert_eq!(threads.len(), 1);
        assert_eq!(threads[0].id, ThreadId::WhatsApp(pn.into()));
        assert!(infos[pn].meta.archived && !infos[pn].meta.pinned);
    }

    fn build_with(
        sms: &[SmsThreadRow],
        wa: &[InboxThread],
        contacts: Vec<WireContact>,
        rules: &SpamRules,
        blocked: &BlockSet,
    ) -> InboxView {
        build_meta(sms, wa, contacts, rules, blocked, &HashMap::new())
    }

    fn build_meta(
        sms: &[SmsThreadRow],
        wa: &[InboxThread],
        contacts: Vec<WireContact>,
        rules: &SpamRules,
        blocked: &BlockSet,
        infos: &HashMap<String, WaChatInfo>,
    ) -> InboxView {
        let index = ContactIndex::new(contacts);
        build(
            sms,
            wa,
            &Inputs {
                contacts: &index,
                wa: infos,
                rules,
                blocked,
                muted: &BTreeSet::new(),
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

    fn build_muted(sms: &[SmsThreadRow], muted: &BTreeSet<String>) -> InboxView {
        let index = ContactIndex::new(vec![]);
        build(
            sms,
            &[],
            &Inputs {
                contacts: &index,
                wa: &HashMap::new(),
                rules: &rules(),
                blocked: &BlockSet::default(),
                muted,
                locale: LocaleTime::default(),
                now_ms: NOW,
            },
        )
    }

    #[test]
    fn sms_rows_carry_thread_and_sender() {
        let view = build_with(
            &[
                sms(1, &["+905321234567"], "hi", 5 * MIN, 0),
                sms(2, &["+905321111111", "+905322222222"], "group", 4 * MIN, 0),
            ],
            &[wa("905559999999@s.whatsapp.net", "Wa", 3 * MIN, 0)],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        let one = view
            .rows
            .iter()
            .find(|r| r.sms_thread == Some(1))
            .expect("row");
        assert_eq!(one.sms_sender.as_deref(), Some("+905321234567"));
        let group = view
            .rows
            .iter()
            .find(|r| r.sms_thread == Some(2))
            .expect("row");
        assert_eq!(group.sms_sender, None);
        let wa_row = view.rows.iter().find(|r| r.wa_chat.is_some()).expect("row");
        assert_eq!((wa_row.sms_thread, wa_row.sms_sender.clone()), (None, None));
    }

    #[test]
    fn muted_sms_thread_shows_the_bell() {
        let rows = [sms(1, &["+905321234567"], "hi", 5 * MIN, 0)];
        assert!(!build_muted(&rows, &BTreeSet::new()).rows[0].muted);
        let muted: BTreeSet<String> = ["sms:1".to_owned()].into();
        assert!(build_muted(&rows, &muted).rows[0].muted);
        let other: BTreeSet<String> = ["sms:2".to_owned()].into();
        assert!(!build_muted(&rows, &other).rows[0].muted);
    }

    fn row_for(title: &str, snippet: &str, sender: Option<&str>) -> RowView {
        let mut view = build_with(
            &[sms(1, &["+905321234567"], snippet, MIN, 0)],
            &[],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        let mut row = view.rows.remove(0);
        row.title = title.to_owned().into();
        row.sms_sender = sender.map(|s| SharedString::from(s.to_owned()));
        row
    }

    #[test]
    fn mark_all_skips_read_and_spam_rows() {
        let mut unread = row_for("A", "x", None);
        unread.unread = 2;
        unread.sms_thread = Some(7);
        let read = row_for("B", "y", None);
        let mut wa = row_for("C", "z", None);
        wa.marked_unread = true;
        wa.sms_thread = None;
        wa.wa_chat = Some("1@s.whatsapp.net".into());
        let view = InboxView {
            rows: vec![unread, read, wa],
            spam: vec![{
                let mut r = row_for("S", "spam", None);
                r.unread = 5;
                r
            }],
            ..InboxView::default()
        };
        let t = mark_all_targets(&view.rows);
        assert_eq!(t.sms_threads, [7]);
        assert_eq!(t.wa_chats, ["1@s.whatsapp.net"]);
        assert_eq!(t.count(), 2);
    }

    #[test]
    fn mark_all_text_plural() {
        assert_eq!(mark_all_text(3), "Marked 3 chats as read");
        assert_eq!(mark_all_text(1), "Marked 1 chat as read");
    }

    #[test]
    fn drafts_replace_snippets_of_rows_that_have_one() {
        let mut view = InboxView {
            rows: vec![row_for("A", "old", None), {
                let mut r = row_for("B", "keep", None);
                r.threads = vec![ThreadId::Sms(99)];
                r
            }],
            ..InboxView::default()
        };
        let first = view.rows[0].threads[0].clone();
        let text = "line one\nline two".to_owned();
        apply_drafts(&mut view, |t| (*t == first).then_some(text.as_str()));
        assert_eq!(view.rows[0].snippet, "Draft: line one");
        assert_eq!(view.rows[1].snippet, "keep");
    }

    #[test]
    fn filter_rows_matches_title_snippet_digits_and_turkish_fold() {
        let rows = vec![
            row_for("Ayşe Yılmaz", "see you", Some("+905321234567")),
            row_for("Bank", "Your balance", Some("+908501112233")),
            row_for("Group", "no sender", None),
        ];
        assert_eq!(filter_rows(&rows, "ayse"), vec![0]);
        assert_eq!(filter_rows(&rows, "BALANCE"), vec![1]);
        assert_eq!(filter_rows(&rows, "0532 123"), vec![0]);
        assert_eq!(filter_rows(&rows, "9999"), Vec::<usize>::new());
        assert_eq!(filter_rows(&rows, "85011"), vec![1]);
        // Two digits are too few for a number match.
        assert_eq!(filter_rows(&rows, "53"), Vec::<usize>::new());
    }

    #[test]
    fn blank_query_keeps_all() {
        let rows = vec![row_for("A", "x", None), row_for("B", "y", None)];
        assert_eq!(filter_rows(&rows, ""), vec![0, 1]);
        assert_eq!(filter_rows(&rows, "   "), vec![0, 1]);
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
    fn local_wa_mute_shows_the_bell() {
        let jid = "905321111111@s.whatsapp.net";
        let threads = [wa(jid, "A", MIN, 0)];
        let index = ContactIndex::new(vec![]);
        let run = |muted: &BTreeSet<String>| {
            build(
                &[],
                &threads,
                &Inputs {
                    contacts: &index,
                    wa: &HashMap::new(),
                    rules: &rules(),
                    blocked: &BlockSet::default(),
                    muted,
                    locale: LocaleTime::default(),
                    now_ms: NOW,
                },
            )
        };
        assert!(!run(&BTreeSet::new()).rows[0].muted);
        let tag = thread_tag(&ThreadId::WhatsApp(jid.to_owned()));
        assert!(run(&[tag].into()).rows[0].muted);
    }

    #[test]
    fn bell_unmutes_both_and_mutes_on_the_gateway() {
        let forever = ChatMeta {
            muted_until_ms: Some(MUTED_FOREVER),
            ..ChatMeta::default()
        };
        let expired = ChatMeta {
            muted_until_ms: Some(NOW - 1),
            ..ChatMeta::default()
        };
        assert_eq!(wa_bell(None, false, NOW), BellToggle::Mute);
        assert_eq!(wa_bell(Some(&expired), false, NOW), BellToggle::Mute);
        assert_eq!(
            wa_bell(Some(&forever), false, NOW),
            BellToggle::Unmute {
                gateway: true,
                local: false
            }
        );
        assert_eq!(
            wa_bell(Some(&forever), true, NOW),
            BellToggle::Unmute {
                gateway: true,
                local: true
            }
        );
        assert_eq!(
            wa_bell(Some(&expired), true, NOW),
            BellToggle::Unmute {
                gateway: false,
                local: true
            }
        );
        assert!(wa_muted(None, true, NOW));
        assert!(!wa_muted(Some(&expired), false, NOW));
    }

    #[test]
    fn gateway_muted_jids_skip_local_tags() {
        let mute = |until: i64| WaChatInfo {
            meta: ChatMeta {
                muted_until_ms: Some(until),
                ..ChatMeta::default()
            },
            ..WaChatInfo::default()
        };
        let wa: HashMap<String, WaChatInfo> = [
            ("b@s.whatsapp.net".to_owned(), mute(MUTED_FOREVER)),
            ("a@s.whatsapp.net".to_owned(), mute(MUTED_FOREVER)),
            ("c@s.whatsapp.net".to_owned(), mute(NOW - 1)),
            ("d@s.whatsapp.net".to_owned(), mute(MUTED_FOREVER)),
        ]
        .into();
        let local: BTreeSet<String> = [thread_tag(&ThreadId::WhatsApp(
            "d@s.whatsapp.net".to_owned(),
        ))]
        .into();
        assert_eq!(
            gateway_muted_jids(&wa, &local, NOW),
            ["a@s.whatsapp.net", "b@s.whatsapp.net"]
        );
    }

    #[test]
    fn in_spam_finds_spam_threads_only() {
        let view = build_with(
            &[
                sms(1, &["+447700900123"], "Win now http://bit.ly/x", MIN, 1),
                sms(2, &["+905559876543"], "hello", 2 * MIN, 0),
            ],
            &[],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert!(view.in_spam(&ThreadId::Sms(1)));
        assert!(!view.in_spam(&ThreadId::Sms(2)));
        assert!(!view.in_spam(&ThreadId::Sms(9)));
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
    fn sms_only_is_false_for_merged_rows() {
        let view = build_with(
            &[
                sms(1, &["+905321234567"], "hi", MIN, 0),
                sms(2, &["+905559876543"], "hello", 2 * MIN, 0),
            ],
            &[wa("905321234567@s.whatsapp.net", "Ayse", 5 * MIN, 0)],
            vec![],
            &rules(),
            &BlockSet::default(),
        );
        assert!(!view.sms_only(&ThreadId::Sms(1)), "merged with WhatsApp");
        assert!(view.sms_only(&ThreadId::Sms(2)));
        assert!(!view.sms_only(&ThreadId::Sms(99)), "unknown thread");
        assert!(!view.sms_only(&ThreadId::WhatsApp("905321234567@s.whatsapp.net".into())));
    }

    #[test]
    fn sender_rule_moves_a_row_to_spam() {
        let rules = SpamRules {
            rules: vec![spam::SpamRule::Sender {
                pattern: "TRENDYOL".into(),
            }],
            ..SpamRules::default()
        };
        let view = build_with(
            &[
                sms(1, &["TRENDYOL"], "Kampanya", MIN, 1),
                sms(2, &["GARANTI"], "Merhaba", 2 * MIN, 0),
            ],
            &[],
            vec![],
            &rules,
            &BlockSet::default(),
        );
        assert!(view.in_spam(&ThreadId::Sms(1)));
        assert!(!view.in_spam(&ThreadId::Sms(2)));
        assert_eq!(view.spam.len(), 1);
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

    fn rich(chat: rovsti_wa_proto::ChatSummary) -> RichChat {
        RichChat {
            chat,
            meta: ChatMeta::default(),
            last_meta: None,
        }
    }

    #[test]
    fn the_chat_list_becomes_threads_and_keeps_newer_live_messages() {
        let chat = |jid: &str, name: Option<&str>, last: Option<WaMessage>, unread| {
            rich(rovsti_wa_proto::ChatSummary {
                jid: jid.into(),
                name: name.map(Into::into),
                last,
                unread,
                is_group: false,
            })
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
    fn a_nameless_chat_takes_the_push_name_but_a_group_does_not() {
        let jid = "905321234567@s.whatsapp.net";
        let mut one = rich(rovsti_wa_proto::ChatSummary {
            jid: jid.into(),
            name: None,
            last: Some(wa_message(jid, "a", false, 10)),
            unread: 0,
            is_group: false,
        });
        one.meta.push_name = Some("ayse".into());
        let mut group = rich(rovsti_wa_proto::ChatSummary {
            jid: "1@g.us".into(),
            name: None,
            last: Some(wa_message("1@g.us", "g", false, 5)),
            unread: 0,
            is_group: true,
        });
        group.meta.push_name = Some("someone".into());
        let threads = chat_threads(&[one, group], &[]);
        assert_eq!(threads[0].title.as_deref(), Some("ayse"));
        assert_eq!(threads[1].title, None);
    }

    fn info(meta: ChatMeta, last: Option<LastMessage>, is_group: bool) -> WaChatInfo {
        WaChatInfo {
            meta,
            last,
            is_group,
        }
    }

    fn last(from_me: bool, sender: Option<&str>, body: &str, meta: MessageMeta) -> LastMessage {
        LastMessage {
            id: "L".into(),
            from_me,
            sender: sender.map(Into::into),
            timestamp_ms: 1,
            body: MessageBody::Text(body.into()),
            meta,
        }
    }

    #[test]
    fn pinned_rows_lead_and_archived_ones_leave_the_inbox() {
        let a = "905321111111@s.whatsapp.net";
        let b = "905322222222@s.whatsapp.net";
        let c = "905323333333@s.whatsapp.net";
        let mut infos = HashMap::new();
        infos.insert(
            b.to_owned(),
            info(
                ChatMeta {
                    pinned: true,
                    muted_until_ms: Some(rovsti_wa_proto::MUTED_FOREVER),
                    picture_id: Some("pic".into()),
                    ..ChatMeta::default()
                },
                None,
                false,
            ),
        );
        infos.insert(
            c.to_owned(),
            info(
                ChatMeta {
                    archived: true,
                    marked_unread: true,
                    ..ChatMeta::default()
                },
                None,
                false,
            ),
        );
        let view = build_meta(
            &[],
            &[
                wa(a, "A", MIN, 0),
                wa(b, "B", 5 * MIN, 0),
                wa(c, "C", 2 * MIN, 0),
            ],
            vec![],
            &rules(),
            &BlockSet::default(),
            &infos,
        );
        let titles: Vec<&str> = view.rows.iter().map(|r| r.title.as_ref()).collect();
        assert_eq!(titles, ["B", "A"]);
        assert!(view.rows[0].pinned && view.rows[0].muted);
        assert_eq!(view.rows[0].avatar.picture_id.as_deref(), Some("pic"));
        assert_eq!(view.rows[0].wa_chat.as_deref(), Some(b));
        assert_eq!(view.archived.len(), 1);
        assert!(view.archived[0].marked_unread);
    }

    #[test]
    fn an_sms_thread_keeps_an_archived_person_in_the_inbox() {
        let jid = "905321234567@s.whatsapp.net";
        let mut infos = HashMap::new();
        infos.insert(
            jid.to_owned(),
            info(
                ChatMeta {
                    archived: true,
                    ..ChatMeta::default()
                },
                None,
                false,
            ),
        );
        let view = build_meta(
            &[sms(1, &["+905321234567"], "sms", MIN, 0)],
            &[wa(jid, "Ayse", 5 * MIN, 0)],
            vec![],
            &rules(),
            &BlockSet::default(),
            &infos,
        );
        assert_eq!(view.rows.len(), 1);
        assert!(view.archived.is_empty());
    }

    #[test]
    fn drafts_replace_the_snippet() {
        assert_eq!(snippet_with_draft("old", None), "old");
        assert_eq!(snippet_with_draft("old", Some("  \n ")), "old");
        assert_eq!(snippet_with_draft("old", Some("hi")), "Draft: hi");
        assert_eq!(
            snippet_with_draft("old", Some("\n first line \nsecond")),
            "Draft: first line"
        );
    }

    #[test]
    fn snippets_say_deleted_edited_and_who_wrote() {
        let contacts = ContactIndex::new(vec![contact(1, "Mert", "+905559876543")]);
        let revoked = MessageMeta {
            revoked: true,
            ..MessageMeta::default()
        };
        let edited = MessageMeta {
            edited_ms: Some(5),
            ..MessageMeta::default()
        };
        let one = |l| info(ChatMeta::default(), Some(l), false);
        assert_eq!(
            wa_snippet(&one(last(false, None, "x", revoked.clone())), &contacts).as_deref(),
            Some("This message was deleted")
        );
        assert_eq!(
            wa_snippet(&one(last(true, None, "x", revoked)), &contacts).as_deref(),
            Some("You deleted this message")
        );
        assert_eq!(
            wa_snippet(&one(last(false, None, "fixed", edited)), &contacts).as_deref(),
            Some("fixed · Edited")
        );
        let group = |l| info(ChatMeta::default(), Some(l), true);
        let named = MessageMeta {
            sender_name: Some("ada".into()),
            ..MessageMeta::default()
        };
        assert_eq!(
            wa_snippet(
                &group(last(
                    false,
                    Some("905559876543@s.whatsapp.net"),
                    "hi",
                    named.clone()
                )),
                &contacts
            )
            .as_deref(),
            Some("Mert: hi"),
            "the contact name wins"
        );
        assert_eq!(
            wa_snippet(
                &group(last(
                    false,
                    Some("905550000000@s.whatsapp.net"),
                    "hi",
                    named
                )),
                &contacts
            )
            .as_deref(),
            Some("ada: hi")
        );
        assert_eq!(
            wa_snippet(
                &group(last(
                    false,
                    Some("905550000000@s.whatsapp.net"),
                    "hi",
                    MessageMeta::default()
                )),
                &contacts
            )
            .as_deref(),
            Some("+905550000000: hi")
        );
        assert_eq!(
            wa_snippet(
                &group(last(true, None, "mine", MessageMeta::default())),
                &contacts
            )
            .as_deref(),
            Some("You: mine")
        );
        assert_eq!(
            wa_snippet(&info(ChatMeta::default(), None, false), &contacts),
            None
        );
    }

    #[test]
    fn locations_contacts_and_voice_notes_get_their_own_snippets() {
        let contacts = ContactIndex::new(vec![]);
        let one = |meta, body: MessageBody| {
            let mut l = last(false, None, "", meta);
            l.body = body;
            info(ChatMeta::default(), Some(l), false)
        };
        let location = MessageMeta {
            location: Some(rovsti_wa_proto::Location {
                latitude: 41.0,
                longitude: 29.0,
                name: Some("Kadıköy".into()),
                ..Default::default()
            }),
            ..MessageMeta::default()
        };
        assert_eq!(
            wa_snippet(&one(location, MessageBody::Unsupported), &contacts).as_deref(),
            Some("Location · Kadıköy")
        );
        let card = MessageMeta {
            contact: Some(rovsti_wa_proto::SharedContact {
                display_name: "Ada".into(),
                vcard: String::new(),
            }),
            ..MessageMeta::default()
        };
        assert_eq!(
            wa_snippet(&one(card, MessageBody::Unsupported), &contacts).as_deref(),
            Some("Contact · Ada")
        );
        let voice = MessageMeta {
            media: Some(rovsti_wa_proto::MediaMeta {
                voice_note: true,
                seconds: Some(75),
                ..Default::default()
            }),
            ..MessageMeta::default()
        };
        let audio = MessageBody::Media {
            kind: rovsti_wa_proto::MediaKind::Audio,
            caption: None,
            mime: "audio/ogg".into(),
            size: 1,
        };
        assert_eq!(
            wa_snippet(&one(voice, audio), &contacts).as_deref(),
            Some("Voice message (1:15)")
        );
        assert_eq!(duration_label(3_725), "1:02:05");
    }

    #[test]
    fn live_messages_and_meta_events_update_the_preview() {
        let jid = "905321234567@s.whatsapp.net";
        let mut infos = HashMap::new();
        note_last(&mut infos, &wa_message(jid, "a", false, 10));
        note_last(&mut infos, &wa_message(jid, "old", false, 5));
        assert_eq!(infos[jid].last.as_ref().expect("a preview").id, "a");
        let meta = MessageMeta {
            revoked: true,
            ..MessageMeta::default()
        };
        assert!(note_meta(
            &mut infos,
            jid,
            "a",
            Some(&MessageBody::Unsupported),
            &meta
        ));
        assert!(!note_meta(
            &mut infos,
            jid,
            "a",
            Some(&MessageBody::Unsupported),
            &meta
        ));
        assert!(!note_meta(&mut infos, jid, "other", None, &meta));
        assert!(infos[jid].last.as_ref().expect("a preview").meta.revoked);
        // The same message again keeps its meta.
        note_last(&mut infos, &wa_message(jid, "a", false, 10));
        assert!(infos[jid].last.as_ref().expect("a preview").meta.revoked);
    }

    #[test]
    fn receipts_move_the_preview_ticks_forward_only() {
        let jid = "905321234567@s.whatsapp.net";
        let mut infos = HashMap::new();
        note_last(&mut infos, &wa_message(jid, "mine", true, 10));
        let tick = |infos: &HashMap<String, WaChatInfo>| preview_tick(&infos[jid]);
        // A message of mine without a stored status was at least sent.
        assert_eq!(tick(&infos), Some(Tick::Sent));
        let ids = ["mine".to_owned()];
        assert!(note_receipt(&mut infos, jid, &ids, ReceiptKind::Delivered));
        assert_eq!(tick(&infos), Some(Tick::Delivered));
        assert!(note_receipt(&mut infos, jid, &ids, ReceiptKind::Read));
        assert_eq!(tick(&infos), Some(Tick::Read));
        // A late delivered receipt does not take the blue ticks away.
        assert!(!note_receipt(&mut infos, jid, &ids, ReceiptKind::Delivered));
        assert_eq!(tick(&infos), Some(Tick::Read));
        // Receipts for other messages and other chats change nothing.
        assert!(!note_receipt(
            &mut infos,
            jid,
            &["x".into()],
            ReceiptKind::Read
        ));
        assert!(!note_receipt(&mut infos, "y", &ids, ReceiptKind::Read));

        // Their newest message has no ticks, even when read.
        note_last(&mut infos, &wa_message(jid, "theirs", false, 20));
        assert!(!note_receipt(
            &mut infos,
            jid,
            &["theirs".into()],
            ReceiptKind::Read
        ));
        assert_eq!(tick(&infos), None);
    }

    #[test]
    fn chat_actions_apply_at_once() {
        let mut meta = ChatMeta::default();
        let mut unread = 3;
        apply_action(
            &mut meta,
            &mut unread,
            &ChatActionKind::Pin { pinned: true },
        );
        apply_action(
            &mut meta,
            &mut unread,
            &ChatActionKind::Archive { archived: true },
        );
        apply_action(
            &mut meta,
            &mut unread,
            &ChatActionKind::Mute { until_ms: None },
        );
        assert!(meta.pinned && meta.archived);
        assert_eq!(meta.muted_until_ms, Some(MUTED_FOREVER));
        apply_action(
            &mut meta,
            &mut unread,
            &ChatActionKind::Mute { until_ms: Some(99) },
        );
        assert_eq!(meta.muted_until_ms, Some(99));
        apply_action(&mut meta, &mut unread, &ChatActionKind::Unmute);
        assert_eq!(meta.muted_until_ms, None);
        apply_action(
            &mut meta,
            &mut unread,
            &ChatActionKind::MarkRead { read: false },
        );
        assert!(meta.marked_unread);
        assert_eq!(unread, 3, "marking unread keeps the count");
        apply_action(
            &mut meta,
            &mut unread,
            &ChatActionKind::MarkRead { read: true },
        );
        assert!(!meta.marked_unread);
        assert_eq!(unread, 0);
    }

    #[test]
    fn a_contact_row_finds_its_photo() {
        let mut ayse = contact(7, "Ayşe", "+905321234567");
        ayse.photo_uri = Some("content://photo/7".into());
        let view = build_with(
            &[sms(1, &["+905321234567"], "hi", MIN, 0)],
            &[],
            vec![ayse],
            &rules(),
            &BlockSet::default(),
        );
        assert_eq!(
            view.rows[0].avatar.photo_uri.as_deref(),
            Some("content://photo/7")
        );
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
