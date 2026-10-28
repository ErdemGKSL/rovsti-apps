# Verification review: ARCHITECTURE-SPINE v0.2 (AD-23..AD-36, spike table)

Lens: every Android API / behaviour claim checked against real sources. Spine not edited.

Sources used
- Local SDK `platforms/android-36.1/data/api-versions.xml` (API levels, existence), `activity_actions.txt`, `broadcast_actions.txt`, `annotations.zip` (RequiresPermission).
- AOSP (android.googlesource.com, main): `Call.java`, `TelecomManager.java`, `TelephonyManager.java` (header part), `KeyguardManager.java`, `BlockedNumberContract.java`, `AndroidManifest.xml` (framework-res), Telecomm `RespondViaSmsManager.java`, `CallsManager.java`, Telephony `NotificationMgr.java`.
- Note: `activity_actions.txt` / `broadcast_actions.txt` are incomplete (no `android.telecom.*` actions, no `SHOW_VOICEMAIL_NOTIFICATION`), so absence there is not evidence.

## Verdict

Mostly sound. The API surface is almost entirely real and correctly levelled. Two real defects (a missing permission, an unmapped Telecom state set), three behaviours that are stated more strongly than the evidence allows, and one claim (voicemail) that can now be upgraded from "unverified" to verified, which changes the S9 design. No nonexistent API found.

## Defects (fix in the spine)

**V1 (high). `KeyguardManager.addKeyguardLockedStateListener` needs a permission not in AD-18/AD-34.**
AD-3 says "refreshed by `addKeyguardLockedStateListener` where available". SDK annotation: `@RequiresPermission(android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)` (permission exists, since 33). Without it the call throws `SecurityException`. Correction: add `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` to the AD-34 additions (I believe protection level is normal; this was not confirmable from the manifest chunks I could read, so verify with `aapt2 dump permissions` / a debug run, spike-style) and wrap registration in try/catch falling back to the existing receivers. Or drop the listener and use the receivers + `onResume` only. Keep "a missing value counts as locked".

**V2 (high). AD-4/AD-24 state mapping omits real `Call` states.**
`Call` has `STATE_NEW`, `CONNECTING`, `DIALING`, `RINGING`, `HOLDING`, `ACTIVE`, `DISCONNECTED`, `DISCONNECTING`, `SELECT_PHONE_ACCOUNT`, `PULLING_CALL` (25), `AUDIO_PROCESSING` (30), `SIMULATED_RINGING` (30). AD-4 maps to five names only, and AD-24's selection ("first Active, else Dialing, ... else Ringing") and `waiting_call_id` ("lowest-id Ringing") depend on it. A call in `SIMULATED_RINGING` (DND / silent ring, Android 11+) mapped to `unknown` would never become waiting/incoming. Also `Call.getState()` is deprecated since 31; use `Call.Details.getState()` (since 31). Correction: add an explicit table: NEW/CONNECTING/SELECT_PHONE_ACCOUNT/PULLING_CALL -> dialing (SELECT_PHONE_ACCOUNT carries `selecting_account`), RINGING and SIMULATED_RINGING -> ringing, AUDIO_PROCESSING -> ringing if incoming else dialing, DISCONNECTING -> keep previous or disconnected. Consider `Call.EXTRA_SILENT_RINGING_REQUESTED` (29) and `EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB` (34) for AD-29 `ringer_silenced` (call is already silent, Silence control should hide).

**V3 (medium). AD-33 voicemail: S9 "unverified" can be replaced by verified AOSP behaviour, and it changes the design.**
From Telephony `NotificationMgr`: the broadcast `ACTION_SHOW_VOICEMAIL_NOTIFICATION` (API 26, public, `@SdkConstant BROADCAST_INTENT_ACTION`) is sent only to the default dialer package (`setPackage`) and only if `queryBroadcastReceivers` finds a receiver; it is sent with `READ_PHONE_STATE` required of the receiver, `FLAG_RECEIVER_FOREGROUND`, a 10 s temporary allowlist. Extras: `EXTRA_PHONE_ACCOUNT_HANDLE`, `EXTRA_IS_REFRESH` (27), `EXTRA_NOTIFICATION_COUNT` (only when the count is known; a count of 0 means clear), `EXTRA_VOICEMAIL_NUMBER`, and a PendingIntent `EXTRA_CALL_VOICEMAIL_INTENT` (or `EXTRA_LAUNCH_VOICEMAIL_SETTINGS_INTENT` when no number). The system posts its own notification only when the dialer path does not apply, i.e. when no receiver exists. Consequences for the spine:
1. "the system may then stop posting its own (unverified)" is verified: declaring the receiver suppresses the system notification regardless of what the receiver does, with no acknowledgment. The receiver MUST post a notification (use `EXTRA_CALL_VOICEMAIL_INTENT` as the tap action), otherwise the owner silently loses voicemail alerts. Do not ship the receiver "to count only".
2. The count can be absent (when `getVoiceMessageCount` is unsupported): handle "new voicemail, count unknown" and `count == 0` = clear. "Never a guessed badge" should read: badge only when a count extra is present.
3. Declare `android:permission="android.permission.READ_PHONE_STATE"` on the exported receiver so other apps cannot spoof the broadcast (the system sender holds it).
4. S9's "test receiver switched on in Diagnostics" must use `PackageManager.setComponentEnabledSetting` (the system checks for a receiver at send time, so a disabled-by-default component is a valid probe and also matches the "added only after S9 passes" rule).
Still unverified: whether HyperOS's own Telephony fork follows AOSP here (keeps S9).

**V4 (medium). AD-25 "answerHolding" consequences are more than the one stated.**
AOSP `CallsManager.holdActiveCallForNewCall`: active call holdable -> held. Not holdable but same ConnectionService ("same source") -> any already-held call from that service is disconnected first, then the active call is held. Different source (e.g. calls on different SIMs/accounts) and not holdable -> the active call is disconnected (unless emergency, then the incoming call is rejected). So with Active + Held + Ringing, "Hold and answer" can silently drop the already-held call, and the spine's "offered only when the current call has `caps.hold`" does not prevent it. Correction: in the Two scene, label the Waiting action "End held call and answer" or disable/replace Hold-and-answer when `background_call_id` exists; add this to S4's pass criterion. Which capability bit `canHold` tests (`CAPABILITY_HOLD` vs `CAPABILITY_SUPPORT_HOLD`) was not visible in the source I could read, so "`caps.hold` is the right gate" is unverified (add to S4).
Related: Telecom's own `canAddCall()` is false for an existing outgoing call, any emergency call, `EXTRA_DISABLE_ADD_CALL`, or when top-level calls reach `MAXIMUM_TOP_LEVEL_CALLS` (ringing included, children and external excluded). AD-24's rule "two non-ringing top-level calls" differs (a ringing call also blocks add in Telecom). Since the value comes from `InCallService.canAddCall()` this is only a doc mismatch: say "Telecom decides; the sentence in parentheses is illustrative".

## Claims checked and CORRECT (verified)

| Spine claim | Evidence |
| --- | --- |
| `CallEndpoint` API, `getIdentifier(): ParcelUuid`, types EARPIECE/BLUETOOTH/WIRED_HEADSET/SPEAKER/STREAMING/UNKNOWN, `InCallService.requestCallEndpointChange(endpoint, Executor, OutcomeReceiver)`, `onCallEndpointChanged`, `onAvailableCallEndpointsChanged`, `onMuteStateChanged`, `getCurrentCallEndpoint` | api-versions: all since 34 |
| Legacy `getCallAudioState`, `setAudioRoute`, `onCallAudioStateChanged` deprecated 34 but present; `CallAudioState.getSupportedRouteMask` | api-versions |
| `AudioManager.getAvailableCommunicationDevices` (31), `AudioDeviceInfo.TYPE_HEARING_AID` (28) | api-versions |
| `Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE`, `CAPABILITY_DISCONNECT_FROM_CONFERENCE` exist (javadoc: "Call is able to be separated from its parent Conference" / "individually disconnected when in a Conference"), so reading them on the child is consistent with the docs | `Call.java` |
| `CAPABILITY_MERGE_CONFERENCE` / `CAPABILITY_SWAP_CONFERENCE`: javadoc says "intended only for use by a Conference" (CDMA-style unmerged conference, merge button while an unmerged Conference is foreground), so `foreground caps.merge -> mergeConference()` and the else-branch `foreground.conference(other)` with `getConferenceableCalls()` is the right two-path design | `Call.java` |
| `PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL` (29), `PROPERTY_HIGH_DEF_AUDIO`, `PROPERTY_WIFI`, `PROPERTY_CONFERENCE`, `CAPABILITY_RESPOND_VIA_TEXT`, `CAPABILITY_MUTE`, `CAPABILITY_ADD_PARTICIPANT` (31), `CAPABILITY_SUPPORT_HOLD`, `CAPABILITY_HOLD` | api-versions |
| `Call.Callback.onChildrenChanged/onParentChanged/onConferenceableCallsChanged`; `InCallService.onCanAddCallChanged`, `canAddCall`, `onSilenceRinger` (24) | api-versions |
| `TelecomManager.silenceRinger()` (23): needs `MODIFY_PHONE_STATE`, javadoc also allows the dialer-role app. SDK annotation lists only MODIFY_PHONE_STATE, the dialer exemption is in the javadoc | annotations.xml + `TelecomManager.java` |
| `getUserSelectedOutgoingPhoneAccount()` (29, READ_PHONE_STATE): javadoc says apps must handle null = "no user-chosen default"; the setter doc says null means "the user will be asked each time". So `default_key == null` ~ ask-always. `setUserSelectedOutgoingPhoneAccount` needs MODIFY_PHONE_STATE and is `@SystemApi` | `TelecomManager.java` |
| `getCallCapablePhoneAccounts()` READ_PHONE_STATE, "includes only accounts explicitly enabled by the user"; `getVoiceMailNumber(handle)` (23) READ_PHONE_STATE, null if unset; `placeCall` needs CALL_PHONE (or MANAGE_OWN_CALLS) | annotations + source |
| `SubscriptionManager.getActiveSubscriptionInfoList()` READ_PHONE_STATE; `TelephonyManager.createForPhoneAccountHandle` (26); `getSubscriptionId(PhoneAccountHandle)` (30); `OnSubscriptionsChangedListener.onSubscriptionsChanged` | api-versions |
| `TelephonyManager.getEmergencyNumberList()` (29): `@RequiresPermission(READ_PHONE_STATE)`; returns `Map<Integer, List<EmergencyNumber>>`, not strings (see N2) | annotations.xml |
| `isEmergencyNumber(String)` (29), no permission annotation | api-versions/annotations |
| `TelephonyManager.getVoiceMessageCount()` is not public (absent from api-versions) | api-versions |
| `KeyguardManager.requestDismissKeyguard(Activity, KeyguardDismissCallback)` (26) public; the 3-arg variant with a message is `@SystemApi`. Immediate dismiss for non-secure keyguard; the activity must be visible (showWhenLocked) or `onDismissError` fires | `KeyguardManager.java` |
| `isKeyguardLocked` (16) returns true while occluded by an incoming-call activity, true regardless of screen on/off | `KeyguardManager.java` |
| `VibrationEffect.createPredefined(int)` and `EFFECT_CLICK` (29); `Vibrator.vibrate(VibrationEffect)` (26) needs `VIBRATE` (normal) | api-versions, annotations, manifest |
| `BlockedNumberContract`: access limited to system, default SMS app, default dialer, carrier apps; `canCurrentUserBlockNumbers` must be called first (false -> SecurityException); insert of an existing number replaces; no update; `unblock` returns rows deleted; `isBlocked` matches original and E.164; columns `_id`, `original_number`, `e164_number`; `isBlocked`/`unblock` are `@WorkerThread` | `BlockedNumberContract.java`, annotations |
| `CallLog.Calls.BLOCKED_TYPE` (24), `BLOCK_REASON` (29) + three reason constants, `FEATURES_HD_CALL` (26), `FEATURES_WIFI` (26), `FEATURES_VOLTE` (30), `FEATURES_VIDEO` (21), `PHONE_ACCOUNT_COMPONENT_NAME`/`PHONE_ACCOUNT_ID` (21) | api-versions |
| Reject with message: Telecom itself sends via `SmsManager.getSmsManagerForSubscriptionId(sub of call's account).sendMultipartTextMessage` unless the connection has `CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION`; no permission check in Telecom, so the dialer needs no SEND_SMS. Toast: success toast only after all parts OK; failure toast on a non-OK part | `RespondViaSmsManager.java` |
| `SEND_SMS` is `dangerous`, `costsMoney|hardRestricted`; `READ_CALL_LOG` and `WRITE_CALL_LOG` are `dangerous|hardRestricted` (so "hard-restricted for sideloaded apps" is right for SEND_SMS, and the call-log pair is correctly treated as role-granted) | framework `AndroidManifest.xml` |
| `Notification.CallStyle.forIncomingCall(Person, declineIntent, answerIntent)` argument order | api-versions |
| Metadata `IN_CALL_SERVICE_UI` (23), `IN_CALL_SERVICE_RINGING` (24), `IN_CALL_SERVICE_CAR_MODE_UI` (29) | api-versions |
| Intent actions in the SDK list: `android.settings.HEARING_DEVICES_SETTINGS`, `android.telephony.action.EMERGENCY_ASSISTANCE`; neither has a public constant in 36.1 (spine's "no public constant" is correct). Constants exist: `Settings.ACTION_SOUND_SETTINGS`, `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` (24), `ACTION_BLUETOOTH_SETTINGS`, `ACTION_ACCESSIBILITY_SETTINGS`, `ACTION_APPLICATION_DETAILS_SETTINGS`, `TelecomManager.ACTION_SHOW_CALL_SETTINGS` = `android.telecom.action.SHOW_CALL_SETTINGS`, `ACTION_SHOW_CALL_ACCESSIBILITY_SETTINGS` (23), `ACTION_CHANGE_PHONE_ACCOUNTS` (23), `EXTRA_PHONE_ACCOUNT_HANDLE` (23), `Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS` (29), `RingtoneManager.ACTION_RINGTONE_PICKER` | api-versions, action lists |
| `<queries>` filters only package-visibility queries (`resolveActivity`), `startActivity` is not filtered | platform behaviour, consistent with spine |
| `ContactsContract` `CUSTOM_RINGTONE` (ContactOptionsColumns), `LOOKUP_KEY`, `DISPLAY_NAME_ALTERNATIVE`, `SORT_KEY_ALTERNATIVE`, `CONTENT_LOOKUP_URI` exist | api-versions |

## Stated as fact but unverified (tag as unverified or move into a spike)

U1. AD-3/AD-24 `requestDismissKeyguard` "opens the sheet only from a success callback": API correct, but callbacks are three (`onDismissSucceeded/Cancelled/Error`). Spec the Cancelled/Error path (sheet stays closed, no toast loop) and note MIUI/HyperOS secure-lock behaviour with `showWhenLocked` is untested; add to S2 or a new line in S4's procedure.

U2. AD-29 `InCallService.onSilenceRinger()` semantics ("fires on volume or power key"): the method exists (24), but I could not read its javadoc or the Telecom dispatch. Say "expected to fire when the ringer is silenced by hardware key; also possibly after our own `silenceRinger()`" and keep it inside S8 (it already is). Setting `ringer_silenced` from both paths is safe either way.

U3. AD-29 vibration: `Vibrator.vibrate(VibrationEffect)` with no attributes uses an UNKNOWN usage. Android filters/suppresses some usages from background apps and honours the user's touch/ring vibration settings and DND. The InCallService process is foreground during a call (phoneCall FGS), but "vibrate-on-end" fires as the call disconnects. Not verified; add to S8 (or a new S14 "vibrate on connect/end fires with screen off and phone locked"), and consider `VibrationAttributes` (public `vibrate(VibrationEffect, VibrationAttributes)` exists in later levels, not checked here) or the deprecated-33 `AudioAttributes` overload. Also `Vibrator` vs `VibratorManager.getDefaultVibrator` (31) for minSdk 34: use the latter.

U4. AD-27 "Enforcement is Telecom's, before an InCallService is bound for the call, so a blocked caller does not ring Rovsti": stated flatly. Plausible (Telecom block check filter, call logged as type 6) but not read from source. Rephrase "expected; S6 decides" (the `BLOCK_GUARD` fallback already exists). Also note Telecom may suppress the block for the system's emergency-contact exception window after an emergency call (platform behaviour, not verified here).

U5. AD-28 `caps.respond_via_text` as the gate for `reject(true, text)`: `CAPABILITY_RESPOND_VIA_TEXT` exists, but I did not find where Telecom populates it vs. `CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION`. Failure is silent when the call's subscription id is invalid (Telecom only logs, no toast). "Sent" in the UI is therefore best effort (already stated); add "can silently do nothing on an invalid subscription" to S7 notes.

U6. AD-34 "Android 15 restricted settings gate the sideloaded dialer role and call-log or SMS-class permissions": the hard-restricted flags on READ/WRITE_CALL_LOG and SEND_SMS are verified. The claim that restricted settings gate the dialer **role** itself is not verified (roles exempt their holders from hard restrictions for the role's permissions, as far as I know, but I did not confirm). Reword: "call-log and SMS permissions are hard-restricted; the role holder is expected to be exempt (S11, S12 record it)".

U7. AD-23 hearing aid: `AudioDeviceInfo.getProductName()` equal to the endpoint name is a heuristic; correctly marked best effort, keep S3.

U8. AD-23 `BLUETOOTH_CONNECT` "names come from Telecom, not from the app": unverified, correctly conditional on S3; keep.

U9. AD-26 `ask = default_key == null && accounts >= 2`: null also occurs on a single enabled account or when the OEM keeps its own SIM preference outside Telecom (HyperOS "calls preference"). Already covered by the S5 fallback, but state that `default_key` may be non-null while the OEM still prompts.

U10. AD-26 `account_key` equals the log's pair: for Telephony the handle id is the ICCID (or subscription id on older builds) and CallLog stores the same; plausible, S5 is the correct gate.

U11. AD-31 contact ringtone: `CUSTOM_RINGTONE` exists and is part of the Contacts options columns; update-ability on `Contacts.CONTENT_URI/{id}` was not confirmable from the provider chunk I could read, and "Telecom plays the ringtone it reads from CUSTOM_RINGTONE" is expected AOSP behaviour. S11 covers both; keep the "Ringtone row absent" fallback.

U12. AD-31 `ContactsWrite.delete` via `CONTENT_LOOKUP_URI/{lookup_key}` and "synced accounts remove it at next sync": standard but not verified here (S11 covers delete).

## Minor corrections

N1. AD-4/AD-23 text puts `onCanAddCallChanged` and `onMuteStateChanged` (InCallService) and `onChildrenChanged/onParentChanged/onConferenceableCallsChanged` (Call.Callback) in one list; fine, but name the owner class in the code comment so the Kotlin author registers each on the right object.

N2. AD-33 `emergency_numbers()` "returns digit strings from getEmergencyNumberList()": the API returns `Map<Int, List<EmergencyNumber>>`; flatten with `EmergencyNumber.getNumber()`. The list can be empty with no SIM, which the always-plus `112`/`911` already handles. `TelephonyManager.isEmergencyNumber(String)` (no permission) is a better live check where available; consider it in the Kotlin side while Rust keeps the pure compare.

N3. AD-24 `props.emergency` from `PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL`: constant exists (29) and a Kotlin number match; also consider `PROPERTY_EMERGENCY_CALLBACK_MODE`. Wire bits are the app's own constants, no conflict with Android's values.

N4. AD-33 `voicemail:` `placeCall(Uri.fromParts("voicemail", "", null), extras)`: `placeCall` is verified; the `voicemail:` scheme handling was not found in the javadoc chunk. It is the documented-by-usage path (Telecom special-cases the scheme), keep it under S9 ("the `voicemail:` call connects" is already the pass criterion).

N5. AD-34: add `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` to "Added" (V1); add `READ_PHONE_STATE` as a required receiver-permission attribute (V3.3); `READ_PHONE_STATE` is `dangerous` (runtime), so "Already declared, now used" must say it is requested at runtime before `phone_accounts`/`voicemail_info`/`emergency_numbers` (AD-31 style: return `permission` with no side effect). The spine already lists it as declared; confirm Setup requests it.

N6. Spike table: S3 should record `getCurrentCallEndpoint()` null at call start (AD-23 fallback relies on it); S4 should record the held-call disconnect case (V4) and which capability bit gates hold; S9 gains the notification-posting requirement and count-absent case (V3); S2 or S4 gains the lock-screen `requestDismissKeyguard` Cancelled/Error paths (U1).

## Top corrections, one line each

1. Add `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE` (or drop the listener) and try/catch it (V1).
2. Add a full `Call.STATE_*` mapping incl. `SIMULATED_RINGING`, `CONNECTING`, `AUDIO_PROCESSING`, `PULLING_CALL`; use `Details.getState()` (V2).
3. Voicemail: receiver presence suppresses the system notification (verified), so the receiver must post one; count may be missing; add `android:permission=READ_PHONE_STATE`; test via component enable (V3).
4. Hold-and-answer can disconnect the already-held call; relabel or disable it when a background call exists (V4).
5. Reword the unverified items U1..U6 as expected-and-spiked; fix N2 (emergency list is a Map of `EmergencyNumber`).
