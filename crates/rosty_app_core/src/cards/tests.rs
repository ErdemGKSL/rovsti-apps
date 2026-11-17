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

// ---- the card templates (X.2 step 2, round 16 step 8 layout fixes)

use std::collections::BTreeMap;
use std::path::PathBuf;

fn spec(template: Template, palette: Palette, texts: &[(&str, &str)]) -> CardSpec {
    CardSpec {
        template,
        palette,
        animated: false,
        texts: texts
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect::<BTreeMap<_, _>>(),
        photo: None,
        initials: "AY".to_owned(),
        qr: None,
    }
}

fn birthday_tr(name: &str) -> CardSpec {
    spec(
        Template::Birthday,
        Palette::Dawn,
        &[
            ("title", &format!("Doğum günün kutlu olsun, {name}!")),
            ("age_line", "36 yaşına giriyor"),
            ("date_line", "3 Ekim"),
        ],
    )
}

/// The phone-number contact of the owner's device report: the name is the number, no birthday.
const NUMBER_NAME: &str = "+90 555 000 12 34";

fn number_contact() -> CardSpec {
    let mut s = spec(
        Template::Contact,
        Palette::Ink,
        &[("title", NUMBER_NAME), ("numbers", NUMBER_NAME)],
    );
    s.initials = "+9".to_owned();
    s.qr = Some(format!(
        "BEGIN:VCARD\nVERSION:3.0\nTEL:{NUMBER_NAME}\nEND:VCARD\n"
    ));
    s
}

fn review_dir() -> PathBuf {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../target/checks/cards");
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn decode_png(path: &std::path::Path) -> image::RgbaImage {
    image::open(path).unwrap().to_rgba8()
}

/// Every text run lies inside the panel's padded inner box, on at most `max_lines` lines per block.
fn assert_text_inside(spec: &CardSpec, w: u32, h: u32) {
    let prepared = Prepared::new(spec, w, h).unwrap();
    let inner = prepared.geometry.inner();
    let boxes = prepared.text_boxes().unwrap();
    assert!(!boxes.is_empty(), "{:?}: no text measured", spec.template);
    for (text, rect) in &boxes {
        assert!(
            inner.contains(rect, 1.0),
            "{:?} {w}x{h}: {text:?} at {rect:?} leaves the inner panel {inner:?}",
            spec.template
        );
    }
}

#[test]
fn every_template_and_palette_renders_a_card_of_the_right_size() {
    let dir = review_dir();
    for template in Template::ALL {
        for palette in Palette::ALL {
            let mut s = spec(
                template,
                palette,
                &[
                    ("title", "Happy birthday, Ayşe Yılmaz!"),
                    ("age_line", "Turns 36"),
                    ("date_line", "October 3"),
                    ("subtitle", "My new number"),
                    ("numbers", "+90 532 000 11 22\n+90 212 000 33 44"),
                ],
            );
            if template != Template::Birthday {
                s.texts.insert("title".into(), "Ayşe Yılmaz".into());
            }
            s.qr = Some("BEGIN:VCARD\nFN:Ayşe Yılmaz\nEND:VCARD".into());
            let out = dir.join(format!("{template:?}-{palette:?}.png").to_lowercase());
            let done = render(&s, &out, &|| false).unwrap();
            assert_eq!(
                (done.width, done.height, done.frames),
                (STILL.0, STILL.1, 1)
            );
            let img = decode_png(&out);
            assert_eq!(img.dimensions(), STILL);
            assert_eq!(done.bytes, std::fs::metadata(&out).unwrap().len());
        }
    }
}

#[test]
fn a_long_turkish_headline_wraps_inside_the_panel() {
    let long = birthday_tr("Ayşe Yılmaz Karadenizoğlu Çağlayangil");
    for (w, h) in [STILL, ANIMATED] {
        assert_text_inside(&long, w, h);
        let prepared = Prepared::new(&long, w, h).unwrap();
        let boxes = prepared.text_boxes().unwrap();
        let first = boxes
            .iter()
            .find(|(t, _)| t.starts_with("Doğum"))
            .unwrap()
            .1;
        let title_lines = boxes
            .iter()
            .filter(|(_, r)| (r.h - first.h).abs() < 0.5)
            .map(|(_, r)| r.y.round() as i32)
            .collect::<std::collections::BTreeSet<_>>();
        assert!(
            (2..=3).contains(&title_lines.len()),
            "the headline wraps on 2-3 lines: {boxes:?}"
        );
    }
    // The owner's report: the short Turkish greeting with a two-word name.
    assert_text_inside(&birthday_tr("Ayşe Yılmaz"), STILL.0, STILL.1);
    let out = review_dir().join("birthday-long-tr.png");
    render(&long, &out, &|| false).unwrap();
}

#[test]
fn an_endless_name_shrinks_then_ends_in_an_ellipsis_inside_the_panel() {
    let name = "Wolfeschlegelsteinhausenbergerdorffvoralternwarengewissenhaftschaferswessenschafewarenwohlgepflegt";
    for template in Template::ALL {
        let mut s = spec(
            template,
            Palette::Lagoon,
            &[
                ("title", &format!("Doğum günün kutlu olsun, {name} {name}!")),
                ("subtitle", "Yeni numaram"),
                ("numbers", "+90 532 000 11 22 33 44 55 66 77 88 99 00 11 22"),
            ],
        );
        if template != Template::Birthday {
            s.texts.insert("title".into(), format!("{name} {name}"));
        }
        assert_text_inside(&s, STILL.0, STILL.1);
        let prepared = Prepared::new(&s, STILL.0, STILL.1).unwrap();
        assert!(
            prepared
                .text_boxes()
                .unwrap()
                .iter()
                .any(|(t, _)| t.contains('…'))
        );
        render(
            &s,
            &review_dir().join(format!("{template:?}-endless.png").to_lowercase()),
            &|| false,
        )
        .unwrap();
    }
}

#[test]
fn a_contact_named_with_its_number_and_no_birthday_renders_every_template() {
    let contact = number_contact();
    assert_text_inside(&contact, STILL.0, STILL.1);
    let out = review_dir().join("contact-number-name.png");
    let done = render(&contact, &out, &|| false).unwrap();
    assert_eq!((done.width, done.height), STILL);
    // The same contact on the other templates: no age and no date line.
    let birthday = spec(
        Template::Birthday,
        Palette::Ink,
        &[("title", &format!("Doğum günün kutlu olsun, {NUMBER_NAME}!"))],
    );
    assert_text_inside(&birthday, STILL.0, STILL.1);
    render(
        &birthday,
        &review_dir().join("birthday-number-name.png"),
        &|| false,
    )
    .unwrap();
    let number = spec(
        Template::NewNumber,
        Palette::Ink,
        &[
            ("title", NUMBER_NAME),
            ("subtitle", "Yeni numaram"),
            ("numbers", NUMBER_NAME),
        ],
    );
    render(
        &number,
        &review_dir().join("newnumber-number-name.png"),
        &|| false,
    )
    .unwrap();
}

#[test]
fn the_contact_qr_decodes_back_to_the_vcard() {
    let contact = number_contact();
    let out = review_dir().join("contact-qr-decode.png");
    render(&contact, &out, &|| false).unwrap();
    let img = image::DynamicImage::ImageRgba8(decode_png(&out)).to_luma8();
    let texts = rosty_core::contact_scan::decode_luma(img.width(), img.height(), img.as_raw());
    assert_eq!(texts, vec![contact.qr.clone().unwrap()]);
}

#[test]
fn a_missing_title_is_text_missing_and_a_bad_photo_draws_initials() {
    let empty = spec(Template::Birthday, Palette::Dawn, &[("title", "  ")]);
    let out = review_dir().join("never.png");
    let err = render(&empty, &out, &|| false).unwrap_err();
    assert_eq!(err.code(), "text_missing");
    assert!(!out.exists());

    let dir = std::env::temp_dir().join(format!("rosty-cards-photo-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let junk = dir.join("junk.jpg");
    std::fs::write(&junk, b"not a picture").unwrap();
    let mut with_junk = birthday_tr("Ayşe");
    with_junk.photo = Some(junk);
    let a = dir.join("junk.png");
    render(&with_junk, &a, &|| false).unwrap();
    let mut without = birthday_tr("Ayşe");
    without.photo = Some(dir.join("missing.jpg"));
    let b = dir.join("missing.png");
    render(&without, &b, &|| false).unwrap();
    assert_eq!(std::fs::read(&a).unwrap(), std::fs::read(&b).unwrap());
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn an_unwritable_output_is_out_unwritable() {
    let dir = std::env::temp_dir().join(format!("rosty-cards-ro-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    // A file where the folder should be.
    let blocker = dir.join("blocker");
    std::fs::write(&blocker, b"x").unwrap();
    let err = render(&birthday_tr("Ayşe"), &blocker.join("card.png"), &|| false).unwrap_err();
    assert_eq!(err.code(), "out_unwritable");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_moving_card_plays_once_and_ends_on_the_still_render() {
    let dir = review_dir();
    let mut moving = birthday_tr("Ayşe Yılmaz Karadenizoğlu Çağlayangil");
    moving.animated = true;
    let out = dir.join("birthday-animated.gif");
    let done = render(&moving, &out, &|| false).unwrap();
    assert_eq!((done.width, done.height), ANIMATED);
    assert!(done.frames >= 30, "{}", done.frames);
    let bytes = std::fs::read(&out).unwrap();
    assert_eq!(&bytes[..6], b"GIF89a");
    assert!(
        !bytes.windows(11).any(|w| w == b"NETSCAPE2.0"),
        "a looping block would make the GIF play forever"
    );
    assert!(bytes.len() < 3 * 1024 * 1024, "{} bytes", bytes.len());

    // The held frame is the still card at the moving size (the GIF palette rounds colours a little).
    let still = Prepared::new(&moving, ANIMATED.0, ANIMATED.1)
        .unwrap()
        .bitmap(&motion::Motion::rest())
        .unwrap();
    let frames = image::codecs::gif::GifDecoder::new(std::io::Cursor::new(&bytes)).unwrap();
    let last = image::AnimationDecoder::into_frames(frames)
        .collect_frames()
        .unwrap()
        .pop()
        .unwrap();
    let last = last.buffer();
    let raw = still.as_raw();
    let mut far = 0usize;
    for (i, px) in last.pixels().enumerate() {
        let diff = (0..3)
            .map(|c| (i32::from(px[c]) - i32::from(raw[i * 4 + c])).abs())
            .max()
            .unwrap_or(0);
        if diff > 24 {
            far += 1;
        }
    }
    assert!(
        far * 1000 < raw.len() / 4,
        "{far} pixels of the last frame differ from the still"
    );

    // Contact moves too; New number is still only.
    let mut contact = number_contact();
    contact.animated = true;
    let done = render(&contact, &dir.join("contact-animated.gif"), &|| false).unwrap();
    assert!(done.frames > 1);
    let mut number = spec(
        Template::NewNumber,
        Palette::Dusk,
        &[
            ("subtitle", "My new number"),
            ("numbers", "+90 532 000 11 22"),
        ],
    );
    number.animated = true;
    let done = render(&number, &dir.join("newnumber-still.png"), &|| false).unwrap();
    assert_eq!((done.frames, done.width), (1, STILL.0));
}

#[test]
fn a_cancelled_render_leaves_no_file() {
    let mut moving = birthday_tr("Ayşe");
    moving.animated = true;
    let out = std::env::temp_dir().join(format!("rosty-cards-cancel-{}.gif", std::process::id()));
    let asked = std::sync::atomic::AtomicU32::new(0);
    let err = render(&moving, &out, &|| {
        asked.fetch_add(1, std::sync::atomic::Ordering::Relaxed) >= 3
    })
    .unwrap_err();
    assert_eq!(err.code(), "cancelled");
    assert!(!out.exists());
}

#[test]
fn cards_are_deterministic() {
    let dir = std::env::temp_dir().join(format!("rosty-cards-det-{}", std::process::id()));
    let s = number_contact();
    let a = render(&s, &dir.join("a.png"), &|| false).unwrap();
    let b = render(&s, &dir.join("b.png"), &|| false).unwrap();
    assert_eq!(a, b);
    assert_eq!(
        std::fs::read(dir.join("a.png")).unwrap(),
        std::fs::read(dir.join("b.png")).unwrap()
    );
    let _ = std::fs::remove_dir_all(&dir);
}

/// Writes frames 0, 6, 12, 18, 24 and the last of the moving Birthday card to `target/checks/cards/frames/`.
#[test]
fn writes_birthday_frames_for_review() {
    let dir = review_dir().join("frames");
    std::fs::create_dir_all(&dir).unwrap();
    let s = birthday_tr("Ayşe Yılmaz");
    let prepared = Prepared::new(&s, ANIMATED.0, ANIMATED.1).unwrap();
    let frames = motion::Timeline::of(Template::Birthday, HOLD_MS)
        .unwrap()
        .frames();
    let last = frames.len() - 1;
    for i in [0, 6, 12, 18, 24, last] {
        let bitmap = prepared.bitmap(&frames[i].motion).unwrap();
        let mut png = Vec::new();
        takumi::write_image(&bitmap, &mut png, OutputFormat::Png).unwrap();
        std::fs::write(dir.join(format!("birthday-{i:02}.png")), png).unwrap();
    }
}

/// Frame proof of round 18 step 3: with `WRITE_CARD_FRAMES=1`, the 720 px optics frames of the Dawn Turkish Birthday
/// card (0, 3, 6, 9, 12, 15, 18, 24, last) and of the English Contact card (0, 8, last) go to
/// `target/checks/cards/frames/`. Opened by eye; without the variable the test does nothing.
#[test]
fn writes_720_px_optics_frames_for_review() {
    if !std::env::var("WRITE_CARD_FRAMES").is_ok_and(|v| v == "1") {
        return;
    }
    let dir = review_dir().join("frames");
    std::fs::create_dir_all(&dir).unwrap();
    let specs = golden_specs();
    let pick = |name: &str| specs.iter().find(|(n, _)| *n == name).unwrap().1.clone();
    for (name, template, wanted) in [
        (
            "birthday-dawn-tr",
            Template::Birthday,
            &[0usize, 3, 6, 9, 12, 15, 18, 24][..],
        ),
        ("contact-dawn-en", Template::Contact, &[0, 8][..]),
    ] {
        let prepared = Prepared::new(&pick(name), 720, 900).unwrap();
        let mut timeline = motion::Timeline::of(template, 600).unwrap();
        timeline.moving_s = 2.6;
        let frames = timeline.frames();
        let last = frames.len() - 1;
        for &i in wanted.iter().chain(std::iter::once(&last)) {
            let bitmap = prepared.bitmap(&frames[i].motion).unwrap();
            let mut png = Vec::new();
            takumi::write_image(&bitmap, &mut png, OutputFormat::Png).unwrap();
            std::fs::write(dir.join(format!("{name}-{i:02}.png")), png).unwrap();
            if template == Template::Contact && i == last {
                let luma = image::DynamicImage::ImageRgba8(
                    image::RgbaImage::from_raw(720, 900, bitmap.as_raw().to_vec()).unwrap(),
                )
                .to_luma8();
                let texts = rosty_core::contact_scan::decode_luma(
                    luma.width(),
                    luma.height(),
                    luma.as_raw(),
                );
                assert_eq!(texts, vec![pick(name).qr.unwrap()], "QR of the last frame");
            }
        }
    }
}

/// The cards the Contacts screenshots show (`StudioSamples` in the Contacts tests): their texts as `cardTexts` builds
/// them in English and Turkish. Kept in `tests/cards/`; `UPDATE_CARD_GOLDENS=1` rewrites them.
fn golden_specs() -> Vec<(&'static str, CardSpec)> {
    let vcard = "BEGIN:VCARD\nFN:Ayşe Yılmaz\nEND:VCARD";
    let mut contact = spec(
        Template::Contact,
        Palette::Dawn,
        &[
            ("name", "Ayşe Yılmaz"),
            ("title", "Ayşe Yılmaz"),
            ("numbers", "+90 532 000 11 22"),
        ],
    );
    contact.qr = Some(vcard.to_owned());
    vec![
        (
            "birthday-dawn-en",
            spec(
                Template::Birthday,
                Palette::Dawn,
                &[
                    ("name", "Ayşe Yılmaz"),
                    ("title", "Happy birthday, Ayşe Yılmaz!"),
                    ("age_line", "Turning 36"),
                    ("date_line", "October 3"),
                ],
            ),
        ),
        (
            "birthday-dawn-tr",
            spec(
                Template::Birthday,
                Palette::Dawn,
                &[
                    ("name", "Ayşe Yılmaz"),
                    ("title", "Doğum günün kutlu olsun, Ayşe Yılmaz!"),
                    ("age_line", "36 yaşına giriyorsun"),
                    ("date_line", "3 Ekim"),
                ],
            ),
        ),
        ("contact-dawn-en", contact),
        (
            "birthday-ink-tr",
            spec(
                Template::Birthday,
                Palette::Ink,
                &[
                    ("name", "Ayşe Yılmaz"),
                    ("title", "Doğum günün kutlu olsun, Ayşe Yılmaz!"),
                    ("age_line", "36 yaşına giriyorsun"),
                    ("date_line", "3 Ekim"),
                ],
            ),
        ),
        ("contact-lagoon-en", {
            let mut s = spec(
                Template::Contact,
                Palette::Lagoon,
                &[
                    ("name", "Ayşe Yılmaz"),
                    ("title", "Ayşe Yılmaz"),
                    ("numbers", "+90 532 000 11 22\n+90 212 000 33 44"),
                ],
            );
            s.qr = Some(vcard.to_owned());
            s
        }),
        (
            "number-meadow-tr",
            spec(
                Template::NewNumber,
                Palette::Meadow,
                &[
                    ("name", "Ayşe Yılmaz"),
                    ("title", "Ayşe Yılmaz"),
                    ("subtitle", "Yeni numaram"),
                    ("numbers", "+90 532 000 11 22"),
                ],
            ),
        ),
        // The owner's long case: a 40-character Turkish name.
        ("birthday-long-name-tr", {
            let name = "Ayşe Yılmaz Karadenizoğlu Çağlayangiller";
            assert_eq!(name.chars().count(), 40);
            let mut s = birthday_tr(name);
            s.texts.insert("name".into(), name.to_owned());
            s
        }),
        ("contact-number-name", number_contact()),
    ]
}

#[test]
fn card_goldens_match() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/cards");
    let update = std::env::var("UPDATE_CARD_GOLDENS").is_ok_and(|v| v == "1");
    let tmp = std::env::temp_dir().join(format!("rosty-cards-golden-{}", std::process::id()));
    for (name, s) in golden_specs() {
        assert_text_inside(&s, STILL.0, STILL.1);
        let golden = dir.join(format!("{name}.png"));
        if update {
            std::fs::create_dir_all(&dir).unwrap();
            render(&s, &golden, &|| false).unwrap();
            continue;
        }
        let out = tmp.join(format!("{name}.png"));
        render(&s, &out, &|| false).unwrap();
        let (a, b) = (decode_png(&golden), decode_png(&out));
        assert_eq!(a.dimensions(), b.dimensions(), "{name}");
        let off = a
            .pixels()
            .zip(b.pixels())
            .filter(|(p, q)| (0..4).any(|c| p[c].abs_diff(q[c]) > 2))
            .count();
        let total = (a.width() * a.height()) as usize;
        assert!(
            off * 1000 <= total,
            "{name}: {off} of {total} pixels differ (UPDATE_CARD_GOLDENS=1 rewrites)"
        );
    }
    let _ = std::fs::remove_dir_all(tmp);
}

// ---- budgets (r17-s6) -------------------------------------------------------------------------------------------

/// Budgets are asserted at 2x the target, only when `RUN_BUDGETS=1` (the checks profile, desktop proxy): wall-clock
/// limits are noise on a loaded CI box. The measured times are always printed.
fn budgets_on() -> bool {
    std::env::var("RUN_BUDGETS").is_ok_and(|v| v == "1")
}

/// Static budget 150 ms, asserted at 300 ms. One warm-up render first (font and style caches), best of three.
#[test]
fn budget_static_render_under_300_ms() {
    let spec = birthday_tr("Ayşe");
    let out = std::env::temp_dir().join(format!("rosty-cards-budget-s-{}.png", std::process::id()));
    render(&spec, &out, &|| false).unwrap();
    let best = (0..3)
        .map(|_| {
            let t = std::time::Instant::now();
            render(&spec, &out, &|| false).unwrap();
            t.elapsed()
        })
        .min()
        .unwrap();
    let size = std::fs::metadata(&out).unwrap().len();
    let _ = std::fs::remove_file(&out);
    eprintln!("BUDGET static 720x900: {best:?}, {size} bytes");
    if budgets_on() {
        assert!(best.as_millis() < 300, "static render took {best:?}");
    }
}

/// GIF budget 1.5 s, asserted at 3 s, for the 720 px, 39-frame birthday card (the optics' size and frame count).
/// The shipped animated size is [`ANIMATED`]; the 720 px render runs through [`Prepared`] with a 2.6 s timeline.
#[test]
fn budget_birthday_gif_under_3_s() {
    let spec = birthday_tr("Ayşe");
    let mut timeline = motion::Timeline::of(Template::Birthday, 600).unwrap();
    timeline.moving_s = 2.6;
    let frames = timeline.frames().len();
    assert_eq!(frames, 39, "38 moving frames, the 39th is the held still");
    let dir = std::env::temp_dir().join(format!("rosty-cards-budget-g-{}", std::process::id()));
    let gif = |w: u32, h: u32| {
        let prepared = Prepared::new(&spec, w, h).unwrap();
        let out = dir.join(format!("b{w}.gif"));
        let t = std::time::Instant::now();
        prepared.write(&out, Some(&timeline), &|| false).unwrap();
        (t.elapsed(), std::fs::metadata(&out).unwrap().len())
    };
    let (t720, bytes720) = gif(720, 900);
    let (t540, bytes540) = gif(ANIMATED.0, ANIMATED.1);
    let _ = std::fs::remove_dir_all(&dir);
    // Size only: the same frames as an animated WebP (lossy q75), to compare with the GIF. WebP is not shipped.
    let prepared = Prepared::new(&spec, 720, 900).unwrap();
    let webp_frames: Vec<_> = timeline
        .frames()
        .iter()
        .map(|f| {
            takumi::prelude::AnimationFrame::new(prepared.bitmap(&f.motion).unwrap(), f.duration_ms)
        })
        .collect();
    let mut webp = Vec::new();
    let options = takumi::prelude::AnimatedWebpOptions::builder()
        .loop_count(Some(0))
        .lossless(false)
        .quality(75)
        .build();
    let t = std::time::Instant::now();
    takumi::write_animated_webp(std::borrow::Cow::Owned(webp_frames), &mut webp, options).unwrap();
    eprintln!(
        "BUDGET webp 720x900 x40 frames: {} bytes, encode {:?}",
        webp.len(),
        t.elapsed()
    );
    eprintln!("BUDGET gif 720x900 x40 frames: {t720:?}, {bytes720} bytes");
    eprintln!("BUDGET gif 540x675 x40 frames: {t540:?}, {bytes540} bytes");
    assert!(bytes720 < 3 * 1024 * 1024, "GIF under 3 MB: {bytes720}");
    if budgets_on() {
        assert!(t720.as_secs_f32() < 3.0, "720 px GIF took {t720:?}");
    }
}

/// A cancelled animated job deletes its partial file: no `.gif` and no `.part` stay next to `out`, whether the
/// cancel arrives before the first frame, mid-render or at the end.
#[test]
fn a_cancelled_job_leaves_no_partial_file() {
    let dir = std::env::temp_dir().join(format!("rosty-cards-cancel-d-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut moving = birthday_tr("Ayşe");
    moving.animated = true;
    for after in [0u32, 5, 31, 32, 33] {
        let out = dir.join(format!("c{after}.gif"));
        let asked = std::sync::atomic::AtomicU32::new(0);
        let result = render(&moving, &out, &|| {
            asked.fetch_add(1, std::sync::atomic::Ordering::Relaxed) >= after
        });
        if let Err(e) = result {
            assert_eq!(e.code(), "cancelled", "after {after} asks");
            assert!(!out.exists(), "no file after {after} asks");
        }
        let left: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .filter(|n| n.to_string_lossy().ends_with(".part"))
            .collect();
        assert!(
            left.is_empty(),
            "no .part file after {after} asks: {left:?}"
        );
        let _ = std::fs::remove_file(&out);
    }
    let _ = std::fs::remove_dir_all(&dir);
}

/// The Birthday timeline: 39 frames in 2.6 s at 15 fps, the last one the rest pose.
fn birthday_frames() -> Vec<motion::Frame> {
    motion::Timeline::of(Template::Birthday, HOLD_MS)
        .unwrap()
        .frames()
}

/// How many pixels differ clearly between two bitmaps of the same size.
fn differing_pixels(a: &Bitmap, b: &Bitmap) -> usize {
    a.as_raw()
        .as_chunks::<4>()
        .0
        .iter()
        .zip(b.as_raw().as_chunks::<4>().0)
        .filter(|(p, q)| p.iter().zip(q.iter()).any(|(m, n)| m.abs_diff(*n) > 6))
        .count()
}

#[test]
fn the_birthday_gif_has_39_frames_over_2_6_seconds() {
    let frames = birthday_frames();
    assert_eq!(frames.len(), 39);
    let moving: u32 = frames[..38].iter().map(|f| f.duration_ms).sum();
    assert!((2500..=2600).contains(&moving), "{moving} ms");
    assert_eq!(frames[38].motion, motion::Motion::rest());
    // The squash keeps the area, and the spring tokens drive it.
    for f in &frames {
        assert!((f.motion.sx * f.motion.sy - 1.0).abs() < 1e-4);
    }
}

#[test]
fn glass_optics_bend_the_card_in_frames_8_to_20() {
    let s = birthday_tr("Ayşe Yılmaz");
    let prepared = Prepared::new(&s, ANIMATED.0, ANIMATED.1).unwrap();
    let frames = birthday_frames();
    for (i, frame) in frames.iter().enumerate().take(21).skip(8) {
        // The same frame with the panel flat (scale 0), no lens and no glint.
        let mut flat = frame.motion.clone();
        flat.refract = 0.0;
        flat.lens = None;
        flat.glint_x = None;
        let bent = prepared.bitmap(&frame.motion).unwrap();
        let plain = prepared.bitmap(&flat).unwrap();
        let moved = differing_pixels(&bent, &plain);
        assert!(moved > 200, "frame {i}: only {moved} pixels bend");
    }
    // The lens alone bends the name; the glint alone lights the rim.
    let mid = frames[10].motion.clone();
    assert!(mid.lens.is_some(), "the lens is out in frame 10");
    let mut no_lens = mid.clone();
    no_lens.lens = None;
    let with = prepared.bitmap(&mid).unwrap();
    let without = prepared.bitmap(&no_lens).unwrap();
    assert!(differing_pixels(&with, &without) > 300);
    let lit = frames[18].motion.clone();
    assert!(lit.glint_x.is_some(), "the glint travels in frame 18");
    let mut dark = lit.clone();
    dark.glint_x = None;
    assert!(
        differing_pixels(
            &prepared.bitmap(&lit).unwrap(),
            &prepared.bitmap(&dark).unwrap()
        ) > 100
    );
}

#[test]
fn the_birthday_gif_is_deterministic_and_small() {
    let dir = std::env::temp_dir().join(format!("rosty-cards-gif-{}", std::process::id()));
    let mut s = birthday_tr("Ayşe Yılmaz");
    s.animated = true;
    let a = render(&s, &dir.join("a.gif"), &|| false).unwrap();
    let b = render(&s, &dir.join("b.gif"), &|| false).unwrap();
    assert_eq!(a, b);
    assert_eq!(a.frames, 39);
    let (x, y) = (
        std::fs::read(dir.join("a.gif")).unwrap(),
        std::fs::read(dir.join("b.gif")).unwrap(),
    );
    assert_eq!(x, y, "the same spec renders the same bytes");
    assert!(x.len() < 3 * 1024 * 1024, "{} bytes", x.len());
    assert!(!x.windows(11).any(|w| w == b"NETSCAPE2.0"), "plays once");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_still_frames_carry_no_lens_and_no_glint() {
    for f in birthday_frames().iter().skip(31) {
        assert!(f.motion.lens.is_none(), "no lens after 1.4 s");
        assert!(f.motion.glint_x.is_none(), "no glint after 1.6 s");
    }
    let last = birthday_frames().pop().unwrap();
    assert_eq!(last.motion, motion::Motion::rest());
    assert!(last.motion.lens.is_none() && last.motion.glint_x.is_none());
}
