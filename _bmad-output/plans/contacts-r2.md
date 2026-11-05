# Plan: Rovsti Contacts, round 2

- Base: `be79ea0744772bc6d64d41b5cc6a396e8176abdc` ("Rovsti Contacts: review fixes")
- Unit: Rovsti Contacts. Architect: PLAN agent. No source changed by this plan.
- Verdict: **done**. No further steps.

## Evidence

All 8 steps of `contacts.md` landed (commits `a23781b` .. `be79ea0`), no stash entries.

- Android host + `RovstiBridge`, persisted name format/sort (`ContactsPrefs`, now with `save_to`),
  in-app create/edit/delete, photos (show, choose, change, remove), groups (incl. synced-account
  groups for new contacts), vCard import/export, number-less contacts: ticked in `docs/roadmap.md`.
- WhatsApp per contact: `apps/contacts/src/whatsapp.rs` (lazy `POST /v1/contacts/lookup`,
  `PresenceBook` + `presence_label`, list mark from existing chats), chat/call via
  `rovsti_android::handoff::{wa_chat, wa_call}` to Messages / Phone (signature permission
  `dev.rovsti.permission.HANDOFF`, `deliverHandoff` in both hosts).
- Shared WhatsApp settings (`rovsti_wa_ui::WhatsAppSettings`) in Phone, Messages, Contacts, Stops.
- Contacts manifest has `INTERNET`, the handoff permission and `<queries>`.
- Checks at the base: fmt, clippy `-D warnings`, `cargo test --workspace`,
  `cargo check -p rovsti_android` and `-p rovsti_wa_client` for `aarch64-linux-android`: all green.

## Left open (needs a device, not plannable here)

- "Check the editor, groups and vCard flows on a phone", plus photo write, Photo Picker,
  synced-account visibility, cross-APK handoff signing and real presence privacy.
