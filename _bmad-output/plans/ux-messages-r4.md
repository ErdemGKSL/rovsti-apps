# UX completeness pass: Rovsti Messages (round 4)

Base: `c9374174ff8e07f2efb7109da5878781928003c9` (main, clean tree, no stash). Rounds 1
(`ux-messages.md`, G1 to G10), 2 (`ux-messages-r2.md`, H1 to H9) and 3
(`ux-messages-r3.md`, K1 to K9) are done. This round re-audits as a user holding the
phone, with fresh eyes on what earlier rounds did not look at: Android permissions,
WhatsApp message cards, the two mute switches, Spam from inside a conversation,
scheduled sends that fail, and recovery after failed WhatsApp actions.
Scope as before: `apps/messages`, `rovsti_core`, `rovsti_wa_proto`/`rovsti_wa_client`
as used by Messages, existing `rovsti_android` seams. No new product features, no
backends, no Kotlin changes (cargo is the only verification).

GPUI and seam APIs used below were checked at the pinned rev or in this repo:
`App::open_url(&self, &str)` (Kotlin `openUrl` = `ACTION_VIEW` on any scheme, so `geo:`
works), `rovsti_android::permissions::{state, request, open_app_details, Group, Access}`,
`rovsti_android::trip::can_schedule_exact_alarms`, `telecom::{place_call, add_contact}`.
Icons exist in the enabled gpui_icons features: `LucideMapPin`, `LucidePhone`,
`LucideMessageCircle`, `LucideUserPlus`, `LucideBellOff`, `LucideAlarmClock`,
`LucideShieldCheck`, `LucideCopy`.

## Re-audit: gaps found

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| N1 | **High** | **Messages never asks for notifications or contacts, and never notices a change made in system settings.** The manifest declares `POST_NOTIFICATIONS` and `READ_CONTACTS`, but no Rust code calls `permissions::request` (only the SMS role grants them). A WhatsApp-only user (no SMS role) on Android 13+ gets no notifications at all and sees numbers instead of names, with nothing on screen saying why. The acceptance sheet says "Allow ... when asked", but nothing asks. Also, Messages' `MainActivity` never emits `permissions_changed` (Phone's does in `onResume`) and nothing re-pulls on resume: after "SMS Access Is Off" → "Open settings" → allow → Back, the list still says access is off until a restart. | `backend.rs::refresh_all/pull_contacts` (`Pull::Denied` → empty index silently), `main.rs::on_window_active`, `main.rs` `ChatItem::Empty` (Open settings), `settings_ui.rs::notifications`, `new_conversation.rs` |
| N2 | Medium | **A received WhatsApp location or contact card is a dead end.** The location card shows coordinates as text: no "Open in Maps", no Copy. The contact card shows a name and number: no Call, no Message, no Add to contacts, no Copy (`copy_text_of` returns `None` for both, so long-press offers only Reply/Star/Delete). | `conversation.rs::location_card` (3001), `contact_card`, `copy_text_of` (50), `actions` (2211) |
| N3 | Medium | **The two WhatsApp mutes disagree.** The row menu's "Mute…" mutes on the gateway (bell-off on the row); the bell inside the chat only writes a local prefs tag. A chat muted from the row shows an unmuted bell inside it; a chat muted with the bell shows no bell-off on its row, and the row menu still offers "Mute…". Settings > Muted lists only the local tags. | `main.rs::sync_open_page` (2303), `toggle_open_mute` (2289), `wa_menu_items` (1833), `inbox.rs::build` (653), `settings_ui.rs::notifications` |
| N4 | Medium | **Inside a Spam conversation there is no "Not spam" and no sign it is spam.** `open_page_menu` hard-codes `in_spam: false` and `sms_actions::actions` never offers NotSpam on a page; the user must go Back and long-press the row. | `main.rs::open_page_menu` (1280), `sms_actions.rs::actions`, `sms_conversation.rs::top_slot` |
| N5 | Medium | **A scheduled SMS that fails to send loses its text.** `ScheduleQueue::reconcile` removes the item and the app only says "1 scheduled message couldn't be sent": no recipient, no text, no way to open it and send again. | `backend.rs::reconcile_fired` (872), `rovsti_core::schedule::reconcile` |
| N6 | Low | **Failed WhatsApp reaction and edit are not undone or recoverable.** A failed reaction stays on the bubble (optimistic) and the line shows the raw client error (`"Couldn't send the reaction: {error}"`). A failed edit has already cleared the composer: the new text is gone. | `conversation.rs::react` (831), edit send (~525) |
| N7 | Low | **"Couldn't load WhatsApp chats" has no Retry**: chats stay missing until a reconnect or restart. | `main.rs::load_chats` (772) |
| N8 | Low | **Scheduled SMS may be late without a word.** When Android denies exact alarms (default for new installs on Android 14), the alarm falls back to inexact; the manifest comment says "the UI says 'may be a few minutes late'", but the Send later sheet says nothing and offers no way to App info. | `schedule_ui.rs::ScheduleSheet`, `AndroidManifest.xml` comment |
| — | Info (not planned) | Playing voice notes and videos in the app, a full-screen picture viewer, a list of starred messages (no gateway endpoint), group member page, deleting a WhatsApp chat (no endpoint), deleting one SMS / marking an SMS thread unread (new Kotlin writes), calling phone numbers found in message text, notification "Mark as read" action, share-into-Messages target, posting a Status. These are new screens, new Kotlin or gateway work, or new features. Status load failures stay logged only (the entry row hides when there is nothing). |

## Plan (ordered by user impact, pure logic first; every step leaves all checks green)

Checks after every step (from the task):
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
`df` showed 6.8 GB free at planning time; under 6 GB run `rm -rf target/debug/incremental` first.
Until a later step calls a new pure function, mark it `#[cfg_attr(not(test), allow(dead_code))]`
and remove the attribute in that step.

### Step 1. Pure logic (+tests)
- New `apps/messages/src/access.rs` (`mod access;` in `main.rs`), using
  `rovsti_android::permissions::Access as Perm` (compiles on desktop):
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum Ask { Request, OpenAppInfo }`
  - `pub fn ask_for(state: Perm) -> Option<Ask>`: Granted → None, Denied → Request,
    PermanentlyDenied → OpenAppInfo.
  - `pub fn notify_banner(app_switch_on: bool, state: Option<Perm>) -> Option<&'static str>`:
    `None` when the app's own Notifications switch is off, the state is unknown (`None`,
    desktop) or Granted; else `NOTIFY_OFF` =
    "Android has notifications off for Rovsti Messages. Tap to allow them."
  - `pub const CONTACTS_OFF: &str = "Contacts access is off: names and search by name need it. Tap to allow.";`
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)] pub struct ResumePulls { pub role: bool, pub access: bool, pub threads: bool, pub contacts: bool }`
    and `pub fn resume_pulls(threads: crate::backend::Access, contacts_denied: bool) -> ResumePulls`:
    role and access always; threads only when `threads == Access::Denied`; contacts only
    when `contacts_denied`.
  - Tests: `ask_maps_each_state`, `banner_only_when_switch_on_and_android_says_no`,
    `resume_repulls_only_what_was_denied`.
- `apps/messages/src/conversation.rs`:
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub enum CardAction { OpenMap { url: String },
    Call(String), Message { name: String, number: String }, AddContact(String), Copy(String) }`
  - `pub fn geo_url(location: &Location) -> String`:
    `geo:{lat:.6},{lon:.6}?q={lat:.6},{lon:.6}` plus `(<name>)` when `name` is non-blank,
    the name percent-encoded by a small private `fn encode_query(s: &str) -> String`
    (keep ASCII alphanumerics and `-._~`, encode every other UTF-8 byte as `%XX`,
    uppercase hex).
  - `pub fn card_actions(meta: Option<&MessageMeta>) -> Vec<CardAction>`: revoked → empty;
    location → `[OpenMap, Copy(text)]` where text is name, address (non-blank ones, one
    per line) then `coordinates(location)`; contact with `vcard_phone` = Some(n) →
    `[Call(n), Message{display_name, n}, AddContact(n), Copy(n)]`; contact without a number
    → `[Copy(display_name)]`; anything else → empty.
  - Tests: `geo_urls_encode_the_name` (e.g. "Ayasofya Müzesi" → `(Ayasofya%20M%C3%BCzesi)`,
    no name → no parentheses), `cards_offer_map_call_message_and_copy`,
    `revoked_and_plain_messages_have_no_card_actions`.
- `apps/messages/src/inbox.rs`:
  - `pub fn wa_muted(meta: Option<&ChatMeta>, local: bool, now_ms: i64) -> bool`
    (`local || meta.is_some_and(|m| m.is_muted(now_ms))`).
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum BellToggle { Mute, Unmute { gateway: bool, local: bool } }`
    and `pub fn wa_bell(meta: Option<&ChatMeta>, local: bool, now_ms: i64) -> BellToggle`.
  - `build`: a WhatsApp row is also `muted` when `inputs.muted` contains
    `thread_tag(&ThreadId::WhatsApp(jid))` (use `wa_muted`).
  - `impl InboxView { pub fn in_spam(&self, thread: &ThreadId) -> bool }` (any `spam` row whose
    `threads` contains it).
  - Tests: `local_wa_mute_shows_the_bell`, `bell_unmutes_both_and_mutes_on_the_gateway`
    (expired gateway mute counts as unmuted), `in_spam_finds_spam_threads_only`.
- `apps/messages/src/sms_actions.rs::actions`: on a page, when `in_spam && single_sender`,
  `NotSpam` comes first (before Call). Update existing tests; add
  `page_offers_not_spam_for_a_spam_thread`.
- `apps/messages/src/schedule_ui.rs`:
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct FailedNotice { pub text: String, pub reopen: Option<(String, String)> }`
    and `pub fn failed_notice(failed: &[ScheduledSms]) -> Option<FailedNotice>`: empty →
    None; one SMS item → text "Scheduled message to {to} couldn't be sent", reopen
    `(to, body)`; several → "{n} scheduled messages couldn't be sent", reopen = the latest
    (`max_by_key(at_ms)`) SMS item; WhatsApp targets never reopen.
  - `pub const LATE_NOTE: &str = "Android may send it a few minutes late. Tap to allow exact alarms in App info.";`
    and `pub fn late_note(is_sms: bool, exact_allowed: Option<bool>) -> Option<&'static str>`
    (`Some` only for `is_sms && exact_allowed == Some(false)`).
  - Tests: `failed_notice_names_one_and_counts_many`, `late_note_only_for_sms_without_exact_alarms`.
- `crates/rovsti_wa_proto/src/timeline.rs`: `pub fn own_reaction(&self, id: &str) -> Option<&str>`
  (the `from_me` reaction's emoji of item `id`). Test next to the `set_own_reaction` tests:
  set, read back, clear → None.

### Step 2. Ask for notifications and contacts; refresh on return (fixes N1)
- `backend.rs`:
  - Fields `notify_access: Option<rovsti_android::permissions::Access>` (None = unknown or
    desktop) and `contacts_denied: bool`; getters `notify_access()`, `contacts_denied()`.
  - `pull_contacts`: set `contacts_denied = true` on `Pull::Denied`, `false` on `Ready`
    (carry the flag out of the background task with the index).
  - `pub fn pull_access(&mut self, cx)`: background
    `permissions::state(Group::Notifications)`; `Ok(a)` → `Some(a)`, `Err(Unsupported)` →
    `None`, other errors logged and the old value kept; `cx.notify()` on change. Call it
    from `refresh_all`.
  - `pub fn ask_access(&mut self, group: Group, cx)`: background. Notifications: by
    `access::ask_for(state)` → `request` (if it returns `Ok(false)`, `open_app_details`) or
    `open_app_details`. Contacts: Messages declares only `READ_CONTACTS` while
    `Group::Contacts` also checks `WRITE_CONTACTS`, so never trust `state(Contacts)` here:
    call `request(Group::Contacts)` and on `Ok(false)` call `open_app_details`.
    `Err(Unsupported)` → `BackendEvent::Notice("That needs an Android phone.")`; other
    errors → Notice("Couldn't open the permission settings").
  - `pub fn on_resume(&mut self, cx)`: `access::resume_pulls(self.threads_state,
    self.contacts_denied)` → `pull_role`, `pull_access`, and `pull_threads` /
    `pull_contacts` when flagged.
- `main.rs`:
  - `on_window_active(true)`: `backend.on_resume(cx)`, except on the first activation after
    start (a `seen_active: bool` field), which `refresh_all` already covers.
  - `ChatItem::AccessBanner` after `RoleBanner`/`WaBanner` (no search active) when
    `access::notify_banner(prefs.notifications, backend.notify_access())` is `Some(text)`:
    `list_row` with an orange `Icon::LucideBellOff`, id `"access-banner"`, tap →
    `backend.ask_access(Group::Notifications, cx)`. Add the text to `ChatLayout` and a
    `key_of((13u8, text))` key. Turning the app's own switch off in Settings hides it.
- `settings_ui.rs`: in the Notifications section, when the banner text is `Some`, a row
  "Allow notifications" with the text as subtitle → `ask_access(Notifications)`. In the
  first section (next to Default SMS app), when `contacts_denied()`, a row "Contacts" with
  `access::CONTACTS_OFF` → `ask_access(Contacts)`.
- `new_conversation.rs`: when `backend.read(cx).contacts_denied()`, a tappable line above the
  results with `CONTACTS_OFF` (id `"new-contacts-access"`) → `ask_access(Contacts)`.
- `docs/messages-acceptance.md` row 1: say where the asks come from (Make default grants
  them; otherwise the inbox banner, Settings rows and New message line).

### Step 3. Location and contact cards act (fixes N2)
- `conversation.rs::actions`: after the Copy/Share block, for each
  `card_actions(meta)` add a pill: OpenMap "Open in Maps" (`LucideMapPin`) →
  `cx.open_url(&url)`; Call "Call" (`LucidePhone`) → `ConversationEvent::Call(n)`;
  Message "Message" (`LucideMessageCircle`) → `ConversationEvent::Message { name, number }`;
  AddContact "Add to contacts" (`LucideUserPlus`) → `ConversationEvent::AddContact(n)`;
  Copy "Copy" (`LucideCopy`) → existing `ConversationEvent::Copy`. Each closes the strip
  (`strip = None; rows_dirty = true`) like Copy does.
- A tap on a location bubble while no strip is open opens the map (`geo_url`); a tap
  while the strip is open still just closes it (keep the existing branch order).
- `main.rs`: `Call(n)` → `telecom_action(SmsAction::Call, n)`; `AddContact(n)` → if
  `backend.contacts().match_number(&n)` is a contact, `telecom::edit_contact(id)` via the
  same path as `wa_page_contact_action`'s Open contact, else
  `telecom_action(SmsAction::AddContact, n)`; `Message { name, number }` →
  `open_recipient(&Recipient { title: name, number, detail: None }, cx)` (the channel pick
  appears when WhatsApp is linked).

### Step 4. One WhatsApp mute (fixes N3)
- `main.rs::sync_open_page`: for `Open::WhatsApp(jid, ..)`, `muted = inbox::wa_muted(
  self.wa_info.get(jid).map(|i| &i.meta), local, now_ms())` where `local` is the prefs tag.
- `toggle_open_mute`: SMS unchanged. WhatsApp: `match inbox::wa_bell(..)`: `Mute` →
  `self.chat_action(&jid, ChatActionKind::Mute { until_ms: None }, false, cx)` (its pill
  "Muted" with Undo already exists); `Unmute { gateway, local }` → clear the local tag if
  `local`; if `gateway`, `chat_action(Unmute, false)`, else `show_pill("Unmuted")`.
- `chat_action`: on `ChatActionKind::Unmute` also clear the local `wa:<jid>` prefs tag, so
  the row menu's Unmute ends both.
- `wa_menu_items` / `RowMenu`: add `local_muted: bool` (filled in `open_row_menu` and
  `open_wa_page_menu` from prefs); the Mute…/Unmute choice uses `inbox::wa_muted`.
- Settings > Muted also lists WhatsApp chats muted on the gateway:
  `SettingsView::set_wa_muted(&mut self, jids: Vec<String>, cx)` called from
  `MessagesApp::rebuild` next to `set_wa_titles` (jids whose `meta.is_muted(now)`, minus
  ones already listed by a local tag); each row "Muted: {title}", subtitle "Tap to unmute",
  emits new `SettingsEvent::UnmuteWhatsApp(String)` → `chat_action(&jid, Unmute, false)`
  (its pill offers Undo).

### Step 5. Not spam from inside a Spam conversation (fixes N4)
- `main.rs::open_page_menu`: `in_spam = self.view.in_spam(&ThreadId::Sms(thread_id))`;
  make sure `row_sms_actions` passes `in_spam` and `on_page` into `SmsActionContext`.
  `sms_row_action(NotSpam)` from the page uses the existing `not_spam(sender)` (pill with
  Undo); the page stays open.
- `sms_conversation.rs`: `pub fn set_in_spam(&mut self, in_spam: bool, cx)` (field
  `in_spam: bool`), called from `sync_open_page` with `self.view.in_spam(..)`. When set and
  the thread has one sender, `top_slot` shows a line "This conversation is in Spam." with a
  `glass_pill` "Not spam" (id `"sms-not-spam"`) emitting new
  `SmsConversationEvent::NotSpam`; `main.rs` maps it to `not_spam(sender)`.
  (`rebuild` already calls `sync_open_page`, so the line goes away after Not spam and comes
  back with Undo.)

### Step 6. Failed scheduled SMS can be sent again; late-alarm note (fixes N5, N8)
- `backend.rs::reconcile_fired`: emit new `BackendEvent::ScheduledFailed(Vec<ScheduledSms>)`
  instead of the counted `Notice`.
- `main.rs`: on `ScheduledFailed(items)`, `schedule_ui::failed_notice(&items)`; with
  `reopen = Some((to, body))` → `show_pill_action(text, "Open",
  PillAction::ReopenScheduled { to, body }, cx)`, else `show_pill(text)`. `pill_fired`:
  `open_recipient_sms(&Recipient { title: <contact name via match_number, else to>, number:
  to, detail: None }, cx)`, then on the opened `Open::Sms` page `set_draft(&body, cx)` (the
  existing round-1 method), so Send (or Send later) resends it.
- `backend.rs`: `exact_alarms: Option<bool>` pulled in `refresh_all` and `on_resume` with
  `rovsti_android::trip::can_schedule_exact_alarms()` (`Err(Unsupported)` → `None`).
- `schedule_ui.rs::ScheduleSheet`: field `late_note: Option<&'static str>` and a setter;
  `MessagesApp::ask_schedule_sms` sets it from `late_note(true, backend.exact_alarms())`,
  `ask_schedule_whatsapp` clears it. The sheet renders it as a small tappable line with
  `Icon::LucideAlarmClock`; tap → `backend.open_app_settings(cx)` (App info holds "Alarms
  & reminders"). Correct the manifest comment only if its wording no longer matches.

### Step 7. Recover from failed WhatsApp actions (fixes N6, N7)
- `conversation.rs::react`: before `set_own_reaction`, keep
  `previous = self.timeline.own_reaction(&message_id).map(str::to_owned)`. On error:
  `set_own_reaction(&id, previous.as_deref().unwrap_or(""))`, `rows_dirty = true`, and the
  plain `notice = Some("Couldn't send the reaction".into())` (same field star/delete use),
  no raw error on screen (log it).
- Edit send (~line 525): keep `let sent = text.clone()` and the message id; on error, if
  the composer is empty and nothing else is being edited or replied to, put `sent` back
  with `TextField::set`, set `editing = Some(id)` again, and
  `notice = "Couldn't edit the message. Send to try again."`.
- `main.rs::load_chats` error arm: `show_pill_action("Couldn't load WhatsApp chats",
  "Retry", PillAction::ReloadChats, cx)`; `pill_fired` → `self.load_chats(cx)`.

### Step 8. Docs and status
- `docs/messages-acceptance.md` section M, rows 73 onward: notifications banner and Allow
  (no SMS role, Android 13+), contacts row/line, return from App info refreshes the list,
  open a shared location in Maps, contact card Call/Message/Add, bell and row menu agree
  on WhatsApp mute (and Settings > Muted lists both), Not spam from inside a Spam
  conversation, failed scheduled SMS "Open" puts the text back, late-alarm note in Send
  later, failed reaction rolls back, failed edit keeps the text, Retry on the chats pill.
- `docs/roadmap.md` Messages: one ticked line "Fourth UX pass: permission asks and
  refresh on return, location and contact card actions, one WhatsApp mute, Not spam from
  the conversation, resend failed scheduled SMS, recovery after failed WhatsApp actions
  (done without a device)".
- Status section at the end of this file.

## Status

Done without a device (cargo only), one commit per step:

- Step 1 pure logic and tests (`access.rs`, card actions, `wa_muted`/`wa_bell`/`in_spam`, `failed_notice`/`late_note`, `own_reaction`).
- N1 done: notifications banner and Allow, Contacts row/line, refresh on return from App info (step 2).
- N2 done: location and contact cards act (step 3).
- N3 done: one WhatsApp mute, Settings > Muted lists gateway mutes (step 4).
- N4 done: Not spam from inside a Spam conversation (step 5).
- N5 done: failed scheduled SMS "Open" restores the text (step 6).
- N8 done: late-alarm note in Send later (step 6).
- N6 done: failed reaction rolls back, failed edit keeps the text (step 7).
- N7 done: Retry on the chats pill (step 7).
- Step 8 docs: `docs/messages-acceptance.md` rows 73 to 84 and the roadmap line.

Device checks for all of the above are rows 73 to 84 of `docs/messages-acceptance.md`.
