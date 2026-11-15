# E6 Messages: status notes for the main thread

Newest round first. Branch `messages-kotlin`; merge it into `ui-overhaul` after a round is closed here.

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
