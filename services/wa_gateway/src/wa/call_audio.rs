//! The codec side of a WhatsApp call's audio, and the per-call audio counters.
//!
//! The apps speak 16 kHz mono PCM (`rovsti_wa_proto::audio`). How that PCM
//! meets WhatsApp is [`CALL_AUDIO_MODE`]:
//!
//! - [`CallAudioMode::MlowPcm`] (default): whatsapp-rust's PCM audio
//!   (`AudioConfig::MLOW_PCM`). The engine encodes the microphone with its own
//!   pure-Rust MLOW encoder and decodes the peer's MLOW itself: no Opus on the
//!   way out. The answer is the same MLOW signaling as in `OpusInMlow` (same
//!   RTP profile, payload type and rate, see [`CallAudioMode::wire_format`]),
//!   the one the caller's phone takes. Only a peer that answers with Opus/CELT
//!   through MLOW's escape still needs libopus: the engine hands those packets
//!   over as `CallEvent::ForeignAudio`, which [`ForeignAudio`] decodes into the
//!   speaker. The engine keeps ticking silent 20 ms playout frames meanwhile,
//!   so [`run_pcm_downlink`] holds those back while foreign audio plays. The
//!   engine's 20 ms frames go to the speaker queue as they are, and every
//!   other source is cut to 20 ms too ([`to_speaker`]), so one slot of that
//!   queue is always 20 ms.
//! - [`CallAudioMode::OpusInMlow`]: encoded audio in the MLOW RTP profile,
//!   answered with the same MLOW signaling. The microphone goes out as
//!   Opus/CELT through MLOW's escape (libopus); the peer may answer with MLOW
//!   or with the escape, and both are decoded here. The default from
//!   2026-11-11 to 2026-11-13.
//! - [`CallAudioMode::NativeOpus`]: whatsapp-rust's encoded audio
//!   with `AudioFormat::OPUS_16KHZ_60MS`. The gateway encodes the microphone
//!   with libopus and decodes the peer's packets itself. The signaling clears
//!   the MLOW capability, so the peer sends standard Opus too. On the owner's
//!   phone the caller never took this answer: their phone rang on and the
//!   call ended as missed. Kept only to compare.
//!
//! The queues between the socket and the engine are [`FreshQueue`]s: short,
//! and when one overflows the oldest audio goes, never the newest, so a
//! stall ends with the audio of now and not with a standing delay.
//!
//! [`CallAudioStats`] counts every hop and measures the levels, and
//! [`AudioSummary`] turns that into the one log line per call.

use std::fmt;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use async_channel::{Receiver, Sender, TrySendError};
use bytes::Bytes;
use rovsti_wa_proto::audio::{STEP_SAMPLES, samples_ms};
use whatsapp_rust::voip::audio::{WaOpusDecoder, WaOpusEncoder};
use whatsapp_rust::voip::{AudioCodec, AudioFormat, AudioRtpProfile, EncodedAudioFrame};
use whatsapp_rust::wacore::voip::rtp::RTP_PAYLOAD_TYPE_MLOW_RED;
use whatsapp_rust::wacore::voip::{AudioConfig, MlowDecoder};

pub type Frame = Vec<i16>;

/// How the gateway carries call audio to WhatsApp. See the module docs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CallAudioMode {
    NativeOpus,
    OpusInMlow,
    MlowPcm,
}

/// The mode every new call uses.
pub const CALL_AUDIO_MODE: CallAudioMode = CallAudioMode::MlowPcm;

/// Slots of the speaker queue (decoder to socket), 20 ms each: 200 ms. The
/// socket takes a frame the moment it can write, so the queue is empty while
/// the connection moves and fills only while it does not.
pub const SPK_QUEUE: usize = 10;
/// What the speaker queue keeps when it overflowed: the newest 40 ms.
pub const SPK_TARGET: usize = 2;
/// Slots of the microphone queues (socket to codec task to engine), 60 ms
/// each: 240 ms.
pub const MIC_QUEUE: usize = 4;
/// What a microphone queue keeps when it overflowed: the newest 60 ms.
pub const MIC_TARGET: usize = 1;
/// Playout frames the PCM engine may have waiting for the gateway's task
/// (20 ms each). The task forwards at once, so this only covers scheduling.
pub const ENGINE_PLAYOUT_QUEUE: usize = 24;
/// Encoded packets queued towards the engine (about 180 ms).
pub const ENCODED_QUEUE: usize = 3;
/// Decrypted packets queued from the engine (about one second).
pub const INBOUND_QUEUE: usize = 16;
/// After a foreign (Opus) packet, the engine's all-zero playout frames are
/// held back this long so they do not interleave with the decoded audio.
pub const FOREIGN_HOLD: Duration = Duration::from_millis(500);
/// A frame whose peak reaches this (about -40 dBFS) counts as sound.
const VOICED_PEAK: u32 = 328;

impl CallAudioMode {
    /// Short name for the logs.
    pub fn label(self) -> &'static str {
        match self {
            Self::NativeOpus => "native-opus pt120 16k/60ms",
            Self::OpusInMlow => "opus-in-mlow pt120 16k/60ms",
            Self::MlowPcm => "mlow-pcm pt120 16k/60ms",
        }
    }

    /// The whatsapp-rust format of an encoded mode; `None` for PCM.
    pub fn encoded_format(self) -> Option<AudioFormat> {
        match self {
            Self::NativeOpus => Some(AudioFormat::OPUS_16KHZ_60MS),
            Self::OpusInMlow => Some(AudioFormat::OPUS_MLOW_16KHZ_60MS),
            Self::MlowPcm => None,
        }
    }

    /// The format the call is signaled and carried in. `MlowPcm` and
    /// `OpusInMlow` share the MLOW profile, so they answer alike.
    pub fn wire_format(self) -> AudioFormat {
        self.encoded_format()
            .unwrap_or(AudioConfig::MLOW_PCM.format)
    }

    /// The gateway's microphone encoder of an encoded mode. PCM mode has
    /// none: the engine encodes MLOW itself.
    pub fn encoder(self) -> Result<WaOpusEncoder, String> {
        let encoder = match self {
            Self::OpusInMlow => WaOpusEncoder::new_mlow_escape(),
            Self::NativeOpus => WaOpusEncoder::new(),
            Self::MlowPcm => return Err("PCM mode has no gateway encoder".to_owned()),
        };
        encoder.map_err(|e| e.to_string())
    }
}

// ------------------------------------------------------------------ queues

/// The call is over: the queue is closed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct QueueClosed;

/// A short bounded queue that keeps the newest audio. A push never waits
/// and never refuses the new frame: when the queue is full, the **oldest**
/// frames are dropped until `target` are left (the new one included). So
/// after a stall the reader finds the audio of now, and at most `target`
/// frames of delay, instead of a full queue that stays one.
#[derive(Clone, Debug)]
pub struct FreshQueue {
    tx: Sender<Frame>,
    rx: Receiver<Frame>,
    target: usize,
}

impl FreshQueue {
    /// A queue of `capacity` frames that falls back to `target` (at least
    /// one, at most the capacity) when it overflows.
    pub fn new(capacity: usize, target: usize) -> Self {
        let capacity = capacity.max(1);
        let (tx, rx) = async_channel::bounded(capacity);
        Self {
            tx,
            rx,
            target: target.clamp(1, capacity),
        }
    }

    /// Queues `frame`. Answers how many of the oldest frames were dropped
    /// to make room.
    pub fn push(&self, frame: Frame) -> Result<usize, QueueClosed> {
        let mut dropped = 0;
        if self.tx.is_full() {
            while self.rx.len() >= self.target && self.rx.try_recv().is_ok() {
                dropped += 1;
            }
        }
        let mut frame = frame;
        loop {
            match self.tx.try_send(frame) {
                Ok(()) => return Ok(dropped),
                Err(TrySendError::Closed(_)) => return Err(QueueClosed),
                // Another sender got in between: make room for one more.
                Err(TrySendError::Full(back)) => {
                    frame = back;
                    if self.rx.try_recv().is_ok() {
                        dropped += 1;
                    }
                }
            }
        }
    }

    /// The reading end. Clones share the queue.
    pub fn receiver(&self) -> Receiver<Frame> {
        self.rx.clone()
    }

    /// Frames waiting.
    pub fn len(&self) -> usize {
        self.rx.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rx.is_empty()
    }

    /// Throws away everything waiting; answers how many frames.
    pub fn clear(&self) -> usize {
        std::iter::from_fn(|| self.rx.try_recv().ok()).count()
    }

    /// Ends the queue for both sides.
    pub fn close(&self) {
        self.tx.close();
    }
}

// ------------------------------------------------------------------ levels

/// The level of the PCM that went through one point.
#[derive(Debug, Default)]
pub struct Level {
    sum_sq: AtomicU64,
    samples: AtomicU64,
    peak: AtomicU32,
    voiced_frames: AtomicU64,
}

impl Level {
    pub fn add(&self, pcm: &[i16]) {
        if pcm.is_empty() {
            return;
        }
        let mut sum = 0u64;
        let mut peak = 0u32;
        for &s in pcm {
            let a = u32::from(s.unsigned_abs());
            sum += u64::from(a * a);
            peak = peak.max(a);
        }
        self.sum_sq.fetch_add(sum, Ordering::Relaxed);
        self.samples.fetch_add(pcm.len() as u64, Ordering::Relaxed);
        self.peak.fetch_max(peak, Ordering::Relaxed);
        if peak >= VOICED_PEAK {
            self.voiced_frames.fetch_add(1, Ordering::Relaxed);
        }
    }

    fn snapshot(&self) -> LevelSnapshot {
        let samples = self.samples.load(Ordering::Relaxed);
        let rms_dbfs = (samples > 0).then(|| {
            let mean = self.sum_sq.load(Ordering::Relaxed) as f64 / samples as f64;
            let rms = mean.sqrt() / 32768.0;
            if rms > 0.0 {
                (20.0 * rms.log10()).max(-120.0)
            } else {
                -120.0
            }
        });
        LevelSnapshot {
            rms_dbfs,
            peak: self.peak.load(Ordering::Relaxed),
            voiced_frames: self.voiced_frames.load(Ordering::Relaxed),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LevelSnapshot {
    /// `None` when no sample went through.
    pub rms_dbfs: Option<f64>,
    pub peak: u32,
    /// Frames whose peak reached about -40 dBFS.
    pub voiced_frames: u64,
}

impl fmt::Display for LevelSnapshot {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.rms_dbfs {
            Some(db) => write!(f, "rms={db:.1}dBFS")?,
            None => f.write_str("rms=none")?,
        }
        write!(f, " peak={} voiced={}", self.peak, self.voiced_frames)
    }
}

// ------------------------------------------------------------------- stats

/// Counters of one call's audio, shared by the socket, the codec tasks and
/// the engine event loop.
#[derive(Debug, Default)]
pub struct CallAudioStats {
    format: OnceLock<&'static str>,
    /// Whole frames the app socket delivered (lagging ones included).
    pub mic_from_app: AtomicU64,
    /// Socket messages that were not one frame long.
    pub mic_bad_size: AtomicU64,
    /// The gateway's microphone queue was full when a frame came: its
    /// oldest frames were dropped.
    pub mic_lagging: AtomicU64,
    /// Frames (PCM mode) or packets (encoded modes) handed to the engine.
    pub mic_to_engine: AtomicU64,
    /// Dropped because the engine's queue was full (PCM mode: the oldest
    /// frames; encoded modes: the new packet).
    pub mic_engine_dropped: AtomicU64,
    pub mic_encode_failed: AtomicU64,
    /// Playout frames (PCM mode) or packets (encoded modes) from the engine.
    pub spk_from_engine: AtomicU64,
    pub inbound_opus: AtomicU64,
    pub inbound_mlow: AtomicU64,
    pub decode_failed: AtomicU64,
    /// `CallEvent::ForeignAudio` packets (PCM mode only).
    pub foreign: AtomicU64,
    pub foreign_ok: AtomicU64,
    pub foreign_failed: AtomicU64,
    /// Silent engine frames held back while foreign audio played.
    pub spk_gated: AtomicU64,
    /// Speaker frames (20 ms each) dropped, oldest first, because the
    /// socket was behind and the queue full.
    pub spk_queue_dropped: AtomicU64,
    /// The speaker queue's depth when a frame came: the sum over all
    /// frames, in slots of 20 ms, ...
    pub spk_depth_sum: AtomicU64,
    /// ... how many frames that was, ...
    pub spk_depth_frames: AtomicU64,
    /// ... and the deepest it was.
    pub spk_depth_max: AtomicU64,
    /// Decoded packets that gave no samples (nothing reached the speaker).
    pub spk_empty: AtomicU64,
    /// Samples handed to the speaker queue (16 kHz), to compare with the
    /// call's length and with `app_ms`.
    pub spk_samples: AtomicU64,
    /// Queued speaker frames thrown away when the app's socket attached
    /// (stale audio from before it listened).
    pub spk_stale: AtomicU64,
    /// Frames sent to the app (20 ms each, or 60 ms for an app that did not
    /// ask for 20 ms frames).
    pub spk_to_app: AtomicU64,
    /// Samples sent to the app.
    pub spk_app_samples: AtomicU64,
    /// Audio sockets the app opened (more than one: it reopened a stuck one).
    pub sockets: AtomicU64,
    /// Level of the microphone PCM on its way to the engine.
    pub mic_level: Level,
    /// Level of the PCM sent to the app.
    pub spk_level: Level,
}

pub(crate) fn bump(counter: &AtomicU64) {
    counter.fetch_add(1, Ordering::Relaxed);
}

fn load(counter: &AtomicU64) -> u64 {
    counter.load(Ordering::Relaxed)
}

impl CallAudioStats {
    /// Records the negotiated format (first call wins).
    pub fn set_format(&self, label: &'static str) {
        let _ = self.format.set(label);
    }

    pub fn summary(&self) -> AudioSummary {
        AudioSummary {
            format: self.format.get().copied().unwrap_or("none"),
            mic_from_app: load(&self.mic_from_app),
            mic_bad_size: load(&self.mic_bad_size),
            mic_lagging: load(&self.mic_lagging),
            mic_to_engine: load(&self.mic_to_engine),
            mic_engine_dropped: load(&self.mic_engine_dropped),
            mic_encode_failed: load(&self.mic_encode_failed),
            mic_level: self.mic_level.snapshot(),
            spk_from_engine: load(&self.spk_from_engine),
            inbound_opus: load(&self.inbound_opus),
            inbound_mlow: load(&self.inbound_mlow),
            decode_failed: load(&self.decode_failed),
            foreign: load(&self.foreign),
            foreign_ok: load(&self.foreign_ok),
            foreign_failed: load(&self.foreign_failed),
            spk_gated: load(&self.spk_gated),
            spk_queue_dropped: load(&self.spk_queue_dropped),
            spk_empty: load(&self.spk_empty),
            spk_ms: samples_ms(load(&self.spk_samples) as usize),
            spk_queue_mean_ms: samples_ms(STEP_SAMPLES) * load(&self.spk_depth_sum)
                / load(&self.spk_depth_frames).max(1),
            spk_queue_max_ms: samples_ms(STEP_SAMPLES) * load(&self.spk_depth_max),
            spk_stale: load(&self.spk_stale),
            spk_to_app: load(&self.spk_to_app),
            spk_app_ms: samples_ms(load(&self.spk_app_samples) as usize),
            spk_level: self.spk_level.snapshot(),
            sockets: load(&self.sockets),
        }
    }
}

/// A copy of the counters, printed as the diagnostics line.
#[derive(Clone, Debug, PartialEq)]
pub struct AudioSummary {
    pub format: &'static str,
    pub mic_from_app: u64,
    pub mic_bad_size: u64,
    pub mic_lagging: u64,
    pub mic_to_engine: u64,
    pub mic_engine_dropped: u64,
    pub mic_encode_failed: u64,
    pub mic_level: LevelSnapshot,
    pub spk_from_engine: u64,
    pub inbound_opus: u64,
    pub inbound_mlow: u64,
    pub decode_failed: u64,
    pub foreign: u64,
    pub foreign_ok: u64,
    pub foreign_failed: u64,
    pub spk_gated: u64,
    pub spk_queue_dropped: u64,
    pub spk_empty: u64,
    /// Milliseconds of decoded audio handed to the speaker queue.
    pub spk_ms: u64,
    /// Mean depth of the speaker queue when a frame came, in milliseconds.
    pub spk_queue_mean_ms: u64,
    /// The deepest the speaker queue was when a frame came, in milliseconds.
    pub spk_queue_max_ms: u64,
    pub spk_stale: u64,
    pub spk_to_app: u64,
    /// Milliseconds of audio sent to the app.
    pub spk_app_ms: u64,
    pub spk_level: LevelSnapshot,
    pub sockets: u64,
}

impl fmt::Display for AudioSummary {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "format={} | mic: app={} bad={} lag={} engine={} engine_drop={} enc_fail={} {} | \
             spk: engine={} opus={} mlow={} dec_fail={} foreign={} foreign_ok={} \
             foreign_fail={} gated={} queue_drop={} queue_mean={}ms queue_max={}ms empty={} \
             pcm_ms={} stale={} app={} app_ms={} {} | sockets={}",
            self.format,
            self.mic_from_app,
            self.mic_bad_size,
            self.mic_lagging,
            self.mic_to_engine,
            self.mic_engine_dropped,
            self.mic_encode_failed,
            self.mic_level,
            self.spk_from_engine,
            self.inbound_opus,
            self.inbound_mlow,
            self.decode_failed,
            self.foreign,
            self.foreign_ok,
            self.foreign_failed,
            self.spk_gated,
            self.spk_queue_dropped,
            self.spk_queue_mean_ms,
            self.spk_queue_max_ms,
            self.spk_empty,
            self.spk_ms,
            self.spk_stale,
            self.spk_to_app,
            self.spk_app_ms,
            self.spk_level,
            self.sockets,
        )
    }
}

/// A JID or phone number for the logs: the first two and last two
/// characters of the user part, and the server.
pub fn mask_peer(peer: &str) -> String {
    let (user, server) = match peer.split_once('@') {
        Some((user, server)) => (user, Some(server)),
        None => (peer, None),
    };
    // Drop a device suffix (`:12`) and an agent (`_1`): they identify too.
    let user = user.split([':', '_']).next().unwrap_or_default();
    let chars: Vec<char> = user.chars().collect();
    let masked = if chars.len() > 6 {
        let head: String = chars[..2].iter().collect();
        let tail: String = chars[chars.len() - 2..].iter().collect();
        format!("{head}…{tail}")
    } else {
        "…".to_owned()
    };
    match server {
        Some(server) => format!("{masked}@{server}"),
        None => masked,
    }
}

// ----------------------------------------------------------------- decoding

/// Decodes the peer's packets, with the state of one call.
#[derive(Default)]
pub struct InboundDecoder {
    opus: Option<WaOpusDecoder>,
    mlow: Option<MlowDecoder>,
}

impl InboundDecoder {
    pub fn new() -> Self {
        Self::default()
    }

    /// Decodes one Opus packet to 16 kHz mono PCM. `escaped`: the packet
    /// carries MLOW's rewritten CELT TOC (the MLOW RTP profile), which is
    /// restored before libopus sees it.
    pub fn decode_opus(&mut self, payload: &[u8], escaped: bool) -> Result<Frame, String> {
        if self.opus.is_none() {
            self.opus = Some(WaOpusDecoder::new().map_err(|e| e.to_string())?);
        }
        let Some(decoder) = self.opus.as_mut() else {
            return Err("no opus decoder".to_owned());
        };
        let pcm = if escaped {
            decoder.decode_mlow_escape(payload)
        } else {
            decoder.decode(payload)
        };
        pcm.map(<[i16]>::to_vec).map_err(|e| e.to_string())
    }

    /// Decodes one MLOW packet (`red`: payload type 121, with redundancy).
    pub fn decode_mlow(&mut self, payload: &[u8], red: bool) -> Frame {
        let decoder = self.mlow.get_or_insert_with(MlowDecoder::new);
        decoder.set_redundancy(i32::from(red));
        decoder
            .decode(payload)
            .iter()
            .map(|s| (s * 32767.0).clamp(-32768.0, 32767.0) as i16)
            .collect()
    }

    /// Decodes a packet the engine passed through in an encoded mode.
    pub fn decode_packet(
        &mut self,
        codec: AudioCodec,
        profile: AudioRtpProfile,
        payload_type: u8,
        payload: &[u8],
        stats: &CallAudioStats,
    ) -> Result<Frame, String> {
        match codec {
            AudioCodec::Mlow => {
                bump(&stats.inbound_mlow);
                Ok(self.decode_mlow(payload, payload_type == RTP_PAYLOAD_TYPE_MLOW_RED))
            }
            _ => {
                bump(&stats.inbound_opus);
                self.decode_opus(payload, profile == AudioRtpProfile::Mlow)
            }
        }
    }
}

/// Hands decoded audio to the socket side in slots of 20 ms: the engine's
/// playout frames as they are, a longer frame (60 ms of decoded Opus) cut
/// up. A full queue drops its oldest slots, never these. `false`: the call
/// is over.
pub fn to_speaker(spk: &FreshQueue, pcm: Frame, stats: &CallAudioStats) -> bool {
    if pcm.is_empty() {
        bump(&stats.spk_empty);
        return true;
    }
    if pcm.len() <= STEP_SAMPLES {
        return slot_to_speaker(spk, pcm, stats);
    }
    pcm.chunks(STEP_SAMPLES)
        .all(|slot| slot_to_speaker(spk, slot.to_vec(), stats))
}

fn slot_to_speaker(spk: &FreshQueue, slot: Frame, stats: &CallAudioStats) -> bool {
    let samples = slot.len() as u64;
    let depth = spk.len() as u64;
    match spk.push(slot) {
        Ok(dropped) => {
            stats.spk_samples.fetch_add(samples, Ordering::Relaxed);
            stats.spk_depth_sum.fetch_add(depth, Ordering::Relaxed);
            bump(&stats.spk_depth_frames);
            stats.spk_depth_max.fetch_max(depth, Ordering::Relaxed);
            stats
                .spk_queue_dropped
                .fetch_add(dropped as u64, Ordering::Relaxed);
            true
        }
        Err(QueueClosed) => false,
    }
}

// ------------------------------------------------------------ encoded tasks

/// Microphone PCM to encoded packets for the engine, until either side ends.
/// While `muted`, silence is encoded so the stream stays fed (the engine's
/// own mute only covers PCM mode).
pub async fn run_uplink(
    mic: Receiver<Frame>,
    engine: Sender<Bytes>,
    mut encoder: WaOpusEncoder,
    muted: Arc<AtomicBool>,
    stats: Arc<CallAudioStats>,
) {
    while let Ok(mut frame) = mic.recv().await {
        if muted.load(Ordering::Relaxed) {
            frame.fill(0);
        }
        stats.mic_level.add(&frame);
        let packet = match encoder.encode(&frame) {
            Ok(packet) => packet,
            Err(e) => {
                if load(&stats.mic_encode_failed) == 0 {
                    log::warn!("call audio: encoding the microphone failed: {e}");
                }
                bump(&stats.mic_encode_failed);
                continue;
            }
        };
        match engine.try_send(Bytes::from(packet)) {
            Ok(()) => bump(&stats.mic_to_engine),
            Err(TrySendError::Full(_)) => bump(&stats.mic_engine_dropped),
            Err(TrySendError::Closed(_)) => break,
        }
    }
}

/// The engine's decrypted packets to speaker PCM, until the engine ends.
pub async fn run_downlink(
    engine: Receiver<EncodedAudioFrame>,
    spk: FreshQueue,
    mut decoder: InboundDecoder,
    stats: Arc<CallAudioStats>,
) {
    while let Ok(frame) = engine.recv().await {
        bump(&stats.spk_from_engine);
        let decoded = decoder.decode_packet(
            frame.codec,
            frame.format.rtp_profile,
            frame.payload_type,
            &frame.data,
            &stats,
        );
        match decoded {
            Ok(pcm) => {
                if !to_speaker(&spk, pcm, &stats) {
                    break;
                }
            }
            Err(e) => {
                if load(&stats.decode_failed) == 0 {
                    log::warn!("call audio: decoding a packet failed: {e}");
                }
                bump(&stats.decode_failed);
            }
        }
    }
}

// ---------------------------------------------------------------- PCM mode

/// Opus packets the PCM engine handed over as `CallEvent::ForeignAudio`.
pub struct ForeignAudio {
    decoder: Mutex<InboundDecoder>,
    spk: FreshQueue,
    stats: Arc<CallAudioStats>,
    last: Mutex<Option<Instant>>,
}

impl ForeignAudio {
    pub fn new(spk: FreshQueue, stats: Arc<CallAudioStats>) -> Self {
        Self {
            decoder: Mutex::new(InboundDecoder::new()),
            spk,
            stats,
            last: Mutex::new(None),
        }
    }

    /// Decodes one packet into the speaker. The engine only reports packets
    /// with MLOW's escape TOC, so the escape is undone first; a packet
    /// without it is tried as plain Opus.
    pub fn deliver(&self, payload: &[u8]) {
        bump(&self.stats.foreign);
        let escaped = payload.first().is_some_and(|toc| toc & 0xC0 == 0xC0);
        let decoded = self
            .decoder
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .decode_opus(payload, escaped);
        match decoded {
            Ok(pcm) => {
                bump(&self.stats.foreign_ok);
                *self.last.lock().unwrap_or_else(|e| e.into_inner()) = Some(Instant::now());
                to_speaker(&self.spk, pcm, &self.stats);
            }
            Err(e) => {
                if load(&self.stats.foreign_failed) == 0 {
                    log::warn!("call audio: decoding foreign audio failed: {e}");
                }
                bump(&self.stats.foreign_failed);
            }
        }
    }

    /// Foreign audio played within [`FOREIGN_HOLD`].
    pub fn playing(&self) -> bool {
        self.last
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_some_and(|at| at.elapsed() < FOREIGN_HOLD)
    }
}

/// Microphone frames to the PCM engine, counted. When the engine is behind,
/// its queue drops the oldest frames.
pub async fn run_pcm_uplink(mic: Receiver<Frame>, engine: FreshQueue, stats: Arc<CallAudioStats>) {
    while let Ok(frame) = mic.recv().await {
        stats.mic_level.add(&frame);
        match engine.push(frame) {
            Ok(dropped) => {
                bump(&stats.mic_to_engine);
                stats
                    .mic_engine_dropped
                    .fetch_add(dropped as u64, Ordering::Relaxed);
            }
            Err(QueueClosed) => break,
        }
    }
}

/// The PCM engine's playout to the speaker, counted. Its silent frames are
/// held back while foreign audio plays. The engine plays out 20 ms (320
/// sample) frames and they go on as they are: joining them into 60 ms
/// frames (as before) made the first sample of each wait 40 ms for the last.
pub async fn run_pcm_downlink(
    engine: Receiver<Frame>,
    spk: FreshQueue,
    foreign: Arc<ForeignAudio>,
    stats: Arc<CallAudioStats>,
) {
    while let Ok(frame) = engine.recv().await {
        bump(&stats.spk_from_engine);
        if foreign.playing() && frame.iter().all(|&s| s == 0) {
            bump(&stats.spk_gated);
            continue;
        }
        if !to_speaker(&spk, frame, &stats) {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::audio::{FRAME_SAMPLES, SAMPLE_RATE};

    /// One 60 ms frame of a sine at `hz`, continuing at sample `offset`.
    fn tone(hz: f64, offset: usize) -> Frame {
        (0..FRAME_SAMPLES)
            .map(|n| {
                let t = (offset + n) as f64 / f64::from(SAMPLE_RATE);
                (12_000.0 * (2.0 * std::f64::consts::PI * hz * t).sin()) as i16
            })
            .collect()
    }

    /// Signal power at `hz` (Goertzel), normalised so a sine of amplitude A
    /// at that frequency gives about `A^2 / 4`.
    fn goertzel(samples: &[i16], hz: f64) -> f64 {
        let n = samples.len() as f64;
        let k = (n * hz / f64::from(SAMPLE_RATE)).round();
        let w = 2.0 * std::f64::consts::PI * k / n;
        let coeff = 2.0 * w.cos();
        let (mut s1, mut s2) = (0.0f64, 0.0f64);
        for &x in samples {
            let s = f64::from(x) + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (n * n)
    }

    /// The 1 kHz tone survived a lossy codec: clear energy at 1 kHz, far
    /// less at 2.3 kHz.
    fn assert_tone(pcm: &[i16]) {
        assert!(pcm.len() >= FRAME_SAMPLES, "only {} samples", pcm.len());
        // Skip the codec's start-up: judge the last frame.
        let last = &pcm[pcm.len() - FRAME_SAMPLES..];
        let wanted = goertzel(last, 1_000.0);
        let other = goertzel(last, 2_300.0);
        // 12 000 amplitude gives 3.6e7; lossy coding may lose some.
        assert!(wanted > 1.0e7, "1 kHz power {wanted}");
        assert!(other < wanted / 100.0, "2.3 kHz {other} vs 1 kHz {wanted}");
        assert!(last.iter().any(|&s| s.unsigned_abs() > 3_000), "silent");
    }

    fn drain(rx: &Receiver<Frame>) -> Frame {
        let mut pcm = Vec::new();
        while let Ok(frame) = rx.try_recv() {
            pcm.extend(frame);
        }
        pcm
    }

    #[test]
    fn foreign_mlow_escape_opus_reaches_the_speaker_as_the_tone() {
        let mut encoder = WaOpusEncoder::new_mlow_escape().unwrap();
        let spk = FreshQueue::new(64, 64);
        let spk_rx = spk.receiver();
        let stats = Arc::new(CallAudioStats::default());
        let foreign = ForeignAudio::new(spk, Arc::clone(&stats));
        for i in 0..10 {
            let packet = encoder.encode(&tone(1_000.0, i * FRAME_SAMPLES)).unwrap();
            // What the engine reports: the escape TOC.
            assert_eq!(packet[0] & 0xC0, 0xC0, "packet {i}");
            foreign.deliver(&packet);
        }
        assert!(foreign.playing());
        let pcm = drain(&spk_rx);
        assert_eq!(pcm.len(), 10 * FRAME_SAMPLES);
        assert_tone(&pcm);
        let s = stats.summary();
        assert_eq!((s.foreign, s.foreign_ok, s.foreign_failed), (10, 10, 0));
    }

    #[test]
    fn foreign_garbage_counts_as_failed_and_stays_silent() {
        let spk = FreshQueue::new(4, 4);
        let spk_rx = spk.receiver();
        let stats = Arc::new(CallAudioStats::default());
        let foreign = ForeignAudio::new(spk, Arc::clone(&stats));
        // Escape TOC with a multi-frame flag but no frame count byte.
        foreign.deliver(&[0xC1]);
        // A plain Opus code-3 TOC without its frame count byte.
        foreign.deliver(&[0x03]);
        assert!(spk_rx.is_empty());
        assert!(!foreign.playing());
        let s = stats.summary();
        assert_eq!((s.foreign, s.foreign_ok, s.foreign_failed), (2, 0, 2));
    }

    #[test]
    fn native_and_escaped_opus_packets_decode_to_the_tone() {
        for (mode, profile) in [
            (CallAudioMode::NativeOpus, AudioRtpProfile::StandardOpus),
            (CallAudioMode::OpusInMlow, AudioRtpProfile::Mlow),
        ] {
            let mut encoder = mode.encoder().unwrap();
            let mut decoder = InboundDecoder::new();
            let stats = CallAudioStats::default();
            let mut pcm = Vec::new();
            for i in 0..10 {
                let packet = encoder.encode(&tone(1_000.0, i * FRAME_SAMPLES)).unwrap();
                let format = mode.encoded_format().unwrap();
                let codec = format.inbound_codec(format.rtp_payload_type, &packet);
                assert_eq!(codec, AudioCodec::Opus, "{mode:?}");
                pcm.extend(
                    decoder
                        .decode_packet(codec, profile, format.rtp_payload_type, &packet, &stats)
                        .unwrap(),
                );
            }
            assert_tone(&pcm);
            assert_eq!(stats.summary().inbound_opus, 10, "{mode:?}");
        }
    }

    #[test]
    fn mlow_packets_decode_through_the_mlow_decoder() {
        let mut decoder = InboundDecoder::new();
        let stats = CallAudioStats::default();
        // An MLOW SID (DTX) packet: 60 ms of silence.
        let pcm = decoder
            .decode_packet(
                AudioCodec::Mlow,
                AudioRtpProfile::Mlow,
                120,
                &[0x90],
                &stats,
            )
            .unwrap();
        assert_eq!(pcm.len(), FRAME_SAMPLES);
        assert!(pcm.iter().all(|&s| s == 0));
        assert_eq!(stats.summary().inbound_mlow, 1);
    }

    #[tokio::test]
    async fn uplink_encodes_the_microphone_and_downlink_decodes_it_back() {
        let stats = Arc::new(CallAudioStats::default());
        let (mic_tx, mic_rx) = async_channel::bounded(16);
        let (eng_tx, eng_rx) = async_channel::bounded(16);
        let muted = Arc::new(AtomicBool::new(false));
        let encoder = CallAudioMode::NativeOpus.encoder().unwrap();
        for i in 0..8 {
            mic_tx.try_send(tone(1_000.0, i * FRAME_SAMPLES)).unwrap();
        }
        mic_tx.close();
        run_uplink(mic_rx, eng_tx, encoder, muted, Arc::clone(&stats)).await;
        let mut decoder = InboundDecoder::new();
        let mut pcm = Vec::new();
        while let Ok(packet) = eng_rx.try_recv() {
            pcm.extend(decoder.decode_opus(&packet, false).unwrap());
        }
        assert_tone(&pcm);
        let s = stats.summary();
        assert_eq!((s.mic_to_engine, s.mic_engine_dropped), (8, 0));
        assert_eq!(s.mic_level.voiced_frames, 8);
        assert!(s.mic_level.rms_dbfs.unwrap() > -12.0, "{s}");
    }

    #[tokio::test]
    async fn muted_uplink_sends_silence() {
        let stats = Arc::new(CallAudioStats::default());
        let (mic_tx, mic_rx) = async_channel::bounded(4);
        let (eng_tx, eng_rx) = async_channel::bounded(4);
        let muted = Arc::new(AtomicBool::new(true));
        mic_tx.try_send(tone(1_000.0, 0)).unwrap();
        mic_tx.close();
        let encoder = CallAudioMode::OpusInMlow.encoder().unwrap();
        run_uplink(mic_rx, eng_tx, encoder, muted, Arc::clone(&stats)).await;
        // The escape encoder turns silence into MLOW's SID token.
        assert_eq!(eng_rx.try_recv().unwrap().as_ref(), [0x90]);
        assert_eq!(stats.summary().mic_level.voiced_frames, 0);
    }

    #[tokio::test]
    async fn pcm_downlink_holds_back_engine_silence_while_foreign_audio_plays() {
        let stats = Arc::new(CallAudioStats::default());
        let spk = FreshQueue::new(64, 64);
        let spk_rx = spk.receiver();
        let (eng_tx, eng_rx) = async_channel::bounded(64);
        let foreign = Arc::new(ForeignAudio::new(spk.clone(), Arc::clone(&stats)));
        let task = tokio::spawn(run_pcm_downlink(
            eng_rx,
            spk,
            Arc::clone(&foreign),
            Arc::clone(&stats),
        ));
        // Nothing foreign yet: a 20 ms frame of engine silence passes as it is.
        eng_tx.send(vec![0; STEP_SAMPLES]).await.unwrap();
        assert_eq!(spk_rx.recv().await.unwrap(), vec![0; STEP_SAMPLES]);

        // A decoded 60 ms Opus frame goes on as three 20 ms slots.
        let mut encoder = WaOpusEncoder::new_mlow_escape().unwrap();
        foreign.deliver(&encoder.encode(&tone(1_000.0, 0)).unwrap());
        for _ in 0..3 {
            assert_eq!(spk_rx.recv().await.unwrap().len(), STEP_SAMPLES);
        }
        // Silence is held back now; real engine sound still passes.
        eng_tx.send(vec![0; STEP_SAMPLES]).await.unwrap();
        eng_tx.send(vec![5; STEP_SAMPLES]).await.unwrap();
        eng_tx.close();
        task.await.unwrap();
        assert_eq!(spk_rx.try_recv().unwrap(), vec![5; STEP_SAMPLES]);
        assert!(spk_rx.is_empty());
        let s = stats.summary();
        assert_eq!((s.spk_from_engine, s.spk_gated), (3, 1));
        assert_eq!((s.foreign, s.foreign_ok), (1, 1));
        assert_eq!(s.spk_ms, 20 + 60 + 20);
    }

    /// The engine plays out 20 ms frames and they reach the speaker queue as
    /// 20 ms frames, each the moment it comes: nothing waits for two more to
    /// make 60 ms (which cost up to 40 ms).
    #[tokio::test]
    async fn pcm_downlink_passes_20_ms_frames_on_without_joining_them() {
        let stats = Arc::new(CallAudioStats::default());
        let spk = FreshQueue::new(SPK_QUEUE, SPK_TARGET);
        let spk_rx = spk.receiver();
        let (eng_tx, eng_rx) = async_channel::bounded(64);
        let foreign = Arc::new(ForeignAudio::new(spk.clone(), Arc::clone(&stats)));
        let task = tokio::spawn(run_pcm_downlink(eng_rx, spk, foreign, Arc::clone(&stats)));
        for i in 0..5 {
            eng_tx.send(vec![i; STEP_SAMPLES]).await.unwrap();
            // One frame in, one frame out, before the next one exists.
            assert_eq!(spk_rx.recv().await.unwrap(), vec![i; STEP_SAMPLES]);
        }
        eng_tx.close();
        task.await.unwrap();
        let s = stats.summary();
        assert_eq!((s.spk_queue_dropped, s.spk_ms), (0, 100));
        assert_eq!((s.spk_queue_mean_ms, s.spk_queue_max_ms), (0, 0));
    }

    /// The socket stalls for half a second while the peer talks on. Before,
    /// the queue kept the oldest 480 ms and threw the newest away, and the
    /// app got that old audio first. Now the oldest goes: what waits is the
    /// newest audio and no more than the queue holds.
    #[tokio::test]
    async fn a_stalled_socket_leaves_the_newest_audio_in_the_speaker_queue() {
        let stats = Arc::new(CallAudioStats::default());
        let spk = FreshQueue::new(SPK_QUEUE, SPK_TARGET);
        let spk_rx = spk.receiver();
        let (eng_tx, eng_rx) = async_channel::bounded(64);
        let foreign = Arc::new(ForeignAudio::new(spk.clone(), Arc::clone(&stats)));
        // 500 ms of playout while nobody reads the speaker queue.
        for i in 0..25 {
            eng_tx.send(vec![i; STEP_SAMPLES]).await.unwrap();
        }
        eng_tx.close();
        run_pcm_downlink(eng_rx, spk, foreign, Arc::clone(&stats)).await;
        let left: Vec<i16> = std::iter::from_fn(|| spk_rx.try_recv().ok())
            .map(|f| f[0])
            .collect();
        assert!(left.len() <= SPK_QUEUE, "{left:?}");
        assert_eq!(left.last(), Some(&24), "the newest frame is there");
        assert!(left.windows(2).all(|w| w[1] == w[0] + 1), "{left:?}");
        let s = stats.summary();
        assert_eq!(s.spk_queue_dropped as usize, 25 - left.len());
        // Every frame was counted as decoded audio; the depth was measured.
        assert_eq!(s.spk_ms, 500);
        assert_eq!(s.spk_queue_max_ms, 200);
        assert!(s.spk_queue_mean_ms > 40, "{s}");
    }

    #[test]
    fn a_fresh_queue_drops_the_oldest_and_falls_back_to_the_target() {
        let queue = FreshQueue::new(10, 2);
        for i in 0..10 {
            assert_eq!(queue.push(vec![i]), Ok(0));
        }
        assert_eq!(queue.len(), 10);
        // Full: the eleventh frame goes in, and all but the newest two
        // (this one included) go out, oldest first.
        assert_eq!(queue.push(vec![10]), Ok(9));
        let rx = queue.receiver();
        assert_eq!(rx.try_recv().unwrap(), vec![9]);
        assert_eq!(rx.try_recv().unwrap(), vec![10]);
        assert!(queue.is_empty());
        // A reader that keeps up never loses a frame.
        for i in 0..100 {
            assert_eq!(queue.push(vec![i]), Ok(0));
            assert_eq!(rx.try_recv().unwrap(), vec![i]);
        }
        assert_eq!(queue.push(vec![1]), Ok(0));
        assert_eq!(queue.clear(), 1);
        queue.close();
        assert_eq!(queue.push(vec![1]), Err(QueueClosed));
        assert!(rx.try_recv().is_err());
    }

    #[test]
    fn a_fresh_queue_of_microphone_frames_keeps_the_newest_one() {
        let queue = FreshQueue::new(MIC_QUEUE, MIC_TARGET);
        let dropped: usize = (0..9).map(|i| queue.push(vec![i]).unwrap()).sum();
        let rx = queue.receiver();
        let left: Vec<Frame> = std::iter::from_fn(|| rx.try_recv().ok()).collect();
        assert_eq!(left.last(), Some(&vec![8]));
        assert_eq!(dropped + left.len(), 9);
        assert!(left.len() <= MIC_QUEUE);
    }

    #[tokio::test]
    async fn the_pcm_uplink_drops_the_oldest_frames_when_the_engine_is_behind() {
        let stats = Arc::new(CallAudioStats::default());
        let (mic_tx, mic_rx) = async_channel::bounded(16);
        let engine = FreshQueue::new(MIC_QUEUE, MIC_TARGET);
        let engine_rx = engine.receiver();
        for i in 0..6 {
            mic_tx.try_send(vec![i; FRAME_SAMPLES]).unwrap();
        }
        mic_tx.close();
        run_pcm_uplink(mic_rx, engine, Arc::clone(&stats)).await;
        let left: Vec<i16> = std::iter::from_fn(|| engine_rx.try_recv().ok())
            .map(|f| f[0])
            .collect();
        assert_eq!(left.last(), Some(&5));
        let s = stats.summary();
        assert_eq!(s.mic_to_engine, 6);
        assert_eq!(s.mic_engine_dropped as usize, 6 - left.len());
        assert!(s.mic_engine_dropped > 0);
    }

    #[test]
    fn mlow_pcm_is_the_default_and_answers_with_the_mlow_signaling() {
        assert_eq!(CALL_AUDIO_MODE, CallAudioMode::MlowPcm);
        // The gateway encodes nothing itself: no libopus on the way out.
        assert!(CallAudioMode::MlowPcm.encoder().is_err());
        assert_eq!(CallAudioMode::MlowPcm.encoded_format(), None);
        // The same signaling as OpusInMlow, which the caller's phone takes;
        // not the standard-Opus answer it ignored.
        let pcm = CallAudioMode::MlowPcm.wire_format();
        let escape = CallAudioMode::OpusInMlow.wire_format();
        assert_eq!(pcm.codec, AudioCodec::Mlow);
        assert_eq!(pcm.rtp_profile, AudioRtpProfile::Mlow);
        assert_eq!(pcm.rtp_profile, escape.rtp_profile);
        assert_eq!(pcm.rtp_payload_type, escape.rtp_payload_type);
        assert_eq!(pcm.signaling_rate, escape.signaling_rate);
        assert_eq!(pcm.samples_per_frame, escape.samples_per_frame);
        assert_ne!(
            CallAudioMode::NativeOpus.wire_format().rtp_profile,
            AudioRtpProfile::Mlow
        );
    }

    /// What the peer gets in the default mode: the engine's MLOW encoder
    /// (the same one the engine runs) carries a 1 kHz tone, and an MLOW
    /// decoder plays it back as 1 kHz.
    #[test]
    fn the_engine_mlow_encoder_carries_the_microphone() {
        let mut encoder = whatsapp_rust::wacore::voip::MlowEncoder::new();
        let mut decoder = InboundDecoder::new();
        let mut pcm = Vec::new();
        for i in 0..10 {
            let frame: Vec<f32> = tone(1_000.0, i * FRAME_SAMPLES)
                .iter()
                .map(|&s| f32::from(s) / 32768.0)
                .collect();
            let packet = encoder.encode(&frame).unwrap();
            assert!(!packet.is_empty(), "packet {i}");
            pcm.extend(decoder.decode_mlow(&packet, false));
        }
        assert_eq!(pcm.len(), 10 * FRAME_SAMPLES);
        assert_tone(&pcm);
    }

    #[test]
    fn levels_tell_silence_from_sound() {
        let level = Level::default();
        assert_eq!(level.snapshot().rms_dbfs, None);
        level.add(&[0; 960]);
        let silent = level.snapshot();
        assert_eq!(silent.rms_dbfs, Some(-120.0));
        assert_eq!((silent.peak, silent.voiced_frames), (0, 0));
        level.add(&tone(1_000.0, 0));
        let loud = level.snapshot();
        assert_eq!(loud.peak, 12_000);
        assert_eq!(loud.voiced_frames, 1);
        // Half the samples a 12 000 sine (-11.7 dBFS), half silence: -14.7.
        let db = loud.rms_dbfs.unwrap();
        assert!((db + 14.7).abs() < 0.2, "{db}");
        // Full scale does not overflow.
        level.add(&[i16::MIN; 960]);
        assert_eq!(level.snapshot().peak, 32_768);
    }

    #[test]
    fn the_summary_line_has_every_counter() {
        let stats = CallAudioStats::default();
        stats.set_format(CallAudioMode::NativeOpus.label());
        stats.set_format("ignored");
        bump(&stats.mic_from_app);
        bump(&stats.mic_from_app);
        bump(&stats.mic_bad_size);
        bump(&stats.foreign_failed);
        bump(&stats.spk_to_app);
        stats.spk_app_samples.fetch_add(320, Ordering::Relaxed);
        bump(&stats.sockets);
        stats.spk_level.add(&[1_000; 960]);
        // 60 ms into an empty speaker queue: three slots, which found 0,
        // 20 and 40 ms waiting.
        let spk = FreshQueue::new(SPK_QUEUE, SPK_TARGET);
        assert!(to_speaker(&spk, vec![0; FRAME_SAMPLES], &stats));
        let line = stats.summary().to_string();
        assert_eq!(
            line,
            "format=native-opus pt120 16k/60ms | mic: app=2 bad=1 lag=0 engine=0 engine_drop=0 \
             enc_fail=0 rms=none peak=0 voiced=0 | spk: engine=0 opus=0 mlow=0 dec_fail=0 \
             foreign=0 foreign_ok=0 foreign_fail=1 gated=0 queue_drop=0 queue_mean=20ms \
             queue_max=40ms empty=0 pcm_ms=60 stale=0 app=1 app_ms=20 rms=-30.3dBFS peak=1000 \
             voiced=1 | sockets=1"
        );
    }

    #[test]
    fn the_speaker_counts_decoded_time_and_empty_packets() {
        let stats = CallAudioStats::default();
        let spk = FreshQueue::new(2, 1);
        assert!(to_speaker(&spk, vec![0; STEP_SAMPLES], &stats));
        assert!(to_speaker(&spk, Vec::new(), &stats));
        assert!(to_speaker(&spk, vec![0; STEP_SAMPLES], &stats));
        // Full: the oldest two go for this one, and it still counts as audio
        // that reached the queue.
        assert!(to_speaker(&spk, vec![7; STEP_SAMPLES], &stats));
        let summary = stats.summary();
        assert_eq!(
            (summary.spk_ms, summary.spk_empty, summary.spk_queue_dropped),
            (60, 1, 2)
        );
        assert_eq!(spk.receiver().try_recv().unwrap(), vec![7; STEP_SAMPLES]);
        // A frame that is not a multiple of 20 ms keeps every sample.
        let odd = FreshQueue::new(8, 8);
        assert!(to_speaker(&odd, vec![1; STEP_SAMPLES + 5], &stats));
        let rx = odd.receiver();
        assert_eq!(rx.try_recv().unwrap().len(), STEP_SAMPLES);
        assert_eq!(rx.try_recv().unwrap().len(), 5);
        // The call is over: the queue is closed.
        spk.close();
        assert!(!to_speaker(&spk, vec![0; STEP_SAMPLES], &stats));
    }

    #[test]
    fn peers_are_masked() {
        assert_eq!(
            mask_peer("905321234567@s.whatsapp.net"),
            "90…67@s.whatsapp.net"
        );
        assert_eq!(mask_peer("123456789012345:7@lid"), "12…45@lid");
        assert_eq!(mask_peer("905321234567"), "90…67");
        assert_eq!(mask_peer("12345@s.whatsapp.net"), "…@s.whatsapp.net");
        assert_eq!(mask_peer(""), "…");
    }
}
