# E6 Messages: status notes for the main thread

Newest round first. Branch `messages-kotlin`; merge it into `ui-overhaul` after a round is closed here.

## E6-WA R1 closed (2026-11-15) at `7d24b484` plus this note: identity (LID, phone JID, one person)

Merged with `origin/ui-overhaul`. Gate: Rust steps green (fmt, clippy, 2 982 tests with `wa_gateway`, the Android checks);
the Gradle steps green **without the Stops tests** (`-x :stops:testReleaseUnitTest -x :stops:verifyRoborazziRelease`, as
in R5), APK check and compose stability included. The orphan-golden check lists only Stops goldens, because its shots
were not run here.
Plan and evidence: `plans/e6wa-r1.md`. Epic: `plans/e6wa-plan.md`.

**Gateway commits (deploy these; nothing was deployed from here)**: `9dab1c9f` (identity pairs, route, events, alt ids,
store migration v7, the fixes) and `7dc63514` (the sweep logs its duration). Proto and client: `fb215b14`.
The gateway code was never run against WhatsApp.

**What landed**
- Rust `rosty_core::wa_identity`: one parser and one identity map (canonical key: the number when known, else the LID),
  sources ranked, number changes, the merge steps.
- Gateway: learned pairs in `identity_pairs`, `GET /v1/identity/mappings`, events `identity_mapped` and
  `identity_number_changed`, `own_lid`, the other id of a chat, sender, reactor, participant, typing, presence, call
  and profile; LIDs seen only as authors, reactors, receipts, mentions and quotes are swept too.
- Device store (`wa_sync` schema v4): person, alias and merge log; every incoming id is resolved before it is
  matched; a pair learned later merges the two chats in one logged, replayable step (messages, stars, pin, archive,
  mute, unread, outbox); a read under the old key follows.
- Messages: drafts, mutes, notifications, reminders, scheduled texts and the open thread follow a merge (`WaRekeys`);
  a LID nobody names reads "WhatsApp user"; a contact is matched by every number of a person, current then former,
  national spellings in the home region.
- One Kotlin JID helper (`WaIds`, `:core-bridge`), checked against the Rust parser; the other copies are gone in
  Messages, Phone, Contacts, `:people`, `:wa`, `:platform`.
- Phone: a call from a LID dials the number of its alt id. No WhatsApp messages or media in Phone (owner).

**Shared code touched (own commits)**: `rosty_core` `7e8759d8`; proto and client `fb215b14`; `wa_sync` `55e44d01`,
`d29f12f8`, `15d22770`; `rosty_ffi` `7ea29482`; `:core-bridge` `1073af50`, `32e9f992`; `:people`, `:wa`, `:platform`
`293b3950`; Phone and Contacts `56114148`.

**Database backup before the gateway's v7 migration: yes, take one.**
- The migration is additive and runs in one transaction, so a failed migration leaves v6.
- There is no way back without a copy: an older gateway refuses a v7 database, and the first connect after the deploy
  rewrites rows (the LID sweep renames authors, reactions, receipts, quotes, mentions and profiles to the number).
- Copy `gateway.db` in the gateway's data directory (`WA_GATEWAY_DATA`) with the gateway stopped, or run
  `sqlite3 gateway.db ".backup gateway-v6.db"` while it runs. `instances/<id>/wa.db` is not migrated by this change.

**What to try on the real gateway, in order**
1. Read the log of the first connect: `lid-merge: sweep moved M of T stored LIDs in N ms`. `T` is how many LIDs the
   store held, `M` how many now have a number, `N` the time of one sweep. The line is written when the sweep found
   LIDs or took 250 ms or more; a sweep runs at every connect and on a device-list or number-change event. If `N` is
   regularly above about a second, tell this thread: the listing scans `messages` and needs an index or a rarer sweep.
2. `GET /v1/identity/mappings` with the connect key lists the pairs known at connect; `next` pages.
3. `GET /v1/instance`: `own_lid` is filled.
4. Open a group whose members show as LIDs (a community group): authors are numbers or names where a pair is known,
   and each participant carries `lid` and `pn` in the group info answer.
5. Send a reaction and a reply in a LID-addressed group from Rosty and look at the phone: the reaction sits on the
   right message and the reply quotes it (outbound keys now use the wire id; not verified on the wire).
6. Read a group message in Rosty: on the sender's phone the ticks turn blue once, not early.
7. Send a message from the phone to a contact whose chat is LID-addressed: it lands in the number chat, not a new one.

**What to test on a device (after the gateway deploy)**
1. A chat that showed twice (a number and a LID) is one row; its draft, mute and pin are on it.
2. Write a draft in a LID chat, let the pair arrive (open the group the person writes in): the draft is still there.
3. A group member with no saved contact and no WhatsApp name reads "WhatsApp user", never a long digit string.
4. A contact saved the national way (0532…) names its WhatsApp chat.

**For the main thread**
- A dev device that already opened an earlier build of this round holds an intermediate v4 store: clear the app's
  data once. v4 was never shipped, so users are not affected. Older app builds refuse a v4 store.
- `apps/*` no longer compiles with the new proto fields (not touched, by instruction).
- `:phone:lintPlainRelease` fails on `PhoneApp.kt:708` (`LocalContextGetResourceValueCall`); the line is not from
  this branch and the gate does not run that task.
- Left for WA-R2: Phone's own identity table (a LID caller without an alt id has no number), the rekey cursor reset on
  an account change, Rust `wa_digest` titles for a LID, the wire id on outbox sends.
- Own-device receipts: the chat cannot be recovered at whatsapp-rust 0.7.0.

## R5 closed (2026-11-15) at `50e05ebe`: name order follow-ups

Gate: Rust steps of `scripts/kotlin-ui-check.sh` green; the Gradle steps green **without the Stops tests**
(`-x :stops:testReleaseUnitTest -x :stops:verifyRoborazziRelease`), APK check, compose stability and orphan goldens
included. Two full runs each failed on a different Stops test (`SavedViewModelTest.noPositionNoDistance`, then the
golden of `E75ShotTest.preflightSheet` dark); the first passes alone. Stops is not this branch's module.
Plan and evidence: `plans/e6-r5.md`.

**What landed**
- The Starred list is read again when a name changes.
- `LiveWaNames` answers persist across restarts (`:people`, `LiveNameStore`), wired in Messages and Phone.
- Group lines of a closed-app push name their writers by the rule; Rust `wa_digest` adds `senders` for a group
  (no gateway change).
- Dropped by the owner: making `apps/*` compile again (nothing committed there).

**Shared code touched (own commits)**: `:people` `328b1cae`, Phone wiring `50e05ebe`, Rust `wa_digest` `351c0534`.

**What to test on a device**
1. Ask a name for an unknown number, kill the app, open it: the name is there at once and no lookup is sent.
2. A group message arrives with the app closed (firebase build): the lines read "Name: text", a saved contact first.
3. Save a contact for a starred WhatsApp message's writer: the Starred row takes the name.

## R4 closed (2026-11-15) at `184fd173`: cross-layer follow-ups and the name order

Merged with `origin/ui-overhaul` (`e823684`, gateway host). Gate: `scripts/kotlin-ui-check.sh` ALL GREEN (Rust steps at
`f947682b`, Gradle at `c323c2ad`); after the last merge the Rust and Kotlin tests of the touched modules pass. Plan and
evidence: `plans/e6-r4.md`.

**What landed**
- (a) Rust `wa_sync`: outbox Star/Unstar and `wa_sync.starred`; the Starred page and stars use them (sent with the app
  closed, all chats listed). Store schema is now v3 (older builds refuse a v3 store).
- (b) `:platform` bulk text-only SMS/MMS read; the backup uses it; a seal/open/restore round trip on the real Rust core.
- (c) `:design` `GlassSecureTextField` and `ActivityIndicator`; the backup sheet uses them.
- (d) `:shell` `startsDevLogAtCreate()` seam; the Messages test container opts out (the `MessageAnnouncerTest` flake).
- Name order on every Messages surface: contact, WhatsApp name (verified, push, stored chat name), readable number;
  unknown numbers are asked through `LiveWaNames` and swap in.

**Shared code touched (own commits)**: `:design` (`a333b98c`), `:shell` (`72abebe0`), `:platform` (in `1618dc35`),
`:core-bridge` and Rust `wa_sync` / `rosty_ffi` (in `b1b67edf`).

**For the main thread**
- `cargo clippy --workspace` and `cargo test --workspace` do not compile on this base: the GPUI apps `apps/messages`,
  `apps/phone`, `apps/contacts` miss `WireNumber.normalized`, the `marked_contacts` arity and `MessageDeleted` fields.
  Not from this branch; the Rust checks were run on the gate's package set.
- A refused star is undone and dropped (the other outbox actions stay as failed rows); pending stars of an older
  install's `wa-stars.json` are not migrated.
- Name order leftovers: the Starred list re-reads names on store changes only; `LiveWaNames` answers are memory-only
  (asked again after a process start, paced); group lines inside the closed-app push keep the core's sender names.
- Not on a device yet: backup/restore, the star outbox job with the app closed, the live name swap.

**What to test on a device**
1. Unknown number sends an SMS: the row and header show the number, then the WhatsApp name; save the contact: its name wins.
2. WhatsApp chat of a saved contact with a different push name: the contact name everywhere (inbox, header, notification).
3. Star a WhatsApp message in airplane mode, close the app, go online: it is starred on the account; Starred lists old stars.
4. Backup with thousands of messages: export time, restore preview counts, a second restore adds nothing.

## R3 closed (2026-11-15) at `c2ea78f9`, last E6 round

Merged with `origin/ui-overhaul`: nothing new since R2 (the `:design` tab-bar glass fix is not there yet; when it lands,
merge it here and re-record the Messages goldens it changes; the inbox and settings shots carry the tab bar). Gate:
`SKIP_RUST=1 scripts/kotlin-ui-check.sh` ALL GREEN (no Rust crate or gateway changed in R3). E6.1-E6.14 are all done
in `epics.md`. Checklist: `docs/kotlin-ui/checklist-e6.md`; contact sheet: `docs/kotlin-ui/screenshots/messages.png`.

**What landed**
- E6.12 settings in GPUI's order: Notifications (switches, muted conversations), Quick replies (M11, chips above an
  empty composer), Text size (M34), encrypted Backup export/restore with preview (AD-MSG7, restore needs the SMS role).
- E6.13 Star/Unstar (SMS local, WhatsApp via gateway), Starred page (inbox folder row), Remind me (four Rust slots,
  bubble badge, notification tap glows the message, re-armed at boot).
- E6.6 M48 export with media (.zip), M27 Clear Chat; E6.7 M4 header photo; E6.10 inbox Scheduled overview; E6.11 Not
  spam / Move to Spam on the SMS header; E6.14 recomposition tests, readable WhatsApp header number.

**Shared code touched**: none (all changes are in `android/messages` and the plan docs).

**Needs a Rust, `:platform`, `:design` or `:shell` change (not done here)**
- Rust `wa_sync`: an outbox Star action (offline WhatsApp stars now wait in the app's file) and a `starred` query
  (the Starred list reads recent chats only); `quick_replies.edit` in place.
- `:platform`: a bulk text-only SMS/MMS read for backups.
- `:design`: secure mode for `GlassTextField`, an activity spinner, a bolt icon; context menu overlap at font scale 2.
- `:shell`: `ShellContainer`'s init coroutine can touch SharedPreferences after Robolectric teardown
  (`MessageAnnouncerTest` flake on the firebase flavor, passes on rerun).

**What to test on a device**
1. Settings > Backup: export with a passphrase, restore it on the same phone (preview counts, nothing doubled).
2. Settings > Quick replies: add, reorder, use a chip in a chat; Text size Larger changes the bubbles.
3. Star an SMS and a WhatsApp message; open Starred from the inbox and tap a row (the message glows).
4. Remind me in 15 minutes on a message; tap the notification (the chat opens on it); reboot with one armed.
5. Chat menu: Export with Media (zip), Clear Chat; tap the header photo; inbox Scheduled row with two scheduled SMS.
6. WhatsApp chat with an unsaved number: the header reads `+90 5xx xxx xx xx`.

## R2 closed (2026-11-15) at `8d1b7e72`

Merged with `origin/ui-overhaul` (nothing new since R1's merge `7120032`). Gate: `SKIP_RUST=1 scripts/kotlin-ui-check.sh`
ALL GREEN (no Rust crate changed in R2). The gate now also runs `:messages` tests, lint and screenshot verify
(`8d1b7e72`); before, the flavored module was skipped by `testReleaseUnitTest`/`verifyRoborazziRelease`.

**What landed**
- E6.4b WhatsApp media (received through the store's media cache, send with optimistic bubble, retry, 16 MiB rule,
  paste), voice notes with speed and scrubbing, double-tap and menu reactions with recent emoji, swipe to reply with the
  quoted composer, group sender mini card.
- E6.5 message menu (lifted bubble, panel below or above, reaction row for WhatsApp), Copy, Select Text, Forward,
  Select (block copy/forward/delete), Info, detection pills (call, message, e-mail, calendar, maps, link, Copy code).
- E6.9 notifications from the core's digest spec, inline reply (attached: repository/outbox; cold: headless), Copy code,
  Mark as read, Remind me in 1 hour, no notification for the open conversation, FCM wake (firebase).
- E6.10 long-press Send → Send later sheet, Scheduled strip, SMS alarms, WhatsApp gateway scheduled items.
- E6.11 spam filter page (rules, allow list, system blocklist, M47), Spam folder with Not spam / Move to Spam / Block.
- E6.3 MMS pictures (picker, paste) through the shared attachment bar; E6.7 contact card sheet and Share for files;
  E6.6 mute and next unread chat in the SMS header menu.
- R1 review notes: my bubbles deep blue with white text in both themes, rounded run-end corner.

**Shared code touched (own commit)**: `scripts/kotlin-ui-check.sh` (the `:messages` tasks next to `:phone`'s).

**What to test on a device**
1. Long-press a bubble: menu below/above, WhatsApp reaction row; Select several and Copy; tap a number pill (dialer).
2. WhatsApp: send a photo offline (clock), reconnect; play a voice note at 1.5×; swipe a bubble to reply.
3. Notifications: receive an SMS and a WhatsApp message with the app closed, reply from the notification; open the
   conversation and check no new notification appears for it.
4. Long-press Send → schedule for in 1 hour; cancel one from the strip.
5. Settings → Spam filter: add a rule `PROMO*`, block a number, Move a chat to Spam and back.

**Needs the main thread**: nothing to deploy, no gateway change. Played receipts and offline chat actions need a
`wa_sync` outbox action; recent emoji and M47 have no field in `messages-prefs.json` (own files for now); the shared
context menu has no room at font scale 2 and overlaps the lifted bubble.

**Carried to R3**: E6.12 settings and backup, E6.13 starred and reminders, E6.14 polish; M48 zip export, M27 clear
chat, M4 header photo, inbox Scheduled overview, Not spam / Move to Spam on the thread header, unformatted number in
the WhatsApp header subtitle.

## R1 closed (2026-11-14) at `3f313b6`

Merged with `origin/ui-overhaul` (nothing new since `9e3432e`). Gate: `scripts/kotlin-ui-check.sh` ALL GREEN (Rust steps
in the first run, Gradle steps re-run after the fixes with `SKIP_RUST=1`); clippy `-D warnings` and tests of the
Kotlin-facing crates clean; `cargo check --release -p rosty_android --target aarch64-linux-android` clean. Workspace-wide
`cargo clippy --all-targets` fails in `apps/messages` tests (the GPUI app: `WireNumber.normalized`, `deleted_at`/
`deleted_by` patterns), which is already so on `ui-overhaul`; this branch does not touch `apps/` or `rosty_core`.

**What landed**
- `:messages` app module (`dev.rosty.messages`, plain and firebase flavors): SMS role components, receivers ported onto
  `:platform`, deep links (`sms:`/`smsto:`/`mms:`/`mmsto:`, SEND, `WA_CHAT` handoff, notification tag), default-SMS ask,
  Settings root with access rows, the WhatsApp row and the common rows (developer mode: hold Settings → About → Version
  for 10 s, Logs page).
- Unified inbox: one row per person, chips, pins, archive and spam folders, mute, mark read/unread, row menu, swipe
  actions, select mode with the bulk toolbar in the tab bar's place, drafts, OTP "Copy" chip, global search with a
  Messages section (hits open their conversation on the message).
- SMS/MMS conversation: Rust timeline replay, delivery states, dual SIM, 4 s undo send, drafts, failed-message menu,
  delete with Undo, image viewer, find, jump to date, gallery grid, export/share, unread divider, jump-to-latest count,
  forward.
- WhatsApp offline store (E6.4s): `rosty_app_core::wa_sync` (SQLite), `wa_sync.*` ops, `WaSyncStore`; in the app the
  WhatsApp tab and threads read only the store, the sync fills it, an outbox queues sends/reactions/edits/revokes/read
  marks and a WorkManager job (CONNECTED) drains it. Status row and Status viewer.
- New conversation (number or contact, SMS or WhatsApp), share-in, forward chooser.

**Shared code touched (own commits)**
- `9fcf183`: `settings.gradle.kts` includes `:messages`; `:shell` permission rows usable inside settings groups
  (`permissionSettingsItem`, `specialAccessSettingsItem`, public `PermissionIconTile`); `:platform` Messages permission
  table (VIBRATE, people sync); `docs/kotlin-ui/architecture.md` §7.5.
- `3aaeecb`, `2a5613a`, `bf2bf41`: `rosty_app_core::wa_sync`, `rosty_ffi` `wa_sync.*` ops and fixtures, `:core-bridge`
  `WaSyncOps`/`WaSyncStore` and `FakeWaSyncOps`. These three carry a different Co-Authored-By line than the session's
  (written by a subagent); left as pushed.

**What to test on a device**
1. Set Messages as the default SMS app from the inbox card; receive and send an SMS, undo a send within 4 s, dual SIM.
2. Inbox: swipe a row, long-press menu, select mode, archive + Undo, search a word that only appears in a message body
   and tap the hit (the thread scrolls to it and it glows).
3. Link WhatsApp (connect key), open a chat, turn on airplane mode, send two messages (clock, "Waiting for connection"),
   turn the network back on (they go out once, no doubles). Kill the app while offline, reconnect: the WorkManager job
   sends them.
4. Status row → viewer: tap left/right, hold to pause, drag down to close.
5. Forward an SMS to a WhatsApp chat (opens with the text in the composer).

**Needs the main thread**
- No gateway change and nothing to deploy. The sync opens a second event socket per instance (`<instance>#sync`)
  because `:wa`'s `WaRepository.events` has no `seq`/`after`; one stream would be better (a `:wa` change: take `after`
  from the store, expose `WaStreamEvent.Event`).
- `rosty_ffi` now links bundled SQLite (C, built with the NDK clang). `docs/kotlin-ui/architecture.md` 6.7 says a
  C-toolchain dependency is refused in review; the E6.4s spec asks for rusqlite bundled, so that line needs amending.
- Launcher icon: vector placeholder; the glass art needs image generation, which this container does not have.
- `:design` `IosSwipeableRow`: a drag that rests before release still flings with the velocity of its last move (the
  tracker only sees moves), so a slow half swipe can commit the full-swipe action. Not changed here (shared code).

**Carried to R2**: E6.3 MMS attachment send, M46 paste image, unblock row; E6.6 M50 sender mini card, M43 next unread,
M48 zip export, M27 clear chat, M29, M22; E6.7 M26 contact card sheet, M28 attachments, M4 header photo, attachment
menu; WhatsApp media (thumbnails, downloads, `wa_sync` media LRU) with E6.4b; offline chat actions (archive/pin/mute
need a `wa_sync` outbox action); Status updates are not stored offline.
