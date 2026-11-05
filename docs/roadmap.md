# Roadmap

Planning follows BMAD (`/bmad-help` → product brief → PRD → architecture → epics).
This file is the seed backlog. Done means it works on a physical Xiaomi device.

## Foundation (done in the scaffold)

- [x] Cargo workspace on gpui-fusion `d6c8412` with `gpui_android`
- [x] Liquid-glass kit, shared tab bar and components
- [x] Phone number normalisation and matching (`rovsti_core`)
- [x] Arrival-alarm state machine with adaptive GPS polling (`rovsti_geo`)
- [x] WhatsApp companion session, QR PNG rendering, share sheet hook
- [x] JNI bridge: roles, place call, Xiaomi deep links, battery optimisation
- [ ] First APK on a device through GPUiForge (needs NDK r29 + GPUiForge)
- [ ] Eject Android hosts and add `RovstiBridge` + `Application` to each app
- [ ] CI: fmt, clippy, tests, Android `cargo check`

## Rovsti Phone

- [ ] InCallService with a glass in-call screen (mute, speaker, keypad, hold, swap, merge)
- [ ] Full-screen incoming-call UI, including on the lock screen
- [ ] Call log (`CallLog.Calls`) grouped by person, with missed-call badges
- [ ] Contacts (`ContactsContract`) with search, favourites and T9 search on the keypad
- [ ] Speed dial, long-press `1` for voicemail
- [ ] Dual-SIM picker (`PhoneAccountHandle`), which Xiaomi phones commonly have
- [ ] Blocklist through `BlockedNumberContract`
- [ ] Audio routing (earpiece, speaker, Bluetooth) through GPUiForge `audio-routing`

## Rovsti Messages

- [ ] SMS/MMS read and write through the Telephony provider once `ROLE_SMS` is held
- [ ] Conversation view with glass composer, delivery reports and dual-SIM send
- [ ] Unified inbox: one person shows SMS and WhatsApp threads, matched by number
- [ ] WhatsApp: QR link + share (done), **pairing-code link** (number entry), history
      sync, send text, media download, reactions, groups
- [ ] Foreground `dataSync` service so WhatsApp stays connected, plus notifications
      with inline reply
- [ ] OTP detection and copy, spam filter (on-device rules), scheduled send
- [ ] Encrypted local backup and export to a user-chosen file

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
- [ ] WhatsApp session owner? See `docs/whatsapp-notes.md`
- [ ] Check the editor, groups and vCard flows on a phone: written without a device

## Rovsti Stops

- [ ] Foreground location service → `Trip::update`, using `next_fix_in` for battery
- [ ] Full-screen alarm with sound, vibration and snooze, which survives Doze
- [ ] Pick destination: from shared `geo:` URIs and maps links, saved stops, and the last trips
- [ ] Offline map tiles or a minimal vector map (decide: bundled region vs user import)
- [ ] Optional GTFS import for stop names and line-aware alarms ("2 stops before")
- [ ] Trip history and statistics, all local
