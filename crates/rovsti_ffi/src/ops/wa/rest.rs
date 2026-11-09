//! One op per `Gateway` method. Requests are flat: `{handle, ...the fields of the proto request}`.

use super::fixture::{CHAT, case, named};
use super::{Call, Empty, INLINE_LIMIT, Nothing, done, open, open_cases};
use crate::envelope::FfiError;
use crate::ops::registry::{Case, OpSpec};
use base64::{Engine, engine::general_purpose::STANDARD};
use rovsti_wa_client::Picture;
use rovsti_wa_proto::{
    CallStarted, CallSummary, ChatAction, ChatState, ChatSummary, CreateInstance, CreatedInstance,
    DeleteForMe, EditMessage, GroupInfo, InstanceStatus, IssuedKey, KeyInfo, LinkRequest, MarkRead,
    MediaKind, MediaOptions, PushRegistration, RevokeMessage, RichChat, RichMessage, ScheduleText,
    ScheduledText, SendReaction, SendText, Sent, StarMessage, StatusList, WaMessage, WaProfile,
    WaUser,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::Path;

pub(super) const OPS: &[OpSpec] = &[
    wa_op!(
        "wa.open",
        super::OpenReq,
        super::HandleAns,
        open,
        open_cases
    ),
    wa_op!(
        "wa.create_instance",
        Call<CreateInstance>,
        CreatedInstance,
        create_instance,
        || vec![
            case(json!({"link": {"type": "qr"}, "app": "messages"})),
            named(
                "limit",
                json!({"link": {"type": "phone_number", "number": "+905321234567"}, "app": "phone"})
            ),
        ]
    ),
    wa_op!("wa.status", Call<Nothing>, InstanceStatus, status, || vec![
        case(json!({})),
        named("unauthorized", json!({})),
        named("forbidden", json!({})),
        named("api_error", json!({})),
        named("garbage", json!({})),
        named("network", json!({})),
    ]),
    wa_op!("wa.relink", Call<LinkRequest>, Empty, relink, || vec![
        case(json!({"type": "phone_number", "number": "+905321234567"})),
        named("qr", json!({"type": "qr"})),
    ]),
    wa_op!(
        "wa.delete_instance",
        Call<Nothing>,
        Empty,
        delete_instance,
        || vec![case(json!({}))]
    ),
    wa_op!("wa.mint_key", Call<Label>, IssuedKey, mint_key, || vec![
        case(json!({"label": "Tablet"}))
    ]),
    wa_op!("wa.keys", Call<Nothing>, Vec<KeyInfo>, keys, || vec![case(
        json!({})
    )]),
    wa_op!("wa.revoke_key", Call<KeyRef>, Empty, revoke_key, || vec![
        case(json!({"key_id": "k2"}))
    ]),
    wa_op!(
        "wa.register_push",
        Call<PushRegistration>,
        Empty,
        register_push,
        || vec![case(json!({"app": "phone", "token": "fcm-token-1"}))]
    ),
    wa_op!("wa.chats", Call<Nothing>, Vec<ChatSummary>, chats, || vec![
        case(json!({}))
    ]),
    wa_op!(
        "wa.chats_with_meta",
        Call<Nothing>,
        Vec<RichChat>,
        chats_with_meta,
        || vec![case(json!({}))]
    ),
    wa_op!(
        "wa.messages",
        Call<Page>,
        Vec<WaMessage>,
        messages,
        || vec![case(
            json!({"chat": CHAT, "before_ms": 1_790_000_000_000_i64, "limit": 20})
        )]
    ),
    wa_op!(
        "wa.messages_with_meta",
        Call<Page>,
        Vec<RichMessage>,
        messages_with_meta,
        || vec![case(json!({"chat": CHAT}))]
    ),
    wa_op!("wa.send_text", Call<SendText>, Sent, send_text, || vec![
        case(json!({"chat": CHAT, "text": "Merhaba", "quoted_id": null})),
        named(
            "reply",
            json!({"chat": CHAT, "text": "Tamam", "quoted_id": "3EB0A1"})
        ),
    ]),
    wa_op!(
        "wa.schedule_text",
        Call<ScheduleText>,
        ScheduledText,
        schedule_text,
        || vec![case(
            json!({"chat": CHAT, "text": "Dogum gunun kutlu olsun", "send_at_ms": 1_790_086_400_000_i64})
        )]
    ),
    wa_op!(
        "wa.scheduled",
        Call<Nothing>,
        Vec<ScheduledText>,
        scheduled,
        || vec![case(json!({}))]
    ),
    wa_op!(
        "wa.cancel_scheduled",
        Call<IdRef>,
        Empty,
        cancel_scheduled,
        || vec![
            case(json!({"id": "s1"})),
            named("gone", json!({"id": "s9"}))
        ]
    ),
    wa_op!("wa.react", Call<SendReaction>, Empty, react, || vec![case(
        json!({"chat": CHAT, "message_id": "3EB0A1", "from_me": false, "sender": null, "emoji": "👍"})
    )]),
    wa_op!("wa.mark_read", Call<MarkRead>, Empty, mark_read, || vec![
        case(json!({"chat": CHAT, "sender": null, "ids": ["3EB0A1", "3EB0A2"]}))
    ]),
    wa_op!(
        "wa.mark_played",
        Call<MarkRead>,
        Empty,
        mark_played,
        || vec![case(
            json!({"chat": CHAT, "sender": null, "ids": ["3EB0A3"]})
        )]
    ),
    wa_op!(
        "wa.set_presence",
        Call<Presence>,
        Empty,
        set_presence,
        || vec![case(json!({"available": true}))]
    ),
    wa_op!(
        "wa.subscribe_presence",
        Call<JidRef>,
        Empty,
        subscribe_presence,
        || vec![case(json!({"jid": CHAT}))]
    ),
    wa_op!(
        "wa.chat_state",
        Call<ChatState>,
        Empty,
        chat_state,
        || vec![case(json!({"chat": CHAT, "state": "typing"}))]
    ),
    wa_op!("wa.groups", Call<Nothing>, Vec<GroupInfo>, groups, || vec![
        case(json!({}))
    ]),
    wa_op!("wa.group", Call<JidRef>, GroupInfo, group, || vec![case(
        json!({"jid": "120363041234567890@g.us"})
    )]),
    wa_op!(
        "wa.lookup_users",
        Call<Jids>,
        Vec<WaUser>,
        lookup_users,
        || vec![case(json!({"jids": [CHAT, "905550000000@s.whatsapp.net"]}))]
    ),
    wa_op!(
        "wa.profiles",
        Call<Jids>,
        Vec<WaProfile>,
        profiles,
        || vec![case(json!({"jids": [CHAT]}))]
    ),
    wa_op!(
        "wa.refresh_profiles",
        Call<Jids>,
        Vec<WaProfile>,
        refresh_profiles,
        || vec![case(json!({"jids": [CHAT]}))]
    ),
    wa_op!("wa.statuses", Call<Nothing>, StatusList, statuses, || vec![
        case(json!({}))
    ]),
    wa_op!(
        "wa.mark_status_viewed",
        Call<Ids>,
        Empty,
        mark_status_viewed,
        || vec![case(json!({"ids": ["st1", "st2"]}))]
    ),
    wa_op!(
        "wa.edit_message",
        Call<EditMessage>,
        Empty,
        edit_message,
        || vec![case(
            json!({"chat": CHAT, "message_id": "3EB0A1", "text": "Merhaba!"})
        )]
    ),
    wa_op!(
        "wa.revoke_message",
        Call<RevokeMessage>,
        Empty,
        revoke_message,
        || vec![case(
            json!({"chat": CHAT, "message_id": "3EB0A1", "sender": null})
        )]
    ),
    wa_op!(
        "wa.delete_for_me",
        Call<DeleteForMe>,
        Empty,
        delete_for_me,
        || vec![case(json!({"chat": CHAT, "message_id": "3EB0A1"}))]
    ),
    wa_op!(
        "wa.star_message",
        Call<StarMessage>,
        Empty,
        star_message,
        || vec![case(
            json!({"chat": CHAT, "message_id": "3EB0A1", "starred": true})
        )]
    ),
    wa_op!(
        "wa.chat_action",
        Call<ChatAction>,
        Empty,
        chat_action,
        || vec![
            case(json!({"chat": CHAT, "action": {"type": "pin", "pinned": true}})),
            named(
                "mute",
                json!({"chat": CHAT, "action": {"type": "mute", "until_ms": 1_790_086_400_000_i64}})
            ),
        ]
    ),
    wa_op!(
        "wa.place_call",
        Call<PlaceTo>,
        CallStarted,
        place_call,
        || {
            vec![
                case(json!({"to": CHAT})),
                named("offline", json!({"to": CHAT})),
            ]
        }
    ),
    wa_op!("wa.calls", Call<Nothing>, Vec<CallSummary>, calls, || vec![
        case(json!({}))
    ]),
    wa_op!(
        "wa.accept_call",
        Call<CallRef>,
        Empty,
        accept_call,
        || vec![case(json!({"call_id": "call1"}))]
    ),
    wa_op!(
        "wa.reject_call",
        Call<CallRef>,
        Empty,
        reject_call,
        || vec![case(json!({"call_id": "call1"}))]
    ),
    wa_op!("wa.end_call", Call<CallRef>, Empty, end_call, || vec![
        case(json!({"call_id": "call1"}))
    ]),
    wa_op!("wa.set_muted", Call<CallMute>, Empty, set_muted, || vec![
        case(json!({"call_id": "call1", "muted": true}))
    ]),
];

/// The ops whose request or answer carries bytes.
pub(super) const MEDIA_OPS: &[OpSpec] = &[
    wa_op!("wa.send_media", Call<SendMedia>, Sent, send_media, || vec![
        case(
            json!({"chat": CHAT, "kind": "image", "mime": "image/jpeg", "caption": "Bak",
                        "data_b64": "SU1H"})
        ),
        named(
            "both",
            json!({"chat": CHAT, "kind": "image", "mime": "image/jpeg",
                       "data_b64": "SU1H", "path": "/tmp/x"})
        ),
        named(
            "bad_base64",
            json!({"chat": CHAT, "kind": "image", "mime": "image/jpeg", "data_b64": "***"})
        ),
        named(
            "empty",
            json!({"chat": CHAT, "kind": "image", "mime": "image/jpeg"})
        ),
    ]),
    wa_op!(
        "wa.send_media_with",
        Call<SendMediaWith>,
        Sent,
        send_media_with,
        || vec![case(
            json!({"chat": CHAT, "kind": "audio", "mime": "audio/ogg",
                   "data_b64": "T0dH",
                   "options": {"quoted_id": "3EB0A1", "file_name": null, "seconds": 7, "voice_note": true}})
        )]
    ),
    wa_op!("wa.media", Call<MessageRef>, BytesAns, media, || vec![
        case(json!({"message_id": "3EB0A1"})),
        large_case(),
    ]),
    wa_op!(
        "wa.thumbnail",
        Call<MessageRef>,
        BytesAns,
        thumbnail,
        || vec![case(json!({"message_id": "3EB0A1"}))]
    ),
    wa_op!(
        "wa.status_media",
        Call<IdRef>,
        BytesAns,
        status_media,
        || vec![case(json!({"id": "st1"}))]
    ),
    wa_op!(
        "wa.status_thumbnail",
        Call<IdRef>,
        BytesAns,
        status_thumbnail,
        || vec![case(json!({"id": "st1"}))]
    ),
    wa_op!(
        "wa.picture",
        Call<PictureReq>,
        PictureAns,
        picture,
        || vec![
            case(json!({"jid": CHAT, "full": true})),
            named("unchanged", json!({"jid": CHAT, "known_id": "55"})),
            named("none", json!({"jid": "120363041234567890@g.us"})),
            named("rate_limited", json!({"jid": CHAT})),
        ]
    ),
];

/// A download over [`INLINE_LIMIT`] ends up as a file in the handle's cache directory.
fn large_case() -> Case {
    Case::named(
        "large",
        json!({"message_id": "3EB0A9", "handle": super::super::registry::PLACEHOLDER_HANDLE}),
    )
    .seeded("wa")
    .after(
        "wa.open",
        json!({"url": "$WA", "key": super::fixture::KEY, "cache_dir": "$DIR/media"}),
    )
}

// ---- request bodies the proto crate has no type for

#[derive(Deserialize)]
pub(super) struct Label {
    label: String,
}

#[derive(Deserialize)]
pub(super) struct KeyRef {
    key_id: String,
}

#[derive(Deserialize)]
pub(super) struct IdRef {
    id: String,
}

#[derive(Deserialize)]
pub(super) struct Ids {
    ids: Vec<String>,
}

#[derive(Deserialize)]
pub(super) struct MessageRef {
    message_id: String,
}

#[derive(Deserialize)]
pub(super) struct JidRef {
    jid: String,
}

#[derive(Deserialize)]
pub(super) struct Jids {
    jids: Vec<String>,
}

#[derive(Deserialize)]
pub(super) struct Presence {
    available: bool,
}

#[derive(Deserialize)]
pub(super) struct PlaceTo {
    to: String,
}

#[derive(Deserialize)]
pub(super) struct CallRef {
    call_id: String,
}

#[derive(Deserialize)]
pub(super) struct CallMute {
    call_id: String,
    muted: bool,
}

/// A page of messages, newest first; `before_ms` pages back.
#[derive(Deserialize)]
pub(super) struct Page {
    chat: String,
    #[serde(default)]
    before_ms: Option<i64>,
    #[serde(default = "default_limit")]
    limit: u32,
}

fn default_limit() -> u32 {
    50
}

/// The bytes of an upload: base64 text, or a file the caller wrote (large videos).
#[derive(Deserialize)]
pub(super) struct SendMedia {
    chat: String,
    kind: MediaKind,
    mime: String,
    #[serde(default)]
    caption: Option<String>,
    #[serde(default)]
    data_b64: Option<String>,
    #[serde(default)]
    path: Option<String>,
}

#[derive(Deserialize)]
pub(super) struct SendMediaWith {
    #[serde(flatten)]
    media: SendMedia,
    options: MediaOptions,
}

#[derive(Deserialize)]
pub(super) struct PictureReq {
    jid: String,
    #[serde(default)]
    full: bool,
    #[serde(default)]
    known_id: Option<String>,
}

// ---- answers that carry bytes

/// Bytes as the Kotlin side receives them: `data_b64`, or `path` of a temp file when over [`INLINE_LIMIT`].
#[derive(Debug, Serialize)]
pub(super) struct BytesAns {
    size: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    data_b64: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    path: Option<String>,
}

#[derive(Serialize)]
pub(super) struct PictureAns {
    /// `image`, `unchanged` or `none`.
    kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(flatten, skip_serializing_if = "Option::is_none")]
    bytes: Option<BytesAns>,
}

/// Inline base64, or a file named `<prefix>-<id>.bin` under `dir` for anything over `limit`.
pub(super) fn bytes_ans(
    bytes: &[u8],
    dir: &Path,
    prefix: &str,
    id: &str,
    limit: usize,
) -> Result<BytesAns, FfiError> {
    if bytes.len() <= limit {
        return Ok(BytesAns {
            size: bytes.len(),
            data_b64: Some(STANDARD.encode(bytes)),
            path: None,
        });
    }
    std::fs::create_dir_all(dir)?;
    let safe: String = id
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect();
    let path = dir.join(format!("{prefix}-{safe}.bin"));
    std::fs::write(&path, bytes)?;
    Ok(BytesAns {
        size: bytes.len(),
        data_b64: None,
        path: Some(path.to_string_lossy().into_owned()),
    })
}

/// The bytes of an upload request; exactly one of `data_b64` and `path`.
fn upload(media: &SendMedia) -> Result<Vec<u8>, FfiError> {
    match (&media.data_b64, &media.path) {
        (Some(_), Some(_)) => Err(FfiError::invalid("send either data_b64 or path, not both")),
        (None, None) => Err(FfiError::invalid(
            "nothing to send: data_b64 and path are empty",
        )),
        (Some(text), None) => STANDARD
            .decode(text)
            .map_err(|e| FfiError::invalid(format!("data_b64 is not base64: {e}"))),
        (None, Some(path)) => Ok(std::fs::read(path)?),
    }
}

fn caption(media: &SendMedia) -> Option<&str> {
    media.caption.as_deref()
}

// ---- handlers

gateway_fn!(create_instance, CreateInstance => CreatedInstance, |g, b| g.create_instance(&b));
gateway_fn!(status, Nothing => InstanceStatus, |g, _b| g.status());
gateway_fn!(relink, LinkRequest => Empty, |g, b| done(g.relink(&b)));
gateway_fn!(delete_instance, Nothing => Empty, |g, _b| done(g.delete_instance()));
gateway_fn!(mint_key, Label => IssuedKey, |g, b| g.mint_key(&b.label));
gateway_fn!(keys, Nothing => Vec<KeyInfo>, |g, _b| g.keys());
gateway_fn!(revoke_key, KeyRef => Empty, |g, b| done(g.revoke_key(&b.key_id)));
gateway_fn!(register_push, PushRegistration => Empty, |g, b| done(g.register_push(&b)));
gateway_fn!(chats, Nothing => Vec<ChatSummary>, |g, _b| g.chats());
gateway_fn!(chats_with_meta, Nothing => Vec<RichChat>, |g, _b| g.chats_with_meta());
gateway_fn!(messages, Page => Vec<WaMessage>, |g, b| g.messages(&b.chat, b.before_ms, b.limit));
gateway_fn!(messages_with_meta, Page => Vec<RichMessage>, |g, b| {
    g.messages_with_meta(&b.chat, b.before_ms, b.limit)
});
gateway_fn!(send_text, SendText => Sent, |g, b| g.send_text(&b));
gateway_fn!(schedule_text, ScheduleText => ScheduledText, |g, b| g.schedule_text(&b));
gateway_fn!(scheduled, Nothing => Vec<ScheduledText>, |g, _b| g.scheduled());
gateway_fn!(cancel_scheduled, IdRef => Empty, |g, b| done(g.cancel_scheduled(&b.id)));
gateway_fn!(react, SendReaction => Empty, |g, b| done(g.react(&b)));
gateway_fn!(mark_read, MarkRead => Empty, |g, b| done(g.mark_read(&b)));
gateway_fn!(mark_played, MarkRead => Empty, |g, b| done(g.mark_played(&b)));
gateway_fn!(set_presence, Presence => Empty, |g, b| done(g.set_presence(b.available)));
gateway_fn!(subscribe_presence, JidRef => Empty, |g, b| done(g.subscribe_presence(&b.jid)));
gateway_fn!(chat_state, ChatState => Empty, |g, b| done(g.chat_state(&b)));
gateway_fn!(groups, Nothing => Vec<GroupInfo>, |g, _b| g.groups());
gateway_fn!(group, JidRef => GroupInfo, |g, b| g.group(&b.jid));
gateway_fn!(lookup_users, Jids => Vec<WaUser>, |g, b| g.lookup_users(&b.jids));
gateway_fn!(profiles, Jids => Vec<WaProfile>, |g, b| g.profiles(&b.jids));
gateway_fn!(refresh_profiles, Jids => Vec<WaProfile>, |g, b| g.refresh_profiles(&b.jids));
gateway_fn!(statuses, Nothing => StatusList, |g, _b| g.statuses());
gateway_fn!(mark_status_viewed, Ids => Empty, |g, b| done(g.mark_status_viewed(&b.ids)));
gateway_fn!(edit_message, EditMessage => Empty, |g, b| done(g.edit_message(&b)));
gateway_fn!(revoke_message, RevokeMessage => Empty, |g, b| done(g.revoke_message(&b)));
gateway_fn!(delete_for_me, DeleteForMe => Empty, |g, b| done(g.delete_for_me(&b)));
gateway_fn!(star_message, StarMessage => Empty, |g, b| done(g.star_message(&b)));
gateway_fn!(chat_action, ChatAction => Empty, |g, b| done(g.chat_action(&b)));
gateway_fn!(place_call, PlaceTo => CallStarted, |g, b| g.place_call(&b.to));
gateway_fn!(calls, Nothing => Vec<CallSummary>, |g, _b| g.calls());
gateway_fn!(accept_call, CallRef => Empty, |g, b| done(g.accept_call(&b.call_id)));
gateway_fn!(reject_call, CallRef => Empty, |g, b| done(g.reject_call(&b.call_id)));
gateway_fn!(end_call, CallRef => Empty, |g, b| done(g.end_call(&b.call_id)));
gateway_fn!(set_muted, CallMute => Empty, |g, b| done(g.set_muted(&b.call_id, b.muted)));

fn send_media(req: Call<SendMedia>) -> Result<Sent, FfiError> {
    let bytes = upload(&req.body)?;
    let g = super::ctx(req.handle)?;
    let b = &req.body;
    g.send_media(&b.chat, b.kind, &b.mime, caption(b), bytes)
        .map_err(super::map_error)
}

fn send_media_with(req: Call<SendMediaWith>) -> Result<Sent, FfiError> {
    let bytes = upload(&req.body.media)?;
    let g = super::ctx(req.handle)?;
    let b = &req.body.media;
    g.send_media_with(
        &b.chat,
        b.kind,
        &b.mime,
        caption(b),
        &req.body.options,
        bytes,
    )
    .map_err(super::map_error)
}

fn media(req: Call<MessageRef>) -> Result<BytesAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let bytes = g.media(&req.body.message_id).map_err(super::map_error)?;
    bytes_ans(
        &bytes,
        &g.cache_dir,
        "media",
        &req.body.message_id,
        INLINE_LIMIT,
    )
}

fn thumbnail(req: Call<MessageRef>) -> Result<BytesAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let bytes = g
        .thumbnail(&req.body.message_id)
        .map_err(super::map_error)?;
    bytes_ans(
        &bytes,
        &g.cache_dir,
        "thumb",
        &req.body.message_id,
        INLINE_LIMIT,
    )
}

fn status_media(req: Call<IdRef>) -> Result<BytesAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let bytes = g.status_media(&req.body.id).map_err(super::map_error)?;
    bytes_ans(&bytes, &g.cache_dir, "status", &req.body.id, INLINE_LIMIT)
}

fn status_thumbnail(req: Call<IdRef>) -> Result<BytesAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let bytes = g.status_thumbnail(&req.body.id).map_err(super::map_error)?;
    bytes_ans(
        &bytes,
        &g.cache_dir,
        "status-thumb",
        &req.body.id,
        INLINE_LIMIT,
    )
}

fn picture(req: Call<PictureReq>) -> Result<PictureAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let b = &req.body;
    match g
        .picture(&b.jid, b.full, b.known_id.as_deref())
        .map_err(super::map_error)?
    {
        Picture::Image { id, bytes } => {
            let name = format!("{}-{id}", b.jid);
            Ok(PictureAns {
                kind: "image",
                bytes: Some(bytes_ans(
                    &bytes,
                    &g.cache_dir,
                    "picture",
                    &name,
                    INLINE_LIMIT,
                )?),
                id: Some(id),
            })
        }
        Picture::Unchanged => Ok(PictureAns {
            kind: "unchanged",
            id: None,
            bytes: None,
        }),
        Picture::None => Ok(PictureAns {
            kind: "none",
            id: None,
            bytes: None,
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::ErrorKind;

    #[test]
    fn bytes_stay_inline_up_to_the_limit_and_become_a_file_above_it() {
        let dir = std::env::temp_dir().join(format!("rovsti-wa-bytes-{}", std::process::id()));
        let small = bytes_ans(&[1, 2, 3], &dir, "media", "a/b", 3).unwrap();
        assert_eq!(small.size, 3);
        assert_eq!(small.data_b64.as_deref(), Some("AQID"));
        assert!(small.path.is_none());
        let big = bytes_ans(&[1, 2, 3, 4], &dir, "media", "a/b", 3).unwrap();
        assert!(big.data_b64.is_none());
        let path = big.path.unwrap();
        assert!(path.ends_with("media-a_b.bin"), "{path}");
        assert_eq!(std::fs::read(&path).unwrap(), vec![1, 2, 3, 4]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_upload_needs_exactly_one_source() {
        let media = |data: Option<&str>, path: Option<&str>| SendMedia {
            chat: "c".into(),
            kind: MediaKind::Image,
            mime: "image/png".into(),
            caption: None,
            data_b64: data.map(str::to_owned),
            path: path.map(str::to_owned),
        };
        assert_eq!(upload(&media(Some("AQID"), None)).unwrap(), vec![1, 2, 3]);
        for bad in [
            media(None, None),
            media(Some("AQID"), Some("/x")),
            media(Some("*"), None),
        ] {
            assert_eq!(upload(&bad).unwrap_err().kind, ErrorKind::Invalid);
        }
        assert_eq!(
            upload(&media(None, Some("/definitely/not/here")))
                .unwrap_err()
                .kind,
            ErrorKind::NotFound
        );
    }
}
