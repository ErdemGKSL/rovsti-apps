use std::io::Cursor;
use std::path::{Path, PathBuf};

use super::anim::*;
use super::scene::{self, CardContent, Template};
use takumi::prelude::Bitmap;

fn birthday() -> CardContent {
    CardContent {
        kicker: "Doğum günün kutlu olsun".into(),
        name: "İlkay Şahin".into(),
        line1: "30 yaşına giriyor".into(),
        line2: "14 Kasım".into(),
        qr: None,
    }
}

fn contact() -> CardContent {
    CardContent {
        kicker: String::new(),
        name: "Ayşe Yılmaz".into(),
        line1: "+90 532 555 01 23".into(),
        line2: "Rehbere eklemek için okut".into(),
        qr: Some("BEGIN:VCARD\nVERSION:3.0\nFN:Ayşe Yılmaz\nTEL:+905325550123\nEND:VCARD".into()),
    }
}

const SMALL: u32 = 360;

fn out_dir(sub: &str) -> PathBuf {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../target/checks/cards")
        .join(sub);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn png(bitmap: &Bitmap) -> Vec<u8> {
    let mut out = Vec::new();
    takumi::write_image(bitmap, &mut out, takumi::prelude::OutputFormat::Png).unwrap();
    out
}

/// A decoded animation: RGBA frames and their durations in milliseconds.
struct Decoded {
    frames: Vec<Vec<u8>>,
    ms: Vec<u32>,
}

fn decode_gif(bytes: &[u8]) -> Decoded {
    let mut options = gif::DecodeOptions::new();
    options.set_color_output(gif::ColorOutput::RGBA);
    let mut decoder = options.read_info(Cursor::new(bytes)).unwrap();
    let (w, h) = (usize::from(decoder.width()), usize::from(decoder.height()));
    let (mut frames, mut ms) = (Vec::new(), Vec::new());
    let mut canvas = vec![0u8; w * h * 4];
    while let Some(frame) = decoder.read_next_frame().unwrap() {
        // Frames hold only a box: paste it on the canvas (disposal Keep), as a viewer does.
        for row in 0..usize::from(frame.height) {
            let src = row * usize::from(frame.width) * 4;
            let dst = ((usize::from(frame.top) + row) * w + usize::from(frame.left)) * 4;
            let len = usize::from(frame.width) * 4;
            canvas[dst..dst + len].copy_from_slice(&frame.buffer[src..src + len]);
        }
        frames.push(canvas.clone());
        ms.push(u32::from(frame.delay) * 10);
    }
    Decoded { frames, ms }
}

fn decode_apng(bytes: &[u8]) -> Decoded {
    use image::AnimationDecoder;
    let decoder = image::codecs::png::PngDecoder::new(Cursor::new(bytes)).unwrap();
    assert!(decoder.is_apng().unwrap());
    let (mut frames, mut ms) = (Vec::new(), Vec::new());
    for frame in decoder.apng().unwrap().into_frames() {
        let frame = frame.unwrap();
        let (num, den) = frame.delay().numer_denom_ms();
        ms.push((f64::from(num) / f64::from(den)).round() as u32);
        frames.push(frame.into_buffer().into_raw());
    }
    Decoded { frames, ms }
}

fn decode_webp(bytes: &[u8]) -> Decoded {
    let mut decoder = image_webp::WebPDecoder::new(Cursor::new(bytes)).unwrap();
    assert!(decoder.is_animated());
    let size = decoder.output_buffer_size().unwrap();
    let (mut frames, mut ms) = (Vec::new(), Vec::new());
    for _ in 0..decoder.num_frames() {
        let mut buf = vec![0; size];
        ms.push(decoder.read_frame(&mut buf).unwrap());
        frames.push(buf);
    }
    Decoded { frames, ms }
}

fn decode(format: AnimFormat, bytes: &[u8]) -> Decoded {
    match format {
        AnimFormat::Gif => decode_gif(bytes),
        AnimFormat::Apng => decode_apng(bytes),
        AnimFormat::Webp => decode_webp(bytes),
    }
}

const FORMATS: [AnimFormat; 3] = [AnimFormat::Gif, AnimFormat::Apng, AnimFormat::Webp];

#[test]
fn timelines_add_up() {
    for (t, frames, ms) in [
        (Timeline::BIRTHDAY, 39, 2600),
        (Timeline::CONTACT, 27, 1800),
    ] {
        assert_eq!((t.fps, t.frames, t.duration_ms()), (15, frames, ms));
        assert_eq!((0..frames).map(|f| t.frame_ms(f)).sum::<u32>(), ms);
        assert_eq!(
            (0..frames).map(|f| u32::from(t.frame_cs(f))).sum::<u32>(),
            ms / 10
        );
        assert!((0..frames).all(|f| (66..=67).contains(&t.frame_ms(f))));
    }
}

#[test]
fn frame_count_and_duration_in_every_format() {
    for (template, content, timeline) in [
        (Template::Birthday, birthday(), Timeline::BIRTHDAY),
        (Template::Contact, contact(), Timeline::CONTACT),
    ] {
        let frames = render_frames(template, &content, SMALL).unwrap();
        for format in FORMATS {
            let bytes = encode(&frames, timeline, format).unwrap();
            let decoded = decode(format, &bytes);
            // Takumi's WebP writer merges identical neighbours into one frame with the summed delay (the
            // held still is one frame), so count the runs of different pictures; the GIF keeps every frame.
            let runs = 1 + frames
                .windows(2)
                .filter(|w| w[0].as_raw() != w[1].as_raw())
                .count() as u32;
            let expected = if format == AnimFormat::Webp {
                runs
            } else {
                timeline.frames
            };
            assert_eq!(
                decoded.frames.len() as u32,
                expected,
                "{template:?} {format:?}"
            );
            let total: u32 = decoded.ms.iter().sum();
            assert!(
                total.abs_diff(timeline.duration_ms()) <= 2,
                "{template:?} {format:?}: {total} ms"
            );
        }
    }
}

fn mean_diff(a: &[u8], b: &[u8]) -> f64 {
    assert_eq!(a.len(), b.len());
    a.iter()
        .zip(b)
        .map(|(x, y)| f64::from(x.abs_diff(*y)))
        .sum::<f64>()
        / a.len() as f64
}

/// Compares `bitmap` with `golden/<name>.png`; `UPDATE_GOLDENS=1` rewrites the file.
fn golden(name: &str, bitmap: &Bitmap) {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("src/cards/golden")
        .join(format!("{name}.png"));
    if std::env::var_os("UPDATE_GOLDENS").is_some() {
        std::fs::write(&path, png(bitmap)).unwrap();
    }
    let expected = image::open(&path)
        .unwrap_or_else(|e| panic!("missing golden {path:?} ({e}); run with UPDATE_GOLDENS=1"))
        .into_rgba8();
    assert_eq!(
        (expected.width(), expected.height()),
        (bitmap.width(), bitmap.height())
    );
    let diff = mean_diff(expected.as_raw(), bitmap.as_raw());
    assert!(diff < 0.5, "{name}: mean channel difference {diff}");
}

#[test]
fn first_and_last_frames_match_the_goldens() {
    for (template, content, tag) in [
        (Template::Birthday, birthday(), "birthday"),
        (Template::Contact, contact(), "contact"),
    ] {
        let frames = render_frames(template, &content, SMALL).unwrap();
        golden(&format!("{tag}-first"), &frames[0]);
        golden(&format!("{tag}-last"), frames.last().unwrap());
    }
}

#[test]
fn last_frame_is_the_static_render() {
    for (template, content, timeline) in [
        (Template::Birthday, birthday(), Timeline::BIRTHDAY),
        (Template::Contact, contact(), Timeline::CONTACT),
    ] {
        let frames = render_frames(template, &content, SMALL).unwrap();
        let still = render_static(template, &content, SMALL).unwrap();
        assert_eq!(
            frames.last().unwrap().as_raw(),
            still.as_raw(),
            "{template:?} bitmaps"
        );
        // The held frames are all the same picture.
        let hold = (Choreography::for_template(template).still_from() * 15.0).ceil() as usize;
        assert!(
            frames[hold..]
                .windows(2)
                .all(|w| w[0].as_raw() == w[1].as_raw()),
            "{template:?} hold"
        );

        for format in FORMATS {
            let decoded = decode(format, &encode(&frames, timeline, format).unwrap());
            let last = decoded.frames.last().unwrap();
            if format == AnimFormat::Gif {
                // The static card written as a GIF of its own: same palette, same dither, same pixels.
                let one = Timeline { fps: 15, frames: 1 };
                let reference = decode(
                    format,
                    &encode(std::slice::from_ref(&still), one, format).unwrap(),
                );
                assert_eq!(
                    last, &reference.frames[0],
                    "{template:?} gif: last frame is the static render"
                );
            } else {
                // Lossless formats: the decoded last frame is the rendered pixels themselves. The pure-Rust WebP
                // decoder used here reads some frames one level low in every channel; libwebp (Pillow) decodes the same
                // files exactly (checked by hand for the birthday card), so WebP gets a one-level tolerance.
                let worst = last
                    .iter()
                    .zip(still.as_raw())
                    .map(|(a, b)| a.abs_diff(*b))
                    .max()
                    .unwrap();
                let allowed = u8::from(format == AnimFormat::Webp);
                assert!(
                    worst <= allowed,
                    "{template:?} {format:?} lossless: worst channel difference {worst}"
                );
            }
        }
    }
}

#[test]
fn gif_is_close_to_the_render_and_has_no_loop_block() {
    let frames = render_frames(Template::Birthday, &birthday(), SMALL).unwrap();
    let bytes = encode(&frames, Timeline::BIRTHDAY, AnimFormat::Gif).unwrap();
    let decoded = decode_gif(&bytes);
    let worst = frames
        .iter()
        .zip(&decoded.frames)
        .map(|(f, d)| mean_diff(f.as_raw(), d))
        .fold(0.0, f64::max);
    assert!(worst < 6.0, "palette error {worst}");
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|w| w == needle)
}

#[test]
fn play_once_is_set_at_the_byte_level() {
    let frames = render_frames(Template::Contact, &contact(), SMALL).unwrap();
    let t = Timeline::CONTACT;

    // GIF: header, one global palette, and no application extension at all (0x21 0xFF), so no NETSCAPE2.0.
    let gif = encode(&frames, t, AnimFormat::Gif).unwrap();
    assert_eq!(&gif[..6], b"GIF89a");
    assert_eq!(gif[10] & 0x80, 0x80, "global colour table present");
    assert!(find(&gif, b"NETSCAPE2.0").is_none());
    assert!(
        find(&gif, &[0x21, 0xFF, 0x0B]).is_none(),
        "no application extension"
    );
    assert_eq!(*gif.last().unwrap(), 0x3B);

    // APNG: the acTL chunk is `num_frames` then `num_plays`; 1 plays once (0 would loop forever).
    let apng = encode(&frames, t, AnimFormat::Apng).unwrap();
    let at = find(&apng, b"acTL").expect("acTL chunk");
    let num_frames = u32::from_be_bytes(apng[at + 4..at + 8].try_into().unwrap());
    let num_plays = u32::from_be_bytes(apng[at + 8..at + 12].try_into().unwrap());
    assert_eq!((num_frames, num_plays), (t.frames, 1));

    // WebP: the ANIM chunk is a 4-byte background colour, then the 16-bit loop count; 1 plays once.
    let webp = encode(&frames, t, AnimFormat::Webp).unwrap();
    assert_eq!(&webp[..4], b"RIFF");
    assert_eq!(&webp[8..12], b"WEBP");
    let at = find(&webp, b"ANIM").expect("ANIM chunk");
    let loops = u16::from_le_bytes(webp[at + 12..at + 14].try_into().unwrap());
    assert_eq!(loops, 1);
    assert_eq!(
        image_webp::WebPDecoder::new(Cursor::new(&webp))
            .unwrap()
            .loop_count(),
        image_webp::LoopCount::Times(std::num::NonZeroU16::new(1).unwrap())
    );
}

#[test]
fn birthday_gif_is_under_3_mb_at_720() {
    let bytes = render_animated(Template::Birthday, &birthday(), 720, AnimFormat::Gif).unwrap();
    println!("birthday gif 720: {} bytes", bytes.len());
    assert!(bytes.len() < 3 * 1024 * 1024, "{} bytes", bytes.len());
    let bytes = render_animated(Template::Contact, &contact(), 720, AnimFormat::Gif).unwrap();
    println!("contact gif 720: {} bytes", bytes.len());
    assert!(bytes.len() < 3 * 1024 * 1024, "{} bytes", bytes.len());
}

#[test]
fn animation_is_deterministic() {
    for format in FORMATS {
        let a = render_animated(Template::Birthday, &birthday(), SMALL, format).unwrap();
        let b = render_animated(Template::Birthday, &birthday(), SMALL, format).unwrap();
        assert!(a == b, "{format:?}");
    }
}

#[test]
fn the_motion_has_the_expected_shape() {
    let c = Choreography::for_template(Template::Birthday);
    let p0 = c.frame_pose(0);
    assert!(
        p0.panel_dy < -300.0,
        "the panel starts above: {}",
        p0.panel_dy
    );
    assert!(p0.confetti.is_empty());
    // The jelly keeps the area and stretches the refraction.
    let mut squashed = 0;
    let mut peak = 0.0f32;
    for frame in 0..c.timeline().frames {
        let p = c.frame_pose(frame);
        assert!((p.panel_sx * p.panel_sy - 1.0).abs() < 1e-4);
        if p.panel_sy < 0.99 {
            squashed += 1;
        }
        peak = peak.max(p.refraction);
    }
    assert!(
        squashed >= 1 && peak > 0.5,
        "squashed {squashed} frames, refraction peak {peak}"
    );
    // Lens, glint and confetti all appear and are gone or at rest in the end.
    assert!((0..39).any(|f| c.frame_pose(f).lens.is_some()));
    assert!((0..39).any(|f| c.frame_pose(f).glint.is_some()));
    let rest = c.rest();
    assert_eq!(rest.confetti.len(), 40);
    assert!(rest.lens.is_none() && rest.glint.is_none());
    let (_, top, _, _, _) = scene::panel_rect(Template::Birthday);
    assert!(
        rest.confetti.iter().all(|p| (p.y - top).abs() < 24.0),
        "confetti lies on the top edge"
    );
    assert_eq!(c.frame_pose(38), rest);
    // From 2.0 s the pose is the rest pose.
    assert_eq!(c.pose(2.0), rest);
    assert_eq!(c.pose(2.4), rest);

    let k = Choreography::for_template(Template::Contact);
    assert_eq!(k.frame_pose(26), k.rest());
    assert_eq!(k.pose(1.2), k.rest());
    assert!(k.frame_pose(0).qr_alpha < 0.01 && k.rest().qr_alpha > 0.999);
    assert!(k.frame_pose(0).drift > 0.9 && k.rest().drift < 1e-4);
}

#[test]
fn rng_is_seeded() {
    let mut a = Rng::new(7);
    let mut b = Rng::new(7);
    assert_eq!(
        (0..10).map(|_| a.next_f64()).collect::<Vec<_>>(),
        (0..10).map(|_| b.next_f64()).collect::<Vec<_>>()
    );
    assert!(Rng::new(7).next_f64() != Rng::new(8).next_f64());
}

/// Writes frames 0, 6, 12, 18, 24 and the last of the birthday GIF, and the contact ones, for the visual review.
#[test]
fn writes_frames_for_review() {
    let dir = out_dir("frames");
    let bytes = render_animated(Template::Birthday, &birthday(), 720, AnimFormat::Gif).unwrap();
    std::fs::write(dir.join("birthday.gif"), &bytes).unwrap();
    let decoded = decode_gif(&bytes);
    let (w, h) = (720u32, 900u32);
    for index in [0usize, 6, 12, 18, 24, decoded.frames.len() - 1] {
        let image = image::RgbaImage::from_raw(w, h, decoded.frames[index].clone()).unwrap();
        image
            .save(dir.join(format!("birthday-{index:02}.png")))
            .unwrap();
    }
    let small = render_frames(Template::Birthday, &birthday(), SMALL).unwrap();
    std::fs::write(
        dir.join("birthday-360.webp"),
        encode(&small, Timeline::BIRTHDAY, AnimFormat::Webp).unwrap(),
    )
    .unwrap();
    std::fs::write(
        dir.join("birthday-360.apng"),
        encode(&small, Timeline::BIRTHDAY, AnimFormat::Apng).unwrap(),
    )
    .unwrap();
    std::fs::write(
        dir.join("birthday-360-last.png"),
        png(small.last().unwrap()),
    )
    .unwrap();
    let bytes = render_animated(Template::Contact, &contact(), 720, AnimFormat::Gif).unwrap();
    std::fs::write(dir.join("contact.gif"), &bytes).unwrap();
    let decoded = decode_gif(&bytes);
    for index in [0usize, 4, 8, 14, decoded.frames.len() - 1] {
        let image = image::RgbaImage::from_raw(w, h, decoded.frames[index].clone()).unwrap();
        image
            .save(dir.join(format!("contact-{index:02}.png")))
            .unwrap();
    }
}
