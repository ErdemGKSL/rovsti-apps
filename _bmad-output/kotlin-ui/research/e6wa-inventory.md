# E6 WhatsApp: feature inventory, whatsapp-rust 0.7.0 vs what Rosty exposes

Read-only research, 2026-11-15. Nothing in the repo was changed, nothing was built or run.

## Sources and how to read the paths

- **Pinned library.** `services/wa_gateway/Cargo.toml` takes `whatsapp-rust` from the workspace with features
  `voip-mlow`, `voip-libopus`. `Cargo.lock` pins `whatsapp-rust 0.7.0` from **crates.io** (not git), with
  `wacore`, `wacore-appstate`, `wacore-binary`, `wacore-libsignal`, `wacore-noise`, `waproto`,
  `whatsapp-rust-sqlite-storage`, `whatsapp-rust-tokio-transport`, all `0.7.0`.
- **The source of that exact version is on disk** under `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/`.
  Every library claim below cites a file there. Short forms used in the table:
  - `LIB/` = `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/whatsapp-rust-0.7.0/src/`
  - `WACORE/` = `.../wacore-0.7.0/src/`
- **Rosty layers.** `GW/` = `services/wa_gateway/src/`, `PROTO/` = `crates/rosty_wa_proto/src/`,
  `CLIENT/` = `crates/rosty_wa_client/src/`, `SYNC/` = `crates/rosty_app_core/src/wa_sync/`,
  `BRIDGE/` = `android/core-bridge/src/main/kotlin/dev/rosty/core/ops/`,
  `MSG/` = `android/messages/src/main/kotlin/dev/rosty/messages/`.
- **Status words.** DONE = the user can do it. PARTIAL = works with a stated hole. GATEWAY-ONLY = reaches the gateway
  (and usually client and bridge) but no app uses it. LIB-ONLY = the library has it, the gateway does not expose it.
  NONE = the library has no support.
- **Not checked.** I read code only. Whether a library call works against WhatsApp's servers today was not tested.
  Line numbers are from the files as they stand today.

## How the layers are wired (short)

- Gateway routes: `GW/routes.rs:53-78`, `GW/rich_routes.rs:39-47`, `GW/status_routes.rs:33-36`,
  `GW/wa/calls.rs:1097-1102`. Route templates: `PROTO/api.rs:7-49`.
- Driver trait (what the gateway can ask of WhatsApp): `GW/driver.rs:649-830`. Real driver: `GW/wa/session.rs`.
- Library events the gateway reacts to: `GW/wa/session.rs:832-940` and `GW/wa/app_state.rs` (`handle`). All other
  library events fall into `_ => {}`.
- Gateway events to apps: `PROTO/event.rs:46-147` (`GatewayEvent`).
- Client: one method per route, `CLIENT/http.rs:269-588`; event stream `CLIENT/events.rs:116`; call audio
  `CLIENT/audio.rs:108`.
- Offline store: events applied in `SYNC/apply.rs:520-668`; outbox actions are exactly `SendText`, `SendMedia`,
  `Edit`, `Revoke`, `React`, `MarkRead`, `Star` (`SYNC/outbox.rs:41-94`).
- Bridge ops: `BRIDGE/WaOps.kt:83-360` (`wa.*`), `BRIDGE/WaSyncOps.kt` (`wa_sync.*`).
- Messages UI: thread `MSG/wa/WaThreadViewModel.kt`, `MSG/wa/WaThreadPage.kt`; inbox `MSG/inbox/InboxViewModel.kt`;
  status `MSG/wa/status/`.

## The table

### Media

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Receive image | `wa::Message.image_message`; download `Client::download` `LIB/download.rs:531` | Converted to `MessageBody::Media{Image}` with caption, size, width/height, thumbnail: `GW/wa/convert.rs:131-138, 262-274`. Bytes: `GET /v1/messages/{id}/media` (`GW/routes.rs:698`), thumbnail `GW/rich_routes.rs:103` | `media()` `CLIENT/http.rs:361`, `thumbnail()` `:481`; `Kind::Image` `SYNC/model.rs:21`; file cache `SYNC/media.rs:48-141` | Picture bubble with thumbnail, tap opens viewer, save, share (`MSG/wa/WaThreadViewModel.kt:512-522, 826-866`) | DONE |
| Receive video | same, `video_message` | `GW/wa/convert.rs:139-146, 275-289` (seconds, size, `gif_playback` as `animated`) | same as image | Video bubble with thumbnail, length and size; a tap downloads and hands the file to another app (`WaMediaEffect.Open`, `WaThreadViewModel.kt:100-105, 524-535`). No in-app player | PARTIAL: no in-app playback |
| Receive voice note (PTT) and audio | `audio_message`, `ptt` flag | `GW/wa/convert.rs:147-149, 290-300` (`voice_note`, `seconds`) | `MediaMeta.voice_note` `PROTO/meta.rs:83` | In-app player with seek and speed (`MSG/wa/WaVoice.kt`, `WaThreadViewModel.kt:868-897`) | DONE (no waveform drawn; none is passed down) |
| Receive document | `document_message` | `GW/wa/convert.rs:150-157, 301-319` (file name, pages, thumbnail) | `MediaMeta.file_name`, `pages` | File row, tap downloads and opens in another app, save as, share | DONE |
| Receive sticker | `sticker_message`; sticker packs `LIB/download.rs:565` (`fetch_sticker_pack`) | `GW/wa/convert.rs:158-160, 320-333` | `MediaKind::Sticker` `PROTO/api.rs:256` | Drawn as a picture (`WaSyncKind.Sticker` shares the Image branch, `WaThreadViewModel.kt:512`). Animated stickers are not animated by anything I found | PARTIAL: static only, no pack browsing |
| Receive GIF | `video_message.gif_playback` | Flag kept as `MediaMeta.animated` (`GW/wa/convert.rs:281`) | `PROTO/meta.rs:86` | Shown as an ordinary video bubble; nothing in `MSG/` reads `animated` | PARTIAL: no inline loop |
| View-once | Library marks the stanza on send (`LIB/send/mod.rs:533-535`) and reports an inbound view-once placeholder (`LIB/message/receive.rs:189-194`) | Flag kept as `MediaMeta.view_once` (`GW/wa/convert.rs:268, 283, 295`); media lookup looks through view-once wrappers (`GW/wa/media.rs:229`) | `PROTO/meta.rs:89` | No hit for `viewOnce` in `android/messages/src/main`: shown like normal media, not hidden after viewing. Cannot send view-once | PARTIAL: receive flag unused, no send |
| Captions | fields on image, video, document; `ImageOptions.caption` etc. `LIB/media.rs:20-52` | In and out: `GW/wa/convert.rs:133-153`, `GW/wa/session.rs:538-545` | `MessageBody::Media.caption` | Caption shown under media; composer text becomes the caption (`WaThreadViewModel.kt:719-727`). Caption edit works (`GW/wa/session.rs:1183-1187`) | DONE |
| Thumbnails | `jpeg_thumbnail` in options `LIB/media.rs:24, 33, 47` | Inbound: stored and served. **Outbound: none.** `GW/wa/media.rs` never sets `jpeg_thumbnail`, width, height or waveform (grep of the file shows only `ptt`) | `thumbnail()` `CLIENT/http.rs:481` | Inbound thumbs cached (`MSG/wa/WaMedia.kt:90`) | PARTIAL: media Rosty sends shows no preview to the recipient until downloaded, and no dimensions |
| Send image, video, document, audio | `Client::upload` `LIB/upload.rs:409`, `upload_stream` `:463`; builders `image_message` `LIB/media.rs:67`, `video_message` `:92`, `document_message` `:119`, `audio_message` `:150`; `send_message` `LIB/send/mod.rs:846` | `POST /v1/messages/media` (`GW/routes.rs:645`), `send_media_inner` `GW/wa/session.rs:514-598`; own builder `GW/wa/media.rs:70-200`. Cap 16 MiB (`PROTO/media.rs:6`) | `send_media` `CLIENT/http.rs:348`, `send_media_with` `:465`; `Action::SendMedia` `SYNC/outbox.rs:47` (queued offline) | Attach menu: Photo, Video, Document, Paste picture; keyboard GIF/sticker as picture (`MSG/wa/WaThreadMedia.kt:64-131`). One file per message | DONE (single attachment, 16 MiB) |
| Send voice note | `AudioOptions.ptt`, `waveform` `LIB/media.rs:54-63` | `voice_note` option sets `ptt` (`GW/wa/media.rs:91, 162`); no waveform | `MediaOptions.voice_note` `PROTO/meta.rs:395`; outbox field `SYNC/outbox.rs:62`; bridge passes it (`MSG/wa/WaRemote.kt:188`) | **No recorder.** No `MediaRecorder`/`AudioRecord` in `android/messages/src/main`; nothing sets `voiceNote = true` | GATEWAY-ONLY: blocked by UI |
| Send sticker, GIF | `MediaType::Sticker` `WACORE/download.rs:44`; `VideoOptions.gif_playback` `LIB/media.rs:36` | Sticker kind accepted (`GW/wa/media.rs:19`); `gif_playback` never set | `MediaKind::Sticker` | No sticker picker; a keyboard sticker goes out as an image | PARTIAL / LIB-ONLY for GIF flag |
| Media re-upload for expired media | `Client::media_reupload().request` `LIB/features/media_reupload.rs:84`, `request_many` `:176` | Not used (no `reupload` anywhere in `GW/`). A failed download just returns the error (`GW/wa/session.rs:1127`) | none | Old media whose URL expired cannot be fetched | LIB-ONLY |
| Streaming download | `download_to_writer` `LIB/download.rs:500, 632` | Gateway buffers whole files in memory and a disk cache (`GW/media_cache.rs`) | whole-body `Vec<u8>` | whole file | DONE functionally |

### Messages

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Text | `send_text` `LIB/send/mod.rs:863` | `POST /v1/messages/text`, `GW/wa/session.rs:692-760` | `send_text` `CLIENT/http.rs:327`; `Action::SendText` | Composer, offline outbox, retry, cancel | DONE |
| Reply / quote | `ContextInfo` on any message | Out: `quote_context` `GW/wa/session.rs:212`; in: `GW/wa/convert.rs:349-368` | `SendText.quoted_id` `PROTO/api.rs:172`; `Quoted` `PROTO/meta.rs:52` | Swipe or menu Reply, quote bar, tap to jump (`WaThreadViewModel.kt:745-757`) | DONE |
| Reactions | `send_reaction` `LIB/features/reaction.rs:35` | `POST /v1/messages/reaction`, `GW/wa/session.rs:600`; inbound `GW/wa/convert.rs:161-175` | `react` `CLIENT/http.rs:371`; `Action::React` | Emoji row in the menu, double tap, chips with counts (`WaThreadPage.kt:293-303`). No "who reacted" list | DONE |
| Edit | `edit_message` `LIB/client/messaging.rs:152`; decrypting inbound edits `LIB/features/message_edit.rs:73-149` | `POST /v1/messages/edit` `GW/wa/session.rs:1167-1199`; inbound incl. encrypted edits `GW/wa/app_state.rs` (`encrypted_edit`, `PendingEdit::resolve`) | `edit_message` `CLIENT/http.rs:530`; `Action::Edit`; edit history kept (`PROTO/meta.rs:172`) | Inbound "Edited" label works. **Outbound: `model.edit()` exists (`WaThreadViewModel.kt:963`) but the bubble menu only offers Edit for messages still in the outbox** (`WaThreadPage.kt:152-156`); a sent message has Reply, Copy, Star, Delete for everyone | PARTIAL: no menu entry to edit a sent message |
| Delete for everyone | `revoke_message` `LIB/send/actions.rs:14`, `RevokeType::{Sender, Admin}` `LIB/send/mod.rs:465` | `POST /v1/messages/revoke` `GW/wa/session.rs:1201-1221` (admin revoke supported) | `revoke_message` `CLIENT/http.rs:536`; `Action::Revoke`; content kept, marked deleted | Own messages only (`WaThreadViewModel.kt:957`); admin delete of others' messages not offered | DONE (own), admin revoke GATEWAY-ONLY |
| Delete for me | `chat_actions().delete_message_for_me` `LIB/features/chat_actions.rs:600` | `POST /v1/messages/delete` `GW/wa/session.rs:1223-1246`; inbound `DeleteMessageForMeUpdate` handled | `delete_for_me` `CLIENT/http.rs:542`; **not an outbox action** | No caller in any app (`deleteForMe` only in the bridge) | GATEWAY-ONLY |
| Forward | `forward_message` `LIB/send/mod.rs:887` | Inbound `is_forwarded` kept (`GW/wa/convert.rs:343`). No forward route; `forwarding_score` not read | `MessageMeta.forwarded` `PROTO/meta.rs:124` | "Forwarded" label shown. WhatsApp bubble menu has no Forward (`waBubbleMenu`, `WaThreadPage.kt:132-170`); the SMS thread does (`MSG/conversation/SmsThreadPage.kt:154`) and can target a WhatsApp chat as plain text | PARTIAL: label only; true forward LIB-ONLY |
| Starred | `star_message`/`unstar_message` `LIB/features/chat_actions.rs:471, 483` | `POST /v1/messages/star`; inbound `StarUpdate` | `star_message` `CLIENT/http.rs:546`; `Action::Star`; `Store::starred` `SYNC/query.rs:191` | Star/Unstar in the menu, Starred page (`MSG/starred/StarWiring.kt:56`, `StarredPage.kt`) | DONE |
| Mentions | `ContextInfo.mentioned_jid` (proto) | Inbound list kept (`GW/wa/convert.rs:344-348`); cannot send | `MessageMeta.mentions` `PROTO/meta.rs:149` | Carried into the model (`MSG/wa/StoreWaChats.kt:175`) but not rendered as names, no `@` picker | PARTIAL: receive data only |
| Link previews | No helper; `MediaType::LinkThumbnail` exists (`WACORE/download.rs:47`); a preview is an `extended_text_message` the caller fills | Not read, not sent (no `matched_text`/preview code in `GW/`) | none | Links are detected locally and tappable (`MSG/conversation/MessageDetector.kt:100`); no preview card | LIB-ONLY (raw proto) |
| Disappearing messages | `set_chat_disappearing_timer` `LIB/client/iq_ops.rs:129`, `set_default_disappearing_mode` `:113`, `groups().set_ephemeral` `LIB/features/groups.rs:1015`, `SendOptions::with_ephemeral_expiration` `LIB/send/mod.rs:318`, event `DisappearingModeChanged` `WACORE/types/events.rs:943` | Nothing. Sends never set an expiration, the event is ignored, stored messages never expire | none | none | LIB-ONLY. Side effect: in a chat with a timer, what Rosty sends does not carry the timer |
| Pin message | `pin_message`/`unpin_message` `LIB/send/actions.rs:103, 119`, `PinDuration` `LIB/send/mod.rs:445` | none | none | none | LIB-ONLY |
| Keep in chat | `keep_message` `LIB/send/actions.rs:87` | none | none | none | LIB-ONLY |
| Scheduled text (Rosty's own) | not a WhatsApp feature | `/v1/scheduled` `GW/routes.rs:64-65`, `GW/schedule.rs` | `schedule_text` `CLIENT/http.rs:332` | Schedule sheet and list (`MSG/schedule/`) | DONE |

### Receipts and privacy

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Delivered / read ticks on my messages | `Event::Receipt` `WACORE/types/events.rs:869` | `on_receipt` `GW/wa/session.rs:764`, `receipt_status` `:121` | `GatewayEvent::Receipt` `PROTO/event.rs:63`; `RowState` `SYNC/model.rs:127` | Tick states on bubbles (`WaThreadViewModel.kt:420-429`) | DONE |
| Played tick | `ReceiptType::Played` | mapped (`GW/wa/session.rs:126`), sent as `MessageMeta` | `DeliveryStatus::Played` `PROTO/meta.rs:40` | `BubbleState.Played` | DONE (inbound) |
| Per-recipient receipts in groups | receipt carries the participant | Stored per member and folded into one group-wide tick (`GW/driver.rs:482-505`, `store::group_status`) | only the folded tick | No "message info" (who read, when) | PARTIAL: aggregate only |
| Send read receipts | `mark_as_read` `LIB/receipt.rs:854` | `POST /v1/read` `GW/wa/session.rs:981-1046` (per author in groups) | `mark_read` `CLIENT/http.rs:375`; `Action::MarkRead` | Sent when the thread is visible (`WaThreadViewModel.kt:603-620`) | DONE |
| Send played receipt | `mark_as_played` `LIB/receipt.rs:915` | `POST /v1/played` `GW/wa/session.rs:1305-1334` | `mark_played` `CLIENT/http.rs:551`; **not an outbox action** | No caller: playing a voice note does not tell the sender | GATEWAY-ONLY |
| Privacy settings (last seen, photo, about, read receipts, groups, online) | `fetch_privacy_settings` `LIB/client/iq_ops.rs:66`, `set_privacy_setting` `:86`, `set_privacy_disallowed_list` `:100` | none | none | none | LIB-ONLY |

### Typing and presence

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Send typing / recording / paused | `chatstate().send_composing/recording/paused` `LIB/features/chatstate.rs:50-58` | `POST /v1/chatstate` `GW/wa/session.rs:1066` | `chat_state` `CLIENT/http.rs:394`; `TypingThrottle` `PROTO/timeline.rs:513` | Typing sent while writing (`MSG/wa/WaRemote.kt:227-230`). "Recording" is never sent because there is no recorder | DONE (typing) |
| Receive typing / recording | `Event::ChatPresence` | `GatewayEvent::Typing` (`GW/wa/session.rs:846-856`) | `PROTO/event.rs:73` | "typing…" / "recording…" under the title, with name in groups (`WaThreadViewModel.kt:1007-1030`) | DONE |
| Own presence (online / offline) | `presence().set_available/unavailable` `LIB/features/presence.rs:118, 123` | `POST /v1/presence` `GW/wa/session.rs:1048` | `set_presence` `CLIENT/http.rs:379` | Bridge only (`BRIDGE/WaOps.kt:207`); no app calls it | GATEWAY-ONLY |
| Subscribe to presence, online / last seen | `presence().subscribe` `LIB/features/presence.rs:138`, `unsubscribe` `:173`; `Event::Presence` | `POST /v1/presence/subscribe`; `GatewayEvent::Presence` (`GW/wa/session.rs:841-845`) | `subscribe_presence` `CLIENT/http.rs:385`; `PresenceBook`, `presence_label` `PROTO/presence.rs:17, 70` | Contacts card only (`android/contacts/.../card/ContactCardViewModel.kt:681`, `CardWaPort.kt:57-63`). **The Messages thread header shows no online / last seen**; Phone does not use it | PARTIAL: Contacts only |

### Polls, locations, contacts, events, business

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Polls: create, vote, results | `polls().create` `LIB/features/polls.rs:50`, `create_quiz` `:67`, `vote` `:120`, `decrypt_vote` `:211`, `aggregate_votes` `:271` | Not handled: a poll falls through to `MessageBody::Unsupported` (`GW/wa/convert.rs:199`). The message secret needed for votes is stored (`convert.rs:399-404`) | `Kind::Unsupported` | "Unsupported message" style placeholder | LIB-ONLY |
| Location, static | proto only (`location_message`), sent with `send_message` | Inbound kept as `MessageMeta.location` with thumbnail (`GW/wa/convert.rs:373-381`); no send | `Location` `PROTO/meta.rs:97`; `Kind::Location` `SYNC/model.rs:26` | A text line ("Location: name") in bubble and inbox (`WaThreadViewModel.kt:443`, `MSG/inbox/InboxRows.kt:290`). No map, not tappable, cannot send | PARTIAL: receive as text |
| Location, live | proto only | First fix only, `live: true` (`GW/wa/convert.rs:382-390`); updates not followed | same | text line | PARTIAL |
| Contact card (vCard) | proto only (`contact_message`, `contacts_array_message`) | First card kept (`GW/wa/convert.rs:391-401`); a multi-contact message keeps only the first | `SharedContact` `PROTO/meta.rs:111`; `Kind::Contact` | A text line with the name (`InboxRows.kt:291`); no "add contact", cannot send | PARTIAL: receive as text |
| Events | `events().create` `LIB/features/events.rs:36`, `respond` `:73` | Unsupported body | none | placeholder | LIB-ONLY |
| Buttons, lists, templates, interactive (business) | No builders. The send path recognises interactive messages for the stanza (`LIB/send/mod.rs:634-718`); content is raw `wa::Message` | Unsupported body | none | placeholder | LIB-ONLY (raw proto), inbound unreadable |
| Channel post comments | `comments().send_text` `LIB/features/comments.rs:35` | none | none | none | LIB-ONLY |

### Groups, communities, channels

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Group info / metadata | `groups().get_metadata` `LIB/features/groups.rs:689`, `get_participating` `:657`, `query_info` `:434` | `GET /v1/groups`, `GET /v1/groups/{jid}` (`GW/routes.rs:855-870`, `GW/wa/session.rs:642-690`); `GroupChanged` event; subject change renames the chat (`session.rs:857-863`) | `groups` `CLIENT/http.rs:398`, `group` `:404`; `GroupInfo` `PROTO/api.rs:310` (subject, participants + admin flag, description, announce) | Bridge has `groups`/`group`; **no app calls them**. Group chats work as chats (sender names, sender card `MSG/wa/WaSenderCard.kt`), but there is no group info page or member list | GATEWAY-ONLY |
| Create group | `create_group` `LIB/features/groups.rs:703` | none | none | none | LIB-ONLY |
| Participants add / remove / promote / demote | `add_participants` `:833`, `remove_participants` `:871`, `promote_participants` `:949`, `demote_participants` `:961` | none | none | none | LIB-ONLY |
| Subject / description / picture | `set_subject` `:748`, `set_description` `:772`, `set_profile_picture` `:1308`, `remove_profile_picture` `:1321` | none (read only) | none | none | LIB-ONLY |
| Invite link get / revoke / join | `get_invite_link` `:973` (with reset flag), `join_with_invite_code` `:1042`, `join_with_invite_v4` `:1049`, `get_invite_info` `:1075` | none | none | none | LIB-ONLY |
| Announcements-only, locked, approval, member-add mode | `set_announce` `:997`, `set_locked` `:986`, `set_membership_approval` `:1029`, `get/approve/reject_membership_requests` `:1083-1108`, `set_member_add_mode` `:1121` | `announce` is read into `GroupInfo`; nothing can be set | read flag | The composer is not disabled in an announce-only group as far as I found | LIB-ONLY |
| Leave group | `leave` `:822` | none | none | none | LIB-ONLY |
| Communities | `community().create` `LIB/features/community.rs:153`, `get_subgroups` `:297`, `link_subgroups` `:234`, `join_subgroup` `:428` and more | none | none | none | LIB-ONLY |
| Newsletters / channels | `newsletter().list_subscribed` `LIB/features/newsletter.rs:168`, `get_messages` `:519`, `join` `:255`, `leave` `:278`, `send_reaction` `:443`, `create` `:223` | Skipped on purpose in history (`GW/wa/history.rs:17`) | none | none | LIB-ONLY |

### Chat state (app state sync)

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Mute / unmute | `mute_chat` `LIB/features/chat_actions.rs:438`, `mute_chat_until` `:444`, `unmute_chat` `:465` | `POST /v1/chats/action` `GW/wa/session.rs:1273-1303`; inbound `MuteUpdate` | `chat_action` `CLIENT/http.rs:557`; `ChatActionKind` `PROTO/meta.rs:355` | Inbox row menu and bulk (`MSG/inbox/InboxViewModel.kt:500, 516`) | DONE |
| Archive | `archive_chat` `:410`, `unarchive_chat` `:419` | same route; `ArchiveUpdate` | same | `InboxViewModel.kt:544` | DONE |
| Pin chat | `pin_chat` `:428`, `unpin_chat` `:433` | same; `PinUpdate` | same | `InboxViewModel.kt:475` | DONE |
| Mark unread / read | `mark_chat_as_read` `:496` | same; `MarkChatAsReadUpdate` | same | `InboxViewModel.kt:488` | DONE |
| Clear chat | `clear_chat` `:553` | Inbound only (`ClearChatUpdate`, `GW/wa/app_state.rs`); no outbound action in `ChatActionKind` | `GatewayEvent::ChatCleared` applied (`SYNC/apply.rs:621`) | Cannot clear from Rosty | PARTIAL: inbound only |
| Delete chat | `delete_chat` `:522` | Inbound only (`DeleteChatUpdate`) | `ChatDeleted` applied (`SYNC/apply.rs:613`) | Inbox Delete is offered for SMS-only rows (`InboxViewModel.kt:771`) | PARTIAL: inbound only |
| Labels | `labels().create_label` `LIB/features/labels.rs:110`, `add_chat_label` `:169`; events `LabelEditUpdate`, `LabelAssociationUpdate` | ignored | none | none | LIB-ONLY |
| Save contact to WhatsApp address book | `save_contact` `LIB/features/chat_actions.rs:645` | Inbound `ContactUpdate` feeds `contact_name` | `WaProfile.contact_name` `PROTO/meta.rs:276` | names shown | PARTIAL: read only |

### Status (stories)

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| View statuses | messages to `status@broadcast` | Stored apart (`GW/wa/status.rs:53-135`, `GW/store/status.rs`), `GET /v1/statuses`, media, thumbnail (`GW/status_routes.rs:33-36`), history import (`status.rs:160`), 24 h purge | `statuses` `CLIENT/http.rs:493`, `status_media` `:498`; `StatusList`, `StatusPlayer` `PROTO/status.rs` | Status row on the WhatsApp tab and a viewer for text and pictures. **Videos are not played** (`MSG/wa/status/StatusViewer.kt:110, 334-351`) | PARTIAL: no video |
| Mark status viewed | `mark_as_read` on the status chat | `POST /v1/statuses/viewed` `GW/wa/session.rs:1428-1447` | `mark_status_viewed` `CLIENT/http.rs:521` | Sent while an update shows (`StatusViewer.kt:86`) | DONE |
| Post status | `status().send_text` `LIB/features/status.rs:56`, `send_image` `:83`, `send_video` `:109`, `revoke` `:151` | none | none | none | LIB-ONLY |
| Status privacy | `StatusPrivacySetting` (contacts / allow list / deny list) per send `LIB/features/status.rs:20-40` | none | none | none | LIB-ONLY |
| Reply to a status | a normal message quoting the status (raw `ContextInfo`) | none; reactions to a status are dropped (`GW/wa/status.rs:65-69`) | none | "Message" opens the author's chat without a quote (`StatusViewer.kt:87-88`) | PARTIAL: opens chat only |
| Mute a contact's statuses | `set_user_status_mute` `LIB/features/chat_actions.rs:583` | ignored | none | none | LIB-ONLY |

### Blocking, profile, lookup

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Blocklist get / block / unblock | `blocking().get_blocklist` `LIB/features/blocking.rs:90`, `block` `:60`, `unblock` `:72`, `is_blocked` `:101` | none | none | none (Rosty's own SMS spam rules are separate) | LIB-ONLY |
| Spam report | `send_spam_report` `LIB/spam_report.rs:34` | none | none | none | LIB-ONLY |
| Own push name / about / picture | `profile().set_push_name` `LIB/features/profile.rs:77`, `set_status_text` `:55`, `set_profile_picture` `:124`, `remove_profile_picture` `:137` | Own name is only read (`InstanceStatus.push_name`, `GW/wa/session.rs:364`) | read | shown in settings | LIB-ONLY (set) |
| Others' about, picture, business flag, verified name | `contacts().get_user_info` `LIB/features/contacts.rs:267`, `get_profile_picture` `:196`; events `PictureUpdate`, `UserAboutUpdate`, `PushNameUpdate` | `POST /v1/contacts/profiles` (`GW/rich_routes.rs:316`, `GW/wa/session.rs:1336-1392`), `GET /v1/pictures/{jid}` with ETag (`rich_routes.rs:448`), `GatewayEvent::Profile` | `profiles` `CLIENT/http.rs:564`, `refresh_profiles` `:575`, `picture` `:588`; `WaProfile` `PROTO/meta.rs:269` | Avatars everywhere (`android/wa/.../avatars/WaAvatars.kt:179`), names and about on the Contacts card, People sync (`android/people/.../sync/PeopleSyncRunner.kt:422-443`) | DONE |
| Full business profile (address, hours, website, catalogue) | `get_business_profile` `LIB/client/iq_ops.rs:148` | none (only the business flag and verified name) | `WaProfile.business`, `verified_name` | flag only | PARTIAL |
| "On WhatsApp" lookup | `contacts().is_on_whatsapp` `LIB/features/contacts.rs:115` | `POST /v1/contacts/lookup` `GW/wa/session.rs:1136-1160`, cached and rate limited (`GW/routes.rs:792`) | `lookup_users` `CLIENT/http.rs:411`; `LookupCache`, `wa_target` `PROTO/lookup.rs` | New conversation (`MSG/compose/NewConversationViewModel.kt:158`), Contacts card, People sync | DONE |

### Sync, identity, devices

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Initial history sync | `Event::HistorySync` `WACORE/types/events.rs:922`; opt-out `skip_history_sync` `LIB/bot.rs:1245` | Imported: chats, messages, ticks, stars, reactions, push names, statuses (`GW/wa/session.rs:864-899`, `GW/wa/history.rs:168-290`). Newsletters and broadcasts skipped | `HistorySynced` event; paging `GET /v1/chats/{jid}/messages?before=&limit=` (`GW/routes.rs:430`); `SYNC/plan.rs` resync planning | Thread pages older messages from the store, then from the gateway (`WaThreadViewModel.kt:627-660`) | DONE |
| On-demand backfill of older history | `fetch_message_history` `LIB/pdo.rs:210` (asks the primary phone) | Not used. "Older" stops at what the first sync delivered | none | Scrolling ends at the gateway's oldest stored message | LIB-ONLY |
| Placeholder resend for undecryptable messages | `send_pdo_placeholder_resend_request` `LIB/pdo.rs:63` (library drives it); `Event::UndecryptableMessage` | Event ignored: the user sees nothing for a message that could not be decrypted | none | none | PARTIAL (library-internal) |
| Offline message queue | Delivered through the normal message path after connect; `Event::OfflineSyncPreview/Completed` `WACORE/types/events.rs:923-924` | Messages arrive via `on_message`; the two progress events are ignored | Client side has its own gap handling (`Resync`, `SYNC/plan.rs:87`) and offline outbox (`SYNC/outbox.rs`) | "Syncing" / offline notices; outbox worker (`MSG/wa/WaOutboxWorker.kt`) | DONE |
| App state sync (syncd patches) | `LIB/appstate_sync.rs`, `LIB/client/app_state.rs`; typed events Pin/Mute/Archive/Star/MarkRead/DeleteChat/ClearChat/DeleteForMe/Label `WACORE/types/events.rs:910-920`; failure event `AppStateSyncFailed` | Handled for pin, archive, mute, read, star, delete-for-me, delete chat, clear chat, contact names (`GW/wa/app_state.rs` `handle`). Labels, status mute and `AppStateSyncFailed` ignored | `ChatMeta`, `MessageMeta`, `MessageDeleted`, `ChatDeleted`, `ChatCleared` events | Reflected in the inbox and thread | DONE for the handled kinds |
| LID / phone-number mapping | `get_lid_pn_entry` `LIB/client/lid_pn.rs:1092`, `add_lid_pn_mapping(s)` `:165, 192`, `is_lid_migrated` `:720` | `Resolver` folds LID chats into phone-number chats and merges old ones (`GW/wa/lid.rs:83-200`); `ChatMerged` event | `GatewayEvent::ChatMerged` applied (`SYNC/apply.rs:630`) | Transparent to the user | DONE |
| Device list of a user | `signal().get_user_devices` `LIB/features/signal.rs:684`; `Event::DeviceListUpdate`; `Event::IdentityChange` | Only used as a trigger for the LID sweep (`GW/wa/session.rs:901-903`); `IdentityChange` ignored (no "security code changed" notice) | none | none | LIB-ONLY |

### Calls

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| 1:1 voice call, signalling and media | Yes, behind the `voip-*` features the gateway enables. `client.voip()` `LIB/client/voip.rs:393`: `call` `:1010`, `accept` `:999`, `reject` `:934`, `terminate` `:1815`; `CallHandle` `hangup`, `set_muted`, `events` `LIB/voip/facade.rs:3694, 3353, 3727`; PCM or encoded audio endpoints `facade.rs:81, 94`; events `IncomingCall`, `MissedCall`, `CallEndedElsewhere` | Full: `CallTable` and `CallMachine`; routes place / list / accept / reject / end / mute and the audio WebSocket (`GW/wa/calls.rs:1097-1102`); library calls at `calls.rs:1481-1527`; MLOW PCM audio by default (`GW/wa/call_audio.rs:1-35`); FCM wake for a ring (`GW/push/`) | `place_call` `CLIENT/http.rs:420`, `calls` `:425`, `accept_call` `:429`, `reject_call` `:433`, `end_call` `:437`, `set_muted` `:441`; 16 kHz PCM socket `CLIENT/audio.rs:108`; `CallMachine`, `WaCallHistory` `PROTO/call.rs`, `PROTO/history.rs`; `rosty_app_core/src/phone/wa_call.rs` | Phone: ring notification, in-call screen, accept / reject / end / mute, call history (`android/phone/.../wa/WaCallSource.kt`, `WaRingNotifier.kt`, `WaCallsRouter.kt`; `android/wa/.../calls/WaCalls.kt`, `WaCallActions.kt`) | DONE |
| Video call | Transport for pre-encoded H.264 only (`LIB/voip/video.rs:1-6`); `start_video`, `accept_video`, `stop_video` `LIB/voip/facade.rs:3368-3427` | `is_video` is carried in `CallUpdate` (`GW/wa/calls.rs:721, 736`) but only audio endpoints are wired (`calls.rs:1336-1354`) | `CallUpdate.is_video` `PROTO/event.rs:28` | The flag reaches Phone (`WaCallSource.kt:216`); no camera or video surface | LIB-ONLY (a video call can at most be taken as audio; not verified) |
| Group calls | `group_call` `LIB/client/voip.rs:1016`, `group_call_by_id` `:1023`, invite accept `:1039-1081`, `invite_participant` `LIB/voip/facade.rs:3169` | Explicitly ignored: "ignoring a group call" (`GW/wa/calls.rs:725-728`) | none | none | LIB-ONLY |
| Call links, waiting room, screen share, raise hand, in-call reactions | `create_call_link` `LIB/client/voip.rs:1120`, `join_call_link` `:1168`, `set_screen_share` `:1618`, `set_hand_raised` `:1548` | none | none | none | LIB-ONLY |

### Pairing and session

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| QR pairing | `on_qr_code` `LIB/bot.rs:975`; `make_qr_data` `LIB/pair.rs:18` | `GatewayEvent::Qr` (`GW/wa/session.rs:329-342`) | `LinkRequest::Qr` `PROTO/api.rs:87`; `create_instance` `CLIENT/http.rs:269` | QR shown in WhatsApp settings (`android/wa/.../settings/WhatsAppSettingsScreen.kt`, `QrPng.kt`) | DONE |
| Pair code (phone number) | `with_pair_code` `LIB/bot.rs:1230`, `pair_with_code` `LIB/pair_code.rs:220`, `on_pair_code` `bot.rs:995`; refresh and error hooks `:1025, 1047` | `GatewayEvent::PairCode` (`session.rs:343-354`); refresh and error hooks not wired | `LinkRequest::PhoneNumber` `PROTO/api.rs:88` | Offered in settings (`WhatsAppSettingsViewModel.kt:448`) | DONE |
| Passkey pairing | `LIB/passkey/flow.rs`, events `PairPasskeyRequest/Confirmation/Error` | ignored | none | none | LIB-ONLY |
| Multi-device session storage | `whatsapp-rust-sqlite-storage 0.7.0` (`SqliteStore`) | One `wa.db` per instance (`GW/wa/session.rs:317-324`) | n/a | n/a | DONE |
| Logout | `Client::logout` `LIB/client/lifecycle.rs:871`; `Event::LoggedOut` | `DELETE /v1/instance` logs out and deletes (`GW/routes.rs:223-230`, `session.rs:959-970`); remote logout handled (`session.rs:385-402`) | `delete_instance` `CLIENT/http.rs:282`; `InstanceState::LoggedOut` | "Unlink" in settings (`WhatsAppSettingsViewModel.kt:307, 353`) | DONE |
| Relink | new pairing on the same store | `POST /v1/instance/link` `GW/routes.rs:191` | `relink` `CLIENT/http.rs:278` | Settings (`WhatsAppSettingsViewModel.kt:345-348`) | DONE |
| Share one instance between apps | not a library feature | Connect keys: mint, list, revoke (`GW/routes.rs:236-285`) | `mint_key`, `keys`, `revoke_key` `CLIENT/http.rs:288-301`; `ConnectKey` `PROTO/key.rs` | Key list, mint, paste, revoke in settings | DONE |
| Ban / stream-replaced notice | `Event::TemporaryBan`, `StreamReplaced`, `ConnectFailure` | Mapped to `InstanceState::Banned` / `Disconnected` (`session.rs:919-940`) | `InstanceState` `PROTO/api.rs:123` | State shown in settings | DONE |

### Search

| Feature | whatsapp-rust 0.7.0 | Gateway today | Client, proto, wa_sync | Kotlin today | Status |
|---|---|---|---|---|---|
| Search inside chats | None server-side: no search API in `LIB/features/` or `LIB/client/` | No search route | Local SQLite store is the only source (`SYNC/query.rs`) | Local: find-in-thread and jump (`WaThreadPage.kt:199-203, 323-326`), global search (`MSG/inbox/GlobalSearch.kt`, `MSG/wa/StoreWaChats.kt:59`). Only covers what the device has synced | DONE (local only) |

## Other library capabilities found, not exposed anywhere

- Reach-out timelock and GraphQL (`mex().fetch_reachout_timelock` `LIB/features/mex.rs:119`).
- Group member labels (`update_member_label` `LIB/features/groups.rs:1369`), group history sharing modes (`:1160-1188`).
- Batch group pictures (`get_profile_pictures` `:1274`).
- Signal session tools and device queries (`LIB/features/signal.rs`).
- Trusted-contact tokens (`tc_token()` `LIB/features/tctoken.rs:109`).
- Raw node access and plugins (`LIB/plugins/`), which is the route for anything the typed API lacks.

## Biggest user-value gaps, ranked

Ranking is my judgement of everyday use, not measured.

1. **Record and send voice notes.** Blocked by **UI** only. Library, gateway (`voice_note` → `ptt`), client, outbox and
   bridge already carry it. Add a waveform and "recording…" chat state at the same time (gateway does not set
   `waveform`).
2. **Edit a sent message.** Blocked by **UI** only: `WaThreadViewModel.edit()` and the whole stack exist; the menu has
   no entry for sent messages.
3. **Thumbnails and dimensions on sent media.** Blocked by **gateway** (`GW/wa/media.rs` sets no `jpeg_thumbnail`,
   width or height). Recipients see a blank tile until they download.
4. **Polls.** Blocked by **gateway** first (convert, vote, aggregate), then proto / client / wa_sync kind / bridge / UI.
   Today a poll is an unsupported bubble.
5. **Group info and management** (member list, leave, add/remove, subject, invite link). Read path is blocked by **UI**
   (routes and bridge exist, no caller). Every write is blocked by **gateway** and all layers above.
6. **Location and contact cards as real bubbles, and sending them.** Receive is blocked by **UI** (data arrives, shown
   as a text line). Send is blocked by **gateway** upward.
7. **Disappearing messages.** Blocked by **gateway** upward. Also a correctness issue: sends into a timed chat do not
   carry the timer and stored messages never expire.
8. **Online / last seen in the Messages thread; own presence.** Blocked by **UI** (Contacts already shows it; nothing
   calls `setPresence`).
9. **Forward, delete for me, played receipts, admin delete.** Forward is blocked by **gateway** (no route for
   `forward_message`). Delete for me and played are blocked by **wa_sync** (no outbox action) and **UI**.
10. **Expired media re-upload and on-demand older history.** Blocked by **gateway** (`media_reupload().request`,
    `fetch_message_history` unused).
11. **Status: play videos, post, reply.** Video playback is blocked by **UI**. Posting and replying are blocked by
    **gateway** upward.
12. **Block / unblock and privacy settings.** Blocked by **gateway** upward.
13. **Mentions.** Rendering is blocked by **UI**; sending by **gateway** upward.
14. **Video and group calls.** Blocked by **gateway** upward. The library only transports pre-encoded H.264, so the
    codec and camera work would sit in the app; group calls are dropped on purpose.
15. **View-once, GIF loop, animated stickers, link previews, pin / keep message, labels, communities, channels.**
    Mostly **gateway** upward; view-once handling and GIF loop also need **UI**.

Nothing in the list is blocked by the **library** at 0.7.0, except that there is no server-side message search and
that video is transport-only.
