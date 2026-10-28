# Spike S5: SIM accounts and the call-log pair (story 16.3)

Status: instrumented, **waiting for the owner's phone run** (needs two SIMs). Until the table is
filled, the shipped defaults apply and nothing visible changes on a single-SIM phone.

## What is measured

Diagnostics (hidden page: long-press the Settings title) -> group **SIM**. It prints one `info`
line per call-capable account (slot, SIM-backed, enabled, label, whether it is the system default,
the slot colour, the key length) and then compares two keys:

- the account key of the last placed call (`"{ComponentName.flattenToString()}#{handle.id}"`), and
- the key built from the newest outgoing call-log row (`PHONE_ACCOUNT_COMPONENT_NAME` and
  `PHONE_ACCOUNT_ID`).

With one SIM the check says `unknown ... n/a` and the SIM UI stays hidden.

## Owner procedure

1. Phone settings: Calls -> default SIM for calls = "Ask every time" (or the phone's equivalent).
2. In Rovsti Phone type a number you can ring and tap Call: the "Call with" sheet rises. Pick SIM 1,
   let the call connect, end it. Check the SIM chip appears on the In-Call screen.
3. Repeat and pick SIM 2.
4. Open Diagnostics -> SIM -> Run, then Copy report.
5. Open Recents: both calls show their SIM at the trailing end of the subtitle line.
6. Choose a default SIM in the phone settings, then long-press Call for about half a second: the
   chooser opens with the default row captioned.

## Shipped defaults

- The SIM section, the chooser and every chip are hidden below two SIM-backed accounts.
- A call-log row whose pair matches no account shows no chip (the slot stays empty, nothing shifts).
- Another app's account picker (`STATE_SELECT_PHONE_ACCOUNT`) is answered as in v0.1 when the phone
  is locked, Rust has no live session, or nobody answers within 10 s; otherwise the chooser opens.
- The app never changes the system default account, and an emergency number never gets an account.

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| Two accounts listed with slots and colours | PENDING | | |
| Placed key equals the call-log pair | PENDING | | |
| Chip colour readable on both SIMs | PENDING | | |
| Default SIM captioned in the chooser | PENDING | | |
