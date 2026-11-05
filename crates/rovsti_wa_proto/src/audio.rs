//! Call audio framing: 960 mono i16 samples at 16 kHz (60 ms), little endian.

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;

pub const FRAME_SAMPLES: usize = 960;
pub const SAMPLE_RATE: u32 = 16_000;
pub const FRAME_BYTES: usize = FRAME_SAMPLES * 2;

#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum FrameError {
    #[error("audio frame has {0} samples; expected {FRAME_SAMPLES}")]
    BadSamples(usize),
    #[error("audio frame has {0} bytes; expected {FRAME_BYTES}")]
    BadBytes(usize),
    #[error("audio frame is not valid base64")]
    BadBase64,
}

pub fn encode_frame(samples: &[i16]) -> Result<Vec<u8>, FrameError> {
    if samples.len() != FRAME_SAMPLES {
        return Err(FrameError::BadSamples(samples.len()));
    }
    Ok(samples.iter().flat_map(|s| s.to_le_bytes()).collect())
}

pub fn decode_frame(bytes: &[u8]) -> Result<Vec<i16>, FrameError> {
    if bytes.len() != FRAME_BYTES {
        return Err(FrameError::BadBytes(bytes.len()));
    }
    let (pairs, _) = bytes.as_chunks::<2>();
    Ok(pairs.iter().map(|b| i16::from_le_bytes(*b)).collect())
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

/// Turns arbitrary-sized capture reads into whole frames.
#[derive(Clone, Debug, Default)]
pub struct Rechunker {
    pending: Vec<i16>,
}

impl Rechunker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Appends samples and returns every complete frame now available.
    pub fn push(&mut self, samples: &[i16]) -> Vec<Vec<i16>> {
        self.pending.extend_from_slice(samples);
        let whole = self.pending.len() / FRAME_SAMPLES * FRAME_SAMPLES;
        let mut frames = Vec::with_capacity(whole / FRAME_SAMPLES);
        let (chunks, _) = self.pending[..whole].as_chunks::<FRAME_SAMPLES>();
        frames.extend(chunks.iter().map(|c| c.to_vec()));
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
}
