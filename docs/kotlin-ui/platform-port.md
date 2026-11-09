# Platform port map

One row per Kotlin source of the GPUI build. Source of truth for E8.6, which deletes the originals once every row is
`ported` or `dropped`. Status words: `ported`, `partly ported`, `todo` (the step in the Step column does it), `dropped: <reason>`,
`app module E<n>`, `R2 E3.x`. Plan: `_bmad-output/kotlin-ui/plans/round-08.md`; design: `docs/kotlin-ui/architecture.md` 7.3.

Rules: `:platform` keeps each file's logic, constants, intent actions, extras, pref keys and channel ids; the Rust seams are
`PlatformEvents` (events out), `Headless.call` (blocking decisions) and `:core-bridge` facades. Components named by the manifests
keep `dev.rovsti.bridge.<Name>` (ARCH 7.2); `FqcnTest` lists the ones ported so far:
`CallReminderReceiver`, `RovstiShareProvider`, `MessageReminderReceiver`, `MmsFileProvider`, `MmsResultReceiver`, `ReplyReceiver`,
`ScheduledSmsReceiver`, `SmsStatusReceiver`, `BirthdayReminderReceiver`, `AlarmActivity`, `TripAlarmReceiver`, `TripService`,
`TripWatchdogReceiver`. `RovstiShareProvider` is the E3.8a share provider (R2).

## Bridge files (`crates/rovsti_android/kotlin/dev/rovsti/bridge/`)

| Source | Destination | Step | Status |
|---|---|---|---|
| `AlarmActivity.kt` | platform.location / platform.trip | 7 (E3.7) | todo |
| `AlarmOptions.kt` | platform.location / platform.trip | 7 (E3.7) | todo |
| `AlarmSound.kt` | platform.location / platform.trip | 7 (E3.7) | todo |
| `BirthdayReminders.kt` | platform.notify.BirthdayReminders | 6 (E3.6) | todo |
| `Blocklist.kt` | platform.calllog / platform.telephony | 3 (E3.3) | todo |
| `CallLogQuery.kt` | platform.calllog / platform.telephony | 3 (E3.3) | todo |
| `CallLogWrite.kt` | platform.calllog / platform.telephony | 3 (E3.3) | todo |
| `CallReminders.kt` | platform.notify.CallReminders | 6 (E3.6) | todo |
| `CallsApi.kt` | platform.telephony.CallsApi | 5 (E3.5) | todo |
| `ContactOptions.kt` | platform.contacts.ContactOptions | 2 (E3.2) | todo |
| `ContactPhotos.kt` | platform.contacts.ContactPhotos | 2 (E3.2) | todo |
| `ContactsEdit.kt` | platform.contacts.ContactsEdit | 2 (E3.2) | todo |
| `ContactsQuery.kt` | platform.contacts.ContactsQuery | 2 (E3.2) | todo |
| `Documents.kt` | platform.files (E3.8a) | R2 E3.8a | R2 E3.8a |
| `Epic17Probes.kt` | none | 1 (E3.1) | dropped: GPUI spikes (ARCH 7.3) |
| `Handoff.kt` | platform.system.Handoff | 1 (E3.1) | ported (Handoff and the roles half revisited in R2 E3.8b) |
| `Haptics.kt` | platform.system.Haptics | 1 (E3.1) | ported (Handoff and the roles half revisited in R2 E3.8b) |
| `Headless.kt` | platform.Headless (blocking wrapper over Rust.core().headless) | 1 (E3.1) | ported, rewritten |
| `KeypadTone.kt` | platform.telephony.KeypadTone | 5 (E3.5) | todo |
| `Mailbox.kt` | none: one-shots become intent handling in the activity | R2 E3.11 | dropped: see ARCH 7.3 |
| `MediaShare.kt` | platform.files (E3.8a) | R2 E3.8a | R2 E3.8a |
| `MessageNotifications.kt` | platform.notify.MessageNotifications | 6 (E3.6) | todo |
| `MessageReminders.kt` | platform.notify.MessageReminders | 6 (E3.6) | todo |
| `MissedCalls.kt` | platform.calllog.MissedCalls; LocaleTimeInfo is ported to platform.system.LocaleTimeInfo | 3 (E3.3), LocaleTimeInfo in 1 | partly ported |
| `MmsCodec.kt` | platform.sms.MmsCodec | 4 (E3.4) | todo |
| `MultiCall.kt` | platform.telephony.MultiCall | 5 (E3.5) | todo |
| `NotificationIcon.kt` | platform.notify.NotificationIcon | 6 (E3.6) | todo |
| `ProviderObservers.kt` | platform.ProviderChanges (callbackFlow) | 1 (E3.1) | ported, rewritten |
| `RejectPolicy.kt` | platform.calllog / platform.telephony | 3 (E3.3) | todo |
| `ReplyReceiver.kt` | platform.notify.ReplyReceiver | 6 (E3.6) | todo |
| `RovstiBridge.kt` | none: the Rust-calls-Kotlin methods become repositories; emit becomes PlatformEvents | 1 (E3.1) | dropped: replaced by seams |
| `ScheduledSms.kt` | platform.sms.ScheduledSms | 4 (E3.4) | todo |
| `SettingsMirror.kt` | platform.system.SettingsMirror | 1 (E3.1) | ported (Handoff and the roles half revisited in R2 E3.8b) |
| `Shortcuts.kt` | platform.contacts.Shortcuts | 2 (E3.2) | todo |
| `SimAccounts.kt` | platform.calllog / platform.telephony | 3 (E3.3) | todo |
| `SimProbe.kt` | platform.calllog / platform.telephony | 3 (E3.3) | todo |
| `SmsQuery.kt` | platform.sms.SmsQuery | 4 (E3.4) | todo |
| `SmsSend.kt` | platform.sms.SmsSend | 4 (E3.4) | todo |
| `SpikeProbes.kt` | none | 1 (E3.1) | dropped: GPUI spikes (ARCH 7.3) |
| `StopsLocation.kt` | platform.location / platform.trip | 7 (E3.7) | todo |
| `SystemPages.kt` | platform.system.SystemPages | 1 (E3.1) | ported (Handoff and the roles half revisited in R2 E3.8b) |
| `TripAlarm.kt` | platform.location / platform.trip | 7 (E3.7) | todo |
| `TripService.kt` | platform.location / platform.trip | 7 (E3.7) | todo |
| `VoicePlayer.kt` | platform.telephony.VoicePlayer | 5 (E3.5) | todo |
| `WaCallAudio.kt` | platform.wa.WaCallAudio | 8 (E3.9 seams) | todo |
| `WaIncoming.kt` | platform.wa.WaIncoming | 8 (E3.9 seams) | todo |
| `WaPush.kt` | platform.wa.WaPush | 8 (E3.9 seams) | todo |
| `WireJson.kt` | platform.wire.WireJson | 1 (E3.1) | ported |
| `XiaomiOps.kt` | platform.system.XiaomiOps | 1 (E3.1) | ported (Handoff and the roles half revisited in R2 E3.8b) |

## App host files (`apps/<app>/platforms/android/app/src/main/kotlin/dev/rovsti/<app>/`)

| Source | Destination | Step | Status |
|---|---|---|---|
| `apps/phone/.../phone/AudioPath.kt` | platform.telephony.AudioPath | 5 (E3.5) | todo |
| `apps/phone/.../phone/CallActionReceiver.kt` | app module E5 (component stays in the app; calls `:platform`) | E5 | app module E5 |
| `apps/phone/.../phone/CallEventRing.kt` | platform.telephony.CallEventRing | 5 (E3.5) | todo |
| `apps/phone/.../phone/CallNotifications.kt` | platform.telephony.CallNotifications | 5 (E3.5) | todo |
| `apps/phone/.../phone/CallRegistry.kt` | platform.telephony.CallRegistry | 5 (E3.5) | todo |
| `apps/phone/.../phone/CallSurface.kt` | platform.telephony.CallSurface | 5 (E3.5) | todo |
| `apps/phone/.../phone/Caps.kt` | platform.telephony.Caps | 5 (E3.5) | todo |
| `apps/phone/.../phone/DialIntents.kt` | platform.telephony.DialIntents | 5 (E3.5) | todo |
| `apps/phone/.../phone/FlipDetector.kt` | platform.telephony.FlipDetector | 5 (E3.5) | todo |
| `apps/phone/.../phone/IncomingCall.kt` | platform.telephony.IncomingCall | 5 (E3.5) | todo |
| `apps/phone/.../phone/IncomingPerf.kt` | platform.telephony.IncomingPerf | 5 (E3.5) | todo |
| `apps/phone/.../phone/MainActivity.kt` | app module E5 (Application/MainActivity on `:shell`) | E5 | app module E5 |
| `apps/phone/.../phone/NativeIncoming.kt` | platform.telephony.NativeIncoming | 5 (E3.5) | todo |
| `apps/phone/.../phone/OngoingCall.kt` | platform.telephony.OngoingCall | 5 (E3.5) | todo |
| `apps/phone/.../phone/ProximityController.kt` | platform.telephony.ProximityController | 5 (E3.5) | todo |
| `apps/phone/.../phone/RebuildCoalescer.kt` | platform.telephony.RebuildCoalescer | 5 (E3.5) | todo |
| `apps/phone/.../phone/RovstiInCallService.kt` | app module E5 (component stays in the app; calls `:platform`) | E5 | app module E5 |
| `apps/phone/.../phone/RovstiPhoneApp.kt` | app module E5 (Application/MainActivity on `:shell`) | E5 | app module E5 |
| `apps/phone/.../phone/VoicemailReceiver.kt` | app module E5 (component stays in the app; calls `:platform`) | E5 | app module E5 |
| `apps/messages/.../messages/BootReceiver.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/ComposeRequest.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/HeadlessSmsSendService.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/IncomingNotice.kt` | platform.sms.IncomingNotice | 4 (E3.4) | todo |
| `apps/messages/.../messages/MainActivity.kt` | app module E6 (Application/MainActivity on `:shell`) | E6 | app module E6 |
| `apps/messages/.../messages/MmsInbox.kt` | platform.sms.MmsInbox | 4 (E3.4) | todo |
| `apps/messages/.../messages/MmsWapPushReceiver.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/messages/.../messages/RovstiMessagesApp.kt` | app module E6 (Application/MainActivity on `:shell`) | E6 | app module E6 |
| `apps/messages/.../messages/SmsDeliverReceiver.kt` | app module E6 (component stays in the app; calls `:platform`) | E6 | app module E6 |
| `apps/stops/.../stops/BootReceiver.kt` | app module E7 (component stays in the app; calls `:platform`) | E7 | app module E7 |
| `apps/stops/.../stops/MainActivity.kt` | app module E7 (Application/MainActivity on `:shell`) | E7 | app module E7 |
| `apps/stops/.../stops/RovstiStopsApp.kt` | app module E7 (Application/MainActivity on `:shell`) | E7 | app module E7 |
| `apps/contacts/.../contacts/MainActivity.kt` | app module E4 (Application/MainActivity on `:shell`) | E4 | app module E4 |
| `apps/contacts/.../contacts/RovstiContactsApp.kt` | app module E4 (Application/MainActivity on `:shell`) | E4 | app module E4 |

## Tests

| Source | Destination | Step | Status |
|---|---|---|---|
| `apps/phone/.../src/test/java/dev/rovsti/bridge/WireFixturesTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/WireV2Test.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/LocaleTimeInfoTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/SystemPagesTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/XiaomiOpsTest.kt` | `:platform` test, same name | 1 | moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/Epic16Test.kt` | SettingsMirrorTest (mirror and system-page cases); the Mailbox, tone, SIM and document cases go with their classes | 1, 3, 5 | partly moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/MessagesSeamTest.kt` | MessagesSeamTest (Headless cases, FakeRustCore); SMS/MMS/notification/scheduled cases go with E3.4/E3.6 | 1, 4, 6 | partly moved |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/ContactPhotosTest.kt` | E3.2 | 2 | todo |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/MultiCallTest.kt` | E3.5 | 5 | todo |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/WaIncomingTest.kt` | E3.9 seams | 8 | todo |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/Epic17Test.kt` | blocklist and SIM cases to E3.3; the Epic17Probes half is dropped | 3 | partly todo |
| `apps/phone/.../src/test/java/dev/rovsti/bridge/SpikeProbesTest.kt` | dropped with its GPUI spike | 1 | dropped |
