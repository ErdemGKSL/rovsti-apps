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

## Device test script (stories 14.6 to 14.8, UJ-7)

1. Start a call (a number that answers). With nothing connected the Audio
   control reads "Phone"; a tap toggles Speaker and back (pending look 120 ms).
2. Connect the earbuds. The 6 px accent dot appears at the control's icon for
   3 s; the selected route does not change by itself.
3. Tap the Audio control: the popover opens above it (Bluetooth device on top,
   Speaker, Phone with a check). Close with Back, open again 20 times, also
   with a long press (450 ms) which works with only Phone and Speaker too.
4. Tap the earbuds: the popover closes, a check flies to the control, the icon
   and caption cross-fade within 500 ms and the earbuds play the call.
5. Repeat for the wired headset and the hearing aid (best effort, ear icon and
   "Hearing aid" subtitle). Switch a device off during the call: its row dims
   with "Disconnected" and the check moves to what Android falls back to.
6. Connect and disconnect Bluetooth five times in a row: the control follows
   with no flicker (logcat `RovstiSpike` prints one `snapshot` line per change).
7. Provoke a failure (switch the earbuds off right after tapping their row):
   the pending look reverts after 1 s with the pill "Couldn't switch to ...".
8. Tap "Bluetooth settings" and "Hearing aids" in the popover footer: the popover
   closes, the system page opens, coming back shows the same call screen. A
   phone without the hearing page opens App info and says "Opened app settings
   instead".
9. Burst check (14.0e): connect and disconnect the earbuds quickly; at most two
   `snapshot` lines appear per burst in `adb logcat -s RovstiSpike`. Note the
   count in the result table above.
10. Run the Diagnostics Audio probe (see the procedure) and paste the report.
