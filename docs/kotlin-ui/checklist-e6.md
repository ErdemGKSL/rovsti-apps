# E6 checklist record (Messages, R3 close)

The pass of `.claude/skills/convx-motion-compose/references/checklist.md` sections A-H over every Messages screen built in
E6 (`android/messages`): inbox and its folders (E6.2, E6.11, E6.13), the SMS/MMS and WhatsApp conversations (E6.3, E6.4a,
E6.4b), the bubble menu and select mode (E6.5), the conversation tools (E6.6), the viewers (E6.7), New Conversation,
forward and share-in (E6.8), scheduled send (E6.10), the settings pages and backup (E6.12), Starred and Remind me (E6.13).
Each item is `pass`, `fixed` or `deviation: <reason>`. Goldens are in `android/messages/screenshots/` (95 shots, light and
dark); the contact sheet `docs/kotlin-ui/screenshots/messages.png` was regenerated at the R3 close and opened.

## A. Licence and provenance
- pass: no GPUI, no Material. Every surface is a `:design` piece (`ScreenLazyPage`, `IosContextMenuEntry`, `SheetEntry`,
  `PopoverEntry`, `ActionSheetEntry`, `GlassTextField`, the undo pill).
- pass: the Messages files are Rosty code (GPL-3.0-or-later); the parts ported from the GPUI app name their source file in
  the KDoc (`BootReceiver`, the backup state machine from `backup_ui.rs`). Nothing new was ported from Convx in E6.
- pass: the domain work runs in Rust through `ChatOps`, `MessagesOps` and `SharedOps` (detection pills, OTP, multi
  select, unread jump, global search, schedule rules, spam rules, quick replies, reminder slots, `msg_backup`, starred
  rows); Kotlin keeps only wiring and the pure page state.

## B. Layout
- pass: a conversation and New Conversation hide the tab bar (`TabBarMode.Hidden`); the composer docks to the keyboard
  (`e6.3-sms-keyboard-open`, `e6.4-wa-keyboard-open`) with `imeOverlapPadding`, never `imePadding`.
- pass: the tab roots keep the large title and the bar; content clears the floating bar (`e6.2-inbox-end-above-bar`,
  `e6.13-starred-list`).
- pass: font scale 2 shots (`e6.4-wa-quoted-composer-fs2`, `e6.12-settings-fs2`) show no clipping; settings values stack
  under their titles by the shared row rule.
- fixed (R1 review): dark outgoing bubbles are the deep accent `MessagesColors.OutgoingBubble` with white text (contrast
  above 4.5:1); the bubble corner no longer looks clipped.
- pass: the Remind me popover opens below the bubble, or above it near the end of the screen, so the message stays
  readable (`e6.13-remind-me-popover`).
- deviation: at font scale 2 the shared context menu can overlap the lifted bubble (`:design` positioning, noted in R2).
- deviation: the tab-bar glass smear on the inbox shots is the `:design` tab bar, fixed on the main thread; Messages
  goldens are re-recorded when that fix is merged.

## C. Overlays
- pass: the bubble menu is an `IosContextMenuEntry` with the reaction row as quick actions; message info, Select Text,
  the contact card, the schedule sheet, the forward chooser and the backup sheet are sheets; jump to date and Remind me
  are popovers; Clear Chat and the delete flows use `confirmDestructive`; undoable actions show the undo pill.
- pass: overlays are siblings in the overlay host; opening one does not change the page layout.

## D. Motion
- pass: sheets and popovers use the design-system springs; swipe rows use the shared swipe with `fullSwipeFraction`.
- pass: a revealed message (global search hit, reminder tap, Starred row) scrolls into view and glows once
  (`e6.13-message-glow`).
- pass (E6.14 AC1): `ConversationRecompositionTest`: typing recomposes no bubble; scrolling a 2 000-bubble conversation
  composes each entering bubble once and recomposes none that stays; opening a bubble menu recomposes at most the
  pressed bubble.
- deviation: no shared-element flight from an inbox avatar into the conversation header; the conversation opens with the
  normal push.

## E. Input
- pass: long-press Send opens the schedule sheet; a swipe on a bubble quotes it; a double tap reacts (WhatsApp); paste
  of an image stages it above the composer (`contentReceiver`).
- pass: quick-reply chips sit above an empty composer and hide while typing (`e6.12-composer-chips`).
- pass: the backup passphrase uses a secure text field, typed twice; a weak passphrase is warned about once.

## F. Accessibility
- pass: every icon button and glyph has a label (star, reminder bell, ticks, select circles); select mode reads its
  count; Quick replies has move actions besides the drag handle.
- pass: no literal UI text (`ConventionTest`); English and Turkish strings for every page (`-tr` shots).
- pass: text size (M34) scales bubble text only; system font scale is honoured everywhere.

## G. Platform
- pass: no wall clock in UI code (`LocalRostyClock`), no raw dp outside the theme files (`ConventionTest`).
- pass: no Rust call before `Rust.init`; work runs in coroutines off the main thread.
- pass: R8 keeps the WorkManager worker (`proguard-rules.pro`); the manifest removes the default WorkManager
  initializer (the app is a `Configuration.Provider`).
- pass: the SMS role gates every write (restore, delete, clear chat, spam moves); without it the page says why.
- pass: scheduled SMS and message reminders are re-armed at boot and on app update (`BootReceiver`).
- deviation: WhatsApp stars made while offline wait in the app's own file and are flushed on reconnect, not by the
  WorkManager outbox (needs a Rust `wa_sync` star action); the Starred list reads recent chats only (needs a
  `wa_sync.starred` op).
- deviation: backups page every thread through `SmsRepository.messages` (a bulk text-only read would be a `:platform`
  addition); the round trip is tested on fakes, not on a device.

## H. Tests
- JVM and Robolectric: inbox, thread, WhatsApp, schedule, spam, notification, settings, backup, starred and reminder view
  model tests; `ConversationRecompositionTest`; `ChatZipTest`; `ScheduledGroupsTest`.
- Shots: `E61`..`E613ShotTest`, `E65ShotTest`, the WhatsApp shot tests; every golden opened at the round it landed, the
  contact sheet opened at the R3 close.
- Known flake: `MessageAnnouncerTest` on the firebase flavor can fail with an uncaught exception from `ShellContainer`'s
  init coroutine (`DevLog` reading SharedPreferences after Robolectric teardown); it passes on rerun. The fix belongs
  in `:shell`.
