# WhatsApp call audio: every hop (E5.8a)

Status: traced and tested in software (round 13, step 6). The check on a real phone is an owner item (see the end).

One format runs the whole way: **16 kHz, mono, signed 16-bit little-endian PCM** (`crates/rovsti_wa_proto/src/audio.rs`).
The microphone goes in frames of **960 samples = 1 920 bytes = 60 ms** (`FRAME_SAMPLES`, `FRAME_BYTES`; MLOW encodes
60 ms at a time). The speaker comes in frames of **320 samples = 640 bytes = 20 ms** (`STEP_SAMPLES`), as WhatsApp's
decoder plays them out, when the app asked for them; an app or a gateway from before that still speaks whole 60 ms
frames, see "Latency" at the end.

## Microphone: phone to WhatsApp

| # | Hop | Format | Code |
|---|-----|--------|------|
| 1 | `AudioRecord`, source `VOICE_COMMUNICATION` (platform echo cancel and noise suppression), `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`, 16 kHz, buffer at least 4 frames | PCM16 mono, reads of any size | `android/platform/.../wa/WaCallAudio.kt` (`start`, `format`, `RECORD_SOURCE`, `bufferBytes`) |
| 2 | Thread `wa-mic` fills a `ByteArray(1920)` with whole reads, then pushes | 1 920 bytes | `WaCallAudio.pumpMic` |
| 3 | `CallFrames.push` -> `CallAudio.push` (never the main thread) -> `RustNative.audioPush(handle, pcm)` | 1 920 bytes, JNI `byte[]` | `android/core-bridge/.../ops/WaGateway.kt`, `RustNative.kt` |
| 4 | `Java_dev_rovsti_core_RustNative_audioPush` -> `ops::audio_push`: `decode_frame` (other sizes are dropped with a debug log), `mic.try_send` (a full queue drops its oldest frames, no added latency) | 960 `i16` | `crates/rovsti_ffi/src/jni_exports.rs`, `ops/wa/live.rs` |
| 5 | `rovsti_wa_client::audio::call_audio`: `pump` encodes with `encode_frame` and sends one **binary WebSocket message** per frame to `wss://<gateway>/v1/calls/<id>/audio` with `Authorization: Bearer <connect key>` | 1 920 bytes per message | `crates/rovsti_wa_client/src/audio.rs` |
| 6 | Gateway `audio_socket` -> `feed_mic`: `decode_frame` (wrong sizes dropped), `push` into the call's `mic` queue (`Lagging`: it was full and dropped its oldest frames) | 960 `i16` | `services/wa_gateway/src/wa/calls.rs` |
| 7 | `MediaPlan` (gateway): in the default mode `call_audio::run_uplink` encodes each frame with libopus (`WaOpusEncoder`, 24 kb/s, DTX) and hands the packet to the engine (`encoded_audio`), which wraps it in RTP/SRTP. See "Codec mode" below | 960 `i16` frames in, one Opus packet out | `calls.rs`, `call_audio.rs` |

## Speaker: WhatsApp to phone

| # | Hop | Format | Code |
|---|-----|--------|------|
| 1 | Default mode: the engine hands each decrypted packet to `call_audio::run_downlink`, which decodes it (libopus, or the MLOW decoder for an MLOW packet) into `spk_tx`. PCM mode: the engine's own decoder writes **20 ms, 320 sample** frames (any length is handled) | 960 samples per packet (default), 320 (PCM mode) | `calls.rs`, `call_audio.rs` |
| 2 | `to_speaker` puts the audio into the call's speaker queue in 20 ms slots (longer decoded frames are cut up). `shuttle` sends each slot as one message to an app that asked for `?spk_ms=20`; for an app that did not, `Rechunker` joins three into a whole frame | 640-byte binary messages (1 920 for an older app) | `call_audio.rs` (`to_speaker`, `FreshQueue`), `calls.rs` (`shuttle`) |
| 3 | Client `pump` decodes each message with `decode_speaker_frame` (640, 1 280 or 1 920 bytes; anything else is dropped) and pushes it to the speaker queue, which drops its oldest audio when the player is behind | 320 `i16` (960 from an older gateway) | `audio.rs`, `queue.rs` |
| 4 | `RustNative.audioPull(handle, out)` waits up to 20 ms for a frame: its length in bytes = a frame, 0 = none came, -1 = call ended or handle gone | 640 bytes (1 920 from an older gateway) | `ops::audio_pull`, `jni_exports.rs` |
| 5 | Thread `wa-speaker`: frame of any valid length -> `AudioTrack.write(WRITE_BLOCKING)` (the track paces the thread), unless `PlayoutTrim` drops it; 0 -> asks again; -1 -> thread ends | 640 or 1 920 bytes | `WaCallAudio.pumpSpeaker`, `PlayoutTrim` |
| 6 | `AudioTrack`: `USAGE_VOICE_COMMUNICATION`, `CONTENT_TYPE_SPEECH`, `CHANNEL_OUT_MONO`, `ENCODING_PCM_16BIT`, 16 kHz, `MODE_STREAM`, buffer two 60 ms frames (or the platform minimum), starts playing at 40 ms | PCM16 mono | `WaCallAudio.trackAttributes`, `format`, `speakerBufferBytes`, `startThresholdFrames` |

## Platform state while a call runs

- **Mode**: `AudioManager.MODE_IN_COMMUNICATION` is set in `start` and the previous mode restored in `stop`.
- **Audio focus** (added in this step): `AUDIOFOCUS_GAIN_TRANSIENT` with the voice-communication attributes, abandoned in
  `stop`. Before, music kept playing under a call.
- **Routes**: `WaCallAudio.Route` = Earpiece / Speaker / Bluetooth. API 31+ uses `setCommunicationDevice` over
  `availableCommunicationDevices` (earpiece `TYPE_BUILTIN_EARPIECE`, speaker `TYPE_BUILTIN_SPEAKER`, headset
  `TYPE_BLUETOOTH_SCO`, else `TYPE_BLE_HEADSET`). A paired headset is chosen at start (added in this step; before, only
  earpiece and speaker existed). Below API 31 the speaker uses `isSpeakerphoneOn`, and the system routes SCO itself.
  `stop` clears the communication device and turns the speakerphone off.
- **Microphone permission**: `WaCalls.place` and `accept` answer `NeedsMicrophone` without `RECORD_AUDIO` and send
  nothing to the gateway; `WaCallAudio.start` re-checks and returns false.
- **Foreground service** (added in this step): `dev.rovsti.platform.wa.WaCallService`, declared in
  `android/phone/src/main/AndroidManifest.xml` as `foregroundServiceType="microphone|phoneCall"` with
  `FOREGROUND_SERVICE_MICROPHONE`. Android 14 stops background capture without a microphone service. At run time it
  claims `microphone`, plus `phoneCall` only when the app holds the dialer role (the system refuses the type
  otherwise). `RovstiInCallService` keeps `phoneCall` for telecom calls. **Order (changed, see "Silent microphone"
  below):** `WaCalls` starts the service when the user answers or places the call (`CallAudioPump.prepare`, the app is
  in front), `AndroidCallAudioPump.start` starts it again (a no-op) **before** the recorder, and `stop` stops the
  recorder first and the service after. No R8 rule is needed: manifest components are kept by the aapt2 rules.
- **Opened only after connected** (changed in this step): `WaCalls` starts the pump when the gateway reports phase
  `Active`, for outgoing **and** incoming calls. Before, an incoming call opened the recorder and the audio socket
  at the moment the answer was sent, while the media was still connecting. Hanging up, rejecting, an `Ended` event
  and `close()` stop the pump first.
- **Rust.init**: `audioPush` and `audioPull` are only reachable through a `CallAudio` handle, and that handle comes from
  `wa.call_audio_open`, a call that waits for `Rust.init` (commit ce32f09). Nothing here calls `RustNative` before it.

## Mismatches found and fixed

1. Incoming calls opened the audio at accept, not when connected (now at `Active`).
2. No audio focus request (added).
3. No Bluetooth route and no headset preference (added).
4. No microphone foreground service for background capture on Android 14+ (added, with the permission).
5. No test covered the client WebSocket against a socket with exact frame sizes and real samples (added, below).

No mismatch in format: Kotlin, JNI, client, protocol and gateway all use 960 samples / 1 920 bytes / 16 kHz mono.

## Tests

- `crates/rovsti_wa_client/tests/call_audio.rs`: an in-process fake gateway WebSocket sends six frames of a 1 kHz tone
  and checks the six tone frames the client sends (frame size 1 920 bytes, samples unchanged, Goertzel energy at
  1 kHz and none at 2.3 kHz, path and bearer).
- `services/wa_gateway/tests/api.rs` `call_audio_rechunks_20_ms_engine_frames_into_60_ms_app_frames`: the engine hands
  the speaker 320-sample frames; the app receives whole 960-sample frames in order and no partial one. Also
  `call_audio_echoes_frames_and_drops_wrong_sizes` (real gateway, real client).
- `android/wa/.../WaCallAudioTest` (Robolectric): rate, format, usage, source, buffer, routes, mode and focus taken
  and given back, service types, and that the real pump records only after `Active` (incoming and outgoing).
- `android/wa/.../WaCallsTest`: the pump logic with a fake pump. `PhoneManifestTest`: the service and its type.

## No audio in either direction on a real phone (E5.8a follow-up)

Symptom: WhatsApp calls through the gateway rang, connected and hung up, but neither side heard
anything (also in the old app). Cellular calls were fine.

### What was wrong: the peer's audio (inbound)

The gateway used whatsapp-rust 0.7 with only `voip-mlow` and `.audio(mic, speaker)`, which is
`AudioConfig::MLOW_PCM`: the engine encodes and decodes Meta's MLOW codec itself. Real WhatsApp
peers in that RTP profile often send **standard Opus/CELT through MLOW's escape** (TOC with the top
two bits set). In PCM mode wacore does not decode those
(`wacore-0.7.0/src/voip/engine.rs`, `on_rtp`: `AudioIo::Pcm && codec == Opus` becomes
`CallEvent::ForeignAudio(bytes)` and returns), its MLOW decoder answers an Opus TOC with silence
(`mlow/decoder.rs`, `toc.std_opus`), and `WaLiveCall::next_event` ignored `ForeignAudio`. So every
such packet was dropped and the engine kept emitting silent 20 ms playout frames.

### The peer hearing nothing (outbound)

There was no log evidence (the engine logs voip details at debug only, the server runs at info).
The pure-Rust MLOW encoder is the library's own reimplementation; the library documents a
different path as the verified one: `agent_docs/voip_audio_codecs.md` in whatsapp-rust 0.7 says
"The PT120 native-Opus path was verified against Android/Web implementations and with a live
full-duplex Android call. MLOW remains the compatibility default." Native Opus clears MLOW
capability bit 31 (`CAPABILITY_STANDARD_OPUS_*` in `wacore/src/stanza/call.rs`, "selecting
WhatsApp's standard Opus fallback instead of MLOW") and, when answering, sends the
`use_mlow_codec_v1=false` settings, so both directions use Opus. That removes the unproven encoder
and the inbound escape problem at once.

### Codec mode (changed)

`call_audio::CALL_AUDIO_MODE` selects one of three modes (one constant, so the owner can switch
back without code changes elsewhere):

| Mode | whatsapp-rust | Microphone | Peer audio |
|------|---------------|------------|------------|
| `NativeOpus` | `encoded_audio(AudioFormat::OPUS_16KHZ_60MS, ..)` | `WaOpusEncoder::new()` (libopus, VoIP, 16 kHz, 60 ms, 24 kb/s, DTX) | `WaOpusDecoder::decode` |
| `OpusInMlow` (default 2026-11-11 to 2026-11-13) | `encoded_audio(AudioFormat::OPUS_MLOW_16KHZ_60MS, ..)` | `WaOpusEncoder::new_mlow_escape()` (CELT in MLOW's escape, SID for silence) | escape: `decode_mlow_escape`; MLOW: wacore `MlowDecoder` (PT 121 = redundancy) |
| `MlowPcm` (**default** since 2026-11-13, see the end) | `.audio(mic, speaker)` | engine's MLOW encoder | engine's MLOW decoder, plus `CallEvent::ForeignAudio` decoded with `decode_mlow_escape` |

In `MlowPcm` the engine keeps sending silent 20 ms playout frames while the peer talks Opus, so
`run_pcm_downlink` holds back all-zero engine frames for 500 ms after the last foreign packet
(`FOREIGN_HOLD`); otherwise the app would get twice the samples per second, half of them silence.

**Owner test, 2026-11-11.** With `NativeOpus` the caller never took the answer: their phone rang on, the call ended as
missed after about 45 s, and the peer heard nothing although audio flowed both ways at the gateway (the debug log showed
only the `offer`, never anything back). With `OpusInMlow` (commit 879b821) the call connected and audio worked in both
directions: a 70 s call, the peer sending MLOW (`spk: mlow=956`) and the microphone voiced (`mic: voiced=38`).
`CallHandle::events()` is one shared queue (each event reaches one receiver), so the foreign audio
is decoded in the existing event loop (`WaLiveCall::next_event`, driven by `CallTable::follow`),
not by a second reader. Decoder state is per call. In the encoded modes the gateway does mute
itself (it encodes silence), because `CallHandle::set_muted` only covers PCM audio.

The decoded PCM goes into the same `spk_tx` as before, so `Rechunker` and the app socket are
unchanged. libopus comes from `whatsapp-rust`'s `voip-libopus` feature (the `opus` 0.3 crate,
`audiopus_sys`): with no system libopus found by pkg-config it builds the bundled copy with
**cmake** and links it statically, so the build host needs cmake and a C compiler (the Raspberry
Pi has cmake 3.31; libopus-dev is not needed).

### Diagnostics (added)

`call_audio::CallAudioStats` counts every hop; `CallTable::follow` logs one info line every 10 s
while the call is active and one when it ends (`journalctl -u wa-gateway | grep 'call audio'`):

```
<instance>: call audio <call id> peer=90…67@s.whatsapp.net ended after 42s: format=native-opus pt120 16k/60ms | mic: app=650 bad=0 lag=0 engine=650 engine_drop=0 enc_fail=0 rms=-31.2dBFS peak=9120 voiced=410 | spk: engine=640 opus=640 mlow=0 dec_fail=0 foreign=0 foreign_ok=0 foreign_fail=0 gated=0 queue_drop=0 app=640 rms=-27.9dBFS peak=14011 voiced=380
```

- `mic: app` whole frames from the app socket, `bad` wrong-size messages, `lag` dropped on a full
  queue, `engine` frames (PCM) or packets (encoded) handed to the engine, `engine_drop` refused by
  a full engine queue, `enc_fail` encoder errors; `rms`/`peak`/`voiced` (frames peaking above about
  -40 dBFS) of the microphone PCM after mute.
- `spk: engine` playout frames (PCM) or packets (encoded) from the engine, `opus`/`mlow` the codec
  of each packet, `dec_fail` decoder errors, `foreign*` the `ForeignAudio` packets (PCM mode),
  `gated` silent engine frames held back, `queue_drop` frames lost to a full socket queue, `app`
  whole 60 ms frames sent to the app; the level of what the app got.
- The peer is masked (first two and last two digits); no full number or JID is logged.

Reading one test call: `mic app=0` means the phone never sent audio (socket or recorder);
`mic voiced=0` with frames means the phone sent silence (permission, route, mute); `engine` close
to `app` with sound means the gateway did its part outbound. `spk engine=0` means WhatsApp sent no
media; `dec_fail` or `foreign_fail` growing means a codec mismatch; `spk app` with `voiced=0`
means the peer's audio decoded to silence.

### Tests

- `services/wa_gateway/src/wa/call_audio.rs`: a 1 kHz tone encoded with
  `WaOpusEncoder::new_mlow_escape` goes through `ForeignAudio::deliver` into the speaker channel
  and comes out as 1 kHz (Goertzel, as in `crates/rovsti_wa_client/tests/call_audio.rs`); the same
  for native and escaped packets through `InboundDecoder::decode_packet`; an MLOW SID decodes to
  60 ms of silence; the uplink encodes the tone and it decodes back; mute sends MLOW SID; the PCM
  downlink gate; levels; the exact summary line; peer masking; garbage counted as failed.
- `services/wa_gateway/tests/api.rs` `call_audio_echoes_frames_and_drops_wrong_sizes` also checks
  the socket counters.

### Also fixed in this deploy: FCM refused every call push

The call pushes carried the caller under the data key `from`, which FCM reserves: every `call` and
`call_end` push failed with `FCM answered 400: Invalid data payload key: from`. The key is now
`sender` (`rovsti_wa_proto::push::SENDER_KEY`); the apps (`android/platform` `waCallerTitle`, the
old bridge's `WaIncoming.kt`, and `rovsti_wa_proto::push::parse_wake`) read `sender` and still
accept `from`.

## Owner item: check on a real phone

Software cannot hear. On a phone, place a WhatsApp call from the Phone app to another account and check: the other
side hears you, you hear them, earpiece / speaker / Bluetooth headset switch, music pauses and resumes, the call keeps
working with the screen off or the app in the background (the microphone notification shows), and no audio is
captured before the other side answers.

## Silent microphone, short speaker, ringing after answer (E5.8a follow-up 2)

Owner's call (HyperOS, Android 14+), gateway line: `mic: app=71 ... rms=-120.0dBFS peak=0 voiced=0 | spk: engine=83
opus=83 ... queue_drop=1 app=15 rms=-17.3dBFS`.

- **Microphone all zeros.** Android gives an app digital silence when it captures while counted as background. Three
  causes in the app: (1) a WhatsApp ring never showed over the lock screen: `MainActivity` turned
  `setShowWhenLocked`/`setTurnScreenOn` on only for `CallRegistry` (telecom) calls, so the full-screen intent's
  activity stayed behind the keyguard; (2) the microphone foreground service started **after** the recorder, at
  `Active`, possibly from the background, where Android 14 refuses a while-in-use type; (3) the service was started
  at all only after capture began. Fixed: `LockScreenGate` also follows `WaCalls.calls` and holds the flags from the
  ring's intent (`wa_call_id`) until the list has the call, the list says it ended, or `WaIncoming.RING_TIMEOUT_MS`
  passed (never longer without a call; `LockScreenGateTest`); the service starts at answer/place and before the
  recorder (`WaCallsTest`, `theRealPumpStartsTheServiceBeforeTheRecorderAndStopsItAfter`).
- **Zero-run warning.** `SilenceWatch` in `wa-mic` logs one `Log.w` (tag `Rovsti`) after 2 s of exact zeros, naming the
  likely causes: `adb logcat -s Rovsti | grep 'exact digital silence'`.
- **15 of 83 speaker frames.** Not explained by the counters (only 1 queue drop, no decode failure; the socket was open
  for about 4 s judging by `mic app=71`). The gateway line now has three more speaker counters to settle it on the next
  call: `empty` (decoded packets with no samples), `pcm_ms` (decoded audio that reached the socket queue, in ms) and
  `stale` (queued frames thrown away when the app's socket attached). `pcm_ms` close to `app x 60` and far below the
  call length means the peer's packets decode to less audio than 60 ms each; `stale` large means the socket attached
  late. On the phone, the speaker write is now blocking (above).
- **"Keeps ringing" after answer.** Rovsti has no ringtone, vibrator or sound for WhatsApp calls (the `wa_calls`
  channel has no sound since its first version), so the ring is the incoming-call notification and the Incoming
  screen: the notification was cancelled only by its own Answer/Decline buttons or a `call_end` push (sent only for a
  ring that ended unanswered), so answering in the app left the ringing `CallStyle` notification up for 60 s, and the
  screen stayed on Incoming until the gateway's `Active` event. Fixed: `WaCalls` calls `ringOver` (the container cancels
  the notification) on accept, reject and every incoming event that is not `Ringing`; accept moves the call to
  `Connecting` at once (a late `Ringing` event cannot undo it); `WaIncoming` remembers rings that are over so a late or
  repeated `call` push cannot post the notification again. (Separately, the official WhatsApp app on the primary phone
  is dismissed by the **caller** with `<terminate reason="accepted_elsewhere">`, per whatsapp-rust
  `handlers/call.rs::dismiss_outgoing_siblings`; a companion never dismisses its siblings, so the gateway has nothing
  to send.)
- **Screen on, unlocked: no call screen.** The system turns a full-screen intent into a heads-up while the phone is
  unlocked and in use. `WaIncoming.show` now also starts the call screen itself in that case (allowed on HyperOS with
  "Display pop-up windows while running in the background"; refused quietly elsewhere, the heads-up stays) and logs a
  warning when `canUseFullScreenIntent()` is false. The live gateway event posts the ringing notification too
  (`WaRingNotifier`), not only the FCM push.
- **Slide to answer on an unlocked phone.** The Incoming screen read `CallSnapshot.keyguardLocked` from the registry,
  which refreshes it only while a telecom call runs (its default is `true`). `WaMergedCallSource` now takes the keyguard
  from `PhoneContainer.keyguardLocked`, which the activity keeps current (resume, unlock, screen on/off, a call).

## Lock-screen Answer, proximity, ringtone, localized in-call notice (E5.8a follow-up 3)

Four owner items, all in the app (the gateway is unchanged).

- **Answer and Decline without unlocking.** SystemUI asks for the unlock before it runs an *activity* action from a
  lock-screen notification. The incoming `CallStyle`'s Answer and Decline are now broadcasts to
  `dev.rovsti.phone.wa.WaCallActionReceiver` (not exported, `FLAG_IMMUTABLE`, installed as
  `PlatformComponents.waCallAction`); the full-screen and body intents stay the activity. The receiver runs
  `WaIncoming.takeAction` (cancels the notification, stops the ring) and hands the payload to
  `WaCallActions.submit`, which waits for `awaitRustReady` (a cold process woken by the FCM push) and for the call to
  ring, as before; `goAsync` keeps the process up to 9 s. Answer then calls `WaIncoming.openAnswered`: it starts
  `MainActivity` with `wa_call_id` + `wa_call_answered` (`LockScreenGate` holds the flags until the list has the call
  as `Connecting`), and, because Android 12+ refuses an activity start from a notification broadcast ("trampoline")
  unless the app has a background-start exemption, it also posts a short "Answering…" notice with a full-screen intent
  while the phone is locked or the screen is off; the activity cancels it. Without the receiver installed (other apps)
  the actions fall back to the activity intents.
- **Proximity.** `WaProximity` holds `PROXIMITY_SCREEN_OFF_WAKE_LOCK` (if `isWakeLockLevelSupported`) while a WhatsApp
  call is `Active`, the communication device is the earpiece (`communicationOnEarpiece`, and the loudspeaker was not
  asked for) and the call is not muted; it releases with `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY` on a route change,
  mute, the end of the call and when the container scope ends. `WaInCallEffects` (phone) drives it from `WaCalls`.
- **Ringtone.** `WaRinger` (process-wide, `WaRing` is the testable policy) rings with the default ringtone
  (`USAGE_NOTIFICATION_RINGTONE`, looping) plus vibration (`USAGE_RINGTONE`). Silent ringer: nothing; vibrate:
  vibration only; another call live (`CallRegistry` or the audio mode is in-call/in-communication): the
  `TONE_SUP_CALL_WAITING` beep. Do Not Disturb: off rings; total silence and alarms-only do not; priority-only rings
  when calls from anyone are allowed or the `wa_calls` channel may override DND (calls from contacts/starred only stay
  silent: the app cannot tell). `WaIncoming.show` starts it (both the FCM push and the gateway event go there; one
  ring per call), `WaIncoming.cancel` stops it (accept, decline, `ringOver`, a non-Ringing event, `call_end` push), it
  stops by itself after `RING_TIMEOUT_MS`, and the call screen's volume/power keys silence it
  (`WaCallsRouter.silenceRinger`). The `wa_calls` channel stays silent.
- **In-call notification.** `WaCallService` shows `CallStyle.forOngoingCall` with Hang up (a broadcast to the same
  receiver, `WaCallActions.hangUp`) once `WaInCallEffects` named the call (`WaCallService.describe`), with the
  chronometer from when the call went `Active`; its body opens the In-Call screen (`wa_open_call` in `phoneLinkFor`).
  Its texts and the missed-call text are string resources in `:platform` (English, Turkish; `TurkishCoverageTest`
  now covers `platform`).

Tests: `WaRingTest`, `WaProximityTest`, `WaCallNotificationsTest` (platform), `WaCallActionsTest` (submit, hang up),
`WaCallActionReceiverTest`, `WaInCallEffectsTest`, `PhoneManifestTest` (phone).

On the phone: Answer and Decline from the lock screen, ringtone and vibration in each ringer mode, volume key while the
call screen shows, the screen going dark at the ear (and back on speaker), Hang up and the body of the ongoing
notification. HyperOS: "Display pop-up windows while running in the background" and "Show on lock screen" for Rovsti
Phone let the call screen open directly; full-screen notifications must be allowed (Android 14).

## Audio stops after a second or two; no Opus on the way out (E5.8a follow-up 4)

Owner's call, 2026-11-13 07:33 UTC, a recent APK. Gateway line (OpusInMlow):
`mic: app=21 ... rms=-88.6dBFS peak=28 voiced=0 | spk: engine=167 opus=0 mlow=167 ... queue_drop=128 pcm_ms=2340
stale=5 app=26`.

**What the numbers say.** The peer's audio reached the gateway the whole time (167 MLOW packets in 10 s, each decoded to
exactly 60 ms: `pcm_ms` 2340 = 39 x 60). The app's socket attached early (`stale=5`: only five frames had queued) and
then carried 26 frames out and 21 in, about 1.5 s, and nothing after that in **either** direction, while the call stayed
up: 167 = 5 stale + 26 sent + 8 left in the queue + 128 dropped on a full queue. No codec failure, no frame-size
mismatch, no engine stall, and the server build of the deploy that followed had finished 9 s before the call began (the
call ended 4 s before that restart). Neither side logged anything, because neither side noticed.

**Why both directions stop at once (the bug).** The gateway's `audio_socket` and the client's `pump` were each one
`select!` loop that both wrote and read the socket, awaiting each write before it read again. When the connection
slows for a moment (the phone's radio, the tunnel), the buffers between the two ends fill, the gateway waits in a
speaker write and stops reading the microphone, the phone waits in a microphone write and stops reading the speaker:
each waits for the other to read, for good. Nothing timed out and nothing reopened the socket; a second socket for
the call was refused (409) while the stuck one held the slot. `rovsti_wa_client` test
`the_speaker_keeps_flowing_while_microphone_writes_are_stuck` reproduces it: with the old loop the speaker starves.

**Fixed (gateway and app's Rust client).**

- Both ends read and write side by side (`calls::shuttle`, `audio::pump`), so a slow write never stops reading.
- The client pings every second and reopens the socket when nothing (no frame, no pong) came for 3 s, up to five times
  in a row (`a_stuck_socket_is_reopened_and_the_audio_resumes`). The mic and speaker channels stay the same, so Kotlin
  sees nothing of it. A close from the gateway (the call ended) or a failed connection still ends the audio.
- The gateway gives the call's audio to the newest socket and tells the old one to step down (it used to answer 409),
  and closes a socket that sent nothing for 5 s. It pings too.
- Each socket logs `attached` and `closed after Ns: <why>; sent N frames, got M`; the summary line ends with
  `sockets=N` (more than one: the app reopened).

**No Opus out: `MlowPcm` is the default.** The microphone is encoded by whatsapp-rust's own MLOW encoder; the answer
is the same MLOW signaling as `OpusInMlow` (`CallAudioMode::wire_format`, test
`mlow_pcm_is_the_default_and_answers_with_the_mlow_signaling`), so the caller still takes it. libopus is left only
to decode a peer that answers with the Opus escape (`ForeignAudio`; this peer sends plain MLOW). The engine's 20 ms
playout frames are now joined into 60 ms frames before the speaker queue (before, eight 20 ms frames, 160 ms, filled
it). Dropping libopus entirely is possible as a cargo feature of `wa_gateway` (off: build without `voip-libopus`,
`ForeignAudio` counts the escape packets as failed and plays silence; `NativeOpus`/`OpusInMlow` compile out), at the
cost of silence from a peer that sends the escape; it is not done, so no call can lose audio over it.

**Deploys no longer cut calls.** The gateway keeps the number of live calls (ringing, connecting, active) in
`<data_dir>/live-calls`; `remote-install.sh install` (and `rollback`) waits up to `CALL_WAIT` seconds (default 1800)
until it is 0, checks again right before the restart, and gives up rather than cut a call. `status` shows it. The
server build runs at `nice 19`.

**On the phone:** a call of at least a minute, both ways, with the phone at the ear (screen dark) for part of it and on
speaker for part. Then `journalctl -u wa-gateway | grep 'call audio'`: `sockets=1` and `mic: voiced` growing mean all
was well; `closed after ...: nothing came from the app` followed by a second `attached` means the connection got stuck
and the app recovered.

## Latency: the peer is heard about half a second late (E5.8a follow-up 5)

**What was wrong.** From the gateway's decoder to the phone's loudspeaker the peer's voice took about 500 to 650 ms on
a good connection, and after any stall more than a second, for the rest of the call. Nothing was broken; every stage
held audio it did not need:

| Stage | Before | Now |
|-------|--------|-----|
| Gateway: 20 ms playout frames joined into 60 ms (`run_pcm_downlink`) | the first sample of a frame waited 40 ms for the last | sent on as 20 ms frames |
| Gateway speaker queue (`AUDIO_QUEUE`, 8 x 60 ms) | full after a stall: the **oldest** 480 ms stayed, new frames were dropped | `FreshQueue`: 10 x 20 ms; when full the oldest go and the newest 40 ms stay |
| Client speaker queue (`QUEUE_FRAMES`, 8 x 60 ms) | the same, 480 ms | `queue.rs`: cut to 40 ms when more than 120 ms waits, and when more than 40 ms stood for a whole second |
| `audioPull` | 0 when nothing was ready, and the thread slept 10 ms (`IDLE_POLL_MS`) | waits for the frame (up to 20 ms) and hands it over at once |
| `AudioTrack` buffer | at least 4 x 60 ms = 240 ms, and playback started only when it was full | 2 x 60 ms = 120 ms, playback starts at 40 ms (`setStartThresholdInFrames`) |
| `AudioTrack` fill | whatever a burst left in it stayed (blocking writes) | `PlayoutTrim` drops the surplus over 40 ms once it stood for 2 s |

**Why a stall made it worse for good ("ratchet").** The track plays exactly as fast as frames arrive. When the network
holds frames back and then delivers them at once, the burst fills the track buffer, then the client queue, then the
gateway queue, and from then on every queue stays as full as the burst left it: nothing ever reads faster than real
time. Dropping the **newest** frame on a full queue (what all three queues did) kept exactly the wrong audio. A gateway
clock that runs a little faster than the phone's does the same thing slowly. Now every queue drops its oldest audio,
and the two on the phone also notice a queue that merely stands: the least that waited over a window (1 s in the Rust
queue, 2 s in the track) was never needed, so the part of it above 40 ms is cut. Jitter is left alone: a fill that
dips to the target within the window shows the buffer is in use.

**The frame size on the wire, and old versions.** The microphone direction did not change (960 samples; MLOW fixes
it). For the speaker the app asks: it opens `/v1/calls/<id>/audio?spk_ms=20` (`SpeakerFrames::Step`,
`rovsti_wa_client::call_audio_with`, used by `wa.call_audio_open`).

| App | Gateway | What happens |
|-----|---------|--------------|
| new | new | 640-byte messages, 20 ms each |
| old (asks for nothing) | new | the gateway joins three slots into the 1 920-byte frames that app has always taken (`SpeakerFrames::Whole`) |
| new | old (ignores the query) | 1 920-byte messages; the receiver takes 640, 1 280 and 1 920 (`decode_speaker_frame`), `audioPull` answers the length and Kotlin writes that many bytes (`WaCallAudio.playable`) |

The GPUI Phone app (`apps/phone`) keeps asking for whole frames (`call_audio`): its Kotlin bridge takes exactly 960
samples. Both versions keep every counter and the reopen/takeover behaviour of follow-up 4.

**What was not done.** The socket is still a WebSocket over TCP through the tunnel (a lost packet delays everything
behind it; UDP or QUIC would not), and the engine's own jitter buffer is untouched. Those are the two remaining large
parts of the delay.

**Measuring it on a real call.**

- Gateway, `journalctl -u wa-gateway | grep 'call audio'`: the counters line has `queue_mean=..ms queue_max=..ms` (how
  much waited in the speaker queue when a frame came: 0 while the connection moves, up to 200 ms during a stall),
  `queue_drop` (20 ms slots dropped, oldest first), `app` and `app_ms` (frames and milliseconds sent to the app;
  `app_ms / app` is 20 for a new app and 60 for an old one). `attached, 20 ms speaker frames` says what the socket's
  app asked for.
- Gateway, the app's own report every 10 s on the same socket (a text message; an older gateway ignores it):
  `call audio <id> socket <n>: app: spk_q mean=..ms max=..ms cut=..ms frames=.. | mic_q ...`. `mean` and `max` are what
  a new frame found waiting in the client queue, `cut` is old audio dropped to get back to the target, `frames` per
  10 s is about 500 with 20 ms frames and about 166 with 60 ms ones. The app writes the same line to logcat
  (`wa audio: ...`).
- Phone, `adb logcat -s Rovsti | grep 'WA call audio'`, every 10 s:
  `track_fill mean=..ms min=..ms max=..ms trimmed=..ms frames=.. frame=20ms underruns=.. buffer=120ms`. `track_fill` is
  frames written minus `playbackHeadPosition` before each write: the delay the track alone adds. `trimmed` is what
  `PlayoutTrim` dropped, `underruns` is `AudioTrack.underrunCount` (it grows when the track ran dry: gaps).

Good: `track_fill mean` around 40 to 60 ms, `spk_q mean` 0 to 20 ms, `queue_mean` 0 ms, `underruns` growing slowly or
not at all. After switching Wi-Fi to mobile data in the call, `cut`, `trimmed` or `queue_drop` rise once and the means
come back within a few seconds. If `underruns` climbs steadily and the voice crackles, the 40 ms target is too tight
for that connection (`WaCallAudio.TARGET_FILL_MS`, `queue::SPEAKER_LIMITS`).

**Tests.** `rovsti_wa_proto::audio` (20 ms round trip, the receiver takes 20/40/60 ms, the query), `rovsti_wa_client`
`queue` (oldest dropped, drain to the target, a standing queue, clock drift, the waiting read) and `audio`
(`frames_of_20_ms_and_of_60_ms_both_reach_the_speaker`, a burst from the socket), `tests/call_audio.rs`
(`a_client_that_asks_for_20_ms_frames_plays_20_ms_and_whole_frames`), `rovsti_ffi` `tests/wa_live.rs` (pull sizes
against an old and a new gateway, the waiting pull), `wa_gateway` `call_audio` (`FreshQueue`, 20 ms frames passed on,
a stalled socket keeps the newest audio, the counters line) and `calls`
(`the_socket_sends_20_ms_frames_only_to_an_app_that_asked_for_them`), `tests/api.rs` (old and new app end to end),
`android/platform` `WaCallPlayoutTest` (buffer size, start threshold, frame sizes, fill, trim, the log line).
