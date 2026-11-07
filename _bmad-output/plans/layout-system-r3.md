# Unit `system`, round 3: popovers, context menus and one button set

baseSha: 2fb3dc45c683cdf04c96eeaff8fc2eb150ad368a

## Scope note (read first)

The relayed owner request for this run is, translated from Turkish: "We generally have no
popover or context-menu style things. Every tap makes content flow into the page, and the
buttons look very bad." That request takes priority. The computed task (the shared layout
system) is mostly done after rounds 1 and 2: tokens, PageHeader, ListRow, AvatarStack,
FilterChipRow, SectionHeader, EmptyState, the tab-bar inset, the puck, colour roles and
honest shots all landed (`7deff5f..2fb3dc4`). The fresh `messages/01-inbox-dark.png` matches
the spec. What the shared system still lacks is exactly what the owner names: a real
**anchored popover / context menu**, an overlay material you cannot read through, and
**one button set**. This round adds those to `rovsti_ui` and proves them on two screens:
the Messages row menu, which is the system unit's proof screen, and the Stops Saved
inline action row, which is the clearest "flows into content" case. The per-app units
migrate every other site from the inventory written in step 1.

## What I looked at (fresh shots at HEAD, all opened)

- Owner device shot `054d28c4-image.jpg`.
- `target/shots/messages/01-inbox-dark.png`: the inbox now meets the layout spec.
- `target/shots/messages/11-row-menu-dark.png`: **the worst screen**. A bottom card
  (`menu_card`, `apps/messages/src/main.rs:5921`) with `GlassSpec::control` over a 0.4
  black scrim. The inbox reads straight through the card, with titles, times and badges
  overlapping the menu labels. The list has **duplicate items**: Pin x2, Archive x2,
  Mute x2, plus both "Mark as unread" and "Mark as read". A merged WhatsApp+SMS thread
  pushes `wa_menu_items` and the SMS items one after the other. There are no groups. The
  "Delete conversation" item sits on top of the tab bar. "Mute..." and Delete replace the
  whole list in place (`mute_choices`, `delete_confirm`) with no back affordance.
- `target/shots/stops/07-saved-stop-sheet-dark.png`: the Saved header is a wall of four
  full-size buttons (Export GPX, Export JSON, Import, then a full-width Copy list) plus Sort.
  The sheet's rows are plain labels with no icons, and the list behind shows through the
  top of the sheet.
- `apps/stops/src/saved_ui.rs:447-560`: tapping a saved stop sets `expanded` and **inserts
  a row of glass pills into the list** (Show on map, Open in maps, Up, Down, Delete,
  "Delete X?" armed). This is literally content flowing into the page.
- `target/shots/phone/09-call-details-dark.png`: the sheet is too transparent (contact
  avatars blur through every row), and its actions are bare blue text links with an
  icon-less "Add note".
- `target/shots/contacts/05-detail-dark.png`: the "..." on each field opens a sheet
  (`open_field_sheet`) where a small anchored menu belongs. The action circles look
  fine, but "Connect WhatsApp to chat and call from her" is clipped at the right edge.
- The pinned gpui (`d6c8412`) has `deferred`, `deferred_overlay`, `anchored()` with
  `Anchor`, `snap_to_window_with_margin`, and `LongPressEvent { position }`. `field.rs:1954`
  already draws its edit menu with `deferred(..).with_priority(10)` from bounds stored in
  a `Cell` during prepaint. Reuse that pattern. The skill's `07-components.md` section 9
  gives the menu recipe: `GlassSpec::sheet`, radius 14, 44 px rows, 0.33 px separators,
  and a morph from the source (`motion_tokens::morph`).

## Design decisions (go into docs/design/layout.md section 2.15 / 2.16)

- **Rule:** a tap never inserts controls into list or page content. Use one of these instead:
  - **Popover menu.** Up to 8 short actions about one element, like a row long-press, a
    "..." button or a header "more" button. It is anchored to the element or the press
    point.
  - **Sheet.** Rich content (details, forms, QR, pickers) or more than 8 actions.
  - **Confirm.** A destructive confirmation shows *inside the same popover*: a title line,
    the red action and Cancel. It never opens as a second card or as inline text in the list.
  - **Submenu** (Mute for ...). It pushes inside the popover with a 44 dp "< Mute" back
    row and slides 24 dp horizontally, so the popover itself never jumps.
- **Popover geometry (dp):** width = clamp(longest label + 16 + 20 + 12 + 16, 220, 280),
  never wider than the viewport minus 2 x 16. Rows are 44 high with the icon *leading*
  (20 dp at x 16, label at x 48, matching the app's ListRow icon column). Groups are split
  by an 8 dp band in `separator` colour at 0.5 alpha, and items inside a group by a
  0.5 dp hairline inset 48. Corner radius 14. The menu sits 8 dp from the anchor, below
  it if it fits above the bottom limit, otherwise above it. Horizontally it is
  right-aligned to the anchor when the anchor is in the right half, left-aligned otherwise,
  and clamped to the 16 dp margins. Limits are top = `safe.top + 8` and bottom =
  `viewport - content_bottom_inset()`, so a menu never covers the tab bar. If neither
  side fits, the menu caps its height to the larger side and scrolls.
- **Material:** overlays (menu, sheet, action sheet) use a new `GlassSpec` "thick": the
  `sheet` optics, blur 24, and a tint of `background` at 0.86 (dark) / 0.82 (light).
  The scrim behind a popover is transparent but catches taps (tap outside = dismiss).
  The scrim behind a sheet stays at its current alpha.
- **Buttons (one set, `rovsti_ui::button`):**
  - Styles: `Filled` (accent fill, `readable_on` ink), `Tinted` (accent at 0.16 alpha,
    `accent_text` ink), `Gray` (`secondary_background`, label ink), `Plain` (no fill,
    `accent_text`), and `Destructive` as a role that switches the colour to red in any style.
  - Sizes: `Large` 50 h / radius 14 / Headline, `Regular` 44 h / radius 12 / Body w600,
    `Small` 32 h visual / radius full / Subheadline w600 with a 44 dp hit area.
  - Content is an optional 18 dp icon, a gap of 8, then the label, centred. `full_width()`
    is available.
  - A row of buttons uses equal widths and a gap of 8. More than 3 secondary actions are
    never shown as buttons; they go in a "..." popover.

## Steps (each leaves every check green)

### 1. Spec and inventory
- `docs/design/layout.md`: add **2.15 Menus, popovers and confirms** and **2.16 Buttons**
  with the decisions above (numbers, material, the rule, ASCII anatomy of a 2-group menu).
  Add audit rows M14 (duplicate row-menu items for merged threads), S13 (inline pill row
  in Saved), S14 (Saved header button wall), P12 (sheet transparency and text-link
  actions in call details), C12 (field "..." opens a sheet; WhatsApp connect row clipped),
  and X10 (all overlays readable through).
- Add an **inventory table** (2.15a): every site that inserts controls into content or uses
  a bottom card or sheet for a short action list. Grep `menu_card`, `expanded`,
  `confirm_delete`, `_choices`, `glass_pill(` and `open_field_sheet`. List each site as
  file:line, today's behaviour, its target (popover / sheet / confirm) and the owning
  unit (messages, phone, contacts, stops). The per-app units work from this table.
**Acceptance:** doc only. The inventory has at least the sites named above, including
`apps/messages/src/main.rs` `menu_card` callers, `conversation.rs`,
`sms_conversation.rs`, `remind_ui.rs`, `date_jump_ui.rs`,
`apps/stops/src/saved_ui.rs` `expanded` and `apps/contacts/src/detail.rs`
`open_field_sheet`. All checks pass.

### 2. Pure menu model and popover geometry (platform-free, unit-tested)
- `crates/rovsti_ui/src/layout.rs`, `geom`: add a pure
  `place_popover(anchor: Rect, menu: Size, viewport: Size, top: f32, bottom: f32, margin: f32, gap: f32) -> Placement { rect: Rect, below: bool, max_h: f32 }`
  and `popover_width(longest_label_dp: f32, viewport_w: f32) -> f32`. Use the plain
  `Rect` / `Size` f32 structs already in `geom` (add them if they are missing; no gpui types).
  Tests cover: below when it fits; flip above near the bottom; clamp to the 16 dp margins
  at both edges; right alignment for an anchor in the right half; the cap and scroll case
  when neither side fits; never past `bottom` (the tab-bar inset); and a zero-size anchor
  (long-press point).
- `menu_height(groups: &[usize]) -> f32` = 44 x items + 8 x (groups - 1) + 2 x 6 dp
  vertical padding. Test it.
- Add a pure model `crates/rovsti_ui/src/menu/model.rs` (no gpui imports):
  `MenuItem { key: &'static str, label, icon_name, role: Normal|Destructive, enabled, checked, submenu: bool }`,
  `MenuGroup(Vec<MenuItem>)`, and `MenuModel::new(groups)`. The constructor drops empty
  groups and **debug-asserts unique keys**. Add `MenuModel::dedup_by_key` for callers that
  merge two sources. Tests cover the dedupe, the empty-group removal and the order kept.
**Acceptance:** `cargo test -p rovsti_ui geom menu` passes with at least 10 new tests. All
checks pass.

### 3. `Overlay` material and the `Menu` popover component
- `crates/liquid_glass_kit/src/glass.rs`: add `GlassSpec::thick(theme)` built from
  `sheet(theme)` with only existing fields changed (blur 24, tint = theme background at
  0.86 dark / 0.82 light). `crates/rovsti_ui/src/sheet.rs` panel and `menu_card` users
  switch to it in step 6. `Sheet` switches now (one line), and its shots are re-rendered.
- `crates/rovsti_ui/src/menu.rs` (gpui):
  - `AnchorSlot`: an `Rc<Cell<Option<Bounds<Pixels>>>>`, filled by a `canvas` prepaint
    child exactly as `field.rs` stores `content`, with `AnchorSlot::point(Point)` for
    long-press positions.
  - `MenuState { open: Spring (motion_tokens::morph), page: Root|Submenu(key)|Confirm(key), slide: Spring }`
    with `open_at(anchor)`, `close()`, `push_submenu`, `confirm`, `back()` and
    `tick(dt) -> bool`. Reduced motion gives a 120 ms fade, like `Sheet`.
  - `Menu::render(model, state, theme, window, on_select)`: a
    `deferred(..).with_priority(20)` full-window transparent `occlude()` catcher that
    dismisses on click. On top of it sits an absolutely positioned
    `glass(GlassSpec::thick)` panel at `place_popover(..)`. It scales from 0.92 to 1.0
    around the anchor-side corner and fades in. Rows follow the 2.15 geometry; the
    destructive role uses `colors.red`; the Submenu page has the back row; the Confirm
    page has a Footnote prompt, a red `Button::Regular` and a `Gray` Cancel.
  - System Back closes a submenu or confirm page first, then the menu. The doc comment
    shows the owner pattern (like `Sheet`).
  - Check every gpui call against the pinned checkout before using it (`anchored`,
    `deferred`, `canvas`, `occlude`, `on_mouse_down_out` if used). Do not invent any.
- `#[gpui::test]`s: open, then tap outside, closes the menu; selecting an item calls
  `on_select` with its key once; Back from a submenu returns to the root.
**Acceptance:** `cargo test -p rovsti_ui menu` passes. All checks pass.

### 4. `Button` set
- `crates/rovsti_ui/src/button.rs`: `Button::new(id, label).style(..).size(..).icon(..).destructive().full_width().disabled()`
  with the 2.16 numbers. It returns a `Stateful<Div>` so callers add `on_click`. The
  hit area is always at least 44 dp (`Small` pads invisibly).
- Pure `button_width(label_dp, icon: bool, size) -> f32` and `buttons_row_widths(n, avail, gap) -> f32`
  in `layout::geom`, with tests.
- Use `accent_text` / `readable_on` from round 2 for ink. The theme table test adds
  `Tinted` ink on its own fill and `Filled` ink on accent, both at least 4.5:1 in both
  themes.
- `components::glass_pill` gets a doc line: "Prefer `Button`; kept for migration". Do
  not delete it.
**Acceptance:** `cargo test -p rovsti_ui button theme` passes. All checks pass.

### 5. Gallery `04-menus` proves the components headless
- `crates/rovsti_ui/src/shots/menus.rs` (feature `shots`) renders
  `04-menus-{dark,light}.png` over a busy fake inbox, so legibility is tested. It shows:
  (a) a 2-group row menu below a mid-screen anchor, (b) the same menu flipped above an
  anchor near the tab bar, (c) the "Mute for" submenu page, (d) the delete Confirm page,
  and (e) a button matrix (4 styles x 3 sizes, plus destructive and disabled). Draw one
  state per tile, or split the gallery into 04a to 04d if one window cannot hold it.
- Asserts in the test, using `shots/color.rs`: `contrast_in` over a 40 x 16 dp patch of
  menu body with no labels is under 1.6 (nothing reads through). Label text inside rows
  is at least 4.5. The menu rect stays inside [safe.top + 8, viewport - content_bottom_inset].
- Run `cargo test -p rovsti_ui --features shots menus -- --ignored --nocapture`, then
  **open both PNGs**.
**Acceptance:** the PNGs show crisp, opaque popovers with grouped 44 dp rows, a visible 8 dp
band between groups, leading icons aligned at x = 16 dp and labels at 48 dp, and no list
text showing through. Buttons have equal heights per size and no clipped labels. The
asserts pass. `cargo clippy -p rovsti_ui --features shots --all-targets -- -D warnings` is clean.

### 6. Proof screen: the Messages row menu becomes an anchored popover
- `apps/messages/src/main.rs`: `open_row_menu` takes an `AnchorSlot`, either the row's
  bounds or the long-press `position` from `LongPressEvent` (`inbox.rs:1231`). It builds a
  `MenuModel` instead of `Vec<AnyElement>`:
  - group 1: Select
  - group 2: Pin/Unpin, Mark as read **or** unread (exactly one), Mute... (submenu) /
    Unmute, Archive/Unarchive
  - group 3: Block (SMS number senders only), Delete conversation (destructive -> Confirm page)
  
  For a merged WhatsApp+SMS row, each action appears **once** and applies to both
  threads. Put the merge rule in a pure function in `apps/messages/src/` with tests,
  e.g. `row_menu_items(wa: Option<WaFlags>, sms: Option<SmsFlags>) -> MenuModel`.
  The tests cover WhatsApp-only, SMS-only, merged (no duplicate keys), in-Spam and
  blocked cases.
- `mute_choices` and `delete_confirm` go away in favour of `MenuState` pages. The header
  bell `mute_menu_overlay` uses the same `Menu`, anchored to the bell button.
- `menu_card` stays for its other callers (inventory, per-app unit) but switches to
  `GlassSpec::thick` so it is no longer see-through.
- `apps/messages/src/shots.rs` shot 11 passes a mid-list anchor (Ayşe's row). Add
  `11b-row-menu-mute` (submenu) and `11c-row-menu-delete` (confirm).
**Acceptance:** render the messages shots. Open `11-row-menu-{dark,light}`, `11b-*`, `11c-*`
and the before copy saved from this round. The popover sits under Ayşe's row, right-aligned
at 16 dp, at most 280 dp wide, opaque, grouped, and has no duplicates. "Delete
conversation" is red and above the tab bar. The rest of the inbox is unchanged
(`01-inbox-*` byte-identical to before this step). The unit tests pass, and all checks
plus the shots clippy line pass.

### 7. Second proof: Stops Saved no longer inserts pills into the list
- `apps/stops/src/saved_ui.rs`: remove the `expanded` inline pill row
  (`:447-560`, and `expanded` in `main.rs`). A row tap keeps its primary meaning (opens
  the existing saved-stop sheet). Each row gets a trailing 44 dp "..." hit target
  (`Icon::LucideEllipsis`, 20 dp, `secondary_label`). It and a long-press open a `Menu`:
  - group 1: Show on map, Open in maps
  - group 2: Move up, Move down (disabled, not hidden, at the ends or when sort is not
    "Saved")
  - group 3: Delete (Confirm page "Delete <name>?")
  
  `confirm_delete` goes away.
- The Saved header: Export GPX, Export JSON, Import and Copy list move into one header
  "more" button (`HeaderAction`, `LucideEllipsis`) that opens a `Menu`
  (group: Copy list; group: Export GPX, Export JSON; group: Import). Search and Sort
  stay on one row: search flex and a `Button::Small Gray` "Sort: Saved" that opens a
  `Menu` with the sort choices, `checked` on the current one.
- Stops shots: add `06b-saved-row-menu-{dark,light}` and `06c-saved-more-menu-*`.
**Acceptance:** open `stops/06-saved-list-*`, `06b`, `06c` and `07-saved-stop-sheet-*`.
The header is title + one 40 dp "..." button, the search and sort row is a single 44 dp
line, the list rows never grow on tap, and the menus are anchored and opaque.
`cargo test -p rovsti_stops` passes, as do all checks and the shots clippy line.

### 8. Docs, baselines, full verification
- `docs/design/layout.md` 2.13 checklist: add Menu, Button and `GlassSpec::thick`. In 2.14,
  list Messages row menu / bell and Stops Saved as migrated. Mark the inventory rows that
  are done.
- `docs/screenshots.md`: document the `menus` gallery command and the new 11b/11c and
  06b/06c shots.
- Refresh `docs/screenshots/baseline/messages.png`, `stops.png` and the `rovsti_ui` sheet.
  Phone and Contacts change only through the `Sheet` material; refresh them too and say
  so in the commit.
- Confirm `rovsti_ui/01-fields-*` and `02-field-touch-*` are unchanged; the field edit
  menu must not move.
- Run every check in the unit list plus the shots clippy line. Watch disk (6.2 GB free
  at plan time): clean `target/debug/incremental` and stale test binaries first.
**Acceptance:** all checks pass. The docs match the code. The listed PNGs were opened and
match 2.15 / 2.16.
