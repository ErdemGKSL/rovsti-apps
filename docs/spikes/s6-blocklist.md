# Spike S6: HyperOS and the system blocklist (story 17.1)

Status: instrumented, **waiting for the owner's phone run**. Until the table is filled the shipped
default applies: blocking goes through `BlockedNumberContract` and `Caps.BLOCK_GUARD = true`.

## What is measured

Rovsti Phone is the default phone app, so Android lets it use `BlockedNumberContract`
(`canCurrentUserBlockNumbers`). The open questions on HyperOS are whether a number in that list
really never rings, whether a type-6 row appears in the call log, and whether Xiaomi's own block
list (Phone app > Blocked) is the same list.

`Caps.BLOCK_GUARD` is the safety net: when a call starts ringing, the registry asks
`BlockedNumberContract.isBlocked(number)` on its own lookup thread (never on the main Looper and
never before the notification is posted). On a hit it rejects the call and takes the notification
down. A slow provider therefore cannot delay a normal ring (`Epic17Test`).

## Owner procedure

1. Make sure Rovsti Phone is the default phone app.
2. Settings (gear) > Blocked numbers: type the number of the second phone and tap Block.
3. Diagnostics (hold the Settings title) > Blocking > Run. Expect `pass: isBlocked on the newest entry`.
4. Call this phone from the second phone. Note: did it ring (yes/no)?
5. Run Blocking again. Expect `pass: a type-6 call-log row appeared` with reason `user`, and
   `guard_hits` (in the info line) tells whether the safety net had to act.
6. Open the system Phone app > Blocked numbers (Xiaomi's own list). Is the number there?
7. Settings > Blocked numbers > Unblock. Call again: it must ring.
8. Copy report and paste it in the table below.

## Pass criterion

A call from the second phone does not ring and a type-6 row appears.

## Shipped default

`Caps.BLOCK_GUARD = true`; the Blocked numbers page carries the footnote "Xiaomi's own block list may
be separate" until a run proves the lists are shared (22.6 removes it then).

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| isBlocked true right after Block | PENDING | | |
| Call from the second phone did not ring | PENDING | | |
| Type-6 row appeared, reason | PENDING | | |
| Guard hits | PENDING | | |
| Xiaomi's own list shows the number | PENDING | | |
