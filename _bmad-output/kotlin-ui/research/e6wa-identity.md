# E6 research: WhatsApp identity matching (LID vs phone JID), end to end

Read-only research. Nothing in the repo was edited, built or tested.

## 0. Sources and what could be verified

- **Pinned library.** `services/wa_gateway/Cargo.toml` takes `whatsapp-rust` from the workspace; `Cargo.lock`
  pins `whatsapp-rust`, `wacore`, `wacore-binary`, `waproto`, `wacore-appstate`, `whatsapp-rust-sqlite-storage`
  all at **0.7.0 from crates.io** (not a git rev). The sources are on disk under
  `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/` (`whatsapp-rust-0.7.0`, `wacore-0.7.0`,
  `wacore-binary-0.7.0`, `waproto-0.7.0`, ...). Every library claim below cites a path under that directory
  (prefix omitted) and a symbol I read. There is no whatsapp-rust checkout under `~/.cargo/git/checkouts`.
- **Not verified:** real wire traffic. Statements about what the WhatsApp server sends are what the library's
  parsers and comments say. No web source was fetched; every comparison with Baileys / whatsmeow /
  mautrix-whatsapp is marked *unverified, from memory*.

## 1. whatsapp-rust 0.7.0

### 1.1 JID model (`wacore-binary-0.7.0/src/jid.rs`)

- `struct Jid { user: CompactString, server: Server, agent: u8, device: u16, integrator: u16 }`.
- `enum Server { Pn = "s.whatsapp.net", Lid = "lid", Group = "g.us", Broadcast, Newsletter, Hosted = "hosted",
  HostedLid = "hosted.lid", Messenger = "msgr", Interop, Bot, Legacy = "c.us", Call }` (`Server::as_str`).
  `Server::is_pn_family()` = Pn | Hosted, `is_lid_family()` = Lid | HostedLid, `carries_phone_number()`.
- Text form: `user[:device]@server`; the agent is only rendered for servers where `Server::renders_agent()` is
  true (not Pn/Lid/Hosted/HostedLid). LID users may contain dots, handled in `impl FromStr for Jid`.
  `push_phash_form_to` writes `user.0:device@server` (the `user_agent:device` shape seen in hashes; the agent
  is always the literal `0`).
- Equality and hash (`impl PartialEq for Jid`, `identity_agent`) include **device** and server. Two JIDs of
  the same person on different devices are *not* equal, and a LID is never equal to its PN.
- Helpers: `to_non_ad()` / `into_non_ad()` / `to_non_ad_string()` (drop device and agent),
  `is_same_chat_as()` (user + server + integrator, device ignored), `JidExt::is_same_user_as()` (compares the
  **user part only**, ignores the server), `matches_user_or_lid(user, lid)` ("is this me"), `is_pn()`,
  `is_lid()`, `is_ad()`, `is_hosted()` (device 99 or hosted server), `is_bot()`, `is_psa()`,
  `is_status_broadcast()`, `with_device()`, `device_eq()`, `user_base()`.
- Consequence for Rosty: any comparison must first strip the device (`to_non_ad`) and then go through a
  LID/PN mapping; the library gives no synchronous "same person" predicate. The async one is crate-private:
  `Client::jids_share_user_identity` (`whatsapp-rust-0.7.0/src/client/lid_pn.rs`).

### 1.2 Where LID/PN mappings live

- Type: `LidPnEntry { lid, phone_number, created_at, learning_source }` and `enum LearningSource { Usync,
  PeerPnMessage, PeerLidMessage, RecipientLatestLid, MigrationSyncLatest, MigrationSyncOld, BlocklistActive,
  BlocklistInactive, Pairing, DeviceNotification, Other }` (`wacore-0.7.0/src/types/lid_pn.rs`). Both fields
  are bare user parts.
- In-memory cache: `LidPnCache` (`whatsapp-rust-0.7.0/src/lid_pn_cache.rs`): `get_current_lid(phone)`,
  `get_phone_number(lid)`, `get_entry_by_lid`, `get_entry_by_phone`, `add`, `warm_up`. Rule in `add`: the
  LID -> entry map always takes the latest write; the PN -> entry map only when `created_at` is newer or equal
  (so one PN can have had several LIDs, the newest is "current").
- Persistence: store trait methods `get_lid_mapping`, `get_pn_mapping`, `put_lid_mapping(s)`,
  `get_all_lid_mappings` (`wacore-0.7.0/src/store/traits.rs`), table `lid_pn_mapping(lid, phone_number,
  created_at, learning_source, updated_at, device_id, PRIMARY KEY (lid, device_id))`
  (`whatsapp-rust-sqlite-storage-0.7.0/migrations/2025-12-11-000000_add_lid_pn_mapping/up.sql`).
- **Public API an embedder can call** (`whatsapp-rust-0.7.0/src/client/lid_pn.rs`):
  `Client::get_lid_pn_entry(&Jid) -> Result<Option<LidPnEntry>>` (cache, then backend; works for a PN or a
  LID input), `Client::add_lid_pn_mapping(lid, pn, source)`, `Client::add_lid_pn_mappings`,
  `Client::is_lid_migrated()`. There is **no** public "list all mappings" on `Client` and **no event when a
  mapping is learned** (the gateway comment at `services/wa_gateway/src/wa/session.rs` "there is no dedicated
  event" is correct).
- Write policy: `lid_pn_write_policy(source, lid_unseen, exact)` in the same file. Directed sources
  (peer message, migration sync, blocklist, recipient-latest) overwrite on any difference; `Usync` seeds new
  LIDs and corrects a drifted one; observational sources (`Other`, `Pairing`, `DeviceNotification`) only seed
  unseen LIDs and, on a conflict, ask for a live usync re-resolve (`spawn_lid_usync_reconcile`).
  `is_stale_source` stamps `MigrationSyncOld` / `BlocklistInactive` with `created_at = 0`.

### 1.3 What reveals a mapping, and when

| Source | Code | Notes |
|---|---|---|
| Incoming 1:1 message | `wacore-0.7.0/src/messages.rs::parse_message_info` reads `sender_pn` (sender is LID) or `sender_lid` (sender is PN) into `MessageSource::sender_alt`; `Client::cache_lid_pn_from_message` (`src/message/receive.rs`) learns it | `PeerLidMessage` / `PeerPnMessage`. Offline replays only warm memory (`learn_lid_pn_mapping_fast(.., is_offline)`) |
| Incoming group message | same parser: `participant_pn` when `addressing_mode="lid"`, `participant_lid` when `"pn"`; **`sender_alt` is `None` when the stanza has no `addressing_mode`** | |
| Status / broadcast | same parser: `participant_lid` / `participant_pn` read unconditionally | |
| My own message echoed from another device | same parser: `chat = recipient`, `sender_alt` = my other identity; the peer's number is in **`MessageInfo::peer_recipient_pn`** (`wacore-0.7.0/src/types/message.rs`) | `MessageSource::recipient_alt` exists but **is never assigned anywhere in 0.7.0** (grep over `wacore` and `whatsapp-rust`: only the field and a serde test) |
| Receipts | `Client::handle_receipt_inline` (`src/receipt.rs`): `participant_pn` -> `source.sender_alt`; aggregated receipts: one event per `<user>` with its own `participant_pn` (`wacore-0.7.0/src/stanza/receipt.rs::ReceiptUser`) | |
| Group metadata | `GroupParticipant { jid, phone_number, lid, .. }`, `GroupMetadata { addressing_mode, creator, creator_pn, .. }` (`src/features/groups.rs`); the group query learns `lid -> pn` for every participant with source `Other`; cached per group in `wacore-0.7.0/src/client/context.rs::GroupInfo` (`phone_jid_for_lid_user`, `lid_user_for_phone_user`) | |
| Group notifications | `GroupUpdate { participant, participant_pn, is_lid_addressing_mode, .. }` (`wacore-0.7.0/src/types/events.rs`) | |
| usync | `IsOnWhatsAppResult { jid, lid, pn_jid, .. }`, `UserInfo { jid, lid, .. }` (`wacore-0.7.0/src/iq/usync.rs`); learned with source `Usync` (`src/usync.rs`, `src/features/contacts.rs::persist_lid_mappings`) | |
| History sync | `HistorySync.phoneNumberToLidMappings` (proto field 15) -> `sync_result.lid_mappings` -> `learn_lid_pn_mappings_batch(.., Other, ..)` (`src/history_sync.rs`); `Conversation.pnJid` (39), `lidJid` (42), `accountLid` (49) are in `waproto-0.7.0/src/whatsapp.proto` | |
| 1:1 migration push from the phone | `ProtocolMessage.lidMigrationMappingSyncMessage` -> `Client::handle_lid_migration_mapping_sync` (source `MigrationSyncLatest`, uses `latest_lid` else `assigned_lid`) | also sets the account's `lid_migrated` flag |
| Device notification | `handlers/notification/device.rs::handle_devices_notification` (`DeviceNotification::lid_pn_mapping()`), event `DeviceListUpdate { user, lid_user, .. }` | |
| Number change | `handlers/notification/profile.rs::learn_contact_modify_mappings` (`old`/`new`/`old_lid`/`new_lid`), event `ContactNumberChanged { old_jid, new_jid, old_lid, new_lid }` | both old and new pairs are stored |
| Pairing | `src/pair.rs` (`LearningSource::Pairing`), event `PairSuccess { id, lid, .. }`; own LID in `device.lid` | my own PN <-> LID |
| Calls | `IncomingCall` offer carries `caller_pn` (used in `services/wa_gateway/src/wa/calls.rs::on_incoming`) | |
| App-state contact sync | `ContactAction.lidJid` / `pnJid` (proto) arrive as `Event::ContactUpdate { jid, action, .. }`. The library does **not** learn these itself: the doc comment on `Client::add_lid_pn_mapping` says embedders should feed them in | **gateway does not** (see 2.4) |
| `PnForLidChatAction` | only a schema entry (`wacore-appstate-0.7.0/src/schemas.rs`); no event in 0.7.0 | not usable today |

Events with **no alt identity at all**: `Event::Presence(PresenceUpdate { from, .. })`
(`src/handlers/presence.rs`) and `Event::ChatPresence` (typing; `Client::dispatch_chatstate_event` in
`src/client/messaging.rs` builds the source with `sender_alt: None`). They can only be matched through the
mapping store.

### 1.4 LID-addressed vs PN-addressed groups

`AddressingMode { Pn, Lid }` (`wacore-0.7.0/src/types/message.rs`). In a LID group every `participant`, receipt
sender and notification participant is a LID; the phone number is only present as `participant_pn` /
`GroupParticipant::phone_number` when the server chooses to send it. In a PN group the roles are swapped
(`participant_lid`). Community announcement groups: `GroupInfo::is_community_announce`.

### 1.5 1:1 chats moving from PN to LID

- `Client::is_lid_migrated()` = persisted device flag or the `lid_one_on_one_migration_enabled` AB prop.
- `Client::resolve_dm_wire_jid` (crate-private): a migrated account sends DMs to the LID when a mapping is
  known; an unmigrated account stays on PN. Signal sessions always prefer the LID (`resolve_encryption_jid`).
- So after migration, inbound 1:1 stanzas arrive with `from = <lid>` and `sender_pn`; outbound echoes arrive
  with `recipient = <lid>` and `peer_recipient_pn`. History sync conversations are keyed by LID with `pnJid`.
  The same person therefore appears under two chat ids over time. That is exactly the split Rosty must merge.

### 1.6 Receipts

`Receipt { source: MessageSource, message_ids, timestamp, type, offline }`
(`wacore-0.7.0/src/types/events.rs`), `ReceiptType { Delivered, Sent, Sender, Retry, Read, ReadSelf, Played,
PlayedSelf, PeerMsg, HistorySync, .. }` (`wacore-0.7.0/src/types/presence.rs`).

- `source.chat = from` (with its device suffix, not stripped), `source.sender = participant` for groups, else
  `from`; `source.sender_alt = participant_pn`. `is_from_me` is never set on receipts.
- The `recipient` attribute of a receipt stanza is **not read** (`handle_receipt_inline`), so a receipt sent by
  one of my own devices has `chat` = my own JID. *Unverified, from memory:* whatsmeow's `parseMessageSource`
  uses `recipient` as the chat for such receipts.
- A delivery ack that carries `<error reason="lid" type="feature-incapable">` is downgraded to `Sent`
  (`downgrade_for_feature_incapable`).
- Device suffixes: every device of the recipient acks separately (`123:0@lid`, `123:7@lid`); they must be
  collapsed to one person before counting.

### 1.7 Special accounts

- **Business:** `MessageInfo::verified_name` (boxed `VerifiedName`, name in `.name`), `verified_level`,
  `IsOnWhatsAppResult::{is_business, verified_name}`, `UserInfo::{is_business, verified_name}`, event
  `BusinessStatusUpdate { jid, update_type, verified_name, .. }`. Hosted (Cloud API) devices use
  `@hosted` / `@hosted.lid` or device 99; `Jid::is_hosted()`.
- **Hidden-number members (communities):** only a LID; `GroupParticipant::phone_number` is `None` and no
  stanza carries `participant_pn`. No mapping will ever exist. `GroupParticipantDetails::display_name` and
  `GroupParticipant::username` may be the only names besides the push name.
- **Number change:** `ContactNumberChanged`; the library stores `(old_lid, old_pn)` and `(new_lid, new_pn)`.
  *Unverified, from memory:* the LID normally stays the same across a number change, in which case
  `old_lid == new_lid` and the LID -> PN map flips to the new number (latest write wins).
- **Multi-device:** see 1.6; my own devices are recognised by `matches_user_or_lid(own_pn, own_lid)`.

## 2. The gateway today (`services/wa_gateway`)

### 2.1 Normalisation

- One module: `src/wa/lid.rs`. `LidMap` (LID user -> PN user), `resolve(jid, alt, map)` (non-LID: strip
  device; LID: map, else the PN `alt`, else stay a bare LID), `resolve_str`, `source_jids`, `Resolver`
  (wraps `Client::get_lid_pn_entry`, remembers hits per instance), `sweep` / `sweep_with` (merge every stored
  chat whose id ends in `@lid` and now has a number).
- Call sites: live messages `convert::chat_and_sender` (`src/wa/convert.rs`); receipts `on_receipt` and
  `participant_key` (also asks `GroupInfo::phone_jid_for_lid_user`); presence, typing, app-state events
  (`src/wa/app_state.rs::chat_of`); history (`src/wa/history.rs::conversation_jid`, `author`, `reactions`,
  uses `Conversation.pn_jid`); group lists (`src/wa/groups.rs::participant_jid`, prefers
  `GroupParticipant::phone_number`); calls (`src/wa/calls.rs::on_incoming`, `caller_pn`); statuses
  (`src/wa/status.rs::live`); sends, reactions, edits (`Resolver::resolve` on the requested chat).
- The **canonical id is the bare phone JID when known, else the bare LID**. The original wire id is thrown
  away after resolution.

### 2.2 What it forwards

`crates/rosty_wa_proto/src/event.rs::GatewayEvent` and the REST types in `api.rs` / `meta.rs` carry exactly
one id string per person (`WaMessage::{chat, sender}`, `Reaction::sender`, `Receipt::chat`,
`Presence::jid`, `Typing::{chat, jid}`, `Participant::jid`, `CallUpdate::peer`, `WaProfile::jid`,
`MessageMeta` mentions and `Quoted::sender`). There is **no LID field, no alt JID, no mapping event and no
mapping endpoint**. The only identity signal is `GatewayEvent::ChatMerged { from, into }`. Own identity:
`InstanceStatus::own_jid` (PN only; my LID is never exposed). Group receipts are aggregated on the gateway
(`Store::record_group_receipt`) and reach the apps as a `MessageMeta` status, so apps never see per-member
receipts.

### 2.3 What it stores

`src/store.rs`: `chats(instance_id, jid)`, `messages(instance_id, chat, id, sender, ..)`,
`reactions(instance_id, chat, id, sender)`, `receipts(instance_id, chat, id, participant, status)`,
`chat_settings(instance_id, jid)`, `profiles(instance_id, jid, push_name, contact_name, about, picture_id,
business, verified_name, ..)`, `scheduled(chat)`, `message_edits`. No mapping table of its own (it relies on
the library's `lid_pn_mapping`). `src/store/merge.rs::merge` moves messages, reactions, receipts, edits,
settings, profile and scheduled rows of chat `from` into `into`, and rewrites `sender` / `participant` /
`deleted_by` equal to `from` in every chat.

### 2.4 Gaps and bugs in the gateway (read from the code)

1. **`recipient_alt` is dead.** `convert::chat_and_sender` uses `source.recipient_alt` for my own messages and
   `session.rs` uses it for the post-message merge, but the library never sets it (1.3). The peer's number of
   an echoed own message is in `ctx.info.peer_recipient_pn`, which the gateway never reads. Effect: a message I
   send from my phone to a LID-addressed 1:1 chat whose mapping is not cached yet is stored under the LID chat.
2. **The sweep only looks at chat rows.** `Store::lid_chats` selects `chats.jid LIKE '%@lid'`. A LID that is
   only a *group author*, a *reaction sender*, a *receipt participant*, a *quoted sender*, a *mention* or a
   *profile* row is never rewritten when its number is learned later. `merge` would fix sender / participant
   rows, but it is never called for such a LID (and returns early when no chat/messages/settings/scheduled row
   exists under `from`).
3. **Mentions and quoted senders are not resolved.** `convert.rs` copies `context.mentioned_jid` verbatim
   (may be `123@lid`, may carry a device) and only strips the device from the quoted `participant`.
4. **History push names are stored under the raw id** (`history::push_names` -> `set_push_names`), so a name
   can sit under `123@lid` while the chat is `90555...@s.whatsapp.net`. Only a later chat merge joins them.
5. **`ContactUpdate` mappings are dropped.** `app_state::handle` uses only the name of
   `Event::ContactUpdate`; `action.lid_jid` / `pn_jid` are not passed to `Client::add_lid_pn_mapping`.
6. **`ContactNumberChanged` only triggers a sweep.** Nothing tells the apps that person A moved from number X
   to number Y; the old PN chat and the new PN chat stay two chats.
7. **Receipt chat keeps its device and may be my own JID** (1.6). `Resolver::resolve` strips the device, so
   the first part is fine; the own-device case falls through to `set_status(<my own chat>, ids, ..)`, which
   is harmless only because the ids are not in that chat.
8. **Group receipt counting is by string.** `participant_key` returns PN when known else LID. A member who
   acked once as `123@lid` (before the mapping) and once as PN counts twice in
   `SELECT COUNT(*) FROM receipts`, and `members_besides_me` counts the library's participant list. Ticks can
   turn blue one member early.
9. **Outbound keys use the canonical id, not the wire id.** `react_inner`, `quote_context`, read receipts
   (`split_by_author`, `author_jid`) put the stored (PN) author into `MessageKey.participant` / the receipt
   `participant`. In a LID-addressed group the original key was a LID. *Risk, not verified on the wire:*
   reactions or read receipts that other clients cannot attach to the message. `Client::send_reaction` and
   `mark_as_read` send the participant as given (`src/features/reaction.rs`, `src/receipt.rs`).
10. **Business name from the message envelope is unused** (`MessageInfo::verified_name`); it only arrives
    through a profile fetch.
11. **Relink:** `teardown(.., delete = true)` removes the WhatsApp session directory only. I found no code that
    clears `chats` / `messages` / `profiles` of the instance when a different account is paired (to verify in
    `routes.rs` link handling before relying on this).
12. `is_own` compares user parts across servers (`me.user == jid.user`); fine in practice, but a LID user
    could in theory equal some PN user.

## 3. Rosty today

### 3.1 Rust

| Place | What it does |
|---|---|
| `crates/rosty_wa_proto/src/api.rs` | `jid_user` (text before `@`, then before `:`), `is_pn_jid`, `is_lid_jid`, `WHATSAPP_USER_LABEL`, `to_rosty` (sender number only for PN JIDs) |
| `crates/rosty_wa_proto/src/lookup.rs` | `chat_users` (PN chats only), `wa_target` |
| `crates/rosty_wa_proto/src/call.rs` | `peer_label`: name, else `+digits` for PN, else fallback |
| `crates/rosty_wa_proto/src/timeline.rs` | events matched by `chat == self.chat` string equality; `ChatMerged` clears the `from` timeline |
| `crates/rosty_wa_proto/src/meta.rs` | `WaProfile::display_name`: contact_name, verified_name, push_name |
| `crates/rosty_wa_client` | transport only; no JID logic found |
| `crates/rosty_app_core/src/wa_sync/schema.rs` | `chats(jid PK)`, `chat_sync(jid PK)`, `messages(chat, id PK; sender, sender_name, reactions JSON, quoted_json, extra_json, deleted_by, status)`, `outbox(chat)`. No identity or mapping table. One file per `instance_id` (`store.rs::file_name`) |
| `crates/rosty_app_core/src/wa_sync/apply.rs` | every event applied by exact `chat` string; `merge_chats(from, into)`; `ensure_chat` (`is_group` = ends with `@g.us`) |
| `crates/rosty_app_core/src/people.rs` | the people cache `people.json`: `Person { jid, numbers, lid, push_name, verified_name, .. }`, `Book::lookup` (number, PN JID, LID JID, bare LID), `Book::upsert` joins a LID record into the phone record. **Already a LID-aware identity store, but nothing feeds it a LID**: no producer of `Update::lid` outside tests (`rosty_ffi/src/ops/people.rs` fixtures only) |
| `crates/rosty_app_core/src/people_sync.rs` | usync planner; works on PN JIDs only (`PHONE_SUFFIX`) |
| `crates/rosty_app_core/src/contacts/whatsapp.rs` | number -> JID through `caller_id::e164_of` |
| `crates/rosty_app_core/src/messages/{prefs,drafts}.rs` | `rekey_thread`, `Drafts::rekey` for a merged chat |
| `crates/rosty_core/src/inbox.rs` | private copies of `jid_user` / `is_pn_jid`; `resolve_jid`: PN -> contact by number, everything else (LID, broadcast) is its own `PersonKey::Group(ThreadId::WhatsApp(jid))`; `person_for_jid` returns `None` for a LID |
| `crates/rosty_core/src/caller_id.rs` | `NumberBook` (E.164 map, loose tails, short codes, ranking), `e164_of`, same-line keys |
| `crates/rosty_core/src/phone_number.rs` | `PhoneNumber::parse` (`00` -> `+`, `(0)` trunk, format marks), `to_e164(home_cc)`, `matches` (tail of 9), `MIN_E164_NSN = 8`, `whatsapp_user` |
| `crates/rosty_core/src/search.rs` | `ContactIndex::match_number(_e164)` on top of `NumberBook` |
| `crates/rosty_core/src/{copy_choices,wire,redact}.rs` | more private "is this a PN JID" copies (`has_number`, handoff check, masking) |

There are at least **five independent re-implementations** of "user part / is PN JID" in Rust
(`rosty_wa_proto::api`, `rosty_core::inbox`, `rosty_core::copy_choices`, `rosty_core::wire`,
`rosty_app_core::people`) plus `people::e164` (digits only, 5+ digits, no home region), which is weaker than
`caller_id::e164_of`.

### 3.2 Kotlin

- `android/messages/.../inbox/InboxRows.kt::numberOfJid` (PN only, strips device).
- `android/messages/.../names/PersonNames.kt`: order contact, WhatsApp name (verified, else push), stored chat
  name, number. `ofJid` for a JID without a number falls back to **`jid.substringBefore('@')`** with
  `NameTier.Number`.
- `StoredWaNames.kt` (names of 1:1 PN chats by digits), `NotificationNames.kt::groupLines`
  (prefix `jid.substringBefore('@')`), `NotificationAvatars.kt`, `WaChats.kt`, `WaThreadViewModel.kt`
  (`senderCard`, header number), `status/StatusUi.kt`, `ScheduledPage.kt` (`d.chat.substringBefore('@')`).
- `android/people/.../PeopleIndex.kt`: `byNumber`, `byJid` (exact JID, then bare LID, then digits as number).
  `byJid` does `substringBefore(':').substringBefore('@')`, and for a LID with no record falls to
  `byNumber["+<lid digits>"]`: a LID whose digits equal a real number would name the wrong person.
- Phone: `android/platform/.../wa/WaIncoming.kt::waCallerNumber`, `phone/.../data/RecentsRepository.kt`,
  `phone/.../wa/WaCallSource.kt`, `WaCallsRouter.kt::jidOf` (digits + `@s.whatsapp.net`): four more copies.
- `GatewayEvent.ChatMerged`: handled only in `android/wa/.../DefaultWaRepository.kt::chatKey` (refresh `into`)
  and `avatars/WaAvatars.kt`. `MessagesOps.draftsRekey` and the prefs `rekey` action exist in the bridge but
  **no production Kotlin code calls them** (grep over `android/**/src/main`).

### 3.3 Bugs and risks visible today

1. **A LID with no name shows raw LID digits.** `PersonNames.ofJid` -> `jid.substringBefore('@')`; the same in
   `groupLines`, `ScheduledPage`, `WhatsAppPage` status rows. `docs/whatsapp-notes.md` says the label must be
   "WhatsApp user" (`WHATSAPP_USER_LABEL`). Hidden community members hit this permanently.
2. **Merge on the device loses or strands state.** `wa_sync/apply.rs::merge_chats` deletes the `from` chat row
   without carrying `pinned`, `archived`, `muted_until_ms`, `marked_unread`, `unread`, `hidden`, `cleared_ts`,
   `push_name`, `picture_id`; it does not rewrite `messages.sender`, the `reactions` JSON senders,
   `quoted_json`, mentions in `extra_json` or `deleted_by` (a group author stored as `123@lid` stays a LID
   forever, and a later reaction from the PN form does not replace the LID-form reaction of the same person);
   on an id collision the `from` copy is dropped with its device-only state (`starred`, cached media path,
   `client_id`).
3. **Drafts, mutes, SMS marks and notification tags are not moved on Android** when `ChatMerged` arrives
   (3.2). `docs/whatsapp-notes.md` promises they are. An open thread screen on the `from` JID is emptied by
   `Timeline::apply_event` and not re-pointed.
4. **Duplicate chats until the gateway happens to merge.** The device has no mapping of its own, so a LID
   chat and the PN chat of one person are two inbox rows (`inbox.rs` keeps a LID as a separate
   `PersonKey::Group`), with two notification tags and two unread counts, until `chat_merged`.
5. **Reactions / receipts / typing / presence that arrive under an unmapped LID miss.** The gateway emits
   them under the LID string; the device matches by exact `chat` (`UPDATE ... WHERE chat = ?1`), so a
   `MessageUpdate` or `Receipt` for `123@lid` silently changes nothing in the chat `90555...@s.whatsapp.net`.
   Presence and typing can never carry an alt (1.3), so they depend entirely on the mapping being known.
6. **Group receipts can double-count a member** (2.4 item 8).
7. **The people cache never learns a LID** (3.1), so `PeopleIndex.byJid("123@lid")` cannot name or picture a
   LID sender even when the gateway knows the number.
8. **Name order differs between layers.** `WaProfile::display_name` puts the WhatsApp-synced contact name
   first; `Person::name` is verified then push; `PersonNames` is device contact, verified, push, stored chat
   name, number. The owner's rule is the `PersonNames` one; the Rust helpers should not be used for display
   without the device contact step.
9. **`people::e164`** accepts any 5+ digits and prefixes `+` without a home region: `0532...` becomes
   `+0532...` and never matches `+90532...`.
10. **`PeopleIndex.byJid` LID-as-number fallback** (3.2).
11. **Relink to another account:** the device store is keyed by `instance_id` only, the people checkpoint by
    `LinkId { instance_id, gateway }`. A different WhatsApp account on the same instance would inherit the old
    chats, mappings and names on the device, and probably on the gateway (2.4 item 11).
12. **Calls from an unmapped LID** cannot be matched to a contact or called back (`RecentsRepository` drops
    them); correct, but they should be upgraded when the mapping arrives.

## 4. Edge cases to design for

| Case | Data | Correct behaviour |
|---|---|---|
| Group in LID mode | `chat = 1203...@g.us`, `sender = 111:3@lid`, maybe `participant_pn` | Chat key is the group JID, never merged. Author = identity of `111@lid`; show contact name if a PN is mapped and a contact owns it, else verified/push name, else "WhatsApp user". Keep the wire id (LID) for outbound keys |
| Group in PN mode | `sender = 90555:3@s.whatsapp.net`, maybe `participant_lid` | Same identity; learn the pair |
| Hidden community member | LID only, no PN ever | An identity with no number. Named by push name / display_name, else "WhatsApp user". No "call" or "add number" actions. Never formatted as a number. Stable across restarts. Not asked from usync by number |
| Business | PN or LID (or `@hosted` / device 99), `verified_name` | Name = contact, else verified name, else push name. `@hosted` folds into PN family, `@hosted.lid` into LID family |
| Number change | `ContactNumberChanged { old_jid, new_jid, old_lid, new_lid }` | One identity gets a second number. LID -> PN points to the new one; the old number stays as a *former* alias so old messages still resolve. Chats under old PN and new PN merge into one (survivor: new PN). Device contact that only has the old number still names the person (alias match), with lower priority than a contact holding the new number |
| My own LID | `device.lid`, `device.pn`, devices `:0`..`:N` | Both are "me". Messages / reactions / receipts whose sender is me under either form are `from_me`. Receipts from my other devices never move ticks. The self chat ("message yourself") has one key (my PN) |
| Mapping learned late | chat `123@lid` and chat `90555@s.whatsapp.net` both exist with history | Merge (section 5.5): messages, reactions, receipts, stars, edits, deleted marks, cursors, unread, pin, archive, mute, draft, notification tag, scheduled sends, outbox rows, open screen |
| Conflicting mapping | LID L was mapped to P1, now a directed source says P2 | Trust by source rank and recency (5.3). If accepted: P1 becomes a former alias *only if* the source is a number change; otherwise the identity is split back (L moves to P2's identity) and nothing already merged is un-merged automatically; log it |
| Stale mapping | PN P had LID L1, now L2 | Both LIDs alias the same identity; `current_lid = L2` |
| Gateway keys by LID, device by PN (or the reverse) | REST `chats` returns `123@lid`, device has the PN chat | Every id entering the device is normalised through the device mapping before any store access; the request to the gateway uses whichever id the gateway last used for that chat (`wire_jid` column) |
| Relink to a different account | `own_jid` changes for the same instance | Identity data is scoped to `(gateway, instance, own account)`. A changed own PN wipes or archives the mapping table, the chat store and the people cache for that link |
| LID digits equal to a phone number | `905321234567@lid` | Never compared across servers; keys are always `(kind, user)` |
| Device and agent suffixes | `123:7@lid`, `123.0:7@lid`, `90555:12@s.whatsapp.net` | Stripped by the one normaliser before anything else |
| Bots, PSA, newsletters, broadcast lists, status | `@bot`, `0@s.whatsapp.net`, `@newsletter`, `@broadcast` | Their own kinds; never merged with people |

## 5. Design sketch (no code)

### 5.1 Principles

1. One platform-free crate module owns identity: parsing, the mapping table logic, the canonical key, the
   merge plan. Proposed home: `crates/rosty_core/src/wa_identity.rs` (pure types and algorithms, unit tests)
   with persistence in `crates/rosty_app_core` (it already owns `people.rs` and `wa_sync`). Every other copy of
   "user part / is PN JID" (3.1, 3.2) is deleted and calls this module through `rosty_ffi`.
2. The gateway keeps resolving (it has the library's store), but it stops *hiding* the facts: it forwards
   both ids and the mappings. The device makes the final decision, so a mapping the gateway learns late, or a
   gateway that is older, cannot split a person on the device.
3. A stored row never loses the id it arrived with.

### 5.2 Types

- `WaId { kind, user }` with `kind` in `{ Pn, Lid, Group, Broadcast, Status, Newsletter, Bot, Other }`. No
  device, no agent. `Hosted` parses to `Pn`, `HostedLid` to `Lid`, `c.us` to `Pn`.
- `parse(text) -> Option<(WaId, device)>`: split at the last `@`; an unknown server gives `Other`; for
  `Pn` the user must be digits; strip `:device`; for non-LID kinds strip a `.agent` or `_agent` part; the LID
  user keeps its dots. Mirrors `impl FromStr for Jid`.
- `IdentityKey`: the canonical key of a person or chat. Rule: **the current phone number when one is known,
  else the LID** (`pn:<digits>` or `lid:<user>`), groups and others keep their own id. This matches the keys
  already stored everywhere (`<digits>@s.whatsapp.net`), so the migration is cheap. The stable internal handle
  is a numeric `person_id` so that a key change (LID -> PN, or number change) is one row update plus a merge.

### 5.3 The mapping table

Lives in **one shared place per link**. Two workable options:

- (A) extend the people cache (`people.json`, already shared by Phone and Messages through the people
  provider in `android/people`, already has `lid` and the join logic);
- (B) a small SQLite file `wa-identity-<instance>.db` in the same directory, exposed read-only through the
  same people provider.

I recommend (B) for the mapping and merge journal (it needs transactions and history) and keeping
`people.json` for names and pictures, keyed by `person_id`. Each chat store (`wa_sync`) keeps a copy of
`person_id` per chat and sender but not the mapping itself.

```
link(id=1, gateway_fp, instance_id, own_pn, own_lid, schema_version)
person(person_id PK, key TEXT UNIQUE, created_ms)
alias(kind, user, person_id, state, source, learned_ms, PRIMARY KEY (kind, user))
   -- kind: 'pn' | 'lid';  state: 'current' | 'former'
   -- at most one current pn and one current lid per person
merge_log(seq PK, at_ms, from_key, into_key, reason)   -- consumers replay from their own cursor
```

Source rank (higher wins on conflict, ties by `learned_ms`), following `lid_pn_write_policy`:
`number_change` > `peer_message` / `receipt` / `call` (the stanza itself named both) > `usync` /
`group_metadata` > `migration_sync` > `contact_action` > `history` / `device_notification` / `other` >
`history_old`.

### 5.4 Normalisation

`resolve(text) -> Resolved { person_id, key, kind, wire: WaId, device }`:

1. `parse`. Non-person kinds return themselves.
2. Look up `alias(kind, user)`. Hit: that person. Miss: create a person whose key is this id.
3. `key` = the person's current PN if any, else its current LID.

`learn(lid, pn, source, at_ms) -> Vec<MergeStep>`: find the persons of both aliases.
- Neither exists: create one person with both.
- One exists: attach the other alias.
- Both exist and are the same: refresh source / time.
- Both exist and differ: **merge** the LID person into the PN person (5.5).
- The LID is `current` for a different PN: apply the source rank. Accepted and source is a number change: old
  PN becomes `former` on the same person and the two PN persons merge. Accepted otherwise: move the LID alias
  to the new PN's person (a re-point, not a merge). Rejected: record nothing but a log line.

Invariants (unit-tested as properties): an alias belongs to exactly one person; a person has at most one
current PN and one current LID; `resolve` is idempotent and device-insensitive; `learn` is idempotent and
order-independent for non-conflicting pairs; a PN and a LID with the same digits are different aliases; own
PN and own LID always resolve to the `me` person.

### 5.5 Merge procedure

Input `MergeStep { from_key, into_key }` appended to `merge_log`. Each consumer applies it in one
transaction and stores the last applied `seq`, so a crash or an app that was not running catches up.

Chat store (`wa_sync`):
1. Move messages `from -> into`; on `(chat, id)` collision keep one row and OR the device-only fields
   (`starred`, `media_path`, `client_id`, the higher `status`, the longer `edit_history`).
2. Rewrite author references everywhere, not only in the merged chat: `messages.sender`, `deleted_by`,
   reaction senders inside the `reactions` JSON (dedupe: one reaction per person, newest wins),
   `quoted_json` sender, mentions.
3. Chat row: `unread` summed, `pinned` / `archived` / `marked_unread` OR-ed, `muted_until_ms` max,
   `cleared_ts` max, `hidden` AND-ed, names and picture from `into` else `from`.
4. `chat_sync`: `newest` = max, `oldest` = min, `reached_start` AND-ed, any open gap kept open; then
   `needs_resync`.
5. `outbox.chat`, scheduled sends.
6. Emit one "chat rekeyed" result so the UI layer moves: draft (keep `into`'s when both exist, as
   `Drafts::rekey` does), prefs (mute, allow list, SMS marks via `rekey_thread`), notification tag (cancel
   `from`, repost under `into`), conversation shortcuts, the open thread route, avatar cache key.

Invariants: no message, star or reaction is lost; no duplicate `(chat, id)`; applying the same step twice
changes nothing; after the step no row references `from_key`; total unread is preserved.

### 5.6 Contact matching and names

- Keep `caller_id::NumberBook` / `ContactIndex::match_number_e164` as the only number matcher (it already
  covers national vs international, one trunk `0`, `00`, `(0)`, home region, tails of 9, short codes, ranking
  by starred / updated). A WhatsApp PN is always international, so lookup is `"+" + digits`.
- Match a person against the contact book by **every** PN alias: current first, then former.
- Replace `people::e164` by `caller_id::e164_of` with the home calling code.
- Add WhatsApp-specific variant keys as an explicit, tested fallback. *Unverified, from memory:* Brazilian
  mobile JIDs may omit the ninth digit (`55 DD 9XXXX-XXXX` vs `55 DD XXXX-XXXX`), Mexico may carry `521` vs
  `52`, Argentina `549` vs `54` + `15`. The tail-of-9 rule does not cover the Brazilian case. Confirm with
  real data before adding.
- One name function in Rust, used by all surfaces: device contact (by alias), verified business name, push
  name, stored chat / group display name, formatted current number, else `WHATSAPP_USER_LABEL`. Never the LID
  digits. Whether the WhatsApp-synced `contact_name` (`WaProfile::contact_name`) gets a place between contact
  and verified name is an owner decision; today `PersonNames` ignores it and `WaProfile::display_name` puts
  it first.

### 5.7 What the gateway must expose

Additive, old clients skip unknown fields and events:

1. **`GET /v1/identity/mappings?since=<cursor>`**: `[{ lid, pn, source, learned_ms }]`. Needs either a
   gateway-side copy of learned pairs (a table filled by `Resolver` hits and by the events below) or read
   access to the library table; `Client` has no "list all" (1.2), the backend trait has
   `get_all_lid_mappings`.
2. **Event `identity_mapped { lid, pn, source }`** whenever the gateway learns or changes a pair, and
   **`identity_number_changed { old_pn, new_pn, old_lid, new_lid }`** from `ContactNumberChanged`.
3. **Alt ids on payloads**: `WaMessage.chat_alt`, `sender_alt`; `Reaction.sender_alt`; `Participant.lid` and
   `Participant.pn`; `Typing.jid_alt`; `Presence.jid_alt`; `CallUpdate.peer_alt`; `ChatSummary.alt`;
   `WaProfile.lid`. Plus `GroupInfo.addressing_mode`.
4. **`InstanceStatus.own_lid`** next to `own_jid`.
5. Keep the **wire id** per stored message (`messages.wire_chat`, `wire_sender`) and use it for
   `MessageKey.participant`, quote context and read receipts (2.4 item 9).
6. Fixes from 2.4: read `info.peer_recipient_pn`; feed `ContactAction.lid_jid`/`pn_jid` into
   `Client::add_lid_pn_mapping`; resolve mentions, quoted senders and history push names; run the rewrite for
   every LID seen as author / reactor / participant / profile, not only chat rows; count group receipts by
   person; use `MessageInfo::verified_name`; clear instance data when a different account is paired.
7. `ChatMerged` stays (older apps), but new apps act on `identity_mapped` and their own merge log.

### 5.8 Migration of existing stores

- Device `wa_sync` (schema v3 -> v4): add `chats.person_id`, `chats.wire_jid`, `messages.sender_person_id`,
  `messages.wire_sender`, a `merge_cursor` in `sync_state`. Backfill: resolve every existing `chats.jid`,
  `messages.sender`, reaction sender through the new table (creates persons; PN rows get a PN alias, LID rows
  a LID alias). Then fetch `/v1/identity/mappings` once and `learn` each pair: this produces the merge steps
  for every LID/PN duplicate already on the device. The migration itself only adds columns; merges run as
  normal steps, so it is resumable.
- People cache: `Person.lid` and `numbers` seed the alias table (`source = other`); afterwards `people.json`
  is keyed by `person_id`.
- Drafts / prefs / notification tags: rekeyed by replaying the merge steps (they are keyed by `wa:<jid>`).
- Gateway: a one-time pass that applies fix 6 to stored rows (sender / participant / reactions / profiles
  under a LID that now has a mapping).
- Old gateway + new app: no alt fields, no mappings endpoint; the app falls back to `ChatMerged` (treated as
  a `learn(from, into)` when `from` is a LID and `into` a PN).

### 5.9 Unit-test matrix (all platform-free)

Parsing / normalising
- `90555@s.whatsapp.net`, `90555:12@s.whatsapp.net`, `90555.0:12@s.whatsapp.net`, `123@lid`, `123:7@lid`,
  `12.34:7@lid`, `1-2@g.us`, `status@broadcast`, `x@newsletter`, `1@bot`, `90555@c.us`, `90555:99@hosted`,
  `123@hosted.lid`, `0@s.whatsapp.net`, empty, no `@`, non-digit PN user, unknown server.
- Same person, all device suffixes -> one key. PN and LID with equal digits -> two keys.

Learning
- lid then pn, pn then lid, both at once, repeated (idempotent), shuffled order of N pairs gives one result.
- Conflict matrix: every source pair x (same LID new PN, same PN new LID), accepted / rejected as ranked.
- Number change with unchanged LID; with a new LID; with only `old`/`new` and no LIDs.
- Own PN/LID: `me` under either form and any device.

Merge
- LID chat + PN chat, disjoint messages; overlapping ids (one starred, one with cached media, one with a
  higher status); empty target (rename); merge applied twice; crash between steps (replay from cursor).
- Reactions: same person reacted as LID and as PN -> one reaction, newest; removal under the other form
  removes it.
- Receipts: delivered as LID, read as PN -> one member, read; group of N counts each member once whatever
  mix of forms and devices; my own devices never count.
- Group authors, quotes, mentions, `deleted_by` rewritten in *other* chats.
- Chat flags: pin/archive/mute/unread/hidden/cleared combined as specified; drafts (both, one, none); prefs;
  outbox and scheduled rows follow; cursors and gaps.
- Late events: a reaction / receipt / edit / revoke / typing addressed to the old key after the merge lands
  on the merged chat.

Names and contacts
- Order: contact > verified > push > stored name > number > "WhatsApp user"; LID with nothing never shows
  digits; contact saved later wins; contact holding only the former number still matches.
- Contact spellings against a PN: `+90 532 123 45 67`, `0532 123 45 67`, `0090532...`, `532 123 45 67`,
  `+90 (0) 532...`, with and without home region, wrong country same tail, short codes, two contacts with the
  same number (ranking).
- Hidden member: no number actions, stable label, picture by LID.
- Business: verified name beats push name; hosted ids fold.

Link scope
- Different `own_pn` for the same instance resets the table; same account re-paired keeps it.

Compatibility
- Old gateway payloads (no alt, no own_lid) parse; `ChatMerged` drives `learn`; new fields are skipped by
  old clients (pinned JSON, as `events_are_pinned` does today).

## 6. Open questions for the owner

1. Canonical key: PN-when-known (cheap, matches today) or LID-first (where WhatsApp is heading; needed if
   usernames replace numbers)? The sketch isolates this in one function.
2. Should the WhatsApp-synced contact name (`contact_name`) rank between the device contact and the WhatsApp
   name, or stay unused?
3. On a rejected conflicting mapping, is a silent log line enough, or should a developer-mode entry show it?
4. Is un-merge needed (a wrong mapping that already merged two chats)? The sketch keeps wire ids so it is
   possible later, but does not build it.
