//! Settings > Call notes (U30, U33): every call note in one searchable list.
//!
//! The rules (search, the row texts, the share text) live in
//! `rovsti_core::call_notes`; this only shows them. A row opens the Call
//! details sheet of its call, the trash glyph deletes the note (with Undo)
//! and "Share all" sends every note as text through the share sheet.

use std::{ops::Range, sync::Arc};

use gpui::{
    AnyElement, Context, Entity, HapticFeedback, Hsla, InteractiveElement, IntoElement,
    MouseButton, ParentElement, Role, SharedString, StatefulInteractiveElement, Styled,
    UniformListScrollHandle, Window, div, prelude::*, px, svg, uniform_list,
};
use rovsti_core::{call_notes, recents::LocaleTime};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    theme::{self, call_colors},
    tokens::{Theme, metrics, shadow, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp, SheetKind,
    backend::{self, Load},
    block_ui::PillAction,
    field::{SearchField, input_layer, on_backspace},
    recents::ROW_H,
};

/// The search field of the page.
pub(crate) const FIELD_ID: &str = "set-nt-field";
/// The "Share all" pill of the page.
pub(crate) const SHARE_ID: &str = "set-nt-share";
/// A row opens its call (`{ROW_PREFIX}{i}`), the glyph deletes its note.
pub(crate) const ROW_PREFIX: &str = "set-nt-row-";
pub(crate) const DEL_PREFIX: &str = "set-nt-del-";
/// Placeholder of the search field.
pub(crate) const HINT: &str = "Search notes";
const FIELD_H: f32 = 44.0;
const HEADER_H: f32 = 44.0;

/// One row of the list, built when the notes or the query change.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct NoteVm {
    title: SharedString,
    subtitle: SharedString,
    number: String,
    at_ms: i64,
    aria: SharedString,
}

/// The state of the page.
pub(crate) struct NotesUi {
    pub(crate) field: Entity<SearchField>,
    seen_edits: u64,
    typing: bool,
    rows: Arc<[NoteVm]>,
    /// Notes in total (the search may show fewer).
    total: usize,
    /// The notes revision and field edit the rows were built from.
    built: Option<(u64, u64)>,
    scroll: UniformListScrollHandle,
}

impl NotesUi {
    pub(crate) fn new(cx: &mut Context<PhoneApp>) -> Self {
        Self {
            field: cx.new(SearchField::new),
            seen_edits: 0,
            typing: false,
            rows: Arc::from(Vec::new()),
            total: 0,
            built: None,
            scroll: UniformListScrollHandle::new(),
        }
    }
}

/// The index a row or delete id `{prefix}{i}` stands for.
pub(crate) fn id_index(id: &str, prefix: &str) -> Option<usize> {
    id.strip_prefix(prefix)?.parse().ok()
}

/// What the list shows instead of rows.
pub(crate) fn empty_text(total: usize) -> &'static str {
    if total == 0 {
        call_notes::EMPTY_TEXT
    } else {
        call_notes::NO_MATCH_TEXT
    }
}

impl PhoneApp {
    pub(crate) fn notes_opened(&mut self, cx: &mut Context<Self>) {
        self.notes_ui.built = None;
        self.notes_page_sync(cx);
    }

    pub(crate) fn notes_closed(&mut self, cx: &mut Context<Self>) {
        self.notes_ui.field.update(cx, |f, cx| f.clear(cx));
        self.notes_ui.seen_edits = 0;
        self.notes_ui.typing = false;
        self.notes_ui.built = None;
    }

    /// The search field's text or focus changed.
    pub(crate) fn notes_field_changed(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let (edits, focused) = {
            let field = self.notes_ui.field.read(cx);
            (field.edits, field.focus_handle().is_focused(window))
        };
        let mut changed = focused != self.notes_ui.typing;
        self.notes_ui.typing = focused;
        if edits != self.notes_ui.seen_edits {
            self.notes_ui.seen_edits = edits;
            self.notes_page_sync(cx);
            changed = true;
        }
        if changed {
            cx.notify();
        }
    }

    /// Contact name for a number, built from the loaded contacts.
    fn notes_name_of(&self, cx: &gpui::App) -> impl Fn(&str) -> Option<String> + use<> {
        let backend = self.backend.read(cx);
        let contacts = backend.contacts();
        let format = backend.settings().name_format;
        move |raw| match &contacts {
            Load::Ready(index) => index
                .match_number(raw)
                .map(|c| rovsti_core::names::display(c, format).into_owned()),
            _ => None,
        }
    }

    fn notes_locale(&self, cx: &gpui::App) -> LocaleTime {
        match self.backend.read(cx).call_log() {
            Load::Ready(log) => log.locale,
            _ => LocaleTime::default(),
        }
    }

    /// Rebuilds the rows when the notes or the query moved. Called from the
    /// notes' change path (`save_notes`, loading) and by the field.
    pub(crate) fn notes_page_sync(&mut self, cx: &mut Context<Self>) {
        if self.settings.sub != Some(crate::settings_ui::Sub::Notes) {
            return;
        }
        let (query, edits) = {
            let field = self.notes_ui.field.read(cx);
            (field.text().to_owned(), field.edits)
        };
        let stamp = (self.notes.rev(), edits);
        if self.notes_ui.built == Some(stamp) {
            return;
        }
        let name_of = self.notes_name_of(cx);
        let locale = self.notes_locale(cx);
        let snapshot = self.notes.snapshot();
        let rows = snapshot
            .rows(&query, &name_of)
            .into_iter()
            .map(|row| {
                let title = call_notes::row_title(name_of(&row.number).as_deref(), &row.number);
                let when = call_notes::when_line(row.at_ms, locale);
                let subtitle = call_notes::row_subtitle(&when, &row.preview);
                NoteVm {
                    aria: format!("{title}, {subtitle}").into(),
                    title: title.into(),
                    subtitle: subtitle.into(),
                    number: row.number,
                    at_ms: row.at_ms,
                }
            })
            .collect();
        self.notes_ui.rows = rows;
        self.notes_ui.total = snapshot.notes.len();
        self.notes_ui.built = Some(stamp);
        cx.notify();
    }

    pub(crate) fn notes_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        if id == SHARE_ID {
            self.notes_share_all(window, cx);
        } else if let Some(i) = id_index(id, ROW_PREFIX) {
            self.notes_open_row(i, window, cx);
        } else if let Some(i) = id_index(id, DEL_PREFIX) {
            self.notes_delete_row(i, window, cx);
        }
        cx.notify();
    }

    /// Every note as text through the share sheet.
    fn notes_share_all(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let name_of = self.notes_name_of(cx);
        let tz = self.notes_locale(cx).tz_offset_s;
        let text = self.notes.snapshot().all_text(name_of, tz);
        if text.is_empty() {
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        let outcome = backend::share_text(&text);
        if outcome != backend::Outcome::Done {
            self.show_notice_pill(
                outcome.why_not("Couldn't open the share sheet"),
                None,
                false,
                cx,
            );
        }
    }

    /// Opens the Call details sheet of the row's call.
    fn notes_open_row(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.notes_ui.rows.get(index).cloned() else {
            return;
        };
        if self.sheet.is_visible() {
            return;
        }
        let vm = self.logged_call_near(&row.number, row.at_ms, cx);
        let Some(vm) = vm else {
            self.show_notice_pill("This call is no longer in the call log", None, false, cx);
            return;
        };
        let name_of = self.notes_name_of(cx);
        let who = name_of(&row.number).map(SharedString::from);
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.open_details_for(&vm, who, SheetKind::CallDetails, true, window, cx);
        self.sheet.open();
    }

    /// Deletes the row's note; Undo puts it back.
    fn notes_delete_row(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(row) = self.notes_ui.rows.get(index).cloned() else {
            return;
        };
        let Some(note) = self.notes.remove(&row.number, row.at_ms) else {
            return;
        };
        self.save_notes(cx);
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        self.show_pill_action(
            "Note deleted",
            "Undo",
            PillAction::RestoreNote(Box::new(note)),
            cx,
        );
    }

    // ---- drawing ------------------------------------------------------------

    pub(crate) fn notes_body(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let count = self.notes_ui.rows.len();
        let list = if count == 0 {
            div()
                .id("set-nt-empty")
                .role(Role::Alert)
                .aria_label(empty_text(self.notes_ui.total))
                .size_full()
                .px(px(32.0))
                .pt(px(48.0))
                .flex()
                .justify_center()
                .text_size(typography::BODY.size)
                .text_color(colors.secondary_label)
                .child(empty_text(self.notes_ui.total))
                .into_any_element()
        } else {
            uniform_list(
                "nt-rows",
                count,
                cx.processor(move |this, range: Range<usize>, window, cx| {
                    let theme = rovsti_ui::current_theme(window);
                    this.notes_rows(theme, range, cx)
                }),
            )
            .track_scroll(&self.notes_ui.scroll)
            .size_full()
            .into_any_element()
        };
        div()
            .flex_1()
            .min_h_0()
            .flex()
            .flex_col()
            .pt(px(4.0))
            .child(
                div()
                    .h(px(FIELD_H + 12.0))
                    .flex_none()
                    .pt(px(12.0))
                    .child(self.notes_field(theme, cx)),
            )
            .children((self.notes_ui.total > 0).then(|| self.notes_header(theme, cx)))
            .child(div().relative().flex_1().min_h_0().w_full().child(list))
            .into_any_element()
    }

    /// The "Share all" pill under the field.
    fn notes_header(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(SHARE_ID).clamp(0.0, 1.0);
        div()
            .flex_none()
            .h(px(HEADER_H))
            .mx(px(16.0))
            .pt(px(8.0))
            .pb(px(0.0))
            .child(
                div()
                    .id(SHARE_ID)
                    .role(Role::Button)
                    .aria_label("Share all notes")
                    .relative()
                    .w_full()
                    .h(px(36.0))
                    .rounded_full()
                    .bg(Hsla {
                        a: 0.7 + 0.2 * press,
                        ..colors.fill
                    })
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .child("Share all")
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, window, cx| {
                            this.presses.down(SHARE_ID);
                            this.settings_tap(SHARE_ID, window, cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some(SHARE_ID.into()),
                    ))),
            )
            .into_any_element()
    }

    /// The search field in the glass field shape.
    fn notes_field(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let field = self.notes_ui.field.clone();
        let focus = field.read(cx).focus_handle();
        let text: SharedString = field.read(cx).text().to_owned().into();
        let typing = self.notes_ui.typing;
        let focus_for_tap = focus.clone();
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
            .child(if text.is_empty() && !typing {
                div()
                    .text_color(colors.tertiary_label)
                    .child(HINT)
                    .into_any_element()
            } else {
                div()
                    .truncate()
                    .text_color(colors.label)
                    .child(text)
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
            .id(FIELD_ID)
            .role(Role::TextInput)
            .aria_label(HINT)
            .relative()
            .flex_none()
            .h(px(FIELD_H))
            .mx(px(16.0))
            .track_focus(&focus)
            .on_key_down(on_backspace(field))
            .on_mouse_down(MouseButton::Left, {
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(FIELD_ID);
                    window.focus(&focus_for_tap, cx);
                    window.show_soft_keyboard();
                    this.notes_field_changed(window, cx);
                })
            })
            .child(touch_surface(Self::touch_listener(
                cx,
                Some(FIELD_ID.into()),
            )))
            .child(
                glass(GlassSpec::control(theme))
                    .size_full()
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .pl(px(14.0))
                    .pr(px(14.0))
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
                    .child(text_box),
            )
            .into_any_element()
    }

    fn notes_rows(
        &self,
        theme: Theme,
        range: Range<usize>,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let colors = theme::colors(theme);
        range
            .map(|i| {
                let Some(row) = self.notes_ui.rows.get(i) else {
                    return div().h(px(ROW_H)).into_any_element();
                };
                let row_id = SharedString::from(format!("{ROW_PREFIX}{i}"));
                let del_id = SharedString::from(format!("{DEL_PREFIX}{i}"));
                let row_press = self.presses.value(&row_id).clamp(0.0, 1.0);
                let del_press = self.presses.value(&del_id).clamp(0.0, 1.0);
                div()
                    .relative()
                    .h(px(ROW_H))
                    .w_full()
                    .flex()
                    .items_center()
                    .child(
                        div()
                            .id(row_id.clone())
                            .role(Role::Button)
                            .aria_label(row.aria.clone())
                            .relative()
                            .flex_1()
                            .min_w_0()
                            .h_full()
                            .pl(metrics::ROW_INSET)
                            .pr(px(8.0))
                            .flex()
                            .flex_col()
                            .justify_center()
                            .bg(Hsla {
                                a: 0.07 * row_press,
                                ..colors.label
                            })
                            .child(
                                div()
                                    .truncate()
                                    .text_size(typography::HEADLINE.size)
                                    .font_weight(typography::HEADLINE.weight)
                                    .text_color(colors.label)
                                    .child(row.title.clone()),
                            )
                            .child(
                                div()
                                    .truncate()
                                    .text_size(typography::SUBHEADLINE.size)
                                    .text_color(colors.secondary_label)
                                    .child(row.subtitle.clone()),
                            )
                            .on_mouse_down(MouseButton::Left, {
                                let id = row_id.clone();
                                cx.listener(move |this, _, window, cx| {
                                    this.presses.down(id.clone());
                                    this.settings_tap(&id, window, cx);
                                })
                            })
                            .child(touch_surface(Self::touch_listener(cx, Some(row_id)))),
                    )
                    .child(
                        div()
                            .id(del_id.clone())
                            .role(Role::Button)
                            .aria_label(SharedString::from(format!("Delete note, {}", row.title)))
                            .relative()
                            .flex_none()
                            .size(px(44.0))
                            .mr(px(8.0))
                            .rounded_full()
                            .bg(Hsla {
                                a: 0.12 * del_press,
                                ..colors.label
                            })
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(
                                svg()
                                    .path(Icon::LucideTrash2.path())
                                    .size(px(20.0))
                                    .text_color(call_colors(theme).danger),
                            )
                            .on_mouse_down(MouseButton::Left, {
                                let id = del_id.clone();
                                cx.listener(move |this, _, window, cx| {
                                    this.presses.down(id.clone());
                                    this.settings_tap(&id, window, cx);
                                })
                            })
                            .child(touch_surface(Self::touch_listener(cx, Some(del_id)))),
                    )
                    .child(
                        div()
                            .absolute()
                            .bottom_0()
                            .left(metrics::ROW_INSET)
                            .right_0()
                            .h(px(0.5))
                            .bg(colors.separator),
                    )
                    .into_any_element()
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::settings_ui::Sub;

    #[test]
    fn ids_stay_on_the_notes_page_and_apart() {
        for id in [FIELD_ID, SHARE_ID, ROW_PREFIX, DEL_PREFIX] {
            assert!(id.starts_with(Sub::Notes.prefix()), "{id}");
        }
        assert!(!SHARE_ID.starts_with(ROW_PREFIX) && !SHARE_ID.starts_with(DEL_PREFIX));
        assert!(!ROW_PREFIX.starts_with(DEL_PREFIX) && !DEL_PREFIX.starts_with(ROW_PREFIX));
        assert_eq!(ROW_PREFIX, "set-nt-row-");
        assert_eq!(DEL_PREFIX, "set-nt-del-");
    }

    #[test]
    fn indexes_come_back_from_their_ids() {
        assert_eq!(id_index("set-nt-row-3", ROW_PREFIX), Some(3));
        assert_eq!(id_index("set-nt-del-12", DEL_PREFIX), Some(12));
        assert_eq!(id_index("set-nt-del-12", ROW_PREFIX), None);
        assert_eq!(id_index("set-nt-row-x", ROW_PREFIX), None);
        assert_eq!(id_index(SHARE_ID, ROW_PREFIX), None);
    }

    #[test]
    fn the_empty_texts() {
        assert_eq!(
            empty_text(0),
            "No call notes yet. Add one from a call's details."
        );
        assert_eq!(empty_text(3), "No notes match your search");
        assert_eq!(HINT, "Search notes");
    }
}
