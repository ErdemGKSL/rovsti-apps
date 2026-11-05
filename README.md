# Rovsti apps

A Rust monorepo of Android apps with an iOS-style liquid-glass UI, built on
[GPUI](https://github.com/zed-industries/zed/tree/main/crates/gpui) through the
[gpui-fusion](https://github.com/cradiy/gpui-fusion) fork, tuned for Xiaomi
(MIUI / HyperOS) phones. Everything runs on the device, with one exception: WhatsApp runs on a gateway you host
(`services/wa_gateway`). No Rovsti servers and no accounts.

| App | Crate | What it does |
| --- | --- | --- |
| **Rovsti Phone** | `apps/phone` | Default dialer: keypad, recents, contacts, favourites, in-call UI |
| **Rovsti Messages** | `apps/messages` | Default SMS app plus WhatsApp (through your gateway) in one inbox |
| **Rovsti Contacts** | `apps/contacts` | Address book: search, favorites, contact cards with call, message and share |
| **Rovsti Stops** | `apps/stops` | "Wake me there": offline GPS arrival alarm for buses, trains and ferries |

## Layout

```
apps/
  phone/  messages/  contacts/  stops/   one GPUI binary each (src/main.rs + gpuiforge.json)
crates/
  liquid_glass_kit/   glass material + spring motion (from the liquid-glass-gpui skill)
  rovsti_ui/          shared components: glass tab bar, rows, sections, data dirs
  rovsti_core/        platform-free domain types (phone numbers, contacts, messages)
  rovsti_geo/         offline geometry and the arrival-alarm state machine
  rovsti_wa_proto/    platform-free WhatsApp gateway protocol: wire types, connect keys, events
  rovsti_wa_client/   HTTP + WebSocket client for the gateway (rustls, builds for Android)
  rovsti_wa_ui/       shared WhatsApp settings screen: link for the first time, or connect with a key
  rovsti_android/     roles, telecom, Xiaomi settings, JNI bridge (+ Kotlin in kotlin/)
services/
  wa_gateway/         the WhatsApp gateway: owns the whatsapp-rust sessions (axum + tokio)
docs/                 architecture, Android build, Xiaomi notes, roadmap
_bmad/  .claude/skills/   BMAD method + project skills
```

## Quick start (desktop preview)

Every app runs on Linux for UI work; Android-only features report
"not available on this platform".

```bash
cargo run -p rovsti_phone
```

```bash
cargo test --workspace
```

## Android

See [docs/android.md](docs/android.md). In short: install the NDK and
[GPUiForge](https://github.com/cradiy/gpuiforge), then run this from an app directory:

```bash
gpuiforge run android
```

## Agent skills

`.claude/skills/` holds `liquid-glass-gpui` (design system), `rust-best-practices`
and the BMAD method (`bmad-*`, configured in `_bmad/`). Start planning with
`/bmad-help`.
