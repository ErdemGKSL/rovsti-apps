# E6 Messages: status notes for the main thread

Newest round first. Branch `messages-kotlin`; merge it into `ui-overhaul` after a round is closed here.

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
