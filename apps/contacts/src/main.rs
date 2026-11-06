//! Rovsti Contacts: the phone's address book in liquid glass.
//!
//! Contacts are read from Android's `ContactsContract` through
//! `rovsti_android` (the `backend` module); search, sections, the detail card,
//! the editor's checks, vCard files and the import plan come from
//! `rovsti_core`. The owner can create, edit and delete contacts and groups,
//! import and export a `.vcf`, and see photos. Desktop runs show a sample
//! address book. Nothing leaves the device.

mod backend;
mod confirm;
mod detail;
mod editor;
mod field;
mod groups;
mod photos;
mod prefs;
mod sample;
mod transfer;
mod ui;
mod whatsapp;

use std::{iter, path::PathBuf, sync::Arc};

use futures::StreamExt;
use gpui::{
    AnyElement, App, AppContext as _, Bounds, Context, Entity, FocusHandle, ListAlignment,
    ListOffset, ListState, MouseButton, ParentElement, Pixels, Render, ScrollHandle, SharedString,
    StatefulInteractiveElement, Styled, Window, WindowBounds, WindowOptions, div, list, prelude::*,
    px, size, svg,
};
use rovsti_android::{
    events,
    permissions::{self, Group as AccessGroup},
};
use rovsti_core::{
    contact_card::{self, ContactCard},
    contact_edit::{Account, ContactDraft, Group},
    contacts_prefs::ContactsPrefs,
    contacts_view::{self, ContactVm, NameStyle},
    settings::SortBy,
    wire::{NameFormat, WireContact},
};
use rovsti_ui::{
    Icon, IconAssets,
    components::{empty_state, glass_icon_button, glass_pill, large_title, list_row, section},
    current_theme,
    glass::{GlassSpec, glass},
    sheet::Sheet,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    theme::{self, hue_pair},
    tokens::{Theme, metrics, shadow, typography},
};

use rovsti_wa_ui::{AppId, WhatsAppSettings};

use crate::{
    backend::Book,
    confirm::Confirm,
    editor::Editor,
    field::{TextField, input_layer, on_backspace},
    groups::GroupEdit,
    photos::{Photos, photo_avatar},
    ui::{Tone, tap_row},
};

const TABS: [&str; 4] = ["Contacts", "Favorites", "Groups", "Settings"];
const TAB_ICONS: [Icon; 4] = [
    Icon::LucideUsers,
    Icon::LucideStar,
    Icon::LucideFolder,
    Icon::LucideSettings,
];
const CONTACTS_TAB: usize = 0;
const FAVORITES_TAB: usize = 1;
const GROUPS_TAB: usize = 2;
const SETTINGS_TAB: usize = 3;
const FIELD_H: f32 = 44.0;
/// A list row is about this tall: the height a virtualised list assumes for
/// rows it has not measured yet (it measures the real one when drawn).
const ROW_PX: f32 = 58.0;
/// Rows measured (and their photos asked for) beyond the screen's edges.
const LIST_OVERDRAW_PX: f32 = 600.0;

/// A virtualised page: item 0 is the header (title, search, notes), the rows
/// follow. Only the items on screen are built and laid out.
fn page_list() -> ListState {
    ListState::new(1, ListAlignment::Top, px(LIST_OVERDRAW_PX)).with_uniform_item_height(px(ROW_PX))
}

/// Points `list` at a header and `rows` rows. `header_focus` is the field in
/// the header, kept drawn while it has focus even when scrolled away, so
/// typing keeps working. The scroll position stays unless `to_top`.
fn refill(list: &ListState, rows: usize, header_focus: Option<FocusHandle>, to_top: bool) {
    let keep = list.logical_scroll_top();
    list.splice_focusable(
        0..list.item_count(),
        iter::once(header_focus).chain(iter::repeat_n(None, rows)),
    );
    list.scroll_to(if to_top { ListOffset::default() } else { keep });
}

/// Which built rows a contact list item reads.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RowSet {
    Contacts,
    Favorites,
    Group,
}

/// Section letters and row texts as shared strings, built once per change.
struct Row {
    vm: ContactVm,
    letter: SharedString,
    name: SharedString,
    subtitle: Option<SharedString>,
    monogram: SharedString,
    /// The star and the WhatsApp mark at the row's end.
    trailing: Option<SharedString>,
}

impl Row {
    /// Sets the WhatsApp mark (computed per pull and per chats refresh).
    fn set_whatsapp(&mut self, on: bool) {
        self.trailing = match (on, self.vm.starred) {
            (true, true) => Some("WhatsApp ★".into()),
            (true, false) => Some("WhatsApp".into()),
            (false, true) => Some("★".into()),
            (false, false) => None,
        };
    }
}

impl From<ContactVm> for Row {
    fn from(vm: ContactVm) -> Self {
        Self {
            letter: vm.letter.to_string().into(),
            name: vm.name.clone().into(),
            subtitle: vm.subtitle.clone().map(Into::into),
            monogram: vm.monogram.map(String::from).unwrap_or_default().into(),
            trailing: vm.starred.then(|| "★".into()),
            vm,
        }
    }
}

struct ContactsApp {
    tabs: TabBar,
    motion: rovsti_ui::motion::Motion,
    page: rovsti_ui::motion::Entrance,
    scroll: ScrollHandle,
    field: Entity<TextField>,
    seen_edits: u64,
    book: Book,
    pulls: u64,
    groups: Vec<Group>,
    /// The accounts a new contact or group can be saved to.
    accounts: Vec<Account>,
    /// The account key new contacts start in (from the saved preferences).
    save_to: String,
    style: NameStyle,
    /// Where `contacts-prefs.json` lives, once the data directory is known.
    prefs_dir: Option<PathBuf>,
    /// The owner picked a style before the saved one was read.
    style_touched: bool,
    rows: Vec<Row>,
    /// The query `rows` was built for: a new query scrolls back to the top.
    built_query: String,
    favorites: Vec<Row>,
    /// The Groups tab's rows (title and member count), built once per change.
    group_items: Vec<groups::GroupItem>,
    /// The virtualised Contacts, Favorites, Groups and group pages.
    contacts_list: ListState,
    favorites_list: ListState,
    groups_list: ListState,
    group_list: ListState,
    open: Option<ContactCard>,
    /// The open contact in full (organisation, emails, note, groups).
    open_draft: Option<ContactDraft>,
    editor: Option<Editor>,
    /// The group whose page is open, and its rows.
    group_page: Option<i64>,
    group_rows: Vec<Row>,
    group_edit: Option<GroupEdit>,
    confirm: Option<Confirm>,
    /// Open this contact once the next pull lands (after a save).
    pending_open: Option<i64>,
    /// A file picker is open.
    transfer_busy: bool,
    /// How many contacts the export in flight holds.
    exported: Option<usize>,
    notice: Option<SharedString>,
    photos: Photos,
    /// The "Contact photo" sheet over the editor.
    photo_sheet: Sheet,
    /// System Back is routed to the app (a sheet is open).
    back_enabled: bool,
    /// The shared WhatsApp settings screen, and whether its page is showing.
    whatsapp: Entity<WhatsAppSettings>,
    whatsapp_open: bool,
    /// WhatsApp per contact: chats, presence, lookups.
    wa: whatsapp::WaPanel,
}

impl ContactsApp {
    fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let field = cx.new(TextField::new);
        cx.observe(&field, |this, field, cx| {
            let edits = field.read(cx).edits;
            if edits != this.seen_edits {
                this.seen_edits = edits;
                this.rebuild_rows(cx);
            }
        })
        .detach();
        // Contacts may change in another app (or access may be granted in
        // Settings) while we are in the background: pull again on return.
        cx.observe_window_activation(window, |this, window, cx| {
            if window.is_window_active() {
                this.reload(cx);
            }
        })
        .detach();

        // Answers from Kotlin (a file picker finished) arrive as events.
        let (mut events_rx, sink) = events::install_sink_owned();
        cx.spawn(async move |this, cx| {
            while let Some(event) = events_rx.next().await {
                if this
                    .update(cx, |this, cx| this.on_event(&event, cx))
                    .is_err()
                {
                    break;
                }
            }
            events::clear_sink_if(sink);
        })
        .detach();
        // Tell Kotlin the session is live so an answer that came early is delivered.
        cx.background_executor()
            .spawn(async {
                let _ = rovsti_android::window::ui_attached(true);
            })
            .detach();

        // The saved name format and sort order.
        let data_dir = rovsti_ui::storage::data_dir("contacts", cx);
        cx.spawn(async move |this, cx| {
            let dir = match data_dir.await {
                Ok(dir) => dir,
                Err(error) => {
                    log::warn!("no data directory for the preferences: {error}");
                    return;
                }
            };
            let read_from = dir.clone();
            let saved = cx
                .background_executor()
                .spawn(async move { prefs::load(&read_from) })
                .await;
            this.update(cx, |this, cx| {
                this.prefs_dir = Some(dir);
                this.save_to.clone_from(&saved.save_to);
                if this.style_touched {
                    // The owner chose before the file was read: theirs wins.
                    this.save_prefs(cx);
                } else {
                    this.apply_style(saved.style(), cx);
                }
            })
            .ok();
        })
        .detach();

        let whatsapp = cx.new(|cx| WhatsAppSettings::new(AppId::Contacts, "contacts", cx));
        // The Settings row shows whether WhatsApp is linked; gateway events
        // (presence, resync) and a fresh connection feed the card and the list.
        cx.observe(&whatsapp, |this, _, cx| this.on_whatsapp_changed(cx))
            .detach();
        let mut app = Self {
            tabs: TabBar::new(
                TABS.into_iter()
                    .zip(TAB_ICONS)
                    .map(|(label, icon)| Tab::new(icon, label)),
            ),
            motion: Default::default(),
            page: Default::default(),
            scroll: ScrollHandle::new(),
            field,
            seen_edits: 0,
            book: Book::Loading,
            pulls: 0,
            groups: Vec::new(),
            accounts: Vec::new(),
            save_to: String::new(),
            style: NameStyle::default(),
            prefs_dir: None,
            style_touched: false,
            rows: Vec::new(),
            built_query: String::new(),
            favorites: Vec::new(),
            group_items: Vec::new(),
            contacts_list: page_list(),
            favorites_list: page_list(),
            groups_list: page_list(),
            group_list: page_list(),
            open: None,
            open_draft: None,
            editor: None,
            group_page: None,
            group_rows: Vec::new(),
            group_edit: None,
            confirm: None,
            pending_open: None,
            transfer_busy: false,
            exported: None,
            notice: None,
            photos: Photos::new(),
            photo_sheet: Sheet::new(),
            back_enabled: false,
            whatsapp,
            whatsapp_open: false,
            wa: whatsapp::WaPanel::default(),
        };
        refill(&app.contacts_list, 0, Some(app.search_focus(cx)), true);
        app.pull_wa_offset(cx);
        app.reload(cx);
        app
    }

    /// Pulls the contacts and groups on the background executor; only the
    /// newest pull lands.
    fn reload(&mut self, cx: &mut Context<Self>) {
        self.pulls += 1;
        let pull_id = self.pulls;
        cx.spawn(async move |this, cx| {
            let (book, groups, accounts) = cx
                .background_executor()
                .spawn(async {
                    (
                        backend::pull_book(),
                        backend::pull_groups(),
                        backend::pull_accounts(),
                    )
                })
                .await;
            this.update(cx, |this, cx| {
                if this.pulls == pull_id {
                    this.book = book;
                    this.groups = groups;
                    this.accounts = accounts;
                    this.photos.forget_misses();
                    this.refresh_marks();
                    this.rebuild(cx);
                    if let Some(id) = this.pending_open.take() {
                        this.open_contact(id, cx);
                    }
                }
            })
            .ok();
        })
        .detach();
    }

    fn index(&self) -> Option<&Arc<rovsti_core::search::ContactIndex>> {
        match &self.book {
            Book::Ready(index) => Some(index),
            _ => None,
        }
    }

    /// The photo address of contact `id`, if it has one.
    fn photo_uri_of(&self, id: i64) -> Option<&str> {
        self.index()?
            .all()
            .find(|c| c.id == id)?
            .photo_uri
            .as_deref()
    }

    /// Rebuilds the list rows, favorites, the open card and the open group
    /// from the book, the query and the name style.
    fn rebuild(&mut self, cx: &mut Context<Self>) {
        self.rebuild_rows(cx);
        let Some(index) = self.index().cloned() else {
            self.favorites.clear();
            self.group_rows.clear();
            self.group_items.clear();
            self.refill_others(cx);
            cx.notify();
            return;
        };
        let favorites = contacts_view::ordered(contact_card::favorites(index.all()), self.style);
        self.favorites = contacts_view::build_rows_styled(favorites, "", self.style)
            .into_iter()
            .map(Row::from)
            .collect();
        if let Some(open) = &self.open {
            let id = open.id;
            self.open = index
                .all()
                .find(|c| c.id == id)
                .map(|c| ContactCard::new(c, self.style.format));
        }
        if let Some(id) = self.group_page {
            match self.group(id) {
                Some(group) => self.group_rows = self.rows_of_group(group),
                None => {
                    self.group_page = None;
                    self.group_rows.clear();
                }
            }
        }
        self.group_items = self.build_group_items();
        self.apply_marks();
        self.refill_others(cx);
        cx.notify();
    }

    /// Rebuilds the Contacts rows alone, for the query and the name style
    /// (each keystroke lands here; Favorites and groups do not follow it).
    fn rebuild_rows(&mut self, cx: &mut Context<Self>) {
        let query = self.field.read(cx).text().to_owned();
        self.rows = match self.index() {
            Some(index) => {
                let shown: Vec<&WireContact> = if query.trim().is_empty() {
                    contacts_view::ordered(index.all(), self.style)
                } else {
                    index.search(&query)
                };
                contacts_view::build_rows_styled(shown, &query, self.style)
                    .into_iter()
                    .map(|vm| {
                        let mut row = Row::from(vm);
                        row.set_whatsapp(self.wa.marked.contains(&row.vm.id));
                        row
                    })
                    .collect()
            }
            None => Vec::new(),
        };
        // A new query starts at the top; a pull or a star keeps the place.
        let new_query = query != self.built_query;
        refill(
            &self.contacts_list,
            self.rows.len(),
            Some(self.search_focus(cx)),
            new_query,
        );
        self.built_query = query;
        cx.notify();
    }

    /// The search field's focus handle (the Contacts header keeps it drawn).
    fn search_focus(&self, cx: &App) -> FocusHandle {
        self.field.read(cx).focus_handle()
    }

    /// Re-points the Favorites and group lists at the rows just built,
    /// keeping their scroll positions.
    fn refill_others(&self, cx: &App) {
        refill(&self.favorites_list, self.favorites.len(), None, false);
        self.refill_groups(false, cx);
    }

    fn open_contact(&mut self, id: i64, cx: &mut Context<Self>) {
        let format = self.style.format;
        self.open = self
            .index()
            .and_then(|index| index.all().find(|c| c.id == id))
            .map(|c| ContactCard::new(c, format));
        self.open_draft = None;
        self.notice = None;
        // The lists keep their own scroll; the card starts at its top.
        self.scroll.set_offset(gpui::point(px(0.0), px(0.0)));
        self.page.restart();
        cx.notify();
        if self.open.is_none() {
            return;
        }
        self.check_whatsapp(cx);
        // The rest of the card (organisation, emails, note, groups) follows.
        cx.spawn(async move |this, cx| {
            let draft = cx
                .background_executor()
                .spawn(async move { backend::pull_detail(id) })
                .await;
            this.update(cx, |this, cx| {
                if this.open.as_ref().is_some_and(|card| card.id == id) {
                    this.open_draft = draft.ok();
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    fn set_whatsapp_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.whatsapp_open = open;
        self.notice = None;
        self.page.restart();
        cx.notify();
    }

    /// The shared WhatsApp settings screen, pushed over Settings.
    fn whatsapp_page(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        div()
            .child(
                div().px(px(8.0)).pt(px(48.0)).child(
                    glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                        .id("whatsapp-back")
                        .on_click(cx.listener(|this, _, _, cx| this.set_whatsapp_open(false, cx))),
                ),
            )
            .child(large_title(theme, "WhatsApp", None))
            .child(self.whatsapp.clone())
            .into_any_element()
    }

    fn close_contact(&mut self, cx: &mut Context<Self>) {
        self.open = None;
        self.open_draft = None;
        self.page.restart();
        cx.notify();
    }

    /// Whether System Back has a layer to close: the confirm card, the photo sheet, the editor,
    /// a card or group page, the WhatsApp page, or any tab but the first. When it has not,
    /// Back leaves the app.
    fn can_go_back(&self, cx: &App) -> bool {
        self.confirm.is_some()
            || self.photo_sheet.is_open()
            || self.editor.is_some()
            || self.group_edit.is_some()
            || self.open.is_some()
            || self.group_page.is_some()
            || self.whatsapp_open
            || self.whatsapp.read(cx).can_go_back()
            || self.tabs.selected() != CONTACTS_TAB
    }

    /// System Back: one step out of the top-most layer.
    fn go_back(&mut self, cx: &mut Context<Self>) {
        if self.confirm.is_some() {
            self.answer(false, cx);
        } else if self.photo_sheet.is_open() {
            self.close_photo_sheet(cx);
        } else if self.editor.is_some() {
            self.close_editor(cx);
        } else if self.group_edit.is_some() {
            self.cancel_group_edit(cx);
        } else if self.open.is_some() {
            self.close_contact(cx);
        } else if self.group_page.is_some() {
            self.close_group(cx);
        } else if self.whatsapp_open {
            // The WhatsApp settings step back inside themselves first.
            if !self
                .whatsapp
                .update(cx, |whatsapp, cx| whatsapp.go_back(cx))
            {
                self.set_whatsapp_open(false, cx);
            }
        } else if self.tabs.select(CONTACTS_TAB) {
            self.notice = None;
            self.page.restart();
            cx.notify();
        }
    }

    /// Runs an Android action off the UI thread and reports a failure in the
    /// notice line. `what` completes "Can't …".
    fn act(
        &mut self,
        what: &'static str,
        action: impl FnOnce() -> rovsti_android::Result<bool> + Send + 'static,
        cx: &mut Context<Self>,
    ) {
        self.notice = None;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { action() })
                .await;
            let notice: Option<SharedString> = match result {
                Ok(true) => None,
                Ok(false) => Some(format!("Can't {what}: no app answered.").into()),
                Err(error) => Some(format!("Can't {what}: {error}.").into()),
            };
            this.update(cx, |this, cx| {
                this.notice = notice;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Stars or unstars a contact: the list changes at once, then Android is
    /// asked. On desktop the change stays local to this run.
    fn toggle_star(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(index) = self.index() else { return };
        let mut all: Vec<WireContact> = index.all().cloned().collect();
        let Some(contact) = all.iter_mut().find(|c| c.id == id) else {
            return;
        };
        contact.starred = !contact.starred;
        let starred = contact.starred;
        self.book = Book::Ready(Arc::new(rovsti_core::search::ContactIndex::new(all)));
        if let Some(draft) = self.open_draft.as_mut().filter(|d| d.id == Some(id)) {
            draft.starred = starred;
        }
        self.rebuild(cx);
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { rovsti_android::providers::set_starred(id, starred) })
                .await;
            this.update(cx, |this, cx| match result {
                Err(rovsti_android::Error::Unsupported) => {}
                Ok(true) => this.reload(cx),
                Ok(false) => {
                    this.notice = Some("Android refused the change to Favorites.".into());
                    this.reload(cx);
                }
                Err(error) => {
                    this.notice = Some(format!("Can't change Favorites: {error}.").into());
                    this.reload(cx);
                }
            })
            .ok();
        })
        .detach();
    }

    /// Switches the name style without saving it (the saved one was read).
    fn apply_style(&mut self, style: NameStyle, cx: &mut Context<Self>) {
        if self.style != style {
            self.style = style;
            self.rebuild(cx);
        }
    }

    /// The owner picked a name style: show it and remember it.
    fn set_style(&mut self, style: NameStyle, cx: &mut Context<Self>) {
        if self.style != style {
            self.style_touched = true;
            self.apply_style(style, cx);
            self.save_prefs(cx);
        }
    }

    fn save_prefs(&self, cx: &mut Context<Self>) {
        let Some(dir) = self.prefs_dir.clone() else {
            return;
        };
        let saved = ContactsPrefs {
            save_to: self.save_to.clone(),
            ..ContactsPrefs::from_style(self.style)
        };
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = prefs::save(&dir, saved) {
                    log::warn!("could not save the contacts preferences: {error}");
                }
            })
            .detach();
    }

    // ---- photos ----

    /// Asks for the photo of the open card or of the contact being edited.
    /// List rows ask for their own photos as the virtualised lists draw them.
    fn request_photos(&mut self, cx: &mut Context<Self>) {
        let id = match (&self.editor, &self.open) {
            (Some(editor), _) => editor.id,
            (None, Some(card)) => Some(card.id),
            (None, None) => None,
        };
        let Some(id) = id.filter(|id| self.photos.needs(*id)) else {
            return;
        };
        if let Some(uri) = self.photo_uri_of(id).map(str::to_owned) {
            self.want_photos([(id, uri.as_str())], cx);
        }
    }

    // ---- views ----

    fn search_field(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let field = self.field.clone();
        let focus = field.read(cx).focus_handle();
        let text: SharedString = field.read(cx).text().to_owned().into();
        let typing = focus.is_focused(window);
        let text_box = div()
            .relative()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .items_center()
            .overflow_hidden()
            .text_size(typography::BODY.size)
            .child(input_layer(field.clone(), cx))
            // The placeholder goes as soon as the field is touched.
            .child(if text.is_empty() && !typing {
                div()
                    .text_color(colors.tertiary_label)
                    .child("Search name or number")
            } else if typing {
                // While typing the end of the query counts: cut the start, not the end.
                div()
                    .overflow_hidden()
                    .whitespace_nowrap()
                    .text_ellipsis_start()
                    .text_color(colors.label)
                    .child(text.clone())
            } else {
                div()
                    .truncate()
                    .text_color(colors.label)
                    .child(text.clone())
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
        let clear = (!text.is_empty()).then(|| {
            div()
                .id("search-clear")
                .size(px(36.0))
                .flex_none()
                .flex()
                .items_center()
                .justify_center()
                .child(
                    svg()
                        .path(Icon::LucideX.path())
                        .size(px(16.0))
                        .text_color(colors.secondary_label),
                )
                .on_click({
                    let field = field.clone();
                    move |_, _, cx| field.update(cx, |field, cx| field.clear(cx))
                })
        });
        div()
            .id("search")
            .track_focus(&focus)
            .on_key_down(on_backspace(field.clone()))
            .flex_none()
            .h(px(FIELD_H))
            .mx(metrics::ROW_INSET)
            .mb(px(8.0))
            .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                window.focus(&focus, cx);
                window.show_soft_keyboard();
            })
            .child(
                glass(GlassSpec::control(theme))
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
                            .text_color(colors.secondary_label),
                    )
                    .child(text_box)
                    .children(clear),
            )
            .into_any_element()
    }

    fn contact_row(
        &self,
        theme: Theme,
        row: &Row,
        key: &'static str,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let id = row.vm.id;
        let tint = hue_pair(theme, row.vm.hue).0;
        let photo = row.vm.photo_uri.as_ref().and_then(|_| self.photos.get(id));
        list_row(
            theme,
            Some(photo_avatar(theme, row.monogram.clone(), 40.0, tint, photo).into_any_element()),
            row.name.clone(),
            row.subtitle.clone(),
            row.trailing.clone(),
        )
        .id((key, usize::try_from(id).unwrap_or_default()))
        .on_click(cx.listener(move |this, _, _, cx| this.open_contact(id, cx)))
        .into_any_element()
    }

    fn row_set(&self, set: RowSet) -> &[Row] {
        match set {
            RowSet::Contacts => &self.rows,
            RowSet::Favorites => &self.favorites,
            RowSet::Group => &self.group_rows,
        }
    }

    /// Item `i` of a virtualised contact list: the row, under its section
    /// letter when it starts one. Asks for the row's photo as it is drawn
    /// (the list also draws the rows just beyond the screen's edges).
    fn contact_item(
        &mut self,
        theme: Theme,
        set: RowSet,
        i: usize,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let wanted = {
            let rows = match set {
                RowSet::Contacts => &self.rows,
                RowSet::Favorites => &self.favorites,
                RowSet::Group => &self.group_rows,
            };
            let photos = &mut self.photos;
            rows.get(i).and_then(|row| {
                let id = row.vm.id;
                row.vm
                    .photo_uri
                    .as_ref()
                    .filter(|_| photos.needs(id))
                    .map(|uri| (id, uri.clone()))
            })
        };
        if let Some((id, uri)) = wanted {
            self.want_photos([(id, uri.as_str())], cx);
        }
        let Some(row) = self.row_set(set).get(i) else {
            return div().into_any_element();
        };
        let key = match set {
            RowSet::Contacts => "contact",
            RowSet::Favorites => "favorite",
            RowSet::Group => "group-contact",
        };
        let item = self.contact_row(theme, row, key, cx);
        if row.vm.header && set == RowSet::Contacts {
            div()
                .flex()
                .flex_col()
                .child(Self::letter_header(theme, row.letter.clone()))
                .child(item)
                .into_any_element()
        } else {
            item
        }
    }

    fn letter_header(theme: Theme, letter: SharedString) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .px(metrics::ROW_INSET)
            .pt(px(12.0))
            .pb(px(4.0))
            .text_size(typography::FOOTNOTE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(colors.secondary_label)
            .child(letter)
            .into_any_element()
    }

    /// Loading, permission and error states shared by both list tabs.
    fn book_state(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        match self.book {
            Book::Ready(_) => None,
            Book::Loading => Some(div().h(px(120.0)).into_any_element()),
            Book::Denied => Some(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .child(empty_state(
                        theme,
                        "Allow Access to Contacts",
                        "Rovsti Contacts reads your address book on this phone. Nothing is uploaded.",
                    ))
                    .child(
                        glass_pill(theme, "Allow Access")
                            .id("allow")
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.act(
                                    "ask for access",
                                    || permissions::request(AccessGroup::Contacts),
                                    cx,
                                );
                            })),
                    )
                    .into_any_element(),
            ),
            Book::Unavailable => Some(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .child(empty_state(
                        theme,
                        "Can't Load Contacts",
                        "Something went wrong reading your address book.",
                    ))
                    .child(
                        glass_pill(theme, "Try Again")
                            .id("retry")
                            .on_click(cx.listener(|this, _, _, cx| this.reload(cx))),
                    )
                    .into_any_element(),
            ),
        }
    }

    /// A virtualised page: `item` builds item `ix` (0 is the header) only for
    /// the items on screen. `bottom` keeps the last rows clear of the tab bar
    /// and the keyboard.
    fn list_page(
        list_state: &ListState,
        bottom: Pixels,
        item: impl Fn(&mut Self, usize, &mut Window, &mut Context<Self>) -> AnyElement + 'static,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        list(
            list_state.clone(),
            cx.processor(move |this, ix: usize, window, cx| item(this, ix, window, cx)),
        )
        .size_full()
        .pb(bottom)
        .into_any_element()
    }

    /// The Contacts tab: the header (title, search field, any state), then
    /// one item per row.
    fn contacts_item(
        &mut self,
        ix: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        if ix > 0 {
            return self.contact_item(theme, RowSet::Contacts, ix - 1, cx);
        }
        let trailing = matches!(self.book, Book::Ready(_)).then(|| {
            glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
                .id("new-contact")
                .on_click(cx.listener(|this, _, _, cx| this.open_new_editor(cx)))
                .into_any_element()
        });
        let body = if let Some(state) = self.book_state(theme, cx) {
            Some(state)
        } else if self.rows.is_empty() {
            let query = self.field.read(cx).text();
            Some(if query.trim().is_empty() {
                empty_state(
                    theme,
                    "No Contacts",
                    "Contacts with a phone number appear here.",
                )
                .into_any_element()
            } else {
                empty_state(
                    theme,
                    "No Results",
                    format!("No contact matches “{}”.", contacts_view::clip_query(query)),
                )
                .into_any_element()
            })
        } else {
            None
        };
        div()
            .flex()
            .flex_col()
            .child(large_title(theme, TABS[CONTACTS_TAB], trailing))
            .child(self.search_field(theme, window, cx))
            .children(body)
            .into_any_element()
    }

    /// The Favorites tab: the header (title, any state), then the rows.
    fn favorites_item(
        &mut self,
        ix: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        if ix > 0 {
            return self.contact_item(theme, RowSet::Favorites, ix - 1, cx);
        }
        let body = self.book_state(theme, cx).or_else(|| {
            self.favorites.is_empty().then(|| {
                empty_state(
                    theme,
                    "No Favorites",
                    "Open a contact and tap the star to keep them here.",
                )
                .into_any_element()
            })
        });
        div()
            .flex()
            .flex_col()
            .child(large_title(theme, TABS[FAVORITES_TAB], None))
            .children(body)
            .into_any_element()
    }

    fn choice_row(
        &self,
        theme: Theme,
        key: &'static str,
        label: &'static str,
        selected: bool,
        style: NameStyle,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .id(key)
            .flex()
            .items_center()
            .justify_between()
            .h(metrics::MIN_HIT)
            .px(metrics::ROW_INSET)
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .text_size(typography::BODY.size)
            .text_color(colors.label)
            .child(label)
            .children(selected.then(|| {
                svg()
                    .path(Icon::LucideCheck.path())
                    .size(px(18.0))
                    .text_color(colors.accent)
            }))
            .on_click(cx.listener(move |this, _, _, cx| this.set_style(style, cx)))
            .into_any_element()
    }

    fn settings_tab(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let style = self.style;
        let format = |format| NameStyle { format, ..style };
        let sort = |sort| NameStyle { sort, ..style };
        let whatsapp_state = if self.whatsapp.read(cx).is_connected() {
            "Connected"
        } else if self.whatsapp.read(cx).link().is_some() {
            "Linked"
        } else {
            "Not linked"
        };
        div()
            .flex()
            .flex_col()
            .gap(px(24.0))
            .child(section(
                theme,
                Some("Name format".into()),
                [
                    self.choice_row(
                        theme,
                        "format-first",
                        "First Last",
                        style.format == NameFormat::FirstLast,
                        format(NameFormat::FirstLast),
                        cx,
                    ),
                    self.choice_row(
                        theme,
                        "format-last",
                        "Last, First",
                        style.format == NameFormat::LastFirst,
                        format(NameFormat::LastFirst),
                        cx,
                    ),
                ],
            ))
            .child(section(
                theme,
                Some("Sort by".into()),
                [
                    self.choice_row(
                        theme,
                        "sort-first",
                        "First name",
                        style.sort == SortBy::FirstName,
                        sort(SortBy::FirstName),
                        cx,
                    ),
                    self.choice_row(
                        theme,
                        "sort-last",
                        "Last name",
                        style.sort == SortBy::LastName,
                        sort(SortBy::LastName),
                        cx,
                    ),
                ],
            ))
            .child(section(
                theme,
                Some("Contacts file".into()),
                [
                    tap_row(
                        theme,
                        "export-vcf",
                        Some(Icon::LucideUpload),
                        "Export to a .vcf file",
                        Tone::Accent,
                    )
                    .opacity(if self.transfer_busy { 0.4 } else { 1.0 })
                    .on_click(cx.listener(|this, _, _, cx| this.export_contacts(cx)))
                    .into_any_element(),
                    tap_row(
                        theme,
                        "import-vcf",
                        Some(Icon::LucideDownload),
                        "Import from a .vcf file",
                        Tone::Accent,
                    )
                    .opacity(if self.transfer_busy { 0.4 } else { 1.0 })
                    .on_click(cx.listener(|this, _, _, cx| this.import_contacts(cx)))
                    .into_any_element(),
                ],
            ))
            .child(section(
                theme,
                Some("WhatsApp".into()),
                [
                    list_row(theme, None, "WhatsApp", Some(whatsapp_state.into()), None)
                        .id("whatsapp-open")
                        .on_click(cx.listener(|this, _, _, cx| this.set_whatsapp_open(true, cx)))
                        .into_any_element(),
                ],
            ))
            .child(
                div()
                    .px(px(32.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(
                        "Contacts are kept in this phone's address book. \
                         Files you export or import stay where you save them. \
                         Only WhatsApp goes through your Rovsti gateway.",
                    ),
            )
            .into_any_element()
    }

    fn notice(&self, theme: Theme) -> Option<AnyElement> {
        let colors = theme::colors(theme);
        let text = self.notice.clone()?;
        Some(
            div()
                .absolute()
                .left_0()
                .right_0()
                .bottom(content_bottom_inset() + px(8.0))
                .flex()
                .justify_center()
                .px(px(24.0))
                .child(
                    glass(GlassSpec::control(theme))
                        // A tap on the notice must not reach the page under it.
                        .occlude()
                        .rounded_full()
                        .px(px(16.0))
                        .py(px(10.0))
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.label)
                        .child(text),
                )
                .into_any_element(),
        )
    }
}

impl Render for ContactsApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let tab = self.tabs.selected();
        let want_back = self.can_go_back(cx);
        if want_back != self.back_enabled {
            self.back_enabled = want_back;
            window.set_back_enabled(want_back);
        }
        self.request_photos(cx);
        let fading = self.photos.fading();
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame_timed(window, |dt, real| {
            self.tabs.tick(dt)
                | self.page.tick(dt)
                | fading
                | self.photo_sheet.tick(dt)
                | self.photo_sheet.tick_real(real)
        });
        self.motion = motion;
        let entity = cx.entity();
        let touch_entity = entity.clone();
        let editing = self.editor.is_some();

        // The keyboard covers the bottom of the screen: leave room to scroll.
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let bottom = content_bottom_inset() + keyboard;

        // Lists that grow with the address book are virtualised and scroll
        // themselves; the other pages scroll as one block.
        let (page, listed) = if editing {
            (self.editor_view(theme, window, cx), false)
        } else if let Some(card) = &self.open {
            (self.detail(card, theme, cx), false)
        } else if let Some(id) = self.group_page {
            let page = Self::list_page(
                &self.group_list,
                bottom,
                move |this, ix, window, cx| this.group_page_item(id, ix, window, cx),
                cx,
            );
            (page, true)
        } else if self.whatsapp_open && tab == SETTINGS_TAB {
            (self.whatsapp_page(theme, cx), false)
        } else {
            match tab {
                CONTACTS_TAB => (
                    Self::list_page(&self.contacts_list, bottom, Self::contacts_item, cx),
                    true,
                ),
                FAVORITES_TAB => (
                    Self::list_page(&self.favorites_list, bottom, Self::favorites_item, cx),
                    true,
                ),
                GROUPS_TAB => (
                    Self::list_page(&self.groups_list, bottom, Self::groups_item, cx),
                    true,
                ),
                _ => (
                    div()
                        .child(large_title(theme, TABS[tab], None))
                        .child(self.settings_tab(theme, cx))
                        .into_any_element(),
                    false,
                ),
            }
        };
        let page = div()
            .relative()
            .top(px(self.page.offset(24.0)))
            .when(listed, |page| page.size_full())
            .child(page);
        let scroller = div().id("page").size_full().opacity(self.page.progress());
        let scroller = if listed {
            scroller.child(page)
        } else {
            scroller
                .overflow_y_scroll()
                .track_scroll(&self.scroll)
                .pb(bottom)
                .child(page)
        };

        div()
            .size_full()
            .relative()
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.tabs.release();
                    cx.notify();
                }),
            )
            .child(rovsti_ui::components::backdrop(
                theme,
                theme::colors(theme).green,
            ))
            .child(scroller)
            .children(self.notice(theme))
            // No tab bar while a form is open: no stray tap loses the edits.
            .children((!editing).then(|| {
                self.tabs.render(
                    theme,
                    move |index, _, cx| {
                        entity.update(cx, |this, cx| {
                            this.tabs.press();
                            let changed = this.tabs.select(index);
                            if changed
                                || this.open.is_some()
                                || this.group_page.is_some()
                                || this.group_edit.is_some()
                                || this.whatsapp_open
                            {
                                this.whatsapp_open = false;
                                this.open = None;
                                this.open_draft = None;
                                this.group_page = None;
                                this.group_edit = None;
                                this.notice = None;
                                this.page.restart();
                            }
                            cx.notify();
                        });
                    },
                    move |phase, _, cx| {
                        touch_entity.update(cx, |this, cx| {
                            this.tabs.touch(phase);
                            cx.notify();
                        });
                    },
                )
            }))
            .children(self.confirm_view(theme, cx))
            .children(self.photo_sheet_view(theme, window, cx))
    }
}

#[gpui_platform::main]
fn main() {
    // Keeps the JNI entry points in the final library (see `bridge::keep_symbols`).
    rovsti_android::bridge::keep_symbols();
    gpui_platform::application()
        .with_assets(IconAssets::new())
        .run(|cx: &mut App| {
            cx.open_window(
                WindowOptions {
                    window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                        None,
                        size(px(402.0), px(874.0)),
                        cx,
                    ))),
                    ..Default::default()
                },
                |window, cx| {
                    let app = cx.new(|cx| ContactsApp::new(window, cx));
                    // System Back steps out of the top-most layer, then to the first tab.
                    let weak = app.downgrade();
                    window.on_system_back(cx, move |_, cx| {
                        weak.update(cx, |this, cx| this.go_back(cx)).ok();
                    });
                    app
                },
            )
            .expect("failed to open the Rovsti Contacts window");
            cx.activate(true);
        });
}
