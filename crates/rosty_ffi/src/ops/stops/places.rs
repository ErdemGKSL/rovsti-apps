//! `stops.saved_*` and `stops.places_*`: the saved stops (gates) and their import and export.
//!
//! Stateless over a `saved` value (`{version, next_id, stops}`, the content of `saved.json`): every edit answers
//! the new list, which Kotlin saves with `stops.saved_save`. The rules (names, duplicates, the 200-stop cap, order
//! inside the favourite group) stay in Rust.

use super::pick::saved_sample;
use super::trip::diameter_error_in;
use super::{NOW, coord};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rosty_geo::saved_stops::{self, NEAREST_CHIP_M, SavedSort};
use rosty_geo::{
    Coord, Endpoint, Feed, Lang, SavedStop, SavedStopError, SavedStops, StopColour, places_io,
};
use serde::Deserialize;
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("stops.saved_load", LoadReq, Value, saved_load, load_cases),
    op!("stops.saved_edit", EditReq, Value, saved_edit, edit_cases),
    op!("stops.saved_list", ListReq, Value, saved_list, list_cases),
    op!(
        "stops.saved_suggest_name",
        SuggestReq,
        Value,
        suggest_name,
        suggest_cases
    ),
    op!(
        "stops.saved_shortcut",
        ShortcutReq,
        Value,
        shortcut,
        shortcut_cases
    ),
    op!(
        "stops.places_export",
        ExportReq,
        Value,
        places_export,
        export_cases
    ),
    op!(
        "stops.places_import",
        ImportReq,
        Value,
        places_import,
        import_cases
    ),
];

fn saved_error(lang: Lang, error: SavedStopError) -> FfiError {
    let (reason, id) = match error {
        SavedStopError::InvalidName => ("invalid_name", None),
        SavedStopError::InvalidGate(inner) => {
            return diameter_error_in(lang, inner).with_detail(json!({"reason": "invalid_gate"}));
        }
        SavedStopError::Duplicate(id) => ("duplicate", Some(id)),
        SavedStopError::Full => ("full", None),
        SavedStopError::NotFound => ("not_found", None),
        SavedStopError::NoteTooLong => ("note_too_long", None),
    };
    FfiError::new(ErrorKind::Invalid, error.message_in(lang))
        .with_detail(json!({"reason": reason, "id": id}))
}

// ---- load ----

#[derive(Deserialize)]
struct LoadReq {
    /// The content of `saved.json`; absent for no file.
    #[serde(default)]
    text: Option<String>,
}

fn saved_load(req: LoadReq) -> Result<Value, FfiError> {
    let loaded = saved_stops::load_saved_stops(req.text.as_deref());
    Ok(json!({"saved": loaded.stops, "discarded": loaded.discarded,
              "format_version": saved_stops::FORMAT_VERSION}))
}

fn load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": saved_sample().to_string()})),
        Case::named("missing", json!({})),
        Case::named("old_format", json!({"text": "{\"places\": []}"})),
    ]
}

// ---- edit ----

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Edit {
    Add {
        name: String,
        a: Endpoint,
        b: Endpoint,
    },
    Rename {
        id: u64,
        name: String,
    },
    Remove {
        id: u64,
    },
    ToggleFavourite {
        id: u64,
    },
    CycleColour {
        id: u64,
    },
    SetColour {
        id: u64,
        colour: StopColour,
    },
    MoveBy {
        id: u64,
        delta: isize,
    },
    Restore {
        stop: SavedStop,
        index: usize,
    },
    /// S44: the stop's note; an empty or blank text clears it.
    SetNote {
        id: u64,
        note: String,
    },
}

#[derive(Deserialize)]
struct EditReq {
    saved: SavedStops,
    edit: Edit,
    #[serde(default)]
    now_ms: i64,
    #[serde(default)]
    locale: Lang,
}

fn saved_edit(req: EditReq) -> Result<Value, FfiError> {
    let lang = req.locale;
    let mut saved = req.saved;
    // `id`: the stop touched; `removed`/`index`: what a delete took out (for undo); `flag`: the favourite or
    // moved result; `colour`: the colour now set.
    let id: Option<u64>;
    let mut removed = None;
    let mut index = None;
    let mut flag = None;
    let mut colour = None;
    match req.edit {
        Edit::Add { name, a, b } => {
            id = Some(
                saved
                    .add(&name, a, b, req.now_ms)
                    .map_err(|e| saved_error(lang, e))?,
            )
        }
        Edit::Rename { id: i, name } => {
            saved.rename(i, &name).map_err(|e| saved_error(lang, e))?;
            id = Some(i);
        }
        Edit::Remove { id: i } => {
            let at = saved.stops.iter().position(|s| s.id == i);
            let stop = saved.get(i).cloned();
            if !saved.remove(i) {
                return Err(saved_error(lang, SavedStopError::NotFound));
            }
            id = Some(i);
            removed = stop;
            index = at;
        }
        Edit::ToggleFavourite { id: i } => {
            flag = Some(
                saved
                    .toggle_favourite(i)
                    .ok_or_else(|| saved_error(lang, SavedStopError::NotFound))?,
            );
            id = Some(i);
        }
        Edit::CycleColour { id: i } => {
            colour = Some(
                saved
                    .cycle_colour(i)
                    .ok_or_else(|| saved_error(lang, SavedStopError::NotFound))?,
            );
            id = Some(i);
        }
        Edit::SetColour { id: i, colour: c } => {
            if !saved.set_colour(i, c) {
                return Err(saved_error(lang, SavedStopError::NotFound));
            }
            colour = Some(c);
            id = Some(i);
        }
        Edit::MoveBy { id: i, delta } => {
            flag = Some(saved.move_by(i, delta));
            id = Some(i);
        }
        Edit::Restore { stop, index: at } => {
            id = Some(stop.id);
            saved.restore(stop, at);
        }
        Edit::SetNote { id: i, note } => {
            saved.set_note(i, &note).map_err(|e| saved_error(lang, e))?;
            id = Some(i);
        }
    }
    Ok(
        json!({"saved": saved, "id": id, "removed": removed, "index": index, "flag": flag, "colour": colour}),
    )
}

fn edit_cases() -> Vec<Case> {
    let s = saved_sample();
    let end = |lat: f64, lon: f64| json!({"label": "", "coord": {"lat": lat, "lon": lon}});
    vec![
        Case::new(json!({"saved": s, "now_ms": NOW,
            "edit": {"kind": "add", "name": "Bridge", "a": end(41.1, 29.0), "b": end(41.1, 29.01)}})),
        Case::named(
            "duplicate",
            json!({"saved": s, "now_ms": NOW,
                "edit": {"kind": "add", "name": "Pier again", "a": end(41.0, 28.994), "b": end(41.0, 29.006)}}),
        ),
        Case::named(
            "bad_name",
            json!({"saved": s, "edit": {"kind": "add", "name": "  ", "a": end(41.1, 29.0), "b": end(41.1, 29.01)}}),
        ),
        Case::named(
            "short_gate",
            json!({"saved": s, "edit": {"kind": "add", "name": "Tiny", "a": end(41.1, 29.0), "b": end(41.1, 29.0001)}}),
        ),
        Case::named(
            "rename",
            json!({"saved": s, "edit": {"kind": "rename", "id": 2, "name": "Ferry dock"}}),
        ),
        Case::named(
            "remove",
            json!({"saved": s, "edit": {"kind": "remove", "id": 1}}),
        ),
        Case::named(
            "remove_missing",
            json!({"saved": s, "edit": {"kind": "remove", "id": 99}}),
        ),
        Case::named(
            "favourite",
            json!({"saved": s, "edit": {"kind": "toggle_favourite", "id": 2}}),
        ),
        Case::named(
            "cycle_colour",
            json!({"saved": s, "edit": {"kind": "cycle_colour", "id": 1}}),
        ),
        Case::named(
            "set_colour",
            json!({"saved": s, "edit": {"kind": "set_colour", "id": 2, "colour": "red"}}),
        ),
        Case::named(
            "move",
            json!({"saved": s, "edit": {"kind": "move_by", "id": 2, "delta": -1}}),
        ),
        Case::named(
            "set_note",
            json!({"saved": s, "edit": {"kind": "set_note", "id": 2, "note": "  Back door,\n platform 2  "}}),
        ),
        Case::named(
            "clear_note",
            json!({"saved": {"version": 2, "next_id": 2, "stops": [
                       {"id": 1, "name": "Pier", "a": end(41.0, 28.994), "b": end(41.0, 29.006), "created_ms": 0,
                        "note": "Back door"}]},
                   "edit": {"kind": "set_note", "id": 1, "note": "   "}}),
        ),
        Case::named(
            "note_too_long",
            json!({"saved": s, "edit": {"kind": "set_note", "id": 2, "note": "x".repeat(201)}}),
        ),
        Case::named(
            "note_missing_stop",
            json!({"saved": s, "edit": {"kind": "set_note", "id": 99, "note": "x"}}),
        ),
        Case::named(
            "restore",
            json!({"saved": {"version": 2, "next_id": 1, "stops": []},
                   "edit": {"kind": "restore", "stop": s["stops"][0], "index": 0}}),
        ),
    ]
}

// ---- list ----

#[derive(Deserialize)]
struct ListReq {
    saved: SavedStops,
    #[serde(default)]
    query: String,
    /// `saved` (default), `name` or `nearest`.
    #[serde(default)]
    sort: Option<String>,
    #[serde(default)]
    here: Option<Coord>,
    #[serde(default)]
    locale: Lang,
}

fn saved_list(req: ListReq) -> Result<Value, FfiError> {
    let sort = match req.sort.as_deref() {
        None | Some("saved") => SavedSort::Saved,
        Some("name") => SavedSort::Name,
        Some("nearest") => SavedSort::Nearest,
        Some(other) => return Err(FfiError::invalid(format!("unknown sort {other:?}"))),
    };
    let hits = saved_stops::sorted(req.saved.search(&req.query), sort, req.here);
    let rows: Vec<Value> = hits
        .iter()
        .map(|s| {
            json!({
                "stop": s,
                "center": coord(s.center()),
                "length_m": s.length_m(),
                "valid": s.diameter().is_ok(),
                "distance_m": req.here.map(|h| h.distance_m(s.center())),
                "colour_label": s.colour.label_in(req.locale),
            })
        })
        .collect();
    let nearest = req
        .here
        .and_then(|h| req.saved.nearest(h, NEAREST_CHIP_M))
        .map(|(s, d)| json!({"id": s.id, "name": s.name, "distance_m": d}));
    Ok(json!({
        "rows": rows,
        "total": req.saved.stops.len(),
        "nearest": nearest,
        "nearest_chip_m": NEAREST_CHIP_M,
        "text": saved_stops::list_text_in(req.locale, &hits, req.here),
        "max_stops": saved_stops::MAX_STOPS,
        "max_name_chars": saved_stops::MAX_NAME_CHARS,
        "colours": StopColour::ALL.iter().map(|c| json!({"id": c, "label": c.label_in(req.locale), "next": c.next()})).collect::<Vec<_>>(),
    }))
}

fn list_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"saved": saved_sample(), "sort": "nearest", "here": {"lat": 41.001, "lon": 29.0}}),
        ),
        Case::named("search", json!({"saved": saved_sample(), "query": "FER"})),
        Case::named("by_name", json!({"saved": saved_sample(), "sort": "name"})),
        Case::named(
            "empty",
            json!({"saved": {"version": 2, "next_id": 1, "stops": []}}),
        ),
        Case::named(
            "bad_sort",
            json!({"saved": saved_sample(), "sort": "random"}),
        ),
        super::tr_case(
            json!({"saved": saved_sample(), "sort": "nearest", "here": {"lat": 41.001, "lon": 29.0}}),
        ),
    ]
}

// ---- suggest name / shortcut ----

#[derive(Deserialize)]
struct SuggestReq {
    a: Endpoint,
    b: Endpoint,
    /// The imported feed (the content of `gtfs.json`); absent reads it from `dir`.
    #[serde(default)]
    feed: Option<Feed>,
    /// Where `gtfs.json` is when `feed` is absent; absent for the directory given to `init`.
    #[serde(default)]
    dir: Option<String>,
    /// The names saved already: the suggestion is none of them.
    #[serde(default)]
    names: Vec<String>,
    #[serde(default)]
    locale: Lang,
}

/// A name to prefill when saving the stop AB: never empty, at most `MAX_NAME_CHARS`, none of `names`.
fn suggest_name(req: SuggestReq) -> Result<Value, FfiError> {
    // No feed in the request: the imported one, when the app's directory is known.
    let feed = req.feed.or_else(|| {
        let dir = super::data_dir(&req.dir).ok()?;
        rosty_app_core::storage::load_json(&dir, rosty_app_core::stops::store::GTFS_FILE)
    });
    let names: Vec<&str> = req.names.iter().map(String::as_str).collect();
    Ok(json!({
        "name": saved_stops::suggest_name_in(req.locale, feed.as_ref(), &req.a, &req.b, &names),
    }))
}

pub(super) fn feed_sample() -> Value {
    json!({
        "stops": [
            {"id": "A", "name": "Moda İskele", "coord": {"lat": 41.0001, "lon": 29.0}, "parent": null},
            {"id": "B", "name": "Kadıköy", "coord": {"lat": 40.99, "lon": 29.02}, "parent": null}
        ],
        "routes": [{"id": "R1", "short_name": "14", "long_name": "Moda - Kadıköy", "kind": 3}],
        "patterns": [{"route": 0, "headsign": "Kadıköy", "stops": [0, 1]}],
        "imported_ms": NOW
    })
}

fn suggest_cases() -> Vec<Case> {
    let g = super::gate();
    let unnamed = json!({
        "a": {"label": "Point A", "coord": g["a"]["coord"]},
        "b": {"label": "", "coord": g["b"]["coord"]},
    });
    vec![
        Case::new(json!({"a": g["a"], "b": g["b"], "feed": feed_sample()})),
        Case::named("no_feed", json!({"a": g["a"], "b": g["b"]})),
        Case::named(
            "unnamed",
            json!({"a": unnamed["a"], "b": unnamed["b"], "names": ["Home", "Stop 3"]}),
        ),
        Case::named(
            "taken",
            json!({"a": g["a"], "b": g["b"], "feed": feed_sample(), "names": ["Moda İskele"]}),
        ),
        super::tr_case(json!({"a": unnamed["a"], "b": unnamed["b"], "names": ["Ev"]})),
    ]
}

#[derive(Deserialize)]
struct ShortcutReq {
    stop: SavedStop,
    #[serde(default)]
    locale: Lang,
}

fn shortcut(req: ShortcutReq) -> Result<Value, FfiError> {
    Ok(serde_json::to_value(saved_stops::shortcut_spec_in(
        req.locale, &req.stop,
    ))?)
}

fn shortcut_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"stop": saved_sample()["stops"][0]})),
        Case::named(
            "long_name",
            json!({"stop": {"id": 5, "name": "A very long stop name that gets cut", "a": {"label": "", "coord": {"lat": 41.0, "lon": 29.0}},
                            "b": {"label": "", "coord": {"lat": 41.01, "lon": 29.0}}, "created_ms": 1}}),
        ),
    ]
}

// ---- export / import ----

#[derive(Deserialize)]
struct ExportReq {
    saved: SavedStops,
    /// `json` or `gpx`.
    format: String,
    today: String,
}

fn places_export(req: ExportReq) -> Result<Value, FfiError> {
    let text = match req.format.as_str() {
        "json" => places_io::to_json(&req.saved),
        "gpx" => places_io::to_gpx(&req.saved),
        other => return Err(FfiError::invalid(format!("unknown format {other:?}"))),
    };
    Ok(json!({"text": text, "file_name": places_io::export_file_name(&req.format, &req.today)}))
}

fn export_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"saved": saved_sample(), "format": "gpx", "today": "2026-09-21"})),
        Case::named(
            "json",
            json!({"saved": saved_sample(), "format": "json", "today": "2026-09-21"}),
        ),
        Case::named(
            "bad_format",
            json!({"saved": saved_sample(), "format": "kml", "today": "2026-09-21"}),
        ),
    ]
}

#[derive(Deserialize)]
struct ImportReq {
    saved: SavedStops,
    text: String,
    now_ms: i64,
    /// Add the planned stops to `saved` (the user confirmed); else the list comes back unchanged.
    #[serde(default)]
    apply: bool,
    #[serde(default)]
    locale: Lang,
}

fn places_import(req: ImportReq) -> Result<Value, FfiError> {
    let incoming = places_io::parse_import_in(req.locale, &req.text).map_err(|e| {
        let reason = match e {
            places_io::ImportError::Empty => "empty",
            places_io::ImportError::NotRecognised => "not_recognised",
            places_io::ImportError::TooLarge => "too_large",
        };
        FfiError::invalid(e.message_in(req.locale)).with_detail(json!({"reason": reason}))
    })?;
    let plan = places_io::merge_plan(&req.saved, incoming);
    let mut saved = req.saved;
    let added = if req.apply {
        places_io::apply_plan(&mut saved, &plan, req.now_ms)
    } else {
        0
    };
    let add: Vec<Value> = plan
        .add
        .iter()
        .map(|i| json!({"name": i.name, "a": i.a, "b": i.b, "favourite": i.favourite, "colour": i.colour, "note": i.note}))
        .collect();
    Ok(json!({
        "plan": {"add": add, "duplicates": plan.duplicates, "invalid": plan.invalid, "over_cap": plan.over_cap},
        "summary": places_io::summary_in(req.locale, &plan),
        "added": added,
        "saved": saved,
        "max_bytes": places_io::MAX_IMPORT_BYTES,
    }))
}

fn import_cases() -> Vec<Case> {
    let gpx = "<?xml version=\"1.0\"?><gpx version=\"1.1\"><rte><name>Bridge</name>\
        <rtept lat=\"41.1\" lon=\"29.0\"/><rtept lat=\"41.1\" lon=\"29.01\"/></rte></gpx>";
    vec![
        Case::new(json!({"saved": saved_sample(), "text": gpx, "now_ms": NOW})),
        Case::named(
            "apply",
            json!({"saved": saved_sample(), "text": gpx, "now_ms": NOW, "apply": true}),
        ),
        Case::named(
            "empty",
            json!({"saved": saved_sample(), "text": "", "now_ms": NOW}),
        ),
        Case::named(
            "garbage",
            json!({"saved": saved_sample(), "text": "hello", "now_ms": NOW}),
        ),
        super::tr_case(json!({"saved": saved_sample(), "text": gpx, "now_ms": NOW})),
    ]
}
