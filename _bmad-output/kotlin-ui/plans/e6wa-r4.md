# E6-WA R4: message actions

Branch `messages-kotlin`. Epic plan: `e6wa-plan.md`. Research: `../research/e6wa-inventory.md` (the rows Edit, Delete
for everyone, Delete for me, Forward, Mentions, Link previews, Disappearing messages, Played tick, Per-recipient receipts
and the ranked list items 2, 7, 9 and 13). Base: `messages-kotlin` at `866062a1` (R3 closed, people sync v3, vault 7c).

Goal of the round: the long-press menu of a WhatsApp message does what WhatsApp's does: edit my message in the
composer, forward it with the "Forwarded" mark, delete it for me or for everyone, see who in a group got, read or played
it and when; `@` names people in groups and a mention reaches me even in a muted chat; links show their preview card
both ways; a chat's disappearing timer is honoured on send, can be changed here, and its messages vanish from the phone
on time.

Library facts (whatsapp-rust 0.7.0 from crates.io, the version `Cargo.lock` pins; `LIB/` =
`~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/whatsapp-rust-0.7.0/src/`, `WACORE/` = `.../wacore-0.7.0/src/`,
`PROTO/` = `.../waproto-0.7.0/src/whatsapp.proto`):

- Edit: `Client::edit_message` `LIB/client/messaging.rs:152`; the gateway already sends and applies edits (15 minutes).
- Forward: `Client::forward_message(to, &wa::Message)` `LIB/send/mod.rs:887` runs `prepare_for_forward`
  (`WACORE/proto_helpers.rs:372-409`): `is_forwarded`, `forwarding_score` raised by one and set to 127 from 5 on (WA
  Web's "frequently forwarded" sentinel), the quote and mentions stripped, media relayed from the same CDN blob. It needs
  the original protobuf: the gateway keeps it for media (`raw_proto`), a text is rebuilt from its body.
- Delete for me: `chat_actions().delete_message_for_me` `LIB/features/chat_actions.rs:600`; route exists.
- Played: `mark_as_played` `LIB/receipt.rs:915`; route exists; the `Receipt` event carries a timestamp
  (`WACORE/types/events.rs:1643-1652`) the gateway does not keep today.
- Mentions: no helper; `ContextInfo.mentioned_jid` is set by the caller (`PROTO` `ContextInfo`), the text carries
  `@<user>` tokens.
- Link previews: no helper and no fetcher in the library (no match for a preview builder in `LIB/`). A preview is the
  caller's `ExtendedTextMessage` fields `matchedText`, `title`, `description`, `jpegThumbnail`, `previewType`
  (`PROTO:3209-3221`). The main `Message.ExtendedTextMessage` of 0.7.0 has **no `canonicalUrl`** (field 4 is gone
  there; only the consumer-application copy at `PROTO:1471-1479` has it): the link travels as `matchedText`.
- Disappearing: `SendOptions::with_ephemeral_expiration` `LIB/send/mod.rs:289, 318` sets `contextInfo.expiration`
  (promotes a bare text, `WACORE/proto_helpers.rs:468`); 1:1 timer `set_chat_disappearing_timer` `LIB/client/iq_ops.rs:129`
  (sends the `EPHEMERAL_SETTING` protocol message `:201-217`); groups `groups().set_ephemeral`
  `LIB/features/groups.rs:1015`; a group's timer in `GroupMetadata.ephemeral` `LIB/features/groups.rs:185` and in the
  `GroupUpdate` action `Ephemeral { expiration, trigger }` (`WACORE/stanza/groups.rs:203-210`); history conversations
  carry `ephemeralExpiration` (`PROTO:1906`). `Event::DisappearingModeChanged` (`WACORE/types/events.rs:810`) is a
  contact's **default** for new chats, not a chat's timer: not used. The library keeps no chat timer and applies none on
  send: the gateway must.

## Owner decisions

- **Link previews when sending (2026-11-18, through the main thread; replaces "do not fetch URLs")**: build them.
  The phone fetches the URL, the gateway never does (as official WhatsApp). The fetch runs when a link appears in the
  composer, debounced (600 ms after typing stops), for the first URL only; at most 512 KiB of HTML, short timeouts, only
  `og:` tags and `<title>`, no scripts. The image is downscaled into the JPEG thumbnail WhatsApp expects, within the
  existing 64 KiB thumbnail limit. The composer shows a preview chip with an "x" that drops the preview for this
  message. A "Link previews" switch in Messages > WhatsApp settings, on by default; off: nothing is fetched. On the wire
  the preview is the `extendedTextMessage` fields (title, description, URL, thumbnail), through an additive send option.
  HTML and `og:` parsing and URL detection in Rust (pure, unit tested); the fetch in Kotlin with fakes in tests. Only
  `http` and `https`, no redirect to another scheme, no cookies, a generic User-Agent, private and loopback addresses
  skipped.

## Wire additions (agreed up front; all additive)

- `SendText` gains `mentions: Vec<String>` (JIDs; left out when empty) and `link_preview: Option<LinkPreviewOut>`
  `{url, title?, description?, thumbnail_b64?}` (JPEG, at most 64 KiB decoded, the media thumbnail rule). An older
  gateway ignores both (serde) and sends the plain text: the `@` digits and the URL stay readable.
- `MessageMeta` gains `forwarding_score: Option<u32>`, `link_preview: Option<LinkPreview>` `{url, title?,
  description?}` (the thumbnail is served by `GET /v1/messages/{id}/thumbnail`, `meta.media` stays empty, a new
  `link_preview.thumbnail: bool` says it is there), `expires_s: Option<u32>` (the message's disappearing timer) and
  `system: Option<SystemNote>` (`{"type":"disappearing","seconds":N,"by":jid?}`: a line in the thread, the body is
  `unsupported` as for any message an older app cannot show).
- `ChatMeta` gains `ephemeral_s: Option<u32>` (the chat's timer, left out when off).
- `POST /v1/messages/forward` `{chat, message_id, to}` -> `Sent`. 404 for an unknown message, 400 for a message that
  cannot be forwarded (deleted, view once, placeholder, a media message without its stored protobuf).
- `POST /v1/chats/action` gains `{"type":"disappearing","seconds":N}` (0, 86400, 604800 or 7776000; anything else 400).
- `GET /v1/messages/{message_id}/receipts?chat=` -> `MessageReceipts {chat, id, members: [{jid, jid_alt?, name?,
  delivered_ms?, read_ms?, played_ms?, left}]}`: my message's receipts per member with times, members of the group
  today with none yet, and members who left marked `left`. A one-to-one message answers its one person. 404 for an
  unknown message or one that is not mine. An older gateway answers 404 for the route: the sheet then says it is not
  available.
- Gateway store **schema v11**: `receipts.delivered_ms`, `read_ms`, `played_ms`; one-to-one receipts recorded too;
  `chat_settings.ephemeral_s`, `ephemeral_ms`; `messages.expires_ms`; table `pending_changes` (an edit or delete for
  everyone that arrived before its message, applied when the message is stored). Only new columns and a new table.

## Steps (one story each)

| Step | What | Layers | Who |
|---|---|---|---|
| 1 | Domain rules in Rust: `rosty_core::wa_actions` with `edit` (my text or caption, not deleted, not a placeholder, within 15 min), `forward` (frequently forwarded from score 5; what can be forwarded), `mention` (find the `@` query at the caret, encode the composer's picks into wire text and JIDs, spans of a received text through the identity aliases as UTF-16 offsets, "mentions or replies to me"), `link` (first `http(s)` URL of a text, `og:`/`<title>` parse of a bounded HTML prefix, entity decoding, `og:image` resolved against the page), `receipts` (sections Read / Delivered / Played / Waiting, newest first, members who left last in their section), `disappearing` (the four choices, `expires_at`, labels). Unit tests. | `rosty_core` | me |
| 2 | Wire and client: the fields and routes above in `rosty_wa_proto`, `forward`, `message_receipts`, the new chat action and send options in `rosty_wa_client`, wire tests. | proto, client | me |
| 3 | Gateway (own commit, reported, not deployed): schema v11; receipt times; forward route; mentions and link preview on send; received link previews (thumbnail stored for the thumbnail route); chat timer from `EPHEMERAL_SETTING`, group updates and metadata, history and incoming `contextInfo.expiration`, applied to every send (text, media, forward); the disappearing chat action and its system line; `expires_s` on messages; early edits and deletes kept in `pending_changes`; receipts route. Tests. | `services/wa_gateway` | me |
| 4 | Device store `wa_sync` **v7**: `messages.expires_at`, `forwarding_score`, `link_json`, `system_json`; `chats.ephemeral_s`; `pending_meta` (a meta-only update of a message not stored yet is kept and applied when it arrives); outbox actions `Forward`, `DeleteForMe`, `SendText` with mentions and preview; `expire(now)` deletes expired rows and their media rows and answers the files and the next expiry; an expired row is never stored again; rows carry `editable_until_ms`, `forwarded_many`, `mention_spans`, `link_preview`, `system`, `expires_at`. Tests incl. the edge cases below. | `rosty_app_core` | me |
| 5 | FFI and bridge: ops `wa.forward`, `wa.message_receipts`, `wa.mention_query`, `wa.mention_encode`, `wa.link_find`, `wa.link_parse`, `wa.receipt_sections`, `wa_sync.expire`, `wa_sync.delete_for_me`; fixtures, `OPS.md`, Kotlin models, facades and fakes. | `rosty_ffi`, `:core-bridge` | me |
| 6 | Edit, forward, delete for me, played: "Edit" on my sent messages while `editable_until_ms` holds, an editing bar above the composer (Send saves, the bar's x cancels), "Forward" through the chooser (a WhatsApp target gets a real forward, an SMS target keeps the text), "Forwarded" and "Forwarded many times" marks, "Delete for me" next to "Delete for everyone", the played state on my voice notes. | Messages | me |
| 7 | Message info: "Info" on my messages opens a sheet: Read, Played (voice notes), Delivered, Waiting, each member with name and time, "left the group" for former members; one-to-one: the person's delivered, read and played times. | Messages | me |
| 8 | Mentions: in a group typing `@` opens a member list above the composer (filtered as you type, members without a number by name); a pick inserts `@Name` and the send carries `mentioned_jid`; received mentions show as highlighted names (contact, WhatsApp name, readable number, never LID digits); a mention of me or a reply to my message notifies in a chat muted in WhatsApp (WhatsApp's behaviour as remembered, not verified). | Messages, `:core-bridge` | me |
| 9 | Link previews: received card (thumbnail, title, description, host) under the text; sending per the owner decision: `LinkPreviewFetcher` in `:platform` (HttpURLConnection, no cookies, http(s) only, manual redirects, private and loopback addresses refused, 512 KiB cap, 5 s timeouts, image cap and JPEG downscale), the composer chip with x, the "Link previews" switch. | `:platform`, Messages | me |
| 10 | Disappearing messages: "Disappearing messages" in the thread menu (Off, 24 hours, 7 days, 90 days) through the gateway; the system line in the thread; a timer mark in the header; expiry at store open and on a timer to the next expiry while the app runs. | Messages | me |

Order: 1, 2, 3 (gateway commit), 4, 5, then 6 to 10. Goldens for every new sheet, bar and chip in light and dark.

Edge cases and the tests that cover them:
- An edit or delete for everyone that arrives before its message: gateway `pending_changes` (store test), device
  `pending_meta` (wa_sync test).
- An edit of a LID-keyed message after an identity merge: the event names the LID chat, the row lives under the number
  (wa_sync test).
- Expiry while the app was closed: `expire` runs when the store opens and on a timer (wa_sync and view model tests); a
  page from the gateway that still has an expired message does not bring it back (wa_sync test).
- A forwarded media message: forwarded from its stored protobuf, the same CDN blob (gateway test); a media message
  without one is refused with 400.
- Mentions of a LID-only member: the JID stays `<lid>@lid`, the token is the LID user, the name never shows the digits
  (rosty_core and Kotlin tests).
- Group message info with members who left: marked `left`, kept in their section (gateway and rosty_core tests).

## Out of this round

- Admin "delete for everyone" of other people's messages (gateway supports it; UI later with WA-R6 group admin).
- A high-quality uploaded link thumbnail (`thumbnailDirectPath`, `MediaType::LinkThumbnail`): the inline JPEG only.
- The account default timer for new chats (`set_default_disappearing_mode`) and a contact's default
  (`DisappearingModeChanged`): WA-R7 privacy.
- Keeping a disappearing message ("keep in chat"): not in whatsapp-rust 0.7.0 as a helper; WA-R7 if wanted.
- Message info for messages received (WhatsApp shows only my messages' info).

## Open owner questions

1. Disappearing messages on the gateway: the device deletes expired rows; the gateway still keeps them (owner answer 6,
   "the gateway keeps messages as today") and serves them in pages until it is told otherwise. Should the gateway
   purge expired messages too (its `messages.expires_ms` column is there for it)? Recommended: yes.
2. Mentions in a chat muted in Rosty only (not in WhatsApp): this round lets a mention through WhatsApp's mute only;
   Rosty's own per-conversation mute stays absolute. Keep it so?
3. Starred messages in a disappearing chat expire like the others (WhatsApp's "keep" is not built). Fine?

## Evidence

Branch `worktree-agent-a6f974094db30ae02` on `messages-kotlin` (`866062a1`).

| Step | Commits | Checks |
|---|---|---|
| 1, 2 | `64e97708` | `rosty_core::wa_actions` unit tests; proto and client wire tests |
| 3 | `696e0460`, `8f43ae7a` (Cargo.lock) | gateway store, convert and `tests/api.rs` tests; schema **v11**; **not deployed** |
| 4 | `e57e35eb` | `wa_sync` v7, `actions_tests.rs` (11 tests: early edit and revoke, LID-keyed edit after a merge, expiry at open and a page that would bring a row back, forward snapshot, delete for me and its undo on refusal, mentions of a LID-only member) |
| 5 | `82e88cb7`, `601b2335`, `7959619d`, `7b8c8f5b` | FFI fixtures and `OPS.md`; core-bridge contracts, `testRustBridge` 118 passed (schema 7) |
| 9 (fetcher) | `aaaf81c5` | `:platform` `PagePreviewTest` |
| 6 to 10 | `d3384888`, `86a33f54`, `dbec004e` | Messages `WaMessageActionsTest` (15), `WaExpiryTest`, `MessageAnnouncerTest` muted mention; goldens `wa-r4-*` light and dark; `e6wa-r3-media-menu` re-recorded ("Delete for Me") |

Gates: `cargo fmt`, clippy `-D warnings` and 3396 tests on the package set (`--profile checks`); `cargo check --release
-p rosty_android --target aarch64-linux-android`; Messages `testPlainReleaseUnitTest` and `testFirebaseReleaseUnitTest`
668 each, `verifyRoborazziPlainRelease`, `lintPlainRelease`; core-bridge and platform `testReleaseUnitTest` and
`lintRelease`; `spotlessCheck`; no orphan Messages goldens.

Deviations from the steps above:
- `wa_sync.delete_for_me` is not an op of its own: delete for me is the outbox action `DeleteForMe` of `wa_sync.enqueue`
  (hidden at once, shown again when the gateway refuses it).
- The timer shows in the thread menu ("Disappearing messages: 7 Days") and as the system line, not as a mark in the
  header.
- "Mentions or replies to me" for the muted-chat notification compares ids in Kotlin (`WaIds.same` against the linked
  account's number and LID from the link status), mirroring `rosty_core::wa_actions::addresses_me`; there is no
  `wa.addresses_me` op yet. The announcer holds a muted chat's message until its `message_meta` arrives.
- Expiry while the app runs: `WaOffline` runs `expire` when the store opens, then waits for the next expiry the store
  names (cut short when rows change) and deletes the media files of what expired.

Needs a device: edit within and after 15 minutes against a real phone; forward of a media message (same CDN blob);
played receipts on my voice notes; message info in a group with a member who left; a mention from another phone in a
WhatsApp-muted group (notification); a link card made on the phone and seen on WhatsApp (thumbnail size); the timer set
from Rosty and from the phone in a group and in a one-to-one chat (system line both ways); expiry after the app was
closed.
