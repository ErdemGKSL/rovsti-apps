# Thread scroll (Messages, SMS and WhatsApp threads)

Where a conversation's list stays. Model: `android/messages/.../conversation/ThreadScroll.kt` (pure, JVM tests in
`ThreadScrollTest`); glue: `ThreadScrollState.kt`; plan and evidence: `_bmad-output/kotlin-ui/plans/thread-scroll.md`.

The reader is either **pinned** (at the newest message: the newest row on screen with at most 48 dp of it hidden) or
**scrolled up** (the first visible row and its offset are kept). The jump button shows exactly while scrolled up; its
badge counts incoming messages that arrived below the screen (never my own).

| Event | Pinned | Scrolled up |
|---|---|---|
| Incoming message, page in front | Shown; 1 to 3 new rows slide in, more appear at once | Place kept, badge +1 |
| Messages while in the background | Back at the newest, no animation | Place kept; after 15 minutes away (or the clock went back): the newest |
| I send (composer, voice note, attachment, forward, share-in) | Slides to the newest | Goes to the newest and pins (a new waiting or sending bubble of mine) |
| My message from another device, a scheduled WhatsApp text the gateway sends | Shown | Place kept (it is not a new pending bubble) |
| Edit, delete, reaction, ticks, star, media size, older pages | No move | No move |
| Keyboard, composer height, rotation, font scale | The newest stays above the composer | The bottom row on screen stays |
| Finger on the list, select mode, a modal overlay (menu, sheet, viewer, alert) | New messages wait; shown when the hold ends | Nothing to do |
| Page pushed on top, then back; process death | Newest | Same row and offset if it is still loaded and not 15 minutes away, else the newest |
| Thread closed and opened again | Opening rule (below), never the old place | |
| Notification tap for the thread that is up | Newest | Newest and pinned |
| Find, jump to date, a search hit, a reminder | Animated to the target, then scrolled up there | Same |

**Opening.** With unread messages the unread divider is placed 64 dp (times the font scale) below the header, so the
first unread message is at the top of the screen and a sliver of the last read one shows above the divider. When the
unread run is shorter than the screen the list cannot scroll that far and the thread simply opens at the bottom,
pinned. A message target (search hit, reminder) wins over the divider. The divider is frozen on its bubble for the
whole visit; a thread opened again starts a new opening (`opened()` on the view model, `ConversationUi.opens`).
