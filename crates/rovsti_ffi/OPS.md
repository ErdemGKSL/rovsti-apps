# Op inventory (E2 R2)

Every public item of `rovsti_core`, `rovsti_geo`, `rovsti_tiles` and `rovsti_app_core` that the deprecated GPUI apps call
(`apps/*/src`, `crates/rovsti_ui/src`, `crates/rovsti_wa_ui/src`), and what becomes of it in the Kotlin apps. Generated once from
`use` paths and inline paths; methods fold into their type. `tests/inventory.rs` parses the table below and checks it
against the op registry: the ops named by `op` rows are exactly the registered ops (`core.panic` and `core.emit_test` are
test-only), and no row of a finished domain is `todo` (`DONE_DOMAINS` in that test grows with each step).

Classes:

- `op <name>[, <name>]`: exposed through `call` (or `tile` / audio); one `OpSpec` per name in `src/ops/<domain>.rs`.
- `kotlin <target>`: per-keystroke or per-frame behaviour that must answer synchronously on the main thread; ported to Kotlin in
  the app epic, the Rust unit tests mirrored as JVM tests. The target names the planned module and file.
- `none <reason>`: not needed by the Kotlin apps.
- `todo`: not yet decided or built; allowed only for domains that are not in `DONE_DOMAINS` (every domain is done since E2.14, so no row is `todo`).

Handles (E2.9): `search.index_new` and `phone.wa_tracker_new` answer `{"handle": <long>}`; every later op of that
object sends the handle back, and `RustNative.release` frees it (a released or foreign handle answers `not_found`). The
contact index owns its `T9Scratch`, so `t9.query` narrows the previous digits' candidates. Every other op is stateless: the
request carries the data (call-log entries, notes, speed-dial map) and the answer the new data.

Contacts (E2.10): `contacts.deep_index_new` answers `{"handle", "count"}`, the handle of the detail-search index (`contacts.deep_find`
takes it back; a released or foreign handle answers `not_found`). Every other `contacts.*` op is stateless: the request carries the
drafts, contacts or marks and the answer the new data. The trash (`contacts-trash.json`) and the preferences (`contacts-prefs.json`)
are files in the data directory: a request may name `dir`, else `core.init`'s data dir is used. Drafts travel as
`rovsti_core::contact_edit::ContactDraft` (empty optional lists left out). The editor's per-keystroke state, the select-mode set and
the sheet state of the screens are Kotlin (E4); the ops here run per event.

Messages core (E2.11a): `inbox.merge`, `inbox.person_for_number` and `inbox.person_for_jid` take the handle of a contact index
(`search.index_new`) as `contacts`; a released or foreign handle answers `not_found`. Every other `inbox.*`, `sms.*`,
`sms_timeline.*`, `mms_pdu.*`, `otp.*`, `spam.*`, `schedule.*`, `notify.*` and `msg_backup.*` op is stateless. Choices worth knowing:

- `sms_timeline.build` replays an ordered `events` list (`page`, `pending`, `status`, `retry`, `discard`, `remove`, `adopt_thread`;
  the `kind` field names the event) on a fresh `SmsTimeline` and answers the ascending items plus one result per event (a retry's
  `SmsSend`, a discarded text). No handle: a thread's events are a few hundred small values and the Kotlin ViewModel already keeps
  them, so a replay costs less than keeping native state in step with it.
- `schedule.*` is stateless over a `queue_json` string (the content of `scheduled.json`), not a `ScheduleQueue` handle. Nothing in
  `rovsti_app_core` persists the queue, the alarm registration is Kotlin (`AlarmManager`), and the queue is capped at 200 small
  items, so the string round-trips for free and survives process death without recreating a handle. Every op answers the new
  `queue_json` (save it as is) and the parsed `items`; a corrupt or empty string reads as an empty queue.
- `msg_backup.export` takes the `salt` (16 bytes) and `nonce` (12 bytes) as base64 from Kotlin's `SecureRandom`, because Rust has
  no RNG dependency and a fixed pair must never reach production; `iterations` defaults to 600 000 (at least 1 000 is accepted
  so fixtures stay fast). `msg_backup.parse` answers `auth` (`detail.reason = "wrong_passphrase"`) for a wrong passphrase and
  `parse` (`detail.reason` = `not_a_backup` | `newer_version` | `corrupt`) for the rest. All bytes travel as standard base64.
- `otp.find` answers UTF-16 offsets (`start`, `end`), the unit of a Kotlin `String`, not Rust byte offsets.
- `inbox.pending_delete` rebuilds `PendingDeletes<String>` from the `pending` list (`{key, at_ms}`), applies `action` (`add`, `undo`,
  `due`, `drain`, `none`) and answers the new list; `inbox.filter` answers the chips to show (the selected one always among them).
- `messages.prefs_load` / `messages.prefs_save` / `messages.drafts_*` use `dir` like the other file ops; `messages.prefs_edit`
  applies one named edit (`mute`, `unmute`, `expire_mutes`, `mark_spam`, `unmark_spam`, `allow_sender`, `unallow_sender`,
  `remove_allowed`, `rekey`, `reset`) so the rules stay in Rust and Kotlin only saves the result. Drafts travel as a map of
  conversation tag (`sms:7`, `wa:<jid>`) to text.

QR (E2.10, AC0): `qr.encode {text, ecc: "l"|"m"|"q"|"h" (default m)}` answers `{side, modules}`: `modules` is the standard base64
(padded) of a bitset of `side * side` bits, row-major, the first module of the first row in the most significant bit of the first
byte, a set bit a dark module, unused trailing bits zero; the quiet zone (4 modules) is not part of the matrix. `qr.decode {w, h, luma}`
takes `w * h` 8-bit luma bytes (row-major, base64, at most 1600 x 1600) and answers `{texts}`, every code it reads without repeats; a
size mismatch is `invalid`, bad base64 `parse`. The decoder is `rqrr` (through `contact_scan::decode_luma`); there is no ZXing or ML Kit.
Kotlin: `QrMatrix` in `:core-bridge` reads the bitset (`QrMatrix.get(x, y)`).

Stops (E2.12): `stops.trip_start` / `stops.trip_restore` answer `{handle, ...}` (a `TripView` with the whole persisted trip as `snapshot`),
the handle of an `ActiveTrip`; `stops.trip_{state, fix, notice_result, snooze, timeout, dismiss, status_share, end}` send it back and
`RustNative.release` frees it (Kotlin: `ActiveTripHandle`). Every other `stops.*` and `view.*` op is stateless. Choices worth knowing:

- Persistence: a trip op that names `dir` also writes `trip.json` under `rovsti_app_core::stops::store::lock()`, the lock the
  headless kinds (`trip_fix`, `trip_alarm`, `trip_end`) take on the service thread; `stops.store_trip_{load, save, end}` hold it for
  their whole read-modify-write. The headless fixtures are untouched. `stops.store_read` / `stops.store_write` cover `saved.json`,
  `history.json` and `draft.json` (the file's shape is checked on write); `stops.prefs_load` / `stops.prefs_save` cover `stops-prefs.json`.
- Errors that are sentences for the user (`DiameterError`, `SavedStopError`, `DestError`, `BackupError`, `ImportError`, `TemplateError`,
  `TileUrlError`) answer `invalid` with the sentence as `message` and a stable `detail.reason` (`too_short`, `same_point`,
  `duplicate` + `id`, `no_coordinates`, `newer`, ...).
- `stops.pick_replay` replays gesture events (`tap`, `place`, `drag_start`, `drag_to`, `drag_end`, `swap`, ...) on a fresh `Picking`, the
  same stateless shape as `sms_timeline.build`; the pin following the finger frame by frame stays Kotlin.
- `stops.saved_edit`, `stops.history_edit` and `stops.prefs_edit` apply one named edit to the value in the request and answer the new
  value (Kotlin saves it); the rules (name length, duplicates, the 200-stop cap, favourites first, sound-or-vibrate) stay in Rust.
- `stops.gtfs_import {zip, now_ms, save?, dir?}` reads the zip at a path (a tiny feed lives in `tests/data/gtfs-tiny.zip`; fixture requests
  write it as `$DATA/gtfs-tiny.zip`) and answers the `FeedReport`; `save` writes `gtfs.json`, which `stops.gtfs_search` and
  `stops.saved_suggest_name` read. `stops.measure` takes the point list (Kotlin keeps it; undo drops the last point).
- `view.*` (`project`, `step`, `tiles`, `frac_to_coord`, `follow`) wrap `rovsti_geo::view` and the slippy maths of `rovsti_geo::tiles`.
  Their fixtures are the cross-check data for the Kotlin `MapCamera` (E7): same centre, scale and pixel in, same coordinate or tile set out.

Tiles (E2.13): `tiles.open {server_url?, cache_cap?, dir?}` answers `{handle, key, template, is_default, cap_bytes, cached_bytes,
cached_tiles}`, a `TileClient`, a `TileSource` and the per-server `DiskCache` (`dir` defaults to `<cache dir>/tiles`). The `tile(handle, z, x, y,
flags)` export of `RustNative` reads the handle (cache or download, `TileGeometry`, style, encode) and answers the `RVT1` buffer of
`docs/kotlin-ui/tile-buffer.md`, or an error envelope; the caller tells them apart by the magic (`RVT1` against `{`). Flag bit 0 asks for the dark
style, bit 1 for the cache only (a tile that is not cached is `not_found` with `detail.reason = "not_cached"`, no network is touched).

- Cache ops take the handle: `tiles.usage` (all servers, this server, the cap), `tiles.usage_by_source`, `tiles.clear_all`, `tiles.clear_except`
  (keeps this server unless `keep_key` names another), `tiles.set_cap`. `tiles.test_server {server_url}` answers the layer count of tile 0/0/0.
- Download errors are envelopes with a sentence in `message` and a stable `detail.reason`: `unreachable` (`network`), `refused` (`auth`),
  `no_tile` (`not_found`), `status` (`network`, with `status`), `too_large` (`invalid`), `not_vector` (`parse`), `storage` (`io`); a bad server URL is
  `invalid` with `scheme`, `placeholder`, `whitespace`, `too_long` or `empty`.
- `tiles.offline_start {handle, bbox | circle, zooms?}` plans the tiles (`bbox {south, west, north, east}` with `tiles_for_area` and the 2 500-tile
  budget, or `circle {center, radius_m, margin_m?, budget?}` with `tiles_for_circle` for a trip), answers `{handle, total, top_zoom, capped,
  estimate_bytes}` and downloads on a background thread with four workers. Progress arrives as `tiles.offline` events `{job, done, saved, failed,
  total, fraction, text, finished, cancelled}` (at most every 100 ms, and always the last one with `finished: true`). `tiles.offline_cancel {job}`
  stops the queue (the downloads in flight finish and stay cached); releasing the job handle cancels too.
- `tiles.encode_mvt {mvt, z, x, y, flags}` is the pure half of `tile` (no cache, no network); its fixtures hold the real `.mvt` of
  `crates/rovsti_geo/src/tiles/testdata` and the buffer it becomes, which the Kotlin `TileBuffer` test decodes.

WhatsApp (E2.14): `wa.open {url, key?, cache_dir?}` answers `{handle}`, a `rovsti_wa_client::Gateway`; every network op is `wa.<verb>` with `{handle, ...flat fields of the
`rovsti_wa_proto` request}` and answers the proto type (field names are the gateway's). The client call runs outside the registry lock. Errors: `network`, `auth` (401 and 403),
`invalid` with the gateway's own sentence (`detail.code` is its error code) and `parse`. Bytes (`wa.media`, `wa.thumbnail`, `wa.status_media`, `wa.status_thumbnail`, `wa.picture`)
answer `{size, data_b64}` up to 1 MiB, else `{size, path}` of a file in the handle's cache directory (Kotlin deletes it). `wa.send_media(_with)` takes `data_b64` or a `path`.
`wa.events_start {handle, instance, after?}` answers a stream handle and emits every socket message as event `wa.event` `{instance, event}` with `event.kind` one of `connected`,
`event` (`seq`, `at_ms`, `event` = the gateway event, tagged `type`), `disconnected {reason}`, `unauthorized`; `wa.events_reconnect_now`, `RustNative.release` stops it. `wa.call_audio_open
{handle, call_id}` answers an audio handle for `RustNative.audioPush` / `audioPull` (frames of exactly 1 920 bytes; push drops any other size, pull answers 1 920, 0 when nothing
is ready, -1 when the call ended). Fixtures run against a loopback stub that answers `crates/rovsti_ffi/tests/data/wa/<stem>.json` (`tests/support/mod.rs`); each file also states the request
the client must have sent. Pure: `wa.push_parse` (FCM wake), `wa.link_read` / `wa.link_write` (`whatsapp-link.json`), `wa.avatars_plan` (picture file names and the disk prune plan; the
`AvatarBook` fetch policy is ported to Kotlin with the Rust unit tests in `:wa`, E3), `wa.names`, `wa.target` (contact -> chat/number target and the JIDs to look up), `wa.settings_apply {model?, link?, app, now_ms, input?}` (the stateless WhatsApp settings state machine: answers `{model, effects, view}`, a render-ready page; the caller keeps `model` as JSON; the gateway URL and `rwk_` key checks run inside it).
Phone backup: `phone.backup_export` / `phone.backup_import` (pure; Kotlin reads and writes the picked file), `phone.display_name`.

| Item | Used by | Domain | Class | Target |
|---|---|---|---|---|
| `(bridge) core.version` | - | bridge | op | core.version |
| `(bridge) core.echo` | - | bridge | op | core.echo |
| `(bridge) core.data_dir` | - | bridge | op | core.data_dir |
| `(bridge) core.fail` | - | bridge | op | core.fail |
| `(bridge) core.panic` | - | bridge | op | core.panic |
| `(bridge) core.emit_test` | - | bridge | op | core.emit_test |
| `(qr) qr.encode` | contacts | qr | op | qr.encode |
| `(qr) qr.decode` | contacts | qr | op | qr.decode |
| `(wa) wa.call_audio_open` | - | wa | op | wa.call_audio_open |
| `(wa) wa.events_reconnect_now` | - | wa | op | wa.events_reconnect_now |
| `(wa) wa.events_start` | - | wa | op | wa.events_start |
| `(wa) wa.media` | - | wa | op | wa.media |
| `(wa) wa.open` | - | wa | op | wa.open |
| `(wa) wa.picture` | - | wa | op | wa.picture |
| `(wa) wa.push_parse` | - | wa | op | wa.push_parse |
| `(wa) wa.send_media` | - | wa | op | wa.send_media |
| `(wa) wa.status_media` | - | wa | op | wa.status_media |
| `(wa) wa.status_thumbnail` | - | wa | op | wa.status_thumbnail |
| `(wa) wa.thumbnail` | - | wa | op | wa.thumbnail |
| `(wa) wa.accept_call` | - | wa | op | wa.accept_call |
| `(wa) wa.calls` | - | wa | op | wa.calls |
| `(wa) wa.cancel_scheduled` | - | wa | op | wa.cancel_scheduled |
| `(wa) wa.chat_action` | - | wa | op | wa.chat_action |
| `(wa) wa.chat_state` | - | wa | op | wa.chat_state |
| `(wa) wa.chats` | - | wa | op | wa.chats |
| `(wa) wa.chats_with_meta` | - | wa | op | wa.chats_with_meta |
| `(wa) wa.create_instance` | - | wa | op | wa.create_instance |
| `(wa) wa.delete_for_me` | - | wa | op | wa.delete_for_me |
| `(wa) wa.delete_instance` | - | wa | op | wa.delete_instance |
| `(wa) wa.edit_message` | - | wa | op | wa.edit_message |
| `(wa) wa.end_call` | - | wa | op | wa.end_call |
| `(wa) wa.group` | - | wa | op | wa.group |
| `(wa) wa.groups` | - | wa | op | wa.groups |
| `(wa) wa.keys` | - | wa | op | wa.keys |
| `(wa) wa.lookup_users` | - | wa | op | wa.lookup_users |
| `(wa) wa.mark_played` | - | wa | op | wa.mark_played |
| `(wa) wa.mark_read` | - | wa | op | wa.mark_read |
| `(wa) wa.mark_status_viewed` | - | wa | op | wa.mark_status_viewed |
| `(wa) wa.messages` | - | wa | op | wa.messages |
| `(wa) wa.messages_with_meta` | - | wa | op | wa.messages_with_meta |
| `(wa) wa.mint_key` | - | wa | op | wa.mint_key |
| `(wa) wa.place_call` | - | wa | op | wa.place_call |
| `(wa) wa.profiles` | - | wa | op | wa.profiles |
| `(wa) wa.react` | - | wa | op | wa.react |
| `(wa) wa.refresh_profiles` | - | wa | op | wa.refresh_profiles |
| `(wa) wa.register_push` | - | wa | op | wa.register_push |
| `(wa) wa.reject_call` | - | wa | op | wa.reject_call |
| `(wa) wa.relink` | - | wa | op | wa.relink |
| `(wa) wa.revoke_key` | - | wa | op | wa.revoke_key |
| `(wa) wa.revoke_message` | - | wa | op | wa.revoke_message |
| `(wa) wa.schedule_text` | - | wa | op | wa.schedule_text |
| `(wa) wa.scheduled` | - | wa | op | wa.scheduled |
| `(wa) wa.send_media_with` | - | wa | op | wa.send_media_with |
| `(wa) wa.send_text` | - | wa | op | wa.send_text |
| `(wa) wa.set_muted` | - | wa | op | wa.set_muted |
| `(wa) wa.set_presence` | - | wa | op | wa.set_presence |
| `(wa) wa.star_message` | - | wa | op | wa.star_message |
| `(wa) wa.status` | - | wa | op | wa.status |
| `(wa) wa.statuses` | - | wa | op | wa.statuses |
| `(wa) wa.subscribe_presence` | - | wa | op | wa.subscribe_presence |
| `rovsti_app_core::contacts::prefs` | contacts | contacts | op | contacts.prefs_load, contacts.prefs_save |
| `rovsti_app_core::contacts::trash_store` | contacts | contacts | op | contacts.trash_list, contacts.trash_push, contacts.trash_restore, contacts.trash_delete |
| `rovsti_app_core::contacts::whatsapp` | contacts | wa | op | wa.target, contacts.wa_marks, contacts.wa_card, contacts.wa_profile_lines |
| `rovsti_app_core::messages::access` | messages | messages | op | messages.access |
| `rovsti_app_core::messages::drafts` | messages | messages | op | messages.drafts_load, messages.drafts_save, messages.drafts_put, messages.drafts_rekey |
| `rovsti_app_core::messages::headless` | messages | platform | none | served by the headless kinds (E2.6, fixtures headless.*) |
| `rovsti_app_core::messages::prefs` | messages | messages | op | messages.prefs_load, messages.prefs_save, messages.prefs_edit |
| `rovsti_app_core::messages::tags` | messages | messages | op | messages.tag |
| `rovsti_app_core::permission::Access` | messages | platform | op | messages.access |
| `rovsti_app_core::phone::notes` | phone | phone | op | phone.notes_load, phone.notes_save, call_notes.set, call_notes.remove, call_notes.restore |
| `rovsti_app_core::phone::wa_call` | phone | phone | op | phone.wa_tracker_new, phone.wa_tracker_update, phone.wa_tracker_placed, phone.wa_tracker_view, phone.wa_tracker_mute, phone.wa_tracker_clear, phone.wa_tracker_can_place, phone.wa_tracker_format_elapsed, phone.wa_arbitrate, phone.wa_screen, phone.wa_handoff_number |
| `rovsti_app_core::stops::headless` | stops | platform | none | served by the headless kinds (E2.6, fixtures headless.*) |
| `rovsti_app_core::stops::pick` | stops | stops | op | stops.pick_replay, stops.pick_labels, stops.pick_save_offer |
| `rovsti_app_core::stops::snap` | stops | stops | op | stops.snap |
| `rovsti_app_core::stops::store` | stops | stops | op | stops.store_trip_load, stops.store_trip_save, stops.store_trip_end, stops.store_read, stops.store_write |
| `rovsti_app_core::stops::types` | stops | stops | op | stops.pick_replay, stops.pick_labels, stops.pick_save_offer |
| `rovsti_app_core::storage::BackupRefusal` | rovsti_ui | platform | op | phone.backup_export, phone.backup_import |
| `rovsti_app_core::storage::Loaded` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::Origin` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::PREFS_FILE` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::Prefs` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::backup_json` | rovsti_ui | platform | op | phone.backup_export, phone.backup_import |
| `rovsti_app_core::storage::copy_aside` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::load_json` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::load_prefs` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::parse_backup` | rovsti_ui | platform | op | phone.backup_export, phone.backup_import |
| `rovsti_app_core::storage::save_json` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::storage::save_prefs` | rovsti_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rovsti_app_core::wa_link::avatars` | rovsti_wa_ui | wa | op | wa.avatars_plan |
| `rovsti_app_core::wa_link::link` | rovsti_wa_ui | wa | op | wa.link_read, wa.link_write |
| `rovsti_app_core::wa_link::names` | rovsti_wa_ui | wa | op | wa.names |
| `rovsti_app_core::wa_link::send` | rovsti_wa_ui | wa | none | served by the messages headless kinds wa_digest and wa_reply (E2.6, fixtures headless.*) |
| `rovsti_app_core::wa_link::settings` | rovsti_wa_ui | wa | op | wa.settings_apply |
| `rovsti_app_core::wa_link::settings_view` | rovsti_wa_ui | wa | op | wa.settings_apply |
| `rovsti_core::Channel` | messages | messages | op | inbox.preferred_channel |
| `rovsti_core::Message` | messages | messages | op | inbox.merge, sms.to_message |
| `rovsti_core::MessageDirection` | messages | messages | op | inbox.merge, sms.to_message |
| `rovsti_core::PhoneNumber` | messages,phone | shared | op | phone_number.parse, phone_number.same |
| `rovsti_core::ThreadId` | messages | messages | op | messages.tag |
| `rovsti_core::audio::Decision` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::Diff` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::EndpointList` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::MAX_ROWS` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::Row` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::audio::RowKind` | phone | phone | op | audio.rows, audio.diff |
| `rovsti_core::avatar::AvatarKey` | phone | contacts | op | contacts.avatar |
| `rovsti_core::avatar::avatar_hue` | phone | contacts | op | contacts.avatar |
| `rovsti_core::avatar::hue_index` | phone | contacts | op | contacts.avatar |
| `rovsti_core::avatar::initials` | messages | contacts | op | contacts.avatar |
| `rovsti_core::avatar::monogram` | phone | contacts | op | contacts.avatar |
| `rovsti_core::birthday_reminder::BirthdayAlarm` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::birthday_reminder::DEFAULT_HOUR` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::birthday_reminder::HOUR_CHOICES` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::birthday_reminder::hour_label` | contacts | contacts | op | contacts.birthday_reminders |
| `rovsti_core::blocklist::BlockInputError` | phone | phone | op | blocklist.parse_input |
| `rovsti_core::blocklist::BlockRefusal` | messages,phone | phone | op | blocklist.check |
| `rovsti_core::blocklist::BlockSet` | messages,phone | phone | op | blocklist.check |
| `rovsti_core::blocklist::can_block` | messages,phone | phone | op | blocklist.check |
| `rovsti_core::bubble_gesture::SwipeReply` | messages | input | kotlin | :messages BubbleGesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::call::CallModel` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::CallState` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::after_call_text` | phone | phone | op | scene.texts |
| `rovsti_core::call::caller_label` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::format_elapsed` | phone | phone | op | scene.texts |
| `rovsti_core::call::format_number` | phone | shared | op | format.render |
| `rovsti_core::call::incoming_announcement` | phone | phone | op | scene.texts |
| `rovsti_core::call::is_missed` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call::missed_headline` | phone | phone | op | scene.texts |
| `rovsti_core::call::status_text` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::call_log::CallType` | contacts,phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::DateOrder` | phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::EntryRef` | contacts,phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::FREQUENT_LIMIT` | phone | phone | op | call_log.frequent, call_log.counts |
| `rovsti_core::call_log::FREQUENT_WINDOW_DAYS` | phone | phone | op | call_log.frequent, call_log.counts |
| `rovsti_core::call_log::RecentsRow` | phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::TimeFormat` | phone | phone | op | recents.group, recents.build |
| `rovsti_core::call_log::badge_label` | phone | phone | op | recents.missed_extras |
| `rovsti_core::call_log::blocked_ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rovsti_core::call_log::deleted_text` | phone | phone | kotlin | :phone res/values/strings.xml (user-facing sentences live in Android resources, localised) |
| `rovsti_core::call_log::entries_by_id` | phone | phone | kotlin | :phone RecentsRepository.kt (a filter by id over the list Kotlin already holds) |
| `rovsti_core::call_log::frequent` | phone | phone | op | call_log.frequent, call_log.counts |
| `rovsti_core::call_log::ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rovsti_core::call_log::missed_badge` | phone | phone | op | recents.missed_extras |
| `rovsti_core::call_log::relative_time` | messages,phone | phone | op | recents.build, recents.history_text |
| `rovsti_core::call_log_query::PruneAge` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::Query` | phone | phone | op | call_log.query |
| `rovsti_core::call_log_query::RecentsFilter` | phone | phone | op | call_log.query |
| `rovsti_core::call_log_query::csv_file_name` | phone | phone | op | call_log.csv |
| `rovsti_core::call_log_query::delete_selected_text` | phone | phone | kotlin | :phone res/values/strings.xml (user-facing sentences live in Android resources, localised) |
| `rovsti_core::call_log_query::ids_older_than` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::person_export` | phone | phone | op | call_log.csv |
| `rovsti_core::call_log_query::prune_none_text` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::prune_text` | phone | phone | op | call_log.prune_plan |
| `rovsti_core::call_log_query::to_csv_with_notes` | phone | phone | op | call_log.csv |
| `rovsti_core::call_notes::CallDetails` | phone | phone | op | call_notes.details |
| `rovsti_core::call_notes::CallNote` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rovsti_core::call_notes::Notes` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rovsti_core::call_notes::SetOutcome` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rovsti_core::call_notes::details` | phone | phone | op | call_notes.details |
| `rovsti_core::call_notes::notes_csv_file_name` | phone | phone | op | call_notes.export |
| `rovsti_core::call_stats::DayBar` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::Range` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::StatCall` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::StatDir` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::call_stats::Totals` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rovsti_core::calling_code::country_line` | phone | shared | op | calling_code.line |
| `rovsti_core::chat_export::ExportLine` | messages | messages | op | chat_export.text, chat_export.block, chat_export.notice |
| `rovsti_core::chat_find::FindCursor` | messages | messages | op | chat_find.find, chat_find.cursor |
| `rovsti_core::chat_gallery::DocEntry` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rovsti_core::chat_gallery::Gallery` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rovsti_core::chat_gallery::GalleryInput` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rovsti_core::chat_gallery::GalleryKind` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rovsti_core::chat_gallery::GalleryTab` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rovsti_core::chat_gallery::LinkEntry` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rovsti_core::clock::now_ms` | contacts,messages,phone,rovsti_wa_ui | platform | none | ops take now_ms; Kotlin owns the clock |
| `rovsti_core::clock::set_fixed_now_ms` | contacts,messages,phone,stops | platform | none | ops take now_ms; Kotlin owns the clock |
| `rovsti_core::contact_card::ContactCard` | contacts | contacts | op | contacts.card |
| `rovsti_core::contact_cleanup::Cleanup` | contacts | contacts | op | contacts.cleanup |
| `rovsti_core::contact_csv` | contacts | contacts | op | contacts.csv_export, contacts.csv_import |
| `rovsti_core::contact_dates::Upcoming` | contacts | contacts | op | contacts.dates, contacts.event_line, contacts.maps_url |
| `rovsti_core::contact_dates::local_ymd` | contacts,stops | contacts | op | contacts.local_ymd |
| `rovsti_core::contact_dates::parse` | contacts | contacts | op | contacts.date_parse, contacts.age |
| `rovsti_core::contact_dupes::DupGroup` | contacts | contacts | op | contacts.dupes, contacts.merge_plan |
| `rovsti_core::contact_dupes::find` | contacts | contacts | op | contacts.dupes |
| `rovsti_core::contact_edit::Account` | contacts | contacts | op | contacts.trash_restore, contacts.account_label |
| `rovsti_core::contact_edit::AccountsPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::Addition` | contacts | contacts | op | contacts.draft_from_query, contacts.draft_add |
| `rovsti_core::contact_edit::ContactDraft` | contacts | contacts | op | contacts.draft_validate, contacts.draft_diff, contacts.draft_edit |
| `rovsti_core::contact_edit::DetailPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::DraftEvent` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::DraftField` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::DraftRelation` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::EventKind` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::FieldKind` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::FullPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::Group` | contacts | contacts | op | contacts.groups_title, contacts.groups_targets |
| `rovsti_core::contact_edit::GroupsPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads groups into model.contacts.Group directly |
| `rovsti_core::contact_edit::ImportPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::MAX_FIELDS` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::MembersPayload` | contacts | contacts | op | contacts.groups_summary |
| `rovsti_core::contact_edit::RelationKind` | contacts | contacts | op | contacts.draft_validate |
| `rovsti_core::contact_edit::RelationTap` | contacts | contacts | op | contacts.relation_tap |
| `rovsti_core::contact_edit::SavedPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rovsti_core::contact_edit::SelectionChange` | contacts | contacts | op | contacts.groups_change |
| `rovsti_core::contact_edit::account_label` | contacts | contacts | op | contacts.account_label |
| `rovsti_core::contact_edit::default_action` | contacts | contacts | op | contacts.field_menu |
| `rovsti_core::contact_edit::draft_from_query` | contacts | contacts | op | contacts.draft_from_query |
| `rovsti_core::contact_edit::group_join_hint` | contacts | contacts | op | contacts.groups_labels |
| `rovsti_core::contact_edit::group_subtitle` | contacts | contacts | op | contacts.groups_labels |
| `rovsti_core::contact_edit::group_title` | contacts | contacts | op | contacts.groups_title |
| `rovsti_core::contact_edit::primary_of` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::relation_tap` | contacts | contacts | op | contacts.relation_tap |
| `rovsti_core::contact_edit::retain_account_groups` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::set_primary` | contacts | contacts | op | contacts.draft_edit |
| `rovsti_core::contact_edit::web_url` | contacts | contacts | op | contacts.web_url |
| `rovsti_core::contact_find::DeepField` | contacts | contacts | op | contacts.deep_find |
| `rovsti_core::contact_find::DeepHit` | contacts | contacts | op | contacts.deep_find |
| `rovsti_core::contact_find::DeepIndex` | contacts | contacts | op | contacts.deep_index_new, contacts.deep_find |
| `rovsti_core::contact_frequent::Frequent` | contacts | contacts | op | contacts.frequent |
| `rovsti_core::contact_history::CARD_LIMIT` | contacts | contacts | op | contacts.history |
| `rovsti_core::contact_history::PULL_LIMIT` | contacts | contacts | op | contacts.history |
| `rovsti_core::contact_ics` | contacts | contacts | op | contacts.ics |
| `rovsti_core::contact_intake::Intake` | contacts | contacts | op | contacts.intake |
| `rovsti_core::contact_me` | contacts | contacts | op | contacts.me_state, contacts.me_toggle |
| `rovsti_core::contact_options::ContactOptions` | contacts | contacts | op | contacts.options |
| `rovsti_core::contact_options::PinShortcut` | contacts | contacts | op | contacts.pin_shortcut |
| `rovsti_core::contact_photo::CropView` | contacts | contacts | op | contacts.photo_crop |
| `rovsti_core::contact_photo::PhotoError` | contacts,phone | contacts | op | contacts.photo_plan |
| `rovsti_core::contact_photo::accepts` | phone | contacts | op | contacts.photo_accepts |
| `rovsti_core::contact_photo::plan_for` | contacts | contacts | op | contacts.photo_plan |
| `rovsti_core::contact_photo::rotated_size` | contacts | contacts | op | contacts.photo_plan |
| `rovsti_core::contact_quick::QuickAction` | contacts | contacts | op | contacts.quick |
| `rovsti_core::contact_recent::AddedMark` | contacts | contacts | op | contacts.recent_note_pull, contacts.recent_note_created |
| `rovsti_core::contact_recent::CLEAR_VIEWED` | contacts | contacts | op | contacts.recent_lists |
| `rovsti_core::contact_recent::RecentTab` | contacts | contacts | op | contacts.recent_lists |
| `rovsti_core::contact_recent::VIEWED_KEEP` | contacts | contacts | op | contacts.recent_viewed |
| `rovsti_core::contact_recent::note_created` | contacts | contacts | op | contacts.recent_note_created |
| `rovsti_core::contact_recent::note_pull` | contacts | contacts | op | contacts.recent_note_pull |
| `rovsti_core::contact_recent::show_entry` | contacts | contacts | op | contacts.recent_lists |
| `rovsti_core::contact_scan::Scanned` | contacts | contacts | op | contacts.scan |
| `rovsti_core::contact_select::GroupTarget` | contacts | contacts | op | contacts.groups_targets |
| `rovsti_core::contact_select::SHARE_FORMATS` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::SelectAction` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::ShareFormat` | contacts | contacts | op | contacts.select_export |
| `rovsti_core::contact_select::cap_notice` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::delete_confirm` | contacts | contacts | op | contacts.select_bar |
| `rovsti_core::contact_select::export_ids` | contacts | contacts | op | contacts.select_export |
| `rovsti_core::contact_stats::Summary` | contacts | contacts | op | contacts.stats |
| `rovsti_core::contact_trash::Trash` | contacts | contacts | op | contacts.trash_list, contacts.trash_push, contacts.trash_restore, contacts.trash_delete, contacts.trash_confirm |
| `rovsti_core::contact_trash::moved_notice` | contacts | contacts | op | contacts.trash_push |
| `rovsti_core::contacts_prefs::ContactsPrefs` | contacts | contacts | op | contacts.prefs_load, contacts.prefs_save |
| `rovsti_core::contacts_prefs::MAX_EMERGENCY` | contacts | contacts | op | contacts.me_state |
| `rovsti_core::contacts_view::CallTarget` | contacts,phone | contacts | op | contacts.card |
| `rovsti_core::contacts_view::ContactVm` | contacts,phone | contacts | op | contacts.view |
| `rovsti_core::contacts_view::NameStyle` | contacts,phone | contacts | op | contacts.view |
| `rovsti_core::contacts_view::NumberVm` | contacts,phone | contacts | op | contacts.view, contacts.card |
| `rovsti_core::contacts_view::build_rows_styled` | contacts | contacts | op | contacts.view |
| `rovsti_core::contacts_view::call_target` | phone | contacts | op | contacts.card |
| `rovsti_core::contacts_view::without` | contacts | contacts | op | contacts.view, contacts.favorites_move, contacts.scrubber |
| `rovsti_core::copy_choices::CopyChoice` | messages | shared | op | copy.header_choices |
| `rovsti_core::copy_choices::header_choices` | messages | shared | op | copy.header_choices |
| `rovsti_core::date_detect::calendar_end_ms` | messages | messages | op | date_detect.detect |
| `rovsti_core::date_detect::detect` | messages | messages | op | date_detect.detect |
| `rovsti_core::date_detect::event_title` | messages | messages | op | date_detect.detect |
| `rovsti_core::date_detect::matched_text` | messages | messages | op | date_detect.detect |
| `rovsti_core::date_jump::DateStep` | messages | messages | op | date_jump.step |
| `rovsti_core::date_jump::DayPick` | messages | messages | op | date_jump.pick |
| `rovsti_core::date_jump::date_notice` | messages | messages | op | date_jump.step |
| `rovsti_core::date_jump::date_step` | messages | messages | op | date_jump.step |
| `rovsti_core::date_jump::later_notice` | messages | messages | op | date_jump.step |
| `rovsti_core::date_jump::quick_picks` | messages | messages | op | date_jump.pick |
| `rovsti_core::date_jump::starts_later` | messages | messages | op | date_jump.step |
| `rovsti_core::dial::FieldAction` | phone | input | op | dial.field_menu |
| `rovsti_core::dial::Push` | phone | input | kotlin | :phone Dial.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::dial::TonePlan` | phone | input | op | dial.tone_plan, dial.tones_text, dial.tone_preview, dial.paste_tones_label |
| `rovsti_core::dial::ToneStep` | phone | input | op | dial.tone_plan, dial.tones_text, dial.tone_preview, dial.paste_tones_label |
| `rovsti_core::dial::from_tel_uri` | phone | input | op | dial.from_tel_uri |
| `rovsti_core::forward::ForwardBody` | messages | messages | op | forward.plan |
| `rovsti_core::forward::ForwardPlan` | messages | messages | op | forward.plan |
| `rovsti_core::forward::ForwardTo` | messages | messages | op | forward.plan, forward.words |
| `rovsti_core::forward::notice` | messages | messages | op | forward.words |
| `rovsti_core::forward::plan` | messages | messages | op | forward.plan |
| `rovsti_core::forward::target_label` | messages | messages | op | forward.words |
| `rovsti_core::gesture::Dir` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::gesture::Release` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::gesture::Tick` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::global_search::JumpStep` | messages | messages | op | global_search.jump |
| `rovsti_core::global_search::JumpTarget` | messages | messages | none | a hit is its chat_key and message_id plus at_ms; Kotlin keeps the target while a jump loads pages |
| `rovsti_core::global_search::MIN_QUERY_CHARS` | messages | messages | op | global_search.prepare |
| `rovsti_core::global_search::SearchCandidate` | messages | messages | op | global_search.search |
| `rovsti_core::global_search::SearchHit` | messages | messages | op | global_search.search |
| `rovsti_core::global_search::Source` | messages | messages | op | global_search.footer |
| `rovsti_core::global_search::WA_CHATS` | messages | messages | op | global_search.prepare |
| `rovsti_core::global_search::jump_notice` | messages | messages | op | global_search.jump |
| `rovsti_core::global_search::jump_step` | messages | messages | op | global_search.jump |
| `rovsti_core::group_tools::Kind` | contacts | contacts | op | contacts.groups_recipients |
| `rovsti_core::import_plan::number_key` | contacts | contacts | op | contacts.number_key |
| `rovsti_core::import_plan::photo_targets` | contacts | contacts | op | contacts.import_photos |
| `rovsti_core::inbox::InboxRow` | messages | messages | op | inbox.merge |
| `rovsti_core::inbox::InboxThread` | messages | messages | op | inbox.merge |
| `rovsti_core::inbox::PersonKey` | messages | messages | op | inbox.person_for_number, inbox.person_for_jid |
| `rovsti_core::inbox::merge` | messages | messages | op | inbox.merge |
| `rovsti_core::inbox_bulk::BulkAction` | messages | messages | op | inbox.bulk |
| `rovsti_core::inbox_bulk::BulkFacts` | messages | messages | op | inbox.bulk |
| `rovsti_core::inbox_filter::InboxFilter` | messages | messages | op | inbox.filter |
| `rovsti_core::inbox_filter::RowFacts` | messages | messages | op | inbox.filter |
| `rovsti_core::keypad_layout::Insets` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::KeypadInput` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::KeypadLayout` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::Rect` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::key_style` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::keypad_layout::keypad_layout` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::location_link::coords_text` | messages | messages | op | location_link.find, location_link.share |
| `rovsti_core::location_link::find_places` | messages | messages | op | location_link.find |
| `rovsti_core::location_link::geo_url` | messages | messages | op | location_link.find, location_link.share |
| `rovsti_core::lru::Lru` | contacts,messages,phone | platform | none | Kotlin uses android.util.LruCache |
| `rovsti_core::message_info::InfoChannel` | messages | messages | op | message_info.rows |
| `rovsti_core::message_info::InfoInput` | messages | messages | op | message_info.rows |
| `rovsti_core::message_info::InfoRow` | messages | messages | op | message_info.rows |
| `rovsti_core::message_info::InfoState` | messages | messages | op | message_info.rows |
| `rovsti_core::message_info::info_text` | messages | messages | op | message_info.rows |
| `rovsti_core::message_info::rows` | messages | messages | op | message_info.rows |
| `rovsti_core::message_reminder::MessageReminder` | messages | messages | op | message_reminder.make |
| `rovsti_core::message_reminder::arm_json` | messages | messages | op | message_reminder.make |
| `rovsti_core::message_reminder::fired_id` | messages | messages | op | message_reminder.list |
| `rovsti_core::message_reminder::parse_list` | messages | messages | op | message_reminder.list |
| `rovsti_core::msg_backup::BackupBody` | messages | messages | op | msg_backup.export, msg_backup.parse |
| `rovsti_core::msg_backup::BackupError` | messages | messages | op | msg_backup.parse |
| `rovsti_core::msg_backup::RestorePlan` | messages | messages | op | msg_backup.plan |
| `rovsti_core::msg_backup::Strength` | messages | messages | op | msg_backup.strength |
| `rovsti_core::msg_backup::passphrase_strength` | messages | messages | op | msg_backup.strength |
| `rovsti_core::multi_select::Selection` | contacts,messages | messages | kotlin | :messages MultiSelect.kt (the selection set is per-tap UI state; the cap, title, copy block and pill texts are multi_select.plan; Rust tests mirrored as JVM tests) |
| `rovsti_core::mute::MuteFor` | messages | messages | op | mute.choices |
| `rovsti_core::mute::header_line` | messages | messages | op | mute.status |
| `rovsti_core::mute::muted_notice` | messages | messages | op | mute.status |
| `rovsti_core::names::display` | phone | platform | op | phone.display_name |
| `rovsti_core::notify::Incoming` | messages | messages | op | notify.build |
| `rovsti_core::notify::MAX_LINES` | messages | messages | op | notify.build |
| `rovsti_core::notify::NotificationSpec` | messages | messages | op | notify.build |
| `rovsti_core::notify::build` | messages | messages | op | notify.build |
| `rovsti_core::number_format::NumberForm` | phone | shared | op | format.render |
| `rovsti_core::number_format::QR_MAX_BYTES` | contacts | shared | op | format.contact_qr |
| `rovsti_core::number_format::QrTooBig` | contacts | shared | op | format.contact_qr |
| `rovsti_core::number_format::contact_qr` | contacts,phone | shared | op | format.contact_qr |
| `rovsti_core::number_format::file_slug` | messages | shared | op | format.file_name |
| `rovsti_core::number_format::file_slug_opt` | contacts,messages,phone,stops | shared | op | format.file_name |
| `rovsti_core::number_format::infer_home_cc` | contacts,phone | shared | op | format.infer_home_cc |
| `rovsti_core::number_format::qr_file_name` | phone | shared | op | format.file_name |
| `rovsti_core::otp::detect` | messages | messages | op | otp.find |
| `rovsti_core::pending_delete::PendingDeletes` | messages | messages | op | inbox.pending_delete |
| `rovsti_core::pending_delete::notice` | messages | messages | op | inbox.pending_delete |
| `rovsti_core::perf::FrameStats` | phone,rovsti_ui | input | kotlin | :design FramePerf.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::perf::FrameSummary` | rovsti_ui | input | kotlin | :design FramePerf.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::quick_replies::ReplyError` | messages | messages | op | quick_replies.edit |
| `rovsti_core::quick_replies::insert_into` | messages | messages | op | quick_replies.insert |
| `rovsti_core::quick_replies::restore` | messages | messages | op | quick_replies.edit |
| `rovsti_core::recents::Direction` | phone | phone | op | recents.build |
| `rovsti_core::recents::ExtraCall` | phone | phone | op | recents.merge_extras |
| `rovsti_core::recents::HistoryLine` | contacts,phone | phone | op | recents.history, recents.blocked_history, recents.history_text |
| `rovsti_core::recents::LocaleTime` | contacts,messages,phone,stops | phone | op | recents.build |
| `rovsti_core::recents::RowView` | phone | phone | op | recents.build |
| `rovsti_core::recents::extra_ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rovsti_core::recents::split_refs` | phone | phone | kotlin | :phone RecentsRepository.kt (a when over the two ref kinds) |
| `rovsti_core::reminder::Reminder` | phone | messages | op | call_reminder.make, call_reminder.list |
| `rovsti_core::reminder::Slot` | messages,phone | messages | op | message_reminder.slots, call_reminder.make |
| `rovsti_core::reminder::arm_json` | phone | messages | op | call_reminder.make |
| `rovsti_core::reminder::clock` | messages | messages | op | message_reminder.make |
| `rovsti_core::reminder::parse_list` | phone | messages | op | call_reminder.list |
| `rovsti_core::scene::CallScene` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::Control` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::DisabledReason` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::Layout` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::Participants` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::scene::SceneDiff` | phone | phone | op | scene.of, scene.diff |
| `rovsti_core::schedule::FiredSms` | messages | messages | op | schedule.reconcile |
| `rovsti_core::schedule::MIN_LEAD_MS` | messages | messages | op | schedule.validate |
| `rovsti_core::schedule::ScheduleError` | messages | messages | op | schedule.validate, schedule.add |
| `rovsti_core::schedule::ScheduleQueue` | messages | messages | op | schedule.add, schedule.cancel, schedule.load, schedule.due_soon |
| `rovsti_core::schedule::ScheduleTarget` | messages | messages | op | schedule.add |
| `rovsti_core::schedule::ScheduledSms` | messages | messages | op | schedule.add |
| `rovsti_core::search::ContactIndex` | contacts,messages,phone | search | op | search.index_new, search.index_replace, search.set_call_counts, search.query, search.query_loose, search.match_number, search.match_numbers |
| `rovsti_core::search::fold` | contacts,phone,stops | search | op | search.fold |
| `rovsti_core::settings::BACKUP_KIND` | phone | platform | op | phone.backup_export, phone.backup_import |
| `rovsti_core::settings::DEFAULT_REPLIES` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::HapticKind` | rovsti_ui | phone | kotlin | :design Haptics.kt (per-tap check, main thread; Rust table mirrored as a JVM test) |
| `rovsti_core::settings::ImportPreview` | phone | platform | op | phone.backup_import |
| `rovsti_core::settings::MAX_REPLIES` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::MAX_REPLY_CHARS` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::PrefsV2` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::REPLY_COUNTER_AT` | phone | phone | op | phone.reply_choices |
| `rovsti_core::settings::SPEED_DIAL_DIGITS` | phone | phone | op | speed_dial.assign, speed_dial.free_digits |
| `rovsti_core::settings::Settings` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::SortBy` | contacts,phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::SpeedDialEntry` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rovsti_core::settings::backup_file_name` | phone | platform | op | phone.backup_export |
| `rovsti_core::settings::effective_keypad_tones` | phone | phone | op | phone.keypad_tones |
| `rovsti_core::settings::haptic_allowed` | rovsti_ui | phone | kotlin | :design Haptics.kt (per-tap check, main thread; Rust table mirrored as a JVM test) |
| `rovsti_core::settings::reply_choices` | phone | phone | op | phone.reply_choices |
| `rovsti_core::setup::ItemState` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::ItemStatus` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::OpenedThisSession` | phone | phone | op | setup.xiaomi |
| `rovsti_core::setup::Reading` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::SetupChecklist` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::SetupFacts` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::SetupItem` | phone | phone | op | setup.steps, setup.checklist |
| `rovsti_core::setup::XiaomiConfirmations` | phone | phone | op | setup.xiaomi |
| `rovsti_core::setup::can_confirm` | phone | phone | op | setup.xiaomi |
| `rovsti_core::setup::changed_title` | phone | phone | op | setup.reopened |
| `rovsti_core::setup::still_reopened` | phone | phone | op | setup.reopened |
| `rovsti_core::shared_card::CardField` | messages | messages | op | shared_card.read |
| `rovsti_core::shared_card::CardView` | messages | messages | op | shared_card.read |
| `rovsti_core::shared_card::insert_json` | messages | messages | op | shared_card.read |
| `rovsti_core::shared_card::primary_number` | messages | messages | op | shared_card.read |
| `rovsti_core::sim::DOT_MIN_CONTRAST` | phone | phone | op | sim.dot |
| `rovsti_core::sim::SimAccount` | phone | phone | op | sim.build |
| `rovsti_core::sim::SimInfo` | phone | phone | op | sim.build |
| `rovsti_core::sim::argb_rgb` | phone | phone | op | sim.dot |
| `rovsti_core::sim::clamp_dot` | phone | phone | op | sim.dot |
| `rovsti_core::small_text::SmallText` | phone | input | kotlin | :design SmallText.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::sms::DeliveryState` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms::MmsPart` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms::MmsRow` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms::SmsBox` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms::SmsMessagesPayload` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms::SmsRow` | messages | messages | op | sms.to_message |
| `rovsti_core::sms::SmsSend` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms::SmsSim` | messages | messages | none | provider payload: Kotlin reads the SIM list from SubscriptionManager; the per-thread choice is messages.prefs sim_choices |
| `rovsti_core::sms::SmsSimsPayload` | messages | messages | none | provider payload: Kotlin reads the SIM list from SubscriptionManager; the per-thread choice is messages.prefs sim_choices |
| `rovsti_core::sms::SmsStatus` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms::SmsThreadRow` | messages | messages | op | inbox.merge |
| `rovsti_core::sms::SmsThreadsPayload` | messages | messages | none | provider payload: the bridge answers a list of SmsThreadRow, which inbox.merge takes |
| `rovsti_core::sms::segments` | messages | messages | op | sms.segments |
| `rovsti_core::sms_timeline::SmsItem` | messages | messages | op | sms_timeline.build |
| `rovsti_core::sms_timeline::SmsKey` | messages | messages | op | sms.key, sms_timeline.build |
| `rovsti_core::sms_timeline::SmsTimeline` | messages | messages | op | sms_timeline.build |
| `rovsti_core::spam::RuleError` | messages | messages | op | spam.add_rule |
| `rovsti_core::spam::SpamRule` | messages | messages | op | spam.add_rule, spam.insert_rule |
| `rovsti_core::spam::SpamRules` | messages | messages | op | spam.classify, spam.remove_rule |
| `rovsti_core::spam::contains_link` | messages | messages | op | spam.contains_link |
| `rovsti_core::spam::open_links` | messages | messages | op | spam.open_links |
| `rovsti_core::speed_dial::HoldDigit` | phone | phone | op | speed_dial.on_hold, speed_dial.assign, speed_dial.unassign, speed_dial.free_digits, speed_dial.digit_for |
| `rovsti_core::speed_dial::badges` | phone | phone | op | speed_dial.badges |
| `rovsti_core::starred::LocalStar` | messages | messages | op | starred.local |
| `rovsti_core::starred::StarCandidate` | messages | messages | op | starred.rows |
| `rovsti_core::starred::StarredRow` | messages | messages | op | starred.rows |
| `rovsti_core::t9::MAX_HITS` | phone | t9 | op | t9.query |
| `rovsti_core::t9::T9Hit` | phone | t9 | op | t9.query |
| `rovsti_core::t9::T9Scratch` | phone | t9 | op | t9.query |
| `rovsti_core::text_detect::DetectAction` | messages | messages | op | text_detect.detect |
| `rovsti_core::text_edit::EditBuffer` | rovsti_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_edit::History` | rovsti_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_edit::Kind` | rovsti_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_scale::ChatTextSize` | messages | input | kotlin | :design TextScale.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::ECHO_MS` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::EDGE_MAX` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::EDGE_ZONE` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::HANDLE_HIT` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::HANDLE_SIZE` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::INSERTION_MS` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MENU_H` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MENU_ITEM_PAD` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MenuItem` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::MenuState` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::TapAction` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::drag_selection` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::edge_scroll` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::handle_visible` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::hits_caret` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::in_selection` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::keep_over_field` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::menu_items` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::menu_width` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::place_menu` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::resolve_tap` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::tap_byte` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_touch::teardrop` | rovsti_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::CARET_MARGIN` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::ML_LINE` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::ML_PAD_V` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::box_height` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::capsule_radius` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::caret_visible` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::clamp_scroll` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::clamp_scroll_y` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::locate_paragraph` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::masked_index` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::next_toggle_ms` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::paragraph_for_row` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::paragraph_starts` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::scroll_for_caret` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::scroll_y_for_caret` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::selection_rects` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::unmasked_index` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::text_view::visible_lines` | rovsti_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::thread_marks::SmsMarks` | messages | messages | op | thread_marks.edit |
| `rovsti_core::thread_marks::TOO_MANY_PINS` | messages | messages | op | thread_marks.edit |
| `rovsti_core::thread_marks::archive_notice` | messages | messages | op | thread_marks.edit |
| `rovsti_core::thread_marks::pin_notice` | messages | messages | op | thread_marks.edit |
| `rovsti_core::thread_marks::unread_notice` | messages | messages | op | thread_marks.edit |
| `rovsti_core::unread_jump::JumpState` | messages | messages | op | unread_jump.state |
| `rovsti_core::unread_jump::next_unread_label` | messages | messages | op | unread_jump.next |
| `rovsti_core::vcard::CardPhoto` | contacts | contacts | op | contacts.vcard_parse, contacts.vcard_write, contacts.import_plan |
| `rovsti_core::vcard::PhotoFormat` | contacts | contacts | op | contacts.vcard_parse, contacts.vcard_write |
| `rovsti_core::voice_player::PlayerState` | messages | messages | op | voice_player.toggle |
| `rovsti_core::voice_player::Speed` | messages | messages | op | voice_player.speeds |
| `rovsti_core::voice_player::TogglePlan` | messages | messages | op | voice_player.toggle |
| `rovsti_core::wire::AudioRoute` | phone | wa | op | audio.rows, audio.diff |
| `rovsti_core::wire::AudioState` | phone | wa | op | audio.rows, audio.diff |
| `rovsti_core::wire::BlockReason` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::BlockedNumber` | messages,phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::BlocklistPayload` | messages,phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::CallLogEntry` | contacts,phone | wa | op | recents.refresh_delta, call_log.counts |
| `rovsti_core::wire::CallLogKind` | contacts,phone | wa | op | recents.refresh_delta, call_log.counts |
| `rovsti_core::wire::CallLogPayload` | contacts,phone | wa | op | recents.refresh_delta, call_log.counts |
| `rovsti_core::wire::CallSnapshot` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::ContactsPayload` | contacts,messages,phone | wa | op | search.index_new |
| `rovsti_core::wire::DisconnectCause` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::DocumentOp` | contacts,messages,phone,stops | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::DocumentResult` | contacts,messages,phone,stops | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::EmergencyList` | messages,phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::Endpoint` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::EndpointHint` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::EndpointType` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::Envelope` | contacts,messages,phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::FavoritesPayload` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::Handoff` | messages,phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::HandoffAction` | messages | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::HapticsLevel` | phone,rovsti_ui | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::NameFormat` | contacts,phone | wa | op | search.index_new |
| `rovsti_core::wire::OpenSystemResponse` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::PendingOp` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::PhoneAccountInfo` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::PhoneAccounts` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::PhoneAccountsResponse` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::SettingsSync` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::SilencedBy` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::UiRequest` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::UiRequestKind` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::WireCall` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::WireCallState` | phone | wa | op | scene.of, scene.diff |
| `rovsti_core::wire::WireContact` | contacts,messages,phone | wa | op | search.index_new |
| `rovsti_core::wire::WireError` | contacts,messages,phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::WireNumber` | contacts,messages,phone | wa | op | search.index_new |
| `rovsti_core::wire::WriteError` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::caps` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::parse_handoff` | messages,phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::wire::props` | phone | wa | none | GPUI-era service wire between rovsti_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rovsti_core::zoom::Point` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::zoom::Size` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_core::zoom::ZoomView` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rovsti_geo::ActiveTrip` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::AlarmPlan` | stops | stops | op | stops.alarm_plan |
| `rovsti_geo::Appearance` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::Coord` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rovsti_geo::DAY_MS` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::Diameter` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rovsti_geo::DiameterError` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rovsti_geo::EARTH_RADIUS_M` | stops | stops | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_geo::Endpoint` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::Feed` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rovsti_geo::FeedReport` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rovsti_geo::Fix` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::FixIn` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::FixOut` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::FixPos` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::History` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::Measure` | stops | stops | op | stops.measure |
| `rovsti_geo::NoticePrefs` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::NoticeState` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::NoticeWhen` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::Outcome` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::OutcomeFilter` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::PRUNE_CHOICES_DAYS` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::Pattern` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rovsti_geo::Route` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rovsti_geo::SavedStop` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::SavedStopError` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::SavedStops` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::Side` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rovsti_geo::Stats` | stops | stops | op | stops.stats |
| `rovsti_geo::Stop` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rovsti_geo::StopColour` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::StopsPrefs` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::TripRecord` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::TripState` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rovsti_geo::alarm::MIN_ALARM_VOLUME_PCT` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::arrival_clock` | stops | stops | op | stops.arrival_clock, stops.format_distance |
| `rovsti_geo::backup::Backup` | stops | stops | op | stops.backup_export, stops.backup_parse |
| `rovsti_geo::coord_format::CoordFormat` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rovsti_geo::coord_format::cardinal` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rovsti_geo::coord_format::from_you_text` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rovsti_geo::coord_format::map_view_share_text` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rovsti_geo::coord_format::osm_view_url` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rovsti_geo::dest::SharedPlace` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rovsti_geo::dest::parse_location` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rovsti_geo::dest::parse_stop_share` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rovsti_geo::dest::point_qr_payload` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rovsti_geo::dest::stop_share_text` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rovsti_geo::history::format_duration` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::history_io::export_file_name` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::history_io::share_text` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::history_io::to_csv` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::history_io::to_gpx` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::measure::MAX_POINTS` | stops | stops | op | stops.measure |
| `rovsti_geo::next_volume` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::notice::DEFAULT_TEMPLATE` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::notice::render` | stops | stops | op | stops.notice_render, stops.notice_validate |
| `rovsti_geo::notice::validate` | stops | stops | op | stops.notice_render, stops.notice_validate |
| `rovsti_geo::places_io::MergePlan` | stops | stops | op | stops.places_export, stops.places_import |
| `rovsti_geo::poll::MAX_POLL` | stops | stops | op | stops.poll |
| `rovsti_geo::poll::MIN_POLL` | stops | stops | op | stops.poll |
| `rovsti_geo::poll::retry_after` | stops | stops | op | stops.poll |
| `rovsti_geo::prefs::AlarmSound` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rovsti_geo::prune_label` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::read_zip` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rovsti_geo::saved_stops::FORMAT_VERSION` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::saved_stops::NEAREST_CHIP_M` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::saved_stops::SavedSort` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::saved_stops::load_saved_stops` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::saved_stops::suggest_name` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::share_text` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rovsti_geo::shortcut_spec` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rovsti_geo::status_share_text` | stops | stops | op | stops.pick_labels, stops.trip_status_share |
| `rovsti_geo::tiles::AREA_MIN_ZOOM` | stops | tiles | op | tiles.offline_start |
| `rovsti_geo::tiles::AREA_TILE_BUDGET` | stops | tiles | op | tiles.offline_start |
| `rovsti_geo::tiles::ATTRIBUTION` | stops | tiles | op | stops.prefs_choices |
| `rovsti_geo::tiles::AreaDownload` | stops | tiles | op | tiles.offline_start, tiles.offline_cancel |
| `rovsti_geo::tiles::AreaPlan` | stops | tiles | op | tiles.offline_start |
| `rovsti_geo::tiles::ClassGeometry` | stops | tiles | op | tiles.encode_mvt |
| `rovsti_geo::tiles::DEFAULT_TILE_URL` | stops | tiles | op | stops.prefs_choices, tiles.open |
| `rovsti_geo::tiles::DrawClass` | stops | tiles | op | tiles.encode_mvt |
| `rovsti_geo::tiles::FetchQueue` | stops | tiles | kotlin | :stops TileScheduler.kt (dedupes view requests; every `tile` call is one blocking Rust read, offline downloads use four Rust workers) |
| `rovsti_geo::tiles::LruIndex` | stops | tiles | op | tiles.open, tiles.usage, tiles.set_cap |
| `rovsti_geo::tiles::MAX_TILE_ZOOM` | stops | tiles | op | tiles.offline_start |
| `rovsti_geo::tiles::MapStyle` | stops | tiles | op | tiles.encode_mvt |
| `rovsti_geo::tiles::Priority` | stops | tiles | kotlin | :stops TileScheduler.kt (view requests before prefetch) |
| `rovsti_geo::tiles::Rgba8` | stops | tiles | op | tiles.encode_mvt |
| `rovsti_geo::tiles::Shape` | stops | tiles | op | tiles.encode_mvt |
| `rovsti_geo::tiles::TileCover` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_geo::tiles::TileGeometry` | stops | tiles | op | tiles.encode_mvt |
| `rovsti_geo::tiles::TileId` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_geo::tiles::TileLayer` | stops | tiles | kotlin | :stops MapTileLayer.kt (per-frame transform of the RVT1 buffer to the canvas) |
| `rovsti_geo::tiles::TileSource` | stops | tiles | op | tiles.open, tiles.test_server |
| `rovsti_geo::tiles::TileTransform` | stops | tiles | kotlin | :stops MapTileLayer.kt (tile units to screen, same corner maths as view.project) |
| `rovsti_geo::tiles::TileUrlError` | stops | tiles | op | tiles.open, tiles.test_server |
| `rovsti_geo::tiles::build_layer` | stops | tiles | kotlin | :stops MapTileLayer.kt (clip and merge per frame; vertex budget by class) |
| `rovsti_geo::tiles::display_zoom` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_geo::tiles::estimate_bytes` | stops | tiles | op | tiles.offline_start |
| `rovsti_geo::tiles::frac_to_coord` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_geo::tiles::line_width` | stops | tiles | kotlin | :stops MapTileLayer.kt (`w14 * 2^clamp(zoom - 14, -3, 2)`, at least 0.5 px; width14 is in the buffer) |
| `rovsti_geo::tiles::stand_in` | stops | tiles | op | tiles.stand_in |
| `rovsti_geo::tiles::tiles_for_area` | stops | tiles | op | tiles.offline_start |
| `rovsti_geo::tiles::tiles_for_circle` | stops | tiles | op | tiles.offline_start |
| `rovsti_geo::tiles::tiles_for_view` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_geo::usage_text` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rovsti_geo::view::FollowMode` | stops | stops | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_geo::view::Viewport` | stops | stops | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rovsti_tiles::DEFAULT_CAP_BYTES` | stops | tiles | op | tiles.open |
| `rovsti_tiles::DiskCache` | stops | tiles | op | tiles.open, tiles.usage, tiles.set_cap |
| `rovsti_tiles::TileClient` | stops | tiles | op | tiles.open, tiles.test_server |
| `rovsti_tiles::TileError` | stops | tiles | op | tiles.test_server |
| `rovsti_tiles::clear_all` | stops | tiles | op | tiles.clear_all |
| `rovsti_tiles::clear_except` | stops | tiles | op | tiles.clear_except |
| `rovsti_tiles::disk_usage` | stops | tiles | op | tiles.usage |
| `rovsti_tiles::load` | stops | tiles | op | tiles.open |
| `rovsti_tiles::usage_by_source` | stops | tiles | op | tiles.usage_by_source |
| `rovsti_core::phone_number::PhoneNumber::matches` | - | shared | op | phone_number.same |
| `rovsti_core::calling_code::COUNTRIES` | - | shared | op | calling_code.list |
| `rovsti_core::number_format::available` | - | shared | op | format.available |
| `rovsti_core::number_format::copy_forms` | - | shared | op | format.copy_forms |
| `rovsti_core::number_format::copied_notice` | - | shared | op | format.copied_notice |
| `rovsti_core::number_format::render` | - | shared | op | format.render |
| `rovsti_core::calling_code::country_of_cc` | - | shared | op | calling_code.of_cc |
| `rovsti_core::calling_code::country_of` | - | shared | op | calling_code.country_of |
| `rovsti_core::dial::normalise_paste` | phone | input | op | dial.normalise_paste |
| `rovsti_core::dial::validate` | phone | input | op | dial.validate |
| `rovsti_core::dial::extract_numbers` | phone | input | op | dial.extract_numbers |
| `rovsti_core::dial::paste_plan` | phone | input | op | dial.paste_plan |
| `rovsti_core::dial::redial_target` | phone | input | op | dial.redial_target |
| `rovsti_core::dial::is_emergency` | phone | input | op | dial.is_emergency |
| `rovsti_core::blocklist::parse_many` | phone | phone | op | blocklist.parse_many |
| `rovsti_core::blocklist::parse_file` | phone | phone | op | blocklist.parse_file |
| `rovsti_core::blocklist::numbers_line` | phone | phone | op | blocklist.lines |
| `rovsti_core::blocklist::call_line` | phone | phone | op | blocklist.call_line |
| `rovsti_core::recents::refresh_delta` | phone | phone | op | recents.refresh_delta |
| `rovsti_core::recents::section_starts` | phone | phone | op | recents.section_starts |
| `rovsti_core::inbox::person_for_number` | messages | messages | op | inbox.person_for_number |
| `rovsti_core::inbox::person_for_jid` | messages | messages | op | inbox.person_for_jid |
| `rovsti_core::inbox::preferred_channel` | messages | messages | op | inbox.preferred_channel |
| `rovsti_core::sms_timeline::SmsKey::provider_id` | messages | messages | op | sms.key |
| `rovsti_core::mms_pdu::decode_notification_ind` | messages | messages | op | mms_pdu.parse |
| `rovsti_core::mms_pdu::decode_retrieve_conf` | messages | messages | op | mms_pdu.parse |
| `rovsti_core::mms_pdu::encode_send_req` | messages | messages | op | mms_pdu.build_send_req |
| `rovsti_core::mms_pdu::encode_notify_resp` | messages | messages | op | mms_pdu.build_notify_resp |
| `rovsti_core::spam::classify` | messages | messages | op | spam.classify |
| `rovsti_core::spam::links` | messages | messages | op | spam.links |
| `rovsti_core::schedule::validate` | messages | messages | op | schedule.validate |
| `rovsti_core::schedule::quick_times` | messages | messages | op | schedule.quick_times |
| `rovsti_core::msg_backup::seal` | messages | messages | op | msg_backup.export |
| `rovsti_core::msg_backup::open` | messages | messages | op | msg_backup.parse |
| `rovsti_core::msg_backup::restore_plan` | messages | messages | op | msg_backup.plan |
| `rovsti_core::multi_select::{title, copy_block, star_all, copied_notice, starred_notice, deleted_notice, cap_notice}` | messages | messages | op | multi_select.plan |
| `rovsti_core::shared_card::is_vcard_mime` | messages | messages | op | shared_card.mime |
| `rovsti_core::unread_jump::{unread_divider, divider_label, newly_arrived}` | messages | messages | op | unread_jump.divider |
| `rovsti_core::voice_player::{progress, seek_target, time_label}` | messages | messages | op | voice_player.view |
