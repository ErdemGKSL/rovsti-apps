# Roadmap

Planning follows BMAD (`/bmad-help` → product brief → PRD → architecture → epics).
This file is the seed backlog. Done means it works on a physical Xiaomi device.

## Foundation (done in the scaffold)

- [x] Cargo workspace on gpui-fusion `d6c8412` with `gpui_android`
- [x] Liquid-glass kit, shared tab bar and components
- [x] Phone number normalisation and matching (`rovsti_core`)
- [x] Arrival-alarm state machine with adaptive GPS polling (`rovsti_geo`)
- [x] WhatsApp companion session, QR PNG rendering, share sheet hook (superseded by the gateway below)
- [x] JNI bridge: roles, place call, Xiaomi deep links, battery optimisation
- [ ] First APK on a device through GPUiForge (needs NDK r29 + GPUiForge)
- [ ] Eject Android hosts and add `RovstiBridge` + `Application` to each app
- [ ] CI: fmt, clippy, tests, Android `cargo check`

## Rovsti Phone

Ticked here means built and covered by tests; the on-phone check is the acceptance
list in `docs/phone-acceptance.md`.

- [x] InCallService with a glass in-call screen (mute, speaker, keypad, hold, swap, merge)
- [x] Full-screen incoming-call UI, including on the lock screen
- [x] Call log (`CallLog.Calls`) grouped by person, with missed-call badges
- [x] Contacts (`ContactsContract`) with search and favourites
- [x] T9 search on the keypad (`rovsti_core::t9`)
- [x] Speed dial, long-press `1` for voicemail
- [x] Dual-SIM picker (`PhoneAccountHandle`), which Xiaomi phones commonly have
- [x] Blocklist through `BlockedNumberContract`
- [x] Audio routing (earpiece, speaker, Bluetooth) through GPUiForge `audio-routing`
- [x] WhatsApp calls on the In-Call screen (Mute, Speaker, End), placed from Recents,
      the Contact sheet or the keypad chip; one arbitration rule against cellular calls
      (done without a device)
- [x] FCM incoming-call notification (full-screen, Answer and Decline) that rings a cold
      phone (done without a device)
- [x] WhatsApp calls in Recents with a WhatsApp badge (done without a device)

## Rovsti Messages

- [ ] SMS/MMS read and write through the Telephony provider once `ROLE_SMS` is held
- [ ] Conversation view with glass composer, delivery reports and dual-SIM send
- [ ] Unified inbox: one person shows SMS and WhatsApp threads, matched by number
- [x] WhatsApp through the gateway: link by QR or pairing code, history sync, text,
      media, reactions, groups and presence all run server-side; Messages shows chats
      and live messages from `rovsti_wa_client`
- [x] WhatsApp conversation view with composer, media and reactions in the UI
- [x] No foreground service: FCM push wakes the app (needs `google-services.json` in
      the host, see `docs/android.md`). Notifications with inline reply are still open.
- [ ] OTP detection and copy, spam filter (on-device rules), scheduled send
- [ ] Encrypted local backup and export to a user-chosen file

## WhatsApp gateway (`services/wa_gateway`)

- [x] `rovsti_wa_proto`, `rovsti_wa_client` (builds for Android without the NDK),
      `rovsti_wa_ui` (two options: link for the first time, or connect with a key)
- [x] Instances and revocable connect keys, hashed at rest
- [x] Resumable event WebSocket, history sync, text, media, reactions, groups, presence
- [x] Voice calls: gateway `voip-mlow` bridge, call audio over a WebSocket, Kotlin
      `WaCallAudio`, Phone call card (Answer, Decline, Mute, End)
- [x] FCM HTTP v1 push, config-gated; apps register tokens (`WaPush`)
- [ ] Try it on a phone: link a real account, place and answer a call, FCM on HyperOS
- [x] Outgoing WhatsApp calls from Phone (`place_call` in the client)
- [ ] Video calls, group calls
- [x] Packaging the gateway (Dockerfile, systemd unit, Caddyfile, example config in
      `services/wa_gateway/deploy/`) and the key list/revoke UI

## Rovsti Contacts

- [x] Scaffold: sectioned list with search, Favorites, contact card (call,
      message, share, edit, star), name format and sort settings, desktop sample
- [x] Eject the Android host and package `RovstiBridge` (contacts pull, intents).
      The host adds `crates/rovsti_android/kotlin` as a Gradle source directory, so
      it needs no symlink and builds on Windows.
- [x] Persist name format and sort (`contacts-prefs.json` in the app data dir;
      `rovsti_core::contacts_prefs`, read and written by `apps/contacts/src/prefs.rs`)
- [x] Create, edit and delete contacts in-app (`rovsti_core::contact_edit` checks the
      form; Kotlin `ContactsEdit` writes through the primary raw contact). The system
      editor stays as "Open in Android Contacts".
- [x] Photos on the list and the card, groups (Groups tab, join a group in the edit
      form, create, rename, delete), vCard import and export (`rovsti_core::vcard`,
      duplicates skipped by `rovsti_core::import_plan`, files through the Storage
      Access Framework)
- [ ] Choose or change a contact's photo
- [ ] Contacts without a phone number (the list and the editor need a number today)
- [ ] Groups of a synced account for a new contact (new contacts live on the phone only)
- [x] WhatsApp: the shared settings screen (resolved, see `docs/whatsapp-notes.md`)
- [ ] Check the editor, groups and vCard flows on a phone: written without a device

## Rovsti Stops

- [ ] Foreground location service → `Trip::update`, using `next_fix_in` for battery
- [ ] Full-screen alarm with sound, vibration and snooze, which survives Doze
- [ ] Pick destination: from shared `geo:` URIs and maps links, saved stops, and the last trips
- [ ] Offline map tiles or a minimal vector map (decide: bundled region vs user import)
- [ ] Optional GTFS import for stop names and line-aware alarms ("2 stops before")
- [ ] Trip history and statistics, all local
