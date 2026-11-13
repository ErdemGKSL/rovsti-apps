//! `contacts.wa_*`: the WhatsApp decisions of the contact list and card (AD-CT6), stateless over
//! `rovsti_app_core::contacts::whatsapp`. The app keeps the chats, the lookups and the presence; each request
//! carries what the decision needs and the answer is what to draw or ask next.

use super::{NOW, TZ, wires};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::contacts::whatsapp::{
    LookupState, WaPanel, e164_numbers, marked_contacts, number_jid, profile_lines, status_line,
    wa_chip_index,
};
use rovsti_core::wire::WireContact;
use rovsti_wa_proto::{WaProfile, WaUser};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::BTreeMap;

pub(super) const OPS: &[OpSpec] = &[
    op!("contacts.wa_marks", MarksReq, MarksAns, marks, marks_cases),
    op!("contacts.wa_card", CardReq, CardAns, card, card_cases),
    op!(
        "contacts.wa_profile_lines",
        LinesReq,
        LinesAns,
        lines,
        lines_cases
    ),
];

// ---- contacts.wa_marks

#[derive(Deserialize)]
struct MarksReq {
    contacts: Vec<WireContact>,
    /// User parts of the 1:1 chats of the instance.
    #[serde(default)]
    chat_users: Vec<String>,
    /// The home calling code (`"90"`): a national number is compared in its E.164 form (F39).
    #[serde(default)]
    home_cc: Option<String>,
}

#[derive(Serialize)]
struct MarksAns {
    /// Contact id to the JID of its chat; ordered by id for stable answers.
    marks: BTreeMap<i64, String>,
}

fn marks(req: MarksReq) -> Result<MarksAns, FfiError> {
    Ok(MarksAns {
        marks: marked_contacts(&req.contacts, &req.chat_users, req.home_cc.as_deref())
            .into_iter()
            .collect(),
    })
}

fn marks_cases() -> Vec<Case> {
    let people = json!([
        {"id": 1, "display_name": "Ayşe Yılmaz", "numbers": [{"raw": "+90 532 123 45 67", "label": "mobile"}]},
        {"id": 2, "display_name": "Mehmet Demir", "numbers": [{"raw": "+90 216 555 03 04", "label": "work"}]},
        {"id": 3, "display_name": "Cem Aksoy", "numbers": []}
    ]);
    vec![
        Case::new(json!({"contacts": people, "chat_users": ["905321234567"]})),
        Case::named("no_chats", json!({"contacts": people, "chat_users": []})),
        // The shared fixture people only have national numbers, which never count as a match.
        Case::named(
            "local_number_ignored",
            json!({"contacts": wires(), "chat_users": ["905321234567"]}),
        ),
        // F39: with the home code a national number is compared in its E.164 form.
        Case::named(
            "local_number_home",
            json!({"contacts": [{"id": 4, "display_name": "Ayşe", "numbers": [{"raw": "0532 123 45 67"}]}],
                   "chat_users": ["905321234567"], "home_cc": "90"}),
        ),
    ]
}

// ---- contacts.wa_card

#[derive(Deserialize)]
struct LookupDto {
    /// `checking`, `done` or `failed`.
    state: String,
    #[serde(default)]
    users: Vec<WaUser>,
}

#[derive(Deserialize)]
struct PresenceDto {
    available: bool,
    #[serde(default)]
    last_seen_ms: Option<i64>,
}

#[derive(Deserialize)]
struct CardReq {
    id: i64,
    /// The contact's dialable numbers, in the order of their rows.
    numbers: Vec<String>,
    /// User parts of the 1:1 chats of the instance.
    #[serde(default)]
    chat_users: Vec<String>,
    /// Where contact `id`'s lookup stands; absent before it was asked.
    #[serde(default)]
    lookup: Option<LookupDto>,
    #[serde(default)]
    presence: Option<PresenceDto>,
    now_ms: i64,
    /// The phone's offset from UTC, for "last seen" lines.
    #[serde(default)]
    offset_s: i32,
    /// The home calling code (`"90"`): a national number is looked up and matched in its E.164 form (F39).
    #[serde(default)]
    home_cc: Option<String>,
}

#[derive(Serialize)]
struct CardAns {
    /// The JIDs to look up now (empty when an answer is held or the target is not a bare number).
    lookup_request: Vec<String>,
    /// The JID chat and call go to, once known.
    jid: Option<String>,
    /// One flag per number: that row carries a WhatsApp chip.
    chips: Vec<bool>,
    status_line: Option<String>,
    /// One entry per number: its JID when it is on WhatsApp.
    number_jids: Vec<Option<String>>,
}

fn lookup_state(dto: LookupDto) -> Result<LookupState, FfiError> {
    match dto.state.as_str() {
        "checking" => Ok(LookupState::Checking),
        "done" => Ok(LookupState::Done(dto.users)),
        "failed" => Ok(LookupState::Failed),
        other => Err(FfiError::invalid(format!("unknown lookup state {other:?}"))),
    }
}

fn card(mut req: CardReq) -> Result<CardAns, FfiError> {
    // One number, one key: the rows keep their order, a national number gains its country (F39).
    req.numbers = e164_numbers(&req.numbers, req.home_cc.as_deref());
    let mut panel = WaPanel {
        chat_users: req.chat_users,
        offset_s: req.offset_s,
        ..WaPanel::default()
    };
    if let Some(dto) = req.lookup {
        panel.lookups.insert(req.id, lookup_state(dto)?);
    }
    let target = panel.target(&req.numbers);
    let lookup = panel.lookups.get(&req.id);
    let presence = req.presence.map(|p| (p.available, p.last_seen_ms));
    let number_jids: Vec<Option<String>> = req
        .numbers
        .iter()
        .map(|n| number_jid(n, &panel.chat_users, lookup))
        .collect();
    Ok(CardAns {
        lookup_request: panel.lookup_request(req.id, &req.numbers),
        jid: panel.jid(req.id, &req.numbers),
        chips: wa_chip_index(&number_jids),
        status_line: status_line(&target, lookup, presence, req.now_ms, req.offset_s),
        number_jids,
    })
}

fn card_cases() -> Vec<Case> {
    let mine = "+90 532 123 45 67";
    let on = json!([{"jid": "905321234567@s.whatsapp.net", "on_whatsapp": true}]);
    let off = json!([{"jid": "905321234567@s.whatsapp.net", "on_whatsapp": false}]);
    vec![
        Case::new(json!({"id": 1, "numbers": [mine], "now_ms": NOW, "offset_s": TZ})),
        Case::named(
            "chat",
            json!({"id": 1, "numbers": [mine, "0532 123 45 67"], "chat_users": ["905321234567"],
                   "now_ms": NOW, "offset_s": TZ}),
        ),
        Case::named(
            "chat_online",
            json!({"id": 1, "numbers": [mine], "chat_users": ["905321234567"],
                   "presence": {"available": true}, "now_ms": NOW, "offset_s": TZ}),
        ),
        Case::named(
            "chat_last_seen",
            json!({"id": 1, "numbers": [mine], "chat_users": ["905321234567"],
                   "presence": {"available": false, "last_seen_ms": NOW - 3_600_000},
                   "now_ms": NOW, "offset_s": TZ}),
        ),
        Case::named(
            "lookup_checking",
            json!({"id": 1, "numbers": [mine], "lookup": {"state": "checking"}, "now_ms": NOW}),
        ),
        Case::named(
            "lookup_done",
            json!({"id": 1, "numbers": [mine], "lookup": {"state": "done", "users": on}, "now_ms": NOW}),
        ),
        Case::named(
            "lookup_not_on_whatsapp",
            json!({"id": 1, "numbers": [mine], "lookup": {"state": "done", "users": off}, "now_ms": NOW}),
        ),
        Case::named(
            "lookup_failed",
            json!({"id": 1, "numbers": [mine], "lookup": {"state": "failed"}, "now_ms": NOW}),
        ),
        Case::named(
            "local_number",
            json!({"id": 1, "numbers": ["0532 123 45 67"], "now_ms": NOW}),
        ),
        Case::named(
            "local_number_home",
            json!({"id": 1, "numbers": ["0532 123 45 67"], "chat_users": ["905321234567"],
                   "home_cc": "90", "now_ms": NOW}),
        ),
        Case::named("no_number", json!({"id": 1, "numbers": [], "now_ms": NOW})),
        Case::named(
            "bad_lookup_state",
            json!({"id": 1, "numbers": [mine], "lookup": {"state": "maybe"}, "now_ms": NOW}),
        ),
    ]
}

// ---- contacts.wa_profile_lines

#[derive(Deserialize)]
struct LinesReq {
    profile: WaProfile,
    contact_name: String,
}

#[derive(Serialize)]
struct LineDto {
    label: String,
    value: String,
}

#[derive(Serialize)]
struct LinesAns {
    lines: Vec<LineDto>,
}

fn lines(req: LinesReq) -> Result<LinesAns, FfiError> {
    Ok(LinesAns {
        lines: profile_lines(&req.profile, &req.contact_name)
            .into_iter()
            .map(|(label, value)| LineDto {
                label: label.to_owned(),
                value,
            })
            .collect(),
    })
}

fn lines_cases() -> Vec<Case> {
    let jid = "905321234567@s.whatsapp.net";
    vec![
        Case::new(json!({
            "profile": {"jid": jid, "push_name": "Ayşe Y.", "about": "Busy", "business": true, "verified_name": "Acme Ltd"},
            "contact_name": "Ayşe Yılmaz"})),
        Case::named(
            "same_name",
            json!({"profile": {"jid": jid, "push_name": "AYŞE YILMAZ"}, "contact_name": "Ayşe Yılmaz"}),
        ),
        Case::named(
            "empty",
            json!({"profile": {"jid": jid, "about": "  "}, "contact_name": "Ayşe Yılmaz"}),
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_chat_match_marks_and_carries_the_chip_once() {
        let ans = card(CardReq {
            id: 1,
            numbers: vec!["+90 532 123 45 67".into(), "0532 123 45 67".into()],
            chat_users: vec!["905321234567".into()],
            lookup: None,
            presence: None,
            now_ms: NOW,
            offset_s: TZ,
            home_cc: None,
        })
        .unwrap();
        assert_eq!(ans.chips.iter().filter(|c| **c).count(), 1);
        assert_eq!(ans.jid.as_deref(), Some("905321234567@s.whatsapp.net"));
        assert!(ans.lookup_request.is_empty());
    }

    #[test]
    fn a_national_number_with_the_home_code_gets_its_chat_jid() {
        // F39: the row of a contact saved as `0532 …` carries the chat `90532…`.
        let ans = card(CardReq {
            id: 1,
            numbers: vec!["0532 123 45 67".into(), "0532 123 45 68".into()],
            chat_users: vec!["905321234567".into()],
            lookup: None,
            presence: None,
            now_ms: NOW,
            offset_s: TZ,
            home_cc: Some("90".into()),
        })
        .unwrap();
        assert_eq!(
            ans.number_jids,
            vec![Some("905321234567@s.whatsapp.net".to_owned()), None]
        );
        assert_eq!(ans.chips, vec![true, false]);
    }

    #[test]
    fn an_unknown_lookup_state_is_invalid() {
        let err = lookup_state(LookupDto {
            state: "maybe".into(),
            users: vec![],
        });
        assert!(err.is_err());
    }
}
