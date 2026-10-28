# Spike S8: silence the ringer (story 18.4)

Status: instrumented, **waiting for the owner's phone run**. Shipped defaults: the Silence control is
shown; if `TelecomManager.silenceRinger()` throws once, the control becomes a blank reserved slot for
the session. Flip to silence is P3 and only runs while a call rings, is not yet silenced and the
Settings toggle is on.

## What is measured

- `silenceRinger()` from the Silence control: does the ringtone stop while the call stays answerable?
  (needs the default phone role or `MODIFY_PHONE_STATE`)
- A volume key: `InCallService.onSilenceRinger()` fires (`silenced_by=volume`).
- Flip face down (gravity sensor, z below -7.0 m/s2 twice, after a sample at or above -3.0): once with
  the screen on and once locked.

## Owner procedure

1. Settings > Flip to silence on.
2. Diagnostics > Silence > Run (it watches 75 s, up to three rings).
3. Call this phone three times from the second phone. Ring 1: tap Silence. Ring 2: press a volume key.
   Ring 3: turn the phone face down (do it again later with the screen locked).
4. Note for each whether the ringtone stopped and whether the call could still be answered.
5. Copy report and fill in the table.

## Pass criterion

Each way reports the expected `silenced_by` and the ringtone stops.

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| silenceRinger() from the app (user) | PENDING | | |
| Volume key (onSilenceRinger fires) | PENDING | | |
| Flip, screen on | PENDING | | |
| Flip, screen locked | PENDING | | |
| Call still answerable after silencing | PENDING | | |
