//! One WhatsApp conversation: history from the gateway, live events, and a
//! text composer. The merge logic lives in `rovsti_wa_proto::timeline`; this
//! module is the thin GPUI shell around it plus a few pure text helpers.

use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use gpui::TextInputAction;
use gpui::TextInputPurpose;
use gpui::{
    AnyElement, Context, Entity, EventEmitter, Image, ImageFormat, InteractiveElement, IntoElement,
    ListAlignment, ObjectFit, ParentElement, Render, SharedString, StatefulInteractiveElement,
    Styled, StyledImage, Window, div, img, list, prelude::*, px,
};
use rovsti_core::{ThreadId, recents::LocaleTime, wire::DocumentOp};
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, glass_pill},
    current_theme,
    field::{TextField, field_box},
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, radius, typography},
};
use rovsti_wa_client::Gateway;
pub(crate) use rovsti_wa_proto::presence::{civil_from_days, clock, day_label, presence_label};
use rovsti_wa_ui::{WaAvatars, display_name, photo_avatar};

use crate::{
    backend::{Backend, BackendEvent},
    inbox::duration_label,
    keyed_list::{KeyedList, key_of},
    photos::ContactPhotos,
    schedule_ui::{self, StripItem, StripKey},
    ticks::{Tick, read_color, ticks},
};
use rovsti_wa_proto::{
    ChatState, DeleteForMe, EditMessage, GatewayEvent, Item, ItemState, Location, MarkRead,
    MediaKind, MediaMeta, MediaOptions, MessageBody, MessageMeta, Quoted, RevokeMessage,
    SendReaction, SendText, Sent, SharedContact, StarMessage, Timeline, TypingState,
    TypingThrottle, WaMessage, check_size, classify, day_breaks, jid_user, meta::body_preview,
    reaction_chips, read_requests,
};

/// The text "Copy" puts on the clipboard: the text or a media caption. Nothing
/// for a deleted message, media without a caption, or a location/contact card.
fn copy_text_of(body: &MessageBody, meta: Option<&MessageMeta>) -> Option<String> {
    if let Some(meta) = meta
        && (meta.revoked || meta.location.is_some() || meta.contact.is_some())
    {
        return None;
    }
    let text = match body {
        MessageBody::Text(text) => text,
        MessageBody::Media { caption, .. } => caption.as_ref()?,
        MessageBody::Unsupported => return None,
    };
    let text = text.trim();
    (!text.is_empty()).then(|| text.to_owned())
}

/// Messages per history request.
const PAGE: u32 = 50;

/// The quick reactions offered by the long-press strip.
const QUICK_EMOJIS: [&str; 6] = ["👍", "❤️", "😂", "😮", "😢", "🙏"];

/// Downloaded images kept in memory.
const MEDIA_CACHE: usize = 16;
/// Media preview thumbnails kept in memory.
const THUMB_CACHE: usize = 48;
/// Thumbnails fetched at once.
const THUMB_PARALLEL: usize = 3;
/// WhatsApp lets a text or caption be edited this long after sending.
const EDIT_WINDOW_MS: i64 = 15 * 60_000;
/// A message jumped to from a quote glows this long.
const HIGHLIGHT_FOR: Duration = Duration::from_millis(1_600);

/// How long a "typing" event keeps the header line alive.
const TYPING_SHOW_MS: i64 = 6_000;

/// What a conversation tells the page that hosts it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ConversationEvent {
    /// The back button was tapped.
    Back,
    /// The header's channel switch was tapped: open the person's other thread.
    Switch(ThreadId),
    /// Long-press on Send: the text wants a time (the "Send later" sheet).
    Schedule(String),
    /// Cancel on a Scheduled strip row.
    CancelScheduled(StripKey),
    /// The header's bell: mute or unmute this chat's notifications.
    ToggleMute,
    /// The Copy pill of a message: the page puts the text on the clipboard.
    Copy(String),
}

impl EventEmitter<ConversationEvent> for Conversation {}

/// Who the chat is with, for the header avatar.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Peer {
    pub initials: SharedString,
    /// The matched contact's photo.
    pub photo_uri: Option<SharedString>,
    /// The chat's WhatsApp picture id, when the gateway told one.
    pub picture_id: Option<SharedString>,
}

/// Everything a conversation starts from.
pub struct Setup {
    pub chat: String,
    pub title: SharedString,
    pub is_group: bool,
    pub gateway: Gateway,
    pub locale: LocaleTime,
    pub backend: Entity<Backend>,
    pub avatars: Entity<WaAvatars>,
    pub photos: Entity<ContactPhotos>,
    pub peer: Peer,
    /// The window is in front of the user (see [`Conversation::set_active`]).
    pub active: bool,
}

/// The message the composer answers.
#[derive(Clone, Debug, PartialEq, Eq)]
struct ReplyTarget {
    id: String,
    author: SharedString,
    preview: SharedString,
}

/// The message the Delete menu is open for.
#[derive(Clone, Debug, PartialEq, Eq)]
struct DeleteTarget {
    id: String,
    /// "Delete for everyone" is offered (my own message).
    for_everyone: bool,
}

/// A picked file waiting in the chip above the composer.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Attached {
    path: String,
    name: String,
    /// The picker's MIME type, when it reported one.
    mime: Option<String>,
    kind: MediaKind,
}

/// Why a media send stopped before the gateway had it.
enum MediaError {
    /// The cached copy of the file is missing.
    Gone,
    TooLarge,
    Gateway(String),
}

/// A file read, classified and ready to upload.
struct Prepared {
    bytes: Vec<u8>,
    kind: MediaKind,
    mime: String,
}

/// One item of the timeline list after its "Load earlier" slot.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Entry {
    /// The local day (its midnight, in ms) that starts here.
    Day(i64),
    /// The timeline item at this index.
    Message(usize),
}

pub struct Conversation {
    chat: String,
    title: SharedString,
    is_group: bool,
    timeline: Timeline,
    composer: Entity<TextField>,
    gateway: Gateway,
    next_local: u64,
    loading: bool,
    /// Incoming ids already reported to the gateway as read.
    marked: HashSet<String>,
    /// The window is in front of the user: only then do incoming messages
    /// count as seen and get read receipts.
    active: bool,
    /// The last failed load, shown above the messages.
    error: Option<SharedString>,
    /// The virtualised, bottom-aligned timeline: item 0 is the "Load earlier"
    /// slot, then one item per [`Entry`].
    list: KeyedList,
    /// Day breaks and messages in timeline order, rebuilt when `rows_dirty`.
    entries: Vec<Entry>,
    /// The timeline, its media or the reaction strip changed since `entries`.
    rows_dirty: bool,
    /// "Now" for the frame being drawn (the list builds rows after `render`).
    frame_now: i64,
    /// Decoded images by message id, oldest first (at most [`MEDIA_CACHE`]).
    media_cache: Vec<(String, Arc<Image>)>,
    media_loading: HashSet<String>,
    media_failed: HashSet<String>,
    /// The message whose quick-reaction strip is open.
    strip: Option<String>,
    throttle: TypingThrottle,
    /// Composer text at the last observation, to tell edits from re-renders.
    last_text: String,
    /// The composer's edit counter at the last observation.
    seen_edits: u64,
    /// The peer is typing until this time.
    typing_until: Option<i64>,
    /// `(available, last_seen_ms)` of the peer in a 1:1 chat.
    presence: Option<(bool, Option<i64>)>,
    /// The phone's clock, from the backend.
    locale: LocaleTime,
    /// The same person's other thread, shown as a header pill.
    switch: Option<(ThreadId, crate::inbox::Badge)>,
    /// Waiting scheduled sends of this chat, set by the page.
    scheduled: Vec<StripItem>,
    muted: bool,
    backend: Entity<Backend>,
    /// The file picker is open and its answer is ours.
    awaiting_attachment: bool,
    attachment: Option<Attached>,
    /// Cached file of each pending or failed media send, by local id.
    pending_paths: HashMap<u64, String>,
    /// A line above the composer (attach and send problems).
    notice: Option<SharedString>,
    avatars: Entity<WaAvatars>,
    photos: Entity<ContactPhotos>,
    peer: Peer,
    /// Preview thumbnails by message id, oldest first (at most [`THUMB_CACHE`]).
    thumbs: Vec<(String, Arc<Image>)>,
    thumbs_loading: HashSet<String>,
    thumbs_failed: HashSet<String>,
    /// The composer answers this message.
    reply_to: Option<ReplyTarget>,
    /// The composer edits this message of mine.
    editing: Option<String>,
    /// Quotes of replies just sent, until the gateway's meta has them; keyed
    /// by message id (`pending-N`, then the real id).
    local_quotes: HashMap<String, Quoted>,
    /// The quoted id of each pending or failed reply, by local id (a retry
    /// answers the same message).
    pending_quotes: HashMap<u64, String>,
    /// Stars set here, until the gateway's `message_meta` confirms them.
    star_override: HashMap<String, bool>,
    /// Deleted for me here, until the gateway's `message_deleted` drops them.
    hidden: HashSet<String>,
    /// The Delete menu.
    delete_confirm: Option<DeleteTarget>,
    /// The message a quote tap jumped to (it glows for a moment).
    highlight: Option<String>,
    _subscriptions: Vec<gpui::Subscription>,
}

impl Conversation {
    pub fn new(setup: Setup, cx: &mut Context<Self>) -> Self {
        let Setup {
            chat,
            title,
            is_group,
            gateway,
            locale,
            backend,
            avatars,
            photos,
            peer,
            active,
        } = setup;
        let composer =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Send, cx));
        // Typing re-renders the composer box.
        cx.observe(&composer, |this, _, cx| this.on_composer_changed(cx))
            .detach();
        let subscriptions = vec![
            cx.subscribe(&backend, |this, _, event, cx| {
                if let BackendEvent::Document(result) = event
                    && result.op == DocumentOp::Attachment
                {
                    this.on_attachment(
                        result.ok,
                        result.path.clone(),
                        result.name.clone(),
                        result.mime.clone(),
                        cx,
                    );
                }
            }),
            // A picture arrived: the header and the bubbles repaint.
            cx.observe(&avatars, |_, _, cx| cx.notify()),
            cx.observe(&photos, |_, _, cx| cx.notify()),
        ];
        let mut this = Self {
            timeline: Timeline::new(chat.clone()),
            chat,
            title,
            is_group,
            composer,
            gateway,
            next_local: 1,
            loading: false,
            marked: HashSet::new(),
            active,
            error: None,
            list: KeyedList::new(ListAlignment::Bottom, 1),
            entries: Vec::new(),
            rows_dirty: true,
            frame_now: 0,
            media_cache: Vec::new(),
            media_loading: HashSet::new(),
            media_failed: HashSet::new(),
            strip: None,
            throttle: TypingThrottle::new(),
            last_text: String::new(),
            seen_edits: 0,
            typing_until: None,
            presence: None,
            locale,
            switch: None,
            scheduled: Vec::new(),
            muted: false,
            backend,
            awaiting_attachment: false,
            attachment: None,
            pending_paths: HashMap::new(),
            notice: None,
            avatars,
            photos,
            peer,
            thumbs: Vec::new(),
            thumbs_loading: HashSet::new(),
            thumbs_failed: HashSet::new(),
            reply_to: None,
            editing: None,
            local_quotes: HashMap::new(),
            pending_quotes: HashMap::new(),
            star_override: HashMap::new(),
            hidden: HashSet::new(),
            delete_confirm: None,
            highlight: None,
            _subscriptions: subscriptions,
        };
        this.load(None, cx);
        if !this.is_group {
            this.subscribe_presence(cx);
        }
        this
    }

    pub fn set_scheduled(&mut self, scheduled: Vec<StripItem>, cx: &mut Context<Self>) {
        if self.scheduled != scheduled {
            self.scheduled = scheduled;
            cx.notify();
        }
    }

    pub fn set_muted(&mut self, muted: bool, cx: &mut Context<Self>) {
        if self.muted != muted {
            self.muted = muted;
            cx.notify();
        }
    }

    /// The text was scheduled: the composer is done with it.
    pub fn clear_draft(&mut self, cx: &mut Context<Self>) {
        self.composer.update(cx, |field, cx| field.clear(cx));
        self.last_text.clear();
    }

    /// System Back inside the page: closes the Delete menu, a message's menu,
    /// or the edit or reply bar; `false` when there is none of them.
    pub fn go_back(&mut self, cx: &mut Context<Self>) -> bool {
        if self.delete_confirm.is_some() {
            self.delete_confirm = None;
        } else if self.strip.is_some() {
            self.strip = None;
            self.rows_dirty = true;
        } else if self.editing.is_some() || self.reply_to.is_some() {
            self.cancel_compose_context(cx);
        } else {
            return false;
        }
        cx.notify();
        true
    }

    /// The ✕ of the reply or edit bar.
    fn cancel_compose_context(&mut self, cx: &mut Context<Self>) {
        if self.editing.take().is_some() {
            // The edited text was never sent: the composer empties again.
            self.composer.update(cx, |field, cx| field.clear(cx));
            self.last_text.clear();
        }
        self.reply_to = None;
        cx.notify();
    }

    /// Reply on a message's menu: the composer answers it.
    fn begin_reply(&mut self, id: &str, cx: &mut Context<Self>) {
        let Some(item) = self.timeline.items().iter().find(|i| i.message.id == id) else {
            return;
        };
        let message = &item.message;
        let meta = self.timeline.meta(id);
        let author = self.author(message.from_me, message.sender.as_deref(), meta, cx);
        let preview = preview_of(&message.body, meta);
        self.reply_to = Some(ReplyTarget {
            id: id.to_owned(),
            author: author.into(),
            preview: preview.into(),
        });
        if self.editing.take().is_some() {
            self.composer.update(cx, |field, cx| field.clear(cx));
        }
        self.strip = None;
        self.rows_dirty = true;
        cx.notify();
    }

    /// Edit on my message's menu: its text goes into the composer.
    fn begin_edit(&mut self, id: &str, cx: &mut Context<Self>) {
        let Some(item) = self.timeline.items().iter().find(|i| i.message.id == id) else {
            return;
        };
        let text = match &item.message.body {
            MessageBody::Text(text) => text.clone(),
            MessageBody::Media { caption, .. } => caption.clone().unwrap_or_default(),
            MessageBody::Unsupported => return,
        };
        self.composer.update(cx, |field, cx| field.set(&text, cx));
        self.last_text = text;
        self.editing = Some(id.to_owned());
        self.reply_to = None;
        self.strip = None;
        self.rows_dirty = true;
        cx.notify();
    }

    /// Sends the edited text; the gateway's `message_meta` brings it back.
    fn send_edit(&mut self, id: String, text: String, cx: &mut Context<Self>) {
        self.editing = None;
        self.composer.update(cx, |field, cx| field.clear(cx));
        self.last_text.clear();
        let request = EditMessage {
            chat: self.chat.clone(),
            message_id: id,
            text,
        };
        let gateway = self.gateway.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.edit_message(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("editing a WhatsApp message failed: {error}");
                this.update(cx, |this, cx| {
                    this.notice = Some("Couldn't edit the message".into());
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    /// Star or Unstar on a message's menu; shown at once.
    fn set_starred(&mut self, id: &str, starred: bool, cx: &mut Context<Self>) {
        self.strip = None;
        self.star_override.insert(id.to_owned(), starred);
        self.rows_dirty = true;
        let request = StarMessage {
            chat: self.chat.clone(),
            message_id: id.to_owned(),
            starred,
        };
        let gateway = self.gateway.clone();
        let wanted = id.to_owned();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.star_message(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("starring a WhatsApp message failed: {error}");
                this.update(cx, |this, cx| {
                    this.star_override.remove(&wanted);
                    this.rows_dirty = true;
                    this.notice = Some("Couldn't star the message".into());
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    /// Delete for me: the bubble goes at once, the gateway's
    /// `message_deleted` confirms it.
    fn delete_for_me(&mut self, id: &str, cx: &mut Context<Self>) {
        self.delete_confirm = None;
        self.hidden.insert(id.to_owned());
        self.forget_targets(id);
        self.rows_dirty = true;
        let request = DeleteForMe {
            chat: self.chat.clone(),
            message_id: id.to_owned(),
        };
        let gateway = self.gateway.clone();
        let wanted = id.to_owned();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.delete_for_me(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("deleting a WhatsApp message failed: {error}");
                this.update(cx, |this, cx| {
                    this.hidden.remove(&wanted);
                    this.rows_dirty = true;
                    this.notice = Some("Couldn't delete the message".into());
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    /// Delete for everyone; the gateway's `message_meta` (revoked) follows.
    fn revoke(&mut self, id: &str, cx: &mut Context<Self>) {
        self.delete_confirm = None;
        self.forget_targets(id);
        let request = RevokeMessage {
            chat: self.chat.clone(),
            message_id: id.to_owned(),
            sender: None,
        };
        let gateway = self.gateway.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.revoke_message(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("deleting a WhatsApp message for everyone failed: {error}");
                this.update(cx, |this, cx| {
                    this.notice = Some("Couldn't delete the message for everyone".into());
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    /// A message went away: it can no longer be answered or edited.
    fn forget_targets(&mut self, id: &str) {
        if self.reply_to.as_ref().is_some_and(|r| r.id == id) {
            self.reply_to = None;
        }
        if self.editing.as_deref() == Some(id) {
            self.editing = None;
        }
        if self.strip.as_deref() == Some(id) {
            self.strip = None;
        }
    }

    /// A tap on a quote: shows the quoted message and lets it glow.
    fn jump_to(&mut self, id: &str, cx: &mut Context<Self>) {
        if self.rows_dirty {
            self.sync_rows();
        }
        let items = self.timeline.items();
        let found = self.entries.iter().position(|entry| {
            matches!(entry, Entry::Message(at) if items.get(*at).is_some_and(|i| i.message.id == id))
        });
        let Some(position) = found else {
            self.notice = Some(if self.timeline.has_older() {
                "That message is further back: load earlier messages".into()
            } else {
                "That message isn't here any more".into()
            });
            cx.notify();
            return;
        };
        // Item 0 of the list is the "Load earlier" slot.
        self.list.reveal(position + 1);
        self.highlight = Some(id.to_owned());
        let wanted = id.to_owned();
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(HIGHLIGHT_FOR).await;
            this.update(cx, |this, cx| {
                if this.highlight.as_deref() == Some(wanted.as_str()) {
                    this.highlight = None;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn cached_thumb(&self, id: &str) -> Option<Arc<Image>> {
        self.thumbs
            .iter()
            .find(|(cached, _)| cached == id)
            .map(|(_, image)| image.clone())
    }

    /// Fetches the small preview that came with a media message, for a
    /// bubble on screen.
    fn want_thumbnail(&mut self, id: &str, cx: &mut Context<Self>) {
        if self.thumbs_loading.contains(id)
            || self.thumbs_failed.contains(id)
            || self.thumbs_loading.len() >= THUMB_PARALLEL
            || self.cached_thumb(id).is_some()
        {
            return;
        }
        self.thumbs_loading.insert(id.to_owned());
        let gateway = self.gateway.clone();
        let wanted = id.to_owned();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.thumbnail(&wanted) });
        let id = id.to_owned();
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.thumbs_loading.remove(&id);
                match result.ok().and_then(|bytes| {
                    sniff_format(&bytes).map(|format| Arc::new(Image::from_bytes(format, bytes)))
                }) {
                    Some(image) => {
                        this.thumbs.retain(|(cached, _)| *cached != id);
                        this.thumbs.push((id, image));
                        if this.thumbs.len() > THUMB_CACHE {
                            this.thumbs.remove(0);
                        }
                    }
                    None => {
                        this.thumbs_failed.insert(id);
                    }
                }
                this.rows_dirty = true;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Long-press (or right-click) on Send. Only a typed text can wait.
    fn request_schedule(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().trim().to_owned();
        if !text.is_empty() {
            cx.emit(ConversationEvent::Schedule(text));
        }
    }

    pub fn set_locale(&mut self, locale: LocaleTime, cx: &mut Context<Self>) {
        if self.locale != locale {
            self.locale = locale;
            self.rows_dirty = true;
            cx.notify();
        }
    }

    /// The window came to the front or left it (app backgrounded, screen
    /// off, another app or the shade on top). Coming back reads what
    /// arrived meanwhile.
    pub fn set_active(&mut self, active: bool, cx: &mut Context<Self>) {
        if self.active == active {
            return;
        }
        self.active = active;
        if active {
            self.mark_unread(cx);
        }
    }

    pub fn set_switch(
        &mut self,
        switch: Option<(ThreadId, crate::inbox::Badge)>,
        cx: &mut Context<Self>,
    ) {
        if self.switch != switch {
            self.switch = switch;
            cx.notify();
        }
    }

    fn subscribe_presence(&self, cx: &mut Context<Self>) {
        let gateway = self.gateway.clone();
        let chat = self.chat.clone();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = gateway.subscribe_presence(&chat) {
                    log::warn!("subscribing to WhatsApp presence failed: {error}");
                }
            })
            .detach();
    }

    fn send_chat_state(&self, state: TypingState, cx: &mut Context<Self>) {
        let gateway = self.gateway.clone();
        let request = ChatState {
            chat: self.chat.clone(),
            state,
        };
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = gateway.chat_state(&request) {
                    log::warn!("sending a WhatsApp chat state failed: {error}");
                }
            })
            .detach();
    }

    /// Composer text changed: tells the peer we type (throttled) or paused.
    fn on_composer_changed(&mut self, cx: &mut Context<Self>) {
        // Focus and caret changes notify too: only an edit reads the text.
        let edits = self.composer.read(cx).edits;
        if edits == self.seen_edits {
            cx.notify();
            return;
        }
        self.seen_edits = edits;
        let text = self.composer.read(cx).text().to_owned();
        if text != self.last_text {
            let empty = text.trim().is_empty();
            self.last_text = text;
            if empty {
                if self.throttle.on_send() {
                    self.send_chat_state(TypingState::Paused, cx);
                }
            } else if self.throttle.on_edit(now_ms()) {
                self.send_chat_state(TypingState::Typing, cx);
            }
        }
        cx.notify();
    }

    /// Sends our reaction (`""` removes it) and shows it right away.
    fn react(
        &mut self,
        message_id: String,
        from_me: bool,
        sender: Option<String>,
        emoji: String,
        cx: &mut Context<Self>,
    ) {
        self.strip = None;
        self.timeline.set_own_reaction(&message_id, &emoji);
        self.rows_dirty = true;
        let request = SendReaction {
            chat: self.chat.clone(),
            message_id,
            from_me,
            sender,
            emoji,
        };
        let gateway = self.gateway.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.react(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("sending a WhatsApp reaction failed: {error}");
                this.update(cx, |this, cx| {
                    this.error = Some(format!("Couldn't send the reaction: {error}").into());
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    fn cached_image(&self, id: &str) -> Option<Arc<Image>> {
        self.media_cache
            .iter()
            .find(|(cached, _)| cached == id)
            .map(|(_, image)| image.clone())
    }

    /// Keeps a decoded picture, dropping the oldest beyond [`MEDIA_CACHE`].
    fn cache_image(&mut self, id: String, image: Arc<Image>) {
        self.media_cache.retain(|(cached, _)| *cached != id);
        self.media_cache.push((id, image));
        self.rows_dirty = true;
        if self.media_cache.len() > MEDIA_CACHE {
            self.media_cache.remove(0);
        }
    }

    /// Downloads the media of an image or sticker message off the UI thread.
    fn fetch_media(&mut self, id: String, mime: String, cx: &mut Context<Self>) {
        if self.media_loading.contains(&id) || self.cached_image(&id).is_some() {
            return;
        }
        let Some(format) = image_format(&mime) else {
            self.media_failed.insert(id);
            self.rows_dirty = true;
            cx.notify();
            return;
        };
        self.media_failed.remove(&id);
        self.media_loading.insert(id.clone());
        self.rows_dirty = true;
        let gateway = self.gateway.clone();
        let wanted = id.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.media(&wanted) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.media_loading.remove(&id);
                this.rows_dirty = true;
                match result {
                    Ok(bytes) => {
                        this.cache_image(id, Arc::new(Image::from_bytes(format, bytes)));
                    }
                    Err(error) => {
                        log::warn!("downloading WhatsApp media failed: {error}");
                        this.media_failed.insert(id);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Fetches one page (the newest, or the one before `before_ms`) off the
    /// UI thread.
    fn load(&mut self, before_ms: Option<i64>, cx: &mut Context<Self>) {
        if self.loading {
            return;
        }
        self.loading = true;
        let gateway = self.gateway.clone();
        let chat = self.chat.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.messages_with_meta(&chat, before_ms, PAGE) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.loading = false;
                match result {
                    Ok(page) => {
                        this.error = None;
                        this.timeline.apply_rich_page(page, PAGE);
                        this.sync_rows();
                        if before_ms.is_none() {
                            this.list.scroll_to_end();
                        }
                        this.mark_unread(cx);
                    }
                    Err(error) => {
                        log::warn!("loading WhatsApp messages failed: {error}");
                        this.error = Some(format!("Couldn't load messages: {error}").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Tells the gateway about incoming messages that were not reported yet,
    /// while the user can see them: nothing is "seen" from the background
    /// ([`Self::set_active`] catches up). In a group there is one receipt
    /// per author, as WhatsApp wants.
    fn mark_unread(&mut self, cx: &mut Context<Self>) {
        for request in reads_due(&self.timeline, &self.marked, self.active) {
            self.marked.extend(request.ids.iter().cloned());
            self.send_read(request, cx);
        }
    }

    fn send_read(&self, request: MarkRead, cx: &mut Context<Self>) {
        let gateway = self.gateway.clone();
        let task = cx.background_executor().spawn(async move {
            let result = gateway.mark_read(&request);
            (result, request.ids)
        });
        cx.spawn(async move |this, cx| {
            let (result, ids) = task.await;
            if let Err(error) = result {
                log::warn!("marking WhatsApp messages read failed: {error}");
                // Forget them so the next event tries again.
                this.update(cx, |this, _| {
                    for id in &ids {
                        this.marked.remove(id);
                    }
                })
                .ok();
            }
        })
        .detach();
    }

    /// A gateway event the host observed. Events of other chats are ignored
    /// by the timeline.
    pub fn on_event(&mut self, event: &GatewayEvent, cx: &mut Context<Self>) {
        if matches!(event, GatewayEvent::Resync) {
            self.load(None, cx);
            return;
        }
        match event {
            GatewayEvent::Typing { chat, state, .. } if *chat == self.chat => {
                if *state == TypingState::Paused {
                    self.typing_until = None;
                } else {
                    self.typing_until = Some(now_ms() + TYPING_SHOW_MS);
                    // Repaint once the line has expired.
                    cx.spawn(async move |this, cx| {
                        cx.background_executor()
                            .timer(Duration::from_millis(TYPING_SHOW_MS as u64 + 100))
                            .await;
                        this.update(cx, |_, cx| cx.notify()).ok();
                    })
                    .detach();
                }
                cx.notify();
                return;
            }
            GatewayEvent::Presence {
                jid,
                available,
                last_seen_ms,
            } if !self.is_group && jid_user(jid) == jid_user(&self.chat) => {
                self.presence = Some((*available, *last_seen_ms));
                cx.notify();
                return;
            }
            GatewayEvent::Message(m) if m.chat == self.chat && !m.from_me => {
                self.typing_until = None;
            }
            GatewayEvent::MessageMeta { chat, id, meta, .. } if *chat == self.chat => {
                // The gateway has the star now; a deleted one can't be answered.
                self.star_override.remove(id);
                if meta.revoked {
                    self.forget_targets(id);
                }
                if meta.quoted.is_some() {
                    self.local_quotes.remove(id);
                }
            }
            GatewayEvent::MessageDeleted { chat, id } if *chat == self.chat => {
                self.hidden.remove(id);
                self.forget_targets(id);
            }
            GatewayEvent::ChatCleared { jid } | GatewayEvent::ChatDeleted { jid }
                if *jid == self.chat =>
            {
                self.reply_to = None;
                self.editing = None;
                self.strip = None;
                self.delete_confirm = None;
                self.hidden.clear();
                self.rows_dirty = true;
            }
            _ => {}
        }
        if self.timeline.apply_event(event) {
            // A reader at the bottom follows the new message (the list is
            // bottom-aligned); one reading older messages keeps their place.
            self.rows_dirty = true;
            self.mark_unread(cx);
            cx.notify();
        }
    }

    /// "typing…", "online" or "last seen …" under the title.
    fn subtitle(&self, now: i64) -> Option<String> {
        if self.typing_until.is_some_and(|until| now < until) {
            return Some("typing…".to_owned());
        }
        if self.is_group {
            return None;
        }
        let (available, last_seen) = self.presence?;
        presence_label(available, last_seen, now, self.locale.tz_offset_s)
    }

    fn send(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().trim().to_owned();
        if let Some(id) = self.editing.clone() {
            if !text.is_empty() {
                self.send_edit(id, text, cx);
            }
            return;
        }
        match send_plan(&text, self.attachment.is_some()) {
            Plan::Nothing => {}
            Plan::Text => {
                self.composer.update(cx, |field, cx| field.clear(cx));
                self.last_text.clear();
                self.send_text(text, cx);
            }
            Plan::Media { caption } => {
                let Some(attached) = self.attachment.take() else {
                    return;
                };
                self.composer.update(cx, |field, cx| field.clear(cx));
                self.last_text.clear();
                self.send_media(attached, caption, cx);
            }
        }
    }

    /// The paperclip: opens the system file picker.
    fn pick_attachment(&mut self, cx: &mut Context<Self>) {
        self.awaiting_attachment = true;
        self.notice = None;
        self.backend
            .update(cx, |backend, cx| backend.pick_attachment(cx));
        cx.notify();
    }

    fn on_attachment(
        &mut self,
        ok: bool,
        path: Option<String>,
        name: Option<String>,
        mime: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if !std::mem::take(&mut self.awaiting_attachment) {
            return;
        }
        match path.filter(|_| ok) {
            Some(path) => {
                let name = name
                    .filter(|n| !n.is_empty())
                    .unwrap_or_else(|| crate::sms_conversation::attachment_label(&path));
                let (kind, _) = classify(mime.as_deref(), &name, &[]);
                self.attachment = Some(Attached {
                    path,
                    name,
                    mime,
                    kind,
                });
                self.notice = None;
            }
            None => {
                let words = if self.backend.read(cx).is_preview() {
                    "Attachments need an Android phone"
                } else {
                    "Couldn't attach that file"
                };
                self.notice = Some(words.into());
            }
        }
        cx.notify();
    }

    /// Shows the bubble at once, then uploads the file off the UI thread.
    fn send_media(&mut self, attached: Attached, caption: Option<String>, cx: &mut Context<Self>) {
        if self.throttle.on_send() {
            self.send_chat_state(TypingState::Paused, cx);
        }
        let local_id = self.next_local;
        self.next_local += 1;
        self.take_reply(local_id);
        let (kind, mime) = classify(attached.mime.as_deref(), &attached.name, &[]);
        let size = std::fs::metadata(&attached.path).map_or(0, |m| m.len());
        self.timeline.push_pending_media(
            local_id,
            kind,
            mime.clone(),
            caption.clone(),
            size,
            now_ms(),
        );
        self.pending_paths.insert(local_id, attached.path.clone());
        self.sync_rows();
        self.list.scroll_to_end();
        self.dispatch_media(
            local_id,
            attached.path,
            attached.name,
            Some(mime),
            caption,
            cx,
        );
        cx.notify();
    }

    /// Reads, checks and classifies the file, then sends it; settles the bubble.
    fn dispatch_media(
        &mut self,
        local_id: u64,
        path: String,
        name: String,
        mime: Option<String>,
        caption: Option<String>,
        cx: &mut Context<Self>,
    ) {
        let options = MediaOptions {
            quoted_id: self.pending_quotes.get(&local_id).cloned(),
            file_name: Some(name.clone()).filter(|n| !n.is_empty()),
            ..MediaOptions::default()
        };
        let read = cx.background_executor().spawn(async move {
            let bytes = std::fs::read(&path).map_err(|_| MediaError::Gone)?;
            check_size(bytes.len() as u64).map_err(|_| MediaError::TooLarge)?;
            let head = &bytes[..bytes.len().min(16)];
            let (kind, mime) = classify(mime.as_deref(), &name, head);
            Ok::<_, MediaError>(Prepared { bytes, kind, mime })
        });
        let gateway = self.gateway.clone();
        let chat = self.chat.clone();
        cx.spawn(async move |this, cx| {
            let prepared = match read.await {
                Ok(prepared) => prepared,
                Err(error) => {
                    this.update(cx, |this, cx| this.media_failed_send(local_id, error, cx))
                        .ok();
                    return;
                }
            };
            // The picture shows under the pending id while it uploads.
            let shown = this
                .update(cx, |this, cx| {
                    let format = (prepared.kind == MediaKind::Image)
                        .then(|| image_format(&prepared.mime))
                        .flatten();
                    let shown = format
                        .map(|format| Arc::new(Image::from_bytes(format, prepared.bytes.clone())));
                    if let Some(image) = &shown {
                        this.cache_image(format!("pending-{local_id}"), image.clone());
                        cx.notify();
                    }
                    shown
                })
                .ok()
                .flatten();
            let Prepared { bytes, kind, mime } = prepared;
            // Only documents show a file name.
            let options = MediaOptions {
                file_name: options.file_name.filter(|_| kind == MediaKind::Document),
                ..options
            };
            let send = cx.background_executor().spawn(async move {
                gateway
                    .send_media_with(&chat, kind, &mime, caption.as_deref(), &options, bytes)
                    .map_err(|error| MediaError::Gateway(error.to_string()))
            });
            let result = send.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(sent) => this.media_sent(local_id, &sent, shown),
                    Err(error) => this.media_failed_send(local_id, error, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn media_sent(&mut self, local_id: u64, sent: &Sent, shown: Option<Arc<Image>>) {
        self.timeline.confirm(local_id, sent);
        self.settle_reply(local_id, sent);
        self.rows_dirty = true;
        // The cache copy of the picked file has done its job.
        if let Some(path) = self.pending_paths.remove(&local_id)
            && let Err(error) = std::fs::remove_file(&path)
        {
            log::debug!("removing the sent attachment copy failed: {error}");
        }
        let pending = format!("pending-{local_id}");
        self.media_cache.retain(|(cached, _)| *cached != pending);
        if let Some(image) = shown {
            self.cache_image(sent.message_id.clone(), image);
        }
    }

    fn media_failed_send(&mut self, local_id: u64, error: MediaError, cx: &mut Context<Self>) {
        self.timeline.fail(local_id);
        self.rows_dirty = true;
        self.notice = Some(match error {
            MediaError::Gone => "The file is gone; attach it again".into(),
            MediaError::TooLarge => format!(
                "That file is over {} MB",
                rovsti_wa_proto::MAX_SEND_BYTES / (1024 * 1024)
            )
            .into(),
            MediaError::Gateway(message) => {
                log::warn!("sending WhatsApp media failed: {message}");
                "Couldn't send the file".into()
            }
        });
        cx.notify();
    }

    /// Sends `text` now: the composer's, or one typed into a notification.
    pub fn send_text(&mut self, text: String, cx: &mut Context<Self>) {
        let text = text.trim().to_owned();
        if text.is_empty() {
            return;
        }
        if self.throttle.on_send() {
            self.send_chat_state(TypingState::Paused, cx);
        }
        let local_id = self.next_local;
        self.next_local += 1;
        self.take_reply(local_id);
        self.timeline.push_pending(local_id, text.clone(), now_ms());
        self.sync_rows();
        self.list.scroll_to_end();
        self.dispatch(local_id, text, cx);
        cx.notify();
    }

    /// The reply bar's message becomes the quote of send `local_id`.
    fn take_reply(&mut self, local_id: u64) {
        let Some(reply) = self.reply_to.take() else {
            return;
        };
        self.local_quotes.insert(
            format!("pending-{local_id}"),
            Quoted {
                id: reply.id.clone(),
                sender: None,
                preview: Some(reply.preview.to_string()),
            },
        );
        self.pending_quotes.insert(local_id, reply.id);
    }

    /// A reply went out: its quote moves to the real id.
    fn settle_reply(&mut self, local_id: u64, sent: &Sent) {
        self.pending_quotes.remove(&local_id);
        if let Some(quote) = self.local_quotes.remove(&format!("pending-{local_id}"))
            && self.quote_of(&sent.message_id).is_none()
        {
            self.local_quotes.insert(sent.message_id.clone(), quote);
        }
    }

    fn retry(&mut self, local_id: u64, cx: &mut Context<Self>) {
        self.rows_dirty = true;
        match self.timeline.restart_body(local_id) {
            Some(MessageBody::Text(text)) => self.dispatch(local_id, text, cx),
            Some(MessageBody::Media { caption, mime, .. }) => {
                match self.pending_paths.get(&local_id).cloned() {
                    Some(path) => {
                        let name = crate::sms_conversation::attachment_label(&path);
                        self.notice = None;
                        self.dispatch_media(local_id, path, name, Some(mime), caption, cx);
                    }
                    None => self.media_failed_send(local_id, MediaError::Gone, cx),
                }
            }
            Some(MessageBody::Unsupported) => self.timeline.fail(local_id),
            None => return,
        }
        cx.notify();
    }

    /// Sends one pending text off the UI thread and settles its bubble.
    fn dispatch(&mut self, local_id: u64, text: String, cx: &mut Context<Self>) {
        let gateway = self.gateway.clone();
        let request = SendText {
            chat: self.chat.clone(),
            text,
            quoted_id: self.pending_quotes.get(&local_id).cloned(),
        };
        let task = cx
            .background_executor()
            .spawn(async move { gateway.send_text(&request) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.rows_dirty = true;
                match result {
                    Ok(sent) => {
                        this.timeline.confirm(local_id, &sent);
                        this.settle_reply(local_id, &sent);
                    }
                    Err(error) => {
                        log::warn!("sending a WhatsApp message failed: {error}");
                        this.timeline.fail(local_id);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Rebuilds the day breaks and message entries and tells the list which
    /// items changed. Runs only after a change, never per frame.
    fn sync_rows(&mut self) {
        self.rows_dirty = false;
        let items = self.timeline.items();
        let breaks = day_breaks(items, self.locale.tz_offset_s);
        let mut entries = Vec::with_capacity(items.len() + breaks.len());
        let mut next_break = breaks.iter().peekable();
        for (index, item) in items.iter().enumerate() {
            let day = next_break
                .next_if(|(at, _)| *at == index)
                .map(|&(_, day_ms)| day_ms);
            // Deleted for me here: gone before the gateway confirms it.
            if self.hidden.contains(&item.message.id) {
                continue;
            }
            if let Some(day_ms) = day {
                entries.push(Entry::Day(day_ms));
            }
            entries.push(Entry::Message(index));
        }
        // The "Load earlier" slot keeps one key: it is measured while on screen.
        let keys = std::iter::once(key_of(0u8))
            .chain(entries.iter().map(|entry| match *entry {
                Entry::Day(day_ms) => key_of((1u8, day_ms)),
                Entry::Message(index) => self.message_key(&items[index]),
            }))
            .collect();
        self.list.sync(keys);
        self.entries = entries;
    }

    /// The key of a bubble: everything that changes what it shows or how tall it is.
    fn message_key(&self, item: &Item) -> u64 {
        let message = &item.message;
        let state = match item.state {
            ItemState::Sent => (0u8, 0),
            ItemState::Delivered => (1, 0),
            ItemState::Read => (2, 0),
            ItemState::Pending { local_id } => (3, local_id),
            ItemState::Failed { local_id } => (4, local_id),
        };
        let body = match &message.body {
            MessageBody::Text(text) => (0u8, Some(text.as_str()), 0),
            MessageBody::Media { caption, size, .. } => (1, caption.as_deref(), *size),
            MessageBody::Unsupported => (2, None, 0),
        };
        let media = (
            self.cached_image(&message.id).is_some(),
            self.media_loading.contains(&message.id),
            self.media_failed.contains(&message.id),
        );
        let reactions: Vec<(&str, bool)> = message
            .reactions
            .iter()
            .map(|r| (r.emoji.as_str(), r.from_me))
            .collect();
        let strip = self.strip.as_deref() == Some(message.id.as_str());
        let local = (
            self.cached_thumb(&message.id).is_some(),
            self.local_quotes.get(&message.id).map(|q| &q.id),
            self.star_override.get(&message.id).copied(),
        );
        key_of((
            2u8,
            &message.id,
            message.sender.as_deref(),
            state,
            body,
            media,
            reactions,
            strip,
            meta_key(self.timeline.meta(&message.id)),
            local,
        ))
    }

    /// One item of the timeline list: the "Load earlier" slot, a day break or
    /// a bubble. Only items on screen are built.
    fn list_item(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let now = self.frame_now;
        let offset = self.locale.tz_offset_s;
        let content = match index.checked_sub(1).map(|i| self.entries.get(i).copied()) {
            None => self.top_slot(theme, cx),
            Some(Some(Entry::Day(day_ms))) => div()
                .flex()
                .justify_center()
                .py(px(6.0))
                .text_size(typography::CAPTION.size)
                .text_color(colors.secondary_label)
                .child(day_label(day_ms, now, offset))
                .into_any_element(),
            Some(Some(Entry::Message(at))) => {
                // A preview image is fetched only for bubbles on screen.
                let wants_thumb = self.timeline.items().get(at).and_then(|item| {
                    let id = &item.message.id;
                    let has = self
                        .timeline
                        .meta(id)
                        .and_then(|m| m.media.as_ref())
                        .is_some_and(|m| m.thumbnail);
                    (has && self.cached_image(id).is_none()).then(|| id.clone())
                });
                if let Some(id) = wants_thumb {
                    self.want_thumbnail(&id, cx);
                }
                match self.timeline.items().get(at) {
                    Some(item) => self.bubble(theme, item, now, cx),
                    None => div().into_any_element(),
                }
            }
            Some(None) => div().into_any_element(),
        };
        // The padding stands in for the old column gap.
        div()
            .w_full()
            .px(px(12.0))
            .pb(px(4.0))
            .child(content)
            .into_any_element()
    }

    /// "Load earlier", a load error, or "No messages yet".
    fn top_slot(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let older = self.timeline.has_older().then(|| {
            let label = if self.loading {
                "Loading…"
            } else {
                "Load earlier"
            };
            div()
                .id("conv-older")
                .flex()
                .justify_center()
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.accent)
                .child(label)
                .on_click(cx.listener(|this, _, _, cx| {
                    if let Some(oldest) = this.timeline.oldest_ms() {
                        // The gateway's bound is exclusive and WhatsApp stamps whole
                        // seconds: +1 re-reads messages that share the oldest
                        // stamp (the timeline dedups by id) instead of skipping them.
                        this.load(Some(oldest.saturating_add(1)), cx);
                    }
                }))
        });
        let status = self.error.clone().map(|error| {
            div()
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.red)
                .child(error)
        });
        let empty = (self.entries.is_empty() && !self.loading && self.error.is_none()).then(|| {
            div()
                .py(px(48.0))
                .flex()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("No messages yet")
        });
        div()
            .flex()
            .flex_col()
            .gap(px(4.0))
            .children(older)
            .children(status)
            .children(empty)
            .into_any_element()
    }

    /// What to call the author of a message in this chat: "You", the
    /// contact, WhatsApp's name for them, or the number.
    fn author(
        &self,
        from_me: bool,
        sender: Option<&str>,
        meta: Option<&MessageMeta>,
        cx: &gpui::App,
    ) -> String {
        if from_me {
            return "You".to_owned();
        }
        let Some(jid) = sender.filter(|s| !s.is_empty()) else {
            // One-to-one chats leave the sender out: it is the other person.
            return self.title.to_string();
        };
        let contact = self
            .backend
            .read(cx)
            .contacts()
            .match_number(&format!("+{}", jid_user(jid)))
            .map(|c| c.display_name.clone());
        display_name(
            contact.as_deref(),
            meta.and_then(|m| m.sender_name.as_deref()),
            jid,
        )
    }

    /// The author of a quoted message, looked up in the timeline when it is
    /// loaded (it knows whether the message was mine).
    fn quote_author(&self, quoted: &Quoted, cx: &gpui::App) -> String {
        if let Some(item) = self
            .timeline
            .items()
            .iter()
            .find(|i| i.message.id == quoted.id)
        {
            let message = &item.message;
            return self.author(
                message.from_me,
                message.sender.as_deref().or(quoted.sender.as_deref()),
                self.timeline.meta(&message.id),
                cx,
            );
        }
        self.author(false, quoted.sender.as_deref(), None, cx)
    }

    /// The quote a bubble shows: the gateway's, else the one we just sent.
    fn quote_of(&self, id: &str) -> Option<&Quoted> {
        self.timeline
            .meta(id)
            .and_then(|m| m.quoted.as_ref())
            .or_else(|| self.local_quotes.get(id))
    }

    fn starred(&self, id: &str) -> bool {
        self.star_override
            .get(id)
            .copied()
            .unwrap_or_else(|| self.timeline.meta(id).is_some_and(|m| m.starred))
    }

    fn bubble(
        &self,
        theme: Theme,
        item: &Item,
        now: i64,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let colors = theme::colors(theme);
        let message = &item.message;
        let meta = self.timeline.meta(&message.id);
        let revoked = meta.is_some_and(|m| m.revoked);
        let own = message.from_me;
        let failed = matches!(item.state, ItemState::Failed { .. });
        let highlighted = self.highlight.as_deref() == Some(message.id.as_str());
        let (fill, text, quiet) = if own {
            let white = gpui::white();
            (colors.accent, white, white.opacity(0.72))
        } else {
            (
                colors.secondary_background,
                colors.label,
                colors.tertiary_label,
            )
        };
        let fill = if highlighted {
            colors.orange.opacity(if own { 0.9 } else { 0.35 })
        } else {
            fill
        };
        let meta_color = if failed { colors.red } else { quiet };
        let tick = Tick::of_item(item, meta);
        let line: SharedString = if failed {
            "Not sent · Tap to retry".into()
        } else {
            meta_line(
                item,
                meta,
                self.starred(&message.id),
                now,
                self.locale.tz_offset_s,
            )
            .into()
        };

        let mut bubble = div()
            .id(SharedString::from(format!("msg-{}", message.id)))
            .max_w(px(290.0))
            .px(px(12.0))
            .py(px(7.0))
            .rounded(radius::CARD)
            .bg(fill)
            .flex()
            .flex_col()
            .gap(px(2.0));
        if self.is_group && !own {
            bubble = bubble.child(
                div()
                    .text_size(typography::CAPTION.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .child(self.author(false, message.sender.as_deref(), meta, cx)),
            );
        }
        if meta.is_some_and(|m| m.forwarded) && !revoked {
            bubble = bubble.child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(4.0))
                    .text_size(typography::CAPTION.size)
                    .italic()
                    .text_color(quiet)
                    .child(
                        gpui::svg()
                            .path(Icon::LucideForward.path())
                            .size(px(12.0))
                            .text_color(quiet),
                    )
                    .child("Forwarded"),
            );
        }
        if !revoked && let Some(quoted) = self.quote_of(&message.id) {
            bubble = bubble.child(self.quote_block(theme, &message.id, quoted, own, cx));
        }
        let content = if revoked {
            div()
                .flex()
                .items_center()
                .gap(px(6.0))
                .text_size(typography::BODY.size)
                .italic()
                .text_color(quiet)
                .child(
                    gpui::svg()
                        .path(Icon::LucideBan.path())
                        .size(px(14.0))
                        .text_color(quiet),
                )
                .child(if own {
                    "You deleted this message"
                } else {
                    "This message was deleted"
                })
                .into_any_element()
        } else if let Some(location) = meta.and_then(|m| m.location.as_ref()) {
            location_card(theme, location, text, quiet)
        } else if let Some(contact) = meta.and_then(|m| m.contact.as_ref()) {
            contact_card(theme, contact, text, quiet)
        } else {
            self.body(theme, message, meta, text, quiet, cx)
        };
        bubble = bubble.child(content).child(
            div()
                .self_end()
                .flex()
                .items_center()
                .gap(px(3.0))
                .text_size(typography::CAPTION.size)
                .text_color(meta_color)
                .child(line)
                .children(tick.map(|tick| ticks(tick, 14.0, quiet, read_color(true)))),
        );
        let confirmed = !matches!(
            item.state,
            ItemState::Pending { .. } | ItemState::Failed { .. }
        );
        if let ItemState::Failed { local_id } = item.state {
            bubble = bubble.on_click(cx.listener(move |this, _, _, cx| this.retry(local_id, cx)));
        } else if confirmed {
            let id = message.id.clone();
            bubble = bubble.on_click(cx.listener(move |this, _, _, cx| {
                if this.strip.take().is_some() {
                    this.rows_dirty = true;
                    cx.notify();
                }
            }));
            bubble = bubble.on_long_press(cx.listener(move |this, _, _, cx| {
                this.strip = Some(id.clone());
                this.rows_dirty = true;
                cx.notify();
            }));
        }

        let mut column = div()
            .flex()
            .flex_col()
            .gap(px(2.0))
            .when(own, |c| c.items_end())
            .child(bubble);
        let chips = reaction_chips(&message.reactions);
        if !chips.is_empty() && !revoked {
            column = column.child(self.chips(theme, message, &chips, cx));
        }
        if confirmed && self.strip.as_deref() == Some(message.id.as_str()) {
            if !revoked {
                column = column.child(self.quick_strip(theme, message, cx));
            }
            column = column.child(self.actions(theme, item, meta, now, cx));
        }
        div()
            .flex()
            .when(own, |row| row.justify_end())
            .child(column)
            .into_any_element()
    }

    /// The quoted message at the top of a reply; a tap shows it.
    fn quote_block(
        &self,
        theme: Theme,
        owner: &str,
        quoted: &Quoted,
        own: bool,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let colors = theme::colors(theme);
        let (bar, text, quiet) = if own {
            let white = gpui::white();
            (white, white, white.opacity(0.8))
        } else {
            (colors.accent, colors.label, colors.secondary_label)
        };
        let author = self.quote_author(quoted, cx);
        let preview = quoted
            .preview
            .clone()
            .filter(|p| !p.trim().is_empty())
            .unwrap_or_else(|| "Message".to_owned());
        let target = quoted.id.clone();
        div()
            .id(SharedString::from(format!("quote-{owner}")))
            .flex()
            .gap(px(8.0))
            .px(px(8.0))
            .py(px(4.0))
            .rounded(px(8.0))
            .bg(text.opacity(0.08))
            .child(div().flex_none().w(px(3.0)).rounded_full().bg(bar))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::CAPTION.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(bar)
                            .child(author),
                    )
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(quiet)
                            .child(preview),
                    ),
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                cx.stop_propagation();
                this.jump_to(&target, cx);
            }))
            .into_any_element()
    }

    /// The content of a bubble: text, or a media card with its preview
    /// image, file name, duration and size.
    fn body(
        &self,
        theme: Theme,
        message: &WaMessage,
        meta: Option<&MessageMeta>,
        text: gpui::Hsla,
        quiet: gpui::Hsla,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let MessageBody::Media {
            kind,
            caption,
            mime,
            size,
        } = &message.body
        else {
            return div()
                .text_size(typography::BODY.size)
                .text_color(text)
                .child(body_text(&message.body))
                .into_any_element();
        };
        let colors = theme::colors(theme);
        let media = meta.and_then(|m| m.media.as_ref());
        let downloadable = matches!(kind, MediaKind::Image | MediaKind::Sticker);
        let mut card = div().flex().flex_col().gap(px(4.0));
        if let Some(image) = self.cached_image(&message.id) {
            card = card.child(
                img(image)
                    .max_w(px(240.0))
                    .max_h(px(320.0))
                    .rounded(radius::CARD),
            );
        } else {
            let status = if self.media_loading.contains(&message.id) {
                "Loading…"
            } else if self.media_failed.contains(&message.id) {
                "Couldn't load · Tap to retry"
            } else if downloadable {
                "Tap to load"
            } else {
                ""
            };
            let thumb = self.cached_thumb(&message.id).map(|thumb| {
                let (w, h) = thumb_size(media);
                img(thumb)
                    .w(px(w))
                    .h(px(h))
                    .rounded(radius::CARD)
                    .object_fit(ObjectFit::Cover)
            });
            let icon = media_icon(*kind, media);
            let mut inner = div()
                .id(SharedString::from(format!("media-{}", message.id)))
                .flex()
                .flex_col()
                .gap(px(4.0))
                .children(thumb)
                .child(
                    div()
                        .px(px(10.0))
                        .py(px(8.0))
                        .rounded(radius::CARD)
                        .bg(colors.label.opacity(0.08))
                        .flex()
                        .items_center()
                        .gap(px(8.0))
                        .child(
                            gpui::svg()
                                .flex_none()
                                .path(icon.path())
                                .size(px(20.0))
                                .text_color(text),
                        )
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .flex_col()
                                .child(
                                    div()
                                        .truncate()
                                        .text_size(typography::BODY.size)
                                        .font_weight(typography::HEADLINE.weight)
                                        .text_color(text)
                                        .child(media_title(*kind, media)),
                                )
                                .child(
                                    div()
                                        .text_size(typography::CAPTION.size)
                                        .text_color(quiet)
                                        .child(media_details(*size, media, status)),
                                ),
                        ),
                );
            if downloadable {
                let id = message.id.clone();
                let mime = mime.clone();
                inner = inner.on_click(cx.listener(move |this, _, _, cx| {
                    this.fetch_media(id.clone(), mime.clone(), cx);
                }));
            }
            card = card.child(inner);
        }
        if let Some(caption) = caption.as_deref().filter(|c| !c.is_empty()) {
            card = card.child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(text)
                    .child(caption.to_owned()),
            );
        }
        card.into_any_element()
    }

    /// Reaction chips under a bubble; tapping our own removes it.
    fn chips(
        &self,
        theme: Theme,
        message: &WaMessage,
        chips: &[(String, u32, bool)],
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let colors = theme::colors(theme);
        let mut row = div().flex().flex_row().gap(px(4.0)).px(px(6.0));
        for (index, (emoji, count, mine)) in chips.iter().enumerate() {
            let label = if *count > 1 {
                format!("{emoji} {count}")
            } else {
                emoji.clone()
            };
            let mut chip = div()
                .id(SharedString::from(format!("chip-{}-{index}", message.id)))
                .px(px(8.0))
                .py(px(2.0))
                .rounded_full()
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.label)
                .bg(if *mine {
                    colors.accent.opacity(0.25)
                } else {
                    colors.secondary_background
                })
                .child(label);
            if *mine {
                let id = message.id.clone();
                let from_me = message.from_me;
                let sender = message.sender.clone();
                chip = chip.on_click(cx.listener(move |this, _, _, cx| {
                    this.react(id.clone(), from_me, sender.clone(), String::new(), cx);
                }));
            }
            row = row.child(chip);
        }
        row.into_any_element()
    }

    /// The glass strip of quick emojis opened by a long press.
    fn quick_strip(
        &self,
        theme: Theme,
        message: &WaMessage,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let mine = message
            .reactions
            .iter()
            .find(|r| r.from_me)
            .map(|r| r.emoji.as_str());
        let mut row = glass(GlassSpec::control(theme))
            .rounded_full()
            .px(px(8.0))
            .py(px(4.0))
            .flex()
            .flex_row()
            .gap(px(4.0));
        for emoji in QUICK_EMOJIS {
            let id = message.id.clone();
            let from_me = message.from_me;
            let sender = message.sender.clone();
            // Picking the emoji already set takes it back.
            let send = if mine == Some(emoji) { "" } else { emoji };
            row = row.child(
                div()
                    .id(SharedString::from(format!("quick-{}-{emoji}", message.id)))
                    .px(px(6.0))
                    .py(px(2.0))
                    .text_size(typography::TITLE3.size)
                    .child(emoji)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.react(id.clone(), from_me, sender.clone(), send.to_owned(), cx);
                    })),
            );
        }
        row.into_any_element()
    }

    /// The actions under the emoji strip: Reply, Star, Edit and Delete.
    fn actions(
        &self,
        theme: Theme,
        item: &Item,
        meta: Option<&MessageMeta>,
        now: i64,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let message = &item.message;
        let revoked = meta.is_some_and(|m| m.revoked);
        let id = message.id.clone();
        let pill = |key: &str, icon: Icon, label: &'static str, danger: bool| {
            let colors = theme::colors(theme);
            let color = if danger { colors.red } else { colors.label };
            glass_pill(theme, label)
                .id(SharedString::from(format!("act-{key}-{id}")))
                .flex()
                .items_center()
                .gap(px(6.0))
                .text_color(color)
                .child(
                    gpui::svg()
                        .path(icon.path())
                        .size(px(14.0))
                        .text_color(color),
                )
        };
        let mut row = div().flex().flex_wrap().gap(px(6.0)).px(px(4.0));
        if !revoked {
            let target = id.clone();
            row = row.child(
                pill("reply", Icon::LucideReply, "Reply", false)
                    .on_click(cx.listener(move |this, _, _, cx| this.begin_reply(&target, cx))),
            );
            let starred = self.starred(&id);
            let target = id.clone();
            row = row.child(
                pill(
                    "star",
                    Icon::LucideStar,
                    if starred { "Unstar" } else { "Star" },
                    false,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.set_starred(&target, !starred, cx);
                })),
            );
        }
        if let Some(text) = copy_text_of(&message.body, meta) {
            row = row.child(
                pill("copy", Icon::LucideCopy, "Copy", false).on_click(cx.listener(
                    move |this, _, _, cx| {
                        this.strip = None;
                        this.rows_dirty = true;
                        cx.emit(ConversationEvent::Copy(text.clone()));
                        cx.notify();
                    },
                )),
            );
        }
        if can_edit(item, meta, now) {
            let target = id.clone();
            row = row.child(
                pill("edit", Icon::LucidePencil, "Edit", false)
                    .on_click(cx.listener(move |this, _, _, cx| this.begin_edit(&target, cx))),
            );
        }
        let for_everyone = message.from_me && !revoked;
        let target = id.clone();
        row = row.child(
            pill("delete", Icon::LucideTrash2, "Delete", true).on_click(cx.listener(
                move |this, _, _, cx| {
                    this.strip = None;
                    this.rows_dirty = true;
                    this.delete_confirm = Some(DeleteTarget {
                        id: target.clone(),
                        for_everyone,
                    });
                    cx.notify();
                },
            )),
        );
        row.into_any_element()
    }

    /// The header's avatar: the contact photo, else the WhatsApp picture,
    /// else the initials.
    fn header_avatar(&self, theme: Theme, cx: &mut Context<Self>) -> gpui::AnyElement {
        let colors = theme::colors(theme);
        let mut photo = None;
        if let Some(uri) = &self.peer.photo_uri {
            self.photos.update(cx, |photos, cx| photos.want(uri, cx));
            photo = self.photos.read(cx).photo(uri);
        }
        if photo.is_none() {
            let picture_id = self.peer.picture_id.as_deref();
            let chat = self.chat.as_str();
            self.avatars
                .update(cx, |avatars, cx| avatars.want(chat, picture_id, cx));
            photo = self.avatars.read(cx).picture(chat);
        }
        let monogram = if self.is_group {
            SharedString::default()
        } else {
            self.peer.initials.clone()
        };
        photo_avatar(theme, monogram, 36.0, colors.green, photo).into_any_element()
    }

    /// The bar above the composer while replying or editing.
    fn compose_context(&self, theme: Theme, cx: &mut Context<Self>) -> Option<gpui::AnyElement> {
        let colors = theme::colors(theme);
        let (title, preview): (SharedString, SharedString) = if self.editing.is_some() {
            ("Edit message".into(), "Send saves the new text".into())
        } else {
            let reply = self.reply_to.as_ref()?;
            (
                format!("Replying to {}", reply.author).into(),
                reply.preview.clone(),
            )
        };
        Some(
            div()
                .flex_none()
                .mx(px(12.0))
                .px(px(12.0))
                .py(px(6.0))
                .rounded(px(12.0))
                .bg(colors.secondary_background)
                .flex()
                .items_center()
                .gap(px(8.0))
                .child(
                    div()
                        .flex_none()
                        .w(px(3.0))
                        .h(px(32.0))
                        .rounded_full()
                        .bg(colors.accent),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .flex()
                        .flex_col()
                        .child(
                            div()
                                .truncate()
                                .text_size(typography::CAPTION.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.accent)
                                .child(title),
                        )
                        .child(
                            div()
                                .truncate()
                                .text_size(typography::FOOTNOTE.size)
                                .text_color(colors.secondary_label)
                                .child(preview),
                        ),
                )
                .child(
                    div()
                        .id("conv-context-close")
                        .flex_none()
                        .size(px(32.0))
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(
                            gpui::svg()
                                .path(Icon::LucideX.path())
                                .size(px(16.0))
                                .text_color(colors.secondary_label),
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.cancel_compose_context(cx))),
                )
                .into_any_element(),
        )
    }

    /// "Delete for everyone / for me" over the conversation.
    fn delete_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<gpui::AnyElement> {
        let target = self.delete_confirm.as_ref()?;
        let colors = theme::colors(theme);
        let mut items = Vec::new();
        if target.for_everyone {
            let id = target.id.clone();
            items.push(
                crate::menu_item(
                    theme,
                    "del-everyone",
                    Icon::LucideTrash2,
                    "Delete for everyone".into(),
                    colors.red,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.revoke(&id, cx)))
                .into_any_element(),
            );
        }
        let id = target.id.clone();
        items.push(
            crate::menu_item(
                theme,
                "del-me",
                Icon::LucideTrash2,
                "Delete for me".into(),
                colors.red,
            )
            .on_click(cx.listener(move |this, _, _, cx| this.delete_for_me(&id, cx)))
            .into_any_element(),
        );
        items.push(
            crate::menu_item(
                theme,
                "del-cancel",
                Icon::LucideX,
                "Cancel".into(),
                colors.label,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.delete_confirm = None;
                cx.notify();
            }))
            .into_any_element(),
        );
        Some(crate::menu_card(
            theme,
            "delete-menu",
            "Delete message?".into(),
            items,
            cx.listener(|this, _, _, cx| {
                this.delete_confirm = None;
                cx.notify();
            }),
        ))
    }
}

impl Render for Conversation {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let now = now_ms();
        self.frame_now = now;
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let offset = self.locale.tz_offset_s;
        if self.rows_dirty {
            self.sync_rows();
        }
        let timeline = list(
            self.list.state().clone(),
            cx.processor(|this, index: usize, window, cx| this.list_item(index, window, cx)),
        )
        .flex_1()
        .min_h_0()
        .w_full();

        let back = glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
            .id("conv-back")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(ConversationEvent::Back)))
            .into_any_element();
        let switch = self.switch.clone().map(|(thread, badge)| {
            glass_pill(theme, badge.label())
                .id("conv-switch")
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.emit(ConversationEvent::Switch(thread.clone()));
                }))
                .into_any_element()
        });
        let mute = glass_icon_button(theme, Icon::LucideBellOff, 0.0, self.muted)
            .id("conv-mute")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(ConversationEvent::ToggleMute)))
            .into_any_element();
        let trailing = div()
            .flex()
            .items_center()
            .gap(px(8.0))
            .children(switch)
            .child(mute)
            .into_any_element();
        let avatar = self.header_avatar(theme, cx);
        let header = header_bar_with(
            theme,
            back,
            Some(avatar),
            self.title.clone(),
            self.subtitle(now),
            Some(trailing),
        );
        let strip = schedule_ui::strip(
            theme,
            &self.scheduled,
            now,
            offset,
            cx,
            |_: &mut Self, key, cx| cx.emit(ConversationEvent::CancelScheduled(key)),
        );

        let field = div()
            .flex_1()
            .min_w_0()
            .flex()
            .flex_col()
            .child(field_box(
                theme,
                "conv-field",
                &self.composer,
                "Message",
                window,
                cx,
            ))
            .into_any_element();
        let send = glass_icon_button(theme, Icon::LucideArrowUp, 0.0, false)
            .id("conv-send")
            .on_click(cx.listener(|this, _, _, cx| this.send(cx)))
            .on_long_press(cx.listener(|this, _, _, cx| this.request_schedule(cx)))
            .on_mouse_down(
                gpui::MouseButton::Right,
                cx.listener(|this, _, _, cx| this.request_schedule(cx)),
            )
            .into_any_element();
        let attach = glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
            .id("conv-attach")
            .on_click(cx.listener(|this, _, _, cx| this.pick_attachment(cx)))
            .into_any_element();
        let composer = composer_bar(keyboard, Some(attach), field, send);
        let colors = theme::colors(theme);
        let info = (self.attachment.is_some() || self.notice.is_some()).then(|| {
            let chip = self.attachment.as_ref().map(|attached| {
                glass_pill(
                    theme,
                    format!("{} ✕", attach_chip_label(&attached.name, attached.kind)),
                )
                .id("conv-attached")
                .h(px(30.0))
                .px(px(12.0))
                .text_size(typography::FOOTNOTE.size)
                .on_click(cx.listener(|this, _, _, cx| {
                    this.attachment = None;
                    cx.notify();
                }))
            });
            div()
                .flex_none()
                .flex()
                .flex_col()
                .items_start()
                .gap(px(4.0))
                .px(px(16.0))
                .children(chip)
                .children(self.notice.clone().map(|notice| {
                    div()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.red)
                        .child(notice)
                }))
        });

        let context = self.compose_context(theme, cx);
        let overlay = self.delete_overlay(theme, cx);

        div()
            .size_full()
            .relative()
            .flex()
            .flex_col()
            .child(header)
            .child(timeline)
            .children(strip)
            .children(info)
            .children(context)
            .child(composer)
            .children(overlay)
    }
}

/// The strip above a conversation, shared by the WhatsApp and SMS views: back
/// button, title with an optional line under it, and an optional trailing
/// control (the channel switch).
pub(crate) fn header_bar(
    theme: Theme,
    back: gpui::AnyElement,
    title: SharedString,
    subtitle: Option<String>,
    trailing: Option<gpui::AnyElement>,
) -> gpui::Div {
    header_bar_with(theme, back, None, title, subtitle, trailing)
}

/// [`header_bar`] with an avatar between the back button and the title.
pub(crate) fn header_bar_with(
    theme: Theme,
    back: gpui::AnyElement,
    avatar: Option<gpui::AnyElement>,
    title: SharedString,
    subtitle: Option<String>,
    trailing: Option<gpui::AnyElement>,
) -> gpui::Div {
    let colors = theme::colors(theme);
    div()
        .flex_none()
        .flex()
        .items_center()
        .gap(px(8.0))
        .px(px(8.0))
        .pt(px(48.0))
        .pb(px(8.0))
        .child(back)
        .children(avatar)
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(
                    div()
                        .truncate()
                        .text_size(typography::TITLE3.size)
                        .font_weight(typography::TITLE3.weight)
                        .text_color(colors.label)
                        .child(title),
                )
                .children(subtitle.map(|line| {
                    div()
                        .truncate()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.secondary_label)
                        .child(line)
                })),
        )
        .children(trailing)
}

/// The strip under a conversation: an optional leading button (attach), the
/// message field and the send button, lifted by the keyboard.
pub(crate) fn composer_bar(
    keyboard: gpui::Pixels,
    leading: Option<gpui::AnyElement>,
    field: gpui::AnyElement,
    send: gpui::AnyElement,
) -> gpui::Div {
    div()
        .flex_none()
        .flex()
        .items_center()
        .gap(px(8.0))
        .px(px(12.0))
        .pt(px(8.0))
        .pb(px(12.0) + keyboard)
        .children(leading)
        .child(field)
        .child(send)
}

pub(crate) fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// The read receipts the open chat owes: none while the window is not in
/// front of the user (`active`), else one per author for the incoming
/// messages not yet `marked`.
fn reads_due(timeline: &Timeline, marked: &HashSet<String>, active: bool) -> Vec<MarkRead> {
    if !active {
        return Vec::new();
    }
    let unread = timeline
        .unread()
        .filter(|m| !marked.contains(&m.id))
        .map(|m| (m.sender.as_deref(), m.id.as_str()));
    read_requests(timeline.chat(), unread)
}

/// The text under a bubble: the time ("Now" within a minute), or "Not sent".
/// The ticks of my messages are icons after it ([`Tick`]).
fn meta_at(item: &Item, now_ms: i64, offset_s: i32) -> String {
    if matches!(item.state, ItemState::Failed { .. }) {
        return "Not sent".to_owned();
    }
    let ts = item.message.timestamp_ms;
    if (0..60_000).contains(&(now_ms - ts)) {
        "Now".to_owned()
    } else {
        clock(ts, offset_s)
    }
}

/// The text under a bubble: a star, "Edited" and the time.
fn meta_line(
    item: &Item,
    meta: Option<&MessageMeta>,
    starred: bool,
    now_ms: i64,
    offset_s: i32,
) -> String {
    let mut line = meta_at(item, now_ms, offset_s);
    let failed_or_pending = matches!(
        item.state,
        ItemState::Pending { .. } | ItemState::Failed { .. }
    );
    if let Some(meta) = meta
        && !failed_or_pending
        && meta.edited_ms.is_some()
        && !meta.revoked
    {
        line = format!("Edited · {line}");
    }
    if starred {
        line = format!("★ {line}");
    }
    line
}

/// Whether my message may still be edited: a confirmed text, or a photo,
/// video or document caption, within WhatsApp's 15 minutes.
fn can_edit(item: &Item, meta: Option<&MessageMeta>, now_ms: i64) -> bool {
    let message = &item.message;
    let confirmed = !matches!(
        item.state,
        ItemState::Pending { .. } | ItemState::Failed { .. }
    );
    let editable = match &message.body {
        MessageBody::Text(_) => true,
        MessageBody::Media { kind, .. } => {
            matches!(
                kind,
                MediaKind::Image | MediaKind::Video | MediaKind::Document
            )
        }
        MessageBody::Unsupported => false,
    };
    message.from_me
        && confirmed
        && editable
        && !meta.is_some_and(|m| m.revoked)
        && now_ms.saturating_sub(message.timestamp_ms) <= EDIT_WINDOW_MS
}

/// The one-line preview of a message for the reply bar.
fn preview_of(body: &MessageBody, meta: Option<&MessageMeta>) -> String {
    if let Some(location) = meta.and_then(|m| m.location.as_ref()) {
        return location
            .name
            .clone()
            .filter(|n| !n.trim().is_empty())
            .unwrap_or_else(|| "Location".to_owned());
    }
    if let Some(contact) = meta.and_then(|m| m.contact.as_ref()) {
        return format!("Contact · {}", contact.display_name);
    }
    body_preview(body, 80).unwrap_or_else(|| "Message".to_owned())
}

/// A hash of what the meta changes in a bubble (the meta holds a float, so
/// it has no `Hash` of its own).
fn meta_key(meta: Option<&MessageMeta>) -> u64 {
    let Some(meta) = meta else {
        return 0;
    };
    let quoted = meta
        .quoted
        .as_ref()
        .map(|q| (&q.id, q.sender.as_deref(), q.preview.as_deref()));
    let media = meta.media.as_ref().map(|m| {
        (
            m.file_name.as_deref(),
            m.seconds,
            m.width,
            m.height,
            m.pages,
            m.voice_note,
            m.thumbnail,
        )
    });
    let location = meta.location.as_ref().map(|l| {
        (
            l.latitude.to_bits(),
            l.longitude.to_bits(),
            l.name.as_deref(),
            l.address.as_deref(),
            l.live,
        )
    });
    let contact = meta.contact.as_ref().map(|c| (&c.display_name, &c.vcard));
    key_of((
        1u8,
        quoted,
        meta.forwarded,
        meta.edited_ms,
        meta.revoked,
        meta.status,
        meta.sender_name.as_deref(),
        media,
        location,
        contact,
        meta.starred,
    ))
}

/// The image format of thumbnail bytes, from their magic number.
fn sniff_format(bytes: &[u8]) -> Option<ImageFormat> {
    match bytes {
        [0xFF, 0xD8, 0xFF, ..] => Some(ImageFormat::Jpeg),
        [0x89, b'P', b'N', b'G', ..] => Some(ImageFormat::Png),
        [
            b'R',
            b'I',
            b'F',
            b'F',
            _,
            _,
            _,
            _,
            b'W',
            b'E',
            b'B',
            b'P',
            ..,
        ] => Some(ImageFormat::Webp),
        [b'G', b'I', b'F', ..] => Some(ImageFormat::Gif),
        _ => None,
    }
}

/// The size a preview thumbnail is drawn at: 240 px wide (180 for stickers
/// and squares), its height from the media's aspect ratio.
fn thumb_size(media: Option<&MediaMeta>) -> (f32, f32) {
    const WIDTH: f32 = 240.0;
    match media.and_then(|m| Some((m.width?, m.height?))) {
        Some((w, h)) if w > 0 && h > 0 => {
            let height = WIDTH * h as f32 / w as f32;
            (WIDTH, height.clamp(90.0, 320.0))
        }
        _ => (180.0, 180.0),
    }
}

fn media_icon(kind: MediaKind, media: Option<&MediaMeta>) -> Icon {
    match kind {
        MediaKind::Image | MediaKind::Sticker => Icon::LucideImage,
        MediaKind::Video => Icon::LucideVideo,
        MediaKind::Audio if media.is_some_and(|m| m.voice_note) => Icon::LucideMic,
        MediaKind::Audio => Icon::LucideHeadphones,
        MediaKind::Document => Icon::LucidePaperclip,
    }
}

/// The card's first line: a document's file name, else what it is.
fn media_title(kind: MediaKind, media: Option<&MediaMeta>) -> String {
    if kind == MediaKind::Document
        && let Some(name) = media
            .and_then(|m| m.file_name.as_deref())
            .filter(|n| !n.trim().is_empty())
    {
        return name.to_owned();
    }
    match kind {
        MediaKind::Audio if !media.is_some_and(|m| m.voice_note) => "Audio".to_owned(),
        MediaKind::Video if media.is_some_and(|m| m.animated) => "GIF".to_owned(),
        _ => media_label(kind).to_owned(),
    }
}

/// The card's second line: duration, pages, size, view once, and the
/// download state.
fn media_details(size: u64, media: Option<&MediaMeta>, status: &str) -> String {
    let mut parts = Vec::new();
    if let Some(media) = media {
        if let Some(seconds) = media.seconds {
            parts.push(duration_label(seconds));
        }
        if let Some(pages) = media.pages {
            parts.push(format!("{pages} page{}", if pages == 1 { "" } else { "s" }));
        }
    }
    parts.push(format_size(size));
    if media.is_some_and(|m| m.view_once) {
        parts.push("View once".to_owned());
    }
    if !status.is_empty() {
        parts.push(status.to_owned());
    }
    parts.join(" · ")
}

/// The first phone number of a vCard (`TEL;TYPE=CELL:+90 532 …`).
fn vcard_phone(vcard: &str) -> Option<String> {
    vcard.lines().find_map(|line| {
        let (key, value) = line.split_once(':')?;
        // `TEL`, `TEL;TYPE=CELL` or a grouped `item1.TEL;waid=…`.
        let name = key
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_uppercase();
        let is_tel = name == "TEL" || name.ends_with(".TEL");
        let value = value.trim();
        (is_tel && !value.is_empty()).then(|| value.to_owned())
    })
}

/// `41.00820, 28.97840`.
fn coordinates(location: &Location) -> String {
    format!("{:.5}, {:.5}", location.latitude, location.longitude)
}

/// A shared location: its name, address and coordinates.
fn location_card(
    theme: Theme,
    location: &Location,
    text: gpui::Hsla,
    quiet: gpui::Hsla,
) -> gpui::AnyElement {
    let colors = theme::colors(theme);
    let title = location
        .name
        .clone()
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| {
            if location.live {
                "Live location".to_owned()
            } else {
                "Location".to_owned()
            }
        });
    let mut lines = vec![];
    if let Some(address) = location.address.as_deref().filter(|a| !a.trim().is_empty()) {
        lines.push(address.to_owned());
    }
    lines.push(coordinates(location));
    if location.live {
        lines.push("Live location".to_owned());
    }
    info_card(
        Icon::LucideMapPin,
        title,
        lines,
        colors.label.opacity(0.08),
        text,
        quiet,
    )
}

/// A shared contact card: the name and its first number.
fn contact_card(
    theme: Theme,
    contact: &SharedContact,
    text: gpui::Hsla,
    quiet: gpui::Hsla,
) -> gpui::AnyElement {
    let colors = theme::colors(theme);
    let mut lines = vec![];
    if let Some(phone) = vcard_phone(&contact.vcard) {
        lines.push(phone);
    }
    lines.push("Contact card".to_owned());
    info_card(
        Icon::LucideContact,
        contact.display_name.clone(),
        lines,
        colors.label.opacity(0.08),
        text,
        quiet,
    )
}

fn info_card(
    icon: Icon,
    title: String,
    lines: Vec<String>,
    fill: gpui::Hsla,
    text: gpui::Hsla,
    quiet: gpui::Hsla,
) -> gpui::AnyElement {
    div()
        .px(px(10.0))
        .py(px(8.0))
        .rounded(radius::CARD)
        .bg(fill)
        .flex()
        .items_start()
        .gap(px(8.0))
        .child(
            gpui::svg()
                .flex_none()
                .path(icon.path())
                .size(px(20.0))
                .text_color(text),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(
                    div()
                        .text_size(typography::BODY.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(text)
                        .child(title),
                )
                .children(lines.into_iter().map(|line| {
                    div()
                        .text_size(typography::CAPTION.size)
                        .text_color(quiet)
                        .child(line)
                })),
        )
        .into_any_element()
}

/// The gpui image format for a MIME type, `None` when it cannot be shown.
fn image_format(mime: &str) -> Option<ImageFormat> {
    match mime.split(';').next()?.trim() {
        "image/png" => Some(ImageFormat::Png),
        "image/jpeg" | "image/jpg" => Some(ImageFormat::Jpeg),
        "image/webp" => Some(ImageFormat::Webp),
        "image/gif" => Some(ImageFormat::Gif),
        _ => None,
    }
}

/// `1.4 MB`, `820 KB`, `12 B`.
fn format_size(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{} KB", bytes / KB)
    } else {
        format!("{bytes} B")
    }
}

/// What Send does with the composer text and the attachment chip.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Plan {
    Nothing,
    Text,
    /// The text, when there is any, becomes the caption.
    Media {
        caption: Option<String>,
    },
}

fn send_plan(text: &str, has_chip: bool) -> Plan {
    let text = text.trim();
    match (has_chip, text.is_empty()) {
        (true, true) => Plan::Media { caption: None },
        (true, false) => Plan::Media {
            caption: Some(text.to_owned()),
        },
        (false, false) => Plan::Text,
        (false, true) => Plan::Nothing,
    }
}

/// The chip's text: the file name and what it will be sent as.
fn attach_chip_label(name: &str, kind: MediaKind) -> String {
    format!("{name} · {}", media_label(kind))
}

fn media_label(kind: MediaKind) -> &'static str {
    match kind {
        MediaKind::Image => "Photo",
        MediaKind::Video => "Video",
        MediaKind::Audio => "Voice message",
        MediaKind::Document => "Document",
        MediaKind::Sticker => "Sticker",
    }
}

/// The text a bubble shows for a body.
fn body_text(body: &MessageBody) -> String {
    match body {
        MessageBody::Text(text) => text.clone(),
        MessageBody::Media { kind, caption, .. } => {
            let label = media_label(*kind);
            match caption.as_deref().filter(|c| !c.is_empty()) {
                Some(caption) => format!("{label}\n{caption}"),
                None => label.to_owned(),
            }
        }
        MessageBody::Unsupported => "Unsupported message".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use rovsti_wa_proto::{DeliveryStatus, WaMessage};

    use super::*;

    fn item(ts: i64, from_me: bool, state: ItemState) -> Item {
        Item {
            message: WaMessage {
                id: "a".into(),
                chat: "1@s.whatsapp.net".into(),
                sender: None,
                from_me,
                timestamp_ms: ts,
                body: MessageBody::Text("hi".into()),
                reactions: vec![],
            },
            state,
        }
    }

    #[test]
    fn copy_text_of_text_and_captions_only() {
        let text = MessageBody::Text(" hi ".into());
        assert_eq!(copy_text_of(&text, None).as_deref(), Some("hi"));
        let media = |caption: Option<&str>| MessageBody::Media {
            kind: MediaKind::Image,
            caption: caption.map(str::to_owned),
            mime: "image/jpeg".into(),
            size: 1,
        };
        assert_eq!(
            copy_text_of(&media(Some("beach")), None).as_deref(),
            Some("beach")
        );
        assert_eq!(copy_text_of(&media(None), None), None);
        assert_eq!(copy_text_of(&MessageBody::Unsupported, None), None);
        let revoked = MessageMeta {
            revoked: true,
            ..MessageMeta::default()
        };
        assert_eq!(copy_text_of(&text, Some(&revoked)), None);
        let card = MessageMeta {
            location: Some(Location::default()),
            ..MessageMeta::default()
        };
        assert_eq!(copy_text_of(&text, Some(&card)), None);
    }

    // 2026-11-05 14:05:00 UTC
    const T: i64 = 1_793_887_500_000;

    #[test]
    fn bubble_meta_shows_time_and_state() {
        let later = T + 3_600_000;
        // The ticks are icons after the time (see `ticks`).
        for state in [ItemState::Sent, ItemState::Delivered, ItemState::Read] {
            assert_eq!(meta_at(&item(T, true, state), later, 0), "14:05");
        }
        assert_eq!(meta_at(&item(T, false, ItemState::Sent), later, 0), "14:05");
        assert_eq!(
            meta_at(&item(T, true, ItemState::Pending { local_id: 1 }), later, 0),
            "14:05"
        );
        assert_eq!(
            meta_at(&item(T, true, ItemState::Failed { local_id: 1 }), later, 0),
            "Not sent"
        );
        assert_eq!(
            meta_at(&item(T, false, ItemState::Sent), T + 5_000, 0),
            "Now"
        );
        // The offset moves the clock; a minute ago is not "Now".
        assert_eq!(
            meta_at(&item(T, false, ItemState::Sent), later, 3 * 3600),
            "17:05"
        );
        assert_eq!(meta_at(&item(T, false, ItemState::Sent), later, 0), "14:05");
    }

    fn with_meta(item: &Item, meta: MessageMeta) -> (Item, MessageMeta) {
        (item.clone(), meta)
    }

    #[test]
    fn meta_lines_show_stars_edits_and_played_voice_notes() {
        let later = T + 3_600_000;
        let (sent, edited) = with_meta(
            &item(T, true, ItemState::Delivered),
            MessageMeta {
                edited_ms: Some(T + 1),
                ..MessageMeta::default()
            },
        );
        assert_eq!(
            meta_line(&sent, Some(&edited), false, later, 0),
            "Edited · 14:05"
        );
        assert_eq!(meta_line(&sent, None, true, later, 0), "★ 14:05");
        let voice = MessageMeta {
            status: Some(DeliveryStatus::Played),
            media: Some(MediaMeta {
                voice_note: true,
                ..MediaMeta::default()
            }),
            ..MessageMeta::default()
        };
        // A heard voice note says so with a blue microphone by its ticks.
        let read = item(T, true, ItemState::Read);
        assert_eq!(meta_line(&read, Some(&voice), false, later, 0), "14:05");
        assert_eq!(Tick::of_item(&read, Some(&voice)), Some(Tick::Played));
        let pending = item(T, true, ItemState::Pending { local_id: 1 });
        assert_eq!(meta_line(&pending, Some(&edited), false, later, 0), "14:05");
        assert_eq!(Tick::of_item(&pending, None), Some(Tick::Pending));
    }

    #[test]
    fn read_receipts_only_while_seen_and_per_group_author() {
        let group = "1-2@g.us";
        let incoming = |id: &str, sender: &str| WaMessage {
            id: id.into(),
            chat: group.into(),
            sender: Some(sender.into()),
            from_me: false,
            timestamp_ms: T,
            body: MessageBody::Text(id.into()),
            reactions: vec![],
        };
        let mut timeline = Timeline::new(group);
        timeline.apply_page(
            vec![
                incoming("a", "1@s.whatsapp.net"),
                incoming("b", "2@s.whatsapp.net"),
                incoming("c", "1@s.whatsapp.net"),
            ],
            50,
        );
        let mut marked = HashSet::new();
        // In the background (or with the screen off) nothing is "seen".
        assert!(reads_due(&timeline, &marked, false).is_empty());
        let due = reads_due(&timeline, &marked, true);
        let by_author: Vec<(Option<&str>, Vec<&str>)> = due
            .iter()
            .map(|r| {
                (
                    r.sender.as_deref(),
                    r.ids.iter().map(String::as_str).collect(),
                )
            })
            .collect();
        assert_eq!(
            by_author,
            [
                (Some("1@s.whatsapp.net"), vec!["a", "c"]),
                (Some("2@s.whatsapp.net"), vec!["b"]),
            ]
        );
        // Once reported, a message is not reported again this session.
        marked.extend(["a".to_owned(), "b".to_owned(), "c".to_owned()]);
        assert!(reads_due(&timeline, &marked, true).is_empty());
    }

    #[test]
    fn only_my_recent_texts_and_captions_can_be_edited() {
        let mine = item(T, true, ItemState::Sent);
        assert!(can_edit(&mine, None, T + EDIT_WINDOW_MS));
        assert!(!can_edit(&mine, None, T + EDIT_WINDOW_MS + 1));
        assert!(!can_edit(&item(T, false, ItemState::Sent), None, T));
        assert!(!can_edit(
            &item(T, true, ItemState::Pending { local_id: 1 }),
            None,
            T
        ));
        let revoked = MessageMeta {
            revoked: true,
            ..MessageMeta::default()
        };
        assert!(!can_edit(&mine, Some(&revoked), T));
        let mut voice = mine.clone();
        voice.message.body = MessageBody::Media {
            kind: MediaKind::Audio,
            caption: None,
            mime: "audio/ogg".into(),
            size: 1,
        };
        assert!(!can_edit(&voice, None, T));
        let mut photo = mine;
        photo.message.body = MessageBody::Media {
            kind: MediaKind::Image,
            caption: Some("sea".into()),
            mime: "image/jpeg".into(),
            size: 1,
        };
        assert!(can_edit(&photo, None, T));
    }

    #[test]
    fn meta_keys_follow_what_the_bubble_shows() {
        let plain = MessageMeta::default();
        let starred = MessageMeta {
            starred: true,
            ..MessageMeta::default()
        };
        assert_eq!(meta_key(None), 0);
        assert_eq!(meta_key(Some(&plain)), meta_key(Some(&plain)));
        assert_ne!(meta_key(Some(&plain)), meta_key(Some(&starred)));
    }

    #[test]
    fn thumbnails_media_lines_and_cards() {
        assert_eq!(
            sniff_format(&[0xFF, 0xD8, 0xFF, 0xE0]),
            Some(ImageFormat::Jpeg)
        );
        assert_eq!(sniff_format(b"\x89PNG\r\n"), Some(ImageFormat::Png));
        assert_eq!(
            sniff_format(b"RIFF\0\0\0\0WEBPVP8 "),
            Some(ImageFormat::Webp)
        );
        assert_eq!(sniff_format(b"nope"), None);
        let wide = MediaMeta {
            width: Some(1600),
            height: Some(900),
            ..MediaMeta::default()
        };
        assert_eq!(thumb_size(Some(&wide)), (240.0, 135.0));
        assert_eq!(thumb_size(None), (180.0, 180.0));
        let doc = MediaMeta {
            file_name: Some("rapor.pdf".into()),
            pages: Some(3),
            ..MediaMeta::default()
        };
        assert_eq!(media_title(MediaKind::Document, Some(&doc)), "rapor.pdf");
        assert_eq!(
            media_details(2048, Some(&doc), "Tap to load"),
            "3 pages · 2 KB · Tap to load"
        );
        let voice = MediaMeta {
            voice_note: true,
            seconds: Some(42),
            ..MediaMeta::default()
        };
        assert_eq!(media_title(MediaKind::Audio, Some(&voice)), "Voice message");
        assert_eq!(media_title(MediaKind::Audio, None), "Audio");
        assert_eq!(media_details(12, Some(&voice), ""), "0:42 · 12 B");
        assert_eq!(
            vcard_phone("BEGIN:VCARD\nFN:Ada\nitem1.TEL;waid=90:+90 532 123 45 67\nEND:VCARD"),
            Some("+90 532 123 45 67".into())
        );
        assert_eq!(vcard_phone("TEL;TYPE=CELL:+1 555"), Some("+1 555".into()));
        assert_eq!(vcard_phone("FN:Ada"), None);
        let location = Location {
            latitude: 41.0082,
            longitude: 28.9784,
            ..Location::default()
        };
        assert_eq!(coordinates(&location), "41.00820, 28.97840");
        assert_eq!(
            preview_of(
                &MessageBody::Unsupported,
                Some(&MessageMeta {
                    location: Some(location),
                    ..MessageMeta::default()
                })
            ),
            "Location"
        );
        assert_eq!(preview_of(&MessageBody::Text("hi".into()), None), "hi");
        assert_eq!(preview_of(&MessageBody::Unsupported, None), "Message");
    }

    #[test]
    fn body_text_labels() {
        assert_eq!(
            body_text(&MessageBody::Media {
                kind: MediaKind::Image,
                caption: Some("sunset".into()),
                mime: "image/jpeg".into(),
                size: 10,
            }),
            "Photo\nsunset"
        );
        assert_eq!(body_text(&MessageBody::Unsupported), "Unsupported message");
    }

    #[test]
    fn media_helpers() {
        assert_eq!(image_format("image/jpeg"), Some(ImageFormat::Jpeg));
        assert_eq!(image_format("image/webp; q=1"), Some(ImageFormat::Webp));
        assert_eq!(image_format("video/mp4"), None);
        assert_eq!(format_size(12), "12 B");
        assert_eq!(format_size(2048), "2 KB");
        assert_eq!(format_size(1_572_864), "1.5 MB");
    }

    #[test]
    fn send_plans() {
        assert_eq!(send_plan("", false), Plan::Nothing);
        assert_eq!(send_plan("  \n", false), Plan::Nothing);
        assert_eq!(send_plan(" hi ", false), Plan::Text);
        assert_eq!(send_plan("", true), Plan::Media { caption: None });
        assert_eq!(send_plan("  ", true), Plan::Media { caption: None });
        assert_eq!(
            send_plan(" look ", true),
            Plan::Media {
                caption: Some("look".into())
            }
        );
    }

    #[test]
    fn chip_labels() {
        assert_eq!(
            attach_chip_label("a.jpg", MediaKind::Image),
            "a.jpg · Photo"
        );
        assert_eq!(
            attach_chip_label("cv.pdf", MediaKind::Document),
            "cv.pdf · Document"
        );
        assert_eq!(
            attach_chip_label("v.mp4", MediaKind::Video),
            "v.mp4 · Video"
        );
    }

    #[test]
    fn day_labels() {
        let day = T - T.rem_euclid(86_400_000);
        assert_eq!(day_label(day, T, 0), "Today");
        assert_eq!(day_label(day - 86_400_000, T, 0), "Yesterday");
        assert_eq!(day_label(day - 3 * 86_400_000, T, 0), "2 Nov 2026");
        assert_eq!(day_label(0, T, 0), "1 Jan 1970");
    }
}
