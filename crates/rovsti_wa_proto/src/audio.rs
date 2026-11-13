//! Call audio framing: mono i16 samples at 16 kHz, little endian.
//!
//! The microphone direction (app to gateway) is always one whole frame of 960
//! samples (60 ms): MLOW encodes 60 ms at a time. The speaker direction
//! (gateway to app) is a multiple of [`STEP_SAMPLES`] (20 ms) up to one whole
//! frame; which one is asked for by the app when it opens the socket, see
//! [`SpeakerFrames`].

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;

pub const FRAME_SAMPLES: usize = 960;
pub const SAMPLE_RATE: u32 = 16_000;
pub const FRAME_BYTES: usize = FRAME_SAMPLES * 2;
/// The engine's playout step: 20 ms. Speaker frames are a multiple of it.
pub const STEP_SAMPLES: usize = 320;
pub const STEP_BYTES: usize = STEP_SAMPLES * 2;
/// Query parameter of the audio socket that asks for a speaker frame length
/// in milliseconds, see [`SpeakerFrames`].
pub const SPEAKER_MS_PARAM: &str = "spk_ms";

/// Milliseconds of audio in `samples` samples.
pub const fn samples_ms(samples: usize) -> u64 {
    samples as u64 * 1_000 / SAMPLE_RATE as u64
}

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum FrameError {
    #[error("audio frame has {0} samples; expected {FRAME_SAMPLES}")]
    BadSamples(usize),
    #[error("audio frame has {0} bytes; expected {FRAME_BYTES}")]
    BadBytes(usize),
    #[error("audio frame is not valid base64")]
    BadBase64,
    #[error(
        "speaker frame has {0} samples; expected a multiple of {STEP_SAMPLES} up to {FRAME_SAMPLES}"
    )]
    BadSpeakerSamples(usize),
    #[error("speaker frame has {0} bytes; expected a multiple of {STEP_BYTES} up to {FRAME_BYTES}")]
    BadSpeakerBytes(usize),
}

/// The speaker frame length an app asks the gateway for.
///
/// Version safety, both ways: an app that does not ask (one from before
/// 20 ms frames) gets whole 60 ms frames from any gateway; a gateway that
/// does not know the parameter ignores it and sends whole frames, which a
/// new app plays as well, because its receiver takes every length
/// [`decode_speaker_frame`] accepts.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum SpeakerFrames {
    /// Whole 60 ms frames (960 samples), as every version sends by default.
    #[default]
    Whole,
    /// 20 ms frames (320 samples), as the engine plays them out.
    Step,
}

impl SpeakerFrames {
    /// Samples per frame on the wire.
    pub const fn samples(self) -> usize {
        match self {
            Self::Whole => FRAME_SAMPLES,
            Self::Step => STEP_SAMPLES,
        }
    }

    /// The query string that asks for it; `None` for the default.
    pub fn query(self) -> Option<String> {
        match self {
            Self::Whole => None,
            Self::Step => Some(format!("{SPEAKER_MS_PARAM}={}", samples_ms(STEP_SAMPLES))),
        }
    }

    /// Reads the request's query string. Anything but a clear ask for 20 ms
    /// (a missing parameter, another length, garbage) is the default.
    pub fn from_query(query: Option<&str>) -> Self {
        let asked = query
            .unwrap_or_default()
            .split('&')
            .filter_map(|pair| pair.split_once('='))
            .find(|(name, _)| *name == SPEAKER_MS_PARAM)
            .map(|(_, value)| value);
        if asked == Some("20") {
            Self::Step
        } else {
            Self::Whole
        }
    }
}

pub fn encode_frame(samples: &[i16]) -> Result<Vec<u8>, FrameError> {
    if samples.len() != FRAME_SAMPLES {
        return Err(FrameError::BadSamples(samples.len()));
    }
    Ok(to_bytes(samples))
}

pub fn decode_frame(bytes: &[u8]) -> Result<Vec<i16>, FrameError> {
    if bytes.len() != FRAME_BYTES {
        return Err(FrameError::BadBytes(bytes.len()));
    }
    Ok(from_bytes(bytes))
}

/// True for a speaker frame length: 320, 640 or 960 samples.
pub const fn is_speaker_len(samples: usize) -> bool {
    samples != 0 && samples <= FRAME_SAMPLES && samples.is_multiple_of(STEP_SAMPLES)
}

/// Encodes one speaker frame: a multiple of 20 ms up to 60 ms.
pub fn encode_speaker_frame(samples: &[i16]) -> Result<Vec<u8>, FrameError> {
    if !is_speaker_len(samples.len()) {
        return Err(FrameError::BadSpeakerSamples(samples.len()));
    }
    Ok(to_bytes(samples))
}

/// Decodes one speaker frame of any length [`encode_speaker_frame`] makes,
/// so the receiver plays what either version of the gateway sends.
pub fn decode_speaker_frame(bytes: &[u8]) -> Result<Vec<i16>, FrameError> {
    if !bytes.len().is_multiple_of(2) || !is_speaker_len(bytes.len() / 2) {
        return Err(FrameError::BadSpeakerBytes(bytes.len()));
    }
    Ok(from_bytes(bytes))
}

fn to_bytes(samples: &[i16]) -> Vec<u8> {
    samples.iter().flat_map(|s| s.to_le_bytes()).collect()
}

fn from_bytes(bytes: &[u8]) -> Vec<i16> {
    let (pairs, _) = bytes.as_chunks::<2>();
    pairs.iter().map(|b| i16::from_le_bytes(*b)).collect()
}

/// Frames cross the JNI seam (strings only) as base64.
pub fn frame_to_base64(samples: &[i16]) -> Result<String, FrameError> {
    encode_frame(samples).map(|b| STANDARD.encode(b))
}

pub fn frame_from_base64(text: &str) -> Result<Vec<i16>, FrameError> {
    let bytes = STANDARD
        .decode(text.trim())
        .map_err(|_| FrameError::BadBase64)?;
    decode_frame(&bytes)
}

/// Turns arbitrary-sized reads into frames of one length.
#[derive(Clone, Debug)]
pub struct Rechunker {
    pending: Vec<i16>,
    frame: usize,
}

impl Default for Rechunker {
    fn default() -> Self {
        Self::new()
    }
}

impl Rechunker {
    /// Whole 60 ms frames.
    pub fn new() -> Self {
        Self::with_frame(FRAME_SAMPLES)
    }

    /// Frames of `samples` samples (at least one).
    pub fn with_frame(samples: usize) -> Self {
        Self {
            pending: Vec::new(),
            frame: samples.max(1),
        }
    }

    /// Appends samples and returns every complete frame now available.
    pub fn push(&mut self, samples: &[i16]) -> Vec<Vec<i16>> {
        // The common case: nothing pending and exactly one frame.
        if self.pending.is_empty() && samples.len() == self.frame {
            return vec![samples.to_vec()];
        }
        self.pending.extend_from_slice(samples);
        let whole = self.pending.len() / self.frame * self.frame;
        let frames = self.pending[..whole]
            .chunks_exact(self.frame)
            .map(<[i16]>::to_vec)
            .collect();
        self.pending.drain(..whole);
        frames
    }

    /// Samples waiting for the next frame to fill.
    pub fn pending(&self) -> usize {
        self.pending.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ramp(n: usize, start: i32) -> Vec<i16> {
        (0..n).map(|i| (start + i as i32) as i16).collect()
    }

    #[test]
    fn constants_agree() {
        assert_eq!(FRAME_BYTES, 1920);
        assert_eq!(FRAME_SAMPLES as u32 * 1000 / SAMPLE_RATE, 60);
    }

    #[test]
    fn round_trip() {
        let mut frame = ramp(FRAME_SAMPLES, -400);
        frame[0] = i16::MIN;
        frame[1] = i16::MAX;
        let bytes = encode_frame(&frame).unwrap();
        assert_eq!(bytes.len(), FRAME_BYTES);
        assert_eq!(&bytes[..4], &[0x00, 0x80, 0xff, 0x7f]);
        assert_eq!(decode_frame(&bytes).unwrap(), frame);
        let text = frame_to_base64(&frame).unwrap();
        assert_eq!(frame_from_base64(&text).unwrap(), frame);
        assert_eq!(frame_from_base64(&format!(" {text}\n")).unwrap(), frame);
    }

    #[test]
    fn wrong_sizes_are_rejected() {
        assert_eq!(encode_frame(&[0; 959]), Err(FrameError::BadSamples(959)));
        assert_eq!(encode_frame(&[0; 961]), Err(FrameError::BadSamples(961)));
        assert_eq!(decode_frame(&[0; 1919]), Err(FrameError::BadBytes(1919)));
        assert_eq!(decode_frame(&[]), Err(FrameError::BadBytes(0)));
        assert_eq!(frame_from_base64("!!!"), Err(FrameError::BadBase64));
        assert_eq!(frame_from_base64("AAAA"), Err(FrameError::BadBytes(3)));
    }

    #[test]
    fn rechunker_handles_odd_pushes() {
        let all = ramp(3000, 0);
        let mut r = Rechunker::new();
        let mut frames = Vec::new();
        for chunk in all.chunks(333) {
            frames.extend(r.push(chunk));
        }
        assert_eq!(frames.len(), 3);
        assert_eq!(r.pending(), 3000 - 3 * FRAME_SAMPLES);
        assert!(frames.iter().all(|f| f.len() == FRAME_SAMPLES));
        let joined: Vec<i16> = frames.concat();
        assert_eq!(joined, all[..3 * FRAME_SAMPLES]);
    }

    #[test]
    fn rechunker_big_push_and_empty() {
        let mut r = Rechunker::new();
        assert!(r.push(&[]).is_empty());
        assert_eq!(r.push(&ramp(FRAME_SAMPLES * 2 + 5, 0)).len(), 2);
        assert_eq!(r.pending(), 5);
    }

    #[test]
    fn rechunker_joins_20_ms_decoder_frames() {
        // The WhatsApp decoder emits 320-sample (20 ms) frames; three make one.
        let mut r = Rechunker::new();
        assert!(r.push(&ramp(320, 0)).is_empty());
        assert!(r.push(&ramp(320, 320)).is_empty());
        let frames = r.push(&ramp(320, 640));
        assert_eq!(frames, vec![ramp(FRAME_SAMPLES, 0)]);
        assert_eq!(r.pending(), 0);
    }

    #[test]
    fn a_20_ms_speaker_frame_round_trips() {
        let frame = ramp(STEP_SAMPLES, -160);
        let bytes = encode_speaker_frame(&frame).unwrap();
        assert_eq!(bytes.len(), STEP_BYTES);
        assert_eq!(samples_ms(frame.len()), 20);
        assert_eq!(decode_speaker_frame(&bytes).unwrap(), frame);
    }

    /// Old and new versions meet: a receiver takes 20, 40 and 60 ms, so the
    /// whole frames of an old gateway play as well as the 20 ms ones.
    #[test]
    fn the_speaker_receiver_takes_every_multiple_of_20_ms_up_to_a_whole_frame() {
        for samples in [320, 640, 960] {
            let frame = ramp(samples, 7);
            let bytes = encode_speaker_frame(&frame).unwrap();
            assert_eq!(decode_speaker_frame(&bytes).unwrap(), frame, "{samples}");
        }
        // What an old gateway sends is exactly what the old decoder took.
        let whole = ramp(FRAME_SAMPLES, 0);
        assert_eq!(
            decode_speaker_frame(&encode_frame(&whole).unwrap()).unwrap(),
            whole
        );
        for bad in [0, 2, 638, 641, 1280 + 1, 1922, 2560] {
            assert_eq!(
                decode_speaker_frame(&vec![0; bad]),
                Err(FrameError::BadSpeakerBytes(bad)),
                "{bad}"
            );
        }
        assert_eq!(
            encode_speaker_frame(&[0; 321]),
            Err(FrameError::BadSpeakerSamples(321))
        );
        assert_eq!(
            encode_speaker_frame(&[]),
            Err(FrameError::BadSpeakerSamples(0))
        );
    }

    /// The microphone direction did not change: exactly 60 ms.
    #[test]
    fn the_microphone_frame_is_still_exactly_60_ms() {
        assert_eq!(encode_frame(&[0; 320]), Err(FrameError::BadSamples(320)));
        assert_eq!(decode_frame(&[0; 640]), Err(FrameError::BadBytes(640)));
    }

    #[test]
    fn the_speaker_frame_length_is_asked_for_in_the_query() {
        assert_eq!(SpeakerFrames::Whole.query(), None);
        assert_eq!(SpeakerFrames::Step.query().as_deref(), Some("spk_ms=20"));
        assert_eq!(SpeakerFrames::Step.samples(), 320);
        assert_eq!(SpeakerFrames::Whole.samples(), 960);
        // An old app asks for nothing and gets whole frames.
        assert_eq!(SpeakerFrames::from_query(None), SpeakerFrames::Whole);
        assert_eq!(SpeakerFrames::from_query(Some("")), SpeakerFrames::Whole);
        assert_eq!(
            SpeakerFrames::from_query(Some("spk_ms=20")),
            SpeakerFrames::Step
        );
        assert_eq!(
            SpeakerFrames::from_query(Some("a=1&spk_ms=20&b")),
            SpeakerFrames::Step
        );
        for other in ["spk_ms=60", "spk_ms=10", "spk_ms=", "spk_ms", "xspk_ms=20"] {
            assert_eq!(
                SpeakerFrames::from_query(Some(other)),
                SpeakerFrames::Whole,
                "{other}"
            );
        }
    }

    #[test]
    fn rechunker_cuts_to_20_ms_frames() {
        let mut r = Rechunker::with_frame(STEP_SAMPLES);
        // A whole decoded Opus frame becomes three steps.
        let frames = r.push(&ramp(FRAME_SAMPLES, 0));
        assert_eq!(frames.len(), 3);
        assert_eq!(frames[2], ramp(STEP_SAMPLES, 640));
        // One step passes straight through.
        assert_eq!(r.push(&ramp(STEP_SAMPLES, 5)), vec![ramp(STEP_SAMPLES, 5)]);
        // Odd lengths wait for the rest.
        assert!(r.push(&ramp(100, 0)).is_empty());
        assert_eq!(r.push(&ramp(220, 100)), vec![ramp(STEP_SAMPLES, 0)]);
        assert_eq!(r.pending(), 0);
    }
}
