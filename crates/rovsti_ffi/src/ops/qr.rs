//! `qr.encode` and `qr.decode`: QR codes without ZXing or ML Kit.
//!
//! Wire format of a matrix (documented in `OPS.md`): `side` modules per row and column, `modules` a base64 string
//! (standard alphabet, padded) of a bitset that holds `side * side` bits in row-major order, the first module of the
//! first row in the most significant bit of the first byte; a set bit is a dark module; the trailing bits of the last
//! byte are zero. The quiet zone is not part of the matrix: the renderer adds four modules on every side.
//!
//! `qr.decode` takes an 8-bit luma image (`w * h` bytes, row-major, base64) and answers every code it reads, in
//! reading order, without repeats. It is the same decoder the contact scanner uses (`contact_scan::decode_luma`,
//! which wraps `rqrr`), so camera frames and picked pictures go through one path.

use super::registry::{Case, OpSpec};
use crate::envelope::FfiError;
use crate::op;
use base64::{Engine, engine::general_purpose::STANDARD};
use qrcode::{Color, EcLevel, QrCode};
use rovsti_core::contact_scan;
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!("qr.encode", EncodeReq, EncodeAns, encode, encode_cases),
    op!("qr.decode", DecodeReq, DecodeAns, decode, decode_cases),
];

/// Longest luma image `qr.decode` accepts (the scanner downsizes to 1600 px a side).
const MAX_PIXELS: usize = 1600 * 1600;

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Ecc {
    L,
    #[default]
    M,
    Q,
    H,
}

#[derive(Deserialize)]
struct EncodeReq {
    text: String,
    #[serde(default)]
    ecc: Ecc,
}

#[derive(Debug, Serialize)]
struct EncodeAns {
    side: usize,
    modules: String,
}

fn encode(req: EncodeReq) -> Result<EncodeAns, FfiError> {
    if req.text.is_empty() {
        return Err(FfiError::invalid("there is nothing to encode"));
    }
    let level = match req.ecc {
        Ecc::L => EcLevel::L,
        Ecc::M => EcLevel::M,
        Ecc::Q => EcLevel::Q,
        Ecc::H => EcLevel::H,
    };
    let code = QrCode::with_error_correction_level(req.text.as_bytes(), level)
        .map_err(|e| FfiError::invalid(format!("this text does not fit a QR code: {e}")))?;
    let side = code.width();
    let colors = code.to_colors();
    let mut bytes = vec![0u8; (side * side).div_ceil(8)];
    for (i, color) in colors.iter().enumerate() {
        if *color == Color::Dark {
            bytes[i / 8] |= 0x80 >> (i % 8);
        }
    }
    Ok(EncodeAns {
        side,
        modules: STANDARD.encode(bytes),
    })
}

fn encode_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "https://example.org/rovsti", "ecc": "m"})),
        Case::named("high", json!({"text": "Ayşe", "ecc": "h"})),
        Case::named("empty", json!({"text": ""})),
        Case::named("too_long", json!({"text": "x".repeat(8000), "ecc": "h"})),
    ]
}

#[derive(Deserialize)]
struct DecodeReq {
    w: u32,
    h: u32,
    luma: String,
}

#[derive(Debug, Serialize)]
struct DecodeAns {
    texts: Vec<String>,
}

fn decode(req: DecodeReq) -> Result<DecodeAns, FfiError> {
    let pixels = (req.w as usize).saturating_mul(req.h as usize);
    if pixels == 0 || pixels > MAX_PIXELS {
        return Err(FfiError::invalid(format!(
            "the image must have 1 to {MAX_PIXELS} pixels, not {}x{}",
            req.w, req.h
        )));
    }
    let luma = STANDARD
        .decode(req.luma.as_bytes())
        .map_err(|e| FfiError::new(crate::envelope::ErrorKind::Parse, format!("luma: {e}")))?;
    if luma.len() != pixels {
        return Err(FfiError::invalid(format!(
            "luma holds {} bytes, {}x{} needs {pixels}",
            luma.len(),
            req.w,
            req.h
        )));
    }
    Ok(DecodeAns {
        texts: contact_scan::decode_luma(req.w, req.h, &luma),
    })
}

/// Renders a matrix of `encode` as luma: `px` pixels per module, a four-module quiet zone, dark 0, light 255.
fn render_luma(side: usize, modules: &str, px: usize) -> (u32, u32, Vec<u8>) {
    let bits = STANDARD.decode(modules).expect("base64");
    let dim = (side + 8) * px;
    let mut luma = vec![255u8; dim * dim];
    for y in 0..side {
        for x in 0..side {
            let i = y * side + x;
            if bits[i / 8] & (0x80 >> (i % 8)) != 0 {
                for dy in 0..px {
                    for dx in 0..px {
                        luma[((y + 4) * px + dy) * dim + (x + 4) * px + dx] = 0;
                    }
                }
            }
        }
    }
    (dim as u32, dim as u32, luma)
}

fn decode_cases() -> Vec<Case> {
    let encoded = encode(EncodeReq {
        text: "hi".to_owned(),
        ecc: Ecc::M,
    })
    .expect("encodes");
    let (w, h, luma) = render_luma(encoded.side, &encoded.modules, 4);
    vec![
        Case::new(json!({"w": w, "h": h, "luma": STANDARD.encode(&luma)})),
        Case::named(
            "blank",
            json!({"w": 8, "h": 8, "luma": STANDARD.encode([255u8; 64])}),
        ),
        Case::named(
            "bad_size",
            json!({"w": 8, "h": 8, "luma": STANDARD.encode([255u8; 10])}),
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::ErrorKind;

    fn round_trip(text: &str, ecc: Ecc) -> Vec<String> {
        let m = encode(EncodeReq {
            text: text.to_owned(),
            ecc,
        })
        .expect("encodes");
        let (w, h, luma) = render_luma(m.side, &m.modules, 4);
        decode(DecodeReq {
            w,
            h,
            luma: STANDARD.encode(luma),
        })
        .expect("decodes")
        .texts
    }

    #[test]
    fn encode_then_decode_returns_the_text() {
        for (text, ecc) in [
            ("hi", Ecc::M),
            ("https://example.org/rovsti?x=1", Ecc::L),
            ("Ayşe Yılmaz +90 532 123 45 67", Ecc::Q),
            (
                "BEGIN:VCARD\nVERSION:3.0\nFN:Ada\nTEL:+905551112233\nEND:VCARD\n",
                Ecc::H,
            ),
        ] {
            assert_eq!(round_trip(text, ecc), vec![text.to_owned()], "{text}");
        }
    }

    #[test]
    fn the_matrix_is_a_packed_msb_first_bitset() {
        let m = encode(EncodeReq {
            text: "hi".to_owned(),
            ecc: Ecc::M,
        })
        .expect("encodes");
        assert_eq!(m.side, 21);
        let bits = STANDARD.decode(&m.modules).expect("base64");
        assert_eq!(bits.len(), (21 * 21usize).div_ceil(8));
        // The top-left finder pattern: seven dark modules in the first row, MSB first.
        assert_eq!(bits[0], 0b1111_1110);
        // Padding bits of the last byte are zero.
        assert_eq!(bits[bits.len() - 1] & 0b0000_0111, 0);
    }

    #[test]
    fn bad_input_has_a_kind() {
        let empty = encode(EncodeReq {
            text: String::new(),
            ecc: Ecc::M,
        })
        .expect_err("empty");
        assert_eq!(empty.kind, ErrorKind::Invalid);
        let short = decode(DecodeReq {
            w: 4,
            h: 4,
            luma: STANDARD.encode([0u8; 3]),
        })
        .expect_err("short");
        assert_eq!(short.kind, ErrorKind::Invalid);
        let junk = decode(DecodeReq {
            w: 4,
            h: 4,
            luma: "***".to_owned(),
        })
        .expect_err("junk");
        assert_eq!(junk.kind, ErrorKind::Parse);
    }
}
