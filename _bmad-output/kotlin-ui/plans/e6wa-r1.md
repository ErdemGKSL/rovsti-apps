# E6-WA R1: identity (LID, phone JID, one person)

Branch `messages-kotlin`. Epic plan: `e6wa-plan.md`. Research: `../research/e6wa-identity.md` (sections quoted as
"ID n.n"). Goal of the round: every message, receipt, reaction, typing and presence lands on the right chat and person
whatever id it arrives under, a mapping learned later merges what was stored, and no surface shows LID digits.

Decision for this round: the alias table and the merge log live **inside the `wa_sync` store** (schema v4), not in a
file of their own, so a merge and the rows it moves are one transaction. Phone shows no messages (owner), so it does
not read this store: it gets its own small identity table for caller names in WA-R2, from the same Rust module and the
gateway's mappings. Canonical key: phone number when known, else LID (owner; kept in one function).

## Wire additions (agreed up front so the gateway and the client can be written in parallel)

All additive; an old client skips them, an old gateway omits them.
- `InstanceStatus.own_lid: Option<String>`.
- Alt ids, each the other form of the id next to it when the gateway knows it: `WaMessage.chat_alt`, `sender_alt`;
  `Reaction.sender_alt`; `Participant.lid`, `Participant.pn`; `Typing.jid_alt`; `Presence.jid_alt`;
  `CallUpdate.peer_alt`; `ChatSummary.alt`; `WaProfile.lid`; `GroupInfo.addressing_mode` (`"pn"` or `"lid"`).
- `GET /v1/identity/mappings?since=<cursor>` -> `{ items: [{ lid, pn, source, learned_ms }], next: <cursor>|null }`.
- Events `identity_mapped { lid, pn, source, learned_ms }` and
  `identity_number_changed { old_pn, new_pn, old_lid?, new_lid? }`. `ChatMerged` stays for old apps.
- `source` is one of `number_change`, `peer_message`, `receipt`, `call`, `usync`, `group_metadata`,
  `migration_sync`, `contact_action`, `history`, `device_notification`, `other` (rank in ID 5.3).
- Ids in these fields are bare (`user@server`, no device, no agent).

## Steps (one story each)

| Step | What | Layers | Who |
|---|---|---|---|
| 1 | `rosty_core::wa_identity`: `WaId` and `parse` (ID 5.2), `IdentityMap` with `resolve`, `learn` (source rank, re-point, number change) and the merge steps it yields (ID 5.4), the invariants as tests, the name rule with "WhatsApp user" for an unnamed LID. Pure, no I/O. The parsing and learning rows of the test matrix (ID 5.9). | `rosty_core` | subagent, reviewed |
| 2 | Gateway: the wire additions above; a table of learned pairs filled by the resolver and the events; the fixes of ID 2.4 (items 1-10: `peer_recipient_pn`, sweep every LID seen, mentions and quoted senders, history push names, `ContactAction` pairs, number change, receipts counted by person, wire ids kept for outbound keys, envelope verified name). Item 11 (account change wipe) is WA-R2. Own commit; reported to the main thread when pushed. | `services/wa_gateway` | subagent, reviewed |
| 3 | `rosty_wa_proto` and `rosty_wa_client`: the new fields, the mappings call, the two events; pinned JSON tests for old and new payloads. | proto, client | with step 2 |
| 4 | `wa_sync` schema v4: `person`, `alias`, `merge_log`, `chats.person_id` / `wire_jid`, `messages.sender_person_id` / `wire_sender`, merge cursor, `own_lid`; backfill of existing rows; persistence of the `IdentityMap`. | `rosty_app_core` | subagent, reviewed |
| 5 | `wa_sync` applies identity: every incoming chat, message, reaction, receipt, typing and presence is resolved before it is matched; mappings from events, alt ids, the endpoint (catch-up on open and reconnect) and `ChatMerged` (old gateway) feed `learn`; the full merge of ID 5.5 (messages with collisions, senders, reactions, quotes, mentions, chat flags, cursors and gaps, outbox and scheduled rows), replayable from the cursor; the merge rows of the test matrix. | `rosty_app_core` | with step 4 |
| 6 | FFI and bridge: `wa_sync` answers carry the canonical key and the wire id; a "chat rekeyed" result per merge; ops `wa_identity.parse` / `resolve` for Kotlin; fixtures and contract tests. | `rosty_ffi`, `:core-bridge` | subagent, reviewed |
| 7 | Kotlin follows a merge: drafts, mute and other prefs, notification tags, the open thread route and the avatar cache key move to the merged chat; the Kotlin copies of JID parsing (`numberOfJid` and the others in ID 3.2) call the bridge; `PersonNames` never shows LID digits. | `:wa`, Messages, Phone | lead |
| 8 | Contacts match every number alias of a person, current then former, with the home region (`people::e164` goes through `caller_id::e164_of`); the names and contacts rows of the test matrix. | `rosty_core`, `rosty_app_core`, `:people` | lead |

Order: 1, 2 and 3 in parallel (different crates); 4 and 5 after 1; 6 after 5; 7 and 8 after 6.

Close: merge `origin/ui-overhaul`, the gate with Rust (`scripts/kotlin-ui-check.sh`) plus
`cargo test -p wa_gateway --profile checks`, note at the top of `e6-status.md`, message to the main thread naming the
gateway commit.

## For the close note (asked by the main thread)

**Database backup before the gateway's v7 migration: yes.**
- The migration itself is additive and runs in one transaction (`store.rs::migrate`), so a failed migration leaves v6.
- But there is no way back without a copy: a gateway older than this one refuses a v7 database ("database schema v7
  is newer than this gateway"), so rolling the binary back needs the pre-v7 file.
- The first connect after the deploy rewrites rows (the LID sweep renames authors, reactions, receipts, quotes,
  mentions and profiles to the number, and joins receipt rows); that is not reversible either.
- What to copy: `gateway.db` in the gateway's data directory (`WA_GATEWAY_DATA`), with the gateway stopped, or with
  `sqlite3 gateway.db ".backup gateway-v6.db"` while it runs (the store is WAL). `instances/<id>/wa.db` (the Signal
  keys) is not migrated by this change; `config.example.toml` already says to back the directory up.

**What to try on the real gateway, in order**
1. After the deploy, read the log of the first connect: `lid-merge: sweep moved M of T stored LIDs in N ms`
   (`wa/lid.rs::sweep_with`, commit `7dc63514`). `N` is the time of one sweep; the line is written when the sweep found
   LIDs or took 250 ms or more. A sweep runs at every connect and on a device-list or number-change event. If `N` is
   regularly above about a second, the listing (`store/identity.rs::lids_seen`, `LIKE '%@lid'` over `messages`) needs
   an index or a rarer sweep: tell this thread.
2. `GET /v1/identity/mappings` with the connect key: the pairs the library knew at connect are listed; `next` pages.
3. `GET /v1/instance`: `own_lid` is filled.
4. A group whose members show as LIDs in WhatsApp (a community group): open it, the authors are numbers or names where
   a pair is known, and each participant carries `lid` and `pn` in the group info answer.
5. Send a reaction and a reply in a LID-addressed group from Rosty and look at the phone: the reaction sits on the
   right message and the reply quotes it (the outbound keys now use the wire id; not verified on the wire).
6. Read a group message in Rosty: on the sender's phone the ticks turn blue once, not early (receipts by person).
7. Send a message from the phone to a contact whose chat is LID-addressed: it lands in the number chat, not a new
   LID chat (`peer_recipient_pn`).

## Evidence

- **Step 1** (`7e8759d8`, merge `9986fc23`): `rosty_core::wa_identity` (`id`, `source`, `rows`, `map`, `name`), 63 tests
  including the conflict matrix over every source pair and a random-operations invariant test.
- **Steps 2 and 3** (`fb215b14` proto and client, `9dab1c9f` gateway, `9e70600f`, merge `4e7c9f03`): wire additions as
  agreed; gateway table `identity_pairs`, route and events, store migration v7, the fixes of ID 2.4 (item 7 partly: the
  chat of an own-device receipt cannot be recovered at 0.7.0). Never run against WhatsApp. `apps/*` struct literals no
  longer compile with the new fields (not touched, by instruction).
- **Step 4 and the store half of 5** (`55e44d01`, merge `1e292692`, fixture `bf874965`, `afbdd7e1`): schema v4
  (`person`, `alias`, `merge_log`, `wire_jid`, `wire_sender`, own ids, merge cursor, backfill cursor; no `person_id`
  columns: rows stay keyed by the canonical key), resolve before match on ingest, the logged and replayable merge,
  backfill. 41 new `wa_sync` tests.
- **Rest of step 5 and step 6** (`d29f12f8` store, `7ea29482` FFI, `1073af50` bridge, merge `35452cda`): alt ids,
  the two events, `ChatMerged` and the mappings endpoint (resumable cursor) feed `learn`; a known pair writes nothing;
  a read under an old key follows the merge. Ops `wa_sync.identity_resolve`, `rekeyed_since`, `identity_catch_up`,
  `identity_observe`, `wa.identity_mappings` (`supported: false` on an old gateway), `wa_identity.parse`; answers
  carry `rekeyed` steps.
- **Step 8** (`15d22770`): `people::e164` goes through `caller_id::e164_of` with the home calling code;
  `wa_sync.identity_numbers` lists the current then the former numbers of a person; `PersonNames` looks a contact up
  by each. 12 new Rust tests (five spellings with and without a home region, wrong country same tail, former number
  only, hidden member).
- **Step 7** (`32e9f992` bridge, `293b3950` shared modules, `261f7b8f` Messages, `56114148` Phone and Contacts, merge
  `cfe141e5`):
  - `WaIds` (`:core-bridge`) is the one Kotlin JID helper. It is a Kotlin port, not a native call (its callers are
    synchronous); `WaIdsTest` checks it against the Rust fixtures and `WaIdsNativeTest` against the real library.
    Every other copy is gone except the log mask's domain list.
  - `WaRekeys` (Messages) follows the store's merge log from its own cursor (`wa-rekeys.json`) and runs the movers in
    order: draft and mute, announcer lines, the conversation on screen, the notification, message reminders,
    scheduled texts. The open thread swaps its key and keeps its rows, reply bar and draft.
  - The sync runner catches up on pairs and own ids before the chat list; ids from notifications and deep links are
    resolved before the first read; React, Revoke and MarkRead send the wire sender.
  - A LID nobody names reads "WhatsApp user" (en, tr) on every Messages surface.
  - Phone: a call whose peer is a LID dials the number of `peer_alt`.
  - No golden changed.
- Not done in this round (WA-R2): Phone's identity table (a LID caller with no `peer_alt` has no number); the rekey
  cursor reset on an account change; Rust `wa_digest` titles; outbox sends still name the chat by the canonical key;
  group info is fetched nowhere in Messages, so only profile answers reach `store.observe`.
- Close: see the WA-R1 note in `e6-status.md`.
