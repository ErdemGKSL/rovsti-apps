# E6-WA: full WhatsApp in Rosty (plan)

Asked by the owner through the main thread on 2026-11-15, after E6 R5. Branch `messages-kotlin`; one round at a time,
at most 8 stories a round, each round closed with the gate and a note in `e6-status.md`, as in E6.

Goal: Messages carries essentially every WhatsApp feature that whatsapp-rust offers, with polished UI; the WhatsApp
parts Phone needs too live in shared modules; chats open instantly and offline from one on-device cache; every message,
receipt and reaction lands on the right chat and person whatever id it arrives under.

Research this plan is built on (read from source, nothing was run against WhatsApp; comparisons with other clients are
from memory and marked so in the reports):
- `research/e6wa-inventory.md`: whatsapp-rust 0.7.0 (crates.io, the version `Cargo.lock` pins) feature by feature
  against the gateway, `rosty_wa_client` / `wa_sync`, the bridge and the UI.
- `research/e6wa-identity.md`: LID and phone JIDs in the library, the gateway and Rosty; bugs seen today; the design.
- `research/e6wa-cache.md`: the store and media cache today, sharing between apps, gateway history, proposals.

## Findings that shape the plan

- The library blocks almost nothing: only server-side search (none) and video calls (transport only, pre-encoded
  H.264). Most gaps are in the gateway or the UI. Two are UI-only today: voice-note recording and editing a sent message.
- Identity: the gateway already resolves LID to phone JID and merges LID chats, but it shows one id per person and
  misses LIDs seen only as group authors, reactors, receipt participants, mentions and quotes. On the device "user part
  of a JID" is written about ten times (Rust and Kotlin); a device chat merge drops pin, archive, mute and unread and
  moves no drafts; events under an unmapped LID miss silently; a LID with no name shows its digits.
- Cache: the store (`wa_sync`, SQLite, schema v3) is shared Rust, but the engine around it (`WaOffline`, `WaSyncRunner`,
  outbox worker, `WaMedia`) and the media files live inside Messages; Phone does not use the store. Nothing deletes a
  store or its media on unlink or account change. The gateway's event ring is in memory with no epoch, so a gateway
  restart can lose events silently; expired media cannot be fetched again; only recent history is synced.

## Decisions (the owner answered the questions on 2026-11-15; see the end)

1. **Identity lives in Rust, platform-free**: `rosty_core::wa_identity` (parse, canonical key, alias table logic, merge
   plan, all unit tested) with persistence in `rosty_app_core`. Canonical key: the phone number when known, else the
   LID; a numeric `person_id` is the stable handle; a stored row never loses the id it arrived with. Every other copy of
   JID parsing is deleted and goes through it. Names keep the order contact, WhatsApp name (verified, push, stored chat
   name), readable number; never LID digits ("WhatsApp user" when nothing is known).
2. **The gateway stops hiding ids**: a mappings endpoint and `identity_mapped` / number-changed events, alt ids on
   messages, reactions, participants, typing, presence, calls and profiles, `own_lid`, and its own fixes (the report's
   list 2.4). The device makes the final merge, from a replayable merge log, so a late or older gateway cannot split a
   person. Old gateway + new app falls back to today's `ChatMerged`.
3. **One cache, owned by Messages; Phone shows no WhatsApp messages or media** (owner): Phone has only a "message"
   shortcut that opens Messages, and keeps using the shared identity, name, photo and call code. So there is no
   cross-app cache provider: the sync engine and the media cache stay in Messages. What both apps need is shared as
   code (`rosty_core::wa_identity`, `rosty_wa_client`, `:wa`, `:people`); Phone keeps its own small identity table,
   filled from the gateway's mappings, for caller names.
4. **Schema**: v4 adds a `media` table (state, pin, hash, size, last use), `person_id` and wire ids on chats and
   messages, the merge cursor, `account_jid` and the gateway epoch in `sync_state`; v5 adds FTS5 for search inside
   chats. Migrations only add columns and tables; merges run as normal resumable steps.
5. **Media rules**: thumbnails kept in the store (capped); files in an LRU cache with a configurable cap (default
   512 MiB), starred and saved files exempt; auto-download by network (Wi-Fi: images, voice notes, stickers; mobile
   data: images and voice notes; video and documents on tap); `.part` downloads; Save puts a copy in the gallery or
   Downloads through MediaStore; an evicted file downloads again, through media re-upload when the link expired.
6. **Sync**: the global event sequence stays, with a gateway epoch so a restart forces a catch-up; per-chat gap paging
   stays. **Full history** is asked from WhatsApp (owner; the gateway's device props change for it), with progress
   shown and the sync resumable; older history on scroll and for search still uses on-demand history when a chat's
   start has not arrived.
7. **Relink and second app**: same account relinked keeps the cache, drafts and stars; a different account on the link
   wipes the store and media of the old one on the device and the gateway; the connect key is **shared
   automatically between Rosty apps signed with the same key** (owner): linking once in any app links all of them
   through a signature-permission provider in `:wa`; pasting the key stays as the fallback; unlink deletes the store
   and its media.
8. **Calls**: 1:1 voice calls stay as they are in Phone. Video and group calls are out of this epic (the library gives
   transport only; the gateway wires audio only).

## Rounds (ordered by user value, foundations first where features depend on them)

| Round | Theme | Stories |
|---|---|---|
| WA-R1 | Identity | `rosty_core::wa_identity` with the test matrix; alias table and merge log (shared per link); gateway: alt ids, mappings endpoint and events, `own_lid`, the fixes; `wa_sync` v4 identity columns and the full merge (pin, archive, mute, unread, senders, reactions, quotes, mentions, outbox); drafts, prefs and notification tags follow a merge; Kotlin JID copies removed; contact matching by every number alias with the home region. |
| WA-R2 | Cache, history and link | `media` table, limits, eviction, auto-download rules, `.part` files; full history sync with progress, resumable (gateway device props, history events); gateway epoch; account-change wipe and unlink cleanup on device and gateway; the connect key shared automatically between Rosty apps (paste as fallback); Phone's identity table for caller names and its "message" shortcut into Messages. |
| WA-R3 | Media | Full-screen viewer: pinch, double-tap zoom, swipe between items, video playback; save to gallery, share, forward; stickers and GIFs; view-once; thumbnails, dimensions and waveform on sent media (gateway); several files with captions; voice-note recording (hold to record, slide to cancel, lock). |
| WA-R4 | Message actions | Edit a sent message; forward with the forwarded mark (gateway route); delete for me; played receipts; message info per recipient in groups; mentions; link previews; disappearing messages (timer on send, expiry in the store, chat setting). |
| WA-R5 | Rich content and presence | Polls (show, vote, create); locations (show on the Stops map tiles, send current place); contact cards (send); online, last seen and typing in the thread header (shared with Phone through `:wa`); presence subscription rules. |
| WA-R6 | Groups | Group info page (subject, description, picture, members with roles); admin actions (add, remove, promote, demote, announcements only, approval); invite link (show, reset, join); create and leave a group; communities shown read-only. |
| WA-R7 | Account and history | Status: video, post, reply, privacy; blocklist; privacy settings; own name, about and picture; expired media through re-upload; older history on demand; search inside chats (FTS5) with gateway search for what is not on the phone. |

Each story names its layers (library call, gateway route or event, `rosty_wa_proto` / `rosty_wa_client`, `wa_sync`,
FFI op and fixture, bridge, `:wa` / `:people` / `:platform` / `:design`, app UI) in the round's plan file. Domain rules
stay in Rust with unit tests. The WhatsApp protocol, gateway and client parts go to subagents and are reviewed before
commit. A gateway change is its own commit and is reported to the main thread when it lands (it deploys itself).
Screenshot review checks layout (fit, overflow, clipping, wraps, alignment, safe area), not blur. `apps/*` is not touched.

## Edge cases the identity and cache work must cover (tests named in the round plans)

Group LIDs and LID-addressed groups; community members with no number ever; business accounts and verified names; a
contact who changes number (old number kept as a former alias); my own LID and my other devices; device and agent
suffixes on receipts; a mapping learned after messages were stored (merge of chats, receipts, reactions, stars, mutes,
drafts, notification tags, with no row lost and no duplicate); conflicting or stale mappings (ranked by source);
chats keyed by LID on the gateway and by number on the device; relink to another account; the owner app not installed
or killed; a gateway restart between two events; a download cut off half way; a file evicted and its link expired.

## Owner answers (2026-11-15, through the main thread)

1. Messages owns the cache. Phone shows no WhatsApp messages or media, only a "message" shortcut that opens Messages;
   it keeps the shared identity, names, photos and calls. The cache provider is dropped unless identity sharing needs it.
2. The connect key is shared automatically between Rosty apps signed with the same key; paste stays as a fallback.
3. Media as proposed: 512 MiB LRU, starred and saved items exempt; Wi-Fi: images, voice notes, stickers; mobile data:
   images, voice notes; video and documents on tap; thumbnails kept.
4. Full history from WhatsApp; changing the device props is accepted; show sync progress and keep it resumable.
5. A different account wipes the old cache at once.
6. The gateway keeps messages as today.
7. The canonical key is the number, with the LID as fallback.
8. WhatsApp's own address-book name is not used.
9. No un-merge UI; the design stays open for it (wire ids are kept).
