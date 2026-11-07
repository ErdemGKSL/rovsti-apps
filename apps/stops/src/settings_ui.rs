//! The Settings tab: a list of sections, and the pages it opens: History
//! with its statistics, the arriving-soon WhatsApp message, and the shared
//! WhatsApp settings.
//!
//! What the screens say (durations, distances, a chat's title, the notice
//! preview, the summary lines) is computed by plain functions with unit
//! tests. The controller methods only move that between the UI, `backend`
//! and the gateway.

use std::time::Duration;

use gpui::HapticFeedback;
use gpui::prelude::FluentBuilder as _;
use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, list, px,
};
use rovsti_android::{Error as AndroidError, trip, trip::AlarmPick, xiaomi};
use rovsti_geo::{
    NoticePrefs, NoticeWhen, Outcome, Stats, StopsPrefs, TripRecord,
    alarm::MIN_ALARM_VOLUME_PCT,
    next_volume,
    notice::DEFAULT_TEMPLATE,
    tiles::{DEFAULT_TILE_URL, TileSource, TileUrlError},
};
use rovsti_tiles::TileError;
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, glass_pill, large_title, list_row, section},
    field::field_box,
    haptics, theme,
    tokens::Theme,
};
use rovsti_wa_proto::ChatSummary;

use crate::{
    Page, StopsApp, backend, lists,
    store::now_ms,
    transit::{date_text, note},
};

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

/// One item of the arriving-soon page's list.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum NoticeItem {
    Header,
    Intro,
    SendTo,
    /// The search box, shown for long chat lists.
    Search,
    /// Loading, failed or no chats: one row in place of the chats.
    Status,
    /// Index into `chat_view`, and the chat's key.
    Chat(usize, u64),
    /// The template field, its hint and the preview.
    Message {
        refused: bool,
        /// The saved template differs from the default: the restore row shows.
        custom: bool,
    },
    When,
    Off,
}

impl NoticeItem {
    /// The list key: it changes whenever the item's height may.
    fn key(self, chats: &ChatList) -> u64 {
        match self {
            Self::Header => lists::key(0, 0),
            Self::Intro => lists::key(1, 0),
            Self::SendTo => lists::key(2, 0),
            Self::Status => lists::key(
                3,
                match chats {
                    ChatList::Idle | ChatList::Loading => 0,
                    ChatList::Ready(_) => 1,
                    ChatList::Failed(_) => 2,
                },
            ),
            Self::Search => lists::key(8, 0),
            Self::Chat(_, k) => lists::key(4, k),
            Self::Message { refused, custom } => {
                lists::key(5, u64::from(refused) | (u64::from(custom) << 1))
            }
            Self::When => lists::key(6, 0),
            Self::Off => lists::key(7, 0),
        }
    }
}

/// A stable key for a chat row, so a filtered list re-measures.
fn chat_key(jid: &str) -> u64 {
    // FNV-1a: deterministic across runs.
    jid.bytes().fold(0xcbf2_9ce4_8422_2325, |h, b| {
        (h ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3)
    })
}

/// Chats from this count on get a search box.
const SEARCH_FROM: usize = 8;

// ---- pure --------------------------------------------------------------------------

/// "under 1 min", "18 min", "1 h 05 min".
pub fn duration_text(ms: u64) -> String {
    rovsti_geo::history::format_duration(ms)
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

/// Title and subtitle of the "Downloaded maps" row.
pub fn tile_cache_texts(usage: Option<u64>, armed: bool) -> (&'static str, String) {
    let title = if armed {
        "Tap again to clear"
    } else {
        "Downloaded maps"
    };
    let subtitle = usage.map_or_else(|| "Checking...".to_owned(), bytes_text);
    (title, subtitle)
}

/// Title and subtitle of the "Cache size" row.
pub fn cache_size_text(cap_mb: u32, armed_mb: Option<u32>) -> (String, &'static str) {
    match armed_mb {
        Some(mb) => (
            format!("Tap again to shrink to {mb} MB"),
            "Older tiles are deleted now",
        ),
        None => (format!("{cap_mb} MB"), "Offline areas stay while they fit"),
    }
}

/// Whether the current cache holds more than the new cap allows.
pub fn needs_shrink_confirm(usage: u64, new_cap: u64) -> bool {
    usage > new_cap
}

/// Bytes held by servers other than `current`.
pub fn other_servers_bytes(sources: &[(String, u64)], current: &str) -> u64 {
    sources
        .iter()
        .filter(|(key, _)| key != current)
        .map(|(_, bytes)| bytes)
        .sum()
}

/// Cache size: "None", "< 1 MB", "4.2 MB", "37 MB".
pub fn bytes_text(bytes: u64) -> String {
    const MIB: u64 = 1024 * 1024;
    match bytes {
        0 => "None".to_owned(),
        1..MIB => "< 1 MB".to_owned(),
        _ if bytes < 10 * MIB => format!("{:.1} MB", bytes as f64 / MIB as f64),
        _ => format!("{} MB", (bytes as f64 / MIB as f64).round() as u64),
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

/// "5 Nov  ·  18 min  ·  3.4 km" under a history row's title.
pub fn record_details(record: &TripRecord, now_ms: i64, tz_offset_s: i32) -> String {
    format!(
        "{}  ·  {}  ·  {}",
        date_text(record.ended_ms, now_ms, tz_offset_s),
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

/// The picker rows: the gateway's order, groups kept and marked.
pub fn chat_rows(chats: &[ChatSummary]) -> Vec<ChatRow> {
    chats
        .iter()
        .map(|c| ChatRow {
            jid: c.jid.clone(),
            title: chat_title(c),
            group: c.is_group,
        })
        .collect()
}

/// The rows to show for `query`: at most `cap` matches by title or phone
/// digits. With no query the selected chat `(jid, name)` is pinned first.
pub fn visible_chats(
    rows: &[ChatRow],
    query: &str,
    selected: Option<(&str, &str)>,
    cap: usize,
) -> Vec<ChatRow> {
    let folded = rovsti_core::search::fold(query.trim());
    if folded.is_empty() {
        let mut out: Vec<ChatRow> = rows.iter().take(cap).cloned().collect();
        if let Some((jid, name)) = selected
            && !out.iter().any(|r| r.jid == jid)
        {
            let row = rows
                .iter()
                .find(|r| r.jid == jid)
                .cloned()
                .unwrap_or_else(|| {
                    let name = name.trim();
                    ChatRow {
                        jid: jid.to_owned(),
                        title: if name.is_empty() {
                            "Selected chat"
                        } else {
                            name
                        }
                        .to_owned(),
                        group: jid.ends_with("@g.us"),
                    }
                });
            out.insert(0, row);
        }
        return out;
    }
    let digits: String = folded.chars().filter(char::is_ascii_digit).collect();
    rows.iter()
        .filter(|r| {
            rovsti_core::search::fold(&r.title).contains(&folded)
                || (!digits.is_empty()
                    && r.jid.split('@').next().is_some_and(|user| {
                        user.split(':').next().unwrap_or(user).contains(&digits)
                    }))
        })
        .take(cap)
        .cloned()
        .collect()
}

/// The single status row of the chat list, as (title, subtitle, trailing
/// action), or `None` when chat rows show.
pub fn chat_status_text(
    list: &ChatList,
    query: &str,
) -> Option<(&'static str, Option<String>, Option<&'static str>)> {
    match list {
        ChatList::Idle | ChatList::Loading => Some(("Loading chats...", None, None)),
        ChatList::Failed(reason) => Some((
            "Could not load your chats",
            Some(reason.clone()),
            Some("Retry"),
        )),
        ChatList::Ready(rows) if rows.is_empty() => Some((
            "No chats yet",
            Some("Chats appear after history sync.".to_owned()),
            Some("Refresh"),
        )),
        ChatList::Ready(rows) => {
            visible_chats(rows, query, None, 1)
                .is_empty()
                .then_some(("No chats match", None, None))
        }
    }
}

/// What a chat reload does, as (fetch, show loading): it never overlaps a
/// running fetch, and it keeps a populated list on screen while refreshing.
pub fn reload_plan(list: &ChatList) -> (bool, bool) {
    match list {
        ChatList::Loading => (false, false),
        ChatList::Ready(rows) if !rows.is_empty() => (true, false),
        _ => (true, true),
    }
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

/// The pill after "Test and save". The technical detail goes to the log.
pub fn tile_test_text(result: &Result<usize, TileError>) -> String {
    let why = match result {
        Ok(1) => return "Map server works (1 layer)".to_owned(),
        Ok(n) => return format!("Map server works ({n} layers)"),
        Err(TileError::Http(_)) => "it could not be reached. Check the URL and the connection.",
        Err(TileError::Status(404)) => "it has no tile there (404). Check the URL.",
        Err(TileError::Status(401 | 403)) => "it refused access. Check the URL.",
        Err(TileError::Status(code)) => {
            return format!("Map server not saved: it answered with error {code}.");
        }
        Err(TileError::TooLarge) => "its tile is too large.",
        Err(TileError::Decode(_)) => "it does not send vector tiles.",
        Err(TileError::Io(_)) => "the tile could not be stored on the phone.",
    };
    format!("Map server not saved: {why}")
}

/// Why the chat list did not load, for the user. The detail goes to the log.
pub fn chats_error_text(error: &rovsti_wa_client::ClientError) -> &'static str {
    use rovsti_wa_client::ClientError;
    match error {
        ClientError::Unauthorized | ClientError::Key(_) => {
            "The gateway refused this phone's connect key. Link WhatsApp again."
        }
        ClientError::Url(_) => "The gateway address is not valid. Check it in WhatsApp settings.",
        ClientError::Http(_) | ClientError::Ws(_) | ClientError::Tls(_) | ClientError::Closed => {
            "The gateway could not be reached. Check that it is running and online."
        }
        ClientError::Api(_) | ClientError::Decode(_) => "The gateway gave an unexpected answer.",
    }
}

pub fn exact_alarm_text(allowed: Option<bool>) -> &'static str {
    match allowed {
        Some(true) => "Allowed",
        Some(false) => "Not allowed: a snooze may ring late. Allow it under Alarms & reminders.",
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
        Err(error) => {
            log::warn!("{what} failed: {error}");
            Some(format!("{what} did not work. Try again."))
        }
    }
}

/// The volume row's value: "80 %".
pub fn volume_text(pct: u8) -> String {
    format!("{} %", pct.clamp(MIN_ALARM_VOLUME_PCT, 100))
}

/// The pattern row only matters while vibration is on.
pub fn pattern_row_visible(prefs: &StopsPrefs) -> bool {
    prefs.vibrate
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
    ) -> Page {
        match self.settings_page {
            SettingsPage::Main => Page::Flow(self.settings_main(theme, cx)),
            SettingsPage::History => self.history_page(theme, cx),
            SettingsPage::Notice => self.notice_page(theme, cx),
            SettingsPage::WhatsApp => {
                let body = self.whatsapp.clone().into_any_element();
                Page::Flow(self.settings_subpage(theme, "WhatsApp", body, cx))
            }
            SettingsPage::MapTiles => {
                let body = self.tiles_body(theme, window, cx);
                Page::Flow(self.settings_subpage(theme, "Map tiles", body, cx))
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
            .child(Self::subpage_header(theme, title, cx))
            .child(body)
            .into_any_element()
    }

    /// The back button and the title of a Settings page.
    fn subpage_header(theme: Theme, title: &'static str, cx: &mut Context<Self>) -> AnyElement {
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
            .into_any_element()
    }

    /// Shows a Settings page. Leaving the History page forgets a half-done Clear.
    pub(crate) fn open_settings(&mut self, page: SettingsPage, cx: &mut Context<Self>) {
        self.settings_page = page;
        self.confirm_clear = false;
        self.history_open = None;
        self.confirm_remove_feed = false;
        self.confirm_clear_tiles = false;
        self.shrink_armed = None;
        self.confirm_clear_other = false;
        self.page.restart();
        self.history_list.scroll_to_top();
        self.notice_list.scroll_to_top();
        match page {
            SettingsPage::Notice => {
                self.sync_template_field(cx);
                self.chat_query.update(cx, |f, cx| f.set("", cx));
                self.load_chats(true, cx);
            }
            SettingsPage::MapTiles => {
                self.sync_tile_field(cx);
                self.measure_tiles(cx);
            }
            SettingsPage::Main => self.refresh_preflight(cx),
            _ => {}
        }
        cx.notify();
    }

    fn settings_main(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let pre = self.preflight;
        let connected = self.whatsapp.read(cx).is_connected();

        let mut alarm = vec![
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
                "Alarm sound",
                None,
                Some(self.prefs.alarm_sound_label().into()),
            )
            .id("set-alarm-sound")
            .on_click(cx.listener(|this, _, _, cx| {
                let current = this.prefs.alarm_sound.as_ref().map(|s| s.uri.clone());
                this.run_android(
                    "Alarm sound",
                    move || trip::pick_alarm_sound(current.as_deref()),
                    cx,
                );
            }))
            .into_any_element(),
            list_row(
                theme,
                None,
                "Volume",
                Some("Never quieter than this.".into()),
                Some(volume_text(self.prefs.volume_pct).into()),
            )
            .id("set-volume")
            .on_click(cx.listener(|this, _, _, cx| {
                this.prefs.volume_pct = next_volume(this.prefs.volume_pct);
                this.save_prefs(cx);
                let text = format!("Volume: {}", volume_text(this.prefs.volume_pct));
                this.show_pill(text, None, cx);
                cx.notify();
            }))
            .into_any_element(),
            list_row(
                theme,
                None,
                "Test volume",
                Some("Plays your alarm sound for 3 seconds.".into()),
                Some("Play".into()),
            )
            .id("set-test-volume")
            .on_click(cx.listener(|this, _, _, cx| {
                let plan = serde_json::to_string(&this.prefs.alarm_plan()).unwrap_or_default();
                cx.spawn(async move |this, cx| {
                    let result = cx
                        .background_executor()
                        .spawn(async move { trip::preview_alarm_sound(&plan, 3000) })
                        .await;
                    let text = android_notice("Test volume", &result)
                        .unwrap_or_else(|| "Playing for 3 seconds".to_owned());
                    this.update(cx, |this, cx| this.show_pill(text, None, cx))
                        .ok();
                })
                .detach();
            }))
            .into_any_element(),
        ];
        if pattern_row_visible(&self.prefs) {
            alarm.push(
                list_row(
                    theme,
                    None,
                    "Vibration pattern",
                    None,
                    Some(self.prefs.vibration.label().into()),
                )
                .id("set-vibration-pattern")
                .on_click(cx.listener(|this, _, window, cx| {
                    let next = this.prefs.vibration.next();
                    this.prefs.vibration = next;
                    this.save_prefs(cx);
                    haptics::perform(window, HapticFeedback::Selection);
                    let ms = next.preview_ms();
                    cx.spawn(async move |this, cx| {
                        let result = cx
                            .background_executor()
                            .spawn(async move { trip::preview_vibration(&ms) })
                            .await;
                        let text = android_notice("Vibration", &result)
                            .unwrap_or_else(|| format!("Vibration: {}", next.label()));
                        this.update(cx, |this, cx| this.show_pill(text, None, cx))
                            .ok();
                    })
                    .detach();
                    cx.notify();
                }))
                .into_any_element(),
            );
        }
        alarm.push(
            list_row(
                theme,
                None,
                "Test alarm",
                Some("Rings once with your sound and vibration.".into()),
                Some("Ring".into()),
            )
            .id("set-test-alarm")
            .on_click(cx.listener(|this, _, _, cx| {
                let json = this.prefs.test_ring_json();
                this.run_android("Test alarm", move || trip::test_alarm(&json), cx);
            }))
            .into_any_element(),
        );

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
        let exact_missing = pre.exact_alarms == Some(false);
        let exact = list_row(
            theme,
            None,
            "Exact alarms",
            Some(exact_alarm_text(pre.exact_alarms).into()),
            exact_missing.then(|| "Open".into()),
        );
        reliability.push(if exact_missing {
            // The app's page leads to "Alarms & reminders"; the activation
            // refresh reads the grant again on return.
            exact
                .id("set-exact-alarms")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.run_android(
                        "App settings",
                        rovsti_android::permissions::open_app_details,
                        cx,
                    );
                }))
                .into_any_element()
        } else {
            exact.into_any_element()
        });

        let history_summary = match self.history.records.len() {
            0 => "No trips yet".to_owned(),
            1 => "1 trip".to_owned(),
            n => format!("{n} trips"),
        };
        let more = vec![
            list_row(
                theme,
                None,
                "Appearance",
                Some(self.prefs.appearance.label().into()),
                Some("Change".into()),
            )
            .id("set-appearance")
            .on_click(cx.listener(|this, _, _, cx| {
                let next = this.prefs.appearance.next();
                this.set_appearance(next, cx);
            }))
            .into_any_element(),
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

        let backup_rows = vec![
            list_row(
                theme,
                None,
                "Export backup",
                Some("Saved stops, trip history and settings".into()),
                None,
            )
            .id("set-backup-export")
            .on_click(cx.listener(|this, _, _, cx| this.export_backup(cx)))
            .into_any_element(),
            list_row(theme, None, "Restore backup", None, None)
                .id("set-backup-restore")
                .on_click(cx.listener(|this, _, _, cx| this.restore_backup(cx)))
                .into_any_element(),
        ];

        div()
            .flex()
            .flex_col()
            .gap(px(24.0))
            .child(section(theme, Some("Alarm".into()), alarm))
            .child(section(theme, Some("Battery".into()), reliability))
            .child(self.transit_section(theme, cx))
            .child(section(theme, Some("Backup".into()), backup_rows))
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

    /// History: statistics, then every recorded trip (the file keeps up to
    /// 500) in a virtualised list, so only the rows on screen are built.
    fn history_page(&mut self, theme: Theme, cx: &mut Context<Self>) -> Page {
        if self.history.records.is_empty() {
            let body = empty_state(
                theme,
                "No Trips Yet",
                "Finished and cancelled trips are listed here, with some statistics.",
            )
            .into_any_element();
            return Page::Flow(self.settings_subpage(theme, "History", body, cx));
        }
        // A hidden field must not keep filtering.
        let query = if crate::saved_ui::history_field_visible(self.history.records.len()) {
            self.history_query.as_str()
        } else {
            ""
        };
        self.history_view = self.history.matching(query, self.history_filter);
        let shown = self.history_view.len();
        // The statistics change height with the records behind them; the controls with
        // the field being there.
        let controls = u64::from(crate::saved_ui::history_field_visible(
            self.history.records.len(),
        ));
        let mut keys = vec![
            lists::key(0, 0),
            lists::key(1, self.history.records.len() as u64),
            lists::key(2, controls),
        ];
        if shown == 0 {
            keys.push(lists::key(6, 0));
        }
        keys.extend(self.history_view.iter().map(|&i| {
            let r = &self.history.records[i];
            // An open row is taller: a new key makes the list measure it again.
            let kind = if self.history_open == Some(i) { 5 } else { 3 };
            lists::key(kind, r.id)
        }));
        keys.push(lists::key(4, 0));
        let state = self.history_list.sync(keys);
        Page::List(Box::new(list(
            state,
            cx.processor(move |this, ix: usize, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                this.history_item(theme, ix, window, cx)
            }),
        )))
    }

    /// Item `ix` of the History list: header, statistics, "Trips", the
    /// records, Clear.
    fn history_item(
        &mut self,
        theme: Theme,
        ix: usize,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        const RECORDS_FROM: usize = 3;
        let count = self.history_view.len();
        if count == 0 && ix == RECORDS_FROM {
            return empty_state(
                theme,
                "No trips match",
                "Try another word, or show All trips.",
            )
            .into_any_element();
        }

        match ix {
            0 => Self::subpage_header(theme, "History", cx),
            1 => {
                let stats = stats_rows(&self.history.stats(now_ms()));
                let rows = stats.into_iter().map(|(label, value)| {
                    list_row(theme, None, label, None, Some(value.into())).into_any_element()
                });
                section(theme, Some("Statistics".into()), rows).into_any_element()
            }
            2 => self.history_controls(theme, window, cx),
            ix if ix - RECORDS_FROM < count => {
                let place_at = ix - RECORDS_FROM;
                let i = self.history_view[place_at];
                let r = &self.history.records[i];
                let open = self.history_open == Some(i);
                let pair = match (&r.a, &r.b) {
                    (Some(a), Some(b)) => Some((a.clone(), b.clone())),
                    _ => None,
                };
                let row = list_row(
                    theme,
                    None,
                    record_pair(r),
                    Some(record_details(r, now_ms(), self.tz_offset_s).into()),
                    Some(
                        if open {
                            "Close"
                        } else {
                            outcome_text(r.outcome)
                        }
                        .into(),
                    ),
                )
                .id(("history-row", i))
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.history_open = if this.history_open == Some(i) {
                        None
                    } else {
                        Some(i)
                    };
                    cx.notify();
                }));
                let place = lists::Place::of(place_at, count);
                let grouped = lists::grouped(theme, place, row);
                if !open {
                    return grouped.into_any_element();
                }
                let colors = theme::colors(theme);
                let mut pills = div()
                    .px(px(16.0))
                    .pt(px(8.0))
                    .flex()
                    .flex_wrap()
                    .gap(px(12.0));
                if let Some((a, b)) = pair {
                    let (again_a, again_b) = (a.clone(), b.clone());
                    pills = pills
                        .child(
                            glass_pill(theme, "Start again")
                                .id(("history-again", i))
                                .text_color(colors.accent)
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.start_again(&again_a, &again_b, cx);
                                })),
                        )
                        .child(
                            glass_pill(theme, "Save as stop")
                                .id(("history-save", i))
                                .text_color(colors.accent)
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.save_record_as_stop(i, cx);
                                })),
                        );
                    pills = pills.child(
                        glass_pill(theme, "Use")
                            .id(("history-use", i))
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                let a = crate::pick::dest_from_endpoint(&a);
                                let b = crate::pick::dest_from_endpoint(&b);
                                this.choose_pair(a, b, cx);
                                this.tabs.jump_to(0);
                                this.open_settings(SettingsPage::Main, cx);
                            })),
                    );
                }
                let share = rovsti_geo::history_io::share_text(r, self.tz_offset_s);
                let copy = share.clone();
                pills = pills
                    .child(
                        glass_pill(theme, "Share")
                            .id(("history-share", i))
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.share_out("Trip".into(), share.clone(), cx);
                            })),
                    )
                    .child(
                        glass_pill(theme, "Copy")
                            .id(("history-copy", i))
                            .text_color(colors.accent)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                cx.write_to_clipboard(gpui::ClipboardItem::new_string(
                                    copy.clone(),
                                ));
                                this.show_pill("Trip copied", None, cx);
                            })),
                    );
                pills = pills.child(
                    glass_pill(theme, "Delete")
                        .id(("history-delete", i))
                        .text_color(colors.red)
                        .on_click(cx.listener(move |this, _, _, cx| this.delete_record(i, cx))),
                );
                div().child(grouped).child(pills).into_any_element()
            }
            _ => {
                let colors = theme::colors(theme);
                let clear_label = if self.confirm_clear {
                    "Tap again to clear the history"
                } else {
                    "Clear history"
                };
                div()
                    .pt(px(20.0))
                    .flex()
                    .justify_center()
                    .child(
                        glass_pill(theme, clear_label)
                            .id("history-clear")
                            .px(px(24.0))
                            .h(px(48.0))
                            .text_color(colors.red)
                            .on_click(cx.listener(|this, _, _, cx| this.clear_history(cx))),
                    )
                    .into_any_element()
            }
        }
    }

    /// "Trips", the search field, the outcome filter and the export pills.
    fn history_controls(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let mut row = div()
            .flex()
            .items_center()
            .gap(px(8.0))
            .px(px(16.0))
            .pb(px(8.0));
        if crate::saved_ui::history_field_visible(self.history.records.len()) {
            row = row.child(div().flex_1().child(field_box(
                theme,
                "history-search",
                &self.history_field,
                "Search trips",
                window,
                cx,
            )));
        } else {
            row = row.child(div().flex_1());
        }
        row = row.child(
            glass_pill(theme, format!("Show: {}", self.history_filter.label()))
                .id("history-filter")
                .text_color(colors.accent)
                .on_click(cx.listener(|this, _, _, cx| {
                    this.history_filter = this.history_filter.next();
                    this.history_open = None;
                    this.history_list.scroll_to_top();
                    cx.notify();
                })),
        );
        let export = div()
            .flex()
            .gap(px(8.0))
            .px(px(16.0))
            .pb(px(8.0))
            .child(
                glass_pill(theme, "Export CSV")
                    .id("history-export-csv")
                    .flex_1()
                    .text_color(colors.accent)
                    .on_click(cx.listener(|this, _, _, cx| this.export_history(false, cx))),
            )
            .child(
                glass_pill(theme, "Export GPX")
                    .id("history-export-gpx")
                    .flex_1()
                    .text_color(colors.accent)
                    .on_click(cx.listener(|this, _, _, cx| this.export_history(true, cx))),
            );
        div()
            .child(lists::section_header(theme, "Trips", 20.0))
            .child(row)
            .child(export)
            .into_any_element()
    }

    /// Writes the trips on screen to a file the user picks the place of.
    fn export_history(&mut self, gpx: bool, cx: &mut Context<Self>) {
        let records: Vec<TripRecord> = self
            .history_view
            .iter()
            .filter_map(|&i| self.history.records.get(i).cloned())
            .collect();
        if records.is_empty() {
            self.show_pill("No trips to export", None, cx);
            return;
        }
        let (ext, mime, text) = if gpx {
            (
                "gpx",
                "application/gpx+xml",
                rovsti_geo::history_io::to_gpx(&records),
            )
        } else {
            (
                "csv",
                "text/csv",
                rovsti_geo::history_io::to_csv(&records, self.tz_offset_s),
            )
        };
        let stamp = crate::saved_ui::export_stamp(now_ms(), self.tz_offset_s);
        let name = rovsti_geo::history_io::export_file_name(ext, &stamp);
        self.pending_export = Some(crate::saved_ui::ExportKind::History);
        if let Err(words) = backend::export_text(&name, mime, &text) {
            self.pending_export = None;
            self.show_pill(format!("Can't export: {words}"), None, cx);
        }
    }

    /// "Start again": the same rule as starting a saved stop.
    pub(crate) fn start_again(
        &mut self,
        a: &rovsti_geo::Endpoint,
        b: &rovsti_geo::Endpoint,
        cx: &mut Context<Self>,
    ) {
        if self.trip.is_some() {
            self.show_pill("Not while a trip is running", None, cx);
            return;
        }
        let a = crate::pick::dest_from_endpoint(a);
        let b = crate::pick::dest_from_endpoint(b);
        self.tabs.jump_to(0);
        self.request_start(a, b, cx);
    }

    /// "Save as stop": keeps the trip's A and B in Saved; the pill offers Undo.
    pub(crate) fn save_record_as_stop(&mut self, i: usize, cx: &mut Context<Self>) {
        let Some(r) = self.history.records.get(i) else {
            return;
        };
        let (Some(a), Some(b)) = (r.a.clone(), r.b.clone()) else {
            return;
        };
        let mut name = rovsti_geo::saved_stops::suggest_name(self.gtfs.as_deref(), &a, &b);
        if name.trim().is_empty() {
            name = r.label.clone();
        }
        match self.saved.add(&name, a, b, now_ms()) {
            Ok(id) => {
                self.save_stops(cx);
                let name = name.trim().to_owned();
                self.show_pill(
                    format!("Saved as {name}"),
                    Some(crate::PillAction::UndoSave(id)),
                    cx,
                );
            }
            Err(rovsti_geo::SavedStopError::Duplicate(id)) => {
                let name = self
                    .saved
                    .get(id)
                    .map_or_else(String::new, |s| s.name.clone());
                self.show_pill(format!("Already saved as {name}"), None, cx);
            }
            Err(error) => self.show_pill(error.message(), None, cx),
        }
    }

    /// Undo on the pill: the stop saved from a trip goes again.
    pub(crate) fn undo_save(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.saved.remove(id) {
            self.expanded = None;
            self.save_stops(cx);
        }
        cx.notify();
    }

    /// Two taps: the first asks, the second clears.
    fn clear_history(&mut self, cx: &mut Context<Self>) {
        if !self.confirm_clear {
            self.confirm_clear = true;
            cx.notify();
            return;
        }
        self.confirm_clear = false;
        self.history_open = None;
        let old = std::mem::take(&mut self.history);
        if let Some(dir) = self.dir.clone() {
            cx.background_executor()
                .spawn(async move { backend::clear_history(&dir) })
                .detach();
        }
        self.show_pill(
            "History cleared",
            Some(crate::PillAction::RestoreHistory(old)),
            cx,
        );
    }

    /// Removes one trip; the pill offers Undo.
    fn delete_record(&mut self, i: usize, cx: &mut Context<Self>) {
        self.history_open = None;
        let Some(r) = self.history.take(i) else {
            cx.notify();
            return;
        };
        self.save_history_in_background(cx);
        let text = format!("Removed {}", record_pair(&r));
        self.show_pill(text, Some(crate::PillAction::RestoreRecord(r, i)), cx);
    }

    /// Undo on the pill: the trip returns to its place and is saved.
    pub(crate) fn restore_record(
        &mut self,
        record: rovsti_geo::TripRecord,
        index: usize,
        cx: &mut Context<Self>,
    ) {
        self.history.put_back(index, record);
        self.history_open = None;
        self.save_history_in_background(cx);
        cx.notify();
    }

    pub(crate) fn save_history_in_background(&self, cx: &mut Context<Self>) {
        if let Some(dir) = self.dir.clone() {
            let history = self.history.clone();
            cx.background_executor()
                .spawn(async move { backend::save_history(&dir, &history) })
                .detach();
        }
    }

    /// Undo on the pill: the old trips come back and are saved again.
    pub(crate) fn restore_history(&mut self, history: rovsti_geo::History, cx: &mut Context<Self>) {
        self.history = history.clone();
        self.history_open = None;
        if let Some(dir) = self.dir.clone() {
            cx.background_executor()
                .spawn(async move { backend::save_history(&dir, &history) })
                .detach();
        }
        cx.notify();
    }

    // ---- Arriving-soon message ---------------------------------------------------------

    /// The arriving-soon page: the chat picker is a virtualised list, followed
    /// by the message, when to send it, and Turn off.
    fn notice_page(&mut self, theme: Theme, cx: &mut Context<Self>) -> Page {
        if !self.whatsapp.read(cx).is_connected() {
            let body = self.notice_unlinked(theme, cx);
            return Page::Flow(self.settings_subpage(theme, "Arriving soon", body, cx));
        }
        let chosen = self
            .prefs
            .notice
            .as_ref()
            .is_some_and(|n| !n.chat.is_empty());
        let mut items = vec![NoticeItem::Header, NoticeItem::Intro, NoticeItem::SendTo];
        let query = self.chat_query.read(cx).text().to_owned();
        self.chat_view.clear();
        if let ChatList::Ready(rows) = &self.chats {
            if rows.len() > SEARCH_FROM {
                items.push(NoticeItem::Search);
            }
            let selected = self
                .prefs
                .notice
                .as_ref()
                .filter(|n| !n.chat.is_empty())
                .map(|n| (n.chat.as_str(), n.chat_name.as_str()));
            self.chat_view = visible_chats(rows, &query, selected, MAX_CHATS);
        }
        items.extend(
            self.chat_view
                .iter()
                .enumerate()
                .map(|(i, r)| NoticeItem::Chat(i, chat_key(&r.jid))),
        );
        if chat_status_text(&self.chats, &query).is_some() {
            items.push(NoticeItem::Status);
        }
        if chosen {
            // A refused template adds an error line under the field.
            let text = self.template_field.read(cx).text();
            let refused = rovsti_geo::notice::validate(text).is_err();
            let custom = self
                .prefs
                .notice
                .as_ref()
                .is_some_and(|n| n.template != DEFAULT_TEMPLATE);
            items.extend([
                NoticeItem::Message { refused, custom },
                NoticeItem::When,
                NoticeItem::Off,
            ]);
        }
        let keys = items.iter().map(|item| item.key(&self.chats)).collect();
        let state = self.notice_list.sync(keys);
        Page::List(Box::new(list(
            state,
            cx.processor(move |this, ix: usize, window, cx| {
                let theme = rovsti_ui::current_theme(window);
                match items.get(ix) {
                    Some(&item) => this.notice_item(theme, item, window, cx),
                    None => div().into_any_element(),
                }
            }),
        )))
    }

    /// WhatsApp is not linked: one row that leads to its page.
    fn notice_unlinked(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        div()
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
                    cx.listener(|this, _, _, cx| this.open_settings(SettingsPage::WhatsApp, cx)),
                )
                .into_any_element()],
            ))
            .into_any_element()
    }

    /// One item of the arriving-soon list.
    fn notice_item(
        &mut self,
        theme: Theme,
        item: NoticeItem,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        match item {
            NoticeItem::Header => Self::subpage_header(theme, "Arriving soon", cx),
            NoticeItem::Intro => note(
                theme,
                "Sends one WhatsApp message through your gateway when you are close. Nothing else leaves the phone.",
                false,
            ),
            NoticeItem::SendTo => lists::section_header(theme, "Send to", 20.0),
            NoticeItem::Search => div()
                .px(px(16.0))
                .pb(px(6.0))
                .child(field_box(
                    theme,
                    "chat-search",
                    &self.chat_query,
                    "Search chats",
                    window,
                    cx,
                ))
                .into_any_element(),
            NoticeItem::Status => {
                let query = self.chat_query.read(cx).text().to_owned();
                let Some((title, subtitle, action)) = chat_status_text(&self.chats, &query) else {
                    return div().into_any_element();
                };
                let retry = action.is_some();
                let row = list_row(
                    theme,
                    None,
                    title,
                    subtitle.map(Into::into),
                    action.map(Into::into),
                );
                let row = if retry {
                    row.id("chats-retry")
                        .on_click(cx.listener(|this, _, _, cx| this.load_chats(true, cx)))
                        .into_any_element()
                } else {
                    row.into_any_element()
                };
                lists::grouped(theme, lists::Place::ALONE, row).into_any_element()
            }
            NoticeItem::Chat(i, _) => {
                let rows = &self.chat_view;
                let Some(chat) = rows.get(i) else {
                    return div().into_any_element();
                };
                let selected = self
                    .prefs
                    .notice
                    .as_ref()
                    .is_some_and(|n| !n.chat.is_empty() && n.chat == chat.jid);
                let picked = chat.clone();
                let row = list_row(
                    theme,
                    None,
                    chat.title.clone(),
                    chat.group.then(|| "Group".into()),
                    selected.then(|| "Selected".into()),
                )
                .id(("notice-chat", i))
                .on_click(cx.listener(move |this, _, _, cx| this.choose_chat(&picked, cx)));
                lists::grouped(theme, lists::Place::of(i, rows.len()), row).into_any_element()
            }
            NoticeItem::Message { .. } => {
                let template = self
                    .prefs
                    .notice
                    .as_ref()
                    .map(|n| n.template.clone())
                    .unwrap_or_default();
                let custom = template != DEFAULT_TEMPLATE;
                let text = self.template_field.read(cx).text().to_owned();
                let check = rovsti_geo::notice::validate(&text);
                let shown = if check.is_ok() { text } else { template };
                let hint = match check {
                    Err(error) => {
                        note(theme, format!("{error}. The last good text is kept."), true)
                    }
                    Ok(()) => note(
                        theme,
                        "Use {stop} for the stop and {min} for the minutes left.",
                        false,
                    ),
                };
                div()
                    .pt(px(20.0))
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
                    )))
                    .child(hint)
                    .child(
                        div()
                            .mx(px(20.0))
                            .p(px(12.0))
                            .rounded(px(14.0))
                            .bg(colors.secondary_background)
                            .text_color(colors.label)
                            .child(notice_preview(&shown)),
                    )
                    .when(custom, |d| {
                        d.child(
                            list_row(theme, None, "Use the default text", None, None)
                                .id("notice-default")
                                .on_click(
                                    cx.listener(|this, _, _, cx| this.use_default_template(cx)),
                                ),
                        )
                    })
                    .into_any_element()
            }
            NoticeItem::When => {
                let current = self.prefs.notice.as_ref().map(|n| n.when);
                let when: Vec<AnyElement> = NoticeWhen::ALL
                    .into_iter()
                    .enumerate()
                    .map(|(i, option)| {
                        list_row(
                            theme,
                            None,
                            option.label(),
                            None,
                            (current == Some(option)).then(|| "Selected".into()),
                        )
                        .id(("notice-when", i))
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.set_notice_when(option, cx)),
                        )
                        .into_any_element()
                    })
                    .collect();
                div()
                    .pt(px(20.0))
                    .child(section(theme, Some("When".into()), when))
                    .into_any_element()
            }
            NoticeItem::Off => div()
                .pt(px(20.0))
                .child(section(
                    theme,
                    None,
                    [list_row(theme, None, "Turn off", None, None)
                        .id("notice-off")
                        .text_color(colors.red)
                        .on_click(cx.listener(|this, _, _, cx| this.turn_notice_off(cx)))
                        .into_any_element()],
                ))
                .into_any_element(),
        }
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
        let (title, subtitle) = tile_cache_texts(self.tile_usage, self.confirm_clear_tiles);
        let downloaded = list_row(
            theme,
            None,
            title,
            Some(subtitle.into()),
            Some("Clear".into()),
        )
        .id("tiles-clear")
        .on_click(cx.listener(|this, _, _, cx| this.clear_tiles(cx)))
        .into_any_element();
        let (size_title, size_sub) = cache_size_text(self.prefs.tile_cache_mb, self.shrink_armed);
        let size_row = list_row(
            theme,
            None,
            size_title,
            Some(size_sub.into()),
            Some("Change".into()),
        )
        .id("tiles-cap")
        .on_click(cx.listener(|this, _, _, cx| this.cycle_tile_cache(cx)))
        .into_any_element();
        let other = other_servers_bytes(&self.tile_sources, &self.prefs.tile_source().key());
        let mut cache_rows = vec![downloaded, size_row];
        if other > 0 {
            let title = if self.confirm_clear_other {
                "Tap again to clear"
            } else {
                "Other servers"
            };
            cache_rows.push(
                list_row(
                    theme,
                    None,
                    title,
                    Some(bytes_text(other).into()),
                    Some("Clear".into()),
                )
                .id("tiles-clear-other")
                .on_click(cx.listener(|this, _, _, cx| this.clear_other_tiles(cx)))
                .into_any_element(),
            );
        }
        let progress = self.area_progress_row(theme, "tiles-area-cancel", cx);
        div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(note(
                theme,
                "Map tiles come from this server and are kept on the phone. Any VersaTiles-compatible Shortbread vector tile server works. Download areas from the map's Map button.",
                false,
            ))
            .child(
                div()
                    .px(px(16.0))
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .child(div().flex_1().child(field_box(
                        theme,
                        "tiles-url",
                        &self.tile_field,
                        DEFAULT_TILE_URL,
                        window,
                        cx,
                    )))
                    .child(
                        glass_pill(theme, "Paste")
                            .id("tiles-paste")
                            .text_color(rovsti_ui::theme::colors(theme).accent)
                            .on_click(cx.listener(|this, _, _, cx| this.paste_tile_url(cx))),
                    ),
            )
            .child(note(
                theme,
                "Use {z}, {x} and {y} for the tile position.",
                false,
            ))
            .child(section(theme, None, [save, reset]))
            .child(section(theme, None, cache_rows))
            .children(progress)
            .child(note(
                theme,
                "Kept for offline use, up to the cache size per map server.",
                false,
            ))
            .into_any_element()
    }

    /// Measures the tile cache off the UI thread.
    pub(crate) fn measure_tiles(&mut self, cx: &mut Context<Self>) {
        self.tile_usage = None;
        let Some(root) = self.map_tiles.root() else {
            self.tile_usage = Some(0);
            self.tile_sources.clear();
            return;
        };
        cx.spawn(async move |this, cx| {
            let bytes = cx
                .background_executor()
                .spawn(async move {
                    (
                        rovsti_tiles::disk_usage(&root),
                        rovsti_tiles::usage_by_source(&root),
                    )
                })
                .await;
            this.update(cx, |this, cx| {
                this.tile_usage = Some(bytes.0);
                this.tile_sources = bytes.1;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Two taps: the first asks, the second clears. Refused during a trip.
    fn clear_tiles(&mut self, cx: &mut Context<Self>) {
        if self.trip.is_some() {
            self.show_pill("Not while a trip is running", None, cx);
            return;
        }
        if !self.confirm_clear_tiles {
            self.confirm_clear_tiles = true;
            cx.notify();
            return;
        }
        self.confirm_clear_tiles = false;
        self.map_tiles.clear_disk(cx, |app, cx| {
            app.tile_usage = Some(0);
            app.show_pill("Downloaded maps cleared", None, cx);
        });
        cx.notify();
    }

    /// Cache size row: the next size; shrinking below the current usage
    /// asks for a second tap first.
    fn cycle_tile_cache(&mut self, cx: &mut Context<Self>) {
        let next = self.prefs.next_tile_cache();
        let shrinking = next < self.prefs.tile_cache_mb;
        let new_bytes = u64::from(next) * 1024 * 1024;
        let usage = self.tile_usage.unwrap_or(0);
        if shrinking && needs_shrink_confirm(usage, new_bytes) && self.shrink_armed != Some(next) {
            self.shrink_armed = Some(next);
            cx.notify();
            return;
        }
        self.shrink_armed = None;
        self.prefs.tile_cache_mb = next;
        self.save_prefs(cx);
        self.map_tiles.set_cap(new_bytes, cx);
        self.show_pill(format!("Cache limit {next} MB"), None, cx);
        self.measure_tiles(cx);
        cx.notify();
    }

    /// Other servers row: two taps, then their cached tiles are deleted.
    fn clear_other_tiles(&mut self, cx: &mut Context<Self>) {
        if self.trip.is_some() {
            self.show_pill("Not while a trip is running", None, cx);
            return;
        }
        if !self.confirm_clear_other {
            self.confirm_clear_other = true;
            cx.notify();
            return;
        }
        self.confirm_clear_other = false;
        let Some(root) = self.map_tiles.root() else {
            return;
        };
        let keep = self.prefs.tile_source().key();
        cx.spawn(async move |this, cx| {
            let freed = cx
                .background_executor()
                .spawn(async move { rovsti_tiles::clear_except(&root, &keep) })
                .await;
            this.update(cx, |this, cx| {
                match freed {
                    Ok(bytes) => this.show_pill(format!("Freed {}", bytes_text(bytes)), None, cx),
                    Err(error) => {
                        log::warn!("cannot clear other tile caches: {error}");
                        this.show_pill("Could not clear. Try again.", None, cx);
                    }
                }
                this.measure_tiles(cx);
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Paste button: fills the field only; the user still taps Test and save.
    fn paste_tile_url(&mut self, cx: &mut Context<Self>) {
        let clip = cx.read_from_clipboard().and_then(|item| item.text());
        match crate::pick::clean_paste(clip.as_deref()) {
            Some(text) => self.tile_field.update(cx, |f, cx| f.set(&text, cx)),
            None => self.show_pill("Nothing to paste", None, cx),
        }
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
                if let Err(error) = &result {
                    log::warn!("tile server test failed: {error}");
                }
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
        let old = self.prefs.tile_url.clone();
        let source = TileSource::default_source();
        self.use_tile_source(source, cx);
        self.sync_tile_field(cx);
        let action = old
            .is_some()
            .then_some(crate::PillAction::RestoreTileUrl(old));
        self.show_pill("Map server reset to default", action, cx);
    }

    /// Undo of "reset": puts the custom tile server back.
    pub(crate) fn restore_tile_url(&mut self, old: Option<String>, cx: &mut Context<Self>) {
        self.prefs.tile_url = old;
        self.use_tile_source(self.prefs.tile_source(), cx);
        self.sync_tile_field(cx);
    }

    /// Saves `source` as the setting and switches the map to it.
    pub(crate) fn use_tile_source(&mut self, source: TileSource, cx: &mut Context<Self>) {
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
        let old = self.prefs.notice.take();
        self.save_prefs(cx);
        self.sync_template_field(cx);
        if let Some(old) = old {
            self.show_pill(
                "Arriving-soon message off",
                Some(crate::PillAction::RestoreNotice(old)),
                cx,
            );
        }
        cx.notify();
    }

    /// Sets the template back to the default, with undo.
    fn use_default_template(&mut self, cx: &mut Context<Self>) {
        let old = self.prefs.notice.clone();
        if let Some(prefs) = self.prefs.notice.as_mut() {
            prefs.template = DEFAULT_TEMPLATE.to_owned();
        }
        self.save_prefs(cx);
        self.sync_template_field(cx);
        if let Some(old) = old {
            self.show_pill(
                "Default text restored",
                Some(crate::PillAction::RestoreNotice(old)),
                cx,
            );
        }
        cx.notify();
    }

    /// A picked alarm sound (`None` = the default alarm) with an Undo.
    pub(crate) fn on_alarm_sound_picked(&mut self, json: &str, cx: &mut Context<Self>) {
        let Some(picked) = trip::parse_alarm_sound_picked(json) else {
            log::warn!("unreadable alarm_sound_picked event");
            return;
        };
        let (new, text) = match picked {
            // A cancelled picker leaves everything as it was.
            AlarmPick::Cancelled => return,
            AlarmPick::Default => (None, "Alarm sound: Default alarm".to_owned()),
            AlarmPick::Chosen(uri, title) => {
                let title = if title.is_empty() {
                    "Alarm".to_owned()
                } else {
                    title
                };
                let text = format!("Alarm sound: {title}");
                (Some(rovsti_geo::prefs::AlarmSound { uri, title }), text)
            }
        };
        let old = std::mem::replace(&mut self.prefs.alarm_sound, new);
        self.save_prefs(cx);
        self.show_pill(text, Some(crate::PillAction::RestoreAlarmSound(old)), cx);
        cx.notify();
    }

    pub(crate) fn restore_alarm_sound(
        &mut self,
        old: Option<rovsti_geo::prefs::AlarmSound>,
        cx: &mut Context<Self>,
    ) {
        self.prefs.alarm_sound = old;
        self.save_prefs(cx);
        cx.notify();
    }

    /// Undo of "turn off": brings the message setup back.
    pub(crate) fn restore_notice(&mut self, old: rovsti_geo::NoticePrefs, cx: &mut Context<Self>) {
        self.prefs.notice = Some(old);
        self.save_prefs(cx);
        self.sync_template_field(cx);
        cx.notify();
    }

    /// Reads the chat list from the gateway on the background executor.
    fn load_chats(&mut self, _force: bool, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        let (fetch, show_loading) = reload_plan(&self.chats);
        if !fetch {
            return;
        }
        if show_loading {
            self.chats = ChatList::Loading;
        }
        cx.notify();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.chats() });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(chats) => this.chats = ChatList::Ready(chat_rows(&chats)),
                    Err(_) if matches!(&this.chats, ChatList::Ready(rows) if !rows.is_empty()) => {
                        this.show_pill("Could not refresh your chats", None, cx);
                    }
                    Err(error) => {
                        log::warn!("could not load the chats: {error}");
                        this.chats = ChatList::Failed(chats_error_text(&error).to_owned());
                    }
                }
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

    #[test]
    fn reload_plan_keeps_rows_and_skips_overlap() {
        assert_eq!(reload_plan(&ChatList::Loading), (false, false));
        assert_eq!(reload_plan(&ChatList::Idle), (true, true));
        assert_eq!(reload_plan(&ChatList::Failed("x".into())), (true, true));
        assert_eq!(reload_plan(&ChatList::Ready(vec![])), (true, true));
        let row = ChatRow {
            jid: "a@s.whatsapp.net".into(),
            title: "A".into(),
            group: false,
        };
        assert_eq!(reload_plan(&ChatList::Ready(vec![row])), (true, false));
    }

    #[test]
    fn cache_size_helpers() {
        assert_eq!(cache_size_text(256, None).0, "256 MB");
        assert_eq!(
            cache_size_text(1024, Some(128)).0,
            "Tap again to shrink to 128 MB"
        );
        assert!(needs_shrink_confirm(200, 100));
        assert!(!needs_shrink_confirm(100, 100));
        let s = vec![
            ("a".to_owned(), 5),
            ("b".to_owned(), 7),
            ("c".to_owned(), 1),
        ];
        assert_eq!(other_servers_bytes(&s, "b"), 6);
        assert_eq!(other_servers_bytes(&[], "b"), 0);
    }

    #[test]
    fn tile_cache_texts_cover_states() {
        assert_eq!(
            tile_cache_texts(None, false),
            ("Downloaded maps", "Checking...".to_owned())
        );
        assert_eq!(tile_cache_texts(Some(0), true).0, "Tap again to clear");
        assert_eq!(tile_cache_texts(Some(0), false).1, bytes_text(0));
    }

    #[test]
    fn bytes_text_edges() {
        const MIB: u64 = 1024 * 1024;
        assert_eq!(bytes_text(0), "None");
        assert_eq!(bytes_text(1), "< 1 MB");
        assert_eq!(bytes_text(MIB - 1), "< 1 MB");
        assert_eq!(bytes_text(MIB), "1.0 MB");
        assert_eq!(bytes_text(10 * MIB - 1), "10.0 MB");
        assert_eq!(bytes_text(10 * MIB), "10 MB");
        assert_eq!(bytes_text(37 * MIB + MIB / 4), "37 MB");
    }

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
    fn notice_items_change_key_when_their_height_may() {
        let loading = NoticeItem::Status.key(&ChatList::Loading);
        assert_eq!(loading, NoticeItem::Status.key(&ChatList::Idle));
        assert_ne!(
            loading,
            NoticeItem::Status.key(&ChatList::Failed("x".into()))
        );
        let message =
            |refused, custom| NoticeItem::Message { refused, custom }.key(&ChatList::Idle);
        assert_ne!(message(false, false), message(true, false));
        assert_ne!(message(false, false), message(false, true));
        assert_ne!(message(true, false), message(false, true));
        assert_ne!(
            NoticeItem::Chat(0, chat_key("a@s.whatsapp.net")).key(&ChatList::Idle),
            NoticeItem::Chat(0, chat_key("b@s.whatsapp.net")).key(&ChatList::Idle)
        );
        assert_ne!(
            NoticeItem::Search.key(&ChatList::Idle),
            NoticeItem::SendTo.key(&ChatList::Idle)
        );
    }

    #[test]
    fn chat_key_is_stable_and_distinct() {
        assert_eq!(chat_key("a@g.us"), chat_key("a@g.us"));
        assert_ne!(chat_key("a@g.us"), chat_key("b@g.us"));
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
        let offline = tile_test_text(&Err(TileError::Http("io: os error 111".into())));
        assert!(offline.contains("could not be reached"), "{offline}");
        assert!(!offline.contains("os error"));
        assert!(tile_test_text(&Err(TileError::Status(503))).contains("503"));
    }

    #[test]
    fn chat_errors_read_as_sentences() {
        use rovsti_wa_client::ClientError;
        let raw = ClientError::Tls("handshake failure: alert 40".into());
        let text = chats_error_text(&raw);
        assert!(text.contains("could not be reached"));
        assert!(!text.contains("handshake"));
        assert!(chats_error_text(&ClientError::Unauthorized).contains("connect key"));
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
            record_details(&legacy_record(), 1_700_100_000_000, 0),
            "14 Nov  ·  18 min  ·  3.4 km"
        );
        assert_eq!(record_pair(&legacy_record()), "Kadikoy");
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
            record_details(&r, 1_700_100_000_000, 0),
            "14 Nov  ·  18 min  ·  3.4 km"
        );
        assert_eq!(record_pair(&r), "Home – Work");
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
        let rows = chat_rows(&chats);
        assert_eq!(rows.len(), 200);
        assert_eq!(visible_chats(&rows, "", None, MAX_CHATS).len(), MAX_CHATS);
    }

    fn row(jid: &str, title: &str) -> ChatRow {
        ChatRow {
            jid: jid.into(),
            title: title.into(),
            group: jid.ends_with("@g.us"),
        }
    }

    fn many() -> Vec<ChatRow> {
        (0..100)
            .map(|i| match i {
                80 => row("80@s.whatsapp.net", "Family"),
                81 => row("905321234567@s.whatsapp.net", "Ali"),
                82 => row("82@s.whatsapp.net", "Kadıköy Group"),
                _ => row(&format!("{i}@s.whatsapp.net"), &format!("Chat {i}")),
            })
            .collect()
    }

    #[test]
    fn visible_chats_caps_and_searches_deep() {
        let rows = many();
        assert_eq!(visible_chats(&rows, " ", None, 60).len(), 60);
        let fam = visible_chats(&rows, "fam", None, 60);
        assert_eq!(fam.len(), 1);
        assert_eq!(fam[0].title, "Family");
        assert_eq!(
            visible_chats(&rows, "kadikoy", None, 60)[0].jid,
            "82@s.whatsapp.net"
        );
        assert_eq!(visible_chats(&rows, "5321", None, 60)[0].title, "Ali");
        assert_eq!(visible_chats(&rows, "chat", None, 5).len(), 5);
    }

    #[test]
    fn visible_chats_pins_the_selected_chat_once() {
        let rows = many();
        let out = visible_chats(&rows, "", Some(("80@s.whatsapp.net", "Family")), 10);
        assert_eq!(out.len(), 11);
        assert_eq!(out[0].title, "Family");
        assert_eq!(
            out.iter().filter(|r| r.jid == "80@s.whatsapp.net").count(),
            1
        );
        let inside = visible_chats(&rows, "", Some(("3@s.whatsapp.net", "x")), 10);
        assert_eq!(inside.len(), 10);
        assert_eq!(inside[0].jid, "0@s.whatsapp.net");
        let unknown = visible_chats(&rows, "", Some(("9@g.us", " ")), 10);
        assert_eq!(unknown[0].title, "Selected chat");
        assert!(unknown[0].group);
        // A query shows only matches, no pinned chat.
        assert_eq!(
            visible_chats(&rows, "fam", Some(("9@g.us", "")), 10).len(),
            1
        );
    }

    #[test]
    fn chat_status_covers_each_state() {
        let none = |l: &ChatList| chat_status_text(l, "");
        assert_eq!(none(&ChatList::Idle).unwrap().0, "Loading chats...");
        assert_eq!(none(&ChatList::Loading).unwrap().0, "Loading chats...");
        let failed = none(&ChatList::Failed("offline".into())).unwrap();
        assert_eq!(
            failed,
            (
                "Could not load your chats",
                Some("offline".into()),
                Some("Retry")
            )
        );
        let empty = none(&ChatList::Ready(vec![])).unwrap();
        assert_eq!((empty.0, empty.2), ("No chats yet", Some("Refresh")));
        let ready = ChatList::Ready(many());
        assert_eq!(none(&ready), None);
        assert_eq!(chat_status_text(&ready, "fam"), None);
        assert_eq!(
            chat_status_text(&ready, "zzz"),
            Some(("No chats match", None, None))
        );
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
        let text = android_notice("Test alarm", &Err(AndroidError::NotInitialized)).unwrap();
        assert_eq!(text, "Test alarm did not work. Try again.");
    }

    #[test]
    fn volume_and_pattern_helpers() {
        assert_eq!(volume_text(80), "80 %");
        assert_eq!(volume_text(100), "100 %");
        assert_eq!(volume_text(10), "80 %");
        let mut prefs = StopsPrefs::default();
        assert!(pattern_row_visible(&prefs));
        prefs.vibrate = false;
        assert!(!pattern_row_visible(&prefs));
    }

    #[test]
    fn status_lines() {
        assert_eq!(exact_alarm_text(Some(true)), "Allowed");
        assert!(exact_alarm_text(Some(false)).starts_with("Not allowed"));
        assert_eq!(battery_text(None), "Checked on the phone");
    }
}
