//! One SMS/MMS conversation: pages from the provider through the backend,
//! optimistic sends, delivery ticks, a SIM chip and a composer. The merge of
//! pages and `sms_status` events is `rovsti_core::sms_timeline::SmsTimeline`;
//! this module is the GPUI shell around it plus a few pure text helpers.

use rovsti_core::text_detect::{self, DetectAction};
use std::{
    collections::{HashMap, HashSet},
    path::PathBuf,
    sync::Arc,
    time::Duration,
};

use gpui::{
    AnyElement, Context, Entity, EventEmitter, InteractiveElement, IntoElement, ListAlignment,
    ParentElement, Render, SharedString, StatefulInteractiveElement, Styled, TextInputAction,
    TextInputPurpose, Window, div, img, list, prelude::*, px,
};
use rovsti_android::{Error as AndroidError, documents};
use rovsti_core::{
    ThreadId,
    chat_export::{self, ExportLine},
    chat_find::{self, FindCursor},
    multi_select::{self, Selection},
    pending_delete::PendingDeletes,
    shared_card::{self, CardView},
    sms::{DeliveryState, SmsSend, SmsSimsPayload, SmsStatus, segments},
    sms_timeline::{SmsItem, SmsKey, SmsTimeline},
    starred::{self, LocalStar, StarredRow},
    unread_jump::{self, JumpState},
    wire::{DocumentOp, DocumentResult},
};
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, glass_pill},
    current_theme,
    field::{TextField, field_box},
    theme,
    tokens::{Theme, radius, typography},
};
use rovsti_wa_ui::photo_avatar;

use crate::text_size::bubble_text;
use crate::{
    backend::{Backend, BackendEvent, now_ms, sim_for},
    contact_ui::SheetAction,
    conversation::{
        HIGHLIGHT_FOR, clock, composer_bar, day_label, day_start_ms, header_bar_with, jump_button,
        quick_reply_row, replies_at, unread_divider_row,
    },
    find_ui::{FindActions, FindState, find_bar},
    inbox::Badge,
    keyed_list::{KeyedList, key_of},
    media_viewer::{ViewerItem, ViewerSource},
    photos::ContactPhotos,
    schedule_ui::{self, StripItem, StripKey},
};

/// Messages per provider page.
const PAGE: usize = 50;

/// The counter shows once a message is this close to a part boundary.
const COUNTER_FROM: u32 = 30;

/// The Spam banner shows for a spam thread with exactly one sender.
fn spam_banner_visible(in_spam: bool, recipients: &[String]) -> bool {
    in_spam && crate::sms_actions::sender_of(recipients).is_some()
}

/// What the conversation tells the page that hosts it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SmsConversationEvent {
    Back,
    /// The header's channel switch: open the person's other thread.
    Switch(ThreadId),
    /// Long-press on Send: the text wants a time (the "Send later" sheet).
    Schedule {
        to: Vec<String>,
        body: String,
        sub_id: Option<i32>,
    },
    /// Cancel on a Scheduled strip row.
    CancelScheduled(StripKey),
    /// The header's bell: mute or unmute this thread's notifications.
    ToggleMute,
    /// The "Add quick replies in Settings" chip: go to the Settings tab.
    OpenSettings,
    /// A copy pill under a selected bubble. `sensitive` hides it from the paste preview.
    Copy {
        text: String,
        sensitive: bool,
    },
    /// A share pill under a selected bubble: the page opens the share sheet.
    Share(String),
    /// The Remind me pill under a selected bubble: the page opens the time sheet.
    Remind {
        id: String,
        at_ms: i64,
        preview: String,
    },
    /// A Call pill of a number found in a bubble.
    Call(String),
    /// A Message pill of a number found in a bubble: start a chat with it.
    Message(String),
    /// An Email pill of an address found in a bubble.
    Email(String),
    /// Add to contacts for a shared contact card.
    AddContactCard(rovsti_core::shared_card::CardView),
    /// The header's more button: the conversation menu.
    Menu,
    /// The Spam banner's "Not spam": allow this sender.
    NotSpam,
    /// A short confirmation for the page's pill, such as "Message deleted".
    Notice(&'static str),
    /// A picture part was tapped: the page opens the full-screen viewer.
    OpenMedia {
        items: Vec<ViewerItem>,
        index: usize,
    },
    /// The header's contact photo was tapped: the page opens it full size.
    OpenAvatar(ViewerItem),
    /// The Forward pill of a message: the page opens the chooser.
    Forward(crate::forward_ui::ForwardItem),
    /// The Copy link pill: the page copies the URL and says "Link copied".
    CopyLink(String),
    /// A copy with its own pill text ("Coordinates copied", "Name copied").
    CopyNotice {
        text: String,
        notice: &'static str,
    },
    /// The Copy date pill: the page copies the text and says "Date copied".
    CopyDate(String),
    /// The Add to calendar pill: the page opens the calendar editor.
    AddToCalendar {
        title: String,
        start_ms: i64,
        end_ms: i64,
        all_day: bool,
    },
    /// Delete on a message without the SMS role: the page offers the role.
    NeedsRole,
    /// Messages were hidden for deletion: the page's pill offers Undo for all of them.
    Deleted(Vec<SmsKey>),
    /// A confirmation with computed text ("Starred 3 messages").
    Toast(String),
    /// The ticked messages as one block: the page copies it and says `notice`.
    CopyBlock {
        text: String,
        notice: String,
    },
}

impl EventEmitter<SmsConversationEvent> for SmsConversation {}

/// The address-book photo behind the header avatar.
struct PeerPhoto {
    photos: Entity<ContactPhotos>,
    uri: SharedString,
    initials: SharedString,
}

/// One item of the timeline list after its "Load earlier" slot.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Entry {
    /// A day starts at the timeline item at this index.
    Day(usize),
    /// The timeline item at this index.
    Message(usize),
    /// The "N unread messages" divider.
    Unread(u32),
}

pub struct SmsConversation {
    backend: Entity<Backend>,
    voice: Entity<crate::voice_ui::VoiceController>,
    /// A negative id is a draft: a new conversation with no provider thread yet.
    thread_id: i64,
    title: SharedString,
    recipients: Vec<String>,
    timeline: SmsTimeline,
    composer: Entity<TextField>,
    /// The composer's action-key count already acted on.
    seen_actions: u64,
    /// The virtualised, bottom-aligned timeline: item 0 is the "Load earlier"
    /// slot, then one item per [`Entry`].
    list: KeyedList,
    /// The text size the list was last measured at.
    measured_text_size: rovsti_core::text_scale::ChatTextSize,
    /// Day breaks and messages in timeline order, rebuilt when `rows_dirty`.
    entries: Vec<Entry>,
    /// The timeline changed since `entries`.
    rows_dirty: bool,
    /// The clock offset `entries` cut days with.
    rows_offset: i32,
    /// "Now" and the offset for the frame being drawn (rows are built after `render`).
    frame_now: i64,
    loading: bool,
    /// The thread sits in the Spam filter.
    in_spam: bool,
    /// The provider changed while a page was loading: read the newest again.
    reload_due: bool,
    error: Option<SharedString>,
    /// A line above the composer ("Couldn't attach that file").
    notice: Option<SharedString>,
    /// The SIM the user picked here; the backend remembers it per thread.
    chosen_sim: Option<i32>,
    /// A picture picked for the next send (a path in the app's cache).
    attachment: Option<String>,
    /// The picked file's own name, for the chip (the cache copy has none).
    attachment_name: Option<String>,
    awaiting_attachment: bool,
    /// `local_id -> (text, image path)` of MMS sends, so a retry resends the picture.
    mms_sends: HashMap<u64, (String, String)>,
    switch: Option<(ThreadId, Badge)>,
    /// Waiting scheduled sends of this thread, set by the page.
    scheduled: Vec<StripItem>,
    strip_expanded: bool,
    /// The quick reply chip row above the composer is open.
    quick_open: bool,
    muted: bool,
    /// The bubble (by `message_key`) whose copy pills are showing.
    selected: Option<u64>,
    /// Select mode: the ticked messages.
    select: Option<Selection<SmsKey>>,
    /// The "Delete n messages?" sheet of select mode is open.
    select_delete: bool,
    /// The "Starred messages" sheet is open.
    starred_open: bool,
    /// Provider ids of this thread's starred messages, refreshed whenever rows are rebuilt.
    star_ids: HashSet<String>,
    /// The open Info or Select text sheet.
    info_sheet: Option<crate::info_ui::InfoSheet>,
    /// The header's copy choices (name, number), while their sheet is open.
    header_sheet: Option<Vec<rovsti_core::copy_choices::CopyChoice>>,
    /// The "Media, links and docs" sheet.
    gallery: Option<crate::gallery_ui::GallerySheet>,
    /// The "View contact" sheet of a shared card.
    card_sheet: Option<CardView>,
    /// vCard parts by file path: `None` while read or when unreadable.
    cards: std::rc::Rc<std::cell::RefCell<HashMap<String, Option<CardView>>>>,
    /// Bumped when a vCard has been read, so its row is measured again.
    cards_rev: u32,
    /// A picture or file is being handed to the "Save as" sheet.
    saving: bool,
    /// An exported chat is at the "Save as" sheet; true when only part of it was loaded.
    exporting: Option<bool>,
    /// The address-book photo of the person, for the header avatar.
    peer_photo: Option<PeerPhoto>,
    /// The "Search in chat" bar.
    find: Option<FindState<SmsKey>>,
    /// The hit that glows for a moment.
    highlight: Option<SmsKey>,
    /// The message a search hit opened the thread for, until it is shown.
    goto: Option<rovsti_core::global_search::JumpTarget>,
    /// Provider id -> badge text of the armed message reminders.
    reminders: std::collections::HashMap<String, String>,
    /// Older pages loaded while working towards `goto`.
    goto_pages: usize,
    /// The "Jump to date" sheet, with the day picked so far.
    date_sheet: Option<rovsti_core::date_jump::DayPick>,
    /// The day being worked towards and the older pages loaded for it.
    date_goto: Option<(rovsti_core::date_jump::DayPick, usize)>,
    /// When the mute ends (header line), if it is timed.
    muted_until: Option<i64>,
    /// The scroll-to-bottom button and its count of new messages.
    jump: JumpState,
    /// Unread count the thread opened with, until the first page decides the divider.
    opening_unread: Option<u32>,
    /// The divider: the first unread message and how many follow it.
    divider: Option<(SmsKey, u32)>,
    /// The message waiting at the "Delete this message?" sheet.
    delete_confirm: Option<SmsKey>,
    /// Confirmed deletes in their Undo window (hidden from the rows).
    pending: PendingDeletes<SmsKey>,
    /// Deletes handed to the backend, still hidden until it answers.
    deleting: Vec<SmsKey>,
    /// The 1 s timer that commits due deletes is running.
    ticking: bool,
    _subscriptions: Vec<gpui::Subscription>,
}

impl SmsConversation {
    pub fn new(
        backend: Entity<Backend>,
        voice: Entity<crate::voice_ui::VoiceController>,
        thread_id: i64,
        title: impl Into<SharedString>,
        recipients: Vec<String>,
        unread: u32,
        cx: &mut Context<Self>,
    ) -> Self {
        let composer = cx.new(|cx| {
            let mut field =
                TextField::with_input(TextInputPurpose::Text, TextInputAction::Send, cx);
            field.set_keep_focus_on_action(true);
            field
        });
        let mut timeline = SmsTimeline::new(thread_id);
        timeline.set_recipients(recipients.clone());
        let subscriptions = vec![
            // Typing re-renders the counter; the keyboard's Send key sends.
            cx.observe(&composer, |this: &mut Self, composer, cx| {
                let actions = composer.read(cx).actions();
                if actions != this.seen_actions {
                    this.seen_actions = actions;
                    this.send(cx);
                }
                cx.notify();
            }),
            // Role, SIMs and the clock live in the backend.
            cx.observe(&backend, |_, _, cx| cx.notify()),
            // The note playing, scrubbed or paused changes its bubble.
            cx.observe(&voice, |this: &mut Self, _, cx| {
                this.rows_dirty = true;
                cx.notify();
            }),
            cx.subscribe(&backend, |this, _, event, cx| this.on_backend(event, cx)),
        ];
        let measured_text_size = backend.read(cx).prefs().chat_text_size;
        let mut this = Self {
            backend,
            voice,
            thread_id,
            title: title.into(),
            recipients,
            timeline,
            composer,
            seen_actions: 0,
            list: KeyedList::new(ListAlignment::Bottom, 1),
            measured_text_size,
            entries: Vec::new(),
            rows_dirty: true,
            rows_offset: 0,
            frame_now: 0,
            loading: false,
            reload_due: false,
            error: None,
            notice: None,
            chosen_sim: None,
            attachment: None,
            attachment_name: None,
            awaiting_attachment: false,
            mms_sends: HashMap::new(),
            switch: None,
            scheduled: Vec::new(),
            strip_expanded: false,
            quick_open: false,
            muted: false,
            in_spam: false,
            selected: None,
            select: None,
            select_delete: false,
            starred_open: false,
            date_sheet: None,
            date_goto: None,
            muted_until: None,
            star_ids: HashSet::new(),
            info_sheet: None,
            header_sheet: None,
            gallery: None,
            card_sheet: None,
            cards: Default::default(),
            cards_rev: 0,
            saving: false,
            exporting: None,
            peer_photo: None,
            find: None,
            highlight: None,
            goto: None,
            reminders: std::collections::HashMap::new(),
            goto_pages: 0,
            jump: JumpState::default(),
            opening_unread: (unread > 0).then_some(unread),
            divider: None,
            delete_confirm: None,
            pending: PendingDeletes::default(),
            deleting: Vec::new(),
            ticking: false,
            _subscriptions: subscriptions,
        };
        this.fill_from_thread(cx);
        this.load(None, cx);
        this
    }

    /// Opens the thread at a search hit: it is shown once loaded (the first
    /// page is still on its way when this runs).
    pub fn set_goto(&mut self, target: Option<rovsti_core::global_search::JumpTarget>) {
        self.goto = target;
    }

    /// Gives the header an avatar: the contact's photo at `uri`, with
    /// `initials` until it has been read.
    pub fn set_peer_photo(
        &mut self,
        photos: Entity<ContactPhotos>,
        uri: SharedString,
        initials: SharedString,
        cx: &mut Context<Self>,
    ) {
        self._subscriptions
            .push(cx.observe(&photos, |_, _, cx| cx.notify()));
        self.peer_photo = Some(PeerPhoto {
            photos,
            uri,
            initials,
        });
        cx.notify();
    }

    /// The header avatar, tappable once the photo has been read.
    fn header_avatar(&self, theme: Theme, cx: &mut Context<Self>) -> Option<gpui::AnyElement> {
        let peer = self.peer_photo.as_ref()?;
        peer.photos
            .update(cx, |photos, cx| photos.want(&peer.uri, cx));
        let photo = peer.photos.read(cx).photo(&peer.uri);
        let item = photo.as_ref().map(|(image, _)| ViewerItem {
            key: "avatar".to_owned(),
            title: self.title.clone(),
            mime: "image/png".to_owned(),
            source: ViewerSource::ContactPhoto {
                uri: peer.uri.to_string(),
            },
            at_ms: 0,
            placeholder: None,
            avatar: Some(Arc::clone(image)),
        });
        let avatar = photo_avatar(
            theme,
            peer.initials.clone(),
            36.0,
            theme::colors(theme).green,
            photo,
        );
        Some(match item {
            Some(item) => avatar
                .id("sms-avatar")
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.emit(SmsConversationEvent::OpenAvatar(item.clone()));
                }))
                .into_any_element(),
            None => avatar.into_any_element(),
        })
    }

    pub fn thread(&self) -> ThreadId {
        ThreadId::Sms(self.thread_id)
    }

    pub fn set_switch(&mut self, switch: Option<(ThreadId, Badge)>, cx: &mut Context<Self>) {
        if self.switch != switch {
            self.switch = switch;
            cx.notify();
        }
    }

    pub fn title(&self) -> SharedString {
        self.title.clone()
    }

    pub fn recipients(&self) -> &[String] {
        &self.recipients
    }

    /// The armed message reminders of this thread as (message id, badge text).
    pub fn set_reminders(&mut self, badges: Vec<(String, String)>, cx: &mut Context<Self>) {
        let badges: std::collections::HashMap<String, String> = badges.into_iter().collect();
        if self.reminders != badges {
            self.reminders = badges;
            self.rows_dirty = true;
            cx.notify();
        }
    }

    pub fn set_scheduled(&mut self, scheduled: Vec<StripItem>, cx: &mut Context<Self>) {
        if self.scheduled != scheduled {
            self.scheduled = scheduled;
            if self.scheduled.is_empty() {
                self.strip_expanded = false;
            }
            cx.notify();
        }
    }

    /// Cancel on the Scheduled strip: the text goes back into an empty
    /// composer so nothing is lost.
    fn cancel_scheduled(&mut self, key: StripKey, cx: &mut Context<Self>) {
        let body = self
            .scheduled
            .iter()
            .find(|i| i.key == key)
            .map(|i| i.body.clone());
        if let Some(body) = body {
            let current = self.composer.read(cx).text().to_owned();
            if let Some(text) = schedule_ui::text_after_cancel(&current, &body) {
                self.composer.update(cx, |field, cx| field.set(&text, cx));
            }
        }
        cx.emit(SmsConversationEvent::CancelScheduled(key));
    }

    pub fn set_in_spam(&mut self, in_spam: bool, cx: &mut Context<Self>) {
        if self.in_spam != in_spam {
            self.in_spam = in_spam;
            cx.notify();
        }
    }

    /// The end of a timed mute for the header line.
    pub fn set_muted_until(&mut self, until: Option<i64>, cx: &mut Context<Self>) {
        if self.muted_until != until {
            self.muted_until = until;
            cx.notify();
        }
    }

    pub fn set_muted(&mut self, muted: bool, cx: &mut Context<Self>) {
        if self.muted != muted {
            self.muted = muted;
            cx.notify();
        }
    }

    /// System Back inside the page: clears the selected bubble; `false` when
    /// nothing was selected.
    pub fn go_back(&mut self, cx: &mut Context<Self>) -> bool {
        if self.select_delete {
            self.select_delete = false;
            cx.notify();
            return true;
        }
        if self.select.is_some() {
            self.end_select();
            cx.notify();
            return true;
        }
        if self.date_sheet.take().is_some() || self.date_goto.take().is_some() {
            cx.notify();
            return true;
        }
        if self.starred_open {
            self.starred_open = false;
            cx.notify();
            return true;
        }
        if self.delete_confirm.take().is_some() {
            cx.notify();
            return true;
        }
        if self.gallery.take().is_some()
            || self.info_sheet.take().is_some()
            || self.header_sheet.take().is_some()
            || self.card_sheet.take().is_some()
        {
            cx.notify();
            return true;
        }
        if self.find.take().is_some() {
            cx.notify();
            return true;
        }
        if self.selected.take().is_none() {
            return false;
        }
        self.rows_dirty = true;
        cx.notify();
        true
    }

    /// "Search in chat" on the page menu: opens the bar and focuses its field.
    pub fn begin_find(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.find.is_none() {
            self.find = Some(FindState::new(Self::find_edited, cx));
        }
        if let Some(find) = &self.find {
            let focus = find.field.read(cx).focus_handle();
            window.focus(&focus, cx);
        }
        cx.notify();
    }

    /// The find field changed: search again from the newest hit.
    fn find_edited(&mut self, cx: &mut Context<Self>) {
        let Some(find) = &mut self.find else {
            return;
        };
        let text = find.field.read(cx).text().to_owned();
        if text == find.query {
            return;
        }
        find.query = text;
        find.anchor = None;
        self.refind(false, true, cx);
    }

    /// Recomputes the hits. With an anchor (a page just arrived) the cursor stays on
    /// that message, `step_older` moving to the next older hit; `reveal` shows it.
    fn refind(&mut self, step_older: bool, reveal: bool, cx: &mut Context<Self>) {
        let Some(find) = &self.find else {
            return;
        };
        let items = self.timeline.items();
        let texts: Vec<String> = items
            .iter()
            .map(|item| copy_text(item).unwrap_or_default())
            .collect();
        let refs: Vec<&str> = texts.iter().map(String::as_str).collect();
        let hits = chat_find::find(&refs, &find.query);
        let keep = find
            .anchor
            .and_then(|key| items.iter().position(|i| i.key == key));
        let cursor = FindCursor::resume(hits, keep, step_older);
        let target = cursor.current().and_then(|at| items.get(at)).map(|i| i.key);
        if let Some(find) = &mut self.find {
            find.cursor = cursor;
            find.anchor = None;
        }
        match target {
            Some(key) if reveal => self.glow(key, cx),
            _ => cx.notify(),
        }
    }

    /// Makes the shown hit the anchor of the next [`Self::refind`].
    fn keep_current_hit(&mut self) {
        let key = self
            .find
            .as_ref()
            .and_then(|f| f.cursor.current())
            .and_then(|at| self.timeline.items().get(at))
            .map(|i| i.key);
        if let Some(find) = &mut self.find {
            find.anchor = key;
        }
    }

    /// Up (older) or down (newer) on the find bar.
    fn find_step(&mut self, older: bool, cx: &mut Context<Self>) {
        let Some(find) = &mut self.find else {
            return;
        };
        let at = if older {
            find.cursor.older()
        } else {
            find.cursor.newer()
        };
        let key = at
            .and_then(|at| self.timeline.items().get(at))
            .map(|i| i.key);
        match key {
            Some(key) => self.glow(key, cx),
            None => cx.notify(),
        }
    }

    /// "Load earlier" on the find bar: one more page, then the next older hit.
    fn find_load_earlier(&mut self, cx: &mut Context<Self>) {
        let Some(oldest) = self.timeline.oldest_ms() else {
            return;
        };
        if self.loading {
            return;
        }
        self.keep_current_hit();
        if let Some(find) = &mut self.find {
            find.asked = true;
        }
        self.load(Some(oldest.saturating_add(1)), cx);
    }

    /// Works towards the message a search hit asked for: shows it, loads one
    /// more older page, or says why it stopped.
    fn continue_goto(&mut self, cx: &mut Context<Self>) {
        use rovsti_core::global_search::{JumpStep, jump_notice, jump_step};
        let Some(target) = self.goto.clone() else {
            return;
        };
        let Some(wanted) = SmsKey::from_provider_id(&target.id) else {
            self.goto = None;
            return;
        };
        let found = self
            .timeline
            .items()
            .iter()
            .find(|i| i.key == wanted || self.timeline.provider_key(i.key) == Some(wanted))
            .map(|i| i.key);
        let oldest = self.timeline.oldest_ms();
        let step = jump_step(
            found.is_some(),
            self.timeline.has_older(),
            self.goto_pages,
            oldest,
            target.at_ms,
        );
        match (step, found, oldest) {
            (JumpStep::Reveal, Some(key), _) => {
                self.goto = None;
                self.glow(key, cx);
            }
            (JumpStep::LoadOlder, _, Some(oldest)) => {
                self.load(Some(oldest.saturating_add(1)), cx);
            }
            (step, _, _) => {
                self.goto = None;
                self.notice = jump_notice(step).map(Into::into);
                cx.notify();
            }
        }
    }

    /// Shows the message and lets it glow for a moment.
    fn glow(&mut self, key: SmsKey, cx: &mut Context<Self>) {
        if self.rows_dirty {
            self.sync_rows();
        }
        let items = self.timeline.items();
        let found = self.entries.iter().position(|entry| {
            matches!(entry, Entry::Message(at) if items.get(*at).is_some_and(|i| i.key == key))
        });
        if let Some(position) = found {
            // Item 0 of the list is the "Load earlier" slot.
            self.list.reveal(position + 1);
        }
        self.highlight = Some(key);
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(HIGHLIGHT_FOR).await;
            this.update(cx, |this, cx| {
                if this.highlight == Some(key) {
                    this.highlight = None;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Unsent composer text worth keeping.
    pub fn draft(&self, cx: &gpui::App) -> String {
        self.composer.read(cx).text().to_owned()
    }

    /// Puts kept text back into the composer.
    pub fn set_draft(&mut self, text: &str, cx: &mut Context<Self>) {
        self.composer.update(cx, |field, cx| field.set(text, cx));
    }

    /// The text was scheduled: the composer is done with it.
    pub fn clear_draft(&mut self, cx: &mut Context<Self>) {
        self.composer.update(cx, |field, cx| field.clear(cx));
    }

    /// Long-press (or right-click) on Send. Only a typed text can wait.
    pub(crate) fn request_schedule(&mut self, cx: &mut Context<Self>) -> bool {
        let body = self.composer.read(cx).text().trim().to_owned();
        if body.is_empty() || !self.backend.read(cx).sms_role_held() {
            return false;
        }
        let sub_id = self.current_sim(cx);
        cx.emit(SmsConversationEvent::Schedule {
            to: self.recipients.clone(),
            body,
            sub_id,
        });
        true
    }

    /// Long-press on Send: schedules, or says why nothing happened.
    fn long_press_schedule(&mut self, cx: &mut Context<Self>) {
        let scheduled = self.request_schedule(cx);
        if crate::schedule_ui::explain_type_first(scheduled, &self.draft(cx)) {
            cx.emit(SmsConversationEvent::Notice(crate::schedule_ui::TYPE_FIRST));
        }
    }

    fn is_draft(&self) -> bool {
        self.thread_id < 0
    }

    fn on_backend(&mut self, event: &BackendEvent, cx: &mut Context<Self>) {
        match event {
            BackendEvent::Status(status) => self.on_status(status, cx),
            // New messages may have arrived: read the newest page again.
            BackendEvent::SmsChanged => {
                self.adopt_thread(cx);
                self.fill_from_thread(cx);
                self.load(None, cx);
            }
            BackendEvent::SmsMessageDeleted { thread_id, key, ok }
                if *thread_id == self.thread_id =>
            {
                self.deleting.retain(|k| k != key);
                if *ok {
                    self.timeline.remove(*key);
                }
                self.rows_dirty = true;
                cx.notify();
            }
            BackendEvent::Document(result) if result.op == DocumentOp::Attachment => {
                self.on_attachment(result, cx);
            }
            // The answer of "Save as" for a picture or file of a message.
            BackendEvent::Document(result)
                if result.op == DocumentOp::ExportMessages && self.saving =>
            {
                self.saving = false;
                let notice = match self.exporting.take() {
                    Some(partial) => chat_export::export_notice(result.ok, partial),
                    None if result.ok => "Saved",
                    None => "Not saved",
                };
                cx.emit(SmsConversationEvent::Notice(notice));
                cx.notify();
            }
            _ => {}
        }
    }

    /// A draft whose first send created the provider thread becomes that
    /// thread, so replies show here and it reads like any other.
    fn adopt_thread(&mut self, cx: &mut Context<Self>) {
        if !self.is_draft() {
            return;
        }
        let [number] = self.recipients.as_slice() else {
            return;
        };
        let found = crate::inbox::thread_for_number(self.backend.read(cx).threads(), number)
            .map(|t| t.thread_id);
        if let Some(thread_id) = found {
            self.thread_id = thread_id;
            self.timeline.adopt_thread(thread_id);
            self.rows_dirty = true;
            // The SIM picked while drafting belongs to the thread now.
            if let Some(sub_id) = self.chosen_sim {
                self.backend.update(cx, |backend, cx| {
                    backend.remember_sim(thread_id, sub_id, cx)
                });
            }
        }
    }

    /// A thread opened before the thread list knew it (a notification tapped
    /// at start) gets its addresses, and a title, once the list has it.
    fn fill_from_thread(&mut self, cx: &mut Context<Self>) {
        if !self.recipients.is_empty() || self.is_draft() {
            return;
        }
        let Some(addresses) = self
            .backend
            .read(cx)
            .threads()
            .iter()
            .find(|t| t.thread_id == self.thread_id)
            .map(|t| t.addresses.clone())
            .filter(|a| !a.is_empty())
        else {
            return;
        };
        if self.title.trim().is_empty() {
            let backend = self.backend.read(cx);
            let names: Vec<String> = addresses
                .iter()
                .map(|a| {
                    backend
                        .contacts()
                        .match_number(a)
                        .map(|c| c.display_name.clone())
                        .filter(|n| !n.trim().is_empty())
                        .unwrap_or_else(|| a.clone())
                })
                .collect();
            self.title = names.join(", ").into();
        }
        self.timeline.set_recipients(addresses.clone());
        self.recipients = addresses;
        cx.notify();
    }

    /// `sms_status` for this thread's sends; other threads' are ignored by the timeline.
    fn on_status(&mut self, status: &SmsStatus, cx: &mut Context<Self>) {
        if self.timeline.apply_status(status) {
            self.rows_dirty = true;
            cx.notify();
        }
    }

    fn on_attachment(&mut self, result: &DocumentResult, cx: &mut Context<Self>) {
        if !std::mem::take(&mut self.awaiting_attachment) {
            return;
        }
        let path = result.path.clone().filter(|_| result.ok);
        match path {
            // SMS carries pictures only; the provider's type says early.
            Some(_) if result.mime.as_deref().is_some_and(|m| !is_image(m)) => {
                self.notice = Some("Only pictures can be sent by SMS".into());
            }
            Some(path) => {
                self.attachment = Some(path);
                self.attachment_name = result.name.clone().filter(|n| !n.trim().is_empty());
                self.notice = None;
            }
            None => {
                let words = if self.backend.read(cx).is_preview() {
                    "Pictures need an Android phone"
                } else {
                    "Couldn't attach that picture (up to 4 MB)"
                };
                self.notice = Some(words.into());
            }
        }
        cx.notify();
    }

    /// Fetches one page (the newest, or the one before `before_ms`) off the UI thread.
    fn load(&mut self, before_ms: Option<i64>, cx: &mut Context<Self>) {
        if self.is_draft() {
            return;
        }
        if self.loading {
            // A change while a page is on its way: read the newest page after it.
            self.reload_due |= before_ms.is_none();
            return;
        }
        self.loading = true;
        let thread_id = self.thread_id;
        let task = self
            .backend
            .read(cx)
            .messages(cx, thread_id, before_ms, PAGE);
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.loading = false;
                match result {
                    Ok(page) => {
                        this.error = None;
                        let first = this.timeline.items().is_empty();
                        let before = this.timeline.items().iter().filter(|i| !i.outgoing).count();
                        this.timeline.apply_page(page);
                        if before_ms.is_none() && !first {
                            let after =
                                this.timeline.items().iter().filter(|i| !i.outgoing).count();
                            this.jump.arrived(unread_jump::newly_arrived(before, after));
                        }
                        this.sync_rows();
                        if this.find.is_some() && before_ms.is_some() {
                            // Asked on the find bar: move on to the next older hit.
                            // Any other page keeps the hit and the scroll position.
                            let asked = this
                                .find
                                .as_mut()
                                .is_some_and(|f| std::mem::take(&mut f.asked));
                            if !asked {
                                this.keep_current_hit();
                            }
                            this.refind(asked, asked, cx);
                        }
                        if before_ms.is_none() && !this.reveal_unread() {
                            this.list.scroll_to_end();
                        }
                        if before_ms.is_some() {
                            this.goto_pages += 1;
                        }
                        this.continue_goto(cx);
                        this.continue_date_goto(cx);
                        // Read only while someone looks: the app in the
                        // background leaves new messages unread.
                        if (first || before_ms.is_none()) && this.backend.read(cx).ui_active() {
                            this.backend
                                .update(cx, |backend, cx| backend.mark_read(thread_id, cx));
                        }
                    }
                    Err(error) => {
                        log::warn!("loading SMS messages failed: {error}");
                        // The detail is for the log; the screen says it plainly.
                        this.error = Some(load_error_text(&error).into());
                        this.goto = None;
                        this.date_goto = None;
                        if let Some(find) = &mut this.find {
                            find.asked = false;
                        }
                    }
                }
                if std::mem::take(&mut this.reload_due) {
                    this.load(None, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The SIM a send uses now: this conversation's pick, else the thread's
    /// remembered one, else the system default.
    fn current_sim(&self, cx: &gpui::App) -> Option<i32> {
        let backend = self.backend.read(cx);
        sim_for(
            backend.sims(),
            self.chosen_sim
                .or_else(|| backend.sim_choice(self.thread_id)),
        )
    }

    fn cycle_sim(&mut self, cx: &mut Context<Self>) {
        let current = self.current_sim(cx);
        let next = next_sim(self.backend.read(cx).sims(), current);
        if let Some(next) = next {
            self.chosen_sim = Some(next);
            let thread = self.thread_id;
            self.backend
                .update(cx, |backend, cx| backend.remember_sim(thread, next, cx));
            cx.notify();
        }
    }

    /// A reply chip was tapped: it goes into the composer, the row closes and
    /// the keyboard stays up.
    fn insert_quick_reply(&mut self, reply: &str, window: &mut Window, cx: &mut Context<Self>) {
        let text = rovsti_core::quick_replies::insert_into(self.composer.read(cx).text(), reply);
        self.composer.update(cx, |field, cx| field.set(&text, cx));
        self.quick_open = false;
        let focus = self.composer.read(cx).focus_handle();
        window.focus(&focus, cx);
        cx.notify();
    }

    fn send(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().trim().to_owned();
        let attachment = self.attachment.clone();
        if !can_send(
            &text,
            attachment.is_some(),
            self.backend.read(cx).sms_role_held(),
        ) {
            return;
        }
        if self.recipients.is_empty() {
            // Opened before the thread list knew it: wait for its addresses.
            self.notice = Some("This conversation is still loading".into());
            cx.notify();
            return;
        }
        self.composer.update(cx, |field, cx| field.clear(cx));
        self.attachment = None;
        self.notice = None;
        self.dispatch(text, attachment, cx);
    }

    /// Sends text typed into a notification as if it came from the composer.
    /// `false` when it could not go (not the default SMS app, no addresses).
    pub fn send_reply(&mut self, text: String, cx: &mut Context<Self>) -> bool {
        let text = text.trim().to_owned();
        let sendable = can_send(&text, false, self.backend.read(cx).sms_role_held())
            && !self.recipients.is_empty();
        if sendable {
            self.dispatch(text, None, cx);
        }
        sendable
    }

    /// On the first page only: puts the unread divider before the first unread
    /// message and shows it instead of the end. False when there is none.
    fn reveal_unread(&mut self) -> bool {
        let Some(unread) = self.opening_unread.take() else {
            return false;
        };
        let items = self.timeline.items();
        let incoming: Vec<bool> = items.iter().map(|i| !i.outgoing).collect();
        let Some(at) = unread_jump::unread_divider(&incoming, unread) else {
            return false;
        };
        let count = incoming[at..].iter().filter(|&&inc| inc).count();
        self.divider = Some((items[at].key, u32::try_from(count).unwrap_or(u32::MAX)));
        self.sync_rows();
        let Some(position) = self
            .entries
            .iter()
            .position(|e| matches!(e, Entry::Unread(_)))
        else {
            return false;
        };
        // Item 0 of the list is the "Load earlier" slot.
        self.list.reveal(position + 1);
        true
    }

    /// Puts the bubble in the timeline and hands the send to the backend.
    fn dispatch(&mut self, text: String, attachment: Option<String>, cx: &mut Context<Self>) {
        let sub_id = self.current_sim(cx);
        let local_id = self
            .backend
            .update(cx, |backend, _| backend.next_local_id());
        let shown = if text.is_empty() {
            "Photo".to_owned()
        } else {
            text.clone()
        };
        self.timeline
            .push_pending(local_id, shown, sub_id, now_ms());
        // Sending answers the thread: the unread divider has done its job.
        self.opening_unread = None;
        self.divider = None;
        self.sync_rows();
        self.list.scroll_to_end();
        let to = self.recipients.clone();
        match attachment {
            Some(path) => {
                self.mms_sends
                    .insert(local_id, (text.clone(), path.clone()));
                self.backend.update(cx, |backend, cx| {
                    backend.send_mms(local_id, to, text, Some(path), sub_id, cx);
                });
            }
            None => {
                let req = SmsSend {
                    local_id,
                    to,
                    body: text,
                    sub_id,
                };
                self.backend
                    .update(cx, |backend, cx| backend.send_sms(req, cx));
            }
        }
        cx.notify();
    }

    fn retry(&mut self, local_id: u64, cx: &mut Context<Self>) {
        self.rows_dirty = true;
        let Some(req) = self.timeline.retry(local_id) else {
            return;
        };
        match self.mms_sends.get(&local_id).cloned() {
            Some((text, path)) => self.backend.update(cx, |backend, cx| {
                backend.send_mms(local_id, req.to, text, Some(path), req.sub_id, cx);
            }),
            None => self
                .backend
                .update(cx, |backend, cx| backend.send_sms(req, cx)),
        }
        cx.notify();
    }

    /// The Delete pill on a failed send: nothing left the phone, so it simply goes.
    fn discard_failed(&mut self, local_id: u64, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        if self.timeline.discard(local_id).is_some() {
            self.mms_sends.remove(&local_id);
            cx.emit(SmsConversationEvent::Notice("Message deleted"));
        }
        cx.notify();
    }

    // ---- select mode and local stars ------------------------------------------

    /// The thread's key in `sms_stars`; `None` for a draft that has no provider thread yet.
    fn star_thread(&self) -> Option<String> {
        (self.thread_id >= 0).then(|| self.thread_id.to_string())
    }

    /// The provider id a star is kept under; `None` for a send with no provider row yet.
    fn star_id(&self, key: SmsKey) -> Option<String> {
        self.timeline.provider_key(key)?.provider_id()
    }

    fn is_starred(&self, key: SmsKey) -> bool {
        self.star_id(key)
            .is_some_and(|id| self.star_ids.contains(&id))
    }

    /// Reads the thread's stars from the preferences.
    fn refresh_stars(&mut self, cx: &gpui::App) {
        self.star_ids = match self.star_thread() {
            Some(thread) => self
                .backend
                .read(cx)
                .prefs()
                .sms_stars
                .rows_for(&thread)
                .into_iter()
                .map(|row| row.id)
                .collect(),
            None => HashSet::new(),
        };
    }

    /// Stars or unstars `keys` (those with a provider id). Returns how many changed hands.
    fn set_stars(&mut self, keys: &[SmsKey], star: bool, cx: &mut Context<Self>) -> usize {
        let Some(thread) = self.star_thread() else {
            return 0;
        };
        let title = self.title.to_string();
        let edits: Vec<(String, Option<LocalStar>)> = keys
            .iter()
            .filter_map(|key| {
                let item = self.timeline.items().iter().find(|i| i.key == *key)?;
                let id = self.star_id(*key)?;
                let value = star.then(|| local_star(item, &thread, &title));
                Some((id, value))
            })
            .collect();
        let count = edits.len();
        if count > 0 {
            self.backend.update(cx, |backend, cx| {
                backend.update_prefs(cx, |prefs| {
                    for (id, value) in &edits {
                        prefs.sms_stars.set(id, value.clone());
                    }
                });
            });
            self.rows_dirty = true;
            cx.notify();
        }
        count
    }

    /// The Star / Unstar pill under one bubble.
    fn star_one(&mut self, key: SmsKey, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        let star = !self.is_starred(key);
        let text = if self.set_stars(&[key], star, cx) == 0 {
            "Can't star that message yet".to_owned()
        } else if star {
            "Starred".to_owned()
        } else {
            "Unstarred".to_owned()
        };
        cx.emit(SmsConversationEvent::Toast(text));
        cx.notify();
    }

    /// "Jump to date" on the page menu: opens the sheet on today.
    pub fn open_date_sheet(&mut self, cx: &mut Context<Self>) {
        let (now, tz) = (self.frame_now, self.backend.read(cx).locale().tz_offset_s);
        self.date_sheet = Some(rovsti_core::date_jump::DayPick::today(now, tz));
        cx.notify();
    }

    fn date_actions() -> crate::date_jump_ui::Actions<Self> {
        crate::date_jump_ui::Actions {
            pick: |this, day, cx| {
                this.date_sheet = Some(day);
                cx.notify();
            },
            go: |this, cx| {
                if let Some(day) = this.date_sheet.take() {
                    this.date_goto = Some((day, 0));
                    this.notice = None;
                    this.continue_date_goto(cx);
                }
                cx.notify();
            },
            close: |this, cx| {
                this.date_sheet = None;
                cx.notify();
            },
        }
    }

    /// Works towards the first message of the picked day: shows it, loads one more
    /// older page, or says why it stopped.
    fn continue_date_goto(&mut self, cx: &mut Context<Self>) {
        use rovsti_core::date_jump::{
            DateStep, date_notice, date_step, later_notice, starts_later,
        };
        let Some((day, pages)) = self.date_goto else {
            return;
        };
        let (now, tz) = (self.frame_now, self.backend.read(cx).locale().tz_offset_s);
        let start = day.start_ms(tz);
        let label = day.label(now, tz);
        let at: Vec<i64> = self.timeline.items().iter().map(|i| i.at_ms).collect();
        match date_step(&at, self.timeline.has_older(), pages, start) {
            DateStep::Reveal(index) => {
                self.date_goto = None;
                let item = &self.timeline.items()[index];
                let (key, at_ms) = (item.key, item.at_ms);
                self.glow(key, cx);
                if starts_later(at_ms, start, tz) {
                    self.notice = Some(later_notice(&label).into());
                }
            }
            DateStep::LoadOlder => {
                if let Some(oldest) = self.timeline.oldest_ms() {
                    if !self.loading {
                        self.date_goto = Some((day, pages + 1));
                    }
                    self.load(Some(oldest.saturating_add(1)), cx);
                } else {
                    self.date_goto = None;
                }
            }
            step => {
                self.date_goto = None;
                if matches!(step, DateStep::Newest) {
                    self.list.scroll_to_end();
                }
                self.notice = date_notice(step, &label).map(Into::into);
            }
        }
        cx.notify();
    }

    /// The Jump to date sheet over the conversation.
    fn date_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let day = self.date_sheet?;
        let tz = self.backend.read(cx).locale().tz_offset_s;
        Some(crate::date_jump_ui::sheet(
            theme,
            day,
            self.frame_now,
            tz,
            Self::date_actions(),
            cx,
        ))
    }

    /// Opens the "Starred messages" sheet (the page menu's row).
    pub fn open_starred(&mut self, cx: &mut Context<Self>) {
        self.starred_open = true;
        self.refresh_stars(cx);
        cx.notify();
    }

    /// The Select pill: enters select mode with this message ticked.
    fn begin_select(&mut self, key: SmsKey, cx: &mut Context<Self>) {
        let mut selection = Selection::new();
        selection.toggle(key);
        self.select = Some(selection);
        self.selected = None;
        self.rows_dirty = true;
        cx.notify();
    }

    fn end_select(&mut self) {
        self.select = None;
        self.select_delete = false;
        self.rows_dirty = true;
    }

    /// A tap or long press on a row in select mode.
    fn toggle_select(&mut self, key: SmsKey, cx: &mut Context<Self>) {
        if !self
            .timeline
            .items()
            .iter()
            .any(|i| i.key == key && selectable(i))
        {
            return;
        }
        if let Some(selection) = &mut self.select
            && !selection.toggle(key)
        {
            cx.emit(SmsConversationEvent::Toast(multi_select::cap_notice()));
        }
        self.rows_dirty = true;
        cx.notify();
    }

    /// "Select all": every loaded message that can be ticked.
    fn select_all_loaded(&mut self, cx: &mut Context<Self>) {
        let keys: Vec<SmsKey> = self
            .timeline
            .items()
            .iter()
            .filter(|i| selectable(i) && !self.is_hidden(i.key))
            .map(|i| i.key)
            .collect();
        let total = keys.len();
        if let Some(selection) = &mut self.select {
            selection.select_all(keys);
            if selection.len() < total {
                cx.emit(SmsConversationEvent::Toast(multi_select::cap_notice()));
            }
        }
        self.rows_dirty = true;
        cx.notify();
    }

    /// Drops ticks of messages that left the timeline or are being deleted.
    fn prune_selection(&mut self) {
        if let Some(selection) = &mut self.select {
            let live: HashSet<SmsKey> = self
                .timeline
                .items()
                .iter()
                .filter(|i| !(self.pending.contains(&i.key) || self.deleting.contains(&i.key)))
                .map(|i| i.key)
                .collect();
            selection.retain(|key| live.contains(key));
        }
    }

    /// The ticked messages in timeline order.
    fn selected_items(&self) -> Vec<&SmsItem> {
        let Some(selection) = &self.select else {
            return Vec::new();
        };
        self.timeline
            .items()
            .iter()
            .filter(|i| selection.contains(&i.key) && !self.is_hidden(i.key))
            .collect()
    }

    /// The ticked messages with text as one block, as the chat export writes them.
    fn select_block(&self, cx: &gpui::App) -> Option<String> {
        let title = self.title.to_string();
        let items = self.selected_items();
        let texts: Vec<Option<String>> = items.iter().map(|i| copy_text(i)).collect();
        let lines: Vec<ExportLine<'_>> = items
            .iter()
            .zip(&texts)
            .filter_map(|(item, text)| {
                Some(ExportLine {
                    at_ms: item.at_ms,
                    author: if item.outgoing { "You" } else { &title },
                    text: text.as_deref()?,
                })
            })
            .collect();
        if lines.is_empty() {
            return None;
        }
        let offset = self.backend.read(cx).locale().tz_offset_s;
        Some(multi_select::copy_block(&lines, offset))
    }

    fn select_copy(&mut self, cx: &mut Context<Self>) {
        let n = self.selected_items().len();
        match self.select_block(cx) {
            Some(text) => cx.emit(SmsConversationEvent::CopyBlock {
                text,
                notice: multi_select::copied_notice(n),
            }),
            None => cx.emit(SmsConversationEvent::Toast("Nothing to copy".into())),
        }
        self.end_select();
        cx.notify();
    }

    fn select_forward(&mut self, cx: &mut Context<Self>) {
        use rovsti_core::forward::ForwardBody;
        let single = match self.selected_items().as_slice() {
            [one] => forward_item(one, &self.title).filter(|f| f.picture.is_some()),
            _ => None,
        };
        let item = single.or_else(|| {
            self.select_block(cx)
                .map(|text| crate::forward_ui::ForwardItem {
                    body: ForwardBody::Text(text),
                    picture: None,
                })
        });
        match item {
            Some(item) => cx.emit(SmsConversationEvent::Forward(item)),
            None => cx.emit(SmsConversationEvent::Toast("Nothing to forward".into())),
        }
        self.end_select();
        cx.notify();
    }

    fn select_star(&mut self, cx: &mut Context<Self>) {
        let keys: Vec<SmsKey> = self.selected_items().iter().map(|i| i.key).collect();
        let states: Vec<bool> = keys.iter().map(|k| self.is_starred(*k)).collect();
        let star = multi_select::star_all(&states);
        let done = self.set_stars(&keys, star, cx);
        let text = if done == 0 {
            "Can't star those messages yet".to_owned()
        } else {
            multi_select::starred_notice(done, star)
        };
        cx.emit(SmsConversationEvent::Toast(text));
        self.end_select();
        cx.notify();
    }

    /// The Delete action of the bar: without the SMS role the page offers it, else the
    /// confirm sheet opens.
    fn select_ask_delete(&mut self, cx: &mut Context<Self>) {
        let backend = self.backend.read(cx);
        if !backend.sms_role_held() && !backend.is_preview() {
            cx.emit(SmsConversationEvent::NeedsRole);
        } else {
            self.select_delete = true;
        }
        cx.notify();
    }

    /// "Delete" on the sheet: every ticked message is hidden and waits out one shared
    /// Undo window.
    fn select_delete_now(&mut self, cx: &mut Context<Self>) {
        let keys: Vec<SmsKey> = self.selected_items().iter().map(|i| i.key).collect();
        self.end_select();
        if keys.is_empty() {
            cx.notify();
            return;
        }
        let now = now_ms();
        for key in &keys {
            self.pending.add(*key, now);
        }
        cx.emit(SmsConversationEvent::Deleted(keys));
        self.start_ticking(cx);
        cx.notify();
    }

    /// Whether `key` is hidden: waiting out its Undo window or being deleted.
    fn is_hidden(&self, key: SmsKey) -> bool {
        self.pending.contains(&key) || self.deleting.contains(&key)
    }

    /// The Delete pill: without the SMS role the page offers it; otherwise
    /// the confirm sheet opens.
    fn ask_delete(&mut self, key: SmsKey, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        let backend = self.backend.read(cx);
        if !backend.sms_role_held() && !backend.is_preview() {
            cx.emit(SmsConversationEvent::NeedsRole);
        } else {
            self.delete_confirm = Some(key);
        }
        cx.notify();
    }

    /// "Delete" on the sheet: the message is hidden and waits out the Undo window.
    fn confirm_delete(&mut self, cx: &mut Context<Self>) {
        let Some(key) = self.delete_confirm.take() else {
            return;
        };
        self.pending.add(key, now_ms());
        self.rows_dirty = true;
        cx.emit(SmsConversationEvent::Deleted(vec![key]));
        self.start_ticking(cx);
        cx.notify();
    }

    /// The pill's Undo: the message comes back. False when it was already committed.
    pub fn undo_delete(&mut self, key: SmsKey, cx: &mut Context<Self>) -> bool {
        let undone = self.pending.undo(&key);
        if undone {
            self.rows_dirty = true;
            cx.notify();
        }
        undone
    }

    /// Hands deletes to the backend; they stay hidden until it answers.
    fn commit_deletes(&mut self, keys: Vec<SmsKey>, cx: &mut Context<Self>) {
        let thread_id = self.thread_id;
        for key in keys {
            // A send of ours linked to a provider row deletes that row.
            match self.timeline.provider_key(key) {
                Some(provider) => {
                    self.deleting.push(key);
                    self.backend.update(cx, |backend, cx| {
                        backend.delete_sms_message(thread_id, provider, cx);
                    });
                }
                None => {
                    self.timeline.remove(key);
                }
            }
        }
        self.rows_dirty = true;
        cx.notify();
    }

    /// Commits everything still waiting (leaving the page, the app pausing).
    pub fn commit_all_deletes(&mut self, cx: &mut Context<Self>) {
        let keys = self.pending.drain();
        if !keys.is_empty() {
            self.commit_deletes(keys, cx);
        }
    }

    /// Runs a 1 s timer that commits due deletes while any are pending.
    fn start_ticking(&mut self, cx: &mut Context<Self>) {
        if self.ticking {
            return;
        }
        self.ticking = true;
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_secs(1)).await;
                let go_on = this
                    .update(cx, |this, cx| {
                        let due = this.pending.due(now_ms());
                        if !due.is_empty() {
                            this.commit_deletes(due, cx);
                        }
                        let more = this.pending.has_pending();
                        this.ticking = more;
                        more
                    })
                    .unwrap_or(false);
                if !go_on {
                    break;
                }
            }
        })
        .detach();
    }

    /// A copy pill was tapped: the page copies it and the selection closes.
    fn copy(&mut self, text: String, sensitive: bool, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        cx.emit(SmsConversationEvent::Copy { text, sensitive });
        cx.notify();
    }

    /// A share pill was tapped: the page opens the share sheet and the selection closes.
    fn share(&mut self, text: String, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        cx.emit(SmsConversationEvent::Share(text));
        cx.notify();
    }

    /// A pill for a number or address found in a bubble was tapped.
    fn detect_action(&mut self, action: DetectAction, cx: &mut Context<Self>) {
        match action {
            DetectAction::Call(n) => {
                self.selected = None;
                self.rows_dirty = true;
                cx.emit(SmsConversationEvent::Call(text_detect::dial(&n)));
                cx.notify();
            }
            DetectAction::Message(number) => {
                self.selected = None;
                self.rows_dirty = true;
                cx.emit(SmsConversationEvent::Message(number));
                cx.notify();
            }
            DetectAction::CopyNumber(t) | DetectAction::CopyEmail(t) => self.copy(t, false, cx),
            DetectAction::Email(a) => {
                self.selected = None;
                self.rows_dirty = true;
                cx.emit(SmsConversationEvent::Email(a));
                cx.notify();
            }
        }
    }

    /// An open-link pill was tapped: the browser gets the URL and the selection closes.
    fn open_link(&mut self, url: &str, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        cx.open_url(url);
        cx.notify();
    }

    /// The provider id of a timeline item in the gallery: `Sms(12)`, or
    /// `Mms(7)#1` for part 1 of an MMS.
    fn gallery_id(key: SmsKey, part: Option<usize>) -> String {
        match part {
            Some(n) => format!("{key:?}#{n}"),
            None => format!("{key:?}"),
        }
    }

    /// The timeline item and part a gallery id names.
    fn gallery_target(&self, id: &str) -> Option<(usize, Option<usize>)> {
        let (key, part) = match id.split_once('#') {
            Some((key, part)) => (key, part.parse::<usize>().ok()),
            None => (id, None),
        };
        let at = self
            .timeline
            .items()
            .iter()
            .position(|i| Self::gallery_id(i.key, None) == key)?;
        Some((at, part))
    }

    /// "Media, links and docs" on the page menu: opens the sheet.
    pub fn open_gallery(&mut self, cx: &mut Context<Self>) {
        self.gallery = Some(self.build_gallery());
        cx.notify();
    }

    /// The gallery of the loaded messages: picture parts are Media, other
    /// files Docs, and links come from the bodies.
    fn build_gallery(&self) -> crate::gallery_ui::GallerySheet {
        use crate::gallery_ui::{base_name, kind_of_mime};
        use rovsti_core::chat_gallery::{self, GalleryInput};
        struct Owned {
            id: String,
            at_ms: i64,
            kind: Option<chat_gallery::GalleryKind>,
            text: Option<String>,
            name: Option<String>,
        }
        let mut owned: Vec<Owned> = Vec::new();
        for item in self.timeline.items() {
            if !item.body.trim().is_empty() {
                owned.push(Owned {
                    id: Self::gallery_id(item.key, None),
                    at_ms: item.at_ms,
                    kind: None,
                    text: Some(item.body.clone()),
                    name: None,
                });
            }
            for (n, part) in item.parts.iter().enumerate() {
                let (Some(kind), Some(path)) = (kind_of_mime(&part.content_type), &part.path)
                else {
                    continue;
                };
                owned.push(Owned {
                    id: Self::gallery_id(item.key, Some(n)),
                    at_ms: item.at_ms,
                    kind: Some(kind),
                    text: None,
                    name: Some(base_name(path).to_owned()),
                });
            }
        }
        let inputs: Vec<GalleryInput<'_>> = owned
            .iter()
            .map(|o| GalleryInput {
                id: &o.id,
                at_ms: o.at_ms,
                kind: o.kind,
                text: o.text.as_deref(),
                file_name: o.name.as_deref(),
                size: None,
            })
            .collect();
        crate::gallery_ui::GallerySheet::new(
            chat_gallery::build(&inputs),
            self.timeline.has_older(),
            self.timeline.items().len(),
        )
    }

    /// A tap inside the gallery sheet.
    fn gallery_action(&mut self, action: crate::gallery_ui::GalleryAction, cx: &mut Context<Self>) {
        use crate::gallery_ui::GalleryAction as A;
        match action {
            A::Close => self.gallery = None,
            A::Tab(tab) => {
                if let Some(sheet) = &mut self.gallery {
                    sheet.tab = tab;
                }
            }
            A::Media(id) => {
                self.gallery = None;
                match self.gallery_target(&id) {
                    Some((at, Some(part)))
                        if self.timeline.items()[at]
                            .parts
                            .get(part)
                            .is_some_and(|p| is_image(&p.content_type) && p.path.is_some()) =>
                    {
                        let (items, index) =
                            viewer_items(self.timeline.items(), &self.title, at, part);
                        if !items.is_empty() {
                            cx.emit(SmsConversationEvent::OpenMedia { items, index });
                        }
                    }
                    Some((at, _)) => {
                        let key = self.timeline.items()[at].key;
                        self.glow(key, cx);
                    }
                    None => {}
                }
            }
            A::Open(url) => cx.open_url(&url),
            A::CopyLink(url) => cx.emit(SmsConversationEvent::CopyLink(url)),
            A::Jump(id) => {
                self.gallery = None;
                if let Some((at, _)) = self.gallery_target(&id) {
                    let key = self.timeline.items()[at].key;
                    self.glow(key, cx);
                }
            }
            A::Older => {
                if !self.loading
                    && let Some(oldest) = self.timeline.oldest_ms()
                {
                    self.load(Some(oldest.saturating_add(1)), cx);
                }
            }
        }
        cx.notify();
    }

    /// The Media, links and docs sheet over the conversation.
    fn gallery_overlay(&mut self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let stale = self
            .gallery
            .as_ref()
            .is_some_and(|g| g.built_for != self.timeline.items().len());
        if stale {
            let tab = self.gallery.as_ref().map(|g| g.tab).unwrap_or_default();
            let mut fresh = self.build_gallery();
            fresh.tab = tab;
            self.gallery = Some(fresh);
        }
        let sheet = self.gallery.as_ref()?;
        let offset = self.backend.read(cx).locale().tz_offset_s;
        let now = now_ms();
        let this = cx.entity().downgrade();
        let dispatch: crate::gallery_ui::Dispatch = std::rc::Rc::new(move |action, _, app| {
            this.update(app, |this, cx| this.gallery_action(action, cx))
                .ok();
        });
        Some(crate::gallery_ui::render_gallery(
            theme,
            sheet,
            self.loading,
            |ms| {
                format!(
                    "{} {}",
                    day_label(day_start_ms(ms, offset), now, offset),
                    clock(ms, offset)
                )
            },
            offset,
            |id| {
                let part = self
                    .gallery_target(id)
                    .and_then(|(at, part)| self.timeline.items()[at].parts.get(part?).cloned());
                let video = part.as_ref().is_some_and(|p| {
                    p.content_type
                        .trim()
                        .to_ascii_lowercase()
                        .starts_with("video/")
                });
                let source = part
                    .filter(|p| is_image(&p.content_type))
                    .and_then(|p| p.path)
                    .map(|path| PathBuf::from(path).into());
                crate::gallery_ui::Thumb { source, video }
            },
            dispatch,
        ))
    }

    /// The loaded messages as the text of an export or a share.
    fn chat_text(&self, cx: &gpui::App) -> String {
        let title = self.title();
        let lines: Vec<ExportLine<'_>> = self
            .timeline
            .items()
            .iter()
            .map(|item| ExportLine {
                at_ms: item.at_ms,
                author: if item.outgoing { "You" } else { &title },
                text: &item.body,
            })
            .collect();
        let offset = self.backend.read(cx).locale().tz_offset_s;
        chat_export::export_text(&title, &lines, offset)
    }

    /// "Share as text" of the page menu: the loaded messages go to the share sheet.
    pub fn share_chat(&mut self, cx: &mut Context<Self>) {
        let text = self.chat_text(cx);
        cx.emit(SmsConversationEvent::Share(text));
    }

    /// "Export chat" of the page menu: writes the loaded messages to a .txt in the cache and
    /// hands it to "Save as".
    pub fn export_chat(&mut self, cx: &mut Context<Self>) {
        if self.saving {
            cx.emit(SmsConversationEvent::Notice("Already saving a file"));
            return;
        }
        let Some(dir) = self
            .backend
            .read(cx)
            .data_dir()
            .map(|d| d.join("cache").join("export"))
        else {
            cx.emit(SmsConversationEvent::Notice(
                "The app's storage is not ready yet.",
            ));
            return;
        };
        let offset = self.backend.read(cx).locale().tz_offset_s;
        let name =
            chat_export::export_file_name(&self.title(), crate::conversation::now_ms(), offset);
        let text = self.chat_text(cx);
        let path = dir.join(&name);
        self.saving = true;
        self.exporting = Some(self.timeline.has_older());
        let task = cx.background_executor().spawn(async move {
            std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
            std::fs::write(&path, text).map_err(|e| e.to_string())?;
            Ok::<_, String>(
                documents::export_file(&name, "text/plain", &path.to_string_lossy())
                    .map_err(|e| matches!(e, AndroidError::Unsupported)),
            )
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                let (notice, started) = crate::conversation::save_outcome(result);
                if !started {
                    this.saving = false;
                    this.exporting = None;
                }
                if let Some(notice) = notice {
                    cx.emit(SmsConversationEvent::Notice(notice));
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Hands one picture or file of a message to the "Save as" sheet.
    fn save_part(&mut self, part: SavePart, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        if self.saving {
            cx.emit(SmsConversationEvent::Notice("Already saving a file"));
            cx.notify();
            return;
        }
        self.saving = true;
        let task = cx.background_executor().spawn(async move {
            documents::export_file(&part.name, &part.mime, &part.path)
                .map_err(|e| matches!(e, AndroidError::Unsupported))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                let (notice, started) = crate::conversation::save_outcome(Ok(result));
                if !started {
                    this.saving = false;
                }
                if let Some(notice) = notice {
                    cx.emit(SmsConversationEvent::Notice(notice));
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Hands one file of a message to the share sheet.
    fn share_part(&mut self, part: SavePart, cx: &mut Context<Self>) {
        self.selected = None;
        self.select = None;
        self.rows_dirty = true;
        let task = cx.background_executor().spawn(async move {
            rovsti_android::media::share_file(&part.path, &part.mime, &part.name)
                .map_err(|e| matches!(e, AndroidError::Unsupported))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |_, cx| {
                if let Some(notice) = crate::conversation::share_outcome(Ok(result)) {
                    cx.emit(SmsConversationEvent::Notice(notice));
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// The card of a received vCard part, once read. The first call starts
    /// reading the file (at most 64 KB) in the background.
    fn card_of(&self, path: &str, cx: &mut Context<Self>) -> Option<CardView> {
        if let Some(entry) = self.cards.borrow().get(path) {
            return entry.clone();
        }
        self.cards.borrow_mut().insert(path.to_owned(), None);
        let owned = path.to_owned();
        let task = cx.background_executor().spawn({
            let owned = owned.clone();
            async move { crate::contact_ui::read_vcf(&owned) }
        });
        cx.spawn(async move |this, cx| {
            let card = task.await;
            this.update(cx, |this, cx| {
                if card.is_some() {
                    this.cards.borrow_mut().insert(owned, card);
                    this.cards_rev = this.cards_rev.wrapping_add(1);
                    this.rows_dirty = true;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        None
    }

    /// A tap inside the "View contact" sheet.
    fn card_sheet_action(&mut self, action: SheetAction, cx: &mut Context<Self>) {
        let Some(card) = self.card_sheet.clone() else {
            return;
        };
        match action {
            SheetAction::Close => self.card_sheet = None,
            SheetAction::Call(n) => cx.emit(SmsConversationEvent::Call(n)),
            SheetAction::Message(n) => cx.emit(SmsConversationEvent::Message(n)),
            SheetAction::Email(a) => cx.emit(SmsConversationEvent::Email(a)),
            SheetAction::Copy(text) => cx.emit(SmsConversationEvent::Copy {
                text,
                sensitive: false,
            }),
            SheetAction::Add => cx.emit(SmsConversationEvent::AddContactCard(card)),
            SheetAction::Share => {
                cx.emit(SmsConversationEvent::Share(shared_card::share_text(&card)));
            }
            SheetAction::SaveVcf => {
                self.card_sheet = None;
                self.save_card(&card, cx);
            }
        }
        cx.notify();
    }

    /// Writes the card to a .vcf in the cache and hands it to "Save as".
    fn save_card(&mut self, card: &CardView, cx: &mut Context<Self>) {
        if self.saving {
            cx.emit(SmsConversationEvent::Notice("Already saving a file"));
            return;
        }
        let Some(dir) = self.backend.read(cx).data_dir().map(|d| d.join("cache")) else {
            cx.emit(SmsConversationEvent::Notice(
                "The app's storage is not ready yet.",
            ));
            return;
        };
        self.saving = true;
        self.exporting = None;
        let card = card.clone();
        let path = dir.join(shared_card::cache_file_name(&card.name));
        let task = cx.background_executor().spawn(async move {
            crate::contact_ui::write_vcf(&dir, &path, &card)?;
            Ok::<_, String>(
                documents::export_file(
                    &shared_card::vcf_file_name(&card.name),
                    "text/x-vcard",
                    &path.to_string_lossy(),
                )
                .map_err(|e| matches!(e, AndroidError::Unsupported)),
            )
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                let (notice, started) = crate::conversation::save_outcome(result);
                if !started {
                    this.saving = false;
                }
                if let Some(notice) = notice {
                    cx.emit(SmsConversationEvent::Notice(notice));
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn pick_attachment(&mut self, cx: &mut Context<Self>) {
        self.awaiting_attachment = true;
        self.notice = None;
        self.backend
            .update(cx, |backend, cx| backend.pick_attachment(cx));
    }

    /// Rebuilds the day breaks and message entries for `offset` and tells the
    /// list which items changed. Runs only after a change, never per frame.
    fn sync_rows_at(&mut self, offset: i32) {
        self.rows_dirty = false;
        self.rows_offset = offset;
        let items = self.timeline.items();
        let starts = day_starts(items, offset, |item| self.is_hidden(item.key));
        let mut entries = Vec::with_capacity(items.len() + starts.len());
        let mut next_start = starts.iter().peekable();
        for (index, item) in items.iter().enumerate() {
            if self.is_hidden(item.key) {
                continue;
            }
            if next_start.next_if(|&&at| at == index).is_some() {
                entries.push(Entry::Day(index));
            }
            if let Some((_, count)) = self.divider.as_ref().filter(|(key, _)| *key == item.key) {
                entries.push(Entry::Unread(*count));
            }
            entries.push(Entry::Message(index));
        }
        let day = |ms: i64| (ms.div_euclid(1000) + i64::from(offset)).div_euclid(86_400);
        // The "Load earlier" slot keeps one key: it is measured while on screen.
        let keys = std::iter::once(key_of(0u8))
            .chain(entries.iter().map(|entry| match *entry {
                Entry::Day(index) => key_of((1u8, day(items[index].at_ms))),
                Entry::Unread(count) => key_of((3u8, count)),
                Entry::Message(index) => {
                    let base = message_key(&items[index]);
                    let picked = self.select.as_ref().map(|s| s.contains(&items[index].key));
                    let starred = self.is_starred(items[index].key);
                    let reminder = self
                        .star_id(items[index].key)
                        .and_then(|id| self.reminders.get(&id));
                    key_of((
                        base,
                        self.selected == Some(base),
                        picked,
                        starred,
                        reminder,
                        self.cards_rev,
                    ))
                }
            }))
            .collect();
        self.list.sync(keys);
        self.entries = entries;
    }

    fn sync_rows(&mut self) {
        self.sync_rows_at(self.rows_offset);
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
        let offset = self.rows_offset;
        let items = self.timeline.items();
        let content = match index.checked_sub(1).map(|i| self.entries.get(i).copied()) {
            None => self.top_slot(theme, cx),
            Some(Some(Entry::Day(at))) => match items.get(at) {
                Some(item) => div()
                    .flex()
                    .justify_center()
                    .py(px(6.0))
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(day_label(item.at_ms, now, offset))
                    .into_any_element(),
                None => div().into_any_element(),
            },
            Some(Some(Entry::Unread(count))) => unread_divider_row(theme, count),
            Some(Some(Entry::Message(at))) => match items.get(at) {
                Some(item) => self.bubble(theme, item, now, offset, at, cx),
                None => div().into_any_element(),
            },
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
            div()
                .id("sms-older")
                .flex()
                .justify_center()
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.accent)
                .child(if self.loading {
                    "Loading…"
                } else {
                    "Load earlier"
                })
                .on_click(cx.listener(|this, _, _, cx| {
                    if let Some(oldest) = this.timeline.oldest_ms() {
                        // +1 re-reads rows that share the oldest stamp; the
                        // timeline dedups them by key.
                        this.load(Some(oldest.saturating_add(1)), cx);
                    }
                }))
        });
        let error = self.error.clone().map(|error| {
            div()
                .id("sms-load-error")
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.red)
                .child(error)
                .on_click(cx.listener(|this, _, _, cx| {
                    this.error = None;
                    this.load(None, cx);
                    cx.notify();
                }))
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
        let spam =
            spam_banner_visible(self.in_spam, &self.recipients).then(|| {
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .gap(px(8.0))
                    .py(px(8.0))
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child("This conversation is in Spam."),
                    )
                    .child(glass_pill(theme, "Not spam").id("sms-not-spam").on_click(
                        cx.listener(|_, _, _, cx| cx.emit(SmsConversationEvent::NotSpam)),
                    ))
            });
        div()
            .flex()
            .flex_col()
            .gap(px(4.0))
            .children(spam)
            .children(older)
            .children(error)
            .children(empty)
            .into_any_element()
    }

    /// The Info rows of a message.
    fn info_rows(&self, item: &SmsItem, cx: &gpui::App) -> Vec<rovsti_core::message_info::InfoRow> {
        use rovsti_core::message_info::{InfoInput, rows};
        let backend = self.backend.read(cx);
        let sim = item.sub_id.and_then(|id| {
            backend
                .sims()
                .sims
                .iter()
                .find(|s| s.sub_id == id)
                .map(|s| s.label.clone())
        });
        let kinds: Vec<&str> = item
            .parts
            .iter()
            .filter(|p| {
                let t = p.content_type.trim().to_ascii_lowercase();
                !t.starts_with("text/") && !t.starts_with("application/smil")
            })
            .map(|p| crate::info_ui::mime_label(&p.content_type))
            .collect();
        let media = (!kinds.is_empty()).then(|| kinds.join(", "));
        let text = copy_text(item);
        let author = (!item.outgoing).then(|| self.title.to_string());
        let input = InfoInput {
            channel: Some(crate::info_ui::sms_channel(
                item.key,
                !item.parts.is_empty(),
            )),
            at_ms: item.at_ms,
            outgoing: item.outgoing,
            state: crate::info_ui::sms_state(item.outgoing, item.state),
            author: author.as_deref(),
            sim: sim.as_deref(),
            text: text.as_deref(),
            media: media.as_deref(),
            ..InfoInput::default()
        };
        rows(&input, backend.locale())
    }

    /// "Delete this message?" over the conversation.
    fn delete_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        self.delete_confirm?;
        let colors = theme::colors(theme);
        let cancel = |this: &mut Self, cx: &mut Context<Self>| {
            this.delete_confirm = None;
            cx.notify();
        };
        let items = vec![
            crate::menu_item(
                theme,
                "sms-del-yes",
                Icon::LucideTrash2,
                "Delete".into(),
                colors.red,
            )
            .on_click(cx.listener(|this, _, _, cx| this.confirm_delete(cx)))
            .into_any_element(),
            crate::menu_item(
                theme,
                "sms-del-no",
                Icon::LucideX,
                "Cancel".into(),
                colors.label,
            )
            .on_click(cx.listener(move |this, _, _, cx| cancel(this, cx)))
            .into_any_element(),
        ];
        Some(crate::menu_card(
            theme,
            "sms-delete-menu",
            "Delete this message? It is removed from this phone.".into(),
            items,
            cx.listener(move |this, _, _, cx| cancel(this, cx)),
        ))
    }

    /// Select mode's bar over the conversation.
    fn select_bar(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        use crate::select_ui::{BarAction, BarEnds, select_bar};
        let items = self.selected_items();
        let n = items.len();
        let states: Vec<bool> = items.iter().map(|i| self.is_starred(i.key)).collect();
        let star = multi_select::star_all(&states);
        let ends = BarEnds {
            close: Box::new(cx.listener(|this, _, _, cx| {
                this.end_select();
                cx.notify();
            })),
            select_all: Box::new(cx.listener(|this, _, _, cx| this.select_all_loaded(cx))),
        };
        let actions = if n == 0 {
            Vec::new()
        } else {
            vec![
                BarAction {
                    id: "sms-sel-copy",
                    icon: Icon::LucideCopy,
                    label: "Copy".into(),
                    danger: false,
                    on_click: Box::new(cx.listener(|this, _, _, cx| this.select_copy(cx))),
                },
                BarAction {
                    id: "sms-sel-forward",
                    icon: Icon::LucideForward,
                    label: "Forward".into(),
                    danger: false,
                    on_click: Box::new(cx.listener(|this, _, _, cx| this.select_forward(cx))),
                },
                BarAction {
                    id: "sms-sel-star",
                    icon: Icon::LucideStar,
                    label: if star { "Star" } else { "Unstar" }.into(),
                    danger: false,
                    on_click: Box::new(cx.listener(|this, _, _, cx| this.select_star(cx))),
                },
                BarAction {
                    id: "sms-sel-delete",
                    icon: Icon::LucideTrash2,
                    label: "Delete".into(),
                    danger: true,
                    on_click: Box::new(cx.listener(|this, _, _, cx| this.select_ask_delete(cx))),
                },
            ]
        };
        select_bar(theme, multi_select::title(n), ends, actions)
    }

    /// "Delete n messages?" for select mode.
    fn select_delete_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        if !self.select_delete {
            return None;
        }
        let colors = theme::colors(theme);
        let n = self.selected_items().len();
        let cancel = |this: &mut Self, cx: &mut Context<Self>| {
            this.select_delete = false;
            cx.notify();
        };
        let items = vec![
            crate::menu_item(
                theme,
                "sms-seldel-yes",
                Icon::LucideTrash2,
                "Delete".into(),
                colors.red,
            )
            .on_click(cx.listener(|this, _, _, cx| this.select_delete_now(cx)))
            .into_any_element(),
            crate::menu_item(
                theme,
                "sms-seldel-no",
                Icon::LucideX,
                "Cancel".into(),
                colors.label,
            )
            .on_click(cx.listener(move |this, _, _, cx| cancel(this, cx)))
            .into_any_element(),
        ];
        let title = if n == 1 {
            "Delete 1 message? It is removed from this phone.".to_owned()
        } else {
            format!("Delete {n} messages? They are removed from this phone.")
        };
        Some(crate::menu_card(
            theme,
            "sms-select-delete-menu",
            title.into(),
            items,
            cx.listener(move |this, _, _, cx| cancel(this, cx)),
        ))
    }

    /// Jumps to a starred message: the sheet closes and the page works towards it.
    fn jump_to_star(&mut self, row: &StarredRow, cx: &mut Context<Self>) {
        self.starred_open = false;
        self.goto = Some(rovsti_core::global_search::JumpTarget {
            id: row.id.clone(),
            at_ms: row.at_ms,
        });
        self.goto_pages = 0;
        self.continue_goto(cx);
        cx.notify();
    }

    /// Unstars by provider id (the sheet's rows).
    fn unstar_id(&mut self, id: &str, cx: &mut Context<Self>) {
        let id = id.to_owned();
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |prefs| {
                prefs.sms_stars.set(&id, None);
            });
        });
        self.rows_dirty = true;
        cx.emit(SmsConversationEvent::Toast("Unstarred".into()));
        cx.notify();
    }

    /// The "Starred messages" sheet over the conversation.
    fn starred_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        if !self.starred_open {
            return None;
        }
        let colors = theme::colors(theme);
        let rows = self
            .star_thread()
            .map(|thread| self.backend.read(cx).prefs().sms_stars.rows_for(&thread))
            .unwrap_or_default();
        let now = self.frame_now;
        let offset = self.rows_offset;
        let mut items: Vec<AnyElement> = Vec::new();
        if rows.is_empty() {
            items.push(
                div()
                    .px(px(16.0))
                    .py(px(12.0))
                    .text_size(typography::BODY.size)
                    .text_color(colors.secondary_label)
                    .child(starred::empty_text(false))
                    .into_any_element(),
            );
        } else {
            let list: Vec<AnyElement> = rows
                .into_iter()
                .enumerate()
                .map(|(index, row)| {
                    let (unstar_id, hold_id) = (row.id.clone(), row.id.clone());
                    let when = format!(
                        "{} {}",
                        day_label(day_start_ms(row.at_ms, offset), now, offset),
                        clock(row.at_ms, offset)
                    );
                    let author = row.author.clone();
                    let preview = row.preview.clone();
                    let target = row;
                    div()
                        .flex()
                        .items_center()
                        .gap(px(8.0))
                        .child(
                            div()
                                .id(("sms-starred-row", index))
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .flex_col()
                                .px(px(16.0))
                                .py(px(8.0))
                                .min_h(rovsti_ui::tokens::metrics::MIN_HIT)
                                .rounded(px(14.0))
                                .hover(|s| s.bg(colors.label.opacity(0.06)))
                                .child(
                                    div()
                                        .flex()
                                        .justify_between()
                                        .gap(px(8.0))
                                        .text_size(typography::CAPTION.size)
                                        .text_color(colors.secondary_label)
                                        .child(div().truncate().child(author))
                                        .child(div().flex_none().child(when)),
                                )
                                .child(
                                    div()
                                        .truncate()
                                        .text_size(typography::BODY.size)
                                        .text_color(colors.label)
                                        .child(preview),
                                )
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.jump_to_star(&target, cx);
                                }))
                                .on_long_press(cx.listener(move |this, _, _, cx| {
                                    this.unstar_id(&hold_id, cx);
                                })),
                        )
                        .child(
                            div()
                                .id(("sms-starred-unstar", index))
                                .flex_none()
                                .flex()
                                .items_center()
                                .justify_center()
                                .size(rovsti_ui::tokens::metrics::MIN_HIT)
                                .child(
                                    gpui::svg()
                                        .path(Icon::LucideStar.path())
                                        .size(px(20.0))
                                        .text_color(colors.accent),
                                )
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.unstar_id(&unstar_id, cx);
                                })),
                        )
                        .into_any_element()
                })
                .collect();
            items.push(
                div()
                    .id("sms-starred-list")
                    .flex()
                    .flex_col()
                    .max_h(px(420.0))
                    .overflow_y_scroll()
                    .children(list)
                    .into_any_element(),
            );
        }
        items.push(
            crate::menu_item(
                theme,
                "sms-starred-close",
                Icon::LucideX,
                "Close".into(),
                colors.label,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.starred_open = false;
                cx.notify();
            }))
            .into_any_element(),
        );
        Some(crate::menu_card(
            theme,
            "sms-starred-sheet",
            "Starred messages".into(),
            items,
            cx.listener(|this, _, _, cx| {
                this.starred_open = false;
                cx.notify();
            }),
        ))
    }

    /// Title hold: the sheet of copy choices (name, number).
    fn open_header_sheet(&mut self, cx: &mut Context<Self>) {
        let number = match self.recipients.as_slice() {
            [one] => Some(one.as_str()),
            _ => None,
        };
        let choices = rovsti_core::copy_choices::header_choices(&self.title, number, None);
        if choices.is_empty() {
            cx.emit(SmsConversationEvent::Notice("Nothing to copy"));
        } else {
            self.selected = None;
            self.rows_dirty = true;
            self.header_sheet = Some(choices);
        }
        cx.notify();
    }

    /// The header's copy choices over the conversation.
    fn header_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<gpui::AnyElement> {
        let choices = self.header_sheet.as_ref()?;
        let close = cx.listener(|this, _, _, cx| {
            this.header_sheet = None;
            cx.notify();
        });
        let page = cx.entity();
        Some(crate::info_ui::choices_overlay(
            theme,
            choices,
            close,
            move |i| {
                let page = page.clone();
                Box::new(move |_, _, cx| {
                    page.update(cx, |this, cx| {
                        let choice = this.header_sheet.take().and_then(|c| c.into_iter().nth(i));
                        if let Some(choice) = choice {
                            cx.emit(SmsConversationEvent::CopyNotice {
                                text: choice.text,
                                notice: choice.notice,
                            });
                        }
                        cx.notify();
                    });
                })
            },
        ))
    }

    /// The Info or Select text sheet over the conversation.
    fn info_overlay(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let sheet = self.info_sheet.as_ref()?;
        let close = cx.listener(|this, _, _, cx| {
            this.info_sheet = None;
            cx.notify();
        });
        let copy_info = cx.listener(|this, _, _, cx| {
            if let Some(sheet) = this.info_sheet.take() {
                cx.emit(SmsConversationEvent::Copy {
                    text: sheet.info_text(),
                    sensitive: false,
                });
            }
            cx.notify();
        });
        let copy_selection = cx.listener(|this, _, window, cx| {
            let Some(sheet) = this.info_sheet.as_ref() else {
                return;
            };
            match sheet.selected(window) {
                Some(text) => {
                    this.info_sheet = None;
                    cx.emit(SmsConversationEvent::Copy {
                        text,
                        sensitive: false,
                    });
                }
                None => cx.emit(SmsConversationEvent::Notice(crate::info_ui::SELECT_FIRST)),
            }
            cx.notify();
        });
        let copy_all = cx.listener(|this, _, _, cx| {
            if let Some(sheet) = this.info_sheet.take()
                && let Some(text) = sheet.text
            {
                cx.emit(SmsConversationEvent::Copy {
                    text: text.to_string(),
                    sensitive: false,
                });
            }
            cx.notify();
        });
        Some(crate::info_ui::info_overlay(
            theme,
            sheet,
            close,
            copy_info,
            copy_selection,
            copy_all,
        ))
    }

    fn bubble(
        &self,
        theme: Theme,
        item: &SmsItem,
        now: i64,
        offset: i32,
        index: usize,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let (body_size, body_line) = bubble_text(self.backend.read(cx).prefs().chat_text_size);
        let colors = theme::colors(theme);
        let own = item.outgoing;
        let failed = item.state == DeliveryState::Failed && own;
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
        let fill = if self.highlight == Some(item.key) {
            colors.orange.opacity(if own { 0.9 } else { 0.35 })
        } else {
            fill
        };
        let meta_color = if failed {
            if own { gpui::white() } else { colors.red }
        } else {
            quiet
        };
        let retry = failed_local(item);
        let meta: SharedString = if retry.is_some() {
            "Not sent · Tap to retry".into()
        } else {
            meta_line(item, now, offset).into()
        };

        let mut bubble = div()
            .id(("sms-msg", index))
            .max_w(px(290.0))
            .px(px(12.0))
            .py(px(7.0))
            .rounded(radius::CARD)
            .bg(fill)
            .flex()
            .flex_col()
            .gap(px(4.0));
        if failed {
            bubble = bubble.border_1().border_color(colors.red);
        }
        for (part_index, part) in item.parts.iter().enumerate() {
            if let Some(path) = part
                .path
                .as_deref()
                .filter(|_| is_image(&part.content_type))
            {
                let tapped = (index, part_index);
                bubble = bubble.child(
                    img(PathBuf::from(path))
                        .id(("sms-image", index * 64 + part_index))
                        .max_w(px(240.0))
                        .max_h(px(320.0))
                        .rounded(radius::CARD)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            if this.selected.is_some() || this.select.is_some() {
                                // An open selection is closed by the bubble's own tap.
                                return;
                            }
                            cx.stop_propagation();
                            let (items, at) = viewer_items(
                                this.timeline.items(),
                                &this.title,
                                tapped.0,
                                tapped.1,
                            );
                            if !items.is_empty() {
                                cx.emit(SmsConversationEvent::OpenMedia { items, index: at });
                            }
                        })),
                );
            } else if let Some(path) = part.path.as_deref().filter(|_| {
                part.content_type
                    .trim()
                    .to_ascii_lowercase()
                    .starts_with("audio/")
            }) {
                // Save stays in the selection strip (`saveable_parts`).
                let speed = self.backend.read(cx).prefs().voice_speed;
                bubble = bubble.child(crate::voice_ui::player_row(
                    &self.voice,
                    &format!("{index}-{part_index}"),
                    format!("mms:{path}"),
                    crate::voice_ui::VoiceSource::File(PathBuf::from(path)),
                    0,
                    speed,
                    text,
                    quiet,
                    cx,
                ));
            } else if let Some(path) = part
                .path
                .as_deref()
                .filter(|_| shared_card::is_vcard_mime(&part.content_type))
            {
                match self.card_of(path, cx) {
                    Some(card) => {
                        let tapped = card.clone();
                        bubble = bubble.child(
                            div()
                                .id(("sms-card", index * 64 + part_index))
                                .child(crate::conversation::contact_card(theme, &card, text, quiet))
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    if this.selected.is_some() || this.select.is_some() {
                                        return;
                                    }
                                    cx.stop_propagation();
                                    this.card_sheet = Some(tapped.clone());
                                    cx.notify();
                                })),
                        );
                    }
                    None => {
                        bubble = bubble.child(
                            div()
                                .text_size(typography::SUBHEADLINE.size)
                                .text_color(quiet)
                                .child("Contact card"),
                        );
                    }
                }
            } else if part.text.is_none() {
                bubble = bubble.child(
                    div()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(quiet)
                        .child(part_label(&part.content_type)),
                );
            }
        }
        if !item.body.is_empty() {
            bubble = bubble.child(
                div()
                    .text_size(body_size)
                    .line_height(body_line)
                    .text_color(text)
                    .child(item.body.clone()),
            );
        }
        let starred = self.is_starred(item.key);
        let badge = self
            .star_id(item.key)
            .and_then(|id| self.reminders.get(&id).cloned());
        bubble = bubble.child(
            div()
                .self_end()
                .flex()
                .items_center()
                .gap(px(3.0))
                .text_size(typography::CAPTION.size)
                .text_color(meta_color)
                .when(starred, |row| {
                    row.child(
                        gpui::svg()
                            .path(Icon::LucideStar.path())
                            .size(px(10.0))
                            .text_color(meta_color),
                    )
                })
                .when_some(badge, |row, badge| {
                    row.child(
                        gpui::svg()
                            .path(Icon::LucideAlarmClock.path())
                            .size(px(11.0))
                            .text_color(meta_color),
                    )
                    .child(badge)
                })
                .child(meta),
        );
        let key = message_key(item);
        let has_files = !saveable_parts(item).is_empty();
        if self.select.is_some() {
            // The row's own tap toggles the tick.
        } else if let Some(local_id) = retry {
            bubble = bubble
                .on_click(cx.listener(move |this, _, _, cx| this.retry(local_id, cx)))
                .on_long_press(cx.listener(move |this, _, _, cx| {
                    this.selected = Some(key);
                    this.rows_dirty = true;
                    cx.notify();
                }));
        } else if selectable(item) {
            bubble = bubble
                .on_click(cx.listener(move |this, _, _, cx| {
                    // A tap on a picture opens the selection so it can be saved.
                    if this.selected.take().is_some() {
                        this.rows_dirty = true;
                        cx.notify();
                    } else if has_files {
                        this.selected = Some(key);
                        this.rows_dirty = true;
                        cx.notify();
                    }
                }))
                .on_long_press(cx.listener(move |this, _, _, cx| {
                    this.selected = Some(key);
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
        if self.selected == Some(key)
            && let Some(local_id) = retry
        {
            let mut pills = div().flex().flex_wrap().gap(px(6.0)).px(px(4.0));
            pills = pills.child(
                glass_pill(theme, "Retry")
                    .id(("sms-retry", index))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.selected = None;
                        this.retry(local_id, cx);
                    })),
            );
            if let Some(text) = copy_text(item) {
                pills = pills.child(glass_pill(theme, "Copy").id(("sms-copy", index)).on_click(
                    cx.listener(move |this, _, _, cx| {
                        this.copy(text.clone(), false, cx);
                    }),
                ));
            }
            pills = pills.child(
                glass_pill(theme, "Delete")
                    .id(("sms-delete", index))
                    .text_color(colors.red)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.discard_failed(local_id, cx);
                    })),
            );
            column = column.child(pills);
        } else if self.selected == Some(key) && selectable(item) {
            let mut pills = div().flex().flex_wrap().gap(px(6.0)).px(px(4.0));
            if let Some(text) = copy_text(item) {
                let shared = text.clone();
                pills = pills.child(glass_pill(theme, "Copy").id(("sms-copy", index)).on_click(
                    cx.listener(move |this, _, _, cx| {
                        this.copy(text.clone(), false, cx);
                    }),
                ));
                pills = pills.child(
                    glass_pill(theme, "Share")
                        .id(("sms-share", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.share(shared.clone(), cx);
                        })),
                );
            }
            if let Some(forward) = forward_item(item, &self.title) {
                pills = pills.child(
                    glass_pill(theme, "Forward")
                        .id(("sms-forward", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.emit(SmsConversationEvent::Forward(forward.clone()));
                            cx.notify();
                        })),
                );
            }
            if let Some(text) = copy_text(item)
                && crate::info_ui::worth_selecting(&text)
            {
                pills = pills.child(
                    glass_pill(theme, "Select text")
                        .id(("sms-select", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            this.info_sheet = Some(crate::info_ui::InfoSheet::select(text.clone()));
                            cx.notify();
                        })),
                );
            }
            let key = item.key;
            if let Some(provider_id) = self.star_id(key) {
                let at_ms = item.at_ms;
                let preview = copy_text(item).unwrap_or_default();
                pills = pills.child(
                    glass_pill(theme, "Remind me")
                        .id(("sms-remind", index))
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .child(
                            gpui::svg()
                                .path(Icon::LucideAlarmClock.path())
                                .size(px(14.0))
                                .text_color(colors.label),
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.emit(SmsConversationEvent::Remind {
                                id: provider_id.clone(),
                                at_ms,
                                preview: preview.clone(),
                            });
                            cx.notify();
                        })),
                );
            }
            if self.star_id(key).is_some() {
                let label = if starred { "Unstar" } else { "Star" };
                pills = pills.child(
                    glass_pill(theme, label)
                        .id(("sms-star", index))
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .child(
                            gpui::svg()
                                .path(Icon::LucideStar.path())
                                .size(px(14.0))
                                .text_color(colors.label),
                        )
                        .on_click(cx.listener(move |this, _, _, cx| this.star_one(key, cx))),
                );
            }
            pills = pills.child(
                glass_pill(theme, "Select")
                    .id(("sms-pick", index))
                    .on_click(cx.listener(move |this, _, _, cx| this.begin_select(key, cx))),
            );
            pills = pills.child(
                glass_pill(theme, "Delete")
                    .id(("sms-del-one", index))
                    .text_color(colors.red)
                    .on_click(cx.listener(move |this, _, _, cx| this.ask_delete(key, cx))),
            );
            let sheet_rows = self.info_rows(item, cx);
            pills = pills.child(glass_pill(theme, "Info").id(("sms-info", index)).on_click(
                cx.listener(move |this, _, _, cx| {
                    this.selected = None;
                    this.rows_dirty = true;
                    this.info_sheet = Some(crate::info_ui::InfoSheet::info(
                        "Message info",
                        sheet_rows.clone(),
                    ));
                    cx.notify();
                }),
            ));
            let files = saveable_parts(item);
            let total = files.len();
            for (n, part) in files.into_iter().enumerate() {
                let label = save_label(&part.mime, total, n + 1);
                pills = pills.child(
                    glass_pill(theme, label)
                        .id(("sms-save", index * 100 + n))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.save_part(part.clone(), cx);
                        })),
                );
            }
            let files = saveable_parts(item);
            for (n, part) in files.iter().cloned().enumerate() {
                let label = share_label(total, n + 1);
                pills = pills.child(
                    glass_pill(theme, label)
                        .id(("sms-share-file", index * 100 + n))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.share_part(part.clone(), cx);
                        })),
                );
            }
            for (n, part) in files.iter().enumerate() {
                if !shared_card::is_vcard_mime(&part.mime) {
                    continue;
                }
                let Some(card) = self.card_of(&part.path, cx) else {
                    continue;
                };
                let view = card.clone();
                pills = pills.child(
                    glass_pill(theme, "View contact")
                        .id(("sms-view-card", index * 100 + n))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            this.card_sheet = Some(view.clone());
                            cx.notify();
                        })),
                );
                pills = pills.child(
                    glass_pill(theme, "Add to contacts")
                        .id(("sms-add-card", index * 100 + n))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.emit(SmsConversationEvent::AddContactCard(card.clone()));
                            cx.notify();
                        })),
                );
            }
            for (n, link) in rovsti_core::spam::open_links(&item.body, 3)
                .into_iter()
                .enumerate()
            {
                pills = pills.child(
                    glass_pill(theme, link.label)
                        .id(("sms-link", index * 10 + n))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.open_link(&link.url, cx);
                        })),
                );
            }
            let place = rovsti_core::location_link::find_places(&item.body, 1).pop();
            if let Some(place) = &place {
                let (lat, lon, label) = (place.lat, place.lon, place.label.clone());
                let coords = rovsti_core::location_link::coords_text(lat, lon);
                pills = pills.child(
                    glass_pill(theme, "Open in Maps")
                        .id(("sms-place-open", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.open_url(&rovsti_core::location_link::geo_url(
                                lat,
                                lon,
                                label.as_deref(),
                            ));
                            cx.notify();
                        })),
                );
                pills = pills.child(
                    glass_pill(theme, "Copy coordinates")
                        .id(("sms-place-copy", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.emit(SmsConversationEvent::CopyNotice {
                                text: coords.clone(),
                                notice: "Coordinates copied",
                            });
                            cx.notify();
                        })),
                );
            }
            if let Some(link) = rovsti_core::spam::open_links(&item.body, 1)
                .into_iter()
                .next()
                && !place
                    .as_ref()
                    .is_some_and(|p| crate::conversation::place_is_link(&item.body, p, &link.url))
            {
                pills = pills.child(
                    glass_pill(theme, "Copy link")
                        .id(("sms-copy-link", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.emit(SmsConversationEvent::CopyLink(link.url.clone()));
                            cx.notify();
                        })),
                );
            }
            if let Some(found) = rovsti_core::date_detect::detect(
                &item.body,
                now_ms(),
                self.backend.read(cx).locale().tz_offset_s,
                1,
            )
            .into_iter()
            .next()
            {
                let title = rovsti_core::date_detect::event_title(&self.title, &item.body);
                let matched = rovsti_core::date_detect::matched_text(&item.body, &found).to_owned();
                let end_ms = rovsti_core::date_detect::calendar_end_ms(&found);
                pills = pills.child(
                    glass_pill(theme, format!("Add to calendar · {}", found.label))
                        .id(("sms-add-calendar", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.emit(SmsConversationEvent::AddToCalendar {
                                title: title.clone(),
                                start_ms: found.start_ms,
                                end_ms,
                                all_day: found.all_day,
                            });
                            cx.notify();
                        })),
                );
                pills = pills.child(
                    glass_pill(theme, "Copy date")
                        .id(("sms-copy-date", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected = None;
                            this.rows_dirty = true;
                            cx.emit(SmsConversationEvent::CopyDate(matched.clone()));
                            cx.notify();
                        })),
                );
            }
            let own: Vec<&str> = self.recipients.iter().map(String::as_str).collect();
            for (n, action) in text_detect::actions(&item.body, 2, &own)
                .into_iter()
                .enumerate()
            {
                pills = pills.child(
                    glass_pill(theme, action.label())
                        .id(("sms-detect", index * 10 + n))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.detect_action(action.clone(), cx);
                        })),
                );
            }
            if let Some(code) = copy_code(item) {
                pills = pills.child(
                    glass_pill(theme, "Copy code")
                        .id(("sms-copy-code", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.copy(code.clone(), true, cx);
                        })),
                );
            }
            column = column.child(pills);
        }
        let row = div()
            .flex()
            .when(own, |row| row.justify_end())
            .child(column);
        let Some(selection) = &self.select else {
            return row.into_any_element();
        };
        if !selectable(item) {
            return row.opacity(0.5).into_any_element();
        }
        let picked = selection.contains(&item.key);
        let (toggle, hold) = (item.key, item.key);
        div()
            .id(("sms-sel", index))
            .flex()
            .items_center()
            .gap(px(8.0))
            .py(px(2.0))
            .px(px(4.0))
            .rounded(radius::CARD)
            .when(picked, |r| r.bg(colors.accent.opacity(0.15)))
            .child(crate::select_ui::check_mark(theme, picked))
            .child(div().flex_1().min_w_0().child(row))
            .on_click(cx.listener(move |this, _, _, cx| this.toggle_select(toggle, cx)))
            .on_long_press(cx.listener(move |this, _, _, cx| this.toggle_select(hold, cx)))
            .into_any_element()
    }
}

impl Render for SmsConversation {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let now = now_ms();
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let text_size = self.backend.read(cx).prefs().chat_text_size;
        if text_size != self.measured_text_size {
            // Bubbles change height: measure every row again.
            self.measured_text_size = text_size;
            self.rows_dirty = true;
            self.list.remeasure_all();
        }
        let (locale, role_held, sims) = {
            let backend = self.backend.read(cx);
            (
                backend.locale(),
                backend.sms_role_held(),
                backend.sims().clone(),
            )
        };
        let offset = locale.tz_offset_s;

        self.frame_now = now;
        if self.rows_dirty || self.rows_offset != offset {
            self.refresh_stars(cx);
            self.prune_selection();
            self.sync_rows_at(offset);
        }
        let timeline = list(
            self.list.state().clone(),
            cx.processor(|this, index: usize, window, cx| this.list_item(index, window, cx)),
        )
        .flex_1()
        .min_h_0()
        .w_full();
        self.jump.scrolled(self.list.at_end());
        let jump = jump_button(
            theme,
            &self.jump,
            "sms-jump",
            cx.listener(|this, _, _, cx| {
                this.list.scroll_to_end();
                this.jump = JumpState::default();
                cx.notify();
            }),
        );
        let timeline = div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col()
            .child(timeline)
            .children(jump);

        let back = glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
            .id("sms-back")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(SmsConversationEvent::Back)))
            .into_any_element();
        let switch = self.switch.clone().map(|(thread, badge)| {
            glass_pill(theme, badge.label())
                .id("sms-switch")
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.emit(SmsConversationEvent::Switch(thread.clone()));
                }))
                .into_any_element()
        });
        let more = glass_icon_button(theme, Icon::LucideEllipsis, 0.0, false)
            .id("sms-more")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(SmsConversationEvent::Menu)))
            .into_any_element();
        let mute = glass_icon_button(theme, Icon::LucideBellOff, 0.0, self.muted)
            .id("sms-mute")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(SmsConversationEvent::ToggleMute)))
            .into_any_element();
        let trailing = div()
            .flex()
            .items_center()
            .gap(px(8.0))
            .children(switch)
            .child(mute)
            .child(more)
            .into_any_element();
        let avatar = self.header_avatar(theme, cx);
        let hold: crate::conversation::TitleHold = {
            let page = cx.entity();
            Box::new(move |_, cx| page.update(cx, |this, cx| this.open_header_sheet(cx)))
        };
        let header = if self.select.is_some() {
            self.select_bar(theme, cx)
        } else {
            header_bar_with(
                theme,
                back,
                avatar,
                self.title.clone(),
                rovsti_core::mute::header_line(self.muted_until, now, locale.tz_offset_s)
                    .or_else(|| header_subtitle(&self.title, &self.recipients)),
                Some(trailing),
                Some(hold),
            )
            .into_any_element()
        };
        let find_row = self.find.as_ref().map(|find| {
            find_bar(
                theme,
                find,
                self.timeline.has_older(),
                self.loading,
                &FindActions {
                    older: |this: &mut Self, cx| this.find_step(true, cx),
                    newer: |this: &mut Self, cx| this.find_step(false, cx),
                    close: |this: &mut Self, cx| {
                        this.find = None;
                        cx.notify();
                    },
                    load_earlier: Self::find_load_earlier,
                },
                window,
                cx,
            )
        });
        let strip = schedule_ui::strip(
            theme,
            &self.scheduled,
            self.strip_expanded,
            (now, offset),
            cx,
            |this: &mut Self, key, cx| this.cancel_scheduled(key, cx),
            |this: &mut Self, cx| {
                this.strip_expanded = true;
                cx.notify();
            },
        );

        let current_sim = self.current_sim(cx);
        let text = self.composer.read(cx).text().to_owned();
        let mut info = div().flex().items_center().gap(px(8.0)).px(px(16.0));
        let mut has_info = false;
        if sim_chip_visible(&sims) {
            has_info = true;
            info = info.child(
                glass_pill(theme, sim_chip_label(&sims, current_sim))
                    .id("sms-sim")
                    .h(px(30.0))
                    .px(px(12.0))
                    .text_size(typography::FOOTNOTE.size)
                    .on_click(cx.listener(|this, _, _, cx| this.cycle_sim(cx))),
            );
        }
        if let Some(path) = self.attachment.as_deref() {
            has_info = true;
            let label = self
                .attachment_name
                .clone()
                .unwrap_or_else(|| attachment_label(path));
            info = info.child(
                glass_pill(theme, format!("{label} ✕"))
                    .id("sms-attached")
                    .h(px(30.0))
                    .px(px(12.0))
                    .text_size(typography::FOOTNOTE.size)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.attachment = None;
                        cx.notify();
                    })),
            );
        } else if let Some(counter) = segment_label(&text) {
            has_info = true;
            info = info.child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(counter),
            );
        }
        if self.date_goto.is_some() {
            has_info = true;
            info = info.child(
                div()
                    .id("date-progress")
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child("Loading older messages…")
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.date_goto = None;
                        cx.notify();
                    })),
            );
        }
        if let Some(notice) = self.notice.clone() {
            has_info = true;
            info = info.child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.red)
                    .child(notice),
            );
        }

        let bottom: AnyElement = if role_held {
            let field = div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(field_box(
                    theme,
                    "sms-field",
                    &self.composer,
                    "Text message",
                    window,
                    cx,
                ))
                .into_any_element();
            let attach = glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
                .id("sms-attach")
                .on_click(cx.listener(|this, _, _, cx| this.pick_attachment(cx)))
                .into_any_element();
            let send = glass_icon_button(theme, Icon::LucideArrowUp, 0.0, false)
                .id("sms-send")
                .on_click(cx.listener(|this, _, _, cx| this.send(cx)))
                .on_long_press(cx.listener(|this, _, _, cx| this.long_press_schedule(cx)))
                .on_mouse_down(
                    gpui::MouseButton::Right,
                    cx.listener(|this, _, _, cx| this.long_press_schedule(cx)),
                )
                .into_any_element();
            let zap = glass_icon_button(theme, Icon::LucideZap, 0.0, self.quick_open)
                .id("sms-quick")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.quick_open = !this.quick_open;
                    cx.notify();
                }))
                .into_any_element();
            let leading = div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .child(attach)
                .child(zap)
                .into_any_element();
            let bar = composer_bar(keyboard, Some(leading), field, send);
            let quick = self.quick_open.then(|| {
                let replies = self.backend.read(cx).prefs().quick_replies.clone();
                quick_reply_row(theme, "sms-quick-row", &replies, |pick: Option<usize>| {
                    Box::new(cx.listener(move |this, _, window, cx| {
                        let Some(index) = pick else {
                            cx.emit(SmsConversationEvent::OpenSettings);
                            return;
                        };
                        let Some(reply) = replies_at(&this.backend, index, cx) else {
                            return;
                        };
                        this.insert_quick_reply(&reply, window, cx);
                    }))
                })
            });
            div()
                .flex_none()
                .flex()
                .flex_col()
                .children(quick)
                .child(bar)
                .into_any_element()
        } else {
            div()
                .flex_none()
                .flex()
                .justify_center()
                .px(px(12.0))
                .pt(px(8.0))
                .pb(px(12.0) + keyboard)
                .child(
                    glass_pill(theme, "Make default to reply")
                        .id("sms-role")
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.backend
                                .update(cx, |backend, cx| backend.request_sms_role(cx));
                        })),
                )
                .into_any_element()
        };

        let info_sheet = self.info_overlay(theme, cx);
        let header_sheet = self.header_overlay(theme, cx);
        let card_sheet = self
            .card_sheet
            .as_ref()
            .map(|card| crate::contact_ui::card_overlay(theme, card, cx, Self::card_sheet_action));
        let gallery_sheet = self.gallery_overlay(theme, cx);
        let delete_sheet = self.delete_overlay(theme, cx);
        let select_delete_sheet = self.select_delete_overlay(theme, cx);
        let starred_sheet = self.starred_overlay(theme, cx);
        let date_sheet = self.date_overlay(theme, cx);
        div()
            .size_full()
            .relative()
            .flex()
            .flex_col()
            .child(header)
            .children(find_row)
            .child(timeline)
            .children(strip)
            .children(has_info.then_some(info))
            .child(bottom)
            .children(info_sheet)
            .children(header_sheet)
            .children(card_sheet)
            .children(gallery_sheet)
            .children(delete_sheet)
            .children(select_delete_sheet)
            .children(starred_sheet)
            .children(date_sheet)
    }
}

// ---- pure helpers -----------------------------------------------------------

/// The star kept for a message: who wrote it and a one-line preview.
fn local_star(item: &SmsItem, thread: &str, title: &str) -> LocalStar {
    let preview = copy_text(item)
        .or_else(|| item.parts.first().map(|p| part_label(&p.content_type)))
        .unwrap_or_default();
    LocalStar {
        thread_id: thread.to_owned(),
        at_ms: item.at_ms,
        author: if item.outgoing { "You" } else { title }.to_owned(),
        preview,
    }
}

/// The key of a bubble: everything that changes what it shows or how tall it is.
fn message_key(item: &SmsItem) -> u64 {
    let parts: Vec<(&str, Option<&str>, bool)> = item
        .parts
        .iter()
        .map(|p| (p.content_type.as_str(), p.path.as_deref(), p.text.is_some()))
        .collect();
    key_of((
        2u8,
        item.key,
        item.outgoing,
        &item.body,
        parts,
        item.at_ms,
        item.state,
    ))
}

/// The SIM chip shows only when there is a choice to make.
pub fn sim_chip_visible(sims: &SmsSimsPayload) -> bool {
    sims.sims.len() > 1
}

/// The chip's text: the SIM's label, or a prompt while Android would pick.
pub fn sim_chip_label(sims: &SmsSimsPayload, current: Option<i32>) -> String {
    current
        .and_then(|id| sims.sims.iter().find(|s| s.sub_id == id))
        .map_or_else(|| "Choose SIM".to_owned(), |s| s.label.clone())
}

/// The SIM after `current` in slot order (wraps); the first when none is set.
pub fn next_sim(sims: &SmsSimsPayload, current: Option<i32>) -> Option<i32> {
    let at = current.and_then(|id| sims.sims.iter().position(|s| s.sub_id == id));
    let next = at.map_or(0, |i| (i + 1) % sims.sims.len().max(1));
    sims.sims.get(next).map(|s| s.sub_id)
}

/// The text under a bubble's body: the time, then the delivery tick.
pub fn meta_line(item: &SmsItem, now_ms: i64, offset_s: i32) -> String {
    let time = if (0..60_000).contains(&(now_ms - item.at_ms)) {
        "Now".to_owned()
    } else {
        clock(item.at_ms, offset_s)
    };
    match tick(item.outgoing, item.state) {
        "" => time,
        tick if item.state == DeliveryState::Pending => tick.to_owned(),
        tick => format!("{time} {tick}"),
    }
}

/// Delivery state as shown: sending, one tick sent, two delivered, not sent.
/// Incoming messages and states the provider does not report show nothing.
pub fn tick(outgoing: bool, state: DeliveryState) -> &'static str {
    if !outgoing {
        return "";
    }
    match state {
        DeliveryState::Pending => "Sending…",
        DeliveryState::Sent => "✓",
        DeliveryState::Delivered => "✓✓",
        DeliveryState::Failed => "Not sent",
        DeliveryState::None => "",
    }
}

/// What a failed page load says on screen. The backend's own sentences
/// (access off, desktop) are kept; provider internals are not shown.
pub fn load_error_text(error: &str) -> String {
    match error {
        "SMS access is off" | "this needs an Android phone" => {
            format!("Couldn't load messages: {error}.")
        }
        _ => "Couldn't load messages. Tap to try again.".to_owned(),
    }
}

/// Whether the composer may send: something to send, and the role to send it.
pub fn can_send(text: &str, has_attachment: bool, role_held: bool) -> bool {
    role_held && (has_attachment || !text.trim().is_empty())
}

/// "112 left · 2 SMS" near a part boundary or past the first part; nothing for
/// short texts, where the counter would only be noise.
pub fn segment_label(body: &str) -> Option<String> {
    if body.is_empty() {
        return None;
    }
    let info = segments(body);
    if info.parts == 1 && info.left_in_part > COUNTER_FROM {
        return None;
    }
    let unicode = if info.unicode { " · Unicode" } else { "" };
    Some(format!(
        "{} left · {} SMS{unicode}",
        info.left_in_part, info.parts
    ))
}

/// The line under the title: the number when the title is a name, the head
/// count of a group.
pub fn header_subtitle(title: &str, recipients: &[String]) -> Option<String> {
    match recipients {
        [] => None,
        [one] => (one != title).then(|| one.clone()),
        many => Some(format!("{} people", many.len())),
    }
}

/// Indexes of the shown items (not `hidden`) that start a new local day.
pub fn day_starts(
    items: &[SmsItem],
    offset_s: i32,
    hidden: impl Fn(&SmsItem) -> bool,
) -> Vec<usize> {
    let day = |ms: i64| (ms.div_euclid(1000) + i64::from(offset_s)).div_euclid(86_400);
    let mut last = None;
    let mut out = Vec::new();
    for (i, item) in items.iter().enumerate().filter(|(_, it)| !hidden(it)) {
        if last != Some(day(item.at_ms)) {
            last = Some(day(item.at_ms));
            out.push(i);
        }
    }
    out
}

pub fn is_image(content_type: &str) -> bool {
    content_type
        .trim()
        .to_ascii_lowercase()
        .starts_with("image/")
}

fn part_label(content_type: &str) -> String {
    match content_type.split('/').next().unwrap_or("") {
        "video" => "Video".to_owned(),
        "audio" => "Audio".to_owned(),
        _ => "Attachment".to_owned(),
    }
}

/// The picked file's name, for the chip.
pub fn attachment_label(path: &str) -> String {
    let name = path.rsplit(['/', '\\']).next().unwrap_or(path);
    if name.is_empty() {
        "Photo".to_owned()
    } else {
        name.to_owned()
    }
}

/// Only sent or received messages with text can be selected for copying, not
/// ones still sending or failed.
fn selectable(item: &SmsItem) -> bool {
    !matches!(item.state, DeliveryState::Pending | DeliveryState::Failed)
        && (copy_text(item).is_some() || !saveable_parts(item).is_empty())
}

/// The local id of a failed send of ours, which can be retried, copied or
/// deleted. `None` for everything else.
pub fn failed_local(item: &SmsItem) -> Option<u64> {
    match item.key {
        SmsKey::Local(id) if item.state == DeliveryState::Failed => Some(id),
        _ => None,
    }
}

/// One MMS picture or file that can be saved to the phone's storage.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SavePart {
    pub name: String,
    pub mime: String,
    pub path: String,
}

/// Every picture part of `items`, oldest first, and the position of part
/// `part` of item `item` among them (0 when it is not a picture).
fn viewer_items(
    items: &[SmsItem],
    title: &SharedString,
    item: usize,
    part: usize,
) -> (Vec<ViewerItem>, usize) {
    let mut out = Vec::new();
    let mut index = 0;
    for (at, message) in items.iter().enumerate() {
        for (number, p) in message.parts.iter().enumerate() {
            let Some(path) = p.path.as_deref().filter(|_| is_image(&p.content_type)) else {
                continue;
            };
            if (at, number) == (item, part) {
                index = out.len();
            }
            out.push(ViewerItem {
                key: format!("{:?}-{number}", message.key),
                title: if message.outgoing {
                    "You".into()
                } else {
                    title.clone()
                },
                mime: p.content_type.trim().to_ascii_lowercase(),
                source: ViewerSource::File {
                    path: PathBuf::from(path),
                },
                at_ms: message.at_ms,
                placeholder: None,
                avatar: None,
            });
        }
    }
    (out, index)
}

/// The parts of a message that are files (not text or the SMIL layout).
pub fn saveable_parts(item: &SmsItem) -> Vec<SavePart> {
    item.parts
        .iter()
        .filter(|p| {
            let t = p.content_type.trim().to_ascii_lowercase();
            !t.starts_with("text/plain") && !t.starts_with("application/smil")
        })
        .filter_map(|p| p.path.as_deref().map(|path| (p, path)))
        .enumerate()
        .map(|(index, (p, path))| SavePart {
            name: mms_save_name(&p.content_type, item.at_ms, index + 1),
            mime: p.content_type.clone(),
            path: path.to_owned(),
        })
        .collect()
}

/// The Save pill text: "Save picture" for a lone image, "Save file" for a lone
/// other file, else "Save 1", "Save 2" and so on.
pub fn save_label(mime: &str, total: usize, number: usize) -> String {
    match (
        total,
        mime.trim().to_ascii_lowercase().starts_with("image/"),
    ) {
        (1, true) => "Save picture".to_owned(),
        (1, false) => "Save file".to_owned(),
        _ => format!("Save {number}"),
    }
}

/// The Share pill text: "Share file" for a lone file, else "Share 1", "Share 2".
pub fn share_label(total: usize, number: usize) -> String {
    if total == 1 {
        "Share file".to_owned()
    } else {
        format!("Share {number}")
    }
}

/// `mms-20261105-140500-1.jpg`: the UTC time of the message, the part number
/// and the extension of the type.
pub fn mms_save_name(content_type: &str, at_ms: i64, number: usize) -> String {
    let secs = at_ms.div_euclid(1000);
    let (year, month, day) = crate::conversation::civil_from_days(secs.div_euclid(86_400));
    let rem = secs.rem_euclid(86_400);
    format!(
        "mms-{year:04}{month:02}{day:02}-{:02}{:02}{:02}-{number}.{}",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60,
        crate::files::ext_for_mime(content_type)
    )
}

/// The text of a message to copy; `None` when it has none (a picture alone).
pub fn copy_text(item: &SmsItem) -> Option<String> {
    let text = item.body.trim();
    (!text.is_empty()).then(|| text.to_owned())
}

/// What the Forward pill sends: the text and the first picture. `None` when
/// the message has neither.
pub(crate) fn forward_item(
    item: &SmsItem,
    title: &SharedString,
) -> Option<crate::forward_ui::ForwardItem> {
    use rovsti_core::forward::ForwardBody;
    let text = copy_text(item);
    let picture = item.parts.iter().find_map(|p| {
        let path = p.path.as_deref().filter(|_| is_image(&p.content_type))?;
        Some(ViewerItem {
            key: format!("{:?}-forward", item.key),
            title: if item.outgoing {
                "You".into()
            } else {
                title.clone()
            },
            mime: p.content_type.trim().to_ascii_lowercase(),
            source: ViewerSource::File {
                path: PathBuf::from(path),
            },
            at_ms: item.at_ms,
            placeholder: None,
            avatar: None,
        })
    });
    let body = match (&picture, text) {
        (Some(_), caption) => ForwardBody::Picture { caption },
        (None, Some(text)) => ForwardBody::Text(text),
        (None, None) => return None,
    };
    Some(crate::forward_ui::ForwardItem { body, picture })
}

/// The one-time code of an incoming message, to copy.
pub fn copy_code(item: &SmsItem) -> Option<String> {
    if item.outgoing {
        return None;
    }
    rovsti_core::otp::detect(&item.body).map(|o| o.code)
}

#[cfg(test)]
mod tests {
    #[test]
    fn spam_banner_needs_spam_and_one_sender() {
        let v = |a: &[&str]| a.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>();
        assert!(super::spam_banner_visible(true, &v(&["+90555"])));
        assert!(!super::spam_banner_visible(false, &v(&["+90555"])));
        assert!(!super::spam_banner_visible(true, &v(&["+90555", "+90556"])));
        assert!(!super::spam_banner_visible(true, &[]));
    }

    use rovsti_core::sms::{MmsPart, SmsSim};

    #[test]
    fn share_labels() {
        assert_eq!(share_label(1, 1), "Share file");
        assert_eq!(share_label(3, 2), "Share 2");
    }

    use super::*;

    const T: i64 = 1_793_887_500_000; // 2026-11-05 14:05 UTC

    fn sim(id: i32, label: &str) -> SmsSim {
        SmsSim {
            sub_id: id,
            slot_index: None,
            label: label.into(),
            color_argb: 0,
        }
    }

    fn sims(list: &[(i32, &str)], default: Option<i32>) -> SmsSimsPayload {
        SmsSimsPayload {
            sims: list.iter().map(|(id, l)| sim(*id, l)).collect(),
            default_sub_id: default,
        }
    }

    fn item(outgoing: bool, state: DeliveryState, at_ms: i64) -> SmsItem {
        SmsItem {
            key: SmsKey::Sms(1),
            outgoing,
            body: "hi".into(),
            parts: Vec::<MmsPart>::new(),
            at_ms,
            state,
            sub_id: None,
        }
    }

    #[test]
    fn copy_helpers() {
        let mut m = item(false, DeliveryState::None, T);
        m.body = "  Your code is 123456 \n".into();
        assert_eq!(copy_text(&m).as_deref(), Some("Your code is 123456"));
        assert_eq!(copy_code(&m).as_deref(), Some("123456"));
        m.outgoing = true;
        assert_eq!(copy_code(&m), None, "outgoing has no code to copy");
        m.body = "  ".into();
        assert_eq!(copy_text(&m), None);
        m.outgoing = false;
        m.body = "see you soon".into();
        assert_eq!(copy_code(&m), None);
    }

    #[test]
    fn only_settled_messages_with_text_are_selectable() {
        let mut m = item(false, DeliveryState::None, T);
        m.body = "hello".into();
        assert!(selectable(&m));
        m.state = DeliveryState::Pending;
        assert!(!selectable(&m));
        m.state = DeliveryState::Failed;
        assert!(!selectable(&m));
        m.state = DeliveryState::None;
        m.body = "  ".into();
        assert!(!selectable(&m));
    }

    #[test]
    fn local_stars_name_the_author_and_preview() {
        let mut m = item(false, DeliveryState::None, 5_000);
        m.body = "  see you at 6  ".to_owned();
        let star = local_star(&m, "12", "Ada");
        assert_eq!(star.author, "Ada");
        assert_eq!(star.thread_id, "12");
        assert_eq!(star.at_ms, 5_000);
        assert_eq!(star.preview, "see you at 6");
        let mine = item(true, DeliveryState::Sent, 6_000);
        assert_eq!(local_star(&mine, "12", "Ada").author, "You");
    }

    #[test]
    fn sms_keys_order_for_selection() {
        let mut selection = Selection::new();
        assert!(selection.toggle(SmsKey::Mms(2)));
        assert!(selection.toggle(SmsKey::Sms(9)));
        assert!(selection.contains(&SmsKey::Sms(9)));
        assert!(!selection.contains(&SmsKey::Local(1)));
        assert_eq!(selection.len(), 2);
    }

    #[test]
    fn the_sim_chip_needs_more_than_one_sim() {
        assert!(!sim_chip_visible(&sims(&[], None)));
        assert!(!sim_chip_visible(&sims(&[(1, "SIM 1")], Some(1))));
        assert!(sim_chip_visible(&sims(
            &[(1, "SIM 1"), (2, "SIM 2")],
            Some(1)
        )));
    }

    #[test]
    fn the_sim_chip_names_the_sim_and_cycles() {
        let two = sims(&[(1, "Turkcell"), (2, "Vodafone")], Some(1));
        assert_eq!(sim_chip_label(&two, Some(2)), "Vodafone");
        assert_eq!(sim_chip_label(&two, None), "Choose SIM");
        assert_eq!(sim_chip_label(&two, Some(9)), "Choose SIM");
        assert_eq!(next_sim(&two, Some(1)), Some(2));
        assert_eq!(next_sim(&two, Some(2)), Some(1));
        assert_eq!(next_sim(&two, None), Some(1));
        assert_eq!(next_sim(&sims(&[], None), None), None);
    }

    #[test]
    fn delivery_ticks() {
        assert_eq!(tick(true, DeliveryState::Pending), "Sending…");
        assert_eq!(tick(true, DeliveryState::Sent), "✓");
        assert_eq!(tick(true, DeliveryState::Delivered), "✓✓");
        assert_eq!(tick(true, DeliveryState::Failed), "Not sent");
        assert_eq!(tick(true, DeliveryState::None), "");
        // Only our own messages carry ticks.
        assert_eq!(tick(false, DeliveryState::Delivered), "");
    }

    #[test]
    fn the_meta_line_joins_time_and_tick() {
        let later = T + 3_600_000;
        let own = |state| item(true, state, T);
        assert_eq!(meta_line(&own(DeliveryState::Sent), later, 0), "14:05 ✓");
        assert_eq!(
            meta_line(&own(DeliveryState::Delivered), later, 0),
            "14:05 ✓✓"
        );
        assert_eq!(
            meta_line(&own(DeliveryState::Failed), later, 0),
            "14:05 Not sent"
        );
        assert_eq!(
            meta_line(&own(DeliveryState::Pending), later, 0),
            "Sending…"
        );
        assert_eq!(
            meta_line(&item(false, DeliveryState::None, T), later, 0),
            "14:05"
        );
        assert_eq!(
            meta_line(&item(false, DeliveryState::None, T), T + 5_000, 0),
            "Now"
        );
        assert_eq!(
            meta_line(&item(false, DeliveryState::None, T), later, 3 * 3600),
            "17:05"
        );
    }

    #[test]
    fn sending_needs_text_or_a_picture_and_the_role() {
        assert!(can_send("hi", false, true));
        assert!(can_send("", true, true));
        assert!(!can_send("   ", false, true));
        assert!(!can_send("hi", false, false));
        assert!(!can_send("", true, false));
    }

    #[test]
    fn picture_only_mms_is_selectable_and_saveable() {
        let mut m = item(false, DeliveryState::None, T);
        m.body = String::new();
        assert!(!selectable(&m));
        m.parts = vec![
            MmsPart {
                content_type: "application/smil".into(),
                text: Some("<smil/>".into()),
                path: None,
            },
            MmsPart {
                content_type: "image/jpeg".into(),
                text: None,
                path: Some("/cache/a.jpg".into()),
            },
            MmsPart {
                content_type: "text/plain".into(),
                text: Some("x".into()),
                path: Some("/cache/t.txt".into()),
            },
        ];
        assert!(selectable(&m));
        let parts = saveable_parts(&m);
        assert_eq!(parts.len(), 1);
        assert_eq!(parts[0].mime, "image/jpeg");
        assert_eq!(parts[0].path, "/cache/a.jpg");
        assert!(parts[0].name.starts_with("mms-") && parts[0].name.ends_with("-1.jpg"));
        m.state = DeliveryState::Pending;
        assert!(!selectable(&m));
    }

    #[test]
    fn the_viewer_gets_every_picture_part_oldest_first() {
        let pic = |path: &str| MmsPart {
            content_type: "image/jpeg".into(),
            text: None,
            path: Some(path.into()),
        };
        let mut first = item(false, DeliveryState::None, T);
        first.parts = vec![pic("/c/a.jpg"), pic("/c/b.jpg")];
        let plain = item(false, DeliveryState::None, T + 1);
        let mut last = item(true, DeliveryState::Sent, T + 2);
        last.parts = vec![
            MmsPart {
                content_type: "application/pdf".into(),
                text: None,
                path: Some("/c/d.pdf".into()),
            },
            pic("/c/c.jpg"),
        ];
        let items = [first, plain, last];
        let title = SharedString::from("Ada");
        let (list, at) = viewer_items(&items, &title, 2, 1);
        assert_eq!(list.len(), 3);
        assert_eq!(at, 2);
        assert_eq!(list[0].title, "Ada");
        assert_eq!(list[2].title, "You");
        assert_eq!(
            list[1].source,
            ViewerSource::File {
                path: PathBuf::from("/c/b.jpg")
            }
        );
        assert_eq!(viewer_items(&items, &title, 0, 1).1, 1);
        // Keys are unique per part.
        assert_ne!(list[0].key, list[1].key);
    }

    #[test]
    fn save_labels() {
        assert_eq!(save_label("image/jpeg", 1, 1), "Save picture");
        assert_eq!(save_label("application/pdf", 1, 1), "Save file");
        assert_eq!(save_label("image/png", 3, 2), "Save 2");
    }

    #[test]
    fn mms_names_get_extensions() {
        // 2026-11-05 14:05:00 UTC
        let at = 1_793_887_500_000;
        assert_eq!(
            mms_save_name("image/jpeg", at, 1),
            "mms-20261105-140500-1.jpg"
        );
        assert_eq!(
            mms_save_name("application/pdf", at, 2),
            "mms-20261105-140500-2.pdf"
        );
        assert_eq!(
            mms_save_name("video/3gpp", at, 3),
            "mms-20261105-140500-3.bin"
        );
    }

    #[test]
    fn failed_local_items() {
        let mut m = item(true, DeliveryState::Failed, T);
        assert_eq!(failed_local(&m), None);
        m.key = SmsKey::Local(7);
        assert_eq!(failed_local(&m), Some(7));
        m.state = DeliveryState::Pending;
        assert_eq!(failed_local(&m), None);
        m.state = DeliveryState::Failed;
        m.key = SmsKey::Sms(7);
        assert_eq!(failed_local(&m), None);
    }

    #[test]
    fn load_errors_read_plainly() {
        assert_eq!(
            load_error_text("SMS access is off"),
            "Couldn't load messages: SMS access is off."
        );
        assert!(!load_error_text("Internal(\"cursor\")").contains("Internal"));
        assert!(load_error_text("x").ends_with("Tap to try again."));
    }

    #[test]
    fn the_counter_appears_near_a_boundary() {
        assert_eq!(segment_label(""), None);
        assert_eq!(segment_label("short"), None);
        let near = "a".repeat(140);
        assert_eq!(segment_label(&near).as_deref(), Some("20 left · 1 SMS"));
        let long = "a".repeat(161);
        assert_eq!(segment_label(&long).as_deref(), Some("145 left · 2 SMS"));
        assert_eq!(
            segment_label(&"ş".repeat(71)).as_deref(),
            Some("63 left · 2 SMS · Unicode")
        );
    }

    #[test]
    fn subtitles() {
        assert_eq!(
            header_subtitle("Ayşe", &["+905321234567".into()]).as_deref(),
            Some("+905321234567")
        );
        assert_eq!(
            header_subtitle("+905321234567", &["+905321234567".into()]),
            None
        );
        assert_eq!(
            header_subtitle("A, B", &["1".into(), "2".into()]).as_deref(),
            Some("2 people")
        );
        assert_eq!(header_subtitle("x", &[]), None);
    }

    #[test]
    fn day_starts_follow_the_local_day() {
        let day = 86_400_000;
        let items = [
            item(false, DeliveryState::None, T - day),
            item(false, DeliveryState::None, T - day + 60_000),
            item(false, DeliveryState::None, T),
        ];
        let none = |_: &SmsItem| false;
        assert_eq!(day_starts(&items, 0, none), vec![0, 2]);
        assert_eq!(day_starts(&[], 0, none), Vec::<usize>::new());
        // A +10h offset moves the cut to local midnight but keeps two days.
        assert_eq!(day_starts(&items, 10 * 3600, none), vec![0, 2]);
        // Hiding the only item of a day drops its break; hiding the first
        // item of a day moves the break to the next shown one.
        let hide2 = |i: &SmsItem| i.at_ms == T;
        assert_eq!(day_starts(&items, 0, hide2), vec![0]);
        let hide0 = |i: &SmsItem| i.at_ms == T - day;
        assert_eq!(day_starts(&items, 0, hide0), vec![1, 2]);
    }

    #[test]
    fn attachments_and_parts() {
        assert_eq!(attachment_label("/data/cache/IMG_1.jpg"), "IMG_1.jpg");
        assert_eq!(attachment_label("/data/cache/"), "Photo");
        assert!(is_image("image/jpeg"));
        assert!(is_image(" IMAGE/PNG"));
        assert!(!is_image("video/mp4"));
        assert_eq!(part_label("video/mp4"), "Video");
        assert_eq!(part_label("application/pdf"), "Attachment");
    }
}
