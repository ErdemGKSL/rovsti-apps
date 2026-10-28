//! The Contacts and Favorites tabs, the Contact sheet and the number chooser
//! (stories 8.1, 8.2, 8.4 to 8.7).
//!
//! Like Recents, both lists are virtualised `uniform_list`s of fixed-height
//! rows whose view-models are built once per provider snapshot (or per
//! query), never in render. A whole list shares one pointer surface and a tap
//! becomes a row with plain arithmetic.

use std::{cell::Cell, ops::Range, rc::Rc, sync::Arc, time::Duration};

use gpui::{
    AnyElement, Bounds, Context, Entity, HapticFeedback, InteractiveElement, IntoElement,
    MouseButton, ObjectFit, ParentElement, Pixels, Point, RenderImage, Role, ScrollStrategy,
    SharedString, StatefulInteractiveElement, Styled, Task, TouchPhase, UniformListScrollHandle,
    Window, div, linear_color_stop, linear_gradient, prelude::*, px, svg, uniform_list,
};
use rovsti_core::{
    contacts_view::{self, CallTarget, ContactVm, NameStyle, NumberVm},
    search::ContactIndex,
    wire::WireContact,
};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    moments::Tween,
    motion::press_scale,
    motion_tokens::{self, curve, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, metrics, mix, shadow, typography},
    touch::{Pointer, TAP_SLOP, TapGuard, pointer_surface, touch_surface},
};

use crate::{
    CONTACTS_TAB, FAVORITES_TAB, PhoneApp, SheetKind,
    backend::{self, Access, Group, Load},
    field::{SearchField, input_layer},
    flight::{Home, Look},
    recents::{ROW_H, row_at, state_view},
};

const GUTTER: f32 = 24.0;
const AVATAR: f32 = 40.0;
const FIELD_H: f32 = 44.0;
/// Empty rows at the end so the last contact scrolls clear of the tab bar.
const TAIL_ROWS: usize = 2;
const TILE_H: f32 = 132.0;
const TILE_GAP: f32 = 12.0;
/// Space above a tile inside its grid row (half the gap).
const TILE_TOP: f32 = 6.0;
/// One grid row: a tile and the gap under it.
const GRID_ROW_H: f32 = TILE_H + TILE_GAP;
/// A held finger on a tile or row opens the sheet (story 8.7).
pub(crate) const LONG_PRESS: Duration = Duration::from_millis(450);
const NOTE_FOR: Duration = Duration::from_secs(3);
/// Phone numbers that fit before the sheet scrolls.
const SHEET_AVATAR: f32 = 80.0;

/// One contact, ready to draw: strings are shared handles.
#[derive(Clone)]
pub(crate) struct ContactRow {
    pub(crate) id: i64,
    pub(crate) name: SharedString,
    pub(crate) subtitle: Option<SharedString>,
    letter: SharedString,
    pub(crate) header: bool,
    pub(crate) monogram: Option<SharedString>,
    pub(crate) hue: Option<usize>,
    pub(crate) numbers: Arc<[NumberVm]>,
    starred: bool,
    aria: SharedString,
    /// "Call <name>", spoken for a Favorites tile (built once, not per frame).
    call_aria: SharedString,
    /// Provider URI of the contact's photo (story 10.5).
    photo: Option<SharedString>,
}

impl From<ContactVm> for ContactRow {
    fn from(v: ContactVm) -> Self {
        let aria = match &v.subtitle {
            Some(sub) => format!("{}, {sub}", v.name),
            None => v.name.clone(),
        };
        let call_aria = format!("Call {}", v.name);
        Self {
            id: v.id,
            call_aria: call_aria.into(),
            name: v.name.into(),
            subtitle: v.subtitle.map(Into::into),
            letter: v.letter.to_string().into(),
            header: v.header,
            monogram: v.monogram.map(|c| c.to_string().into()),
            hue: v.hue,
            numbers: v.numbers.into(),
            starred: v.starred,
            aria: aria.into(),
            photo: v.photo_uri.map(Into::into),
        }
    }
}

/// The fixed height of a Contacts row, which the virtualised list relies on.
#[cfg(test)]
pub(crate) fn list_row_height() -> f32 {
    ROW_H
}

/// A list press id (`ct-12`) on the stack, for reading a spring each frame.
pub(crate) fn list_key(prefix: &str, index: usize) -> rovsti_core::small_text::SmallText<16> {
    let mut id = rovsti_core::small_text::SmallText::<16>::new();
    id.push_str(prefix);
    id.push_padded(index as u64, 1);
    id
}

fn rows_of<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    query: &str,
    style: NameStyle,
) -> Arc<[ContactRow]> {
    // The list follows the Sort by setting; search results keep their ranking.
    let contacts = if query.trim().is_empty() {
        contacts_view::ordered(contacts, style)
    } else {
        contacts.into_iter().collect()
    };
    contacts_view::build_rows_styled(contacts, query, style)
        .into_iter()
        .map(ContactRow::from)
        .collect::<Vec<_>>()
        .into()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum State {
    Loading,
    Denied,
    Unavailable,
    Ready,
}

impl State {
    fn of<T>(load: &Load<T>) -> Self {
        match load {
            Load::Loading => Self::Loading,
            Load::Ready(_) => Self::Ready,
            Load::PermissionDenied => Self::Denied,
            Load::Unavailable => Self::Unavailable,
        }
    }
}

pub(crate) struct ContactsView {
    scroll: UniformListScrollHandle,
    bounds: Rc<Cell<Bounds<Pixels>>>,
    pub(crate) state: State,
    index: Option<Arc<ContactIndex>>,
    all: Arc<[ContactRow]>,
    pub(crate) rows: Arc<[ContactRow]>,
    /// The query `rows` was built for.
    query: String,
    pub(crate) field: Entity<SearchField>,
    seen_edits: u64,
    /// The field's text as of the last edit.
    field_text: SharedString,
    touch: Option<Point<Pixels>>,
    tap: TapGuard,
    last_pressed: Option<usize>,
    /// The keyboard is up (the field has focus): the Tab Bar steps aside.
    pub(crate) typing: bool,
    /// Rows where a new letter section starts (empty while searching).
    section_starts: Arc<[u32]>,
    /// A search running on the background executor (large address books).
    search_task: Option<Task<()>>,
    /// The Name format and Sort by the rows were built with (story 16.9).
    style: NameStyle,
    /// A re-sort running off the UI thread after a setting change.
    sort_task: Option<Task<()>>,
}

/// Address books longer than this filter on the background executor.
const SEARCH_OFF_THREAD_ABOVE: usize = 200;
/// Height of the section letter, and where it pins under the search field.
const LETTER_H: f32 = 24.0;
const LETTER_PIN: f32 = 4.0;

impl ContactsView {
    pub(crate) fn new(cx: &mut gpui::App) -> Self {
        Self {
            scroll: UniformListScrollHandle::new(),
            bounds: Rc::new(Cell::new(Bounds::default())),
            state: State::Loading,
            index: None,
            all: Arc::from(Vec::new()),
            rows: Arc::from(Vec::new()),
            query: String::new(),
            style: NameStyle::default(),
            sort_task: None,
            field: cx.new(SearchField::new),
            seen_edits: 0,
            field_text: SharedString::default(),
            touch: None,
            tap: TapGuard::default(),
            last_pressed: None,
            typing: false,
            section_starts: Arc::from(Vec::new()),
            search_task: None,
        }
    }

    pub(crate) fn scrolled(&self) -> f32 {
        -f32::from(self.scroll.0.borrow().base_handle.offset().y).min(0.0)
    }

    pub(crate) fn scroll_to_top(&self) {
        self.scroll.scroll_to_item(0, ScrollStrategy::Top);
    }

    /// Desktop preview: jump `y` px down the list.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_scroll(&self, y: f32) {
        let handle = self.scroll.0.borrow().base_handle.clone();
        handle.set_offset(gpui::point(px(0.0), px(-y)));
    }
}

pub(crate) struct FavoritesView {
    scroll: UniformListScrollHandle,
    bounds: Rc<Cell<Bounds<Pixels>>>,
    pub(crate) state: State,
    source: Option<Arc<Vec<WireContact>>>,
    pub(crate) tiles: Arc<[ContactRow]>,
    touch: Option<Point<Pixels>>,
    tap: TapGuard,
    last_pressed: Option<usize>,
    long_task: Option<Task<()>>,
    /// A long press already opened the sheet: swallow the tap that follows.
    suppress_click: bool,
}

impl FavoritesView {
    pub(crate) fn new() -> Self {
        Self {
            scroll: UniformListScrollHandle::new(),
            bounds: Rc::new(Cell::new(Bounds::default())),
            state: State::Loading,
            source: None,
            tiles: Arc::from(Vec::new()),
            touch: None,
            tap: TapGuard::default(),
            last_pressed: None,
            long_task: None,
            suppress_click: false,
        }
    }

    /// Where tile `index`'s avatar is painted now (window coordinates), if the
    /// tile is inside the grid.
    pub(crate) fn avatar_rect(&self, index: usize) -> Option<Bounds<Pixels>> {
        let list = self.bounds.get();
        let width = f32::from(list.size.width);
        let cols = contacts_view::favorite_columns(width);
        let inset = f32::from(metrics::ROW_INSET);
        let tile_w = (width - inset * 2.0 - TILE_GAP * (cols as f32 - 1.0)) / cols as f32;
        let (row, col) = (index / cols, index % cols);
        let tile_x = f32::from(list.origin.x) + inset + col as f32 * (tile_w + TILE_GAP);
        let tile_y =
            f32::from(list.origin.y) + row as f32 * GRID_ROW_H + TILE_TOP - self.scrolled();
        // Avatar, 12 px gap and a one-line name, centred in the tile.
        let content = 64.0 + 12.0 + f32::from(typography::SUBHEADLINE.line);
        let rect = Bounds::new(
            gpui::point(
                px(tile_x + (tile_w - 64.0) / 2.0),
                px(tile_y + (TILE_H - content) / 2.0),
            ),
            gpui::size(px(64.0), px(64.0)),
        );
        list.contains(&rect.center()).then_some(rect)
    }

    pub(crate) fn scrolled(&self) -> f32 {
        -f32::from(self.scroll.0.borrow().base_handle.offset().y).min(0.0)
    }

    pub(crate) fn scroll_to_top(&self) {
        self.scroll.scroll_to_item(0, ScrollStrategy::Top);
    }
}

/// The open Contact sheet (or number chooser).
/// S12: the star's pop and the six dots that fly off it. Fixed-size, no allocation.
pub(crate) struct StarFx {
    pop: Spring,
    dots: Tween,
}

/// The dots fly 24 px over 400 ms on the kit's RELEASE_RING bezier.
const DOTS: usize = 6;
const DOT_FLIGHT: f32 = 24.0;
const DOT_SIZE: f32 = 4.0;

impl Default for StarFx {
    fn default() -> Self {
        Self {
            pop: Spring::new(1.0, motion_tokens::release()).with_epsilon(0.002),
            dots: Tween::new(Duration::from_millis(400), curve::RELEASE_RING.1),
        }
    }
}

impl StarFx {
    /// The write succeeded. On: 1.35 back to 1.0 with the dots. Off: 0.85 and
    /// back, no dots. Reduced motion: nothing moves (the fill already changed).
    pub(crate) fn play(&mut self, on: bool, reduced: bool) {
        if reduced {
            return;
        }
        if on {
            self.pop.snap(1.35);
            self.pop.retarget(1.0, motion_tokens::release());
            self.dots.start();
        } else {
            self.pop.snap(0.85);
            self.pop.retarget(1.0, motion_tokens::press());
        }
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.pop.tick(dt);
        moving |= self.dots.tick(dt);
        moving
    }

    /// Scale of the star glyph.
    fn scale(&self) -> f32 {
        self.pop.value.max(0.0)
    }

    /// `(distance from the star's centre in px, alpha)` while the dots fly.
    fn flight(&self) -> Option<(f32, f32)> {
        self.dots
            .running()
            .then(|| (DOT_FLIGHT * self.dots.progress(), 1.0 - self.dots.time()))
    }
}

pub(crate) struct ContactSheet {
    star_fx: StarFx,
    pub(crate) contact: ContactRow,
    /// Shown at once; reverts when the write fails.
    starred: bool,
    /// "Call {name}": only the numbers, no star or edit.
    chooser: bool,
    note: Option<SharedString>,
    note_task: Option<Task<()>>,
}

impl ContactSheet {
    pub(crate) fn tick_star(&mut self, dt: Duration) -> bool {
        self.star_fx.tick(dt)
    }

    pub(crate) fn chooser(&self) -> bool {
        self.chooser
    }
}

/// Where a pointer landed in the Favorites grid.
pub(crate) fn tile_at(x: f32, y: f32, scrolled: f32, width: f32, count: usize) -> Option<usize> {
    if x < 0.0 || y < 0.0 || x > width {
        return None;
    }
    let cols = contacts_view::favorite_columns(width);
    let inset = f32::from(metrics::ROW_INSET);
    let tile_w = (width - inset * 2.0 - TILE_GAP * (cols as f32 - 1.0)) / cols as f32;
    let row = ((y + scrolled) / GRID_ROW_H).floor();
    let within_y = (y + scrolled) - row * GRID_ROW_H - TILE_TOP;
    if row < 0.0 || !(0.0..=TILE_H).contains(&within_y) {
        return None;
    }
    let col = ((x - inset) / (tile_w + TILE_GAP)).floor();
    let within_x = (x - inset) - col * (tile_w + TILE_GAP);
    if col < 0.0 || col >= cols as f32 || within_x > tile_w {
        return None;
    }
    let index = row as usize * cols + col as usize;
    (index < count).then_some(index)
}

impl PhoneApp {
    // ---- Data ---------------------------------------------------------

    /// Pulls the backend's contacts and favorites into the views; rebuilds
    /// view-models only when a snapshot actually changed.
    pub(crate) fn sync_contacts(&mut self, cx: &mut Context<Self>) {
        let (contacts, favorites) = {
            let backend = self.backend.read(cx);
            (backend.contacts(), backend.favorites())
        };
        let state = State::of(&contacts);
        match &contacts {
            Load::Ready(index) => {
                let same = self
                    .contacts
                    .index
                    .as_ref()
                    .is_some_and(|known| Arc::ptr_eq(known, index));
                let style = self.name_style(cx);
                if same && style != self.contacts.style {
                    // Name format or Sort by changed: sort once, off the UI thread, swap the Arc.
                    self.resort_contacts(Arc::clone(index), style, cx);
                }
                if !same {
                    self.contacts.style = style;
                    // The Display rows dim when no contact has a distinct alternative name (S11).
                    self.settings.names_differ = index.all().any(|c| {
                        c.display_name_alt
                            .as_deref()
                            .map(str::trim)
                            .is_some_and(|alt| !alt.is_empty() && alt != c.display_name.trim())
                    });
                    self.contacts.sort_task = None;
                    self.contacts.all = rows_of(index.all(), "", style);
                    self.contacts.index = Some(Arc::clone(index));
                    self.photos.forget_misses();
                    self.apply_contacts_query(true, cx);
                    self.refresh_open_sheet();
                }
            }
            _ => {
                self.contacts.index = None;
                self.contacts.search_task = None;
                self.contacts.all = Arc::from(Vec::new());
                self.contacts.rows = Arc::from(Vec::new());
                self.contacts.section_starts = Arc::from(Vec::new());
            }
        }
        self.contacts.state = state;

        self.favorites.state = State::of(&favorites);
        if let Load::Ready(list) = &favorites {
            let same = self
                .favorites
                .source
                .as_ref()
                .is_some_and(|known| Arc::ptr_eq(known, list));
            if !same {
                self.favorites.tiles = rows_of(list.iter(), "", self.name_style(cx));
                self.favorites.source = Some(Arc::clone(list));
                self.favorites.last_pressed = None;
            }
        } else {
            self.favorites.source = None;
            self.favorites.tiles = Arc::from(Vec::new());
        }
    }

    /// The contacts snapshot changed under an open sheet (the star was
    /// written, or the contact was edited elsewhere): show the new numbers.
    fn refresh_open_sheet(&mut self) {
        let Some(sheet) = &mut self.contact_sheet else {
            return;
        };
        if let Some(fresh) = self.contacts.all.iter().find(|r| r.id == sheet.contact.id) {
            sheet.contact = fresh.clone();
        }
    }

    /// Re-filters the list for the field's text. The index folds names once
    /// and a query is a substring scan, so small address books filter inline;
    /// longer ones filter on the background executor and swap the rows in when
    /// done (a newer keystroke drops the older search).
    fn apply_contacts_query(&mut self, force: bool, cx: &mut Context<Self>) {
        let text = self.contacts.field_text.trim().to_owned();
        if !force && text == self.contacts.query {
            return;
        }
        self.contacts.query.clone_from(&text);
        self.contacts.search_task = None;
        let Some(index) = self.contacts.index.clone() else {
            return;
        };
        if text.is_empty() {
            let rows = Arc::clone(&self.contacts.all);
            self.set_contact_rows(rows);
        } else if index.len() <= SEARCH_OFF_THREAD_ABOVE {
            let rows = rows_of(index.search(&text), &text, self.contacts.style);
            self.set_contact_rows(rows);
        } else {
            let style = self.contacts.style;
            self.contacts.search_task = Some(cx.spawn(async move |this, cx| {
                let query = text.clone();
                let rows = cx
                    .background_executor()
                    .spawn(async move { rows_of(index.search(&query), &query, style) })
                    .await;
                this.update(cx, |this, cx| {
                    if this.contacts.query == text {
                        this.contacts.search_task = None;
                        this.set_contact_rows(rows);
                        cx.notify();
                    }
                })
                .ok();
            }));
        }
    }

    fn set_contact_rows(&mut self, rows: Arc<[ContactRow]>) {
        self.contacts.section_starts = rows
            .iter()
            .enumerate()
            .filter(|(_, row)| row.header)
            .map(|(i, _)| i as u32)
            .collect::<Vec<_>>()
            .into();
        self.contacts.rows = rows;
        self.contacts.last_pressed = None;
        self.contacts.scroll_to_top();
    }

    /// Once per render: is the keyboard up (the field focused on its tab)?
    /// Points the Tab Bar's slide at the answer.
    pub(crate) fn contacts_sync_focus(&mut self, window: &Window, cx: &mut Context<Self>) {
        let focused = self.tabs.selected() == CONTACTS_TAB
            && self
                .contacts
                .field
                .read(cx)
                .focus_handle()
                .is_focused(window);
        self.contacts.typing = focused;
        self.tabs_away.set_target(f32::from(u8::from(focused)));
    }

    /// The search field's text or focus changed.
    pub(crate) fn contacts_field_changed(&mut self, window: &Window, cx: &mut Context<Self>) {
        let (edits, text, focused) = {
            let field = self.contacts.field.read(cx);
            (
                field.edits,
                field.text().to_owned(),
                field.focus_handle().is_focused(window),
            )
        };
        let mut changed = false;
        if edits != self.contacts.seen_edits {
            self.contacts.seen_edits = edits;
            self.contacts.field_text = text.into();
            self.apply_contacts_query(false, cx);
            changed = true;
        }
        if focused != self.contacts.typing {
            self.contacts.typing = focused;
            changed = true;
        }
        if changed {
            cx.notify();
        }
    }

    // ---- Input --------------------------------------------------------

    fn contacts_blocked(&self) -> bool {
        self.tabs.selected() != CONTACTS_TAB
            || self.sheet.is_visible()
            || self.incall.covers()
            || self.incoming.covers()
            || self.contacts.state != State::Ready
    }

    fn list_limit(window: &Window) -> f32 {
        // The floating tab bar sits over the bottom of the list.
        f32::from(
            window.viewport_size().height - rovsti_ui::tab_bar::content_bottom_inset()
                + metrics::TAB_BAR_MARGIN,
        )
    }

    fn contacts_pointer(
        &mut self,
        pointer: Pointer,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.contacts_blocked() {
            return;
        }
        let hit = |this: &Self| {
            if f32::from(position.y) > Self::list_limit(window) && !this.contacts.typing {
                return None;
            }
            if this
                .overlay_band_top(window)
                .is_some_and(|top| position.y > top)
            {
                return None;
            }
            let bounds = this.contacts.bounds.get();
            row_at(
                f32::from(position.x - bounds.origin.x),
                f32::from(position.y - bounds.origin.y),
                this.contacts.scrolled(),
                f32::from(bounds.size.width),
                this.contacts.rows.len(),
            )
            .map(|(index, _)| index)
        };
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                self.contacts.tap.begin(
                    f32::from(position.x),
                    f32::from(position.y),
                    self.contacts.scrolled(),
                );
                if let Some(index) = hit(self) {
                    self.contacts.touch = Some(position);
                    self.contacts.last_pressed = Some(index);
                    self.presses.touch_down(format!("ct-{index}"));
                    cx.notify();
                }
            }
            Pointer::Touch(TouchPhase::Moved) => {
                self.contacts
                    .tap
                    .moved(f32::from(position.x), f32::from(position.y));
                if let Some(start) = self.contacts.touch {
                    let d =
                        (f32::from(position.x - start.x)).hypot(f32::from(position.y - start.y));
                    if d > TAP_SLOP {
                        self.contacts.touch = None;
                        self.presses.release_all();
                        cx.notify();
                    }
                }
            }
            Pointer::Touch(_) => {
                self.contacts
                    .tap
                    .finish(f32::from(position.x), f32::from(position.y));
                self.contacts.touch = None;
                self.presses.release_all();
                cx.notify();
            }
            Pointer::Click => {
                if !self.contacts.tap.allow_click(self.contacts.scrolled()) {
                    return;
                }
                let Some(index) = hit(self) else {
                    return;
                };
                self.contacts.last_pressed = Some(index);
                self.presses.down(format!("ct-{index}"));
                if let Some(row) = self.contacts.rows.get(index).cloned() {
                    self.open_contact_sheet(row, false, window, cx);
                }
            }
        }
    }

    fn favorites_blocked(&self) -> bool {
        self.tabs.selected() != FAVORITES_TAB
            || self.sheet.is_visible()
            || self.incall.covers()
            || self.incoming.covers()
            || self.favorites.state != State::Ready
    }

    fn favorites_pointer(
        &mut self,
        pointer: Pointer,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.favorites_blocked() {
            return;
        }
        let hit = |this: &Self| {
            if f32::from(position.y) > Self::list_limit(window)
                || this
                    .overlay_band_top(window)
                    .is_some_and(|top| position.y > top)
            {
                return None;
            }
            let bounds = this.favorites.bounds.get();
            tile_at(
                f32::from(position.x - bounds.origin.x),
                f32::from(position.y - bounds.origin.y),
                this.favorites.scrolled(),
                f32::from(bounds.size.width),
                this.favorites.tiles.len(),
            )
        };
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                self.favorites.suppress_click = false;
                self.favorites.tap.begin(
                    f32::from(position.x),
                    f32::from(position.y),
                    self.favorites.scrolled(),
                );
                if let Some(index) = hit(self) {
                    self.favorites.touch = Some(position);
                    self.favorites.last_pressed = Some(index);
                    self.presses.touch_down(format!("fv-{index}"));
                    self.favorites.long_task = Some(cx.spawn_in(window, async move |this, cx| {
                        cx.background_executor().timer(LONG_PRESS).await;
                        this.update_in(cx, |this, window, cx| {
                            this.favorites.long_task = None;
                            if this.favorites.touch.is_some()
                                && let Some(row) = this.favorites.tiles.get(index).cloned()
                            {
                                this.favorites.suppress_click = true;
                                this.favorites.tap.spoil();
                                this.favorites.touch = None;
                                rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
                                this.open_contact_sheet(row, false, window, cx);
                            }
                        })
                        .ok();
                    }));
                    cx.notify();
                }
            }
            Pointer::Touch(TouchPhase::Moved) => {
                self.favorites
                    .tap
                    .moved(f32::from(position.x), f32::from(position.y));
                if let Some(start) = self.favorites.touch {
                    let d =
                        (f32::from(position.x - start.x)).hypot(f32::from(position.y - start.y));
                    if d > TAP_SLOP {
                        self.favorites.touch = None;
                        self.favorites.long_task = None;
                        self.presses.release_all();
                        cx.notify();
                    }
                }
            }
            Pointer::Touch(_) => {
                self.favorites
                    .tap
                    .finish(f32::from(position.x), f32::from(position.y));
                self.favorites.touch = None;
                self.favorites.long_task = None;
                self.presses.release_all();
                cx.notify();
            }
            Pointer::Click => {
                let tapped = self.favorites.tap.allow_click(self.favorites.scrolled());
                if std::mem::take(&mut self.favorites.suppress_click) || !tapped {
                    return;
                }
                let Some(index) = hit(self) else {
                    return;
                };
                self.favorites.last_pressed = Some(index);
                self.presses.down(format!("fv-{index}"));
                if let Some(row) = self.favorites.tiles.get(index).cloned() {
                    self.call_favorite(row, window, cx);
                }
            }
        }
    }

    /// Desktop preview: tap the first Favorites tile.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_tap_favorite(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.favorites.last_pressed = Some(0);
        if let Some(row) = self.favorites.tiles.first().cloned() {
            self.call_favorite(row, window, cx);
        }
    }

    /// Story 8.7: one tap calls the default number, or asks which.
    fn call_favorite(&mut self, row: ContactRow, window: &mut Window, cx: &mut Context<Self>) {
        match contacts_view::call_target(&row.numbers) {
            CallTarget::Direct(i) => {
                let raw = row.numbers[i].raw.clone();
                let index = self.favorites.last_pressed;
                let home = index.map(|index| Home {
                    tab: FAVORITES_TAB,
                    index,
                    look: Look {
                        hue: row.hue,
                        monogram: row.monogram.clone(),
                        photo: row
                            .photo
                            .as_ref()
                            .and_then(|_| self.photos.get(row.id))
                            .map(|(image, _)| Arc::clone(image)),
                    },
                });
                let from = index.and_then(|i| self.favorites.avatar_rect(i));
                if self.place_call_from(&raw, window, cx)
                    && let (Some(home), Some(from)) = (home, from)
                {
                    self.begin_list_flight(home, from, window);
                }
            }
            CallTarget::Choose => self.open_contact_sheet(row, true, window, cx),
            CallTarget::Nothing => {}
        }
    }

    // ---- The sheet ------------------------------------------------------

    /// Opens the Contact sheet (or, with `chooser`, the number chooser).
    pub(crate) fn open_contact_sheet(
        &mut self,
        contact: ContactRow,
        chooser: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.can_open_sheet() {
            return;
        }
        window.blur();
        let starred = contact.starred;
        self.contact_sheet = Some(ContactSheet {
            star_fx: StarFx::default(),
            contact,
            starred,
            chooser,
            note: None,
            note_task: None,
        });
        self.setup_note = None;
        self.sheet_kind = SheetKind::Contact;
        self.sheet.open();
        cx.notify();
    }

    /// Desktop design preview: opens a sample contact's sheet at start-up
    /// (`ROVSTI_PREVIEW_CONTACT=<id>`, add `c` for the chooser).
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_contact_sheet(
        &mut self,
        spec: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let chooser = spec.ends_with('c');
        let id = spec.trim_end_matches('c').parse::<i64>().unwrap_or(1);
        let all = crate::sample::contacts();
        if let Some(row) = rows_of(all.iter().filter(|c| c.id == id), "", self.name_style(cx))
            .first()
            .cloned()
        {
            self.open_contact_sheet(row, chooser, window, cx);
        }
    }

    /// The contact for a dialed or logged number, as a sheet row (story 8.8).
    pub(crate) fn contact_for_number(&self, number: &str, cx: &gpui::App) -> Option<ContactRow> {
        let Load::Ready(index) = self.backend.read(cx).contacts() else {
            return None;
        };
        let found = index.match_number(number)?;
        rows_of(std::iter::once(found), "", self.name_style(cx))
            .first()
            .cloned()
    }

    /// The Name format and Sort by settings as the lists use them.
    pub(crate) fn name_style(&self, cx: &gpui::App) -> NameStyle {
        let settings = self.backend.read(cx).settings();
        NameStyle {
            format: settings.name_format,
            sort: settings.sort_by,
        }
    }

    /// Sorts and builds the Contacts rows for a new style off the UI thread
    /// (once per setting change, never per frame), then swaps the `Arc`.
    fn resort_contacts(
        &mut self,
        index: Arc<ContactIndex>,
        style: NameStyle,
        cx: &mut Context<Self>,
    ) {
        self.contacts.style = style;
        self.contacts.sort_task = Some(cx.spawn(async move |this, cx| {
            let rows = cx
                .background_executor()
                .spawn({
                    let index = Arc::clone(&index);
                    async move { rows_of(index.all(), "", style) }
                })
                .await;
            this.update(cx, |this, cx| {
                if this.contacts.style == style {
                    this.contacts.all = rows;
                    this.contacts.sort_task = None;
                    this.apply_contacts_query(true, cx);
                    // Favorites and the open sheet take the new format at once.
                    this.sync_contacts(cx);
                    this.refresh_open_sheet();
                    cx.notify();
                }
            })
            .ok();
        }));
        // Favorites tiles are not sorted: restyle them now.
        if let Load::Ready(list) = self.backend.read(cx).favorites() {
            self.favorites.tiles = rows_of(list.iter(), "", style);
        }
        cx.notify();
    }

    fn sheet_note(&mut self, text: &'static str, cx: &mut Context<Self>) {
        let Some(sheet) = &mut self.contact_sheet else {
            return;
        };
        sheet.note = Some(text.into());
        sheet.note_task = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(NOTE_FOR).await;
            this.update(cx, |this, cx| {
                if let Some(sheet) = &mut this.contact_sheet {
                    sheet.note = None;
                    sheet.note_task = None;
                }
                cx.notify();
            })
            .ok();
        }));
    }

    /// A Contact sheet control was pressed (ids start with `sheet-c`).
    pub(crate) fn contact_sheet_act(
        &mut self,
        id: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // A hold already moved the sheet to the SIM chooser: the lift that follows is not a tap.
        if self.sheet_kind == SheetKind::SimChooser {
            return;
        }
        let Some(sheet) = &mut self.contact_sheet else {
            return;
        };
        let contact_id = sheet.contact.id;
        if let Some(index) = id
            .strip_prefix("sheet-cn-")
            .and_then(|i| i.parse::<usize>().ok())
        {
            if let Some(number) = sheet.contact.numbers.get(index) {
                let raw = number.raw.clone();
                self.close_sheet(cx);
                let _ = self.place_call_from(&raw, window, cx);
            }
            return;
        }
        match id {
            "sheet-cstar" => {
                let next = !sheet.starred;
                sheet.starred = next;
                sheet.note = None;
                let reduced = window.prefers_reduced_motion();
                let write = self
                    .backend
                    .update(cx, |backend, cx| backend.set_starred(contact_id, next, cx));
                cx.spawn_in(window, async move |this, cx| {
                    let accepted = write.await;
                    this.update_in(cx, |this, window, cx| {
                        if accepted {
                            // S12 plays only once Android took the write: a medium
                            // haptic for a new favorite, the star pops (and sheds dots).
                            if let Some(sheet) = &mut this.contact_sheet
                                && sheet.contact.id == contact_id
                            {
                                sheet.star_fx.play(next, reduced);
                            }
                            rovsti_ui::haptics::perform(
                                window,
                                if next {
                                    HapticFeedback::Confirm
                                } else {
                                    HapticFeedback::Selection
                                },
                            );
                        } else if let Some(sheet) = &mut this.contact_sheet
                            && sheet.contact.id == contact_id
                        {
                            sheet.starred = !next;
                            this.sheet_note("Couldn't update favorite", cx);
                        }
                        cx.notify();
                    })
                    .ok();
                })
                .detach();
            }
            "sheet-cedit" => {
                let outcome = backend::edit_contact(contact_id);
                if outcome != backend::Outcome::Done {
                    self.sheet_note(outcome.why_not("Couldn't open the Contacts app"), cx);
                }
            }
            _ => {}
        }
        cx.notify();
    }

    // ---- Tabs -----------------------------------------------------------

    pub(crate) fn scroll_tab_to_top(&mut self, tab: usize) {
        match tab {
            FAVORITES_TAB => self.favorites.scroll_to_top(),
            CONTACTS_TAB => self.contacts.scroll_to_top(),
            crate::RECENTS_TAB => self.recents.scroll_to_top(),
            _ => {}
        }
    }

    /// The pill on a Contacts or Favorites state screen.
    pub(crate) fn contacts_pill(&mut self, id: &str, cx: &mut Context<Self>) {
        match id {
            "cs-grant" | "fv-grant" => {
                let access = self.backend.read(cx).permissions().contacts;
                let outcome = if access == Access::PermanentlyDenied {
                    backend::Outcome::Declined
                } else {
                    backend::request_permission(Group::Contacts)
                };
                if outcome == backend::Outcome::Declined {
                    backend::open_app_details();
                }
            }
            "cs-open" => {
                backend::open_contacts_app();
            }
            "fv-go" => self.select_tab(CONTACTS_TAB, cx),
            "cs-retry" | "fv-retry" => self.backend.update(cx, |b, cx| b.retry_contacts(cx)),
            _ => {}
        }
        cx.notify();
    }

    // ---- Rendering: Contacts ---------------------------------------------

    pub(crate) fn contacts_body(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let c = &self.contacts;
        let permanent = self.backend.read(cx).permissions().contacts == Access::PermanentlyDenied;
        match c.state {
            State::Ready if c.all.is_empty() => state_view(
                theme,
                Icon::LucideUsers,
                false,
                "No contacts with a phone number",
                "People you save with a number show up here.",
                Some(("cs-open", "Open Contacts", self.presses.value("cs-open"))),
                cx,
            ),
            State::Ready => {
                let column = div().flex_1().min_h_0().w_full().flex().flex_col();
                column
                    .child(self.contacts_field(theme, cx))
                    .child(self.contacts_results(theme, window, cx))
                    .into_any_element()
            }
            State::Denied => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Allow contacts access",
                if permanent {
                    "Rovsti Phone needs it to show your contacts. Turn on Contacts under Permissions."
                } else {
                    "Rovsti Phone needs it to show the people on this phone."
                },
                Some((
                    "cs-grant",
                    if permanent {
                        "Open settings"
                    } else {
                        "Grant access"
                    },
                    self.presses.value("cs-grant"),
                )),
                cx,
            ),
            State::Unavailable => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Can't load contacts",
                "Something went wrong reading your contacts. It will retry when they change.",
                Some(("cs-retry", "Try again", self.presses.value("cs-retry"))),
                cx,
            ),
            State::Loading => div().flex_1().into_any_element(),
        }
    }

    /// The sticky glass search field (44 px).
    pub(crate) fn contacts_field(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme::colors(theme);
        let field = self.contacts.field.clone();
        let focus = field.read(cx).focus_handle();
        let text = self.contacts.field_text.clone();
        let typing = self.contacts.typing;
        let press = self.presses.value("cs-field").clamp(0.0, 1.0);
        let clear_press = self.presses.value("cs-clear").clamp(0.0, 1.0);
        let focus_for_tap = focus.clone();
        let clear_field = field.clone();
        let spec = GlassSpec::control(theme).lerp(GlassSpec::lens(theme), press * 0.5);
        let text_box = div()
            .relative()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .items_center()
            .overflow_hidden()
            .text_size(typography::BODY.size)
            .child(input_layer(field, cx))
            .child(if text.is_empty() {
                div()
                    .text_color(colors.tertiary_label)
                    .child("Search name or number")
                    .into_any_element()
            } else {
                div()
                    .truncate()
                    .text_color(colors.label)
                    .child(text.clone())
                    .into_any_element()
            })
            .children(typing.then(|| {
                div()
                    .flex_none()
                    .ml(px(1.0))
                    .w(px(2.0))
                    .h(px(20.0))
                    .rounded_full()
                    .bg(colors.accent)
            }));
        div()
            .id("cs-field")
            .role(Role::TextInput)
            .aria_label("Search name or number")
            .track_focus(&focus)
            .relative()
            .flex_none()
            .h(px(FIELD_H))
            .mx(metrics::ROW_INSET)
            .mb(px(8.0))
            .on_mouse_down(MouseButton::Left, {
                let focus = focus_for_tap;
                cx.listener(move |this, _, window, cx| {
                    this.presses.down("cs-field");
                    window.focus(&focus, cx);
                    window.show_soft_keyboard();
                    this.contacts_field_changed(window, cx);
                })
            })
            .child(
                glass(spec)
                    .size_full()
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .pl(px(14.0))
                    .pr(px(4.0))
                    .child(
                        svg()
                            .path(Icon::LucideSearch.path())
                            .size(px(18.0))
                            .flex_none()
                            .text_color(if typing {
                                colors.accent
                            } else {
                                colors.secondary_label
                            }),
                    )
                    .child(text_box)
                    .children((!text.is_empty()).then(|| {
                        div()
                            .id("cs-clear")
                            .role(Role::Button)
                            .aria_label("Clear search")
                            .relative()
                            .flex_none()
                            .size(px(36.0))
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(
                                div()
                                    .size(px(22.0 * press_scale(clear_press, scale::PRESS_CARD)))
                                    .rounded_full()
                                    .bg(gpui::Hsla {
                                        a: 0.55 + 0.35 * clear_press,
                                        ..colors.fill
                                    })
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .child(
                                        svg()
                                            .path(Icon::LucideX.path())
                                            .size(px(14.0))
                                            .text_color(colors.secondary_label),
                                    ),
                            )
                            .on_mouse_down(MouseButton::Left, {
                                let field = clear_field;
                                cx.listener(move |this, _, window, cx| {
                                    this.presses.down("cs-clear");
                                    // Empties the field and keeps focus.
                                    field.update(cx, |f, cx| f.clear(cx));
                                    this.contacts_field_changed(window, cx);
                                })
                            })
                            .child(touch_surface(Self::touch_listener(
                                cx,
                                Some("cs-clear".into()),
                            )))
                    })),
            )
            .child(touch_surface(Self::touch_listener(
                cx,
                Some("cs-field".into()),
            )))
    }

    /// The section letters, drawn over the list in the rows' left gutter. A
    /// letter rides with its section's first row, pins under the search field
    /// once that row scrolled off, and is pushed away by the next letter.
    /// Rows never draw letters themselves, so a row is the same wherever it is.
    fn section_letters(&self, theme: Theme, window: &Window) -> Vec<AnyElement> {
        let c = &self.contacts;
        let starts = &c.section_starts;
        if starts.is_empty() {
            return Vec::new();
        }
        let colors = theme::colors(theme);
        let scrolled = c.scrolled();
        let view_h = f32::from(window.viewport_size().height);
        let natural = |row: u32| row as f32 * ROW_H + (ROW_H - LETTER_H) / 2.0 - scrolled;
        let top_row = (scrolled / ROW_H).floor().max(0.0) as u32;
        let current = starts.partition_point(|s| *s <= top_row).saturating_sub(1);
        let mut out = Vec::with_capacity(8);
        for (i, start) in starts.iter().enumerate().skip(current) {
            let mut y = natural(*start);
            if y > view_h {
                break;
            }
            if i == current {
                y = y.max(LETTER_PIN);
                if let Some(next) = starts.get(i + 1) {
                    y = y.min(natural(*next) - LETTER_H);
                }
            }
            if y <= -LETTER_H {
                continue;
            }
            let Some(row) = c.rows.get(*start as usize) else {
                continue;
            };
            out.push(
                div()
                    .absolute()
                    .top(px(y))
                    .left(metrics::ROW_INSET)
                    .w(px(GUTTER))
                    .h(px(LETTER_H))
                    .flex()
                    .items_center()
                    .text_size(typography::HEADLINE.size)
                    .font_weight(gpui::FontWeight::BOLD)
                    .text_color(colors.accent)
                    .child(row.letter.clone())
                    .into_any_element(),
            );
        }
        out
    }

    fn contacts_results(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let c = &self.contacts;
        if c.rows.is_empty() {
            let echo = contacts_view::clip_query(&c.query);
            return no_matches(theme, &echo);
        }
        let list = uniform_list(
            "contacts",
            c.rows.len() + TAIL_ROWS,
            cx.processor(move |this, range: Range<usize>, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                let rows = Arc::clone(&this.contacts.rows);
                this.want_photos(
                    range
                        .clone()
                        .filter_map(|i| rows.get(i))
                        .filter_map(|r| r.photo.as_ref().map(|uri| (r.id, uri))),
                    cx,
                );
                this.contact_rows(theme, range)
            }),
        )
        .track_scroll(&c.scroll)
        .size_full();
        let entity = cx.entity();
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(list)
            .children(self.section_letters(theme, window))
            .child(pointer_surface(
                Rc::clone(&c.bounds),
                move |pointer, position, window, cx| {
                    entity.update(cx, |this, cx| {
                        this.contacts_pointer(pointer, position, window, cx);
                    });
                },
            ))
            .into_any_element()
    }

    fn contact_rows(&self, theme: Theme, range: Range<usize>) -> Vec<AnyElement> {
        let c = &self.contacts;
        range
            .map(|i| match c.rows.get(i) {
                Some(row) => {
                    let press = if c.last_pressed == Some(i) {
                        self.presses
                            .value(crate::contacts::list_key("ct-", i).as_str())
                    } else {
                        0.0
                    };
                    let photo = row.photo.as_ref().and_then(|_| self.photos.get(row.id));
                    contact_row(theme, i, row, press, photo)
                }
                None => div().h(px(ROW_H)).into_any_element(),
            })
            .collect()
    }

    // ---- Rendering: Favorites --------------------------------------------

    pub(crate) fn favorites_body(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let f = &self.favorites;
        let permanent = self.backend.read(cx).permissions().contacts == Access::PermanentlyDenied;
        match f.state {
            State::Ready if f.tiles.is_empty() => state_view(
                theme,
                Icon::LucideStar,
                false,
                "No favorites yet",
                "Star someone in Contacts to call them with one tap.",
                Some(("fv-go", "Go to Contacts", self.presses.value("fv-go"))),
                cx,
            ),
            State::Ready => self.favorites_grid(window, cx),
            State::Denied => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Allow contacts access",
                if permanent {
                    "Rovsti Phone needs it to show your favorites. Turn on Contacts under Permissions."
                } else {
                    "Rovsti Phone needs it to show your favorites."
                },
                Some((
                    "fv-grant",
                    if permanent {
                        "Open settings"
                    } else {
                        "Grant access"
                    },
                    self.presses.value("fv-grant"),
                )),
                cx,
            ),
            State::Unavailable => state_view(
                theme,
                Icon::LucideTriangleAlert,
                true,
                "Can't load favorites",
                "Something went wrong reading your contacts. It will retry when they change.",
                Some(("fv-retry", "Try again", self.presses.value("fv-retry"))),
                cx,
            ),
            State::Loading => div().flex_1().into_any_element(),
        }
    }

    fn favorites_grid(&self, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let f = &self.favorites;
        let width = f32::from(window.viewport_size().width);
        let cols = contacts_view::favorite_columns(width);
        let rows = f.tiles.len().div_ceil(cols);
        let list = uniform_list(
            "favorites",
            rows + 1,
            cx.processor(move |this, range: Range<usize>, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                let width = f32::from(window.viewport_size().width);
                let cols = contacts_view::favorite_columns(width);
                let tiles = Arc::clone(&this.favorites.tiles);
                this.want_photos(
                    tiles
                        .iter()
                        .skip(range.start * cols)
                        .take(range.len() * cols)
                        .filter_map(|r| r.photo.as_ref().map(|uri| (r.id, uri))),
                    cx,
                );
                this.favorite_rows(theme, width, range)
            }),
        )
        .track_scroll(&f.scroll)
        .size_full();
        let entity = cx.entity();
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(list)
            .child(pointer_surface(
                Rc::clone(&f.bounds),
                move |pointer, position, window, cx| {
                    entity.update(cx, |this, cx| {
                        this.favorites_pointer(pointer, position, window, cx);
                    });
                },
            ))
            .into_any_element()
    }

    fn favorite_rows(&self, theme: Theme, width: f32, range: Range<usize>) -> Vec<AnyElement> {
        let f = &self.favorites;
        let cols = contacts_view::favorite_columns(width);
        let inset = f32::from(metrics::ROW_INSET);
        let tile_w = (width - inset * 2.0 - TILE_GAP * (cols as f32 - 1.0)) / cols as f32;
        range
            .map(|r| {
                let start = r * cols;
                if start >= f.tiles.len() {
                    // The tail row keeps the last tiles clear of the tab bar.
                    return div().h(px(GRID_ROW_H * 0.8)).into_any_element();
                }
                div()
                    .h(px(GRID_ROW_H))
                    .w_full()
                    .pt(px(TILE_TOP))
                    .flex()
                    .gap(px(TILE_GAP))
                    .px(metrics::ROW_INSET)
                    .children((start..(start + cols).min(f.tiles.len())).map(|i| {
                        let press = if f.last_pressed == Some(i) {
                            self.presses
                                .value(crate::contacts::list_key("fv-", i).as_str())
                        } else {
                            0.0
                        };
                        let tile = &f.tiles[i];
                        let photo = tile.photo.as_ref().and_then(|_| self.photos.get(tile.id));
                        favorite_tile(theme, i, tile, tile_w, press, photo)
                    }))
                    .into_any_element()
            })
            .collect()
    }

    // ---- Rendering: the sheet ----------------------------------------------

    pub(crate) fn contact_sheet_body(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let sheet = self.contact_sheet.as_ref()?;
        let colors = theme::colors(theme);
        let reveal = |index: usize, el: AnyElement| {
            let (alpha, lift) = self.sheet.rise(index);
            div()
                .relative()
                .top(px(lift))
                .when(alpha < 1.0, |d| d.opacity(alpha))
                .child(el)
        };
        let contact = &sheet.contact;
        let header = if sheet.chooser {
            div()
                .flex()
                .flex_col()
                .items_center()
                .pb(px(8.0))
                .child(
                    div()
                        .text_size(typography::TITLE2.size)
                        .line_height(typography::TITLE2.line)
                        .font_weight(typography::TITLE2.weight)
                        .text_color(colors.label)
                        .child(SharedString::from(format!("Call {}", contact.name))),
                )
                .child(
                    div()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.secondary_label)
                        .child("Choose a number"),
                )
        } else {
            let star = self.presses.value("sheet-cstar").clamp(0.0, 1.0);
            let on = sheet.starred;
            let gold = call_colors(theme).warning;
            div()
                .relative()
                .flex()
                .flex_col()
                .items_center()
                .gap(px(4.0))
                .pb(px(4.0))
                // S16: the avatar grows 0.8 to 1.0 inside a fixed slot, so nothing shifts.
                .child(
                    div()
                        .size(px(SHEET_AVATAR))
                        .flex_none()
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(person_avatar(
                            theme,
                            contact.hue,
                            contact.monogram.as_ref(),
                            SHEET_AVATAR * self.sheet.avatar_scale(),
                            contact
                                .photo
                                .as_ref()
                                .and_then(|_| self.photos.get(contact.id)),
                        )),
                )
                .child(
                    div()
                        .pt(px(8.0))
                        .max_w(px(280.0))
                        .truncate()
                        .text_size(typography::TITLE2.size)
                        .line_height(typography::TITLE2.line)
                        .font_weight(typography::TITLE2.weight)
                        .text_color(colors.label)
                        .child(contact.name.clone()),
                )
                .child(
                    div()
                        .id("sheet-cstar")
                        .role(Role::Switch)
                        .aria_label(if on {
                            "Remove from favorites"
                        } else {
                            "Add to favorites"
                        })
                        .absolute()
                        .top(px(0.0))
                        .right(px(0.0))
                        .size(metrics::MIN_HIT)
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(
                            div()
                                .size(px(40.0 * press_scale(star, scale::PRESS_CARD)))
                                .rounded_full()
                                .flex()
                                .items_center()
                                .justify_center()
                                .bg(if on {
                                    gpui::Hsla {
                                        a: 0.22 + 0.2 * star,
                                        ..gold
                                    }
                                } else {
                                    gpui::Hsla {
                                        a: 0.7 + 0.2 * star,
                                        ..colors.fill
                                    }
                                })
                                .child(
                                    svg()
                                        .path(Icon::LucideStar.path())
                                        .size(px(22.0 * sheet.star_fx.scale()))
                                        .text_color(if on { gold } else { colors.secondary_label }),
                                ),
                        )
                        .children(
                            sheet
                                .star_fx
                                .flight()
                                .map(|(distance, alpha)| star_dots(distance, alpha, gold)),
                        )
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, _, window, cx| {
                                this.presses.down("sheet-cstar");
                                this.contact_sheet_act("sheet-cstar", window, cx);
                            }),
                        )
                        .child(touch_surface(PhoneApp::touch_listener(
                            cx,
                            Some("sheet-cstar".into()),
                        ))),
                )
        };

        let count = contact.numbers.len();
        let numbers = div()
            .flex()
            .flex_col()
            .rounded(px(22.0))
            .overflow_hidden()
            .bg(gpui::Hsla {
                a: 0.5,
                ..colors.fill
            })
            .children(
                contact
                    .numbers
                    .iter()
                    .enumerate()
                    .map(|(i, n)| number_row(self, theme, i, n, i + 1 < count, cx)),
            );

        let mut children = vec![
            reveal(0, header.into_any_element()),
            reveal(
                1,
                // A fixed line: an error appearing never moves the numbers.
                div()
                    .h(px(20.0))
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(call_colors(theme).danger)
                    .children(sheet.note.clone())
                    .into_any_element(),
            ),
            reveal(2, numbers.into_any_element()),
        ];
        if !sheet.chooser {
            children.push(reveal(
                3,
                crate::recents::sheet_pill(
                    self,
                    "sheet-cedit",
                    Icon::LucidePencil,
                    "Edit in Contacts",
                    colors.fill,
                    colors.label,
                    cx,
                )
                .into_any_element(),
            ));
        }
        Some(
            div()
                .flex()
                .flex_col()
                .gap(px(6.0))
                .px(px(18.0))
                .pb(px(18.0))
                .children(children)
                .into_any_element(),
        )
    }
}

/// One number in the Contact sheet: label, number, "Default", and a round
/// call button; the whole row calls, on finger-up.
fn number_row(
    app: &PhoneApp,
    theme: Theme,
    index: usize,
    n: &NumberVm,
    separator: bool,
    cx: &mut Context<PhoneApp>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let id: SharedString = format!("sheet-cn-{index}").into();
    let press = app.presses.value(&id).clamp(0.0, 1.0);
    let answer = call_colors(theme).answer;
    let act_id = id.clone();
    // Story 17.4: a blocked number carries the glyph; the word is in the label too.
    let blocked = app.backend.read(cx).is_blocked(&n.display);
    div()
        .id(id.clone())
        .role(Role::Button)
        .aria_label(SharedString::from(format!(
            "Call {} {}{}",
            n.label,
            n.display,
            if blocked { ", blocked" } else { "" }
        )))
        .relative()
        .h(px(58.0))
        .w_full()
        .flex()
        .items_center()
        .gap(px(12.0))
        .px(px(16.0))
        .bg(gpui::Hsla {
            a: 0.9 * press,
            ..colors.fill
        })
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(8.0))
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(n.label.clone())
                        .children(n.default.then(|| {
                            div()
                                .px(px(7.0))
                                .rounded_full()
                                .bg(gpui::Hsla {
                                    a: 0.18,
                                    ..colors.accent
                                })
                                .text_color(colors.accent)
                                .child("Default")
                        })),
                )
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(6.0))
                        .child(
                            div()
                                .truncate()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(gpui::FontWeight::NORMAL)
                                .text_color(colors.label)
                                .child(n.display.clone()),
                        )
                        .children(blocked.then(|| {
                            svg()
                                .path(Icon::LucideBan.path())
                                .size(px(14.0))
                                .flex_none()
                                .text_color(colors.secondary_label)
                        })),
                ),
        )
        .child(
            div()
                .flex_none()
                .size(px(36.0 * press_scale(press, scale::PRESS_CARD)))
                .rounded_full()
                .bg(answer)
                .flex()
                .items_center()
                .justify_center()
                .child(
                    svg()
                        .path(Icon::LucidePhone.path())
                        .size(px(18.0))
                        .text_color(gpui::white()),
                ),
        )
        .children(separator.then(|| {
            div()
                .absolute()
                .bottom_0()
                .left(px(16.0))
                .right_0()
                .h(px(0.5))
                .bg(colors.separator)
        }))
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(move |this, _, window, cx| {
                this.presses.down(act_id.clone());
                this.contact_sheet_act(&act_id, window, cx);
            }),
        )
        .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
        .into_any_element()
}

/// "No matches" for a query nothing matched.
fn no_matches(theme: Theme, echo: &str) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .flex_1()
        .w_full()
        .flex()
        .flex_col()
        .items_center()
        .gap(px(8.0))
        .px(px(32.0))
        .pt(px(56.0))
        .child(
            div()
                .text_size(typography::TITLE3.size)
                .font_weight(typography::TITLE3.weight)
                .text_color(colors.label)
                .child("No matches"),
        )
        .child(
            div()
                .max_w(px(300.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .text_center()
                .child(SharedString::from(format!(
                    "No contact matches \"{echo}\"."
                ))),
        )
        .into_any_element()
}

/// A round avatar: the hue pair as a diagonal gradient with the monogram, and
/// the contact's photo fading in over it once decoded (`photo` is the image
/// and its opacity).
/// S12: six 4 px dots on a ring `distance` px from the middle of the star's
/// 44 px slot. Absolute, so they never move anything.
fn star_dots(distance: f32, alpha: f32, color: gpui::Hsla) -> gpui::Div {
    let centre = f32::from(metrics::MIN_HIT) * 0.5;
    div().absolute().inset_0().children((0..DOTS).map(move |i| {
        let angle = std::f32::consts::TAU * i as f32 / DOTS as f32 - std::f32::consts::FRAC_PI_2;
        div()
            .absolute()
            .left(px(centre + distance * angle.cos() - DOT_SIZE * 0.5))
            .top(px(centre + distance * angle.sin() - DOT_SIZE * 0.5))
            .size(px(DOT_SIZE))
            .rounded_full()
            .bg(gpui::Hsla { a: alpha, ..color })
    }))
}

pub(crate) fn person_avatar(
    theme: Theme,
    hue: Option<usize>,
    monogram: Option<&SharedString>,
    size: f32,
    photo: Option<(&Arc<RenderImage>, f32)>,
) -> gpui::Div {
    let (top, bottom) = theme::hue_pair(theme, hue);
    let base = div()
        .relative()
        .size(px(size))
        .flex_none()
        .rounded_full()
        .flex()
        .items_center()
        .justify_center()
        .border(px(1.5))
        .border_color(gpui::Hsla {
            a: 0.3,
            ..gpui::white()
        })
        .bg(linear_gradient(
            135.0,
            linear_color_stop(top, 0.0),
            linear_color_stop(bottom, 1.0),
        ));
    let base = match monogram {
        Some(letter) => base
            .text_size(px(size * 0.42))
            .font_weight(typography::HEADLINE.weight)
            .text_color(gpui::white())
            .child(letter.clone()),
        None => base.child(
            svg()
                .path(Icon::LucideUser.path())
                .size(px(size * 0.5))
                .text_color(gpui::white()),
        ),
    };
    base.children(photo.map(|(image, opacity)| {
        gpui::img(Arc::clone(image))
            .absolute()
            .top(px(-1.5))
            .left(px(-1.5))
            .size(px(size))
            .rounded_full()
            .object_fit(ObjectFit::Cover)
            .opacity(opacity)
    }))
}

/// One Contacts row: purely visual; the list's pointer surface does the touching.
fn contact_row(
    theme: Theme,
    index: usize,
    vm: &ContactRow,
    press: f32,
    photo: Option<(&Arc<RenderImage>, f32)>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let held = press.clamp(0.0, 1.0);
    div()
        .id(index)
        .role(Role::Button)
        .aria_label(vm.aria.clone())
        .relative()
        .h(px(ROW_H))
        .w_full()
        .flex()
        .items_center()
        .when(held > 0.002, |row| {
            row.child(
                div()
                    .absolute()
                    .top(px(2.0))
                    .bottom(px(2.0))
                    .left(px(6.0))
                    .right(px(6.0))
                    .rounded(px(16.0))
                    .bg(gpui::Hsla {
                        a: 0.9 * held,
                        ..colors.fill
                    }),
            )
        })
        .child(
            div()
                .flex_1()
                .min_w_0()
                .h_full()
                .flex()
                .items_center()
                .gap(px(8.0))
                .pl(metrics::ROW_INSET)
                .pr(px(12.0))
                // The section letter is drawn over this gutter by `section_letters`.
                .child(div().w(px(GUTTER)).flex_none())
                .child(person_avatar(
                    theme,
                    vm.hue,
                    vm.monogram.as_ref(),
                    AVATAR,
                    photo,
                ))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .pl(px(4.0))
                        .flex()
                        .flex_col()
                        .child(
                            div()
                                .truncate()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child(vm.name.clone()),
                        )
                        .children(vm.subtitle.clone().map(|sub| {
                            div()
                                .truncate()
                                .text_size(typography::SUBHEADLINE.size)
                                .text_color(colors.secondary_label)
                                .child(sub)
                        })),
                ),
        )
        .child(
            div()
                .absolute()
                .bottom_0()
                .left(px(f32::from(metrics::ROW_INSET)
                    + GUTTER
                    + 8.0
                    + AVATAR
                    + 12.0))
                .right_0()
                .h(px(0.5))
                .bg(colors.separator),
        )
        .into_any_element()
}

/// One Favorites tile: avatar and name in a soft card that dips under the finger.
fn favorite_tile(
    theme: Theme,
    index: usize,
    vm: &ContactRow,
    width: f32,
    press: f32,
    photo: Option<(&Arc<RenderImage>, f32)>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let held = press.clamp(0.0, 1.0);
    let k = press_scale(held, scale::PRESS_CARD);
    let (top, _) = theme::hue_pair(theme, vm.hue);
    div()
        .id(("fav", index))
        .role(Role::Button)
        .aria_label(vm.call_aria.clone())
        .w(px(width))
        .h(px(TILE_H))
        .flex_none()
        .flex()
        .items_center()
        .justify_center()
        .child(
            div()
                .relative()
                .w(px(width * k))
                .h(px(TILE_H * k))
                .rounded(px(26.0))
                .overflow_hidden()
                .bg(mix(
                    gpui::Hsla {
                        a: 0.55,
                        ..colors.fill
                    },
                    gpui::Hsla {
                        a: 0.9,
                        ..colors.fill
                    },
                    held,
                ))
                .border_1()
                .border_color(colors.hairline)
                // A whisper of the contact's hue behind the avatar.
                .child(
                    div()
                        .absolute()
                        .top_0()
                        .left_0()
                        .right_0()
                        .h(px(90.0))
                        .rounded_t(px(26.0))
                        .bg(linear_gradient(
                            180.0,
                            linear_color_stop(gpui::Hsla { a: 0.20, ..top }, 0.0),
                            linear_color_stop(gpui::Hsla { a: 0.0, ..top }, 1.0),
                        )),
                )
                .child(
                    div()
                        .size_full()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap(px(12.0))
                        .px(px(10.0))
                        .child(person_avatar(
                            theme,
                            vm.hue,
                            vm.monogram.as_ref(),
                            64.0,
                            photo,
                        ))
                        .child(
                            div()
                                .max_w_full()
                                .truncate()
                                .text_size(typography::SUBHEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child(vm.name.clone()),
                        ),
                ),
        )
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tiles_are_found_by_arithmetic() {
        // 402 wide: 2 columns, tile width (402 - 32 - 12) / 2 = 179.
        assert_eq!(tile_at(20.0, 10.0, 0.0, 402.0, 5), Some(0));
        assert_eq!(tile_at(250.0, 10.0, 0.0, 402.0, 5), Some(1));
        assert_eq!(tile_at(20.0, GRID_ROW_H + 10.0, 0.0, 402.0, 5), Some(2));
        // Scrolled one grid row.
        assert_eq!(tile_at(20.0, 10.0, GRID_ROW_H, 402.0, 5), Some(2));
    }

    #[test]
    fn gaps_and_the_unfilled_end_are_not_tiles() {
        // Between the columns, under a tile, beyond the last tile.
        assert_eq!(tile_at(16.0 + 179.0 + 4.0, 10.0, 0.0, 402.0, 5), None);
        assert_eq!(tile_at(20.0, TILE_TOP + TILE_H + 2.0, 0.0, 402.0, 5), None);
        assert_eq!(tile_at(250.0, 2.0 * GRID_ROW_H + 10.0, 0.0, 402.0, 3), None);
        assert_eq!(tile_at(5.0, 10.0, 0.0, 402.0, 5), None);
    }

    #[test]
    fn wide_windows_lay_out_three_columns() {
        // 450 wide: 3 columns, tile width (450 - 32 - 24) / 3.
        let w = (450.0 - 32.0 - 24.0) / 3.0;
        assert_eq!(
            tile_at(16.0 + 2.0 * (w + TILE_GAP) + 3.0, 10.0, 0.0, 450.0, 6),
            Some(2)
        );
    }
}
