//! `messages.*`: the preferences file, the drafts, the permission decisions and the conversation tags.

use super::{Empty, data_dir};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::messages::access::{self, Access, Ask, ResumePulls};
use rovsti_app_core::messages::drafts::{DRAFTS_FILE, Drafts};
use rovsti_app_core::messages::prefs::{MessagesPrefs, SpamMark};
use rovsti_app_core::messages::tags;
use rovsti_app_core::permission::Access as Perm;
use rovsti_app_core::storage;
use rovsti_core::spam::RuleError;
use rovsti_core::thread::ThreadId;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::BTreeMap;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "messages.prefs_load",
        DirReq,
        PrefsAns,
        prefs_load,
        prefs_load_cases
    ),
    op!(
        "messages.prefs_save",
        PrefsSaveReq,
        Empty,
        prefs_save,
        prefs_save_cases
    ),
    op!(
        "messages.prefs_edit",
        EditReq,
        EditAns,
        prefs_edit,
        edit_cases
    ),
    op!(
        "messages.drafts_load",
        DirReq,
        DraftsAns,
        drafts_load,
        drafts_load_cases
    ),
    op!(
        "messages.drafts_save",
        DraftsSaveReq,
        Empty,
        drafts_save,
        drafts_save_cases
    ),
    op!(
        "messages.drafts_put",
        DraftsPutReq,
        DraftsAns,
        drafts_put,
        drafts_put_cases
    ),
    op!(
        "messages.drafts_rekey",
        DraftsRekeyReq,
        DraftsAns,
        drafts_rekey,
        drafts_rekey_cases
    ),
    op!(
        "messages.access",
        AccessReq,
        AccessAns,
        access_decide,
        access_cases
    ),
    op!("messages.tag", TagReq, TagAns, tag, tag_cases),
];

#[derive(Deserialize)]
struct DirReq {
    #[serde(default)]
    dir: Option<String>,
}

// ---- prefs ----

#[derive(Serialize)]
struct PrefsAns {
    prefs: MessagesPrefs,
    /// Whether the settings already equal the defaults (the Reset row is disabled).
    is_reset: bool,
}

fn prefs_load(req: DirReq) -> Result<PrefsAns, FfiError> {
    let prefs = MessagesPrefs::load(&data_dir(&req.dir)?);
    Ok(PrefsAns {
        is_reset: prefs.is_reset(),
        prefs,
    })
}

fn prefs_load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("messages"),
        Case::named("missing", json!({"dir": "$DIR/nothing-here"})).seeded("messages"),
    ]
}

#[derive(Deserialize)]
struct PrefsSaveReq {
    #[serde(default)]
    dir: Option<String>,
    prefs: MessagesPrefs,
}

fn prefs_save(req: PrefsSaveReq) -> Result<Empty, FfiError> {
    req.prefs.save(&data_dir(&req.dir)?)?;
    Ok(Empty {})
}

fn prefs_save_cases() -> Vec<Case> {
    vec![Case::new(json!({"dir": "$DIR", "prefs": MessagesPrefs::default()})).seeded("messages")]
}

/// One change to the preferences: `edit` names it, the other fields are its arguments.
#[derive(Deserialize)]
struct EditReq {
    prefs: MessagesPrefs,
    /// `mute`, `unmute`, `expire_mutes`, `mark_spam`, `unmark_spam`, `allow_sender`, `unallow_sender`,
    /// `remove_allowed`, `rekey` or `reset`.
    edit: String,
    /// A notification tag (`sms:7`, `wa:<jid>`): `mute`, `unmute`.
    #[serde(default)]
    tag: String,
    /// `mute`: when it ends; absent mutes until the user unmutes.
    #[serde(default)]
    until_ms: Option<i64>,
    /// `expire_mutes`.
    #[serde(default)]
    now_ms: i64,
    /// `mark_spam`, `unmark_spam`, `allow_sender`, `unallow_sender`.
    #[serde(default)]
    sender: String,
    /// `unmark_spam`: what `mark_spam` answered.
    #[serde(default)]
    mark: Option<MarkDto>,
    /// `remove_allowed`.
    #[serde(default)]
    index: usize,
    /// `rekey`: the tag of the merged chat's old and new thread.
    #[serde(default)]
    from: String,
    #[serde(default)]
    into: String,
}

#[derive(Serialize, Deserialize, Clone, Copy)]
struct MarkDto {
    rule_added: bool,
    was_allowed: bool,
}

#[derive(Serialize)]
struct EditAns {
    prefs: MessagesPrefs,
    /// Whether the edit changed anything.
    changed: bool,
    /// `mark_spam`: hand it back to `unmark_spam` for Undo.
    mark: Option<MarkDto>,
}

fn rule_error(error: RuleError) -> FfiError {
    FfiError::invalid(error.to_string())
}

fn prefs_edit(mut req: EditReq) -> Result<EditAns, FfiError> {
    let mut mark = None;
    let changed = match req.edit.as_str() {
        "mute" => {
            req.prefs.set_muted_until(&req.tag, req.until_ms);
            true
        }
        "unmute" => {
            let was = req.prefs.is_muted(&req.tag);
            req.prefs.set_muted(&req.tag, false);
            was
        }
        "expire_mutes" => req.prefs.expire_mutes(req.now_ms),
        "mark_spam" => {
            let done = req.prefs.mark_spam(&req.sender).map_err(rule_error)?;
            mark = Some(MarkDto {
                rule_added: done.rule_added,
                was_allowed: done.was_allowed,
            });
            done.rule_added || done.was_allowed
        }
        "unmark_spam" => {
            let given = req
                .mark
                .ok_or_else(|| FfiError::invalid("unmark_spam needs the mark of mark_spam"))?;
            req.prefs.unmark_spam(
                &req.sender,
                SpamMark {
                    rule_added: given.rule_added,
                    was_allowed: given.was_allowed,
                },
            );
            given.rule_added || given.was_allowed
        }
        "allow_sender" => req.prefs.allow_sender(&req.sender),
        "unallow_sender" => req.prefs.unallow_sender(&req.sender),
        "remove_allowed" => req.prefs.remove_allowed(req.index),
        "rekey" => req.prefs.rekey_thread(&req.from, &req.into),
        "reset" => {
            let changed = !req.prefs.is_reset();
            req.prefs = req.prefs.reset();
            changed
        }
        other => return Err(FfiError::invalid(format!("unknown edit {other:?}"))),
    };
    Ok(EditAns {
        prefs: req.prefs,
        changed,
        mark,
    })
}

fn edit_cases() -> Vec<Case> {
    let prefs = || json!(MessagesPrefs::default());
    vec![
        Case::new(
            json!({"prefs": prefs(), "edit": "mute", "tag": "sms:7", "until_ms": 1_790_003_600_000_i64}),
        ),
        Case::named(
            "unmute",
            json!({"prefs": prefs(), "edit": "unmute", "tag": "sms:7"}),
        ),
        Case::named(
            "expire",
            json!({"prefs": prefs(), "edit": "expire_mutes", "now_ms": 1_790_000_000_000_i64}),
        ),
        Case::named(
            "mark_spam",
            json!({"prefs": prefs(), "edit": "mark_spam", "sender": " PROMO-SHOP "}),
        ),
        Case::named(
            "unmark_spam",
            json!({"prefs": prefs(), "edit": "unmark_spam", "sender": "PROMO-SHOP",
                   "mark": {"rule_added": true, "was_allowed": false}}),
        ),
        Case::named(
            "allow",
            json!({"prefs": prefs(), "edit": "allow_sender", "sender": "BANK"}),
        ),
        Case::named("reset", json!({"prefs": prefs(), "edit": "reset"})),
        Case::named("bad_edit", json!({"prefs": prefs(), "edit": "explode"})),
        Case::named(
            "empty_sender",
            json!({"prefs": prefs(), "edit": "mark_spam", "sender": "  "}),
        ),
    ]
}

// ---- drafts ----

#[derive(Serialize)]
struct DraftsAns {
    /// Conversation tag (`sms:7`, `wa:<jid>`) to the unsent text.
    drafts: BTreeMap<String, String>,
}

fn drafts_load(req: DirReq) -> Result<DraftsAns, FfiError> {
    let map = storage::load_json::<BTreeMap<String, String>>(&data_dir(&req.dir)?, DRAFTS_FILE)
        .unwrap_or_default();
    // Round-trips through Drafts so bad tags and blank texts are dropped.
    Ok(DraftsAns {
        drafts: Drafts::from_map(&map).to_map(),
    })
}

fn drafts_load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("messages"),
        Case::named("missing", json!({"dir": "$DIR/nothing-here"})).seeded("messages"),
    ]
}

#[derive(Deserialize)]
struct DraftsSaveReq {
    #[serde(default)]
    dir: Option<String>,
    drafts: BTreeMap<String, String>,
}

fn drafts_save(req: DraftsSaveReq) -> Result<Empty, FfiError> {
    let clean = Drafts::from_map(&req.drafts).to_map();
    storage::save_json(&data_dir(&req.dir)?, DRAFTS_FILE, &clean)?;
    Ok(Empty {})
}

fn drafts_save_cases() -> Vec<Case> {
    vec![Case::new(json!({"dir": "$DIR", "drafts": {"sms:7": "On my way", "bad": "x", "wa:1@s.whatsapp.net": "  "}}))
        .seeded("messages")]
}

#[derive(Deserialize)]
struct DraftsPutReq {
    #[serde(default)]
    drafts: BTreeMap<String, String>,
    /// The conversation tag.
    thread: String,
    text: String,
}

fn thread_of(tag: &str) -> Result<ThreadId, FfiError> {
    tags::parse_tag(tag)
        .ok_or_else(|| FfiError::invalid(format!("{tag:?} is not a conversation tag")))
}

fn drafts_put(req: DraftsPutReq) -> Result<DraftsAns, FfiError> {
    let mut drafts = Drafts::from_map(&req.drafts);
    drafts.put(thread_of(&req.thread)?, &req.text);
    Ok(DraftsAns {
        drafts: drafts.to_map(),
    })
}

fn drafts_put_cases() -> Vec<Case> {
    let drafts = json!({"sms:7": "On my way"});
    vec![
        Case::new(json!({"drafts": drafts, "thread": "wa:90532@s.whatsapp.net", "text": "Hi"})),
        Case::named(
            "blank_removes",
            json!({"drafts": drafts, "thread": "sms:7", "text": "  "}),
        ),
        Case::named(
            "new_thread_keeps_none",
            json!({"drafts": drafts, "thread": "sms:-1", "text": "x"}),
        ),
        Case::named("bad_tag", json!({"thread": "nope", "text": "x"})),
    ]
}

#[derive(Deserialize)]
struct DraftsRekeyReq {
    #[serde(default)]
    drafts: BTreeMap<String, String>,
    from: String,
    into: String,
}

fn drafts_rekey(req: DraftsRekeyReq) -> Result<DraftsAns, FfiError> {
    let mut drafts = Drafts::from_map(&req.drafts);
    drafts.rekey(&thread_of(&req.from)?, &thread_of(&req.into)?);
    Ok(DraftsAns {
        drafts: drafts.to_map(),
    })
}

fn drafts_rekey_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"drafts": {"sms:7": "On my way", "sms:8": ""}, "from": "sms:7", "into": "wa:1@s.whatsapp.net"}),
    )]
}

// ---- access ----

#[derive(Deserialize)]
struct AccessReq {
    /// The state of the permission to decide about (read SMS, notifications, contacts, ...).
    #[serde(default)]
    state: Perm,
    /// The app's own notifications switch (the banner shows only when it is on).
    #[serde(default)]
    notifications_on: bool,
    /// The notifications permission; absent where Android cannot tell (desktop).
    #[serde(default)]
    notify_state: Option<Perm>,
    /// The state of the thread list.
    #[serde(default = "loading")]
    threads: Access,
    #[serde(default)]
    contacts_denied: bool,
}

fn loading() -> Access {
    Access::Loading
}

#[derive(Serialize)]
struct AccessAns {
    /// How to ask for `state`; `None` when granted.
    ask: Option<Ask>,
    notify_banner: Option<&'static str>,
    contacts_banner: &'static str,
    /// What to pull again when the app returns to the foreground.
    resume: ResumePulls,
}

fn access_decide(req: AccessReq) -> Result<AccessAns, FfiError> {
    Ok(AccessAns {
        ask: access::ask_for(req.state),
        notify_banner: access::notify_banner(req.notifications_on, req.notify_state),
        contacts_banner: access::CONTACTS_OFF,
        resume: access::resume_pulls(req.threads, req.contacts_denied),
    })
}

fn access_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"state": "permanently_denied", "notifications_on": true, "notify_state": "denied",
                         "threads": "denied", "contacts_denied": true}),
        ),
        Case::named(
            "granted",
            json!({"state": "granted", "notifications_on": true, "notify_state": "granted", "threads": "ready"}),
        ),
        Case::named("defaults", json!({})),
    ]
}

// ---- tags ----

#[derive(Deserialize)]
struct TagReq {
    /// A thread to tag.
    #[serde(default)]
    thread: Option<ThreadId>,
    /// A tag to read back.
    #[serde(default)]
    tag: Option<String>,
}

#[derive(Serialize)]
struct TagAns {
    tag: Option<String>,
    thread: Option<ThreadId>,
}

fn tag(req: TagReq) -> Result<TagAns, FfiError> {
    let thread = req
        .thread
        .or_else(|| req.tag.as_deref().and_then(tags::parse_tag));
    Ok(TagAns {
        tag: thread.as_ref().map(tags::thread_tag),
        thread,
    })
}

fn tag_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"thread": {"Sms": 7}})),
        Case::named("parse", json!({"tag": "wa:905321234567@s.whatsapp.net"})),
        Case::named("bad", json!({"tag": "mms:7"})),
    ]
}
