//! Rovsti Contacts: the phone's address book in liquid glass.
//!
//! Contacts are read from Android's `ContactsContract` through
//! `rovsti_android::providers`; search, sections and the detail card come from
//! `rovsti_core`. Desktop runs show a sample address book. Nothing leaves the
//! device.

mod field;
mod sample;

use std::sync::Arc;

use gpui::{
    AnyElement, App, Bounds, Context, Entity, IntoElement, MouseButton, ParentElement, Render,
    SharedString, StatefulInteractiveElement, Styled, Window, WindowBounds, WindowOptions, div,
    prelude::*, px, size, svg,
};
use rovsti_android::permissions::{self, Group};
use rovsti_core::{
    contact_card::{self, ContactCard},
    contacts_view::{self, ContactVm, NameStyle},
    search::ContactIndex,
    settings::SortBy,
    wire::{ContactsPayload, Envelope, NameFormat, WireContact, WireError},
};
use rovsti_ui::{
    Icon, IconAssets,
    components::{
        avatar, empty_state, glass_icon_button, glass_pill, large_title, list_row, section,
    },
    current_theme,
    glass::{GlassSpec, glass},
    tab_bar::{Tab, TabBar, content_bottom_inset},
    theme::{self, hue_pair},
    tokens::{Theme, metrics, shadow, typography},
};

use crate::field::{SearchField, input_layer, on_backspace};

const TABS: [&str; 3] = ["Contacts", "Favorites", "Settings"];
const TAB_ICONS: [Icon; 3] = [Icon::LucideUsers, Icon::LucideStar, Icon::LucideSettings];
const FIELD_H: f32 = 44.0;

/// The address book as last pulled.
enum Book {
    Loading,
    Ready(Arc<ContactIndex>),
    /// `READ_CONTACTS` is not granted.
    Denied,
    Unavailable,
}

/// Pulls and indexes the contacts. Blocks on JNI: run it off the UI thread.
fn pull() -> Book {
    match rovsti_android::providers::contacts() {
        Ok(json) => match serde_json::from_str::<Envelope<ContactsPayload>>(&json)
            .map(Envelope::into_result)
        {
            Ok(Ok(payload)) => Book::Ready(Arc::new(ContactIndex::new(payload.contacts))),
            Ok(Err(WireError::Permission)) => Book::Denied,
            Ok(Err(error)) => {
                log::warn!("contacts pull refused: {error}");
                Book::Unavailable
            }
            Err(error) => {
                log::warn!("bad contacts payload: {error}");
                Book::Unavailable
            }
        },
        Err(rovsti_android::Error::Unsupported) => {
            Book::Ready(Arc::new(ContactIndex::new(sample::contacts())))
        }
        Err(error) => {
            log::warn!("contacts pull failed: {error}");
            Book::Unavailable
        }
    }
}

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
    field: Entity<SearchField>,
    seen_edits: u64,
    book: Book,
    pulls: u64,
    style: NameStyle,
    rows: Vec<Row>,
    favorites: Vec<Row>,
    open: Option<ContactCard>,
    notice: Option<SharedString>,
}

impl ContactsApp {
    fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let field = cx.new(SearchField::new);
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
        let mut app = Self {
            tabs: TabBar::new(
                TABS.into_iter()
                    .zip(TAB_ICONS)
                    .map(|(label, icon)| Tab::new(icon, label)),
            ),
            motion: Default::default(),
            page: Default::default(),
            field,
            seen_edits: 0,
            book: Book::Loading,
            pulls: 0,
            style: NameStyle::default(),
            rows: Vec::new(),
            favorites: Vec::new(),
            open: None,
            notice: None,
        };
        app.reload(cx);
        app
    }

    /// Pulls the contacts on the background executor; only the newest pull lands.
    fn reload(&mut self, cx: &mut Context<Self>) {
        self.pulls += 1;
        let pull_id = self.pulls;
        cx.spawn(async move |this, cx| {
            let book = cx.background_executor().spawn(async { pull() }).await;
            this.update(cx, |this, cx| {
                if this.pulls == pull_id {
                    this.book = book;
                    this.rebuild(cx);
                }
            })
            .ok();
        })
        .detach();
    }

    fn index(&self) -> Option<&Arc<ContactIndex>> {
        match &self.book {
            Book::Ready(index) => Some(index),
            _ => None,
        }
    }

    /// Rebuilds the list rows, favorites and the open card from the book,
    /// the query and the name style.
    fn rebuild(&mut self, cx: &mut Context<Self>) {
        let query = self.field.read(cx).text().to_owned();
        let Some(index) = self.index().cloned() else {
            self.rows.clear();
            self.favorites.clear();
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
        cx.notify();
    }

    fn open_contact(&mut self, id: i64, cx: &mut Context<Self>) {
        let format = self.style.format;
        self.open = self
            .index()
            .and_then(|index| index.all().find(|c| c.id == id))
            .map(|c| ContactCard::new(c, format));
        self.notice = None;
        self.page.restart();
        cx.notify();
    }

    fn close_contact(&mut self, cx: &mut Context<Self>) {
        self.open = None;
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
        self.book = Book::Ready(Arc::new(ContactIndex::new(all)));
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

    fn set_style(&mut self, style: NameStyle, cx: &mut Context<Self>) {
        if self.style != style {
            self.style = style;
            self.rebuild(cx);
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
        list_row(
            theme,
            Some(avatar(theme, row.monogram.clone(), 40.0, tint).into_any_element()),
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
                                    || permissions::request(Group::Contacts),
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
            .child(
                div()
                    .px(px(32.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(
                        "Contacts are read from this phone's address book. \
                         Nothing leaves the device.",
                    ),
            )
            .into_any_element()
    }

    /// A round glass action with a caption (Call, Message, Share).
    fn action_button(
        theme: Theme,
        key: &'static str,
        icon: Icon,
        label: &'static str,
        enabled: bool,
    ) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(key)
            .flex()
            .flex_col()
            .items_center()
            .gap(px(6.0))
            .opacity(if enabled { 1.0 } else { 0.4 })
            .child(
                glass(GlassSpec::control(theme))
                    .size(px(56.0))
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(icon.path())
                            .size(px(22.0))
                            .text_color(colors.accent),
                    ),
            )
            .child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.accent)
                    .child(label),
            )
    }

    fn detail(&self, card: &ContactCard, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let id = card.id;
        let primary = card.primary().map(|n| n.raw.clone());
        let tint = hue_pair(theme, card.hue).0;
        let monogram: SharedString = card.monogram.map(String::from).unwrap_or_default().into();

        let top = div()
            .flex()
            .items_center()
            .justify_between()
            .px(px(8.0))
            .pt(px(48.0))
            .child(
                glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                    .id("back")
                    .on_click(cx.listener(|this, _, _, cx| this.close_contact(cx))),
            )
            .child(
                // The accent dot marks a favorite.
                glass_icon_button(theme, Icon::LucideStar, 0.0, card.starred)
                    .id("star")
                    .on_click(cx.listener(move |this, _, _, cx| this.toggle_star(id, cx))),
            );

        let head = div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(12.0))
            .pt(px(8.0))
            .pb(px(20.0))
            .child(avatar(theme, monogram, 96.0, tint))
            .child(
                div()
                    .px(px(24.0))
                    .text_center()
                    .text_size(typography::TITLE.size)
                    .line_height(typography::TITLE.line)
                    .font_weight(typography::TITLE.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(card.name.clone())),
            );

        let has_number = primary.is_some();
        let call_number = primary.clone();
        let sms_number = primary;
        let share = card.share_text();
        let actions =
            div()
                .flex()
                .justify_center()
                .gap(px(28.0))
                .pb(px(24.0))
                .child(
                    Self::action_button(theme, "call", Icon::LucidePhone, "Call", has_number)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            if let Some(number) = call_number.clone() {
                                this.act(
                                    "call",
                                    move || rovsti_android::telecom::place_call(&number),
                                    cx,
                                );
                            }
                        })),
                )
                .child(
                    Self::action_button(
                        theme,
                        "message",
                        Icon::LucideMessageCircle,
                        "Message",
                        has_number,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if let Some(number) = sms_number.clone() {
                            this.act(
                                "send a message",
                                move || rovsti_android::telecom::send_sms_to(&number),
                                cx,
                            );
                        }
                    })),
                )
                .child(
                    Self::action_button(theme, "share", Icon::LucideUpload, "Share", true)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            let text = share.clone();
                            this.act(
                                "share",
                                move || rovsti_android::telecom::share_text(&text),
                                cx,
                            );
                        })),
                );

        let numbers: Vec<AnyElement> = card
            .numbers
            .iter()
            .enumerate()
            .map(|(i, number)| {
                let raw = number.raw.clone();
                let label: SharedString = if number.default {
                    format!("{} · default", number.label).into()
                } else {
                    number.label.clone().into()
                };
                div()
                    .id(("number", i))
                    .flex()
                    .flex_col()
                    .justify_center()
                    .min_h(metrics::ROW_HEIGHT_SUBTITLE)
                    .px(metrics::ROW_INSET)
                    .border_b(metrics::SEPARATOR)
                    .border_color(colors.separator)
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(label),
                    )
                    .child(
                        div()
                            .text_size(typography::BODY.size)
                            .text_color(colors.accent)
                            .child(SharedString::from(number.display.clone())),
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let raw = raw.clone();
                        this.act(
                            "call",
                            move || rovsti_android::telecom::place_call(&raw),
                            cx,
                        );
                    }))
                    .into_any_element()
            })
            .collect();

        let edit = div()
            .id("edit")
            .flex()
            .items_center()
            .gap(px(10.0))
            .h(metrics::MIN_HIT)
            .px(metrics::ROW_INSET)
            .text_size(typography::BODY.size)
            .text_color(colors.accent)
            .child(
                svg()
                    .path(Icon::LucidePencil.path())
                    .size(px(18.0))
                    .text_color(colors.accent),
            )
            .child("Edit Contact")
            .on_click(cx.listener(move |this, _, _, cx| {
                this.act(
                    "edit this contact",
                    move || rovsti_android::telecom::edit_contact(id),
                    cx,
                );
            }))
            .into_any_element();

        div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(top)
            .child(head)
            .child(actions)
            .children((!numbers.is_empty()).then(|| section(theme, Some("Phone".into()), numbers)))
            .child(section(theme, None, [edit]))
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
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| self.tabs.tick(dt) | self.page.tick(dt));
        self.motion = motion;
        let tab = self.tabs.selected();
        let entity = cx.entity();
        let touch_entity = entity.clone();

        let page = if let Some(card) = self.open.clone() {
            self.detail(&card, theme, cx)
        } else {
            let body = match tab {
                0 => self.contacts_tab(theme, window, cx),
                1 => self.favorites_tab(theme, cx),
                _ => self.settings_tab(theme, cx),
            };
            div()
                .child(large_title(theme, TABS[tab], None))
                .child(body)
                .into_any_element()
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
            .child(
                div()
                    .id("page")
                    .size_full()
                    .overflow_y_scroll()
                    .pb(content_bottom_inset())
                    .opacity(self.page.progress())
                    .child(div().relative().top(px(self.page.offset(24.0))).child(page)),
            )
            .children(self.notice(theme))
            .child(self.tabs.render(
                theme,
                move |index, _, cx| {
                    entity.update(cx, |this, cx| {
                        this.tabs.press();
                        let changed = this.tabs.select(index);
                        if changed || this.open.is_some() {
                            this.open = None;
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
            ))
    }
}

#[gpui_platform::main]
fn main() {
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
