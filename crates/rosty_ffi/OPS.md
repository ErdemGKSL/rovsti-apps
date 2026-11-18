# Op inventory (E2 R2)

Every public item of `rosty_core`, `rosty_geo`, `rosty_tiles` and `rosty_app_core` that the deprecated GPUI apps call
(`apps/*/src`, `crates/rosty_ui/src`, `crates/rosty_wa_ui/src`), and what becomes of it in the Kotlin apps. Generated once from
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
`rosty_core::contact_edit::ContactDraft` (empty optional lists left out). The editor's per-keystroke state, the select-mode set and
the sheet state of the screens are Kotlin (E4); the ops here run per event.

Messages core (E2.11a): `inbox.merge`, `inbox.person_for_number` and `inbox.person_for_jid` take the handle of a contact index
(`search.index_new`) as `contacts`; a released or foreign handle answers `not_found`. Every other `inbox.*`, `sms.*`,
`sms_timeline.*`, `mms_pdu.*`, `otp.*`, `spam.*`, `schedule.*`, `notify.*` and `msg_backup.*` op is stateless. Choices worth knowing:

- `sms_timeline.build` replays an ordered `events` list (`page`, `pending`, `status`, `retry`, `discard`, `remove`, `adopt_thread`;
  the `kind` field names the event) on a fresh `SmsTimeline` and answers the ascending items plus one result per event (a retry's
  `SmsSend`, a discarded text). No handle: a thread's events are a few hundred small values and the Kotlin ViewModel already keeps
  them, so a replay costs less than keeping native state in step with it.
- `schedule.*` is stateless over a `queue_json` string (the content of `scheduled.json`), not a `ScheduleQueue` handle. Nothing in
  `rosty_app_core` persists the queue, the alarm registration is Kotlin (`AlarmManager`), and the queue is capped at 200 small
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

Cards (X.2): share cards drawn by `rosty_app_core::cards` (Takumi, bundled Inter, no system fonts). `cards.templates {}` answers
`{templates: [{id, animatable, texts, required, still: {width, height}, animated}], palettes}`. `cards.render {spec, out}` takes a
`spec` (`template`: `birthday` | `contact` | `new_number`, `palette`: `dawn` | `lagoon` | `meadow` | `dusk` | `ink`, `animated`,
`texts` by key, `photo` (an absolute file path or null), `initials`, `qr`) and an absolute `out` path, writes a 720 x 900 PNG, or a
540 x 675 GIF that plays once (no NETSCAPE block) when `animated` and the template moves, and answers `{path, width, height, bytes,
frames}`. Every word comes from the app's string resources; Rust only lays out, and every text block is fitted inside the glass
panel (wrap on at most 2-3 lines, shrink to a floor, then "…"). A missing or unreadable photo draws the initials. Errors are codes in
`message`, never sentences: `text_missing` (`detail.key`), `out_invalid`, `cancelled`, `render_failed` (all `invalid`) and
`out_unwritable` (`io`). `cards.cancel {out}` stops the running render of `out` between frames and answers `{cancelled}`; the
cancelled render leaves no file. Kotlin: `NativeCardsOps` in `:core-bridge` (Contacts' Card Studio).

Stops (E2.12): `stops.trip_start` / `stops.trip_restore` answer `{handle, ...}` (a `TripView` with the whole persisted trip as `snapshot`),
the handle of an `ActiveTrip`; `stops.trip_{state, fix, notice_result, snooze, timeout, dismiss, status_share, end}` send it back and
`RustNative.release` frees it (Kotlin: `ActiveTripHandle`). Every other `stops.*` and `view.*` op is stateless. Choices worth knowing:

- Persistence: a trip op that names `dir` also writes `trip.json` under `rosty_app_core::stops::store::lock()`, the lock the
  headless kinds (`trip_fix`, `trip_alarm`, `trip_end`) take on the service thread; `stops.store_trip_{load, save, end, clear}` hold it for
  their whole read-modify-write. The headless fixtures are untouched. `stops.store_read` / `stops.store_write` cover `saved.json`,
  `history.json` and `draft.json` (the file's shape is checked on write); `stops.prefs_load` / `stops.prefs_save` cover `stops-prefs.json`.
- Errors that are sentences for the user (`DiameterError`, `SavedStopError`, `DestError`, `BackupError`, `ImportError`, `TemplateError`,
  `TileUrlError`) answer `invalid` with the sentence as `message` and a stable `detail.reason` (`too_short`, `same_point`,
  `duplicate` + `id`, `no_coordinates`, `newer`, ...).
- `stops.pick_replay` replays gesture events (`tap`, `place`, `drag_start`, `drag_to`, `drag_end`, `swap`, ...) on a fresh `Picking`, the
  same stateless shape as `sms_timeline.build`; the pin following the finger frame by frame stays Kotlin.
- `stops.saved_edit`, `stops.history_edit` and `stops.prefs_edit` apply one named edit to the value in the request and answer the new
  value (Kotlin saves it); the rules (name length, duplicates, the 200-stop cap, favourites first, sound-or-vibrate) stay in Rust.
- `stops.store_trip_clear {dir?}` deletes `trip.json` under the trip lock and records nothing (`{removed}`; clearing again answers
  `false`): for a start Android refused, where `store_trip_end` would put a trip that never ran into the history.
- `stops.store_gtfs_remove {dir?}` deletes the imported feed `gtfs.json` (Settings > Transit data > Remove; `{removed}`, removing again
  answers `false`).
- The trip status (`FixOut`: the headless `trip_fix` answer and `out` of `stops.trip_fix`) carries `eta_min`, the whole minutes of
  "about 14 min" in its `text` (null without a speed), so the live card can ask `stops.arrival_clock` (S41).
- The trip's cadence (E7 R4, AD-SA10): `FixOut.next_fix_ms` is a rung of `rosty_geo::cadence::LADDER_S`, planned from the
  speed history kept in `trip.json` (`speed`, absent when empty) so the next fix always lands before the user can reach the
  line, never more often than the old curve (`stops.poll`). `FixOut` adds `speed_mps` (the measured speed, null without one),
  `assumed_mps` and `cadence` (`cold` | `bound` | `doppler` | `average` | `floor`; both null when not tracking) and `late`
  (the fix came after the previous `retry_ms`). `FixIn` takes an optional `elapsed_ms` (the fix's monotonic clock), the
  history's clock when present. The headless kind `trip_no_fix {dir, now_ms, elapsed_ms?}` is the watchdog's call when no
  provider found a fix: a `FixOut` without `at` (`late` true, `text` empty before any usable fix) whose interval dead-reckons
  from the last usable fix; it never rings. `stops.cadence {line_m?, accuracy_m?, notice_m?, assumed_mps?, est_mps?, prev_ms?,
  prev_high?}` answers one step of the plan `{interval_ms, retry_ms, high_accuracy, assumed_mps, cold_ms}` (`cold_ms` is the
  old curve); the trip does not call it.
- Saved stop notes (S44): `SavedStop.note` (absent from `saved.json` when empty, so the file stays version 2 both ways; one trimmed
  line of at most 200 characters). `stops.saved_edit {kind: "set_note", id, note}` sets it, a blank text clears it, a longer one is
  `invalid` with `reason: "note_too_long"`. The note is a line under the stop in `stops.saved_list`'s share text, the last line of
  `stop_text` of `stops.dest_share {note}`, kept by the JSON export/import (`Incoming.note`, cut to the cap) and by the backup; GPX
  has none.
- Language (`locale`): every sentence Stops shows from Rust exists in English and Turkish (`rosty_geo::Lang`). The ops that write
  one take an optional `locale` (any tag a phone writes: `tr`, `tr-TR`, `tr_TR`; absent, `null` or unknown is English, so old
  requests answer as before): `stops.pick_{replay, labels}`, `stops.crossing_{validate, read}`, `stops.saved_{edit, list}`,
  `stops.saved_shortcut`, `stops.places_import`, `stops.history_{list, prune_plan, usage, text}`, `stops.stats`, `stops.prefs_{edit,
  choices}`, `stops.notice_{render, validate}`, `stops.alarm_plan`, `stops.gtfs_{lines, summary}`, `stops.coord_format`,
  `stops.measure`, `stops.backup_{export, parse}`, `stops.format_distance`, `stops.dest_parse`. Turkish writes "1,2 km", "1.234 durak",
  K/KD/D/GD/G/GB/B/KB and keeps nouns singular after a number; no suffix follows a name the user typed. The fixture case `tr` (and
  `tr_*` for errors) of each op pins the Turkish answer.
- The trip keeps its language: `stops.trip_start {locale}` stores it in `trip.json` (`lang`, absent for English), and it is the
  language of the texts the trip writes on its own: the status (`FixOut.title`, `FixOut.text`: the service's notification) and the
  "a few" of an arriving-soon message without an ETA. A message the user never rewrote (still a default of either language) goes
  out as the default of the trip's language (`stops.prefs_choices` lists every language's default as `default_templates`, so the
  settings page shows such a message in the phone's language too). `stops.trip_{restore, fix, status_share, end}` and the headless `trip_fix` take an
  optional `locale` that replaces the stored one (the phone's language changed during the trip); without it the stored one holds.
  A point without a name is stored as "Point A" / "A noktası"; both read as "no name" in either language.
- `stops.notice_chats {chats: [{jid, group?, contact?, verified?, push?, chat_name?}], locale?}` names the chats the arriving-soon
  message can go to with the shared rule of `rosty_core::wa_identity` (contact, verified, push, the chat's own name, the number,
  else "WhatsApp user"), in the order given; `number` is the row's second line (null for a group and for a bare number).
- `stops.gtfs_import {zip, now_ms, save?, dir?}` reads the zip at a path (a tiny feed lives in `tests/data/gtfs-tiny.zip`; fixture requests
  write it as `$DATA/gtfs-tiny.zip`) and answers the `FeedReport`; `save` writes `gtfs.json`, which `stops.gtfs_search` and
  `stops.saved_suggest_name` read. `stops.saved_suggest_name {a, b, feed?, dir?, names?, locale?}` answers a name that is never empty, fits the name limit and is none of `names` (a GTFS stop near the midpoint, an end's own name, else "Stop 3" / "Durak 3"; a taken name gets the next number). `stops.measure` takes the point list (Kotlin keeps it; undo drops the last point).
- `stops.gtfs_view {bbox: {south, west, north, east}, cap, pattern?, dir?}` answers what the map draws and snaps to from the saved
  feed: `{has_feed, total, capped, stops: [{index, id, name, coord}], line}`. The stops are those inside the box (`west > east` crosses
  the antimeridian), at most `cap`, the ones nearest the middle of the box when there are more (`total` counts all of them), in feed
  order; `line` is the coordinates of the stops of `pattern` in travel order, null without one or for an unknown pattern. It replaces
  the GPUI `stops_in_view` and pattern lookup of `apps/stops/src/map.rs` (`rosty_geo::Feed::box_hits`, `pattern_line`).
- `stops.gtfs_lines {stop? | stop_id?, limit?, dir?}` answers the stop sheet's line list: `{has_feed, stop, lines: [{pattern, name,
  headsign, before, title, earlier}], max_lines}` (`rosty_geo::transit::lines_through`: one entry per line and headsign, the variant
  with the most stops before this one, numbers before names; `title` is "Line 34 to Kadıköy", `earlier` "3 stops earlier on this
  line"). `pattern` goes on the picked point as `Destination.pattern`; `stops.gtfs_view {pattern}` then answers its line. GTFS has no
  alarm rule (AD-ST6): the alarm is the crossing of the circle on AB, so `stops.trip_start` takes nothing from here.
- `stops.gtfs_summary {now_ms, tz_offset_s?, skipped_rows?, dir?}` answers the Settings row of the imported feed: `{has_feed, summary,
  imported, imported_ms, stops, routes, patterns}` ("1 234 stops, 87 lines", "Imported 5 Nov, 1 200 unusable rows skipped";
  `skipped_rows` is `report.skipped_rows` of the import just done).
- `view.*` (`project`, `step`, `tiles`, `frac_to_coord`, `follow`) wrap `rosty_geo::view` and the slippy maths of `rosty_geo::tiles`.
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
- `tiles.offline_plan {bbox | circle, zooms?}` (no handle) answers `{total, top_zoom, capped, estimate_bytes}` for the same area without
  downloading anything: the estimate Settings shows before the download (S10). It plans exactly as `tiles.offline_start`.
- `tiles.offline_start {handle, bbox | circle, zooms?}` plans the tiles (`bbox {south, west, north, east}` with `tiles_for_area` and the 2 500-tile
  budget, or `circle {center, radius_m, margin_m?, budget?}` with `tiles_for_circle` for a trip), answers `{handle, total, top_zoom, capped,
  estimate_bytes}` and downloads on a background thread with four workers. Progress arrives as `tiles.offline` events `{job, done, saved, failed,
  total, fraction, text, finished, cancelled}` (at most every 100 ms, and always the last one with `finished: true`). `tiles.offline_cancel {job}`
  stops the queue (the downloads in flight finish and stay cached); releasing the job handle cancels too.
- `tiles.encode_mvt {mvt, z, x, y, flags}` is the pure half of `tile` (no cache, no network); its fixtures hold the real `.mvt` of
  `crates/rosty_geo/src/tiles/testdata` and the buffer it becomes, which the Kotlin `TileBuffer` test decodes.

Logs (people sync v2, S2): `core.logs_drain {after_seq, max?}` answers `{entries: [{seq, time_ms, level, target, text}], dropped,
last_seq}`: the Rust log lines after `after_seq` (oldest first, at most `max`, 500 at most), already masked by
`rosty_core::redact::mask`. `rosty*` targets are kept from `debug`, every other target from `warn`; the ring holds 2 000 lines.
`dropped` counts lines after `after_seq` that were lost (pushed out, or dropped while the ring was busy); pass `last_seq` next time.

WhatsApp (E2.14): `wa.open {url?, key?, cache_dir?}` answers `{handle}`, a `rosty_wa_client::Gateway`. While the vault is unlocked (`vault.*` below) the connect key and the URL come from the vault: a `key` is refused (`invalid`, `detail.code` `vault_unlocked`), a `url` must be the vault's (`other_gateway`), and locking releases the gateway; while it is locked `url` is required and `key` optional (the pre-vault path, kept until the Kotlin apps move to the vault); every network op is `wa.<verb>` with `{handle, ...flat fields of the
`rosty_wa_proto` request}` and answers the proto type (field names are the gateway's). The client call runs outside the registry lock. Errors: `network`, `auth` (401 and 403),
`invalid` with the gateway's own sentence (`detail.code` is its error code; a `rate_limited` one may add `detail.retry_after_ms` and
`detail.source` = `whatsapp` | `gateway`) and `parse`. Bytes (`wa.media`, `wa.thumbnail`, `wa.status_media`, `wa.status_thumbnail`, `wa.picture`)
answer `{size, data_b64}` up to 1 MiB, else `{size, path}` of a file in the handle's cache directory (Kotlin deletes it); a gateway opened from the unlocked vault answers every size inline, so nothing it downloads touches the disk. `wa.send_media(_with)` takes `data_b64` or a `path`. `wa.send_media_with` `options` (E6-WA R3, all optional): `quoted_id`, `file_name`, `seconds`, `voice_note`, `thumbnail_b64` (JPEG, at most 64 KiB decoded; photo, video, document), `width`, `height` (photo, video, sticker), `waveform_b64` (exactly 64 bytes, each 0 to 100; audio), `animated` (a video sent as a GIF, an animated sticker), `view_once` (photo, video, audio only). A bad preview or waveform, or view once on a document or sticker, is `invalid` (`detail.code` `bad_request`) before anything is uploaded; an option the kind has no field for is ignored. With one of the newer options set the client asks `POST /v1/messages/media/options` and puts the preview in front of the file (`thumbnail_len`); a gateway from before them answers 404 there, and the file is then sent the old way without them, except a view-once send, which fails (`invalid`, `detail.code` `not_found`). `wa.chat_state` `state` is `typing`, `recording` or `paused`.
`wa.events_start {handle, instance, after?}` answers a stream handle and emits every socket message as event `wa.event` `{instance, event}` with `event.kind` one of `connected`,
`event` (`seq`, `at_ms`, `event` = the gateway event, tagged `type`; since E6-WA R2 also `hello {epoch}` as the first frame of every socket, `account_changed {old_pn?, new_pn}`, `resync {epoch?}`, `history_synced {chats, progress, sync_type?, messages, oldest_ms?}`, and `state` and `wa.status` carry `epoch?`, `account_epoch?`, `history?: {state, progress, chats, messages, oldest_ms}`), `disconnected {reason}`, `unauthorized`; `wa.events_reconnect_now`, `RustNative.release` stops it. `wa.call_audio_open
{handle, call_id}` answers an audio handle for `RustNative.audioPush` / `audioPull` (push takes microphone frames of exactly 1 920 bytes and drops any other size; the socket asks for 20 ms speaker frames (`?spk_ms=20`), and pull waits up to 20 ms and answers the frame's bytes: 640 from a gateway that sends 20 ms frames, 1 920 from an older one; 0 when nothing came, -1 when the call ended). Fixtures run against a loopback stub that answers `crates/rosty_ffi/tests/data/wa/<stem>.json` (`tests/support/mod.rs`); each file also states the request
the client must have sent. Pure: `wa.push_parse` (FCM wake), `wa.link_read` / `wa.link_write` (`whatsapp-link.json`), `wa.avatars_plan` (picture file names and the disk prune plan; the
`AvatarBook` fetch policy is ported to Kotlin with the Rust unit tests in `:wa`, E3), `wa.names`, `wa.target` (contact -> chat/number target and the JIDs to look up), `wa.settings_apply {model?, link?, app, now_ms, input?}` (the stateless WhatsApp settings state machine: answers `{model, effects, view}`, a render-ready page; the caller keeps `model` as JSON; the gateway URL and `rwk_` key checks run inside it).
People cache (X.1): `people.upsert {dir?, now_ms, people:[{jid, number?, lid?, push_name?, verified_name?, on_whatsapp?, picture_id?, picture_removed?, picture_file?}]}` writes `people.json` (a changed `picture_id` marks the picture stale and keeps the old file showing; `picture_removed` drops the file; a LID and its number join into one person under the phone JID) and answers `{changed, stale, drop_files}`; `people.lookup {dir?, key, home_cc?}` (a number in any spelling, phone JID, LID JID or bare LID; a number is read by the one number rule, `caller_id::e164_of`, so a national spelling needs the home calling code `home_cc` and `people.upsert` takes `home_cc?` for its `number`s too; offline, a missing or damaged file reads as empty) answers `{person: row|null}`; `people.plan {dir?, now_ms, files, max_bytes}` answers `{refresh:[{jid, picture_id}], remove:[path]}` (orphans of a changed picture, then the oldest over the cap, by the `wa.avatars_plan` rules); `people.snapshot {dir?}` answers `{people: rows}` sorted by name for the Contacts provider.

People sync v2 (`rosty_app_core::people_sync`, plan `_bmad-output/plans/people-sync-v2.md`): `people.sync_begin
{dir?, now_ms, instance_id, gateway_url, numbers, start: resume|restart|auto|live_pictures, slice_ms?}` opens a slice and
answers `{handle?, refused?: {kind: not_before, until_ms} | {kind: nothing_to_resume}, status}`; `people.sync_next
{handle, now_ms, rand}` answers one step (`{kind: lookup|profiles, jids}`, `{kind: plan_pictures}`, `{kind: picture,
jid, known_id?}`, `{kind: sleep, ms, why: pace|rate_limit}`, `{kind: stop, stopped: {outcome: done|waiting|interrupted|
failed, ...}}`); `people.sync_report {handle, now_ms, rand, report}` commits the answer (`lookup`, `profiles`, `files`,
`picture`, `rate_limited`, `error`, `cancelled`; `people.json` first, `people-sync.json` second) and answers `{changed,
drop_files, stage, progress}`; `people.sync_stop {handle?|dir?, now_ms, reason}` ends the slice and releases the handle
(without a handle it interrupts the stored run: unlink); `people.sync_status {dir?, now_ms}` answers `{run?, last?,
not_before_ms?}` for the settings row. The first `people.sync_*` call for a directory in a process turns a stored
`running` run into `interrupted(process_death)`.

People sync v3 (plan `_bmad-output/kotlin-ui/plans/people-sync-v3.md`), the job mode: `people.sync_begin {..., jobs: true}`
(absent or false: the v2 steps only) makes a new run use the gateway's people job. Its steps are `{kind: job_start, mode:
auto|full, entries: [{n, h?, p?}]}` -> `wa.people_sync_start`, `{kind: job_watch, job_id, rev, wait_ms}` ->
`wa.people_sync_status`, `{kind: job_fetch, job_id, after, max_bytes}` -> `wa.people_sync_bundle` and `{kind: job_done,
job_id}` -> `wa.people_sync_cancel`, before which `plan_pictures` lists the picture files (they decide `p`). The reports:
`{kind: job_started, status}`, `{kind: job_unsupported}` (the run goes on in the v2 steps; asked again after 24 h), `{kind:
job, status}`, `{kind: job_gone}`, `{kind: bundle, job_id, records: [{seq, number, hash, on_whatsapp, jid?, lid?,
push_name?, verified_name?, picture_id?, picture_missing?, picture?: {kind: stored, file} | {kind: failed}}], last_seq,
more, bytes}` and `{kind: job_closed}`. Stage `server` / `download`, interrupt `server_working` (the slice ended while the
gateway works: continue at the job's `eta_ms`); `run.job`, `committed.job` = `{job_id, state, phase, done, total, numbers,
wait_until_ms?, wait_source?, eta_ms?, records?, pictures?, bytes?, mine?}`; `last.changed?`, `last.bytes?`, `status.
attempt_ms?`. A bundle part commits the book once and only when something changed; an unchanged run writes only
`people-sync.json`.

The gateway's people job: `wa.people_sync_start {handle, mode, entries}` answers `{supported: true, status}` or
`{supported: false}` (a gateway without the job routes: the router's bare 404); `wa.people_sync_status {handle, job_id,
rev?, wait_ms?}` answers `{status}` (a long-poll of at most 25 s) or `{gone: true}`; `wa.people_sync_bundle {handle,
job_id, after, max_bytes?}` streams one part, writes each picture to `<cache_dir>/people-bundle/<job_id>-<seq>.bin` and
answers `{gone?, records: [{seq, number, hash, on_whatsapp, jid?, lid?, push_name?, verified_name?, picture_id?,
picture_missing?, picture_path?}], last_seq, more, bytes}` (a cut part is `network`; its files are deleted);
`wa.people_sync_cancel {handle, job_id}` answers `{}` (a gone job included).
Memory-only store and lazy sync (`plans/wa-vault.md` 13.1, 14): `wa_sync.open {instance_id, memory: true, lazy?, vault_cache?}`
is how Messages opens the store since the vault. `lazy` makes the plan and a refreshed chat list owe only the first page of the chat
on screen (`wa_sync.focus {handle, chat?}` answers `{handle}`), makes a page never name the next one, and leaves other gaps open
until their chat is opened: `wa_sync.open_page {handle, chat, limit?}` answers `{fetch}` (the gap's next page, or the newest page of a
chat not paged in this session, else `null`); older history stays `wa_sync.older`, one page per scroll. `vault_cache` starts the
identity map and its catch-up cursor from the DK-encrypted `wa-vault-cache.bin` when the vault is unlocked for this instance; the
answer adds `lazy: true` and `cache` (`loaded`, `empty`, `locked`). `wa_sync.cache_save {handle}` writes the map and the cursor
back (pictures stay) and answers `{handle, persons}`; `locked` (`not_found`) while the vault is locked or holds another instance.
WhatsApp offline store (E6.4s, `rosty_app_core::wa_sync`): `wa_sync.open {instance_id, dir?, memory?}` opens the SQLite store of
one linked instance (`wa-sync-<instance>.db` in the data directory; another instance is another file) and answers `{handle,
instance_id, schema_version, last_seq, needs_resync}`; a second open of the same file in one process shares the store, and
`wa_sync.close {handle}` or `RustNative.release` frees a handle. No op touches the network: Kotlin makes the `wa.*` calls and hands the
answers in. The ops that write repeat `handle` in their answer. Sync: `wa_sync.plan {handle, limit?}` answers `{after_seq,
refresh_chats, fetch: [{chat, before, limit}]}` (what to subscribe after, whether `GET /v1/chats` is owed, the next page of every open
gap); `wa_sync.apply_events {handle, events: [{seq, at_ms, event}]}` applies gateway events in order (stale `seq` skipped, a `resync`
resets it) and answers `{last_seq, applied, resync, chats, reconciled, account_changed?, delete_files?}`; `wa_sync.store_chats {handle, now_ms, chats, limit?,
refresh_recent_ms?}` stores the chat list and opens a gap for each chat that missed messages (`{chats, fetch}`); `wa_sync.store_page
{handle, now_ms, chat, before?, limit, messages}` stores one `GET .../messages` page (`{stored, new, next?, reached_start,
reconciled}`; `next` is the following page of the gap); `wa_sync.older {handle, chat, limit?}` answers the page before the oldest
stored message (`{fetch}` or `null` at the start). Reads: `wa_sync.chats {handle, include_archived?}`, `wa_sync.thread {handle, chat,
limit, before?: {timestamp_ms, id}}` (newest first; the first page starts with the sends still in the outbox; `state` is `received`,
`sent`, `delivered`, `read`, `played`, `waiting`, `sending` or `failed`; a deleted row has `deleted`, `deleted_at`, `deleted_by` and
still its text, quote and media; `edit_history` holds earlier texts; `pending` the queued edits, deletes and reactions of the row),
`wa_sync.message {handle, chat, id}`, `wa_sync.starred {handle, limit, before?: {timestamp_ms, chat, id}}` (the starred messages of
every chat, newest first, as thread rows: `{items, next?}`; a star still in the outbox counts; deleted messages, cleared messages and
chats deleted on another device are left out). Outbox: `wa_sync.outbox_enqueue {handle, now_ms, client_id, chat, action}` (`action.kind`:
`send_text`, `send_media`, `edit`, `revoke`, `react`, `mark_read`, `star {message_id, starred}`; the same `client_id` twice is one
row; a star sets the stored flag at once, holds it against gateway pages until it went out, and replaces the stars of the same message
that have not gone out), `wa_sync.outbox_due {handle,
now_ms, network_back?}` (at most one row per chat, the oldest; marks them `sending`; `held` counts sends kept back until the sync is
done; `next_wake_ms`), `wa_sync.outbox_settle {handle, now_ms, client_id, outcome}` (`outcome.kind`: `sent {message_id?,
timestamp_ms?}` or `error {error: network|gateway|rejected, message, retry_after_ms?}`; answers `state`: `done`, `waiting` or `failed`; a rejected `star` is undone and dropped, it leaves no failed row),
`wa_sync.outbox_cancel`, `wa_sync.outbox_edit {handle, client_id, text}`, `wa_sync.outbox_retry`, `wa_sync.outbox`.
Media (E6-WA R2, schema v5): the full file of a message is a row of the store's `media` table; the store decides and Kotlin does the
file I/O. A row's `state` is `none`, `queued`, `downloading`, `ready`, `evicted`, `failed` or `gone`; a media row in an answer is
`{chat, id, kind, mime?, size_expected?, state, path?, bytes?, sha256?, used_ms?, pinned?, saved_uri?, by_tap?, attempts?,
next_attempt_ms?, last_error?}` (`path` and `bytes` only while `ready`; flags and counters left out when false or 0). Thread rows
carry `media_path?` (the cached file) and `media_state?` (left out while nothing was ever asked for).
`wa_sync.media_policy {handle, chat, id, network, free_bytes?}` answers `{decision: auto|on_tap|never}`, the owner's rules:
`network` is `wifi`, `mobile`, `roaming`, `data_saver` or `none` (the caller names the most restrictive that holds); on Wi-Fi images,
voice notes and stickers are `auto`, on mobile data images and voice notes; video, documents and other audio are `on_tap`; view-once
media and messages without a file are `never`; nothing is `auto` on `roaming`, `data_saver` or `none`, nor when `free_bytes` minus the
file's size is under 500 MiB. `wa_sync.media_enqueue {handle, now_ms, chat, id, tap?, network?, free_bytes?}` asks for the file:
with `tap` it is queued whatever the network (view-once: see the media round below), without it the rules decide under `network` (required then) and a
deleted message, a `failed` and a `gone` file are left alone; answers `{queued, decision, media}` (`queued`: it waits or is being
downloaded; a `ready` file stays as it is; a tap on a file that waits for its next attempt sends it first, now).
`wa_sync.media_due {handle, now_ms, limit?, network?, free_bytes?}` hands out the downloads to make (`{items, held, next_wake_ms?}`;
taps first, then the order queued; at most `limit`, 4 by default, 32 at most; each is `downloading` until settled and is handed out
again 10 minutes later if nobody settled it; with `network`, automatic downloads the rules no longer allow stay queued and are counted
in `held`). `wa_sync.media_settle {handle, now_ms, chat, id, outcome}` reports one (`outcome.kind`: `ready {path, bytes, sha256?}`,
`failed {error?}`, `gone {error?}`, `cancelled`) and answers `{media, replaced?}`: a failure queues the file again after the
outbox back-off (2 s doubling to 300 s) and after 5 failures it is `failed` until a tap; `replaced` is a file the row named before
and no longer does (delete it). `wa_sync.media_set {handle, now_ms, chat, id, thumbnail_b64?, path?, bytes?}` stores the thumbnail
(kept for good) and/or records a downloaded file without the queue (the same as settling `ready`). `wa_sync.media {handle, chat, id,
used_ms?}` answers `{thumbnail_b64, path, bytes, state, size_expected?, attempts, pinned, saved_uri?, last_error?}`; `used_ms` makes
a cached file the most recently used. `wa_sync.media_keep {handle, chat, id, pinned?, saved_uri?}` keeps a file out of eviction
(`saved_uri: ""` forgets the saved copy) and answers `{media}`. `wa_sync.media_cap {handle, cap_bytes?, no_limit?}` reads the cap,
or sets it first, and answers `{cap_bytes}` (`null`: no limit; 512 MiB until set); it evicts nothing.
`wa_sync.media_evict {handle, max_bytes?, now_ms?}` brings the cache under `max_bytes`, or under the stored cap when left out, and
answers `{evicted: [{chat, id, path, bytes}], bytes_left, exempt_bytes}`: the files to delete, least recently used first; their rows
become `evicted` and keep message and thumbnail, and can be queued again. Never evicted: the file of a starred message, a pinned or
saved file, a file an unsent outbox row sends, and (when `now_ms` is given) a file used in the last 24 hours; when those alone are
over the cap the others go and they stay (`exempt_bytes` > cap). `wa_sync.media_reconcile {handle, files?}` puts rows and files in
step when the store opens: downloads that were cut off are queued again (`requeued`), and with `files` (every file under the
directories of full media and of outbox files, absolute paths, not the thumbnails) a `ready` row whose file is not listed becomes
`evicted` (`missing: [{chat, id, path}]`) and `delete` names the listed files to remove: `*.part` files and files neither a row nor an
unsent outbox row names. Leave `files` out when the listing failed: an empty list would evict every row. `wa_sync.media_stats
{handle, now_ms?}` answers `{total: {files, bytes}, exempt: {files, bytes}, by_kind: [{kind, files, bytes}], outbox: {files, bytes},
queued, downloading, failed, cap_bytes?, exempt_over_cap}` for the settings screen (`outbox`: files of unsent sends that are not
cached files; `cap_bytes` left out without a limit). A merge of two chats moves the media rows too; a message both chats held keeps
the row that has the file.
Media round (E6-WA R3, schema v6): a `send_media` action carries, next to `file`, `media`, `mime`, `size`, `caption?`, `quoted_id?`,
`file_name?`, `seconds?` and `voice_note?`, the options of the gateway call: `thumbnail_b64?`, `width?`, `height?`, `waveform_b64?`,
`animated?`, `view_once?` (left out when unset, so a row of an older build reads and writes as before). They are checked when the row
is queued (`invalid`), come back on the due row for `wa.send_media_with`, and show on the waiting row and on the sent message as
`media` until the gateway describes the message; the preview becomes the message's thumbnail; the file of a view-once send is not
kept as cached media (`wa_sync.media_reconcile` then lists it for deletion). Thread rows (`thread`, `message`, `starred`,
`chat_media`, `chats[].last`) carry in `media`: `waveform_b64?` (the 64 samples of a voice note, base64), `animated`, `view_once`,
`voice_note`, and two local marks, left out when false: `viewed` (a view-once message opened on this device) and `played` (the
played receipt of a received voice note was queued).
A message WhatsApp keeps from linked devices (a view-once message someone sent, a bot's or a hosted business's message) is a row
with `kind: "unsupported"` and `unavailable: "view_once" | "hosted" | "bot" | "other"` (left out on every other row); it has no media
and `wa_sync.media_enqueue` answers `never` for it. The gateway names it in `meta.unavailable`; real content arriving later clears it.
`wa_sync.mark_played {handle, now_ms, client_id, chat, id}` queues the played receipt of a received voice note as an outbox row
(`action: {kind: "mark_played", message_id, sender?}`; `sender` is the author in a group) and answers `{queued, item?}`: once per
message (`queued: false` and no `item` when it was marked before; the same `client_id` again answers the queued row); anything but
a received voice note is `invalid`, an unknown message `not_found`. The row goes out through `wa_sync.outbox_due` (send it with
`wa.mark_played {chat, sender?, ids: [message_id]}`) and `wa_sync.outbox_settle`; a rejected one is dropped (`state: failed`, no
row left) and the message stays played. `wa_sync.outbox_enqueue` refuses a `mark_played` action (`invalid`).
`wa_sync.media_viewed {handle, now_ms, chat, id}`: the owner opened a view-once message. Answers `{media, delete?, first}`: the
media row is `gone` for good, `delete` is the cached file to remove, the thumbnail is dropped, `first` is false when it was viewed
before. Not view once: `invalid`. View-once media is never queued by the auto-download rules; `wa_sync.media_enqueue` with `tap`
queues a received one that was not viewed yet (the tap that opens it) and answers `never` once it was viewed and for one the owner
sent; a download that lands after the message was viewed is not recorded (`wa_sync.media_settle` names the file in `replaced`).
`wa_sync.chat_media {handle, chat, limit, before?, after?, kinds?}` pages the media of a chat for a viewer or a gallery, newest
first, as thread rows: `{items, older?, newer?}`. `kinds` is any of `image`, `video`, `document`, `audio` (left out: `image` and
`video`; another kind is `invalid`); `limit` is 1 to 200. `before` and `after` are `{timestamp_ms, id}` of any row: `before` alone
is the page below it, `after` alone the page right above it (still newest first), both a window between them. `older` (pass as
`before`) and `newer` (pass as `after`) are present when media lies beyond that end. Left out: deleted messages, view-once media,
sends still in the outbox, messages cleared on another device. `chat` may be any id of the chat (a merged chat's old key).
Gateway and account (E6-WA R2): `wa_sync.note_status {handle, now_ms, epoch?, account_epoch?, own_pn?, own_lid?, history?}` takes
what the gateway says about itself (every `wa.status` answer, and the `epoch` alone of the event socket's first frame or of a
`resync`) and answers `{gap, account_changed, delete_files?, rekeyed?, rejected?, resync, history?}`. A stored `epoch` other than
the gateway's is a gap (`gap`: the stored `seq` is forgotten, so `wa_sync.plan` answers `after_seq: null`, and the chat list must be
refreshed, as after a `resync`); the first epoch a store with a `seq` sees is a gap too; no `epoch` changes nothing. An
`account_epoch` above the stored one, or an `own_pn` other than the stored one, is another account on the instance: the store is
emptied in one transaction (chats, cursors, messages with their reactions, ticks and stars, media rows, the outbox, identity tables
and merge log, own ids, the pairs cursor, the history progress; the event `seq`, the epoch and the media cap stay) and the answer
says `account_changed` and lists the media files to delete; what is kept per chat outside the store (drafts, mutes, the rekey
cursor) belongs to the old account too. A value that is unknown on either side wipes nothing, nor does the same account linked
again. `wa_sync.identity_observe` does all of this with the whole `wa.status` answer in `status` (epoch, account epoch, own ids,
history report; a gap reads as `resync`), so the fields need not be copied into `wa_sync.note_status`. `wa_sync.apply_events`
takes the same from the stream, so every `wa.event` is handed in as it came: a `hello` or a `resync` that names another epoch than
the stored one is the gap (`resync` in the answer; `last_seq` is `null` after a `hello` gap until the next event; a `hello` of the
stored epoch changes nothing and never moves the `seq`; no epoch changes nothing); a `state` event is taken in like a status
answer (epoch, account epoch, own ids, history report; one of an account before the stored one, by its lower `account_epoch`,
is not believed); an `account_changed` event is applied at its place in the batch as `wa_sync.account_changed` does
(`account_changed?`, `delete_files?` in the answer: what came before it in the batch is dropped, what follows is kept); a
`history_synced` event keeps its `sync_type` and the oldest `oldest_ms` and adds its `messages` to the stored count until the next
status report sets it. `wa_sync.account_changed {handle, now_ms, old_pn?, new_pn}`
takes the gateway's `account_changed` event at its place in the stream (for a caller that does not hand the event to
`wa_sync.apply_events`; never both) and answers `{rekeyed, rejected, resync, account_changed,
delete_files?}`: a store of another number is emptied; a store already emptied for `new_pn` by a status answer is swept once more
(events of the old account may have arrived in between; the outbox stays); the same account does nothing. `history` is
`{state: idle|syncing|done, progress?, chats?, messages?, oldest_ms?}` as the gateway reports it; it is stored (`progress` capped at
100) and `wa_sync.history {handle}` answers `{history: {state, progress?, chats, messages, oldest_ms?, sync_type?, updated_ms} |
null}` after a restart; a `history_synced` event updates `progress`, `sync_type`, `oldest_ms` and `messages`. `wa_sync.wipe {handle}` or `{instance_id, dir?}` is the
unlink: it closes the store (every handle of it then answers `not_found`), deletes the database with its `-wal` and `-shm`, and
answers `{deleted, media_files}`: the store files removed and the media files the store named (cached files and files of unsent
outbox rows); Kotlin deletes those and the instance's media directory. An instance without a store answers two empty lists.
Identity (E6-WA R1): one person is one chat, whichever id the gateway names them by (a phone-number JID or a LID). A chat is keyed by
the number when it is known, else by the LID; when a pair is learned the rows stored under the LID move to the number, and that move
is a `rekeyed` step `{seq, from_key, into_key}` that everything kept per chat outside the store (drafts, prefs, notification tags,
the open thread) must follow. The store learns pairs from `wa_sync.apply_events` (`identity_mapped`, `identity_number_changed`,
`chat_merged`, the `own_jid` / `own_lid` of `state`, and the other form an event carries: `chat_alt`, `sender_alt`, `jid_alt`,
`peer_alt`, a profile's `lid`), from `wa_sync.store_chats` (`alt`) and `wa_sync.store_page`; their answers add `rekeyed?` and
`rejected?: [{lid, pn, source, reason: invalid|outranked|own_id}]` (pairs that changed nothing, for the log), both left out when
empty, and `apply_events` names the merged chat in `chats` and sets `resync`. `wa_sync.identity_catch_up {handle, items, next?}`
takes one page of `wa.identity_mappings` and answers `{pairs, since, more, rekeyed?, rejected?, resync}`: ask with
`wa_sync.plan`'s `identity_since?` (left out before the first page) when the store opens, when the socket reconnects and after a
`resync`, and go on with `since` while `more`. `wa.identity_mappings {handle, since?}` answers `{supported, items: [{lid, pn,
source, learned_ms}], next}`; `supported: false` is a gateway from before the route (404), not an error. `wa_sync.identity_observe
{handle, now_ms, status?, groups?, profiles?}` learns from the answers of `wa.status` (the whole answer: the account's own ids, and its `epoch`, `account_epoch` and
`history` as `wa_sync.note_status` takes them; another epoch is a gap: `resync`; another account
empties the store: `account_changed`, `delete_files?`), `wa.group(s)` (members' `lid` / `pn`) and `wa.profiles` (`lid`) and answers
`{rekeyed, rejected, resync, account_changed, delete_files?}`. `wa_sync.identity_resolve {handle, id}` answers `{key, kind, wire, person_id?}`
(the key the chat of `id` is stored under, the kind of `id` as written, `id` without device for the wire, the person of a number or
LID) or `null` when `id` is not a JID; `wa_sync.rekeyed_since {handle, seq?}` answers `{rekeyed}`, the steps after `seq`, oldest
first, for a consumer's own cursor; `wa_sync.identity_numbers {handle, id}` answers `{numbers}`, the phone numbers of the
person behind `id` as `+<digits>`, the current one first and then the former ones (a device contact is looked up by each in turn; a
LID without a number, a group and text that is no JID have none; nothing is learned). `wa_sync.thread`, `message`, `older`, `media` and `media_set` take any id of a chat: an id of
before a merge reads the merged chat, and the rows name the key. Chat rows carry `wire_jid?` (the id the gateway last used for the
chat, when it is not `jid`) and message rows `wire_sender?` (the id the author arrived under, when it is not `sender`: what an
outbound message key names). `wa_identity.parse {id}` (pure, no handle) answers `{kind: pn|lid|group|broadcast|status|newsletter|
bot|other, user, bare, number?, device?}` or `null`; a LID has no `number`, whatever its digits.
Phone's identity table (WA-R2, `rosty_app_core::phone::wa_idbook`): Phone shows no WhatsApp messages and never opens the `wa_sync`
store, so it keeps its own small table of LID and number pairs, the same `rosty_core::wa_identity::IdentityMap`, in `wa-identity.json`
under the data dir (or `dir`), one instance per file (read for another `instance_id` it is empty; a damaged or newer file too). No op
touches the network. `wa_idbook.snapshot {dir?, instance_id}` answers `{since?, own_pn?, own_lid?, pairs: [{lid, pn}]}`: every LID the
table can name with the current number of its person (bare ids), and `since` for the next `wa.identity_mappings` (left out before the
first page). `wa_idbook.catch_up {dir?, instance_id, items, next?}` takes one page of `wa.identity_mappings` and answers `{pairs,
since?, more, changed}`: ask at start and after a reconnect or a `resync`, and go on with `since` while `more`; the cursor only moves
with a page that names one, so a catch-up cut short resumes. `wa_idbook.learn {dir?, instance_id, now_ms, pairs?: [{id, alt, source?,
learned_ms?}], number_changes?: [{old_pn, new_pn, old_lid?, new_lid?}]}` answers `{changed}`: `pairs` are two ids of one person in
either order (an `identity_mapped` event, a call's `peer` and `peer_alt`; `source` defaults to `call`; two ids that are not a LID and
a number teach nothing), `number_changes` are `identity_number_changed` events. `wa_idbook.account {dir?, instance_id, own_pn?,
own_lid?, account_epoch?, changed?}` answers `{reset}`: another own number or another `account_epoch` than the stored one empties the
table and its cursor (a value left out never does), `changed: true` is the `account_changed` event and always empties it.
`wa_idbook.wipe {dir?}` removes the file (unlink). After `changed` or `reset`, read `wa_idbook.snapshot` again. Every op takes
`vault: true` (Phone since its vault): the table then lives only in the DK-encrypted vault cache of the unlocked session (`locked`
while the vault is), and the first read moves a plaintext `wa-identity.json` into the cache and deletes it.
WhatsApp vault (`_bmad-output/kotlin-ui/plans/wa-vault.md`, `rosty_core::vault`, `rosty_app_core::wa_vault`): every request may name
`dir` (the data directory; tests); blobs travel as standard base64. Kotlin never gets DK, DDK or the connect key: it seals and
unseals ciphertext with its Keystore keys (K_hw, K_daily) and gets a session `handle`. One session per process; releasing it
(`vault.lock`, `RustNative.release`) zeroizes DK and the connect key and releases the gateways opened from it.
`vault.state {dir?, boot_count?, now_ms?}` answers `{vault?: {hw_alias, hw_sealed}, daily?: {alias, sealed, boot_count, unlocked_ms},
daily_dropped?: rebooted|idle|clock_back, legacy_link, unlocked?: {handle, gateway_url, instance_id}}` (with `boot_count` and `now_ms`
a lapsed daily unlock is deleted and named). `vault.create {dir?, link?: {gateway_url, instance_id, key}}` (no `link`: the plaintext
`whatsapp-link.json`, the migration) answers `{handle, passphrase, seal}`: the passphrase to show once, and the key blob for Kotlin to
seal with K_hw; nothing is written until `vault.commit {handle, hw_alias, hw_sealed}`, which writes `wa-vault.json`, drops the daily
unlock and the cache, makes the session current and answers the state. `vault.unlock {dir?, key_blob, passphrase, boot_count, now_ms}`
(`key_blob` = K_hw-decrypted `hw_sealed`) answers `{handle, daily_seal}`; `vault.daily_store {handle, alias, sealed}` stores the
K_daily-sealed `daily_seal` (`wa-vault-daily.json`) and answers the state; `vault.unlock_daily {dir?, daily_blob, boot_count, now_ms}`
(`daily_blob` = K_daily-decrypted `daily.sealed`) answers `{handle, daily_seal}`, or `auth` `daily_expired` with `detail.reason`
(and deletes the daily file) after a reboot, 72 h without an unlock or a clock set back. `vault.daily_forget {dir?}` ends the daily
unlock (BootReceiver, biometric change, five failures). `vault.lock {handle?, dir?}` answers the state. `vault.change {handle}`
answers `{handle, passphrase, seal}` for a new passphrase and DK (rotation), committed with `vault.commit` under a new alias.
`vault.reset {dir?}` deletes the vault files and answers `{deleted}` (Kotlin deletes the Keystore keys). `vault.wipe_plaintext {dir?,
files_dir, cache_dir?}` deletes every plaintext WhatsApp file (`rosty_app_core::wa_vault::wipe`; databases erased in place first) and
answers `{deleted, scrubbed, drafts_removed, failed}`; it refuses (`no_vault`) while a plaintext link has no vault yet.
`vault.export_link {handle}` answers `{gateway_url, instance_id, key}` of the current, committed session only (`locked`
otherwise): the hand-over to a sibling app that sets up its own vault with `vault.create {link}` (`WaLinkProvider`, memory only).
`vault.trip_hold {handle}` copies that link into the RAM of the running Stops trip (`wa_link::send::hold_trip_link`; the
`trip_fix` notice sends through it, never through a link file) and answers `{held}`; `vault.trip_release {}` and every end
of a trip (`stops::store::end_trip`) drop it.
`vault.pictures {jids?}` answers `{pictures: [{jid, picture_id, jpeg}]}` (base64 JPEG) from the encrypted cache, and
`vault.picture_put {jid, picture_id?, jpeg?}` keeps one (both left out: the person has no picture; at most 4 MiB, oldest first out)
and answers `{handle, pictures}`; both are `locked` while the vault is. Errors carry
`detail.code`: `typo`, `length`, `character` (`invalid`), `wrong_passphrase` (`auth`), `damaged` (`parse`), `no_vault`, `no_daily`,
`no_link`, `locked` (`not_found`), `vault_exists`, `nothing_to_commit`, `nothing_to_store`, `not_committed`, `bad_url`, `bad_key`
(`invalid`). Answers with fresh random values (`passphrase`, `seal`, `daily_seal`) read `$RANDOM` in the fixtures (`Case::volatile`).
Phone backup: `phone.backup_export` / `phone.backup_import` (pure; Kotlin reads and writes the picked file), `phone.display_name`.

| Item | Used by | Domain | Class | Target |
|---|---|---|---|---|
| `(bridge) core.version` | - | bridge | op | core.version |
| `(bridge) core.echo` | - | bridge | op | core.echo |
| `(bridge) core.data_dir` | - | bridge | op | core.data_dir |
| `(bridge) core.fail` | - | bridge | op | core.fail |
| `(bridge) core.logs_drain` | - | bridge | op | core.logs_drain |
| `(bridge) core.panic` | - | bridge | op | core.panic |
| `(bridge) core.emit_test` | - | bridge | op | core.emit_test |
| `(qr) qr.encode` | contacts | qr | op | qr.encode |
| `(qr) qr.decode` | contacts | qr | op | qr.decode |
| `(cards) cards.templates` | contacts | cards | op | cards.templates |
| `(cards) cards.render` | contacts | cards | op | cards.render |
| `(cards) cards.cancel` | contacts | cards | op | cards.cancel |
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
| `(wa) wa.identity_mappings` | - | wa | op | wa.identity_mappings |
| `(wa) wa.keys` | - | wa | op | wa.keys |
| `(wa) wa.lookup_users` | - | wa | op | wa.lookup_users |
| `(wa) wa.mark_played` | - | wa | op | wa.mark_played |
| `(wa) wa.mark_read` | - | wa | op | wa.mark_read |
| `(wa) wa.mark_status_viewed` | - | wa | op | wa.mark_status_viewed |
| `(wa) wa.messages` | - | wa | op | wa.messages |
| `(wa) wa.messages_with_meta` | - | wa | op | wa.messages_with_meta |
| `(wa) wa.mint_key` | - | wa | op | wa.mint_key |
| `(wa) wa.people_sync_bundle` | - | wa | op | wa.people_sync_bundle |
| `(wa) wa.people_sync_cancel` | - | wa | op | wa.people_sync_cancel |
| `(wa) wa.people_sync_start` | - | wa | op | wa.people_sync_start |
| `(wa) wa.people_sync_status` | - | wa | op | wa.people_sync_status |
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
| `rosty_app_core::contacts::prefs` | contacts | contacts | op | contacts.prefs_load, contacts.prefs_save |
| `rosty_app_core::contacts::trash_store` | contacts | contacts | op | contacts.trash_list, contacts.trash_push, contacts.trash_restore, contacts.trash_delete |
| `rosty_app_core::contacts::whatsapp` | contacts | wa | op | wa.target, contacts.wa_marks, contacts.wa_card, contacts.wa_profile_lines |
| `rosty_app_core::messages::access` | messages | messages | op | messages.access |
| `rosty_app_core::messages::drafts` | messages | messages | op | messages.drafts_load, messages.drafts_save, messages.drafts_put, messages.drafts_rekey |
| `rosty_app_core::messages::headless` | messages | platform | none | served by the headless kinds (E2.6, fixtures headless.*) |
| `rosty_app_core::messages::prefs` | messages | messages | op | messages.prefs_load, messages.prefs_save, messages.prefs_edit |
| `rosty_app_core::messages::tags` | messages | messages | op | messages.tag |
| `rosty_app_core::permission::Access` | messages | platform | op | messages.access |
| `rosty_app_core::people` | contacts,phone | people | op | people.upsert, people.lookup, people.plan, people.snapshot |
| `rosty_app_core::people_sync` | contacts,phone | people | op | people.sync_status, people.sync_begin, people.sync_next, people.sync_report, people.sync_stop |
| `rosty_app_core::wa_sync` | messages | wa | op | wa_sync.open, wa_sync.open_page, wa_sync.focus, wa_sync.cache_save, wa_sync.close, wa_sync.plan, wa_sync.apply_events, wa_sync.store_chats, wa_sync.store_page, wa_sync.older, wa_sync.chats, wa_sync.thread, wa_sync.message, wa_sync.starred, wa_sync.outbox_enqueue, wa_sync.outbox_due, wa_sync.outbox_settle, wa_sync.outbox_cancel, wa_sync.outbox_edit, wa_sync.outbox_retry, wa_sync.outbox, wa_sync.media_set, wa_sync.media, wa_sync.media_evict, wa_sync.media_policy, wa_sync.media_enqueue, wa_sync.media_due, wa_sync.media_settle, wa_sync.media_keep, wa_sync.media_reconcile, wa_sync.media_stats, wa_sync.media_cap, wa_sync.chat_media, wa_sync.media_viewed, wa_sync.mark_played, wa_sync.note_status, wa_sync.account_changed, wa_sync.history, wa_sync.wipe, wa_sync.identity_resolve, wa_sync.rekeyed_since, wa_sync.identity_numbers, wa_sync.identity_catch_up, wa_sync.identity_observe |
| `rosty_core::wa_identity::parse` | messages,phone | wa | op | wa_identity.parse |
| `rosty_app_core::phone::wa_idbook` | phone | wa | op | wa_idbook.snapshot, wa_idbook.catch_up, wa_idbook.learn, wa_idbook.account, wa_idbook.wipe |
| `rosty_app_core::wa_vault` (WhatsApp vault) | messages,phone,contacts,stops | vault | op | vault.state, vault.create, vault.commit, vault.unlock, vault.unlock_daily, vault.daily_store, vault.daily_forget, vault.lock, vault.change, vault.export_link, vault.reset, vault.trip_hold, vault.trip_release, vault.wipe_plaintext, vault.pictures, vault.picture_put |
| `rosty_app_core::phone::notes` | phone | phone | op | phone.notes_load, phone.notes_save, call_notes.set, call_notes.remove, call_notes.restore |
| `rosty_app_core::phone::wa_call` | phone | phone | op | phone.wa_tracker_new, phone.wa_tracker_update, phone.wa_tracker_placed, phone.wa_tracker_view, phone.wa_tracker_mute, phone.wa_tracker_clear, phone.wa_tracker_can_place, phone.wa_tracker_format_elapsed, phone.wa_arbitrate, phone.wa_screen, phone.wa_handoff_number |
| `rosty_app_core::stops::headless` | stops | platform | none | served by the headless kinds (E2.6, fixtures headless.*) |
| `rosty_app_core::stops::pick` | stops | stops | op | stops.pick_replay, stops.pick_labels, stops.pick_save_offer |
| `rosty_app_core::stops::snap` | stops | stops | op | stops.snap |
| `rosty_app_core::stops::store` | stops | stops | op | stops.store_trip_load, stops.store_trip_save, stops.store_trip_end, stops.store_trip_clear, stops.store_gtfs_remove, stops.store_read, stops.store_write |
| `rosty_app_core::stops::types` | stops | stops | op | stops.pick_replay, stops.pick_labels, stops.pick_save_offer |
| `rosty_app_core::storage::BackupRefusal` | rosty_ui | platform | op | phone.backup_export, phone.backup_import |
| `rosty_app_core::storage::Loaded` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::Origin` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::PREFS_FILE` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::Prefs` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::backup_json` | rosty_ui | platform | op | phone.backup_export, phone.backup_import |
| `rosty_app_core::storage::copy_aside` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::load_json` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::load_prefs` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::parse_backup` | rosty_ui | platform | op | phone.backup_export, phone.backup_import |
| `rosty_app_core::storage::save_json` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::storage::save_prefs` | rosty_ui | platform | op | phone.prefs_load, phone.prefs_save |
| `rosty_app_core::wa_link::avatars` | rosty_wa_ui | wa | op | wa.avatars_plan |
| `rosty_app_core::wa_link::link` | rosty_wa_ui | wa | op | wa.link_read, wa.link_write |
| `rosty_app_core::wa_link::names` | rosty_wa_ui | wa | op | wa.names |
| `rosty_app_core::wa_link::send` | rosty_wa_ui | wa | none | served by the messages headless kinds wa_digest and wa_reply (E2.6, fixtures headless.*) |
| `rosty_app_core::wa_link::settings` | rosty_wa_ui | wa | op | wa.settings_apply |
| `rosty_app_core::wa_link::settings_view` | rosty_wa_ui | wa | op | wa.settings_apply |
| `rosty_core::Channel` | messages | messages | op | inbox.preferred_channel |
| `rosty_core::Message` | messages | messages | op | inbox.merge, sms.to_message |
| `rosty_core::MessageDirection` | messages | messages | op | inbox.merge, sms.to_message |
| `rosty_core::PhoneNumber` | messages,phone | shared | op | phone_number.parse, phone_number.same |
| `rosty_core::ThreadId` | messages | messages | op | messages.tag |
| `rosty_core::audio::Decision` | phone | phone | op | audio.rows, audio.diff |
| `rosty_core::audio::Diff` | phone | phone | op | audio.rows, audio.diff |
| `rosty_core::audio::EndpointList` | phone | phone | op | audio.rows, audio.diff |
| `rosty_core::audio::MAX_ROWS` | phone | phone | op | audio.rows, audio.diff |
| `rosty_core::audio::Row` | phone | phone | op | audio.rows, audio.diff |
| `rosty_core::audio::RowKind` | phone | phone | op | audio.rows, audio.diff |
| `rosty_core::avatar::AvatarKey` | phone | contacts | op | contacts.avatar |
| `rosty_core::avatar::avatar_hue` | phone | contacts | op | contacts.avatar |
| `rosty_core::avatar::hue_index` | phone | contacts | op | contacts.avatar |
| `rosty_core::avatar::initials` | messages | contacts | op | contacts.avatar |
| `rosty_core::avatar::monogram` | phone | contacts | op | contacts.avatar |
| `rosty_core::birthday_reminder::BirthdayAlarm` | contacts | contacts | op | contacts.birthday_reminders |
| `rosty_core::birthday_reminder::DEFAULT_HOUR` | contacts | contacts | op | contacts.birthday_reminders |
| `rosty_core::birthday_reminder::HOUR_CHOICES` | contacts | contacts | op | contacts.birthday_reminders |
| `rosty_core::birthday_reminder::hour_label` | contacts | contacts | op | contacts.birthday_reminders |
| `rosty_core::blocklist::BlockInputError` | phone | phone | op | blocklist.parse_input |
| `rosty_core::blocklist::BlockRefusal` | messages,phone | phone | op | blocklist.check |
| `rosty_core::blocklist::BlockSet` | messages,phone | phone | op | blocklist.check |
| `rosty_core::blocklist::can_block` | messages,phone | phone | op | blocklist.check |
| `rosty_core::bubble_gesture::SwipeReply` | messages | input | kotlin | :messages BubbleGesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::call::CallModel` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::call::CallState` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::call::after_call_text` | phone | phone | op | scene.texts |
| `rosty_core::call::caller_label` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::call::format_elapsed` | phone | phone | op | scene.texts |
| `rosty_core::call::format_number` | phone | shared | op | format.render |
| `rosty_core::call::incoming_announcement` | phone | phone | op | scene.texts |
| `rosty_core::call::is_missed` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::call::missed_headline` | phone | phone | op | scene.texts |
| `rosty_core::call::status_text` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::call_log::CallType` | contacts,phone | phone | op | recents.group, recents.build |
| `rosty_core::call_log::DateOrder` | phone | phone | op | recents.group, recents.build |
| `rosty_core::call_log::EntryRef` | contacts,phone | phone | op | recents.group, recents.build |
| `rosty_core::call_log::FREQUENT_LIMIT` | phone | phone | op | call_log.frequent, call_log.counts |
| `rosty_core::call_log::FREQUENT_WINDOW_DAYS` | phone | phone | op | call_log.frequent, call_log.counts |
| `rosty_core::call_log::RecentsRow` | phone | phone | op | recents.group, recents.build |
| `rosty_core::call_log::TimeFormat` | phone | phone | op | recents.group, recents.build |
| `rosty_core::call_log::badge_label` | phone | phone | op | recents.missed_extras |
| `rosty_core::call_log::blocked_ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rosty_core::call_log::deleted_text` | phone | phone | kotlin | :phone res/values/strings.xml (user-facing sentences live in Android resources, localised) |
| `rosty_core::call_log::entries_by_id` | phone | phone | kotlin | :phone RecentsRepository.kt (a filter by id over the list Kotlin already holds) |
| `rosty_core::call_log::frequent` | phone | phone | op | call_log.frequent, call_log.counts |
| `rosty_core::call_log::ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rosty_core::call_log::missed_badge` | phone | phone | op | recents.missed_extras |
| `rosty_core::call_log::relative_time` | messages,phone | phone | op | recents.build, recents.history_text |
| `rosty_core::call_log_query::PruneAge` | phone | phone | op | call_log.prune_plan |
| `rosty_core::call_log_query::Query` | phone | phone | op | call_log.query |
| `rosty_core::call_log_query::RecentsFilter` | phone | phone | op | call_log.query |
| `rosty_core::call_log_query::csv_file_name` | phone | phone | op | call_log.csv |
| `rosty_core::call_log_query::delete_selected_text` | phone | phone | kotlin | :phone res/values/strings.xml (user-facing sentences live in Android resources, localised) |
| `rosty_core::call_log_query::ids_older_than` | phone | phone | op | call_log.prune_plan |
| `rosty_core::call_log_query::person_export` | phone | phone | op | call_log.csv |
| `rosty_core::call_log_query::prune_none_text` | phone | phone | op | call_log.prune_plan |
| `rosty_core::call_log_query::prune_text` | phone | phone | op | call_log.prune_plan |
| `rosty_core::call_log_query::to_csv_with_notes` | phone | phone | op | call_log.csv |
| `rosty_core::call_notes::CallDetails` | phone | phone | op | call_notes.details |
| `rosty_core::call_notes::CallNote` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rosty_core::call_notes::Notes` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rosty_core::call_notes::SetOutcome` | phone | phone | op | call_notes.set, call_notes.remove, call_notes.restore, call_notes.rows, call_notes.for_calls |
| `rosty_core::call_notes::details` | phone | phone | op | call_notes.details |
| `rosty_core::call_notes::notes_csv_file_name` | phone | phone | op | call_notes.export |
| `rosty_core::call_stats::DayBar` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rosty_core::call_stats::Range` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rosty_core::call_stats::StatCall` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rosty_core::call_stats::StatDir` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rosty_core::call_stats::Totals` | phone | phone | op | call_stats.overview, call_stats.person, call_stats.bucket |
| `rosty_core::calling_code::country_line` | phone | shared | op | calling_code.line |
| `rosty_core::chat_export::ExportLine` | messages | messages | op | chat_export.text, chat_export.block, chat_export.notice |
| `rosty_core::chat_find::FindCursor` | messages | messages | op | chat_find.find, chat_find.cursor |
| `rosty_core::chat_gallery::DocEntry` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rosty_core::chat_gallery::Gallery` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rosty_core::chat_gallery::GalleryInput` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rosty_core::chat_gallery::GalleryKind` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rosty_core::chat_gallery::GalleryTab` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rosty_core::chat_gallery::LinkEntry` | messages | messages | op | chat_gallery.build, chat_gallery.tabs |
| `rosty_core::clock::now_ms` | contacts,messages,phone,rosty_wa_ui | platform | none | ops take now_ms; Kotlin owns the clock |
| `rosty_core::clock::set_fixed_now_ms` | contacts,messages,phone,stops | platform | none | ops take now_ms; Kotlin owns the clock |
| `rosty_core::contact_card::ContactCard` | contacts | contacts | op | contacts.card |
| `rosty_core::contact_cleanup::Cleanup` | contacts | contacts | op | contacts.cleanup |
| `rosty_core::contact_csv` | contacts | contacts | op | contacts.csv_export, contacts.csv_import |
| `rosty_core::contact_dates::Upcoming` | contacts | contacts | op | contacts.dates, contacts.event_line, contacts.maps_url |
| `rosty_core::contact_dates::local_ymd` | contacts,stops | contacts | op | contacts.local_ymd |
| `rosty_core::contact_dates::parse` | contacts | contacts | op | contacts.date_parse, contacts.age |
| `rosty_core::contact_dupes::DupGroup` | contacts | contacts | op | contacts.dupes, contacts.merge_plan |
| `rosty_core::contact_dupes::find` | contacts | contacts | op | contacts.dupes |
| `rosty_core::contact_edit::Account` | contacts | contacts | op | contacts.trash_restore, contacts.account_label |
| `rosty_core::contact_edit::AccountsPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rosty_core::contact_edit::Addition` | contacts | contacts | op | contacts.draft_from_query, contacts.draft_add |
| `rosty_core::contact_edit::ContactDraft` | contacts | contacts | op | contacts.draft_validate, contacts.draft_diff, contacts.draft_edit |
| `rosty_core::contact_edit::DetailPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rosty_core::contact_edit::DraftEvent` | contacts | contacts | op | contacts.draft_validate |
| `rosty_core::contact_edit::DraftField` | contacts | contacts | op | contacts.draft_validate |
| `rosty_core::contact_edit::DraftRelation` | contacts | contacts | op | contacts.draft_validate |
| `rosty_core::contact_edit::EventKind` | contacts | contacts | op | contacts.draft_validate |
| `rosty_core::contact_edit::FieldKind` | contacts | contacts | op | contacts.draft_validate |
| `rosty_core::contact_edit::FullPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rosty_core::contact_edit::Group` | contacts | contacts | op | contacts.groups_title, contacts.groups_targets |
| `rosty_core::contact_edit::GroupsPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads groups into model.contacts.Group directly |
| `rosty_core::contact_edit::ImportPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rosty_core::contact_edit::MAX_FIELDS` | contacts | contacts | op | contacts.draft_edit |
| `rosty_core::contact_edit::MembersPayload` | contacts | contacts | op | contacts.groups_summary |
| `rosty_core::contact_edit::RelationKind` | contacts | contacts | op | contacts.draft_validate |
| `rosty_core::contact_edit::RelationTap` | contacts | contacts | op | contacts.relation_tap |
| `rosty_core::contact_edit::SavedPayload` | contacts | contacts | none | payload of the GPUI provider bridge; Kotlin reads ContactsContract into model.contacts.ContactDraft directly |
| `rosty_core::contact_edit::SelectionChange` | contacts | contacts | op | contacts.groups_change |
| `rosty_core::contact_edit::account_label` | contacts | contacts | op | contacts.account_label |
| `rosty_core::contact_edit::default_action` | contacts | contacts | op | contacts.field_menu |
| `rosty_core::contact_edit::draft_from_query` | contacts | contacts | op | contacts.draft_from_query |
| `rosty_core::contact_edit::group_join_hint` | contacts | contacts | op | contacts.groups_labels |
| `rosty_core::contact_edit::group_subtitle` | contacts | contacts | op | contacts.groups_labels |
| `rosty_core::contact_edit::group_title` | contacts | contacts | op | contacts.groups_title |
| `rosty_core::contact_edit::primary_of` | contacts | contacts | op | contacts.draft_edit |
| `rosty_core::contact_edit::relation_tap` | contacts | contacts | op | contacts.relation_tap |
| `rosty_core::contact_edit::retain_account_groups` | contacts | contacts | op | contacts.draft_edit |
| `rosty_core::contact_edit::set_primary` | contacts | contacts | op | contacts.draft_edit |
| `rosty_core::contact_edit::web_url` | contacts | contacts | op | contacts.web_url |
| `rosty_core::contact_find::DeepField` | contacts | contacts | op | contacts.deep_find |
| `rosty_core::contact_find::DeepHit` | contacts | contacts | op | contacts.deep_find |
| `rosty_core::contact_find::DeepIndex` | contacts | contacts | op | contacts.deep_index_new, contacts.deep_find |
| `rosty_core::contact_frequent::Frequent` | contacts | contacts | op | contacts.frequent |
| `rosty_core::contact_history::CARD_LIMIT` | contacts | contacts | op | contacts.history |
| `rosty_core::contact_history::PULL_LIMIT` | contacts | contacts | op | contacts.history |
| `rosty_core::contact_ics` | contacts | contacts | op | contacts.ics |
| `rosty_core::contact_intake::Intake` | contacts | contacts | op | contacts.intake |
| `rosty_core::contact_me` | contacts | contacts | op | contacts.me_state, contacts.me_toggle |
| `rosty_core::contact_options::ContactOptions` | contacts | contacts | op | contacts.options |
| `rosty_core::contact_options::PinShortcut` | contacts | contacts | op | contacts.pin_shortcut |
| `rosty_core::contact_photo::CropView` | contacts | contacts | op | contacts.photo_crop |
| `rosty_core::contact_photo::PhotoError` | contacts,phone | contacts | op | contacts.photo_plan |
| `rosty_core::contact_photo::accepts` | phone | contacts | op | contacts.photo_accepts |
| `rosty_core::contact_photo::plan_for` | contacts | contacts | op | contacts.photo_plan |
| `rosty_core::contact_photo::rotated_size` | contacts | contacts | op | contacts.photo_plan |
| `rosty_core::contact_quick::QuickAction` | contacts | contacts | op | contacts.quick |
| `rosty_core::contact_recent::AddedMark` | contacts | contacts | op | contacts.recent_note_pull, contacts.recent_note_created |
| `rosty_core::contact_recent::CLEAR_VIEWED` | contacts | contacts | op | contacts.recent_lists |
| `rosty_core::contact_recent::RecentTab` | contacts | contacts | op | contacts.recent_lists |
| `rosty_core::contact_recent::VIEWED_KEEP` | contacts | contacts | op | contacts.recent_viewed |
| `rosty_core::contact_recent::note_created` | contacts | contacts | op | contacts.recent_note_created |
| `rosty_core::contact_recent::note_pull` | contacts | contacts | op | contacts.recent_note_pull |
| `rosty_core::contact_recent::show_entry` | contacts | contacts | op | contacts.recent_lists |
| `rosty_core::contact_scan::Scanned` | contacts | contacts | op | contacts.scan |
| `rosty_core::contact_select::GroupTarget` | contacts | contacts | op | contacts.groups_targets |
| `rosty_core::contact_select::SHARE_FORMATS` | contacts | contacts | op | contacts.select_bar |
| `rosty_core::contact_select::SelectAction` | contacts | contacts | op | contacts.select_bar |
| `rosty_core::contact_select::ShareFormat` | contacts | contacts | op | contacts.select_export |
| `rosty_core::contact_select::cap_notice` | contacts | contacts | op | contacts.select_bar |
| `rosty_core::contact_select::delete_confirm` | contacts | contacts | op | contacts.select_bar |
| `rosty_core::contact_select::export_ids` | contacts | contacts | op | contacts.select_export |
| `rosty_core::contact_stats::Summary` | contacts | contacts | op | contacts.stats |
| `rosty_core::contact_trash::Trash` | contacts | contacts | op | contacts.trash_list, contacts.trash_push, contacts.trash_restore, contacts.trash_delete, contacts.trash_confirm |
| `rosty_core::contact_trash::moved_notice` | contacts | contacts | op | contacts.trash_push |
| `rosty_core::contacts_prefs::ContactsPrefs` | contacts | contacts | op | contacts.prefs_load, contacts.prefs_save |
| `rosty_core::contacts_prefs::MAX_EMERGENCY` | contacts | contacts | op | contacts.me_state |
| `rosty_core::contacts_view::CallTarget` | contacts,phone | contacts | op | contacts.card |
| `rosty_core::contacts_view::ContactVm` | contacts,phone | contacts | op | contacts.view |
| `rosty_core::contacts_view::NameStyle` | contacts,phone | contacts | op | contacts.view |
| `rosty_core::contacts_view::NumberVm` | contacts,phone | contacts | op | contacts.view, contacts.card |
| `rosty_core::contacts_view::build_rows_styled` | contacts | contacts | op | contacts.view |
| `rosty_core::contacts_view::call_target` | phone | contacts | op | contacts.card |
| `rosty_core::contacts_view::without` | contacts | contacts | op | contacts.view, contacts.favorites_move, contacts.scrubber |
| `rosty_core::copy_choices::CopyChoice` | messages | shared | op | copy.header_choices |
| `rosty_core::copy_choices::header_choices` | messages | shared | op | copy.header_choices |
| `rosty_core::date_detect::calendar_end_ms` | messages | messages | op | date_detect.detect |
| `rosty_core::date_detect::detect` | messages | messages | op | date_detect.detect |
| `rosty_core::date_detect::event_title` | messages | messages | op | date_detect.detect |
| `rosty_core::date_detect::matched_text` | messages | messages | op | date_detect.detect |
| `rosty_core::date_jump::DateStep` | messages | messages | op | date_jump.step |
| `rosty_core::date_jump::DayPick` | messages | messages | op | date_jump.pick |
| `rosty_core::date_jump::date_notice` | messages | messages | op | date_jump.step |
| `rosty_core::date_jump::date_step` | messages | messages | op | date_jump.step |
| `rosty_core::date_jump::later_notice` | messages | messages | op | date_jump.step |
| `rosty_core::date_jump::quick_picks` | messages | messages | op | date_jump.pick |
| `rosty_core::date_jump::starts_later` | messages | messages | op | date_jump.step |
| `rosty_core::dial::FieldAction` | phone | input | op | dial.field_menu |
| `rosty_core::dial::Push` | phone | input | kotlin | :phone Dial.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::dial::TonePlan` | phone | input | op | dial.tone_plan, dial.tones_text, dial.tone_preview, dial.paste_tones_label |
| `rosty_core::dial::ToneStep` | phone | input | op | dial.tone_plan, dial.tones_text, dial.tone_preview, dial.paste_tones_label |
| `rosty_core::dial::from_tel_uri` | phone | input | op | dial.from_tel_uri |
| `rosty_core::forward::ForwardBody` | messages | messages | op | forward.plan |
| `rosty_core::forward::ForwardPlan` | messages | messages | op | forward.plan |
| `rosty_core::forward::ForwardTo` | messages | messages | op | forward.plan, forward.words |
| `rosty_core::forward::notice` | messages | messages | op | forward.words |
| `rosty_core::forward::plan` | messages | messages | op | forward.plan |
| `rosty_core::forward::target_label` | messages | messages | op | forward.words |
| `rosty_core::gesture::Dir` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::gesture::Release` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::gesture::Tick` | phone | input | kotlin | :design Gesture.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::global_search::JumpStep` | messages | messages | op | global_search.jump |
| `rosty_core::global_search::JumpTarget` | messages | messages | none | a hit is its chat_key and message_id plus at_ms; Kotlin keeps the target while a jump loads pages |
| `rosty_core::global_search::MIN_QUERY_CHARS` | messages | messages | op | global_search.prepare |
| `rosty_core::global_search::SearchCandidate` | messages | messages | op | global_search.search |
| `rosty_core::global_search::SearchHit` | messages | messages | op | global_search.search |
| `rosty_core::global_search::Source` | messages | messages | op | global_search.footer |
| `rosty_core::global_search::WA_CHATS` | messages | messages | op | global_search.prepare |
| `rosty_core::global_search::jump_notice` | messages | messages | op | global_search.jump |
| `rosty_core::global_search::jump_step` | messages | messages | op | global_search.jump |
| `rosty_core::group_tools::Kind` | contacts | contacts | op | contacts.groups_recipients |
| `rosty_core::import_plan::number_key` | contacts | contacts | op | contacts.number_key |
| `rosty_core::import_plan::photo_targets` | contacts | contacts | op | contacts.import_photos |
| `rosty_core::inbox::InboxRow` | messages | messages | op | inbox.merge |
| `rosty_core::inbox::InboxThread` | messages | messages | op | inbox.merge |
| `rosty_core::inbox::PersonKey` | messages | messages | op | inbox.person_for_number, inbox.person_for_jid |
| `rosty_core::inbox::merge` | messages | messages | op | inbox.merge |
| `rosty_core::inbox_bulk::BulkAction` | messages | messages | op | inbox.bulk |
| `rosty_core::inbox_bulk::BulkFacts` | messages | messages | op | inbox.bulk |
| `rosty_core::inbox_filter::InboxFilter` | messages | messages | op | inbox.filter |
| `rosty_core::inbox_filter::RowFacts` | messages | messages | op | inbox.filter |
| `rosty_core::keypad_layout::Insets` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::keypad_layout::KeypadInput` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::keypad_layout::KeypadLayout` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::keypad_layout::Rect` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::keypad_layout::key_style` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::keypad_layout::keypad_layout` | phone | input | kotlin | :phone KeypadLayout.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::location_link::coords_text` | messages | messages | op | location_link.find, location_link.share |
| `rosty_core::location_link::find_places` | messages | messages | op | location_link.find |
| `rosty_core::location_link::geo_url` | messages | messages | op | location_link.find, location_link.share |
| `rosty_core::lru::Lru` | contacts,messages,phone | platform | none | Kotlin uses android.util.LruCache |
| `rosty_core::message_info::InfoChannel` | messages | messages | op | message_info.rows |
| `rosty_core::message_info::InfoInput` | messages | messages | op | message_info.rows |
| `rosty_core::message_info::InfoRow` | messages | messages | op | message_info.rows |
| `rosty_core::message_info::InfoState` | messages | messages | op | message_info.rows |
| `rosty_core::message_info::info_text` | messages | messages | op | message_info.rows |
| `rosty_core::message_info::rows` | messages | messages | op | message_info.rows |
| `rosty_core::message_reminder::MessageReminder` | messages | messages | op | message_reminder.make |
| `rosty_core::message_reminder::arm_json` | messages | messages | op | message_reminder.make |
| `rosty_core::message_reminder::fired_id` | messages | messages | op | message_reminder.list |
| `rosty_core::message_reminder::parse_list` | messages | messages | op | message_reminder.list |
| `rosty_core::msg_backup::BackupBody` | messages | messages | op | msg_backup.export, msg_backup.parse |
| `rosty_core::msg_backup::BackupError` | messages | messages | op | msg_backup.parse |
| `rosty_core::msg_backup::RestorePlan` | messages | messages | op | msg_backup.plan |
| `rosty_core::msg_backup::Strength` | messages | messages | op | msg_backup.strength |
| `rosty_core::msg_backup::passphrase_strength` | messages | messages | op | msg_backup.strength |
| `rosty_core::multi_select::Selection` | contacts,messages | messages | kotlin | :messages MultiSelect.kt (the selection set is per-tap UI state; the cap, title, copy block and pill texts are multi_select.plan; Rust tests mirrored as JVM tests) |
| `rosty_core::mute::MuteFor` | messages | messages | op | mute.choices |
| `rosty_core::mute::header_line` | messages | messages | op | mute.status |
| `rosty_core::mute::muted_notice` | messages | messages | op | mute.status |
| `rosty_core::names::display` | phone | platform | op | phone.display_name |
| `rosty_core::notify::Incoming` | messages | messages | op | notify.build |
| `rosty_core::notify::MAX_LINES` | messages | messages | op | notify.build |
| `rosty_core::notify::NotificationSpec` | messages | messages | op | notify.build |
| `rosty_core::notify::build` | messages | messages | op | notify.build |
| `rosty_core::number_format::NumberForm` | phone | shared | op | format.render |
| `rosty_core::number_format::QR_MAX_BYTES` | contacts | shared | op | format.contact_qr |
| `rosty_core::number_format::QrTooBig` | contacts | shared | op | format.contact_qr |
| `rosty_core::number_format::contact_qr` | contacts,phone | shared | op | format.contact_qr |
| `rosty_core::number_format::file_slug` | messages | shared | op | format.file_name |
| `rosty_core::number_format::file_slug_opt` | contacts,messages,phone,stops | shared | op | format.file_name |
| `rosty_core::number_format::infer_home_cc` | contacts,phone | shared | op | format.infer_home_cc |
| `rosty_core::number_format::qr_file_name` | phone | shared | op | format.file_name |
| `rosty_core::otp::detect` | messages | messages | op | otp.find |
| `rosty_core::pending_delete::PendingDeletes` | messages | messages | op | inbox.pending_delete |
| `rosty_core::pending_delete::notice` | messages | messages | op | inbox.pending_delete |
| `rosty_core::perf::FrameStats` | phone,rosty_ui | input | kotlin | :design FramePerf.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::perf::FrameSummary` | rosty_ui | input | kotlin | :design FramePerf.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::quick_replies::ReplyError` | messages | messages | op | quick_replies.edit |
| `rosty_core::quick_replies::insert_into` | messages | messages | op | quick_replies.insert |
| `rosty_core::quick_replies::restore` | messages | messages | op | quick_replies.edit |
| `rosty_core::recents::Direction` | phone | phone | op | recents.build |
| `rosty_core::recents::ExtraCall` | phone | phone | op | recents.merge_extras |
| `rosty_core::recents::HistoryLine` | contacts,phone | phone | op | recents.history, recents.blocked_history, recents.history_text |
| `rosty_core::recents::LocaleTime` | contacts,messages,phone,stops | phone | op | recents.build |
| `rosty_core::recents::RowView` | phone | phone | op | recents.build |
| `rosty_core::recents::extra_ids_for_numbers` | phone | phone | op | call_log.ids_for_numbers |
| `rosty_core::recents::split_refs` | phone | phone | kotlin | :phone RecentsRepository.kt (a when over the two ref kinds) |
| `rosty_core::reminder::Reminder` | phone | messages | op | call_reminder.make, call_reminder.list |
| `rosty_core::reminder::Slot` | messages,phone | messages | op | message_reminder.slots, call_reminder.make |
| `rosty_core::reminder::arm_json` | phone | messages | op | call_reminder.make |
| `rosty_core::reminder::clock` | messages | messages | op | message_reminder.make |
| `rosty_core::reminder::parse_list` | phone | messages | op | call_reminder.list |
| `rosty_core::scene::CallScene` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::scene::Control` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::scene::DisabledReason` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::scene::Layout` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::scene::Participants` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::scene::SceneDiff` | phone | phone | op | scene.of, scene.diff |
| `rosty_core::schedule::FiredSms` | messages | messages | op | schedule.reconcile |
| `rosty_core::schedule::MIN_LEAD_MS` | messages | messages | op | schedule.validate |
| `rosty_core::schedule::ScheduleError` | messages | messages | op | schedule.validate, schedule.add |
| `rosty_core::schedule::ScheduleQueue` | messages | messages | op | schedule.add, schedule.cancel, schedule.load, schedule.due_soon |
| `rosty_core::schedule::ScheduleTarget` | messages | messages | op | schedule.add |
| `rosty_core::schedule::ScheduledSms` | messages | messages | op | schedule.add |
| `rosty_core::search::ContactIndex` | contacts,messages,phone | search | op | search.index_new, search.index_replace, search.set_call_counts, search.query, search.query_loose, search.match_number, search.match_numbers |
| `rosty_core::search::fold` | contacts,phone,stops | search | op | search.fold |
| `rosty_core::settings::BACKUP_KIND` | phone | platform | op | phone.backup_export, phone.backup_import |
| `rosty_core::settings::DEFAULT_REPLIES` | phone | phone | op | phone.reply_choices |
| `rosty_core::settings::HapticKind` | rosty_ui | phone | kotlin | :design Haptics.kt (per-tap check, main thread; Rust table mirrored as a JVM test) |
| `rosty_core::settings::ImportPreview` | phone | platform | op | phone.backup_import |
| `rosty_core::settings::MAX_REPLIES` | phone | phone | op | phone.reply_choices |
| `rosty_core::settings::MAX_REPLY_CHARS` | phone | phone | op | phone.reply_choices |
| `rosty_core::settings::PrefsV2` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rosty_core::settings::REPLY_COUNTER_AT` | phone | phone | op | phone.reply_choices |
| `rosty_core::settings::SPEED_DIAL_DIGITS` | phone | phone | op | speed_dial.assign, speed_dial.free_digits |
| `rosty_core::settings::Settings` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rosty_core::settings::SortBy` | contacts,phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rosty_core::settings::SpeedDialEntry` | phone | phone | op | phone.prefs_load, phone.prefs_save |
| `rosty_core::settings::backup_file_name` | phone | platform | op | phone.backup_export |
| `rosty_core::settings::effective_keypad_tones` | phone | phone | op | phone.keypad_tones |
| `rosty_core::settings::haptic_allowed` | rosty_ui | phone | kotlin | :design Haptics.kt (per-tap check, main thread; Rust table mirrored as a JVM test) |
| `rosty_core::settings::reply_choices` | phone | phone | op | phone.reply_choices |
| `rosty_core::setup::ItemState` | phone | phone | op | setup.steps, setup.checklist |
| `rosty_core::setup::ItemStatus` | phone | phone | op | setup.steps, setup.checklist |
| `rosty_core::setup::OpenedThisSession` | phone | phone | op | setup.xiaomi |
| `rosty_core::setup::Reading` | phone | phone | op | setup.steps, setup.checklist |
| `rosty_core::setup::SetupChecklist` | phone | phone | op | setup.steps, setup.checklist |
| `rosty_core::setup::SetupFacts` | phone | phone | op | setup.steps, setup.checklist |
| `rosty_core::setup::SetupItem` | phone | phone | op | setup.steps, setup.checklist |
| `rosty_core::setup::XiaomiConfirmations` | phone | phone | op | setup.xiaomi |
| `rosty_core::setup::can_confirm` | phone | phone | op | setup.xiaomi |
| `rosty_core::setup::changed_title` | phone | phone | op | setup.reopened |
| `rosty_core::setup::still_reopened` | phone | phone | op | setup.reopened |
| `rosty_core::shared_card::CardField` | messages | messages | op | shared_card.read |
| `rosty_core::shared_card::CardView` | messages | messages | op | shared_card.read |
| `rosty_core::shared_card::insert_json` | messages | messages | op | shared_card.read |
| `rosty_core::shared_card::primary_number` | messages | messages | op | shared_card.read |
| `rosty_core::sim::DOT_MIN_CONTRAST` | phone | phone | op | sim.dot |
| `rosty_core::sim::SimAccount` | phone | phone | op | sim.build |
| `rosty_core::sim::SimInfo` | phone | phone | op | sim.build |
| `rosty_core::sim::argb_rgb` | phone | phone | op | sim.dot |
| `rosty_core::sim::clamp_dot` | phone | phone | op | sim.dot |
| `rosty_core::small_text::SmallText` | phone | input | kotlin | :design SmallText.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::sms::DeliveryState` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms::MmsPart` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms::MmsRow` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms::SmsBox` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms::SmsMessagesPayload` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms::SmsRow` | messages | messages | op | sms.to_message |
| `rosty_core::sms::SmsSend` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms::SmsSim` | messages | messages | none | provider payload: Kotlin reads the SIM list from SubscriptionManager; the per-thread choice is messages.prefs sim_choices |
| `rosty_core::sms::SmsSimsPayload` | messages | messages | none | provider payload: Kotlin reads the SIM list from SubscriptionManager; the per-thread choice is messages.prefs sim_choices |
| `rosty_core::sms::SmsStatus` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms::SmsThreadRow` | messages | messages | op | inbox.merge |
| `rosty_core::sms::SmsThreadsPayload` | messages | messages | none | provider payload: the bridge answers a list of SmsThreadRow, which inbox.merge takes |
| `rosty_core::sms::segments` | messages | messages | op | sms.segments |
| `rosty_core::sms_timeline::SmsItem` | messages | messages | op | sms_timeline.build |
| `rosty_core::sms_timeline::SmsKey` | messages | messages | op | sms.key, sms_timeline.build |
| `rosty_core::sms_timeline::SmsTimeline` | messages | messages | op | sms_timeline.build |
| `rosty_core::spam::RuleError` | messages | messages | op | spam.add_rule |
| `rosty_core::spam::SpamRule` | messages | messages | op | spam.add_rule, spam.insert_rule |
| `rosty_core::spam::SpamRules` | messages | messages | op | spam.classify, spam.remove_rule |
| `rosty_core::spam::contains_link` | messages | messages | op | spam.contains_link |
| `rosty_core::spam::open_links` | messages | messages | op | spam.open_links |
| `rosty_core::speed_dial::HoldDigit` | phone | phone | op | speed_dial.on_hold, speed_dial.assign, speed_dial.unassign, speed_dial.free_digits, speed_dial.digit_for |
| `rosty_core::speed_dial::badges` | phone | phone | op | speed_dial.badges |
| `rosty_core::starred::LocalStar` | messages | messages | op | starred.local |
| `rosty_core::starred::StarCandidate` | messages | messages | op | starred.rows |
| `rosty_core::starred::StarredRow` | messages | messages | op | starred.rows |
| `rosty_core::t9::MAX_HITS` | phone | t9 | op | t9.query |
| `rosty_core::t9::T9Hit` | phone | t9 | op | t9.query |
| `rosty_core::t9::T9Scratch` | phone | t9 | op | t9.query |
| `rosty_core::text_detect::DetectAction` | messages | messages | op | text_detect.detect |
| `rosty_core::text_edit::EditBuffer` | rosty_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_edit::History` | rosty_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_edit::Kind` | rosty_ui | input | kotlin | :design TextEdit.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_scale::ChatTextSize` | messages | input | kotlin | :design TextScale.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::ECHO_MS` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::EDGE_MAX` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::EDGE_ZONE` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::HANDLE_HIT` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::HANDLE_SIZE` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::INSERTION_MS` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::MENU_H` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::MENU_ITEM_PAD` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::MenuItem` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::MenuState` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::TapAction` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::drag_selection` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::edge_scroll` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::handle_visible` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::hits_caret` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::in_selection` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::keep_over_field` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::menu_items` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::menu_width` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::place_menu` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::resolve_tap` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::tap_byte` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_touch::teardrop` | rosty_ui | input | kotlin | :design TextTouch.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::CARET_MARGIN` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::ML_LINE` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::ML_PAD_V` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::box_height` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::capsule_radius` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::caret_visible` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::clamp_scroll` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::clamp_scroll_y` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::locate_paragraph` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::masked_index` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::next_toggle_ms` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::paragraph_for_row` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::paragraph_starts` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::scroll_for_caret` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::scroll_y_for_caret` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::selection_rects` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::unmasked_index` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::text_view::visible_lines` | rosty_ui | input | kotlin | :design TextView.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::thread_marks::SmsMarks` | messages | messages | op | thread_marks.edit |
| `rosty_core::thread_marks::TOO_MANY_PINS` | messages | messages | op | thread_marks.edit |
| `rosty_core::thread_marks::archive_notice` | messages | messages | op | thread_marks.edit |
| `rosty_core::thread_marks::pin_notice` | messages | messages | op | thread_marks.edit |
| `rosty_core::thread_marks::unread_notice` | messages | messages | op | thread_marks.edit |
| `rosty_core::unread_jump::JumpState` | messages | messages | op | unread_jump.state |
| `rosty_core::unread_jump::next_unread_label` | messages | messages | op | unread_jump.next |
| `rosty_core::vcard::CardPhoto` | contacts | contacts | op | contacts.vcard_parse, contacts.vcard_write, contacts.import_plan |
| `rosty_core::vcard::PhotoFormat` | contacts | contacts | op | contacts.vcard_parse, contacts.vcard_write |
| `rosty_core::voice_player::PlayerState` | messages | messages | op | voice_player.toggle |
| `rosty_core::voice_player::Speed` | messages | messages | op | voice_player.speeds |
| `rosty_core::voice_player::TogglePlan` | messages | messages | op | voice_player.toggle |
| `rosty_core::wire::AudioRoute` | phone | wa | op | audio.rows, audio.diff |
| `rosty_core::wire::AudioState` | phone | wa | op | audio.rows, audio.diff |
| `rosty_core::wire::BlockReason` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::BlockedNumber` | messages,phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::BlocklistPayload` | messages,phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::CallLogEntry` | contacts,phone | wa | op | recents.refresh_delta, call_log.counts |
| `rosty_core::wire::CallLogKind` | contacts,phone | wa | op | recents.refresh_delta, call_log.counts |
| `rosty_core::wire::CallLogPayload` | contacts,phone | wa | op | recents.refresh_delta, call_log.counts |
| `rosty_core::wire::CallSnapshot` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::ContactsPayload` | contacts,messages,phone | wa | op | search.index_new |
| `rosty_core::wire::DisconnectCause` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::DocumentOp` | contacts,messages,phone,stops | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::DocumentResult` | contacts,messages,phone,stops | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::EmergencyList` | messages,phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::Endpoint` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::EndpointHint` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::EndpointType` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::Envelope` | contacts,messages,phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::FavoritesPayload` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::Handoff` | messages,phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::HandoffAction` | messages | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::HapticsLevel` | phone,rosty_ui | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::NameFormat` | contacts,phone | wa | op | search.index_new |
| `rosty_core::wire::OpenSystemResponse` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::PendingOp` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::PhoneAccountInfo` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::PhoneAccounts` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::PhoneAccountsResponse` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::SettingsSync` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::SilencedBy` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::UiRequest` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::UiRequestKind` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::WireCall` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::WireCallState` | phone | wa | op | scene.of, scene.diff |
| `rosty_core::wire::WireContact` | contacts,messages,phone | wa | op | search.index_new |
| `rosty_core::wire::WireError` | contacts,messages,phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::WireNumber` | contacts,messages,phone | wa | op | search.index_new |
| `rosty_core::wire::WriteError` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::caps` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::parse_handoff` | messages,phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::wire::props` | phone | wa | none | GPUI-era service wire between rosty_android and its Kotlin service; the Kotlin apps call Telecom and the providers directly in :platform (E3) |
| `rosty_core::zoom::Point` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::zoom::Size` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_core::zoom::ZoomView` | contacts,messages,phone | input | kotlin | :design Zoom.kt (per-keystroke/per-frame, main thread; Rust tests mirrored as JVM tests) |
| `rosty_geo::ActiveTrip` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::AlarmPlan` | stops | stops | op | stops.alarm_plan |
| `rosty_geo::Appearance` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::Coord` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rosty_geo::DAY_MS` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::Diameter` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rosty_geo::DiameterError` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rosty_geo::EARTH_RADIUS_M` | stops | stops | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_geo::Endpoint` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::Feed` | stops | stops | op | stops.gtfs_import, stops.gtfs_search, stops.gtfs_view, stops.gtfs_lines, stops.gtfs_summary |
| `rosty_geo::FeedReport` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rosty_geo::Fix` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::FixIn` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::FixOut` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::FixPos` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::History` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::Measure` | stops | stops | op | stops.measure |
| `rosty_geo::NoticePrefs` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::NoticeState` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::NoticeWhen` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::Outcome` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::OutcomeFilter` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::PRUNE_CHOICES_DAYS` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::Pattern` | stops | stops | op | stops.gtfs_import, stops.gtfs_search, stops.gtfs_view, stops.gtfs_lines, stops.gtfs_summary |
| `rosty_geo::Route` | stops | stops | op | stops.gtfs_import, stops.gtfs_search, stops.gtfs_lines, stops.gtfs_summary |
| `rosty_geo::SavedStop` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::SavedStopError` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::SavedStops` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::Side` | stops | stops | op | stops.crossing_validate, stops.crossing_read |
| `rosty_geo::Stats` | stops | stops | op | stops.stats |
| `rosty_geo::Stop` | stops | stops | op | stops.gtfs_import, stops.gtfs_search, stops.gtfs_view, stops.gtfs_lines, stops.gtfs_summary |
| `rosty_geo::StopColour` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::StopsPrefs` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::TripRecord` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::TripState` | stops | stops | op | stops.trip_start, stops.trip_restore, stops.trip_state, stops.trip_fix, stops.trip_notice_result, stops.trip_snooze, stops.trip_timeout, stops.trip_dismiss, stops.trip_status_share, stops.trip_end |
| `rosty_geo::alarm::MIN_ALARM_VOLUME_PCT` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::arrival_clock` | stops | stops | op | stops.arrival_clock, stops.format_distance |
| `rosty_geo::backup::Backup` | stops | stops | op | stops.backup_export, stops.backup_parse |
| `rosty_geo::cadence::plan` | stops | stops | op | stops.cadence |
| `rosty_geo::coord_format::CoordFormat` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rosty_geo::coord_format::cardinal` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rosty_geo::coord_format::from_you_text` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rosty_geo::coord_format::map_view_share_text` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rosty_geo::coord_format::osm_view_url` | stops | stops | op | stops.coord_format, stops.coord_parse |
| `rosty_geo::dest::SharedPlace` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rosty_geo::dest::parse_location` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rosty_geo::dest::parse_stop_share` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rosty_geo::dest::point_qr_payload` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rosty_geo::dest::stop_share_text` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rosty_geo::history::format_duration` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::history_io::export_file_name` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::history_io::share_text` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::history_io::to_csv` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::history_io::to_gpx` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::measure::MAX_POINTS` | stops | stops | op | stops.measure |
| `rosty_geo::next_volume` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::notice::DEFAULT_TEMPLATE` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::notice::render` | stops | stops | op | stops.notice_render, stops.notice_validate |
| `rosty_geo::notice::validate` | stops | stops | op | stops.notice_render, stops.notice_validate |
| `rosty_core::wa_identity::display_name` (the arriving-soon picker's chats) | stops | stops | op | stops.notice_chats |
| `rosty_geo::places_io::MergePlan` | stops | stops | op | stops.places_export, stops.places_import |
| `rosty_geo::poll::MAX_POLL` | stops | stops | op | stops.poll |
| `rosty_geo::poll::MIN_POLL` | stops | stops | op | stops.poll |
| `rosty_geo::poll::retry_after` | stops | stops | op | stops.poll |
| `rosty_geo::prefs::AlarmSound` | stops | stops | op | stops.prefs_load, stops.prefs_save, stops.prefs_edit, stops.prefs_choices |
| `rosty_geo::prune_label` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::read_zip` | stops | stops | op | stops.gtfs_import, stops.gtfs_search |
| `rosty_geo::saved_stops::FORMAT_VERSION` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::saved_stops::NEAREST_CHIP_M` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::saved_stops::SavedSort` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::saved_stops::load_saved_stops` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::saved_stops::suggest_name` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::share_text` | stops | stops | op | stops.dest_parse, stops.dest_share |
| `rosty_geo::shortcut_spec` | stops | stops | op | stops.saved_load, stops.saved_edit, stops.saved_list, stops.saved_suggest_name, stops.saved_shortcut |
| `rosty_geo::status_share_text` | stops | stops | op | stops.pick_labels, stops.trip_status_share |
| `rosty_geo::tiles::AREA_MIN_ZOOM` | stops | tiles | op | tiles.offline_plan, tiles.offline_start |
| `rosty_geo::tiles::AREA_TILE_BUDGET` | stops | tiles | op | tiles.offline_plan, tiles.offline_start |
| `rosty_geo::tiles::ATTRIBUTION` | stops | tiles | op | stops.prefs_choices |
| `rosty_geo::tiles::AreaDownload` | stops | tiles | op | tiles.offline_start, tiles.offline_cancel |
| `rosty_geo::tiles::AreaPlan` | stops | tiles | op | tiles.offline_plan, tiles.offline_start |
| `rosty_geo::tiles::ClassGeometry` | stops | tiles | op | tiles.encode_mvt |
| `rosty_geo::tiles::DEFAULT_TILE_URL` | stops | tiles | op | stops.prefs_choices, tiles.open |
| `rosty_geo::tiles::DrawClass` | stops | tiles | op | tiles.encode_mvt |
| `rosty_geo::tiles::FetchQueue` | stops | tiles | kotlin | :stops TileScheduler.kt (dedupes view requests; every `tile` call is one blocking Rust read, offline downloads use four Rust workers) |
| `rosty_geo::tiles::LruIndex` | stops | tiles | op | tiles.open, tiles.usage, tiles.set_cap |
| `rosty_geo::tiles::MAX_TILE_ZOOM` | stops | tiles | op | tiles.offline_plan, tiles.offline_start |
| `rosty_geo::tiles::MapStyle` | stops | tiles | op | tiles.encode_mvt |
| `rosty_geo::tiles::Priority` | stops | tiles | kotlin | :stops TileScheduler.kt (view requests before prefetch) |
| `rosty_geo::tiles::Rgba8` | stops | tiles | op | tiles.encode_mvt |
| `rosty_geo::tiles::Shape` | stops | tiles | op | tiles.encode_mvt |
| `rosty_geo::tiles::TileCover` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_geo::tiles::TileGeometry` | stops | tiles | op | tiles.encode_mvt |
| `rosty_geo::tiles::TileId` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_geo::tiles::TileLayer` | stops | tiles | kotlin | :stops MapTileLayer.kt (per-frame transform of the RVT1 buffer to the canvas) |
| `rosty_geo::tiles::TileSource` | stops | tiles | op | tiles.open, tiles.test_server |
| `rosty_geo::tiles::TileTransform` | stops | tiles | kotlin | :stops MapTileLayer.kt (tile units to screen, same corner maths as view.project) |
| `rosty_geo::tiles::TileUrlError` | stops | tiles | op | tiles.open, tiles.test_server |
| `rosty_geo::tiles::build_layer` | stops | tiles | kotlin | :stops MapTileLayer.kt (clip and merge per frame; vertex budget by class) |
| `rosty_geo::tiles::display_zoom` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_geo::tiles::estimate_bytes` | stops | tiles | op | tiles.offline_plan, tiles.offline_start |
| `rosty_geo::tiles::frac_to_coord` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_geo::tiles::line_width` | stops | tiles | kotlin | :stops MapTileLayer.kt (`w14 * 2^clamp(zoom - 14, -3, 2)`, at least 0.5 px; width14 is in the buffer) |
| `rosty_geo::tiles::stand_in` | stops | tiles | op | tiles.stand_in |
| `rosty_geo::tiles::tiles_for_area` | stops | tiles | op | tiles.offline_plan, tiles.offline_start |
| `rosty_geo::tiles::tiles_for_circle` | stops | tiles | op | tiles.offline_plan, tiles.offline_start |
| `rosty_geo::tiles::tiles_for_view` | stops | tiles | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_geo::usage_text` | stops | stops | op | stops.history_list, stops.history_edit, stops.history_prune_plan, stops.history_usage, stops.history_recent, stops.history_export, stops.history_text |
| `rosty_geo::view::FollowMode` | stops | stops | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_geo::view::Viewport` | stops | stops | op | view.project, view.step, view.tiles, view.frac_to_coord, view.follow |
| `rosty_tiles::DEFAULT_CAP_BYTES` | stops | tiles | op | tiles.open |
| `rosty_tiles::DiskCache` | stops | tiles | op | tiles.open, tiles.usage, tiles.set_cap |
| `rosty_tiles::TileClient` | stops | tiles | op | tiles.open, tiles.test_server |
| `rosty_tiles::TileError` | stops | tiles | op | tiles.test_server |
| `rosty_tiles::clear_all` | stops | tiles | op | tiles.clear_all |
| `rosty_tiles::clear_except` | stops | tiles | op | tiles.clear_except |
| `rosty_tiles::disk_usage` | stops | tiles | op | tiles.usage |
| `rosty_tiles::load` | stops | tiles | op | tiles.open |
| `rosty_tiles::usage_by_source` | stops | tiles | op | tiles.usage_by_source |
| `rosty_core::phone_number::PhoneNumber::matches` | - | shared | op | phone_number.same |
| `rosty_core::calling_code::COUNTRIES` | - | shared | op | calling_code.list |
| `rosty_core::number_format::available` | - | shared | op | format.available |
| `rosty_core::number_format::copy_forms` | - | shared | op | format.copy_forms |
| `rosty_core::number_format::copied_notice` | - | shared | op | format.copied_notice |
| `rosty_core::number_format::render` | - | shared | op | format.render |
| `rosty_core::calling_code::country_of_cc` | - | shared | op | calling_code.of_cc |
| `rosty_core::calling_code::country_of` | - | shared | op | calling_code.country_of |
| `rosty_core::dial::normalise_paste` | phone | input | op | dial.normalise_paste |
| `rosty_core::dial::validate` | phone | input | op | dial.validate |
| `rosty_core::dial::extract_numbers` | phone | input | op | dial.extract_numbers |
| `rosty_core::dial::paste_plan` | phone | input | op | dial.paste_plan |
| `rosty_core::dial::redial_target` | phone | input | op | dial.redial_target |
| `rosty_core::dial::is_emergency` | phone | input | op | dial.is_emergency |
| `rosty_core::blocklist::parse_many` | phone | phone | op | blocklist.parse_many |
| `rosty_core::blocklist::parse_file` | phone | phone | op | blocklist.parse_file |
| `rosty_core::blocklist::numbers_line` | phone | phone | op | blocklist.lines |
| `rosty_core::blocklist::call_line` | phone | phone | op | blocklist.call_line |
| `rosty_core::recents::refresh_delta` | phone | phone | op | recents.refresh_delta |
| `rosty_core::recents::section_starts` | phone | phone | op | recents.section_starts |
| `rosty_core::inbox::person_for_number` | messages | messages | op | inbox.person_for_number |
| `rosty_core::inbox::person_for_jid` | messages | messages | op | inbox.person_for_jid |
| `rosty_core::inbox::preferred_channel` | messages | messages | op | inbox.preferred_channel |
| `rosty_core::sms_timeline::SmsKey::provider_id` | messages | messages | op | sms.key |
| `rosty_core::mms_pdu::decode_notification_ind` | messages | messages | op | mms_pdu.parse |
| `rosty_core::mms_pdu::decode_retrieve_conf` | messages | messages | op | mms_pdu.parse |
| `rosty_core::mms_pdu::encode_send_req` | messages | messages | op | mms_pdu.build_send_req |
| `rosty_core::mms_pdu::encode_notify_resp` | messages | messages | op | mms_pdu.build_notify_resp |
| `rosty_core::spam::classify` | messages | messages | op | spam.classify |
| `rosty_core::spam::links` | messages | messages | op | spam.links |
| `rosty_core::schedule::validate` | messages | messages | op | schedule.validate |
| `rosty_core::schedule::quick_times` | messages | messages | op | schedule.quick_times |
| `rosty_core::msg_backup::seal` | messages | messages | op | msg_backup.export |
| `rosty_core::msg_backup::open` | messages | messages | op | msg_backup.parse |
| `rosty_core::msg_backup::restore_plan` | messages | messages | op | msg_backup.plan |
| `rosty_core::multi_select::{title, copy_block, star_all, copied_notice, starred_notice, deleted_notice, cap_notice}` | messages | messages | op | multi_select.plan |
| `rosty_core::shared_card::is_vcard_mime` | messages | messages | op | shared_card.mime |
| `rosty_core::unread_jump::{unread_divider, divider_label, newly_arrived}` | messages | messages | op | unread_jump.divider |
| `rosty_core::voice_player::{progress, seek_target, time_label}` | messages | messages | op | voice_player.view |
