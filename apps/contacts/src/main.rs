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

use std::{path::PathBuf, sync::Arc};

use futures::StreamExt;
use gpui::{
    AnyElement, App, AppContext as _, Bounds, Context, Entity, MouseButton, ParentElement, Render,
    ScrollHandle, SharedString, StatefulInteractiveElement, Styled, Window, WindowBounds,
    WindowOptions, div, prelude::*, px, size, svg,
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
/// A list row is about this tall; used to guess which rows are on screen.
const ROW_PX: f32 = 58.0;
/// What sits above the first row (title and search field), in pixels.
const LIST_TOP_PX: f32 = 150.0;
/// Rows kept ready beyond the screen's edges when asking for photos.
const PHOTO_MARGIN: usize = 10;

/// Section letters and row texts as shared strings, built once per change.
struct Row {
    vm: ContactVm,
    letter: SharedString,
    name: SharedString,
    subtitle: Option<SharedString>,
    monogram: SharedString,
}

impl From<ContactVm> for Row {
    fn from(vm: ContactVm) -> Self {
        Self {
            letter: vm.letter.to_string().into(),
            name: vm.name.clone().into(),
            subtitle: vm.subtitle.clone().map(Into::into),
            monogram: vm.monogram.map(String::from).unwrap_or_default().into(),
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
    favorites: Vec<Row>,
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
    /// The shared WhatsApp settings screen, and whether its page is showing.
    whatsapp: Entity<WhatsAppSettings>,
    whatsapp_open: bool,
}

impl ContactsApp {
    fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let field = cx.new(TextField::new);
        cx.observe(&field, |this, field, cx| {
            let edits = field.read(cx).edits;
            if edits != this.seen_edits {
                this.seen_edits = edits;
                this.rebuild(cx);
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
        // The Settings row shows whether WhatsApp is linked.
        cx.observe(&whatsapp, |_, _, cx| cx.notify()).detach();
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
            favorites: Vec::new(),
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
            whatsapp,
            whatsapp_open: false,
        };
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
        let query = self.field.read(cx).text().to_owned();
        let Some(index) = self.index().cloned() else {
            self.rows.clear();
            self.favorites.clear();
            self.group_rows.clear();
            cx.notify();
            return;
        };
        let shown: Vec<&WireContact> = if query.trim().is_empty() {
            contacts_view::ordered(index.all(), self.style)
        } else {
            index.search(&query)
        };
        self.rows = contacts_view::build_rows_styled(shown, &query, self.style)
            .into_iter()
            .map(Row::from)
            .collect();
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
        cx.notify();
    }

    fn open_contact(&mut self, id: i64, cx: &mut Context<Self>) {
        let format = self.style.format;
        self.open = self
            .index()
            .and_then(|index| index.all().find(|c| c.id == id))
            .map(|c| ContactCard::new(c, format));
        self.open_draft = None;
        self.notice = None;
        self.page.restart();
        cx.notify();
        if self.open.is_none() {
            return;
        }
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

    /// Asks for the photos of the rows near the screen, and of the open card.
    fn request_photos(&mut self, tab: usize, cx: &mut Context<Self>) {
        let mut wanted: Vec<(i64, String)> = Vec::new();
        if let Some(card) = &self.open {
            if let Some(uri) = self.photo_uri_of(card.id) {
                wanted.push((card.id, uri.to_owned()));
            }
        } else if self.editor.is_none() {
            let list = if self.group_page.is_some() {
                Some(&self.group_rows)
            } else {
                match tab {
                    CONTACTS_TAB => Some(&self.rows),
                    FAVORITES_TAB => Some(&self.favorites),
                    _ => None,
                }
            };
            if let Some(list) = list {
                let scrolled = f32::from(-self.scroll.offset().y).max(0.0);
                let viewport = f32::from(self.scroll.bounds().size.height);
                let first = ((scrolled - LIST_TOP_PX) / ROW_PX).max(0.0) as usize;
                let visible = (viewport / ROW_PX) as usize + 2;
                wanted.extend(
                    list.iter()
                        .skip(first.saturating_sub(PHOTO_MARGIN))
                        .take(visible + 2 * PHOTO_MARGIN)
                        .filter_map(|row| {
                            row.vm
                                .photo_uri
                                .as_ref()
                                .map(|uri| (row.vm.id, uri.clone()))
                        }),
                );
            }
        }
        if !wanted.is_empty() {
            self.want_photos(wanted.iter().map(|(id, uri)| (*id, uri.as_str())), cx);
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
        let star = row.vm.starred.then(|| SharedString::from("★"));
        let photo = row.vm.photo_uri.as_ref().and_then(|_| self.photos.get(id));
        list_row(
            theme,
            Some(photo_avatar(theme, row.monogram.clone(), 40.0, tint, photo).into_any_element()),
            row.name.clone(),
            row.subtitle.clone(),
            star,
        )
        .id((key, usize::try_from(id).unwrap_or_default()))
        .on_click(cx.listener(move |this, _, _, cx| this.open_contact(id, cx)))
        .into_any_element()
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

    fn contacts_tab(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let field = self.search_field(theme, window, cx);
        let body = if let Some(state) = self.book_state(theme, cx) {
            state
        } else if self.rows.is_empty() {
            let query = self.field.read(cx).text().to_owned();
            if query.trim().is_empty() {
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
                    format!(
                        "No contact matches “{}”.",
                        contacts_view::clip_query(&query)
                    ),
                )
                .into_any_element()
            }
        } else {
            let mut children: Vec<AnyElement> = Vec::with_capacity(self.rows.len() + 27);
            for row in &self.rows {
                if row.vm.header {
                    children.push(Self::letter_header(theme, row.letter.clone()));
                }
                children.push(self.contact_row(theme, row, "contact", cx));
            }
            div()
                .flex()
                .flex_col()
                .children(children)
                .into_any_element()
        };
        div()
            .flex()
            .flex_col()
            .child(field)
            .child(body)
            .into_any_element()
    }

    fn favorites_tab(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        if let Some(state) = self.book_state(theme, cx) {
            return state;
        }
        if self.favorites.is_empty() {
            return empty_state(
                theme,
                "No Favorites",
                "Open a contact and tap the star to keep them here.",
            )
            .into_any_element();
        }
        let rows: Vec<AnyElement> = self
            .favorites
            .iter()
            .map(|row| self.contact_row(theme, row, "favorite", cx))
            .collect();
        div().flex().flex_col().children(rows).into_any_element()
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
        self.request_photos(tab, cx);
        let fading = self.photos.fading();
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| {
            self.tabs.tick(dt) | self.page.tick(dt) | fading
        });
        self.motion = motion;
        let entity = cx.entity();
        let touch_entity = entity.clone();
        let editing = self.editor.is_some();

        let page = if editing {
            self.editor_view(theme, window, cx)
        } else if let Some(card) = self.open.clone() {
            self.detail(&card, theme, cx)
        } else if let Some(id) = self.group_page {
            self.group_page_view(theme, id, window, cx)
        } else if self.whatsapp_open && tab == SETTINGS_TAB {
            self.whatsapp_page(theme, cx)
        } else {
            let body = match tab {
                CONTACTS_TAB => self.contacts_tab(theme, window, cx),
                FAVORITES_TAB => self.favorites_tab(theme, cx),
                GROUPS_TAB => self.groups_tab(theme, window, cx),
                _ => self.settings_tab(theme, cx),
            };
            let trailing =
                (tab == CONTACTS_TAB && matches!(self.book, Book::Ready(_))).then(|| {
                    glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
                        .id("new-contact")
                        .on_click(cx.listener(|this, _, _, cx| this.open_new_editor(cx)))
                        .into_any_element()
                });
            div()
                .child(large_title(theme, TABS[tab], trailing))
                .child(body)
                .into_any_element()
        };

        // The keyboard covers the bottom of the screen: leave room to scroll.
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));

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
            .child(
                div()
                    .id("page")
                    .size_full()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll)
                    .pb(content_bottom_inset() + keyboard)
                    .opacity(self.page.progress())
                    .child(div().relative().top(px(self.page.offset(24.0))).child(page)),
            )
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
                |window, cx| cx.new(|cx| ContactsApp::new(window, cx)),
            )
            .expect("failed to open the Rovsti Contacts window");
            cx.activate(true);
        });
}
