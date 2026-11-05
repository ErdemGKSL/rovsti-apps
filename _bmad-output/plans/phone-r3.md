# Plan: Rovsti Phone, round 3

- Base: `9a1257c2999d5b695cc3292283fd1fc129bc9c50` ("Rovsti Phone: review fixes")
- Unit: Rovsti Phone. Architect plan, no source changed.
- Verdict: **done**. No further steps.

## State at the base

Round 1 (`phone.md`, commits `3fca1aa`..`bdb3c78`) and round 2 (`phone-r2.md`, commits
`d6af978`..`9a1257c`) landed every step. `git stash list` is empty. All checks pass at the
base (`cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`,
`cargo test --workspace`, `cargo check -p rovsti_android --target aarch64-linux-android`,
`cargo check -p rovsti_wa_client --target aarch64-linux-android`).

## Goal coverage

| Goal item | Where it is |
| --- | --- |
| Open Phone roadmap items that need no device | All Phone items in `docs/roadmap.md` are ticked (speed dial, voicemail, T9, WhatsApp calls, FCM, Recents, cold start) |
| Place a WhatsApp call from a contact / Recents / keypad | `apps/phone/src/wa_call.rs`, contact sheet `sheet-cw-*`, Recents, keypad chip |
| Incoming WhatsApp call woken by FCM | `WaPush.kt` / `WaIncoming.kt` (CallStyle, Answer/Decline, `call_end`, `wa_missed`), `rovsti_wa_proto::push::Wake`, `WaCallUi::sync_calls` + `plan_snapshot` for a cold start |
| In-call UI reusing the glass screen | `CallSource::WhatsApp` on the Incoming and In-Call screens, `rovsti_wa_proto::call::{WaScreen, arbitrate}` |
| Audio over the gateway | Gateway `voip-mlow` bridge + audio WebSocket, Kotlin `WaCallAudio`, `wa::call_audio_speaker` |
| Shared WhatsApp settings (link / connect with key) | `rovsti_wa_ui::WhatsAppSettings` in Phone, Messages, Contacts and Stops; key list/revoke |

## Left out (needs a device or an owner decision)

On-device acceptance (`docs/phone-acceptance.md`), first APK and CI, video and group
calls, a Telecom `ConnectionService` for WhatsApp calls.
