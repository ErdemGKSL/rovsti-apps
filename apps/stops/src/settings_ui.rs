//! The Settings tab: a list of sections, and the pages it opens: History
//! with its statistics, the arriving-soon WhatsApp message, and the shared
//! WhatsApp settings.
//!
//! What the screens say (durations, distances, a chat's title, the notice
//! preview, the summary lines) is computed by plain functions with unit
//! tests. The controller methods only move that between the UI, `backend`
//! and the gateway.

use std::time::Duration;

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_android::{Error as AndroidError, trip, trip::AlarmAction, xiaomi};
use rovsti_geo::{
    NoticePrefs, NoticeWhen, Outcome, Stats, StopsPrefs, TripRecord,
    notice::DEFAULT_TEMPLATE,
    tiles::{DEFAULT_TILE_URL, TileSource, TileUrlError},
};
use rovsti_tiles::TileError;
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, glass_pill, large_title, list_row, section},
    field::field_box,
    theme,
    tokens::Theme,
};
use rovsti_wa_proto::ChatSummary;

use crate::{
    StopsApp, backend,
    store::now_ms,
    transit::{date_text, note},
};

/// Records drawn in the History list (the file keeps up to 500).
const SHOWN_RECORDS: usize = 100;
/// Chats offered in the picker.
const MAX_CHATS: usize = 60;
/// The stop and ETA the template preview uses.
const PREVIEW_STOP: &str = "Kadikoy";
const PREVIEW_ETA: Duration = Duration::from_secs(4 * 60);

/// Which Settings screen is showing.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum SettingsPage {
    #[default]
    Main,
    History,
    Notice,
    WhatsApp,
    MapTiles,
}

/// One chat the arriving-soon message can go to.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChatRow {
    pub jid: String,
    pub title: String,
    pub group: bool,
}

/// The chat list of the notice page.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum ChatList {
    #[default]
    Idle,
    Loading,
    Ready(Vec<ChatRow>),
    Failed(String),
}

// ---- pure --------------------------------------------------------------------------

/// "under 1 min", "18 min", "1 h 05 min".
pub fn duration_text(ms: u64) -> String {
    let minutes = ms / 60_000;
    match minutes {
        0 => "under 1 min".to_owned(),
        1..=59 => format!("{minutes} min"),
        _ => format!("{} h {:02} min", minutes / 60, minutes % 60),
    }
}

/// "850 m" under a kilometre, then "3.4 km", "152 km" from 100 km.
pub fn distance_text(metres: f64) -> String {
    let metres = metres.max(0.0);
    if metres < 1_000.0 {
        format!("{} m", metres.round() as u32)
    } else if metres < 100_000.0 {
        format!("{:.1} km", metres / 1_000.0)
    } else {
        format!("{} km", (metres / 1_000.0).round() as u32)
    }
}

pub fn outcome_text(outcome: Outcome) -> &'static str {
    match outcome {
        Outcome::Arrived => "Arrived",
        Outcome::Cancelled => "Cancelled",
    }
}

/// "A – B" for a trip with two points, the single label of a legacy record.
pub fn record_pair(record: &TripRecord) -> String {
    match (&record.a, &record.b) {
        (Some(a), Some(b)) => format!(
            "{} – {}",
            crate::pick::row_label(&a.label),
            crate::pick::row_label(&b.label)
        ),
        _ if record.label.is_empty() => "Trip".to_owned(),
        _ => record.label.clone(),
    }
}

/// "A – B  ·  5 Nov  ·  18 min  ·  3.4 km" under a history row's title.
pub fn record_subtitle(record: &TripRecord, now_ms: i64) -> String {
    format!(
        "{}  ·  {}  ·  {}  ·  {}",
        record_pair(record),
        date_text(record.ended_ms, now_ms),
        duration_text(record.duration_ms()),
        distance_text(record.travelled_m)
    )
}

/// The statistics card as (label, value) rows.
pub fn stats_rows(stats: &Stats) -> Vec<(String, String)> {
    let mut rows = vec![
        ("Trips".to_owned(), stats.trips.to_string()),
        (
            "Arrived".to_owned(),
            format!("{}%", stats.arrived_percent()),
        ),
        ("Total distance".to_owned(), distance_text(stats.total_m)),
        ("Average trip".to_owned(), duration_text(stats.avg_ms)),
        ("Last 30 days".to_owned(), stats.last_30_days.to_string()),
    ];
    for (i, (label, count)) in stats.top.iter().enumerate() {
        let name = if i == 0 { "Top places" } else { "" };
        rows.push((name.to_owned(), format!("{label}  ·  {count}")));
    }
    rows
}

/// A chat's title: its name, else its number, else "Group".
fn chat_title(chat: &ChatSummary) -> String {
    if let Some(name) = chat
        .name
        .as_deref()
        .map(str::trim)
        .filter(|n| !n.is_empty())
    {
        return name.to_owned();
    }
    let user = chat.jid.split('@').next().unwrap_or_default();
    let user = user.split(':').next().unwrap_or_default();
    if chat.is_group || user.is_empty() {
        "Group".to_owned()
    } else if user.chars().all(|c| c.is_ascii_digit()) {
        format!("+{user}")
    } else {
        user.to_owned()
    }
}

/// The picker rows: the gateway's order, groups kept and marked, capped.
pub fn chat_rows(chats: &[ChatSummary]) -> Vec<ChatRow> {
    chats
        .iter()
        .take(MAX_CHATS)
        .map(|c| ChatRow {
            jid: c.jid.clone(),
            title: chat_title(c),
            group: c.is_group,
        })
        .collect()
}

/// The message as it would read for a four-minute ETA to "Kadikoy".
pub fn notice_preview(template: &str) -> String {
    rovsti_geo::notice::render(template, PREVIEW_STOP, Some(PREVIEW_ETA))
}

/// The line under the "Arriving-soon message" row.
pub fn notice_summary(prefs: &StopsPrefs, connected: bool) -> String {
    match &prefs.notice {
        _ if !connected => "Needs WhatsApp".to_owned(),
        Some(n) if !n.chat.is_empty() => {
            let name = if n.chat_name.is_empty() {
                "chat"
            } else {
                &n.chat_name
            };
            format!("{name}  ·  {}", n.when.label().to_lowercase())
        }
        _ => "Off".to_owned(),
    }
}

/// The host of the tile server in use ("tiles.versatiles.org").
pub fn tile_server_summary(prefs: &StopsPrefs) -> String {
    let source = prefs.tile_source();
    let template = source.template();
    let rest = template.split_once("://").map_or(template, |(_, r)| r);
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let host = rest[..end].rsplit('@').next().unwrap_or_default();
    if host.is_empty() { template } else { host }.to_owned()
}

/// Why a typed tile URL was refused (no network involved).
pub fn tile_url_error_text(error: &TileUrlError) -> &'static str {
    match error {
        TileUrlError::Empty => "Enter a tile URL.",
        TileUrlError::Scheme => "The URL must start with http:// or https://.",
        TileUrlError::Missing("{z}") => "The URL needs {z} exactly once.",
        TileUrlError::Missing("{x}") => "The URL needs {x} exactly once.",
        TileUrlError::Missing(_) => "The URL needs {y} exactly once.",
        TileUrlError::Whitespace => "The URL must not contain spaces.",
        TileUrlError::TooLong => "The URL is too long.",
    }
}

/// The pill after "Test and save".
pub fn tile_test_text(result: &Result<usize, TileError>) -> String {
    match result {
        Ok(1) => "Map server works (1 layer)".to_owned(),
        Ok(n) => format!("Map server works ({n} layers)"),
        Err(error) => format!("Map server not saved: {error}"),
    }
}

pub fn exact_alarm_text(allowed: Option<bool>) -> &'static str {
    match allowed {
        Some(true) => "Allowed",
        Some(false) => "Not allowed: a snooze may ring late",
        None => "Checked on the phone",
    }
}

pub fn battery_text(unrestricted: Option<bool>) -> &'static str {
    match unrestricted {
        Some(true) => "Allowed",
        Some(false) => "Limited: the system may pause tracking",
        None => "Checked on the phone",
    }
}

/// What to tell the user when an Android action did not simply work.
pub fn android_notice(what: &str, result: &Result<bool, AndroidError>) -> Option<String> {
    match result {
        Ok(true) => None,
        Ok(false) => Some(format!("{what}: no app answered.")),
        Err(AndroidError::Unsupported) => Some(format!("{what} works on the phone only.")),
        Err(error) => Some(format!("{what} failed: {error}.")),
    }
}

// ---- UI ----------------------------------------------------------------------------

fn on_off(on: bool) -> SharedString {
    if on { "On" } else { "Off" }.into()
}

impl StopsApp {
    /// The Settings tab: the list, or the page that was opened from it.
    pub(crate) fn settings_tab(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        match self.settings_page {
            SettingsPage::Main => self.settings_main(theme, cx),
            SettingsPage::History => {
                let body = self.history_body(theme, cx);
                self.settings_subpage(theme, "History", body, cx)
            }
            SettingsPage::Notice => {
                let body = self.notice_body(theme, window, cx);
                self.settings_subpage(theme, "Arriving soon", body, cx)
            }
            SettingsPage::WhatsApp => {
                let body = self.whatsapp.clone().into_any_element();
                self.settings_subpage(theme, "WhatsApp", body, cx)
            }
            SettingsPage::MapTiles => {
                let body = self.tiles_body(theme, window, cx);
                self.settings_subpage(theme, "Map tiles", body, cx)
            }
        }
    }

    /// A back button, the title, then the page.
    fn settings_subpage(
        &self,
        theme: Theme,
        title: &'static str,
        body: AnyElement,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        div()
            .child(
                div().px(px(8.0)).pt(px(48.0)).child(
                    glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                        .id("settings-back")
                        .on_click(
                            cx.listener(|this, _, _, cx| {
                                this.open_settings(SettingsPage::Main, cx)
                            }),
                        ),
                ),
            )
            .child(large_title(theme, title, None))
            .child(body)
            .into_any_element()
    }

    /// Shows a Settings page. Leaving the History page forgets a half-done Clear.
    pub(crate) fn open_settings(&mut self, page: SettingsPage, cx: &mut Context<Self>) {
        self.settings_page = page;
        self.confirm_clear = false;
        self.page.restart();
        match page {
            SettingsPage::Notice => {
                self.sync_template_field(cx);
                self.load_chats(false, cx);
            }
            SettingsPage::MapTiles => self.sync_tile_field(cx),
            SettingsPage::Main => self.refresh_preflight(cx),
            _ => {}
        }
        cx.notify();
    }

    fn settings_main(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let pre = self.preflight;
        let connected = self.whatsapp.read(cx).is_connected();

        let alarm = vec![
            list_row(theme, None, "Sound", None, Some(on_off(self.prefs.sound)))
                .id("set-sound")
                .on_click(cx.listener(|this, _, _, cx| {
                    if this.prefs.toggle_sound() {
                        this.save_prefs(cx);
                    } else {
                        this.show_pill(
                            "Keep sound or vibration on, or the alarm is silent.",
                            None,
                            cx,
                        );
                    }
                    cx.notify();
                }))
                .into_any_element(),
            list_row(
                theme,
                None,
                "Vibration",
                None,
                Some(on_off(self.prefs.vibrate)),
            )
            .id("set-vibrate")
            .on_click(cx.listener(|this, _, _, cx| {
                if this.prefs.toggle_vibrate() {
                    this.save_prefs(cx);
                } else {
                    this.show_pill(
                        "Keep sound or vibration on, or the alarm is silent.",
                        None,
                        cx,
                    );
                }
                cx.notify();
            }))
            .into_any_element(),
            list_row(
                theme,
                None,
                "Snooze",
                None,
                Some(format!("{} min", self.prefs.snooze_min.clamp(1, 15)).into()),
            )
            .id("set-snooze")
            .on_click(cx.listener(|this, _, _, cx| {
                this.prefs.snooze_min = this.prefs.next_snooze();
                this.save_prefs(cx);
                cx.notify();
            }))
            .into_any_element(),
            list_row(
                theme,
                None,
                "Test alarm",
                Some("Rings once with your sound and vibration.".into()),
                Some("Ring".into()),
            )
            .id("set-test-alarm")
            .on_click(cx.listener(|this, _, _, cx| {
                this.run_android("Test alarm", || trip::alarm(AlarmAction::Test), cx);
            }))
            .into_any_element(),
        ];

        let mut reliability = vec![
            list_row(
                theme,
                None,
                "Background activity",
                Some(battery_text(pre.unrestricted_battery).into()),
                (pre.unrestricted_battery != Some(true)).then(|| "Allow".into()),
            )
            .id("set-battery")
            .on_click(cx.listener(|this, _, _, cx| {
                this.run_android(
                    "Battery settings",
                    xiaomi::request_ignore_battery_optimizations,
                    cx,
                );
            }))
            .into_any_element(),
        ];
        if pre.xiaomi_os {
            reliability.push(
                list_row(
                    theme,
                    None,
                    "Autostart",
                    Some("Xiaomi stops background apps unless this is on.".into()),
                    Some("Open".into()),
                )
                .id("set-autostart")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.run_android(
                        "Autostart settings",
                        || xiaomi::open(xiaomi::Setting::Autostart),
                        cx,
                    );
                }))
                .into_any_element(),
            );
        }
        reliability.push(
            list_row(
                theme,
                None,
                "Exact alarms",
                Some(exact_alarm_text(pre.exact_alarms).into()),
                None,
            )
            .into_any_element(),
        );

        let history_summary = match self.history.records.len() {
            0 => "No trips yet".to_owned(),
            1 => "1 trip".to_owned(),
            n => format!("{n} trips"),
        };
        let more = vec![
            list_row(
                theme,
                None,
                "Arriving-soon message",
                Some(notice_summary(&self.prefs, connected).into()),
                Some("Open".into()),
            )
            .id("set-notice")
            .on_click(cx.listener(|this, _, _, cx| this.open_settings(SettingsPage::Notice, cx)))
            .into_any_element(),
            list_row(
                theme,
                None,
                "History",
                Some(history_summary.into()),
                Some("Open".into()),
            )
            .id("set-history")
            .on_click(cx.listener(|this, _, _, cx| this.open_settings(SettingsPage::History, cx)))
            .into_any_element(),
            list_row(
                theme,
                None,
                "Map tiles",
                Some(tile_server_summary(&self.prefs).into()),
                Some("Open".into()),
            )
            .id("set-tiles")
            .on_click(cx.listener(|this, _, _, cx| this.open_settings(SettingsPage::MapTiles, cx)))
            .into_any_element(),
            list_row(
                theme,
                None,
                "WhatsApp",
                Some(
                    if connected {
                        "Connected"
                    } else {
                        "Not connected"
                    }
                    .into(),
                ),
                Some("Open".into()),
            )
            .id("set-whatsapp")
            .on_click(cx.listener(|this, _, _, cx| this.open_settings(SettingsPage::WhatsApp, cx)))
            .into_any_element(),
        ];

        div()
            .flex()
            .flex_col()
            .gap(px(24.0))
            .child(section(theme, Some("Alarm".into()), alarm))
            .child(section(theme, Some("Battery".into()), reliability))
            .child(self.transit_section(theme, cx))
            .child(section(theme, None, more))
            .into_any_element()
    }

    /// Runs an Android call off the UI thread; a failure shows on the pill.
    pub(crate) fn run_android(
        &mut self,
        what: &'static str,
        action: impl FnOnce() -> rovsti_android::Result<bool> + Send + 'static,
        cx: &mut Context<Self>,
    ) {
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { action() })
                .await;
            if let Some(text) = android_notice(what, &result) {
                this.update(cx, |this, cx| this.show_pill(text, None, cx))
                    .ok();
            }
        })
        .detach();
    }

    // ---- History ---------------------------------------------------------------------

    fn history_body(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        if self.history.records.is_empty() {
            return empty_state(
                theme,
                "No Trips Yet",
                "Finished and cancelled trips are listed here, with some statistics.",
            )
            .into_any_element();
        }
        let now = now_ms();
        let colors = theme::colors(theme);
        let stats = stats_rows(&self.history.stats(now));
        let stats = stats.into_iter().map(|(label, value)| {
            list_row(theme, None, label, None, Some(value.into())).into_any_element()
        });

        let rows = self
            .history
            .records
            .iter()
            .take(SHOWN_RECORDS)
            .map(|r| {
                list_row(
                    theme,
                    None,
                    "Trip",
                    Some(record_subtitle(r, now).into()),
                    Some(outcome_text(r.outcome).into()),
                )
                .into_any_element()
            })
            .collect::<Vec<_>>();

        let (clear_label, clear_color) = if self.confirm_clear {
            ("Tap again to clear the history", colors.red)
        } else {
            ("Clear history", colors.red)
        };
        let mut page = div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(section(theme, Some("Statistics".into()), stats))
            .child(section(theme, Some("Trips".into()), rows));
        if self.history.records.len() > SHOWN_RECORDS {
            page = page.child(note(
                theme,
                format!("The latest {SHOWN_RECORDS} trips are listed."),
                false,
            ));
        }
        page.child(
            div().flex().justify_center().child(
                glass_pill(theme, clear_label)
                    .id("history-clear")
                    .px(px(24.0))
                    .h(px(48.0))
                    .text_color(clear_color)
                    .on_click(cx.listener(|this, _, _, cx| this.clear_history(cx))),
            ),
        )
        .into_any_element()
    }

    /// Two taps: the first asks, the second clears.
    fn clear_history(&mut self, cx: &mut Context<Self>) {
        if !self.confirm_clear {
            self.confirm_clear = true;
            cx.notify();
            return;
        }
        self.confirm_clear = false;
        self.history.records.clear();
        if let Some(dir) = self.dir.clone() {
            cx.background_executor()
                .spawn(async move { backend::clear_history(&dir) })
                .detach();
        }
        cx.notify();
    }

    // ---- Arriving-soon message ---------------------------------------------------------

    fn notice_body(&mut self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        if !self.whatsapp.read(cx).is_connected() {
            return div()
                .flex()
                .flex_col()
                .gap(px(8.0))
                .child(section(
                    theme,
                    None,
                    [list_row(
                        theme,
                        None,
                        "Link WhatsApp in Settings > WhatsApp to use this",
                        Some("Stops texts a chat shortly before you arrive.".into()),
                        Some("Open".into()),
                    )
                    .id("notice-link")
                    .on_click(
                        cx.listener(|this, _, _, cx| {
                            this.open_settings(SettingsPage::WhatsApp, cx)
                        }),
                    )
                    .into_any_element()],
                ))
                .into_any_element();
        }
        let colors = theme::colors(theme);
        let chosen = self.prefs.notice.clone().filter(|n| !n.chat.is_empty());

        // Where to send it.
        let mut chat_rows: Vec<AnyElement> = Vec::new();
        match &self.chats {
            ChatList::Idle | ChatList::Loading => chat_rows
                .push(list_row(theme, None, "Loading chats...", None, None).into_any_element()),
            ChatList::Failed(reason) => chat_rows.push(
                list_row(
                    theme,
                    None,
                    "Could not load your chats",
                    Some(reason.clone().into()),
                    Some("Retry".into()),
                )
                .id("chats-retry")
                .on_click(cx.listener(|this, _, _, cx| this.load_chats(true, cx)))
                .into_any_element(),
            ),
            ChatList::Ready(rows) if rows.is_empty() => chat_rows.push(
                list_row(
                    theme,
                    None,
                    "No chats yet",
                    Some("Chats appear after history sync.".into()),
                    None,
                )
                .into_any_element(),
            ),
            ChatList::Ready(rows) => {
                for (i, row) in rows.iter().enumerate() {
                    let selected = chosen.as_ref().is_some_and(|n| n.chat == row.jid);
                    let picked = row.clone();
                    chat_rows.push(
                        list_row(
                            theme,
                            None,
                            row.title.clone(),
                            row.group.then(|| "Group".into()),
                            selected.then(|| "Selected".into()),
                        )
                        .id(("notice-chat", i))
                        .on_click(cx.listener(move |this, _, _, cx| this.choose_chat(&picked, cx)))
                        .into_any_element(),
                    );
                }
            }
        }

        let mut page = div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(note(
                theme,
                "Sends one WhatsApp message through your gateway when you are close. Nothing else leaves the phone.",
                false,
            ))
            .child(section(theme, Some("Send to".into()), chat_rows));

        if let Some(prefs) = chosen {
            let text = self.template_field.read(cx).text().to_owned();
            let check = rovsti_geo::notice::validate(&text);
            let shown = if check.is_ok() {
                text
            } else {
                prefs.template.clone()
            };
            let mut message = div()
                .flex()
                .flex_col()
                .gap(px(6.0))
                .child(div().px(px(16.0)).child(field_box(
                    theme,
                    "notice-template",
                    &self.template_field,
                    DEFAULT_TEMPLATE,
                    window,
                    cx,
                )));
            message = match check {
                Err(error) => message.child(note(
                    theme,
                    format!("{error}. The last good text is kept."),
                    true,
                )),
                Ok(()) => message.child(note(
                    theme,
                    "Use {stop} for the stop and {min} for the minutes left.",
                    false,
                )),
            };
            message = message.child(
                div()
                    .mx(px(20.0))
                    .p(px(12.0))
                    .rounded(px(14.0))
                    .bg(colors.secondary_background)
                    .text_color(colors.label)
                    .child(notice_preview(&shown)),
            );

            let when = NoticeWhen::ALL
                .into_iter()
                .enumerate()
                .map(|(i, option)| {
                    list_row(
                        theme,
                        None,
                        option.label(),
                        None,
                        (prefs.when == option).then(|| "Selected".into()),
                    )
                    .id(("notice-when", i))
                    .on_click(cx.listener(move |this, _, _, cx| this.set_notice_when(option, cx)))
                    .into_any_element()
                })
                .collect::<Vec<_>>();

            page = page
                .child(div().flex().flex_col().child(message))
                .child(section(theme, Some("When".into()), when))
                .child(section(
                    theme,
                    None,
                    [list_row(theme, None, "Turn off", None, None)
                        .id("notice-off")
                        .text_color(colors.red)
                        .on_click(cx.listener(|this, _, _, cx| this.turn_notice_off(cx)))
                        .into_any_element()],
                ));
        }
        page.into_any_element()
    }

    // ---- Map tiles -----------------------------------------------------------------------

    fn tiles_body(&mut self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let default = TileSource::default_source();
        let testing = self.tile_testing;
        let save = list_row(
            theme,
            None,
            "Test and save",
            Some("Downloads one tile first. A server that fails is not saved.".into()),
            Some(if testing { "Testing..." } else { "Test" }.into()),
        )
        .id("tiles-save")
        .on_click(cx.listener(|this, _, _, cx| this.test_and_save_tiles(cx)))
        .into_any_element();
        let reset = list_row(
            theme,
            None,
            "Reset to default",
            Some(default.template().to_owned().into()),
            None,
        )
        .id("tiles-reset")
        .on_click(cx.listener(|this, _, _, cx| this.reset_tiles(cx)))
        .into_any_element();
        div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(note(
                theme,
                "Map tiles come from this server and are kept on the phone. Any VersaTiles-compatible Shortbread vector tile server works.",
                false,
            ))
            .child(div().px(px(16.0)).child(field_box(
                theme,
                "tiles-url",
                &self.tile_field,
                DEFAULT_TILE_URL,
                window,
                cx,
            )))
            .child(note(
                theme,
                "Use {z}, {x} and {y} for the tile position.",
                false,
            ))
            .child(section(theme, None, [save, reset]))
            .into_any_element()
    }

    /// Puts the saved server URL into the text field.
    fn sync_tile_field(&mut self, cx: &mut Context<Self>) {
        let template = self.prefs.tile_source().template().to_owned();
        self.tile_field
            .update(cx, |field, cx| field.set(&template, cx));
    }

    /// Parses the field (no network on a parse error), tests the server on the
    /// background executor, and only a working server is saved.
    fn test_and_save_tiles(&mut self, cx: &mut Context<Self>) {
        if self.tile_testing {
            return;
        }
        let text = self.tile_field.read(cx).text().trim().to_owned();
        let source = match TileSource::parse(&text) {
            Ok(source) => source,
            Err(error) => {
                self.show_pill(tile_url_error_text(&error), None, cx);
                return;
            }
        };
        self.tile_testing = true;
        cx.notify();
        let client = self.map_tiles.client();
        let tested = source.clone();
        let task = cx
            .background_executor()
            .spawn(async move { client.test_server(&tested) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.tile_testing = false;
                if result.is_ok() {
                    this.use_tile_source(source, cx);
                }
                this.show_pill(tile_test_text(&result), None, cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn reset_tiles(&mut self, cx: &mut Context<Self>) {
        let source = TileSource::default_source();
        self.use_tile_source(source, cx);
        self.sync_tile_field(cx);
        self.show_pill("Map server reset to default", None, cx);
    }

    /// Saves `source` as the setting and switches the map to it.
    fn use_tile_source(&mut self, source: TileSource, cx: &mut Context<Self>) {
        self.prefs.set_tile_url(&source);
        self.save_prefs(cx);
        self.map_tiles.set_source(source, cx);
    }

    /// Puts the saved template into the text field.
    fn sync_template_field(&mut self, cx: &mut Context<Self>) {
        let template = self
            .prefs
            .notice
            .as_ref()
            .map_or(DEFAULT_TEMPLATE, |n| n.template.as_str())
            .to_owned();
        self.template_field
            .update(cx, |field, cx| field.set(&template, cx));
    }

    /// The text field changed: a valid text becomes the template.
    pub(crate) fn on_template_edited(&mut self, cx: &mut Context<Self>) {
        if self.settings_page != SettingsPage::Notice {
            return;
        }
        let text = self.template_field.read(cx).text().trim().to_owned();
        let Some(prefs) = self.prefs.notice.as_mut() else {
            return;
        };
        if prefs.template == text {
            return;
        }
        if prefs.set_template(&text).is_ok() {
            self.save_prefs(cx);
        }
        cx.notify();
    }

    fn choose_chat(&mut self, row: &ChatRow, cx: &mut Context<Self>) {
        let created = self.prefs.notice.is_none();
        let prefs = self.prefs.notice.get_or_insert_with(NoticePrefs::default);
        prefs.chat = row.jid.clone();
        prefs.chat_name = row.title.clone();
        if created {
            self.sync_template_field(cx);
        }
        self.save_prefs(cx);
        cx.notify();
    }

    fn set_notice_when(&mut self, when: NoticeWhen, cx: &mut Context<Self>) {
        if let Some(prefs) = self.prefs.notice.as_mut() {
            prefs.when = when;
            self.save_prefs(cx);
            cx.notify();
        }
    }

    fn turn_notice_off(&mut self, cx: &mut Context<Self>) {
        self.prefs.notice = None;
        self.save_prefs(cx);
        self.sync_template_field(cx);
        cx.notify();
    }

    /// Reads the chat list from the gateway on the background executor.
    fn load_chats(&mut self, force: bool, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        match &self.chats {
            ChatList::Loading => return,
            ChatList::Ready(_) if !force => return,
            _ => {}
        }
        self.chats = ChatList::Loading;
        cx.notify();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.chats() });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.chats = match result {
                    Ok(chats) => ChatList::Ready(chat_rows(&chats)),
                    Err(error) => ChatList::Failed(error.to_string()),
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use rovsti_geo::Coord;

    fn chat(jid: &str, name: Option<&str>, group: bool) -> ChatSummary {
        ChatSummary {
            jid: jid.into(),
            name: name.map(str::to_owned),
            last: None,
            unread: 0,
            is_group: group,
        }
    }

    #[test]
    fn tile_server_summary_is_the_host() {
        let mut prefs = StopsPrefs::default();
        assert_eq!(tile_server_summary(&prefs), "tiles.versatiles.org");
        let m = TileSource::parse("https://user:pw@maps.maltepe.edu.tr:8443/tiles/osm/{z}/{x}/{y}")
            .unwrap();
        prefs.set_tile_url(&m);
        assert_eq!(tile_server_summary(&prefs), "maps.maltepe.edu.tr:8443");
    }

    #[test]
    fn tile_url_errors_have_texts() {
        let bad = [
            "",
            "ftp://x/{z}/{x}/{y}",
            "https://x/{x}/{y}",
            "https://x/{z}/{x} {y}",
        ];
        for text in bad {
            let error = TileSource::parse(text).unwrap_err();
            assert!(tile_url_error_text(&error).ends_with('.'), "{text}");
        }
        assert_eq!(
            tile_url_error_text(&TileUrlError::Missing("{x}")),
            "The URL needs {x} exactly once."
        );
    }

    #[test]
    fn tile_test_texts() {
        assert_eq!(tile_test_text(&Ok(12)), "Map server works (12 layers)");
        assert_eq!(tile_test_text(&Ok(1)), "Map server works (1 layer)");
        assert!(tile_test_text(&Err(TileError::Status(404))).contains("404"));
    }

    #[test]
    fn durations_read_naturally() {
        assert_eq!(duration_text(0), "under 1 min");
        assert_eq!(duration_text(59_999), "under 1 min");
        assert_eq!(duration_text(18 * 60_000), "18 min");
        assert_eq!(duration_text(65 * 60_000), "1 h 05 min");
        assert_eq!(duration_text(120 * 60_000), "2 h 00 min");
    }

    #[test]
    fn distances_switch_unit() {
        assert_eq!(distance_text(-5.0), "0 m");
        assert_eq!(distance_text(849.6), "850 m");
        assert_eq!(distance_text(3_449.0), "3.4 km");
        assert_eq!(distance_text(152_400.0), "152 km");
    }

    fn legacy_record() -> TripRecord {
        TripRecord {
            label: "Kadikoy".into(),
            destination: Some(Coord::new(41.0, 29.0)),
            started_ms: 1_700_000_000_000,
            ended_ms: 1_700_000_000_000 + 18 * 60_000,
            travelled_m: 3_400.0,
            ..TripRecord::default()
        }
    }

    #[test]
    fn a_legacy_record_row_shows_its_single_label() {
        // 14 Nov 2023, seen from 2023.
        assert_eq!(
            record_subtitle(&legacy_record(), 1_700_100_000_000),
            "Kadikoy  ·  14 Nov  ·  18 min  ·  3.4 km"
        );
        let unnamed = TripRecord {
            label: String::new(),
            ..legacy_record()
        };
        assert_eq!(record_pair(&unnamed), "Trip");
        assert_eq!(outcome_text(Outcome::Cancelled), "Cancelled");
    }

    #[test]
    fn a_record_row_shows_both_points() {
        let end = |label: &str, lon: f64| rovsti_geo::Endpoint {
            label: label.into(),
            coord: Coord::new(41.0, lon),
        };
        let r = TripRecord {
            label: "Home – Work".into(),
            a: Some(end("Home", 29.0)),
            b: Some(end("Work", 29.01)),
            destination: None,
            ..legacy_record()
        };
        assert_eq!(
            record_subtitle(&r, 1_700_100_000_000),
            "Home – Work  ·  14 Nov  ·  18 min  ·  3.4 km"
        );
    }

    #[test]
    fn stats_card_rows() {
        let stats = Stats {
            trips: 4,
            arrived: 3,
            cancelled: 1,
            total_m: 12_300.0,
            total_ms: 4 * 600_000,
            avg_ms: 600_000,
            last_30_days: 2,
            top: vec![("Home".into(), 3), ("Work".into(), 1)],
        };
        let rows = stats_rows(&stats);
        assert_eq!(rows[0], ("Trips".into(), "4".into()));
        assert_eq!(rows[1], ("Arrived".into(), "75%".into()));
        assert_eq!(rows[2].1, "12.3 km");
        assert_eq!(rows[3].1, "10 min");
        assert_eq!(rows[4].1, "2");
        assert_eq!(rows[5], ("Top places".into(), "Home  ·  3".into()));
        assert_eq!(rows[6], (String::new(), "Work  ·  1".into()));
    }

    #[test]
    fn chat_titles_mark_groups_and_numbers() {
        let chats = [
            chat("1@g.us", Some("Family"), true),
            chat("905321234567@s.whatsapp.net", None, false),
            chat("905321234567:12@s.whatsapp.net", Some("  "), false),
            chat("2@g.us", None, true),
            chat("Ann@lid", None, false),
        ];
        let rows = chat_rows(&chats);
        let titles: Vec<_> = rows.iter().map(|r| r.title.as_str()).collect();
        assert_eq!(
            titles,
            ["Family", "+905321234567", "+905321234567", "Group", "Ann"]
        );
        assert!(rows[0].group && !rows[1].group);
    }

    #[test]
    fn chat_list_is_capped() {
        let chats: Vec<_> = (0..200)
            .map(|i| chat(&format!("{i}@s.whatsapp.net"), None, false))
            .collect();
        assert_eq!(chat_rows(&chats).len(), MAX_CHATS);
    }

    #[test]
    fn preview_fills_the_placeholders() {
        assert_eq!(
            notice_preview("Arriving at {stop} in about {min} min."),
            "Arriving at Kadikoy in about 4 min."
        );
    }

    #[test]
    fn notice_summary_lines() {
        let mut prefs = StopsPrefs::default();
        assert_eq!(notice_summary(&prefs, true), "Off");
        assert_eq!(notice_summary(&prefs, false), "Needs WhatsApp");
        prefs.notice = Some(NoticePrefs {
            chat: "c".into(),
            chat_name: "Family".into(),
            when: NoticeWhen::MinutesBefore(10),
            ..NoticePrefs::default()
        });
        assert_eq!(
            notice_summary(&prefs, true),
            "Family  ·  10 min before the circle"
        );
        assert_eq!(notice_summary(&prefs, false), "Needs WhatsApp");
    }

    #[test]
    fn android_failures_are_worded() {
        assert_eq!(android_notice("Test alarm", &Ok(true)), None);
        assert_eq!(
            android_notice("Test alarm", &Err(AndroidError::Unsupported)).unwrap(),
            "Test alarm works on the phone only."
        );
        assert!(
            android_notice("Test alarm", &Ok(false))
                .unwrap()
                .contains("no app")
        );
    }

    #[test]
    fn status_lines() {
        assert_eq!(exact_alarm_text(Some(true)), "Allowed");
        assert!(exact_alarm_text(Some(false)).starts_with("Not allowed"));
        assert_eq!(battery_text(None), "Checked on the phone");
    }
}
