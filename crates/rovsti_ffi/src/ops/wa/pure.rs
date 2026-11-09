//! The `wa.*` ops that need no gateway: the push payload, the stored link, the avatar disk policy, display names
//! and the contact target.

use super::Empty;
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use crate::state;
use rovsti_app_core::contacts::whatsapp::lookup_jids;
use rovsti_app_core::wa_link::avatars::{self, DiskFile};
use rovsti_app_core::wa_link::link::StoredLink;
use rovsti_app_core::wa_link::names;
use rovsti_wa_proto::{ConnectKey, GatewayUrl, WaTarget, Wake, parse_wake, wa_target};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::{Path, PathBuf};
use std::time::{Duration, UNIX_EPOCH};

pub(super) const OPS: &[OpSpec] = &[
    op!("wa.push_parse", PushReq, PushAns, push_parse, push_cases),
    op!("wa.link_read", DirReq, LinkReadAns, link_read, read_cases),
    op!("wa.link_write", WriteReq, Empty, link_write, write_cases),
    op!(
        "wa.avatars_plan",
        PlanReq,
        PlanAns,
        avatars_plan,
        plan_cases
    ),
    op!("wa.names", NamesReq, NamesAns, names_op, names_cases),
    op!("wa.target", TargetReq, TargetAns, target, target_cases),
];

// ---- wa.push_parse

#[derive(Deserialize)]
struct PushReq {
    json: String,
}

/// The decoded wake-up, `kind` naming the variant.
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum WakeDto {
    Call {
        call_id: String,
        from: Option<String>,
        name: Option<String>,
    },
    Message {
        chat: String,
    },
    CallEnd {
        call_id: String,
        missed: bool,
    },
}

#[derive(Serialize)]
struct PushAns {
    /// `null` for an unknown kind or malformed JSON.
    wake: Option<WakeDto>,
}

fn push_parse(req: PushReq) -> Result<PushAns, FfiError> {
    let wake = parse_wake(&req.json).map(|wake| match wake {
        Wake::Call {
            call_id,
            from,
            name,
        } => WakeDto::Call {
            call_id,
            from,
            name,
        },
        Wake::Message { chat } => WakeDto::Message { chat },
        Wake::CallEnd { call_id, missed } => WakeDto::CallEnd { call_id, missed },
    });
    Ok(PushAns { wake })
}

fn push_cases() -> Vec<Case> {
    let case = |label: &'static str, text: &str| {
        let request = json!({ "json": text });
        if label.is_empty() {
            Case::new(request)
        } else {
            Case::named(label, request)
        }
    };
    vec![
        case(
            "",
            r#"{"kind":"call","call_id":"abc","from":"905321234567@s.whatsapp.net","name":"Ada"}"#,
        ),
        case(
            "message",
            r#"{"kind":"message","chat":"905321234567@s.whatsapp.net"}"#,
        ),
        case(
            "call_end",
            r#"{"kind":"call_end","call_id":"abc","missed":"1"}"#,
        ),
        case("unknown", r#"{"kind":"banner"}"#),
        case("garbage", "not json"),
    ]
}

// ---- wa.link_read / wa.link_write

#[derive(Deserialize)]
struct DirReq {
    #[serde(default)]
    dir: Option<String>,
}

#[derive(Serialize)]
struct LinkReadAns {
    /// `null` when the app is not linked (or the file no longer parses).
    link: Option<StoredLink>,
}

#[derive(Deserialize)]
struct WriteReq {
    #[serde(default)]
    dir: Option<String>,
    /// `null` removes the link.
    link: Option<StoredLink>,
}

fn data_dir(dir: Option<&str>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(PathBuf::from(dir)),
        None => state::dirs()
            .map(|dirs| dirs.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("core.init has not been called")),
    }
}

fn link_read(req: DirReq) -> Result<LinkReadAns, FfiError> {
    let dir = data_dir(req.dir.as_deref())?;
    Ok(LinkReadAns {
        link: StoredLink::load(&dir),
    })
}

fn link_write(req: WriteReq) -> Result<Empty, FfiError> {
    let dir = data_dir(req.dir.as_deref())?;
    match req.link {
        Some(link) => {
            // The connect key is a secret that must never be saved half-typed.
            GatewayUrl::parse(&link.gateway_url).map_err(|e| FfiError::invalid(e.to_string()))?;
            ConnectKey::parse(&link.key).map_err(|e| FfiError::invalid(e.to_string()))?;
            if link.instance_id.trim().is_empty() {
                return Err(FfiError::invalid("the link needs an instance id"));
            }
            std::fs::create_dir_all(&dir)?;
            link.save(&dir)?;
        }
        None => StoredLink::remove(&dir)?,
    }
    Ok(Empty {})
}

fn read_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("wa"),
        Case::named("none", json!({"dir": "$DIR"})).seeded("phone"),
    ]
}

fn write_cases() -> Vec<Case> {
    let link =
        |url: &str, key: &str| json!({"gateway_url": url, "instance_id": "inst1", "key": key});
    vec![
        Case::new(
            json!({"dir": "$DIR", "link": link("https://wa.example.com", super::fixture::KEY)}),
        )
        .seeded("phone"),
        Case::named("remove", json!({"dir": "$DIR", "link": null})).seeded("wa"),
        Case::named(
            "bad_key",
            json!({"dir": "$DIR", "link": link("https://wa.example.com", "rwk_short")}),
        )
        .seeded("phone"),
        Case::named(
            "bad_url",
            json!({"dir": "$DIR", "link": link("wa.example.com", super::fixture::KEY)}),
        )
        .seeded("phone"),
    ]
}

// ---- wa.avatars_plan

#[derive(Deserialize)]
struct FileDto {
    path: String,
    bytes: u64,
    modified_ms: u64,
}

#[derive(Deserialize)]
struct PlanReq {
    /// The picture files on disk, with their size and age.
    #[serde(default)]
    files: Vec<FileDto>,
    /// The disk budget; the oldest files above it are listed in `remove`.
    max_bytes: u64,
    /// With `jid` and `picture_id`: the file name that picture is stored under.
    #[serde(default)]
    jid: Option<String>,
    #[serde(default)]
    picture_id: Option<String>,
}

#[derive(Serialize)]
struct PlanAns {
    remove: Vec<String>,
    file_name: Option<String>,
    /// The picture id a file name holds (for the first file whose name parses for `jid`).
    held_id: Option<String>,
}

fn avatars_plan(req: PlanReq) -> Result<PlanAns, FfiError> {
    let files: Vec<DiskFile> = req
        .files
        .iter()
        .map(|file| DiskFile {
            path: PathBuf::from(&file.path),
            bytes: file.bytes,
            modified: UNIX_EPOCH + Duration::from_millis(file.modified_ms),
        })
        .collect();
    let remove = avatars::prune_plan(files, req.max_bytes)
        .into_iter()
        .map(|path| path.to_string_lossy().into_owned())
        .collect();
    let file_name = match (&req.jid, &req.picture_id) {
        (Some(jid), Some(id)) => Some(avatars::file_name(jid, id)),
        _ => None,
    };
    let held_id = req.jid.as_deref().and_then(|jid| {
        req.files.iter().find_map(|file| {
            let name = Path::new(&file.path).file_name()?.to_str()?;
            avatars::id_of_file(jid, name)
        })
    });
    Ok(PlanAns {
        remove,
        file_name,
        held_id,
    })
}

fn plan_cases() -> Vec<Case> {
    let jid = "905321234567@s.whatsapp.net";
    let file = |name: &str, bytes: u64, modified_ms: u64| json!({"path": format!("/cache/avatars/{name}"), "bytes": bytes, "modified_ms": modified_ms});
    let old = avatars::file_name(jid, "55");
    let new = avatars::file_name("1-2@g.us", "9");
    vec![
        Case::new(json!({
            "max_bytes": 1500,
            "files": [file(&old, 1000, 1_000), file(&new, 1000, 2_000)],
            "jid": jid, "picture_id": "56",
        })),
        Case::named(
            "within_budget",
            json!({"max_bytes": 10_000, "files": [file(&old, 1000, 1_000)]}),
        ),
    ]
}

// ---- wa.names

#[derive(Deserialize)]
struct NamesReq {
    #[serde(default)]
    contact: Option<String>,
    #[serde(default)]
    wa_name: Option<String>,
    jid: String,
}

#[derive(Serialize)]
struct NamesAns {
    /// The contact name, else WhatsApp's name, else the number.
    name: String,
    /// `+905321234567` for a phone JID, "WhatsApp user" for a LID, the group id for a group.
    number: String,
}

fn names_op(req: NamesReq) -> Result<NamesAns, FfiError> {
    Ok(NamesAns {
        name: names::display_name(req.contact.as_deref(), req.wa_name.as_deref(), &req.jid),
        number: names::number_label(&req.jid),
    })
}

fn names_cases() -> Vec<Case> {
    let jid = "905321234567@s.whatsapp.net";
    vec![
        Case::new(json!({"contact": "Ayse Yilmaz", "wa_name": "ayse", "jid": jid})),
        Case::named("whatsapp_name", json!({"wa_name": "ayse", "jid": jid})),
        Case::named("number", json!({"jid": jid})),
        Case::named("lid", json!({"jid": "96413459456093@lid"})),
    ]
}

// ---- wa.target

#[derive(Deserialize)]
struct TargetReq {
    /// The contact's dialable numbers.
    numbers: Vec<String>,
    /// User parts of the 1:1 chats of the instance.
    #[serde(default)]
    chat_users: Vec<String>,
}

#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum TargetDto {
    Chat { jid: String },
    Number { jid: String },
    NeedsCountryCode,
    None,
}

#[derive(Serialize)]
struct TargetAns {
    target: TargetDto,
    /// The JIDs to ask the gateway about (only for a bare-number target, at most 12).
    lookup_jids: Vec<String>,
}

fn target(req: TargetReq) -> Result<TargetAns, FfiError> {
    let numbers: Vec<&str> = req.numbers.iter().map(String::as_str).collect();
    let users: Vec<&str> = req.chat_users.iter().map(String::as_str).collect();
    let found = wa_target(&numbers, &users);
    let lookup = lookup_jids(&found, &numbers);
    let target = match found {
        WaTarget::Chat(jid) => TargetDto::Chat { jid },
        WaTarget::Number(jid) => TargetDto::Number { jid },
        WaTarget::NeedsCountryCode => TargetDto::NeedsCountryCode,
        WaTarget::None => TargetDto::None,
    };
    Ok(TargetAns {
        target,
        lookup_jids: lookup,
    })
}

fn target_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"numbers": ["+90 532 123 45 67"], "chat_users": ["905321234567"]})),
        Case::named(
            "number",
            json!({"numbers": ["+90 532 123 45 67", "+49 151 2345 6789"], "chat_users": []}),
        ),
        Case::named(
            "local_only",
            json!({"numbers": ["0532 123 45 67"], "chat_users": []}),
        ),
        Case::named("no_number", json!({"numbers": [], "chat_users": []})),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_stored_picture_file_name_round_trips_to_its_id() {
        let jid = "905321234567@s.whatsapp.net";
        let ans = avatars_plan(PlanReq {
            files: vec![FileDto {
                path: format!("/c/{}", avatars::file_name(jid, "77")),
                bytes: 1,
                modified_ms: 5,
            }],
            max_bytes: 10,
            jid: Some(jid.into()),
            picture_id: Some("77".into()),
        })
        .unwrap();
        assert_eq!(ans.held_id.as_deref(), Some("77"));
        assert_eq!(ans.file_name, Some(avatars::file_name(jid, "77")));
        assert!(ans.remove.is_empty());
    }

    #[test]
    fn the_oldest_files_go_first_over_budget() {
        let files = (0..3)
            .map(|n| FileDto {
                path: format!("/c/{n}.img"),
                bytes: 100,
                modified_ms: 10 * (n + 1),
            })
            .collect();
        let ans = avatars_plan(PlanReq {
            files,
            max_bytes: 150,
            jid: None,
            picture_id: None,
        })
        .unwrap();
        assert_eq!(
            ans.remove,
            vec!["/c/0.img".to_owned(), "/c/1.img".to_owned()]
        );
    }
}
