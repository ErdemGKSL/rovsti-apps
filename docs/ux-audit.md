# UX quality audit (release APK hand-test bugs)

Systematic read-through of the four apps and the shared UI crates for the kind of bug that keeps
showing up in hand testing: taps leaking through overlays, Back gaps, text input, raw or swallowed
errors, dead buttons, double taps, stale UI, layout overflow, battery, dead-end permission flows,
panics, and date/time text.

Severity: **High** = crash, data loss, dead end, duplicate data or an action on the wrong target;
**Medium** = visible wrong behaviour; **Low** = polish.

Status: `[ ]` open, `[x]` fixed, `[-]` skipped (reason given), `[>]` deferred (other agent).
Files owned by the concurrent WhatsApp ticks/receipts work (`services/wa_gateway`,
`crates/rovsti_wa_proto`, `crates/rovsti_wa_client`, `apps/messages/src/conversation.rs`,
`apps/messages/src/inbox.rs`) are not edited here.

Result: 96 findings. 92 fixed (PC9 and PC13 only partly), 3 skipped (PP13, M21, C19), 1 deferred
(M24). Nothing here has been tried on a device yet.

## Phone: call screens (PC)

- [x] PC1 `apps/phone/src/main.rs:823` | High | With Settings open when a call arrives, the touch-down guard returns early, so End, Mute, Keypad and the DTMF keys on the In-Call screen do nothing.
- [x] PC2 `apps/phone/src/main.rs:834-856`, `incoming.rs:922` | High | A tab sheet (Setup, Contact, Number, Diagnostics) left open under a call blocks Mute, Keypad and DTMF and turns off the incoming drag.
- [x] PC3 `apps/phone/src/main.rs:700-734` | Medium | On the In-Call screen, Back first closes hidden layers under it (row menu, route list, sheet, Settings), so the first presses seem to do nothing.
- [x] PC4 `apps/phone/src/incoming.rs:1022-1037` | High | A WhatsApp Answer/Decline that fails (no gateway, accept error) still hides both buttons and the drag; Back does nothing until the caller hangs up.
- [x] PC5 `apps/phone/src/main.rs:375-378` | Medium | Every WhatsApp call error shows "The WhatsApp call could not be placed", only on the tabs, never on the call screen.
- [x] PC6 `apps/phone/src/incall.rs:1001-1005` | Medium | When a second caller rings, the call screen is shown again without restarting the status ticker, so the call timer freezes.
- [x] PC7 `apps/phone/src/incall.rs:1439-1497` | Medium | The Add call sheet stays open over the tabs after the call ends, with a stale pill; placing from it starts a new call.
- [x] PC8 `apps/phone/src/addcall.rs:179` | Low | The Add call pill timer is never switched off, so it wakes every second for the rest of the run (battery).
- [x] PC9 `apps/phone/src/waiting_card.rs:209-212,271` | Medium | "End held call and answer" and "Add replies in Settings" overflow their pills (no truncation). Partly fixed: label "End held & answer", pill text truncates; "Add replies in Settings" may still ellipsise on narrow pills.
- [x] PC10 `apps/phone/src/waiting_card.rs:314` | Low | The Waiting card does not occlude, so taps on its header/gaps reach the zone under it.
- [x] PC11 `apps/phone/src/incoming.rs:1167-1171` | Medium | Silence shows on incoming WhatsApp calls but does nothing (no call id).
- [x] PC12 `apps/phone/src/incoming.rs:188-191` | Low | After Silence, the invisible ring/glow effect still requests a frame every vsync (battery).
- [x] PC13 `apps/phone/src/keypad.rs:1163-1190` | Medium | The "Allow Phone access" row cannot shrink or wrap; on 360-402 px screens "Grant access" is cut off. Partly fixed: the sentence shrinks with an ellipsis and "Grant access" has a fixed width; very narrow screens may still clip.
- [x] PC14 `apps/phone/src/main.rs:727-731` | Low | On the locked call-only screen with the keypad closed, Back switches the hidden tab and nothing visible happens.
- [x] PC15 `apps/phone/src/addcall.rs:287`, `main.rs:711` | Low | With the Add call number chooser open, Back closes the whole sheet instead of returning to the list.
- [x] PC16 `apps/phone/src/addcall.rs:1011-1017` | Low | The chooser header name has no truncation; long names overflow the sheet.
- [x] PC17 `apps/phone/src/addcall.rs:221,966` | Low | The Add call keypad shares the Keypad tab's number; unplaced digits show up on the Keypad tab afterwards.
- [x] PC18 `apps/phone/src/notice.rs:277-295` | Low | The Return-to-call tap area is the whole 44 px wide strip, not the capsule.
- [x] PC19 `apps/phone/src/flight.rs:453-461` | Low | The flying avatar fades its photo while landing on an avatar that shows it (photo, monogram, photo flash).
- [x] PC20 `apps/phone/src/incall.rs:2137-2144` | Low | The in-call digit line can show 24 digits at 28 px and overflow a 360 px screen.

## Phone: other pages (PP)

- [x] PP1 `apps/phone/src/sim_ui.rs:343-361` | High | Dismissing the "Call with" SIM sheet while another app waits for a SIM choice leaves `sim.chooser` set; the sheet never shows again and the call is stuck.
- [x] PP2 `apps/phone/src/contacts.rs:1156-1161` | Medium | Contacts search truncates the end of the text while typing.
- [x] PP3 `apps/phone/src/blocked_ui.rs:570-575` | Medium | Same in the "Add a number to block" field.
- [x] PP4 `apps/phone/src/replies_ui.rs:908-913` | Medium | Same in the Quick replies fields.
- [x] PP5 `apps/phone/src/recents.rs:1212-1219` | Medium | A failed "Add to contacts" in the Number sheet writes to `setup_note`, which only the Setup sheet draws: nothing visible happens.
- [x] PP6 `apps/phone/src/setup_ui.rs:294-295` | Medium | Setup buttons only report `NotAvailable`; a declined open of a settings page does nothing visible.
- [x] PP7 `apps/phone/src/replies_ui.rs:668-722` | Low | Saving/resetting quick replies leaves the "Quick replies N" count in Settings stale.
- [x] PP8 `apps/phone/src/speed_dial_ui.rs:225` | Low | The speed-dial pick stays armed after its pill expires; a later contact tap assigns speed dial instead of calling.
- [x] PP9 `apps/phone/src/block_ui.rs:200` | Low | The Recents row menu requests a frame every vsync while open (battery).
- [x] PP10 `apps/phone/src/settings_ui.rs:1073-1075` | Low | A call closes Settings with `close_now()` and skips the sub-page cleanup (blocked-call pulls keep running).
- [x] PP11 `apps/phone/src/main.rs:713-721` | Low | A second Back during a Settings sub-page close animation is swallowed.
- [x] PP12 `apps/phone/src/main.rs:724-731` | Low | Back on Contacts with a search query jumps to Keypad instead of clearing the search.
- [-] PP13 `apps/phone/src/backend.rs:456-458` | Low | Pref writes made before stored prefs finish loading are overwritten when they arrive. Skipped: replaying early edits needs a pending-edit queue at every update_prefs call site; low value.
- [x] PP14 `apps/phone/src/backend.rs:433-440` | Low | Without a data dir, settings silently never persist.
- [x] PP15 `apps/phone/src/field.rs:123-136` | Low | Every field shows a "Search" action key; on the Blocked page Enter only hides the keyboard instead of blocking.
- [x] PP16 `apps/phone/src/contacts.rs:1057-1059` | Low | "Open Contacts" ignores a failure to open the app.

## Messages (M)

- [x] M1 `apps/messages/src/sms_conversation.rs:227-235`, `headless.rs:276-304` | High | Every SMS picture send fails: the picked file is `import-<ms>.bin`, the MIME type from Kotlin is dropped, so the PDU builder refuses it ("Not sent" forever).
- [x] M2 `apps/messages/src/schedule_ui.rs:368`, `main.rs:1309-1315` | High | A double tap on a "Send later" row schedules the same SMS twice (the closing sheet stays clickable).
- [x] M3 `apps/messages/src/sms_conversation.rs:203-205,250` | High | A new conversation keeps `thread_id = -1` after its first send: replies never appear on that page.
- [x] M4 `apps/messages/src/main.rs:1026-1082`, `backend.rs:262-273`, `sms_conversation.rs:272-275` | High | The app never reports going to the background: Kotlin's push digest stays suppressed, open-chat messages get no notification while backgrounded, and SMS in the open thread are marked read in the background.
- [x] M5 `apps/messages/src/main.rs:1079-1082` | Medium | A notification posted by the SMS receiver for the thread that is open in the foreground is never cancelled.
- [x] M6 `apps/messages/src/main.rs:921-962` | Medium | Back from the Spam list closes the app.
- [x] M7 `apps/messages/src/main.rs:507,530` | Medium | A failed first WhatsApp chat fetch is treated as loaded: chats stay missing until resync/restart, no error.
- [x] M8 `apps/messages/src/main.rs:545-554,1173-1182` | Medium | Opening an SMS thread not yet in the thread list (cold notification tap) gives empty recipients and a blank title; Send goes nowhere.
- [x] M9 `apps/messages/src/sms_conversation.rs:117`, `crates/rovsti_ui/src/field.rs:145-155` | Medium | The keyboard's Send key only hides the keyboard; nothing is sent. (The WhatsApp composer half is in `conversation.rs`: deferred, other agent.)
- [x] M10 `apps/messages/src/main.rs:1919-1932` | Medium | The Settings page ignores the keyboard height; passphrase/spam/block fields near the bottom sit behind the keyboard.
- [x] M11 `apps/messages/src/schedule_ui.rs:371-390` | Medium | The "Send later" sheet's custom-time field and Schedule button are covered by the keyboard.
- [x] M12 `apps/messages/src/backend.rs:56,808` | Medium | Backups silently miss threads past the 500-thread list cap.
- [x] M13 `apps/messages/src/backend.rs:59`, `headless.rs:39` | Medium | Pictures up to 8 MB are accepted, but the PDU builder refuses over 4 MB: the send fails on every retry.
- [x] M14 `apps/messages/src/main.rs:1337-1363` | Medium | The schedule sheet stays open when a notification opens another conversation; scheduling then wipes the other conversation's typed text.
- [x] M15 `apps/messages/src/main.rs:251,1151,1165`, `backend.rs:846-848` | Low | Some pills are shown without `cx.notify()` and appear late.
- [x] M16 `apps/messages/src/sms_conversation.rs:279`, `backend.rs:114`, `settings_ui.rs:483` | Low | Load/restore errors show raw `{error:?}` text.
- [x] M17 `apps/messages/src/main.rs:1131-1134`, `sms_conversation.rs:329-334` | Medium | A notification reply to the open SMS thread is dropped silently when the app is not the default SMS app.
- [x] M18 `apps/messages/src/main.rs:634-662` | Low | `open_sms`/`open_new` do not clear the row menu; the first Back closes an invisible menu.
- [x] M19 `apps/messages/src/settings_ui.rs:856-910` | Low | Backup passphrases are shown in clear text.
- [x] M20 `apps/messages/src/backup_ui.rs:244` | Low | The backup file name uses the UTC date (yesterday's date 00:00-03:00 in Turkey).
- [-] M21 `apps/messages/src/new_conversation.rs:65-70` | Low | Only one number per contact is offered in New message. Skipped: a multi-number picker is a feature, not a small fix.
- [x] M22 `apps/messages/src/main.rs:1834-1838` | Low | The "SMS Access Is Off" empty state has no action (dead end).
- [x] M23 `apps/messages/src/sms_conversation.rs:250-253` | Low | An `SmsChanged` during a load is dropped; a new message can be missing until the next change.
- [>] M24 `apps/messages/src/conversation.rs` | Medium | WhatsApp composer: the keyboard Send key does not send (same as M9). Deferred (other agent owns the file).

## Contacts and shared UI (C)

- [x] C1 `apps/contacts/src/main.rs:553` | High | `can_go_back` counts the WhatsApp model's own back state even when its page is closed, so Back is captured and does nothing (offline at start, or left the page mid-flow).
- [x] C2 `apps/contacts/src/main.rs:931-938` | High | Permanently denied contacts permission is a dead end ("Can't ask for access: no app answered.").
- [x] C3 `apps/contacts/src/transfer.rs:108-117`, `confirm.rs:145-166` | High | Importing the same file again while "Adding contacts..." runs creates duplicate contacts.
- [x] C4 `apps/contacts/src/main.rs:257-264` | Medium | The data folder is asked for once and never retried; prefs and "Use as Contact Photo" stay broken for the run.
- [x] C5 `apps/contacts/src/editor.rs:225-230,549-550`, `main.rs:563-564` | Medium | Cancel and Back drop a dirty editor immediately; typed edits are lost.
- [x] C6 `apps/contacts/src/ui.rs:131-134`, `main.rs:1304-1311` | Medium | The keyboard covers lower editor fields; nothing scrolls the focused field into view.
- [x] C7 `apps/contacts/src/main.rs:603,642`, `whatsapp.rs:496-498` | Medium | Raw `rovsti_android::Error` text (JNI/bridge details) shown in the notice.
- [x] C8 `apps/contacts/src/whatsapp.rs:235,238` | Medium | "Use the WhatsApp photo" shows raw HTTP/io error text.
- [x] C9 `crates/rovsti_wa_ui/src/view.rs:69-73,159` | Medium | The shared WhatsApp settings show raw `ClientError` text ("http: ...", "tls: ...").
- [x] C10 `crates/rovsti_wa_ui/src/view.rs:881-883,939-945` | Medium | "Unlink this app" and "Revoke" act on one tap without confirmation.
- [x] C11 `apps/contacts/src/main.rs:545-579` | Medium | Back with a search query on the Contacts tab closes the app instead of clearing the search.
- [x] C12 `apps/contacts/src/main.rs:1201-1226` | Low | The Contacts notice never times out.
- [x] C13 `apps/contacts/src/whatsapp.rs:64-74` | Low | The WhatsApp lookup cache keeps the old status after a contact's numbers are edited.
- [x] C14 `apps/contacts/src/confirm.rs:205` | Low | The "Nothing to add" card's only button says "Cancel".
- [x] C15 `apps/contacts/src/whatsapp.rs:208` | Low | ASCII-only case compare shows a redundant "WhatsApp name" for Turkish names (AYŞE vs Ayşe).
- [x] C16 `crates/rovsti_ui/src/components.rs:29-34` | Low | `large_title` cannot shrink; long group names overflow off-screen.
- [x] C17 `crates/rovsti_wa_ui/src/view.rs:526-561` | Low | "Share QR to PC..." fails silently.
- [x] C18 `apps/contacts/src/main.rs:1162-1173`, `transfer.rs:19,50` | Low | Dimmed Export/Import still tappable while busy, with no feedback.
- [-] C19 `crates/rovsti_ui/src/touch.rs:33`, `tab_bar.rs:443` | Low | `touch_surface` ignores occlusion: a finger on a scrim over the tab bar still swells the bar. Skipped: needs hit-test occlusion inside the shared touch_surface (every tab bar); only a press animation, and touch hover on gpui_android is unverified.
- [x] C20 `apps/contacts/src/whatsapp.rs:374-401` | Low | "Use as Contact Photo" has no in-flight guard; a double tap applies it twice.

## Stops (S)

- [x] S1 `apps/stops/src/map.rs:1494-1504,2006-2009` | High | Map touches are window-level and ignore occlusion: closing a sheet by its scrim, tapping Undo/a tab/the alarm's Dismiss over the map drops a pin; swiping in a sheet pans the map.
- [x] S2 `apps/stops/src/main.rs:430-437` | Medium | Back with the pin "Move" mode armed closes the app.
- [x] S3 `apps/stops/src/main.rs:645-651` | Medium | Switching tabs keeps the armed "Remove transit data"/"Clear history" confirms (one tap later destroys data) and the pin menu open.
- [x] S4 `apps/stops/src/picker.rs:327-330` | Medium | A shared link while the alarm rings hides the alarm screen without snoozing/dismissing it.
- [x] S5 `apps/stops/src/main.rs:542-549` | Medium | Pills ignore the keyboard inset and sit hidden under the keyboard while typing.
- [x] S6 `apps/stops/src/trip_ui.rs:553-559`, `main.rs:444-445` | Medium | After the last snooze, "Snooze N min" (and Back) silently dismisses the alarm and ends the trip.
- [x] S7 `apps/stops/src/main.rs:115,286`, `trip_ui.rs:380` | Medium | The per-trip "Text X when arriving" switch is not persisted; after a process kill it comes back on.
- [x] S8 `apps/stops/src/picker.rs:336-375,384,474,1108` | Medium | Rename/Save from the Confirm or stop sheet replaces it; Save/Cancel then loses the shared point.
- [x] S9 `apps/stops/src/settings_ui.rs:269,295,1161`, `backend.rs:292-299`, `transit.rs:182-364` | Medium | Raw internal errors (network/http/tls/JNI/io) shown to the user.
- [x] S10 `apps/stops/src/map.rs:1642-1647` | Low | During a trip, "Use as A/B" edits the hidden next-trip pair and draws its pins on the live trip map.
- [x] S11 `apps/stops/src/transit.rs:153-162` | Low | History dates are UTC (previous day 00:00-03:00 in Turkey).
- [x] S12 `apps/stops/src/trip_ui.rs:856-859` | Low | Each "searching" status replaces an Undo/"Snoozed" pill still on screen.
- [x] S13 `apps/stops/src/map.rs:2040-2053` | Low | After allowing location from "Me", nothing retries; a stale `locate_pending` recentres much later.
- [x] S14 `apps/stops/src/settings_ui.rs:487-495` | Low | "Exact alarms: Not allowed" has no action.
- [x] S15 `apps/stops/src/trip_ui.rs:35-41` | Low | `distance_text(995..999)` gives "1000 m".
- [x] S16 `apps/stops/src/trip_ui.rs:726,731` | Low | Inconsistent pill punctuation ("Trip stopped" vs "Trip stopped.").

## Unsure (not counted, not changed)

- Phone: WhatsApp call timer may step back a second on each view rebuild; Waiting-card "Message" for a private caller; WhatsApp End stops audio before the gateway confirms; "Missed call from" line and route popover at large font.
- Phone pages: notice slot band without occlude over Keypad controls; Quick replies editor under the keyboard; long name under the Contact sheet star; Recents relative times past midnight; double tap on Block.
- Messages: runtime `POST_NOTIFICATIONS`/`READ_CONTACTS`/`READ_SMS` requests; busy backup states that cannot be cancelled; time-zone change while running; `open_chat` with no gateway; "Scheduled for" pill under the keyboard.
- Contacts/shared: stuck `transfer_busy` if a document result never arrives; reversed IME range slicing in the text fields; Connecting screen without Cancel; `split_at(24)` on a gateway key; Delete while saving.
- Stops: stale field focus; template field under the keyboard; tile memory cap vs view tile cap; concurrent detached saves sharing one `.tmp`; double taps during sheet close.
