//! Shared number ops: `phone_number.*`, `format.*`, `calling_code.*`, `copy.*`.
//!
//! Thin wrappers over `rovsti_core::{phone_number, number_format, calling_code, copy_choices}`. The home
//! country code is always a request field, never read from the device (ops are deterministic).

use super::registry::{Case, OpSpec};
use crate::envelope::FfiError;
use crate::op;
use rovsti_core::calling_code::{self, Country};
use rovsti_core::copy_choices::{self, CopyChoice};
use rovsti_core::number_format::{self, ContactQr, NumberForm};
use rovsti_core::phone_number::{PhoneNumber, PhoneNumberError};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("phone_number.parse", ParseReq, ParseAns, parse, parse_cases),
    op!("phone_number.same", SameReq, SameAns, same, same_cases),
    op!("format.render", RenderReq, RenderAns, render, render_cases),
    op!(
        "format.available",
        AvailableReq,
        FormsAns,
        available,
        available_cases
    ),
    op!(
        "format.copy_forms",
        AvailableReq,
        FormsAns,
        copy_forms,
        copy_forms_cases
    ),
    op!(
        "format.copied_notice",
        NoticeReq,
        NoticeAns,
        copied_notice,
        notice_cases
    ),
    op!(
        "format.infer_home_cc",
        InferReq,
        InferAns,
        infer_home_cc,
        infer_cases
    ),
    op!("format.contact_qr", QrReq, ContactQr, contact_qr, qr_cases),
    op!("format.file_name", FileReq, FileAns, file_name, file_cases),
    op!(
        "calling_code.country_of",
        RawReq,
        CountryAns,
        country_of,
        country_of_cases
    ),
    op!("calling_code.of_cc", CcReq, CountryAns, of_cc, of_cc_cases),
    op!("calling_code.line", LineReq, LineAns, line, line_cases),
    op!("calling_code.list", Value, ListAns, list, list_cases),
    op!(
        "copy.header_choices",
        ChoicesReq,
        ChoicesAns,
        header_choices,
        choices_cases
    ),
];

fn number(input: &str) -> Result<PhoneNumber, FfiError> {
    PhoneNumber::parse(input).map_err(|error| {
        let reason = match error {
            PhoneNumberError::Empty => "empty",
            PhoneNumberError::InvalidCharacter(_) => "invalid_character",
            PhoneNumberError::TooLong(_) => "too_long",
        };
        FfiError::invalid(error.to_string()).with_detail(json!({ "reason": reason }))
    })
}

// ---- phone_number.* ----

#[derive(Deserialize)]
struct ParseReq {
    input: String,
}

#[derive(Serialize)]
struct ParseAns {
    canonical: String,
    international: bool,
    whatsapp_user: Option<String>,
}

fn parse(req: ParseReq) -> Result<ParseAns, FfiError> {
    let n = number(&req.input)?;
    Ok(ParseAns {
        canonical: n.as_str().to_owned(),
        international: n.is_international(),
        whatsapp_user: n.whatsapp_user().map(str::to_owned),
    })
}

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"input": "0532 123 45 67"})),
        Case::named("international", json!({"input": "00 49 (151) 2345-678"})),
        Case::named("empty", json!({"input": "  "})),
        Case::named("letters", json!({"input": "555-CALL"})),
        Case::named("too_long", json!({"input": "+1234567890123456"})),
    ]
}

#[derive(Deserialize)]
struct SameReq {
    a: String,
    b: String,
}

#[derive(Serialize)]
struct SameAns {
    same: bool,
}

fn same(req: SameReq) -> Result<SameAns, FfiError> {
    Ok(SameAns {
        same: number(&req.a)?.matches(&number(&req.b)?),
    })
}

fn same_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"a": "+905321234567", "b": "05321234567"})),
        Case::named(
            "different",
            json!({"a": "+905321234567", "b": "+905329999999"}),
        ),
        Case::named("invalid", json!({"a": "abc", "b": "05321234567"})),
    ]
}

// ---- format.* ----

#[derive(Deserialize)]
struct RenderReq {
    raw: String,
    form: NumberForm,
    #[serde(default)]
    home_cc: Option<String>,
    #[serde(default)]
    name: Option<String>,
}

#[derive(Serialize)]
struct RenderAns {
    text: Option<String>,
}

fn render(req: RenderReq) -> Result<RenderAns, FfiError> {
    Ok(RenderAns {
        text: number_format::render(
            &req.raw,
            req.form,
            req.home_cc.as_deref(),
            req.name.as_deref(),
        ),
    })
}

fn render_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"raw": "0532 123 45 67", "form": "e164", "home_cc": "90"})),
        Case::named("no_form", json!({"raw": "112", "form": "tel_link"})),
        Case::named("empty", json!({"raw": " ", "form": "display"})),
        Case::named("bad_form", json!({"raw": "112", "form": "fax"})),
    ]
}

#[derive(Deserialize)]
struct AvailableReq {
    raw: String,
    #[serde(default)]
    home_cc: Option<String>,
}

#[derive(Serialize)]
struct FormRow {
    form: NumberForm,
    label: &'static str,
    text: String,
}

#[derive(Serialize)]
struct FormsAns {
    forms: Vec<FormRow>,
}

fn rows(forms: Vec<(NumberForm, String)>) -> FormsAns {
    FormsAns {
        forms: forms
            .into_iter()
            .map(|(form, text)| FormRow {
                form,
                label: form.label(),
                text,
            })
            .collect(),
    }
}

fn available(req: AvailableReq) -> Result<FormsAns, FfiError> {
    Ok(rows(number_format::available(
        &req.raw,
        req.home_cc.as_deref(),
    )))
}

fn available_cases() -> Vec<Case> {
    vec![Case::new(json!({"raw": "0532 123 45 67", "home_cc": "90"}))]
}

fn copy_forms(req: AvailableReq) -> Result<FormsAns, FfiError> {
    Ok(rows(number_format::copy_forms(
        &req.raw,
        req.home_cc.as_deref(),
    )))
}

fn copy_forms_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"raw": "+49 151 2345678", "home_cc": "90"})),
        Case::named("short_code", json!({"raw": "112"})),
    ]
}

#[derive(Deserialize)]
struct NoticeReq {
    form: NumberForm,
}

#[derive(Serialize)]
struct NoticeAns {
    notice: &'static str,
}

fn copied_notice(req: NoticeReq) -> Result<NoticeAns, FfiError> {
    Ok(NoticeAns {
        notice: number_format::copied_notice(req.form),
    })
}

fn notice_cases() -> Vec<Case> {
    vec![Case::new(json!({"form": "national"}))]
}

#[derive(Deserialize)]
struct InferReq {
    numbers: Vec<String>,
}

#[derive(Serialize)]
struct InferAns {
    home_cc: Option<String>,
}

fn infer_home_cc(req: InferReq) -> Result<InferAns, FfiError> {
    Ok(InferAns {
        home_cc: number_format::infer_home_cc(req.numbers.iter().map(String::as_str)),
    })
}

fn infer_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"numbers": ["0532 123 45 67", "+90 532 123 45 67", "+49 30 1234567"]})),
        Case::named("no_evidence", json!({"numbers": ["112"]})),
    ]
}

#[derive(Deserialize)]
struct QrNumber {
    raw: String,
    #[serde(default)]
    label: Option<String>,
}

#[derive(Deserialize)]
struct QrReq {
    name: String,
    numbers: Vec<QrNumber>,
    #[serde(default)]
    home_cc: Option<String>,
}

fn contact_qr(req: QrReq) -> Result<ContactQr, FfiError> {
    let numbers: Vec<(&str, Option<&str>)> = req
        .numbers
        .iter()
        .map(|n| (n.raw.as_str(), n.label.as_deref()))
        .collect();
    number_format::contact_qr(&req.name, &numbers, req.home_cc.as_deref())
        .map_err(|e| FfiError::invalid(e.to_string()).with_detail(json!({"reason": "too_big"})))
}

fn qr_cases() -> Vec<Case> {
    vec![
        Case::new(json!({
            "name": "Ayşe Yılmaz",
            "numbers": [
                {"raw": "0532 123 45 67", "label": "mobile"},
                {"raw": "+90 532 123 45 67"},
                {"raw": "0212 555 00 11", "label": "work"},
            ],
            "home_cc": "90",
        })),
        Case::named(
            "too_big",
            json!({"name": "x".repeat(1300), "numbers": [{"raw": "112"}]}),
        ),
    ]
}

#[derive(Deserialize)]
struct FileReq {
    name: String,
}

#[derive(Serialize)]
struct FileAns {
    slug: String,
    qr_file_name: String,
}

fn file_name(req: FileReq) -> Result<FileAns, FfiError> {
    Ok(FileAns {
        slug: number_format::file_slug(&req.name),
        qr_file_name: number_format::qr_file_name(&req.name),
    })
}

fn file_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"name": "Ayşe Yılmaz / İş"})),
        Case::named("nothing_usable", json!({"name": " // "})),
    ]
}

// ---- calling_code.* ----

#[derive(Deserialize)]
struct RawReq {
    raw: String,
}

#[derive(Deserialize)]
struct CcReq {
    cc: String,
}

#[derive(Serialize)]
struct CountryAns {
    country: Option<Country>,
}

fn country_of(req: RawReq) -> Result<CountryAns, FfiError> {
    Ok(CountryAns {
        country: calling_code::country_of(&req.raw),
    })
}

fn country_of_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"raw": "+49 30 1234567"})),
        Case::named("national", json!({"raw": "0532 123 45 67"})),
    ]
}

fn of_cc(req: CcReq) -> Result<CountryAns, FfiError> {
    Ok(CountryAns {
        country: calling_code::country_of_cc(&req.cc),
    })
}

fn of_cc_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"cc": "971"})),
        Case::named("unknown", json!({"cc": "999"})),
    ]
}

#[derive(Deserialize)]
struct LineReq {
    raw: String,
    #[serde(default)]
    home_cc: Option<String>,
}

#[derive(Serialize)]
struct LineAns {
    line: Option<String>,
}

fn line(req: LineReq) -> Result<LineAns, FfiError> {
    Ok(LineAns {
        line: calling_code::country_line(&req.raw, req.home_cc.as_deref()),
    })
}

fn line_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"raw": "+49 30 1234567", "home_cc": "90"})),
        Case::named(
            "home",
            json!({"raw": "+90 532 123 45 67", "home_cc": "+90"}),
        ),
    ]
}

#[derive(Serialize)]
struct ListAns {
    countries: Vec<Country>,
}

fn list(_: Value) -> Result<ListAns, FfiError> {
    Ok(ListAns {
        countries: calling_code::COUNTRIES
            .iter()
            .filter_map(|(cc, _)| calling_code::country_of_cc(cc))
            .collect(),
    })
}

fn list_cases() -> Vec<Case> {
    vec![Case::new(Value::Null)]
}

// ---- copy.* ----

#[derive(Deserialize)]
struct ChoicesReq {
    name: String,
    #[serde(default)]
    number: Option<String>,
    #[serde(default)]
    wa_id: Option<String>,
}

#[derive(Serialize)]
struct ChoicesAns {
    choices: Vec<CopyChoice>,
}

fn header_choices(req: ChoicesReq) -> Result<ChoicesAns, FfiError> {
    Ok(ChoicesAns {
        choices: copy_choices::header_choices(
            &req.name,
            req.number.as_deref(),
            req.wa_id.as_deref(),
        ),
    })
}

fn choices_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"name": "Ayşe", "number": "+90 532 123 45 67"})),
        Case::named("group", json!({"name": "Family", "wa_id": "120363@g.us"})),
        Case::named(
            "name_is_number",
            json!({"name": "0532 123 45 67", "number": "+905321234567", "wa_id": "905321234567@s.whatsapp.net"}),
        ),
    ]
}
