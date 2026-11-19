# E6-WA R5: rich content and presence

Branch `messages-kotlin`. Epic plan: `e6wa-plan.md`. Research: `../research/e6wa-inventory.md` (rows Polls, Location
static and live, Contact card, Own presence, Subscribe to presence, Keep in chat; ranked items 4, 6 and 8). Base:
`messages-kotlin` at `b70db89e` (R4 closed and merged with `ui-overhaul`).

Goal of the round: a poll, a place and a contact card are real bubbles both ways. A received poll shows its question,
its options with counts and who voted, single or multiple answers, and my vote; I vote and change my vote, and I make a
poll from the attachment menu. A received location is a small map drawn with Stops' tile code and the tile server the
user chose, opens full screen with its pin and hands a `geo:` URI to another app; I send my current place after a
one-shot fix and a preview. A received vCard shows its name and numbers with "Message" and "Add to contacts"; I send
one or several contacts from the device picker. The thread header says online, last seen, typing or recording, only
for the chat on screen; Phone reads the same state through `:wa`. Three R4 follow-ups the owner decided are in too.

Library facts (whatsapp-rust 0.7.0 from crates.io, the version `Cargo.lock` pins; `LIB/` =
`~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/whatsapp-rust-0.7.0/src/`, `WACORE/` = `.../wacore-0.7.0/src/`,
`PROTO/` = `.../waproto-0.7.0/src/whatsapp.proto`):

- Poll creation: `polls().create(to, name, options, selectable_count)` `LIB/features/polls.rs:50` returns the send
  result and the 32-byte `message_secret` it generated (`:102-111`); single choice goes out as
  `pollCreationMessageV3`, multiple as `pollCreationMessage` (`:88-99`). Validation (`build_poll_creation_message`,
  private, `:374-405`): 2 to 12 options, `selectable_count` 1..=options, no duplicate names. `create` takes no
  `SendOptions`, so it cannot carry a chat's disappearing timer; the gateway builds the same protobuf itself and sends
  it with `send_message_with_options` (`set_ephemeral_expiration` covers `poll_creation_message*`,
  `contacts_array_message`, `location_message`: `WACORE/proto_helpers.rs:15-40, 468-480`). Quiz polls exist
  (`create_quiz` `:67`): not offered.
- Voting: `polls().vote(chat, poll_id, creator, secret, option_names)` `LIB/features/polls.rs:120` hashes the names
  (`WACORE/poll.rs:30` SHA-256 of the name), encrypts them under a key derived from the secret, the poll id, the
  creator and the voter (`WACORE/poll.rs:41-88`) and sends a `pollUpdateMessage` (`PROTO:4114-4119`, field 50 of
  `Message`, `PROTO:2789`). An empty list is a vote taken back. The voter id follows the creator's namespace (own LID
  for a LID-addressed poll, `:185-209`). The library does not record my own vote anywhere: the gateway must.
- Receiving votes: a vote is a `pollUpdateMessage` with `pollCreationMessageKey` (the poll) and `vote {encPayload,
  encIv}`. `decrypt_vote` `LIB/features/polls.rs:211` and the pure `wacore::poll::decrypt_poll_vote_with_fallback`
  `WACORE/poll.rs:141` (retries once with both ids swapped to the other namespace, never mixed) answer the selected
  option hashes. `aggregate_votes` `:271` tallies last vote wins per voter, deduplicated by the LID-preferred identity,
  an empty selection removing the voter (`:300-345`). **What the gateway must keep**: the poll's `messageSecret`
  (already stored per message: `messages.secret`, `GW/wa/convert.rs:476-481`), its option names, the creator id as it
  arrived (wire ids, schema v8), and each voter's latest vote: the ciphertext until the poll is there (a vote can come
  first), then the selected names. History sync carries votes already opened: `WebMessageInfo.pollUpdates`
  (`PROTO:6468`, `PollUpdate` `:5052-5059`, `PollVoteMessage.selectedOptions` `:1571-1574`).
- Later poll forms: `pollCreationMessageV2` (60), `V3` (64), `V5` (111), `V6` (119) share the `PollCreationMessage`
  shape (`PROTO:2797, 2801, 2840, 2846`); `V4` (93) is a `FutureProofMessage` wrapper; `pollAddOptionMessage` (121)
  and result snapshots (88, 115) are not handled (shown as the poll as it is).
- Locations: `LocationMessage` `PROTO:3663-3676` (lat, lon, name, address, url, comment, thumbnail),
  `LiveLocationMessage` `:3650-3661` (lat, lon, accuracy, caption, sequence). No sender helper: the caller builds the
  message. Live updates: the library has no live-location update path (no match outside the proto helpers,
  `WACORE/messages.rs:1029` only classifies the first message), so a live location shows its first point only,
  marked live. Sending a live location is out (owner).
- Contact cards: `ContactMessage {displayName, vcard}` `PROTO:3105-3110`, `ContactsArrayMessage {displayName,
  contacts}` `:3112-3116`. No helper; the caller builds them. Several contacts go as one contacts array.
- Presence: `presence().set_available/set_unavailable` `LIB/features/presence.rs:118, 123` (refuses with an empty push
  name, `:81-84`), `subscribe` `:138` (with the contact's tctoken, tracked and re-sent after a reconnect, `:144, 199-230`),
  `unsubscribe` `:173`. `Event::Presence(PresenceUpdate {from, unavailable, last_seen})` `WACORE/types/events.rs:1687-1692`;
  `last_seen` comes from the `last` attribute and is `None` when it is not a number (`LIB/handlers/presence.rs:46-50`),
  which is what a hidden last seen sends. WhatsApp sends presence only to a client that is itself available
  (WhatsApp's behaviour as remembered, the library has no note on it): the app marks itself available while a thread is
  on screen. Typing: `Event::ChatPresence` (R3, `GatewayEvent::Typing` with `state` typing, recording or paused).
- Keep in chat: received as `Message.keepInChatMessage {key, keepType}` (`PROTO:2790, 3563-3567`, `KeepType`
  `:2588-2592`) and in history as `WebMessageInfo.keepInChat` (`PROTO:2579-2586, 6473`); the sender's undo is
  classified as a sender revoke at the wire level (`WACORE/types/message.rs:233-239`). Sending one is
  `keep_message` `LIB/send/actions.rs:87` (not used this round, see Out of scope).

## Owner decisions

- **Gateway purge of expired messages** (2026-11-19, through the main thread; answers R4 question 1): the gateway
  deletes disappearing messages whose `messages.expires_ms` passed, with their reactions, receipts, edits and stored
  media, at start and every few minutes (index `messages_by_expiry`). Its own commit, flagged for deploy.
- **Mentions and replies through Rosty's own mute** (2026-11-19; answers R4 question 2): a WhatsApp message that
  mentions me or replies to one of mine also gets through Rosty's per-conversation mute, not only WhatsApp's.
- **Keep in chat** (2026-11-19; answers R4 question 3): starring does not keep a message; a disappearing message
  starred keeps expiring. When WhatsApp's "Keep in chat" reaches the gateway (live or in history), the kept message
  does not expire, on the gateway and on the phone; "undo keep" makes it expire again from its original time.

## Decisions made for this round (open to the owner, see questions)

- **Map tiles in Messages stay in memory.** The vault rule (`wa-vault.md` 13.1: nothing readable about messages on
  disk) would be broken by a plaintext tile cache that shows which areas were shared. `tiles.open` gains `memory: true`
  (an in-memory LRU with the same index, no files); Messages opens its source that way. Stops is unchanged.
- **The tile server is the one chosen in Stops.** Stops answers it through a signature-protected provider
  (`content://dev.rosty.stops.tileserver/server`, the `WA_LINK`-style permission `dev.rosty.permission.MAP_TILES`).
  With Stops not installed, the public VersaTiles server (the default of `rosty_geo::tiles`) is used. No other map
  service is called; the system map app is only reached through "Open in…" (a `geo:` intent the user picks).
- **The map code is shared.** A new module `:map` (`dev.rosty.map`) takes Stops' tile painting
  (`PreparedTile`/`TileRasterizer`, `MapMath`/`TileKey`, `TileSource`) and adds a static map (a few tiles around a
  point, painted once) and the tile-server share. Stops imports them from there.

## Wire additions (agreed up front; all additive)

- `MessageMeta.poll: Option<Poll>` `{question, options: [{name, voters: [jid]}], selectable, my_votes: [name]}`
  (`selectable` 1 is single choice, more is "multiple answers"; voters by the id the gateway shows them under, number
  first, `my_votes` the names of my current choice). The body stays `unsupported`, so an older app shows an
  unsupported message.
- `MessageMeta.contacts: Vec<SharedContact>` (every card of a contacts array; `contact` keeps the first, for older apps).
- `Location` gains `url`, `comment` (optional).
- `MessageMeta.kept: bool`: kept in a disappearing chat; `expires_s` still says the timer, but it does not expire.
- `POST /v1/messages/rich` `{chat, quoted_id?, content}` -> `Sent`, `content` one of `{"type":"poll","question",
  "options":[..],"multiple":bool}`, `{"type":"location","latitude","longitude","name"?,"address"?}`,
  `{"type":"contacts","contacts":[{display_name, vcard}]}` (1 to 20 cards, each vCard at most 64 KiB). 400 for an
  invalid poll (the library's rules), a coordinate out of range, no cards. The chat's timer applies. An older gateway
  answers 404 (the router's): the app says the gateway must be updated.
- `POST /v1/polls/vote` `{chat, message_id, options: [name]}` -> 204; an empty list takes the vote back. 404 unknown
  poll, 400 a name that is not an option or more names than the poll allows.
- `POST /v1/presence/unsubscribe` `{jid}` -> 204 (the library's `unsubscribe`).
- Gateway store **schema v12**: table `poll_votes (instance_id, chat, poll_id, voter, voter_alt, ts_ms, enc_payload,
  enc_iv, options_json)` and `messages.kept`. Only a new table and a new nullable column.

## Steps (one story each)

| Step | What | Layers |
|---|---|---|
| 1 | Owner decision: gateway purge of expired disappearing messages and their media: `Store::purge_expired_messages(now)` (rows, reactions, receipts, edits, votes, cached media files), at start and every 5 minutes. Tests. Own commit, deploy flag. | `services/wa_gateway` |
| 2 | Owner decision: a mention of me or a reply to me gets through Rosty's own mute too: the announcer holds a Rosty-muted message like a WhatsApp-muted one until its meta says; a held message that addresses me is posted past the mute. Tests. | Messages `notify` |
| 3 | Domain rules in Rust, `rosty_core::wa_rich`: `poll` (draft rules: trimmed question, 2..=12 distinct options, multiple; tally of votes last vote wins by time per voter alias set; a vote change; the view: count, share, leader, mine), `vcard` (parse 3.0/4.0 with folding, escapes, several or no numbers; build one card with name and numbers), `location` (coordinate check and text, the label of a place with no name, `geo:` URI, the static map zoom and tiles around a point), `presence` (the header state: typing and recording expire after 25 s, online, last seen when known, nothing when hidden, a group shows typing only). Unit tests. | `rosty_core` |
| 4 | Wire and client: the fields and routes above in `rosty_wa_proto`, `send_rich`, `poll_vote`, `unsubscribe_presence` in `rosty_wa_client`; wire tests. | proto, client |
| 5 | Gateway rich content (own commit, not deployed): schema v12; polls in convert (every creation form) and votes (`Converted::PollVote`), decrypted with the stored secret through the pure library call and the gateway's pairs, early votes kept encrypted until their poll arrives, history votes taken as they come, my vote recorded on send; the rich send route (poll built as the library builds it, with the chat's timer; location; contacts array); the vote route; presence unsubscribe; every card of a contacts array; keep in chat (live and history) sets `kept` and clears `expires_ms`, undo restores it. Tests. | `services/wa_gateway` |
| 6 | Device store `wa_sync`: `Kind::Poll`; `extra_json` takes `poll`, `contacts`, `kept`; a kept row has no `expires_at`; outbox actions `SendPoll`, `SendLocation`, `SendContacts` (shown at once) and `PollVote` (my choice shows at once, undone on refusal); rows carry `poll`, `contacts`, `kept`. No schema change (v7 stays). Tests incl. the edge cases below. | `rosty_app_core` |
| 7 | FFI and bridge: ops `wa.poll_check`, `wa.poll_view`, `wa.vcard_parse`, `wa.vcard_build`, `wa.place_view`, `wa.static_map`, `wa.presence_view`; `tiles.open {memory}`; fixtures, `OPS.md`, Kotlin models, facades and fakes. | `rosty_ffi`, `rosty_tiles`, `:core-bridge` |
| 8 | Shared map: module `:map` (moved tile painting, static map, tile server share); Stops on it, its provider. | `:map`, Stops |
| 9 | Messages UI: poll bubble (bars, counts, voters sheet, vote and change), poll create sheet (question, 2 to 12 options, "Allow multiple answers"); location card (static map, name, address, live mark), full-screen map with pin and "Open in…", send current place (permission at first use, one-shot fix, preview); contact card (name, numbers, Message, Add to contacts) and sending contacts from the picker; header presence with the subscription rules and my availability while a thread is on screen. Goldens light and dark, strings en and tr. | Messages, `:platform` |
| 10 | Presence through `:wa`: `WaPresenceWatch` (subscribe on watch, unsubscribe on leave, events after leave ignored); Phone shows it under the name while a WhatsApp call rings out. | `:wa`, Phone |

Order: 1 (gateway commit), 2, 3, 4, 5 (gateway commit), 6, 7, then 8 to 10.

Edge cases and the tests that cover them:
- A vote that arrives before its poll: kept encrypted in `poll_votes`, opened and counted when the poll is stored
  (gateway store and convert tests).
- A vote from a LID-only member: decrypted with the LID (and the swapped pair when known); counted under the id it
  came with, never shown as LID digits on the phone (gateway test, Kotlin names through the identity layer).
- A poll in a disappearing chat: the poll carries the timer (gateway send test), expires on both sides; votes go with
  it (purge test).
- A vote changed, a vote taken back (empty), an older vote after a newer one (rosty_core tally tests).
- A location with no name: "Shared location" / "Paylaşılan konum" with the coordinates (rosty_core and Kotlin).
- A vCard with several numbers, with none (only a name: no "Message", "Add to contacts" stays), folded lines and
  escapes (rosty_core).
- Presence in a group: the header shows typing and recording names only; no per-member online (rosty_core presence
  test). One-to-one only for online and last seen.
- Presence events after leaving the thread are ignored (Kotlin `WaPresenceWatch` test) and the chat is unsubscribed.
- Last seen hidden: nothing is shown (rosty_core presence test).
- A kept message in a disappearing chat does not expire, an undo makes it expire again (gateway and wa_sync tests).

## Out of this round

- Quiz polls, poll end times, adding options to a poll, poll result snapshots (shown as the poll as stored).
- Sending a live location (owner); following a live location's later points (no library path).
- "Keep in chat" from Rosty (`keep_message` exists): only what arrives is honoured this round.
- Per-member online in groups.
- Events (`events().create`), business messages.

## Open owner questions

1. Map tiles in Messages are kept in memory only (vault rule); every cold start fetches the tiles of a card again from
   the tile server. Fine, or allow a small disk cache (it would show the areas shared in chats)?
2. Messages reads the tile server from Stops; without Stops it uses the public VersaTiles server. Should Messages have
   its own tile-server setting instead?
3. Sending my current place: Messages now asks for location permission (it had none). Precise or approximate only?
   This round asks for precise and accepts approximate.

## Evidence

(filled at the end of the round)
