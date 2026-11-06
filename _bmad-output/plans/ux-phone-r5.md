# UX completeness pass: Rovsti Phone (round 5)

Base: `0d5213fbf39959ee6db02ebed9b3c12584cdcbdd` (main, clean tree, no stash).
Scope: `apps/phone`, `crates/rovsti_core`, `crates/rovsti_android` (permission seam and shared
Kotlin bridge), the Phone Android host manifest. No new product features and no backends. Intent
comes from code and docs that already exist: `gpuiforge.json` lists `RECORD_AUDIO`,
`docs/android.md` and `docs/architecture.md` say Phone needs it for WhatsApp calls,
`docs/phone-acceptance.md` says "Allow the microphone when asked"; `WaCallAudio.kt` returns false
without it; epics story 20.2 (speed-dial key badge, TalkBack "5, speed dial {name}"); EXPERIENCE
"Notice pill" lifetime rules (info 2 s, one surface for short results).

Rounds 1 to 4 (`ux-phone.md` .. `ux-phone-r4.md`) closed G1-G11, R1-R4, T1-T8, U1-U5. This round
walks what the earlier rounds did not: WhatsApp calls end to end on the device (permissions),
the call-outcome banner's lifetime, and speed dial as seen from the Keypad.

## Re-audit: gaps that remain

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| V1 | **High** | **WhatsApp calls in Phone are silent: the microphone can never be granted.** The ejected manifest (`management: manual`) lacks `RECORD_AUDIO` although `gpuiforge.json` lists it, no code ever asks for the permission (the bridge has no microphone group), and `open_call_audio` only logs `call audio did not start: is RECORD_AUDIO granted?`. The call connects, the timer runs, the other side hears nothing and the user is told nothing. | `apps/phone/platforms/android/app/src/main/AndroidManifest.xml`, `RovstiBridge.kt` permission `groups`, `rovsti_android::permissions::Group`, `wa_call.rs::open_call_audio/answer/place`, `main.rs` WhatsApp error observer |
| V2 | Medium | **Call-outcome notices never go away.** "The call could not be placed", "Placed through the system phone app" and every WhatsApp reason except "not connected" ("Another call is in progress", "Could not place the WhatsApp call", ...) are set in `call_note` and stay until the user changes tab. They have no action and cannot be dismissed, and `overlay_band_top` makes the band above the Tab Bar swallow taps meant for the list rows behind them. EXPERIENCE: info notices live 2 s. | `notice.rs`, `recents.rs::place_call_from_on`, `main.rs::place_whatsapp` and WhatsApp error observer |
| V3 | Low | **Speed dial is invisible on the Keypad.** Digits 2-9 with an assignment look and read exactly like free ones (story 20.2: a 14 px hue dot at the key's top-right, TalkBack "5, speed dial {name}. Long press to call"). Same discoverability gap round 3 fixed for voicemail on 1 (T4). | `keypad.rs::dial_key`, `Key` |
| V4 | Low | **"No speed dial on 5" > "Set" makes the user pick the digit again.** The pill opens the Speed dial page; the digit the user just held is known, so "Set" should start the contact pick for it directly (the page's own Free-row path). | `speed_dial_ui.rs::speed_dial_hold/speed_dial_pick`, `block_ui.rs::PillAction::SpeedDial` |
| V5 | Info (not planned) | Same as round 4 U6: Delete contact via "Edit in Contacts"; contact ringtone gated; 19.7 P3; Call details page and quality chips (21.1, 21.4) need seams; emergency caption on the Keypad (20.4) and voicemail badge need device spikes (the SIM chooser and block checks already skip emergency numbers); S9/S12 PENDING; redial out of scope (EXPERIENCE). `Backend::end_all` is used by the notification End on the Kotlin side by design. The "Connect" and "Grant" notices keep their banner (they carry an action). `scripts/check-apk.sh` still fails on `INTERNET`, which Phone now needs for the WhatsApp gateway; that is a release-script fix outside this UX pass (noted for the release story 22.7). | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(`df` showed 7.4 GB free at planning time; if it shows under 6 GB, `rm -rf target/debug/incremental` first).
Never invent GPUI APIs: reuse only what these files already call (`show_pill_action`,
`show_notice_pill`, `cx.spawn` + `background_executor().timer` as in `sync_pill`, `svg()`,
`rovsti_ui::theme::hue_pair`, `touch_surface`).

### S1. Pure logic and the microphone permission seam (+tests)
`crates/rovsti_android/src/permissions.rs`:
- `Group::Microphone` with key `"microphone"` (doc: `RECORD_AUDIO`, asked on demand by Phone for
  WhatsApp calls; not in `Group::ALL`). Add it to the `every` array of
  `group_keys_are_unique_and_all_excludes_on_demand_groups` and assert
  `!Group::ALL.contains(&Group::Microphone)`; add `state(Group::Microphone)` to `desktop_is_unsupported`.
- `apps/phone/src/setup_ui.rs:290`: add `Group::Microphone` to the on-demand arm
  (`Group::Bluetooth | Group::Location | Group::BackgroundLocation | Group::Microphone`).
- Kotlin `crates/rovsti_android/kotlin/dev/rovsti/bridge/RovstiBridge.kt` `groups`:
  `"microphone" to listOf(android.Manifest.permission.RECORD_AUDIO)`.
  (`apps/stops/src/kotlin_contract.rs` style: if Phone has no such contract test, none is added.)

`apps/phone/src/wa_call.rs` (pure, tested in its test module):
- `pub(crate) const MIC_OFF: &str = "Allow the microphone so they can hear you";`
- `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub(crate) enum MicAsk { Ready, Ask, OpenSettings }`
  and `pub(crate) fn mic_ask(access: rovsti_android::permissions::Access) -> MicAsk`
  (Granted → Ready, Denied → Ask, PermanentlyDenied → OpenSettings). Tests for the three.
- `pub(crate) fn mic_started(result: &rovsti_android::Result<bool>) -> bool`: `Ok(true)` and any
  `Err` (desktop `Unsupported`) are true; only `Ok(false)` is false. Tests: `Ok(true)`, `Ok(false)`,
  `Err(Error::Unsupported)`.

`apps/phone/src/notice.rs`:
- `impl Notice { pub(crate) fn transient(self) -> bool }`: true when `self.action().is_none()` and
  `self != Notice::Preview` (System, Failed, every WhatsApp reason except NOT_CONNECTED).
  Tests: `Failed`, `System`, `WhatsApp("Another call is in progress")` → true; `Preview`,
  `NeedsPhone`, `WhatsApp(NOT_CONNECTED)` → false.

`apps/phone/src/keypad.rs` (pure, tested in its test module):
- `pub(crate) fn speed_dial_aria(digit: &str, name: Option<&str>) -> Option<String>`:
  `Some("{digit}, speed dial {name}. Long press to call")` for a non-blank name, else `None`.
  Tests: `("5", Some("Ayşe"))`, `("5", Some("  "))` → None, `("5", None)` → None.

`crates/rovsti_core/src/speed_dial.rs`:
- `pub fn badges(map: &BTreeMap<u8, SpeedDialEntry>) -> [Option<(String, Option<usize>)>; 9]`:
  index `d - 1` for digits 2..=9 with an entry whose `number_raw` is non-blank: the shown name
  (`name` trimmed, else `number_raw` trimmed) and its hue
  (`avatar::avatar_hue(AvatarKey::Number(&digits_only(number_raw)))`); index 0 (voicemail) and free
  or blank entries are `None`. Tests: empty map → all None; 5 = Ayşe → only index 4 is Some with
  that name and a hue equal to `avatar_hue` of the same digits; an entry on 1 is ignored; a blank
  `number_raw` is None; a nameless entry shows the number.

### S2. WhatsApp calls get a microphone (fixes V1)
- `apps/phone/platforms/android/app/src/main/AndroidManifest.xml`: add
  `<uses-permission android:name="android.permission.RECORD_AUDIO" />` (alphabetical place, after
  `READ_PHONE_STATE`). `scripts/check-apk.sh`: add `RECORD_AUDIO` to the required-permission list
  (do not touch its INTERNET rule; see V5).
- `apps/phone/src/backend.rs`: `PermissionFacts.microphone: Access` (default `Granted` on desktop),
  filled in `load_permissions` with `access(Group::Microphone)`. The existing
  `permissions_changed`/resume pull keeps it fresh.
- `apps/phone/src/wa_call.rs`:
  - `open_call_audio` returns `Result<(CallAudio, bool), ClientError>` where the bool is
    `mic_started(&rovsti_android::wa::call_audio_start())` (keep the log line for `Ok(false)`).
  - `WaCallUi.mic_off: bool`. `attach_audio(audio, mic, cx)`: when `!mic`, set `mic_off = true`
    and `self.error = Some(MIC_OFF)` (through `fail(MIC_OFF, "RECORD_AUDIO missing", cx)`).
    `finish`/call end resets `mic_off = false`.
  - `pub(crate) fn mic_off(&self) -> bool` and `pub(crate) fn retry_mic(&mut self, cx)`: when
    `mic_off` and a session is live, run `rovsti_android::wa::call_audio_start()` on the background
    executor; on `Ok(true)` set `mic_off = false` and `cx.notify()`.
  - `fn ask_mic()` (private, in `wa_call.rs`), called at the top of `place` (after `can_place`
    passes) and of `answer` (after the ringing check), so every answer path is covered
    (`incoming.rs:1043`, `answer_id` at ~l.820 and the notification path at ~l.1171):
    `match permissions::state(Group::Microphone).map(mic_ask)`: `Ok(MicAsk::Ask)` →
    `let _ = permissions::request(Group::Microphone);`; anything else (Ready, OpenSettings, desktop
    `Err`) does nothing. The call is never held back by the dialog.
- `apps/phone/src/main.rs`:
  - WhatsApp error observer: when `what == wa_call::MIC_OFF`, show
    `show_pill_action(MIC_OFF, "Allow", PillAction::GrantMic, cx)` (over the call screen and over
    the tabs alike) instead of the plain pill / `call_note`.
  - Backend observer: if `backend.permissions().microphone.is_granted() && wa_call.read(cx).mic_off()`
    then `wa_call.update(cx, |w, cx| w.retry_mic(cx))` (a bool check per notify, no JNI unless needed).
- `apps/phone/src/block_ui.rs::PillAction::GrantMic` ("Ask for the microphone, or open App info
  when Android won't ask again"): `mic_ask(..)` → `Ask` → `request_permission(Group::Microphone)`;
  `OpenSettings` → `backend::open_app_details()`; `Ready` → `wa_call.retry_mic`.
- Desktop: unchanged (`call_audio_start` is `Unsupported`, so `mic_started` is true).

Acceptance (device, recorded in docs): fresh install, place a WhatsApp call: Android asks for the
microphone; allow: the other side hears you. Deny: during the call the pill reads "Allow the
microphone so they can hear you" with "Allow"; allowing then un-mutes the live call. Permanently
denied: "Allow" opens App info. Cargo: S1 tests.

### S3. Call-outcome notices are transient (fixes V2)
- `PhoneApp::set_call_note(&mut self, note: Option<Notice>, cx)`: when `note.is_some_and(Notice::transient)`,
  show it with `show_notice_pill(note.text(), None, false, cx)` (the 2 s info pill, which never
  blocks the list) and set `call_note = None`; otherwise `call_note = note`. Make `Notice::text`
  `pub(crate)`.
- Use it in `recents.rs::place_call_from_on` (the `self.call_note = match placement { .. }`
  assignment), `main.rs::place_whatsapp` and the WhatsApp error observer's tab branch
  (`call_note = Some(Notice::WhatsApp(what))`). NeedsPhone and NOT_CONNECTED keep the banner
  with their action. No other caller writes `call_note`.
- Existing tests keep passing; the S1 `transient` tests cover the split.

Acceptance: desktop with `ROVSTI_PREVIEW_ROLE=0` (system-app placement): call a Favorite: the pill
"Placed through the system phone app" shows for 2 s and goes, then the Preview "Fix" banner is back;
Recents rows under the band are tappable once it is gone. WhatsApp "Another call is in progress"
behaves the same.

### S4. Speed dial on the Keypad: badge, spoken name, "Set" picks the digit (fixes V3, V4)
- `PhoneApp.speed_badges: [Option<(SharedString, Option<usize>)>; 9]` and
  `speed_aria: [Option<SharedString>; 9]`, rebuilt from `speed_dial::badges(&prefs.speed_dial)`
  and `keypad::speed_dial_aria` only when the prefs change (the place that already re-syncs after
  `update_prefs` / `apply_import` / backend notify comparing a cheap generation or the map by
  equality; never per frame).
- `keypad.rs::dial_key`: on the Keypad tab only (`!in_call`), for digits 2..=9 with a badge, an
  absolutely positioned 14 px circle at the key's top-right (inside the key's fixed square, so no
  key moves) filled with `hue_pair(theme, hue).0` and a 1.5 px `background` ring; aria label is
  `speed_aria[d-1]` instead of `key.label`. Keep the `1` voicemail glyph untouched.
- `block_ui.rs`: `PillAction::SpeedDial` becomes `PillAction::SpeedDial(u8)` ("Choose a contact for
  this digit"). `speed_dial_hold`'s Unassigned arm passes `d`; the handler calls
  `speed_dial_pick(d, window, cx)` (make it `pub(crate)`; it already closes Settings if open, goes
  to Contacts and shows "Choose a contact for {d}" with Cancel). Any other `PillAction::SpeedDial`
  user (none today besides the hold) keeps `speed_dial_open_page`.

Acceptance: assign 5 = Ayşe in Settings > Speed dial; Keypad shows a coloured dot on 5 only;
TalkBack reads "5, speed dial Ayşe. Long press to call". Hold 7 (free): pill "No speed dial on 7"
> Set: Contacts opens with "Choose a contact for 7"; tapping a number assigns 7.

### S5. Docs and plan status
- `docs/phone-acceptance.md`: rows R19 (WhatsApp microphone ask, deny, Allow pill, App info),
  R20 (transient call notices), R21 (speed-dial badge and TalkBack), R22 ("Set" picks the digit).
  In "WhatsApp calls" keep "Allow the microphone when asked" (now true).
- `docs/android.md`: the WaCallAudio paragraph says the permission is in the ejected manifest
  and asked on demand through `Group::Microphone` (Phone).
- `apps/phone/_bmad-output/implementation-artifacts/sprint-status.yaml`: no status changes
  (20-2 keeps `backlog` until S33 ships).
- Append a "Status" section to this file with the commits.

## Next round (candidates)
- Re-audit after S1 to S5. Expected to leave only V5 items (device spikes, gated, P3 or release
  scripts by design).
