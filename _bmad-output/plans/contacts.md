# Plan: Rovsti Contacts

- Base: `a31040abb2188baca724499085e2815bed01d068` ("WIP: carry-over before sync")
- Unit: Rovsti Contacts. Architect: PLAN agent. No source changed by this plan.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`
- Nothing runs on a device here. Kotlin is checked by reading only; every Kotlin change keeps
  the strings-only JNI seam and the Rust side returns `Error::Unsupported` on desktop.

## What the code already has (source of truth at the base)

Most of the brief has landed (`docs/roadmap.md`, "Rovsti Contacts"):

- Ejected Android host `apps/contacts/platforms/android` with `RovstiBridge` (shared Kotlin in
  `crates/rovsti_android/kotlin` as a Gradle source dir). Done.
- Name format and sort persisted: `rovsti_core::contacts_prefs::ContactsPrefs`
  (`contacts-prefs.json`), read and written by `apps/contacts/src/prefs.rs`. Done.
- In-app create, edit, delete: `rovsti_core::contact_edit::{ContactDraft, DraftError, ...}`,
  Kotlin `ContactsEdit.kt`, Rust `rovsti_android::contacts_edit`, app `editor.rs`. Done.
- Photos on list and card (`apps/contacts/src/photos.rs`, `providers::contact_photo`), groups
  (`groups.rs`, Groups tab), vCard import/export (`rovsti_core::{vcard, import_plan}`,
  `transfer.rs`, SAF). Done.
- Shared WhatsApp settings: `rovsti_wa_ui::WhatsAppSettings::new(AppId::Contacts, "contacts", cx)`
  is pushed from Settings (`main.rs` `whatsapp_page`); two options (link first time / connect
  with key), keys hashed at rest on the gateway, revocable, stored in `whatsapp-link.json`. Done.

Still open, and what this plan delivers:

1. Roadmap: "Choose or change a contact's photo".
2. Roadmap: "Contacts without a phone number" (list pull is `Phone.CONTENT_URI` with
   `HAS_PHONE_NUMBER = 1`; `DraftError::NoNumber` refuses a draft without a number).
3. Roadmap: "Groups of a synced account for a new contact" (`ContactsEdit.appendNew` writes
   `ACCOUNT_TYPE/NAME = null`; the editor filters groups by `draft.account`, so a new contact only
   sees phone-only groups).
4. WhatsApp per contact: availability (on WhatsApp or not, online / last seen) and start a
   WhatsApp chat or call through the gateway. Nothing of this exists in `apps/contacts`.
5. Bugs found while studying: the Contacts manifest has **no `INTERNET` permission**, so the
   shared WhatsApp settings cannot reach the gateway on a phone (Messages declares it; Phone
   gets it only if a merged library does, check it in step 7). `docs/whatsapp-notes.md` line 27
   still says "Rovsti Contacts owns the WhatsApp session" (superseded by the gateway).

Not doable without a device (stays open): "Check the editor, groups and vCard flows on a phone".

## Decisions (AD-CT)

- **AD-CT1 Pure first.** Draft rules, accounts, photo crop plan, the handoff payload, presence
  text, the WhatsApp target of a contact and the lookup cache are pure (`rovsti_core`,
  `rovsti_wa_proto`) with unit tests. Kotlin and GPUI only map.
- **AD-CT2 Number-less contacts are Contacts-only.** Phone keeps `pullContacts` (numbers only).
  Contacts switches to a new `pullContactsAll` (every visible contact, numbers may be empty,
  first email for the subtitle). A draft needs a name or organisation; numbers become optional.
- **AD-CT3 Accounts.** A writable contacts account is `type|name` (the key `ContactsEdit`
  already uses; empty = phone only). Kotlin lists accounts from `Groups` and `RawContacts`
  rows filtered by `ContentResolver.getSyncAdapterTypes()` with authority
  `ContactsContract.AUTHORITY` and `supportsUploading()`; no `GET_ACCOUNTS`. New contacts use the
  account picked in the editor ("Save to"), remembered as `ContactsPrefs::save_to`. Changing the
  account drops group ticks of the old account.
- **AD-CT4 Photos.** Android Photo Picker (`MediaStore.ACTION_PICK_IMAGES`, minSdk is 34, no
  permission), copied to cache (cap 20 MiB) and answered as `document_result` op
  `contact_photo`. Rust asks Kotlin for the decoded size, computes a centred square crop and an
  output side (max 720 px) with `rovsti_core::contact_photo::plan`, and Kotlin decodes with
  `ImageDecoder` (EXIF applied), crops, scales, writes JPEG q90 to
  `RawContacts.DisplayPhoto.CONTENT_DIRECTORY` of the primary raw contact. Remove deletes the
  `Photo.CONTENT_ITEM_TYPE` data row.
- **AD-CT5 WhatsApp from Contacts goes through the apps that own it.** Calls need Phone's
  In-Call screen and `WaCallAudio`, chats need Messages' conversation. Contacts hands off with an
  explicit intent (`dev.rovsti.action.WA_CALL` / `WA_CHAT`, extras `jid`, `name`). The target
  activity-alias is exported with permission `dev.rovsti.permission.HANDOFF`
  (`protectionLevel="signature"`), so only same-signed Rovsti apps can make Phone place a call.
  Kotlin `Handoff.start` resolves by action through `<queries>` and keeps only packages whose
  signature matches (`PackageManager.checkSignatures == SIGNATURE_MATCH`), which also covers
  Phone's debug `.perf` suffix. The receiving app turns it into a one-shot `handoff`
  (mailbox, survives a cold start). Phone places the call with its existing
  `WaCall::place`; Messages opens `wa:<jid>` like a notification tap. When the app is missing,
  Contacts says "Install Rovsti Phone to call on WhatsApp" (or Messages).
- **AD-CT6 Availability is lazy.** Contacts never sends the whole address book to WhatsApp. It
  marks list rows from the instance's existing chats (`GET /v1/chats`, no usync), and only when a
  card opens asks `POST /v1/contacts/lookup` for that contact's numbers (at most 12) and
  subscribes presence for the WhatsApp number. The gateway caches lookups per instance
  (24 h, 4096 entries) and rate-limits them (token bucket, 60 jids per minute) to protect the
  account from WhatsApp abuse limits. Local numbers without a country code only match an
  existing chat (`PhoneNumber::matches`); otherwise the card says "Add the country code to check
  WhatsApp".

## Steps

### Step 1. Pure: number-optional drafts, accounts, photo plan, prefs (`rovsti_core`)

Files: `crates/rovsti_core/src/{contact_edit.rs, contacts_view.rs, wire.rs, contacts_prefs.rs, contact_photo.rs (new), lib.rs}`, `crates/rovsti_core/src/vcard.rs` (tests only), `apps/contacts/src/{prefs.rs, main.rs, editor.rs}` (compile fixes only).

- `contact_edit`: remove `DraftError::NoNumber`; `validate` needs a name or organisation, numbers
  optional; `ContactDraft::blank()` unchanged (one empty mobile row, dropped by `clean` if left
  empty). Update the module doc (no longer "needs at least one number"). `display_name` falls back
  to the first email after the first number.
- `contact_edit::Account { key: String, name: String, kind: String }` (`#[serde(default)]`),
  `AccountsPayload { accounts: Vec<Account> }`, and
  `pub fn account_label(key: &str) -> String` ("On this phone" for `""`, `com.google|a@b` ->
  "Google, a@b", unknown types -> the name), `pub fn groups_of<'a>(groups: &'a [Group], account: &str) -> impl Iterator<Item = &'a Group>`,
  `pub fn retain_account_groups(draft: &mut ContactDraft, groups: &[Group])` (drops ids whose
  group is not in `draft.account`).
- `wire::WireContact`: `#[serde(default, skip_serializing_if = "Option::is_none")] pub email: Option<String>`.
  `contacts_view`: a contact with no numbers gets the email (else nothing) as subtitle;
  `call_target(&[])` already answers "none", keep it and add a test.
- `wire::DocumentOp::ContactPhoto` (serde `contact_photo`).
- New `contact_photo`: `pub const MAX_SIDE: u32 = 720; pub const MAX_PICK_BYTES: u64 = 20 << 20;`
  `#[derive(Serialize, Deserialize)] pub struct PhotoPlan { x: u32, y: u32, side: u32, out: u32 }`,
  `pub fn plan(width: u32, height: u32) -> Result<PhotoPlan, PhotoError>` (centred square,
  `out = side.min(MAX_SIDE)`, `PhotoError::TooSmall` under 96 px, `Empty` for 0),
  `pub fn accepts(mime: Option<&str>) -> bool` (`image/*`).
- `contacts_prefs::ContactsPrefs`: add `#[serde(default)] save_to: String` (account key for new
  contacts). This drops `Copy`; fix the call sites in `apps/contacts` with `.clone()`. Schema
  stays 1 (missing field reads as phone only).
- Tests: `a_draft_without_numbers_is_valid_with_a_name`, `a_draft_needs_a_name_or_org`,
  `display_name_falls_back_to_email`, `account_labels`, `switching_account_drops_foreign_groups`,
  `numberless_row_shows_email`, `plan_crops_centre_square_and_caps_side` (4000x3000,
  300x900, 96x96, 50x50 errors), `old_prefs_without_save_to_load`, vCard: an email-only card now
  imports.

Acceptance: tests pass, `apps/contacts` builds unchanged in behaviour, all checks green.

### Step 2. Pure WhatsApp: presence text, contact target, lookup wire and cache, handoff payload (`rovsti_wa_proto`, `rovsti_core`)

Files: `crates/rovsti_wa_proto/src/{presence.rs (new), lookup.rs (new), api.rs, lib.rs}`, `crates/rovsti_core/src/wire.rs`, `apps/messages/src/conversation.rs`.

- Move `presence_label` (and the `clock` / `day_label` helpers it needs) from
  `apps/messages/src/conversation.rs` to `rovsti_wa_proto::presence`:
  `pub fn presence_label(available: bool, last_seen_ms: Option<i64>, now_ms: i64, offset_s: i32) -> Option<String>`.
  Messages calls the moved function; its tests move with it.
- `presence::PresenceBook` (bounded map, 256 entries, oldest dropped):
  `apply(&mut self, event: &GatewayEvent, now_ms)` keeps `GatewayEvent::Presence {jid, available, last_seen_ms}`,
  `get(&self, jid) -> Option<(bool, Option<i64>)>`.
- `lookup`: `api::routes::CONTACTS_LOOKUP = "/v1/contacts/lookup"`,
  `LookupUsers { jids: Vec<String> }` (`MAX_LOOKUP = 50`), `WaUser { jid: String, on_whatsapp: bool, business: bool, verified_name: Option<String> }`.
  `LookupCache::new(cap, ttl_ms)`, `get(&self, jid, now_ms) -> Option<&WaUser>`,
  `put(&mut self, user, now_ms)`, `split(&self, jids, now_ms) -> (Vec<WaUser> /*hits*/, Vec<String> /*misses*/)`.
- `lookup::wa_target(numbers: &[&str], chat_jids: &[&str]) -> WaTarget` with
  `enum WaTarget { Chat(String), Number(String), NeedsCountryCode, None }`: a number that
  `PhoneNumber::matches` an existing 1:1 chat's user wins, else the first international number as
  `user_jid`, else `NeedsCountryCode` when numbers exist. `lookup::chat_users(chats: &[ChatSummary]) -> Vec<String>` (1:1 only).
- `rovsti_core::wire::Handoff { action: HandoffAction, jid: String, name: Option<String> }`,
  `enum HandoffAction { WaCall, WaChat, #[serde(other)] Unknown }`, `parse_handoff(json) -> Option<Handoff>`
  (rejects empty or non-`@s.whatsapp.net` jids).
- Tests: presence label table (moved), `book_keeps_latest_and_is_bounded`,
  `cache_ttl_and_cap`, `split_hits_and_misses`, `wa_target_prefers_known_chat`,
  `local_number_without_chat_needs_country_code`, `groups_are_not_targets`,
  `handoff_round_trip_and_rejects_bad_jid`, serde round-trips of `LookupUsers` / `WaUser`.

Acceptance: Messages behaviour unchanged (moved code only), all checks green.

### Step 3. Gateway and client: `POST /v1/contacts/lookup` (`services/wa_gateway`, `rovsti_wa_client`)

Files: `services/wa_gateway/src/{driver.rs, routes.rs, lib.rs, wa/session.rs}`, `services/wa_gateway/tests/api.rs`, `crates/rovsti_wa_client/src/http.rs`, `docs/whatsapp-gateway.md`.

- `WaDriver::lookup_users(&self, instance: &InstanceId, jids: Vec<String>) -> BoxFuture<'_, Result<Vec<WaUser>, DriverError>>`
  with a default body `Err(DriverError::NotConnected)` (so `UnlinkedDriver` needs nothing).
- Real driver (`wa/session.rs`): parse with `convert::parse_jid`, then
  `client.contacts().is_on_whatsapp(&jids).await` (whatsapp-rust 0.7
  `features/contacts.rs`: `Contacts::is_on_whatsapp(&self, jids: &[Jid]) -> Result<Vec<IsOnWhatsAppResult>, ContactError>`;
  result fields `jid`, `pn_jid: Option<Jid>`, `is_registered`, `is_business`,
  `verified_name: Option<VerifiedName { name: Option<String>, .. }>`). Map back to the queried PN:
  `r.pn_jid.unwrap_or(r.jid).to_non_ad().to_string()`; jids not answered come back
  `on_whatsapp: false`. Errors through the existing `failed`.
- `FakeDriver`: `set_registered(Vec<String>)`, records `lookup <n>` in `ops`.
- Route in `routes.rs` (`post(lookup_users)`), auth as every other route: reject empty, over
  `MAX_LOOKUP`, or non-user jids (`BadRequest`); serve cache hits from a per-instance
  `LookupCache` (24 h, 4096) kept in `AppState` (`Mutex<HashMap<InstanceId, LookupCache>>`);
  misses go through a per-instance `TokenBucket` (60 capacity, 1 per second refill, one token per
  jid) and answer `RateLimited` when empty; results are cached.
- Client: `Gateway::lookup_users(&self, jids: &[String]) -> Result<Vec<WaUser>, ClientError>`.
- Tests (`tests/api.rs`): `lookup_answers_registered_and_caches` (second call does not reach the
  driver: `ops` count), `lookup_rejects_bad_and_too_many_jids`, `lookup_is_rate_limited`,
  `lookup_needs_a_key`.
- Document the endpoint in `docs/whatsapp-gateway.md`.

Acceptance: gateway tests pass; client still checks for `aarch64-linux-android`.

### Step 4. Android seam: all contacts, accounts, photo pick and write, handoff (`rovsti_android` + shared Kotlin)

Files: `crates/rovsti_android/src/{providers.rs, contacts_edit.rs, documents.rs, handoff.rs (new), events.rs, lib.rs}`, `crates/rovsti_android/kotlin/dev/rovsti/bridge/{ContactsQuery.kt, ContactsEdit.kt, ContactPhotos.kt, Documents.kt, Handoff.kt (new), RovstiBridge.kt}`.

- `providers::contacts_all() -> Result<String>` -> `RovstiBridge.pullContactsAll()`:
  `ContactsQuery.allContactsJson` queries `Contacts.CONTENT_URI` (id, display names, sort keys,
  starred, photo, lookup key) and merges the `Phone` rows and the first `Email` per contact into
  the same `ProviderJson.contacts` envelope (numbers may be empty, `email` set).
- `contacts_edit::accounts() -> Result<String>` (`{accounts: [Account]}`) -> `contactAccounts()`
  per AD-CT3. `ContactsEdit.create` honours `draft.account` (`type|name` split on the first `|`;
  empty = null account) and only joins groups of that account; `saveGroup(groupId, title, account)`
  gains the account for new groups (Rust `save_group(group_id, title, account)`).
- Photos: `documents::pick_contact_photo() -> Result<bool>` -> `pickContactPhoto()` (Photo
  Picker, op `contact_photo`, `path`, `mime`); `contacts_edit::photo_size(path) -> Result<String>`
  (`{"w":..,"h":..}` from `ImageDecoder.setOnHeaderDecodedListener`, rotation applied);
  `contacts_edit::set_photo(contact_id, path, plan_json) -> Result<bool>`;
  `contacts_edit::remove_photo(contact_id) -> Result<bool>`. Kotlin in `ContactPhotos.kt`
  (decode with `ImageDecoder`, crop by the plan, `Bitmap.createScaledBitmap`, JPEG 90, write
  through `openAssetFileDescriptor(displayPhotoUri, "rw")`; delete the cached file afterwards).
- Handoff: `handoff::wa_call(jid, name) -> Result<bool>`, `handoff::wa_chat(jid, name) -> Result<bool>`
  -> `RovstiBridge.handoff(action, jid, name)`; `Handoff.kt` with `ACTION_WA_CALL`,
  `ACTION_WA_CHAT`, `EXTRA_JID`, `EXTRA_NAME`, `PERMISSION = "dev.rovsti.permission.HANDOFF"`,
  `start(context, action, jid, name)` (resolve by action, keep signature-matching packages,
  `FLAG_ACTIVITY_NEW_TASK`, false when none) and `RovstiBridge.deliverHandoff(intent): Boolean`
  (posts one-shot `handoff` `{action, jid, name}`, removes the extras). `events::kind::HANDOFF = "handoff"`.
- Desktop tests (`#[cfg(all(test, not(target_os = "android")))]`): every new function returns
  `Error::Unsupported`.

Acceptance: `cargo check -p rovsti_android --target aarch64-linux-android` and desktop tests
green; Kotlin compiles by inspection (no new dependencies).

### Step 5. Contacts app: number-less contacts and "Save to" account with its groups

Files: `apps/contacts/src/{backend.rs, editor.rs, main.rs, sample.rs, prefs.rs}`.

- `backend::pull_book` uses `providers::contacts_all()`; `backend::pull_accounts() -> Vec<Account>`
  (desktop: phone only plus one sample Google account from `sample.rs`);
  `backend::save_group(group_id, title, account)`.
- Card (`detail.rs`) and list already disable Call/Message without a primary number; show emails
  and the subtitle email; the editor no longer forces a number (`DraftError::NoNumber` is gone).
  `export_text` keeps every valid draft (now including number-less ones).
- Editor: for a new contact (no `id`) a "Save to" row cycling through `pull_accounts()`
  (`account_label`), defaulting to `ContactsPrefs::save_to` when it is still listed; switching
  calls `retain_account_groups`. The picked account is saved to prefs on save. Existing contacts
  show their account read-only. The Groups tab "New group" uses the same default account.
- Sample: one number-less contact with an email, and a group in the sample Google account.
- Tests (app, desktop): `backend::pull_accounts` falls back to the sample; the sample book has a
  number-less contact that builds a row with its email; prefs round-trip with `save_to`.

Acceptance: desktop run shows the number-less sample contact; checks green. Tick the two roadmap
items ("Contacts without a phone number", "Groups of a synced account for a new contact") with
"(done without a device)".

### Step 6. Contacts app: choose, change and remove a contact's photo

Files: `apps/contacts/src/{editor.rs, transfer.rs, photos.rs, backend.rs}`.

- Editor header: tap the avatar for a small sheet (rovsti_ui `sheet` as Messages uses it):
  "Choose photo", "Remove photo" (only when the contact has one). New contacts get the photo
  after the first save (the save returns the id; keep the picked path pending until then).
- `transfer.rs::on_document_result` routes `DocumentOp::ContactPhoto`: check
  `contact_photo::accepts(mime)`, then on the background executor
  `photo_size -> contact_photo::plan -> set_photo`; notice "Photo updated" or
  "Can't change the photo: ..." (`Failed::words`).
- `Photos::forget(id)` (drop the LRU entry and the miss mark) so the new thumbnail loads; the next
  `contacts_changed` pull refreshes `photo_uri`.
- Tests: `backend` decode of the size answer; `Photos::forget` clears cache and miss for that id.

Acceptance: desktop says "this needs an Android phone"; checks green. Tick "Choose or change a
contact's photo" (done without a device).

### Step 7. Phone and Messages accept the WhatsApp handoff; manifests

Files: `apps/phone/platforms/android/app/src/main/{AndroidManifest.xml, kotlin/dev/rovsti/phone/MainActivity.kt}`,
`apps/messages/platforms/android/app/src/main/{AndroidManifest.xml, kotlin/dev/rovsti/messages/MainActivity.kt}`,
`apps/phone/src/{backend.rs, main.rs}`, `apps/messages/src/{backend.rs, main.rs}`.

- Both manifests: `<permission android:name="dev.rovsti.permission.HANDOFF" android:protectionLevel="signature"/>`
  and an exported `<activity-alias android:name=".WaHandoff" android:targetActivity=".MainActivity" android:permission="dev.rovsti.permission.HANDOFF">`
  with the action filter (`WA_CALL` on Phone, `WA_CHAT` on Messages, category DEFAULT). Check
  Phone has `INTERNET` after the manifest merge; add it explicitly if not.
- `MainActivity.onCreate` / `onNewIntent`: `RovstiBridge.deliverHandoff(intent)` before other
  forwarding.
- Phone: the `handoff` one-shot (`rovsti_core::wire::parse_handoff`) with `WaCall` calls
  `WaCall::place(&dial_number(jid), cx)` (existing `wa_call::dial_number`), errors as the existing
  call-error notice. Messages: `WaChat` -> `on_open_tag(&format!("wa:{jid}"))`; check that a
  `wa:` jid with no chat yet opens an empty conversation with the contact name as title (pass
  `name`), fix if not.
- Tests: Phone `handoff_places_once_per_intent` style test for the pure dispatch helper
  (`fn handoff_number(h: &Handoff) -> Option<String>`); Messages tag building for a new chat.

Acceptance: checks green; no change for intents without the extras.

### Step 8. Contacts app: WhatsApp on the card and in the list, manifest, docs

Files: `apps/contacts/src/{whatsapp.rs (new), detail.rs, main.rs, transfer.rs}`,
`apps/contacts/platforms/android/app/src/main/AndroidManifest.xml`,
`apps/contacts/platforms/android/app/src/main/kotlin/dev/rovsti/contacts/MainActivity.kt`,
`docs/{roadmap.md, architecture.md, whatsapp-notes.md}`.

- Manifest: `INTERNET`; `<uses-permission android:name="dev.rovsti.permission.HANDOFF"/>` plus the
  same `<permission>` declaration (whichever Rovsti app installs first defines it);
  `<queries>` with the two handoff actions. No FCM: Contacts does not register push.
- `whatsapp.rs`: `struct WaPanel { chat_users: Vec<String>, book: PresenceBook, lookups: HashMap<i64 /*contact*/, LookupState> }`
  with `enum LookupState { Checking, Done(Vec<WaUser>), Failed }`. On connect and on `Resync`:
  `gateway.chats()` -> `lookup::chat_users` (background executor). On the existing
  `cx.observe(&whatsapp, ..)`: drain `take_events()` into `PresenceBook::apply`. On card open
  (`open_contact`): `lookup::wa_target`; for `Number` call `lookup_users` (skip when cached in the
  panel), then `subscribe_presence(jid)` once per jid per run.
- Card: a WhatsApp section under the numbers when the instance is connected: status line
  (`presence_label` with the local offset, "On WhatsApp", "Not on WhatsApp", "Checking…",
  "Add the country code to check WhatsApp"), and two glass actions "Chat" and "Call"
  (`Icon::LucideMessageCircle`, `Icon::LucidePhone`, both already used in the apps at
  d6c8412) calling `handoff::wa_chat` / `wa_call` on the background executor; a `false` answer
  shows "Install Rovsti Messages/Phone to ..."; desktop shows the Unsupported notice. Hidden when
  WhatsApp is not connected (a one-line hint links to the WhatsApp settings page instead).
- List: a small WhatsApp mark on rows whose number matches a known chat (computed once per pull
  and chats refresh, never per frame).
- Tests: `whatsapp.rs` pure helpers (status text from `LookupState` + presence; which contacts
  get the list mark), and that a contact without international numbers or chats asks nothing.
- Docs: roadmap new ticked items "WhatsApp availability on the card, chat and call through Phone
  and Messages (done without a device)"; architecture AD-CT1..AD-CT6 summary under "What each
  app needs from Android" (Contacts row: `INTERNET`, Photo Picker, handoff); fix
  `docs/whatsapp-notes.md` line 27.

Acceptance: all checks green; desktop run shows the WhatsApp section state from the sample
link state; Contacts never sends more than one contact's numbers per card open.

## Left out (needs a device or an owner decision)

- Every on-phone check: editor, groups, vCard, photo write on HyperOS, Photo Picker, account
  visibility of synced accounts, handoff across signed APKs (debug and release must share a key
  for the signature permission), presence privacy settings of real contacts.
- Bulk "who is on WhatsApp" for the whole address book (AD-CT6 keeps it lazy on purpose).
- WhatsApp profile pictures as contact photos (`Contacts::get_profile_picture` exists in
  whatsapp-rust 0.7, but writing someone's WhatsApp picture into the address book is an owner call).
- Video and group calls (gateway roadmap).
