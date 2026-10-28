# Spike S4: multi-call and conference on this carrier (story 15.1)

Status: instrumented, **waiting for the owner's phone run**. Until the table below is filled, the
shipped defaults apply. Nothing in the code needs changing if a row passes; a failing row is
handled by the fallback in the right-hand column of the last table.

## What is measured

Diagnostics (hidden page: long-press the Setup title, or Settings title once 16.1 lands) ->
group **Calls**. It reads the live snapshot once and prints, per call, the `caps` and `props`
masks (bit values in `docs/phone-wire.md` section 5), the child count, whether the call has a
parent and whether Telecom lists it as conferenceable, then the flags `can_add`, `can_merge`,
`can_swap`, `pending`, `failed`, then four pass/fail/unknown lines (the S4 criteria). The
registry measures the timings itself while you use the app (`CallRegistry.multiCallFacts`):

| Criterion | How it is measured |
| --- | --- |
| Swap changes the Active call within 1 s without both held | time from Swap to the held call becoming Active; "both held" is seen if no call is Active at that moment |
| Merge yields a top-level call with `props.conference` within 2 s | time from Merge to the conference parent reporting more children than before |
| Children and their split/disconnect bits are reported | the conference parent's `children[]` is not empty and the children carry bits 32 and 64 (counts are printed) |
| Hold-and-answer holds the first call | after "Hold and answer" the first call moves to Holding (pass) or Disconnected (fail) |

The call event ring (no numbers, no names) is appended to "Copy report". Logcat tag `RovstiSpike`.

## Owner procedure

Needs two real calls and a second phone for the third caller. Do it once on each SIM that
supports three-way calling.

1. Call A (any number you can ring). Wait until it is answered.
2. Tap **Add call**, choose Favorites or the Keypad, call B. A goes on hold (the Held card shows).
3. Tap **Swap** twice (or the Held card). Each tap should change who is on the line within a second.
4. Tap **Merge**. The conference view appears within about two seconds.
5. In the participant list, **End** one participant (and, if the icon is there, **Split** one).
6. Run Diagnostics -> Calls and **Copy report**.
7. Start again with one call (A). Have a third phone call you: the Waiting card drops. Try each
   choice in turn on separate runs: **Hold and answer**, **End and answer**, **Decline**.
   Run Diagnostics -> Calls after "Hold and answer" and copy the report.

## Shipped defaults (used until a row says otherwise)

- Merge is dimmed with the reason "Your carrier doesn't allow merging calls" when neither the
  foreground call has the merge capability nor Telecom lists a conferenceable call.
- Conference rows are read-only (no Split, no End icons) unless the child reports bit 32 or 64.
- Swap is `swapConference()` when the foreground reports bit 8, otherwise `unhold()` on the held
  call, then `hold()` on the former active call once if it is still Active after 1 s.
- **Hold and answer** is dimmed when the current call has no hold capability. When an Active, a
  Held and a Ringing call exist, the choice is relabelled **End held call and answer** (some
  carriers disconnect the held call when a new one is answered; the owner learns whether this
  one does from the fourth probe line).

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| Swap changes the Active call within 1 s, never both held | PENDING | | |
| Merge yields `props.conference` within 2 s | PENDING | | |
| Children reported, split bit (32) / disconnect bit (64) | PENDING | | |
| Hold-and-answer holds the first call | PENDING | | |
| Hold-and-answer with an existing held call disconnects it? | PENDING | | |
| Carrier / SIM / HyperOS version | PENDING | | |
