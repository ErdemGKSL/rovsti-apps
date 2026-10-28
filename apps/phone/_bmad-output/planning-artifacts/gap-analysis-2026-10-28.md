---
title: "Rovsti Phone: gap analysis after the first device test"
created: 2026-10-28
status: final
inputs:
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/prd.md
  - apps/phone/_bmad-output/planning-artifacts/epics.md
  - apps/phone/_bmad-output/implementation-artifacts/sprint-status.yaml
  - apps/phone/src/*.rs, apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/*.kt (read for "already built" claims)
feeds: PRD update, FR-41 to FR-73, NFR-14 to NFR-18, proposed Epics 14 to 21
---

# Rovsti Phone: gap analysis (2026-10-28)

The owner tested v0.1 (Epics 1 to 13) and found it lacks features a modern dialer has, for example a detailed audio-route picker and shortcuts for conference calls. This document lists what mainstream dialers and the Android Telecom API offer, compares it with what Rovsti Phone has, decides what is in scope, and groups the in-scope work into proposed epics. The PRD update (FR-41+) follows directly from section 5.

## 1. Method and evidence quality

- **API facts** were checked against the Android SDK platform 36.1 `api-versions.xml` on this machine (`~/Android/Sdk/platforms/android-36.1/data/api-versions.xml`). That file is generated from the same source as developer.android.com's "added in / deprecated in" markers. The developer.android.com reference pages themselves could not be read through the fetch tool (each page is ~335 KB of navigation, the class body is cut off), so no developer.android.com page was read in full.
- **Behavioural facts** (who may write the blocklist, endpoint type meanings) were checked in AOSP source on android.googlesource.com.
- **Competitor facts** come from vendor support pages where the search reached them. Everything else is marked **K** (common knowledge, not re-verified in this run). Marks: **V** = verified in a source below, **K** = from knowledge, unverified, **?** = could not verify and must be tested on the device.
- Confirmed in the repo (not the web): the app already uses the Android 14 `CallEndpoint` API (`RovstiInCallService` overrides `onCallEndpointChanged` and `onAvailableCallEndpointsChanged`; `CallRegistry.route(name)` picks the first endpoint of a given type), declares `BLUETOOTH_CONNECT`, `WRITE_CALL_LOG` and `WRITE_CONTACTS`, and the Rust wire model `AudioRoute` has only Earpiece, Speaker, Bluetooth, WiredHeadset, Unknown. So the data needed for a named-device picker reaches Kotlin but is flattened to a type before Rust sees it. That is the single most important finding for the owner's first complaint.

### Sources

- Local SDK: `api-versions.xml`, platform android-36.1 (V for every "since API" and "deprecated" number below).
- AOSP `CallEndpoint.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/telecomm/java/android/telecom/CallEndpoint.java (class is "the endpoint where call media can flow"; types are Unknown, Earpiece, Bluetooth, WiredHeadset, Speaker, Streaming; no hearing-aid type).
- AOSP `BlockedNumberContract.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/BlockedNumberContract.java (access limited to the system, the default SMS app, the default phone app and carrier apps).
- Android 15 sideload restrictions: https://9to5google.com/2024/09/12/android-15-sideloaded-apps-restrictions/ and https://androidauthority.com/android-15-restricted-settings-sideloading-3481098 (Dialer and SMS roles are restricted settings for sideloaded apps; the user can still allow them).
- Samsung call settings list: https://www.samsung.com/in/support/mobile-devices/samsung-smartphone-how-to-set-up-call-settings (Call alerts and ringtones, Answering and ending calls, Quick decline messages, Call display while using apps, Block numbers, Call waiting). Quick decline limit of 6: https://www.samsung.com/ae/support/mobile-devices/sending-quick-decline-messages-on-my-samsung-phone.
- Google Phone Call Screen quick responses: https://support.google.com/phoneapp/answer/9118387. Hold for Me: https://support.google.com/phoneapp/answer/10104618.
- Apple unknown-caller screening: https://support.apple.com/111106.
- Xiaomi blocking and call-recording availability by ROM region (secondary, vendor blog): https://www.technobezz.com/how-to-block-number-xiaomi-14t-pro and https://drfone.wondershare.com/android-xiaomi/how-to-use-call-recording-on-xiaomi-with-hyperos-legal-guide.html (treat as weak evidence).

## 2. Platform constraints that decide scope

1. **Sideloaded default dialer.** Rovsti Phone holds `ROLE_DIALER` by sideload. On Android 15+ the Dialer role is a restricted setting for sideloaded apps; the owner already allows it once in App info (three dots, "Allow restricted settings") (V, 9to5google / Android Authority; menu path is user-reported). Everything in this document assumes that is done. Anything needing privileged permissions (`MODIFY_PHONE_STATE`, `READ_PRIVILEGED_PHONE_STATE`, `CAPTURE_AUDIO_OUTPUT`) is out of reach and flagged "privileged".
2. **No INTERNET permission.** Rules out caller-ID/spam lookup, reverse lookup, cloud backup, AI call screening, visual-voicemail IMAP, any update check.
3. **Default-dialer-only providers.** `BlockedNumberContract` accepts the default phone app (V, AOSP source), so blocking is possible. Visual voicemail needs a carrier VVM client plus network, so it is out.
4. **SMS.** Reject-with-message goes through `Call.reject(true, text)` (API 23+, V: `reject(ZLjava/lang/String;)V`). Which component actually sends the text (Telecom, the default SMS app, or the dialer's own `SEND_SMS`) is **? and needs a device spike**; the SMS runtime permission is itself a restricted setting for sideloaded apps on Android 15 (V, 9to5google), and Xiaomi adds its own "Send SMS" app-op. The safe fallback is `ACTION_SENDTO sms:` with the text prefilled (opens the default SMS app, which may be Rovsti Messages).
5. **HyperOS.** Xiaomi's own dialer is "Phone" with its own blocklist, recorder and smart dial; Rovsti Phone cannot read or share the Xiaomi harassment filter. The system `BlockedNumbers` provider is the shared store. Behaviour of the system provider versus Xiaomi's filter on HyperOS is **?** (a German forum guide says HyperOS suppresses blocked calls before the log; weak evidence).
6. **Layout rule from the owner.** Controls appear in reserved slots and are disabled (not removed) when unsupported, so the main layout never shifts. This supersedes FR-17's "shown only when supported" wording (the capability gate stays; the visibility rule changes).

## 3. Comparison table

Columns: **G** Google Phone, **S** Samsung Phone, **X** Xiaomi/HyperOS Dialer, **A** Apple Phone. "Y" = has it, "-" = no or unknown, "K" = from knowledge only. "Local?" = feasible with no network and no privileged permission. "Now" = status in Rovsti Phone v0.1. Effort S (about one story), M (2 to 4 stories), L (5+ or a spike). Value is for the owner (H/M/L).

| # | Feature | Who has it | Android API (since API, verified in SDK 36.1) | Local-only feasible? | Effort | Value | Now in Rovsti Phone? |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Audio route picker: earpiece, speaker, wired, each Bluetooth device by name, live updates | G Y, S Y, X Y, A Y (K) | `InCallService.onAvailableCallEndpointsChanged`, `onCallEndpointChanged`, `requestCallEndpointChange(CallEndpoint, Executor, OutcomeReceiver)` (34); `CallEndpoint.getEndpointName()`, `getEndpointType()`, `getIdentifier()`; types EARPIECE, BLUETOOTH, WIRED_HEADSET, SPEAKER, STREAMING, UNKNOWN (V). `setAudioRoute` and `CallAudioState` deprecated in 34 | Yes | M | H (owner asked) | Deferred. Plumbing exists; type-only toggle (FR-15) |
| 2 | Hearing-aid route and indicator | S Y (K), G Y (K, via Bluetooth), A Y (MFi, K) | No `CallEndpoint` type for hearing aids (V, AOSP). A BLE hearing aid shows as a TYPE_BLUETOOTH endpoint with its device name. `TelephonyManager.isHearingAidCompatibilitySupported()` (23) reports the handset's HAC support. The hearing-aid pairing UI is system Settings | Partly: name and route yes; HAC icon only as "supported" flag; no special mode | S | M | New. Shortcut to Bluetooth/Accessibility settings only |
| 3 | Add call | G Y, S Y, X Y, A Y | Place a second call with `TelecomManager.placeCall` while one is active (23); network puts the first on hold. `InCallService.canAddCall()` is public (V). `Call.Details.CAPABILITY_ADD_PARTICIPANT` (31) for adding to an existing conference | Yes | S | H (owner asked) | Deferred |
| 4 | Swap calls (two calls, one held) | G Y, S Y, X Y, A Y | `Call.hold()`/`unhold()`, and `Call.swapConference()` with `CAPABILITY_SWAP_CONFERENCE` (23, V) | Yes (carrier dependent) | S | H | Deferred |
| 5 | Merge into conference | G Y, S Y, X Y, A Y | `Call.mergeConference()`, `Call.conference(Call)`, `Call.getConferenceableCalls()`, `CAPABILITY_MERGE_CONFERENCE` (23, V) | Yes (carrier dependent: needs 3-way conference) | S | H (owner asked) | Deferred |
| 6 | Conference management: participant list, end one participant, split one out | G Y (where network supports), S Y, A Y | `Call.getChildren()`, `Call.getParent()`, `Call.splitFromConference()`, `CAPABILITY_MANAGE_CONFERENCE`, `CAPABILITY_SEPARATE_FROM_CONFERENCE`, `CAPABILITY_DISCONNECT_FROM_CONFERENCE` (23, V). IMS conference participants may be missing on some carriers (?) | Yes, gated per capability | M | M | Deferred |
| 7 | Waiting call choices: answer and hold, end and answer, decline, decline with message | G Y, S Y, X Y, A Y | `Call.answer`, `hold`, `disconnect`, `reject(true, text)` (23) | Yes | S | H | Partly: FR-20 answers by ending the current call |
| 8 | Dual-SIM account picker at dial time | G Y, S Y, X Y, A (eSIM, Y) | `TelecomManager.getCallCapablePhoneAccounts()` (23, needs READ_PHONE_STATE), `getUserSelectedOutgoingPhoneAccount()` (29), `getDefaultOutgoingPhoneAccount(scheme)` (23); `Call.phoneAccountSelected(handle, setDefault)`; `TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE` on `placeCall` (V) | Yes | M | M (single SIM today? owner not stated) | Deferred; FR-11 auto-selects |
| 9 | SIM label/colour on live call and in Recents | G Y, S Y, X Y | `Call.Details.getAccountHandle()`; `CallLog.Calls.PHONE_ACCOUNT_ID` (21); `PhoneAccount.getLabel()`, `getIcon()`; `SubscriptionManager.getActiveSubscriptionInfoList()` (22) | Yes | S | M | Deferred |
| 10 | HD / Wi-Fi calling / VoLTE indicators | G Y, S Y, X Y | Live: `Call.Details.PROPERTY_HIGH_DEF_AUDIO`, `PROPERTY_WIFI` (23). History: `CallLog.Calls.FEATURES_HD_CALL` (26), `FEATURES_WIFI` (26), `FEATURES_VOLTE` (30). There is no live `PROPERTY_VOLTE` (V: absent from SDK) | Yes | S | M | New |
| 11 | Call blocking: block number, blocked list, unblock | G Y, S Y, X Y, A Y | `BlockedNumberContract` (24): insert/delete on `BlockedNumbers.CONTENT_URI`, `isBlocked`, `canCurrentUserBlockNumbers`. Default dialer/SMS app only (V, AOSP) | Yes | M | H (owner asked) | Deferred. Blocked Recents entries hidden |
| 12 | Block from Recents, Contact Sheet, call details | G Y, S Y, X Y | same as 11 | Yes | S | H | Deferred |
| 13 | Block or silence unknown/private callers | G Y (K), A Y (V, "Silence" unknown callers), S (K) | `CallScreeningService.onScreenCall` (24) needs the call-screening role or default dialer; behaviour as sideloaded app **?** | Possibly | M | L | New, spike only |
| 14 | Reject with quick reply message | G Y, S Y (V, up to 6 messages), X Y (K), A Y | `Call.reject(boolean rejectWithMessage, String text)` (23, V), `Call.getCannedTextResponses()`, `CAPABILITY_RESPOND_VIA_TEXT`. Sender of the SMS is **?** (see 2.4) | Yes (SMS may be carrier-billed) | M | H (owner asked) | New |
| 15 | Edit quick replies | S Y (V), G Y (K) | App-local storage | Yes | S | H | New |
| 16 | Speed dial (digit to contact) | S Y (V), G Y (K), X Y (K), A Y | App-local map digit to contact lookup key; no system API | Yes | S | M | Deferred |
| 17 | Favorites reorder / frequent contacts | G Y (K), A Y | `STARRED` has no order. Local order keyed by `LOOKUP_KEY`; frequent from `CallLog` counts (pure Rust) | Yes | M | M (owner asked "reorder") | New |
| 18 | Voicemail shortcut (long-press 1) and voicemail Recents type | G Y, S Y (V, speed dial 1), X Y, A Y | `TelecomManager.getVoiceMailNumber(handle)` (23), `isVoiceMailNumber` (23), `voicemail:` URI; `CallLog.Calls.VOICEMAIL_TYPE` | Yes (calling the number). A "new voicemail" badge needs carrier VVM or message-waiting data: **?** | S | M | Deferred (icon for type exists) |
| 19 | Visual voicemail, transcription | G Y, A Y (Live Voicemail, V) | `VoicemailContract` (14); carrier VVM needs network | **No** (network, carrier) | - | - | Out |
| 20 | T9 / smart-dial results on the keypad | S Y, X Y, G Y (K), A (K) | App-local pure function over the loaded contact index | Yes | M | H | Deferred (FR-12 matches by number only) |
| 21 | Contact create (prefilled from number) | all Y | `Intent.ACTION_INSERT_OR_EDIT` / `ContactsContract.Intents.Insert` (PHONE, NAME) (5, V), `ACTION_EDIT`, `Intents.SHOW_OR_CREATE_CONTACT` | Yes | S | H | Partly: "Edit in Contacts" and "Add to contacts" hand-offs |
| 22 | Contact edit and delete inside the app | all Y | Provider writes with `WRITE_CONTACTS` (declared): `ContentProviderOperation` batch on `RawContacts`/`Data`; delete via `Contacts.CONTENT_LOOKUP_URI`. Multi-raw-contact aggregation and synced accounts (Mi Account) make a full editor risky | Yes (editor L, delete S) | L / S | M | Deferred |
| 23 | Contact sheet quick actions: message, copy, share, block, favorite, call details | G Y, S Y, X Y, A Y | `ACTION_SENDTO sms:`, clipboard, `ACTION_SEND` text or vCard (`Contacts.CONTENT_VCARD_URI`) | Yes | S | H | Partly (call, star) |
| 24 | Call details per number: history, durations, SIM, HD, actions | G Y, S Y, X Y, A Y | `CallLog.Calls` query by number: `DURATION`, `DATE`, `TYPE`, `PHONE_ACCOUNT_ID`, `FEATURES_*`, `GEOCODED_LOCATION` | Yes | M | H | New (number sheet only) |
| 25 | Delete call log entries (one, group, all) | all Y | `ContentResolver.delete(CallLog.Calls.CONTENT_URI)` with `WRITE_CALL_LOG` (declared) | Yes | S | M | Deferred |
| 26 | Settings screen | all Y | App-local | Yes | M | H | New (only the Setup sheet exists) |
| 27 | Default SIM / "ask every time" | G Y, S Y, X Y | read `getUserSelectedOutgoingPhoneAccount`; changing the system default is a system setting: open `TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS` (23) | Read yes; write via system page | S | M | New |
| 28 | Sort order and display name format (first/last) | G Y, S Y, X Y | Contacts columns `DISPLAY_NAME_PRIMARY` and `DISPLAY_NAME_ALTERNATIVE` selectable in the query | Yes | S | M | New |
| 29 | Keypad tones and haptics settings | G Y, S Y, X Y | App-local; system flag `Settings.System.DTMF_TONE_WHEN_DIALING` (readable) | Yes | S | M | Partly (haptics exist, no setting) |
| 30 | Vibrate on connect / on end | S Y (K), G Y (K) | `Vibrator.vibrate(VibrationEffect)` (26), needs `VIBRATE` (normal, install-time) | Yes | S | L | New |
| 31 | Ringtone shortcut | all Y | Telecom plays the ringtone. Open `Settings.ACTION_SOUND_SETTINGS`; per-contact ringtone via `Contacts.CUSTOM_RINGTONE` (writes need WRITE_CONTACTS; picker via `RingtoneManager.ACTION_RINGTONE_PICKER`) | Yes | S | M | New |
| 32 | Flip to silence / silence ringer button | G Y (Pixel, K), S Y (palm/flip, K), X Y (K), A Y (side button) | `InCallService.onSilenceRinger()` (24, V); `TelecomManager.silenceRinger()` (23) is believed to need default-dialer or privileged permission **?**; flip uses the accelerometer in app code | Yes (flip: foreground wake sensor use while ringing) | S/M | M | New. Volume-key silence is system behaviour (FR-21) |
| 33 | Answer/decline gestures (slide up, swipe) | G Y, S Y, X Y, A Y | App UI only | Yes | M | H (design priority) | Partly: buttons only |
| 34 | Proximity screen off | all Y | exists (`ProximityController`); add a toggle | Yes | S | L | Built (Epic 5.7) |
| 35 | Emergency numbers: label, never gated; emergency info shortcut | all Y | `TelephonyManager.isEmergencyNumber(String)` (29, V). Emergency info intent (`android.settings.EDIT_EMERGENCY_INFO`) is not in the SDK public list (V) so treat as **?** per OEM | Yes | S | M | Partly (FR-10 passes emergency numbers) |
| 36 | Share / copy number | all Y | clipboard, `ACTION_SEND` | Yes | S | M | New |
| 37 | Call recording | G Y (Pixel, region), S Y (region), X Y (region, V weak) | No public API for third parties; `CAPTURE_AUDIO_OUTPUT` is privileged (K); accessibility-service hacks are restricted on Android 15 and legally risky | **No** | - | - | Out (deferred list item removed from the roadmap) |
| 38 | Caller-ID / spam lookup, Call Screen, Hold for Me, Live Voicemail | G Y (V), A Y (V) | Cloud / on-device AI services | **No** (network or Google/Apple services) | - | - | Out |
| 39 | RTT / TTY | G Y, S Y, A Y | `Call.sendRttRequest()` (26), `PhoneAccount.CAPABILITY_RTT` (26), `Call.Details.PROPERTY_RTT` (28), `TelecomManager.isTtySupported()` (28), `ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS` (23) | Possible but carrier gated | L | L | Out. A Settings shortcut to the system accessibility page is in |
| 40 | Video calls | G Y, S Y, A Y | `VideoProfile`, `CAPABILITY_SUPPORTS_VT_*` | Carrier gated | L | N/A (owner: video N/A) | Out |
| 41 | Call forwarding, call waiting, caller-ID settings | G Y, S Y (V, call waiting), X Y | Carrier network settings; open system page `TelecomManager.ACTION_SHOW_CALL_SETTINGS` (V, present) | Shortcut only | S | L | New (shortcut only) |
| 42 | Call notes / contact notes | A (K), few Android | App-local table; Rovsti rule: no own contacts DB | Yes | M | L | Out for now (backlog) |
| 43 | Backup/restore of local app data | Y via cloud on others | Local JSON via Storage Access Framework (`ACTION_CREATE_DOCUMENT`/`OPEN_DOCUMENT`, no permission) | Yes | S | M | New |
| 44 | Theme follows system | all Y | built | Yes | - | - | Built (FR-37) |
| 45 | Assisted dialing (country code prefix) | G Y | `Call.Details.PROPERTY_ASSISTED_DIALING` (30); carrier-config driven | Possible | S | L | Out |
| 46 | Wearable / Android Auto / car Bluetooth call UI | G Y | Car mode uses the same `InCallService`; separate templates | - | L | L | Out |
| 47 | Handover (Wi-Fi to LTE) controls | - | `Call.handoverTo` (28), system-managed | - | - | - | Out |

Totals: 47 rows. 9 are out of scope (19, 37, 38, 39, 40, 42, 45, 46, 47). 2 are already built (34, 44). Row 13 is a spike only. The other 35 are gaps or partial gaps that the epics below cover (counted as the gap total: 36 with the spike).

## 4. What the owner's two named complaints need

**Audio route picker (owner).** The data path exists. Kotlin gets the full `CallEndpoint` list with `getEndpointName()` (the device name for Bluetooth) and `getIdentifier()` (a `ParcelUuid`). Today it is reduced to a type and the first endpoint of that type is chosen, so two paired Bluetooth devices cannot be told apart, and the UI shows one icon. The fix is wire-level: send the list (identifier, type, display name, selected flag) and select by identifier. The list is live: `onAvailableCallEndpointsChanged` fires when a headset connects or disconnects mid-call. A hearing aid is reported as a Bluetooth endpoint (no dedicated type, V), so the picker shows its name with a Bluetooth glyph and may add a small "hearing aid" hint only when the name or class says so (**?**, best effort, not required).

**Conference shortcuts (owner).** `Call.getDetails().can(...)` capabilities decide everything. The In-Call screen needs a fixed 3 x 2 control grid whose slots are: Mute, Keypad, Audio, Add call, Hold (or Swap when two calls exist), Contacts/Merge. Slots stay in place; unsupported ones are dimmed. A merge action replaces "Hold" after a second call exists, and a conference shows a participant list. The behaviour depends on the carrier supporting three-way calling (**? per network**), which the capability bits reflect.

## 5. Prioritised proposals, grouped into epics

Priority P1 = do next (owner-requested or high value, low risk), P2 = follow, P3 = optional. Effort per epic is the sum of its stories. Epics continue the numbering after Epic 13 (signed release, which stays the last v0.1 epic). FR ids below are the new ones added to the PRD (section 4.11 onwards).

| Epic | Name | Priority | FRs | Effort | Why first / dependency |
| --- | --- | --- | --- | --- | --- |
| 14 | Audio routes and devices | P1 | FR-41, FR-42, FR-43 | M | Owner's first complaint. No new dependencies. Gives the wire list reused by Settings |
| 15 | Multi-call and conference | P1 | FR-44 to FR-48 | M to L | Owner's second complaint. Needs the In-Call control grid redesign (no layout shift) |
| 16 | Settings, SIM and local data | P1 | FR-49, FR-50, FR-51, FR-71, FR-72, FR-73 | M | A Settings screen is the home for toggles from later epics. Dual SIM lives here |
| 17 | Blocking | P1 | FR-53, FR-54, FR-55 | M | Default-dialer provider; contributes the Block action used by 19 and 21 |
| 18 | Incoming call controls | P1 | FR-56, FR-57, FR-58, FR-59 | M | Reject with message needs the SMS spike first (Story 18.1) |
| 19 | Contacts management and quick actions | P2 | FR-60 to FR-64 | M (L with the stretch editor) | Hand-offs first, native editor last |
| 20 | Keypad intelligence | P2 | FR-65 to FR-68 | M | T9, speed dial, voicemail, emergency labelling |
| 21 | Call details and history tools | P2 | FR-52, FR-69, FR-70 | M | Uses Epic 17 (block) and 16 (SIM labels) |

Suggested order: 14, 15, 16, 17, 18, then 19 to 21 in any order. Each epic ends with a signed release APK for the owner (memory: phone tests use release).

### Epic 14: Audio routes and devices (P1)

- 14.1 Wire: endpoint list DTO (identifier, type, name, selected, available) from `RovstiInCallService`; golden fixtures; select by identifier. Keep the old `AudioRoute` for compatibility.
- 14.2 Route picker UI: glass sheet/popover from the Audio control with one row per endpoint (icon by type, name, check mark on the current one), spring open, live list updates without the sheet jumping.
- 14.3 Audio control behaviour: tap toggles speaker/earpiece when no other endpoint exists; with a headset or Bluetooth present the tap opens the picker; the control label shows the current route name. Reduced motion respected.
- 14.4 Bluetooth and hearing-aid shortcuts: footer row "Bluetooth settings" and "Hearing aids" (accessibility settings), `BLUETOOTH_CONNECT` runtime request when names are missing, HAC support flag shown in Settings.

### Epic 15: Multi-call and conference (P1)

- 15.1 Registry: multi-call model, capability bits, parent/child lists, conferenceable calls (core + wire).
- 15.2 In-Call control grid: fixed 3 x 2 slots (Mute, Keypad, Audio, Add call, Hold/Swap, Merge/Contacts). Disabled, never hidden.
- 15.3 Add call: opens the keypad/contact picker over the In-Call screen; placing the call holds the first.
- 15.4 Two-call view: active card plus held card, tap held card or Swap to switch, both timers.
- 15.5 Merge and conference view: participant list, per-participant End and Split where capabilities allow, conference timer.
- 15.6 Waiting call choices: Answer and hold, End and answer, Decline, Decline with message (message part in 18).

### Epic 16: Settings, SIM and local data (P1)

- 16.1 Settings screen: opened from a header gear button next to Setup; one glass page; sections Calls, Sounds and haptics, Display, SIM, Quick replies, Data, System shortcuts. No fifth tab.
- 16.2 Local settings store extension (typed keys, defaults, migration).
- 16.3 SIM picker: shown when two or more call-capable accounts exist and the system default is "ask"; call-button long-press chooses a SIM; default-SIM row opens `ACTION_CHANGE_PHONE_ACCOUNTS`. Replaces FR-11's silent choice for dual-SIM.
- 16.4 SIM labels on the In-Call screen and Recents rows (only with two or more SIMs, so single-SIM layout is unchanged).
- 16.5 Sounds and haptics, display order, proximity toggle, flip-to-silence toggle.
- 16.6 Backup and restore of local data (favorites order, speed dial, quick replies, settings) to a JSON file via the system file picker.
- 16.7 System shortcuts: ringtone/sound settings, call forwarding/waiting (`ACTION_SHOW_CALL_SETTINGS`), accessibility/TTY/RTT page, Default apps.

### Epic 17: Blocking (P1)

- 17.1 Provider bridge: block, unblock, is-blocked, list (`BlockedNumberContract`), `canCurrentUserBlockNumbers` check, `Unsupported` on desktop.
- 17.2 Block/unblock actions in Recents row menu, number sheet, Contact Sheet, incoming screen (post-call "Block").
- 17.3 Blocked numbers list in Settings: virtualised list, swipe/tap to unblock, add a number manually.
- 17.4 Blocked-call handling: blocked entries stay out of Recents by default; a "Blocked calls" filter inside the blocked list shows them with the block reason (`CallLog.Calls.BLOCK_REASON`, 29).

### Epic 18: Incoming call controls (P1)

- 18.1 Spike: `Call.reject(true, text)` on the owner's HyperOS phone; record which component sends the SMS and whether permissions or restricted settings block it; choose the fallback (`sms:` hand-off). Story output: decision note.
- 18.2 Reject with message sheet from the Incoming screen and the heads-up notification (action "Message").
- 18.3 Quick replies editor (Settings): up to 6 texts, defaults "Can't talk now. I'll call you back." etc.; stored locally.
- 18.4 Silence ringer button on the Incoming screen (`silenceRinger`/system ringer path, verified on device) and flip-to-silence while ringing.
- 18.5 Answer/decline gestures: slide-to-answer with spring, swipe up for message, haptic ticks; buttons remain for accessibility.

### Epic 19: Contacts management and quick actions (P2)

- 19.1 Create/Add from a number or empty (`ACTION_INSERT_OR_EDIT` with phone and name extras); returns to the app and refreshes.
- 19.2 Delete contact with confirm and undo window (provider delete).
- 19.3 Contact Sheet quick actions row: Message, Copy, Share, Block, Call details, Favorite; copy/share on any number via long-press.
- 19.4 Favorites reorder (drag with springs, local order) and an optional "Frequent" strip computed from the call log.
- 19.5 Stretch: in-app editor (name, numbers with labels, delete number) for contacts with a single writable raw contact; others fall back to `ACTION_EDIT`.

### Epic 20: Keypad intelligence (P2)

- 20.1 T9 index in `rovsti_core` (letters to digits, prefix and word-start match, ranking), unit tested; results strip above the keypad, tap fills or calls.
- 20.2 Speed dial: assign digits 2 to 9 in Settings or from a Contact Sheet; long-press on the keypad key dials.
- 20.3 Voicemail: long-press 1 calls voicemail (`getVoiceMailNumber`), Voicemail tile in Recents filter; no badge unless the device exposes one (**?**).
- 20.4 Emergency: number recognised with `isEmergencyNumber`, button relabelled "Emergency call", no animation delay, never blocked; Emergency info shortcut in Settings if the intent resolves.

### Epic 21: Call details and history tools (P2)

- 21.1 Call details screen per number: header, history list (type, date, duration, SIM, HD/Wi-Fi/VoLTE chips from `CallLog.Calls.FEATURES_*`), totals; actions Call, Message, Copy, Share, Block, Add contact.
- 21.2 Delete entries: one, a group, all; confirm; Recents refresh.
- 21.3 Live quality chips on the In-Call screen from `PROPERTY_HIGH_DEF_AUDIO` and `PROPERTY_WIFI` in a reserved slot.

## 6. Scope decisions

### In scope
Rows 1 to 12, 14 to 18, 20 to 33, 35, 36, 41 and 43 of the table (the epics above), with the caveats stated. Row 13 is a spike. Rows 34 and 44 are built. Row 39 contributes only a Settings shortcut.

### Out of scope (with reasons)

| Item | Reason |
| --- | --- |
| Call recording (row 37) | No public API for third parties; `CAPTURE_AUDIO_OUTPUT` is privileged; accessibility hacks are restricted on Android 15 for sideloaded apps and carry legal risk. Removed from the roadmap, not only deferred |
| Caller-ID/spam lookup, Call Screen, Hold for Me, Live Voicemail (38) | Need network or Google/Apple services; violates "serverless, no INTERNET" |
| Visual voicemail (19) | Carrier VVM protocol plus network. Only the plain voicemail shortcut is in |
| Video calls (40) | Owner: not applicable |
| RTT/TTY (39) | Carrier-gated, low value for the owner. Shortcut to the system page is in Settings |
| Assisted dialing, handover, car/wearable UI (45, 46, 47) | Carrier or platform managed, low value, high cost |
| Call/contact notes (42) | Would need an own database; project rule is "providers stay the source of truth". Backlog |
| Unknown-caller silencing via `CallScreeningService` (13) | Spike only (Epic 18 may include a probe); behaviour as a sideloaded default dialer unverified |
| Cloud/sync backup | Local JSON export only (row 43) |

### Priorities check against the owner's rules
- **Fluid motion:** every new surface (route picker, SIM chooser, reject-message sheet, conference list) is a glass sheet with the existing interruptible springs; no new layout-animating lists.
- **Main layout never shifts:** the In-Call control grid is fixed; SIM chips and quality chips use reserved slots; the Audio label has a fixed width with ellipsis; lists use fixed row heights.
- **Raw touch feedback:** every new control uses the existing press-state (scale/brightness within one frame) and a haptic tick.
- **Performance by code:** T9 index prebuilt off-thread; blocked list, call details and conference lists virtualised; endpoint updates coalesced to one snapshot per frame.

## 7. Open questions

1. **Who sends the reject-with-message SMS** on HyperOS for a sideloaded default dialer, and does the Android 15 restricted-settings rule or Xiaomi's "Send SMS" app-op block it? (Story 18.1 decides.)
2. **`TelecomManager.silenceRinger()` permission:** default-dialer allowed, or privileged only? If not allowed, the Silence button uses the system ringer path (volume key emulation is not possible), and may be cut.
3. **Does the owner's carrier support three-way calling**, and does HyperOS expose IMS conference children? Capability bits decide at run time; acceptance needs a device test with two real calls.
4. **Dual SIM:** does the owner use two SIMs? If not, Epic 16.3/16.4 can drop to P3 and stay invisible on single-SIM (they are designed to be invisible then).
5. **HyperOS blocklist:** does the system `BlockedNumbers` provider also drive Xiaomi's own filter, and do blocked calls reach the log? Test on device.
6. **Voicemail badge:** is there any local signal for "new voicemail" on this carrier without VVM? Probably not; confirm.
7. **Contact editor depth:** in-app editor (19.5) versus keeping the system editor hand-off. Default: hand-off; the editor is a stretch.
8. **Emergency info shortcut:** intent availability on HyperOS unverified.

## 8. Competitor feature evidence summary

- Samsung Phone settings (V): call alerts and ringtones, answering and ending calls, quick decline messages (maximum 6), call display while using apps, block numbers, call waiting; speed dial uses long-press and digit 1 is voicemail.
- Google Phone (V partial): Call Screen quick responses ("Is it urgent?", "I'll call you back"), Hold for Me, Take a Message; blocked numbers and spam warnings (V, app listing). Flip to Silence, speed dial and quick-reply editing are (K).
- Apple (V partial): Screen Unknown Callers (Never, Ask Reason for Calling, Silence), Live Voicemail transcription, call filtering lists; Add Call and merge are (K).
- Xiaomi (V weak): block from Recents, recording availability varies by ROM region; smart dial, speed dial, dual SIM are (K).
