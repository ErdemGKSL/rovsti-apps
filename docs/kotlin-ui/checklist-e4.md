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
  `sh.calvin.reorderable` in `THIRD_PARTY_NOTICES.md`). `SelectCheck`, `SelectController`, `SelectToolbar` are Rovsti code
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
- pass: specs from `RovstiMotion` (`select` spring for the check circle fill and the slot width, `appear` for added
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
- pass: haptics go through `RovstiHaptics` (alphabet rail tick, reorder, menu rows, tab bar).
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
- pass: all specs come from `RovstiMotion`; no `tween` for position or size, no `animateContentSize`/`AnimatedVisibility`
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
  have 56 dp targets; haptics via `RovstiHaptics` (settings toggle `contacts.haptics` respected).
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
