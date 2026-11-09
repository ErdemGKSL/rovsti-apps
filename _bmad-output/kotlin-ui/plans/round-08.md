# Round 08 plan: E3 R1 = `:platform` (E3.1-E3.7)

Planned 2026-11-09 at base `115be3b` (branch `ui-overhaul`). Epic: **E3 Platform layer**, round R1: the `:platform`
library module, ported from `crates/rovsti_android/kotlin/dev/rovsti/bridge/*.kt` (49 files, 9 138 lines) and the
app hosts (`apps/{phone,messages,stops}/platforms/android/app/src/main/kotlin/**`). It covers contacts, call log and
blocklist, SMS/MMS, the telecom core, notifications and reminders, and location with the trip service. E3 R2 (E3.8a
files, E3.8b roles and permissions, E3.9 `:wa`, E3.10 WhatsApp settings screen, E3.11 `:shell`) is the next round.

Owner request for this workflow: "make our project GPL, port the code with very high similarity". It was delivered in
R3 (E1.23) and is still true at this base: `LICENSE` is the GPL-3.0 text, `Cargo.toml` has
`license = "GPL-3.0-or-later"`, `android/THIRD_PARTY_NOTICES.md` lists Convx, Kyant0 backdrop / AndroidLiquidGlass,
compose-hig and glisse, and `CLAUDE.md` has the GPL and attribution line. The glass stack is the Convx port from R3
(E1.24-E1.26, `GlassRealPathTest`). This round ports **our own** Kotlin (GPL-3.0-or-later, Rovsti), so it needs no
Convx or glisse header. The rule still holds, though: if a step copies any Convx code, that file keeps the original
header and adds the "Ported from Convx ..." line.

## State found (verified in code, not trusted)

- `git status` clean. HEAD `115be3b` (E2.14, close E2). `stash@{0}` is the deprecated GPUI layout WIP. Never pop it.
- **E1 done** (R5). It has not changed since. Rounds 06 and 07 changed no UI and left no screenshots, so there is no
  new PNG to open.
- **E2 done** (R2). Checked: `crates/rovsti_ffi/src/ops/{registry,core,phone,contacts,messages,chat,stops,tiles,wa,qr,search,shared}`,
  1 957 files in `crates/rovsti_ffi/fixtures` (including the `headless.*` pairs for `trip_fix`, `trip_alarm`,
  `trip_end`, `msg_remind`, `wa_reply`, `wa_digest`, `sms_classify` and `mms_*`), and the facades
  `android/core-bridge/src/main/kotlin/dev/rovsti/core/ops/*Ops.kt` plus the handles. The `@Serializable` wire models
  are in `dev.rovsti.core.model.*`: `search.WireContact`/`WireNumber`, `phone.CallLogEntry`, `phone.WireCall`,
  `messages.SmsThreadRow`, and others. `FakeRustCore` is in `:core-bridge` testFixtures.
- **E3 has not started.** `android/settings.gradle.kts` includes only `:core-bridge`, `:design`, `:catalog` and
  `:testing`.
- Today the GPUI apps compile the bridge sources in place
  (`apps/phone/platforms/android/app/build.gradle.kts:95` adds `crates/rovsti_android/kotlin` as a source dir).
  `:platform` is therefore a **copy**. The originals stay untouched for the deprecated GPUI build until E8.6. The two
  builds are separate Gradle roots, so the duplicate `dev.rovsti.bridge` FQCNs cannot clash.
- Kotlin calls into Rust in two ways today, and both must go:
  - `RovstiBridge.emit(kind, json)` (Kotlin -> Rust event, 20 call sites in `TripAlarm`, `TripService`,
    `StopsLocation`, `ReplyReceiver`, `ScheduledSms`, `SmsSend`, ...);
  - `Headless.call(kind, json)` (sync JNI into the GPUI app's own library).

  The ~150 `@JvmStatic` methods of `RovstiBridge.kt` (1 192 lines) are the Rust-calls-Kotlin direction (E3.1 AC2).
  They are **not** ported. Repositories replace them.
- Components named by the manifests (`apps/*/platforms/android/app/src/main/AndroidManifest.xml`) that live in the
  bridge and must keep `dev.rovsti.bridge.<Name>`: `CallReminderReceiver`, `RovstiShareProvider`,
  `MessageReminderReceiver`, `MmsFileProvider`, `MmsResultReceiver`, `ReplyReceiver`, `ScheduledSmsReceiver`,
  `SmsStatusReceiver`, `BirthdayReminderReceiver`, `AlarmActivity`, `TripAlarmReceiver`, `TripService`,
  `TripWatchdogReceiver`. The app-owned components (`RovstiInCallService`, `CallActionReceiver`, `VoicemailReceiver`,
  `SmsDeliverReceiver`, `MmsWapPushReceiver`, `HeadlessSmsSendService`, `ComposeRequest`, `BootReceiver`, `WaHandoff`,
  `RovstiMessagingService`) stay in the app modules (E4-E7). `:platform` gives them the logic they call.
- SharedPreferences names in use (`getSharedPreferences` in the bridge): `rovsti_bridge`, `rovsti_scheduled_sms`,
  `rovsti_birthday_reminders`, `rovsti_alarm`, `rovsti_call_reminders`, `rovsti_message_reminders`,
  `rovsti_phone_settings` (`SettingsMirror.kt:26`) and `rovsti_trip` (`TripService.kt:149`). ARCH 7.1 lists only the
  first six; Step 1 fixes ARCH.
- Notification channel ids: the `CHANNEL`/`MISSED_CHANNEL`/`CHANNEL_ONGOING` constants in `MessageNotifications`,
  `BirthdayReminders`, `CallReminders`, `MessageReminders`, `WaIncoming`, `TripService` and `TripAlarm`
  (`trip_alarm_v2`, deletes `OLD_CHANNEL`), plus `"trip_problem"` and `CallNotifications` in the phone host.
- JVM tests to move: `apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/*` (`WireFixturesTest`,
  `WireV2Test`, `LocaleTimeInfoTest`, `ContactPhotosTest`, `SystemPagesTest`, `XiaomiOpsTest`, `Epic16Test`,
  `Epic17Test`, `MessagesSeamTest`, `MultiCallTest`, `WaIncomingTest`, `SpikeProbesTest`),
  `.../dev/rovsti/phone/*` (`AudioPathTest`, `CallEventRingTest`, `DialIntentsTest`, `IncomingCallTest`,
  `OngoingCallTest`, `ProximityPolicyTest`, `RebuildCoalescerTest`), and
  `apps/messages/platforms/android/app/src/test/kotlin/dev/rovsti/messages/MessagesHostTest.kt`.
  `SpikeProbesTest` and the `Epic17Probes` half of `Epic17Test` are dropped with their GPUI spikes (ARCH 7.3
  "Removed").
- The Stops Kotlin contract (`apps/stops/src/kotlin_contract.rs`, 312 lines) `include_str!`s bridge files and the
  Stops manifest (E3 AC4 moves it).
- Disk: **6.4 GB free** (limit 6 GB). `target/` is 6.7 G, `android/core-bridge/build` 1.3 G,
  `android/catalog/build` 672 M and `~/.gradle/caches/9.9.0` 3.5 G.

## Rules for this round

1. **Disk first, every step.** Before any build: `df -h .`. Below 8 GB free, run
   `rm -rf target/debug/incremental target/aarch64-linux-android/debug/incremental android/*/build`. Below 7 GB,
   also run `cargo clean -p` for the deprecated GPUI crates (`rovsti_phone rovsti_messages rovsti_stops rovsti_contacts
   rovsti_ui rovsti_wa_ui liquid_glass_kit`). Step 1 starts with the full clean (`rm -rf target android/*/build`),
   because the check script rebuilds only the Kotlin-facing crates. Never go below 6 GB.
2. **Port, do not redesign.** Keep each file's logic, constants, intent actions, extras, request codes, channel ids,
   pref keys and PendingIntent flags exactly as they are. Only these change:
   - the Rust seams (Rule 3);
   - `org.json` string returns turn into the `:core-bridge` `@Serializable` wire models where one exists (Rule 4);
   - the app's own activity/receiver classes are referenced through a seam (Rule 5).

   Keep the ported file's KDoc and add one line, `Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/<File>.kt`
   (or the app host path), so E8.6 can delete the originals with a clear map.
3. **No Rust-calls-Kotlin, no JNI in `:platform`.** Replace `RovstiBridge.emit(kind, json)` with
   `dev.rovsti.platform.PlatformEvents`, a process-wide `MutableSharedFlow<PlatformEvent>` (`extraBufferCapacity = 64`,
   `DROP_OLDEST`; `PlatformEvent(kind: String, json: String)` keeps today's kinds and payloads). Repositories expose
   typed `Flow`s filtered from it. Replace `Headless.call(kind, json)` with `dev.rovsti.platform.Headless.call`, a
   **blocking** wrapper (off the main thread; `check(Looper.myLooper() != Looper.getMainLooper())` in debug) over
   `Rust.core().headless(kind, json)` from `:core-bridge`. It keeps today's contract: `null` = no decision (library
   missing, error envelope, blank answer), and callers fall back as they do now. `PlatformConventionTest` scans
   `android/platform/src/main` and fails on `external fun`, `System.loadLibrary`, `RovstiBridge` or
   `nativeHeadless`.
4. **One wire format.** Provider readers keep producing the wire JSON that `WireJson.kt` builds (it is proven against
   `crates/rovsti_core/tests/fixtures` by `WireFixturesTest`/`WireV2Test`). Repositories decode it once with
   `dev.rovsti.core.Envelope.json` into the `:core-bridge` model and hand those models to the facades (for example
   `List<WireContact>` -> `SearchOps` / the `ContactIndex` handle). Do not write a second Kotlin model for the same
   row. `WireModelsTest` decodes every relevant `crates/rovsti_core/tests/fixtures/*.json` both through `WireJson`
   output and directly, and asserts equality.
5. **Seams for app-owned classes.** `:platform` never references `dev.rovsti.<app>.*`. It takes
   `PlatformComponents(mainActivity: Class<out Activity>, callAction: Class<out BroadcastReceiver>? = null, ...)`,
   passed once from the app's `Application` (E4-E7). Tests pass a dummy activity.
6. **Repository shape (E3 AC1).** For each area:
   - an `interface XRepository` in `dev.rovsti.platform.<pkg>`;
   - `AndroidXRepository(context, io: CoroutineDispatcher = Dispatchers.IO)`;
   - `FakeXRepository` in `android/platform/src/testFixtures/kotlin` (enable `testFixtures` as `:core-bridge` does),
     so app modules can use it in their tests.

   Provider reads are `Flow`s that re-query on change: a `ProviderChanges.observe(resolver, uri...)` callbackFlow
   (port of `ProviderObservers.kt`), `.conflate()`, then `mapLatest { query() }`, `flowOn(io)`. Writes are `suspend`
   and return `Outcome`-style sealed results, never throwing to the UI.
7. **Tests.** Robolectric (4.17, already in `libs.versions.toml`) for anything that touches a provider or service.
   Provider tests register an in-memory `RecordingProvider` (a `ContentProvider` backed by `MatrixCursor` rows that
   records `applyBatch`/`insert`/`update`/`delete`) with `Robolectric.setupContentProvider(RecordingProvider::class.java, authority)`.
   The provider lives in `android/platform/src/testFixtures` so E4-E7 can reuse it. Pure logic gets plain JUnit. Moved
   tests change only their package and imports, so a diff against the original shows that.
8. **Check per step:** `bash scripts/kotlin-ui-check.sh` must print `ALL GREEN`. Add `:platform` to `settings.gradle.kts`
   in Step 1. `testDebugUnitTest` and `lintDebug` then cover it with no script change. Steps that touch Rust (Step 7)
   also run `cargo test -p rovsti_app_core -p rovsti_stops`. The full `CLAUDE.md` gate runs in Step 8.
9. **No screenshots this round.** E3.1-E3.7 list none (epics.md), and `:platform` has no Compose code. `AlarmActivity`
   is a plain `Activity` ported as is, and its look is judged in E7. `checkComposeStability` and the orphan-goldens
   step must stay green, unchanged.
10. **Commits:** one per step, `Kotlin UI (E3 R1): <step title>`, ending with the session attribution lines. Never
    push to `main`.

## Steps

### Step 1. `:platform` module, seams, system helpers, convention tests (E3.1 base)

- Disk clean (Rule 1). Create `android/platform` with `rovsti.android.library` and `kotlin.serialization`, namespace
  `dev.rovsti.platform`, and `api(project(":core-bridge"))`. Enable `testFixtures`. Add test deps (`junit`,
  `robolectric`, `coroutines-test`, `testFixtures(project(":core-bridge"))`). Add `include(":platform")`.
- `src/main/AndroidManifest.xml` declares **no** components. It has only the `uses-permission` lines the library code
  needs to compile its lint checks, and those are mirrored from the app manifests.
- Seams: `PlatformEvents`/`PlatformEvent`, `Headless` (Rule 3), `PlatformComponents` (Rule 5), and
  `ProviderChanges` (port of `ProviderObservers.kt`, returns `Flow<Unit>`).
- Port without provider dependencies:
  - `WireJson.kt` -> `dev.rovsti.platform.wire`;
  - `SystemPages`, `XiaomiOps`, `SettingsMirror` (only the part Android must read without the app: the
    `rovsti_phone_settings` file), `Haptics`, `Handoff` -> `dev.rovsti.platform.system`;
  - `LocaleTimeInfo` (in `MissedCalls.kt:39`) -> `dev.rovsti.platform.system.LocaleTimeInfo`.

  `Mailbox` is **not** ported (ARCH 7.3: one-shots become intent handling in the activity, E3.11).
- Move tests: `WireFixturesTest`, `WireV2Test`, `LocaleTimeInfoTest`, `SystemPagesTest`, `XiaomiOpsTest`, the
  `SettingsMirror`/`SystemPages` cases of `Epic16Test` (as `SettingsMirrorTest`), and `MessagesSeamTest` (retarget
  it to the new `Headless` with `FakeRustCore`: an error envelope or a blank answer gives `null`).
- New tests:
  - `LibraryManifestTest` (E3.1 AC4): parses `src/main/AndroidManifest.xml` and asserts there is no `activity`,
    `service`, `receiver` or `provider`;
  - `PrefsNamesTest` (AC5): one constant table `PlatformPrefs.ALL` with the 8 names above; the test asserts the set,
    and every `getSharedPreferences(` call in `src/main` uses a `PlatformPrefs` constant (source scan);
  - `PlatformConventionTest` (Rule 3);
  - `WireModelsTest` (Rule 4) for the fixtures the moved tests already read;
  - `FqcnTest`: `Class.forName` for every `dev.rovsti.bridge.*` component ported so far. The list grows per step and
    is complete in Step 8.
- Docs: fix ARCH 7.1 prefs list (add `rovsti_phone_settings`, `rovsti_trip`). Start `docs/kotlin-ui/platform-port.md`:
  one row per source file (all 49 bridge files plus the host files) with destination, step and status (`ported`,
  `dropped: <reason>`, `app module E<n>`, `R2 E3.8a/b`, `R2 E3.9`).
- Acceptance: check green; the moved tests pass unchanged except for package and imports; the convention tests fail
  when you add a component to the library manifest or an `external fun` (try once locally, then revert).

### Step 2. Contacts provider repository (E3.2)

- Port `ContactsQuery`, `ContactsEdit` (776 lines, `EDITED_MIMES`), `ContactPhotos`, `ContactOptions` and `Shortcuts`
  to `dev.rovsti.platform.contacts`.
- `ContactsRepository`:
  - `all(): Flow<List<WireContact>>`, `withNumbers()`, `favorites()`, `full(): Flow<List<FullContact>>`,
    `detail(id): Flow<ContactDetail?>`;
  - `save(draft): SaveResult`, `delete(id)`, `setStarred(id, on)`;
  - `groups(): Flow<List<Group>>`, `saveGroup`, `deleteGroup`, `setMembers`, `accounts()`;
  - `photoSize(path)`, `setPhoto(id, path, plan)`, `removePhoto(id)`;
  - `options(id)`, `setRingtone`, `setVoicemail`;
  - `import(json)`.

  Use the `:core-bridge` contact models (`dev.rovsti.core.model.contacts.*`, `search.WireContact`) where the shapes
  match. Where no model exists yet (full/detail rows), add `@Serializable @Immutable` models next to the existing ones
  in `:core-bridge` `model.contacts` and list them in `android/compose_stability.conf`.
- `ShortcutsRepository` (`canPin`, `pinContact(spec)`, `pinStop(spec)`). It keeps the shortcut intent actions and
  extras (`EXTRA_CONTACT_ID`, AD-CT8), and the target activity comes from `PlatformComponents`.
- `observeContacts()` is `ProviderChanges` on `ContactsContract.AUTHORITY_URI`, conflated.
- Tests: moved `ContactPhotosTest`. New Robolectric `AndroidContactsRepositoryTest` with `RecordingProvider` at
  `com.android.contacts`:
  - save a draft that fills **every** mime of `ContactsEdit.EDITED_MIMES`, and assert one `applyBatch` that has
    an operation per mime with the right `Data.MIMETYPE` and columns;
  - read back from seeded rows;
  - star/unstar;
  - group members diff;
  - delete;
  - a provider change re-emits `all()` once for a burst of 5 notifications (conflation, `mainClock`-free
    `runTest` + `advanceUntilIdle`).
- Acceptance: check green; `FakeContactsRepository` covers every method (a reflection test compares the interface
  and fake method sets).

### Step 3. Call log, blocklist, blocked calls, SIM accounts (E3.3)

- Port `CallLogQuery`, `CallLogWrite`, `MissedCalls`, `Blocklist`, `RejectPolicy`, `SimAccounts` and `SimProbe` to
  `dev.rovsti.platform.calllog` / `telephony`.
- Repositories:
  - `CallLogRepository`: `entries(limit): Flow<List<CallLogEntry>>`, `blocked(limit)`, `delete(ids): Restorable`,
    `restore(rows)`, `deleteAll()`, `missedCount()`;
  - `BlocklistRepository`: `numbers(): Flow`, `block(n)` (keeps the emergency-number refusal, `Blocklist.Result`),
    `unblock`, `canBlock`, `isBlocked`;
  - `SimRepository`: `accounts(): Flow<List<SimAccount>>` (from `SimAccounts.collect`, re-queried on
    `ACTION_DEFAULT_SUBSCRIPTION_CHANGED` / `SubscriptionManager` listener), `probe()`.
- Tests:
  - the `Blocklist` cases of `Epic17Test`, moved as `BlocklistTest`;
  - Robolectric `AndroidCallLogRepositoryTest` (`RecordingProvider` at `call_log`): read seeded rows into
    `CallLogEntry` (equal to the `crates/rovsti_core/tests/fixtures` call-log fixture after decode), delete then
    restore round trip (restored rows equal the originals except `_id`), and a burst of changes gives one re-query;
  - `RejectPolicyTest` (the pure policy table).
- Acceptance: check green; `docs/kotlin-ui/platform-port.md` rows updated.

### Step 4. SMS/MMS provider, send, scheduled SMS (E3.4)

- Port to `dev.rovsti.platform.sms`: `SmsQuery` (377), `SmsSend` (183, `SmsStatusReceiver` keeps
  `dev.rovsti.bridge`), `MmsCodec` (`MmsFileProvider`, `MmsResultReceiver` keep `dev.rovsti.bridge`), `ScheduledSms`
  (`ScheduledSmsReceiver` keeps `dev.rovsti.bridge`; prefs `rovsti_scheduled_sms`), and from the messages host
  `MmsInbox` and `IncomingNotice` (not manifest components).
- `SmsRepository`:
  - `threads(limit): Flow<List<SmsThreadRow>>`, `messages(threadId, beforeMs, limit)`, `sims()`, `search(like, limit)`;
  - `markRead`, `deleteThread`, `deleteMessage`, `insertRestored`;
  - `send(req): SendHandle` (multipart, dual SIM, delivery: status flows from `PlatformEvents` kind `sms_status`);
  - `sendMms(req)`.

  `ScheduledSmsRepository`: `arm`, `disarm`, `fired(): Flow`, and the exact-alarm fallback flag (AD-MSG6: inexact
  window when `canScheduleExactAlarms()` is false; the result says which one was used).
- Tests:
  - moved `MessagesHostTest`;
  - Robolectric `AndroidSmsRepositoryTest` (`RecordingProvider` at `sms`/`mms-sms`): insert then read, and thread
    rows decode to `SmsThreadRow`;
  - `ScheduledSmsTest` (Robolectric `ShadowAlarmManager`): arm sets an exact alarm with the same request code and
    extras as today; with exact alarms denied, an inexact window is set and reported; the receiver firing emits
    `scheduled_fired` and clears the pref;
  - `SmsSendTest`: a long body is split into the parts `SmsManager.divideMessage` returns, and the status receiver
    maps result codes as today.
- Acceptance: check green.

### Step 5. Telecom core (E3.5)

- Port to `dev.rovsti.platform.telephony` from the phone host: `CallRegistry` (1 187), `AudioPath`,
  `ProximityController` + `ProximityPolicy`, `CallNotifications`, `IncomingCall`, `OngoingCall`, `CallSurface`,
  `CallEventRing`, `FlipDetector`, `RebuildCoalescer`, `DialIntents`, `Caps`. From the bridge: `MultiCall`,
  `CallsApi`, `KeypadTone`, `VoicePlayer` (voicemail/voice notes playback).
- Seams:
  - `InCallHost` (interface implemented later by `:phone`'s `RovstiInCallService`; `CallRegistry.onServiceBound(host)`
    replaces the `RovstiInCallService` field);
  - `PlatformComponents.mainActivity` / `callAction` replace `MainActivity::class.java` / `CallActionReceiver` in
    `CallNotifications`.

  The per-process answer secret and the "Answer honoured once" rule stay unchanged.
- Expose `CallRegistry.state: StateFlow<List<WireCall>>` (the `callsSnapshot` JSON decoded into `phone.WireCall`) and
  the actions `CallsApi` already has, as suspend-free main-thread calls (they are Telecom calls).
  `IncomingPerf.kt` and `NativeIncoming.kt` stay for `:phone` (E5).
- **Incoming order (ARCH 8.2, E3 AC3):** Robolectric `IncomingOrderTest`:
  - feed `CallRegistry` an incoming call through a fake `InCallHost`, and assert the `CallStyle` notification is
    posted (`ShadowNotificationManager`) before any `state` emission reaches a collector, and before
    `PlatformComponents.mainActivity` is started;
  - assert the full-screen intent targets `mainActivity` with the answer extras.
- Tests moved: `MultiCallTest`, `IncomingCallTest`, `OngoingCallTest`, `AudioPathTest`, `ProximityPolicyTest`,
  `CallEventRingTest`, `DialIntentsTest`, `RebuildCoalescerTest`.
- `CallNotificationsChannelsTest`: channel ids and importance equal today's.
- Acceptance: check green; `CallRegistry` has no reference to `dev.rovsti.phone` (a `PlatformConventionTest` rule).

### Step 6. Notifications, reminders, shortcuts (E3.6)

- Port to `dev.rovsti.platform.notify`: `MessageNotifications` (MessagingStyle, inline reply), `NotificationIcon`,
  `MessageReminders` (+ `MessageReminderReceiver`), `CallReminders` (+ `CallReminderReceiver`), `BirthdayReminders`
  (+ `BirthdayReminderReceiver`), and `ReplyReceiver`. Every receiver keeps `dev.rovsti.bridge`.
  - `ReplyReceiver` uses `PlatformEvents` (`msg_reply` while the UI is attached: `PlatformEvents.uiAttached` replaces
    `RovstiBridge.uiReady`) and `Headless.call("msg_remind" | "wa_reply", ...)`.
  - Reminder prefs: `rovsti_message_reminders`, `rovsti_call_reminders`, `rovsti_birthday_reminders`.
- `RemindersRepository` per kind: `arm`, `disarm`, `armed(): Flow`, `rearmAll()` (boot path).
- `NotificationChannelsTest` (Robolectric): after each `ensureChannel` the set of channel ids and importances equals
  a committed table (Messages HIGH, Birthdays DEFAULT, Call reminders DEFAULT, Message reminders DEFAULT, WhatsApp
  calls HIGH, Missed WhatsApp call DEFAULT, `trip_problem` HIGH, Trip in progress LOW, `trip_alarm_v2` HIGH, phone
  call channels). Every id is a `PlatformChannels` constant.
- Tests:
  - `ReplyReceiverTest`: with UI attached, a reply emits `msg_reply`; without, it calls `Headless` (via `FakeRustCore`)
    and posts the follow-up notification; a `null` answer falls back to the plain notification;
  - `CallRemindersTest` and `BirthdayRemindersTest`: arm then fire then rearm-after-boot with `ShadowAlarmManager`
    (request codes and extras equal today's);
  - `HeadlessFixturesTest`: the `msg_remind` and `wa_reply` request JSON the ported code builds decodes into the
    `headless.*.request.json` fixture models of `:core-bridge`.
- Acceptance: check green.

### Step 7. Location, trip service, alarm (E3.7) and the moved Kotlin contract

- Port to `dev.rovsti.platform.location`: `StopsLocation` (`LocationRepository`: `lastKnown()`,
  `fresh(timeout): Fix?`, `updates(): Flow<Fix>` replacing the `stops_location` emits), `TripService` (527;
  `TripService`/`TripWatchdogReceiver` keep `dev.rovsti.bridge`; prefs `rovsti_trip`), `TripAlarm` (384;
  `TripAlarmReceiver` keeps `dev.rovsti.bridge`), `AlarmSound` (prefs `rovsti_alarm`), `AlarmOptions`, and
  `AlarmActivity` (keeps `dev.rovsti.bridge`).
  - The service still calls Rust for every fix: `Headless.call("trip_fix", ...)`, and `"trip_alarm"` for
    timeout/dismiss/snooze.
  - AD-SA1 to AD-SA9 are unchanged.
  - `TripRepository` exposes `status: Flow<TripStatus>` (from `PlatformEvents` `EVENT_STATUS`) and `start(req)`.
    `ForegroundServiceStartNotAllowedException` and `SecurityException` map to `StartResult.NotAllowed`, which the
    UI turns into the "trip could not start" row (ARCH 7.5). It never crashes.
- **E3 AC4:** move `apps/stops/src/kotlin_contract.rs` to `crates/rovsti_app_core/tests/kotlin_contract.rs`:
  - `include_str!` the ported files under `android/platform/src/main/kotlin/...`, so it keeps checking the Kotlin
    defaults against `rovsti_geo::poll`;
  - the assertions on the Stops **manifest** and `stops/MainActivity.kt` stay in the GPUI copy until `:stops` exists,
    and are added back against `android/stops/src/main/AndroidManifest.xml` in E7.1 (add that line to E7.1's ACs);
  - the GPUI copy stays for the deprecated build until E8.6.
- Tests (Robolectric):
  - `TripServiceTest` (`ServiceController`): start with a fake location gives the foreground notification on channel
    "Trip in progress" with `FOREGROUND_SERVICE_TYPE_LOCATION`; `START_FLAG_REDELIVERY` restart posts the
    `trip_problem` notice and does not clear the trip; a fix calls `Headless` with the `trip_fix` request that decodes
    into the fixture model;
  - `TripStartResultTest`: the thrown exception maps to `NotAllowed`;
  - `TripAlarmTest`: ring then timeout then dismiss/snooze emits the same event kinds as today, and a `null` headless
    answer keeps the local fallback;
  - `AlarmOptionsTest`: defaults equal the Rust plan (also guarded by the moved contract).
- Acceptance: check green, plus `cargo test -p rovsti_app_core -p rovsti_stops`.

### Step 8. WhatsApp seams, final FQCN list, port inventory, close E3 R1

- Port the `wa` seams to `dev.rovsti.platform.wa`: `WaPush` (token register and `reconnect_now` wake, AD-WA9),
  `WaIncoming` (WhatsApp call notifications, channels from Step 6), and `WaCallAudio` (record/play threads with
  voice-communication attributes; frames go to the `:core-bridge` WA handle's `audioPush`/`audioPull`, not to
  `RovstiBridge`). These are the seams `:wa` (E3.9, R2) uses. Move `WaIncomingTest`.
- `FqcnTest` final list: the 13 `dev.rovsti.bridge` component names above, minus `RovstiShareProvider`, which comes
  with E3.8a in R2 (written in the test as a `// R2 E3.8a` line). Every intent action and extra string used in a
  `PendingIntent` that the GPUI build may have left armed (reminders, scheduled SMS, trip alarm/watchdog) equals the
  GPUI source constant: `PendingIntentContractTest` reads both files as text and compares the constants.
- `docs/kotlin-ui/platform-port.md` complete: every row is `ported` / `dropped: <reason>` (`SpikeProbes`,
  `Epic17Probes`, `Mailbox`, the `RovstiBridge` statics) / `R2 E3.8a` (`Documents`, `MediaShare`,
  `RovstiShareProvider`) / `R2 E3.8b` (roles, permissions) / `app module` (`IncomingPerf`, `NativeIncoming`, the
  manifest components). A test (`PortInventoryTest`) lists `crates/rovsti_android/kotlin/dev/rovsti/bridge/*.kt` and
  fails if a file has no row.
- Full `CLAUDE.md` gate after the disk check (`cargo fmt --all && cargo clippy --workspace --all-targets &&
  cargo test --workspace`, `cargo check -p rovsti_android --target aarch64-linux-android`). The catalog APK is
  rebuilt by the check (`scripts/check-catalog-apk.sh`, unchanged by this round). Its path goes in the commit message:
  `android/catalog/build/outputs/apk/debug/catalog-debug.apk`.
- epics.md: set E3.1-E3.7 to `done` with one Evidence line each (commit, test classes). Under E7.1, add the AC
  "manifest/MainActivity assertions of `kotlin_contract.rs` re-targeted to `:stops`". Point the next planner at E3 R2
  (E3.8a, E3.8b, E3.9, E3.10, E3.11), which has the round's screenshots (`e3.10-*`, `e3.11-*`).

## Verification summary for the next planner

- `android/platform` exists; `settings.gradle.kts` includes it; the library manifest has no components.
- `grep -rn "external fun\|RovstiBridge\|loadLibrary" android/platform/src/main` is empty.
- These test classes exist and run in `testDebugUnitTest`: `LibraryManifestTest`, `PrefsNamesTest`,
  `PlatformConventionTest`, `WireModelsTest`, `FqcnTest`, `AndroidContactsRepositoryTest`,
  `AndroidCallLogRepositoryTest`, `AndroidSmsRepositoryTest`, `ScheduledSmsTest`, `IncomingOrderTest`,
  `NotificationChannelsTest`, `ReplyReceiverTest`, `TripServiceTest`, `TripAlarmTest`, `PendingIntentContractTest`,
  `PortInventoryTest`, plus every moved test listed above.
- `crates/rovsti_app_core/tests/kotlin_contract.rs` exists and reads `android/platform` sources.
- No new or changed PNG (none expected).
