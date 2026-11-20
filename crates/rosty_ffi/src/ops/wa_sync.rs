//! `wa_sync.*` (E6.4s): the WhatsApp offline store of `rosty_app_core::wa_sync`, a SQLite file per linked instance.
//!
//! `wa_sync.open {instance_id, dir?, memory?}` answers a `handle`; every other op sends it back and
//! `wa_sync.close` (or `RustNative.release`) frees it. Opening the same file twice in one process shares one store.
//! No op here touches the network: the caller performs the gateway calls (`wa.*`) and hands the answers in, with the
//! time. The ops that write answer the `handle` again next to their result.
//!
//! One person is one chat here, whichever id the gateway names them by. The store learns the pairs from the events
//! it applies and the ids its payloads carry; `wa_sync.identity_catch_up` takes the pages of `wa.identity_mappings`
//! and `wa_sync.identity_observe` what `wa.status`, `wa.group(s)` and `wa.profiles` answered. A merge is reported
//! as `rekeyed` (`{seq, from_key, into_key}`) by the op that caused it and listed by `wa_sync.rekeyed_since`; the
//! reads take any id of a chat, so a screen that still holds the old one reads the merged chat.
//!
//! Media: the store decides, the caller does the file I/O. `wa_sync.media_enqueue` asks for a file (a tap, or the
//! auto-download rules under the network the caller names), `wa_sync.media_due` hands out the downloads to make,
//! `wa_sync.media_settle` reports each, `wa_sync.media_evict` and `wa_sync.media_reconcile` answer the files to
//! delete, `wa_sync.media_stats` and `wa_sync.media_cap` serve the settings screen.
//!
//! Media of a chat and what the owner did with it: `wa_sync.chat_media` pages the photos, videos, audio and
//! documents of a chat both ways (a viewer), `wa_sync.media_viewed` marks a view-once message opened (its file
//! is handed back for deletion and never fetched again), `wa_sync.mark_played` queues the played receipt of a
//! received voice note, once.
//!
//! The gateway and the account: `wa_sync.note_status` takes the gateway's epoch, account epoch, own ids and
//! history progress (another epoch is a gap, another account empties the store), `wa_sync.account_changed` takes
//! the gateway's announcement of an account change, `wa_sync.history` reads the progress back;
//! `wa_sync.identity_observe` (a whole `wa.status` answer) and `wa_sync.apply_events` (`hello`, `resync`, `state`,
//! `account_changed`, `history_synced`) take the same from the wire types, so a caller copies no field by hand;
//! `wa_sync.wipe` closes a store and deletes its files (unlink).

use crate::envelope::{ErrorKind, FfiError};
use crate::handles;
use crate::op;
use crate::ops::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use crate::state;
use base64::{Engine, engine::general_purpose::STANDARD};
use rosty_app_core::wa_sync::{
    self, Action, AppliedEvents, ChatRekeyed, ChatRow, Conditions, Decision, Due, Eviction,
    Expired, Fetch, FetchPage, Fetched, GalleryPage, GroupPage, GroupStored, HistoryCount,
    HistoryProgress, HistoryReport, HistorySnapshot, IdentityApplied, IdentityCaughtUp,
    IdentitySnapshot, Kind, MediaDue, MediaEntry, MediaQueued, MediaSettled, MediaState,
    MediaStats, MessageRow, Network, Observed, Opened, OutboxItem, Outcome, Played, Reconciled,
    ResolvedId, Settled, StarredCursor, StarredPage, StatusNote, StatusNoted, Store, StoredChats,
    StoredPage, SyncPlan, ThreadCursor, ThreadPage, Viewed, Wiped,
};
use rosty_wa_proto::{
    EventEnvelope, GroupInfo, IdentityMappings, InstanceStatus, RichChat, RichMessage, WaProfile,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, PoisonError, Weak};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "wa_sync.open",
        OpenReq,
        WithHandle<OpenAns>,
        open,
        open_cases
    ),
    op!(
        "wa_sync.open_page",
        OlderReq,
        OlderAns,
        open_page,
        open_page_cases
    ),
    op!("wa_sync.focus", FocusReq, FocusAns, focus, focus_cases),
    op!(
        "wa_sync.cache_save",
        HandleReq,
        CacheSaved,
        cache_save,
        cache_save_cases
    ),
    op!(
        "wa_sync.history_save",
        HandleReq,
        HistorySaved,
        history_save,
        history_save_cases
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
    op!(
        "wa_sync.media_policy",
        MediaPolicyReq,
        MediaPolicyAns,
        media_policy,
        media_policy_cases
    ),
    op!(
        "wa_sync.media_enqueue",
        MediaEnqueueReq,
        WithHandle<MediaQueued>,
        media_enqueue,
        media_enqueue_cases
    ),
    op!(
        "wa_sync.media_due",
        MediaDueReq,
        WithHandle<MediaDue>,
        media_due,
        media_due_cases
    ),
    op!(
        "wa_sync.media_settle",
        MediaSettleReq,
        WithHandle<MediaSettled>,
        media_settle,
        media_settle_cases
    ),
    op!(
        "wa_sync.media_keep",
        MediaKeepReq,
        WithHandle<MediaKeptAns>,
        media_keep,
        media_keep_cases
    ),
    op!(
        "wa_sync.media_reconcile",
        ReconcileReq,
        WithHandle<Reconciled>,
        media_reconcile,
        media_reconcile_cases
    ),
    op!(
        "wa_sync.media_stats",
        MediaStatsReq,
        MediaStats,
        media_stats,
        media_stats_cases
    ),
    op!(
        "wa_sync.media_cap",
        MediaCapReq,
        WithHandle<MediaCapAns>,
        media_cap,
        media_cap_cases
    ),
    op!(
        "wa_sync.chat_media",
        ChatMediaReq,
        GalleryPage,
        chat_media,
        chat_media_cases
    ),
    op!(
        "wa_sync.media_viewed",
        MediaViewedReq,
        WithHandle<Viewed>,
        media_viewed,
        media_viewed_cases
    ),
    op!(
        "wa_sync.expire",
        ExpireReq,
        WithHandle<Expired>,
        expire,
        expire_cases
    ),
    op!(
        "wa_sync.mark_played",
        MarkPlayedReq,
        WithHandle<Played>,
        mark_played,
        mark_played_cases
    ),
    op!(
        "wa_sync.note_status",
        NoteStatusReq,
        WithHandle<StatusNoted>,
        note_status,
        note_status_cases
    ),
    op!(
        "wa_sync.account_changed",
        AccountChangedReq,
        WithHandle<IdentityApplied>,
        account_changed,
        account_changed_cases
    ),
    op!(
        "wa_sync.history",
        HandleReq,
        HistoryAns,
        history,
        history_cases
    ),
    op!("wa_sync.wipe", WipeReq, Wiped, wipe, wipe_cases),
    op!(
        "wa_sync.group_store",
        GroupStoreReq,
        WithHandle<GroupStored>,
        group_store,
        group_store_cases
    ),
    op!(
        "wa_sync.group_read",
        GroupReadReq,
        WithHandle<GroupPage>,
        group_read,
        group_read_cases
    ),
    op!(
        "wa_sync.identity_resolve",
        ResolveReq,
        Option<ResolvedId>,
        identity_resolve,
        identity_resolve_cases
    ),
    op!(
        "wa_sync.rekeyed_since",
        RekeyedReq,
        RekeyedAns,
        rekeyed_since,
        rekeyed_since_cases
    ),
    op!(
        "wa_sync.identity_numbers",
        ResolveReq,
        NumbersAns,
        identity_numbers,
        identity_numbers_cases
    ),
    op!(
        "wa_sync.identity_catch_up",
        CatchUpReq,
        WithHandle<IdentityCaughtUp>,
        identity_catch_up,
        identity_catch_up_cases
    ),
    op!(
        "wa_sync.identity_observe",
        ObserveReq,
        WithHandle<IdentityApplied>,
        identity_observe,
        identity_observe_cases
    ),
];

/// A store behind its handles. `None` once `wa_sync.wipe` deleted it: the handles that still name it answer
/// "not found" until they are closed.
type Shared = Arc<Mutex<Option<Store>>>;

/// The stores open on a file, by path.
type OpenFiles = HashMap<PathBuf, Weak<Mutex<Option<Store>>>>;

fn lock(store: &Shared) -> MutexGuard<'_, Option<Store>> {
    store.lock().unwrap_or_else(PoisonError::into_inner)
}

fn wiped(handle: i64) -> FfiError {
    FfiError::new(
        ErrorKind::NotFound,
        format!("the store of wa_sync handle {handle} was wiped"),
    )
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
    let store = store.as_mut().ok_or_else(|| wiped(handle))?;
    f(store).map_err(map_error)
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
/// The LID of [`ALI`].
const ALI_LID: &str = "777000000000001@lid";
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

/// One message under Ali's LID, written before the store knew whose LID it is.
fn lid_events() -> Value {
    json!({"handle": H, "events": [
        event(9, text_msg(ALI_LID, "L1", NOW - 500, false, "lid'den"), "message"),
    ]})
}

fn mapped_event(seq: u64, lid: &str, pn: &str, source: &str) -> Value {
    event(
        seq,
        json!({"lid": lid, "pn": pn, "source": source, "learned_ms": NOW}),
        "identity_mapped",
    )
}

/// A case on the sample store that also holds a chat under Ali's LID: one person in two chats.
fn on_two_chats(case: Case) -> Case {
    on_sample(case).after("wa_sync.apply_events", lid_events())
}

/// A case on a store where the LID chat and the number chat of Ali became one.
fn on_merged(case: Case) -> Case {
    on_two_chats(case).after(
        "wa_sync.apply_events",
        json!({"handle": H, "events": [mapped_event(10, ALI_LID, ALI, "peer_message")]}),
    )
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
    /// A store in memory (the product since the vault, tests, previews); `dir` is not used.
    #[serde(default)]
    memory: bool,
    /// The lazy sync (`plans/wa-vault.md` 14): pages only for the chat on screen (`wa_sync.focus`), for a chat
    /// that is opened (`wa_sync.open_page`) and for a scroll (`wa_sync.older`); a page never names the next one.
    #[serde(default)]
    lazy: bool,
    /// Starts the identity map and its catch-up cursor from the encrypted vault cache (plan 13.5), when the
    /// vault is unlocked for this instance; `wa_sync.cache_save` writes it back. Also seeds the recent chats and
    /// messages from the vault's history file (round 7a); `wa_sync.history_save` writes it.
    #[serde(default)]
    vault_cache: bool,
}

/// What `wa_sync.open {vault_cache}` found in the vault cache.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum CacheUse {
    /// The map and the cursor were taken from the cache.
    Loaded,
    /// The vault is unlocked but its cache knows nobody yet (or this store already did).
    Empty,
    /// The vault is locked, or holds another instance: nothing was read.
    Locked,
}

#[derive(Serialize)]
struct OpenAns {
    #[serde(flatten)]
    opened: Opened,
    #[serde(skip_serializing_if = "Option::is_none")]
    lazy: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    cache: Option<CacheUse>,
    /// What the history file put into the store (round 7a): shown at once, the sync then catches up lazily.
    #[serde(skip_serializing_if = "Option::is_none")]
    history: Option<HistoryCount>,
}

/// The file stores open in this process: a second `wa_sync.open` of a file shares the first one's connection.
fn open_files() -> MutexGuard<'static, OpenFiles> {
    static OPEN: LazyLock<Mutex<OpenFiles>> = LazyLock::new(Mutex::default);
    OPEN.lock().unwrap_or_else(PoisonError::into_inner)
}

/// The directory of the store files: the one named, else `core.init`'s data dir.
fn store_dir(dir: Option<&str>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(dir.into()),
        None => state::dirs()
            .map(|dirs| dirs.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called")),
    }
}

fn open(req: OpenReq) -> Result<WithHandle<OpenAns>, FfiError> {
    let mut cache = None;
    let mut history = None;
    let store: Shared = if req.memory {
        let mut store = Store::open_in_memory(&req.instance_id).map_err(map_error)?;
        if req.vault_cache {
            cache = Some(seed_from_vault(&mut store)?);
            // After the identity: the backfill of the seeded rows resolves their ids against the cached map.
            history = seed_history_from_vault(&mut store)?;
        }
        Arc::new(Mutex::new(Some(store)))
    } else {
        let dir = store_dir(req.dir.as_deref())?;
        let path = dir.join(wa_sync::file_name(&req.instance_id));
        let mut open = open_files();
        open.retain(|_, store| store.strong_count() > 0);
        // A store that was wiped is not shared again: the instance starts a new file.
        let live = open
            .get(&path)
            .and_then(Weak::upgrade)
            .filter(|store| lock(store).is_some());
        match live {
            Some(store) => store,
            None => {
                let store = Arc::new(Mutex::new(Some(
                    Store::open(&dir, &req.instance_id).map_err(map_error)?,
                )));
                open.insert(path, Arc::downgrade(&store));
                store
            }
        }
    };
    let (opened, lazy) = match lock(&store).as_mut() {
        Some(store) => {
            // A file store shared with another open keeps the lazy sync once one open asked for it.
            if req.lazy {
                store.set_lazy(true);
            }
            (store.opened().map_err(map_error)?, store.is_lazy())
        }
        None => {
            return Err(FfiError::new(
                ErrorKind::Io,
                "the store was wiped while it opened",
            ));
        }
    };
    let answer = OpenAns {
        opened,
        lazy: lazy.then_some(true),
        cache,
        history,
    };
    Ok(handled(handles::global().insert(store), answer))
}

/// Starts `store`'s identity from the vault cache when the vault is unlocked for its instance.
fn seed_from_vault(store: &mut Store) -> Result<CacheUse, FfiError> {
    if super::vault::unlocked_instance().as_deref() != Some(store.instance_id()) {
        return Ok(CacheUse::Locked);
    }
    let snapshot = super::vault::with_cache(false, |cache| {
        let mut snapshot: IdentitySnapshot =
            serde_json::from_value(cache.identity.clone()).unwrap_or_default();
        snapshot.since = cache.identity_cursor.clone();
        snapshot
    })?;
    Ok(if store.seed_identity(&snapshot).map_err(map_error)? {
        CacheUse::Loaded
    } else {
        CacheUse::Empty
    })
}

/// Seeds `store` with the recent chats and messages of the vault's history file, when the vault is unlocked for its
/// instance. A file that does not read as a snapshot is ignored (the next save replaces it).
fn seed_history_from_vault(store: &mut Store) -> Result<Option<HistoryCount>, FfiError> {
    if super::vault::unlocked_instance().as_deref() != Some(store.instance_id()) {
        return Ok(None);
    }
    let Some(plain) = super::vault::history_load(store.instance_id())? else {
        return Ok(None);
    };
    let Ok(snapshot) = serde_json::from_slice::<HistorySnapshot>(&plain) else {
        return Ok(None);
    };
    store.seed_history(&snapshot).map_err(map_error)
}

fn open_cases() -> Vec<Case> {
    vec![
        Case::new(open_req()),
        Case::named("no_instance", json!({"instance_id": " ", "memory": true})),
        Case::named("lazy", vault_req()),
        // The vault of the fixtures is unlocked and its cache knows Ali's LID: the map starts from it.
        // The history file of a session before: the chats and messages show at once.
        Case::named("vault_history", vault_req())
            .seeded("wa_vault")
            .after("vault.unlock", super::vault::seed_unlock_request())
            .after("wa_sync.open", lazy_req())
            .after("wa_sync.apply_events", sample_events())
            .after("wa_sync.store_chats", chats_req(None))
            .after("wa_sync.history_save", json!({"handle": H})),
        Case::named("vault_cache", vault_req())
            .seeded("wa_vault")
            .after("vault.unlock", super::vault::seed_unlock_request())
            .after("wa_sync.open", lazy_req())
            .after(
                "wa_sync.identity_catch_up",
                json!({"handle": H, "items": [{"lid": ALI_LID, "pn": ALI, "source": "peer_message",
                    "learned_ms": NOW}], "next": "c1"}),
            )
            .after("wa_sync.cache_save", json!({"handle": H})),
    ]
}

fn lazy_req() -> Value {
    json!({"instance_id": "inst1", "memory": true, "lazy": true})
}

fn vault_req() -> Value {
    json!({"instance_id": "inst1", "memory": true, "lazy": true, "vault_cache": true})
}

/// A case on a fresh lazy store.
fn on_lazy(case: Case) -> Case {
    case.after("wa_sync.open", lazy_req())
}

// ---- wa_sync.open_page, wa_sync.focus (the lazy sync)

fn open_page(req: OlderReq) -> Result<OlderAns, FfiError> {
    with(req.handle, |store| store.open_page(&req.chat, req.limit)).map(|fetch| OlderAns { fetch })
}

fn open_page_cases() -> Vec<Case> {
    vec![
        // The chat list stored Ali's newest message only: opening the chat asks for its newest page.
        on_lazy(Case::new(json!({"handle": H, "chat": ALI})))
            .after("wa_sync.store_chats", chats_req(None)),
        // Paged in this session: nothing more until a scroll.
        on_lazy(Case::named("paged", json!({"handle": H, "chat": ALI})))
            .after("wa_sync.store_chats", chats_req(None))
            .after(
                "wa_sync.store_page",
                page_req(
                    None,
                    json!([text_msg(ALI, "A3", NOW - 3_000, false, "bunu sileceğim")]),
                ),
            ),
        // A store that is not lazy pages its gaps from the plan: nothing here.
        on_sample(Case::named("eager", json!({"handle": H, "chat": ALI}))),
    ]
}

#[derive(Deserialize)]
struct FocusReq {
    handle: i64,
    /// The chat on screen (any of its ids); left out: none.
    #[serde(default)]
    chat: Option<String>,
}

#[derive(Serialize)]
struct FocusAns {
    handle: i64,
}

fn focus(req: FocusReq) -> Result<FocusAns, FfiError> {
    with(req.handle, |store| {
        store.set_focus(req.chat.as_deref());
        Ok(())
    })?;
    Ok(FocusAns { handle: req.handle })
}

fn focus_cases() -> Vec<Case> {
    vec![
        on_lazy(Case::new(json!({"handle": H, "chat": ALI}))),
        on_lazy(Case::named("none", json!({"handle": H}))),
    ]
}

// ---- wa_sync.cache_save

#[derive(Serialize)]
struct CacheSaved {
    handle: i64,
    /// People in the saved map.
    persons: u32,
}

/// Writes the store's identity map and catch-up cursor into the encrypted vault cache (plan 13.5); its pictures
/// stay. `locked` while the vault is locked or holds another instance.
fn cache_save(req: HandleReq) -> Result<CacheSaved, FfiError> {
    let (instance, snapshot) = with(req.handle, |store| {
        let instance = store.instance_id().to_owned();
        store
            .identity_snapshot()
            .map(|snapshot| (instance, snapshot))
    })?;
    if super::vault::unlocked_instance().as_deref() != Some(instance.as_str()) {
        return Err(
            FfiError::new(ErrorKind::NotFound, "the WhatsApp vault is locked")
                .with_detail(json!({"code": "locked"})),
        );
    }
    let persons = snapshot.persons.len() as u32;
    let since = snapshot.since.clone();
    let identity = serde_json::to_value(IdentitySnapshot {
        since: None,
        ..snapshot
    })
    .map_err(|e| FfiError::invalid(e.to_string()))?;
    super::vault::with_cache(true, |cache| {
        cache.identity = identity;
        cache.identity_cursor = since;
    })?;
    Ok(CacheSaved {
        handle: req.handle,
        persons,
    })
}

fn cache_save_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H}))
            .seeded("wa_vault")
            .after("vault.unlock", super::vault::seed_unlock_request())
            .after("wa_sync.open", lazy_req()),
        on_lazy(Case::named("locked", json!({"handle": H}))),
    ]
}

// ---- wa_sync.history_save

#[derive(Serialize)]
struct HistorySaved {
    handle: i64,
    #[serde(flatten)]
    kept: HistoryCount,
}

/// Writes the store's recent chats and messages into the vault's history file, sealed under DK (round 7a; bounds in
/// `rosty_app_core::wa_sync::history`). Called when WhatsApp locks and when the app goes to the background, so the
/// next unlock shows them at once. `locked` while the vault is locked or holds another instance.
fn history_save(req: HandleReq) -> Result<HistorySaved, FfiError> {
    let (instance, snapshot) = with(req.handle, |store| {
        let instance = store.instance_id().to_owned();
        store
            .history_snapshot()
            .map(|snapshot| (instance, snapshot))
    })?;
    if super::vault::unlocked_instance().as_deref() != Some(instance.as_str()) {
        return Err(
            FfiError::new(ErrorKind::NotFound, "the WhatsApp vault is locked")
                .with_detail(json!({"code": "locked"})),
        );
    }
    let kept = HistoryCount {
        chats: snapshot.chats.len() as u32,
        messages: snapshot.messages.len() as u32,
    };
    let plain = zeroize::Zeroizing::new(
        serde_json::to_vec(&snapshot).map_err(|e| FfiError::invalid(e.to_string()))?,
    );
    drop(snapshot);
    super::vault::history_store(&instance, &plain)?;
    Ok(HistorySaved {
        handle: req.handle,
        kept,
    })
}

fn history_save_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H}))
            .seeded("wa_vault")
            .after("vault.unlock", super::vault::seed_unlock_request())
            .after("wa_sync.open", lazy_req())
            .after("wa_sync.apply_events", sample_events())
            .after("wa_sync.store_chats", chats_req(None)),
        on_lazy(Case::named("locked", json!({"handle": H}))),
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
        // The gateway learned whose LID it is: the two chats of the person become one (`rekeyed`).
        on_two_chats(Case::named(
            "identity",
            json!({"handle": H, "events": [mapped_event(10, ALI_LID, ALI, "peer_message")]}),
        )),
        // A message that names both forms of its chat teaches the pair itself; a number change follows it.
        on_two_chats(Case::named("alt_ids", {
            let mut message = text_msg(ALI_LID, "L2", NOW - 400, false, "ikisi de");
            message["chat_alt"] = json!(ALI);
            json!({"handle": H, "events": [
                event(10, message, "message"),
                event(11, json!({"old_pn": ALI, "new_pn": "100000000002@s.whatsapp.net", "new_lid": ALI_LID}),
                    "identity_number_changed"),
            ]})
        })),
        // Every other place an event names a person by both ids: a group author, who reacted, who is typing,
        // who is online, who calls, a profile.
        on_two_chats(Case::named("other_forms", {
            let veli = "100000000002@s.whatsapp.net";
            let veli_lid = "777000000000002@lid";
            let mut message = text_msg(GROUP, "G2", NOW - 300, false, "grupta");
            message["sender"] = json!(veli_lid);
            message["sender_alt"] = json!(veli);
            message["reactions"] =
                json!([{"sender": ALI, "from_me": false, "emoji": "👍", "sender_alt": ALI_LID}]);
            json!({"handle": H, "events": [
                event(10, message, "message"),
                event(11, json!({"chat": GROUP, "id": "G2", "read": false, "reactions": [
                    {"sender": veli_lid, "from_me": false, "emoji": "❤", "sender_alt": veli}]}), "message_update"),
                event(12, json!({"chat": GROUP, "jid": veli_lid, "state": "typing", "jid_alt": veli}), "typing"),
                event(13, json!({"jid": veli, "available": true, "last_seen_ms": null, "jid_alt": veli_lid}),
                    "presence"),
                event(14, json!({"call_id": "c1", "peer": veli, "peer_name": null, "direction": "in",
                    "phase": "ringing", "is_video": false, "peer_alt": veli_lid}), "call"),
                event(15, json!({"jid": veli, "push_name": "veli", "contact_name": null, "about": null,
                    "picture_id": null, "business": false, "verified_name": null, "fetched_ms": NOW,
                    "lid": veli_lid}), "profile"),
            ]})
        })),
        // Pairs that change nothing are listed (`rejected`): ids of the wrong kind, a weaker source.
        on_merged(Case::named(
            "rejected",
            json!({"handle": H, "events": [
                mapped_event(11, ALI, ALI_LID, "usync"),
                mapped_event(12, ALI_LID, "100000000002@s.whatsapp.net", "history"),
            ]}),
        )),
        // The account's own number and LID arrive with the instance state.
        on_store(Case::named(
            "own_ids",
            json!({"handle": H, "events": [event(1, json!({"instance_id": "inst1", "state": "connected",
                "own_jid": "100000000009@s.whatsapp.net", "push_name": null, "own_lid": "777000000000009@lid"}),
                "state")]}),
        )),
        // A socket of a restarted gateway: `hello` names another epoch than the stored one (a gap: `resync`,
        // the low numbers of the new process are applied), a history batch says its kind, and the `state`
        // names the gateway, the account and how far its history got (`wa_sync.history` then answers it).
        on_linked(Case::named(
            "gateway",
            json!({"handle": H, "events": [
                event(1, json!({"epoch": "e2"}), "hello"),
                event(2, json!({"chats": 12, "progress": 40, "sync_type": "full", "messages": 3500,
                    "oldest_ms": NOW - 86_400_000}), "history_synced"),
                event(3, json!({"instance_id": "inst1", "state": "connected", "own_jid": OWN, "push_name": null,
                    "epoch": "e2", "account_epoch": 1, "history": {"state": "syncing", "progress": 55,
                    "chats": 120, "messages": 48_000, "oldest_ms": NOW - 86_400_000}}), "state"),
                event(4, json!({"epoch": "e2"}), "resync"),
            ]}),
        )),
        // The gateway announces another account at its place in the stream: what came before is dropped
        // (`account_changed`, `delete_files`), what follows is kept.
        on_linked(Case::named(
            "account_changed",
            json!({"handle": H, "events": [
                event(9, text_msg(ALI, "A9", NOW + 2_500, false, "eski hesaptan"), "message"),
                event(10, json!({"old_pn": OWN, "new_pn": OTHER_OWN}), "account_changed"),
                event(11, text_msg(ALI, "B1", NOW + 2_600, false, "yeni hesaptan"), "message"),
            ]}),
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
        // The gateway lists the chat under the LID and names its number (`alt`): the stored chats become one.
        on_two_chats(Case::named(
            "alt",
            json!({"handle": H, "now_ms": NOW + 61_000, "chats": [
                {"jid": ALI_LID, "name": "Ali", "last": null, "unread": 1, "is_group": false, "alt": ALI},
            ]}),
        )),
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
        // A page asked under the LID whose message names the number: it is a page of the number's chat, and
        // the two stored chats become one (`rekeyed`).
        on_two_chats(Case::named("alt", {
            let mut newest = text_msg(ALI_LID, "L3", NOW + 50_000, false, "yeni");
            newest["chat_alt"] = json!(ALI);
            newest["sender_alt"] = json!(ALI);
            json!({"handle": H, "now_ms": NOW + 62_000, "chat": ALI_LID, "before": null, "limit": 50,
                "messages": [newest]})
        })),
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
        // One chat for the person; `wire_jid` is the id the gateway last used for it.
        on_merged(Case::named("merged", json!({"handle": H}))),
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
        // A voice note with its waveform (played), a GIF, and a view-once photo that was opened.
        on_marked(Case::named("media_round", json!({"handle": H, "chat": ALI, "limit": 3}))),
        // A view-once message Ali sent, which only the phone can open: a placeholder row.
        on_sample(Case::named("placeholder", json!({"handle": H, "chat": ALI, "limit": 1})))
            .after("wa_sync.apply_events", placeholder_events()),
        // A view-once voice note that waits in the outbox shows what it was queued with.
        on_sample(Case::named("media_waiting", json!({"handle": H, "chat": ALI, "limit": 1})))
            .after("wa_sync.outbox_enqueue", voice_send_req("v1")),
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
        // A screen that still holds the LID of a chat that merged into its number reads the merged thread; the
        // message that arrived under the LID keeps it as `wire_sender`.
        on_merged(Case::named("old_key", json!({"handle": H, "chat": ALI_LID, "limit": 2}))),
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
        // Everything a media send can carry: preview, pixel size, GIF flag, view once.
        queue(
            "media_options",
            json!({"kind": "send_media", "file": "/data/user/0/dev.rosty.messages/files/outbox/c3.mp4",
                "media": "video", "mime": "video/mp4", "size": 90000, "seconds": 4,
                "thumbnail_b64": "/9j/4A==", "width": 640, "height": 360, "animated": true, "view_once": true}),
        ),
        on_sample(Case::named("voice_note", voice_send_req("voice_note"))),
        // A waveform that is not 64 samples is refused before it is queued.
        queue(
            "bad_waveform",
            json!({"kind": "send_media", "file": "/f/v.ogg", "media": "audio", "mime": "audio/ogg", "size": 9,
                "voice_note": true, "waveform_b64": "AAEC"}),
        ),
        // A played receipt has its own op.
        queue(
            "mark_played",
            json!({"kind": "mark_played", "message_id": "A1"}),
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
        // E6-WA R4: a text with mentions and a link card; a forward to another chat (the store fills the
        // snapshot); a delete for me (hidden at once).
        queue(
            "mentions_preview",
            json!({"kind": "send_text", "text": "@100000000002 https://example.com",
                "mentions": ["100000000002@s.whatsapp.net"],
                "link_preview": {"url": "https://example.com", "title": "Example", "thumbnail_b64": "/9j/4A=="}}),
        ),
        on_sample(Case::named(
            "forward",
            json!({"handle": H, "now_ms": NOW, "client_id": "f1", "chat": GROUP,
                "action": {"kind": "forward", "source_chat": ALI, "message_id": "A1"}}),
        )),
        on_sample(Case::named(
            "forward_deleted",
            json!({"handle": H, "now_ms": NOW, "client_id": "f2", "chat": GROUP,
                "action": {"kind": "forward", "source_chat": ALI, "message_id": "A3"}}),
        )),
        queue(
            "delete_for_me",
            json!({"kind": "delete_for_me", "message_id": "A1"}),
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
        // A played receipt and a media send with its options go out like any other row (one per chat: the
        // receipt was written first).
        on_media_round(Case::named(
            "mark_played",
            json!({"handle": H, "now_ms": NOW + 10}),
        ))
        .after("wa_sync.mark_played", played_req("p1", "VOICE")),
        on_sample(Case::named(
            "media_options",
            json!({"handle": H, "now_ms": NOW + 10}),
        ))
        .after("wa_sync.outbox_enqueue", voice_send_req("v1")),
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
        // A refused played receipt is dropped: no failed row stays behind, and the message stays played.
        on_media_round(Case::named(
            "played_rejected",
            json!({"handle": H, "now_ms": NOW + 500, "client_id": "p1",
                "outcome": {"kind": "error", "error": "rejected", "message": "no such message"}}),
        ))
        .after("wa_sync.mark_played", played_req("p1", "VOICE"))
        .after(
            "wa_sync.outbox_due",
            json!({"handle": H, "now_ms": NOW + 10}),
        ),
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
    /// The cached full file; `null` unless `state` is `ready`.
    path: Option<String>,
    bytes: Option<u64>,
    /// `none`, `queued`, `downloading`, `ready`, `evicted`, `failed` or `gone`.
    state: MediaState,
    /// The size the gateway named.
    #[serde(skip_serializing_if = "Option::is_none")]
    size_expected: Option<u64>,
    /// Downloads that failed since the file was last asked for.
    attempts: u32,
    pinned: bool,
    /// Where the user saved a copy.
    #[serde(skip_serializing_if = "Option::is_none")]
    saved_uri: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    last_error: Option<String>,
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
    let media = info.media;
    Ok(MediaAns {
        thumbnail_b64: info.thumbnail.map(|bytes| STANDARD.encode(bytes)),
        path: media.path,
        bytes: media.bytes,
        state: media.state,
        size_expected: media.size_expected,
        attempts: media.attempts,
        pinned: media.pinned,
        saved_uri: media.saved_uri,
        last_error: media.last_error,
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
        // A download that failed once waits for its next attempt.
        on_downloading(Case::named(
            "failed_once",
            json!({"handle": H, "chat": GROUP, "id": "G1"}),
        ))
        .after(
            "wa_sync.media_settle",
            settle_req(json!({"kind": "failed", "error": "timeout"})),
        ),
    ]
}

#[derive(Deserialize)]
struct EvictReq {
    handle: i64,
    /// The cap to bring the cache under; the stored cap (`wa_sync.media_cap`) when left out.
    #[serde(default)]
    max_bytes: Option<u64>,
    /// Now: files used in the last 24 hours are not evicted. Left out: none is spared for that reason.
    #[serde(default)]
    now_ms: Option<i64>,
}

fn media_evict(req: EvictReq) -> Result<Eviction, FfiError> {
    with(req.handle, |store| {
        store.media_evict(req.max_bytes, req.now_ms)
    })
}

fn media_evict_cases() -> Vec<Case> {
    let cached = |case: Case| on_sample(case).after("wa_sync.media_set", media_set_req("G1"));
    vec![
        cached(Case::new(json!({"handle": H, "max_bytes": 1024}))),
        cached(Case::named(
            "under_the_cap",
            json!({"handle": H, "max_bytes": 4096}),
        )),
        // The file was used a moment ago: it stays, although it alone is over the cap.
        cached(Case::named(
            "recently_used",
            json!({"handle": H, "max_bytes": 1024, "now_ms": NOW + 1_000}),
        )),
        // A starred message keeps its file whatever the cap.
        cached(Case::named("starred", json!({"handle": H, "max_bytes": 0})))
            .after("wa_sync.outbox_enqueue", star_req("s1", GROUP, "G1", true)),
        // No `max_bytes`: the stored cap.
        cached(Case::named("stored_cap", json!({"handle": H})))
            .after("wa_sync.media_cap", json!({"handle": H, "cap_bytes": 100})),
    ]
}

// ---- wa_sync.media_policy, wa_sync.media_enqueue, wa_sync.media_due, wa_sync.media_settle

/// The network and the free space the caller measured.
fn conditions(network: Network, free_bytes: Option<u64>) -> Conditions {
    Conditions {
        network,
        free_bytes,
    }
}

#[derive(Deserialize)]
struct MediaPolicyReq {
    handle: i64,
    chat: String,
    id: String,
    /// `wifi`, `mobile`, `roaming`, `data_saver` or `none`: the most restrictive that holds.
    network: Network,
    /// Free bytes where media is kept; the 500 MiB floor is not applied when left out.
    #[serde(default)]
    free_bytes: Option<u64>,
}

#[derive(Serialize)]
struct MediaPolicyAns {
    /// `auto`, `on_tap` or `never`.
    decision: Decision,
}

fn media_policy(req: MediaPolicyReq) -> Result<MediaPolicyAns, FfiError> {
    with(req.handle, |store| {
        store.media_policy(&req.chat, &req.id, &conditions(req.network, req.free_bytes))
    })
    .map(|decision| MediaPolicyAns { decision })
}

fn media_policy_cases() -> Vec<Case> {
    let ask = |network: &str| json!({"handle": H, "chat": GROUP, "id": "G1", "network": network});
    vec![
        on_sample(Case::new(ask("wifi"))),
        on_sample(Case::named("roaming", ask("roaming"))),
        on_sample(Case::named(
            "low_space",
            json!({"handle": H, "chat": GROUP, "id": "G1", "network": "wifi", "free_bytes": 1_000_000}),
        )),
        // A text has no file.
        on_sample(Case::named(
            "not_media",
            json!({"handle": H, "chat": ALI, "id": "A1", "network": "wifi"}),
        )),
        on_sample(Case::named(
            "unknown",
            json!({"handle": H, "chat": GROUP, "id": "nope", "network": "wifi"}),
        )),
    ]
}

#[derive(Deserialize)]
struct MediaEnqueueReq {
    handle: i64,
    now_ms: i64,
    chat: String,
    id: String,
    /// The user tapped the media: it is queued whatever the network. Left out: the auto-download rules decide,
    /// under `network`.
    #[serde(default)]
    tap: bool,
    #[serde(default)]
    network: Option<Network>,
    #[serde(default)]
    free_bytes: Option<u64>,
}

fn media_enqueue(req: MediaEnqueueReq) -> Result<WithHandle<MediaQueued>, FfiError> {
    let fetch = match (req.tap, req.network) {
        (true, _) => Fetch::Tap,
        (false, Some(network)) => Fetch::Auto(conditions(network, req.free_bytes)),
        (false, None) => {
            return Err(FfiError::invalid("an automatic request names the network"));
        }
    };
    with(req.handle, |store| {
        store.media_enqueue(&req.chat, &req.id, fetch, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

/// A request for the sample photo: a tap, or the rules under `network`. What is not said is left out.
fn enqueue_media_req(tap: bool, network: Option<&str>) -> Value {
    let mut req = json!({"handle": H, "now_ms": NOW, "chat": GROUP, "id": "G1"});
    if tap {
        req["tap"] = json!(true);
    }
    if let Some(network) = network {
        req["network"] = json!(network);
    }
    req
}

/// A case on the sample store whose photo was asked for by a tap.
fn on_media_queued(case: Case) -> Case {
    on_sample(case).after("wa_sync.media_enqueue", enqueue_media_req(true, None))
}

/// A case on the sample store whose photo is being downloaded.
fn on_downloading(case: Case) -> Case {
    on_media_queued(case).after(
        "wa_sync.media_due",
        json!({"handle": H, "now_ms": NOW + 10}),
    )
}

fn media_enqueue_cases() -> Vec<Case> {
    vec![
        // The rules queue a photo on Wi-Fi by themselves.
        on_sample(Case::new(enqueue_media_req(false, Some("wifi")))),
        // Not while roaming: it waits for a tap.
        on_sample(Case::named(
            "held",
            enqueue_media_req(false, Some("roaming")),
        )),
        on_sample(Case::named("tap", enqueue_media_req(true, None))),
        // A cached file is not queued again.
        on_sample(Case::named("cached", enqueue_media_req(true, None)))
            .after("wa_sync.media_set", media_set_req("G1")),
        on_sample(Case::named("no_network", enqueue_media_req(false, None))),
        // View once: never by itself, by the tap that opens it, and never again once it was opened.
        on_media_round(Case::named(
            "view_once_auto",
            json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": "ONCE", "network": "wifi"}),
        )),
        on_media_round(Case::named(
            "view_once_tap",
            json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": "ONCE", "tap": true}),
        )),
        on_marked(Case::named(
            "view_once_viewed",
            json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": "ONCE", "tap": true}),
        )),
        on_sample(Case::named(
            "unknown",
            json!({"handle": H, "now_ms": NOW, "chat": GROUP, "id": "nope", "tap": true}),
        )),
    ]
}

#[derive(Deserialize)]
struct MediaDueReq {
    handle: i64,
    now_ms: i64,
    /// The most downloads to hand out (4 when left out, at most 32).
    #[serde(default)]
    limit: Option<u32>,
    /// With a network, automatic downloads the rules no longer allow stay queued (`held`).
    #[serde(default)]
    network: Option<Network>,
    #[serde(default)]
    free_bytes: Option<u64>,
}

fn media_due(req: MediaDueReq) -> Result<WithHandle<MediaDue>, FfiError> {
    let conditions = req
        .network
        .map(|network| conditions(network, req.free_bytes));
    with(req.handle, |store| {
        store.media_due(req.now_ms, req.limit, conditions.as_ref())
    })
    .map(|out| handled(req.handle, out))
}

fn media_due_cases() -> Vec<Case> {
    vec![
        on_media_queued(Case::new(json!({"handle": H, "now_ms": NOW + 10}))),
        // Queued by the rules on Wi-Fi, asked for on mobile data while roaming: it waits.
        on_sample(Case::named(
            "held",
            json!({"handle": H, "now_ms": NOW + 10, "network": "roaming"}),
        ))
        .after(
            "wa_sync.media_enqueue",
            enqueue_media_req(false, Some("wifi")),
        ),
        // Already out: nothing more, and when its lease ends.
        on_downloading(Case::named(
            "downloading",
            json!({"handle": H, "now_ms": NOW + 20, "limit": 1}),
        )),
        on_sample(Case::named("empty", json!({"handle": H, "now_ms": NOW}))),
    ]
}

#[derive(Deserialize)]
struct MediaSettleReq {
    handle: i64,
    now_ms: i64,
    chat: String,
    id: String,
    /// `{kind: "ready", path, bytes, sha256?}`, `{kind: "failed", error?}`, `{kind: "gone", error?}` or
    /// `{kind: "cancelled"}`.
    outcome: Fetched,
}

fn media_settle(req: MediaSettleReq) -> Result<WithHandle<MediaSettled>, FfiError> {
    with(req.handle, |store| {
        store.media_settle(&req.chat, &req.id, &req.outcome, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

fn settle_req(outcome: Value) -> Value {
    json!({"handle": H, "now_ms": NOW + 500, "chat": GROUP, "id": "G1", "outcome": outcome})
}

fn media_settle_cases() -> Vec<Case> {
    let file = "/data/user/0/dev.rosty.messages/files/wa-media/inst1/files/G1/photo.jpg";
    vec![
        on_downloading(Case::new(settle_req(
            json!({"kind": "ready", "path": file, "bytes": 2100, "sha256": "9f2c"}),
        ))),
        on_downloading(Case::named(
            "failed",
            settle_req(json!({"kind": "failed", "error": "timeout"})),
        )),
        on_downloading(Case::named(
            "gone",
            settle_req(json!({"kind": "gone", "error": "the gateway no longer has it"})),
        )),
        on_downloading(Case::named(
            "cancelled",
            settle_req(json!({"kind": "cancelled"})),
        )),
        // Another file for a message that has one: the old file is named for deletion.
        on_sample(Case::named(
            "replaced",
            settle_req(json!({"kind": "ready", "path": file, "bytes": 2100})),
        ))
        .after("wa_sync.media_set", media_set_req("G1")),
        on_sample(Case::named("unknown", {
            let mut req = settle_req(json!({"kind": "cancelled"}));
            req["id"] = json!("nope");
            req
        })),
    ]
}

// ---- wa_sync.chat_media, wa_sync.media_viewed, wa_sync.mark_played

/// 64 samples rising from 0, as base64.
fn waveform_b64() -> String {
    STANDARD.encode((0..64).collect::<Vec<u8>>())
}

fn media_event(seq: u64, id: &str, ts: i64, kind: &str, mime: &str) -> Value {
    event(
        seq,
        json!({"id": id, "chat": ALI, "sender": ALI, "from_me": false, "timestamp_ms": ts,
            "body": {"media": {"kind": kind, "caption": null, "mime": mime, "size": 1000}}, "reactions": []}),
        "message",
    )
}

fn media_meta_event(seq: u64, id: &str, media: Value) -> Value {
    event(
        seq,
        json!({"chat": ALI, "id": id, "meta": {"media": media}}),
        "message_meta",
    )
}

/// Ali sends two photos, a document, a GIF, a view-once photo and a voice note with its waveform.
fn media_round_events() -> Value {
    json!({"handle": H, "events": [
        media_event(20, "M1", NOW - 900, "image", "image/jpeg"),
        media_event(21, "M2", NOW - 800, "image", "image/jpeg"),
        media_event(22, "D1", NOW - 700, "document", "application/pdf"),
        media_meta_event(23, "D1", json!({"file_name": "rapor.pdf", "pages": 2})),
        media_event(24, "GIF", NOW - 600, "video", "video/mp4"),
        media_meta_event(25, "GIF", json!({"seconds": 2, "width": 320, "height": 240, "animated": true})),
        media_event(26, "ONCE", NOW - 500, "image", "image/jpeg"),
        media_meta_event(27, "ONCE", json!({"view_once": true})),
        media_event(28, "VOICE", NOW - 400, "audio", "audio/ogg; codecs=opus"),
        media_meta_event(29, "VOICE", json!({"seconds": 7, "voice_note": true, "waveform_b64": waveform_b64()})),
    ]})
}

/// Ali sends a view-once photo, which reaches linked devices as a placeholder only.
fn placeholder_events() -> Value {
    json!({"handle": H, "events": [
        event(
            40,
            json!({"id": "VO1", "chat": ALI, "sender": ALI, "from_me": false, "timestamp_ms": NOW - 100,
                "body": "unsupported", "reactions": []}),
            "message",
        ),
        event(41, json!({"chat": ALI, "id": "VO1", "meta": {"unavailable": "view_once"}}), "message_meta"),
    ]})
}

/// A case on the sample store that also holds [`media_round_events`].
fn on_media_round(case: Case) -> Case {
    on_sample(case).after("wa_sync.apply_events", media_round_events())
}

fn viewed_req() -> Value {
    json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": "ONCE"})
}

fn played_req(client_id: &str, id: &str) -> Value {
    json!({"handle": H, "now_ms": NOW, "client_id": client_id, "chat": ALI, "id": id})
}

/// A case on the media store whose view-once photo was opened and whose voice note was played.
fn on_marked(case: Case) -> Case {
    on_media_round(case)
        .after("wa_sync.media_viewed", viewed_req())
        .after("wa_sync.mark_played", played_req("p1", "VOICE"))
}

/// A view-once voice note with its waveform, queued under `client_id`.
fn voice_send_req(client_id: &str) -> Value {
    json!({"handle": H, "now_ms": NOW, "client_id": client_id, "chat": ALI,
        "action": {"kind": "send_media", "file": "/data/user/0/dev.rosty.messages/files/outbox/v1.ogg",
            "media": "audio", "mime": "audio/ogg; codecs=opus", "size": 5000, "seconds": 7, "voice_note": true,
            "waveform_b64": waveform_b64(), "view_once": true}})
}

#[derive(Deserialize)]
struct ChatMediaReq {
    handle: i64,
    chat: String,
    limit: u32,
    /// Only media older than this row (`older` of a page, or the `timestamp_ms` and `id` of any row).
    #[serde(default)]
    before: Option<ThreadCursor>,
    /// Only media newer than this row (`newer` of a page, or any row). Alone: the page right above it.
    #[serde(default)]
    after: Option<ThreadCursor>,
    /// `image`, `video`, `document`, `audio`. Left out or empty: images and videos.
    #[serde(default)]
    kinds: Vec<Kind>,
}

fn chat_media(req: ChatMediaReq) -> Result<GalleryPage, FfiError> {
    with(req.handle, |store| {
        store.chat_media(
            &req.chat,
            req.limit,
            req.before.as_ref(),
            req.after.as_ref(),
            &req.kinds,
        )
    })
}

fn chat_media_cases() -> Vec<Case> {
    let ask = |label: &'static str, extra: Value| {
        let mut req = json!({"handle": H, "chat": ALI, "limit": 2});
        for (key, value) in extra.as_object().into_iter().flatten() {
            req[key.as_str()] = value.clone();
        }
        on_media_round(Case::named(label, req))
    };
    vec![
        // Images and videos, newest first; the view-once photo is left out.
        on_media_round(Case::new(json!({"handle": H, "chat": ALI, "limit": 2}))),
        ask(
            "older",
            json!({"before": {"timestamp_ms": NOW - 800, "id": "M2"}}),
        ),
        // A viewer opened on the oldest photo pages up.
        ask(
            "newer",
            json!({"after": {"timestamp_ms": NOW - 900, "id": "M1"}}),
        ),
        ask(
            "kinds",
            json!({"limit": 50, "kinds": ["document", "audio"]}),
        ),
        ask("not_gallery", json!({"kinds": ["sticker"]})),
        on_sample(Case::named(
            "empty",
            json!({"handle": H, "chat": ALI, "limit": 50}),
        )),
        // The LID of a chat that merged into its number reads the same gallery.
        on_merged(Case::named(
            "old_key",
            json!({"handle": H, "chat": ALI_LID, "limit": 50}),
        ))
        .after("wa_sync.apply_events", media_round_events()),
    ]
}

#[derive(Deserialize)]
struct MediaViewedReq {
    handle: i64,
    now_ms: i64,
    chat: String,
    id: String,
}

fn media_viewed(req: MediaViewedReq) -> Result<WithHandle<Viewed>, FfiError> {
    with(req.handle, |store| {
        store.media_viewed(&req.chat, &req.id, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

// ---- wa_sync.expire

#[derive(Deserialize)]
struct ExpireReq {
    handle: i64,
    now_ms: i64,
}

fn expire(req: ExpireReq) -> Result<WithHandle<Expired>, FfiError> {
    with(req.handle, |store| store.expire(req.now_ms)).map(|out| handled(req.handle, out))
}

/// A disappearing message (one minute) of Ali, sent two minutes ago, and one sent just now.
fn timed_events() -> Value {
    let timed = |seq, id: &str, ts: i64| {
        [
            event(seq, text_msg(ALI, id, ts, false, "kaybolacak"), "message"),
            event(
                seq + 1,
                json!({"chat": ALI, "id": id, "meta": {"expires_s": 60}}),
                "message_meta",
            ),
        ]
    };
    let mut events: Vec<Value> = timed(20, "T1", NOW - 120_000).into();
    events.extend(timed(22, "T2", NOW - 1_000));
    json!({"handle": H, "events": events})
}

fn expire_cases() -> Vec<Case> {
    vec![
        on_store(Case::new(json!({"handle": H, "now_ms": NOW})))
            .after("wa_sync.apply_events", timed_events()),
        on_store(Case::named("nothing", json!({"handle": H, "now_ms": NOW}))),
    ]
}

fn media_viewed_cases() -> Vec<Case> {
    let once = |id: &str| json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": id, "tap": true});
    vec![
        // Opened by a tap, downloaded, then viewed: the file is handed back to delete.
        on_media_round(Case::new(viewed_req()))
            .after("wa_sync.media_enqueue", once("ONCE"))
            .after(
                "wa_sync.media_settle",
                json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": "ONCE",
                    "outcome": {"kind": "ready", "path": "/media/once.jpg", "bytes": 1000}}),
            ),
        on_marked(Case::named("again", viewed_req())),
        on_media_round(Case::named(
            "not_view_once",
            json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": "M1"}),
        )),
        on_media_round(Case::named(
            "unknown",
            json!({"handle": H, "now_ms": NOW, "chat": ALI, "id": "nope"}),
        )),
    ]
}

#[derive(Deserialize)]
struct MarkPlayedReq {
    handle: i64,
    now_ms: i64,
    /// The caller's random id of this receipt; queuing it twice is one row.
    client_id: String,
    chat: String,
    /// The received voice note that was played.
    id: String,
}

fn mark_played(req: MarkPlayedReq) -> Result<WithHandle<Played>, FfiError> {
    with(req.handle, |store| {
        store.mark_played(&req.client_id, &req.chat, &req.id, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

fn mark_played_cases() -> Vec<Case> {
    vec![
        on_media_round(Case::new(played_req("p1", "VOICE"))),
        // Played before: nothing is queued again.
        on_marked(Case::named("again", played_req("p2", "VOICE"))),
        on_media_round(Case::named("not_a_voice_note", played_req("p1", "M1"))),
        on_media_round(Case::named("unknown", played_req("p1", "nope"))),
    ]
}

// ---- wa_sync.media_keep, wa_sync.media_reconcile, wa_sync.media_stats, wa_sync.media_cap

#[derive(Deserialize)]
struct MediaKeepReq {
    handle: i64,
    chat: String,
    id: String,
    /// Never evict the file. Left out: as it is.
    #[serde(default)]
    pinned: Option<bool>,
    /// Where the user saved a copy (a saved file is never evicted); `""` forgets the copy. Left out: as it is.
    #[serde(default)]
    saved_uri: Option<String>,
}

#[derive(Serialize)]
struct MediaKeptAns {
    media: MediaEntry,
}

fn media_keep(req: MediaKeepReq) -> Result<WithHandle<MediaKeptAns>, FfiError> {
    with(req.handle, |store| {
        store.media_keep(&req.chat, &req.id, req.pinned, req.saved_uri.as_deref())
    })
    .map(|media| handled(req.handle, MediaKeptAns { media }))
}

fn media_keep_cases() -> Vec<Case> {
    let cached = |case: Case| on_sample(case).after("wa_sync.media_set", media_set_req("G1"));
    vec![
        cached(Case::new(
            json!({"handle": H, "chat": GROUP, "id": "G1", "saved_uri": "content://media/external/images/media/42"}),
        )),
        cached(Case::named(
            "pinned",
            json!({"handle": H, "chat": GROUP, "id": "G1", "pinned": true}),
        )),
        on_sample(Case::named(
            "unknown",
            json!({"handle": H, "chat": GROUP, "id": "nope", "pinned": true}),
        )),
    ]
}

#[derive(Deserialize)]
struct ReconcileReq {
    handle: i64,
    /// Every file under the directories of full media and of outbox files (not the thumbnails), as absolute
    /// paths. Left out when the listing failed: only cut-off downloads are queued again.
    #[serde(default)]
    files: Option<Vec<String>>,
}

fn media_reconcile(req: ReconcileReq) -> Result<WithHandle<Reconciled>, FfiError> {
    with(req.handle, |store| {
        store.media_reconcile(req.files.as_deref())
    })
    .map(|out| handled(req.handle, out))
}

fn media_reconcile_cases() -> Vec<Case> {
    vec![
        // The cached file is gone, a download was cut off, and two files belong to nothing.
        on_sample(Case::new(json!({"handle": H, "files": [
            "/data/user/0/dev.rosty.messages/files/wa-media/inst1/files/A1/a.jpg.part",
            "/data/user/0/dev.rosty.messages/files/wa-media/inst1/files/old/stray.bin",
        ]})))
        .after("wa_sync.media_set", media_set_req("G1")),
        on_downloading(Case::named("no_listing", json!({"handle": H}))),
        // Everything in its place.
        on_sample(Case::named(
            "clean",
            json!({"handle": H, "files": ["/data/user/0/dev.rosty.messages/cache/wa-media/G1.jpg"]}),
        ))
        .after("wa_sync.media_set", media_set_req("G1")),
    ]
}

#[derive(Deserialize)]
struct MediaStatsReq {
    handle: i64,
    /// Now: files used in the last 24 hours count as exempt.
    #[serde(default)]
    now_ms: Option<i64>,
}

fn media_stats(req: MediaStatsReq) -> Result<MediaStats, FfiError> {
    with(req.handle, |store| store.media_stats(req.now_ms))
}

fn media_stats_cases() -> Vec<Case> {
    vec![
        on_sample(Case::new(json!({"handle": H, "now_ms": NOW + 1_000})))
            .after("wa_sync.media_set", media_set_req("G1"))
            .after(
                "wa_sync.outbox_enqueue",
                json!({"handle": H, "now_ms": NOW, "client_id": "m1", "chat": ALI,
                    "action": {"kind": "send_media", "file": "/data/user/0/dev.rosty.messages/files/out/foto.jpg",
                        "media": "image", "mime": "image/jpeg", "size": 34567}}),
            ),
        on_store(Case::named("empty", json!({"handle": H}))),
    ]
}

#[derive(Deserialize)]
struct MediaCapReq {
    handle: i64,
    /// Sets the cap in bytes. Without it and without `no_limit` the op only reads.
    #[serde(default)]
    cap_bytes: Option<u64>,
    /// Sets no limit.
    #[serde(default)]
    no_limit: bool,
}

#[derive(Serialize)]
struct MediaCapAns {
    /// The cap as stored now; `null`: no limit.
    cap_bytes: Option<u64>,
}

fn media_cap(req: MediaCapReq) -> Result<WithHandle<MediaCapAns>, FfiError> {
    if req.no_limit && req.cap_bytes.is_some() {
        return Err(FfiError::invalid(
            "cap_bytes and no_limit exclude each other",
        ));
    }
    with(req.handle, |store| {
        if req.no_limit {
            store.set_media_cap(None)?;
        } else if req.cap_bytes.is_some() {
            store.set_media_cap(req.cap_bytes)?;
        }
        store.media_cap()
    })
    .map(|cap_bytes| handled(req.handle, MediaCapAns { cap_bytes }))
}

fn media_cap_cases() -> Vec<Case> {
    vec![
        // Never set: 512 MiB.
        on_store(Case::new(json!({"handle": H}))),
        on_store(Case::named(
            "set",
            json!({"handle": H, "cap_bytes": 1_073_741_824_u64}),
        )),
        on_store(Case::named(
            "no_limit",
            json!({"handle": H, "no_limit": true}),
        )),
        on_store(Case::named(
            "both",
            json!({"handle": H, "cap_bytes": 1, "no_limit": true}),
        )),
    ]
}

// ---- wa_sync.note_status, wa_sync.account_changed, wa_sync.history, wa_sync.wipe

#[derive(Deserialize)]
struct NoteStatusReq {
    handle: i64,
    now_ms: i64,
    /// The gateway's epoch: `InstanceStatus.epoch`, or the `epoch` of the socket's first frame or of a `resync`.
    #[serde(default)]
    epoch: Option<String>,
    /// `InstanceStatus.account_epoch`.
    #[serde(default)]
    account_epoch: Option<u32>,
    /// `InstanceStatus.own_jid`.
    #[serde(default)]
    own_pn: Option<String>,
    /// `InstanceStatus.own_lid`.
    #[serde(default)]
    own_lid: Option<String>,
    /// `InstanceStatus.history`: `{state, progress?, chats?, messages?, oldest_ms?}`.
    #[serde(default)]
    history: Option<HistoryReport>,
}

fn note_status(req: NoteStatusReq) -> Result<WithHandle<StatusNoted>, FfiError> {
    let note = StatusNote {
        epoch: req.epoch.as_deref(),
        account_epoch: req.account_epoch,
        own_pn: req.own_pn.as_deref(),
        own_lid: req.own_lid.as_deref(),
        history: req.history.as_ref(),
    };
    with(req.handle, |store| store.note_status(&note, req.now_ms))
        .map(|out| handled(req.handle, out))
}

const OWN: &str = "100000000009@s.whatsapp.net";
const OTHER_OWN: &str = "100000000008@s.whatsapp.net";

fn status_req(epoch: &str, account_epoch: u32, own: &str) -> Value {
    json!({"handle": H, "now_ms": NOW + 2_000, "epoch": epoch, "account_epoch": account_epoch, "own_pn": own})
}

/// A case on the sample store that knows its gateway (`e1`), its account and a cached photo.
fn on_linked(case: Case) -> Case {
    on_sample(case)
        .after("wa_sync.note_status", status_req("e1", 1, OWN))
        .after("wa_sync.media_set", media_set_req("G1"))
}

fn note_status_cases() -> Vec<Case> {
    vec![
        // The first status: the epoch, the account and the history progress are stored. The store already
        // applied events of a gateway that named no epoch, so this is a gap.
        on_sample(Case::new(
            json!({"handle": H, "now_ms": NOW + 2_000, "epoch": "e1", "account_epoch": 1,
            "own_pn": OWN, "own_lid": "777000000000009@lid",
            "history": {"state": "syncing", "progress": 40, "chats": 12, "messages": 3400,
                "oldest_ms": NOW - 86_400_000}}),
        )),
        // The same gateway and the same account: nothing changes.
        on_linked(Case::named("same", status_req("e1", 1, OWN))),
        // The gateway restarted.
        on_linked(Case::named("gap", status_req("e2", 1, OWN))),
        // Another account on the instance: the store is emptied, the cached file is named for deletion.
        on_linked(Case::named(
            "account_changed",
            status_req("e1", 2, OTHER_OWN),
        )),
        // An old gateway names nothing.
        on_linked(Case::named(
            "nothing",
            json!({"handle": H, "now_ms": NOW + 2_000}),
        )),
    ]
}

#[derive(Deserialize)]
struct AccountChangedReq {
    handle: i64,
    now_ms: i64,
    /// The number the instance was linked to before, when the gateway knows it.
    #[serde(default)]
    old_pn: Option<String>,
    new_pn: String,
}

fn account_changed(req: AccountChangedReq) -> Result<WithHandle<IdentityApplied>, FfiError> {
    with(req.handle, |store| {
        store.note_account_event(req.old_pn.as_deref(), &req.new_pn, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

fn account_changed_cases() -> Vec<Case> {
    let announced = json!({"handle": H, "now_ms": NOW + 3_000, "old_pn": OWN, "new_pn": OTHER_OWN});
    vec![
        on_linked(Case::new(announced.clone())),
        // The status said so first and the store was emptied then; the announcement sweeps what arrived since.
        on_linked(Case::named("swept", announced))
            .after("wa_sync.note_status", status_req("e1", 2, OTHER_OWN))
            .after(
                "wa_sync.apply_events",
                json!({"handle": H, "events": [
                    event(9, text_msg(ALI, "A9", NOW + 2_500, false, "eski hesaptan"), "message")]}),
            ),
        // The same account: nothing to do.
        on_linked(Case::named(
            "same",
            json!({"handle": H, "now_ms": NOW + 3_000, "old_pn": OWN, "new_pn": OWN}),
        )),
    ]
}

#[derive(Serialize)]
struct HistoryAns {
    /// What the gateway last reported; `null` before the first report.
    history: Option<HistoryProgress>,
}

fn history(req: HandleReq) -> Result<HistoryAns, FfiError> {
    with(req.handle, |store| store.history()).map(|history| HistoryAns { history })
}

fn history_cases() -> Vec<Case> {
    vec![
        on_store(Case::new(json!({"handle": H}))).after(
            "wa_sync.note_status",
            json!({"handle": H, "now_ms": NOW, "history": {"state": "syncing", "progress": 40, "chats": 12,
                "messages": 3400, "oldest_ms": NOW - 86_400_000}}),
        ),
        on_store(Case::named("done", json!({"handle": H}))).after(
            "wa_sync.note_status",
            json!({"handle": H, "now_ms": NOW, "history": {"state": "done", "progress": null, "chats": 40,
                "messages": 120_000, "oldest_ms": null}}),
        ),
        on_store(Case::named("none", json!({"handle": H}))),
    ]
}

#[derive(Deserialize)]
struct WipeReq {
    /// The open store to wipe. The handle is closed; other handles of the same store answer "not found".
    #[serde(default)]
    handle: Option<i64>,
    /// Without a handle: the instance whose store files are deleted, open or not, in `dir`.
    #[serde(default)]
    instance_id: Option<String>,
    /// The directory of the store files; `core.init`'s data dir when left out.
    #[serde(default)]
    dir: Option<String>,
}

fn wipe(req: WipeReq) -> Result<Wiped, FfiError> {
    if let Some(handle) = req.handle {
        let shared = shared(handle)?;
        let store = lock(&shared).take().ok_or_else(|| wiped(handle))?;
        handles::global().release(handle);
        return store.wipe().map_err(map_error);
    }
    let instance_id = req
        .instance_id
        .ok_or_else(|| FfiError::invalid("wipe names a handle or an instance_id"))?;
    let dir = store_dir(req.dir.as_deref())?;
    let path = dir.join(wa_sync::file_name(&instance_id));
    // The map stays locked: nobody opens the file while it is deleted.
    let mut open = open_files();
    let live = open
        .remove(&path)
        .and_then(|store| store.upgrade())
        .and_then(|store| lock(&store).take());
    let store = match live {
        Some(store) => Some(store),
        // Opened only to read which media files it names; a file that does not open is deleted all the same.
        None if path.exists() => Store::open(&dir, &instance_id).ok(),
        None => None,
    };
    match store {
        Some(store) => store.wipe().map_err(map_error),
        None => Ok(Wiped {
            deleted: wa_sync::delete_files(&dir, &instance_id).map_err(map_error)?,
            media_files: Vec::new(),
        }),
    }
}

fn wipe_cases() -> Vec<Case> {
    vec![
        // A cached file and a file the outbox was about to send.
        on_linked(Case::new(json!({"handle": H}))).after(
            "wa_sync.outbox_enqueue",
            json!({"handle": H, "now_ms": NOW, "client_id": "m1", "chat": ALI,
                "action": {"kind": "send_media", "file": "/data/user/0/dev.rosty.messages/files/out/foto.jpg",
                    "media": "image", "mime": "image/jpeg", "size": 34567}}),
        ),
        // An instance that was never linked here: nothing to delete.
        Case::named(
            "never_linked",
            json!({"instance_id": "nobody", "dir": "/data/user/0/dev.rosty.fixture/none"}),
        ),
        Case::named("no_target", json!({})),
    ]
}

// ---- wa_sync.identity_resolve, wa_sync.rekeyed_since

#[derive(Deserialize)]
struct ResolveReq {
    handle: i64,
    /// A JID as it arrived or as a screen holds it, device allowed.
    id: String,
}

/// `null` when `id` is not a JID.
fn identity_resolve(req: ResolveReq) -> Result<Option<ResolvedId>, FfiError> {
    with(req.handle, |store| store.identity_resolve(&req.id))
}

fn identity_resolve_cases() -> Vec<Case> {
    vec![
        // A LID with a device whose number is known: the key is the number, `wire` goes back on the wire.
        on_merged(Case::new(
            json!({"handle": H, "id": "777000000000001:2@lid"}),
        )),
        on_merged(Case::named("number", json!({"handle": H, "id": ALI}))),
        // Seen for the first time: a person of its own, keyed by the LID until a number is learned.
        on_store(Case::named(
            "unknown_lid",
            json!({"handle": H, "id": "777000000000005@lid"}),
        )),
        on_store(Case::named("group", json!({"handle": H, "id": GROUP}))),
        on_store(Case::named(
            "not_an_id",
            json!({"handle": H, "id": "merhaba"}),
        )),
    ]
}

#[derive(Deserialize)]
struct RekeyedReq {
    handle: i64,
    /// The `seq` of the last key change the caller followed; 0 for all of them.
    #[serde(default)]
    seq: u64,
}

#[derive(Serialize)]
struct RekeyedAns {
    /// Oldest first.
    rekeyed: Vec<ChatRekeyed>,
}

fn rekeyed_since(req: RekeyedReq) -> Result<RekeyedAns, FfiError> {
    with(req.handle, |store| store.rekeyed_since(req.seq)).map(|rekeyed| RekeyedAns { rekeyed })
}

fn rekeyed_since_cases() -> Vec<Case> {
    vec![
        on_merged(Case::new(json!({"handle": H, "seq": 0}))),
        on_merged(Case::named("followed", json!({"handle": H, "seq": 1}))),
        on_sample(Case::named("none", json!({"handle": H}))),
    ]
}

// ---- wa_sync.identity_numbers

#[derive(Serialize)]
struct NumbersAns {
    /// `+<digits>`: the current number first, then the former ones, newest first.
    numbers: Vec<String>,
}

fn identity_numbers(req: ResolveReq) -> Result<NumbersAns, FfiError> {
    with(req.handle, |store| Ok(store.identity_numbers(&req.id)))
        .map(|numbers| NumbersAns { numbers })
}

fn identity_numbers_cases() -> Vec<Case> {
    let moved = json!({"handle": H, "events": [event(
        11,
        json!({"old_pn": ALI, "new_pn": "100000000002@s.whatsapp.net", "new_lid": ALI_LID}),
        "identity_number_changed",
    )]});
    vec![
        // Asked by the LID: the number the person is known by.
        on_merged(Case::new(json!({"handle": H, "id": ALI_LID}))),
        // After a number change: the new number first, then the one a contact may still hold.
        on_merged(Case::named("former", json!({"handle": H, "id": ALI_LID})))
            .after("wa_sync.apply_events", moved),
        // A LID nobody mapped has no number, whatever its digits.
        on_store(Case::named(
            "unknown_lid",
            json!({"handle": H, "id": "777000000000005@lid"}),
        )),
        // A number the store has not met is its own number.
        on_store(Case::named(
            "unknown_number",
            json!({"handle": H, "id": "100000000009:3@s.whatsapp.net"}),
        )),
        on_store(Case::named("group", json!({"handle": H, "id": GROUP}))),
    ]
}

// ---- wa_sync.identity_catch_up, wa_sync.identity_observe

#[derive(Deserialize)]
struct CatchUpReq {
    handle: i64,
    /// One page of `GET /v1/identity/mappings` (`wa.identity_mappings`): `items` and `next`.
    #[serde(flatten)]
    page: IdentityMappings,
}

fn identity_catch_up(req: CatchUpReq) -> Result<WithHandle<IdentityCaughtUp>, FfiError> {
    with(req.handle, |store| store.identity_catch_up(&req.page)).map(|out| handled(req.handle, out))
}

fn mapping(lid: &str, pn: &str, source: &str) -> Value {
    json!({"lid": lid, "pn": pn, "source": source, "learned_ms": NOW - 1_000})
}

fn identity_catch_up_cases() -> Vec<Case> {
    let first = json!({"handle": H, "items": [mapping(ALI_LID, ALI, "usync")], "next": "41"});
    vec![
        // The first page joins the two chats of Ali and leaves a cursor; `more` asks for the next page.
        on_two_chats(Case::new(first.clone())),
        // An empty page: the store has every pair and keeps the cursor of the page before.
        on_two_chats(Case::named(
            "done",
            json!({"handle": H, "items": [], "next": null}),
        ))
        .after("wa_sync.identity_catch_up", first),
        on_merged(Case::named(
            "rejected",
            json!({"handle": H, "items": [
                mapping(ALI_LID, "100000000002@s.whatsapp.net", "history"),
                mapping("777000000000003@lid", "100000000003@s.whatsapp.net", "from_the_future"),
            ], "next": "43"}),
        )),
    ]
}

#[derive(Deserialize)]
struct ObserveReq {
    handle: i64,
    now_ms: i64,
    /// `wa.status`, the whole answer: the account's own number and LID, the gateway's `epoch`, the
    /// `account_epoch` and the `history` report (what `wa_sync.note_status` takes field by field).
    #[serde(default)]
    status: Option<InstanceStatus>,
    /// `wa.group` / `wa.groups`: members named by LID and number.
    #[serde(default)]
    groups: Vec<GroupInfo>,
    /// `wa.profiles` / `wa.refresh_profiles`: the LID of a number.
    #[serde(default)]
    profiles: Vec<WaProfile>,
}

fn identity_observe(req: ObserveReq) -> Result<WithHandle<IdentityApplied>, FfiError> {
    let seen = Observed {
        status: req.status.as_ref(),
        groups: &req.groups,
        profiles: &req.profiles,
    };
    with(req.handle, |store| {
        store.identity_observe(&seen, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

fn identity_observe_cases() -> Vec<Case> {
    vec![
        on_two_chats(Case::new(json!({"handle": H, "now_ms": NOW,
            "status": {"instance_id": "inst1", "state": "connected", "own_jid": "100000000009@s.whatsapp.net",
                "push_name": "Ben", "own_lid": "777000000000009@lid"},
            "groups": [{"jid": GROUP, "subject": "Aile", "addressing_mode": "lid", "participants": [
                {"jid": ALI_LID, "admin": true, "name": "Ali", "lid": ALI_LID, "pn": ALI},
                {"jid": "777000000000003@lid", "admin": false, "lid": "777000000000003@lid"},
            ]}],
            "profiles": [{"jid": "100000000002@s.whatsapp.net", "push_name": "veli", "contact_name": null,
                "about": null, "picture_id": null, "business": false, "verified_name": null, "fetched_ms": NOW,
                "lid": "777000000000002@lid"}],
        }))),
        on_sample(Case::named("nothing", json!({"handle": H, "now_ms": NOW}))),
        // The whole `wa.status` answer of a gateway that restarted: another epoch is a gap (`resync`), and the
        // history report is stored.
        on_linked(Case::named(
            "gateway",
            json!({"handle": H, "now_ms": NOW + 3_000,
                "status": {"instance_id": "inst1", "state": "connected", "own_jid": OWN, "push_name": "Ben",
                    "epoch": "e2", "account_epoch": 1, "history": {"state": "done", "progress": 100,
                    "chats": 412, "messages": 120_000, "oldest_ms": NOW - 86_400_000}}}),
        )),
        // The status names a raised account epoch: the store is emptied, as `wa_sync.note_status` does.
        on_linked(Case::named(
            "account_changed",
            json!({"handle": H, "now_ms": NOW + 3_000,
                "status": {"instance_id": "inst1", "state": "connected", "own_jid": OTHER_OWN,
                    "push_name": null, "epoch": "e1", "account_epoch": 2}}),
        )),
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
    fn a_catch_up_joins_two_chats_and_its_cursor_and_the_merge_survive_close() {
        let dir = temp_dir("identity");
        let open = || {
            ok("wa_sync.open", json!({"dir": dir, "instance_id": "inst1"}))["handle"]
                .as_i64()
                .expect("handle")
        };
        let on = |handle: i64, mut request: Value| {
            request["handle"] = json!(handle);
            request
        };
        let handle = open();
        ok("wa_sync.apply_events", on(handle, sample_events()));
        ok("wa_sync.apply_events", on(handle, lid_events()));
        assert_eq!(
            ok("wa_sync.plan", json!({"handle": handle})).get("identity_since"),
            None
        );
        let caught = ok(
            "wa_sync.identity_catch_up",
            json!({"handle": handle, "items": [mapping(ALI_LID, ALI, "usync")], "next": "41"}),
        );
        assert_eq!(caught["since"], "41");
        assert_eq!(caught["more"], true);
        assert_eq!(
            caught["rekeyed"],
            json!([{"seq": 1, "from_key": ALI_LID, "into_key": ALI}])
        );
        ok("wa_sync.close", json!({"handle": handle}));

        let handle = open();
        // The next catch-up goes on from the cursor, and a consumer that was not running finds the step.
        assert_eq!(
            ok("wa_sync.plan", json!({"handle": handle}))["identity_since"],
            "41"
        );
        assert_eq!(
            ok("wa_sync.rekeyed_since", json!({"handle": handle}))["rekeyed"],
            caught["rekeyed"]
        );
        // A reader that still holds the LID reads the one chat, under its key.
        let thread = ok(
            "wa_sync.thread",
            json!({"handle": handle, "chat": ALI_LID, "limit": 10}),
        );
        let rows = thread["items"].as_array().expect("items");
        assert_eq!(rows.len(), 4);
        assert!(rows.iter().all(|row| row["chat"] == ALI));
        assert_eq!(rows[0]["wire_sender"], ALI_LID);
        let resolved = ok(
            "wa_sync.identity_resolve",
            json!({"handle": handle, "id": ALI_LID}),
        );
        assert_eq!(resolved["key"], ALI);
        handles::global().release(handle);
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

    fn files_in(dir: &std::path::Path) -> Vec<String> {
        let mut names: Vec<String> = std::fs::read_dir(dir)
            .map(|entries| {
                entries
                    .filter_map(|entry| {
                        Some(entry.ok()?.file_name().to_string_lossy().into_owned())
                    })
                    .collect()
            })
            .unwrap_or_default();
        names.sort();
        names
    }

    #[test]
    fn wipe_deletes_the_store_files_and_every_handle_of_it_stops_answering() {
        let dir = temp_dir("wipe");
        let open = |instance: &str| {
            ok("wa_sync.open", json!({"dir": dir, "instance_id": instance}))["handle"]
                .as_i64()
                .expect("handle")
        };
        let on = |handle: i64, mut request: Value| {
            request["handle"] = json!(handle);
            request
        };
        let (ui, worker, other) = (open("inst1"), open("inst1"), open("inst2"));
        ok("wa_sync.apply_events", on(ui, sample_events()));
        ok("wa_sync.media_set", on(ui, media_set_req("G1")));
        assert!(files_in(&dir).contains(&wa_sync::file_name("inst1")));

        // By handle: the store is closed and its files are gone; the other instance is untouched.
        let wiped = ok("wa_sync.wipe", json!({"handle": ui}));
        assert_eq!(
            wiped["media_files"],
            json!(["/data/user/0/dev.rosty.messages/cache/wa-media/G1.jpg"])
        );
        assert!(
            wiped["deleted"]
                .as_array()
                .expect("deleted")
                .iter()
                .any(|file| file.as_str().expect("path").ends_with("wa-sync-inst1.db"))
        );
        assert!(
            files_in(&dir)
                .iter()
                .all(|name| name.starts_with("wa-sync-inst2"))
        );
        for handle in [ui, worker] {
            assert_eq!(
                err("wa_sync.chats", json!({"handle": handle})).kind,
                ErrorKind::NotFound
            );
        }
        assert_eq!(
            err("wa_sync.wipe", json!({"handle": worker})).kind,
            ErrorKind::NotFound
        );
        handles::global().release(worker);

        // Linking again starts a new, empty file; it is not the wiped store.
        let again = open("inst1");
        assert_eq!(
            ok("wa_sync.chats", json!({"handle": again}))["chats"],
            json!([])
        );
        ok("wa_sync.apply_events", on(again, sample_events()));
        ok("wa_sync.media_set", on(again, media_set_req("G1")));

        // By instance, while it is open: the same, and its handle stops answering.
        let wiped = ok("wa_sync.wipe", json!({"dir": dir, "instance_id": "inst1"}));
        assert_eq!(wiped["media_files"].as_array().expect("files").len(), 1);
        assert_eq!(
            err("wa_sync.plan", json!({"handle": again})).kind,
            ErrorKind::NotFound
        );
        handles::global().release(again);

        // By instance, while it is closed: opened only to read its media files, then deleted.
        ok("wa_sync.apply_events", on(other, sample_events()));
        ok("wa_sync.media_set", on(other, media_set_req("G1")));
        ok("wa_sync.close", json!({"handle": other}));
        let wiped = ok("wa_sync.wipe", json!({"dir": dir, "instance_id": "inst2"}));
        assert_eq!(wiped["media_files"].as_array().expect("files").len(), 1);
        assert_eq!(files_in(&dir), Vec::<String>::new());
        // Nothing left: wiping again is not an error.
        let nothing = ok("wa_sync.wipe", json!({"dir": dir, "instance_id": "inst2"}));
        assert_eq!(nothing, json!({"deleted": [], "media_files": []}));
        assert_eq!(files_in(&dir), Vec::<String>::new());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn the_epoch_the_history_and_a_queued_download_survive_close() {
        let dir = temp_dir("status");
        let open = || {
            ok("wa_sync.open", json!({"dir": dir, "instance_id": "inst1"}))["handle"]
                .as_i64()
                .expect("handle")
        };
        let on = |handle: i64, mut request: Value| {
            request["handle"] = json!(handle);
            request
        };
        let handle = open();
        let status = json!({"now_ms": NOW, "epoch": "e1", "account_epoch": 1, "own_pn": OWN,
            "history": {"state": "syncing", "progress": 40, "chats": 12, "messages": 3400}});
        assert_eq!(
            ok("wa_sync.note_status", on(handle, status.clone()))["gap"],
            false
        );
        ok("wa_sync.apply_events", on(handle, sample_events()));
        ok(
            "wa_sync.media_enqueue",
            on(handle, enqueue_media_req(true, None)),
        );
        ok(
            "wa_sync.media_due",
            json!({"handle": handle, "now_ms": NOW + 10}),
        );
        ok("wa_sync.close", json!({"handle": handle}));

        let handle = open();
        assert_eq!(
            ok("wa_sync.history", json!({"handle": handle}))["history"]["progress"],
            40
        );
        // The download that was cut off by the close is queued again.
        assert_eq!(
            ok("wa_sync.media_reconcile", json!({"handle": handle}))["requeued"],
            1
        );
        let same = ok("wa_sync.note_status", on(handle, status));
        assert_eq!(
            (&same["gap"], &same["account_changed"]),
            (&json!(false), &json!(false))
        );
        // Another epoch after the close: the gap is seen, and the stored messages stay.
        let restarted = ok(
            "wa_sync.note_status",
            json!({"handle": handle, "now_ms": NOW + 1, "epoch": "e2"}),
        );
        assert_eq!(restarted["gap"], true);
        assert_eq!(
            ok("wa_sync.plan", json!({"handle": handle}))["after_seq"],
            Value::Null
        );
        assert_eq!(
            ok("wa_sync.chats", json!({"handle": handle}))["chats"]
                .as_array()
                .expect("chats")
                .len(),
            2
        );
        handles::global().release(handle);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_wrong_handle_is_not_found_for_every_op() {
        let foreign = handles::global().insert(7u8);
        for spec in OPS.iter().filter(|spec| spec.name != "wa_sync.open") {
            for handle in [0, foreign] {
                let mut request = json!({"handle": handle, "now_ms": 0, "client_id": "c", "chat": "c", "id": "i",
                    "limit": 1, "events": [], "chats": [], "messages": [], "text": "", "max_bytes": 0,
                    "seq": 0, "items": [], "network": "wifi", "new_pn": "x",
                    "action": {"kind": "send_text", "text": "x"}, "outcome": {"kind": "sent"}});
                // A download has other outcomes than a send.
                if spec.name == "wa_sync.media_settle" {
                    request["outcome"] = json!({"kind": "cancelled"});
                }
                let error = err(spec.name, request);
                assert_eq!(error.kind, ErrorKind::NotFound, "{}", spec.name);
            }
        }
        handles::global().release(foreign);
    }
}

// ---- groups (E6-WA R6)

#[derive(Deserialize)]
struct GroupStoreReq {
    handle: i64,
    now_ms: i64,
    /// The gateway's answer (`wa.group`).
    group: GroupInfo,
}

fn group_store(req: GroupStoreReq) -> Result<WithHandle<GroupStored>, FfiError> {
    with(req.handle, |store| {
        store.group_store(&req.group, req.now_ms)
    })
    .map(|out| handled(req.handle, out))
}

#[derive(Deserialize)]
struct GroupReadReq {
    handle: i64,
    jid: String,
    /// My ids (number and LID).
    #[serde(default)]
    own: Vec<String>,
    /// A name or the digits of a number.
    #[serde(default)]
    query: Option<String>,
    #[serde(default)]
    offset: u32,
    limit: u32,
}

fn group_read(req: GroupReadReq) -> Result<WithHandle<GroupPage>, FfiError> {
    with(req.handle, |store| {
        store.group_read(
            &req.jid,
            &req.own,
            req.query.as_deref(),
            req.offset,
            req.limit,
        )
    })
    .map(|out| handled(req.handle, out))
}

/// A group of four with me as an admin, a member known only by LID, announcement mode on.
fn stored_group() -> Value {
    json!({"jid": GROUP, "subject": "Aile", "announce": true, "locked": true, "size": 4,
    "created_ms": NOW - 86_400_000, "creator": ALI,
    "community": {"kind": "sub", "parent": "120000000000000000@g.us"},
    "participants": [
        {"jid": ALI, "admin": true, "role": "superadmin", "name": "Ali", "lid": ALI_LID, "pn": ALI},
        {"jid": "100000000002@s.whatsapp.net", "admin": false, "role": "member", "name": "Bora"},
        {"jid": "777000000000003@lid", "admin": false, "role": "member", "lid": "777000000000003@lid"},
        {"jid": "100000000009@s.whatsapp.net", "admin": true, "role": "admin"}
    ]})
}

fn group_store_cases() -> Vec<Case> {
    vec![on_store(Case::new(
        json!({"handle": H, "now_ms": NOW, "group": stored_group()}),
    ))]
}

fn group_read_cases() -> Vec<Case> {
    let own = json!(["100000000009@s.whatsapp.net", "777000000000009@lid"]);
    vec![
        on_store(Case::new(
            json!({"handle": H, "jid": GROUP, "own": own, "limit": 100}),
        ))
        .after(
            "wa_sync.group_store",
            json!({"handle": H, "now_ms": NOW, "group": stored_group()}),
        ),
        on_store(Case::named(
            "unknown",
            json!({"handle": H, "jid": GROUP, "own": own, "limit": 100}),
        )),
    ]
}
