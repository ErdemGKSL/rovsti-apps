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
- deviation: the row's long-press menu has no separate custom accessibility actions list yet (E4.16 polish).

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
- seen, not fixed (pre-existing since E4.2): row separators run under the alphabet rail letters; the rail sits over the
  trailing 24 dp but the hairline is full width. Logged for E4.16.
