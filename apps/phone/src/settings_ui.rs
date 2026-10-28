//! The Settings page behind the gear (Epic 16): one pushed full-height page
//! with grouped sections, S24 (gear turn and page push) and S25 (toggle snap).
//!
//! The page is a table of [`Section`]s built from the settings, the SIMs and
//! what the phone can do, and rebuilt only when one of those changed
//! ([`RowsKey`]), never per frame. A section with no rows is not drawn, and
//! the SIM section does not exist below two SIMs, so later stories register
//! their sections here without touching the layout code.
//!
//! Every row acts on finger-up (the host synthesises the click on lift); the
//! press feedback follows the raw touch.

use std::sync::Arc;

use gpui::{
    AnyElement, Context, ElementId, HapticFeedback, Hsla, InteractiveElement, IntoElement,
    MouseButton, ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window,
    div, px, svg,
};
use rovsti_core::{
    names,
    settings::{Settings, SortBy},
    sim::SimInfo,
    wire::{HapticsLevel, NameFormat, WireContact},
};
use rovsti_ui::{
    Icon,
    components::{backdrop, glass_icon_button, glass_icon_button_turned},
    moments::Fade,
    motion_tokens,
    spring::Spring,
    theme,
    tokens::{Theme, mix, radius, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp, SheetKind,
    backend::{self, SystemFacts},
};

/// Long-press on the Settings title opens Diagnostics (moved from the Setup
/// sheet in story 16.1; the old trigger still works until the next release).
pub(crate) const DIAG_AFTER_MS: u64 = 800;

/// 60 degrees, the gear's turn while held.
const GEAR_TURN: f32 = std::f32::consts::FRAC_PI_3;
const ROW_H: f32 = 58.0;
const GROUP_GAP: f32 = 16.0;
const SECTION_GAP: f32 = 35.0;
const TRACK_W: f32 = 51.0;
const TRACK_H: f32 = 31.0;
const THUMB: f32 = 27.0;
const SEG_W: f32 = 200.0;
const SEG_H: f32 = 32.0;

/// Every control the page can show. The discriminant indexes the spring table.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RowId {
    Proximity,
    Flip,
    VibrateConnect,
    VibrateEnd,
    KeypadTones,
    Haptics,
    Ringtone,
    SortBy,
    NameFormat,
    NamePreview,
    Theme,
    SimDefault,
    Export,
    Import,
    ImportError,
    Forwarding,
    Accessibility,
    DefaultApps,
    EmergencyInfo,
    Hearing,
}

impl RowId {
    const COUNT: usize = 20;

    const fn index(self) -> usize {
        self as usize
    }

    const fn key(self) -> &'static str {
        match self {
            Self::Proximity => "set-proximity",
            Self::Flip => "set-flip",
            Self::VibrateConnect => "set-vib-connect",
            Self::VibrateEnd => "set-vib-end",
            Self::KeypadTones => "set-tones",
            Self::Haptics => "set-haptics",
            Self::Ringtone => "set-ringtone",
            Self::SortBy => "set-sort",
            Self::NameFormat => "set-format",
            Self::NamePreview => "set-preview",
            Self::Theme => "set-theme",
            Self::SimDefault => "set-sim",
            Self::Export => "set-export",
            Self::Import => "set-import",
            Self::ImportError => "set-import-error",
            Self::Forwarding => "set-forwarding",
            Self::Accessibility => "set-access",
            Self::DefaultApps => "set-default-apps",
            Self::EmergencyInfo => "set-emergency",
            Self::Hearing => "set-hearing",
        }
    }
}

/// How a row's control looks and behaves.
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum Control {
    Toggle,
    /// Option labels; the selected index lives in the settings.
    Segmented(&'static [&'static str]),
    /// A tappable row: optional value, an icon at the trailing edge.
    Link {
        value: Option<SharedString>,
        icon: Icon,
    },
    /// Read-only text on the right.
    Info(SharedString),
    /// The inline red line of a refused import.
    Error(SharedString),
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Row {
    pub id: RowId,
    pub title: &'static str,
    pub footnote: Option<&'static str>,
    pub control: Control,
    /// Dimmed and inert (a sensor the phone lacks, a page that is not there).
    pub enabled: bool,
    /// Ids of the segments (built once with the row).
    pub segment_ids: Vec<SharedString>,
}

impl Row {
    fn new(id: RowId, title: &'static str, control: Control) -> Self {
        let segment_ids = match &control {
            Control::Segmented(options) => (0..options.len())
                .map(|i| SharedString::from(format!("{}-{i}", id.key())))
                .collect(),
            _ => Vec::new(),
        };
        Self {
            id,
            title,
            footnote: None,
            control,
            enabled: true,
            segment_ids,
        }
    }

    fn note(mut self, footnote: &'static str) -> Self {
        self.footnote = Some(footnote);
        self
    }

    fn disabled(mut self, footnote: &'static str) -> Self {
        self.enabled = false;
        self.footnote = Some(footnote);
        self
    }
}

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Section {
    pub title: &'static str,
    pub rows: Vec<Row>,
    pub footer: Option<&'static str>,
}

const HAPTIC_OPTIONS: &[&str] = &["Off", "Light", "Normal"];
const SORT_OPTIONS: &[&str] = &["First name", "Last name"];
const FORMAT_OPTIONS: &[&str] = &["First Last", "Last, First"];

/// What the sections are built from. Compared by value (the settings by
/// `Arc` identity), so an unchanged frame rebuilds nothing.
#[derive(Clone)]
pub(crate) struct RowsKey {
    pub settings: Arc<Settings>,
    pub sims: SimInfo,
    pub facts: SystemFacts,
    pub on_android: bool,
    pub import_error: Option<&'static str>,
    pub names_differ: bool,
}

impl PartialEq for RowsKey {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.settings, &other.settings)
            && self.sims == other.sims
            && self.facts == other.facts
            && self.on_android == other.on_android
            && self.import_error == other.import_error
            && self.names_differ == other.names_differ
    }
}

/// The sample name of the live preview line.
fn preview_name(format: NameFormat) -> SharedString {
    let sample = WireContact {
        display_name: "Ayşe Yılmaz".into(),
        display_name_alt: Some("Yılmaz, Ayşe".into()),
        ..WireContact::default()
    };
    names::display(&sample, format).into_owned().into()
}

/// The effective value of a toggle row for the current settings.
pub(crate) fn toggle_value(row: RowId, s: &Settings, system_tones: bool) -> bool {
    match row {
        RowId::Proximity => s.proximity,
        RowId::Flip => s.flip_to_silence,
        RowId::VibrateConnect => s.vibrate_connect,
        RowId::VibrateEnd => s.vibrate_end,
        RowId::KeypadTones => {
            rovsti_core::settings::effective_keypad_tones(s.keypad_tones, system_tones)
        }
        _ => false,
    }
}

/// The selected option of a segmented row.
pub(crate) fn segment_value(row: RowId, s: &Settings) -> usize {
    match row {
        RowId::Haptics => match s.haptics {
            HapticsLevel::Off => 0,
            HapticsLevel::Light => 1,
            HapticsLevel::Normal => 2,
        },
        RowId::SortBy => usize::from(s.sort_by == SortBy::LastName),
        RowId::NameFormat => usize::from(s.name_format == NameFormat::LastFirst),
        _ => 0,
    }
}

/// Builds the page. A section without rows is left out.
pub(crate) fn build_sections(key: &RowsKey) -> Vec<Section> {
    let s = &*key.settings;
    let mut out = Vec::with_capacity(6);

    let mut flip = Row::new(RowId::Flip, "Flip to silence", Control::Toggle);
    flip = if key.facts.flip_supported {
        flip.note("Face down while ringing")
    } else {
        flip.disabled("Not supported on this phone")
    };
    out.push(Section {
        title: "Calls",
        rows: vec![
            Row::new(RowId::Proximity, "Proximity screen-off", Control::Toggle),
            flip,
            Row::new(
                RowId::VibrateConnect,
                "Vibrate when call connects",
                Control::Toggle,
            )
            .note("May not work with the screen off"),
            Row::new(RowId::VibrateEnd, "Vibrate when call ends", Control::Toggle),
        ],
        footer: None,
    });

    out.push(Section {
        title: "Sounds and haptics",
        rows: vec![
            Row::new(RowId::KeypadTones, "Keypad tones", Control::Toggle),
            Row::new(
                RowId::Haptics,
                "Haptics",
                Control::Segmented(HAPTIC_OPTIONS),
            ),
            Row::new(
                RowId::Ringtone,
                "Ringtone and sounds",
                Control::Link {
                    value: None,
                    icon: Icon::LucideExternalLink,
                },
            ),
        ],
        footer: None,
    });

    let dim = |row: Row| {
        if key.names_differ {
            row
        } else {
            row.disabled("Your contacts have no alternative names")
        }
    };
    out.push(Section {
        title: "Display",
        rows: vec![
            dim(Row::new(
                RowId::SortBy,
                "Sort by",
                Control::Segmented(SORT_OPTIONS),
            )),
            dim(Row::new(
                RowId::NameFormat,
                "Name format",
                Control::Segmented(FORMAT_OPTIONS),
            )),
            Row::new(
                RowId::NamePreview,
                "Preview",
                Control::Info(preview_name(s.name_format)),
            ),
            Row::new(
                RowId::Theme,
                "Theme",
                Control::Info("Follows system".into()),
            ),
        ],
        footer: None,
    });

    if key.sims.visible() {
        out.push(Section {
            title: "SIM",
            rows: vec![Row::new(
                RowId::SimDefault,
                "Default for calls",
                Control::Link {
                    value: Some(key.sims.default_label().to_owned().into()),
                    icon: Icon::LucideExternalLink,
                },
            )],
            footer: None,
        });
    }

    let mut data = vec![
        Row::new(
            RowId::Export,
            "Export favorites and settings",
            Control::Link {
                value: None,
                icon: Icon::LucideUpload,
            },
        ),
        Row::new(
            RowId::Import,
            "Import",
            Control::Link {
                value: None,
                icon: Icon::LucideDownload,
            },
        ),
    ];
    if let Some(message) = key.import_error {
        data.push(Row::new(
            RowId::ImportError,
            "",
            Control::Error(message.into()),
        ));
    }
    out.push(Section {
        title: "Data",
        rows: data,
        footer: Some("Contacts, call log and blocked numbers are not included."),
    });

    let shortcut = |id, title, value: Option<SharedString>| {
        let mut row = Row::new(
            id,
            title,
            if key.on_android {
                Control::Link {
                    value,
                    icon: Icon::LucideExternalLink,
                }
            } else {
                Control::Info("Not available on this platform".into())
            },
        );
        row.enabled = key.on_android;
        row
    };
    let mut shortcuts = vec![
        shortcut(RowId::Forwarding, "Call forwarding and waiting", None),
        shortcut(RowId::Accessibility, "Accessibility (TTY and RTT)", None),
        shortcut(RowId::DefaultApps, "Default apps", None),
    ];
    // "Emergency information" stays out until the S10 result says the intent resolves (20.4).
    if key.facts.emergency_info {
        shortcuts.push(shortcut(
            RowId::EmergencyInfo,
            "Emergency information",
            None,
        ));
    }
    let hearing = match key.facts.hearing_aid_compatible {
        Some(true) => "Supported",
        Some(false) | None => "Not reported",
    };
    let mut hearing_row = Row::new(
        RowId::Hearing,
        "Hearing-aid compatibility",
        Control::Info(if key.on_android {
            hearing.into()
        } else {
            "Not available on this platform".into()
        }),
    );
    hearing_row.enabled = false;
    shortcuts.push(hearing_row);
    out.push(Section {
        title: "System shortcuts",
        rows: shortcuts,
        footer: None,
    });

    out.retain(|section| !section.rows.is_empty());
    out
}

/// The page's state.
pub(crate) struct SettingsUi {
    pub open: bool,
    /// Drawn at all (open, or still fading out).
    shown: bool,
    enter: Spring,
    fade: Fade,
    reduced: bool,
    /// 0 at rest, 1 turned by [`GEAR_TURN`].
    gear: Spring,
    pub gear_held: bool,
    /// Thumb or indicator positions, indexed by [`RowId`].
    anim: Vec<Spring>,
    key: Option<RowsKey>,
    sections: Vec<Section>,
    /// New id per open, so the list starts at the top.
    scroll_gen: usize,
    /// The inline red line of the last refused import.
    pub import_error: Option<&'static str>,
    /// Contacts whose alternative name differs from the primary one.
    pub names_differ: bool,
    /// Desktop preview: row ids to tap, one per `I` key.
    #[cfg(not(target_os = "android"))]
    pub preview_taps: std::collections::VecDeque<String>,
}

impl Default for SettingsUi {
    fn default() -> Self {
        Self {
            open: false,
            shown: false,
            enter: Spring::new(0.0, motion_tokens::appear()).with_epsilon(0.002),
            fade: Fade::default(),
            reduced: false,
            gear: Spring::new(0.0, motion_tokens::glow()).with_epsilon(0.002),
            gear_held: false,
            anim: (0..RowId::COUNT)
                .map(|_| Spring::new(0.0, motion_tokens::select()).with_epsilon(0.002))
                .collect(),
            key: None,
            sections: Vec::new(),
            scroll_gen: 0,
            import_error: None,
            names_differ: true,
            #[cfg(not(target_os = "android"))]
            preview_taps: std::env::var("ROVSTI_PREVIEW_TAPS")
                .map(|v| v.split(',').map(str::to_owned).collect())
                .unwrap_or_default(),
        }
    }
}

impl SettingsUi {
    /// 0 when closed, 1 when settled open.
    pub fn progress(&self) -> f32 {
        if self.reduced {
            if self.open {
                self.fade.value()
            } else if self.shown {
                1.0 - self.fade.value()
            } else {
                0.0
            }
        } else {
            self.enter.value.clamp(0.0, 1.0)
        }
    }

    /// Anything of the page is on screen.
    pub fn covers(&self) -> bool {
        self.shown
    }

    pub fn is_open(&self) -> bool {
        self.open
    }

    /// Opens (S24). Reduced motion is a 120 ms fade.
    pub fn open(&mut self, reduced: bool) {
        self.reduced = reduced;
        self.open = true;
        self.shown = true;
        self.scroll_gen = self.scroll_gen.wrapping_add(1);
        self.import_error = None;
        if reduced {
            self.fade.restart();
        } else {
            self.enter.set_target(1.0);
        }
    }

    pub fn close(&mut self, reduced: bool) {
        if !self.open {
            return;
        }
        self.reduced = reduced;
        self.open = false;
        if reduced {
            self.fade.restart();
        } else {
            self.enter.set_target(0.0);
        }
    }

    /// Desktop preview: open without the push.
    #[cfg(not(target_os = "android"))]
    pub fn enter_snap(&mut self) {
        self.enter.snap(1.0);
    }

    /// A new call or a re-lock: gone at once.
    pub fn close_now(&mut self) {
        self.open = false;
        self.shown = false;
        self.enter.snap(0.0);
    }

    pub fn tick(&mut self, dt: std::time::Duration, real: std::time::Duration) -> bool {
        let mut moving = false;
        if self.shown {
            moving |= if self.reduced {
                self.fade.tick(real)
            } else {
                self.enter.tick(dt)
            };
            for spring in &mut self.anim {
                moving |= spring.tick(dt);
            }
        }
        moving |= self.gear.tick(dt);
        if self.shown && !self.open && self.progress() <= 0.002 && !moving_fade(self) {
            self.shown = false;
        }
        moving
    }

    /// Moves the gear's target: turned while the finger is on it.
    pub fn sync_gear(&mut self, reduced: bool) {
        let target = if self.gear_held && !reduced { 1.0 } else { 0.0 };
        if (self.gear.target - target).abs() > f32::EPSILON {
            self.gear.set_target(target);
        }
    }

    fn gear_turn(&self) -> f32 {
        self.gear.value * GEAR_TURN
    }
}

fn moving_fade(ui: &SettingsUi) -> bool {
    ui.reduced && ui.fade.running()
}

// ---- behaviour ---------------------------------------------------------------

impl PhoneApp {
    /// S24: the gear was tapped (finger-up). Does nothing during a call.
    pub(crate) fn settings_open(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.settings.is_open() || !self.can_open_sheet() || self.sheet.is_visible() {
            return;
        }
        let reduced = window.prefers_reduced_motion();
        self.settings.open(reduced);
        self.sync_settings_rows(cx, true);
        self.update_back(window);
        cx.notify();
    }

    pub(crate) fn settings_close(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let reduced = window.prefers_reduced_motion();
        self.settings.close(reduced);
        self.update_back(window);
        cx.notify();
    }

    /// Rebuilds the rows when something they depend on changed; moves the
    /// control springs to the stored values (snapping when `snap`).
    pub(crate) fn sync_settings_rows(&mut self, cx: &mut Context<Self>, snap: bool) {
        let backend = self.backend.read(cx);
        let key = RowsKey {
            settings: backend.settings(),
            sims: backend.sims(),
            facts: backend.facts(),
            on_android: cfg!(target_os = "android"),
            import_error: self.settings.import_error,
            names_differ: self.settings.names_differ,
        };
        let system_tones = key.facts.system_keypad_tones;
        for row in [
            RowId::Proximity,
            RowId::Flip,
            RowId::VibrateConnect,
            RowId::VibrateEnd,
            RowId::KeypadTones,
        ] {
            let target = f32::from(u8::from(toggle_value(row, &key.settings, system_tones)));
            self.settings_target(row, target, snap);
        }
        for row in [RowId::Haptics, RowId::SortBy, RowId::NameFormat] {
            let target = segment_value(row, &key.settings) as f32;
            self.settings_target(row, target, snap);
        }
        if self.settings.key.as_ref() != Some(&key) {
            self.settings.sections = build_sections(&key);
            self.settings.key = Some(key);
        }
    }

    fn settings_target(&mut self, row: RowId, target: f32, snap: bool) {
        let spring = &mut self.settings.anim[row.index()];
        if snap {
            spring.snap(target);
        }
        if (spring.target - target).abs() > f32::EPSILON {
            spring.set_target(target);
        }
    }

    /// One row was tapped.
    pub(crate) fn settings_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        if id == "set-back" {
            self.settings_close(window, cx);
            return;
        }
        let tapped = self
            .settings
            .sections
            .iter()
            .flat_map(|s| &s.rows)
            .find_map(|r| {
                if r.id.key() == id {
                    Some((r.id, None))
                } else {
                    r.segment_ids
                        .iter()
                        .position(|s| s.as_ref() == id)
                        .map(|i| (r.id, Some(i)))
                }
            });
        let Some((row, segment)) = tapped else { return };
        let enabled = self
            .settings
            .sections
            .iter()
            .flat_map(|s| &s.rows)
            .any(|r| r.id == row && r.enabled);
        if !enabled {
            return;
        }
        match (row, segment) {
            (RowId::Proximity, _) => self.toggle_setting(row, window, cx, |s| {
                s.proximity = !s.proximity;
            }),
            (RowId::Flip, _) => self.toggle_setting(row, window, cx, |s| {
                s.flip_to_silence = !s.flip_to_silence;
            }),
            (RowId::VibrateConnect, _) => self.toggle_setting(row, window, cx, |s| {
                s.vibrate_connect = !s.vibrate_connect;
            }),
            (RowId::VibrateEnd, _) => self.toggle_setting(row, window, cx, |s| {
                s.vibrate_end = !s.vibrate_end;
            }),
            (RowId::KeypadTones, _) => {
                let system = self.backend.read(cx).facts().system_keypad_tones;
                self.toggle_setting(row, window, cx, move |s| {
                    let now = rovsti_core::settings::effective_keypad_tones(s.keypad_tones, system);
                    s.keypad_tones = Some(!now);
                });
            }
            (RowId::Haptics, Some(i)) => {
                let level =
                    [HapticsLevel::Off, HapticsLevel::Light, HapticsLevel::Normal][i.min(2)];
                self.pick_setting(row, i, window, cx, move |s| s.haptics = level);
            }
            (RowId::SortBy, Some(i)) => {
                let by = if i == 0 {
                    SortBy::FirstName
                } else {
                    SortBy::LastName
                };
                self.pick_setting(row, i, window, cx, move |s| s.sort_by = by);
            }
            (RowId::NameFormat, Some(i)) => {
                let format = if i == 0 {
                    NameFormat::FirstLast
                } else {
                    NameFormat::LastFirst
                };
                self.pick_setting(row, i, window, cx, move |s| s.name_format = format);
            }
            (RowId::Ringtone, _) => {
                self.open_system_page(rovsti_android::telecom::SystemPage::Sound, cx)
            }
            (RowId::SimDefault, _) => {
                self.open_system_page(rovsti_android::telecom::SystemPage::ChangePhoneAccounts, cx)
            }
            (RowId::Forwarding, _) => {
                self.open_system_page(rovsti_android::telecom::SystemPage::CallSettings, cx);
            }
            (RowId::Accessibility, _) => {
                self.open_system_page(rovsti_android::telecom::SystemPage::CallAccessibility, cx);
            }
            (RowId::DefaultApps, _) => {
                self.open_system_page(rovsti_android::telecom::SystemPage::DefaultApps, cx);
            }
            (RowId::EmergencyInfo, _) => {
                self.open_system_page(rovsti_android::telecom::SystemPage::EmergencyInfo, cx);
            }
            (RowId::Export, _) => self.backup_export(cx),
            (RowId::Import, _) => self.backup_import(cx),
            _ => {}
        }
        cx.notify();
    }

    fn open_system_page(
        &mut self,
        page: rovsti_android::telecom::SystemPage,
        cx: &mut Context<Self>,
    ) {
        if let Some(text) = backend::open_system(page).notice() {
            self.show_notice_pill(text, None, false, cx);
        }
    }

    fn toggle_setting(
        &mut self,
        row: RowId,
        window: &mut Window,
        cx: &mut Context<Self>,
        f: impl FnOnce(&mut Settings),
    ) {
        // S25: the thumb travels on `select`; a tick that follows the Haptics setting.
        self.backend.update(cx, |b, cx| b.change_setting(cx, f));
        self.sync_settings_rows(cx, false);
        if window.prefers_reduced_motion() {
            let target = self.settings.anim[row.index()].target;
            self.settings.anim[row.index()].snap(target);
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
    }

    fn pick_setting(
        &mut self,
        row: RowId,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
        f: impl FnOnce(&mut Settings),
    ) {
        if self.settings.anim[row.index()].target as usize == index {
            return;
        }
        self.backend.update(cx, |b, cx| b.change_setting(cx, f));
        self.sync_settings_rows(cx, false);
        if window.prefers_reduced_motion() {
            self.settings.anim[row.index()].snap(index as f32);
        }
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
    }

    /// Once per render, before the springs tick: the gear's target, and a
    /// new call or a re-lock closes the page at once (AD-3).
    pub(crate) fn sync_settings_frame(&mut self, window: &Window, cx: &mut Context<Self>) {
        let reduced = window.prefers_reduced_motion();
        if self.settings.covers() && (self.incall.covers() || self.incoming.covers()) {
            self.settings.close_now();
        }
        self.settings.reduced = reduced;
        self.settings.sync_gear(reduced);
        self.sync_setting_failure(cx);
    }

    /// On a backend change: keep the rows and the control positions current.
    pub(crate) fn sync_settings_live(&mut self, cx: &mut Context<Self>) {
        if self.settings.covers() {
            self.sync_settings_rows(cx, false);
        }
    }

    /// Once per render: a failed save put the value back and says so.
    pub(crate) fn sync_setting_failure(&mut self, cx: &mut Context<Self>) {
        let failed = self.backend.read(cx).setting_failed_gen();
        if failed != self.setting_failed_seen {
            self.setting_failed_seen = failed;
            self.show_notice_pill("Couldn't save setting", None, false, cx);
            self.sync_settings_rows(cx, false);
        }
    }

    // ---- rendering --------------------------------------------------------------

    /// The gear in the title's reserved slot (absolute, left of the Setup button).
    pub(crate) fn settings_gear(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let press = self.presses.value("gear");
        div()
            .id("gear")
            .role(Role::Button)
            .aria_label("Settings")
            .absolute()
            .top(px(54.0))
            .right(px(60.0))
            .size(px(44.0))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, window, cx| {
                    this.presses.down("gear");
                    this.settings.gear_held = true;
                    this.settings_open(window, cx);
                }),
            )
            .child(glass_icon_button_turned(
                theme,
                Icon::LucideSettings,
                press,
                false,
                1.0,
                self.settings.gear_turn(),
            ))
            .child(touch_surface(Self::touch_listener(cx, Some("gear".into()))))
    }

    /// Row text colour for a dimmed row.
    fn dimmed(colors: &rovsti_ui::tokens::Colors, enabled: bool) -> Hsla {
        if enabled {
            colors.label
        } else {
            colors.tertiary_label
        }
    }

    fn toggle_control(&self, theme: Theme, row: &Row, press: f32) -> AnyElement {
        let colors = theme::colors(theme);
        let pos = self.settings.anim[row.id.index()].value;
        // The track mixes grey to the accent in RGB while the thumb travels.
        let track = mix(colors.fill, colors.accent, pos.clamp(0.0, 1.0));
        let stretch = THUMB * (1.0 + 0.2 * press.clamp(0.0, 1.0));
        let travel = TRACK_W - 4.0 - THUMB;
        // The edge nearest the end it is leaving stays put while it stretches.
        let left = 2.0 + pos * travel - (stretch - THUMB) * pos.clamp(0.0, 1.0);
        div()
            .relative()
            .flex_none()
            .w(px(TRACK_W))
            .h(px(TRACK_H))
            .rounded_full()
            .bg(track)
            .opacity(if row.enabled { 1.0 } else { 0.5 })
            .child(
                div()
                    .absolute()
                    .top(px(2.0))
                    .left(px(left))
                    .w(px(stretch))
                    .h(px(THUMB))
                    .rounded_full()
                    .bg(gpui::white())
                    .shadow(vec![gpui::BoxShadow {
                        color: gpui::black().opacity(0.22),
                        offset: gpui::point(px(0.0), px(2.0)),
                        blur_radius: px(4.0),
                        spread_radius: px(0.0),
                        inset: false,
                    }]),
            )
            .into_any_element()
    }

    fn segmented_control(
        &self,
        theme: Theme,
        row: &Row,
        options: &'static [&'static str],
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let pos = self.settings.anim[row.id.index()].value;
        let n = options.len() as f32;
        let seg_w = (SEG_W - 4.0) / n;
        let selected = pos.round().clamp(0.0, n - 1.0) as usize;
        let enabled = row.enabled;
        div()
            .relative()
            .flex_none()
            .w(px(SEG_W))
            .h(px(SEG_H))
            .rounded_full()
            .bg(Hsla {
                a: 0.55,
                ..colors.fill
            })
            .opacity(if enabled { 1.0 } else { 0.5 })
            .child(
                div()
                    .absolute()
                    .top(px(2.0))
                    .left(px(2.0 + pos * seg_w))
                    .w(px(seg_w))
                    .h(px(SEG_H - 4.0))
                    .rounded_full()
                    .bg(mix(
                        colors.background,
                        colors.label,
                        if theme.is_dark() { 0.22 } else { 0.0 },
                    ))
                    .shadow(vec![gpui::BoxShadow {
                        color: gpui::black().opacity(0.16),
                        offset: gpui::point(px(0.0), px(1.0)),
                        blur_radius: px(3.0),
                        spread_radius: px(0.0),
                        inset: false,
                    }]),
            )
            .child(
                div()
                    .absolute()
                    .inset_0()
                    .flex()
                    .children(options.iter().enumerate().map(|(i, label)| {
                        let id = row.segment_ids[i].clone();
                        let mut seg = div()
                            .id(id.clone())
                            .role(Role::Tab)
                            .aria_selected(i == selected)
                            .aria_label(*label)
                            .relative()
                            .w(px(seg_w + 4.0 / n))
                            .h_full()
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_size(px(13.0))
                            .font_weight(if i == selected {
                                gpui::FontWeight::SEMIBOLD
                            } else {
                                gpui::FontWeight::NORMAL
                            })
                            .text_color(Self::dimmed(&colors, true))
                            .child(*label);
                        if enabled {
                            seg = seg
                                .on_mouse_down(MouseButton::Left, {
                                    let id = id.clone();
                                    cx.listener(move |this, _, window, cx| {
                                        this.presses.down(id.clone());
                                        this.settings_tap(&id, window, cx);
                                    })
                                })
                                .child(touch_surface(Self::touch_listener(cx, Some(id))));
                        }
                        seg
                    })),
            )
            .into_any_element()
    }

    fn settings_row(
        &self,
        theme: Theme,
        row: &Row,
        last: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = SharedString::new_static(row.id.key());
        let press = self.presses.value(row.id.key());
        let tappable = row.enabled && matches!(row.control, Control::Toggle | Control::Link { .. });
        if let Control::Error(message) = &row.control {
            return div()
                .id(id)
                .role(Role::Alert)
                .aria_label(message.clone())
                .min_h(px(44.0))
                .px(px(16.0))
                .flex()
                .items_center()
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.red)
                .child(message.clone())
                .into_any_element();
        }
        let title_color = Self::dimmed(&colors, row.enabled);
        let mut text = div().flex_1().min_w_0().flex().flex_col().child(
            div()
                .text_size(typography::HEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(title_color)
                .child(row.title),
        );
        if let Some(note) = row.footnote {
            text = text.child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(note),
            );
        }
        let trailing: AnyElement = match &row.control {
            Control::Toggle => self.toggle_control(theme, row, press),
            Control::Segmented(options) => self.segmented_control(theme, row, options, cx),
            Control::Link { value, icon } => div()
                .flex_none()
                .flex()
                .items_center()
                .gap(px(8.0))
                .children(value.as_ref().map(|v| {
                    div()
                        .text_size(typography::BODY.size)
                        .text_color(colors.secondary_label)
                        .child(v.clone())
                }))
                .child(
                    svg()
                        .path(icon.path())
                        .size(px(18.0))
                        .text_color(colors.tertiary_label),
                )
                .into_any_element(),
            Control::Info(value) => div()
                .flex_none()
                .max_w(px(190.0))
                .text_size(typography::BODY.size)
                .text_color(if row.enabled {
                    colors.secondary_label
                } else {
                    colors.tertiary_label
                })
                .child(value.clone())
                .into_any_element(),
            Control::Error(_) => div().into_any_element(),
        };
        let label = match &row.control {
            Control::Toggle => {
                let on = toggle_value(
                    row.id,
                    &self.backend.read(cx).settings(),
                    self.backend.read(cx).facts().system_keypad_tones,
                );
                format!(
                    "{}, {}. Double tap to toggle",
                    row.title,
                    if on { "on" } else { "off" }
                )
            }
            Control::Segmented(options) => {
                let i = (self.settings.anim[row.id.index()].target as usize).min(options.len() - 1);
                format!("{}: {}, {} options", row.title, options[i], options.len())
            }
            Control::Link { value, .. } => match value {
                Some(v) => format!("{}, {v}", row.title),
                None => row.title.to_owned(),
            },
            Control::Info(v) => format!("{}, {v}", row.title),
            Control::Error(_) => String::new(),
        };
        let mut el = div()
            .id(id.clone())
            .role(match row.control {
                Control::Toggle => Role::Switch,
                Control::Link { .. } => Role::Button,
                _ => Role::Row,
            })
            .aria_label(label)
            .aria_disabled(!row.enabled)
            .relative()
            .w_full()
            .min_h(px(ROW_H))
            .px(px(16.0))
            .flex()
            .items_center()
            .justify_between()
            .gap(px(12.0))
            .bg(Hsla {
                a: 0.07 * press.clamp(0.0, 1.0),
                ..colors.label
            })
            .child(text)
            .child(trailing);
        if !last {
            el = el.border_b(px(0.33)).border_color(colors.separator);
        }
        if tappable {
            el = el
                .on_mouse_down(MouseButton::Left, {
                    let id = id.clone();
                    cx.listener(move |this, _, window, cx| {
                        this.presses.down(id.clone());
                        this.settings_tap(&id, window, cx);
                    })
                })
                .child(touch_surface(Self::touch_listener(cx, Some(id))));
        }
        el.into_any_element()
    }

    fn settings_section(
        &self,
        theme: Theme,
        section: &Section,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let n = section.rows.len();
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(
                div()
                    .px(px(32.0))
                    .text_size(typography::FOOTNOTE.size)
                    .font_weight(gpui::FontWeight::SEMIBOLD)
                    .text_color(colors.secondary_label)
                    .child(section.title),
            )
            .child(
                div()
                    .mx(px(16.0))
                    .rounded(radius::GROUPED_LIST)
                    .overflow_hidden()
                    .bg(Hsla {
                        a: 0.72,
                        ..colors.secondary_background
                    })
                    .flex()
                    .flex_col()
                    .children(
                        section
                            .rows
                            .iter()
                            .enumerate()
                            .map(|(i, row)| self.settings_row(theme, row, i + 1 == n, cx)),
                    ),
            )
            .children(section.footer.map(|text| {
                div()
                    .px(px(32.0))
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(text)
            }))
            .into_any_element()
    }

    /// The page over the tabs; `None` when it is not on screen.
    pub(crate) fn settings_overlay(
        &mut self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.settings.covers() {
            return None;
        }
        let colors = theme::colors(theme);
        let p = self.settings.progress();
        let slide = if self.settings.reduced {
            0.0
        } else {
            28.0 * (1.0 - p)
        };
        let parallax = if self.settings.reduced {
            0.0
        } else {
            12.0 * (1.0 - p)
        };
        let back_press = self.presses.value("set-back");
        let back = div()
            .id("set-back")
            .role(Role::Button)
            .aria_label("Back")
            .size(px(44.0))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, window, cx| {
                    this.presses.down("set-back");
                    this.settings_tap("set-back", window, cx);
                }),
            )
            .child(glass_icon_button(
                theme,
                Icon::LucideChevronLeft,
                back_press,
                false,
            ))
            .child(touch_surface(Self::touch_listener(
                cx,
                Some("set-back".into()),
            )));
        let title = div()
            .id("set-title")
            .relative()
            .left(px(parallax))
            .px(px(16.0))
            .pt(px(4.0))
            .pb(px(8.0))
            .text_size(typography::LARGE_TITLE.size)
            .line_height(typography::LARGE_TITLE.line)
            .font_weight(typography::LARGE_TITLE.weight)
            .text_color(colors.label)
            .child("Settings")
            .child(touch_surface(Self::touch_listener(
                cx,
                Some("set-title".into()),
            )));
        let sections: Vec<AnyElement> = self
            .settings
            .sections
            .iter()
            .map(|s| self.settings_section(theme, s, cx))
            .collect();
        let list = div()
            .id(ElementId::NamedInteger(
                "settings-scroll".into(),
                self.settings.scroll_gen as u64,
            ))
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .pb(px(48.0))
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(SECTION_GAP - GROUP_GAP))
                    .pt(px(GROUP_GAP))
                    .children(sections),
            );
        Some(
            div()
                .id("settings-page")
                .absolute()
                .inset_0()
                .occlude()
                .child(
                    div()
                        .absolute()
                        .inset_0()
                        .opacity(p)
                        .child(backdrop(theme, theme::accent_glow(theme))),
                )
                .child(
                    div()
                        .relative()
                        .left(px(slide))
                        .opacity(p)
                        .size_full()
                        .flex()
                        .flex_col()
                        .child(div().h(px(54.0)).flex_none())
                        .child(div().px(px(12.0)).h(px(44.0)).flex_none().child(back))
                        .child(title)
                        .child(list),
                )
                .into_any_element(),
        )
    }

    /// Opens Diagnostics as a sheet over the page (the long-press on the title).
    pub(crate) fn settings_diagnostics(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.settings.is_open() && !self.sheet.is_open() {
            rovsti_ui::haptics::perform(window, HapticFeedback::LongPress);
            self.sheet_kind = SheetKind::Diagnostics;
            self.sheet.open();
            self.presses.release_all();
            cx.notify();
        }
    }

    /// The tab-bar fade that goes with the page.
    pub(crate) fn settings_tab_fade(&self) -> f32 {
        1.0 - self.settings.progress()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key() -> RowsKey {
        RowsKey {
            settings: Arc::new(Settings::default()),
            sims: SimInfo::default(),
            facts: SystemFacts::default(),
            on_android: true,
            import_error: None,
            names_differ: true,
        }
    }

    fn titles(sections: &[Section]) -> Vec<&'static str> {
        sections.iter().map(|s| s.title).collect()
    }

    #[test]
    fn one_sim_has_no_sim_section() {
        let sections = build_sections(&key());
        assert!(!titles(&sections).contains(&"SIM"));
        assert_eq!(
            titles(&sections),
            [
                "Calls",
                "Sounds and haptics",
                "Display",
                "Data",
                "System shortcuts"
            ]
        );
    }

    #[test]
    fn two_sims_add_the_sim_section() {
        use rovsti_core::wire::{PhoneAccountInfo, PhoneAccounts};
        let account = |k: &str, slot| PhoneAccountInfo {
            key: k.into(),
            label: format!("Net{slot}"),
            slot_index: Some(slot),
            sim: true,
            enabled: true,
            color_argb: 0,
        };
        let mut k = key();
        k.sims = SimInfo::from_payload(&PhoneAccounts {
            accounts: vec![account("a#1", 0), account("a#2", 1)],
            default_key: None,
            ask: true,
        });
        let sections = build_sections(&k);
        let sim = sections
            .iter()
            .find(|s| s.title == "SIM")
            .expect("SIM section");
        assert_eq!(
            sim.rows[0].control,
            Control::Link {
                value: Some("Ask every time".into()),
                icon: Icon::LucideExternalLink
            }
        );
    }

    #[test]
    fn the_flip_row_is_dimmed_without_a_sensor() {
        let mut k = key();
        k.facts.flip_supported = false;
        let sections = build_sections(&k);
        let flip = &sections[0].rows[1];
        assert_eq!(flip.id, RowId::Flip);
        assert!(!flip.enabled);
        assert_eq!(flip.footnote, Some("Not supported on this phone"));
        assert_eq!(
            build_sections(&key())[0].rows[1].footnote,
            Some("Face down while ringing")
        );
    }

    #[test]
    fn display_rows_dim_when_no_contact_has_an_alternative_name() {
        let mut k = key();
        k.names_differ = false;
        let display = build_sections(&k)
            .into_iter()
            .find(|s| s.title == "Display")
            .expect("present");
        assert!(!display.rows[0].enabled && !display.rows[1].enabled);
        assert!(display.rows[2].id == RowId::NamePreview);
    }

    #[test]
    fn desktop_shortcuts_are_inert_and_say_why() {
        let mut k = key();
        k.on_android = false;
        let s = build_sections(&k);
        let shortcuts = s
            .iter()
            .find(|s| s.title == "System shortcuts")
            .expect("present");
        for row in &shortcuts.rows {
            assert!(!row.enabled);
            assert_eq!(
                row.control,
                Control::Info("Not available on this platform".into())
            );
        }
    }

    #[test]
    fn a_refused_import_adds_the_red_row() {
        let mut k = key();
        k.import_error = Some("This isn't a Rovsti Phone backup.");
        let data = build_sections(&k)
            .into_iter()
            .find(|s| s.title == "Data")
            .expect("present");
        assert_eq!(data.rows.len(), 3);
        assert_eq!(data.rows[2].id, RowId::ImportError);
    }

    #[test]
    fn toggle_values_follow_the_settings_and_the_system() {
        let mut s = Settings::default();
        assert!(toggle_value(RowId::Proximity, &s, true));
        assert!(!toggle_value(RowId::Flip, &s, true));
        assert!(toggle_value(RowId::KeypadTones, &s, true));
        assert!(!toggle_value(RowId::KeypadTones, &s, false));
        s.keypad_tones = Some(false);
        assert!(!toggle_value(RowId::KeypadTones, &s, true));
    }

    #[test]
    fn segments_read_the_settings() {
        let mut s = Settings::default();
        assert_eq!(segment_value(RowId::Haptics, &s), 2);
        s.haptics = HapticsLevel::Off;
        s.sort_by = SortBy::LastName;
        s.name_format = NameFormat::LastFirst;
        assert_eq!(segment_value(RowId::Haptics, &s), 0);
        assert_eq!(segment_value(RowId::SortBy, &s), 1);
        assert_eq!(segment_value(RowId::NameFormat, &s), 1);
    }

    #[test]
    fn the_preview_line_follows_the_format() {
        assert_eq!(preview_name(NameFormat::FirstLast), "Ayşe Yılmaz");
        assert_eq!(preview_name(NameFormat::LastFirst), "Yılmaz, Ayşe");
    }

    #[test]
    fn row_ids_are_unique_and_in_range() {
        let all = [
            RowId::Proximity,
            RowId::Flip,
            RowId::VibrateConnect,
            RowId::VibrateEnd,
            RowId::KeypadTones,
            RowId::Haptics,
            RowId::Ringtone,
            RowId::SortBy,
            RowId::NameFormat,
            RowId::NamePreview,
            RowId::Theme,
            RowId::SimDefault,
            RowId::Export,
            RowId::Import,
            RowId::ImportError,
            RowId::Forwarding,
            RowId::Accessibility,
            RowId::DefaultApps,
            RowId::EmergencyInfo,
            RowId::Hearing,
        ];
        assert_eq!(all.len(), RowId::COUNT);
        for (i, a) in all.iter().enumerate() {
            assert_eq!(a.index(), i);
            assert!(all.iter().skip(i + 1).all(|b| b.key() != a.key()));
        }
    }
}
