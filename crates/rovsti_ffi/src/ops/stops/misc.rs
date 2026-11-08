//! The smaller Stops ops: preferences edits, the arrival message, the alarm plan, polling, the GTFS import,
//! coordinate texts, the measure tool and the backup file. All stateless.

use super::history::sample as history_sample;
use super::pick::saved_sample;
use super::{NOW, TZ, coord, data_dir};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::stops::store::GTFS_FILE;
use rovsti_app_core::storage;
use rovsti_geo::alarm::{self, VibrationPattern};
use rovsti_geo::coord_format::{self, CoordFormat};
use rovsti_geo::poll;
use rovsti_geo::prefs::{
    Appearance, NOTICE_MINUTES, NoticePrefs, NoticeWhen, SNOOZE_CHOICES, TILE_CACHE_CHOICES_MB,
};
use rovsti_geo::tiles::{ATTRIBUTION, DEFAULT_TILE_URL, TileSource};
use rovsti_geo::{
    AlarmSound, Coord, Feed, History, Measure, SavedStops, StopsPrefs, backup, notice, read_zip,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::time::Duration;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "stops.prefs_edit",
        PrefsEditReq,
        Value,
        prefs_edit,
        prefs_edit_cases
    ),
    op!(
        "stops.prefs_choices",
        ChoicesReq,
        Value,
        prefs_choices,
        choices_cases
    ),
    op!(
        "stops.notice_render",
        RenderReq,
        Value,
        notice_render,
        render_cases
    ),
    op!(
        "stops.notice_validate",
        ValidateReq,
        Value,
        notice_validate,
        validate_cases
    ),
    op!("stops.alarm_plan", PlanReq, Value, alarm_plan, plan_cases),
    op!("stops.poll", PollReq, Value, poll_op, poll_cases),
    op!(
        "stops.gtfs_import",
        ImportReq,
        Value,
        gtfs_import,
        import_cases
    ),
    op!(
        "stops.gtfs_search",
        SearchReq,
        Value,
        gtfs_search,
        search_cases
    ),
    op!(
        "stops.coord_format",
        FormatReq,
        Value,
        coord_format_op,
        format_cases
    ),
    op!(
        "stops.coord_parse",
        ParseReq,
        Value,
        coord_parse,
        parse_cases
    ),
    op!("stops.measure", MeasureReq, Value, measure, measure_cases),
    op!(
        "stops.backup_export",
        ExportReq,
        Value,
        backup_export,
        export_cases
    ),
    op!(
        "stops.backup_parse",
        BackupParseReq,
        Value,
        backup_parse,
        backup_parse_cases
    ),
    op!(
        "stops.dest_parse",
        DestParseReq,
        Value,
        dest_parse,
        dest_parse_cases
    ),
    op!(
        "stops.dest_share",
        DestShareReq,
        Value,
        dest_share,
        dest_share_cases
    ),
    op!(
        "stops.format_distance",
        DistanceReq,
        Value,
        format_distance,
        distance_cases
    ),
];

// ---- prefs ----

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum PrefsEdit {
    ToggleSound,
    ToggleVibrate,
    NextSnooze,
    NextTileCache,
    NextAppearance,
    NextVibration,
    NextVolume,
    AlarmDefaults,
    SetTemplate { text: String },
    SetNotice { chat: String, chat_name: String },
    SetNoticeWhen { when: NoticeWhen },
    ClearNotice,
    SetTileUrl { url: Option<String> },
    SetSound { uri: String, title: String },
    ClearSound,
    SetShowSaved { value: bool },
}

#[derive(Deserialize)]
struct PrefsEditReq {
    prefs: StopsPrefs,
    edit: PrefsEdit,
}

fn rejected(message: String, reason: &str) -> FfiError {
    FfiError::invalid(message).with_detail(json!({"reason": reason}))
}

/// One named edit, so the rules (the sound/vibrate pair keeps one on, the choices cycle, the template and tile URL
/// are validated) stay in Rust and Kotlin only saves the result.
fn prefs_edit(req: PrefsEditReq) -> Result<Value, FfiError> {
    let mut p = req.prefs;
    let mut changed = true;
    match req.edit {
        PrefsEdit::ToggleSound => changed = p.toggle_sound(),
        PrefsEdit::ToggleVibrate => changed = p.toggle_vibrate(),
        PrefsEdit::NextSnooze => p.snooze_min = p.next_snooze(),
        PrefsEdit::NextTileCache => p.tile_cache_mb = p.next_tile_cache(),
        PrefsEdit::NextAppearance => p.appearance = p.appearance.next(),
        PrefsEdit::NextVibration => p.vibration = p.vibration.next(),
        PrefsEdit::NextVolume => p.volume_pct = alarm::next_volume(p.volume_pct),
        PrefsEdit::AlarmDefaults => p = p.with_alarm_defaults(),
        PrefsEdit::SetTemplate { text } => {
            let mut n = p.notice.take().unwrap_or_default();
            let result = n.set_template(&text);
            p.notice = Some(n);
            result.map_err(|e| rejected(e.to_string(), "template"))?;
        }
        PrefsEdit::SetNotice { chat, chat_name } => {
            let n = p.notice.get_or_insert_with(NoticePrefs::default);
            n.chat = chat;
            n.chat_name = chat_name;
        }
        PrefsEdit::SetNoticeWhen { when } => {
            p.notice.get_or_insert_with(NoticePrefs::default).when = when;
        }
        PrefsEdit::ClearNotice => p.notice = None,
        PrefsEdit::SetTileUrl { url } => {
            match url.as_deref().map(str::trim).filter(|u| !u.is_empty()) {
                None => p.tile_url = None,
                Some(text) => {
                    let source =
                        TileSource::parse(text).map_err(|e| rejected(e.to_string(), "tile_url"))?;
                    p.set_tile_url(&source);
                }
            }
        }
        PrefsEdit::SetSound { uri, title } => p.alarm_sound = Some(AlarmSound { uri, title }),
        PrefsEdit::ClearSound => p.alarm_sound = None,
        PrefsEdit::SetShowSaved { value } => p.show_saved_on_map = value,
    }
    Ok(json!({
        "prefs": p,
        "changed": changed,
        "alarm_is_default": p.alarm_is_default(),
        "alarm_sound_label": p.alarm_sound_label(),
        "tile_cache_bytes": p.tile_cache_bytes(),
        "tile_url": p.tile_source().template(),
    }))
}

fn prefs_edit_cases() -> Vec<Case> {
    let p = || json!(StopsPrefs::default());
    let one_sound_only = json!(StopsPrefs {
        vibrate: false,
        ..StopsPrefs::default()
    });
    vec![
        Case::new(json!({"prefs": p(), "edit": {"kind": "toggle_vibrate"}})),
        Case::named(
            "sound_blocked",
            json!({"prefs": one_sound_only, "edit": {"kind": "toggle_sound"}}),
        ),
        Case::named(
            "next_snooze",
            json!({"prefs": p(), "edit": {"kind": "next_snooze"}}),
        ),
        Case::named(
            "next_tile_cache",
            json!({"prefs": p(), "edit": {"kind": "next_tile_cache"}}),
        ),
        Case::named(
            "next_appearance",
            json!({"prefs": p(), "edit": {"kind": "next_appearance"}}),
        ),
        Case::named(
            "next_vibration",
            json!({"prefs": p(), "edit": {"kind": "next_vibration"}}),
        ),
        Case::named(
            "next_volume",
            json!({"prefs": p(), "edit": {"kind": "next_volume"}}),
        ),
        Case::named(
            "alarm_defaults",
            json!({"prefs": json!(StopsPrefs {snooze_min: 15, ..StopsPrefs::default()}),
                                            "edit": {"kind": "alarm_defaults"}}),
        ),
        Case::named(
            "template",
            json!({"prefs": p(), "edit": {"kind": "set_template", "text": " On my way to {stop} "}}),
        ),
        Case::named(
            "template_empty",
            json!({"prefs": p(), "edit": {"kind": "set_template", "text": "  "}}),
        ),
        Case::named(
            "notice",
            json!({"prefs": p(), "edit": {"kind": "set_notice", "chat": "90532@s.whatsapp.net",
                                                          "chat_name": "Ayşe"}}),
        ),
        Case::named(
            "notice_when",
            json!({"prefs": p(), "edit": {"kind": "set_notice_when", "when": {"MinutesBefore": 10}}}),
        ),
        Case::named(
            "tile_url",
            json!({"prefs": p(), "edit": {"kind": "set_tile_url",
                                       "url": "https://tiles.example.org/{z}/{x}/{y}"}}),
        ),
        Case::named(
            "tile_url_bad",
            json!({"prefs": p(), "edit": {"kind": "set_tile_url", "url": "ftp://x"}}),
        ),
        Case::named(
            "sound",
            json!({"prefs": p(), "edit": {"kind": "set_sound", "uri": "content://media/1",
                                                          "title": "Birds"}}),
        ),
        Case::named(
            "show_saved",
            json!({"prefs": p(), "edit": {"kind": "set_show_saved", "value": false}}),
        ),
    ]
}

#[derive(Deserialize)]
struct ChoicesReq {}

/// The fixed choices the settings page lists, with their labels.
fn prefs_choices(_: ChoicesReq) -> Result<Value, FfiError> {
    Ok(json!({
        "snooze_min": SNOOZE_CHOICES,
        "tile_cache_mb": TILE_CACHE_CHOICES_MB,
        "notice_minutes": NOTICE_MINUTES,
        "volume_pct": alarm::VOLUME_CHOICES,
        "min_volume_pct": alarm::MIN_ALARM_VOLUME_PCT,
        "appearance": Appearance::ALL.iter().map(|a| json!({"id": a, "label": a.label()})).collect::<Vec<_>>(),
        "vibration": VibrationPattern::ALL.iter().map(|v| json!({"id": v, "label": v.label(),
            "pattern_ms": v.pattern_ms(), "preview_ms": v.preview_ms()})).collect::<Vec<_>>(),
        "notice_when": NoticeWhen::ALL.iter().map(|w| json!({"id": w, "label": w.label()})).collect::<Vec<_>>(),
        "default_template": notice::DEFAULT_TEMPLATE,
        "default_tile_url": DEFAULT_TILE_URL,
        "attribution": ATTRIBUTION,
    }))
}

fn choices_cases() -> Vec<Case> {
    vec![Case::new(json!({}))]
}

// ---- notice ----

#[derive(Deserialize)]
struct RenderReq {
    template: String,
    stop: String,
    #[serde(default)]
    eta_s: Option<u64>,
}

fn notice_render(req: RenderReq) -> Result<Value, FfiError> {
    Ok(
        json!({"text": notice::render(&req.template, &req.stop, req.eta_s.map(Duration::from_secs))}),
    )
}

fn render_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"template": notice::DEFAULT_TEMPLATE, "stop": "Work", "eta_s": 540})),
        Case::named(
            "no_eta",
            json!({"template": notice::DEFAULT_TEMPLATE, "stop": "Work"}),
        ),
    ]
}

#[derive(Deserialize)]
struct ValidateReq {
    template: String,
}

/// `ok` when the template can be saved; else `invalid` with the reason in `message`.
fn notice_validate(req: ValidateReq) -> Result<Value, FfiError> {
    notice::validate(&req.template).map_err(|e| {
        let reason = match e {
            notice::TemplateError::Empty => "empty",
            notice::TemplateError::TooLong => "too_long",
        };
        rejected(e.to_string(), reason)
    })?;
    Ok(json!({"max_chars": 500, "trimmed": req.template.trim()}))
}

fn validate_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"template": " Arriving at {stop} "})),
        Case::named("empty", json!({"template": "   "})),
        Case::named("too_long", json!({"template": "x".repeat(501)})),
    ]
}

// ---- alarm / poll ----

#[derive(Deserialize)]
struct PlanReq {
    prefs: StopsPrefs,
}

fn alarm_plan(req: PlanReq) -> Result<Value, FfiError> {
    let p = &req.prefs;
    let test_ring: Value = serde_json::from_str(&p.test_ring_json())?;
    Ok(json!({
        "plan": p.alarm_plan(),
        "test_ring": test_ring,
        "sound": p.sound,
        "vibrate": p.vibrate,
        "next_volume_pct": alarm::next_volume(p.volume_pct),
        "alarm_sound_label": p.alarm_sound_label(),
    }))
}

fn plan_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"prefs": StopsPrefs::default()})),
        Case::named(
            "custom",
            json!({"prefs": StopsPrefs {
            volume_pct: 100,
            vibration: VibrationPattern::Heartbeat,
            alarm_sound: Some(AlarmSound {uri: "content://media/1".into(), title: "Birds".into()}),
            ..StopsPrefs::default()}}),
        ),
    ]
}

#[derive(Deserialize)]
struct PollReq {
    #[serde(default)]
    line_m: Option<f64>,
    #[serde(default)]
    accuracy_m: Option<f64>,
}

/// Seconds until the next fix is wanted, from the distance to the line.
fn poll_op(req: PollReq) -> Result<Value, FfiError> {
    let interval = poll::poll_interval(req.line_m, req.accuracy_m);
    Ok(json!({
        "interval_ms": interval.as_millis() as u64,
        "retry_ms": poll::retry_after(interval).as_millis() as u64,
        "min_ms": poll::MIN_POLL.as_millis() as u64,
        "max_ms": poll::MAX_POLL.as_millis() as u64,
        "idle_ms": poll::IDLE_POLL.as_millis() as u64,
    }))
}

fn poll_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"line_m": 5000.0, "accuracy_m": 10.0})),
        Case::named("close", json!({"line_m": 30.0, "accuracy_m": 10.0})),
        Case::named("unknown", json!({})),
    ]
}

// ---- gtfs ----

#[derive(Deserialize)]
struct ImportReq {
    /// Path of the GTFS zip (`stops.txt`, `routes.txt`, `trips.txt`, `stop_times.txt` at its root).
    zip: String,
    now_ms: i64,
    /// Save the feed as `gtfs.json` in the data directory (`save`), so `stops.gtfs_search` and the picker find it.
    #[serde(default)]
    save: bool,
    #[serde(default)]
    dir: Option<String>,
}

fn gtfs_import(req: ImportReq) -> Result<Value, FfiError> {
    let file = std::fs::File::open(&req.zip)?;
    let (mut feed, report) = read_zip(std::io::BufReader::new(file))
        .map_err(|e| FfiError::new(crate::envelope::ErrorKind::Parse, e.to_string()))?;
    feed.imported_ms = req.now_ms;
    if req.save {
        storage::save_json(&data_dir(&req.dir)?, GTFS_FILE, &feed)?;
    }
    Ok(json!({
        "report": {
            "stops": report.stops, "routes": report.routes, "trips": report.trips,
            "stop_times": report.stop_times, "patterns": report.patterns,
            "ungrouped_trips": report.ungrouped_trips, "skipped_rows": report.skipped_rows,
        },
        "saved": req.save,
        "imported_ms": req.now_ms,
    }))
}

fn import_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"zip": "$DATA/gtfs-tiny.zip", "now_ms": NOW})),
        Case::named(
            "saved",
            json!({"zip": "$DATA/gtfs-tiny.zip", "now_ms": NOW, "save": true, "dir": "$DIR"}),
        )
        .seeded("stops_files"),
        Case::named(
            "missing_file",
            json!({"zip": "$DATA/nope.zip", "now_ms": NOW}),
        ),
        Case::named(
            "not_a_zip",
            json!({"zip": "$DATA/README.md", "now_ms": NOW}),
        ),
    ]
}

#[derive(Deserialize)]
struct SearchReq {
    #[serde(default)]
    dir: Option<String>,
    query: String,
    #[serde(default)]
    near: Option<Coord>,
    limit: usize,
}

/// Stops of the imported feed matching `query`, each with the lines that call there. No feed is an empty list.
fn gtfs_search(req: SearchReq) -> Result<Value, FfiError> {
    let feed: Option<Feed> = storage::load_json(&data_dir(&req.dir)?, GTFS_FILE);
    let Some(feed) = feed else {
        return Ok(json!({"has_feed": false, "imported_ms": null, "rows": []}));
    };
    let rows: Vec<Value> = feed
        .search(&req.query, req.near, req.limit)
        .into_iter()
        .filter_map(|i| {
            let stop = feed.stops.get(i as usize)?;
            let patterns: Vec<Value> = feed
                .patterns_through(i)
                .into_iter()
                .filter_map(|p| {
                    let pattern = feed.patterns.get(p as usize)?;
                    let route = feed.routes.get(pattern.route as usize)?;
                    Some(json!({"index": p, "route_short": route.short_name, "route_long": route.long_name,
                                "kind": route.kind, "headsign": pattern.headsign}))
                })
                .collect();
            Some(json!({"index": i, "id": stop.id, "name": stop.name, "coord": stop.coord,
                        "distance_m": req.near.map(|n| n.distance_m(stop.coord)), "patterns": patterns}))
        })
        .collect();
    Ok(json!({"has_feed": true, "imported_ms": feed.imported_ms, "rows": rows}))
}

fn search_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "query": "kadikoy", "limit": 5})).seeded("stops_gtfs"),
        Case::named(
            "no_feed",
            json!({"dir": "$DIR", "query": "kadikoy", "limit": 5}),
        )
        .seeded("stops_files"),
    ]
}

// ---- coordinates ----

#[derive(Deserialize)]
struct FormatReq {
    at: Coord,
    #[serde(default)]
    label: Option<String>,
    /// Where the user is, for the "1.2 km from you, NE" line.
    #[serde(default)]
    here: Option<Coord>,
    #[serde(default)]
    zoom: Option<f64>,
}

fn coord_format_op(req: FormatReq) -> Result<Value, FfiError> {
    let formats: Vec<Value> = CoordFormat::ALL
        .iter()
        .map(|&f| {
            json!({"id": format!("{f:?}").to_lowercase(), "label": f.label(),
                   "text": coord_format::format(req.at, f, req.label.as_deref())})
        })
        .collect();
    let zoom = req.zoom;
    Ok(json!({
        "formats": formats,
        "from_you": req.here.map(|h| coord_format::from_you_text(h, req.at)),
        "bearing": req.here.map(|h| coord_format::cardinal(h.bearing_deg(req.at))),
        "view": zoom.map(|z| json!({
            "osm_url": coord_format::osm_view_url(req.at, z),
            "geo_url": coord_format::geo_view_url(req.at, z),
            "share_text": coord_format::map_view_share_text(req.at, z),
        })),
        "plus_code": coord_format::plus_code(req.at),
    }))
}

fn format_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"at": {"lat": 41.0082, "lon": 28.9784}, "label": "Sultanahmet",
                         "here": {"lat": 41.0370, "lon": 28.9850}, "zoom": 15.4}),
        ),
        Case::named("plain", json!({"at": {"lat": -33.8688, "lon": 151.2093}})),
    ]
}

#[derive(Deserialize)]
struct ParseReq {
    text: String,
}

fn coord_parse(req: ParseReq) -> Result<Value, FfiError> {
    let c = coord_format::parse_coord(&req.text);
    Ok(json!({"coord": c.map(coord)}))
}

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "41°00'29.5\"N 28°58'42.2\"E"})),
        Case::named("decimal", json!({"text": "41.0082, 28.9784"})),
        Case::named("nothing", json!({"text": "hello"})),
    ]
}

// ---- measure ----

#[derive(Deserialize)]
struct MeasureReq {
    points: Vec<Coord>,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    today: Option<String>,
}

/// The measure tool over a list of points (Kotlin keeps the list; undo is dropping the last). Points past the cap
/// are ignored, the same as `Measure::push`.
fn measure(req: MeasureReq) -> Result<Value, FfiError> {
    let mut m = Measure::new();
    let mut accepted = 0usize;
    for p in &req.points {
        if m.push(*p) {
            accepted += 1;
        }
    }
    Ok(json!({
        "accepted": accepted,
        "legs_m": m.legs_m(),
        "total_m": m.total_m(),
        "summary": m.summary_text(),
        "gpx": m.to_gpx(req.name.as_deref().unwrap_or("Measurement")),
        "gate": m.as_gate().map(|(a, b)| json!({"a": a, "b": b})),
        "file_name": req.today.as_deref().map(Measure::export_file_name),
        "max_points": rovsti_geo::measure::MAX_POINTS,
    }))
}

fn measure_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"points": [{"lat": 41.0, "lon": 28.994}, {"lat": 41.0, "lon": 29.0},
                                    {"lat": 41.01, "lon": 29.0}], "name": "Walk", "today": "2026-09-21"}),
        ),
        Case::named(
            "one_point",
            json!({"points": [{"lat": 41.0, "lon": 28.994}]}),
        ),
        Case::named("empty", json!({"points": []})),
    ]
}

// ---- backup ----

#[derive(Deserialize)]
struct ExportReq {
    saved: SavedStops,
    history: History,
    prefs: StopsPrefs,
    now_ms: i64,
    tz_offset_s: i32,
    today: String,
}

fn backup_export(req: ExportReq) -> Result<Value, FfiError> {
    let b = backup::Backup::new(req.saved, req.history, req.prefs, req.now_ms);
    Ok(json!({
        "text": backup::to_json(&b),
        "file_name": backup::export_file_name(&req.today),
        "summary": backup::summary(&b, req.tz_offset_s),
    }))
}

fn export_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"saved": saved_sample(), "history": history_sample(), "prefs": StopsPrefs::default(),
                          "now_ms": NOW, "tz_offset_s": TZ, "today": "2026-09-21"}),
    )]
}

#[derive(Deserialize)]
struct BackupParseReq {
    text: String,
    tz_offset_s: i32,
}

fn backup_parse(req: BackupParseReq) -> Result<Value, FfiError> {
    let b = backup::parse(&req.text).map_err(|e| {
        let reason = match e {
            backup::BackupError::NotJson => "not_json",
            backup::BackupError::NotStops => "not_stops",
            backup::BackupError::Newer => "newer",
            backup::BackupError::BadStops => "bad_stops",
        };
        rejected(e.message().to_owned(), reason)
    })?;
    Ok(json!({
        "summary": backup::summary(&b, req.tz_offset_s),
        "created_ms": b.created_ms,
        "saved": b.saved,
        "history": b.history,
        "prefs": b.prefs,
    }))
}

fn backup_parse_cases() -> Vec<Case> {
    let good = backup::to_json(&backup::Backup::new(
        serde_json::from_value(saved_sample()).unwrap_or_default(),
        serde_json::from_value(history_sample()).unwrap_or_default(),
        StopsPrefs::default(),
        NOW,
    ));
    vec![
        Case::new(json!({"text": good, "tz_offset_s": TZ})),
        Case::named("not_json", json!({"text": "hello", "tz_offset_s": 0})),
        Case::named(
            "other_app",
            json!({"text": "{\"app\":\"rovsti-phone\",\"version\":1,\"created_ms\":1,\"saved\":{\"version\":2,\"next_id\":1,\"stops\":[]},\"history\":{},\"prefs\":{}}", "tz_offset_s": 0}),
        ),
        Case::named(
            "newer",
            json!({"text": "{\"app\":\"rovsti-stops\",\"version\":99,\"created_ms\":1,\"saved\":{\"version\":2,\"next_id\":1,\"stops\":[]},\"history\":{},\"prefs\":{}}", "tz_offset_s": 0}),
        ),
    ]
}

// ---- distance ----

#[derive(Deserialize)]
struct DistanceReq {
    metres: f64,
}

fn format_distance(req: DistanceReq) -> Result<Value, FfiError> {
    Ok(json!({"text": rovsti_geo::format_distance(req.metres)}))
}

fn distance_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"metres": 450.0})),
        Case::named("km", json!({"metres": 1840.0})),
    ]
}

// ---- destinations from shared text ----

#[derive(Deserialize)]
struct DestParseReq {
    text: String,
}

fn place_json(p: &rovsti_geo::SharedPlace) -> Value {
    json!({"coord": p.coord, "label": p.label})
}

/// Reads shared text (a maps link, a `geo:` URI, bare coordinates, or the two-link text of a shared stop).
/// A stop share answers `stop`; a single place answers `place`; anything else is `invalid` (the reason in `message`).
fn dest_parse(req: DestParseReq) -> Result<Value, FfiError> {
    if let Some((name, a, b)) = rovsti_geo::parse_stop_share(&req.text) {
        return Ok(json!({"kind": "stop", "place": null,
                         "stop": {"name": name, "a": place_json(&a), "b": place_json(&b)}}));
    }
    let place = rovsti_geo::parse_location(&req.text).map_err(|e| {
        let reason = match e {
            rovsti_geo::DestError::NoCoordinates => "no_coordinates",
            rovsti_geo::DestError::OutOfRange => "out_of_range",
            rovsti_geo::DestError::NotALocation => "not_a_location",
        };
        rejected(e.message().to_owned(), reason)
    })?;
    Ok(json!({"kind": "place", "place": place_json(&place), "stop": null}))
}

fn dest_parse_cases() -> Vec<Case> {
    let two =
        rovsti_geo::stop_share_text("Pier", Coord::new(41.0, 28.994), Coord::new(41.0, 29.006));
    vec![
        Case::new(
            json!({"text": "Sultanahmet\nhttps://www.openstreetmap.org/?mlat=41.00820&mlon=28.97840#map=17/41.00820/28.97840"}),
        ),
        Case::named(
            "geo_uri",
            json!({"text": "geo:41.0082,28.9784?q=41.0082,28.9784(Sultanahmet)"}),
        ),
        Case::named("stop_share", json!({"text": two})),
        Case::named("bare", json!({"text": "41.0082, 28.9784"})),
        Case::named(
            "short_link",
            json!({"text": "https://maps.app.goo.gl/abc123"}),
        ),
        Case::named("nothing", json!({"text": "see you at six"})),
    ]
}

#[derive(Deserialize)]
struct DestShareReq {
    #[serde(default)]
    label: String,
    at: Coord,
    /// The second point of a stop; with it `stop_text` is written under `name`.
    #[serde(default)]
    b: Option<Coord>,
    #[serde(default)]
    name: String,
}

fn dest_share(req: DestShareReq) -> Result<Value, FfiError> {
    Ok(json!({
        "text": rovsti_geo::share_text(&req.label, req.at),
        "stop_text": req.b.map(|b| rovsti_geo::stop_share_text(&req.name, req.at, b)),
        "qr_payload": rovsti_geo::point_qr_payload(req.at, if req.label.trim().is_empty() { None } else { Some(&req.label) }),
    }))
}

fn dest_share_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"label": "Work", "at": {"lat": 41.0, "lon": 29.006},
                         "b": {"lat": 41.0, "lon": 28.994}, "name": "Pier"})),
        Case::named("unnamed", json!({"at": {"lat": 41.0, "lon": 29.006}})),
    ]
}
