# E4 R1 checklist record (Contacts, round-10 step 8)

The pass of `.claude/skills/convx-motion-compose/references/checklist.md` sections A-H over every Contacts screen built
in E4.1-E4.8 (`android/contacts`): tabs and settings (E4.1), list and search (E4.2), card (E4.3), editor, photo menu and
crop (E4.4), delete and Trash (E4.5), Favorites and upcoming birthdays (E4.6), Groups, group page and member picker
(E4.7), select mode (E4.8). Each item is `pass`, `fixed` (found and fixed in this pass) or `deviation: <reason>`.
Method: grep of `android/contacts/src/main` for the rule, the test that proves it, and the goldens in
`android/contacts/screenshots/` (all opened in their step; the two E4.8 goldens re-opened here).

## A. Licence and provenance
- pass: no GPUI, no Material (`grep` for `Popup(`, `Dialog(`, `androidx.compose.material` finds nothing in `:contacts`).
- pass: the only ported file of E4 R1 is the favorites reorder (Convx header + `Ported from Convx ...` line, and
  `sh.calvin.reorderable` in `THIRD_PARTY_NOTICES.md`). `SelectCheck`, `SelectController`, `SelectToolbar` are Rosty code
  written for this story (a Convx circle selector does not exist for rows); `ToolbarTextButton` sits in `Toolbar.kt`,
  which already carries the glisse and Convx header.

## B. Layout
- pass: layers: page content (LazyColumn) then chrome (tab bar, accessory, top bar) then overlays; the select toolbar is
  the tab bar's accessory, so it is chrome, not content.
- pass: every list takes `LocalContentInsets`; `e4.2-list-end-above-bar` and `SelectModeScreenTest
  .theLastRowEndsAboveTheSelectToolbar` prove the end of the list clears the bar and the toolbar.
- pass: one avatar per row; one WhatsApp glyph per row; no source pill; no chip rows in Contacts.
- pass: spacing from tokens; per-screen `Spacing.kt` files hold the few screen-specific dp (editor, card, photo, favorites,
  groups, people) as named values.
- pass: rows use `heightIn(min)`; `e4.2-list-fs2` and `e4.3-card-fs2` show no clipping at 2.0.
- fixed (this step): select mode must not push content: the check circle slot animates its width inside the row, the
  rows are NOT re-laid out around a new bar: the tab bar folds (beside the circle the toolbar appears) and the list
  bottom inset follows the bar's measured height. Seen in `e4.8-select-mode`: the last visible rows slide under the
  glass toolbar as under any floating bar, titles and checks stay aligned.
- n/a: Stops map items.

## C. Motion
- pass: specs from `RostyMotion` (`select` spring for the check circle fill and the slot width, `appear` for added
  editor rows, `morph` for menus).
- pass: press changes only draw-phase properties; `ToolbarButton`/`ToolbarTextButton` use `scalePress` and the press
  fill; `ContactsListScreenTest` (row bounds unchanged while the menu opens).
- pass: favorites reorder follows Convx `LocalPlaylistScreen` (drag, spring settle, haptic); `FavoritesScreenTest`.
- pass: reduce motion goes through the shared gates; the check circle uses the `select` spring only (no squash).

## D. Overlays
- pass: every menu, sheet, alert, toast and the undo pill is an `OverlayHost` entry (row menu, field menu, label
  popover, photo menu, discard/delete/clear alerts, group chooser `ActionSheetEntry`, "..." menu via `menuTrigger`).
- pass: back closes the top entry first (host `BackHandler`), then select mode (`BackHandler` in
  `ContactsListScreen`), then the tab/page; `SelectModeScreenTest.backLeavesSelectModeBeforeAnythingElse`.
- pass: long-press menus: the row menu has the "Select" row as the TalkBack-reachable way into select mode; rows in
  select mode expose `selected` state and a click action.
- fixed (E4.16, see R2 below): list rows now carry the row menu as `semantics { customActions }`.

## E. Glass
- pass: glass only on chrome: tab bar, its accessory (the select toolbar), top-bar capsules, menus. Rows are flat.
- pass: the select toolbar takes the bar's glass modifier from the accessory slot (same material as the tab bar).
- pass: `GlassRealPathTest` and the E1 contrast tests cover the presets; Cancel / Select All use the accent colour on the
  bar glass (readable in both goldens).

## F. Controls and input
- pass: editor fields are `GlassTextField` (caret, selection, paste), `SearchField` in the list; IME action submits.
- pass: haptics go through `RostyHaptics` (alphabet rail tick, reorder, menu rows, tab bar).
- pass: touch targets: toolbar buttons 44 x 48 dp, row min 56 dp.
- deviation: Share in select mode shares the plain text form through the system sheet (selectExport `text`); the .vcf goes
  to the save picker through "Export .vcf" (the system share sheet needs a staged file; planned with E4.9 share formats).

## G. Performance
- pass: `contentType` and `key` on every lazy item; immutable UI state (`ImmutableList`, `ImmutableSet`).
- pass: `ContactsListScreenTest` counts recompositions while typing; in select mode each row gets a plain `Boolean?` so
  a toggle recomposes the toggled row (and the title), not the list.
- pass: no JNI on the main thread: the select bar, export and group ops run on the view model's worker flow or
  `viewModelScope` (Rust ops are suspend).

## H. Tests and evidence
- pass: JVM tests on the real Rust core: `ContactsListViewModelTest`, `SelectModeViewModelTest` (10 tests), card, editor,
  trash, favorites, groups; Compose tests with a manual clock for every screen; goldens light and dark.
- pass: E4.8: `SelectModeScreenTest` (8), `E48ShotTest`. PNGs opened: `e4.8-select-mode-light` (title "3 selected",
  Select All / Cancel capsule, three rows with filled accent circles among empty rings, avatars one slot to the right,
  folded tab-bar circle with the five-button glass toolbar beside it, last rows sliding under it) and
  `e4.8-select-actions-menu-dark` (the "..." menu above its button: Export .vcf, hairline, Copy Numbers, Copy Emails,
  glass card clear of the rail).
- fixed (E4.16): row separators now stop 8 dp before the alphabet rail column (was: ran to the rail edge).

# E4 R2 checklist record (Contacts, round-11 step 8)

Same sections A-H over the screens of E4.9-E4.16: share and QR sheet (E4.9), intake / paste / import / add to existing /
scan QR / CSV mapping (E4.10), duplicates, merge preview, Clean up (E4.11), photo viewer (E4.12), call options, block,
voicemail confirm (E4.13), My card, emergency contacts, recently added / edited (E4.14), settings, accounts summary, name
format, WhatsApp (E4.15). Method: grep of `android/contacts/src/main` plus the test that proves each rule; goldens in
`android/contacts/screenshots/` (`e4.9-*` .. `e4.15-*`, each opened in its step; the sheet `docs/kotlin-ui/screenshots/
contacts.png` opened again in E4.16).

## A. Licence and provenance
- pass: no GPUI, no Material, no `Popup(`/`Dialog(` in `:contacts` (grep finds nothing). `QrCode` is own code in `:design`.
- pass: no ported file was added in R2; the E4 R1 ported file (favorites reorder) keeps its header.

## B. Layout
- pass: every R2 page is a `LargeTitleScaffold` or sheet that takes `LocalContentInsets`; nothing ends under the tab bar
  (`e4.15-settings-end`, `e4.2-list-end-above-bar`).
- pass: no two grouped cards touch (F13 section gaps; seen in `e4.15-settings`, `e4.14-my-card`, `e4.11-duplicates`).
- pass: fs2 goldens exist for the list, card, cleanup chips, added/edited, emergency, my card, settings, accounts; no
  clipping, rows grow (`heightIn(min)`).
- pass: RTL goldens `e4.2-list-rtl`, `e4.14-emergency-list-rtl` mirror avatar, text and rail.
- fixed (E4.16): list separators stop 8 dp before the alphabet rail.

## C. Motion
- pass: all specs come from `RostyMotion`; no `tween` for position or size, no `animateContentSize`/`AnimatedVisibility`
  in `:contacts` (grep).
- pass (springs retarget mid-flight): select slot, select check, favorites/emergency reorder and sheet detents are springs
  that retarget from current value and velocity (`SelectModeScreenTest`, `FavoritesScreenTest`, sheet tests in `:design`).
- pass (no layout shift on press): press only changes draw-phase properties (`rowPress`, `scalePress`); `ContactsListScreenTest`
  (the row-menu test compares `bounds(id)` before and after) asserts row bounds unchanged while the menu opens.
- pass (menus and sheets never push content): every menu, sheet, alert, toast is an `OverlayHost` entry, so the page below
  keeps its bounds; E4.9 share menu, E4.10 CSV mapping menu, E4.12 viewer menu, E4.14 row/badge menus are overlays.

## D. Overlays
- pass: share submenu, QR sheet, intake preview sheet, add-to-existing picker, CSV mapping sheet/menu, merge preview, block
  / voicemail / clear alerts, photo viewer and its menu, emergency row menu, My card badge menu are all overlay entries.
- pass: back order (top overlay, select mode, page) unchanged; pages added in R2 pop one level.
- fixed (E4.16): the row long-press menu is also reachable without a long press through custom accessibility actions
  (`ContactsListScreenTest.everyRowOffersItsMenuAsAccessibilityActions`).

## E. Glass
- pass: glass only on chrome (tab bar, capsules, menus, sheets); rows and grouped cards are flat. `GlassRealPathTest`
  (API 33+ default settings use the backdrop path) stays green in `:design`.

## F. Controls and input
- pass: text input is `GlassTextField`/`SearchField` (CSV mapping, intake, editor); the ringtone/voicemail/block rows
  have 56 dp targets; haptics via `RostyHaptics` (settings toggle `contacts.haptics` respected).
- pass: settings Appearance and Haptics rows write `ContactsPrefs` through the Rust core.

## G. Performance
- pass: `contentType` and `key` on every lazy item in the list, duplicates, cleanup, emergency, recent.
- pass (E4.16): `ContactsListScreenTest.scrollingRecomposesEachRowAtMostOnceAndNeverTheOnesThatStay` and
  `.typingReportsTheQueryAndOnlyTheChangedRowsRecompose` count row compositions with `ListProbe`.
- pass: JNI/Rust work (import, merge, cleanup, export) runs in suspend ops off the main thread.

## H. Tests and evidence
- pass: view-model tests on the real Rust core for every R2 page (see the plan's verification summary), Compose tests with a
  manual clock, goldens light and dark, fs2/rtl variants listed above.
- pass: contact sheet `docs/kotlin-ui/screenshots/contacts.png` built by `:contacts:contactSheet`, opened and judged (see
  epics.md E4.16 Evidence).

# X.2 checklist record: Card Studio (round-17 step 8)

The pass of `references/checklist.md` over the Card Studio sheet (`android/contacts/.../cards/CardStudioSheet.kt`,
`CardStudioViewModel.kt`, `CardStudioEntry.kt`) after round 17. Method: grep of the sources, the tests in
`android/contacts/src/test/kotlin/dev/rosty/contacts/cards/` (`CardStudioSheetTest`, `CardStudioViewModelTest`,
`CardTextsTest`, `CardsWiringGuardTest`) and the `x2-*` goldens in `android/contacts/screenshots/` (opened). Items:
`pass`, `deviation` or `open`. No device: nothing here is TalkBack- or hand-tested on hardware.

## A. Licence and provenance
- pass: Rosty code only in the cards package; no Convx port. No GPUI, no Material, no `Popup`/`Dialog`.
- open: the glisse optics (lens, glint, fringe; round-17 step 4) are NOT in the tree: `crates/rosty_app_core/src/cards/`
  has no `refraction.rs`, so no glisse header was needed. The animated card keeps the blur/tint panel and the confetti.

## B. Layout
- pass: the sheet is a `SheetEntry` (medium detent); the preview keeps its size while it renders
  (`thePreviewBoxKeepsItsSizeWhileItRenders`).
- pass: the headline wraps inside the panel (Rust `cards/fit.rs`, `a_long_turkish_headline_wraps_inside_the_panel`); the
  date is localised ("3 Ekim"); opened `x2-share-still-menu-tr-light`, `x2-card-preview-gif-dark`: nothing touches the
  panel edge. One non-wrapping colour row (also at fs2).
- pass: Share, Save and "..." share one row above the bottom inset; the Animated toggle sits above the preview so the menu
  never covers it (R17-F3, `theMoreMenuOpensUpwardInsideTheWindowAndLeavesTheToggleAlone`).

## C. Motion
- pass: preview changes use `Crossfade` with `RostyMotion.appear`; switch and swatches use design-system controls
  (transform/alpha press feedback only).
- pass: render cancelled when the sheet closes (`closingTheSheetCancelsTheRunningRender`, `closingMidRenderShowsNoLateImage`),
  taps closer than 150 ms cost one render, a rotation keeps the job (`aRotationKeepsTheJob`).
- open: the animated preview decodes the GIF with `BitmapFactory` and shows frame 0; it does not play in the sheet. The
  shared file is the real GIF.

## D. Sheet and menu
- pass: sheet and share menu are `OverlayHost` entries; the menu (`ButtonMenuEntry`) opens upward from "...", right
  aligned, inside the window, with the menu scrim behaviour instead of a full-sheet dim (R17-F2). Back closes the sheet
  before the page (`backClosesTheSheetBeforeThePage`); the Share toast goes through `showToast`.
- pass: `SheetEntry(title = ...)` gives the sheet a pane title; the page behind is cleared from semantics by the host.

## E. Glass
- pass: glass only on the sheet and buttons; the card preview is a flat picture.
- deviation: no refraction in the animated card itself (see A).

## F. Controls, haptics, insets, a11y
- pass: `LocalRostyHaptics` `reject()` on a refused Share, a failed Save and a card that cannot be staged
  (`aFailedSaveToastsAndRejects`, `aCardThatCannotBeStagedToastsAndRejects`); a saved card does not reject
  (`aSavedCardToastsWithoutTheRejectHaptic`).
- pass: insets: the content sits in the sheet body above the navigation inset; there is no text field, so no IME handling
  (`imeOverlapPadding` rule n/a).
- pass: the preview is described by who it is for ("Birthday card for Ayse Yilmaz", Turkish "... icin dogum gunu karti",
  `thePreviewIsDescribedByWhoItIsFor`, `thePreviewDescriptionIsTurkish`) plus "Making the card" while rendering; the
  "..." button is labelled "Share as still image"; templates are `Role.RadioButton` with `selected`.
- open: swatch and button sizes are not asserted against 48 dp in a test.
- not run on a device: TalkBack order should follow source order: sheet title, template picker, Animated switch, preview,
  colours, Save, Share, "...". Unverified.

## G. Performance
- pass: one render per settled choice, off Main; the bitmap decodes in `produceState` off Main; no JNI on Main and every
  call waits for `Rust.init` (`CardsWiringGuardTest`, `NativeRustCore.call`).
- pass: Rust budgets (host, `RUN_BUDGETS=1`): static 720 x 900 41-44 ms (budget 150, asserted < 300); GIF 720 x 900,
  39 frames 1.4-1.6 s (asserted < 3 s), without optics.

## H. Tests and evidence
- pass: `CardStudioSheetTest`, `CardStudioViewModelTest`, `CardTextsTest`, `CardsWiringGuardTest`, `CardsOpsNativeTest`
  (real host `librosty_ffi`), goldens `x2-*` light, dark, fs2, tr (opened).
- pass (kept from F40): the `x1-wa-avatar-*` goldens use portraits drawn in code (`:testing` `portraitImage`); opened in
  round 16.

# X.2b checklist record: optics and in-sheet playback (round 18 step 6)

`references/checklist.md` run again over the animated card (Rust `crates/rosty_app_core/src/cards/`) and the Card
Studio sheet with one-shot playback (`android/design/.../media/OneShotAnimatedImage.kt`,
`android/contacts/.../cards/CardStudioSheet.kt`, detents 0.64 and Large). It closes the `open` and `deviation` items
of the X.2 record above (A, C, E, G). No device: nothing here is TalkBack- or hand-tested on hardware.

## A. Licence and provenance
- pass: `crates/rosty_app_core/src/cards/refraction.rs` carries the glisse header; `android/THIRD_PARTY_NOTICES.md` lists
  it and holds the full glisse MIT text. Closes the X.2 "open" item. `OneShotAnimatedImage` is Rosty code (no port).
- pass: no GPUI, no Material, no `Popup`/`Dialog` added.

## B. Layout
- pass: at the first detent the preview is 61% of the sheet width (was 37%), at Large the full content width at 3:4;
  no two of templates, toggle, preview, swatches, Save, Share overlap; swatches stay on one row also at fs2
  (`CardStudioSheetTest`: medium and large preview tests). Opened `x2-card-preview-gif-light` this step: card centred,
  Save/Share clear of the bottom edge, confetti inside the card, nothing under a floating bar.

## C. Motion
- pass: the GIF plays once and holds its last frame (`OneShotAnimatedImageTest.startIsCalledOncePerKey`, no NETSCAPE
  loop block in the file); a tap replays with `glassPress` and a selection tick
  (`anAnimatedPreviewPlaysOnceAndAFurtherTapReplaysItWithASelectionTick`). Closes the X.2 "open" item.
- pass: reduce motion never starts the drawable and shows the final still
  (`underReduceMotionTheAnimatedPreviewNeverStarts`, golden `x2-card-preview-gif-reduce-motion-light` opened: clean
  still, no lens ring, no confetti).
- pass: frame draw goes through a `Drawable.Callback` that invalidates only the draw phase (no recomposition per frame).

## D. Sheet and menu
- pass: unchanged from X.2 (OverlayHost entries, menu opens upward, back closes the sheet first).

## E. Glass
- pass: the optics live inside the rendered card: edge-band refraction of the panel following the squash, lens sweep over
  the headline, rim glint, fringe at most 1.5 px (frame proof in `plans/round-18.md`, step 3, 720 px frames opened).
  Closes the X.2 "deviation". Mid-frame in `x2-card-preview-gif-light` shows the lens ring over "birthday," and the
  glyphs un-torn. The preview itself is still a flat picture (no live glass over it).

## F. Controls, haptics, insets, a11y
- pass: replay is an `onClickLabel` action for TalkBack, English and Turkish (`theReplayActionIsOfferedToTalkBack`,
  `theReplayActionIsTurkish`); a still preview has none. 48 dp size of swatches is still not asserted (kept open).
- pass: no text field, no IME handling.

## G. Performance
- pass: Rust budgets with optics, checks profile: static 720x900 155 ms (asserted < 300, target 150 missed by 5 ms),
  GIF 720x900 39 frames 1.71 s (asserted < 3 s, target 1.5 s missed by 0.2 s), 2.45 MiB; shipped 540x675 GIF
  0.99 s, 1.70 MiB. `librosty_ffi.so` arm64 release 19.8 MiB (optics cost 42 KB).
- pass: decode off Main, drawable released on dispose (`theDrawableIsReleasedOnDispose`), no JNI on Main.
- not measured on a device: playback smoothness in the sheet; see `acceptance.md`.

## H. Tests and evidence
- pass: Rust refraction, lens, glint, determinism, size and budget tests; `CardsOpsNativeTest` 5/5 on the real host
  library; Compose tests above; goldens `x2-*` (light, dark, fs2, tr, large) opened in steps 2, 3, 5 and 6.

# X.3 checklist record: readable text on accent and photo backgrounds (round 19 step 7)

The pass of the contrast section of `.claude/skills/convx-motion-compose/references/checklist.md` ("E. Glass": label
contrast >= 4.5:1 in light and dark) over the Contacts screens the X.3 fixes touch: filled capsules (crop "Choose",
Card Studio Share/Save), the Contacts photo viewer, settings icon tiles and swipe actions. Method: the JVM and Compose
tests below, the contrast audit of every light and dark golden (scratch script, not committed) and the opened PNGs.
Targets: 4.5:1 body text (the 17 sp semibold capsule label counts as body), 3:1 for large text and glyph-only controls.
No device: nothing here is hand-tested on hardware.

- pass (filled controls): `IosColors.onAccent` is a theme-aware property mapped by `IosColorsBridge`; `IosButton`
  (BorderedProminent, GlassProminent) uses `colors.onAccent` on the theme accent and `readableOn(tint)` on any other tint.
  White stays on the light accent #0051D5; navy #001A3A on the dark accent #5AB0FF; ink on green/orange fills.
  Tests: `ContrastHelpersTest`, `ContrastTest`, `FilledButtonContrastTest`, `SwipeActionContrastTest`,
  `TextContrastTest` (the golden text-contrast measure), and `assertTagTextReadable`/`assertNodeReadable` calls in `E44PhotoShotTest` ("Choose" on the
  dark crop page) and `X2ShotTest` (Share on the Card Studio sheet).
- pass (viewer chrome over a light photo): `ViewerScrim` raises the top scrim with the photo luminance; the glass back
  button gets a readable glyph (`BackButton`). Tests: `ViewerScrimTest`, `ViewerChromeContrastTest`.
- pass (icon tiles and swipe actions): the tile glyph and the swipe label use `readableOn` (glyph tiles at 3:1).
  Tests: `SettingsGlyphColorTest`, `SwipeActionContrastTest`.
- pass (Rust card art): every palette's secondary ink reads at 4.5:1 on the colour behind it
  (`every_palette_secondary_ink_reads_at_4_5`, `crates/rosty_app_core/src/cards/tests.rs`); Rust and x2 goldens re-recorded.
- exception (notification badges): the white numerals on systemRed (`IosTabBar` badge, about 3.5:1) stay white. It is an
  iOS convention, the numerals are bold and short, and a badge is not the "white text on a light surface" case the owner
  reported. Recorded as a deliberate deviation; not a defect.
- exception (avatar initials and glyphs): untouched on the owner's instruction.
- pass (contrast audit): the scratch audit over 1,222 light and dark goldens (text-like components with a 2-cluster ratio
  below 3.0) went from 1,555 hits in 575 files (before round 19) to 1,529 hits in 564 files. The remainder was triaged:
  avatar initials, map tiles, glass blur and anti-aliasing noise; every real hit found was fixed in step 6 and has an
  `assertReadable` or unit test.
- open: no TalkBack or on-device check of the new colours.
