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
mod dupes;
mod editor;
mod field;
mod groups;
mod photo_crop;
mod photo_viewer;
mod photos;
mod picker;
mod prefs;
mod qr_sheet;
mod recent;
mod sample;
mod select;
mod transfer;
mod ui;
mod upcoming;
mod whatsapp;

use std::{iter, path::PathBuf, sync::Arc};

use futures::StreamExt;
use gpui::{
    AnyElement, App, AppContext as _, Bounds, Context, Entity, FocusHandle, KeyDownEvent,
    ListAlignment, ListOffset, ListState, MouseButton, ParentElement, Pixels, Render, ScrollHandle,
    SharedString, StatefulInteractiveElement, Styled, Window, WindowBounds, WindowOptions, canvas,
    div, list, prelude::*, px, size, svg,
};
use rovsti_android::{
    events,
    permissions::{self, Group as AccessGroup},
};
use rovsti_core::{
    contact_card::{self, ContactCard},
    contact_edit::{Account, ContactDraft, Group, account_label},
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

use rovsti_wa_ui::{AppId, WaAvatars, WhatsAppSettings};

use crate::{
    backend::Book,
    confirm::Confirm,
    editor::Editor,
    field::{TextField, input_layer, on_backspace},
    groups::GroupEdit,
    photos::{Photos, photo_avatar},
    picker::Picker,
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
/// The scrubber column starts below the title and search field.
const SCRUBBER_TOP: f32 = 150.0;
const SCRUBBER_BOTTOM_GAP: f32 = 8.0;
/// A list row is about this tall: the height a virtualised list assumes for
/// rows it has not measured yet (it measures the real one when drawn).
const ROW_PX: f32 = 58.0;
/// Rows measured (and their photos asked for) beyond the screen's edges.
const LIST_OVERDRAW_PX: f32 = 600.0;
/// Requests for the data folder before giving up, and the pause between them.
const DATA_DIR_ATTEMPTS: u32 = 5;
const DATA_DIR_RETRY: std::time::Duration = std::time::Duration::from_millis(400);
/// A notice goes away after this long (one that ends in "…" stays: it is
/// replaced by the outcome).
const NOTICE_LIFE: std::time::Duration = std::time::Duration::from_secs(4);
/// A notice with an action (Undo) stays this long.
const UNDO_LIFE: std::time::Duration = std::time::Duration::from_secs(5);
/// Space kept between a focused editor field and the keyboard.
const REVEAL_MARGIN: f32 = 16.0;

/// The button a notice carries.
#[derive(Clone, Copy, PartialEq, Eq)]
enum NoticeAction {
    UndoDelete,
    UndoMerge,
}

/// Whether a notice button survives a clear: only the Undo of a waiting delete
/// (`pending`) or of a merge that can still be undone (`merged`).
fn keeps_undo(action: Option<NoticeAction>, pending: bool, merged: bool) -> bool {
    match action {
        Some(NoticeAction::UndoDelete) => pending,
        Some(NoticeAction::UndoMerge) => merged,
        None => false,
    }
}

#[cfg(test)]
mod notice_tests {
    use super::*;

    #[test]
    fn undo_is_kept_while_pending() {
        assert!(keeps_undo(Some(NoticeAction::UndoDelete), true, false));
    }

    #[test]
    fn undo_is_dropped_without_pending() {
        assert!(!keeps_undo(Some(NoticeAction::UndoDelete), false, true));
    }

    #[test]
    fn merge_undo_is_kept_while_it_can_be_undone() {
        assert!(keeps_undo(Some(NoticeAction::UndoMerge), false, true));
        assert!(!keeps_undo(Some(NoticeAction::UndoMerge), true, false));
    }

    #[test]
    fn undo_of_a_multi_delete_is_kept_while_pending() {
        // The bulk delete uses the same action; only `pending` matters.
        assert!(keeps_undo(Some(NoticeAction::UndoDelete), true, true));
        assert!(!keeps_undo(Some(NoticeAction::UndoDelete), false, true));
    }

    #[test]
    fn a_multi_delete_hides_every_id_from_the_export() {
        let ids = vec![2_i64, 5, 9];
        let set: std::collections::BTreeSet<i64> = [1, 2, 5, 7, 9].into();
        assert_eq!(
            rovsti_core::contact_select::export_ids(&set, &ids),
            vec![1, 7]
        );
    }

    #[test]
    fn no_action_is_dropped() {
        assert!(!keeps_undo(None, true, true));
        assert!(!keeps_undo(None, false, false));
    }
}

/// Deleted contacts, hidden but not yet removed from Android.
struct PendingDelete {
    pub(crate) ids: Vec<i64>,
    task: gpui::Task<()>,
}

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
    /// The JID of the contact's WhatsApp chat, for its picture.
    wa_jid: Option<String>,
}

impl Row {
    /// Sets the WhatsApp mark and chat (computed per pull and per chats
    /// refresh, never per frame).
    fn set_whatsapp(&mut self, jid: Option<&str>) {
        if self.wa_jid.as_deref() != jid {
            self.wa_jid = jid.map(str::to_owned);
        }
        let on = jid.is_some();
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
            wa_jid: None,
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
    /// The contact picker, its rows and list.
    picker: Option<Picker>,
    picker_rows: Vec<Row>,
    picker_list: ListState,
    /// The group whose page is open, and its rows.
    group_page: Option<i64>,
    group_rows: Vec<Row>,
    group_edit: Option<GroupEdit>,
    confirm: Option<Confirm>,
    /// Open this contact once the next pull lands (after a save).
    pending_open: Option<i64>,
    /// Open this group once the next pull lands (after it was created).
    pending_group: Option<i64>,
    /// A file picker is open.
    transfer_busy: bool,
    /// How many contacts the export in flight holds.
    exported: Option<usize>,
    /// Per number of the open card: the JID when it is on WhatsApp (one
    /// chip per JID).
    card_wa_numbers: Vec<Option<String>>,
    /// An import was added: the file buttons stay busy until this pull (or a
    /// newer one) lands, so a second import is planned against the new book.
    import_settle: Option<u64>,
    /// "Use as Contact Photo" is fetching or saving.
    wa_photo_busy: bool,
    notice: Option<SharedString>,
    /// The button of `notice`, cleared with it or when another notice replaces it.
    notice_action: Option<NoticeAction>,
    /// The merge that Undo would revert, and the Find duplicates page.
    merge_undo: Option<dupes::MergeUndo>,
    dupes: dupes::Dupes,
    /// Birthdays and anniversaries within a year (soonest first), the first
    /// number of each contact for the Wish chip, and the page showing them.
    upcoming: Vec<rovsti_core::contact_dates::Upcoming>,
    upcoming_numbers: std::collections::HashMap<i64, String>,
    upcoming_loads: u64,
    upcoming_open: bool,
    recent: Vec<recent::RecentRow>,
    recent_open: bool,
    /// The alphabet scrubber: the section letter of each Contacts row, the
    /// letters shown, and the drag.
    row_letters: Vec<char>,
    scrub_letters: Vec<char>,
    scrub: upcoming::Scrub,
    /// Select mode in the Contacts list: the ticked contacts.
    select: Option<rovsti_core::multi_select::Selection<i64>>,
    select_ui: select::SelectUi,
    /// The contacts whose delete waits for their Undo window.
    pending_delete: Option<PendingDelete>,
    /// The timer that clears `notice` (the text it was started for).
    notice_timer: Option<(SharedString, gpui::Task<()>)>,
    /// Bring the focused editor field above the keyboard: the focus and the
    /// keyboard height last seen, and whether a reveal is still due.
    reveal_focus: Option<FocusHandle>,
    reveal_keyboard: Pixels,
    reveal_pending: bool,
    photos: Photos,
    /// The "Contact photo" sheet over the editor.
    photo_sheet: Sheet,
    /// The full-screen photo viewer, drawn above the card and under the sheets.
    viewer: Option<photo_viewer::PhotoViewer>,
    /// The full-screen crop page for a picked or captured photo, above the viewer.
    crop: Option<photo_crop::CropPage>,
    /// The per-field actions sheet over the contact card, and what it acts on.
    field_sheet: Sheet,
    /// The card's Share menu and the QR code sheet it opens.
    share_sheet: Sheet,
    qr_sheet: Sheet,
    qr_card: Option<qr_sheet::QrCard>,
    field_target: Option<detail::FieldTarget>,
    /// System Back is routed to the app (a sheet is open).
    back_enabled: bool,
    /// The shared WhatsApp settings screen, and whether its page is showing.
    whatsapp: Entity<WhatsAppSettings>,
    whatsapp_open: bool,
    /// WhatsApp per contact: chats, presence, lookups.
    wa: whatsapp::WaPanel,
    /// WhatsApp profile pictures and profiles, for contacts without a photo.
    avatars: Entity<WaAvatars>,
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
            } else {
                this.commit_pending_delete(cx);
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

        // The saved name format and sort order. The platform sometimes refuses
        // the first request for the data folder; asking again works.
        let data_dirs = rovsti_ui::storage::DataDirs::new("contacts");
        cx.spawn(async move |this, cx| {
            let mut attempt = 1;
            let dir = loop {
                let Ok(request) = this.update(cx, |_, cx| data_dirs.request(cx)) else {
                    return;
                };
                match request.await {
                    Ok(dir) => break dir,
                    Err(error) if attempt < DATA_DIR_ATTEMPTS => {
                        log::warn!(
                            "data folder request failed ({attempt}/{DATA_DIR_ATTEMPTS}): {error}"
                        );
                        attempt += 1;
                        cx.background_executor().timer(DATA_DIR_RETRY).await;
                    }
                    Err(error) => {
                        log::warn!("no data directory for the preferences: {error}");
                        return;
                    }
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
        // A picture or a profile arrived: the rows on screen draw it.
        let avatars = cx.new(|cx| WaAvatars::new(&whatsapp, cx));
        cx.observe(&avatars, |_, _, cx| cx.notify()).detach();
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
            picker: None,
            picker_rows: Vec::new(),
            picker_list: page_list(),
            group_page: None,
            group_rows: Vec::new(),
            group_edit: None,
            confirm: None,
            pending_open: None,
            pending_group: None,
            transfer_busy: false,
            exported: None,
            card_wa_numbers: Vec::new(),
            import_settle: None,
            wa_photo_busy: false,
            notice: None,
            notice_action: None,
            upcoming: Vec::new(),
            upcoming_numbers: std::collections::HashMap::new(),
            upcoming_loads: 0,
            upcoming_open: false,
            recent: Vec::new(),
            recent_open: false,
            row_letters: Vec::new(),
            scrub_letters: Vec::new(),
            scrub: upcoming::Scrub::default(),
            pending_delete: None,
            select: None,
            select_ui: select::SelectUi::default(),
            notice_timer: None,
            reveal_focus: None,
            reveal_keyboard: px(0.0),
            reveal_pending: false,
            photos: Photos::new(),
            photo_sheet: Sheet::new(),
            viewer: None,
            crop: None,
            field_sheet: Sheet::new(),
            share_sheet: Sheet::new(),
            qr_sheet: Sheet::new(),
            qr_card: None,
            field_target: None,
            back_enabled: false,
            whatsapp,
            whatsapp_open: false,
            merge_undo: None,
            dupes: dupes::Dupes::new(),
            wa: whatsapp::WaPanel::default(),
            avatars,
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
                    let book = match (book, this.pending_delete.as_ref()) {
                        (Book::Ready(index), Some(pending)) => {
                            Book::Ready(Arc::new(rovsti_core::search::ContactIndex::new(
                                rovsti_core::contacts_view::without(index.all(), &pending.ids),
                            )))
                        }
                        (book, _) => book,
                    };
                    this.book = book;
                    let alive: Option<std::collections::HashSet<i64>> = this
                        .index()
                        .map(|index| index.all().map(|c| c.id).collect());
                    if let (Some(select), Some(alive)) = (this.select.as_mut(), alive) {
                        select.retain(|id| alive.contains(id));
                    }
                    this.groups = groups;
                    this.accounts = accounts;
                    if this.import_settle.take_if(|id| *id <= pull_id).is_some() {
                        this.transfer_busy = false;
                    }
                    this.photos.forget_misses();
                    this.refresh_marks();
                    this.rebuild(cx);
                    this.load_upcoming(cx);
                    if let Some(id) = this.pending_open.take() {
                        this.open_contact(id, cx);
                    }
                    if let Some(id) = this.pending_group.take() {
                        // Opening a page clears the notice: keep the one for the new group.
                        let notice = this.notice.take();
                        this.open_group(id, cx);
                        this.notice = notice;
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
            self.recent.clear();
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
        self.recent = recent::build(index.all(), crate::whatsapp::now_ms(), self.wa.offset_s);
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
                        row.set_whatsapp(self.wa.marked.get(&row.vm.id).map(String::as_str));
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
        self.set_scrub_letters();
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
        self.refresh_card_wa();
        self.close_field_sheet_now();
        self.clear_notice();
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
        self.clear_notice();
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
        self.close_field_sheet_now();
        self.viewer = None;
        self.crop = None;
        self.open = None;
        self.open_draft = None;
        self.page.restart();
        cx.notify();
    }

    /// Whether System Back has a layer to close: the confirm card, the photo sheet, the editor,
    /// a card or group page, the WhatsApp page, any tab but the first, or a search. When it
    /// has not, Back leaves the app. (The WhatsApp settings' own steps count only while their
    /// page is open: `whatsapp_open`.)
    fn can_go_back(&self, cx: &App) -> bool {
        self.confirm.is_some()
            || self.photo_sheet.is_open()
            || self.field_sheet.is_open()
            || self.share_sheet.is_open()
            || self.qr_sheet.is_open()
            || self.crop.is_some()
            || self.viewer.as_ref().is_some_and(|v| !v.is_closing())
            || self.picker.is_some()
            || self.editor.is_some()
            || self.group_edit.is_some()
            || self.open.is_some()
            || self.group_page.is_some()
            || self.whatsapp_open
            || self.dupes.open
            || self.dupes.sheet.is_open()
            || self.upcoming_open
            || self.recent_open
            || self.select_ui.sheet.is_open()
            || self.select.is_some()
            || self.tabs.selected() != CONTACTS_TAB
            || !self.field.read(cx).text().is_empty()
    }

    /// System Back: one step out of the top-most layer.
    fn go_back(&mut self, cx: &mut Context<Self>) {
        if self.confirm.is_some() {
            self.answer(false, cx);
        } else if self.select_ui.sheet.is_open() {
            self.select_ui.sheet.close();
            cx.notify();
        } else if self.photo_sheet.is_open() {
            self.close_photo_sheet(cx);
        } else if self.qr_sheet.is_open() {
            self.close_qr_sheet(cx);
        } else if self.share_sheet.is_open() {
            self.close_share_sheet(cx);
        } else if self.field_sheet.is_open() {
            self.close_field_sheet(cx);
        } else if self.crop.is_some() {
            self.crop_cancel(cx);
        } else if self.viewer.as_ref().is_some_and(|v| !v.is_closing()) {
            self.request_close_viewer(cx);
        } else if self.picker.is_some() {
            self.close_picker(cx);
        } else if self.editor.is_some() {
            self.leave_editor(cx);
        } else if self.group_edit.is_some() {
            self.cancel_group_edit(cx);
        } else if self.open.is_some() {
            self.close_contact(cx);
        } else if self.group_page.is_some() {
            self.close_group(cx);
        } else if self.upcoming_open {
            self.set_upcoming_open(false, cx);
        } else if self.recent_open {
            self.set_recent_open(false, cx);
        } else if self.dupes.sheet.is_open() {
            self.close_dupes_sheet(cx);
        } else if self.dupes.open {
            self.set_dupes_open(false, cx);
        } else if self.whatsapp_open {
            // The WhatsApp settings step back inside themselves first.
            if !self
                .whatsapp
                .update(cx, |whatsapp, cx| whatsapp.go_back(cx))
            {
                self.set_whatsapp_open(false, cx);
            }
        } else if self.select.is_some() {
            self.leave_select(cx);
        } else if self.tabs.select(CONTACTS_TAB) {
            self.clear_notice();
            self.page.restart();
            cx.notify();
        } else {
            // On the Contacts tab with a search: Back clears it first.
            self.field.update(cx, |field, cx| field.clear(cx));
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
        self.clear_notice();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { action() })
                .await;
            let notice: Option<SharedString> = match result {
                Ok(true) => None,
                Ok(false) => Some(format!("Can't {what}: no app answered.").into()),
                Err(error) => {
                    Some(format!("Can't {what}: {}.", backend::android_words(&error)).into())
                }
            };
            this.update(cx, |this, cx| {
                if let Some(n) = notice {
                    this.notice = Some(n);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Stars or unstars a contact: the list changes at once, then Android is
    /// asked. On desktop the change stays local to this run.
    fn toggle_star(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(contact) = self.index().and_then(|i| i.all().find(|c| c.id == id)) else {
            return;
        };
        let starred = !contact.starred;
        self.set_starred_local(&[id], starred);
        self.rebuild(cx);
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { rovsti_android::providers::set_starred(id, starred) })
                .await;
            this.update(cx, |this, cx| {
                let done = if starred {
                    "Added to Favorites."
                } else {
                    "Removed from Favorites."
                };
                match result {
                    Err(rovsti_android::Error::Unsupported) => {
                        this.notice = Some(done.into());
                        cx.notify();
                    }
                    Ok(true) => {
                        this.notice = Some(done.into());
                        this.reload(cx);
                    }
                    Ok(false) => {
                        this.notice = Some("Android refused the change to Favorites.".into());
                        this.reload(cx);
                    }
                    Err(error) => {
                        this.notice = Some(
                            format!(
                                "Can't change Favorites: {}.",
                                backend::android_words(&error)
                            )
                            .into(),
                        );
                        this.reload(cx);
                    }
                }
            })
            .ok();
        })
        .detach();
    }

    /// Sets the star of `ids` in the book and in an open editor draft, without
    /// asking Android. The caller rebuilds the rows.
    fn set_starred_local(&mut self, ids: &[i64], starred: bool) {
        let Some(index) = self.index() else { return };
        let mut all: Vec<WireContact> = index.all().cloned().collect();
        for contact in all.iter_mut().filter(|c| ids.contains(&c.id)) {
            contact.starred = starred;
        }
        self.book = Book::Ready(Arc::new(rovsti_core::search::ContactIndex::new(all)));
        if let Some(draft) = self
            .open_draft
            .as_mut()
            .filter(|d| d.id.is_some_and(|id| ids.contains(&id)))
        {
            draft.starred = starred;
        }
    }

    /// The ids of every starred contact in the book.
    pub(crate) fn starred_ids(&self) -> Vec<i64> {
        self.index()
            .map(|i| i.all().filter(|c| c.starred).map(|c| c.id).collect())
            .unwrap_or_default()
    }

    /// Opens the picker over the Favorites.
    fn open_favorites_picker(&mut self, cx: &mut Context<Self>) {
        let initial = self.starred_ids();
        self.open_picker(picker::PickFor::Favorites, initial, cx);
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

    /// Moves the account new contacts start in to the next one, and keeps it.
    fn cycle_save_to(&mut self, cx: &mut Context<Self>) {
        let current = editor::default_account(&self.accounts, &self.save_to);
        self.save_to = editor::next_account(&self.accounts, &current);
        self.save_prefs(cx);
        let label = account_label(&editor::default_account(&self.accounts, &self.save_to));
        self.notice = Some(format!("New contacts go to {label}.").into());
        cx.notify();
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
        self.search_box(&self.field.clone(), theme, window, cx)
    }

    /// The glass search box for `field`.
    fn search_box(
        &self,
        field: &Entity<TextField>,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let field = field.clone();
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
        let picture =
            self.avatar_picture(id, row.vm.photo_uri.is_some(), row.wa_jid.as_deref(), cx);
        let photo = picture.as_ref().map(|(image, opacity)| (image, *opacity));
        // Select mode lives on the Contacts tab: its rows show a check.
        let ticked = (key == "contact")
            .then(|| self.select.as_ref().map(|s| s.contains(&id)))
            .flatten();
        list_row(
            theme,
            Some(photo_avatar(theme, row.monogram.clone(), 40.0, tint, photo).into_any_element()),
            row.name.clone(),
            row.subtitle.clone(),
            if ticked.is_some() {
                None
            } else {
                row.trailing.clone()
            },
        )
        .children(ticked.map(|on| select::check_mark(theme, on)))
        .id((key, usize::try_from(id).unwrap_or_default()))
        .on_click(cx.listener(move |this, _, _, cx| {
            if key == "contact" && this.select.is_some() {
                this.toggle_selected(id, cx);
            } else {
                this.open_contact(id, cx);
            }
        }))
        .on_long_press(cx.listener(move |this, _, _, cx| {
            if key == "contact" {
                this.long_press_contact(id, cx);
            }
        }))
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
        // A contact on WhatsApp without a readable photo of its own shows its
        // WhatsApp picture (asked for only while its row is drawn).
        let rows = match set {
            RowSet::Contacts => &self.rows,
            RowSet::Favorites => &self.favorites,
            RowSet::Group => &self.group_rows,
        };
        if let Some(row) = rows.get(i)
            && let Some(jid) = row.wa_jid.as_deref()
            && self
                .photos
                .wa_stands_in(row.vm.id, row.vm.photo_uri.is_some())
        {
            self.avatars.update(cx, |avatars, cx| {
                let picture_id = avatars.profile(jid).and_then(|p| p.picture_id.clone());
                avatars.want(jid, picture_id.as_deref(), cx);
            });
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
                        "Rovsti Contacts reads your address book on this phone. Nothing is uploaded. \
                         If Android no longer asks, allow Contacts in the app's settings.",
                    ))
                    .child(
                        glass_pill(theme, "Allow Access")
                            .id("allow")
                            .on_click(cx.listener(|this, _, _, cx| {
                                // Android no longer shows the dialog once access was
                                // refused for good: the app's settings page is the way.
                                this.act(
                                    "ask for access",
                                    || match permissions::request(AccessGroup::Contacts) {
                                        Ok(false) => permissions::open_app_details(),
                                        other => other,
                                    },
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
            // A list item is sized to its content unless told otherwise: a flex header or row
            // would shrink to its text and leave the rest of the screen empty.
            cx.processor(move |this, ix: usize, window, cx| {
                div()
                    .w_full()
                    .child(item(this, ix, window, cx))
                    .into_any_element()
            }),
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
        if self.select.is_some() {
            return self.select_header(theme, window, cx);
        }
        let trailing = matches!(self.book, Book::Ready(_)).then(|| {
            let select = (!self.rows.is_empty()).then(|| {
                glass_pill(theme, "Select")
                    .id("select-mode")
                    .on_click(cx.listener(|this, _, _, cx| this.enter_select(None, cx)))
            });
            div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .children(select)
                .child(
                    glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
                        .id("new-contact")
                        .on_click(cx.listener(|this, _, _, cx| this.open_new_editor(cx))),
                )
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
                    "Tap + to add someone, or import a .vcf file in Settings.",
                )
                .into_any_element()
            } else {
                let draft = matches!(self.book, Book::Ready(_))
                    .then(|| rovsti_core::contact_edit::draft_from_query(query))
                    .flatten();
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .child(empty_state(
                        theme,
                        "No Results",
                        format!("No contact matches “{}”.", contacts_view::clip_query(query)),
                    ))
                    .children(draft.map(|draft| {
                        glass_pill(theme, "Create Contact")
                            .id("create-from-search")
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_new_editor_with(draft.clone(), cx)
                            }))
                    }))
                    .into_any_element()
            })
        } else {
            None
        };
        let query = self.field.read(cx).text().to_owned();
        let recent_row = (matches!(self.book, Book::Ready(_))
            && contacts_view::show_recent(&query, self.recent.len()))
        .then(|| self.recent_entry(theme, cx));
        div()
            .flex()
            .flex_col()
            .child(large_title(theme, TABS[CONTACTS_TAB], trailing))
            .child(self.search_field(theme, window, cx))
            .children(recent_row)
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
                    "Tap + to choose favorites, or tap the star on a contact.",
                )
                .into_any_element()
            })
        });
        let trailing = matches!(self.book, Book::Ready(_)).then(|| {
            glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
                .id("favorites-add")
                .on_click(cx.listener(|this, _, _, cx| this.open_favorites_picker(cx)))
                .into_any_element()
        });
        let upcoming = if matches!(self.book, Book::Ready(_)) {
            self.upcoming_section(theme, cx)
        } else {
            None
        };
        div()
            .flex()
            .flex_col()
            .child(large_title(theme, TABS[FAVORITES_TAB], trailing))
            .children(upcoming)
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
            .children((self.accounts.len() > 1).then(|| {
                let label = SharedString::from(account_label(&editor::default_account(
                    &self.accounts,
                    &self.save_to,
                )));
                section(
                    theme,
                    Some("New contacts".into()),
                    [tap_row(theme, "save-to", None, "Save to", Tone::Plain)
                        .justify_between()
                        .child(
                            div()
                                .text_size(typography::FOOTNOTE.size)
                                .text_color(colors.accent)
                                .child(label),
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.cycle_save_to(cx)))
                        .into_any_element()],
                )
            }))
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
                    tap_row(
                        theme,
                        "find-duplicates",
                        Some(Icon::LucideUsers),
                        "Find duplicates",
                        Tone::Accent,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.set_dupes_open(true, cx)))
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

    /// Keeps the focused editor field above the keyboard. Runs when the focus
    /// or the keyboard height changes, and scrolls (at most) until the field
    /// is clear of the keyboard; after that the owner scrolls freely.
    /// `field` is where the focused field was painted last frame.
    fn reveal_focused_field(
        &mut self,
        field: Option<Bounds<Pixels>>,
        keyboard: Pixels,
        window: &mut Window,
        cx: &App,
    ) {
        let focus = window.focused(cx);
        if focus != self.reveal_focus || keyboard != self.reveal_keyboard {
            self.reveal_focus = focus;
            self.reveal_keyboard = keyboard;
            self.reveal_pending = true;
        }
        if !self.reveal_pending || keyboard <= px(0.0) {
            return;
        }
        let Some(field) = field else {
            return;
        };
        let visible_bottom = window.viewport_size().height - keyboard - px(REVEAL_MARGIN);
        let covered = field.bottom() - visible_bottom;
        if covered <= px(0.0) {
            self.reveal_pending = false;
            return;
        }
        let offset = self.scroll.offset();
        // The page's padding already grew by the keyboard (`bottom`), so the
        // range is there; the offset is negative as the page scrolls down.
        let y = (offset.y - covered).max(-self.scroll.max_offset().y);
        if y < offset.y {
            self.scroll.set_offset(gpui::point(offset.x, y));
            window.request_animation_frame();
        }
    }

    /// Navigation and a new action clear the notice, except the Undo of a
    /// delete that is still waiting.
    pub(crate) fn clear_notice(&mut self) {
        if keeps_undo(
            self.notice_action,
            self.pending_delete.is_some(),
            self.merge_undo.is_some(),
        ) {
            return;
        }
        self.notice = None;
        self.notice_action = None;
        self.merge_undo = None;
    }

    /// Starts the timer that takes the notice away, once per new text. A
    /// notice ending in "…" says work is under way and stays until replaced.
    fn keep_notice_timer(&mut self, cx: &mut Context<Self>) {
        // The button belongs to the Undo notice only: any other text drops it.
        let owns_button = match self.notice_action {
            Some(NoticeAction::UndoDelete) => self.pending_delete.as_ref().is_some_and(|p| {
                self.notice.as_deref()
                    == Some(rovsti_core::contact_select::deleted_notice(p.ids.len()).as_str())
            }),
            Some(NoticeAction::UndoMerge) => self
                .merge_undo
                .as_ref()
                .is_some_and(|undo| Some(&undo.notice) == self.notice.as_ref()),
            None => false,
        };
        if !owns_button {
            self.notice_action = None;
        }
        let Some(text) = self.notice.clone() else {
            self.notice_timer = None;
            return;
        };
        let life = if self.notice_action.is_some() {
            UNDO_LIFE
        } else {
            NOTICE_LIFE
        };
        if text.ends_with('…') {
            self.notice_timer = None;
            return;
        }
        if self
            .notice_timer
            .as_ref()
            .is_some_and(|(timed, _)| *timed == text)
        {
            return;
        }
        let timed = text.clone();
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(life).await;
            this.update(cx, |this, cx| {
                if this.notice.as_ref() == Some(&timed) {
                    this.notice = None;
                    this.notice_action = None;
                    this.merge_undo = None;
                    cx.notify();
                }
            })
            .ok();
        });
        self.notice_timer = Some((text, task));
    }

    fn notice(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let colors = theme::colors(theme);
        let text = self.notice.clone()?;
        let undo = self.notice_action.map(|action| {
            crate::ui::pill(theme, "notice-undo", "Undo", false).on_click(cx.listener(
                move |this, _, _, cx| match action {
                    NoticeAction::UndoDelete => this.undo_delete(cx),
                    NoticeAction::UndoMerge => this.undo_merge(cx),
                },
            ))
        });
        Some(
            div()
                .absolute()
                .left_0()
                .right_0()
                .bottom(
                    content_bottom_inset()
                        + px(8.0)
                        // Above the viewer's bottom bar.
                        + px(if self.viewer.is_some() || self.crop.is_some() {
                            88.0
                        } else {
                            0.0
                        })
                        // Above the select bar.
                        + px(if self.select.is_some() { 76.0 } else { 0.0 }),
                )
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
                        .flex()
                        .items_center()
                        .gap(px(12.0))
                        .child(text)
                        .children(undo),
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
        self.request_wa_card(cx);
        let fading = self.photos.fading() || self.avatars.read(cx).fading();
        self.start_viewer_close(window);
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame_timed(window, |dt, real| {
            self.viewer.as_mut().is_some_and(|v| v.tick(dt))
                | self.tabs.tick(dt)
                | self.page.tick(dt)
                | fading
                | self.photo_sheet.tick(dt)
                | self.photo_sheet.tick_real(real)
                | self.field_sheet.tick(dt)
                | self.field_sheet.tick_real(real)
                | self.share_sheet.tick(dt)
                | self.share_sheet.tick_real(real)
                | self.qr_sheet.tick(dt)
                | self.qr_sheet.tick_real(real)
                | self.select_ui.sheet.tick(dt)
                | self.select_ui.sheet.tick_real(real)
                | self.dupes.sheet.tick(dt)
                | self.dupes.sheet.tick_real(real)
        });
        self.motion = motion;
        self.drop_finished_viewer();
        let entity = cx.entity();
        let touch_entity = entity.clone();
        let editing = self.editor.is_some();
        let picking = self.picker.is_some();

        // The keyboard covers the bottom of the screen: leave room to scroll.
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let bottom = content_bottom_inset() + keyboard;
        let focused_field = ui::take_focused_field_bounds();
        if editing {
            self.reveal_focused_field(focused_field, keyboard, window, cx);
        }
        self.keep_notice_timer(cx);

        // Lists that grow with the address book are virtualised and scroll
        // themselves; the other pages scroll as one block.
        let mut scrubber = false;
        let (page, listed) = if picking {
            let page = Self::list_page(&self.picker_list, bottom, Self::picker_item, cx);
            (page, true)
        } else if editing {
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
        } else if self.upcoming_open && tab == FAVORITES_TAB {
            (self.upcoming_page(theme, cx), false)
        } else if self.recent_open && tab == CONTACTS_TAB {
            (self.recent_page(theme, cx), false)
        } else if self.dupes.open && tab == SETTINGS_TAB {
            (self.dupes_page(theme, cx), false)
        } else if self.whatsapp_open && tab == SETTINGS_TAB {
            (self.whatsapp_page(theme, cx), false)
        } else {
            match tab {
                CONTACTS_TAB => {
                    let query = self.field.read(cx).text().to_owned();
                    scrubber = matches!(self.book, Book::Ready(_))
                        && !self.scrub_letters.is_empty()
                        && contacts_view::show_scrubber(self.rows.len(), &query);
                    (
                        Self::list_page(&self.contacts_list, bottom, Self::contacts_item, cx),
                        true,
                    )
                }
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
            .children(if self.viewer.is_none() && self.crop.is_none() {
                self.notice(theme, cx)
            } else {
                None
            })
            .children(self.select_bar_view(theme, cx))
            .children(self.select.is_some().then(|| {
                let entity = cx.entity();
                canvas(
                    |_, _, _| (),
                    move |_, (), window, _| {
                        let entity = entity.clone();
                        window.on_key_event(move |event: &KeyDownEvent, phase, _, cx| {
                            if phase.bubble() && event.keystroke.key == "escape" {
                                entity.update(cx, |this, cx| this.go_back(cx));
                            }
                        });
                    },
                )
                .absolute()
                .size_full()
            }))
            // No tab bar while a form is open or ticking: no stray tap loses the edits.
            .children((!editing && !picking && self.select.is_none()).then(|| {
                self.tabs.render(
                    theme,
                    move |index, _, cx| {
                        entity.update(cx, |this, cx| {
                            this.tabs.press();
                            let changed = this.tabs.select(index);
                            if changed {
                                this.select = None;
                            }
                            if changed
                                || this.open.is_some()
                                || this.group_page.is_some()
                                || this.group_edit.is_some()
                                || this.whatsapp_open
                                || this.dupes.open
                                || this.upcoming_open
                                || this.recent_open
                            {
                                this.upcoming_open = false;
                                this.recent_open = false;
                                this.whatsapp_open = false;
                                this.dupes.open = false;
                                this.dupes.sheet.close();
                                this.open = None;
                                this.open_draft = None;
                                this.group_page = None;
                                this.group_edit = None;
                                this.clear_notice();
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
            .children(
                scrubber
                    .then(|| {
                        let top = SCRUBBER_TOP;
                        let height = f32::from(window.viewport_size().height)
                            - top
                            - f32::from(bottom)
                            - SCRUBBER_BOTTOM_GAP;
                        self.scrubber_view(theme, top, height.max(120.0), cx)
                    })
                    .into_iter()
                    .flatten(),
            )
            .children(self.photo_viewer_overlay(theme, window, cx))
            .children(self.photo_crop_overlay(theme, window, cx))
            // The pill sits above the viewer, which hides the one under the page.
            .children(if self.viewer.is_some() || self.crop.is_some() {
                self.notice(theme, cx)
            } else {
                None
            })
            .children(self.confirm_view(theme, cx))
            .children(self.photo_sheet_view(theme, window, cx))
            .children(self.field_sheet_view(theme, window, cx))
            .children(self.share_sheet_view(theme, window, cx))
            .children(self.qr_sheet_view(theme, window, cx))
            .children(self.dupes_sheet_view(theme, window, cx))
            .children(self.select_sheet_view(theme, window, cx))
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
