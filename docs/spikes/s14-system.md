# Spike S14: system behaviours (vibration part, story 16.8)

Status: instrumented, **waiting for the owner's phone run**. The answer and decline gestures part of
S14 arrives with story 18.5.

## What is measured (vibration)

Diagnostics -> group **TalkBack/system** -> Run. It prints the vibrator facts, the two vibrate
settings the Kotlin mirror holds and whether TalkBack touch exploration is on, then counts down
5 seconds and vibrates once (`EFFECT_CLICK`). Hold the phone to your ear during the countdown so the
proximity sensor turns the screen off, and note whether you felt it.

## Owner procedure

1. Settings -> Calls: turn on "Vibrate when call connects" and "Vibrate when call ends".
2. Place a call, put the phone against your ear. The connect vibration should come when the call is
   answered; hang up from the other side: the end vibration should come.
3. Diagnostics -> TalkBack/system -> Run with the phone at your ear and the screen off.

## Shipped default

Both vibrate options stay off. The Settings footnote reads "May not work with the screen off". If a
row below fails, the options stay off by default and the footnote stays.

## Result (fill in from the phone)

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| Connect vibration felt with the screen off | PENDING | | |
| End vibration felt with the screen off | PENDING | | |
| Vibrate test (5 s countdown) felt at the ear | PENDING | | |

## TalkBack and gestures (story 18.5)

The same probe now also prints `talkback_touch_exploration`, `dtmf_tone_when_dialing` and an
`unknown` line for announcements and custom actions, which only the owner can judge.

### Owner procedure (TalkBack)

1. Turn TalkBack on. Run Diagnostics -> TalkBack/system and Copy report.
2. Walk these flows with TalkBack: the route list (14), a conference (15), decline with a reply
   (18: the focus order must be Answer, Decline, Message, Silence; rows read "{text}. Double tap to
   decline and send"), and reorder the quick replies (Move up / Move down actions).
3. Note which announcements were spoken.

### Shipped defaults

The answer and decline gestures keep their buttons (TalkBack users never need a gesture) and the
hold-to-delete on the keypad is a button when TalkBack is on.

| Row | Result | Value | Note |
| --- | --- | --- | --- |
| Focus order Answer, Decline, Message, Silence | PENDING | | |
| Reply row reads "Double tap to decline and send" | PENDING | | |
| Move up / Move down actions on a quick reply | PENDING | | |
| Block / Undo pill announced assertively | PENDING | | |
