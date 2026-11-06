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
- [x] Answer from the notification after a cold start, call_end push, and missed WhatsApp
      calls in Recents (done without a device)
- [x] Quick actions (Message, Copy, Share, Block) on the Contact and Number sheets, with a
      number chooser (done without a device)
- [x] Call history in the sheets: recent calls with time and duration (done without a device)
- [x] Favorites in the user's order with Move earlier/later and drag reorder, and the Frequent
      row with a Show Frequent setting (done without a device)
- [x] Keypad Add to contacts and Message, Recents menu Add to contacts, WhatsApp call-back from
      Recents, Undo pills for speed dial Remove and quick reply Clear (done without a device)
- [x] Delete call history (CallLogWrite, spike S12): Recents row Delete with Undo, "Delete call
      history" in the sheets, Settings > Data "Delete all call history", Speed dial row on the
      Contact sheet (done without a device; the S12 device check is pending,
      `docs/spikes/s12-calllog.md`)
- [x] Per-call delete in the sheets (Edit, trash on a line, Undo, live refresh) and blocked calls
      history and delete in the Number sheet (done without a device; S12 device check pending)
- [x] Voicemail: glyph on the Keypad `1` key and "No voicemail number set" with Open call
      settings when none is configured (done without a device; the S9 device check is PENDING)

## Rovsti Messages

- [x] SMS/MMS read and write through the Telephony provider once `ROLE_SMS` is held
      (done without a device)
- [x] Conversation view with glass composer, delivery reports and dual-SIM send
      (done without a device)
- [x] Unified inbox: one person shows SMS and WhatsApp threads, matched by number
      (done without a device)
- [x] WhatsApp through the gateway: link by QR or pairing code, history sync, text,
      media, reactions, groups and presence all run server-side; Messages shows chats
      and live messages from `rovsti_wa_client`
- [x] WhatsApp conversation view with composer, media and reactions in the UI; send pictures, video, audio and documents
- [x] No foreground service: FCM push wakes the app (needs `google-services.json` in
      the host, see `docs/android.md`)
- [x] Notifications with inline reply, for SMS and WhatsApp, cold and while the app is
      open; a bell in each conversation mutes it (done without a device)
- [x] OTP detection and copy, spam filter (on-device rules, allow list, system
      blocklist), scheduled send for SMS (alarm) and WhatsApp (gateway), with a
      Scheduled strip per conversation (done without a device)
- [x] Manage conversations: delete, not spam, block, copy, search, drafts (done without a device)
- [x] Second UX pass: save received WhatsApp files, unblock and make-default from the SMS
      row menu, WhatsApp chat menu, share, scheduled strip with Show more and restore on
      cancel, Draft line in the inbox, names in Settings > Muted, custom send time picker
      (done without a device)
- [x] Encrypted local backup and export to a user-chosen file, and import with a
      preview (done without a device)
- [x] Third UX pass: keyboard Send on WhatsApp, failed message Copy/Delete, save MMS
      pictures, open links, Undo for archive and mute, WhatsApp offline banner, tap to
      retry a load, restore asks for the role first, Reset settings with Undo
      (done without a device)
- [x] Fourth UX pass: permission asks and refresh on return, location and contact card
      actions, one WhatsApp mute, Not spam from the conversation, resend failed scheduled
      SMS, recovery after failed WhatsApp actions (done without a device)
- [x] Fifth UX pass: move a sender to Spam, drafts survive restarts, Send later in the
      menu, scheduled overview, failed WhatsApp schedule notice, message from Status,
      search archived chats (done without a device)

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
- [x] Choose or change a contact's photo (done without a device)
- [x] Contacts without a phone number (done without a device)
- [x] Groups of a synced account for a new contact (done without a device)
- [x] WhatsApp: the shared settings screen (resolved, see `docs/whatsapp-notes.md`)
- [x] WhatsApp availability on the card (on WhatsApp or not, online or last seen) and a
      WhatsApp mark in the list for contacts with a chat (done without a device)
- [x] Chat and call on WhatsApp from the card, through Rovsti Messages and Phone
      (done without a device)
- [ ] Check the editor, groups and vCard flows on a phone: written without a device

## Rovsti Stops

- [x] Foreground location service → `CrossingTrip::update`, using `next_fix_in` for battery
      (done without a device)
- [x] Full-screen alarm with sound, vibration and snooze, which survives Doze; a failed arrival
      message is shown on the live card and the alarm; the alarm shows when no snoozes are left
      (done without a device)
- [x] Diameter-crossing trigger: pick two points A and B (shared `geo:` URIs and maps links, saved
      places, recent trips, GTFS stops). The circle has AB as its diameter; the line AB splits it
      into a left and a right half (facing A to B), both shown on the map. Crossing from one half
      to the other inside the circle fires the alarm in either direction. A dead band of
      max(10 m, fix accuracy) around the line keeps GPS jitter quiet, and a crossing counts only
      when the path between the last confident fixes cuts the segment AB, so sparse fixes still
      fire and passing outside the circle never does (done without a device)
- [x] Tile map under the vector radar: Shortbread vector tiles (zoom 0-14, overzoomed beyond)
      from a settable VersaTiles-compatible server (Settings > Map tiles: test before save,
      reset to default), VersaTiles colorful in the light theme and eclipse in the dark one,
      OSM attribution, disk cache with LRU and prefetch around the trip circle for offline
      use, radar look where a tile is missing (done without a device)
- [ ] Check tile drawing speed on a phone (vertex budget, tiles per view)
- [x] Optional GTFS import: stop names and lines on the map; a stop is a point source for A or B
      (done without a device)
- [x] Trip history and statistics, all local (done without a device)
- [x] Arriving-soon WhatsApp message: one chat, a template, with the alarm or 5/10/15 min
      before, through the gateway (done without a device)
- [x] Pick A and B on the map: tap to drop, drag to adjust, snap to stops, my location (done
      without a device)
- [x] Save and manage places from map pins, links and stops (name, rename, reorder, delete with
      undo); share a place or any pin; paste links into the picker; the chosen A/B survive a
      restart; Stop and settings resets can be undone; reuse trips from History; downloaded map
      size and Clear in Settings > Map tiles; default arriving-soon text; search every WhatsApp chat
      for the arriving-soon message; the chat list refreshes (done without a device)
- [x] Delete single History trips, with undo (done without a device)
- [x] Saved places drawn on the map; a tap on one picks it exactly (done without a device)
- [x] Move a saved place on the map, with undo (done without a device)
- [ ] Check map picking one-handed on a phone
- [ ] Check the trip, alarm and Doze on a phone: written without a device
