# Spike S7: decline with a text through Telecom (story 18.1)

Status: instrumented, **waiting for the owner's phone run**. The app never holds `SEND_SMS`
(`scripts/check-apk.sh` asserts it). Shipped default: `Caps.REJECT_PATH = "HANDOFF"`.

## Paths

- `TELECOM` (only after this spike passes): `Call.reject(true, text)`. Telecom hands the text to the
  system respond-via-message service; the text goes out on the SIM the call came in on.
- `HANDOFF` (shipped): the call is rejected with `reject(false, null)` and the system SMS app opens on
  `smsto:{number}` with `sms_body` filled in. The owner sends it. The card says "Opened Messages",
  never "Sent".
- Per call, a missing `CAPABILITY_RESPOND_VIA_TEXT` or an unusable number makes `TELECOM` fall back
  to `HANDOFF`, or to a plain decline for a caller without a number ("Declined").

The result code (0 stale, 1 sent, 2 opened, 3 rejected only) drives the label on the card.

## Owner procedure

1. Diagnostics (hold the Settings title) > Reject text > Run. It waits 40 s.
2. Call this phone from the second phone. The probe declines the call with the text "test" through
   the Telecom path once (the override is scoped to the probe, `Caps.REJECT_PATH` is untouched).
3. Did the SMS "test" arrive on the second phone within 30 s? Which SIM sent it? Does your plan
   charge for it?
4. Copy report and fill in the table.

## Pass criterion

`result_code=1` and the SMS arrives within 30 s. Then 22.6 flips `Caps.REJECT_PATH` to `TELECOM`.

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| respond_via_text capability on the ringing call | PENDING | | |
| reject(true, "test") accepted | PENDING | | |
| SMS arrived within 30 s | PENDING | | |
| SIM that sent it, cost | PENDING | | |
