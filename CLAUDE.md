# Rovsti apps: agent notes

- Rust monorepo of Android apps (Phone, Messages, Stops) on **gpui-fusion**. Read
  `docs/architecture.md` before changing crate boundaries.
- **One GPUI source.** Every `gpui*` dependency comes from the gpui-fusion rev pinned
  in the root `Cargo.toml` `[workspace.dependencies]`. Bump them together and keep
  `[patch.crates-io]` in sync with gpui-fusion's root manifest at that rev. Never
  add `gpui` from crates.io or zed-industries.
- Never invent GPUI APIs. The fork moves fast, so grep its source at the pinned rev
  (`~/.cargo/git/checkouts/gpui-fusion-*/d6c8412/crates/gpui`).
- UI follows `.claude/skills/liquid-glass-gpui` (read its SKILL.md before UI work). Layout, spacing, safe area and
  component anatomy: `docs/design/layout.md`.
  Product choices are set in `crates/rovsti_ui/src/lib.rs`
  (`ANIMATED_RESIZE = false`, `FOLLOW_SYSTEM_THEME = true`).
- Rust style follows `.claude/skills/rust-best-practices`.
- Keep domain logic in platform-free crates (`rovsti_core`, `rovsti_geo`) with unit
  tests. GPUI and JNI code stay thin.
- Android-only code goes through `rovsti_android`. Every function there must compile
  on desktop and return `Error::Unsupported` there.
- Apps are GPUiForge binary packages: `src/main.rs`, no `[lib]`, no `[[bin]]`.
- Everything is local except WhatsApp, which runs on the user's gateway
  (`services/wa_gateway`). Apps reach it only through `rovsti_wa_client`. A
  linked WhatsApp instance is shared by apps via a connect key (link once in any app,
  or paste the key from another app). Stops also downloads map tiles from the
  user-chosen tile server (default the public VersaTiles server, `tiles.versatiles.org`)
  and caches them on the device. No other backend, analytics or cloud sync.
- Planning uses BMAD (`/bmad-help`). Its outputs go to `_bmad-output/`, and project
  knowledge goes to `docs/`.
- Licence: GPL-3.0-or-later (`LICENSE`). Ported code keeps its upstream header and adds `Ported from Convx
  (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>` (or the glisse line); list new sources in
  `android/THIRD_PARTY_NOTICES.md`.

Checks before you finish:

```
cargo fmt --all && cargo clippy --workspace --all-targets --profile checks && cargo test --workspace --profile checks
cargo check --release -p rovsti_android --target aarch64-linux-android
```

`scripts/kotlin-ui-check.sh` is the full gate and runs **release builds only** (host Rust uses `--profile checks`, Gradle
uses the `*Release*` tasks). Never build debug (Rust or APK): every cargo command takes `--profile checks` or `--release`.
Never `cargo clean`, never delete `target/` itself. `target/debug` may always be deleted. If disk is low delete
`target/debug`, then `target/checks` or one module's `android/<module>/build`, never all of them.
