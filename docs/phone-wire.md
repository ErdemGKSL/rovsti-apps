# Rovsti Phone wire table (v0.2)

The single place that lists every field exchanged between Kotlin and Rust
(AD-4, AD-5, AD-10, AD-23 to AD-31). Rust types: `rovsti_core::wire`. Kotlin
mirror: `crates/rovsti_android/kotlin/dev/rovsti/bridge/WireJson.kt`. Goldens:
`crates/rovsti_core/tests/fixtures/*.json`, read by `tests/wire_golden.rs` and
`WireFixturesTest.kt`.

**Versioning rule.** An added field keeps `"v": 1` (every new field is optional
with a default, readers ignore unknown fields, every enum has an `unknown`
catch-all). A changed or removed field bumps `v`. Rust rejects a snapshot whose
`v` is higher than it knows (`CallSnapshot::accept`) and keeps showing the last
good one. Marker below: `+` additive in v0.2 (stays `v: 1`), `=` unchanged
since v0.1.

## 1. Kotlin to Rust: call snapshot (event kind `calls`)

| Field | Type | Opt | Owner | Story | Default when absent |
|---|---|---|---|---|---|
| `v` = | int | yes | Kotlin | 3.2 | 1 |
| `seq` = | long | yes | Kotlin | 3.2 | 0 (lower than held is ignored) |
| `now_ms` = | long | yes | Kotlin | 3.2 | 0 |
| `keyguard_locked` = | bool | yes | Kotlin | 3.2 | true (AD-3) |
| `audio.muted` = | bool | yes | Kotlin | 3.2 | false |
| `audio.route` = | enum earpiece/bluetooth/wired_headset/speaker/unknown | yes | Kotlin (derived from endpoints) | 3.2 | unknown |
| `audio.routes[]` = | enum[] | yes | Kotlin (distinct endpoint types) | 3.2 | [] |
| `audio.endpoints[]` + | `{id, type, name?, selected, hint?}` | yes | Kotlin `AudioPath` | 14.1 | [] (Rust shows `route`) |
| `audio.endpoints[].id` + | string (session handle) | no | Kotlin | 14.1 | n/a, never persisted |
| `audio.endpoints[].type` + | earpiece/bluetooth/wired_headset/speaker/unknown | yes | Kotlin | 14.1 | unknown |
| `audio.endpoints[].name` + | string; sent for bluetooth, wired, unknown | yes | Kotlin | 14.1 | none (Rust names earpiece and speaker) |
| `audio.endpoints[].selected` + | bool, at most one true | yes | Kotlin | 14.1 | false |
| `audio.endpoints[].hint` + | `hearing_aid` | yes | Kotlin | 14.1, 14.8 | none |
| `foreground_call_id` = | int | yes | Kotlin (`Focus`) | 3.2 | none |
| `waiting_call_id` = | int | yes | Kotlin | 9.2 | none |
| `background_call_id` + | int | yes | Kotlin | 15.x | none |
| `ended_call_id` + | int | yes | Kotlin | 15.x | none |
| `can_add_call` + | bool | yes | Kotlin | 15.x | false |
| `can_merge` + | bool | yes | Kotlin | 15.x | false |
| `can_swap` + | bool | yes | Kotlin | 15.x | false |
| `pending` + | answer/swap/merge | yes | Kotlin `PendingOp` | 15.x | none |
| `pending_failed` + | answer/swap/merge | yes | Kotlin | 15.x | none |
| `ringer_silenced` + | bool | yes | Kotlin | 17.x | false |
| `silenced_by` + | user/volume/flip | yes | Kotlin | 17.x | none |
| `selecting_account` + | bool | yes | Kotlin | 16.3 | false |
| `accounts[]` + | `{key, label}` | yes | Kotlin | 16.3 | [] |
| `calls[].id` = | int | no | Kotlin | 3.2 | n/a |
| `calls[].state` = | dialing/ringing/active/holding/disconnected/unknown | yes | Kotlin | 3.2 | unknown |
| `calls[].number_raw`, `normalized` = | string | yes | Kotlin | 3.2 | "" |
| `calls[].display_name`, `contact_id` = | string / long | yes | Kotlin | 3.2 | none |
| `calls[].connect_time_ms` = | long (0 = not connected) | yes | Kotlin | 3.2 | 0 |
| `calls[].can_hold` = | bool (equals `caps.hold`) | yes | Kotlin | 3.2 | false |
| `calls[].is_incoming` = | bool | yes | Kotlin | 3.2 | false |
| `calls[].disconnect_cause` = | enum | yes | Kotlin | 3.2 | none |
| `calls[].parent_id` + | int (conference parent) | yes | Kotlin | 15.x | none |
| `calls[].children[]` + | int[] | yes | Kotlin | 15.x | [] |
| `calls[].caps` + | bitmask (section 5) | yes | Kotlin | 15.x | 0 |
| `calls[].props` + | bitmask (section 5) | yes | Kotlin | 15.x | 0 |
| `calls[].account_key` + | opaque string | yes | Kotlin | 16.3 | none |
| `calls[].display_name_alt` + | string | yes | Kotlin | 19.x | none |
| `calls[].hold_since_ms` + | long | yes | Kotlin | 15.x | none |

Note: `ringer_silenced` and `silenced_by` are snapshot-level (story 14.0b); AD-29
says per call. They move per call with the story that uses them (17.x) if the
two ever differ; until then the snapshot-level pair is the contract.

## 2. Provider pulls (Rust calls Kotlin, envelope `{v, ok, error?, data?}`)

`pullCallLog`, `pullContacts`, `pullFavorites` keep their calls (`=`); their
rows gained optional fields (below). The error enum is
`permission|unavailable|refused|not_found|unknown` (`wire::WireError`).
Rust shapes landed in 14.9; the Kotlin side of each arrives with the story in
brackets.

| Provider | Rust type | Fixture | Kotlin story / JVM test |
|---|---|---|---|
| `phone_accounts` | `PhoneAccountsResponse` (`PhoneAccounts{accounts[{key,label,slot_index?,color_argb,sim,enabled}],default_key?,ask}`) | `phone_accounts.json` | 16.3 `WireFixturesTest` |
| `voicemail_info` | `VoicemailInfoResponse` | `voicemail_info.json` | 20.x `WireFixturesTest` |
| `emergency_numbers` | `EmergencyListResponse` | `emergency_list.json` | 19.x `WireFixturesTest` |
| blocklist rows | `BlocklistResponse` (`numbers[{id,number_raw,normalized}]`) | `blocklist.json` | 18.x `WireFixturesTest` |
| contacts, new fields `lookup_key`, `display_name_alt`, `sort_key`, `sort_key_alt` | `WireContact` | `contacts_v2.json` | 19.x `WireFixturesTest` |
| call log, new fields `account_key`, `block_reason` (`user|system|unknown`), `features` (details only), kinds `voicemail`, `blocked` | `CallLogEntry` | `call_log_v2.json` | 18.x, 21.x `WireFixturesTest` |
| `SettingsSync{proximity,flip_to_silence,vibrate_connect,vibrate_end,has_replies,keypad_tones?,haptics,name_format}` (Rust pushes via `syncSettings(json)`) | `SettingsSync` | `settings_sync.json` | 14.12 / 17.x `WireFixturesTest` |
| envelope errors | `WireError` | `envelope_errors.json` | 18.x `WireFixturesTest` |
| one-shot payloads | `DocumentResult`, `RingtoneResult`, `UiRequest` | `document_result_*.json`, `ringtone_result.json`, `ui_request.json` | 21.x, 17.x, 16.x |

A v0.1 row (no new field) parses with every new field defaulted
(`v01_rows_default_every_new_field`).

## 3. One-shot events (Kotlin to Rust, `RovstiBridge.emit`)

| Kind | Payload | Story |
|---|---|---|
| `calls` = | `CallSnapshot` | 3.2 |
| `call_log_changed` = | none | 3.3 |
| `contacts_changed` = | none | 3.3 |
| `permissions_changed` = | none | 3.4 |
| `sims_changed` + | none | 16.3 |
| `voicemail_changed` + | none | 20.x |
| `blocked_changed` + | none | 18.x |
| `document_result` + | `DocumentResult{op,ok,name?,text?,error?}` | 14.9 (shape), 21.x |
| `ringtone_result` + | `RingtoneResult{ok,title?}` | 14.9 (shape), 17.x |
| `ui_request` + | `UiRequest{kind,call_id?}` (`show_dialer|show_call|show_recents`) | 14.9 (shape), 16.x |

## 4. Rust to Kotlin commands (static methods on `RovstiBridge`)

All take String, Int or Boolean only (AD-5); all return Boolean or an envelope
string and never throw.

| Command | Story | Notes |
|---|---|---|
| `callAnswer`, `callReject`, `callDisconnect`, `callHold`, `callMute`, `callDtmf`, `callDtmfStop`, `callAnswerReplacing`, `callIncomingFrame` = | 4.x to 5.6 | id-addressed, idempotent |
| `callRoute(name)` = | 4.7 | Phone/Speaker toggle only (resolves the unique earpiece or speaker endpoint) |
| `callRouteEndpoint(id)` + | 14.1 | unknown id returns false; a `CallEndpointException` forces a rebuild |
| `openSystem(page)` + | 14.4 | envelope `{v, ok, error?, data?:{fallback?}}` |
| `sendSmsTo`, `copyText`, `shareText` + | 14.4 | Boolean |
| `addContact(number)` = / changed | 14.4 | `ACTION_INSERT_OR_EDIT` |
| `hearingAidCompatible()` + | 14.8 | Boolean |
| `diagnostics(probe)` + | 14.0f | one `pass|fail|unknown` line per check |

## 5. Bit constants (Rust `wire::caps|props|features`, Kotlin `WireJson.kt`)

`caps`: hold=1, support_hold=2, merge=4, swap=8, manage=16, split=32,
disconnect_participant=64, respond_via_text=128, mute=256, add_participant=512.
`props`: conference=1, hd=2, wifi=4, emergency=8.
Log `features`: hd=1, wifi=2, volte=4, video=8.
Unit tests on both sides pin each value and assert no two bits of one mask
overlap.

## Orphan-field rule

A field in this table that no later story's Covers names is added to the
completion notes of the nearest owning story. Story ids marked `x` (15.x,
17.x, ...) stand for "the story of that epic that first reads the field".
