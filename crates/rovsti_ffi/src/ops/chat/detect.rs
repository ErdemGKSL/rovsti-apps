//! `text_detect.detect`, `date_detect.detect`, `location_link.*`, `shared_card.*`, `message_info.rows` and
//! `message_reminder.*`: what a message body or a card holds, and the rows and reminders built from a message.

use super::{NOW, Span, TZ};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::date_detect;
use rovsti_core::location_link;
use rovsti_core::message_info::{self, InfoChannel, InfoInput, InfoState};
use rovsti_core::message_reminder::{self, MessageReminder};
use rovsti_core::recents::LocaleTime;
use rovsti_core::reminder::{self, Slot};
use rovsti_core::shared_card::{self, CardField, CardView};
use rovsti_core::text_detect::{self, DetectAction, Detected};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "text_detect.detect",
        TextReq,
        TextAns,
        text_detect_op,
        text_cases
    ),
    op!("date_detect.detect", DatesReq, DatesAns, dates, dates_cases),
    op!(
        "location_link.find",
        PlacesReq,
        PlacesAns,
        places,
        places_cases
    ),
    op!(
        "location_link.share",
        ShareReq,
        ShareAns,
        share,
        share_cases
    ),
    op!("shared_card.read", CardReq, CardAns, card_read, card_cases),
    op!("shared_card.mime", MimeReq, MimeAns, card_mime, mime_cases),
    op!("message_info.rows", InfoReq, InfoAns, info_rows, info_cases),
    op!(
        "message_reminder.make",
        MakeReq,
        MakeAns,
        reminder_make,
        make_cases
    ),
    op!(
        "message_reminder.slots",
        SlotsReq,
        SlotsAns,
        reminder_slots,
        slots_cases
    ),
    op!(
        "message_reminder.list",
        ListReq,
        ListAns,
        reminder_list,
        list_cases
    ),
    op!(
        "call_reminder.make",
        CallMakeReq,
        CallMakeAns,
        call_make,
        call_make_cases
    ),
    op!(
        "call_reminder.list",
        CallListReq,
        CallListAns,
        call_list,
        call_list_cases
    ),
];

// ---- text_detect ----

#[derive(Deserialize)]
struct TextReq {
    body: String,
    /// At most this many numbers and addresses (10 when absent).
    #[serde(default)]
    max: Option<usize>,
    /// Numbers of the chat's own counterpart: no Call pill for them.
    #[serde(default)]
    own: Vec<String>,
}

#[derive(Serialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum FoundKind {
    Phone,
    Email,
}

#[derive(Serialize)]
struct FoundOut {
    kind: FoundKind,
    /// As written in the body.
    text: String,
}

#[derive(Serialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum ActionKind {
    Call,
    Message,
    CopyNumber,
    Email,
    CopyEmail,
}

#[derive(Serialize)]
struct ActionOut {
    kind: ActionKind,
    /// The number (as written for call and copy, dialled for message) or the address.
    value: String,
    /// The pill text.
    label: String,
}

#[derive(Serialize)]
struct TextAns {
    found: Vec<FoundOut>,
    actions: Vec<ActionOut>,
    /// `value` reduced to what is dialled, per found phone number, in order.
    dialled: Vec<String>,
}

fn text_detect_op(req: TextReq) -> Result<TextAns, FfiError> {
    let max = req.max.unwrap_or(10);
    let own: Vec<&str> = req.own.iter().map(String::as_str).collect();
    let found = text_detect::detect(&req.body, max);
    let dialled = found
        .iter()
        .filter_map(|d| match d {
            Detected::Phone(raw) => Some(text_detect::dial(raw)),
            Detected::Email(_) => None,
        })
        .collect();
    Ok(TextAns {
        found: found
            .into_iter()
            .map(|d| match d {
                Detected::Phone(text) => FoundOut {
                    kind: FoundKind::Phone,
                    text,
                },
                Detected::Email(text) => FoundOut {
                    kind: FoundKind::Email,
                    text,
                },
            })
            .collect(),
        actions: text_detect::actions(&req.body, max, &own)
            .into_iter()
            .map(|a| {
                let label = a.label();
                let (kind, value) = match a {
                    DetectAction::Call(v) => (ActionKind::Call, v),
                    DetectAction::Message(v) => (ActionKind::Message, v),
                    DetectAction::CopyNumber(v) => (ActionKind::CopyNumber, v),
                    DetectAction::Email(v) => (ActionKind::Email, v),
                    DetectAction::CopyEmail(v) => (ActionKind::CopyEmail, v),
                };
                ActionOut { kind, value, label }
            })
            .collect(),
        dialled,
    })
}

fn text_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"body": "Beni ara: 0532 123 45 67 veya ada@example.com, saat 14:05",
                         "own": ["+90 532 123 45 67"]}),
        ),
        Case::named(
            "two_numbers",
            json!({"body": "+49 151 2345678 / (0212) 555-11-22", "max": 5}),
        ),
        Case::named(
            "not_a_number",
            json!({"body": "Kod 482913, tarih 12.11.2026, tutar 12.50 TL https://a.co/123456789"}),
        ),
    ]
}

// ---- date_detect ----

#[derive(Deserialize)]
struct DatesReq {
    body: String,
    now_ms: i64,
    tz_offset_s: i32,
    #[serde(default)]
    max: Option<usize>,
    /// The chat title, for the calendar event title.
    #[serde(default)]
    chat: Option<String>,
}

#[derive(Serialize)]
struct DateOut {
    /// UTF-16 range of the matched text in the body.
    start: usize,
    end: usize,
    /// What "Copy date" copies.
    text: String,
    start_ms: i64,
    /// One hour after the start, one day when all day.
    end_ms: i64,
    all_day: bool,
    /// `Thu 12 Nov, 15:00` or `Thu 12 Nov 2026`.
    label: String,
}

#[derive(Serialize)]
struct DatesAns {
    dates: Vec<DateOut>,
    /// `{chat}: {first 40 characters}` for the calendar insert.
    event_title: String,
}

fn dates(req: DatesReq) -> Result<DatesAns, FfiError> {
    let found = date_detect::detect(&req.body, req.now_ms, req.tz_offset_s, req.max.unwrap_or(5));
    Ok(DatesAns {
        event_title: date_detect::event_title(req.chat.as_deref().unwrap_or_default(), &req.body),
        dates: found
            .iter()
            .map(|f| {
                let span = Span::of(&req.body, &f.span);
                DateOut {
                    start: span.start,
                    end: span.end,
                    text: date_detect::matched_text(&req.body, f).to_owned(),
                    start_ms: f.start_ms,
                    end_ms: date_detect::calendar_end_ms(f),
                    all_day: f.all_day,
                    label: f.label.clone(),
                }
            })
            .collect(),
    })
}

fn dates_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"body": "Yarın 15:00 görüşelim, 2026-12-01 at 10:30 de olur", "now_ms": NOW,
                         "tz_offset_s": TZ, "chat": "Ayşe"}),
        ),
        Case::named(
            "all_day",
            json!({"body": "Toplantı 12.11.2027", "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "past",
            json!({"body": "Geçen 01.01.2026", "now_ms": NOW, "tz_offset_s": TZ, "chat": "X"}),
        ),
    ]
}

// ---- location_link ----

#[derive(Deserialize)]
struct PlacesReq {
    text: String,
    #[serde(default)]
    max: Option<usize>,
}

#[derive(Serialize)]
struct PlaceOut {
    lat: f64,
    lon: f64,
    label: Option<String>,
    /// UTF-16 range of the link in the text.
    start: usize,
    end: usize,
    /// `geo:` URL for a maps app.
    geo_url: String,
    /// `41.008200, 28.978400`, for the clipboard.
    coords: String,
}

#[derive(Serialize)]
struct PlacesAns {
    places: Vec<PlaceOut>,
}

fn places(req: PlacesReq) -> Result<PlacesAns, FfiError> {
    Ok(PlacesAns {
        places: location_link::find_places(&req.text, req.max.unwrap_or(5))
            .into_iter()
            .map(|p| {
                let span = Span::of(&req.text, &p.span);
                PlaceOut {
                    geo_url: location_link::geo_url(p.lat, p.lon, p.label.as_deref()),
                    coords: location_link::coords_text(p.lat, p.lon),
                    lat: p.lat,
                    lon: p.lon,
                    label: p.label,
                    start: span.start,
                    end: span.end,
                }
            })
            .collect(),
    })
}

fn places_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"text": "Buradayım 😊 https://www.google.com/maps?q=41.0082,28.9784 gel"}),
        ),
        Case::named(
            "geo_with_name",
            json!({"text": "geo:41.008200,28.978400?q=41.008200,28.978400(Ayasofya%20M%C3%BCzesi)"}),
        ),
        Case::named("none", json!({"text": "Hiçbir yer yok geo:0,0"})),
    ]
}

#[derive(Deserialize)]
struct ShareReq {
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    address: Option<String>,
    lat: f64,
    lon: f64,
}

#[derive(Serialize)]
struct ShareAns {
    /// Finite, in range and not the (0, 0) placeholder.
    valid: bool,
    osm_url: String,
    geo_url: String,
    coords: String,
    /// Name, address and the link, one per line.
    text: String,
}

fn share(req: ShareReq) -> Result<ShareAns, FfiError> {
    Ok(ShareAns {
        valid: location_link::is_valid(req.lat, req.lon),
        osm_url: location_link::osm_url(req.lat, req.lon),
        geo_url: location_link::geo_url(req.lat, req.lon, req.name.as_deref()),
        coords: location_link::coords_text(req.lat, req.lon),
        text: location_link::share_text(
            req.name.as_deref(),
            req.address.as_deref(),
            req.lat,
            req.lon,
        ),
    })
}

fn share_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"name": "Ayasofya", "address": "Sultanahmet", "lat": 41.0082, "lon": 28.9784}),
        ),
        Case::named("placeholder", json!({"lat": 0.0, "lon": 0.0})),
    ]
}

// ---- shared_card ----

#[derive(Serialize, Deserialize, Clone)]
struct FieldDto {
    value: String,
    label: String,
}

#[derive(Serialize, Deserialize, Clone)]
struct CardDto {
    name: String,
    org: String,
    numbers: Vec<FieldDto>,
    emails: Vec<FieldDto>,
    note: String,
}

fn field_out(f: CardField) -> FieldDto {
    FieldDto {
        value: f.value,
        label: f.label,
    }
}

fn card_out(c: CardView) -> CardDto {
    CardDto {
        name: c.name,
        org: c.org,
        numbers: c.numbers.into_iter().map(field_out).collect(),
        emails: c.emails.into_iter().map(field_out).collect(),
        note: c.note,
    }
}

#[derive(Deserialize)]
struct CardReq {
    /// Shown when the vCard has no name.
    #[serde(default)]
    display_name: String,
    vcard: String,
    /// Numbers listed in a bubble before "+N more" (2 when absent).
    #[serde(default)]
    max_lines: Option<usize>,
}

#[derive(Serialize)]
struct CardAns {
    card: CardDto,
    /// The number to call or message first.
    primary_number: Option<String>,
    /// The bubble's number lines, then "+N more".
    number_lines: Vec<String>,
    /// vCard 3.0 text of the card (what Save .vcf writes).
    vcf: String,
    /// `ada-lovelace.vcf`.
    file_name: String,
    /// `card-Ada-Lovelace.vcf`, the cache file of a Save.
    cache_file_name: String,
    /// Plain text for the share sheet.
    share_text: String,
    /// JSON for the insert-contact intent (a string).
    insert_json: String,
}

fn card_read(req: CardReq) -> Result<CardAns, FfiError> {
    let view = shared_card::from_vcard(&req.display_name, &req.vcard);
    Ok(CardAns {
        primary_number: shared_card::primary_number(&view).map(str::to_owned),
        number_lines: shared_card::number_lines(&view, req.max_lines.unwrap_or(2)),
        vcf: shared_card::to_vcard(&view),
        file_name: shared_card::vcf_file_name(&view.name),
        cache_file_name: shared_card::cache_file_name(&view.name),
        share_text: shared_card::share_text(&view),
        insert_json: shared_card::insert_json(&view),
        card: card_out(view),
    })
}

fn card_cases() -> Vec<Case> {
    let full = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Lovelace;Ada;;;\r\nFN:Ada Lovelace\r\nORG:Analytical\r\n\
TEL;TYPE=CELL:+44 20 7946 0001\r\nTEL;TYPE=WORK:+44 20 7946 0002\r\nTEL;TYPE=HOME:+44 20 7946 0003\r\n\
EMAIL:ada@example.com\r\nNOTE:Hello\r\nEND:VCARD\r\n";
    vec![
        Case::new(json!({"display_name": "Ada", "vcard": full})),
        Case::named(
            "no_name",
            json!({"display_name": "", "vcard": "BEGIN:VCARD\nVERSION:3.0\nTEL:5551112233\nEND:VCARD\n"}),
        ),
        Case::named(
            "unreadable",
            json!({"display_name": "Ayşe", "vcard": "not a card"}),
        ),
    ]
}

#[derive(Deserialize)]
struct MimeReq {
    mime: String,
}

#[derive(Serialize)]
struct MimeAns {
    /// The part is a vCard.
    vcard: bool,
    /// The largest vCard file that is read (bytes).
    max_bytes: u64,
}

fn card_mime(req: MimeReq) -> Result<MimeAns, FfiError> {
    Ok(MimeAns {
        vcard: shared_card::is_vcard_mime(&req.mime),
        max_bytes: shared_card::MAX_VCARD_BYTES,
    })
}

fn mime_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"mime": "Text/X-vCard; charset=utf-8"})),
        Case::named("image", json!({"mime": "image/jpeg"})),
    ]
}

// ---- message_info ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum ChannelDto {
    Whatsapp,
    Sms,
    Mms,
}

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum StateDto {
    Pending,
    Sent,
    Delivered,
    Read,
    Played,
    Failed,
    Received,
}

#[derive(Deserialize)]
struct InfoDto {
    #[serde(default)]
    channel: Option<ChannelDto>,
    #[serde(default)]
    at_ms: i64,
    #[serde(default)]
    outgoing: bool,
    #[serde(default)]
    state: Option<StateDto>,
    #[serde(default)]
    author: Option<String>,
    #[serde(default)]
    edited_ms: Option<i64>,
    #[serde(default)]
    forwarded: bool,
    #[serde(default)]
    starred: bool,
    #[serde(default)]
    sim: Option<String>,
    #[serde(default)]
    text: Option<String>,
    /// Short description, e.g. "Photo, 1280 × 720".
    #[serde(default)]
    media: Option<String>,
}

#[derive(Deserialize)]
struct InfoReq {
    info: InfoDto,
    locale: LocaleTime,
}

#[derive(Serialize)]
struct InfoRowOut {
    label: &'static str,
    value: String,
}

#[derive(Serialize)]
struct InfoAns {
    rows: Vec<InfoRowOut>,
    /// "Label: value" lines, for Copy.
    text: String,
}

fn info_rows(req: InfoReq) -> Result<InfoAns, FfiError> {
    let i = &req.info;
    let input = InfoInput {
        channel: i.channel.map(|c| match c {
            ChannelDto::Whatsapp => InfoChannel::WhatsApp,
            ChannelDto::Sms => InfoChannel::Sms,
            ChannelDto::Mms => InfoChannel::Mms,
        }),
        at_ms: i.at_ms,
        outgoing: i.outgoing,
        state: i.state.map(|s| match s {
            StateDto::Pending => InfoState::Pending,
            StateDto::Sent => InfoState::Sent,
            StateDto::Delivered => InfoState::Delivered,
            StateDto::Read => InfoState::Read,
            StateDto::Played => InfoState::Played,
            StateDto::Failed => InfoState::Failed,
            StateDto::Received => InfoState::Received,
        }),
        author: i.author.as_deref(),
        edited_ms: i.edited_ms,
        forwarded: i.forwarded,
        starred: i.starred,
        sim: i.sim.as_deref(),
        text: i.text.as_deref(),
        media: i.media.as_deref(),
    };
    let rows = message_info::rows(&input, req.locale);
    Ok(InfoAns {
        text: message_info::info_text(&rows),
        rows: rows
            .into_iter()
            .map(|r| InfoRowOut {
                label: r.label,
                value: r.value,
            })
            .collect(),
    })
}

fn locale() -> Value {
    json!({"tz_offset_s": TZ, "format": {"hour12": false, "date_order": "day_month_year"}})
}

fn info_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"locale": locale(), "info": {
            "channel": "sms", "at_ms": NOW, "outgoing": true, "state": "delivered",
            "starred": true, "sim": "SIM 2", "text": "Merhaba dünya"}})),
        Case::named(
            "incoming_whatsapp",
            json!({"locale": locale(), "info": {
                "channel": "whatsapp", "at_ms": NOW, "author": "Ayşe", "edited_ms": NOW + 60_000,
                "forwarded": true, "media": "Photo, 1280 × 720"}}),
        ),
        Case::named("unknown", json!({"locale": locale(), "info": {}})),
    ]
}

// ---- message_reminder ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum SlotDto {
    In15Min,
    In1Hour,
    Tonight,
    TomorrowMorning,
}

impl SlotDto {
    fn slot(self) -> Slot {
        match self {
            Self::In15Min => Slot::In15Min,
            Self::In1Hour => Slot::In1Hour,
            Self::Tonight => Slot::Tonight,
            Self::TomorrowMorning => Slot::TomorrowMorning,
        }
    }
}

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum MakeKind {
    /// A reminder on one message at a chosen slot.
    Message,
    /// A reminder on a whole chat (from a notification) after `minutes`.
    Chat,
}

#[derive(Deserialize)]
struct MakeReq {
    kind: MakeKind,
    /// Chat tag: `sms:12` or `wa:<jid>`.
    tag: String,
    #[serde(default)]
    message_id: String,
    #[serde(default)]
    message_at_ms: i64,
    title: String,
    preview: String,
    #[serde(default = "default_slot")]
    slot: SlotDto,
    #[serde(default)]
    minutes: i64,
    now_ms: i64,
    tz_offset_s: i32,
}

fn default_slot() -> SlotDto {
    SlotDto::In1Hour
}

#[derive(Serialize)]
struct MakeAns {
    reminder: MessageReminder,
    /// The reminder plus its notification texts, as the alarm layer stores it.
    arm_json: String,
    notif_title: String,
    notif_body: String,
    /// "Reminder set for 18:05" / "... for tomorrow at 09:00".
    set_text: String,
    /// The badge on the message, "Reminder 18:05".
    badge: String,
    /// `HH:MM` local time the reminder fires.
    at_clock: String,
}

fn reminder_make(req: MakeReq) -> Result<MakeAns, FfiError> {
    let made = match req.kind {
        MakeKind::Message => message_reminder::new_reminder(
            &req.tag,
            &req.message_id,
            req.message_at_ms,
            &req.title,
            &req.preview,
            req.slot.slot(),
            req.now_ms,
            req.tz_offset_s,
        ),
        MakeKind::Chat => message_reminder::chat_reminder(
            &req.tag,
            &req.title,
            &req.preview,
            req.minutes,
            req.now_ms,
        ),
    };
    let r =
        made.ok_or_else(|| FfiError::invalid("a reminder needs a tag, a message and a delay"))?;
    let arm_json = message_reminder::arm_json(&r);
    let armed: Value = serde_json::from_str(&arm_json)?;
    let text_of = |key: &str| armed[key].as_str().unwrap_or_default().to_owned();
    Ok(MakeAns {
        notif_title: text_of("notif_title"),
        notif_body: text_of("notif_body"),
        set_text: message_reminder::set_text(&r, req.now_ms, req.tz_offset_s),
        badge: message_reminder::badge_text(&r, req.tz_offset_s),
        at_clock: reminder::clock(r.at_ms, req.tz_offset_s),
        arm_json,
        reminder: r,
    })
}

fn make_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"kind": "message", "tag": "sms:12", "message_id": "sms:99",
            "message_at_ms": NOW - 60_000, "title": "Ayşe", "preview": "Akşam   kahve\niçelim mi?",
            "slot": "tomorrow_morning", "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "tonight",
            json!({"kind": "message", "tag": "wa:90@s.whatsapp.net", "message_id": "3EB0",
                   "message_at_ms": NOW, "title": "", "preview": "", "slot": "tonight",
                   "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "chat",
            json!({"kind": "chat", "tag": "sms:12", "title": "Ayşe", "preview": "Selam",
                   "minutes": 30, "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "blank_tag",
            json!({"kind": "message", "tag": " ", "message_id": "x", "title": "", "preview": "",
                   "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct SlotsReq {
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct SlotOut {
    /// `in15_min`, `in1_hour`, `tonight` or `tomorrow_morning`.
    key: &'static str,
    label: String,
    due_ms: i64,
}

#[derive(Serialize)]
struct SlotsAns {
    slots: Vec<SlotOut>,
}

fn reminder_slots(req: SlotsReq) -> Result<SlotsAns, FfiError> {
    let key = |s: Slot| match s {
        Slot::In15Min => "in15_min",
        Slot::In1Hour => "in1_hour",
        Slot::Tonight => "tonight",
        Slot::TomorrowMorning => "tomorrow_morning",
    };
    Ok(SlotsAns {
        slots: Slot::ALL
            .into_iter()
            .map(|s| SlotOut {
                key: key(s),
                label: s.label(req.now_ms, req.tz_offset_s),
                due_ms: s.due_ms(req.now_ms, req.tz_offset_s),
            })
            .collect(),
    })
}

fn slots_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"now_ms": NOW, "tz_offset_s": TZ})),
        Case::named(
            "late_evening",
            json!({"now_ms": NOW + 4 * 3_600_000, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct ListReq {
    /// The stored list (JSON text); bad JSON is an empty list and bad entries are skipped.
    json: String,
    #[serde(default)]
    message_id: Option<String>,
    /// A `message_reminder_fired` payload (`{"id": 5}`).
    #[serde(default)]
    fired: Option<String>,
}

#[derive(Serialize)]
struct ListAns {
    reminders: Vec<MessageReminder>,
    /// The reminder armed for `message_id`.
    for_message: Option<MessageReminder>,
    fired_id: Option<i64>,
}

fn reminder_list(req: ListReq) -> Result<ListAns, FfiError> {
    let reminders = message_reminder::parse_list(&req.json);
    Ok(ListAns {
        for_message: req
            .message_id
            .as_deref()
            .and_then(|id| message_reminder::for_message(&reminders, id))
            .cloned(),
        fired_id: req.fired.as_deref().and_then(message_reminder::fired_id),
        reminders,
    })
}

fn list_cases() -> Vec<Case> {
    let item = |id: i64, message: &str| {
        json!({"id": id, "tag": "sms:12", "message_id": message, "message_at_ms": NOW - 1000,
               "at_ms": NOW + 3_600_000, "title": "Ayşe", "preview": "Selam"})
    };
    vec![
        Case::new(
            json!({"json": json!([item(1, "sms:1"), item(2, "sms:2")]).to_string(),
                         "message_id": "sms:2", "fired": "{\"id\": \"1\"}"}),
        ),
        Case::named(
            "corrupt",
            json!({"json": "not json", "message_id": "sms:2", "fired": "{}"}),
        ),
        Case::named(
            "skips_bad_entries",
            json!({"json": json!([item(3, "sms:3"), {"id": "bad"}]).to_string()}),
        ),
    ]
}

// ---- call_reminder (the Phone app's "call back later", same slots) ----

#[derive(Deserialize)]
struct CallMakeReq {
    number: String,
    #[serde(default)]
    name: Option<String>,
    slot: SlotDto,
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct CallMakeAns {
    reminder: reminder::Reminder,
    /// The reminder plus its notification `title` and `body`, as the alarm layer stores it.
    arm_json: String,
    /// "Call back Anna", or the grouped number.
    title: String,
    body: String,
    /// "Reminder at 18:05" / "Reminder tomorrow at 09:00".
    set_text: String,
    at_clock: String,
}

fn call_make(req: CallMakeReq) -> Result<CallMakeAns, FfiError> {
    let r = reminder::new_reminder(
        &req.number,
        req.name.as_deref(),
        req.slot.slot(),
        req.now_ms,
        req.tz_offset_s,
    )
    .ok_or_else(|| FfiError::invalid("a private or empty number cannot be reminded"))?;
    Ok(CallMakeAns {
        arm_json: reminder::arm_json(&r),
        title: reminder::notification_title(&r),
        body: reminder::notification_body(&r),
        set_text: reminder::set_text(&r, req.now_ms, req.tz_offset_s),
        at_clock: reminder::clock(r.at_ms, req.tz_offset_s),
        reminder: r,
    })
}

fn call_make_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"number": "+90 532 123 45 67", "name": "Ayşe", "slot": "tomorrow_morning",
                         "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "no_name",
            json!({"number": "05321234567", "slot": "in15_min", "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "private_number",
            json!({"number": " ", "slot": "tonight", "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct CallListReq {
    json: String,
    #[serde(default)]
    number: Option<String>,
}

#[derive(Serialize)]
struct CallListAns {
    reminders: Vec<reminder::Reminder>,
    /// The reminder armed for `number`, matched by parsed number.
    for_number: Option<reminder::Reminder>,
}

fn call_list(req: CallListReq) -> Result<CallListAns, FfiError> {
    let reminders = reminder::parse_list(&req.json);
    Ok(CallListAns {
        for_number: req
            .number
            .as_deref()
            .and_then(|n| reminder::for_number(&reminders, n))
            .cloned(),
        reminders,
    })
}

fn call_list_cases() -> Vec<Case> {
    let item = json!({"id": 1, "number": "+90 532 123 45 67", "name": "Ayşe", "at_ms": NOW + 60_000,
                      "created_ms": NOW, "missed": true});
    vec![
        Case::new(json!({"json": json!([item]).to_string(), "number": "0532 123 45 67"})),
        Case::named("corrupt", json!({"json": "{", "number": "1"})),
    ]
}
