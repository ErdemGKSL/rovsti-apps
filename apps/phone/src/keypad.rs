//! The Keypad tab (stories 4.2 and 4.3): typing, pasting, deleting and the
//! Call button. Keys act on finger-down, so a digit is in the field on the
//! first frame; the synthesised mouse-down that follows a tap is ignored once
//! a touch screen has been seen.

use std::{ops::Range, sync::Arc, time::Duration};

use gpui::{
    AnyElement, Context, FontWeight, HapticFeedback, HighlightStyle, InteractiveElement,
    IntoElement, MouseButton, ParentElement, Role, SharedString, StatefulInteractiveElement,
    Styled, StyledText, Window, div, prelude::*, px, svg,
};
use rovsti_core::{
    dial::{self, Push},
    names,
    t9::{T9Hit, T9Scratch},
};
use rovsti_ui::{
    Icon,
    glass::{GlassGrid, GlassInteraction, GlassSpec, glass, glass_interactive, paint_glass_grid},
    motion::press_scale,
    motion_tokens::scale,
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, mix, radius, shadow, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp, Status,
    backend::{self, Load, Placement},
    contacts::person_avatar,
    flight::ButtonFrom,
    fx::{A_CALL, A_FIELD, A_KEY, A_LAST},
};

/// Height of the title block above the keypad: top bar, large title, gap.
pub const HEADER_H: f32 = 56.0 + 41.0 + 8.0;
const LONG_ZERO: u64 = 400;
/// The layout input before the first frame (a 412x915 phone).
pub fn default_input() -> rovsti_core::keypad_layout::KeypadInput {
    rovsti_core::keypad_layout::KeypadInput {
        viewport_w: 412.0,
        viewport_h: 915.0,
        insets: Default::default(),
        text_len: 0,
        header_h: HEADER_H,
        tab_bar_h: f32::from(rovsti_ui::tokens::metrics::TAB_BAR_HEIGHT) * 1.1,
        tab_bar_margin: f32::from(rovsti_ui::tokens::metrics::TAB_BAR_MARGIN),
    }
}
/// Holding a digit key for speed dial or voicemail.
const LONG_DIGIT: u64 = 500;
const LONG_DELETE: u64 = 500;

/// Fewest typed characters worth looking up (the shortest emergency codes).
const MATCH_MIN: usize = 3;

/// The contact the typed number belongs to (story 10.1).
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Found {
    id: i64,
    name: SharedString,
    /// The spoken label, built once instead of on every render.
    label: SharedString,
    monogram: Option<SharedString>,
    hue: Option<usize>,
}

/// The lookup behind the status line: asked off the UI thread whenever the
/// typed number changes. A newer key press drops the task of the older one.
#[derive(Default)]
pub(crate) struct DialMatch {
    /// The typed text the answer below is for (or being fetched for).
    query: String,
    /// Address of the contact index it was asked of: a new snapshot (the
    /// contacts finished loading, or changed) asks again.
    index: usize,
    found: Option<Found>,
    task: Option<gpui::Task<()>>,
}

/// Whether the status line offers "Add to contacts" and "Message": a valid
/// number is typed, its lookup has settled with no match, and no status text
/// is in the way.
pub(crate) fn offer_add(dialed: &str, settled: bool, found: bool, status_none: bool) -> bool {
    settled && !found && status_none && dial::validate(dialed).is_ok()
}

/// Most T9 suggestions shown above the field.
const T9_SHOWN: usize = 3;
/// Height of one suggestion row and the gap between rows.
const T9_ROW_H: f32 = 48.0;
const T9_GAP: f32 = 6.0;
/// Height of a row in the Matches sheet.
const T9_RESULT_H: f32 = 58.0;

/// The T9 suggestions for the digits in the field, computed like
/// [`DialMatch`]: off the UI thread whenever the field or the contact index
/// changes. The scratch (the previous query's candidates) travels into the
/// task and back, so each extra digit only narrows the last answer.
#[derive(Default)]
pub(crate) struct T9Match {
    /// The typed text the hits are for (or being fetched for).
    query: String,
    /// Address of the contact index they were asked of.
    index: usize,
    /// The name format the spans were computed under.
    format: rovsti_core::wire::NameFormat,
    hits: Vec<T9Hit>,
    /// Bumped whenever `hits` changes, so [`T9Rows`] knows to resolve again.
    hits_gen: u64,
    /// `None` while a task holds it.
    scratch: Option<T9Scratch>,
    task: Option<gpui::Task<()>>,
    /// The strip's rows, resolved from `hits` when they (or the exact match,
    /// the index or the name format) change, never per frame.
    rows: T9Rows,
    /// Every hit, for the Matches sheet; resolved when it opens (and when the
    /// hits change under it), never per frame.
    all: T9All,
}

/// Every hit resolved for the Matches sheet.
#[derive(Default)]
struct T9All {
    key: Option<AllKey>,
    rows: Arc<[Suggestion]>,
}

/// What [`T9All`] was resolved from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct AllKey {
    hits_gen: u64,
    index: usize,
    format: rovsti_core::wire::NameFormat,
}

/// What [`T9Rows`] was resolved from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct RowsKey {
    hits_gen: u64,
    index: usize,
    format: rovsti_core::wire::NameFormat,
    exact: Option<i64>,
    /// Rows the layout has room for.
    cap: usize,
}

/// The resolved strip: names, numbers and spoken labels built once.
#[derive(Default)]
struct T9Rows {
    key: Option<RowsKey>,
    shown: Vec<Suggestion>,
    more: usize,
    /// "N more" and its spoken label, when `more > 0`.
    more_text: SharedString,
    more_aria: SharedString,
}

/// Title of the T9 results list.
pub(crate) fn results_title(digits: &str, count: usize) -> String {
    if count == 1 {
        format!("1 match for {digits}")
    } else {
        format!("{count} matches for {digits}")
    }
}

/// Whether the "allow contacts" hint shows under the keypad.
pub(crate) fn contacts_hint_shown(denied: bool, digits_typed: bool, dismissed: bool) -> bool {
    denied && digits_typed && !dismissed
}

/// Parses a T9 result row id: `sheet-t9-{i}` is (i, false), `sheet-t9c-{i}` is (i, true).
pub(crate) fn t9_result_index(id: &str) -> Option<(usize, bool)> {
    if let Some(i) = id.strip_prefix("sheet-t9c-") {
        i.parse().ok().map(|i| (i, true))
    } else {
        id.strip_prefix("sheet-t9-")?
            .parse()
            .ok()
            .map(|i| (i, false))
    }
}

/// The digits worth a T9 lookup: at least one, and nothing but digits (`+`,
/// `*` and `#` are not T9).
fn t9_digits(dialed: &str) -> Option<&str> {
    (!dialed.is_empty() && dialed.bytes().all(|b| b.is_ascii_digit())).then_some(dialed)
}

/// What the strip shows: the hits without the contact the status line already
/// names, at most `cap` (and [`T9_SHOWN`]), and how many more there are. `exact` is that
/// contact's position in the contacts payload (a [`T9Hit::contact_idx`]).
pub(crate) fn visible_suggestions(
    hits: &[T9Hit],
    exact: Option<i64>,
    cap: usize,
) -> (Vec<T9Hit>, usize) {
    let mut rest = hits
        .iter()
        .filter(|hit| exact != Some(i64::from(hit.contact_idx)));
    let shown: Vec<T9Hit> = rest.by_ref().take(cap.min(T9_SHOWN)).copied().collect();
    (shown, rest.count())
}

/// One hit resolved into a row: name, matched span, number and labels.
fn suggestion_of(
    index: &rovsti_core::search::ContactIndex,
    hit: &T9Hit,
    format: rovsti_core::wire::NameFormat,
) -> Option<Suggestion> {
    let contact = index.contact(hit.contact_idx)?;
    let number = contact.numbers.get(usize::from(hit.number_idx))?;
    let name = names::display(contact, format).into_owned();
    let span = hit.name_span.and_then(|s| byte_range(&name, s));
    Some(Suggestion {
        monogram: rovsti_core::avatar::monogram(&name).map(|c| c.to_string().into()),
        hue: rovsti_core::avatar::hue_index(Some(contact.id)),
        label: format!("Call {name}, {}", number.raw).into(),
        number: number.raw.clone().into(),
        span,
        name: name.into(),
    })
}

/// Byte range of the characters `span` of `name`, clamped to the name.
fn byte_range(name: &str, span: (u16, u16)) -> Option<Range<usize>> {
    let (start, end) = (usize::from(span.0), usize::from(span.1));
    if start >= end {
        return None;
    }
    let mut offsets = name
        .char_indices()
        .map(|(i, _)| i)
        .chain(std::iter::once(name.len()));
    let from = offsets.by_ref().nth(start)?;
    let to = if end - start == 1 {
        offsets.next()?
    } else {
        offsets.nth(end - start - 1).unwrap_or(name.len())
    };
    Some(from..to)
}

/// One row of the strip, resolved from a hit.
struct Suggestion {
    name: SharedString,
    span: Option<Range<usize>>,
    number: SharedString,
    label: SharedString,
    monogram: Option<SharedString>,
    hue: Option<usize>,
}

/// S2: the digit that was just deleted. It stays in the row, narrowing to
/// nothing while it shrinks to 0.6 and fades on `morph`, so the digits left
/// ease together once and the string never re-lays out in jumps.
pub(crate) struct Ghost {
    digit: Option<char>,
    width: f32,
    t: Spring,
}

impl Default for Ghost {
    fn default() -> Self {
        Self {
            digit: None,
            width: 0.0,
            t: Spring::new(1.0, rovsti_ui::motion_tokens::morph()).with_epsilon(0.002),
        }
    }
}

impl Ghost {
    fn begin(&mut self, digit: char, width: f32) {
        self.digit = Some(digit);
        self.width = width;
        self.t.snap(0.0);
        self.t.set_target(1.0);
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        if self.digit.is_none() {
            return false;
        }
        if self.t.tick(dt) {
            true
        } else {
            self.digit = None;
            false
        }
    }
}

/// One dial pad key. Ids and spoken labels are static, so building the keypad
/// allocates nothing per frame.
#[derive(Clone, Copy)]
pub struct Key {
    pub digit: &'static str,
    /// Letter caption under the digit ("ABC"), or the plus hint on `0`.
    pub letters: &'static str,
    /// Press id on the Keypad tab.
    pub id: &'static str,
    /// Press id on the In-Call keypad.
    pub in_id: &'static str,
    /// What a screen reader says (UX-DR22).
    pub label: &'static str,
    /// Spoken label on the Keypad tab when the key also does something on hold.
    pub hold_label: Option<&'static str>,
}

impl Key {
    const fn new(
        digit: &'static str,
        letters: &'static str,
        id: &'static str,
        in_id: &'static str,
        label: &'static str,
    ) -> Self {
        Self {
            digit,
            letters,
            id,
            in_id,
            label,
            hold_label: None,
        }
    }

    const fn with_hold(mut self, hold_label: &'static str) -> Self {
        self.hold_label = Some(hold_label);
        self
    }
}

/// Dial pad keys in reading order.
pub const KEYS: [Key; 12] = [
    Key::new("1", "", "key-1", "ik-1", "1").with_hold("1, hold for voicemail"),
    Key::new("2", "ABC", "key-2", "ik-2", "2 A B C"),
    Key::new("3", "DEF", "key-3", "ik-3", "3 D E F"),
    Key::new("4", "GHI", "key-4", "ik-4", "4 G H I"),
    Key::new("5", "JKL", "key-5", "ik-5", "5 J K L"),
    Key::new("6", "MNO", "key-6", "ik-6", "6 M N O"),
    Key::new("7", "PQRS", "key-7", "ik-7", "7 P Q R S"),
    Key::new("8", "TUV", "key-8", "ik-8", "8 T U V"),
    Key::new("9", "WXYZ", "key-9", "ik-9", "9 W X Y Z"),
    Key::new("*", "", "key-*", "ik-*", "star"),
    Key::new("0", "+", "key-0", "ik-0", "0 plus"),
    Key::new("#", "", "key-#", "ik-#", "pound"),
];

#[derive(Clone, Copy)]
pub(crate) enum HoldAction {
    Plus,
    /// A digit 1..=9 was held on an empty field: voicemail or speed dial.
    Digit(u8),
    /// Delete is held: erase the next character from the right (after this
    /// many repeats), then arm the next erase while the finger stays down.
    Erase(u32),
    /// The Setup title was held: open Diagnostics (story 14.0f).
    Diagnostics,
    /// The Call button was held with a default SIM: open the chooser (story 16.4).
    SimChooser,
    /// A number's call circle in the Contact sheet was held (its index).
    SimNumber(u8),
    /// The Settings title was held: open Diagnostics (story 16.1).
    SettingsDiagnostics,
    /// The Audio control was held: open the route list (story 14.6).
    RouteList,
    /// A dimmed In-Call control (its [`Slot`](crate::zone::Slot) index) was held: say why.
    Reason(u8),
}

impl PhoneApp {
    // ---- Press routing ---------------------------------------------------

    /// Mouse-down (desktop, or the synthesised one on a touch screen).
    pub(crate) fn mouse_down(
        &mut self,
        id: &'static str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.presses.touch_seen() {
            return;
        }
        self.presses.down(id);
        self.activate(id, window, cx);
    }

    /// The Call button dials on the tap (finger-up on a touch screen), so a
    /// stray touch never places a call. Press feedback still starts on
    /// finger-down through the raw-touch listener.
    pub(crate) fn call_tap(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        // A hold already opened the chooser: the lift that follows is not a tap.
        if self.sheet.is_visible() {
            return;
        }
        if !self.presses.touch_seen() {
            self.presses.down("call");
        }
        self.activate("call", window, cx);
    }

    /// Whether `id` acts on finger-down.
    pub(crate) fn acts_on_down(id: &str) -> bool {
        id.starts_with("key-")
            || id.starts_with("ik-")
            || id.starts_with("ic-")
            || matches!(id, "delete" | "paste" | "grant" | "wa-call" | "kp-vm-set")
    }

    pub(crate) fn activate(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        match id {
            "delete" => self.delete_press(window, cx),
            "call" => self.call_press(window, cx),
            "paste" => self.paste_press(window, cx),
            "wa-call" => self.whatsapp_press(window, cx),
            "kp-ct-grant" => {
                self.t9_hint_dismissed = true;
                self.contacts_pill("cs-grant", window, cx);
                cx.notify();
            }
            "kp-vm-set" => {
                let outcome =
                    backend::open_system(rovsti_android::telecom::SystemPage::CallSettings);
                if let Some(text) = outcome.notice() {
                    self.set_status(text);
                }
            }
            "kp-name" => {
                let id = self.dial_match.found.as_ref().map(|f| f.id);
                let row = id.and_then(|id| self.contacts.all.iter().find(|r| r.id == id).cloned());
                if let Some(row) = row {
                    self.open_contact_sheet(row, false, window, cx);
                }
            }
            "kp-add" => {
                let outcome = backend::add_contact(&self.dialed);
                if outcome != backend::Outcome::Done {
                    self.set_status(outcome.why_not("Couldn't open the Contacts app"));
                }
            }
            "kp-msg" => {
                let outcome = backend::send_sms_to(&self.dialed);
                if outcome != backend::Outcome::Done {
                    self.set_status(outcome.why_not("Couldn't open Messages"));
                }
            }
            "grant" => {
                let raw = self.dialed.clone();
                self.backend
                    .update(cx, |backend, cx| backend.grant_phone_access(&raw, cx));
                self.status = Some(Status::Text("Waiting for permission...".into()));
                self.status_in.restart();
            }
            _ if id.starts_with("key-") => {
                if let Some(key) = id.chars().nth(4) {
                    self.key_press(key, window, cx);
                }
            }
            _ => self.incall_activate(id, window, cx),
        }
        cx.notify();
    }

    /// The finger lifted (or the mouse button): end holds and tones.
    pub(crate) fn released(&mut self, cx: &mut Context<Self>) {
        self.hold_gen += 1;
        self.presses.release_all();
        self.settings.gear_held = false;
        self.incall_released(cx);
    }

    // ---- Keypad actions --------------------------------------------------

    pub(crate) fn arm_hold(
        &mut self,
        ms: u64,
        action: HoldAction,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.hold_gen += 1;
        let generation = self.hold_gen;
        cx.spawn_in(window, async move |this, cx| {
            cx.background_executor()
                .timer(Duration::from_millis(ms))
                .await;
            this.update_in(cx, |this, window, cx| {
                if this.hold_gen == generation {
                    this.hold_fired(action, window, cx);
                }
            })
            .ok();
        })
        .detach();
    }

    fn hold_fired(&mut self, action: HoldAction, window: &mut Window, cx: &mut Context<Self>) {
        match action {
            HoldAction::Plus => {
                if dial::long_press_zero(&mut self.dialed) {
                    rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
                    self.digit_pop.snap(1.18);
                    self.digit_pop.set_target(1.0);
                }
            }
            HoldAction::Digit(d) => self.speed_dial_hold(d, window, cx),
            HoldAction::Erase(step) => self.erase_step(step, window, cx),
            HoldAction::Diagnostics => {
                if self.sheet.is_open() && self.sheet_kind == crate::SheetKind::Setup {
                    rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
                    self.sheet_kind = crate::SheetKind::Diagnostics;
                    self.presses.release_all();
                }
            }
            HoldAction::SimChooser => self.sim_long_press(window, cx),
            HoldAction::SimNumber(i) => self.sim_long_press_number(usize::from(i), window, cx),
            HoldAction::SettingsDiagnostics => self.settings_diagnostics(window, cx),
            HoldAction::RouteList => self.route_long_press(window, cx),
            HoldAction::Reason(slot) => self.show_reason(usize::from(slot), window, cx),
        }
        cx.notify();
    }

    pub(crate) fn invalid(&mut self, window: &mut Window) {
        // A sideways kick on a centred spring reads as "no".
        if window.prefers_reduced_motion() {
            self.set_status("Not a valid number");
        } else {
            self.shake.kick(-1400.0);
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Reject);
    }

    pub(crate) fn set_status(&mut self, status: impl Into<SharedString>) {
        self.status = Some(Status::Text(status.into()));
        self.status_in.restart();
    }

    fn key_press(&mut self, key: char, window: &mut Window, cx: &mut Context<Self>) {
        match dial::push(&mut self.dialed, key) {
            Push::Accepted => {
                self.status = None;
                self.backend.update(cx, |b, cx| b.key_tone(key, cx));
                self.key_fx(key, window, cx);
                self.digit_pop.snap(1.18);
                self.digit_pop.set_target(1.0);
                self.delete_shown.set_target(1.0);
                rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                if key == '0' {
                    self.arm_hold(LONG_ZERO, HoldAction::Plus, window, cx);
                } else if let Some(d) = key
                    .to_digit(10)
                    .and_then(|d| u8::try_from(d).ok())
                    .filter(|d| *d >= 1 && self.dialed.len() == 1)
                {
                    // Speed dial only applies to the first digit of a number.
                    self.arm_hold(LONG_DIGIT, HoldAction::Digit(d), window, cx);
                } else {
                    self.hold_gen += 1;
                }
            }
            Push::Shake => self.invalid(window),
            Push::Ignored => {}
        }
    }

    /// S1: the ripple ring and the digit copy that drops into the field.
    /// Reduced motion keeps only the press scale.
    fn key_fx(&mut self, key: char, window: &Window, cx: &mut Context<Self>) {
        if window.prefers_reduced_motion() {
            return;
        }
        let Some(slot) = KEYS.iter().position(|k| k.digit.starts_with(key)) else {
            return;
        };
        let Some(bounds) = self.marks.get(A_KEY + slot) else {
            return;
        };
        // The new digit lands where the row's right end is now: the row is
        // centred, so growing by one glyph moves its end by half of it and
        // the glyph's centre by the other half.
        let landing = self
            .marks
            .get(A_LAST)
            .filter(|last| !self.dialed.is_empty() && last.size.width > px(0.5))
            .map(|last| gpui::point(last.right(), last.center().y))
            .or_else(|| self.marks.get(A_FIELD).map(|f| f.center()));
        let digit = KEYS[slot].digit;
        let size = dial::font_step(self.dialed.chars().count()).min(self.font.target);
        self.keyfx.update(cx, |fx, cx| {
            fx.key_down(bounds, digit, landing.map(|p| (p, size)), cx);
        });
    }

    /// The number field's font size now: 36 stepping down with length, and
    /// never wider than the screen.
    /// The pure layout's input for this frame.
    pub(crate) fn keypad_input(&self, window: &Window) -> rovsti_core::keypad_layout::KeypadInput {
        let size = window.viewport_size();
        let e = window.insets().effective();
        rovsti_core::keypad_layout::KeypadInput {
            viewport_w: f32::from(size.width),
            viewport_h: f32::from(size.height),
            insets: rovsti_core::keypad_layout::Insets {
                top: f32::from(e.top),
                right: f32::from(e.right),
                bottom: f32::from(e.bottom),
                left: f32::from(e.left),
            },
            text_len: self.dialed.chars().count(),
            header_h: HEADER_H,
            tab_bar_h: f32::from(rovsti_ui::tokens::metrics::TAB_BAR_HEIGHT) * 1.1,
            tab_bar_margin: f32::from(rovsti_ui::tokens::metrics::TAB_BAR_MARGIN),
        }
    }

    pub(crate) fn field_font(&self) -> f32 {
        self.kp_layout.number_fit.font
    }

    fn delete_press(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let last = self.dialed.chars().last();
        if dial::backspace(&mut self.dialed) {
            if let Some(digit) = last {
                self.begin_ghost(digit, window);
            }
            rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
            self.status = None;
            if self.dialed.is_empty() {
                self.delete_shown.set_target(0.0);
            } else {
                self.arm_hold(LONG_DELETE, HoldAction::Erase(0), window, cx);
            }
        }
    }

    /// One repeat of a held Delete: the last digit leaves as a ghost (S2),
    /// then the next repeat is armed, sooner each time, until the field is
    /// empty or the finger lifts (`released` bumps `hold_gen`).
    fn erase_step(&mut self, step: u32, window: &mut Window, cx: &mut Context<Self>) {
        let last = self.dialed.chars().last();
        if !dial::backspace(&mut self.dialed) {
            return;
        }
        if let Some(digit) = last {
            self.begin_ghost(digit, window);
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        self.status = None;
        if self.dialed.is_empty() {
            self.delete_shown.set_target(0.0);
        } else {
            let next = dial::erase_interval_ms(step);
            self.arm_hold(next, HoldAction::Erase(step.saturating_add(1)), window, cx);
        }
    }

    fn begin_ghost(&mut self, digit: char, window: &Window) {
        if window.prefers_reduced_motion() {
            return;
        }
        let measured = self
            .marks
            .get(A_LAST)
            .map(|b| f32::from(b.size.width) / self.digit_pop.value.max(0.5))
            .filter(|w| *w > 1.0);
        let width = measured.unwrap_or(self.font.value * 0.56);
        self.ghost.begin(digit, width);
    }

    fn paste_press(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let text = cx
            .read_from_clipboard()
            .and_then(|item| item.text())
            .unwrap_or_default();
        let pasted = dial::normalise_paste(&text);
        if pasted.is_empty() {
            self.set_status("Nothing to paste");
            self.invalid(window);
            return;
        }
        self.dialed = pasted.chars().take(dial::MAX_LEN).collect();
        self.status = None;
        self.digit_pop.snap(1.1);
        self.digit_pop.set_target(1.0);
        self.delete_shown.set_target(1.0);
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
    }

    fn call_press(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.call_press_on(None, window, cx);
    }

    /// Calls the typed number, on `account` when a SIM was chosen. With "ask
    /// every time" and no choice yet, the chooser opens first and the number
    /// stays in the field (moment S3 does not play yet).
    pub(crate) fn call_press_on(
        &mut self,
        account: Option<&str>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let raw = self.dialed.clone();
        if dial::validate(&raw).is_err() {
            self.invalid(window);
            return;
        }
        if account.is_none() && self.sim_needs_choice(&raw, cx) {
            self.open_sim_chooser(crate::sim_ui::ChooserFor::Dial, raw.into(), None, cx);
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        let placement = self
            .backend
            .update(cx, |backend, cx| backend.place_call_on(&raw, account, cx));
        match placement {
            Placement::Placed | Placement::Requesting => {
                // Not the default phone app: the system's in-call screen takes over.
                if self.backend.read(cx).is_default_dialer() {
                    self.set_status("Calling...");
                } else {
                    self.set_status("Placed through the system phone app");
                }
                self.flights.home = None;
                self.bloom_ring(window, cx);
            }
            Placement::Debounced => {}
            Placement::Blocked => self.status = Some(Status::NeedsPhone),
            Placement::Failed => self.set_status("The call could not be placed"),
            Placement::NoVoicemail => self.status = Some(Status::NoVoicemail),
        }
    }

    /// Whether the status line shows "Add to contacts" and "Message".
    fn add_offered(&self) -> bool {
        offer_add(
            &self.dialed,
            self.dial_match.query == self.dialed && self.dial_match.task.is_none(),
            self.dial_match.found.is_some(),
            self.status.is_none(),
        )
    }

    /// Whether the WhatsApp chip is offered: WhatsApp is connected, a number
    /// that validates is typed, and no status line is in the way.
    fn whatsapp_chip_shown(&self, cx: &Context<Self>) -> bool {
        self.status.is_none()
            && self.whatsapp.read(cx).is_connected()
            && dial::validate(&self.dialed).is_ok()
    }

    /// The WhatsApp chip: calls the typed number over WhatsApp.
    fn whatsapp_press(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let raw = self.dialed.clone();
        if dial::validate(&raw).is_err() {
            self.invalid(window);
            return;
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        self.place_whatsapp(&raw, cx);
    }

    fn whatsapp_chip(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let press = self.presses.value("wa-call").clamp(0.0, 1.0);
        let pill = rovsti_ui::components::glass_pill(theme, "WhatsApp")
            .text_color(call_colors(theme).answer)
            .opacity(1.0 - 0.3 * press);
        div()
            .id("wa-call")
            .role(Role::Button)
            .aria_label("Call over WhatsApp")
            .relative()
            .flex_none()
            .whitespace_nowrap()
            .child(pill)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, window, cx| this.mouse_down("wa-call", window, cx)),
            )
            .child(touch_surface(Self::touch_listener(
                cx,
                Some("wa-call".into()),
            )))
    }

    /// Calls voicemail and says how it went, like the Call button does.
    pub(crate) fn voicemail_press(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        match self
            .backend
            .update(cx, |backend, cx| backend.call_voicemail(cx))
        {
            Placement::Placed | Placement::Requesting => {
                if self.backend.read(cx).is_default_dialer() {
                    self.set_status("Calling voicemail...");
                } else {
                    self.set_status("Placed through the system phone app");
                }
                self.flights.home = None;
            }
            Placement::Debounced => {}
            Placement::Blocked => self.status = Some(Status::NeedsPhone),
            Placement::Failed => self.set_status("The call could not be placed"),
            Placement::NoVoicemail => self.status = Some(Status::NoVoicemail),
        }
    }

    /// S3: the answer-coloured ring blooms and the circle gets ready to
    /// become the End button. The call is already placed.
    fn bloom_ring(&mut self, window: &Window, cx: &mut Context<Self>) {
        if window.prefers_reduced_motion() || !self.backend.read(cx).is_default_dialer() {
            return;
        }
        if let Some(rect) = self.marks.get(A_CALL) {
            self.keyfx.update(cx, |fx, cx| fx.bloom(rect, cx));
        }
        self.begin_button_flight(ButtonFrom::Call, window);
    }

    // ---- Contact match (story 10.1) ----------------------------------------

    /// Once per render: if the typed number changed, looks it up in the
    /// contact index on the background executor. Nothing runs while the
    /// number is unchanged, so a frame costs one string comparison.
    pub(crate) fn sync_dial_match(&mut self, cx: &mut Context<Self>) {
        let load = self.backend.read(cx).contacts();
        let index_id = match &load {
            Load::Ready(index) => std::sync::Arc::as_ptr(index) as usize,
            _ => 0,
        };
        if self.dial_match.query == self.dialed && self.dial_match.index == index_id {
            return;
        }
        self.dial_match.query.clone_from(&self.dialed);
        self.dial_match.index = index_id;
        self.dial_match.task = None;
        let Load::Ready(index) = load else {
            self.dial_match.found = None;
            return;
        };
        if self.dialed.chars().count() < MATCH_MIN {
            self.dial_match.found = None;
            return;
        }
        let query = self.dialed.clone();
        self.dial_match.task = Some(cx.spawn(async move |this, cx| {
            let hit = cx
                .background_executor()
                .spawn(async move {
                    index
                        .match_number(&query)
                        .map(|c| (c.id, c.display_name.clone()))
                })
                .await;
            this.update(cx, |this, cx| {
                this.dial_match.task = None;
                let found = hit.map(|(id, name)| Found {
                    id,
                    monogram: rovsti_core::avatar::monogram(&name).map(|c| c.to_string().into()),
                    hue: rovsti_core::avatar::hue_index(Some(id)),
                    label: format!("Dialing {name}").into(),
                    name: name.into(),
                });
                if this.dial_match.found != found {
                    this.dial_match.found = found;
                    if this.status.is_none() {
                        this.status_in.restart();
                    }
                    cx.notify();
                }
            })
            .ok();
        }));
    }

    /// Once per render: if the typed digits or the contact index changed,
    /// asks the index for T9 suggestions on the background executor.
    pub(crate) fn sync_t9_match(&mut self, cx: &mut Context<Self>) {
        let load = self.backend.read(cx).contacts();
        let format = self.backend.read(cx).settings().name_format;
        let index_id = match &load {
            Load::Ready(index) => Arc::as_ptr(index) as usize,
            _ => 0,
        };
        let m = &mut self.t9;
        if m.query == self.dialed && m.index == index_id && m.format == format {
            return;
        }
        m.query.clone_from(&self.dialed);
        m.index = index_id;
        m.format = format;
        m.task = None;
        let (Load::Ready(index), Some(digits)) = (load, t9_digits(&self.dialed)) else {
            if !m.hits.is_empty() {
                m.hits.clear();
                m.hits_gen = m.hits_gen.wrapping_add(1);
            }
            if let Some(scratch) = &mut m.scratch {
                scratch.forget();
            }
            return;
        };
        let digits = digits.to_owned();
        let mut scratch = m.scratch.take().unwrap_or_default();
        scratch.set_format(format);
        m.task = Some(cx.spawn(async move |this, cx| {
            let (scratch, hits) = cx
                .background_executor()
                .spawn(async move {
                    let hits = index.t9(digits.as_bytes(), &mut scratch).to_vec();
                    (scratch, hits)
                })
                .await;
            this.update(cx, |this, cx| {
                this.t9.task = None;
                this.t9.scratch = Some(scratch);
                if this.t9.hits != hits {
                    this.t9.hits = hits;
                    this.t9.hits_gen = this.t9.hits_gen.wrapping_add(1);
                    cx.notify();
                }
            })
            .ok();
        }));
    }

    /// Once per render, after [`Self::sync_t9_match`] and
    /// [`Self::sync_dial_match`]: resolves the hits into strip rows when what
    /// they depend on changed. An unchanged frame costs one comparison (this
    /// used to scan the whole book for the exact match and build every row's
    /// name and labels on every frame the strip showed).
    pub(crate) fn sync_t9_rows(&mut self, cx: &mut Context<Self>) {
        if self.sheet_kind == crate::SheetKind::T9Results && self.sheet.is_open() {
            if t9_digits(&self.dialed).is_none() {
                self.close_sheet(cx);
            } else {
                self.t9_all_rows(cx);
            }
        }
        let Load::Ready(index) = self.backend.read(cx).contacts() else {
            if self.t9.rows.key.is_some() {
                self.t9.rows = T9Rows::default();
            }
            return;
        };
        let key = RowsKey {
            hits_gen: self.t9.hits_gen,
            index: Arc::as_ptr(&index) as usize,
            format: self.t9.format,
            exact: self.dial_match.found.as_ref().map(|found| found.id),
            cap: self.kp_layout.match_rows,
        };
        if self.t9.rows.key == Some(key) {
            return;
        }
        let format = self.t9.format;
        let exact = key
            .exact
            .and_then(|id| index.all().position(|c| c.id == id))
            .and_then(|i| i64::try_from(i).ok());
        let (shown, more) = visible_suggestions(&self.t9.hits, exact, key.cap);
        let shown = shown
            .iter()
            .filter_map(|hit| suggestion_of(&index, hit, format))
            .collect();
        let (more_text, more_aria) = if more > 0 {
            (
                format!("{more} more").into(),
                format!("{more} more contacts").into(),
            )
        } else {
            Default::default()
        };
        self.t9.rows = T9Rows {
            key: Some(key),
            shown,
            more,
            more_text,
            more_aria,
        };
    }

    /// The rows to draw above the field, empty when the strip is hidden, with
    /// how many more hits there are.
    fn t9_rows(&self) -> (&[Suggestion], usize) {
        if t9_digits(&self.dialed).is_none() || self.t9.query != self.dialed {
            return (&[], 0);
        }
        (&self.t9.rows.shown, self.t9.rows.more)
    }

    /// A tap on suggestion `slot`: calls its number.
    pub(crate) fn t9_tap(&mut self, slot: usize, window: &mut Window, cx: &mut Context<Self>) {
        let (rows, _) = self.t9_rows();
        if let Some(row) = rows.get(slot) {
            let number = row.number.clone();
            self.place_call_from(&number, window, cx);
        }
    }

    /// Resolves every hit (at most [`t9::MAX_HITS`]) for the Matches sheet
    /// when the hits, the index or the name format changed since last time.
    fn t9_all_rows(&mut self, cx: &Context<Self>) {
        let Load::Ready(index) = self.backend.read(cx).contacts() else {
            self.t9.all = T9All::default();
            return;
        };
        let key = AllKey {
            hits_gen: self.t9.hits_gen,
            index: Arc::as_ptr(&index) as usize,
            format: self.t9.format,
        };
        if self.t9.all.key == Some(key) {
            return;
        }
        let rows: Arc<[Suggestion]> = self
            .t9
            .hits
            .iter()
            .take(rovsti_core::t9::MAX_HITS)
            .filter_map(|hit| suggestion_of(&index, hit, key.format))
            .collect();
        self.t9.all = T9All {
            key: Some(key),
            rows,
        };
    }

    /// A tap on "N more": the Matches sheet with every hit.
    pub(crate) fn t9_more(&mut self, cx: &mut Context<Self>) {
        if !self.can_open_sheet() {
            return;
        }
        self.t9_all_rows(cx);
        self.sheet_kind = crate::SheetKind::T9Results;
        self.sheet.open();
        cx.notify();
    }

    /// A tap (finger-up; the mouse button on desktop) on a Matches sheet id.
    fn t9_result_act(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        self.presses.down(id);
        if let Some((index, call)) = t9_result_index(id) {
            self.t9_result_tap(index, call, window, cx);
        }
    }

    /// A tap on row `index` of the Matches sheet: its number fills the field
    /// (`call` false) or is called (`call` true); the sheet closes either way.
    pub(crate) fn t9_result_tap(
        &mut self,
        index: usize,
        call: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(number) = self.t9.all.rows.get(index).map(|row| row.number.clone()) else {
            return;
        };
        self.close_sheet(cx);
        if call {
            self.place_call_from(&number, window, cx);
            return;
        }
        self.dialed = dial::normalise_paste(&number)
            .chars()
            .take(dial::MAX_LEN)
            .collect();
        self.status = None;
        self.digit_pop.snap(1.1);
        self.digit_pop.set_target(1.0);
        self.delete_shown.set_target(1.0);
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        cx.notify();
    }

    /// The Matches sheet body: a header and every hit in a scrolling column.
    pub(crate) fn t9_results_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
        let answer = call_colors(theme).answer;
        let rows = &self.t9.all.rows;
        let mut list = div()
            .id("sheet-t9-list")
            .flex()
            .flex_col()
            .max_h(px(T9_RESULT_H * 6.5))
            .overflow_y_scroll();
        for (i, row) in rows.iter().enumerate() {
            let row_id: SharedString = format!("sheet-t9-{i}").into();
            let call_id: SharedString = format!("sheet-t9c-{i}").into();
            let press = self.presses.value(&row_id).clamp(0.0, 1.0);
            let call_press = self.presses.value(&call_id).clamp(0.0, 1.0);
            let highlights: Vec<_> = row
                .span
                .clone()
                .into_iter()
                .map(|r| {
                    (
                        r,
                        HighlightStyle {
                            font_weight: Some(FontWeight::BOLD),
                            color: Some(colors.accent),
                            ..Default::default()
                        },
                    )
                })
                .collect();
            let circle = div()
                .id(call_id.clone())
                .role(Role::Button)
                .aria_label(row.label.clone())
                .relative()
                .flex_none()
                .size(px(36.0 * press_scale(call_press, scale::PRESS)))
                .rounded_full()
                .bg(mix(answer, gpui::white(), 0.18 * call_press))
                .flex()
                .items_center()
                .justify_center()
                .child(
                    svg()
                        .path(Icon::LucidePhone.path())
                        .size(px(18.0))
                        .text_color(gpui::white()),
                )
                .on_mouse_down(MouseButton::Left, {
                    let id = call_id.clone();
                    cx.listener(move |this, _, window, cx| this.t9_result_act(&id, window, cx))
                })
                .child(touch_surface(Self::touch_listener(
                    cx,
                    Some(call_id.clone()),
                )));
            list = list.child(
                div()
                    .id(row_id.clone())
                    .role(Role::Button)
                    .aria_label(format!("Use {}", row.number))
                    .relative()
                    .h(px(T9_RESULT_H))
                    .px(px(18.0))
                    .opacity(1.0 - 0.3 * press)
                    .flex()
                    .items_center()
                    .gap(px(12.0))
                    .child(person_avatar(
                        theme,
                        row.hue,
                        row.monogram.as_ref(),
                        36.0,
                        None,
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
                                    .text_size(typography::BODY.size)
                                    .text_color(colors.label)
                                    .child(
                                        StyledText::new(row.name.clone())
                                            .with_highlights(highlights),
                                    ),
                            )
                            .child(
                                div()
                                    .truncate()
                                    .text_size(typography::FOOTNOTE.size)
                                    .text_color(colors.secondary_label)
                                    .child(row.number.clone()),
                            ),
                    )
                    .on_mouse_down(MouseButton::Left, {
                        let id = row_id.clone();
                        cx.listener(move |this, _, window, cx| this.t9_result_act(&id, window, cx))
                    })
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some(row_id.clone()),
                    )))
                    .child(circle),
            );
        }
        let body = div()
            .flex()
            .flex_col()
            .pb(px(18.0))
            .child(
                div()
                    .px(px(18.0))
                    .pb(px(8.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(results_title(&self.dialed, rows.len())),
            )
            .child(list)
            .into_any_element();
        self.modal_sheet(theme, window, "Matches", body, cx)
    }

    fn t9_strip(
        &self,
        theme: Theme,
        layout: &rovsti_core::keypad_layout::KeypadLayout,
        cx: &mut Context<Self>,
    ) -> Option<impl IntoElement> {
        let (rows, more) = self.t9_rows();
        if rows.is_empty() {
            return None;
        }
        let colors = theme::colors(theme);
        const IDS: [&str; T9_SHOWN] = ["t9-0", "t9-1", "t9-2"];
        let mut strip = div()
            .flex()
            .flex_col()
            .gap(px(T9_GAP))
            .w(px(layout.matches.w.min(360.0)));
        let last = rows.len().saturating_sub(1);
        for (slot, row) in rows.iter().enumerate() {
            let id = IDS[slot];
            let press = self.presses.value(id).clamp(0.0, 1.0);
            let highlights: Vec<_> = row
                .span
                .clone()
                .into_iter()
                .map(|r| {
                    (
                        r,
                        HighlightStyle {
                            font_weight: Some(FontWeight::BOLD),
                            color: Some(colors.accent),
                            ..Default::default()
                        },
                    )
                })
                .collect();
            let pill = (layout.more_inline && more > 0 && slot == last).then(|| {
                let press = self.presses.value("t9-more").clamp(0.0, 1.0);
                glass(GlassSpec::control(theme))
                    .id("t9-more")
                    .role(Role::Button)
                    .aria_label(self.t9.rows.more_aria.clone())
                    .relative()
                    .flex_none()
                    .h(px(30.0))
                    .px(px(10.0))
                    .rounded(radius::CARD)
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(typography::FOOTNOTE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .opacity(1.0 - 0.5 * press)
                    .child(SharedString::from(format!("+{more}")))
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, _, cx| {
                            cx.stop_propagation();
                            this.t9_more(cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some("t9-more".into()),
                    )))
            });
            strip = strip.child(
                glass(GlassSpec::control(theme))
                    .id(id)
                    .role(Role::Button)
                    .aria_label(row.label.clone())
                    .relative()
                    .h(px(T9_ROW_H))
                    .px(px(12.0))
                    .rounded(radius::CARD)
                    .shadow(shadow::glass(theme.is_dark()))
                    .opacity(1.0 - 0.3 * press)
                    .flex()
                    .items_center()
                    .gap(px(10.0))
                    .child(person_avatar(
                        theme,
                        row.hue,
                        row.monogram.as_ref(),
                        30.0,
                        None,
                    ))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .truncate()
                            .text_size(typography::SUBHEADLINE.size)
                            .text_color(colors.label)
                            .child(StyledText::new(row.name.clone()).with_highlights(highlights)),
                    )
                    .child(
                        div()
                            .flex_none()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(row.number.clone()),
                    )
                    .children(pill)
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, window, cx| this.t9_tap(slot, window, cx)),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some(id.into())))),
            );
        }
        if more > 0 && !layout.more_inline {
            let press = self.presses.value("t9-more").clamp(0.0, 1.0);
            strip = strip.child(
                div()
                    .id("t9-more")
                    .role(Role::Button)
                    .aria_label(self.t9.rows.more_aria.clone())
                    .relative()
                    .h(px(36.0))
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(typography::FOOTNOTE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .opacity(1.0 - 0.5 * press)
                    .child(self.t9.rows.more_text.clone())
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, _, cx| this.t9_more(cx)),
                    )
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some("t9-more".into()),
                    ))),
            );
        }
        Some(strip)
    }

    // ---- Rendering --------------------------------------------------------

    /// One glass key in a fixed slot, so neighbours never move while it shrinks.
    pub(crate) fn dial_key(
        &self,
        theme: Theme,
        key: &Key,
        size: f32,
        in_call: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = if in_call { key.in_id } else { key.id };
        let press = self.presses.value(id);
        let side = size * press_scale(press, scale::PRESS_CARD);
        // A key at rest is painted by the pad's one shared glass effect
        // (`dial_pad`); only a key that is moving paints its own.
        let at_rest = press == 0.0;
        let spec =
            GlassSpec::control(theme).lerp(GlassSpec::lens(theme), press.clamp(0.0, 1.0) * 0.9);
        let style = rovsti_core::keypad_layout::key_style(size);
        let glyph = style.glyph;
        let caption = div()
            .h(px(style.caption_h))
            .text_size(typography::CAPTION2.size)
            .font_weight(typography::CAPTION2.weight)
            .text_color(colors.secondary_label)
            .when(
                style.letters && !in_call && key.hold_label.is_some(),
                |el| {
                    el.flex().items_center().justify_center().child(
                        svg()
                            .path(Icon::LucideVoicemail.path())
                            .size(px(12.0))
                            .flex_none()
                            .text_color(colors.secondary_label),
                    )
                },
            )
            .when(style.letters, |el| el.child(key.letters));
        let badge = self.key_badge(key, in_call);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(match (&badge, key.hold_label) {
                (Some((_, aria)), _) => aria.clone(),
                (None, Some(hold)) if !in_call => SharedString::from(hold),
                _ => SharedString::from(key.label),
            })
            .size(px(size))
            .flex()
            .items_center()
            .justify_center()
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| this.mouse_down(id, window, cx)),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
            .children((!in_call).then(|| {
                let slot = KEYS.iter().position(|k| k.digit == key.digit).unwrap_or(0);
                self.marks.probe(A_KEY + slot)
            }))
            .child(
                (if at_rest {
                    div()
                } else {
                    glass_interactive(spec, move || GlassInteraction { press, focus: None })
                })
                .size(px(side))
                .rounded_full()
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .child(
                    div()
                        .text_size(px(glyph * side / size))
                        .text_color(colors.label)
                        .child(key.digit),
                )
                .child(caption),
            )
            .children(badge.map(|(hue, _)| {
                div()
                    .absolute()
                    .top(px((size - side) / 2.0 + 2.0))
                    .right(px((size - side) / 2.0 + 2.0))
                    .size(px(14.0))
                    .rounded_full()
                    .bg(theme::hue_pair(theme, hue).0)
                    .border(px(1.5))
                    .border_color(colors.background)
            }))
            .into_any_element()
    }

    /// The speed-dial dot (hue) and spoken label for a Keypad digit key.
    fn key_badge(&self, key: &Key, in_call: bool) -> Option<(Option<usize>, SharedString)> {
        if in_call {
            return None;
        }
        let d = key
            .digit
            .parse::<usize>()
            .ok()
            .filter(|d| (2..=9).contains(d))?;
        let (_, hue) = self.speed_badges[d - 1].as_ref()?;
        let aria = self.speed_aria[d - 1].clone()?;
        Some((*hue, aria))
    }

    /// Rebuild the key badges when the speed-dial map changed (not per frame).
    pub(crate) fn sync_speed_badges(&mut self, cx: &mut Context<Self>) {
        let backend = self.backend.read(cx);
        let map = &backend.prefs().speed_dial;
        if *map == self.speed_map {
            return;
        }
        let map = map.clone();
        self.speed_badges = speed_badges(&map);
        self.speed_aria = speed_aria_labels(&map);
        self.speed_map = map;
        cx.notify();
    }

    /// The twelve keys as one grid. Keys at rest share ONE backdrop effect
    /// (one copy of the backdrop, one blur, one composite; AD-21 4), instead
    /// of twelve; a key that is pressed or springing back is left out of it
    /// and paints its own glass with the press glow. `gap` is the space
    /// between keys in a row; rows sit `row_gap` apart.
    pub(crate) fn dial_pad(
        &self,
        theme: Theme,
        size: f32,
        gap: f32,
        row_gap: f32,
        in_call: bool,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let mut skip = 0u32;
        for (index, key) in KEYS.iter().enumerate() {
            let id = if in_call { key.in_id } else { key.id };
            if self.presses.value(id) != 0.0 {
                skip |= 1 << index;
            }
        }
        let grid = move |origin: gpui::Point<gpui::Pixels>| GlassGrid {
            origin,
            cell: gpui::size(px(size), px(size)),
            pitch: gpui::size(px(size + gap), px(size + row_gap)),
            cols: 3,
            rows: 4,
        };
        let spec = GlassSpec::control(theme);
        let mut rows = Vec::with_capacity(4);
        for row in KEYS.chunks(3) {
            let mut keys = Vec::with_capacity(3);
            for key in row {
                keys.push(self.dial_key(theme, key, size, in_call, cx));
            }
            rows.push(div().flex().gap(px(gap)).children(keys));
        }
        div()
            .flex()
            .flex_col()
            .flex_none()
            .gap(px(row_gap))
            .w(px(size * 3.0 + gap * 2.0))
            .h(px(size * 4.0 + row_gap * 3.0))
            .on_paint_before_children(move |bounds, _style, window, _cx| {
                paint_glass_grid(
                    &grid(bounds.origin),
                    gpui::Corners::all(px(size / 2.0)),
                    &spec,
                    skip,
                    window,
                );
            })
            .children(rows)
    }

    pub(crate) fn number_display(&self, theme: Theme) -> impl IntoElement {
        let colors = theme::colors(theme);
        let (_, _, head, last) = &self.shown_digits;
        let font = self.font.value;
        let ghost = self.ghost.digit.map(|digit| {
            let t = self.ghost.t.value.clamp(0.0, 1.0);
            div()
                .w(px(self.ghost.width * (1.0 - t)))
                .flex()
                .flex_none()
                .items_center()
                .justify_center()
                .opacity(1.0 - t)
                .child(
                    div()
                        .flex_none()
                        .text_size(px(font * (1.0 - 0.4 * t)))
                        .child(SharedString::from(digit.to_string())),
                )
        });
        div()
            .id("number")
            .role(Role::Group)
            .aria_label(SharedString::from(self.dialed.clone()))
            .relative()
            .left(px(self.shake.value * 0.02))
            .h(px(52.0))
            .w_full()
            .px(px(24.0))
            .flex()
            .items_center()
            .justify_center()
            .whitespace_nowrap()
            .overflow_hidden()
            .text_color(colors.label)
            .font_weight(gpui::FontWeight(300.0))
            .child(self.marks.probe(A_FIELD))
            .child(div().flex_none().text_size(px(font)).child(head.clone()))
            .child(
                div()
                    .flex_none()
                    .relative()
                    .text_size(px(font * self.digit_pop.value))
                    .child(last.clone())
                    .child(self.marks.probe(A_LAST)),
            )
            .children(ghost)
    }

    fn paste_pill(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme::colors(theme);
        let press = self.presses.value("paste");
        let shown = 1.0 - self.delete_shown.value.clamp(0.0, 1.0);
        let pill = rovsti_ui::components::glass_pill(theme, "Paste")
            .text_color(colors.accent)
            .opacity(shown * (1.0 - 0.3 * press.clamp(0.0, 1.0)));
        div()
            .id("paste")
            .role(Role::Button)
            .aria_label("Paste a number")
            .flex_none()
            .child(pill)
            .when(self.dialed.is_empty(), |el| {
                el.on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, window, cx| this.mouse_down("paste", window, cx)),
                )
                .child(touch_surface(Self::touch_listener(
                    cx,
                    Some("paste".into()),
                )))
            })
    }

    fn status_line(
        &self,
        theme: Theme,
        layout: &rovsti_core::keypad_layout::KeypadLayout,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme::colors(theme);
        let name_max = (layout.actions.w - 32.0).max(0.0);
        // Content is centred while it fits: the two empty spacers grow to
        // share the free space and shrink to nothing once it overflows, and
        // then the row scrolls sideways.
        let spacer = || div().flex_1().min_w_0();
        let area = div()
            .h(px(layout.actions.h))
            .w(px(layout.actions.w))
            .flex()
            .items_center()
            .id("status")
            .overflow_x_scroll()
            .child(spacer())
            .gap(px(8.0))
            .opacity(self.status_in.progress())
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.secondary_label)
            .role(Role::Status);
        match &self.status {
            Some(Status::Text(text)) => area
                .aria_label(text.clone())
                .child(div().flex_none().whitespace_nowrap().child(text.clone())),
            Some(Status::NeedsPhone) => {
                let press = self.presses.value("grant");
                // The sentence gives way (ellipsis) so "Grant access" always fits on screen.
                area.max_w_full()
                    .px(px(16.0))
                    .child(
                        div()
                            .min_w_0()
                            .truncate()
                            .child("Allow Phone access to place calls."),
                    )
                    .child(
                        div()
                            .id("grant")
                            .role(Role::Button)
                            .aria_label("Grant access")
                            .flex_none()
                            .relative()
                            .h(px(36.0))
                            .flex()
                            .items_center()
                            .px(px(8.0))
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.accent)
                            .opacity(1.0 - 0.5 * press.clamp(0.0, 1.0))
                            .child("Grant access")
                            .on_mouse_down(
                                MouseButton::Left,
                                cx.listener(|this, _, window, cx| {
                                    this.mouse_down("grant", window, cx)
                                }),
                            )
                            .child(touch_surface(Self::touch_listener(
                                cx,
                                Some("grant".into()),
                            ))),
                    )
            }
            Some(Status::NoVoicemail) => area
                .aria_label("No voicemail number set")
                .child(
                    div()
                        .flex_none()
                        .whitespace_nowrap()
                        .child("No voicemail number set"),
                )
                .child(self.status_button(theme, "kp-vm-set", "Open call settings", cx)),
            // The status line is 36 px tall whether or not it speaks, so a
            // name appearing never moves the keys.
            None if contacts_hint_shown(
                matches!(self.backend.read(cx).contacts(), Load::PermissionDenied),
                !self.dialed.is_empty(),
                self.t9_hint_dismissed,
            ) =>
            {
                let wa = self.whatsapp_chip_shown(cx);
                area.aria_label("Allow Contacts to see matches")
                    .child(
                        div()
                            .flex_none()
                            .whitespace_nowrap()
                            .child("Allow Contacts to see matches"),
                    )
                    .child(self.status_button(theme, "kp-ct-grant", "Grant access", cx))
                    .when(wa, |el| el.child(self.whatsapp_chip(theme, cx)))
            }
            None => match &self.dial_match.found {
                Some(found) => {
                    let press = self.presses.value("kp-name").clamp(0.0, 1.0);
                    let wa = self.whatsapp_chip_shown(cx);
                    area.child(
                        div()
                            .id("kp-name")
                            .role(Role::Button)
                            .aria_label(SharedString::from(format!("{}, open contact", found.name)))
                            .relative()
                            .flex_none()
                            .h(px(36.0))
                            .flex()
                            .items_center()
                            .gap(px(8.0))
                            .opacity(1.0 - 0.5 * press)
                            .child(person_avatar(
                                theme,
                                found.hue,
                                found.monogram.as_ref(),
                                22.0,
                                None,
                            ))
                            .child(
                                div()
                                    .max_w(px(name_max))
                                    .truncate()
                                    .text_size(typography::SUBHEADLINE.size)
                                    .font_weight(typography::HEADLINE.weight)
                                    .text_color(colors.label)
                                    .child(found.name.clone()),
                            )
                            .on_mouse_down(
                                MouseButton::Left,
                                cx.listener(|this, _, window, cx| {
                                    this.mouse_down("kp-name", window, cx)
                                }),
                            )
                            .child(touch_surface(Self::touch_listener(
                                cx,
                                Some("kp-name".into()),
                            ))),
                    )
                    .when(wa, |el| el.child(self.whatsapp_chip(theme, cx)))
                }
                None if self.add_offered() => {
                    let wa = self.whatsapp_chip_shown(cx);
                    area.child(self.status_button(theme, "kp-add", "Add to contacts", cx))
                        .child(self.status_button(theme, "kp-msg", "Message", cx))
                        .when(wa, |el| el.child(self.whatsapp_chip(theme, cx)))
                }
                None if self.whatsapp_chip_shown(cx) => area.child(self.whatsapp_chip(theme, cx)),
                None => area,
            },
        }
        .child(spacer())
    }

    /// A text button in the status line; acts on finger-up (the mouse press
    /// that follows it), with raw touch feedback while held.
    fn status_button(
        &self,
        theme: Theme,
        id: &'static str,
        label: &'static str,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(id);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .relative()
            .flex_none()
            .whitespace_nowrap()
            .h(px(36.0))
            .flex()
            .items_center()
            .px(px(8.0))
            .font_weight(typography::HEADLINE.weight)
            .text_color(colors.accent)
            .opacity(1.0 - 0.5 * press.clamp(0.0, 1.0))
            .child(label)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| this.mouse_down(id, window, cx)),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
    }

    pub(crate) fn keypad(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let answer = call_colors(theme).answer;
        let layout = &self.kp_layout;
        let key = layout.key;

        // While the circle waits for the call screen it stays pressed; once it
        // flies, the copy drawn over everything replaces it.
        let flying = self.flights.button_flying(ButtonFrom::Call);
        let call_press = if self.flights.holds_call() {
            1.0
        } else {
            self.presses.value("call")
        };
        let call_side = key * press_scale(call_press, scale::PRESS_CARD);
        let call_icon = (key * 0.38).clamp(20.0, 30.0);
        let call_bg = mix(answer, gpui::white(), 0.18 * call_press.clamp(0.0, 1.0));
        let delete_press = self.presses.value("delete");
        let shown = self.delete_shown.value.clamp(0.0, 1.0);

        // Every section is a clipped box at a rect from the pure layout, so
        // content can never spill into a neighbour.
        let section = |r: rovsti_core::keypad_layout::Rect| {
            div()
                .absolute()
                .left(px(r.x))
                .top(px(r.y))
                .w(px(r.w))
                .h(px(r.h))
                .overflow_hidden()
        };

        let number = section(layout.number).flex().justify_center().child(
            div()
                .relative()
                .w_full()
                .flex()
                .justify_center()
                .child(self.number_display(theme))
                .when(self.dialed.is_empty(), |el| {
                    el.child(self.paste_pill(theme, cx))
                }),
        );
        let matches = section(layout.matches)
            .flex()
            .justify_center()
            .items_end()
            .children(self.t9_strip(theme, layout, cx));
        let actions = section(layout.actions)
            .flex()
            .justify_center()
            .child(self.status_line(theme, layout, cx));
        let pad = section(layout.grid).child(self.dial_pad(
            theme,
            key,
            layout.col_gap,
            layout.row_gap,
            false,
            cx,
        ));
        let call_row = section(layout.call_row)
            .flex()
            .items_center()
            .justify_center()
            .gap(px(layout.col_gap))
            .child(div().size(px(key)).flex_none())
            .child(
                div()
                    .id("call")
                    .role(Role::Button)
                    .aria_label("Call")
                    .relative()
                    .size(px(key))
                    .flex()
                    .items_center()
                    .justify_center()
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, window, cx| this.call_tap(window, cx)),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some("call".into()))))
                    .child(self.marks.probe(A_CALL))
                    .child(
                        div()
                            .size(px(call_side))
                            .opacity(1.0 - f32::from(u8::from(flying)))
                            .rounded_full()
                            .bg(call_bg)
                            .flex()
                            .items_center()
                            .justify_center()
                            .child(
                                svg()
                                    .path(Icon::LucidePhone.path())
                                    .size(px(call_icon))
                                    .text_color(gpui::white()),
                            ),
                    ),
            )
            .child(
                div()
                    .id("backspace")
                    .role(Role::Button)
                    .aria_label("Delete")
                    .size(px(key))
                    .flex()
                    .items_center()
                    .justify_center()
                    .opacity(shown * (1.0 - 0.5 * delete_press.clamp(0.0, 1.0)))
                    .left(px((1.0 - shown) * 12.0))
                    .text_size(typography::HEADLINE.size)
                    .text_color(theme::colors(theme).secondary_label)
                    .child("Delete")
                    .when(!self.dialed.is_empty(), |el| {
                        el.on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, _, window, cx| {
                                this.mouse_down("delete", window, cx)
                            }),
                        )
                        .child(touch_surface(Self::touch_listener(
                            cx,
                            Some("delete".into()),
                        )))
                    }),
            );

        div()
            .size_full()
            .relative()
            .child(number)
            .child(matches)
            .child(actions)
            .child(pad)
            .child(call_row)
    }
}

type Badges = [Option<(SharedString, Option<usize>)>; 9];

fn speed_badges(
    map: &std::collections::BTreeMap<u8, rovsti_core::settings::SpeedDialEntry>,
) -> Badges {
    rovsti_core::speed_dial::badges(map).map(|b| b.map(|(n, h)| (SharedString::from(n), h)))
}

/// Spoken label per digit key (index `d - 1`); `None` for free digits.
fn speed_aria_labels(
    map: &std::collections::BTreeMap<u8, rovsti_core::settings::SpeedDialEntry>,
) -> [Option<SharedString>; 9] {
    let badges = rovsti_core::speed_dial::badges(map);
    std::array::from_fn(|i| {
        let digit = (i + 1).to_string();
        let name = badges[i].as_ref().map(|(n, _)| n.as_str());
        speed_dial_aria(&digit, name).map(SharedString::from)
    })
}

/// Screen-reader label for a digit key, naming its speed dial when assigned.
pub(crate) fn speed_dial_aria(digit: &str, name: Option<&str>) -> Option<String> {
    let name = name.map(str::trim).filter(|n| !n.is_empty())?;
    Some(format!("{digit}, speed dial {name}. Long press to call"))
}

/// Split `dialed` into the text before the newest digit and the newest
/// digit. With `keep = Some(n)` the head is a leading `…` plus the `n - 1`
/// chars before the last, so the end of the number stays visible.
pub(crate) fn elided_head(dialed: &str, keep: Option<usize>) -> (String, String) {
    let split = dialed.char_indices().last().map_or(0, |(i, _)| i);
    let (head, last) = dialed.split_at(split);
    let Some(n) = keep else {
        return (head.to_owned(), last.to_owned());
    };
    let before = n.saturating_sub(1);
    let total = head.chars().count();
    if total <= before {
        return (head.to_owned(), last.to_owned());
    }
    let tail: String = head.chars().skip(total - before).collect();
    (format!("\u{2026}{tail}"), last.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn elided_head_without_elision_splits_off_the_last_char() {
        assert_eq!(elided_head("0555", None), ("055".into(), "5".into()));
        assert_eq!(elided_head("", None), (String::new(), String::new()));
        assert_eq!(elided_head("12", Some(5)), ("1".into(), "2".into()));
    }

    #[test]
    fn elided_head_keeps_the_last_n_chars() {
        let (head, last) = elided_head("0123456789", Some(4));
        assert_eq!((head.as_str(), last.as_str()), ("\u{2026}678", "9"));
        assert_eq!(format!("{head}{last}"), "\u{2026}6789");
        assert_eq!(
            elided_head("0123456789", Some(1)),
            ("\u{2026}".into(), "9".into())
        );
        assert_eq!(
            elided_head("0123456789", Some(0)),
            ("\u{2026}".into(), "9".into())
        );
    }

    #[test]
    fn elided_head_handles_dial_symbols() {
        assert_eq!(
            elided_head("+905551234567", Some(3)),
            ("\u{2026}56".into(), "7".into())
        );
        assert_eq!(
            elided_head("*#06#12*#", Some(3)),
            ("\u{2026}2*".into(), "#".into())
        );
        assert_eq!(elided_head("+9055", Some(5)), ("+905".into(), "5".into()));
    }

    #[test]
    fn aria_labels_follow_the_map() {
        let mut map = std::collections::BTreeMap::new();
        map.insert(
            5,
            rovsti_core::settings::SpeedDialEntry {
                lookup_key: String::new(),
                number_raw: "0555".into(),
                name: "Ayşe".into(),
            },
        );
        let aria = speed_aria_labels(&map);
        assert_eq!(
            aria[4].as_deref(),
            Some("5, speed dial Ayşe. Long press to call")
        );
        assert_eq!(aria.iter().flatten().count(), 1);
        assert_eq!(speed_badges(&map).iter().flatten().count(), 1);
    }

    #[test]
    fn speed_dial_aria_names_assigned_digits_only() {
        assert_eq!(
            speed_dial_aria("5", Some("Ayşe")).as_deref(),
            Some("5, speed dial Ayşe. Long press to call")
        );
        assert_eq!(speed_dial_aria("5", Some("  ")), None);
        assert_eq!(speed_dial_aria("5", None), None);
    }

    #[test]
    fn suggestion_of_resolves_hits_and_rejects_stale_ones() {
        let index = rovsti_core::search::ContactIndex::new(crate::sample::long_contacts(5));
        let hit = T9Hit {
            contact_idx: 0,
            number_idx: 0,
            name_span: None,
            rank: 3,
        };
        let format = rovsti_core::wire::NameFormat::default();
        let row = suggestion_of(&index, &hit, format).expect("first contact resolves");
        assert!(row.label.starts_with("Call "));
        assert!(row.label.ends_with(&*row.number));
        let stale = T9Hit {
            contact_idx: 999,
            ..hit
        };
        assert!(suggestion_of(&index, &stale, format).is_none());
        let no_number = T9Hit {
            number_idx: 999,
            ..hit
        };
        assert!(suggestion_of(&index, &no_number, format).is_none());
    }

    #[test]
    fn results_title_pluralises() {
        assert_eq!(results_title("52", 1), "1 match for 52");
        assert_eq!(results_title("52", 0), "0 matches for 52");
        assert_eq!(results_title("52", 3), "3 matches for 52");
    }

    #[test]
    fn contacts_hint_needs_denied_digits_and_not_dismissed() {
        assert!(contacts_hint_shown(true, true, false));
        assert!(!contacts_hint_shown(false, true, false));
        assert!(!contacts_hint_shown(true, false, false));
        assert!(!contacts_hint_shown(true, true, true));
    }

    #[test]
    fn t9_result_ids_parse() {
        assert_eq!(t9_result_index("sheet-t9-3"), Some((3, false)));
        assert_eq!(t9_result_index("sheet-t9c-12"), Some((12, true)));
        assert_eq!(t9_result_index("sheet-t9-x"), None);
        assert_eq!(t9_result_index("sheet-t9c-"), None);
        assert_eq!(t9_result_index("other-1"), None);
    }

    /// The static table must agree with the spoken-label rule of UX-DR22.
    fn expected_label(digit: &str, letters: &str) -> String {
        match digit {
            "*" => "star".to_owned(),
            "#" => "pound".to_owned(),
            "0" => "0 plus".to_owned(),
            _ if letters.is_empty() => digit.to_owned(),
            _ => format!(
                "{digit} {}",
                letters
                    .chars()
                    .map(String::from)
                    .collect::<Vec<_>>()
                    .join(" ")
            ),
        }
    }

    fn hit(contact_idx: u32) -> T9Hit {
        T9Hit {
            contact_idx,
            number_idx: 0,
            name_span: None,
            rank: 1,
        }
    }

    fn idxs(hits: &[T9Hit]) -> Vec<u32> {
        hits.iter().map(|h| h.contact_idx).collect()
    }

    #[test]
    fn add_is_offered_only_for_a_settled_unknown_valid_number() {
        assert!(offer_add("05321234567", true, false, true));
        assert!(!offer_add("05321234567", false, false, true));
        assert!(!offer_add("05321234567", true, true, true));
        assert!(!offer_add("05321234567", true, false, false));
        assert!(!offer_add("", true, false, true));
    }

    #[test]
    fn suggestions_cap_at_three_and_count_the_rest() {
        let hits: Vec<_> = (0..7).map(hit).collect();
        let (shown, more) = visible_suggestions(&hits, None, 3);
        assert_eq!(idxs(&shown), [0, 1, 2]);
        assert_eq!(more, 4);
    }

    #[test]
    fn suggestions_with_a_cap_of_one_count_the_hidden_hits() {
        let hits: Vec<_> = (0..5).map(hit).collect();
        let (shown, more) = visible_suggestions(&hits, Some(0), 1);
        assert_eq!(idxs(&shown), [1]);
        assert_eq!(more, 3);
        // A cap above the strip's maximum is clamped.
        let (shown, more) = visible_suggestions(&hits, None, 9);
        assert_eq!((shown.len(), more), (3, 2));
    }

    #[test]
    fn suggestions_drop_the_exact_contact_before_capping() {
        let hits: Vec<_> = (0..5).map(hit).collect();
        let (shown, more) = visible_suggestions(&hits, Some(1), 3);
        assert_eq!(idxs(&shown), [0, 2, 3]);
        assert_eq!(more, 1);
    }

    #[test]
    fn suggestions_with_few_hits_have_no_remainder() {
        let hits = [hit(4), hit(9)];
        assert_eq!(visible_suggestions(&hits, None, 3).1, 0);
        let (shown, more) = visible_suggestions(&hits, Some(4), 3);
        assert_eq!((idxs(&shown), more), (vec![9], 0));
        let (shown, more) = visible_suggestions(&[], Some(4), 3);
        assert!(shown.is_empty());
        assert_eq!(more, 0);
        // An exact match that is not among the hits changes nothing.
        let (shown, _) = visible_suggestions(&hits, Some(77), 3);
        assert_eq!(shown.len(), 2);
    }

    #[test]
    fn t9_needs_plain_digits() {
        assert_eq!(t9_digits("5"), Some("5"));
        assert_eq!(t9_digits("0532"), Some("0532"));
        for bad in ["", "+90", "*21#", "12#", "1*"] {
            assert_eq!(t9_digits(bad), None, "{bad}");
        }
    }

    #[test]
    fn spans_become_byte_ranges() {
        assert_eq!(byte_range("Ayse Yilmaz", (0, 2)), Some(0..2));
        assert_eq!(byte_range("Ayse Yilmaz", (5, 11)), Some(5..11));
        // Multi-byte characters: the span counts characters.
        assert_eq!(byte_range("Şeyma Öz", (0, 1)), Some(0..2));
        assert_eq!(byte_range("Şeyma Öz", (6, 8)), Some(7..10));
        // Past the end is clamped, empty or out of range is nothing.
        assert_eq!(byte_range("Ali", (1, 9)), Some(1..3));
        assert_eq!(byte_range("Ali", (2, 2)), None);
        assert_eq!(byte_range("Ali", (5, 6)), None);
    }

    #[test]
    fn only_the_one_key_has_a_hold_label() {
        for (index, key) in KEYS.iter().enumerate() {
            assert_eq!(key.hold_label.is_some(), index == 0, "key {}", key.digit);
        }
        assert_eq!(KEYS[0].hold_label, Some("1, hold for voicemail"));
    }

    #[test]
    fn key_table_is_consistent() {
        for key in KEYS {
            assert_eq!(key.label, expected_label(key.digit, key.letters));
            assert_eq!(key.id, format!("key-{}", key.digit));
            assert_eq!(key.in_id, format!("ik-{}", key.digit));
        }
    }

    #[test]
    fn every_key_is_a_dial_key() {
        for k in KEYS {
            let mut field = String::new();
            let key = k.digit.chars().next().expect("one char");
            assert_eq!(dial::push(&mut field, key), Push::Accepted);
        }
    }
}
