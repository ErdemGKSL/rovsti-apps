//! A text field for forms: the text, the focus handle and the platform
//! input-method plumbing (`EntityInputHandler`).
//!
//! The text, selection and composing range live in
//! [`rovsti_core::text_edit::EditBuffer`]; this file only maps the keyboard's
//! calls (UTF-16, as `gpui_android` sends them) and key events onto it. The
//! keyboard layout ([`TextInputPurpose`]) and the action key
//! ([`TextInputAction`]) are set per field. [`field_box`] draws the glass box.

use std::{
    cell::{Cell, RefCell},
    ops::Range,
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};

use gpui::{
    App, Bounds, ClipboardItem, Context, Entity, EntityInputHandler, FocusHandle, Focusable, Font,
    Hsla, IntoElement, KeyDownEvent, Pixels, Point, PreeditSelection, ShapedLine, Styled,
    SurroundingText, TextAlign, TextInputAction, TextInputMode, TextInputPurpose, TextRun,
    UTF16Selection, UnderlineStyle, Window, canvas, div, fill, point, px, size,
};
use liquid_glass_kit::tokens::{Theme, typography};
use rovsti_core::text_edit::EditBuffer;
use rovsti_core::text_view::{
    CARET_MARGIN, caret_visible, clamp_scroll, masked_index, next_toggle_ms, scroll_for_caret,
    unmasked_index,
};

/// Whether carets blink. The screenshot rig turns it off so frames are stable.
static BLINK: AtomicBool = AtomicBool::new(true);

/// Turns caret blinking on or off for every field (on by default).
pub fn set_blink(on: bool) {
    BLINK.store(on, Ordering::Relaxed);
}

/// Caret width, dp.
const CARET_W: f32 = 2.0;
/// Selection corner radius, dp.
const SELECTION_RADIUS: f32 = 3.0;
/// Composing-text underline thickness, dp.
const MARKED_UNDERLINE: f32 = 1.5;

/// The text as last shaped and painted: what hit-testing and the keyboard's
/// geometry queries read.
struct Laid {
    line: ShapedLine,
    /// The content box, window coordinates.
    bounds: Bounds<Pixels>,
    /// The text is drawn masked (a passphrase).
    masked: bool,
}

pub struct TextField {
    focus: FocusHandle,
    purpose: TextInputPurpose,
    action: TextInputAction,
    buf: EditBuffer,
    /// A passphrase: the keyboard gets `TextInputMode::Password`.
    secret: Cell<bool>,
    /// Bumped on every edit; the page compares it instead of the text.
    pub edits: u64,
    /// Bumped each time the keyboard's action key (Send, Go, ...) is pressed.
    actions: u64,
    /// The action key keeps the keyboard up (a composer that sends on it).
    keep_focus_on_action: bool,
    /// Where the field was last laid out (window coordinates).
    viewport: Cell<Option<Bounds<Pixels>>>,
    /// Horizontal scroll of the text inside the content box, dp.
    scroll: Cell<f32>,
    /// The last painted line; `None` while unfocused.
    laid: RefCell<Option<Laid>>,
    /// Bumped on every edit or caret move; paint scrolls to the caret once per bump.
    activity: Cell<u64>,
    seen_activity: Cell<u64>,
    /// When the last edit or move happened: the caret stays solid after it.
    last_activity: Cell<Instant>,
    /// The field was focused when it last painted (the blink timer's cue).
    painted_focused: Cell<bool>,
    blinking: Cell<bool>,
}

impl TextField {
    pub fn new(cx: &mut Context<Self>) -> Self {
        Self::with_input(TextInputPurpose::Text, TextInputAction::Done, cx)
    }

    /// A field that asks the keyboard for `purpose` (URL, phone, ...) and
    /// shows `action` on its enter key.
    pub fn with_input(
        purpose: TextInputPurpose,
        action: TextInputAction,
        cx: &mut Context<Self>,
    ) -> Self {
        Self {
            focus: cx.focus_handle(),
            purpose,
            action,
            buf: EditBuffer::new(false),
            secret: Cell::new(false),
            edits: 0,
            actions: 0,
            keep_focus_on_action: false,
            viewport: Cell::new(None),
            scroll: Cell::new(0.0),
            laid: RefCell::new(None),
            activity: Cell::new(0),
            seen_activity: Cell::new(u64::MAX),
            last_activity: Cell::new(Instant::now()),
            painted_focused: Cell::new(false),
            blinking: Cell::new(false),
        }
    }

    /// Records an edit or caret move: the caret turns solid and the view
    /// scrolls to it at the next paint.
    fn touch(&self) {
        self.activity.set(self.activity.get().wrapping_add(1));
        self.last_activity.set(Instant::now());
    }

    fn elapsed_ms(&self) -> u64 {
        u64::try_from(self.last_activity.get().elapsed().as_millis()).unwrap_or(u64::MAX)
    }

    /// Whether the caret is drawn now.
    fn caret_on(&self) -> bool {
        caret_visible(self.elapsed_ms(), BLINK.load(Ordering::Relaxed))
    }

    /// Starts the blink timer (once). It wakes at each on/off change and
    /// notifies, until the field stops painting focused.
    fn ensure_blink(&self, cx: &mut Context<Self>) {
        if !BLINK.load(Ordering::Relaxed) || self.blinking.replace(true) {
            return;
        }
        cx.spawn(async move |this, cx| {
            loop {
                let Ok(wait) = this.read_with(cx, |f, _| next_toggle_ms(f.elapsed_ms())) else {
                    return;
                };
                cx.background_executor()
                    .timer(Duration::from_millis(wait + 1))
                    .await;
                let again = this.update(cx, |f, cx| {
                    if f.painted_focused.get() {
                        cx.notify();
                        true
                    } else {
                        f.blinking.set(false);
                        false
                    }
                });
                if !matches!(again, Ok(true)) {
                    return;
                }
            }
        })
        .detach();
    }

    /// Index in the displayed string (masked for a passphrase) of a text byte.
    fn display_index(&self, byte: usize) -> usize {
        if self.secret.get() {
            masked_index(self.buf.text(), byte)
        } else {
            byte
        }
    }

    /// How many times the keyboard's action key was pressed. A page that acts
    /// on it (Send in a composer) observes the field and compares this.
    pub fn actions(&self) -> u64 {
        self.actions
    }

    /// Whether the action key leaves the keyboard up instead of putting it
    /// away (the default). A composer that sends on it keeps typing.
    pub fn set_keep_focus_on_action(&mut self, keep: bool) {
        self.keep_focus_on_action = keep;
    }

    /// Marks the field as a passphrase (keyboard `Password` mode). Set by
    /// [`secret_field_box`]; call it directly when drawing the box yourself.
    pub fn set_secret(&self, secret: bool) {
        self.secret.set(secret);
    }

    pub fn is_secret(&self) -> bool {
        self.secret.get()
    }

    /// Sets the text (a pasted value, or a preview typing a query). The caret
    /// goes to the end.
    pub fn set(&mut self, text: &str, cx: &mut Context<Self>) {
        self.buf.set_text(text);
        self.edits += 1;
        self.touch();
        cx.notify();
    }

    pub fn text(&self) -> &str {
        self.buf.text()
    }

    /// The selection in UTF-8 bytes (a caret when empty).
    pub fn selection(&self) -> Range<usize> {
        self.buf.selection()
    }

    /// The composing range in UTF-8 bytes.
    pub fn marked(&self) -> Option<Range<usize>> {
        self.buf.marked()
    }

    pub fn select_all(&mut self, cx: &mut Context<Self>) {
        self.buf.select_all();
        self.touch();
        cx.notify();
    }

    /// Selects `anchor..head` (UTF-8 bytes, clamped to grapheme boundaries).
    pub fn set_selection(&mut self, anchor: usize, head: usize, cx: &mut Context<Self>) {
        self.buf.set_selection(anchor, head);
        self.touch();
        cx.notify();
    }

    pub fn focus_handle(&self) -> FocusHandle {
        self.focus.clone()
    }

    /// Empties the field and keeps focus (the clear button).
    pub fn clear(&mut self, cx: &mut Context<Self>) {
        if !self.buf.is_empty() || self.buf.marked().is_some() {
            self.buf.set_text("");
            self.edits += 1;
            self.touch();
            cx.notify();
        }
    }

    /// The delete key: removes the selection, else the character before the
    /// caret. Keyboards send it as a `backspace` key event, rather than an
    /// input-method edit, when they hold no text.
    pub fn backspace(&mut self, cx: &mut Context<Self>) {
        if self.buf.delete_backward() {
            self.edits += 1;
        }
        self.touch();
        cx.notify();
    }

    /// The forward delete key.
    pub fn delete_forward(&mut self, cx: &mut Context<Self>) {
        if self.buf.delete_forward() {
            self.edits += 1;
        }
        self.touch();
        cx.notify();
    }

    /// Runs `edit` on the buffer; bumps `edits` when the text changed.
    fn mutate(&mut self, cx: &mut Context<Self>, edit: impl FnOnce(&mut EditBuffer)) {
        let before = self.buf.text().len();
        let before_text = (before <= 4096).then(|| self.buf.text().to_owned());
        edit(&mut self.buf);
        let changed = match before_text {
            Some(old) => old != self.buf.text(),
            None => true,
        };
        if changed {
            self.edits += 1;
        }
        self.touch();
        cx.notify();
    }

    fn copy(&self, cx: &mut Context<Self>) {
        if !self.secret.get() && self.buf.has_selection() {
            cx.write_to_clipboard(ClipboardItem::new_string(
                self.buf.selected_text().to_owned(),
            ));
        }
    }

    fn paste(&mut self, cx: &mut Context<Self>) {
        let Some(text) = cx.read_from_clipboard().and_then(|c| c.text()) else {
            return;
        };
        self.mutate(cx, |b| b.replace_selection(&text));
    }

    /// Handles a key event (arrows, Home/End, Backspace, Delete, clipboard
    /// shortcuts). Returns whether the key was used.
    pub fn handle_key(&mut self, event: &KeyDownEvent, cx: &mut Context<Self>) -> bool {
        let k = &event.keystroke;
        let shift = k.modifiers.shift;
        let word = k.modifiers.control || k.modifiers.alt;
        let command = k.modifiers.control || k.modifiers.platform;
        match k.key.as_str() {
            "left" if word => self.mutate(cx, |b| b.word_left(shift)),
            "right" if word => self.mutate(cx, |b| b.word_right(shift)),
            "left" => self.mutate(cx, |b| b.move_left(shift)),
            "right" => self.mutate(cx, |b| b.move_right(shift)),
            "home" => self.mutate(cx, |b| b.home(shift)),
            "end" => self.mutate(cx, |b| b.end(shift)),
            "backspace" => self.backspace(cx),
            "delete" => self.delete_forward(cx),
            "a" if command => self.select_all(cx),
            "c" if command => self.copy(cx),
            "x" if command => {
                self.copy(cx);
                if !self.secret.get() {
                    self.backspace(cx);
                }
            }
            "v" if command => self.paste(cx),
            _ => return false,
        }
        true
    }
}

impl Focusable for TextField {
    fn focus_handle(&self, _: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl EntityInputHandler for TextField {
    fn text_input_mode(&self, _: &mut Window, _: &mut Context<Self>) -> TextInputMode {
        if self.secret.get() {
            TextInputMode::Password
        } else {
            TextInputMode::SingleLine
        }
    }

    fn text_input_purpose(&self, _: &mut Window, _: &mut Context<Self>) -> TextInputPurpose {
        self.purpose
    }

    fn text_input_action(&self, _: &mut Window, _: &mut Context<Self>) -> Option<TextInputAction> {
        Some(self.action)
    }

    fn perform_text_input_action(
        &mut self,
        _: TextInputAction,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        // The enter key puts the keyboard away (unless the page keeps it); the
        // text stays, and a page that observes `actions` acts on it.
        if !self.keep_focus_on_action {
            window.blur();
        }
        self.actions += 1;
        cx.notify();
        true
    }

    fn text_for_range(
        &mut self,
        range: Range<usize>,
        actual: &mut Option<Range<usize>>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<String> {
        let bytes = self.buf.bytes_of(range);
        actual.replace(self.buf.utf16_of(bytes.clone()));
        Some(self.buf.text()[bytes].to_owned())
    }

    fn selected_text_range(
        &mut self,
        _: bool,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<UTF16Selection> {
        let (range, reversed) = self.buf.selection_utf16();
        Some(UTF16Selection { range, reversed })
    }

    fn set_selected_text_range(
        &mut self,
        range_utf16: Range<usize>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // The platform's `setSelection` / the cursor after a commit. The
        // composing range stays as it is.
        self.buf.set_selection_utf16(range_utf16, false);
        self.touch();
        cx.notify();
    }

    fn marked_text_range(&self, _: &mut Window, _: &mut Context<Self>) -> Option<Range<usize>> {
        self.buf.marked().map(|r| self.buf.utf16_of(r))
    }

    fn unmark_text(&mut self, _: &mut Window, _: &mut Context<Self>) {
        self.buf.unmark();
        self.touch();
    }

    fn replace_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.mutate(cx, |b| {
            let range = target_range(b, range);
            b.replace(range, text);
        });
    }

    fn replace_and_mark_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        new_selected_range: Option<Range<usize>>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.mutate(cx, |b| {
            let range = target_range(b, range);
            b.replace_and_mark(range, text, new_selected_range);
        });
    }

    fn replace_and_mark_text_with_selection(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        selection: PreeditSelection,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.mutate(cx, |b| {
            let range = target_range(b, range);
            match selection {
                PreeditSelection::Range { anchor, head } => {
                    b.replace_and_mark(
                        range.clone(),
                        text,
                        Some(anchor.min(head)..anchor.max(head)),
                    );
                    if anchor > head {
                        // The caret sits at the start: a reversed selection.
                        let sel = b.selection();
                        b.set_selection(sel.end, sel.start);
                    }
                }
                PreeditSelection::Hidden => {
                    b.replace_and_mark(range, text, None);
                }
            }
        });
    }

    fn surrounding_text(
        &mut self,
        max_bytes: usize,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<SurroundingText> {
        // Without this snapshot the keyboard believes the field is empty, so
        // it sends a bare delete key instead of deleting text.
        SurroundingText::from_utf8(
            self.buf.text(),
            self.buf.head(),
            self.buf.anchor(),
            self.buf.marked(),
            max_bytes,
        )
    }

    fn delete_surrounding_text(
        &mut self,
        before_utf16: usize,
        after_utf16: usize,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        let mut done = false;
        self.mutate(cx, |b| {
            done = b.delete_surrounding(before_utf16, after_utf16)
        });
        done || (before_utf16 == 0 && after_utf16 == 0)
    }

    fn bounds_for_range(
        &mut self,
        range_utf16: Range<usize>,
        element_bounds: Bounds<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<Bounds<Pixels>> {
        let laid = self.laid.borrow();
        let Some(laid) = laid.as_ref() else {
            return Some(element_bounds);
        };
        let bytes = self.buf.bytes_of(range_utf16);
        let x0 = laid.line.x_for_index(self.display_index(bytes.start));
        let x1 = laid.line.x_for_index(self.display_index(bytes.end));
        let lh = typography::BODY.line;
        let origin = point(
            laid.bounds.left() - px(self.scroll.get()) + x0,
            laid.bounds.top() + (laid.bounds.size.height - lh) / 2.0,
        );
        // A caret for an empty range; the span for composing or selected text.
        Some(Bounds::new(origin, size((x1 - x0).max(px(CARET_W)), lh)))
    }

    fn element_bounds(
        &mut self,
        element_bounds: Bounds<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<Bounds<Pixels>> {
        // The visible text area as last laid out; the keyboard scrolls to it.
        self.viewport.set(Some(element_bounds));
        Some(element_bounds)
    }

    fn character_index_for_point(
        &mut self,
        pt: Point<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<usize> {
        let laid = self.laid.borrow();
        let byte = match laid.as_ref() {
            Some(laid) => {
                let x = pt.x - laid.bounds.left() + px(self.scroll.get());
                let idx = laid.line.closest_index_for_x(x);
                let idx = if laid.masked {
                    unmasked_index(self.buf.text(), idx)
                } else {
                    idx
                };
                self.buf.snap(idx)
            }
            // Not painted yet: a tap answers the caret.
            None => self.buf.head(),
        };
        Some(self.buf.utf16_of(byte..byte).start)
    }

    fn scroll_text_input(
        &mut self,
        delta: Point<Pixels>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        let laid = self.laid.borrow();
        let Some(laid) = laid.as_ref() else {
            return false;
        };
        // Positive deltas move the content right: the offset shrinks.
        let old = self.scroll.get();
        let new = clamp_scroll(
            old - f32::from(delta.x),
            f32::from(laid.line.width),
            f32::from(laid.bounds.size.width),
            CARET_MARGIN,
        );
        // An explicit scroll cancels the pending scroll-to-caret.
        self.seen_activity.set(self.activity.get());
        self.scroll.set(new);
        cx.notify();
        (new - old).abs() > 0.01
    }
}

/// The range a replace targets: the one the keyboard names, else the composing
/// text, else the selection.
fn target_range(b: &EditBuffer, range: Option<Range<usize>>) -> Range<usize> {
    match range {
        Some(r) => b.bytes_of(r),
        None => b.marked().unwrap_or_else(|| b.selection()),
    }
}

/// A key-down listener for the element that tracks the field's focus:
/// the delete key, arrows, Home/End and the clipboard shortcuts.
pub fn on_backspace(
    field: Entity<TextField>,
) -> impl Fn(&KeyDownEvent, &mut Window, &mut App) + 'static {
    move |event, _, cx| {
        if field.update(cx, |field, cx| field.handle_key(event, cx)) {
            cx.stop_propagation();
        }
    }
}

/// An invisible layer that connects the platform keyboard to `field` while it
/// has focus. Put it inside the box that shows the text.
pub fn input_layer(field: Entity<TextField>, cx: &App) -> impl IntoElement {
    text_layer(field, Hsla::default(), Hsla::default(), false, cx)
}

/// The text layer: connects the keyboard, and while `field` has focus shapes
/// and draws its text (scrolled to the caret, no ellipsis), the selection,
/// the composing underline and the caret. `paint` is off for [`input_layer`].
fn text_layer(
    field: Entity<TextField>,
    label: Hsla,
    accent: Hsla,
    paint: bool,
    cx: &App,
) -> impl IntoElement {
    let focus = field.read(cx).focus.clone();
    canvas(
        |_, _, _| (),
        move |bounds, (), window, cx| {
            window.handle_input(
                &focus,
                gpui::ElementInputHandler::new(bounds, field.clone()),
                cx,
            );
            let focused = focus.is_focused(window);
            field.update(cx, |f, cx| {
                f.painted_focused.set(focused);
                if focused && paint {
                    f.ensure_blink(cx);
                }
            });
            if paint {
                paint_text(&field, focused, bounds, label, accent, window, cx);
            }
        },
    )
    .absolute()
    .size_full()
}

/// Shapes and draws the focused field's line.
fn paint_text(
    field: &Entity<TextField>,
    focused: bool,
    bounds: Bounds<Pixels>,
    label: Hsla,
    accent: Hsla,
    window: &mut Window,
    cx: &mut App,
) {
    if !focused {
        field.read(cx).laid.replace(None);
        return;
    }
    let (secret, shown, sel, head, marked, scroll_state) = {
        let f = field.read(cx);
        let secret = f.secret.get();
        let shown: gpui::SharedString = if secret {
            masked(f.buf.text()).into()
        } else {
            f.buf.text().to_owned().into()
        };
        let sel = f.buf.selection();
        let marked = f
            .buf
            .marked()
            .map(|m| f.display_index(m.start)..f.display_index(m.end));
        (
            secret,
            shown,
            f.display_index(sel.start)..f.display_index(sel.end),
            f.display_index(f.buf.head()),
            marked,
            (f.scroll.get(), f.activity.get() != f.seen_activity.get()),
        )
    };
    let font = window.text_style().font();
    let line = window.text_system().shape_line(
        shown.clone(),
        typography::BODY.size,
        &runs(&font, label, shown.len(), marked),
        None,
    );
    let lh = typography::BODY.line;
    let view_w = f32::from(bounds.size.width);
    let text_w = f32::from(line.width);
    let mut scroll = clamp_scroll(scroll_state.0, text_w, view_w, CARET_MARGIN);
    let follow = scroll_state.1;
    if follow {
        let caret_x = f32::from(line.x_for_index(head));
        scroll = scroll_for_caret(scroll, caret_x, text_w, view_w, CARET_MARGIN);
    }
    let caret_on = {
        let f = field.read(cx);
        f.scroll.set(scroll);
        if follow {
            f.seen_activity.set(f.activity.get());
        }
        f.caret_on()
    };

    let top = bounds.top() + (bounds.size.height - lh) / 2.0;
    let left = bounds.left() - px(scroll);
    if !sel.is_empty() {
        let mut quad = fill(
            Bounds::new(
                point(left + line.x_for_index(sel.start), top),
                size(line.x_for_index(sel.end) - line.x_for_index(sel.start), lh),
            ),
            accent.opacity(0.28),
        );
        quad.corner_radii = px(SELECTION_RADIUS).into();
        window.paint_quad(quad);
    }
    if let Err(err) = line.paint(point(left, top), lh, TextAlign::Left, None, window, cx) {
        log::warn!("text field: paint failed: {err}");
    }
    if sel.is_empty() && caret_on {
        let mut quad = fill(
            Bounds::new(
                point(left + line.x_for_index(head), top),
                size(px(CARET_W), lh),
            ),
            accent,
        );
        quad.corner_radii = px(CARET_W / 2.0).into();
        window.paint_quad(quad);
    }
    *field.read(cx).laid.borrow_mut() = Some(Laid {
        line,
        bounds,
        masked: secret,
    });
}

/// Text runs for a line of `len` bytes: the marked range underlined.
fn runs(font: &Font, color: Hsla, len: usize, marked: Option<Range<usize>>) -> Vec<TextRun> {
    let plain = |len| TextRun {
        len,
        font: font.clone(),
        color,
        background_color: None,
        underline: None,
        strikethrough: None,
    };
    match marked.filter(|m| m.start < m.end && m.end <= len) {
        Some(m) => [
            plain(m.start),
            TextRun {
                underline: Some(UnderlineStyle {
                    color: Some(color),
                    thickness: px(MARKED_UNDERLINE),
                    wavy: false,
                }),
                ..plain(m.end - m.start)
            },
            plain(len - m.end),
        ]
        .into_iter()
        .filter(|r| r.len > 0)
        .collect(),
        None => vec![plain(len)],
    }
}

/// The glass box around `field`: `placeholder` while it is empty and
/// unfocused, the text and a caret while typing, and a tap that focuses it
/// and raises the soft keyboard.
pub fn field_box(
    theme: Theme,
    id: impl Into<gpui::ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    window: &Window,
    cx: &App,
) -> impl IntoElement {
    field_box_with(theme, id, field, placeholder, false, window, cx)
}

/// [`field_box`] for a passphrase: every character shows as a dot.
pub fn secret_field_box(
    theme: Theme,
    id: impl Into<gpui::ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    window: &Window,
    cx: &App,
) -> impl IntoElement {
    field_box_with(theme, id, field, placeholder, true, window, cx)
}

/// What a secret field shows for `text`: one dot per character.
pub fn masked(text: &str) -> String {
    "•".repeat(text.chars().count())
}

fn field_box_with(
    theme: Theme,
    id: impl Into<gpui::ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    secret: bool,
    window: &Window,
    cx: &App,
) -> impl IntoElement {
    use gpui::{InteractiveElement, MouseButton, ParentElement};
    use liquid_glass_kit::{
        glass::{GlassSpec, glass},
        tokens::{shadow, typography},
    };

    let colors = crate::theme::colors(theme);
    field.read(cx).set_secret(secret);
    let focus = field.read(cx).focus_handle();
    let raw = field.read(cx).text();
    let text: gpui::SharedString = if secret {
        masked(raw).into()
    } else {
        raw.to_owned().into()
    };
    let typing = focus.is_focused(window);
    // Focused, the text layer draws the line (scrolled to the caret, no
    // ellipsis); otherwise a div shows the start with an end ellipsis.
    let shown = if typing {
        None
    } else if text.is_empty() {
        Some(
            div()
                .truncate()
                .text_color(colors.tertiary_label)
                .child(placeholder),
        )
    } else {
        Some(div().truncate().text_color(colors.label).child(text))
    };
    div()
        .id(id)
        .track_focus(&focus)
        .on_key_down(on_backspace(field.clone()))
        .flex_none()
        .h(px(44.0))
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
                .px(px(16.0))
                .child(
                    div()
                        .relative()
                        .flex_1()
                        .min_w_0()
                        .h_full()
                        .flex()
                        .items_center()
                        .overflow_hidden()
                        .text_size(typography::BODY.size)
                        .child(text_layer(
                            field.clone(),
                            colors.label,
                            colors.accent,
                            true,
                            cx,
                        ))
                        .children(shown),
                ),
        )
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use gpui::{AppContext as _, TestAppContext};

    use super::*;

    struct Host(Entity<TextField>);

    impl gpui::Render for Host {
        fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
            div()
        }
    }

    fn field(cx: &mut TestAppContext) -> Entity<TextField> {
        cx.new(TextField::new)
    }

    fn window_cx(cx: &mut TestAppContext) -> (Entity<TextField>, &mut gpui::VisualTestContext) {
        let (host, cx) = cx.add_window_view(|_, cx| Host(cx.new(TextField::new)));
        let field = host.read_with(cx, |h, _| h.0.clone());
        (field, cx)
    }

    #[gpui::test]
    fn set_text_and_backspace(cx: &mut TestAppContext) {
        let field = field(cx);
        field.update(cx, |f, cx| f.set("https://a\nb", cx));
        field.read_with(cx, |f, _| assert_eq!(f.text(), "https://a b"));
        field.update(cx, |f, cx| f.backspace(cx));
        field.read_with(cx, |f, _| assert_eq!(f.text(), "https://a "));
        field.update(cx, |f, cx| f.clear(cx));
        field.read_with(cx, |f, _| assert_eq!(f.text(), ""));
    }

    #[gpui::test]
    fn android_commit_then_cursor_then_insert_mid_text(cx: &mut TestAppContext) {
        let (field, cx) = window_cx(cx);
        field.update_in(cx, |f, w, cx| {
            // commitText("kar", 1) then setSelection(2, 2).
            f.replace_text_in_range(None, "kar", w, cx);
            assert_eq!(f.selected_text_range(false, w, cx).unwrap().range, 3..3);
            f.set_selected_text_range(2..2, w, cx);
            let sel = f.selected_text_range(false, w, cx).unwrap();
            assert_eq!((sel.range, sel.reversed), (2..2, false));
            // commitText("ş", 1) lands between "ka" and "r".
            f.replace_text_in_range(None, "ş", w, cx);
            assert_eq!(f.text(), "kaşr");
            assert_eq!(f.selected_text_range(false, w, cx).unwrap().range, 3..3);
        });
        field.read_with(cx, |f, _| assert!(f.edits >= 2));
    }

    #[gpui::test]
    fn android_composing_region_over_a_word(cx: &mut TestAppContext) {
        let (field, cx) = window_cx(cx);
        field.update_in(cx, |f, w, cx| {
            f.replace_text_in_range(None, "merhaba dünya", w, cx);
            // setComposingRegion(8, 13) marks "dünya" and keeps the cursor.
            f.set_selected_text_range(13..13, w, cx);
            f.buf.mark_utf16(8..13);
            assert_eq!(f.marked_text_range(w, cx), Some(8..13));
            // setComposingText("dünyalı") replaces the marked word.
            f.replace_and_mark_text_in_range(None, "dünyalı", Some(7..7), w, cx);
            assert_eq!(f.text(), "merhaba dünyalı");
            assert_eq!(f.marked_text_range(w, cx), Some(8..15));
            f.set_selected_text_range(15..15, w, cx);
            // finishComposingText.
            f.unmark_text(w, cx);
            assert_eq!(f.marked_text_range(w, cx), None);
            // The suggestion bar replaces an explicit range.
            f.replace_text_in_range(Some(0..7), "selam", w, cx);
            assert_eq!(f.text(), "selam dünyalı");
        });
    }

    #[gpui::test]
    fn android_delete_surrounding_both_sides(cx: &mut TestAppContext) {
        let (field, cx) = window_cx(cx);
        field.update_in(cx, |f, w, cx| {
            f.replace_text_in_range(None, "abcde", w, cx);
            f.set_selected_text_range(2..3, w, cx);
            // deleteSurroundingText(1, 1) around the selected "c".
            assert!(f.delete_surrounding_text(1, 1, w, cx));
            assert_eq!(f.text(), "ace");
            let s = f.surrounding_text(100, w, cx).unwrap();
            assert_eq!((s.text.as_str(), s.cursor, s.anchor), ("ace", 2, 1));
            // Nothing before the start: clamps instead of failing.
            f.set_selected_text_range(0..0, w, cx);
            assert!(f.delete_surrounding_text(3, 1, w, cx));
            assert_eq!(f.text(), "ce");
        });
    }

    #[gpui::test]
    fn surrounding_text_reports_a_reversed_selection(cx: &mut TestAppContext) {
        let (field, cx) = window_cx(cx);
        field.update_in(cx, |f, w, cx| {
            f.set("abcdef", cx);
            f.set_selection(4, 1, cx);
            let sel = f.selected_text_range(false, w, cx).unwrap();
            assert_eq!((sel.range, sel.reversed), (1..4, true));
            let s = f.surrounding_text(100, w, cx).unwrap();
            assert_eq!((s.cursor, s.anchor), (1, 4));
            // Typing replaces the selection.
            f.replace_text_in_range(None, "X", w, cx);
            assert_eq!(f.text(), "aXef");
        });
    }

    #[gpui::test]
    fn marked_text_with_selection_and_hidden_caret(cx: &mut TestAppContext) {
        let (field, cx) = window_cx(cx);
        field.update_in(cx, |f, w, cx| {
            f.replace_and_mark_text_with_selection(
                None,
                "abc",
                PreeditSelection::Range { anchor: 1, head: 1 },
                w,
                cx,
            );
            assert_eq!(f.selection(), 1..1);
            assert_eq!(f.marked(), Some(0..3));
            f.replace_and_mark_text_with_selection(None, "abcd", PreeditSelection::Hidden, w, cx);
            assert_eq!(f.text(), "abcd");
            assert_eq!(f.selection(), 4..4);
        });
    }

    #[gpui::test]
    fn secret_fields_ask_for_password_mode(cx: &mut TestAppContext) {
        let (field, cx) = window_cx(cx);
        field.update_in(cx, |f, w, cx| {
            assert_eq!(f.text_input_mode(w, cx), TextInputMode::SingleLine);
            f.set_secret(true);
            assert_eq!(f.text_input_mode(w, cx), TextInputMode::Password);
        });
    }

    #[gpui::test]
    fn keys_move_select_and_delete(cx: &mut TestAppContext) {
        use gpui::{Keystroke, Modifiers};
        let (field, cx) = window_cx(cx);
        let key = |s: &str, shift: bool| {
            let mut k = Keystroke::parse(s).unwrap();
            k.modifiers.shift |= shift;
            KeyDownEvent {
                keystroke: k,
                is_held: false,
                prefer_character_input: false,
            }
        };
        field.update(cx, |f, cx| {
            f.set("sağlık", cx);
            assert!(f.handle_key(&key("left", false), cx));
            assert!(f.handle_key(&key("left", true), cx));
            assert_eq!(f.selection(), 5..7);
            assert!(f.handle_key(&key("delete", false), cx));
            assert_eq!(f.text(), "sağlk");
            assert!(f.handle_key(&key("home", false), cx));
            assert!(f.handle_key(&key("delete", false), cx));
            assert_eq!(f.text(), "ağlk");
            assert!(f.handle_key(&key("end", true), cx));
            assert!(f.handle_key(&key("backspace", false), cx));
            assert_eq!(f.text(), "");
            assert!(!f.handle_key(&key("q", false), cx));
            f.set("abc", cx);
            let mut all = key("a", false);
            all.keystroke.modifiers = Modifiers::control();
            assert!(f.handle_key(&all, cx));
            assert_eq!(f.selection(), 0..3);
        });
    }

    #[gpui::test]
    fn clipboard_shortcuts(cx: &mut TestAppContext) {
        use gpui::{Keystroke, Modifiers};
        let (field, cx) = window_cx(cx);
        let ctrl = |c: &str| {
            let mut k = Keystroke::parse(c).unwrap();
            k.modifiers = Modifiers::control();
            KeyDownEvent {
                keystroke: k,
                is_held: false,
                prefer_character_input: false,
            }
        };
        field.update(cx, |f, cx| {
            f.set("hello world", cx);
            f.set_selection(0, 5, cx);
            assert!(f.handle_key(&ctrl("x"), cx));
            assert_eq!(f.text(), " world");
            f.set_selection(6, 6, cx);
            assert!(f.handle_key(&ctrl("v"), cx));
            assert_eq!(f.text(), " worldhello");
            // A secret field never copies.
            f.set_secret(true);
            f.select_all(cx);
            assert!(f.handle_key(&ctrl("c"), cx));
            assert!(f.handle_key(&ctrl("x"), cx));
            assert_eq!(f.text(), " worldhello");
        });
    }

    #[test]
    fn secrets_show_one_dot_per_character() {
        assert_eq!(masked(""), "");
        assert_eq!(masked("şifre1"), "••••••");
    }
}
