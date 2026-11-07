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
    sync::atomic::{AtomicBool, AtomicU8, Ordering},
    time::{Duration, Instant},
};

use gpui::{
    AnyElement, App, Bounds, ClipboardItem, Context, Entity, EntityInputHandler, FocusHandle,
    Focusable, Font, Hsla, InteractiveElement as _, IntoElement, KeyDownEvent, LongPressEvent,
    MouseButton, ParentElement as _, PathBuilder, Pixels, Point, PreeditSelection, ShapedLine,
    Styled, SurroundingText, TextAlign, TextInputAction, TextInputMode, TextInputPurpose, TextRun,
    TouchEvent, TouchPhase, UTF16Selection, UnderlineStyle, Window, WrappedLine, canvas, deferred,
    div, fill, point, px, size,
};
use liquid_glass_kit::{
    glass::{GlassSpec, glass},
    tokens::{Theme, shadow, typography},
};
use rovsti_core::text_edit::EditBuffer;
use rovsti_core::text_touch::{
    ECHO_MS, EDGE_MAX, EDGE_ZONE, HANDLE_HIT, HANDLE_SIZE, INSERTION_MS, MENU_H, MENU_ITEM_PAD,
    MenuItem, MenuState, TapAction, drag_selection, edge_scroll, handle_visible, hits_caret,
    in_selection, menu_items, menu_width, place_menu, resolve_tap, tap_byte, teardrop,
};
use rovsti_core::text_view::{
    CARET_MARGIN, ML_LINE, ML_PAD_V, box_height, capsule_radius, caret_visible, clamp_scroll,
    clamp_scroll_y, locate_paragraph, masked_index, next_toggle_ms, paragraph_for_row,
    paragraph_starts, scroll_for_caret, scroll_y_for_caret, selection_rects, unmasked_index,
    visible_lines,
};

/// Whether carets blink. The screenshot rig turns it off so frames are stable.
static BLINK: AtomicBool = AtomicBool::new(true);

/// Turns caret blinking on or off for every field (on by default).
pub fn set_blink(on: bool) {
    BLINK.store(on, Ordering::Relaxed);
}

/// Who draws the selection handles, magnifier and cut/copy/paste menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SelectionChrome {
    /// The platform's own selection UI (Android's editor).
    Native,
    /// The field draws its own handles and menu (desktop, tests).
    Drawn,
}

/// Override of [`selection_chrome`]: 0 = none, 1 = Native, 2 = Drawn.
static CHROME_OVERRIDE: AtomicU8 = AtomicU8::new(0);

/// The selection chrome in effect: the override if set, else `Native` on
/// Android and `Drawn` elsewhere.
pub fn selection_chrome() -> SelectionChrome {
    match CHROME_OVERRIDE.load(Ordering::Relaxed) {
        1 => SelectionChrome::Native,
        2 => SelectionChrome::Drawn,
        _ if cfg!(target_os = "android") => SelectionChrome::Native,
        _ => SelectionChrome::Drawn,
    }
}

/// Forces a selection chrome for every field (`None` restores the default).
/// For tests and the screenshot rig.
pub fn set_selection_chrome(chrome: Option<SelectionChrome>) {
    let v = match chrome {
        None => 0,
        Some(SelectionChrome::Native) => 1,
        Some(SelectionChrome::Drawn) => 2,
    };
    CHROME_OVERRIDE.store(v, Ordering::Relaxed);
}

/// A touch gesture on a field.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Gesture {
    /// A single tap away from the caret.
    Tap,
    /// Two quick taps.
    DoubleTap,
    /// A tap on the caret (or insertion handle).
    TapOnCaret,
    /// A long press on text.
    LongPress,
    /// A long press in a field with no text.
    LongPressEmpty,
}

/// What the field itself does for a [`Gesture`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ChromeAction {
    /// Leave it to the platform; the field draws and changes nothing.
    Platform,
    /// Move the caret to the touch point.
    PlaceCaret,
    /// Move the caret and show the drawn insertion handle.
    PlaceCaretWithHandle,
    /// Select the word under the touch and show handles and menu.
    SelectWord,
    /// Toggle the drawn menu at the caret.
    ToggleMenu,
    /// Show the drawn paste menu for an empty field.
    ShowPasteMenu,
}

/// The pure policy table: what the field does for `gesture` under `policy`.
/// Under [`SelectionChrome::Native`] the platform owns selection UI, so the
/// field only places the caret on a plain tap.
pub fn chrome_for(policy: SelectionChrome, gesture: Gesture) -> ChromeAction {
    use ChromeAction::*;
    match (policy, gesture) {
        (SelectionChrome::Native, Gesture::Tap) => PlaceCaret,
        (SelectionChrome::Native, _) => Platform,
        (SelectionChrome::Drawn, Gesture::Tap) => PlaceCaretWithHandle,
        (SelectionChrome::Drawn, Gesture::DoubleTap | Gesture::LongPress) => SelectWord,
        (SelectionChrome::Drawn, Gesture::TapOnCaret) => ToggleMenu,
        (SelectionChrome::Drawn, Gesture::LongPressEmpty) => ShowPasteMenu,
    }
}

/// Caret width, dp.
const CARET_W: f32 = 2.0;
/// Selection corner radius, dp.
const SELECTION_RADIUS: f32 = 3.0;
/// Composing-text underline thickness, dp.
const MARKED_UNDERLINE: f32 = 1.5;

/// The field's side padding inside its glass box, dp.
const BOX_PAD: f32 = 16.0;
/// The menu keeps this far below the window's top (the status bar), dp.
const SAFE_TOP: f32 = 48.0;
/// Auto-scroll tick while a finger drags near an edge.
const DRAG_TICK: Duration = Duration::from_millis(16);

/// A finger drag that edits the selection.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Drag {
    /// After a long-press: extends from the pressed word (`start..end`).
    Select(usize, usize),
    /// A handle: `Some(fixed)` keeps the other end of the selection, `None`
    /// is the insertion handle and moves the caret.
    Handle(Option<usize>),
}

/// The text as last shaped and painted: what hit-testing and the keyboard's
/// geometry queries read.
struct Laid {
    line: ShapedLine,
    /// The content box, window coordinates.
    bounds: Bounds<Pixels>,
    /// The text is drawn masked (a passphrase).
    masked: bool,
}

/// A multiline field's wrapped text as last painted: what hit-testing and
/// the keyboard's geometry queries read. Coordinates are relative to the
/// content box, before the vertical scroll.
struct MultiLaid {
    lines: Vec<WrappedLine>,
    /// Byte offset where each paragraph starts.
    starts: Vec<usize>,
    /// First visual row of each paragraph.
    rows_before: Vec<usize>,
    total_rows: usize,
    /// The content box, window coordinates.
    bounds: Bounds<Pixels>,
}

impl MultiLaid {
    fn new(lines: Vec<WrappedLine>, text: &str, bounds: Bounds<Pixels>) -> Self {
        let mut rows_before = Vec::with_capacity(lines.len());
        let mut total_rows = 0;
        for line in &lines {
            rows_before.push(total_rows);
            total_rows += line.wrap_boundaries.len() + 1;
        }
        Self {
            lines,
            starts: paragraph_starts(text),
            rows_before,
            total_rows,
            bounds,
        }
    }

    /// Where the caret at `byte` sits.
    fn pos(&self, byte: usize, lh: Pixels) -> Point<Pixels> {
        let (p, local) = locate_paragraph(&self.starts, byte);
        let Some(line) = self.lines.get(p) else {
            return point(px(0.0), px(0.0));
        };
        let at = line.position_for_index(local, lh).unwrap_or_default();
        point(at.x, at.y + lh * self.rows_before[p])
    }

    /// The visual row of the caret at `byte`.
    fn row(&self, byte: usize, lh: Pixels) -> usize {
        (f32::from(self.pos(byte, lh).y) / f32::from(lh)).round() as usize
    }

    /// The text byte nearest to `at`.
    fn index_at(&self, at: Point<Pixels>, lh: Pixels) -> usize {
        let row = ((f32::from(at.y) / f32::from(lh)).floor().max(0.0) as usize)
            .min(self.total_rows.saturating_sub(1));
        let p = paragraph_for_row(&self.rows_before, row);
        let Some(line) = self.lines.get(p) else {
            return 0;
        };
        let local = point(
            at.x.max(px(0.0)),
            (at.y - lh * self.rows_before[p]).max(px(0.0)),
        );
        let local = match line.closest_index_for_position(local, lh) {
            Ok(i) | Err(i) => i,
        };
        self.starts[p] + local
    }
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
    /// The content box as last laid out, focused or not (window coordinates).
    content: Cell<Option<Bounds<Pixels>>>,
    /// The edit menu is open: `edits` and selection when it opened. Any edit
    /// or selection change since closes it.
    menu: Cell<Option<(u64, usize, usize)>>,
    /// When the insertion handle was shown, and `edits` then.
    insertion: Cell<Option<(Instant, u64)>>,
    drag: Cell<Option<Drag>>,
    /// The dragging finger's window x.
    pointer_x: Cell<f32>,
    drag_loop: Cell<bool>,
    /// Taps before this are the echo of a lift that ended a drag.
    ignore_until: Cell<Instant>,
    /// Handle touch targets as last painted, with the drag each starts.
    handles: RefCell<Vec<(Drag, Bounds<Pixels>)>>,
    /// A multiline field: the fewest and most lines it shows.
    lines: Option<(usize, usize)>,
    /// Vertical scroll of a multiline field, dp.
    scroll_y: Cell<f32>,
    /// The wrapped text as last painted (multiline).
    multi: RefCell<Option<MultiLaid>>,
    /// The line count the box was last sized for (multiline).
    shown_lines: Cell<usize>,
    /// The dragging finger's window y.
    pointer_y: Cell<f32>,
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
            lines: None,
            scroll_y: Cell::new(0.0),
            multi: RefCell::new(None),
            shown_lines: Cell::new(0),
            pointer_y: Cell::new(0.0),
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
            content: Cell::new(None),
            menu: Cell::new(None),
            insertion: Cell::new(None),
            drag: Cell::new(None),
            pointer_x: Cell::new(0.0),
            drag_loop: Cell::new(false),
            ignore_until: Cell::new(Instant::now()),
            handles: RefCell::new(Vec::new()),
        }
    }

    /// A multiline field for messages: it wraps, grows from `min_lines` to
    /// `max_lines` lines of 22 dp, then scrolls to the caret. Enter inserts a
    /// line break; sending is up to a button.
    pub fn multiline(min_lines: usize, max_lines: usize, cx: &mut Context<Self>) -> Self {
        let mut field = Self::with_input(TextInputPurpose::Text, TextInputAction::Send, cx);
        let min = min_lines.max(1);
        field.lines = Some((min, max_lines.max(min)));
        field.buf = EditBuffer::new(true);
        field
    }

    /// Whether the field wraps over several lines.
    pub fn is_multiline(&self) -> bool {
        self.lines.is_some()
    }

    /// The lines the multiline box shows now (1 for a single-line field).
    pub fn shown_lines(&self) -> usize {
        self.shown_lines.get().max(1)
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

    /// Opens the edit menu as a long-press would (previews and screenshots).
    pub fn open_edit_menu(&self) {
        self.open_menu();
    }

    /// Shows the insertion handle as a tap would (previews and screenshots).
    pub fn show_insertion_handle(&self, cx: &mut Context<Self>) {
        self.show_insertion(cx);
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
            "up" | "down" if self.lines.is_some() => self.move_vertical(k.key == "down", shift, cx),
            "enter" if self.lines.is_some() && !command => {
                self.mutate(cx, |b| b.replace_selection("\n"));
            }
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

/// Touch: tap, double-tap, long-press, drag, handles and the edit menu.
impl TextField {
    /// The displayed line (masked for a passphrase), shaped for hit-testing.
    fn shape_display(&self, window: &Window) -> ShapedLine {
        let shown: gpui::SharedString = if self.secret.get() {
            masked(self.buf.text()).into()
        } else {
            self.buf.text().to_owned().into()
        };
        let font = window.text_style().font();
        window.text_system().shape_line(
            shown.clone(),
            typography::BODY.size,
            &runs(&font, Hsla::default(), shown.len(), None),
            None,
        )
    }

    /// Scroll offset of the text as shown: unfocused fields show the start.
    fn shown_scroll(&self, window: &Window) -> f32 {
        if self.focus.is_focused(window) {
            self.scroll.get()
        } else {
            0.0
        }
    }

    /// The text byte under window point `at`.
    fn byte_at(&self, at: Point<Pixels>, window: &Window) -> usize {
        if self.lines.is_some() {
            return self.multi_byte_at(at).unwrap_or_else(|| self.buf.head());
        }
        let x = at.x;
        let Some(content) = self.content.get() else {
            return self.buf.head();
        };
        let line = self.shape_display(window);
        let byte = tap_byte(
            self.buf.text(),
            self.secret.get(),
            f32::from(x),
            f32::from(content.left()),
            self.shown_scroll(window),
            |lx| line.closest_index_for_x(px(lx)),
        );
        self.buf.snap(byte)
    }

    /// The byte under window point `at` in the painted multiline text.
    fn multi_byte_at(&self, at: Point<Pixels>) -> Option<usize> {
        let multi = self.multi.borrow();
        let multi = multi.as_ref()?;
        let rel = point(
            at.x - multi.bounds.left(),
            at.y - multi.bounds.top() + px(self.scroll_y.get()),
        );
        Some(self.buf.snap(multi.index_at(rel, typography::BODY.line)))
    }

    /// Window position of the top of the caret at `byte` in the painted
    /// multiline text.
    fn multi_caret(&self, byte: usize) -> Option<Point<Pixels>> {
        let multi = self.multi.borrow();
        let multi = multi.as_ref()?;
        let at = multi.pos(byte, typography::BODY.line);
        Some(point(
            multi.bounds.left() + at.x,
            multi.bounds.top() + at.y - px(self.scroll_y.get()),
        ))
    }

    /// Up/Down by one wrapped row, keeping the column (the arrow keys).
    fn move_vertical(&mut self, down: bool, extend: bool, cx: &mut Context<Self>) {
        let lh = typography::BODY.line;
        let Some(at) = self.multi_caret(self.buf.head()) else {
            return;
        };
        let dy = if down { lh * 1.5 } else { -(lh * 0.5) };
        let to = self
            .multi_byte_at(point(at.x, at.y + dy))
            .unwrap_or_else(|| self.buf.head());
        let anchor = if extend { self.buf.anchor() } else { to };
        self.buf.set_selection(anchor, to);
        self.touch();
        cx.notify();
    }

    /// [`Self::byte_at`] from the painted line (no window needed).
    fn byte_at_laid(&self, x: f32, y: f32) -> Option<usize> {
        if self.lines.is_some() {
            return self.multi_byte_at(point(px(x), px(y)));
        }
        let laid = self.laid.borrow();
        let laid = laid.as_ref()?;
        let byte = tap_byte(
            self.buf.text(),
            laid.masked,
            x,
            f32::from(laid.bounds.left()),
            self.scroll.get(),
            |lx| laid.line.closest_index_for_x(px(lx)),
        );
        Some(self.buf.snap(byte))
    }

    fn open_menu(&self) {
        let sel = self.buf.selection();
        self.menu.set(Some((self.edits, sel.start, sel.end)));
    }

    /// Whether the edit menu is showing now.
    fn menu_visible(&self, window: &Window) -> bool {
        let sel = self.buf.selection();
        self.drag.get().is_none()
            && self.focus.is_focused(window)
            && self.menu.get() == Some((self.edits, sel.start, sel.end))
    }

    /// Whether the insertion handle is showing now.
    fn insertion_visible(&self) -> bool {
        matches!(self.insertion.get(), Some((at, edits))
            if edits == self.edits
                && !self.buf.has_selection()
                && at.elapsed() < Duration::from_millis(INSERTION_MS))
    }

    /// Shows the insertion handle for [`INSERTION_MS`] and repaints when it goes.
    fn show_insertion(&self, cx: &mut Context<Self>) {
        self.insertion.set(Some((Instant::now(), self.edits)));
        cx.spawn(async move |this, cx| {
            cx.background_executor()
                .timer(Duration::from_millis(INSERTION_MS + 50))
                .await;
            this.update(cx, |_, cx| cx.notify()).ok();
        })
        .detach();
    }

    fn ignore_echo(&self) {
        self.ignore_until
            .set(Instant::now() + Duration::from_millis(ECHO_MS));
    }

    /// A tap at window `position` (`click_count` 2 is a double-tap): focuses
    /// the field and raises the keyboard, then places the caret, toggles the
    /// edit menu (a tap on the caret or the selection) or selects the word.
    pub fn tap(
        &mut self,
        position: Point<Pixels>,
        click_count: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.drag.get().is_some() || Instant::now() < self.ignore_until.get() {
            return;
        }
        let focused = self.focus.is_focused(window);
        let byte = self.byte_at(position, window);
        let sel = self.buf.selection();
        let on_target = focused
            && if sel.is_empty() && self.lines.is_some() {
                self.multi_caret(self.buf.head()).is_some_and(|caret| {
                    let mid = f32::from(caret.y + typography::BODY.line / 2.0);
                    hits_caret(f32::from(position.x), f32::from(caret.x))
                        && (f32::from(position.y) - mid).abs() <= ML_LINE * 0.75
                })
            } else if sel.is_empty() {
                self.content.get().is_some_and(|content| {
                    let line = self.shape_display(window);
                    let caret_x = f32::from(content.left()) - self.scroll.get()
                        + f32::from(line.x_for_index(self.display_index(self.buf.head())));
                    hits_caret(f32::from(position.x), caret_x)
                })
            } else {
                in_selection(byte, &sel)
            };
        if !focused {
            self.scroll.set(0.0);
        }
        let handle = self.focus.clone();
        window.focus(&handle, cx);
        window.show_soft_keyboard();
        match resolve_tap(click_count, focused, on_target) {
            TapAction::PlaceCaret => {
                self.buf.unmark();
                self.buf.set_caret(byte);
                self.menu.set(None);
                self.show_insertion(cx);
            }
            TapAction::ToggleMenu => {
                if self.menu.get().is_some() {
                    self.menu.set(None);
                } else {
                    self.open_menu();
                }
            }
            TapAction::SelectWord => {
                self.buf.unmark();
                let word = self.buf.word_at(byte);
                self.buf.set_selection(word.start, word.end);
                self.menu.set(None);
                self.insertion.set(None);
            }
        }
        self.touch();
        cx.notify();
    }

    /// A long-press at window `position`: focuses, selects the word under it
    /// and arms the menu (Paste only in an empty field). The finger may keep
    /// going: its drag extends the selection until it lifts.
    pub fn long_press(
        &mut self,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let byte = self.byte_at(position, window);
        if !self.focus.is_focused(window) {
            self.scroll.set(0.0);
        }
        let handle = self.focus.clone();
        window.focus(&handle, cx);
        window.show_soft_keyboard();
        let word = if self.buf.is_empty() {
            0..0
        } else {
            self.buf.word_at(byte)
        };
        self.buf.unmark();
        self.buf.set_selection(word.start, word.end);
        self.insertion.set(None);
        self.drag.set(Some(Drag::Select(word.start, word.end)));
        self.pointer_x.set(f32::from(position.x));
        self.pointer_y.set(f32::from(position.y));
        self.open_menu();
        crate::haptics::perform(window, gpui::HapticFeedback::LongPress);
        self.ensure_drag_loop(cx);
        self.touch();
        cx.notify();
    }

    /// Raw touch: a finger landing on a handle starts dragging it; a drag in
    /// progress follows the finger and ends when it lifts. Returns whether
    /// the event was used.
    pub fn touch_event(&mut self, event: &TouchEvent, cx: &mut Context<Self>) -> bool {
        match event.phase {
            TouchPhase::Started => {
                let hit = self
                    .handles
                    .borrow()
                    .iter()
                    .find(|(_, rect)| rect.contains(&event.position))
                    .map(|(drag, _)| *drag);
                let Some(drag) = hit else { return false };
                self.drag.set(Some(drag));
                self.pointer_x.set(f32::from(event.position.x));
                self.pointer_y.set(f32::from(event.position.y));
                self.ensure_drag_loop(cx);
                cx.notify();
                true
            }
            TouchPhase::Moved => {
                let Some(drag) = self.drag.get() else {
                    return false;
                };
                self.pointer_x.set(f32::from(event.position.x));
                self.pointer_y.set(f32::from(event.position.y));
                self.apply_drag(drag, cx);
                true
            }
            _ => {
                if self.drag.take().is_none() {
                    return false;
                }
                self.ignore_echo();
                if self.menu.get().is_some() || self.buf.has_selection() {
                    self.open_menu();
                }
                cx.notify();
                true
            }
        }
    }

    /// Moves the selection to the finger.
    fn apply_drag(&mut self, drag: Drag, cx: &mut Context<Self>) {
        let Some(idx) = self.byte_at_laid(self.pointer_x.get(), self.pointer_y.get()) else {
            return;
        };
        match drag {
            Drag::Select(start, end) => {
                let (anchor, head) = drag_selection(&(start..end), idx);
                self.buf.set_selection(anchor, head);
            }
            Drag::Handle(Some(fixed)) => self.buf.set_selection(fixed, idx),
            Drag::Handle(None) => self.buf.set_caret(idx),
        }
        cx.notify();
    }

    /// Scrolls the text while a finger holds near an edge.
    fn ensure_drag_loop(&self, cx: &mut Context<Self>) {
        if self.drag_loop.replace(true) {
            return;
        }
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(DRAG_TICK).await;
                let again = this.update(cx, |f, cx| f.drag_tick(cx));
                if !matches!(again, Ok(true)) {
                    return;
                }
            }
        })
        .detach();
    }

    fn drag_tick(&mut self, cx: &mut Context<Self>) -> bool {
        let Some(drag) = self.drag.get() else {
            self.drag_loop.set(false);
            return false;
        };
        let scrolled = if self.lines.is_some() {
            let multi = self.multi.borrow();
            multi.as_ref().is_some_and(|m| {
                let (top, bottom) = (f32::from(m.bounds.top()), f32::from(m.bounds.bottom()));
                let delta = edge_scroll(self.pointer_y.get(), top, bottom, EDGE_ZONE, EDGE_MAX);
                let old = self.scroll_y.get();
                let new = clamp_scroll_y(old + delta, m.total_rows as f32 * ML_LINE, bottom - top);
                self.scroll_y.set(new);
                (new - old).abs() > 0.01
            })
        } else {
            let laid = self.laid.borrow();
            laid.as_ref().is_some_and(|laid| {
                let (left, right) = (
                    f32::from(laid.bounds.left()),
                    f32::from(laid.bounds.right()),
                );
                let delta = edge_scroll(self.pointer_x.get(), left, right, EDGE_ZONE, EDGE_MAX);
                let old = self.scroll.get();
                let new = clamp_scroll(
                    old + delta,
                    f32::from(laid.line.width),
                    right - left,
                    CARET_MARGIN,
                );
                self.scroll.set(new);
                (new - old).abs() > 0.01
            })
        };
        if scrolled {
            // The drag owns the scroll: no scroll-to-caret for this move.
            self.seen_activity.set(self.activity.get());
            self.apply_drag(drag, cx);
        }
        true
    }

    /// Runs a tapped edit-menu item.
    fn menu_action(&mut self, item: MenuItem, cx: &mut Context<Self>) {
        match item {
            MenuItem::Cut => {
                if !self.secret.get() {
                    self.copy(cx);
                    self.backspace(cx);
                }
                self.menu.set(None);
            }
            MenuItem::Copy => {
                self.copy(cx);
                self.menu.set(None);
            }
            MenuItem::Paste => {
                self.paste(cx);
                self.menu.set(None);
            }
            MenuItem::SelectAll => {
                self.select_all(cx);
                self.open_menu();
            }
        }
        self.insertion.set(None);
        cx.notify();
    }

    /// Window x the menu centres on: the caret or the middle of the selection.
    fn menu_anchor_x(&self) -> Option<f32> {
        if self.lines.is_some() {
            let sel = self.buf.selection();
            let (a, b) = (self.multi_caret(sel.start)?, self.multi_caret(sel.end)?);
            // A selection over several rows centres on the field instead.
            return ((a.y - b.y).abs() < px(1.0)).then(|| f32::from(a.x + b.x) / 2.0);
        }
        let laid = self.laid.borrow();
        let laid = laid.as_ref()?;
        let sel = self.buf.selection();
        let x = |b: usize| f32::from(laid.line.x_for_index(self.display_index(b)));
        Some(f32::from(laid.bounds.left()) - self.scroll.get() + (x(sel.start) + x(sel.end)) / 2.0)
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
        } else if self.lines.is_some() {
            TextInputMode::Multiline
        } else {
            TextInputMode::SingleLine
        }
    }

    fn text_input_purpose(&self, _: &mut Window, _: &mut Context<Self>) -> TextInputPurpose {
        self.purpose
    }

    fn text_input_action(&self, _: &mut Window, _: &mut Context<Self>) -> Option<TextInputAction> {
        // The enter key of a multiline field types a line break.
        if self.lines.is_some() {
            return None;
        }
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
        if self.lines.is_some() {
            let bytes = self.buf.bytes_of(range_utf16);
            let lh = typography::BODY.line;
            let (Some(a), Some(b)) = (self.multi_caret(bytes.start), self.multi_caret(bytes.end))
            else {
                return Some(element_bounds);
            };
            // The span on one row; a range over several rows ends at the edge.
            let right = self
                .multi
                .borrow()
                .as_ref()
                .map_or(a.x, |m| m.bounds.right());
            let end = if (a.y - b.y).abs() < px(1.0) {
                b.x
            } else {
                right
            };
            return Some(Bounds::new(a, size((end - a.x).max(px(CARET_W)), lh)));
        }
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
        if self.lines.is_some() {
            let byte = self.multi_byte_at(pt).unwrap_or_else(|| self.buf.head());
            return Some(self.buf.utf16_of(byte..byte).start);
        }
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
        if self.lines.is_some() {
            let multi = self.multi.borrow();
            let Some(m) = multi.as_ref() else {
                return false;
            };
            let old = self.scroll_y.get();
            let new = clamp_scroll_y(
                old - f32::from(delta.y),
                m.total_rows as f32 * ML_LINE,
                f32::from(m.bounds.size.height),
            );
            self.seen_activity.set(self.activity.get());
            self.scroll_y.set(new);
            cx.notify();
            return (new - old).abs() > 0.01;
        }
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
            field.read(cx).content.set(Some(bounds));
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
    if field.read(cx).lines.is_some() {
        paint_multiline(field, focused, bounds, label, accent, window, cx);
        return;
    }
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

/// Shapes the multiline text wrapped to `width`, runs underlining `marked`.
fn shape_multi(
    window: &Window,
    text: &str,
    width: Pixels,
    color: Hsla,
    marked: Option<Range<usize>>,
) -> Vec<WrappedLine> {
    let font = window.text_style().font();
    let shared: gpui::SharedString = text.to_owned().into();
    window
        .text_system()
        .shape_text(
            shared,
            typography::BODY.size,
            &runs(&font, color, text.len(), marked),
            Some(width.max(px(1.0))),
            None,
        )
        .map(|lines| lines.into_iter().collect())
        .unwrap_or_default()
}

/// Wrapped rows of a multiline field's text at `width`.
fn count_rows(window: &Window, text: &str, width: Pixels) -> usize {
    shape_multi(window, text, width, Hsla::default(), None)
        .iter()
        .map(|l| l.wrap_boundaries.len() + 1)
        .sum::<usize>()
        .max(1)
}

/// Shapes and draws a multiline field: the wrapped text scrolled to the
/// caret, the selection per row, the composing underline and the caret.
fn paint_multiline(
    field: &Entity<TextField>,
    focused: bool,
    bounds: Bounds<Pixels>,
    label: Hsla,
    accent: Hsla,
    window: &mut Window,
    cx: &mut App,
) {
    let (text, sel, head, marked, scroll_y, follow, (min, max)) = {
        let f = field.read(cx);
        (
            f.buf.text().to_owned(),
            f.buf.selection(),
            f.buf.head(),
            f.buf.marked(),
            f.scroll_y.get(),
            f.activity.get() != f.seen_activity.get(),
            f.lines.unwrap_or((1, 1)),
        )
    };
    let lh = typography::BODY.line;
    let lines = shape_multi(window, &text, bounds.size.width, label, marked);
    let laid = MultiLaid::new(lines, &text, bounds);
    let content_h = laid.total_rows as f32 * ML_LINE;
    let view_h = f32::from(bounds.size.height);
    let visible = visible_lines(laid.total_rows, min, max);
    let mut scroll = clamp_scroll_y(scroll_y, content_h, view_h);
    if follow && focused {
        let caret_top = f32::from(laid.pos(head, lh).y);
        scroll = scroll_y_for_caret(scroll, caret_top, ML_LINE, content_h, view_h);
    }
    // The box was sized for another line count: repaint with the right
    // height, and scroll to the caret again then.
    let settled = field.read(cx).shown_lines.get() == visible;
    let caret_on = {
        let f = field.read(cx);
        f.scroll_y.set(scroll);
        if follow && settled {
            f.seen_activity.set(f.activity.get());
        }
        f.caret_on()
    };
    if !settled {
        field.update(cx, |f, cx| {
            f.shown_lines.set(visible);
            cx.notify();
        });
    }

    let left = bounds.left();
    let top = bounds.top() - px(scroll);
    if focused && sel.start < sel.end {
        let at = |byte: usize| {
            let p = laid.pos(byte, lh);
            (f32::from(p.x), laid.row(byte, lh))
        };
        let width = f32::from(bounds.size.width);
        for (l, row, r) in selection_rects(at(sel.start), at(sel.end), width) {
            let mut quad = fill(
                Bounds::new(
                    point(left + px(l), top + lh * row),
                    size(px((r - l).max(0.0)), lh),
                ),
                accent.opacity(0.28),
            );
            quad.corner_radii = px(SELECTION_RADIUS).into();
            window.paint_quad(quad);
        }
    }
    for (line, rows) in laid.lines.iter().zip(&laid.rows_before) {
        if let Err(err) = line.paint(
            point(left, top + lh * *rows),
            lh,
            TextAlign::Left,
            None,
            window,
            cx,
        ) {
            log::warn!("text field: paint failed: {err}");
        }
    }
    if focused && sel.is_empty() && caret_on {
        let p = laid.pos(head, lh);
        let mut quad = fill(
            Bounds::new(point(left + p.x, top + p.y), size(px(CARET_W), lh)),
            accent,
        );
        quad.corner_radii = px(CARET_W / 2.0).into();
        window.paint_quad(quad);
    }
    *field.read(cx).multi.borrow_mut() = Some(laid);
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

/// The touch layer over a whole field box (not clipped to the text): the
/// gestures, and the selection handles hanging below the text.
fn touch_layer(field: Entity<TextField>, accent: Hsla) -> impl IntoElement {
    canvas(
        |_, _, _| (),
        move |bounds, (), window, cx| {
            let f = field.clone();
            window.on_mouse_event(move |event: &TouchEvent, phase, window, cx| {
                if !phase.bubble() {
                    return;
                }
                if f.update(cx, |f, cx| f.touch_event(event, cx)) {
                    window.prevent_default();
                    cx.stop_propagation();
                }
            });
            let f = field.clone();
            window.on_mouse_event(move |event: &LongPressEvent, phase, window, cx| {
                if !phase.bubble()
                    || window.default_prevented()
                    || !bounds.contains(&event.position)
                {
                    return;
                }
                f.update(cx, |f, cx| f.long_press(event.position, window, cx));
                window.prevent_default();
                cx.stop_propagation();
            });
            paint_handles(&field, accent, window, cx);
        },
    )
    .absolute()
    .size_full()
}

/// A handle to draw: its tip (if on screen), tip-to-centre direction and drag.
type HandleWant = (Option<(f32, f32)>, (f32, f32), Drag);

/// Paints the selection handles (or the insertion handle) and records their
/// touch targets.
fn paint_handles(field: &Entity<TextField>, accent: Hsla, window: &mut Window, cx: &mut App) {
    let f = field.read(cx);
    f.handles.borrow_mut().clear();
    if !f.focus.is_focused(window) {
        return;
    }
    let laid = f.laid.borrow();
    let multi = f.multi.borrow();
    let (left, right, top, bottom) = match (f.lines.is_some(), laid.as_ref(), multi.as_ref()) {
        (true, _, Some(m)) => (
            f32::from(m.bounds.left()),
            f32::from(m.bounds.right()),
            f32::from(m.bounds.top()),
            f32::from(m.bounds.bottom()),
        ),
        (false, Some(l), _) => (
            f32::from(l.bounds.left()),
            f32::from(l.bounds.right()),
            f32::from(l.bounds.top()),
            f32::from(l.bounds.bottom()),
        ),
        _ => return,
    };
    let sel = f.buf.selection();
    let r = HANDLE_SIZE / 2.0;
    let lh = typography::BODY.line;
    // Where the handle of the caret at `byte` points: (tip x, tip y), or
    // `None` when that caret has scrolled out of the box.
    let tip_of = |byte: usize| -> Option<(f32, f32)> {
        if f.lines.is_some() {
            let at = f.multi_caret(byte)?;
            let (x, y) = (f32::from(at.x), f32::from(at.y));
            return (y >= top - 1.0 && y + ML_LINE <= bottom + 1.0).then_some((x, y + ML_LINE));
        }
        let laid = laid.as_ref()?;
        let tip_y = f32::from(laid.bounds.top() + (laid.bounds.size.height + lh) / 2.0);
        let x = left - f.scroll.get() + f32::from(laid.line.x_for_index(f.display_index(byte)));
        Some((x, tip_y))
    };
    // (tip, direction tip -> centre, the drag it starts)
    let wanted: Vec<HandleWant> = if !sel.is_empty() {
        vec![
            (tip_of(sel.start), (-1.0, 1.0), Drag::Handle(Some(sel.end))),
            (tip_of(sel.end), (1.0, 1.0), Drag::Handle(Some(sel.start))),
        ]
    } else if f.insertion_visible() || matches!(f.drag.get(), Some(Drag::Handle(None))) {
        vec![(tip_of(f.buf.head()), (0.0, 1.0), Drag::Handle(None))]
    } else {
        Vec::new()
    };
    let mut hits = Vec::new();
    for (tip, dir, drag) in wanted {
        let Some((tip_x, tip_y)) = tip else { continue };
        if !handle_visible(tip_x, left, right) {
            continue;
        }
        let pts: Vec<Point<Pixels>> = teardrop((tip_x, tip_y), dir, r, 28)
            .into_iter()
            .map(|(x, y)| point(px(x), px(y)))
            .collect();
        let mut path = PathBuilder::fill();
        path.add_polygon(&pts, true);
        match path.build() {
            Ok(path) => window.paint_path(path, accent),
            Err(err) => log::warn!("text field: handle path failed: {err}"),
        }
        let len = dir.0.hypot(dir.1);
        let reach = r * std::f32::consts::SQRT_2 / len;
        let (cx0, cy0) = (tip_x + dir.0 * reach, tip_y + dir.1 * reach);
        hits.push((
            drag,
            Bounds::new(
                point(px(cx0 - HANDLE_HIT / 2.0), px(cy0 - HANDLE_HIT / 2.0)),
                size(px(HANDLE_HIT), px(HANDLE_HIT)),
            ),
        ));
    }
    *f.handles.borrow_mut() = hits;
}

/// The edit menu as a glass capsule above the field (below it when there is
/// no room), kept on screen. `None` while closed or with nothing to offer.
fn edit_menu(
    theme: Theme,
    field: &Entity<TextField>,
    pad: f32,
    window: &Window,
    cx: &App,
) -> Option<AnyElement> {
    let f = field.read(cx);
    if !f.menu_visible(window) {
        return None;
    }
    let content = f.content.get()?;
    let sel = f.buf.selection();
    let clipboard_empty = cx
        .read_from_clipboard()
        .and_then(|c| c.text())
        .is_none_or(|t| t.is_empty());
    let items = menu_items(MenuState {
        has_selection: !sel.is_empty(),
        secret: f.secret.get(),
        clipboard_empty,
        text_empty: f.buf.is_empty(),
        all_selected: !f.buf.is_empty() && sel == (0..f.buf.text().len()),
    });
    if items.is_empty() {
        return None;
    }
    let colors = crate::theme::colors(theme);
    let font = window.text_style().font();
    let widths: Vec<f32> = items
        .iter()
        .map(|item| {
            let label: gpui::SharedString = item.label().into();
            let line = window.text_system().shape_line(
                label.clone(),
                typography::SUBHEADLINE.size,
                &runs(&font, Hsla::default(), label.len(), None),
                None,
            );
            f32::from(line.width)
        })
        .collect();
    let width = menu_width(&widths);
    let anchor = f
        .menu_anchor_x()
        .unwrap_or_else(|| f32::from(content.center().x));
    let (left, top) = place_menu(
        anchor,
        f32::from(content.top()),
        f32::from(content.bottom()),
        width,
        f32::from(window.viewport_size().width),
        SAFE_TOP,
    );
    // The box's own origin: the content box sits `pad` in from the left.
    let rel_left = left - (f32::from(content.left()) - pad);
    let rel_top = top - f32::from(content.top());
    let buttons = items.into_iter().enumerate().map(|(i, item)| {
        let field = field.clone();
        div()
            .id(("edit-menu-item", i))
            .h_full()
            .px(px(MENU_ITEM_PAD))
            .flex()
            .items_center()
            .text_size(typography::SUBHEADLINE.size)
            .text_color(colors.label)
            .child(item.label())
            .on_mouse_down(MouseButton::Left, move |_, _, cx| {
                field.update(cx, |f, cx| f.menu_action(item, cx));
                cx.stop_propagation();
            })
    });
    Some(
        deferred(
            div()
                .absolute()
                .left(px(rel_left))
                .top(px(rel_top))
                .w(px(width))
                .h(px(MENU_H))
                .occlude()
                .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                .child(
                    glass(GlassSpec::control(theme))
                        .size_full()
                        .rounded_full()
                        .shadow(shadow::glass(theme.is_dark()))
                        .flex()
                        .items_center()
                        .justify_center()
                        .children(buttons),
                ),
        )
        .with_priority(10)
        .into_any_element(),
    )
}

/// Invisible touch targets over the handles, so a press on one never reaches
/// what lies under it.
fn handle_shields(
    field: &Entity<TextField>,
    pad: f32,
    window: &Window,
    cx: &App,
) -> Vec<AnyElement> {
    let f = field.read(cx);
    let Some(content) = f.content.get() else {
        return Vec::new();
    };
    if !f.focus.is_focused(window) {
        return Vec::new();
    }
    let origin = point(content.left() - px(pad), content.top());
    f.handles
        .borrow()
        .iter()
        .enumerate()
        .map(|(i, (_, rect))| {
            deferred(
                div()
                    .id(("handle-shield", i))
                    .absolute()
                    .left(rect.left() - origin.x)
                    .top(rect.top() - origin.y)
                    .size(px(HANDLE_HIT))
                    .occlude()
                    .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation()),
            )
            .with_priority(9)
            .into_any_element()
        })
        .collect()
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
    field_box_with(
        theme,
        id,
        field,
        placeholder,
        Shell::Glass { secret: false },
        window,
        cx,
    )
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
    field_box_with(
        theme,
        id,
        field,
        placeholder,
        Shell::Glass { secret: true },
        window,
        cx,
    )
}

/// What a secret field shows for `text`: one dot per character.
pub fn masked(text: &str) -> String {
    "•".repeat(text.chars().count())
}

/// How [`bare_field`] sizes itself.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Bare {
    /// Height of a single-line field, dp; `None` fills the parent, whose
    /// height must then be definite.
    pub height: Option<f32>,
    /// Space above and below the lines of a multiline field, dp.
    pub pad_v: f32,
}

/// What draws around the text of [`field_box_with`].
#[derive(Clone, Copy)]
enum Shell {
    /// A glass capsule; `secret` shows dots.
    Glass { secret: bool },
    /// Nothing: the page draws the box.
    Bare(Bare),
}

/// A field without a glass box, for pages that draw their own (a form row, a
/// search capsule). It fills the width of its flex parent. A single-line field
/// is `style.height` dp tall (`None`: the parent's height, which must be
/// definite); a multiline one sizes itself to its rows plus `style.pad_v`
/// above and below. Focus, keys, touch, selection handles and the
/// edit menu work exactly as in [`field_box`].
pub fn bare_field(
    theme: Theme,
    id: impl Into<gpui::ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    style: Bare,
    window: &Window,
    cx: &App,
) -> AnyElement {
    field_box_with(
        theme,
        id,
        field,
        placeholder,
        Shell::Bare(style),
        window,
        cx,
    )
}

fn field_box_with(
    theme: Theme,
    id: impl Into<gpui::ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    shell: Shell,
    window: &Window,
    cx: &App,
) -> AnyElement {
    let (secret, bare) = match shell {
        Shell::Glass { secret } => (secret, None),
        Shell::Bare(style) => (false, Some(style)),
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
    let multiline = field.read(cx).lines;
    // A multiline field draws its text itself, always; its box is sized for
    // the rows the text wraps to at the width last painted.
    let rows_lines = multiline.map(|(min, max)| {
        let f = field.read(cx);
        let rows = match f.content.get() {
            Some(content) => count_rows(window, &text, content.size.width),
            None => paragraph_starts(&text).len(),
        };
        let lines = visible_lines(rows, min, max);
        f.shown_lines.set(lines);
        lines
    });
    // Focused, the text layer draws the line (scrolled to the caret, no
    // ellipsis); otherwise a div shows the start with an end ellipsis.
    let shown = if typing || (multiline.is_some() && !text.is_empty()) {
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
    let inner = div()
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
        .children(shown);
    if let Some(bare) = bare {
        // No glass: the page draws the box. The field still owns focus, keys,
        // touch, handles and the edit menu.
        let height = match (rows_lines, bare.height) {
            (Some(lines), _) => Some(lines as f32 * ML_LINE + 2.0 * bare.pad_v),
            (None, height) => height,
        };
        let shell = div()
            .id(id)
            .track_focus(&focus)
            .on_key_down(on_backspace(field.clone()))
            .relative()
            .flex_1()
            .min_w_0();
        let shell = match height {
            Some(h) => shell.h(px(h)),
            None => shell.h_full(),
        };
        return shell
            .flex()
            .items_center()
            .py(px(if multiline.is_some() { bare.pad_v } else { 0.0 }))
            .on_mouse_down(MouseButton::Left, {
                let field = field.clone();
                move |event, window, cx| {
                    field.update(cx, |f, cx| {
                        f.tap(event.position, event.click_count, window, cx);
                    });
                }
            })
            .child(inner)
            .child(touch_layer(field.clone(), colors.accent))
            .children(handle_shields(field, 0.0, window, cx))
            .children(edit_menu(theme, field, 0.0, window, cx))
            .into_any_element();
    }
    div()
        .id(id)
        .track_focus(&focus)
        .on_key_down(on_backspace(field.clone()))
        .flex_none()
        .h(px(rows_lines.map_or(44.0, box_height)))
        .on_mouse_down(MouseButton::Left, {
            let field = field.clone();
            move |event, window, cx| {
                field.update(cx, |f, cx| {
                    f.tap(event.position, event.click_count, window, cx);
                });
                cx.stop_propagation();
            }
        })
        .child(
            glass(GlassSpec::control(theme))
                .size_full()
                .rounded(px(capsule_radius(rows_lines.unwrap_or(1))))
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .items_center()
                .px(px(BOX_PAD))
                .py(px(if multiline.is_some() { ML_PAD_V } else { 0.0 }))
                .child(inner),
        )
        .child(touch_layer(field.clone(), colors.accent))
        .children(handle_shields(field, BOX_PAD, window, cx))
        .children(edit_menu(theme, field, BOX_PAD, window, cx))
        .into_any_element()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use gpui::{AppContext as _, TestAppContext};

    use super::*;

    #[test]
    fn chrome_table_native_leaves_it_to_the_platform() {
        use ChromeAction::*;
        let n = SelectionChrome::Native;
        assert_eq!(chrome_for(n, Gesture::Tap), PlaceCaret);
        for g in [
            Gesture::DoubleTap,
            Gesture::TapOnCaret,
            Gesture::LongPress,
            Gesture::LongPressEmpty,
        ] {
            assert_eq!(chrome_for(n, g), Platform);
        }
    }

    #[test]
    fn chrome_table_drawn() {
        use ChromeAction::*;
        let d = SelectionChrome::Drawn;
        assert_eq!(chrome_for(d, Gesture::Tap), PlaceCaretWithHandle);
        assert_eq!(chrome_for(d, Gesture::DoubleTap), SelectWord);
        assert_eq!(chrome_for(d, Gesture::TapOnCaret), ToggleMenu);
        assert_eq!(chrome_for(d, Gesture::LongPress), SelectWord);
        assert_eq!(chrome_for(d, Gesture::LongPressEmpty), ShowPasteMenu);
    }

    #[test]
    fn chrome_override_round_trips() {
        set_selection_chrome(Some(SelectionChrome::Native));
        assert_eq!(selection_chrome(), SelectionChrome::Native);
        set_selection_chrome(Some(SelectionChrome::Drawn));
        assert_eq!(selection_chrome(), SelectionChrome::Drawn);
        set_selection_chrome(None);
        assert_eq!(selection_chrome(), SelectionChrome::Drawn);
    }

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

    /// A host that draws a real field box so layout and hit-testing work.
    struct BoxHost(Entity<TextField>);

    impl gpui::Render for BoxHost {
        fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
            div().size_full().p(px(16.0)).child(field_box(
                Theme::Dark,
                "f",
                &self.0,
                "Ara",
                window,
                cx,
            ))
        }
    }

    fn box_cx<'a>(
        cx: &'a mut TestAppContext,
        text: &str,
    ) -> (Entity<TextField>, &'a mut gpui::VisualTestContext) {
        let (host, cx) = cx.add_window_view(|_, cx| BoxHost(cx.new(TextField::new)));
        let field = host.read_with(cx, |h, _| h.0.clone());
        field.update(cx, |f, cx| f.set(text, cx));
        cx.run_until_parked();
        cx.update(|window, _| window.refresh());
        cx.run_until_parked();
        (field, cx)
    }

    /// Window x of byte `b` in the unfocused, unscrolled line.
    fn x_of(field: &Entity<TextField>, b: usize, cx: &mut gpui::VisualTestContext) -> f32 {
        field.update_in(cx, |f, w, _| {
            let line = f.shape_display(w);
            f32::from(f.content.get().unwrap().left() + line.x_for_index(b))
        })
    }

    #[gpui::test]
    fn tap_on_an_unfocused_field_focuses_and_places_the_caret(cx: &mut TestAppContext) {
        let (field, cx) = box_cx(cx, "merhaba dünya");
        let x = x_of(&field, 3, cx);
        field.update_in(cx, |f, w, cx| {
            assert!(!f.focus.is_focused(w));
            f.tap(point(px(x + 1.0), px(40.0)), 1, w, cx);
            assert!(f.focus.is_focused(w));
            assert_eq!(f.selection(), 3..3);
            assert!(f.insertion.get().is_some());
        });
    }

    #[gpui::test]
    fn tap_in_a_focused_field_moves_the_caret_and_the_caret_toggles_the_menu(
        cx: &mut TestAppContext,
    ) {
        let (field, cx) = box_cx(cx, "merhaba dünya");
        let (x2, x9) = (x_of(&field, 2, cx), x_of(&field, 9, cx));
        field.update_in(cx, |f, w, cx| {
            f.tap(point(px(x2), px(40.0)), 1, w, cx);
            f.tap(point(px(x9), px(40.0)), 1, w, cx);
            assert_eq!(f.selection(), 9..9);
            assert!(f.menu.get().is_none());
        });
        cx.run_until_parked();
        field.update_in(cx, |f, w, cx| {
            // The painted caret is now at 9: tapping it opens the menu.
            f.tap(point(px(x9), px(40.0)), 1, w, cx);
            assert!(f.menu.get().is_some());
            f.tap(point(px(x9), px(40.0)), 1, w, cx);
            assert!(f.menu.get().is_none());
            assert_eq!(f.selection(), 9..9);
        });
    }

    #[gpui::test]
    fn double_tap_selects_the_word(cx: &mut TestAppContext) {
        let (field, cx) = box_cx(cx, "merhaba dünya");
        let x = x_of(&field, 10, cx);
        field.update_in(cx, |f, w, cx| {
            f.tap(point(px(x), px(40.0)), 2, w, cx);
            assert!(f.focus.is_focused(w));
            assert_eq!(&f.text()[f.selection()], "dünya");
        });
    }

    #[gpui::test]
    fn long_press_selects_the_word_and_arms_the_menu(cx: &mut TestAppContext) {
        let (field, cx) = box_cx(cx, "merhaba dünya");
        let x = x_of(&field, 2, cx);
        field.update_in(cx, |f, w, cx| {
            f.long_press(point(px(x), px(40.0)), w, cx);
            assert_eq!(&f.text()[f.selection()], "merhaba");
            assert!(f.menu.get().is_some());
            assert!(f.drag.get().is_some());
        });
    }

    #[gpui::test]
    fn long_press_on_an_empty_field_arms_a_menu(cx: &mut TestAppContext) {
        let (field, cx) = box_cx(cx, "");
        field.update_in(cx, |f, w, cx| {
            f.long_press(point(px(30.0), px(40.0)), w, cx);
            assert_eq!(f.selection(), 0..0);
            assert!(f.menu.get().is_some());
        });
    }

    #[gpui::test]
    fn menu_actions_use_the_clipboard(cx: &mut TestAppContext) {
        let (field, cx) = window_cx(cx);
        field.update(cx, |f, cx| {
            f.set("hello world", cx);
            f.set_selection(0, 5, cx);
            f.menu_action(MenuItem::Copy, cx);
            assert_eq!(f.text(), "hello world");
            f.menu_action(MenuItem::Cut, cx);
            assert_eq!(f.text(), " world");
            f.set_selection(6, 6, cx);
            f.menu_action(MenuItem::Paste, cx);
            assert_eq!(f.text(), " worldhello");
            f.menu_action(MenuItem::SelectAll, cx);
            assert_eq!(f.selection(), 0..11);
            assert!(f.menu.get().is_some());
            // A secret field neither copies nor cuts.
            f.set_secret(true);
            f.menu_action(MenuItem::Cut, cx);
            assert_eq!(f.text(), " worldhello");
        });
    }

    #[gpui::test]
    fn dragging_a_handle_moves_one_end(cx: &mut TestAppContext) {
        let (field, cx) = box_cx(cx, "bir iki üç dört");
        let (x1, x12) = (x_of(&field, 1, cx), x_of(&field, 12, cx));
        field.update_in(cx, |f, w, cx| {
            f.tap(point(px(x1), px(40.0)), 2, w, cx);
            assert_eq!(&f.text()[f.selection()], "bir");
        });
        cx.run_until_parked();
        cx.update(|window, _| window.refresh());
        cx.run_until_parked();
        field.update(cx, |f, cx| {
            // Grab the end handle, drag it to byte 12.
            let rect = f.handles.borrow().last().map(|(_, r)| *r).unwrap();
            let start = TouchEvent {
                phase: TouchPhase::Started,
                position: rect.center(),
                ..Default::default()
            };
            assert!(f.touch_event(&start, cx));
            f.pointer_x.set(x12);
            f.apply_drag(f.drag.get().unwrap(), cx);
            assert_eq!(f.selection(), 0..12);
            let end = TouchEvent {
                phase: TouchPhase::Ended,
                position: rect.center(),
                ..Default::default()
            };
            assert!(f.touch_event(&end, cx));
            assert!(f.drag.get().is_none());
        });
    }

    /// A host that draws a multiline composer in a narrow window.
    struct MultiHost(Entity<TextField>);

    impl gpui::Render for MultiHost {
        fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
            div().w(px(200.0)).p(px(16.0)).child(field_box(
                Theme::Dark,
                "m",
                &self.0,
                "Mesaj",
                window,
                cx,
            ))
        }
    }

    fn multi_cx<'a>(
        cx: &'a mut TestAppContext,
        text: &str,
    ) -> (Entity<TextField>, &'a mut gpui::VisualTestContext) {
        let (host, cx) =
            cx.add_window_view(|_, cx| MultiHost(cx.new(|cx| TextField::multiline(1, 3, cx))));
        let field = host.read_with(cx, |h, _| h.0.clone());
        field.update(cx, |f, cx| f.set(text, cx));
        for _ in 0..4 {
            cx.run_until_parked();
            cx.update(|window, _| window.refresh());
        }
        cx.run_until_parked();
        (field, cx)
    }

    #[gpui::test]
    fn multiline_asks_for_multiline_input_without_an_action_key(cx: &mut TestAppContext) {
        let (field, cx) = multi_cx(cx, "");
        field.update_in(cx, |f, w, cx| {
            assert_eq!(f.text_input_mode(w, cx), TextInputMode::Multiline);
            assert_eq!(f.text_input_action(w, cx), None);
            assert!(f.is_multiline());
        });
    }

    #[gpui::test]
    fn multiline_keeps_line_breaks_and_enter_inserts_one(cx: &mut TestAppContext) {
        use gpui::Keystroke;
        let (field, cx) = multi_cx(cx, "bir\r\niki");
        let enter = KeyDownEvent {
            keystroke: Keystroke::parse("enter").unwrap(),
            is_held: false,
            prefer_character_input: false,
        };
        field.update(cx, |f, cx| {
            assert_eq!(f.text(), "bir\niki");
            f.set_selection(3, 3, cx);
            assert!(f.handle_key(&enter, cx));
            assert_eq!(f.text(), "bir\n\niki");
            assert_eq!(f.selection(), 4..4);
        });
    }

    struct BareHost(Entity<TextField>);

    impl gpui::Render for BareHost {
        fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
            div().w(px(320.0)).flex().child(bare_field(
                Theme::Dark,
                "b",
                &self.0,
                "Not",
                Bare {
                    height: Some(44.0),
                    pad_v: 5.0,
                },
                window,
                cx,
            ))
        }
    }

    #[gpui::test]
    fn a_bare_note_field_wraps_and_sizes_to_its_rows(cx: &mut TestAppContext) {
        let (host, cx) =
            cx.add_window_view(|_, cx| BareHost(cx.new(|cx| TextField::multiline(2, 4, cx))));
        let field = host.read_with(cx, |h, _| h.0.clone());
        let long = "bu bir not, birkaç satıra sarılacak kadar uzun yazılmış bir metindir";
        field.update_in(cx, |f, w, cx| {
            let handle = f.focus_handle();
            w.focus(&handle, cx);
            f.set(long, cx);
        });
        for _ in 0..4 {
            cx.run_until_parked();
            cx.update(|window, _| window.refresh());
        }
        field.read_with(cx, |f, _| {
            assert!(f.shown_lines() >= 3, "{} lines", f.shown_lines());
            assert!(f.is_multiline());
            assert_eq!(f.text(), long);
        });
    }

    #[gpui::test]
    fn multiline_box_grows_with_the_wrapped_rows_up_to_the_maximum(cx: &mut TestAppContext) {
        let (field, cx) = multi_cx(cx, "kısa");
        field.read_with(cx, |f, _| assert_eq!(f.shown_lines(), 1));
        let long = "uzun bir mesaj yazıyorum burada, satıra sığmayacak kadar uzun ve daha da uzun";
        field.update_in(cx, |f, w, cx| {
            let handle = f.focus_handle();
            w.focus(&handle, cx);
            f.set(long, cx);
        });
        for _ in 0..4 {
            cx.run_until_parked();
            cx.update(|window, _| window.refresh());
        }
        field.read_with(cx, |f, _| {
            assert_eq!(f.shown_lines(), 3);
            let rows = f.multi.borrow().as_ref().unwrap().total_rows;
            assert!(rows > 3, "{rows} rows");
            // The caret is at the end: the view scrolled down to it.
            let m = f.multi.borrow();
            let m = m.as_ref().unwrap();
            assert!(
                f.scroll_y.get() > 0.0,
                "scroll {} rows {} bounds {:?}",
                f.scroll_y.get(),
                m.total_rows,
                m.bounds
            );
        });
    }

    #[gpui::test]
    fn multiline_hit_testing_round_trips_through_the_wrapped_layout(cx: &mut TestAppContext) {
        let (field, cx) = multi_cx(cx, "merhaba dünya, bugün hava çok güzel\nikinci satır");
        field.update(cx, |f, cx| {
            f.set_selection(0, 0, cx);
            f.scroll_y.set(0.0);
        });
        cx.run_until_parked();
        cx.update(|window, _| window.refresh());
        field.read_with(cx, |f, _| {
            let lh = typography::BODY.line;
            for byte in [0, 5, 9, 21, 36, 40, 45] {
                let at = f.multi_caret(byte).unwrap();
                // A point inside the caret's row, at the caret's x.
                let got = f.multi_byte_at(point(at.x + px(0.5), at.y + lh / 2.0));
                assert_eq!(got, Some(byte), "byte {byte}");
            }
        });
    }

    #[gpui::test]
    fn multiline_up_and_down_move_by_wrapped_rows(cx: &mut TestAppContext) {
        let (field, cx) = multi_cx(cx, "bir iki üç dört beş altı yedi sekiz dokuz on");
        field.update(cx, |f, cx| {
            f.set_selection(0, 0, cx);
            f.scroll_y.set(0.0);
        });
        cx.run_until_parked();
        cx.update(|window, _| window.refresh());
        field.update(cx, |f, cx| {
            f.move_vertical(true, false, cx);
            let first = f.selection().start;
            assert!(first > 0, "moved down a row");
            f.move_vertical(true, false, cx);
            assert!(f.selection().start > first);
            f.move_vertical(false, false, cx);
            f.move_vertical(false, false, cx);
            assert_eq!(f.selection(), 0..0);
        });
    }

    #[test]
    fn secrets_show_one_dot_per_character() {
        assert_eq!(masked(""), "");
        assert_eq!(masked("şifre1"), "••••••");
    }
}
