//! Files and pictures: vCard, CSV and calendar files, the import plan, intake of shared files and text, the QR/paste
//! scan result and the photo crop.

use super::{NOW, ayse, cem, d, drafts, mehmet, wires};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use base64::{Engine, engine::general_purpose::STANDARD};
use rovsti_core::contact_csv;
use rovsti_core::contact_edit::ContactDraft;
use rovsti_core::contact_ics;
use rovsti_core::contact_intake::{self, Intake};
use rovsti_core::contact_photo::{self, CropView, PhotoPlan};
use rovsti_core::contact_scan::{self, Scanned};
use rovsti_core::import_plan;
use rovsti_core::vcard::{self, CardPhoto, PhotoFormat};
use rovsti_core::wire::WireContact;
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "contacts.csv_export",
        ExportReq,
        TextFileAns,
        csv_export,
        csv_export_cases
    ),
    op!(
        "contacts.csv_import",
        TextReq,
        ParsedAns,
        csv_import,
        csv_import_cases
    ),
    op!("contacts.ics", IcsReq, IcsAns, ics, ics_cases),
    op!(
        "contacts.vcard_parse",
        TextReq,
        ParsedAns,
        vcard_parse,
        vcard_parse_cases
    ),
    op!(
        "contacts.vcard_write",
        VcardWriteReq,
        TextFileAns,
        vcard_write,
        vcard_write_cases
    ),
    op!(
        "contacts.import_plan",
        ImportReq,
        ImportAns,
        import_plan,
        import_cases
    ),
    op!(
        "contacts.import_photos",
        PhotosReq,
        PhotosAns,
        import_photos,
        import_photos_cases
    ),
    op!("contacts.number_key", KeyReq, KeyAns, number_key, key_cases),
    op!(
        "contacts.intake",
        IntakeReq,
        IntakeAns,
        intake,
        intake_cases
    ),
    op!("contacts.scan", ScanReq, ScanDto, scan, scan_cases),
    op!(
        "contacts.photo_plan",
        PlanReq,
        PlanAns,
        photo_plan,
        plan_cases
    ),
    op!(
        "contacts.photo_crop",
        CropReq,
        CropAns,
        photo_crop,
        crop_cases
    ),
    op!(
        "contacts.photo_accepts",
        AcceptsReq,
        AcceptsAns,
        photo_accepts,
        accepts_cases
    ),
];

/// A local calendar day.
#[derive(Clone, Copy, Deserialize, Serialize)]
pub(super) struct Day {
    pub year: i32,
    pub month: u8,
    pub day: u8,
}

impl Day {
    fn tuple(self) -> (i32, u8, u8) {
        (self.year, self.month, self.day)
    }
}

const TODAY: Day = Day {
    year: 2026,
    month: 9,
    day: 21,
};

fn today() -> serde_json::Value {
    json!({"year": TODAY.year, "month": TODAY.month, "day": TODAY.day})
}

/// An embedded card photo.
#[derive(Clone, Deserialize, Serialize)]
pub(super) struct PhotoDto {
    /// `jpeg` or `png`.
    format: String,
    mime: String,
    /// Standard base64 of the image bytes.
    base64: String,
}

impl PhotoDto {
    fn of(photo: &CardPhoto) -> Self {
        Self {
            format: match photo.format {
                PhotoFormat::Jpeg => "jpeg",
                PhotoFormat::Png => "png",
            }
            .to_owned(),
            mime: photo.format.mime().to_owned(),
            base64: STANDARD.encode(&photo.bytes),
        }
    }

    fn to_card(&self) -> Result<CardPhoto, FfiError> {
        let bytes = STANDARD
            .decode(self.base64.as_bytes())
            .map_err(|e| FfiError::new(ErrorKind::Parse, format!("photo: {e}")))?;
        let format = PhotoFormat::sniff(&bytes)
            .ok_or_else(|| FfiError::invalid("the photo is neither JPEG nor PNG"))?;
        Ok(CardPhoto { format, bytes })
    }
}

fn cards_of(photos: &[Option<PhotoDto>]) -> Result<Vec<Option<CardPhoto>>, FfiError> {
    photos
        .iter()
        .map(|p| p.as_ref().map(PhotoDto::to_card).transpose())
        .collect()
}

/// A tiny but real PNG (1x1), enough for `sniff`.
const PNG_1X1: &[u8] = &[
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52, 0, 0, 0,
    1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1f, 0x15, 0xc4, 0x89,
];

fn png_photo() -> serde_json::Value {
    json!({"format": "png", "mime": "image/png", "base64": STANDARD.encode(PNG_1X1)})
}

#[derive(Deserialize)]
struct TextReq {
    text: String,
}

#[derive(Serialize)]
struct TextFileAns {
    text: String,
    file_name: String,
    count: usize,
}

#[derive(Serialize)]
struct ParsedAns {
    contacts: Vec<ContactDraft>,
    /// Cards or rows that were read but have no usable name.
    skipped: usize,
    /// The embedded photo of each contact, aligned with `contacts` (vCard only; empty for CSV).
    photos: Vec<Option<PhotoDto>>,
}

// ---- csv ----

#[derive(Deserialize)]
struct ExportReq {
    drafts: Vec<ContactDraft>,
    today: Day,
}

fn csv_export(req: ExportReq) -> Result<TextFileAns, FfiError> {
    Ok(TextFileAns {
        text: contact_csv::write(&req.drafts),
        file_name: contact_csv::file_name(req.drafts.len(), req.today.tuple()),
        count: req.drafts.len(),
    })
}

fn csv_export_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"drafts": [ayse(), mehmet()], "today": today()}),
    )]
}

fn csv_import(req: TextReq) -> Result<ParsedAns, FfiError> {
    let parsed = contact_csv::parse(&req.text);
    Ok(ParsedAns {
        contacts: parsed.contacts,
        skipped: parsed.skipped,
        photos: Vec::new(),
    })
}

fn csv_import_cases() -> Vec<Case> {
    let text = contact_csv::write(&[
        serde_json::from_value(ayse()).expect("draft"),
        serde_json::from_value(mehmet()).expect("draft"),
    ]);
    vec![
        Case::new(json!({"text": text})),
        Case::named("empty", json!({"text": ""})),
    ]
}

// ---- ics ----

#[derive(Deserialize)]
struct IcsReq {
    drafts: Vec<ContactDraft>,
    today: Day,
    stamp_ms: i64,
}

#[derive(Serialize)]
struct IcsAns {
    text: String,
    file_name: String,
    /// Events written (one per birthday, anniversary or dated event).
    count: usize,
    notice: String,
}

fn ics(req: IcsReq) -> Result<IcsAns, FfiError> {
    let events = contact_ics::events_of(&req.drafts);
    Ok(IcsAns {
        text: contact_ics::write(&events, req.stamp_ms),
        file_name: contact_ics::file_name(events.len(), req.today.tuple()),
        notice: contact_ics::saved_notice(events.len()),
        count: events.len(),
    })
}

fn ics_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"drafts": drafts(), "today": today(), "stamp_ms": NOW}),
    )]
}

// ---- vcard ----

fn vcard_parse(req: TextReq) -> Result<ParsedAns, FfiError> {
    let parsed = vcard::parse(&req.text);
    Ok(ParsedAns {
        photos: vcard::photos(&req.text)
            .iter()
            .map(|p| p.as_ref().map(PhotoDto::of))
            .collect(),
        contacts: parsed.contacts,
        skipped: parsed.skipped,
    })
}

fn vcard_parse_cases() -> Vec<Case> {
    let card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada Lovelace\r\nN:Lovelace;Ada;;;\r\nTEL;TYPE=CELL:+905551112233\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nEND:VCARD\r\n";
    vec![
        Case::new(json!({"text": card})),
        Case::named("none", json!({"text": "hello"})),
    ]
}

#[derive(Deserialize)]
struct VcardWriteReq {
    drafts: Vec<ContactDraft>,
    today: Day,
    /// Optional photos aligned with `drafts`.
    #[serde(default)]
    photos: Vec<Option<PhotoDto>>,
}

fn vcard_write(req: VcardWriteReq) -> Result<TextFileAns, FfiError> {
    let text = if req.photos.is_empty() {
        vcard::write(&req.drafts)
    } else {
        let photos = cards_of(&req.photos)?;
        req.drafts
            .iter()
            .enumerate()
            .map(|(i, draft)| vcard::write_one(draft, photos.get(i).and_then(Option::as_ref)))
            .collect()
    };
    Ok(TextFileAns {
        text,
        file_name: vcard::export_name(&req.drafts, req.today.tuple()),
        count: req.drafts.len(),
    })
}

fn vcard_write_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"drafts": [ayse(), mehmet()], "today": today()})),
        Case::named(
            "one_with_photo",
            json!({"drafts": [cem()], "today": today(), "photos": [png_photo()]}),
        ),
        Case::named(
            "bad_photo",
            json!({"drafts": [cem()], "today": today(),
            "photos": [{"format": "png", "mime": "image/png", "base64": STANDARD.encode(b"not an image")}]}),
        ),
    ]
}

// ---- import plan ----

#[derive(Deserialize)]
struct ImportReq {
    existing: Vec<WireContact>,
    incoming: Vec<ContactDraft>,
    #[serde(default)]
    photos: Vec<Option<PhotoDto>>,
}

#[derive(Serialize)]
struct ImportAns {
    /// The cards to add.
    add: Vec<ContactDraft>,
    /// Cards left out because the contact is already there or repeated.
    duplicates: usize,
    /// The photos of the kept cards, aligned with `add`.
    photos: Vec<Option<PhotoDto>>,
}

fn import_plan(req: ImportReq) -> Result<ImportAns, FfiError> {
    let photos = cards_of(&req.photos)?;
    let (plan, kept) = import_plan::plan_with_photos(&req.existing, req.incoming, photos);
    Ok(ImportAns {
        add: plan.add,
        duplicates: plan.duplicates,
        photos: kept.iter().map(|p| p.as_ref().map(PhotoDto::of)).collect(),
    })
}

fn import_cases() -> Vec<Case> {
    let incoming = json!([
        d(
            json!({"given": "Ayşe", "family": "Yılmaz", "numbers": [{"value": "+90 532 123 45 67"}]})
        ),
        d(
            json!({"given": "Ada", "family": "Lovelace", "numbers": [{"value": "+90 555 111 22 33"}]})
        ),
    ]);
    vec![
        Case::new(
            json!({"existing": wires(), "incoming": incoming, "photos": [null, png_photo()]}),
        ),
        Case::named("nothing", json!({"existing": [], "incoming": []})),
    ]
}

#[derive(Deserialize)]
struct PhotosReq {
    ids: Vec<i64>,
    photos: Vec<Option<PhotoDto>>,
}

#[derive(Serialize)]
struct PhotosAns {
    targets: Vec<PhotoTarget>,
    max: usize,
}

#[derive(Serialize)]
struct PhotoTarget {
    contact_id: i64,
    photo: PhotoDto,
}

fn import_photos(req: PhotosReq) -> Result<PhotosAns, FfiError> {
    let photos = cards_of(&req.photos)?;
    Ok(PhotosAns {
        targets: import_plan::photo_targets(&req.ids, &photos)
            .into_iter()
            .map(|(contact_id, photo)| PhotoTarget {
                contact_id,
                photo: PhotoDto::of(photo),
            })
            .collect(),
        max: import_plan::MAX_IMPORT_PHOTOS,
    })
}

fn import_photos_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"ids": [31, 0, 33], "photos": [png_photo(), png_photo(), null]}),
    )]
}

#[derive(Deserialize)]
struct KeyReq {
    raw: String,
}

#[derive(Serialize)]
struct KeyAns {
    /// The last ten digits of the number; `None` without digits.
    key: Option<String>,
}

fn number_key(req: KeyReq) -> Result<KeyAns, FfiError> {
    Ok(KeyAns {
        key: import_plan::number_key(&req.raw),
    })
}

fn key_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"raw": "+90 (532) 123 45 67"})),
        Case::named("no_digits", json!({"raw": "voicemail"})),
    ]
}

// ---- scan / intake ----

#[derive(Serialize)]
struct ScanDto {
    /// `cards`, `number`, `email` or `nothing`.
    kind: &'static str,
    cards: Vec<ContactDraft>,
    /// The number or the address.
    value: Option<String>,
    notice: String,
}

fn scan_dto(scanned: Scanned, notice: &str) -> ScanDto {
    let notice = notice.to_owned();
    match scanned {
        Scanned::Cards(cards) => ScanDto {
            kind: "cards",
            cards,
            value: None,
            notice,
        },
        Scanned::Number(value) => ScanDto {
            kind: "number",
            cards: Vec::new(),
            value: Some(value),
            notice,
        },
        Scanned::Email(value) => ScanDto {
            kind: "email",
            cards: Vec::new(),
            value: Some(value),
            notice,
        },
        Scanned::Nothing => ScanDto {
            kind: "nothing",
            cards: Vec::new(),
            value: None,
            notice,
        },
    }
}

#[derive(Deserialize)]
struct ScanReq {
    text: String,
    /// Text from the clipboard (names on a line are read too) instead of a QR payload.
    #[serde(default)]
    pasted: bool,
}

fn scan(req: ScanReq) -> Result<ScanDto, FfiError> {
    let (scanned, notice) = if req.pasted {
        let s = contact_scan::parse_pasted(&req.text);
        let n = contact_scan::paste_notice(&s);
        (s, n)
    } else {
        let s = contact_scan::parse_payload(&req.text);
        let n = contact_scan::scan_notice(&s);
        (s, n)
    };
    Ok(scan_dto(scanned, notice))
}

fn scan_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"text": "MECARD:N:Lovelace,Ada;TEL:+905551112233;EMAIL:ada@example.org;;"}),
        ),
        Case::named("tel", json!({"text": "tel:+905551112233"})),
        Case::named(
            "mailto",
            json!({"text": "mailto:ada@example.org?subject=Hi"}),
        ),
        Case::named("nothing", json!({"text": "https://example.org"})),
        Case::named(
            "pasted_line",
            json!({"text": "Ada Lovelace 0555 111 22 33", "pasted": true}),
        ),
    ]
}

#[derive(Deserialize)]
struct IntakeReq {
    /// `file` (a shared or opened file) or `text` (shared text).
    source: String,
    #[serde(default)]
    mime: Option<String>,
    #[serde(default)]
    name: String,
    /// `file`: the bytes, base64.
    #[serde(default)]
    bytes: String,
    /// `text`: the shared text.
    #[serde(default)]
    text: String,
}

#[derive(Serialize)]
struct IntakeAns {
    /// `cards`, `single`, `not_contacts`, `too_big` or `unreadable`.
    kind: &'static str,
    cards: Vec<ContactDraft>,
    single: Option<ScanDto>,
    /// The pill for a file that cannot be used.
    notice: Option<&'static str>,
    /// What the file looked like: `vcard`, `csv` or `text`.
    format: &'static str,
}

fn intake(req: IntakeReq) -> Result<IntakeAns, FfiError> {
    let (result, format) = match req.source.as_str() {
        "file" => {
            let bytes = STANDARD
                .decode(req.bytes.as_bytes())
                .map_err(|e| FfiError::new(ErrorKind::Parse, format!("bytes: {e}")))?;
            let head = &bytes[..bytes.len().min(64)];
            let format = if contact_intake::is_vcard(req.mime.as_deref(), &req.name, head) {
                "vcard"
            } else if contact_csv::looks_like_csv(req.mime.as_deref(), &req.name, head) {
                "csv"
            } else {
                "text"
            };
            (
                contact_intake::from_file(req.mime.as_deref(), &req.name, &bytes),
                format,
            )
        }
        "text" => (contact_intake::from_text(&req.text), "text"),
        other => {
            return Err(FfiError::invalid(format!(
                "unknown intake source {other:?}"
            )));
        }
    };
    let notice = contact_intake::notice(&result);
    let mut ans = IntakeAns {
        kind: "not_contacts",
        cards: Vec::new(),
        single: None,
        notice,
        format,
    };
    match result {
        Intake::Cards(cards) => {
            ans.kind = "cards";
            ans.cards = cards;
        }
        Intake::Single(s) => {
            ans.kind = "single";
            let notice = contact_scan::scan_notice(&s);
            ans.single = Some(scan_dto(s, notice));
        }
        Intake::NotContacts => {}
        Intake::TooBig => ans.kind = "too_big",
        Intake::Unreadable => ans.kind = "unreadable",
    }
    Ok(ans)
}

fn intake_cases() -> Vec<Case> {
    let vcf = "BEGIN:VCARD\nVERSION:3.0\nFN:Ada Lovelace\nN:Lovelace;Ada;;;\nTEL:+905551112233\nEND:VCARD\n";
    vec![
        Case::new(
            json!({"source": "file", "mime": "text/x-vcard", "name": "ada.vcf", "bytes": STANDARD.encode(vcf)}),
        ),
        Case::named(
            "csv",
            json!({"source": "file", "name": "people.csv",
                   "bytes": STANDARD.encode("First Name,Last Name,Phone 1 - Value\nAda,Lovelace,+905551112233\n")}),
        ),
        Case::named("text", json!({"source": "text", "text": "0555 111 22 33"})),
        Case::named(
            "unreadable",
            json!({"source": "file", "name": "x.bin", "bytes": STANDARD.encode([0xffu8, 0xfe, 0xfd])}),
        ),
        Case::named("bad_source", json!({"source": "carrier pigeon"})),
    ]
}

// ---- photo ----

#[derive(Clone, Copy, Deserialize, Serialize)]
struct CropDto {
    turns: u8,
    zoom: f64,
    cx: f64,
    cy: f64,
}

/// An `f32` as the shortest `f64` that prints the same (0.45 stays 0.45, not 0.44999998807907104).
fn short(x: f32) -> f64 {
    x.to_string().parse().unwrap_or(f64::from(x))
}

impl From<CropDto> for CropView {
    fn from(c: CropDto) -> Self {
        Self {
            turns: c.turns,
            zoom: c.zoom as f32,
            cx: c.cx as f32,
            cy: c.cy as f32,
        }
    }
}

impl From<CropView> for CropDto {
    fn from(c: CropView) -> Self {
        Self {
            turns: c.turns,
            zoom: short(c.zoom),
            cx: short(c.cx),
            cy: short(c.cy),
        }
    }
}

#[derive(Deserialize)]
struct PlanReq {
    width: u32,
    height: u32,
    /// The user's crop; absent plans the centred square.
    #[serde(default)]
    view: Option<CropDto>,
}

#[derive(Serialize)]
struct PlanAns {
    plan: Option<PhotoPlan>,
    error: Option<String>,
    /// The size after the rotation.
    rotated_width: u32,
    rotated_height: u32,
    max_zoom: f64,
    max_side: u32,
}

fn photo_plan(req: PlanReq) -> Result<PlanAns, FfiError> {
    let turns = req.view.map_or(0, |v| v.turns);
    let planned = match req.view {
        Some(view) => contact_photo::plan_for(req.width, req.height, view.into()),
        None => contact_photo::plan(req.width, req.height),
    };
    let (rotated_width, rotated_height) = contact_photo::rotated_size(req.width, req.height, turns);
    let (plan, error) = match planned {
        Ok(p) => (Some(p), None),
        Err(e) => (None, Some(e.to_string())),
    };
    Ok(PlanAns {
        plan,
        error,
        rotated_width,
        rotated_height,
        max_zoom: short(contact_photo::max_zoom(req.width, req.height, turns)),
        max_side: contact_photo::MAX_SIDE,
    })
}

fn plan_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"width": 4000, "height": 3000})),
        Case::named(
            "cropped",
            json!({"width": 4000, "height": 3000, "view": {"turns": 1, "zoom": 2.0, "cx": 0.4, "cy": 0.6}}),
        ),
        Case::named("too_small", json!({"width": 50, "height": 50})),
        Case::named("empty", json!({"width": 0, "height": 10})),
    ]
}

#[derive(Deserialize)]
struct CropReq {
    width: u32,
    height: u32,
    view: CropDto,
    /// `rotate`, `pan` or `pinch`.
    action: String,
    /// `pan`: the finger's movement in pixels and the side of the crop window in pixels.
    #[serde(default)]
    dx: f32,
    #[serde(default)]
    dy: f32,
    #[serde(default)]
    side_px: f32,
    /// `pinch`: the scale factor of the gesture so far.
    #[serde(default = "one")]
    factor: f32,
}

fn one() -> f32 {
    1.0
}

#[derive(Serialize)]
struct CropAns {
    view: CropDto,
}

fn photo_crop(req: CropReq) -> Result<CropAns, FfiError> {
    let view: CropView = req.view.into();
    let next = match req.action.as_str() {
        "rotate" => view.rotate_right(),
        "pan" => view.pan(req.dx, req.dy, req.side_px, req.width, req.height),
        "pinch" => view.pinch(req.factor, req.width, req.height),
        other => return Err(FfiError::invalid(format!("unknown crop action {other:?}"))),
    };
    Ok(CropAns { view: next.into() })
}

fn crop_cases() -> Vec<Case> {
    let view = json!({"turns": 0, "zoom": 2.0, "cx": 0.5, "cy": 0.5});
    vec![
        Case::new(
            json!({"width": 4000, "height": 3000, "view": view, "action": "pan", "dx": 40.0, "dy": -20.0, "side_px": 300.0}),
        ),
        Case::named(
            "rotate",
            json!({"width": 4000, "height": 3000, "view": view, "action": "rotate"}),
        ),
        Case::named(
            "pinch",
            json!({"width": 4000, "height": 3000, "view": view, "action": "pinch", "factor": 1.5}),
        ),
        Case::named(
            "bad_action",
            json!({"width": 10, "height": 10, "view": view, "action": "spin"}),
        ),
    ]
}

#[derive(Deserialize)]
struct AcceptsReq {
    #[serde(default)]
    mime: Option<String>,
    now_ms: i64,
}

#[derive(Serialize)]
struct AcceptsAns {
    accepts: bool,
    camera_file_name: String,
    max_pick_bytes: u64,
    min_side: u32,
}

fn photo_accepts(req: AcceptsReq) -> Result<AcceptsAns, FfiError> {
    Ok(AcceptsAns {
        accepts: contact_photo::accepts(req.mime.as_deref()),
        camera_file_name: contact_photo::camera_file_name(req.now_ms),
        max_pick_bytes: contact_photo::MAX_PICK_BYTES,
        min_side: contact_photo::MIN_SIDE,
    })
}

fn accepts_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"mime": "image/webp", "now_ms": NOW})),
        Case::named(
            "not_an_image",
            json!({"mime": "application/pdf", "now_ms": NOW}),
        ),
        Case::named("unknown", json!({"now_ms": NOW})),
    ]
}
