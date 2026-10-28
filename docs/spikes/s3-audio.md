# Spike S3: audio endpoints, names, ids, hearing aid

Question (FR-41, UX-Q16, UX-Q19, AD-23): does `CallEndpoint` give every
Bluetooth and wired device a non-empty name even with `BLUETOOTH_CONNECT`
denied, does choosing one move `selected` within 500 ms, and do ids stay equal
while a device stays connected?

## Pass criterion

During a call, each Bluetooth and wired device appears with a non-empty name
with `BLUETOOTH_CONNECT` denied; a route request moves `selected` within
500 ms; ids stay equal while the device stays connected.

## Shipped default (what the app does while this is PENDING)

- Legacy type-only list when Telecom never sends endpoints within 1.5 s
  (`AudioPath`, synthetic ids `legacy:*`, `setAudioRoute`).
- With endpoints: nameless Bluetooth devices read "Bluetooth device 1, 2, ..."
  and a "Show device names" row asks for `BLUETOOTH_CONNECT` (story 14.7).
- No hearing-aid hint unless Kotlin finds a `TYPE_HEARING_AID` communication
  device whose product name equals the endpoint name, or the name has the
  whole word "hearing" or "aid" (best effort).
- `BLUETOOTH_CONNECT` is declared but never requested at startup.

## Owner procedure (release APK)

1. Start a call (any number). Open Setup, long-press its title for 1 second:
   Diagnostics opens.
2. Tap **Audio**. For 20 seconds the page collects one line per second (type,
   name length, id tail, selected). During those 20 s: connect earbuds, then
   disconnect them; plug a wired headset in and out; connect the hearing aid if
   you have one.
3. After the 20 s the page adds three verdict lines:
   `names present with BLUETOOTH_CONNECT denied`, `id stable while connected`,
   `selected moved within 500 ms after a route request`. The last one needs a
   route request first: open the route popover (long-press the Audio control or
   tap it while a device is connected), tap each device once, then run the probe
   again.
4. To test the "denied" case, open App info -> Permissions and make sure
   Nearby devices is **not** allowed before step 2.
5. Tap **Copy report** and paste it into the chat.

## Results

| Date | Device | Names with permission denied | Ids stable | Selected within 500 ms | Hearing hint | Decision |
|---|---|---|---|---|---|---|
| PENDING | Xiaomi (HyperOS) | PENDING | PENDING | PENDING | PENDING | keep shipped default |

If the names are present without the grant, hide the "Show device names" row
(it already disappears at runtime when no name is empty). If ids change while a
device stays connected, route by type plus name and re-check.
