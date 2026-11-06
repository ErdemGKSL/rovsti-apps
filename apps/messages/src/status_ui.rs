//! WhatsApp Status (Updates): the stories contacts share for 24 hours.
//!
//! [`StatusFeed`] holds the gateway's list (`GET /v1/statuses`) for the whole
//! app: the Chats tab shows an entry row from it and [`StatusPage`] the list
//! of people. Tapping a person opens the full-screen viewer, which plays
//! their updates in order (tap right for the next, left for the previous,
//! hold to pause, swipe down or Back to close) and marks each one seen while
//! it shows in a window the user can see.

use std::collections::HashSet;
use std::sync::Arc;
use std::time::{Duration, Instant};

use gpui::{
    AnyElement, App, Context, Entity, EventEmitter, Hsla, InteractiveElement, IntoElement,
    ListAlignment, MouseButton, MouseDownEvent, MouseUpEvent, ObjectFit, ParentElement, Render,
    RenderImage, SharedString, StatefulInteractiveElement, Styled, StyledImage, Subscription, Task,
    Window, div, img, list, px, rgba,
};
use rovsti_core::{call_log::relative_time, recents::LocaleTime};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, glass_pill},
    current_theme, theme,
    tokens::{Theme, metrics, typography},
};
use rovsti_wa_client::Gateway;
use rovsti_wa_proto::{
    StatusAuthor, StatusItem, StatusKind, StatusList, StatusPlayer, is_pn_jid, jid_user,
    status::STATUS_SHOW_MS, status::Step,
};
use rovsti_wa_ui::{WaAvatars, decode_image, display_name, photo_avatar};

use crate::backend::{Backend, now_ms};
use crate::conversation::header_bar;
use crate::inbox;
use crate::keyed_list::{KeyedList, key_of};

/// Decoded pictures kept by the viewer (the update on screen and the next).
const IMAGE_CACHE: usize = 6;
/// Longest side a status photo is decoded at.
const MEDIA_PX: u32 = 1600;
/// Longest side a preview thumbnail is decoded at.
const THUMB_PX: u32 = 480;
/// A press that moved less than this is a tap.
const TAP_SLOP: f32 = 16.0;
/// A press held longer than this paused the viewer and is not a tap.
const HOLD: Duration = Duration::from_millis(300);
/// Dragging down this far closes the viewer.
const SWIPE_CLOSE: f32 = 110.0;
/// The background of a text update that names none (WhatsApp's teal).
const DEFAULT_BACKGROUND: u32 = 0xFF1E_6E4F;

// ---- the feed ---------------------------------------------------------------

/// The gateway's status updates, shared by the Chats tab and the page.
#[derive(Default)]
pub struct StatusFeed {
    list: StatusList,
    /// Bumped whenever `list` changes.
    rev: u64,
    loading: Option<Task<()>>,
    /// A reload was asked for while one ran.
    stale: bool,
    /// Ids seen here; kept seen when a reload races the receipt.
    seen: HashSet<String>,
    /// Ids whose receipt is still to go out.
    queue: Vec<String>,
    sending: bool,
}

impl StatusFeed {
    pub fn list(&self) -> &StatusList {
        &self.list
    }

    pub fn rev(&self) -> u64 {
        self.rev
    }

    /// Unlinked: nothing to show.
    pub fn clear(&mut self, cx: &mut Context<Self>) {
        if !self.list.is_empty() {
            self.list = StatusList::default();
            self.rev += 1;
            cx.notify();
        }
        self.loading = None;
        self.stale = false;
        self.seen.clear();
        self.queue.clear();
    }

    /// Fetches the list again, off the UI thread (once at a time).
    pub fn reload(&mut self, gateway: Gateway, cx: &mut Context<Self>) {
        if self.loading.is_some() {
            self.stale = true;
            return;
        }
        let fetch = gateway.clone();
        let task = cx
            .background_executor()
            .spawn(async move { fetch.statuses() });
        self.loading = Some(cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.loading = None;
                match result {
                    Ok(mut list) => {
                        let seen: Vec<String> = this.seen.iter().cloned().collect();
                        list.mark_viewed(&seen);
                        if list != this.list {
                            this.list = list;
                            this.rev += 1;
                            cx.notify();
                        }
                    }
                    Err(error) => log::warn!("loading WhatsApp status updates failed: {error}"),
                }
                if std::mem::take(&mut this.stale) {
                    this.reload(gateway, cx);
                }
            })
            .ok();
        }));
    }

    /// The user saw `id`: it shows as seen at once and its author gets a
    /// read receipt through the gateway.
    pub fn viewed(&mut self, id: &str, gateway: Gateway, cx: &mut Context<Self>) {
        if !self.seen.insert(id.to_owned()) {
            return;
        }
        if self.list.mark_viewed(&[id.to_owned()]) {
            self.rev += 1;
            cx.notify();
        }
        self.queue.push(id.to_owned());
        self.flush(gateway, cx);
    }

    fn flush(&mut self, gateway: Gateway, cx: &mut Context<Self>) {
        if self.sending || self.queue.is_empty() {
            return;
        }
        self.sending = true;
        let ids = std::mem::take(&mut self.queue);
        let send = gateway.clone();
        let task = cx
            .background_executor()
            .spawn(async move { send.mark_status_viewed(&ids) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("a WhatsApp status read receipt failed: {error}");
            }
            this.update(cx, |this, cx| {
                this.sending = false;
                this.flush(gateway, cx);
            })
            .ok();
        })
        .detach();
    }
}

// ---- names and labels -------------------------------------------------------

/// The chat to open from someone's status: only another person's phone JID
/// (own updates and LIDs have no chat to open).
pub fn message_target(author: &str, from_me: bool) -> Option<String> {
    let author = author.trim();
    (!from_me && author.ends_with("@s.whatsapp.net") && author != "@s.whatsapp.net")
        .then(|| author.to_owned())
}

/// The number to look an author up by in the address book: only real phone
/// JIDs have one (a LID's digits are not a phone number).
fn author_number(author: &str) -> Option<String> {
    is_pn_jid(author).then(|| format!("+{}", jid_user(author)))
}

/// What to call an author: the address-book name, WhatsApp's name for them,
/// the push name their update carried, then the number.
fn author_title(author: &StatusAuthor, backend: &Backend, avatars: &WaAvatars) -> String {
    let contact = author_number(&author.author)
        .and_then(|n| backend.contacts().match_number(&n))
        .map(|c| c.display_name.clone());
    let wa_name = avatars
        .name(&author.author)
        .map(str::to_owned)
        .or_else(|| author.name.clone());
    display_name(contact.as_deref(), wa_name.as_deref(), &author.author)
}

/// "Just now", "12 min ago", else the clock time or "Yesterday".
pub(crate) fn ago_label(now_ms: i64, then_ms: i64, locale: LocaleTime) -> String {
    let minutes = now_ms.saturating_sub(then_ms).max(0) / 60_000;
    match minutes {
        0 => "Just now".to_owned(),
        1..=59 => format!("{minutes} min ago"),
        _ => relative_time(now_ms, then_ms, locale.tz_offset_s, locale.format).to_string(),
    }
}

/// "0:14" for a video's length.
pub(crate) fn duration_label(seconds: u32) -> String {
    format!("{}:{:02}", seconds / 60, seconds % 60)
}

/// A WhatsApp `0xAARRGGBB` colour.
fn argb(value: u32) -> Hsla {
    rgba(value.rotate_left(8)).into()
}

/// The subtitle of the entry row in the Chats tab.
pub(crate) fn entry_subtitle(list: &StatusList) -> String {
    match list.unviewed() {
        0 => "No new updates".to_owned(),
        1 => "1 new update".to_owned(),
        n => format!("{n} new updates"),
    }
}

/// An avatar inside a ring: green while it has unseen updates, grey after.
fn ring_avatar(
    theme: Theme,
    title: &str,
    size: f32,
    unseen: bool,
    photo: Option<(Arc<RenderImage>, f32)>,
) -> gpui::Div {
    let colors = theme::colors(theme);
    let ring = if unseen {
        colors.green
    } else {
        colors.tertiary_label.opacity(0.6)
    };
    div()
        .flex_none()
        .rounded_full()
        .border(px(if unseen { 2.5 } else { 1.5 }))
        .border_color(ring)
        .p(px(2.5))
        .child(photo_avatar(
            theme,
            inbox::initials(title),
            size,
            colors.green,
            photo,
        ))
}

/// The leading element of the Chats tab's Status row: up to three rings,
/// unseen first.
pub(crate) fn entry_avatars(
    theme: Theme,
    list: &StatusList,
    backend: &Entity<Backend>,
    avatars: &Entity<WaAvatars>,
    cx: &mut App,
) -> AnyElement {
    let shown: Vec<&StatusAuthor> = list.authors.iter().take(3).collect();
    let mut stack = div().flex_none().flex().items_center().w(px(44.0));
    for (i, author) in shown.iter().enumerate() {
        avatars.update(cx, |a, cx| a.want(&author.author, None, cx));
        let title = author_title(author, backend.read(cx), avatars.read(cx));
        let photo = avatars.read(cx).picture(&author.author);
        let size = if shown.len() == 1 { 34.0 } else { 26.0 };
        let mut ring = div().child(ring_avatar(
            theme,
            &title,
            size,
            author.unviewed() > 0,
            photo,
        ));
        if i > 0 {
            ring = ring.ml(px(-16.0));
        }
        stack = stack.child(ring);
    }
    if shown.is_empty() {
        let colors = theme::colors(theme);
        stack = stack.justify_center().child(
            gpui::svg()
                .path(Icon::LucideCircle.path())
                .size(px(22.0))
                .text_color(colors.green),
        );
    }
    stack.into_any_element()
}

// ---- the page ---------------------------------------------------------------

pub enum StatusPageEvent {
    Back,
    /// Open the chat with the author of the update being viewed.
    Message {
        jid: String,
        title: String,
    },
}

/// One item of the page's list.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Item {
    Mine,
    Recent,
    Viewed,
    Author(usize),
    Empty,
    Tail,
}

/// One person the viewer plays, frozen when it opened (a reload does not
/// move the updates under the reader).
struct Shown {
    author: String,
    title: SharedString,
    mine: bool,
    items: Vec<StatusItem>,
}

/// A finger on the viewer.
#[derive(Clone, Copy)]
struct Press {
    x: f32,
    y: f32,
    at: Instant,
}

struct Viewer {
    people: Vec<Shown>,
    player: StatusPlayer,
    press: Option<Press>,
    /// The previous timer tick, `None` while paused.
    last_tick: Option<Instant>,
}

impl Viewer {
    fn current(&self) -> Option<(&Shown, &StatusItem)> {
        let person = self.people.get(self.player.author())?;
        Some((person, person.items.get(self.player.item())?))
    }

    /// The update after the current one, to fetch ahead.
    fn upcoming(&self) -> Option<&StatusItem> {
        let person = self.people.get(self.player.author())?;
        person
            .items
            .get(self.player.item() + 1)
            .or_else(|| self.people.get(self.player.author() + 1)?.items.first())
    }
}

pub struct StatusPage {
    feed: Entity<StatusFeed>,
    gateway: Gateway,
    backend: Entity<Backend>,
    avatars: Entity<WaAvatars>,
    list: KeyedList,
    items: Vec<Item>,
    /// The feed revision `items` was built from.
    built: Option<u64>,
    viewer: Option<Viewer>,
    /// The window is in front of the user: only then does the timer run and
    /// does an update count as seen.
    active: bool,
    /// Decoded photos and thumbnails by key (`id`, `t:id`), oldest first.
    images: Vec<(String, Arc<RenderImage>)>,
    loading: HashSet<String>,
    failed: HashSet<String>,
    _subscriptions: Vec<Subscription>,
}

impl EventEmitter<StatusPageEvent> for StatusPage {}

impl StatusPage {
    pub fn new(
        feed: Entity<StatusFeed>,
        gateway: Gateway,
        backend: Entity<Backend>,
        avatars: Entity<WaAvatars>,
        active: bool,
        cx: &mut Context<Self>,
    ) -> Self {
        let subscriptions = vec![
            cx.observe(&feed, |_, _, cx| cx.notify()),
            cx.observe(&avatars, |_, _, cx| cx.notify()),
        ];
        feed.update(cx, |feed, cx| feed.reload(gateway.clone(), cx));
        Self {
            feed,
            gateway,
            backend,
            avatars,
            list: KeyedList::new(ListAlignment::Top, 0),
            items: Vec::new(),
            built: None,
            viewer: None,
            active,
            images: Vec::new(),
            loading: HashSet::new(),
            failed: HashSet::new(),
            _subscriptions: subscriptions,
        }
    }

    /// System Back: the viewer closes first. False when there is nothing of
    /// the page's own to close.
    pub fn go_back(&mut self, cx: &mut Context<Self>) -> bool {
        if self.viewer.take().is_some() {
            cx.notify();
            return true;
        }
        false
    }

    /// The window came to the front or left it.
    pub fn set_active(&mut self, active: bool, cx: &mut Context<Self>) {
        self.active = active;
        if let Some(viewer) = &mut self.viewer {
            viewer.last_tick = None;
        }
        if active {
            self.mark_current(cx);
        }
        cx.notify();
    }

    // ---- the list ----------------------------------------------------------

    fn sync_items(&mut self, cx: &App) {
        let feed = self.feed.read(cx);
        if self.built == Some(feed.rev()) && !self.items.is_empty() {
            return;
        }
        self.built = Some(feed.rev());
        let list = feed.list();
        let mut items = Vec::new();
        if !list.mine.is_empty() {
            items.push(Item::Mine);
        }
        let unseen = list.authors.iter().filter(|a| a.unviewed() > 0).count();
        if unseen > 0 {
            items.push(Item::Recent);
            items.extend((0..unseen).map(Item::Author));
        }
        if unseen < list.authors.len() {
            items.push(Item::Viewed);
            items.extend((unseen..list.authors.len()).map(Item::Author));
        }
        if list.is_empty() {
            items.push(Item::Empty);
        }
        items.push(Item::Tail);
        let keys = items
            .iter()
            .map(|item| match *item {
                Item::Mine => key_of((0u8, list.mine.len(), list.mine.last().map(|m| &m.id))),
                Item::Author(i) => {
                    let a = &list.authors[i];
                    key_of((1u8, &a.author, a.items.len(), a.unviewed(), a.latest_ms()))
                }
                Item::Recent => key_of(2u8),
                Item::Viewed => key_of(3u8),
                Item::Empty => key_of(4u8),
                Item::Tail => key_of(5u8),
            })
            .collect();
        self.list.sync(keys);
        self.items = items;
    }

    fn list_item(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let Some(&item) = self.items.get(index) else {
            return div().into_any_element();
        };
        let locale = self.backend.read(cx).locale();
        let now = now_ms();
        match item {
            Item::Recent | Item::Viewed => div()
                .px(metrics::ROW_INSET)
                .pt(px(18.0))
                .pb(px(6.0))
                .text_size(typography::FOOTNOTE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(colors.secondary_label)
                .child(if item == Item::Recent {
                    "Recent updates"
                } else {
                    "Viewed updates"
                })
                .into_any_element(),
            Item::Mine => {
                let list = self.feed.read(cx).list();
                let count = list.mine.len();
                let latest = list.mine.iter().map(|m| m.timestamp_ms).max().unwrap_or(0);
                let subtitle = format!(
                    "{count} update{} · {}",
                    if count == 1 { "" } else { "s" },
                    ago_label(now, latest, locale)
                );
                let me = list
                    .mine
                    .first()
                    .map(|m| m.author.clone())
                    .unwrap_or_default();
                self.avatars.update(cx, |a, cx| a.want(&me, None, cx));
                let photo = self.avatars.read(cx).picture(&me);
                person_row(
                    theme,
                    ring_avatar(theme, "Me", 48.0, false, photo).into_any_element(),
                    "My status".into(),
                    subtitle,
                )
                .id("status-mine")
                .on_click(cx.listener(|this, _, _, cx| this.open_mine(cx)))
                .into_any_element()
            }
            Item::Author(i) => {
                let (author, title) = {
                    let list = self.feed.read(cx).list();
                    let Some(author) = list.authors.get(i).cloned() else {
                        return div().into_any_element();
                    };
                    let title = author_title(&author, self.backend.read(cx), self.avatars.read(cx));
                    (author, title)
                };
                self.avatars.update(cx, |a, cx| {
                    a.want(&author.author, None, cx);
                    a.want_profile(&author.author, cx);
                });
                let photo = self.avatars.read(cx).picture(&author.author);
                let ring = ring_avatar(theme, &title, 48.0, author.unviewed() > 0, photo);
                person_row(
                    theme,
                    ring.into_any_element(),
                    title.into(),
                    ago_label(now, author.latest_ms(), locale),
                )
                .id(("status-author", i))
                .on_click(cx.listener(move |this, _, _, cx| this.open_author(i, cx)))
                .into_any_element()
            }
            Item::Empty => div()
                .pt(px(80.0))
                .child(empty_state(
                    theme,
                    "No Status Updates",
                    "Updates your contacts share on WhatsApp show here for 24 hours.",
                ))
                .into_any_element(),
            Item::Tail => div().h(px(32.0)).into_any_element(),
        }
    }

    // ---- the viewer ----------------------------------------------------------

    fn open_mine(&mut self, cx: &mut Context<Self>) {
        let mine = self.feed.read(cx).list().mine.clone();
        let Some(first) = mine.first() else {
            return;
        };
        let people = vec![Shown {
            author: first.author.clone(),
            title: "My status".into(),
            mine: true,
            items: mine,
        }];
        self.open_viewer(people, 0, 0, cx);
    }

    fn open_author(&mut self, index: usize, cx: &mut Context<Self>) {
        let (people, start) = {
            let list = self.feed.read(cx).list();
            let Some(author) = list.authors.get(index) else {
                return;
            };
            let start = author.start_index();
            let backend = self.backend.read(cx);
            let avatars = self.avatars.read(cx);
            let people: Vec<Shown> = list
                .authors
                .iter()
                .map(|a| Shown {
                    author: a.author.clone(),
                    title: author_title(a, backend, avatars).into(),
                    mine: false,
                    items: a.items.clone(),
                })
                .collect();
            (people, start)
        };
        self.open_viewer(people, index, start, cx);
    }

    fn open_viewer(
        &mut self,
        people: Vec<Shown>,
        author: usize,
        item: usize,
        cx: &mut Context<Self>,
    ) {
        let counts = people.iter().map(|p| p.items.len()).collect();
        let Some(player) = StatusPlayer::new(counts, author, item, STATUS_SHOW_MS) else {
            return;
        };
        self.viewer = Some(Viewer {
            people,
            player,
            press: None,
            last_tick: None,
        });
        self.on_moved(cx);
    }

    /// The viewer shows another update: fetch what it needs and count it seen.
    fn on_moved(&mut self, cx: &mut Context<Self>) {
        let (current, upcoming) = match &self.viewer {
            Some(viewer) => (
                viewer.current().map(|(_, item)| item.clone()),
                viewer.upcoming().cloned(),
            ),
            None => return,
        };
        for item in current.iter().chain(upcoming.iter()) {
            self.want_media(item, cx);
        }
        self.mark_current(cx);
        cx.notify();
    }

    /// Counts the update on screen as seen, while the window is in front.
    fn mark_current(&mut self, cx: &mut Context<Self>) {
        if !self.active {
            return;
        }
        let Some(viewer) = &mut self.viewer else {
            return;
        };
        let (author, item) = (viewer.player.author(), viewer.player.item());
        let Some(person) = viewer.people.get_mut(author) else {
            return;
        };
        let mine = person.mine;
        let Some(shown) = person.items.get_mut(item) else {
            return;
        };
        if mine || shown.viewed {
            return;
        }
        shown.viewed = true;
        let id = shown.id.clone();
        let gateway = self.gateway.clone();
        self.feed
            .update(cx, |feed, cx| feed.viewed(&id, gateway, cx));
    }

    fn step(&mut self, step: Step, cx: &mut Context<Self>) {
        match step {
            Step::Stay => {}
            Step::Item | Step::Author => self.on_moved(cx),
            Step::Close => {
                self.viewer = None;
                cx.notify();
            }
        }
    }

    fn on_press(&mut self, event: &MouseDownEvent, cx: &mut Context<Self>) {
        if let Some(viewer) = &mut self.viewer {
            viewer.press = Some(Press {
                x: f32::from(event.position.x),
                y: f32::from(event.position.y),
                at: Instant::now(),
            });
            viewer.player.set_paused(true);
            viewer.last_tick = None;
            cx.notify();
        }
    }

    fn on_release(&mut self, event: &MouseUpEvent, window: &Window, cx: &mut Context<Self>) {
        let Some(viewer) = &mut self.viewer else {
            return;
        };
        viewer.player.set_paused(false);
        viewer.last_tick = None;
        let Some(press) = viewer.press.take() else {
            cx.notify();
            return;
        };
        let (x, y) = (f32::from(event.position.x), f32::from(event.position.y));
        let (dx, dy) = (x - press.x, y - press.y);
        let step = if dy > SWIPE_CLOSE && dy > dx.abs() {
            Step::Close
        } else if dx.hypot(dy) < TAP_SLOP && press.at.elapsed() < HOLD {
            let width = f32::from(window.viewport_size().width);
            if x < width / 3.0 {
                viewer.player.back()
            } else {
                viewer.player.forward()
            }
        } else {
            Step::Stay
        };
        self.step(step, cx);
        cx.notify();
    }

    /// Advances the timer by the real time since the last frame.
    fn tick(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let active = self.active;
        let Some(viewer) = &mut self.viewer else {
            return;
        };
        if !active || viewer.player.is_paused() {
            viewer.last_tick = None;
            return;
        }
        let now = Instant::now();
        let dt = viewer
            .last_tick
            .map_or(Duration::ZERO, |last| now.duration_since(last));
        viewer.last_tick = Some(now);
        let ms = u64::try_from(dt.as_millis()).unwrap_or(u64::MAX).min(250);
        let step = viewer.player.advance(ms);
        if step == Step::Stay {
            window.request_animation_frame();
        } else {
            self.step(step, cx);
            if self.viewer.is_some() {
                window.request_animation_frame();
            }
        }
    }

    // ---- media ---------------------------------------------------------------

    fn image(&self, key: &str) -> Option<Arc<RenderImage>> {
        self.images
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, image)| Arc::clone(image))
    }

    /// Fetches the photo (and the thumbnail of a photo or video) of `item`.
    fn want_media(&mut self, item: &StatusItem, cx: &mut Context<Self>) {
        let has_thumb = item.media.as_ref().is_some_and(|m| m.thumbnail);
        if has_thumb {
            self.fetch(format!("t:{}", item.id), item.id.clone(), true, cx);
        }
        if item.kind == StatusKind::Image {
            self.fetch(item.id.clone(), item.id.clone(), false, cx);
        }
    }

    fn fetch(&mut self, key: String, id: String, thumb: bool, cx: &mut Context<Self>) {
        if self.loading.contains(&key) || self.failed.contains(&key) || self.image(&key).is_some() {
            return;
        }
        self.loading.insert(key.clone());
        let gateway = self.gateway.clone();
        let task = cx.background_executor().spawn(async move {
            let bytes = if thumb {
                gateway.status_thumbnail(&id)
            } else {
                gateway.status_media(&id)
            };
            bytes.map(|b| decode_image(&b, if thumb { THUMB_PX } else { MEDIA_PX }))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.loading.remove(&key);
                match result {
                    Ok(Some(image)) => {
                        this.images.push((key, image));
                        if this.images.len() > IMAGE_CACHE {
                            let (_, old) = this.images.remove(0);
                            cx.drop_image(old, None);
                        }
                    }
                    Ok(None) => {
                        this.failed.insert(key);
                    }
                    Err(error) => {
                        log::warn!("loading a WhatsApp status picture failed: {error}");
                        this.failed.insert(key);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---- drawing -------------------------------------------------------------

    fn viewer_element(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let viewer = self.viewer.as_ref()?;
        let (person, item) = viewer.current()?;
        let now = now_ms();
        let locale = self.backend.read(cx).locale();
        let white = gpui::white();

        let bars = div()
            .flex()
            .gap(px(4.0))
            .px(px(10.0))
            .children((0..viewer.player.count()).map(|i| {
                let fill = viewer.player.fill(i);
                div()
                    .flex_1()
                    .h(px(3.0))
                    .rounded_full()
                    .bg(white.opacity(0.35))
                    .child(
                        div()
                            .h_full()
                            .rounded_full()
                            .bg(white)
                            .w(gpui::relative(fill)),
                    )
            }));
        let photo = self.avatars.read(cx).picture(&person.author);
        let close = glass_icon_button(theme, Icon::LucideX, 0.0, false)
            .id("status-close")
            .occlude()
            .on_click(cx.listener(|this, _, _, cx| {
                this.viewer = None;
                cx.notify();
            }));
        let header = div()
            .flex()
            .items_center()
            .gap(px(10.0))
            .px(px(12.0))
            .child(photo_avatar(
                theme,
                inbox::initials(&person.title),
                36.0,
                theme::colors(theme).green,
                photo,
            ))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(white)
                            .child(person.title.clone()),
                    )
                    .child(
                        div()
                            .text_size(typography::CAPTION.size)
                            .text_color(white.opacity(0.8))
                            .child(ago_label(now, item.timestamp_ms, locale)),
                    ),
            )
            .child(close);

        let body = self.item_body(item);
        let caption = item
            .text
            .clone()
            .filter(|_| item.kind != StatusKind::Text)
            .map(|text| {
                div()
                    .absolute()
                    .bottom_0()
                    .left_0()
                    .w_full()
                    .px(px(20.0))
                    .pt(px(16.0))
                    .pb(px(40.0))
                    .bg(gpui::black().opacity(0.45))
                    .text_center()
                    .text_size(typography::BODY.size)
                    .text_color(white)
                    .child(text)
            });

        let message = message_target(&person.author, item.from_me).map(|jid| {
            let title = person.title.clone();
            div()
                .absolute()
                .bottom(px(110.0))
                .left_0()
                .w_full()
                .flex()
                .justify_center()
                .child(
                    glass_pill(theme, "Message")
                        .id("status-message")
                        .occlude()
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.viewer = None;
                            cx.emit(StatusPageEvent::Message {
                                jid: jid.clone(),
                                title: title.to_string(),
                            });
                            cx.notify();
                        })),
                )
        });

        Some(
            div()
                .id("status-viewer")
                .absolute()
                .top_0()
                .left_0()
                .size_full()
                .bg(gpui::black())
                .occlude()
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, event: &MouseDownEvent, _, cx| this.on_press(event, cx)),
                )
                .on_mouse_up(
                    MouseButton::Left,
                    cx.listener(|this, event: &MouseUpEvent, window, cx| {
                        this.on_release(event, window, cx);
                    }),
                )
                .child(body)
                .children(caption)
                .children(message)
                .child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .w_full()
                        .pt(px(44.0))
                        .flex()
                        .flex_col()
                        .gap(px(10.0))
                        .child(bars)
                        .child(header),
                )
                .into_any_element(),
        )
    }

    /// The update itself, filling the screen.
    fn item_body(&self, item: &StatusItem) -> AnyElement {
        let white = gpui::white();
        let full = || div().absolute().top_0().left_0().size_full();
        let picture = |image: Arc<RenderImage>| {
            img(image)
                .absolute()
                .top_0()
                .left_0()
                .size_full()
                .object_fit(ObjectFit::Contain)
        };
        let note = |text: String| {
            div()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(white.opacity(0.85))
                .child(text)
        };
        match item.kind {
            StatusKind::Text => {
                let background = argb(item.background_argb.unwrap_or(DEFAULT_BACKGROUND));
                let color = item.text_argb.map_or(white, argb);
                let mut text = div()
                    .max_w(px(360.0))
                    .px(px(28.0))
                    .text_center()
                    .text_size(px(28.0))
                    .line_height(px(36.0))
                    .text_color(color)
                    .child(item.text.clone().unwrap_or_default());
                // WhatsApp's fonts are not here; bold and script fonts get
                // the nearest look.
                text = match item.font {
                    Some(2) => text.italic(),
                    Some(6 | 8 | 9 | 10) => text.font_weight(typography::TITLE.weight),
                    _ => text.font_weight(typography::TITLE3.weight),
                };
                full()
                    .bg(background)
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(text)
                    .into_any_element()
            }
            StatusKind::Image => {
                let shown = self
                    .image(&item.id)
                    .or_else(|| self.image(&format!("t:{}", item.id)));
                let waiting = self.image(&item.id).is_none().then(|| {
                    note(if self.failed.contains(&item.id) {
                        "The photo could not be loaded".to_owned()
                    } else {
                        "Loading…".to_owned()
                    })
                });
                full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .children(shown.map(picture))
                    .children(waiting)
                    .into_any_element()
            }
            StatusKind::Video => {
                let seconds = item.media.as_ref().and_then(|m| m.seconds).unwrap_or(0);
                let label = if seconds > 0 {
                    format!("Video · {}", duration_label(seconds))
                } else {
                    "Video".to_owned()
                };
                full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .children(self.image(&format!("t:{}", item.id)).map(picture))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .items_center()
                            .gap(px(10.0))
                            .child(
                                div()
                                    .size(px(64.0))
                                    .rounded_full()
                                    .bg(gpui::black().opacity(0.45))
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .child(
                                        gpui::svg()
                                            .path(Icon::LucidePlay.path())
                                            .size(px(28.0))
                                            .text_color(white),
                                    ),
                            )
                            .child(note(label))
                            .child(note("Videos play in WhatsApp on your phone".to_owned())),
                    )
                    .into_any_element()
            }
            StatusKind::Other => full()
                .flex()
                .items_center()
                .justify_center()
                .child(note("This update can't be shown here".to_owned()))
                .into_any_element(),
        }
    }
}

/// A person on the page: ring, name, time.
fn person_row(
    theme: Theme,
    leading: AnyElement,
    title: SharedString,
    subtitle: String,
) -> gpui::Div {
    let colors = theme::colors(theme);
    div()
        .flex()
        .items_center()
        .gap(px(12.0))
        .min_h(px(68.0))
        .px(metrics::ROW_INSET)
        .py(px(6.0))
        .hover(|s| s.bg(colors.label.opacity(0.04)))
        .child(leading)
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(2.0))
                .child(
                    div()
                        .truncate()
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(title),
                )
                .child(
                    div()
                        .truncate()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.secondary_label)
                        .child(subtitle),
                ),
        )
}

impl Render for StatusPage {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        self.tick(window, cx);
        // The viewer covers the whole page: the list underneath is not built
        // while it plays.
        if let Some(viewer) = self.viewer_element(theme, cx) {
            return div()
                .size_full()
                .relative()
                .child(viewer)
                .into_any_element();
        }
        self.sync_items(cx);
        let back = glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
            .id("status-back")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(StatusPageEvent::Back)))
            .into_any_element();
        let subtitle = {
            let list = self.feed.read(cx).list();
            (!list.authors.is_empty()).then(|| entry_subtitle(list))
        };
        let rows = list(
            self.list.state().clone(),
            cx.processor(|this, index: usize, window, cx| this.list_item(index, window, cx)),
        )
        .flex_1()
        .min_h_0()
        .w_full();
        div()
            .size_full()
            .flex()
            .flex_col()
            .child(header_bar(theme, back, "Status".into(), subtitle, None))
            .child(rows)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn author_number_only_for_phone_jids() {
        assert_eq!(
            author_number("905551234567@s.whatsapp.net").as_deref(),
            Some("+905551234567")
        );
        assert_eq!(
            author_number("905551234567:3@s.whatsapp.net").as_deref(),
            Some("+905551234567")
        );
        assert_eq!(author_number("96413459456093@lid"), None);
        assert_eq!(author_number("123-456@g.us"), None);
        assert!(!display_name(None, None, "96413459456093@lid").contains('+'));
    }

    #[test]
    fn ages_read_like_whatsapp() {
        let locale = LocaleTime::default();
        let now = 1_800_000_000_000;
        assert_eq!(ago_label(now, now - 20_000, locale), "Just now");
        assert_eq!(ago_label(now, now - 12 * 60_000, locale), "12 min ago");
        // An hour or more: the clock time (or "Yesterday").
        let label = ago_label(now, now - 3 * 3_600_000, locale);
        assert!(label.contains(':') || label == "Yesterday", "{label}");
        // A clock a little ahead never says "-1 min".
        assert_eq!(ago_label(now, now + 5_000, locale), "Just now");
    }

    #[test]
    fn durations_and_subtitles() {
        assert_eq!(duration_label(14), "0:14");
        assert_eq!(duration_label(75), "1:15");
        let mut list = StatusList::default();
        assert_eq!(entry_subtitle(&list), "No new updates");
        list.authors.push(StatusAuthor {
            author: "a".into(),
            name: None,
            items: vec![StatusItem::default(), StatusItem::default()],
        });
        assert_eq!(entry_subtitle(&list), "2 new updates");
    }

    #[test]
    fn whatsapp_colours_are_argb() {
        let teal = argb(0xFF1E_6E4F);
        let expected: Hsla = rgba(0x1E6E_4FFF).into();
        assert_eq!(teal, expected);
    }

    #[test]
    fn only_other_peoples_phone_jids_can_be_messaged() {
        let jid = "905321234567@s.whatsapp.net";
        assert_eq!(message_target(jid, false), Some(jid.to_owned()));
        assert_eq!(message_target(jid, true), None);
        assert_eq!(message_target("123456@lid", false), None);
        assert_eq!(message_target("status@broadcast", false), None);
        assert_eq!(message_target("", false), None);
    }
}
