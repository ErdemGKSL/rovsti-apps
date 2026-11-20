//! Rich content and presence (E6-WA R5). The gateway ops `wa.send_rich`, `wa.poll_vote` and
//! `wa.unsubscribe_presence`, and the pure rules of `rosty_core::wa_rich`: a poll draft's check and a poll bubble's
//! view, reading and writing vCards, a place's view and the tiles of its static map, and the header's presence line.

use super::fixture::{case, named};
use super::{Call, Empty};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rosty_core::wa_rich::{
    self, Card, CardNumber, HeaderPresence, PlaceView, PollDraft, PollProblem, PollView,
    PresenceSeen, StaticMap, TypingSeen,
};
use rosty_wa_proto::{Poll, PollVote, SendRich, Sent};
use serde::{Deserialize, Serialize};
use serde_json::json;

const GROUP: &str = "120363000000000011@g.us";
const PEER: &str = "100000000011@s.whatsapp.net";

pub(super) const OPS: &[OpSpec] = &[
    wa_op!("wa.send_rich", Call<SendRich>, Sent, send_rich, || vec![
        case(
            json!({"chat": GROUP, "content": {"type": "poll", "question": "Lunch?",
            "options": ["Pizza", "Soup"], "multiple": true}})
        ),
        named(
            "location",
            json!({"chat": PEER, "content": {"type": "location", "latitude": 41.0258,
            "longitude": 28.9744, "name": "Galata"}})
        ),
        named(
            "old_gateway",
            json!({"chat": PEER, "content": {"type": "contacts", "contacts": [
            {"display_name": "Ada", "vcard": "BEGIN:VCARD\nFN:Ada\nEND:VCARD"}]}})
        ),
    ]),
    wa_op!("wa.poll_vote", Call<PollVote>, Empty, poll_vote, || vec![
        case(json!({"chat": GROUP, "message_id": "3EB0P1", "options": ["Soup"]})),
        named(
            "take_back",
            json!({"chat": GROUP, "message_id": "3EB0P1", "options": []})
        ),
    ]),
    wa_op!(
        "wa.unsubscribe_presence",
        Call<super::rest::JidRef>,
        Empty,
        unsubscribe_presence,
        || vec![case(json!({"jid": PEER}))]
    ),
    op!(
        "wa.poll_check",
        PollDraft,
        CheckAns,
        poll_check,
        check_cases
    ),
    op!("wa.poll_view", ViewReq, PollView, poll_view, view_cases),
    op!(
        "wa.vcard_parse",
        ParseReq,
        ParseAns,
        vcard_parse,
        parse_cases
    ),
    op!(
        "wa.vcard_build",
        BuildReq,
        BuildAns,
        vcard_build,
        build_cases
    ),
    op!(
        "wa.place_view",
        PlaceReq,
        PlaceView,
        place_view,
        place_cases
    ),
    op!("wa.static_map", MapReq, StaticMap, static_map, map_cases),
    op!(
        "wa.presence_view",
        PresenceReq,
        HeaderPresence,
        presence_view,
        presence_cases
    ),
];

gateway_fn!(send_rich, SendRich => Sent, |g, b| g.send_rich(&b));
gateway_fn!(poll_vote, PollVote => Empty, |g, b| super::done(g.poll_vote(&b)));
gateway_fn!(unsubscribe_presence, super::rest::JidRef => Empty, |g, b| super::done(
    g.unsubscribe_presence(b.jid())
));

// ---- wa.poll_check

#[derive(Serialize)]
struct CheckAns {
    /// The draft as it is sent (trimmed, empty options dropped); left out when it cannot be sent.
    #[serde(skip_serializing_if = "Option::is_none")]
    draft: Option<PollDraft>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    problems: Vec<PollProblem>,
    min_options: usize,
    max_options: usize,
}

fn poll_check(req: PollDraft) -> Result<CheckAns, FfiError> {
    let (draft, problems) = match wa_rich::check_draft(&req) {
        Ok(draft) => (Some(draft), Vec::new()),
        Err(problems) => (None, problems),
    };
    Ok(CheckAns {
        draft,
        problems,
        min_options: wa_rich::MIN_OPTIONS,
        max_options: wa_rich::MAX_OPTIONS,
    })
}

fn check_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"question": " Lunch? ", "options": ["Pizza", " Soup ", ""], "multiple": true}),
        ),
        Case::named("problems", json!({"question": "", "options": ["A", "A"]})),
    ]
}

// ---- wa.poll_view

#[derive(Deserialize)]
struct ViewReq {
    poll: Poll,
    /// My ids (number and LID): a voter that is one of them is me.
    #[serde(default)]
    own: Vec<String>,
}

fn poll_view(req: ViewReq) -> Result<PollView, FfiError> {
    let options: Vec<wa_rich::OptionTally> = req
        .poll
        .options
        .iter()
        .map(|o| wa_rich::OptionTally {
            name: o.name.clone(),
            voters: o.voters.clone(),
        })
        .collect();
    Ok(wa_rich::poll_view(
        &req.poll.question,
        req.poll.selectable,
        &options,
        &req.poll.my_votes,
        &req.own,
    ))
}

fn view_cases() -> Vec<Case> {
    vec![Case::new(json!({
        "poll": {"question": "Lunch?", "selectable": 1, "my_votes": ["Soup"], "options": [
            {"name": "Pizza", "voters": ["100000000011@s.whatsapp.net", "777000000000012@lid"]},
            {"name": "Soup", "voters": ["me"]},
            {"name": "Salad"}]},
        "own": ["100000000019@s.whatsapp.net"]
    }))]
}

// ---- wa.vcard_parse

#[derive(Deserialize)]
struct ParseReq {
    vcard: String,
}

#[derive(Serialize)]
struct ParseAns {
    cards: Vec<Card>,
}

fn vcard_parse(req: ParseReq) -> Result<ParseAns, FfiError> {
    Ok(ParseAns {
        cards: wa_rich::parse_vcards(&req.vcard),
    })
}

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"vcard": "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Yılmaz;Ayşe;;;\r\nFN:Ayşe Yılmaz\r\n\
            item1.TEL;waid=100000000031:+1 000 000 0031\r\nitem1.X-ABLabel:Mobile\r\n\
            item2.TEL:+1 000 000 0032\r\nitem2.X-ABLabel:Work\r\nEND:VCARD"}),
        ),
        Case::named(
            "no_number",
            json!({"vcard": "BEGIN:VCARD\nVERSION:3.0\nFN:Ben\nEND:VCARD"}),
        ),
    ]
}

// ---- wa.vcard_build

#[derive(Deserialize)]
struct BuildReq {
    name: String,
    #[serde(default)]
    numbers: Vec<CardNumber>,
}

#[derive(Serialize)]
struct BuildAns {
    vcard: String,
}

fn vcard_build(req: BuildReq) -> Result<BuildAns, FfiError> {
    if req.name.trim().is_empty() {
        return Err(FfiError::invalid("a contact card needs a name"));
    }
    let vcard = wa_rich::build_vcard(&req.name, &req.numbers);
    if vcard.len() > wa_rich::MAX_VCARD_BYTES {
        return Err(FfiError::invalid("the contact card is too large"));
    }
    Ok(BuildAns { vcard })
}

fn build_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"name": "Ayşe Yılmaz", "numbers": [
            {"number": "+1 000 000 0031"}, {"number": "0555 000 0032", "label": "Home"}]})),
        Case::named("no_name", json!({"name": " "})),
    ]
}

// ---- wa.place_view

#[derive(Deserialize)]
struct PlaceReq {
    latitude: f64,
    longitude: f64,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    address: Option<String>,
    #[serde(default)]
    live: bool,
}

fn place_view(req: PlaceReq) -> Result<PlaceView, FfiError> {
    wa_rich::place_view(
        req.latitude,
        req.longitude,
        req.name.as_deref(),
        req.address.as_deref(),
        req.live,
    )
    .ok_or_else(|| FfiError::invalid("the coordinates name no place"))
}

fn place_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"latitude": 41.0258, "longitude": 28.9744, "name": "Galata Kulesi",
            "address": "Bereketzade, Beyoğlu"}),
        ),
        Case::named(
            "no_name",
            json!({"latitude": 41.0, "longitude": 29.0, "live": true}),
        ),
        Case::named("nowhere", json!({"latitude": 95.0, "longitude": 0.0})),
    ]
}

// ---- wa.static_map

#[derive(Deserialize)]
struct MapReq {
    latitude: f64,
    longitude: f64,
    #[serde(default = "default_zoom")]
    zoom: u8,
    width: u32,
    height: u32,
    /// The side of a tile in the picture (pixels); 256 at 1x.
    tile_px: u32,
}

fn default_zoom() -> u8 {
    wa_rich::STATIC_MAP_ZOOM
}

fn static_map(req: MapReq) -> Result<StaticMap, FfiError> {
    wa_rich::static_map(
        req.latitude,
        req.longitude,
        req.zoom,
        req.width,
        req.height,
        req.tile_px,
    )
    .ok_or_else(|| FfiError::invalid("no map for these coordinates or this size"))
}

fn map_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"latitude": 41.0258, "longitude": 28.9744, "width": 600, "height": 300,
            "tile_px": 512}),
        ),
        Case::named(
            "empty",
            json!({"latitude": 41.0, "longitude": 29.0, "width": 0, "height": 300, "tile_px": 512}),
        ),
    ]
}

// ---- wa.presence_view

#[derive(Deserialize)]
struct PresenceReq {
    #[serde(default)]
    group: bool,
    #[serde(default)]
    presence: Option<PresenceSeen>,
    #[serde(default)]
    typing: Vec<TypingSeen>,
    now_ms: i64,
}

fn presence_view(req: PresenceReq) -> Result<HeaderPresence, FfiError> {
    Ok(wa_rich::header_presence(
        req.group,
        req.presence,
        &req.typing,
        req.now_ms,
    ))
}

fn presence_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"presence": {"available": false, "last_seen_ms": 1_799_996_400_000_i64},
            "now_ms": 1_800_000_000_000_i64}),
        ),
        Case::named(
            "typing",
            json!({"presence": {"available": true}, "typing": [
            {"jid": PEER, "kind": "typing", "at_ms": 1_799_999_995_000_i64}], "now_ms": 1_800_000_000_000_i64}),
        ),
        Case::named(
            "group",
            json!({"group": true, "typing": [
            {"jid": PEER, "kind": "recording", "at_ms": 1_799_999_999_000_i64},
            {"jid": "100000000012@s.whatsapp.net", "kind": "typing", "at_ms": 1_799_999_999_000_i64}],
            "now_ms": 1_800_000_000_000_i64}),
        ),
        Case::named(
            "hidden",
            json!({"presence": {"available": false}, "now_ms": 1_800_000_000_000_i64}),
        ),
    ]
}
