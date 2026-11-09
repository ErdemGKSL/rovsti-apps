# Platform port map

One row per Kotlin source of the GPUI build. Source of truth for E8.6, which deletes the originals once every row is
`ported` or `dropped`. Status words: `ported`, `dropped: <reason>`,
`app module E<n>`. No row is `todo` or waits for a later round: E3 is done. Plan: `_bmad-output/kotlin-ui/plans/round-08.md`; design: `docs/kotlin-ui/architecture.md` 7.3.

Rules: `:platform` keeps each file's logic, constants, intent actions, extras, pref keys and channel ids; the Rust seams are
`PlatformEvents` (events out), `Headless.call` (blocking decisions) and `:core-bridge` facades. Components named by the manifests
keep `dev.rovsti.bridge.<Name>` (ARCH 7.2); `FqcnTest` lists the ones ported so far:
`CallReminderReceiver`, `RovstiShareProvider`, `MessageReminderReceiver`, `MmsFileProvider`, `MmsResultReceiver`, `ReplyReceiver`,
`ScheduledSmsReceiver`, `SmsStatusReceiver`, `BirthdayReminderReceiver`, `AlarmActivity`, `TripAlarmReceiver`, `TripService`,
`TripWatchdogReceiver`. `RovstiShareProvider` is the E3.8a share provider (ported to `platform/files/RovstiShareProvider.kt`).

## Bridge files (`crates/rovsti_android/kotlin/dev/rovsti/bridge/`)

| Source | Destination | Step | Status |
|---|---|---|---|
| `AlarmActivity.kt` | platform.location.BridgeTripComponents (package dev.rovsti.bridge) | 7 (E3.7) | ported |
| `AlarmOptions.kt` | platform.location.AlarmOptions (public; `REQUEST_ALARM_SOUND` const replaces `Documents.REQUEST_ALARM_SOUND`) | 7 (E3.7) | ported |
| `AlarmSound.kt` | platform.location.AlarmSound (prefs via `PlatformPrefs.ALARM`) | 7 (E3.7) | ported |
| `BirthdayReminders.kt` | platform.notify.BirthdayReminders | 6 (E3.6) | ported |
| `Blocklist.kt` | platform.calllog.Blocklist (typed `BlockedNumber`/`BlockReason`; `numbers` replaces `pullJson`; `BlockGuard` comes along) + `BlocklistRepository` | 3 (E3.3) | ported |
| `CallLogQuery.kt` | platform.calllog.CallLogQuery (typed `CallLogEntry`; `byIds` and `missedCount` added) | 3 (E3.3) | ported |
| `CallLogWrite.kt` | platform.calllog.CallLogWrite (typed rows; restore also writes name, label, contact and block reason) | 3 (E3.3) | ported |
| `CallReminders.kt` | platform.notify.CallReminders | 6 (E3.6) | ported |
| `CallsApi.kt` | platform.telephony.CallsApi | 5 (E3.5) | ported |
| `ContactOptions.kt` | platform.contacts.ContactCallOptions (renamed: `ContactOptions` is the `:core-bridge` model it returns; the picker is an intent plus `parsePicked`, no `startActivityForResult`) | 2 (E3.2) | ported |
| `ContactPhotos.kt` | platform.contacts.ContactPhotos (typed `PhotoPlan`, `ContactResult`) | 2 (E3.2) | ported |
| `ContactsEdit.kt` | platform.contacts.ContactsEdit (typed `ContactDraft`, `EDITED_MIMES` kept, JSON envelope dropped) | 2 (E3.2) | ported |
| `ContactsQuery.kt` | platform.contacts.ContactsQuery (rows grouped into `WireContact`) | 2 (E3.2) | ported |
| `Documents.kt` | platform.files.Documents (limits and pure helpers kept) plus `Contracts.kt` (`ExportDocument`, `ImportDocument`, `PickPhoto`, `CapturePhoto`) and `FilesRepository`; `Documents.start*`, request codes and the JSON result are replaced by `FileResult` | 8a (E3.8a) | ported |
| `Epic17Probes.kt` | none | 1 (E3.1) | dropped: GPUI spikes (ARCH 7.3) |
| `Handoff.kt` | platform.system.Handoff | 1 (E3.1) | ported |
| `Haptics.kt` | platform.system.Haptics | 1 (E3.1) | ported |
| `Headless.kt` | platform.Headless (blocking wrapper over Rust.core().headless) | 1 (E3.1) | ported, rewritten |
| `KeypadTone.kt` | platform.telephony.KeypadTone | 5 (E3.5) | ported |
| `Mailbox.kt` | none: one-shots become intent handling in the activity | 7 (E3.11) | dropped: one-shots become intent handling in the activity (ARCH 7.3, E3.11) |
| `MediaShare.kt` | platform.files.MediaShare (names, share dir, `mimeFor`, gallery save, staging) plus `ShareIntents`; `RovstiShareProvider` (package `dev.rovsti.bridge`) is `files/RovstiShareProvider.kt` | 8a (E3.8a) | ported |
| `MessageNotifications.kt` | platform.notify.MessageNotifications | 6 (E3.6) | ported |
| `MessageReminders.kt` | platform.notify.MessageReminders | 6 (E3.6) | ported |
| `MissedCalls.kt` | platform.calllog.MissedCalls; LocaleTimeInfo is platform.system.LocaleTimeInfo | 3 (E3.3), LocaleTimeInfo in 1 | ported |
| `MmsCodec.kt` | platform.sms.MmsCodec | 4 (E3.4) | ported |
| `MultiCall.kt` | platform.telephony.MultiCall | 5 (E3.5) | ported |
| `NotificationIcon.kt` | platform.notify.NotificationIcon | 6 (E3.6) | ported |
| `ProviderObservers.kt` | platform.ProviderChanges (callbackFlow) | 1 (E3.1) | ported, rewritten |
| `RejectPolicy.kt` | platform.telephony.RejectPolicy; `FlipRule` (same file) goes to platform.telephony.FlipDetector | 3 (E3.3), FlipRule in 5 | ported (`FlipRule` lives in FlipDetector.kt) |
| `ReplyReceiver.kt` | platform.notify.ReplyReceiver | 6 (E3.6) | ported |
| `RovstiBridge.kt` | none: the Rust-calls-Kotlin methods become repositories; emit becomes PlatformEvents | 1 (E3.1) | dropped: replaced by seams |
| `ScheduledSms.kt` | platform.sms.ScheduledSms | 4 (E3.4) | ported |
| `SettingsMirror.kt` | platform.system.SettingsMirror | 1 (E3.1) | ported |
| `Shortcuts.kt` | platform.contacts.Shortcuts + `ShortcutsRepository` (target activity from `PlatformComponents`; `EXTRA_CONTACT_ID` is now owned here, `BirthdayReminders` in step 6 reads it) | 2 (E3.2) | ported |
| `SimAccounts.kt` | platform.telephony.SimAccounts (returns `PhoneAccounts`; the JSON envelope is dropped) + `SimRepository` | 3 (E3.3) | ported |
| `SimProbe.kt` | platform.telephony.SimProbe (S5 lines only; the S14 vibration half went with the spikes) | 3 (E3.3) | ported |
| `SmsQuery.kt` | platform.sms.SmsQuery | 4 (E3.4) | ported |
| `SmsSend.kt` | platform.sms.SmsSend | 4 (E3.4) | ported |
| `SpikeProbes.kt` | none | 1 (E3.1) | dropped: GPUI spikes (ARCH 7.3) |
| `StopsLocation.kt` | platform.location.LocationRepository (`lastKnown`, `fresh(timeout)`, `updates()` replace the `stops_location` emits; `Fix` model) | 7 (E3.7) | ported |
| `SystemPages.kt` | platform.system.SystemPages | 1 (E3.1) | ported |
| `TripAlarm.kt` | platform.location.TripAlarm + `TripAlarmReceiver` in BridgeTripComponents (channel via `PlatformChannels`, events via `PlatformEvents`) | 7 (E3.7) | ported |
| `TripService.kt` | platform.location.TripModel (pure helpers, `StartResult`) + `TripService`/`TripWatchdogReceiver` in BridgeTripComponents + `TripRepository` (`status`, `start`) | 7 (E3.7) | ported |
| `VoicePlayer.kt` | platform.telephony.VoicePlayer | 5 (E3.5) | ported |
| `WaCallAudio.kt` | platform.wa.WaCallAudio (+ `CallFrames`/`CallAudio.asFrames()`: frames go to the core-bridge handle `audioPush`/`audioPull` as `ByteArray`, no base64; a `wa-mic` and a `wa-speaker` thread) | 8 (E3.9 seams) | ported |
| `WaIncoming.kt` | platform.wa.WaIncoming (channels via `PlatformChannels`; `takeAction(context, intent)` replaces `RovstiBridge.deliverWaCallAction`) | 8 (E3.9 seams) | ported |
| `WaPush.kt` | platform.wa.WaPush (`onMessage(context, data)` emits `wa_push` on `PlatformEvents`; `:wa` calls `reconnectNow`) | 8 (E3.9 seams) | ported |
| `WireJson.kt` | platform.wire.WireJson | 1 (E3.1) | ported |
| `XiaomiOps.kt` | platform.system.XiaomiOps | 1 (E3.1) | ported |

## App host files (`apps/<app>/platforms/android/app/src/main/kotlin/dev/rovsti/<app>/`)

| Source | Destination | Step | Status |
|---|---|---|---|
| `apps/phone/.../phone/AudioPath.kt` | platform.telephony.AudioPath | 5 (E3.5) | ported |
| `apps/phone/.../phone/CallActionReceiver.kt` | app module E5 (component stays in the app; calls `:platform`) | E5 | app module E5 |
| `apps/phone/.../phone/CallEventRing.kt` | platform.telephony.CallEventRing | 5 (E3.5) | ported |
| `apps/phone/.../phone/CallNotifications.kt` | platform.telephony.CallNotifications | 5 (E3.5) | ported |
| `apps/phone/.../phone/CallRegistry.kt` | platform.telephony.CallRegistry | 5 (E3.5) | ported |
| `apps/phone/.../phone/CallSurface.kt` | platform.telephony.CallSurface | 5 (E3.5) | ported |
| `apps/phone/.../phone/Caps.kt` | platform.telephony.Caps | 5 (E3.5) | ported |
| `apps/phone/.../phone/DialIntents.kt` | platform.telephony.DialIntents | 5 (E3.5) | ported |
| `apps/phone/.../phone/FlipDetector.kt` | platform.telephony.FlipDetector | 5 (E3.5) | ported |
| `apps/phone/.../phone/IncomingCall.kt` | platform.telephony.IncomingCall | 5 (E3.5) | ported |
| `apps/phone/.../phone/IncomingPerf.kt` | stays in `:phone` (E5); `:platform` only has the `IncomingTiming` seam that `CallRegistry.timing` calls | E5 | app module E5 |
| `apps/phone/.../phone/MainActivity.kt` | app module E5 (Application/MainActivity on `:shell`) | E5 | app module E5 |
| `apps/phone/.../phone/NativeIncoming.kt` | stays in `:phone` (E5); GPUI-era native incoming-screen hand-off | E5 | app module E5 |
| `apps/phone/.../phone/OngoingCall.kt` | platform.telephony.OngoingCall | 5 (E3.5) | ported |
| `apps/phone/.../phone/ProximityController.kt` | platform.telephony.ProximityController | 5 (E3.5) | ported |
| `apps/phone/.../phone/RebuildCoalescer.kt` | platform.telephony.RebuildCoalescer | 5 (E3.5) | ported |
| `apps/phone/.../phone/RovstiInCallService.kt` | app module E5 (component stays in the app; calls `:platform`) | E5 | app module E5 |
| `apps/phone/.../phone/RovstiPhoneApp.kt` | app module E5 (Application/MainActivity on `:shell`) | E5 | app module E5 |
| `apps/phone/.../phone/VoicemailReceiver.kt` | app module E5 (component stays in the app; calls `:platform`) | E5 | app module E5 |
| `apps/messages/.../messages/BootReceiver.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/ComposeRequest.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/HeadlessSmsSendService.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/IncomingNotice.kt` | platform.sms.IncomingNotice | 4 (E3.4) | done |
| `apps/messages/.../messages/MainActivity.kt` | app module E6 (Application/MainActivity on `:shell`) | E6 | app module E6 |
| `apps/messages/.../messages/MmsInbox.kt` | platform.sms.MmsInbox | 4 (E3.4) | done |
| `apps/messages/.../messages/MmsWapPushReceiver.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/RovstiMessagesApp.kt` | app module E6 (Application/MainActivity on `:shell`) | E6 | app module E6 |
| `apps/messages/.../messages/SmsDeliverReceiver.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/stops/.../stops/BootReceiver.kt` | app module E7 (component stays in the app; calls `:platform`) | E7 | app module E7 |
| `apps/stops/.../stops/MainActivity.kt` | app module E7 (Application/MainActivity on `:shell`) | E7 | app module E7 |
| `apps/stops/.../stops/RovstiStopsApp.kt` | app module E7 (Application/MainActivity on `:shell`) | E7 | app module E7 |
| `apps/contacts/.../contacts/MainActivity.kt` | `:contacts` `dev.rovsti.contacts.MainActivity : RovstiShellActivity` | E4 | ported (E4.1) |
| `apps/contacts/.../contacts/RovstiContactsApp.kt` | `:contacts` `dev.rovsti.contacts.RovstiContactsApp : HasContainer<ContactsContainer>` | E4 | ported (E4.1) |

## Tests

| Source | Destination | Step | Status |
|---|---|---|---|
| `apps/phone/.../src/test/java/dev/rovsti/phone/{IncomingCall,OngoingCall,AudioPath,ProximityPolicy,CallEventRing,DialIntents,RebuildCoalescer}Test.kt` | `:platform` test `telephony.<same name>` (the `IncomingTimeline` case stays with `IncomingPerf` in `:phone`) | 5 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/WireFixturesTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/WireV2Test.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/LocaleTimeInfoTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/SystemPagesTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/XiaomiOpsTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/Epic16Test.kt` | SettingsMirrorTest (mirror and system-page cases); the Mailbox, tone, SIM and document cases go with their classes | 1, 3, 5 | partly moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/MessagesSeamTest.kt` | MessagesSeamTest (Headless cases, FakeRustCore); SMS/MMS/notification/scheduled cases go with E3.4/E3.6 | 1, 4, 6 | partly moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/ContactPhotosTest.kt` | `:platform` test `contacts.ContactPhotosTest` (plan cases now on `PhotoPlan`) | 2 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/MultiCallTest.kt` | `:platform` test `telephony.MultiCallTest` | 5 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/WaIncomingTest.kt` | `:platform` test `wa.WaIncomingTest` (plus frame-size, takeAction and wake-event cases) | 8 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/Epic17Test.kt` | BlocklistTest (blocklist, reason and BlockGuard cases), RejectPolicyTest (reject cases); the FlipRule cases are `telephony.FlipRuleTest` (step 5); the Epic17Probes cases (blocking, reject, silence probes) are dropped with the GPUI spikes | 3, 5 | moved; probe cases dropped |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/SpikeProbesTest.kt` | dropped with its GPUI spike (recorded again in step 3: nothing of it is ported) | 1 | dropped |

## Call log, blocklist, SIM repositories (E3.3, step 3)

`dev.rovsti.platform.calllog`: `CallLogRepository` (`entries(limit)`, `blocked(limit)`, `missedCount()` are cold `Flow`s
that query on collect and again after a `CallLog.Calls.CONTENT_URI` change through the shared `liveQuery` helper: debounced
by `CHANGE_DEBOUNCE_MS`, so a burst is one re-query; `delete(ids)` reads the rows first and answers a `Restorable`,
`restore(rows)` puts them back as read, `deleteAll()`, `markMissedRead()`; writes answer a sealed `CallLogResult`),
`AndroidCallLogRepository(context, io)`, `FakeCallLogRepository`. `BlocklistRepository` (`numbers()` flow of the
`:core-bridge` `BlockedNumber`, `block` keeps the emergency/blank refusal and answers `Blocklist.Result`, `unblock`,
`canBlock`, `isBlocked`), `AndroidBlocklistRepository(context, io, provider, isEmergency)`, `FakeBlocklistRepository`.
`dev.rovsti.platform.telephony`: `SimRepository` (`accounts()` flow of `PhoneAccounts`, re-read on a
`SubscriptionManager` listener or default-subscription broadcast, empty without `READ_PHONE_STATE`; `probe(placed, log)`),
`AndroidSimRepository(context, io, changes, collect)`, `FakeSimRepository`.

- The plan said `accounts(): Flow<List<SimAccount>>`; `SimAccount` in `:core-bridge` is the chip Rust builds from the
  accounts (`sim.build`), so the repository emits the input of that op, `PhoneAccounts` (accounts, default, ask).
- `missedCount()` is a flow (the tab badge is live); `markMissedRead()` is the old `MissedCalls.markRead`.
- Restore round trip: a deleted row restores with its number, kind, date, duration, cached name and label, contact,
  account and block reason, so it reads back equal except for `_id`. `CallLogKind.Unknown` is written as type 0 (the GPUI
  build wrote 1 and turned it into an incoming call).
- Dropped on purpose: `Epic17Probes`/`SpikeProbes` and their tests (GPUI spikes, ARCH 7.3); `pullJson`/`json` JSON
  envelopes (typed models instead). `ProviderJson.callLog`/`kindCode` stay for the golden-fixture tests.
- `RecordingProvider.failWith` makes the fake provider throw, for the denied-app cases.

## Contacts repository (E3.2, step 2)

`dev.rovsti.platform.contacts`: `ContactsRepository` (reads are cold `Flow`s that query on collect and again after a
`ContactsContract.AUTHORITY_URI` change, debounced by `CHANGE_DEBOUNCE_MS` so a burst is one re-query, `mapLatest`,
`flowOn(io)`; writes are `suspend` and answer a sealed `ContactResult`), `AndroidContactsRepository(context, io)`,
`FakeContactsRepository` and `RecordingProvider` in `:platform` testFixtures.

- Models are the `:core-bridge` ones: `search.WireContact` for list rows, `ContactDraft` for `full()`/`detail()`/`save()`
  (the plan's "FullContact" is `ContactDraft`, the shape Rust already owns), `Group`, `Account`, `PhotoPlan`,
  `ContactOptions`, `PinShortcut`, stops `ShortcutAnswer`. New in `model.contacts/ProviderModels.kt`:
  `GroupMembersResult`, `ImportResult`, `PhotoSize` (the `contacts.*` wildcard in `compose_stability.conf` covers them).
- `import(drafts)` takes the drafts the Rust `contacts.import_plan` op returns, not a JSON string.
- The tests use `runCurrent()`, not `advanceUntilIdle()`, to run `backgroundScope` collectors (the latter does not run them).

## E3.4 notes (SMS/MMS)

- `SmsQuery` returns typed rows (`SmsThreadRow`, `SmsRow`, `MmsRow`, `SmsMessagesPayload`), not envelopes; the old
  `SmsJson` builders became `SmsRules` (delivery mapping, paging, extensions).
- The four manifest components (`SmsStatusReceiver`, `MmsFileProvider`, `MmsResultReceiver`, `ScheduledSmsReceiver`) live
  in `platform/sms/BridgeComponents.kt` under `package dev.rovsti.bridge`. Their receivers do blocking work through
  `ReceiverWork` (tests run it inline).
- `MmsInbox` posts its notification through the `MessageNotifier` seam; E3.6 installs `MessageNotifications` there. The
  app installs `MmsCodec.downloadedHandler = MmsCodec.DownloadedHandler(MmsInbox::onDownloaded)` in `Application.onCreate`.
- `scheduled_fired` now also carries `at_ms` (additive; Rust reads the kind only).
- `ComposeRequest` and `MmsAnnouncement` cases of the old `MessagesHostTest` stay with the Messages app (E6).

## Telecom core (E3.5, step 5)

`dev.rovsti.platform.telephony`: `CallRegistry` (same state machine, same rebuild order), `CallsApi`, `MultiCall` (+ `PendingMachine`,
`MultiCallFacts`), `AudioPath`, `ProximityController` + `ProximityPolicy`, `CallNotifications`, `IncomingCall` (+ `AnswerOnce`),
`OngoingCall`, `CallSurface`, `CallEventRing`, `FlipDetector` (+ `FlipRule`), `RebuildCoalescer`, `DialIntents`, `Caps`, `KeypadTone`,
`VoicePlayer`. Resource: `ic_stat_call` (the notification small icon) now lives in `:platform`.

Seams:

- `InCallHost` replaces the `RovstiInCallService` field: the app's `InCallService` (or an adapter around it) passes itself to
  `CallRegistry.onServiceBound(host)`; the registry asks it for `setMuted`, `setAudioRoute`, `requestEndpoint`, `legacyAudioState` and
  `startForeground`/`stopForegroundRemove`.
- `PlatformComponents.mainActivity` / `callAction` replace `MainActivity::class.java` / `CallActionReceiver::class.java` in
  `CallNotifications` and `CallSurface`. `callAction` is required for call notifications (a missing one fails loudly).
- `CallRegistry.state: StateFlow<List<WireCall>>` (`phone.WireCall`, decoded from the same `calls` JSON the Rust side gets) is set
  last in a rebuild, after the notifications. `PlatformEvents.emit("calls", json)` replaces `RovstiBridge.emit` and
  `PlatformEvents.uiAttached` replaces `RovstiBridge.uiReady`.
- `IncomingPerf.kt` and `NativeIncoming.kt` stay in `:phone` (E5). The registry calls `CallRegistry.timing: IncomingTiming?`
  (`onCallAdded`, `mark("notify")`, `frame`, `clear`); `:phone` installs an `IncomingPerf` adapter there.
- Dropped: `probeRejectText` and the `rejectPathOverride` (spike S7, GPUI spikes), `Epic17Probes` use.
- Unchanged on purpose: the per-process `answerToken`, the intent action strings (`dev.rovsti.phone.ANSWER_CALL` etc.) and extras, the
  "Answer is honoured once" rule (`AnswerOnce`), channel ids `ongoing` (LOW) and `incoming` (HIGH, silent), notification ids 1001/1002.
  `PlatformConventionTest` now allows only ALL_CAPS action tokens after `dev.rovsti.phone.` and forbids any phone import or class.

## Notifications and reminders (E3.6, step 6)

`dev.rovsti.platform.notify`: `PlatformChannels` (every channel id and importance as constants plus `ensure`, pinned by
`NotificationChannelsTest` against `src/test/resources/notification_channels.tsv`; `CallNotifications` now uses it),
`MessageNotifications` (+ `NotificationSpec`, `notifier` seam for `MmsInbox`), `NotificationIcon`, `ReplyActions`,
`MessageReminders`, `CallReminders`, `BirthdayReminders`, `ReminderStore`, `ReminderRepositories` (`RemindersRepository`: `arm`,
`disarm`, `armed(): Flow`, `rearmAll`).

- The four receivers (`MessageReminderReceiver`, `CallReminderReceiver`, `BirthdayReminderReceiver`, `ReplyReceiver`) are in
  `notify/NotifyReceivers.kt` under `package dev.rovsti.bridge`; the logic is in `platform.notify`.
- `PlatformEvents.uiAttached` replaces `RovstiBridge.uiReady`; `msg_reply`, `reminder_fired`, `message_reminder_fired`,
  `message_reminders_changed` go to `PlatformEvents`. New and additive: `birthday_reminder_fired`, `reminders_changed`,
  `birthday_reminders_changed` (the `armed()` flows refresh on them).
- Changed on purpose: a failed or undecided cold reply (`wa_reply` with no answer) re-posts the notification unchanged instead of
  cancelling it, so the message is not lost. Everything else (request codes, extras, actions, prefs files) is as before.
- `BirthdayReminders.EXTRA_CONTACT_ID` is `Shortcuts.EXTRA_CONTACT_ID`.

## WhatsApp seams, FQCN and PendingIntent contract (E3 R1, step 8)

`dev.rovsti.platform.wa`: `WaPush` (token by reflection; `onMessage(context, data)` shows or cancels the call notification
and emits `wa_push` on `PlatformEvents`, which `:wa` (E3.9) turns into `reconnectNow()`), `WaIncoming` (+ `WaIncomingNotice`,
`WaMissedNotice`; channels `wa_calls` and `wa_missed` come from `PlatformChannels`), `WaCallAudio` (`start(context, CallFrames)`;
`CallAudio.asFrames()` binds the `:core-bridge` handle, so the microphone thread calls `audioPush` and the speaker thread
`audioPull`; frame size equals `CallAudio.FRAME_BYTES`, pinned by a test).

Tests that guard the whole round: `FqcnTest` (all 13 components load by their `dev.rovsti.bridge` names, `RovstiShareProvider`
being the 13th, E3.8a), `PendingIntentContractTest` (action and extra strings of reminders, scheduled SMS, reply actions, trip alarm and
watchdog equal the GPUI source constants, read as text), `PortInventoryTest` (every bridge `.kt` has a row with an allowed status).

Summary of what does not move in R1:

- dropped: `SpikeProbes`, `Epic17Probes`, `Mailbox`, the `RovstiBridge` statics (replaced by repositories and `PlatformEvents`);
- E3.8a (done, round 09): `Documents`, `MediaShare`, `RovstiShareProvider`, plus `ClipboardRepository` and `ShareIntents`;
- E3.8b (done, round 09): roles and permissions in `platform.system.permissions`: `PermissionGroup`, `AppPermissions`, `PermissionState` (`decide`, `nextAction`), `PermissionController`, `SpecialAccess` (+ `FakeSpecialAccess`), `ForegroundStart`; the `RovstiBridge` permission, role, special-access and battery helpers are replaced by them, and `HandoffPermissionsTest` pins the manifest lists;
- app module: `IncomingPerf`, `NativeIncoming`, the manifest components and the Application/MainActivity classes.

## `:wa` module (E3.9, R9)

`:wa` holds the WhatsApp state of one app; it depends on `:core-bridge` and `:platform` (seams only) and on `:design` (Coil).

| GPUI / bridge source | Kotlin | Notes |
|---|---|---|
| `rovsti_wa_ui::settings` link state, `Gateway` handling | `WaLinkStore`, `WaRepository` / `DefaultWaRepository` (R9-4) | link state, events, resync, wake |
| call handling in `apps/messages` + `WaCallAudio.kt` | `dev.rovsti.wa.calls.WaCalls` (R9-5) | `calls: StateFlow<List<CallSummary>>`, `place`, `accept`, `reject`, `end`, `mute`, `speaker` |
| `rovsti_wa_ui::avatars` (`WaAvatars`, `fetch`, `decode_image`) | `dev.rovsti.wa.avatars.WaAvatars`, `AvatarBook`, `AvatarCodec`, `WaAvatarFetcher` (R9-5) | `want`, `wantAll`, `wantProfile`, `profile`, `name` |
| `rovsti_app_core::wa_link::avatars` (`AvatarBook`, `Backoff`) | `AvatarBook` (Kotlin twin, same constants) | `wa.avatars_plan` only names and prunes files (the op is stateless); dedup, parallelism and back-off live in the Kotlin book, as the GPUI client had them in its own book |

Calls:

- `place` and `accept` check `PermissionController.state(PermissionGroup.MICROPHONE)` first. Not granted: `CallResult.NeedsMicrophone`, no gateway call, no `AudioRecord`. `WaCallAudio.start` checks `RECORD_AUDIO` again (defence in depth).
- The pump runs behind `CallAudioPump` (`AndroidCallAudioPump` = `WaCallAudio`). It starts after `accept` succeeds, or when an outgoing call's `call` event reaches `Active`; it opens `gateway.openCallAudio(id)` and hands `asFrames()` to `WaCallAudio.start`. It stops (and the handle closes) on `end`, `reject`, an `Ended` call event or `close()`. Hanging up silences the audio even when the gateway call fails.
- Speaker: `speaker(on)` goes to `WaCallAudio.setSpeaker` (API 31+ `setCommunicationDevice`, else `isSpeakerphoneOn`). `platform.telephony.AudioPath` is the Telecom endpoint registry for SIM calls (it needs `CallEndpoint`s that a gateway call never has), so it is not used for WhatsApp calls.

Avatars:

- One fetch per jid at a time, 3 at once, none while a 429 back-off holds (5 s doubling to 5 min, reset by a good answer). A 429 is `RustError.Invalid` with detail `{"code":"rate_limited"}` (`map_error` in `ops/wa/mod.rs`).
- Files: `filesDir/wa-avatars/<clean jid>~<id>.jpg`, written as a `.part` then renamed, stored already decoded down to 128 px with `BitmapFactory.Options.inSampleSize` (`BitmapAvatarCodec`), 8 MiB budget, pruned oldest first from the `remove` list of `wa.avatars_plan` after every write and at start.
- Coil: image model `WaAvatar(jid)` (key `wa-avatar:<jid>`), registered with `rovstiImageLoader(context, lowRam) { waAvatars(avatars) }`. `WaAvatars` calls its `invalidate(jid)` when a stored picture changed, so the host removes `MemoryCache.Key("wa-avatar:<jid>")`. Rows call `want(jid, pictureId)` in a `LaunchedEffect(avatars.revision)`.
- Tests: `WaCallsTest`, `WaAvatarsTest` (virtual time), fixtures `FakeWaGateway` (calls, picture, profiles, plan, names), `FakeAvatarsPlan`, `FakeCallPump`.
