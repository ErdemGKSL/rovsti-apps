# Rovsti apps: agent notes

- Rust monorepo of Android apps (Phone, Messages, Stops) on **gpui-fusion**. Read
  `docs/architecture.md` before changing crate boundaries.
- **One GPUI source.** Every `gpui*` dependency comes from the gpui-fusion rev pinned
  in the root `Cargo.toml` `[workspace.dependencies]`. Bump them together and keep
  `[patch.crates-io]` in sync with gpui-fusion's root manifest at that rev. Never
  add `gpui` from crates.io or zed-industries.
- Never invent GPUI APIs. The fork moves fast, so grep its source at the pinned rev
  (`~/.cargo/git/checkouts/gpui-fusion-*/cc05989/crates/gpui`).
- UI follows `.claude/skills/liquid-glass-gpui` (read its SKILL.md before UI work).
  Product choices are set in `crates/rovsti_ui/src/lib.rs`
  (`ANIMATED_RESIZE = false`, `FOLLOW_SYSTEM_THEME = true`).
- Rust style follows `.claude/skills/rust-best-practices`.
- Keep domain logic in platform-free crates (`rovsti_core`, `rovsti_geo`) with unit
  tests. GPUI and JNI code stay thin.
- Android-only code goes through `rovsti_android`. Every function there must compile
  on desktop and return `Error::Unsupported` there.
- Apps are GPUiForge binary packages: `src/main.rs`, no `[lib]`, no `[[bin]]`.
- Everything is local and serverless. Do not add a backend, analytics or cloud sync.
- Planning uses BMAD (`/bmad-help`). Its outputs go to `_bmad-output/`, and project
  knowledge goes to `docs/`.

Checks before you finish:

```
cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
```
