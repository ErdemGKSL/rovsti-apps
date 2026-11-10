# Kotlin UI: epics and stories

Architecture: `docs/kotlin-ui/architecture.md` (cited as **ARCH n**). Skill: `.claude/skills/convx-motion-compose`
(cited as **SKILL ref section**). Toolchain and the check script: `docs/kotlin-ui/toolchain.md`.
Branch: `ui-overhaul` (never push to `main`).

## How to use this file

- Epics run in order: E1 design system, E2 Rust bridge, E3 platform layer, E4 Contacts, E5 Phone, E6 Messages
  (with WhatsApp), E7 Stops, E8 release. E1 does not need E2 (`:design` has no Rust); E2 and E3 may overlap once E1
  round 1 is done.
- Each epic is split into **rounds of at most 8 stories**. A planner picks one round, writes its plan under
  `_bmad-output/kotlin-ui/plans/<epic>-r<n>.md`, and updates the **Status** column here: `todo`, `in-progress`,
  `done`. Add an **Evidence** line under a story when it is done (commit, the PNGs that were opened and what was
  seen).
- **Hard dependencies** (a round may start only when these are `done`; the planner checks this table first):

  | Needs | Stories |
  |---|---|
  | E3 `:shell` and `:wa` | E1.1-E1.8, E1.18 (nav), E2.6 |
  | E4 Contacts | E1 done; E2.7, E2.10; E3.2, E3.6, E3.8a/b, E3.10, E3.11 |
  | E5 Phone | E1 done; E2.9, E2.10 (`ContactIndex`), E2.14; E3.2, E3.3, E3.5, E3.8a/b, E3.9, E3.10, E3.11 |
  | E6 Messages | E1 done; E2.8, E2.11a/b, E2.14; E3.4, E3.6, E3.9, E3.10, E3.11 |
  | E7 Stops | E1 done; E2.8, E2.12, E2.13; E3.7, E3.8a/b, E3.11 |
  | E8 | all of E4-E7; E8.7 (owner acceptance) before E8.6 (removal) |

- Every app epic first reaches **feature parity** with its GPUI app (the parity table lists every feature ID from
  `_bmad-output/plans/util-*.md` and the roadmap, `docs/roadmap.md`, and the story that delivers it), then polish.
- Definition of done for every story (not repeated below):
  1. `scripts/kotlin-ui-check.sh` passes (and the `CLAUDE.md` Rust checks if a workspace crate changed).
  2. New pure functions have JVM tests; gestures have Compose tests with `mainClock.autoAdvance = false`.
  3. The listed screenshots exist as goldens (`android/<module>/screenshots/`), each in light and dark unless the
     story says otherwise, and **every new or changed PNG was opened with the Read tool and judged**
     (ARCH 9). What was seen goes in the Evidence line.
  4. `references/checklist.md` sections that apply are ticked or have a written reason.
  5. Licence (R3 owner decision: Rovsti is GPL-3.0-or-later): Convx code is ported close to verbatim with its original
     header plus the "Ported from Convx" line; glisse-derived files carry the glisse header; `android/THIRD_PARTY_NOTICES.md`
     updated for any new third-party code.
  6. Owner rules from 2026-11-10 (apply to every app, Phone included from round 12 on, and to Messages and Stops):
     - **Turkish:** every user-visible string lives in `res/values/strings.xml` (no hard-coded UI text in Kotlin) and has
       a natural Turkish entry in `res/values-tr/strings.xml`; Android lint `MissingTranslation` must pass. English stays
       the default; a Turkish phone shows Turkish automatically.
     - **Launcher name and icon:** the manifest label is `@string/launcher_name` (`res/values/launcher.xml`), plain
       "Phone"/"Messages"/"Stops" in English and "Telefon"/"Mesajlar"/"Duraklar" in `values-tr` (no "Rovsti").
       The icon is the same scheme as Contacts and Phone: a liquid-glass glyph art from the image-gen skill
       (`drawable-nodpi/ic_launcher_art.webp`, inset 20%) over a vector gradient background, with a flat white vector
       glyph as the monochrome layer.
     - **Notifications:** every notification uses its kind's monochrome small icon, accent colour and coloured glass
       large icon from `:platform` (NotificationIcon); a contact photo, when there is one, stays the large icon.
     - **Never crash on platform calls:** declare every permission a call needs (VIBRATE crashed Contacts), keep R8 keep
       rules for anything reached by name, and wait for `Rust.init` before native calls.
- Screenshot names: `<story>-<what>[-dark|-light][-fs2|-rtl]`, for example `e1.9-menu-open-below-dark.png`.

---

## E1. Design system, motion, overlays, text field (`:design`, `:catalog`) - DONE (R5)

Goal: every component the apps need exists, is demonstrated in the catalog app, and is proven by screenshots,
before any app screen is written. Spec: SKILL `references/*` (all), ARCH 4, 9.

Round plans: R1 -> `_bmad-output/kotlin-ui/plans/round-01.md` (planned 2026-11-08; implemented in `6cf6b37`..`c46d5dc`,
verified in code by the round-2 planner). R2 -> `_bmad-output/kotlin-ui/plans/round-02.md` (planned 2026-11-08 at base
`c46d5dc`; also fixes two R1 screenshot findings: content readable through Flat chrome, and both titles visible at
mid-scroll). R2 implemented in `d0f16a5`..`d0dcf08`, verified in code by the round-3 planner (every file and test
named in the E1.9-E1.16 evidence lines exists; 166 goldens). R3 first plan -> `_bmad-output/kotlin-ui/plans/round-03-e1.17-e1.22.md` (planned
2026-11-08 at base `d0dcf08`; E1.17-E1.22 plus R2 screenshot fixes F3 action-sheet shadow haze and F4 stacked sheets
step back; Navigation 3 1.2.1 and Coil 3.6.3 checked usable with BOM 2026.09.00). Only its Step 1 (F3/F4) was
implemented (`6bebfbe`, verified in code: `SheetMath.kt` `stackedTopPx`/`upperDetentOffset`, `SheetGestureTest`
step-back tests, `ActionSheetTest` haze patch). **R3 re-planned** -> `_bmad-output/kotlin-ui/plans/round-03.md`
(planned 2026-11-08 at base `6bebfbe`) after the owner decisions: Rovsti becomes GPL-3.0-or-later and Convx code is
ported close to verbatim; no liquid glass shows on a real phone, so the hand-written glass engine (E1.5) is replaced by
Convx's backdrop stack; Convx's iOS component library replaces our hand-written overlays/controls/chrome where it is
better. New stories E1.23-E1.29 below; E1.17 re-scoped to Convx; E1.18-E1.22 move to R4. R3 implemented in
`542e271`..`d0ea763`, verified in code by the round-4 planner: everything except E1.29's facades (see E1.29).
**R4** -> `_bmad-output/kotlin-ui/plans/round-04.md` (planned 2026-11-08 at base `d0ea763`): screenshot fixes F5 (trigger
residue under open menus), F6 (text over text under the collapsed bar), F7 (page-sheet row inset), orphan goldens; finish
E1.29 by porting Convx's device chrome (`floatingtabbar/**`, `FrostedTopBar`, `ScreenChrome`, `GlassCircleButton`,
`IosOverscroll`) under our facades; then E1.18-E1.22 and close E1.
R4 implemented in `8bd4989`..`86c994f`, verified in code by the round-5 planner: E1.18-E1.21 done; E1.22 only partly
(no `docs/kotlin-ui/screenshots/design.png`, no `e1.22-*` goldens, no checklist record), so E1 is not closed yet.
**R5** -> `_bmad-output/kotlin-ui/plans/round-05.md` (planned 2026-11-08 at base `86c994f`): fix F9 (inline title off
centre: asymmetric title reserve in `FrostedTopBar.kt`, Convx pads 96 dp both sides), finish E1.22, close E1.
**E1 done (R5)**, implemented in `29c7154`..`7851cf2` plus the close commit. **Next planner: start E2 R1 (Rust bridge,
`crates/rovsti_app_core`, `crates/rovsti_ffi`, `:core-bridge`) and E3 R1 (`:platform`, `:wa`, `:shell`; they need
E1.1-E1.8 and E1.18, all done). The two epics are independent and can be planned in parallel.**

| Story | Title | Round | Status |
|---|---|---|---|
| E1.1 | Modules, convention plugins, screenshot harness, catalog shell | R1 | done |
| E1.2 | Theme: colours, type, spacing, shapes, contrast test | R1 | done |
| E1.3 | Motion tokens, spring solver, fling projection, rubber band, reduce motion, MotionGate | R1 | done |
| E1.4 | Press feedback, hold rule, haptics | R1 | done |
| E1.5 | Glass: tier function, capture engine, Flat/Frost/Liquid, AGSL lens and rim, renderer probe | R1 | replaced (no glass on device; superseded by E1.24-E1.26) |
| E1.6 | Buttons, rows, inset-grouped sections, avatars, chip row, badges, empty state | R1 | done |
| E1.7 | Floating tab bar with puck, ChromeMetrics and content insets | R1 | done |
| E1.8 | Large-title scaffold, inline bar, toolbar capsules, back button | R1 | done |
| E1.9 | Overlay host and placement | R2 | done |
| E1.10 | Button-anchored menu (glisse morph, press-slide-release) | R2 | done; rendering replaced by E1.27 (`ButtonMenuEntry` is a facade over `IosContextMenuEntry`; `MenuPanel` and the glyph morph are gone) |
| E1.11 | Long-press context menu (simple and preview-card variants) | R2 | done; rendering replaced by E1.27 (`ContextMenuEntry` / `PreviewCardEntry` are facades over `IosContextMenuEntry` / `AppleContextMenuEntry`) |
| E1.12 | Popover (anchored arbitrary content) | R2 | done (still our `MenuMorph` + `panelGlass` on the Convx material; not replaced by E1.27) |
| E1.13 | Sheets with detents, pushback, background interaction | R2 | done; numbers and page sheet from Convx via E1.28 |
| E1.14 | Alert, action sheet, confirm | R2 | done |
| E1.15 | Toast and undo pill | R2 | done |
| E1.16 | Text field, search field, composer field | R2 | done |
| E1.23 | Relicense to GPL-3.0-or-later, third-party notices, skill/docs licensing rule | R3 | done (542e271) |
| E1.24 | Port Convx backdrop engine (Kyant0 backdrop + Convx pool/freeze/static region) and continuous shapes | R3 | done (R3 step 2) |
| E1.25 | Port Convx `liquidGlass`, AppleTokens/GlassTintTokens, TopFadeBlur, iOS foundation (IosGlass, IosMenuMaterial, IosTheme, IosColors, IosDesignSystem) | R3 | done (476cf5d) |
| E1.26 | One root backdrop, migrate every glass caller, delete the old engine, real-path test, catalog APK | R3 | done |
| E1.27 | Convx menus and context menus (IosMenu, AppleContextMenu, IosContextMenu, OverlayMenu) in `OverlayHost` | R3 | done |
| E1.28 | Convx sheets (IosSheet, IosPageSheet, IosSheetPresentation, BottomSheet) | R3 | done (SheetEntry facade keeps detents, stacking, non-modal; release rule, pushback, corner, grabber from Convx) |
| E1.17 | Switch, slider, segmented control (re-scoped: Convx IosToggle/IosSlider/LiquidBottomTabs puck) | R3 | done |
| E1.29 | Convx tab bar, navigation bar, buttons, list, swipe actions; catalog APK | R3/R4 | done (R3 swipe/IosButton/APK; R4 Step 2 tab bar; R4 Step 3 top chrome, glass circle button, list drawing, overscroll) |
| E1.18 | Navigation 3 scaffold: per-tab stacks, push/pop, predictive back, shared bounds | R4 | done (R4-4; evidence below) |
| E1.19 | Swipe-to-reveal row and alphabet rail (swipe half delivered by E1.29) | R4 | done (R4-5; evidence below) |
| E1.20 | Image loading and the photo viewer | R4 | done (R4 step 6: Coil 3.6.3 loader, slot-sized images, PhotoViewer; ZoomMathTest, PhotoViewerTest, ImageSlotTest, e1.20-* screenshots) |
| E1.21 | Performance harness and convention tests | R4 | done (R4-7; evidence below) |
| E1.22 | Catalog complete, E1 contact sheet, checklist pass | R4/R5 | done (R5; evidence below) |

### E1.1 Modules, convention plugins, screenshot harness, catalog shell
- AC1 `android/build-logic` with convention plugins (`rovsti.android.library`, `rovsti.android.app`, `rovsti.compose`,
  `rovsti.screenshots`); SDK levels minSdk 34 / targetSdk 36 / compileSdk 37 (ARCH 3) in one place.
- AC2 `:design` and `:catalog` (applicationId `dev.rovsti.catalog`, debug only) exist; `settings.gradle.kts` includes them;
  `docs/kotlin-ui/toolchain.md` module names match ARCH 2.
- AC3 Roborazzi output dir is `<module>/screenshots/`; `ShotTest` helper renders light and dark and optional
  `FontScale2`, `Rtl`; a sample shot is recorded and verified.
- AC4 Compose compiler reports and metrics land in `build/compose_reports` through a Gradle property.
- AC6 `ShotTest` pins locale `en-US`, time zone UTC, a fake `Clock`, a seeded `Random`, a fake image fetcher, and sets
  the Roborazzi `changeThreshold` (ARCH 9 "Determinism"); two consecutive `verifyRoborazziDebug` runs pass without
  re-recording.
- AC5 `docs/kotlin-ui/api-notes.md` lists each Compose/Navigation API the skill names, checked against the pinned
  sources (name, artifact, version, file).
- Screenshots: `e1.1-catalog-home-{light,dark}`.
- Evidence (E1.1, commit 6cf6b37): `android/build-logic` (plugins `rovsti.android.library|app`, `rovsti.compose`,
  `rovsti.screenshots`, `RovstiSdk` 37/34/36), `:design` (`RovstiTheme` stub, `RovstiClock`), `:testing` (`ShotTest`,
  `FixedClock`, `StripedBackdrop`, `ShotVariant`), `:catalog` (`CatalogActivity`, `CatalogPage`, `CatalogApp`).
  Opened with Read: `e1.1-catalog-home-light.png` and `-dark.png` (title, subtitle, Pages and Planned sections on the
  page colour; first line sits below the simulated 28 dp status bar, last row ends well above the simulated gesture bar,
  secondary text legible), plus `e1.1-catalog-toolchain-{light,dark,fs2-*,rtl-*,landscape-*}.png` (fs2 wraps and fits,
  RTL mirrors rows and title, landscape caps content at 640 dp and centres it). Two consecutive
  `:catalog:verifyRoborazziDebug` runs pass; `-PcomposeReports` writes `android/design/build/compose_reports`.
  Findings: `enableAccessibilityChecks` is not public in ui-test 1.12.1 (see `docs/kotlin-ui/api-notes.md`);
  changing Robolectric qualifiers recreates the activity, so landscape renders in its own activity inside `ShotTest`
  and must be the last `shot()` of a test.

### E1.2 Theme
- AC1 `RovstiTheme` with light and dark colour sets, follows the system theme; page, grouped background, labels
  (primary, secondary, tertiary), separators, fills, accent, destructive, glass presets.
- AC2 Typography in `sp` on the system font (large title 34 sp bold, title, headline 17 sp semibold, body 17,
  callout, subheadline 15, footnote 13, caption) (SKILL navigation-layout 10-11).
- AC3 `Spacing` tokens (gutter 16, gaps 4/8/12/16/24, text gap 2, section gap 24) and `Sizes` (row min 56/64,
  avatar 40/44, touch 48, button 44).
- AC4 JVM test: every label colour on every background and glass preset is >= 4.5:1 in light and dark (fixes X5, X7 of
  `docs/design/layout.md`).
- Screenshots: `e1.2-palette`, `e1.2-type-scale`, `e1.2-type-scale-fs2`.
- Evidence (E1.2, commit 5905fcb): `android/design/.../theme/` `RovstiColors`, `GlassTokens` (glisse header), `RovstiType`, `Spacing`/`Sizes`/`RovstiShapes`/`concentric`,
  `RovstiTheme` + `RovstiText`, `Contrast.kt`; JVM `ContrastTest`, `ConcentricTest`, `SpacingTokensTest` green; catalog `PalettePage`/`TypeScalePage`
  with `ThemePageTest`. Design decision: glass fills over white/black backdrops cannot give 4.5:1 to labels (dark glass over white is 4.3:1 for pure
  white), so `RovstiColors.glassScrim` (white or black at 40 %) sits under every glass fill; E1.5 must paint it. Accent/destructive/success are 4.5:1 on
  flat surfaces and 3:1 on glass (glyph or bold text). Opened e1.2-palette-{light,dark}, e1.2-type-scale-{light,dark}, e1.2-type-scale-fs2-{light,dark}:
  measured ratios drawn on the swatches are legible (secondary 7.4-12.6:1 on page, tertiary 4.3+ light, glass tiles 3.3-21:1), selected pill reads, fs2 wraps
  to three lines without clipping (page scrolls, lower styles checked by `everyTypeStyleIsListedAtFontScale2`).

### E1.3 Motion
- AC1 `RovstiMotion` holds every spring/tween token of SKILL motion 2.1-2.3, each spring with `visibilityThreshold`;
  a test pins the values.
- AC2 Ported glisse analytic spring solver (header present) with tests: step response vs RK4 per regime; retarget mid-flight
  keeps velocity.
- AC3 `Fling.project(velocity)` (0.998) and `rubberBand(x, dim, 0.55)` pure, JVM-tested.
- AC4 Reduce-motion gate `.gated()` (no squash, parallax, bounce when on) and `MotionGate` (edge-only freeze, SKILL
  performance 5) with tests.
- Screenshots: none (pure); `e1.3-spring-curves` chart drawn by a test canvas to eyeball the curves.
- Evidence (E1.3, commit e43ae0a): `android/design/.../motion/` `RovstiMotion` (glisse header, all tokens of motion.md 2.1-2.3, thresholds required), `SpringState` (ported, header), `Fling`, `Rubber`, `ReduceMotion` (reads `ANIMATOR_DURATION_SCALE` directly, observer-driven; `bounceless` only touches under-damped springs), `MotionGate`. JVM tests: token pin, RK4 at 1 kHz (3 regimes), step splitting, retarget, GlassPressUp overshoot 28 %, Fling 499, Rubber, MotionGate edges. Catalog `MotionPage`; `e1.3-spring-curves-{light,dark,fs2-*}.png` opened: GlassPressUp (red) overshoots once, then dips slightly under, Toggle never overshoots, axes labelled (time ms, progress, target line), legend shows computed ratio/stiffness/overshoot. `scripts/kotlin-ui-check.sh` ALL GREEN.

### E1.4 Press, hold, haptics
- AC1 Glass press node ported from glisse `press.ts` (grow, stretch toward finger, release bounce), scale press 0.96,
  flat row press; all `Modifier.Node`.
- AC2 Hold rule 150 ms (glisse `hold.ts`) as a pure function + node.
- AC3 A Compose test asserts that pressing changes no layout bounds (complaint: "buttons flowed into the content").
- AC4 `RovstiHaptics` (Off/System/Strong) with the event list of SKILL navigation-layout 8.
- Screenshots: `e1.4-press-rest`, `e1.4-press-down-+60ms`, `e1.4-press-release-+120ms`.
- Evidence (E1.4, commit 7499f54): `android/design/.../press/` `GlassPress.kt` (glisse header; pure `pressTargets`, `GlassPressNode` observing Final without consuming, four `Animatable`s, release in `finally` via shared `observePress`, `placeWithLayer`, optional 240 dp glow fading 220 ms), `ScalePress.kt` (0.96, `RovstiMotion.press`), `RowPress.kt` (`fillTertiary` behind content, optional `InteractionSource`, no ripple), `Hold.kt` (glisse header; `HOLD_MS`, `held`, `awaitHoldOrScroll`); `.../haptics/RovstiHaptics.kt` (Off/System/Strong, `HapticEvent`, `PlatformHaptics`, `LocalRovstiHaptics`), `:testing` `RecordingHaptics`, VIBRATE in catalog manifest only. Tests: `PressTargetsTest`, `HoldTest` (149/150 ms), `HapticEventTest`, Robolectric `PressNoLayoutShiftTest` (target and neighbour layout bounds identical before/during/after for glass, scale, row; inner visual does move), `HapticsTest`, `PressPageTest`. Catalog `PressPage`; `e1.4-press-{rest,down-+60ms,release-+120ms}-{light,dark}` and `e1.4-press-scale-row-held-*` opened: pressed button grows and shifts right toward the finger with a faint glow, release frame is slightly below rest size (overshoot), text below never moves; card shrinks, row shows flat grey fill.

### E1.5 Glass
- AC1 `glassTier(GlassConditions)` exactly as SKILL glass 6, table-tested.
- AC2 Capture engine (`glassSource` + glass node) records once, replays translated, builds effects only when size/spec
  change, capture scale tied to blur (SKILL glass 2, 5).
- AC3 AGSL lens and rim ported from glisse with the header and the Aave / kube.io credit; Kotlin CPU twins pass the
  glisse property tests (centre pull 0, rim signs, antisymmetry, relative band, `spectrum(0.4)[0] == 1.08`, rim alpha
  rules) (SKILL glass 7).
- AC4 Probe test records whether Robolectric runs `RuntimeShader`; result written in `api-notes.md`; tests use Flat.
- Screenshots: `e1.5-glass-flat-over-stripes`, `e1.5-glass-frost-over-stripes` (if renderable), `e1.5-rim-cpu-twin`,
  and `e1.5-glass-liquid-over-stripes` only if the probe passed.
- Evidence (E1.5, commit 8fe7a78): `android/design/.../glass/` `GlassTier.kt` (tier fn exactly glass.md 6, `LocalGlassConditions`, `rememberGlassConditions`), `GlassSpec.kt` (Bar/Popover/Sheet/Thumb presets, `captureScale`), `GlassSource.kt` + `GlassNode.kt` (source recording, node re-records only on offset/size/press-scale change, effects keyed on size/spec/corner, saturation as colorFilter, null effect, Flat fill 0.85 + hairline + dropShadow, `frozen` plain field, inverse-press replay), `Lens.kt`/`LensShader.kt`/`Rim.kt` (glisse headers + Aave/kube.io credit; CPU twins, pure `lensUniforms`, `RimBrush`, `rimImage`), `GlassEffect.kt` (blur then lens). `ShotTest` forces Flat. Tests: `GlassTierTest`, `CaptureScaleTest`, `LensPullTest` (incl. spectrum 1.08), `RimTwinTest`, `LensUniformsTest`, Robolectric `RuntimeShaderProbeTest` (result in api-notes.md: blur and AGSL both run), `GlassPageTest`. Catalog `GlassPage` + `RimTwinPanel`; `StripedBackdrop` moved to `:catalog`. Opened `e1.5-glass-{flat,frost,liquid}-over-stripes-{light,dark}` and `e1.5-rim-cpu-twin-{light,dark}`: Flat labels legible, shadow and hairline visible; Frost blurred with saturated tint; Liquid shows lens colour fringing at capsule ends; rim bright on top-left and bottom-right, faint at theme shine 0.13.

- **Replaced (round R3 re-plan, 2026-11-08):** the owner's phone shows no liquid glass from this engine. Found in code:
  overlays compute `sourceAvailable = source != null` and the catalog never gives them a source (`Popover.kt:80-81`,
  `ContextMenuPanel.kt:84-85`, `ButtonMenu.kt:257-270`), so they are always Flat; most catalog pages create no source;
  `ShotTest` forced Flat, so goldens never showed the real path (`e1.7-tabbar-rest-light` opened: opaque white bar). The
  engine is deleted in E1.26 and replaced by the Convx port (E1.24-E1.26).

### E1.6 Rows and small components
- AC1 Buttons: glass icon button (44 dp), capsule, text, destructive, disabled.
- AC2 `ListRow` (one leading slot, title, one secondary line, trailing meta, `heightIn(min)`, merged semantics, custom
  actions), `SectionHeader`, inset-grouped section (radius 20, inset separators hidden after the last row).
- AC3 `Avatar`: photo or initials with colour from a pure function, text from a cached layout in `drawWithCache`; group
  avatar is one composite, never an overlapping stack.
- AC4 `ChipRow`: one non-wrapping `LazyRow`, 8 dp gaps, 16 dp content padding, selected chip filled with >= 4.5:1 text;
  count shown as a separate small label, not glued text.
- AC5 `Badge`, `EmptyState` (glyph, title, one line, optional action), source glyph (14 dp) for SMS/WhatsApp.
- Screenshots: `e1.6-buttons`, `e1.6-rows`, `e1.6-rows-fs2`, `e1.6-grouped-section`, `e1.6-avatars`,
  `e1.6-chip-row-overflowing`, `e1.6-empty-state`.
- Evidence (E1.6, commit f930a74): `android/design/.../controls/` (`Buttons.kt` glisse header, `PressFill.kt`, `ChipRow.kt`, `Badge.kt`, `EmptyState.kt`, `SourceGlyph.kt`), `list/` (`ListRow.kt`, `SectionHeader.kt`, `InsetGroupedSection.kt`, `Avatar.kt`), `icons/` (`RovstiIcons.kt`, `RovstiIcon.kt`); `kotlinx-collections-immutable` 0.4.0 in the catalog and `api` of `:design`. Tests: JVM `AvatarColorTest` (palette >= 4.5:1 with white, stable colour, initials, badge text), Robolectric `ListRowSemanticsTest` (one merged node, custom action, >= 56/64 dp, grows at fs2), `ChipRowTest` (chips share one top, row scrolls, one selected), `GroupedSectionTest` (2 separators for 3 rows, none after the last, inset 72 dp). Catalog pages Buttons, Rows, Grouped sections, Avatars, Chips, Empty states; home and toolchain rebuilt on `ListRow` + `InsetGroupedSection`. Opened `e1.6-*` light/dark and `e1.1-catalog-home-dark`: text column aligned at 72 dp in every row, 16 dp gutters, group avatar is one split circle, chips on one line and the fifth cut by the right edge, selected chip white on accent legible in light, fs2 rows grow (titles ellipsize rather than clip), separators stop before the last row, buttons 44 dp with disabled at 35 %. Known: glass buttons render in the Flat tier in screenshots by design (JVM renderer); on a plain page they read as white discs with a hairline.

### E1.7 Tab bar and insets
- AC1 `RovstiTabBar` geometry and puck ported from glisse (REST/DRAG springs, hold 150 ms, tap slop 24 dp, drag start
  6 dp, squash/stretch), `Role.Tab` + `selected`, labels drop at font scale >= 1.5, minimise on scroll, folded circle
  variant (Stops).
- AC2 `ChromeMetrics` + `LocalContentInsets`; bar bottom margin = system inset + 8 dp; bar hides while the IME is up.
- AC3 Compose test: a 50-row list scrolled to the end has its last row fully above the bar.
- AC4 Puck never exceeds the bar's inner edge while pressed or stretched (fixes X4); bounds test.
- Screenshots: `e1.7-tabbar-rest`, `e1.7-tabbar-drag-mid`, `e1.7-tabbar-minimised`, `e1.7-tabbar-folded`,
  `e1.7-list-end-above-bar`, `e1.7-tabbar-fs2`.

- Evidence (E1.7, commit 4792edf): `android/design/.../chrome/` (`ChromeMetrics.kt`, `TabBarGeometry.kt` and `RovstiTabBar.kt` with glisse headers, `SelectionPuck.kt`, `TabBarMinimizer.kt`, `BottomScrim.kt`); `press/Hold.kt` gained `awaitHold` (also returns the release position). Tests: JVM `TabBarGeometryTest` (puck inside inner rect for lift 0..1, squash 0..0.2, positions past both ends), `TabBarMinimizerTest`, `ChromeMetricsTest`; Robolectric `ListEndAboveBarTest` (row-49 bottom <= bar top; bar 8 dp above the nav inset), `TabBarGestureTest` (tap, reselect, 1.6-slot drag lands on slot 2, release >= 24 dp off ignored, vertical move before 150 ms scrolls the parent, haptics), `TabBarSemanticsTest`, `PuckBoundsTest` (real layout bounds while flicked), `ImeHidesBarTest`. Catalog pages Tab bar and Tab bar: folded. Opened `e1.7-*` light/dark: puck inside the bar and centred in rest, mid-drag puck between two tabs inside the bar, last row fully above the bar, fs2 icons only, folded circle bottom-start. Known: glass renders Flat in screenshots (lens not visible, lifted puck is a raised fill); minimised bar translates 40 dp so it is partly cut by the screen edge (spec look); content inset keeps the expanded bar height while minimised (no relayout during scroll); the bar is a sibling above the list, so a vertical swipe on it does not scroll the list (a parent scroll container still gets it).

### E1.8 Large-title scaffold
- AC1 `LargeTitleScaffold`: title as item 0, collapse fraction pure and tested, inline bar fades in, large title lifts
  12 dp, progressive top scrim; reduce-transparency variant with solid bar and divider.
- AC2 Toolbar capsules (44 dp glass buttons grouped; a spacer splits capsules) sharing the title baseline (fixes X6).
- AC3 Back button for pushed pages; header actions limited to two plus an overflow menu.
- AC4 Width cap 640 dp centred on wide screens (ARCH 9 "Adaptive layout"); landscape keeps the bars usable.
- Screenshots: `e1.8-title-landscape`, `e1.8-title-w840`, `e1.8-title-expanded`, `e1.8-title-collapsed`, `e1.8-title-mid-scroll`, `e1.8-title-fs2`.
- Round-2 re-judgement (planner, opened `e1.8-title-expanded-light`, `-collapsed-dark`, `-mid-scroll-light`,
  `e1.7-tabbar-rest-light`, `e1.7-tabbar-drag-mid-dark`): rows readable through the Flat tab bar and the collapsed top
  band; large and inline titles both half visible at mid-scroll. Fixed in R2 step 1 (F1, F2); stories stay `done`.
- Round-3 re-judgement (planner, opened `e1.13-sheet-medium-light`, `e1.13-sheet-pushback-dark`,
  `e1.13-sheet-nonmodal-over-stripes-light`, `e1.14-action-sheet-light`, `e1.16-field-with-selection-light`,
  `e1.16-composer-ime-open-dark`): the Cancel group's shadow veils the last action row (F3); the stacked-sheet shot shows
  only the top sheet, the lower one fully hidden at the same detent (F4). Fixed in R3 step 1; E1.13/E1.14 stay `done`.

- Evidence (E1.8, commit 7959075, review fixes c46d5dc): `android/design/.../chrome/` (`TitleCollapse.kt`, `LargeTitleScaffold.kt`, `Toolbar.kt`, `BackButton.kt`; `controls/Buttons.kt` `glassSurface` made internal). Tests: JVM `TitleCollapseTest` (fraction, clamps, NaN/zero height, top scrim stops); Robolectric `ScaffoldTest` (collapse fraction read through `onCollapseChanged`, which is the inline alpha by construction: strictly between 0 and 1 at half scroll, 1 when collapsed; third action goes to the overflow with its anchor rect; capsule height 44 dp and centre within 4 dp of the title's first line via `TextLayoutResult`; `topBarPx` = 80 dp; title is item 0 key "title"; back button pops the sub-page), `ScaffoldWideTest` (list and bar <= 640 dp and centred at w840). Catalog page Large title (40 rows, 2 actions + overflow, pushed sub-page; `TODO(E1.10)` for the menu). Opened `e1.8-title-expanded/collapsed/mid-scroll/fs2/landscape/w840/pushed(-rtl)/reduce-transparency` light/dark: capsules level with the title line and 44 dp; collapsed inline title centred with rows fading under a firm scrim (first pass at 0.42 left row text legible beside the title, so the scrim over the bar firms up to 0.94 as the title collapses); mid-scroll shows both titles partly; fs2 title clear of capsules (capsules stay in the 52 dp band, so at fs2 they sit above the first line centre); w840 centred 640 dp column (root now paints the page colour full width, the first landscape shot showed the sides unpainted); RTL pushed page has the mirrored chevron on the leading (right) edge; reduce transparency shows a solid bar with a 0.5 dp divider. Known: Flat glass in screenshots; no simulated display cutout in the harness (cutout handled by `safeDrawing` insets, not visually verified); inline title is hidden from accessibility (the large title carries the heading) so it is unannounced once item 0 is scrolled off; the pushed page's title sits below the button row (iOS style), so capsules only align with the title line on root pages.
- F9 (R5, `29c7154`): the collapsed inline title was off centre because the title reserve was asymmetric; `chrome/FrostedTopBar.kt` now reserves the same width on both sides (Convx pads 96 dp both sides), so the title is centred in the bar.

### E1.9 Overlay host and placement
- AC1 `OverlayHost`, `OverlayController`, `OverlayEntry` as SKILL overlays 1; back closes only the top entry; exit
  animations play for programmatic dismiss; modal entries clear the page from semantics and set `paneTitle`.
- AC2 `placePanel()` pure with JVM tests: each corner and centre, too tall, RTL, landscape, cutout, IME open.
- AC3 Test: trigger bounds identical before and after `present()`.
- Screenshots: `e1.9-host-two-entries-stacked`.
- Evidence (E1.9 plus R1 fixes F1, F2, R2 step 1): `android/design/.../overlay/` (`OverlayHost.kt`, `Scrim.kt`, `Placement.kt`, all original); `GlassSpec.flatAlpha` (bar 0.96, popover 0.97, sheet 0.98, thumb 0.85); `chrome/TitleCollapse.kt` `inlineTitleAlpha`/`largeTitleAlpha`; Flat top band goes to 1.0 once collapsed. Tests: JVM `PlacePanelTest` (corners, centre, too tall, RTL, landscape, cutout, IME, origin), `TitleAlphaTest` (never both titles), `FlatAlphaTest`; Robolectric `OverlayHostTest` (back pops only the top entry, exit plays before removal, page semantics cleared and restored, `paneTitle`, scrim blocks the list), `TriggerBoundsTest` (identical bounds before, +16 ms, +200 ms, after dismiss), `FlatLegibilityTest` (luminance spread < 0.05 over a black/white stripe backdrop for the tab bar and the collapsed top band), `ScaffoldTest` probes f = 0.8. Opened `e1.9-host-two-entries-stacked-{light,dark}` (card 2 above card 1, page dimmed, both cards legible), `e1.7-tabbar-rest`, `e1.7-list-end-above-bar`, `e1.8-title-collapsed` (no readable row text under the chrome; a faint ghost remains only inside the translucent puck), `e1.8-title-mid-scroll` (one title, the large one half faded). Known: with the manual test clock a dismissal needs one small advance before a large one to start the spring; the page behind is not inert for touch except through the scrim.

### E1.10 Button-anchored menu
- AC1 Menu morph ported from glisse `menu.tsx` (header), per-axis growth from the anchor, clearing blur on open, short
  plain exit; card press +5 dp; drag resistance 7 dp.
- AC2 Press-slide-release from the trigger and on the open menu commits the row under the finger on lift; haptic tick
  on row change; rows with glyph, destructive row, section separators, checkmark row.
- AC3 `menuMorphFrame` invariants tested (t=0 surface = trigger rect; t=1 settled; clip inside card).
- Screenshots: `e1.10-menu-closed`, `e1.10-menu-+40ms`, `e1.10-menu-+100ms`, `e1.10-menu-open-below`,
  `e1.10-menu-open-above`, `e1.10-menu-clamped-edge`, `e1.10-menu-row-pressed`.
- Evidence (E1.10 part 2): `android/design/.../overlay/MenuPanel.kt` (glisse header; plate and card press from `menu.tsx:128-262`, `styles.css:985-1070`), `ButtonMenu.kt` (`menuTrigger` feeds the finger), `OverlayHost.kt` (`OverlayController.beginSlide(entry, Flow<Offset>)`, `SlideTarget`; no global pointer state). One non-consuming Final-pass observer on the card; row rects measured during layout into one `FloatArray` (`NaN` for separators and disabled rows); `hoveredRow(rects, y)` is a pure hit-test. Plate: `menuPlate()` 100 ms, first highlight in place, 80 ms fade out; card follows the finger with `menuResistance`, press `MenuCardPress`, release `MenuCardRelease`, 120 ms sheen. Row change gives `HapticEvent.TextHandle`, destructive commit `Confirm`. Keys: arrows wrap and skip disabled rows, Home/End, Enter commits, Escape/Tab close, first enabled row has logical focus. Semantics: Role.Button, checkmark rows Role.Checkbox + selected, disabled rows `disabled()`, rows 48 dp (`RowMinHeight` 44 -> 48). A slide that stays within 8 dp is a tap: the menu stays open and nothing commits; releasing outside the card commits nothing and leaves it open (glisse). Tests: JVM `MenuHitTest`; Robolectric `MenuSlideTest` (slide to row 3 runs it once and closes, 3 crossings give 3 ticks, release outside, tap, destructive Confirm, tap on a row, semantics click, keyboard wrap/Enter/Escape, roles and 48 dp). Opened `e1.10-menu-row-pressed-{light,dark}` (plate on the second row with rounded 14 dp corners, readable in both themes, disabled row and separator not lit, card sheen faint) and `e1.10-menu-open-below-rtl-{light,dark}` (mirrored, grows from the leading corner, rows right-aligned, check on the leading side); the other e1.10 shots were re-recorded for the 48 dp rows. Known: Compose reports a cancelled touch as a lift, so a cancel after sliding onto a row commits it (a detached trigger still cancels); the demo menu mixes rows with and without icons so labels do not align.

### E1.11 Long-press context menu
- AC1 Gesture resolves four outcomes (tap, long-press open, scroll cancel, child click wins) (SKILL overlays 3.1).
- AC2 Simple variant: lifted copy 1.05 + panel, scrim .28/.14; preview-card variant: card min(.78 W, 340), radius 22,
  drag to dismiss (64 dp or 40 %), return spring.
- AC3 TalkBack equivalent: `onLongClick` semantics and custom actions.
- Screenshots: `e1.11-context-row-open`, `e1.11-context-row-open-near-bottom` (panel above),
  `e1.11-context-preview-card`, `e1.11-context-preview-dragged`, `e1.11-context-open-rtl`.

- Evidence (E1.11): `android/design/.../overlay/` `ContextMenuGesture.kt` (`PressRamp` 300 ms in / spring 0.7-600 out, `Modifier.contextMenu` resolving Tap/Cancel/ChildTook/Long: Main pass before the timeout, Initial pass with consume after open, `onLongClick` + `customActions`), `ContextMenuEntry.kt` (`ContextMenuTarget`, `longPressMenu`, lifted dedicated preview 1.05, scrim .28/.14, panel spring 0.78/700, per-axis scale min 0.08, 20 dp clearing blur on open only, `LongPress` haptic), `ContextMenuPanel.kt` (rows reuse `MenuRows`), `PreviewCardLayout.kt` (pure), `PreviewCardEntry.kt` (menuLift/menuLand, 30 % cross-fade, radius 22, menuGrowX/Y, rubber 0.6/0.3, dismiss 64 dp or 40 %, `previewReturn`). All ORIGINAL, no glisse header. `captureAnchor` now records the unclipped window rect (a half-visible row first lifted as a clipped strip). Tests: JVM `PreviewCardLayoutTest`; Robolectric `ContextMenuGestureTest` (tap, 500 ms hold + LongPress haptic, 20 dp move scrolls, "..." click and hold win, OnLongClick, custom actions, bounds unchanged, panel row runs, 80 dp drag dismisses, short drag returns). Opened `e1.11-context-row-open`, `-row-open-near-bottom` (panel above, row exactly in its slot), `-preview-card(-left)`, `-preview-dragged` (card follows, panel fading), `-open-rtl` (mirrored) light/dark. Known: the finger that opened the menu cannot slide onto panel rows (touch target is fixed at down); the preview card's scrim also dims the tile it grew from.

### E1.12 Popover
- AC1 Anchored popover with arbitrary content (for example a date chooser or a small form), arrowless, placed by
  `placePanel`, scrolls when taller than the free space.
- AC2 Opening never moves the content under it (bounds test).
- Screenshots: `e1.12-popover-below`, `e1.12-popover-above`, `e1.12-popover-tall-scrolling`.

- Evidence (E1.12): `android/design/.../overlay/Popover.kt` (glisse header; `PopoverEntry` reuses `menuMorphFrame` without the glyph, max 320 dp, `placePanel`, height capped by `panelMaxHeight` with `verticalScroll`, transparent input-eating scrim; `Modifier.popoverTrigger`). Catalog `PopoverPage`. Tests: Robolectric `PopoverTest` (three page nodes keep bounds at +16/+100/+300/+1200 ms and after close, 320 dp cap, tall content `performScrollToNode` to the last row (needs `autoAdvance = true` while it runs: the scroll action animates), above when anchored low, scrim tap). Opened `e1.12-popover-below/-above/-tall-scrolling` light/dark: the glass grows out of its button and covers it (menu style), form rows readable, tall one fills the safe area and clips its 17th row mid-scroll.
- Evidence (E1.14): `Alert.kt` (270 dp, min 110, row or column by `maxIntrinsicWidth`, 1.2 -> 1 + fade on `appear`, 150 ms exit, scrim .2/.4, no outside dismiss, `OverlayEntry.onBack` hook so Back runs the cancel action; `dismissOnBack` only with a cancel), `ActionSheet.kt` (max 500 dp, 56 dp rows, separate cancel group, nav inset + 8 dp, 400 ms (0.2833,0.99,0.31833,0.99) in / 150 ms EaseIn out, `confirmDestructive` + two-tap: 'Tap again' 3 s, `Confirm` on commit, `Reject` on timeout, live-region state). Tests: `AlertTest`, `ActionSheetTest`. Opened `e1.14-alert`, `-alert-fs2`, `-action-sheet`, `-confirm-destructive` light/dark. Known: the lower group's drop shadow slightly veils the last row of the upper group in the action sheet.

### E1.13 Sheets
- AC1 Detents (peek, medium .5 H, large H - (status + 24)), settle .86/380 with release velocity, dismiss rule (+12 % H or
  projected), grabber 36x5, nested scroll hand-off (SKILL overlays 5).
- AC2 Pushback of the page behind (.91, 32 dp, dim .22) for modal sheets; a non-modal mode with background interaction
  at small detents (for Stops), no pushback.
- AC3 Back on a sheet: predictive preview 35 % then dismiss.
- Screenshots: `e1.13-sheet-peek`, `e1.13-sheet-medium`, `e1.13-sheet-large`, `e1.13-sheet-pushback`,
  `e1.13-sheet-nonmodal-over-stripes`.
- Evidence (E1.13, commit 6bc19e9): `android/design/.../overlay/` `SheetEntry.kt`, `SheetState.kt` (detent physics, nested-scroll hand-off), `SheetMath.kt` (pure detents, dismiss rule), `PushBack.kt` (page scale .91, 32 dp corner), `OverlayHost.kt` (pushback registry, `handlesBack`). Catalog `SheetsPage`. Tests: JVM `SheetMathTest`; Robolectric `SheetGestureTest` (detent swipes, fling dismiss/expand, list-then-sheet hand-off, stacked sheets keep the page back, pushback .91, predictive back preview then cancel/complete, only the top sheet closes, non-modal passes taps and has no scrim, scrim tap, one tick per new detent, focus cleared on close, grab while settling). Shots `e1.13-sheet-peek/-medium/-large/-pushback/-nonmodal-over-stripes` light/dark recorded in that commit.

### E1.14 Alert, action sheet, confirm
- AC1 Alert 270 dp, 1.2 -> 1 + fade, out 150 ms; action sheet up to 500 dp, 56 dp rows, cancel separate; destructive
  confirm with a two-tap variant.
- Screenshots: `e1.14-alert`, `e1.14-alert-fs2`, `e1.14-action-sheet`, `e1.14-confirm-destructive`.

### E1.15 Toast and undo pill
- AC1 Capsule above the measured tab bar, 3 s, swipe to dismiss, live region; undo pill with action and countdown;
  one at a time with replace semantics.
- Screenshots: `e1.15-toast-above-bar`, `e1.15-undo-pill`.
- Evidence (E1.15): `android/design/.../overlay/Toast.kt` (ORIGINAL; `ToastEntry`, `OverlayController.showToast/showUndo` with replace semantics via `OverlayController.toast`), `ToastMath.kt` (pure: swipe outcome by `Fling` projection, bar gap, ring sweep, upward rubber). Capsule `labelPrimary` 0.92 with page-coloured subheadline, max 2 lines, padding 18/12, radius 24, max 420 dp, 350 ms rise / 180 ms exit, liveRegion Polite + `dismiss` action, not modal (`OverlayEntry.passesBack`: Back is never taken by a toast). Placement reads `OverlayController.chromeMetrics` (registered by `RovstiTabBar`, since entries are composed outside the bar's locals) in the layout phase: bar top - 8 dp, or nav inset + 8 dp without a bar. Timer runs on the frame clock (animator scale cannot cut it short) and holds under a finger; ring is a draw-phase arc; Undo runs once and is dead once the pill is leaving; the action takes the opposite theme's accent (the capsule is an inverted surface; contrast >= 4.5 in a JVM test). Catalog `ToastsPage` with the tab bar. Tests: JVM `ToastMathTest`; Robolectric `ToastTest` (3 s auto-dismiss, replace + fresh timer, swipe down/sideways, short drag springs back, timer holds under finger, undo once, double tap same frame, dead after 5 s while fading, dead once replaced, bottom <= bar top - 8 dp and within 2 px, long toast 2 lines, no-bar placement, live region + page still takes input, Back not swallowed). Opened `e1.15-toast-above-bar`, `-undo-pill`, `-toast-long-fs2` light/dark: dark capsule with 8 dp clear of the bar and its shadow over the bar's top edge only; dark theme gives a light capsule with dark text; the Undo label and ring read clearly in both; ring about 60 % full at 2 s of 5; at font scale 2 the long text clips to two lines with an ellipsis and the capsule spans the gutters above the bar. Known: the toast does not animate when the bar minimises (it follows instantly); a 3 s toast is short for TalkBack users (no extended timeout yet).

### E1.16 Text fields
- AC1 `GlassTextField` and `SearchField` ported from glisse `input.tsx` / `search-field.tsx` (header), over
  `BasicTextField(TextFieldState)`; clear button keeps focus; tapping the pill focuses.
- AC2 `ComposerField`: multiline up to 6 lines, docks with `imePadding()`, send action, hardware Enter handling.
- AC3 Compose tests: tap places the caret at the tapped offset; long-press selects a word; `performTextInputSelection`;
  paste; IME action submits; field stays above the simulated keyboard; font scale 2 does not clip.
- AC4 Selection colours and cursor brush from the accent.
- Screenshots: `e1.16-field-empty`, `e1.16-field-focused`, `e1.16-field-with-selection` (selection highlight and both
  handles visible), `e1.16-field-selection-toolbar` (platform toolbar if Robolectric draws it; otherwise the test asserts
  the toolbar was requested and the shot shows the selection), `e1.16-search-with-clear`, `e1.16-composer-3-lines`,
  `e1.16-composer-ime-open`.
- Evidence (E1.16, commit 1898350): `android/design/.../text/` `GlassTextField.kt`, `SearchField.kt` (glisse headers), `ComposerField.kt` (up to 6 lines, send, hardware Enter / Shift+Enter), `TextSelection.kt`; all over `BasicTextField(TextFieldState)`. Catalog `TextFieldsPage`. Tests: JVM `TextSelectionTest`; Robolectric `TextFieldsTest` (tap places caret, long-press selects word, selection via semantics, paste through toolbar and semantics, IME action once, clear keeps focus, composer above keyboard, font scale 2, 6-line growth, send once, blank sends nothing, platform toolbar asked on long-press). Shots `e1.16-field-empty/-focused/-focused-fs2/-with-selection/-selection-toolbar`, `-search-with-clear`, `-composer-3-lines`, `-composer-ime-open` light/dark recorded in that commit.

### E1.17 Switch, slider, segmented
- Re-scoped (R3 re-plan, Step 7): port Convx `IosToggle`, `IosSlider` (on `LiquidToggle`/`LiquidSlider`,
  `DampedDragAnimation`, `InteractiveHighlight`) and a segmented control on the `LiquidBottomTabs` puck; glisse rules
  below apply only where Convx lacks them (the untracked glisse `SwitchRules.kt` WIP was moved out of the tree).
- AC1 Ported glisse geometry and gesture rules (deadzones, rubber band /22, near = 24, drag = 6, round to slot), toggle
  spring 1.265/1000, thumb lift; haptic on commit.
- AC2 Gesture tests from glisse tests (tap, drag off-bar selects on release, >24 px off does nothing, hold blocks scroll,
  cancel cancels).
- Screenshots: `e1.17-switch-off-on`, `e1.17-switch-pressed`, `e1.17-slider`, `e1.17-segmented-3`, `e1.17-segmented-drag`.
- Evidence (R3 Step 7): `ios/component/IosToggle.kt`, `IosSlider.kt`, `IosSwitchRules.kt` (glisse 4 dp / 24 dp rules; Convx lacks them), `controls/SegmentedControl.kt` (LiquidBottomTabs puck), `list/ListRow.kt` `ListRowSwitch`. Catalog `ControlsPage`. Tests: JVM `IosControlsRulesTest`; Robolectric `ToggleTest`, `SliderTest`, `SegmentedTest` (tap, drag, scroll does not act, cancel, semantics, 48 dp, no layout shift, real-path thumb records), shots `E117ShotTest`. Seen: held thumb/pill is lens glass bending stripes, selected label readable over the resting pill, nothing clipped.

### E1.23 Relicense (plan round-03 Step 1)
- AC1 `Cargo.toml` workspace `license = "GPL-3.0-or-later"` (every crate inherits); root `LICENSE` = Convx's GPL-3.0 text
  byte for byte; README licence section.
- AC2 `android/THIRD_PARTY_NOTICES.md` lists Convx, Kyant0 backdrop / AndroidLiquidGlass / Capsule, compose-miuix-ui,
  compose-hig, Haze (if used), glisse, with licences, copyright lines, full MIT and Apache-2.0 texts.
- AC3 Skill section 0 and references, `CLAUDE.md` (one line), `docs/kotlin-ui/architecture.md` section 0 say Convx code is
  portable with the attribution header; `LicenceHeaderTest` enforces headers.

### E1.24 Backdrop engine (Step 2)
- AC1 All 42 files of Convx `ui/component/backdrop/**` and `ui/component/shapes/**` ported close to verbatim under
  `dev.rovsti.design.backdrop` / `.shapes`, upstream headers kept + "Ported from Convx" line; `AppBackdropState`.
- AC2 JVM freeze/static-region tests; Robolectric `BackdropEngineTest` proves blur and lens run.
- Screenshots: `e1.24-backdrop-engine`.

### E1.25 Glass layer, tokens, iOS foundation (Step 3)
- AC1 `liquidGlass`, `GlassEffectConfig`, `glassResolutionScale`, `isGlassAllowed`, `TopFadeBlur`, `AppleTokens`,
  `GlassTintTokens`, `IosGlass`, `IosMenuMaterial`, `IosTheme`, `IosColors`, `IosDesignSystem` ported; IosColors fed from
  `RovstiTheme`; `GlassSettings` replaces Convx preferences.
- AC2 Convx tests `GlassEffectTest`, `GlassTintTokensTest`, `AppleColorsTest` ported and green.
- Screenshots: `e1.25-ios-glass-materials`.

### E1.26 Root backdrop, migration, real-path proof (Step 4)
- AC1 `RovstiBackdropRoot`: content records into one `LayerBackdrop`; chrome and `OverlayHost` are siblings after it and
  sample it (as Convx's chrome and popups do).
- AC2 Every former `Modifier.glass` caller migrated; `D/glass` old engine and its tests deleted; no test forces Flat.
- AC3 `GlassRealPathTest` (API 33 and 36, default settings): every glass surface on every catalog page and overlay draws
  through a `LayerBackdrop` with a render effect. Fallback only for reduce transparency / pre-31 / low RAM, as Convx.
- AC4 Catalog APK rebuilt; path in the evidence.
- Screenshots: all goldens re-recorded; `e1.26-liquid-glass-over-stripes`, `e1.26-overlays-over-stripes`.
- Evidence: `RovstiBackdropRoot` + `BackdropScope` (`chrome/RovstiRoot.kt`; a layer cannot sample itself, so chrome inside a page
  uses a local scope, content-level controls paint a flat fill as Convx's `forceFlat`); `OverlayHost` split into
  `OverlayProvider`/`OverlayPage`/`OverlayEntries`; `rovstiGlass`/`panelGlass`/`sheetGlass` over `liquidGlass`/`iosMenuSurface`/`iosGlass`;
  `BackdropDebug` records; `GlassRealPathTest` (API 35 only: minSdk is 34, the 34 jar was rate-limited, API 36 fails on this JRE),
  `GlassFallbackTest`, `NoFlatInTestsTest`, `TranslucentFallbackLegibilityTest`. Toast is now glass; sheets have no lens (seam at the screen edge).

### E1.27 Menus and context menus (Step 5)
- AC1 Convx `IosMenu`, `AppleContextMenu`, `IosContextMenu`, `OverlayMenu` ported; `Popup` replaced by `OverlayEntry`;
  our `ButtonMenu`/`contextMenu` APIs are facades; menu tests green (numbers updated to Convx with citations).
- Screenshots: `e1.27-menu-open`, `e1.27-context-menu-preview`, `e1.27-context-menu-submenu` (the inline picker section with
  header and check column plus quick actions: Convx has no nested drill-down menu), `e1.27-context-menu-row`.
- Evidence: `ios/component/{IosMenu,AppleContextMenu,IosContextMenu,OverlayMenu,IosText,IosGlyph,IosButtonRole,MenuActionColumns}.kt`;
  `Popup` became `OverlayEntry`s (`IosContextMenuEntry`, `AppleContextMenuEntry`, `OverlayMenuEntry`); `MenuModel.toSections()`
  maps the facade items; Convx numbers now rule (panel 238 dp, rows 44 dp, 8 dp gap, 12 dp margin, springs 0.78/700 etc.);
  `MenuMorph` stays because `Popover` still uses it. Removed: `MenuPanel`, `ContextMenuPanel`, `PreviewCardEntry` class,
  `PreviewCardLayout` (+ tests). Added `IosMenuTest`, `MenuActionColumnCountTest`, `E127ShotTest`.

### E1.28 Sheets (Step 6)
- AC1 Convx `IosSheet`, `IosPageSheet`, `IosSheetPresentation`, `BottomSheet` (no player code) ported; detents,
  non-modal, predictive back, pushback and stacked step-back kept.
- Screenshots: `e1.13-sheet-*` re-recorded, `e1.28-page-sheet`.

### E1.29 Tab bar, navigation bar, buttons, list, swipe (Step 8)
- AC1 Convx `IosTabBar` (+ `LiquidBottomTabs` puck), `IosNavigationBar`, `IosButton`, `IosList`, `IosSwipeActions`
  ported; `RovstiTabBar`, `LargeTitleScaffold`, `Toolbar`, `Buttons`, `ListRow` are facades; inset, IME and
  list-end tests green.
- AC2 Catalog APK rebuilt at round close.
- Screenshots: `e1.7-tabbar-*`, `e1.8-*`, `e1.6-buttons*`, `e1.29-swipe-revealed`, `e1.29-swipe-full`.
- Evidence (R3 step 8, uncommitted at time of writing): ported to `android/design/.../ios/component/` IosTabBar (+IosBadge,
  rememberIosTabBarState), IosNavigationBar (+IosToolbarGroup/Spacer, collapse), IosButton, IosList (+IosNavigationRow,
  IosChevron), IosSwipeActions with Convx headers. Changes: swipe velocity tracks summed drag (Convx read the node-local
  position, which moves with the layer, giving ~0 velocity); action buttons fill row height (IntrinsicSize.Min); row clipped
  to its bounds; one `confirm` haptic on full swipe; `CustomAccessibilityAction` per action. `ListRowChevron` now draws
  `IosChevron`. `SwipePage` in the catalog shows swipe rows and the six `IosButtonStyle`s. `SwipeRowTest` (5 tests: reveal,
  full swipe fires first trailing action once with haptic, small drag settles back, accessibility actions) is green.
  NOT done: `RovstiTabBar`, `LargeTitleScaffold`, `Toolbar` and `Buttons.kt` were not turned into facades; they keep their
  existing Convx-glass drawing (glass capsule plus lens puck, already on `liquidGlass`) and their tests stay green. The
  IosTabBar/IosNavigationBar/IosButton ports are compiled but only IosButton and the swipe row are exercised in the catalog.
  Opened: e1.7-tabbar-rest-light and drag-mid-light (glass capsule, lens puck, bar covers rows only by scroll overlap, list-end
  test keeps the last row above it), e1.6-rows-light (new chevron), e1.29-swipe-revealed-{light,dark} (Archive/Delete
  76 dp buttons full row height, row clipped to the card), e1.29-swipe-full-light (row dragged mostly off).
  Catalog APK: android/catalog/build/outputs/apk/debug/catalog-debug.apk (34.8 MB).
- Verified by the round-4 planner (grep at `d0ea763`): `IosTabBar`, `IosNavigationBar`, `IosList` have no caller; `IosButton`
  only in `SwipePage`. Convx's on-device chrome is `ui/component/floatingtabbar/**` (vendored compose-floating-tab-bar,
  Apache-2.0, + Convx puck/fold) and `FrostedTopBar`/`ScreenChrome`/`GlassCircleButton`, not the `ios/component` catalog
  versions, so R4 Step 2 ports the floating tab bar under `RovstiTabBar` and Step 3 the top chrome, buttons and list drawing
  under `LargeTitleScaffold`/`Toolbar`/`Buttons`/`ListRow`. Screens judged: `e1.7-tabbar-rest-light` (row text doubled through
  the capsule: blur too weak), `e1.8-title-collapsed-dark` (inline title over legible rows: F6), `e1.28-page-sheet-light`
  (row at a second inset: F7), `e1.27-menu-open-light` + `e1.26-overlays-over-stripes-light` (stray square at the hidden
  trigger: F5). Status stays in-progress until R4 Step 3.
- **R4 Step 3 (done, closes E1.29)**: ported close to verbatim into `android/design/.../chrome/`: `FrostedTopBar` (Convx
  `ui/component/FrostedTopBar.kt`), `ScreenChrome` (+`GlassScreenChrome`, `ScreenScaffold`, `ScreenLazyPage`, `ScreenPage`,
  `ScreenLargeTitle`, `rememberTitleCollapseProgressState`; `ScreenChrome.kt`, `GlassScreenChrome.kt`, `ScreenTitleBar.kt`),
  `NavigationTitle`, `ChromeScrim`; `controls/GlassCircleButton.kt` (+`ContrastCircleButton`, `chromeShadow`, elastic stretch from
  `ios/foundation/ConvxUtils.kt`); `motion/IosOverscroll.kt` (rubber band 0.55, `ui/utils/IosOverscroll.kt:47`). Material3, Coil
  (account button) and the ambient colour were removed; the refresh line is drawn with a Canvas.
  Facades now calling them (grep): `LargeTitleScaffold` -> `ScreenLazyPage` + `ScreenLargeTitle` + `FrostedTopBar` + `TopFadeBlur`
  behind the inline bar, with `LocalOverscrollFactory` = `rememberIosOverscrollFactory()`; `Toolbar` capsules ->
  `IosToolbarGroup` (now 44 dp glass in a 48 dp touch box, `debugTag`); `BackButton`/`GlassIconButton` -> `GlassCircleButton`
  (44 dp circle, 48 dp touch target); `CapsuleButton`/`TextButton` -> `IosButton` (Glass, BorderedProminent, Borderless; flat
  Bordered when the button sits inside recorded content); `InsetGroupedSection` -> `IosCardSection` (new, in `IosList.kt`) which
  draws through `IosListRowContainer` (card radius 26 dp and 0.33 dp separators are Convx numbers; `GroupedSectionTest` updated);
  `ListRowChevron` is `IosChevron`. Kept Rovsti's own `TitleCollapse` maths (spring smoothing and the cross-fade that guarantees
  never two titles); Convx's collapse is linear and lets the inline title show over the large one. Additions beyond Convx:
  blur and a page-colour wash (peak 0.8) over the bar band follow the collapse, since Convx blurs always, which blurred the
  expanded large title; asymmetric title reserves centre the inline title between the back button and the toolbar.
  Deleted: `topScrimStops`/TopScrim/SolidBar and their tests. F6 fixed, F7 fixed (page-sheet row in an `InsetGroupedSection`).
  Tests: `CollapsedBarLegibilityTest` (variance under the bar < 0.4 of an unblurred row; title contrast >= 4.5:1, light and dark),
  `E129ShotTest`; ScaffoldTest, ScaffoldWideTest, GroupedSectionTest, ListRowSemanticsTest, PressNoLayoutShiftTest and
  GlassRealPathTest green.
  Opened: e1.8-title-expanded-light (crisp large title, toolbar capsules), e1.8-title-collapsed-light (rows under the bar blurred
  and faded, inline title centred and legible), e1.8-title-mid-scroll-dark, e1.28-page-sheet-light (row in a card at the paragraph's
  inset), e1.29-overscroll-top-light (see the check list below).
- F9 (R5, `29c7154`): collapsed inline title centred (symmetric reserve in `chrome/FrostedTopBar.kt`, as Convx); see E1.8.

### E1.18 Navigation scaffold
- AC1 Navigation 3 checked at the pinned version (or the documented fallback, ARCH 5.1); `TabNavigator` with one stack
  per tab, pure and tested (reselect scrolls to top through an event, back pops the tab stack then exits).
- AC2 Push/pop tweens with depth parallax and dim, tab crossfade ~200 ms, predictive back scrubs the pop.
- AC3 Shared-bounds helper (avatar -> detail) (SKILL motion 8).
- AC4 `chromeFor(route)` pattern documented in `:shell` usage notes.
- Screenshots: `e1.18-push-mid`, `e1.18-predictive-back-50pct`, `e1.18-shared-avatar-mid`, `e1.18-tab-switch-mid`.
- Evidence (R4-4): `design/transition/{TabNavigator,NavTransitions,RovstiNavHost,SharedBounds,ChromeSpec,ArtworkMotion}.kt`;
  `NavTransitions` ports Convx `AppNavHost.kt:55-98` and `AppRootContent.kt:990` (one deviation: pop-enter parallax sign, see
  `api-notes.md`); `rememberNavTransitionFreeze` is wired through `RovstiBackdropRoot(route, lastNavWasPop)`; catalog has the
  Components/Demo tabs with avatar -> detail -> notes. Tests: `TabNavigatorTest`, `ChromeSpecTest`, `NavTest` (11), `NavFreezeTest` (3),
  `CatalogNavigationTest`. Docs: `docs/kotlin-ui/shell-usage.md`, `api-notes.md`. PNGs opened: push-mid shows the detail 8 % from the left edge over
  the list shifted 0.3 W left, no blank band, tab bar kept (detail is Visible); predictive-back-50pct shows the detail almost gone (the iOS curve
  puts 50 % gesture at ~90 % of the distance) and the avatar in return flight; shared-avatar-mid shows the avatar unclipped over both pages;
  tab-switch-mid shows both tab pages cross-fading at x = 0 with the puck mid-move.

### E1.19 Swipe row and alphabet rail
- AC1 Swipe-to-reveal: 76 dp per action, full-swipe threshold with haptic, settle 1.0/500, custom accessibility actions.
- AC2 Alphabet rail: `scrollToItem` while scrubbing, letter bubble, haptic per letter.
- Screenshots: `e1.19-swipe-revealed`, `e1.19-swipe-full`, `e1.19-alphabet-rail-scrubbing`.
- **R4 Step 5 (done)**: `design/list/AlphabetScrollBar.kt` ported from Convx `ui/component/AlphabetScrollBar.kt` (Char sections as an
  `ImmutableList<Char>`, `ListScrollRail` for `LazyListState`; added: bubble level with the finger, TalkBack `setProgress` +
  `stateDescription`, hidden while the IME is up, above `LocalContentInsets.bottom`), test `AlphabetScrollBarTest` ported.
  `design/list/SwipeRow.kt`: `SwipeRow` facade over `IosSwipeableRow`, `SwipeAction`, `SwipeRowGroup` (one open row; a tap on
  a list with an open row only closes it; the last trailing action runs on a full swipe). Catalog `ListsPage` (120 contacts,
  sticky headers, Pin / Mute / Delete + undo, rail). Tests: `AlphabetRailTest`, `SwipeRowTest` (group), `E119ShotTest`.
  AC1 swipe screenshots are the `e1.29-swipe-*` goldens; rail goldens `e1.19-alphabet-rail-scrubbing-*`, `e1.19-lists-page-*`.

### E1.20 Images and photo viewer
- AC1 Coil 3 loader factory (memory 25 %/15 % low RAM, crossfade off in rows, size from the slot).
- AC2 `PhotoViewer`: pinch and double-tap zoom, pan with bounds, swipe down to dismiss with background fade, tap hides
  chrome, paging with "3 of 7", toolbar slots (Save, Share, Forward, Edit, Remove); zoom maths pure and tested.
- Screenshots: `e1.20-viewer-fit`, `e1.20-viewer-zoomed`, `e1.20-viewer-dismiss-drag`, `e1.20-viewer-paging`.

### E1.21 Performance harness
- AC1 `CountRecompositions` test helper; a test drives a tab-bar drag and a menu open and asserts <= 2 recompositions per
  composable.
- AC2 Convention tests (ARCH 4.3): no Material3 import, no `composed {`, no `Popup(`/`Dialog(` outside overlay, no raw dp
  in app modules, no `FlowRow` chips.
- AC2b Convention tests also forbid string literals passed to `Text(`/`contentDescription` in app modules (text comes from
  `strings.xml` or Rust sentences), `System.currentTimeMillis()`/`Instant.now()` outside the injected `Clock`, and
  `Thread.sleep`.
- AC2c Every screen test in later epics calls `enableAccessibilityChecks()` (ARCH 9); the helper is in `ShotTest`.
- AC3 Compiler-report check: named hot composables are restartable and skippable.
- Screenshots: none.
- Evidence (E1.21, R4-7): `testing/.../Recomposition.kt` (`RecompositionCounter`, `CountRecompositions`, `CountedStatic`),
  `catalog/.../RecompositionTest` (tab-bar drag over four tabs, menu open, sheet medium to large, slider drag, swipe-row drag;
  each counted slot <= 2 recompositions, each test also asserts the interaction happened; a control test proves the counter
  sees a state-reading slot). Counted slots are the ones callers hand to components plus static siblings (the components
  carry no test hooks); the list is in the test KDoc. `design/.../conventions/ConventionRules + ConventionTest`: 11 rules
  (material3, composed, window Popup/Dialog only under `design/overlay`, FlowRow, rail `animateScrollToItem`, `Thread.sleep`,
  wall clock outside `design/time`, app-module raw dp and literal text), each with positive and negative fixtures, plus a
  scan of every `src/main/kotlin` tree; the one existing hit (`composed {` in the ported `IosPressable.kt`) was fixed by
  moving it onto the `rowPress` Modifier node, and the allow-list is empty. `testing/.../Accessibility.kt`: ui-test's own
  `enableAccessibilityChecks()` is still not public in 1.12.1, so `ShotTest.enableAccessibilityChecks()`, `assertAccessible()`,
  `suppressAccessibility(contains, reason)` and `screenTest {}` run our own checks (touch target >= 48 dp, speakable label, role
  on toggles); `CatalogAccessibilityTest` runs them over all 30 catalog pages in light and dark and found the context-menu
  rows and tiles without a label (fixed: merged semantics in `longPressMenu`, content description on the page rows);
  `AccessibilityHelperTest` has the failing fixtures. `:design:checkComposeStability` (build-logic `CheckComposeStabilityTask`,
  wired in `scripts/kotlin-ui-check.sh` with `-PcomposeReports=true`) requires every overload of 22 hot Convx composables
  (tab bar cells, `FrostedTopBar`, `IosList`, `IosSwipeableRow`, `IosMenu*`, `IosToggle`, `IosSlider`, `SegmentedControl`,
  `GlassTextField`, `AlphabetScrollBar`) to be restartable skippable and fails on a name missing from the report; all pass today.
  `FrozenRecordCountTest` (`:design`, real `drawBackdrop`): `BackdropDebug.samplerRecords` and `rootRecords` show the source
  layer keeps recording its animated content while a sampling surface records once and stays flat through a freeze. Note: the
  source layer itself does re-record when its content changes (Convx only freezes the samplers), so "the root layer is not
  re-recorded" in the plan is read as "the glass samplers are not re-recorded". No screenshots (no visual change).

### E1.22 Catalog complete
- AC1 The catalog has a page for every component and state above; a "states" page per overlay.
- AC2 `docs/kotlin-ui/screenshots/design.png` contact sheet; `docs/kotlin-ui/design-system.md` (short usage guide,
  points to the skill for numbers).
- AC3 Checklist (SKILL `references/checklist.md`) run over the catalog; findings fixed or written down.
- Screenshots: the contact sheet.
- R4 delivered (verified by the round-5 planner): `catalog/.../OverlayStatesPage.kt` + `CatalogPage.OverlayStates`,
  `CatalogNavigationTest` (every page), `build-logic/.../ContactSheetTask.kt` (`:catalog:contactSheet`, never run: the PNG
  is missing), `docs/kotlin-ui/design-system.md`, notices for Coil and Navigation 3, catalog APK (37.5 MB). R5 finishes
  AC1 goldens, AC2 sheet, AC3 checklist record (`round-05.md` Steps 2-5).
- Evidence (R5, done): commits `29c7154` (F9), `600e781` (AC1 overlay-state goldens), `d61a709` (AC2 contact sheet),
  `7851cf2` (AC3 checklist pass). 24 `android/catalog/screenshots/e1.22-states-*.png` goldens (12 states, light and dark).
  Opened `e1.22-states-menu-below-light.png`: the glass menu (Select with check, Sort by, divider, red Delete all) grows
  under the "..." button over a dimmed page; panel is near-opaque white with faint blurred page text behind it, nothing
  clipped. Opened `e1.22-states-sheet-medium-dark.png`: medium sheet with grabber, title and body, 28 dp-class top
  corners, page dimmed and blurred behind, no content under the sheet edge. Opened `docs/kotlin-ui/screenshots/design.png`
  (1956x3243 contact sheet of all goldens in 8 columns, labelled by golden name, runs from catalog home to
  `e1.29-tabbar-accessory`): every row aligned, no blank or broken tile, light and dark states visible.
  `docs/kotlin-ui/checklist-e1.md` records sections A-H (3 defects found at font scale 2.0 and fixed). Checks:
  `bash scripts/kotlin-ui-check.sh` = ALL GREEN (R5-5 run). Catalog APK rebuilt with `./gradlew :catalog:assembleDebug`:
  `android/catalog/build/outputs/apk/debug/catalog-debug.apk`, 37483881 bytes (35.7 MiB).

---

## E2. Rust bridge (`crates/rovsti_app_core`, `crates/rovsti_ffi`, `:core-bridge`) - DONE (R2)

Goal: the Kotlin apps can call every domain function they need, off the main thread, with typed models, contract
tests and the cold-path `headless` entry. Spec: ARCH 6.

Round plans: **R1** -> `_bmad-output/kotlin-ui/plans/round-06.md` (planned 2026-11-08 at base `a9f189c`): E2.1-E2.8 in
8 steps (app_core + storage + Stops store/pick/snap; wa_link; Messages prefs/drafts/access; Contacts/Phone pure halves;
headless handlers with golden answers; `rovsti_ffi`; `:core-bridge` with `testRustBridge`; contract fixtures, Kotlin
models and fallback, catalog APK with `librovsti_ffi.so`). E1 re-verified done by this planner (R5 review fix of the
inline title judged: it slides off centre only when a centred title would run under a wide toolbar).
**R2** -> `_bmad-output/kotlin-ui/plans/round-07.md` (planned 2026-11-08 at base `09a4343`): E2.9-E2.14 in 8 steps (op
registry + `OPS.md` inventory + typed Kotlin facades + shared number ops; Phone + `ContactIndex` handle and the 5 000-contact
bench; Contacts + `qr.*`; Messages core; Messages chat tools; Stops + `ActiveTrip`; tiles binary buffer
(`docs/kotlin-ui/tile-buffer.md`) and offline jobs; WhatsApp ops, events, call audio, close E2). R1 re-verified done (42
fixtures, 8 exports, `testRustBridge`); found that most `rovsti_core` types lack serde (plan Rule 3).

| Story | Title | Round | Status |
|---|---|---|---|
| E2.1 | `rovsti_app_core` crate, storage, Stops logic moved (store, pick, snap, headless) | R1 | done (round-06 steps 1 and 5) |
| E2.2 | Messages logic moved (prefs, drafts, access, headless) | R1 | done (round-06 step 3) |
| E2.3 | Contacts and Phone logic moved (trash store, prefs, WhatsApp glue, notes, WA call tracker) | R1 | done (round-06 step 4) |
| E2.4 | `wa_link` moved (link file, headless send) | R1 | done (round-06 step 2) |
| E2.5 | `rovsti_ffi`: exports, envelope, panic guard, handles, event sink, desktop build | R1 | done (round-06 step 6) |
| E2.6 | `:core-bridge`: cargo-ndk and host build tasks, `RustNative`, dispatcher, init | R1 | done (round-06 step 7) |
| E2.7 | Contract fixtures and Kotlin models | R1 | done (round-06 step 8) |
| E2.8 | `headless` entry compatible with today's receivers | R1 | done (round-06 steps 5 and 8) |
| E2.9 | Phone domain ops | R2 | done (round-07 step 2; evidence below) |
| E2.10 | Contacts domain ops, `qr.encode` / `qr.decode` | R2 | done (round-07 step 3; evidence below) |
| E2.11a | Messages domain ops: inbox, sms, mms, notify, schedule, spam, backup | R2 | done (round-07 step 4; evidence below) |
| E2.11b | Messages domain ops: chat tools (find, gallery, export, search, forward, info, reminders, stars, marks, mute, quick replies, voice speeds, shared card, detection) | R2 | done (round-07 step 5; evidence below) |
| E2.12 | Stops domain ops | R2 | done (round-07 step 6; evidence below) |
| E2.13 | Tiles: binary geometry, cache usage, clear, offline area | R2 | done (round-07 step 7; evidence below) |
| E2.14 | WhatsApp client ops, event stream, call audio frames, avatar plan | R2 | done (round-07 step 8; evidence below) |

#### Evidence, E2.14 (round-07 step 8)
- Rust, `rovsti_ffi/src/ops/wa/`: `mod.rs` (`wa.open {url, key?, cache_dir?}` -> a `Gateway` handle; `ClientError` -> envelope kinds in `map_error`: `network`,
  `auth` for 401 and 403, `invalid` with the gateway's sentence and `detail.code`, `parse`), `rest.rs` (one op per `Gateway` method: instance create/status/relink/delete,
  keys mint/list/revoke, register_push, chats(_with_meta), messages(_with_meta), send_text, schedule_text/scheduled/cancel_scheduled, send_media(_with),
  media/thumbnail/status_media/status_thumbnail/picture as base64 or, over 1 MiB, a temp file in the handle's cache dir, react, mark_read, mark_played, presence,
  chat_state, groups/group, lookup_users, profiles/refresh_profiles, statuses, mark_status_viewed, edit/revoke/delete_for_me/star, chat_action, calls place/list/accept/
  reject/end/set_muted), `pure.rs` (`wa.push_parse`, `wa.link_read`, `wa.link_write`, `wa.avatars_plan`, `wa.names`, `wa.target`), `live.rs`
  (`wa.events_start` -> a stream handle with its own thread that emits `wa.event {instance, event}`, `wa.events_reconnect_now`, release stops it; `wa.call_audio_open` ->
  a `CallAudio` handle behind the `audioPush` / `audioPull` JNI exports: 1 920-byte frames, other sizes dropped with a debug log, pull answers 1 920 / 0 / -1 ended).
- Inventory sweep: the 62 remaining `todo` rows are classified (`op`, or `none` for the GPUI-era service wire and the headless send path) and the two missing user-facing pieces
  are built: `phone.backup_export`, `phone.backup_import` (`BackupFile` / `ImportPreview`), `phone.display_name`. `DONE_DOMAINS` now holds every domain (`wa`, `platform` added);
  `OPS.md` has no `todo` row.
- Fixtures: `tests/fixtures.rs` starts a loopback gateway per `wa.*` case that answers `tests/data/wa/<stem>.json` (71 canned files; each also states the request the client must
  have sent, incl. bearer, method, path with query and body, and the test fails on a mismatch); `$WA` in a request is its URL, a temp-file path in an answer is `$DIR`. 57 `wa.*` ops
  (97 fixture stems) and 3 new `phone.*` ops: INDEX now lists 978 stems (1 957 files incl. INDEX).
- Rust tests beyond the fixtures: `tests/wa_live.rs` (a WebSocket stub: events arrive as `wa.event` in order with the `after` query and the bearer, a revoked key ends the stream
  with `unauthorized`, release stops it; audio loopback: 6 frames pushed come back identical and in order, wrong sizes dropped, ended call -> -1), unit tests in `ops/wa/*`.
- Gate (round-07 step 8, disk 14 GB free before; the workspace test ran with `CARGO_PROFILE_DEV_DEBUG=0 CARGO_INCREMENTAL=0` because the debug-info build of the GPUI crates
  fills the disk): `cargo fmt --all`, `cargo clippy --workspace --all-targets` (0 warnings), `cargo test --workspace` (3602 passed, 0 failed),
  `cargo check -p rovsti_android --target aarch64-linux-android` ok. `cargo test -p rovsti_ffi`: 65 unit + 3 fixtures (978 stems) + 3 inventory + 2 bench (1 ignored, the release
  bench: p95 0.74 ms `search.query` from E2.9) + 4 `wa_live`. `bash scripts/kotlin-ui-check.sh` ALL GREEN (Kotlin-facing crates 2178 Rust tests; `:core-bridge`
  `testDebugUnitTest` 65, `testRustBridge` 73, 0 failures). No `:design` / `:catalog` UI code changed, so no screenshot is new (E2 has none).
- Catalog APK (218 864 819 bytes, 209 MiB, debug): `android/catalog/build/outputs/apk/debug/catalog-debug.apk` carries `lib/arm64-v8a/librovsti_ffi.so` and `lib/x86_64/librovsti_ffi.so`
  (`scripts/check-catalog-apk.sh` green).
- Not in this story, by design: `AvatarBook` (the in-memory fetch policy of `rovsti_app_core::wa_link::avatars`) and `WaPanel` are state machines the Kotlin `:wa` module ports with their
  Rust unit tests (E3); the ops here are the disk plan, names, target and link file. The loopback audio test lives in `rovsti_ffi/tests/wa_live.rs` (it needs the handle registry)
  instead of `rovsti_wa_client/tests`.
- Hand-off: next planner starts E3 R1 (`:platform`, `:wa`, `:shell`; `:shell` begins with `Rust.init` in `Application.onCreate`, `HeadlessClient` in `:platform`, `WaGateway` /
  `WaEvents` / `CallAudio` in `:wa`) and may plan the E4-E7 app epics against the finished ops (`crates/rovsti_ffi/OPS.md` is the list of what is Rust and what is Kotlin).

- Kotlin: `model.wa` (`WaModels`, `WaEventModels`: `GatewayEvent` with the gateway's `type` tag, `WaRequests`: flat `WithHandleSerializer`), `WaOps` / `NativeWaOps` (generated
  1:1 from the op list), `WaGateway : RustHandle` (all methods, `startEvents` -> `WaEventStream`, `openCallAudio` -> `CallAudio`), `WaEvents.feed()` (sink overflow -> one
  synthetic `WaFeed.Resync`), `model.phone.BackupModels`, `WaContracts`. Tests: `WaModelsTest`, `WaEventsTest` (JVM) and, in `testRustBridge` against a Kotlin loopback gateway with
  a hand-written RFC 6455 peer (`FakeGateway`), `WaNativeTest` and `CallAudioTest` (through JNI).

#### Evidence, E2.13 (round-07 step 7)
- Format: `docs/kotlin-ui/tile-buffer.md` (`RVT1` v1, little-endian; header, class records, parts of tile-local f32 points; flags bit 0 dark,
  bit 1 cache only; paint ids, casing as bit 15 of the id; polygon = exterior then holes by ring orientation, filled even-odd per polygon).
- Rust: `rovsti_ffi/src/tile_buffer.rs` (encoder, reference decoder, self-decode tests on `osm-14-9520-6130.mvt`: vertex count equals
  `TileGeometry::vertices`) and `src/ops/tiles.rs` (`tiles.{open, usage, usage_by_source, clear_all, clear_except, set_cap, test_server,
  stand_in, encode_mvt, offline_start, offline_cancel}`); the `tile` JNI export reads the `tiles.open` handle (cache hit or download,
  `TileGeometry::from_bytes`, style, encode; errors stay envelopes, told apart by the magic). The offline job runs four workers, emits
  `tiles.offline` `{job, done, saved, failed, total, fraction, text, finished, cancelled}` and cancels on `offline_cancel` or when its
  handle is released; `offline_start` takes a `bbox` or a `circle` (the trip prefetch). Unit tests use a localhost stub server.
- Fixtures: 11 ops, 27 pairs (`tiles.encode_mvt` is built from the real `.mvt`); the fixture harness now chains `Case::after`.
- OPS.md: no `todo` row of the tiles domain (`tiles` joins `DONE_DOMAINS`).
- Kotlin: `TilesOps` / `NativeTilesOps`, `TileHandle` (decoded `tile()`, cache ops, `startOffline` -> `OfflineJob.progress`), `TileBuffer.decode`,
  `model.stops.Tile*` models, `TilesContracts`; `TileBufferTest` (decodes the fixture), `testRustBridge` `TileHandleTest` (cache-only on a
  seeded dir, cache ops, offline progress and cancel against a localhost server).

#### Evidence, E2.12 (round-07 step 6)
- Rust: `rovsti_ffi/src/ops/stops/{trip,pick,places,history,store,misc,view}.rs`, 69 ops. `stops.trip_*` is a handle over
  `rovsti_geo::ActiveTrip` (start, restore, fix -> `FixOut`, notice result, snooze, timeout, dismiss, status share, end); a `dir` makes
  the op also write `trip.json` under `rovsti_app_core::stops::store::lock()`, the lock of the headless service (headless fixtures
  unchanged). Also `crossing_{validate, read}` (`DiameterError::message` in the envelope message), `dest_{parse, share}`,
  `pick_{replay, labels, save_offer}` and `snap`, `saved_{load, edit, list, suggest_name, shortcut}`, `places_{export, import}`,
  `history_{list, edit, prune_plan, usage, recent, export, text}`, `stats`, `store_*`, `prefs_{load, save, edit, choices}`,
  `notice_{render, validate}`, `alarm_plan`, `poll`, `gtfs_{import, search}` (tiny zip `rovsti_ffi/tests/data/gtfs-tiny.zip`),
  `coord_{format, parse}`, `measure`, `backup_{export, parse}`, `arrival_clock`, `format_distance`; `view.{project, step, tiles,
  frac_to_coord, follow}` are the `MapCamera` cross-check fixtures for E7.
- OPS.md: no `todo` row of the stops domain (`stops` joins `DONE_DOMAINS`); the `tiles` rows stay for E2.13.
- Kotlin: `StopsOps` / `NativeStopsOps`, `ActiveTripHandle`, `model.stops.*` (strict contract fixtures for every case),
  `StopsContracts`; `testRustBridge` `StopsOpsNativeTest` (create, fix, ring, snooze, dismiss, end, close, restore, `dir` file,
  gtfs import).

#### Evidence, E2.11b (round-07 step 5)
- Rust: `rovsti_ffi/src/ops/chat/{search,gallery,jump,marks,detect}.rs` (44 ops, all stateless; offsets into a body or snippet are
  UTF-16): `chat_find.{find, cursor}`, `global_search.{search, prepare, footer, jump}`, `chat_gallery.{build, tabs}` (media / links /
  docs with month sections), `chat_export.{text, block, notice}`, `multi_select.plan`, `date_jump.{pick, step}`,
  `unread_jump.{state, divider, next}`, `voice_player.{speeds, toggle, view}`, `thread_marks.edit`, `mute.{choices, status}`,
  `starred.{rows, local}`, `quick_replies.{edit, insert}`, `forward.{plan, words}`, `text_detect.detect`, `date_detect.detect`,
  `location_link.{find, share}`, `shared_card.{read, mime}`, `message_info.rows`, `message_reminder.{make, slots, list}` and the
  Phone app's `call_reminder.{make, list}` (the `reminder` module rows were `todo` under the messages domain). `OPS.md`: no `todo` row
  is left in the `messages` domain (`DONE_DOMAINS` now lists it); `multi_select::Selection` is `kotlin` (per-tap set), `lru` and
  `JumpTarget` are `none`. 138 fixture stems (`crates/rovsti_ffi/fixtures`), including the error cases (`invalid` with
  `detail.reason` for a refused quick reply, `rekey` without a target, a blank reminder, a forward of nothing).
- Kotlin: `ChatOps`/`NativeChatOps`, models `model.chat` (`FindModels`, `GalleryModels`, `JumpModels`, `MarksModels`,
  `DetectModels`), contract models `ChatContracts`; `ChatModelsTest` (JVM) and `ChatOpsNativeTest` (real library, 12 cases) in
  `testRustBridge`. No UI, so no screenshots.

#### Evidence, E2.11a (round-07 step 4)
- Rust: `rovsti_ffi/src/ops/messages/{inbox,sms,spam,schedule,backup,store}.rs` (43 ops: `inbox.{merge, preferred_channel,
  person_for_number, person_for_jid, filter, bulk, pending_delete}` (merge and `person_for_*` take a `search.index_new` handle),
  `sms.{segments, to_message, key}`, `sms_timeline.build` (replays page / pending / status / retry / discard / remove / adopt events),
  `mms_pdu.{parse, build_send_req, build_notify_resp}` (base64 bytes), `otp.find` (UTF-16 offsets), `spam.{classify, add_rule,
  insert_rule, remove_rule, links, open_links, contains_link}`, `notify.build`, `schedule.{validate, add, cancel, reconcile, due_soon,
  load, quick_times}` (stateless `queue_json`, reason in `OPS.md`), `msg_backup.{export, parse, plan, strength}` (Kotlin supplies salt and
  nonce), `messages.{prefs_load, prefs_save, prefs_edit, drafts_load, drafts_save, drafts_put, drafts_rekey, access, tag}`). Serde
  derives added to the plain data types in `rovsti_core` / `rovsti_app_core` (no existing on-disk format changed). `OPS.md`: every
  Messages row of these modules is op or none; the chat-tool rows were finished in step 5 (E2.11b).
- Kotlin: `MessagesOps`/`NativeMessagesOps`, models `model.messages` (externally tagged `ThreadId`, `PersonKey`, `SmsKey`,
  `SpamRule`, `ScheduleTarget` via `ExternallyTagged`), contract models `MessagesContracts`; `MessagesModelsTest` (JVM) and
  `MessagesOpsNativeTest` (real library) in `testRustBridge`. No UI, so no screenshots.

#### Evidence, E2.10 (round-07 step 3)
- Rust: `rovsti_ffi/src/ops/contacts/{draft,files,lists,store}.rs` (66 ops: `contacts.draft_*`, dates/age/event line, `card`, `dupes`,
  `merge_plan`, `cleanup`, `avatar`, `stats`, `quick`, csv/ics/vcard/`import_plan`/`intake`/`scan`/`photo_*`, `view`, `select_*`,
  `groups_*`, `recent_*`, `history`, `frequent`, `me_*`, `birthday_reminders`, `options`, `pin_shortcut`, `deep_index_new` +
  `deep_find` (handle over `DeepIndex`), `trash_*` and `prefs_*` over `rovsti_app_core::contacts` with a `dir` or the init data dir;
  fixture cases use the `contacts` seed dir) and `ops/qr.rs` (`qr.encode`, `qr.decode` over `qrcode` / `rqrr`; format in `OPS.md`;
  round-trip test at 4 px per module with a quiet zone). The WhatsApp panel decisions (`contacts::whatsapp`) moved to the `wa` domain
  (step 8). `OPS.md`: every contacts and qr row is op or none (`DONE_DOMAINS` += contacts, qr); `shared_card` rows moved to messages.
- Kotlin: `ContactsOps`/`NativeContactsOps`, `QrOps`/`NativeQrOps`, `DeepIndexHandle : RustHandle`, models `model.contacts`,
  `model.qr` (+ `QrMatrix`, no ZXing / ML Kit), contract models `ContactsContracts`, `QrContracts`; `QrMatrixTest` (from the fixture),
  `ContactsOpsNativeTest`, `QrOpsNativeTest` (Kotlin draws the matrix, Rust reads it back) in `testRustBridge`.

#### Evidence, E2.9 (round-07 step 2)
- Rust: `rovsti_ffi/src/ops/search.rs` (`search.{index_new, index_replace, set_call_counts, query, query_loose, match_number, fold}`,
  `t9.query`: a handle over `ContactIndex` + `T9Scratch`) and `ops/phone/{log,people,dial,calls,store}.rs` (74 ops: `recents.*`,
  `call_log.*`, `call_notes.*`, `call_stats.*`, `speed_dial.*`, `blocklist.*`, `dial.*`, `sim.*`, `setup.*`, `audio.*`, `scene.*`,
  `phone.*` incl. `phone.wa_tracker_*`). Serde derives added to the `rovsti_core` types they carry (Rule 3), no DTO copies except
  `scene`, `audio`, `sim`. `OPS.md`: every phone, search and t9 row is op, kotlin or none (`DONE_DOMAINS` += phone, search, t9).
- Fixtures: cases may open a handle first (`Case::after`); the fixture test swaps `PLACEHOLDER_HANDLE` for the live handle.
- Bench (`tests/search_bench.rs`, 5 000 seeded Latin/Turkish/Cyrillic contacts, 20 queries, JSON both ways): release p95
  `search.query` 0.74 ms, `search.query_loose` 0.90 ms, `t9.query` 0.72 ms (limit 5 ms); debug median under 25 ms.
- Kotlin: `SearchOps`/`NativeSearchOps` + `ContactIndexHandle : RustHandle`, `PhoneOps`/`NativePhoneOps`, models
  `model.search`, `model.phone`; `ContactIndexTest` and `PhoneOpsNativeTest` in `testRustBridge`.

#### Evidence, E2 R1 (E2.1-E2.8 done, round-06)
- E2.1-E2.4: commits `07ab3a5` (app_core, storage, Stops store/pick/snap), `e099053` (`wa_link`), `eadb490` (Messages prefs, drafts,
  access), `1eaba7c` (Contacts and Phone pure halves), `0e8fcb0` (headless handlers). `rovsti_app_core` has no gpui/jni
  dependency (`crates/rovsti_app_core/tests/deps.rs`); `cargo test -p rovsti_app_core`: 210 lib tests + the store, headless
  golden (14) and deps tests, all green.
- E2.5: `7f72cca`. `cargo test -p rovsti_ffi`: 20 lib tests (envelope per `ErrorKind`, panic guard, 10 000-handle leak test,
  1 000-event sink order) + 2 fixture tests; `scripts/check-ffi-exports.sh` lists the 8 `Java_dev_rovsti_core_RustNative_*`
  symbols in the aarch64 `.so` (also run on the packaged `.so` by `scripts/check-catalog-apk.sh`).
- E2.6: `8cd30b5`. `:core-bridge:testRustBridge` (real host `.so`: version, 1 MB echo, error kinds, `core.panic`, headless golden,
  1 000 events in order) and `FakeRustCoreTest` pass in one Gradle invocation.
- E2.7: `crates/rovsti_ffi/tests/fixtures.rs` writes/compares 11 `core.*` and 10 `headless.*` pairs in
  `crates/rovsti_ffi/fixtures/` (42 files; `UPDATE_FIXTURES=1` rewrites; a stray file or a differing byte fails).
  `ContractFixturesTest` (6 tests): every fixture decodes strictly (`ignoreUnknownKeys = false`) into
  `dev.rovsti.core.model.{core,headless}` and re-encodes to equal JSON; a fixture without a model and a model without a
  fixture both fail. `RustErrorTest` (4 tests): all 8 kinds map, unknown kind -> `RustError.Unknown`, malformed envelopes ->
  `Parse`. Models are listed in `android/compose_stability.conf`.
- E2.8: Rust half in `0e8fcb0` (goldens now live in `crates/rovsti_ffi/fixtures/headless.<kind>.*`, read by
  `rovsti_app_core/tests/headless_golden.rs`). Kotlin half: `HeadlessClient` (`HeadlessClientTest`, 9 tests: library missing,
  link error at call time, panic/empty answer, `RustError`, runtime error -> `null`; cancellation propagates).
- Rust gate (full, disk 11 GB free before): `cargo fmt --all`, `cargo clippy --workspace --all-targets` (0 warnings; the moved
  tests got a crate-level `cfg_attr(test, allow(clippy::unwrap_used))` in `rovsti_app_core`), `cargo test --workspace` (3547 passed,
  0 failed), `cargo check -p rovsti_android --target aarch64-linux-android` ok. `bash scripts/kotlin-ui-check.sh` ALL GREEN.
- Catalog APK (109 237 553 bytes, 104 MiB): `android/catalog/build/outputs/apk/debug/catalog-debug.apk` carries `lib/{arm64-v8a,x86_64}/librovsti_ffi.so`.
- Hand-off: next planner does E2 R2 (E2.9-E2.14 domain ops, each op with a fixture pair and a Kotlin model, or the
  fixture test fails) and/or E3 R1 (`:shell` starts with `Rust.init` in `Application.onCreate` and `HeadlessClient` in `:platform`).

### E2.1-E2.4 Move app logic out of the GPUI crates
- AC1 Each moved module keeps its unit tests; the GPUI app now calls the moved function (no duplicate copy).
- AC2 `rovsti_app_core` has no GPUI, no JNI, no `rovsti_ui` dependency; it takes the data dir as a `&Path`.
- AC3 File names and schemas unchanged (ARCH 7.1 table); a test per store reads a fixture written by the old code.
- AC4 `cargo test --workspace`, `cargo clippy --workspace --all-targets` and the Android `cargo check` from `CLAUDE.md`
  pass; GPUI apps still build.
- Moves: E2.1 `apps/stops/src/{store,pick,snap,headless}.rs` (pure parts); E2.2
  `apps/messages/src/{prefs,drafts,access,headless}.rs`; E2.3 `apps/contacts/src/{trash_store,prefs,whatsapp}.rs`,
  `apps/phone/src/{notes,wa_call}.rs` (non-drawing parts) and Phone prefs/backup glue; E2.4
  `rovsti_wa_ui::headless`, the link file, and the pure halves of `rovsti_wa_ui::{avatar_book, names}` (profile-picture fetch plan, 429 back-off, disk naming/pruning, display-name rule).
- Screenshots: none.

### E2.5 `rovsti_ffi`
- AC1 Exports of ARCH 6.3; every export in `catch_unwind`; envelope of ARCH 6.5; `op` dispatch per domain module.
- AC2 Handle registry with release; a leak test (create/release 10 000 handles).
- AC3 Event sink: a test thread emits 1 000 events into a JVM-side fake (host JVM test in E2.6).
- AC4 Builds for `x86_64-unknown-linux-gnu`, `aarch64-linux-android`, `x86_64-linux-android`.

### E2.6 `:core-bridge`
- AC1 `cargoBuildAndroid` and `cargoBuildHost` tasks (ARCH 6.7), incremental (second run is UP-TO-DATE); cargo-ndk in
  `scripts/android-setup.sh`.
- AC2 `RustNative` (externs), `RustDispatcher`, debug `assertNotMain`, `Rust.init` from `Application`.
- AC3 A **plain JUnit** test (no Robolectric, task `testRustBridge`, ARCH 6.7 "Classloader trap") loads the host `.so`, calls
  `init` with a temp dir and a round-trip op. A second test proves a Robolectric test in the same Gradle invocation still
  passes with a fake facade (the split works).
- AC4 APK of `:catalog` (or a test app) contains `lib/arm64-v8a/librovsti_ffi.so`; export list checked.

### E2.7 Contract fixtures
- AC1 `crates/rovsti_ffi/fixtures/<op>.{request,answer}.json` written by a Rust test; `UPDATE_FIXTURES=1` rewrites.
- AC2 Kotlin JVM test decodes and re-encodes every fixture; unknown keys fail.
- AC3 `RustError` mapping tested for every `kind`.

### E2.8 `headless`
- AC1 `headless(kind, json)` answers every kind the GPUI apps answer today (Messages: `sms_classify`, `mms_notification`,
  `mms_retrieved`, `mms_send_req`, `wa_digest`, `wa_reply`; Stops: `trip_fix` and the alarm kinds), byte-identical for
  the existing fixtures.
- AC2 Fallback rules of AD-MSG4 hold (library missing, panic, empty answer -> Kotlin posts a plain notification).

### E2.9-E2.12 Domain ops
- AC0 `qr.encode` / `qr.decode` ops (ARCH 6.6) land with E2.10 (first user, Contacts); Phone, Stops and `:wa` reuse them.
- AC1 Every op listed for the domain in ARCH 6.6 exists, has a fixture and a Kotlin facade method (`suspend`).
- AC2 Stateful objects are handles (`ContactIndex` for search-as-you-type, `ActiveTrip` view).
- AC3 A search-as-you-type benchmark on the host: 5 000 contacts, query answer < 5 ms per keystroke.

### E2.13 Tiles
- AC1 `tile(handle, z, x, y, flags)` returns the packed buffer of ARCH 8.4 (format documented in
  `docs/kotlin-ui/tile-buffer.md`, versioned); styles colorful/eclipse by flag.
- AC2 Cache ops: usage total and per server, clear, clear-except, cap change; offline area job with progress events and
  cancel.
- AC3 Kotlin decoder test reads a buffer made from `rovsti_geo/src/tiles/testdata`.

### E2.14 WhatsApp client
- AC1 Ops for link/relink, keys, chats, messages, send text/media, react, mark read, chat state, groups, presence,
  lookup, statuses, scheduled, calls, push wake parse (ARCH 6.6).
- AC2 Event stream handle: start, `reconnect_now`, stop; events through the sink with instance id.
- AC2b `wa.avatars` / `wa.names` ops (ARCH 6.6).
- AC3 Call audio: `audioPush`/`audioPull` with 1 920-byte frames; a loopback test against a fake gateway in
  `rovsti_wa_client` tests.

---

## E3. Platform layer (`:platform`, `:wa`, `:shell`) - DONE (R2)

Goal: every Android capability of the GPUI apps is a Kotlin repository or service class with JVM tests, and the shared
WhatsApp settings screen and app shell exist. Spec: ARCH 7, 5.

Round plans: **R1** -> `_bmad-output/kotlin-ui/plans/round-08.md` (planned 2026-11-09 at base `115be3b`): E3.1-E3.7 in
8 steps (`:platform` module + seams `PlatformEvents`/`Headless`/`PlatformComponents`/`ProviderChanges`, system helpers
and convention tests; contacts; call log/blocklist/SIM; SMS/MMS/scheduled; telecom core with the incoming-order test;
notifications/reminders; location/trip/alarm and `kotlin_contract.rs` moved to `rovsti_app_core`; WhatsApp seams,
FQCN and PendingIntent contract, port inventory `docs/kotlin-ui/platform-port.md`). No screenshots in R1. E2 re-verified
done by this planner (ops modules, fixtures incl. the headless pairs, `:core-bridge` facades and wire models).
**R2** -> `_bmad-output/kotlin-ui/plans/round-09.md` (planned 2026-11-09 at base `840588c`; E3 R1 re-verified done in code):
E3.8a-E3.11 in 8 steps (`platform.files` + `RovstiShareProvider`; `PermissionState`/`PermissionController`/`SpecialAccess` and
the HANDOFF permission lists; the WhatsApp settings machine moved from `rovsti_wa_ui::model` to `rovsti_app_core::wa_link::settings`
with the stateless op `wa.settings_apply`; `:wa` repository, calls, avatars; the settings screen with `e3.10-*` goldens; `:shell`
activity/container/permission UI; common settings pages ported from Convx `IosSettingsGroup`/`SettingsPage`/`AboutScreen`, licences,
close E3). Haptics levels are Off/Light/Normal (`rovsti_core::wire::HapticsLevel`), not Off/System/Strong.

| Story | Title | Round | Status |
|---|---|---|---|
| E3.1 | `:platform` module, port of the bridge files, tests moved | R1 | done (round-08 steps 1 and 8) |
| E3.2 | Contacts provider repository (read, write, groups, accounts, photos, options, observer) | R1 | done (round-08 step 2) |
| E3.3 | Call log, blocklist, blocked calls, SIM accounts | R1 | done (round-08 step 3) |
| E3.4 | SMS/MMS provider, send, scheduled SMS | R1 | done (round-08 step 4) |
| E3.5 | Telecom core: CallRegistry, audio path, proximity, call notifications | R1 | done (round-08 step 5) |
| E3.6 | Notifications, reminders, shortcuts | R1 | done (round-08 step 6) |
| E3.7 | Location, TripService, TripAlarm, AlarmSound, AlarmActivity | R1 | done (round-08 step 7) |
| E3.8a | Files, media store, share, clipboard, Photo Picker, camera capture, SAF import/export | R2 | done (round-09 step 1) |
| E3.8b | Handoff, roles, `PermissionController` + `PermissionState` rules, special access, OEM settings (ARCH 7.5) | R2 | done (round-09 step 2) |
| E3.9 | `:wa`: WaRepository, events, wake, calls and audio threads, `WaAvatars` | R2 | done (round-09 steps 3-5) |
| E3.10 | Shared WhatsApp settings screen | R2 | done (round-09 steps 3 and 6) |
| E3.11 | `:shell`: RovstiRoot, base activity, containers, permission UI, common settings, licences | R2 | done (round-09 steps 7-8) |

Next planner: **E3 is done** (R1 and R2). Plan **E4 R1** (E4.1-E4.8, Contacts) next. E4.1's merged-manifest test uses
`android/platform/src/test/resources/manifest/contacts.permissions.txt` (round-09 step 2); the app container implements
`CommonSettings` (`:shell`, `dev.rovsti.shell.settings`) for its Appearance and Haptics prefs and puts `CommonSettingsGroup`
at the end of its settings page. The apps get the whole platform through `:shell`.

#### Evidence, E4 R1 (E4.1-E4.8 done, round-10)

- E4.1 (commit c7fd7d4): `:contacts` module, manifest parity (`ContactsManifestTest`), container, routes, migration; `ContactsRoutesTest`, `ContactsMigrationTest`, `E41ShotTest` (`e4.1-*` opened: tabs over an empty book, permission row, settings).
- E4.2 (commits 9c4f8ee, 3978efb): data layer and list; `ContactsListViewModelTest`, `ContactsListScreenTest`, `E42ShotTest`; `e4.2-*` opened (sections, one avatar per row, rail, no-results offers, last row above the bar).
- E4.3 (commit b60888b): card; `ContactCardViewModelTest`, `ContactCardScreenTest`, `E43ShotTest`; `e4.3-*` opened.
- E4.4, E4.5 (commits 1006c5f, c5062a3): editor, photo, delete, Undo, Trash; `ContactEditor*Test`, `PhotoCropTest`, `Trash*Test`, `UndoPillTest`, shots `e4.4-*`, `e4.5-*` opened.
- E4.6, E4.7 (commit 5c2439b): Favorites, frequent, upcoming, Groups; `FavoritesViewModelTest`, `UpcomingTest`, `GroupsViewModelTest`, `FavoritesScreenTest`, `GroupsScreenTest`; `e4.6-*`, `e4.7-*` opened.
- E4.8 (commit: round-10 step 8, "Select mode (E4.8)"): `SelectModeViewModelTest` (10: enter from a row or the toolbar, bar words and actions from the core, toggle and select all, share text, .vcf export, Copy Numbers, Star both ways, add to group, multi-delete with ONE undo restoring all three), `SelectModeScreenTest` (8, including Back leaves select mode not the tab, last row above the toolbar), `E48ShotTest`. Opened `e4.8-select-mode-light` (title "3 selected", Select All / Cancel capsule, filled circles on Ada and Burak, the third selected row is below, folded tab-bar circle with the five-button toolbar beside it) and `e4.8-select-actions-menu-dark` (the "..." card above its button). `contacts-debug.apk`: `android/contacts/build/outputs/apk/debug/contacts-debug.apk`. Checklist A-H: `docs/kotlin-ui/checklist-e4.md`. Design additions: `RovstiNavHost`/`RovstiShell` `accessory` slot, `LargeTitleScaffold.actionsContent`, `ToolbarTextButton`, `ToolbarButton(enabled)`, `SelectCheck`.

Next planner: **E4 R2** (E4.9-E4.16: share and shortcuts, import paths, duplicates and Clean up, photo viewer on the card, ringtone/voicemail/block, My card/emergency/recent, settings, polish). Known carry-overs: share as a staged .vcf file in the share sheet (E4.9), row separators under the alphabet rail (E4.16), custom accessibility actions on the row menu (E4.16).

#### Evidence, E5 R1 (E5.1 done, round-12 step 2)

- E5.1 data layer (`447ee0d` files, step 2 tests and gaps): `dev.rovsti.phone.data` `PhoneStore` (debounced single write, `flush` from `MainActivity.onStop`), `RecentsRepository` (builds on the scope's Default dispatcher, bursts coalesced by `RebuildCoalescer`, `RecentsModel.error` set when an op fails and cleared by the next good build; the default `extras` is `flowOf(emptyList())`, the first version's `emptyFlow()` never emitted), `PeopleIndex` (handle released on READ_CONTACTS revoke, new handle on re-grant), `SimState`, `BlockRepo`, `SpeedDial`, `CallPlacer`. `FakeCalls` lives only in `platform/src/testFixtures`. Tests: `PhoneStoreTest` (concurrent edits are one write, nothing lost), `RecentsRepositoryTest` (failed build keeps rows and says why, off the main thread), `PeopleIndexTest`, `SimStateTest`, `CallPlacerTest` (emergency never asks for a SIM, two SIMs without default NeedsSim, default Placed with its handle, invalid carries the core sentence), `SpeedDialTest`, `BlockRepoTest`, `PhoneContainerTest` (owner rule b: store, recents, people and the rest issue no core call until the gate, standing for `Rust.init`, opens).

#### Evidence, E4 R2 (E4.9-E4.16 done, round-11)

- E4.9 (commit 4aa2099): `ContactShareTest`, `QrSheetTest`, `QrCodeTest` (`QrCode` in `:design`); `e4.9-share-menu`, `e4.9-qr-sheet` opened.
- E4.10 (commits 9208cb8, 6ef4753): `IntakeViewModelTest`, `IntakeScreenTest`, `ScanQrTest`, `CsvImportViewModelTest`, `CsvMappingTest`; `e4.10-*` opened.
- E4.11 (commit ffcc478): `DuplicatesViewModelTest`, `CleanupViewModelTest`, `DupesScreenTest`, `CleanupScreenTest`; `e4.11-*` (incl. fs2 chips) opened.
- E4.12, E4.13 (commit 35fae2d): `PhotoViewerActionsTest`, `CallOptionsViewModelTest`, `CallOptionsSectionTest`; `e4.12-*`, `e4.13-*` opened.
- E4.14 (commit 7e28c7a): `MeViewModelTest`, `EmergencyViewModelTest`, `RecentListsViewModelTest`; `e4.14-*` (fs2, `e4.14-emergency-list-rtl`) opened.
- E4.15 (commit 91d95d6): `ContactsSettingsViewModelTest`, `SettingsScreenTest`; `e4.15-*` opened.
- E4.16 (round-11 step 8): row separators stop 8 dp before the alphabet rail (`e4.2-list-*` re-recorded and opened: hairlines end
  clear of the letters); the row menu is mirrored as `customActions` (`ContactsListScreenTest.everyRowOffersItsMenuAsAccessibilityActions`);
  `scrollingRecomposesEachRowAtMostOnceAndNeverTheOnesThatStay` and the typing test count row compositions; motion audit and
  checklist A-H for every R2 screen in `docs/kotlin-ui/checklist-e4.md`; contact sheet `docs/kotlin-ui/screenshots/contacts.png`
  (`:contacts:contactSheet`, 12 columns of the light goldens, opened: consistent chrome, no overlap, nothing under the floating
  bars). Gate: cargo fmt/clippy/test --workspace, `cargo check -p rovsti_android --target aarch64-linux-android`, `scripts/kotlin-ui-check.sh`
  green. APKs: `android/contacts/build/outputs/apk/debug/contacts-debug.apk`, `android/catalog/build/outputs/apk/debug/catalog-debug.apk`.

Next planner: **E5 R1** (Phone, E5.1-E5.8). E4 is done.

#### Evidence, E3 R2 (E3.8a-E3.11 done, round-09)

- E3.8a (commit 6323952): `platform.files` (`Documents`, `MediaShare`, `RovstiShareProvider`, clipboard, Photo Picker, camera); `FqcnTest` (13 names), `NoMediaPermissionTest`, `PortInventoryTest`.
- E3.8b (commit 96cc308): `PermissionState`, `PermissionController`, `SpecialAccess`; `PermissionStateTest`, `PermissionControllerTest`, `HandoffPermissionsTest`.
- E3.9 (commits 288f0fb, c66376a): `:wa` `WaLinkStore`, `WaRepository`, `WaCalls`, `WaAvatars`; `WaRepositoryTest`, `WaCallsTest`, `WaAvatarsTest`, `WaLinkStoreTest`.
- E3.10 (commit b299c48): `WhatsAppSettingsScreen`; `WhatsAppSettingsViewModelTest`, `WhatsAppSettingsScreenTest`, `E310ShotTest`; `android/wa/screenshots/e3.10-*` opened in that step.
- E3.11 (commit e840c38, and round-09 step 8): `:shell` activity, container, permission UI, then `dev.rovsti.shell.settings`: `IosSettingsGroup`, `SettingsPage`, `AboutPage` (Convx ports with headers), `CommonSettings` + `FakeCommonSettings`, `AppearancePage`, `HapticsPage`, `LicencesPage`/`LicenceDetailPage`, `NoticesParser`; Gradle task `copyLicenceAssets`. Tests: `NoticesParserTest`, `CommonSettingsTest`, `SettingsPagesTest` (incl. last licence row clears the inset), `LicenceAssetsTest`, `E311SettingsShotTest`, `PermissionGateTest`, `RefreshRateTest`, `PortInventoryTest` (no `R2`/`todo` rows). PNGs opened in `android/shell/screenshots/`: `e3.11-settings-appearance` (segmented control full width in its card, footer wraps), `e3.11-settings-haptics` (radio rows, tick on the chosen one, no chevrons, Try it row with a tile), `e3.11-licences` and `e3.11-licences-fs2-light` (tiles aligned, "Kyant0/AndroidLiquidGlass (kmp branch)" wraps and the chevron stays), `e3.11-licence-detail` (glisse notice and full MIT text, clear of the bottom), `e3.11-about` (title, version pill, rows with tiles, Licences row), all light and dark. Found and fixed from the first shots: `ScreenPage` kept its large title blurred under the top fade (it did not pass `collapse` as `ScreenLazyPage` does), and radio rows showed chevrons.

#### Evidence, E3 R1 (E3.1-E3.7 done, round-08)

- E3.1 (commits 974206c, step 8): `android/platform`, `PlatformEvents`, `Headless`, `PlatformComponents`, `ProviderChanges`, system helpers; tests `LibraryManifestTest`, `PrefsNamesTest`, `PlatformConventionTest`, `WireModelsTest`, `WireFixturesTest`, `WireV2Test`, `SettingsMirrorTest`, `SystemPagesTest`, `XiaomiOpsTest`, `LocaleTimeInfoTest`, `MessagesSeamTest`; step 8 adds `PortInventoryTest` (every bridge file has a row), `FqcnTest` (12 components, `RovstiShareProvider` is R2 E3.8a) and `PendingIntentContractTest`.
- E3.2 (commit 983b09e): `ContactsRepository`, `ShortcutsRepository`, `ContactsEdit`; tests `AndroidContactsRepositoryTest` (every `EDITED_MIMES` row), `FakeContactsRepositoryTest`, `ContactPhotosTest`, `ShortcutsTest`, `ContactCallOptionsTest`.
- E3.3 (commit e69063c): `CallLogRepository`, `BlocklistRepository`, `SimRepository`; tests `AndroidCallLogRepositoryTest`, `BlocklistTest`, `RejectPolicyTest`, `SimRepositoryTest`.
- E3.4 (commit 395589e): SMS/MMS provider, send, `ScheduledSms`; tests `AndroidSmsRepositoryTest`, `SmsSendTest`, `MmsCodecTest`, `MmsInboxTest`, `ScheduledSmsTest`, `SmsRulesTest`, `MessagesHostTest`.
- E3.5 (commit bf0d946): telecom core; tests `IncomingOrderTest`, `IncomingCallTest`, `OngoingCallTest`, `MultiCallTest`, `AudioPathTest`, `ProximityPolicyTest`, `CallEventRingTest`, `RebuildCoalescerTest`, `FlipRuleTest`, `DialIntentsTest`, `CallNotificationsChannelsTest`.
- E3.6 (commit 157b05c): notifications, reminders, reply receiver; tests `NotificationChannelsTest`, `MessageRemindersTest`, `CallRemindersTest`, `BirthdayRemindersTest`, `ReplyReceiverTest`, `HeadlessFixturesTest`.
- E3.7 (commit 72d4345): location, `TripService`, `TripAlarm`, `AlarmActivity`; tests `LocationRepositoryTest`, `TripServiceTest`, `TripAlarmTest`, `TripStatusTest`, `AlarmOptionsTest`, `TripStartResultTest`, and `kotlin_contract.rs` in `rovsti_app_core`.
- Step 8 also ports the WhatsApp seams (`WaIncomingTest` moved, plus frame-size, `takeAction` and wake-event cases).

### E3.1 Port
- AC1 Files ported per ARCH 7.3; classes named by any manifest, PendingIntent or shortcut keep their FQCN.
- AC2 The Rust-calls-Kotlin statics are gone; repositories return Kotlin models or wire JSON for Rust.
- AC3 Existing JVM tests moved and green (`apps/phone/platforms/android/app/src/test/...`,
  `apps/messages/platforms/android/app/src/test/...`).
- AC4 Library manifest declares no components; a test asserts it.
- AC5 SharedPreferences names unchanged (test lists them, ARCH 7.1).

### E3.2-E3.8b Repositories and services
- AC1 Each repository is an interface + Android implementation + fake for tests; provider-backed reads are `Flow`s that
  re-query on change (conflated).
- AC2 Robolectric tests with shadow providers for the main reads and writes (contacts save with every mime of
  `ContactsEdit.EDITED_MIMES`, call log delete/restore, SMS insert/read, scheduled alarm set/fire).
- AC3 E3.5 keeps the incoming order of ARCH 8.2: notification first, UI after; `CallRegistry` tests (`MultiCallTest`,
  `IncomingCallTest`, `OngoingCallTest`) green.
- AC4 E3.7 keeps AD-SA1 to AD-SA9; the Kotlin contract with the Rust alarm plan (moved to `crates/rovsti_app_core/tests/kotlin_contract.rs`) is
  moved to `rovsti_app_core` and still checks the Kotlin defaults.
- AC5 E3.8b `PermissionController` exposes state per group as `StateFlow` and asks through the activity result API;
  rationale and "open settings" decisions come from the pure access rules (`rovsti_app_core::messages::access` style).
  `PermissionState` covers never asked / granted / denied once / denied for good / revoked while running (ARCH 7.5);
  table-tested per app. Special access (full-screen intent, exact alarm, battery optimisation, role) has one
  `SpecialAccess` interface with a fake; role checks use `RoleManager.isRoleHeld`.
- AC6 E3.8b The HANDOFF signature permission declarations and `uses-permission` lines are exactly those of the GPUI
  manifests (ARCH 7.2); a test lists them. Foreground-service start failures map to a UI state (ARCH 7.5 rules).
- AC7 E3.8a Camera capture uses `ACTION_IMAGE_CAPTURE` with a `FileProvider`-style `RovstiShareProvider` URI (no `CAMERA`
  permission); Photo Picker is used instead of storage permissions everywhere (`READ_MEDIA_*` are not requested).

### E3.9 `:wa`
- AC1 `WaRepository` (ARCH 7.4): link state, events, chats, messages, `wake()` on push, resync on gap.
- AC2 `WaCalls`: place, accept, reject, end, mute, speaker; audio record/play threads with voice-communication attributes.
- AC3 Fake gateway tests for link, resync and call state.
- AC4 `WaAvatars`: fetch plan from Rust, 429 back-off honoured, disk pruning, picture shown through Coil; fake gateway test.

### E3.10 WhatsApp settings screen
- AC1 Exactly two options when not linked: Link WhatsApp (QR or pairing code) / Connect with a key (paste field
  validates `rwk_` keys through Rust); gateway URL field with the URL rules.
- AC2 Connected: account line, Show connect key (mints fresh, copy, share, QR), key list with current marked and revoke
  (confirm), Link again after logout, Delete instance (two-tap confirm).
- Screenshots: `e3.10-wa-not-linked`, `e3.10-wa-linking-qr`, `e3.10-wa-pairing-code`, `e3.10-wa-connect-key-field`
  (with a pasted key and selection), `e3.10-wa-connected`, `e3.10-wa-key-list-menu-open`, `e3.10-wa-logged-out`.

### E3.11 `:shell`
- AC1 `RovstiRoot` (content -> chrome -> overlay host), base activity (edge-to-edge, `adjustResize`, predictive back,
  highest refresh mode), `ShellContainer` base, `routeFor(intent)` pattern.
- AC2 Permission request UI (rationale sheet, denied state row, open settings), common settings pages (Appearance
  Auto/Light/Dark, Haptics Off/Light/Normal, About with versions, Licences listing `THIRD_PARTY_NOTICES.md`).
- Screenshots: `e3.11-permission-rationale`, `e3.11-permission-denied-row`, `e3.11-settings-appearance`, `e3.11-settings-haptics`,
  `e3.11-licences`, `e3.11-licence-detail`, `e3.11-about`, `e3.11-licences-fs2-light`.

---

## E4. Contacts (`:contacts`) - DONE (R2)

Goal: parity with `apps/contacts` (roadmap "Rovsti Contacts", `util-contacts*.md` C1-C54), then polish.

Round plans: **R1** -> `_bmad-output/kotlin-ui/plans/round-10.md` (planned 2026-11-09 at base `ed92dda`; E3 R2 re-verified
done in code, all hard dependencies `done`): E4.1-E4.8 in 8 steps (`:contacts` module, manifest parity with a reusable
`MergedManifest` test helper, release signing from env, container/routes/migration; data layer `ContactsStore`/
`BookRepository`/`WaMarks` with new Rust ops `contacts.wa_marks`/`wa_card`/`wa_profile_lines`; list and search; card;
editor; photo + Trash; favorites (Convx reorderable port) + groups; select mode and close). Also fixes **F11** from the
E3.11 goldens: `e3.11-permission-denied-row`/`-rationale` demo host double-padded the sections (32-48 dp instead of the
16 dp gutter; `shell/src/test/.../PermissionTestSupport.kt:59-60`).

**R2** -> `_bmad-output/kotlin-ui/plans/round-11.md` (planned 2026-11-09 at base `7bf317d`; E4 R1 re-verified done in
code): E4.9-E4.16 in 8 steps (share/QR/shortcut with `QrCode` moved to `:design`; intake page, paste, file import, add
to existing; scan QR from image and CSV; duplicates/merge/Clean up; photo viewer actions and ringtone/voicemail/block;
My card, emergency, recently added/edited; full Settings with appearance/haptics prefs in Rust; polish and close E4).
Also fixes from the R1 goldens: **F12** menu items without glyphs (`e4.2-row-context-menu`), **F13** settings groups
touching (`e4.1-settings`), **F14** card numbers in mixed display formats (`e4.3-card-full`).

**F16 Evidence (round-12 rev 2 step 1, owner fix a):** `design/transition/PageBack.kt` (`PageBack`, `LocalPageBack`);
`RovstiNavHost` provides it around every entry with `StackKey.depth > 0` (pop / `select(currentTab)` for the long press, null
on tab roots) and `check`s that a pushed route is never `TabBarMode.Folded`. The fallback lives in `ScreenChromeBox`
(not `FrostedTopBar`, so `PhotoViewer`'s own bar is unaffected): explicit `onBack` wins, `showBack = false` (new on
`ScreenPage` / `ScreenLazyPage` / `ScreenScaffold` / `ScreenChromeBox`) hides it; `LargeTitleScaffold` takes the host's button as
its `navigation` so the title starts below it, and `SettingsPage` / `SettingsLazyPage` start the large title below the button
row (it used to run under the circle). `ContactsRoute.kt` / `PhoneRoute.kt`: every pushed page is `Visible`; only select mode on the
Contacts root stays `Folded`. Tests: `PageBackTest` (:design, 6: root none, pushed shows one and click pops, long press to root,
explicit wins, `showBack=false`, Folded on a pushed page fails), `ContactsRoutesTest` / `PhoneRoutesTest`
(`noNonRootRouteMapsToFolded`), `PushedPagesBackTest` in :contacts (11 pushed routes) and :phone (7) on the real `RovstiShell`
host: one `back-button` and all four tab labels. Goldens opened: `e4.15-name-format` (+ new `-rtl`), `e4.15-accounts-summary`
(+fs2), `e4.15-whatsapp`, `e5.1-settings`, `e5.1-diagnostics`: glass back circle top-left at the 16 dp gutter (mirrored chevron
at the right in RTL), large title below it, full four-tab bar in the first (light) frame of each test. Known harness
artifact (also in the committed `e4.15-settings-dark`): from the second frame of one test on, only the selected cell of the
Convx folding tab bar is drawn; the semantics tree still holds all four tabs. Not a product issue, left for the harness.

### Parity table (feature -> story)

| Features | Story |
|---|---|
| App, tabs (Contacts, Favorites, Groups, Settings), manifest, intake filters, shortcuts open card (AD-CT8) | E4.1 |
| Sectioned list, search (`ContactIndex`), C10 alphabet rail, C27 fuzzy, C41 more fields ("In details"), C51 recently viewed, no-results Create prefilled, C30 row quick actions, WhatsApp mark (AD-CT6) | E4.2 |
| Card: Call, Message, Email, Share, WhatsApp chat/call handoff (AD-CT5), presence; C5/C52 dates with age, C6 addresses, C17 WA chip per number, C18 websites/nickname/title, C21/C37 recent calls and SMS, C23 default number/email, C32 copy formats, C44 country, C46 phonetic and relation; field long-press menu (Copy, Share, Email, note copy); Open in Android Contacts | E4.3 |
| Editor: names, org, numbers, emails, addresses, dates, websites, relations, phonetic, note, groups, Save to account (AD-CT3), number-less contacts (AD-CT2), photo choose/remove/WhatsApp photo, C13 crop/rotate, C14 camera, discard confirm, C40 undo last edit | E4.4 |
| Delete with Undo, C20 Trash (30 days, Restore, Empty) | E4.5 |
| Favorites: star, C26 custom order, Move earlier/later, C42 frequent, C9 upcoming birthdays + Wish SMS, C50 .ics | E4.6 |
| Groups: create, rename, delete, members add/remove (picker), C25 message group, email group, export group | E4.7 |
| C11 select mode (select all, export, share .vcf, add to group, star, delete), C47 share as text, C53 copy numbers/emails | E4.8 |
| C4 share as text/.vcf/QR (Save/Share image), C12 export one .vcf, C16 copy whole / as vCard, C36 home screen shortcut | E4.9 |
| C7 vCard dates/ADR, C31 open/shared .vcf preview, import whole file with dedup, C28 clipboard import, C19 scan QR from image, C39 CSV export/import, C33 vCard PHOTO, C48 multi-card photos, C49 add to existing contact | E4.10 |
| C8 duplicates and merge, C24 / C54 Clean up filters | E4.11 |
| C1 photo viewer, C2 save/share photo, C3 change/remove from viewer | E4.12 |
| C34 ringtone and voicemail, C35 block/unblock | E4.13 |
| C38 My card, C43 emergency contacts, C15 / C45 recently added / edited | E4.14 |
| Settings: name format and sort, Save to account, C29 counts per account, C22 birthday reminders, WhatsApp, export all .vcf | E4.15 |
| Motion, shared element, checklist, RTL, font scale, perf | E4.16 |

| Story | Title | Round | Status |
|---|---|---|---|
| E4.1 | App module, manifest parity, container, tabs, routes, migration test | R1 | done (round-10) |
| E4.2 | Contacts list and search | R1 | done (round-10) |
| E4.3 | Contact card | R1 | done (round-10) |
| E4.4 | Editor | R1 | done (round-10) |
| E4.5 | Delete, Undo, Trash | R1 | done (round-10) |
| E4.6 | Favorites, frequent, upcoming birthdays | R1 | done (round-10) |
| E4.7 | Groups | R1 | done (round-10) |
| E4.8 | Select mode | R1 | done (round-10) |
| E4.9 | Share and shortcuts | R2 | done (round-11 step 1) |
| E4.10 | Import paths | R2 | done (round-11 step 2-3) |
| E4.11 | Duplicates, merge, Clean up | R2 | done (round-11 step 4) |
| E4.12 | Photo viewer on the card | R2 | done (round-11 step 5) |
| E4.13 | Ringtone, voicemail, block | R2 | done (round-11 step 5) |
| E4.14 | My card, emergency, recently added/edited | R2 | done (round-11 step 6) |
| E4.15 | Settings | R2 | done (round-11 step 7; owner fix (a) / F16: sub-pages without back button and with the bar folded to a lone gear, fixed by round-12 rev-2 step 1) |
| E4.16 | Polish | R2 | done (round-11 step 8) |

Shared AC for every E4 story: the GPUI behaviour named in the parity table is reproduced (same rules from Rust), every
list ends above the tab bar, one avatar per row, chip rows never wrap, every long-press opens a context menu from the
host, every destructive action confirms or offers Undo as the GPUI app does.

### E4.1 App module
- AC1 `applicationId dev.rovsti.contacts`, versionCode >= 100, release signing from env, component FQCNs and filters of
  the GPUI manifest (vCard SEND/VIEW, `RovstiShareProvider`), permissions READ/WRITE_CONTACTS, INTERNET, HANDOFF,
  READ_CALL_LOG, POST_NOTIFICATIONS (asked only on use, AD-CT7); manifest test against the committed list.
- AC2 Container, routes, `routeFor(intent)` for `EXTRA_CONTACT_ID` and shared files (tested).
- AC3 Migration test: fixture `contacts-prefs.json`, `contacts-trash.json`, `whatsapp-link.json` load.
- Screenshots: `e4.1-tabs-empty-book`.

### E4.2 List and search
- AC1 Letter sections, alphabet rail, search with fuzzy initials and number fragments, "In details" section, recently
  viewed (Clear with confirm), no-results row offering Create Contact prefilled, WhatsApp glyph (14 dp, once per row).
- AC2 Row long-press: context menu with Call, Message, Copy number, Share, Star, Select.
- Screenshots: `e4.2-list`, `e4.2-list-end-above-bar`, `e4.2-list-fs2`, `e4.2-list-rtl`, `e4.2-search-typing`,
  `e4.2-search-no-results`, `e4.2-rail-scrubbing`, `e4.2-row-context-menu`, `e4.2-recently-viewed`.

### E4.3 Card
- AC1 Header (avatar opens viewer, name, org), action capsules (Call, Message, WhatsApp when available, Email), field
  rows by kind with the C-features of the parity table, recent calls/SMS section behind its permission ask.
- AC2 Field long-press menu: Copy (formats), Share, Email, Set/Clear default, Open in maps (address), Open (website).
- AC3 Shared-element avatar from the list row.
- Screenshots: `e4.3-card-full`, `e4.3-card-minimal`, `e4.3-card-whatsapp-online`, `e4.3-field-menu-open`,
  `e4.3-card-recent-calls`, `e4.3-card-fs2`.

### E4.4 Editor
- AC1 All fields of the parity row; add/remove field rows; field labels picker (popover); account chooser; groups chooser.
- AC2 Photo: menu (Choose, Take photo, Use WhatsApp photo, Remove) -> crop/rotate screen -> save.
- AC3 Validation sentences from `rovsti_core::contact_edit`; discard confirm on dirty back; Undo pill after save.
- AC4 Every field is a `GlassTextField` (caret, selection, paste work); keyboard types per field (phone, email, url).
- Screenshots: `e4.4-editor-new`, `e4.4-editor-filled`, `e4.4-editor-keyboard-open`, `e4.4-editor-label-popover`,
  `e4.4-photo-menu`, `e4.4-crop`, `e4.4-discard-confirm`, `e4.4-validation-error`.

### E4.5 Delete and Trash
- Screenshots: `e4.5-delete-confirm`, `e4.5-undo-pill`, `e4.5-trash-list`, `e4.5-trash-empty`.

### E4.6 Favorites
- AC1 Favorites in the user's order; edit mode drag reorder with spring; Frequently contacted section; Upcoming birthdays
  section and page with Wish SMS and Add to calendar (.ics through the save picker).
- Screenshots: `e4.6-favorites`, `e4.6-favorites-reorder-drag`, `e4.6-upcoming-page`.

### E4.7 Groups
- Screenshots: `e4.7-groups`, `e4.7-group-page`, `e4.7-member-picker`, `e4.7-group-menu-open`.

### E4.8 Select mode
- AC1 Entered from the toolbar "Select" or a row menu; leading check circles (`select` spring); title from
  `contacts.select_bar` ("3 selected"); Cancel and Select All / Deselect All in the title capsule.
- AC2 Bottom glass toolbar as the folded tab bar's accessory: Share (text), Add to Group (chooser), Star, Delete
  (confirm, hidden rows, ONE undo pill) and a "..." menu (Export .vcf, Copy Numbers, Copy Emails). Back leaves select mode first.
- Screenshots: `e4.8-select-mode`, `e4.8-select-actions-menu`.

### E4.9 Share
- Screenshots: `e4.9-share-menu`, `e4.9-qr-sheet`.

### E4.10 Import
- AC1 Intake decides through `rovsti_core::contact_intake`; preview sheet "Add these contacts?" with counts and dedup;
  CSV mapping via `rovsti_core::contact_csv`; Add to existing contact picker with Undo.
- Screenshots: `e4.10-import-preview`, `e4.10-scan-result`, `e4.10-add-to-existing-picker`.
- Evidence (round 11 step 2): `intake/IntakeViewModel` + `IntakeScreen` replace the placeholder; sources are the VIEW/SEND
  `Intake(uri)` route, Settings "Import from a File" and "Paste Contact" (clipboard read on tap, "Nothing to paste" toast).
  `IntakeViewModelTest` (13, real core), `IntakeScreenTest`, `ContactsRoutesTest` (VIEW). Goldens `e4.10-import-preview`,
  `e4.10-add-to-existing-picker`, `e4.10-card-preview`, `e4.10-paste-nothing-toast` (light and dark) opened: one avatar per
  row, sheet floats, last row above the buttons, Cancel is plain accent text (a glass capsule at the sheet bottom smeared).

### E4.11 Duplicates and Clean up
- Screenshots: `e4.11-duplicates`, `e4.11-merge-preview`, `e4.11-cleanup-chips`.
- Evidence (round 11 step 4): `dupes/DupesViewModel` + `DupesScreen` (groups from `contacts.dupes` once per book on Default, `merge_plan` preview in a Large sheet, merge saves the keeper then `ContactTrash.delete` for the losers, ONE undo pill for one group or Merge All) and `cleanup/CleanupViewModel` + `CleanupScreen` (one `ChipRow`, `SelectController` select mode and delete path of E4.8); Settings rows "Find Duplicates" / "Clean Up", routes `Duplicates` / `Cleanup`. Tests `DupesViewModelTest` (7, real core), `CleanupViewModelTest` (6), `DupesScreenTest`, `CleanupScreenTest` (chips scroll, one line at fs2). Goldens `e4.11-duplicates`, `-merge-preview`, `-cleanup-chips`, `-cleanup-chips-fs2`, `-cleanup-select` (light and dark) opened: one avatar per row, sources one text line, chips one line, title-bar buttons in ONE capsule (two capsules overlapped).

### E4.12 Photo viewer
- Screenshots: `e4.12-viewer`, `e4.12-viewer-menu`.
- Evidence (round 11 step 5): `card/CardPhotoViewer` opens `showPhotoViewer` with `ViewerPhoto(full = display_photo, thumb = avatar uri)` (thumbnail first, full fades in; 500 ms flight from the avatar rect); `photo/PhotoViewerActions` gives Save (gallery once, toast), Share (staged JPEG) and the Edit `IosMenu` (`ViewerAction.menu`, reuses `photoMenuItems`; Remove asks first). Tests `PhotoViewerActionsTest` (10). Goldens `e4.12-viewer`, `e4.12-viewer-menu` opened.

### E4.13 Ringtone, voicemail, block
- Screenshots: `e4.13-card-options`, `e4.13-voicemail-confirm`, `e4.13-block-confirm`, `e4.13-block-needs-role`.
- Evidence (round 11 step 5): `card/CallOptionsViewModel` + `CallOptionsSection` (Ringtone row via `PickRingtone` result contract, voicemail `ListRowSwitch` with confirm only when turning on, Block/Unblock for every number through `BlocklistRepository`, role sentence + role settings when `canBlock` is false). Tests `CallOptionsViewModelTest` (12, real core), `CallOptionsSectionTest` (6, section never moves when the switch flips). Goldens opened.

### E4.14 My card, emergency, recent
- Screenshots: `e4.14-my-card`, `e4.14-emergency-list`, `e4.14-added-edited-tabs`.

### E4.15 Settings
- Screenshots: `e4.15-settings`, `e4.15-settings-fs2`, `e4.15-accounts-summary`, `e4.15-whatsapp` (reuses E3.10).

### E4.16 Polish
- AC1 Checklist pass; recomposition test for list scroll and search typing; motion audit (springs retarget, no layout
  shift on press); contact sheet `docs/kotlin-ui/screenshots/contacts.png`.

---

## E5. Phone (`:phone`)

Goal: parity with `apps/phone` (roadmap "Rovsti Phone", `util-phone*.md` U1-U56, `phone.md`), then polish.

**R1** -> `_bmad-output/kotlin-ui/plans/round-12.md` (planned 2026-11-09 at base `c34b155`; E4 R2 re-verified done in
code): E5.1-E5.7a in 8 steps (module, manifest, components, setup sheet, diagnostics, migration; data layer; keypad
with `DialField` in `:design`; Recents; person/number/call-details sheets; Favorites and Contacts tabs; Incoming with
the Convx-ported call aura; In-call single call and round close). **E5.8 moved to R2** (needs both call screens; the
screens take a `CallSource` from R1). Check from the E4 goldens: **F15** strong lens blob at the tab bar's leading cap
(`e4.15-settings-light`, `e4.14-my-card-dark`): compare lens values with Convx `IosTabBar` in Step 1.
**F15 closed (round-12 step 1):** Convx's `ui/ios/component/IosTabBar.kt` has no lens (it is the plain bar); the lens lives in
Convx's `ui/component/floatingtabbar/FloatingTabBarTabs.kt` (bar `lens(24dp, 24dp)` with `blur(4dp)`; pressed puck
`lens(10dp x progress, 14dp x progress)`, `chromaticAberrationAmount 0.5`) and `FoldingTabGroup.kt` (`lens(10dp x
progress, 14dp x progress, chromaticAberration)`). Ours (`chrome/floatingtabbar/FloatingTabBarTabs.kt` lines 429 and
492-494, `FoldingTabGroup.kt` line 261) carry the same numbers, so nothing changed and no golden was re-recorded: the blob
is the real backdrop path, Convx's own look.
**Round-12 revision 2** (re-planned at base `36760c7`, same file `plans/round-12.md`): Step 1 is **F16** (owner fix (a)):
the pushed settings pages of Contacts (`e4.15-name-format-*`, `e4.15-accounts-summary-*`) and Phone (`e5.1-settings-*`,
`e5.1-diagnostics-*`) show no back button and the tab bar folded into a lone circle. Causes: `chromeFor` maps pushed
pages to `TabBarMode.Folded` (`ContactsRoute.kt:179-199`, `PhoneRoute.kt:112-119`), and the back button depends on each
caller passing `onBack`, which the screenshot hosts do not (`E415ShotTest.kt:51-68`, `PhoneHost.kt:219-226`). Fix: the
nav host provides `LocalPageBack` to every entry with depth > 0, `FrostedTopBar` falls back to it, pushed pages keep the
full bar (Convx keeps `AppFloatingNavBar` on pushed routes, `AppRootContent.kt:1172-1180`), guarded by
`PushedPagesBackTest` and a Folded-only-on-roots invariant. Step 2 finishes the uncommitted data layer; Steps 3-8 are
unchanged (E5.2-E5.7a).

**R2 part 1** -> `_bmad-output/kotlin-ui/plans/round-13.md` (planned 2026-11-10 at base `ca993e6`; E5.1-E5.4, E5.6, E5.7a
re-verified in code): 8 steps: recover E5.5 from `stash@{0}` + Phone Turkish (`values-tr/strings.xml` missing, Diagnostics
labels hard-coded); **F21** the dark frame of pushed-page goldens (`e5.1-settings-dark`) draws only the selected tab cell:
root-cause and fix, not a harness excuse; F17-F19 + F20 ringing on Incoming (still open in `e5.6-incoming-light`: raw
`+905551112233`, stroked handsets, green-on-green avatar, discs behind the secondary actions) in the iOS 26 layout; F20
in-call frosted controls (still dark opaque discs in `e5.7-incall-active-dark`) and the caller photo background;
slide to answer when locked; E5.8a WhatsApp call audio loopback proof; E5.8b WhatsApp call UI; E5.7b multi-call and
round close. E5.9-E5.14 follow in round 14.

### Parity table

| Features | Story |
|---|---|
| App, ROLE_DIALER, manifest (DIAL, VIEW tel, InCallService, receivers, FCM flavor, share provider), tabs (Favorites, Recents, Contacts, Keypad), setup sheet checklist (`rovsti_core::setup`), hidden Diagnostics page, notice line | E5.1 |
| Keypad: number field (paste works), T9 suggestions, speed dial (long-press digit), voicemail on 1 (glyph, "No voicemail number set"), U6 pause/wait, U14/U53 field menu (Copy, Paste, Clear+Undo, Share, Copy as +E.164), U29 add/create contact, U31 redial, U32 smart paste chooser, U47 long-press Call: SIM / WhatsApp, Message, WhatsApp chip, keypad tones, dual-SIM chooser sheet when no SIM is remembered (`sim_ui`, `sim-choices.json`) | E5.2 |
| Recents: grouped by person, missed badges, U15 date sections, U3 search + filter chips (All, Missed, Incoming, Outgoing, WhatsApp) + U35 With notes, U21 notes search, U39 mark missed seen, row menu (call back, message, WhatsApp call, U20 quick reply, U13 remind later, U25/U56 copy number/country, U4 copy/share as, add to contacts, block, delete with Undo), U12/U45 select mode (delete, block, share), U48 country of unknown number, WhatsApp calls with badge, SIM chip on rows of a dual-SIM phone (S26), Block flow with confirmation and Undo (`block_ui`, S27) | E5.3 |
| Contact/Number sheets: quick actions (Message, Copy, Share, Block), history lines, U11 call details (copy, share, U8 note, delete this call, U44 remind me), U7 per-person stats, U16 export person CSV, U18 share stats, U26 share details, U36 copy all numbers, U9 QR (`qr.encode`), Delete call history confirm sheet (`history_ui`), per-person quick reply and CSV export (`person_ui`) | E5.4 |
| Favorites (order, drag, Move earlier/later, Frequent) and Contacts tab (search, sections, open sheet) | E5.5 |
| Incoming: full screen and lock screen, answer/decline, decline with message (reply sheet), silence, waiting-call card, answer replacing/holding/ending held, CallStyle notification first, first-frame report, native fallback | E5.6 |
| In-call (basics, E5.7a): mute, audio route picker (earpiece, speaker, Bluetooth, endpoints), keypad (DTMF, U49 paste tones + Stop, U50 copy digits, U51 remember tones), hold, timer, U17 note + copy number, U37 copy timer / share summary, proximity, SIM chip on the call screen, post-call notice pill with Block / Undo (`notice.rs`, 17.3-17.4), aura and shared-element moments S3, S4, S6, S8 (`aura`, `flight`) | E5.7a |
| In-call multi-call (E5.7b): Add call sheet (`addcall`: keypad, Favorites, Contacts without leaving the call), swap, merge, split, end all, Held card, conference panel, one/two/three-call faces and S19-S22 moments (`zone`, `zone_ui`) | E5.7b |
| WhatsApp calls: CallSource WhatsApp on Incoming/In-Call (Mute, Speaker, End; others dimmed with the sentence), arbitration, place from Recents/sheet/keypad chip, FCM wake to Incoming, answer from notification after cold start, call_end, missed WA calls in Recents, snapshot re-sync (AD-WA5, AD-WA10) | E5.8 |
| Settings: Blocked (list, U34 paste many, U55 prefix range, U27 share/unblock all + Undo, U38 export/import file, blocked calls), quick replies, speed dial page (U22 share, clear + Undo), WhatsApp, SIM choices, haptics, Data (U5 export CSV with U19 note column, U41 export names, U43 delete older, U24 delete all + Undo, U54 export contacts .vcf), prefs backup/restore | E5.9 |
| U7 statistics page (this week, last 7 days, last 8 weeks, top people), U28 CSV, U40 share, U46 tap a day, U52 records | E5.10 |
| U30 notes page (search, open call, delete + Undo), U33 share all, U42 export CSV | E5.11 |
| Call reminders (U13 notifications), missed-call notification actions, setup flows (role request, Xiaomi background settings, battery optimisation, full-screen intent) | E5.12 |
| U1 photo viewer, U2 save/share, U10 set/replace/remove photo | E5.13 |
| U23 haptic and pill audit, theme melt and setup-complete moments, checklist, perf | E5.14 |

| Story | Title | Round | Status |
|---|---|---|---|
| E5.1 | App module, role, manifest parity, tabs, setup sheet, diagnostics, migration test | R1 | done (`36760c7` module, manifest, components, container, routes, setup sheet, diagnostics, migration; `447ee0d` F16 back button/full bar; round-12 step 2 data layer) |
| E5.2 | Keypad | R1 | done (round-12 step 3; Evidence: `KeypadLayoutTest`, `KeypadViewModelTest`, `KeypadScreenTest`, `e5.2-*` goldens) |
| E5.3 | Recents | R1 | done (round-12 step 4) |
| E5.4 | Contact, Number and Call details sheets | R1 | done (round-12 step 5; Evidence: `PersonSheetViewModelTest`, `CallDetailsViewModelTest`, `e5.4-*` goldens) |
| E5.5 | Favorites and Contacts tabs | R1 | done (recovered from `stash@{0}` in round-13 step 1; Evidence: `FavoritesViewModelTest`, `FavoritesScreenTest`, `PhoneContactsViewModelTest`, `PhoneContactsScreenTest`, `PhoneStringsTest`, `e5.5-*` goldens incl. `e5.5-favorites-tr`) |
| E5.6 | Incoming call | R1 | done (round-12 step 7; Evidence: `IncomingViewModelTest`, `IncomingScreenTest`, `CallRegistryIncomingTest`, `e5.6-*` goldens). Open owner follow-ups F17-F20, photo background, slide to answer: round-13 steps 3-5 |
| E5.7a | In-call: single call | R1 | done (F20 in-call frosted controls and photo background: round-13 step 4; round-12 step 8; Evidence: `InCallViewModelTest`, `RoutePickerTest`, `InCallScreenTest`, `e5.7-*` goldens, `docs/kotlin-ui/checklist-e5.md`) |
| E5.8 | WhatsApp calls | R2 | done (E5.8a audio proven in software, round-13 step 6; E5.8b call UI, round-13 step 7; owner acceptance item: the manual audio check on the real phone) |

> **Recovery note (RECOVERED in round-13 step 1; branch `wip-r12-s6` deleted, `stash@{0}` kept until the round closes):** round-12 step 6 (E5.4 sheets wiring / E5.5
> Favorites and Contacts tabs) was implemented but stashed as `stash@{0}` "blocked: kotlin-ui r12 S6" (also kept as
> local branch `wip-r12-s6`). It was blocked only because the old debug check took 40-70 min and the haiku test agents
> returned before it finished, not because of failing code. The stash also captured another agent's edits that are
> ALREADY COMMITTED in 5f51e2c (CLAUDE.md, Cargo.toml, build-logic, scripts/*, contacts/core-bridge/phone
> build.gradle.kts, QrSheetTest.kt): do not re-apply those. Recover only the Phone files (android/phone/src/**,
> android/phone/screenshots/e5.5-*, phone strings.xml), `android/design/src/main/kotlin/dev/rovsti/design/list/Reorder.kt`,
> `android/design/build.gradle.kts`, `android/contacts/.../favorites/FavoritesReorder.kt` and
> `android/testing/.../ComposeActivity.kt`, then run the (now release-only) checks. Phone strings also need values-tr
> (definition of done item 6).
| E5.7b | In-call: multi-call, Add call, conference | R2 | todo (planned in round-13 step 8) |
| E5.9 | Settings | R2 | todo |
| E5.10 | Statistics | R2 | todo |
| E5.11 | Notes page | R2 | todo |
| E5.12 | Reminders, notifications, setup flows | R2 | todo |
| E5.13 | Photo viewer and photo edit | R2 | todo |
| E5.14 | Polish | R2 | todo |

### E5.1 App module
- AC1 `dev.rovsti.phone`, arm64-v8a release, every component FQCN of the GPUI manifest (`MainActivity` with DIAL/VIEW
  filters and the activity-alias, `RovstiInCallService`, `CallActionReceiver`, `VoicemailReceiver`,
  `CallReminderReceiver`, provider, firebase `RovstiMessagingService`); role request flow; manifest test.
- AC2 Migration test with fixture `prefs.json` (PrefsV2), `wa-calls.json`, `call-notes.json`, `whatsapp-link.json`
  (Phone has no `sim-choices.json`: that is the Messages legacy SIM file, `rovsti_app_core/src/messages/prefs.rs:22`).
- Screenshots: `e5.1-setup-sheet`, `e5.1-diagnostics`, `e5.1-notice-line`, `e5.1-tabs` (+ `e5.1-settings`, `e5.1-setup-sheet-fs2`).
- Evidence (step 1): `PhoneManifestTest` (both flavors), `PhoneRoutesTest`, `PhoneMigrationTest`, `InCallServiceTest`, `SetupSheetViewModelTest` (real core) pass. Setup sheet scrolls and stacks buttons at 2x font; notice line sits in its own card under the Recents title. Open item (closed in round-13 step 7: `phone.wa_handoff_number`): WA hand-off JID to number needs a `rovsti_ffi` op (`wa_call::handoff_number`).

### E5.2 Keypad
- AC1 Keypad layout from Kotlin pure layout maths (not `rovsti_core::keypad_layout`), sized to the viewport; digits are
  press-feedback buttons with tones; the number is a `BasicTextField` with the soft keyboard suppressed, caret and
  selection working, paste working.
- AC2 T9 suggestion list above the keypad from the `ContactIndex` handle.
- Built (round-12 step 3): `:design` `text/DialField.kt` (`BasicTextField(TextFieldState)` under `InterceptPlatformTextInput`,
  36 to 24 sp fit, `DialAlphabet`, caret edit helpers), `RovstiIcons.Backspace/Voicemail`, `HapticEvent.KeyboardTap`;
  `:phone` `keypad/` (`KeypadLayout`, `KeypadViewModel`, `KeypadScreen`, `DialKeys`, `KeypadStrings`). Tests:
  `DialFieldTest`, `KeypadLayoutTest`, `KeypadViewModelTest` (real core), `KeypadScreenTest`, `E52ShotTest`.
- Screenshots: `e5.2-sim-chooser-sheet`, `e5.2-keypad-empty`, `e5.2-keypad-typing-t9`, `e5.2-keypad-field-menu`, `e5.2-keypad-selection`,
  `e5.2-call-longpress-menu`, `e5.2-smart-paste-chooser`, `e5.2-keypad-fs2`, `e5.2-keypad-small-screen`.

### E5.3 Recents
- Evidence (round-12 step 4): `RecentsViewModelTest` (13, real core), `RecentsScreenTest` (13), `E53ShotTest` (11 shots, light and dark, opened). Added `recents.section_starts` `labels` (`now_ms`) in the core, `TabItem.badge`. Delete is log-only until E5.8 (WhatsApp rows disabled); "Remind me later" is an action sheet, not a submenu.
- Screenshots: `e5.3-recents-dual-sim-chip`, `e5.3-block-confirm`, `e5.3-recents`, `e5.3-recents-end-above-bar`, `e5.3-recents-missed-filter`, `e5.3-recents-chips-scrolled`,
  `e5.3-recents-row-menu`, `e5.3-recents-select-mode`, `e5.3-recents-search`, `e5.3-recents-whatsapp-badge`.

### E5.4 Sheets
- Screenshots: `e5.4-contact-sheet`, `e5.4-number-sheet`, `e5.4-call-details`, `e5.4-call-note-editing`,
  `e5.4-person-stats`, `e5.4-qr`.

### E5.5 Favorites and Contacts tabs
- **(F24) Owner, Phone search focus:** tapping a search result opens its sheet (good), but the search field stays
  focused and the keyboard stays up behind the sheet. When a result's sheet opens, clear focus and hide the keyboard;
  when that sheet closes (dismissed back to the results), give the field focus again and bring the keyboard back with
  the caret at the end, so the owner can keep typing. Same rule for the Recents search and any search that opens a sheet.
  Compose test: type, tap result -> IME hidden and field unfocused; dismiss -> field focused and IME shown.
- Screenshots: `e5.5-favorites`, `e5.5-favorites-empty`, `e5.5-favorites-reorder`, `e5.5-favorites-tr`, `e5.5-contacts-tab`, `e5.5-contacts-search`.
- **Done (round-13 step 1).** Evidence: the tests above, Phone `values-tr/strings.xml` (all strings, `PhoneStringsTest` checks keys, placeholders and untranslated copies), Diagnostics labels moved to `@StringRes` (`DiagnosticsLabels`), goldens opened: last row above the bar, 16 dp gutters, Turkish labels unclipped.

### E5.6 Incoming
- **Owner direction (2026-11-10): take the call screens' design from iOS freely.** For the incoming, locked-incoming
  and in-call screens, use the iOS 26 Phone call UI as the reference for layout, spacing, type scale (large thin name,
  small secondary line), control grid, slide to answer, frosted controls over the caller photo or gradient, and the
  transitions (ringing -> answered morph, controls fading in, end-call shrink). Build it with our own Compose code and
  icons (no Apple assets or SF Symbols), and keep Android behaviour where it matters (notification heads-up, foreground
  service, audio routes, Bluetooth).
- **Screenshot fixes from the coordinator (2026-11-10, e5.6 goldens of f7023e1), plan them first:** (F17) the number
  under the name is raw E.164 (`+905551112233`); show it formatted like everywhere else (`+90 555 111 22 33`, the shared
  number format op, F14). (F18) Answer and Decline use stroked handsets; use the filled white handset the keypad call
  button uses (owner: call icons are filled), Decline rotated 135 deg. (F19) The initials avatar is green on the green
  background and nearly vanishes; give it a contrasting fill or a light ring.
- **Evidence (round-13 step 3, F17-F19, F20 ringing: done):** F17 `phone/.../data/NumberDisplay.kt` (Rust `NumberForm.Display`, used by
  `IncomingViewModel`, `InCallViewModel`, the waiting card and the post-call pill; `IncomingViewModelTest`,
  `InCallViewModelTest` assert `+905551112233` -> `+90 555 111 22 33`). F18 `RovstiIcons.PhoneFill` (`:design`), Answer, Decline
  and End on solid 76 dp green/red discs. F19 `Avatar(onAura = true)`: white 22 % fill, 0.5 dp white 40 % rim. F20 ringing
  `:design` `controls/PlainIconAction.kt` (`PlainIconActionTest`, `IncomingScreenTest` no disc / 0.5 press / >= 48 dp);
  iOS 26 layout (Light 36 sp name shrinking after two lines, 96 dp avatar, discs at 1/4 and 3/4). Goldens `e5.6-*` re-recorded
  and opened.
- **Evidence (round-13 step 5, slide to answer on the locked screen: done):** `:design` `controls/SlideToAnswer.kt`
  (CallControl glass capsule 76 dp, 68 dp green handle, 80 % / fling answer via `Fling.restingPoint`, spring 0.75/500 back,
  `HapticEvent.ThresholdTick` once and re-armed below 70 %, RTL mirrored, custom action + click for TalkBack;
  `SlideToAnswerTest`). `LockScreenGate.locked` (`KeyguardManager.isKeyguardLocked`, refreshed on resume and live-call
  change); `IncomingViewModel.onRingKey` + `MainActivity.onKeyDown` (first power/volume press silences, second declines;
  `IncomingViewModelTest`); locked layout in `IncomingScreen` (`IncomingScreenTest`). Goldens `e5.6-locked-slide-rest`,
  `-mid`, `-rtl`, re-recorded `e5.6-incoming-lockscreen-variant` (light + dark), opened.
- **(F20) Owner: call-screen secondary buttons, as on iOS (revised 2026-11-10).**
  - **Ringing (incoming) screen:** Remind me / Message / Silence get **no background at all**: just the white glyph with
    its label under it, the whole icon+label area is the touch target (min 48 dp), pressed state dims to 50%. Answer and
    Decline stay solid green/red discs.
  - **In-call screen after answering (E5.7a):** the control grid (mute, keypad, speaker, add call, video, contacts) uses
    translucent **frosted glass** discs: white tint about 18-22% alpha in both themes (the call background is always a
    deep colour), real backdrop blur of the gradient (`rovstiGlass` button preset, light tint, no outer shadow), 0.5 dp
    light rim, white glyphs; an active toggle (mute on, speaker on) becomes solid white with a dark glyph. End call stays
    solid red.
- **Owner request (2026-11-10): the caller's photo is the call background.** When the caller has a contact photo (or a
  WhatsApp profile photo for WA calls), the incoming and in-call screens use it full-bleed as the background: scaled to
  fill (centre-crop, face-aware if cheap), softly blurred (about 30-40 dp) and darkened with a vertical scrim (top 25%,
  bottom 55% black) so white text and the frosted buttons stay readable (WCAG 4.5:1 for the name). The small avatar
  circle is then hidden (the photo is the background). No photo: keep the current colour gradient. Load it off the main
  thread at the right size (Coil, cached), cross-fade in, never delay showing the ringing screen for it. Screenshots:
  `e5.6-incoming-photo` and `e5.7-incall-photo`, light and dark.
- **Owner request (2026-11-10): slide to answer when the phone is locked.** When the incoming call shows over the lock
  screen (screen was off or keyguard locked: `KeyguardManager.isKeyguardLocked`), replace the Answer/Decline buttons
  with an iOS-style "slide to answer" track: a glass capsule with a green handle (filled white handset) that follows the
  finger, answers when dragged past ~80% (spring back below that, haptic tick at the threshold), fling-aware, works for
  accessibility (a double-tap action "Answer" plus TalkBack hint), RTL mirrored. Remind me / Message stay above it;
  decline on the locked screen is the power/volume key (silence, then decline on second press) plus a small "Decline"
  under the track. Unlocked phones keep the two buttons. Screenshots: `e5.6-locked-slide-rest`, `e5.6-locked-slide-mid`
  (light and dark). Compose test with `mainClock.autoAdvance = false` for threshold and spring-back.
- AC1 The notification path works without the UI (JVM test of `CallRegistry`); the Compose screen shows from the same
  snapshot; first-frame report logged; native fallback kept.
- Screenshots: `e5.6-incoming`, `e5.6-incoming-lockscreen-variant`, `e5.6-decline-with-message-sheet`,
  `e5.6-waiting-call-card`.

### E5.7a In-call (single call)
- **Coordinator screenshot fixes (2026-11-10, goldens of c0ffc3c):** (F22) the in-call screen still shows the raw
  E.164 number (`+905559990000`) under the name; use the shared number format like the incoming screen (F17). (F23)
  with the in-call keypad open, the name ("Mert Kaya") is clipped at the bottom by the dialled digits line; give the
  digits line its own row under the name with the standard gap, or shrink the name to the compact style while the keypad
  is open.
- AC1 Aura is an ambient animation obeying ARCH 10 rule 5; route picker lists endpoints and tolerates a missing
  `BLUETOOTH_CONNECT` grant (ARCH 7.5); the screen is shown over the lock screen only while a call is live.
- Screenshots: `e5.7-incall-active`, `e5.7-incall-keypad`, `e5.7-route-picker-popover`, `e5.7-incall-sim-chip`,
  `e5.7-incall-ended-summary`, `e5.7-post-call-block-pill`.

### E5.7b In-call (multi-call)
- AC1 Multi-call scenes from `rovsti_core::scene` through the bridge; springs of hold-away, swap, merge.
- Screenshots: `e5.7-incall-hold-two-calls`, `e5.7-incall-conference`, `e5.7-add-call-sheet`.

**F21 Evidence (round-13 step 2):** `:design` `TabBarCellsTest` (ShotTest harness, `RovstiNavHost` with the Recents tab and a
`ScreenPage` pushed, light then dark in one test, pixel contrast of each of the four cell label bands; `:design` now has
`testImplementation(project(":testing"))`). It failed at `ca993e6` (favorites band contrast 0.14 = bar fill only) and passes
now. Cause, narrowed by bisection (not by the plan's guess): not `fold`/label alpha (both read 1.0), not the glass, the
puck or the cell draw (the cells did run their draw in the bad frame). Any `ScreenPage` whose draw-phase lambdas
(`collapse`, `titleAlphaProvider`: TopFadeBlur and bar-scrim alpha, FrostedTopBar title alpha) read the snapshot state
`titleBlockHeight` that `onSizeChanged` writes in the first layout made the tab bar drop its unselected cells in a later
frame; a constant, a dummy state or a scroll-only read did not. Those layers sit inside the root's recorded backdrop, so the
late invalidation re-records the page and the samplers over it mid-frame. Fix: `design/chrome/ScreenChrome.kt`
`titleBlockHeight` is a plain `IntArray(1)` holder (same pattern as `BackdropFreeze.scrollClockNs`); the scroll value still
drives the redraw. Not harness-caused (the first composition was always fine and a second one broke only with that read), so
`ShotTest` is unchanged. Re-recorded goldens opened: `e5.1-settings-dark`, `e5.1-diagnostics-dark`, `e4.15-settings-dark`,
`e4.15-name-format-dark` show all four labelled cells. Residual: `LargeTitleScaffold.titleHeightPx` has the same shape
(state written on size, read in draw); not changed here, no golden shows the symptom.

### E5.8 WhatsApp calls
- **Owner-reported bug in the GPUI app (2026-11-10), must not come back:** WhatsApp calls could be answered, declined
  and ended, but **no audio flowed either way** (the owner heard nothing and was not heard), while normal calls worked.
  Before building the UI, trace the whole audio path and prove it end to end: mic capture (AudioRecord, VOICE_COMMUNICATION,
  sample rate/frames the gateway expects) -> `RustNative.audioPush` -> `rovsti_wa_client` call audio socket -> gateway
  (services/wa_gateway call audio, the 20 ms frame rechunk of commit 457ac0f) -> WhatsApp; and back: gateway ->
  `audioPull` -> AudioTrack (USAGE_VOICE_COMMUNICATION) with the audio mode/route set (MODE_IN_COMMUNICATION, speaker
  and earpiece, Bluetooth SCO). Check sample format and rate on every hop, the start order (audio session opened only
  after the call is connected), mic permission and audio focus, and that the foreground service type includes
  microphone. Acceptance: an automated loopback test with the fake gateway that pushes a tone in and gets it out on the
  far side (both directions), plus a manual check on the owner's phone listed in the evidence with gateway logs.
- **Status: done** (round-13 step 7; the manual audio check below stays an owner acceptance item). E5.8a (audio chain, no UI) is done in software; E5.8b (call screens) is done.
- **Evidence (E5.8a, round-13 step 6):** `docs/kotlin-ui/wa-call-audio.md` lists every hop and format (16 kHz mono PCM16,
  960 samples / 1 920 bytes / 60 ms; the gateway rechunks the engine's 20 ms frames). Tests: `rovsti_wa_client/tests/call_audio.rs`
  (fake gateway WebSocket, 1 kHz tone both directions, frame size and Goertzel energy), `wa_gateway` `call_audio_rechunks_20_ms_engine_frames_into_60_ms_app_frames`,
  `WaCallAudioTest` (Robolectric: rate, format, usage, mode, focus, service types, audio opens only after the call is connected), `WaCallsTest`,
  `PhoneManifestTest` (service type `microphone|phoneCall`). Fixed: incoming calls opened audio at accept instead of
  when connected; no audio focus; no Bluetooth route; no microphone foreground service (`WaCallService`).
- **Owner item (manual, real phone):** place and answer a WhatsApp call between two accounts; check both directions
  of audio, earpiece / speaker / Bluetooth, music pausing, background and screen-off operation, and gateway logs
  (checklist at the end of `wa-call-audio.md`).
- **Evidence (E5.8b, round-13 step 7):** the core ops `phone.wa_arbitrate`, `phone.wa_screen` (enabled controls and the
  sentence "Not available on WhatsApp calls") and `phone.wa_handoff_number` (`rovsti_ffi/src/ops/phone/wa_screen.rs`, fixtures
  `phone.wa_*`, `OPS.md`). Kotlin: `phone/.../wa/WaCallSource.kt` folds the WhatsApp call into the registry snapshot (id from `WaIdBook`,
  the core's arbitration decides who owns the screen), `WaCallsRouter` routes the screens' commands (answer, decline, end, mute,
  speaker, "End & Answer" both ways) to `WaCalls`, `WaCallLauncher` places calls (Recents row menu, More menu of the person sheet,
  keypad long-press Call menu, sibling-app hand-off), `WaCallActions` takes Answer/Decline from the notification once and only after
  the core is ready. `WaCalls` re-reads the calls when the link reconnects and keeps deleted calls away (`ForgottenCalls`).
  Screens: "WhatsApp audio" with the 14 dp `SourceGlyph` on Incoming, In-Call and the waiting card; Keypad, Hold, Add call, Note dimmed
  at 45 % with the core's sentence as a toast; the WhatsApp profile picture as the call background through `WaAvatars`.
  Tests: `WaCallFlowTest` (real core arbitration; ring, answer, audio opened, end; decline; arbitration both ways; busy decline once;
  cold-start answer waits), `WaCallActionsTest`, WA cases in `IncomingViewModelTest`, `InCallViewModelTest`, `RecentsViewModelTest`,
  `IncomingScreenTest`, `InCallScreenTest`, `PhoneContainerTest`. Goldens `e5.8-*`. R8: no new class is reached by name (FCM service
  is a manifest component, `WaPush` reflects Firebase classes that its consumer rules keep); checked with `dexdump` on the firebase
  release APK (see the step summary).
- Screenshots: `e5.8-wa-incoming`, `e5.8-wa-incoming-photo`, `e5.8-wa-incall-dimmed-controls`, `e5.8-wa-waiting-during-cellular`.

### E5.9 Settings
- Screenshots: `e5.9-settings`, `e5.9-blocked-list`, `e5.9-block-paste-many`, `e5.9-quick-replies`, `e5.9-speed-dial`,
  `e5.9-data-delete-older`, `e5.9-settings-fs2`.

### E5.10 Statistics
- Screenshots: `e5.10-stats-week`, `e5.10-stats-day-tapped`, `e5.10-records`.

### E5.11 Notes page
- Screenshots: `e5.11-notes`, `e5.11-notes-search`.

### E5.12 Reminders and setup flows
- Screenshots: `e5.12-remind-later-menu`, `e5.12-xiaomi-setup-rows`, `e5.12-full-screen-intent-revoked-row`.

### E5.13 Photo viewer
- Screenshots: `e5.13-viewer`, `e5.13-photo-edit-menu`.

### E5.14 Polish
- AC1 Every utility has a pill and a haptic (U23 audit table in the story notes); checklist pass; recomposition tests for
  keypad typing and Recents scroll; contact sheet `docs/kotlin-ui/screenshots/phone.png`.

---

## X. Cross-app owner requests (2026-11-10): plan these right after round 13, before E6

| Story | Title | Round | Status |
|---|---|---|---|
| X.1 | WhatsApp profile photo as the fallback avatar (Contacts and Phone) | next | todo |
| X.2 | Share cards rendered with Takumi (birthday card first), Contacts | next | todo |

### X.1 WhatsApp photo fallback avatar
- When a contact has **no photo of its own**, Contacts and Phone show the WhatsApp profile photo of the contact's
  WhatsApp number instead of the initials (lists, card, favorites, recents, call screens incl. the photo background).
- It is a **fallback only**: never written into the Android contact (the existing editor action "Use WhatsApp Photo"
  stays the explicit way to adopt it). Stored in a separate app-private cache shared by both apps through the Rust core
  (`rovsti_app_core`, reuse `wa_link` avatar plan / names from E2.4): keyed by WhatsApp JID and picture id, with the
  picture id from the gateway's profile events so a changed photo refreshes and a removed one is dropped; size-capped
  (LRU), readable offline, refreshed in the background (never blocks a list or the ringing screen).
- Only for numbers that resolve to a WhatsApp account the linked instance can see; no lookups when WhatsApp is not
  linked. A small WhatsApp glyph is not added on top (keep the avatar clean).
- Tests: Rust cache (insert, refresh on picture-id change, eviction, offline read); Kotlin: contact with photo keeps it,
  without photo shows the WA image, unlinked shows initials. Screenshots `x1-wa-avatar-list`, `x1-wa-avatar-card`.

### X.2 Share cards with Takumi
- Use **Takumi** (Rust, `takumi` crate, MIT OR Apache-2.0, https://github.com/kane50613/takumi; pin a release at least
  two weeks old, check its current API on docs.rs) inside the Rust core to render designed cards from node trees to
  **JPEG** (and PNG where transparency matters). New module `rovsti_app_core::cards`, ops exposed through `rovsti_ffi`
  (`cards.render`), general-purpose so Phone/Messages/Stops can add templates later.
- First templates in Contacts: **birthday card** ("Happy birthday, <name>!", age if known, photo or WA fallback avatar,
  date), **contact card** (name, numbers, QR of the vCard), maybe **new number** announcement. Liquid-glass look in the
  app's style: soft gradient, frosted panel, clean type. Turkish and English text by locale. Bundle the fonts they need
  (licence noted in THIRD_PARTY_NOTICES) so rendering is deterministic.
- UI: from the contact card's menu and the Upcoming Birthdays "Wish" action -> a preview sheet (template switcher,
  colour choice) -> Share (Android share sheet with the JPEG) or Save to gallery.
- **What Takumi can do (checked in its source, 2026-11-10):** CSS `backdrop-filter` (blur, saturate, brightness,
  contrast, hue-rotate, drop-shadow and `url()`), `filter`, `mix-blend-mode`, `mask-image`, `clip-path`, gradients and
  `box-shadow`, plus SVG filter primitives including `feDisplacementMap`, `feTurbulence`, `feSpecularLighting`,
  `feGaussianBlur`, `feColorMatrix`, `feComposite`, `feImage`. It is a CPU rasterizer: **no custom GPU shaders**
  (no AGSL/WGSL). So **liquid glass is doable without shaders**: frosted panel = `backdrop-filter: blur() saturate()`
  + a white tint + a 1 px light rim (gradient border) + inner highlight; the lens/refraction edge = an SVG filter with a
  radial displacement map (`feImage`/`feTurbulence` -> `feDisplacementMap`) and a `feSpecularLighting` rim, the same
  technique as glisse's SVG glass (port its constants, MIT, with the glisse header). Prototype first and compare with
  the app's glass; if `backdrop-filter: url()` does not apply the displacement, apply the filter to a copy of the
  background clipped to the panel instead.
- **Formats:** PNG (lossless, transparency), JPEG (quality ~88), WebP, and **animated GIF / APNG / animated WebP**
  (Takumi's animation encoders). Offer JPEG for photos, PNG for cards with transparency, **GIF for animated cards**.
- **Light bending as the GIFs' signature (owner, 2026-11-10: "use the refraction to give the GIFs extra motion and
  taste"):** animate the glass optics, not just the content: (1) a glass lens or droplet that drifts slowly across the
  card and bends the background, text and photo under it (`feDisplacementMap` with an animated displacement map per
  frame); (2) a specular highlight that sweeps along the panel rim as if a light source moves (`feSpecularLighting`
  light position keyframed); (3) a faint chromatic dispersion fringe that blooms at the lens edge at the loop's peak;
  (4) the panel's refraction breathing slightly (scale of the displacement eased in and out) so the frosted panel
  looks liquid. Restrained: one hero effect per card, never all at full strength. Prototype the birthday GIF first,
  look at it frame by frame, then tune.
- **One-shot, not looping (owner, 2026-11-10):** every animated card plays **once** and **settles on a final still
  frame** that is itself a complete, beautiful card (GIF loop count = 1, i.e. no repeat; the last frame is held).
  Think of it as an entrance: e.g. the glass panel drops in and **wobbles like jelly as it settles** (spring with
  overshoot, the refraction stretching and relaxing with the squash), a lens sweeps once across the name and leaves it
  sharp, the specular glint travels the rim once and fades, confetti bursts and comes to rest, the photo comes into
  focus through the glass. The owner's examples are not a limit: be creative per card (e.g. a candle that lights with a
  little refraction shimmer, numbers of the age rolling into place behind the glass, a liquid drop that splits into the
  initials). Use spring physics for motion (the skill's motion tokens), 1.5-3 s total, front-load the motion, at least
  the last ~0.6 s still. Because some apps loop GIFs anyway, also export APNG/animated WebP with play-once and pick the
  share format per target; the final still is also offered as JPEG/PNG.
- **Animated cards (owner: "design nice GIFs"):** birthday card (confetti burst that settles, glass shimmer once,
  candle lighting); contact card (panel settles, gradient drift easing to rest). 1.5-3 s one-shot, 24-45 frames,
  540-720 px wide,
  palette chosen per card, target under ~3 MB; render frames in the Rust core on a background thread with progress.
  Share GIF where the target app supports it (WhatsApp sends GIFs as video: also offer MP4 only if Takumi's encoder is
  available in our build, otherwise animated WebP).
- Tests: Rust golden renders (hash or pixel-diff tolerance) for static frames and the first/last GIF frame, size/time
  budget (static card under ~300 ms, GIF under ~3 s on the phone), Kotlin flow tests; screenshots
  `x2-card-preview-birthday`, `x2-card-preview-contact`, `x2-card-preview-gif`.

## E6. Messages including WhatsApp (`:messages`)

Goal: parity with `apps/messages` (roadmap "Rovsti Messages", `util-messages*.md` M1-M50, `messages.md`, AD-MSG1 to
AD-MSG9, AD-WA11), then polish.

### Parity table

| Features | Story |
|---|---|
| App, ROLE_SMS components (SMS_DELIVER, WAP_PUSH, RESPOND_VIA_MESSAGE, SENDTO/SEND, boot, reply, scheduled receivers), firebase flavor, tabs (Chats, WhatsApp, Settings), permission asks and refresh on return (`access.rs`) | E6.1 |
| Unified inbox (one person one row, AD-MSG3), one avatar, source glyph once, M30 filter chips (All, Unread, WhatsApp, SMS) one row with counts as labels, M36 pins, M37 archive + Undo, M38 mark unread, mute, M7 mark all read, row menu, swipe actions, M39 bulk select, M21 avatar opens photo, WhatsApp Status row and feed (no overlapping avatar stack), M18 global search with jump, drafts marker | E6.2 |
| SMS/MMS conversation: timeline, delivery reports, dual-SIM send, composer, M45 undo send 4 s, drafts survive restarts, failed message Copy/Delete, M15 delete single + Undo, MMS view/save, attachments, M46 paste image (MMS), unblock and make-default rows | E6.3 |
| WhatsApp conversation (E6.4a thread, text, paging, ticks, edited/forwarded marks, group sender names, typing, keyboard Send, WhatsApp avatars with back-off; E6.4b the rest): history paging, text, media send (optimistic bubble, retry, 16 MiB rule), reactions, M32 double-tap react, M49 recent emoji, M31 swipe to reply, quoted replies, group sender names, typing, ticks, edited/forwarded marks, M17 voice notes with speed and scrubbing, keyboard Send, M46 paste image, save received files | E6.4a / E6.4b |
| Message context menu (preview card + reaction bar): reply, copy, M16 select text, M9 forward, M14 info, star, M33 remind me, delete; M20 select mode copy/forward as block; detection pills: M8 phone/email, M25 links, M19 dates, M40 maps links, M35 location card, OTP Copy code (SMS and M44 WhatsApp) | E6.5 |
| Conversation tools: M5 search with jump + glow, M24 jump to date (popover), M12 scroll-to-bottom with count + unread divider, M43 next unread, M13 media/links/docs gallery, M6 export .txt / share text, M48 export .zip, M27 clear chat, M42 header long-press copy, M29 mute override, M50 group sender mini card, M22 message privately | E6.6 |
| M1 image viewer, M3 swipe between images "3 of 7", M2 save/share, M4 header photo, M26 shared contact card sheet (save .vcf, add to contacts), M28 non-image attachment copy/share, Status viewer | E6.7 |
| New conversation (number or contact, SMS or WhatsApp), forward chooser, share-in (text, images), `sms:`/`smsto:`/`mms:`/`mmsto:` intents, contact handoff `WA_CHAT` (AD-CT5) | E6.8 |
| Notifications: per-conversation digest (`rovsti_core::notify`), MessagingStyle, inline reply SMS and WhatsApp (cold through `headless`), OTP copy action, M41 remind in 1 hour, `uiAttached` rule, FCM wake (AD-MSG5) | E6.9 |
| Scheduled send (AD-MSG6): long-press Send sheet, Scheduled strip, SMS alarms, WhatsApp gateway items, fired settle | E6.10 |
| Spam (AD-MSG8): rules, allow list, system blocklist, Spam folder, not spam, move sender to Spam, M47 delete codes after copying | E6.11 |
| Settings: M11 quick replies (+ composer chip row), M34 chat text size, notifications, WhatsApp, encrypted backup export/import with preview (AD-MSG7), default SMS app ask | E6.12 |
| M10 WhatsApp starred list, M23 local SMS stars, M33 reminders (notification opens and glows the message) | E6.13 |
| Keyboard-open shots, long-conversation performance, checklist | E6.14 |

| Story | Title | Round | Status |
|---|---|---|---|
| E6.1 | App module, SMS role, manifest parity, tabs, permissions, migration test | R1 | todo |
| E6.2 | Unified inbox | R1 | todo |
| E6.3 | SMS and MMS conversation | R1 | todo |
| E6.4s | WhatsApp offline store and per-device sync engine (owner request 2026-11-10; before E6.2 and E6.4a) | R1 | todo |
| E6.4a | WhatsApp conversation: thread, text, ticks, groups, paging | R1 | todo |
| E6.4b | WhatsApp conversation: media send, voice notes, reactions, replies, paste image | R2 | todo |
| E6.5 | Message context menu, select mode, detection pills | R2 | todo |
| E6.6 | Conversation tools | R1 | todo |
| E6.7 | Viewers: images, status, contact card, attachments | R1 | todo |
| E6.8 | New conversation, forward, share-in, intents | R1 | todo |
| E6.9 | Notifications and inline reply | R2 | todo |
| E6.10 | Scheduled send | R2 | todo |
| E6.11 | Spam | R2 | todo |
| E6.12 | Settings and encrypted backup | R2 | todo |
| E6.13 | Starred and reminders | R2 | todo |
| E6.14 | Polish | R2 | todo |

### E6.1 App module
- AC1 Every component FQCN of the GPUI Messages manifest (main and firebase); default-SMS role request; manifest test.
- AC2 Migration test with fixture `messages-prefs.json`, `drafts.json`, `scheduled.json`, `whatsapp-link.json`.
- Screenshots: `e6.1-default-sms-ask`, `e6.1-permission-row`.

### E6.2 Inbox
- AC1 Rows: one avatar (group: one composite), name + 14 dp source glyph when the row is WhatsApp-only or SMS-only (a
  merged person row shows no pill, the latest channel glyph only), preview one line, time, unread dot/badge, pin glyph,
  muted glyph. No row carries a source pill.
- AC2 Filter chips never wrap; counts are small secondary labels.
- AC4 WhatsApp avatars come from `WaAvatars` (E3.9); names follow the Rust rule contact name, WhatsApp name, number.
- AC3 Status row: one ring avatar of the latest update + "N new updates" text, never a stack (fixes layout.md M1).
- Screenshots: `e6.2-inbox`, `e6.2-inbox-end-above-bar`, `e6.2-inbox-chips-unread`, `e6.2-inbox-row-menu`,
  `e6.2-inbox-swipe`, `e6.2-inbox-select-mode`, `e6.2-inbox-empty`, `e6.2-status-row`, `e6.2-global-search`,
  `e6.2-inbox-fs2`, `e6.2-inbox-rtl`.

### E6.3 SMS conversation
- AC1 `ComposerField` docked to the keyboard; the last bubble stays visible above the composer with the keyboard open;
  long-press Send opens the schedule sheet (E6.10 fills it).
- Screenshots: `e6.3-sms-thread`, `e6.3-sms-keyboard-open`, `e6.3-composer-multiline-selection`, `e6.3-undo-send-pill`,
  `e6.3-failed-message-menu`, `e6.3-mms-bubble`, `e6.3-dual-sim-chooser`.

### E6.4s WhatsApp offline store and per-device sync engine (owner request, 2026-11-10)
Owner: "the old app fetched WhatsApp live from the server every time; I want to read WhatsApp messages even without
internet: cache old messages on the device and only ask the gateway for new ones." Do this before the inbox (E6.2) and
the thread (E6.4a): both read WhatsApp **only from the local store**; the network only fills the store.
- **Store (platform-free, Rust, `rovsti_app_core::wa_sync`):** SQLite on the device (rusqlite, bundled) with chats,
  messages (gateway id, chat JID, sender, timestamp, kind, text, quoted id, reactions, ticks/status, edit/delete flags),
  media metadata (thumbnail bytes inline, full media as a cached file path), contacts/names/avatars refs, and a
  `sync_state` row per linked instance. Schema versioned with migrations and tests. One store per app install; it is
  keyed by the connect key's instance, so re-linking another instance never mixes data.
- **Per-device sync cursor:** each install keeps its own last event `seq` (the gateway `EventLog`, crates/rovsti_wa_proto
  `log.rs` `since()`), plus per chat the newest and oldest stored message. On (re)connect: ask the gateway for events
  `since(seq)` and apply them in order (idempotent upserts by gateway message id; the timeline echo/confirm dedupe in
  `timeline.rs` must hold). On `Since::Gap` (gateway restarted or ring trimmed) run a **resync**: refresh the chat list,
  then for each chat fetch pages newer than the newest stored message (`GET .../messages?before=` paging,
  services/wa_gateway/src/routes.rs, 50-200 per page) until they overlap what is stored; never drop stored history.
- **Older history on demand:** scrolling a thread past the oldest stored message fetches the next page `before` it and
  stores it; once stored it is readable offline.
- **Outbox (owner confirmed 2026-11-10):** sending without internet puts the message in a persistent local queue and
  it **waits until the connection is back**, then goes out by itself: shown at once in the thread with a clock icon
  ("Waiting for connection" / "Bağlantı bekleniyor"), kept across app kills and phone restarts (stored in the same
  database), sent in the order written per chat, triggered by a network-available callback (WorkManager job with a
  CONNECTED constraint plus the live connection) rather than polling, retried with backoff on gateway errors, and
  reconciled with the echo by client id so it never doubles. The owner can cancel or edit a waiting message. Media
  sends queue the same way (file copied into app storage first). Edits, deletes, reactions and read marks queue too.
- **Deleted messages are never erased (owner request, 2026-11-10):** a message deleted by its sender ("delete for
  everyone") or by the owner is only **marked** deleted (`deleted_at`, `deleted_by`), with its text, quote and media
  kept in the store. The thread shows it as a "This message was deleted" bubble (Turkish: "Bu mesaj silindi") with the
  time; **long-press** opens the normal message menu with a "Show content" preview that reveals the original text/media
  (no other action changes it). Search, export and backups keep it. The gateway does the same server side (see the
  gateway change "soft delete"), so a resync never removes it either. Applies to the `MessageDeleted` event and to
  edits (keep the previous text as edit history, shown the same way).
- **Media:** thumbnails stored with the message; full media downloaded on open (and optionally prefetched on Wi-Fi),
  kept in an LRU cache with a size cap set in Settings; a message stays readable when its media is evicted.
- **Live updates:** the event stream (E2.14) writes to the store; the UI observes the store (Flow), so live and offline
  are the same code path. Sync runs in a foreground-safe worker on connectivity change and app start; no polling loop.
- **Gateway:** prefer the existing API. If a needed call is missing (for example "messages after id" for gap repair,
  or event replay beyond the ring), add it in `services/wa_gateway` with tests, keep the API backward compatible for
  the deployed apps, and list the deploy step in the story evidence; deploying needs the owner's go.
- **Tests:** Rust unit tests for ordering, idempotent upsert, gap -> resync, outbox reconcile, edits/deletes, migration;
  Kotlin tests that the inbox and a thread render from the store with the network off (fake gateway down), and a
  screenshot `e6.4s-offline-thread` with an "offline" indicator.

### E6.4a / E6.4b WhatsApp conversation
- E6.4a shots: `e6.4-wa-thread`, `e6.4-wa-group-thread`, `e6.4-wa-keyboard-open`. E6.4b shots: the rest below.
- Screenshots: `e6.4-wa-thread`, `e6.4-wa-group-thread`, `e6.4-wa-reply-swipe`, `e6.4-wa-quoted-composer`,
  `e6.4-wa-media-pending`, `e6.4-wa-voice-note`, `e6.4-wa-keyboard-open`.

### E6.5 Message menu and detection
- AC1 Long-press a bubble: preview-card context menu (E1.11) with the reaction bar above and the action panel below or
  above by placement; the list does not scroll or move under it.
- Screenshots: `e6.5-bubble-menu-below`, `e6.5-bubble-menu-above`, `e6.5-reaction-picker`, `e6.5-select-text-sheet`,
  `e6.5-message-info`, `e6.5-detection-pills`, `e6.5-otp-copy-code`, `e6.5-select-mode-block`.

### E6.6 Conversation tools
- Screenshots: `e6.6-find-in-chat`, `e6.6-jump-to-date-popover`, `e6.6-scroll-to-bottom-count`, `e6.6-unread-divider`,
  `e6.6-gallery-grid`, `e6.6-header-menu`, `e6.6-sender-mini-card`.

### E6.7 Viewers
- Screenshots: `e6.7-image-viewer-paging`, `e6.7-status-viewer`, `e6.7-contact-card-sheet`, `e6.7-attachment-menu`.

### E6.8 New conversation and forward
- Screenshots: `e6.8-new-conversation`, `e6.8-new-conversation-typing-number`, `e6.8-forward-chooser`, `e6.8-share-in`.

### E6.9 Notifications
- AC1 Robolectric tests: notification built from a digest spec; inline reply goes through `headless` when the UI is not
  attached and through the repository when it is; fallback notification when Rust is missing.
- Screenshots: none (notifications are system UI); JVM assertions on the built `Notification`.

### E6.10 Scheduled send
- Screenshots: `e6.10-schedule-sheet`, `e6.10-scheduled-strip`.

### E6.11 Spam
- Screenshots: `e6.11-spam-folder`, `e6.11-spam-rules`, `e6.11-rule-editor-field`.

### E6.12 Settings and backup
- Screenshots: `e6.12-settings`, `e6.12-quick-replies`, `e6.12-text-size`, `e6.12-backup-passphrase`,
  `e6.12-restore-preview`, `e6.12-settings-fs2`.

### E6.13 Starred and reminders
- Screenshots: `e6.13-starred-list`, `e6.13-remind-me-popover`, `e6.13-message-glow`.

### E6.14 Polish
- AC1 Recomposition tests: typing in the composer (<= 2 per bubble), scrolling 2 000 bubbles, opening a bubble menu;
  checklist pass; contact sheet `docs/kotlin-ui/screenshots/messages.png`.

---

## E7. Stops (`:stops`)

Goal: parity with `apps/stops` (roadmap "Rovsti Stops", `util-stops*.md` S1-S44, `stops*.md`, AD-ST1 to AD-ST9, AD-SA1
to AD-SA9, AD-SX1 to AD-SX8), as a **full-screen map with floating controls** (ARCH 8.4), then polish.

### Parity table

| Features | Story |
|---|---|
| App, manifest (TripService location FGS, AlarmActivity, boot, `geo:`/maps VIEW, SEND text/plain, exact alarm, full-screen intent), full-screen map home with floating chrome, folded tab bar, sheet host | E7.1 |
| Tile map: own renderer over `rovsti_tiles` (camera, pan, pinch, fling, double-tap zoom: E7.2a; bitmap LRU, parent stand-ins, colorful/eclipse by theme, radar look where tiles are missing, attribution, S28 scale bar: E7.2b) | E7.2a / E7.2b |
| Radar layers: A and B pins, circle + diameter line + halves, position + accuracy, breadcrumb, GTFS stops and line, S40 saved stops on the map (toggle, tap opens the stop) | E7.3 |
| Picking (AD-ST9): first tap A, second B, circle at once, third tap hint, pin drag (`PIN_GRAB_PX`, second finger -> pinch), snap to GTFS stop on flick, Move, Swap, Clear, labels "Point A"/"A-B 850 m"; pin menu: S1 copy as (decimal, DMS, DDM, Plus Code, geo:, OSM), S3 QR, S4 open in maps, S34 distance from you + Measure from here, save as stop | E7.4 |
| Trip: start/stop, preflight (location, background recommended row AD-SA7, notifications, exact alarm, full-screen intent, battery), live card (distance, S9 bearing arrow, S41 arrival clock, the line-aware "N stops before" alarm from imported GTFS, `transit.rs`), S20 copy/share status, arriving-soon toggle per trip, restart refused handling (AD-SA8) | E7.5 |
| Alarm: in-app alarm screen, native AlarmActivity over the lock screen, snooze, timeout re-ring (AD-SA5), S21 ring again in N min, S27 trip summary after arrival (share, save as stop) | E7.6 |
| Saved stops: list, S6 search and sort (saved order, name, nearest), reorder, rename, delete + Undo, S24 pin favourites, S35 colour tag, S44 note, S19 reverse and duplicate, S2 share text (paste fills A and B), S3 QR, S18 home screen shortcut, S29 open in maps + distance, S30 copy all, S38 used N times | E7.7 |
| Pick sheets: paste field (S23 DMS / DDM / Plus Code), recent trips, saved stops, GTFS stop search, S7 stop sheet (copy name/code, share, distance), Confirm sheet for shared links, S37 nearest saved stop chip | E7.8 |
| Map options: S17 follow-me (cycling Me button), S11 appearance Auto/Light/Dark, S42 copy map link / share view / copy centre, S8 + S26 measure tool (legs, Undo last, Clear, copy, GPX, save two points as stop), S25 share my location (Me long-press, blue dot) | E7.9 |
| Settings: alarm (S13 sound picker, S14 volume + test, S12 vibration pattern + preview, sound/vibration never both off, S39 reset), S43 units, tile server URL + test, S22 cache cap + per-server usage + Clear, S10 offline area download, GTFS import (AD-ST6), arriving-soon message (chat, template preview, when; AD-ST7), WhatsApp | E7.10 |
| History (AD-ST8): list, stats, S15 search + outcome filter + start again + save as stop, delete single + Undo, S36 delete older, S16 CSV/GPX export, S33 share summary, S31 separators fixed; S32 backup/restore everything | E7.11 |
| S5 saved stops GPX/JSON export and import with merge summary, duplicates skipped, Undo | E7.12 |
| Device performance gate for the tile renderer, MapLibre re-evaluation record | E7.13 |
| Checklist, motion, perf | E7.14 |

| Story | Title | Round | Status |
|---|---|---|---|
| E7.1 | App module, manifest parity, full-screen map home skeleton, migration test | R1 | todo |
| E7.2a | Camera, gestures, tile request/cancel, decode (`MapCamera`, `tile()` client) | R1 | todo |
| E7.2b | Tile rasteriser, bitmap LRU, stand-ins, styles, scale bar, JVM render benchmark | R1 | todo |
| E7.3 | Radar layers | R1 | todo |
| E7.4 | Picking and the pin menu | R1 | todo |
| E7.5 | Trip start, preflight, live card | R1 | todo |
| E7.6 | Alarm and arrival | R1 | todo |
| E7.7 | Saved stops | R1 | todo |
| E7.8 | Pick sheets | R2 | todo |
| E7.9 | Map options, measure, share location | R2 | todo |
| E7.10 | Settings | R2 | todo |
| E7.11 | History, stats, backup | R2 | todo |
| E7.12 | Saved stops import and export | R2 | todo |
| E7.13 | Tile renderer device check and MapLibre decision record | R2 | todo |
| E7.14 | Polish | R2 | todo |

Shared AC for every E7 story: the map is the full window in every state; no screen shows a map inside a box; every
control floats over the map as glass chrome; the sheet at peek and medium lets the map pan and take taps; floating
buttons ride the sheet by translation only; camera padding follows the settled sheet detent.

### E7.1 App module and home skeleton
- AC1 `dev.rovsti.stops`, components `TripService`, `AlarmActivity`, `BootReceiver`, VIEW `geo:` + maps links, SEND
  text/plain; manifest test.
- AC2 Home: map layer (fake tile source in tests), search/pick pill, button column, folded tab bar, sheet host; Saved,
  History, Settings open as sheets or pushed pages over the map.
- AC3 Migration test with fixture `stops-prefs.json`, `trip.json`, legacy trip (renamed, AD-SX8), `saved.json` v2,
  `history.json`, `gtfs.json`.
- AC4 (E3 AC4) Re-target the manifest and `MainActivity` assertions that stayed in `apps/stops/src/kotlin_contract.rs` at
  `android/stops/src/main/AndroidManifest.xml` and the Kotlin `MainActivity` (location FGS type and permissions, background
  location, share provider, shortcut and `geo:` intents), in a Rust test beside
  `crates/rovsti_app_core/tests/kotlin_contract.rs` (which already reads the `android/platform` sources).
- Screenshots: `e7.1-home-idle`, `e7.1-home-idle-dark`, `e7.1-home-sheet-peek`, `e7.1-home-sheet-medium`,
  `e7.1-home-landscape`, `e7.1-home-fs2`.

### E7.2a / E7.2b Tile renderer
- Split: E7.2a is ARCH 8.4 steps 1-2 (camera, gestures, requests, cancellation, decode, offline fake source); E7.2b is step 3 and the visuals.
- AC1 Pipeline of ARCH 8.4 steps 1-3; camera holder read in draw; a pinch recomposes nothing (recomposition test).
- AC2 Bitmap LRU sized from memory class; tiles leaving the viewport are cancelled (test with a fake source); parent
  stand-in drawn while children load.
- AC3 Glass over the map is Frost while panning and Liquid (or Flat in tests) at rest (`MotionGate`).
- Screenshots: `e7.2-map-colorful-z14`, `e7.2-map-eclipse-z14`, `e7.2-map-missing-tiles-radar`, `e7.2-map-standin-parent`,
  `e7.2-scale-bar`.

### E7.3 Radar layers
- Screenshots: `e7.3-ab-circle`, `e7.3-position-accuracy`, `e7.3-breadcrumb`, `e7.3-gtfs-stops-line`,
  `e7.3-saved-stops-markers`.

### E7.4 Picking
- AC1 Gesture tests: tap drops A then B; drag near a pin grabs it immediately (no long-press); second finger cancels into
  pinch; flick snaps to a GTFS stop; map tap never moves the view; labels from `rovsti_app_core::stops::pick`.
- Screenshots: `e7.4-pick-a`, `e7.4-pick-ab`, `e7.4-pin-dragging`, `e7.4-pin-menu-open`, `e7.4-copy-as-menu`,
  `e7.4-third-tap-hint`.

### E7.5 Trip
- AC1 Preflight rows follow ARCH 7.5: location (fine) then background (separate step, recommended only), notifications, exact alarm n/a, full-screen intent, battery; a revoked grant while a trip runs shows the row and keeps the trip.
- Screenshots: `e7.5-line-aware-alarm-setting`, `e7.5-preflight-sheet`, `e7.5-trip-live-card`, `e7.5-trip-live-card-dark`, `e7.5-background-row`,
  `e7.5-restart-refused-row`.

### E7.6 Alarm
- AC1 AlarmActivity behaviour unchanged (native Views over the lock screen); the in-app screen matches its actions.
- Screenshots: `e7.6-alarm-in-app`, `e7.6-arrival-summary`, `e7.6-ring-again-menu`.

### E7.7 Saved stops
- Screenshots: `e7.7-saved-sheet`, `e7.7-saved-search-sort`, `e7.7-saved-row-menu`, `e7.7-saved-reorder`,
  `e7.7-saved-edit-note-field`, `e7.7-saved-end-above-bar`.

### E7.8 Pick sheets
- Screenshots: `e7.8-pick-sheet-paste-field`, `e7.8-stop-search`, `e7.8-stop-sheet`, `e7.8-confirm-shared-link`,
  `e7.8-nearest-chip`.

### E7.9 Map options and measure
- Screenshots: `e7.9-map-options-popover`, `e7.9-measure-two-legs`, `e7.9-measure-more-menu`, `e7.9-my-location-sheet`,
  `e7.9-follow-mode`.

### E7.10 Settings
- Screenshots: `e7.10-settings`, `e7.10-alarm-settings`, `e7.10-tiles-settings`, `e7.10-offline-download-progress`,
  `e7.10-arriving-soon`, `e7.10-template-field-selection`, `e7.10-settings-fs2`.

### E7.11 History
- Screenshots: `e7.11-history`, `e7.11-history-stats`, `e7.11-history-filter`, `e7.11-history-row-menu`.

### E7.12 Import and export
- Screenshots: `e7.12-import-summary`.

### E7.13 Performance gate
- AC1 (moved earlier: runs inside E7.2b so a failing renderer is known before E7.3-E7.8 build on it) JVM benchmark: rasterise a dense
  city tile (testdata) in < 25 ms on the host; draw 16 cached bitmaps per frame with no allocation (allocation-count test).
  E7.13 keeps AC2, the owner-device check.
- AC2 Owner device checklist entry: pan and pinch at 120 Hz over a city, frame stats recorded (`dumpsys gfxinfo`); if p90
  > 8.3 ms after the listed fixes, open a decision record on MapLibre.

### E7.14 Polish
- AC1 Checklist pass (B "Stops" line), contact sheet `docs/kotlin-ui/screenshots/stops.png`.

---

## E8. Release, profiles, docs, retiring GPUI from the default build

| Story | Title | Round | Status |
|---|---|---|---|
| E8.1 | Release build types, signing from env, versionCode scheme, ABI policy | R1 | todo |
| E8.2 | R8 and resource shrinking with minimal keep rules | R1 | todo |
| E8.3 | Baseline profiles and the Macrobenchmark module | R1 | todo |
| E8.4 | In-place upgrade: migration tests for all apps, data and role checklist | R1 | todo |
| E8.5 | Docs: architecture, android.md, CLAUDE.md rules, screenshots, notices audit | R1 | todo |
| E8.7 | Owner device acceptance checklist (runs before removal) | R1 | todo |
| E8.6 | Remove the GPUI apps and crates from the default build (only after E8.7 signed off) | R1 | todo |
| E8.8 | targetSdk 37 review | R1 | todo |

### E8.1 Release builds
- AC1 `assembleRelease` for the four apps with the release keystore from env (`scripts/signing.sh` style; when `ANDROID_STORE_PASSWORD` or the keystore file is absent, as on the agent machine where `gpuiforge.json` points at `/home/erdem/...`, `assembleRelease` signs with the debug key and `check-apk.sh` prints `UNSIGNED-FOR-RELEASE`); versionCode
  `100 + build number` (> GPUI's 1); arm64-v8a only in release.
- AC2 `scripts/build-apks.sh` builds the Kotlin APKs; `scripts/check-apk.sh` checks id, version, signer, ABI, `.so` exports.

### E8.2 R8
- AC1 `isMinifyEnabled` and `isShrinkResources`; keep rules only for `RustNative`/`RustEvents`, serialization and manifest
  classes; no package wildcards; a release check that cannot use Robolectric (it cannot run minified classes): a Gradle task parses `mapping.txt` and fails if
  `RustNative`, `RustEvents`, their members, `@Serializable` models or any manifest class were renamed or removed, and `check-apk.sh`
  lists the `.so` exports; an instrumented bridge call is added to `acceptance.md` for a device.

### E8.3 Baseline profiles
- AC1 `androidx.profileinstaller` in every app; hand-written rules for first screen, rows, overlay host, map node;
  `:benchmark` module ready (cold/warm start, list scroll, menu open, map pan) for when a device or managed emulator exists.

### E8.4 In-place upgrade
- AC1 Per app: fixture data dir from the GPUI build loads in the Kotlin app (JVM test); SharedPreferences names, channel
  ids, component FQCNs unchanged (tests from E3.1 and the manifest tests).
- AC2 `docs/kotlin-ui/upgrade.md`: release-signed update steps, what to check after updating (role still held, WhatsApp
  still linked, scheduled SMS still fires, trip resumes).

### E8.5 Docs
- AC1 `docs/architecture.md` points to the Kotlin UI architecture for the UI; GPUI sections marked historical.
- AC2 `CLAUDE.md` updated: Kotlin UI rules, the only JNI crate is `rovsti_ffi`, checks are `scripts/kotlin-ui-check.sh`.
- AC3 `android/THIRD_PARTY_NOTICES.md` audited (glisse, any Apache-2.0 dependency notices); every glisse-derived file has
  the header (grep test).

### E8.6 Remove GPUI from the default build
- AC1 Workspace `default-members` exclude `apps/*`, `rovsti_ui`, `rovsti_wa_ui`, `liquid_glass_kit`; owner decides delete
  vs `legacy/` (recorded); unused `rovsti_core` UI modules (ARCH 6.6 "Not exposed") removed or kept with a reason;
  `rovsti_android` Rust side retired once nothing uses it.
- AC2 All checks green without the GPUI dependency (`cargo test --workspace` no longer fetches gpui-fusion).

### E8.7 Owner device acceptance
- AC0 Ordering: the Kotlin apps are installed as release-signed updates on the owner's phone, the checklist is signed off, and only then E8.6 deletes or moves the GPUI code (the GPUI apps stay the rollback until then).
- AC1 One checklist per app in `docs/kotlin-ui/acceptance.md` covering what cannot be verified here: cross-app handoff (same signer, HANDOFF), permission revocation rows (ARCH 7.5), Turkish-vs-English decision (ARCH 12),  Liquid glass look,
  120 Hz, incoming call on the lock screen, SMS role, FCM wake, trip and alarm under Doze, map pan speed, text selection
  toolbar and magnifier on device.

### E8.8 targetSdk 37
- AC1 Behaviour changes of API 37 reviewed against foreground services, exact alarms, full-screen intents, predictive
  back; bump or keep 36 with the reason written in ARCH 3.

---

## Future (not scheduled): Stops on iOS

**Planners: do not pick this up.** Owner note 2026-11-10: if Rovsti ever goes to iOS, only **Stops** goes (Phone and
SMS cannot be default apps on iOS). Kept here as a future plan, not part of this rewrite.
- Reuse the platform-free Rust core (`rovsti_app_core` Stops store/pick/snap/trip, `rovsti_geo`, `rovsti_tiles`) via a
  Swift binding (UniFFI or a thin C ABI next to `rovsti_ffi`), built for `aarch64-apple-ios` and the simulator.
- UI in SwiftUI with the system Liquid Glass (iOS 26) rather than porting the Compose glass engine.
- iOS replacements: Core Location region monitoring and significant-change updates for the arrival alarm (no
  foreground service), critical or time-sensitive notifications for the alarm, MapLibre iOS with the same tile cache
  and user-chosen tile server.
- Open questions for then: background location limits, alarm reliability with the phone locked, App Store vs
  sideloading (personal use).

**Round-12 close (step 8):** E5.1-E5.4, E5.6, E5.7a are done with the Evidence above; E5.5 (stashed step 6) is not. Next planner = **E5 R2** (E5.5 recovery, E5.7b, E5.8-E5.14). Known gaps carried to R2: avatar flight from the Keypad call button, `values-tr` for Phone, U51 persistence.
