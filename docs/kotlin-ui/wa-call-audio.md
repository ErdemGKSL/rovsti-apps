# WhatsApp call audio: every hop (E5.8a)

Status: traced and tested in software (round 13, step 6). The check on a real phone is an owner item (see the end).

One format runs the whole way: **16 kHz, mono, signed 16-bit little-endian PCM, 960 samples = 1 920 bytes = 60 ms per
frame**, in both directions (`crates/rovsti_wa_proto/src/audio.rs`: `SAMPLE_RATE`, `FRAME_SAMPLES`, `FRAME_BYTES`).
The only hop that uses another size is inside the gateway: WhatsApp's decoder hands over 20 ms (320 sample) frames.

## Microphone: phone to WhatsApp

| # | Hop | Format | Code |
|---|-----|--------|------|
| 1 | `AudioRecord`, source `VOICE_COMMUNICATION` (platform echo cancel and noise suppression), `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`, 16 kHz, buffer at least 4 frames | PCM16 mono, reads of any size | `android/platform/.../wa/WaCallAudio.kt` (`start`, `format`, `RECORD_SOURCE`, `bufferBytes`) |
| 2 | Thread `wa-mic` fills a `ByteArray(1920)` with whole reads, then pushes | 1 920 bytes | `WaCallAudio.pumpMic` |
| 3 | `CallFrames.push` -> `CallAudio.push` (never the main thread) -> `RustNative.audioPush(handle, pcm)` | 1 920 bytes, JNI `byte[]` | `android/core-bridge/.../ops/WaGateway.kt`, `RustNative.kt` |
| 4 | `Java_dev_rovsti_core_RustNative_audioPush` -> `ops::audio_push`: `decode_frame` (other sizes are dropped with a debug log), `mic.try_send` (a full queue drops the frame, no added latency) | 960 `i16` | `crates/rovsti_ffi/src/jni_exports.rs`, `ops/wa/live.rs` |
| 5 | `rovsti_wa_client::audio::call_audio`: `pump` encodes with `encode_frame` and sends one **binary WebSocket message** per frame to `wss://<gateway>/v1/calls/<id>/audio` with `Authorization: Bearer <connect key>` | 1 920 bytes per message | `crates/rovsti_wa_client/src/audio.rs` |
| 6 | Gateway `audio_socket` -> `feed_mic`: `decode_frame` (wrong sizes dropped), `try_send` into the call's `mic_tx` (`Lagging` frames are dropped) | 960 `i16` | `services/wa_gateway/src/wa/calls.rs` |
| 7 | `MediaPlan` (gateway): in the default mode `call_audio::run_uplink` encodes each frame with libopus (`WaOpusEncoder`, 24 kb/s, DTX) and hands the packet to the engine (`encoded_audio`), which wraps it in RTP/SRTP. See "Codec mode" below | 960 `i16` frames in, one Opus packet out | `calls.rs`, `call_audio.rs` |

## Speaker: WhatsApp to phone

| # | Hop | Format | Code |
|---|-----|--------|------|
| 1 | Default mode: the engine hands each decrypted packet to `call_audio::run_downlink`, which decodes it (libopus, or the MLOW decoder for an MLOW packet) into `spk_tx`. PCM mode: the engine's own decoder writes **20 ms, 320 sample** frames (any length is handled) | 960 samples per packet (default), 320 (PCM mode) | `calls.rs`, `call_audio.rs` |
| 2 | `audio_socket` rechunks with `Rechunker` (commit 457ac0f) and sends only whole frames; leftover samples wait for the next chunk | 1 920-byte binary messages | `rovsti_wa_proto::audio::Rechunker`, `audio_socket` |
| 3 | Client `pump` decodes each message (wrong size: dropped) and `try_send`s it to `speaker`; a full queue (8 frames, about half a second) drops the frame instead of adding latency | 960 `i16` | `audio.rs` |
| 4 | `RustNative.audioPull(handle, out)`: 1 920 = a frame, 0 = none ready, -1 = call ended or handle gone | 1 920 bytes | `ops::audio_pull`, `jni_exports.rs` |
| 5 | Thread `wa-speaker`: frame -> `AudioTrack.write(WRITE_BLOCKING)` (the track paces the thread; a non-blocking write used to cut a burst off at the track buffer); 0 -> parks 10 ms; -1 -> thread ends | 1 920 bytes | `WaCallAudio.pumpSpeaker` |
| 6 | `AudioTrack`: `USAGE_VOICE_COMMUNICATION`, `CONTENT_TYPE_SPEECH`, `CHANNEL_OUT_MONO`, `ENCODING_PCM_16BIT`, 16 kHz, `MODE_STREAM`, buffer at least 4 frames | PCM16 mono | `WaCallAudio.trackAttributes`, `format` |

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
| `OpusInMlow` (**default**) | `encoded_audio(AudioFormat::OPUS_MLOW_16KHZ_60MS, ..)` | `WaOpusEncoder::new_mlow_escape()` (CELT in MLOW's escape, SID for silence) | escape: `decode_mlow_escape`; MLOW: wacore `MlowDecoder` (PT 121 = redundancy) |
| `MlowPcm` (the old path) | `.audio(mic, speaker)` | engine's MLOW encoder | engine's MLOW decoder, plus `CallEvent::ForeignAudio` decoded with `decode_mlow_escape` |

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
