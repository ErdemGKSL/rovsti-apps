//! `thread_marks.edit`, `mute.*`, `starred.*`, `quick_replies.*` and `forward.*`: the local marks on threads, the mute
//! choices, the starred lists, the quick-reply templates and what a forward sends.

use super::{NOW, TZ};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::forward::{self, ForwardBody, ForwardPlan, ForwardTo};
use rovsti_core::mute::{self, MuteFor};
use rovsti_core::quick_replies::{self, ReplyError};
use rovsti_core::starred::{self, LocalStar, LocalStars, StarCandidate, StarredRow};
use rovsti_core::thread_marks::{self, PinError, SmsMarks};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "thread_marks.edit",
        MarksReq,
        MarksAns,
        marks_edit,
        marks_cases
    ),
    op!(
        "mute.choices",
        ChoicesReq,
        ChoicesAns,
        mute_choices,
        choices_cases
    ),
    op!(
        "mute.status",
        StatusReq,
        StatusAns,
        mute_status,
        status_cases
    ),
    op!("starred.rows", RowsReq, RowsAns, starred_rows, rows_cases),
    op!(
        "starred.local",
        LocalReq,
        LocalAns,
        starred_local,
        local_cases
    ),
    op!(
        "quick_replies.edit",
        RepliesReq,
        RepliesAns,
        replies_edit,
        replies_cases
    ),
    op!(
        "quick_replies.insert",
        InsertReq,
        InsertAns,
        replies_insert,
        insert_cases
    ),
    op!("forward.plan", PlanReq, PlanAns, forward_plan, plan_cases),
    op!(
        "forward.words",
        WordsReq,
        WordsAns,
        forward_words,
        words_cases
    ),
];

// ---- thread_marks ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum MarksAction {
    Pin,
    Unpin,
    Archive,
    Unarchive,
    MarkUnread,
    /// Clears the unread mark (on open, Mark read).
    MarkRead,
    /// Drops every mark of a deleted thread.
    Forget,
    /// Moves every mark from `thread` to `new_thread`.
    Rekey,
}

#[derive(Deserialize)]
struct MarksReq {
    #[serde(default)]
    marks: SmsMarks,
    action: MarksAction,
    thread: i64,
    #[serde(default)]
    new_thread: Option<i64>,
}

#[derive(Serialize)]
struct MarksAns {
    marks: SmsMarks,
    changed: bool,
    /// `too_many_pins` when a pin was refused (the cap is 5).
    refused: Option<&'static str>,
    /// The pill to show.
    notice: Option<&'static str>,
}

fn marks_edit(req: MarksReq) -> Result<MarksAns, FfiError> {
    let mut marks = req.marks;
    let t = req.thread;
    let (mut changed, mut refused, mut notice) = (false, None, None);
    match req.action {
        MarksAction::Pin | MarksAction::Unpin => {
            let pin = matches!(req.action, MarksAction::Pin);
            match marks.set_pinned(t, pin) {
                Ok(c) => {
                    changed = c;
                    notice = Some(thread_marks::pin_notice(pin));
                }
                Err(PinError::TooMany) => {
                    refused = Some("too_many_pins");
                    notice = Some(thread_marks::TOO_MANY_PINS);
                }
            }
        }
        MarksAction::Archive | MarksAction::Unarchive => {
            let archive = matches!(req.action, MarksAction::Archive);
            changed = marks.set_archived(t, archive);
            notice = Some(thread_marks::archive_notice(archive));
        }
        MarksAction::MarkUnread => {
            changed = marks.set_unread(t, true);
            notice = Some(thread_marks::unread_notice());
        }
        MarksAction::MarkRead => changed = marks.clear_unread(t),
        MarksAction::Forget => changed = marks.forget(t),
        MarksAction::Rekey => {
            let new = req
                .new_thread
                .ok_or_else(|| FfiError::invalid("rekey needs new_thread"))?;
            changed = marks.rekey(t, new);
        }
    }
    Ok(MarksAns {
        marks,
        changed,
        refused,
        notice,
    })
}

fn marks_of(pinned: &[i64], archived: &[i64], unread: &[i64]) -> Value {
    json!({"pinned": pinned, "archived": archived, "unread": unread})
}

fn marks_cases() -> Vec<Case> {
    let full = marks_of(&[1, 2, 3, 4, 5], &[], &[]);
    let some = marks_of(&[1], &[7], &[9]);
    vec![
        Case::new(json!({"marks": some, "action": "pin", "thread": 7})),
        Case::named(
            "too_many_pins",
            json!({"marks": full, "action": "pin", "thread": 8}),
        ),
        Case::named(
            "archive_unpins",
            json!({"marks": some, "action": "archive", "thread": 1}),
        ),
        Case::named(
            "mark_unread",
            json!({"marks": some, "action": "mark_unread", "thread": 2}),
        ),
        Case::named(
            "mark_read",
            json!({"marks": some, "action": "mark_read", "thread": 9}),
        ),
        Case::named(
            "forget",
            json!({"marks": some, "action": "forget", "thread": 7}),
        ),
        Case::named(
            "rekey",
            json!({"marks": some, "action": "rekey", "thread": 9, "new_thread": 12}),
        ),
        Case::named(
            "rekey_without_target",
            json!({"marks": some, "action": "rekey", "thread": 9}),
        ),
    ]
}

// ---- mute ----

#[derive(Deserialize)]
struct ChoicesReq {
    now_ms: i64,
}

#[derive(Serialize)]
struct Choice {
    /// `eight_hours`, `week` or `always`.
    key: &'static str,
    label: &'static str,
    /// When the mute ends; `null` means never.
    until_ms: Option<i64>,
}

#[derive(Serialize)]
struct ChoicesAns {
    choices: Vec<Choice>,
}

fn mute_choices(req: ChoicesReq) -> Result<ChoicesAns, FfiError> {
    Ok(ChoicesAns {
        choices: MuteFor::ALL
            .into_iter()
            .map(|m| Choice {
                key: match m {
                    MuteFor::EightHours => "eight_hours",
                    MuteFor::Week => "week",
                    MuteFor::Always => "always",
                },
                label: m.label(),
                until_ms: m.until_ms(req.now_ms),
            })
            .collect(),
    })
}

fn choices_cases() -> Vec<Case> {
    vec![Case::new(json!({"now_ms": NOW}))]
}

#[derive(Deserialize)]
struct StatusReq {
    /// When the mute ends; `null` is "always".
    #[serde(default)]
    until_ms: Option<i64>,
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct StatusAns {
    /// The pill: "Muted", "Muted until 18:05", "Muted until Sat 14 Nov 09:00".
    notice: String,
    /// The line under the chat title; `null` for "always" or a mute that has ended.
    header: Option<String>,
    active: bool,
}

fn mute_status(req: StatusReq) -> Result<StatusAns, FfiError> {
    Ok(StatusAns {
        notice: mute::muted_notice(req.until_ms, req.now_ms, req.tz_offset_s),
        header: mute::header_line(req.until_ms, req.now_ms, req.tz_offset_s),
        active: mute::is_active(req.until_ms, req.now_ms),
    })
}

fn status_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"until_ms": NOW + 3_600_000, "now_ms": NOW, "tz_offset_s": TZ})),
        Case::named(
            "another_day",
            json!({"until_ms": NOW + 3 * 86_400_000, "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named("always", json!({"now_ms": NOW, "tz_offset_s": TZ})),
        Case::named(
            "ended",
            json!({"until_ms": NOW - 1000, "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

// ---- starred ----

#[derive(Deserialize)]
struct CandidateDto {
    id: String,
    author: String,
    preview: String,
    at_ms: i64,
    starred: bool,
}

#[derive(Deserialize)]
struct RowsReq {
    items: Vec<CandidateDto>,
    /// Only part of the chat is loaded.
    #[serde(default)]
    partial: bool,
}

#[derive(Serialize)]
struct RowOut {
    id: String,
    author: String,
    /// One line, cut at 120 characters.
    preview: String,
    at_ms: i64,
}

impl From<StarredRow> for RowOut {
    fn from(r: StarredRow) -> Self {
        Self {
            id: r.id,
            author: r.author,
            preview: r.preview,
            at_ms: r.at_ms,
        }
    }
}

#[derive(Serialize)]
struct RowsAns {
    /// Starred messages only, newest first.
    rows: Vec<RowOut>,
    empty_text: &'static str,
}

fn starred_rows(req: RowsReq) -> Result<RowsAns, FfiError> {
    let items: Vec<StarCandidate<'_>> = req
        .items
        .iter()
        .map(|c| StarCandidate {
            id: &c.id,
            author: &c.author,
            preview: &c.preview,
            at_ms: c.at_ms,
            starred: c.starred,
        })
        .collect();
    Ok(RowsAns {
        rows: starred::starred_rows(&items)
            .into_iter()
            .map(RowOut::from)
            .collect(),
        empty_text: starred::empty_text(req.partial),
    })
}

fn rows_cases() -> Vec<Case> {
    let item = |id: &str, preview: &str, at: i64, starred: bool| json!({"id": id, "author": "Ayse", "preview": preview, "at_ms": at, "starred": starred});
    vec![
        Case::new(json!({"items": [
            item("a", "Eski\nmesaj", NOW - 5000, true),
            item("b", "Yıldızsız", NOW - 4000, false),
            item("c", "Yeni mesaj", NOW, true)]})),
        Case::named("empty", json!({"items": []})),
        Case::named("empty_partial", json!({"items": [], "partial": true})),
    ]
}

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum LocalAction {
    /// Just read: rows and counts of `thread_id`.
    Show,
    /// Stars `id` with `star`.
    Set,
    /// Unstars `id`.
    Unset,
    /// Drops every star of a deleted thread.
    ForgetThread,
}

#[derive(Deserialize)]
struct LocalReq {
    #[serde(default)]
    stars: LocalStars,
    action: LocalAction,
    #[serde(default)]
    id: Option<String>,
    #[serde(default)]
    star: Option<LocalStar>,
    #[serde(default)]
    thread_id: Option<String>,
    #[serde(default)]
    partial: bool,
}

#[derive(Serialize)]
struct LocalAns {
    stars: LocalStars,
    changed: bool,
    /// Whether `id` is starred afterwards.
    starred: bool,
    /// The rows of `thread_id`, newest first.
    rows: Vec<RowOut>,
    thread_count: usize,
    total: usize,
    empty_text: &'static str,
}

fn starred_local(req: LocalReq) -> Result<LocalAns, FfiError> {
    let mut stars = req.stars;
    let need_id = || {
        req.id
            .as_deref()
            .ok_or_else(|| FfiError::invalid("this action needs an id"))
    };
    let changed = match req.action {
        LocalAction::Show => false,
        LocalAction::Set => {
            let star = req
                .star
                .clone()
                .ok_or_else(|| FfiError::invalid("set needs a star"))?;
            stars.set(need_id()?, Some(star))
        }
        LocalAction::Unset => stars.set(need_id()?, None),
        LocalAction::ForgetThread => {
            let thread = req
                .thread_id
                .as_deref()
                .ok_or_else(|| FfiError::invalid("forget_thread needs a thread_id"))?;
            stars.forget_thread(thread) > 0
        }
    };
    let thread = req.thread_id.as_deref().unwrap_or_default();
    Ok(LocalAns {
        starred: req.id.as_deref().is_some_and(|id| stars.is_starred(id)),
        rows: stars
            .rows_for(thread)
            .into_iter()
            .map(RowOut::from)
            .collect(),
        thread_count: stars.count_for(thread),
        total: stars.len(),
        empty_text: starred::empty_text(req.partial),
        stars,
        changed,
    })
}

fn local_cases() -> Vec<Case> {
    let star = |thread: &str, at: i64, preview: &str| json!({"thread_id": thread, "at_ms": at, "author": "Me", "preview": preview});
    let stars = json!({"sms:1": star("7", NOW - 5000, "Birinci"), "sms:2": star("7", NOW, "İkinci"),
                       "sms:3": star("8", NOW, "Başka sohbet")});
    vec![
        Case::new(json!({"stars": stars, "action": "show", "thread_id": "7"})),
        Case::named(
            "set",
            json!({"stars": stars, "action": "set", "id": "sms:9", "thread_id": "7",
                   "star": star("7", NOW + 1, "Yeni\nyıldız")}),
        ),
        Case::named(
            "unset",
            json!({"stars": stars, "action": "unset", "id": "sms:1", "thread_id": "7"}),
        ),
        Case::named(
            "forget_thread",
            json!({"stars": stars, "action": "forget_thread", "thread_id": "7"}),
        ),
        Case::named("set_without_star", json!({"action": "set", "id": "sms:9"})),
    ]
}

// ---- quick replies ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum RepliesAction {
    /// The list as it is (the defaults when `list` is absent).
    Show,
    Add,
    Remove,
    /// Puts a removed reply back (Undo).
    Restore,
    /// Swaps the reply with the one above.
    MoveUp,
}

#[derive(Deserialize)]
struct RepliesReq {
    /// The stored list; the defaults of a fresh install when absent.
    #[serde(default)]
    list: Option<Vec<String>>,
    action: RepliesAction,
    #[serde(default)]
    text: String,
    #[serde(default)]
    index: usize,
}

#[derive(Serialize)]
struct RepliesAns {
    list: Vec<String>,
    /// The chip texts, one line each, cut at 28 characters.
    chips: Vec<String>,
    /// Where an added reply landed.
    index: Option<usize>,
    /// The reply a `remove` took out (for Undo).
    removed: Option<String>,
    changed: bool,
    max_replies: usize,
    max_chars: usize,
}

fn reason_of(e: ReplyError) -> &'static str {
    match e {
        ReplyError::Empty => "empty",
        ReplyError::TooLong => "too_long",
        ReplyError::Duplicate => "duplicate",
        ReplyError::Full => "full",
    }
}

fn replies_edit(req: RepliesReq) -> Result<RepliesAns, FfiError> {
    let mut list = req.list.unwrap_or_else(quick_replies::defaults);
    let before = list.clone();
    let (mut index, mut removed) = (None, None);
    match req.action {
        RepliesAction::Show => {}
        RepliesAction::Add => {
            index = Some(quick_replies::add(&mut list, &req.text).map_err(|e| {
                FfiError::invalid(e.to_string()).with_detail(json!({"reason": reason_of(e)}))
            })?);
        }
        RepliesAction::Remove => removed = quick_replies::remove(&mut list, req.index),
        RepliesAction::Restore => quick_replies::restore(&mut list, req.index, req.text),
        RepliesAction::MoveUp => {
            quick_replies::move_up(&mut list, req.index);
        }
    }
    Ok(RepliesAns {
        chips: list.iter().map(|r| quick_replies::chip_label(r)).collect(),
        changed: list != before,
        list,
        index,
        removed,
        max_replies: quick_replies::MAX_REPLIES,
        max_chars: quick_replies::MAX_REPLY_CHARS,
    })
}

fn replies_cases() -> Vec<Case> {
    let list = json!([
        "On my way.",
        "Thanks!",
        "Bu çok uzun bir hazır yanıt metnidir, çipte kısaltılır"
    ]);
    vec![
        Case::new(json!({"action": "show"})),
        Case::named(
            "add",
            json!({"list": list, "action": "add", "text": "  Tamamdır  "}),
        ),
        Case::named(
            "duplicate",
            json!({"list": list, "action": "add", "text": "THANKS!"}),
        ),
        Case::named(
            "empty",
            json!({"list": list, "action": "add", "text": "  "}),
        ),
        Case::named(
            "remove",
            json!({"list": list, "action": "remove", "index": 1}),
        ),
        Case::named(
            "restore",
            json!({"list": ["On my way."], "action": "restore", "index": 1, "text": "Thanks!"}),
        ),
        Case::named(
            "move_up",
            json!({"list": list, "action": "move_up", "index": 2}),
        ),
    ]
}

#[derive(Deserialize)]
struct InsertReq {
    draft: String,
    reply: String,
}

#[derive(Serialize)]
struct InsertAns {
    /// The draft after tapping the chip.
    draft: String,
}

fn replies_insert(req: InsertReq) -> Result<InsertAns, FfiError> {
    Ok(InsertAns {
        draft: quick_replies::insert_into(&req.draft, &req.reply),
    })
}

fn insert_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"draft": "Selam  ", "reply": "On my way."})),
        Case::named("blank_draft", json!({"draft": "  ", "reply": "Thanks!"})),
    ]
}

// ---- forward ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum ChannelDto {
    Sms,
    Whatsapp,
}

impl From<ChannelDto> for ForwardTo {
    fn from(c: ChannelDto) -> Self {
        match c {
            ChannelDto::Sms => Self::Sms,
            ChannelDto::Whatsapp => Self::WhatsApp,
        }
    }
}

#[derive(Deserialize, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
enum PartKind {
    Text,
    Picture,
    Nothing,
}

#[derive(Deserialize)]
struct PlanReq {
    /// `text` or `picture`.
    kind: PartKind,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    caption: Option<String>,
    to: ChannelDto,
}

#[derive(Serialize)]
struct PlanAns {
    /// `text`, `picture` or `nothing` (blank, or SMS text over 5 000 characters).
    kind: PartKind,
    text: Option<String>,
    caption: Option<String>,
    max_sms_chars: usize,
}

fn forward_plan(req: PlanReq) -> Result<PlanAns, FfiError> {
    let body = match req.kind {
        PartKind::Text => ForwardBody::Text(req.text.unwrap_or_default()),
        PartKind::Picture => ForwardBody::Picture {
            caption: req.caption,
        },
        PartKind::Nothing => return Err(FfiError::invalid("kind is text or picture")),
    };
    let (kind, text, caption) = match forward::plan(&body, req.to.into()) {
        ForwardPlan::Text(t) => (PartKind::Text, Some(t), None),
        ForwardPlan::Picture { caption } => (PartKind::Picture, None, caption),
        ForwardPlan::Nothing => (PartKind::Nothing, None, None),
    };
    Ok(PlanAns {
        kind,
        text,
        caption,
        max_sms_chars: forward::MAX_SMS_FORWARD_CHARS,
    })
}

fn plan_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"kind": "text", "text": "  Merhaba  ", "to": "sms"})),
        Case::named(
            "blank",
            json!({"kind": "text", "text": "   ", "to": "whatsapp"}),
        ),
        Case::named(
            "too_long_for_sms",
            json!({"kind": "text", "text": "x".repeat(5001), "to": "sms"}),
        ),
        Case::named(
            "picture",
            json!({"kind": "picture", "caption": "  Manzara ", "to": "whatsapp"}),
        ),
        Case::named(
            "picture_without_caption",
            json!({"kind": "picture", "caption": "  ", "to": "sms"}),
        ),
        Case::named("bad_kind", json!({"kind": "nothing", "to": "sms"})),
    ]
}

#[derive(Deserialize)]
struct WordsReq {
    title: String,
    to: ChannelDto,
    /// The person offers both channels in the chooser.
    #[serde(default)]
    both: bool,
    ok: bool,
}

#[derive(Serialize)]
struct WordsAns {
    /// "Ada" or "Ada · WhatsApp".
    target_label: String,
    /// "Forwarded to Ada" or "Couldn't forward to Ada".
    notice: String,
}

fn forward_words(req: WordsReq) -> Result<WordsAns, FfiError> {
    Ok(WordsAns {
        target_label: forward::target_label(&req.title, req.to.into(), req.both),
        notice: forward::notice(&req.title, req.ok),
    })
}

fn words_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"title": " Ayşe ", "to": "whatsapp", "both": true, "ok": true})),
        Case::named("failed", json!({"title": "Ayşe", "to": "sms", "ok": false})),
        Case::named("no_title", json!({"title": " ", "to": "sms", "ok": true})),
    ]
}
