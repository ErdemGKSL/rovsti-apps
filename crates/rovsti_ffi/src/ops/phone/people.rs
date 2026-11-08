//! `speed_dial.*` and `blocklist.*`: the numbers the owner keeps on lists.

use super::{NOW, entries};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::blocklist::{
    self, BlockInputError, BlockRefusal, BlockSet, BlockedLine, BulkParse, FileImport,
};
use rovsti_core::settings::SpeedDialEntry;
use rovsti_core::speed_dial::{self, HoldDigit, SpeedDialError};
use rovsti_core::wire::{BlockedNumber, CallLogEntry, EmergencyList};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashMap};

pub(super) const OPS: &[OpSpec] = &[
    op!("speed_dial.on_hold", HoldReq, HoldAns, on_hold, hold_cases),
    op!("speed_dial.assign", AssignReq, MapAns, assign, assign_cases),
    op!(
        "speed_dial.unassign",
        UnassignReq,
        UnassignAns,
        unassign,
        unassign_cases
    ),
    op!(
        "speed_dial.free_digits",
        MapReq,
        FreeAns,
        free_digits,
        free_cases
    ),
    op!(
        "speed_dial.digit_for",
        DigitForReq,
        DigitForAns,
        digit_for,
        digit_for_cases
    ),
    op!("speed_dial.badges", MapReq, BadgesAns, badges, badges_cases),
    op!("blocklist.check", CheckReq, CheckAns, check, check_cases),
    op!(
        "blocklist.parse_input",
        ParseReq,
        ParseAns,
        parse_input,
        parse_input_cases
    ),
    op!(
        "blocklist.parse_many",
        ManyReq,
        ManyAns,
        parse_many,
        many_cases
    ),
    op!(
        "blocklist.parse_file",
        ManyReq,
        FileAns,
        parse_file,
        file_cases
    ),
    op!("blocklist.lines", LinesReq, LinesAns, lines, lines_cases),
    op!(
        "blocklist.call_line",
        CallLineReq,
        BlockedLine,
        call_line,
        call_line_cases
    ),
];

type Map = BTreeMap<u8, SpeedDialEntry>;

// ---- speed_dial.* ----

fn sample_map() -> Value {
    json!({"2": {"lookup_key": "k1", "number_raw": "0532 123 45 67", "name": "Ayşe Yılmaz"},
           "5": {"lookup_key": "k2", "number_raw": "+90 212 555 01 02", "name": "Mehmet Demir"}})
}

#[derive(Deserialize)]
struct MapReq {
    map: Map,
}

#[derive(Serialize, Deserialize)]
struct MapAns {
    map: Map,
}

#[derive(Deserialize)]
struct HoldReq {
    digit: char,
    /// What the number field held before the press.
    dialed_before: String,
    map: Map,
}

#[derive(Serialize)]
struct HoldAns {
    result: HoldDigit,
}

fn on_hold(req: HoldReq) -> Result<HoldAns, FfiError> {
    Ok(HoldAns {
        result: speed_dial::on_hold(req.digit, &req.dialed_before, &req.map),
    })
}

fn hold_cases() -> Vec<Case> {
    let case = |label: &'static str, digit: &str, before: &str| {
        Case::named(
            label,
            json!({"digit": digit, "dialed_before": before, "map": sample_map()}),
        )
    };
    vec![
        Case::new(json!({"digit": "2", "dialed_before": "", "map": sample_map()})),
        case("voicemail", "1", ""),
        case("unassigned", "7", ""),
        case("field_not_empty", "2", "0"),
        case("zero", "0", ""),
    ]
}

#[derive(Deserialize)]
struct AssignReq {
    map: Map,
    digit: u8,
    entry: SpeedDialEntry,
}

fn speed_error(error: SpeedDialError) -> FfiError {
    let reason = match error {
        SpeedDialError::Digit(_) => "digit",
        SpeedDialError::EmptyNumber => "empty_number",
        SpeedDialError::TooLong => "too_long",
    };
    FfiError::invalid(error.to_string()).with_detail(json!({"reason": reason}))
}

fn assign(mut req: AssignReq) -> Result<MapAns, FfiError> {
    speed_dial::assign(&mut req.map, req.digit, req.entry).map_err(speed_error)?;
    Ok(MapAns { map: req.map })
}

fn assign_cases() -> Vec<Case> {
    let entry =
        json!({"lookup_key": "k3", "number_raw": "  0555 000 11 22 ", "name": " Cem Aksoy "});
    vec![
        Case::new(json!({"map": sample_map(), "digit": 3, "entry": entry})),
        Case::named(
            "bad_digit",
            json!({"map": sample_map(), "digit": 1, "entry": entry}),
        ),
        Case::named(
            "empty_number",
            json!({"map": {}, "digit": 4, "entry": {"lookup_key": "", "number_raw": "", "name": "x"}}),
        ),
    ]
}

#[derive(Deserialize)]
struct UnassignReq {
    map: Map,
    digit: u8,
}

#[derive(Serialize)]
struct UnassignAns {
    removed: bool,
    map: Map,
}

fn unassign(mut req: UnassignReq) -> Result<UnassignAns, FfiError> {
    let removed = speed_dial::unassign(&mut req.map, req.digit);
    Ok(UnassignAns {
        removed,
        map: req.map,
    })
}

fn unassign_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"map": sample_map(), "digit": 2})),
        Case::named("empty_slot", json!({"map": sample_map(), "digit": 9})),
    ]
}

#[derive(Serialize)]
struct FreeAns {
    digits: Vec<u8>,
}

fn free_digits(req: MapReq) -> Result<FreeAns, FfiError> {
    Ok(FreeAns {
        digits: speed_dial::free_digits(&req.map).collect(),
    })
}

fn free_cases() -> Vec<Case> {
    vec![Case::new(json!({"map": sample_map()}))]
}

#[derive(Deserialize)]
struct DigitForReq {
    map: Map,
    numbers: Vec<String>,
}

#[derive(Serialize)]
struct DigitForAns {
    digit: Option<u8>,
}

fn digit_for(req: DigitForReq) -> Result<DigitForAns, FfiError> {
    let numbers: Vec<&str> = req.numbers.iter().map(String::as_str).collect();
    Ok(DigitForAns {
        digit: speed_dial::digit_for(&req.map, &numbers),
    })
}

fn digit_for_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"map": sample_map(), "numbers": ["+905321234567"]})),
        Case::named(
            "none",
            json!({"map": sample_map(), "numbers": ["+10000000000"]}),
        ),
    ]
}

#[derive(Serialize)]
struct Badge {
    digit: u8,
    name: String,
    hue: Option<usize>,
}

#[derive(Serialize)]
struct BadgesAns {
    /// One entry per assigned key, digits 2 to 9; free keys and 1 (voicemail) are absent.
    badges: Vec<Badge>,
}

fn badges(req: MapReq) -> Result<BadgesAns, FfiError> {
    let badges = speed_dial::badges(&req.map)
        .into_iter()
        .enumerate()
        .filter_map(|(i, slot)| {
            slot.map(|(name, hue)| Badge {
                digit: i as u8 + 1,
                name,
                hue,
            })
        })
        .collect();
    Ok(BadgesAns { badges })
}

fn badges_cases() -> Vec<Case> {
    vec![Case::new(json!({"map": sample_map()}))]
}

// ---- blocklist.* ----

fn emergency() -> Value {
    json!({"numbers": ["155"]})
}

fn blocked_rows() -> Value {
    json!([
        {"id": 1, "number_raw": "+49 151 2345678", "normalized": "+491512345678"},
        {"id": 2, "number_raw": "0850 333 44 55", "normalized": "08503334455"}
    ])
}

#[derive(Deserialize)]
struct CheckReq {
    raw: String,
    #[serde(default)]
    emergency: EmergencyList,
    #[serde(default)]
    blocked: Vec<BlockedNumber>,
}

#[derive(Serialize)]
struct CheckAns {
    /// `None` when the number may be blocked.
    refusal: Option<BlockRefusal>,
    already_blocked: bool,
}

fn check(req: CheckReq) -> Result<CheckAns, FfiError> {
    Ok(CheckAns {
        refusal: blocklist::can_block(&req.raw, &req.emergency).err(),
        already_blocked: BlockSet::from_rows(&req.blocked).is_blocked(&req.raw),
    })
}

fn check_cases() -> Vec<Case> {
    let case = |label: &'static str, raw: &str| {
        Case::named(
            label,
            json!({"raw": raw, "emergency": emergency(), "blocked": blocked_rows()}),
        )
    };
    vec![
        Case::new(
            json!({"raw": "0532 123 45 67", "emergency": emergency(), "blocked": blocked_rows()}),
        ),
        case("already_blocked", "+491512345678"),
        case("emergency", "112"),
        case("custom_emergency", "155"),
        case("no_number", "  "),
        case("not_a_number", "ABC"),
    ]
}

#[derive(Deserialize)]
struct ParseReq {
    typed: String,
    #[serde(default)]
    emergency: EmergencyList,
}

#[derive(Serialize)]
struct ParseAns {
    number: String,
}

fn parse_input(req: ParseReq) -> Result<ParseAns, FfiError> {
    blocklist::parse_input(&req.typed, &req.emergency)
        .map(|number| ParseAns { number })
        .map_err(|error| {
            let reason = match error {
                BlockInputError::Invalid => "invalid",
                BlockInputError::Emergency => "emergency",
            };
            FfiError::invalid(format!("cannot block {:?}: {reason}", req.typed))
                .with_detail(json!({"reason": reason}))
        })
}

fn parse_input_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"typed": " 0532-123 45 67 ", "emergency": emergency()})),
        Case::named("emergency", json!({"typed": "112"})),
        Case::named("invalid", json!({"typed": "hello"})),
    ]
}

#[derive(Deserialize)]
struct ManyReq {
    text: String,
    #[serde(default)]
    emergency: EmergencyList,
    #[serde(default)]
    blocked: Vec<BlockedNumber>,
}

#[derive(Serialize)]
struct ManyAns {
    parsed: BulkParse,
    /// The pill after blocking them all.
    text: String,
}

fn parse_many(req: ManyReq) -> Result<ManyAns, FfiError> {
    let parsed = blocklist::parse_many(
        &req.text,
        &req.emergency,
        &BlockSet::from_rows(&req.blocked),
    );
    Ok(ManyAns {
        text: blocklist::bulk_text(&parsed),
        parsed,
    })
}

const PASTED: &str = "Spam: 0532 111 22 33, +49 151 2345678, 112\nand 0532-111-22-33 again";

fn many_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"text": PASTED, "emergency": emergency(), "blocked": blocked_rows()}),
    )]
}

#[derive(Serialize)]
struct FileAns {
    import: FileImport,
    text: String,
}

fn parse_file(req: ManyReq) -> Result<FileAns, FfiError> {
    let import = blocklist::parse_file(
        &req.text,
        &req.emergency,
        &BlockSet::from_rows(&req.blocked),
    );
    Ok(FileAns {
        text: blocklist::import_text(&import),
        import,
    })
}

fn file_cases() -> Vec<Case> {
    vec![Case::new(json!({
        "text": "number,name\r\n0532 111 22 33,Spam\r\n+49 151 2345678,Known\r\nnot a number,x\r\n",
        "emergency": emergency(), "blocked": blocked_rows()
    }))]
}

#[derive(Deserialize)]
struct LinesReq {
    rows: Vec<BlockedNumber>,
    /// Contact names by number as the system stores it.
    #[serde(default)]
    names: HashMap<String, String>,
    tz_offset_s: i32,
    now_ms: i64,
}

#[derive(Serialize)]
struct LinesAns {
    lines: Vec<BlockedLine>,
    csv: String,
    csv_file_name: String,
    share_text: String,
}

fn lines(req: LinesReq) -> Result<LinesAns, FfiError> {
    let name_of = |n: &str| req.names.get(n.trim()).cloned();
    let lines = req
        .rows
        .iter()
        .map(|row| {
            let raw = if row.number_raw.trim().is_empty() {
                row.normalized.as_str()
            } else {
                row.number_raw.as_str()
            };
            blocklist::numbers_line(row, req.names.get(raw.trim()).map(String::as_str))
        })
        .collect();
    Ok(LinesAns {
        lines,
        csv: blocklist::to_csv(&req.rows, name_of),
        csv_file_name: blocklist::csv_file_name(req.now_ms, req.tz_offset_s),
        share_text: blocklist::list_text(&req.rows, name_of),
    })
}

fn lines_cases() -> Vec<Case> {
    vec![Case::new(json!({
        "rows": blocked_rows(), "names": {"+49 151 2345678": "Hans Müller"}, "tz_offset_s": 10800, "now_ms": NOW
    }))]
}

#[derive(Deserialize)]
struct CallLineReq {
    entry: CallLogEntry,
    #[serde(default)]
    name: Option<String>,
    time: String,
}

fn call_line(req: CallLineReq) -> Result<BlockedLine, FfiError> {
    Ok(blocklist::call_line(
        &req.entry,
        req.name.as_deref(),
        &req.time,
    ))
}

fn call_line_cases() -> Vec<Case> {
    let blocked = entries()[3].clone();
    vec![Case::new(
        json!({"entry": blocked, "name": null, "time": "Yesterday"}),
    )]
}
