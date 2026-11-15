# E6 WhatsApp cache: one on-device message and media cache for Phone and Messages

Read-only research, 2026-11-15, repo HEAD `50e05ebe`. Nothing was built or run. Paths are relative to
`/home/user/rovsti-apps` unless absolute. Library claims cite `whatsapp-rust 0.7.0` from crates.io (pinned by the root
`Cargo.toml` line 66 and `Cargo.lock`), read at `~/.cargo/registry/src/*/whatsapp-rust-0.7.0`, `wacore-0.7.0` and
`waproto-0.7.0`. No web lookups were made: everything about Baileys, whatsmeow and mautrix-whatsapp is marked
"unverified, from memory".

## 1. What exists today

### 1.1 Rust store: `crates/rosty_app_core/src/wa_sync/`

Platform-free (no clock, no network). Kotlin makes the gateway calls and hands answers in with the time.

- **File**: one SQLite file per linked instance, `wa-sync-<instance>.db` (`store.rs`, `file_name`; characters outside
  `[A-Za-z0-9_-]` become `~xx`). Opened with `journal_mode=WAL`, `synchronous=NORMAL`, 5 s busy timeout
  (`Store::open_file`, `Store::prepare`). The file remembers its instance in `sync_state.instance_id` and refuses
  another (`Error::InstanceMismatch`).
- **Schema** (`schema.rs`, `SCHEMA_VERSION = 3`):
  - v1: `sync_state` (single row: `instance_id`, `last_seq`, `needs_resync`, `chats_synced_ms`); `chats` (PK `jid`;
    name, push_name, is_group, unread, pinned, archived, muted_until_ms, marked_unread, picture_id, `hidden`,
    `cleared_ts`); `chat_sync` (PK `jid`; newest_ts/newest_id/oldest_ts, `reached_start`, `gap_open`, `gap_floor_ts`,
    `gap_before`); `messages` (PK `(chat, id)`, WITHOUT ROWID; sender, sender_name, from_me, ts, kind, text, mime, size,
    quoted_id/quoted_json, reactions JSON, status, edited_ms, edit_history JSON, deleted/deleted_at/deleted_by,
    forwarded, starred, media_json, extra_json, `thumb BLOB`, `media_path`, `media_bytes`, `media_used_ms`). Indexes
    `messages_by_time (chat, ts DESC, id DESC)` and partial `messages_cached_media (media_used_ms) WHERE media_path IS
    NOT NULL`.
  - v2: `messages.client_id` + partial index `messages_by_client`; `outbox` table (seq AUTOINCREMENT, client_id UNIQUE,
    chat, kind, payload JSON, created_ms, attempts, next_attempt_ms, state, last_error, attempted_ms, maybe_sent);
    index `outbox_by_chat`.
  - v3: partial index `messages_starred (ts DESC, chat DESC, id DESC) WHERE starred = 1`.
- **Migrations**: `PRAGMA user_version` = number of migrations applied; `schema::migrate` runs each missing script in
  its own transaction; a file from a newer build is refused untouched (`Error::NewerSchema`). There is no downgrade
  and no backup-before-migrate.
- **Apply/merge** (`apply.rs`): every write is an idempotent upsert by `(chat, id)`. `merge()` never removes content:
  a delete sets `deleted`, `deleted_at`, `deleted_by` and keeps text/quote/media; a changed text moves the old text
  into `edit_history`; ticks only move forward (`upgrade_status`, `DeliveryStatus::merge`). `ChatDeleted` sets
  `hidden`, `ChatCleared` sets `cleared_ts`; rows stay. `ChatMerged {from, into}` runs `merge_chats` (moves messages
  and outbox rows from the LID chat to the phone-number chat, deletes the LID chat row, recomputes the cursor, sets
  `needs_resync`). `HistorySynced` resets every `reached_start` and sets `needs_resync`. Status chats are skipped.
  Presence, typing, calls, group changes, scheduled items are not stored.
- **Cursors** (`plan.rs`, `store.rs`): one global event cursor (`sync_state.last_seq`) plus a per-chat window
  (`chat_sync`). `plan::accepts` skips events at or before `last_seq`; a `resync` event is always accepted.
  `Store::plan` returns `{after_seq, refresh_chats, fetch}`.
- **Events vs REST reconciliation**: on a gap (`resync`), `Store::store_chats` compares each chat's gateway `last`
  message with the stored newest (`plan::chat_gap`) and opens a gap `{floor_ts, before}`; `Store::store_page` stores
  `GET /v1/chats/{jid}/messages?before=&limit=` pages (newest first, 1..200, default 50) and closes the gap when a
  page reaches `floor_ts` or comes back short (`plan::after_page`). `next_before` re-asks the oldest millisecond so
  page edges never skip. A new page row also reconciles outbox echoes (`outbox::reconcile_echo`). `refresh_recent_ms`
  refetches one page of recently active chats to pick up edits/deletes/ticks an event gap hid.
- **Paging for readers** (`query.rs`): `Store::chats`, `Store::thread(chat, limit, before)` (first page is prefixed
  with outbox sends), `Store::starred`, `Store::message`. `Store::older` plans the page before `oldest_ts` unless
  `reached_start`.
- **Outbox** (`outbox.rs`): actions `SendText`, `SendMedia`, `Edit`, `Revoke`, `React`, `MarkRead`, `Star`; backoff
  2 s to 300 s (`BACKOFF_BASE_MS`, `BACKOFF_MAX_MS`), sending lease 120 s, `maybe_sent` for attempts with no definite
  answer.
- **Media bookkeeping** (`media.rs`): Rust owns only the *index*: `set_thumbnail` (blob in the message row, kept for
  good), `set_media_file(path, bytes, now)`, `media(used_ms)` (touches LRU), `media_bytes()`, `media_evict(max_bytes)`
  (clears `media_path` of least recently used rows and returns the paths; the caller deletes files). There are no
  pin/star exemptions, no per-type rules, no download state and no content hash.

### 1.2 FFI: `crates/rosty_ffi/src/ops/wa_sync.rs`, `crates/rosty_ffi/OPS.md` (lines 150-176)

`wa_sync.open {instance_id, dir?, memory?}` returns a handle; opening the same path twice **in one process** shares
one `Arc<Mutex<Store>>` (`open_files()` map). Ops: `close`, `plan`, `apply_events`, `store_chats`, `store_page`,
`older`, `chats`, `thread`, `message`, `starred`, `outbox_enqueue`, `outbox_due`, `outbox_settle`, `outbox_cancel`,
`outbox_edit`, `outbox_retry`, `outbox`, `media_set`, `media`, `media_evict`. No op touches the network. `dir`
defaults to the core's `data_dir` (`state::dirs()`).

### 1.3 Kotlin

- `android/core-bridge/.../ops/WaSyncOps.kt`, `WaSyncStore.kt`: typed wrapper over the ops (shared module, good).
- **Everything else lives inside Messages** (`android/messages/src/main/kotlin/dev/rosty/messages/wa/`):
  `WaOffline.kt` (opens the store for the instance in the link, runs the socket after `plan.after_seq`, batches
  events up to 200, catch-up, refresh on `Dropped` with `RECENT_MS = 15 min`, outbox drain with a 1.5 s replay
  grace), `WaSyncRunner.kt` (`catchUp`, `refresh`, `pages` with `MAX_PAGES_PER_CHAT = 40`, `older`), `WaOutbox.kt`,
  `WaOutboxWorker.kt` (WorkManager wake), `StoreWaChats.kt` (inbox and search candidates read from the store),
  `WaMedia.kt`, `WaWiring.kt`. Phone does not use `WaSyncStore` at all (grep: only `core-bridge` and `messages`).
- **Media files are owned by Kotlin in Messages** (`WaMedia.kt`): directory `filesDir/wa-media/<instance>/`
  (`MessagesContainer.kt:181`) with `thumbs/<id>.jpg`, `files/<id>/<name>`, `out/<uuid>/<name>`. Thumbnails: store
  blob, else `GET /v1/messages/{id}/thumbnail`, then written to a file. Full files: downloaded on tap (`fetch`), one
  at a time (`Mutex`), recorded with `set_media_file`, then `media_evict(capBytes)`; `DEFAULT_CAP_BYTES = 512 MiB`,
  not user-configurable today. `MAX_SEND_BYTES = 16 MiB`. Downloads arrive as `BytesAnswer` (base64 up to 1 MiB, else
  a temp file path which is renamed into place); there is no `.part` file, no resume, no checksum.
- **Where the store lives**: `Rust.init` is given `context.noBackupFilesDir` (`android/shell/.../ShellContainer.kt:110`),
  so the DB is `/data/user/0/dev.rosty.messages/no_backup/wa-sync-<instance>.db`: per app, per instance. All three
  apps have `android:allowBackup="false"`.
- **Link / connect key**: `whatsapp-link.json` in the same per-app data dir (`crates/rosty_app_core/src/wa_link/link.rs`,
  `LINK_FILE`, `StoredLink {gateway_url, instance_id, key}`), read and written through `wa.link_read` /
  `wa.link_write` by `android/wa/.../WaLinkStore.kt`. The key is a bearer secret, hashed on the gateway
  (`keys.hash`). Each app keeps its own copy of the link; the "shared instance" is simply two apps holding keys of
  the same `instance_id` (minted with `mintKey`, pasted or scanned in `WhatsAppSettingsViewModel`).
- **Unlink today**: `DefaultWaRepository.forget()` removes the link file; `WaOffline.follow(null)` closes the store.
  I found no code that deletes `wa-sync-<instance>.db` or `wa-media/<instance>/` on unlink or instance change (grep
  for `remove_file`, `deleteRecursively`, `wa-sync` in messages, wa, core-bridge, rosty_ffi, wa_sync). Old instances'
  data stays on disk as orphans.

## 2. Sharing between apps

### 2.1 What is shared today

- **Connect key**: each app stores its own `whatsapp-link.json`. Nothing is copied between apps automatically.
- **`:people`** (`android/people`): Contacts owns an exported `ContentProvider`
  (`android/contacts/.../people/PeopleProvider.kt`, authority `dev.rosty.contacts.people`) guarded by the signature
  permission `dev.rosty.permission.READ_PEOPLE`, declared in every app's manifest "so whichever installs first
  defines it". `query(/people)` returns a `MatrixCursor` with a notification URI; `openFile(/picture/<jid>/<pictureId>)`
  returns a read-only `ParcelFileDescriptor`. Readers use `ProviderSource` (`PeopleSource.kt`) with a
  `ContentObserver`. When Contacts is not installed, Phone falls back to its own cache and its own slow sync
  (`android/phone/.../people/StandalonePeople.kt`, `PeopleSource.Kind.Gateway`). The provider's `onCreate` does no
  work and waits up to 3 s for `Rust.init` on first query.
- **Intents**: `dev.rosty.action.WA_CHAT` (Contacts to Messages) and `dev.rosty.action.WA_CALL` (to Phone), both
  behind the signature permission `dev.rosty.permission.HANDOFF` (`android/platform/.../system/Handoff.kt`).
- **What Phone stores about WhatsApp**: its own link file; WhatsApp calls come from the gateway (`GET /v1/calls`,
  `WaCallSource`, `WaCallsRouter`), not a local DB; forgotten call ids in prefs (`PrefsForgottenCalls`); live names
  (`LiveWaNames` + `FileLiveNameStore` JSON); avatars per app (`android/wa/.../avatars/WaAvatars.kt`, `AvatarBook`);
  full-size pictures (`WaFullPictures`, small folder); people from the provider or `StandalonePeople`. No messages,
  no media.

### 2.2 Options for "one cache, no duplicate downloads"

| Option | How | For | Against |
| --- | --- | --- | --- |
| A. One owner app + signature ContentProvider | Owner holds `wa-sync-<instance>.db` and media; readers `query` pages and `openFile` media as file descriptors; writes go through `call()` (enqueue outbox, request download) | Same pattern as `:people`; one writer process so SQLite and LRU stay consistent; a reader's query starts the owner process; no extra permission prompts; nothing duplicated | Owner must be installed, else a fallback is needed; cursor IPC is slower than in-process (CursorWindow 2 MB, so page sizes stay small and thumbnails go through `openFile`); provider cold start (Rust init) on first query; OEM process killers (HyperOS autostart) can block starting the owner |
| B. `sharedUserId` / shared files | Both apps under one UID open the same DB | Fastest reads | `sharedUserId` is deprecated (API 29) and cannot be added to an installed app without reinstall; two processes writing one SQLite file plus two FFI caches (`open_files()` is per process); ruled out |
| C. Each app syncs its own store, media through the owner | Text in both apps, media provider only | Thread opens with no IPC; works when the other app is missing | Two sockets, two catch-ups, text stored twice; outbox, drafts, read marks diverge between apps; "one cache" only half true |
| D. Gateway-side media cache with validators | Each app keeps a private copy, gateway answers 304 | Simple | Still two copies on the phone and two downloads; today `media_response` sends no `ETag` and the client does not send `If-None-Match` for media (`services/wa_gateway/src/routes.rs`, `crates/rosty_wa_client/src/http.rs` `media`) |

**Recommendation: A, with the `:people` fallback rule.** Messages is the owner (it already has the store, the socket
runner, the outbox worker and the FCM wake). Move the engine out of `android/messages/.../wa/` into `:wa`
(`WaOffline`, `WaSyncRunner`, `WaOutbox`, `WaMedia`, plus a new `WaCacheContract`, `WaCacheSource`), so that the
*provider class* is the only owner-specific piece. Readers get a `WaCacheSource` with two kinds, exactly like
`PeopleSource`: `Provider` (Messages installed: query + `openFile`) and `Local` (Messages not installed: the same
engine runs in-process on the reader's own file). Phone needs little: last messages and media of a contact, a
"message" action, and call rows; it should not run a second socket when Messages is installed.

Trade-offs to accept: reads in Phone cross a process boundary; if Messages is uninstalled the cache is gone and Phone
rebuilds from the gateway; if Messages is installed later, Phone drops its local store (or keeps it until the provider
answers) and switches source, as `:people` does.

## 3. Gateway and library

### 3.1 Gateway (`services/wa_gateway`)

- **Storage** (`src/store.rs`, `MIGRATIONS` v1..v6, `PRAGMA user_version`): `gateway.db` holds `instances`, `keys`
  (hashed), `push_tokens`, `chats`, `messages` (PK `(instance_id, chat, id)`, `body_json`, `raw_proto`, `meta_json`,
  `secret`, `thumb`, `deleted_at`, `deleted_by`), `reactions`, `chat_settings`, `profiles`, `receipts`, `statuses`,
  `message_edits`, `scheduled`. I found no retention or pruning of `messages` (only `prune_scheduled` and status
  expiry), so the gateway keeps all history it ever saw.
- **REST paging**: `GET /v1/chats`, `GET /v1/chats/{jid}/messages?before=<ms>&limit=` (`routes.rs` `chat_messages`,
  `DEFAULT_PAGE 50`, `MAX_PAGE 200`), time-based cursor only. There is no "changes since" endpoint and no search
  endpoint.
- **Event socket** (`src/hub.rs`, `crates/rosty_wa_proto/src/log.rs`): per-instance in-memory ring `EventLog`,
  `LOG_CAPACITY = 1024`, broadcast channel 256. `GET /v1/events?after=<seq>` replays or sends `Resync`. Sequences
  restart at 1 when the gateway restarts; `EventLog::since` treats `after > last` as a gap. **There is no epoch or
  boot id**: after a restart, once the new head passes the client's old `last_seq`, `since` replays only events above
  that number and the earlier ones of the new run are silently missed (found by reading `since`; not tested).
- **Media** (`routes.rs` `message_media`, `src/media_cache.rs`): decrypted bytes cached on disk per instance, LRU by
  mtime, `DEFAULT_MAX_BYTES = 256 MiB`; miss: decode stored `raw_proto` and `client.download(...)`
  (`src/wa/session.rs` `download_media`). The whole file is buffered in memory and sent with no `ETag`, no `Range`,
  no `Content-Length`-based resume. Client limit `MEDIA_LIMIT = 128 MiB`. Thumbnails: `GET /v1/messages/{id}/thumbnail`
  (up to 64 KiB, stored in `messages.thumb`).
- **Media expiry and re-request**: the gateway does **not** call the library's media re-upload
  (`grep media_reupload services/wa_gateway/src`: no hits). When WhatsApp's CDN link has expired and the file is no
  longer in the 256 MiB gateway cache, the download fails for good today.
- **History import** (`src/wa/history.rs`, `session.rs` `Event::HistorySync` arm, `driver.rs` `import_history`): the
  lazy blob is walked per conversation, stored in one transaction, announced as one `HistorySynced {chats, progress}`
  event; history rows keep `raw_proto` (`history.rs:84`), so their media can be downloaded while the link is valid.
- **Relink / logout**: on a WhatsApp-side logout the instance goes to `logged_out` and `wa.db` is deleted
  (`docs/whatsapp-gateway.md`); `POST /v1/instance/link` (`routes.rs` `relink`) restarts linking on the **same
  instance id**. I found no code that clears `chats`/`messages` when the newly linked account differs from the old
  `own_jid`. `DELETE /v1/instance` deletes everything (cascade).

### 3.2 whatsapp-rust 0.7.0

- **History sync delivery**: `src/history_sync.rs` `Client::handle_history_sync` queues a `MajorSyncTask::HistorySync`;
  `process_history_sync_task_tracked` sends the `HistorySync` receipt, downloads/inflates the blob and dispatches one
  `Event::HistorySync` carrying `wacore::types::events::LazyHistorySync` (`stream()`, `decompress()`, `get()`,
  `progress()`). Sync types in `waproto-0.7.0/src/whatsapp.proto`: `INITIAL_BOOTSTRAP`, `INITIAL_STATUS_V3`, `FULL`,
  `RECENT`, `PUSH_NAME`, `NON_BLOCKING_DATA`, `ON_DEMAND` (+ `NO_HISTORY`, `MESSAGE_ACCESS_STATUS` in the message enum).
  `BotBuilder::skip_history_sync()` (`src/bot.rs:1245`) exists; the gateway does not use it.
- **Full vs recent**: `wacore-0.7.0/src/store/device.rs` `DevicePropsOverride::with_require_full_sync` and
  `with_history_sync_config` (`full_sync_days_limit`, `on_demand_ready`, `complete_on_demand_ready`); the doc comment
  says the default asks for a recent-only sync and full sync belongs to the `win_hybrid` (UWP) row. The gateway sets
  no override (grep `DevicePropsOverride`, `require_full_sync`: no hits), so it gets the default recent sync. How many
  days/messages WhatsApp then sends is server-decided: not verifiable from source.
- **On-demand history**: `src/pdo.rs` `Client::fetch_message_history(chat_jid, oldest_msg_id, oldest_msg_from_me,
  oldest_msg_timestamp_ms, count) -> Result<String>` sends a `HISTORY_SYNC_ON_DEMAND` peer data operation to the
  primary phone; the answer arrives as an `ON_DEMAND` history sync (`LazyHistorySync` carries the request id for
  correlation). The gateway does not call it. It needs the primary phone online; limits per request are not stated
  in the source (unverified).
- **Offline queue**: `src/handlers/ib.rs` handles `offline_preview` / `offline` and dispatches
  `Event::OfflineSyncPreview` / `Event::OfflineSyncCompleted` (`wacore types/events.rs`); messages missed while the
  gateway's socket was down are delivered as ordinary message events on reconnect. The gateway does not subscribe to
  the two offline events (its `on_event_for` list in `session.rs` does not name them).
- **App state sync**: `src/client/app_state.rs` (`process_app_state_sync_task`, `sync_collections_batched`); the
  gateway listens to `PinUpdate`, `MuteUpdate`, `ArchiveUpdate`, `StarUpdate`, `MarkChatAsReadUpdate`, contact and
  push-name updates and turns them into `chat_meta` / `message_meta` / `profile` events (`src/wa/app_state.rs`).
- **Media download and expiry**: `src/download.rs` `Client::download(&dyn Downloadable)`, `download_to_writer`
  (streaming), `MediaDownloadError` with 401/403/404/410 classified as an expired or revoked reference (media conn is
  refreshed and URLs re-derived first). Re-upload: `src/features/media_reupload.rs` `MediaReupload::request
  (MediaReuploadRequest {message id, chat, media key, from_me, participant})` sends `<receipt type="server-error">`
  and waits for a `mediaretry` notification with a new `directPath`; it also needs the primary phone (or sender) to
  still have the file. How long CDN paths stay valid is not in the source (commonly said to be about 30 days:
  unverified, from memory).

### 3.3 Other clients (all unverified, from memory)

- **whatsmeow**: delivers `events.HistorySync`; `BuildHistorySyncRequest` + `SendPeerMessage` for on-demand backfill;
  `SendMediaRetryReceipt` for expired media.
- **mautrix-whatsapp**: stores history sync conversations in its own DB first, backfills rooms in batches later, and
  asks for media retries lazily (on demand or scheduled) instead of downloading everything.
- **Baileys**: `messaging-history.set` event, `fetchMessageHistory`, `updateMediaMessage` for re-upload;
  `syncFullHistory` option changes the device props.
The common shape: persist the history blob as rows first, download media lazily, treat re-upload as a best-effort
request to the phone.

## 4. Proposals

### 4.1 Schema (v4 and v5 on top of v3)

Keep `PRAGMA user_version` and one-transaction-per-step. Before the first step of a multi-step upgrade, copy the file
(`VACUUM INTO 'wa-sync-<instance>.db.bak'`, delete after success): the store is a cache but holds the outbox, drafts
and deleted-message history that the gateway may not give back.

- **v4 media rows** (new table, messages table untouched except that the three `media_*` columns stop being written):
  `media (chat, id, PRIMARY KEY (chat, id), kind, mime, size_expected, state TEXT -- none|queued|downloading|ready|
  evicted|failed|gone, path, bytes, sha256 BLOB, used_ms, pinned INTEGER, attempts, last_error, saved_uri)`,
  index `media_lru (used_ms) WHERE state = 'ready' AND pinned = 0`, index `media_queue (state)`. Backfill from
  `messages.media_path/media_bytes/media_used_ms`. Thumbnails stay in `messages.thumb` (kept forever). A
  `media_dedupe (sha256 PRIMARY KEY, path, refs)` table lets a forwarded file be stored once (optional, later).
- **v4 identity**: add `chats.canon TEXT` (the phone-number JID when known, else the LID, device suffix dropped) and
  `chat_alias (alias PRIMARY KEY, canon)`. The gateway already resolves LIDs (`wa/lid.rs`) and announces
  `chat_merged`; the alias table makes `merge_chats` cheap to repeat, lets a handoff with an old LID land in the right
  chat, and lets Phone look up a chat by number. Message identity stays `(chat, id)`; note the gateway's own
  `media_source` looks up by id alone, so ids are assumed unique per instance.
- **v4 local-only state**: `drafts (chat PRIMARY KEY, text, quoted_id, updated_ms)` and `sync_state.account_jid`,
  `sync_state.epoch` (see 4.3).
- **v5 search**: FTS5 is compiled in (`libsqlite3-sys-0.37.0/build.rs` sets `SQLITE_ENABLE_FTS5`; rusqlite `bundled`).
  `messages` is WITHOUT ROWID, so use a contentless-delete or self-contained table:
  `CREATE VIRTUAL TABLE messages_fts USING fts5(text, chat UNINDEXED, id UNINDEXED, tokenize = 'unicode61
  remove_diacritics 2')`, maintained in `apply::write` (delete + insert when text changes), filled once by the
  migration in batches. Turkish dotted/dotless i needs a decision (fold in Rust before indexing, as `rosty_core`
  search does elsewhere). Today search pages through stored rows in Kotlin (`StoreWaChats.searchCandidates`).
- Index for the media gallery of a chat: `messages_media (chat, ts DESC) WHERE kind IN (image, video, ...)`.

### 4.2 Media limits

- **Auto-download** (settings in `:wa`, defaults): Wi-Fi: images, voice notes, stickers, documents up to 5 MiB,
  videos up to 16 MiB; mobile data: images and voice notes only; roaming and Data Saver: nothing. Videos and large
  documents on tap. Never auto-download view-once. Rules are pure Rust (`wa_sync::media::policy(kind, size, network)`)
  so both apps behave the same.
- **Cap**: user setting 256 MiB / 512 MiB (default, today's value) / 1 GiB / 2 GiB / no limit, plus a floor: stop
  auto-download when free space is under 500 MiB.
- **Eviction**: LRU by `used_ms` among `state = ready AND pinned = 0`; exempt: starred messages (join on
  `messages.starred`), files saved by the user, files of outbox rows not yet sent, files opened in the last 24 h.
  If exempt files alone exceed the cap, do not evict them; show the size in settings.
- **Thumbnails**: kept forever in the row (64 KiB max each on the gateway side). Stop writing a second copy to
  `thumbs/<id>.jpg`; serve them through `openFile` from the blob (pipe) or keep the file but count it.
- **Re-download after eviction**: state `evicted` to `queued`; ask the gateway; on 404 "not kept" or an expired link
  the row becomes `gone` and the bubble says so. To make this work beyond the gateway's 256 MiB cache the gateway
  should call `MediaReupload::request` on an expired download (new gateway work; the library API exists).
- **Gallery**: "Save" copies the cached file through `MediaStore` (`RELATIVE_PATH` Pictures/Rosty, Movies/Rosty,
  Download/Rosty; `IS_PENDING` while writing; no storage permission on API 29+). Never automatic by default; record
  `saved_uri` so the item is exempt and not saved twice.
- **Crash safety**: download to `<name>.part` in the same directory, fsync, rename, then `media_set` in one
  transaction; on start, delete `.part` files and reset `downloading` rows to `queued`; a `ready` row whose file is
  missing becomes `evicted`. For large files the gateway should stream (`download_to_writer` exists in the library)
  and support `Range` so a broken download resumes.

### 4.3 Incremental sync, history, backfill

- Keep the two-level design: a global event sequence for live changes and a per-chat time window for REST catch-up.
- **Fix the restart hole**: add a gateway epoch (boot id) to the socket hello / `Resync` and store it in
  `sync_state.epoch`; a different epoch is always a gap. Optionally persist the ring so short restarts replay.
- **Gap detection** stays as is (`chat_gap`), plus a cheap "changed since" gateway endpoint later so edits, deletes
  and ticks older than the 15-minute `RECENT_MS` window are not lost after a long gap.
- **Reconnect catch-up**: socket after `last_seq`, then `plan` (already done). Add `MAX_PAGES_PER_CHAT` continuation
  through the WorkManager job so a 40-page stop resumes without the app open.
- **History**: first link shows the gateway's recent sync (default props). Offer "load older messages" in two steps:
  (1) page the gateway (`older`), (2) when the gateway reports the start, ask it for on-demand history from the phone
  (new gateway route around `Client::fetch_message_history`; answer comes as `HistorySynced`). Full sync
  (`with_require_full_sync`) is an owner decision: bigger first sync, UWP device identity.
- **Backfill for search**: FTS only covers what is stored. On first link prefetch N pages (for example 200 messages)
  of the 50 most recent chats on Wi-Fi and while charging; show "searched messages since <date>" and offer a
  background "download all history from the gateway" job (paged, resumable through `chat_sync`).

### 4.4 Relink, second app, logout

- **Same account relinked** (same `instance_id`, same `own_jid`): keep everything; set `needs_resync`; reset
  `reached_start` (history may be richer). Outbox rows older than a day are shown as failed rather than sent late.
- **Different account**: the store must also check the account. Store `account_jid` in `sync_state`; if the instance
  status reports another `own_jid`, close and move the file to `wa-sync-<instance>-<old account>.db` (delete after
  the owner confirms, or at once), delete that instance's media directory, drop drafts and stars (they belong to the
  other account). The gateway should clear its `chats`/`messages` for that instance too (today it does not appear to).
- **New instance id**: other file (already so). Add cleanup: on `forget()` and on instance change delete the old DB
  (+ `-wal`, `-shm`), `wa-media/<old instance>/` and the provider's grants. Ask before deleting when the outbox is
  not empty.
- **Key pasted in a second app**: with option A the second app does not bootstrap at all when the owner is installed;
  it reads through the provider. The provider answers only when the reader's own link has the same `instance_id`
  (pass it in the URI: `content://dev.rosty.messages.wa/<instance>/...`, else empty cursor). When the owner is
  missing, the second app bootstraps from the gateway (`store_chats`, then pages on demand). A direct copy of the
  owner's DB into the reader is not worth it.
- **Auto-share the key**: the provider could hand the link to a same-signed sibling (`call("link")`) so "link once in
  any app" needs no paste. The signature permission protects it; still a product decision since it moves a bearer
  secret between apps.
- **Logout** (`logged_out`, key revoked, instance deleted): stop the socket, keep the cache readable offline until the
  user removes the link or a different account links; "Remove WhatsApp from this phone" wipes DB, media and thumbs.

### 4.5 Integrity and crash safety

- WAL + `synchronous=NORMAL` is already set; add `PRAGMA wal_autocheckpoint` default check and a `wal_checkpoint
  (TRUNCATE)` after big imports; run `PRAGMA quick_check` when open fails and fall back to "rename the broken file,
  start empty, resync" (the outbox is lost: tell the user).
- One writer process (the owner). Readers never open the file. Provider `call()` writes run on the owner's single
  store handle.
- Every multi-row change is already one transaction (`apply_events`, `store_chats`, `store_page`, `media_evict`).
- Reconcile files and rows at start (see 4.2); files on disk with no row are deleted.
- `noBackupFilesDir` for the DB is right (no cloud backup of messages). Media under `filesDir` is also excluded since
  `allowBackup=false`; moving it to `noBackupFilesDir/wa-media` makes that explicit.

### 4.6 Size estimates (rough, assumptions stated)

- Text row with JSON columns and index entries: about 0.4 to 0.8 KiB. 100,000 messages: 40 to 80 MiB. FTS5 adds
  roughly 30 to 50 % of the text bytes: about 10 to 20 MiB.
- Thumbnails: WhatsApp inline previews are usually 3 to 10 KiB (gateway caps at 64 KiB). 20,000 media messages:
  60 to 200 MiB **inside the DB** if kept forever. This is the largest uncapped item; consider a separate
  `thumbs` table (keeps `messages` pages small) and counting it in the settings screen.
- Full media: bounded by the cap (512 MiB default) plus exempt files. Typical photo 100 to 300 KiB, voice note
  about 100 to 200 KiB a minute, video up to 16 MiB or more (all three unverified, from memory).
- Today a second private copy of each thumbnail exists as a file (`thumbs/<id>.jpg`), doubling thumbnail space.

## 5. Risks and open questions for the owner

Risks:
1. **Gateway restart can lose events silently** (no epoch in `EventLog::since`); needs a protocol change.
2. **Expired media cannot be recovered today** (no `media_reupload` use; gateway cache 256 MiB); "re-download after
   eviction" depends on gateway work and on the primary phone being online.
3. **Relinking another account on the same instance mixes histories** on the gateway and in the app store (no
   account check found on either side).
4. **Owner app not installed / killed**: provider reads fail on HyperOS when autostart is denied (`docs/xiaomi.md`
   covers the same class of problem for calls).
5. **Orphaned data**: old instance DBs and media are never deleted today.
6. **Thumbnails forever** grow without a cap inside the DB.
7. **Provider IPC limits**: 2 MB cursor windows; thread pages over IPC need small pages and no blobs.
8. Moving `WaOffline`, `WaMedia`, runner and outbox out of Messages into `:wa` is a large refactor of tested code
   (`WaThreadViewModel` is 1078 lines and depends on them).
9. Not verified: real history sync volume, CDN link lifetime, on-demand history limits, FTS behaviour on device.
   Nothing here was run.

Questions:
1. Which app owns the cache: Messages (recommended) or Contacts (already owns `:people`)? What must Phone show from
   it (last messages on a contact card, media, only a "message" shortcut)?
2. When the owner app is not installed, should Phone run its own store (fallback as `:people` does) or show nothing?
3. Should the connect key be shared automatically between same-signed Rosty apps, or stay paste-only?
4. Media: default cap (512 MiB?), auto-download defaults on mobile data, are starred and saved files exempt, should
   thumbnails really be kept forever?
5. History: recent-only (today) or ask WhatsApp for full sync (changes the device identity to the Windows-hybrid
   row)? Is "load older from the phone" (on-demand) wanted?
6. Search: only what is on the phone, or also a gateway search endpoint over its full `messages` table?
7. Different account on the same instance: wipe at once, or keep the old cache for N days? Do drafts and stars
   survive a relink of the same account only?
8. Should the gateway keep all messages forever (today), and is gateway work (epoch, re-upload, streaming/Range,
   on-demand history, account-change wipe) in scope for this epic?
9. Should deleted messages' cached media stay (today's "never erased" rule) even when the cap is hit?
