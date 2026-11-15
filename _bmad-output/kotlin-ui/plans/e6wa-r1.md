# E6-WA R1: identity (LID, phone JID, one person)

Branch `messages-kotlin`. Epic plan: `e6wa-plan.md`. Research: `../research/e6wa-identity.md` (sections quoted as
"ID n.n"). Goal of the round: every message, receipt, reaction, typing and presence lands on the right chat and person
whatever id it arrives under, a mapping learned later merges what was stored, and no surface shows LID digits.

Decision for this round: the alias table and the merge log live **inside the `wa_sync` store** (schema v4), not in a
file of their own, so a merge and the rows it moves are one transaction. Phone reads it through the cache provider
that WA-R2 builds. Canonical key: phone number when known, else LID (owner question 7 can change it; one function).

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

## Evidence

(per step, filled in as each lands)
