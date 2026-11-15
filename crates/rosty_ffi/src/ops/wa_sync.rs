//! `wa_sync.*` (E6.4s): the WhatsApp offline store of `rosty_app_core::wa_sync`, a SQLite file per linked instance.
//!
//! `wa_sync.open {instance_id, dir?, memory?}` answers a `handle`; every other op sends it back and
//! `wa_sync.close` (or `RustNative.release`) frees it. Opening the same file twice in one process shares one store.
//! No op here touches the network: the caller performs the gateway calls (`wa.*`) and hands the answers in, with the
//! time. The ops that write answer the `handle` again next to their result.

use crate::envelope::{ErrorKind, FfiError};
use crate::handles;
use crate::op;
use crate::ops::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use crate::state;
use base64::{Engine, engine::general_purpose::STANDARD};
use rosty_app_core::wa_sync::{
    self, Action, AppliedEvents, ChatRow, Due, Eviction, FetchPage, MessageRow, Opened, OutboxItem,
    Outcome, Settled, StarredCursor, StarredPage, Store, StoredChats, StoredPage, SyncPlan,
    ThreadCursor, ThreadPage,
};
use rosty_wa_proto::{EventEnvelope, RichChat, RichMessage};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, PoisonError, Weak};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "wa_sync.open",
        OpenReq,
        WithHandle<Opened>,
        open,
        open_cases
    ),
    op!("wa_sync.close", HandleReq, CloseAns, close, close_cases),
    op!("wa_sync.plan", PlanReq, SyncPlan, plan, plan_cases),
    op!(
        "wa_sync.apply_events",
        EventsReq,
        WithHandle<AppliedEvents>,
        apply_events,
        apply_events_cases
    ),
    op!(
        "wa_sync.store_chats",
        StoreChatsReq,
        WithHandle<StoredChats>,
        store_chats,
        store_chats_cases
    ),
    op!(
        "wa_sync.store_page",
        StorePageReq,
        WithHandle<StoredPage>,
        store_page,
        store_page_cases
    ),
    op!("wa_sync.older", OlderReq, OlderAns, older, older_cases),
    op!("wa_sync.chats", ChatsReq, ChatsAns, chats, chats_cases),
    op!(
        "wa_sync.thread",
        ThreadReq,
        ThreadPage,
        thread,
        thread_cases
    ),
    op!(
        "wa_sync.message",
        MessageReq,
        MessageAns,
        message,
        message_cases
    ),
    op!(
        "wa_sync.starred",
        StarredReq,
        StarredPage,
        starred,
        starred_cases
    ),
    op!(
        "wa_sync.outbox_enqueue",
        EnqueueReq,
        WithHandle<ItemAns>,
        outbox_enqueue,
        outbox_enqueue_cases
    ),
    op!(
        "wa_sync.outbox_due",
        DueReq,
        WithHandle<Due>,
        outbox_due,
        outbox_due_cases
    ),
    op!(
        "wa_sync.outbox_settle",
        SettleReq,
        WithHandle<Settled>,
        outbox_settle,
        outbox_settle_cases
    ),
    op!(
        "wa_sync.outbox_cancel",
        ClientIdReq,
        CancelAns,
        outbox_cancel,
        outbox_cancel_cases
    ),
    op!(
        "wa_sync.outbox_edit",
        OutboxEditReq,
        WithHandle<ItemAns>,
        outbox_edit,
        outbox_edit_cases
    ),
    op!(
        "wa_sync.outbox_retry",
        ClientIdReq,
        RetryAns,
        outbox_retry,
        outbox_retry_cases
    ),
    op!("wa_sync.outbox", HandleReq, OutboxAns, outbox, outbox_cases),
    op!(
        "wa_sync.media_set",
        MediaSetReq,
        WithHandle<Nothing>,
        media_set,
        media_set_cases
    ),
    op!("wa_sync.media", MediaReq, MediaAns, media, media_cases),
    op!(
        "wa_sync.media_evict",
        EvictReq,
        Eviction,
        media_evict,
        media_evict_cases
    ),
];

type Shared = Arc<Mutex<Store>>;

fn lock(store: &Shared) -> MutexGuard<'_, Store> {
    store.lock().unwrap_or_else(PoisonError::into_inner)
}

/// The store behind `handle`; the registry is not held while the caller works on it.
fn shared(handle: i64) -> Result<Shared, FfiError> {
    handles::global()
        .with::<Shared, _>(handle, |store| Arc::clone(store))
        .ok_or_else(|| {
            FfiError::new(
                ErrorKind::NotFound,
                format!("wa_sync handle {handle} is not open"),
            )
        })
}

/// Runs `f` on the store of `handle`.
fn with<T>(handle: i64, f: impl FnOnce(&mut Store) -> wa_sync::Result<T>) -> Result<T, FfiError> {
    let store = shared(handle)?;
    let mut store = lock(&store);
    f(&mut store).map_err(map_error)
}

fn map_error(error: wa_sync::Error) -> FfiError {
    let message = error.to_string();
    match error {
        wa_sync::Error::Sqlite(_) | wa_sync::Error::Io(_) => FfiError::new(ErrorKind::Io, message),
        wa_sync::Error::Json(_) => FfiError::new(ErrorKind::Parse, message),
        wa_sync::Error::InstanceMismatch { .. } => {
            FfiError::invalid(message).with_detail(json!({"reason": "instance_mismatch"}))
        }
        wa_sync::Error::NewerSchema { found, supported } => FfiError::unsupported(message)
            .with_detail(json!({"reason": "newer_schema", "found": found, "supported": supported})),
        wa_sync::Error::NotFound(_) => FfiError::new(ErrorKind::NotFound, message),
        wa_sync::Error::Invalid(_) => FfiError::invalid(message),
    }
}

/// An answer that repeats the handle next to its fields.
#[derive(Serialize)]
struct WithHandle<T> {
    handle: i64,
    #[serde(flatten)]
    body: T,
}

fn handled<T>(handle: i64, body: T) -> WithHandle<T> {
    WithHandle { handle, body }
}

#[derive(Serialize)]
struct Nothing {}

#[derive(Deserialize)]
struct HandleReq {
    handle: i64,
}

#[derive(Deserialize)]
struct ClientIdReq {
    handle: i64,
    client_id: String,
}

#[derive(Serialize)]
struct ItemAns {
    item: OutboxItem,
}

// ---- fixtures

const NOW: i64 = 1_790_000_000_000;
const ALI: &str = "100000000001@s.whatsapp.net";
const GROUP: &str = "120000000000000001@g.us";

fn open_req() -> Value {
    json!({"instance_id": "inst1", "memory": true})
}

/// A case on a fresh store.
fn on_store(case: Case) -> Case {
    case.after("wa_sync.open", open_req())
}

fn text_msg(chat: &str, id: &str, ts: i64, from_me: bool, text: &str) -> Value {
    json!({"id": id, "chat": chat, "sender": if from_me { Value::Null } else { json!(chat) }, "from_me": from_me,
        "timestamp_ms": ts, "body": {"text": text}, "reactions": []})
}

fn event(seq: u64, mut event: Value, kind: &str) -> Value {
    event["type"] = json!(kind);
    json!({"seq": seq, "at_ms": NOW + seq as i64, "event": event})
}

/// Three messages in one chat (one mine, edited and read; one deleted by its sender) and one in a group.
fn sample_events() -> Value {
    json!({"handle": H, "events": [
        event(1, text_msg(ALI, "A1", NOW - 5_000, false, "merhaba"), "message"),
        event(2, text_msg(ALI, "A2", NOW - 4_000, true, "selam"), "message"),
        event(3, json!({"chat": ALI, "id": "A2", "body": {"text": "selam!"},
            "meta": {"edited_ms": NOW - 3_500, "status": "sent"}}), "message_meta"),
        event(4, json!({"chat": ALI, "ids": ["A2"], "kind": "read"}), "receipt"),
        event(5, text_msg(ALI, "A3", NOW - 3_000, false, "bunu sileceğim"), "message"),
        event(6, json!({"chat": ALI, "id": "A3", "body": "unsupported",
            "meta": {"revoked": true, "deleted": true, "deleted_at": NOW - 2_000, "deleted_by": ALI}}), "message_meta"),
        event(7, json!({"id": "G1", "chat": GROUP, "sender": ALI, "from_me": false, "timestamp_ms": NOW - 1_000,
            "body": {"media": {"kind": "image", "caption": "bak", "mime": "image/jpeg", "size": 2048}},
            "reactions": [{"sender": ALI, "from_me": false, "emoji": "👍"}]}), "message"),
        event(8, json!({"chat": GROUP, "id": "G1", "meta": {"sender_name": "Ali", "forwarded": true,
            "media": {"width": 640, "height": 480, "thumbnail": true}}}), "message_meta"),
    ]})
}

/// A case on a store that holds [`sample_events`].
fn on_sample(case: Case) -> Case {
    on_store(case).after("wa_sync.apply_events", sample_events())
}

fn enqueue_req(client_id: &str, text: &str) -> Value {
    json!({"handle": H, "now_ms": NOW, "client_id": client_id, "chat": ALI,
        "action": {"kind": "send_text", "text": text}})
}

/// A case on the sample store with one text waiting in the outbox.
fn on_queued(case: Case) -> Case {
    on_sample(case).after("wa_sync.outbox_enqueue", enqueue_req("c1", "yoldayım"))
}

/// A case on the sample store whose queued text was handed out (`sending`).
fn on_sending(case: Case) -> Case {
    on_queued(case).after(
        "wa_sync.outbox_due",
        json!({"handle": H, "now_ms": NOW + 10}),
    )
}

// ---- wa_sync.open

#[derive(Deserialize)]
struct OpenReq {
    /// The directory of the store files; `core.init`'s data dir when left out.
    #[serde(default)]
    dir: Option<String>,
    /// The linked instance (from the connect key): one file per instance.
    instance_id: String,
    /// A store in memory (tests, previews); `dir` is not used.
    #[serde(default)]
    memory: bool,
}

/// The file stores open in this process: a second `wa_sync.open` of a file shares the first one's connection.
fn open_files() -> MutexGuard<'static, HashMap<PathBuf, Weak<Mutex<Store>>>> {
    static OPEN: LazyLock<Mutex<HashMap<PathBuf, Weak<Mutex<Store>>>>> =
        LazyLock::new(Mutex::default);
    OPEN.lock().unwrap_or_else(PoisonError::into_inner)
}

fn open(req: OpenReq) -> Result<WithHandle<Opened>, FfiError> {
    let store: Shared = if req.memory {
        Arc::new(Mutex::new(
            Store::open_in_memory(&req.instance_id).map_err(map_error)?,
        ))
    } else {
        let dir: PathBuf = match &req.dir {
            Some(dir) => dir.into(),
            None => state::dirs()
                .map(|dirs| dirs.data_dir.clone())
                .ok_or_else(|| FfiError::invalid("init was not called"))?,
        };
        let path = dir.join(wa_sync::file_name(&req.instance_id));
        let mut open = open_files();
        open.retain(|_, store| store.strong_count() > 0);
        match open.get(&path).and_then(Weak::upgrade) {
            Some(store) => store,
            None => {
                let store = Arc::new(Mutex::new(
                    Store::open(&dir, &req.instance_id).map_err(map_error)?,
                ));
                open.insert(path, Arc::downgrade(&store));
                store
            }
        }
    };
    let opened = lock(&store).opened().map_err(map_error)?;
    Ok(handled(handles::global().insert(store), opened))
}

fn open_cases() -> Vec<Case> {
    vec![
        Case::new(open_req()),
        Case::named("no_instance", json!({"instance_id": " ", "memory": true})),
    ]
}

// ---- wa_sync.close

#[derive(Serialize)]
struct CloseAns {
    closed: bool,
}

fn close(req: HandleReq) -> Result<CloseAns, FfiError> {
    shared(req.handle)?;
    Ok(CloseAns {
        closed: handles::global().release(req.handle),
    })
}

fn close_cases() -> Vec<Case> {
    vec![
        on_store(Case::new(json!({"handle": H}))),
        Case::named("not_open", json!({"handle": 42})),
    ]
}

// ---- wa_sync.plan

#[derive(Deserialize)]
struct PlanReq {
    handle: i64,
    /// The page size of the fetches (the gateway takes 1 to 200; 50 when left out).
    #[serde(default)]
    limit: Option<u32>,
}

fn plan(req: PlanReq) -> Result<SyncPlan, FfiError> {
    with(req.handle, |store| store.plan(req.limit))
}

fn plan_cases() -> Vec<Case> {
    vec![
        on_store(Case::new(json!({"handle": H}))),
        // After a refreshed chat list found missed messages: the first page of that chat.
        on_sample(Case::named("gap", json!({"handle": H, "limit": 100})))
            .after("wa_sync.store_chats", chats_req(None))
            .after(
                "wa_sync.apply_events",
                json!({"handle": H, "events": [event(3, json!({}), "resync")]}),
            )
            .after("wa_sync.store_chats", chats_req(Some("A9"))),
    ]
}

// ---- wa_sync.apply_events

#[derive(Deserialize)]
struct EventsReq {
    handle: i64,
    /// Gateway events as the socket sent them (`{seq, at_ms, event}`), oldest first.
    events: Vec<EventEnvelope>,
}

fn apply_events(req: EventsReq) -> Result<WithHandle<AppliedEvents>, FfiError> {
    with(req.handle, |store| store.apply_events(&req.events)).map(|out| handled(req.handle, out))
}

fn apply_events_cases() -> Vec<Case> {
    vec![
        on_store(Case::new(sample_events())),
        on_sample(Case::named(
            "resync",
            json!({"handle": H, "events": [event(2, json!({}), "resync")]}),
        )),
        on_sample(Case::named(
            "stale",
            json!({"handle": H, "events": [event(4, text_msg(ALI, "OLD", NOW - 9_000, false, "eski"), "message")]}),
        )),
        // The echo of a send whose answer never came confirms the outbox row.
        on_sending(Case::named(
            "echo",
            json!({"handle": H, "events": [event(9, text_msg(ALI, "S1", NOW + 20, true, "yoldayım"), "message")]}),
        )),
    ]
}

// ---- wa_sync.store_chats

#[derive(Deserialize)]
struct StoreChatsReq {
    handle: i64,
    now_ms: i64,
    /// `GET /v1/chats` (`wa.chats_with_meta`).
    chats: Vec<RichChat>,
    #[serde(default)]
    limit: Option<u32>,
    /// Chats active within this long get one page even when nothing seems missed.
    #[serde(default)]
    refresh_recent_ms: Option<i64>,
}

fn store_chats(req: StoreChatsReq) -> Result<WithHandle<StoredChats>, FfiError> {
    with(req.handle, |store| {
        store.store_chats(&req.chats, req.now_ms, req.limit, req.refresh_recent_ms)
    })
    .map(|out| handled(req.handle, out))
}

/// The gateway's chat list for the sample store; `newest` names a message the store has not seen.
fn chats_req(newest: Option<&str>) -> Value {
    let last = match newest {
        Some(id) => text_msg(ALI, id, NOW + 60_000, false, "yeni"),
        None => {
            json!({"id": "A3", "chat": ALI, "sender": ALI, "from_me": false, "timestamp_ms": NOW - 3_000,
            "body": "unsupported", "reactions": []})
        }
    };
    json!({"handle": H, "now_ms": NOW + 61_000, "chats": [
        {"jid": ALI, "name": "Ali", "last": last, "unread": if newest.is_some() { 4 } else { 0 }, "is_group": false,
            "meta": {"picture_id": "17", "push_name": "ali", "pinned": true}},
        {"jid": GROUP, "name": "Aile", "last": null, "unread": 1, "is_group": true,
            "meta": {"muted_until_ms": -1}},
    ]})
}

fn store_chats_cases() -> Vec<Case> {
    vec![
        on_sample(Case::new(chats_req(None))),
        on_sample(Case::named("missed", chats_req(Some("A9")))),
    ]
}

// ---- wa_sync.store_page

#[derive(Deserialize)]
struct StorePageReq {
    handle: i64,
    now_ms: i64,
    chat: String,
    /// The `before=` that was asked.
    #[serde(default)]
    before: Option<i64>,
    /// The `limit=` that was asked.
    limit: u32,
    /// `GET /v1/chats/{chat}/messages` (`wa.messages_with_meta`), newest first.
    messages: Vec<RichMessage>,
}

fn store_page(req: StorePageReq) -> Result<WithHandle<StoredPage>, FfiError> {
    with(req.handle, |store| {
        store.store_page(&req.chat, req.before, req.limit, &req.messages, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

fn page_req(before: Option<i64>, messages: Value) -> Value {
    json!({"handle": H, "now_ms": NOW + 62_000, "chat": ALI, "before": before, "limit": 2, "messages": messages})
}

fn store_page_cases() -> Vec<Case> {
    let mut quoting = text_msg(ALI, "A8", NOW + 50_000, false, "cevap");
    quoting["meta"] = json!({"quoted": {"id": "A2", "sender": null, "preview": "selam!"}});
    let gap = |case: Case| on_sample(case).after("wa_sync.store_chats", chats_req(Some("A9")));
    vec![
        // The first page of a gap does not reach the stored history yet: the next page follows.
        gap(Case::new(page_req(
            None,
            json!([text_msg(ALI, "A9", NOW + 60_000, false, "yeni"), quoting]),
        ))),
        // A page that reaches the stored history closes the gap.
        gap(Case::named(
            "overlap",
            page_req(
                None,
                json!([
                    text_msg(ALI, "A9", NOW + 60_000, false, "yeni"),
                    text_msg(ALI, "A1", NOW - 5_000, false, "merhaba")
                ]),
            ),
        )),
        // Older history: a short page means there is nothing older.
        on_sample(Case::named(
            "older",
            page_req(
                Some(NOW - 4_999),
                json!([text_msg(ALI, "A0", NOW - 9_000, false, "en eski")]),
            ),
        )),
    ]
}

// ---- wa_sync.older

#[derive(Deserialize)]
struct OlderReq {
    handle: i64,
    chat: String,
    #[serde(default)]
    limit: Option<u32>,
}

#[derive(Serialize)]
struct OlderAns {
    /// The page before the oldest stored message; `null`: the gateway has nothing older.
    fetch: Option<FetchPage>,
}

fn older(req: OlderReq) -> Result<OlderAns, FfiError> {
    with(req.handle, |store| store.older(&req.chat, req.limit)).map(|fetch| OlderAns { fetch })
}

fn older_cases() -> Vec<Case> {
    vec![
        on_sample(Case::new(json!({"handle": H, "chat": ALI}))),
        on_sample(Case::named(
            "start",
            json!({"handle": H, "chat": ALI, "limit": 2}),
        ))
        .after(
            "wa_sync.store_page",
            page_req(
                Some(NOW - 4_999),
                json!([text_msg(ALI, "A0", NOW - 9_000, false, "en eski")]),
            ),
        ),
    ]
}

// ---- wa_sync.chats

#[derive(Deserialize)]
struct ChatsReq {
    handle: i64,
    #[serde(default)]
    include_archived: bool,
}

#[derive(Serialize)]
struct ChatsAns {
    chats: Vec<ChatRow>,
}

fn chats(req: ChatsReq) -> Result<ChatsAns, FfiError> {
    with(req.handle, |store| store.chats(req.include_archived)).map(|chats| ChatsAns { chats })
}

fn chats_cases() -> Vec<Case> {
    vec![
        on_queued(Case::new(json!({"handle": H}))).after("wa_sync.store_chats", chats_req(None)),
        on_store(Case::named(
            "empty",
            json!({"handle": H, "include_archived": true}),
        )),
    ]
}

// ---- wa_sync.thread

#[derive(Deserialize)]
struct ThreadReq {
    handle: i64,
    chat: String,
    limit: u32,
    /// The `next` of the page before; left out for the newest page.
    #[serde(default)]
    before: Option<ThreadCursor>,
}

fn thread(req: ThreadReq) -> Result<ThreadPage, FfiError> {
    with(req.handle, |store| {
        store.thread(&req.chat, req.limit, req.before.as_ref())
    })
}

fn thread_cases() -> Vec<Case> {
    vec![
        // A waiting send, a deleted message (content kept), my edited and read message, an incoming one.
        on_queued(Case::new(json!({"handle": H, "chat": ALI, "limit": 2}))),
        on_queued(Case::named(
            "older",
            json!({"handle": H, "chat": ALI, "limit": 2, "before": {"timestamp_ms": NOW - 4_000, "id": "A2"}}),
        )),
        on_sample(Case::named("media", json!({"handle": H, "chat": GROUP, "limit": 50}))),
        // An edit and a reaction that wait in the outbox show on their messages.
        on_sample(Case::named("pending", json!({"handle": H, "chat": ALI, "limit": 50})))
            .after(
                "wa_sync.outbox_enqueue",
                json!({"handle": H, "now_ms": NOW, "client_id": "e1", "chat": ALI,
                    "action": {"kind": "edit", "message_id": "A2", "text": "selam :)"}}),
            )
            .after(
                "wa_sync.outbox_enqueue",
                json!({"handle": H, "now_ms": NOW, "client_id": "r1", "chat": ALI,
                    "action": {"kind": "react", "message_id": "A1", "from_me": false, "sender": ALI, "emoji": "❤"}}),
            ),
        on_store(Case::named("unknown_chat", json!({"handle": H, "chat": ALI, "limit": 50}))),
    ]
}

// ---- wa_sync.message

#[derive(Deserialize)]
struct MessageReq {
    handle: i64,
    chat: String,
    id: String,
}

#[derive(Serialize)]
struct MessageAns {
    message: Option<MessageRow>,
}

fn message(req: MessageReq) -> Result<MessageAns, FfiError> {
    with(req.handle, |store| store.message(&req.chat, &req.id))
        .map(|message| MessageAns { message })
}

fn message_cases() -> Vec<Case> {
    vec![
        on_sample(Case::new(json!({"handle": H, "chat": ALI, "id": "A3"}))),
        on_sample(Case::named(
            "unknown",
            json!({"handle": H, "chat": ALI, "id": "nope"}),
        )),
    ]
}

// ---- wa_sync.starred

#[derive(Deserialize)]
struct StarredReq {
    handle: i64,
    limit: u32,
    /// The `next` of the page before; left out for the newest page.
    #[serde(default)]
    before: Option<StarredCursor>,
}

fn starred(req: StarredReq) -> Result<StarredPage, FfiError> {
    with(req.handle, |store| {
        store.starred(req.limit, req.before.as_ref())
    })
}

fn star_req(client_id: &str, chat: &str, id: &str, starred: bool) -> Value {
    json!({"handle": H, "now_ms": NOW, "client_id": client_id, "chat": chat,
        "action": {"kind": "star", "message_id": id, "starred": starred}})
}

fn starred_cases() -> Vec<Case> {
    // Stars of two chats that still wait in the outbox: they are listed at once, the newest message first.
    let on_starred = |case: Case| {
        on_sample(case)
            .after("wa_sync.outbox_enqueue", star_req("s1", ALI, "A1", true))
            .after("wa_sync.outbox_enqueue", star_req("s2", GROUP, "G1", true))
    };
    vec![
        on_starred(Case::new(json!({"handle": H, "limit": 1}))),
        on_starred(Case::named(
            "older",
            json!({"handle": H, "limit": 1,
                "before": {"timestamp_ms": NOW - 1_000, "chat": GROUP, "id": "G1"}}),
        )),
        // The unstar took the star back before it went out.
        on_starred(Case::named("unstarred", json!({"handle": H, "limit": 50})))
            .after("wa_sync.outbox_enqueue", star_req("s3", ALI, "A1", false)),
        on_sample(Case::named("empty", json!({"handle": H, "limit": 50}))),
    ]
}

// ---- wa_sync.outbox_enqueue

#[derive(Deserialize)]
struct EnqueueReq {
    handle: i64,
    now_ms: i64,
    /// The caller's random id of this action; queuing it twice is one row.
    client_id: String,
    chat: String,
    action: Action,
}

fn outbox_enqueue(req: EnqueueReq) -> Result<WithHandle<ItemAns>, FfiError> {
    with(req.handle, |store| {
        store.outbox_enqueue(&req.client_id, &req.chat, &req.action, req.now_ms)
    })
    .map(|item| handled(req.handle, ItemAns { item }))
}

fn outbox_enqueue_cases() -> Vec<Case> {
    let queue = |label: &'static str, action: Value| {
        on_sample(Case::named(
            label,
            json!({"handle": H, "now_ms": NOW, "client_id": label, "chat": ALI, "action": action}),
        ))
    };
    vec![
        on_sample(Case::new(enqueue_req("c1", "yoldayım"))),
        queue(
            "media",
            json!({"kind": "send_media", "file": "/data/user/0/dev.rosty.messages/files/outbox/c2.jpg",
                "media": "image", "mime": "image/jpeg", "size": 4096, "caption": "bak", "quoted_id": "A1"}),
        ),
        queue(
            "edit",
            json!({"kind": "edit", "message_id": "A2", "text": "selam :)"}),
        ),
        queue("revoke", json!({"kind": "revoke", "message_id": "A2"})),
        queue(
            "react",
            json!({"kind": "react", "message_id": "A1", "from_me": false, "sender": ALI, "emoji": "❤"}),
        ),
        queue(
            "mark_read",
            json!({"kind": "mark_read", "sender": ALI, "ids": ["A1", "A3"]}),
        ),
        queue(
            "star",
            json!({"kind": "star", "message_id": "A1", "starred": true}),
        ),
        on_sample(Case::named(
            "no_chat",
            json!({"handle": H, "now_ms": NOW, "client_id": "c9", "chat": "", "action": {"kind": "send_text", "text": "x"}}),
        )),
    ]
}

// ---- wa_sync.outbox_due

#[derive(Deserialize)]
struct DueReq {
    handle: i64,
    now_ms: i64,
    /// The connection just came back: every backoff ends.
    #[serde(default)]
    network_back: bool,
}

fn outbox_due(req: DueReq) -> Result<WithHandle<Due>, FfiError> {
    with(req.handle, |store| {
        store.outbox_due(req.now_ms, req.network_back)
    })
    .map(|due| handled(req.handle, due))
}

fn outbox_due_cases() -> Vec<Case> {
    vec![
        on_queued(Case::new(json!({"handle": H, "now_ms": NOW + 10}))),
        // Handed out already: nothing more until the answer, or the lease, is in.
        on_sending(Case::named(
            "sending",
            json!({"handle": H, "now_ms": NOW + 20}),
        )),
        on_sample(Case::named(
            "empty",
            json!({"handle": H, "now_ms": NOW, "network_back": true}),
        )),
    ]
}

// ---- wa_sync.outbox_settle

#[derive(Deserialize)]
struct SettleReq {
    handle: i64,
    now_ms: i64,
    client_id: String,
    outcome: Outcome,
}

fn outbox_settle(req: SettleReq) -> Result<WithHandle<Settled>, FfiError> {
    with(req.handle, |store| {
        store.outbox_settle(&req.client_id, &req.outcome, req.now_ms)
    })
    .map(|settled| handled(req.handle, settled))
}

fn outbox_settle_cases() -> Vec<Case> {
    let settle = |outcome: Value| json!({"handle": H, "now_ms": NOW + 500, "client_id": "c1", "outcome": outcome});
    vec![
        on_sending(Case::new(settle(
            json!({"kind": "sent", "message_id": "S1", "timestamp_ms": NOW + 400}),
        ))),
        on_sending(Case::named(
            "network",
            settle(json!({"kind": "error", "error": "network", "message": "timed out"})),
        )),
        on_sending(Case::named(
            "rate_limited",
            settle(
                json!({"kind": "error", "error": "gateway", "message": "slow down", "retry_after_ms": 30_000}),
            ),
        )),
        on_sending(Case::named(
            "rejected",
            settle(
                json!({"kind": "error", "error": "rejected", "message": "text must be 1 byte to 64 KiB"}),
            ),
        )),
        // Its echo confirmed it meanwhile (or it was cancelled): done, nothing to send again.
        on_sample(Case::named("gone", settle(json!({"kind": "sent"})))),
        // A refused star is undone and dropped: no failed row stays behind.
        on_sample(Case::named(
            "star_rejected",
            json!({"handle": H, "now_ms": NOW + 500, "client_id": "s1",
                "outcome": {"kind": "error", "error": "rejected", "message": "no such message"}}),
        ))
        .after("wa_sync.outbox_enqueue", star_req("s1", ALI, "A1", true))
        .after(
            "wa_sync.outbox_due",
            json!({"handle": H, "now_ms": NOW + 10}),
        ),
    ]
}

// ---- wa_sync.outbox_cancel, wa_sync.outbox_edit, wa_sync.outbox_retry, wa_sync.outbox

#[derive(Serialize)]
struct CancelAns {
    /// `false`: already gone, or going out right now.
    cancelled: bool,
}

fn outbox_cancel(req: ClientIdReq) -> Result<CancelAns, FfiError> {
    with(req.handle, |store| store.outbox_cancel(&req.client_id))
        .map(|cancelled| CancelAns { cancelled })
}

fn outbox_cancel_cases() -> Vec<Case> {
    vec![
        on_queued(Case::new(json!({"handle": H, "client_id": "c1"}))),
        on_sending(Case::named(
            "in_flight",
            json!({"handle": H, "client_id": "c1"}),
        )),
    ]
}

#[derive(Deserialize)]
struct OutboxEditReq {
    handle: i64,
    client_id: String,
    /// The new text (or caption).
    text: String,
}

fn outbox_edit(req: OutboxEditReq) -> Result<WithHandle<ItemAns>, FfiError> {
    with(req.handle, |store| {
        store.outbox_edit(&req.client_id, &req.text)
    })
    .map(|item| handled(req.handle, ItemAns { item }))
}

fn outbox_edit_cases() -> Vec<Case> {
    let edit = json!({"handle": H, "client_id": "c1", "text": "5 dk'ya oradayım"});
    vec![
        on_queued(Case::new(edit.clone())),
        on_sending(Case::named("in_flight", edit.clone())),
        on_sample(Case::named("gone", edit)),
    ]
}

#[derive(Serialize)]
struct RetryAns {
    retried: bool,
}

fn outbox_retry(req: ClientIdReq) -> Result<RetryAns, FfiError> {
    with(req.handle, |store| store.outbox_retry(&req.client_id)).map(|retried| RetryAns { retried })
}

fn outbox_retry_cases() -> Vec<Case> {
    vec![
        on_sending(Case::new(json!({"handle": H, "client_id": "c1"}))).after(
            "wa_sync.outbox_settle",
            json!({"handle": H, "now_ms": NOW + 500, "client_id": "c1",
                "outcome": {"kind": "error", "error": "rejected", "message": "no"}}),
        ),
        on_sample(Case::named("gone", json!({"handle": H, "client_id": "c1"}))),
    ]
}

#[derive(Serialize)]
struct OutboxAns {
    items: Vec<OutboxItem>,
}

fn outbox(req: HandleReq) -> Result<OutboxAns, FfiError> {
    with(req.handle, |store| store.outbox()).map(|items| OutboxAns { items })
}

fn outbox_cases() -> Vec<Case> {
    vec![on_queued(Case::new(json!({"handle": H})))]
}

// ---- wa_sync.media_set, wa_sync.media, wa_sync.media_evict

#[derive(Deserialize)]
struct MediaSetReq {
    handle: i64,
    now_ms: i64,
    chat: String,
    id: String,
    /// The thumbnail bytes (standard base64); kept with the message for good.
    #[serde(default)]
    thumbnail_b64: Option<String>,
    /// The downloaded full file; `bytes` is its size.
    #[serde(default)]
    path: Option<String>,
    #[serde(default)]
    bytes: Option<u64>,
}

fn media_set(req: MediaSetReq) -> Result<WithHandle<Nothing>, FfiError> {
    let thumbnail = req
        .thumbnail_b64
        .as_deref()
        .map(|text| STANDARD.decode(text))
        .transpose()
        .map_err(|e| {
            FfiError::new(
                ErrorKind::Parse,
                format!("thumbnail_b64 is not base64: {e}"),
            )
        })?;
    if req.path.is_some() != req.bytes.is_some() {
        return Err(FfiError::invalid("path and bytes go together"));
    }
    with(req.handle, |store| {
        if let Some(thumbnail) = &thumbnail {
            store.set_thumbnail(&req.chat, &req.id, thumbnail)?;
        }
        if let (Some(path), Some(bytes)) = (&req.path, req.bytes) {
            store.set_media_file(&req.chat, &req.id, path, bytes, req.now_ms)?;
        }
        Ok(())
    })?;
    Ok(handled(req.handle, Nothing {}))
}

fn media_set_req(id: &str) -> Value {
    json!({"handle": H, "now_ms": NOW, "chat": GROUP, "id": id, "thumbnail_b64": STANDARD.encode(b"thumbnail"),
        "path": "/data/user/0/dev.rosty.messages/cache/wa-media/G1.jpg", "bytes": 2048})
}

fn media_set_cases() -> Vec<Case> {
    vec![
        on_sample(Case::new(media_set_req("G1"))),
        on_sample(Case::named("unknown", media_set_req("nope"))),
        on_sample(Case::named(
            "bad_base64",
            json!({"handle": H, "now_ms": NOW, "chat": GROUP, "id": "G1", "thumbnail_b64": "***"}),
        )),
    ]
}

#[derive(Deserialize)]
struct MediaReq {
    handle: i64,
    chat: String,
    id: String,
    /// The media is being opened now: its file becomes the most recently used.
    #[serde(default)]
    used_ms: Option<i64>,
}

#[derive(Serialize)]
struct MediaAns {
    thumbnail_b64: Option<String>,
    /// The cached full file; `null` when not downloaded or evicted.
    path: Option<String>,
    bytes: Option<u64>,
}

fn media(req: MediaReq) -> Result<MediaAns, FfiError> {
    let found = with(req.handle, |store| {
        store.media(&req.chat, &req.id, req.used_ms)
    })?;
    let info = found.ok_or_else(|| {
        FfiError::new(
            ErrorKind::NotFound,
            format!("message {} of {}", req.id, req.chat),
        )
    })?;
    Ok(MediaAns {
        thumbnail_b64: info.thumbnail.map(|bytes| STANDARD.encode(bytes)),
        path: info.path,
        bytes: info.bytes,
    })
}

fn media_cases() -> Vec<Case> {
    vec![
        on_sample(Case::new(
            json!({"handle": H, "chat": GROUP, "id": "G1", "used_ms": NOW + 5}),
        ))
        .after("wa_sync.media_set", media_set_req("G1")),
        on_sample(Case::named(
            "none",
            json!({"handle": H, "chat": GROUP, "id": "G1"}),
        )),
        on_sample(Case::named(
            "unknown",
            json!({"handle": H, "chat": GROUP, "id": "nope"}),
        )),
    ]
}

#[derive(Deserialize)]
struct EvictReq {
    handle: i64,
    /// The cap from Settings.
    max_bytes: u64,
}

fn media_evict(req: EvictReq) -> Result<Eviction, FfiError> {
    with(req.handle, |store| store.media_evict(req.max_bytes))
}

fn media_evict_cases() -> Vec<Case> {
    vec![
        on_sample(Case::new(json!({"handle": H, "max_bytes": 1024})))
            .after("wa_sync.media_set", media_set_req("G1")),
        on_sample(Case::named(
            "under_the_cap",
            json!({"handle": H, "max_bytes": 4096}),
        ))
        .after("wa_sync.media_set", media_set_req("G1")),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::{Answer, decode};
    use crate::ops::call;

    fn ok(op: &str, request: Value) -> Value {
        match decode(&call(op, request.to_string().as_bytes())).expect("envelope") {
            Answer::Ok(value) => value,
            Answer::Err(error) => panic!("{op}: {error:?}"),
        }
    }

    fn err(op: &str, request: Value) -> FfiError {
        match decode(&call(op, request.to_string().as_bytes())).expect("envelope") {
            Answer::Ok(value) => panic!("{op}: {value}"),
            Answer::Err(error) => error,
        }
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("rosty-ffi-wa-sync-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn a_file_store_survives_close_and_keeps_instances_apart() {
        let dir = temp_dir("file");
        let open =
            |instance: &str| ok("wa_sync.open", json!({"dir": dir, "instance_id": instance}));
        let first = open("inst1");
        let handle = first["handle"].as_i64().expect("handle");
        assert_eq!(first["needs_resync"], true);
        let mut events = sample_events();
        events["handle"] = json!(handle);
        assert_eq!(ok("wa_sync.apply_events", events)["last_seq"], 8);
        ok("wa_sync.outbox_enqueue", {
            let mut req = enqueue_req("c1", "yoldayım");
            req["handle"] = json!(handle);
            req
        });
        assert_eq!(
            ok("wa_sync.close", json!({"handle": handle}))["closed"],
            true
        );
        assert_eq!(
            err("wa_sync.plan", json!({"handle": handle})).kind,
            ErrorKind::NotFound
        );

        // Another instance: its own empty file.
        let other = open("inst2");
        assert_eq!(other["last_seq"], Value::Null);
        let other_handle = other["handle"].as_i64().expect("handle");
        assert_eq!(
            ok("wa_sync.chats", json!({"handle": other_handle}))["chats"],
            json!([])
        );

        // The first one again: everything is there, the queued text included.
        let again = open("inst1");
        assert_eq!(again["last_seq"], 8);
        let handle = again["handle"].as_i64().expect("handle");
        let thread = ok(
            "wa_sync.thread",
            json!({"handle": handle, "chat": ALI, "limit": 10}),
        );
        let ids: Vec<&str> = thread["items"]
            .as_array()
            .expect("items")
            .iter()
            .map(|row| row["id"].as_str().expect("id"))
            .collect();
        assert_eq!(ids, ["c1", "A3", "A2", "A1"]);
        assert_eq!(thread["items"][0]["state"], "waiting");
        assert_eq!(thread["items"][1]["deleted"], true);
        assert_eq!(thread["items"][1]["text"], "bunu sileceğim");
        handles::global().release(handle);
        handles::global().release(other_handle);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn two_handles_of_one_file_share_the_store() {
        let dir = temp_dir("shared");
        let open = || {
            ok("wa_sync.open", json!({"dir": dir, "instance_id": "inst1"}))["handle"]
                .as_i64()
                .expect("handle")
        };
        let (ui, worker) = (open(), open());
        assert_ne!(ui, worker);
        let mut events = sample_events();
        events["handle"] = json!(worker);
        ok("wa_sync.apply_events", events);
        // Closing one handle leaves the other working on the same data.
        ok("wa_sync.close", json!({"handle": worker}));
        let chats = ok("wa_sync.chats", json!({"handle": ui}));
        assert_eq!(chats["chats"].as_array().expect("chats").len(), 2);
        handles::global().release(ui);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_wrong_handle_is_not_found_for_every_op() {
        let foreign = handles::global().insert(7u8);
        for spec in OPS.iter().filter(|spec| spec.name != "wa_sync.open") {
            for handle in [0, foreign] {
                let request = json!({"handle": handle, "now_ms": 0, "client_id": "c", "chat": "c", "id": "i",
                    "limit": 1, "events": [], "chats": [], "messages": [], "text": "", "max_bytes": 0,
                    "action": {"kind": "send_text", "text": "x"}, "outcome": {"kind": "sent"}});
                let error = err(spec.name, request);
                assert_eq!(error.kind, ErrorKind::NotFound, "{}", spec.name);
            }
        }
        handles::global().release(foreign);
    }
}
