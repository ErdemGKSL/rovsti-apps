use super::*;

const W: u32 = 640;
const H: u32 = 360;

fn text_box_pixels(bitmap: &Bitmap) -> Vec<[u8; 4]> {
    let (x, y, w, h) = probe_text_box(W, H);
    let raw = bitmap.as_raw();
    let mut out = Vec::new();
    for row in y..y + h {
        for col in x..x + w {
            let i = ((row * W + col) * 4) as usize;
            out.push([raw[i], raw[i + 1], raw[i + 2], raw[i + 3]]);
        }
    }
    out
}

/// Pixels that differ clearly from the panel's own colour behind the text (the text ink).
fn ink(pixels: &[[u8; 4]]) -> usize {
    pixels.iter().filter(|p| p[0] < 90 && p[1] < 90).count()
}

fn differing(a: &[[u8; 4]], b: &[[u8; 4]]) -> usize {
    a.iter().zip(b).filter(|(x, y)| x != y).count()
}

#[test]
fn png_has_the_requested_size() {
    let png = render_probe(PROBE_LINE, W, H).unwrap();
    let decoded = image::load_from_memory_with_format(&png, image::ImageFormat::Png).unwrap();
    assert_eq!((decoded.width(), decoded.height()), (W, H));
}

#[test]
fn turkish_glyphs_are_not_tofu() {
    let turkish = text_box_pixels(&render_probe_bitmap(PROBE_LINE, W, H).unwrap());
    let ascii =
        text_box_pixels(&render_probe_bitmap("Dogum gunun kutlu olsun, Ilkay!", W, H).unwrap());
    let ink_tr = ink(&turkish);
    let ink_ascii = ink(&ascii);
    assert!(ink_tr > 500, "the Turkish line has ink: {ink_tr}");
    // Same words, so the ink is similar, but the accents make the pixels differ.
    assert!(ink_tr > ink_ascii, "{ink_tr} vs {ink_ascii}");
    assert!(
        differing(&turkish, &ascii) > 60,
        "the dotted and breve letters are drawn, not replaced by their ASCII look: {} differ, ink {ink_tr} vs {ink_ascii}",
        differing(&turkish, &ascii)
    );

    // A code point Inter does not have shows the fallback box (or nothing); real Turkish letters must not
    // look like that.
    let letters = "ğüşİıçö";
    let missing = "\u{E000}\u{E001}\u{E002}\u{E003}\u{E004}\u{E005}\u{E006}";
    let real = text_box_pixels(&render_probe_bitmap(letters, W, H).unwrap());
    let none = text_box_pixels(&render_probe_bitmap(missing, W, H).unwrap());
    assert!(ink(&real) > 200, "the Turkish letters have ink");
    assert!(
        differing(&real, &none) > 300,
        "the Turkish letters differ from a missing-glyph render"
    );
    // The seven letters take about the same width as seven ASCII letters, not seven boxes of the fallback.
    let plain = text_box_pixels(&render_probe_bitmap("gusIico", W, H).unwrap());
    assert!(differing(&real, &plain) > 100);
}

#[test]
fn output_is_deterministic() {
    let a = render_probe(PROBE_LINE, W, H).unwrap();
    let b = render_probe(PROBE_LINE, W, H).unwrap();
    assert_eq!(a, b);
}

#[test]
fn oversized_request_is_refused() {
    assert!(matches!(
        render_probe(PROBE_LINE, 10, 10),
        Err(CardError::Size(10, 10))
    ));
    assert!(matches!(
        render_probe(PROBE_LINE, 5000, 100),
        Err(CardError::Size(5000, 100))
    ));
}

/// Writes `target/checks/cards/probe.png` for the visual review.
#[test]
fn writes_probe_png_for_review() {
    let png = render_probe(PROBE_LINE, 720, 405).unwrap();
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../target/checks/cards");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("probe.png"), png).unwrap();
}
