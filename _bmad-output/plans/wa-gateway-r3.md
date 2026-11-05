# Plan: WhatsApp gateway, round 3 (gap check)

- Base: `20084e65fd2a2dff87d780cc07696e621c15270e` ("… review fixes")
- Round 2 (`wa-gateway-r2.md`) landed all seven steps (`82956bf` … `20084e6`).
  No stash, nothing blocked.
- Checks at the base, all green: `cargo fmt --all --check`,
  `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`
  (30 test binaries, 0 failed), and `cargo check --target aarch64-linux-android` for
  `rovsti_android`, `rovsti_wa_client` and `rovsti_wa_proto`.

## Gap analysis against the whole goal

| Goal item | State |
|---|---|
| `services/wa_gateway` (axum + tokio, workspace member) owns all whatsapp-rust sessions | Done |
| Linking by QR and pairing code, relink after logout, delete instance | Done (`routes.rs`, widget) |
| History sync, text, media, reactions, groups, presence, chat state | Done (gateway routes, client, Messages conversation) |
| Voice calls: offer/accept/reject/terminate, audio over WebSocket bridged to voip | Done (`wa::calls`, Phone `wa_call.rs`, incoming and outgoing) |
| FCM HTTP v1 push, config-gated; token registration; wake reaches the event stream | Done (`push/`, `register_push`, `reconnect_now`, `forward_push` / Phone and Contacts drains) |
| `crates/rovsti_wa_client` (HTTP + WS, rustls, builds for Android) | Done |
| Instance model: connect keys hashed at rest, revocable, stored locally; list/revoke UI | Done |
| Shared two-option settings widget (`rovsti_wa_ui`) in Phone, Messages, Contacts, Stops | Done |
| Messages migrated off local session; `rovsti_whatsapp` removed | Done |
| Docs: architecture, README, gateway deploy docs and files | Done |

Remaining roadmap items are out of scope for this unit by owner decision or
environment: device testing (no physical device here), video and group calls,
sending media picked on the phone.

## Steps

None. The unit is done.
