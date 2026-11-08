//! The forms a phone number can be copied or shared in (as shown, E.164,
//! national, `tel:` link, vCard), and the home country code inferred from the
//! owner's own call log.

use std::collections::{BTreeMap, HashSet};

use crate::call::format_number;
use crate::contact_edit::{ContactDraft, DraftField, FieldKind};
use crate::phone_number::PhoneNumber;
use crate::vcard;

/// How many distinct numbers [`infer_home_cc`] looks at.
const MAX_INFER: usize = 500;
/// Numbers with fewer digits than this and no `+` are short codes.
const MIN_DIALABLE: usize = 7;
/// The longest country code.
const MAX_CC: usize = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NumberForm {
    Display,
    E164,
    National,
    TelLink,
    #[serde(rename = "vcard")]
    VCard,
}

impl NumberForm {
    pub const ALL: [Self; 5] = [
        Self::Display,
        Self::E164,
        Self::National,
        Self::TelLink,
        Self::VCard,
    ];

    pub fn label(self) -> &'static str {
        match self {
            Self::Display => "As shown",
            Self::E164 => "International (E.164)",
            Self::National => "National",
            Self::TelLink => "Phone link",
            Self::VCard => "Contact card (vCard)",
        }
    }
}

/// The national significant digits of a national number (one trunk zero dropped).
fn nsn_of(number: &PhoneNumber) -> &str {
    let digits = number.as_str();
    digits.strip_prefix('0').unwrap_or(digits)
}

fn is_full_number(number: &PhoneNumber) -> bool {
    number.is_international() || number.as_str().len() >= MIN_DIALABLE
}

fn clean_cc(home_cc: Option<&str>) -> Option<&str> {
    let cc = home_cc?.trim().trim_start_matches('+');
    let valid = (1..=MAX_CC).contains(&cc.len())
        && cc.bytes().all(|b| b.is_ascii_digit())
        && !cc.starts_with('0');
    valid.then_some(cc)
}

/// The owner's country code, from pairs of a national and an international
/// number that reach the same line. The most frequent code wins; ties go to
/// the shorter code. `None` without evidence.
pub fn infer_home_cc<'a>(numbers: impl IntoIterator<Item = &'a str>) -> Option<String> {
    let mut seen = HashSet::new();
    let mut national = Vec::new();
    let mut international = Vec::new();
    for raw in numbers {
        let Ok(number) = PhoneNumber::parse(raw) else {
            continue;
        };
        if !is_full_number(&number) || !seen.insert(number.clone()) {
            continue;
        }
        if number.is_international() {
            international.push(number);
        } else {
            national.push(number);
        }
        if seen.len() >= MAX_INFER {
            break;
        }
    }
    let mut votes: BTreeMap<String, usize> = BTreeMap::new();
    for n in &national {
        let nsn = nsn_of(n);
        for i in &international {
            if !n.matches(i) {
                continue;
            }
            let digits = i.as_str().trim_start_matches('+');
            if let Some(cc) = digits.strip_suffix(nsn)
                && clean_cc(Some(cc)).is_some()
            {
                *votes.entry(cc.to_owned()).or_default() += 1;
            }
        }
    }
    votes
        .into_iter()
        .max_by(|(a, na), (b, nb)| na.cmp(nb).then(b.len().cmp(&a.len())).then(b.cmp(a)))
        .map(|(cc, _)| cc)
}

fn e164(number: &PhoneNumber, home_cc: Option<&str>) -> Option<String> {
    if !is_full_number(number) {
        return None;
    }
    if number.is_international() {
        return Some(number.as_str().to_owned());
    }
    let cc = clean_cc(home_cc)?;
    Some(format!("+{cc}{}", nsn_of(number)))
}

fn national(number: &PhoneNumber, home_cc: Option<&str>) -> Option<String> {
    if !is_full_number(number) {
        return None;
    }
    if !number.is_international() {
        let digits = number.as_str();
        if digits.starts_with('0') || clean_cc(home_cc) != Some("90") || digits.len() != 10 {
            return Some(format_number(number));
        }
        return PhoneNumber::parse(&format!("0{digits}"))
            .ok()
            .map(|n| format_number(&n));
    }
    let digits = number.as_str().trim_start_matches('+');
    if let Some(nsn) = digits.strip_prefix("90")
        && nsn.len() == 10
    {
        return PhoneNumber::parse(&format!("0{nsn}"))
            .ok()
            .map(|n| format_number(&n));
    }
    let cc = clean_cc(home_cc)?;
    let nsn = digits.strip_prefix(cc)?;
    Some(if cc == "1" {
        nsn.to_owned()
    } else {
        format!("0{nsn}")
    })
}

fn vcard_of(
    raw: &str,
    number: Option<&PhoneNumber>,
    home_cc: Option<&str>,
    name: Option<&str>,
) -> String {
    let tel = number
        .and_then(|n| e164(n, home_cc).or_else(|| Some(n.as_str().to_owned())))
        .unwrap_or_else(|| raw.to_owned());
    let name = name.map(str::trim).filter(|n| !n.is_empty());
    let draft = ContactDraft {
        given: name.unwrap_or(&tel).to_owned(),
        numbers: vec![DraftField::new(tel.clone(), FieldKind::Mobile)],
        ..ContactDraft::default()
    };
    vcard::write(&[draft])
}

/// The text of `raw` in `form`, or `None` when the number has no such form.
pub fn render(
    raw: &str,
    form: NumberForm,
    home_cc: Option<&str>,
    name: Option<&str>,
) -> Option<String> {
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }
    let number = PhoneNumber::parse(raw).ok();
    match form {
        NumberForm::Display => Some(
            number
                .as_ref()
                .map_or_else(|| raw.to_owned(), format_number),
        ),
        NumberForm::E164 => e164(number.as_ref()?, home_cc),
        NumberForm::National => national(number.as_ref()?, home_cc),
        NumberForm::TelLink => {
            let n = number.as_ref()?;
            if !is_full_number(n) {
                return None;
            }
            let body = e164(n, home_cc).unwrap_or_else(|| n.as_str().to_owned());
            Some(format!("tel:{body}"))
        }
        NumberForm::VCard => Some(vcard_of(raw, number.as_ref(), home_cc, name)),
    }
}

/// Every form that exists for `raw`, without two forms showing the same text.
pub fn available(raw: &str, home_cc: Option<&str>) -> Vec<(NumberForm, String)> {
    let mut out: Vec<(NumberForm, String)> = Vec::new();
    for form in NumberForm::ALL {
        if let Some(text) = render(raw, form, home_cc, None)
            && !out.iter().any(|(_, t)| *t == text)
        {
            out.push((form, text));
        }
    }
    out
}

/// The forms offered by "Copy as...": [`available`] without the vCard form.
pub fn copy_forms(raw: &str, home_cc: Option<&str>) -> Vec<(NumberForm, String)> {
    let mut forms = available(raw, home_cc);
    forms.retain(|(form, _)| *form != NumberForm::VCard);
    forms
}

/// The pill after copying a number in `form`.
pub fn copied_notice(form: NumberForm) -> &'static str {
    match form {
        NumberForm::Display => "Number copied",
        NumberForm::E164 => "International number copied",
        NumberForm::National => "National number copied",
        NumberForm::TelLink => "Phone link copied",
        NumberForm::VCard => "Contact card copied",
    }
}

/// The most bytes a contact QR payload may hold; bigger cards scan badly.
pub const QR_MAX_BYTES: usize = 1200;

/// A vCard ready to be drawn as a QR code.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct ContactQr {
    pub payload: String,
    /// Numbers in the card.
    pub kept: usize,
    /// Numbers left out to make the card fit (duplicates are not counted).
    pub dropped: usize,
}

/// Even a card with a single number is over [`QR_MAX_BYTES`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
#[error("This contact is too big for a QR code.")]
pub struct QrTooBig;

fn label_kind(label: Option<&str>) -> FieldKind {
    match label.map(|l| l.trim().to_lowercase()).as_deref() {
        Some("mobile" | "cell") => FieldKind::Mobile,
        Some("home") => FieldKind::Home,
        Some("work") => FieldKind::Work,
        _ => FieldKind::Other,
    }
}

/// One vCard for `name` with `numbers` (raw text and optional label), as E.164
/// when known. Duplicates are dropped; numbers go from the end until it fits.
pub fn contact_qr(
    name: &str,
    numbers: &[(&str, Option<&str>)],
    home_cc: Option<&str>,
) -> Result<ContactQr, QrTooBig> {
    let mut fields: Vec<DraftField> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    for (raw, label) in numbers {
        let raw = raw.trim();
        if raw.is_empty() {
            continue;
        }
        let value = render(raw, NumberForm::E164, home_cc, None).unwrap_or_else(|| raw.to_owned());
        if seen.insert(value.clone()) {
            fields.push(DraftField::new(value, label_kind(*label)));
        }
    }
    let total = fields.len();
    let build = |fields: &[DraftField]| {
        vcard::write(&[ContactDraft {
            given: name.trim().to_owned(),
            numbers: fields.to_vec(),
            ..ContactDraft::default()
        }])
    };
    loop {
        let payload = build(&fields);
        if payload.len() <= QR_MAX_BYTES {
            let kept = fields.len();
            return Ok(ContactQr {
                payload,
                kept,
                dropped: total - kept,
            });
        }
        if fields.len() <= 1 {
            return Err(QrTooBig);
        }
        fields.pop();
    }
}

/// A file-system safe slug of `name`, or `None` when nothing is left.
pub fn file_slug_opt(name: &str) -> Option<String> {
    let mapped: String = name
        .trim()
        .chars()
        .map(|c| match c {
            'ı' => 'i',
            'İ' => 'I',
            c if c.is_alphanumeric() => c,
            _ => '-',
        })
        .collect();
    let slug = mapped
        .split('-')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("-");
    (!slug.is_empty()).then_some(slug)
}

/// Like [`file_slug_opt`], with `contact` when the name has nothing usable.
pub fn file_slug(name: &str) -> String {
    file_slug_opt(name).unwrap_or_else(|| "contact".to_owned())
}

/// The gallery name of a contact's QR image.
pub fn qr_file_name(name: &str) -> String {
    format!("rovsti-{}-qr.png", file_slug(name))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn r(raw: &str, form: NumberForm, cc: Option<&str>) -> Option<String> {
        render(raw, form, cc, None)
    }

    #[test]
    fn infers_from_pair() {
        assert_eq!(
            infer_home_cc(["05551112233", "+905551112233"]).as_deref(),
            Some("90")
        );
    }

    #[test]
    fn inference_conflicts_and_none() {
        let got = infer_home_cc([
            "05551112233",
            "+905551112233",
            "05552223344",
            "+905552223344",
            "05553334455",
            "+495553334455",
        ]);
        assert_eq!(got.as_deref(), Some("90"));
        assert_eq!(infer_home_cc(["05551112233", "112"]), None);
        assert_eq!(infer_home_cc(std::iter::empty()), None);
        // Tie goes to the shorter code.
        assert_eq!(
            infer_home_cc(["05551112233", "+905551112233", "5553334455", "+15553334455"])
                .as_deref(),
            Some("1")
        );
    }

    #[test]
    fn turkish_national() {
        let raw = "0555 111 22 33";
        let cc = Some("90");
        assert_eq!(
            r(raw, NumberForm::Display, cc).expect("form"),
            "0555 111 22 33"
        );
        assert_eq!(r(raw, NumberForm::E164, cc).expect("form"), "+905551112233");
        assert_eq!(r(raw, NumberForm::E164, None), None);
        assert_eq!(
            r(raw, NumberForm::National, None).expect("form"),
            "0555 111 22 33"
        );
        assert_eq!(
            r(raw, NumberForm::TelLink, cc).expect("form"),
            "tel:+905551112233"
        );
        assert_eq!(
            r(raw, NumberForm::TelLink, None).expect("form"),
            "tel:05551112233"
        );
    }

    #[test]
    fn turkish_international() {
        let raw = "+90 555 111 22 33";
        assert_eq!(
            r(raw, NumberForm::Display, None).expect("form"),
            "+90 555 111 22 33"
        );
        assert_eq!(
            r(raw, NumberForm::E164, None).expect("form"),
            "+905551112233"
        );
        assert_eq!(
            r(raw, NumberForm::National, None).expect("form"),
            "0555 111 22 33"
        );
        assert_eq!(
            r(raw, NumberForm::TelLink, None).expect("form"),
            "tel:+905551112233"
        );
    }

    #[test]
    fn us_number() {
        let raw = "+1 202 555 0123";
        assert_eq!(
            r(raw, NumberForm::E164, None).expect("form"),
            "+12025550123"
        );
        assert_eq!(r(raw, NumberForm::National, None), None);
        assert_eq!(
            r(raw, NumberForm::National, Some("1")).expect("form"),
            "2025550123"
        );
        assert_eq!(r(raw, NumberForm::National, Some("90")), None);
    }

    #[test]
    fn short_and_ussd_codes() {
        for raw in ["112", "*#06#"] {
            assert!(r(raw, NumberForm::Display, Some("90")).is_some());
            assert!(r(raw, NumberForm::VCard, Some("90")).is_some());
            for form in [NumberForm::E164, NumberForm::National, NumberForm::TelLink] {
                assert_eq!(r(raw, form, Some("90")), None, "{raw} {form:?}");
            }
            assert_eq!(available(raw, Some("90")).len(), 2);
        }
        assert_eq!(
            r("*#06#", NumberForm::Display, None).expect("form"),
            "*#06#"
        );
    }

    #[test]
    fn name_string() {
        assert_eq!(r(" Mom ", NumberForm::Display, None).expect("form"), "Mom");
        assert_eq!(r("Mom", NumberForm::E164, Some("90")), None);
        assert!(r("Mom", NumberForm::VCard, None).is_some());
        assert_eq!(r("  ", NumberForm::Display, None), None);
    }

    #[test]
    fn vcard_round_trips() {
        let text = render(
            "0555 111 22 33",
            NumberForm::VCard,
            Some("90"),
            Some("Ayşe Yılmaz"),
        )
        .expect("form");
        let parsed = vcard::parse(&text);
        assert_eq!(parsed.contacts.len(), 1);
        let card = &parsed.contacts[0];
        assert_eq!(card.display_name(), "Ayşe Yılmaz");
        assert_eq!(card.numbers.len(), 1);
        assert_eq!(card.numbers[0].value, "+905551112233");

        let anon = r("+12025550123", NumberForm::VCard, None).expect("form");
        let parsed = vcard::parse(&anon);
        assert_eq!(parsed.contacts.len(), 1);
        assert_eq!(parsed.contacts[0].display_name(), "+12025550123");
    }

    #[test]
    fn available_dedups() {
        let forms = available("+905551112233", None);
        let kinds: Vec<_> = forms.iter().map(|(f, _)| *f).collect();
        assert_eq!(
            kinds,
            [
                NumberForm::Display,
                NumberForm::E164,
                NumberForm::National,
                NumberForm::TelLink,
                NumberForm::VCard
            ]
        );
        let canonical = available("+12025550123", None);
        assert!(canonical.iter().all(|(f, _)| *f != NumberForm::National));
        let texts: HashSet<_> = canonical.iter().map(|(_, t)| t.clone()).collect();
        assert_eq!(texts.len(), canonical.len());
        // A national number without a country code: Display == National.
        let national = available("05551112233", None);
        assert!(!national.iter().any(|(f, _)| *f == NumberForm::National));
    }

    #[test]
    fn labels() {
        assert_eq!(NumberForm::E164.label(), "International (E.164)");
        assert_eq!(NumberForm::ALL.len(), 5);
    }

    #[test]
    fn qr_round_trips_and_dedups() {
        let qr = contact_qr(
            "Ada Lovelace",
            &[
                ("0555 111 22 33", Some("Mobile")),
                ("+905551112233", None),
                ("0212 111 22 33", Some("work")),
            ],
            Some("90"),
        )
        .expect("fits");
        assert_eq!((qr.kept, qr.dropped), (2, 0));
        let parsed = vcard::parse(&qr.payload);
        let c = &parsed.contacts[0];
        assert_eq!(c.display_name(), "Ada Lovelace");
        let values: Vec<_> = c.numbers.iter().map(|n| n.value.as_str()).collect();
        assert_eq!(values, ["+905551112233", "+902121112233"]);
    }

    #[test]
    fn qr_escapes_name() {
        let name = "Çelik, Ömer; Jr";
        let qr = contact_qr(name, &[("+905551112233", None)], None).expect("fits");
        assert!(qr.payload.contains("\\,") && qr.payload.contains("\\;"));
        assert_eq!(vcard::parse(&qr.payload).contacts[0].display_name(), name);
    }

    #[test]
    fn qr_trims_from_the_end() {
        let name = "a".repeat(420);
        let numbers: Vec<String> = (0..12).map(|i| format!("+9055511{i:05}")).collect();
        let refs: Vec<(&str, Option<&str>)> = numbers.iter().map(|n| (n.as_str(), None)).collect();
        let qr = contact_qr(&name, &refs, None).expect("fits");
        assert!(qr.payload.len() <= QR_MAX_BYTES);
        assert_eq!(qr.kept + qr.dropped, 12);
        assert!(qr.dropped > 0 && qr.kept > 0);
        let parsed = vcard::parse(&qr.payload);
        assert_eq!(parsed.contacts[0].numbers.len(), qr.kept);
        assert_eq!(parsed.contacts[0].numbers[0].value, numbers[0]);
        // One more number would not have fit.
        let more = contact_qr(&name, &refs[..qr.kept + 1], None).expect("fits");
        assert_eq!(more.kept, qr.kept);
    }

    #[test]
    fn qr_too_big() {
        let name = "x".repeat(QR_MAX_BYTES);
        assert_eq!(
            contact_qr(&name, &[("+905551112233", None)], None),
            Err(QrTooBig)
        );
    }

    #[test]
    fn slugs() {
        assert_eq!(file_slug("Çağrı Işık"), "Çağri-Işik");
        assert_eq!(file_slug("Ömer ı İ"), "Ömer-i-I");
        assert_eq!(file_slug("😀 Ada 🎉"), "Ada");
        assert_eq!(file_slug(""), "contact");
        assert_eq!(file_slug_opt("***"), None);
        assert_eq!(qr_file_name("Ada Lovelace"), "rovsti-Ada-Lovelace-qr.png");
        assert_eq!(qr_file_name("😀"), "rovsti-contact-qr.png");
    }

    #[test]
    fn copy_forms_drop_vcard_and_duplicates() {
        let forms = copy_forms("+90 532 111 22 33", Some("90"));
        assert!(forms.iter().all(|(f, _)| *f != NumberForm::VCard));
        assert!(forms.iter().any(|(f, _)| *f == NumberForm::E164));
        let texts: HashSet<&String> = forms.iter().map(|(_, t)| t).collect();
        assert_eq!(texts.len(), forms.len());
        assert_eq!(
            copied_notice(NumberForm::E164),
            "International number copied"
        );
        assert_eq!(copied_notice(NumberForm::Display), "Number copied");
    }
}
