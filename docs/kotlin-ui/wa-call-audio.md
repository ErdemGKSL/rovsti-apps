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
| 7 | Engine end `mic_rx` of the whatsapp-rust call (`.audio(io.mic_rx, io.spk_tx)`), encoded and sent to WhatsApp | 960 `i16` frames | `calls.rs`, `CallIo` |

## Speaker: WhatsApp to phone

| # | Hop | Format | Code |
|---|-----|--------|------|
| 1 | Engine decodes the peer's voice into `spk_tx` | **20 ms, 320 samples** (any length is handled) | `calls.rs` (`CallIo`) |
| 2 | `audio_socket` rechunks with `Rechunker` (commit 457ac0f) and sends only whole frames; leftover samples wait for the next chunk | 1 920-byte binary messages | `rovsti_wa_proto::audio::Rechunker`, `audio_socket` |
| 3 | Client `pump` decodes each message (wrong size: dropped) and `try_send`s it to `speaker`; a full queue (8 frames, about half a second) drops the frame instead of adding latency | 960 `i16` | `audio.rs` |
| 4 | `RustNative.audioPull(handle, out)`: 1 920 = a frame, 0 = none ready, -1 = call ended or handle gone | 1 920 bytes | `ops::audio_pull`, `jni_exports.rs` |
| 5 | Thread `wa-speaker`: frame -> `AudioTrack.write(WRITE_NON_BLOCKING)`; 0 -> parks 10 ms; -1 -> thread ends | 1 920 bytes | `WaCallAudio.pumpSpeaker` |
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
  otherwise). `AndroidCallAudioPump` starts it after the audio runs and stops it first. `RovstiInCallService` keeps
  `phoneCall` for telecom calls. No R8 rule is needed: manifest components are kept by the aapt2 rules.
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

## Owner item: check on a real phone

Software cannot hear. On a phone, place a WhatsApp call from the Phone app to another account and check: the other
side hears you, you hear them, earpiece / speaker / Bluetooth headset switch, music pauses and resumes, the call keeps
working with the screen off or the app in the background (the microphone notification shows), and no audio is
captured before the other side answers.
