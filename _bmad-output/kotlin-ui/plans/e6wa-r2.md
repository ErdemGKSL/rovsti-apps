# E6-WA R2: cache limits, full history, account and link

Branch `messages-kotlin`. Epic plan and owner answers: `e6wa-plan.md`. Research: `../research/e6wa-cache.md` (quoted
as "CA n.n"), `../research/e6wa-identity.md` ("ID n.n"). Base: WA-R1 closed at `c966c5ab`.

Goal of the round: media obeys a size cap and the owner's auto-download rules and survives a crash; the whole
WhatsApp history arrives with visible, resumable progress; a gateway restart or another account on the link can no
longer leave wrong or stale rows; a second Rosty app needs no pasted key; Phone names a caller that arrives under a
LID and offers a "message" shortcut into Messages.

Owner answers that bind this round: Messages owns the cache and Phone shows no messages or media (so no cache
provider); the connect key is shared between Rosty apps signed with the same key, paste stays as the fallback; media
512 MiB LRU with starred and saved files exempt, Wi-Fi: images, voice notes, stickers, mobile data: images and voice
notes, video and documents on tap, thumbnails kept; full history from WhatsApp with progress, resumable; another
account wipes the old cache at once; the gateway keeps messages as today.

## Wire additions (agreed up front; all additive)

- **Epoch**: `InstanceStatus.epoch: Option<String>` and `epoch` on the event socket's first frame and on `resync`
  (`{"type":"resync","epoch":"…"}`). One random id per gateway process. A device that stored another epoch treats it
  as a gap whatever its `last_seq` says (CA 3.1, the restart hole).
- **History**: `InstanceStatus.history: Option<{ state: "idle"|"syncing"|"done", progress: 0..=100|null,
  chats: u32, messages: u64, oldest_ms: i64|null }>`; `history_synced` gains `sync_type` (`"initial"`, `"recent"`,
  `"full"`, `"on_demand"`, `"other"`), `messages: u32` and `oldest_ms`. Old fields keep their meaning.
- **Account**: `InstanceStatus.account_epoch: Option<u32>`, raised each time the linked account (own number) changes;
  event `account_changed { old_pn?, new_pn }`. The gateway clears chats, messages, reactions, receipts, profiles,
  statuses, scheduled rows, identity pairs and cached media of the old account before it announces it.
- No new media route this round (re-upload and `Range` are WA-R7 / WA-R3).

## Steps (one story each)

| Step | What | Layers | Who |
|---|---|---|---|
| 1 | Gateway epoch and account change: the wire fields above; the wipe when the linked account differs from the stored own number (ID 2.4 item 11); migration if a column is needed. Own commit, reported. | `services/wa_gateway`, proto, client | subagent A, reviewed |
| 2 | Gateway full history: the device asks for a full sync (`DevicePropsOverride::with_require_full_sync` and the history config, verified in whatsapp-rust 0.7.0 source: when the props are sent, whether an already linked device must relink); history blobs imported in bounded transactions; the `history` status and the richer `history_synced`; the state survives a gateway restart. Own commit, reported. | `services/wa_gateway`, proto, client | subagent A, reviewed |
| 3 | `wa_sync` schema v5, media: table `media` (CA 4.1) backfilled from the three `media_*` columns; pure policy `media::policy(kind, size, network) -> Auto|OnTap|Never` with the owner's rules (never view-once, nothing on roaming or Data Saver, nothing under 500 MiB free); eviction to a cap, LRU, exempt: starred, saved, unsent outbox files, opened in the last 24 h; `.part` reconcile at open (`downloading` back to `queued`, a `ready` row with no file becomes `evicted`); a download queue with attempts. | `rosty_app_core` | subagent B, reviewed |
| 4 | `wa_sync` v5, sync state: stored `epoch` (another epoch is a gap), `account_epoch` and the account wipe (rows, identity tables, merge log, outbox, media rows; answers `account_changed` with the files to delete), history progress kept from status and events, unlink cleanup (`wipe`). | `rosty_app_core` | subagent B |
| 5 | FFI and bridge for steps 3 and 4: ops, fixtures, `OPS.md`, Kotlin models, facade and fakes. | `rosty_ffi`, `:core-bridge` | subagent B |
| 6 | Messages media: downloads go through the queue and the policy (network kind from `:platform`), write `.part` and rename; the cap and the auto-download rules in WhatsApp settings (cap choices 256 MiB, 512 MiB, 1 GiB, 2 GiB, no limit; used size shown); an evicted file shows its thumbnail and downloads again on tap; an account change or unlink deletes the store, the media directory, drafts, mutes, the rekey cursor and the names of that link. | Messages, `:platform` | subagent C after 5 |
| 7 | Messages history: a progress row on the WhatsApp tab while the history arrives (percent, chats so far, "you can keep using the app"), kept across restarts and resumed by the sync job; the epoch reaches the runner; tests and shots. | Messages | subagent C |
| 8 | Link sharing and Phone: a signature-permission provider in `:wa` hands the connect key to a same-signed Rosty app that has none (asked at start and from the link screen; paste stays); Phone keeps its own small identity table (same Rust module, fed by `wa.identity_mappings` and call alt ids) so a LID caller gets a number and a name, and the contact card and recents get a "message" action that opens Messages on that chat. | `:wa`, Phone, Messages, Contacts, `rosty_app_core`, `rosty_ffi` | subagent D |

Order: 1+2 (A) and 3+4+5 (B) in parallel (the wire shapes above are fixed); 6+7 (C) after B; 8 (D) after A, alongside C
if the disk allows.

Out of this round: the full-screen viewer and save to gallery (WA-R3), media re-upload and on-demand older history
(WA-R7), FTS search (WA-R7), a cache provider (dropped by the owner).

Risks to check first
- Device props are sent when a device registers. If so, an existing link gets the full history only after a relink:
  the plan then needs a "relink to load full history" note for the owner, not a silent change.
- A full sync can be large: bounded transactions on the gateway, paged store on the device, no whole blob in memory.
- The account wipe deletes user state (drafts, outbox): it runs only on a proven different own number, never on a
  missing one.

Close: merge `origin/ui-overhaul`, the gate with Rust plus `cargo test -p wa_gateway --profile checks`, note at the top
of `e6-status.md` (gateway commits, backup need, what to try), message to the main thread.

## Evidence

- **Steps 1 and 2** (`748a7973` proto and client, `91b74e14` gateway, merge `bc12444c`): the wire additions as agreed,
  plus a first socket frame `hello { epoch }`. Store migration v8 (six columns on `instances`, additive).
  - Another phone number on a link wipes the instance's account data in one transaction, deletes its media and
    pictures, raises `account_epoch`, then announces `account_changed`. Only two known, different numbers count.
  - Full history: config `full_history` (`WA_GATEWAY_FULL_HISTORY`, default on). **Device props go out only when a
    device registers** (`wacore-0.7.0/src/store/device.rs` `get_registration_payload`; `whatsapp-rust-0.7.0/src/bot.rs:1178`
    "Only applied on the initial pairing"), so an existing link must be relinked to get it. The gateway then pairs as
    the Windows client row (`UWP`, `require_full_sync`, `full_sync_days_limit: 365`): the phone lists the device as
    Windows. History is imported in transactions of at most 2 000 rows; the state survives a restart.
  - `history.progress` (0 to 25 recent, 25 to 100 full) and the "quiet for 30 minutes reads done" rule are guesses,
    not WhatsApp facts. Never run against WhatsApp.
- **Steps 3 to 5** (`wa_sync` schema v5, FFI, bridge; merge `bbda725c`): table `media` (backfilled; the three
  `messages.media_*` columns are no longer used), pure policy by network, download queue with back-off, cap (default
  512 MiB) with starred, pinned, saved, unsent and recently used files exempt, reconcile at open; stored epoch
  (another epoch is a gap), account wipe on a proven different own number or a higher `account_epoch`, history
  progress, `wa_sync.wipe`. Twelve new ops and their fixtures; `WaSyncStore` facade and `FakeWaSyncOps` follow.
- **Connection** (merge `5b8b9374`): `hello`, `resync { epoch }`, `account_changed`, the richer `history_synced` and
  the status fields of a `state` event reach the store through `apply_events`; `identity_observe` takes the whole
  status answer. Kotlin wire models carry the new events and fields (`GatewayEvent.Resync` is now a class).
  Rust: 2 589 tests on the touched package set, 0 failed; `:core-bridge` 124 unit and 104 native tests.
- Not built from the research: the copy before a multi-step migration, `quick_check` on a failed open.
- **Step 8**:
  - Link hand-over: `:wa` `dev.rosty.wa.share` (`WaLinkShare` contract, `WaLinkProvider`, `WaLinkHandover`,
    `ResolverSiblingLinks`, `PrefsWaLinkMarks`). Each of Messages, Phone and Contacts declares the provider under
    `<applicationId>.walink`, exported behind the signature permission `dev.rosty.permission.WA_LINK` (read and
    write), holds the permission and queries the two sibling authorities. `content://<pkg>.walink/link` answers no
    row or one row (`gateway_url`, `instance_id`, `key`). The provider checks the caller's signing certificate itself;
    the asking side only asks a package whose provider it owns and whose signature matches. An app without a link
    asks when its WhatsApp repository starts (`ShellContainer.createWa`) and when the link screen opens; the first
    usable answer goes through `WaRepository.relinkTo`, as a pasted key does.
  - Unlink is per app: the siblings keep their link. The app remembers a one-way mark of the link it removed
    (SHA-256 of instance id and key, never the key) and does not take that same link back by itself; the start page
    then shows "Use the link from <app>". A sibling with another link (new key, other instance) is adopted as usual.
  - The linked page says where the link came from in the footer of the status group (en, tr). Stops is not part of
    the hand-over (its manifest is unchanged): it still links by QR or pasted key.
  - Phone identity: `rosty_app_core::phone::wa_idbook` (`wa-identity.json`, the `IdentityMap` rows, the mappings
    cursor, own ids and account epoch; one instance per file), ops `wa_idbook.snapshot`, `catch_up`, `learn`,
    `account`, `wipe`, `:core-bridge` `WaIdentityBook` with `FakeWaIdBookOps`. `WaIdentitySync` (Phone) opens it for
    the link's instance, notes the account, pages `wa.identity_mappings` from the stored cursor when connected, on
    `hello` and on `resync`, takes `identity_mapped`, `identity_number_changed`, `account_changed` and state events,
    and learns `peer` / `peer_alt` of calls. `resolvedWaCalls` fills `peerAlt` of a LID peer before Recents, caller id
    and the call screens read the call; a LID nobody maps reads "WhatsApp user". The FCM path asks the table through
    `WaPush.lidNumber`.
  - "WhatsApp message" in the Recents row menu and the person sheet's menu opens the chat in Rosty Messages
    (`dev.rosty.action.WA_CHAT`); hidden while Messages is not installed. Phone now holds `HANDOFF`.
  - Not proven here: the signature check on a device (no keystore), the hand-over between two installed apps, a real
    LID call. A call from a LID nobody maps is still left out of Recents (it has no number to list or dial).
- **Steps 6 and 7** (merge `aa227001`): `:platform` `DeviceData`; Messages `WaDownloads` (queue worker, `.part`
  then rename, reconcile and eviction at open), storage settings, history card, account-change and unlink cleanup;
  the runner observes the status on every catch-up. 20 new goldens (`e6wa-r2-*`), none changed.
- **Close**: merged `origin/ui-overhaul` (`4df9a7f2`); gate green without the Stops tests at `832ca834` (Rust 3 074
  tests). See the WA-R2 note in `e6-status.md`.
