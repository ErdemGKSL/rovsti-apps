# Thread scroll position (Messages, SMS and WhatsApp threads)

Status: implemented 2026-11-16 (see Evidence at the end). Scope: `android/messages` (Kotlin/Compose) plus one getter in `android/design`.
Owner's request: "When I left the app I was at the bottom; new messages came in; when I come back the chat must
again be at the bottom, unless I scrolled up manually."

Paths below are relative to `android/messages/src/main/kotlin/dev/rosty/messages/` unless they start with `android/`.

## 1. What happens today, and why the owner sees the bug

1. The list is a `LazyColumn(reverseLayout = true)` with `items(state.items, key = { it.key })`
   (`conversation/ConversationScreen.kt:275-285`). Index 0 is the newest bubble (`conversation/DayLabels.kt:69`).
2. Nothing scrolls to index 0 when a message arrives. A keyed lazy list keeps its first visible item by key on every
   measure (`LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved`, foundation 1.12.1, checked with
   `javap`: it is unconditional, also at index 0 offset 0). So a reader at the bottom stays on the old newest bubble
   and the new one is laid out below the viewport. This is the bug: it shows most after a background stay, when
   several messages are applied in one recomposition.
3. `conversation/ThreadReturn.kt` (commit `c0dff1df`) jumps to the newest after 15 minutes away. It does not help
   for shorter stays and it also moves a reader who scrolled up on purpose.
4. The jump button shows when `firstVisibleItemIndex > 2` and counts every bubble (mine too) that is newer than the
   last one seen at the end (`ConversationScreen.kt:590-621`, `JUMP_AFTER_ITEMS` at `:988`).
5. Find, jump to date and reveal call `listState.animateScrollToItem(index)` (`ConversationScreen.kt:232-237`).
6. An unread divider row exists (`ConversationItem.Divider`, `DayLabels.kt:64`; `SmsThreadViewModel.kt:255-265`,
   `wa/WaThreadViewModel.kt:801-805`) but the thread never scrolls to it. Two defects in it matter here:
   - it is recomputed on every publish as "the last N incoming bubbles" (`rosty_core/src/unread_jump.rs:50`), so it
     moves down by one for every message that arrives while the thread is open;
   - `unreadAtOpen` is set once per view model (`WaThreadViewModel.kt:453`, `SmsThreadViewModel.kt:201`) and the view
     models are activity-scoped and never cleared (`wa/WaThreadPage.kt:205-251`, `conversation/SmsThreadPage.kt:179-203`),
     so a thread opened a second time shows the divider of its first opening.

### 1.1 How a thread page lives (verified)

| Situation | Composition | Saveable state | View model |
|---|---|---|---|
| App to background and back | kept (recomposition is paused while stopped, then all changes arrive at once) | kept | kept |
| Rotation, font scale, dark mode | kept: the activity handles these (`android/messages/src/main/AndroidManifest.xml:75`) | kept | kept |
| Keyboard, composer height | kept; the list is `weight(1f)` inside `Column(...imeOverlapPadding())` (`ConversationScreen.kt:273-274`), so only its height changes | kept | kept |
| Media viewer, bubble menu, sheets | kept; they are overlay entries over the page, lifecycle stays RESUMED | kept | kept |
| A page pushed on top (sender card, forward target, a thread opened from Starred) | disposed | kept by Navigation 3 while the `StackKey` is in the stack (`android/design/.../transition/RostyNavHost.kt:190-227`, default `rememberSaveableStateHolderNavEntryDecorator`) | kept |
| Back out of the thread, open it again | new | new | the same instance (activity store, key `wa-<jid>` / `sms-<id>-<addresses>`) |
| Another tab | not reachable: threads hide the tab bar (`MessagesRoute.kt:174-176`) | | |
| Notification tap, same thread, same route | `navigator.resetTo` (`android/shell/.../RostyShellActivity.kt:142-145`) rebuilds an equal `StackKey`: the page stays | kept | kept |
| Notification or reminder tap with another route (`reveal`, another depth or tab) | new page | new | same instance |
| Process death | new | restored (`TabNavigator.saver` + saveable holder) | new, loads the first page only (`WaThreadViewModel.PAGE = 50`) |

Each Nav3 entry has its own lifecycle owner that follows the activity
(`androidx.navigation3.scene.BackStackAwareLifecycleNavEntryDecorator`), so `LocalLifecycleOwner` inside a page is
right for "the page is visible".

Consequence: the scroll state belongs to the **page entry** (`rememberSaveable`), not to the view model. The view
model lives too long (a reopened thread would inherit the old place) and the saveable state has exactly the wanted
lifetime: kept under a pushed page, over rotation and process death, dropped when the thread is closed.

### 1.2 Why this is Kotlin and not `rosty_core`

`CLAUDE.md` puts domain logic in Rust. This is view state that must answer synchronously inside a frame (before the
measure pass); core ops are `suspend` calls. `rosty_core::unread_jump::JumpState` stays unused by the screen as it is
today. The model below is a pure Kotlin file with JVM tests, the same pattern as `returnsToNewest`.

## 2. The model

New file `conversation/ThreadScroll.kt`. No Compose, Android or coroutine imports.

### 2.1 Terms (reverse layout)

- **Spot**: `(index, offsetPx, key)` of the first visible item = `listState.firstVisibleItemIndex`,
  `firstVisibleItemScrollOffset` and that item's key. In a reversed list the first visible item is the one at the
  **bottom** edge, and the offset is how many pixels of it lie below the content start line (the bottom edge minus
  the 8 dp `contentPadding.bottom`). At rest at the newest message the spot is `(0, 0)`.
- **At the newest**: the list is empty, or `index == 0 && offsetPx <= slopPx`. `slopPx` is 48 dp in pixels. In words:
  the newest row is on screen and at most 48 dp of it is hidden. A reader inside a newest message taller than the
  screen (offset above the slop) is "scrolled up", on purpose.
- **Mode**: `Following` (pinned: new messages are shown) or `Anchored(key, offsetPx)` (keep my place: the saved spot).
- **Seen key**: the newest row the reader has had on screen since the thread was opened (only moves towards newer).
  `unseen` = incoming bubbles newer than it. It feeds the badge of the jump button.

### 2.2 Types

```kotlin
sealed interface ScrollMode {
    data object Following : ScrollMode
    data class Anchored(val key: String, val offsetPx: Int) : ScrollMode
}
enum class ScrollPhase { Opening, Restoring, Live }
enum class RowKind { Incoming, Outgoing, OutgoingPending, Unread, Label }

/** The rows, newest first; read in place, never copied. */
interface ScrollRows { val size: Int; fun key(index: Int): String; fun kind(index: Int): RowKind }

data class ScrollSpot(val index: Int, val offsetPx: Int, val key: String?)

data class ThreadScroll(
    val mode: ScrollMode = ScrollMode.Following,
    val phase: ScrollPhase = ScrollPhase.Opening,
    val seenKey: String? = null,
    val unseen: Int = 0,
    val leftAtMs: Long = NOT_LEFT,          // wall clock, saved
    // not saved:
    val newestKey: String? = null,          // rows.key(0) at the last Items event
    val wasEmpty: Boolean = true,
    val followPending: Boolean = false,
    val resumedAtMs: Long = 0,
)

sealed interface ScrollEvent {
    /** The rows changed (or are seen for the first time). */
    data class Items(val rows: ScrollRows, val env: ScrollEnv) : ScrollEvent
    /** The spot changed, or a command was applied. scrolling: a scroll is in progress that the screen did not start. */
    data class Moved(val spot: ScrollSpot, val scrolling: Boolean, val rows: ScrollRows, val held: Boolean) : ScrollEvent
    data class Released(val rows: ScrollRows) : ScrollEvent          // a hold ended
    data class Left(val nowMs: Long) : ScrollEvent                   // not visible any more
    data class Returned(val nowMs: Long) : ScrollEvent               // visible again, composition was kept
    data object OpenedAgain : ScrollEvent                            // a notification opened the thread that is up
    data object JumpToNewest : ScrollEvent                           // the round button
}

data class ScrollEnv(
    val nowMs: Long, val resumed: Boolean, val held: Boolean,
    val openReady: Boolean,      // Opening only: the unread divider of this opening is in the rows (7.1)
    val openAtTarget: Boolean,   // Opening only: a reveal target will be scrolled to
)

sealed interface ScrollCommand {
    data class ToNewest(val animated: Boolean) : ScrollCommand
    data class ToKey(val key: String, val offsetPx: Int) : ScrollCommand   // restore an anchor
    data class ToUnread(val key: String) : ScrollCommand                   // the divider near the top
}

data class ScrollLimits(val slopPx: Int, val staleMs: Long = THREAD_STALE_MS, val animateMaxRows: Int = 3,
                        val returnSettleMs: Long = 600)

fun reduce(state: ThreadScroll, event: ScrollEvent, limits: ScrollLimits): Pair<ThreadScroll, ScrollCommand?>
```

`RowKind.OutgoingPending` = my bubble in `BubbleState.Waiting` or `Sending` (WhatsApp outbox,
`WaThreadViewModel.kt:576-577`; SMS pending, `SmsThreadViewModel.kt:308`). It means "sent from this device just
now". `THREAD_STALE_MS`, `NOT_LEFT` and `returnsToNewest` move into this file unchanged.

### 2.3 Rules

`evaluate(spot)` = `Following` when at the newest, else `Anchored(spot.key, spot.offsetPx)` (keep the old anchor if
`spot.key` is null).

**Items**

1. `rows.size == 0`: set `wasEmpty = true`, nothing else (first load not there yet; a WhatsApp session swap publishes
   an empty page, `WaThreadViewModel.kt:350-354`).
2. `phase == Opening`: wait (return unchanged) until `env.openReady`. Then `phase = Live` and:
   - `env.openAtTarget`: `Following`, no command (the reveal scroll follows, rule Moved makes it an anchor);
   - a row of kind `Unread` exists: `mode = Anchored(thatKey, 0)`, command `ToUnread(thatKey)`;
   - else `Following`, no command (a new list state is at `(0, 0)`).
3. `phase == Restoring` (the state came out of the saver): `phase = Live`, `leftAtMs = NOT_LEFT`.
   - `Anchored`, not stale (`!returnsToNewest(leftAtMs, env.nowMs, limits.staleMs)`) and the key is in the rows:
     command `ToKey(key, offsetPx)`;
   - anything else: `Following`, command `ToNewest(animated = false)`.
4. `phase == Live`:
   - `wasEmpty` (the rows came back): `Anchored` with its key in the rows gives `ToKey`; otherwise `Following` and
     `ToNewest(false)`.
   - Fast path: `rows.key(0) == newestKey` gives no command (only the unseen count is refreshed when it is above 0).
   - `added` = index of the old `newestKey` in the rows (search the first 50 rows; -1 when not found).
   - **Own send**: `added > 0` and one of the rows `0 until added` is `OutgoingPending`: `mode = Following`,
     `followPending = false`, command `ToNewest(animated = true)`. Holds do not delay it.
   - `Following` and `env.held`: `followPending = true`, no command.
   - `Following`: command `ToNewest(animated)` with
     `animated = env.resumed && added in 1..limits.animateMaxRows && env.nowMs - resumedAtMs >= limits.returnSettleMs`.
   - `Anchored`: no command. The lazy list keeps the first visible item by key.
5. Always: `newestKey = rows.key(0)`, `wasEmpty = false`, `unseen` recounted (2.4).

**Moved** (only in `Live`; ignored in the other phases)

1. `scrolling`: `mode = evaluate(spot)`.
2. Not scrolling:
   - `Anchored`: `mode = evaluate(spot)`. This promotes to `Following` when the list came to rest at the newest
     without a scroll (the rows below were deleted, the unread run fits the screen) and refreshes the anchor after a
     data change (the anchor row was deleted and the list fell back to the index).
   - `Following` and not at the newest: stays `Following` when `followPending`; else `Anchored(spot)` (somebody moved
     the list with an instant scroll, for example an accessibility scroll or a test).
   - `Following`, `followPending`, not `held`: command `ToNewest(false)`, `followPending = false`.
3. Seen key: if the spot's row is newer than `seenKey` (or `seenKey` is not in the rows), `seenKey = spot.key`;
   recount `unseen`. In `Following` at the newest `unseen` is 0.

**Released**: `Following && followPending` gives `ToNewest(false)`, `followPending = false`.

**Left(now)**: `leftAtMs = now` when it is `NOT_LEFT`.

**Returned(now)**: `stale = returnsToNewest(leftAtMs, now, staleMs)`; `leftAtMs = NOT_LEFT`; `resumedAtMs = now`.
`Anchored && stale` gives `Following` and `ToNewest(false)`. Otherwise no command: in `Following` the Items event of
the pending recomposition does the work, in `Anchored` the place is kept and the badge counts.

**OpenedAgain**: `Following`, `ToNewest(false)`.

**JumpToNewest**: command `ToNewest(true)`. The mode becomes `Following` through `Moved` when the scroll ends there.

### 2.4 Unseen count

`unseen` = number of rows of kind `Incoming` at indices `0 until indexOf(seenKey)`; 0 when `seenKey` is at index 0.
If `seenKey` is not in the rows, the caller's spot replaces it. Recount only on an Items event or when `seenKey`
changes, and skip the scan while `unseen == 0 && mode == Following`. My own messages never count (today they do).

### 2.5 Invariants (assert them in the tests)

- I1. A data or layout change alone never turns `Following` into `Anchored` (only a scroll, or an instant move that
  no data change explains).
- I2. In `Anchored` no command is ever produced by an incoming message, an edit, a delete, a reaction or paging.
- I3. The jump button is visible exactly when `phase == Live && mode is Anchored`. So "the button is hidden" always
  means "new messages will be shown".
- I4. A jump to a message ends in `Anchored` unless the target is the newest row, so following cannot undo it.

## 3. Every event

| # | Event | Following (pinned) | Anchored (scrolled up) |
|---|---|---|---|
| 1 | Incoming message, page resumed | Shown. 1 to 3 new rows slide in (`animateScrollToItem(0)`), more appear at once | Place kept. Badge +1 per incoming bubble |
| 2 | Incoming message while in background, another app on top, screen off | On return the newest is at the bottom, no animation | Place kept, badge counts; after 15 minutes away see 14 |
| 3 | I send (composer, attachment, voice note, send preview, forward, share-in, SMS with Undo) | Slides to the newest | Goes to the newest and pins. Rule "own send": a new pending outgoing bubble |
| 4 | My message from another device (WhatsApp on the phone), a retry of a failed send | Like 1 | Place kept (it is not pending-new) |
| 5 | Edit, delete, reaction, tick or state change, star, reminder mark | No command. Reverse layout keeps `(0, 0)`; the newest row grows upwards | No command. The first visible row keeps its offset; rows above it move |
| 6 | Media height change (thumbnail arrives and the aspect becomes known, file row becomes a picture) | Same as 5 | Below the viewport: not measured, no effect. Inside or above: the anchor row keeps its bottom edge |
| 7 | The anchor row itself is deleted | n/a | The list falls back to the same index; the model re-anchors at rest |
| 8 | Newest rows deleted or an SMS send undone while near the end | Stays pinned | If the list now rests at the newest: becomes `Following` (button hides) |
| 9 | Older history paged in (`OlderOnReachingTheEnd`, `wa/WaThreadPage.kt:441-457`; the Load Earlier button) | No effect: rows are added at the far end | No effect, the first visible row is unchanged |
| 10 | First load: empty, then filled | Opening rule (2.3 Items 2) | |
| 11 | Loaded from the offline store, then the gateway adds rows | Newer rows: like 1. Rows in the middle: no effect | Place kept |
| 12 | Rows go empty and come back (session swap, relink) | Newest | Anchor restored by key, else newest |
| 13 | WhatsApp chat merge or rekey (WA-R1, `WaThreadViewModel.kt:397-402`, `swap` at `:417`) | Bubble keys are message ids (`keyOf`, `:1480`), not chat ids, so they survive. Many rows may arrive at once: newest, no animation | Place kept by key. The scroll state must not be keyed by `jid` or `tag` (the page keeps one `listState` over a swap) |
| 14 | Typing indicator | It is the header subtitle (`WaThreadViewModel.kt:516`), not a row. It changes `subtitleRoom` (`ConversationScreen.kt:281`, the padding at the far end): no movement | Same |
| 15 | Keyboard opens or closes | The list only gets shorter or taller. A reversed list is anchored at the bottom: `(0, 0)` stays, the newest stays above the composer | The bottom-most visible row stays above the composer |
| 16 | Composer grows: multi-line, reply bar, attachment chip, recording panel, Scheduled strip, quick replies, SIM row, select bar, default-SMS card | Same as 15 (all are siblings below the list in the `Column`) | Same as 15 |
| 17 | Rotation, font scale, text-size setting, dark mode | No recreation (manifest). `(0, 0)` stays | Same index and pixel offset; the list clamps an offset larger than the row. Good enough, no command |
| 18 | Media viewer, bubble menu, sheet, alert open | Hold: a new message is not shown under the overlay (`followPending`); when the overlay closes the list goes to the newest at once. Own send is not held | Nothing to do |
| 19 | Select mode | Hold, like 18 | Nothing to do |
| 20 | Finger on the list (drag or fling) while a message arrives | Hold until the scroll ends; then shown if still at the newest, else the reader is `Anchored` | Nothing to do |
| 21 | Page pushed on top, then back | Composition is new, state restored: newest | Anchor restored by key and offset (view model still has all pages). 15-minute rule applies |
| 22 | Process death, then return | Newest | Not stale and the anchor is in the first page: restored. Otherwise newest (no paging to find it) |
| 23 | Thread closed and opened again | New entry: Opening rule. Never the old place | |
| 24 | Notification tap for the thread that is already up (same route) | Newest | Newest and pinned (`OpenedAgain`): the tap asks for the new message |
| 25 | Notification, reminder, Starred, global search with a message (`route.reveal`) | New page; Opening with `openAtTarget`; the reveal scroll ends `Anchored` on the target (I4) | |
| 26 | Find in chat, jump to date, gallery row (`tools.scrollTo`) | Programmatic animated scroll, then `evaluate`: `Anchored` on the target | Same |
| 27 | Jump button | n/a (hidden) | Animated scroll to the newest, then `Following`, badge 0 |
| 28 | Overscroll stretch at the newest end | Spot stays `(0, 0)`: stays pinned | n/a |
| 29 | Newest message taller than the screen arrives | Its end is shown (like WhatsApp); scrolling up inside it un-pins | Place kept |
| 30 | TalkBack or keyboard scroll | Treated as a user scroll (`Moved`) | Same |

### 3.1 The time-away rule (scrolled up and away for a long time)

Decision: **keep 15 minutes, as a rule inside the model, only for `Anchored`**.

- Pinned always wins and needs no timer: any time away, the thread comes back at the newest.
- Scrolled up and back within 15 minutes: the place is kept and the button's badge shows what arrived.
- Scrolled up and away for 15 minutes or more (or the clock went back): newest, pinned. The owner asked for exactly
  this number today (`c0dff1df`), and after a long absence the old reading place has lost its context.
- "Away" starts when the page stops being visible: lifecycle below `STARTED`, or the page leaves composition (another
  page on top). Today's code uses `RESUMED`; `STARTED` is better because a thread behind a permission dialog, the
  notification shade or in split screen is still being read.
- The time is wall clock and saved with the page, so it also covers process death (as today).

`ThreadReturn.kt` is **absorbed**: `ScrollToNewestAfterAway` and the file are deleted; `returnsToNewest`,
`THREAD_STALE_MS`, `NOT_LEFT` move to `ThreadScroll.kt`; `ThreadReturnTest` becomes part of `ThreadScrollTest`.

## 4. Unread messages when a thread opens

Decision: **WhatsApp-like, by geometry instead of a count**. When a thread opens with unread messages, the divider
"N unread messages" is placed near the top of the list area (64 dp times the font scale below the header, so a
sliver of the last read message shows above it). The lazy list cannot scroll past the newest row, so when the whole
unread run fits on the screen the request ends at the bottom by itself. No threshold to tune:

- few unread (they fit): the thread opens at the bottom, `Following`, no button;
- many unread: the thread opens at the divider, `Anchored`, the jump button shows with the number of unread bubbles
  still below the screen. Scrolling down lowers the badge (seen key); at the bottom it is 0 and the thread pins.

Reading state is unchanged: opening marks everything read as today (`markRead`, `WaThreadViewModel.kt:817`;
`SmsThreadViewModel.kt:173`). A reveal target wins over the divider (`openAtTarget`). While the page stays up (also
through a background stay) no second divider is made: pinned shows the newest, scrolled up keeps the place and counts.

This needs the two divider defects of section 1 fixed (step 7): freeze the divider on a bubble key per opening, and
start a new opening when the thread is opened again.

## 5. Implementation shape

### 5.1 Files

| File | Change |
|---|---|
| `conversation/ThreadScroll.kt` | new, the pure model of section 2 |
| `conversation/ThreadScrollState.kt` | new, the Compose glue (5.2) |
| `conversation/ThreadReturn.kt` | deleted (3.1) |
| `conversation/ConversationScreen.kt` | uses the glue: `:229-237`, `JumpToLatest` `:590-621`, `JUMP_AFTER_ITEMS` `:988` removed; new optional parameters |
| `conversation/ConversationModels.kt` | `ConversationUi.opens: Int = 0` (7.1) |
| `conversation/SmsThreadViewModel.kt`, `wa/WaThreadViewModel.kt` | `opened()`, frozen divider (step 7) |
| `conversation/SmsThreadPage.kt`, `wa/WaThreadPage.kt` | call `opened()`, pass the new parameters; WhatsApp reveal runs once (step 8) |
| `notify/OpenConversation.kt`, `MainActivity.kt` | `ThreadOpens` signal (step 9) |
| `android/design/.../overlay/OverlayHost.kt` | `OverlayController.hasModal` (step 6) |

### 5.2 `ThreadScrollState` (screen level, saved with the page)

```kotlin
@Stable
class ThreadScrollState internal constructor(val listState: LazyListState, initial: ThreadScroll, ...) {
    val jumpVisible: Boolean            // snapshot state, read only by JumpToLatest
    val unseen: Int                     // snapshot state, read only by JumpToLatest
    fun jumpToNewest()                  // the button
    suspend fun jumpTo(index: Int)      // find, date, reveal: animated, guarded
}

@Composable
fun rememberThreadScroll(listState: LazyListState, now: () -> Long = System::currentTimeMillis): ThreadScrollState
```

- The model value is a plain field (not snapshot state); only `jumpVisible` and `unseen` are `mutableStateOf`, and
  only `JumpToLatest` reads them. `ConversationScreen`'s body and the bubbles read nothing from it
  (`ConversationRecompositionTest` must stay green).
- `rememberSaveable` with a `Saver` that writes `[anchorKey or "", offsetPx, seenKey or "", leftAtMs]`; restoring
  gives `phase = Restoring`. A new entry gives `phase = Opening`. Not keyed by thread id (item 13).
- The pages keep `rememberLazyListState()` (`WaThreadPage.kt:267`, `SmsThreadPage.kt:207`); its own index-based
  restore is harmless because rule Items 3 overrides it before the first measure with rows.

Mechanics, all inside one composable `ThreadScrollEffects(scroll, state, env...)` called from `ConversationScreen`
in place of line 230:

1. **Items, before the measure.** `SideEffect { scroll.onItems(state.items, env) }`. It returns at once when
   `state.items` is the same instance as last time. Rows are read through a `ScrollRows` adapter over
   `ImmutableList<ConversationItem>` (no allocation; the view models publish a new list on every change). Commands
   that are not animated run right there with `listState.requestScrollToItem(index, offset)`.
   Why this API: in foundation 1.12.1 `requestScrollToItem` only records the position, forgets the last known first
   key and invalidates the measure scope (`snapToItemIndexInternal(..., forceRemeasure = false)`, checked with
   `javap`), so it is safe in a `SideEffect`, wins over the keep-by-key rule for the next measure, and the new
   position is drawn in the same frame (no one-frame flash). It also cancels a scroll in progress, which is why
   holds exist. `scrollToItem` is a suspend function and would land a frame late.
2. **Animated commands** go to a `Channel<ScrollCommand>(CONFLATED)` read by a `LaunchedEffect(scroll)` with
   `collectLatest`: `scroll.programmatic { listState.animateScrollToItem(0) }`. `programmatic` sets a flag in
   `try/finally`; at the end it sends one `Moved(spot, scrolling = false)`. A finger on the list cancels the
   animation (user input has the higher mutate priority) and the normal samples take over.
3. **Moves.** `LaunchedEffect(scroll) { snapshotFlow { Sample(listState.isScrollInProgress,
   listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, scroll.tick) }.collect { ... } }`.
   Samples are dropped while the flag of 2 is set. `scrolling = sample.inProgress`. The key of the spot is
   `listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String`, read in the collector.
   `scroll.tick` is bumped after every instant command so a `Moved` arrives even when the position did not change
   (needed for `ToUnread` that ends at the bottom).
4. **ToUnread.** `H = layoutInfo.viewportSize.height - beforeContentPadding - afterContentPadding`,
   `room = 64 dp * fontScale` in px; `requestScrollToItem(dividerIndex, -(H - room).coerceAtLeast(0))` (a negative
   offset moves the row away from the start edge; the list clamps at the newest row). When the viewport is not
   measured yet (`height == 0`, a reopened thread whose view model already has rows), wait in a `LaunchedEffect` for
   `snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }` and then do the same.
5. **Holds.** `held = (listState.isScrollInProgress && !programmatic) || tools.selected != null || overlays?.hasModal == true`.
   A `LaunchedEffect` with `snapshotFlow { held }` sends `Released` when it turns false.
6. **Away.** One `DisposableEffect(lifecycle)` with a `LifecycleEventObserver`: `ON_STOP` sends `Left(now())`,
   `ON_START` sends `Returned(now())` (not the first `ON_START` of a new composition); `onDispose` sends
   `Left(now())`. `env.resumed = lifecycle.currentState.isAtLeast(RESUMED)`.
7. **Find, date, reveal.** Lines 232-237 become: `if (index >= 0) scroll.jumpTo(index)`, then `actions.onScrolled()`.
   `jumpTo` is `programmatic { listState.animateScrollToItem(index) }`.
8. **Jump button.** `JumpToLatest(scroll, labels, modifier)`: shown when `scroll.jumpVisible`, badge
   `minOf(scroll.unseen, JUMP_CAP)`, click `scroll.jumpToNewest()`. The `seenNewest` bookkeeping is removed.
9. **Reopen signal.** `LaunchedEffect(reopen) { reopen?.collect { scroll.onOpenedAgain() } }`.

New parameters of `ConversationScreen`, all with defaults so existing tests and callers compile:
`openSeq: Int = 0`, `openAtTarget: Boolean = false`, `reopen: Flow<Unit>? = null`, `now: () -> Long = System::currentTimeMillis`.
`env.openReady = state.loaded && state.opens >= openSeq`.

Things deliberately not done: no `Modifier.animateItem()` on the rows (it would animate the key-follow frame and
fight the follow scroll); no custom fling or overscroll; no scroll state in the view models.

## 6. Steps, in order

Each step ends green on the module tests; the full gate (`scripts/kotlin-ui-check.sh`, release only) runs at the end.

1. **Probe test first.** In a new `ThreadScrollScreenTest : ShotTest` (pattern of `ConversationRecompositionTest`),
   prove the two facts the design stands on and keep them as regression tests: (a) at rest the spot is `(0, 0)` and
   after `scrollBy(+px)` the offset grows or the index rises (reverse layout); (b) with today's code, adding a row at
   index 0 while at `(0, 0)` leaves the old row first visible (the bug; invert the assertion in step 4).
2. **Model.** `ThreadScroll.kt` and `ThreadScrollTest.kt` (table 8.1). Move the three `ThreadReturnTest` cases in.
3. **Glue.** `ThreadScrollState.kt` (5.2 items 1 to 6), without unread opening (`ToUnread` treated as no command for
   now) and without the reopen signal.
4. **Screen.** Wire `ConversationScreen` (5.2 items 7, 8, parameters), delete `ThreadReturn.kt` and its test, remove
   `JUMP_AFTER_ITEMS`. Screen tests 8.2 numbers 1 to 10.
5. **Check the shots** `e6.6-scroll-to-bottom-count-*` and `e6.6-unread-divider` (`E63ShotTest.kt:190`, `:266`): they
   must still pass unchanged (the first uses an instant `scrollToItem(12)`, covered by rule Moved 2).
6. **Overlay hold.** Add to `OverlayController` (`android/design/src/main/kotlin/dev/rosty/design/overlay/OverlayHost.kt`,
   next to `hasEntries` at `:206`): `val hasModal: Boolean get() = entries.any { it.modal && it.state.targetState }`.
   Popovers, menus, sheets and alerts are modal; toasts and the undo pill are not (`Toast.kt:122`), so the SMS
   "Sending… Undo" pill does not hold. Verify that the lifted bubble menu and the media viewer present modal
   entries; if one is not, say so in the report instead of widening the rule.
7. **Unread opening.**
   1. `ConversationUi.opens` = number of openings whose unread count is known.
   2. Both view models: `fun opened(): Int` increments an open counter and returns it. WhatsApp: on a reopened model
      set `unreadAtOpen = chat?.unread ?: 0` at once (null while the chat was never read; `reload` sets it), clear
      the frozen divider key, `reloads.tryEmit(Unit)`. SMS: `unreadAtOpen = null`, clear the key, then
      `loadNewest()` followed by `markRead(thread)` in that order (as in `init`, `SmsThreadViewModel.kt:172-173`);
      publish the new `opens` only from the `rebuild` that runs after the count is known.
   3. Frozen divider: `dividerOf` returns the stored key while it is still among the bubbles; it asks Rust
      (`unreadDivider`) only when no key is stored. Existing tests on the divider keep passing; add one per view
      model: "an incoming message after opening does not move the divider".
   4. Pages: `val openSeq = rememberSaveable { model.opened() }`, passed to `ConversationScreen` together with
      `openAtTarget = route.reveal != null`.
   5. Glue: execute `ToUnread` (5.2 item 4). Screen tests 8.2 numbers 11 and 12.
8. **WhatsApp reveal once.** `WaThreadPage.kt:299` runs `model.reveal` again every time the page comes back into
   composition (after a pushed page), which would undo the restored place. Guard it with a
   `rememberSaveable(route.reveal) { mutableStateOf(false) }` flag like `RevealHit` does (`SmsThreadPage.kt:563`).
9. **Reopen signal.** `object ThreadOpens` beside `OpenConversation` (`notify/OpenConversation.kt`): a
   `MutableSharedFlow<String>(extraBufferCapacity = 4)`, `fun opened(tag: String)`, `fun of(tag: String): Flow<Unit>`.
   `MainActivity.routeFor(intent)` (`MainActivity.kt:38-47`) calls `ThreadOpens.opened(tag)` when the intent has a
   thread tag and no message id. Pages pass `reopen = remember(tag) { ThreadOpens.of(tag) }` (`model.tag` for SMS,
   `WaRekeys.tagOf(jid)` for WhatsApp). A new page misses the event on purpose (its Opening rule applies).
10. **Docs and gate.** A short "Thread scroll" section in `docs/kotlin-ui/` (the rules of section 3 as a table),
    then `scripts/kotlin-ui-check.sh`. No Rust source changes; the Rust checks of `CLAUDE.md` still run in the gate.

## 7. Notes for the implementer

### 7.1 Opening sequence

Opening must not finish on rows that still carry the divider of an earlier opening (reopened thread, cached view
model). That is what `openSeq` and `ConversationUi.opens` are for: `opened()` returns the number synchronously, the
state reaches it only after the unread count of this opening is in the published rows. After process death the
saved `openSeq` is not compared (phase `Restoring` ignores `openReady`), and the new view model computes its divider
at its first load as today.

### 7.2 API facts checked for this plan

Compose BOM `2026.09.00` (`android/gradle/libs.versions.toml:4`) resolves foundation `1.12.1`. Present there
(`javap` on `foundation.aar`): `LazyListState.requestScrollToItem(index, scrollOffset)`, `scrollToItem`,
`animateScrollToItem`, `isScrollInProgress`, `firstVisibleItemIndex`, `firstVisibleItemScrollOffset`, `layoutInfo`;
`LazyListLayoutInfo.viewportSize`, `beforeContentPadding`, `afterContentPadding`, `visibleItemsInfo`,
`totalItemsCount`; `LazyListItemInfo.index/key/offset/size`. Navigation 3 is `1.2.1`. `lifecycle-runtime-testing`
is not a dependency: tests build their own owner with `LifecycleRegistry`.

Not checked in a running list, so step 1 or the screen tests must prove them: the exact offset semantics of a
reversed list, a negative `scrollOffset` in `requestScrollToItem`, and that a `SideEffect` request is applied by the
measure of the same frame.

### 7.3 Seen while reading, not part of this plan

- `WaThreadPage.kt:271` and `:273` (`route.attach`, `route.share`) also run again when the page comes back.
- An SMS thread marks read only when its view model is created; step 7.2 fixes the reopen case, not messages that
  arrive while the thread is open.
- A tap on a quoted reply does not scroll to the quoted message (`BubbleQuote.targetKey` is unused). When it is
  built it must go through `ThreadScrollState.jumpTo`.

## 8. Test plan

### 8.1 Model: `ThreadScrollTest` (JVM, no Robolectric)

Rows are written newest first; `i` incoming, `o` outgoing, `p` outgoing pending, `U` unread divider, `L` label.
Slop 100 px. Every case starts `Live`, `Following`, rows `[i3 i2 i1]`, unless it says otherwise.

| # | Sequence | Expected |
|---|---|---|
| 1 | Items `[i4 i3 i2 i1]`, resumed, settled | `ToNewest(animated = true)`, Following, unseen 0 |
| 2 | Items with 4 new rows | `ToNewest(false)` |
| 3 | Items `[i4 ...]`, not resumed | `ToNewest(false)` |
| 4 | Returned(now), then Items `[i4 ...]` 100 ms later, resumed | `ToNewest(false)` (return settle) |
| 5 | Moved(scrolling, spot (3, 0)), then Items `[i4 ...]` | Anchored, no command, unseen 1 |
| 6 | as 5, then Items `[o5 i4 ...]` (my message from another device) | no command, unseen 1 |
| 7 | as 5, then Items `[p5 i4 ...]` | `ToNewest(true)`, Following |
| 8 | Anchored; Items where a bubble changes kind `p` to `o` and nothing is added | no command |
| 9 | Anchored; Items with rows appended at the far end | no command, anchor unchanged |
| 10 | Anchored on `i2`; Items without `i2`; Moved(rest, spot on `i1`) | no command; anchor is `i1` |
| 11 | Anchored on `i2` (index 1); Items `[i2 i1]` (newest deleted); Moved(rest, (0, 0)) | Following |
| 12 | Following; Moved(scrolling, (0, 60)) | Following (inside the slop) |
| 13 | Following; Moved(scrolling, (0, 160)) | Anchored (tall newest message) |
| 14 | Following; Moved(rest, (12, 0)) with no pending follow | Anchored (instant scroll by someone else) |
| 15 | Following, held; Items `[i4 ...]`; Moved(rest, (1, 0)); Released | pending; still Following; then `ToNewest(false)` |
| 16 | Following; Moved(scrolling, (0, 10), held); Items `[i4 ...]`; Moved(rest, (1, 10)) not held | pending, then `ToNewest(false)` |
| 17 | as 16 but the scroll ends at (4, 0) | Anchored, pending cleared, unseen 1 |
| 18 | Items empty, then `[i3 i2 i1]`; Following | `ToNewest(false)` |
| 19 | Anchored on `i2`; Items empty; Items with `i2` | `ToKey(i2, offset)` |
| 20 | Opening; Items empty; Items `[i3 i2 i1]`, ready | Live, Following, no command |
| 21 | Opening; Items `[i5 i4 U i3 ...]`, ready | `ToUnread(U)`; then Moved(rest, (0, 0)) gives Following; Moved(rest, (2, 0)) gives Anchored, unseen = incoming below |
| 22 | Opening; rows with `U`, `openReady = false` | unchanged, no command; ready later gives 21 |
| 23 | Opening; `openAtTarget`, rows with `U` | Following, no command |
| 24 | Restoring, Anchored on `i2`, left 5 min ago; Items with `i2` | `ToKey(i2, offset)`, Live |
| 25 | Restoring, Anchored, left 15 min ago | `ToNewest(false)`, Following |
| 26 | Restoring, Anchored on a key that is not in the rows | `ToNewest(false)`, Following |
| 27 | Restoring, Following | `ToNewest(false)` |
| 28 | Anchored; Left(t); Returned(t + 14 min 59 s) | no command, Anchored |
| 29 | Anchored; Left(t); Returned(t + 15 min) and Returned(t - 1) (clock back) | `ToNewest(false)`, Following |
| 30 | Following; Left; Returned after 3 hours | no command (the Items event follows) |
| 31 | Left twice | the first time is kept |
| 32 | Anchored; OpenedAgain | `ToNewest(false)`, Following |
| 33 | Anchored; JumpToNewest; Moved(rest, (0, 0)) | `ToNewest(true)`; then Following, unseen 0 |
| 34 | Anchored with unseen 3; Moved(scrolling) to a newer row twice, then back up | unseen 2, 1, then still 1 (seen only moves to newer) |
| 35 | unseen counts `i` only, never `o`, `p`, `U`, `L`; `returnsToNewest` cases of `ThreadReturnTest` | |
| 36 | Property: random event sequences never break I1 to I3 | |

### 8.2 Screen: `ThreadScrollScreenTest` (Robolectric, `@Config(sdk = [35])`, `ShotTest`)

Real `ConversationScreen`, a `mutableStateOf<ConversationUi>`, a fake clock through `now`, a hand-made lifecycle
owner provided with `LocalLifecycleOwner`. "At the newest" = first visible key is the newest key and offset 0.

1. Pinned, a row is added: the new bubble is displayed, the list is at the newest; again with 5 rows at once.
2. Scrolled up (`scrollBy`), rows are added: the first visible key and offset are unchanged; the button shows with
   the badge of the incoming ones.
3. Scrolled up, a pending outgoing row is added: at the newest, button gone.
4. Scrolled up, 50 rows appended at the far end (paging): first visible key and offset unchanged.
5. Pinned; lifecycle to CREATED; 3 rows added; back to RESUMED: at the newest, the new bubble displayed.
6. Scrolled up; away 5 minutes: place kept. Away 15 minutes: at the newest.
7. Pinned; the list area gets shorter (a `Box` with a changing height around the screen, standing in for the
   keyboard and a growing composer), then taller: at the newest both times and the newest bubble is displayed.
8. A row above the anchor changes height (media aspect), a row is edited, a reaction is added: first visible key and
   offset unchanged; the same while pinned keeps the newest.
9. `tools.scrollTo` to an old bubble, then a row is added: the target is still the first visible row (I4).
10. `StateRestorationTester`: scrolled up, rows added while "away", restore: the anchor row is first visible again
    with its offset; pinned restores at the newest; a missing anchor key restores at the newest.
11. Opening with a divider and 40 unread rows: the divider is displayed in the top part, the button shows the count
    of unread bubbles below; scrolling to the end clears it.
12. Opening with a divider and 2 unread rows: at the newest, no button.
13. Hold: select mode on, a row is added: not moved; select mode off: at the newest.
14. Newest rows removed while scrolled up one row: the button hides.
15. `ConversationRecompositionTest` unchanged and green (no bubble recomposes for scroll state).

View model tests (step 7.3): the divider does not move when a message arrives; `opened()` on a kept model gives a
new divider from the unread count of that moment (`WaThreadViewModelTest`, an SMS equivalent). `WaThreadRekeyTest`:
add an assertion that bubble keys are the same before and after a rekey.

### 8.3 Screenshots

No new visual design. Keep `e6.6-unread-divider` and `e6.6-scroll-to-bottom-count-*` as they are. One new pair
`thread-open-at-unread-light/dark` (test 11's state) to review the divider's place under the header. On a device,
check by hand: the slide-in of one new message while pinned, and the keyboard opening on a pinned thread (HyperOS,
because of `imeOverlapPadding`).

## 9. Risks

| Risk | Mitigation |
|---|---|
| The `SideEffect` request lands a frame late on some path and the new row flashes below the edge | Screen test 1 asserts the frame after the change; fallback: run the request from `remember(state.items)` in composition |
| A reopened thread with cached rows shows one frame at the bottom before `ToUnread` (viewport not measured yet) | It happens inside the push transition. If visible on a device: draw the list with alpha 0 until the request ran |
| The newest row shrinks while the reader is pinned with a small offset; the list then rests one row up and un-pins (rule Moved 2) | Rare (a delete-for-everyone of the newest message); the button shows and one tap fixes it |
| A scheduled WhatsApp message goes out while the reader is scrolled up: it enters as a pending outgoing row and pulls the thread down | See question 3 |
| After process death an anchor older than the first page is lost | By design (item 22); a long stay is usually stale anyway |
| The jump button now shows after about one row of scrolling instead of three | Wanted (I3): hidden means following |

## 10. Open questions for the owner

1. **Scrolled up and away for a long time.** Recommended default: keep today's 15 minutes (back at the newest),
   pinned needs no timer. Alternative: never move a reader who scrolled up, until the thread is closed.
2. **Open at the first unread message.** Recommended default: yes, as in section 4 (bottom when the unread ones fit,
   else the divider near the top with the count on the button). Alternative: always open at the bottom; then step 7
   shrinks to the divider fix.
3. **A scheduled message that fires while I read older messages.** Recommended default: accept the jump to the
   bottom (it is my own message going out). Alternative: mark scheduled sends so they do not move the list.
4. **Notification tap for the thread that is already on screen, while scrolled up.** Recommended default: go to the
   newest. Alternative: keep the place and only show the badge.

## Owner answers (2026-11-16)

1. Scrolled up and away 15 minutes or more: return to the newest (the default).
2. Unread: open with the first unread message at the top of the screen; when the unread run is shorter than the
   screen, that is the same as the bottom (the default, in the owner's words).
3. A scheduled WhatsApp message that fires while reading older messages does not move the list (the default).
4. A notification tap for the thread on screen while scrolled up goes to the newest (the default).

## Evidence (implementation, 2026-11-16)

### What was built

- `conversation/ThreadScroll.kt`: the model of section 2 (`reduce`, modes, phases, holds, time away, unread
  opening, seen key and badge); `returnsToNewest`, `THREAD_STALE_MS` and `NOT_LEFT` moved in. `ThreadReturn.kt` and
  `ThreadReturnTest.kt` are deleted; their three cases are in `ThreadScrollTest` (`t35_returnsToNewest`).
- `conversation/ThreadScrollState.kt`: the glue of 5.2 (`rememberThreadScroll`, saved with the page;
  `ThreadScrollEffects`: rows in a `SideEffect` before the measure, spot samples, holds, lifecycle away/back on
  `ON_STOP`/`ON_START` and dispose, the reopen flow; `ToUnread` with the 64 dp x font scale room).
- `ConversationScreen`: uses the glue; `JumpToLatest` reads only `jumpVisible`/`unseen` (my own messages no longer
  count); find/date/reveal go through `jumpTo`; `JUMP_AFTER_ITEMS` removed; new parameters `openSeq`, `openAtTarget`,
  `reopen`, `now` (all defaulted); new test tag `ConversationTags.JumpBadge`.
- `ConversationUi.opens`; `opened()` and the frozen divider in `SmsThreadViewModel` and `WaThreadViewModel`; pages pass
  `openSeq = rememberSaveable { model.opened() }`, `openAtTarget = route.reveal != null` and the reopen flow;
  WhatsApp reveal runs once per page (step 8).
- `ThreadOpens` (in `notify/OpenConversation.kt`), emitted by `MainActivity.routeFor(intent)` for a thread tag without
  a message id (step 9).
- `OverlayController.hasModal` (`android/design/.../overlay/OverlayHost.kt`). Checked: the lifted bubble menu
  (`IosContextMenuEntry`), header and attach menus (`ButtonMenuEntry`), sheets (`SheetEntry`, modal by default),
  alerts, popovers and the media viewer (`PhotoViewer`) are modal; toasts and the undo pill are not.
  `OverlayMenuEntry` (modal = false) is not used by Messages.
- Docs: `docs/kotlin-ui/thread-scroll.md`.

### Probe (step 1), `ThreadScrollProbeTest` (bare reversed `LazyColumn`, foundation 1.12.1)

| Assumption | Result |
|---|---|
| At rest the spot is (0, 0); `scrollBy(+px)` grows the offset, then the index | confirmed |
| A row added at index 0 while at (0, 0) leaves the old row first visible (the bug) | confirmed (every layout pass that saw the new row had `r0` first) |
| `requestScrollToItem(i, -x)`: row `i` ends `x` px above the bottom content edge (`LazyListItemInfo.offset == x`); near the newest end the list clamps to (0, 0) | confirmed |
| A request made in a `SideEffect` is applied by the measure of the same frame | confirmed: every layout pass that saw the new row had it first (recorded in the items' placement blocks; Robolectric does not draw, and one `advanceTimeByFrame` alone runs no layout) |

No assumption was refuted; the design stands as planned.

### Deviations

1. Model: a non-saved `pendingKey` (the newest row when a follow became pending). While a finger is down, the keyed
   list moves the old newest to index 1 when a row arrives; without it the next scrolling sample would anchor and the
   pending follow of case 16 would be lost. A spot on that row within the slop still counts as at the newest.
2. Model: `Moved` with empty rows is ignored (else case 19, rows gone and back, would lose the anchor); opening
   pinned sets the seen key to the newest row.
3. Glue: animated commands run in a `Job` launched undispatched on the page scope (the previous one cancelled), not
   a conflated channel: the "programmatic" flag is up before the frame that would sample the list half way (that
   sample would un-pin). A generation counter makes only the last scroll send the closing `Moved`.
4. Saver: `leftAtMs` is saved as `now()` when the page is still marked visible. Saving only happens when the page goes
   away (pushed over, the activity stopped), so this does not depend on the order of dispose callbacks.
5. WhatsApp `opened()` sets `unreadAtOpen = null` (not `chat?.unread`): the reload it asks for reads the chat again,
   so the count is never from a stale cached chat. SMS: a thread not created yet (`thread <= 0`) is ready at once.
6. Owner answer 3 needed no code: WhatsApp scheduled texts are sent by the gateway (`wa.schedule_text`), not through
   the local outbox, so they arrive as sent rows (`RowKind.Outgoing`), never as a new pending bubble. Screen test
   `t03b`. (An SMS provider row in the Outbox/Queued box maps to `Sending` and would pull a scrolled-up list; that is
   outside the owner's question and unchanged.)
7. Screen test 10: a missing anchor at restore cannot be staged on a live page (it re-anchors before it is saved);
   covered by model test 26. The probes live in their own class `ThreadScrollProbeTest`.

### Tests and shots

- `ThreadScrollTest` (JVM): 37 cases, table 8.1 rows 1 to 36 (35 split into two), 36 = 2 000 random sequences of 40
  events asserting I1 to I3.
- `ThreadScrollScreenTest` (Robolectric): 8.2 numbers 1 to 14, plus `t03b` (a sent message from elsewhere keeps the
  place) and `t15` (the reopen signal goes to the newest), and the shot `threadOpenAtUnread`.
- `ThreadScrollProbeTest`: 4. `SmsThreadDividerTest`: 1 (frozen divider, new opening). `WaThreadViewModelTest`: +1
  (same). `WaThreadRekeyTest`: bubble keys asserted before and after the merge.
- New shots: `android/messages/screenshots/thread-open-at-unread-{light,dark}.png` (reviewed: divider just under the
  header with a sliver of the last read message above it, first unread right below, jump button with badge above the
  composer, nothing clipped or wrapped). Existing goldens unchanged, including `e6.6-unread-divider-*` and
  `e6.6-scroll-to-bottom-count-*`.
- The 48 dp slop and the 64 dp unread room are `MessagesMetrics.AtNewestSlop` / `UnreadRoom` (the design
  `ConventionTest` forbids raw dp in app code).

### Checks (release only, `plain` flavor)

`spotlessCheck :design:testReleaseUnitTest :messages:testPlainReleaseUnitTest :messages:testFirebaseReleaseUnitTest
:messages:lintPlainRelease :messages:verifyRoborazziPlainRelease :phone:compilePlainReleaseKotlin
:contacts:compileReleaseKotlin`: green. `:design` 372 tests, `:messages` plain 606 and firebase 606, 0 failures. The
first full run lost its Gradle daemon (low memory) and timed out `E68ShotTest.typingANumber` (New Conversation, not
touched); the rerun of the messages tasks passed it.
