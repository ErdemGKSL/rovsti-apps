# util-wa-lid-merge: plan (round 1)

Status: S1-S7 implemented and green; S8 (deploy) pending.

baseSha: da0377a7e8b465db31fa3da7474980d83a932757

## Bug
The same person shows up twice in Messages: 'Annem' (a phone-number JID) and '+96413459456093'
(a WhatsApp LID `96413459456093@lid`). The gateway (`services/wa_gateway/src/wa/convert.rs`
`prefer_pn`/`chat_and_sender`, `session.rs::on_receipt`, `history.rs::conversation_jid`,
`status.rs::live`, `app_state.rs::chat_of`, `groups.rs::participant_jid`, `calls.rs::on_incoming`)
maps LID to PN only when the message carries `sender_alt`/`recipient_alt`. The app then turns the
LID into a "phone number": `rovsti_core::inbox::resolve_jid` does `PhoneNumber::parse("+" + jid_user)`
for every non-group JID (a 14-digit LID parses as +964...). The same happens in
`rovsti_wa_proto::api::to_rovsti` (sender), `lookup::chat_users` (LID users count as numbers),
`apps/messages/src/inbox.rs` (`contact_photo`, `match_number(+jid_user)`),
`apps/messages/src/conversation.rs:1753`, `apps/contacts/src/whatsapp.rs::marked_contacts`.

## Verified upstream APIs (whatsapp-rust 0.7.0 / wacore 0.7.0)
- `Client::get_lid_pn_entry(&self, jid: &Jid) -> anyhow::Result<Option<LidPnEntry>>`
  (src/client/lid_pn.rs:1092). Cache, then persistent backend. `Ok(None)` for groups or other servers.
- `wacore::types::LidPnEntry { lid: Arc<str>, phone_number: Arc<str>, created_at, learning_source }`
  (bare user parts, no server, no device). Reach it via `whatsapp_rust::wacore::types::LidPnEntry`.
- There is NO "mapping learned" event. Mappings are learned by the library from live messages
  (`cache_lid_pn_from_message`), history sync (`sync_result.lid_mappings`, written before the
  `HistorySync` event), usync, `DeviceListUpdate { user, lid_user }` and
  `ContactNumberChanged { old_jid, new_jid, old_lid, new_lid }`. Both events exist as
  `EventKind::DeviceListUpdate` / `EventKind::ContactNumberChanged`. So: resolve lazily, and sweep
  stored LID chats on connect, after each history sync and on those two events.
- `MessageContext { message, info, client: Arc<Client> }`: the client is available in `on_message`.
- `Jid::is_lid()`, `Jid::is_pn()`, `Jid::to_non_ad()`, `Jid::is_group()`.
- Old clients skip unknown event types (`rovsti_wa_client::events` logs "undecodable event").

## Steps

### S1 Pure: never treat a LID as a phone number (proto + core + apps call sites)
- `crates/rovsti_wa_proto/src/api.rs`: add `pub fn is_pn_jid(jid: &str) -> bool` (server
  `s.whatsapp.net`, digits-only user, device suffix allowed) and `pub fn is_lid_jid(jid: &str) -> bool`
  (server `lid`); re-export from lib.rs; add `pub const WHATSAPP_USER_LABEL: &str = "WhatsApp user";`.
  `to_rovsti`: sender is `Some(PhoneNumber)` only when `is_pn_jid(jid)`.
- `crates/rovsti_wa_proto/src/lookup.rs::chat_users`: keep only PN JIDs.
- `crates/rovsti_core/src/inbox.rs::resolve_jid`: `@g.us` -> Group (as now); PN JID ->
  resolve_number; anything else (`@lid`, broadcast) -> `PersonKey::Group(ThreadId::WhatsApp(jid))`
  (never merges). Title fallback for `PersonKey::Group(ThreadId::WhatsApp(jid))` when the JID is
  not a group: "WhatsApp user" (local copy of the LID check, rovsti_core cannot depend on proto).
  `person_for_jid` still returns `None` for LID (keep its contract; adjust doc).
- `crates/rovsti_wa_ui/src/names.rs::number_label`: LID (and any non-PN, non-group JID) gives
  "WhatsApp user"; groups keep their user part. `display_name` therefore shows contact name,
  then push name, then "WhatsApp user".
- Apps: guard every `format!("+{}", jid_user(..))` / `match_number` on a JID with `is_pn_jid`:
  `apps/messages/src/inbox.rs` (contact_photo, group sender at ~421), `apps/messages/src/conversation.rs`
  (~1148 compare, ~1753), `apps/messages/src/main.rs` (scheduled title fallback ~848 use
  display_name), `apps/contacts/src/whatsapp.rs::marked_contacts` (input is chat_users: already PN
  only, add a unit test), `crates/rovsti_wa_ui/src/view.rs:933` (own_jid only when PN).
- Tests: names.rs `lid_is_never_a_number` (`display_name(None,None,"96413459456093@lid") ==
  "WhatsApp user"`, push name wins, no '+'); rovsti_core inbox: a PN chat and a LID chat with the
  same digits do NOT merge, LID row title is "WhatsApp user" or its thread title; proto:
  `is_pn_jid`/`is_lid_jid` table test, `to_rovsti` LID sender is None, `chat_users` drops LID.

### S2 Proto event + pure client-side rekeying
- `crates/rovsti_wa_proto/src/event.rs`: new variant `ChatMerged { from: String, into: String }`
  (doc: "a LID chat was merged into the phone-number chat; move local state from `from` to `into`
  and refetch chats. Older clients skip it"). Serde round-trip test
  `{"type":"chat_merged","from":"1@lid","into":"2@s.whatsapp.net"}`.
- `crates/rovsti_wa_proto/src/timeline.rs`: handle `ChatMerged` like `ChatDeleted` for `from`
  (and refetch if the open chat is `from`); test.
- `apps/messages/src/drafts.rs`: `pub fn rekey(&mut self, from: &ThreadId, into: &ThreadId)`
  (moves the draft; if both exist keep the `into` draft unless empty; bumps rev). Tests.
- `apps/messages/src/prefs.rs`: `pub fn rekey_thread(&mut self, from_tag: &str, into_tag: &str) -> bool`
  moving `muted` (and any other per-tag set keyed by thread tag). Tests.
- `apps/messages/src/inbox.rs`: pure `pub fn apply_merge(threads: &mut Vec<InboxThread>,
  info: &mut HashMap<String, ChatInfo>, from: &str, into: &str)` (drop the `from` thread, fold its
  unread and newer last into `into`, move ChatInfo meta (pin/mute/archive) when `into` has none). Tests.
- Wire in `apps/messages/src/main.rs` event match: `GatewayEvent::ChatMerged { from, into }` ->
  apply_merge, drafts.rekey, prefs.rekey_thread via backend.update_prefs, swap `self.open` and
  `row_menu` from -> into, then the same reload as `Resync`. `apps/messages/src/conversation.rs`:
  an open conversation on `from` switches its chat id to `into` and reloads. Phone
  (`apps/phone/src/wa_call.rs`), Contacts (`apps/contacts/src/whatsapp.rs`), `rovsti_wa_ui`
  (`model.rs`, `avatars.rs`): treat `ChatMerged` like `Resync` (refetch). Keep the GPUI part thin.

### S3 Gateway pure resolver (`services/wa_gateway/src/wa/lid.rs`, register in wa/mod.rs)
- `pub struct LidMap(HashMap<String, String>)` lid_user -> pn_user, with `insert(lid_user, pn_user)`,
  `get(lid_user)`, `from_entries`.
- `pub fn resolve(jid: &Jid, alt: Option<&Jid>, map: &LidMap) -> Jid`: groups/broadcast/PN ->
  `jid.to_non_ad()`; LID with mapping -> `<pn>@s.whatsapp.net`; else LID with PN `alt` -> alt.to_non_ad();
  else `jid.to_non_ad()` (LID unchanged). Same as `resolve_str(&str, &LidMap) -> String` for stored ids.
- `pub struct Resolver { client: Arc<Client>, learned: Mutex<LidMap> }`:
  `async fn lookup(&self, jid: &Jid) -> Option<String>` (calls `client.get_lid_pn_entry`, logs Err at
  debug, caches hits in `learned`), `async fn prefetch(&self, jids: &[&Jid]) -> LidMap` (snapshot for
  the sync converters), `async fn resolve(&self, jid, alt) -> Jid`. Keep the Client-dependent part
  minimal; all logic in the pure fns.
- Tests (pure): LID with mapping -> PN; LID without mapping -> LID unchanged; `1234:5@lid` device
  suffix stripped (both with and without mapping); `x@g.us` untouched even if map has the user;
  PN input untouched; alt fallback when the map misses; map wins over alt.

### S4 Gateway store merge (`services/wa_gateway/src/store/merge.rs`, `mod merge;` in store.rs)
- `pub struct MergeReport { pub moved: usize, pub deduped: usize, pub merged: bool }`.
- `Store::lid_chats(&self, instance) -> Result<Vec<String>, StoreError>`: chat jids ending `@lid`.
- `Store::merge_chat(&self, instance, from: String, into: String) -> Result<MergeReport, StoreError>`,
  one transaction, no-op (`merged=false`) when `from == into` or nothing stored under `from`:
  messages `INSERT OR IGNORE ... SELECT` with chat=into then `DELETE` from (dedupe by id; keep the
  `into` row on conflict); reactions (chat) and receipts (chat) likewise; `UPDATE messages SET
  sender=into WHERE sender=from` and reactions.sender / receipts.participant (group authors);
  chats: if `into` exists `unread = into.unread + from.unread`, `last_ms = max`, `name =
  COALESCE(into.name, from.name)`, delete `from`; else rename the row; chat_settings: keep `into`
  row, else move `from` (pinned/archived OR, muted max); profiles: copy push_name/contact_name into
  `into` when missing, delete `from`; scheduled: `UPDATE scheduled SET chat=into WHERE chat=from`.
  Message ordering is by ts_ms so it survives; per-message meta_json (ticks, read) moves as is.
- Tests in merge.rs (Store::open_in_memory): messages moved and listed in time order under
  `into`; duplicate id kept once; unread summed; LID chat gone from `chats()`/`chats_rich()`;
  settings/profile carried over; second call is a no-op (`merged=false`, same rows); `into` absent ->
  renamed.

### S5 Gateway: resolve every produced id
- `convert.rs`: `chat_and_sender(source, map: &LidMap)` uses `lid::resolve`; `prefer_pn` becomes a
  thin wrapper or is removed; `message(ctx, map)`. `session.rs` on_message: build
  `resolver.prefetch(&[&source.chat, &source.sender])` (plus alts) inside the async block BEFORE
  converting (move conversion into the async block; do not hold MessageContext across awaits on
  wasm only, native is fine since handle_event already does). `app_state::encrypted_edit`,
  `status::live`, `on_receipt` (chat and `participant_key`), `ChatPresence`/`Presence` jids,
  `app_state::chat_of` (async resolve via resolver), `groups::participant_jid` (fallback to map),
  `calls.rs::on_incoming` peer (keep `wire_peer` raw for signalling). `history.rs::drain(lazy, now,
  own_pn, map)`: `conversation_jid` and message participants use the map (prefetch every
  `@lid` conversation id before spawn_blocking).
- Store the `Resolver` in `Running` (session.rs) and hand it to the EventSink paths that need it.
- Sends: `send_text_inner`/`send_media_inner`/`react_inner`/`mark_read`/`chat_action`/edit/revoke:
  parse `req.chat`; the store key is `resolver.resolve(&to)` (PN when known), the wire target stays
  what the app sent (a legacy LID still delivers). Find stored messages (quotes) under the resolved id.
- Update existing tests (`convert.rs` lids_give_way_to_phone_numbers, history.rs LID test,
  session.rs participant tests) to pass a `LidMap`; add tests where the map (not alt) supplies the PN.

### S6 Gateway: merge triggers, startup migration, client notification
- `driver.rs` EventSink: `pub async fn merge_chat(&self, from: &str, into: &str)` -> store.merge_chat;
  when `merged`, `publish(GatewayEvent::ChatMerged { from, into })` and log
  `info!("{instance}: lid-merge: {from} -> {into}: {moved} moved, {deduped} deduped")`.
- `lid.rs`: `pub async fn sweep(resolver: &Resolver, sink: &EventSink) -> usize`: for each
  `store.lid_chats`, `resolver.lookup`, merge; logs `lid-merge: startup sweep merged N of M LID chats`
  (warn on store errors, never panic).
- Triggers in session.rs: `on_connected` spawns the sweep (this is the one-time startup migration;
  it is idempotent so running at every connect is safe); after `Event::HistorySync` import; on
  `Event::DeviceListUpdate` (with `lid_user`) and `Event::ContactNumberChanged` (add both to the
  `on_event_for` kinds); and in on_message when the resolved chat is PN but the raw chat was LID and
  the resolver had not seen that LID before (merge that one chat).
- Fake driver (`driver.rs` FakeDriver): allow scripting a LID map and test end to end through the
  HTTP/WS layer (`services/wa_gateway/tests` or driver.rs tests): a stored LID chat + mapping ->
  after sweep `GET chats` lists only the PN chat and the event stream carries `chat_merged`.

### S7 Docs and green checks
- `docs/whatsapp-gateway.md` and `docs/whatsapp-notes.md`: LID resolution, merge, `chat_merged` event,
  display rule. Run all checks (fmt, clippy -D warnings, tests, the three aarch64-linux-android
  checks). Commit.

### S8 Deploy (last, only after everything is committed and green)
- `CLOUDFLARED=/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/cloudflared bash scripts/deploy-wa-gateway.sh deploy --if-changed`,
  then `... status`, `... logs` (LINES=300 if needed). Confirm /healthz ok and the journal shows
  `lid-merge: startup sweep` without errors. On failure: `... rollback` and report. Never touch
  /etc/wa-gateway/config.toml, no FORCE_CONFIG.
